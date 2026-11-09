package com.offerchaser.application.persistence;

/**
 * 简历图片发送确认状态。
 * <p>
 * 图片发送与文字发送相互独立：图片失败不影响文字状态，反之亦然。
 */
public enum ImageSendStatus {
    /** 尚未尝试发送图片 */
    NOT_ATTEMPTED,
    /** 有明确证据证明图片已出现在会话中 */
    CONFIRMED,
    /** 有明确证据证明图片发送失败 */
    FAILED,
    /** 结果未知（超时、崩溃恢复等），按失败保守处理 */
    UNKNOWN,
    /** 按配置或流程主动跳过图片发送 */
    SKIPPED
}
