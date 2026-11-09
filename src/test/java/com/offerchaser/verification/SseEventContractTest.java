package com.offerchaser.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.delivery.RuleSet;
import com.offerchaser.application.delivery.SalaryStrategy;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B1 SSE 事件契约验收：{@link JobProgressMessage} 新增 {@code runId/seq/state}，
 * {@code BossJobService.decorateProgress} 必须为每条任务内进度事件注入 runId、单调递增 seq 与当前状态，
 * 且回调异常不得影响投递主流程。
 * <p>
 * 两个层面：反射直测 decorateProgress（精确断言 seq 递增/状态/异常吞掉），
 * 以及执行一轮完整（mock 浏览器）的 executeDelivery 收集全部进度事件验证端到端装饰。
 */
class SseEventContractTest {

    private BossJobService service;
    private BossConfig config;
    private PlaywrightManager playwrightManager;

    @BeforeEach
    void setUp() {
        playwrightManager = mock(PlaywrightManager.class);
        ConfigService configService = mock(ConfigService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<Boss> bossProvider = mock(ObjectProvider.class);
        AiService aiService = mock(AiService.class);
        AiMatchService aiMatchService = mock(AiMatchService.class);
        BudgetService budgetService = mock(BudgetService.class);
        DeliveryLedgerService deliveryLedgerService = mock(DeliveryLedgerService.class);
        TaskRunRepository taskRunRepository = mock(TaskRunRepository.class);
        SchemaMigrationService schemaMigrationService = mock(SchemaMigrationService.class);
        DatabaseMaintenanceService databaseMaintenanceService = mock(DatabaseMaintenanceService.class);
        BossQueryRepository bossQueryRepository = mock(BossQueryRepository.class);
        RuleConfigService ruleConfigService = mock(RuleConfigService.class);

        config = new BossConfig();
        config.setKeywords(List.of("Java"));
        config.setCityCode(List.of("101020100"));
        config.setSayHi("您好，我有相关经验，希望进一步沟通。");
        config.setDebugger(false);
        config.setEnableAI(false);
        when(ruleConfigService.getActiveRuleSet()).thenReturn(
                RuleSet.defaultFrom(config, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT));
        when(ruleConfigService.currentVersion()).thenReturn(1);

        when(schemaMigrationService.isReady()).thenReturn(true);
        when(configService.getBossConfig()).thenReturn(config);
        when(databaseMaintenanceService.backup(anyString())).thenReturn(null);
        when(budgetService.reconcileStaleReservations(anyString())).thenReturn(0);
        when(budgetService.isFirstContactBudgetDepleted()).thenReturn(false);
        when(playwrightManager.getBossPage()).thenReturn(mock(Page.class));
        when(playwrightManager.isLoggedIn("boss")).thenReturn(true);
        when(bossProvider.getObject()).thenReturn(mock(Boss.class));
        doReturn(0).when(playwrightManager).callOnPlaywright(any());

        service = new BossJobService(playwrightManager, configService, bossProvider, aiService,
                aiMatchService, budgetService, deliveryLedgerService, taskRunRepository,
                schemaMigrationService, databaseMaintenanceService, bossQueryRepository,
                ruleConfigService, mock(ProfileTemplateService.class));
    }

    @Test
    void decorateProgressInjectsRunIdMonotonicSeqAndState() throws Exception {
        BossRunContext run = new BossRunContext("run-decorate");
        run.markRunning();
        List<JobProgressMessage> emitted = new ArrayList<>();
        Consumer<JobProgressMessage> decorated = invokeDecorateProgress(run, emitted::add);

        decorated.accept(JobProgressMessage.info("boss", "one"));
        decorated.accept(JobProgressMessage.info("boss", "two"));
        run.markCompleted();
        decorated.accept(JobProgressMessage.success("boss", "three"));

        assertEquals(3, emitted.size());
        for (JobProgressMessage message : emitted) {
            assertEquals("run-decorate", message.getRunId(), "每条事件必须带当前 runId");
            assertNotNull(message.getSeq());
        }
        assertEquals(1L, emitted.get(0).getSeq(), "seq 应从 1 开始");
        assertEquals(2L, emitted.get(1).getSeq());
        assertEquals(3L, emitted.get(2).getSeq(), "seq 必须单调递增");
        assertEquals("RUNNING", emitted.get(0).getState());
        assertEquals("RUNNING", emitted.get(1).getState());
        assertEquals("COMPLETED", emitted.get(2).getState(), "state 应为事件产生时的任务状态");
    }

    @Test
    void decorateProgressIgnoresNullMessageAndSwallowsConsumerFailure() throws Exception {
        BossRunContext run = new BossRunContext("run-decorate");
        run.markRunning();
        AtomicInteger callbackCalls = new AtomicInteger();
        Consumer<JobProgressMessage> decorated = invokeDecorateProgress(run, message -> {
            callbackCalls.incrementAndGet();
            throw new IllegalStateException("SSE 客户端断开");
        });

        decorated.accept(null);
        assertEquals(0, callbackCalls.get(), "null 消息不应回调");
        decorated.accept(JobProgressMessage.info("boss", "boom"));
        assertEquals(1, callbackCalls.get(), "回调异常应被装饰器吞掉，不能抛出到投递主流程");
    }

    @Test
    void executeDeliveryEmitsDecoratedProgressForWholeRun() {
        List<JobProgressMessage> messages = new ArrayList<>();

        service.executeDelivery(messages::add);

        assertEquals("COMPLETED", service.getStatus().get("state"));
        assertTrue(messages.size() >= 3, "一轮任务应产生多条进度事件，实际 " + messages.size());
        String runId = (String) service.getStatus().get("runId");
        assertNotNull(runId);
        long previousSeq = 0L;
        boolean sawRunningState = false;
        for (JobProgressMessage message : messages) {
            assertEquals(runId, message.getRunId(), "每条事件必须绑定当前 runId：" + message.getMessage());
            assertNotNull(message.getSeq(), "每条事件必须有 seq");
            assertTrue(message.getSeq() > previousSeq,
                    "seq 必须严格递增，实际 " + message.getSeq() + " 前一个 " + previousSeq);
            previousSeq = message.getSeq();
            assertNotNull(message.getState(), "每条事件必须有 state");
            if ("RUNNING".equals(message.getState())) {
                sawRunningState = true;
            }
        }
        assertTrue(sawRunningState, "任务开始投递后的事件 state 应为 RUNNING");
        assertEquals("COMPLETED", messages.get(messages.size() - 1).getState(),
                "收尾成功消息的 state 应为 COMPLETED");
    }

    @Test
    void jobProgressMessageSerializesB1Fields() throws Exception {
        BossRunContext run = new BossRunContext("run-json");
        run.markRunning();
        List<JobProgressMessage> emitted = new ArrayList<>();
        invokeDecorateProgress(run, emitted::add).accept(JobProgressMessage.info("boss", "hello"));

        String json = new ObjectMapper().writeValueAsString(emitted.get(0));

        assertTrue(json.contains("\"runId\":\"run-json\""), "SSE JSON 应包含 runId：" + json);
        assertTrue(json.contains("\"seq\":1"), "SSE JSON 应包含 seq：" + json);
        assertTrue(json.contains("\"state\":\"RUNNING\""), "SSE JSON 应包含 state：" + json);
    }

    /**
     * B2 回归：seq 必须是服务实例级全局单调递增（跨轮次不重置），
     * 否则前端按 seq 去重会把新任务的早期事件丢弃（B1 红队 F-B1-1）。
     */
    @Test
    void seqDoesNotResetAcrossRunsOnSameServiceInstance() {
        List<JobProgressMessage> firstRun = new ArrayList<>();
        service.executeDelivery(firstRun::add);
        long firstRunMax = firstRun.stream().mapToLong(JobProgressMessage::getSeq).max().orElse(0L);
        assertTrue(firstRunMax > 0L);

        List<JobProgressMessage> secondRun = new ArrayList<>();
        service.executeDelivery(secondRun::add);
        long secondRunMin = secondRun.stream().mapToLong(JobProgressMessage::getSeq).min().orElse(Long.MAX_VALUE);

        assertTrue(secondRunMin > firstRunMax,
                "第二轮任务的 seq 必须接续第一轮（服务实例级），实际第二轮最小 " + secondRunMin
                        + " <= 第一轮最大 " + firstRunMax);
    }

    /** 反射调用 BossJobService 私有 decorateProgress。 */
    @SuppressWarnings("unchecked")
    private Consumer<JobProgressMessage> invokeDecorateProgress(
            BossRunContext run, Consumer<JobProgressMessage> raw) throws Exception {
        Method method = BossJobService.class.getDeclaredMethod(
                "decorateProgress", Consumer.class, BossRunContext.class);
        method.setAccessible(true);
        return (Consumer<JobProgressMessage>) method.invoke(service, raw, run);
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> unusedReference() {
        return Map.of();
    }
}
