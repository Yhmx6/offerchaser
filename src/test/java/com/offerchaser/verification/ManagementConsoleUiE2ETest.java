package com.offerchaser.verification;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.FilePayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5 空间（今日/待办/记录/配置/洞察）真实浏览器 UI E2E。
 * <p>
 * 不启动 Spring、不连接招聘网站：JDK {@link HttpServer} 静态托管 {@code src/main/resources/dist}，
 * Playwright Chromium 打开导出页面，{@code ** /api/**} 全部由页面路由返回 fixture；
 * 写操作只命中 fixture（记录请求体断言），绝不调用真实后端写接口。
 * <p>
 * 覆盖：登录门、5 空间顶栏导航、运行胶囊、今日只读总览、待办批准的理由必填+二次确认、
 * 旧路由重定向保 query、三套主题切换、无 hydration 错误、配置空间模板编辑器四区块+保存、
 * 洞察空间跟进策略只读。
 * <p>
 * 仅在 {@code BOSS_UI_E2E=true} 时运行；{@code BOSS_TEST_CHROME} 可指定本机 Chrome。
 */
@EnabledIfEnvironmentVariable(named = "BOSS_UI_E2E", matches = "true")
class ManagementConsoleUiE2ETest {

    private static final Path DIST = Path.of("src", "main", "resources", "dist")
            .toAbsolutePath().normalize();

    private static HttpServer server;
    private static int port;
    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    private Page page;
    private final List<ApiCall> apiCalls = new CopyOnWriteArrayList<>();
    private final List<String> consoleErrors = new CopyOnWriteArrayList<>();
    private final List<String> pageErrors = new CopyOnWriteArrayList<>();
    private final AtomicBoolean authRequired = new AtomicBoolean(false);
    private final AtomicBoolean authenticated = new AtomicBoolean(true);
    private volatile String fixtureBossState = "RUNNING";
    private volatile boolean fixtureBossLoggedIn = true;
    private volatile boolean fixtureBossWorkerExited = false;
    private volatile boolean fixtureBossCanStart = false;

    private record ApiCall(String method, String path, String query, String body) {
    }

    @BeforeAll
    static void launch() throws Exception {
        assertTrue(Files.isDirectory(DIST), "dist 不存在，请先构建前端：" + DIST);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ManagementConsoleUiE2ETest::serveStatic);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();

        var environment = new java.util.HashMap<>(System.getenv());
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(environment));
        var options = new BrowserType.LaunchOptions().setHeadless(true);
        String chrome = System.getenv("BOSS_TEST_CHROME");
        if (chrome != null && !chrome.isBlank()) {
            options.setExecutablePath(Path.of(chrome));
        }
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        authRequired.set(false);
        authenticated.set(true);
        fixtureBossState = "RUNNING";
        fixtureBossLoggedIn = true;
        fixtureBossWorkerExited = false;
        fixtureBossCanStart = false;
        apiCalls.clear();
        consoleErrors.clear();
        pageErrors.clear();
        context = browser.newContext();
        // SSE 不连真实本机后端。
        context.addInitScript("""
                window.EventSource = class {
                  constructor() { this.readyState = 0; }
                  addEventListener() {}
                  removeEventListener() {}
                  close() { this.readyState = 2; }
                };
                """);
        page = context.newPage();
        page.onConsoleMessage(message -> {
            if ("error".equals(message.type())) {
                consoleErrors.add(message.text());
            }
        });
        page.onPageError(error -> pageErrors.add(String.valueOf(error)));
        context.route("**/api/**", this::handleApi);
    }

    @AfterEach
    void cleanup() {
        context.close();
    }

    // ==================== 用例 ====================

    @Test
    void loginGateBlocksAndUnlocksConsole() {
        authRequired.set(true);
        authenticated.set(false);
        open("/boss");

        waitVisible("OfferChaser · 本机登录");
        assertTrue(page.locator("text=管理台写操作需要本机会话认证").first().isVisible(),
                "登录门应说明写操作需要认证");
        assertTrue(page.locator("text=data/local-session-token").first().isVisible(),
                "登录门应提示令牌文件路径");

        page.locator("#session-token").fill("e2e-local-token");
        page.locator("button:has-text(\"登录管理台\")").click();
        page.waitForSelector("#session-token", new Page.WaitForSelectorOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED)
                .setTimeout(15_000));
        page.waitForSelector("text=投递管线", new Page.WaitForSelectorOptions().setTimeout(15_000));
        assertTrue(page.locator("#session-token").count() == 0,
                "登录成功后应关闭登录门");
        assertTrue(apiCalls.stream().anyMatch(call -> "POST".equals(call.method())
                        && call.path().equals("/api/session/login")),
                "登录应调用真实 /api/session/login");
    }

    @Test
    void topBarExposesFiveSpacesAndNavigates() {
        open("/boss");
        waitVisible("今日");

        var links = page.locator("nav[aria-label='空间切换'] a");
        assertEquals(5, links.count(), "顶栏应有 5 个空间入口");
        String navText = links.allTextContents().toString();
        for (String label : List.of("今日", "待办", "记录", "配置", "洞察")) {
            assertTrue(navText.contains(label), "顶栏缺少空间：" + label);
        }

        links.nth(1).click();
        page.waitForURL("**/boss/inbox", new Page.WaitForURLOptions().setTimeout(10_000));
        waitVisible("待办");

        page.locator("nav[aria-label='空间切换'] a:has-text(\"记录\")").first().click();
        page.waitForURL("**/boss/ledger", new Page.WaitForURLOptions().setTimeout(10_000));
        waitVisible("记录");
    }

    @Test
    void todayIsReadOnlyAndRunCapsuleOwnsActions() {
        open("/boss");
        waitVisible("今日");

        for (String text : List.of("投递管线", "事件流", "今日预算环", "状态同步",
                "今日投递数", "总投递数", "总回复数", "跟进待办", "需手动核对",
                "生效模板")) {
            assertTrue(page.locator("text=" + text).first().isVisible(),
                    "今日页缺少只读区块：" + text);
        }
        // 状态同步卡只保留标题/按钮/来源表格/今日昨日/每日补读等短文案
        for (String text : List.of("投递状态同步", "读取今日投递状态", "今日主动同步",
                "昨天启动补读", "来源", "每日首次启动会自动补读前一天。", "暂无记录")) {
            assertTrue(page.locator("text=" + text).first().isVisible(),
                    "状态同步卡缺少：" + text);
        }
        assertEquals(0, page.locator("text=还没有记录").count(),
                "状态同步卡不得再使用旧文案「还没有记录」");
        page.locator("button:has-text(\"读取今日投递状态\")").first().click();
        waitVisible("已读取 15 条会话");
        assertEquals(0, page.locator("header button:has-text(\"开始投递\")").count(),
                "今日页顶部 RunCapsule 不应再显示小号开始按钮");
        // 只允许用户显式点击状态同步产生的 POST；不得出现 start/approve/activate 等业务写请求
        boolean unexpectedWrite = apiCalls.stream().anyMatch(call -> !"GET".equals(call.method())
                && !(call.path().equals("/api/boss/status-sync") && "POST".equals(call.method())));
        assertFalse(unexpectedWrite, "今日页不得产生除显式状态同步之外的写请求：" + apiCalls);
    }

    @Test
    void jobInsightCardReadsBossLinkAndRendersReadonlyBrief() {
        open("/boss");
        waitVisible("岗位解读");
        var input = page.locator("[aria-label='BOSS 岗位链接']").first();
        assertTrue(input.isVisible(), "首页应显示岗位链接输入框");
        input.fill("https://www.zhipin.com/job_detail/e2e-insight.html");
        page.locator("button:has-text(\"解读岗位\")").first().click();
        waitVisible("岗位真正看重什么");
        waitVisible("你的匹配点");
        waitVisible("首次沟通话术");
        assertTrue(page.locator("text=只读，不会自动发消息").first().isVisible(),
                "岗位解读必须明确只读边界");
        ApiCall call = apiCalls.stream()
                .filter(item -> "POST".equals(item.method())
                        && item.path().equals("/api/boss/jobs/insight"))
                .findFirst().orElseThrow(() -> new AssertionError("应 POST /api/boss/jobs/insight"));
        assertTrue(call.body().contains("e2e-insight.html"), "链接应提交给后端：" + call.body());
    }

    @Test
    void todayFiveCardsShowCountsAndOpenReadonlyDetail() {
        open("/boss");
        // 计数走真实 attempts/contacts/followups/status fixture：今日3、昨日2、回复4、跟进1、需核对2。
        waitCardValue("今日投递数", "3");
        waitCardValue("总投递数", "9");
        waitCardValue("总回复数", "4");
        waitCardValue("跟进待办", "1");
        waitCardValue("需手动核对", "2");

        // 跟进待办：有效沟通联系人 → 公司/岗位/薪资/岗位页
        page.locator("button:has-text(\"跟进待办\")").first().click();
        waitVisible("有效沟通跟进");
        waitVisible("上海欧印传媒科技");
        waitVisible("15-25K");
        waitVisible("简历附件已确认");
        var jobLink = page.locator("[role='dialog'] a:has-text(\"AI 产品经理\")").first();
        assertTrue(jobLink.isVisible(), "有效沟通详情应能跳转岗位页");
        assertEquals("https://www.zhipin.com/job_detail/e2e-job.html", jobLink.getAttribute("href"));
        assertEquals("_blank", jobLink.getAttribute("target"), "岗位页外链应新开标签");
        page.locator("button[aria-label='关闭']").first().click();

        // 五卡视觉一致：需手动核对卡边框颜色应与普通卡一致（无 warn 描边）
        String normalBorder = String.valueOf(page.locator("button:has-text(\"今日投递数\")").first()
                .evaluate("el => getComputedStyle(el).borderColor"));
        String pendingBorder = String.valueOf(page.locator("button:has-text(\"需手动核对\")").first()
                .evaluate("el => getComputedStyle(el).borderColor"));
        assertEquals(normalBorder, pendingBorder, "需手动核对卡不应有 warn 告警描边");

        // 今日投递明细
        page.locator("button:has-text(\"今日投递数\")").first().click();
        page.waitForSelector("[role='dialog'][aria-label='今日投递明细']",
                new Page.WaitForSelectorOptions().setTimeout(5_000));
        var todayDialog = page.locator("[role='dialog'][aria-label='今日投递明细']");
        assertTrue(todayDialog.first().isVisible(), "点击今日投递数应打开详情弹层");
        waitVisible("共 3 条 · 最多展示 20 条");
        waitVisible("Java 开发");
        waitVisible("测试公司一");
        assertDetailRequest("今日明细", "size=20", "kind=FIRST_CONTACT",
                "textStatus=CONFIRMED", "from=");
        page.locator("button[aria-label='关闭']").first().click();

        // 总投递明细：全量 total=9，详情请求不得带 from/to
        page.locator("button:has-text(\"总投递数\")").first().click();
        assertTrue(page.locator("[role='dialog'][aria-label='总投递明细']").first().isVisible(),
                "点击总投递数应打开「总投递明细」弹层");
        waitVisible("共 9 条 · 最多展示 20 条");
        waitVisible("历史岗位1");
        ApiCall totalDetail = apiCalls.stream()
                .filter(item -> "GET".equals(item.method()) && item.query() != null
                        && item.query().contains("size=20")
                        && item.query().contains("kind=FIRST_CONTACT")
                        && item.query().contains("textStatus=CONFIRMED"))
                .filter(item -> !item.query().contains("from=") && !item.query().contains("to="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("总投递明细 GET 不应带 from/to：" + apiCalls));
        assertTrue(totalDetail.query().contains("size=20"),
                "总投递详情应拉最多 20 条：" + totalDetail.query());
        page.locator("button[aria-label='关闭']").first().click();

        // 总回复数
        page.locator("button:has-text(\"总回复数\")").first().click();
        assertTrue(page.locator("[role='dialog'][aria-label='已回复联系人']").first().isVisible());
        waitVisible("共 4 条 · 最多展示 20 条");
        waitVisible("HR 小王");
        assertDetailRequest("回复明细", "size=20", "replyState=REPLIED");
        assertTrue(page.locator("[role='dialog'] a[href*='/boss/ledger?entity=contacts']")
                .first().isVisible(), "回复详情应提供去联系人页链接");
        page.locator("button[aria-label='关闭']").first().click();

        // 跟进待办：有效沟通列表（第二次打开，验证请求口径）
        page.locator("button:has-text(\"跟进待办\")").first().click();
        assertTrue(page.locator("[role='dialog'][aria-label='有效沟通跟进']").first().isVisible());
        waitVisible("共 1 条 · 最多展示 20 条");
        waitVisible("AI 产品经理");
        assertTrue(apiCalls.stream().anyMatch(item -> "GET".equals(item.method())
                        && item.query() != null && item.query().contains("limit=20")),
                "有效沟通明细应请求 limit=20：" + apiCalls);
        page.locator("button[aria-label='关闭']").first().click();

        // 需手动核对
        page.locator("button:has-text(\"需手动核对\")").first().click();
        assertTrue(page.locator("[role='dialog'][aria-label='需手动核对']").first().isVisible());
        waitVisible("共 2 条 · 最多展示 20 条");
        waitVisible("未知岗位");
        assertDetailRequest("核对明细", "size=20", "textStatus=UNKNOWN");
        assertTrue(page.locator("[role='dialog'] a[href*='/boss/inbox?kind=resolve']")
                .first().isVisible(), "核对详情应提供去待办处理链接");
        page.locator("button[aria-label='关闭']").first().click();

        // 只读：全部为 GET，无 start/approve/activate 等写请求
        assertTrue(apiCalls.stream().noneMatch(call -> !"GET".equals(call.method())),
                "首页五卡与详情应只读，不得产生写请求：" + apiCalls);
    }

    @Test
    void todayBigStartBlockedWhenBossNotLoggedIn() {
        fixtureBossState = "IDLE";
        fixtureBossLoggedIn = false;
        fixtureBossWorkerExited = true;
        fixtureBossCanStart = true;
        open("/boss");
        waitVisible("RUN · 开始一轮投递");

        assertEquals(0, page.locator("header button:has-text(\"开始投递\")").count(),
                "今日页顶部 RunCapsule 必须隐藏小号开始按钮");
        var bigStart = page.locator("section:has-text(\"RUN · 开始一轮投递\") button:has-text(\"开始投递\")").first();
        assertTrue(bigStart.isVisible(), "今日页应显示独立大号开始按钮");
        // 等 status fixture 回来、登录原因提示出现后再断言 disabled（首屏渲染可能还没拿到 isLoggedIn）。
        waitVisible("BOSS 未登录，请先扫码。");
        assertTrue(bigStart.isDisabled(), "BOSS 未登录时大按钮应 disabled（预期）");
        assertTrue(apiCalls.stream().noneMatch(call -> "POST".equals(call.method())
                        && call.path().equals("/api/boss/start")),
                "未登录禁用状态下不应发 POST /api/boss/start：" + apiCalls);
    }

    @Test
    void todayBigStartPostsStartWhenIdleLoggedIn() {
        fixtureBossState = "IDLE";
        fixtureBossLoggedIn = true;
        fixtureBossWorkerExited = true;
        fixtureBossCanStart = true;
        open("/boss");
        waitVisible("RUN · 开始一轮投递");
        waitVisible("需手动点击开始。");

        assertEquals(0, page.locator("header button:has-text(\"开始投递\")").count(),
                "今日页顶部 RunCapsule 必须隐藏小号开始按钮");
        var bigStart = page.locator("section:has-text(\"RUN · 开始一轮投递\") button:has-text(\"开始投递\")").first();
        assertTrue(bigStart.isVisible(), "今日页应显示独立大号开始按钮");
        assertFalse(bigStart.isDisabled(), "BOSS 已登录且 IDLE/canStart 时大按钮应可点（mock fixture，不启动真实投递）");
        bigStart.click();
        waitVisible("任务启动成功");
        assertTrue(apiCalls.stream().anyMatch(call -> "POST".equals(call.method())
                        && call.path().equals("/api/boss/start")),
                "点击大按钮应调用同一 POST /api/boss/start：" + apiCalls);
        assertTrue(apiCalls.stream().noneMatch(call -> !"GET".equals(call.method())
                        && (call.path().contains("/approve") || call.path().contains("/reject")
                        || call.path().contains("/activate"))),
                "开始投递不得触发 approve/activate 写接口：" + apiCalls);
    }

    @Test
    void runCapsuleShowsRunningStateAndStopEntry() {
        open("/boss");
        // 等 status fixture 回来、胶囊进入 RUNNING（首屏会先显示空闲/读取中）。
        page.waitForSelector("button:has-text(\"停止投递\")",
                new Page.WaitForSelectorOptions().setTimeout(15_000));
        assertTrue(page.locator("text=运行中").first().isVisible(), "运行胶囊应显示运行中");
        assertTrue(page.locator("button:has-text(\"停止投递\")").first().isVisible(),
                "运行中应提供停止投递按钮");
    }

    @Test
    void inboxReleaseDecisionRequiresReasonAndSecondConfirmation() {
        open("/boss/inbox?kind=release");
        waitVisible("待办");
        waitVisible("Java 开发");
        assertTrue(page.locator("text=人工放行：已批准").first().isVisible(),
                "人工放行队列应显示已批准/下一轮上限/剩余条数");

        page.locator("[role='option']").first().click();
        // 空原因时先被拦下（理由必填），不会直接进入二次确认。
        page.locator("button:has-text(\"批准入队\")").first().click();
        waitVisible("请填写原因（必填，会写入人工操作审计）");

        // 填理由后再点动作，才出现二次确认条。
        page.locator("#inbox-reason").fill("已核对岗位与方向匹配，同意下一轮放行");
        page.locator("button:has-text(\"批准入队\")").first().click();
        assertTrue(page.locator("text=确认执行").first().isVisible(),
                "批准必须出现二次确认条");
        assertTrue(page.locator("text=原因会写入审计").first().isVisible(),
                "二次确认应说明原因写入审计");

        page.locator("button:has-text(\"确认\")").first().click();
        waitVisible("不会立即发送");

        ApiCall decision = apiCalls.stream()
                .filter(call -> "POST".equals(call.method()) && call.path().equals("/api/boss/manual-releases"))
                .findFirst().orElseThrow(() -> new AssertionError("批准应 POST /api/boss/manual-releases"));
        assertTrue(decision.body().contains("\"action\":\"APPROVE\""),
                "批准请求体应包含 APPROVE：" + decision.body());
        assertTrue(decision.body().contains("已核对岗位与方向匹配"),
                "批准请求体应携带必填原因：" + decision.body());
        assertTrue(page.locator("text=批准只入队，不会立即发送").first().isVisible(),
                "待办页应展示批准只入队口径");
    }

    @Test
    void legacyRoutesRedirectToNewSpacesAndKeepQuery() {
        open("/boss/templates");
        page.waitForURL("**/boss/setup**", new Page.WaitForURLOptions().setTimeout(10_000));
        waitVisible("方向模板");

        // 旧 rules / ai-config / advanced 都落到 setup，且 setup 忽略 ?tab 仍显示方向模板
        for (String legacy : List.of("/boss/rules", "/ai-config", "/boss/advanced")) {
            open(legacy);
            page.waitForURL("**/boss/setup**", new Page.WaitForURLOptions().setTimeout(10_000));
            assertTrue(page.url().contains("/boss/setup"), "旧路由应重定向到配置空间：" + legacy);
            waitVisible("方向模板");
            for (String tab : List.of("筛选规则", "AI 配置", "高级调试")) {
                assertEquals(0, page.locator("button:has-text(\"" + tab + "\")").count(),
                        "重定向后不得出现配置页签：" + tab);
            }
        }

        open("/boss/setup?tab=rules");
        waitVisible("方向模板");
        assertEquals(0, page.locator("button:has-text(\"筛选规则\")").count(),
                "setup 应忽略旧 ?tab 参数，不显示筛选规则页签");

        open("/boss/attempts?textStatus=UNKNOWN&page=2");
        page.waitForURL("**/boss/inbox**", new Page.WaitForURLOptions().setTimeout(10_000));
        assertTrue(page.url().contains("kind=resolve") && page.url().contains("page=2"),
                "旧路由重定向应保留查询参数：" + page.url());
        waitVisible("待办");
    }

    @Test
    void themeCyclesThreeSchemes() {
        open("/boss");
        waitVisible("今日");
        assertEquals("porcelain", String.valueOf(page.evaluate("document.documentElement.dataset.theme")));

        page.locator("button[aria-label='切换配色']").click();
        assertEquals("graphite", String.valueOf(page.evaluate("document.documentElement.dataset.theme")));
        page.locator("button[aria-label='切换配色']").click();
        assertEquals("ivory", String.valueOf(page.evaluate("document.documentElement.dataset.theme")));
        assertTrue(String.valueOf(page.evaluate("localStorage.getItem('offerchaser.ui-theme')"))
                .contains("ivory"), "主题选择应持久化到 localStorage");
    }

    @Test
    void noHydrationErrorsAcrossFiveSpaces() {
        for (String path : List.of("/boss", "/boss/inbox", "/boss/ledger", "/boss/setup",
                "/boss/insights?tab=strategy")) {
            open(path);
            page.waitForTimeout(600);
        }
        List<String> hydration = new ArrayList<>();
        for (String error : consoleErrors) {
            String lower = error.toLowerCase(Locale.ROOT);
            if (lower.contains("hydration") || lower.contains("did not match")
                    || lower.contains("server rendered html")) {
                hydration.add(error);
            }
        }
        assertTrue(hydration.isEmpty(), "页面不应有 hydration 错误：" + hydration);
        assertTrue(pageErrors.isEmpty(), "页面不应有未捕获异常：" + pageErrors);
    }

    @Test
    void bulkClearResolveSubmitsStillUnknownForEachVisibleItem() {
        open("/boss/inbox?kind=resolve");
        waitVisible("待核对");
        waitVisible("未知岗位");
        var clearButton = page.locator("button:has-text('清理待核对')").first();
        assertTrue(clearButton.isVisible(), "待核对页应显示批量清理按钮");
        clearButton.click();
        waitVisible("清理 2 条待核对？");
        page.locator("[role='dialog'] button:has-text('确认清理')").first().click();
        page.waitForTimeout(500);
        List<ApiCall> resolutions = apiCalls.stream()
                .filter(call -> "POST".equals(call.method())
                        && call.path().matches("/api/boss/attempts/[^/]+/resolve"))
                .toList();
        assertEquals(2, resolutions.size(), "批量清理应逐条提交： " + resolutions);
        for (ApiCall call : resolutions) {
            assertTrue(call.body().contains("\"resolution\":\"STILL_UNKNOWN\""),
                    "批量清理不得记成功，必须使用 STILL_UNKNOWN：" + call.body());
            assertTrue(call.body().contains("不记成功"), "批量清理原因应写入审计：" + call.body());
        }
        assertTrue(page.locator("text=已清理 2 条待核对记录").first().isVisible(),
                "批量清理后应显示完成反馈");
    }

    @Test
    void insightsTrendChartLabelsAreNotClipped() {
        open("/boss/insights");
        waitVisible("指标趋势");
        page.waitForSelector("[data-slot='chart-card']", new Page.WaitForSelectorOptions().setTimeout(15_000));
        var chart = page.locator("[data-slot='chart-card']").first();
        assertTrue(chart.isVisible(), "趋势图卡片应可见");
        String overflow = String.valueOf(chart.evaluate("el => getComputedStyle(el).overflow"));
        assertFalse("hidden".equals(overflow), "趋势图卡片不能再用 overflow:hidden 裁掉日期标签：" + overflow);
        assertTrue(page.locator("[data-slot='chart-card'] .oc-chart").first().isVisible(), "趋势图 SVG 应可见");
        assertTrue(page.locator("[data-slot='chart-card'] span").count() > 0, "趋势图应有日期/数值标签");
    }

    @Test
    void mobileFiveSpacesDoNotOverflowViewport() {
        page.setViewportSize(390, 844);
        String[][] pages = {
                {"/boss", "今日"},
                {"/boss/inbox", "待办"},
                {"/boss/ledger", "记录"},
                {"/boss/setup", "配置"},
                {"/boss/insights", "洞察"},
        };
        for (String[] item : pages) {
            open(item[0]);
            page.waitForSelector("main h1", new Page.WaitForSelectorOptions().setTimeout(15_000));
            assertTrue(page.locator("main h1").first().innerText().contains(item[1]),
                    item[0] + " 主标题应包含 " + item[1]);
            page.waitForTimeout(400);
            assertTrue(page.locator("nav[aria-label='空间切换（底部）']").first().isVisible(),
                    item[0] + " 移动端底部空间标签应可见");
            long overflow = ((Number) page.evaluate("""
                    () => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - window.innerWidth
                    """)).longValue();
            assertTrue(overflow <= 2, item[0] + " 在 390px 视口横向溢出 " + overflow + "px");
        }
    }

    @Test
    void insightsShowsReadOnlyFollowupStrategy() {
        open("/boss/insights?tab=strategy");
        waitVisible("洞察");
        for (String text : List.of("跟进策略", "后续跟进 · 措施清单（规划）", "只读",
                "不执行发送")) {
            assertTrue(page.locator("text=" + text).first().isVisible(),
                    "跟进策略视图缺少：" + text);
        }
    }

    @Test
    void setupTemplateCardByDefaultExpandsEditorAndCollapsesAfterSave() {
        open("/boss/setup");
        waitVisible("方向模板");
        assertTrue(page.locator("text=方向模板 = 搜索条件 + 筛选规则 + AI + 简历；模型服务为全局配置。")
                .first().isVisible(), "配置空间顶部说明应为精简版");
        // 配置空间不再有筛选规则/AI 配置/高级调试三页签；模型服务仍在
        waitVisible("模型服务（全局）");
        for (String tab : List.of("筛选规则", "AI 配置", "高级调试")) {
            assertEquals(0, page.locator("button:has-text(\"" + tab + "\")").count(),
                    "配置空间不得再有页签：" + tab);
        }
        page.locator("ul[aria-label='模板列表'] li button").first().click();

        // 默认卡片态：只读信息 + 编辑配置入口，配置编辑器隐藏
        waitVisible("编辑配置");
        for (String label : List.of("方向", "关键词", "期望月薪下限", "图片简历", "最近更新", "描述")) {
            assertTrue(page.locator("dt:has-text(\"" + label + "\")").first().isVisible(),
                    "默认卡片缺少字段：" + label);
        }
        assertTrue(page.locator("text=30K 起").first().isVisible(),
                "默认卡片应从规则摘要显示「30K 起」");
        assertEquals(0, page.locator("text=不超过").count(),
                "模板卡不得再有「不超过 maxK」分支");
        assertTrue(page.locator("text=Java 后端方向").count() >= 0);
        assertEquals(0, page.locator("text=A. 搜索与投递").count(),
                "默认不应展开配置编辑器");
        assertEquals(0, page.locator("button:has-text(\"保存配置\")").count(),
                "默认卡片态不应出现保存配置按钮");

        // 点「编辑配置」后展开四区块
        page.locator("button:has-text(\"编辑配置\")").first().click();
        waitVisible("A. 搜索与投递");
        for (String section : List.of("B. 公司要求与筛选规则", "C. AI 配置", "D. 模板图片简历")) {
            assertTrue(page.locator("text=" + section).first().isVisible(),
                    "编辑器缺少区块：" + section);
        }
        assertTrue(page.locator("text=格式由后端严格校验。").first().isVisible(),
                "提示词说明应为「格式由后端严格校验。」");
        assertEquals(0, page.locator("text=仅影响本模板").count(),
                "编辑器不得再有「仅影响本模板」前缀");
        assertEquals(0, page.locator("text=期望月薪下限（K）").count(),
                "A 区不得再有期望月薪下限输入");
        assertEquals(0, page.locator("text=期望月薪上限（K）").count(),
                "A 区不得再有期望月薪上限输入");
        assertTrue(page.locator("text=规则期望月薪区间（K，留空=不限）").first().isVisible(),
                "B 区应为规则期望月薪区间（下限/上限）");
        var ruleSalaryMin = page.locator("input[aria-label='规则期望月薪下限']").first();
        var ruleSalaryMax = page.locator("input[aria-label='规则期望月薪上限']").first();
        assertTrue(ruleSalaryMin.isVisible(), "应显示规则薪资下限输入");
        assertTrue(ruleSalaryMax.isVisible(), "应显示规则薪资上限输入");

        // 融资阶段空值回退为「不限」且默认勾选
        var stageUnlimited = page.locator("label:has-text(\"不限\") input[type='checkbox']").first();
        assertTrue(stageUnlimited.isVisible(), "融资阶段应显示「不限」选项");
        assertTrue(stageUnlimited.isChecked(), "融资阶段「不限」应默认勾选");

        // 多选互斥：勾具体阶段 → 自动取消「不限」；再勾「不限」→ 清空其它阶段
        var stageA = page.locator("label:has-text(\"A轮\") input[type='checkbox']").first();
        var stageAngel = page.locator("label:has-text(\"天使轮\") input[type='checkbox']").first();
        stageA.check();
        assertFalse(stageUnlimited.isChecked(), "勾选 A轮 后「不限」应自动取消");
        assertTrue(stageA.isChecked(), "A轮 应被勾选");
        stageAngel.check();
        assertTrue(stageA.isChecked() && stageAngel.isChecked(), "多个具体阶段可同时勾选");
        stageUnlimited.check();
        assertTrue(stageUnlimited.isChecked(), "再次勾选「不限」应保留不限");
        assertFalse(stageA.isChecked(), "勾选「不限」后 A轮 应被清空");
        assertFalse(stageAngel.isChecked(), "勾选「不限」后 天使轮 应被清空");

        // 上限 < 下限：前端应拦截且不发请求
        ruleSalaryMax.fill("20");
        page.locator("button:has-text(\"保存配置\")").first().click();
        waitVisible("薪资上限不能低于下限。");
        assertTrue(apiCalls.stream().noneMatch(call -> "PUT".equals(call.method())
                        && call.path().matches("/api/boss/templates/\\d+/config")),
                "上限小于下限时不得提交保存：" + apiCalls);

        // 修正上限 60（下限 fixture=30），保存配置 → mock 成功 → 自动收起回卡片态，不激活/不开始投递
        ruleSalaryMax.fill("60");
        page.locator("button:has-text(\"保存配置\")").first().click();
        page.waitForSelector("dt:has-text(\"最近更新\")",
                new Page.WaitForSelectorOptions().setTimeout(15_000));
        assertTrue(page.locator("button:has-text(\"编辑配置\")").first().isVisible(),
                "保存成功后应回到卡片态");
        assertTrue(page.locator("text=30K 起").first().isVisible(),
                "保存后卡片应显示「30K 起」");
        assertEquals(0, page.locator("text=A. 搜索与投递").count(),
                "保存成功后配置编辑器应已收起");
        ApiCall configCall = apiCalls.stream()
                .filter(call -> "PUT".equals(call.method())
                        && call.path().matches("/api/boss/templates/\\d+/config"))
                .findFirst().orElseThrow(() -> new AssertionError("保存配置应 PUT /api/boss/templates/{id}/config"));
        assertTrue(configCall.body().contains("\"stage\":[\"不限\"]"),
                "互斥选择后保存的融资阶段应只包含「不限」：" + configCall.body());
        assertTrue(configCall.body().contains("\"expectedSalaryMin\":30"),
                "保存配置应写规则下限：" + configCall.body());
        assertTrue(configCall.body().contains("\"expectedSalaryMax\":60"),
                "保存配置应写入用户输入的上限：" + configCall.body());
        assertTrue(apiCalls.stream().noneMatch(call -> call.path().contains("/activate")
                        || call.path().contains("/start") || call.path().contains("/execute")),
                "保存配置不得激活模板或触发投递：" + apiCalls);
    }

    @Test
    void inboxReleaseDetailJobUrlLinkIsSafeAndHiddenWhenMissing() {
        open("/boss/inbox?kind=release");
        waitVisible("Java 开发");
        String beforeUrl = page.url();

        page.locator("[role='option']").first().click();
        page.waitForSelector("[aria-label='详情与决策'] h2:has-text(\"Java 开发\")",
                new Page.WaitForSelectorOptions().setTimeout(10_000));
        var link = page.locator("[aria-label='详情与决策'] a:has-text(\"打开岗位页\")").first();
        assertTrue(link.isVisible(), "带 jobUrl 的放行详情应显示「打开岗位页」");
        assertEquals("https://www.zhipin.com/job_detail/e2e.html",
                link.getAttribute("href"), "外链 href 必须使用真实 jobUrl");
        assertEquals("_blank", link.getAttribute("target"), "外链必须 target=_blank");
        String rel = String.valueOf(link.getAttribute("rel"));
        assertTrue(rel.contains("noopener") && rel.contains("noreferrer"),
                "外链 rel 必须包含 noopener noreferrer：" + rel);
        // 不点击真实外链，只断言属性；页面不得发生导航。
        assertEquals(beforeUrl, page.url(), "仅断言外链属性时不得发生真实导航");

        page.locator("[role='option']").nth(1).click();
        page.waitForSelector("[aria-label='详情与决策'] h2:has-text(\"无链接岗位\")",
                new Page.WaitForSelectorOptions().setTimeout(10_000));
        assertEquals(0, page.locator("[aria-label='详情与决策'] a:has-text(\"打开岗位页\")").count(),
                "无 jobUrl 的放行详情不得显示「打开岗位页」");
        assertEquals(beforeUrl, page.url(), "切换详情不得发生真实导航");
        assertTrue(apiCalls.stream().noneMatch(call -> !"GET".equals(call.method())
                        && !(call.path().equals("/api/boss/status-sync") && "POST".equals(call.method()))),
                "打开待办详情不得产生业务写请求：" + apiCalls);
    }

    @Test
    void aiTemplateWizardBuildsDraftWithoutActivationAndFallsBackToText() {
        open("/boss/setup");
        waitVisible("AI 帮我建模板");
        page.locator("button:has-text(\"AI 帮我建模板\")").first().click();
        waitVisible("用简历生成一个方向模板");
        assertWizardWithinViewport("向导打开时");
        assertTrue(page.locator("[aria-label='补充说明']").first().isVisible(),
                "上传简历步骤应提供补充说明输入框");
        page.locator("[aria-label='补充说明']").first().fill("我的优势是跨团队推进，做过两个 AI 产品 0→1。");

        // 隐私同意 + 上传图片（fixture 让视觉解析失败）
        page.locator("[role='dialog'] input[type='checkbox']").first().check();
        page.locator("[role='dialog'] input[type='file']").setInputFiles(
                new FilePayload("resume.png", "image/png", "fake-image".getBytes(StandardCharsets.UTF_8)));
        page.locator("button:has-text(\"开始分析\")").first().click();
        waitVisible("视觉解析失败（模型可能不支持图片）：请改用「粘贴简历文本」");
        assertTrue(page.locator("text=错误码：VISION_PARSE_FAILED").first().isVisible(),
                "视觉失败应展示稳定错误码");

        // 视觉失败 → 切文本兜底
        page.locator("button:has-text(\"粘贴简历文本\")").first().click();
        page.locator("[aria-label='简历文本']").first()
                .fill("五年 Java 后端经验，本科，上海，期望 30-50K，熟悉 Spring。");
        page.locator("button:has-text(\"分析文本\")").first().click();
        waitVisible("确认识别出的画像（可编辑）");
        ApiCall analyzeTextCall = apiCalls.stream()
                .filter(call -> "POST".equals(call.method())
                        && call.path().equals("/api/boss/resume/analyze-text"))
                .findFirst().orElseThrow(() -> new AssertionError("应调用文本分析接口"));
        assertTrue(analyzeTextCall.body().contains("跨团队推进"),
                "补充说明必须随文本分析一起提交：" + analyzeTextCall.body());

        // 画像：无薪资输入，专业/毕业年份/学历/经验只读
        assertEquals(0, page.locator("[role='dialog'] input[type='number']").count(),
                "画像确认步骤不应再有期望薪资输入");
        for (String text : List.of("专业", "毕业年份", "AI 判断最高学历（只读）",
                "AI 判断工作经验（只读）", "AI 建议薪资（只读，下一步问答中确认）")) {
            assertTrue(page.locator("text=" + text).first().isVisible(), "画像只读区缺少：" + text);
        }
        assertTrue(page.locator("text=软件工程").first().isVisible(), "应展示 AI 识别的专业");
        assertTrue(page.locator("text=2020 年").first().isVisible(), "应展示 AI 识别的毕业年份");
        page.locator("button:has-text(\"下一步\")").first().click();

        // 方向：行业偏好默认匹配方向/画像，最多 6
        waitVisible("选择一个投递方向");
        page.locator("[role='dialog'] button:has-text(\"Java 后端\")").first().click();
        assertTrue(page.locator("[aria-label='行业偏好']").first().isVisible(), "方向后应有行业偏好");
        var selectedIndustries = page.locator("[aria-label='行业偏好'] button[aria-pressed='true']");
        assertTrue(selectedIndustries.count() >= 2, "默认应勾选方向/画像匹配的行业");
        page.locator("[aria-label='行业偏好'] button:has-text(\"人工智能\")").first().click();
        page.locator("button:has-text(\"下一步\")").first().click();

        // 城市：搜索+多选 chips，最多 4，第 1 个主搜索城市
        waitVisible("希望优先看哪些城市？（可多选，最多 4 个）");
        assertTrue(page.locator("input[aria-label='搜索城市']").first().isVisible(),
                "城市问答应有搜索框");
        assertTrue(page.locator("[aria-label='城市候选']").first().isVisible(),
                "城市问答应有候选 chips");
        page.locator("[aria-label='城市候选'] button:has-text(\"北京\")").first().click();
        page.locator("[aria-label='城市候选'] button:has-text(\"深圳\")").first().click();
        waitVisible("已选 4/4");
        assertTrue(page.locator("text=（主搜索城市）").first().isVisible(),
                "第 1 个城市必须标注主搜索城市");
        assertTrue(page.locator("[aria-label='城市候选'] button:has-text(\"广州\")")
                        .first().isDisabled(),
                "已选 4 个后第 5 个城市必须禁用");
        page.locator("button:has-text(\"下一步\")").first().click();

        // 薪资
        waitVisible("期望月薪下限是多少？");
        var salaryInputs = page.locator("[role='dialog'] input[type='number']");
        assertEquals(1, salaryInputs.count(), "AI 向导薪资问答只应有下限输入");
        salaryInputs.first().fill("60");
        assertTrue(page.locator("text=只设下限，不设上限").first().isVisible(),
                "AI 向导应说明只设下限");
        page.locator("button:has-text(\"下一步\")").first().click();

        // 排除项：默认 6 chips + 自定义合并去重
        waitVisible("有哪些岗位/公司/词需要排除？");
        assertEquals(6, page.locator("[aria-label='常用排除项'] button[aria-pressed='true']").count(),
                "6 个常用排除项必须默认勾选");
        page.locator("[role='dialog'] textarea").first().fill("外包驻场");
        waitVisible("最终排除项 7 个");
        page.locator("button:has-text(\"下一步\")").first().click();

        // 强度/图片
        waitVisible("投递强度选哪一档？");
        page.locator("[role='dialog'] button:has-text(\"尝试期\")").first().click();
        page.locator("button:has-text(\"下一步\")").first().click();
        waitVisible("需要自动发送图片简历吗？");
        page.locator("[role='dialog'] button:has-text(\"不自动发送图片简历\")").first().click();
        page.locator("button:has-text(\"生成草稿\")").first().click();

        // 草稿预览：trial 2/5/20 + 新字段
        waitVisible("预览草稿");
        assertWizardWithinViewport("向导预览时");
        assertTrue(page.locator("text=每关键词 2 · 单关键词 5 分钟 · 无新增判定 20 秒").first().isVisible(),
                "草稿预览应展示 trial 采集策略 2/5/20");
        for (String label : List.of("城市", "行业", "学历要求", "经验要求", "公司规模", "融资阶段",
                "期望月薪下限")) {
            assertTrue(page.locator("dt:has-text(\"" + label + "\")").first().isVisible(),
                    "预览缺少字段：" + label);
        }
        assertTrue(page.locator("text=60K 起").first().isVisible(),
                "预览薪资应显示用户确认的规则下限「60K 起」（60 > 简历推荐上限 50 也不报错）");
        assertTrue(page.locator("text=上海（主搜索城市）").first().isVisible(),
                "预览城市应标注主搜索城市");
        assertTrue(page.locator("text=本科").first().isVisible(), "预览应展示后端映射的学历");
        assertTrue(page.locator("text=5-10年").first().isVisible(), "预览应展示后端映射的经验档");
        assertTrue(page.locator("text=20-99人").first().isVisible(), "预览应展示 20 人以上公司规模");
        assertTrue(page.locator("text=不限").first().isVisible(), "融资阶段应显示不限");

        // 保存并激活必须先二次确认；取消后不得发 activate
        page.locator("button:has-text(\"保存并激活\")").first().click();
        waitVisible("保存并激活该模板？");
        assertTrue(page.locator("text=不会立即发送").first().isVisible(),
                "激活确认必须说明不会立即发送");
        page.locator("button:has-text(\"取消\")").first().click();
        assertTrue(apiCalls.stream().noneMatch(call -> call.path().contains("/activate")),
                "取消二次确认后不得调用 activate");

        // 保存为草稿：createTemplate → updateTemplateConfig，不 activate
        page.locator("button:has-text(\"保存为草稿\")").first().click();
        waitVisible("已保存为草稿模板");

        ApiCall draftCall = apiCalls.stream()
                .filter(call -> "POST".equals(call.method())
                        && call.path().equals("/api/boss/templates/ai-draft"))
                .findFirst().orElseThrow(() -> new AssertionError("向导应调用 POST /api/boss/templates/ai-draft"));
        for (String marker : List.of("\"cities\":[\"上海\",\"杭州\",\"北京\",\"深圳\"]",
                "\"cityCode\":\"上海\"", "\"industries\":[",
                "\"intensity\":\"trial\"", "\"direction\":\"Java 后端\"",
                "\"expectedSalaryMin\":60", "\"expectedSalaryMax\":null")) {
            assertTrue(draftCall.body().contains(marker),
                    "草稿请求体缺少 " + marker + "：" + draftCall.body());
        }

        ApiCall createCall = apiCalls.stream()
                .filter(call -> "POST".equals(call.method()) && call.path().equals("/api/boss/templates"))
                .findFirst().orElseThrow(() -> new AssertionError("保存草稿应 POST /api/boss/templates"));
        assertTrue(createCall.body().contains("Java 后端"),
                "创建模板请求应带名称/方向：" + createCall.body());

        ApiCall configCall = apiCalls.stream()
                .filter(call -> "PUT".equals(call.method())
                        && call.path().equals("/api/boss/templates/501/config"))
                .findFirst().orElseThrow(() -> new AssertionError("保存草稿应 PUT 模板配置"));
        for (String marker : List.of("\"cityCode\":\"上海\"",
                "\"industry\":[\"互联网/AI\",\"企业服务\"]",
                "\"degree\":[\"本科\"]", "\"experience\":[\"5-10年\"]",
                "\"scale\":[\"20-99人\",\"100-499人\",\"500-999人\",\"1000-9999人\",\"10000人以上\"]",
                "\"stage\":[]", "\"cities\":[\"上海\",\"杭州\",\"北京\",\"深圳\"]",
                "\"collectMaxPerKeyword\":2", "\"collectMaxDurationMinutes\":5",
                "\"collectNoNewWindowSeconds\":20")) {
            assertTrue(configCall.body().contains(marker),
                    "模板配置请求体缺少 " + marker + "：" + configCall.body());
        }

        assertTrue(apiCalls.stream().noneMatch(call -> call.path().contains("/activate")),
                "保存为草稿不得调用 activate");
        assertTrue(apiCalls.stream().noneMatch(call -> !"GET".equals(call.method())
                        && (call.path().contains("/start") || call.path().contains("/stop")
                        || call.path().contains("/execute") || call.path().contains("/manual-releases")
                        || call.path().contains("/followups") || call.path().contains("/attempts"))),
                "向导不得调用投递/放行/跟进/核对写接口：" + apiCalls);

        Object storage = page.evaluate("JSON.stringify(Object.fromEntries(Object.entries(localStorage)))");
        String storageText = String.valueOf(storage);
        assertFalse(storageText.contains("五年 Java 后端经验"), "localStorage 不得写入简历内容");
        assertFalse(storageText.toLowerCase(Locale.ROOT).contains("resume"), "localStorage 不得有简历 key");
    }

    private void assertWizardWithinViewport(String stage) {
        var dialog = page.locator("[role='dialog'][aria-label='AI 帮我建模板']");
        var box = dialog.boundingBox();
        var viewport = page.viewportSize();
        assertTrue(box != null, stage + "弹窗应有 boundingBox");
        assertTrue(viewport != null, "测试上下文应有 viewport");
        assertTrue(box.x >= -1 && box.y >= -1, stage + "弹窗不能超出视口左上：" + box);
        assertTrue(box.x + box.width <= viewport.width + 1,
                stage + "弹窗不能超出视口右侧：" + box);
        assertTrue(box.y + box.height <= viewport.height + 1,
                stage + "弹窗不能超出视口底部：" + box);
    }

    // ==================== fixture ====================

    private void handleApi(Route route) {
        var request = route.request();
        String method = request.method();
        URI uri = URI.create(request.url());
        String path = uri.getPath();
        String body = request.postData();
        apiCalls.add(new ApiCall(method, path, uri.getRawQuery(), body == null ? "" : body));

        if ("/api/session/status".equals(path)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authEnabled", authRequired.get());
            data.put("authenticated", !authRequired.get() || authenticated.get());
            data.put("initialized", true);
            data.put("tokenFileHint", "data/local-session-token");
            data.put("csrfRequired", true);
            ok(route, data);
            return;
        }
        if ("/api/session/login".equals(path) && "POST".equals(method)) {
            authenticated.set(true);
            ok(route, Map.of("authenticated", true));
            return;
        }
        if ("/api/health".equals(path)) {
            route.fulfill(new Route.FulfillOptions().setStatus(200)
                    .setContentType("application/json; charset=utf-8")
                    .setBody("{\"status\":\"UP\"}"));
            return;
        }
        if ("/api/boss/status".equals(path)) {
            ok(route, bossStatus());
            return;
        }
        if ("/api/boss/start".equals(path) && "POST".equals(method)) {
            ok(route, Map.of("message", "任务启动成功"));
            return;
        }
        if ("/api/boss/delivery/summary".equals(path)) {
            ok(route, deliverySummary());
            return;
        }
        if ("/api/boss/status-sync".equals(path) && "POST".equals(method)) {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("syncId", "sync-e2e");
            run.put("triggerType", "ACTIVE");
            run.put("scope", "TODAY");
            run.put("targetDate", "2026-11-08");
            run.put("status", "COMPLETED");
            run.put("startedAt", "2026-11-08 10:00:00");
            run.put("finishedAt", "2026-11-08 10:00:03");
            run.put("autoTotal", 5);
            run.put("autoRead", 2);
            run.put("autoReplied", 1);
            run.put("autoUnread", 3);
            run.put("autoUnknown", 0);
            run.put("manualTotal", 4);
            run.put("manualRead", 1);
            run.put("manualReplied", 1);
            run.put("manualUnread", 3);
            run.put("manualUnknown", 0);
            run.put("hrTotal", 6);
            run.put("hrRead", 2);
            run.put("hrReplied", 1);
            run.put("hrUnread", 4);
            run.put("hrUnknown", 0);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run", run);
            data.put("snapshotCount", 3);
            data.put("skipped", false);
            ok(route, data);
            return;
        }
        if ("/api/boss/status-sync/summary".equals(path)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("today", "2026-11-08");
            data.put("previousDay", "2026-11-07");
            data.put("active", null);
            data.put("startup", null);
            data.put("latest", null);
            data.put("recent", List.of());
            ok(route, data);
            return;
        }
        if ("/api/boss/templates".equals(path) && "GET".equals(method)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", List.of(templateView(1), templateView(2)));
            data.put("active", templateView(1));
            ok(route, data);
            return;
        }
        if (path.matches("/api/boss/templates/\\d+") && "GET".equals(method)) {
            ok(route, templateDetail());
            return;
        }
        if (path.matches("/api/boss/templates/\\d+/config") && "PUT".equals(method)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("template", templateView(1));
            data.put("applied", true);
            data.put("warnings", List.of());
            ok(route, data);
            return;
        }
        if ("/api/boss/config/options/stage".equals(path)) {
            ok(route, List.of(option("s0", "不限"), option("s1", "未融资"),
                    option("s2", "天使轮"), option("s3", "A轮"), option("s4", "B轮"),
                    option("s5", "C轮"), option("s6", "D轮及以上"), option("s7", "不需要融资")));
            return;
        }
        if ("/api/boss/config/options/city".equals(path)) {
            ok(route, List.of(option("310100", "上海"), option("330100", "杭州"),
                    option("110100", "北京"), option("440300", "深圳"), option("440100", "广州")));
            return;
        }
        if ("/api/boss/config/options/industry".equals(path)) {
            ok(route, List.of(option("i1", "互联网/AI"), option("i2", "企业服务"),
                    option("i3", "人工智能"), option("i4", "电商"), option("i5", "金融"),
                    option("i6", "教育"), option("i7", "医疗")));
            return;
        }
        if (path.startsWith("/api/boss/config/options/")) {
            ok(route, List.of());
            return;
        }
        if ("/api/boss/resume/analyze".equals(path) && "POST".equals(method)) {
            route.fulfill(new Route.FulfillOptions().setStatus(422)
                    .setContentType("application/json; charset=utf-8")
                    .setBody("{\"success\":false,\"code\":\"VISION_PARSE_FAILED\","
                            + "\"message\":\"视觉解析失败：模型不支持图片\"}"));
            return;
        }
        if ("/api/boss/resume/analyze-text".equals(path) && "POST".equals(method)) {
            ok(route, resumeAnalysisFixture());
            return;
        }
        if ("/api/boss/templates/ai-draft".equals(path) && "POST".equals(method)) {
            ok(route, aiDraftFixture(requestedSalaryMin(body)));
            return;
        }
        if ("/api/boss/templates".equals(path) && "POST".equals(method)) {
            Map<String, Object> created = templateView(501);
            created.put("name", "Java 后端 模板");
            created.put("direction", "Java 后端");
            created.put("active", false);
            ok(route, created);
            return;
        }
        if (path.matches("/api/boss/templates/\\d+/config") && "PUT".equals(method)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("template", templateView(501));
            data.put("applied", false);
            data.put("warnings", List.of());
            ok(route, data);
            return;
        }
        if (path.matches("/api/boss/templates/\\d+/activate") && "POST".equals(method)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("template", templateView(501));
            data.put("warnings", List.of());
            ok(route, data);
            return;
        }
        if ("/api/config".equals(path)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("BASE_URL", "http://127.0.0.1:11434/v1");
            data.put("API_KEY", "********");
            data.put("MODEL", "e2e-model");
            ok(route, data);
            return;
        }
        if ("/api/boss/manual-releases".equals(path) && "GET".equals(method)) {
            ok(route, manualReleaseQueue());
            return;
        }
        if ("/api/boss/manual-releases".equals(path) && "POST".equals(method)) {
            ok(route, Map.of("releaseId", "rel-e2e", "statusLabel", "已批准",
                    "message", "已加入放行队列"));
            return;
        }
        if ("/api/boss/followups".equals(path) && "GET".equals(method)) {
            Map<String, String> query = queryParams(uri.getRawQuery());
            int size = intParam(query.get("size"), 10);
            if (size >= 20) {
                ok(route, pageResult(List.of(followupItem()), 1));
            } else {
                ok(route, pageResult(List.of(), "SUGGESTED".equals(query.get("status")) ? 1 : 0));
            }
            return;
        }
        if ("/api/boss/contacts".equals(path) && "GET".equals(method)) {
            Map<String, String> query = queryParams(uri.getRawQuery());
            int size = intParam(query.get("size"), 10);
            if (size >= 20) {
                ok(route, pageResult(List.of(contactItem("contact-1", "HR 小王", "测试公司一"),
                        contactItem("contact-2", "HR 小李", "测试公司二"),
                        contactItem("contact-3", "HR 小张", "测试公司三"),
                        contactItem("contact-4", "HR 小赵", "测试公司四")), 4));
            } else {
                ok(route, pageResult(List.of(), "REPLIED".equals(query.get("replyState")) ? 4 : 0));
            }
            return;
        }
        if ("/api/boss/attempts".equals(path) && "GET".equals(method)) {
            Map<String, String> query = queryParams(uri.getRawQuery());
            int size = intParam(query.get("size"), 10);
            String from = query.get("from");
            boolean today = from != null && from.startsWith(LocalDate.now().toString());
            boolean yesterday = from != null
                    && from.startsWith(LocalDate.now().minusDays(1).toString());
            String textStatus = query.get("textStatus");
            if (from == null && "CONFIRMED".equals(textStatus)) {
                List<Object> history = new ArrayList<>();
                for (int index = 1; index <= 9; index++) {
                    history.add(attemptItem("att-hist-" + index, "历史岗位" + index,
                            "历史公司" + index, "CONFIRMED"));
                }
                ok(route, pageResult(history, 9));
                return;
            }
            if (size >= 20) {
                if ("UNKNOWN".equals(textStatus)) {
                    ok(route, pageResult(List.of(
                            attemptItem("att-unknown-1", "未知岗位", "测试公司一", "UNKNOWN"),
                            attemptItem("att-unknown-2", "另一个岗位", "测试公司二", "UNKNOWN")), 2));
                } else if (yesterday) {
                    ok(route, pageResult(List.of(
                            attemptItem("att-y1", "昨日岗位一", "测试公司一", "CONFIRMED"),
                            attemptItem("att-y2", "昨日岗位二", "测试公司二", "CONFIRMED")), 2));
                } else {
                    ok(route, pageResult(List.of(
                            attemptItem("att-t1", "Java 开发", "测试公司一", "CONFIRMED"),
                            attemptItem("att-t2", "Spring 开发", "测试公司二", "CONFIRMED"),
                            attemptItem("att-t3", "后端开发", "测试公司三", "CONFIRMED")), 3));
                }
            } else if (from == null && "CONFIRMED".equals(textStatus)) {
                ok(route, pageResult(List.of(), 9));
            } else {
                long total = "UNKNOWN".equals(textStatus) ? 2 : yesterday ? 2 : 3;
                ok(route, pageResult(List.of(), total));
            }
            return;
        }
        if (path.matches("/api/boss/attempts/[^/]+") && "GET".equals(method)) {
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("attemptId", path.substring("/api/boss/attempts/".length()));
            attempt.put("jobName", "Java 开发");
            attempt.put("companyName", "测试公司一");
            attempt.put("textStatus", "UNKNOWN");
            attempt.put("errorDetail", null);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("attempt", attempt);
            detail.put("events", List.of());
            detail.put("evaluation", null);
            ok(route, detail);
            return;
        }
        if ("/api/boss/followups/effective".equals(path) && "GET".equals(method)) {
            ok(route, effectiveFollowupsFixture());
            return;
        }
        if ("/api/boss/jobs/insight".equals(path) && "POST".equals(method)) {
            ok(route, jobInsightFixture());
            return;
        }
        if ("/api/boss/metrics/overview".equals(path)) {
            ok(route, metricsOverviewFixture());
            return;
        }
        if ("/api/boss/metrics/series".equals(path)) {
            ok(route, metricsSeriesFixture());
            return;
        }
        if (path.startsWith("/api/boss/metrics/")) {
            ok(route, Map.of());
            return;
        }
        ok(route, Map.of());
    }

    private Map<String, Object> metricsOverviewFixture() {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", "2026-11-03");
        range.put("to", "2026-11-09");
        range.put("days", 7);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("pass", 2);
        rule.put("reject", 1);
        rule.put("review", 0);
        rule.put("total", 3);
        rule.put("passRate", 2.0 / 3.0);
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("match", 1);
        ai.put("reject", 1);
        ai.put("error", 0);
        ai.put("unset", 1);
        ai.put("total", 2);
        Map<String, Object> firstContact = new LinkedHashMap<>();
        firstContact.put("attempts", 3);
        firstContact.put("chatRequested", 3);
        firstContact.put("textConfirmed", 1);
        firstContact.put("textFailed", 0);
        firstContact.put("textUnknown", 1);
        firstContact.put("textPending", 0);
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("confirmed", 1);
        image.put("failed", 0);
        image.put("unknown", 0);
        image.put("skipped", 1);
        image.put("total", 2);
        Map<String, Object> replies = new LinkedHashMap<>();
        replies.put("contacts", 3);
        replies.put("contacted", 3);
        replies.put("replied", 1);
        replies.put("interview", 0);
        replies.put("replyRate", 1.0 / 3.0);
        replies.put("manualTakeover", 0);
        replies.put("stopContact", 0);
        Map<String, Object> followups = new LinkedHashMap<>();
        followups.put("suggested", 1);
        followups.put("approved", 0);
        followups.put("rejected", 0);
        followups.put("executed", 0);
        followups.put("cancelled", 0);
        followups.put("approvedPending", 0);
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("date", "2026-11-09");
        budget.put("limit", 145);
        budget.put("consumed", 0);
        budget.put("reserved", 0);
        budget.put("remaining", 145);
        budget.put("uncertain", 0);
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("sourceRecords", 3);
        collection.put("keywords", 2);
        collection.put("uniqueJobs", 2);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("newJobs", 3);
        metrics.put("legacyNewJobs", 0);
        metrics.put("rule", rule);
        metrics.put("ai", ai);
        metrics.put("firstContact", firstContact);
        metrics.put("textConfirmed", 1);
        metrics.put("image", image);
        metrics.put("replies", replies);
        metrics.put("followups", followups);
        metrics.put("budget", budget);
        metrics.put("collection", collection);
        metrics.put("hrActive", Map.of("今日活跃", 2));
        metrics.put("legacy", Map.of("deliveredUnverified", 0));
        Map<String, Object> dataQuality = new LinkedHashMap<>();
        dataQuality.put("unknownText", 1);
        dataQuality.put("legacyDelivered", 0);
        dataQuality.put("firstSeenMissing", 0);
        dataQuality.put("notes", List.of("E2E fixture"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timezone", "Asia/Shanghai");
        response.put("range", range);
        response.put("metrics", metrics);
        response.put("dataQuality", dataQuality);
        response.put("definitions", Map.of("newJobs", "fixture"));
        response.put("links", Map.of("attempts", "/boss/ledger?entity=attempts"));
        return response;
    }

    private Map<String, Object> metricsSeriesFixture() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metric", "newJobs");
        response.put("timezone", "Asia/Shanghai");
        response.put("points", List.of(
                Map.of("date", "2026-11-08", "value", 3),
                Map.of("date", "2026-11-09", "value", 5)));
        return response;
    }

    private Map<String, Object> effectiveFollowupsFixture() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("contactKey", "uid:e2e-hr");
        item.put("hrName", "王女士");
        item.put("companyName", "上海欧印传媒科技");
        item.put("replyState", "REPLIED");
        item.put("lastReplyAt", "2026-11-09 10:00:00");
        item.put("attemptId", "att-effective");
        item.put("encryptId", "e2e-job");
        item.put("imageStatus", "CONFIRMED");
        item.put("jobName", "AI 产品经理");
        item.put("jobUrl", "https://www.zhipin.com/job_detail/e2e-job.html");
        item.put("salary", "15-25K");
        item.put("city", "杭州");
        item.put("experience", "3-5年");
        item.put("degree", "本科");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", List.of(item));
        data.put("total", 1);
        data.put("limit", 20);
        return data;
    }

    private Map<String, Object> jobInsightFixture() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobName", "AI 产品经理（大模型应用方向）");
        job.put("companyName", "上海欧印传媒科技");
        job.put("salary", "15-25K");
        job.put("city", "杭州");
        job.put("experience", "3-5年");
        job.put("degree", "本科");
        job.put("industry", "人工智能");
        job.put("financingStage", "未融资");
        job.put("companyScale", "20-99人");
        job.put("jobUrl", "https://www.zhipin.com/job_detail/e2e-insight.html");
        job.put("jobDescriptionExcerpt", "负责 AI 产品规划、PRD、Demo 和客户沟通。");

        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("jobSummary", "岗位真正看重什么：产品设计、AI Demo 和客户沟通。");
        insight.put("companySummary", "公司处于早期 AI 应用阶段，具体业务需沟通核实。");
        insight.put("matchPoints", List.of("有 AI 产品 0→1 经验", "会写 PRD 和验收标准"));
        insight.put("gaps", List.of("需要补充 RAG/Agent 项目证据"));
        insight.put("risks", List.of("公司早期阶段需核实", "岗位职责范围较宽"));
        insight.put("communicationAdvice", List.of("首条消息先给可验证成果"));
        insight.put("openingScript", "我有两个 AI 产品 0→1 经验，想了解当前优先场景。");
        insight.put("replyScripts", List.of(Map.of("scene", "HR 问经验", "script", "我独立负责过完整闭环。")));
        insight.put("questionsToAsk", List.of("当前优先业务场景是什么？"));
        insight.put("avoidTopics", List.of("不要夸大未做过的 RAG/Agent 经验"));
        insight.put("confidence", 0.8);
        insight.put("disclaimer", "AI 基于岗位页面推断，需自行核实。");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("job", job);
        data.put("insight", insight);
        return data;
    }

    private Map<String, Object> bossStatus() {
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("used", 2);
        budget.put("limit", 10);
        budget.put("consumed", 2);
        budget.put("reserved", 0);
        budget.put("uncertain", 0);
        budget.put("remaining", 8);

        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("textConfirmed", 1);
        delivery.put("imageConfirmed", 0);

        Map<String, Object> status = new LinkedHashMap<>();
        boolean running = "RUNNING".equals(fixtureBossState) || "STARTING".equals(fixtureBossState);
        status.put("state", fixtureBossState);
        status.put("runId", "run-e2e");
        status.put("runningSeconds", running ? 75 : 0);
        status.put("pendingResolutions", 2);
        status.put("isLoggedIn", fixtureBossLoggedIn);
        status.put("workerExited", fixtureBossWorkerExited);
        status.put("canStart", fixtureBossCanStart);
        status.put("isRunning", running);
        status.put("budget", Map.of("firstContact", budget));
        status.put("delivery", delivery);
        status.put("pipeline", pipeline());
        return status;
    }

    private static Map<String, Object> pipeline() {
        List<Map<String, Object>> stages = new ArrayList<>();
        String[][] labels = {
                {"COLLECT", "采集岗位"}, {"RULE", "规则筛选"}, {"AI", "AI匹配"},
                {"GATE", "准入检查"}, {"CHAT", "沟通入口"}, {"IDENTITY", "身份核对"},
                {"TEXT", "文字发送"}, {"IMAGE", "图片简历"}, {"RESULT", "投递结果"},
        };
        for (int index = 0; index < labels.length; index++) {
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("stage", labels[index][0]);
            stage.put("label", labels[index][1]);
            stage.put("entered", Math.max(0, 9 - index));
            stage.put("outcomes", Map.of(labels[index][0], Math.max(0, 9 - index)));
            stages.add(stage);
        }
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("runId", "run-e2e");
        pipeline.put("currentStage", "RULE");
        pipeline.put("currentStageLabel", "规则筛选");
        pipeline.put("stages", stages);
        pipeline.put("recent", List.of());
        return pipeline;
    }

    private static Map<String, Object> deliverySummary() {
        Map<String, Object> attempts = new LinkedHashMap<>();
        attempts.put("textConfirmed", 3);
        attempts.put("imageConfirmed", 1);
        attempts.put("firstContactConfirmed", 3);

        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("used", 2);
        budget.put("limit", 10);
        budget.put("reserved", 0);
        budget.put("uncertain", 0);
        budget.put("remaining", 8);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("attempts", attempts);
        summary.put("budget", Map.of("firstContact", budget));
        summary.put("evaluations", Map.of());
        return summary;
    }

    private static Map<String, Object> templateView(int id) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("name", id == 1 ? "Java 后端方向" : "产品方向");
        view.put("direction", id == 1 ? "后端" : "产品");
        view.put("description", "e2e 模板");
        view.put("active", id == 1);
        view.put("updatedAt", "2026-11-08 10:00:00");
        view.put("expectedSalaryMin", 30);
        view.put("expectedSalaryMax", null);
        view.put("hasResume", false);
        view.put("deliveryPolicy", Map.of("collectMaxPerKeyword", 12,
                "collectMaxDurationMinutes", 4, "collectNoNewWindowSeconds", 9));
        return view;
    }

    private static Map<String, Object> templateDetail() {
        Map<String, Object> bossConfig = new LinkedHashMap<>();
        bossConfig.put("keywords", "[\"Java\",\"后端\"]");
        bossConfig.put("cityCode", "上海");
        bossConfig.put("jobType", "全职");
        bossConfig.put("industry", "[]");
        bossConfig.put("experience", "[]");
        bossConfig.put("degree", "[]");
        bossConfig.put("salary", "[]");
        bossConfig.put("scale", "[]");
        bossConfig.put("stage", "[]");
        bossConfig.put("enableAi", 1);
        bossConfig.put("sendImgResume", 0);
        bossConfig.put("allowRepeatChat", 1);
        bossConfig.put("filterDeadHr", 0);
        bossConfig.put("sayHi", "您好，我有相关经验。");
        bossConfig.put("repeatChatMessages", "");
        bossConfig.put("waitTime", 5);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("highestDegree", "本科");
        rule.put("salaryStrategy", "INTERSECT");
        rule.put("expectedSalaryMin", 30);
        rule.put("expectedSalaryMax", 0);
        rule.put("jobDirections", List.of("后端"));
        rule.put("cities", List.of("上海"));
        rule.put("workTypes", List.of());
        rule.put("blacklistMatchMode", "CONTAINS");
        rule.put("blacklistJob", List.of());
        rule.put("blacklistCompany", List.of());
        rule.put("blacklistRecruiter", List.of());
        rule.put("whitelist", List.of());
        rule.put("filterDeadHr", false);
        rule.put("deadStatus", List.of());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("template", templateView(1));
        detail.put("bossConfig", bossConfig);
        detail.put("rule", rule);
        detail.put("aiIntroduce", "5 年后端经验");
        detail.put("aiPrompt", "严格匹配后端岗位");
        detail.put("deliveryPolicy", Map.of("collectMaxPerKeyword", 12,
                "collectMaxDurationMinutes", 4, "collectNoNewWindowSeconds", 9));
        detail.put("resumeFile", null);
        return detail;
    }

    private static Map<String, Object> manualReleaseQueue() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evaluationId", 1001);
        item.put("statusLabel", "待放行");
        item.put("jobName", "Java 开发");
        item.put("companyName", "测试公司");
        item.put("jobUrl", "https://www.zhipin.com/job_detail/e2e.html");
        item.put("ruleDecision", "REVIEW");
        item.put("ruleReasonCode", "SALARY_REVIEW");
        item.put("ruleReason", "薪资需人工确认");
        item.put("aiDecision", "REVIEW");
        item.put("aiAnalysis", "技术栈接近，需要人工复核");
        item.put("createdAt", "2026-11-08 10:00:00");

        Map<String, Object> itemWithoutUrl = new LinkedHashMap<>();
        itemWithoutUrl.put("evaluationId", 1002);
        itemWithoutUrl.put("statusLabel", "待放行");
        itemWithoutUrl.put("jobName", "无链接岗位");
        itemWithoutUrl.put("companyName", "测试公司二");
        itemWithoutUrl.put("ruleDecision", "REVIEW");
        itemWithoutUrl.put("ruleReasonCode", "SALARY_REVIEW");
        itemWithoutUrl.put("ruleReason", "薪资需人工确认");
        itemWithoutUrl.put("aiDecision", "REVIEW");
        itemWithoutUrl.put("aiAnalysis", "需要人工复核");
        itemWithoutUrl.put("createdAt", "2026-11-08 10:00:00");

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("PENDING", 2);
        counts.put("APPROVED", 3);
        counts.put("FAILED", 0);
        counts.put("CONSUMED", 0);
        counts.put("REJECTED", 0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", List.of(item, itemWithoutUrl));
        data.put("counts", counts);
        data.put("maxPerRun", 2);
        return data;
    }

    private static Map<String, Object> pageResult(List<Object> items, long total) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("total", total);
        page.put("page", 1);
        page.put("size", 50);
        page.put("pages", 0);
        return page;
    }

    private static void ok(Route route, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        route.fulfill(new Route.FulfillOptions().setStatus(200)
                .setContentType("application/json; charset=utf-8")
                .setBody(json(body)));
    }

    private static Map<String, Object> resumeAnalysisFixture() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("yearsExperience", 5);
        profile.put("roles", List.of("Java 后端"));
        profile.put("skills", List.of("Java", "Spring"));
        profile.put("highestDegree", "本科");
        profile.put("major", "软件工程");
        profile.put("graduationYear", 2020);
        profile.put("cities", List.of("上海", "杭州"));
        profile.put("expectedSalaryMin", 30);
        profile.put("expectedSalaryMax", 50);
        profile.put("industries", List.of("互联网/AI"));
        profile.put("strengths", List.of("高并发"));
        profile.put("warnings", List.of());
        profile.put("confidence", 0.9);
        Map<String, Object> direction = new LinkedHashMap<>();
        direction.put("title", "Java 后端");
        direction.put("fit", 0.86);
        direction.put("reason", "技术栈匹配");
        direction.put("keywords", List.of("Java", "后端"));
        direction.put("industries", List.of("互联网/AI", "企业服务"));
        Map<String, Object> band = new LinkedHashMap<>();
        band.put("label", "稳妥区间");
        band.put("min", 25);
        band.put("max", 40);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", profile);
        data.put("directions", List.of(direction));
        data.put("salaryBands", List.of(band));
        data.put("warnings", List.of());
        data.put("visionUsed", false);
        return data;
    }

    private static Integer requestedSalaryMin(String body) {
        if (body == null || body.isBlank()) {
            return 30;
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            var min = node.get("expectedSalaryMin");
            return min != null && min.isNumber() ? min.asInt() : 30;
        } catch (Exception ignored) {
            return 30;
        }
    }

    private static Map<String, Object> aiDraftFixture(Integer minSalary) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("collectMaxPerKeyword", 2);
        policy.put("collectMaxDurationMinutes", 5);
        policy.put("collectNoNewWindowSeconds", 20);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("highestDegree", "本科");
        rule.put("salaryStrategy", "INTERSECT");
        rule.put("expectedSalaryMin", minSalary == null ? 30 : minSalary);
        rule.put("expectedSalaryMax", 0);
        rule.put("blacklistJob", List.of("外包", "销售", "保险", "房产中介", "培训", "劳务派遣", "外包驻场"));
        rule.put("cities", List.of("上海", "杭州", "北京", "深圳"));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("keywords", List.of("Java", "后端"));
        config.put("cityCode", "上海");
        config.put("industry", List.of("互联网/AI", "企业服务"));
        config.put("degree", List.of("本科"));
        config.put("experience", List.of("5-10年"));
        config.put("scale", List.of("20-99人", "100-499人", "500-999人", "1000-9999人", "10000人以上"));
        config.put("stage", List.of());
        config.put("enableAI", true);
        config.put("sendImgResume", false);
        config.put("rule", rule);
        config.put("aiIntroduce", "5 年 Java 后端经验");
        config.put("deliveryPolicy", policy);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "Java 后端 模板");
        draft.put("direction", "Java 后端");
        draft.put("description", "AI 向导草稿");
        draft.put("config", config);
        draft.put("warnings", List.of());
        return draft;
    }

    private static Map<String, Object> option(String code, String name) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("code", code);
        option.put("name", name);
        return option;
    }

    private void waitCardValue(String label, String value) {
        page.waitForSelector("button:has-text(\"" + label + "\"):has-text(\"" + value + "\")",
                new Page.WaitForSelectorOptions().setTimeout(15_000));
    }

    private void assertDetailRequest(String hint, String... queryFragments) {
        ApiCall call = apiCalls.stream()
                .filter(item -> "GET".equals(item.method()) && item.query() != null
                        && java.util.Arrays.stream(queryFragments)
                        .allMatch(fragment -> item.query().contains(fragment)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(hint + " 缺少预期 GET 查询："
                        + java.util.Arrays.toString(queryFragments) + " / " + apiCalls));
        assertTrue(call.query().contains("size=20"), hint + " 详情应拉最多 20 条：" + call.query());
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int index = pair.indexOf('=');
            if (index < 0) {
                params.put(java.net.URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                params.put(java.net.URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static int intParam(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Map<String, Object> attemptItem(String id, String job, String company, String status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("attemptId", id);
        item.put("jobName", job);
        item.put("companyName", company);
        item.put("textStatus", status);
        item.put("startedAt", "2026-11-08 09:00:00");
        return item;
    }

    private static Map<String, Object> contactItem(String key, String hrName, String company) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("contactKey", key);
        item.put("hrName", hrName);
        item.put("companyName", company);
        item.put("replyState", "REPLIED");
        item.put("lastReplyAt", "2026-11-08 10:00:00");
        return item;
    }

    private static Map<String, Object> followupItem() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("planId", "plan-e2e-1");
        item.put("jobName", "产品经理");
        item.put("companyName", "测试公司一");
        item.put("planType", "FOLLOWUP");
        item.put("reason", "候选人有跟进意向");
        item.put("status", "SUGGESTED");
        item.put("suggestedAt", "2026-11-08 09:30:00");
        return item;
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 页面工具 ====================

    private void open(String path) {
        page.navigate("http://127.0.0.1:" + port + path);
    }

    private void waitVisible(String text) {
        page.waitForSelector("text=" + text, new Page.WaitForSelectorOptions().setTimeout(15_000));
    }

    // ==================== 静态文件服务 ====================

    private static void serveStatic(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            if (requestPath == null || requestPath.isBlank() || "/".equals(requestPath)) {
                requestPath = "/index.html";
            }
            Path candidate = resolve(requestPath);
            if (candidate == null) {
                respond(exchange, 404, "text/plain; charset=utf-8", "Not Found: " + requestPath);
                return;
            }
            respond(exchange, 200, contentType(candidate), Files.readAllBytes(candidate));
        } finally {
            exchange.close();
        }
    }

    private static Path resolve(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Path direct = DIST.resolve(relative).normalize();
        if (direct.startsWith(DIST) && Files.isRegularFile(direct)) {
            return direct;
        }
        if (!path.endsWith("/") && !relative.contains(".")) {
            Path html = DIST.resolve(relative + ".html").normalize();
            if (html.startsWith(DIST) && Files.isRegularFile(html)) {
                return html;
            }
        }
        if (!relative.contains(".")) {
            Path index = DIST.resolve(relative).resolve("index.html").normalize();
            if (index.startsWith(DIST) && Files.isRegularFile(index)) {
                return index;
            }
        }
        return null;
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String contentType, String body) throws IOException {
        respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
