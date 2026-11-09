package com.offerchaser.application.controller;

import com.offerchaser.application.metrics.MetricsService;
import com.offerchaser.application.metrics.MetricsValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 阶段 B B4：正确统计看板接口（全部为只读聚合，所有口径见 overview.definitions）。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss/metrics")
@RequiredArgsConstructor
public class BossMetricsController {

    private final MetricsService metricsService;

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "runId", required = false) String runId) {
        try {
            return ok(metricsService.overview(from, to, runId));
        } catch (MetricsValidationException validation) {
            return badRequest(validation.getCode(), validation.getMessage());
        } catch (Exception e) {
            return error("读取统计总览失败", e);
        }
    }

    @GetMapping("/series")
    public ResponseEntity<Map<String, Object>> series(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "metric", required = false) String metric) {
        try {
            return ok(metricsService.series(from, to, metric));
        } catch (MetricsValidationException validation) {
            return badRequest(validation.getCode(), validation.getMessage());
        } catch (Exception e) {
            return error("读取趋势序列失败", e);
        }
    }

    @GetMapping("/collection")
    public ResponseEntity<Map<String, Object>> collection(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        try {
            return ok(metricsService.collection(from, to));
        } catch (MetricsValidationException validation) {
            return badRequest(validation.getCode(), validation.getMessage());
        } catch (Exception e) {
            return error("读取采集汇总失败", e);
        }
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", code);
        response.put("message", message);
        return ResponseEntity.badRequest().body(response);
    }

    private static ResponseEntity<Map<String, Object>> error(String message, Exception e) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message + ": " + e.getMessage());
        return ResponseEntity.internalServerError().body(response);
    }
}
