package com.offerchaser.application.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力声明控制器。
 * <p>
 * 前端据此判断当前构建支持哪些能力（平台开关、预算、跟进、验证恢复、严格 AI），
 * 不暴露任何内部实现细节，只返回布尔能力位与已启用平台列表。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class CapabilitiesController {

    /** 配置缺失时的平台默认值，与 PlaywrightManager 中 offerchaser.platforms 的默认值保持一致 */
    private static final String DEFAULT_PLATFORMS = "boss";

    /** 已启用平台（来自 offerchaser.platforms，逗号分隔） */
    private final List<String> enabledPlatforms;

    public CapabilitiesController(@Value("${offerchaser.platforms:" + DEFAULT_PLATFORMS + "}") String platforms) {
        this.enabledPlatforms = parsePlatforms(platforms);
    }

    /**
     * 获取当前实例的能力声明。
     *
     * @return {success:true, data:{enabledPlatforms:[...], features:{...}}}
     */
    @GetMapping("/capabilities")
    public ResponseEntity<Map<String, Object>> capabilities() {
        Map<String, Object> features = new LinkedHashMap<>();
        // 阶段 A 已落地：预算、跟进、验证恢复、严格 AI 输出
        features.put("budget", true);
        features.put("followup", true);
        features.put("verificationResume", true);
        features.put("strictAi", true);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabledPlatforms", enabledPlatforms);
        data.put("features", features);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * 解析逗号分隔的平台配置（保留配置顺序、去空白、忽略空项）。
     */
    private List<String> parsePlatforms(String platforms) {
        List<String> parsed = new ArrayList<>();
        if (platforms == null) {
            return parsed;
        }
        for (String item : platforms.split(",")) {
            String platform = item.trim();
            if (!platform.isEmpty()) {
                parsed.add(platform);
            }
        }
        return parsed;
    }
}
