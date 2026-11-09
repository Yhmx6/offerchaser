package com.offerchaser.application.delivery;

import com.offerchaser.worker.boss.BossConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JobRuleEngine 单元测试：覆盖通过/拒绝/边界/未知四类情况，以及规则顺序、黑名单证据、两种薪资策略。
 * <p>
 * 不启动 Spring，直接 new 引擎 + new BossConfig。
 */
class JobRuleEngineTest {

    private static final double DELTA = 1e-6;

    // ==================== 测试脚手架 ====================

    /** 基础配置：不启用水印过滤/HR 过滤/期望薪资，避免干扰单条规则用例。 */
    private BossConfig baseConfig() {
        BossConfig config = new BossConfig();
        config.setFilterDeadHR(false);
        config.setDeadStatus(new ArrayList<>());
        config.setExpectedSalary(new ArrayList<>());
        return config;
    }

    /** 一条各字段齐全、能通过所有默认规则的岗位快照。 */
    private JobSnapshot.Builder baseSnapshot() {
        return JobSnapshot.builder()
                .encryptId("enc-1")
                .encryptUserId("user-1")
                .jobName("Java后端开发")
                .salary("20-40K")
                .city("上海")
                .experience("3-5年")
                .degree("本科")
                .jobDescription("负责后端服务开发与维护，参与需求评审和架构设计")
                .companyName("杭州某某科技有限公司")
                .industry("互联网")
                .financingStage("B轮")
                .companyScale("100-499人")
                .companyAddress("杭州市余杭区")
                .recruitmentStatus("招聘中")
                .hrName("李四")
                .hrPosition("技术招聘")
                .hrActiveStatus("今日活跃")
                .jobUrl("https://www.zhipin.com/job_detail/enc-1.html")
                .sourceKeyword("Java")
                .sourceCity("上海");
    }

    private JobRuleEngine engine(BossConfig config) {
        return new JobRuleEngine(config, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT);
    }

    private JobRuleEngine engineWithSalary(int expectedMin, int expectedMax, SalaryStrategy strategy) {
        BossConfig config = baseConfig();
        config.setExpectedSalary(new ArrayList<>(List.of(expectedMin, expectedMax)));
        return new JobRuleEngine(config, Set.of(), Set.of(), Set.of(), strategy);
    }

    // ==================== 通过样例 ====================

    @Test
    void passesWhenAllRulesAreSatisfied() {
        RuleDecision decision = engine(baseConfig()).evaluate(baseSnapshot().build());

        assertEquals(RuleOutcome.PASS, decision.outcome());
        assertEquals(RuleReason.PASS, decision.reason());
        assertNull(decision.evidence());
        assertNull(decision.detail());
    }

    @Test
    void passesWithUndergraduateDegree() {
        RuleDecision decision = engine(baseConfig()).evaluate(baseSnapshot().degree("大专").build());
        assertEquals(RuleOutcome.PASS, decision.outcome());
    }

    @Test
    void passesWhenSalaryMatchesExpectedWithIntersect() {
        RuleDecision decision = engineWithSalary(20, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("20-40K").build());
        assertEquals(RuleOutcome.PASS, decision.outcome());
    }

    // ==================== 身份与内容缺失 ====================

    @Test
    void rejectsMissingIdentity() {
        RuleDecision nullId = engine(baseConfig()).evaluate(baseSnapshot().encryptId(null).build());
        assertEquals(RuleOutcome.REJECT, nullId.outcome());
        assertEquals(RuleReason.MISSING_IDENTITY, nullId.reason());
        assertNotNull(nullId.detail());

        RuleDecision blankId = engine(baseConfig()).evaluate(baseSnapshot().encryptId("   ").build());
        assertEquals(RuleReason.MISSING_IDENTITY, blankId.reason());
    }

    @Test
    void rejectsMissingOrTooShortJobContent() {
        RuleDecision shortJd = engine(baseConfig()).evaluate(baseSnapshot().jobDescription("太短").build());
        assertEquals(RuleOutcome.REJECT, shortJd.outcome());
        assertEquals(RuleReason.MISSING_JOB_CONTENT, shortJd.reason());

        RuleDecision nullJd = engine(baseConfig()).evaluate(baseSnapshot().jobDescription(null).build());
        assertEquals(RuleReason.MISSING_JOB_CONTENT, nullJd.reason());

        RuleDecision blankName = engine(baseConfig()).evaluate(baseSnapshot().jobName(" ").build());
        assertEquals(RuleReason.MISSING_JOB_CONTENT, blankName.reason());
    }

    @Test
    void acceptsJdWithExactlyTenNonWhitespaceChars() {
        RuleDecision decision = engine(baseConfig())
                .evaluate(baseSnapshot().jobDescription("0123456789").build());
        assertEquals(RuleOutcome.PASS, decision.outcome());
    }

    // ==================== 学历 ====================

    @Test
    void rejectsMasterDoctorAndGraduateDegreeRequirements() {
        for (String degree : List.of("硕士", "博士", "研究生")) {
            RuleDecision decision = engine(baseConfig()).evaluate(baseSnapshot().degree(degree).build());
            assertEquals(RuleOutcome.REJECT, decision.outcome(), "学历：" + degree);
            assertEquals(RuleReason.DEGREE_TOO_HIGH, decision.reason(), "学历：" + degree);
            assertEquals(degree, decision.evidence(), "证据应为命中的学历要求");
        }
    }

    @Test
    void degreeRuleIgnoresBlankDegree() {
        RuleDecision decision = engine(baseConfig()).evaluate(baseSnapshot().degree(null).build());
        assertEquals(RuleOutcome.PASS, decision.outcome());
    }

    // ==================== 黑名单 ====================

    @Test
    void rejectsJobBlacklistAndReturnsMatchedTerm() {
        JobRuleEngine engine = new JobRuleEngine(baseConfig(), Set.of(), Set.of(), Set.of("外包"), SalaryStrategy.INTERSECT);

        RuleDecision decision = engine.evaluate(baseSnapshot().jobName("Java开发（外包）").build());

        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.JOB_BLACKLIST, decision.reason());
        assertEquals("外包", decision.evidence(), "evidence 必须是命中的黑名单词");
    }

    @Test
    void rejectsCompanyBlacklistAndReturnsMatchedTerm() {
        JobRuleEngine engine = new JobRuleEngine(baseConfig(), Set.of("某某科技"), Set.of(), Set.of(), SalaryStrategy.INTERSECT);

        RuleDecision decision = engine.evaluate(baseSnapshot().build());

        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.COMPANY_BLACKLIST, decision.reason());
        assertEquals("某某科技", decision.evidence());
    }

    @Test
    void rejectsRecruiterBlacklistByHrPosition() {
        JobRuleEngine engine = new JobRuleEngine(baseConfig(), Set.of(), Set.of("技术招聘"), Set.of(), SalaryStrategy.INTERSECT);

        RuleDecision decision = engine.evaluate(baseSnapshot().build());

        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.RECRUITER_BLACKLIST, decision.reason());
        assertEquals("技术招聘", decision.evidence());
    }

    @Test
    void blacklistMatchingUsesContainsSemantics() {
        JobRuleEngine engine = new JobRuleEngine(baseConfig(), Set.of("科技"), Set.of("招聘"), Set.of("Java"), SalaryStrategy.INTERSECT);

        // 公司名「杭州某某科技有限公司」包含「科技」，职位名包含「Java」，按顺序先命中职位黑名单
        RuleDecision decision = engine.evaluate(baseSnapshot().build());
        assertEquals(RuleReason.JOB_BLACKLIST, decision.reason());
        assertEquals("Java", decision.evidence());
    }

    @Test
    void blankAndNullBlacklistEntriesNeverThrow() {
        JobRuleEngine engine = new JobRuleEngine(baseConfig(),
                Set.of(" ", ""), Set.of(), Set.of(" "), SalaryStrategy.INTERSECT);

        RuleDecision decision = assertDoesNotThrow(() -> engine.evaluate(baseSnapshot().build()));
        assertEquals(RuleOutcome.PASS, decision.outcome());
    }

    // ==================== HR 活跃度 ====================

    @Test
    void rejectsInactiveHrOnlyWhenFilterEnabled() {
        BossConfig enabled = baseConfig();
        enabled.setFilterDeadHR(true);
        enabled.setDeadStatus(new ArrayList<>(List.of("本月活跃", "今日活跃")));
        JobRuleEngine enabledEngine = new JobRuleEngine(enabled, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT);

        RuleDecision inactive = enabledEngine.evaluate(baseSnapshot().hrActiveStatus("本月活跃").build());
        assertEquals(RuleOutcome.REJECT, inactive.outcome());
        assertEquals(RuleReason.HR_INACTIVE, inactive.reason());
        assertEquals("本月活跃", inactive.evidence());

        // 同一个 HR 活跃度，开关关闭时不拦截
        JobRuleEngine disabledEngine = engine(baseConfig());
        assertEquals(RuleOutcome.PASS,
                disabledEngine.evaluate(baseSnapshot().hrActiveStatus("本月活跃").build()).outcome());
    }

    @Test
    void activeHrOrMissingActiveStatusPasses() {
        BossConfig config = baseConfig();
        config.setFilterDeadHR(true);
        config.setDeadStatus(new ArrayList<>(List.of("本月活跃")));
        JobRuleEngine engine = new JobRuleEngine(config, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT);

        assertEquals(RuleOutcome.PASS,
                engine.evaluate(baseSnapshot().hrActiveStatus("3天前活跃").build()).outcome());
        assertEquals(RuleOutcome.PASS,
                engine.evaluate(baseSnapshot().hrActiveStatus(null).build()).outcome());
    }

    // ==================== 规则顺序 ====================

    @Test
    void identityRuleRunsBeforeEverythingElse() {
        BossConfig config = baseConfig();
        config.setExpectedSalary(new ArrayList<>(List.of(20, 40)));
        JobRuleEngine engine = new JobRuleEngine(config, Set.of("某某科技"), Set.of(), Set.of(), SalaryStrategy.INTERSECT);

        RuleDecision decision = engine.evaluate(baseSnapshot()
                .encryptId(null).jobName("外包").companyName("某某科技").salary("面议").build());

        assertEquals(RuleReason.MISSING_IDENTITY, decision.reason());
    }

    @Test
    void degreeRuleRunsBeforeJobBlacklist() {
        JobRuleEngine engine = new JobRuleEngine(baseConfig(), Set.of(), Set.of(), Set.of("Java"), SalaryStrategy.INTERSECT);

        RuleDecision decision = engine.evaluate(baseSnapshot().degree("硕士").build());

        assertEquals(RuleReason.DEGREE_TOO_HIGH, decision.reason());
    }

    @Test
    void jobBlacklistRunsBeforeHrActivity() {
        BossConfig config = baseConfig();
        config.setFilterDeadHR(true);
        config.setDeadStatus(new ArrayList<>(List.of("今日活跃")));
        JobRuleEngine engine = new JobRuleEngine(config, Set.of(), Set.of(), Set.of("Java"), SalaryStrategy.INTERSECT);

        RuleDecision decision = engine.evaluate(baseSnapshot().build());

        assertEquals(RuleReason.JOB_BLACKLIST, decision.reason());
    }

    @Test
    void companyBlacklistRunsBeforeHrActivity() {
        // B2 起规则顺序为「黑名单 → HR 活跃度」，公司黑名单先命中
        BossConfig config = baseConfig();
        config.setFilterDeadHR(true);
        config.setDeadStatus(new ArrayList<>(List.of("今日活跃")));
        JobRuleEngine engine = new JobRuleEngine(config, Set.of("某某科技"), Set.of(), Set.of(), SalaryStrategy.INTERSECT);

        RuleDecision decision = engine.evaluate(baseSnapshot().build());

        assertEquals(RuleReason.COMPANY_BLACKLIST, decision.reason());
    }

    @Test
    void blacklistsRunBeforeSalaryRule() {
        BossConfig config = baseConfig();
        config.setExpectedSalary(new ArrayList<>(List.of(20, 40)));
        JobRuleEngine engine = new JobRuleEngine(config, Set.of(), Set.of(), Set.of("Java"), SalaryStrategy.INTERSECT);

        RuleDecision decision = engine.evaluate(baseSnapshot().salary("8-10K").build());

        assertEquals(RuleReason.JOB_BLACKLIST, decision.reason());
    }

    // ==================== 期望薪资：INTERSECT ====================

    @Test
    void intersectRejectsWhenJobSalaryIsTooLow() {
        RuleDecision decision = engineWithSalary(20, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("8-10K").build());

        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.SALARY_NOT_EXPECTED, decision.reason());
        assertEquals("8-10K", decision.evidence());
    }

    @Test
    void intersectRejectsWhenJobSalaryIsTooHigh() {
        RuleDecision decision = engineWithSalary(20, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("50-60K").build());

        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.SALARY_NOT_EXPECTED, decision.reason());
    }

    @Test
    void intersectAcceptsTouchingBoundaries() {
        assertEquals(RuleOutcome.PASS, engineWithSalary(20, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("10-20K").build()).outcome(), "岗位上限等于期望下限应通过");
        assertEquals(RuleOutcome.PASS, engineWithSalary(20, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("40-50K").build()).outcome(), "岗位下限等于期望上限应通过");
    }

    @Test
    void intersectHandlesOpenEndedAndDecimalSalary() {
        assertEquals(RuleOutcome.PASS, engineWithSalary(30, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("25K以上").build()).outcome(), "只有下限的区间与期望有交集");
        assertEquals(RuleOutcome.REJECT, engineWithSalary(30, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("45K以上").build()).outcome(), "下限已超过期望上限");

        RuleDecision decimal = engineWithSalary(20, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("12.5K").build());
        assertEquals(RuleReason.SALARY_NOT_EXPECTED, decimal.reason(), "12.5K 应按 12.5K 判断而不是 125K");
    }

    // ==================== 期望薪资：FLOOR ====================

    @Test
    void floorRejectsOnlyWhenJobCeilingIsBelowExpectationFloor() {
        RuleDecision tooLow = engineWithSalary(20, 40, SalaryStrategy.FLOOR)
                .evaluate(baseSnapshot().salary("8-10K").build());
        assertEquals(RuleReason.SALARY_NOT_EXPECTED, tooLow.reason());

        // FLOOR 只看下限：岗位开得比期望上限高也不拦
        assertEquals(RuleOutcome.PASS, engineWithSalary(20, 40, SalaryStrategy.FLOOR)
                .evaluate(baseSnapshot().salary("45-60K").build()).outcome(),
                "FLOOR 策略下岗位上限高于期望上限不应拒绝");
        assertEquals(RuleOutcome.PASS, engineWithSalary(20, 40, SalaryStrategy.FLOOR)
                .evaluate(baseSnapshot().salary("20-30K").build()).outcome());
    }

    @Test
    void floorPassesWhenUpperBoundIsUnknown() {
        // 只有下限（25K以上）时无法判断上限是否低于期望下限，保守放行
        assertEquals(RuleOutcome.PASS, engineWithSalary(30, 40, SalaryStrategy.FLOOR)
                .evaluate(baseSnapshot().salary("25K以上").build()).outcome());
    }

    @Test
    void nullStrategyFallsBackToIntersect() {
        BossConfig config = baseConfig();
        config.setExpectedSalary(new ArrayList<>(List.of(20, 40)));
        JobRuleEngine engine = new JobRuleEngine(config, Set.of(), Set.of(), Set.of(), null);

        RuleDecision decision = engine.evaluate(baseSnapshot().salary("50-60K").build());

        assertEquals(RuleReason.SALARY_NOT_EXPECTED, decision.reason(),
                "strategy 为 null 时按 INTERSECT 处理（会拦住高于期望上限的岗位）");
    }

    // ==================== 期望薪资：未知/跳过 ====================

    @Test
    void negotiableSalaryIsReviewNotReject() {
        RuleDecision decision = engineWithSalary(20, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("面议").build());

        assertEquals(RuleOutcome.REVIEW, decision.outcome());
        assertEquals(RuleReason.SALARY_UNPARSABLE, decision.reason());
        assertEquals("面议", decision.evidence());
    }

    @Test
    void unparsableSalaryIsReviewNotReject() {
        RuleDecision decision = engineWithSalary(20, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("薪资保密").build());

        assertEquals(RuleOutcome.REVIEW, decision.outcome());
        assertEquals(RuleReason.SALARY_UNPARSABLE, decision.reason());
        assertEquals("薪资保密", decision.evidence());

        RuleDecision nullSalary = engineWithSalary(20, 40, SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary(null).build());
        assertEquals(RuleOutcome.REVIEW, nullSalary.outcome());
        assertEquals(RuleReason.SALARY_UNPARSABLE, nullSalary.reason());
    }

    @Test
    void skipsSalaryRuleWhenExpectedSalaryNotConfigured() {
        BossConfig emptyList = baseConfig();
        emptyList.setExpectedSalary(new ArrayList<>());
        assertEquals(RuleOutcome.PASS, new JobRuleEngine(emptyList, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("面议").build()).outcome(), "期望薪资为空应跳过");

        BossConfig nullList = baseConfig();
        nullList.setExpectedSalary(null);
        assertEquals(RuleOutcome.PASS, new JobRuleEngine(nullList, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("8-10K").build()).outcome(), "期望薪资为 null 应跳过");

        BossConfig oneElement = baseConfig();
        oneElement.setExpectedSalary(new ArrayList<>(List.of(20)));
        // B2 起单边配置也生效：只有下限时，岗位上限低于下限会拒绝
        assertEquals(RuleReason.SALARY_NOT_EXPECTED,
                new JobRuleEngine(oneElement, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT)
                        .evaluate(baseSnapshot().salary("8-10K").build()).reason(), "只有下限时低薪应拒绝");
        assertEquals(RuleOutcome.PASS,
                new JobRuleEngine(oneElement, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT)
                        .evaluate(baseSnapshot().salary("50-60K").build()).outcome(), "只有下限时高薪不拦");

        BossConfig allZero = baseConfig();
        allZero.setExpectedSalary(new ArrayList<>(List.of(0, 0)));
        assertEquals(RuleOutcome.PASS, new JobRuleEngine(allZero, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT)
                .evaluate(baseSnapshot().salary("8-10K").build()).outcome(), "两端都未配置应跳过");
    }

    @Test
    void treatsZeroBoundAsNotConfigured() {
        // [0, 40]：只配置了上限
        BossConfig maxOnly = baseConfig();
        maxOnly.setExpectedSalary(new ArrayList<>(List.of(0, 40)));
        JobRuleEngine maxOnlyEngine = new JobRuleEngine(maxOnly, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT);
        assertEquals(RuleReason.SALARY_NOT_EXPECTED, maxOnlyEngine
                .evaluate(baseSnapshot().salary("50-60K").build()).reason(), "只配上限时高于上限应拒绝");
        assertEquals(RuleOutcome.PASS, maxOnlyEngine
                .evaluate(baseSnapshot().salary("8-10K").build()).outcome(), "上限为 40 时低薪不应被拦");

        // [20, 0]：只配置了下限
        BossConfig minOnly = baseConfig();
        minOnly.setExpectedSalary(new ArrayList<>(List.of(20, 0)));
        JobRuleEngine minOnlyEngine = new JobRuleEngine(minOnly, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT);
        assertEquals(RuleReason.SALARY_NOT_EXPECTED, minOnlyEngine
                .evaluate(baseSnapshot().salary("8-10K").build()).reason(), "只配下限时低于下限应拒绝");
        assertEquals(RuleOutcome.PASS, minOnlyEngine
                .evaluate(baseSnapshot().salary("50-60K").build()).outcome(), "未配置上限时不应拦高薪");
    }

    // ==================== 健壮性 ====================

    @Test
    void nullConfigStillEvaluatesWithoutThrowing() {
        JobRuleEngine engine = new JobRuleEngine(null, null, null, null, null);

        RuleDecision decision = assertDoesNotThrow(() -> engine.evaluate(baseSnapshot().build()));

        assertEquals(RuleOutcome.PASS, decision.outcome());
    }

    @Test
    void nullSnapshotReturnsReviewWithoutThrowing() {
        RuleDecision decision = assertDoesNotThrow(() -> engine(baseConfig()).evaluate(null));

        assertEquals(RuleOutcome.REVIEW, decision.outcome());
        assertEquals(RuleReason.MISSING_DETAIL_RESPONSE, decision.reason());
    }

    @Test
    void snapshotHashAndSalaryParsingAreAvailableWithoutSpring() {
        // 纯内存不依赖容器：构造、哈希、解析一条龙（防止哪天误加 Spring 依赖）
        JobSnapshot snapshot = baseSnapshot().build();
        String hash = snapshot.snapshotHash();
        assertEquals(64, hash.length());
        SalaryInfo salary = SalaryParser.parse(snapshot.salary());
        assertEquals(20.0, salary.minK(), DELTA);
    }
}
