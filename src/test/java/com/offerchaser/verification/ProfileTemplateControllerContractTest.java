package com.offerchaser.verification;

import com.offerchaser.application.controller.ProfileTemplateController;
import com.offerchaser.application.security.AuthInterceptor;
import com.offerchaser.application.security.LocalAuthService;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.application.templates.TemplateNotFoundException;
import com.offerchaser.application.templates.TemplateValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 方向模板接口契约（Mockito service）：列表/详情/创建/改元信息/激活/覆盖/复制/删除/上传简历/预览简历的
 * 响应外壳与 400 TEMPLATE_INVALID / 404 TEMPLATE_NOT_FOUND / 409 TEMPLATE_CONFLICT / 500 映射；
 * 以及 B5 认证兼容（写接口未登录 401、无 CSRF 403、只读 GET 与简历预览放行）。
 */
class ProfileTemplateControllerContractTest {

    @TempDir
    Path tempDir;

    private ProfileTemplateService templateService;
    private ProfileTemplateController controller;

    @BeforeEach
    void setUp() {
        templateService = mock(ProfileTemplateService.class);
        controller = new ProfileTemplateController(templateService);
    }

    // ==================== 基础映射 ====================

    @Test
    void listReturnsItemsAndActiveTemplate() {
        ProfileTemplateService.TemplateView active = view(2L, "产品方向", true);
        when(templateService.list()).thenReturn(List.of(view(1L, "方向A", false), active));
        when(templateService.active()).thenReturn(Optional.of(active));

        ResponseEntity<Map<String, Object>> response = controller.list();

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals(2, ((List<?>) data.get("items")).size());
        assertEquals(active, data.get("active"));
    }

    @Test
    void listFailureReturns500Envelope() {
        when(templateService.list()).thenThrow(new IllegalStateException("数据库不可用"));
        ResponseEntity<Map<String, Object>> response = controller.list();
        assertEquals(500, response.getStatusCode().value());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("读取方向模板失败"));
        assertFalse(response.getBody().containsKey("code"), "500 外壳不伪造业务错误码");
    }

    @Test
    void detailReturnsSnapshotOr404() {
        Map<String, Object> detail = Map.of("template", view(1L, "方向A", false),
                "bossConfigJson", "{\"keywords\":\"[\\\"Java\\\"]\"}");
        when(templateService.detail(1L)).thenReturn(detail);
        ResponseEntity<Map<String, Object>> ok = controller.detail(1L);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(detail, ok.getBody().get("data"));

        when(templateService.detail(404L)).thenThrow(new TemplateNotFoundException("方向模板不存在: 404"));
        ResponseEntity<Map<String, Object>> missing = controller.detail(404L);
        assertEquals(404, missing.getStatusCode().value());
        assertEquals("TEMPLATE_NOT_FOUND", missing.getBody().get("code"));
    }

    // ==================== 创建 / 改元信息 ====================

    @Test
    void createMapsSuccessAndValidationErrors() {
        when(templateService.create("产品方向", "产品", "说明")).thenReturn(view(1L, "产品方向", false));
        ResponseEntity<Map<String, Object>> ok = controller.create(
                Map.of("name", "产品方向", "direction", "产品", "description", "说明"));
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(view(1L, "产品方向", false), ok.getBody().get("data"));

        assertEquals(400, controller.create(null).getStatusCode().value());
        assertEquals("TEMPLATE_INVALID", controller.create(null).getBody().get("code"));

        when(templateService.create(any(), any(), any()))
                .thenThrow(new TemplateValidationException("模板名称不能为空"));
        ResponseEntity<Map<String, Object>> invalid = controller.create(Map.of("name", " "));
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("TEMPLATE_INVALID", invalid.getBody().get("code"));

        // 已有抛异常 stub 时再次 when(mock.x()) 会先触发旧 stub，必须用 doThrow 覆盖
        org.mockito.Mockito.doThrow(new TemplateValidationException("模板名称已存在: 产品方向"))
                .when(templateService).create(any(), any(), any());
        ResponseEntity<Map<String, Object>> conflict = controller.create(Map.of("name", "产品方向"));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("TEMPLATE_CONFLICT", conflict.getBody().get("code"));

        org.mockito.Mockito.doThrow(new IllegalStateException("创建失败"))
                .when(templateService).create(any(), any(), any());
        assertEquals(500, controller.create(Map.of("name", "x")).getStatusCode().value());
    }

    @Test
    void updateMetadataFillsMissingFieldsFromExistingTemplate() {
        ProfileTemplateService.TemplateView existing = view(1L, "旧名称", false);
        when(templateService.get(1L)).thenReturn(existing);
        when(templateService.updateMetadata(1L, "新名称", "方向", "说明"))
                .thenReturn(new ProfileTemplateService.TemplateView(1L, "新名称", "方向", "说明",
                        false, "2026-11-06 10:00:00", "Java", 30, 50, true, null));

        ResponseEntity<Map<String, Object>> response = controller.updateMetadata(1L,
                Map.of("name", "新名称"));

        assertEquals(200, response.getStatusCode().value());
        verify(templateService).updateMetadata(1L, "新名称", "方向", "说明");
        assertEquals(400, controller.updateMetadata(1L, null).getStatusCode().value());
    }

    @Test
    void updateMetadataMapsConflictAndNotFound() {
        when(templateService.get(404L)).thenThrow(new TemplateNotFoundException("方向模板不存在: 404"));
        assertEquals(404, controller.updateMetadata(404L, Map.of("name", "x")).getStatusCode().value());

        when(templateService.get(1L)).thenReturn(view(1L, "A", false));
        when(templateService.updateMetadata(eq(1L), anyString(), any(), any()))
                .thenThrow(new TemplateValidationException("模板名称已存在: B"));
        ResponseEntity<Map<String, Object>> conflict = controller.updateMetadata(1L, Map.of("name", "B"));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("TEMPLATE_CONFLICT", conflict.getBody().get("code"));
    }

    // ==================== 激活 / 覆盖 / 复制 / 删除 ====================

    @Test
    void activateMapsResultWarningsAndErrors() {
        ProfileTemplateService.TemplateView template = view(1L, "产品方向", true);
        when(templateService.activate(1L))
                .thenReturn(new ProfileTemplateService.ActivateResult(template, List.of("缺少简历，沿用当前")));

        ResponseEntity<Map<String, Object>> ok = controller.activate(1L);
        assertEquals(200, ok.getStatusCode().value());
        ProfileTemplateService.ActivateResult result =
                (ProfileTemplateService.ActivateResult) ok.getBody().get("data");
        assertEquals(template, result.template());
        assertEquals(1, result.warnings().size());

        when(templateService.activate(2L)).thenThrow(new TemplateNotFoundException("方向模板不存在: 2"));
        assertEquals(404, controller.activate(2L).getStatusCode().value());
        when(templateService.activate(3L)).thenThrow(new TemplateValidationException("激活模板失败: x"));
        assertEquals(400, controller.activate(3L).getStatusCode().value());
        when(templateService.activate(4L)).thenThrow(new RuntimeException("boom"));
        assertEquals(500, controller.activate(4L).getStatusCode().value());
    }

    @Test
    void captureDuplicateDeleteMappings() {
        when(templateService.capture(1L)).thenReturn(view(1L, "A", false));
        assertEquals(200, controller.capture(1L).getStatusCode().value());
        when(templateService.capture(9L)).thenThrow(new TemplateNotFoundException("方向模板不存在: 9"));
        assertEquals(404, controller.capture(9L).getStatusCode().value());

        when(templateService.duplicate(1L, "副本")).thenReturn(view(2L, "副本", false));
        ResponseEntity<Map<String, Object>> duplicated = controller.duplicate(1L, Map.of("name", "副本"));
        assertEquals(200, duplicated.getStatusCode().value());
        verify(templateService).duplicate(1L, "副本");
        assertEquals(200, controller.duplicate(1L, null).getStatusCode().value());
        verify(templateService).duplicate(1L, null);
        when(templateService.duplicate(3L, "重复")).thenThrow(new TemplateValidationException("模板名称已存在: 重复"));
        assertEquals(409, controller.duplicate(3L, Map.of("name", "重复")).getStatusCode().value());

        ResponseEntity<Map<String, Object>> deleted = controller.delete(1L);
        assertEquals(200, deleted.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> deleteData = (Map<String, Object>) deleted.getBody().get("data");
        assertEquals(true, deleteData.get("deleted"));

        org.mockito.Mockito.doThrow(new TemplateValidationException("当前生效的模板不能删除，请先激活其他模板"))
                .when(templateService).delete(2L);
        ResponseEntity<Map<String, Object>> activeDelete = controller.delete(2L);
        assertEquals(409, activeDelete.getStatusCode().value());
        assertEquals("TEMPLATE_CONFLICT", activeDelete.getBody().get("code"));

        org.mockito.Mockito.doThrow(new TemplateNotFoundException("方向模板不存在: 9"))
                .when(templateService).delete(9L);
        assertEquals(404, controller.delete(9L).getStatusCode().value());
    }

    // ==================== 简历上传 / 预览 ====================

    @Test
    void uploadResumeMapsResultAndErrors() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.png", "image/png",
                new byte[]{1, 2, 3});
        when(templateService.uploadResume(1L, file)).thenReturn(view(1L, "A", false));
        ResponseEntity<Map<String, Object>> ok = controller.uploadResume(1L, file);
        assertEquals(200, ok.getStatusCode().value());

        when(templateService.uploadResume(2L, file))
                .thenThrow(new TemplateValidationException("文件类型不支持"));
        ResponseEntity<Map<String, Object>> invalid = controller.uploadResume(2L, file);
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("TEMPLATE_INVALID", invalid.getBody().get("code"));

        when(templateService.uploadResume(3L, file))
                .thenThrow(new TemplateNotFoundException("方向模板不存在: 3"));
        assertEquals(404, controller.uploadResume(3L, file).getStatusCode().value());
    }

    @Test
    void resumeContentReturnsFileOr404() throws Exception {
        Path resume = tempDir.resolve("resume.png");
        Files.write(resume, new byte[]{(byte) 0x89, 'P', 'N', 'G', 1});
        when(templateService.resumePath(1L)).thenReturn(Optional.of(resume));
        ResponseEntity<?> ok = controller.resumeContent(1L);
        assertEquals(200, ok.getStatusCode().value());
        assertNotNull(ok.getBody());
        assertEquals(MediaType.IMAGE_PNG, ok.getHeaders().getContentType());

        when(templateService.resumePath(2L)).thenReturn(Optional.empty());
        ResponseEntity<?> empty = controller.resumeContent(2L);
        assertEquals(404, empty.getStatusCode().value());
        assertNull(empty.getBody());

        when(templateService.resumePath(3L)).thenThrow(new TemplateNotFoundException("不存在"));
        assertEquals(404, controller.resumeContent(3L).getStatusCode().value());
    }

    // ==================== B5 认证兼容 ====================

    @Test
    void templateWriteEndpointsAreProtectedBySessionAuth() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        LocalAuthService authService = new LocalAuthService();
        ReflectionTestUtils.setField(authService, "authEnabled", true);
        ReflectionTestUtils.setField(authService, "tokenFile", tokenFile.toString());
        ReflectionTestUtils.setField(authService, "sessionTtlHours", 168L);
        ReflectionTestUtils.setField(authService, "requireCsrfHeader", true);
        ReflectionTestUtils.invokeMethod(authService, "initialize");
        AuthInterceptor interceptor = new AuthInterceptor(authService);

        // 未登录写接口 → 401 UNAUTHORIZED
        for (String method : List.of("POST", "PUT", "DELETE")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request(method, "/api/boss/templates/1"), response, new Object()),
                    method + " 模板写接口未登录必须 401");
            assertEquals(401, response.getStatus());
        }
        // 未登录只读 GET（列表/详情/简历预览）→ 放行
        for (String uri : List.of("/api/boss/templates", "/api/boss/templates/1",
                "/api/boss/templates/1/resume-image/content")) {
            assertTrue(interceptor.preHandle(request("GET", uri), new MockHttpServletResponse(), new Object()),
                    uri + " 只读 GET 应放行（本地回环）");
        }

        // 登录后写接口需要 CSRF 头
        String sessionId = authService.createSession();
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie(
                LocalAuthService.SESSION_COOKIE, sessionId);
        MockHttpServletRequest withoutCsrf = request("POST", "/api/boss/templates");
        withoutCsrf.setCookies(cookie);
        MockHttpServletResponse csrfResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(withoutCsrf, csrfResponse, new Object()));
        assertEquals(403, csrfResponse.getStatus());
        assertTrue(csrfResponse.getContentAsString().contains("CSRF_HEADER_MISSING"));

        MockHttpServletRequest withCsrf = request("POST", "/api/boss/templates");
        withCsrf.setCookies(cookie);
        withCsrf.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertTrue(interceptor.preHandle(withCsrf, new MockHttpServletResponse(), new Object()));
    }

    // ==================== 工具 ====================

    private static ProfileTemplateService.TemplateView view(long id, String name, boolean active) {
        return new ProfileTemplateService.TemplateView(id, name, "方向", "说明", active,
                "2026-11-06 10:00:00", "Java", 30, 50, true, null);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    @SuppressWarnings("unused")
    private static MultipartFile noop(MultipartFile file) {
        return file;
    }
}
