package com.offerchaser.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.controller.ProfileTemplateController;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.application.templates.TemplateValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 控制器契约：PUT /api/boss/templates/{id}/config 的 deliveryPolicy 透传（200/400）；
 * GET /{id} 返回 deliveryPolicy；前端 JSON 嵌套对象可反序列化为 DeliveryPolicy。
 */
class TemplateDeliveryPolicyControllerContractTest {

    private ProfileTemplateService templateService;
    private ProfileTemplateController controller;

    @BeforeEach
    void setUp() {
        templateService = mock(ProfileTemplateService.class);
        controller = new ProfileTemplateController(templateService);
    }

    @Test
    void updateConfigAcceptsDeliveryPolicyAndReturnsIt() {
        ProfileTemplateService.DeliveryPolicy policy =
                new ProfileTemplateService.DeliveryPolicy(12, 4, 9);
        ProfileTemplateService.TemplateView view = view(policy);
        when(templateService.updateTemplateConfig(eq(1L), any())).thenReturn(
                new ProfileTemplateService.TemplateConfigResult(view, false,
                        List.of("模板未激活：保存只更新模板快照")));

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(1L,
                requestWithPolicy(policy));

        assertEquals(200, response.getStatusCode().value());
        ProfileTemplateService.TemplateConfigResult result =
                (ProfileTemplateService.TemplateConfigResult) response.getBody().get("data");
        assertEquals(12, result.template().deliveryPolicy().collectMaxPerKeyword());
        verify(templateService).updateTemplateConfig(eq(1L), any());
    }

    @Test
    void invalidPolicyMapsTo400() {
        ProfileTemplateService.TemplateConfigRequest invalid = requestWithPolicy(
                new ProfileTemplateService.DeliveryPolicy(-2, 4, 9));
        when(templateService.updateTemplateConfig(eq(1L), any()))
                .thenThrow(new TemplateValidationException(
                        "采集策略：每关键词候选上限不能为负数（-1 表示恢复全局默认）"));

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(1L, invalid);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("TEMPLATE_INVALID", response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("采集策略"));
    }

    @Test
    void detailReturnsDeliveryPolicyObject() {
        ProfileTemplateService.DeliveryPolicy policy =
                new ProfileTemplateService.DeliveryPolicy(12, 4, 9);
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("template", view(policy));
        detail.put("bossConfig", Map.of("keywords", "[\"Java\"]"));
        detail.put("rule", Map.of("highestDegree", "本科"));
        detail.put("deliveryPolicy", policy);
        detail.put("aiIntroduce", "介绍");
        detail.put("aiPrompt", "提示词");
        detail.put("resumeFile", null);
        when(templateService.detail(1L)).thenReturn(detail);
        ResponseEntity<Map<String, Object>> response = controller.detail(1L);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        ProfileTemplateService.DeliveryPolicy returned =
                (ProfileTemplateService.DeliveryPolicy) data.get("deliveryPolicy");
        assertNotNull(returned, "detail 必须返回解析后的 deliveryPolicy 对象");
        assertEquals(12, returned.collectMaxPerKeyword());
        assertEquals(4, returned.collectMaxDurationMinutes());
        assertEquals(9, returned.collectNoNewWindowSeconds());
    }

    @Test
    void nestedDeliveryPolicyJsonDeserializes() throws Exception {
        // 前端整包保存的 payload 形状：deliveryPolicy 是嵌套对象
        String json = "{\"keywords\":[\"Java\"],\"deliveryPolicy\":{" +
                "\"collectMaxPerKeyword\":12,\"collectMaxDurationMinutes\":4," +
                "\"collectNoNewWindowSeconds\":9}}";
        ProfileTemplateService.TemplateConfigRequest request =
                new ObjectMapper().findAndRegisterModules()
                        .readValue(json, ProfileTemplateService.TemplateConfigRequest.class);

        assertNotNull(request.deliveryPolicy());
        assertEquals(12, request.deliveryPolicy().collectMaxPerKeyword());
        assertEquals(4, request.deliveryPolicy().collectMaxDurationMinutes());
        assertEquals(9, request.deliveryPolicy().collectNoNewWindowSeconds());
    }

    // ==================== 工具 ====================

    private static ProfileTemplateService.TemplateConfigRequest requestWithPolicy(
            ProfileTemplateService.DeliveryPolicy policy) {
        return new ProfileTemplateService.TemplateConfigRequest(
                List.of("Java"), null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, policy);
    }

    private static ProfileTemplateService.TemplateView view(
            ProfileTemplateService.DeliveryPolicy policy) {
        return new ProfileTemplateService.TemplateView(1L, "产品方向", "产品", "说明", true,
                "2026-11-06 10:00:00", "Java", 30, 50, true, policy);
    }
}