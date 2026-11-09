package com.offerchaser.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.rules.PreviewResult;
import com.offerchaser.application.rules.PreviewSample;
import com.offerchaser.application.rules.RecheckResult;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.rules.RulePayload;
import com.offerchaser.application.rules.RuleSetView;
import com.offerchaser.application.rules.RuleValidationException;
import com.offerchaser.application.rules.RuleVersionConflictException;
import com.offerchaser.application.templates.ProfileTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 B B2：规则配置（版本化）、规则预览、用规则重评。
 * <p>
 * 预览与重评都不会发送任何消息、不会修改投递状态；重评只向 job_evaluation 追加记录。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss")
@RequiredArgsConstructor
public class BossRulesController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuleConfigService ruleConfigService;
    private final ProfileTemplateService profileTemplateService;

    /** 当前规则版本、live 默认值、最近版本列表。 */
    @GetMapping("/rules")
    public ResponseEntity<Map<String, Object>> getRules(
            @RequestParam(value = "versionLimit", required = false, defaultValue = "20") int versionLimit) {
        try {
            RuleSetView active = ruleConfigService.getActive();
            Map<String, Object> data = new HashMap<>();
            data.put("active", active);
            data.put("liveDefaults", ruleConfigService.getLiveDefaults());
            data.put("versions", ruleConfigService.listVersions(versionLimit));
            return ok(data);
        } catch (Exception e) {
            return error("读取规则配置失败", e);
        }
    }

    /** 版本历史。 */
    @GetMapping("/rules/versions")
    public ResponseEntity<Map<String, Object>> listVersions(
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        try {
            return ok(ruleConfigService.listVersions(limit));
        } catch (Exception e) {
            return error("读取规则版本历史失败", e);
        }
    }

    /**
     * 保存新规则版本。body: {@code {"expectedVersion":N,"note":"...","rules":{...}}}
     * 版本冲突 409（带 currentVersion），校验失败 400。
     */
    @PutMapping("/rules")
    public ResponseEntity<Map<String, Object>> saveRules(@RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body == null) {
                return badRequest("RULE_VALIDATION_FAILED", "请求体不能为空");
            }
            Integer expectedVersion = intValue(body.get("expectedVersion"));
            if (expectedVersion == null) {
                return badRequest("RULE_VALIDATION_FAILED", "expectedVersion 为必填整数");
            }
            Object rulesNode = body.get("rules");
            if (rulesNode == null) {
                return badRequest("RULE_VALIDATION_FAILED", "rules 不能为空");
            }
            RulePayload payload = MAPPER.convertValue(rulesNode, RulePayload.class);
            String note = stringValue(body.get("note"));
            RuleSetView saved = ruleConfigService.save(payload, expectedVersion, note);
            // 生效模板优先：规则保存后自动写回当前模板
            profileTemplateService.captureActiveFromCurrent();
            log.info("规则配置保存成功：v{} | hash={}", saved.version(), saved.sourceHash());
            return ok(saved);
        } catch (RuleVersionConflictException conflict) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("code", "RULE_VERSION_CONFLICT");
            response.put("message", conflict.getMessage());
            response.put("currentVersion", conflict.getCurrentVersion());
            return ResponseEntity.status(409).body(response);
        } catch (RuleValidationException validation) {
            return badRequest("RULE_VALIDATION_FAILED", validation.getMessage());
        } catch (IllegalArgumentException invalid) {
            return badRequest("RULE_VALIDATION_FAILED", "规则字段格式不正确：" + invalid.getMessage());
        } catch (Exception e) {
            return error("保存规则失败", e);
        }
    }

    /**
     * 规则预览：body: {@code {"rules":{...可选...},"samples":[{...}]}}。
     * 零发送、不写评估表。
     */
    @PostMapping("/rules/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body == null) {
                return badRequest("RULE_VALIDATION_FAILED", "请求体不能为空");
            }
            RulePayload payload = null;
            Object rulesNode = body.get("rules");
            if (rulesNode != null) {
                payload = MAPPER.convertValue(rulesNode, RulePayload.class);
            }
            Object samplesNode = body.get("samples");
            if (samplesNode == null) {
                return badRequest("RULE_VALIDATION_FAILED", "samples 至少要提供一个样例");
            }
            List<PreviewSample> samples = new ArrayList<>();
            if (samplesNode instanceof List<?> list) {
                for (Object item : list) {
                    samples.add(MAPPER.convertValue(item, PreviewSample.class));
                }
            } else {
                return badRequest("RULE_VALIDATION_FAILED", "samples 必须是数组");
            }
            List<PreviewResult> results = ruleConfigService.preview(payload, samples);
            return ok(results);
        } catch (RuleValidationException validation) {
            return badRequest("RULE_VALIDATION_FAILED", validation.getMessage());
        } catch (IllegalArgumentException invalid) {
            return badRequest("RULE_VALIDATION_FAILED", "样例格式不正确：" + invalid.getMessage());
        } catch (Exception e) {
            return error("规则预览失败", e);
        }
    }

    /**
     * 用指定规则版本重评岗位：body: {@code {"ruleVersion":?, "runId":?, "encryptIds":[], "limit":50}}。
     * 只追加 job_evaluation，不发送、不改投递状态。
     */
    @PostMapping("/evaluations/recheck")
    public ResponseEntity<Map<String, Object>> recheck(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> safeBody = body == null ? Map.of() : body;
            Integer ruleVersion = intValue(safeBody.get("ruleVersion"));
            String runId = stringValue(safeBody.get("runId"));
            Integer limit = intValue(safeBody.get("limit"));
            List<String> encryptIds = stringList(safeBody.get("encryptIds"));
            RecheckResult result = ruleConfigService.recheck(ruleVersion, runId, encryptIds,
                    limit == null ? 50 : limit);
            return ok(result);
        } catch (RuleValidationException validation) {
            return badRequest("RULE_VALIDATION_FAILED", validation.getMessage());
        } catch (Exception e) {
            return error("规则重评失败", e);
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

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }
}
