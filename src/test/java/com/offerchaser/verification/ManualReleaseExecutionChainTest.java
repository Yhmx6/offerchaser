package com.offerchaser.verification;

import com.offerchaser.application.ai.AiMatchResult;
import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.ai.AiOutcome;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.delivery.JobSnapshot;
import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.entity.BossJobDataEntity;
import com.offerchaser.application.manual.ManualReleaseService;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryKind;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.persistence.ManualReleaseRepository;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.boss.Boss;
import com.offerchaser.worker.boss.BossConfig;
import com.offerchaser.worker.boss.BossRunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D3-B 人工放行执行链路验收（重点是<b>不真实发消息</b>）：
 * <ul>
 *   <li>真实 {@link Boss#execute()} + 真实 {@link ManualReleaseService}（临时 SQLite），
 *       其余可产生外部动作的依赖全部 Mockito mock；配置城市列表为空，只跑人工放行队列；</li>
 *   <li>验证放行记录被消费、AI 必须重新评估且只有 MATCH 才继续、话术必须通过校验、
 *       预算必须预留、岗位快照必须来自 boss_data、身份/联系人安全检查不通过即失败；</li>
 *   <li>台账初始化失败时走失败分支（绝不盲发）；预算不足时保留 APPROVED 供下一轮重试；</li>
 *   <li>源码断言锁定安全链路顺序与关键调用（AI MATCH → 话术校验 → 预算 → 身份/确认 → deliverJob(...true)）。</li>
 * </ul>
 * 本测试全程不启动浏览器、不调用真实 AI、不发送任何消息。
 */
class ManualReleaseExecutionChainTest {

    private static final String MESSAGE = "您好，我的经历与岗位要求比较匹配，希望进一步沟通。";
    private static final int MAX_MESSAGE_LENGTH = 120;

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private ManualReleaseRepository repository;
    private ManualReleaseService service;

    private BossService bossService;
    private AiService aiService;
    private AiMatchService aiMatchService;
    private BossResumeImageService bossResumeImageService;
    private BudgetService budgetService;
    private DeliveryLedgerService deliveryLedgerService;
    private EvaluationRepository evaluationRepository;
    private ContactStateService contactStateService;
    private FollowupService followupService;
    private Boss boss;
    private BossRunContext runContext;
    private final List<String> progressMessages = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        repository = new ManualReleaseRepository(dataSource);
        service = new ManualReleaseService(dataSource, repository);

        bossService = mock(BossService.class);
        aiService = mock(AiService.class);
        aiMatchService = mock(AiMatchService.class);
        bossResumeImageService = mock(BossResumeImageService.class);
        budgetService = mock(BudgetService.class);
        deliveryLedgerService = mock(DeliveryLedgerService.class);
        evaluationRepository = mock(EvaluationRepository.class);
        contactStateService = mock(ContactStateService.class);
        followupService = mock(FollowupService.class);
        boss = new Boss(bossService, aiService, aiMatchService, bossResumeImageService,
                budgetService, deliveryLedgerService, evaluationRepository,
                contactStateService, followupService);

        BossConfig config = new BossConfig();
        config.setCityCode(new ArrayList<>()); // 不进入正常搜索投递，只执行人工放行队列
        config.setEnableAI(true);
        config.setSayHi("固定招呼语");
        config.setAllowRepeatChat(false);
        boss.setConfig(config);

        runContext = new BossRunContext("run-d3b");
        ReflectionTestUtils.setField(boss, "manualReleaseService", service);
        ReflectionTestUtils.setField(boss, "runContext", runContext);
        ReflectionTestUtils.setField(boss, "maxMessageLength", MAX_MESSAGE_LENGTH);
        boss.setProgressCallback((message, current, total) -> progressMessages.add(message));
        boss.setPipelineCallback(event -> {
        });

        when(aiService.getAiConfig()).thenReturn(new AiEntity());
        when(budgetService.reserveFirstContact(anyString(), anyString(), anyString()))
                .thenReturn(new BudgetService.Reservation(
                        "res-d3b", 1L, true, null, 10, 0, 1, 0));
    }

    // ==================== 失败/拦截分支（不发送） ====================

    @Test
    void aiReReviewMustPassBeforeAnySend() {
        long evaluationId = seedReviewEvaluation("enc-ai-review", "Java 开发");
        approve(evaluationId, "人工批准");
        when(bossService.findBossJobByEncryptId("enc-ai-review")).thenReturn(bossData("enc-ai-review"));
        when(aiMatchService.evaluate(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AiMatchResult(AiOutcome.REVIEW, null, "AI 仍不确定，需要人工复核",
                        List.of(), null, null, "test-model", 1, 1, 10));

        boss.execute();

        ManualReleaseRepository.ManualRelease release = repository.findByEvaluationId(evaluationId);
        assertEquals("FAILED", release.status(), "AI 重新评估不是 MATCH 时必须失败，不能盲发");
        assertTrue(release.error().contains("AI 重新评估未通过"), release.error());
        assertTrue(release.error().contains("AI 仍不确定"),
                "失败原因应记录 AI 分析/错误码：" + release.error());
        assertNull(release.attemptId(), "未发送不得产生 attemptId");
        // 关键：AI 未通过时不能预留预算、不能初始化投递台账
        verify(budgetService, never()).reserveFirstContact(anyString(), anyString(), anyString());
        verifyNoSendAttempt();
        assertEquals(1, runContext.getSkippedCount(), "AI 未通过应记 skipped，而不是 delivered");
        assertEquals(0, runContext.getDeliveredCount());
    }

    @Test
    void aiReEvaluationUsesRebuiltSnapshotFromBossData() {
        long evaluationId = seedReviewEvaluation("enc-snapshot", "Java 开发");
        approve(evaluationId, "人工批准");
        BossJobDataEntity entity = bossData("enc-snapshot");
        entity.setJobDescription("这里是根据 boss_data 重建的岗位详细描述，长度足够。");
        entity.setHrName("张女士");
        when(bossService.findBossJobByEncryptId("enc-snapshot")).thenReturn(entity);
        when(aiMatchService.evaluate(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AiMatchResult(AiOutcome.MATCH, MESSAGE, "匹配", List.of(),
                        null, null, "test-model", 1, 1, 10));
        doThrow(new IllegalStateException("投递台账不可用")).when(deliveryLedgerService)
                .beginAttempt(any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any());

        boss.execute();

        ArgumentCaptor<String> jobName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jobDescription = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sayHi = ArgumentCaptor.forClass(String.class);
        verify(aiMatchService).evaluate(any(), jobName.capture(), jobDescription.capture(),
                keyword.capture(), sayHi.capture(), anyInt());
        assertEquals("Java 开发", jobName.getValue(), "AI 重新评估必须使用 boss_data 重建的岗位快照");
        assertEquals("这里是根据 boss_data 重建的岗位详细描述，长度足够。", jobDescription.getValue());
        assertEquals("Java", keyword.getValue(), "应使用评估记录里的来源关键词");
        assertEquals("固定招呼语", sayHi.getValue(), "AI 话术生成应沿用当前配置的招呼语模板");

        // 走到 deliverJob：台账消息哈希与岗位链接都来自快照/新话术（随后被 mock 台账拦截）
        ManualReleaseRepository.ManualRelease release = repository.findByEvaluationId(evaluationId);
        assertEquals("FAILED", release.status());
        assertTrue(release.error().contains("投递台账初始化失败"), release.error());
        verify(budgetService).reserveFirstContact("run-d3b", "enc-snapshot", "Java 开发");
        ArgumentCaptor<DeliveryKind> kind = ArgumentCaptor.forClass(DeliveryKind.class);
        ArgumentCaptor<String> jobUrl = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageHash = ArgumentCaptor.forClass(String.class);
        verify(deliveryLedgerService).beginAttempt(anyString(), kind.capture(), anyString(), anyString(),
                anyString(), anyString(), jobUrl.capture(), any(), messageHash.capture(), any(),
                anyString(), any(), any());
        assertEquals(DeliveryKind.FIRST_CONTACT, kind.getValue());
        assertEquals("https://www.zhipin.com/job_detail/enc-snapshot.html", jobUrl.getValue(),
                "必须使用快照里的岗位链接（不使用当前列表页残留链接）");
        assertNotNull(messageHash.getValue(), "台账必须记录新话术哈希");
        verify(budgetService).release(anyString(), anyString());
    }

    @Test
    void invalidAiMessageIsRejectedBeforeBudgetAndSend() {
        long evaluationId = seedReviewEvaluation("enc-bad-message", "Java 开发");
        approve(evaluationId, "人工批准");
        when(bossService.findBossJobByEncryptId("enc-bad-message")).thenReturn(bossData("enc-bad-message"));
        // MATCH 但话术含 JSON 字段/超长：必须被 AiMessageValidator 拦下
        when(aiMatchService.evaluate(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AiMatchResult(AiOutcome.MATCH, "decision: match，这明显是 JSON 片段",
                        "分析", List.of(), null, null, "test-model", 1, 1, 10));

        boss.execute();

        ManualReleaseRepository.ManualRelease release = repository.findByEvaluationId(evaluationId);
        assertEquals("FAILED", release.status());
        assertTrue(release.error().contains("话术校验不通过"), release.error());
        verify(budgetService, never()).reserveFirstContact(anyString(), anyString(), anyString());
        verifyNoSendAttempt();
    }

    @Test
    void budgetDeniedKeepsApprovedForNextRoundWithoutSending() {
        long evaluationId = seedReviewEvaluation("enc-budget", "Java 开发");
        approve(evaluationId, "人工批准");
        when(bossService.findBossJobByEncryptId("enc-budget")).thenReturn(bossData("enc-budget"));
        when(aiMatchService.evaluate(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AiMatchResult(AiOutcome.MATCH, MESSAGE, "匹配", List.of(),
                        null, null, "test-model", 1, 1, 10));
        when(budgetService.reserveFirstContact(anyString(), anyString(), anyString()))
                .thenReturn(new BudgetService.Reservation(
                        "res-denied", 0L, false, "今日首次沟通预算已用完", 10, 10, 0, 0));

        boss.execute();

        ManualReleaseRepository.ManualRelease release = repository.findByEvaluationId(evaluationId);
        assertEquals("APPROVED", release.status(), "预算不足时不能消耗放行记录，应保留待重试");
        assertNull(release.error(), "预算不足不是执行失败，不应写 error");
        assertEquals(1, service.approvedForExecution().size(), "下一轮仍可继续消费该放行记录");
        assertTrue(progressMessages.stream().anyMatch(message -> message.contains("预算不足")),
                "应向进度通道提示预算不足：" + progressMessages);
        verifyNoSendAttempt();
        assertEquals(0, runContext.getDeliveredCount());
    }

    @Test
    void missingSnapshotOrIdentityMarksFailedWithoutAiOrBudget() {
        long missingJob = seedReviewEvaluation("enc-no-data", "岗位A");
        approve(missingJob, "批准A");
        when(bossService.findBossJobByEncryptId("enc-no-data")).thenReturn(null);

        long noIdentity = seedReviewEvaluation("enc-no-id", "岗位B");
        approve(noIdentity, "批准B");
        BossJobDataEntity noEncryptId = bossData("enc-no-id");
        noEncryptId.setEncryptId(null);
        when(bossService.findBossJobByEncryptId("enc-no-id")).thenReturn(noEncryptId);

        boss.execute();

        assertEquals("岗位快照不存在，无法放行", repository.findByEvaluationId(missingJob).error());
        assertEquals("岗位缺少 encryptId，无法确认身份", repository.findByEvaluationId(noIdentity).error());
        verifyNoInteractions(aiService);
        verifyNoInteractions(aiMatchService);
        verifyNoInteractions(budgetService);
        verifyNoSendAttempt();
    }

    @Test
    void alreadyConfirmedBlockedOrDedupedContactIsNotReleased() {
        long confirmed = seedReviewEvaluation("enc-confirmed", "岗位A");
        approve(confirmed, "批准A");
        when(bossService.findBossJobByEncryptId("enc-confirmed")).thenReturn(bossData("enc-confirmed"));
        when(deliveryLedgerService.hasConfirmedFirstContact("enc-confirmed")).thenReturn(true);

        long blocked = seedReviewEvaluation("enc-blocked", "岗位B");
        approve(blocked, "批准B");
        when(bossService.findBossJobByEncryptId("enc-blocked")).thenReturn(bossData("enc-blocked"));
        when(contactStateService.isAutoContactBlocked("uid:user-enc-blocked")).thenReturn(true);

        long deduped = seedReviewEvaluation("enc-deduped", "岗位C");
        approve(deduped, "批准C");
        when(bossService.findBossJobByEncryptId("enc-deduped")).thenReturn(bossData("enc-deduped"));
        when(contactStateService.hasOtherJobConfirmedFirstContact(anyString(), anyString(), anyInt()))
                .thenReturn(true);

        boss.execute();

        assertEquals("该岗位已经确认沟通过，不重复放行",
                repository.findByEvaluationId(confirmed).error());
        assertEquals("联系人已回复/拒绝/接管/停止联系，禁止放行",
                repository.findByEvaluationId(blocked).error());
        assertEquals("同一 HR 近期已在其他岗位沟通过，避免重复打扰",
                repository.findByEvaluationId(deduped).error());
        verifyNoInteractions(aiService);
        verifyNoInteractions(budgetService);
        verifyNoSendAttempt();
        assertEquals(0, runContext.getDeliveredCount());
    }

    @Test
    void aiFailureIsMarkedFailedAndNoSend() {
        long evaluationId = seedReviewEvaluation("enc-ai-error", "Java 开发");
        approve(evaluationId, "人工批准");
        when(bossService.findBossJobByEncryptId("enc-ai-error")).thenReturn(bossData("enc-ai-error"));
        when(aiMatchService.evaluate(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("AI 请求超时"));

        boss.execute();

        ManualReleaseRepository.ManualRelease release = repository.findByEvaluationId(evaluationId);
        assertEquals("FAILED", release.status());
        assertTrue(release.error().contains("重新生成话术异常"), release.error());
        verify(budgetService, never()).reserveFirstContact(anyString(), anyString(), anyString());
        verifyNoSendAttempt();
    }

    @Test
    void syncedReplyAtPreBudgetRecheckStopsReleaseWithoutReservingOrSending() {
        long evaluationId = seedReviewEvaluation("enc-recheck", "Java 开发");
        approve(evaluationId, "人工批准");
        when(bossService.findBossJobByEncryptId("enc-recheck")).thenReturn(bossData("enc-recheck"));
        when(aiMatchService.evaluate(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AiMatchResult(AiOutcome.MATCH, MESSAGE, "匹配", List.of(),
                        null, null, "test-model", 1, 1, 10));
        // 第一次（放行前）检查通过；第二次（预算预留前复检）时 D2 状态同步已把 HR 回复写成 REPLIED
        when(contactStateService.isAutoContactBlocked("uid:user-enc-recheck"))
                .thenReturn(false, true);

        boss.execute();

        ManualReleaseRepository.ManualRelease release = repository.findByEvaluationId(evaluationId);
        assertEquals("FAILED", release.status(), "发送前复检 blocked 必须失败，不得放行");
        assertTrue(release.error().contains("发送前复检"), release.error());
        assertNull(release.attemptId(), "未发送不得产生 attemptId");
        verify(contactStateService, times(2)).isAutoContactBlocked("uid:user-enc-recheck");
        verify(budgetService, never()).reserveFirstContact(anyString(), anyString(), anyString());
        verifyNoSendAttempt();
        assertEquals(1, runContext.getSkippedCount());
        assertEquals(0, runContext.getDeliveredCount());
    }

    // ==================== 消费顺序 / 成功路径状态 ====================

    @Test
    void approvedReleasesAreConsumedInRequestedOrderAndMarkedIndividually() {
        long first = seedReviewEvaluation("enc-first", "岗位一");
        long second = seedReviewEvaluation("enc-second", "岗位二");
        insertApproved(first, "2026-11-07 10:00:00");
        insertApproved(second, "2026-11-07 10:00:01");
        when(bossService.findBossJobByEncryptId(anyString()))
                .thenAnswer(invocation -> {
                    String encryptId = invocation.getArgument(0);
                    BossJobDataEntity entity = bossData(encryptId);
                    entity.setJobName("岗位-" + encryptId);
                    return entity;
                });
        when(aiMatchService.evaluate(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AiMatchResult(AiOutcome.MATCH, MESSAGE, "匹配", List.of(),
                        null, null, "test-model", 1, 1, 10));
        doThrow(new IllegalStateException("台账不可用")).when(deliveryLedgerService)
                .beginAttempt(any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any());

        boss.execute();

        // 两条按 requested_at 升序各执行一次（都被台账 mock 拦在发送前，标记 FAILED）
        ArgumentCaptor<String> jobNames = ArgumentCaptor.forClass(String.class);
        verify(aiMatchService, times(2)).evaluate(any(), jobNames.capture(), anyString(),
                anyString(), anyString(), anyInt());
        assertEquals(List.of("岗位-enc-first", "岗位-enc-second"), jobNames.getAllValues(),
                "人工放行必须按 requested_at 升序逐条消费");
        assertTrue(repository.findByEvaluationId(first).error().contains("投递台账初始化失败"));
        assertTrue(repository.findByEvaluationId(second).error().contains("投递台账初始化失败"));
        assertEquals(2, service.queue("FAILED").counts().get("FAILED"));
        assertEquals(0, service.approvedForExecution().size(), "本轮已处理的记录不再留在 APPROVED");
    }

    @Test
    void rejectedOrPendingRecordsAreNeverExecuted() {
        long pending = seedReviewEvaluation("enc-pending", "待放行岗位");
        long rejected = seedReviewEvaluation("enc-rejected", "已拒绝岗位");
        service.decide(rejected, "REJECT", "岗位方向不符");

        boss.execute();

        verifyNoInteractions(aiService);
        verifyNoInteractions(aiMatchService);
        verifyNoInteractions(budgetService);
        verifyNoSendAttempt();
        assertNull(repository.findByEvaluationId(pending), "未决策记录不得被自动执行");
        assertEquals("REJECTED", repository.findByEvaluationId(rejected).status());
    }

    @Test
    void debuggerModeSkipsManualReleaseQueueWithoutSending() {
        long evaluationId = seedReviewEvaluation("enc-debugger", "Java 开发");
        approve(evaluationId, "人工批准");
        when(bossService.findBossJobByEncryptId("enc-debugger")).thenReturn(bossData("enc-debugger"));

        // P0 修复验收：debugger=1（只评估、不投递）+ 队列里有 APPROVED 记录时，
        // execute() 不得消费放行队列，更不得真实发送。
        BossConfig debugConfig = new BossConfig();
        debugConfig.setCityCode(new ArrayList<>());
        debugConfig.setEnableAI(true);
        debugConfig.setSayHi("固定招呼语");
        debugConfig.setAllowRepeatChat(false);
        debugConfig.setDebugger(true);
        boss.setConfig(debugConfig);

        boss.execute();

        ManualReleaseRepository.ManualRelease release = repository.findByEvaluationId(evaluationId);
        assertEquals("APPROVED", release.status(),
                "调试模式只评估不投递，绝不能消费人工放行记录");
        assertNull(release.attemptId(), "调试模式不得产生投递尝试");
        verifyNoInteractions(aiService);
        verifyNoInteractions(aiMatchService);
        verifyNoInteractions(budgetService);
        verifyNoSendAttempt();
        assertEquals(0, runContext.getDeliveredCount(), "调试模式不得记 delivered");
        assertTrue(progressMessages.stream().anyMatch(message -> message.contains("调试模式")),
                "应向进度通道明确提示“调试模式跳过人工放行”：" + progressMessages);
    }

    // ==================== 源码断言：安全链路顺序 ====================

    @Test
    void sourceLocksDebuggerShortCircuitBeforeManualReleaseConsumption() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/offerchaser/worker/boss/Boss.java"));
        int processStart = source.indexOf("private void processManualReleases()");
        int singleStart = source.indexOf("private void processManualRelease(");
        assertTrue(processStart > 0 && singleStart > processStart,
                "Boss 必须存在 processManualReleases()/processManualRelease(...)");
        String body = source.substring(processStart, singleStart);
        int debugger = body.indexOf("config.getDebugger()");
        int approved = body.indexOf("manualReleaseService.approvedForExecution()");
        assertTrue(debugger > 0,
                "P0 修复：processManualReleases 必须在队列入口判断 debugger（调试模式不得真实发送）");
        assertTrue(approved > debugger,
                "P0 修复：debugger 短路必须发生在读取/消费人工放行队列之前");
    }

    @Test
    void sourceLocksManualReleaseSafetyChain() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/offerchaser/worker/boss/Boss.java"));
        int executeStart = source.indexOf("public int execute()");
        int processStart = source.indexOf("private void processManualReleases()");
        int processEnd = source.indexOf("private JobSnapshot snapshotFromEntity(");
        assertTrue(executeStart > 0 && processStart > executeStart && processEnd > processStart,
                "Boss 必须存在 execute() 与 processManualReleases()");
        String executeBody = source.substring(executeStart, processStart);
        assertTrue(executeBody.contains("processManualReleases();"),
                "execute() 必须消费人工放行队列");
        assertTrue(executeBody.indexOf("processManualReleases();")
                        < executeBody.indexOf("for (String cityCode"),
                "必须先处理人工放行队列，再进入正常搜索投递");

        String chain = source.substring(processStart, processEnd);
        for (String marker : List.of(
                "manualReleaseService.approvedForExecution()",
                "manualReleaseService.findEvaluation(release.evaluationId())",
                "bossService.findBossJobByEncryptId(evaluation.encryptId())",
                "snapshotFromEntity(entity, evaluation)",
                "snapshot.hasIdentity()",
                "deliveryLedgerService.hasConfirmedFirstContact(",
                "contactStateService.isAutoContactBlocked(",
                "contactStateService.hasOtherJobConfirmedFirstContact(",
                "aiMatchService.evaluate(",
                "aiResult.outcome() != AiOutcome.MATCH",
                "AiMessageValidator.validate(message, maxMessageLength)",
                // D-02：预算预留前必须再次复检联系人（状态同步可能刚回写 REPLIED）
                "发送前复检：联系人已回复/拒绝/接管/停止",
                "budgetService.reserveFirstContact(",
                "deliverJob(snapshot, new DeliveryPlan(DeliveryKind.FIRST_CONTACT, message),",
                "markConsumed(",
                "markFailed(")) {
            assertTrue(chain.contains(marker), "人工放行链路缺少安全步骤：" + marker);
        }
        assertTrue(chain.contains("reservation.reservationId(), true);"),
                "deliverJob 必须传 useSnapshotJobUrl=true（使用快照岗位链接，不误用列表页残留链接）");
        // 顺序：身份/联系人 → AI MATCH → 话术校验 → D-02 预算前复检 → 预算预留 → 发送 → 消费/失败
        int identity = chain.indexOf("snapshot.hasIdentity()");
        int aiMatch = chain.indexOf("aiMatchService.evaluate(");
        int aiGate = chain.indexOf("aiResult.outcome() != AiOutcome.MATCH");
        int validation = chain.indexOf("AiMessageValidator.validate(");
        int preBudgetRecheck = chain.indexOf("发送前复检：联系人已回复/拒绝/接管/停止");
        int budget = chain.indexOf("budgetService.reserveFirstContact(");
        int deliver = chain.indexOf("deliverJob(snapshot, new DeliveryPlan(DeliveryKind.FIRST_CONTACT, message),");
        int consumed = chain.indexOf("markConsumed(");
        assertTrue(identity < aiMatch && aiMatch < aiGate && aiGate < validation
                        && validation < preBudgetRecheck && preBudgetRecheck < budget
                        && budget < deliver && deliver < consumed,
                "安全链路顺序必须是：身份检查 → AI 重新评估 → MATCH 门禁 → 话术校验 → D-02 预算前复检 "
                        + "→ 预算 → 发送 → 标记消费");
        assertTrue(chain.contains("预算不足"), "预算不足必须有明确分支（保留 APPROVED 下一轮重试）");
        assertFalse(chain.contains("deliverJob(snapshot, new DeliveryPlan(DeliveryKind.FIRST_CONTACT, message), null"),
                "人工放行不得绕过预算预留直接发送");

        // deliverJobInternal：身份核对 fail-closed + 文字/图片独立确认 + 快照链接模式
        int deliverStart = source.indexOf("private void deliverJobInternal(");
        int deliverEnd = source.indexOf("private RuleDecision checkIdentity(");
        String deliverBody = source.substring(deliverStart, deliverEnd);
        assertTrue(deliverBody.contains("useSnapshotJobUrl ? null : page.locator(\"a.more-job-btn\")"),
                "useSnapshotJobUrl=true 时必须跳过列表页“查看更多”按钮");
        assertTrue(deliverBody.contains("chatConfirmer.verifyChatIdentity("),
                "发送前必须核对聊天会话身份");
        assertTrue(deliverBody.contains("CHAT_IDENTITY_MISMATCH")
                        && deliverBody.contains("CHAT_IDENTITY_UNKNOWN"),
                "身份不符/未知必须 fail-closed 拒绝发送");
        assertTrue(deliverBody.contains("chatConfirmer.clickAndConfirmText("),
                "文字消息必须走发送确认");
        assertTrue(deliverBody.contains("chatConfirmer.awaitImageConfirmed("),
                "图片消息必须独立确认");
    }

    // ==================== 工具 ====================

    private void verifyNoSendAttempt() {
        verify(deliveryLedgerService, never()).beginAttempt(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    private void approve(long evaluationId, String reason) {
        ManualReleaseService.DecisionResult result = service.decide(evaluationId, "APPROVE", reason);
        assertEquals("APPROVED", result.status());
    }

    private void insertApproved(long evaluationId, String requestedAt) {
        repository.insert(new ManualReleaseRepository.ManualRelease(
                "rel-" + evaluationId, evaluationId, "APPROVED", "测试批准",
                requestedAt, null, null, null, null, requestedAt, requestedAt));
    }

    private long seedReviewEvaluation(String encryptId, String jobName) {
        execute(dataSource, "INSERT INTO job_evaluation(run_id, encrypt_id, encrypt_user_id, job_name, " +
                        "company_name, job_url, source_keyword, snapshot_hash, rule_decision, rule_reason_code, " +
                        "rule_evidence, ai_decision, ai_analysis, created_at) " +
                        "VALUES('run-d3b', ?, ?, ?, '测试公司', ?, 'Java', 'snapshot-" + encryptId + "', " +
                        "'REVIEW', 'SALARY_REVIEW', '薪资需人工确认', 'REVIEW', 'AI 待人工复核', " +
                        "'2026-11-07 10:00:00')",
                encryptId, "user-" + encryptId, jobName,
                "https://www.zhipin.com/job_detail/" + encryptId + ".html");
        return scalar(dataSource, "SELECT MAX(id) FROM job_evaluation");
    }

    private BossJobDataEntity bossData(String encryptId) {
        BossJobDataEntity entity = new BossJobDataEntity();
        entity.setEncryptId(encryptId);
        entity.setEncryptUserId("user-" + encryptId);
        entity.setJobName("Java 开发");
        entity.setCompanyName("测试公司");
        entity.setJobDescription("测试公司的 Java 开发岗位描述，内容足够长以便 AI 重新评估。");
        entity.setJobUrl("https://www.zhipin.com/job_detail/" + encryptId + ".html");
        entity.setHrName("张女士");
        return entity;
    }
}
