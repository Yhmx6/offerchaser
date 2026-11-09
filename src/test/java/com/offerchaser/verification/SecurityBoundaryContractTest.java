package com.offerchaser.verification;

import com.offerchaser.application.config.CorsConfig;
import com.offerchaser.application.config.PlatformGateFilter;
import com.offerchaser.application.entity.ConfigEntity;
import com.offerchaser.application.mapper.ConfigMapper;
import com.offerchaser.application.security.AuthInterceptor;
import com.offerchaser.application.security.LocalAuthService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.application.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全边界验收（T6）：CORS 收紧、回环绑定、密钥掩码、平台开关过滤器的行为级/代码级检查。
 * <p>
 * 不启动整个 Spring 应用：CORS 用真实 {@link CorsFilter} + Mock 请求验证响应头，
 * 过滤器用真实 {@link PlatformGateFilter} 调用 doFilter，密钥掩码直接实例化 ConfigService。
 */
class SecurityBoundaryContractTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path CONFIG_DIR = MAIN_JAVA.resolve("com/offerchaser/application");

    // ==================== CORS ====================

    @Test
    void allowedOriginGetsCredentialedCorsHeaders() throws Exception {
        CorsFilter filter = corsFilter("http://localhost:6866,http://127.0.0.1:9527");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boss/status");
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:6866");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("http://localhost:6866", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
        assertNotNull(chain.getRequest(), "允许来源的请求应继续进入 controller");
        assertFalse("*".equals(response.getHeader("Access-Control-Allow-Origin")));
    }

    @Test
    void disallowedOriginIsRejectedWithoutCorsHeaders() throws Exception {
        CorsFilter filter = corsFilter("http://localhost:6866");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boss/status");
        request.addHeader(HttpHeaders.ORIGIN, "http://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus(), "非允许来源应被 CORS 拒绝");
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
        assertNull(chain.getRequest(), "被拒绝的请求不得进入 controller");
    }

    @Test
    void wildcardOriginConfigIsIgnored() throws Exception {
        // 即使配置里写了 *，parseOrigins 也必须忽略它，不能把来源放开成任意网页
        CorsFilter filter = corsFilter("*,http://localhost:6866");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boss/status");
        request.addHeader(HttpHeaders.ORIGIN, "http://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus(), "配置了 * 也不能放行任意来源");
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void preflightListsExplicitMethodsAndHeaders() throws Exception {
        CorsFilter filter = corsFilter("http://localhost:6866");
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/boss/start");
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:6866");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "Content-Type");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String allowMethods = response.getHeader("Access-Control-Allow-Methods");
        String allowHeaders = response.getHeader("Access-Control-Allow-Headers");
        assertNotNull(allowMethods, "预检响应缺少 Access-Control-Allow-Methods");
        assertNotNull(allowHeaders, "预检响应缺少 Access-Control-Allow-Headers");
        assertFalse(allowMethods.contains("*"), "允许方法不能是 *");
        assertFalse(allowHeaders.contains("*"), "允许请求头不能是 *");
        assertTrue(allowMethods.contains("POST"));
        assertTrue(allowHeaders.contains("Content-Type"));
    }

    @Test
    void noCrossOriginAnnotationsRemainInMainSources() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains("@CrossOrigin")) {
                    offenders.add(MAIN_JAVA.relativize(file).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "仍存在 @CrossOrigin 注解（全局 CORS 已接管且禁止 *）：" + offenders);
    }

    @Test
    void corsConfigSourceExplicitlyRejectsWildcard() throws Exception {
        String source = Files.readString(CONFIG_DIR.resolve("config/CorsConfig.java"));
        assertTrue(source.contains("setAllowedOrigins"), "CorsConfig 应显式设置允许来源");
        assertTrue(source.contains("setAllowCredentials(true)"), "管理台跨域需要凭证，但来源必须白名单");
        assertTrue(source.contains("\"*\".equals(origin)"), "CorsConfig 必须显式挡掉 * 配置");
        assertFalse(source.contains("addAllowedOrigin(\"*\")"));
        assertFalse(source.contains("allowedOriginPatterns"));
    }

    // ==================== 认证路径归一化（P0 回归） ====================
    //
    // 背景（审计 P0-1）：AuthInterceptor 旧实现用原始 getRequestURI() 判断敏感路径，而 Spring MVC
    // 用“解码 + 去分号路径参数”后的路径做路由匹配。于是 GET /api/%63ookie、GET /api/cookie;x=1 等
    // 变体会被 MVC 路由到真实敏感接口，拦截器却识别不出而按“只读 GET”放行 —— 未认证可访问。
    // 下面用 Mock 请求（与 B5AuthContractTest 相同构造方式）锁死修复后的行为。

    @Test
    void encodedOrSemicolonUriCannotBypassAuthForSensitiveReads() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(anonymousAuthService());

        String[] bypassAttempts = {
                "/api/%63ookie",
                "/api/cookie;x=1",
                "/api/boss/%72eload",
                "/api/playwright;x/diagnose"
        };
        for (String uri : bypassAttempts) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean allowed = interceptor.preHandle(request("GET", uri), response, new Object());
            assertFalse(allowed, uri + " 经解码/去分号后会命中敏感接口，未认证必须拒绝");
            assertEquals(401, response.getStatus(), uri + " 未认证必须返回 401");
            String body = response.getContentAsString(StandardCharsets.UTF_8);
            assertTrue(body.contains("UNAUTHORIZED"), uri + " 401 响应应带 UNAUTHORIZED：" + body);
        }
    }

    @Test
    void normalReadOnlyGetsAndPublicSessionPathsKeepOriginalSemantics() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(anonymousAuthService());

        assertTrue(interceptor.preHandle(request("GET", "/api/boss/status"),
                        new MockHttpServletResponse(), new Object()),
                "普通只读 GET 仍应放行（不改变原有只读放行语义）");
        assertTrue(interceptor.preHandle(request("GET", "/api/boss/stream"),
                        new MockHttpServletResponse(), new Object()),
                "SSE 只读 GET 仍应放行");
        assertTrue(interceptor.preHandle(request("POST", "/api/session/login"),
                        new MockHttpServletResponse(), new Object()),
                "登录接口保持公开");
        assertTrue(interceptor.preHandle(request("GET", "/api/session/status"),
                        new MockHttpServletResponse(), new Object()),
                "登录状态接口保持公开");
        assertTrue(interceptor.preHandle(request("POST", "/boss/start"),
                        new MockHttpServletResponse(), new Object()),
                "非 /api 路径不受本拦截器影响");
    }

    @Test
    void writeRequestsStillRequireAuthAfterPathNormalization() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(anonymousAuthService());

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", "/api/boss/start"), response, new Object()),
                "未登录写操作必须拒绝");
        assertEquals(401, response.getStatus());

        // 编码/分号变体同样不能绕过写接口的认证
        for (String uri : new String[]{"/api/%62oss/start", "/api/boss;x/start"}) {
            MockHttpServletResponse encodedResponse = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request("POST", uri), encodedResponse, new Object()),
                    uri + " 未登录写操作必须拒绝");
            assertEquals(401, encodedResponse.getStatus(), uri + " 未登录必须 401");
        }
    }

    @Test
    void authenticatedRequestsPassForNormalizedCookiePath() throws Exception {
        LocalAuthService authService = anonymousAuthService();
        when(authService.isAuthenticated(any())).thenReturn(true);
        AuthInterceptor interceptor = new AuthInterceptor(authService);

        assertTrue(interceptor.preHandle(request("GET", "/api/cookie"),
                        new MockHttpServletResponse(), new Object()),
                "已认证访问 Cookie 接口应放行");
        assertTrue(interceptor.preHandle(request("GET", "/api/%63ookie"),
                        new MockHttpServletResponse(), new Object()),
                "已认证访问编码变体同样应放行（同一规范化路径）");
    }

    // ==================== 回环绑定 / 静态服务 ====================

    @Test
    void staticServerConfigurationBindsConfiguredLoopbackAddress() throws Exception {
        String source = Files.readString(CONFIG_DIR.resolve("config/StaticServerConfiguration.java"));
        assertTrue(source.contains("offerchaser.static-server.port"), "静态服务端口必须读配置");
        assertTrue(source.contains("offerchaser.static-server.address"), "静态服务绑定地址必须读配置");
        assertTrue(source.contains("protocol.setAddress"), "必须把地址设置到 Tomcat protocol handler 才真正生效");
        assertTrue(source.contains("DEFAULT_BIND_ADDRESS = \"127.0.0.1\""), "默认必须是回环地址");
        assertFalse(source.contains("\"0.0.0.0\""), "不得把绑定地址写成所有网卡（0.0.0.0）");
    }

    @Test
    void aiServiceUsesRequestLevelTimeout() throws Exception {
        String source = Files.readString(MAIN_JAVA.resolve("com/offerchaser/application/service/AiService.java"));
        assertTrue(source.contains("request-timeout-seconds"), "必须读取请求级超时配置");
        assertTrue(source.contains("connect-timeout-seconds"), "必须保留连接超时配置");
        assertTrue(source.contains(".timeout(Duration.ofSeconds"), "必须用 HttpRequest.Builder.timeout 设置请求级超时");
        assertTrue(source.contains("sendRequestWithUsage"), "必须保留带 usage 的发送方法");
        assertTrue(source.contains("public String sendRequest(String content)"),
                "AiConfigController 依赖的旧方法签名不得删除");
        assertFalse(source.contains("System.out"), "日志不得使用 System.out");
    }

    // ==================== 迁移安全（BossService） ====================

    @Test
    void bossServiceMigrationIsAdditiveAndRefreshDoesNotVacuum() throws Exception {
        String source = Files.readString(
                MAIN_JAVA.resolve("com/offerchaser/application/service/BossService.java"));
        String migrationSource = Files.readString(
                MAIN_JAVA.resolve("com/offerchaser/application/persistence/SchemaMigrationService.java"));
        assertTrue(migrationSource.contains("ALTER TABLE boss_data ADD COLUMN"),
                "列调整必须使用只增不减的 ALTER TABLE ADD COLUMN");
        assertFalse(migrationSource.contains("DROP TABLE"), "迁移不得 DROP 任何表");
        assertFalse(migrationSource.contains("RENAME TO"), "迁移不得 RENAME 表");
        assertFalse(source.contains("VACUUM INTO"), "业务服务不得直接执行 VACUUM 备份");

        int reloadIndex = source.indexOf("public Map<String, Object> reloadBossData()");
        assertTrue(reloadIndex > 0, "找不到 reloadBossData 方法");
        String reloadBody = source.substring(reloadIndex,
                Math.min(source.length(), reloadIndex + 900));
        assertFalse(reloadBody.contains("VACUUM"), "普通刷新不得执行 VACUUM");
        assertFalse(reloadBody.contains("wal_checkpoint"), "普通刷新不得执行 wal_checkpoint");
        assertTrue(reloadBody.contains("SELECT COUNT(*) FROM boss_data"),
                "刷新只应统计总数，不做数据库维护");
    }

    // ==================== 平台开关过滤器 ====================

    @Test
    void platformGateIsPassThroughForBossOnly() throws Exception {
        PlatformGateFilter filter = new PlatformGateFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boss/status");
        request.setContextPath("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "BOSS-only 过滤器应放行请求");
    }

    @Test
    void platformGatePassesEnabledPlatformAndPreflight() throws Exception {
        PlatformGateFilter filter = new PlatformGateFilter();

        MockHttpServletRequest enabled = new MockHttpServletRequest("GET", "/api/boss/status");
        MockHttpServletResponse enabledResponse = new MockHttpServletResponse();
        MockFilterChain enabledChain = new MockFilterChain();
        filter.doFilter(enabled, enabledResponse, enabledChain);
        assertNotNull(enabledChain.getRequest(), "已启用平台（boss）接口应放行");

        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/boss/status");
        MockHttpServletResponse preflightResponse = new MockHttpServletResponse();
        MockFilterChain preflightChain = new MockFilterChain();
        filter.doFilter(preflight, preflightResponse, preflightChain);
        assertNotNull(preflightChain.getRequest(), "OPTIONS 预检必须交给 CORS 处理，不能被平台开关拦截");
    }

    @Test
    void platformGateNoLongerBlocksLegacyPrefixes() throws Exception {
        PlatformGateFilter filter = new PlatformGateFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/job/list");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    // ==================== 配置密钥掩码 ====================

    @Test
    void secretConfigValuesAreMaskedOnRead() {
        ConfigMapper mapper = mock(ConfigMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                configEntity(1L, "API_KEY", "real-secret"),
                configEntity(2L, "MODEL", "deepseek-chat"),
                configEntity(3L, "boss_cookie", "sessionid=abc"),
                configEntity(4L, "EMPTY_TOKEN", "")));
        ConfigEntity secretEntity = configEntity(1L, "API_KEY", "real-secret");
        when(mapper.selectOne(any())).thenReturn(secretEntity);
        ConfigService service = configService(mapper);

        Map<String, String> masked = service.getAllConfigsMasked();

        assertEquals(ConfigService.MASKED_VALUE, masked.get("API_KEY"));
        assertEquals("deepseek-chat", masked.get("MODEL"), "非密钥配置必须原样返回");
        assertEquals(ConfigService.MASKED_VALUE, masked.get("boss_cookie"), "键名含 COOKIE 也按密钥处理");
        assertEquals("", masked.get("EMPTY_TOKEN"), "空值无需掩码");

        ConfigEntity maskedSingle = service.getConfigByKeyMasked("API_KEY");
        assertNotNull(maskedSingle);
        assertEquals(ConfigService.MASKED_VALUE, maskedSingle.getConfigValue());
        assertEquals("real-secret",
                service.getConfigByKey("API_KEY").getConfigValue(), "掩码只能发生在返回副本上");
        assertFalse(ConfigService.isSecretKey("BASE_URL"));
        assertFalse(ConfigService.isSecretKey("MODEL"));
        assertTrue(ConfigService.isSecretKey("API_KEY"));
        assertTrue(ConfigService.isSecretKey("x-auth-token"));
    }

    @Test
    void submittingMaskedValueKeepsRealSecret() {
        ConfigMapper mapper = mock(ConfigMapper.class);
        ConfigEntity entity = configEntity(1L, "API_KEY", "real-secret");
        when(mapper.selectOne(any())).thenReturn(entity);
        when(mapper.updateById(any(ConfigEntity.class))).thenReturn(1);
        ConfigService service = configService(mapper);

        assertTrue(service.updateConfig("API_KEY", ConfigService.MASKED_VALUE));
        assertTrue(service.updateConfig("API_KEY", null));
        assertEquals("real-secret", entity.getConfigValue(), "提交掩码/null 必须保持原密钥，不能被掩码覆盖");
        verify(mapper, never()).updateById(any(ConfigEntity.class));

        // 显式空字符串 = 清空；新值 = 正常写入
        assertTrue(service.updateConfig("API_KEY", ""));
        assertEquals("", entity.getConfigValue());
        assertTrue(service.updateConfig("API_KEY", "new-secret"));
        assertEquals("new-secret", entity.getConfigValue());
        verify(mapper, times(2)).updateById(any(ConfigEntity.class));
    }

    @Test
    void deleteConfigEndpointUsesServiceDeleteAndReportsMissing() {
        ConfigMapper mapper = mock(ConfigMapper.class);
        when(mapper.delete(any())).thenReturn(1, 0);
        ConfigService service = configService(mapper);

        assertTrue(service.deleteConfig("API_KEY"));
        assertFalse(service.deleteConfig("NOT_EXISTS"), "不存在的键删除应返回 false（接口据此返回 404）");
        assertFalse(service.deleteConfig("  "));
        assertTrue(Files.exists(CONFIG_DIR.resolve("controller/ConfigController.java")));
        try {
            String controller = Files.readString(CONFIG_DIR.resolve("controller/ConfigController.java"));
            assertTrue(controller.contains("@DeleteMapping"), "配置接口应提供 DELETE 端点");
            assertTrue(controller.contains("getAllConfigsMasked"), "GET 全量配置必须走掩码方法");
            assertTrue(controller.contains("getConfigByKeyMasked"), "GET 单个配置必须走掩码方法");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void newBossEndpointsAndCapabilitiesDeclared() throws Exception {
        String bossController = Files.readString(CONFIG_DIR.resolve("controller/BossController.java"));
        assertTrue(bossController.contains("/verification/resume"));
        assertTrue(bossController.contains("/force-reset"));
        assertTrue(bossController.contains("/delivery/summary"));
        assertTrue(bossController.contains("confirm"), "force-reset 必须要求显式 confirm");

        String capabilities = Files.readString(CONFIG_DIR.resolve("controller/CapabilitiesController.java"));
        assertTrue(capabilities.contains("/capabilities"));
        assertTrue(capabilities.contains("enabledPlatforms"));
        assertTrue(capabilities.contains("verificationResume"));
        assertTrue(capabilities.contains("strictAi"));
    }

    // ==================== 工具 ====================

    /** 未认证的本机会话服务（authEnabled=true，未登录，写操作要求 CSRF 头）。 */
    private static LocalAuthService anonymousAuthService() {
        LocalAuthService authService = mock(LocalAuthService.class);
        when(authService.isAuthEnabled()).thenReturn(true);
        when(authService.isAuthenticated(any())).thenReturn(false);
        when(authService.isRequireCsrfHeader()).thenReturn(true);
        return authService;
    }

    /**
     * 构造贴近真实请求的 Mock 请求：显式设置 requestURI（保留 %XX 编码与 ; 路径参数原文），
     * 空 contextPath，从而验证拦截器是否真的做了路径归一化。
     */
    private static MockHttpServletRequest request(String method, String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        request.setRequestURI(requestUri);
        request.setContextPath("");
        return request;
    }

    private static CorsFilter corsFilter(String allowedOrigins) {
        FilterRegistrationBean<CorsFilter> registration = new CorsConfig(allowedOrigins).corsFilter();
        return registration.getFilter();
    }

    private static ConfigEntity configEntity(Long id, String key, String value) {
        ConfigEntity entity = new ConfigEntity();
        entity.setId(id);
        entity.setConfigKey(key);
        entity.setConfigValue(value);
        return entity;
    }

    private static ConfigService configService(ConfigMapper mapper) {
        return new ConfigService(mapper, mock(BossService.class));
    }
}
