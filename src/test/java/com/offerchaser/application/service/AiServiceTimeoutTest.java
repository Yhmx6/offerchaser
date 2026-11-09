package com.offerchaser.application.service;

import com.offerchaser.application.mapper.AiMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 回归：AI 接口返回响应头后响应体停滞时，不允许无限期阻塞投递线程。
 * <p>
 * 用本机临时 HTTP Server 模拟"响应体一直不结束"，断言在请求级超时 + 兜底宽限内失败。
 * 不访问任何外网。
 */
class AiServiceTimeoutTest {

    @Test
    void stalledResponseBodyFailsWithinHardTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                // 只写一小段 JSON 开头，然后保持连接不结束：模拟响应体读取停滞
                exchange.getResponseBody().write("{\"id\":\"stalled\"".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                Thread.sleep(30_000);
            } catch (Exception ignored) {
                // 客户端取消连接后 handler 会被打断，直接退出
            } finally {
                try {
                    exchange.close();
                } catch (Exception ignored) {
                }
            }
        });
        server.start();
        try {
            AiService service = new AiService(mock(ConfigService.class), mock(AiMapper.class));
            ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 1);
            ReflectionTestUtils.setField(service, "connectTimeoutSeconds", 1);

            Method sendHttp = AiService.class.getDeclaredMethod("sendHttp",
                    String.class, String.class, String.class);
            sendHttp.setAccessible(true);
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";

            long startedAt = System.nanoTime();
            InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                    () -> sendHttp.invoke(service, endpoint, "test-key", "{}"));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertTrue(elapsedMs < 12_000, "响应体停滞必须在总超时内失败，实际耗时 " + elapsedMs + "ms");
            Throwable cause = exception.getCause();
            assertTrue(cause instanceof RuntimeException, "应转换为 RuntimeException");
            assertTrue(cause.getMessage().contains("AI 请求超时"),
                    "失败原因应为 AI 请求超时，实际：" + cause.getMessage());
        } finally {
            server.stop(0);
        }
    }
}
