package com.offerchaser.verification;

import com.offerchaser.worker.boss.BossChatStatusReader;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2：BossChatStatusReader 真实浏览器本地 HTML fixture 验收（只读，不发送任何消息）。
 * <p>
 * 用 Playwright 打开本地 fixture（拦截 zhipin.com 聊天页），覆盖：已读 status-read、
 * 送达 status-delivery、未读红点、字段解析、空列表、缺字段、中文/英文标记、
 * 首条消息 SELF/HR 判断，以及“只读不发送”（页面上的发送按钮不会被点击）。
 * 仅在 {@code BOSS_CHAT_DOM_TEST=true} 时运行。
 */
@EnabledIfEnvironmentVariable(named = "BOSS_CHAT_DOM_TEST", matches = "true")
class BossChatStatusReaderDomTest {

    private static final String CHAT_URL = "https://www.zhipin.com/web/geek/chat";

    static Playwright playwright;
    static Browser browser;
    private BrowserContext context;
    private Page page;
    private final BossChatStatusReader reader = new BossChatStatusReader();

    @BeforeAll
    static void launch() {
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
    }

    @BeforeEach
    void setUp() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // ==================== 聊天列表解析 ====================

    @Test
    void readsReadDeliveredUnreadAndBasicFields() {
        fixture("""
                <ul>
                  <li role="listitem" id="row-read">
                    <div class="name-box"><span class="name-text">张HR</span><span>示例科技</span><span>Java 开发</span></div>
                    <span class="message-status status-read">已读</span>
                    <p class="last-msg-text">您好，方便发一份简历吗</p>
                    <span class="time">14:30</span>
                  </li>
                  <li role="listitem" id="row-delivered">
                    <div class="name-box"><span class="name-text">李HR</span><span>另一家公司</span><span>产品经理</span></div>
                    <span class="message-status status-delivery">送达</span>
                    <p class="last-msg-text">请问还招人吗</p>
                    <span class="time">昨天</span>
                  </li>
                  <li role="listitem" id="row-unread">
                    <div class="name-box"><span class="name-text">王HR</span><span>第三家公司</span><span>测试</span></div>
                    <span class="notice-badge">3</span>
                    <p class="last-msg-text">你好，有兴趣聊聊吗</p>
                    <span class="time">09:15</span>
                  </li>
                </ul>
                """);

        List<BossChatStatusReader.ChatItem> items = reader.readChatList(page);

        assertEquals(3, items.size());
        BossChatStatusReader.ChatItem read = items.get(0);
        assertEquals("张HR", read.hrName());
        assertEquals("示例科技", read.companyName());
        assertTrue(read.roleName().contains("Java"));
        assertTrue(read.hasOwnStatus());
        assertTrue(read.ownRead(), "status-read 应识别为已读");
        assertFalse(read.ownDelivered());
        assertEquals("14:30", read.chatTime());
        BossChatStatusReader.ChatItem delivered = items.get(1);
        assertTrue(delivered.ownDelivered(), "status-delivery 应识别为送达未读");
        assertFalse(delivered.ownRead());
        BossChatStatusReader.ChatItem unread = items.get(2);
        assertFalse(unread.hasOwnStatus(), "HR 最后消息没有 message-status");
        assertEquals(3, unread.unreadCount(), "notice-badge 数字应解析为未读数");
        assertEquals("你好，有兴趣聊聊吗", unread.lastMessage());
    }

    @Test
    void handlesClassAndChineseTextMarkers() {
        fixture("""
                <ul>
                  <li role="listitem">
                    <div class="name-box"><span class="name-text">甲方</span><span>公司A</span></div>
                    <span class="message-status status-read">Read</span>
                    <p class="last-msg-text">class 已读</p><span class="time">14:00</span>
                  </li>
                  <li role="listitem">
                    <div class="name-box"><span class="name-text">乙方</span><span>公司B</span></div>
                    <span class="message-status">已读</span>
                    <p class="last-msg-text">中文已读</p><span class="time">14:01</span>
                  </li>
                  <li role="listitem">
                    <div class="name-box"><span class="name-text">丙方</span><span>公司C</span></div>
                    <span class="message-status">送达</span>
                    <p class="last-msg-text">中文送达</p><span class="time">14:02</span>
                  </li>
                </ul>
                """);

        List<BossChatStatusReader.ChatItem> items = reader.readChatList(page);

        assertTrue(items.get(0).ownRead(), "class=status-read 即使文案是英文也应识别");
        assertTrue(items.get(1).ownRead(), "中文「已读」文案应识别");
        assertTrue(items.get(2).ownDelivered(), "中文「送达」文案应识别");
    }

    @Test
    void emptyListReturnsEmptyWithoutError() {
        fixture("<div>暂时没有聊天</div>");

        assertTrue(reader.readChatList(page).isEmpty());
    }

    @Test
    void missingFieldsDoNotThrow() {
        fixture("""
                <ul>
                  <li role="listitem"></li>
                  <li role="listitem"><span>只有文本</span></li>
                </ul>
                """);

        List<BossChatStatusReader.ChatItem> items = reader.readChatList(page);

        assertEquals(2, items.size());
        assertEquals("", items.get(0).hrName());
        assertEquals("", items.get(0).lastMessage());
        assertFalse(items.get(0).hasOwnStatus());
    }

    // ==================== 首条消息发起方 ====================

    @Test
    void detectsSelfInitiatedConversation() {
        fixtureWithChat("""
                <li class="message-item item-myself">您好，我对该岗位感兴趣</li>
                <li class="message-item item-friend">可以，发简历看看</li>
                """);
        List<BossChatStatusReader.ChatItem> items = reader.readChatList(page);

        assertEquals(BossChatStatusReader.Initiator.SELF,
                reader.detectInitiator(page, items.get(0)));
    }

    @Test
    void detectsHrInitiatedConversation() {
        fixtureWithChat("""
                <li class="message-item item-friend">你好，看到你的简历，有兴趣聊聊吗</li>
                <li class="message-item item-myself">有兴趣，想了解更多</li>
                """);
        List<BossChatStatusReader.ChatItem> items = reader.readChatList(page);

        assertEquals(BossChatStatusReader.Initiator.HR,
                reader.detectInitiator(page, items.get(0)));
    }

    // ==================== 只读，不发送 ====================

    @Test
    void readerNeverSendsMessages() {
        fixtureWithSendButtonAndChat();
        List<BossChatStatusReader.ChatItem> items = reader.readChatList(page);
        BossChatStatusReader.Initiator initiator = reader.detectInitiator(page, items.get(0));

        assertEquals(BossChatStatusReader.Initiator.HR, initiator);
        assertEquals(Boolean.FALSE, page.evaluate("window.__sent === true"),
                "读取状态不得点击发送按钮");
        assertEquals("", page.inputValue("#chat-input").trim(),
                "读取状态不得填写/修改输入框");
        // 发送按钮存在但从未被点击：__sendClicks 仍为 0
        assertEquals(0, ((Number) page.evaluate("window.__sendClicks || 0")).intValue());
    }

    // ==================== fixture ====================

    private void fixture(String listHtml) {
        String html = """
                <!doctype html><html><head><meta charset="utf-8"></head><body>
                %s
                </body></html>
                """.formatted(listHtml);
        context.route("https://www.zhipin.com/**", route -> route.fulfill(
                new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html)));
    }

    private void fixtureWithChat(String messagesHtml) {
        String html = """
                <!doctype html><html><head><meta charset="utf-8"></head><body>
                <ul>
                  <li role="listitem" id="row">
                    <div class="name-box"><span class="name-text">张HR</span><span>示例科技</span></div>
                    <p class="last-msg-text">你好</p><span class="time">14:30</span>
                  </li>
                </ul>
                <div class="chat-message"><ul class="im-list">%s</ul></div>
                <script>
                  document.querySelector('#row').addEventListener('click', () => {});
                </script>
                </body></html>
                """.formatted(messagesHtml);
        context.route("https://www.zhipin.com/**", route -> route.fulfill(
                new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html)));
    }

    private void fixtureWithSendButtonAndChat() {
        String html = """
                <!doctype html><html><head><meta charset="utf-8"></head><body>
                <ul>
                  <li role="listitem" id="row">
                    <div class="name-box"><span class="name-text">李HR</span><span>示例科技</span></div>
                    <p class="last-msg-text">你好，有兴趣聊聊吗</p><span class="time">14:30</span>
                  </li>
                </ul>
                <div class="chat-message"><ul class="im-list">
                  <li class="message-item item-friend">你好，有兴趣聊聊吗</li>
                </ul></div>
                <textarea id="chat-input"></textarea>
                <button id="send" onclick="window.__sent=true;window.__sendClicks=(window.__sendClicks||0)+1">发送</button>
                </body></html>
                """;
        context.route("https://www.zhipin.com/**", route -> route.fulfill(
                new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html)));
    }
}