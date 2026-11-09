package com.offerchaser.verification;

import com.offerchaser.worker.boss.BossRunContext;
import com.offerchaser.worker.boss.BossRunContext.State;
import com.offerchaser.worker.boss.BossRunContext.VerificationWait;
import com.offerchaser.worker.boss.RunCancelledException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务生命周期状态机验收（A+ 版）。
 * <p>
 * 覆盖 STARTING→RUNNING→PAUSED_VERIFICATION→resume→COMPLETED、requestStop/throwIfCancelled、
 * awaitVerification 三种结果、abandon 终端态 + 取消标记，以及 A-04 新增的
 * markExecutionFinished/hasExecutionFinished/isFullyFinished（终止请求 ≠ worker 已退出）。
 */
class BossRunContextTest {

    @Test
    void mainStateMachineReachesCompleted() {
        BossRunContext context = new BossRunContext("run-1");
        assertEquals(State.STARTING, context.getState());
        assertFalse(context.isTerminal());
        assertFalse(context.hasExecutionFinished());
        assertFalse(context.isFullyFinished());

        context.markRunning();
        assertEquals(State.RUNNING, context.getState());

        context.enterVerification("触发滑块验证");
        assertEquals(State.PAUSED_VERIFICATION, context.getState());
        assertEquals("触发滑块验证", context.getVerificationReason());
        assertTrue(context.isVerificationPaused());

        assertTrue(context.resumeVerification(), "暂停状态应能恢复");
        assertEquals(State.RUNNING, context.getState());
        assertFalse(context.isVerificationPaused());
        assertNull(context.getVerificationReason());

        context.markCompleted();
        assertEquals(State.COMPLETED, context.getState());
        assertTrue(context.isTerminal());
        assertTrue(context.getFinishedAtMillis() > 0L);
        assertEquals("run-1", context.getRunId());

        // A-04：任务已进入终态但 worker 可能还没退出，此时不算“完全结束”
        assertFalse(context.hasExecutionFinished());
        assertFalse(context.isFullyFinished());
        context.markExecutionFinished();
        assertTrue(context.hasExecutionFinished());
        assertTrue(context.isFullyFinished());
    }

    @Test
    void executionFinishedIsTheOnlyGateForFullyFinished() {
        BossRunContext context = new BossRunContext("run-1");
        context.markRunning();

        // 未终结：即使调用 markExecutionFinished 也不能算“完全结束”
        context.markExecutionFinished();
        assertTrue(context.hasExecutionFinished());
        assertFalse(context.isFullyFinished(), "任务未进入终态时不能算完全结束");

        context.markFailed("异常退出");
        assertTrue(context.isFullyFinished(), "终态 + worker 退出 = 可以安全启动新任务");
        // 幂等
        context.markExecutionFinished();
        assertTrue(context.isFullyFinished());
    }

    @Test
    void enterVerificationBeforeRunningDoesNotChangeState() {
        BossRunContext context = new BossRunContext("run-1");
        context.enterVerification("不应生效");
        assertEquals(State.STARTING, context.getState(), "只有 RUNNING 才能进入人工验证暂停");
    }

    @Test
    void requestStopTransitionsToStoppingAndCancelsWork() {
        BossRunContext context = new BossRunContext("run-1");
        context.markRunning();

        assertTrue(context.requestStop("用户取消投递"));
        assertEquals(State.STOPPING, context.getState());
        assertTrue(context.isStopRequested());
        assertEquals("用户取消投递", context.getStopReason());
        assertFalse(context.isTerminal(), "STOPPING 不是终态，要等任务线程真正退出");
        assertFalse(context.isFullyFinished(), "worker 未退出前不能启动新任务");

        RunCancelledException cancelled = assertThrows(RunCancelledException.class,
                context::throwIfCancelled);
        assertEquals("用户取消投递", cancelled.getMessage());
        assertTrue(context.isCancelled());

        // 停止后正常收尾：允许从 STOPPING 进入 COMPLETED（由任务线程退出时调用）
        context.markCompleted();
        assertTrue(context.isTerminal());
        assertFalse(context.isFullyFinished());
        context.markExecutionFinished();
        assertTrue(context.isFullyFinished());

        // 终态任务不再响应停止指令
        assertFalse(context.requestStop("再次停止"));
        assertEquals(State.COMPLETED, context.getState());
    }

    @Test
    void stopDefaultsReasonWhenBlank() {
        BossRunContext context = new BossRunContext("run-1");
        context.markRunning();
        assertTrue(context.requestStop("   "));
        assertEquals("用户取消投递", context.getStopReason());

        // 熔断通道（额度耗尽/连续失败）也走同一停止路径，原因原样保留
        BossRunContext another = new BossRunContext("run-2");
        another.markRunning();
        another.abortByCircuitBreaker("今日首次沟通预算已用尽");
        assertEquals("今日首次沟通预算已用尽", another.getStopReason());
        assertEquals(State.STOPPING, another.getState());
    }

    @Test
    void enterVerificationThrowsWhenAlreadyCancelled() {
        BossRunContext context = new BossRunContext("run-1");
        context.markRunning();
        context.requestStop("人工停止");
        assertThrows(RunCancelledException.class, () -> context.enterVerification("滑块"));
        assertEquals(State.STOPPING, context.getState(), "取消后不得再进入暂停态");
    }

    @Test
    void awaitVerificationReturnsResumedWhenContinueClicked() throws Exception {
        BossRunContext context = new BossRunContext("run-1");
        context.markRunning();
        context.enterVerification("滑块验证");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<VerificationWait> waiting =
                    executor.submit(() -> context.awaitVerification(5_000L));
            // 无论 resume 发生在 await 进入前还是后，结果都必须是 RESUMED
            assertTrue(context.resumeVerification());
            assertEquals(VerificationWait.RESUMED, waiting.get(5, TimeUnit.SECONDS));
            assertEquals(State.RUNNING, context.getState());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void awaitVerificationReturnsTimeoutAndKeepsPaused() throws Exception {
        BossRunContext context = new BossRunContext("run-1");
        context.markRunning();
        context.enterVerification("滑块验证");

        assertEquals(VerificationWait.TIMEOUT, context.awaitVerification(50L));
        assertEquals(State.PAUSED_VERIFICATION, context.getState(),
                "超时只是本时间片无恢复，不能自动改状态");
    }

    @Test
    void awaitVerificationReturnsCancelledWhenStopRequested() throws Exception {
        BossRunContext context = new BossRunContext("run-1");
        context.markRunning();
        context.enterVerification("滑块验证");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<VerificationWait> waiting =
                    executor.submit(() -> context.awaitVerification(5_000L));
            assertTrue(context.requestStop("停止等待"));
            assertEquals(VerificationWait.CANCELLED, waiting.get(5, TimeUnit.SECONDS));
            assertEquals(State.STOPPING, context.getState());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void awaitVerificationOnRunningContextReturnsResumedImmediately() throws Exception {
        BossRunContext context = new BossRunContext("run-1");
        context.markRunning();
        assertEquals(VerificationWait.RESUMED, context.awaitVerification(1_000L),
                "非暂停状态调用等待应立即返回 RESUMED（调用方据此重新检查页面）");
    }

    @Test
    void resumeVerificationReturnsFalseOutsidePausedState() {
        BossRunContext running = new BossRunContext("run-1");
        running.markRunning();
        assertFalse(running.resumeVerification(), "没有暂停时恢复应返回 false");

        running.requestStop("停止");
        assertFalse(running.resumeVerification(), "已请求停止时恢复应返回 false");

        BossRunContext paused = new BossRunContext("run-2");
        paused.markRunning();
        paused.enterVerification("滑块");
        assertTrue(paused.resumeVerification());
        assertFalse(paused.resumeVerification(), "重复恢复应返回 false");
    }

    @Test
    void abandonIsTerminalAndCancelsOldWorkerButKeepsStartPermissionBlocked() {
        BossRunContext context = new BossRunContext("run-1");
        context.markRunning();

        context.abandon("页面完全卡死");
        assertEquals(State.FAILED, context.getState());
        assertTrue(context.isTerminal());
        assertTrue(context.isAbandoned(), "强制复位必须能被收尾逻辑识别，避免覆盖 FAILED");
        assertTrue(context.getError().contains("人工强制复位"));
        assertTrue(context.getError().contains("页面完全卡死"));

        // A-04 关键修复：abandon 必须同时置取消标记，旧线程才能在下一个检查点退出
        assertTrue(context.isStopRequested(), "abandon 必须设置取消标记，否则旧 worker 会继续投递");
        assertTrue(context.isCancelled());
        assertThrows(RunCancelledException.class, context::throwIfCancelled);

        // 终止请求 ≠ worker 退出：旧线程退出前不允许启动新任务
        assertFalse(context.hasExecutionFinished());
        assertFalse(context.isFullyFinished(), "worker 未退出时不得允许新任务启动");
        context.markExecutionFinished();
        assertTrue(context.isFullyFinished());

        // abandon 之后旧线程的收尾不能把状态改回“正常完成”
        context.markCompleted();
        assertEquals(State.FAILED, context.getState(), "终端态一旦写入不得被覆盖");
        assertFalse(context.requestStop("再次停止"));
        assertFalse(context.resumeVerification());
    }

    @Test
    void stopOnOldRunDoesNotAffectNewRun() {
        BossRunContext oldRun = new BossRunContext("run-old");
        BossRunContext newRun = new BossRunContext("run-new");
        oldRun.markRunning();
        newRun.markRunning();

        assertTrue(oldRun.requestStop("停止旧任务"));
        assertEquals(State.STOPPING, oldRun.getState());
        assertEquals(State.RUNNING, newRun.getState(), "停止旧任务不得影响新任务");
        assertFalse(newRun.isStopRequested());
        newRun.throwIfCancelled(); // 不应抛异常

        // 旧任务结束只改自己的状态；且旧任务必须 worker 退出后才释放启动权限
        oldRun.markCompleted();
        assertFalse(oldRun.isFullyFinished());
        oldRun.markExecutionFinished();
        assertTrue(oldRun.isFullyFinished());
        assertEquals(State.RUNNING, newRun.getState());
        newRun.markCompleted();
        assertEquals(State.COMPLETED, newRun.getState());
        assertFalse(newRun.hasExecutionFinished());
    }

    @Test
    void circuitBreakerKeepsFirstReasonAndStopsTask() {
        BossRunContext context = new BossRunContext("run-1");
        context.markRunning();
        context.abortByCircuitBreaker("今日首次沟通预算已用尽");
        assertEquals(State.STOPPING, context.getState());
        assertTrue(context.isStopRequested());
        assertEquals("今日首次沟通预算已用尽", context.getStopReason());

        context.abortByCircuitBreaker("连续失败熔断");
        assertEquals("今日首次沟通预算已用尽", context.getStopReason(),
                "熔断原因应保留第一次的真实原因");

        context.markFailed("连续失败熔断");
        assertTrue(context.isTerminal());
        assertEquals(State.FAILED, context.getState());
    }
}
