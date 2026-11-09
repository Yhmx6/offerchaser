package com.offerchaser.application.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量健康检查接口（{@code GET /api/health}）。
 * <p>
 * 只返回进程存活信息，不查数据库、不查浏览器：管理台 Sidebar 每 30s 轮询一次，
 * 该端点必须始终快速返回（数据库迁移失败等业务问题不在这里体现）。
 * 管理台读取响应里的 {@code status} 字段判断 UP / DEGRADED / DOWN。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    /** 进程存活时为 UP（Sidebar 据此点亮在线圆点）。 */
    private static final String STATUS_UP = "UP";
    /** 服务标识。 */
    private static final String SERVICE_NAME = "OfferChaser";

    /**
     * 健康检查：返回 {status, timestamp, service}。
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", STATUS_UP);
        body.put("timestamp", System.currentTimeMillis());
        body.put("service", SERVICE_NAME);
        return ResponseEntity.ok(body);
    }
}
