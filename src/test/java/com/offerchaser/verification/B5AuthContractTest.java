package com.offerchaser.verification;

import com.offerchaser.application.controller.SessionController;
import com.offerchaser.application.security.AuthInterceptor;
import com.offerchaser.application.security.LocalAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5 本机会话认证契约验收（直接使用真实 {@link LocalAuthService} + 拦截器/控制器，不启动 Spring/浏览器）：
 * auth-enabled 开关、未登录写 401/GET 放行、Cookie 接口必须认证、登录 Set-Cookie 属性与状态不泄漏 token、
 * 登录节流 429、CSRF 头 403、X-Session-Token 头认证、SSE/只读放行、登出清 Cookie。
 */
class B5AuthContractTest {

    private static final String WRITE_URI = "/api/boss/start";

    @TempDir
    Path tempDir;

    private Path tokenFile;
    private LocalAuthService authService;
    private String token;
    private AuthInterceptor interceptor;
    private SessionController sessionController;

    @BeforeEach
    void setUp() throws Exception {
        tokenFile = tempDir.resolve("data/local-session-token");
        authService = newService(true, tokenFile);
        token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        interceptor = new AuthInterceptor(authService);
        sessionController = new SessionController(authService);
    }

    // ==================== auth-enabled 开关 ====================

    @Test
    void authDisabledAllowsEverythingAndStatusSaysSo() throws Exception {
        LocalAuthService disabled = newService(false, tokenFile);
        AuthInterceptor disabledInterceptor = new AuthInterceptor(disabled);
        assertTrue(disabledInterceptor.preHandle(request("POST", WRITE_URI), new MockHttpServletResponse(), new Object()));
        assertTrue(disabledInterceptor.preHandle(request("GET", "/api/cookie"), new MockHttpServletResponse(), new Object()));
        assertTrue(disabled.verifyLocalToken(null), "关闭认证后任何 token 都视为通过");
        assertTrue(disabled.isAuthenticated(request("GET", WRITE_URI)));

        SessionController controller = new SessionController(disabled);
        ResponseEntity<Map<String, Object>> status = controller.status(request("GET", "/api/session/status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) status.getBody().get("data");
        assertEquals(false, data.get("authEnabled"));
        assertEquals(true, data.get("authenticated"));

        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        ResponseEntity<Map<String, Object>> login = controller.login(Map.of("token", "anything"),
                request("POST", "/api/session/login"), loginResponse);
        assertEquals(200, login.getStatusCode().value());
        assertNull(loginResponse.getHeader("Set-Cookie"), "关闭认证时登录不再需要 Cookie");
    }

    // ==================== 未登录：只读放行 / 写 401 ====================

    @Test
    void readOnlyGetsArePublicButWritesRequireAuth() throws Exception {
        assertTrue(interceptor.preHandle(request("GET", "/api/boss/status"), new MockHttpServletResponse(), new Object()),
                "未登录也应能读取只读状态（本地回环）");
        assertTrue(interceptor.preHandle(request("GET", "/api/boss/stream"), new MockHttpServletResponse(), new Object()),
                "SSE 只读 GET 不应要求认证");
        assertTrue(interceptor.preHandle(request("HEAD", "/api/boss/status"), new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("POST", "/boss/start"), new MockHttpServletResponse(), new Object()),
                "非 /api 路径不受本拦截器影响");

        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean allowed = interceptor.preHandle(request(method, WRITE_URI), response, new Object());
            assertFalse(allowed, method + " 写操作未登录必须拒绝");
            assertEquals(401, response.getStatus());
            String body = response.getContentAsString(StandardCharsets.UTF_8);
            assertTrue(body.contains("UNAUTHORIZED"));
            assertTrue(body.contains("需要本机会话登录"));
            assertFalse(body.contains(token), "401 响应不得泄漏令牌");
        }
    }

    @Test
    void sensitiveCookieAndBrowserEndpointsRequireAuthEvenForGet() throws Exception {
        for (String uri : new String[]{"/api/cookie", "/api/cookie/list",
                "/api/boss/reload", "/api/playwright/status"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request("GET", uri), response, new Object()),
                    uri + " 未登录必须 401（敏感只读接口）");
            assertEquals(401, response.getStatus());
        }
        // 已登录后 Cookie 接口 GET 可访问（读只读，不要求 CSRF）
        String sessionId = authService.createSession();
        MockHttpServletRequest cookieRequest = request("GET", "/api/cookie");
        cookieRequest.setCookies(new Cookie(LocalAuthService.SESSION_COOKIE, sessionId));
        assertTrue(interceptor.preHandle(cookieRequest, new MockHttpServletResponse(), new Object()));
    }

    // ==================== 登录 / Set-Cookie / 状态不泄漏 ====================

    @Test
    void loginSetsHardenedHttpOnlySessionCookie() throws Exception {
        MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
        ResponseEntity<Map<String, Object>> wrong = sessionController.login(Map.of("token", "not-the-token"),
                request("POST", "/api/session/login"), wrongResponse);
        assertEquals(401, wrong.getStatusCode().value());
        assertEquals("INVALID_TOKEN", wrong.getBody().get("code"));
        assertEquals(0, authService.isLoginThrottled("127.0.0.1") ? 1 : 0, "一次失败不应触发节流");

        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        ResponseEntity<Map<String, Object>> login = sessionController.login(Map.of("token", token),
                request("POST", "/api/session/login"), loginResponse);
        assertEquals(200, login.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) login.getBody().get("data");
        assertEquals(true, data.get("authenticated"));
        assertNotNull(data.get("expiresAt"));

        String setCookie = loginResponse.getHeader("Set-Cookie");
        assertNotNull(setCookie, "登录成功必须下发会话 Cookie");
        assertTrue(setCookie.contains(LocalAuthService.SESSION_COOKIE + "="), setCookie);
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie);
        assertTrue(setCookie.contains("Path=/"), setCookie);
        assertTrue(setCookie.contains("Max-Age="), setCookie);
        assertFalse(setCookie.contains("Secure"), "本机 http 回环不要求 Secure");

        String sessionId = loginResponse.getCookie(LocalAuthService.SESSION_COOKIE).getValue();
        assertNotNull(sessionId);
        assertTrue(authService.isSessionValid(sessionId));
        assertTrue(setCookie.contains(sessionId));

        // 会话 Cookie 可认证写操作
        MockHttpServletRequest writeRequest = request("POST", WRITE_URI);
        writeRequest.setCookies(new Cookie(LocalAuthService.SESSION_COOKIE, sessionId));
        writeRequest.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertTrue(interceptor.preHandle(writeRequest, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void sessionStatusAndErrorsNeverLeakToken() {
        ResponseEntity<Map<String, Object>> anonymous = sessionController.status(request("GET", "/api/session/status"));
        String body = String.valueOf(anonymous.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) anonymous.getBody().get("data");
        assertEquals(true, data.get("authEnabled"));
        assertEquals(true, data.get("initialized"));
        assertEquals(false, data.get("authenticated"));
        assertTrue(String.valueOf(data.get("tokenFileHint")).contains("local-session-token"));
        assertFalse(body.contains(token), "status 不得返回长期令牌内容");

        // 认证后也不泄漏
        String sessionId = authService.createSession();
        MockHttpServletRequest authed = request("GET", "/api/session/status");
        authed.setCookies(new Cookie(LocalAuthService.SESSION_COOKIE, sessionId));
        ResponseEntity<Map<String, Object>> authenticated = sessionController.status(authed);
        assertEquals(true, ((Map<?, ?>) authenticated.getBody().get("data")).get("authenticated"));
        assertFalse(String.valueOf(authenticated.getBody()).contains(token));
    }

    @Test
    void wrongTokenIsRejectedAndThrottledAfterFiveFailures() {
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<Map<String, Object>> failed = sessionController.login(Map.of("token", "wrong-" + i),
                    request("POST", "/api/session/login"), new MockHttpServletResponse());
            assertEquals(401, failed.getStatusCode().value());
            assertEquals("INVALID_TOKEN", failed.getBody().get("code"));
        }
        assertTrue(authService.isLoginThrottled("127.0.0.1"));

        MockHttpServletResponse throttledResponse = new MockHttpServletResponse();
        ResponseEntity<Map<String, Object>> throttled = sessionController.login(Map.of("token", token),
                request("POST", "/api/session/login"), throttledResponse);
        assertEquals(429, throttled.getStatusCode().value());
        assertEquals("TOO_MANY_ATTEMPTS", throttled.getBody().get("code"));
        assertEquals(0, throttledResponse.getHeaderNames().size() > 0
                && throttledResponse.getHeader("Set-Cookie") != null ? 1 : 0, "被节流时不得发会话 Cookie");
    }

    @Test
    void loginWithoutTokenBodyIsRejected() {
        assertEquals(401, sessionController.login(null, request("POST", "/api/session/login"),
                new MockHttpServletResponse()).getStatusCode().value());
        Map<String, Object> empty = new HashMap<>();
        empty.put("token", " ");
        assertEquals(401, sessionController.login(empty, request("POST", "/api/session/login"),
                new MockHttpServletResponse()).getStatusCode().value());
    }

    // ==================== CSRF / 会话头 / 登出 ====================

    @Test
    void writeRequiresCsrfHeaderEvenWhenAuthenticated() throws Exception {
        String sessionId = authService.createSession();
        MockHttpServletRequest withoutCsrf = request("POST", WRITE_URI);
        withoutCsrf.setCookies(new Cookie(LocalAuthService.SESSION_COOKIE, sessionId));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(withoutCsrf, response, new Object()));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("CSRF_HEADER_MISSING"));

        MockHttpServletRequest wrongCsrf = request("POST", WRITE_URI);
        wrongCsrf.setCookies(new Cookie(LocalAuthService.SESSION_COOKIE, sessionId));
        wrongCsrf.addHeader(LocalAuthService.CSRF_HEADER, "xmlhttprequest");
        assertFalse(interceptor.preHandle(wrongCsrf, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest correct = request("POST", WRITE_URI);
        correct.setCookies(new Cookie(LocalAuthService.SESSION_COOKIE, sessionId));
        correct.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertTrue(interceptor.preHandle(correct, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void sessionTokenHeaderCanAuthenticateForApiClients() throws Exception {
        String sessionId = authService.createSession();
        MockHttpServletRequest request = request("POST", WRITE_URI);
        request.addHeader(LocalAuthService.SESSION_HEADER, sessionId);
        request.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()),
                "X-Session-Token 会话头应可供 curl/API 客户端认证");

        MockHttpServletRequest badHeader = request("POST", WRITE_URI);
        badHeader.addHeader(LocalAuthService.SESSION_HEADER, "not-a-session");
        badHeader.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertFalse(interceptor.preHandle(badHeader, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void logoutClearsCookieAndInvalidatesSession() throws Exception {
        String sessionId = authService.createSession();
        MockHttpServletRequest logoutRequest = request("POST", "/api/session/logout");
        logoutRequest.setCookies(new Cookie(LocalAuthService.SESSION_COOKIE, sessionId));
        MockHttpServletResponse logoutResponse = new MockHttpServletResponse();
        ResponseEntity<Map<String, Object>> logout = sessionController.logout(logoutRequest, logoutResponse);

        assertEquals(200, logout.getStatusCode().value());
        assertEquals(false, ((Map<?, ?>) logout.getBody().get("data")).get("authenticated"));
        String setCookie = logoutResponse.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains(LocalAuthService.SESSION_COOKIE + "="), setCookie);
        assertTrue(setCookie.contains("Max-Age=0") || setCookie.contains("Expires=Thu, 01 Jan 1970"), setCookie);
        assertFalse(authService.isSessionValid(sessionId), "登出后会话必须失效");

        // 旧 Cookie 再访问写接口 → 401
        MockHttpServletRequest stale = request("POST", WRITE_URI);
        stale.setCookies(new Cookie(LocalAuthService.SESSION_COOKIE, sessionId));
        stale.addHeader(LocalAuthService.CSRF_HEADER, LocalAuthService.CSRF_HEADER_VALUE);
        assertFalse(interceptor.preHandle(stale, new MockHttpServletResponse(), new Object()));
    }

    // ==================== 令牌文件 ====================

    @Test
    void tokenFileIsGeneratedOnceAndReused() throws Exception {
        assertTrue(Files.isRegularFile(tokenFile));
        String first = Files.readString(tokenFile).trim();
        assertTrue(first.length() >= 20, "本地令牌应为高熵随机串（Base64URL）");
        assertTrue(first.matches("[A-Za-z0-9_-]+"), "令牌应为 Base64URL 无填充字符");

        LocalAuthService restarted = newService(true, tokenFile);
        assertTrue(restarted.verifyLocalToken(first), "重启后应复用同一令牌文件");
        assertFalse(restarted.verifyLocalToken(first + "x"));
        assertFalse(restarted.verifyLocalToken(""));
        assertEquals(first, Files.readString(tokenFile).trim(), "已存在令牌文件不得被覆盖");
    }

    // ==================== 工具 ====================

    private static LocalAuthService newService(boolean enabled, Path file) throws Exception {
        LocalAuthService service = new LocalAuthService();
        ReflectionTestUtils.setField(service, "authEnabled", enabled);
        ReflectionTestUtils.setField(service, "tokenFile", file.toString());
        ReflectionTestUtils.setField(service, "sessionTtlHours", 168L);
        ReflectionTestUtils.setField(service, "requireCsrfHeader", true);
        ReflectionTestUtils.invokeMethod(service, "initialize");
        return service;
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
