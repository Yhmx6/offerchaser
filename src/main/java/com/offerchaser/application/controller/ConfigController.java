package com.offerchaser.application.controller;

import com.offerchaser.application.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用键值配置接口（{@code /api/config}）：读取/批量保存/单键保存/单键删除/健康检查。
 * <p>
 * 安全契约（S5）：密钥类配置（键名含 KEY/TOKEN/SECRET/PASSWORD/PASSWD/COOKIE/CREDENTIAL）
 * <ul>
 *   <li>读取时只返回掩码 {@code ********}，不回显明文；</li>
 *   <li>保存时提交掩码或 null 表示“保持原值”（前端会把读到的掩码原样回传）；</li>
 *   <li>提交空字符串表示“显式清空”；其他值正常写入。</li>
 * </ul>
 * 鉴权由 {@code AuthInterceptor} 统一处理，本类不重复实现。
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    // 键值配置的读写统一走 ConfigService（掩码/保持原值语义都在 service 层）
    @Autowired
    private ConfigService configService;

    /**
     * 读取全部配置（密钥类配置已掩码）。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllConfigs() {
        try {
            return ResponseEntity.ok(successData(configService.getAllConfigsMasked(), "获取配置成功"));
        } catch (Exception readError) {
            log.error("获取配置失败", readError);
            return ResponseEntity.internalServerError().body(failure("获取配置失败: " + readError.getMessage()));
        }
    }

    /**
     * 按 key 读取单个配置（密钥类配置已掩码）。不存在返回 404。
     */
    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> getConfigByKey(@PathVariable String key) {
        try {
            var config = configService.getConfigByKeyMasked(key);
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(successData(config, "获取配置成功"));
        } catch (Exception readError) {
            log.error("获取配置失败: {}", key, readError);
            return ResponseEntity.internalServerError().body(failure("获取配置失败: " + readError.getMessage()));
        }
    }

    /**
     * 批量保存配置。请求体为空视为参数错误。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> batchUpdateConfigs(@RequestBody Map<String, String> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return ResponseEntity.badRequest().body(failure("配置数据不能为空"));
        }
        try {
            int updateCount = configService.batchUpdateConfigs(configMap);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "配置更新成功");
            response.put("updateCount", updateCount);

            log.info("批量更新配置成功，共处理 {} 项", updateCount);
            return ResponseEntity.ok(response);
        } catch (Exception updateError) {
            log.error("批量更新配置失败", updateError);
            return ResponseEntity.internalServerError().body(failure("配置更新失败: " + updateError.getMessage()));
        }
    }

    /**
     * 保存单个配置。
     * <p>
     * {@code value} 为 null 或掩码 → 保持原值；{@code value=""} → 显式清空；其他值 → 正常写入。
     */
    @PutMapping(path = "/{key}")
    public ResponseEntity<Map<String, Object>> updateConfig(@PathVariable String key,
                                                           @RequestBody Map<String, String> body) {
        try {
            // null / 缺 value 表示“不修改”，避免掩码回传场景误清空
            String value = body == null ? null : body.get("value");
            boolean updated = configService.updateConfig(key, value);
            if (!updated) {
                return ResponseEntity.badRequest().body(failure("配置更新失败，配置键可能不存在"));
            }
            return ResponseEntity.ok(successMessage("配置更新成功"));
        } catch (Exception updateError) {
            log.error("更新配置失败: {}", key, updateError);
            return ResponseEntity.internalServerError().body(failure("更新配置失败: " + updateError.getMessage()));
        }
    }

    /**
     * 删除单个配置；键不存在返回 404（响应体仍给出可读 message）。
     */
    @DeleteMapping("/{key}")
    public ResponseEntity<Map<String, Object>> deleteConfig(@PathVariable String key) {
        try {
            if (!configService.deleteConfig(key)) {
                return ResponseEntity.status(404).body(failure("配置不存在"));
            }
            return ResponseEntity.ok(successMessage("配置删除成功"));
        } catch (Exception deleteError) {
            log.error("删除配置失败: {}", key, deleteError);
            return ResponseEntity.internalServerError().body(failure("删除配置失败: " + deleteError.getMessage()));
        }
    }

    /**
     * 配置模块健康检查（不读数据库）。
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "service", "ConfigController",
                "status", "healthy",
                "success", true,
                "timestamp", System.currentTimeMillis()
        ));
    }

    // ==================== 响应构建 ====================

    /** {success:true, data, message} */
    private Map<String, Object> successData(Object data, String message) {
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

    /** {success:true, message}：更新/删除成功时使用（前端只关心 success）。 */
    private Map<String, Object> successMessage(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        return response;
    }
}
