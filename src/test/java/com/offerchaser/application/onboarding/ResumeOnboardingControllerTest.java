package com.offerchaser.application.onboarding;

import com.offerchaser.application.controller.ResumeOnboardingController;
import com.offerchaser.application.templates.ProfileTemplateService.TemplateConfigRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ResumeOnboardingController} 契约测试：路由路径、成功外壳、错误码映射。
 * Service 全部 mock，不联网、不落库。
 */
class ResumeOnboardingControllerTest {

    private ResumeAnalysisService analysisService;
    private TemplateDraftService draftService;
    private ResumeOnboardingController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        analysisService = mock(ResumeAnalysisService.class);
        draftService = mock(TemplateDraftService.class);
        controller = new ResumeOnboardingController(analysisService, draftService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new OnboardingExceptionHandler())
                .build();
    }

    @Test
    void endpointsMatchFrozenContract() throws Exception {
        assertEquals("/api/boss", ResumeOnboardingController.class.getAnnotation(RequestMapping.class).value()[0]);

        PostMapping analyze = ResumeOnboardingController.class
                .getMethod("analyzeResume", MultipartFile.class, String.class).getAnnotation(PostMapping.class);
        assertEquals("/resume/analyze", analyze.value()[0]);
        assertEquals(MediaType.MULTIPART_FORM_DATA_VALUE, analyze.consumes()[0]);

        PostMapping analyzeText = ResumeOnboardingController.class
                .getMethod("analyzeResumeText", Map.class).getAnnotation(PostMapping.class);
        assertEquals("/resume/analyze-text", analyzeText.value()[0]);

        PostMapping draft = ResumeOnboardingController.class
                .getMethod("generateTemplateDraft", AiDraftRequest.class).getAnnotation(PostMapping.class);
        assertEquals("/templates/ai-draft", draft.value()[0]);
    }

    @Test
    void analyzeMultipartReturnsSuccessEnvelope() {
        when(analysisService.analyzeImage(any(MultipartFile.class))).thenReturn(sampleAnalysis(true));
        MockMultipartFile file = new MockMultipartFile("file", "resume.png", "image/png", "png".getBytes());

        ResponseEntity<Map<String, Object>> response = controller.analyzeResume(file, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        assertInstanceOf(ResumeAnalysis.class, response.getBody().get("data"));
        verify(analysisService).analyzeImage(file);
    }

    @Test
    void analyzeTextReturnsVisionUsedFalse() throws Exception {
        when(analysisService.analyzeText("三年产品经理经验")).thenReturn(sampleAnalysis(false));

        mockMvc.perform(post("/api/boss/resume/analyze-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"三年产品经理经验\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.visionUsed").value(false))
                .andExpect(jsonPath("$.data.profile.yearsExperience").value(3))
                .andExpect(jsonPath("$.data.directions[0].title").value("AI 产品经理"));
        verify(analysisService).analyzeText("三年产品经理经验");
    }

    @Test
    void analyzeTextPassesSupplementToService() throws Exception {
        when(analysisService.analyzeText("简历文本", "优势是数据分析")).thenReturn(sampleAnalysis(false));

        mockMvc.perform(post("/api/boss/resume/analyze-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"简历文本\",\"supplement\":\"优势是数据分析\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(analysisService).analyzeText("简历文本", "优势是数据分析");
    }

    @Test
    void blankAnalyzeTextMapsToInvalidText400() throws Exception {
        when(analysisService.analyzeText(isNull()))
                .thenThrow(OnboardingException.invalidText("请粘贴简历文本后再分析"));

        mockMvc.perform(post("/api/boss/resume/analyze-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(OnboardingException.INVALID_TEXT));
    }

    @Test
    void aiConfigMissingMapsTo400WithCode() throws Exception {
        when(analysisService.analyzeText(anyString()))
                .thenThrow(OnboardingException.aiConfigMissing(new RuntimeException("缺少 MODEL")));

        mockMvc.perform(post("/api/boss/resume/analyze-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"简历\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(OnboardingException.AI_CONFIG_MISSING));
    }

    @Test
    void visionParseFailureMapsTo422() throws Exception {
        when(analysisService.analyzeText(anyString()))
                .thenThrow(OnboardingException.visionParseFailed("视觉解析失败"));

        mockMvc.perform(post("/api/boss/resume/analyze-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"简历\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(OnboardingException.VISION_PARSE_FAILED));
    }

    @Test
    void aiDraftReturnsConfigAndWarnings() throws Exception {
        when(draftService.generate(any(AiDraftRequest.class))).thenReturn(sampleDraft());

        mockMvc.perform(post("/api/boss/templates/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profile":{"yearsExperience":3,"roles":["产品经理"]},
                                 "direction":"AI 产品经理","keywords":["AI 产品经理"],
                                 "intensity":"normal","sendImgResume":true,"templateName":"AI 模板"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("AI 模板"))
                .andExpect(jsonPath("$.data.direction").value("AI 产品经理"))
                .andExpect(jsonPath("$.data.config.enableAI").value(true))
                .andExpect(jsonPath("$.data.config.salary[0]").value("10-20K"))
                .andExpect(jsonPath("$.data.config.expectedSalaryMin").value(20))
                .andExpect(jsonPath("$.data.config.expectedSalaryMax").value(30))
                .andExpect(jsonPath("$.data.warnings[0]").value("草稿提醒"));

        ArgumentCaptor<AiDraftRequest> captor = ArgumentCaptor.forClass(AiDraftRequest.class);
        verify(draftService).generate(captor.capture());
        assertEquals("AI 产品经理", captor.getValue().direction());
        assertEquals(3, captor.getValue().profile().yearsExperience());
        assertNull(captor.getValue().cities(), "旧请求不传 cities 时保持 null（后端按 cityCode 回退）");
        assertNull(captor.getValue().industries());
        assertNull(captor.getValue().salary(), "旧请求不传 salary 时保持 null（后端回退画像推荐值）");
    }

    @Test
    void aiDraftAcceptsSalaryFields() throws Exception {
        when(draftService.generate(any(AiDraftRequest.class))).thenReturn(sampleDraft());

        mockMvc.perform(post("/api/boss/templates/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profile":{"yearsExperience":3,"roles":["产品经理"]},
                                 "direction":"AI 产品经理","keywords":["AI 产品经理"],
                                 "salary":["10-20K","20-30K"],
                                 "expectedSalaryMin":12,"expectedSalaryMax":28,
                                 "intensity":"normal","sendImgResume":true,"templateName":"AI 模板"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<AiDraftRequest> captor = ArgumentCaptor.forClass(AiDraftRequest.class);
        verify(draftService).generate(captor.capture());
        assertEquals(List.of("10-20K", "20-30K"), captor.getValue().salary(), "salary 档位数组按 camelCase 反序列化");
        assertEquals(12, captor.getValue().expectedSalaryMin());
        assertEquals(28, captor.getValue().expectedSalaryMax());
    }

    @Test
    void aiDraftAcceptsNewCitiesAndIndustriesFields() throws Exception {
        when(draftService.generate(any(AiDraftRequest.class))).thenReturn(sampleDraft());

        mockMvc.perform(post("/api/boss/templates/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profile":{"yearsExperience":3,"roles":["产品经理"],"major":"计算机"},
                                 "direction":"AI 产品经理","keywords":["AI 产品经理"],
                                 "cities":["上海","杭州"],"industries":["互联网/AI","人工智能"],
                                 "cityCode":"北京","intensity":"normal","sendImgResume":true,
                                 "templateName":"AI 模板"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<AiDraftRequest> captor = ArgumentCaptor.forClass(AiDraftRequest.class);
        verify(draftService).generate(captor.capture());
        assertEquals(List.of("上海", "杭州"), captor.getValue().cities());
        assertEquals(List.of("互联网/AI", "人工智能"), captor.getValue().industries());
        assertEquals("北京", captor.getValue().cityCode(), "旧字段仍被接收，只是新字段优先");
    }

    @Test
    void invalidDraftMapsTo400WithCode() throws Exception {
        when(draftService.generate(any())).thenThrow(OnboardingException.draftInvalid("请至少填写一个搜索关键词"));

        mockMvc.perform(post("/api/boss/templates/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(OnboardingException.DRAFT_INVALID));
    }

    @Test
    void uploadTooLargeHandlerReturns413InvalidImage() {
        ResponseEntity<Map<String, Object>> response =
                new OnboardingExceptionHandler().handleMaxUploadSizeExceeded();

        assertEquals(413, response.getStatusCode().value());
        assertEquals(OnboardingException.INVALID_IMAGE, response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("10MB"));
    }

    private static ResumeAnalysis sampleAnalysis(boolean visionUsed) {
        ResumeProfile profile = new ResumeProfile(3, List.of("产品经理"), List.of("SQL"),
                "本科", List.of("上海"), 20, 30, List.of("互联网"), List.of("数据驱动"),
                List.of(), Map.of("skills", "简历出现 SQL"), 0.8);
        List<ResumeDirection> directions = List.of(
                new ResumeDirection("AI 产品经理", 0.82, "画像匹配",
                        List.of("缺少大模型项目"), List.of("AI 产品经理")));
        List<ResumeSalaryBand> bands = List.of(
                new ResumeSalaryBand("稳妥区间", 20, 30, "经验", 0.7));
        return new ResumeAnalysis(profile, directions, bands, List.of(), visionUsed);
    }

    private static AiDraftResult sampleDraft() {
        TemplateConfigRequest config = new TemplateConfigRequest(
                List.of("AI 产品经理"), "上海", null, null, null, null,
                List.of("10-20K"), null, null,
                20, 30, true, true, null, null, null, null, null, null,
                null, "3 年产品经理经验；熟悉 SQL。", null, null);
        return new AiDraftResult("AI 模板", "AI 产品经理", "草稿说明", config, List.of("草稿提醒"));
    }
}
