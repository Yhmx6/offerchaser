package com.offerchaser.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.controller.BossController;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.service.CookieService;
import com.offerchaser.worker.manager.PlaywrightManager;
import com.offerchaser.worker.service.BossJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1 契约验收：{@link BossController} 的任务操作（runId 绑定、409/400 映射）与 SSE 连接快照。
 * <p>
 * 不启动 Spring 容器：直接 new controller + mock {@link BossJobService}；SSE 快照通过读取
 * {@link SseEmitter} 在挂载 Handler 前的 earlySendAttempts 来断言事件内容（不依赖网络）。
 */
class BossControllerTaskOpsContractTest {

    private BossJobService bossJobService;
    private PlaywrightManager playwrightManager;
    private CookieService cookieService;
    private BudgetService budgetService;
    private DeliveryLedgerService deliveryLedgerService;
    private EvaluationRepository evaluationRepository;
    private BossController controller;

    @BeforeEach
    void setUp() {
        bossJobService = mock(BossJobService.class);
        playwrightManager = mock(PlaywrightManager.class);
        cookieService = mock(CookieService.class);
        budgetService = mock(BudgetService.class);
        deliveryLedgerService = mock(DeliveryLedgerService.class);
        evaluationRepository = mock(EvaluationRepository.class);
        controller = new BossController(bossJobService, playwrightManager, cookieService,
                budgetService, deliveryLedgerService, evaluationRepository);
    }

    // ==================== 映射契约 ====================

    @Test
    void taskOperationMappingsMatchB1Contract() throws Exception {
        RequestMapping classMapping = BossController.class.getAnnotation(RequestMapping.class);
        assertNotNull(classMapping);
        assertEquals("/api/boss", classMapping.value()[0]);

        assertEquals("/stop", postMapping("stopBoss", String.class));
        assertEquals("/verification/resume", postMapping("resumeVerification", String.class, Map.class));
        assertEquals("/force-reset", postMapping("forceReset", boolean.class, String.class));
        assertEquals("/start", postMapping("startBoss"));
        assertEquals("/stream", getMapping("streamBossProgress"));
        assertEquals("/status", getMapping("getBossStatus"));
        assertEquals("/delivery/summary", getMapping("getDeliverySummary"));
    }

    // ==================== stop：runId 透传与状态码 ====================

    @Test
    void stopPassesRunIdAndReturns200() {
        when(bossJobService.requestStop("run-1")).thenReturn(result(true, null));
        ResponseEntity<Map<String, Object>> response = controller.stopBoss("run-1");
        verify(bossJobService).requestStop("run-1");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
    }

    @Test
    void stopWithoutRunIdStillPassesNullThrough() {
        when(bossJobService.requestStop(null)).thenReturn(result(true, null));
        controller.stopBoss(null);
        verify(bossJobService).requestStop(null);
    }

    @Test
    void stopRunIdMismatchMapsTo409() {
        when(bossJobService.requestStop("old-run")).thenReturn(result(false, "RUN_ID_MISMATCH"));
        ResponseEntity<Map<String, Object>> response = controller.stopBoss("old-run");
        assertEquals(409, response.getStatusCode().value());
        assertEquals("RUN_ID_MISMATCH", response.getBody().get("code"));
    }

    @Test
    void stopNotRunningMapsTo400() {
        when(bossJobService.requestStop("run-1")).thenReturn(result(false, "NOT_RUNNING"));
        ResponseEntity<Map<String, Object>> response = controller.stopBoss("run-1");
        assertEquals(400, response.getStatusCode().value());
        assertEquals("NOT_RUNNING", response.getBody().get("code"));
    }

    // ==================== verification resume：query/body runId 与状态码 ====================

    @Test
    void resumeVerificationPassesQueryRunIdAndReturns200() {
        when(bossJobService.requestResumeVerification("run-2")).thenReturn(result(true, null));
        ResponseEntity<Map<String, Object>> response = controller.resumeVerification("run-2", null);
        verify(bossJobService).requestResumeVerification("run-2");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
    }

    @Test
    void resumeVerificationFallsBackToBodyRunId() {
        when(bossJobService.requestResumeVerification("run-body")).thenReturn(result(true, null));
        controller.resumeVerification(null, Map.of("runId", "run-body"));
        verify(bossJobService).requestResumeVerification("run-body");
    }

    @Test
    void resumeVerificationMismatchMapsTo409AndNotPausedTo400() {
        when(bossJobService.requestResumeVerification("old"))
                .thenReturn(result(false, "RUN_ID_MISMATCH"));
        when(bossJobService.requestResumeVerification("current"))
                .thenReturn(result(false, "NOT_PAUSED"));

        assertEquals(409, controller.resumeVerification("old", null).getStatusCode().value());
        ResponseEntity<Map<String, Object>> notPaused = controller.resumeVerification("current", null);
        assertEquals(400, notPaused.getStatusCode().value());
        assertEquals("NOT_PAUSED", notPaused.getBody().get("code"));
    }

    // ==================== force-reset：confirm 与 runId 绑定 ====================

    @Test
    void forceResetWithoutConfirmReturns400AndNeverCallsService() {
        ResponseEntity<Map<String, Object>> response = controller.forceReset(false, "run-3");
        assertEquals(400, response.getStatusCode().value());
        assertEquals("confirmation_required", response.getBody().get("status"));
        verify(bossJobService, never()).requestForceReset(any());
    }

    @Test
    void forceResetPassesRunIdAndMapsMismatchTo409() {
        when(bossJobService.requestForceReset("run-3"))
                .thenReturn(Map.of("success", true, "status", "FAILED"));
        when(bossJobService.requestForceReset("old"))
                .thenReturn(Map.of("success", false, "code", "RUN_ID_MISMATCH"));

        assertEquals(200, controller.forceReset(true, "run-3").getStatusCode().value());
        assertEquals(409, controller.forceReset(true, "old").getStatusCode().value());
        verify(bossJobService).requestForceReset("run-3");
        verify(bossJobService).requestForceReset("old");
    }

    // ==================== 状态 / 汇总 ====================

    @Test
    void statusReturnsServiceSnapshotVerbatim() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", "RUNNING");
        status.put("runId", "run-1");
        status.put("pendingResolutions", 2);
        when(bossJobService.getStatus()).thenReturn(status);

        ResponseEntity<Map<String, Object>> response = controller.getBossStatus();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("RUNNING", response.getBody().get("state"));
        assertEquals("run-1", response.getBody().get("runId"));
        assertEquals(2, response.getBody().get("pendingResolutions"));
    }

    @Test
    void deliverySummaryMergesThreeSourcesAndToleratesOneFailure() {
        when(budgetService.todaySummary()).thenReturn(Map.of("firstContact", Map.of("used", 3)));
        when(deliveryLedgerService.todaySummary()).thenReturn(Map.of("attempts", 5));
        when(evaluationRepository.summaryToday()).thenThrow(new IllegalStateException("db down"));

        ResponseEntity<Map<String, Object>> response = controller.getDeliverySummary();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertNotNull(data.get("budget"));
        assertNotNull(data.get("attempts"));
        assertTrue(String.valueOf(data.get("evaluations")).contains("db down"),
                "单来源失败不能拖垮整个汇总接口");
    }

    // ==================== SSE：连接后 snapshot 事件 ====================

    @Test
    void streamSendsConnectedAndSnapshotEvents() throws Exception {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", "RUNNING");
        status.put("runId", "run-1");
        when(bossJobService.getStatus()).thenReturn(status);

        SseEmitter emitter = controller.streamBossProgress();

        List<Object> sentData = earlySendData(emitter);
        // 每个 SseEventBuilder 会拆成 name 前缀 / data / 结束符 多条 earlySendAttempts（两个事件共 6 条）
        assertTrue(sentData.size() >= 4, "连接后应先发 connected、再发 snapshot：实际 " + sentData);
        assertTrue(sentData.stream().anyMatch(data -> String.valueOf(data).contains("event:connected")),
                "SSE 连接应先发 connected 事件：" + sentData);
        assertTrue(sentData.stream().anyMatch(data -> String.valueOf(data).contains("event:snapshot")),
                "SSE 连接后必须发 snapshot 事件：" + sentData);

        String expectedJson = new ObjectMapper().writeValueAsString(status);
        assertTrue(sentData.stream().anyMatch(expectedJson::equals),
                "snapshot 事件 data 必须是 bossJobService.getStatus() 的 JSON：实际 " + sentData);
        verify(bossJobService).getStatus();
    }

    @Test
    void streamToleratesSnapshotFailureAndStillReturnsEmitter() throws Exception {
        when(bossJobService.getStatus()).thenThrow(new IllegalStateException("状态读取失败"));

        SseEmitter emitter = controller.streamBossProgress();

        assertNotNull(emitter);
        List<Object> sentData = earlySendData(emitter);
        assertTrue(sentData.stream().anyMatch(data -> String.valueOf(data).contains("event:connected")));
        assertTrue(sentData.stream().noneMatch(data -> String.valueOf(data).contains("event:snapshot")),
                "快照发送失败时不得发出半截 snapshot 事件");
    }

    // ==================== 工具 ====================

    private static Map<String, Object> result(boolean success, String code) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        if (code != null) {
            map.put("code", code);
        }
        return map;
    }

    private static String postMapping(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = BossController.class.getMethod(methodName, parameterTypes);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertNotNull(mapping, methodName + " 缺少 @PostMapping");
        return mapping.value()[0];
    }

    private static String getMapping(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = BossController.class.getMethod(methodName, parameterTypes);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping, methodName + " 缺少 @GetMapping");
        return mapping.value()[0];
    }

    /** 读取 SseEmitter 在挂 Handler 前缓存的 earlySendAttempts 里的 data 对象。 */
    private static List<Object> earlySendData(SseEmitter emitter) throws Exception {
        Field field = findField(emitter.getClass(), "earlySendAttempts");
        field.setAccessible(true);
        Object value = field.get(emitter);
        assertTrue(value instanceof Set<?>, "earlySendAttempts 结构变化，无法读取 SSE 事件");
        List<Object> data = new ArrayList<>();
        for (Object attempt : (Set<?>) value) {
            Method getter = attempt.getClass().getMethod("getData");
            data.add(getter.invoke(attempt));
        }
        return data;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new AssertionError("找不到字段：" + name);
    }

    @SuppressWarnings("unused")
    private static ResponseBodyEmitter unusedReference() {
        return null;
    }
}
