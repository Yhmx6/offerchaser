package com.offerchaser.verification;

import com.offerchaser.application.contacts.ContactNotFoundException;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.ContactValidationException;
import com.offerchaser.application.contacts.ContactVersionConflictException;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.controller.BossContactsController;
import com.offerchaser.application.persistence.ContactStateRepository;
import com.offerchaser.application.persistence.FollowupPlanRepository;
import com.offerchaser.application.persistence.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B3 联系人/跟进/补发图片接口契约（Mockito service，不启动 Spring）：
 * 路由映射、数据外壳、参数透传、400/404/409 错误码（INVALID_STATE / INVALID_RESEND / NOT_FOUND /
 * VERSION_CONFLICT + currentVersion）。
 */
class BossContactsControllerContractTest {

    private ContactStateService contactStateService;
    private FollowupService followupService;
    private BossContactsController controller;

    @BeforeEach
    void setUp() {
        contactStateService = mock(ContactStateService.class);
        followupService = mock(FollowupService.class);
        controller = new BossContactsController(contactStateService, followupService);
    }

    // ==================== 联系人 ====================

    @Test
    void listContactsWrapsPageAndPassesFilters() throws Exception {
        ContactStateRepository.ContactState state = contactState("uid:U1", "UNKNOWN", 0);
        when(contactStateService.list("REPLIED", true, false, 2, 10))
                .thenReturn(new PageResult<>(List.of(state), 2, 10, 21L));

        ResponseEntity<Map<String, Object>> response = controller.listContacts(
                "REPLIED", true, false, 2, 10);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals(2, data.get("page"));
        assertEquals(10, data.get("size"));
        assertEquals(21L, data.get("total"));
        assertNotNull(data.get("items"));
        verify(contactStateService).list("REPLIED", true, false, 2, 10);
    }

    @Test
    void getContactReturnsDetailOr404() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("contact", new ContactStateService.ContactStateView("uid:U1", "U1", "张女士",
                "示例科技", "REPLIED", true, false, "2026-11-04 10:00:00",
                "2026-11-04 11:00:00", 1, "2026-11-01 10:00:00", "2026-11-04 11:00:00"));
        detail.put("attempts", List.of());
        when(contactStateService.detail("uid:U1")).thenReturn(detail);

        ResponseEntity<Map<String, Object>> ok = controller.getContact("uid:U1");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(detail, ok.getBody().get("data"));

        when(contactStateService.detail("uid:404")).thenThrow(new ContactNotFoundException("联系人不存在"));
        ResponseEntity<Map<String, Object>> missing = controller.getContact("uid:404");
        assertEquals(404, missing.getStatusCode().value());
        assertEquals("NOT_FOUND", missing.getBody().get("code"));
    }

    @Test
    void markStateSuccessAndErrorCodes() {
        ContactStateService.ContactStateView updated = new ContactStateService.ContactStateView(
                "uid:U1", "U1", "张女士", "示例科技", "REPLIED", false, false,
                "2026-11-04 10:00:00", "2026-11-04 11:00:00", 1,
                "2026-11-01 10:00:00", "2026-11-04 11:00:00");
        when(contactStateService.markState(eq("uid:U1"), any())).thenReturn(updated);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("replyState", "replied");
        body.put("manualTakeover", false);
        body.put("stopContact", false);
        body.put("reason", "HR 已回复");
        body.put("expectedVersion", 0);
        body.put("idempotencyKey", "idem-1");
        ResponseEntity<Map<String, Object>> success = controller.markState("uid:U1", body);
        assertEquals(200, success.getStatusCode().value());
        assertEquals(updated, success.getBody().get("data"));

        // 参数被解析并透传（replyState 大写由 service 做，这里验证原值/版本）
        org.mockito.ArgumentCaptor<ContactStateService.MarkStateRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ContactStateService.MarkStateRequest.class);
        verify(contactStateService).markState(eq("uid:U1"), captor.capture());
        assertEquals("replied", captor.getValue().replyState());
        assertEquals(0, captor.getValue().expectedVersion());
        assertEquals("idem-1", captor.getValue().idempotencyKey());

        // 缺 expectedVersion → 400 INVALID_STATE
        Map<String, Object> missingVersion = new LinkedHashMap<>();
        missingVersion.put("replyState", "REPLIED");
        ResponseEntity<Map<String, Object>> bad = controller.markState("uid:U1", missingVersion);
        assertEquals(400, bad.getStatusCode().value());
        assertEquals("INVALID_STATE", bad.getBody().get("code"));
        assertFalse((Boolean) bad.getBody().get("success"));

        // 空 body → 400
        assertEquals(400, controller.markState("uid:U1", null).getStatusCode().value());

        // 版本冲突 → 409 VERSION_CONFLICT + currentVersion
        when(contactStateService.markState(eq("uid:U2"), any()))
                .thenThrow(new ContactVersionConflictException(3));
        ResponseEntity<Map<String, Object>> conflict = controller.markState("uid:U2", body);
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("VERSION_CONFLICT", conflict.getBody().get("code"));
        assertEquals(3, conflict.getBody().get("currentVersion"));

        // 校验失败 → 400
        when(contactStateService.markState(eq("uid:U3"), any()))
                .thenThrow(new ContactValidationException("reason 不能为空（人工操作需要留下原因）"));
        ResponseEntity<Map<String, Object>> invalid = controller.markState("uid:U3", body);
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("INVALID_STATE", invalid.getBody().get("code"));
    }

    // ==================== 跟进 ====================

    @Test
    void listFollowupsWrapsPageAndPassesStatus() {
        FollowupPlanRepository.FollowupPlan plan = plan("plan-1", "SUGGESTED", 0);
        when(followupService.list("SUGGESTED", 1, 20))
                .thenReturn(new PageResult<>(List.of(plan), 1, 20, 1L));

        ResponseEntity<Map<String, Object>> response = controller.listFollowups("SUGGESTED", 1, 20);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals(1L, data.get("total"));
        verify(followupService).list("SUGGESTED", 1, 20);
    }

    @Test
    void generateFollowupsDefaultsLimitAndReturnsCreated() {
        when(followupService.generateSuggestions(50)).thenReturn(3);
        ResponseEntity<Map<String, Object>> ok = controller.generateFollowups(null);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(3, ((Map<?, ?>) ok.getBody().get("data")).get("created"));

        when(followupService.generateSuggestions(7)).thenReturn(0);
        ResponseEntity<Map<String, Object>> withLimit = controller.generateFollowups(Map.of("limit", "7"));
        assertEquals(0, ((Map<?, ?>) withLimit.getBody().get("data")).get("created"));

        when(followupService.generateSuggestions(anyInt()))
                .thenThrow(new ContactValidationException("生成失败"));
        ResponseEntity<Map<String, Object>> invalid = controller.generateFollowups(Map.of());
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("INVALID_STATE", invalid.getBody().get("code"));
    }

    @Test
    void approveAndRejectMapStatusAndErrors() {
        FollowupPlanRepository.FollowupPlan approved = plan("plan-1", "APPROVED", 1);
        when(followupService.approve(eq("plan-1"), eq(0), eq("同意"), eq("idem-a")))
                .thenReturn(approved);
        ResponseEntity<Map<String, Object>> approve = controller.approveFollowup("plan-1",
                Map.of("expectedVersion", 0, "reason", "同意", "idempotencyKey", "idem-a"));
        assertEquals(200, approve.getStatusCode().value());
        assertEquals(approved, approve.getBody().get("data"));

        FollowupPlanRepository.FollowupPlan rejected = plan("plan-2", "REJECTED", 1);
        when(followupService.reject(eq("plan-2"), eq(1), eq("不需要"), eq(null)))
                .thenReturn(rejected);
        ResponseEntity<Map<String, Object>> reject = controller.rejectFollowup("plan-2",
                Map.of("expectedVersion", 1, "reason", "不需要"));
        assertEquals(200, reject.getStatusCode().value());
        assertEquals(rejected, reject.getBody().get("data"));

        // 缺 expectedVersion → 400
        ResponseEntity<Map<String, Object>> bad = controller.approveFollowup("plan-1", Map.of("reason", "x"));
        assertEquals(400, bad.getStatusCode().value());
        assertEquals("INVALID_STATE", bad.getBody().get("code"));

        // 版本冲突 / 状态非法 / 不存在
        when(followupService.approve(eq("plan-3"), any(Integer.class), any(), any()))
                .thenThrow(new ContactVersionConflictException(2));
        ResponseEntity<Map<String, Object>> conflict = controller.approveFollowup("plan-3",
                Map.of("expectedVersion", 0, "reason", "x"));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("VERSION_CONFLICT", conflict.getBody().get("code"));
        assertEquals(2, conflict.getBody().get("currentVersion"));

        when(followupService.approve(eq("plan-4"), any(Integer.class), any(), any()))
                .thenThrow(new ContactValidationException("只有待批准（SUGGESTED）的跟进计划可以批准"));
        ResponseEntity<Map<String, Object>> invalidState = controller.approveFollowup("plan-4",
                Map.of("expectedVersion", 0, "reason", "x"));
        assertEquals(400, invalidState.getStatusCode().value());
        assertEquals("INVALID_STATE", invalidState.getBody().get("code"));

        when(followupService.reject(eq("plan-5"), any(Integer.class), any(), any()))
                .thenThrow(new ContactNotFoundException("跟进计划不存在：plan-5"));
        ResponseEntity<Map<String, Object>> missing = controller.rejectFollowup("plan-5",
                Map.of("expectedVersion", 0, "reason", "x"));
        assertEquals(404, missing.getStatusCode().value());
        assertEquals("NOT_FOUND", missing.getBody().get("code"));
    }

    // ==================== 补发图片 ====================

    @Test
    void resendImageMapsSuccessAndErrors() {
        FollowupPlanRepository.FollowupPlan resendPlan = plan("plan-img", "SUGGESTED", 0);
        when(followupService.createImageResendPlan("attempt-1", "补发")).thenReturn(resendPlan);

        ResponseEntity<Map<String, Object>> ok = controller.resendImage("attempt-1",
                Map.of("reason", "补发"));
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(resendPlan, ok.getBody().get("data"));
        verify(followupService).createImageResendPlan("attempt-1", "补发");

        // 条件不满足 → 400 INVALID_RESEND
        when(followupService.createImageResendPlan("attempt-2", null))
                .thenThrow(new ContactValidationException("只有文字确认成功（CONFIRMED）的尝试才能补发图片"));
        ResponseEntity<Map<String, Object>> invalid = controller.resendImage("attempt-2", null);
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("INVALID_RESEND", invalid.getBody().get("code"));

        // 找不到 attempt → 404
        when(followupService.createImageResendPlan("attempt-404", null))
                .thenThrow(new ContactNotFoundException("投递尝试不存在：attempt-404"));
        ResponseEntity<Map<String, Object>> missing = controller.resendImage("attempt-404", null);
        assertEquals(404, missing.getStatusCode().value());
        assertEquals("NOT_FOUND", missing.getBody().get("code"));
    }

    @Test
    void listContactsFailureReturns500Envelope() {
        when(contactStateService.list(any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenThrow(new IllegalStateException("数据库不可用"));
        ResponseEntity<Map<String, Object>> response = controller.listContacts(null, null, null, 1, 20);
        assertEquals(500, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("查询联系人失败"));
        assertNull(response.getBody().get("code"), "B1/B3 控制器 500 外壳不含 code 字段");
    }

    // ==================== 工具 ====================

    private static ContactStateRepository.ContactState contactState(String contactKey, String replyState,
                                                                    int version) {
        return new ContactStateRepository.ContactState(1L, "local", "boss", contactKey, "U1",
                "张女士", "示例科技", replyState, false, false,
                "2026-11-04 10:00:00", null, version,
                "2026-11-01 10:00:00", "2026-11-04 10:00:00");
    }

    private static FollowupPlanRepository.FollowupPlan plan(String planId, String status, int version) {
        return new FollowupPlanRepository.FollowupPlan(1L, planId, "local", "boss", "ENC-1", "U1",
                "uid:U1", "Java 开发", "示例科技", "FOLLOWUP", status, "原因", null, version,
                "2026-11-01 10:00:00", "2026-11-02 10:00:00", null, null,
                "2026-11-01 10:00:00", "2026-11-02 10:00:00");
    }

    @SuppressWarnings("unused")
    private static boolean noop() {
        return true;
    }
}
