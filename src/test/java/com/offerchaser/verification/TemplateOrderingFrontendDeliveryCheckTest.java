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
 * 5 空间重构后的模板拖拽排序交付检查：拖拽实现迁入配置空间（/boss/setup）；
 * HTML5 drag、乐观更新+失败回滚、PUT /order 封装、旧模板页重定向与 dist 同步。
 */
class TemplateOrderingFrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void setupPageImplementsHtml5DragAndDrop() throws Exception {
        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        for (String marker : List.of("draggable", "onDragStart", "onDragOver", "onDrop",
                "dragIdRef", "模板列表", "reorderTemplates")) {
            assertTrue(setup.contains(marker), "配置空间缺少拖拽实现：" + marker);
        }
        assertTrue(setup.contains("排序保存失败") && setup.contains("已回滚"),
                "接口失败必须回滚并提示");
        assertTrue(setup.contains("按服务端顺序") || setup.contains("服务端"),
                "回滚后应以服务端顺序为准");
    }

    @Test
    void apiClientExposesReorderEndpoint() throws Exception {
        String api = Files.readString(FRONT.resolve("lib/api.ts"));
        assertTrue(api.contains("function reorderTemplates"), "lib/api.ts 缺少 reorderTemplates");
        assertTrue(api.contains("/api/boss/templates/order"), "必须调用 PUT /api/boss/templates/order");
        assertTrue(api.contains("method: 'PUT'"), "排序必须是 PUT");
        assertTrue(api.contains("body: { order }"), "请求体应为 { order }");
    }

    @Test
    void templateListLayoutNotRegressed() throws Exception {
        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        for (String text : List.of("方向模板", "编辑", "激活", "复制为新模板", "用当前配置覆盖",
                "删除", "配置")) {
            assertTrue(setup.contains(text), "模板列表/操作不应回归：" + text);
        }
        String spaces = Files.readString(FRONT.resolve("app/lib/spaces.ts"));
        assertTrue(spaces.contains("/boss/setup") && spaces.contains("配置"),
                "配置空间入口应保持");
    }

    @Test
    void distContainsDragOrderingCode() throws Exception {
        assertTrue(distContainsAny("/api/boss/templates/order"), "dist 应包含排序接口调用");
        assertTrue(distContainsAny("reorderTemplates", "排序保存失败"), "dist 应包含排序逻辑/失败提示");
        assertTrue(Files.readString(DIST.resolve("boss/setup.html")).contains("方向模板"),
                "dist 配置空间应渲染模板列表");
        assertTrue(Files.readString(DIST.resolve("boss/templates.html"))
                .contains("正在跳转到新工作台"), "dist 旧 templates.html 应为重定向壳");
        long distFiles;
        try (Stream<Path> stream = Files.walk(DIST)) {
            distFiles = stream.filter(Files::isRegularFile).count();
        }
        assertTrue(distFiles >= 130, "dist 文件数量异常：" + distFiles);
    }

    @Test
    void noMockDataInOrderingSources() throws Exception {
        List<Path> sources = List.of(
                FRONT.resolve("app/boss/setup/page.tsx"),
                FRONT.resolve("lib/api.ts"));
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
