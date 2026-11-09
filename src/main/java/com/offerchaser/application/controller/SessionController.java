package com.offerchaser.application.controller;

import com.offerchaser.application.security.LocalAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 阶段 B B5：本机会话登录/登出/状态。
 * <p>
 * 任何响应都不返回长期令牌内容；只返回是否已认证与令牌文件路径提示。
 */
@Slf4j
@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class SessionController {

    private final LocalAuthService localAuthService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        Map<String, Object> data = new HashMap<>();
        data.put("authEnabled", localAuthService.isAuthEnabled());
        data.put("initialized", localAuthService.isInitialized());
        data.put("authenticated", localAuthService.isAuthenticated(request));
        data.put("tokenFileHint", localAuthService.tokenFileHint());
        return ok(data);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        if (!localAuthService.isAuthEnabled()) {
            return ok(Map.of("authenticated", true, "authEnabled", false));
        }
        String ip = clientIp(request);
        if (localAuthService.isLoginThrottled(ip)) {
            return ResponseEntity.status(429).body(failure("TOO_MANY_ATTEMPTS",
                    "登录失败次数过多，请 1 分钟后再试"));
        }
        if (!localAuthService.isInitialized()) {
            return ResponseEntity.status(503).body(failure("AUTH_NOT_INITIALIZED",
                    "本地令牌未初始化，请查看启动日志中的令牌文件路径"));
        }
        String token = body == null || body.get("token") == null ? null : String.valueOf(body.get("token")).trim();
        if (token == null || token.isEmpty() || !localAuthService.verifyLocalToken(token)) {
            localAuthService.recordLoginFailure(ip);
            return ResponseEntity.status(401).body(failure("INVALID_TOKEN", "令牌不正确"));
        }
        localAuthService.clearLoginFailures(ip);
        String sessionId = localAuthService.createSession();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(LocalAuthService.SESSION_COOKIE, sessionId)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .sameSite("Strict")
                .maxAge(localAuthService.sessionMaxAgeSeconds())
                .build()
                .toString());
        Instant expiresAt = localAuthService.sessionExpiresAt(sessionId);
        Map<String, Object> data = new HashMap<>();
        data.put("authenticated", true);
        data.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        log.info("本机会话登录成功 | ip={}", ip);
        return ok(data);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            String sessionId = localAuthService.sessionIdFromCookie(request);
            localAuthService.invalidateSession(sessionId);
        } catch (Exception e) {
            log.debug("注销会话失败（忽略）：{}", e.getMessage());
        }
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(LocalAuthService.SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(false)
                .path("/")
                .sameSite("Strict")
                .maxAge(0)
                .build()
                .toString());
        Map<String, Object> data = new HashMap<>();
        data.put("authenticated", false);
        return ok(data);
    }

    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private static Map<String, Object> failure(String code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", code);
        response.put("message", message);
        return response;
    }
}
