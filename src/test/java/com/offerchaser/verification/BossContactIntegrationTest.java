package com.offerchaser.verification;

import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationEntry;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.persistence.FollowupPlanRepository;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.boss.Boss;
import com.offerchaser.worker.boss.BossConfig;
import com.offerchaser.worker.boss.BossRunContext;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sqlite.SQLiteDataSource;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.offerchaser.verification.VerificationSupport.dataSource;
import static com.offerchaser.verification.VerificationSupport.migrate;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B3 Boss 集成验收（反射 + mock，不启动浏览器）：
 * contactKey 语义、拦截状态 CONTACT_BLOCKED、同 HR 跨岗位去重 CONTACT_ALREADY_GREETED、
 * 跟进需批准 FOLLOWUP_NOT_APPROVED / 有批准允许跟进、图片重复发送 guard、beginAttempt 联系人字段。
 */
class BossContactIntegrationTest {

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

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

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

        Page listPage = mock(Page.class);
        Locator moreInfoButton = mock(Locator.class);
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
        config.setAllowRepeatChat(true);
        config.setSendImgResume(false);
        config.setEnableAI(false);
        config.setDebugger(false);
        config.setExpectedSalary(List.of());

        boss.setPage(listPage);
        boss.setConfig(config);
        runContext = new BossRunContext("b3-run");
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
        when(ledgerService.hasConfirmedFirstContact(anyString())).thenReturn(false);
        when(ledgerService.followupCount(anyString())).thenReturn(0);
        when(followupService.findApproved(anyString(), anyString(), anyString())).thenReturn(null);
        boss.prepare();
    }

    // ==================== 拦截 / 去重 ====================

    @Test
    void contactBlockedSkipsBeforeBudget() throws Exception {
        when(contactStateService.isAutoContactBlocked("uid:BOSS-1")).thenReturn(true);

        Object action = handleCard(responseWith(detailJson("ENC-1", "BOSS-1", "Java 开发",
                "负责后端服务开发，熟悉 Java 与 Spring。")));

        assertEquals("SKIPPED", String.valueOf(action));
        verify(budgetService, never()).reserveFirstContact(anyString(), anyString(), anyString());
        EvaluationEntry entry = capturedEvaluation();
        assertEquals("CONTACT_BLOCKED", entry.getRuleReasonCode(),
                "联系人已回复/接管/停止时必须以 CONTACT_BLOCKED 记录并跳过");
        assertEquals("REVIEW", entry.getRuleDecision());
        assertEquals("uid:BOSS-1", entry.getRuleEvidence());
    }

    @Test
    void sameHrOtherJobConfirmedSkipsDuplicateGreeting() throws Exception {
        when(contactStateService.hasOtherJobConfirmedFirstContact("uid:BOSS-1", "ENC-1", 30))
                .thenReturn(true);

        Object action = handleCard(responseWith(detailJson("ENC-1", "BOSS-1", "Java 开发",
                "负责后端服务开发，熟悉 Java 与 Spring。")));

        assertEquals("SKIPPED", String.valueOf(action));
        verify(budgetService, never()).reserveFirstContact(anyString(), anyString(), anyString());
        assertEquals("CONTACT_ALREADY_GREETED", capturedEvaluation().getRuleReasonCode(),
                "同一 HR 在其他岗位已确认沟通（30 天内）不应重复问候");
    }

    @Test
    void sameJobConfirmedDoesNotCrossJobDedupe() throws Exception {
        // 服务层已按 encryptId 区分同岗位/跨岗位；Boss 只依赖其布尔结果，应当继续首次沟通
        when(contactStateService.hasOtherJobConfirmedFirstContact("uid:BOSS-1", "ENC-1", 30))
                .thenReturn(false);
        when(budgetService.reserveFirstContact(anyString(), anyString(), anyString()))
                .thenReturn(deniedReservation("测试：预算关闭，验证已到达发送前一刻"));

        Object action = handleCard(responseWith(detailJson("ENC-1", "BOSS-1", "Java 开发",
                "负责后端服务开发，熟悉 Java 与 Spring。")));

        assertEquals("STOP_RUN", String.valueOf(action), "同岗位确认/超期不应被跨岗位去重拦截（会走到预算预留）");
        verify(budgetService).reserveFirstContact(anyString(), anyString(), anyString());
        for (EvaluationEntry entry : capturedEvaluations()) {
            if (entry.getRuleReasonCode() != null) {
                assertFalse("CONTACT_ALREADY_GREETED".equals(entry.getRuleReasonCode()));
            }
        }
    }

    @Test
    void nullContactKeyDoesNotMergeOrQueryContactState() throws Exception {
        // 详情没有可信 encryptUserId：contactKey=null，不做联系人合并/去重/跟进批准判断
        Object action = handleCard(responseWith(detailJson("ENC-NO-USER", null, "Java 开发",
                "负责后端服务开发，熟悉 Java 与 Spring。")));

        verifyNoInteractions(contactStateService);
        verifyNoInteractions(followupService);
        assertEquals("SKIPPED", String.valueOf(action), "无身份信息应被规则/身份校验跳过早于发送");
    }

    // ==================== 跟进批准 ====================

    @Test
    void followupWithoutApprovedPlanSkipsAndRecordsReason() throws Exception {
        when(ledgerService.hasConfirmedFirstContact("ENC-1")).thenReturn(true);
        when(followupService.findApproved("uid:BOSS-1", "ENC-1", FollowupService.PLAN_FOLLOWUP))
                .thenReturn(null);

        Object action = handleCard(responseWith(detailJson("ENC-1", "BOSS-1", "Java 开发",
                "负责后端服务开发，熟悉 Java 与 Spring。")));

        assertEquals("SKIPPED", String.valueOf(action));
        verify(budgetService, never()).reserveFollowup(anyString(), anyString(), anyString());
        assertTrue(capturedEvaluations().stream()
                        .anyMatch(entry -> "FOLLOWUP_NOT_APPROVED".equals(entry.getRuleReasonCode())),
                "无批准计划时必须记录 FOLLOWUP_NOT_APPROVED 并跳过自动跟进");
    }

    @Test
    void approvedPlanAllowsFollowupToReachBudgetGate() throws Exception {
        when(ledgerService.hasConfirmedFirstContact("ENC-1")).thenReturn(true);
        FollowupPlanRepository.FollowupPlan approved = new FollowupPlanRepository.FollowupPlan(
                1L, "plan-approved", "local", "boss", "ENC-1", "BOSS-1", "uid:BOSS-1",
                "Java 开发", "示例科技", "FOLLOWUP", "APPROVED", "用户批准", null, 1,
                "2026-11-01 10:00:00", "2026-11-02 10:00:00", null, null,
                "2026-11-01 10:00:00", "2026-11-02 10:00:00");
        when(followupService.findApproved("uid:BOSS-1", "ENC-1", FollowupService.PLAN_FOLLOWUP))
                .thenReturn(approved);
        when(budgetService.reserveFollowup(anyString(), anyString(), anyString()))
                .thenReturn(deniedReservation("测试：跟进预算关闭，验证已到达发送前一刻"));

        Object action = handleCard(responseWith(detailJson("ENC-1", "BOSS-1", "Java 开发",
                "负责后端服务开发，熟悉 Java 与 Spring。")));

        assertEquals("STOP_RUN", String.valueOf(action));
        verify(budgetService).reserveFollowup(anyString(), anyString(), anyString());
        verify(followupService).findApproved("uid:BOSS-1", "ENC-1", FollowupService.PLAN_FOLLOWUP);
        assertTrue(capturedEvaluations().stream()
                        .noneMatch(entry -> "FOLLOWUP_NOT_APPROVED".equals(entry.getRuleReasonCode())),
                "存在 APPROVED 计划时不应记录 FOLLOWUP_NOT_APPROVED");
    }

    @Test
    void bossSourceMarksFollowupExecutedOnConfirmedAndUnknown() throws Exception {
        String source = java.nio.file.Files.readString(
                Path.of("src/main/java/com/offerchaser/worker/boss/Boss.java"));
        int markExecutedCalls = source.split(
                "followupService\\.markExecuted\\(approvedFollowupPlan\\.planId\\(\\)", -1).length - 1;
        assertTrue(markExecutedCalls >= 2,
                "CONFIRMED 与 UNKNOWN 执行结果都应 markExecuted（实际调用点：" + markExecutedCalls + "）");
        assertTrue(source.contains("beginAttempt(") && source.contains("contactKey"),
                "Boss.beginAttempt 必须传入联系人键");
    }

    // ==================== 图片重复保护 ====================

    @Test
    void confirmedImageSkipsDuplicateImageSend() throws Exception {
        when(resumeImageService.getCurrentImagePath()).thenReturn(Optional.of(Path.of("dummy-resume.png")));
        when(contactStateService.hasConfirmedImage("uid:BOSS-1", "ENC-1")).thenReturn(true);

        Method method = Boss.class.getDeclaredMethod("sendImageResumeTracked",
                Page.class, long.class, String.class, String.class);
        method.setAccessible(true);
        Object status = method.invoke(boss, mock(Page.class), 1L, "uid:BOSS-1", "ENC-1");

        assertEquals("SKIPPED", String.valueOf(status), "同一联系人该岗位已有 CONFIRMED 图片必须跳过重复发送");
        verify(ledgerService).appendEvent(eq(1L), anyString(), eq("IMAGE_SKIPPED"), eq("DB"),
                org.mockito.ArgumentMatchers.contains("重复图片保护"));
        verify(contactStateService).hasConfirmedImage("uid:BOSS-1", "ENC-1");
    }

    @Test
    void imageSendWithoutConfiguredImageSkipsBeforeDuplicateGuard() throws Exception {
        when(resumeImageService.getCurrentImagePath()).thenReturn(Optional.empty());

        Method method = Boss.class.getDeclaredMethod("sendImageResumeTracked",
                Page.class, long.class, String.class, String.class);
        method.setAccessible(true);
        Object status = method.invoke(boss, mock(Page.class), 2L, "uid:BOSS-1", "ENC-1");

        assertEquals("SKIPPED", String.valueOf(status));
        verify(contactStateService, never()).hasConfirmedImage(anyString(), anyString());
        verify(ledgerService).appendEvent(eq(2L), anyString(), eq("IMAGE_SKIPPED"), eq("CONFIG"),
                anyString());
    }

    // ==================== beginAttempt 联系人字段 ====================

    @Test
    void beginAttemptWritesContactKeyAndParentAttemptId() throws Exception {
        SQLiteDataSource ds = dataSource(tempDir.resolve("ledger.db"));
        migrate(ds);
        DeliveryLedgerService ledger = new DeliveryLedgerService(ds, "local", "Asia/Shanghai");

        DeliveryLedgerService.AttemptStarted started = ledger.beginAttempt(
                "run-b3", com.offerchaser.application.persistence.DeliveryKind.FIRST_CONTACT,
                "ENC-1", "BOSS-1", "Java 开发", "示例科技", "https://example.invalid/job/1",
                "snap-1", "msg-1", "resume-1", null, "uid:BOSS-1", "attempt-parent-1");

        assertEquals("uid:BOSS-1", scalarString(ds,
                "SELECT contact_key FROM delivery_attempt WHERE attempt_id=?", started.attemptId()),
                "beginAttempt 13 参必须写入 contactKey（跨岗位去重/跟进依赖）");
        assertEquals("attempt-parent-1", scalarString(ds,
                "SELECT parent_attempt_id FROM delivery_attempt WHERE attempt_id=?", started.attemptId()),
                "图片补发尝试必须记录 parent_attempt_id");

        DeliveryLedgerService.AttemptStarted normal = ledger.beginAttempt(
                "run-b3", com.offerchaser.application.persistence.DeliveryKind.FOLLOWUP,
                "ENC-2", null, "后端开发", "示例科技", "https://example.invalid/job/2",
                "snap-2", "msg-2", "resume-2", null, null, null);
        assertNull(scalarString(ds,
                "SELECT contact_key FROM delivery_attempt WHERE attempt_id=?", normal.attemptId()),
                "无可信联系人键时 contact_key 应为 null");
    }

    // ==================== 工具 ====================

    private Object handleCard(Response detailResp) throws Exception {
        Method method = Boss.class.getDeclaredMethod("handleJobCard",
                String.class, int.class, int.class, Response.class);
        method.setAccessible(true);
        return method.invoke(boss, "Java", 0, 10, detailResp);
    }

    private static String detailJson(String encryptId, String encryptUserId, String jobName, String jd) {
        JSONObject jobInfo = new JSONObject()
                .put("encryptId", encryptId)
                .put("jobName", jobName)
                .put("salaryDesc", "20-30K")
                .put("locationName", "上海")
                .put("experienceName", "3-5年")
                .put("degreeName", "本科")
                .put("postDescription", jd);
        if (encryptUserId != null) {
            jobInfo.put("encryptUserId", encryptUserId);
        }
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

    private static BudgetService.Reservation deniedReservation(String reason) {
        return new BudgetService.Reservation("reservation-test", 1L, false, reason, 145, 0, 0, 0);
    }

    private EvaluationEntry capturedEvaluation() {
        ArgumentCaptor<EvaluationEntry> captor = ArgumentCaptor.forClass(EvaluationEntry.class);
        verify(evaluationRepository, atLeastOnce()).record(captor.capture());
        return captor.getAllValues().get(0);
    }

    private List<EvaluationEntry> capturedEvaluations() {
        ArgumentCaptor<EvaluationEntry> captor = ArgumentCaptor.forClass(EvaluationEntry.class);
        verify(evaluationRepository, atLeastOnce()).record(captor.capture());
        return captor.getAllValues();
    }

    @SuppressWarnings("unused")
    private static boolean noop(boolean value) {
        return value;
    }
}
