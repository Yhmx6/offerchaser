package com.offerchaser.application.controller;

import com.offerchaser.application.manual.ManualReleaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D3-B 人工放行接口。
 * <ul>
 *   <li>GET 队列：REVIEW 岗位 + 放行决策状态；</li>
 *   <li>POST 决策：APPROVE 只入队，下一轮任务执行；REJECT 拒绝；</li>
 * </ul>
 * 本接口不直接发送消息。
 */
@RestController
@RequestMapping("/api/boss/manual-releases")
public class ManualReleaseController {

    private final ManualReleaseService service;

    public ManualReleaseController(ManualReleaseService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> queue(
            @RequestParam(value = "status", required = false, defaultValue = "PENDING") String status) {
        try {
            return ResponseEntity.ok(ok(service.queue(status)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(error("读取人工放行队列失败：" + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> decide(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(error("请求体不能为空"));
        }
        Long evaluationId = longValue(body.get("evaluationId"));
        if (evaluationId == null) {
            return ResponseEntity.badRequest().body(error("evaluationId 必须为数字"));
        }
        String action = stringValue(body.get("action"));
        String reason = stringValue(body.get("reason"));
        try {
            ManualReleaseService.DecisionResult result = service.decide(evaluationId, action, reason);
            return ResponseEntity.ok(ok(result));
        } catch (IllegalStateException conflict) {
            Map<String, Object> response = error(conflict.getMessage());
            response.put("code", "MANUAL_RELEASE_CONFLICT");
            return ResponseEntity.status(409).body(response);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error(invalid.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(error("人工放行决策失败：" + e.getMessage()));
        }
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
