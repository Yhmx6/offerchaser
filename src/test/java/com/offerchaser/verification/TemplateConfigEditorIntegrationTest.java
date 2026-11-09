package com.offerchaser.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.entity.BossConfigEntity;
import com.offerchaser.application.persistence.ProfileTemplateRepository;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.rules.RulePayload;
import com.offerchaser.application.rules.RuleSetView;
import com.offerchaser.application.rules.RuleValidationException;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.application.templates.TemplateValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置中心模板内编辑（PUT /api/boss/templates/{id}/config）真实 SQLite 集成验收：
 * detail 解析对象；非生效模板只存快照（applied=false）；生效模板保存后立即应用（applied=true）；
 * rule=null 保留、部分字段 merge 后校验；非法规则拒绝且快照不变；搜索字段规范化与 HR 双写。
 */
class TemplateConfigEditorIntegrationTest {

    @TempDir
    static Path tempRoot;

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ProfileTemplateRepository repository;
    private BossService bossService;
    private AiService aiService;
    private RuleConfigService ruleConfigService;
    private BossResumeImageService resumeImageService;
    private ProfileTemplateService service;
    private Path currentResume;

    @BeforeEach
    void setUp() throws Exception {
        Path templateRoot = tempRoot.resolve("editor-templates");
        deleteDirectory(templateRoot);
        overrideTemplateRoot(templateRoot);

        SQLiteDataSource dataSource = migratedDataSource(tempDir);
        repository = new ProfileTemplateRepository(dataSource);
        bossService = mock(BossService.class);
        aiService = mock(AiService.class);
        ruleConfigService = mock(RuleConfigService.class);
        resumeImageService = mock(BossResumeImageService.class);
        service = new ProfileTemplateService(repository, bossService, aiService,
                ruleConfigService, resumeImageService, mapper);

        currentResume = tempRoot.resolve("current-" + System.nanoTime()).resolve("resume.png");
        Files.createDirectories(currentResume.getParent());
        Files.write(currentResume, new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        when(resumeImageService.getCurrentImagePath()).thenReturn(Optional.of(currentResume));

        when(bossService.getFirstConfig()).thenReturn(configA());
        when(bossService.normalizeCityToName(anyString())).thenReturn("上海");
        when(bossService.toNames(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(bossService.toBracketListString(any())).thenAnswer(inv -> {
            List<?> names = inv.getArgument(0);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(names.get(i));
            }
            return sb.append("]").toString();
        });
        when(bossService.getAllBlacklist()).thenReturn(List.of());
        when(aiService.getAiConfig()).thenReturn(aiA());
        when(aiService.saveOrUpdateAiConfig(any(), any())).thenReturn(aiA());
        when(ruleConfigService.getActive()).thenReturn(ruleViewA());
        when(ruleConfigService.currentVersion()).thenReturn(1);
        when(ruleConfigService.validateAndCanonicalize(any(RulePayload.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ruleConfigService.save(any(RulePayload.class), anyInt(), any())).thenReturn(ruleViewA());
    }

    // ==================== detail ====================

    @Test
    void detailReturnsParsedBossConfigRuleAndAi() {
        long id = service.create("产品方向", "产品", "说明").id();

        Map<String, Object> detail = service.detail(id);

        assertNotNull(detail.get("template"));
        @SuppressWarnings("unchecked")
        Map<String, Object> bossConfig = (Map<String, Object>) detail.get("bossConfig");
        assertNotNull(bossConfig, "detail 应返回解析后的 bossConfig 对象（不是 JSON 字符串）");
        assertTrue(String.valueOf(bossConfig.get("keywords")).contains("Java"));
        assertEquals(30, bossConfig.get("expectedSalaryMin"));
        RulePayload rule = (RulePayload) detail.get("rule");
        assertNotNull(rule);
        assertEquals("本科", rule.highestDegree());
        assertEquals(List.of("产品"), rule.jobDirections());
        assertEquals("模板介绍A", detail.get("aiIntroduce"));
        assertEquals("模板提示词A", detail.get("aiPrompt"));
        assertNotNull(detail.get("resumeFile"), "detail 应返回模板简历路径");
    }

    // ==================== 非生效模板：只存快照 ====================

    @Test
    void nonActiveTemplateSaveOnlyUpdatesSnapshot() throws Exception {
        long id = service.create("产品方向", "产品", "描述").id();
        ProfileTemplateService.TemplateConfigRequest request = new ProfileTemplateService.TemplateConfigRequest(
                List.of("产品", "AI产品"), "101020100", null, null, null, null, null, null, null,
                40, 60, true, false, true, true, "今日活跃",
                "你好，我是产品经理", null, null, null, "新介绍", "新提示词", null);

        ProfileTemplateService.TemplateConfigResult result = service.updateTemplateConfig(id, request);

        assertFalse(result.applied(), "非生效模板保存不应立即应用");
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("未激活"));
        ProfileTemplateRepository.ProfileTemplate stored = repository.findById(id);
        assertEquals("产品方向", stored.name(), "保存配置不得改名称");
        assertEquals("产品", stored.direction(), "保存配置不得改方向");
        JsonNode boss = mapper.readTree(stored.bossConfigJson());
        assertEquals("[\"产品\",\"AI产品\"]", boss.get("keywords").asText());
        assertEquals("上海", boss.get("cityCode").asText());
        assertEquals(40, boss.get("expectedSalaryMin").asInt());
        assertEquals(60, boss.get("expectedSalaryMax").asInt());
        assertEquals(1, boss.get("enableAi").asInt());
        assertEquals(1, boss.get("filterDeadHr").asInt());
        assertEquals("今日活跃", boss.get("deadStatus").asText());
        assertEquals("你好，我是产品经理", boss.get("sayHi").asText());
        assertEquals("新介绍", stored.aiIntroduce());
        assertEquals("新提示词", stored.aiPrompt());
        assertNull(repository.findActive(), "非生效模板保存不得激活");
    }

    @Test
    void nullKeepsOriginalButEmptyListClears() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        service.updateTemplateConfig(id, new ProfileTemplateService.TemplateConfigRequest(
                List.of("Java"), null, null, List.of("互联网", "电商"), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        JsonNode first = mapper.readTree(repository.findById(id).bossConfigJson());
        assertEquals("[\"Java\"]", first.get("keywords").asText());
        assertEquals("[互联网,电商]", first.get("industry").asText());

        // 只显式清空 keywords；其它 null 字段保留
        service.updateTemplateConfig(id, new ProfileTemplateService.TemplateConfigRequest(
                List.of(), null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        JsonNode second = mapper.readTree(repository.findById(id).bossConfigJson());
        assertEquals("[]", second.get("keywords").asText(), "[] 应显式清空关键词");
        assertEquals("[互联网,电商]", second.get("industry").asText(), "null 字段应保留原值");
        assertEquals("模板介绍A", repository.findById(id).aiIntroduce(), "null AI 字段应保留");
    }

    @Test
    void ruleNullKeepsRuleButPartialRuleMergesWithExisting() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        String originalRule = repository.findById(id).rulePayloadJson();

        // rule=null → 保留
        service.updateTemplateConfig(id, new ProfileTemplateService.TemplateConfigRequest(
                List.of("Java"), null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertEquals(originalRule, repository.findById(id).rulePayloadJson());

        // 只传 highestDegree → 其它规则字段保留
        RulePayload partial = new RulePayload("硕士", null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        service.updateTemplateConfig(id, new ProfileTemplateService.TemplateConfigRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, partial, null, null, null));

        ArgumentCaptor<RulePayload> captor = ArgumentCaptor.forClass(RulePayload.class);
        verify(ruleConfigService).validateAndCanonicalize(captor.capture());
        RulePayload merged = captor.getValue();
        assertEquals("硕士", merged.highestDegree(), "传入字段覆盖");
        assertEquals("INTERSECT", merged.salaryStrategy(), "未传字段保留原值");
        assertEquals(30, merged.expectedSalaryMin());
        assertEquals(List.of("产品"), merged.jobDirections());
        assertEquals(List.of("外包"), merged.blacklistJob());
        assertEquals("CONTAINS", merged.blacklistMatchMode());
    }

    @Test
    void invalidRuleIsRejectedAndSnapshotUnchanged() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        String beforeBoss = repository.findById(id).bossConfigJson();
        String beforeRule = repository.findById(id).rulePayloadJson();
        when(ruleConfigService.validateAndCanonicalize(any(RulePayload.class)))
                .thenThrow(new RuleValidationException("学历非法"));

        TemplateValidationException error = assertThrows(TemplateValidationException.class,
                () -> service.updateTemplateConfig(id, new ProfileTemplateService.TemplateConfigRequest(
                        List.of("新关键词"), null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null,
                        new RulePayload("初中", null, null, null, null, null, null,
                                null, null, null, null, null, null, null), null, null, null)));

        assertTrue(error.getMessage().contains("筛选规则校验失败"), error.getMessage());
        assertEquals(beforeBoss, repository.findById(id).bossConfigJson(),
                "规则非法时 BOSS 配置快照也不得写入（整包校验失败）");
        assertEquals(beforeRule, repository.findById(id).rulePayloadJson());
    }

    @Test
    void searchFieldsAreNormalized() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        service.updateTemplateConfig(id, new ProfileTemplateService.TemplateConfigRequest(
                List.of("  Java  ", "后端", "  "), " 101020100 ", "全职",
                List.of("互联网", " 电商 "), List.of("3-5年"), List.of("本科"),
                List.of("20-30K"), List.of("100-499人"), List.of("不需要融资"),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        JsonNode boss = mapper.readTree(repository.findById(id).bossConfigJson());
        assertEquals("[\"Java\",\"后端\"]", boss.get("keywords").asText(), "关键词应去空白并写 JSON 数组");
        assertEquals("上海", boss.get("cityCode").asText(), "城市码应规范化为中文名");
        assertEquals("[互联网,电商]", boss.get("industry").asText(), "多选项应写括号列表");
        assertEquals("[3-5年]", boss.get("experience").asText());
        assertEquals("[20-30K]", boss.get("salary").asText());
    }

    @Test
    void hrFilterAndDeadStatusAreWrittenConsistently() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        RulePayload rule = new RulePayload("本科", "INTERSECT", 30, 50, List.of("产品"),
                List.of("上海"), List.of(), "CONTAINS", List.of("外包"), List.of(), List.of(),
                List.of(), true, List.of("今日活跃"));
        service.updateTemplateConfig(id, new ProfileTemplateService.TemplateConfigRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, true, "今日活跃", null, null, null, rule, null, null, null));

        JsonNode boss = mapper.readTree(repository.findById(id).bossConfigJson());
        assertEquals(1, boss.get("filterDeadHr").asInt(), "HR 过滤应写入 BOSS 配置");
        assertEquals("今日活跃", boss.get("deadStatus").asText());
        JsonNode ruleJson = mapper.readTree(repository.findById(id).rulePayloadJson());
        assertTrue(ruleJson.get("filterDeadHr").asBoolean(), "规则侧 HR 过滤同步");
        assertEquals("今日活跃", ruleJson.get("deadStatus").get(0).asText());
    }

    // ==================== 生效模板：保存后立即应用 ====================

    @Test
    void activeTemplateSaveAppliesImmediately() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        service.activate(id);
        org.mockito.Mockito.clearInvocations(bossService, aiService, ruleConfigService, resumeImageService);

        ProfileTemplateService.TemplateConfigResult result = service.updateTemplateConfig(id,
                new ProfileTemplateService.TemplateConfigRequest(
                        List.of("新产品关键词"), "101020100", null, null, null, null, null, null, null,
                        50, 70, true, false, true, false, "", "您好，产品方向",
                        null, null,
                        new RulePayload("硕士", "FLOOR", 50, 70, List.of("产品"),
                                List.of("上海"), List.of(), "CONTAINS", List.of(), List.of(),
                                List.of(), List.of(), false, List.of()),
                        "应用介绍", "应用提示词", null));

        assertTrue(result.applied(), "生效模板保存后应立即应用");
        assertTrue(repository.findById(id).active(), "保存后模板仍生效");
        ArgumentCaptor<BossConfigEntity> bossCaptor = ArgumentCaptor.forClass(BossConfigEntity.class);
        verify(bossService).updateConfig(bossCaptor.capture());
        assertTrue(bossCaptor.getValue().getKeywords().contains("新产品关键词"));
        assertEquals(50, bossCaptor.getValue().getExpectedSalaryMin());
        verify(aiService).saveOrUpdateAiConfig("应用介绍", "应用提示词");
        ArgumentCaptor<RulePayload> ruleCaptor = ArgumentCaptor.forClass(RulePayload.class);
        verify(ruleConfigService).save(ruleCaptor.capture(), eq(1), anyString());
        assertEquals("硕士", ruleCaptor.getValue().highestDegree());
        assertEquals("FLOOR", ruleCaptor.getValue().salaryStrategy());
        verify(resumeImageService).saveFromPath(eq(Paths.get(repository.findById(id).resumeFile())));
    }

    @Test
    void templateWithoutBossConfigSnapshotFallsBackToCurrentConfig() {
        // 无快照的模板（手工插入）可用当前 BOSS 配置作为编辑基础
        ProfileTemplateRepository.ProfileTemplate inserted = repository.insert(
                new ProfileTemplateRepository.ProfileTemplate(0L, "空快照模板", "X", null, false,
                        null, null, null, null, null, null, null, null, null));
        when(bossService.getFirstConfig()).thenReturn(configA());

        ProfileTemplateService.TemplateConfigResult result = service.updateTemplateConfig(inserted.id(),
                new ProfileTemplateService.TemplateConfigRequest(
                        List.of("Java"), null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        assertNotNull(result.template());
        assertNotNull(repository.findById(inserted.id()).bossConfigJson());
    }

    // ==================== 工具 ====================

    private static BossConfigEntity configA() {
        BossConfigEntity config = new BossConfigEntity();
        config.setId(1L);
        config.setKeywords("[\"Java\",\"后端\"]");
        config.setCityCode("上海");
        config.setExpectedSalaryMin(30);
        config.setExpectedSalaryMax(50);
        config.setEnableAi(1);
        config.setFilterDeadHr(0);
        config.setCreatedAt(java.time.LocalDateTime.of(2026, 11, 6, 10, 0));
        return config;
    }

    private static AiEntity aiA() {
        AiEntity ai = new AiEntity();
        ai.setIntroduce("模板介绍A");
        ai.setPrompt("模板提示词A");
        return ai;
    }

    private RulePayload payloadA() {
        return new RulePayload("本科", "INTERSECT", 30, 50, List.of("产品"), List.of("上海"),
                List.of(), "CONTAINS", List.of("外包"), List.of(), List.of(), List.of(),
                false, List.of());
    }

    private RuleSetView ruleViewA() {
        return new RuleSetView(1, "hash-a", "2026-11-06 10:00:00", "模板A", payloadA());
    }

    private static void overrideTemplateRoot(Path templateRoot) throws Exception {
        Class.forName(ProfileTemplateService.class.getName()); // 先触发类初始化，避免静态初始化覆盖 TEMPLATE_ROOT
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Field rootField = ProfileTemplateService.class.getDeclaredField("TEMPLATE_ROOT");
        rootField.setAccessible(true);
        Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, rootField);
        long offset = (long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, rootField);
        unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                .invoke(unsafe, base, offset, templateRoot.toAbsolutePath().normalize());
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

