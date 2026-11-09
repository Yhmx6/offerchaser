package com.offerchaser.application.ai;

/**
 * AI 调用结果（只描述传输层，不做业务判定）。
 * <p>
 * 是否可以把内容用于投递，由 {@link AiOutputParser} 与 {@link AiMessageValidator} 决定；
 * 本类只负责把 HTTP 层的正文、模型名、token 用量与耗时原样带出来。
 * </p>
 *
 * @param content          AI 返回的原始文本（尚未做 JSON 解析）
 * @param model            实际使用的模型名
 * @param promptTokens     输入 token 数（响应里没有 usage 时为 -1）
 * @param completionTokens 输出 token 数（响应里没有 usage 时为 -1）
 * @param durationMs       本次调用耗时（毫秒）
 * @param usagePresent     响应中是否携带了 usage 字段
 */
public record AiCallResult(String content, String model, int promptTokens, int completionTokens,
                           long durationMs, boolean usagePresent) {
}
