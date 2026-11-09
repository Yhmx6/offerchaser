package com.offerchaser.application.onboarding;

import java.util.List;
import java.util.Map;

/**
 * 简历分析出的候选人画像（严格 JSON 的解析结果）。
 * <p>
 * 所有字段都是“可能识别不到”的：解析器只在模型给出合法类型时填充，
 * 缺失字段保持 null/空列表，不做任何猜测。该对象只用于本次分析/草稿生成，
 * 不落库、不保存原始简历文本或图片。
 * </p>
 */
public record ResumeProfile(
        Integer yearsExperience,
        List<String> roles,
        List<String> skills,
        String highestDegree,
        String major,
        Integer graduationYear,
        List<String> cities,
        Integer expectedSalaryMin,
        Integer expectedSalaryMax,
        List<String> industries,
        List<String> strengths,
        List<String> warnings,
        Map<String, String> evidence,
        Double confidence) {

    /**
     * 兼容旧调用方的构造器（不含专业/毕业年份，新增字段为 null）。
     * <p>API 字段只增不改：反序列化按字段名工作，这里只是给直接 new 的代码保住源码兼容。</p>
     */
    public ResumeProfile(Integer yearsExperience, List<String> roles, List<String> skills,
                         String highestDegree, List<String> cities,
                         Integer expectedSalaryMin, Integer expectedSalaryMax,
                         List<String> industries, List<String> strengths,
                         List<String> warnings, Map<String, String> evidence, Double confidence) {
        this(yearsExperience, roles, skills, highestDegree, null, null, cities,
                expectedSalaryMin, expectedSalaryMax, industries, strengths, warnings, evidence, confidence);
    }
}
