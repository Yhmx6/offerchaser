package com.offerchaser.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.offerchaser.application.entity.BlacklistEntity;
import com.offerchaser.application.entity.BossConfigEntity;
import com.offerchaser.application.entity.BossIndustryEntity;
import com.offerchaser.application.entity.BossOptionEntity;
import com.offerchaser.application.mapper.BlacklistMapper;
import com.offerchaser.application.mapper.BossJobDataMapper;
import com.offerchaser.application.entity.BossJobDataEntity;
import com.offerchaser.application.mapper.BossConfigMapper;
import com.offerchaser.application.mapper.BossIndustryMapper;
import com.offerchaser.application.mapper.BossOptionMapper;
import com.offerchaser.worker.boss.BossConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boss 数据访问服务：统一封装 boss_option / boss_industry / boss_config / boss_blacklist /
 * boss_data 的读写，以及统计图表与任务运行配置的装配。
 * <p>
 * 不直接执行 Playwright 操作；数据库迁移与版本管理在 persistence 层完成。
 */
@Service
@Slf4j
// 注意：@RequiredArgsConstructor 按字段声明顺序生成构造器；测试会直接 new 7 参构造，字段顺序不要随意调整
@RequiredArgsConstructor
public class BossService {

    /** Boss 城市筛选中“全国”的代码（真实城市项，与“不限”0 语义不同）。 */
    private static final String NATIONWIDE_CITY_CODE = "100010000";

    /** 投递终态：已投递 / 投递失败 / 状态未知，快照刷新不得把它们回退成 未投递 / 已过滤。 */
    private static final Set<String> TERMINAL_DELIVERY_STATUSES = Set.of("已投递", "投递失败", "状态未知");

    /** boss_option 中“不限”的代码，也是名称/代码转换失败时的兜底值。 */
    private static final String DEFAULT_OPTION_CODE = "0";
    private static final String CITY_OPTION_TYPE = "city";
    /** 黑名单类型（boss_blacklist.type）。 */
    private static final String BLACKLIST_COMPANY = "company";
    private static final String BLACKLIST_RECRUITER = "recruiter";
    private static final String BLACKLIST_JOB = "job";

    /** 选项/行业/配置/黑名单/岗位数据 Mapper（注册在 application.mapper，由 @MapperScan 发现）。 */
    private final BossOptionMapper bossOptionMapper;
    private final BossIndustryMapper bossIndustryMapper;
    /** 全局配置 Mapper（boss_config 通常只有一行）。 */
    private final BossConfigMapper bossConfigMapper;
    private final BlacklistMapper blacklistMapper;
    /** 岗位采集快照 Mapper（boss_data）。 */
    private final BossJobDataMapper bossJobDataMapper;
    /** SQLite 数据源：列补全、统计 SQL、刷新计数直接使用。 */
    private final javax.sql.DataSource dataSource;
    /** B4：岗位搜索来源（同岗位多关键词只增加来源关联，不重复岗位）。 */
    private final com.offerchaser.application.persistence.JobSourceRepository jobSourceRepository;

    @org.springframework.beans.factory.annotation.Value("${offerchaser.boss.budget-account:local}")
    private String metricsAccount = "local";

    /** 为旧数据库补充开关，不覆盖已有配置；新空库也会在首次启动时补齐。 */
    @jakarta.annotation.PostConstruct
    public void ensureRepeatChatColumn() {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> columns = bossConfigColumns(connection);
            if (columns.isEmpty()) {
                // boss_config 尚未建表（初始化顺序靠后），没有可补的列
                return;
            }
            addBossConfigColumnIfMissing(connection, columns, "allow_repeat_chat",
                    "ALTER TABLE boss_config ADD COLUMN allow_repeat_chat INTEGER NOT NULL DEFAULT 0");
            addBossConfigColumnIfMissing(connection, columns, "repeat_chat_messages",
                    "ALTER TABLE boss_config ADD COLUMN repeat_chat_messages TEXT");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("无法初始化Boss重复沟通配置", e);
        }
    }

    /** 读取表当前的列名；表不存在时返回空集合。 */
    private Set<String> bossConfigColumns(Connection connection) throws java.sql.SQLException {
        Set<String> columns = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info('boss_config')")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }

    /** 缺列才补；补完把列名加入内存集合，避免同一次调用重复执行 DDL。 */
    private void addBossConfigColumnIfMissing(Connection connection, Set<String> columns,
                                              String columnName, String alterSql)
            throws java.sql.SQLException {
        if (columns.contains(columnName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(alterSql);
        }
        columns.add(columnName);
        if ("allow_repeat_chat".equals(columnName)) {
            log.info("已添加Boss重复沟通开关，默认关闭");
        }
    }

    // ---- boss_option 下拉项：默认项补齐 + 排序 ----

    /** 按类型返回下拉选项；city/industry 额外保证“不限/全国”默认项存在并按 sort_order 排序。 */
    public List<BossOptionEntity> getOptionsByType(String type) {
        prepareDefaultOptions(type);
        QueryWrapper<BossOptionEntity> query = new QueryWrapper<BossOptionEntity>().eq("type", type);
        if (isOrderedOptionType(type)) {
            query.last("ORDER BY sort_order IS NULL, sort_order ASC, id ASC");
        } else {
            query.orderByAsc("id");
        }
        return bossOptionMapper.selectList(query);
    }

    /** 补齐固定默认项：所有类型补“不限”，城市额外补“全国”。 */
    private void prepareDefaultOptions(String type) {
        ensureOption(type, "不限", com.offerchaser.worker.utils.Constant.UNLIMITED_CODE, 0);
        if (!CITY_OPTION_TYPE.equals(type)) {
            return;
        }
        // “全国”是 Boss 的真实城市筛选项，code=0 会被跳过，必须与“不限”分开
        ensureOption(CITY_OPTION_TYPE, "全国", NATIONWIDE_CITY_CODE, 1);
    }

    /** city/industry 使用 sort_order 优先排序，其余类型仍按 id 升序。 */
    private static boolean isOrderedOptionType(String type) {
        return CITY_OPTION_TYPE.equals(type) || "industry".equals(type);
    }

    /** 某类型下缺少指定 code 的选项时插入（用于“不限”/“全国”这类固定默认项）。 */
    private void ensureOption(String type, String name, String code, int sortOrder) {
        QueryWrapper<BossOptionEntity> probe = new QueryWrapper<>();
        probe.eq("type", type);
        probe.eq("code", code);
        Long found = bossOptionMapper.selectCount(probe);
        if (found != null && found > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        BossOptionEntity option = new BossOptionEntity();
        option.setType(type);
        option.setName(name);
        option.setCode(code);
        option.setSortOrder(sortOrder);
        option.setCreatedAt(now);
        option.setUpdatedAt(now);
        bossOptionMapper.insert(option);
    }

    // ==================== 选项 / 行业 / 配置查询（同族封装统一走私有 helper） ====================

    /** 全部下拉选项（无附加条件）。 */
    public List<BossOptionEntity> getAllOptions() {
        return selectAll(bossOptionMapper);
    }

    /** 按类型 + 代码精确查选项。 */
    public BossOptionEntity getOptionByTypeAndCode(String type, String code) {
        return bossOptionMapper.selectOne(new QueryWrapper<BossOptionEntity>()
                .eq("type", type)
                .eq("code", code));
    }

    /** 名称 → 代码；找不到时返回 "0"（与城市/行业共用同一套查找逻辑）。 */
    public String getCodeByTypeAndName(String type, String name) {
        return optionCodeByName(type, name);
    }

    /** 城市名称 → 城市代码（Boss 下拉的城市项）。 */
    public String getCityCodeByName(String name) {
        return optionCodeByName(CITY_OPTION_TYPE, name);
    }

    /** 全部行业。 */
    public List<BossIndustryEntity> getAllIndustries() {
        return selectAll(bossIndustryMapper);
    }

    /** 行业代码 → 行业记录。 */
    public BossIndustryEntity getIndustryByCode(Integer code) {
        return bossIndustryMapper.selectById(code);
    }

    /** 行业名称 → 行业代码；找不到时返回 "0"。 */
    public String getIndustryCodeByName(String name) {
        BossIndustryEntity industry = bossIndustryMapper.selectOne(
                new QueryWrapper<BossIndustryEntity>().eq("name", name));
        return industry == null ? DEFAULT_OPTION_CODE : String.valueOf(industry.getCode());
    }

    /** 全部配置行。 */
    public List<BossConfigEntity> getAllConfigs() {
        return selectAll(bossConfigMapper);
    }

    /** 按主键取配置。 */
    public BossConfigEntity getConfigById(Long id) {
        return bossConfigMapper.selectById(id);
    }

    /** 第一行配置（boss_config 正常只有一行）。 */
    public BossConfigEntity getFirstConfig() {
        return firstConfig();
    }

    /** 新建配置行：created_at / updated_at 都取当前时间。 */
    public BossConfigEntity saveConfig(BossConfigEntity config) {
        LocalDateTime now = LocalDateTime.now();
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        bossConfigMapper.insert(config);
        return config;
    }

    /** 更新配置行：只刷新 updated_at。 */
    public BossConfigEntity updateConfig(BossConfigEntity config) {
        config.setUpdatedAt(LocalDateTime.now());
        bossConfigMapper.updateById(config);
        return config;
    }

    // ==================== 查询辅助（内部复用） ====================

    /** 无附加条件的全表查询；选项/行业/配置三个 Mapper 共用同一入口。 */
    private <T> List<T> selectAll(BaseMapper<T> mapper) {
        return mapper.selectList(null);
    }

    /** boss_option 的“名称→代码”查找；查不到返回 DEFAULT_OPTION_CODE。 */
    private String optionCodeByName(String type, String name) {
        BossOptionEntity option = bossOptionMapper.selectOne(new QueryWrapper<BossOptionEntity>()
                .eq("type", type)
                .eq("name", name));
        return option == null ? DEFAULT_OPTION_CODE : option.getCode();
    }

    /** boss_config 的第一行（用 LIMIT 1 避免多行时 selectOne 抛错）。 */
    private BossConfigEntity firstConfig() {
        return bossConfigMapper.selectOne(new QueryWrapper<BossConfigEntity>().last("LIMIT 1"));
    }

    /**
     * 保存/更新首条配置：请求里为 null 的字段保留库中原值（选择性补丁），
     * 库里没有任何配置时直接插入新行并写入创建/更新时间。
     */
    public BossConfigEntity saveOrUpdateFirstSelective(BossConfigEntity partial) {
        BossConfigEntity current = getFirstConfig();
        LocalDateTime now = LocalDateTime.now();
        if (current == null) {
            partial.setCreatedAt(now);
            partial.setUpdatedAt(now);
            bossConfigMapper.insert(partial);
            return partial;
        }
        mergeNonNullFields(current, partial);
        current.setUpdatedAt(now);
        bossConfigMapper.updateById(current);
        return current;
    }

    /** 选择性合并：请求体为 null 的字段表示“不修改”，非 null（含空字符串）覆盖原值。 */
    private void mergeNonNullFields(BossConfigEntity target, BossConfigEntity patch) {
        applyIfPresent(patch.getSayHi(), target::setSayHi);
        applyIfPresent(patch.getDebugger(), target::setDebugger);
        applyIfPresent(patch.getEnableAi(), target::setEnableAi);
        applyIfPresent(patch.getFilterDeadHr(), target::setFilterDeadHr);
        applyIfPresent(patch.getSendImgResume(), target::setSendImgResume);
        applyIfPresent(patch.getAllowRepeatChat(), target::setAllowRepeatChat);
        applyIfPresent(patch.getRepeatChatMessages(), target::setRepeatChatMessages);
        applyIfPresent(patch.getWaitTime(), target::setWaitTime);

        applyIfPresent(patch.getKeywords(), target::setKeywords);
        applyIfPresent(patch.getCityCode(), target::setCityCode);
        applyIfPresent(patch.getIndustry(), target::setIndustry);
        applyIfPresent(patch.getJobType(), target::setJobType);
        applyIfPresent(patch.getExperience(), target::setExperience);
        applyIfPresent(patch.getDegree(), target::setDegree);
        applyIfPresent(patch.getSalary(), target::setSalary);
        applyIfPresent(patch.getScale(), target::setScale);
        applyIfPresent(patch.getStage(), target::setStage);

        applyIfPresent(patch.getExpectedSalaryMin(), target::setExpectedSalaryMin);
        applyIfPresent(patch.getExpectedSalaryMax(), target::setExpectedSalaryMax);
        applyIfPresent(patch.getDeadStatus(), target::setDeadStatus);
    }

    private static <T> void applyIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /** 删除一条配置行；返回是否真的删掉了记录。 */
    public boolean deleteConfig(Long id) {
        int affected = bossConfigMapper.deleteById(id);
        return affected > 0;
    }

    // ---- boss_config 装配：库中一行配置 → Worker 使用的 BossConfig ----

    /** 读取首条配置并装配成任务运行配置；库为空时返回默认空配置。 */
    public BossConfig loadBossConfig() {
        BossConfigEntity stored = firstConfig();
        if (stored == null) {
            log.warn("boss_config 表为空，使用默认空配置");
            return new BossConfig();
        }
        return assembleBossConfig(stored);
    }

    /** 把 boss_config 行装配为 BossConfig：布尔用 0/1 判定，列表字段转成 Worker 需要的代码。 */
    private BossConfig assembleBossConfig(BossConfigEntity stored) {
        BossConfig config = new BossConfig();
        config.setSayHi(stored.getSayHi());
        config.setDebugger(flag(stored.getDebugger()));
        config.setEnableAI(flag(stored.getEnableAi()));
        config.setFilterDeadHR(flag(stored.getFilterDeadHr()));
        config.setSendImgResume(flag(stored.getSendImgResume()));
        config.setAllowRepeatChat(flag(stored.getAllowRepeatChat()));
        config.setRepeatChatMessages(stored.getRepeatChatMessages() != null
                ? stored.getRepeatChatMessages() : BossConfig.DEFAULT_REPEAT_CHAT_MESSAGES);
        config.setWaitTime(stored.getWaitTime() == null ? null : String.valueOf(stored.getWaitTime()));

        config.setKeywords(parseListString(stored.getKeywords()));
        // 城市/行业/经验/学历/规模/阶段：中文名或代码 -> Worker 使用的代码列表
        config.setCityCode(resolveCodes("city", stored.getCityCode()));
        config.setIndustry(resolveCodes("industry", stored.getIndustry()));
        config.setExperience(resolveCodes("experience", stored.getExperience()));
        config.setDegree(resolveCodes("degree", stored.getDegree()));
        config.setScale(resolveCodes("scale", stored.getScale()));
        config.setStage(resolveCodes("stage", stored.getStage()));
        config.setJobType(resolveJobType(stored.getJobType()));
        config.setSalary(resolveCodes("salary", stored.getSalary()));

        if (stored.getExpectedSalaryMin() != null || stored.getExpectedSalaryMax() != null) {
            config.setExpectedSalary(java.util.Arrays.asList(
                    stored.getExpectedSalaryMin() == null ? 0 : stored.getExpectedSalaryMin(),
                    stored.getExpectedSalaryMax() == null ? 0 : stored.getExpectedSalaryMax()));
        }
        config.setDeadStatus(parseListString(stored.getDeadStatus()));

        log.info("已从 boss_config 加载Boss配置，并完成括号列表解析");
        return config;
    }

    /** 0/1 整型开关转布尔；null 一律视为关闭。 */
    private static boolean flag(Integer value) {
        return value != null && value == 1;
    }

    /** 数据库中的名称/代码列表文本 -> 指定类型的代码列表。 */
    private List<String> resolveCodes(String type, String storedValue) {
        return toCodes(type, parseListString(storedValue));
    }

    /** 职位类型只取第一个代码；无法转换时回退到“不限”。 */
    private String resolveJobType(String storedJobType) {
        List<String> codes = resolveCodes("jobType", storedJobType);
        if (!codes.isEmpty()) {
            return codes.get(0);
        }
        String fallback = null;
        if (StringUtils.isNotBlank(storedJobType)) {
            BossOptionEntity byCode = getOptionByTypeAndCode("jobType", storedJobType);
            fallback = byCode != null && byCode.getCode() != null
                    ? byCode.getCode()
                    : getCodeByTypeAndName("jobType", storedJobType);
        }
        return StringUtils.isBlank(fallback) ? com.offerchaser.worker.utils.Constant.UNLIMITED_CODE : fallback;
    }

    // ==================== 列表文本 / 黑名单（同族方法合并到私有 helper） ====================

    /** 解析括号列表或逗号分隔文本，如 "[a,b,c]" 或 "a,b,c"；空值返回空列表。 */
    public List<String> parseListString(String raw) {
        String inner = stripListBrackets(raw);
        if (inner == null) {
            return Collections.emptyList();
        }
        return java.util.Arrays.stream(inner.split(","))
                .map(String::trim)
                .map(BossService::stripListQuotes)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }

    /** 列表 → 括号列表文本；空列表返回空串。 */
    public String toBracketListString(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return list.stream().collect(Collectors.joining(",", "[", "]"));
    }

    /** 代码列表 → 名称列表；已经是名称的原样保留。 */
    public List<String> toNames(String type, List<String> items) {
        return mapOptionItems(items, item -> optionNameOf(type, item));
    }

    /** 名称/代码列表 → 代码列表；无法识别的项回退 "0"。 */
    public List<String> toCodes(String type, List<String> items) {
        return mapOptionItems(items, item -> optionCodeOf(type, item));
    }

    /** city 值（code / name / 括号列表）→ 城市中文名。 */
    public String normalizeCityToName(String raw) {
        List<String> parts = parseListString(raw);
        String candidate = parts.isEmpty()
                ? (raw == null ? "" : raw.trim())
                : parts.get(0);
        if (candidate.isEmpty()) {
            return "";
        }
        BossOptionEntity byCode = getOptionByTypeAndCode(CITY_OPTION_TYPE, candidate);
        return byCode != null && byCode.getName() != null ? byCode.getName() : candidate;
    }

    // ==================== boss_blacklist ====================

    /** 某类型黑名单值集合。 */
    public Set<String> getBlacklistByType(String type) {
        return blacklistMapper.selectList(new LambdaQueryWrapper<BlacklistEntity>()
                        .eq(BlacklistEntity::getType, type))
                .stream()
                .map(BlacklistEntity::getValue)
                .collect(Collectors.toSet());
    }

    /** 公司黑名单。 */
    public Set<String> getBlackCompanies() {
        return getBlacklistByType(BLACKLIST_COMPANY);
    }

    /** 招聘者黑名单。 */
    public Set<String> getBlackRecruiters() {
        return getBlacklistByType(BLACKLIST_RECRUITER);
    }

    /** 职位黑名单。 */
    public Set<String> getBlackJobs() {
        return getBlacklistByType(BLACKLIST_JOB);
    }

    /** 添加黑名单项；已存在返回 false。 */
    public boolean addBlacklist(String type, String value) {
        if (blacklistMapper.selectCount(blacklistKey(type, value)) > 0) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        BlacklistEntity entry = new BlacklistEntity();
        entry.setType(type);
        entry.setValue(value);
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        return blacklistMapper.insert(entry) > 0;
    }

    /** 批量添加；逐项复用 addBlacklist 的“已存在则跳过”。 */
    public void addBlacklistBatch(String type, Set<String> values) {
        values.forEach(value -> addBlacklist(type, value));
    }

    /** 删除黑名单项；返回是否真的删除。 */
    public boolean removeBlacklist(String type, String value) {
        return blacklistMapper.delete(blacklistKey(type, value)) > 0;
    }

    // ==================== 列表 / 黑名单内部 helper ====================

    /** 去掉首尾空白与可选的一层方括号；无效输入（null / 空白）返回 null。 */
    private static String stripListBrackets(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        String inner = trimmed.trim();
        return inner.isEmpty() ? null : inner;
    }

    /** 去掉列表项两端的一对双引号（兼容 JSON 数组序列化存储）。 */
    private static String stripListQuotes(String item) {
        return item.replaceAll("^\"|\"$", "");
    }

    /** 通用列表映射：null / 空返回空列表，否则逐项转换。 */
    private List<String> mapOptionItems(List<String> items, Function<String, String> mapper) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        return items.stream().map(mapper).collect(Collectors.toList());
    }

    /** 单个代码 → 名称；不是有效代码时原样返回。 */
    private String optionNameOf(String type, String item) {
        BossOptionEntity byCode = getOptionByTypeAndCode(type, item);
        return byCode != null && byCode.getName() != null ? byCode.getName() : item;
    }

    /** 单个名称/代码 → 代码；未识别时回退 DEFAULT_OPTION_CODE。 */
    private String optionCodeOf(String type, String item) {
        BossOptionEntity byCode = getOptionByTypeAndCode(type, item);
        if (byCode != null && byCode.getCode() != null) {
            return byCode.getCode();
        }
        String byName = getCodeByTypeAndName(type, item);
        return byName != null ? byName : DEFAULT_OPTION_CODE;
    }

    /** 黑名单 (type, value) 条件，添加与删除共用。 */
    private LambdaQueryWrapper<BlacklistEntity> blacklistKey(String type, String value) {
        return new LambdaQueryWrapper<BlacklistEntity>()
                .eq(BlacklistEntity::getType, type)
                .eq(BlacklistEntity::getValue, value);
    }

    /** 全部黑名单行（管理台列表使用，不再按类型过滤）。 */
    public List<BlacklistEntity> getAllBlacklist() {
        return selectAll(blacklistMapper);
    }

    // ==================== boss_data（岗位数据）相关方法 ====================

    /**
     * 确保 boss_data 表具备全部业务列（安全的列补全）。
     * <p>
     * 列补全属于 SQLite 旧表迁移职责，实现已下沉到
     * {@link com.offerchaser.application.persistence.SchemaMigrationService#ensureBossDataColumnOrder(javax.sql.DataSource)}；
     * 本方法保留公开签名，供 Boss 启动链与既有调用方使用，行为不变。
     */
    public void ensureBossDataColumnOrder() {
        com.offerchaser.application.persistence.SchemaMigrationService.ensureBossDataColumnOrder(dataSource);
    }

    /** 岗位是否已存在：encrypt_id + encrypt_user_id 同时匹配（两参数任一为 null 视为无法判断）。 */
    public boolean existsBossJob(String encryptId, String encryptUserId) {
        if (encryptId == null || encryptUserId == null) {
            return false;
        }
        QueryWrapper<BossJobDataEntity> locator = new QueryWrapper<BossJobDataEntity>()
                .eq("encrypt_id", encryptId)
                .eq("encrypt_user_id", encryptUserId)
                .last("LIMIT 1");
        Long matched = bossJobDataMapper.selectCount(locator);
        return matched != null && matched > 0;
    }

    /** 降级判断：只看 encrypt_id 是否已有岗位记录（旧数据/缺 user id 时使用）。 */
    public boolean existsBossJobByEncryptId(String encryptId) {
        if (encryptId == null) {
            return false;
        }
        Long matched = bossJobDataMapper.selectCount(new QueryWrapper<BossJobDataEntity>()
                .eq("encrypt_id", encryptId)
                .last("LIMIT 1"));
        return matched != null && matched > 0;
    }

    /** 直接插入一条岗位快照；创建/更新/采集时间由本方法补齐。 */
    public void insertBossJob(BossJobDataEntity entity) {
        if (entity == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (entity.getFirstSeenAt() == null) {
            entity.setFirstSeenAt(now);
        }
        if (entity.getLastDetailAt() == null) {
            entity.setLastDetailAt(now);
        }
        bossJobDataMapper.insert(entity);
    }

    /**
     * 阶段 B B4：记录岗位搜索来源（幂等）。同岗位同关键词重复采集只保留一条来源关联；
     * 同岗位不同关键词会新增来源行，但岗位本身仍只有一条 boss_data 记录。
     *
     * @return 是否新增来源关联；失败只返回 false，不影响投递
     */
    public boolean recordJobSource(String keyword, String cityCode, String encryptId, String snapshotHash) {
        if (encryptId == null || encryptId.isBlank()) {
            return false;
        }
        try {
            String now = LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return jobSourceRepository.recordIfAbsent(metricsAccount, "boss", encryptId.trim(),
                    keyword == null ? null : keyword.trim(), cityCode, now, snapshotHash);
        } catch (Exception e) {
            log.warn("记录岗位搜索来源失败（不影响投递）| encryptId={} | {}", encryptId, e.getMessage());
            return false;
        }
    }

    /**
     * 插入或刷新岗位快照（按 encrypt_id + encrypt_user_id 定位，缺少 encrypt_user_id 时只按 encrypt_id）。
     * <p>
     * 已存在时刷新岗位/公司/HR 的快照字段；但已有的投递终态
     * （已投递 / 投递失败 / 状态未知）不会被快照回退成 未投递 / 已过滤。
     * 入参字段为 null 时保留数据库已有值，避免不完整的快照把已有内容清空。
     *
     * @param entity 解析出的岗位快照（encryptId 必填）
     */
    public void upsertBossJob(BossJobDataEntity entity) {
        if (entity == null) return;
        String encryptId = StringUtils.isBlank(entity.getEncryptId()) ? null : entity.getEncryptId().trim();
        if (encryptId == null) {
            log.warn("upsertBossJob 跳过：encryptId 为空");
            return;
        }
        String encryptUserId = StringUtils.isBlank(entity.getEncryptUserId()) ? null : entity.getEncryptUserId().trim();
        BossJobDataEntity existing;
        try {
            QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
            wrapper.eq("encrypt_id", encryptId);
            if (encryptUserId != null) {
                wrapper.eq("encrypt_user_id", encryptUserId);
            }
            wrapper.last("LIMIT 1");
            existing = bossJobDataMapper.selectOne(wrapper);
        } catch (Exception e) {
            log.error("查询岗位是否已存在失败，本次快照未写入 | encryptId={}：{}", encryptId, e.getMessage(), e);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            if (existing == null) {
                entity.setEncryptId(encryptId);
                entity.setEncryptUserId(encryptUserId);
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                // B4：首次发现时间只在新建时写入；之后采集不覆盖
                if (entity.getFirstSeenAt() == null) entity.setFirstSeenAt(now);
                entity.setLastDetailAt(now);
                if (StringUtils.isBlank(entity.getDeliveryStatus())) {
                    entity.setDeliveryStatus("未投递");
                }
                bossJobDataMapper.insert(entity);
                return;
            }
            refreshBossJobSnapshot(existing, entity);
            if (existing.getEncryptUserId() == null && encryptUserId != null) {
                existing.setEncryptUserId(encryptUserId);
            }
            existing.setUpdatedAt(now);
            // B4：刷新最近详情时间与快照哈希；first_seen_at 保持首次值
            existing.setLastDetailAt(now);
            if (StringUtils.isNotBlank(entity.getSnapshotHash())) {
                existing.setSnapshotHash(entity.getSnapshotHash());
            }
            bossJobDataMapper.updateById(existing);
        } catch (Exception e) {
            log.error("upsertBossJob 写库失败 | encryptId={}：{}", encryptId, e.getMessage(), e);
        }
    }

    /** 按 encryptId 查询岗位快照（D3-B 人工放行执行器用；不存在返回 null）。 */
    public BossJobDataEntity findBossJobByEncryptId(String encryptId) {
        String safeId = StringUtils.isBlank(encryptId) ? null : encryptId.trim();
        if (safeId == null) return null;
        try {
            QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
            wrapper.eq("encrypt_id", safeId).last("LIMIT 1");
            return bossJobDataMapper.selectOne(wrapper);
        } catch (Exception e) {
            log.error("查询岗位快照失败 | encryptId={}：{}", safeId, e.getMessage(), e);
            return null;
        }
    }

    /** 用最新快照刷新已有岗位记录（只覆盖非空字段，并保护投递终态）。 */
    private void refreshBossJobSnapshot(BossJobDataEntity target, BossJobDataEntity source) {
        if (source.getJobName() != null) target.setJobName(source.getJobName());
        if (source.getSalary() != null) target.setSalary(source.getSalary());
        if (source.getLocation() != null) target.setLocation(source.getLocation());
        if (source.getExperience() != null) target.setExperience(source.getExperience());
        if (source.getDegree() != null) target.setDegree(source.getDegree());
        if (source.getJobDescription() != null) target.setJobDescription(source.getJobDescription());
        if (source.getJobUrl() != null) target.setJobUrl(source.getJobUrl());
        if (source.getRecruitmentStatus() != null) {
            target.setRecruitmentStatus(source.getRecruitmentStatus());
        }
        if (source.getCompanyName() != null) target.setCompanyName(source.getCompanyName());
        if (source.getCompanyAddress() != null) target.setCompanyAddress(source.getCompanyAddress());
        if (source.getIndustry() != null) target.setIndustry(source.getIndustry());
        if (source.getFinancingStage() != null) target.setFinancingStage(source.getFinancingStage());
        if (source.getCompanyScale() != null) target.setCompanyScale(source.getCompanyScale());
        if (source.getHrName() != null) target.setHrName(source.getHrName());
        if (source.getHrPosition() != null) target.setHrPosition(source.getHrPosition());
        if (source.getHrActiveStatus() != null) {
            target.setHrActiveStatus(source.getHrActiveStatus());
        }
        String incomingStatus = StringUtils.isBlank(source.getDeliveryStatus()) ? null : source.getDeliveryStatus().trim();
        if (incomingStatus == null) {
            return;
        }
        if (isTerminalDeliveryStatus(target.getDeliveryStatus())
                && !isTerminalDeliveryStatus(incomingStatus)) {
            log.debug("保留岗位投递终态 {}，忽略快照状态 {} | encryptId={}",
                    target.getDeliveryStatus(), incomingStatus, target.getEncryptId());
            return;
        }
        target.setDeliveryStatus(incomingStatus);
    }

    private static boolean isTerminalDeliveryStatus(String status) {
        String value = StringUtils.isBlank(status) ? null : status.trim();
        return value != null && TERMINAL_DELIVERY_STATUSES.contains(value);
    }

    /**
     * 更新投递状态（WHERE encrypt_id = ? AND encrypt_user_id = ?）。
     * 两个必填项任一为空时直接忽略，避免误更新全表。
     */
    public void updateDeliveryStatus(String encryptId, String encryptUserId, String status) {
        if (encryptId == null || status == null) {
            return;
        }
        BossJobDataEntity patch = new BossJobDataEntity();
        patch.setDeliveryStatus(status);
        patch.setUpdatedAt(LocalDateTime.now());
        bossJobDataMapper.update(patch, jobStatusLocator(encryptId, encryptUserId));
    }

    /** 投递状态更新条件：encrypt_id 必带，encrypt_user_id 非空才追加。 */
    private com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BossJobDataEntity>
            jobStatusLocator(String encryptId, String encryptUserId) {
        var locator = new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BossJobDataEntity>();
        locator.eq("encrypt_id", encryptId);
        if (encryptUserId != null) {
            locator.eq("encrypt_user_id", encryptUserId);
        }
        return locator;
    }

    // ==================== 投递分析（Dashboard）相关方法 ====================

    /**
     * 薪资解析结果（公开字段直接映射到统计接口 JSON，字段名/顺序不可随意改）。
     */
    public static class SalaryInfo {
        public Integer minK;       // 月薪下限（单位 K/月）
        public Integer maxK;       // 月薪上限（单值薪资时与 minK 相同）
        public Integer months;     // 薪数：默认 12，如 "·16薪" 为 16
        public Double medianK;     // 区间中位数（K/月）
        public Long annualTotal;   // 估算年包（元）= medianK * 1000 * months
    }

    /** 月数后缀，如 “·16薪”。 */
    private static final Pattern SALARY_MONTHS_PATTERN = Pattern.compile("[·.\\-]?([0-9]+)薪");
    /** 一个正则同时匹配 “20-40K” 与 “30K”：区间优先，单值兜底。 */
    private static final Pattern SALARY_BOUNDS_PATTERN =
            Pattern.compile("^(?:(\\d+)-(\\d+)|(\\d+))[Kk]$");
    /** 兜底清洗：只保留数字、K/k、连字符。 */
    private static final Pattern SALARY_NOISE_PATTERN = Pattern.compile("[^0-9Kk\\-]");

    /** 薪资计薪单位；比较前统一折算成 K/月（年包按 12 个月摊）。 */
    public enum SalaryUnit {
        K_PER_MONTH,
        K_PER_YEAR;

        double monthlyFactor() {
            return this == K_PER_YEAR ? 1.0 / 12.0 : 1.0;
        }
    }

    /**
     * 薪资区间值对象：解析与判定分离后的中间结果。
     * <p>
     * minK / maxK 为 null 表示该侧无界（单边区间）；{@link #intersects} 只做区间交集，
     * 不再关心原始文本。单位统一按 K/月 比较。
     */
    public record SalaryRange(Double minK, Double maxK, SalaryUnit unit) {

        /** 期望薪资过滤条件（界面传入的 minK/maxK 单位就是 K/月，边界可为 null）。 */
        public static SalaryRange monthlyFilter(Double minK, Double maxK) {
            return new SalaryRange(minK, maxK, SalaryUnit.K_PER_MONTH);
        }

        /** 两侧都为 null 表示不限薪资。 */
        public boolean isUnbounded() {
            return minK == null && maxK == null;
        }

        /** 区间是否有交集；一侧为 null 视为不限制，无界侧按 ±∞ 处理。 */
        public boolean intersects(SalaryRange other) {
            if (other == null || isUnbounded() || other.isUnbounded()) {
                return true;
            }
            return lowerBoundInMonthlyK() <= other.upperBoundInMonthlyK()
                    && other.lowerBoundInMonthlyK() <= upperBoundInMonthlyK();
        }

        private double lowerBoundInMonthlyK() {
            return minK == null ? Double.NEGATIVE_INFINITY : minK * unit.monthlyFactor();
        }

        private double upperBoundInMonthlyK() {
            return maxK == null ? Double.POSITIVE_INFINITY : maxK * unit.monthlyFactor();
        }
    }

    /** 解析结果：薪资区间 + 显式薪数（用于年包估算）。 */
    private record ParsedSalary(SalaryRange range, int months) {
    }

    /**
     * 把 Boss 薪资文本解析为 {@link SalaryInfo}；"面议"或无法识别时返回 null
     * （调用方按“薪资未知/不可解析”处理，不在这里抛异常）。
     *
     * @param salary Boss 页面/详情接口返回的原始薪资文本，可为 null
     */
    public static SalaryInfo parseSalary(String salary) {
        ParsedSalary parsed = parseSalaryText(salary);
        if (parsed == null) {
            return null;
        }
        SalaryRange range = parsed.range();
        SalaryInfo info = new SalaryInfo();
        info.minK = range.minK().intValue();
        info.maxK = range.maxK().intValue();
        info.months = parsed.months();
        info.medianK = (info.minK + info.maxK) / 2.0;
        info.annualTotal = Math.round(info.medianK * 1000 * info.months);
        return info;
    }

    /** 只做解析：薪资文本 → 区间 + 薪数；面议/不可解析返回 null。 */
    private static ParsedSalary parseSalaryText(String salary) {
        String body = normalizeSalaryText(salary);
        if (body == null) {
            return null;
        }
        int months = 12;
        Matcher monthsMatcher = SALARY_MONTHS_PATTERN.matcher(body);
        if (monthsMatcher.find()) {
            try {
                months = Integer.parseInt(monthsMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // 月数非法时沿用默认 12
            }
            body = body.substring(0, monthsMatcher.start());
        }
        SalaryRange range = extractSalaryRange(body);
        return range == null ? null : new ParsedSalary(range, months);
    }

    /** 去掉空白；null/空串/含“面议”的薪资视为不可解析。 */
    private static String normalizeSalaryText(String salary) {
        if (salary == null) {
            return null;
        }
        String trimmed = salary.trim();
        if (trimmed.isEmpty() || trimmed.contains("面议")) {
            return null;
        }
        return trimmed.replace(" ", "");
    }

    /** 依次尝试原文与“清洗后”的文本解析区间；都失败返回 null（保留旧宽松兜底语义）。 */
    private static SalaryRange extractSalaryRange(String body) {
        SalaryRange direct = matchSalaryBounds(body);
        return direct != null ? direct : matchSalaryBounds(SALARY_NOISE_PATTERN.matcher(body).replaceAll(""));
    }

    /** 用单个正则匹配区间或单值，产出月薪 K 区间；区间优先，和旧实现顺序一致。 */
    private static SalaryRange matchSalaryBounds(String candidate) {
        Matcher matcher = SALARY_BOUNDS_PATTERN.matcher(candidate);
        if (!matcher.matches()) {
            return null;
        }
        if (matcher.group(1) != null) {
            return new SalaryRange((double) Integer.parseInt(matcher.group(1)),
                    (double) Integer.parseInt(matcher.group(2)), SalaryUnit.K_PER_MONTH);
        }
        double singleK = Integer.parseInt(matcher.group(3));
        return new SalaryRange(singleK, singleK, SalaryUnit.K_PER_MONTH);
    }

    /** Dashboard KPI：岗位总数 + 各投递状态计数；avgMonthlyK 为可解析岗位的月薪中位数均值。 */
    public static class Kpi {
        public long total;           // 岗位总数
        public long delivered;       // 已投递
        public long pending;         // 未投递
        public long filtered;        // 已过滤
        public long failed;          // 投递失败
        public Double avgMonthlyK;   // 平均月薪中位数（K/月）
    }

    /** 图表通用名称-数值对（无参构造供 Jackson 反序列化/前端绑定）。 */
    public static class NameValue {
        public String name;
        public long value;

        public NameValue() {
        }

        public NameValue(String name, long value) {
            this.name = name;
            this.value = value;
        }
    }

    /** 薪资分档项：bucket 为展示文案，value 为岗位数。 */
    public static class BucketValue {
        public String bucket;
        public long value;

        public BucketValue() {
        }

        public BucketValue(String bucket, long value) {
            this.bucket = bucket;
            this.value = value;
        }
    }

    /** 图表集合：每个字段对应 Dashboard 的一张图。 */
    public static class Charts {
        public List<NameValue> byStatus;        // 投递状态分布
        public List<NameValue> byCity;          // 城市 TOP10
        public List<NameValue> byIndustry;      // 行业 TOP10
        public List<NameValue> byCompany;       // 公司 TOP10
        public List<NameValue> byExperience;    // 经验分布
        public List<NameValue> byDegree;        // 学历分布
        public List<BucketValue> salaryBuckets; // 薪资分档
        public List<NameValue> dailyTrend;      // 每日新增趋势（name=日期）
        public List<NameValue> hrActivity;      // HR 活跃度
    }

    /** 统计接口响应：KPI + 图表。 */
    public static class StatsResponse {
        public Kpi kpi;         // 顶部数字卡片
        public Charts charts;   // 图表数据
    }

    /** 岗位列表分页响应。 */
    public static class PagedResult {
        public List<BossJobDataEntity> items;   // 当前页数据
        public long total;                      // 过滤后总数
        public int page;                        // 当前页码（从 1 开始）
        public int size;                        // 页大小
    }

    /**
     * 获取投递分析统计与图表数据
     */
    public StatsResponse getBossStats() {
        StatsResponse response = new StatsResponse();
        response.kpi = new Kpi();
        Charts charts = newCharts();
        try (Connection connection = dataSource.getConnection()) {
            fillOverallKpi(connection, response.kpi);
            List<Double> medians = mediansFromColumn(connection);
            response.kpi.avgMonthlyK = averageOf(medians);

            charts.byStatus = queryNameValues(connection,
                    "SELECT delivery_status, COUNT(*) AS cnt FROM boss_data GROUP BY delivery_status");
            charts.byCity = queryNameValues(connection,
                    "SELECT location, COUNT(*) AS cnt FROM boss_data GROUP BY location " +
                            "ORDER BY cnt DESC LIMIT 10");
            charts.byIndustry = queryNameValues(connection,
                    "SELECT industry, COUNT(*) AS cnt FROM boss_data GROUP BY industry " +
                            "ORDER BY cnt DESC LIMIT 10");
            charts.byCompany = queryNameValues(connection,
                    "SELECT company_name, COUNT(*) AS cnt FROM boss_data GROUP BY company_name " +
                            "ORDER BY cnt DESC LIMIT 10");
            charts.byExperience = queryNameValues(connection,
                    "SELECT experience, COUNT(*) AS cnt FROM boss_data GROUP BY experience");
            charts.byDegree = queryNameValues(connection,
                    "SELECT degree, COUNT(*) AS cnt FROM boss_data GROUP BY degree");
            charts.dailyTrend = queryNameValues(connection,
                    "SELECT substr(created_at,1,10) AS d, COUNT(*) AS cnt FROM boss_data " +
                            "GROUP BY d ORDER BY d");
            charts.hrActivity = queryNameValues(connection,
                    "SELECT hr_name, COUNT(*) AS cnt FROM boss_data WHERE hr_active_status IS NOT NULL " +
                            "AND TRIM(hr_active_status) <> '' GROUP BY hr_name");
            charts.salaryBuckets = buildSalaryBuckets(medians);
            response.charts = charts;
            return response;
        } catch (Exception e) {
            log.error("获取Boss统计失败: {}", e.getMessage(), e);
            // 失败时返回空集合，避免前端崩溃
            response.charts = charts;
            return response;
        }
    }

    /** 一次 SQL 取回 KPI 的 5 个计数（与逐条 SELECT 结果一致）。 */
    private void fillOverallKpi(Connection connection, Kpi kpi) throws Exception {
        String sql = "SELECT COUNT(*), " +
                "COALESCE(SUM(CASE WHEN delivery_status = '已投递' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN delivery_status = '未投递' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN delivery_status = '已过滤' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN delivery_status = '投递失败' THEN 1 ELSE 0 END), 0) " +
                "FROM boss_data";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (resultSet.next()) {
                kpi.total = resultSet.getLong(1);
                kpi.delivered = resultSet.getLong(2);
                kpi.pending = resultSet.getLong(3);
                kpi.filtered = resultSet.getLong(4);
                kpi.failed = resultSet.getLong(5);
            }
        }
    }

    /** 全表 salary 列解析出的中位数列表（面议/不可解析的跳过）。 */
    private List<Double> mediansFromColumn(Connection connection) throws Exception {
        List<Double> medians = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT salary FROM boss_data WHERE salary IS NOT NULL")) {
            while (resultSet.next()) {
                SalaryInfo info = parseSalary(resultSet.getString(1));
                if (info != null && info.medianK != null) {
                    medians.add(info.medianK);
                }
            }
        }
        return medians;
    }

    /** 通用图表查询：两列（name, count）结果映射为 NameValue 列表。 */
    private List<NameValue> queryNameValues(Connection connection, String sql) throws Exception {
        List<NameValue> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(new NameValue(nullSafe(resultSet.getString(1)), resultSet.getLong(2)));
            }
        }
        return values;
    }

    /** 带筛选条件（状态/城市/经验/学历/薪资区间/关键词/猎头）的统计；过滤条件与列表共用。 */
    public StatsResponse getBossStats(List<String> statuses, String location, String experience,
                                      String degree, Double minK, Double maxK, String keyword,
                                      boolean filterHeadhunter) {
        StatsResponse response = new StatsResponse();
        response.kpi = new Kpi();
        Charts charts = newCharts();
        try {
            List<BossJobDataEntity> candidates = bossJobDataMapper.selectList(
                    buildJobFilter(statuses, location, experience, degree, keyword, filterHeadhunter));
            List<BossJobDataEntity> filtered = filterBySalary(candidates, minK, maxK);

            response.kpi.total = filtered.size();
            response.kpi.delivered = countStatus(filtered, "已投递");
            response.kpi.pending = countStatus(filtered, "未投递");
            response.kpi.filtered = countStatus(filtered, "已过滤");
            response.kpi.failed = countStatus(filtered, "投递失败");
            response.kpi.avgMonthlyK = averageOf(mediansOf(filtered));

            charts.byStatus = groupedNameValues(filtered, job -> nullSafe(job.getDeliveryStatus()));
            charts.byCity = topGroupedNameValues(filtered, job -> nullSafe(job.getLocation()), 10);
            charts.byIndustry = topGroupedNameValues(filtered, job -> nullSafe(job.getIndustry()), 10);
            charts.byCompany = topGroupedNameValues(filtered, job -> nullSafe(job.getCompanyName()), 10);
            charts.byExperience = groupedNameValues(filtered, job -> nullSafe(job.getExperience()));
            charts.byDegree = groupedNameValues(filtered, job -> nullSafe(job.getDegree()));
            charts.dailyTrend = groupedByDay(filtered);
            charts.hrActivity = groupedNameValues(
                    filtered.stream()
                            .filter(job -> job.getHrActiveStatus() != null
                                    && !job.getHrActiveStatus().trim().isEmpty())
                            .toList(),
                    job -> nullSafe(job.getHrName()));
            charts.salaryBuckets = buildSalaryBuckets(mediansOf(filtered));

            response.charts = charts;
            return response;
        } catch (Exception e) {
            log.error("获取Boss筛选统计失败: {}", e.getMessage(), e);
            response.charts = charts;
            return response;
        }
    }

    private long countStatus(List<BossJobDataEntity> jobs, String status) {
        return jobs.stream().filter(job -> status.equals(job.getDeliveryStatus())).count();
    }

    private Map<String, Long> countBy(List<BossJobDataEntity> jobs,
                                      Function<BossJobDataEntity, String> classifier) {
        return jobs.stream().collect(Collectors.groupingBy(classifier, Collectors.counting()));
    }

    private List<NameValue> groupedNameValues(List<BossJobDataEntity> jobs,
                                              Function<BossJobDataEntity, String> classifier) {
        List<NameValue> values = new ArrayList<>();
        countBy(jobs, classifier).forEach((name, count) -> values.add(new NameValue(name, count)));
        return values;
    }

    private List<NameValue> topGroupedNameValues(List<BossJobDataEntity> jobs,
                                                 Function<BossJobDataEntity, String> classifier,
                                                 int limit) {
        List<NameValue> values = new ArrayList<>();
        countBy(jobs, classifier).entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .forEach(entry -> values.add(new NameValue(entry.getKey(), entry.getValue())));
        return values;
    }

    private List<NameValue> groupedByDay(List<BossJobDataEntity> jobs) {
        List<NameValue> values = new ArrayList<>();
        countBy(jobs, job -> dayOf(job.getCreatedAt())).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.add(new NameValue(entry.getKey(), entry.getValue())));
        return values;
    }

    private String dayOf(LocalDateTime time) {
        return time == null ? "未知"
                : String.format("%04d-%02d-%02d", time.getYear(), time.getMonthValue(), time.getDayOfMonth());
    }

    private long scalarCount(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private String nullSafe(String s) { return s == null || s.isEmpty() ? "未知" : s; }

    /** 初始化图表容器：9 个分组先给空列表，异常路径返回空集合而不是 null。 */
    private Charts newCharts() {
        Charts charts = new Charts();
        charts.byStatus = new ArrayList<>(0);
        charts.byCity = new ArrayList<>(0);
        charts.byIndustry = new ArrayList<>(0);
        charts.byCompany = new ArrayList<>(0);
        charts.byExperience = new ArrayList<>(0);
        charts.byDegree = new ArrayList<>(0);
        charts.salaryBuckets = new ArrayList<>(0);
        charts.dailyTrend = new ArrayList<>(0);
        charts.hrActivity = new ArrayList<>(0);
        return charts;
    }

    /** 中位数K 的算术平均（保留 2 位）；空列表返回 null。 */
    private Double averageOf(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return Math.round((sum / values.size()) * 100.0) / 100.0;
    }

    /** 按中位数K分档；档位上限随数据最大中位数动态上调，避免区间过窄。 */
    private List<BucketValue> buildSalaryBuckets(List<Double> medians) {
        double maxMedian = 0.0;
        for (double median : medians) {
            if (median > maxMedian) {
                maxMedian = median;
            }
        }
        int upperEdge = (int) Math.ceil(maxMedian / 5.0) * 5;
        if (upperEdge <= 20) {
            upperEdge = 25;
        }
        long below10 = 0;
        long from10To15 = 0;
        long from15To20 = 0;
        long from20ToUpper = 0;
        long atOrAboveUpper = 0;
        for (double median : medians) {
            if (median < 10) {
                below10++;
            } else if (median < 15) {
                from10To15++;
            } else if (median < 20) {
                from15To20++;
            } else if (median < upperEdge) {
                from20ToUpper++;
            } else {
                atOrAboveUpper++;
            }
        }
        List<BucketValue> buckets = new ArrayList<>();
        buckets.add(new BucketValue("0-10K", below10));
        buckets.add(new BucketValue("10-15K", from10To15));
        buckets.add(new BucketValue("15-20K", from15To20));
        buckets.add(new BucketValue("20-" + upperEdge + "K", from20ToUpper));
        buckets.add(new BucketValue(">=" + upperEdge + "K", atOrAboveUpper));
        return buckets;
    }

    /** 列表/统计共用筛选条件：状态、城市、经验、学历、关键词、猎头，最后按创建时间倒序。 */
    private QueryWrapper<BossJobDataEntity> buildJobFilter(List<String> statuses, String location,
                                                           String experience, String degree,
                                                           String keyword, boolean filterHeadhunter) {
        QueryWrapper<BossJobDataEntity> filter = new QueryWrapper<>();
        if (statuses != null && !statuses.isEmpty()) {
            filter.in("delivery_status", statuses);
        }
        appendEqualsFilter(filter, "location", location);
        appendEqualsFilter(filter, "experience", experience);
        appendEqualsFilter(filter, "degree", degree);
        if (StringUtils.isNotBlank(keyword)) {
            filter.and(match -> match.like("company_name", keyword)
                    .or().like("job_name", keyword)
                    .or().like("hr_name", keyword));
        }
        if (filterHeadhunter) {
            appendNonHeadhunterFilter(filter);
        }
        return filter.orderByDesc("created_at");
    }

    /** 非空才追加等值条件（空字符串/null 表示该维度不筛选）。 */
    private static void appendEqualsFilter(QueryWrapper<BossJobDataEntity> filter,
                                           String column, String value) {
        if (StringUtils.isNotBlank(value)) {
            filter.eq(column, value);
        }
    }

    /** 猎头过滤：hr_position 为空，或不包含“猎头”。 */
    private static void appendNonHeadhunterFilter(QueryWrapper<BossJobDataEntity> filter) {
        filter.and(match -> match.isNull("hr_position").or().notLike("hr_position", "猎头"));
    }

    /** 按薪资区间交集过滤（INTERSECT）；期望边界全为 null 时全量通过（含面议）。 */
    private List<BossJobDataEntity> filterBySalary(List<BossJobDataEntity> jobs, Double minK, Double maxK) {
        List<BossJobDataEntity> filtered = new ArrayList<>();
        for (BossJobDataEntity job : jobs) {
            if (matchesSalary(job, minK, maxK)) {
                filtered.add(job);
            }
        }
        return filtered;
    }

    /**
     * 薪资过滤：岗位薪资区间与期望区间有交集才通过（INTERSECT），单边期望按无界处理。
     * 旧实现按「中位数落在期望区间」判断；改为区间交集后，宽区间岗位（如 10-25K
     * 对期望 20-40K）会被放行，而期望边界全空时行为不变（面议/不可解析也通过）。
     */
    private boolean matchesSalary(BossJobDataEntity job, Double minK, Double maxK) {
        SalaryRange expected = SalaryRange.monthlyFilter(minK, maxK);
        if (expected.isUnbounded()) {
            return true;
        }
        ParsedSalary parsed = parseSalaryText(job.getSalary());
        return parsed != null && parsed.range().intersects(expected);
    }

    private List<Double> mediansOf(List<BossJobDataEntity> jobs) {
        List<Double> medians = new ArrayList<>();
        for (BossJobDataEntity job : jobs) {
            SalaryInfo info = parseSalary(job.getSalary());
            if (info != null && info.medianK != null) {
                medians.add(info.medianK);
            }
        }
        return medians;
    }

    /** 岗位列表分页查询：筛选同统计接口，薪资按区间交集过滤，页码/页大小非法时回退 1/20。 */
    public PagedResult listBossJobs(List<String> statuses, String location, String experience,
                                    String degree, Double minK, Double maxK, String keyword,
                                    int page, int size, boolean filterHeadhunter) {
        int normalizedPage = page <= 0 ? 1 : page;
        int normalizedSize = size <= 0 ? 20 : size;

        QueryWrapper<BossJobDataEntity> query = buildJobFilter(
                statuses, location, experience, degree, keyword, filterHeadhunter);
        List<BossJobDataEntity> all = bossJobDataMapper.selectList(query);
        // 猎头已在查询阶段过滤；薪资区间基于解析出的中位数K，在内存中完成
        List<BossJobDataEntity> filtered = filterBySalary(all, minK, maxK);

        int total = filtered.size();
        int from = Math.max(0, (normalizedPage - 1) * normalizedSize);
        int to = Math.min(total, from + normalizedSize);
        PagedResult result = new PagedResult();
        result.items = from >= to ? Collections.emptyList() : filtered.subList(from, to);
        result.total = total;
        result.page = normalizedPage;
        result.size = normalizedSize;
        return result;
    }

    /**
     * 刷新数据：只统计 boss_data 当前总数。
     * <p>
     * 数据库维护（备份、VACUUM、清理）已迁移到 {@code DatabaseMaintenanceService}，
     * 不再在管理台刷新请求里执行 VACUUM / wal_checkpoint（避免长事务阻塞投递线程）。
     */
    public Map<String, Object> reloadBossData() {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("success", true);
            result.put("message", "刷新完成");
            result.put("total", countBossDataRows());
        } catch (Exception e) {
            log.warn("刷新boss_data失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "刷新失败: " + e.getMessage());
        }
        return result;
    }

    /** 刷新接口只做只读计数，不触发数据库维护/清理动作。 */
    private long countBossDataRows() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return scalarCount(connection, "SELECT COUNT(*) FROM boss_data");
        }
    }
}



