package com.offerchaser.application.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段 B B5：本机会话认证拦截器。
 * <p>
 * 规则：
 * <ul>
 *   <li>只读 GET/HEAD/OPTIONS 放行（本地回环，SSE 需要）；</li>
 *   <li>例外：Cookie 接口任何方法都必须认证；</li>
 *   <li>所有写方法必须认证（登录接口除外）；</li>
 *   <li>写方法还要求 X-Requested-With: offerchaser（防跨站表单 CSRF，登录接口除外）。</li>
 * </ul>
 * <p>
 * P0 安全修复（路径归一化，见 2026-11 审计 P0-1）：路径判定必须与 Spring MVC 的路由匹配口径一致，
 * 否则 {@code GET /api/%63ookie}、{@code GET /api/cookie;x=1} 这类请求会被 MVC 路由到真实接口，
 * 但拦截器按原始 URI 判断却认不出“这是敏感路径”，从而被只读放行。详见 {@link #resolveLookupPath}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LocalAuthService localAuthService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!localAuthService.isAuthEnabled()) {
            return true;
        }
        // P0：使用与 MVC 相同的“解码 + 去分号路径参数”口径，不能用原始 getRequestURI()
        String path = resolveLookupPath(request);
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        if (isPublicSessionPath(path)) {
            return true;
        }
        String method = request.getMethod() == null ? "GET" : request.getMethod().toUpperCase();
        boolean readOnly = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        boolean cookieEndpoint = path.equals("/api/cookie") || path.startsWith("/api/cookie/");
        // GET 但实际会触发维护/浏览器动作的接口，同样按敏感操作处理
        boolean sensitiveRead = path.equals("/api/boss/reload")
                || path.startsWith("/api/playwright/");
        if (readOnly && !cookieEndpoint && !sensitiveRead) {
            return true;
        }
        if (!localAuthService.isAuthenticated(request)) {
            writeJson(response, 401, false, "UNAUTHORIZED", "需要本机会话登录");
            return false;
        }
        if (!readOnly && localAuthService.isRequireCsrfHeader()
                && !LocalAuthService.CSRF_HEADER_VALUE.equals(request.getHeader(LocalAuthService.CSRF_HEADER))) {
            writeJson(response, 403, false, "CSRF_HEADER_MISSING",
                    "写操作必须带 " + LocalAuthService.CSRF_HEADER + ": " + LocalAuthService.CSRF_HEADER_VALUE);
            return false;
        }
        return true;
    }

    private static boolean isPublicSessionPath(String path) {
        return path.equals("/api/session/status")
                || path.equals("/api/session/login")
                || path.equals("/api/session/logout");
    }

    /**
     * P0 安全修复（路径归一化）：取“与 Spring MVC 路由匹配同一口径”的应用内路径。
     * <p>
     * 旧实现直接使用 {@link HttpServletRequest#getRequestURI()}（原始、未解码、保留 {@code ;xxx}
     * 路径参数），而 Spring MVC 的 PathPattern 匹配使用“解码后的路径 + 忽略分号路径参数”：
     * <ul>
     *   <li>{@code GET /api/%63ookie} → MVC 命中 {@code /api/cookie}（CookieController，敏感）；</li>
     *   <li>{@code GET /api/cookie;x=1} → MVC 同样命中 {@code /api/cookie}；</li>
     *   <li>{@code GET /api/boss/%72eload}、{@code GET /api/playwright;x/diagnose} 同理。</li>
     * </ul>
     * 旧代码按原始 URI 判断上述路径时，既不是 {@code /api/cookie} 也不是
     * {@code /api/playwright/...}，于是被“只读 GET 放行”规则放行 —— 未认证即可访问敏感接口。
     * <p>
     * 这里改用 Spring 自带的 {@link UrlPathHelper#getPathWithinApplication(HttpServletRequest)}
     * （{@code defaultInstance}：urlDecode=true、removeSemicolonContent=true），与 MVC 的解码/去
     * 分号口径一致；归一化失败时回退原始 URI（宁可多要求认证，也不能放行敏感路径）。
     *
     * @param request 当前请求
     * @return 以 {@code /} 开头的应用内路径，永不为 null
     */
    @SuppressWarnings("deprecation")
    private static String resolveLookupPath(HttpServletRequest request) {
        try {
            String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            if (path != null && !path.isEmpty()) {
                return path.charAt(0) == '/' ? path : "/" + path;
            }
        } catch (RuntimeException normalizationFailure) {
            // 极端容器/畸形 URI 下归一化可能失败：回退原始 URI，保持 fail-closed 方向
            log.debug("请求路径归一化失败，回退原始 URI：{}", normalizationFailure.getMessage());
        }
        String fallback = request.getRequestURI();
        if (fallback == null || fallback.isEmpty()) {
            return "/";
        }
        return fallback.charAt(0) == '/' ? fallback : "/" + fallback;
    }

    private static void writeJson(HttpServletResponse response, int status, boolean success,
                                  String code, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", success);
        body.put("code", code);
        body.put("message", message);
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
