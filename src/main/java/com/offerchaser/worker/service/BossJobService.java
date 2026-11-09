package com.offerchaser.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.ai.AiConfigException;
import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DatabaseMaintenanceService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.SchemaMigrationService;
import com.offerchaser.application.persistence.TaskRunRepository;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.ConfigService;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.worker.boss.Boss;
import com.offerchaser.worker.boss.BossConfig;
import com.offerchaser.worker.boss.BossRunContext;
import com.offerchaser.worker.boss.RunCancelledException;
import com.offerchaser.worker.dto.JobProgressMessage;
import com.offerchaser.worker.dto.PipelineSnapshot;
import com.offerchaser.worker.dto.PipelineStageStat;
import com.offerchaser.worker.manager.PlaywrightManager;
import com.offerchaser.application.delivery.RuleSet;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Boss 直聘任务服务。
 * <p>
 * 任务生命周期由 {@link BossRunContext} 明确建模：
 * <ul>
 *   <li>启动时原子占有任务，重复点击只会得到一个 runId；</li>
 *   <li>停止只取消当前 runId，旧任务的 finally 不会复位新任务；</li>
 *   <li>人工验证期间任务进入 PAUSED_VERIFICATION，由管理台恢复；</li>
 *   <li>停止超时不伪造"已停止"，只有任务线程真正退出才结束；必要时提供管理员强制复位逃生口。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossJobService {
    private static final String PLATFORM = "boss";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;
    private final AiService aiService;
    private final AiMatchService aiMatchService;
    private final BudgetService budgetService;
    private final DeliveryLedgerService deliveryLedgerService;
    private final TaskRunRepository taskRunRepository;
    private final SchemaMigrationService schemaMigrationService;
    private final DatabaseMaintenanceService databaseMaintenanceService;
    /** B1：只读查询（任务/评估/投递明细、待人工核对数） */
    private final BossQueryRepository bossQueryRepository;
    /** B2：规则配置（版本化规则快照，本轮任务固定使用） */
    private final RuleConfigService ruleConfigService;
    /** 方向模板：生效模板优先级最高，任务启动前校验并校正当前配置 */
    private final ProfileTemplateService profileTemplateService;
    /** 今日管线快照持久化（用于停止/重启后未启动时仍显示当天累计）；测试构造时可缺省。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DailyPipelineStore dailyPipelineStore;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 停止指令发出后，多久还没退出就提示用户（不自动复位状态） */
    private static final long STOP_WARN_GRACE_MS = 15_000L;

    /** B1：SSE 事件序号，服务实例内全局单调递增（跨轮次也不重置，前端按 seq 去重/丢弃乱序） */
    private final AtomicLong sseSeq = new AtomicLong();

    /** 任务生命周期锁：保证"检查是否能启动 + 占有任务"是原子的 */
    private final Object lifecycleLock = new Object();
    /** 当前（或最近一轮）任务上下文；任务结束后保留，供管理台查看结果 */
    private volatile BossRunContext currentRun;
    private volatile BossRunContext lastRun;
    /** 今日已启动的 run 快照，用于停止/重新开始后仍累计当天的管线。 */
    private final List<BossRunContext> todayRuns = new ArrayList<>();

    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        BossRunContext run;
        synchronized (lifecycleLock) {
            if (isOccupied(currentRun)) {
                // 终止请求 != 线程退出：必须确认旧 worker 真正退出才允许新任务，
                // 否则旧线程的收尾/恢复可能与新任务的数据库与浏览器操作交叠。
                String blockedHint = startBlockedHint(currentRun);
                String statusPrefix = switch (currentRun.getState()) {
                    case STARTING, RUNNING, PAUSED_VERIFICATION -> "当前任务尚未退出（仍在运行）";
                    default -> "旧任务尚未退出";
                };
                JobProgressMessage occupiedWarning = JobProgressMessage.warning(PLATFORM, String.format(
                        "%s（状态 %s，runId=%s），本次启动请求已忽略：%s",
                        statusPrefix, currentRun.getState(), currentRun.getRunId(), blockedHint));
                occupiedWarning.setRunId(currentRun.getRunId());
                occupiedWarning.setSeq(sseSeq.incrementAndGet());
                occupiedWarning.setState(currentRun.getState().name());
                progressCallback.accept(occupiedWarning);
                return;
            }
            run = new BossRunContext(UUID.randomUUID().toString());
            currentRun = run;
            lastRun = run;
            LocalDate today = LocalDate.now();
            todayRuns.removeIf(existing -> !today.equals(runDate(existing)));
            todayRuns.add(run);
        }
        log.info("Boss 任务启动 | runId={} | 状态={}", run.getRunId(), run.getState());

        // B1：每条进度事件带上 runId/seq/state，前端据此丢弃旧任务与乱序事件
        final Consumer<JobProgressMessage> emit = decorateProgress(progressCallback, run);

        String configSnapshot = null;
        try {
            // ===== 预检：数据库、配置、AI、预算、浏览器 =====
            if (!schemaMigrationService.isReady()) {
                throw new IllegalStateException("数据库升级未完成，已阻止启动投递任务，请查看启动日志中的迁移错误");
            }
            emit.accept(JobProgressMessage.info(PLATFORM, "检查任务配置..."));
            // 方向模板优先级最高：启动前把当前配置校正为生效模板快照（一致时无动作）
            try {
                var appliedTemplate = profileTemplateService.ensureActiveApplied();
                if (appliedTemplate != null) {
                    log.warn("启动前已按方向模板校正当前配置 | template={} | warnings={}",
                            appliedTemplate.template().name(), appliedTemplate.warnings());
                    emit.accept(JobProgressMessage.warning(PLATFORM,
                            "已按生效模板「" + appliedTemplate.template().name() + "」校正当前配置："
                                    + String.join("；", appliedTemplate.warnings())));
                }
            } catch (Exception templateError) {
                log.warn("启动前校验方向模板失败，继续使用当前配置：{}", templateError.getMessage());
            }
            BossConfig config = configService.getBossConfig();
            validateRunConfig(config);
            applyTemplateDeliveryPolicy(config);

            // B2：任务启动时固定本轮规则快照；运行中编辑规则只影响下一轮
            RuleSet activeRules;
            try {
                activeRules = ruleConfigService.getActiveRuleSet();
            } catch (Exception ruleError) {
                log.error("加载规则配置失败，阻止任务启动", ruleError);
                throw new IllegalStateException("规则配置加载失败，已阻止启动：" + ruleError.getMessage(), ruleError);
            }
            emit.accept(JobProgressMessage.info(PLATFORM,
                    "规则版本 v" + activeRules.configVersion() + "（hash " + activeRules.sourceHash() + "）"));
            // task_run.config_snapshot 同时记录本轮 BOSS 配置与规则版本，便于事后复现"当时按什么规则投的"
            configSnapshot = safeRunConfigSnapshot(config, activeRules);

            if (Boolean.TRUE.equals(config.getEnableAI())) {
                emit.accept(JobProgressMessage.info(PLATFORM, "检查 AI 配置..."));
                aiMatchService.validateConfiguration(aiService.getAiConfig());
            }

            try {
                java.nio.file.Path backup = databaseMaintenanceService.backup("pre-run");
                if (backup != null) {
                    log.info("任务开始前数据库备份完成: {}", backup);
                }
            } catch (Exception backupError) {
                // 备份失败不阻塞任务，但必须显式告警：这是数据可恢复性的最后一道保险
                log.warn("任务开始前数据库备份失败，继续执行任务: {}", backupError.getMessage());
                emit.accept(JobProgressMessage.warning(PLATFORM,
                        "数据库备份失败，本次任务继续执行：" + backupError.getMessage()));
            }

            int stale = budgetService.reconcileStaleReservations(run.getRunId());
            if (stale > 0) {
                log.warn("发现 {} 条上次未确认的发送记录，已按「结果未知」保守处理并占用今日额度", stale);
                emit.accept(JobProgressMessage.warning(PLATFORM,
                        String.format("发现 %d 条上次未确认的发送记录，已按结果未知处理（占用今日预算），请人工核对会话", stale)));
            }
            if (budgetService.isFirstContactBudgetDepleted()) {
                throw new IllegalStateException("今日首次沟通预算已用尽，不能启动新任务");
            }

            taskRunRepository.start(run.getRunId(), PLATFORM, configSnapshot);

            emit.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            // 浏览器可能已被手动关闭或崩溃，这里会按需重新拉起
            playwrightManager.ensureReady();
            Page page = playwrightManager.getBossPage();
            if (page == null) {
                throw new IllegalStateException("Boss 页面未初始化");
            }
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                throw new IllegalStateException("请先登录 Boss 直聘");
            }

            // ===== 预检通过，正式占用任务 =====
            run.markRunning();
            if (!run.isStopRequested()) {
                taskRunRepository.updateState(run.getRunId(), BossRunContext.State.RUNNING.name(), null, null);
            }

            // 暂停后台登录监控，避免与投递流程并发访问同一个 Page
            playwrightManager.pauseBossMonitoring();
            emit.accept(JobProgressMessage.info(PLATFORM, "配置加载成功，开始投递任务..."));

            Boss.ProgressCallback bossCallback = (message, current, total) -> {
                if (current != null && total != null) {
                    emit.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    emit.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            Boss boss = bossProvider.getObject();
            boss.setPage(page);
            boss.setConfig(config);
            boss.setProgressCallback(bossCallback);
            // 投递流程可视化：阶段事件单独以 type=pipeline 推送，不影响原有文本进度
            boss.setPipelineCallback(event -> emit.accept(JobProgressMessage.pipeline(PLATFORM, event)));
            boss.setRunContext(run);
            boss.setRuleSet(activeRules);
            boss.prepare();

            // 整个投递流程必须跑在 Playwright 专用线程上
            int delivered = playwrightManager.callOnPlaywright(boss::execute);

            run.markCompleted();
            if (run.isAbandoned()) {
                // 管理员已经强制复位并写了 FAILED；旧线程退出时不得把状态覆盖回 COMPLETED
                log.info("任务已被人工强制复位，跳过完成状态写入 | runId={}", run.getRunId());
                return;
            }
            taskRunRepository.finish(run.getRunId(), BossRunContext.State.COMPLETED.name(),
                    run.getStopReason(), null);
            String summary = String.format(
                    "投递任务结束：确认发送 %d 个首次沟通，跟进 %d 个，规则拒绝 %d 个，跳过 %d 个，结果未知 %d 个%s",
                    delivered, run.getFollowupCount(), run.getRuleRejectedCount(),
                    run.getSkippedCount(), run.getUnknownCount(),
                    run.getStopReason() == null ? "" : "（" + run.getStopReason() + "）");
            log.info("{} | runId={}", summary, run.getRunId());
            emit.accept(JobProgressMessage.success(PLATFORM, summary));
        } catch (RunCancelledException cancelled) {
            // 用户停止 / 熔断：属于正常结束，不能记 FAILED
            log.info("Boss 任务已取消 | runId={} | 原因={}", run.getRunId(), cancelled.getMessage());
            run.markCompleted();
            if (run.isAbandoned()) {
                log.info("任务已被人工强制复位，取消收尾不覆盖 task_run 状态 | runId={}", run.getRunId());
                return;
            }
            try {
                taskRunRepository.finish(run.getRunId(), BossRunContext.State.COMPLETED.name(),
                        cancelled.getMessage(), null);
            } catch (Exception ignore) {
                // 状态落库失败不影响内存状态的正确性
            }
            emit.accept(JobProgressMessage.warning(PLATFORM,
                    "投递任务已停止：" + cancelled.getMessage()));
        } catch (AiConfigException aiConfigError) {
            log.error("AI 配置检查失败，任务未启动 | runId={} | {}", run.getRunId(), aiConfigError.getMessage());
            run.markFailed(aiConfigError.getMessage());
            finishTaskRunQuietly(run, BossRunContext.State.FAILED.name(), aiConfigError.getMessage());
            emit.accept(JobProgressMessage.error(PLATFORM,
                    "AI 配置检查失败，任务未启动：" + aiConfigError.getMessage()));
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            log.error("Boss 投递任务执行失败 | runId=" + run.getRunId(), e);
            run.markFailed(message);
            finishTaskRunQuietly(run, BossRunContext.State.FAILED.name(), message);
            emit.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + message));
        } finally {
            // 无论成功、失败、取消还是预检失败，都表示本轮 worker 已经退出：
            // 这是"允许下一轮启动"的唯一依据，也是"旧线程还在跑就不能启动新任务"的保证。
            run.markExecutionFinished();
            try {
                PipelineSnapshot finishedSnapshot = run.pipelineSnapshot();
                if (finishedSnapshot != null && dailyPipelineStore != null) {
                    dailyPipelineStore.save(run.getRunId(), LocalDate.now(), finishedSnapshot.stages(),
                            finishedSnapshot.updatedAt() == null
                                    ? System.currentTimeMillis()
                                    : finishedSnapshot.updatedAt());
                }
            } catch (Exception pipelineSaveError) {
                log.debug("保存今日管线快照失败: {}", pipelineSaveError.getMessage());
            }
            log.info("Boss 任务执行线程已退出 | runId={} | state={}", run.getRunId(), run.getState());
            // 只恢复监控；任务状态已在上面按真实结果落定，这里不做任何"强制复位"
            try {
                playwrightManager.resumeBossMonitoring();
            } catch (Exception ignored) {
            }
        }
    }

    /** 任务是否仍然占用执行器：未结束，或已结束但 worker 线程还没退出。 */
    private static boolean isOccupied(BossRunContext run) {
        return run != null && !run.isFullyFinished();
    }

    /**
     * 不能开始新任务时给用户看的真实原因。
     * <p>
     * 注意：RUNNING/STARTING/PAUSED_VERIFICATION 都表示"当前任务还活着"，不是"旧线程在退出"；
     * 只有 COMPLETED/FAILED 但 worker 尚未退出时才是真正的"旧任务线程仍在退出中"。
     */
    private static String startBlockedHint(BossRunContext run) {
        if (run == null || run.isFullyFinished()) {
            return "任务尚未完全结束，暂不能开始新任务";
        }
        return switch (run.getState()) {
            case STARTING -> "任务正在启动中，请稍候，无需重复开始";
            case RUNNING -> "任务正在运行中，无需重复开始；如需停止请点击「停止」";
            case PAUSED_VERIFICATION -> "任务等待人工安全验证，请在浏览器完成验证后点击「检查并继续」";
            case STOPPING -> "任务正在停止，请等待任务线程退出后再开始新任务";
            case COMPLETED, FAILED -> "旧任务线程仍在退出中，暂不能开始新任务";
        };
    }

    /**
     * 给进度事件补上 runId / seq / state（B1）。
     * seq 为服务实例内全局单调递增（跨轮次不重置）；前端按 runId 过滤旧任务、按 seq 去重/丢弃乱序。
     */
    private Consumer<JobProgressMessage> decorateProgress(Consumer<JobProgressMessage> raw, BossRunContext run) {
        return message -> {
            if (message == null) {
                return;
            }
            message.setRunId(run.getRunId());
            message.setSeq(sseSeq.incrementAndGet());
            message.setState(run.getState().name());
            try {
                raw.accept(message);
            } catch (Exception e) {
                // SSE/回调异常不能影响投递主流程
                log.debug("进度回调异常（已忽略）: {}", e.getMessage());
            }
        };
    }
    private void finishTaskRunQuietly(BossRunContext run, String state, String error) {
        if (run.isAbandoned()) {
            // 强制复位已经写过 FAILED 记录，旧线程不得覆盖
            return;
        }
        try {
            taskRunRepository.finish(run.getRunId(), state, run.getStopReason(), error);
        } catch (Exception statusError) {
            log.warn("任务状态写入 task_run 失败: {}", statusError.getMessage());
        }
    }

    /**
     * 启动前配置检查。配置错误要在任务开始前暴露，而不是跑到一半才因为缺字段抛错。
     */
    private void validateRunConfig(BossConfig config) {
        if (config == null) {
            throw new IllegalStateException("Boss 配置为空，请先在管理台保存配置");
        }
        if (config.getKeywords() == null || config.getKeywords().isEmpty()) {
            throw new IllegalStateException("未配置搜索关键词，无法开始投递");
        }
        if (config.getCityCode() == null || config.getCityCode().isEmpty()) {
            throw new IllegalStateException("未配置城市，无法开始投递");
        }
        if (!Boolean.TRUE.equals(config.getDebugger())
                && (config.getSayHi() == null || config.getSayHi().isBlank())) {
            throw new IllegalStateException("未配置打招呼语（sayHi），非调试模式不允许投递");
        }
    }

    private String safeConfigSnapshot(BossConfig config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(config);
        } catch (Exception e) {
            log.debug("序列化配置快照失败: {}", e.getMessage());
            return null;
        }
    }

    /** D1：生效模板只覆盖采集策略；每日首次沟通上限仍由全局预算共享，不在这里。 */
    private void applyTemplateDeliveryPolicy(BossConfig config) {
        if (config == null) {
            return;
        }
        try {
            var active = profileTemplateService.active().orElse(null);
            if (active == null) {
                return;
            }
            var policy = active.deliveryPolicy();
            if (policy == null) {
                return;
            }
            if (policy.collectMaxPerKeyword() != null) {
                config.setCollectMaxPerKeyword(policy.collectMaxPerKeyword());
            }
            if (policy.collectMaxDurationMinutes() != null) {
                config.setCollectMaxDurationMinutes(policy.collectMaxDurationMinutes());
            }
            if (policy.collectNoNewWindowSeconds() != null) {
                config.setCollectNoNewWindowSeconds(policy.collectNoNewWindowSeconds());
            }
            log.info("应用方向模板采集策略 | template={} | perKeyword={} | duration={} | quiet={}",
                    active.name(), config.getCollectMaxPerKeyword(),
                    config.getCollectMaxDurationMinutes(), config.getCollectNoNewWindowSeconds());
        } catch (Exception e) {
            log.warn("读取方向模板采集策略失败，使用全局采集默认值：{}", e.getMessage());
        }
    }

    /** B2：本轮运行快照 = BOSS 配置 + 固定规则版本/哈希 + 生效方向模板，写 task_run.config_snapshot 便于事后复现。 */
    private String safeRunConfigSnapshot(BossConfig config, RuleSet rules) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("bossConfig", config);
            snapshot.put("rulesVersion", rules.configVersion());
            snapshot.put("rulesHash", rules.sourceHash());
            snapshot.put("highestDegree", rules.highestDegree());
            snapshot.put("salaryStrategy", rules.salaryStrategy() == null ? null : rules.salaryStrategy().name());
            // 阶段 C 收尾：记录本轮使用的方向模板，任务记录/审计可追溯"这轮按哪个方向投的"
            try {
                var activeTemplate = profileTemplateService.active().orElse(null);
                if (activeTemplate != null) {
                    Map<String, Object> template = new LinkedHashMap<>();
                    template.put("id", activeTemplate.id());
                    template.put("name", activeTemplate.name());
                    template.put("direction", activeTemplate.direction());
                    snapshot.put("template", template);
                }
            } catch (Exception templateError) {
                log.debug("读取生效模板用于运行快照失败: {}", templateError.getMessage());
            }
            return OBJECT_MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.debug("序列化运行快照失败: {}", e.getMessage());
            return safeConfigSnapshot(config);
        }
    }

    public void stopDelivery() {
        BossRunContext run = currentRun;
        if (run == null || run.isTerminal()) {
            return;
        }
        if (!run.requestStop("用户取消投递")) {
            return;
        }
        log.info("收到停止 Boss 投递任务请求 | runId={}", run.getRunId());
        try {
            taskRunRepository.updateState(run.getRunId(), BossRunContext.State.STOPPING.name(),
                    "用户取消投递", null);
        } catch (Exception e) {
            log.debug("更新任务状态为 STOPPING 失败: {}", e.getMessage());
        }
        // 只做超时提示，绝不自动复位运行状态（旧实现 15 秒后伪造"已停止"，
        // 结果后台还在点发送，用户以为停了）
        Thread watchdog = new Thread(() -> {
            long deadline = System.currentTimeMillis() + STOP_WARN_GRACE_MS;
            while (!run.isTerminal() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!run.isTerminal()) {
                log.warn("停止指令已发出 {} 秒，任务仍在执行（可能卡在 Playwright / AI 调用中）；"
                                + "不会伪造已停止状态，请等待当前调用超时退出，或人工确认后使用强制复位",
                        STOP_WARN_GRACE_MS / 1000);
            }
        }, "boss-stop-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * 管理台"继续"：恢复因人工验证暂停的任务。
     *
     * @return 是否确实有暂停中的任务被恢复
     */
    public boolean resumeVerification() {
        BossRunContext run = currentRun;
        if (run == null || !run.isVerificationPaused()) {
            return false;
        }
        if (!run.resumeVerification()) {
            return false;
        }
        log.info("人工验证完成，任务继续 | runId={}", run.getRunId());
        try {
            taskRunRepository.updateState(run.getRunId(), BossRunContext.State.RUNNING.name(), null, null);
        } catch (Exception e) {
            log.debug("更新任务状态为 RUNNING 失败: {}", e.getMessage());
        }
        return true;
    }

    /**
     * 管理员强制复位。只用于任务确实卡死、取消长时间无效的场景；
     * 它只改状态，不保证后台 Playwright 操作已经停止，接口与日志都会明确提示。
     */
    public Map<String, Object> forceReset() {
        Map<String, Object> response = new LinkedHashMap<>();
        synchronized (lifecycleLock) {
            BossRunContext run = currentRun;
            if (run == null || run.isTerminal()) {
                response.put("success", false);
                response.put("message", "当前没有运行中的任务，无需强制复位");
                response.put("status", run == null ? "IDLE" : run.getState().name());
                return response;
            }
            run.abandon("管理员强制复位");
            log.warn("人工强制复位 Boss 任务 | runId={} | 已置取消标记，旧线程会在下一个检查点退出；"
                    + "若仍无响应请关闭浏览器后重启程序", run.getRunId());
            // 强制复位本身就是本次收尾，直接写 task_run；不走 finishTaskRunQuietly（它会因 abandoned 跳过）
            try {
                taskRunRepository.finish(run.getRunId(), BossRunContext.State.FAILED.name(),
                        run.getStopReason(), run.getError());
            } catch (Exception e) {
                log.warn("强制复位写入 task_run 失败: {}", e.getMessage());
            }
            response.put("success", true);
            response.put("message", "已通知旧任务取消并写入 FAILED。旧任务线程退出前不会允许开始新任务；"
                    + "若长时间仍无法退出，请关闭浏览器并重启程序");
            response.put("status", BossRunContext.State.FAILED.name());
            response.put("workerExited", run.hasExecutionFinished());
            return response;
        }
    }

    /** 当前（或最近一轮）任务 runId；没有任务时返回 null。 */
    public String getCurrentRunId() {
        BossRunContext run = currentRun;
        return run == null ? null : run.getRunId();
    }

    /** 校验操作指定的 runId 是否与当前任务一致；expectedRunId 为空表示不校验。 */
    public boolean matchesRunId(String expectedRunId) {
        if (expectedRunId == null || expectedRunId.isBlank()) {
            return true;
        }
        BossRunContext run = currentRun;
        return run != null && expectedRunId.equals(run.getRunId());
    }

    private Map<String, Object> runIdMismatch() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("code", "RUN_ID_MISMATCH");
        response.put("message", "runId 与当前任务不一致，请刷新后重试");
        response.put("currentRunId", getCurrentRunId());
        return response;
    }

    /** B1：绑定 runId 的停止请求（防止旧页面误停新任务）。 */
    public Map<String, Object> requestStop(String expectedRunId) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!matchesRunId(expectedRunId)) {
            return runIdMismatch();
        }
        BossRunContext run = currentRun;
        if (run == null || run.isFullyFinished()) {
            response.put("success", false);
            response.put("code", "NOT_RUNNING");
            response.put("message", "没有正在运行的任务");
            response.put("currentRunId", getCurrentRunId());
            return response;
        }
        stopDelivery();
        response.put("success", true);
        response.put("message", "停止请求已发送，等待任务线程退出");
        response.put("runId", run.getRunId());
        response.put("state", run.getState().name());
        return response;
    }

    /** B1：绑定 runId 的验证恢复请求。 */
    public Map<String, Object> requestResumeVerification(String expectedRunId) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!matchesRunId(expectedRunId)) {
            return runIdMismatch();
        }
        boolean resumed = resumeVerification();
        response.put("success", resumed);
        if (resumed) {
            response.put("message", "已恢复因人工验证暂停的任务");
        } else {
            response.put("code", "NOT_PAUSED");
            response.put("message", "当前没有因人工验证而暂停的任务");
        }
        response.put("runId", getCurrentRunId());
        return response;
    }

    /** B1：绑定 runId 的强制复位请求。 */
    public Map<String, Object> requestForceReset(String expectedRunId) {
        if (!matchesRunId(expectedRunId)) {
            return runIdMismatch();
        }
        return forceReset();
    }
    private static LocalDate runDate(BossRunContext run) {
        return Instant.ofEpochMilli(run.getStartedAtMillis())
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
    }

    /** 今日累计管线：同日多轮 run + 已落盘快照的阶段计数相加；当前/最后 run 提供当前阶段与最近事件。 */
    private PipelineSnapshot dailyPipelineSnapshot(BossRunContext activeRun) {
        LocalDate today = LocalDate.now();
        List<BossRunContext> runs;
        synchronized (lifecycleLock) {
            todayRuns.removeIf(existing -> !today.equals(runDate(existing)));
            runs = new ArrayList<>(todayRuns);
        }
        BossRunContext latest = activeRun != null ? activeRun : lastRun;
        // 跨午夜后，lastRun 仍是昨天那一轮；不能把它当成“今日管线”继续展示。
        if (latest != null && !today.equals(runDate(latest))) {
            latest = null;
        }
        List<DailyPipelineStore.StoredRun> storedRuns = dailyPipelineStore == null
                ? List.of() : dailyPipelineStore.load(today);
        if (runs.isEmpty() && storedRuns.isEmpty()) {
            return null;
        }
        Map<String, PipelineStageStat> merged = new LinkedHashMap<>();
        java.util.Set<String> mergedRunIds = new java.util.HashSet<>();
        for (DailyPipelineStore.StoredRun stored : storedRuns) {
            if (stored == null || stored.stages() == null) continue;
            mergedRunIds.add(stored.runId());
            mergeStages(merged, stored.stages());
        }
        for (BossRunContext candidate : runs) {
            PipelineSnapshot snapshot = candidate.pipelineSnapshot();
            if (snapshot == null || (snapshot.runId() != null && mergedRunIds.contains(snapshot.runId()))) {
                continue;
            }
            mergeStages(merged, snapshot.stages());
        }
        PipelineSnapshot latestSnapshot = latest == null ? null : latest.pipelineSnapshot();
        return new PipelineSnapshot(
                latestSnapshot == null ? null : latestSnapshot.runId(),
                latestSnapshot == null ? null : latestSnapshot.currentJob(),
                latestSnapshot == null ? null : latestSnapshot.currentCompany(),
                latestSnapshot == null ? null : latestSnapshot.currentStage(),
                latestSnapshot == null ? null : latestSnapshot.currentStageLabel(),
                latestSnapshot == null ? null : latestSnapshot.updatedAt(),
                new ArrayList<>(merged.values()),
                latestSnapshot == null ? List.of() : latestSnapshot.recent());
    }

    private static void mergeStages(Map<String, PipelineStageStat> merged, List<PipelineStageStat> stages) {
        if (stages == null) return;
        for (PipelineStageStat stat : stages) {
            if (stat == null) continue;
            PipelineStageStat current = merged.get(stat.stage());
            if (current == null) {
                merged.put(stat.stage(), new PipelineStageStat(stat.stage(), stat.label(),
                        stat.entered(), new java.util.TreeMap<>(stat.outcomes())));
            } else {
                Map<String, Integer> outcomes = new java.util.TreeMap<>(current.outcomes());
                stat.outcomes().forEach((outcome, count) -> outcomes.merge(outcome, count, Integer::sum));
                merged.put(stat.stage(), new PipelineStageStat(stat.stage(), stat.label(),
                        current.entered() + stat.entered(), outcomes));
            }
        }
    }


    public Map<String, Object> getStatus() {
        BossRunContext run = currentRun;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning());
        status.put("isLoggedIn", safeLoggedIn());
        status.put("state", run == null ? "IDLE" : run.getState().name());
        status.put("runId", run == null ? null : run.getRunId());
        status.put("workerExited", run == null || run.hasExecutionFinished());
        status.put("canStart", !isOccupied(run));
        status.put("workerExitHint", run == null || run.isFullyFinished() ? null : startBlockedHint(run));
        status.put("startedAt", run == null ? null : run.getStartedAtMillis());
        status.put("runningSeconds", runningForMillis() / 1000);
        status.put("stopRequested", run != null && run.isStopRequested());
        status.put("verificationPaused", run != null && run.isVerificationPaused());
        status.put("verificationReason", run == null ? null : run.getVerificationReason());
        status.put("stopReason", run == null ? null : run.getStopReason());
        status.put("error", run == null ? null : run.getError());
        status.put("deliveredCount", run == null ? 0 : run.getDeliveredCount());
        status.put("followupCount", run == null ? 0 : run.getFollowupCount());
        status.put("ruleRejectedCount", run == null ? 0 : run.getRuleRejectedCount());
        status.put("skippedCount", run == null ? 0 : run.getSkippedCount());
        status.put("unknownCount", run == null ? 0 : run.getUnknownCount());
        try {
            status.put("pipeline", dailyPipelineSnapshot(run));
        } catch (Exception e) {
            status.put("pipeline", null);
            log.debug("读取投递流程快照失败: {}", e.getMessage());
        }
        try {
            status.put("ruleVersion", ruleConfigService.currentVersion());
        } catch (Exception e) {
            status.put("ruleVersion", 0);
        }
        try {
            status.put("pendingResolutions", bossQueryRepository.countPendingResolutions());
        } catch (Exception e) {
            status.put("pendingResolutions", 0);
            log.debug("读取待人工核对数量失败: {}", e.getMessage());
        }
        try {
            status.put("budget", budgetService.todaySummary());
        } catch (Exception e) {
            status.put("budget", Map.of("error", "预算信息读取失败: " + e.getMessage()));
        }
        try {
            status.put("delivery", deliveryLedgerService.todaySummary());
        } catch (Exception e) {
            status.put("delivery", Map.of("error", "投递台账读取失败: " + e.getMessage()));
        }
        return status;
    }

    private boolean safeLoggedIn() {
        try {
            return playwrightManager.isLoggedIn(PLATFORM);
        } catch (Exception e) {
            return false;
        }
    }

    public String getPlatformName() {
        return PLATFORM;
    }

    public boolean isRunning() {
        // 注意：已 FAILED 但 worker 还没退出时仍视为"占用"，否则会允许新任务与旧线程并存
        return isOccupied(currentRun);
    }

    /** 任务已经跑了多久（毫秒）；没在跑返回上一轮耗时或 0 */
    public long runningForMillis() {
        BossRunContext run = currentRun;
        if (run == null) {
            run = lastRun;
        }
        if (run == null) {
            return 0L;
        }
        long end = run.isTerminal() ? run.getFinishedAtMillis() : System.currentTimeMillis();
        if (end <= 0L) {
            end = System.currentTimeMillis();
        }
        return Math.max(0L, end - run.getStartedAtMillis());
    }

    /**
     * 兼容旧调用：Boss.java 内部已改用 {@link BossRunContext#isStopRequested()}。
     */
    public boolean shouldStop() {
        BossRunContext run = currentRun;
        return run != null && run.isStopRequested();
    }
}
