package com.offerchaser.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.service.CookieService;
import com.offerchaser.worker.dto.JobProgressMessage;
import com.offerchaser.worker.manager.PlaywrightManager;
import com.offerchaser.worker.service.BossJobService;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * BOSS 投递任务的 HTTP / SSE 入口（{@code /api/boss}）。
 * <p>
 * 路由分组：
 * <ul>
 *   <li>任务控制：{@code POST /start}、{@code /execute}、{@code /stop}、{@code /force-reset}、
 *       {@code /verification/resume}；</li>
 *   <li>状态与汇总：{@code GET /status}、{@code GET /delivery/summary}；</li>
 *   <li>进度推送：{@code GET /stream}（SSE，connected → snapshot 快照 → progress 增量）与 30s 心跳；</li>
 *   <li>账号：{@code POST /logout}。</li>
 * </ul>
 * 认证/CSRF 由 {@code AuthInterceptor} 统一处理，本类不做也不应做鉴权判断。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss")
public class BossController {

    private static final String PLATFORM = "boss";
    private static final long SSE_HEARTBEAT_MS = 30_000L;
    /** Tomcat 关闭连接时抛出的异常文案（中文 JDK / Windows 环境）。 */
    private static final String CLIENT_ABORT_MESSAGE = "中止了一个已建立的连接";
    private static final String CONNECTED_MESSAGE = "已连接到Boss投递进度推送";
    /** SSE/快照共用的 JSON 序列化器（无状态，只用于出站事件）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BossJobService bossJobService;
    private final PlaywrightManager playwrightManager;
    private final CookieService cookieService;
    private final BudgetService budgetService;
    private final DeliveryLedgerService deliveryLedgerService;
    private final EvaluationRepository evaluationRepository;

    /** SSE 客户端集合；CopyOnWriteArrayList 允许广播遍历与连接增删并发。 */
    private final List<SseEmitter> progressEmitters = new CopyOnWriteArrayList<>();

    public BossController(BossJobService bossJobService,
                          PlaywrightManager playwrightManager,
                          CookieService cookieService,
                          BudgetService budgetService,
                          DeliveryLedgerService deliveryLedgerService,
                          EvaluationRepository evaluationRepository) {
        this.bossJobService = bossJobService;
        this.playwrightManager = playwrightManager;
        this.cookieService = cookieService;
        this.budgetService = budgetService;
        this.deliveryLedgerService = deliveryLedgerService;
        this.evaluationRepository = evaluationRepository;
    }

    // ==================== SSE 进度推送 ====================

    /**
     * SSE 进度流。连接建立后先发 {@code connected}，紧接着发一次完整状态快照
     * {@code snapshot}（前端刷新/重连时先用快照重建页面），后续由任务回调发 {@code progress}。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBossProgress() {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不设超时，由客户端断开或服务关闭结束
        progressEmitters.add(emitter);
        registerEmitterCallbacks(emitter);

        if (!sendConnectedEvent(emitter)) {
            return emitter;
        }
        sendStatusSnapshot(emitter);
        return emitter;
    }

    /** 注册连接生命周期回调：完成/超时只从集合移除，错误额外记日志。 */
    private void registerEmitterCallbacks(SseEmitter emitter) {
        emitter.onCompletion(() -> {
            log.info("Boss进度SSE连接已完成");
            progressEmitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.info("Boss进度SSE连接超时");
            progressEmitters.remove(emitter);
        });
        emitter.onError(error -> {
            if (isClientGone(error)) {
                log.debug("Boss进度SSE连接已断开（客户端关闭）: {}", error.getMessage());
            } else {
                log.error("Boss进度SSE连接错误", error);
            }
            progressEmitters.remove(emitter);
        });
    }

    /** 发送建连提示；失败说明连接已不可用，返回 false 表示不再尝试后续事件。 */
    private boolean sendConnectedEvent(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("message", CONNECTED_MESSAGE)));
            return true;
        } catch (IOException e) {
            log.error("发送SSE连接消息失败", e);
            return false;
        }
    }

    /** 连接后补一帧状态快照；读取/序列化失败只告警，不发送半截事件。 */
    private void sendStatusSnapshot(SseEmitter emitter) {
        try {
            String snapshotJson = JSON.writeValueAsString(bossJobService.getStatus());
            emitter.send(SseEmitter.event().name("snapshot").data(snapshotJson));
        } catch (Exception snapshotError) {
            log.warn("发送Boss状态快照失败: {}", snapshotError.getMessage());
        }
    }

    // ==================== 任务启动 / 停止 / 退出 ====================

    /** 旧版启动入口：不做登录预检，直接异步跑任务（保留兼容）。 */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeBoss() {
        if (bossJobService.isRunning()) {
            return ResponseEntity.ok(taskResult("already_running", "Boss投递任务已在运行中"));
        }

        CompletableFuture.runAsync(() -> bossJobService.executeDelivery(this::sendBossProgress));
        return ResponseEntity.ok(taskResult("started", "Boss投递任务已启动"));
    }

    /** 管理台启动入口：先校验登录与运行状态，再异步启动任务。 */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startBoss() {
        try {
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                return ResponseEntity.badRequest().body(startFailure("请先登录Boss直聘", "not_logged_in"));
            }

            if (bossJobService.isRunning()) {
                long runningSeconds = bossJobService.runningForMillis() / 1000;
                String message = String.format(
                        "Boss任务已在运行中（已运行 %d 分 %d 秒）。如果长时间没有进展，先点\"停止投递\"再重新开始",
                        runningSeconds / 60, runningSeconds % 60);
                Map<String, Object> response = startFailure(message, "running");
                response.put("runningSeconds", runningSeconds);
                return ResponseEntity.badRequest().body(response);
            }

            CompletableFuture.runAsync(() -> bossJobService.executeDelivery(progress -> {
                sendBossProgress(progress);
                log.info("[{}] {}", progress.getPlatform(), progress.getMessage());
            }));

            log.info("通过API启动Boss任务成功");
            return ResponseEntity.ok(Map.of("success", true,
                    "message", "Boss任务启动成功",
                    "status", "started"));
        } catch (Exception startError) {
            log.error("启动Boss任务失败", startError);
            Map<String, Object> response = startFailure("启动Boss任务失败: " + startError.getMessage(), null);
            response.put("error", startError.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 停止当前任务。
     *
     * @param runId 可选：前端传当前 runId，任务已切换时后端返回 409，避免旧页面误停新任务
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopBoss(
            @RequestParam(value = "runId", required = false) String runId) {
        try {
            Map<String, Object> result = bossJobService.requestStop(runId);
            String code = resultCode(result);
            if ("RUN_ID_MISMATCH".equals(code)) {
                return ResponseEntity.status(409).body(result);
            }
            if ("NOT_RUNNING".equals(code)) {
                return ResponseEntity.badRequest().body(result);
            }
            if (result != null && Boolean.TRUE.equals(result.get("success"))) {
                log.info("通过API停止Boss任务 | runId={}", runId);
            }
            return ResponseEntity.ok(result);
        } catch (Exception stopError) {
            log.error("停止Boss任务失败", stopError);
            return ResponseEntity.internalServerError()
                    .body(operationFailure("停止Boss任务失败: " + stopError.getMessage()));
        }
    }

    /** 退出 BOSS 登录：清数据库 Cookie 与浏览器上下文 Cookie。 */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logoutBoss() {
        try {
            playwrightManager.setLoginStatus(PLATFORM, false);
            cookieService.clearCookieByPlatform(PLATFORM, "manual logout");
            try {
                playwrightManager.clearBossCookies();
            } catch (Exception contextError) {
                log.warn("清理Boss上下文Cookie异常: {}", contextError.getMessage());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Boss已退出登录，数据库Cookie和上下文Cookie均已清理");
            return ResponseEntity.ok(response);
        } catch (Exception logoutError) {
            log.error("退出登录失败", logoutError);
            return ResponseEntity.internalServerError()
                    .body(operationFailure("退出登录失败: " + logoutError.getMessage()));
        }
    }

    // ==================== 状态 / 验证恢复 / 强制复位 ====================

    /** 当前任务状态（原始状态 map，无 success/data 外壳）。 */
    @GetMapping(value = "/status")
    public ResponseEntity<Map<String, Object>> getBossStatus() {
        return ResponseEntity.ok(bossJobService.getStatus());
    }

    /**
     * 人工完成 BOSS 安全验证后，恢复被暂停的任务。
     * runId 可放 query，也可放 JSON body（兼容前端两种传法）；不传则操作当前任务。
     */
    @PostMapping("/verification/resume")
    public ResponseEntity<Map<String, Object>> resumeVerification(
            @RequestParam(value = "runId", required = false) String runId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            String effectiveRunId = resolveRunId(runId, body);
            Map<String, Object> result = bossJobService.requestResumeVerification(effectiveRunId);

            String code = resultCode(result);
            if ("RUN_ID_MISMATCH".equals(code)) {
                return ResponseEntity.status(409).body(result);
            }
            if ("NOT_PAUSED".equals(code)) {
                return ResponseEntity.badRequest().body(result);
            }
            log.info("人工验证恢复接口调用，恢复结果: {} | runId={}",
                    result == null ? null : result.get("success"), effectiveRunId);
            return ResponseEntity.ok(result);
        } catch (Exception resumeError) {
            log.error("恢复人工验证暂停任务失败", resumeError);
            Map<String, Object> response = operationFailure("恢复任务失败: " + resumeError.getMessage());
            response.put("status", null);
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 强制复位任务状态。
     * <p>
     * 必须显式携带 {@code confirm=true}——该操作只改任务状态，不保证后台 Playwright 操作已经停止，
     * 所以把确认放在接口层，避免误触。
     */
    @PostMapping("/force-reset")
    public ResponseEntity<Map<String, Object>> forceReset(
            @RequestParam(value = "confirm", required = false, defaultValue = "false") boolean confirm,
            @RequestParam(value = "runId", required = false) String runId) {
        if (!confirm) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("message", "强制复位需要显式确认：请携带 confirm=true");
            response.put("status", "confirmation_required");
            return ResponseEntity.badRequest().body(response);
        }
        try {
            Map<String, Object> result = bossJobService.requestForceReset(runId);
            if (result == null) {
                result = new LinkedHashMap<>();
                result.put("success", false);
                result.put("message", "强制复位未返回结果");
            }
            if ("RUN_ID_MISMATCH".equals(resultCode(result))) {
                return ResponseEntity.status(409).body(result);
            }
            return ResponseEntity.ok(result);
        } catch (Exception resetError) {
            log.error("强制复位Boss任务失败", resetError);
            Map<String, Object> response = operationFailure("强制复位失败: " + resetError.getMessage());
            response.put("status", null);
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 今日投递汇总：预算 / 投递台账 / 评估记录三块。
     * 三个来源相互独立，单个失败只在该块里返回 error，不拖垮整个接口。
     */
    @GetMapping("/delivery/summary")
    public ResponseEntity<Map<String, Object>> getDeliverySummary() {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("budget", readSummary(budgetService::todaySummary, "预算"));
            data.put("attempts", readSummary(deliveryLedgerService::todaySummary, "投递台账"));
            data.put("evaluations", readSummary(evaluationRepository::summaryToday, "评估记录"));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception summaryError) {
            log.error("获取投递汇总失败", summaryError);
            return ResponseEntity.internalServerError()
                    .body(operationFailure("获取投递汇总失败: " + summaryError.getMessage()));
        }
    }

    /** 读取单个汇总，异常降级为带 error 文案的 map。 */
    private Map<String, Object> readSummary(Supplier<Map<String, Object>> supplier, String label) {
        try {
            Map<String, Object> summary = supplier.get();
            return summary != null ? summary : Map.of();
        } catch (Exception readError) {
            log.error("读取{}汇总失败", label, readError);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("error", label + "汇总读取失败: " + readError.getMessage());
            return fallback;
        }
    }

    // ==================== 进度广播 / 心跳 ====================

    /** 把一条任务进度广播给所有存活的 SSE 连接。 */
    private void sendBossProgress(JobProgressMessage message) {
        String payload;
        try {
            payload = JSON.writeValueAsString(message);
        } catch (Exception serializeError) {
            log.error("序列化Boss进度消息失败", serializeError);
            return;
        }
        broadcast("progress", payload);
    }

    /** 心跳：定时 ping 所有连接，同时借机清理已断开的连接。 */
    @Scheduled(fixedRate = SSE_HEARTBEAT_MS)
    public void heartbeatBossProgress() {
        if (progressEmitters.isEmpty()) {
            return;
        }
        broadcast("ping", "keep-alive");
    }

    /**
     * 向所有连接发送一个 SSE 事件，并在遍历中收集/移除已断开的连接。
     * 断开判定与旧实现保持一致：客户端主动断开/初始化未完成/连接被中止。
     */
    private void broadcast(String eventName, String payload) {
        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : progressEmitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception sendError) {
                if (isClientGone(sendError)) {
                    log.debug("Boss进度 SSE 客户端已断开，移除连接: {}", sendError.getMessage());
                    completeQuietly(emitter);
                } else {
                    log.error("发送Boss进度消息失败", sendError);
                }
                deadEmitters.add(emitter);
            }
        }
        if (!deadEmitters.isEmpty()) {
            progressEmitters.removeAll(deadEmitters);
        }
    }

    /** 判断异常是否只是“客户端断开”，不值得按错误刷日志。 */
    private boolean isClientGone(Throwable error) {
        if (error instanceof AsyncRequestNotUsableException || error instanceof ClientAbortException) {
            return true;
        }
        if (error.getCause() instanceof ClientAbortException) {
            return true;
        }
        return error instanceof IOException
                && String.valueOf(error.getMessage()).contains(CLIENT_ABORT_MESSAGE);
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 连接已断开，忽略重复完成异常
        }
    }

    // ==================== 小工具 ====================

    /** runId 优先取 query，其次取 body（前端 resumeVerification 可能用两者之一）。 */
    private static String resolveRunId(String queryRunId, Map<String, Object> body) {
        if (queryRunId != null && !queryRunId.isBlank()) {
            return queryRunId;
        }
        if (body != null && body.get("runId") != null) {
            return String.valueOf(body.get("runId"));
        }
        return queryRunId;
    }

    private static String resultCode(Map<String, Object> result) {
        return result == null ? null : (String) result.get("code");
    }

    private static Map<String, Object> taskResult(String status, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("message", message);
        return response;
    }

    private static Map<String, Object> startFailure(String message, String status) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        if (status != null) {
            response.put("status", status);
        }
        return response;
    }

    private static Map<String, Object> operationFailure(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
