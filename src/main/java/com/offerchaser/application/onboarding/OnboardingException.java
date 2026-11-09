package com.offerchaser.application.onboarding;

import org.springframework.http.HttpStatus;

/**
 * 简历分析/模板草稿的业务异常：带稳定的错误码与 HTTP 状态，供前端展示与兜底引导。
 * <p>
 * 错误码约定（前端已按这些码做提示）：{@code AI_CONFIG_MISSING}、{@code INVALID_IMAGE}、
 * {@code INVALID_TEXT}、{@code VISION_PARSE_FAILED}、{@code RESUME_ANALYZE_FAILED}、
 * {@code DRAFT_INVALID}。消息只描述问题，不回显简历文本/图片内容。
 * </p>
 */
public class OnboardingException extends RuntimeException {

    /** AI 未配置（BASE_URL/API_KEY/MODEL 缺失或非法） */
    public static final String AI_CONFIG_MISSING = "AI_CONFIG_MISSING";
    /** 图片为空/超过 10MB/不是真实图片 */
    public static final String INVALID_IMAGE = "INVALID_IMAGE";
    /** 文本为空/过长 */
    public static final String INVALID_TEXT = "INVALID_TEXT";
    /** 视觉解析失败（调用失败或返回内容不是合法画像 JSON） */
    public static final String VISION_PARSE_FAILED = "VISION_PARSE_FAILED";
    /** 简历分析失败（文本解析失败 / 方向与薪资建议解析失败 / AI 调用失败） */
    public static final String RESUME_ANALYZE_FAILED = "RESUME_ANALYZE_FAILED";
    /** 草稿请求参数非法 */
    public static final String DRAFT_INVALID = "DRAFT_INVALID";

    private final String code;
    private final HttpStatus status;

    public OnboardingException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public OnboardingException(String code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    /** AI 未配置：400，前端据此引导去配置中心。 */
    public static OnboardingException aiConfigMissing(Throwable cause) {
        String detail = cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                ? "AI 模型服务未配置" : cause.getMessage();
        return new OnboardingException(AI_CONFIG_MISSING, HttpStatus.BAD_REQUEST,
                "AI 未配置：请先在配置中心填写 BASE_URL / API_KEY / MODEL。" + detail, cause);
    }

    /** 图片非法：400。 */
    public static OnboardingException invalidImage(String message) {
        return new OnboardingException(INVALID_IMAGE, HttpStatus.BAD_REQUEST, message);
    }

    /** 文本非法：400。 */
    public static OnboardingException invalidText(String message) {
        return new OnboardingException(INVALID_TEXT, HttpStatus.BAD_REQUEST, message);
    }

    /** 视觉解析失败：422。 */
    public static OnboardingException visionParseFailed(String message) {
        return new OnboardingException(VISION_PARSE_FAILED, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /** 简历分析失败（内容解析）：422。 */
    public static OnboardingException analyzeInvalid(String message) {
        return new OnboardingException(RESUME_ANALYZE_FAILED, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /** 简历分析失败（上游调用）：502。 */
    public static OnboardingException analyzeUpstreamFailed(String message) {
        return new OnboardingException(RESUME_ANALYZE_FAILED, HttpStatus.BAD_GATEWAY, message);
    }

    /** 草稿参数非法：400。 */
    public static OnboardingException draftInvalid(String message) {
        return new OnboardingException(DRAFT_INVALID, HttpStatus.BAD_REQUEST, message);
    }
}
