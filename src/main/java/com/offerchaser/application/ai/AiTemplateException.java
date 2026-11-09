package com.offerchaser.application.ai;

/**
 * AI 提示词模板异常。
 * <p>
 * 出现未知 {{xxx}} 占位符、占位符未闭合、%s 数量超过上限等配置错误时抛出。
 * 模板错误属于配置错误，不允许继续发送请求，更不允许把异常内容当招呼语。
 * </p>
 */
public class AiTemplateException extends RuntimeException {

    public AiTemplateException(String message) {
        super(message);
    }

    public AiTemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
