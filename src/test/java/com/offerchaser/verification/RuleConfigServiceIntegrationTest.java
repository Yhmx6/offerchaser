package com.offerchaser.verification;

import com.offerchaser.application.delivery.RuleOutcome;
import com.offerchaser.application.delivery.RuleReason;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.persistence.RuleConfigRepository;
import com.offerchaser.application.rules.PreviewResult;
import com.offerchaser.application.rules.PreviewSample;
import com.offerchaser.application.rules.RecheckResult;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.rules.RulePayload;
import com.offerchaser.application.rules.RuleSetView;
import com.offerchaser.application.rules.RuleValidationException;
import com.offerchaser.application.rules.RuleVersionConflictException;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.boss.BossConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B2 规则配置/预览/重评的真实 SQLite 集成验收。
 * <p>
 * 使用真实 {@link RuleConfigRepository}/{@link BossQueryRepository}/{@link EvaluationRepository}
 * 与生产 {@link RuleConfigService}（BossService 用 mock 提供 live 配置），覆盖：
 * 首次 bootstrap v1、保存 v2/v3、同 payload 同 hash、版本冲突、live 配置变化自动同步、
 * preview 零写、recheck 只追加 job_evaluation 且不改投递状态/不产生 attempt。
 */
class RuleConfigServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private RuleConfigRepository ruleConfigRepository;
    private BossQueryRepository bossQueryRepository;
    private EvaluationRepository evaluationRepository;
    private BossService bossService;
    private RuleConfigService ruleConfigService;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        createLegacyBossDataTable();
        ruleConfigRepository = new RuleConfigRepository(dataSource);
        bossQueryRepository = new BossQueryRepository(dataSource);
        evaluationRepository = new EvaluationRepository(dataSource, "Asia/Shanghai");
        bossService = mock(BossService.class);
        when(bossService.loadBossConfig()).thenReturn(liveConfig(20, 40, "今日活跃"));
        when(bossService.getBlackJobs()).thenReturn(Set.of());
        when(bossService.getBlackCompanies()).thenReturn(Set.of("某某外包"));
        when(bossService.getBlackRecruiters()).thenReturn(Set.of());
        ruleConfigService = new RuleConfigService(ruleConfigRepository, bossService,
                evaluationRepository, bossQueryRepository);
    }

    // ==================== 版本 / bootstrap ====================

    @Test
    void firstGetActiveBootstrapsVersionOneFromLiveDefaults() {
        RuleSetView active = ruleConfigService.getActive();

        assertEquals(1, active.version());
        assertEquals(1, ruleConfigRepository.latest().version());
        assertNotNull(active.sourceHash());
        assertTrue(active.sourceHash().matches("[0-9a-f]{64}"), "sourceHash 应是 sha256 hex");
        assertNotNull(ruleConfigRepository.latest().liveHash(),
                "V6：初始化 v1 必须记录 live 基线哈希，否则首次任务启动会误同步");
        assertEquals("本科", active.rules().highestDegree());
        assertEquals(20, active.rules().expectedSalaryMin());
        assertEquals(40, active.rules().expectedSalaryMax());
        assertEquals(List.of("某某外包"), active.rules().blacklistCompany());
        assertEquals(1, ruleConfigService.currentVersion());
        // 幂等：再次读取不会重复 bootstrap
        assertEquals(1, ruleConfigService.getActive().version());
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM rule_config_version"));

        // live 未变化：任务启动取快照不应 bump
        assertEquals(1, ruleConfigService.getActiveRuleSet().configVersion());
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM rule_config_version"));

        RulePayload live = ruleConfigService.getLiveDefaults();
        assertEquals("本科", live.highestDegree());
        assertEquals(20, live.expectedSalaryMin());
    }

    @Test
    void saveCreatesNextVersionAndSamePayloadKeepsSameHash() {
        ruleConfigService.getActive();
        RulePayload payload = customPayload("硕士", List.of("Java", "后端"), List.of("上海"));
        RuleSetView v2 = ruleConfigService.save(payload, 1, "调整学历");
        assertEquals(2, v2.version());
        assertEquals("调整学历", v2.note());
        assertNotNull(v2.sourceHash());
        assertEquals("硕士", v2.rules().highestDegree());
        assertEquals(List.of("Java", "后端"), v2.rules().jobDirections());

        RuleSetView v3 = ruleConfigService.save(payload, 2, "重复内容");
        assertEquals(3, v3.version());
        assertEquals(v2.sourceHash(), v3.sourceHash(), "相同 payload 必须得到相同 sourceHash");

        List<RuleSetView> versions = ruleConfigService.listVersions(10);
        assertEquals(3, versions.size());
        assertEquals(3, versions.get(0).version(), "版本历史应按 version DESC 倒序");
        assertEquals(1, versions.get(2).version());
        assertEquals(1, ruleConfigService.getVersion(1).version());

        RuleVersionConflictException conflict = assertThrows(RuleVersionConflictException.class,
                () -> ruleConfigService.save(customPayload("本科", List.of(), List.of()), 1, "过期版本"));
        assertEquals(3, conflict.getCurrentVersion());
        assertEquals(3, ruleConfigService.currentVersion(), "冲突不得创建新版本");

        assertThrows(RuleValidationException.class, () -> ruleConfigService.getVersion(99));
    }

    @Test
    void invalidPayloadIsRejectedWithoutWritingVersion() {
        ruleConfigService.getActive();
        int before = scalar(dataSource, "SELECT COUNT(*) FROM rule_config_version");

        assertThrows(RuleValidationException.class, () -> ruleConfigService.save(null, 1, null));
        assertThrows(RuleValidationException.class,
                () -> ruleConfigService.save(payloadWithDegree("初中"), 1, null));
        assertThrows(RuleValidationException.class,
                () -> ruleConfigService.save(withSalary(payloadWithDegree("本科"), 50, 30), 1, null));
        assertThrows(RuleValidationException.class,
                () -> ruleConfigService.save(withStrategy(payloadWithDegree("本科"), "AVG"), 1, null));
        assertThrows(RuleValidationException.class,
                () -> ruleConfigService.save(withMatchMode(payloadWithDegree("本科"), "FUZZY"), 1, null));
        assertThrows(RuleValidationException.class,
                () -> ruleConfigService.save(withEmptyListItem(payloadWithDegree("本科"), "jobDirections"), 1, null));

        assertEquals(before, scalar(dataSource, "SELECT COUNT(*) FROM rule_config_version"),
                "校验失败不得写入版本");
    }

    // ==================== 自动同步（V6 / 方案 F 语义） ====================

    /**
     * 回归 a：显式保存后，下一次任务启动（getActiveRuleSet）不得覆盖显式规则。
     * 修复前该场景会被 live 默认值生成新版本覆盖（B2 红队探针复现）。
     */
    @Test
    void explicitSaveIsNotOverwrittenByTaskStartSync() {
        ruleConfigService.getActive();
        RulePayload explicit = fullCustomPayload();
        RuleSetView v2 = ruleConfigService.save(explicit, 1, "管理员显式规则");
        assertEquals(2, v2.version());

        var taskStartRules = ruleConfigService.getActiveRuleSet();

        assertEquals(2, taskStartRules.configVersion(), "live 未变化时任务启动必须原样使用显式保存的 v2");
        assertEquals("硕士", taskStartRules.highestDegree());
        assertEquals(30, taskStartRules.expectedSalaryMin());
        assertEquals(List.of("Java"), taskStartRules.jobDirections());
        assertEquals(List.of("上海"), taskStartRules.cities());
        assertEquals(2, scalar(dataSource, "SELECT COUNT(*) FROM rule_config_version"),
                "不得因任务启动生成新版本");
        assertEquals(2, ruleConfigService.getActive().version());
    }

    /**
     * 回归 b/c：live 配置真变化 → 生成新版本，但只合并 live-owned 字段（黑名单/HR 过滤），
     * 保留规则页显式设置的学历/薪资/方向/城市/白名单/匹配模式；再次调用不 bump。
     */
    @Test
    void liveChangeAutoSyncsOnlyLiveOwnedFieldsAndKeepsExplicitRules() {
        ruleConfigService.getActive();
        ruleConfigService.save(fullCustomPayload(), 1, "管理员显式规则");

        // live 变化：黑名单新增、HR 过滤开启/deadStatus 变化
        when(bossService.getBlackJobs()).thenReturn(Set.of("外包"));
        when(bossService.getBlackCompanies()).thenReturn(Set.of("某某外包", "新黑名单公司"));
        when(bossService.loadBossConfig()).thenReturn(liveConfigWithHrFilter(20, 40, "今日活跃"));

        var synced = ruleConfigService.getActiveRuleSet();

        assertEquals(3, synced.configVersion(), "live 真变化应自动生成 v3");
        // live-owned 字段已合并
        assertEquals(List.of("外包"), synced.blacklist().job());
        assertTrue(synced.blacklist().company().contains("新黑名单公司"));
        assertEquals(Boolean.TRUE, synced.filterDeadHr());
        assertEquals(List.of("今日活跃"), synced.deadStatus());
        // 显式规则字段保留
        assertEquals("硕士", synced.highestDegree());
        assertEquals(30, synced.expectedSalaryMin());
        assertEquals(60, synced.expectedSalaryMax());
        assertEquals(List.of("Java"), synced.jobDirections());
        assertEquals(List.of("上海"), synced.cities());
        assertEquals(List.of("目标公司"), synced.blacklist().whitelist());
        assertEquals(com.offerchaser.application.delivery.BlacklistMatchMode.EXACT,
                synced.blacklist().matchMode());
        assertEquals(2, ruleConfigRepository.findByVersion(2).version(), "旧显式版本必须保留");

        // live 未再变化 → 不再 bump
        assertEquals(3, ruleConfigService.getActiveRuleSet().configVersion());
        assertEquals(3, scalar(dataSource, "SELECT COUNT(*) FROM rule_config_version"));
    }

    /** 旧版本 live_hash=null：视为需要同步一次，之后恢复“live 不变不 bump”。 */
    @Test
    void legacyVersionWithNullLiveHashSyncsOnce() {
        ruleConfigService.getActive();
        execute(dataSource, "UPDATE rule_config_version SET live_hash = NULL WHERE version = 1");

        var synced = ruleConfigService.getActiveRuleSet();
        assertEquals(2, synced.configVersion(), "旧版本 live_hash=null 应触发一次合并同步");
        assertNotNull(ruleConfigRepository.latest().liveHash(), "同步后新版本必须记录 live_hash");
        assertEquals(2, ruleConfigService.getActiveRuleSet().configVersion(),
                "同步一次后 live 不变，不应继续 bump");
        assertEquals(2, scalar(dataSource, "SELECT COUNT(*) FROM rule_config_version"));
    }

    /**
     * 回归 d：preview/recheck 只读当前生效版本，不能在查询路径触发自动同步/创建版本。
     */
    @Test
    void previewAndRecheckDoNotTriggerAutoSync() {
        ruleConfigService.getActive();
        ruleConfigService.save(fullCustomPayload(), 1, "管理员显式规则");
        // 故意制造 live 变化，但 preview/recheck 不应因此生成版本
        when(bossService.getBlackJobs()).thenReturn(Set.of("外包"));
        int versionsBefore = scalar(dataSource, "SELECT COUNT(*) FROM rule_config_version");
        insertBossDataRow("ENC-P1", "USER-P1", "Java 开发", "示例科技", "30-40K", "硕士",
                "负责后端服务开发，熟悉 Java 与 Spring。", "未投递");

        List<PreviewResult> results = ruleConfigService.preview(null, List.of(
                new PreviewSample("Java 开发", "示例科技", "30-40K", "硕士", "3-5年", "上海",
                        "负责后端服务开发，熟悉 Java 与 Spring。", "张女士", "今日活跃", null)));
        assertEquals(1, results.size());
        assertEquals("PASS", results.get(0).decision(),
                "preview 必须使用当前生效 v2（最高学历=硕士），而不是 live 默认本科");
        RecheckResult recheck = ruleConfigService.recheck(null, null, null, 10);
        assertEquals(2, recheck.ruleVersion(), "recheck 默认使用当前 active=v2");
        assertEquals(2, scalar(dataSource,
                "SELECT rule_config_version FROM job_evaluation WHERE encrypt_id='ENC-P1'"));

        assertEquals(versionsBefore, scalar(dataSource, "SELECT COUNT(*) FROM rule_config_version"),
                "preview/recheck 不得触发自动同步/创建版本");
        assertEquals(2, ruleConfigService.getActive().version());
    }

    // ==================== 预览：零写 ====================

    @Test
    void previewReturnsDecisionsWithoutWritingAnything() {
        ruleConfigService.getActive();
        int evaluationsBefore = scalar(dataSource, "SELECT COUNT(*) FROM job_evaluation");
        int attemptsBefore = scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt");

        List<PreviewResult> results = ruleConfigService.preview(null, List.of(
                new PreviewSample("Java 开发", "示例科技", "20-30K", "本科", "3-5年", "上海",
                        "负责后端服务开发，熟悉 Java 与 Spring。", "张女士", "今日活跃", null),
                new PreviewSample("Java 架构师", "示例科技", "面议", "本科", "5-10年", "上海",
                        "负责系统架构设计，熟悉分布式与高并发。", "李先生", "刚刚活跃", null),
                new PreviewSample("销售专员", "示例销售", "20-30K", "大专", "1-3年", "上海",
                        "负责客户开发与产品销售，完成销售目标。", "王女士", "今日活跃", null)));

        assertEquals(3, results.size());
        assertEquals("PASS", results.get(0).decision(), "满足规则应 PASS，实际：" + results.get(0));
        assertEquals("REVIEW", results.get(1).decision(), "面议薪资应 REVIEW：" + results.get(1));
        assertEquals(RuleReason.SALARY_UNPARSABLE.name(), results.get(1).reason());
        assertNotNull(results.get(0).jobName());
        assertNotNull(results.get(0).reason());
        // 零写：不新增评估、不产生 attempt
        assertEquals(evaluationsBefore, scalar(dataSource, "SELECT COUNT(*) FROM job_evaluation"),
                "preview 不得写 job_evaluation");
        assertEquals(attemptsBefore, scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt"),
                "preview 不得产生投递尝试");
    }

    @Test
    void previewWithExplicitPayloadUsesThatRuleConfig() {
        RulePayload strict = customPayload("硕士", List.of(), List.of());
        List<PreviewResult> results = ruleConfigService.preview(strict, List.of(
                new PreviewSample("Java 开发", "示例科技", "20-30K", "硕士", "3-5年", "上海",
                        "负责后端服务开发，熟悉 Java 与 Spring。", "张女士", "今日活跃", null)));

        assertEquals(1, results.size());
        assertEquals("PASS", results.get(0).decision(),
                "用户最高学历=硕士时，硕士岗位不应被学历拒绝：" + results.get(0));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM rule_config_version"),
                "preview 显式 payload 不应写版本");
    }

    // ==================== 重评：只写 job_evaluation ====================

    @Test
    void recheckOnlyAppendsEvaluationsAndKeepsSendingStateUntouched() {
        RuleSetView active = ruleConfigService.getActive();
        insertBossDataRow("ENC-R1", "USER-1", "Java 开发", "示例科技", "20-30K", "本科",
                "负责后端服务开发，熟悉 Java 与 Spring。", "已投递");
        insertBossDataRow("ENC-R2", "USER-2", "后端开发", "示例科技", "25-35K", "本科",
                "负责服务端接口开发与维护，熟悉数据库。", "未投递");
        int attemptsBefore = scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt");

        RecheckResult first = ruleConfigService.recheck(null, null, null, 50);

        assertEquals(active.version(), first.ruleVersion());
        assertEquals(2, first.evaluated());
        assertEquals(2, first.passed(), "两条合规岗位都应 PASS：" + first);
        assertEquals(0, first.rejected());
        assertEquals(0, first.reviewed());

        assertEquals(2, scalar(dataSource, "SELECT COUNT(*) FROM job_evaluation"));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM job_evaluation WHERE " +
                "rule_config_version IS NULL OR rules_hash IS NULL"), "重评记录必须带规则版本/哈希");
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM job_evaluation WHERE " +
                "ai_decision IS NOT NULL OR ai_analysis IS NOT NULL"), "重评不得写 AI 字段");
        assertNotNull(scalarString(dataSource,
                "SELECT rules_hash FROM job_evaluation WHERE encrypt_id='ENC-R1'"));
        assertEquals(active.version(), scalar(dataSource,
                "SELECT rule_config_version FROM job_evaluation WHERE encrypt_id='ENC-R1'"));
        assertEquals("PASS", scalarString(dataSource,
                "SELECT rule_decision FROM job_evaluation WHERE encrypt_id='ENC-R1'"));

        // 零副作用：不产生 attempt、不改 boss_data 投递状态
        assertEquals(attemptsBefore, scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt"),
                "recheck 不得产生 delivery_attempt");
        assertEquals("已投递", scalarString(dataSource,
                "SELECT delivery_status FROM boss_data WHERE encrypt_id='ENC-R1'"));
        assertEquals("未投递", scalarString(dataSource,
                "SELECT delivery_status FROM boss_data WHERE encrypt_id='ENC-R2'"));

        // 重复调用追加记录且决策可复现
        RecheckResult second = ruleConfigService.recheck(null, null, null, 50);
        assertEquals(2, second.evaluated());
        assertEquals(4, scalar(dataSource, "SELECT COUNT(*) FROM job_evaluation"),
                "重复 recheck 应追加记录而不是覆盖");
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(DISTINCT rule_decision) FROM job_evaluation WHERE encrypt_id='ENC-R1'"),
                "同输入两次重评的决策应可复现");
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt"));
    }

    @Test
    void recheckWithUnknownRuleVersionIsRejected() {
        ruleConfigService.getActive();
        assertThrows(RuleValidationException.class,
                () -> ruleConfigService.recheck(99, null, null, 10));
        assertThrows(RuleValidationException.class,
                () -> ruleConfigService.preview(null, List.of()));
    }

    // ==================== 工具 ====================

    /** 迁移只建阶段表，boss_data 是旧库表；重评数据源需要它存在（脱敏夹具，仅测试用）。 */
    private void createLegacyBossDataTable() {
        execute(dataSource, "CREATE TABLE IF NOT EXISTS boss_data (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, encrypt_user_id TEXT, " +
                "company_name TEXT, job_name TEXT, salary TEXT, location TEXT, experience TEXT, " +
                "degree TEXT, hr_name TEXT, hr_position TEXT, hr_active_status TEXT, " +
                "delivery_status TEXT, job_description TEXT, job_url TEXT, recruitment_status TEXT, " +
                "company_address TEXT, industry TEXT, introduce TEXT, financing_stage TEXT, " +
                "company_scale TEXT, created_at TEXT, updated_at TEXT)");
    }

    private BossConfig liveConfig(int min, int max, String deadStatus) {
        BossConfig config = new BossConfig();
        config.setExpectedSalary(List.of(min, max));
        config.setFilterDeadHR(false);
        config.setDeadStatus(List.of(deadStatus));
        return config;
    }

    private BossConfig liveConfigWithHrFilter(int min, int max, String deadStatus) {
        BossConfig config = new BossConfig();
        config.setExpectedSalary(List.of(min, max));
        config.setFilterDeadHR(true);
        config.setDeadStatus(List.of(deadStatus));
        return config;
    }

    /** 管理员在规则页显式设置的完整 payload（与 live 默认不同：学历/薪资/方向/城市/白名单/匹配模式）。 */
    private RulePayload fullCustomPayload() {
        return new RulePayload("硕士", "INTERSECT", 30, 60,
                List.of("Java"), List.of("上海"), List.of(), "EXACT",
                List.of("旧职位黑名单"), List.of("旧公司黑名单"), List.of(), List.of("目标公司"),
                false, List.of());
    }

    private RulePayload customPayload(String degree, List<String> directions, List<String> cities) {
        return new RulePayload(degree, "INTERSECT", 20, 40, directions, cities, List.of(),
                "CONTAINS", List.of(), List.of("某某外包"), List.of(), List.of(), false, List.of());
    }

    private RulePayload payloadWithDegree(String degree) {
        return customPayload(degree, List.of(), List.of());
    }

    private RulePayload withSalary(RulePayload payload, int min, int max) {
        return new RulePayload(payload.highestDegree(), payload.salaryStrategy(), min, max,
                payload.jobDirections(), payload.cities(), payload.workTypes(),
                payload.blacklistMatchMode(), payload.blacklistJob(), payload.blacklistCompany(),
                payload.blacklistRecruiter(), payload.whitelist(), payload.filterDeadHr(),
                payload.deadStatus());
    }

    private RulePayload withStrategy(RulePayload payload, String strategy) {
        return new RulePayload(payload.highestDegree(), strategy, payload.expectedSalaryMin(),
                payload.expectedSalaryMax(), payload.jobDirections(), payload.cities(),
                payload.workTypes(), payload.blacklistMatchMode(), payload.blacklistJob(),
                payload.blacklistCompany(), payload.blacklistRecruiter(), payload.whitelist(),
                payload.filterDeadHr(), payload.deadStatus());
    }

    private RulePayload withMatchMode(RulePayload payload, String mode) {
        return new RulePayload(payload.highestDegree(), payload.salaryStrategy(),
                payload.expectedSalaryMin(), payload.expectedSalaryMax(), payload.jobDirections(),
                payload.cities(), payload.workTypes(), mode, payload.blacklistJob(),
                payload.blacklistCompany(), payload.blacklistRecruiter(), payload.whitelist(),
                payload.filterDeadHr(), payload.deadStatus());
    }

    private RulePayload withEmptyListItem(RulePayload payload, String field) {
        return new RulePayload(payload.highestDegree(), payload.salaryStrategy(),
                payload.expectedSalaryMin(), payload.expectedSalaryMax(),
                "jobDirections".equals(field) ? java.util.Arrays.asList("Java", " ") : payload.jobDirections(),
                payload.cities(), payload.workTypes(), payload.blacklistMatchMode(),
                payload.blacklistJob(), payload.blacklistCompany(), payload.blacklistRecruiter(),
                payload.whitelist(), payload.filterDeadHr(), payload.deadStatus());
    }

    private void insertBossDataRow(String encryptId, String encryptUserId, String jobName,
                                   String companyName, String salary, String degree,
                                   String jd, String deliveryStatus) {
        execute(dataSource, "INSERT INTO boss_data(encrypt_id, encrypt_user_id, company_name, job_name, " +
                        "salary, location, experience, degree, hr_name, hr_position, hr_active_status, " +
                        "delivery_status, job_description, job_url, recruitment_status, created_at, updated_at) " +
                        "VALUES(?, ?, ?, ?, ?, '上海', '3-5年', ?, '张女士', '招聘专员', '刚刚活跃', ?, ?, " +
                        "'https://example.invalid/job/recheck', '招聘中', '2026-11-04 19:00:00', " +
                        "'2026-11-04 19:00:00')",
                encryptId, encryptUserId, companyName, jobName, salary, degree, deliveryStatus, jd);
    }

    @SuppressWarnings("unused")
    private static RuleOutcome noop(RuleOutcome value) {
        return value;
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> noopMap() {
        return Map.of();
    }
}
