package com.offerchaser.worker.boss;

import com.offerchaser.worker.dto.PipelineEvent;
import com.offerchaser.worker.dto.PipelineSnapshot;
import com.offerchaser.worker.dto.PipelineStage;
import com.offerchaser.worker.dto.PipelineStageStat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单轮 Boss 投递任务的运行上下文。
 * <p>
 * 每个 runId 一个实例，替代原来 BossJobService 与 Boss 共享的两个 volatile boolean：
 * 状态、取消标记、人工验证暂停闸门、本轮计数都收敛在这里，谁也不能把别人的任务状态改掉。
 * <p>
 * 状态机：
 * <pre>
 * STARTING → RUNNING ⇄ PAUSED_VERIFICATION
 *     ↓          ↓
 *   FAILED    STOPPING → COMPLETED / FAILED
 * </pre>
 * 取消只作用于本实例；旧任务结束时只会改自己的状态，不会影响下一轮任务。
 */
public class BossRunContext {

    /** 任务状态。用枚举替代隐式布尔组合，管理台可以直接展示原因。 */
    public enum State { STARTING, RUNNING, PAUSED_VERIFICATION, STOPPING, COMPLETED, FAILED }

    /** 人工验证暂停的等待结果。 */
    public enum VerificationWait { RESUMED, CANCELLED, TIMEOUT }

    private final String runId;
    private final long startedAtMillis = System.currentTimeMillis();
    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean abandoned = new AtomicBoolean(false);
    /** 执行器（worker 线程）是否已经真正退出：强制复位后必须等它为 true 才能启动新任务 */
    private final AtomicBoolean executionFinished = new AtomicBoolean(false);
    private final Object gate = new Object();

    private volatile String stopReason;
    private volatile String verificationReason;
    private volatile String error;
    private volatile long finishedAtMillis;

    // 本轮统计：只用于日志与状态展示，不参与每日预算（预算在数据库里持久化）
    private final AtomicInteger deliveredCount = new AtomicInteger();
    private final AtomicInteger followupCount = new AtomicInteger();
    private final AtomicInteger rejectedCount = new AtomicInteger();
    private final AtomicInteger skippedCount = new AtomicInteger();
    private final AtomicInteger unknownCount = new AtomicInteger();
    private final AtomicInteger ruleRejectedCount = new AtomicInteger();

    // ==================== 投递流程可视化（B 阶段之后新增） ====================
    /** 同一岗位进入过的阶段：jobKey -> stage names，保证 entered 每岗位每阶段只计一次 */
    private final Map<String, Set<String>> pipelineSeen = new ConcurrentHashMap<>();
    /** 阶段结果计数：stage -> outcome -> count（RUNNING 不计） */
    private final Map<String, Map<String, AtomicInteger>> pipelineOutcomes = new ConcurrentHashMap<>();
    /** 最近事件（倒序，用于实时流水） */
    private final Deque<PipelineEvent> pipelineRecent = new ConcurrentLinkedDeque<>();
    private volatile PipelineEvent currentPipeline;
    private static final int PIPELINE_RECENT_LIMIT = 80;

    public BossRunContext(String runId) {
        this.runId = runId;
    }

    public String getRunId() {
        return runId;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getFinishedAtMillis() {
        return finishedAtMillis;
    }

    public State getState() {
        return state.get();
    }

    /** 任务是否已经结束（无论成功失败），结束的任务不再响应停止指令。 */
    public boolean isTerminal() {
        State current = state.get();
        return current == State.COMPLETED || current == State.FAILED;
    }

    /** 浏览器与配置检查通过，进入正式投递。 */
    public void markRunning() {
        state.compareAndSet(State.STARTING, State.RUNNING);
    }

    /**
     * 请求停止当前任务。
     *
     * @return 是否真的发出了停止指令（任务已结束或已经停止过则返回 false）
     */
    public boolean requestStop(String reason) {
        if (isTerminal()) {
            return false;
        }
        synchronized (gate) {
            if (isTerminal()) {
                return false;
            }
            stopReason = reason == null || reason.isBlank() ? "用户取消投递" : reason;
            stopRequested.set(true);
            state.set(State.STOPPING);
            gate.notifyAll();
        }
        return true;
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    public String getStopReason() {
        return stopReason;
    }

    /** 取消检查：命中就抛出取消异常，由任务主循环在岗位/关键词边界统一收尾。 */
    public void throwIfCancelled() {
        if (stopRequested.get()) {
            throw new RunCancelledException(stopReason == null ? "用户取消投递" : stopReason);
        }
    }

    /** 非异常式取消检查，适合判断后需要做资源清理的场景。 */
    public boolean isCancelled() {
        return stopRequested.get();
    }

    /** 熔断（额度耗尽、连续失败等）也走同一套取消通道，但原因单独记录。 */
    public void abortByCircuitBreaker(String reason) {
        if (isTerminal()) {
            return;
        }
        synchronized (gate) {
            failureReason(reason);
            stopRequested.set(true);
            state.set(State.STOPPING);
            gate.notifyAll();
        }
    }

    private void failureReason(String reason) {
        if (this.stopReason == null) {
            this.stopReason = reason;
        }
    }

    // ==================== 人工验证暂停 ====================

    /**
     * 进入人工验证暂停。Boss 线程会在这里阻塞，直到管理台点"继续"、任务被取消或超时。
     */
    public void enterVerification(String reason) {
        throwIfCancelled();
        verificationReason = reason == null || reason.isBlank() ? "需要人工完成 Boss 安全验证" : reason;
        state.compareAndSet(State.RUNNING, State.PAUSED_VERIFICATION);
        synchronized (gate) {
            gate.notifyAll();
        }
    }

    public boolean isVerificationPaused() {
        return state.get() == State.PAUSED_VERIFICATION;
    }

    public String getVerificationReason() {
        return verificationReason;
    }

    /**
     * 恢复被暂停的任务（管理台"继续"按钮）。
     *
     * @return 是否确实从暂停状态恢复
     */
    public boolean resumeVerification() {
        if (stopRequested.get()) {
            return false;
        }
        synchronized (gate) {
            if (state.get() != State.PAUSED_VERIFICATION) {
                return false;
            }
            verificationReason = null;
            state.set(State.RUNNING);
            gate.notifyAll();
            return true;
        }
    }

    /**
     * 暂停等待一个时间片。调用方（Boss）在循环里反复调用，每个时间片都重新检查页面 URL，
     * 这样即使人工在浏览器里直接拖完滑块、没点管理台按钮，也能自动继续。
     *
     * @param waitMillis 本次最多等待毫秒数
     * @return RESUMED=已恢复；CANCELLED=任务被取消；TIMEOUT=本时间片内无人恢复
     */
    public VerificationWait awaitVerification(long waitMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMillis);
        synchronized (gate) {
            while (true) {
                if (stopRequested.get()) {
                    return VerificationWait.CANCELLED;
                }
                if (state.get() != State.PAUSED_VERIFICATION) {
                    return VerificationWait.RESUMED;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return VerificationWait.TIMEOUT;
                }
                gate.wait(remaining);
            }
        }
    }

    // ==================== 结束 ====================

    /** 正常结束（含用户主动停止后正常退出）。 */
    public void markCompleted() {
        synchronized (gate) {
            if (isTerminal()) {
                return;
            }
            verificationReason = null;
            finishedAtMillis = System.currentTimeMillis();
            state.set(State.COMPLETED);
            gate.notifyAll();
        }
    }

    /** 异常结束。error 会保留给管理台展示。 */
    public void markFailed(String reason) {
        synchronized (gate) {
            if (isTerminal()) {
                return;
            }
            error = reason;
            verificationReason = null;
            finishedAtMillis = System.currentTimeMillis();
            state.set(State.FAILED);
            gate.notifyAll();
        }
    }

    /**
     * 人工强制复位：任务确实卡死、无法通过取消退出时，由管理员显式操作。
     * <p>
     * 除了把状态置为 FAILED 之外，还必须置上取消标记：
     * 否则旧任务线程看不到停止信号，可能在"已复位"之后继续投递；处于人工验证暂停的
     * 旧线程也会因为状态不再是 PAUSED_VERIFICATION 而反复空转。置上取消标记后，
     * 旧线程会在下一个取消检查点抛出 {@link RunCancelledException} 并退出。
     */
    public void abandon(String reason) {
        synchronized (gate) {
            abandoned.set(true);
            stopRequested.set(true);
            if (stopReason == null) {
                stopReason = "人工强制复位";
            }
            error = "人工强制复位：" + (reason == null ? "未填写原因" : reason);
            verificationReason = null;
            finishedAtMillis = System.currentTimeMillis();
            state.set(State.FAILED);
            gate.notifyAll();
        }
    }

    /** 是否是被管理员强制复位终止的任务（用于收尾时避免覆盖真实状态）。 */
    public boolean isAbandoned() {
        return abandoned.get();
    }

    /**
     * 标记执行器（worker 线程）已经真正退出。
     * <p>
     * 终止请求（取消/强制复位）与实际退出是两件事：旧线程可能还卡在某个 Playwright 调用里。
     * 只有 worker 退出后，才允许启动新任务，也才允许把"遗留 PENDING 恢复"交给下一轮执行。
     */
    public void markExecutionFinished() {
        executionFinished.set(true);
    }

    /** 执行器是否已经退出。预检失败、未进入 worker 的任务也会在 finally 里标记。 */
    public boolean hasExecutionFinished() {
        return executionFinished.get();
    }

    /** 任务是否已结束且执行器已退出（= 可以安全启动新任务）。 */
    public boolean isFullyFinished() {
        return isTerminal() && executionFinished.get();
    }

    public String getError() {
        return error;
    }

    // ==================== 本轮统计 ====================

    public int getDeliveredCount() {
        return deliveredCount.get();
    }

    public void recordDelivered() {
        deliveredCount.incrementAndGet();
    }

    public int getFollowupCount() {
        return followupCount.get();
    }

    public void recordFollowup() {
        followupCount.incrementAndGet();
    }

    public int getRejectedCount() {
        return rejectedCount.get();
    }

    public void recordRejected() {
        rejectedCount.incrementAndGet();
    }

    public int getRuleRejectedCount() {
        return ruleRejectedCount.get();
    }

    public void recordRuleRejected() {
        ruleRejectedCount.incrementAndGet();
    }

    public int getSkippedCount() {
        return skippedCount.get();
    }

    public void recordSkipped() {
        skippedCount.incrementAndGet();
    }

    public int getUnknownCount() {
        return unknownCount.get();
    }

    public void recordUnknown() {
        unknownCount.incrementAndGet();
    }

    // ==================== 投递流程可视化 ====================

    /**
     * 记录一个流程事件：同岗位同阶段只计一次 entered；非 RUNNING 结果累加 outcome 计数；
     * 同时保留最近事件供管理台实时流水展示。
     */
    public synchronized void recordPipeline(PipelineEvent event) {
        if (event == null) {
            return;
        }
        String jobName = event.jobName() == null ? "(未知岗位)" : event.jobName();
        String company = event.companyName() == null ? "" : event.companyName();
        String jobKey = jobName + "@@" + company;
        pipelineSeen.computeIfAbsent(jobKey, key -> ConcurrentHashMap.newKeySet())
                .add(event.stage());
        if (!event.isRunning() && event.outcome() != null && !event.outcome().isBlank()) {
            pipelineOutcomes.computeIfAbsent(event.stage(), key -> new ConcurrentHashMap<>())
                    .computeIfAbsent(event.outcome(), key -> new AtomicInteger())
                    .incrementAndGet();
        }
        pipelineRecent.addFirst(event);
        while (pipelineRecent.size() > PIPELINE_RECENT_LIMIT) {
            pipelineRecent.pollLast();
        }
        currentPipeline = event;
    }

    /** 当前流程快照：阶段计数 + 最近事件 + 当前岗位/阶段。 */
    public synchronized PipelineSnapshot pipelineSnapshot() {
        List<PipelineStageStat> stages = new ArrayList<>();
        for (PipelineStage stage : PipelineStage.values()) {
            int entered = 0;
            for (Set<String> seen : pipelineSeen.values()) {
                if (seen.contains(stage.name())) {
                    entered++;
                }
            }
            Map<String, Integer> outcomes = new TreeMap<>();
            Map<String, AtomicInteger> counters = pipelineOutcomes.get(stage.name());
            if (counters != null) {
                counters.forEach((outcome, counter) -> outcomes.put(outcome, counter.get()));
            }
            stages.add(new PipelineStageStat(stage.name(), stage.getLabel(), entered, outcomes));
        }
        List<PipelineEvent> recent = new ArrayList<>(pipelineRecent);
        if (recent.size() > 30) {
            recent = recent.subList(0, 30);
        }
        PipelineEvent current = currentPipeline;
        return new PipelineSnapshot(runId,
                current == null ? null : current.jobName(),
                current == null ? null : current.companyName(),
                current == null ? null : current.stage(),
                current == null ? null : current.stageLabel(),
                current == null ? null : current.timestamp(),
                stages, recent);
    }
}
