package com.offerchaser.application.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.ai.AiCallResult;
import com.offerchaser.application.ai.AiConfigException;
import com.offerchaser.application.service.AiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResumeAnalysisService} 单元测试：两段式调用、错误码、视觉/文本两条路径。
 * AiService 全部 mock，不联网。
 */
class ResumeAnalysisServiceTest {

    private static final String PROFILE_JSON = """
            {"yearsExperience":3,"roles":["产品经理"],"skills":["SQL"],"highestDegree":"本科",
             "major":"信息管理与信息系统","graduationYear":2021,
             "cities":["上海"],"expectedSalaryMin":20,"expectedSalaryMax":30,
             "industries":["互联网","企业服务"],"strengths":["数据驱动"],"warnings":["缺少项目细节"],
             "evidence":{"skills":"JD 中出现 SQL"},"confidence":0.8}
            """;

    private static final String SUGGESTIONS_JSON = """
            {"directions":[{"title":"AI 产品经理","fit":0.82,"reason":"画像匹配",
              "risks":["缺少大模型项目"],"keywords":["AI 产品经理"],
              "industries":["互联网/AI","人工智能"]}],
             "salaryBands":[{"label":"稳妥区间","min":20,"max":30,"basis":"经验","confidence":0.7}]}
            """;

    private AiService aiService;
    private ResumeImagePreprocessor imagePreprocessor;
    private ResumeAnalysisService service;

    @BeforeEach
    void setUp() {
        aiService = mock(AiService.class);
        imagePreprocessor = mock(ResumeImagePreprocessor.class);
        service = new ResumeAnalysisService(aiService, imagePreprocessor, new ObjectMapper());
    }

    @Test
    void analyzeImageReturnsProfileDirectionsAndSalaryBands() {
        when(imagePreprocessor.prepare(any(MultipartFile.class))).thenReturn(
                new ResumeImagePreprocessor.PreparedImage("data:image/jpeg;base64,AAAA", 1600, 800, 100, 50));
        when(aiService.sendVisionRequest(any(), any(), anyString()))
                .thenReturn(callResult(PROFILE_JSON));
        when(aiService.sendRequestWithUsage(anyString()))
                .thenReturn(callResult(SUGGESTIONS_JSON));

        ResumeAnalysis analysis = service.analyzeImage(
                new MockMultipartFile("file", "resume.png", "image/png", "png".getBytes()));

        assertTrue(analysis.visionUsed());
        assertEquals(3, analysis.profile().yearsExperience());
        assertEquals("信息管理与信息系统", analysis.profile().major());
        assertEquals(2021, analysis.profile().graduationYear());
        assertEquals(2, analysis.profile().industries().size());
        assertEquals(1, analysis.directions().size());
        assertEquals("AI 产品经理", analysis.directions().get(0).title());
        assertEquals(List.of("互联网/AI", "人工智能"), analysis.directions().get(0).industries());
        assertEquals(1, analysis.salaryBands().size());
        assertEquals(List.of("缺少项目细节"), analysis.warnings());
        assertEquals(0.8, analysis.profile().confidence(), 0.0001);

        ArgumentCaptor<String> dataUrl = ArgumentCaptor.forClass(String.class);
        verify(aiService).sendVisionRequest(anyString(), anyString(), dataUrl.capture());
        assertEquals("data:image/jpeg;base64,AAAA", dataUrl.getValue());
        verify(aiService).sendRequestWithUsage(anyString());
    }

    @Test
    void missingAiConfigurationReturnsAiConfigMissingBeforeTouchingImage() {
        doThrow(new AiConfigException("AI 配置缺少 MODEL")).when(aiService).validateConfiguration();

        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.analyzeImage(new MockMultipartFile("file", "resume.png", "image/png", new byte[]{1})));

        assertEquals(OnboardingException.AI_CONFIG_MISSING, error.code());
        assertEquals(400, error.status().value());
        assertTrue(error.getMessage().contains("AI 未配置"));
        verify(imagePreprocessor, never()).prepare(any());
    }

    @Test
    void invalidImageIsRejectedByPreprocessor() {
        when(imagePreprocessor.prepare(any(MultipartFile.class)))
                .thenThrow(OnboardingException.invalidImage("无法识别的图片文件"));

        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.analyzeImage(new MockMultipartFile("file", "resume.png", "image/png", new byte[]{1})));

        assertEquals(OnboardingException.INVALID_IMAGE, error.code());
        verify(aiService, never()).sendVisionRequest(any(), any(), any());
    }

    @Test
    void visionOutputInvalidJsonReturnsVisionParseFailedAndSkipsSuggestions() {
        stubImage();
        when(aiService.sendVisionRequest(any(), any(), anyString()))
                .thenReturn(callResult("这不是 JSON"));

        OnboardingException error = assertThrows(OnboardingException.class, () -> service.analyzeImage(stubFile()));

        assertEquals(OnboardingException.VISION_PARSE_FAILED, error.code());
        assertEquals(422, error.status().value());
        verify(aiService, never()).sendRequestWithUsage(anyString());
    }

    @Test
    void visionCallFailureReturnsVisionParseFailed() {
        stubImage();
        when(aiService.sendVisionRequest(any(), any(), anyString()))
                .thenThrow(new RuntimeException("AI视觉请求失败，状态码: 400"));

        OnboardingException error = assertThrows(OnboardingException.class, () -> service.analyzeImage(stubFile()));

        assertEquals(OnboardingException.VISION_PARSE_FAILED, error.code());
        assertTrue(error.getMessage().contains("视觉解析失败"));
    }

    @Test
    void suggestionOutputInvalidJsonReturnsAnalyzeFailed() {
        stubImage();
        when(aiService.sendVisionRequest(any(), any(), anyString())).thenReturn(callResult(PROFILE_JSON));
        when(aiService.sendRequestWithUsage(anyString())).thenReturn(callResult("不是 JSON"));

        OnboardingException error = assertThrows(OnboardingException.class, () -> service.analyzeImage(stubFile()));

        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, error.code());
        assertEquals(422, error.status().value());
    }

    @Test
    void suggestionCallFailureReturnsAnalyzeUpstreamFailed() {
        stubImage();
        when(aiService.sendVisionRequest(any(), any(), anyString())).thenReturn(callResult(PROFILE_JSON));
        when(aiService.sendRequestWithUsage(anyString()))
                .thenThrow(new RuntimeException("请求超时（45 秒）"));

        OnboardingException error = assertThrows(OnboardingException.class, () -> service.analyzeImage(stubFile()));

        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, error.code());
        assertEquals(502, error.status().value());
    }

    @Test
    void analyzeTextUsesTwoTextCallsAndNeverVision() {
        when(aiService.sendRequestWithUsage(anyString()))
                .thenReturn(callResult(PROFILE_JSON))
                .thenReturn(callResult(SUGGESTIONS_JSON));

        ResumeAnalysis analysis = service.analyzeText("三年产品经理经验，熟悉 SQL");

        assertFalse(analysis.visionUsed());
        assertEquals(3, analysis.profile().yearsExperience());
        assertEquals(1, analysis.directions().size());
        verify(aiService, times(2)).sendRequestWithUsage(anyString());
        verify(aiService, never()).sendVisionRequest(any(), any(), any());
    }

    @Test
    void supplementIsIncludedInTextExtractionPrompt() {
        when(aiService.sendRequestWithUsage(anyString()))
                .thenReturn(callResult(PROFILE_JSON))
                .thenReturn(callResult(SUGGESTIONS_JSON));

        service.analyzeText("三年产品经理经验", "我的优势是跨团队推进，做过两个 AI 产品 0→1");

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(aiService, times(2)).sendRequestWithUsage(prompts.capture());
        String extractionPrompt = prompts.getAllValues().get(0);
        assertTrue(extractionPrompt.contains("候选人补充说明"));
        assertTrue(extractionPrompt.contains("跨团队推进"));
        assertTrue(extractionPrompt.contains("<<<补充说明开始"));
    }

    @Test
    void supplementIsIncludedInVisionPrompt() {
        stubImage();
        when(aiService.sendVisionRequest(any(), any(), anyString())).thenReturn(callResult(PROFILE_JSON));
        when(aiService.sendRequestWithUsage(anyString())).thenReturn(callResult(SUGGESTIONS_JSON));

        service.analyzeImage(stubFile(), "我擅长数据分析和用户研究");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiService).sendVisionRequest(anyString(), userPrompt.capture(), anyString());
        assertTrue(userPrompt.getValue().contains("候选人补充说明"));
        assertTrue(userPrompt.getValue().contains("数据分析和用户研究"));
    }

    @Test
    void overlongSupplementIsRejected() {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.analyzeText("简历文本", "x".repeat(ResumeAnalysisService.MAX_SUPPLEMENT_CHARS + 1)));
        assertEquals(OnboardingException.INVALID_TEXT, error.code());
        assertTrue(error.getMessage().contains("补充说明过长"));
    }

    @Test
    void analyzeTextRejectsBlankText() {
        OnboardingException error = assertThrows(OnboardingException.class, () -> service.analyzeText("   "));
        assertEquals(OnboardingException.INVALID_TEXT, error.code());
        assertEquals(400, error.status().value());
    }

    @Test
    void analyzeTextRejectsOverlongText() {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.analyzeText("x".repeat(ResumeAnalysisService.MAX_RESUME_TEXT_CHARS + 1)));
        assertEquals(OnboardingException.INVALID_TEXT, error.code());
        assertTrue(error.getMessage().contains("过长"));
    }

    @Test
    void analyzeTextInvalidProfileJsonReturnsAnalyzeFailed() {
        when(aiService.sendRequestWithUsage(anyString())).thenReturn(callResult("not json"));

        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.analyzeText("简历文本"));

        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, error.code());
        assertEquals(422, error.status().value());
    }

    @Test
    void textExtractionCallFailureReturnsAnalyzeUpstreamFailed() {
        when(aiService.sendRequestWithUsage(anyString()))
                .thenThrow(new RuntimeException("网络异常"));

        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.analyzeText("简历文本"));

        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, error.code());
        assertEquals(502, error.status().value());
    }

    @Test
    void emptySuggestionsAddWarningsInsteadOfFailing() {
        stubImage();
        when(aiService.sendVisionRequest(any(), any(), anyString())).thenReturn(callResult(PROFILE_JSON));
        when(aiService.sendRequestWithUsage(anyString()))
                .thenReturn(callResult("{\"directions\":[],\"salaryBands\":[]}"));

        ResumeAnalysis analysis = service.analyzeImage(stubFile());

        assertTrue(analysis.directions().isEmpty());
        assertTrue(analysis.salaryBands().isEmpty());
        assertTrue(analysis.warnings().contains("AI 未给出推荐方向，可在向导中手动填写方向"));
        assertTrue(analysis.warnings().contains("AI 未给出薪资区间建议，可在向导中手动填写期望薪资"));
    }

    private void stubImage() {
        when(imagePreprocessor.prepare(any(MultipartFile.class))).thenReturn(
                new ResumeImagePreprocessor.PreparedImage("data:image/jpeg;base64,AAAA", 1600, 800, 100, 50));
    }

    private static MultipartFile stubFile() {
        return new MockMultipartFile("file", "resume.png", "image/png", "png".getBytes());
    }

    private static AiCallResult callResult(String content) {
        return new AiCallResult(content, "mock-model", 10, 5, 12L, true);
    }
}
