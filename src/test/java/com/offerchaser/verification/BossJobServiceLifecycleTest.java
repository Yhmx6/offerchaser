package com.offerchaser.verification;

import com.offerchaser.application.ai.AiConfigException;
import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.delivery.RuleSet;
import com.offerchaser.application.delivery.SalaryStrategy;
import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DatabaseMaintenanceService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.SchemaMigrationService;
import com.offerchaser.application.persistence.TaskRunRepository;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.ConfigService;
import com.offerchaser.worker.boss.Boss;
import com.offerchaser.worker.boss.BossConfig;
import com.offerchaser.worker.boss.BossRunContext;
import com.offerchaser.worker.dto.JobProgressMessage;
import com.offerchaser.worker.manager.PlaywrightManager;
import com.offerchaser.worker.service.BossJobService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务生命周期验收（A+ 版，A-04）。
 * <p>
 * 核心语义变化：终止请求 ≠ worker 退出。forceReset 只置取消 + FAILED，**不释放启动权限**；
 * 只有旧 worker 真正执行完 finally（markExecutionFinished）后才允许下一轮启动。
 * 旧线程收尾不得覆盖 forceReset 写入的 task_run FAILED，也不得发出“投递任务结束”成功消息。
 */
class BossJobServiceLifecycleTest {

    private static final long WAIT_SECONDS = 10L;

    private PlaywrightManager playwrightManager;
    private ConfigService configService;
    @SuppressWarnings("unchecked")
    private ObjectProvider<Boss> bossProvider;
    private AiService aiService;
    private AiMatchService aiMatchService;
    private BudgetService budgetService;
    private DeliveryLedgerService deliveryLedgerService;
    private TaskRunRepository taskRunRepository;
    private SchemaMigrationService schemaMigrationService;
    private DatabaseMaintenanceService databaseMaintenanceService;
    /** B1：getStatus().pendingResolutions 依赖它；测试只需 mock。 */
    private BossQueryRepository bossQueryRepository;
    /** B2：预检阶段固定本轮规则快照；测试返回默认 RuleSet 即可。 */
    private RuleConfigService ruleConfigService;
    private Boss bossStub;
    private BossJobService service;
    private BossConfig config;

    @BeforeEach
    void setUp() {
        playwrightManager = mock(PlaywrightManager.class);
        configService = mock(ConfigService.class);
        bossProvider = mock(ObjectProvider.class);
        aiService = mock(AiService.class);
        aiMatchService = mock(AiMatchService.class);
        budgetService = mock(BudgetService.class);
        deliveryLedgerService = mock(DeliveryLedgerService.class);
        taskRunRepository = mock(TaskRunRepository.class);
        schemaMigrationService = mock(SchemaMigrationService.class);
        databaseMaintenanceService = mock(DatabaseMaintenanceService.class);
        bossQueryRepository = mock(BossQueryRepository.class);
        when(bossQueryRepository.countPendingResolutions()).thenReturn(0);
        ruleConfigService = mock(RuleConfigService.class);

        config = new BossConfig();
        config.setKeywords(List.of("Java"));
        config.setCityCode(List.of("101020100"));
        config.setSayHi("您好，我有相关经验，希望进一步沟通。");
        config.setDebugger(false);
        config.setEnableAI(false);
        when(ruleConfigService.getActiveRuleSet()).thenReturn(
                RuleSet.defaultFrom(config, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT));
        when(ruleConfigService.currentVersion()).thenReturn(1);

        bossStub = mock(Boss.class);
        Page bossPage = mock(Page.class);
        when(schemaMigrationService.isReady()).thenReturn(true);
        when(configService.getBossConfig()).thenReturn(config);
        when(databaseMaintenanceService.backup(anyString())).thenReturn(null);
        when(budgetService.reconcileStaleReservations(anyString())).thenReturn(0);
        when(budgetService.isFirstContactBudgetDepleted()).thenReturn(false);
        when(playwrightManager.getBossPage()).thenReturn(bossPage);
        when(playwrightManager.isLoggedIn("boss")).thenReturn(true);
        when(bossProvider.getObject()).thenReturn(bossStub);
        doReturn(0).when(playwrightManager).callOnPlaywright(any());

        service = new BossJobService(playwrightManager, configService, bossProvider, aiService,
                aiMatchService, budgetService, deliveryLedgerService, taskRunRepository,
                schemaMigrationService, databaseMaintenanceService, bossQueryRepository,
                ruleConfigService, mock(ProfileTemplateService.class));
    }

    @Test
    void duplicateStartRequestProducesOnlyOneRunId() throws Exception {
        CountDownLatch enteredEnsureReady = new CountDownLatch(1);
        CountDownLatch allowEnsureReady = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredEnsureReady.countDown();
            assertTrue(allowEnsureReady.await(WAIT_SECONDS, TimeUnit.SECONDS), "等待释放预检失败");
            return null;
        }).when(playwrightManager).ensureReady();

        List<JobProgressMessage> firstRunMessages = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String firstRunId;
        try {
            Future<?> firstRun = executor.submit(() -> service.executeDelivery(firstRunMessages::add));
            assertTrue(enteredEnsureReady.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "第一次任务应进入浏览器预检（ensureReady）");
            Map<String, Object> startingStatus = service.getStatus();
            firstRunId = (String) startingStatus.get("runId");
            assertNotNull(firstRunId, "任务启动后应能查询到 runId");
            assertEquals("STARTING", startingStatus.get("state"));
            assertTrue(service.isRunning());
            assertEquals(Boolean.FALSE, startingStatus.get("canStart"));

            // 第二次启动：占用期间必须被拒绝，不得创建第二个 runId
            List<JobProgressMessage> duplicateMessages = new ArrayList<>();
            service.executeDelivery(duplicateMessages::add);
            assertEquals(1, duplicateMessages.size(), "重复启动应只返回一条 warning，不应真正启动");
            assertTrue(duplicateMessages.get(0).getMessage().contains(firstRunId),
                    "重复启动提示应带上正在运行的 runId");
            assertTrue(duplicateMessages.get(0).getMessage().contains("尚未退出"));
            // B2 回归（F-B1-2）：重复启动被拒的 warning 也必须带 runId/seq/state
            assertEquals(firstRunId, duplicateMessages.get(0).getRunId());
            assertNotNull(duplicateMessages.get(0).getSeq());
            assertNotNull(duplicateMessages.get(0).getState());
            assertEquals(firstRunId, service.getStatus().get("runId"));
            // 本轮 runId 的 task_run 只在真正启动时创建一次（此时已完成 start，未创建第二个）
            verify(taskRunRepository, times(1)).start(eq(firstRunId), eq("boss"), any());

            allowEnsureReady.countDown();
            firstRun.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            allowEnsureReady.countDown();
            executor.shutdownNow();
        }

        Map<String, Object> finalStatus = service.getStatus();
        assertEquals("COMPLETED", finalStatus.get("state"));
        assertEquals(firstRunId, finalStatus.get("runId"));
        assertFalse(service.isRunning());
        assertEquals(Boolean.TRUE, finalStatus.get("workerExited"));
        assertEquals(Boolean.TRUE, finalStatus.get("canStart"));
        verify(taskRunRepository, times(1)).start(eq(firstRunId), eq("boss"), any());
        verify(taskRunRepository, times(1)).finish(eq(firstRunId), eq("COMPLETED"), isNull(), isNull());
        assertFalse(firstRunMessages.isEmpty(), "任务应产生进度消息");
        // B2：预检加载的规则快照必须注入 Boss（本轮固定规则版本）
        verify(bossStub).setRuleSet(any(RuleSet.class));
        assertTrue(firstRunMessages.stream().anyMatch(message -> message.getMessage() != null
                        && message.getMessage().contains("规则版本")),
                "任务进度应显示本轮规则版本");
    }

    /**
     * A-04 核心验收：forceReset 后旧 worker 未退出前不得启动新任务；旧线程退出后：
     * 1) 不覆盖 forceReset 写入的 FAILED；2) 不发出“投递任务结束”成功消息；3) 才允许新 runId。
     */
    @Test
    void forceResetKeepsStartBlockedUntilOldWorkerExits() throws Exception {
        CountDownLatch enteredEnsureReady = new CountDownLatch(1);
        CountDownLatch allowEnsureReady = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredEnsureReady.countDown();
            assertTrue(allowEnsureReady.await(WAIT_SECONDS, TimeUnit.SECONDS), "等待释放预检失败");
            return null;
        }).doThrow(new IllegalStateException("第二个任务预检失败"))
                .when(playwrightManager).ensureReady();

        List<JobProgressMessage> firstRunMessages = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String firstRunId;
        try {
            Future<?> firstRun = executor.submit(() -> service.executeDelivery(firstRunMessages::add));
            assertTrue(enteredEnsureReady.await(WAIT_SECONDS, TimeUnit.SECONDS));
            firstRunId = (String) service.getStatus().get("runId");
            assertNotNull(firstRunId);

            // ===== 强制复位：只发出终止请求并写 FAILED，不释放启动权限 =====
            Map<String, Object> resetResult = service.forceReset();
            assertEquals(Boolean.TRUE, resetResult.get("success"));
            assertEquals("FAILED", resetResult.get("status"));
            assertEquals(Boolean.FALSE, resetResult.get("workerExited"),
                    "forceReset 返回时旧 worker 尚未退出");
            assertEquals("FAILED", service.getStatus().get("state"));
            assertTrue(service.isRunning(),
                    "旧 worker 未退出前必须继续占用执行器（isRunning=true）");
            assertEquals(Boolean.FALSE, service.getStatus().get("canStart"));
            assertNotNull(service.getStatus().get("workerExitHint"));
            verify(taskRunRepository, times(1))
                    .finish(eq(firstRunId), eq("FAILED"), any(), any());

            // 旧 worker 退出前，第二次启动必须被拒绝且不产生新 runId
            List<JobProgressMessage> rejectedMessages = new ArrayList<>();
            service.executeDelivery(rejectedMessages::add);
            assertEquals(1, rejectedMessages.size());
            assertTrue(rejectedMessages.get(0).getMessage().contains("尚未退出"),
                    "必须明确提示旧任务线程仍在退出中");
            assertTrue(rejectedMessages.get(0).getMessage().contains(firstRunId));
            assertEquals(firstRunId, service.getStatus().get("runId"));
            verify(taskRunRepository, times(1)).start(anyString(), anyString(), any());
            verify(playwrightManager, never()).resumeBossMonitoring();

            // ===== 释放旧 worker，等它真正退出 =====
            allowEnsureReady.countDown();
            firstRun.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            allowEnsureReady.countDown();
            executor.shutdownNow();
        }

        Map<String, Object> afterExit = service.getStatus();
        assertEquals(Boolean.TRUE, afterExit.get("workerExited"));
        assertEquals(Boolean.TRUE, afterExit.get("canStart"));
        assertFalse(service.isRunning());
        // 旧线程退出时不得把 forceReset 的 FAILED 覆盖成 COMPLETED
        verify(taskRunRepository, never())
                .finish(eq(firstRunId), eq("COMPLETED"), any(), any());
        // 旧线程也不得发出“投递任务结束”的成功消息
        assertTrue(firstRunMessages.stream().noneMatch(message -> message.getMessage() != null
                        && message.getMessage().contains("投递任务结束")),
                "被强制复位的旧线程不应报告任务成功结束");
        // 旧线程 finally 恢复监控（此时新任务尚未启动，不会影响新任务）
        verify(playwrightManager, times(1)).resumeBossMonitoring();

        // ===== 旧 worker 退出后才允许新任务 =====
        List<JobProgressMessage> secondMessages = new ArrayList<>();
        service.executeDelivery(secondMessages::add);
        String secondRunId = (String) service.getStatus().get("runId");
        assertNotNull(secondRunId);
        assertNotEquals(firstRunId, secondRunId, "旧 worker 退出后必须允许创建新的 runId");
        assertEquals("FAILED", service.getStatus().get("state"));
        assertTrue(secondMessages.stream().anyMatch(message -> message.getMessage() != null
                        && message.getMessage().contains("第二个任务预检失败")),
                "第二个任务的预检失败应通过进度消息明确返回");
        verify(taskRunRepository, times(2)).start(anyString(), anyString(), any());
        // 第二个任务预检失败但 worker 已退出，不能再阻塞后续启动
        assertFalse(service.isRunning());
        assertEquals(Boolean.TRUE, service.getStatus().get("canStart"));
    }

    @Test
    void preflightFailureRightAfterStartStillReleasesStartPermission() {
        when(schemaMigrationService.isReady()).thenReturn(false);
        service.executeDelivery(message -> {
        });
        assertEquals("FAILED", service.getStatus().get("state"));
        assertFalse(service.isRunning(), "预检失败也必须 markExecutionFinished，释放启动权限");
        assertEquals(Boolean.TRUE, service.getStatus().get("canStart"));
        String failedRunId = (String) service.getStatus().get("runId");
        verify(taskRunRepository, never()).start(anyString(), anyString(), any());

        // 修复数据库状态后可以再次启动：证明启动权限确实被释放
        when(schemaMigrationService.isReady()).thenReturn(true);
        service.executeDelivery(message -> {
        });
        assertEquals("COMPLETED", service.getStatus().get("state"));
        assertNotEquals(failedRunId, service.getStatus().get("runId"),
                "预检失败后必须允许创建新的 runId");
        verify(taskRunRepository, times(1)).start(anyString(), anyString(), any());
    }

    @Test
    void stopRequestKeepsStoppingStateAndDoesNotFakeStopped() throws Exception {
        CountDownLatch enteredEnsureReady = new CountDownLatch(1);
        CountDownLatch allowEnsureReady = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredEnsureReady.countDown();
            assertTrue(allowEnsureReady.await(WAIT_SECONDS, TimeUnit.SECONDS), "等待释放预检失败");
            return null;
        }).when(playwrightManager).ensureReady();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        String runId;
        try {
            Future<?> run = executor.submit(() -> service.executeDelivery(message -> {
            }));
            assertTrue(enteredEnsureReady.await(WAIT_SECONDS, TimeUnit.SECONDS));
            runId = (String) service.getStatus().get("runId");

            service.stopDelivery();
            Map<String, Object> stoppingStatus = service.getStatus();
            assertEquals("STOPPING", stoppingStatus.get("state"));
            assertEquals(Boolean.TRUE, stoppingStatus.get("stopRequested"));
            assertTrue(service.isRunning(), "STOPPING 不是终态，任务仍在运行");
            assertEquals(Boolean.FALSE, stoppingStatus.get("canStart"));
            verify(taskRunRepository).updateState(eq(runId), eq("STOPPING"), any(), any());

            // 旧实现 15 秒后自动把按钮复位成"已停止"；新实现绝不自动复位（只打日志）。
            // 这里只观察 1.5 秒（远小于 15 秒提示阈值），不应发生任何状态变化。
            Thread.sleep(1_500L);
            assertEquals("STOPPING", service.getStatus().get("state"),
                    "停止指令发出后不得自动复位状态");
            assertTrue(service.isRunning());

            allowEnsureReady.countDown();
            run.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            allowEnsureReady.countDown();
            executor.shutdownNow();
        }

        // 任务线程真正退出后才允许进入终态，且停止原因要落库
        assertEquals("COMPLETED", service.getStatus().get("state"));
        assertFalse(service.isRunning());
        verify(taskRunRepository).finish(eq(runId), eq("COMPLETED"), eq("用户取消投递"), isNull());
    }

    @Test
    void resumeVerificationReturnsFalseWhenNoPausedTask() {
        assertFalse(service.resumeVerification(), "从未启动任务时应返回 false");

        // 正常跑完一轮后也不存在暂停任务
        service.executeDelivery(message -> {
        });
        assertEquals("COMPLETED", service.getStatus().get("state"));
        assertFalse(service.resumeVerification(), "任务已结束时应返回 false");
        Map<String, Object> resetResult = service.forceReset();
        assertEquals(Boolean.FALSE, resetResult.get("success"));
    }

    @Test
    void resumeVerificationResumesPausedRunAndUpdatesTaskRun() throws Exception {
        BossRunContext pausedRun = new BossRunContext("paused-run");
        pausedRun.markRunning();
        pausedRun.enterVerification("触发 Boss 安全校验");
        setCurrentRun(service, pausedRun);

        assertTrue(service.resumeVerification(), "暂停中的任务应能被恢复");
        assertEquals(BossRunContext.State.RUNNING, pausedRun.getState());
        verify(taskRunRepository).updateState("paused-run", "RUNNING", null, null);
        assertFalse(service.resumeVerification(), "重复恢复应返回 false");
    }

    @Test
    void preflightBlocksStartWhenDatabaseMigrationNotReady() {
        when(schemaMigrationService.isReady()).thenReturn(false);
        List<JobProgressMessage> messages = new ArrayList<>();

        service.executeDelivery(messages::add);

        Map<String, Object> status = service.getStatus();
        assertEquals("FAILED", status.get("state"));
        assertTrue(String.valueOf(status.get("error")).contains("数据库升级未完成"),
                "数据库迁移未完成时必须在任务启动前明确报错");
        verify(playwrightManager, never()).ensureReady();
        verify(taskRunRepository, never()).start(anyString(), anyString(), any());
    }

    @Test
    void preflightBlocksStartWhenAiConfigurationInvalid() {
        config.setEnableAI(true);
        when(aiService.getAiConfig()).thenReturn(new AiEntity());
        doThrow(new AiConfigException("缺少 API_KEY")).when(aiMatchService).validateConfiguration(any());
        List<JobProgressMessage> messages = new ArrayList<>();

        service.executeDelivery(messages::add);

        Map<String, Object> status = service.getStatus();
        assertEquals("FAILED", status.get("state"));
        assertTrue(messages.stream().anyMatch(message -> message.getMessage() != null
                        && message.getMessage().contains("AI 配置检查失败")),
                "AI 配置错误必须在启动前置检查阶段暴露");
        verify(playwrightManager, never()).ensureReady();
        verify(taskRunRepository, never()).start(anyString(), anyString(), any());
    }

    @Test
    void preflightBlocksStartWhenDailyBudgetDepleted() {
        when(budgetService.isFirstContactBudgetDepleted()).thenReturn(true);
        List<JobProgressMessage> messages = new ArrayList<>();

        service.executeDelivery(messages::add);

        Map<String, Object> status = service.getStatus();
        assertEquals("FAILED", status.get("state"));
        assertTrue(String.valueOf(status.get("error")).contains("预算已用尽"));
        verify(playwrightManager, never()).ensureReady();
        verify(taskRunRepository, never()).start(anyString(), anyString(), any());
    }

    @Test
    void statusExposesRunContextAndWorkerExitFacts() {
        when(budgetService.todaySummary()).thenReturn(Map.of("date", "2026-11-04", "enabled", true));
        when(deliveryLedgerService.todaySummary()).thenReturn(Map.of("attempts", 0));
        when(bossQueryRepository.countPendingResolutions()).thenReturn(7);

        Map<String, Object> status = service.getStatus();

        assertEquals("boss", status.get("platform"));
        assertEquals("IDLE", status.get("state"));
        assertFalse((Boolean) status.get("isRunning"));
        assertEquals(Boolean.TRUE, status.get("workerExited"));
        assertEquals(Boolean.TRUE, status.get("canStart"));
        assertNotNull(status.get("budget"));
        assertNotNull(status.get("delivery"));
        assertEquals(0L, status.get("runningSeconds"));
        assertEquals(0, status.get("deliveredCount"));
        // B1：待人工核对数量（UNKNOWN 且 manual_resolution 为空）
        assertEquals(7, status.get("pendingResolutions"));
        // B2：状态里暴露当前规则版本，供管理台展示
        assertEquals(1, status.get("ruleVersion"));
    }

    @Test
    void statusFallsBackToZeroWhenPendingResolutionQueryFails() {
        when(bossQueryRepository.countPendingResolutions())
                .thenThrow(new IllegalStateException("模拟查询失败"));

        Map<String, Object> status = service.getStatus();

        assertEquals("IDLE", status.get("state"));
        assertEquals(0, status.get("pendingResolutions"),
                "B1：待核对数量查询失败不能影响 /status 其它字段，回退 0");
    }

    private static void setCurrentRun(BossJobService target, BossRunContext context) throws Exception {
        Field field = BossJobService.class.getDeclaredField("currentRun");
        field.setAccessible(true);
        field.set(target, context);
    }
}
