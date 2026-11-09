package com.offerchaser.verification;

import com.offerchaser.application.controller.AiConfigController;
import com.offerchaser.application.controller.BossConfigController;
import com.offerchaser.application.controller.BossRulesController;
import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.entity.BossConfigEntity;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.rules.RulePayload;
import com.offerchaser.application.rules.RuleSetView;
import com.offerchaser.application.rules.RuleValidationException;
import com.offerchaser.application.rules.RuleVersionConflictException;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.application.templates.ProfileTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置中心整合：编辑页保存后自动写回生效模板的控制器契约（Mockito）：
 * BOSS 配置保存、图片简历上传、AI 配置保存、筛选规则保存成功后调用
 * {@code profileTemplateService.captureActiveFromCurrent()}；失败/校验不通过不调用。
 */
class ConfigCenterAutoSyncContractTest {

    private final ProfileTemplateService profileTemplateService = mock(ProfileTemplateService.class);

    // ==================== BOSS 配置 ====================

    @Test
    void bossConfigUpdateTriggersTemplateSync() {
        BossService bossService = mock(BossService.class);
        BossResumeImageService resumeService = mock(BossResumeImageService.class);
        BossConfigController controller = new BossConfigController(bossService, resumeService,
                profileTemplateService);

        BossConfigEntity config = new BossConfigEntity();
        config.setId(1L);
        config.setKeywords("Java,后端");
        when(bossService.updateConfig(any(BossConfigEntity.class))).thenReturn(config);

        controller.updateConfig(config);

        verify(profileTemplateService).captureActiveFromCurrent();
    }

    @Test
    void bossConfigUpdateFailureDoesNotSyncTemplate() {
        BossService bossService = mock(BossService.class);
        BossResumeImageService resumeService = mock(BossResumeImageService.class);
        BossConfigController controller = new BossConfigController(bossService, resumeService,
                profileTemplateService);

        BossConfigEntity config = new BossConfigEntity();
        config.setId(1L);
        config.setKeywords("Java");
        when(bossService.updateConfig(any(BossConfigEntity.class)))
                .thenThrow(new IllegalStateException("数据库不可用"));

        assertThrows(IllegalStateException.class, () -> controller.updateConfig(config));
        verify(profileTemplateService, never()).captureActiveFromCurrent();
    }

    @Test
    void resumeUploadTriggersTemplateSync() throws Exception {
        BossService bossService = mock(BossService.class);
        BossResumeImageService resumeService = mock(BossResumeImageService.class);
        BossConfigController controller = new BossConfigController(bossService, resumeService,
                profileTemplateService);
        MockMultipartFile file = new MockMultipartFile("file", "resume.png", "image/png",
                new byte[]{1, 2, 3});
        when(resumeService.save(file)).thenReturn(new BossResumeImageService.ResumeImageInfo(
                Path.of("/tmp/resume.png"), "resume.png", "image/png", 3, 1L));
        when(resumeService.getMetadata()).thenReturn(Map.of("fileName", "resume.png"));

        ResponseEntity<Map<String, Object>> response = controller.uploadResumeImage(file);

        assertEquals(200, response.getStatusCode().value());
        verify(profileTemplateService).captureActiveFromCurrent();
    }

    @Test
    void resumeUploadValidationFailureDoesNotSyncTemplate() throws Exception {
        BossService bossService = mock(BossService.class);
        BossResumeImageService resumeService = mock(BossResumeImageService.class);
        BossConfigController controller = new BossConfigController(bossService, resumeService,
                profileTemplateService);
        MockMultipartFile file = new MockMultipartFile("file", "resume.txt", "text/plain",
                new byte[]{1});
        when(resumeService.save(file)).thenThrow(new IllegalArgumentException("只支持图片简历"));

        ResponseEntity<Map<String, Object>> response = controller.uploadResumeImage(file);

        assertEquals(400, response.getStatusCode().value());
        verify(profileTemplateService, never()).captureActiveFromCurrent();
    }

    // ==================== AI 配置 ====================

    @Test
    void aiConfigSaveTriggersTemplateSync() {
        AiService aiService = mock(AiService.class);
        AiConfigController controller = new AiConfigController();
        ReflectionTestUtils.setField(controller, "aiService", aiService);
        ReflectionTestUtils.setField(controller, "profileTemplateService", profileTemplateService);
        AiEntity saved = new AiEntity();
        saved.setId(1L);
        saved.setIntroduce("介绍");
        saved.setPrompt("提示词");
        when(aiService.saveOrUpdateAiConfig("介绍", "提示词")).thenReturn(saved);

        ResponseEntity<Map<String, Object>> response = controller.saveAiConfig(
                Map.of("introduce", "介绍", "prompt", "提示词"));

        assertEquals(200, response.getStatusCode().value());
        verify(profileTemplateService).captureActiveFromCurrent();
    }

    @Test
    void aiConfigIncompleteRequestDoesNotSyncTemplate() {
        AiService aiService = mock(AiService.class);
        AiConfigController controller = new AiConfigController();
        ReflectionTestUtils.setField(controller, "aiService", aiService);
        ReflectionTestUtils.setField(controller, "profileTemplateService", profileTemplateService);

        ResponseEntity<Map<String, Object>> response = controller.saveAiConfig(
                Map.of("introduce", "只有介绍"));

        assertEquals(400, response.getStatusCode().value());
        verify(profileTemplateService, never()).captureActiveFromCurrent();
    }

    @Test
    void aiConfigSaveFailureDoesNotSyncTemplate() {
        AiService aiService = mock(AiService.class);
        AiConfigController controller = new AiConfigController();
        ReflectionTestUtils.setField(controller, "aiService", aiService);
        ReflectionTestUtils.setField(controller, "profileTemplateService", profileTemplateService);
        when(aiService.saveOrUpdateAiConfig(any(), any()))
                .thenThrow(new IllegalStateException("保存失败"));

        ResponseEntity<Map<String, Object>> response = controller.saveAiConfig(
                Map.of("introduce", "介绍", "prompt", "提示词"));

        assertEquals(500, response.getStatusCode().value());
        verify(profileTemplateService, never()).captureActiveFromCurrent();
    }

    // ==================== 筛选规则 ====================

    @Test
    void rulesSaveTriggersTemplateSync() {
        RuleConfigService ruleConfigService = mock(RuleConfigService.class);
        BossRulesController controller = new BossRulesController(ruleConfigService, profileTemplateService);
        when(ruleConfigService.save(any(RulePayload.class), anyInt(), any()))
                .thenReturn(new RuleSetView(2, "hash", "2026-11-06 10:00:00", "note",
                        new RulePayload("本科", "INTERSECT", 30, 50, List.of(), List.of(),
                                List.of(), "CONTAINS", List.of(), List.of(), List.of(),
                                List.of(), false, List.of())));

        ResponseEntity<Map<String, Object>> response = controller.saveRules(ruleBody(1));

        assertEquals(200, response.getStatusCode().value());
        verify(profileTemplateService).captureActiveFromCurrent();
    }

    @Test
    void rulesSaveMissingVersionDoesNotSyncTemplate() {
        RuleConfigService ruleConfigService = mock(RuleConfigService.class);
        BossRulesController controller = new BossRulesController(ruleConfigService, profileTemplateService);

        ResponseEntity<Map<String, Object>> response = controller.saveRules(Map.of("rules", Map.of()));

        assertEquals(400, response.getStatusCode().value());
        verify(profileTemplateService, never()).captureActiveFromCurrent();
    }

    @Test
    void rulesSaveConflictOrValidationFailureDoesNotSyncTemplate() {
        RuleConfigService ruleConfigService = mock(RuleConfigService.class);
        BossRulesController controller = new BossRulesController(ruleConfigService, profileTemplateService);
        when(ruleConfigService.save(any(RulePayload.class), anyInt(), any()))
                .thenThrow(new RuleVersionConflictException(3));

        ResponseEntity<Map<String, Object>> conflict = controller.saveRules(ruleBody(1));
        assertEquals(409, conflict.getStatusCode().value());
        verify(profileTemplateService, never()).captureActiveFromCurrent();

        when(ruleConfigService.save(any(RulePayload.class), anyInt(), any()))
                .thenThrow(new RuleValidationException("学历非法"));
        ResponseEntity<Map<String, Object>> invalid = controller.saveRules(ruleBody(1));
        assertEquals(400, invalid.getStatusCode().value());
        verify(profileTemplateService, never()).captureActiveFromCurrent();
    }

    @Test
    void rulesSaveUnexpectedFailureDoesNotSyncTemplate() {
        RuleConfigService ruleConfigService = mock(RuleConfigService.class);
        BossRulesController controller = new BossRulesController(ruleConfigService, profileTemplateService);
        when(ruleConfigService.save(any(RulePayload.class), anyInt(), any()))
                .thenThrow(new IllegalStateException("规则库不可用"));

        ResponseEntity<Map<String, Object>> response = controller.saveRules(ruleBody(1));

        assertEquals(500, response.getStatusCode().value());
        verify(profileTemplateService, never()).captureActiveFromCurrent();
    }

    private static Map<String, Object> ruleBody(int expectedVersion) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("highestDegree", "本科");
        rules.put("salaryStrategy", "INTERSECT");
        rules.put("expectedSalaryMin", 30);
        rules.put("expectedSalaryMax", 50);
        rules.put("jobDirections", List.of("产品"));
        rules.put("cities", List.of("上海"));
        rules.put("workTypes", List.of());
        rules.put("blacklistMatchMode", "CONTAINS");
        rules.put("blacklistJob", List.of());
        rules.put("blacklistCompany", List.of());
        rules.put("blacklistRecruiter", List.of());
        rules.put("whitelist", List.of());
        rules.put("filterDeadHr", false);
        rules.put("deadStatus", List.of());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", expectedVersion);
        body.put("note", "测试");
        body.put("rules", rules);
        return body;
    }
}
