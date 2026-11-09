package com.offerchaser.verification;

import com.offerchaser.application.controller.ProfileTemplateController;
import com.offerchaser.application.security.AuthInterceptor;
import com.offerchaser.application.security.LocalAuthService;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.application.templates.TemplateValidationException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 排序控制器契约：PUT /api/boss/templates/order 200/400、GET list 顺序、
 * body 校验（缺 order/非法 id）、B5 写保护（未登录 401、缺 CSRF 403、只读 GET 放行）。
 */
class TemplateOrderingControllerContractTest {

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
    void reorderReturnsOrderedList() {
        ProfileTemplateService.TemplateView b = view(2L, "B方向");
        ProfileTemplateService.TemplateView a = view(1L, "A方向");
        when(templateService.reorderTemplates(List.of(3L, 1L, 2L))).thenReturn(List.of(b, a));

        ResponseEntity<Map<String, Object>> response = controller.reorder(Map.of("order", List.of(3, 1, 2)));

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<ProfileTemplateService.TemplateView> data =
                (List<ProfileTemplateService.TemplateView>) response.getBody().get("data");
        assertEquals(List.of(2L, 1L), data.stream().map(ProfileTemplateService.TemplateView::id).toList());
        verify(templateService).reorderTemplates(List.of(3L, 1L, 2L));
    }

    @Test
    void reorderAcceptsStringIds() {
        when(templateService.reorderTemplates(List.of(3L, 1L, 2L)))
                .thenReturn(List.of(view(3L, "C方向"), view(1L, "A方向"), view(2L, "B方向")));

        ResponseEntity<Map<String, Object>> response =
                controller.reorder(Map.of("order", List.of("3", "1", "2")));

        assertEquals(200, response.getStatusCode().value());
        verify(templateService).reorderTemplates(List.of(3L, 1L, 2L));
    }

    @Test
    void malformedBodyMapsTo400() {
        assertEquals(400, controller.reorder(null).getStatusCode().value());
        assertEquals(400, controller.reorder(Map.of()).getStatusCode().value());
        assertEquals(400, controller.reorder(Map.of("order", "not-a-list")).getStatusCode().value());
        assertEquals(400, controller.reorder(Map.of("order", List.of("x"))).getStatusCode().value());
        assertEquals(400, controller.reorder(Map.of("order", List.of(Map.of("id", 1))))
                .getStatusCode().value());
        // 空数组交给 service 校验 → 400（service 抛 TemplateValidationException）
        when(templateService.reorderTemplates(any()))
                .thenThrow(new TemplateValidationException("模板排序必须包含全部模板且不能重复"));
        assertEquals(400, controller.reorder(Map.of("order", List.of())).getStatusCode().value());
    }

    @Test
    void invalidOrderMapsTo400() {
        when(templateService.reorderTemplates(eq(List.of(1L))))
                .thenThrow(new TemplateValidationException("模板排序必须包含全部模板且不能重复"));

        ResponseEntity<Map<String, Object>> response = controller.reorder(Map.of("order", List.of(1)));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("TEMPLATE_INVALID", response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("排序"));
    }

    @Test
    void reorderIsProtectedBySessionAuthAndCsrf() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        LocalAuthService authService = new LocalAuthService();
        ReflectionTestUtils.setField(authService, "authEnabled", true);
        ReflectionTestUtils.setField(authService, "tokenFile", tokenFile.toString());
        ReflectionTestUtils.setField(authService, "sessionTtlHours", 168L);
        ReflectionTestUtils.setField(authService, "requireCsrfHeader", true);
        ReflectionTestUtils.invokeMethod(authService, "initialize");
        AuthInterceptor interceptor = new AuthInterceptor(authService);

        // 未登录 PUT order → 401
        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("PUT", "/api/boss/templates/order"), unauthorized,
                new Object()));
        assertEquals(401, unauthorized.getStatus());

        // 登录后缺 CSRF 头 → 403
        Cookie cookie = new Cookie(LocalAuthService.SESSION_COOKIE, authService.createSession());
        MockHttpServletRequest withoutCsrf = request("PUT", "/api/boss/templates/order");
        withoutCsrf.setCookies(cookie);
        MockHttpServletResponse csrfResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(withoutCsrf, csrfResponse, new Object()));
        assertEquals(403, csrfResponse.getStatus());
        assertTrue(csrfResponse.getContentAsString().contains("CSRF_HEADER_MISSING"));

        // 登录 + CSRF → 放行
        MockHttpServletRequest withCsrf = request("PUT", "/api/boss/templates/order");
        withCsrf.setCookies(cookie);
        withCsrf.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertTrue(interceptor.preHandle(withCsrf, new MockHttpServletResponse(), new Object()));

        // 只读 GET 列表放行
        assertTrue(interceptor.preHandle(request("GET", "/api/boss/templates"),
                new MockHttpServletResponse(), new Object()));
    }

    // ==================== 工具 ====================

    private static ProfileTemplateService.TemplateView view(long id, String name) {
        return new ProfileTemplateService.TemplateView(id, name, "方向", "说明", id == 1L,
                "2026-11-07 10:00:00", "Java", 30, 50, false, null);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
