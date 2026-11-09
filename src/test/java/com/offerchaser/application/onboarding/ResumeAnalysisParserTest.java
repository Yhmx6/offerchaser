package com.offerchaser.application.onboarding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResumeAnalysisParser} 单元测试：严格 JSON、代码块、字段类型/范围校验、错误码。
 */
class ResumeAnalysisParserTest {

    private static final String VALID_PROFILE = """
            {"yearsExperience":3,"roles":["产品经理"],"skills":["SQL","Axure"],
             "highestDegree":"硕士研究生","major":"计算机科学与技术","graduationYear":2021,
             "cities":["上海"],"expectedSalaryMin":20,
             "expectedSalaryMax":30,"industries":["互联网","人工智能"],"strengths":["数据驱动"],
             "warnings":["未识别到项目细节"],"evidence":{"skills":["SQL","Axure"]},"confidence":0.82}
            """;

    private static final String VALID_SUGGESTIONS = """
            {"directions":[{"title":"AI 产品经理","fit":85,"reason":"画像匹配",
              "risks":["缺少大模型项目"],"keywords":["AI 产品经理","大模型产品"],
              "industries":["互联网/AI","人工智能"]}],
             "salaryBands":[{"label":"稳妥区间","min":20,"max":30,"basis":"当前薪资与经验","confidence":0.7}]}
            """;

    @Test
    void parsesValidProfileWithAllFields() {
        ResumeProfile profile = ResumeAnalysisParser.parseProfile(
                VALID_PROFILE, OnboardingException.VISION_PARSE_FAILED);

        assertEquals(3, profile.yearsExperience());
        assertEquals(1, profile.roles().size());
        assertEquals("产品经理", profile.roles().get(0));
        assertEquals(2, profile.skills().size());
        assertEquals("硕士", profile.highestDegree(), "研究生别名应归一为硕士");
        assertEquals("计算机科学与技术", profile.major());
        assertEquals(2021, profile.graduationYear());
        assertEquals("上海", profile.cities().get(0));
        assertEquals(20, profile.expectedSalaryMin());
        assertEquals(30, profile.expectedSalaryMax());
        assertEquals(2, profile.industries().size(), "行业数组应被解析");
        assertEquals(1, profile.warnings().size());
        assertEquals("SQL；Axure", profile.evidence().get("skills"), "证据数组按分号拼接");
        assertEquals(0.82, profile.confidence(), 0.0001);
    }

    @Test
    void acceptsMarkdownCodeFenceWrappedJson() {
        String raw = "```json\n" + VALID_PROFILE + "\n```";
        ResumeProfile profile = ResumeAnalysisParser.parseProfile(
                raw, OnboardingException.VISION_PARSE_FAILED);
        assertEquals(3, profile.yearsExperience());
    }

    @Test
    void missingOptionalFieldsBecomeNullsAndEmptyLists() {
        ResumeProfile profile = ResumeAnalysisParser.parseProfile(
                "{}", OnboardingException.VISION_PARSE_FAILED);
        assertNull(profile.yearsExperience());
        assertNull(profile.highestDegree());
        assertNull(profile.major());
        assertNull(profile.graduationYear());
        assertNull(profile.confidence());
        assertNull(profile.expectedSalaryMin());
        assertTrue(profile.roles().isEmpty());
        assertTrue(profile.industries().isEmpty());
        assertTrue(profile.evidence().isEmpty());
    }

    @Test
    void rejectsPlainText() {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile("这不是 JSON", OnboardingException.VISION_PARSE_FAILED));
        assertEquals(OnboardingException.VISION_PARSE_FAILED, error.code());
        assertEquals(422, error.status().value());
        assertTrue(error.getMessage().contains("JSON"));
    }

    @Test
    void rejectsJsonWithTrailingProse() {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile(VALID_PROFILE + "以上是结果",
                        OnboardingException.VISION_PARSE_FAILED));
        assertEquals(OnboardingException.VISION_PARSE_FAILED, error.code());
    }

    @Test
    void rejectsWrongFieldType() {
        String raw = """
                {"yearsExperience":3,"roles":"产品经理"}
                """;
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile(raw, OnboardingException.RESUME_ANALYZE_FAILED));
        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, error.code());
        assertTrue(error.getMessage().contains("roles"));
    }

    @Test
    void rejectsNonIntegerYears() {
        String raw = """
                {"yearsExperience":"3"}
                """;
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile(raw, OnboardingException.VISION_PARSE_FAILED));
        assertTrue(error.getMessage().contains("yearsExperience"));
    }

    @Test
    void rejectsSalaryRangeReversed() {
        String raw = """
                {"expectedSalaryMin":40,"expectedSalaryMax":20}
                """;
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile(raw, OnboardingException.VISION_PARSE_FAILED));
        assertTrue(error.getMessage().contains("expectedSalaryMin"));
    }

    @Test
    void rejectsConfidenceOutOfRange() {
        String raw = """
                {"confidence":150}
                """;
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile(raw, OnboardingException.VISION_PARSE_FAILED));
        assertTrue(error.getMessage().contains("confidence"));
    }

    @Test
    void rejectsNonStringMajorAndOutOfRangeGraduationYear() {
        OnboardingException majorError = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile("{\"major\":123}",
                        OnboardingException.VISION_PARSE_FAILED));
        assertTrue(majorError.getMessage().contains("major"));

        OnboardingException yearError = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile("{\"graduationYear\":\"2021\"}",
                        OnboardingException.VISION_PARSE_FAILED));
        assertTrue(yearError.getMessage().contains("graduationYear"));

        OnboardingException rangeError = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile("{\"graduationYear\":1900}",
                        OnboardingException.VISION_PARSE_FAILED));
        assertTrue(rangeError.getMessage().contains("graduationYear"));
    }

    @Test
    void rejectsEvidenceArrayWithNonString() {
        String raw = """
                {"evidence":{"skills":[1,2]}}
                """;
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile(raw, OnboardingException.VISION_PARSE_FAILED));
        assertTrue(error.getMessage().contains("evidence"));
    }

    @Test
    void parsesSuggestionsAndNormalizesPercentFit() {
        ResumeAnalysisParser.Suggestions suggestions = ResumeAnalysisParser.parseSuggestions(VALID_SUGGESTIONS);

        assertEquals(1, suggestions.directions().size());
        ResumeDirection direction = suggestions.directions().get(0);
        assertEquals("AI 产品经理", direction.title());
        assertEquals(0.85, direction.fit(), 0.0001, "85 应归一为 0.85");
        assertEquals(2, direction.keywords().size());
        assertEquals(List.of("互联网/AI", "人工智能"), direction.industries());
        assertEquals(1, suggestions.salaryBands().size());
        assertEquals(20, suggestions.salaryBands().get(0).min());
        assertEquals(0.7, suggestions.salaryBands().get(0).confidence(), 0.0001);
    }

    @Test
    void suggestionWithoutIndustriesFallsBackToEmptyList() {
        String raw = """
                {"directions":[{"title":"Java 后端"}],"salaryBands":[]}
                """;
        ResumeAnalysisParser.Suggestions suggestions = ResumeAnalysisParser.parseSuggestions(raw);
        assertTrue(suggestions.directions().get(0).industries().isEmpty());
    }

    @Test
    void rejectsDirectionWithNonArrayIndustries() {
        String raw = """
                {"directions":[{"title":"Java 后端","industries":"互联网"}],"salaryBands":[]}
                """;
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseSuggestions(raw));
        assertTrue(error.getMessage().contains("industries"));
    }

    @Test
    void rejectsSuggestionsWithoutDirections() {
        String raw = """
                {"salaryBands":[]}
                """;
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseSuggestions(raw));
        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, error.code());
        assertTrue(error.getMessage().contains("directions"));
    }

    @Test
    void rejectsSuggestionWithMissingTitle() {
        String raw = """
                {"directions":[{"fit":0.8}],"salaryBands":[]}
                """;
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseSuggestions(raw));
        assertTrue(error.getMessage().contains("title"));
    }

    @Test
    void rejectsSalaryBandWithReversedRange() {
        String raw = """
                {"directions":[],"salaryBands":[{"label":"区间","min":40,"max":20}]}
                """;
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseSuggestions(raw));
        assertTrue(error.getMessage().contains("min"));
    }
}
