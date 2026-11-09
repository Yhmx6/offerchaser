package com.offerchaser.application.controller;

import com.offerchaser.application.contacts.ContactNotFoundException;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.ContactValidationException;
import com.offerchaser.application.contacts.ContactVersionConflictException;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.persistence.ContactStateRepository;
import com.offerchaser.application.persistence.FollowupPlanRepository;
import com.offerchaser.application.persistence.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 B B3：联系人状态与跟进待办接口（第一版人工闭环）。
 * <p>
 * 这里的所有写操作都有版本保护与幂等键；批准跟进/生成补发图片计划都不会发送任何消息。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss")
@RequiredArgsConstructor
public class BossContactsController {

    private final ContactStateService contactStateService;
    private final FollowupService followupService;

    // ==================== 联系人 ====================

    @GetMapping("/contacts")
    public ResponseEntity<Map<String, Object>> listContacts(
            @RequestParam(value = "replyState", required = false) String replyState,
            @RequestParam(value = "manualTakeover", required = false) Boolean manualTakeover,
            @RequestParam(value = "stopContact", required = false) Boolean stopContact,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        try {
            PageResult<ContactStateRepository.ContactState> result =
                    contactStateService.list(replyState, manualTakeover, stopContact, page, size);
            Map<String, Object> data = new HashMap<>();
            data.put("items", result.items());
            data.put("page", result.page());
            data.put("size", result.size());
            data.put("total", result.total());
            return ok(data);
        } catch (Exception e) {
            return error("查询联系人失败", e);
        }
    }

    @GetMapping("/contacts/{contactKey}")
    public ResponseEntity<Map<String, Object>> getContact(@PathVariable("contactKey") String contactKey) {
        try {
            return ok(contactStateService.detail(contactKey));
        } catch (ContactNotFoundException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            return error("查询联系人详情失败", e);
        }
    }

    /** 人工标记联系人状态（回复/拒绝/面试/人工接管/停止联系）。 */
    @PutMapping("/contacts/{contactKey}/state")
    public ResponseEntity<Map<String, Object>> markState(
            @PathVariable("contactKey") String contactKey,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body == null) {
                return badRequest("INVALID_STATE", "请求体不能为空");
            }
            Integer expectedVersion = intValue(body.get("expectedVersion"));
            if (expectedVersion == null) {
                return badRequest("INVALID_STATE", "expectedVersion 为必填整数");
            }
            ContactStateService.MarkStateRequest request = new ContactStateService.MarkStateRequest(
                    stringValue(body.get("replyState")),
                    booleanValue(body.get("manualTakeover")),
                    booleanValue(body.get("stopContact")),
                    stringValue(body.get("reason")),
                    expectedVersion,
                    stringValue(body.get("idempotencyKey")));
            ContactStateService.ContactStateView updated = contactStateService.markState(contactKey, request);
            return ok(updated);
        } catch (ContactVersionConflictException conflict) {
            return conflict(conflict);
        } catch (ContactValidationException validation) {
            return badRequest("INVALID_STATE", validation.getMessage());
        } catch (ContactNotFoundException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            return error("更新联系人状态失败", e);
        }
    }

    // ==================== 跟进待办 ====================

    /**
     * 首页「跟进待办」：已回复/面试中的有效沟通联系人，附带最近岗位、薪资与岗位链接。
     */
    @GetMapping("/followups/effective")
    public ResponseEntity<Map<String, Object>> listEffectiveCommunications(
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        try {
            return ok(followupService.effectiveCommunications(limit));
        } catch (Exception e) {
            return error("查询有效沟通跟进失败", e);
        }
    }

    @GetMapping("/followups")
    public ResponseEntity<Map<String, Object>> listFollowups(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        try {
            PageResult<FollowupPlanRepository.FollowupPlan> result = followupService.list(status, page, size);
            Map<String, Object> data = new HashMap<>();
            data.put("items", result.items());
            data.put("page", result.page());
            data.put("size", result.size());
            data.put("total", result.total());
            return ok(data);
        } catch (Exception e) {
            return error("查询跟进计划失败", e);
        }
    }

    /** 生成建议跟进（只创建 SUGGESTED，不发送）。 */
    @PostMapping("/followups/generate")
    public ResponseEntity<Map<String, Object>> generateFollowups(
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            Integer limit = body == null ? null : intValue(body.get("limit"));
            int created = followupService.generateSuggestions(limit == null ? 50 : limit);
            Map<String, Object> data = new HashMap<>();
            data.put("created", created);
            return ok(data);
        } catch (ContactValidationException validation) {
            return badRequest("INVALID_STATE", validation.getMessage());
        } catch (Exception e) {
            return error("生成建议跟进失败", e);
        }
    }

    @PostMapping("/followups/{planId}/approve")
    public ResponseEntity<Map<String, Object>> approveFollowup(
            @PathVariable("planId") String planId,
            @RequestBody(required = false) Map<String, Object> body) {
        return decideFollowup(planId, body, true);
    }

    @PostMapping("/followups/{planId}/reject")
    public ResponseEntity<Map<String, Object>> rejectFollowup(
            @PathVariable("planId") String planId,
            @RequestBody(required = false) Map<String, Object> body) {
        return decideFollowup(planId, body, false);
    }

    private ResponseEntity<Map<String, Object>> decideFollowup(
            String planId, Map<String, Object> body, boolean approve) {
        try {
            if (body == null) {
                return badRequest("INVALID_STATE", "请求体不能为空");
            }
            Integer expectedVersion = intValue(body.get("expectedVersion"));
            if (expectedVersion == null) {
                return badRequest("INVALID_STATE", "expectedVersion 为必填整数");
            }
            String reason = stringValue(body.get("reason"));
            String idempotencyKey = stringValue(body.get("idempotencyKey"));
            FollowupPlanRepository.FollowupPlan updated = approve
                    ? followupService.approve(planId, expectedVersion, reason, idempotencyKey)
                    : followupService.reject(planId, expectedVersion, reason, idempotencyKey);
            return ok(updated);
        } catch (ContactVersionConflictException conflict) {
            return conflict(conflict);
        } catch (ContactValidationException validation) {
            return badRequest("INVALID_STATE", validation.getMessage());
        } catch (ContactNotFoundException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            return error(approve ? "批准跟进失败" : "拒绝跟进失败", e);
        }
    }

    // ==================== 补发图片 ====================

    /** 为"文字已确认、图片失败/未知"的尝试生成补发图片计划（不发送，只落 SUGGESTED 计划）。 */
    @PostMapping("/attempts/{attemptId}/resend-image")
    public ResponseEntity<Map<String, Object>> resendImage(
            @PathVariable("attemptId") String attemptId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            String reason = body == null ? null : stringValue(body.get("reason"));
            FollowupPlanRepository.FollowupPlan plan = followupService.createImageResendPlan(attemptId, reason);
            return ok(plan);
        } catch (ContactValidationException validation) {
            return badRequest("INVALID_RESEND", validation.getMessage());
        } catch (ContactNotFoundException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            return error("创建补发图片计划失败", e);
        }
    }

    // ==================== 工具 ====================

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

    private static ResponseEntity<Map<String, Object>> conflict(ContactVersionConflictException exception) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", "VERSION_CONFLICT");
        response.put("currentVersion", exception.getCurrentVersion());
        response.put("message", exception.getMessage());
        return ResponseEntity.status(409).body(response);
    }

    private static ResponseEntity<Map<String, Object>> notFound(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", "NOT_FOUND");
        response.put("message", message);
        return ResponseEntity.status(404).body(response);
    }

    private static ResponseEntity<Map<String, Object>> error(String message, Exception e) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message + ": " + e.getMessage());
        return ResponseEntity.internalServerError().body(response);
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

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase();
            if ("true".equals(normalized) || "1".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "0".equals(normalized)) {
                return false;
            }
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return null;
    }
}
