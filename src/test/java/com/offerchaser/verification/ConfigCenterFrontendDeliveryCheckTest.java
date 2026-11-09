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
 * 5 空间重构后的配置中心交付检查：配置空间（/boss/setup）= 方向模板 + 模型服务；
 * 编辑页（规则/AI）继续挂模板上下文横幅；今日页只读、不再承载配置/简历上传；旧路由重定向；无 mock。
 */
class ConfigCenterFrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void templateContextBannerExplainsPriority() throws Exception {
        Path banner = FRONT.resolve("app/components/TemplateContextBanner.tsx");
        assertTrue(Files.isRegularFile(banner), "缺少配置中心上下文组件 TemplateContextBanner");
        String source = Files.readString(banner);
        for (String text : List.of("getTemplates", "当前模板", "自动同步", "任务启动前",
                "配置中心", "/boss/templates", "没有生效模板")) {
            assertTrue(source.contains(text), "横幅缺少说明：" + text);
        }
    }

    @Test
    void bannerIsMountedOnRulesAndAiViews() throws Exception {
        String rules = Files.readString(FRONT.resolve("app/components/views/RulesView.tsx"));
        String ai = Files.readString(FRONT.resolve("app/components/views/AiConfigView.tsx"));
        assertTrue(rules.contains("TemplateContextBanner") && rules.contains("scope=\"筛选规则\""),
                "规则视图必须挂模板上下文横幅");
        assertTrue(ai.contains("TemplateContextBanner") && ai.contains("scope=\"AI 配置\""),
                "AI 配置视图必须挂模板上下文横幅");
        assertTrue(Files.readString(FRONT.resolve("app/boss/layout.tsx")).contains("TopBar"),
                "新 shell 由 TopBar/空间布局提供");
    }

    @Test
    void configSpaceHoldsTemplatesAndModelService() throws Exception {
        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        for (String marker : List.of("TemplateConfigEditor", "ModelServiceCard", "方向模板",
                "reorderTemplates", "activateTemplate", "captureTemplate", "deleteTemplate",
                "保存后 active 立即应用")) {
            assertTrue(setup.contains(marker), "配置空间缺少：" + marker);
        }
        String model = Files.readString(FRONT.resolve("app/boss/templates/ModelServiceCard.tsx"));
        for (String marker : List.of("/api/config", "BASE_URL", "API_KEY", "MODEL", "掩码",
                "模型服务（全局）", "不随模板切换")) {
            assertTrue(model.contains(marker), "模型服务卡片缺少：" + marker);
        }
        String templatePage = Files.readString(FRONT.resolve("app/boss/templates/page.tsx"));
        assertTrue(templatePage.contains("LegacyRedirect"),
                "旧 /boss/templates 应重定向到配置空间");
    }

    @Test
    void todayPageIsReadOnlyAndDoesNotUploadResume() throws Exception {
        String today = Files.readString(FRONT.resolve("app/boss/page.tsx"));
        for (String forbidden : List.of("uploadResumeImage", "uploadTemplateResume", "resume-image",
                "type=\"file\"")) {
            assertFalse(today.contains(forbidden), "今日页不应保留简历上传：" + forbidden);
        }
        assertTrue(today.contains("本页不发任何写请求")
                        || !today.contains("startBoss(") && !today.contains("request<"),
                "今日页应是只读运行视图");
        assertTrue(today.contains("/boss/setup") && today.contains("生效模板"),
                "今日页应显示生效模板并引导到配置空间");

        String editor = Files.readString(FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"));
        for (String marker : List.of("uploadTemplateResume", "templateResumeImageUrl", "hasResume",
                "MAX_RESUME_BYTES")) {
            assertTrue(editor.contains(marker), "模板简历上传/预览应保留在编辑器：" + marker);
        }
    }

    @Test
    void distContainsConfigSpaceAndModelService() throws Exception {
        Path setupHtml = DIST.resolve("boss/setup.html");
        assertTrue(Files.isRegularFile(setupHtml), "dist 缺少 boss/setup.html");
        assertTrue(Files.size(setupHtml) > 20_000, "dist setup.html 体积异常：" + Files.size(setupHtml));
        assertTrue(Files.readString(setupHtml).contains("方向模板"));
        assertTrue(distContainsAny("模型服务（全局）", "BASE_URL", "API_KEY"),
                "dist 应包含全局模型服务卡片");
        assertTrue(distContainsAny("采集策略（仅本模板）", "全模板共享"),
                "dist 应包含模板采集策略与共享提示");
        for (String legacy : List.of("templates", "rules", "advanced")) {
            assertTrue(Files.readString(DIST.resolve("boss/" + legacy + ".html"))
                            .contains("正在跳转到新工作台"),
                    "旧配置路由应为重定向壳：" + legacy);
        }
        assertTrue(Files.readString(DIST.resolve("ai-config.html")).contains("正在跳转到新工作台"),
                "旧 /ai-config 应为重定向壳");
    }

    @Test
    void noMockDataInConfigCenterSources() throws Exception {
        List<Path> sources = List.of(
                FRONT.resolve("app/boss/setup/page.tsx"),
                FRONT.resolve("app/components/TemplateContextBanner.tsx"),
                FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"),
                FRONT.resolve("app/boss/templates/ModelServiceCard.tsx"));
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
