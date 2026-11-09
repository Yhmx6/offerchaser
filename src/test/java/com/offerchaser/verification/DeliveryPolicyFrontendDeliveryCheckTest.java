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
 * 5 空间重构后的采集策略/跟进视图交付检查：
 * 模板采集策略仍在配置空间的模板编辑器；跟进策略迁到洞察空间的只读视图；
 * 旧路由重定向；dist 同步；无 mock。
 */
class DeliveryPolicyFrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void templateEditorHasTemplateScopedCollectionPolicy() throws Exception {
        String editor = Files.readString(FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"));
        assertTrue(editor.contains("采集策略（仅本模板）"), "编辑器 A 区应有采集策略区块");
        for (String field : List.of("collectMaxPerKeyword", "collectMaxDurationMinutes",
                "collectNoNewWindowSeconds")) {
            assertTrue(editor.contains(field), "编辑器缺少采集策略字段：" + field);
        }
        assertTrue(editor.contains("全模板共享"), "必须说明每日首次沟通上限全模板共享");
        assertTrue(editor.contains("现有跟进设置") && editor.contains("不纳入"),
                "重复沟通应标记为现有跟进设置、不纳入采集策略");
        assertTrue(editor.contains("deliveryPolicy"), "保存 payload 必须携带 deliveryPolicy");
        assertTrue(editor.contains("恢复全局默认"), "必须说明 -1=恢复全局默认");
        assertTrue(editor.contains("不限制") || editor.contains("无限制"), "必须说明 0=不限制");
        assertTrue(Files.readString(FRONT.resolve("app/boss/setup/page.tsx"))
                .contains("TemplateConfigEditor"), "模板编辑器应嵌入配置空间");
    }

    @Test
    void followupStrategyViewIsReadOnlyMeasuresList() throws Exception {
        Path view = FRONT.resolve("app/components/views/FollowupStrategyView.tsx");
        assertTrue(Files.isRegularFile(view), "缺少跟进策略只读视图");
        assertTrue(Files.size(view) > 3_000, "跟进策略视图过小，可能是占位实现");
        String source = Files.readString(view);
        for (String text : List.of("后续跟进 · 措施清单（规划）", "只读", "当前已有",
                "后续待做", "不执行发送", "跟进策略", "每日首次沟通上限")) {
            assertTrue(source.contains(text), "跟进策略视图缺少：" + text);
        }
        assertTrue(source.contains("采集策略"), "说明区应强调采集策略与跟进无关");
        assertTrue(source.contains("不自动发送"), "必须说明跟进默认不自动发送");
        assertFalse(source.contains("fetch(") || source.contains("getBossStatus"),
                "只读视图不应调用业务接口");
        String oldPage = Files.readString(FRONT.resolve("app/boss/followup-strategy/page.tsx"));
        assertTrue(oldPage.contains("LegacyRedirect"), "旧跟进策略路由应重定向");
    }

    @Test
    void insightsSpaceExposesFollowupStrategyTab() throws Exception {
        String spaces = Files.readString(FRONT.resolve("app/lib/spaces.ts"));
        assertTrue(spaces.contains("followup-strategy"), "spaces 应保留旧跟进策略入口");
        assertTrue(spaces.contains("/boss/insights"), "旧跟进策略应映射到洞察空间");
        assertTrue(spaces.contains("tab=strategy"), "旧入口应保留 tab=strategy 语义");
        String insights = Files.readString(FRONT.resolve("app/boss/insights/page.tsx"));
        assertTrue(insights.contains("FollowupStrategyView") && insights.contains("跟进策略"),
                "洞察空间应挂载跟进策略视图");
    }

    @Test
    void distContainsPolicyAndFollowupSpaces() throws Exception {
        assertTrue(distContainsAny("采集策略（仅本模板）", "全模板共享"),
                "dist 应包含模板采集策略区块");
        assertTrue(distContainsAny("后续跟进 · 措施清单（规划）"),
                "dist 应包含跟进策略只读视图");
        assertTrue(Files.isRegularFile(DIST.resolve("boss/insights.html")),
                "dist 缺少 boss/insights.html");
        assertTrue(Files.readString(DIST.resolve("boss/followup-strategy.html"))
                .contains("正在跳转到新工作台"), "旧跟进策略应为重定向壳");
        long distFiles;
        try (Stream<Path> stream = Files.walk(DIST)) {
            distFiles = stream.filter(Files::isRegularFile).count();
        }
        assertTrue(distFiles >= 130, "dist 文件数量异常：" + distFiles);
    }

    @Test
    void noMockDataInPolicySources() throws Exception {
        List<Path> sources = List.of(
                FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"),
                FRONT.resolve("app/components/views/FollowupStrategyView.tsx"),
                FRONT.resolve("app/boss/insights/page.tsx"));
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
