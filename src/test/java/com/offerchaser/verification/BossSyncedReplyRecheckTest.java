package com.offerchaser.verification;

import com.offerchaser.application.ai.AiMatchResult;
import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.ai.AiOutcome;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.boss.Boss;
import com.offerchaser.worker.boss.BossConfig;
import com.offerchaser.worker.boss.BossRunContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-02：正常发送链路“预算预留前复检联系人状态”验收（不打开聊天、不发送）。
 * <p>
 * D2 投递状态同步可能在 AI 评估期间把 HR 已回复写回 contact_state；
 * {@link Boss#handleJobCard} 必须在每日预算预留前再次调用
 * {@link ContactStateService#isAutoContactBlocked(String)}：
 * 被 blocked 时不预留预算、不打开聊天、不初始化投递台账；只有复检通过才进入预算与发送。
 * 另用源码断言锁定“复检在 reserveFirstContact 之前”。
 */
class BossSyncedReplyRecheckTest {

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
        when(contactStateService.isAutoContactBlocked(anyString())).thenReturn(false);
        when(contactStateService.hasOtherJobConfirmedFirstContact(anyString(), anyString(), anyInt()))
                .thenReturn(false);
        when(aiService.getAiConfig()).thenReturn(new AiEntity());
        when(aiMatchService.evaluate(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AiMatchResult(AiOutcome.MATCH,
                        "您好，我有三年 Java 后端经验，希望进一步沟通。", "匹配分析", List.of(),
                        null, null, "deepseek-test", 100, 20, 50L));
        boss.prepare();
    }

    // ==================== 行为：复检拦截 / 放行 ====================

    @Test
    void syncedReplyAtRecheckBlocksBeforeBudgetReservationAndSend() throws Exception {
        // 第 1 次（规则后门禁）通过；第 2 次（预算前复检）时状态同步已把 HR 回复写回 REPLIED
        when(contactStateService.isAutoContactBlocked(anyString())).thenReturn(false, true);

        Object result = handleCard(detailResponse());

        assertEquals("SKIPPED", result.toString(), "预算前复检被 blocked 时应跳过该岗位");
        verify(contactStateService, times(2)).isAutoContactBlocked("uid:U1");
        verify(budgetService, never()).reserveFirstContact(anyString(), anyString(), anyString());
        verify(budgetService, never()).reserveFollowup(anyString(), anyString(), anyString());
        verify(ledgerService, never()).beginAttempt(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(listPage, never()).context();
        assertEquals(1, runContext.getSkippedCount(), "被复检拦截应记 skipped，不算投递");
        assertEquals(0, runContext.getDeliveredCount());
    }

    @Test
    void firstGateBlockAlsoSkipsWithoutReserve() throws Exception {
        when(contactStateService.isAutoContactBlocked(anyString())).thenReturn(true);

        Object result = handleCard(detailResponse());

        assertEquals("SKIPPED", result.toString());
        verify(contactStateService, times(1)).isAutoContactBlocked("uid:U1");
        verify(budgetService, never()).reserveFirstContact(anyString(), anyString(), anyString());
        verify(ledgerService, never()).beginAttempt(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(listPage, never()).context();
    }

    @Test
    void notBlockedReachesBudgetReservationAfterBothChecks() throws Exception {
        when(contactStateService.isAutoContactBlocked(anyString())).thenReturn(false, false);
        when(budgetService.reserveFirstContact(anyString(), anyString(), anyString()))
                .thenReturn(new BudgetService.Reservation("res-1", 1L, true,
                        null, 145, 10, 1, 0));
        // 台账初始化失败 → 发送前终止（本测试不打开浏览器、不发送）
        doThrow(new IllegalStateException("测试：台账不可用")).when(ledgerService)
                .beginAttempt(any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any());

        Object result = handleCard(detailResponse());

        assertEquals("SKIPPED", result.toString());
        verify(contactStateService, times(2)).isAutoContactBlocked("uid:U1");
        InOrder order = inOrder(contactStateService, budgetService);
        order.verify(contactStateService, times(2)).isAutoContactBlocked("uid:U1");
        order.verify(budgetService).reserveFirstContact(anyString(), anyString(), anyString());
        verify(budgetService).release(anyString(), anyString());
        assertEquals(0, runContext.getDeliveredCount(), "台账不可用时不得记投递成功");
        verify(listPage, never()).context();
    }

    // ==================== 源码顺序断言 ====================

    @Test
    void sourceLocksRecheckBeforeBudgetAndSend() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/offerchaser/worker/boss/Boss.java"));

        int methodStart = source.indexOf(
                "private CardAction handleJobCard(String keyword, int index, int total, Response detailResp, String cityCode)");
        int methodEnd = source.indexOf("private record DeliveryPlan");
        assertTrue(methodStart > 0 && methodEnd > methodStart, "必须存在单岗位处理链路 handleJobCard");
        String body = source.substring(methodStart, methodEnd);

        String recheckMarker = "发送前复检：联系人已回复/拒绝/接管/停止";
        int recheck = body.indexOf(recheckMarker);
        int reserve = body.indexOf("budgetService.reserveFirstContact(");
        int reserveFollowup = body.indexOf("budgetService.reserveFollowup(");
        int deliver = body.indexOf("deliverJob(snapshot, new DeliveryPlan(kind, message), reservation.reservationId())");
        assertTrue(recheck > 0, "handleJobCard 必须存在 D-02 预算前复检分支");
        assertTrue(reserve > recheck && reserveFollowup > recheck,
                "联系人复检必须在首次沟通/跟进预算预留之前");
        assertTrue(deliver > reserve, "预算预留必须发生在打开聊天/发送之前");
        assertFalse(body.contains("deliverJob(snapshot, new DeliveryPlan(kind, message), null)"),
                "不得绕过预算预留直接发送");
        // 复检分支的拦截动作：跳过 + 释放（不预留），且发生在预算前
        int skipReturn = body.indexOf("return CardAction.SKIPPED;", recheck);
        assertTrue(skipReturn > recheck && skipReturn < reserve,
                "复检拦截必须在预算预留前 return SKIPPED");
        // 第一次门禁（规则后）仍保留
        assertTrue(body.contains("联系人已回复/明确拒绝/人工接管/停止联系，禁止自动沟通"),
                "规则后联系人门禁不应被删除");
    }

    // ==================== 工具 ====================

    private Object handleCard(Response detailResp) throws Exception {
        Method method = Boss.class.getDeclaredMethod("handleJobCard",
                String.class, int.class, int.class, Response.class);
        method.setAccessible(true);
        return method.invoke(boss, "Java", 0, 10, detailResp);
    }

    private static Response detailResponse() {
        JSONObject jobInfo = new JSONObject()
                .put("encryptId", "ENC-1")
                .put("encryptUserId", "U1")
                .put("jobName", "Java 开发")
                .put("salaryDesc", "20-30K")
                .put("locationName", "上海")
                .put("experienceName", "3-5年")
                .put("degreeName", "本科")
                .put("postDescription", "负责后端服务开发，熟悉 Java 与 Spring。");
        JSONObject brand = new JSONObject().put("brandName", "示例科技");
        JSONObject bossInfo = new JSONObject()
                .put("name", "张女士")
                .put("title", "招聘专员")
                .put("activeTimeDesc", "今日活跃");
        JSONObject zpData = new JSONObject()
                .put("jobInfo", jobInfo)
                .put("brandComInfo", brand)
                .put("bossInfo", bossInfo);
        String body = new JSONObject().put("zpData", zpData).toString();
        Response response = mock(Response.class);
        when(response.text()).thenReturn(body);
        return response;
    }
}
