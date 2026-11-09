package com.offerchaser.application.controller;

import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.templates.ProfileTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 全局配置接口（{@code /api/ai/config}）。
 * <p>
 * 只负责两件事：
 * <ul>
 *   <li>{@code GET /config}：读取 AI 介绍/提示词（管理台 /ai-config 高调页）；</li>
 *   <li>{@code POST /config}：保存介绍/提示词，成功后自动写回当前生效方向模板
 *       （模板优先级见项目总览；没有生效模板时 service 内部静默跳过）。</li>
 * </ul>
 * 旧版的 {@code /health}、{@code /chat} 调试端点已删除，不在这里恢复。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiConfigController {

    /** 保存接口必填字段（缺任一即 400）。 */
    private static final String FIELD_INTRODUCE = "introduce";
    private static final String FIELD_PROMPT = "prompt";
    private static final String INCOMPLETE_MESSAGE = "参数不完整，introduce和prompt不能为空";

    @Autowired
    private AiService aiService;

    @Autowired
    private ProfileTemplateService profileTemplateService;

    /**
     * 读取 AI 配置。
     *
     * @return {success:true, data:AI配置, message}
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getAiConfig() {
        try {
            AiEntity config = aiService.getAiConfig();
            return ResponseEntity.ok(payloadWithData(config, "获取AI配置成功"));
        } catch (Exception readError) {
            log.error("获取AI配置失败", readError);
            return ResponseEntity.internalServerError()
                    .body(failure("获取AI配置失败: " + readError.getMessage()));
        }
    }

    /**
     * 保存（或首次创建）AI 配置，并同步到当前生效模板。
     *
     * @param requestBody 请求体，至少包含 introduce / prompt 两个字符串字段
     * @return {success:true, data:保存后的AI配置, message}
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveAiConfig(@RequestBody Map<String, String> requestBody) {
        String introduce = requestBody == null ? null : requestBody.get(FIELD_INTRODUCE);
        String prompt = requestBody == null ? null : requestBody.get(FIELD_PROMPT);
        if (introduce == null || prompt == null) {
            return ResponseEntity.badRequest().body(failure(INCOMPLETE_MESSAGE));
        }

        try {
            AiEntity saved = aiService.saveOrUpdateAiConfig(introduce, prompt);
            // 生效模板优先：AI 介绍/提示词保存后自动写回当前模板
            profileTemplateService.captureActiveFromCurrent();
            log.info("保存AI配置成功，ID: {}", saved == null ? null : saved.getId());
            return ResponseEntity.ok(payloadWithData(saved, "保存AI配置成功"));
        } catch (Exception saveError) {
            log.error("保存AI配置失败", saveError);
            return ResponseEntity.internalServerError()
                    .body(failure("保存AI配置失败: " + saveError.getMessage()));
        }
    }

    // ==================== 响应构建 ====================

    /** {success:true, data, message} */
    private Map<String, Object> payloadWithData(Object data, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("message", message);
        return response;
    }

    /** {success:false, message} */
    private Map<String, Object> failure(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
