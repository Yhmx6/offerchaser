package com.offerchaser.application.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 冻结接口契约测试：枚举常量顺序/取值、规则版本号、RuleDecision 工厂方法。
 * <p>
 * 这些值会被写进数据库（rule_decision / rule_reason_code / rule_version），改动会导致历史数据口径错乱，
 * 因此用测试锁死。
 */
class RuleContractTest {

    @Test
    void ruleOutcomeValuesAreFrozen() {
        assertArrayEquals(new RuleOutcome[]{RuleOutcome.PASS, RuleOutcome.REJECT, RuleOutcome.REVIEW},
                RuleOutcome.values());
    }

    @Test
    void ruleReasonValuesAreFrozen() {
        assertArrayEquals(new RuleReason[]{
                        RuleReason.PASS,
                        RuleReason.MISSING_IDENTITY,
                        RuleReason.MISSING_JOB_CONTENT,
                        RuleReason.IDENTITY_MISMATCH,
                        RuleReason.MISSING_DETAIL_RESPONSE,
                        RuleReason.DEGREE_TOO_HIGH,
                        RuleReason.JOB_BLACKLIST,
                        RuleReason.COMPANY_BLACKLIST,
                        RuleReason.RECRUITER_BLACKLIST,
                        RuleReason.HR_INACTIVE,
                        RuleReason.SALARY_NOT_EXPECTED,
                        RuleReason.SALARY_UNPARSABLE,
                        RuleReason.DIRECTION_NOT_ACCEPTED,
                        RuleReason.CITY_NOT_ACCEPTED,
                        RuleReason.CITY_BLACKLISTED,
                        RuleReason.EDUCATION_CONFLICT,
                        RuleReason.CONTACT_ALREADY_GREETED,
                        RuleReason.CONTACT_BLOCKED,
                        RuleReason.FOLLOWUP_NOT_APPROVED
                },
                RuleReason.values());
    }

    @Test
    void salaryStrategyValuesAreFrozen() {
        assertArrayEquals(new SalaryStrategy[]{SalaryStrategy.INTERSECT, SalaryStrategy.FLOOR},
                SalaryStrategy.values());
    }

    @Test
    void blacklistMatchModeValuesAreFrozen() {
        assertArrayEquals(new BlacklistMatchMode[]{BlacklistMatchMode.CONTAINS, BlacklistMatchMode.EXACT},
                BlacklistMatchMode.values());
    }

    @Test
    void ruleVersionIsFrozen() {
        assertEquals("job-rules-v3", JobRuleEngine.RULE_VERSION);
    }

    @Test
    void passDecisionHasNoEvidence() {
        RuleDecision decision = RuleDecision.pass();

        assertEquals(RuleOutcome.PASS, decision.outcome());
        assertEquals(RuleReason.PASS, decision.reason());
        assertNull(decision.evidence());
        assertNull(decision.detail());
    }

    @Test
    void rejectAndReviewFactoriesCarryReasonEvidenceAndDetail() {
        RuleDecision reject = RuleDecision.reject(RuleReason.JOB_BLACKLIST, "外包", "职位黑名单命中");
        assertEquals(RuleOutcome.REJECT, reject.outcome());
        assertEquals(RuleReason.JOB_BLACKLIST, reject.reason());
        assertEquals("外包", reject.evidence());
        assertEquals("职位黑名单命中", reject.detail());

        RuleDecision review = RuleDecision.review(RuleReason.SALARY_UNPARSABLE, "面议", "转人工复核");
        assertEquals(RuleOutcome.REVIEW, review.outcome());
        assertEquals(RuleReason.SALARY_UNPARSABLE, review.reason());
        assertEquals("面议", review.evidence());
        assertEquals("转人工复核", review.detail());
    }
}
