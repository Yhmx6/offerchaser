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
 * 5 空间重构后的 B3 交付检查：联系人归「记录」空间、跟进与图片补发归「待办」空间；
 * 真实 API、人工批准口径、版本冲突、旧路由重定向、无 mock。
 */
class B3FrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void contactsEntityUsesRealApis() throws Exception {
        Path ledger = FRONT.resolve("app/boss/ledger/page.tsx");
        assertTrue(Files.isRegularFile(ledger), "缺少记录空间页面");
        assertTrue(Files.size(ledger) > 20_000, "记录空间过小，可能是占位实现");
        String source = Files.readString(ledger);
        for (String function : List.of("getContacts", "putContactState")) {
            assertTrue(source.contains(function), "联系人实体必须调用真实 API：" + function);
        }
        for (String marker : List.of("expectedVersion", "ApiError", "409", "REPLIED",
                "manualTakeover", "stopContact", "只读", "决策在「待办」完成")) {
            assertTrue(source.contains(marker), "联系人实体缺少契约/文案：" + marker);
        }
        assertTrue(Files.readString(FRONT.resolve("app/boss/contacts/page.tsx"))
                .contains("LegacyRedirect"), "旧 /boss/contacts 应保留重定向");
    }

    @Test
    void inboxFollowupDecisionsUseRealApisAndApprovalFlow() throws Exception {
        Path inbox = FRONT.resolve("app/boss/inbox/page.tsx");
        assertTrue(Files.isRegularFile(inbox), "缺少待办空间页面");
        String source = Files.readString(inbox);
        for (String function : List.of("getFollowups", "approveFollowup", "rejectFollowup")) {
            assertTrue(source.contains(function), "跟进决策必须调用真实 API：" + function);
        }
        String api = Files.readString(FRONT.resolve("lib/api.ts"));
        assertTrue(api.contains("function generateFollowups"),
                "lib/api.ts 应保留真实生成跟进建议接口");
        for (String marker : List.of("SUGGESTED", "批准", "不会立即发送", "下一轮",
                "expectedVersion")) {
            assertTrue(source.contains(marker), "跟进待办缺少状态/口径：" + marker);
        }
        String types = Files.readString(FRONT.resolve("lib/boss-types.ts"));
        assertTrue(types.contains("SUGGESTED") && types.contains("APPROVED")
                        && types.contains("REJECTED"),
                "跟进状态类型应覆盖 SUGGESTED/APPROVED/REJECTED");
    }

    @Test
    void inboxDetailAddsSafeJobUrlHeaderLink() throws Exception {
        Path inbox = FRONT.resolve("app/boss/inbox/page.tsx");
        String source = Files.readString(inbox);
        assertTrue(source.contains("function safeJobUrl("), "Inbox 必须有 safeJobUrl 校验函数");
        assertTrue(source.contains("new URL(candidate)"), "safeJobUrl 必须用 URL 解析");
        assertTrue(source.contains("parsed.protocol === 'http:'")
                        && source.contains("parsed.protocol === 'https:'"),
                "safeJobUrl 仅允许 http/https");
        assertTrue(source.contains("parsed.toString()") && source.contains("return null"),
                "safeJobUrl 非法/缺失 URL 必须返回 null");
        assertTrue(source.contains("ExternalLink"), "打开岗位页应使用 ExternalLink 图标");
        assertTrue(source.contains("打开岗位页"), "详情头部应有「打开岗位页」外链");
        assertTrue(source.contains("target=\"_blank\""), "外链必须在新的浏览上下文打开");
        assertTrue(source.contains("rel=\"noopener noreferrer\""),
                "外链必须带 noopener noreferrer");
        assertTrue(source.contains("{safeJobUrl(selected) ? (") && source.contains(") : null}"),
                "无 jobUrl 时必须隐藏外链");
        assertTrue(source.contains("(item.raw as { jobUrl?: string | null }).jobUrl"),
                "jobUrl 必须从当前待办 item.raw 读取");

        String types = Files.readString(FRONT.resolve("lib/boss-types.ts"));
        assertTrue(types.contains("jobUrl?: string | null"), "ManualReleaseItem 应包含可选 jobUrl");
        assertTrue(types.contains("interface ManualReleaseItem"), "缺少 ManualReleaseItem 类型");
    }

    @Test
    void inboxImageResendPlanUsesRealApiAndStaysPending() throws Exception {
        String inbox = Files.readString(FRONT.resolve("app/boss/inbox/page.tsx"));
        assertTrue(inbox.contains("createImageResendPlan"), "待办空间必须调用真实补发计划接口");
        assertTrue(inbox.contains("补发"), "待办空间应有图片补发分类/入口");
        for (String marker : List.of("不会自动执行", "建议跟进", "仍需批准", "下一轮")) {
            assertTrue(inbox.contains(marker), "补发计划必须说明只生成待办、不自动执行：" + marker);
        }
        assertTrue(inbox.contains("文字已确认 + 图片失败/未知"),
                "补发计划应说明生成条件（parent attempt 的文字/图片状态）");
        assertTrue(Files.readString(FRONT.resolve("app/boss/attempts/page.tsx"))
                .contains("LegacyRedirect"), "旧 /boss/attempts 应保留重定向");
    }

    @Test
    void apiAndTypesCoverB3Contracts() throws Exception {
        String api = Files.readString(FRONT.resolve("lib/api.ts"));
        for (String path : List.of("/api/boss/contacts", "/state", "/api/boss/followups",
                "/followups/generate", "/approve", "/reject", "/resend-image")) {
            assertTrue(api.contains(path), "lib/api.ts 缺少 B3 接口：" + path);
        }
        for (String function : List.of("getContacts", "getContact", "putContactState", "getFollowups",
                "generateFollowups", "approveFollowup", "rejectFollowup", "createImageResendPlan")) {
            assertTrue(api.contains("function " + function), "lib/api.ts 缺少函数：" + function);
        }
        String types = Files.readString(FRONT.resolve("lib/boss-types.ts"));
        for (String type : List.of("ContactState", "ContactPage", "ContactDetail", "ContactStateRequest",
                "FollowupPlan", "FollowupPage", "FollowupPlanType", "ImageResendPlan")) {
            assertTrue(types.contains(type), "lib/boss-types.ts 缺少 B3 类型：" + type);
        }
        assertTrue(types.contains("IMAGE_RESEND"), "缺少补发图片计划类型常量");
        assertTrue(types.contains("SUGGESTED") && types.contains("APPROVED"),
                "缺少跟进状态类型常量");
    }

    @Test
    void ledgerAndInboxSpacesAndDistContainB3Pages() throws Exception {
        String spaces = Files.readString(FRONT.resolve("app/lib/spaces.ts"));
        assertTrue(spaces.contains("entity=contacts"), "旧联系人入口应映射到记录空间");
        assertTrue(spaces.contains("kind=followup"), "旧跟进入口应映射到待办空间");
        assertTrue(spaces.contains("kind=resend"), "图片补发旧入口应映射到待办空间");

        for (String page : List.of("boss/ledger.html", "boss/inbox.html")) {
            Path dist = DIST.resolve(page);
            assertTrue(Files.isRegularFile(dist), "dist 缺少构建产物：" + page);
            assertTrue(Files.size(dist) > 15_000, "dist 页面体积异常：" + page);
        }
        String ledgerHtml = Files.readString(DIST.resolve("boss/ledger.html"));
        assertTrue(ledgerHtml.contains("联系人"), "dist 记录空间应包含联系人实体");
        String inboxHtml = Files.readString(DIST.resolve("boss/inbox.html"));
        assertTrue(inboxHtml.contains("跟进") && inboxHtml.contains("补发"),
                "dist 待办空间应包含跟进/补发分类");
        assertTrue(distContainsAny("resend-image", "补发图片", "createImageResendPlan"),
                "dist 客户端 chunk 应包含补发计划入口");
        assertTrue(distContainsAny("approveFollowup", "批准"),
                "dist 客户端 chunk 应包含跟进批准逻辑");
    }

    @Test
    void noMockDataInB3FrontendSources() throws Exception {
        List<Path> sources = new ArrayList<>();
        addTextSources(sources, FRONT.resolve("app/boss/inbox"));
        addTextSources(sources, FRONT.resolve("app/boss/ledger"));
        addTextSources(sources, FRONT.resolve("lib"));
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String content = Files.readString(source).toLowerCase(Locale.ROOT);
            for (String marker : List.of("mockdata", "mock-data", "mock_", "fakedata", "fake data")) {
                if (content.contains(marker)) {
                    offenders.add(FRONT.relativize(source) + " -> " + marker);
                }
            }
        }
        assertFalse(offenders.stream().anyMatch(item -> item.contains("inbox")
                        || item.contains("ledger") || item.contains("api.ts")
                        || item.contains("boss-types")),
                "B3 前端源码不得使用 mock 数据：" + offenders);
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
            target.addAll(stream.filter(B3FrontendDeliveryCheckTest::isTextSource).toList());
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
