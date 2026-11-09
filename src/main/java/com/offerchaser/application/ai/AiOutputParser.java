package com.offerchaser.application.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI 输出解析器：只接受严格 JSON，绝不把原始文本当话术。
 * <p>
 * 支持的格式：
 * <ul>
 *   <li>新格式：{@code {"decision":"PASS|REJECT|REVIEW","analysis":"...","message":"..."}}；</li>
 *   <li>兼容旧格式：{@code {"match":true|false,"analysis":"...","message":"..."}}。</li>
 * </ul>
 * 只允许裸 JSON 或用一对 ``` / ```json 代码块包裹的 JSON；纯文本、markdown 解释、
 * 数组、字段类型错误、decision/match 取值非法一律返回 {@link AiOutcome#ERROR}。
 * </p>
 */
public final class AiOutputParser {

    /** 错误码：AI 输出不是可解析的 JSON / 缺少判定字段 */
    public static final String AI_OUTPUT_INVALID = "AI_OUTPUT_INVALID";
    /** 错误码：判定为匹配但缺少可用话术 */
    public static final String AI_MESSAGE_MISSING = "AI_MESSAGE_MISSING";
    /** 匹配分析最大保留字数：analysis 只做展示，不参与判定；超出服务端硬截断。 */
    public static final int MAX_ANALYSIS_CHARS = 80;

    /** markdown 代码块围栏 */
    private static final String CODE_FENCE = "```";

    private AiOutputParser() {
    }

    /**
     * 解析 AI 原始输出。
     * <p>任何异常情况都返回 ERROR，{@code message} 始终为 null（不会出现“原文当话术”）。</p>
     */
    public static AiMatchResult parse(String raw) {
        String json = extractSingleJsonObject(raw);
        if (json == null) {
            return AiMatchResult.error(AI_OUTPUT_INVALID,
                    "AI 输出不是单个 JSON 对象（可能是纯文本、markdown 说明或 JSON 数组）");
        }

        JSONObject object;
        try {
            object = new JSONObject(json);
        } catch (Exception e) {
            return AiMatchResult.error(AI_OUTPUT_INVALID, "AI 输出不是合法 JSON：" + e.getMessage());
        }

        // 新格式优先；两者同时存在时以 decision 为准
        if (object.has("decision")) {
            return parseDecisionFormat(object);
        }
        if (object.has("match")) {
            return parseLegacyMatchFormat(object);
        }
        return AiMatchResult.error(AI_OUTPUT_INVALID, "AI 输出 JSON 缺少 decision 或 match 字段");
    }

    /**
     * 新格式：decision = PASS / REJECT / REVIEW。
     */
    private static AiMatchResult parseDecisionFormat(JSONObject object) {
        Object rawDecision = object.opt("decision");
        if (!(rawDecision instanceof String decisionText)) {
            return AiMatchResult.error(AI_OUTPUT_INVALID, "decision 字段必须是字符串 PASS/REJECT/REVIEW");
        }
        String decision = decisionText.trim().toUpperCase(Locale.ROOT);
        AiOutcome outcome;
        switch (decision) {
            case "PASS" -> outcome = AiOutcome.MATCH;
            case "REJECT" -> outcome = AiOutcome.REJECT;
            case "REVIEW" -> outcome = AiOutcome.REVIEW;
            default -> {
                return AiMatchResult.error(AI_OUTPUT_INVALID, "AI 输出的 decision 取值非法: " + decisionText);
            }
        }
        String analysis = readAnalysis(object);
        List<AiRequirement> requirements = parseRequirements(object);
        if (outcome == AiOutcome.MATCH) {
            String message = readMessage(object);
            if (message == null) {
                return AiMatchResult.error(AI_MESSAGE_MISSING, "AI 判定 PASS 但缺少非空的 message 话术");
            }
            return new AiMatchResult(AiOutcome.MATCH, message, analysis, requirements,
                    null, null, null, 0, 0, 0);
        }
        // REJECT/REVIEW 不携带话术，避免被误用
        return new AiMatchResult(outcome, null, analysis, requirements,
                null, null, null, 0, 0, 0);
    }

    /**
     * 旧格式：{"match":true|false,"analysis":"...","message":"..."}。
     */
    private static AiMatchResult parseLegacyMatchFormat(JSONObject object) {
        Object rawMatch = object.opt("match");
        if (!(rawMatch instanceof Boolean match)) {
            return AiMatchResult.error(AI_OUTPUT_INVALID, "match 字段必须是布尔值 true/false");
        }
        String analysis = readAnalysis(object);
        List<AiRequirement> requirements = parseRequirements(object);
        if (match) {
            String message = readMessage(object);
            if (message == null) {
                return AiMatchResult.error(AI_MESSAGE_MISSING,
                        "旧格式 match=true 但缺少非空的 message 话术");
            }
            return new AiMatchResult(AiOutcome.MATCH, message, analysis, requirements,
                    null, null, null, 0, 0, 0);
        }
        return new AiMatchResult(AiOutcome.REJECT, null, analysis, requirements,
                null, null, null, 0, 0, 0);
    }

    /**
     * 提取唯一的 JSON 对象：
     * <ul>
     *   <li>裸 JSON 必须首字符 {、末字符 }；</li>
     *   <li>允许一对 ``` / ```json 代码块包裹（去掉围栏后仍需满足上一条）；</li>
     *   <li>其余情况（纯文本、数组、代码块外还有解释）一律返回 null。</li>
     * </ul>
     */
    private static String extractSingleJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.startsWith(CODE_FENCE)) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd < 0) {
                return null;
            }
            String header = text.substring(0, firstLineEnd).trim();
            if (!CODE_FENCE.equals(header) && !header.equalsIgnoreCase(CODE_FENCE + "json")) {
                return null;
            }
            if (!text.endsWith(CODE_FENCE)) {
                return null;
            }
            text = text.substring(firstLineEnd + 1, text.length() - CODE_FENCE.length()).trim();
        }
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return null;
        }
        return text;
    }

    /**
     * 读取 message：只接受字符串；缺失、空白、类型不对都按“缺失”处理。
     */
    private static String readMessage(JSONObject object) {
        Object raw = object.opt("message");
        if (!(raw instanceof String message)) {
            return null;
        }
        String trimmed = message.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 读取 analysis：只作为展示，不参与发送判定。
     * 空白和换行归一化，并硬截断到 {@link #MAX_ANALYSIS_CHARS} 字；避免模型长篇推理进入日志和待复核页。
     */
    private static String readAnalysis(JSONObject object) {
        String analysis = readString(object, "analysis");
        if (analysis.isEmpty()) {
            return "";
        }
        String normalized = analysis.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_ANALYSIS_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_ANALYSIS_CHARS);
    }

    /**
     * 读取字符串字段：非字符串/缺失时返回空串（analysis 只用于展示，不参与发送判定）。
     */
    private static String readString(JSONObject object, String key) {
        Object raw = object.opt(key);
        if (!(raw instanceof String value)) {
            return "";
        }
        return value.trim();
    }

    /**
     * 可选解析 requirements 数组；格式不合法时跳过该条，不影响整体判定。
     */
    private static List<AiRequirement> parseRequirements(JSONObject object) {
        JSONArray array = object.optJSONArray("requirements");
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<AiRequirement> requirements = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            List<String> evidenceIds = new ArrayList<>();
            JSONArray evidenceArray = item.optJSONArray("evidenceIds");
            if (evidenceArray != null) {
                for (int j = 0; j < evidenceArray.length(); j++) {
                    Object evidence = evidenceArray.opt(j);
                    if (evidence != null) {
                        evidenceIds.add(String.valueOf(evidence));
                    }
                }
            }
            requirements.add(new AiRequirement(
                    readString(item, "requirement"),
                    readString(item, "level"),
                    List.copyOf(evidenceIds),
                    readString(item, "result")));
        }
        return List.copyOf(requirements);
    }
}
