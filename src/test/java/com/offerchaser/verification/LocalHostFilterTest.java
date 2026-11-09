package com.offerchaser.verification;

import com.offerchaser.application.config.LocalHostFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DNS rebinding 防护：只允许本机回环 Host。 */
@DisplayName("LocalHostFilter：Host 白名单")
class LocalHostFilterTest {

    @Test
    void allowsLoopbackHostsWithAndWithoutPort() throws Exception {
        for (String host : new String[]{
                "127.0.0.1", "127.0.0.1:9527", "localhost", "localhost:6866",
                "[::1]:9527"}) {
            MockHttpServletRequest request = requestWithHost(host);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            new LocalHostFilter().doFilter(request, response, chain);

            assertEquals(200, response.getStatus(), "应放行本机 Host: " + host);
            assertNotNull(chain.getRequest(), "本机 Host 应继续过滤器链: " + host);
        }
    }

    @Test
    void rejectsNonLoopbackAndMalformedHosts() {
        for (String host : new String[]{
                null, "", "   ", "evil.example", "evil.example:9527",
                "localhost.evil.example", "127.0.0.1.evil.example",
                "attacker.test@localhost:9527", "127.0.0.1,evil.example",
                "localhost:9527 evil.example"}) {
            assertFalse(LocalHostFilter.isAllowedHost(host), "必须拒绝 Host: " + host);
        }
    }

    @Test
    void rejectsRebindingRequestWith403AndNoDownstreamCall() throws Exception {
        MockHttpServletRequest request = requestWithHost("attacker.example:9527");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new LocalHostFilter().doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest(), "被拒绝的请求不得进入业务过滤器/控制器");
        assertTrue(response.getContentAsString().contains("HOST_NOT_ALLOWED"));
    }

    @Test
    void allowsHostsOnlyWhenParsedAsLoopback() {
        assertTrue(LocalHostFilter.isAllowedHost("127.0.0.1:9527"));
        assertTrue(LocalHostFilter.isAllowedHost("LOCALHOST:6866"));
        assertFalse(LocalHostFilter.isAllowedHost("localhost."));
        assertFalse(LocalHostFilter.isAllowedHost("127.0.0.1.nip.io:9527"));
    }

    private static MockHttpServletRequest requestWithHost(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boss/status");
        request.setContextPath("");
        if (host != null) {
            request.addHeader("Host", host);
        }
        return request;
    }
}
