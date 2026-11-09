package com.offerchaser.application.rules;

/**
 * 某个已持久化规则版本的对外视图。
 *
 * @param version    规则版本号（从 1 开始，单调递增）
 * @param sourceHash 规则 JSON 规范化后的 sha256（用于判断 live 配置是否变化）
 * @param createdAt  创建时间（yyyy-MM-dd HH:mm:ss）
 * @param note       版本说明（可为 null）
 * @param rules      规则载荷
 */
public record RuleSetView(int version, String sourceHash, String createdAt, String note, RulePayload rules) {
}
