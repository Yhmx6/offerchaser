package com.offerchaser.worker.boss;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本地路由夹具驱动 {@link BossChatNavigator} 的三态导航结果，全程离线、不发消息。
 * <p>
 * 覆盖：首聊直接进入会话、确认弹层出现时机（1 秒窗口）、重复沟通开关关闭时<b>不点击</b>、
 * 弹层确认后同标签页/新弹窗进入、取消/停止、超时、旧页面上的输入框不得被误用、无输入框、
 * 慢加载聊天页，以及点击后 stop 的 CLICKED_UNKNOWN 判定。
 */
@EnabledIfEnvironmentVariable(named = "BOSS_CHAT_DOM_TEST", matches = "true")
@DisplayName("BossChatNavigator：离线浏览器导航夹具")
class BossChatNavigatorDomTest {

    private static final String FIXTURE_ORIGIN = "http://navigator-fixture.test";
    private static final String ENTRY_BUTTON_ID = "#entry-button";
    private static final String MODAL_ROOT_ID = "#modal-root";
    private static final String LEGACY_INPUT_ID = "#legacy-input";

    private static final String JOB_PAGE = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>岗位页</title></head><body>
            <article class="job-card">
              <h3 class="job-title">模拟岗位</h3>
              <div class="job-actions">
                <button id="entry-button" type="button" onclick="entryClicked()">继续沟通</button>
              </div>
              <div id="modal-root"></div>
              <textarea id="legacy-input" class="input-area" style="display:none"></textarea>
            </article>
            <script>
            const ORIGIN = '%s';
            const CONFIRM_DIALOG = `<div class="greet-boss-dialog" role="dialog">
              <div class="greet-boss-layer"></div>
              <section class="greet-boss-container">
                <header class="greet-boss-header"><h4>已向BOSS发送消息</h4></header>
                <div class="greet-boss-body"><p class="greet-con">测试招呼语</p></div>
                <footer class="greet-boss-footer">
                  <a href="javascript:;" class="default-btn cancel-btn" onclick="closeDialog()">留在此页</a>
                  <a href="javascript:;" class="default-btn sure-btn" onclick="confirmInDialog()">继续沟通</a>
                </footer>
              </section>
            </div>`;
            function openChat() {
              %s
            }
            function confirmInDialog() {
              closeDialog();
              document.querySelector('#legacy-input').style.display = 'block';
              %s
            }
            function closeDialog() {
              document.querySelector('#modal-root').innerHTML = '';
            }
            function entryClicked() {
              %s
            }
            </script>
            </body></html>
            """;

    private static final String READY_CHAT_PAGE = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>聊天页</title></head><body>
            <main class="chat-window"><h2>对话</h2><div id="composer-slot"></div></main>
            <script>
            window.setTimeout(function () {
              document.getElementById('composer-slot').innerHTML =
                '<div id="chat-input" class="chat-input" contenteditable="true" role="textbox"></div>';
            }, 120);
            </script>
            </body></html>
            """;

    private static final String EMPTY_CHAT_PAGE = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>聊天页加载中</title></head><body>
            <main class="chat-window"><h2>对话加载中</h2><p>请稍候，输入框尚未出现</p></main>
            </body></html>
            """;

    private static Playwright playwrightRuntime;
    private static Browser chromium;
    private BrowserContext browserContext;
    private Page activePage;

    @BeforeAll
    static void startHeadlessChromium() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        playwrightRuntime = Playwright.create(new Playwright.CreateOptions().setEnv(env));

        String localChrome = System.getenv("BOSS_TEST_CHROME");
        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true);
        if (localChrome != null && !localChrome.isBlank()) {
            launch.setExecutablePath(Path.of(localChrome));
        }
        chromium = playwrightRuntime.chromium().launch(launch);
    }

    @AfterAll
    static void stopChromium() {
        if (chromium != null) {
            chromium.close();
        }
        if (playwrightRuntime != null) {
            playwrightRuntime.close();
        }
    }

    @BeforeEach
    void openFixtureContext() {
        browserContext = chromium.newContext();
        activePage = browserContext.newPage();
    }

    @AfterEach
    void closeFixtureContext() {
        browserContext.close();
    }

    // ==================== 夹具装载 ====================

    /**
     * 装载岗位页与聊天页路由。
     *
     * @param popup       确认后是否用 {@code window.open} 打开新标签页（否则同标签页跳转）
     * @param withDialog  点击入口后是否出现「已向BOSS发送消息」确认弹层
     * @param navigate    确认后是否真的跳转到聊天页；false 表示停在岗位页
     * @param chatReady   聊天页是否在加载后出现可编辑输入框
     */
    private void loadScenario(boolean popup, boolean withDialog, boolean navigate, boolean chatReady) {
        String chatUrl = FIXTURE_ORIGIN + "/chat";
        String navigateChat = popup
                ? "window.open('" + chatUrl + "', '_blank');"
                : "location.assign('" + chatUrl + "');";
        String afterConfirm = navigate ? "window.setTimeout(function () { " + navigateChat + " }, 120);" : "";
        String entryAction = withDialog
                ? "window.setTimeout(function () { document.querySelector('#modal-root').innerHTML = CONFIRM_DIALOG; }, 120);"
                : (navigate ? "window.setTimeout(function () { " + navigateChat + " }, 200);" : "");

        String jobHtml = JOB_PAGE.formatted(FIXTURE_ORIGIN, navigateChat, afterConfirm, entryAction);
        String chatHtml = chatReady ? READY_CHAT_PAGE : EMPTY_CHAT_PAGE;
        browserContext.route(FIXTURE_ORIGIN + "/**", route -> route.fulfill(
                new Route.FulfillOptions()
                        .setContentType("text/html; charset=utf-8")
                        .setBody(route.request().url().endsWith("/chat") ? chatHtml : jobHtml)));
        activePage.navigate(FIXTURE_ORIGIN + "/job");
    }

    // ==================== 三态断言 ====================

    private static BossChatNavigator.OpenResult expectOpened(BossChatNavigator.OpenResult actual) {
        assertNotNull(actual, "导航结果不允许为 null");
        assertEquals(BossChatNavigator.OpenOutcome.OPENED, actual.outcome(),
                "聊天页已就绪时应返回 OPENED");
        assertNotNull(actual.chat(), "OPENED 必须带可用的聊天页/输入框");
        assertNotNull(actual.detail(), "OPENED 必须带可读的 detail");
        return actual;
    }

    private static void expectClickedUnknown(BossChatNavigator.OpenResult actual) {
        assertNotNull(actual, "导航结果不允许为 null");
        assertEquals(BossChatNavigator.OpenOutcome.CLICKED_UNKNOWN, actual.outcome(),
                "入口已点击但聊天页未就绪时必须返回 CLICKED_UNKNOWN");
        assertNull(actual.chat(), "CLICKED_UNKNOWN 不允许返回聊天对象");
        assertNotNull(actual.detail(), "CLICKED_UNKNOWN 必须说明原因");
    }

    // ==================== 基础进入 ====================

    @Test
    @DisplayName("首次沟通：同页内联输入框即可进入，不触发页面导航")
    void firstContactCanUseInlineComposerWithoutLeavingPage() {
        activePage.setContent("""
                <section class="job-card">
                  <button id="inline-start" type="button">立即沟通</button>
                  <div id="composer-slot"></div>
                </section>
                <script>
                  document.querySelector('#inline-start').addEventListener('click', function () {
                    document.querySelector('#composer-slot').innerHTML =
                      '<div id="chat-input" class="chat-input" contenteditable="true" role="textbox"></div>';
                  });
                </script>
                """);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult opened =
                    expectOpened(navigator.open(activePage.locator("#inline-start"), false, true, null, 3_000));
            assertFalse(opened.chat().repeat(), "首次沟通不应被标记为重复沟通");
            assertEquals(activePage, opened.chat().page(), "内联聊天应停留在原页面");
            assertEquals("chat-input", opened.chat().input().getAttribute("id"));
        }
    }

    static Stream<Arguments> entryCombinations() {
        return Stream.of(
                Arguments.of("同标签页 + 有确认弹层", false, true),
                Arguments.of("新标签页 + 有确认弹层", true, true),
                Arguments.of("同标签页 + 无确认弹层", false, false),
                Arguments.of("新标签页 + 无确认弹层", true, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryCombinations")
    @DisplayName("确认弹层点击后：同标签页或新弹窗都能进入就绪聊天页")
    void opensReadyChatInSameTabOrPopup(String mode, boolean popup, boolean withDialog) {
        loadScenario(popup, withDialog, true, true);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult opened = expectOpened(
                    navigator.open(activePage.locator(ENTRY_BUTTON_ID), true, true, null, 5_000));
            assertEquals(FIXTURE_ORIGIN + "/chat", opened.chat().page().url());
            assertEquals("chat-input", opened.chat().input().getAttribute("id"));
            assertEquals("true", opened.chat().input().getAttribute("contenteditable"),
                    "聊天输入框应为 contenteditable 输入区");
            assertEquals(popup, opened.chat().page() != activePage,
                    "是否进入新标签页必须与场景一致");
            assertTrue(opened.chat().repeat(), "允许重复沟通时应标记 repeat");
        }
        assertTrue(browserContext.pages().isEmpty(), "关闭导航器后不应残留夹具页面");
    }

    @Test
    @DisplayName("确认弹层只确认入口，不改变本次重复沟通标记")
    void confirmationKeepsRepeatFlagUnchanged() {
        loadScenario(false, true, true, true);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult opened = expectOpened(navigator.open(
                    activePage.locator(ENTRY_BUTTON_ID), false, true, null, 5_000));
            assertFalse(opened.chat().repeat(), "确认弹层不得把首聊改成重复沟通");
        }
    }

    // ==================== 不点击 / 停止 ====================

    @Test
    @DisplayName("重复沟通开关关闭：不点击入口、不出现弹层、URL 不变")
    void disabledRepeatSwitchBlocksClickEntirely() {
        loadScenario(false, true, true, true);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult blocked = navigator.open(
                    activePage.locator(ENTRY_BUTTON_ID), true, false, null, 3_000);
            assertNotNull(blocked);
            assertEquals(BossChatNavigator.OpenOutcome.NOT_CLICKED, blocked.outcome(),
                    "开关关闭时必须 NOT_CLICKED");
            assertNull(blocked.chat());
            assertEquals(0, activePage.locator(MODAL_ROOT_ID + " .greet-boss-dialog").count(),
                    "未点击时不应出现确认弹层");
            assertEquals(FIXTURE_ORIGIN + "/job", activePage.url(), "未点击时不得离开岗位页");
        }
    }

    @Test
    @DisplayName("点击后收到停止指令：返回 CLICKED_UNKNOWN 且点击已实际发生")
    void stopAfterClickIsReportedAsUnknownNotUnclicked() {
        activePage.setContent("""
                <section class="job-card">
                  <button id="stopped-entry" type="button">立即沟通</button>
                  <div id="mount"></div>
                </section>
                <script>
                  document.querySelector('#stopped-entry').addEventListener('click', function () {
                    window.__navigatorClicked = true;
                  });
                </script>
                """);
        AtomicInteger stopChecks = new AtomicInteger();
        Supplier<Boolean> stopSignal = () -> stopChecks.incrementAndGet() > 1;
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult stopped = navigator.open(
                    activePage.locator("#stopped-entry"), false, true, stopSignal, 3_000);
            expectClickedUnknown(stopped);
            assertTrue(stopped.detail().contains("停止指令"), "detail 必须说明收到停止指令");
            assertEquals(Boolean.TRUE, activePage.evaluate("() => window.__navigatorClicked === true"),
                    "点击后取消时点击动作必须已经发生");
        }
    }

    // ==================== 1 秒窗口 / 超时 ====================

    @Test
    @DisplayName("超过 1 秒才出现的确认弹层不算本次确认，也不能自动点掉")
    void lateDialogOutsideOneSecondWindowIsIgnored() {
        activePage.setContent("""
                <section class="job-card">
                  <button id="late-entry" type="button">立即沟通</button>
                  <div id="mount"></div>
                </section>
                <script>
                  document.querySelector('#late-entry').addEventListener('click', function () {
                    window.setTimeout(function () {
                      document.querySelector('#mount').innerHTML =
                        '<div class="greet-boss-dialog"><div class="greet-boss-footer">' +
                        '<a href="javascript:;" class="sure-btn" onclick="window.__dialogClicked=true">继续沟通</a>' +
                        '</div></div>';
                    }, 1350);
                  });
                </script>
                """);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult late = navigator.open(
                    activePage.locator("#late-entry"), false, true, null, 2_050);
            expectClickedUnknown(late);
            assertEquals(1, activePage.locator(".greet-boss-dialog").count(),
                    "延迟弹层出现后应保留在页面上（本次不点）");
            assertNotEquals(Boolean.TRUE, activePage.evaluate("() => window.__dialogClicked === true"),
                    "1 秒窗口之外的弹层不得被自动点击");
        }
    }

    @Test
    @DisplayName("点击后聊天页始终无输入框：超时返回 CLICKED_UNKNOWN")
    void clickedButChatStaysUnreadyUntilTimeout() {
        loadScenario(false, false, false, false);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult timedOut = navigator.open(
                    activePage.locator(ENTRY_BUTTON_ID), false, true, null, 950);
            expectClickedUnknown(timedOut);
            assertTrue(timedOut.detail().contains("已点击"), "detail 必须说明点击已发生");
        }
    }

    @Test
    @DisplayName("确认弹层已点掉但聊天页无输入框：CLICKED_UNKNOWN")
    void confirmedDialogWithoutChatInputIsUnknown() {
        loadScenario(false, true, false, false);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult noInput = navigator.open(
                    activePage.locator(ENTRY_BUTTON_ID), false, true, null, 1_450);
            expectClickedUnknown(noInput);
            assertEquals(0, activePage.locator("#modal-root .greet-boss-dialog").count(),
                    "确认后弹层应已被移除，证明点击确实发生");
        }
    }

    // ==================== 旧输入框不得误用 ====================

    @Test
    @DisplayName("导航失败时停在岗位页：页面上可见输入框也不能当作聊天页")
    void visibleInputOnJobPageMustNotBeUsed() {
        loadScenario(false, true, false, true);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult oldPage = navigator.open(
                    activePage.locator(ENTRY_BUTTON_ID), true, true, null, 850);
            expectClickedUnknown(oldPage);
            assertTrue(activePage.locator(LEGACY_INPUT_ID).isVisible(),
                    "岗位页上的旧输入框应为可见，用于验证不会被误用");
            assertEquals(FIXTURE_ORIGIN + "/job", activePage.url(), "导航失败时应仍停在岗位页");
        }
    }

    @Test
    @DisplayName("已有可见输入框的岗位页：不离开页面就不能返回 OPENED")
    void existingVisibleInputRequiresLeavingJobPage() {
        activePage.setContent("""
                <section class="job-card">
                  <button id="reentry" type="button" onclick="window.__reentryClicked=true">继续沟通</button>
                  <textarea class="input-area"></textarea>
                </section>
                """);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult stayed = navigator.open(
                    activePage.locator("#reentry"), true, true, null, 450);
            expectClickedUnknown(stayed);
            assertEquals(Boolean.TRUE, activePage.evaluate("() => window.__reentryClicked === true"));
            assertNull(stayed.chat(), "旧页面里的可见输入框不得被当成聊天页");
        }
    }

    // ==================== 慢加载 / 无输入 ====================

    @Test
    @DisplayName("聊天页输入框晚于弹层检测窗口加载：仍应等待并返回 OPENED")
    void slowChatInputCanAppearAfterDialogWindow() {
        String slowPage = """
                <!doctype html><html lang="zh-CN"><body>
                <main class="chat-window"><h2>对话加载中</h2><div id="slow-slot"></div></main>
                <script>
                window.setTimeout(function () {
                  document.getElementById('slow-slot').innerHTML =
                    '<div id="chat-input" class="chat-input" contenteditable="true" role="textbox"></div>';
                }, 1350);
                </script>
                </body></html>
                """;
        browserContext.route(FIXTURE_ORIGIN + "/slow-chat", route -> route.fulfill(
                new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(slowPage)));
        activePage.setContent("""
                <section class="job-card"><button id="slow-entry" type="button">继续沟通</button></section>
                <script>
                  document.querySelector('#slow-entry').addEventListener('click', function () {
                    location.assign('%s/slow-chat');
                  });
                </script>
                """.formatted(FIXTURE_ORIGIN));
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult slow = expectOpened(navigator.open(
                    activePage.locator("#slow-entry"), true, true, null, 3_000));
            assertEquals(FIXTURE_ORIGIN + "/slow-chat", slow.chat().page().url());
            assertEquals("chat-input", slow.chat().input().getAttribute("id"));
        }
    }

    @Test
    @DisplayName("新弹窗聊天页没有输入框：CLICKED_UNKNOWN 且不残留页面")
    void popupWithoutInputIsUnknownAndCleansUp() {
        loadScenario(true, true, true, false);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult noInput = navigator.open(
                    activePage.locator(ENTRY_BUTTON_ID), false, true, null, 1_050);
            expectClickedUnknown(noInput);
        }
        assertTrue(browserContext.pages().isEmpty(), "等待超时后应关闭本轮产生的页面");
    }

    @Test
    @DisplayName("点击前已收到 stop：返回 NOT_CLICKED，不点击也不跳转")
    void stopBeforeClickBlocksEntry() {
        loadScenario(false, true, true, true);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult notClicked = navigator.open(
                    activePage.locator(ENTRY_BUTTON_ID), true, true, () -> true, 1_200);
            assertNotNull(notClicked);
            assertEquals(BossChatNavigator.OpenOutcome.NOT_CLICKED, notClicked.outcome());
            assertNull(notClicked.chat());
            assertEquals(FIXTURE_ORIGIN + "/job", activePage.url(), "点击前停止不得离开岗位页");
        }
    }

    @Test
    @DisplayName("确认弹层被点击后无任何导航：detail 保留点击证据")
    void dialogConfirmedWithoutNavigationIsClickedUnknown() {
        loadScenario(false, true, false, true);
        try (BossChatNavigator navigator = new BossChatNavigator(activePage)) {
            BossChatNavigator.OpenResult result = navigator.open(
                    activePage.locator(ENTRY_BUTTON_ID), false, true, null, 1_200);
            expectClickedUnknown(result);
            assertTrue(result.detail().contains("已点击") || result.detail().contains("未找到"),
                    "detail 必须体现点击已发生：" + result.detail());
        }
    }
}
