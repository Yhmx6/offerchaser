package com.offerchaser.application.persistence;

/**
 * 文字发送确认状态。
 * <p>
 * 失败策略原则：不确定 = 不记成功，因此 UNKNOWN 与 FAILED 都不会被当成已投递。
 */
public enum TextSendStatus {
    /** 已点击发送，但尚未确认（初始状态） */
    PENDING,
    /** 有明确证据证明文字已出现在会话中 */
    CONFIRMED,
    /** 有明确证据证明发送失败 */
    FAILED,
    /** 结果未知（超时、崩溃恢复等），按失败保守处理 */
    UNKNOWN,
    /** 尚未尝试发送 */
    NOT_ATTEMPTED
}
