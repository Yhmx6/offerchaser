package com.offerchaser.verification;

import com.offerchaser.application.delivery.BlacklistMatchMode;
import com.offerchaser.application.delivery.BlacklistPolicy;
import com.offerchaser.application.delivery.JobRuleEngine;
import com.offerchaser.application.delivery.JobSnapshot;
import com.offerchaser.application.delivery.RuleDecision;
import com.offerchaser.application.delivery.RuleOutcome;
import com.offerchaser.application.delivery.RuleReason;
import com.offerchaser.application.delivery.RuleSet;
import com.offerchaser.application.delivery.SalaryStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2 规则判定端到端样例（学历/方向/城市/黑名单/薪资）。
 * <p>
 * 直接构造 {@link RuleSet} 调生产 {@link JobRuleEngine}，锁定 B2 新增规则语义：
 * 学历等级比较与「优先不拒」、JD 硬性学历冲突 REVIEW、方向/城市过滤、
 * 黑名单 CONTAINS/EXACT/白名单、薪资 12.5K/面议/上下限。实现细节的穷举由 T3 单元测试覆盖，
 * 这里只保留验收契约的关键样例，并验证结果可复现。
 */
class B2RuleDecisionContractTest {

    private static final String JD = "负责后端服务开发与维护，熟悉 Java 与 Spring 生态。";

    // ==================== 学历 ====================

    @Test
    void degreeHigherThanUserIsRejected() {
        JobRuleEngine engine = engine(rules("本科", List.of(), List.of(), emptyPolicy(), null, null,
                SalaryStrategy.INTERSECT));
        RuleDecision decision = engine.evaluate(snapshot("Java 开发", "上海", "硕士", JD, "20-30K"));
        assertEquals(RuleOutcome.REJECT, decision.outcome());
        assertEquals(RuleReason.DEGREE_TOO_HIGH, decision.reason());
    }

    @Test
    void preferredHigherDegreeIsNotRejected() {
        JobRuleEngine engine = engine(rules("本科", List.of(), List.of(), emptyPolicy(), null, null,
                SalaryStrategy.INTERSECT));
        RuleDecision decision = engine.evaluate(snapshot("Java 开发", "上海", "硕士优先", JD, "20-30K"));
        assertEquals(RuleOutcome.PASS, decision.outcome(), "「硕士优先」是偏好，不应拒绝");
        assertEquals(RuleReason.PASS, decision.reason());
    }

    @Test
    void jdHardEducationDemandTriggersReview() {
        JobRuleEngine engine = engine(rules("本科", List.of(), List.of(), emptyPolicy(), null, null,
                SalaryStrategy.INTERSECT));
        String jd = "岗位要求：硕士及以上学历，3 年以上后端开发经验。";
        RuleDecision decision = engine.evaluate(snapshot("Java 开发", "上海", "不限", jd, "20-30K"));
        assertEquals(RuleOutcome.REVIEW, decision.outcome(), "结构化学历不限但 JD 硬性硕士 → 转复核");
        assertEquals(RuleReason.EDUCATION_CONFLICT, decision.reason());
    }

    // ==================== 方向 / 城市 ====================

    @Test
    void directionMismatchIsRejected() {
        JobRuleEngine engine = engine(rules("本科", List.of("Java"), List.of(), emptyPolicy(), null, null,
                SalaryStrategy.INTERSECT));
        assertEquals(RuleReason.DIRECTION_NOT_ACCEPTED,
                engine.evaluate(snapshot("销售专员", "上海", "本科", JD, "20-30K")).reason());
        assertEquals(RuleReason.PASS,
                engine.evaluate(snapshot("Java 开发", "上海", "本科", JD, "20-30K")).reason());
    }

    @Test
    void cityMismatchIsRejected() {
        JobRuleEngine engine = engine(rules("本科", List.of(), List.of("上海"), emptyPolicy(), null, null,
                SalaryStrategy.INTERSECT));
        assertEquals(RuleReason.CITY_NOT_ACCEPTED,
                engine.evaluate(snapshot("Java 开发", "北京", "本科", JD, "20-30K")).reason());
        assertEquals(RuleReason.PASS,
                engine.evaluate(snapshot("Java 开发", "上海", "本科", JD, "20-30K")).reason());
    }

    // ==================== 黑名单 ====================

    @Test
    void blacklistContainsAndExactModes() {
        BlacklistPolicy contains = new BlacklistPolicy(BlacklistMatchMode.CONTAINS,
                List.of("外包"), List.of("某某外包"), List.of("销售"), List.of());
        JobRuleEngine containsEngine = engine(rules("本科", List.of(), List.of(), contains, null, null,
                SalaryStrategy.INTERSECT));
        RuleDecision jobHit = containsEngine.evaluate(
                snapshot("Java 外包开发", "上海", "本科", JD, "20-30K", "示例科技", "招聘专员"));
        assertEquals(RuleReason.JOB_BLACKLIST, jobHit.reason());
        assertEquals("外包", jobHit.evidence());
        assertEquals(RuleReason.COMPANY_BLACKLIST, containsEngine.evaluate(
                snapshot("Java 开发", "上海", "本科", JD, "20-30K", "某某外包公司", "招聘专员")).reason());
        assertEquals(RuleReason.RECRUITER_BLACKLIST, containsEngine.evaluate(
                snapshot("Java 开发", "上海", "本科", JD, "20-30K", "示例科技", "销售经理")).reason());

        BlacklistPolicy exact = new BlacklistPolicy(BlacklistMatchMode.EXACT,
                List.of("Java 开发"), List.of(), List.of(), List.of());
        JobRuleEngine exactEngine = engine(rules("本科", List.of(), List.of(), exact, null, null,
                SalaryStrategy.INTERSECT));
        assertEquals(RuleReason.JOB_BLACKLIST, exactEngine.evaluate(
                snapshot("Java 开发", "上海", "本科", JD, "20-30K")).reason(), "EXACT 必须完全相等");
        assertEquals(RuleReason.PASS, exactEngine.evaluate(
                snapshot("Java 开发工程师", "上海", "本科", JD, "20-30K")).reason(), "EXACT 不应子串命中");
    }

    @Test
    void whitelistOverridesBlacklist() {
        BlacklistPolicy policy = new BlacklistPolicy(BlacklistMatchMode.CONTAINS,
                List.of("外包"), List.of("某某公司"), List.of(), List.of("某某公司"));
        JobRuleEngine engine = engine(rules("本科", List.of(), List.of(), policy, null, null,
                SalaryStrategy.INTERSECT));
        RuleDecision decision = engine.evaluate(
                snapshot("Java 开发", "上海", "本科", JD, "20-30K", "某某公司", "招聘专员"));
        assertEquals(RuleOutcome.PASS, decision.outcome(), "白名单命中应覆盖公司黑名单");
    }

    // ==================== 薪资 ====================

    @Test
    void salaryIntersectAndFloorStrategies() {
        JobRuleEngine intersect = engine(rules("本科", List.of(), List.of(), emptyPolicy(), 20, 40,
                SalaryStrategy.INTERSECT));
        assertEquals(RuleReason.PASS,
                intersect.evaluate(snapshot("Java 开发", "上海", "本科", JD, "20-30K")).reason());
        assertEquals(RuleReason.SALARY_NOT_EXPECTED,
                intersect.evaluate(snapshot("Java 开发", "上海", "本科", JD, "10-15K")).reason());
        assertEquals(RuleReason.SALARY_NOT_EXPECTED, intersect.evaluate(
                snapshot("Java 开发", "上海", "本科", JD, "12.5K")).reason(),
                "12.5K 应解析为 12.5，与期望区间无交集 → REJECT");
        assertEquals(RuleReason.SALARY_UNPARSABLE, intersect.evaluate(
                snapshot("Java 开发", "上海", "本科", JD, "面议")).reason(), "面议应 REVIEW 而非 REJECT");

        JobRuleEngine floor = engine(rules("本科", List.of(), List.of(), emptyPolicy(), 20, 40,
                SalaryStrategy.FLOOR));
        assertEquals(RuleReason.PASS,
                floor.evaluate(snapshot("Java 开发", "上海", "本科", JD, "15-25K")).reason(),
                "FLOOR：岗位上限 25 >= 期望下限 20 → PASS");
        assertEquals(RuleReason.SALARY_NOT_EXPECTED,
                floor.evaluate(snapshot("Java 开发", "上海", "本科", JD, "10-18K")).reason());
    }

    @Test
    void sameInputProducesReproducibleDecision() {
        JobRuleEngine engine = engine(rules("本科", List.of("Java"), List.of("上海"), emptyPolicy(), 20, 40,
                SalaryStrategy.INTERSECT));
        JobSnapshot snapshot = snapshot("Java 开发", "上海", "本科", JD, "20-30K");

        RuleDecision first = engine.evaluate(snapshot);
        RuleDecision second = engine.evaluate(snapshot);

        assertEquals(first.outcome(), second.outcome());
        assertEquals(first.reason(), second.reason());
        assertEquals(first.evidence(), second.evidence());
        assertEquals(7, engine.getRules().configVersion(), "引擎必须能暴露本轮固定规则版本");
    }

    @Test
    void engineNeverThrowsOnNullAndBrokenSnapshot() {
        JobRuleEngine engine = engine(rules("本科", List.of(), List.of(), emptyPolicy(), null, null,
                SalaryStrategy.INTERSECT));
        assertEquals(RuleOutcome.REVIEW, engine.evaluate(null).outcome(), "null 快照应转复核而不是抛异常");
        RuleDecision missingIdentity = engine.evaluate(
                JobSnapshot.builder().jobName("Java 开发").jobDescription(JD).build());
        assertEquals(RuleOutcome.REJECT, missingIdentity.outcome());
        assertEquals(RuleReason.MISSING_IDENTITY, missingIdentity.reason());
    }

    // ==================== 工具 ====================

    private static JobRuleEngine engine(RuleSet rules) {
        return new JobRuleEngine(rules);
    }

    private static RuleSet rules(String degree, List<String> directions, List<String> cities,
                                 BlacklistPolicy blacklist, Integer min, Integer max,
                                 SalaryStrategy strategy) {
        return new RuleSet(7, "test-hash", degree, strategy, min, max, directions, cities,
                List.of(), blacklist, false, List.of());
    }

    private static BlacklistPolicy emptyPolicy() {
        return BlacklistPolicy.empty();
    }

    private static JobSnapshot snapshot(String jobName, String city, String degree, String jd,
                                        String salary) {
        return snapshot(jobName, city, degree, jd, salary, "示例科技", "招聘专员");
    }

    private static JobSnapshot snapshot(String jobName, String city, String degree, String jd,
                                        String salary, String company, String hrPosition) {
        return JobSnapshot.builder()
                .encryptId("ENC-TEST")
                .encryptUserId("USER-TEST")
                .jobName(jobName)
                .city(city)
                .degree(degree)
                .jobDescription(jd)
                .salary(salary)
                .companyName(company)
                .hrName("张女士")
                .hrPosition(hrPosition)
                .hrActiveStatus("刚刚活跃")
                .build();
    }

    @SuppressWarnings("unused")
    private static boolean noop() {
        return true;
    }
}
