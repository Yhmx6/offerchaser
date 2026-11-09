package com.offerchaser.application.ai;

/**
 * AI 评估结论。
 * <p>
 * 只有 {@link #MATCH} 且话术通过校验时才允许投递；{@link #REJECT} / {@link #REVIEW}
 * 表示不匹配或需要人工复核；{@link #ERROR} 表示 AI 输出/调用不可用（按失败策略跳过，
 * 绝不使用原文或兜底话术）。
 * </p>
 */
public enum AiOutcome {
    /** 匹配，且给出了可发送的话术 */
    MATCH,
    /** 不匹配，跳过 */
    REJECT,
    /** 需要人工复核，跳过自动投递 */
    REVIEW,
    /** AI 调用/输出异常，按失败处理 */
    ERROR
}
