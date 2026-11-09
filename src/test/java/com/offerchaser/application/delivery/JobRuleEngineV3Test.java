package com.offerchaser.application.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 B B2 规则引擎测试：学历等级比较/偏好不拒/JD 冲突复核、方向/城市过滤、
 * 黑名单 CONTAINS|EXACT|白名单、RuleSet 期望薪资、容错与可复现性。
 * <p>
 * 不启动 Spring：直接 new RuleSet + new JobRuleEngine(RuleSet)。
 */
class JobRuleEngineV3Test {

    // ==================== 测试脚手架 ====================

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

    private RuleSet rulesOf(String highestDegree, SalaryStrategy strategy, Integer min, Integer max,
                            List<String> directions, List<String> cities, BlacklistPolicy blacklist,
                            Boolean filterDeadHr, List<String> deadStatus) {
        return new RuleSet(7, "hash-v7", highestDegree, strategy, min, max, directions, cities,
                List.of(), blacklist, filterDeadHr, deadStatus);
    }

    private RuleSet simpleRules() {
        return rulesOf("本科", SalaryStrategy.INTERSECT, null, null, List.of(), List.of(),
                BlacklistPolicy.empty(), false, List.of());
    }

    private RuleDecision evaluate(RuleSet rules, JobSnapshot snapshot) {
        return new JobRuleEngine(rules).evaluate(snapshot);
    }

    // ==================== 构造器 / 规则快照 ====================

    @Test
    void exposesRulesSnapshotAndVersion() {
        RuleSet rules = rulesOf("硕士", SalaryStrategy.FLOOR, 20, 40, List.of(), List.of(),
                BlacklistPolicy.empty(), true, List.of("本月活跃"));
        JobRuleEngine engine = new JobRuleEngine(rules);

        assertSame(rules, engine.getRules(), "getRules 应返回构造时传入的规则快照");
        assertEquals("job-rules-v3", JobRuleEngine.RULE_VERSION);
        assertEquals(7, engine.getRules().configVersion());
        assertEquals("hash-v7", engine.getRules().sourceHash());
    }

    @Test
    void engineWithNullRulesUsesSafeDefaults() {
        JobRuleEngine engine = new JobRuleEngine((RuleSet) null);

        assertTrue(engine.getRules() != null);
        RuleDecision decision = assertDoesNotThrow(() -> engine.evaluate(baseSnapshot().build()));
        assertEquals(RuleOutcome.PASS, decision.outcome());
    }

    // ==================== 学历等级比较 ====================

    @Test
    void degreeLevelForUndergraduateUser() {
        RuleSet rules = simpleRules();
        for (String degree : List.of("不限", "学历不限", "高中", "中专", "大专", "本科")) {
            assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().degree(degree).build()).outcome(),
                    "本科用户对岗位学历 " + degree + " 应通过");
        }
        for (String degree : List.of("硕士", "研究生", "博士")) {
            RuleDecision decision = evaluate(rules, baseSnapshot().degree(degree).build());
            assertEquals(RuleOutcome.REJECT, decision.outcome(), "岗位学历：" + degree);
            assertEquals(RuleReason.DEGREE_TOO_HIGH, decision.reason(), "岗位学历：" + degree);
        }
    }

    @Test
    void degreeLevelComparisonUsesHighestDegree() {
        RuleSet juniorCollege = rulesOf("大专", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(juniorCollege, baseSnapshot().degree("大专").build()).outcome());
        assertEquals(RuleReason.DEGREE_TOO_HIGH,
                evaluate(juniorCollege, baseSnapshot().degree("本科").build()).reason());

        RuleSet master = rulesOf("硕士", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(master, baseSnapshot().degree("本科").build()).outcome());
        assertEquals(RuleReason.DEGREE_TOO_HIGH,
                evaluate(master, baseSnapshot().degree("博士").build()).reason());

        RuleSet doctor = rulesOf("博士", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(doctor, baseSnapshot().degree("博士").build()).outcome());

        RuleSet highSchool = rulesOf("高中", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(highSchool, baseSnapshot().degree("中专").build()).outcome());
        assertEquals(RuleReason.DEGREE_TOO_HIGH,
                evaluate(highSchool, baseSnapshot().degree("大专").build()).reason());
    }

    @Test
    void degreePreferenceDoesNotReject() {
        RuleSet rules = simpleRules();

        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().degree("硕士优先").build()).outcome(),
                "「硕士优先」是偏好，不拒绝");
        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().degree("博士优先").build()).outcome(),
                "「博士优先」是偏好，不拒绝");
        assertEquals(RuleReason.DEGREE_TOO_HIGH,
                evaluate(rules, baseSnapshot().degree("硕士").build()).reason(), "无「优先」时仍拒绝");

        RuleSet juniorCollege = rulesOf("大专", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(juniorCollege, baseSnapshot().degree("本科优先").build()).outcome());
    }

    @Test
    void degreeConflictWithJdIsReview() {
        RuleSet rules = simpleRules();

        RuleDecision masterJd = evaluate(rules, baseSnapshot()
                .degree("本科")
                .jobDescription("岗位要求硕士及以上学历，负责核心系统设计与开发")
                .build());
        assertEquals(RuleOutcome.REVIEW, masterJd.outcome());
        assertEquals(RuleReason.EDUCATION_CONFLICT, masterJd.reason());
        assertTrue(masterJd.evidence() != null && masterJd.evidence().contains("硕士"),
                "evidence 应带出 JD 命中的表述：" + masterJd.evidence());

        RuleDecision doctorJd = evaluate(rules, baseSnapshot()
                .degree("大专")
                .jobDescription("该岗位需要博士学历或同等研究背景，负责算法平台建设")
                .build());
        assertEquals(RuleReason.EDUCATION_CONFLICT, doctorJd.reason());
        assertTrue(doctorJd.evidence() != null && doctorJd.evidence().contains("博士"));

        RuleDecision graduateJd = evaluate(rules, baseSnapshot()
                .degree("本科")
                .jobDescription("任职要求：研究生学历，三年以上后端经验，熟悉分布式系统")
                .build());
        assertEquals(RuleReason.EDUCATION_CONFLICT, graduateJd.reason());
    }

    @Test
    void jdSoftEducationWordingDoesNotConflict() {
        RuleSet rules = simpleRules();

        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot()
                .degree("本科")
                .jobDescription("熟悉分布式系统，有大型项目经验，硕士优先，博士优先")
                .build()).outcome());
        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot()
                .degree("本科")
                .jobDescription("岗位要求硕士及以上优先，负责后端服务开发")
                .build()).outcome());
    }

    @Test
    void structuralDegreeRejectTakesPrecedenceOverJdConflict() {
        RuleDecision decision = evaluate(simpleRules(), baseSnapshot()
                .degree("硕士")
                .jobDescription("岗位要求硕士及以上学历，负责核心系统设计与开发")
                .build());

        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.DEGREE_TOO_HIGH, decision.reason());
    }

    // ==================== 方向 / 城市 ====================

    @Test
    void filtersByJobDirectionsWhenConfigured() {
        RuleSet accepted = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of("Java"), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(accepted, baseSnapshot().build()).outcome());

        RuleSet rejected = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of("大数据", "算法"), List.of(), BlacklistPolicy.empty(), false, List.of());
        RuleDecision decision = evaluate(rejected, baseSnapshot().build());
        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.DIRECTION_NOT_ACCEPTED, decision.reason());

        // 空方向列表 = 不限
        assertEquals(RuleOutcome.PASS, evaluate(simpleRules(), baseSnapshot().jobName("产品经理").build()).outcome());
    }

    @Test
    void directionMatchingIsMutualAndIgnoreCase() {
        RuleSet longDirection = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of("Java后端开发工程师"), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(longDirection, baseSnapshot().build()).outcome(),
                "配置写全称、岗位写简称也应命中（互相包含）");

        RuleSet lowerCase = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of("java"), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(lowerCase, baseSnapshot().build()).outcome(),
                "方向匹配忽略大小写");
    }

    @Test
    void filtersByCitiesWhenConfigured() {
        RuleSet accepted = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of("上海"), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(accepted, baseSnapshot().build()).outcome());

        RuleSet rejected = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of("北京", "深圳"), BlacklistPolicy.empty(), false, List.of());
        RuleDecision decision = evaluate(rejected, baseSnapshot().build());
        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.CITY_NOT_ACCEPTED, decision.reason());

        RuleSet fullName = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of("上海市"), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(fullName, baseSnapshot().build()).outcome(),
                "城市配置全称、快照简称也应命中（互相包含）");
    }

    @Test
    void missingCityIsRejectedWhenCitiesConfigured() {
        RuleSet rules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of("上海"), BlacklistPolicy.empty(), false, List.of());

        RuleDecision decision = evaluate(rules, baseSnapshot().city(null).build());
        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.CITY_NOT_ACCEPTED, decision.reason());
    }

    // ==================== 不可接受城市 ====================

    private RuleSet rulesWithCityFilters(List<String> acceptedCities, List<String> blacklistCities) {
        return new RuleSet(7, "hash-v7", "本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), acceptedCities, blacklistCities, List.of(),
                BlacklistPolicy.empty(), false, List.of());
    }

    @Test
    void rejectsBlacklistedCityWithCityBlacklistedReason() {
        RuleSet rules = rulesWithCityFilters(List.of(), List.of("北京"));

        RuleDecision exact = evaluate(rules, baseSnapshot().city("北京").build());
        assertEquals(RuleOutcome.REJECT, exact.outcome());
        assertEquals(RuleReason.CITY_BLACKLISTED, exact.reason());
        assertTrue(exact.detail().contains("不可接受城市"), "拒绝原因应说明不可接受城市");

        RuleDecision fullName = evaluate(rules, baseSnapshot().city("北京市").build());
        assertEquals(RuleOutcome.REJECT, fullName.outcome(), "城市全称与配置简称互相包含也应命中");
        assertEquals(RuleReason.CITY_BLACKLISTED, fullName.reason());

        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().city("上海").build()).outcome());
    }

    @Test
    void emptyBlacklistCitiesDoesNotFilter() {
        RuleSet rules = rulesWithCityFilters(List.of(), List.of());
        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().city("北京").build()).outcome());
    }

    @Test
    void acceptableCitiesRunBeforeBlacklistCities() {
        RuleSet rules = rulesWithCityFilters(List.of("上海"), List.of("北京"));

        // 不在可接受城市内时先命中城市白名单规则，而不是黑名单
        RuleDecision rejectedByAcceptList = evaluate(rules, baseSnapshot().city("北京").build());
        assertEquals(RuleReason.CITY_NOT_ACCEPTED, rejectedByAcceptList.reason());

        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().city("上海").build()).outcome(),
                "同时存在可接受/不可接受城市时，白名单通过后仍需黑名单不命中");
    }

    @Test
    void missingSnapshotCityDoesNotTriggerBlacklistCities() {
        RuleSet rules = rulesWithCityFilters(List.of(), List.of("北京"));
        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().city(null).build()).outcome(),
                "岗位城市缺失时无法命中不可接受城市，交给其它规则判断");
    }

    // ==================== 黑名单 ====================

    @Test
    void containsModeMatchesSubstring() {
        BlacklistPolicy policy = new BlacklistPolicy(BlacklistMatchMode.CONTAINS,
                List.of("外包"), null, null, null);
        RuleSet rules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), policy, false, List.of());

        RuleDecision decision = evaluate(rules, baseSnapshot().jobName("Java开发（外包）").build());
        assertEquals(RuleReason.JOB_BLACKLIST, decision.reason());
        assertEquals("外包", decision.evidence());

        BlacklistPolicy companyPolicy = new BlacklistPolicy(BlacklistMatchMode.CONTAINS,
                null, List.of("某某科技"), null, null);
        RuleSet companyRules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), companyPolicy, false, List.of());
        assertEquals(RuleReason.COMPANY_BLACKLIST,
                evaluate(companyRules, baseSnapshot().build()).reason());
    }

    @Test
    void exactModeRequiresTrimmedEquality() {
        BlacklistPolicy jobPolicy = new BlacklistPolicy(BlacklistMatchMode.EXACT,
                List.of("Java开发"), null, null, null);
        RuleSet jobRules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), jobPolicy, false, List.of());
        assertEquals(RuleReason.JOB_BLACKLIST,
                evaluate(jobRules, baseSnapshot().jobName("Java开发").build()).reason());
        assertEquals(RuleOutcome.PASS,
                evaluate(jobRules, baseSnapshot().jobName("高级Java开发").build()).outcome(),
                "EXACT 模式下包含关系不算命中");

        BlacklistPolicy companyPolicy = new BlacklistPolicy(BlacklistMatchMode.EXACT,
                null, List.of("杭州某某科技有限公司"), null, null);
        RuleSet companyRules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), companyPolicy, false, List.of());
        assertEquals(RuleReason.COMPANY_BLACKLIST, evaluate(companyRules, baseSnapshot().build()).reason());

        BlacklistPolicy shortCompany = new BlacklistPolicy(BlacklistMatchMode.EXACT,
                null, List.of("某某科技"), null, null);
        RuleSet shortRules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), shortCompany, false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(shortRules, baseSnapshot().build()).outcome(),
                "EXACT 模式下公司简称不命中全名");

        BlacklistPolicy recruiterPolicy = new BlacklistPolicy(BlacklistMatchMode.EXACT,
                null, null, List.of("技术招聘"), null);
        RuleSet recruiterRules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), recruiterPolicy, false, List.of());
        assertEquals(RuleReason.RECRUITER_BLACKLIST, evaluate(recruiterRules, baseSnapshot().build()).reason());
        assertEquals(RuleOutcome.PASS,
                evaluate(recruiterRules, baseSnapshot().hrPosition("高级技术招聘").build()).outcome());
    }

    @Test
    void whitelistOverridesBlacklistStep() {
        BlacklistPolicy policy = new BlacklistPolicy(BlacklistMatchMode.CONTAINS,
                List.of("Java"), null, null, List.of("Java后端"));
        RuleSet rules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), policy, false, List.of());

        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().build()).outcome(),
                "白名单命中（岗位名包含白名单词）时应跳过黑名单判定");

        BlacklistPolicy notHit = new BlacklistPolicy(BlacklistMatchMode.CONTAINS,
                List.of("Java"), null, null, List.of("大数据"));
        RuleSet notHitRules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), notHit, false, List.of());
        assertEquals(RuleReason.JOB_BLACKLIST, evaluate(notHitRules, baseSnapshot().build()).reason());
    }

    @Test
    void whitelistMatchesCompanyNameToo() {
        BlacklistPolicy policy = new BlacklistPolicy(BlacklistMatchMode.CONTAINS,
                null, List.of("某某科技"), null, List.of("某某科技"));
        RuleSet rules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), policy, false, List.of());

        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().build()).outcome(),
                "公司名命中白名单时本步通过");
    }

    @Test
    void whitelistDoesNotBypassEarlierRules() {
        BlacklistPolicy policy = new BlacklistPolicy(BlacklistMatchMode.CONTAINS,
                null, null, null, List.of("Java"));
        RuleSet rules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), policy, false, List.of());

        RuleDecision decision = evaluate(rules, baseSnapshot().degree("硕士").build());
        assertEquals(RuleReason.DEGREE_TOO_HIGH, decision.reason(),
                "学历规则在黑名单之前，白名单不能绕过学历判定");
    }

    @Test
    void nullMatchModeFallsBackToContains() {
        BlacklistPolicy policy = new BlacklistPolicy(null, List.of("外包"), null, null, null);
        RuleSet rules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), policy, false, List.of());

        assertEquals(RuleReason.JOB_BLACKLIST,
                evaluate(rules, baseSnapshot().jobName("Java开发（外包）").build()).reason());
    }

    // ==================== 期望薪资（RuleSet 版） ====================

    @Test
    void intersectSalaryRuleUsesRuleSetBounds() {
        RuleSet rules = rulesOf("本科", SalaryStrategy.INTERSECT, 20, 40,
                List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());

        RuleDecision tooLow = evaluate(rules, baseSnapshot().salary("8-10K").build());
        assertEquals(RuleReason.SALARY_NOT_EXPECTED, tooLow.reason());
        assertEquals("8-10K", tooLow.evidence());

        assertEquals(RuleReason.SALARY_NOT_EXPECTED,
                evaluate(rules, baseSnapshot().salary("50-60K").build()).reason());
        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().salary("20-40K").build()).outcome());
        assertEquals(RuleReason.SALARY_NOT_EXPECTED,
                evaluate(rules, baseSnapshot().salary("12.5K").build()).reason(),
                "12.5K 必须按 12.5K 判断，不能变成 125K");
        assertEquals(RuleOutcome.REVIEW,
                evaluate(rules, baseSnapshot().salary("面议").build()).outcome());
        assertEquals(RuleReason.SALARY_UNPARSABLE,
                evaluate(rules, baseSnapshot().salary("薪资保密").build()).reason());
        assertEquals(RuleOutcome.PASS,
                evaluate(rules, baseSnapshot().salary("20-40万/年").build()).outcome(),
                "20-40万/年折算月薪约 16.7-33.3K，与期望 20-40K 有交集");
    }

    @Test
    void floorSalaryRuleUsesRuleSetBounds() {
        RuleSet rules = rulesOf("本科", SalaryStrategy.FLOOR, 20, 40,
                List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());

        assertEquals(RuleReason.SALARY_NOT_EXPECTED,
                evaluate(rules, baseSnapshot().salary("8-10K").build()).reason());
        assertEquals(RuleOutcome.PASS, evaluate(rules, baseSnapshot().salary("45-60K").build()).outcome(),
                "FLOOR 只看下限，开得高不拦");
    }

    @Test
    void partialSalaryBoundsApplyIndividually() {
        RuleSet minOnly = rulesOf("本科", SalaryStrategy.INTERSECT, 20, null,
                List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleReason.SALARY_NOT_EXPECTED,
                evaluate(minOnly, baseSnapshot().salary("8-10K").build()).reason());
        assertEquals(RuleOutcome.PASS, evaluate(minOnly, baseSnapshot().salary("50-60K").build()).outcome());

        RuleSet maxOnly = rulesOf("本科", SalaryStrategy.INTERSECT, null, 40,
                List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleReason.SALARY_NOT_EXPECTED,
                evaluate(maxOnly, baseSnapshot().salary("50-60K").build()).reason());
        assertEquals(RuleOutcome.PASS, evaluate(maxOnly, baseSnapshot().salary("8-10K").build()).outcome());

        RuleSet noBounds = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(noBounds, baseSnapshot().salary("面议").build()).outcome(),
                "未配置期望薪资时跳过薪资规则");
    }

    // ==================== HR 活跃度（RuleSet 版） ====================

    @Test
    void hrActivityRuleUsesRuleSetFields() {
        RuleSet enabled = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), BlacklistPolicy.empty(), true, List.of("本月活跃", "今日活跃"));
        RuleDecision decision = evaluate(enabled, baseSnapshot().hrActiveStatus("本月活跃").build());
        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.HR_INACTIVE, decision.reason());
        assertEquals("本月活跃", decision.evidence());

        RuleSet filterNull = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), BlacklistPolicy.empty(), null, List.of("今日活跃"));
        assertEquals(RuleOutcome.PASS, evaluate(filterNull, baseSnapshot().build()).outcome(),
                "filterDeadHr 为 null 视为不过滤");

        RuleSet deadStatusEmpty = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of(), BlacklistPolicy.empty(), true, List.of());
        assertEquals(RuleOutcome.PASS, evaluate(deadStatusEmpty, baseSnapshot().build()).outcome());

        assertEquals(RuleOutcome.PASS,
                evaluate(enabled, baseSnapshot().hrActiveStatus(null).build()).outcome(),
                "取不到活跃度文案时不视为死号");
    }

    // ==================== 规则顺序 / 可复现 / 容错 ====================

    @Test
    void directionAndCityRunBeforeDegree() {
        RuleSet directionRules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of("大数据"), List.of(), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleReason.DIRECTION_NOT_ACCEPTED,
                evaluate(directionRules, baseSnapshot().degree("硕士").build()).reason());

        RuleSet cityRules = rulesOf("本科", SalaryStrategy.INTERSECT, null, null,
                List.of(), List.of("北京"), BlacklistPolicy.empty(), false, List.of());
        assertEquals(RuleReason.CITY_NOT_ACCEPTED,
                evaluate(cityRules, baseSnapshot().degree("硕士").build()).reason());
    }

    @Test
    void hrActivityRunsBeforeSalaryRule() {
        RuleSet rules = rulesOf("本科", SalaryStrategy.INTERSECT, 20, 40,
                List.of(), List.of(), BlacklistPolicy.empty(), true, List.of("今日活跃"));
        assertEquals(RuleReason.HR_INACTIVE,
                evaluate(rules, baseSnapshot().salary("8-10K").build()).reason(),
                "HR 活跃度规则在期望薪资之前");
    }

    @Test
    void sameInputsProduceSameDecision() {
        JobRuleEngine engine = new JobRuleEngine(simpleRules());
        JobSnapshot snapshot = baseSnapshot().build();

        RuleDecision first = engine.evaluate(snapshot);
        RuleDecision second = engine.evaluate(snapshot);
        assertEquals(first, second, "同一规则快照 + 同一岗位快照必须得到完全相同的判定");
    }

    @Test
    void nullSnapshotIsReviewNotException() {
        RuleDecision decision = assertDoesNotThrow(() -> new JobRuleEngine(simpleRules()).evaluate(null));

        assertEquals(RuleOutcome.REVIEW, decision.outcome());
        assertEquals(RuleReason.MISSING_DETAIL_RESPONSE, decision.reason());
    }
}
