package com.offerchaser.application.templates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.entity.BlacklistEntity;
import com.offerchaser.application.entity.BossConfigEntity;
import com.offerchaser.application.persistence.ProfileTemplateRepository;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.rules.RulePayload;
import com.offerchaser.application.rules.RuleSetView;
import com.offerchaser.application.rules.RuleVersionConflictException;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 方向模板服务。
 * <p>
 * 模板 = 一整套可切换的预设：
 * <ul>
 *   <li>BOSS 搜索条件与投递开关（关键词/城市/行业/经验/学历/薪资/规模/融资/职位类型、
 *       AI 开关、自动发图片、重复沟通、死了的 HR 过滤等，完整保存在 boss_config_json）；</li>
 *   <li>AI 个人介绍与提示词（introduce/prompt）；</li>
 *   <li>筛选规则（RulePayload：最高学历/期望薪资/方向/城市/黑白名单/匹配模式/HR 过滤）；</li>
 *   <li>图片简历（每个模板一份独立文件）。</li>
 * </ul>
 * 激活模板会把以上内容应用到“当前配置”，之后的任务按当前配置运行；模板本身保持不变。
 * 推荐工作流：激活模板 → 用现有配置页/规则页/AI 配置页/简历上传修改 → 回到模板页「用当前配置覆盖模板」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileTemplateService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_NAME_LENGTH = 60;
    private static final Path TEMPLATE_ROOT = Paths.get(System.getProperty("user.dir"),
            "data", "boss", "templates").toAbsolutePath().normalize();

    private final ProfileTemplateRepository templateRepository;
    private final BossService bossService;
    private final AiService aiService;
    private final RuleConfigService ruleConfigService;
    private final BossResumeImageService resumeImageService;
    private final ObjectMapper objectMapper;

    /** 模板列表项（不返回完整的 config JSON，避免前端负担）。 */
    public record TemplateView(long id, String name, String direction, String description,
                               boolean active, String updatedAt, String keywords,
                               Integer expectedSalaryMin, Integer expectedSalaryMax,
                               boolean hasResume, DeliveryPolicy deliveryPolicy) {
    }

    /**
     * D1：模板级采集策略（只影响采集，不包含跟进）。
     * 每日首次沟通上限由全局预算统一控制，所有模板共享，故不在这里。
     */
    public record DeliveryPolicy(Integer collectMaxPerKeyword,
                                 Integer collectMaxDurationMinutes,
                                 Integer collectNoNewWindowSeconds) {
    }

    /** 激活结果（含提示信息）。 */
    public record ActivateResult(TemplateView template, List<String> warnings) {
    }

    /**
     * 模板整包配置编辑请求：搜索条件/薪资/公司要求/黑名单/AI 配置一次保存。
     * 所有字段可选；null 表示保持模板原值，空列表表示显式清空。
     */
    public record TemplateConfigRequest(
            List<String> keywords,
            String cityCode,
            String jobType,
            List<String> industry,
            List<String> experience,
            List<String> degree,
            List<String> salary,
            List<String> scale,
            List<String> stage,
            Integer expectedSalaryMin,
            Integer expectedSalaryMax,
            Boolean enableAI,
            Boolean sendImgResume,
            Boolean allowRepeatChat,
            Boolean filterDeadHr,
            String deadStatus,
            String sayHi,
            String repeatChatMessages,
            Integer waitTime,
            RulePayload rule,
            String aiIntroduce,
            String aiPrompt,
            DeliveryPolicy deliveryPolicy) {
    }

    /** 模板配置保存结果；applied=true 表示模板当前生效、已同步应用到运行配置。 */
    public record TemplateConfigResult(TemplateView template, boolean applied, List<String> warnings) {
    }

    public List<TemplateView> list() {
        List<TemplateView> views = new ArrayList<>();
        for (ProfileTemplateRepository.ProfileTemplate template : templateRepository.list()) {
            views.add(toView(template));
        }
        return views;
    }

    public Optional<TemplateView> active() {
        ProfileTemplateRepository.ProfileTemplate template = templateRepository.findActive();
        return template == null ? Optional.empty() : Optional.of(toView(template));
    }

    public TemplateView get(long id) {
        return toView(requireTemplate(id));
    }

    /** D1：按用户拖拽后的顺序持久化模板列表（必须包含全部模板，不能重复）。 */
    public List<TemplateView> reorderTemplates(List<Long> orderedIds) {
        List<ProfileTemplateRepository.ProfileTemplate> existing = templateRepository.list();
        List<Long> existingIds = existing.stream().map(ProfileTemplateRepository.ProfileTemplate::id).toList();
        if (orderedIds == null
                || orderedIds.isEmpty()
                || orderedIds.size() != existingIds.size()
                || new java.util.HashSet<>(orderedIds).size() != orderedIds.size()
                || !new java.util.HashSet<>(orderedIds).equals(new java.util.HashSet<>(existingIds))) {
            throw new TemplateValidationException("模板排序必须包含全部模板且不能重复");
        }
        templateRepository.reorder(orderedIds);
        log.info("方向模板顺序已保存 | order={}", orderedIds);
        return list();
    }

    /** 从当前配置创建模板（快照当前 BOSS 配置/AI/规则/简历）。 */
    public TemplateView create(String name, String direction, String description) {
        String safeName = normalizeName(name);
        requireUniqueName(safeName, null);
        ProfileTemplateRepository.ProfileTemplate inserted = templateRepository.insert(
                new ProfileTemplateRepository.ProfileTemplate(0L, safeName,
                        normalizeOptional(direction, 40), normalizeOptional(description, 300),
                        false, null, null, null, null, null, null, null, null, null));
        try {
            return captureInto(inserted.id());
        } catch (RuntimeException e) {
            try {
                templateRepository.delete(inserted.id());
            } catch (Exception ignore) {
            }
            throw e;
        }
    }

    /** 用当前配置覆盖模板（不改变名称/方向/描述）。 */
    public TemplateView capture(long id) {
        requireTemplate(id);
        return captureInto(id);
    }

    /**
     * 在配置中心直接编辑模板整包配置（搜索条件/薪资范围/公司要求/黑名单/筛选规则/AI 配置）。
     * 保存只改模板快照；若模板当前生效，会立即把快照应用到运行配置（下一轮任务按此运行）。
     */
    public TemplateConfigResult updateTemplateConfig(long id, TemplateConfigRequest request) {
        if (request == null) {
            throw new TemplateValidationException("配置不能为空");
        }
        ProfileTemplateRepository.ProfileTemplate template = requireTemplate(id);
        BossConfigEntity bossConfig = parseBossConfig(template, id);
        try {
            applyBossConfigFields(bossConfig, request);
        } catch (Exception e) {
            throw new TemplateValidationException("保存搜索配置失败: " + e.getMessage());
        }

        String ruleJson = template.rulePayloadJson();
        if (request.rule() != null) {
            RulePayload currentRule = parseRulePayload(template);
            RulePayload merged = mergeRule(currentRule, request.rule());
            try {
                merged = ruleConfigService.validateAndCanonicalize(merged);
            } catch (Exception e) {
                throw new TemplateValidationException("筛选规则校验失败: " + e.getMessage());
            }
            try {
                ruleJson = objectMapper.writeValueAsString(merged);
            } catch (Exception e) {
                throw new TemplateValidationException("保存筛选规则失败: " + e.getMessage());
            }
        }

        String aiIntroduce = request.aiIntroduce() != null ? request.aiIntroduce() : template.aiIntroduce();
        String aiPrompt = request.aiPrompt() != null ? request.aiPrompt() : template.aiPrompt();
        DeliveryPolicy currentPolicy = parseDeliveryPolicy(template.deliveryPolicyJson());
        DeliveryPolicy mergedPolicy = mergeDeliveryPolicy(currentPolicy, request.deliveryPolicy());
        validateDeliveryPolicy(mergedPolicy);
        String deliveryPolicyJson = writeDeliveryPolicy(mergedPolicy);
        String bossConfigJson;
        try {
            bossConfigJson = objectMapper.writeValueAsString(bossConfig);
        } catch (Exception e) {
            throw new TemplateValidationException("保存模板配置失败: " + e.getMessage());
        }

        ProfileTemplateRepository.ProfileTemplate updated = templateRepository.updateSnapshot(id,
                bossConfigJson, aiIntroduce, aiPrompt, ruleJson, deliveryPolicyJson, template.resumeFile());
        if (template.active()) {
            ActivateResult applied = activate(id);
            return new TemplateConfigResult(applied.template(), true, applied.warnings());
        }
        return new TemplateConfigResult(toView(updated), false,
                List.of("模板未激活：保存只更新模板快照，需在配置中心点击「激活」后下一轮任务才会使用"));
    }

    private BossConfigEntity parseBossConfig(ProfileTemplateRepository.ProfileTemplate template, long id) {
        if (template.bossConfigJson() == null || template.bossConfigJson().isBlank()) {
            BossConfigEntity current = bossService.getFirstConfig();
            if (current == null) {
                throw new TemplateValidationException("模板没有 BOSS 配置快照，无法编辑");
            }
            return current;
        }
        try {
            return objectMapper.readValue(template.bossConfigJson(), BossConfigEntity.class);
        } catch (Exception e) {
            throw new TemplateValidationException("模板 BOSS 配置快照损坏: " + e.getMessage());
        }
    }

    private void applyBossConfigFields(BossConfigEntity config, TemplateConfigRequest request) {
        if (request.keywords() != null) {
            List<String> keywords = request.keywords().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(String::trim)
                    .toList();
            config.setKeywords(writeJson(keywords));
        }
        if (request.cityCode() != null) {
            config.setCityCode(request.cityCode().isBlank() ? null : bossService.normalizeCityToName(request.cityCode().trim()));
        }
        if (request.jobType() != null) {
            config.setJobType(normalizeSingleOption("jobType", request.jobType()));
        }
        if (request.industry() != null) {
            config.setIndustry(bossService.toBracketListString(
                    bossService.toNames("industry", normalizeList(request.industry()))));
        }
        if (request.experience() != null) {
            config.setExperience(bossService.toBracketListString(
                    bossService.toNames("experience", normalizeList(request.experience()))));
        }
        if (request.degree() != null) {
            config.setDegree(bossService.toBracketListString(
                    bossService.toNames("degree", normalizeList(request.degree()))));
        }
        if (request.salary() != null) {
            config.setSalary(bossService.toBracketListString(
                    bossService.toNames("salary", normalizeList(request.salary()))));
        }
        if (request.scale() != null) {
            config.setScale(bossService.toBracketListString(
                    bossService.toNames("scale", normalizeList(request.scale()))));
        }
        if (request.stage() != null) {
            config.setStage(bossService.toBracketListString(
                    bossService.toNames("stage", normalizeList(request.stage()))));
        }
        if (request.expectedSalaryMin() != null) {
            config.setExpectedSalaryMin(request.expectedSalaryMin());
        }
        if (request.expectedSalaryMax() != null) {
            config.setExpectedSalaryMax(request.expectedSalaryMax());
        }
        if (request.enableAI() != null) {
            config.setEnableAi(request.enableAI() ? 1 : 0);
        }
        if (request.sendImgResume() != null) {
            config.setSendImgResume(request.sendImgResume() ? 1 : 0);
        }
        if (request.allowRepeatChat() != null) {
            config.setAllowRepeatChat(request.allowRepeatChat() ? 1 : 0);
        }
        if (request.filterDeadHr() != null) {
            config.setFilterDeadHr(request.filterDeadHr() ? 1 : 0);
        }
        if (request.deadStatus() != null) {
            config.setDeadStatus(request.deadStatus());
        }
        if (request.sayHi() != null) {
            config.setSayHi(request.sayHi());
        }
        if (request.repeatChatMessages() != null) {
            config.setRepeatChatMessages(request.repeatChatMessages());
        }
        if (request.waitTime() != null) {
            config.setWaitTime(request.waitTime());
        }
    }

    private String normalizeSingleOption(String type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> names = bossService.toNames(type, List.of(value.trim()));
        return names == null || names.isEmpty() ? value.trim() : names.get(0);
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new TemplateValidationException("序列化配置失败: " + e.getMessage());
        }
    }

    private RulePayload mergeRule(RulePayload current, RulePayload incoming) {
        if (current == null) {
            return incoming;
        }
        return new RulePayload(
                incoming.highestDegree() != null ? incoming.highestDegree() : current.highestDegree(),
                incoming.salaryStrategy() != null ? incoming.salaryStrategy() : current.salaryStrategy(),
                incoming.expectedSalaryMin() != null ? incoming.expectedSalaryMin() : current.expectedSalaryMin(),
                incoming.expectedSalaryMax() != null ? incoming.expectedSalaryMax() : current.expectedSalaryMax(),
                incoming.jobDirections() != null ? incoming.jobDirections() : current.jobDirections(),
                incoming.cities() != null ? incoming.cities() : current.cities(),
                incoming.blacklistCities() != null ? incoming.blacklistCities() : current.blacklistCities(),
                incoming.workTypes() != null ? incoming.workTypes() : current.workTypes(),
                incoming.blacklistMatchMode() != null ? incoming.blacklistMatchMode() : current.blacklistMatchMode(),
                incoming.blacklistJob() != null ? incoming.blacklistJob() : current.blacklistJob(),
                incoming.blacklistCompany() != null ? incoming.blacklistCompany() : current.blacklistCompany(),
                incoming.blacklistRecruiter() != null ? incoming.blacklistRecruiter() : current.blacklistRecruiter(),
                incoming.whitelist() != null ? incoming.whitelist() : current.whitelist(),
                incoming.filterDeadHr() != null ? incoming.filterDeadHr() : current.filterDeadHr(),
                incoming.deadStatus() != null ? incoming.deadStatus() : current.deadStatus());
    }

    /**
     * 静默地把当前配置快照写回“当前生效模板”。
     * 现有编辑页（BOSS 配置/AI 配置/规则/简历）保存后调用，保证模板与当前配置不漂移。
     * 没有生效模板时不做任何事。
     */
    public TemplateView captureActiveFromCurrent() {
        ProfileTemplateRepository.ProfileTemplate active = templateRepository.findActive();
        if (active == null) {
            return null;
        }
        try {
            return captureInto(active.id());
        } catch (Exception e) {
            log.warn("自动同步当前配置到生效模板失败 | id={} | {}", active.id(), e.getMessage());
            return null;
        }
    }

    /**
     * 任务启动前校验：若当前配置与生效模板不一致，以模板为准重新应用。
     * 这是“模板优先级高于当前配置”的最终保证；返回 null 表示一致、无需动作。
     */
    public ActivateResult ensureActiveApplied() {
        ProfileTemplateRepository.ProfileTemplate template = templateRepository.findActive();
        if (template == null) {
            return null;
        }
        List<String> warnings = new ArrayList<>();
        boolean changed = false;
        try {
            if (template.bossConfigJson() != null && !template.bossConfigJson().isBlank()) {
                BossConfigEntity templateConfig = objectMapper.readValue(template.bossConfigJson(), BossConfigEntity.class);
                BossConfigEntity currentConfig = bossService.getFirstConfig();
                if (!bossSignature(templateConfig).equals(bossSignature(currentConfig))) {
                    applyBossConfig(template, warnings);
                    changed = true;
                    warnings.add("启动前已按生效模板恢复 BOSS 搜索/投递配置");
                }
            }
            AiEntity currentAi = aiService.getAiConfig();
            String currentIntroduce = currentAi == null ? null : currentAi.getIntroduce();
            String currentPrompt = currentAi == null ? null : currentAi.getPrompt();
            if (!java.util.Objects.equals(template.aiIntroduce(), currentIntroduce)
                    || !java.util.Objects.equals(template.aiPrompt(), currentPrompt)) {
                applyAiConfig(template, warnings);
                changed = true;
                warnings.add("启动前已按生效模板恢复 AI 介绍/提示词");
            }
            RulePayload templateRules = parseRulePayload(template);
            if (templateRules != null) {
                RulePayload currentRules = ruleConfigService.getActive().rules();
                if (!rulePayloadSignature(templateRules).equals(rulePayloadSignature(currentRules))) {
                    applyRuleConfig(template, warnings);
                    changed = true;
                    warnings.add("启动前已按生效模板恢复筛选规则");
                }
            }
            if (resumeDiffers(template)) {
                applyResume(template, warnings);
                changed = true;
                warnings.add("启动前已按生效模板恢复图片简历");
            }
        } catch (Exception e) {
            log.warn("启动前校验生效模板失败，继续使用当前配置：{}", e.getMessage());
            return null;
        }
        if (!changed) {
            return null;
        }
        log.info("启动前已按生效模板校正当前配置 | id={} | name={}", template.id(), template.name());
        return new ActivateResult(toView(requireTemplate(template.id())), warnings);
    }

    private RulePayload parseRulePayload(ProfileTemplateRepository.ProfileTemplate template) {
        if (template.rulePayloadJson() == null || template.rulePayloadJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(template.rulePayloadJson(), RulePayload.class);
        } catch (Exception e) {
            log.warn("解析模板规则快照失败 | id={} | {}", template.id(), e.getMessage());
            return null;
        }
    }

    private String bossSignature(BossConfigEntity entity) throws Exception {
        if (entity == null) {
            return "null";
        }
        Map<String, Object> map = objectMapper.convertValue(entity,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        map.remove("id");
        map.remove("createdAt");
        map.remove("updatedAt");
        return objectMapper.writeValueAsString(map);
    }

    private String rulePayloadSignature(RulePayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }

    private boolean resumeDiffers(ProfileTemplateRepository.ProfileTemplate template) {
        String resumeFile = template.resumeFile();
        if (resumeFile == null || resumeFile.isBlank()) {
            return false;
        }
        Path templatePath = Paths.get(resumeFile);
        if (!Files.isRegularFile(templatePath)) {
            return false;
        }
        Optional<Path> currentPath = resumeImageService.getCurrentImagePath();
        if (currentPath.isEmpty()) {
            return true;
        }
        return !sha256(templatePath).equals(sha256(currentPath.get()));
    }

    private static String sha256(Path path) {
        try (var input = Files.newInputStream(path)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "hash-error:" + e.getMessage();
        }
    }

    private TemplateView captureInto(long id) {
        ProfileTemplateRepository.ProfileTemplate template = requireTemplate(id);
        BossConfigEntity bossConfig = bossService.getFirstConfig();
        AiEntity aiConfig = aiService.getAiConfig();
        RuleSetView rules = ruleConfigService.getActive();
        String bossConfigJson;
        String ruleJson;
        try {
            bossConfigJson = bossConfig == null ? null : objectMapper.writeValueAsString(bossConfig);
            ruleJson = objectMapper.writeValueAsString(rules.rules());
        } catch (Exception e) {
            throw new TemplateValidationException("保存模板快照失败: " + e.getMessage());
        }
        String resumeFile = copyCurrentResumeToTemplate(id);
        ProfileTemplateRepository.ProfileTemplate updated = templateRepository.updateSnapshot(id,
                bossConfigJson,
                aiConfig == null ? null : aiConfig.getIntroduce(),
                aiConfig == null ? null : aiConfig.getPrompt(),
                ruleJson,
                template.deliveryPolicyJson(),
                resumeFile);
        log.info("方向模板已从当前配置更新 | id={} | name={}", id, template.name());
        return toView(updated);
    }

    /** 激活模板：把模板快照应用到当前配置；任务下一轮生效。 */
    public ActivateResult activate(long id) {
        ProfileTemplateRepository.ProfileTemplate template = requireTemplate(id);
        List<String> warnings = new ArrayList<>();
        try {
            applyBossConfig(template, warnings);
            applyAiConfig(template, warnings);
            applyRuleConfig(template, warnings);
            applyResume(template, warnings);
            templateRepository.setActive(id);
        } catch (TemplateValidationException | TemplateNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("激活方向模板失败 | id={} | {}", id, e.getMessage(), e);
            throw new TemplateValidationException("激活模板失败: " + e.getMessage());
        }
        log.info("方向模板已激活 | id={} | name={}", id, template.name());
        return new ActivateResult(toView(requireTemplate(id)), warnings);
    }

    private void applyBossConfig(ProfileTemplateRepository.ProfileTemplate template, List<String> warnings) {
        if (template.bossConfigJson() == null || template.bossConfigJson().isBlank()) {
            warnings.add("模板没有 BOSS 配置快照，搜索条件/投递开关保持当前值");
            return;
        }
        try {
            BossConfigEntity snapshot = objectMapper.readValue(template.bossConfigJson(), BossConfigEntity.class);
            BossConfigEntity current = bossService.getFirstConfig();
            if (current == null) {
                snapshot.setId(null);
                snapshot.setCreatedAt(LocalDateTime.now());
                bossService.saveConfig(snapshot);
            } else {
                snapshot.setId(current.getId());
                snapshot.setCreatedAt(current.getCreatedAt());
                bossService.updateConfig(snapshot);
            }
        } catch (Exception e) {
            throw new TemplateValidationException("应用模板 BOSS 配置失败: " + e.getMessage());
        }
    }

    private void applyAiConfig(ProfileTemplateRepository.ProfileTemplate template, List<String> warnings) {
        if ((template.aiIntroduce() == null || template.aiIntroduce().isBlank())
                && (template.aiPrompt() == null || template.aiPrompt().isBlank())) {
            warnings.add("模板没有 AI 介绍/提示词，AI 配置保持当前值");
            return;
        }
        aiService.saveOrUpdateAiConfig(template.aiIntroduce(), template.aiPrompt());
    }

    private void applyRuleConfig(ProfileTemplateRepository.ProfileTemplate template, List<String> warnings) {
        if (template.rulePayloadJson() == null || template.rulePayloadJson().isBlank()) {
            warnings.add("模板没有筛选规则快照，规则保持当前值");
            return;
        }
        RulePayload payload;
        try {
            payload = objectMapper.readValue(template.rulePayloadJson(), RulePayload.class);
        } catch (Exception e) {
            throw new TemplateValidationException("解析模板筛选规则失败: " + e.getMessage());
        }
        // 先把 live 黑名单表同步为模板规则里的黑白名单，避免下一轮触发自动合并时把模板规则覆盖掉
        syncBlacklistTable(payload);
        int currentVersion = ruleConfigService.currentVersion();
        try {
            ruleConfigService.save(payload, currentVersion, "激活方向模板：" + template.name());
        } catch (RuleVersionConflictException conflict) {
            ruleConfigService.save(payload, conflict.getCurrentVersion(), "激活方向模板：" + template.name());
        }
    }

    private void syncBlacklistTable(RulePayload payload) {
        try {
            List<BlacklistEntity> existing = bossService.getAllBlacklist();
            for (BlacklistEntity item : existing) {
                if (item.getType() != null && item.getValue() != null) {
                    bossService.removeBlacklist(item.getType(), item.getValue());
                }
            }
            addBlacklists("job", payload.blacklistJob());
            addBlacklists("company", payload.blacklistCompany());
            addBlacklists("recruiter", payload.blacklistRecruiter());
        } catch (Exception e) {
            log.warn("同步模板黑名单到 boss_blacklist 失败（规则本身仍会生效）：{}", e.getMessage());
        }
    }

    private void addBlacklists(String type, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                bossService.addBlacklist(type, value);
            }
        }
    }

    private void applyResume(ProfileTemplateRepository.ProfileTemplate template, List<String> warnings) {
        String resumeFile = template.resumeFile();
        if (resumeFile == null || resumeFile.isBlank()) {
            warnings.add("模板没有独立简历，沿用当前图片简历");
            return;
        }
        Path path = Paths.get(resumeFile);
        if (!Files.isRegularFile(path)) {
            warnings.add("模板简历文件不存在，沿用当前图片简历");
            return;
        }
        try {
            resumeImageService.saveFromPath(path);
        } catch (Exception e) {
            throw new TemplateValidationException("应用模板简历失败: " + e.getMessage());
        }
    }

    /** 用当前配置覆盖模板的简历文件。 */
    public TemplateView uploadResume(long id, MultipartFile file) {
        ProfileTemplateRepository.ProfileTemplate template = requireTemplate(id);
        try {
            Path directory = templateDirectory(id);
            Path saved = resumeImageService.saveToDirectory(file, directory, "resume");
            templateRepository.updateResumeFile(id, saved.toString());
            if (template.active()) {
                resumeImageService.saveFromPath(saved);
            }
        } catch (IllegalArgumentException e) {
            throw new TemplateValidationException(e.getMessage());
        } catch (IOException e) {
            throw new TemplateValidationException("保存模板简历失败: " + e.getMessage());
        }
        log.info("模板简历已上传 | id={} | name={}", id, template.name());
        return get(id);
    }

    /** 模板简历文件（供预览/下载）；不存在返回空。 */
    public Optional<Path> resumePath(long id) {
        ProfileTemplateRepository.ProfileTemplate template = requireTemplate(id);
        String resumeFile = template.resumeFile();
        if (resumeFile == null || resumeFile.isBlank()) {
            return Optional.empty();
        }
        Path path = Paths.get(resumeFile);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    public TemplateView updateMetadata(long id, String name, String direction, String description) {
        ProfileTemplateRepository.ProfileTemplate template = requireTemplate(id);
        String safeName = normalizeName(name);
        requireUniqueName(safeName, template.id());
        ProfileTemplateRepository.ProfileTemplate updated = templateRepository.updateMetadata(id, safeName,
                normalizeOptional(direction, 40), normalizeOptional(description, 300));
        return toView(updated);
    }

    public TemplateView duplicate(long id, String name) {
        ProfileTemplateRepository.ProfileTemplate source = requireTemplate(id);
        String safeName = normalizeName(name);
        requireUniqueName(safeName, null);
        ProfileTemplateRepository.ProfileTemplate inserted = templateRepository.insert(
                new ProfileTemplateRepository.ProfileTemplate(0L, safeName,
                        source.direction(), source.description(), false,
                        source.bossConfigJson(), source.aiIntroduce(), source.aiPrompt(),
                        source.rulePayloadJson(), source.deliveryPolicyJson(), null, null, null, null));
        try {
            copyTemplateResume(source, inserted.id());
            templateRepository.updateSnapshot(inserted.id(), source.bossConfigJson(),
                    source.aiIntroduce(), source.aiPrompt(), source.rulePayloadJson(),
                    source.deliveryPolicyJson(), templateResumeFile(inserted.id()));
        } catch (Exception e) {
            templateRepository.delete(inserted.id());
            throw new TemplateValidationException("复制模板失败: " + e.getMessage());
        }
        return get(inserted.id());
    }

    public void delete(long id) {
        ProfileTemplateRepository.ProfileTemplate template = requireTemplate(id);
        if (template.active()) {
            throw new TemplateValidationException("当前生效的模板不能删除，请先激活其他模板");
        }
        templateRepository.delete(id);
        try {
            deleteDirectory(templateDirectory(id));
        } catch (Exception e) {
            log.warn("删除模板简历目录失败: {}", e.getMessage());
        }
        log.info("方向模板已删除 | id={} | name={}", id, template.name());
    }

    // ==================== 内部 ====================

    private ProfileTemplateRepository.ProfileTemplate requireTemplate(long id) {
        ProfileTemplateRepository.ProfileTemplate template = templateRepository.findById(id);
        if (template == null) {
            throw new TemplateNotFoundException("方向模板不存在: " + id);
        }
        return template;
    }

    private void requireUniqueName(String name, Long excludeId) {
        for (ProfileTemplateRepository.ProfileTemplate existing : templateRepository.list()) {
            if (existing.name().equals(name) && (excludeId == null || existing.id() != excludeId)) {
                throw new TemplateValidationException("模板名称已存在: " + name);
            }
        }
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new TemplateValidationException("模板名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new TemplateValidationException("模板名称最多 " + MAX_NAME_LENGTH + " 字");
        }
        return trimmed;
    }

    private String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private TemplateView toView(ProfileTemplateRepository.ProfileTemplate template) {
        String keywords = null;
        Integer min = null;
        Integer max = null;
        if (template.bossConfigJson() != null && !template.bossConfigJson().isBlank()) {
            try {
                BossConfigEntity config = objectMapper.readValue(template.bossConfigJson(), BossConfigEntity.class);
                keywords = prettifyKeywords(config.getKeywords());
            } catch (Exception e) {
                log.debug("解析模板配置摘要失败 | id={} | {}", template.id(), e.getMessage());
            }
        }
        // A 区期望月薪已废弃；模板卡展示统一改用筛选规则的期望下限。
        RulePayload rules = parseRulePayload(template);
        if (rules != null) {
            min = positiveOrNull(rules.expectedSalaryMin());
            max = positiveOrNull(rules.expectedSalaryMax());
        }
        return new TemplateView(template.id(), template.name(), template.direction(),
                template.description(), template.active(), template.updatedAt(), keywords,
                min, max, template.resumeFile() != null && !template.resumeFile().isBlank(),
                parseDeliveryPolicy(template.deliveryPolicyJson()));
    }

    /** 0/负数视为未设置；模板卡只展示有效薪资。 */
    private static Integer positiveOrNull(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private DeliveryPolicy parseDeliveryPolicy(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, DeliveryPolicy.class);
        } catch (Exception e) {
            log.warn("解析模板采集策略失败: {}", e.getMessage());
            return null;
        }
    }

    private String writeDeliveryPolicy(DeliveryPolicy policy) {
        if (policy == null
                || (policy.collectMaxPerKeyword() == null
                && policy.collectMaxDurationMinutes() == null
                && policy.collectNoNewWindowSeconds() == null)) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(policy);
        } catch (Exception e) {
            throw new TemplateValidationException("保存采集策略失败: " + e.getMessage());
        }
    }

    private DeliveryPolicy mergeDeliveryPolicy(DeliveryPolicy current, DeliveryPolicy incoming) {
        if (incoming == null) {
            return current;
        }
        if (current == null) {
            current = new DeliveryPolicy(null, null, null);
        }
        return new DeliveryPolicy(
                mergePolicyValue(current.collectMaxPerKeyword(), incoming.collectMaxPerKeyword()),
                mergePolicyValue(current.collectMaxDurationMinutes(), incoming.collectMaxDurationMinutes()),
                mergePolicyValue(current.collectNoNewWindowSeconds(), incoming.collectNoNewWindowSeconds()));
    }

    /** null=保持；-1=清除该字段回退全局默认；其余数值按原义保存。 */
    private Integer mergePolicyValue(Integer current, Integer incoming) {
        if (incoming == null) {
            return current;
        }
        if (incoming == -1) {
            return null;
        }
        return incoming;
    }

    private void validateDeliveryPolicy(DeliveryPolicy policy) {
        if (policy == null) {
            return;
        }
        if (policy.collectMaxPerKeyword() != null && policy.collectMaxPerKeyword() < -1) {
            throw new TemplateValidationException("采集策略：每关键词候选上限不能为负数（-1 表示恢复全局默认）");
        }
        if (policy.collectMaxDurationMinutes() != null && policy.collectMaxDurationMinutes() < -1) {
            throw new TemplateValidationException("采集策略：单关键词采集时长不能为负数（-1 表示恢复全局默认）");
        }
        if (policy.collectNoNewWindowSeconds() != null && policy.collectNoNewWindowSeconds() < -1) {
            throw new TemplateValidationException("采集策略：无新增判定秒数不能为负数（-1 表示恢复全局默认）");
        }
    }

    private static String prettifyKeywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        text = text.replace("\"", "").replace("'", "");
        List<String> parts = new ArrayList<>();
        for (String item : text.split(",")) {
            if (!item.isBlank()) {
                parts.add(item.trim());
            }
        }
        return String.join("、", parts);
    }

    private String copyCurrentResumeToTemplate(long id) {
        Optional<Path> current = resumeImageService.getCurrentImagePath();
        if (current.isEmpty()) {
            return templateResumeFile(id);
        }
        try {
            Path directory = templateDirectory(id);
            Files.createDirectories(directory);
            String fileName = current.get().getFileName().toString();
            Path target = directory.resolve(fileName);
            Files.copy(current.get(), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            log.warn("复制当前简历到模板失败: {}", e.getMessage());
            return null;
        }
    }

    private void copyTemplateResume(ProfileTemplateRepository.ProfileTemplate source, long targetId) {
        if (source.resumeFile() == null) {
            return;
        }
        Path sourcePath = Paths.get(source.resumeFile());
        if (!Files.isRegularFile(sourcePath)) {
            return;
        }
        try {
            Path directory = templateDirectory(targetId);
            Files.createDirectories(directory);
            Path target = directory.resolve(sourcePath.getFileName().toString());
            Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new TemplateValidationException("复制模板简历失败: " + e.getMessage());
        }
    }

    private String templateResumeFile(long id) {
        Path directory = templateDirectory(id);
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("resume."))
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Path templateDirectory(long id) {
        return TEMPLATE_ROOT.resolve(String.valueOf(id));
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    /** 供前端显示模板完整快照（配置中心编辑器回填用）。 */
    public Map<String, Object> detail(long id) {
        ProfileTemplateRepository.ProfileTemplate template = requireTemplate(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("template", toView(template));
        data.put("bossConfig", parseBossConfigView(template));
        data.put("rule", parseRulePayload(template));
        data.put("aiIntroduce", template.aiIntroduce());
        data.put("aiPrompt", template.aiPrompt());
        data.put("deliveryPolicy", parseDeliveryPolicy(template.deliveryPolicyJson()));
        data.put("resumeFile", template.resumeFile());
        return data;
    }

    private Map<String, Object> parseBossConfigView(ProfileTemplateRepository.ProfileTemplate template) {
        if (template.bossConfigJson() == null || template.bossConfigJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(template.bossConfigJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("解析模板 BOSS 配置失败 | id={} | {}", template.id(), e.getMessage());
            return null;
        }
    }
}
