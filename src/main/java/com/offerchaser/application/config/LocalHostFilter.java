package com.offerchaser.application.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 仅允许本机回环 Host 的请求。
 *
 * <p>管理台和 API 都只应通过 {@code 127.0.0.1} / {@code localhost} / {@code ::1}
 * 访问。仅绑定回环地址并不能阻止 DNS rebinding：攻击者可以把自己的域名解析到
 * 127.0.0.1，让浏览器以为请求仍属于攻击者页面，从而读取本机只读 API。这里直接
 * 校验 HTTP Host 头，非本机 Host 一律 403。</p>
 *
 * <p>不做反向代理支持；如果以后确实需要代理，应显式增加受信任 Host 白名单，
 * 不能关闭此过滤器。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class LocalHostFilter extends OncePerRequestFilter {

    /** 允许的本机 Host 名称；IPv6 去掉方括号后比较。 */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String host = request.getHeader("Host");
        if (isAllowedHost(host)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("拒绝非本机 Host 的请求 | method={} | uri={} | host={}",
                request.getMethod(), request.getRequestURI(), host);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json; charset=utf-8");
        response.getWriter().write("{\"success\":false,\"code\":\"HOST_NOT_ALLOWED\","
                + "\"message\":\"仅允许通过本机回环地址访问\"}");
    }

    /**
     * 解析 Host 头，只接受允许的回环主机名。
     * Host 头没有端口也可以；IPv6 必须使用方括号形式。
     */
    public static boolean isAllowedHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return false;
        }
        String host = rawHost.trim();
        // Host 头中不允许出现 userinfo / 逗号 / 空白，提前拒绝畸形值。
        if (host.contains("@") || host.contains(",") || host.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = new URI("http://" + host);
            String parsed = uri.getHost();
            if (parsed == null || uri.getRawUserInfo() != null) {
                return false;
            }
            String normalized = parsed.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            return ALLOWED_HOSTS.contains(normalized);
        } catch (Exception invalidHost) {
            return false;
        }
    }
}
