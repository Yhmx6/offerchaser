package com.offerchaser.worker.boss;

import com.offerchaser.application.ai.AiMatchResult;
import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.ai.AiOutcome;
import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.persistence.TextSendStatus;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 A 核心验收测试：不匹配/身份不明/AI 异常的岗位必须在"打开聊天之前"被拦下。
 * <p>
 * 直接调用 Boss.handleJobCard（反射），用 mock 的 Playwright Page 与全部服务依赖验证：
 * 规则拒绝、身份错配、AI 错误、调试模式、预算不足时，预算预留与页面新开次数均为零。
 */
class BossFlowDecisionTest {

    private BossService bossService;
    private AiService aiService;
    private AiMatchService aiMatchService;
    private BossResumeImageService resumeImageService;
    private BudgetService budgetService;
    private DeliveryLedgerService ledgerService;
    private EvaluationRepository evaluationRepository;
    private ContactStateService contactStateService;
    private FollowupService followupService;

    private Boss boss;
    private BossConfig config;
    private BossRunContext runContext;
    private Page listPage;
    private Locator moreInfoButton;

    @BeforeEach
    void setUp() {
        bossService = mock(BossService.class);
        aiService = mock(AiService.class);
        aiMatchService = mock(AiMatchService.class);
        resumeImageService = mock(BossResumeImageService.class);
        budgetService = mock(BudgetService.class);
        ledgerService = mock(DeliveryLedgerService.class);
        evaluationRepository = mock(EvaluationRepository.class);
        contactStateService = mock(ContactStateService.class);
        followupService = mock(FollowupService.class);

        boss = new Boss(bossService, aiService, aiMatchService, resumeImageService,
                budgetService, ledgerService, evaluationRepository, contactStateService, followupService);

        listPage = mock(Page.class);
        moreInfoButton = mock(Locator.class);
        when(listPage.locator("a.more-job-btn")).thenReturn(moreInfoButton);
        when(moreInfoButton.count()).thenReturn(1);
        when(moreInfoButton.first()).thenReturn(moreInfoButton);
        when(moreInfoButton.getAttribute("href")).thenReturn("/job_detail/ENC-1.html");

        config = new BossConfig();
        config.setKeywords(List.of("Java"));
        config.setCityCode(List.of("101020100"));
        config.setSayHi("您好，我有相关经验，希望进一步沟通。");
        config.setRepeatChatMessages("请问贵公司还招人吗？");
        config.setDeadStatus(List.of("本周活跃"));
        config.setFilterDeadHR(false);
        config.setAllowRepeatChat(false);
        config.setSendImgResume(false);
        config.setEnableAI(true);
        config.setDebugger(false);
        config.setExpectedSalary(List.of());

        boss.setPage(listPage);
        boss.setConfig(config);
        runContext = new BossRunContext("test-run");
        runContext.markRunning();
        boss.setRunContext(runContext);
        boss.setProgressCallback((message, current, total) -> {
        });

        when(bossService.getBlackCompanies()).thenReturn(Set.of());
        when(bossService.getBlackRecruiters()).thenReturn(Set.of());
        when(bossService.getBlackJobs()).thenReturn(Set.of());
        boss.prepare();
    }

    private static String detailJson(String encryptId, String jobName, String degree, String jd) {
        JSONObject jobInfo = new JSONObject()
                .put("encryptId", encryptId)
                .put("encryptUserId", "BOSS-1")
                .put("jobName", jobName)
                .put("salaryDesc", "20-30K")
                .put("locationName", "上海")
                .put("experienceName", "3-5年")
                .put("degreeName", degree)
                .put("postDescription", jd);
        JSONObject brand = new JSONObject().put("brandName", "示例科技");
        JSONObject bossInfo = new JSONObject()
                .put("name", "张女士")
                .put("title", "招聘专员")
                .put("activeTimeDesc", "今日活跃");
        JSONObject zpData = new JSONObject()
                .put("jobInfo", jobInfo)
                .put("brandComInfo", brand)
                .put("bossInfo", bossInfo);
        return new JSONObject().put("zpData", zpData).toString();
    }

    private static Response responseWith(String body) {
        Response response = mock(Response.class);
        when(response.text()).thenReturn(body);
        return response;
    }

    private Object handleCard(Response detailResp) throws Exception {
        Method method = Boss.class.getDeclaredMethod("handleJobCard",
                String.class, int.class, int.class, Response.class);
        method.setAccessible(true);
        return method.invoke(boss, "Java", 0, 10, detailResp);
    }

    private static AiMatchResult matchResult(String message) {
        return new AiMatchResult(AiOutcome.MATCH, message, "匹配分析", List.of(),
                null, null, "deepseek-test", 100, 20, 50L);
    }

    @Test
    void missingDetailResponseNeverReservesBudgetOrOpensChat() throws Exception {
        Object result = handleCard(null);
        assertEquals("SKIPPED", result.toString());
        verify(budgetService, never()).reserveFirstContact(anyString(), any(), any());
        verify(budgetService, never()).reserveFollowup(anyString(), any(), any());
        verify(listPage, never()).context();
        verify(evaluationRepository).record(any());
    }

    @Test
    void identityMismatchBlocksBeforeUpsertAndChat() throws Exception {
        // 卡片链接是 ENC-1，详情响应却是另一个岗位
        Response detail = responseWith(detailJson("ENC-OTHER", "Java 开发", "本科",
                "负责后端服务开发，熟悉 Java 与 Spring。"));
        Object result = handleCard(detail);
        assertEquals("SKIPPED", result.toString());
        verify(bossService, never()).upsertBossJob(any());
        verify(budgetService, never()).reserveFirstContact(anyString(), any(), any());
        verify(listPage, never()).context();
        verify(evaluationRepository).record(any());
    }

    @Test
    void ruleRejectNeverReservesBudgetOrOpensChat() throws Exception {
        when(bossService.getBlackJobs()).thenReturn(Set.of("销售"));
        boss.prepare();
        Response detail = responseWith(detailJson("ENC-1", "销售专员", "本科",
                "负责客户开发与产品销售，完成销售目标。"));
        Object result = handleCard(detail);
        assertEquals("SKIPPED", result.toString());
        verify(budgetService, never()).reserveFirstContact(anyString(), any(), any());
        verify(listPage, never()).context();
        verify(bossService).updateDeliveryStatus(eq("ENC-1"), eq("BOSS-1"), eq("已过滤"));
    }

    @Test
    void aiErrorIsSkippedWithoutSendingRawOutputOrFallbackMessage() throws Exception {
        when(aiService.getAiConfig()).thenReturn(new AiEntity());
        when(aiMatchService.evaluate(any(), any(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(AiMatchResult.error("AI_OUTPUT_INVALID", "模型返回了纯文本而非 JSON"));
        Response detail = responseWith(detailJson("ENC-1", "Java 开发", "本科",
                "负责后端服务开发，熟悉 Java 与 Spring。"));
        Object result = handleCard(detail);
        assertEquals("SKIPPED", result.toString());
        verify(budgetService, never()).reserveFirstContact(anyString(), any(), any());
        verify(listPage, never()).context();
        verify(evaluationRepository).record(any());
    }

    @Test
    void budgetDeniedStopsRunBeforeOpeningChat() throws Exception {
        when(aiService.getAiConfig()).thenReturn(new AiEntity());
        when(aiMatchService.evaluate(any(), any(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(matchResult("您好，我有三年 Java 后端经验，希望进一步沟通。"));
        when(budgetService.reserveFirstContact(anyString(), anyString(), anyString()))
                .thenReturn(new BudgetService.Reservation("r-denied", 1L, false,
                        "今日首次沟通预算已用尽", 145, 145, 0, 0));
        Response detail = responseWith(detailJson("ENC-1", "Java 开发", "本科",
                "负责后端服务开发，熟悉 Java 与 Spring。"));
        Object result = handleCard(detail);
        assertEquals("STOP_RUN", result.toString());
        assertTrue(runContext.isStopRequested(), "预算用尽后应通过取消通道停止整轮任务");
        verify(ledgerService, never()).settleAttempt(any());
        verify(listPage, never()).context();
    }

    @Test
    void debuggerModeEvaluatesButNeverReservesBudget() throws Exception {
        config.setDebugger(true);
        when(aiService.getAiConfig()).thenReturn(new AiEntity());
        when(aiMatchService.evaluate(any(), any(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(matchResult("您好，我有三年 Java 后端经验，希望进一步沟通。"));
        Response detail = responseWith(detailJson("ENC-1", "Java 开发", "本科",
                "负责后端服务开发，熟悉 Java 与 Spring。"));
        Object result = handleCard(detail);
        assertEquals("SKIPPED", result.toString());
        verify(budgetService, never()).reserveFirstContact(anyString(), any(), any());
        verify(listPage, never()).context();
    }

    @Test
    void reverseIdentityCheckRejectsWhenCardLinkUnavailable() throws Exception {
        when(moreInfoButton.getAttribute("href")).thenReturn(null);
        Response detail = responseWith(detailJson("ENC-1", "Java 开发", "本科",
                "负责后端服务开发，熟悉 Java 与 Spring。"));
        Object result = handleCard(detail);
        assertEquals("SKIPPED", result.toString());
        verify(budgetService, never()).reserveFirstContact(anyString(), any(), any());
        verify(listPage, never()).context();
    }

    private void stubBudgetAndAttempt() {
        when(budgetService.reserveFirstContact(anyString(), anyString(), anyString()))
                .thenReturn(new BudgetService.Reservation("res-1", 1L, true,
                        null, 145, 10, 1, 0));
        when(ledgerService.beginAttempt(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), any(), any()))
                .thenReturn(new DeliveryLedgerService.AttemptStarted(1L, "attempt-1"));
        when(ledgerService.settleAttempt(any())).thenReturn(true);
    }

    @Test
    void chatButtonMissingReleasesReservedBudget() throws Exception {
        when(aiService.getAiConfig()).thenReturn(new AiEntity());
        when(aiMatchService.evaluate(any(), any(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(matchResult("您好，我有三年 Java 后端经验，希望进一步沟通。"));
        stubBudgetAndAttempt();

        BrowserContext context = mock(BrowserContext.class);
        Page detailPage = mock(Page.class);
        Locator emptyChatButton = mock(Locator.class);
        when(listPage.context()).thenReturn(context);
        when(context.newPage()).thenReturn(detailPage);
        when(detailPage.locator(anyString())).thenReturn(emptyChatButton);
        when(emptyChatButton.count()).thenReturn(0);

        Response detail = responseWith(detailJson("ENC-1", "Java 开发", "本科",
                "负责后端服务开发，熟悉 Java 与 Spring。"));
        Object result;
        try (MockedStatic<PlaywrightUtil> ignored = mockStatic(PlaywrightUtil.class)) {
            result = handleCard(detail);
        }
        assertEquals("SKIPPED", result.toString());
        // 从未点击沟通入口：预算应释放，attempt 结算为 NOT_ATTEMPTED
        verify(ledgerService).settleAttempt(argThat(request ->
                request.budgetAction() == DeliveryLedgerService.BudgetSettlement.RELEASE
                        && request.textStatus() == TextSendStatus.NOT_ATTEMPTED));
    }

    @Test
    void chatClickUnknownKeepsBudgetOccupied() throws Exception {
        when(aiService.getAiConfig()).thenReturn(new AiEntity());
        when(aiMatchService.evaluate(any(), any(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(matchResult("您好，我有三年 Java 后端经验，希望进一步沟通。"));
        stubBudgetAndAttempt();

        BrowserContext context = mock(BrowserContext.class);
        Page detailPage = mock(Page.class);
        Locator chatButton = mock(Locator.class);
        when(listPage.context()).thenReturn(context);
        when(context.newPage()).thenReturn(detailPage);
        when(detailPage.locator(anyString())).thenReturn(chatButton);
        when(chatButton.count()).thenReturn(1);
        when(chatButton.first()).thenReturn(chatButton);
        when(chatButton.textContent()).thenReturn("立即沟通");

        Response detail = responseWith(detailJson("ENC-1", "Java 开发", "本科",
                "负责后端服务开发，熟悉 Java 与 Spring。"));
        Object result;
        try (MockedStatic<PlaywrightUtil> ignored = mockStatic(PlaywrightUtil.class);
             MockedConstruction<BossChatNavigator> navigators = mockConstruction(BossChatNavigator.class,
                     (navigator, ctx) -> when(navigator.open(any(), anyBoolean(), anyBoolean(), any(), anyLong()))
                             .thenReturn(BossChatNavigator.OpenResult.clickedUnknown("点击后聊天页未加载")))) {
            result = handleCard(detail);
        }
        assertEquals("SKIPPED", result.toString());
        // A-03：已经点击沟通入口但页面未加载出来 → 不能按"未发生外部动作"释放，必须按未知占额
        verify(ledgerService).settleAttempt(argThat(request ->
                request.budgetAction() == DeliveryLedgerService.BudgetSettlement.UNKNOWN
                        && request.textStatus() != TextSendStatus.CONFIRMED));
    }

    @Test
    void unverifiableChatIdentityDoesNotSendText() throws Exception {
        when(aiService.getAiConfig()).thenReturn(new AiEntity());
        when(aiMatchService.evaluate(any(), any(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(matchResult("您好，我有三年 Java 后端经验，希望进一步沟通。"));
        stubBudgetAndAttempt();

        BrowserContext context = mock(BrowserContext.class);
        Page detailPage = mock(Page.class);
        Page chatPage = mock(Page.class);
        Locator chatButton = mock(Locator.class);
        Locator chatInput = mock(Locator.class);
        when(listPage.context()).thenReturn(context);
        when(context.newPage()).thenReturn(detailPage);
        when(detailPage.locator(anyString())).thenReturn(chatButton);
        when(chatButton.count()).thenReturn(1);
        when(chatButton.first()).thenReturn(chatButton);
        when(chatButton.textContent()).thenReturn("立即沟通");
        // 聊天根不存在 → 身份核对 UNKNOWN → fail-closed，不得发送
        when(chatPage.frames()).thenReturn(List.of());

        Response detail = responseWith(detailJson("ENC-1", "Java 开发", "本科",
                "负责后端服务开发，熟悉 Java 与 Spring。"));
        Object result;
        try (MockedStatic<PlaywrightUtil> ignored = mockStatic(PlaywrightUtil.class);
             MockedConstruction<BossChatNavigator> navigators = mockConstruction(BossChatNavigator.class,
                     (navigator, ctx) -> when(navigator.open(any(), anyBoolean(), anyBoolean(), any(), anyLong()))
                             .thenReturn(BossChatNavigator.OpenResult.opened(
                                     new BossChatNavigator.Chat(chatPage, chatInput, false), "测试夹具")))) {
            result = handleCard(detail);
        }
        assertEquals("SKIPPED", result.toString());
        // 身份无法核对必须拒绝发送：按未知占额，绝不确认成功
        verify(ledgerService).settleAttempt(argThat(request ->
                request.budgetAction() == DeliveryLedgerService.BudgetSettlement.UNKNOWN
                        && request.textStatus() != TextSendStatus.CONFIRMED));
        verify(ledgerService, never()).recordFollowup(any(), any(), any());
    }
}
