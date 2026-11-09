package com.offerchaser.application.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offerchaser.application.entity.ConfigEntity;
import com.offerchaser.application.mapper.ConfigMapper;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.boss.BossConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 配置服务：config 表读写、密钥掩码、BossConfig 统一入口。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {
    /** 密钥类配置读取时返回的固定掩码 */
    public static final String MASKED_VALUE = "********";

    /** 密钥键名判定关键字：键名（大写）命中任意一个即按密钥处理 */
    private static final String[] SECRET_KEY_MARKERS = {
            "KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "COOKIE", "CREDENTIAL"
    };

    /** AI 调用必须齐全的三个配置键（声明顺序即校验顺序） */
    private static final List<String> AI_CONFIG_KEYS = List.of("BASE_URL", "API_KEY", "MODEL");

    private final ConfigMapper configMapper;
    private final BossService bossService;

    /**
     * 获取所有配置（以Map形式返回，不做任何掩码处理）。
     *
     * @return 配置Map，key 为 config_key、value 为 config_value
     */
    public Map<String, String> getAllConfigsAsMap() {
        Map<String, String> configMap = new HashMap<>();
        for (ConfigEntity config : configMapper.selectList(null)) {
            configMap.put(config.getConfigKey(), config.getConfigValue());
        }
        return configMap;
    }

    /**
     * 获取所有配置（密钥类配置只返回掩码，不暴露明文）。
     * <p>
     * 前端会把这个 Map 原样回传到保存接口，所以掩码值在保存侧有对应的"保持原值"语义。
     *
     * @return 配置Map，key为config_key，value为config_value（密钥为掩码）
     */
    public Map<String, String> getAllConfigsMasked() {
        List<ConfigEntity> configs = configMapper.selectList(null);
        Map<String, String> configMap = new HashMap<>();

        for (ConfigEntity config : configs) {
            String key = config.getConfigKey();
            String value = config.getConfigValue();
            if (isSecretKey(key) && value != null && !value.isEmpty()) {
                value = MASKED_VALUE;
            }
            configMap.put(key, value);
        }

        return configMap;
    }

    /**
     * 根据配置键获取配置（密钥类配置只返回掩码）。
     * <p>
     * 返回的是副本，避免调用方改动掩码值后误写回数据库。
     *
     * @param configKey 配置键
     * @return 配置实体（密钥为掩码），不存在返回 null
     */
    public ConfigEntity getConfigByKeyMasked(String configKey) {
        ConfigEntity config = getConfigByKey(configKey);
        if (config == null) {
            return null;
        }
        ConfigEntity masked = copyOf(config);
        String value = masked.getConfigValue();
        if (isSecretKey(configKey) && value != null && !value.isEmpty()) {
            masked.setConfigValue(MASKED_VALUE);
        }
        return masked;
    }

    /**
     * 判断配置键是否属于密钥类配置。
     * <p>
     * 只看键名（不区分大小写），命中 KEY/TOKEN/SECRET/PASSWORD/PASSWD/COOKIE/CREDENTIAL 任一即算。
     *
     * @param configKey 配置键
     * @return 是否密钥类配置
     */
    public static boolean isSecretKey(String configKey) {
        if (configKey == null || configKey.isBlank()) {
            return false;
        }
        String upper = configKey.toUpperCase(Locale.ROOT);
        for (String marker : SECRET_KEY_MARKERS) {
            if (upper.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 复制配置实体，避免把"掩码"写回真实实体。
     */
    private ConfigEntity copyOf(ConfigEntity source) {
        ConfigEntity copy = new ConfigEntity();
        copy.setId(source.getId());
        copy.setConfigKey(source.getConfigKey());
        copy.setConfigValue(source.getConfigValue());
        copy.setConfigType(source.getConfigType());
        copy.setCategory(source.getCategory());
        copy.setDescription(source.getDescription());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    /**
     * 删除一个配置项。
     *
     * @param configKey 配置键
     * @return 是否确实删除（键不存在返回 false）
     */
    @Transactional
    public boolean deleteConfig(String configKey) {
        if (isBlankKey(configKey)) {
            log.warn("配置键为空，跳过删除");
            return false;
        }

        LambdaQueryWrapper<ConfigEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConfigEntity::getConfigKey, configKey);

        int deleted = configMapper.delete(queryWrapper);
        if (deleted > 0) {
            log.info("删除配置: {}", configKey);
            return true;
        }
        log.warn("删除配置失败，配置键不存在: {}", configKey);
        return false;
    }

    /** 读取 config 表全部记录（未脱敏，仅供内部管理/迁移等场景使用）。 */
    public List<ConfigEntity> getAllConfigs() {
        List<ConfigEntity> configs = configMapper.selectList(null);
        return configs;
    }

    /**
     * 根据配置键获取配置。
     *
     * @param configKey 配置键
     * @return 配置实体；不存在返回 null
     */
    public ConfigEntity getConfigByKey(String configKey) {
        return configMapper.selectOne(Wrappers.<ConfigEntity>lambdaQuery()
                .eq(ConfigEntity::getConfigKey, configKey));
    }

    /**
     * 根据分类获取配置列表。
     *
     * @param category 分类
     * @return 配置列表（无匹配时为空列表）
     */
    public List<ConfigEntity> getConfigsByCategory(String category) {
        return configMapper.selectList(Wrappers.<ConfigEntity>lambdaQuery()
                .eq(ConfigEntity::getCategory, category));
    }

    /**
     * 根据配置键获取配置值（不抛异常，可能为 null）。
     *
     * @param configKey 配置键
     * @return 配置值；键不存在时为 null
     */
    public String getConfigValue(String configKey) {
        ConfigEntity config = getConfigByKey(configKey);
        return config == null ? null : config.getConfigValue();
    }

    /**
     * 根据配置键获取必填配置值（缺失或空白则抛异常）。
     *
     * @param configKey 配置键
     * @return 配置值（保证非空）
     * @throws IllegalStateException 当配置缺失或空白时抛出
     */
    public String requireConfigValue(String configKey) {
        String value = getConfigValue(configKey);
        boolean missing = value == null || value.isBlank();
        if (missing) {
            throw new IllegalStateException("缺少必要配置: " + configKey);
        }
        return value;
    }

    /**
     * 获取AI调用所需的基础配置（BASE_URL, API_KEY, MODEL）。
     * <p>
     * 三项都是必填：任一项缺失或空白都会由 {@link #requireConfigValue(String)}
     * 抛 {@code IllegalStateException}，避免请求侧带着空配置发出去。
     * </p>
     *
     * @return 配置Map，key 为 BASE_URL / API_KEY / MODEL
     */
    public Map<String, String> getAiConfigs() {
        Map<String, String> aiConfigs = new HashMap<>(AI_CONFIG_KEYS.size());
        for (String key : AI_CONFIG_KEYS) {
            aiConfigs.put(key, requireConfigValue(key));
        }
        return aiConfigs;
    }

    /**
     * 批量更新配置，键不存在时自动新增。
     * <p>
     * 掩码值（********）或 null 表示"保持原值"（前端读到的密钥就是掩码，不能拿它覆盖真密钥）；
     * 空字符串表示"显式清空"。
     *
     * @param configMap 配置Map，key为config_key，value为config_value
     * @return 成功处理的配置数量（含保持原值的项）
     */
    @Transactional
    public int batchUpdateConfigs(Map<String, String> configMap) {
        int handled = 0;
        for (String configKey : configMap.keySet()) {
            if (upsertConfig(configKey, configMap.get(configKey))) {
                handled++;
            }
        }
        return handled;
    }

    /**
     * 更新单个配置，键不存在时自动新增。
     * <p>
     * 掩码值（********）或 null 表示"保持原值"；空字符串表示"显式清空"。
     *
     * @param configKey 配置键
     * @param configValue 配置值
     * @return 是否处理成功
     */
    @Transactional
    public boolean updateConfig(String configKey, String configValue) {
        boolean handled = upsertConfig(configKey, configValue);
        return handled;
    }

    /**
     * 写入一个配置项：已存在则更新，不存在则插入。
     * <p>
     * 这里不能只做 update：config 表里的键（BASE_URL、API_KEY、MODEL）过去依赖数据库文件预置，
     * 一旦某个键不存在，只打一行 warn 就返回 false；
     * 接口层看起来保存成功，实际一个字都没落库，而读取侧 requireConfigValue 会一直抛
     * “缺少必要配置”，用户很难定位。新增配置键也会踩同一个坑：老库没有对应行，
     * 写入同样静默失败。
     * </p>
     */
    private boolean upsertConfig(String configKey, String configValue) {
        if (isBlankKey(configKey)) {
            log.warn("配置键为空，跳过写入");
            return false;
        }

        ConfigEntity existing = getConfigByKey(configKey);
        if (isKeepOriginalValue(configValue)) {
            // 掩码值或 null 表示“保持原值”：前端 GET 拿到的密钥就是 ********，
            // 原样提交回来时不能当成新值写入，否则真密钥会被掩码覆盖。
            if (existing == null) {
                log.info("配置 {} 提交保持原值，但库中不存在该键，跳过写入", configKey);
            } else {
                log.info("配置 {} 提交值为掩码/空值，保持原值", configKey);
            }
            return true;
        }
        if (existing != null) {
            return updateConfigValue(existing, configKey, configValue);
        }
        return insertAutoCreatedConfig(configKey, configValue);
    }

    /**
     * 判断配置键是否为空（null 或纯空白）。
     */
    private static boolean isBlankKey(String configKey) {
        return configKey == null || configKey.isBlank();
    }

    /**
     * 判断提交值是否属于“不修改原值”的标记（掩码或 null）。
     */
    private static boolean isKeepOriginalValue(String configValue) {
        return configValue == null || MASKED_VALUE.equals(configValue);
    }

    /**
     * 把新值写回已有配置并刷新 updated_at。
     */
    private boolean updateConfigValue(ConfigEntity existing, String configKey, String configValue) {
        existing.setConfigValue(configValue);
        existing.setUpdatedAt(LocalDateTime.now());
        if (configMapper.updateById(existing) > 0) {
            log.info("更新配置: {}", configKey);
            return true;
        }
        log.warn("更新配置失败: {}", configKey);
        return false;
    }

    /**
     * 配置键不存在时自动新增一行（接口默认类型 string、分类 custom）。
     */
    private boolean insertAutoCreatedConfig(String configKey, String configValue) {
        LocalDateTime now = LocalDateTime.now();
        ConfigEntity created = new ConfigEntity();
        created.setConfigType("string");
        created.setCategory("custom");
        created.setDescription("由配置接口自动创建");
        created.setConfigKey(configKey);
        created.setConfigValue(configValue);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        if (configMapper.insert(created) > 0) {
            log.info("配置键 {} 不存在，已自动创建", configKey);
            return true;
        }
        log.warn("创建配置失败，key={}", configKey);
        return false;
    }

    /**
     * 创建一条新配置；created_at / updated_at 由本方法统一补齐。
     *
     * @param config 待写入的配置实体
     * @return 写入成功返回 true
     */
    @Transactional
    public boolean createConfig(ConfigEntity config) {
        LocalDateTime now = LocalDateTime.now();
        config.setCreatedAt(now);
        config.setUpdatedAt(now);

        boolean created = configMapper.insert(config) > 0;
        if (created) {
            log.info("创建配置成功: {} = {}", config.getConfigKey(), config.getConfigValue());
        }
        return created;
    }

    /**
     * 从 boss_config 专表读取并构建 BossConfig（兼容旧调用方）。
     */
    public BossConfig getBossConfig() {
        return bossService.loadBossConfig();
    }
}
