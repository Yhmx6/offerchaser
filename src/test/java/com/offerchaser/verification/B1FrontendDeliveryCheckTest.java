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
 * 运行台 5 空间重构后的 B1 交付检查：今日/记录/待办 使用真实 API 与 SSE，
 * 顶栏空间导航 + 运行胶囊取代旧 Sidebar/全局运行条；旧路由以 LegacyRedirect 保留书签。
 * 只读检查源码与构建产物，不启动前端、不调用真实接口。
 */
class B1FrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void distContainsFiveSpacesAndLegacyRedirectShells() throws Exception {
        assertTrue(Files.isRegularFile(DIST.resolve("index.html")), "dist 缺少 index.html");
        String index = Files.readString(DIST.resolve("index.html"));
        assertTrue(index.contains("正在打开工作台"), "index.html 应是到 /boss 的重定向骨架");
        assertTrue(index.length() > 5_000, "index.html 体积异常：" + index.length());

        Path bossHtml = DIST.resolve("boss.html");
        assertTrue(Files.isRegularFile(bossHtml), "dist 缺少 boss.html（今日）");
        assertTrue(Files.size(bossHtml) > 20_000, "boss.html 体积异常：" + Files.size(bossHtml));
        String today = Files.readString(bossHtml);
        for (String text : List.of("今日", "投递管线", "事件流", "生效模板", "开始投递",
                "今日投递数", "总投递数", "总回复数", "跟进待办", "需手动核对", "状态同步")) {
            assertTrue(today.contains(text), "dist 今日页应渲染：" + text);
        }
        for (String href : List.of("/boss/inbox", "/boss/ledger", "/boss/setup", "/boss/insights")) {
            assertTrue(today.contains("href=\"" + href + "\""),
                    "dist 今日页顶栏应包含空间链接：" + href);
        }

        assertSpacePage("boss/inbox.html", 15_000, "待办");
        assertSpacePage("boss/ledger.html", 15_000, "记录");
        assertSpacePage("boss/setup.html", 20_000, "配置");
        assertSpacePage("boss/insights.html", 20_000, "洞察");
        assertTrue(Files.size(DIST.resolve("boss/inbox.html")) > 15_000);

        // 13 个旧路由保留为客户端重定向壳，书签继续可用
        for (String legacy : List.of("flow", "tasks", "evaluations", "attempts", "contacts",
                "followups", "followup-strategy", "analysis", "reviews", "rules", "templates",
                "advanced")) {
            Path html = DIST.resolve("boss/" + legacy + ".html");
            assertTrue(Files.isRegularFile(html), "dist 缺少旧路由占位：" + legacy);
            assertTrue(Files.readString(html).contains("正在跳转到新工作台"),
                    "旧路由应输出重定向提示：" + legacy);
        }
        Path aiConfig = DIST.resolve("ai-config.html");
        assertTrue(Files.isRegularFile(aiConfig), "dist 缺少旧 /ai-config 占位");
        assertTrue(Files.readString(aiConfig).contains("正在跳转到新工作台"));
        assertTrue(Files.readString(FRONT.resolve("app/boss/tasks/page.tsx"))
                        .contains("LegacyRedirect"),
                "旧页面源码必须使用 LegacyRedirect 组件");
    }

    @Test
    void requiredFrontSourceFilesExist() {
        for (String relative : List.of(
                "lib/api.ts",
                "lib/auth.ts",
                "lib/boss-types.ts",
                "lib/boss-events.ts",
                "lib/use-boss-status.ts",
                "app/lib/spaces.ts",
                "app/layout.tsx",
                "app/page.tsx",
                "app/boss/layout.tsx",
                "app/boss/page.tsx",
                "app/boss/inbox/page.tsx",
                "app/boss/ledger/page.tsx",
                "app/boss/setup/page.tsx",
                "app/boss/insights/page.tsx",
                "app/components/shell/TopBar.tsx",
                "app/components/shell/RunCapsule.tsx",
                "app/components/shell/BossStatusProvider.tsx",
                "app/components/shell/LegacyRedirect.tsx",
                "app/components/shell/ThemeProvider.tsx",
                "app/components/views/InsightsMetricsView.tsx")) {
            assertTrue(Files.isRegularFile(FRONT.resolve(relative)),
                    "front 缺少交付文件：" + relative);
        }
    }

    @Test
    void apiClientCoversB1Endpoints() throws Exception {
        String api = Files.readString(FRONT.resolve("lib/api.ts"));
        for (String path : List.of(
                "/api/boss/status",
                "/api/boss/delivery/summary",
                "/api/boss/runs",
                "/api/boss/evaluations",
                "/api/boss/attempts")) {
            assertTrue(api.contains(path), "lib/api.ts 缺少真实接口：" + path);
        }
        String events = Files.readString(FRONT.resolve("lib/boss-events.ts"));
        assertTrue(events.contains("/api/boss/stream") && events.contains("subscribeBossEvents"),
                "SSE 订阅必须走真实 /api/boss/stream");
        assertTrue(events.contains("createSSEWithBackoff"), "SSE 重连必须复用 backoff 客户端");
        for (String function : List.of("getCapabilities", "getBossStatus", "getDeliverySummary",
                "startBoss", "stopBoss", "resumeVerification", "forceReset", "getRuns",
                "getEvaluations", "getAttempts", "getAttempt", "resolveAttempt")) {
            assertTrue(api.contains("function " + function), "lib/api.ts 缺少函数：" + function);
        }
    }

    @Test
    void sseAndTypesCoverB1Contracts() throws Exception {
        String events = Files.readString(FRONT.resolve("lib/boss-events.ts"));
        assertTrue(events.contains("/api/boss/stream"), "SSE 必须连接真实 /api/boss/stream");
        assertTrue(events.contains("createSSEWithBackoff"), "SSE 重连必须复用 backoff 客户端");
        assertTrue(events.contains("snapshot") && events.contains("progress"),
                "SSE 必须处理 snapshot/progress 两类事件");
        String types = Files.readString(FRONT.resolve("lib/boss-types.ts"));
        for (String field : List.of("SseProgress", "PageResult", "RunItem", "EvaluationItem",
                "AttemptItem", "AttemptDetail", "ResolveRequest", "ResolveResult",
                "pendingResolutions", "workerExited", "canStart", "runId", "seq", "state")) {
            assertTrue(types.contains(field), "boss-types 缺少契约类型/字段：" + field);
        }
    }

    @Test
    void spacesDefineFiveSpacesAndLegacyEntries() throws Exception {
        String spaces = Files.readString(FRONT.resolve("app/lib/spaces.ts"));
        for (String key : List.of("today", "inbox", "ledger", "setup", "insights")) {
            assertTrue(spaces.contains("' " + key + "'") || spaces.contains("'" + key + "'"),
                    "spaces 缺少空间 key：" + key);
        }
        for (String label : List.of("今日", "待办", "记录", "配置", "洞察")) {
            assertTrue(spaces.contains(label), "spaces 缺少空间文案：" + label);
        }
        for (String href : List.of("'/boss'", "'/boss/inbox'", "'/boss/ledger'",
                "'/boss/setup'", "'/boss/insights'")) {
            assertTrue(spaces.contains(href), "spaces 缺少路由：" + href);
        }
        // 13 个旧入口映射，保留 query；关键映射语义不能丢
        for (String slug : List.of("flow", "tasks", "evaluations", "attempts", "contacts",
                "followups", "followup-strategy", "analysis", "reviews", "rules", "templates",
                "advanced", "ai-config")) {
            assertTrue(spaces.contains("'" + slug + "'"), "spaces 缺少旧入口映射：" + slug);
        }
        for (String mapping : List.of("kind=release", "kind=followup", "entity=attempts",
                "entity=evaluations", "entity=runs", "entity=contacts", "tab=rules", "tab=ai")) {
            assertTrue(spaces.contains(mapping), "旧路由映射缺少语义：" + mapping);
        }
        assertTrue(Files.readString(FRONT.resolve("app/components/shell/LegacyRedirect.tsx"))
                .contains("legacyTarget"), "LegacyRedirect 必须复用统一映射函数");
    }

    @Test
    void topBarAndRunCapsuleOwnRunControls() throws Exception {
        String topBar = Files.readString(FRONT.resolve("app/components/shell/TopBar.tsx"));
        for (String marker : List.of("SPACES", "RunCapsule", "CommandPalette", "useSharedBossStatus",
                "THEME_LABELS", "cycleTheme", "logout", "dispatchAuthRequired")) {
            assertTrue(topBar.contains(marker), "TopBar 缺少：" + marker);
        }
        String capsule = Files.readString(FRONT.resolve("app/components/shell/RunCapsule.tsx"));
        for (String marker : List.of("startDisabled", "stopDisabled", "resumeDisabled",
                "forceResetDisabled", "开始投递", "停止投递", "继续任务", "强制复位",
                "ConfirmDialog", "actions", "PAUSED_VERIFICATION")) {
            assertTrue(capsule.contains(marker), "运行胶囊缺少按钮/逻辑：" + marker);
        }
        String todayPage = Files.readString(FRONT.resolve("app/boss/page.tsx"));
        for (String marker : List.of("RUN · 开始一轮投递", "开始投递", "startDisabled",
                "actions.start()", "BOSS 未登录")) {
            assertTrue(todayPage.contains(marker), "今日页缺少独立开始面板：" + marker);
        }
        assertTrue(capsule.contains("const onToday = pathname === '/boss'")
                        && capsule.contains(") : onToday ? null : ("),
                "顶部 RunCapsule 必须在 /boss 隐藏小号开始按钮");
        String shellLayout = Files.readString(FRONT.resolve("app/boss/layout.tsx"));
        assertTrue(shellLayout.contains("BossStatusProvider") && shellLayout.contains("<TopBar"),
                "BOSS 布局必须挂载共享状态与顶栏");
        assertTrue(shellLayout.contains("NotificationBar"), "BOSS 布局应挂载状态通知条");
    }

    @Test
    void todayPageBindsRealStatusWithoutDuplicatingRunActions() throws Exception {
        String home = Files.readString(FRONT.resolve("app/page.tsx"));
        assertTrue(home.contains("router.replace('/boss')"));
        assertTrue(home.contains("正在打开工作台"), "首页骨架需有过渡文案");

        String today = Files.readString(FRONT.resolve("app/boss/page.tsx"));
        for (String marker : List.of("useSharedBossStatus", "getTemplates", "StatusSyncCard",
                "getAttempts", "getContacts", "getEffectiveFollowups", "pendingResolutions",
                "今日投递数", "总投递数", "总回复数", "跟进待办", "需手动核对",
                "DialogShell", "详情 · 只读", "最多展示 20 条",
                "按当前生效模板执行。", "需手动点击开始。",
                "BOSS 未登录，请先扫码。", "worker 尚未退出，可先强制复位。", "当前状态不可开始。",
                "投递管线", "事件流", "今日首次沟通预算")) {
            assertTrue(today.contains(marker), "今日页缺少真实数据绑定：" + marker);
        }
        for (String forbidden : List.of("startBoss(", "stopBoss(", "forceReset(", "resumeVerification(",
                "updateBossConfig", "saveRules(")) {
            assertFalse(today.contains(forbidden), "今日页不应复制运行控制/写配置：" + forbidden);
        }
        assertTrue(today.contains("/boss/setup"), "今日页应引导到配置空间");

        // 第二卡为「总投递数」：全量确认首次沟通，详情不带 from/to
        assertTrue(today.contains("label=\"总投递数\""), "第二卡应为「总投递数」");
        assertFalse(today.contains("前一日投递数"), "不得再保留「前一日投递数」");
        assertTrue(today.contains("total: '总投递明细'"), "详情标题应为「总投递明细」");
        assertTrue(today.contains("kind === 'today' || kind === 'total'"),
                "今日/总投递共用 attempts 详情分支");
        assertTrue(today.contains("kind === 'today' ? localDayBounds(0) : null"),
                "只有今日详情带日期范围，总投递不带 from/to");
        assertTrue(today.contains("...(range ? { from: range.from, to: range.to } : {})"),
                "无 range 时请求不得带 from/to");
        assertFalse(today.contains("yesterday"), "页面不得再有昨日统计逻辑");
        assertFalse(today.contains("开始投递不是自动跑"), "今日页不得保留旧长说明");
        assertFalse(today.contains("运行控制只在顶栏胶囊") || today.contains("本页不发任何写请求"),
                "今日 JSDoc 不得保留过期说明");
        assertFalse(today.contains("BOSS 未登录：请先在 Chrome 窗口扫码登录，再点开始投递。"),
                "禁用原因不得再用旧长句");

        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        assertTrue(setup.contains("方向模板 = 搜索条件 + 筛选规则 + AI + 简历；模型服务为全局配置。"),
                "配置空间顶部说明应精简");
        assertFalse(setup.contains("方向模板包含该方向的搜索条件、筛选规则、AI 介绍/提示词与简历；模型服务是全局配置。"),
                "配置空间旧长说明不得保留");

        // 五卡视觉一致：需手动核对不再使用 tone=warn/告警描边
        assertFalse(today.contains("tone={pendingResolutions > 0"),
                "需手动核对卡不再根据待核对数使用 warn 描边");
        assertFalse(today.contains("tone=\"warn\""), "五卡不得使用 warn tone");
        assertTrue(today.contains("label=\"需手动核对\" value={pendingResolutions} hint=\"UNKNOWN 不计入成功\" onClick"),
                "需手动核对卡应保持只读统计与点击详情");

        String controller = Files.readString(FRONT.resolve("lib/use-boss-status.ts"));
        for (String function : List.of("getBossStatus", "getDeliverySummary", "subscribeBossEvents",
                "startBoss", "stopBoss", "resumeVerification", "forceReset")) {
            assertTrue(controller.contains(function), "状态控制器缺少真实 API/SSE：" + function);
        }
        for (String field : List.of("pendingResolutions", "workerExited", "canStart", "STOPPING",
                "forceResetDisabled", "startDisabled", "700")) {
            assertTrue(controller.contains(field), "状态控制器缺少契约字段/节流：" + field);
        }
    }

    @Test
    void ledgerAndInboxCallRealApisAndHandleConflicts() throws Exception {
        String ledger = Files.readString(FRONT.resolve("app/boss/ledger/page.tsx"));
        for (String marker : List.of("getRuns", "getEvaluations", "getAttempts", "getAttempt",
                "putContactState", "recheckEvaluations", "expectedVersion", "ApiError", "409",
                "DataTable", "templateName", "templateDirection", "<Th>模板</Th>")) {
            assertTrue(ledger.contains(marker), "记录空间缺少真实查询/契约：" + marker);
        }
        String inbox = Files.readString(FRONT.resolve("app/boss/inbox/page.tsx"));
        for (String marker : List.of("getAttempts", "getManualReleases", "getFollowups",
                "resolveAttempt", "decideManualRelease", "approveFollowup", "rejectFollowup",
                "createImageResendPlan", "expectedVersion", "ApiError", "409")) {
            assertTrue(inbox.contains(marker), "待办空间缺少真实 API/409 处理：" + marker);
        }
    }

    @Test
    void noMockDataInB1FrontendSources() throws Exception {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> lib = Files.list(FRONT.resolve("lib"))) {
            lib.filter(B1FrontendDeliveryCheckTest::isTextSource).forEach(sources::add);
        }
        try (Stream<Path> app = Files.walk(FRONT.resolve("app"))) {
            app.filter(B1FrontendDeliveryCheckTest::isTextSource).forEach(sources::add);
        }
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
        assertTrue(offenders.isEmpty(), "前端源码不得使用 mock 数据：" + offenders);
    }

    private static void assertSpacePage(String relative, long minimumSize, String text) throws Exception {
        Path html = DIST.resolve(relative);
        assertTrue(Files.isRegularFile(html), "dist 缺少空间页面：" + relative);
        assertTrue(Files.size(html) > minimumSize, relative + " 体积异常（可能空壳）：" + Files.size(html));
        assertTrue(Files.readString(html).contains(text), relative + " 应渲染：" + text);
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
