package com.offerchaser.application.delivery;

/**
 * 硬规则判定结果。
 * <ul>
 *   <li>{@link #PASS}：全部规则通过，可以继续后续流程（AI / 预算 / 打开会话）；</li>
 *   <li>{@link #REJECT}：明确不符合要求，记「已过滤」并跳过；</li>
 *   <li>{@link #REVIEW}：证据不足或无法判定，保守跳过并留痕，不允许当成通过。</li>
 * </ul>
 */
public enum RuleOutcome {
    PASS,
    REJECT,
    REVIEW
}
