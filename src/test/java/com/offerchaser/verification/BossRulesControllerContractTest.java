package com.offerchaser.verification;

import com.offerchaser.application.controller.BossRulesController;
import com.offerchaser.application.rules.PreviewResult;
import com.offerchaser.application.rules.PreviewSample;
import com.offerchaser.application.rules.RecheckResult;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.application.rules.RulePayload;
import com.offerchaser.application.rules.RuleSetView;
import com.offerchaser.application.rules.RuleValidationException;
import com.offerchaser.application.rules.RuleVersionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2 契约验收：{@link BossRulesController}（规则配置/版本/预览/重评）。
 * <p>
 * Mockito mock {@link RuleConfigService} 直接调 controller，不启动 Spring 容器；
 * 锁定统一响应外壳、400/409/500 错误码以及 expectedVersion/rules 等参数透传。
 */
class BossRulesControllerContractTest {

    private RuleConfigService ruleConfigService;
    private BossRulesController controller;

    @BeforeEach
    void setUp() {
        ruleConfigService = mock(RuleConfigService.class);
        controller = new BossRulesController(ruleConfigService, mock(ProfileTemplateService.class));
    }

    // ==================== 映射契约 ====================

    @Test
    void controllerMappingsMatchB2Contract() throws Exception {
        assertTrue(BossRulesController.class.isAnnotationPresent(RestController.class));
        RequestMapping classMapping = BossRulesController.class.getAnnotation(RequestMapping.class);
        assertNotNull(classMapping);
        assertEquals("/api/boss", classMapping.value()[0]);

        assertEquals("/rules", getMapping("getRules", int.class));
        assertEquals("/rules/versions", getMapping("listVersions", int.class));
        assertEquals("/evaluations/recheck", postMapping("recheck", Map.class));

        Method save = BossRulesController.class.getMethod("saveRules", Map.class);
        PutMapping put = save.getAnnotation(PutMapping.class);
        assertNotNull(put, "saveRules 必须是 PUT");
        assertEquals("/rules", put.value()[0]);

        Method preview = BossRulesController.class.getMethod("preview", Map.class);
        PostMapping post = preview.getAnnotation(PostMapping.class);
        assertNotNull(post, "preview 必须是 POST");
        assertEquals("/rules/preview", post.value()[0]);
    }

    // ==================== GET /rules ====================

    @Test
    void getRulesReturnsActiveLiveDefaultsAndVersions() {
        RuleSetView active = view(2, "hash-2", payload("本科"));
        RulePayload liveDefaults = payload("硕士");
        List<RuleSetView> versions = List.of(active, view(1, "hash-1", payload("本科")));
        when(ruleConfigService.getActive()).thenReturn(active);
        when(ruleConfigService.getLiveDefaults()).thenReturn(liveDefaults);
        when(ruleConfigService.listVersions(20)).thenReturn(versions);

        ResponseEntity<Map<String, Object>> response = controller.getRules(20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertSame(active, data.get("active"));
        assertSame(liveDefaults, data.get("liveDefaults"));
        assertSame(versions, data.get("versions"));
    }

    @Test
    void getRulesWrapsServiceFailureAs500() {
        when(ruleConfigService.getActive()).thenThrow(new IllegalStateException("db down"));
        ResponseEntity<Map<String, Object>> response = controller.getRules(20);
        assertEquals(500, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("读取规则配置失败"));
    }

    @Test
    void listVersionsReturnsServiceResult() {
        List<RuleSetView> versions = List.of(view(3, "h3", payload("本科")));
        when(ruleConfigService.listVersions(5)).thenReturn(versions);

        ResponseEntity<Map<String, Object>> response = controller.listVersions(5);

        assertEquals(200, response.getStatusCode().value());
        assertSame(versions, response.getBody().get("data"));
    }

    // ==================== PUT /rules ====================

    @Test
    void saveRulesSuccessReturnsActiveView() {
        RuleSetView saved = view(2, "hash-2", payload("硕士"));
        when(ruleConfigService.save(any(RulePayload.class), eq(1), eq("首次调整")))
                .thenReturn(saved);

        ResponseEntity<Map<String, Object>> response =
                controller.saveRules(saveBody(1, "首次调整", payloadMap("硕士")));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        assertSame(saved, response.getBody().get("data"));
        verify(ruleConfigService).save(any(RulePayload.class), eq(1), eq("首次调整"));
    }

    @Test
    void saveRulesMissingFieldsReturns400() {
        assertEquals(400, controller.saveRules(null).getStatusCode().value());

        Map<String, Object> noVersion = new LinkedHashMap<>();
        noVersion.put("rules", payloadMap("本科"));
        ResponseEntity<Map<String, Object>> missingVersion = controller.saveRules(noVersion);
        assertEquals(400, missingVersion.getStatusCode().value());
        assertEquals("RULE_VALIDATION_FAILED", missingVersion.getBody().get("code"));

        Map<String, Object> noRules = new LinkedHashMap<>();
        noRules.put("expectedVersion", 1);
        ResponseEntity<Map<String, Object>> missingRules = controller.saveRules(noRules);
        assertEquals(400, missingRules.getStatusCode().value());
        assertEquals("RULE_VALIDATION_FAILED", missingRules.getBody().get("code"));
        verify(ruleConfigService, never()).save(any(), anyInt(), any());
    }

    @Test
    void saveRulesValidationFailureReturns400() {
        when(ruleConfigService.save(any(RulePayload.class), anyInt(), any()))
                .thenThrow(new RuleValidationException("highestDegree 非法"));

        ResponseEntity<Map<String, Object>> response =
                controller.saveRules(saveBody(1, null, payloadMap("本科")));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("RULE_VALIDATION_FAILED", response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("highestDegree"));
    }

    @Test
    void saveRulesVersionConflictReturns409WithCurrentVersion() {
        when(ruleConfigService.save(any(RulePayload.class), eq(1), any()))
                .thenThrow(new RuleVersionConflictException(4));

        ResponseEntity<Map<String, Object>> response =
                controller.saveRules(saveBody(1, null, payloadMap("本科")));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("RULE_VERSION_CONFLICT", response.getBody().get("code"));
        assertEquals(4, response.getBody().get("currentVersion"));
    }

    @Test
    void saveRulesUnexpectedFailureReturns500() {
        when(ruleConfigService.save(any(RulePayload.class), anyInt(), any()))
                .thenThrow(new IllegalStateException("写入失败"));

        ResponseEntity<Map<String, Object>> response =
                controller.saveRules(saveBody(1, null, payloadMap("本科")));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("保存规则失败"));
    }

    @Test
    void saveRulesParsesExpectedVersionFromString() {
        when(ruleConfigService.save(any(RulePayload.class), eq(3), isNull()))
                .thenReturn(view(4, "h4", payload("本科")));
        Map<String, Object> body = saveBody(3, null, payloadMap("本科"));
        body.put("expectedVersion", "3");

        ResponseEntity<Map<String, Object>> response = controller.saveRules(body);

        assertEquals(200, response.getStatusCode().value());
        verify(ruleConfigService).save(any(RulePayload.class), eq(3), isNull());
    }

    // ==================== GET /rules/versions ====================

    @Test
    void listVersionsMapsServiceFailureTo500() {
        when(ruleConfigService.listVersions(anyInt())).thenThrow(new IllegalStateException("bad"));
        ResponseEntity<Map<String, Object>> response = controller.listVersions(20);
        assertEquals(500, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("读取规则版本历史失败"));
    }

    // ==================== POST /rules/preview ====================

    @Test
    void previewSuccessReturnsResultsAndSupportsOptionalPayload() {
        List<PreviewResult> results = List.of(
                new PreviewResult(0, "Java 开发", "PASS", "PASS", "", "通过"),
                new PreviewResult(1, "销售", "REJECT", "JOB_BLACKLIST", "销售", "命中黑名单"));
        when(ruleConfigService.preview(isNull(), anyList())).thenReturn(results);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("samples", List.of(sampleMap("Java 开发")));

        ResponseEntity<Map<String, Object>> response = controller.preview(body);

        assertEquals(200, response.getStatusCode().value());
        assertSame(results, response.getBody().get("data"));
        verify(ruleConfigService).preview(isNull(), anyList());
    }

    @Test
    void previewAcceptsInlineRulesPayload() {
        when(ruleConfigService.preview(any(RulePayload.class), anyList()))
                .thenReturn(List.of(new PreviewResult(0, "Java", "PASS", "PASS", "", "")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rules", payloadMap("硕士"));
        body.put("samples", List.of(sampleMap("Java")));

        assertEquals(200, controller.preview(body).getStatusCode().value());
        verify(ruleConfigService).preview(any(RulePayload.class), anyList());
    }

    @Test
    void previewMissingSamplesOrBadFormatReturns400() {
        assertEquals(400, controller.preview(null).getStatusCode().value());

        Map<String, Object> noSamples = new LinkedHashMap<>();
        noSamples.put("rules", payloadMap("本科"));
        ResponseEntity<Map<String, Object>> response = controller.preview(noSamples);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("RULE_VALIDATION_FAILED", response.getBody().get("code"));

        Map<String, Object> badSamples = new LinkedHashMap<>();
        badSamples.put("samples", "not-an-array");
        assertEquals(400, controller.preview(badSamples).getStatusCode().value());
    }

    @Test
    void previewServiceValidationFailureReturns400() {
        when(ruleConfigService.preview(isNull(), anyList()))
                .thenThrow(new RuleValidationException("samples 至少要提供一个样例"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("samples", List.of(sampleMap("Java")));

        ResponseEntity<Map<String, Object>> response = controller.preview(body);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("RULE_VALIDATION_FAILED", response.getBody().get("code"));
    }

    // ==================== POST /evaluations/recheck ====================

    @Test
    void recheckPassesParsedParametersAndReturnsResult() {
        when(ruleConfigService.recheck(2, "run-1", List.of("ENC-1", "ENC-2"), 30))
                .thenReturn(new RecheckResult(2, 2, 1, 1, 0));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleVersion", 2);
        body.put("runId", "run-1");
        body.put("encryptIds", List.of("ENC-1", "ENC-2"));
        body.put("limit", 30);

        ResponseEntity<Map<String, Object>> response = controller.recheck(body);

        assertEquals(200, response.getStatusCode().value());
        RecheckResult data = (RecheckResult) response.getBody().get("data");
        assertEquals(2, data.ruleVersion());
        assertEquals(2, data.evaluated());
        assertEquals(1, data.passed());
        assertEquals(1, data.rejected());
        assertEquals(0, data.reviewed());
    }

    @Test
    void recheckWithEmptyBodyUsesDefaults() {
        when(ruleConfigService.recheck(isNull(), isNull(), eq(List.of()), eq(50)))
                .thenReturn(new RecheckResult(1, 0, 0, 0, 0));

        ResponseEntity<Map<String, Object>> response = controller.recheck(null);

        assertEquals(200, response.getStatusCode().value());
        verify(ruleConfigService).recheck(isNull(), isNull(), eq(List.of()), eq(50));
    }

    @Test
    void recheckValidationFailureReturns400() {
        when(ruleConfigService.recheck(any(), any(), anyList(), anyInt()))
                .thenThrow(new RuleValidationException("规则版本不存在: v99"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleVersion", 99);

        ResponseEntity<Map<String, Object>> response = controller.recheck(body);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("RULE_VALIDATION_FAILED", response.getBody().get("code"));
    }

    // ==================== 工具 ====================

    private static String getMapping(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = BossRulesController.class.getMethod(methodName, parameterTypes);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping, methodName + " 缺少 @GetMapping");
        return mapping.value()[0];
    }

    private static String postMapping(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = BossRulesController.class.getMethod(methodName, parameterTypes);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertNotNull(mapping, methodName + " 缺少 @PostMapping");
        return mapping.value()[0];
    }

    private static Map<String, Object> saveBody(int expectedVersion, String note, Map<String, Object> rules) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", expectedVersion);
        if (note != null) {
            body.put("note", note);
        }
        body.put("rules", rules);
        return body;
    }

    private static RuleSetView view(int version, String hash, RulePayload payload) {
        return new RuleSetView(version, hash, "2026-11-04 19:00:00", "note-v" + version, payload);
    }

    private static RulePayload payload(String degree) {
        return new RulePayload(degree, "INTERSECT", 20, 40,
                List.of(), List.of(), List.of(), "CONTAINS",
                List.of(), List.of(), List.of(), List.of(), false, List.of());
    }

    private static Map<String, Object> payloadMap(String degree) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("highestDegree", degree);
        map.put("salaryStrategy", "INTERSECT");
        map.put("expectedSalaryMin", 20);
        map.put("expectedSalaryMax", 40);
        map.put("blacklistMatchMode", "CONTAINS");
        map.put("filterDeadHr", false);
        return map;
    }

    private static Map<String, Object> sampleMap(String jobName) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("jobName", jobName);
        sample.put("companyName", "示例科技");
        sample.put("salary", "20-30K");
        sample.put("degree", "本科");
        sample.put("jd", "负责后端服务开发，熟悉 Java 与 Spring。");
        return sample;
    }

    @SuppressWarnings("unused")
    private static List<PreviewSample> unused() {
        return new ArrayList<>();
    }
}
