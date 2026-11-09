package com.offerchaser.application.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 平台开关网关壳。
 *
 * <p>项目当前只接入 BOSS 直聘，已不存在其它平台 API 前缀需要按配置拦截。
 * 保留这个过滤器位置，避免以后重新增加平台时改动全局过滤器链；
 * 当前行为是：除 OPTIONS 交给 CORS 外，所有请求直接放行。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class PlatformGateFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 预检请求交给 CORS 过滤器处理，不在这里拦，否则浏览器读不到跨域错误头
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        filterChain.doFilter(request, response);
    }
}
