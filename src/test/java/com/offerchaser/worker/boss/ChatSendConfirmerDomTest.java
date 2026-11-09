package com.offerchaser.worker.boss;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatSendConfirmer 的纯 DOM 单元测试（阶段 A+：A-01 身份三态 / A-02 文字确认不假成功）。
 * <p>
 * 全部通过 Mockito mock {@link Page}/{@link Frame}/{@link Locator} 模拟页面探测结果，
 * 不启动浏览器、不访问网络、不发真实消息；轮询休眠注入空实现，测试不真实等待。
 * 探测脚本本身由生产代码负责，这里只按脚本特征返回预置的「DOM 状态 JSON」。
 * 同包下另有 {@code ChatSendConfirmerBrowserDomTest} 用真实 Playwright + HTML 夹具执行同一个脚本。
 */
class ChatSendConfirmerDomTest {

    private Page page;
    private Frame frame;
    private Locator sendButton;
    private ChatSendConfirmer confirmer;

    /** 聊天消息状态探测的预置返回队列（最后一条会重复使用）。 */
    private final Deque<String> chatStates = new ArrayDeque<>();
    /** 聊天身份探测的预置返回队列（最后一条会重复使用）。 */
    private final Deque<String> identityStates = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        page = Mockito.mock(Page.class);
        frame = Mockito.mock(Frame.class);
        sendButton = Mockito.mock(Locator.class);

        Mockito.when(page.frames()).thenReturn(List.of(frame));
        Mockito.when(page.isClosed()).thenReturn(false);
        Mockito.when(frame.isDetached()).thenReturn(false);
        // 按探测脚本特征分流：身份脚本返回 titleFound 字段，其余按聊天消息状态处理。
        Mockito.when(frame.evaluate(Mockito.anyString())).thenAnswer(invocation -> {
            String script = invocation.getArgument(0);
            return script.contains("titleFound")
                    ? nextState(identityStates)
                    : nextState(chatStates);
        });
        // 空休眠实现：确认逻辑照常轮询，但测试不真实等待。
        confirmer = new ChatSendConfirmer(seconds -> { });
    }

    private static String nextState(Deque<String> queue) {
        if (queue.isEmpty()) {
            // 未预置状态时按「没有聊天根节点」处理，避免测试之间互相影响。
            return "{\"rootFound\":false}";
        }
        if (queue.size() == 1) {
            return queue.peekFirst();
        }
        return queue.pollFirst();
    }

    private void givenChatStates(String... states) {
        chatStates.clear();
        chatStates.addAll(List.of(states));
    }

    private void givenIdentityStates(String... states) {
        identityStates.clear();
        identityStates.addAll(List.of(states));
    }

    // ------------------------------------------------------------------
    // JSON 夹具：字段与 ChatSendConfirmer.CHAT_STATE_SCRIPT / CHAT_IDENTITY_SCRIPT 对齐
    // ------------------------------------------------------------------

    /** 一条本人消息（用于构造 DOM 探测结果）。 */
    private record Msg(String text, boolean hasImage, boolean sending, boolean failed, String status) {
        static Msg text(String text) {
            return new Msg(text, false, false, false, "");
        }

        static Msg sendingText(String text) {
            return new Msg(text, false, true, false, "发送中");
        }

        static Msg failedText(String text) {
            return new Msg(text, false, false, true, "发送失败");
        }

        static Msg image(boolean sending, boolean failed, String status) {
            return new Msg("", true, sending, failed, status);
        }
    }

    /**
     * 构造一次完整的「本人消息列表探测」结果（所有字段齐全，可作为有效基线）。
     */
    private static String chatState(Msg... messages) {
        int textCount = 0;
        int imageCount = 0;
        JSONArray texts = new JSONArray();
        JSONArray tail = new JSONArray();
        Msg lastImage = null;
        for (Msg message : messages) {
            JSONObject item = new JSONObject();
            item.put("text", message.text());
            item.put("hasImage", message.hasImage());
            item.put("sending", message.sending());
            item.put("failed", message.failed());
            item.put("status", message.status());
            tail.put(item);
            if (message.hasImage()) {
                imageCount++;
                lastImage = message;
            } else if (!message.text().isEmpty()) {
                textCount++;
                texts.put(message.text());
            }
        }
        Msg last = messages.length == 0 ? null : messages[messages.length - 1];
        JSONObject state = new JSONObject();
        state.put("rootFound", true);
        state.put("ownCount", messages.length);
        state.put("textCount", textCount);
        state.put("imageCount", imageCount);
        state.put("lastFailed", last != null && last.failed());
        state.put("lastSending", last != null && last.sending());
        state.put("failed", lastImage != null && lastImage.failed());
        state.put("sending", lastImage != null && lastImage.sending());
        state.put("status", lastImage == null ? "" : lastImage.status());
        state.put("texts", texts);
        state.put("tail", tail);
        return state.toString();
    }

    /** 聊天根不存在/字段不完整（不可用作基线）的探测结果。 */
    private static String noChatRootState() {
        return "{\"rootFound\":false}";
    }

    /** 只缺字段的「假完整」结果：rootFound=true 但没有 texts/tail，不能当有效基线。 */
    private static String incompleteFieldsState() {
        return "{\"rootFound\":true,\"ownCount\":1,\"textCount\":1}";
    }

    /** 构造一次「聊天身份探测」结果。 */
    private static String identityState(boolean rootFound, boolean titleFound, String title) {
        JSONObject state = new JSONObject();
        state.put("rootFound", rootFound);
        state.put("titleFound", titleFound);
        state.put("title", title);
        return state.toString();
    }

    // ==================================================================
    // A-02 文字发送确认
    // ==================================================================

    @Test
    void textConfirmedWhenAppendedMatchedMessageIsReady() {
        givenChatStates(
                chatState(Msg.text("您好")),
                chatState(Msg.text("您好"), Msg.text(" 您好，我对该岗位很感兴趣 ")));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "  您好，我对该岗位很感兴趣  ", 3, null);

        assertTrue(result.confirmed(), "本次新增的匹配消息自身已发送完成时应确认成功");
        assertFalse(result.uncertain());
        assertFalse(result.failed());
        assertTrue(result.clickAttempted());
        assertEquals(ChatSendConfirmer.EVIDENCE_TEXT_CONFIRMED, result.evidenceLevel());
    }

    @Test
    void textFailedWhenAppendedMatchedMessageShowsFailureMarker() {
        givenChatStates(
                chatState(Msg.text("您好")),
                chatState(Msg.text("您好"), Msg.failedText("您好，我对该岗位很感兴趣")));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "您好，我对该岗位很感兴趣", 3, null);

        assertFalse(result.confirmed());
        assertFalse(result.uncertain());
        assertTrue(result.failed(), "本次新增消息出现失败标记时应判定失败");
        assertTrue(result.clickAttempted());
        assertEquals(ChatSendConfirmer.EVIDENCE_TEXT_FAILED, result.evidenceLevel());
    }

    /**
     * 复刻验收报告 A-02 假成功场景 2（AuditRegressionProbeTest 同款桩）：
     * 目标消息自身 sending=true，但另一条最后消息已就绪；不得用全局 lastSending 判成功。
     */
    @Test
    void matchedMessageStillSendingMustNotBeConfirmedByUnrelatedLastMessage() {
        givenChatStates(
                chatState(),
                chatState(Msg.sendingText("hello"), Msg.text("other")));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "hello", 2, () -> false);

        assertFalse(result.confirmed(), "必须核对匹配消息自身的 sending，而不是另一条最后消息");
        assertTrue(result.uncertain());
        assertTrue(result.clickAttempted());
        assertTrue(result.detail().contains("发送中"));
    }

    @Test
    void matchedMessageReadyAfterSendingConfirms() {
        givenChatStates(
                chatState(),
                chatState(Msg.sendingText("hello")),
                chatState(Msg.text("hello")));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "hello", 3, () -> false);

        assertTrue(result.confirmed(), "匹配消息自身发送完成后应确认");
        assertTrue(result.clickAttempted());
    }

    /**
     * 复刻验收报告 A-02 假成功场景 1（AuditRegressionProbeTest 同款桩）：
     * 点击前基线不可用，点击后历史聊天才加载出相同文字；不得判成功。
     */
    @Test
    void missingBaselineThenLateHistoryLoadMustNotConfirm() {
        givenChatStates(noChatRootState(),
                chatState(Msg.text("hello")));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "hello", 2, () -> false);

        assertFalse(result.confirmed(), "基线不可用时，迟到历史消息不能证明本次发送成功");
        assertTrue(result.uncertain());
    }

    /** 基线彻底不可用（根一直不存在）时，根本不允许点击。 */
    @Test
    void missingBaselineMustNotClickAtAll() {
        givenChatStates(noChatRootState());

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "hello", 3, () -> false);

        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
        assertFalse(result.clickAttempted(), "基线不可用时必须 clickAttempted=false");
        assertTrue(result.detail().contains("基线不可用"));
        Mockito.verify(sendButton, Mockito.never())
                .click(Mockito.any(Locator.ClickOptions.class));
    }

    /** rootFound=true 但字段不完整同样不算有效基线，不能点击。 */
    @Test
    void incompleteBaselineFieldsMustNotClick() {
        givenChatStates(incompleteFieldsState());

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "hello", 2, () -> false);

        assertFalse(result.clickAttempted());
        assertTrue(result.uncertain());
        Mockito.verify(sendButton, Mockito.never())
                .click(Mockito.any(Locator.ClickOptions.class));
    }

    /** 本人消息数增加来自历史消息前插/分页加载时，不是本次发送，不得确认。 */
    @Test
    void historyPrependMustNotConfirm() {
        givenChatStates(
                chatState(Msg.text("旧消息")),
                chatState(Msg.text("更早的历史消息"), Msg.text("旧消息")));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "旧消息", 2, () -> false);

        assertFalse(result.confirmed(), "历史前插不算本次新增，不能判成功");
        assertTrue(result.uncertain());
    }

    /** 历史里已有相同话术（重复话术）时，不能把旧消息当成本次发送。 */
    @Test
    void duplicatePhraseInHistoryMustNotConfirm() {
        givenChatStates(
                chatState(Msg.text("相同话术")),
                chatState(Msg.text("相同话术"), Msg.text("相同话术")));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "相同话术", 2, () -> false);

        assertFalse(result.confirmed(), "重复话术不能作为本次发送成功的证据");
        assertTrue(result.uncertain());
    }

    @Test
    void stopBeforeClickReturnsNotAttempted() {
        givenChatStates(chatState(Msg.text("您好")));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "您好", 3, () -> true);

        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
        assertFalse(result.clickAttempted(), "点击前 stop 必须 clickAttempted=false");
        Mockito.verify(sendButton, Mockito.never())
                .click(Mockito.any(Locator.ClickOptions.class));
    }

    @Test
    void stopAfterClickReturnsAttemptedUncertain() {
        givenChatStates(
                chatState(Msg.text("您好")),
                chatState(Msg.text("您好"), Msg.text("您好，我对该岗位很感兴趣")));
        // 发送前 3 次检查返回 false（允许点击）：1 初始检查 + 1 基线循环 + 1 点击前检查；
        // 第 4 次（第一次轮询）收到停止指令。
        java.util.concurrent.atomic.AtomicInteger stopCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<Boolean> stop = () -> stopCalls.incrementAndGet() > 3;

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "您好，我对该岗位很感兴趣", 5, stop);

        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
        assertTrue(result.clickAttempted(), "点击后 stop 必须 clickAttempted=true");
        assertTrue(result.detail().contains("取消"));
        Mockito.verify(sendButton, Mockito.times(1))
                .click(Mockito.any(Locator.ClickOptions.class));
    }

    @Test
    void clickThrowsReturnsAttemptedUncertain() {
        givenChatStates(chatState(Msg.text("您好")));
        Mockito.doThrow(new RuntimeException("发送按钮被页面移除"))
                .when(sendButton).click(Mockito.any(Locator.ClickOptions.class));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "您好", 3, null);

        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
        assertTrue(result.clickAttempted(), "点击抛异常也可能已触达外部动作，必须 clickAttempted=true");
        assertTrue(result.detail().contains("点击发送按钮异常"));
    }

    @Test
    void timeoutWithoutNewMessagesReturnsUncertain() {
        givenChatStates(chatState(Msg.text("您好")), chatState(Msg.text("您好")));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "您好", 2, null);

        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
        assertTrue(result.clickAttempted());
        assertTrue(result.detail().contains("超时"));
    }

    @Test
    void appendedMessageWithMismatchedTextReturnsUncertain() {
        givenChatStates(
                chatState(Msg.text("您好")),
                chatState(Msg.text("您好"), Msg.text("完全不相干的其他内容")));

        ChatSendConfirmer.TextResult result =
                confirmer.clickAndConfirmText(page, sendButton, "您好，我对该岗位很感兴趣", 2, null);

        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
        assertTrue(result.detail().contains("未匹配"));
    }

    @Test
    void staleFrameIsSkippedAndChatFrameStillConfirms() {
        Page localPage = Mockito.mock(Page.class);
        Frame staleFrame = Mockito.mock(Frame.class);
        Frame chatFrame = Mockito.mock(Frame.class);
        Locator localSendButton = Mockito.mock(Locator.class);

        Mockito.when(localPage.frames()).thenReturn(List.of(staleFrame, chatFrame));
        Mockito.when(localPage.isClosed()).thenReturn(false);
        Mockito.when(staleFrame.isDetached()).thenReturn(false);
        Mockito.when(staleFrame.evaluate(Mockito.anyString()))
                .thenThrow(new RuntimeException("Object doesn't exist: frame@stale"));
        Mockito.when(chatFrame.isDetached()).thenReturn(false);
        Mockito.when(chatFrame.evaluate(Mockito.anyString())).thenReturn(
                chatState(Msg.text("您好")),
                chatState(Msg.text("您好"), Msg.text("期望文本")));

        ChatSendConfirmer.TextResult result = new ChatSendConfirmer(seconds -> { })
                .clickAndConfirmText(localPage, localSendButton, "期望文本", 2, null);

        assertTrue(result.confirmed(), "失效 frame 应被跳过，继续使用有效 frame 探测");
    }

    // ==================================================================
    // A-01 身份核对（fail-closed 三态）
    // ==================================================================

    @Test
    void identityVerifiedWhenUrlContainsExpectedChatId() {
        Mockito.when(page.url()).thenReturn("https://www.zhipin.com/web/geek/chat?uid=ENC-USER-1");
        givenIdentityStates(identityState(true, true, "某些标题"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", "ENC-USER-1", true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome());
        assertEquals(ChatSendConfirmer.EVIDENCE_URL_CHAT_ID, result.evidenceLevel());
    }

    @Test
    void urlHitButChatRootMissingStillUnknown() {
        Mockito.when(page.url()).thenReturn("https://www.zhipin.com/web/geek/chat?uid=ENC-USER-1");
        givenIdentityStates(identityState(false, false, ""));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", "ENC-USER-1", true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.UNKNOWN, result.outcome(),
                "没有聊天根节点时即使 URL 命中也不能核验身份");
    }

    @Test
    void identityVerifiedWhenTitleContainsHrName() {
        givenIdentityStates(identityState(true, true, "目标公司 · 王先生 · 招聘者 BOSS直聘"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome());
        assertEquals(ChatSendConfirmer.EVIDENCE_TITLE_HR, result.evidenceLevel());
    }

    @Test
    void identityVerifiedWhenTitleContainsCompanyAndJob() {
        givenIdentityStates(identityState(true, true, "目标公司 · Java工程师 BOSS直聘"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", null, null, false);

        assertEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome());
        assertEquals(ChatSendConfirmer.EVIDENCE_TITLE_COMPANY_JOB, result.evidenceLevel());
    }

    /**
     * 复刻验收报告 A-01 假通过场景（AuditRegressionProbeTest 同款桩）：
     * 目标为「目标公司 / Java工程师 / 王先生」，实际标题为「另一公司 李女士 Java工程师」。
     * 岗位通用名不能单独证明联系人身份，且标题出现了其他联系人（李女士）→ MISMATCH。
     */
    @Test
    void genericJobTitleMustNotOverrideConflictingRecruiterIdentity() {
        givenIdentityStates(identityState(true, true, "另一公司 李女士 Java工程师"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertNotEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome(),
                "同名岗位不是联系人唯一身份，不能覆盖公司及HR冲突");
        assertEquals(ChatSendConfirmer.IdentityOutcome.MISMATCH, result.outcome());
    }

    /** A-01 补充裁决：同公司不同 HR（公司+岗位都命中，但标题里是李女士，目标 HR 王先生）→ 非 VERIFIED。 */
    @Test
    void identityMismatchWhenSameCompanyAndJobButDifferentHr() {
        givenIdentityStates(identityState(true, true, "示例科技 李女士 Java工程师"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "示例科技", "Java工程师", "王先生", null, true);

        assertNotEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome(),
                "同公司不同 HR 不得放行");
        assertEquals(ChatSendConfirmer.IdentityOutcome.MISMATCH, result.outcome());
        assertEquals(ChatSendConfirmer.EVIDENCE_TITLE_CONFLICT, result.evidenceLevel());
        assertTrue(result.detail().contains("李女士"));
    }

    /** A-01 补充裁决：同公司同 HR（标题命中目标 HR 姓名）→ VERIFIED。 */
    @Test
    void identityVerifiedWhenSameCompanyAndSameHr() {
        givenIdentityStates(identityState(true, true, "示例科技 王先生 Java工程师"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "示例科技", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome());
        assertEquals(ChatSendConfirmer.EVIDENCE_TITLE_HR, result.evidenceLevel());
    }

    /** A-01 补充裁决：提供了 hrName 但标题只有公司+岗位、没有 HR/联系人形态 → UNKNOWN（不得 VERIFIED）。 */
    @Test
    void identityUnknownWhenHrProvidedMissingAndTitleHasNoContactForm() {
        givenIdentityStates(identityState(true, true, "示例科技 Java工程师"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "示例科技", "Java工程师", "王先生", null, true);

        assertNotEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, result.outcome(),
                "提供了 HR 姓名但标题未命中、且无其他联系人证据时不得 VERIFIED");
        assertEquals(ChatSendConfirmer.IdentityOutcome.UNKNOWN, result.outcome());
    }

    /** A-01 补充裁决：提供了 hrName、标题未命中且出现其他称谓 → MISMATCH。 */
    @Test
    void identityMismatchWhenHrNameMissingAndOtherContactFormAppears() {
        givenIdentityStates(identityState(true, true, "示例科技 李女士 · 招聘者"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "示例科技", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.MISMATCH, result.outcome());
        assertTrue(result.detail().contains("李女士"));
    }

    /** A-01：提供了 hrName、标题未命中 HR 且公司/岗位也不命中、无联系人形态 → 冲突 MISMATCH。 */
    @Test
    void identityMismatchWhenHrProvidedAndNothingMatchesWithoutContactForm() {
        givenIdentityStates(identityState(true, true, "另一公司 · 招聘中"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.MISMATCH, result.outcome());
    }

    /** 同公司不同 HR 的另一种表象：标题只命中公司名，但出现了其他联系人称谓 → MISMATCH。 */
    @Test
    void identityMismatchWhenSameCompanyButDifferentHr() {
        givenIdentityStates(identityState(true, true, "目标公司 李女士"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.MISMATCH, result.outcome(),
                "标题出现其他联系人（李女士）时必须 MISMATCH，不能因公司名命中而放行");
    }

    @Test
    void identityMismatchWhenNoIdentityFieldMatches() {
        givenIdentityStates(identityState(true, true, "另一公司 · 李女士"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.MISMATCH, result.outcome());
        assertTrue(result.detail().contains("李女士"), "冲突 detail 应记录标题中的其他联系人");
    }

    @Test
    void identityUnknownWhenChatRootMissing() {
        givenIdentityStates(identityState(false, false, ""));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.UNKNOWN, result.outcome());
        assertEquals(ChatSendConfirmer.EVIDENCE_NONE, result.evidenceLevel());
    }

    @Test
    void identityUnknownWhenChatTitleMissing() {
        givenIdentityStates(identityState(true, false, ""));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, false);

        assertEquals(ChatSendConfirmer.IdentityOutcome.UNKNOWN, result.outcome());
        assertTrue(result.detail().contains("标题为空"));
    }

    /** iframe 切换：当前 frame 没有聊天根、另一个 frame 已 detached，只能是 UNKNOWN。 */
    @Test
    void identityUnknownWhenIframeSwitchLostChatRoot() {
        Page localPage = Mockito.mock(Page.class);
        Frame currentFrame = Mockito.mock(Frame.class);
        Frame detachedFrame = Mockito.mock(Frame.class);
        Mockito.when(localPage.frames()).thenReturn(List.of(currentFrame, detachedFrame));
        Mockito.when(currentFrame.isDetached()).thenReturn(false);
        Mockito.when(currentFrame.evaluate(Mockito.anyString()))
                .thenReturn(identityState(false, true, "目标公司 王先生"));
        Mockito.when(detachedFrame.isDetached()).thenReturn(true);

        ChatSendConfirmer.IdentityResult result = new ChatSendConfirmer(seconds -> { })
                .verifyChatIdentity(localPage, "目标公司", "Java工程师", "王先生", null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.UNKNOWN, result.outcome(),
                "iframe 切换后当前 frame 没有聊天根时不能核验身份");
    }

    @Test
    void identityUnknownWhenAllIdentityFieldsBlank() {
        givenIdentityStates(identityState(true, true, "目标公司 · 王先生"));

        ChatSendConfirmer.IdentityResult result =
                confirmer.verifyChatIdentity(page, null, " ", null, null, true);

        assertEquals(ChatSendConfirmer.IdentityOutcome.UNKNOWN, result.outcome());
    }

    @Test
    void strictOnlyAffectsDetailNotOutcome() {
        givenIdentityStates(identityState(true, true, "目标公司 · 王先生"));
        ChatSendConfirmer.IdentityResult strictResult =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);
        ChatSendConfirmer.IdentityResult looseResult =
                confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, false);

        assertEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, strictResult.outcome());
        assertEquals(ChatSendConfirmer.IdentityOutcome.VERIFIED, looseResult.outcome());
        assertTrue(strictResult.detail().contains("strict=true"));
        assertTrue(looseResult.detail().contains("strict=false"));
    }

    /** A-01 要求：禁止把 document.title 当作身份标题来源。 */
    @Test
    void identityProbeScriptMustNotReadDocumentTitle() {
        givenIdentityStates(identityState(true, true, "目标公司 · 王先生"));

        confirmer.verifyChatIdentity(page, "目标公司", "Java工程师", "王先生", null, true);

        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(frame, Mockito.atLeastOnce()).evaluate(scriptCaptor.capture());
        List<String> identityScripts = new ArrayList<>();
        for (String script : scriptCaptor.getAllValues()) {
            if (script.contains("titleFound")) {
                identityScripts.add(script);
            }
        }
        assertFalse(identityScripts.isEmpty(), "应至少执行过一次身份探测脚本");
        for (String script : identityScripts) {
            assertFalse(script.contains("document.title"), "身份标题不得使用 document.title 兜底");
        }
    }

    // ==================================================================
    // A-02 图片确认（基线未知不得当作 0）
    // ==================================================================

    @Test
    void readOwnImageMessageCountReturnsCountWhenBaselineValid() {
        givenChatStates(chatState(Msg.image(false, false, ""), Msg.image(false, false, "")));

        assertEquals(2, confirmer.readOwnImageMessageCount(page));
    }

    @Test
    void readOwnImageMessageCountReturnsMinusOneWhenRootMissing() {
        givenChatStates(noChatRootState());

        assertEquals(-1, confirmer.readOwnImageMessageCount(page),
                "聊天根不存在时必须返回 -1 表示未知基线");
    }

    @Test
    void awaitImageConfirmedWithUnknownBaselineReturnsUncertainWithoutPolling() {
        givenChatStates(chatState(Msg.image(false, false, "")));

        ChatSendConfirmer.ImageResult result = confirmer.awaitImageConfirmed(page, -1, 5, null);

        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
        assertTrue(result.detail().contains("基线不可用"));
    }

    @Test
    void imageSendConfirmedAfterStableNewImage() {
        // 基线 1 张，点击后 2 张且不在发送中，连续三轮稳定后确认。
        givenChatStates(chatState(Msg.image(false, false, ""), Msg.image(false, false, "")));

        ChatSendConfirmer.ImageResult result = confirmer.awaitImageConfirmed(page, 1, 5, null);

        assertTrue(result.confirmed());
        assertFalse(result.uncertain());
        assertEquals(ChatSendConfirmer.EVIDENCE_IMAGE_CONFIRMED, result.evidenceLevel());
    }

    @Test
    void imageSendFailedWhenFailureMarkerAppears() {
        givenChatStates(chatState(Msg.image(false, false, ""),
                Msg.image(false, true, "上传失败")));

        ChatSendConfirmer.ImageResult result = confirmer.awaitImageConfirmed(page, 1, 5, null);

        assertFalse(result.confirmed());
        assertFalse(result.uncertain(), "失败标记是明确失败，不应再记为不确定");
        assertEquals(ChatSendConfirmer.EVIDENCE_IMAGE_FAILED, result.evidenceLevel());
    }

    @Test
    void imageSendTimesOutWhenNewImageNeverAppears() {
        givenChatStates(chatState(Msg.image(false, false, "")));

        ChatSendConfirmer.ImageResult result = confirmer.awaitImageConfirmed(page, 1, 2, null);

        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
        assertTrue(result.detail().contains("超时"));
    }

    @Test
    void imageSendKeepsWaitingWhileUploadingThenUncertain() {
        givenChatStates(chatState(Msg.image(false, false, ""), Msg.image(true, false, "上传中")));

        ChatSendConfirmer.ImageResult result = confirmer.awaitImageConfirmed(page, 1, 2, null);

        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
        assertTrue(result.detail().contains("上传"));
    }

    @Test
    void imageSendReturnsUncertainWhenStopRequested() {
        givenChatStates(chatState(Msg.image(false, false, ""), Msg.image(false, false, "")));

        ChatSendConfirmer.ImageResult result = confirmer.awaitImageConfirmed(page, 1, 5, () -> true);

        assertFalse(result.confirmed());
        assertTrue(result.uncertain());
        assertTrue(result.detail().contains("取消"));
    }
}
