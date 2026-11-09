package com.offerchaser.application.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 严格解析 AI 返回的简历画像 / 方向与薪资建议 JSON。
 * <p>
 * 约定：
 * <ul>
 *   <li>只接受单个 JSON 对象（允许一对 ``` / ```json 代码块包裹），JSON 之外有解释文字/数组一律拒绝；</li>
 *   <li>字段类型/范围不合法 → 抛 {@link OnboardingException}，错误消息只指出字段与期望，不回显简历内容；</li>
 *   <li>缺失/为 null 的字段保持 null 或空列表，不做任何猜测；</li>
 *   <li>不在日志/异常中输出模型原文，避免简历文本泄漏。</li>
 * </ul>
 * </p>
 */
public final class ResumeAnalysisParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_YEARS = 60;
    private static final int MAX_SALARY_K = 1000;
    private static final int MAX_MAJOR_LENGTH = 50;
    private static final int MIN_GRADUATION_YEAR = 1950;
    private static final int MAX_GRADUATION_YEAR = 2100;
    private static final int MAX_LIST_ITEMS = 50;
    private static final int MAX_ITEM_LENGTH = 200;
    private static final int MAX_EVIDENCE_ENTRIES = 30;
    private static final int MAX_EVIDENCE_LENGTH = 300;
    private static final int MAX_DIRECTIONS = 20;
    private static final int MAX_SALARY_BANDS = 10;
    private static final int MAX_TEXT_LENGTH = 1000;

    private static final Set<String> ALLOWED_DEGREES =
            Set.of("高中", "中专", "大专", "本科", "硕士", "博士");

    private ResumeAnalysisParser() {
    }

    /** 方向与薪资建议的解析结果。 */
    public record Suggestions(List<ResumeDirection> directions, List<ResumeSalaryBand> salaryBands) {
    }

    /**
     * 解析简历画像。
     *
     * @param raw       AI 原始输出
     * @param errorCode 失败时使用的错误码（视觉路径用 VISION_PARSE_FAILED，文本路径用 RESUME_ANALYZE_FAILED）
     */
    public static ResumeProfile parseProfile(String raw, String errorCode) {
        JsonNode root = readObject(raw, errorCode, "简历画像");

        Integer yearsExperience = optionalInt(root, "yearsExperience", errorCode, 0, MAX_YEARS);
        List<String> roles = optionalStringList(root, "roles", errorCode);
        List<String> skills = optionalStringList(root, "skills", errorCode);
        String highestDegree = normalizeDegree(optionalText(root, "highestDegree", errorCode, 20));
        String major = optionalText(root, "major", errorCode, MAX_MAJOR_LENGTH);
        Integer graduationYear = optionalInt(root, "graduationYear", errorCode,
                MIN_GRADUATION_YEAR, MAX_GRADUATION_YEAR);
        List<String> cities = optionalStringList(root, "cities", errorCode);
        Integer expectedSalaryMin = optionalInt(root, "expectedSalaryMin", errorCode, 0, MAX_SALARY_K);
        Integer expectedSalaryMax = optionalInt(root, "expectedSalaryMax", errorCode, 0, MAX_SALARY_K);
        if (expectedSalaryMin != null && expectedSalaryMax != null && expectedSalaryMin > expectedSalaryMax) {
            throw error(errorCode, "字段非法：expectedSalaryMin 不能大于 expectedSalaryMax");
        }
        List<String> industries = optionalStringList(root, "industries", errorCode);
        List<String> strengths = optionalStringList(root, "strengths", errorCode);
        List<String> warnings = optionalStringList(root, "warnings", errorCode);
        Map<String, String> evidence = optionalEvidence(root, errorCode);
        Double confidence = optionalRatio(root, "confidence", errorCode);

        return new ResumeProfile(yearsExperience, roles, skills, highestDegree, major, graduationYear,
                cities, expectedSalaryMin, expectedSalaryMax, industries, strengths, warnings, evidence, confidence);
    }

    /**
     * 解析方向与薪资建议。失败统一使用 {@code RESUME_ANALYZE_FAILED}（422）。
     */
    public static Suggestions parseSuggestions(String raw) {
        String code = OnboardingException.RESUME_ANALYZE_FAILED;
        JsonNode root = readObject(raw, code, "方向与薪资建议");

        JsonNode directionsNode = root.get("directions");
        if (directionsNode == null || directionsNode.isNull()) {
            throw error(code, "AI 返回缺少 directions 字段");
        }
        if (!directionsNode.isArray()) {
            throw error(code, "字段非法：directions 必须是数组");
        }
        if (directionsNode.size() > MAX_DIRECTIONS) {
            throw error(code, "字段非法：directions 最多 " + MAX_DIRECTIONS + " 个");
        }
        List<ResumeDirection> directions = new ArrayList<>();
        for (JsonNode item : directionsNode) {
            if (!item.isObject()) {
                throw error(code, "字段非法：directions 的每一项必须是对象");
            }
            String title = requiredText(item, "title", code, 100);
            Double fit = optionalRatio(item, "fit", code);
            String reason = optionalText(item, "reason", code, MAX_TEXT_LENGTH);
            List<String> risks = optionalStringList(item, "risks", code);
            List<String> keywords = optionalStringList(item, "keywords", code);
            List<String> industries = optionalStringList(item, "industries", code);
            directions.add(new ResumeDirection(title, fit, reason, risks, keywords, industries));
        }

        JsonNode bandsNode = root.get("salaryBands");
        if (bandsNode == null || bandsNode.isNull()) {
            throw error(code, "AI 返回缺少 salaryBands 字段");
        }
        if (!bandsNode.isArray()) {
            throw error(code, "字段非法：salaryBands 必须是数组");
        }
        if (bandsNode.size() > MAX_SALARY_BANDS) {
            throw error(code, "字段非法：salaryBands 最多 " + MAX_SALARY_BANDS + " 个");
        }
        List<ResumeSalaryBand> salaryBands = new ArrayList<>();
        for (JsonNode item : bandsNode) {
            if (!item.isObject()) {
                throw error(code, "字段非法：salaryBands 的每一项必须是对象");
            }
            String label = requiredText(item, "label", code, 50);
            Integer min = requiredInt(item, "min", code, 0, MAX_SALARY_K);
            Integer max = requiredInt(item, "max", code, 0, MAX_SALARY_K);
            if (min > max) {
                throw error(code, "字段非法：salaryBands 的 min 不能大于 max");
            }
            String basis = optionalText(item, "basis", code, 300);
            Double confidence = optionalRatio(item, "confidence", code);
            salaryBands.add(new ResumeSalaryBand(label, min, max, basis, confidence));
        }
        return new Suggestions(List.copyOf(directions), List.copyOf(salaryBands));
    }

    // ==================== JSON 基础解析 ====================

    private static JsonNode readObject(String raw, String errorCode, String label) {
        String json = extractJsonObject(raw, errorCode, label);
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw error(errorCode, label + "不是 JSON 对象");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw error(errorCode, label + "不是合法 JSON 对象");
        }
    }

    /** 只接受单个 JSON 对象；允许一对代码块围栏，围栏外不允许有任何文字。 */
    private static String extractJsonObject(String raw, String errorCode, String label) {
        if (raw == null || raw.isBlank()) {
            throw error(errorCode, "AI 返回的" + label + "为空");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd < 0) {
                throw error(errorCode, "AI 返回的" + label + "代码块不完整");
            }
            String header = text.substring(0, firstLineEnd).trim();
            if (!"```".equals(header) && !"```json".equalsIgnoreCase(header)) {
                throw error(errorCode, "AI 返回的" + label + "不是可解析的 JSON 代码块");
            }
            if (!text.endsWith("```")) {
                throw error(errorCode, "AI 返回的" + label + "代码块未闭合");
            }
            text = text.substring(firstLineEnd + 1, text.length() - 3).trim();
        }
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw error(errorCode, "AI 返回的" + label + "必须是单个 JSON 对象（禁止 markdown/解释/数组）");
        }
        return text;
    }

    // ==================== 字段级校验 ====================

    private static String requiredText(JsonNode parent, String field, String errorCode, int maxLength) {
        String value = optionalText(parent, field, errorCode, maxLength);
        if (value == null) {
            throw error(errorCode, "字段非法：" + field + " 不能为空");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field, String errorCode, int maxLength) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw error(errorCode, "字段非法：" + field + " 必须是字符串");
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw error(errorCode, "字段非法：" + field + " 超过 " + maxLength + " 字");
        }
        return value;
    }

    private static Integer requiredInt(JsonNode parent, String field, String errorCode, int min, int max) {
        Integer value = optionalInt(parent, field, errorCode, min, max);
        if (value == null) {
            throw error(errorCode, "字段非法：" + field + " 不能为空");
        }
        return value;
    }

    private static Integer optionalInt(JsonNode parent, String field, String errorCode, int min, int max) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw error(errorCode, "字段非法：" + field + " 必须是整数或 null");
        }
        int value = node.intValue();
        if (value < min || value > max) {
            throw error(errorCode, "字段非法：" + field + " 必须在 " + min + "~" + max + " 之间");
        }
        return value;
    }

    /** 0~1 的比例；模型偶尔给 0~100，按百分数归一（确定性换算，不是猜测）。 */
    private static Double optionalRatio(JsonNode parent, String field, String errorCode) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw error(errorCode, "字段非法：" + field + " 必须是数字或 null");
        }
        double value = node.asDouble();
        if (value >= 0 && value <= 1) {
            return value;
        }
        if (value > 1 && value <= 100) {
            return value / 100.0;
        }
        throw error(errorCode, "字段非法：" + field + " 必须在 0~1 之间");
    }

    private static List<String> optionalStringList(JsonNode parent, String field, String errorCode) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw error(errorCode, "字段非法：" + field + " 必须是字符串数组");
        }
        if (node.size() > MAX_LIST_ITEMS) {
            throw error(errorCode, "字段非法：" + field + " 最多 " + MAX_LIST_ITEMS + " 项");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw error(errorCode, "字段非法：" + field + " 中存在非字符串项");
            }
            String value = item.asText().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > MAX_ITEM_LENGTH) {
                throw error(errorCode, "字段非法：" + field + " 中存在超过 " + MAX_ITEM_LENGTH + " 字的项");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static Map<String, String> optionalEvidence(JsonNode parent, String errorCode) {
        JsonNode node = parent.get("evidence");
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw error(errorCode, "字段非法：evidence 必须是对象");
        }
        if (node.size() > MAX_EVIDENCE_ENTRIES) {
            throw error(errorCode, "字段非法：evidence 最多 " + MAX_EVIDENCE_ENTRIES + " 项");
        }
        LinkedHashMap<String, String> evidence = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty()) {
                throw error(errorCode, "字段非法：evidence 中存在空字段名");
            }
            String value = evidenceText(entry.getValue(), errorCode);
            if (!value.isEmpty()) {
                evidence.put(key, value);
            }
        }
        return Map.copyOf(evidence);
    }

    /** evidence 的值允许字符串或字符串数组；数组按“；”拼接为摘要。 */
    private static String evidenceText(JsonNode value, String errorCode) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            String text = value.asText().trim();
            return text.length() > MAX_EVIDENCE_LENGTH ? text.substring(0, MAX_EVIDENCE_LENGTH) : text;
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : value) {
                if (!item.isTextual()) {
                    throw error(errorCode, "字段非法：evidence 的数组值只能包含字符串");
                }
                String text = item.asText().trim();
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            }
            String joined = String.join("；", parts);
            return joined.length() > MAX_EVIDENCE_LENGTH ? joined.substring(0, MAX_EVIDENCE_LENGTH) : joined;
        }
        throw error(errorCode, "字段非法：evidence 的值必须是字符串或字符串数组");
    }

    /** 学历别名归一；不在支持集合内的值保留原文（草稿侧会再次判断是否可用）。 */
    private static String normalizeDegree(String degree) {
        if (degree == null) {
            return null;
        }
        return switch (degree) {
            case "研究生", "硕士研究生", "硕士生" -> "硕士";
            case "博士研究生" -> "博士";
            case "学士" -> "本科";
            case "专科" -> "大专";
            default -> ALLOWED_DEGREES.contains(degree) ? degree : degree;
        };
    }

    private static OnboardingException error(String errorCode, String message) {
        return OnboardingException.VISION_PARSE_FAILED.equals(errorCode)
                ? OnboardingException.visionParseFailed(message)
                : OnboardingException.analyzeInvalid(message);
    }
}
