package com.offerchaser.application.ai;

import com.offerchaser.application.mapper.AiMapper;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiService} 配置校验单元测试：不发真实网络请求，只验证缺失/非法配置会在请求前失败。
 */
class AiServiceConfigValidationTest {

    private ConfigService configService;
    private AiService aiService;

    @BeforeEach
    void setUp() {
        configService = mock(ConfigService.class);
        aiService = new AiService(configService, mock(AiMapper.class));
    }

    @Test
    void validConfigPassesValidation() {
        when(configService.getAiConfigs()).thenReturn(Map.of(
                "BASE_URL", "https://api.openai.com/v1",
                "API_KEY", "sk-test",
                "MODEL", "gpt-4o-mini"));
        assertDoesNotThrow(() -> aiService.validateConfiguration());
    }

    @Test
    void missingConfigThrowsAiConfigException() {
        when(configService.getAiConfigs())
                .thenThrow(new IllegalStateException("缺少必要配置: API_KEY"));

        AiConfigException exception = assertThrows(AiConfigException.class,
                () -> aiService.validateConfiguration());
        assertTrue(exception.getMessage().contains("AI 配置"));
        assertTrue(exception.getMessage().contains("API_KEY"));
    }

    @Test
    void invalidUrlThrowsAiConfigException() {
        when(configService.getAiConfigs()).thenReturn(Map.of(
                "BASE_URL", "not-a-url",
                "API_KEY", "sk-test",
                "MODEL", "gpt-4o-mini"));

        AiConfigException exception = assertThrows(AiConfigException.class,
                () -> aiService.validateConfiguration());
        assertTrue(exception.getMessage().contains("BASE_URL"));
    }

    @Test
    void nonHttpUrlThrowsAiConfigException() {
        when(configService.getAiConfigs()).thenReturn(Map.of(
                "BASE_URL", "ftp://example.com/v1",
                "API_KEY", "sk-test",
                "MODEL", "gpt-4o-mini"));

        AiConfigException exception = assertThrows(AiConfigException.class,
                () -> aiService.validateConfiguration());
        assertTrue(exception.getMessage().contains("http(s)"));
    }

    @Test
    void blankBaseUrlThrowsAiConfigException() {
        when(configService.getAiConfigs()).thenReturn(Map.of(
                "BASE_URL", "   ",
                "API_KEY", "sk-test",
                "MODEL", "gpt-4o-mini"));

        AiConfigException exception = assertThrows(AiConfigException.class,
                () -> aiService.validateConfiguration());
        assertTrue(exception.getMessage().contains("缺少 BASE_URL"));
    }

    @Test
    void sendRequestFailsBeforeNetworkWhenConfigMissing() {
        when(configService.getAiConfigs())
                .thenThrow(new IllegalStateException("缺少必要配置: BASE_URL"));

        // 旧入口与新入口都必须在发出网络请求之前失败
        assertThrows(AiConfigException.class, () -> aiService.sendRequest("你好"));
        assertThrows(AiConfigException.class, () -> aiService.sendRequestWithUsage("你好"));
    }

    @Test
    void blankRequestContentIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> aiService.sendRequestWithUsage("   "));
    }
}
