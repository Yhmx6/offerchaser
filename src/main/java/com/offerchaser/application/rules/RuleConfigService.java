package com.offerchaser.application.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.delivery.BlacklistMatchMode;
import com.offerchaser.application.delivery.BlacklistPolicy;
import com.offerchaser.application.delivery.JobRuleEngine;
import com.offerchaser.application.delivery.JobSnapshot;
import com.offerchaser.application.delivery.RuleDecision;
import com.offerchaser.application.delivery.RuleOutcome;
import com.offerchaser.application.delivery.RuleSet;
import com.offerchaser.application.delivery.SalaryStrategy;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.EvaluationEntry;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.persistence.RuleConfigRepository;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.boss.BossConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 规则配置服务（阶段 B B2）。
 * <p>
 * 职责：
 * <ul>
 *   <li>规则版本化：每次保存生成不可变快照（rule_config_version），运行中编辑只影响下一轮；</li>
 *   <li>自动同步：管理台旧配置页/黑名单页改动后，下一次任务启动自动生成新版本；</li>
 *   <li>预览：只做规则判定，零发送、不写评估表；</li>
 *   <li>重评：只向 job_evaluation 追加新评估记录，绝不发送、不改投递状态。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleConfigService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 用户最高学历可选值（用于校验） */
    private static final Set<String> DEGREE_VALUES = Set.of("高中", "中专", "大专", "本科", "硕士", "博士");
    private static final Set<String> STRATEGY_VALUES = Set.of("INTERSECT", "FLOOR");
    private static final Set<String> MATCH_MODE_VALUES = Set.of("CONTAINS", "EXACT");
    private static final int MAX_LIST_ITEMS = 200;
    private static final int MAX_ITEM_LENGTH = 100;

    private final RuleConfigRepository ruleConfigRepository;
    private final BossService bossService;
    private final EvaluationRepository evaluationRepository;
    private final BossQueryRepository bossQueryRepository;

    @Value("${offerchaser.boss.salary-strategy:INTERSECT}")
    private String salaryStrategyName = "INTERSECT";

    @Value("${offerchaser.rules.highest-degree:本科}")
    private String defaultHighestDegree = "本科";

    // ==================== 读取 / 版本 ====================

    /** 当前生效规则版本；没有任何版本时从 live 配置生成版本 1。 */
    public synchronized RuleSetView getActive() {
        RuleConfigRepository.RuleConfigVersion latest = ruleConfigRepository.latest();
        if (latest == null) {
            RulePayload defaults = getLiveDefaults();
            RulePayload canonical = canonicalize(defaults);
            validate(canonical);
            try {
                RuleConfigRepository.RuleConfigVersion created = ruleConfigRepository.insert(
                        1, MAPPER.writeValueAsString(canonical), payloadHash(canonical),
                        "初始化：从当前 BOSS 配置与黑名单生成", liveHashNow());
                log.info("规则配置初始化：v1 | hash={}", created.sourceHash());
                return toView(created);
            } catch (Exception e) {
                log.error("规则配置初始化失败：{}", e.getMessage(), e);
                throw new IllegalStateException("规则配置初始化失败: " + e.getMessage(), e);
            }
        }
        return toView(latest);
    }

    /**
     * 供任务启动使用：返回本轮固定使用的规则快照。
     * <p>
     * 语义（B2 修复）：
     * <ul>
     *   <li>每个版本记录生成时的 live 配置哈希（live_hash）；live 未变化时**原样返回最新版本**，
     *       不会覆盖管理员在规则页显式保存的规则；</li>
     *   <li>只有 boss_config/黑名单/HR 活跃过滤等 live 配置真的变化时，才自动生成新版本，
     *       且只把 live 拥有的字段（黑名单三组、filterDeadHr、deadStatus）合并进去，
     *       保留规则页显式设置的学历/薪资/方向/城市/白名单/匹配模式。</li>
     * </ul>
     */
    public synchronized RuleSet getActiveRuleSet() {
        RuleSetView active = getActive();
        try {
            String liveHash = liveHashNow();
            RuleConfigRepository.RuleConfigVersion latest = ruleConfigRepository.latest();
            String baseline = latest == null ? null : latest.liveHash();
            if (liveHash == null || Objects.equals(liveHash, baseline)) {
                return toRuleSet(active);
            }
            // live 配置发生变化：只合并 live-owned 字段，保留显式规则
            RulePayload merged = canonicalize(mergeLiveOwned(active.rules(), getLiveDefaults()));
            RuleSetView synced = insertNewVersion(merged, "自动同步：BOSS 配置/黑名单变更", liveHash);
            log.info("规则配置已自动同步到 v{}（live 配置变更，保留显式规则字段）", synced.version());
            return toRuleSet(synced);
        } catch (RuleVersionConflictException conflict) {
            log.warn("自动同步规则遇到版本冲突（并发修改），继续使用最新版本 v{}", conflict.getCurrentVersion());
        } catch (Exception e) {
            log.warn("自动同步规则失败，继续使用当前版本 v{}：{}", active.version(), e.getMessage());
        }
        return toRuleSet(getActive());
    }

    /**
     * 只校验并规范化规则载荷，不落库、不升版本（供方向模板等场景先保存快照）。
     */
    public RulePayload validateAndCanonicalize(RulePayload payload) {
        if (payload == null) {
            throw new RuleValidationException("rules 不能为空");
        }
        validate(payload);
        return canonicalize(payload);
    }

    /** 保存新规则版本；expectedVersion 必须等于当前最新版本（首次为 0），否则 409。 */
    public synchronized RuleSetView save(RulePayload payload, int expectedVersion, String note) {        if (payload == null) {
            throw new RuleValidationException("rules 不能为空");
        }
        validate(payload);
        RulePayload canonical = canonicalize(payload);
        RuleConfigRepository.RuleConfigVersion latest = ruleConfigRepository.latest();
        int currentVersion = latest == null ? 0 : latest.version();
        if (expectedVersion != currentVersion) {
            throw new RuleVersionConflictException(currentVersion);
        }
        try {
            // 显式保存时记录“保存这一刻看到的 live 哈希”：之后 live 不变就不会再被自动同步覆盖
            RuleSetView saved = insertNewVersion(canonical,
                    note == null || note.isBlank() ? null : note.trim(), liveHashNow());
            log.info("规则配置已保存：v{} | hash={}", saved.version(), saved.sourceHash());
            return saved;
        } catch (Exception e) {
            throw new IllegalStateException("保存规则版本失败: " + e.getMessage(), e);
        }
    }

    /** 插入下一个版本（调用方保证 synchronized / 已校验）。 */
    private RuleSetView insertNewVersion(RulePayload canonical, String note, String liveHash) throws Exception {
        RuleConfigRepository.RuleConfigVersion latest = ruleConfigRepository.latest();
        int nextVersion = latest == null ? 1 : latest.version() + 1;
        RuleConfigRepository.RuleConfigVersion inserted = ruleConfigRepository.insert(
                nextVersion,
                MAPPER.writeValueAsString(canonical),
                payloadHash(canonical),
                note,
                liveHash);
        return toView(inserted);
    }

    /** 把 live 拥有的字段合并进当前显式规则：黑名单三组、HR 活跃过滤、deadStatus。 */
    private RulePayload mergeLiveOwned(RulePayload active, RulePayload live) {
        return new RulePayload(
                active.highestDegree(),
                active.salaryStrategy(),
                active.expectedSalaryMin(),
                active.expectedSalaryMax(),
                active.jobDirections(),
                active.cities(),
                active.blacklistCities(),
                active.workTypes(),
                active.blacklistMatchMode(),
                live.blacklistJob(),
                live.blacklistCompany(),
                live.blacklistRecruiter(),
                active.whitelist(),
                live.filterDeadHr(),
                live.deadStatus());
    }

    /** 当前 live 配置的规范化哈希。 */
    private String liveHashNow() {
        try {
            return payloadHash(canonicalize(getLiveDefaults()));
        } catch (Exception e) {
            log.warn("计算 live 规则哈希失败：{}", e.getMessage());
            return null;
        }
    }

    public List<RuleSetView> listVersions(int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 200);
        List<RuleSetView> views = new ArrayList<>();
        for (RuleConfigRepository.RuleConfigVersion version : ruleConfigRepository.list(safeLimit)) {
            views.add(toView(version));
        }
        return views;
    }

    public RuleSetView getVersion(int version) {
        RuleConfigRepository.RuleConfigVersion found = ruleConfigRepository.findByVersion(version);
        if (found == null) {
            throw new RuleValidationException("规则版本不存在: v" + version);
        }
        return toView(found);
    }

    // ==================== live 默认值 ====================

    /**
     * 从当前 BOSS 配置与黑名单表推导 live 默认规则。
     * 用于：首次初始化、管理台旧配置页变更后的自动同步、前端"恢复当前配置"。
     */
    public RulePayload getLiveDefaults() {
        BossConfig config = null;
        try {
            config = bossService.loadBossConfig();
        } catch (Exception e) {
            log.warn("读取 BOSS 配置失败，规则默认值使用空配置：{}", e.getMessage());
        }
        List<Integer> expected = config == null ? null : config.getExpectedSalary();
        Integer min = expected != null && !expected.isEmpty() ? expected.get(0) : null;
        Integer max = expected != null && expected.size() > 1 ? expected.get(1) : null;
        Boolean filterDeadHr = config != null && Boolean.TRUE.equals(config.getFilterDeadHR());
        List<String> deadStatus = config == null || config.getDeadStatus() == null
                ? List.of() : config.getDeadStatus();

        return new RulePayload(
                normalizeDegree(defaultHighestDegree),
                normalizeStrategy(salaryStrategyName),
                min,
                max,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                BlacklistMatchMode.CONTAINS.name(),
                sortedDistinct(safeSetToStrings(() -> bossService.getBlackJobs())),
                sortedDistinct(safeSetToStrings(() -> bossService.getBlackCompanies())),
                sortedDistinct(safeSetToStrings(() -> bossService.getBlackRecruiters())),
                List.of(),
                filterDeadHr,
                sortedDistinct(deadStatus));
    }

    private List<String> safeSetToStrings(java.util.concurrent.Callable<Set<String>> supplier) {
        try {
            Set<String> values = supplier.call();
            return values == null ? List.of() : new ArrayList<>(values);
        } catch (Exception e) {
            log.warn("读取黑名单失败：{}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 校验 / 规范化 / 哈希 ====================

    private void validate(RulePayload payload) {
        String degree = normalizeDegree(payload.highestDegree());
        if (!DEGREE_VALUES.contains(degree)) {
            throw new RuleValidationException("highestDegree 必须是 高中/中专/大专/本科/硕士/博士 之一");
        }
        String strategy = normalizeStrategy(payload.salaryStrategy());
        if (!STRATEGY_VALUES.contains(strategy)) {
            throw new RuleValidationException("salaryStrategy 必须是 INTERSECT 或 FLOOR");
        }
        String matchMode = normalizeMatchMode(payload.blacklistMatchMode());
        if (!MATCH_MODE_VALUES.contains(matchMode)) {
            throw new RuleValidationException("blacklistMatchMode 必须是 CONTAINS 或 EXACT");
        }
        Integer min = payload.expectedSalaryMin();
        Integer max = payload.expectedSalaryMax();
        if (min != null && (min < 0 || min > 1000)) {
            throw new RuleValidationException("expectedSalaryMin 必须在 0..1000（单位 K/月）");
        }
        if (max != null && (max < 0 || max > 1000)) {
            throw new RuleValidationException("expectedSalaryMax 必须在 0..1000（单位 K/月）");
        }
        // 0 表示“未设置上限/下限”：只比较两个有效正数，允许模板只设下限。
        boolean hasMin = min != null && min > 0;
        boolean hasMax = max != null && max > 0;
        if (hasMin && hasMax && min > max) {
            throw new RuleValidationException("expectedSalaryMin 不能大于 expectedSalaryMax");
        }
        validateList("jobDirections", payload.jobDirections());
        validateList("cities", payload.cities());
        validateList("blacklistCities", payload.blacklistCities());
        validateList("workTypes", payload.workTypes());
        validateList("blacklistJob", payload.blacklistJob());
        validateList("blacklistCompany", payload.blacklistCompany());
        validateList("blacklistRecruiter", payload.blacklistRecruiter());
        validateList("whitelist", payload.whitelist());
        validateList("deadStatus", payload.deadStatus());
    }

    private void validateList(String field, List<String> values) {
        if (values == null) {
            return;
        }
        if (values.size() > MAX_LIST_ITEMS) {
            throw new RuleValidationException(field + " 最多 " + MAX_LIST_ITEMS + " 项");
        }
        for (String value : values) {
            if (value == null || value.isBlank() || value.trim().length() > MAX_ITEM_LENGTH) {
                throw new RuleValidationException(field + " 中存在空项或超过 " + MAX_ITEM_LENGTH + " 字的项");
            }
        }
    }

    /** 规范化：去空白、枚举大写、列表排序去重；哈希与落库都用它，保证同一输入得到同一版本结果。 */
    private RulePayload canonicalize(RulePayload payload) {
        return new RulePayload(
                normalizeDegree(payload.highestDegree()),
                normalizeStrategy(payload.salaryStrategy()),
                payload.expectedSalaryMin(),
                payload.expectedSalaryMax(),
                sortedDistinct(payload.jobDirections()),
                sortedDistinct(payload.cities()),
                sortedDistinct(payload.blacklistCities()),
                sortedDistinct(payload.workTypes()),
                normalizeMatchMode(payload.blacklistMatchMode()),
                sortedDistinct(payload.blacklistJob()),
                sortedDistinct(payload.blacklistCompany()),
                sortedDistinct(payload.blacklistRecruiter()),
                sortedDistinct(payload.whitelist()),
                payload.filterDeadHr() != null && payload.filterDeadHr(),
                sortedDistinct(payload.deadStatus()));
    }

    private static String normalizeDegree(String raw) {
        if (raw == null || raw.isBlank()) {
            return "本科";
        }
        String value = raw.trim();
        // 常见别名归一
        if ("研究生".equals(value) || "硕士研究生".equals(value)) {
            return "硕士";
        }
        if ("博士研究生".equals(value)) {
            return "博士";
        }
        return value;
    }

    private static String normalizeStrategy(String raw) {
        return raw == null || raw.isBlank() ? "INTERSECT" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeMatchMode(String raw) {
        return raw == null || raw.isBlank() ? "CONTAINS" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> sortedDistinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                set.add(value.trim());
            }
        }
        List<String> sorted = new ArrayList<>(set);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private String payloadHash(RulePayload canonical) {
        try {
            return sha256Hex(MAPPER.writeValueAsString(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("计算规则哈希失败: " + e.getMessage(), e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }

    // ==================== 视图 / RuleSet 转换 ====================

    private RuleSetView toView(RuleConfigRepository.RuleConfigVersion version) {
        RulePayload payload;
        try {
            payload = MAPPER.readValue(version.rulesJson(), RulePayload.class);
        } catch (Exception e) {
            log.warn("解析规则版本 v{} 的 JSON 失败，回退 live 默认值：{}", version.version(), e.getMessage());
            payload = getLiveDefaults();
        }
        return new RuleSetView(version.version(), version.sourceHash(),
                version.createdAt(), version.note(), canonicalize(payload));
    }

    private RuleSet toRuleSet(RuleSetView view) {
        RulePayload p = canonicalize(view.rules());
        BlacklistPolicy blacklist = new BlacklistPolicy(
                BlacklistMatchMode.valueOf(normalizeMatchMode(p.blacklistMatchMode())),
                p.blacklistJob(), p.blacklistCompany(), p.blacklistRecruiter(), p.whitelist());
        return new RuleSet(
                view.version(),
                view.sourceHash(),
                p.highestDegree(),
                SalaryStrategy.valueOf(normalizeStrategy(p.salaryStrategy())),
                p.expectedSalaryMin(),
                p.expectedSalaryMax(),
                p.jobDirections(),
                p.cities(),
                p.blacklistCities(),
                p.workTypes(),
                blacklist,
                p.filterDeadHr(),
                p.deadStatus());
    }

    private static String ruleVersionTag(RuleSet rules) {
        return JobRuleEngine.RULE_VERSION + "@v" + rules.configVersion();
    }

    // ==================== 预览 ====================

    /**
     * 规则预览：只判定、不发送、不写库。
     */
    public List<PreviewResult> preview(RulePayload payloadOrNull, List<PreviewSample> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new RuleValidationException("samples 至少要提供一个样例");
        }
        if (samples.size() > 100) {
            throw new RuleValidationException("samples 最多 100 个");
        }
        RuleSet rules;
        if (payloadOrNull == null) {
            // 预览只是查看当前生效版本，不触发 live 自动同步（自动同步只发生在任务启动时）
            rules = toRuleSet(getActive());
        } else {
            validate(payloadOrNull);
            RulePayload canonical = canonicalize(payloadOrNull);
            rules = toRuleSet(new RuleSetView(0, payloadHash(canonical), now(), null, canonical));
        }
        JobRuleEngine engine = new JobRuleEngine(rules);
        List<PreviewResult> results = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            PreviewSample sample = samples.get(i);
            JobSnapshot snapshot = toPreviewSnapshot(sample, i);
            RuleDecision decision = engine.evaluate(snapshot);
            results.add(new PreviewResult(i,
                    sample == null ? null : sample.jobName(),
                    decision.outcome() == null ? RuleOutcome.REVIEW.name() : decision.outcome().name(),
                    decision.reason() == null ? null : decision.reason().name(),
                    decision.evidence(), decision.detail()));
        }
        return results;
    }

    private JobSnapshot toPreviewSnapshot(PreviewSample sample, int index) {
        if (sample == null) {
            return JobSnapshot.builder().encryptId("preview-" + (index + 1)).build();
        }
        String encryptId = sample.encryptId() == null || sample.encryptId().isBlank()
                ? "preview-" + (index + 1) : sample.encryptId();
        return JobSnapshot.builder()
                .encryptId(encryptId)
                .jobName(sample.jobName())
                .companyName(sample.companyName())
                .salary(sample.salary())
                .degree(sample.degree())
                .experience(sample.experience())
                .city(sample.city())
                .jobDescription(sample.jd())
                .hrName(sample.hrName())
                .hrActiveStatus(sample.hrActiveStatus())
                .build();
    }

    // ==================== 重评 ====================

    /**
     * 用指定规则版本重新评估指定岗位，只追加 job_evaluation，不发送、不改投递状态。
     */
    public RecheckResult recheck(Integer ruleVersion, String runId, List<String> encryptIds, int limit) {
        // 重评只读当前生效快照（或指定历史版本），不在查询路径上触发 live 自动同步
        RuleSet rules = ruleVersion == null ? toRuleSet(getActive()) : toRuleSet(getVersion(ruleVersion));
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        List<Map<String, Object>> rows = bossQueryRepository.findJobRowsForRecheck(encryptIds, runId, safeLimit);
        JobRuleEngine engine = new JobRuleEngine(rules);
        String evaluationRunId = runId == null || runId.isBlank()
                ? "recheck-v" + rules.configVersion() : runId;
        int passed = 0;
        int rejected = 0;
        int reviewed = 0;
        for (Map<String, Object> row : rows) {
            try {
                JobSnapshot snapshot = toSnapshot(row);
                if (snapshot == null) {
                    continue;
                }
                RuleDecision decision = engine.evaluate(snapshot);
                if (decision.outcome() == RuleOutcome.PASS) {
                    passed++;
                } else if (decision.outcome() == RuleOutcome.REJECT) {
                    rejected++;
                } else {
                    reviewed++;
                }
                evaluationRepository.record(EvaluationEntry.builder()
                        .runId(evaluationRunId)
                        .encryptId(snapshot.encryptId())
                        .encryptUserId(snapshot.encryptUserId())
                        .jobName(snapshot.jobName())
                        .companyName(snapshot.companyName())
                        .jobUrl(snapshot.jobUrl())
                        .snapshotHash(snapshot.snapshotHash())
                        .ruleVersion(ruleVersionTag(rules))
                        .ruleConfigVersion(rules.configVersion())
                        .rulesHash(rules.sourceHash())
                        .ruleDecision(decision.outcome() == null ? null : decision.outcome().name())
                        .ruleReasonCode(decision.reason() == null ? null : decision.reason().name())
                        .ruleEvidence(decision.evidence())
                        .build());
            } catch (Exception e) {
                log.warn("重评岗位失败（已跳过该岗位）：{}", e.getMessage());
            }
        }
        log.info("规则重评完成 | ruleVersion=v{} | evaluated={} | pass={} reject={} review={}",
                rules.configVersion(), rows.size(), passed, rejected, reviewed);
        return new RecheckResult(rules.configVersion(), rows.size(), passed, rejected, reviewed);
    }

    private JobSnapshot toSnapshot(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        String encryptId = text(row, "encryptId");
        if (encryptId == null) {
            return null;
        }
        return JobSnapshot.builder()
                .encryptId(encryptId)
                .encryptUserId(text(row, "encryptUserId"))
                .jobName(text(row, "jobName"))
                .salary(text(row, "salary"))
                .city(firstText(row, "city", "location"))
                .experience(text(row, "experience"))
                .degree(text(row, "degree"))
                .jobDescription(text(row, "jobDescription"))
                .companyName(text(row, "companyName"))
                .industry(text(row, "industry"))
                .financingStage(text(row, "financingStage"))
                .companyScale(text(row, "companyScale"))
                .companyAddress(text(row, "companyAddress"))
                .recruitmentStatus(text(row, "recruitmentStatus"))
                .hrName(text(row, "hrName"))
                .hrPosition(text(row, "hrPosition"))
                .hrActiveStatus(text(row, "hrActiveStatus"))
                .jobUrl(text(row, "jobUrl"))
                .build();
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstText(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            String value = text(row, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 供健康检查/前端展示：当前规则版本号；无版本返回 0。 */
    public int currentVersion() {
        RuleConfigRepository.RuleConfigVersion latest = ruleConfigRepository.latest();
        return latest == null ? 0 : latest.version();
    }

    /** 空数组常量，避免调用方重复创建。 */
    public static List<String> emptyList() {
        return Arrays.asList();
    }
}
