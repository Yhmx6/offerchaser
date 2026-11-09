package com.offerchaser.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5 空间重构后的 B2 交付检查：配置空间只保留方向模板 + 编辑区 + 模型服务，
 * 不再挂「筛选规则/AI 配置/高级调试」页签；规则/AI 只在模板编辑器 B/C 区维护。
 * 评估重评在「记录」空间；旧 /boss/rules、/ai-config、/boss/advanced 保留重定向。真实 API、409、无 mock。
 */
class B2FrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void rulesViewUsesRealApisAndDistArtifactExists() throws Exception {
        Path view = FRONT.resolve("app/components/views/RulesView.tsx");
        assertTrue(Files.isRegularFile(view), "front 缺少 RulesView");
        assertTrue(Files.size(view) > 20_000, "规则视图过小，可能是占位实现");
        String source = Files.readString(view);
        for (String api : List.of("getRules", "saveRules", "getRuleVersions", "previewRules")) {
            assertTrue(source.contains(api), "规则视图必须调用真实 API：" + api);
        }
        assertTrue(source.contains("expectedVersion"), "保存必须携带 expectedVersion");
        assertTrue(source.contains("409") || source.contains("RULE_VERSION_CONFLICT"),
                "规则视图必须处理 409 冲突");
        for (String field : List.of("highestDegree", "salaryStrategy", "blacklistMatchMode", "whitelist")) {
            assertTrue(source.contains(field), "规则表单应覆盖字段：" + field);
        }
        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        assertTrue(setup.contains("方向模板") && setup.contains("ModelServiceCard")
                        && setup.contains("TemplateConfigEditor"),
                "配置空间只保留方向模板/编辑区/模型服务");
        assertFalse(setup.contains("RulesView") || setup.contains("AiConfigView")
                        || setup.contains("AdvancedPanel"),
                "配置空间不得再挂 RulesView/AiConfigView/高级调试");
        for (String tabKey : List.of("key: 'rules'", "key: 'ai'", "key: 'advanced'")) {
            assertFalse(setup.contains(tabKey), "配置空间不得再有页签：" + tabKey);
        }
        for (String route : List.of("app/boss/rules/page.tsx", "app/ai-config/page.tsx",
                "app/boss/advanced/page.tsx")) {
            assertTrue(Files.readString(FRONT.resolve(route)).contains("LegacyRedirect"),
                    "旧路由应保留重定向：" + route);
        }

        Path setupHtml = DIST.resolve("boss/setup.html");
        assertTrue(Files.isRegularFile(setupHtml), "dist 缺少 boss/setup.html");
        assertTrue(Files.size(setupHtml) > 20_000, "dist setup.html 体积异常：" + Files.size(setupHtml));
        assertTrue(Files.readString(setupHtml).contains("方向模板"), "dist 配置空间应渲染页面");
        assertTrue(distContainsAny("用当前规则重评", "重评完成"),
                "dist 客户端 chunk 应包含评估重评逻辑");
        assertTrue(distContainsAny("最高学历", "salaryStrategy", "规则版本"),
                "dist 客户端 chunk 应包含规则表单/版本信息");
    }

    @Test
    void apiAndTypesCoverB2Contracts() throws Exception {
        String api = Files.readString(FRONT.resolve("lib/api.ts"));
        for (String path : List.of("/api/boss/rules", "/api/boss/rules/versions",
                "/api/boss/rules/preview", "/api/boss/evaluations/recheck")) {
            assertTrue(api.contains(path), "lib/api.ts 缺少 B2 接口：" + path);
        }
        for (String function : List.of("getRules", "saveRules", "getRuleVersions",
                "previewRules", "recheckEvaluations")) {
            assertTrue(api.contains("function " + function), "lib/api.ts 缺少函数：" + function);
        }
        String types = Files.readString(FRONT.resolve("lib/boss-types.ts"));
        for (String type : List.of("RulePayload", "RuleSetView", "PreviewResult", "PreviewSample",
                "RecheckRequest", "RecheckResult", "ruleConfigVersion")) {
            assertTrue(types.contains(type), "lib/boss-types.ts 缺少类型/字段：" + type);
        }
    }

    @Test
    void setupHasNoTabsWhileRulesAndAiStayInTemplateEditor() throws Exception {
        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        for (String marker : List.of("ModelServiceCard", "方向模板", "TemplateConfigEditor", "AI 帮我建模板")) {
            assertTrue(setup.contains(marker), "配置空间缺少内容：" + marker);
        }
        for (String forbidden : List.of("TAB_LABELS", "<RulesView", "<AiConfigView", "AdvancedPanel",
                "params.get('tab')", "setTab(")) {
            assertFalse(setup.contains(forbidden), "配置空间不得再有三页签：" + forbidden);
        }

        // 规则/AI 字段仍在模板编辑器 B/C 区维护
        String editor = Files.readString(FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"));
        for (String field : List.of("B. 公司要求与筛选规则", "C. AI 配置",
                "highestDegree", "salaryStrategy", "blacklistMatchMode", "whitelist",
                "blacklistCompany", "blacklistRecruiter", "updateTemplateConfig")) {
            assertTrue(editor.contains(field), "模板编辑器应保留规则/AI 字段：" + field);
        }
        // 旧组件文件未删，API 能力仍保留（组件级，不要求 Setup 挂载）
        String rulesView = Files.readString(FRONT.resolve("app/components/views/RulesView.tsx"));
        for (String api : List.of("getRules", "saveRules", "getRuleVersions", "previewRules")) {
            assertTrue(rulesView.contains(api), "RulesView 组件仍应保留真实规则 API：" + api);
        }
        String aiView = Files.readString(FRONT.resolve("app/components/views/AiConfigView.tsx"));
        for (String marker : List.of("/api/ai/config", "introduce", "prompt", "TemplateContextBanner")) {
            assertTrue(aiView.contains(marker), "AI 配置视图缺少：" + marker);
        }
        String spaces = Files.readString(FRONT.resolve("app/lib/spaces.ts"));
        assertTrue(spaces.contains("tab=rules") || spaces.contains("tab="),
                "旧路由映射仍应保留 query 语义");
        String ledger = Files.readString(FRONT.resolve("app/boss/ledger/page.tsx"));
        assertTrue(ledger.contains("recheckEvaluations"), "记录空间必须调用真实重评接口");
        assertTrue(ledger.contains("重评"), "记录空间应有「用当前规则重评」按钮");
        assertTrue(ledger.contains("只新增评估") && ledger.contains("未发送任何消息"),
                "重评结果必须注明只新增评估、不发送消息");
        assertTrue(ledger.contains("ruleConfigVersion"), "评估表应展示规则版本");
        assertTrue(Files.readString(FRONT.resolve("app/boss/evaluations/page.tsx"))
                .contains("LegacyRedirect"), "旧 /boss/evaluations 应保留重定向");
    }

    @Test
    void noMockDataInB2FrontendSources() throws Exception {
        List<Path> sources = new ArrayList<>();
        addTextSources(sources, FRONT.resolve("lib"));
        addTextSources(sources, FRONT.resolve("app"));
        List<String> markers = List.of("mockdata", "mock-data", "mock_", "fakedata", "fake data");
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String content = Files.readString(source).toLowerCase(Locale.ROOT);
            for (String marker : markers) {
                if (content.contains(marker)) {
                    offenders.add(FRONT.relativize(source) + " -> " + marker);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "B2 前端源码不得使用 mock 数据：" + offenders);
    }

    private static boolean distContainsAny(String... needles) throws Exception {
        try (Stream<Path> stream = Files.walk(DIST)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".js") || name.endsWith(".html") || name.endsWith(".txt");
                    }).toList();
            for (Path file : files) {
                String content = Files.readString(file);
                for (String needle : needles) {
                    if (content.contains(needle)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void addTextSources(List<Path> target, Path directory) throws Exception {
        try (Stream<Path> stream = Files.walk(directory)) {
            target.addAll(stream.filter(B2FrontendDeliveryCheckTest::isTextSource).toList());
        }
    }

    private static boolean isTextSource(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".js")
                || name.endsWith(".jsx") || name.endsWith(".json") || name.endsWith(".css");
    }
}
