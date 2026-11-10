package com.offerchaser.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.ai.AiCallResult;
import com.offerchaser.application.ai.AiConfigException;
import com.offerchaser.application.controller.ResumeOnboardingController;
import com.offerchaser.application.onboarding.AiDraftRequest;
import com.offerchaser.application.onboarding.AiDraftResult;
import com.offerchaser.application.onboarding.OnboardingException;
import com.offerchaser.application.onboarding.OnboardingExceptionHandler;
import com.offerchaser.application.onboarding.ResumeAnalysis;
import com.offerchaser.application.onboarding.ResumeAnalysisParser;
import com.offerchaser.application.onboarding.ResumeAnalysisService;
import com.offerchaser.application.onboarding.ResumeImagePreprocessor;
import com.offerchaser.application.onboarding.ResumeProfile;
import com.offerchaser.application.onboarding.TemplateDraftService;
import com.offerchaser.application.security.AuthInterceptor;
import com.offerchaser.application.security.LocalAuthService;
import com.offerchaser.application.templates.ProfileTemplateService.DeliveryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「AI 帮我建模板」MVP 的独立后端验收：三个接口契约、错误码、认证/CSRF、图片内存处理、
 * ai-draft 不建模板/不激活/不调用 AI，以及草稿强度 trial=2/5/20。
 * <p>
 * Service 使用真实实现（只有外部 AI 调用用 Mockito 替身），不联网、不写数据库。
 */
class AiTemplateOnboardingContractTest {

    private static final Path ONBOARDING_DIR = Path.of("src", "main", "java",
            "com/offerchaser/application/onboarding");

    private com.offerchaser.application.service.AiService aiService;
    private ResumeAnalysisService analysisService;
    private TemplateDraftService draftService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        aiService = mock(com.offerchaser.application.service.AiService.class);
        analysisService = new ResumeAnalysisService(aiService,
                new ResumeImagePreprocessor(), new ObjectMapper());
        draftService = new TemplateDraftService();
        ResumeOnboardingController controller = new ResumeOnboardingController(analysisService, draftService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new OnboardingExceptionHandler())
                .build();
        doNothing().when(aiService).validateConfiguration();
    }

    // ==================== 路由契约 ====================

    @Test
    void endpointsMatchFrozenContract() throws Exception {
        var mapping = ResumeOnboardingController.class.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class);
        assertNotNull(mapping, "缺少类级 @RequestMapping");
        assertEquals("/api/boss", mapping.value()[0]);

        var analyze = ResumeOnboardingController.class
                .getMethod("analyzeResume", org.springframework.web.multipart.MultipartFile.class, String.class)
                .getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        assertEquals("/resume/analyze", analyze.value()[0], "图片分析路由");
        assertEquals(MediaType.MULTIPART_FORM_DATA_VALUE, analyze.consumes()[0],
                "图片分析必须是 multipart/form-data");

        var analyzeText = ResumeOnboardingController.class
                .getMethod("analyzeResumeText", Map.class)
                .getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        assertEquals("/resume/analyze-text", analyzeText.value()[0], "文本分析路由");

        var draft = ResumeOnboardingController.class
                .getMethod("generateTemplateDraft", AiDraftRequest.class)
                .getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        assertEquals("/templates/ai-draft", draft.value()[0], "草稿生成路由");
    }

    // ==================== 分析成功路径 ====================

    @Test
    void analyzeTextUsesTwoStrictAiCallsAndReturnsVisionFalse() throws Exception {
        when(aiService.sendRequestWithUsage(anyString()))
                .thenReturn(aiResult(PROFILE_JSON), aiResult(SUGGESTIONS_JSON));

        mockMvc.perform(post("/api/boss/resume/analyze-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"五年 Java 后端经验，本科，上海，期望 30-50K\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.visionUsed").value(false))
                .andExpect(jsonPath("$.data.profile.yearsExperience").value(5))
                .andExpect(jsonPath("$.data.profile.highestDegree").value("本科"))
                .andExpect(jsonPath("$.data.profile.major").value("软件工程"))
                .andExpect(jsonPath("$.data.profile.graduationYear").value(2020))
                .andExpect(jsonPath("$.data.profile.industries[0]").value("互联网/AI"))
                .andExpect(jsonPath("$.data.directions[0].title").value("Java 后端"))
                .andExpect(jsonPath("$.data.directions[0].industries[0]").value("互联网/AI"))
                .andExpect(jsonPath("$.data.salaryBands[0].min").value(25));

        verify(aiService, times(2)).sendRequestWithUsage(anyString());
        verify(aiService, never()).sendVisionRequest(anyString(), anyString(), anyString());
    }

    @Test
    void analyzeImageCompressesInMemoryAndReturnsVisionTrue() throws Exception {
        when(aiService.sendVisionRequest(anyString(), anyString(), anyString()))
                .thenReturn(aiResult(PROFILE_JSON));
        when(aiService.sendRequestWithUsage(anyString()))
                .thenReturn(aiResult(SUGGESTIONS_JSON));

        byte[] png = samplePng(2400, 1200);
        MockMultipartFile file = new MockMultipartFile("file", "resume.png", "image/png", png);

        mockMvc.perform(multipart("/api/boss/resume/analyze").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.visionUsed").value(true))
                .andExpect(jsonPath("$.data.profile.roles[0]").value("Java 后端"));

        ArgumentCaptor<String> dataUrl = ArgumentCaptor.forClass(String.class);
        verify(aiService).sendVisionRequest(anyString(), anyString(), dataUrl.capture());
        assertTrue(dataUrl.getValue().startsWith("data:image/jpeg;base64,"),
                "视觉请求应使用内存压缩后的 JPEG data URL");
        byte[] jpeg = Base64.getDecoder().decode(dataUrl.getValue().substring("data:image/jpeg;base64,".length()));
        assertTrue(jpeg.length > 0 && (jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8,
                "data URL 内容应是 JPEG（内存编码，不落盘）");
    }

    @Test
    void imageProcessingDoesNotWriteFilesToDisk() throws Exception {
        byte[] png = samplePng(400, 300);
        ResumeImagePreprocessor.PreparedImage prepared = new ResumeImagePreprocessor()
                .prepare(new MockMultipartFile("file", "r.png", "image/png", png));
        assertTrue(prepared.dataUrl().startsWith("data:image/jpeg;base64,"));

        String source = Files.readString(ONBOARDING_DIR.resolve("ResumeImagePreprocessor.java"));
        for (String forbidden : List.of("FileOutputStream", "Files.write", "Files.newOutputStream",
                "createTempFile", "toFile(", "Paths.get", "ImageIO.write(")) {
            assertFalse(source.contains(forbidden),
                    "图片处理不得落盘，发现写文件 API：" + forbidden);
        }
        assertTrue(source.contains("ByteArrayOutputStream") && source.contains("Base64"),
                "图片应只在内存压缩后转 base64");
    }

    // ==================== 错误码 ====================

    @Test
    void missingOrInvalidImageReturnsInvalidImage400() throws Exception {
        // 无文件
        mockMvc.perform(multipart("/api/boss/resume/analyze"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(OnboardingException.INVALID_IMAGE));

        // 非图片内容
        MockMultipartFile fake = new MockMultipartFile("file", "resume.png", "image/png",
                "not-an-image".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/boss/resume/analyze").file(fake))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(OnboardingException.INVALID_IMAGE));

        // 空文件
        MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);
        mockMvc.perform(multipart("/api/boss/resume/analyze").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(OnboardingException.INVALID_IMAGE));
    }

    @Test
    void aiConfigMissingReturns400ForImageAndText() throws Exception {
        doThrow(new AiConfigException("AI 配置缺少 MODEL"))
                .when(aiService).validateConfiguration();

        mockMvc.perform(multipart("/api/boss/resume/analyze")
                        .file(new MockMultipartFile("file", "resume.png", "image/png", samplePng(200, 200))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(OnboardingException.AI_CONFIG_MISSING));

        mockMvc.perform(post("/api/boss/resume/analyze-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"五年产品经验\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(OnboardingException.AI_CONFIG_MISSING));
    }

    @Test
    void visionParseFailureMapsTo422() throws Exception {
        when(aiService.sendVisionRequest(anyString(), anyString(), anyString()))
                .thenReturn(aiResult("抱歉，我无法识别这张图片"));

        mockMvc.perform(multipart("/api/boss/resume/analyze")
                        .file(new MockMultipartFile("file", "resume.png", "image/png", samplePng(200, 200))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(OnboardingException.VISION_PARSE_FAILED));
    }

    @Test
    void textInvalidCasesReturnInvalidText400() throws Exception {
        mockMvc.perform(post("/api/boss/resume/analyze-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(OnboardingException.INVALID_TEXT));

        mockMvc.perform(post("/api/boss/resume/analyze-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":123}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(OnboardingException.INVALID_TEXT));

        String tooLong = "简历".repeat(10_001);
        mockMvc.perform(post("/api/boss/resume/analyze-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("text", tooLong))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(OnboardingException.INVALID_TEXT));
    }

    @Test
    void strictParserRejectsNonJsonAndInvalidFields() {
        OnboardingException vision = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile("```json\n{broken}\n```",
                        OnboardingException.VISION_PARSE_FAILED));
        assertEquals(OnboardingException.VISION_PARSE_FAILED, vision.code());
        assertEquals(422, vision.status().value());

        OnboardingException text = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile("", OnboardingException.RESUME_ANALYZE_FAILED));
        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, text.code());

        OnboardingException suggestions = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseSuggestions("{\"directions\":[],\"salaryBands\":[{\"label\":\"x\",\"min\":5,\"max\":1}]}"));
        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, suggestions.code());

        // 合法 JSON 代码块可以解析（宽容围栏、严格内容）
        ResumeProfile profile = ResumeAnalysisParser.parseProfile(
                "```json\n" + PROFILE_JSON + "\n```", OnboardingException.VISION_PARSE_FAILED);
        assertEquals(5, profile.yearsExperience());
        assertEquals("本科", profile.highestDegree());
        assertEquals("软件工程", profile.major());
        assertEquals(2020, profile.graduationYear());
        assertEquals(List.of("互联网/AI"), profile.industries());
        assertEquals(List.of("互联网/AI", "企业服务"),
                ResumeAnalysisParser.parseSuggestions(SUGGESTIONS_JSON).directions().get(0).industries());

        OnboardingException tooEarly = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile("{\"graduationYear\":1949}",
                        OnboardingException.RESUME_ANALYZE_FAILED));
        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, tooEarly.code());
        OnboardingException tooLate = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile("{\"graduationYear\":2101}",
                        OnboardingException.RESUME_ANALYZE_FAILED));
        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, tooLate.code());
        OnboardingException majorTooLong = assertThrows(OnboardingException.class,
                () -> ResumeAnalysisParser.parseProfile(
                        "{\"major\":\"" + "专".repeat(51) + "\"}",
                        OnboardingException.RESUME_ANALYZE_FAILED));
        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED, majorTooLong.code());
    }

    // ==================== 草稿生成 ====================

    @Test
    void aiDraftDoesNotCreateOrActivateTemplatesAndDoesNotCallAi() throws Exception {
        mockMvc.perform(post("/api/boss/templates/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftJson("trial")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Java 后端 模板"))
                .andExpect(jsonPath("$.data.direction").value("Java 后端"))
                .andExpect(jsonPath("$.data.config.deliveryPolicy.collectMaxPerKeyword").value(2))
                .andExpect(jsonPath("$.data.config.deliveryPolicy.collectMaxDurationMinutes").value(5))
                .andExpect(jsonPath("$.data.config.deliveryPolicy.collectNoNewWindowSeconds").value(20))
                .andExpect(jsonPath("$.data.config.cityCode").value("上海"))
                .andExpect(jsonPath("$.data.config.rule.cities").isEmpty())
                .andExpect(jsonPath("$.data.config.rule.jobDirections").isEmpty())
                .andExpect(jsonPath("$.data.config.rule.blacklistCities").isEmpty())
                .andExpect(jsonPath("$.data.config.industry[0]").value("互联网/AI"))
                .andExpect(jsonPath("$.data.config.degree[0]").value("本科"))
                .andExpect(jsonPath("$.data.config.experience[0]").value("5-10年"))
                .andExpect(jsonPath("$.data.config.scale[0]").value("20-99人"))
                .andExpect(jsonPath("$.data.config.scale[4]").value("10000人以上"))
                .andExpect(jsonPath("$.data.config.stage").isEmpty())
                .andExpect(jsonPath("$.data.config.salary").isEmpty())
                .andExpect(jsonPath("$.data.config.expectedSalaryMin").value(30))
                .andExpect(jsonPath("$.data.config.expectedSalaryMax").value(50))
                .andExpect(jsonPath("$.data.config.rule.expectedSalaryMin").value(30))
                .andExpect(jsonPath("$.data.config.rule.expectedSalaryMax").value(0));

        verifyNoInteractions(aiService);

        // TemplateDraftService 无任何持久化依赖：无字段、无有参构造，源码不出现模板写接口
        Field[] fields = TemplateDraftService.class.getDeclaredFields();
        assertTrue(Arrays.stream(fields).noneMatch(field -> !Modifier.isStatic(field.getModifiers())),
                "草稿服务不得注入 Repository/Mapper/模板服务");
        Constructor<?>[] constructors = TemplateDraftService.class.getDeclaredConstructors();
        assertTrue(Arrays.stream(constructors).allMatch(constructor -> constructor.getParameterCount() == 0),
                "草稿服务应是无依赖纯计算");
        String draftSource = Files.readString(ONBOARDING_DIR.resolve("TemplateDraftService.java"));
        for (String forbidden : List.of("Repository", "Mapper", "JdbcTemplate", "insert(",
                "createTemplate(", "activateTemplate(", "save(")) {
            assertFalse(draftSource.contains(forbidden),
                    "草稿生成不得触碰模板持久化：" + forbidden);
        }
        String controllerSource = Files.readString(Path.of("src", "main", "java",
                "com/offerchaser/application/controller/ResumeOnboardingController.java"));
        for (String forbidden : List.of("createTemplate", "activateTemplate", "updateTemplateConfig",
                "ProfileTemplateService", "ProfileTemplateRepository")) {
            assertFalse(controllerSource.contains(forbidden),
                    "onboarding controller 不得直接创建/激活模板：" + forbidden);
        }
    }

    @Test
    void trialIntensityDraftPolicyIsTwoFiveTwenty() {
        AiDraftResult trial = draftService.generate(draftRequest("trial"));
        assertPolicy(trial.config().deliveryPolicy(), 2, 5, 20);

        AiDraftResult normal = draftService.generate(draftRequest("normal"));
        assertPolicy(normal.config().deliveryPolicy(), 60, 15, 30);

        AiDraftResult active = draftService.generate(draftRequest("active"));
        assertPolicy(active.config().deliveryPolicy(), 100, 30, 30);
    }

    @Test
    void draftRejectsInvalidInputsWithDraftInvalid() {
        assertEquals(OnboardingException.DRAFT_INVALID,
                assertThrows(OnboardingException.class, () -> draftService.generate(null)).code());

        assertDraftInvalid(new AiDraftRequest(null, "Java", List.of("Java"), null,
                null, null, List.of(), "trial", false, "x"));
        assertDraftInvalid(new AiDraftRequest(profile(), "Java", List.of(), null,
                null, null, List.of(), "trial", false, "x"));
        assertDraftInvalid(new AiDraftRequest(profile(), " ", List.of("Java"), null,
                null, null, List.of(), "trial", false, "x"));
        assertDraftInvalid(new AiDraftRequest(profile(), "Java", List.of("Java"), null,
                null, null, List.of(), "extreme", false, "x"));
        assertDraftInvalid(new AiDraftRequest(profile(), "Java", List.of("Java"), null,
                -1, null, List.of(), "trial", false, "x"));
        assertDraftInvalid(new AiDraftRequest(profile(), "Java",
                List.of("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8", "k9", "k10",
                        "k11", "k12", "k13", "k14", "k15", "k16", "k17", "k18", "k19",
                        "k20", "k21"), null, null, null, List.of(), "trial", false, "x"));
    }

    @Test
    void draftInvalidMapsTo400() throws Exception {
        mockMvc.perform(post("/api/boss/templates/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(OnboardingException.DRAFT_INVALID));
    }

    @Test
    void aiDraftAcceptsSalaryFieldsFromJson() throws Exception {
        mockMvc.perform(post("/api/boss/templates/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftJsonWithSalary()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.config.salary[0]").value("10-20K"))
                .andExpect(jsonPath("$.data.config.salary[1]").value("20-30K"))
                .andExpect(jsonPath("$.data.config.expectedSalaryMin").value(12))
                .andExpect(jsonPath("$.data.config.expectedSalaryMax").value(28))
                .andExpect(jsonPath("$.data.config.rule.expectedSalaryMin").value(12))
                .andExpect(jsonPath("$.data.config.rule.expectedSalaryMax").value(0));
        verifyNoInteractions(aiService);
    }

    @Test
    void draftMapsCitiesIndustriesDegreeExperienceScaleAndStage() {
        ResumeProfile profile = fullProfile(3, "本科", "软件工程", 2020,
                List.of("上海", "杭州"), List.of("互联网/AI"));
        AiDraftRequest request = new AiDraftRequest(profile, "Java 后端",
                List.of("Java", "后端"), null, 30, 50, List.of("外包", "销售"),
                "trial", false, "Java 后端 模板",
                List.of("上海", "杭州", "北京", "深圳"),
                List.of("互联网/AI", "企业服务", "人工智能"));

        AiDraftResult result = draftService.generate(request);
        var config = result.config();
        assertEquals("上海", config.cityCode(), "第一个城市作为模板主搜索城市");
        assertTrue(config.rule().cities().isEmpty(), "AI 草稿不再写可接受城市（置空）");
        assertTrue(config.rule().jobDirections().isEmpty(), "AI 草稿不再写方向（置空）");
        assertTrue(config.rule().blacklistCities().isEmpty(), "AI 草稿不可接受城市为空");
        assertEquals(List.of("互联网/AI", "企业服务", "人工智能"), config.industry(),
                "行业偏好写入模板行业条件");
        assertEquals(List.of("本科"), config.degree(), "BOSS 学历档");
        assertEquals("本科", config.rule().highestDegree(), "规则学历");
        assertEquals(List.of("3-5年"), config.experience(), "3 年经验映射 3-5年");
        assertEquals(List.of("20-99人", "100-499人", "500-999人", "1000-9999人", "10000人以上"),
                config.scale(), "公司规模固定 20 人以上五档");
        assertEquals(List.of(), config.stage(), "融资阶段不限");
        assertEquals(List.of("外包", "销售"), config.rule().blacklistJob(), "默认/自定义排除词进黑名单");
        assertEquals(List.of(), config.rule().blacklistCompany(), "AI 草稿不得写公司黑名单");
        assertEquals(List.of(), config.rule().blacklistRecruiter(), "AI 草稿不得写 HR 黑名单");
        assertTrue(config.rule().jobDirections().isEmpty(), "AI 草稿不再写方向规则（置空）");
        assertEquals(30, config.rule().expectedSalaryMin(), "规则薪资只设下限");
        assertEquals(0, config.rule().expectedSalaryMax(), "规则薪资上限固定 0（未设置）");
        assertEquals(30, config.expectedSalaryMin(), "A 区期望月薪下限写请求真实值");
        assertEquals(50, config.expectedSalaryMax(), "A 区期望月薪上限写请求真实值");
        assertEquals(List.of(), config.salary(), "旧请求未传档位时 config.salary 为空列表（不限）");
        assertTrue(result.warnings().contains("未选择 BOSS 薪资范围档位，草稿未设置薪资筛选"),
                "未选档位应有提示");
        assertPolicy(config.deliveryPolicy(), 2, 5, 20);
    }

    @Test
    void draftWritesSalaryBracketsAndRealBoundsIntoConfig() {
        ResumeProfile profile = fullProfile(5, "本科", "软件工程", 2020,
                List.of("上海"), List.of("互联网/AI"));
        AiDraftRequest request = new AiDraftRequest(profile, "Java 后端",
                List.of("Java"), null, 12, 28, List.of("外包"),
                "normal", false, "Java 后端 模板",
                List.of("上海"), List.of("互联网/AI"),
                List.of("10-20K", "20-30K"));

        AiDraftResult result = draftService.generate(request);
        assertEquals(List.of("10-20K", "20-30K"), result.config().salary(), "BOSS 薪资档位写入 config.salary");
        assertEquals(12, result.config().expectedSalaryMin(), "config 写真实下限");
        assertEquals(28, result.config().expectedSalaryMax(), "config 写真实上限");
        assertEquals(12, result.config().rule().expectedSalaryMin(), "规则层只写真实下限");
        assertEquals(0, result.config().rule().expectedSalaryMax(), "规则层上限仍固定 0：不改变既有筛选语义");
        assertFalse(result.warnings().stream().anyMatch(warning -> warning.contains("薪资")),
                "薪资完整时不应有薪资相关提示：" + result.warnings());
    }

    @Test
    void draftExplicitNullSalaryWithBracketsDoesNotFallBackToProfile() {
        // profile 自带 30/50，但用户显式清空（min/max=null）且选了档位：不得用画像推荐值回填。
        ResumeProfile profile = fullProfile(5, "本科", "软件工程", 2020,
                List.of("上海"), List.of("互联网/AI"));
        AiDraftRequest request = new AiDraftRequest(profile, "Java 后端",
                List.of("Java"), null, null, null, List.of(),
                "normal", false, "Java 后端 模板",
                List.of("上海"), List.of("互联网/AI"), List.of("10-20K"));

        AiDraftResult result = draftService.generate(request);
        assertNull(result.config().expectedSalaryMin(), "显式 null 下限不得回退画像");
        assertNull(result.config().expectedSalaryMax(), "显式 null 上限不得回退画像");
        assertNull(result.config().rule().expectedSalaryMin(), "规则层也不得回填画像下限");
        assertEquals(0, result.config().rule().expectedSalaryMax());
        assertEquals(List.of("10-20K"), result.config().salary(), "档位照常写入");
        assertTrue(result.warnings().contains("未提供期望薪资，草稿未设置薪资区间"));
        assertFalse(result.warnings().contains("未选择 BOSS 薪资范围档位，草稿未设置薪资筛选"),
                "已选档位时不应提示未选档位");
    }

    @Test
    void draftLegacyRequestWithoutSalaryFallsBackToProfileNumbers() {
        ResumeProfile profile = fullProfile(5, "本科", "软件工程", 2020,
                List.of("上海"), List.of("互联网/AI"));
        AiDraftRequest legacy = new AiDraftRequest(profile, "Java 后端",
                List.of("Java"), "上海", null, null, List.of("外包"),
                "trial", false, "Java 后端 模板");

        AiDraftResult result = draftService.generate(legacy);
        assertEquals(30, result.config().expectedSalaryMin(), "旧请求回退画像下限");
        assertEquals(50, result.config().expectedSalaryMax(), "旧请求回退画像上限");
        assertEquals(30, result.config().rule().expectedSalaryMin());
        assertEquals(0, result.config().rule().expectedSalaryMax());
        assertEquals(List.of(), result.config().salary());
    }

    @Test
    void draftSalaryBoundsFollowRequestAndRuleKeepsLowerBoundOnly() {
        ResumeProfile profile = fullProfile(5, "本科", "软件工程", 2020,
                List.of("上海"), List.of("互联网/AI"));

        // 用户下限 60 > 简历推荐上限 50：请求值优先，不因画像上限报错/回退。
        AiDraftResult result = draftService.generate(new AiDraftRequest(profile, "Java 后端",
                List.of("Java"), null, 60, null, List.of(), "normal", false, "Java 后端 模板",
                List.of("上海"), List.of()));
        assertEquals(60, result.config().rule().expectedSalaryMin(), "规则层只写用户确认的下限");
        assertEquals(0, result.config().rule().expectedSalaryMax(), "规则层上限固定 0=未设置");
        assertEquals(60, result.config().expectedSalaryMin(), "A 区下限写真实值");
        assertNull(result.config().expectedSalaryMax(), "请求未传上限时 A 区上限留空（沿用当前值）");

        // min > max：非法区间必须报 DRAFT_INVALID（新契约，不允许再静默忽略 max）。
        assertDraftInvalid(new AiDraftRequest(profile, "Java", List.of("Java"), null,
                60, 20, List.of(), "normal", false, "x", List.of("上海"), List.of()));
    }

    @Test
    void draftSupportsLegacyCityCodeOnlyRequest() {
        ResumeProfile profile = fullProfile(5, "本科", "软件工程", 2020,
                List.of("上海"), List.of("互联网/AI"));
        AiDraftRequest legacy = new AiDraftRequest(profile, "Java 后端",
                List.of("Java"), "北京", 30, 50, List.of("外包"),
                "trial", false, "Java 后端 模板");

        AiDraftResult result = draftService.generate(legacy);
        assertEquals("北京", result.config().cityCode(), "旧 cityCode 必须兼容为单城市");
        assertTrue(result.config().rule().cities().isEmpty(),
                "旧 cityCode 只用于搜索城市，不再写可接受城市规则");
        assertNull(result.config().industry(), "未传行业时不设置行业条件（null=沿用模板原值）");
    }

    @Test
    void draftCitiesTakePriorityOverLegacyCityCode() {
        ResumeProfile profile = fullProfile(5, "本科", "软件工程", 2020,
                List.of("上海"), List.of("互联网/AI"));
        AiDraftRequest request = new AiDraftRequest(profile, "Java 后端",
                List.of("Java"), "北京", 30, 50, List.of("外包"),
                "trial", false, "Java 后端 模板",
                List.of("上海", "杭州"), List.of("互联网/AI"));

        AiDraftResult result = draftService.generate(request);
        assertEquals("上海", result.config().cityCode(), "新 cities 优先于旧 cityCode");
        assertTrue(result.config().rule().cities().isEmpty(), "可接受城市规则置空");
        assertEquals(List.of("互联网/AI"), result.config().industry());
    }

    @Test
    void draftRejectsTooManyCitiesOrIndustries() {
        ResumeProfile profile = fullProfile(5, "本科", "软件工程", 2020,
                List.of("上海"), List.of("互联网/AI"));

        assertDraftInvalid(new AiDraftRequest(profile, "Java", List.of("Java"), null,
                30, 50, List.of(), "trial", false, "x",
                List.of("上海", "杭州", "北京", "深圳", "广州"), List.of()));
        assertDraftInvalid(new AiDraftRequest(profile, "Java", List.of("Java"), null,
                30, 50, List.of(), "trial", false, "x",
                List.of("上海"),
                List.of("互联网/AI", "企业服务", "人工智能", "电商", "金融", "教育", "医疗")));

        AiDraftResult maxAllowed = draftService.generate(new AiDraftRequest(profile, "Java",
                List.of("Java"), null, 30, 50, List.of(), "trial", false, "x",
                List.of("上海", "杭州", "北京", "深圳"),
                List.of("互联网/AI", "企业服务", "人工智能", "电商", "金融", "教育")));
        assertEquals("上海", maxAllowed.config().cityCode());
        assertTrue(maxAllowed.config().rule().cities().isEmpty(), "可接受城市规则始终置空");
        assertEquals(6, maxAllowed.config().industry().size());
    }

    @Test
    void draftExperienceBucketsFollowBossOptions() {
        int[][] cases = {{0, 1}, {1, 1}, {2, 1}, {3, 3}, {4, 3}, {5, 5}, {9, 5}, {10, 10}};
        String[] expected = {"1年以下", "1-3年", "1-3年", "3-5年", "3-5年",
                "5-10年", "5-10年", "10年以上"};
        for (int index = 0; index < cases.length; index++) {
            ResumeProfile profile = fullProfile(cases[index][0], "本科", "软件工程", 2020,
                    List.of("上海"), List.of("互联网/AI"));
            AiDraftResult result = draftService.generate(new AiDraftRequest(profile, "Java",
                    List.of("Java"), null, 30, 50, List.of(), "normal", false, "x",
                    List.of("上海"), List.of()));
            assertEquals(List.of(expected[index]), result.config().experience(),
                    cases[index][0] + " 年经验的 BOSS 经验档");
        }

        ResumeProfile negative = fullProfile(-1, "本科", "软件工程", 2020,
                List.of("上海"), List.of());
        assertDraftInvalid(new AiDraftRequest(negative, "Java", List.of("Java"), null,
                30, 50, List.of(), "normal", false, "x", List.of("上海"), List.of()));
    }

    // ==================== 认证 / CSRF ====================

    @Test
    void onboardingWriteEndpointsRequireSessionAndCsrf() throws Exception {
        LocalAuthService auth = mock(LocalAuthService.class);
        when(auth.isAuthEnabled()).thenReturn(true);
        when(auth.isAuthenticated(any())).thenReturn(false);
        when(auth.isRequireCsrfHeader()).thenReturn(true);
        AuthInterceptor interceptor = new AuthInterceptor(auth);

        for (String path : List.of("/api/boss/resume/analyze", "/api/boss/resume/analyze-text",
                "/api/boss/templates/ai-draft")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request, response, new Object()),
                    "未登录的 onboarding 写接口必须被拦截：" + path);
            assertEquals(401, response.getStatus(), path);
            assertTrue(response.getContentAsString(StandardCharsets.UTF_8)
                    .contains("UNAUTHORIZED"), path);
        }

        when(auth.isAuthenticated(any())).thenReturn(true);
        for (String path : List.of("/api/boss/resume/analyze", "/api/boss/resume/analyze-text",
                "/api/boss/templates/ai-draft")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request, response, new Object()),
                    "已登录但缺 CSRF 头的 onboarding 写接口必须被拦截：" + path);
            assertEquals(403, response.getStatus(), path);
            assertTrue(response.getContentAsString(StandardCharsets.UTF_8)
                    .contains("CSRF_HEADER_MISSING"), path);
        }

        MockHttpServletRequest allowed = new MockHttpServletRequest("POST", "/api/boss/templates/ai-draft");
        allowed.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertTrue(interceptor.preHandle(allowed, new MockHttpServletResponse(), new Object()),
                "登录 + CSRF 头齐全时应放行到 controller");

        String securityConfig = Files.readString(Path.of("src", "main", "java",
                "com/offerchaser/application/security/SecurityWebConfig.java"));
        assertTrue(securityConfig.contains("addPathPatterns(\"/api/**\")"),
                "认证拦截器必须覆盖 /api/**（onboarding 写接口因此受保护）");
        assertFalse(securityConfig.contains("excludePathPatterns"),
                "不得为 onboarding 新接口开认证白名单");
    }

    // ==================== 异常出口 ====================

    @Test
    void exceptionHandlerMapsStableCodesAndStatuses() {
        OnboardingExceptionHandler handler = new OnboardingExceptionHandler();

        assertEquals(400, handler.handleOnboarding(OnboardingException.aiConfigMissing(null))
                .getStatusCode().value());
        assertEquals(OnboardingException.AI_CONFIG_MISSING,
                handler.handleOnboarding(OnboardingException.aiConfigMissing(null)).getBody().get("code"));

        assertEquals(400, handler.handleOnboarding(OnboardingException.invalidImage("bad"))
                .getStatusCode().value());
        assertEquals(OnboardingException.INVALID_IMAGE,
                handler.handleOnboarding(OnboardingException.invalidImage("bad")).getBody().get("code"));

        assertEquals(422, handler.handleOnboarding(OnboardingException.visionParseFailed("bad"))
                .getStatusCode().value());
        assertEquals(OnboardingException.VISION_PARSE_FAILED,
                handler.handleOnboarding(OnboardingException.visionParseFailed("bad")).getBody().get("code"));

        assertEquals(422, handler.handleOnboarding(OnboardingException.analyzeInvalid("bad"))
                .getStatusCode().value());
        assertEquals(OnboardingException.RESUME_ANALYZE_FAILED,
                handler.handleOnboarding(OnboardingException.analyzeInvalid("bad")).getBody().get("code"));

        assertEquals(502, handler.handleOnboarding(OnboardingException.analyzeUpstreamFailed("bad"))
                .getStatusCode().value());

        assertEquals(400, handler.handleOnboarding(OnboardingException.draftInvalid("bad"))
                .getStatusCode().value());
        assertEquals(OnboardingException.DRAFT_INVALID,
                handler.handleOnboarding(OnboardingException.draftInvalid("bad")).getBody().get("code"));

        var tooLarge = handler.handleOnboarding(
                new OnboardingException(OnboardingException.INVALID_IMAGE,
                        org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 10MB"));
        assertEquals(413, tooLarge.getStatusCode().value());
        assertEquals(Boolean.FALSE, tooLarge.getBody().get("success"));

        String source = readOnboardingExceptionSource();
        for (String code : List.of("AI_CONFIG_MISSING", "INVALID_IMAGE", "INVALID_TEXT",
                "VISION_PARSE_FAILED", "RESUME_ANALYZE_FAILED", "DRAFT_INVALID")) {
            assertTrue(source.contains(code), "错误码常量缺失：" + code);
        }
    }

    // ==================== 工具 ====================

    private static void assertPolicy(DeliveryPolicy policy, int perKeyword, int duration, int window) {
        assertNotNull(policy, "草稿必须带 deliveryPolicy");
        assertEquals(perKeyword, policy.collectMaxPerKeyword(), "每关键词采集上限");
        assertEquals(duration, policy.collectMaxDurationMinutes(), "单关键词采集时长");
        assertEquals(window, policy.collectNoNewWindowSeconds(), "无新增判定窗口");
    }

    private static void assertDraftInvalid(AiDraftRequest request) {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> new TemplateDraftService().generate(request));
        assertEquals(OnboardingException.DRAFT_INVALID, error.code(), error.getMessage());
        assertEquals(400, error.status().value());
    }

    private static ResumeProfile fullProfile(int years, String degree, String major, int graduationYear,
                                             List<String> cities, List<String> industries) {
        return new ResumeProfile(years, List.of("Java 后端"), List.of("Java", "Spring"),
                degree, major, graduationYear, cities, 30, 50, industries,
                List.of("高并发"), List.of(), Map.of("yearsExperience", years + " 年"), 0.9);
    }

    private static String direction() {
        return "Java 后端";
    }

    private static AiDraftRequest draftRequest(String intensity) {
        return new AiDraftRequest(profile(), "Java 后端",
                List.of("Java", "后端"), "上海", 30, 50, List.of("外包"),
                intensity, true, "Java 后端 模板");
    }

    private static ResumeProfile profile() {
        return new ResumeProfile(5, List.of("Java 后端"), List.of("Java", "Spring"),
                "本科", List.of("上海"), 30, 50, List.of("互联网"),
                List.of("高并发"), List.of(), Map.of("yearsExperience", "5 年"), 0.9);
    }

    private static String draftJson(String intensity) {
        return draftJsonBody(intensity, null, 30, 50);
    }

    private static String draftJsonWithSalary() {
        return draftJsonBody("normal", List.of("10-20K", "20-30K"), 12, 28);
    }

    private static String draftJsonBody(String intensity, List<String> salaryBrackets,
                                        int expectedSalaryMin, int expectedSalaryMax) {
        try {
            Map<String, Object> profile = new java.util.LinkedHashMap<>();
            profile.put("yearsExperience", 5);
            profile.put("roles", List.of("Java 后端"));
            profile.put("skills", List.of("Java", "Spring"));
            profile.put("highestDegree", "本科");
            profile.put("major", "软件工程");
            profile.put("graduationYear", 2020);
            profile.put("cities", List.of("上海", "杭州"));
            profile.put("industries", List.of("互联网/AI"));
            profile.put("expectedSalaryMin", 30);
            profile.put("expectedSalaryMax", 50);
            profile.put("strengths", List.of("高并发"));
            profile.put("warnings", List.of());
            profile.put("evidence", Map.of("yearsExperience", "5 年"));
            profile.put("confidence", 0.9);

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("profile", profile);
            body.put("direction", "Java 后端");
            body.put("keywords", List.of("Java", "后端"));
            body.put("cities", List.of("上海", "杭州"));
            body.put("industries", List.of("互联网/AI", "企业服务"));
            body.put("cityCode", "上海");
            body.put("expectedSalaryMin", expectedSalaryMin);
            body.put("expectedSalaryMax", expectedSalaryMax);
            if (salaryBrackets != null) {
                body.put("salary", salaryBrackets);
            }
            body.put("exclusions", List.of("外包"));
            body.put("intensity", intensity);
            body.put("sendImgResume", true);
            body.put("templateName", "Java 后端 模板");
            return new ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AiCallResult aiResult(String content) {
        return new AiCallResult(content, "e2e-model", 10, 20, 12, true);
    }

    private static byte[] samplePng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.DARK_GRAY);
            graphics.drawString("E2E Resume", 10, 20);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String readOnboardingExceptionSource() {
        try {
            return Files.readString(ONBOARDING_DIR.resolve("OnboardingException.java"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String PROFILE_JSON = """
            {"yearsExperience":5,"roles":["Java 后端"],"skills":["Java","Spring"],
             "highestDegree":"本科","major":"软件工程","graduationYear":2020,
             "cities":["上海"],"expectedSalaryMin":30,
             "expectedSalaryMax":50,"industries":["互联网/AI"],"strengths":["高并发"],
             "warnings":[],"evidence":{"yearsExperience":"5 年"},"confidence":0.9}
            """;

    private static final String SUGGESTIONS_JSON = """
            {"directions":[{"title":"Java 后端","fit":0.86,"reason":"技术栈匹配",
              "risks":["需要补分布式经验"],"keywords":["Java","后端","Spring"],
              "industries":["互联网/AI","企业服务"]}],
             "salaryBands":[{"label":"稳妥区间","min":25,"max":35,"basis":"同岗位市场区间","confidence":0.8}]}
            """;
}
