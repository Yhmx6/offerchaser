package com.offerchaser.application.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiOutputParser} 单元测试：合法 JSON、旧格式、缺 message、纯文本、代码块、错误类型。
 * <p>核心断言：解析失败时 message 必须为 null，绝不能把原始文本当话术。</p>
 */
class AiOutputParserTest {

    @Test
    void parsesNewDecisionPass() {
        AiMatchResult result = AiOutputParser.parse(
                "{\"decision\":\"PASS\",\"analysis\":\"JD 与经历匹配\",\"message\":\"您好，我有三年 Java 后端经验，希望与您沟通。\"}");
        assertEquals(AiOutcome.MATCH, result.outcome());
        assertTrue(result.sendable());
        assertEquals("您好，我有三年 Java 后端经验，希望与您沟通。", result.message());
        assertEquals("JD 与经历匹配", result.analysis());
        assertNull(result.errorCode());
    }

    @Test
    void parsesNewDecisionRejectAndReviewCaseInsensitively() {
        AiMatchResult reject = AiOutputParser.parse("{\"decision\":\"REJECT\",\"analysis\":\"技术栈不符\"}");
        assertEquals(AiOutcome.REJECT, reject.outcome());
        assertNull(reject.message());
        assertFalse(reject.sendable());

        AiMatchResult review = AiOutputParser.parse("{\"decision\":\"review\",\"analysis\":\"需要人工复核\"}");
        assertEquals(AiOutcome.REVIEW, review.outcome());
        assertNull(review.message());
        assertEquals("需要人工复核", review.analysis());
    }

    @Test
    void truncatesLongAnalysisToMaxLength() {
        String longAnalysis = "匹配".repeat(100);
        AiMatchResult result = AiOutputParser.parse(
                "{\"decision\":\"REVIEW\",\"analysis\":\"" + longAnalysis + "\"}");
        assertEquals(AiOutcome.REVIEW, result.outcome());
        assertNotNull(result.analysis());
        assertEquals(AiOutputParser.MAX_ANALYSIS_CHARS, result.analysis().length());
    }

    @Test
    void parsesLegacyMatchTrue() {
        AiMatchResult result = AiOutputParser.parse(
                "{\"match\":true,\"analysis\":\"匹配\",\"message\":\"您好，期待与您沟通岗位细节。\"}");
        assertEquals(AiOutcome.MATCH, result.outcome());
        assertTrue(result.sendable());
        assertEquals("您好，期待与您沟通岗位细节。", result.message());
    }

    @Test
    void parsesLegacyMatchFalse() {
        AiMatchResult result = AiOutputParser.parse("{\"match\":false,\"analysis\":\"不匹配\"}");
        assertEquals(AiOutcome.REJECT, result.outcome());
        assertNull(result.message());
    }

    @Test
    void legacyMatchTrueWithoutMessageIsError() {
        AiMatchResult result = AiOutputParser.parse("{\"match\":true,\"analysis\":\"匹配\"}");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_MESSAGE_MISSING, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void newDecisionPassWithoutMessageIsError() {
        AiMatchResult result = AiOutputParser.parse("{\"decision\":\"PASS\",\"analysis\":\"匹配\"}");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_MESSAGE_MISSING, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void blankMessageCountsAsMissing() {
        AiMatchResult result = AiOutputParser.parse(
                "{\"decision\":\"PASS\",\"analysis\":\"匹配\",\"message\":\"   \"}");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_MESSAGE_MISSING, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void plainTextIsErrorAndNeverBecomesMessage() {
        String raw = "您好，我觉得这个岗位很适合您，方便聊聊吗？";
        AiMatchResult result = AiOutputParser.parse(raw);
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
        assertFalse(result.sendable());
    }

    @Test
    void markdownCodeBlockWrappedJsonIsAccepted() {
        AiMatchResult withLang = AiOutputParser.parse(
                "```json\n{\"decision\":\"PASS\",\"analysis\":\"匹配\",\"message\":\"您好，希望进一步沟通岗位情况。\"}\n```");
        assertEquals(AiOutcome.MATCH, withLang.outcome());
        assertTrue(withLang.sendable());

        AiMatchResult withoutLang = AiOutputParser.parse(
                "```\n{\"match\":false,\"analysis\":\"不匹配\"}\n```");
        assertEquals(AiOutcome.REJECT, withoutLang.outcome());
    }

    @Test
    void textOutsideCodeFenceIsError() {
        AiMatchResult result = AiOutputParser.parse(
                "结果如下：\n```json\n{\"decision\":\"PASS\",\"message\":\"您好，希望沟通一下。\"}\n```");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void jsonArrayIsError() {
        AiMatchResult result = AiOutputParser.parse("[{\"decision\":\"PASS\",\"message\":\"您好\"}]");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void invalidDecisionValueIsError() {
        AiMatchResult result = AiOutputParser.parse("{\"decision\":\"MAYBE\",\"analysis\":\"不确定\"}");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertTrue(result.errorDetail().contains("MAYBE"));
        assertNull(result.message());
    }

    @Test
    void nonStringDecisionIsError() {
        AiMatchResult result = AiOutputParser.parse("{\"decision\":true,\"message\":\"您好\"}");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void nonBooleanMatchIsError() {
        AiMatchResult result = AiOutputParser.parse("{\"match\":\"true\",\"message\":\"您好\"}");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void missingDecisionAndMatchIsError() {
        AiMatchResult result = AiOutputParser.parse("{\"analysis\":\"只有分析\",\"message\":\"您好\"}");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void nonStringMessageOnPassIsMissing() {
        AiMatchResult result = AiOutputParser.parse("{\"decision\":\"PASS\",\"message\":123}");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_MESSAGE_MISSING, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void trailingTextAfterJsonIsError() {
        AiMatchResult result = AiOutputParser.parse(
                "{\"decision\":\"REJECT\",\"analysis\":\"不匹配\"} 以上是分析结果");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void nullBlankAndEmptyObjectAreErrors() {
        assertError(AiOutputParser.parse(null));
        assertError(AiOutputParser.parse("   "));
        assertError(AiOutputParser.parse("{}"));
    }

    @Test
    void invalidJsonIsError() {
        AiMatchResult result = AiOutputParser.parse("{\"decision\":\"PASS\",\"message\":}");
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
    }

    @Test
    void parsesOptionalRequirementsWithoutAffectingOutcome() {
        AiMatchResult result = AiOutputParser.parse(
                "{\"decision\":\"PASS\",\"analysis\":\"匹配\",\"message\":\"您好，希望与您沟通。\","
                        + "\"requirements\":[{\"requirement\":\"Java\",\"level\":\"必须\","
                        + "\"evidenceIds\":[\"E1\",\"E2\"],\"result\":\"MATCH\"}]}");
        assertEquals(AiOutcome.MATCH, result.outcome());
        assertEquals(1, result.requirements().size());
        assertEquals("Java", result.requirements().get(0).requirement());
        assertEquals(2, result.requirements().get(0).evidenceIds().size());
    }

    private void assertError(AiMatchResult result) {
        assertEquals(AiOutcome.ERROR, result.outcome());
        assertEquals(AiOutputParser.AI_OUTPUT_INVALID, result.errorCode());
        assertNull(result.message());
        assertFalse(result.sendable());
    }
}
