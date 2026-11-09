package com.offerchaser.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5 空间重构后的方向模板交付检查：模板管理迁入「配置」空间（/boss/setup），
 * 编辑器内嵌；创建/激活/覆盖/复制/删除/拖拽排序全部调用真实 API；安全口径与 dist 同步；旧路由重定向。
 */
class ProfileTemplateFrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void setupSpaceUsesRealTemplateApis() throws Exception {
        Path setup = FRONT.resolve("app/boss/setup/page.tsx");
        assertTrue(Files.isRegularFile(setup), "缺少配置空间页面");
        assertTrue(Files.size(setup) > 20_000, "配置空间过小，可能是占位实现");
        String source = Files.readString(setup);
        for (String api : List.of("getTemplates", "createTemplate", "updateTemplate",
                "activateTemplate", "captureTemplate", "duplicateTemplate", "deleteTemplate",
                "reorderTemplates", "TemplateConfigEditor")) {
            assertTrue(source.contains(api), "配置空间必须调用真实模板 API/组件：" + api);
        }
        assertTrue(source.contains("TemplateView"), "配置空间应使用模板类型");
        assertTrue(source.contains("加载") || source.contains("载入"), "应有加载状态");
        assertTrue(source.contains("还没有方向模板"), "必须有空态（不白屏）");
    }

    @Test
    void templateEditorIsEmbeddedAndSavesRealConfig() throws Exception {
        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        assertTrue(setup.contains("<TemplateConfigEditor") && setup.contains("embedded"),
                "配置空间应以 embedded 模式内嵌编辑器");
        String editor = Files.readString(FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"));
        for (String marker : List.of("updateTemplateConfig", "onSaved", "A. 搜索与投递",
                "B. 公司要求与筛选规则", "C. AI 配置", "D. 模板图片简历", "保存配置",
                "已保存并立即应用")) {
            assertTrue(editor.contains(marker), "编辑器缺少整包保存/闭环提示：" + marker);
        }
        assertTrue(editor.contains("embedded") && editor.contains("收起编辑器"),
                "embedded 模式应隐藏「收起编辑器」");
        assertTrue(Files.readString(FRONT.resolve("app/boss/templates/page.tsx"))
                .contains("LegacyRedirect"), "旧模板页应重定向到配置空间");
    }

    @Test
    void templatePageShowsSafetyBoundaries() throws Exception {
        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        String editor = Files.readString(FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"));
        String combined = setup + "\n" + editor;
        for (String text : List.of("生效模板 > 当前配置", "下一轮任务生效", "不会立即发送",
                "生效模板不可删除", "不会改变当前配置", "未激活", "激活后生效")) {
            assertTrue(combined.contains(text), "模板操作缺少安全口径：" + text);
        }
        for (String forbidden : List.of("startBoss(", "stopBoss(", "sendMessage(")) {
            assertFalse(setup.contains(forbidden), "模板管理不得直接触发投递：" + forbidden);
        }
    }

    @Test
    void spaceRoutingAndDistContainTemplates() throws Exception {
        String spaces = Files.readString(FRONT.resolve("app/lib/spaces.ts"));
        assertTrue(spaces.contains("'templates'") && spaces.contains("/boss/setup"),
                "旧模板入口应映射到配置空间");
        String setupHtml = Files.readString(DIST.resolve("boss/setup.html"));
        assertTrue(Files.size(DIST.resolve("boss/setup.html")) > 20_000,
                "dist setup.html 体积异常");
        for (String text : List.of("方向模板", "新建模板", "模型服务")) {
            assertTrue(setupHtml.contains(text), "dist 配置空间应渲染：" + text);
        }
        assertTrue(distContainsAny("updateTemplateConfig", "activateTemplate", "captureTemplate"),
                "dist 客户端 chunk 应包含模板操作调用");
        assertTrue(Files.readString(DIST.resolve("boss/templates.html"))
                .contains("正在跳转到新工作台"), "dist 旧 templates.html 应为重定向壳");
        long distFiles;
        try (Stream<Path> stream = Files.walk(DIST)) {
            distFiles = stream.filter(Files::isRegularFile).count();
        }
        assertTrue(distFiles >= 130, "dist 文件数量异常：" + distFiles);
    }

    @Test
    void templateSummarySalaryComesFromRuleAndCardShowsLowerBound() throws Exception {
        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        assertTrue(setup.contains("期望月薪下限"), "模板卡薪资字段应为期望月薪下限");
        assertTrue(setup.contains("`${selected.expectedSalaryMin}K 起`"),
                "模板卡应显示规则下限「minK 起」");
        assertFalse(setup.contains("不超过"),
                "模板卡不得再有「不超过 maxK」兼容分支");

        String templateService = Files.readString(Path.of("src", "main", "java",
                "com/offerchaser/application/templates/ProfileTemplateService.java"));
        int viewStart = templateService.indexOf("private TemplateView toView(");
        int viewEnd = templateService.indexOf("/** 0/负数视为未设置", viewStart);
        String toView = templateService.substring(viewStart, viewEnd);
        assertTrue(toView.contains("parseRulePayload(template)")
                        && toView.contains("rules.expectedSalaryMin()")
                        && toView.contains("rules.expectedSalaryMax()"),
                "toView 必须使用规则 expectedSalaryMin/Max 生成模板摘要");
        assertTrue(toView.indexOf("parseRulePayload(template)")
                        > toView.indexOf("if (template.bossConfigJson()"),
                "rule 薪资解析必须独立于 bossConfig 是否存在");
        assertTrue(toView.contains("A 区期望月薪已废弃"), "toView 应注明 A 区薪资废弃");
        assertTrue(templateService.contains("positiveOrNull"),
                "0/负数应视为未设置，不展示无效薪资");
    }

    @Test
    void noMockDataInTemplateSources() throws Exception {
        List<Path> sources = List.of(
                FRONT.resolve("app/boss/setup/page.tsx"),
                FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"));
        for (Path source : sources) {
            String content = Files.readString(source).toLowerCase(Locale.ROOT);
            for (String marker : List.of("mockdata", "mock-data", "mock_", "fakedata")) {
                assertFalse(content.contains(marker),
                        source.getFileName() + " 不得使用 mock 数据：" + marker);
            }
        }
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
}
