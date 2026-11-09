package com.offerchaser.application.delivery;

/**
 * 单条规则的判定结果。
 *
 * @param outcome  判定结论
 * @param reason   原因码；通过时为 {@link RuleReason#PASS}
 * @param evidence 关键证据，尽量写原始文本里命中的词/片段（如黑名单命中词、薪资原文），便于回看
 * @param detail   给人看的中文说明；通过时可为 null
 */
public record RuleDecision(RuleOutcome outcome, RuleReason reason, String evidence, String detail) {

    /** 构造「通过」结果。 */
    public static RuleDecision pass() {
        return new RuleDecision(RuleOutcome.PASS, RuleReason.PASS, null, null);
    }

    /** 构造「拒绝」结果。 */
    public static RuleDecision reject(RuleReason reason, String evidence, String detail) {
        return new RuleDecision(RuleOutcome.REJECT, reason, evidence, detail);
    }

    /** 构造「复核」结果（证据不足，保守跳过）。 */
    public static RuleDecision review(RuleReason reason, String evidence, String detail) {
        return new RuleDecision(RuleOutcome.REVIEW, reason, evidence, detail);
    }
}
