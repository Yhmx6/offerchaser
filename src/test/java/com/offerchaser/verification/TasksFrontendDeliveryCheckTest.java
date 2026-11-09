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
 * 5 空间重构后的任务记录/本轮模板交付检查：任务记录归「记录」空间（/boss/ledger?entity=runs），
 * 表格展示「模板」列（templateName/templateDirection，旧记录为 —）；旧 /boss/tasks 重定向；dist 同步。
 */
class TasksFrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void ledgerRunsEntityShowsTemplateColumn() throws Exception {
        Path page = FRONT.resolve("app/boss/ledger/page.tsx");
        assertTrue(Files.isRegularFile(page), "缺少记录空间页面");
        String source = Files.readString(page);
        assertTrue(source.contains("<Th>模板</Th>"), "任务记录表必须有「模板」列");
        assertTrue(source.contains("templateName") && source.contains("templateDirection"),
                "模板列必须使用 templateName/templateDirection");
        assertTrue(source.contains("模板："), "展开行应展示模板名称与方向");
        assertTrue(source.contains("getRuns"), "任务记录实体必须调用真实 getRuns");
        assertTrue(source.contains("runId") && source.contains("stopReason") && source.contains("error"),
                "任务记录应展示 runId/停止原因/错误");
        assertTrue(source.contains("'runs'"), "记录空间应有任务记录实体");
        assertTrue(source.contains("—"), "缺少模板的旧记录应显示「—」");
        assertTrue(Files.readString(FRONT.resolve("app/boss/tasks/page.tsx"))
                .contains("LegacyRedirect"), "旧 /boss/tasks 应重定向到记录空间");
    }

    @Test
    void runItemTypeHasTemplateFields() throws Exception {
        String types = Files.readString(FRONT.resolve("lib/boss-types.ts"));
        int runItemIndex = types.indexOf("interface RunItem");
        assertTrue(runItemIndex >= 0, "缺少 RunItem 类型");
        String runItem = types.substring(runItemIndex, Math.min(types.length(), runItemIndex + 900));
        for (String field : List.of("templateId", "templateName", "templateDirection")) {
            assertTrue(runItem.contains(field), "RunItem 缺少字段：" + field);
        }
        assertTrue(runItem.contains("number | null") || runItem.contains("string | null"),
                "模板字段应允许 null（旧记录）");
    }

    @Test
    void distLedgerContainsTemplateColumn() throws Exception {
        Path ledgerHtml = DIST.resolve("boss/ledger.html");
        assertTrue(Files.isRegularFile(ledgerHtml), "dist 缺少 boss/ledger.html");
        assertTrue(Files.size(ledgerHtml) > 15_000, "dist ledger.html 体积异常：" + Files.size(ledgerHtml));
        assertTrue(Files.readString(ledgerHtml).contains("任务记录"),
                "dist 记录空间应渲染任务记录实体");
        assertTrue(distContainsAny("templateName", "templateDirection"),
                "dist 客户端 chunk 应包含模板字段读取逻辑");
        assertTrue(distContainsAny("模板："), "dist 客户端 chunk 应包含展开行模板文案");
        assertTrue(Files.readString(DIST.resolve("boss/tasks.html"))
                .contains("正在跳转到新工作台"), "dist 旧 tasks.html 应为重定向壳");
        long distFiles;
        try (Stream<Path> stream = Files.walk(DIST)) {
            distFiles = stream.filter(Files::isRegularFile).count();
        }
        assertTrue(distFiles >= 130, "dist 文件数量异常：" + distFiles);
    }

    @Test
    void noMockDataInTasksSources() throws Exception {
        List<Path> sources = List.of(
                FRONT.resolve("app/boss/ledger/page.tsx"),
                FRONT.resolve("lib/boss-types.ts"));
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
