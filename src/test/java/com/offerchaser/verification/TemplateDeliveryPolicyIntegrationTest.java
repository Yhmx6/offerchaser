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
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D1：方向模板级采集策略（delivery_policy_json）服务层集成验收：
 * 保存/回填、null 保持、-1 清除、0 保存、负 &lt; -1 拒绝且快照不变、capture 保留、duplicate 复制、旧模板 null 兼容。
 * 口径：策略只含采集三项（每关键词上限/单关键词时长/无新增窗口）；每日首次沟通上限为全局预算，不进模板。
 */
class TemplateDeliveryPolicyIntegrationTest {

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

    @BeforeEach
    void setUp() throws Exception {
        Path templateRoot = tempRoot.resolve("d1-templates");
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
        when(resumeImageService.getCurrentImagePath()).thenReturn(Optional.empty());
    }

    // ==================== 保存/回填 ====================

    @Test
    void savePolicyAndDetailRoundTrip() throws Exception {
        long id = service.create("产品方向", "产品", null).id();

        ProfileTemplateService.TemplateConfigResult result = service.updateTemplateConfig(id,
                requestWithPolicy(policy(12, 4, 9)));

        assertFalse(result.applied());
        ProfileTemplateService.DeliveryPolicy viewPolicy = result.template().deliveryPolicy();
        assertNotNull(viewPolicy);
        assertEquals(12, viewPolicy.collectMaxPerKeyword());
        assertEquals(4, viewPolicy.collectMaxDurationMinutes());
        assertEquals(9, viewPolicy.collectNoNewWindowSeconds());

        ProfileTemplateService.DeliveryPolicy detail = (ProfileTemplateService.DeliveryPolicy) service.detail(id).get("deliveryPolicy");
        assertNotNull(detail, "detail 必须回填 deliveryPolicy 对象");
        assertEquals(12, detail.collectMaxPerKeyword());
        assertEquals(4, detail.collectMaxDurationMinutes());
        assertEquals(9, detail.collectNoNewWindowSeconds());

        JsonNode stored = mapper.readTree(repository.findById(id).deliveryPolicyJson());
        assertEquals(12, stored.get("collectMaxPerKeyword").asInt());
    }

    @Test
    void nullRequestPolicyKeepsExistingAndNullFieldsKeep() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        service.updateTemplateConfig(id, requestWithPolicy(policy(12, 4, 9)));

        // request.deliveryPolicy=null → 完全保持
        service.updateTemplateConfig(id, requestWithPolicy(null));
        JsonNode kept = mapper.readTree(repository.findById(id).deliveryPolicyJson());
        assertEquals(12, kept.get("collectMaxPerKeyword").asInt());
        assertEquals(4, kept.get("collectMaxDurationMinutes").asInt());
        assertEquals(9, kept.get("collectNoNewWindowSeconds").asInt());

        // 字段 null=保持，只改时长
        service.updateTemplateConfig(id, requestWithPolicy(policy(null, 5, null)));
        JsonNode merged = mapper.readTree(repository.findById(id).deliveryPolicyJson());
        assertEquals(12, merged.get("collectMaxPerKeyword").asInt(), "未传字段应保持");
        assertEquals(5, merged.get("collectMaxDurationMinutes").asInt());
        assertEquals(9, merged.get("collectNoNewWindowSeconds").asInt());
    }

    @Test
    void minusOneClearsFieldBackToGlobalDefault() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        service.updateTemplateConfig(id, requestWithPolicy(policy(12, 4, 9)));

        ProfileTemplateService.TemplateConfigResult result = service.updateTemplateConfig(id,
                requestWithPolicy(policy(-1, -1, -1)));

        assertNull(result.template().deliveryPolicy(), "-1 清除全部字段后策略应为 null（回退全局）");
        assertNull(repository.findById(id).deliveryPolicyJson(), "全 null 策略不应写 JSON");
        var detail = service.detail(id);
        assertNull(detail.get("deliveryPolicy"));

        // 部分 -1：仅清除该字段
        service.updateTemplateConfig(id, requestWithPolicy(policy(7, 2, 3)));
        service.updateTemplateConfig(id, requestWithPolicy(policy(-1, null, null)));
        JsonNode partial = mapper.readTree(repository.findById(id).deliveryPolicyJson());
        assertFalse(partial.hasNonNull("collectMaxPerKeyword"), "-1 字段应清除为 null");
        assertEquals(2, partial.get("collectMaxDurationMinutes").asInt());
    }

    @Test
    void zeroIsSavedAsUnlimited() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        service.updateTemplateConfig(id, requestWithPolicy(policy(0, 0, 0)));

        JsonNode stored = mapper.readTree(repository.findById(id).deliveryPolicyJson());
        assertEquals(0, stored.get("collectMaxPerKeyword").asInt(), "0=不限制，应原样保存");
        assertEquals(0, stored.get("collectMaxDurationMinutes").asInt());
        assertEquals(0, stored.get("collectNoNewWindowSeconds").asInt());
        assertNotNull(service.detail(id).get("deliveryPolicy"));
    }

    @Test
    void negativeBelowMinusOneIsRejectedAndSnapshotUnchanged() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        String beforeBoss = repository.findById(id).bossConfigJson();
        String beforeRule = repository.findById(id).rulePayloadJson();

        TemplateValidationException error = assertThrows(TemplateValidationException.class,
                () -> service.updateTemplateConfig(id, requestWithPolicy(policy(-2, 4, 9))));

        assertTrue(error.getMessage().contains("采集策略"), error.getMessage());
        assertNull(repository.findById(id).deliveryPolicyJson(), "非法策略不得写库");
        assertEquals(beforeBoss, repository.findById(id).bossConfigJson(),
                "策略非法时整包保存应失败且 BOSS 快照不变");
        assertEquals(beforeRule, repository.findById(id).rulePayloadJson());
    }

    // ==================== capture / duplicate / 旧模板 ====================

    @Test
    void capturePreservesExistingPolicy() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        service.updateTemplateConfig(id, requestWithPolicy(policy(12, 4, 9)));

        service.capture(id);

        JsonNode stored = mapper.readTree(repository.findById(id).deliveryPolicyJson());
        assertEquals(12, stored.get("collectMaxPerKeyword").asInt(), "capture 用当前配置覆盖模板时应保留采集策略");
        assertEquals(9, stored.get("collectNoNewWindowSeconds").asInt());
    }

    @Test
    void duplicateCopiesPolicy() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        service.updateTemplateConfig(id, requestWithPolicy(policy(12, 4, 9)));

        ProfileTemplateService.TemplateView copy = service.duplicate(id, "产品方向副本");

        assertNotNull(copy.deliveryPolicy(), "复制模板应复制采集策略");
        assertEquals(12, copy.deliveryPolicy().collectMaxPerKeyword());
        assertEquals(4, copy.deliveryPolicy().collectMaxDurationMinutes());
        assertEquals(9, copy.deliveryPolicy().collectNoNewWindowSeconds());
    }

    @Test
    void createFromCurrentConfigHasNoPolicyByDefault() {
        ProfileTemplateService.TemplateView created = service.create("新模板", "X", null);
        assertNull(created.deliveryPolicy(), "从当前配置创建模板不复制采集策略（全局默认）");
        assertNull(repository.findById(created.id()).deliveryPolicyJson());
    }

    @Test
    void legacyTemplateWithNullPolicyReadsWithoutError() {
        ProfileTemplateRepository.ProfileTemplate inserted = repository.insert(
                new ProfileTemplateRepository.ProfileTemplate(0L, "旧模板", "旧方向", null, false,
                        null, null, null, null, null, null, null, null, null));

        ProfileTemplateService.TemplateView view = service.get(inserted.id());
        assertNull(view.deliveryPolicy(), "旧模板 delivery_policy_json 为 null 应返回 null 且不报错");
        assertNull(service.detail(inserted.id()).get("deliveryPolicy"));
        assertNull(service.list().get(0).deliveryPolicy());
    }

    // ==================== 工具 ====================

    private static ProfileTemplateService.DeliveryPolicy policy(Integer perKeyword, Integer duration,
                                                                Integer noNewWindow) {
        return new ProfileTemplateService.DeliveryPolicy(perKeyword, duration, noNewWindow);
    }

    private static ProfileTemplateService.TemplateConfigRequest requestWithPolicy(
            ProfileTemplateService.DeliveryPolicy policy) {
        return new ProfileTemplateService.TemplateConfigRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, policy);
    }

    private static BossConfigEntity configA() {
        BossConfigEntity config = new BossConfigEntity();
        config.setId(1L);
        config.setKeywords("[\"Java\"]");
        config.setCityCode("上海");
        config.setExpectedSalaryMin(30);
        config.setExpectedSalaryMax(50);
        config.setEnableAi(1);
        return config;
    }

    private static AiEntity aiA() {
        AiEntity ai = new AiEntity();
        ai.setIntroduce("介绍A");
        ai.setPrompt("提示词A");
        return ai;
    }

    private static RulePayload payloadA() {
        return new RulePayload("本科", "INTERSECT", 30, 50, List.of("产品"), List.of("上海"),
                List.of(), "CONTAINS", List.of("外包"), List.of(), List.of(), List.of(),
                false, List.of());
    }

    private static RuleSetView ruleViewA() {
        return new RuleSetView(1, "hash-a", "2026-11-06 10:00:00", "模板A", payloadA());
    }

    private static void overrideTemplateRoot(Path templateRoot) throws Exception {
        Class.forName(ProfileTemplateService.class.getName());
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