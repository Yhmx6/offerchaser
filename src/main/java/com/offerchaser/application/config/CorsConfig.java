package com.offerchaser.application.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理台 CORS 配置：只放行配置项里显式列出的来源。
 * <p>
 * 不使用 {@code *}：本服务只监听本机，放开任意来源等于让任意网页都能读写管理接口。
 * 允许的方法/请求头也显式列出，只包含管理台实际会用到的值。
 */
@Configuration
@Slf4j
public class CorsConfig {

    /** 默认来源：本机 6866（前端 dev/静态服务）与 9527（后端）。 */
    private static final String DEFAULT_ORIGINS =
            "http://localhost:6866,http://127.0.0.1:6866,http://localhost:9527,http://127.0.0.1:9527";
    /** 显式允许的请求方法。 */
    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
    /** 显式允许的请求头。 */
    private static final List<String> ALLOWED_HEADERS =
            List.of("Accept", "Authorization", "Content-Type", "Origin",
                    "X-Requested-With", "Cache-Control", "Pragma");
    /** 预检结果缓存时间（秒）。 */
    private static final long PREFLIGHT_MAX_AGE_SECONDS = 3600L;
    /** 管理台所有路径统一套用该 CORS 规则。 */
    private static final String CORS_PATH_PATTERN = "/**";

    private final String configuredOrigins;

    public CorsConfig(@Value("${offerchaser.admin.cors-allowed-origins:" + DEFAULT_ORIGINS + "}")
                      String allowedOrigins) {
        this.configuredOrigins = allowedOrigins;
    }

    /**
     * CORS 过滤器注册为最高优先级：即使后面的过滤器直接返回 403，
     * 浏览器也能拿到跨域响应头、看到真实错误。
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = buildCorsConfiguration();
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(CORS_PATH_PATTERN, config);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** 组装允许来源/方法/请求头；来源为空时只告警，交给浏览器拒绝跨域。 */
    private CorsConfiguration buildCorsConfiguration() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = splitOrigins(configuredOrigins);
        if (origins.isEmpty()) {
            log.warn("未配置任何允许的跨域来源（offerchaser.admin.cors-allowed-origins 为空），所有跨域请求都会被拒绝");
        }
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        // 携带 Cookie 时 Origin 不能为 *，因此来源必须逐项配置
        config.setAllowCredentials(true);
        config.setMaxAge(PREFLIGHT_MAX_AGE_SECONDS);
        return config;
    }

    /** 逗号分隔来源拆成列表：忽略空项，显式挡掉 {@code *}。 */
    private List<String> splitOrigins(String raw) {
        List<String> origins = new ArrayList<>();
        if (raw == null) {
            return origins;
        }
        for (String piece : raw.split(",")) {
            String origin = piece.trim();
            if (origin.isEmpty()) {
                continue;
            }
            if ("*".equals(origin)) {
                log.error("offerchaser.admin.cors-allowed-origins 不允许使用 *，已忽略该配置项");
                continue;
            }
            origins.add(origin);
        }
        return origins;
    }
}
