package com.offerchaser.application.ai;

import java.util.List;

/**
 * AI 匹配评估结果（解析后的唯一对外形态）。
 * <p>
 * 设计原则：失败即失败。{@link AiOutcome#ERROR} 与 {@link AiOutcome#REJECT}/{@link AiOutcome#REVIEW}
 * 的 {@code message} 一律为 null，绝不把模型原始文本、分析文本或兜底话术塞进 message；
 * 调用方只有看到 {@link #sendable()} 为 true 才允许投递。
 * </p>
 *
 * @param outcome         评估结论
 * @param message         通过校验的招呼语（仅 MATCH 且校验通过时非空）
 * @param analysis        模型给出的匹配分析（仅用于记录/日志）
 * @param requirements    模型拆解出的岗位要求（可为空列表）
 * @param errorCode       错误码（仅 ERROR 时有值）
 * @param errorDetail     错误详情（仅 ERROR 时有值）
 * @param model           实际使用的模型名（解析器层面为空，由 AiMatchService 回填）
 * @param promptTokens    输入 token 数（未知为 0 或 -1）
 * @param completionTokens 输出 token 数（未知为 0 或 -1）
 * @param durationMs      调用耗时（毫秒）
 */
public record AiMatchResult(AiOutcome outcome, String message, String analysis,
                            List<AiRequirement> requirements,
                            String errorCode, String errorDetail,
                            String model, int promptTokens, int completionTokens, long durationMs) {

    /**
     * 是否允许投递：必须是 MATCH，且话术非空。
     */
    public boolean sendable() {
        return outcome == AiOutcome.MATCH && message != null && !message.isBlank();
    }

    /**
     * 构造失败结果：不携带任何话术，避免把原始文本/兜底文案当招呼语。
     */
    public static AiMatchResult error(String errorCode, String errorDetail) {
        return new AiMatchResult(AiOutcome.ERROR, null, null, List.of(),
                errorCode, errorDetail, null, 0, 0, 0);
    }

    /**
     * 构造明确的拒绝结果（旧格式 match=false 等场景）。
     */
    public static AiMatchResult reject(String analysis) {
        return new AiMatchResult(AiOutcome.REJECT, null, analysis, List.of(),
                null, null, null, 0, 0, 0);
    }

    /**
     * 附加调用元信息（包内使用，不扩大对外接口）。
     */
    AiMatchResult withCallInfo(AiCallResult call) {
        if (call == null) {
            return this;
        }
        return new AiMatchResult(outcome, message, analysis, requirements, errorCode, errorDetail,
                call.model(), call.promptTokens(), call.completionTokens(), call.durationMs());
    }

    /**
     * 替换话术（仅用于返回前做空白归一化，不改变结论）。
     */
    AiMatchResult withMessage(String newMessage) {
        return new AiMatchResult(outcome, newMessage, analysis, requirements, errorCode, errorDetail,
                model, promptTokens, completionTokens, durationMs);
    }
}
