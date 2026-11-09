package com.offerchaser.application.ai;

import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.service.AiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiMatchService} 单元测试：不启动 Spring、不发真实网络请求，AiService 用 Mockito 替身。
 */
class AiMatchServiceTest {

    private AiService aiService;
    private AiMatchService matchService;
    private AiEntity aiConfig;

    @BeforeEach
    void setUp() {
        aiService = mock(AiService.class);
        matchService = new AiMatchService(aiService);
        aiConfig = new AiEntity();
        aiConfig.setIntroduce("三年 Java 后端经验");
        aiConfig.setPrompt("岗位：{{job_name}}\n描述：{{job_description}}\n介绍：{{introduce}}\n参考：{{say_hi}}");
    }

    @Test
    void matchResultCarriesMessageAndUsageAndPromptContract() {
        when(aiService.sendRequestWithUsage(anyString())).thenReturn(new AiCallResult(
                "{\"decision\":\"PASS\",\"analysis\":\"JD 与经历匹配\","
                        + "\"message\":\"您好，我有三年 Java 后端经验，希望能与您进一步沟通。\"}",
                "gpt-test", 100, 20, 1234L, true));

        AiMatchResult result = matchService.evaluate(aiConfig, "Java 开发",
                "负责后端服务开发，要求三年经验", "Java", "您好", 120);

        assertEquals(AiOutcome.MATCH, result.outcome());
        assertTrue(result.sendable());
        assertEquals("您好，我有三年 Java 后端经验，希望能与您进一步沟通。", result.message());
        assertEquals("gpt-test", result.model());
        assertEquals(100, result.promptTokens());
        assertEquals(20, result.completionTokens());
        assertEquals(1234L, result.durationMs());

        verify(aiService).validateConfiguration();
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).sendRequestWithUsage(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        // 模板变量已渲染
        assertTrue(prompt.contains("岗位：Java 开发"), "提示词应包含渲染后的职位名称");
        assertTrue(prompt.contains("介绍：三年 Java 后端经验"), "提示词应包含渲染后的个人介绍");
        assertTrue(prompt.contains("描述：负责后端服务开发，要求三年经验"));
        assertTrue(prompt.contains("参考：您好"));
        // 固定输出契约已追加
        assertTrue(prompt.contains("JSON"), "提示词应包含固定输出契约");
        assertTrue(prompt.contains("PASS"));
        assertTrue(prompt.contains("REJECT"));
        assertTrue(prompt.contains("REVIEW"));
        assertTrue(prompt.contains("20-120"));
        assertTrue(prompt.contains("JD"));
    }

    @Test
    void matchedMessageIsNormalizedBeforeReturn() {
        when(aiService.sendRequestWithUsage(anyString())).thenReturn(new AiCallResult(
                "{\"decision\":\"PASS\",\"analysis\":\"匹配\","
                        + "\"message\":\"您好，  我是 Java 工程师，\\n期待与您沟通。\"}",
                "gpt-test", 10, 5, 100L, true));

        AiMatchResult result = matchService.evaluate(aiConfig, "Java 开发", "JD", "Java", "您好", 120);

        assertEquals(AiOutcome.MATCH, result.outcome());
        assertEquals("您好， 我是 Java 工程师， 期待与您沟通。", result.message());
    }

    @Test
    void overlongMatchedMessageBecomesError() {
        String longMessage = "您好，" + "很".repeat(80);
        when(aiService.sendRequestWithUsage(anyString())).thenReturn(new AiCallResult(
                "{\"decision\":\"PASS\",\"analysis\":\"匹配\",\"message\":\"" + longMessage + "\"}",
                "gpt-test", 10, 5, 100L, true));

        AiMatchResult result = matchService.evaluate(aiConfig, "Java 开发", "JD", "Java", "您好", 50);

        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiMatchService.AI_MESSAGE_INVALID, result.errorCode());
        assertNull(result.message());
        assertFalse(result.sendable());
        assertEquals("gpt-test", result.model());
    }

    @Test
    void parserErrorIsPropagatedWithoutMessage() {
        when(aiService.sendRequestWithUsage(anyString())).thenReturn(new AiCallResult(
                "这是个纯文本回复，不是 JSON", "gpt-test", 10, 5, 100L, true));

        AiMatchResult result = matchService.evaluate(aiConfig, "Java 开发", "JD", "Java", "您好", 120);

        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
        assertFalse(result.sendable());
    }

    @Test
    void blankAiContentBecomesError() {
        when(aiService.sendRequestWithUsage(anyString())).thenReturn(
                new AiCallResult("   ", "gpt-test", 10, 5, 100L, true));

        AiMatchResult result = matchService.evaluate(aiConfig, "Java 开发", "JD", "Java", "您好", 120);

        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void templateErrorReturnsPromptTemplateErrorAndNeverCallsAi() {
        aiConfig.setPrompt("使用未知变量 {{unknown_variable}} 生成话术");

        AiMatchResult result = matchService.evaluate(aiConfig, "Java 开发", "JD", "Java", "您好", 120);

        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiMatchService.PROMPT_TEMPLATE_ERROR, result.errorCode());
        assertTrue(result.errorDetail().contains("unknown_variable"));
        assertNull(result.message());
        verify(aiService, never()).sendRequestWithUsage(anyString());
    }

    @Test
    void configErrorReturnsConfigInvalidAndNeverCallsAi() {
        doThrow(new AiConfigException("AI 配置缺少 API_KEY，请先在配置页填写"))
                .when(aiService).validateConfiguration();

        AiMatchResult result = matchService.evaluate(aiConfig, "Java 开发", "JD", "Java", "您好", 120);

        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiMatchService.AI_CONFIG_INVALID, result.errorCode());
        assertTrue(result.errorDetail().contains("API_KEY"));
        assertNull(result.message());
        verify(aiService, never()).sendRequestWithUsage(anyString());
    }

    @Test
    void nullAiConfigReturnsConfigInvalid() {
        AiMatchResult result = matchService.evaluate(null, "Java 开发", "JD", "Java", "您好", 120);

        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiMatchService.AI_CONFIG_INVALID, result.errorCode());
        assertNull(result.message());
        verify(aiService, never()).sendRequestWithUsage(anyString());
    }

    @Test
    void requestFailureReturnsRequestFailedAndNeverThrows() {
        when(aiService.sendRequestWithUsage(anyString()))
                .thenThrow(new RuntimeException("AI请求失败，状态码: 401, 详情: invalid api key"));

        AiMatchResult result = matchService.evaluate(aiConfig, "Java 开发", "JD", "Java", "您好", 120);

        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiMatchService.AI_REQUEST_FAILED, result.errorCode());
        assertTrue(result.errorDetail().contains("401"));
        assertNull(result.message());
    }

    @Test
    void rejectOutcomeFromLegacyFormatIsNotSendable() {
        when(aiService.sendRequestWithUsage(anyString())).thenReturn(new AiCallResult(
                "{\"match\":false,\"analysis\":\"技术栈不匹配\"}", "gpt-test", 10, 5, 100L, true));

        AiMatchResult result = matchService.evaluate(aiConfig, "Java 开发", "JD", "Java", "您好", 120);

        assertEquals(AiOutcome.REJECT, result.outcome());
        assertFalse(result.sendable());
        assertNull(result.message());
        assertEquals("技术栈不匹配", result.analysis());
    }

    @Test
    void emptyPromptFallsBackToDefaultTemplate() {
        aiConfig.setPrompt(null);
        when(aiService.sendRequestWithUsage(anyString())).thenReturn(new AiCallResult(
                "{\"decision\":\"PASS\",\"analysis\":\"匹配\","
                        + "\"message\":\"您好，期待与您沟通岗位细节。\"}",
                "gpt-test", 10, 5, 100L, true));

        AiMatchResult result = matchService.evaluate(aiConfig, "Java 开发", "JD", "Java", "您好", 120);

        assertEquals(AiOutcome.MATCH, result.outcome());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).sendRequestWithUsage(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("候选人介绍：三年 Java 后端经验"));
    }
}
