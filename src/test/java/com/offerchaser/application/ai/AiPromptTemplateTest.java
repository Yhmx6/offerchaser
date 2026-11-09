package com.offerchaser.application.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiPromptTemplate} 单元测试：命名变量、旧版 %s、未知占位符、含 % 的模板、默认模板。
 */
class AiPromptTemplateTest {

    @Test
    void rendersNamedVariables() {
        AiPromptTemplate template = new AiPromptTemplate(
                "岗位：{{job_name}}；描述：{{job_description}}；介绍：{{introduce}}；关键词：{{keyword}}；参考：{{say_hi}}");
        String rendered = template.render(Map.of(
                "job_name", "Java 开发",
                "job_description", "负责后端服务",
                "introduce", "三年经验",
                "keyword", "Java",
                "say_hi", "您好"));
        assertEquals("岗位：Java 开发；描述：负责后端服务；介绍：三年经验；关键词：Java；参考：您好", rendered);
    }

    @Test
    void missingNamedVariableBecomesEmpty() {
        AiPromptTemplate template = new AiPromptTemplate("岗位：{{job_name}}|介绍：{{introduce}}");
        assertEquals("岗位：Java 开发|介绍：", template.render(Map.of("job_name", "Java 开发")));
    }

    @Test
    void nullOrBlankPromptFallsBackToDefaultPrompt() {
        Map<String, String> variables = Map.of(
                "introduce", "三年经验",
                "keyword", "Java",
                "job_name", "后端开发",
                "job_description", "负责服务端",
                "say_hi", "您好");
        String expected = AiPromptTemplate.DEFAULT_PROMPT
                .replace("{{introduce}}", "三年经验")
                .replace("{{keyword}}", "Java")
                .replace("{{job_name}}", "后端开发")
                .replace("{{job_description}}", "负责服务端")
                .replace("{{say_hi}}", "您好");
        assertEquals(expected, new AiPromptTemplate(null).render(variables));
        assertEquals(expected, new AiPromptTemplate("   ").render(variables));
        assertEquals(expected, new AiPromptTemplate(AiPromptTemplate.DEFAULT_PROMPT).render(variables));
    }

    @Test
    void unknownPlaceholderThrows() {
        AiTemplateException exception = assertThrows(AiTemplateException.class,
                () -> new AiPromptTemplate("请使用 {{unknown_name}} 变量").render(Map.of()));
        assertTrue(exception.getMessage().contains("unknown_name"), "异常信息应带上未知占位符名");
        assertTrue(exception.getMessage().contains("仅支持"), "异常信息应列出支持的变量");
    }

    @Test
    void unclosedPlaceholderThrows() {
        assertThrows(AiTemplateException.class,
                () -> new AiPromptTemplate("介绍：{{introduce").render(Map.of()));
        // 没有 {{ 的孤立 }} 不构成占位符，按普通文本原样返回（与“两者都没有则原样使用”一致）
        assertEquals("介绍：introduce}}",
                new AiPromptTemplate("介绍：introduce}}").render(Map.of()));
        // 已有命名占位符时，残留未闭合的 }} 属于配置错误
        assertThrows(AiTemplateException.class,
                () -> new AiPromptTemplate("{{introduce}} 后续 }}").render(Map.of("introduce", "A")));
    }

    @Test
    void legacyPercentSReplacedInOrderWithoutFormat() {
        AiPromptTemplate template = new AiPromptTemplate("介绍:%s 关键词:%s 岗位:%s 描述:%s 参考:%s");
        String rendered = template.render(Map.of(
                "introduce", "A",
                "keyword", "B",
                "job_name", "C",
                "job_description", "D",
                "say_hi", "E"));
        assertEquals("介绍:A 关键词:B 岗位:C 描述:D 参考:E", rendered);
    }

    @Test
    void legacyTemplateContainingPercentDoesNotThrow() {
        // 含裸 % 与 %% 的旧模板：手写替换，不能像 String.format 那样抛异常
        AiPromptTemplate template = new AiPromptTemplate("通过率 100% 提升 50%%：%s");
        assertEquals("通过率 100% 提升 50%%：A", template.render(Map.of("introduce", "A")));
    }

    @Test
    void namedTemplateKeepsPercentLiteral() {
        AiPromptTemplate template = new AiPromptTemplate("折扣 100% 的岗位：{{keyword}}");
        assertEquals("折扣 100% 的岗位：Java", template.render(Map.of("keyword", "Java")));
    }

    @Test
    void tooManyLegacyPlaceholdersThrows() {
        AiTemplateException exception = assertThrows(AiTemplateException.class,
                () -> new AiPromptTemplate("%s%s%s%s%s%s").render(Map.of()));
        assertTrue(exception.getMessage().contains("最多允许 5 个"));
    }

    @Test
    void templateWithoutPlaceholdersUsedAsIs() {
        AiPromptTemplate template = new AiPromptTemplate("请生成一句中文问候语");
        assertEquals("请生成一句中文问候语", template.render(Map.of()));
    }

    @Test
    void valueContainingDollarAndBackslashIsNotInterpreted() {
        AiPromptTemplate template = new AiPromptTemplate("介绍：{{introduce}}");
        assertEquals("介绍：$1 \\ 价格", template.render(Map.of("introduce", "$1 \\ 价格")));
    }

    @Test
    void templateHashIsStableSha256Hex() {
        AiPromptTemplate template = new AiPromptTemplate("模板A");
        assertEquals(template.templateHash(), new AiPromptTemplate("模板A").templateHash());
        assertTrue(template.templateHash().matches("[0-9a-f]{64}"), "模板哈希应为 64 位小写 hex");
        assertNotEquals(template.templateHash(), new AiPromptTemplate("模板B").templateHash());
        // 空白模板会回退成 DEFAULT_PROMPT，哈希也应与默认模板一致
        assertEquals(new AiPromptTemplate(AiPromptTemplate.DEFAULT_PROMPT).templateHash(),
                new AiPromptTemplate("   ").templateHash());
    }

    @Test
    void defaultPromptUsesNamedVariablesOnly() {
        assertTrue(AiPromptTemplate.DEFAULT_PROMPT.contains("{{introduce}}"));
        assertTrue(AiPromptTemplate.DEFAULT_PROMPT.contains("{{keyword}}"));
        assertTrue(AiPromptTemplate.DEFAULT_PROMPT.contains("{{job_name}}"));
        assertTrue(AiPromptTemplate.DEFAULT_PROMPT.contains("{{job_description}}"));
        assertTrue(AiPromptTemplate.DEFAULT_PROMPT.contains("{{say_hi}}"));
        assertFalse(AiPromptTemplate.DEFAULT_PROMPT.contains("%s"));
    }
}
