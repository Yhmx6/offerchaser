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
 * 5 空间重构后的 B4 交付检查：统计看板迁到「洞察」空间（InsightsMetricsView + FollowupStrategyView），
 * 真实 metrics API、口径/数据质量/下钻、加载/错误/空三态、SVG 趋势、无 CDN、旧路由重定向、无 mock。
 */
class B4FrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void insightsMetricsViewUsesRealMetricsApis() throws Exception {
        Path view = FRONT.resolve("app/components/views/InsightsMetricsView.tsx");
        assertTrue(Files.isRegularFile(view), "front 缺少 InsightsMetricsView");
        assertTrue(Files.size(view) > 20_000, "看板视图过小，可能是占位实现");
        String source = Files.readString(view);
        for (String function : List.of("getMetricsOverview", "getMetricsSeries", "getMetricsCollection")) {
            assertTrue(source.contains(function), "看板必须调用真实 metrics API：" + function);
        }
        for (String marker : List.of("definitions", "links", "dataQuality", "INVALID_RANGE",
                "366", "加载", "错误", "collection", "下钻")) {
            assertTrue(source.contains(marker), "看板缺少口径/状态/采集/SVG/下钻：" + marker);
        }
        assertFalse(source.contains("cdn.") || source.contains("unpkg") || source.contains("jsdelivr"),
                "看板不得引用 CDN");

        String insights = Files.readString(FRONT.resolve("app/boss/insights/page.tsx"));
        assertTrue(insights.contains("InsightsMetricsView") && insights.contains("FollowupStrategyView"),
                "洞察空间应包含 metrics 与跟进策略两个视图");
        assertTrue(Files.readString(FRONT.resolve("app/boss/analysis/page.tsx"))
                .contains("LegacyRedirect"), "旧 /boss/analysis 应保留重定向");
    }

    @Test
    void insightsMetricsViewHandlesLoadingErrorAndEmptyStates() throws Exception {
        String source = Files.readString(FRONT.resolve("app/components/views/InsightsMetricsView.tsx"));
        assertTrue(source.contains("SkeletonCard") || source.contains("overviewLoading"),
                "指标视图必须有加载中状态");
        assertTrue(source.contains("加载失败") || source.contains("errorMessage"),
                "指标视图必须有错误状态与 message");
        assertTrue(source.contains("暂无统计数据") || source.contains("没有采集来源记录")
                        || source.contains("没有该指标的按日数据"),
                "指标视图必须有空数据状态（不白屏）");
        assertFalse(source.contains("cdn.") || source.contains("unpkg") || source.contains("jsdelivr"),
                "指标视图不得引用 CDN");
        String chart = Files.readString(FRONT.resolve("components/charts/trend-chart.tsx"));
        assertTrue(chart.contains("<svg"), "趋势图组件必须使用本地 SVG");
        assertFalse(chart.contains("cdn.") || chart.contains("unpkg") || chart.contains("jsdelivr"),
                "趋势图组件不得引用 CDN");
    }

    @Test
    void apiAndTypesCoverMetricsContracts() throws Exception {
        String api = Files.readString(FRONT.resolve("lib/api.ts"));
        for (String path : List.of("/api/boss/metrics/overview", "/api/boss/metrics/series",
                "/api/boss/metrics/collection")) {
            assertTrue(api.contains(path), "lib/api.ts 缺少 B4 接口：" + path);
        }
        for (String function : List.of("getMetricsOverview", "getMetricsSeries", "getMetricsCollection")) {
            assertTrue(api.contains("function " + function), "lib/api.ts 缺少函数：" + function);
        }
        String types = Files.readString(FRONT.resolve("lib/boss-types.ts"));
        for (String type : List.of("MetricsOverview", "MetricsSeries", "MetricsCollection", "MetricsQuery",
                "MetricsSeriesMetric")) {
            assertTrue(types.contains(type), "lib/boss-types.ts 缺少 B4 类型：" + type);
        }
        assertTrue(types.contains("newJobs") && types.contains("textConfirmed")
                        && types.contains("followupsExecuted"),
                "series metric 类型应覆盖契约指标");
        assertTrue(types.contains("definitions") && types.contains("dataQuality"),
                "overview 类型应包含 definitions/dataQuality");
    }

    @Test
    void insightsSpaceAndDistContainAnalysisPage() throws Exception {
        String spaces = Files.readString(FRONT.resolve("app/lib/spaces.ts"));
        assertTrue(spaces.contains("'insights'") && spaces.contains("洞察"),
                "spaces 应包含洞察空间");
        assertTrue(spaces.contains("analysis") && spaces.contains("/boss/insights"),
                "旧分析入口应映射到洞察空间");

        Path dist = DIST.resolve("boss/insights.html");
        assertTrue(Files.isRegularFile(dist), "dist 缺少 boss/insights.html");
        assertTrue(Files.size(dist) > 20_000, "dist insights.html 体积异常：" + Files.size(dist));
        String html = Files.readString(dist);
        for (String text : List.of("洞察", "效果与采集", "跟进策略")) {
            assertTrue(html.contains(text), "dist insights.html 应渲染：" + text);
        }
        assertTrue(Files.readString(DIST.resolve("boss/analysis.html")).contains("正在跳转到新工作台"),
                "dist 旧 analysis.html 应为重定向壳");
        assertTrue(distContainsAny("getMetricsOverview", "metrics/overview"),
                "dist 客户端 chunk 应包含 metrics overview 调用");
        assertTrue(distContainsAny("definitions", "dataQuality"),
                "dist 客户端 chunk 应包含口径/数据质量展示逻辑");
    }

    @Test
    void noMockDataInAnalysisSources() throws Exception {
        List<Path> sources = new ArrayList<>();
        addTextSources(sources, FRONT.resolve("app/components/views"));
        sources.add(FRONT.resolve("app/boss/insights/page.tsx"));
        sources.add(FRONT.resolve("lib/api.ts"));
        sources.add(FRONT.resolve("lib/boss-types.ts"));
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            if (!Files.isRegularFile(source)) {
                continue;
            }
            String content = Files.readString(source).toLowerCase(Locale.ROOT);
            for (String marker : List.of("mockdata", "mock-data", "mock_", "fakedata", "fake data")) {
                if (content.contains(marker)) {
                    offenders.add(FRONT.relativize(source) + " -> " + marker);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "B4 看板源码不得使用 mock 数据：" + offenders);
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
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            target.addAll(stream.filter(B4FrontendDeliveryCheckTest::isTextSource).toList());
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
