package com.offerchaser.worker.boss;

import com.offerchaser.worker.boss.fixtures.ChatDomFixtures;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatSendConfirmer 的真实浏览器 HTML 夹具测试（A+5：不能只 mock frame.evaluate）。
 * <p>
 * 用 {@code page.setContent(...)} 注入聊天 DOM，让 Playwright 在真实 Chrome 里执行生产探测脚本，
 * 覆盖 A-01 身份 VERIFIED / MISMATCH / UNKNOWN 与 A-02 文字确认（含两个假成功场景）以及图片基线。
 * 不访问招聘网站、不发真实消息；通过环境变量 {@code BOSS_CHAT_DOM_TEST=true} 启用，
 * Chrome 路径由 {@code BOSS_TEST_CHROME} 指定（沿用现有浏览器夹具方式）。
 * <p>
 * 注意：文字/图片确认内部按秒轮询，本类部分用例会真实等待 2~3 秒，属于预期耗时。
 */
@EnabledIfEnvironmentVariable(named = "BOSS_CHAT_DOM_TEST", matches = "true")
class ChatSendConfirmerBrowserDomTest {

    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;
    ChatSendConfirmer confirmer;

    @BeforeAll
    static void launch() {
        var environment = new java.util.HashMap<>(System.getenv());
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(environment));
        String chrome = System.getenv("BOSS_TEST_CHROME");
        var options = new BrowserType.LaunchOptions().setHeadless(true);
        if (chrome != null) options.setExecutablePath(Path.of(chrome));
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void setup() {
        context = browser.newContext();
        page = context.newPage();
        // 使用生产构造器（真实 1 秒轮询），验证脚本在真实页面里确实能工作。
        confirmer = new ChatSendConfirmer();
    }

    @AfterEach
    void cleanup() {
        context.close();
    }

    private void setChatPage(String chatTitle, String messagesHtml, String extraHtml) {
        page.setContent(ChatDomFixtures.pageWithChat("BOSS直聘", chatTitle, messagesHtml, extraHtml));
    }

    private ChatSendConfirmer.TextResult sendText(String expected, int timeoutSeconds) {
        Locator sendButton = page.locator(".send-message");
        return confirmer.clickAndConfirmText(page, sendButton, expected, timeoutSeconds, null);
    }

    // ==================================================================
    // A-01 身份核对（真实 DOM）
    // ==================================================================

    @Test
    void identityVerifiedByRealChatTitleHr() {
        setChatPage("目标公司 · 王先生 · 招聘者", "", "");

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome());
        assertEquals(ChatSendConfirmer.EVIDENCE_TITLE_HR, result.evidenceLevel());
    }

    @Test
    void identityMismatchByRealChatTitle() {
        setChatPage("另一公司 · 李女士 · 招聘者", "", "");

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.MISMATCH, result.outcome());
    }

    @Test
    void identityUnknownWhenOnlyGenericJobNameAppears() {
        setChatPage("Java工程师 · BOSS直聘", "", "");

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.UNKNOWN, result.outcome(),
                "岗位通用名不能单独证明联系人身份");
    }

    @Test
    void identityUnknownWhenRealChatRootMissing() {
        page.setContent(ChatDomFixtures.chatWithoutRoot("BOSS直聘", "目标公司 · 王先生", ""));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.UNKNOWN, result.outcome(),
                "没有聊天根节点时即使聊天头标题匹配也不能核验身份");
    }

    /** document.title 里有完整身份，但聊天区域标题不符：必须以聊天区域为准，不得用 document.title 兜底。 */
    @Test
    void identityIgnoresDocumentTitleInRealDom() {
        page.setContent(ChatDomFixtures.pageWithChat(
                "目标公司 王先生 - BOSS直聘 招聘",
                "另一公司 · 李女士 · 招聘者",
                "", ""));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertNotEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome(),
                "不允许用 document.title 证明身份");
        assertEquals(ChatSendConfirmer.IdentityOutcome.MISMATCH, result.outcome());
    }

    /** A-01 补充裁决（真实 DOM）：同公司不同 HR（标题里是李女士，目标 HR 王先生）→ 非 VERIFIED。 */
    @Test
    void identityMismatchWhenSameCompanyDifferentHrInRealDom() {
        setChatPage("示例科技 李女士 Java工程师", "", "");

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "示例科技", "Java工程师", "王先生", null, true);

        assertNotEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome(),
                "同公司不同 HR 不得放行");
        assertEquals(ChatSendConfirmer.IdentityOutcome.MISMATCH, result.outcome());
    }

    /** A-01 补充裁决（真实 DOM）：提供了 HR，但标题只有公司+岗位、没有联系人形态 → UNKNOWN。 */
    @Test
    void identityUnknownWhenHrProvidedButTitleHasNoContactInRealDom() {
        setChatPage("示例科技 Java工程师", "", "");

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "示例科技", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.UNKNOWN, result.outcome(),
                "HR 未命中且无其他联系人证据时不能 VERIFIED");
    }

    /** URL 命中 encryptUserId：强证据，即使聊天标题只有岗位名也算 VERIFIED。 */
    @Test
    void identityVerifiedByUrlChatIdInRealBrowser() {
        String html = ChatDomFixtures.pageWithChat("BOSS直聘", "Java工程师 · BOSS直聘", "", "");
        context.route("http://offerchaser.test/**", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html; charset=utf-8")
                .setBody(html)));
        page.navigate("http://offerchaser.test/web/geek/chat?uid=ENC-USER-9&job=java");

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", "ENC-USER-9", true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome());
        assertEquals(ChatSendConfirmer.EVIDENCE_URL_CHAT_ID, result.evidenceLevel());
    }

    // ==================================================================
    // A-02 文字确认（真实 DOM）：不得假成功
    // ==================================================================

    @Test
    void textConfirmedWithRealDom() {
        setChatPage("目标公司 · 王先生", "",
                ChatDomFixtures.sendButton("send-btn")
                        + ChatDomFixtures.appendOnClickScript("send-btn",
                        ChatDomFixtures.ownTextMessage("您好，我对该岗位很感兴趣")));

        ChatSendConfirmer.TextResult result = sendText("您好，我对该岗位很感兴趣", 5);

        assertTrue(result.confirmed(), "真实 DOM 中新消息出现且自身已就绪时应确认");
        assertTrue(result.clickAttempted());
    }

    /** A-02 假成功场景 1 的真实 DOM 版本：点击前聊天根都不存在，迟到历史不能证明发送成功。 */
    @Test
    void missingBaselineAndLateHistoryMustNotClickOrConfirm() {
        page.setContent(ChatDomFixtures.chatWithoutRoot("BOSS直聘", "目标公司 · 王先生",
                ChatDomFixtures.sendButton("send-btn")
                        // 2.5 秒后才把带相同文字的历史聊天“加载”出来（晚于基线重试窗口）。
                        + "<script>setTimeout(function(){document.querySelector('.chat-panel').innerHTML="
                        + "'<div class=\"chat-message\"><ul class=\"im-list\">"
                        + "<li class=\"message-item item-myself\"><div class=\"message-content\">hello</div></li>"
                        + "</ul></div>';}, 2500);</script>"));

        ChatSendConfirmer.TextResult result = sendText("hello", 3);

        assertFalse(result.confirmed(), "基线不可用时迟到历史消息不能证明本次发送成功");
        assertFalse(result.clickAttempted(), "基线不可用时不允许点击发送按钮");
        assertTrue(result.uncertain());
    }

    /** A-02 假成功场景 2 的真实 DOM 版本：目标消息仍发送中，另一条最后消息已就绪。 */
    @Test
    void matchedMessageStillSendingMustNotBeConfirmedInRealDom() {
        setChatPage("目标公司 · 王先生", "",
                ChatDomFixtures.sendButton("send-btn")
                        + ChatDomFixtures.appendOnClickScript("send-btn",
                        ChatDomFixtures.ownTextMessageSending("hello")
                                + ChatDomFixtures.ownTextMessage("other")));

        ChatSendConfirmer.TextResult result = sendText("hello", 2);

        assertFalse(result.confirmed(), "目标消息自身仍在发送中，不能被另一条最后消息带成成功");
        assertTrue(result.uncertain());
        assertTrue(result.clickAttempted());
        assertTrue(result.detail().contains("发送中"));
    }

    /** 真实 DOM 中的失败标记：本次新增消息带 message-fail / 发送失败。 */
    @Test
    void textFailedMarkerInRealDom() {
        setChatPage("目标公司 · 王先生", "",
                ChatDomFixtures.sendButton("send-btn")
                        + ChatDomFixtures.appendOnClickScript("send-btn",
                        ChatDomFixtures.ownTextMessageFailed("您好，我对该岗位很感兴趣")));

        ChatSendConfirmer.TextResult result = sendText("您好，我对该岗位很感兴趣", 3);

        assertFalse(result.confirmed());
        assertTrue(result.failed());
        assertTrue(result.clickAttempted());
    }

    // ==================================================================
    // A-02 图片确认（真实 DOM）
    // ==================================================================

    @Test
    void imageBaselineUnknownInRealDomReturnsMinusOne() {
        page.setContent(ChatDomFixtures.chatWithoutRoot("BOSS直聘", "目标公司 · 王先生", ""));

        assertEquals(-1, confirmer.readOwnImageMessageCount(page),
                "真实页面没有聊天根时图片基线必须是 -1");
        ChatSendConfirmer.ImageResult result = confirmer.awaitImageConfirmed(page, -1, 3, null);
        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
    }

    @Test
    void imageConfirmedAfterStableNewImageInRealDom() {
        setChatPage("目标公司 · 王先生", ChatDomFixtures.ownImageMessage(), "");

        int baseline = confirmer.readOwnImageMessageCount(page);
        assertEquals(1, baseline);

        // 模拟上传完成后聊天记录里插入新的本人图片消息。
        page.evaluate("(html) => document.querySelector('.chat-message .im-list')"
                + ".insertAdjacentHTML('beforeend', html)", ChatDomFixtures.ownImageMessage());

        ChatSendConfirmer.ImageResult result = confirmer.awaitImageConfirmed(page, baseline, 6, null);

        assertTrue(result.confirmed(), "新图片消息真实出现并稳定后应确认");
        assertFalse(result.uncertain());
    }
}
