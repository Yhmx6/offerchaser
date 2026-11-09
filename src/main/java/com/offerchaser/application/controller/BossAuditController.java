package com.offerchaser.application.controller;

import com.offerchaser.application.persistence.AttemptNotFoundException;
import com.offerchaser.application.persistence.AttemptResolutionService;
import com.offerchaser.application.persistence.AlreadyConfirmedException;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.InvalidResolutionException;
import com.offerchaser.application.persistence.PageResult;
import com.offerchaser.application.persistence.VersionConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 阶段 B B1：BOSS 只读记录查询与人工核对接口。
 * <p>
 * 只做查询与"人工核对未知结果"，不发送任何消息；核对接口带 expectedVersion 与 idempotencyKey，
 * 冲突返回 409，已确认成功的记录不允许被通用接口回退成未发送。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss")
@RequiredArgsConstructor
public class BossAuditController {

    private final BossQueryRepository bossQueryRepository;
    private final AttemptResolutionService attemptResolutionService;

    /** 任务运行记录（分页）。 */
    @GetMapping("/runs")
    public ResponseEntity<Map<String, Object>> listRuns(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        try {
            PageResult<Map<String, Object>> result = bossQueryRepository.listRuns(status, from, to, page, size);
            return ok(result);
        } catch (Exception e) {
            return error("查询任务记录失败", e);
        }
    }

    /** 岗位评估记录（分页，可按规则/AI 结果、原因码、关键词等筛选；latestOnly=true 只取每岗位最新评估）。 */
    @GetMapping("/evaluations")
    public ResponseEntity<Map<String, Object>> listEvaluations(
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "ruleDecision", required = false) String ruleDecision,
            @RequestParam(value = "aiDecision", required = false) String aiDecision,
            @RequestParam(value = "reasonCode", required = false) String reasonCode,
            @RequestParam(value = "encryptId", required = false) String encryptId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "latestOnly", required = false, defaultValue = "false") boolean latestOnly,
            @RequestParam(value = "ruleConfigVersion", required = false) Integer ruleConfigVersion,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        try {
            PageResult<Map<String, Object>> result = bossQueryRepository.listEvaluations(
                    runId, ruleDecision, aiDecision, reasonCode, encryptId, keyword, from, to,
                    latestOnly, ruleConfigVersion, page, size);
            return ok(result);
        } catch (Exception e) {
            return error("查询岗位评估记录失败", e);
        }
    }

    /** 投递明细（分页，文字/图片状态独立筛选）。 */
    @GetMapping("/attempts")
    public ResponseEntity<Map<String, Object>> listAttempts(
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "textStatus", required = false) String textStatus,
            @RequestParam(value = "imageStatus", required = false) String imageStatus,
            @RequestParam(value = "encryptId", required = false) String encryptId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        try {
            PageResult<Map<String, Object>> result = bossQueryRepository.listAttempts(
                    runId, kind, textStatus, imageStatus, encryptId, from, to, page, size);
            return ok(result);
        } catch (Exception e) {
            return error("查询投递明细失败", e);
        }
    }

    /** 单条投递尝试详情：attempt + 事件 + 关联评估。 */
    @GetMapping("/attempts/{attemptId}")
    public ResponseEntity<Map<String, Object>> getAttemptDetail(@PathVariable("attemptId") String attemptId) {
        try {
            Map<String, Object> detail = bossQueryRepository.getAttemptDetail(attemptId);
            if (detail == null) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("code", "ATTEMPT_NOT_FOUND");
                response.put("message", "投递尝试不存在：" + attemptId);
                return ResponseEntity.status(404).body(response);
            }
            return ok(detail);
        } catch (Exception e) {
            return error("查询投递详情失败", e);
        }
    }

    /**
     * 人工核对未知结果。
     * <p>
     * body: {@code {"resolution":"SENT|NOT_SENT|STILL_UNKNOWN","reason":"...","expectedVersion":N,"idempotencyKey":"可选"}}
     */
    @PostMapping("/attempts/{attemptId}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAttempt(
            @PathVariable("attemptId") String attemptId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body == null) {
                return badRequest("INVALID_RESOLUTION", "请求体不能为空");
            }
            String resolution = stringValue(body.get("resolution"));
            String reason = stringValue(body.get("reason"));
            Integer expectedVersion = intValue(body.get("expectedVersion"));
            String idempotencyKey = stringValue(body.get("idempotencyKey"));
            if (resolution == null || reason == null || expectedVersion == null) {
                return badRequest("INVALID_RESOLUTION",
                        "resolution、reason、expectedVersion 均为必填");
            }
            AttemptResolutionService.ResolutionResult result = attemptResolutionService.resolve(
                    attemptId, resolution, reason, expectedVersion, idempotencyKey);
            Map<String, Object> data = new HashMap<>();
            data.put("attemptId", result.attemptId());
            data.put("textStatus", result.textStatus());
            data.put("imageStatus", result.imageStatus());
            data.put("version", result.version());
            data.put("manualResolution", result.manualResolution());
            data.put("resolvedAt", result.resolvedAt());
            data.put("changed", result.changed());
            data.put("idempotent", result.idempotent());
            log.info("人工核对投递尝试 | attemptId={} | resolution={} | changed={} | idempotent={}",
                    attemptId, resolution, result.changed(), result.idempotent());
            return ok(data);
        } catch (AttemptNotFoundException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("code", "ATTEMPT_NOT_FOUND");
            response.put("message", "投递尝试不存在：" + attemptId);
            return ResponseEntity.status(404).body(response);
        } catch (VersionConflictException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("code", "VERSION_CONFLICT");
            response.put("message", "记录已被其他操作修改，请刷新后重试");
            response.put("currentVersion", e.getCurrentVersion());
            response.put("currentStatus", e.getCurrentStatus());
            return ResponseEntity.status(409).body(response);
        } catch (AlreadyConfirmedException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("code", "ALREADY_CONFIRMED");
            response.put("message", "该记录已确认发送成功，不能通过通用接口回退");
            return ResponseEntity.status(409).body(response);
        } catch (InvalidResolutionException e) {
            return badRequest("INVALID_RESOLUTION", e.getMessage());
        } catch (Exception e) {
            log.error("人工核对投递尝试失败 | attemptId={}", attemptId, e);
            return error("人工核对失败", e);
        }
    }

    // ==================== 工具 ====================

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private static ResponseEntity<Map<String, Object>> error(String message, Exception e) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message + ": " + e.getMessage());
        return ResponseEntity.internalServerError().body(response);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", code);
        response.put("message", message);
        return ResponseEntity.badRequest().body(response);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
