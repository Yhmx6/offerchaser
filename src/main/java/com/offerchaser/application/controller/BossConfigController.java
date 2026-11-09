package com.offerchaser.application.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.entity.BlacklistEntity;
import com.offerchaser.application.entity.BossConfigEntity;
import com.offerchaser.application.entity.BossOptionEntity;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossResumeImageService.ResumeImageInfo;
import com.offerchaser.application.service.BossService;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.worker.boss.BossConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BOSS 搜索/投递配置接口（{@code /api/boss/config}）。
 * <p>
 * 职责：
 * <ul>
 *   <li>读取配置 + 城市/行业/经验等下拉选项 + 黑名单 + 当前图片简历元信息；</li>
 *   <li>保存配置（把前端传来的代码/分隔串统一成数据库期望的中文名/括号列表）；</li>
 *   <li>黑名单增删；图片简历上传与预览。</li>
 * </ul>
 * 保存与简历上传成功后都会调用 {@code captureActiveFromCurrent()}，把当前配置写回生效方向模板，
 * 避免配置页与模板两处漂移（模板优先级见项目总览）。
 */
@RestController
@RequestMapping("/api/boss/config")
public class BossConfigController {

    /** 前端下拉选项类型：顺序与配置页展示顺序一致。 */
    private static final List<String> OPTION_TYPES = List.of(
            "city", "industry", "experience", "jobType", "salary", "degree", "scale", "stage");

    /** 需要保存成“中文名括号列表”的多选字段（单值字段 city/jobType 单独处理）。 */
    private static final List<String> BRACKET_LIST_FIELDS = List.of(
            "industry", "experience", "degree", "scale", "stage", "salary");

    /** 关键词 JSON 序列化器（只用于配置读写，无全局状态）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DEFAULT_BLACKLIST_TYPE = "boss";
    private static final String RESUME_TOO_LARGE_MESSAGE = "图片大小不能超过 10MB";

    private final BossService bossService;
    private final BossResumeImageService bossResumeImageService;
    private final ProfileTemplateService profileTemplateService;

    public BossConfigController(BossService bossService, BossResumeImageService bossResumeImageService,
                                ProfileTemplateService profileTemplateService) {
        this.bossService = bossService;
        this.bossResumeImageService = bossResumeImageService;
        this.profileTemplateService = profileTemplateService;
    }

    // ==================== 配置读取 ====================

    /**
     * 一次性返回 BOSS 配置页需要的全部数据。
     *
     * @return {config, options, blacklist, resumeImage}
     */
    @GetMapping
    public Map<String, Object> getAllBossConfig() {
        BossConfigEntity config = bossService.getFirstConfig();
        if (config == null) {
            config = new BossConfigEntity();
        }
        // 老库里该列可能为 NULL，给前端一个可编辑的默认文本，而不是 null
        if (config.getRepeatChatMessages() == null) {
            config.setRepeatChatMessages(BossConfig.DEFAULT_REPEAT_CHAT_MESSAGES);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("config", config);
        payload.put("options", loadOptionGroups());
        payload.put("blacklist", bossService.getAllBlacklist());
        payload.put("resumeImage", bossResumeImageService.getMetadata());
        return payload;
    }

    /**
     * 按类型把下拉选项分组，供配置页一次性加载。
     */
    private Map<String, List<BossOptionEntity>> loadOptionGroups() {
        Map<String, List<BossOptionEntity>> groups = new LinkedHashMap<>();
        for (String type : OPTION_TYPES) {
            groups.put(type, bossService.getOptionsByType(type));
        }
        return groups;
    }

    /**
     * 读取指定类型的选项列表（配置页“重新加载选项”用）。
     */
    @GetMapping(path = "/options/{type}")
    public List<BossOptionEntity> getOptionsByType(@PathVariable String type) {
        return bossService.getOptionsByType(type);
    }

    // ==================== 配置保存 ====================

    /**
     * 保存 BOSS 配置。
     * <p>
     * 有 id 时按 id 更新；无 id 时走“选择性更新首条记录”（只覆盖请求里非 null 的字段），
     * 兼容 AI 配置页只提交 {@code {enableAi:1}} 这类局部更新。保存成功后同步生效模板。
     */
    @PutMapping
    public BossConfigEntity updateConfig(@RequestBody BossConfigEntity config) {
        BossConfigEntity target = normalizeForSave(config);

        BossConfigEntity saved = target.getId() != null
                ? bossService.updateConfig(target)
                : bossService.saveOrUpdateFirstSelective(target);

        // 生效模板优先：配置页保存后立即写回当前模板（没有生效模板时该方法内部静默返回 null）
        profileTemplateService.captureActiveFromCurrent();
        return saved;
    }

    /**
     * 把前端提交的“代码/逗号串/括号串”统一成数据库期望的存储格式：
     * 关键词 → JSON 数组；城市/职位类型 → 中文名；其余多选 → 中文名括号列表。
     */
    private BossConfigEntity normalizeForSave(BossConfigEntity config) {
        config.setKeywords(normalizeKeywords(config.getKeywords()));

        if (config.getCityCode() != null) {
            config.setCityCode(bossService.normalizeCityToName(config.getCityCode()));
        }

        for (String field : BRACKET_LIST_FIELDS) {
            String raw = readBracketListField(config, field);
            if (raw == null) {
                continue;
            }
            List<String> names = bossService.toNames(field, bossService.parseListString(raw));
            writeBracketListField(config, field, bossService.toBracketListString(names));
        }

        if (config.getJobType() != null) {
            config.setJobType(resolveJobTypeName(config.getJobType()));
        }
        return config;
    }

    private String readBracketListField(BossConfigEntity config, String field) {
        return switch (field) {
            case "industry" -> config.getIndustry();
            case "experience" -> config.getExperience();
            case "degree" -> config.getDegree();
            case "scale" -> config.getScale();
            case "stage" -> config.getStage();
            case "salary" -> config.getSalary();
            default -> null;
        };
    }

    private void writeBracketListField(BossConfigEntity config, String field, String value) {
        switch (field) {
            case "industry" -> config.setIndustry(value);
            case "experience" -> config.setExperience(value);
            case "degree" -> config.setDegree(value);
            case "scale" -> config.setScale(value);
            case "stage" -> config.setStage(value);
            case "salary" -> config.setSalary(value);
            default -> {
                // 未知字段不处理
            }
        }
    }

    /**
     * 职位类型是单值：优先取列表第一个的中文名；解析不出来时按代码查名称，最后原样保留。
     */
    private String resolveJobTypeName(String raw) {
        List<String> names = bossService.toNames("jobType", bossService.parseListString(raw));
        if (names != null && !names.isEmpty()) {
            return names.get(0);
        }
        BossOptionEntity byCode = bossService.getOptionByTypeAndCode("jobType", raw);
        return byCode != null ? byCode.getName() : raw;
    }

    /**
     * 关键词标准化为 JSON 字符串数组。
     * <p>
     * 兼容四种输入：逗号分隔（半角/全角）、括号列表、JSON 数组、混合引号文本。
     * null 原样返回（表示“不修改”），空文本落库为 {@code []}。
     */
    private String normalizeKeywords(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return "[]";
        }

        List<String> keywords = parseBracketKeywords(text);
        if (keywords == null) {
            keywords = splitKeywordText(text);
        }
        return writeJson(keywords);
    }

    /**
     * 括号形式的关键词先尝试严格 JSON；不是严格 JSON 时去掉首尾括号再按分隔符拆。
     * 非括号文本返回 null，交给 {@link #splitKeywordText(String)}。
     */
    private List<String> parseBracketKeywords(String text) {
        boolean bracketWrapped = text.startsWith("[") && text.endsWith("]");
        if (!bracketWrapped) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (node != null && node.isArray()) {
                List<String> items = new ArrayList<>();
                node.forEach(child -> items.add(child.asText().trim()));
                return items;
            }
        } catch (Exception ignored) {
            // 不是严格 JSON：继续按括号文本处理
        }
        return splitKeywordText(text.substring(1, text.length() - 1));
    }

    /** 按半角/全角逗号拆分关键词，去空白、去空项、去包裹引号。 */
    private List<String> splitKeywordText(String text) {
        return Arrays.stream(text.split("[,，]"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(item -> item.replaceAll("^\"|\"$", ""))
                .collect(Collectors.toList());
    }

    private String writeJson(List<String> keywordList) {
        try {
            return JSON.writeValueAsString(keywordList);
        } catch (Exception e) {
            // 序列化字符串数组理论上不会失败；兜底为空数组，避免把坏值写进配置
            return "[]";
        }
    }

    // ==================== 黑名单 ====================

    /** 获取全部黑名单（公司/招聘者/岗位）。 */
    @GetMapping(path = "/blacklist")
    public List<BlacklistEntity> getBlacklist() {
        return bossService.getAllBlacklist();
    }
    // —— 黑名单增删：type 缺省为 boss，value 为空串时由 service 决定是否落库 ——

    /**
     * 添加一条黑名单。type 缺省为 boss，value 缺省为空串（由 service 决定是否真正落库）。
     */
    @PostMapping("/blacklist")
    public BlacklistEntity addBlacklist(@RequestBody BlacklistEntity blacklist) {
        String type = blacklist.getType() != null ? blacklist.getType() : DEFAULT_BLACKLIST_TYPE;
        String value = blacklist.getValue() != null ? blacklist.getValue() : "";

        boolean added = bossService.addBlacklist(type, value);
        if (added) {
            // 刚创建时数据库会生成 id，这里回给前端 null 表示“以列表刷新为准”
            blacklist.setId(null);
        }
        return blacklist;
    }

    /**
     * 按 id 删除黑名单：先按当前列表定位 type/value，再调用 service 删除。
     *
     * @return 是否删除成功（id 不存在返回 false）
     */
    @DeleteMapping("/blacklist/{id}")
    public boolean deleteBlacklist(@PathVariable Long id) {
        return bossService.getAllBlacklist().stream()
                .filter(item -> id.equals(item.getId()))
                .findFirst()
                .map(item -> bossService.removeBlacklist(item.getType(), item.getValue()))
                .orElse(false);
    }

    // ==================== 图片简历 ====================

    /** 上传或替换当前图片简历；成功后同步到生效模板。 */
    @PostMapping(value = "/resume-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadResumeImage(@RequestParam("file") MultipartFile file) {
        try {
            bossResumeImageService.save(file);
            profileTemplateService.captureActiveFromCurrent();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "图片简历上传成功");
            response.put("resumeImage", bossResumeImageService.getMetadata());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException invalidFile) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("message", invalidFile.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception saveError) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("message", "图片简历保存失败：" + saveError.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** 返回当前图片简历内容，供管理页面预览。 */
    @GetMapping("/resume-image/content")
    public ResponseEntity<Resource> getResumeImageContent() {
        return bossResumeImageService.getCurrentImage()
                .map(this::buildPreviewResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<Resource> buildPreviewResponse(ResumeImageInfo info) {
        MediaType contentType = MediaType.parseMediaType(info.contentType());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.setContentLength(info.size());
        headers.setCacheControl(CacheControl.noStore().getHeaderValue());
        return new ResponseEntity<>(new FileSystemResource(info.path()), headers, HttpStatus.OK);
    }

    /** multipart 超过全局上限时给前端一个明确文案，而不是默认错误页。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", RESUME_TOO_LARGE_MESSAGE);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }
}
