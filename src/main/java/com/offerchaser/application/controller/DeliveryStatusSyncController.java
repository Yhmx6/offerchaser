package com.offerchaser.application.controller;

import com.offerchaser.application.status.DeliveryStatusSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D2：投递状态同步接口。
 * <ul>
 *   <li>{@code POST /api/boss/status-sync}：按钮主动读取今天到目前的投递/已读/回复；</li>
 *   <li>{@code GET /api/boss/status-sync/summary}：最近一次主动同步、昨天启动同步与统计。</li>
 * </ul>
 * 同步只读 BOSS 聊天，不发送消息。
 */
@RestController
@RequestMapping("/api/boss/status-sync")
public class DeliveryStatusSyncController {

    private final DeliveryStatusSyncService syncService;

    public DeliveryStatusSyncController(DeliveryStatusSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> syncActiveToday() {
        try {
            return ResponseEntity.ok(ok(syncService.syncActiveToday()));
        } catch (IllegalStateException busy) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("code", "STATUS_SYNC_BUSY_OR_UNAVAILABLE");
            response.put("message", busy.getMessage());
            return ResponseEntity.status(409).body(response);
        } catch (Exception e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("code", "STATUS_SYNC_FAILED");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        try {
            return ResponseEntity.ok(ok(syncService.summary()));
        } catch (Exception e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("message", "读取投递状态摘要失败：" + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }
}
