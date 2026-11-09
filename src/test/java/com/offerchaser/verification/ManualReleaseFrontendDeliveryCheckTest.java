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
 * 5 空间重构后的人工放行前端交付检查：放行队列归「待办」空间（/boss/inbox?kind=release），
 * 理由必填、二次确认、409 冲突刷新、不重试、批准只入队不立即发送；旧 /boss/reviews 重定向。
 */
class ManualReleaseFrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void inboxReleaseDecisionsUseRealApisAndSafetyCopy() throws Exception {
        Path inbox = FRONT.resolve("app/boss/inbox/page.tsx");
        assertTrue(Files.isRegularFile(inbox), "缺少待办空间页面");
        String page = Files.readString(inbox);
        for (String api : List.of("getManualReleases", "decideManualRelease")) {
            assertTrue(page.contains(api), "人工放行必须调用真实 API：" + api);
        }
        assertTrue(page.contains("getManualReleases('PENDING')"), "必须读取 PENDING 队列");
        for (String marker : List.of("请填写原因（必填", "500", "maxLength={500}",
                "确认执行「", "原因会写入审计")) {
            assertTrue(page.contains(marker), "待办决策缺少理由/二次确认：" + marker);
        }
        for (String copy : List.of("批准只入队，不会立即发送", "下一轮任务执行",
                "重新 AI 评估", "身份", "预算", "文字+图片")) {
            assertTrue(page.contains(copy), "人工放行安全口径缺失：" + copy);
        }
        assertTrue(page.contains("ApiError") && page.contains("409"),
                "重复决策 409 必须作为冲突提示并刷新");
        assertTrue(page.contains("不自动重试"), "409 后不得自动重试");
    }

    @Test
    void spacesRouteManualReleaseAndTopBarCountsTodos() throws Exception {
        String spaces = Files.readString(FRONT.resolve("app/lib/spaces.ts"));
        assertTrue(spaces.contains("'reviews'"), "spaces 应保留旧 reviews 入口");
        assertTrue(spaces.contains("kind=release"), "旧 reviews 应映射到待办空间的 release");
        String topBar = Files.readString(FRONT.resolve("app/components/shell/TopBar.tsx"));
        assertTrue(topBar.contains("getManualReleases") && topBar.contains("PENDING"),
                "顶栏待办计数应读取人工放行 PENDING 数量");
        assertTrue(Files.readString(FRONT.resolve("app/boss/reviews/page.tsx"))
                .contains("LegacyRedirect"), "旧 /boss/reviews 应重定向");
    }

    @Test
    void apiAndTypesCoverManualReleaseContracts() throws Exception {
        String api = Files.readString(FRONT.resolve("lib/api.ts"));
        assertTrue(api.contains("function getManualReleases"));
        assertTrue(api.contains("function decideManualRelease"));
        assertTrue(api.contains("/api/boss/manual-releases"));
        String types = Files.readString(FRONT.resolve("lib/boss-types.ts"));
        for (String type : List.of("ManualReleaseStatus", "ManualReleaseItem", "ManualReleaseQueue",
                "ManualReleaseDecision", "ManualReleaseDecisionResult", "releaseId")) {
            assertTrue(types.contains(type), "boss-types 缺少人工放行类型：" + type);
        }
        for (String status : List.of("PENDING", "APPROVED", "REJECTED", "CONSUMED", "FAILED")) {
            assertTrue(types.contains(status), "ManualReleaseStatus 缺少状态：" + status);
        }
    }

    @Test
    void distContainsBuiltInboxReleaseFlow() throws Exception {
        Path inboxHtml = DIST.resolve("boss/inbox.html");
        assertTrue(Files.isRegularFile(inboxHtml), "dist 缺少 boss/inbox.html");
        assertTrue(Files.size(inboxHtml) > 15_000, "dist inbox.html 体积异常：" + Files.size(inboxHtml));
        String html = Files.readString(inboxHtml);
        assertTrue(html.contains("待放行") && html.contains("批准"),
                "dist 待办页应渲染人工放行分类/动作");
        assertTrue(distContainsAny("decideManualRelease", "manual-releases"),
                "dist 客户端 chunk 应包含人工放行决策调用");
        assertTrue(distContainsAny("批准只入队", "不会立即发送"),
                "dist 客户端 chunk 应包含批准口径文案");
        assertTrue(Files.readString(DIST.resolve("boss/reviews.html"))
                .contains("正在跳转到新工作台"), "dist 旧 reviews.html 应为重定向壳");
    }

    @Test
    void noDirectSendOrFetchBypass() throws Exception {
        String inbox = Files.readString(FRONT.resolve("app/boss/inbox/page.tsx")).toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("startboss(", "stopboss(", "sendmessage(",
                "fetch('/api/boss/manual-releases", "fetch(\"/api/boss/manual-releases")) {
            assertFalse(inbox.contains(forbidden), "待办决策不得绕过 API 封装/直接发送：" + forbidden);
        }
        List<Path> sources = List.of(FRONT.resolve("app/boss/inbox/page.tsx"));
        for (Path source : sources) {
            String content = Files.readString(source).toLowerCase(Locale.ROOT);
            for (String marker : List.of("mockdata", "mock-data", "mock_", "fakedata")) {
                assertFalse(content.contains(marker), "不得使用 mock 数据：" + marker);
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
