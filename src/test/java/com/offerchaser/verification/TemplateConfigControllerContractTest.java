package com.offerchaser.verification;

import com.offerchaser.application.controller.ProfileTemplateController;
import com.offerchaser.application.security.AuthInterceptor;
import com.offerchaser.application.security.LocalAuthService;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.application.templates.TemplateNotFoundException;
import com.offerchaser.application.templates.TemplateValidationException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置中心模板内编辑接口契约（Mockito service）：
 * PUT /api/boss/templates/{id}/config 的 200/400/404/409/500 映射、请求体缺省 400、
 * GET /{id} 返回解析后的配置对象；B5 认证（未登录 401、CSRF 403、只读 GET 放行）。
 */
class TemplateConfigControllerContractTest {

    @TempDir
    Path tempDir;

    private ProfileTemplateService templateService;
    private ProfileTemplateController controller;

    @BeforeEach
    void setUp() {
        templateService = mock(ProfileTemplateService.class);
        controller = new ProfileTemplateController(templateService);
    }

    @Test
    void updateConfigReturnsResult() {
        ProfileTemplateService.TemplateView view = view(1L, "产品方向");
        ProfileTemplateService.TemplateConfigResult result = new ProfileTemplateService.TemplateConfigResult(
                view, true, List.of("启动前已按生效模板恢复筛选规则"));
        when(templateService.updateTemplateConfig(eq(1L), any())).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(1L, request());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(result, response.getBody().get("data"));
    }

    @Test
    void updateConfigNullBodyMapsTo400() {
        ResponseEntity<Map<String, Object>> response = controller.updateConfig(1L, null);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("TEMPLATE_INVALID", response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("配置不能为空"));
    }

    @Test
    void updateConfigNotFoundAndValidationAndConflict() {
        when(templateService.updateTemplateConfig(eq(404L), any()))
                .thenThrow(new TemplateNotFoundException("方向模板不存在: 404"));
        ResponseEntity<Map<String, Object>> missing = controller.updateConfig(404L, request());
        assertEquals(404, missing.getStatusCode().value());
        assertEquals("TEMPLATE_NOT_FOUND", missing.getBody().get("code"));

        when(templateService.updateTemplateConfig(eq(2L), any()))
                .thenThrow(new TemplateValidationException("筛选规则校验失败: 学历非法"));
        ResponseEntity<Map<String, Object>> invalid = controller.updateConfig(2L, request());
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("TEMPLATE_INVALID", invalid.getBody().get("code"));
        assertTrue(String.valueOf(invalid.getBody().get("message")).contains("筛选规则校验失败"));

        // 控制器对“名称已存在/不能删除”映射 409（与模板管理接口保持一致的冲突语义）
        when(templateService.updateTemplateConfig(eq(3L), any()))
                .thenThrow(new TemplateValidationException("模板名称已存在: X"));
        ResponseEntity<Map<String, Object>> conflict = controller.updateConfig(3L, request());
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("TEMPLATE_CONFLICT", conflict.getBody().get("code"));
    }

    @Test
    void updateConfigUnexpectedErrorMapsTo500() {
        when(templateService.updateTemplateConfig(eq(5L), any()))
                .thenThrow(new IllegalStateException("数据库不可用"));
        ResponseEntity<Map<String, Object>> response = controller.updateConfig(5L, request());
        assertEquals(500, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("保存模板配置失败"));
        assertFalse(response.getBody().containsKey("code"), "500 外壳不伪造业务错误码");
    }

    @Test
    void detailReturnsParsedConfigObjects() {
        Map<String, Object> detail = Map.of(
                "template", view(1L, "产品方向"),
                "bossConfig", Map.of("keywords", "[\"Java\"]", "expectedSalaryMin", 30),
                "rule", Map.of("highestDegree", "本科"),
                "aiIntroduce", "介绍",
                "aiPrompt", "提示词",
                "resumeFile", "/tmp/resume.png");
        when(templateService.detail(1L)).thenReturn(detail);

        ResponseEntity<Map<String, Object>> ok = controller.detail(1L);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(detail, ok.getBody().get("data"));

        when(templateService.detail(404L)).thenThrow(new TemplateNotFoundException("方向模板不存在: 404"));
        assertEquals(404, controller.detail(404L).getStatusCode().value());
    }

    @Test
    void configEndpointIsProtectedBySessionAuth() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        LocalAuthService authService = new LocalAuthService();
        ReflectionTestUtils.setField(authService, "authEnabled", true);
        ReflectionTestUtils.setField(authService, "tokenFile", tokenFile.toString());
        ReflectionTestUtils.setField(authService, "sessionTtlHours", 168L);
        ReflectionTestUtils.setField(authService, "requireCsrfHeader", true);
        ReflectionTestUtils.invokeMethod(authService, "initialize");
        AuthInterceptor interceptor = new AuthInterceptor(authService);

        // 未登录 PUT config → 401
        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("PUT", "/api/boss/templates/1/config"),
                unauthorized, new Object()));
        assertEquals(401, unauthorized.getStatus());

        // 登录后缺 CSRF 头 → 403
        String sessionId = authService.createSession();
        Cookie cookie = new Cookie(LocalAuthService.SESSION_COOKIE, sessionId);
        MockHttpServletRequest withoutCsrf = request("PUT", "/api/boss/templates/1/config");
        withoutCsrf.setCookies(cookie);
        MockHttpServletResponse csrfResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(withoutCsrf, csrfResponse, new Object()));
        assertEquals(403, csrfResponse.getStatus());
        assertTrue(csrfResponse.getContentAsString().contains("CSRF_HEADER_MISSING"));

        // 登录 + CSRF → 放行
        MockHttpServletRequest withCsrf = request("PUT", "/api/boss/templates/1/config");
        withCsrf.setCookies(cookie);
        withCsrf.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertTrue(interceptor.preHandle(withCsrf, new MockHttpServletResponse(), new Object()));

        // 只读 GET detail/简历预览/选项接口放行
        for (String uri : List.of("/api/boss/templates/1", "/api/boss/templates/1/resume-image/content",
                "/api/boss/config/options/degree")) {
            assertTrue(interceptor.preHandle(request("GET", uri), new MockHttpServletResponse(), new Object()),
                    uri + " 只读 GET 应放行");
        }
    }

    // ==================== 工具 ====================

    private static ProfileTemplateService.TemplateConfigRequest request() {
        return new ProfileTemplateService.TemplateConfigRequest(
                List.of("Java"), "101020100", null, null, null, null, null, null, null,
                30, 50, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ProfileTemplateService.TemplateView view(long id, String name) {
        return new ProfileTemplateService.TemplateView(id, name, "产品", "说明", true,
                "2026-11-06 10:00:00", "Java", 30, 50, true, null);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
