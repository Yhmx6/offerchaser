package com.offerchaser.application.ai;

/**
 * AI 配置异常。
 * <p>
 * 配置缺失（BASE_URL / API_KEY / MODEL 为空）或 URL 非法时抛出，
 * 抛出时机早于任何真实网络请求；调用方（{@link AiMatchService}）会把它转成
 * {@code AI_CONFIG_INVALID} 的 {@link AiMatchResult}，绝不允许带病发起投递。
 * </p>
 */
public class AiConfigException extends RuntimeException {

    public AiConfigException(String message) {
        super(message);
    }

    public AiConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
