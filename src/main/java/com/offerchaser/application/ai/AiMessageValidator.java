package com.offerchaser.application.ai;

/**
 * AI 招呼语校验器：在发送前做最后一道“像不像人话”的兜底校验。
 * <p>
 * 只做确定性的格式检查，不调用任何外部服务，便于单元测试：
 * <ul>
 *   <li>空 / 全空白 → 不合法；</li>
 *   <li>超长（按归一化后的长度）→ 不合法；</li>
 *   <li>含 ``` 代码块围栏 → 不合法；</li>
 *   <li>含 decision / match / analysis 等 JSON 字段名 → 不合法（JSON 片段泄漏）；</li>
 *   <li>含换行以外的控制字符（如 \t、\u0007）→ 不合法。</li>
 * </ul>
 * </p>
 */
public final class AiMessageValidator {

    /** 未指定上限时的默认话术长度（与接口约定第 5 节的 max-message-length 一致） */
    private static final int DEFAULT_MAX_LENGTH = 120;

    /** 会把 JSON 片段带进话术的字段名（小写比较） */
    private static final String[] FORBIDDEN_FRAGMENTS = {"decision", "match", "analysis"};

    private AiMessageValidator() {
    }

    /**
     * 校验话术。
     *
     * @param message   待校验话术（模型输出）
     * @param maxLength 最大长度；<=0 时按 120 处理
     * @return 校验结果（不合法时 reason 为中文原因）
     */
    public static Validation validate(String message, int maxLength) {
        if (message == null) {
            return new Validation(false, "话术为空");
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return new Validation(false, "话术为空");
        }

        // 控制字符检查用原始文本：normalize 会把 \t 等折叠成空格，从而漏掉非法字符
        for (int i = 0; i < message.length(); i++) {
            char ch = message.charAt(i);
            if (ch == '\n' || ch == '\r') {
                continue;
            }
            if (Character.isISOControl(ch)) {
                return new Validation(false, "话术包含换行以外的控制字符: U+" + toHexCode(ch));
            }
        }

        int limit = maxLength > 0 ? maxLength : DEFAULT_MAX_LENGTH;
        String normalized = normalize(message);
        if (normalized.length() > limit) {
            return new Validation(false, "话术超过长度上限 " + limit + " 字（当前 " + normalized.length() + " 字）");
        }
        if (normalized.contains("```")) {
            return new Validation(false, "话术包含 markdown 代码块围栏");
        }
        String lowered = normalized.toLowerCase();
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            if (lowered.contains(fragment)) {
                return new Validation(false, "话术包含 JSON 字段片段: " + fragment);
            }
        }
        return new Validation(true, null);
    }

    /**
     * 归一化：去首尾空白，并把连续空白（含换行、全角空格）压缩成单个空格。
     */
    public static String normalize(String message) {
        if (message == null) {
            return "";
        }
        return message.trim().replaceAll("[\\s\\u3000]+", " ");
    }

    /**
     * 控制字符转 U+XXXX 形式，便于日志定位（不依赖 String.format）。
     */
    private static String toHexCode(char ch) {
        String hex = Integer.toHexString(ch).toUpperCase();
        return "0000".substring(hex.length()) + hex;
    }

    /**
     * 校验结果。
     *
     * @param valid  是否合法
     * @param reason 不合法原因（合法时为 null）
     */
    public record Validation(boolean valid, String reason) {
    }
}
