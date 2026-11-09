package com.offerchaser.application.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiMessageValidator} 单元测试：正常、空、超长、JSON 片段、代码块、控制字符。
 */
class AiMessageValidatorTest {

    @Test
    void normalChineseMessageIsValid() {
        AiMessageValidator.Validation validation = AiMessageValidator.validate(
                "您好，我是一名有三年经验的 Java 后端开发，看到岗位要求比较匹配，期待与您进一步沟通。", 120);
        assertTrue(validation.valid());
        assertNull(validation.reason());
    }

    @Test
    void nullAndBlankMessageIsInvalid() {
        assertFalse(AiMessageValidator.validate(null, 120).valid());
        assertFalse(AiMessageValidator.validate("   ", 120).valid());
        assertFalse(AiMessageValidator.validate("", 120).valid());
    }

    @Test
    void overlongMessageIsInvalid() {
        String message = "您好，" + "很".repeat(120);
        AiMessageValidator.Validation validation = AiMessageValidator.validate(message, 120);
        assertFalse(validation.valid());
        assertTrue(validation.reason().contains("长度上限"), "原因应说明超出长度上限");
        assertTrue(validation.reason().contains("120"), "原因应带上长度上限数值");
    }

    @Test
    void exactlyMaxLengthIsValid() {
        String message = "很".repeat(120);
        assertTrue(AiMessageValidator.validate(message, 120).valid());
    }

    @Test
    void maxLengthNotPositiveFallsBackToDefault() {
        assertTrue(AiMessageValidator.validate("很".repeat(120), 0).valid());
        assertFalse(AiMessageValidator.validate("很".repeat(121), -1).valid());
    }

    @Test
    void codeFenceIsInvalid() {
        AiMessageValidator.Validation validation = AiMessageValidator.validate(
                "```json\n{\"decision\":\"PASS\"}\n```", 120);
        assertFalse(validation.valid());
        assertTrue(validation.reason().contains("代码块"));
    }

    @Test
    void jsonFieldFragmentIsInvalid() {
        for (String fragment : new String[]{"decision", "match", "analysis"}) {
            AiMessageValidator.Validation validation = AiMessageValidator.validate(
                    "您好，输出字段 " + fragment + " 不应出现在话术里", 120);
            assertFalse(validation.valid(), "包含 " + fragment + " 时应判为不合法");
            assertTrue(validation.reason().contains(fragment));
        }
    }

    @Test
    void jsonSnippetWithQuotesIsInvalid() {
        AiMessageValidator.Validation validation = AiMessageValidator.validate(
                "{\"decision\":\"PASS\",\"message\":\"您好\"}", 120);
        assertFalse(validation.valid());
    }

    @Test
    void controlCharactersOtherThanNewlineAreInvalid() {
        assertFalse(AiMessageValidator.validate("您好\t我是开发", 120).valid());
        assertFalse(AiMessageValidator.validate("您好\u0007我是开发", 120).valid());
    }

    @Test
    void newlineIsAllowed() {
        assertTrue(AiMessageValidator.validate("您好，\n我是一名 Java 开发，期待沟通。", 120).valid());
    }

    @Test
    void normalizeTrimsAndCollapsesWhitespace() {
        assertEquals("您好 世界", AiMessageValidator.normalize("  您好 \n\t 世界  "));
        assertEquals("", AiMessageValidator.normalize(null));
        assertEquals("您好 世界", AiMessageValidator.normalize("您好\u3000\u3000世界"));
    }

    @Test
    void normalizationHappensBeforeLengthCheck() {
        // 空白归一化后只有 4 个字符，不应因为原始空白多而判超长
        String heavilySpaced = "您好" + " ".repeat(200) + "世界";
        assertTrue(AiMessageValidator.validate(heavilySpaced, 10).valid());
    }
}
