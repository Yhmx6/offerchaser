package com.offerchaser.application.ai;

import java.util.List;

/**
 * AI 解析出的单条岗位要求及其匹配证据。
 * <p>
 * 该结构仅用于记录/展示，不参与发送判定；解析失败时允许为空列表。
 * </p>
 *
 * @param requirement 岗位要求原文
 * @param level       要求等级（如 必须/加分 等，模型可空）
 * @param evidenceIds 支持该判断的证据编号（可为空列表）
 * @param result      匹配结论（如 MATCH/UNKNOWN，模型可空）
 */
public record AiRequirement(String requirement, String level, List<String> evidenceIds, String result) {
}
