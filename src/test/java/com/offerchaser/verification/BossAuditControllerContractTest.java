package com.offerchaser.verification;

import com.offerchaser.application.controller.BossAuditController;
import com.offerchaser.application.persistence.AlreadyConfirmedException;
import com.offerchaser.application.persistence.AttemptNotFoundException;
import com.offerchaser.application.persistence.AttemptResolutionService;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.InvalidResolutionException;
import com.offerchaser.application.persistence.PageResult;
import com.offerchaser.application.persistence.VersionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1 契约验收：{@link BossAuditController}（/api/boss/runs、evaluations、attempts、resolve）。
 * <p>
 * 直接用 Mockito mock {@link BossQueryRepository}/{@link AttemptResolutionService} 调 controller，
 * 不启动 Spring 容器；锁定统一响应外壳、404/400/409 错误码与成功字段，防止前后端契约漂移。
 */
class BossAuditControllerContractTest {

    private BossQueryRepository queryRepository;
    private AttemptResolutionService resolutionService;
    private BossAuditController controller;

    @BeforeEach
    void setUp() {
        queryRepository = mock(BossQueryRepository.class);
        resolutionService = mock(AttemptResolutionService.class);
        controller = new BossAuditController(queryRepository, resolutionService);
    }

    // ==================== 映射契约 ====================

    @Test
    void controllerMappingsMatchB1Contract() throws Exception {
        assertTrue(BossAuditController.class.isAnnotationPresent(RestController.class));
        RequestMapping classMapping = BossAuditController.class.getAnnotation(RequestMapping.class);
        assertNotNull(classMapping, "BossAuditController 缺少 @RequestMapping");
        assertEquals("/api/boss", classMapping.value()[0], "BossAuditController 必须挂在 /api/boss 下");

        assertEquals("/runs", getMapping("listRuns", String.class, String.class, String.class,
                int.class, int.class));
        assertEquals("/evaluations", getMapping("listEvaluations", String.class, String.class,
                String.class, String.class, String.class, String.class, String.class, String.class,
                boolean.class, Integer.class, int.class, int.class));
        assertEquals("/attempts", getMapping("listAttempts", String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, int.class, int.class));
        assertEquals("/attempts/{attemptId}", getMapping("getAttemptDetail", String.class));

        Method resolve = BossAuditController.class.getMethod("resolveAttempt", String.class, Map.class);
        PostMapping post = resolve.getAnnotation(PostMapping.class);
        assertNotNull(post, "resolveAttempt 必须是 POST");
        assertEquals("/attempts/{attemptId}/resolve", post.value()[0]);
    }

    // ==================== 分页外壳 ====================

    @Test
    void listEndpointsReturnSuccessDataPageEnvelope() {
        PageResult<Map<String, Object>> runs =
                new PageResult<>(List.of(item("runId", "r1")), 2, 10, 35);
        when(queryRepository.listRuns(isNull(), isNull(), isNull(), eq(2), eq(10))).thenReturn(runs);

        ResponseEntity<Map<String, Object>> response = controller.listRuns(null, null, null, 2, 10);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(Boolean.TRUE, body.get("success"));
        assertSame(runs, body.get("data"), "data 应直接是 PageResult（items/page/size/total）");
        @SuppressWarnings("unchecked")
        PageResult<Map<String, Object>> data = (PageResult<Map<String, Object>>) body.get("data");
        assertEquals(1, data.items().size());
        assertEquals(2, data.page());
        assertEquals(10, data.size());
        assertEquals(35L, data.total());
    }

    @Test
    void listEndpointsPassAllFiltersThrough() {
        when(queryRepository.listEvaluations(eq("run-1"), eq("PASS"), eq("MATCH"), eq("PASS"),
                eq("ENC-1"), eq("Java"), eq("2026-11-01 00:00:00"), eq("2026-11-02 00:00:00"),
                eq(false), isNull(), eq(1), eq(20)))
                .thenReturn(new PageResult<>(List.of(), 1, 20, 0));
        when(queryRepository.listAttempts(eq("run-1"), eq("FIRST_CONTACT"), eq("UNKNOWN"),
                eq("SKIPPED"), eq("ENC-1"), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(new PageResult<>(List.of(), 1, 20, 0));

        ResponseEntity<Map<String, Object>> evaluations = controller.listEvaluations(
                "run-1", "PASS", "MATCH", "PASS", "ENC-1", "Java",
                "2026-11-01 00:00:00", "2026-11-02 00:00:00", false, null, 1, 20);
        ResponseEntity<Map<String, Object>> attempts = controller.listAttempts(
                "run-1", "FIRST_CONTACT", "UNKNOWN", "SKIPPED", "ENC-1", null, null, 1, 20);

        assertEquals(200, evaluations.getStatusCode().value());
        assertEquals(200, attempts.getStatusCode().value());
        assertEquals(Boolean.TRUE, evaluations.getBody().get("success"));
        assertEquals(Boolean.TRUE, attempts.getBody().get("success"));
    }

    @Test
    void listEndpointWrapsRepositoryFailureAs500() {
        when(queryRepository.listRuns(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        ResponseEntity<Map<String, Object>> response = controller.listRuns(null, null, null, 1, 20);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("查询任务记录失败"));
    }

    /** B2：评估列表新增 latestOnly / ruleConfigVersion 可选筛选，必须原样透传给仓储。 */
    @Test
    void evaluationsPassLatestOnlyAndRuleConfigVersionThrough() {
        when(queryRepository.listEvaluations(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(true), eq(3), eq(1), eq(10)))
                .thenReturn(new PageResult<>(List.of(item("id", 1L)), 1, 10, 1));

        ResponseEntity<Map<String, Object>> response =
                controller.listEvaluations(null, null, null, null, null, null, null, null, true, 3, 1, 10);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        verify(queryRepository).listEvaluations(null, null, null, null, null, null, null, null,
                true, 3, 1, 10);
    }

    // ==================== 详情 ====================

    @Test
    void attemptDetailSuccessReturnsAttemptEventsAndEvaluation() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("attempt", item("attemptId", "A-1"));
        detail.put("events", List.of(item("eventType", "TEXT_UNKNOWN")));
        detail.put("evaluation", item("jobName", "Java 开发"));
        when(queryRepository.getAttemptDetail("A-1")).thenReturn(detail);

        ResponseEntity<Map<String, Object>> response = controller.getAttemptDetail("A-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        assertSame(detail, response.getBody().get("data"));
    }

    @Test
    void attemptDetailMissingReturns404() {
        when(queryRepository.getAttemptDetail("NOPE")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.getAttemptDetail("NOPE");

        assertEquals(404, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertEquals("ATTEMPT_NOT_FOUND", response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("NOPE"));
    }

    // ==================== 人工核对：错误映射 ====================

    @Test
    void resolveWithMissingBodyOrFieldsReturns400() {
        ResponseEntity<Map<String, Object>> noBody = controller.resolveAttempt("A-1", null);
        assertEquals(400, noBody.getStatusCode().value());
        assertEquals("INVALID_RESOLUTION", noBody.getBody().get("code"));

        Map<String, Object> missingReason = new LinkedHashMap<>();
        missingReason.put("resolution", "SENT");
        missingReason.put("expectedVersion", 1);
        ResponseEntity<Map<String, Object>> response = controller.resolveAttempt("A-1", missingReason);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("INVALID_RESOLUTION", response.getBody().get("code"));
        verify(resolutionService, never()).resolve(any(), any(), any(), anyInt(), any());
    }

    @Test
    void resolveAttemptNotFoundReturns404() {
        when(resolutionService.resolve(eq("NOPE"), any(), any(), anyInt(), any()))
                .thenThrow(new AttemptNotFoundException("NOPE"));

        ResponseEntity<Map<String, Object>> response =
                controller.resolveAttempt("NOPE", resolveBody("SENT", 1));

        assertEquals(404, response.getStatusCode().value());
        assertEquals("ATTEMPT_NOT_FOUND", response.getBody().get("code"));
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
    }

    @Test
    void resolveVersionConflictReturns409WithCurrentFacts() {
        when(resolutionService.resolve(eq("A-1"), any(), any(), anyInt(), any()))
                .thenThrow(new VersionConflictException("A-1", 7, "UNKNOWN"));

        ResponseEntity<Map<String, Object>> response =
                controller.resolveAttempt("A-1", resolveBody("SENT", 3));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("VERSION_CONFLICT", response.getBody().get("code"));
        assertEquals(7, response.getBody().get("currentVersion"));
        assertEquals("UNKNOWN", response.getBody().get("currentStatus"));
    }

    @Test
    void resolveAlreadyConfirmedReturns409() {
        when(resolutionService.resolve(eq("A-1"), any(), any(), anyInt(), any()))
                .thenThrow(new AlreadyConfirmedException("A-1"));

        ResponseEntity<Map<String, Object>> response =
                controller.resolveAttempt("A-1", resolveBody("NOT_SENT", 1));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("ALREADY_CONFIRMED", response.getBody().get("code"));
    }

    @Test
    void resolveInvalidResolutionReturns400() {
        when(resolutionService.resolve(eq("A-1"), any(), any(), anyInt(), any()))
                .thenThrow(new InvalidResolutionException("reason 必填"));

        ResponseEntity<Map<String, Object>> response =
                controller.resolveAttempt("A-1", resolveBody("SENT", 1));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("INVALID_RESOLUTION", response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("reason"));
    }

    // ==================== 人工核对：成功与参数透传 ====================

    @Test
    void resolveSuccessReturnsCompleteDataAndPassesArguments() {
        when(resolutionService.resolve("A-1", "SENT", "电话确认已发送", 3, "key-1"))
                .thenReturn(new AttemptResolutionService.ResolutionResult(
                        "A-1", "CONFIRMED", "SKIPPED", 4, "SENT",
                        "2026-11-04 18:30:00", true, false));
        Map<String, Object> body = resolveBody("SENT", 3);
        body.put("reason", "电话确认已发送");
        body.put("idempotencyKey", "key-1");

        ResponseEntity<Map<String, Object>> response = controller.resolveAttempt("A-1", body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals("A-1", data.get("attemptId"));
        assertEquals("CONFIRMED", data.get("textStatus"));
        assertEquals("SKIPPED", data.get("imageStatus"));
        assertEquals(4, data.get("version"));
        assertEquals("SENT", data.get("manualResolution"));
        assertEquals("2026-11-04 18:30:00", data.get("resolvedAt"));
        assertEquals(Boolean.TRUE, data.get("changed"));
        assertEquals(Boolean.FALSE, data.get("idempotent"));
    }

    @Test
    void resolveIdempotentReplayFieldsAreReturned() {
        when(resolutionService.resolve(eq("A-1"), eq("SENT"), any(), eq(4), isNull()))
                .thenReturn(new AttemptResolutionService.ResolutionResult(
                        "A-1", "CONFIRMED", "NOT_ATTEMPTED", 4, "SENT",
                        "2026-11-04 18:30:00", false, true));

        ResponseEntity<Map<String, Object>> response =
                controller.resolveAttempt("A-1", resolveBody("SENT", 4));

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals(Boolean.FALSE, data.get("changed"));
        assertEquals(Boolean.TRUE, data.get("idempotent"));
        assertEquals(4, data.get("version"), "幂等重放不得增加版本");
    }

    @Test
    void resolveExpectedVersionFromStringIsParsed() {
        // 前端 JSON 里 expectedVersion 可能是字符串；controller 需兼容解析
        when(resolutionService.resolve(eq("A-1"), eq("STILL_UNKNOWN"), eq("已核对仍未知"), eq(5), isNull()))
                .thenReturn(new AttemptResolutionService.ResolutionResult(
                        "A-1", "UNKNOWN", "SKIPPED", 6, "STILL_UNKNOWN",
                        "2026-11-04 18:31:00", true, false));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolution", "STILL_UNKNOWN");
        body.put("reason", "已核对仍未知");
        body.put("expectedVersion", "5");

        ResponseEntity<Map<String, Object>> response = controller.resolveAttempt("A-1", body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
    }

    // ==================== 工具 ====================

    private static String getMapping(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = BossAuditController.class.getMethod(methodName, parameterTypes);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping, methodName + " 缺少 @GetMapping");
        return mapping.value()[0];
    }

    private static Map<String, Object> resolveBody(String resolution, int expectedVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolution", resolution);
        body.put("reason", "人工核对说明");
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private static Map<String, Object> item(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    @SuppressWarnings("unused")
    private static List<Map<String, Object>> emptyItems() {
        return new ArrayList<>();
    }
}
