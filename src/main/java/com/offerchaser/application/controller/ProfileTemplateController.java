package com.offerchaser.application.controller;

import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.application.templates.TemplateNotFoundException;
import com.offerchaser.application.templates.TemplateValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方向模板接口：每个模板保存一整套配置（AI 介绍/提示词、简历、筛选规则、BOSS 搜索与投递开关）。
 * <p>
 * 激活模板会应用它的配置到当前配置，下一轮任务生效；推荐流程：
 * 激活模板 → 在现有配置/规则/AI/简历页修改 → 回到模板页「用当前配置覆盖模板」。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss/templates")
@RequiredArgsConstructor
public class ProfileTemplateController {

    private final ProfileTemplateService templateService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("items", templateService.list());
            data.put("active", templateService.active().orElse(null));
            return ok(data);
        } catch (Exception e) {
            return error("读取方向模板失败", e);
        }
    }

    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> active() {
        try {
            return ok(templateService.active().map(view -> (Object) view).orElse(null));
        } catch (Exception e) {
            return error("读取当前模板失败", e);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable("id") long id) {
        try {
            return ok(templateService.detail(id));
        } catch (TemplateNotFoundException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            return error("读取模板详情失败", e);
        }
    }

    /** 保存模板拖拽后的顺序；body: {"order":[3,1,2]}。 */
    @PutMapping("/order")
    public ResponseEntity<Map<String, Object>> reorder(@RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body == null || !(body.get("order") instanceof List<?> raw)) {
                return badRequest("order 必须是模板 id 数组");
            }
            List<Long> orderedIds = new ArrayList<>();
            for (Object value : raw) {
                if (value instanceof Number number) {
                    orderedIds.add(number.longValue());
                } else if (value instanceof String text) {
                    try {
                        orderedIds.add(Long.parseLong(text.trim()));
                    } catch (NumberFormatException e) {
                        return badRequest("order 中包含非法 id");
                    }
                } else {
                    return badRequest("order 中包含非法 id");
                }
            }
            return ok(templateService.reorderTemplates(orderedIds));
        } catch (TemplateValidationException e) {
            return validation(e);
        } catch (Exception e) {
            return error("保存模板顺序失败", e);
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body == null) {
                return badRequest("请求体不能为空");
            }
            String name = stringValue(body.get("name"));
            String direction = stringValue(body.get("direction"));
            String description = stringValue(body.get("description"));
            return ok(templateService.create(name, direction, description));
        } catch (TemplateValidationException e) {
            return validation(e);
        } catch (Exception e) {
            return error("创建模板失败", e);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateMetadata(@PathVariable("id") long id,
                                                              @RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body == null) {
                return badRequest("请求体不能为空");
            }
            var existing = templateService.get(id);
            String name = body.containsKey("name") ? stringValue(body.get("name")) : existing.name();
            String direction = body.containsKey("direction") ? stringValue(body.get("direction")) : existing.direction();
            String description = body.containsKey("description") ? stringValue(body.get("description")) : existing.description();
            return ok(templateService.updateMetadata(id, name, direction, description));
        } catch (TemplateNotFoundException e) {
            return notFound(e.getMessage());
        } catch (TemplateValidationException e) {
            return validation(e);
        } catch (Exception e) {
            return error("更新模板信息失败", e);
        }
    }

    /** 用当前配置覆盖模板（名称/方向/描述不变）。 */
    @PostMapping("/{id}/capture")
    public ResponseEntity<Map<String, Object>> capture(@PathVariable("id") long id) {
        try {
            return ok(templateService.capture(id));
        } catch (TemplateNotFoundException e) {
            return notFound(e.getMessage());
        } catch (TemplateValidationException e) {
            return validation(e);
        } catch (Exception e) {
            return error("用当前配置覆盖模板失败", e);
        }
    }

    /**
     * 在配置中心直接编辑模板整包配置（搜索条件/薪资/公司要求/黑名单/规则/AI 配置）。
     * 模板生效时会立即应用到运行配置。
     */
    @PutMapping("/{id}/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@PathVariable("id") long id,
                                                             @RequestBody(required = false) ProfileTemplateService.TemplateConfigRequest request) {
        try {
            if (request == null) {
                return badRequest("配置不能为空");
            }
            return ok(templateService.updateTemplateConfig(id, request));
        } catch (TemplateNotFoundException e) {
            return notFound(e.getMessage());
        } catch (TemplateValidationException e) {
            return validation(e);
        } catch (Exception e) {
            return error("保存模板配置失败", e);
        }
    }

    /** 激活模板：应用到当前配置，下一轮任务生效。 */
    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable("id") long id) {
        try {
            return ok(templateService.activate(id));
        } catch (TemplateNotFoundException e) {
            return notFound(e.getMessage());
        } catch (TemplateValidationException e) {
            return validation(e);
        } catch (Exception e) {
            return error("激活模板失败", e);
        }
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<Map<String, Object>> duplicate(@PathVariable("id") long id,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        try {
            String name = body == null ? null : stringValue(body.get("name"));
            return ok(templateService.duplicate(id, name));
        } catch (TemplateNotFoundException e) {
            return notFound(e.getMessage());
        } catch (TemplateValidationException e) {
            return validation(e);
        } catch (Exception e) {
            return error("复制模板失败", e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") long id) {
        try {
            templateService.delete(id);
            Map<String, Object> data = new HashMap<>();
            data.put("deleted", true);
            return ok(data);
        } catch (TemplateNotFoundException e) {
            return notFound(e.getMessage());
        } catch (TemplateValidationException e) {
            return validation(e);
        } catch (Exception e) {
            return error("删除模板失败", e);
        }
    }

    @PostMapping(value = "/{id}/resume-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadResume(@PathVariable("id") long id,
                                                            @RequestParam("file") MultipartFile file) {
        try {
            return ok(templateService.uploadResume(id, file));
        } catch (TemplateNotFoundException e) {
            return notFound(e.getMessage());
        } catch (TemplateValidationException e) {
            return validation(e);
        } catch (Exception e) {
            return error("上传模板简历失败", e);
        }
    }

    @GetMapping("/{id}/resume-image/content")
    public ResponseEntity<Resource> resumeContent(@PathVariable("id") long id) {
        try {
            var path = templateService.resumePath(id);
            if (path.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            Path file = path.get();
            String contentType = Files.probeContentType(file);
            return ResponseEntity.ok()
                    .contentType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType))
                    .body(new FileSystemResource(file));
        } catch (TemplateNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== 工具 ====================

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private static ResponseEntity<Map<String, Object>> validation(TemplateValidationException e) {
        int status = e.getMessage() != null
                && (e.getMessage().contains("名称已存在") || e.getMessage().contains("不能删除"))
                ? 409 : 400;
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", status == 409 ? "TEMPLATE_CONFLICT" : "TEMPLATE_INVALID");
        response.put("message", e.getMessage());
        return ResponseEntity.status(status).body(response);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", "TEMPLATE_INVALID");
        response.put("message", message);
        return ResponseEntity.badRequest().body(response);
    }

    private static ResponseEntity<Map<String, Object>> notFound(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", "TEMPLATE_NOT_FOUND");
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
}
