package com.offerchaser.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.entity.BossConfigEntity;
import com.offerchaser.application.persistence.ProfileTemplateRepository;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.rules.RulePayload;
import com.offerchaser.application.rules.RuleSetView;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.application.templates.ProfileTemplateService;
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
import java.util.Optional;

import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置中心整合 · 模板优先级验收（真实 SQLite V9 + 真实 Repository；Boss/AI/规则/简历用 Mockito 桩）：
 * create→capture→activate 闭环；ensureActiveApplied 按生效模板校正当前配置（BOSS/AI/规则/简历，
 * 规则仅差异时 save 一次，完全一致返回 null）；captureActiveFromCurrent 静默写回生效模板。
 */
class ProfileTemplatePriorityIntegrationTest {

    @TempDir
    static Path tempRoot;

    @TempDir
    Path tempDir;

    private ProfileTemplateRepository repository;
    private BossService bossService;
    private AiService aiService;
    private RuleConfigService ruleConfigService;
    private BossResumeImageService resumeImageService;
    private ProfileTemplateService service;
    private Path currentResume;

    @BeforeEach
    void setUp() throws Exception {
        Path templateRoot = tempRoot.resolve("priority-templates");
        deleteDirectory(templateRoot);
        overrideTemplateRoot(templateRoot);

        SQLiteDataSource dataSource = migratedDataSource(tempDir);
        repository = new ProfileTemplateRepository(dataSource);
        bossService = mock(BossService.class);
        aiService = mock(AiService.class);
        ruleConfigService = mock(RuleConfigService.class);
        resumeImageService = mock(BossResumeImageService.class);
        service = new ProfileTemplateService(repository, bossService, aiService,
                ruleConfigService, resumeImageService, new ObjectMapper().findAndRegisterModules());

        currentResume = tempRoot.resolve("current-" + System.nanoTime()).resolve("resume.png");
        Files.createDirectories(currentResume.getParent());
        Files.write(currentResume, new byte[]{(byte) 0x89, 'P', 'N', 'G', 11, 22, 33});
        when(resumeImageService.getCurrentImagePath()).thenReturn(Optional.of(currentResume));

        when(bossService.getFirstConfig()).thenReturn(configA());
        when(aiService.getAiConfig()).thenReturn(aiA());
        when(ruleConfigService.getActive()).thenReturn(ruleViewA());
        when(ruleConfigService.currentVersion()).thenReturn(1);
        when(ruleConfigService.save(any(RulePayload.class), any(Integer.class), anyString()))
                .thenReturn(ruleViewA());
    }

    // ==================== 一致时无动作 ====================

    @Test
    void ensureReturnsNullWhenCurrentConfigurationMatchesTemplate() throws Exception {
        long id = createAndActivate("方向A");
        clearInvocations(bossService, aiService, ruleConfigService, resumeImageService);

        ProfileTemplateService.ActivateResult result = service.ensureActiveApplied();

        assertNull(result, "当前配置与模板完全一致时应返回 null（不产生动作/规则版本）");
        verify(ruleConfigService, never()).save(any(), any(Integer.class), anyString());
        verify(aiService, never()).saveOrUpdateAiConfig(any(), any());
        verify(bossService, never()).updateConfig(any());
        verify(resumeImageService, never()).saveFromPath(any());
        assertEquals(1, repository.list().size());
        assertTrue(repository.findById(id).active());
    }

    // ==================== 任一差异 → 按模板校正 ====================

    @Test
    void ensureRestoresBossConfigOnly() throws Exception {
        createAndActivate("方向A");
        clearInvocations(bossService, aiService, ruleConfigService, resumeImageService);
        when(bossService.getFirstConfig()).thenReturn(configB());

        ProfileTemplateService.ActivateResult result = service.ensureActiveApplied();

        assertNotNull(result);
        assertEquals(1, result.warnings().size(), "只有 BOSS 配置差异：" + result.warnings());
        assertTrue(result.warnings().get(0).contains("BOSS"));
        ArgumentCaptor<BossConfigEntity> captor = ArgumentCaptor.forClass(BossConfigEntity.class);
        verify(bossService).updateConfig(captor.capture());
        assertTrue(captor.getValue().getKeywords().contains("Java"), "应按模板快照恢复关键词");
        assertEquals(30, captor.getValue().getExpectedSalaryMin());
        verify(aiService, never()).saveOrUpdateAiConfig(any(), any());
        verify(ruleConfigService, never()).save(any(), any(Integer.class), anyString());
        verify(resumeImageService, never()).saveFromPath(any());
    }

    @Test
    void ensureRestoresAiOnly() {
        createAndActivate("方向A");
        clearInvocations(bossService, aiService, ruleConfigService, resumeImageService);
        when(aiService.getAiConfig()).thenReturn(aiB());

        ProfileTemplateService.ActivateResult result = service.ensureActiveApplied();

        assertNotNull(result);
        assertTrue(result.warnings().get(0).contains("AI"));
        verify(aiService).saveOrUpdateAiConfig("模板介绍A", "模板提示词A");
        verify(bossService, never()).updateConfig(any());
        verify(ruleConfigService, never()).save(any(), any(Integer.class), anyString());
    }

    @Test
    void ensureRestoresRuleOnlyAndSavesExactlyOnce() throws Exception {
        createAndActivate("方向A");
        clearInvocations(bossService, aiService, ruleConfigService, resumeImageService);
        when(ruleConfigService.getActive()).thenReturn(ruleViewB());

        ProfileTemplateService.ActivateResult result = service.ensureActiveApplied();

        assertNotNull(result);
        assertTrue(result.warnings().get(0).contains("规则"));
        ArgumentCaptor<RulePayload> captor = ArgumentCaptor.forClass(RulePayload.class);
        verify(ruleConfigService, times(1)).save(captor.capture(), eq(1), contains("激活方向模板"));
        assertEquals("本科", captor.getValue().highestDegree(), "应按模板规则恢复");
        assertEquals(List.of("产品"), captor.getValue().jobDirections());
        verify(bossService, never()).updateConfig(any());
        verify(aiService, never()).saveOrUpdateAiConfig(any(), any());
        verify(resumeImageService, never()).saveFromPath(any());
    }

    @Test
    void ensureRestoresResumeWhenSha256Differs() throws Exception {
        createAndActivate("方向A");
        clearInvocations(bossService, aiService, ruleConfigService, resumeImageService);
        Files.write(currentResume, new byte[]{9, 9, 9, 9}, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        ProfileTemplateService.ActivateResult result = service.ensureActiveApplied();

        assertNotNull(result);
        assertTrue(result.warnings().get(0).contains("简历"));
        String templateResume = repository.findActive().resumeFile();
        verify(resumeImageService).saveFromPath(eq(Paths.get(templateResume)));
        verify(ruleConfigService, never()).save(any(), any(Integer.class), anyString());
        verify(aiService, never()).saveOrUpdateAiConfig(any(), any());
    }

    @Test
    void ensureAppliesAllDifferencesAndBumpsRulesOnlyOnce() throws Exception {
        createAndActivate("方向A");
        clearInvocations(bossService, aiService, ruleConfigService, resumeImageService);
        when(bossService.getFirstConfig()).thenReturn(configB());
        when(aiService.getAiConfig()).thenReturn(aiB());
        when(ruleConfigService.getActive()).thenReturn(ruleViewB());
        Files.write(currentResume, new byte[]{7, 7, 7}, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        ProfileTemplateService.ActivateResult result = service.ensureActiveApplied();

        assertNotNull(result);
        assertEquals(4, result.warnings().size(), "BOSS/AI/规则/简历四类差异：" + result.warnings());
        verify(ruleConfigService, times(1)).save(any(RulePayload.class), any(Integer.class),
                contains("激活方向模板"));
        verify(bossService).updateConfig(any());
        verify(aiService).saveOrUpdateAiConfig("模板介绍A", "模板提示词A");
        verify(resumeImageService).saveFromPath(any());
    }

    @Test
    void ensureSwallowsBrokenTemplateSnapshot() {
        long id = createAndActivate("方向A");
        clearInvocations(bossService, aiService, ruleConfigService, resumeImageService);
        repository.updateSnapshot(id, "not-json", "x", "y", "{}", null, null);

        ProfileTemplateService.ActivateResult result = service.ensureActiveApplied();

        assertNull(result, "模板快照损坏时应 warn 并返回 null，不能阻止任务启动");
        verify(ruleConfigService, never()).save(any(), any(Integer.class), anyString());
    }

    // ==================== captureActiveFromCurrent ====================

    @Test
    void captureActiveFromCurrentWritesBackToActiveTemplateWithoutChangingMetadata() {
        long id = createAndActivate("方向A");
        when(bossService.getFirstConfig()).thenReturn(configB());
        when(aiService.getAiConfig()).thenReturn(aiB());
        when(ruleConfigService.getActive()).thenReturn(ruleViewB());

        ProfileTemplateService.TemplateView view = service.captureActiveFromCurrent();

        assertNotNull(view);
        assertEquals(id, view.id());
        assertEquals("方向A", view.name(), "自动同步不得改模板名称");
        assertEquals("A", view.direction(), "自动同步不得改方向");
        ProfileTemplateRepository.ProfileTemplate stored = repository.findById(id);
        assertTrue(stored.bossConfigJson().contains("产品经理"), stored.bossConfigJson());
        assertEquals("模板介绍B", stored.aiIntroduce());
        assertTrue(stored.rulePayloadJson().contains("硕士"));
    }

    @Test
    void captureActiveFromCurrentIsNoOpWithoutActiveTemplate() {
        assertNull(service.captureActiveFromCurrent(), "没有生效模板时应 no-op");
        assertEquals(0, repository.list().size());
    }

    // ==================== create → capture → activate 闭环 ====================

    @Test
    void createCaptureActivateLoopKeepsTemplateAuthoritative() {
        long id = service.create("方向A", "A", null).id();
        service.activate(id);
        // 模拟编辑页把当前配置改成 B（保存时会被自动写回模板，这里手动 capture）
        when(bossService.getFirstConfig()).thenReturn(configB());
        when(aiService.getAiConfig()).thenReturn(aiB());
        when(ruleConfigService.getActive()).thenReturn(ruleViewB());
        service.capture(id);
        assertTrue(repository.findById(id).bossConfigJson().contains("产品经理"));

        // 再次激活 → 当前配置回到模板 B，随后 ensure 应判定一致
        service.activate(id);
        clearInvocations(ruleConfigService);
        assertNull(service.ensureActiveApplied(), "激活后当前配置与模板一致，不应再校正");
        verify(ruleConfigService, never()).save(any(), any(Integer.class), anyString());
    }

    // ==================== 工具 ====================

    private long createAndActivate(String name) {
        ProfileTemplateService.TemplateView view = service.create(name, name.substring(name.length() - 1), "说明");
        service.activate(view.id());
        clearInvocations(bossService, aiService, ruleConfigService, resumeImageService);
        return view.id();
    }

    private static BossConfigEntity configA() {
        BossConfigEntity config = new BossConfigEntity();
        config.setId(1L);
        config.setKeywords("[\"Java\",\"后端\"]");
        config.setCityCode("[\"101020100\"]");
        config.setExpectedSalaryMin(30);
        config.setExpectedSalaryMax(50);
        config.setEnableAi(1);
        config.setSendImgResume(1);
        config.setFilterDeadHr(1);
        config.setCreatedAt(java.time.LocalDateTime.of(2026, 11, 6, 10, 0));
        return config;
    }

    private static BossConfigEntity configB() {
        BossConfigEntity config = new BossConfigEntity();
        config.setId(1L);
        config.setKeywords("[\"产品经理\"]");
        config.setCityCode("[\"101010100\"]");
        config.setExpectedSalaryMin(40);
        config.setExpectedSalaryMax(60);
        config.setEnableAi(0);
        config.setCreatedAt(java.time.LocalDateTime.of(2026, 11, 6, 11, 0));
        return config;
    }

    private static AiEntity aiA() {
        AiEntity ai = new AiEntity();
        ai.setIntroduce("模板介绍A");
        ai.setPrompt("模板提示词A");
        return ai;
    }

    private static AiEntity aiB() {
        AiEntity ai = new AiEntity();
        ai.setIntroduce("模板介绍B");
        ai.setPrompt("模板提示词B");
        return ai;
    }

    private static RulePayload payloadA() {
        return new RulePayload("本科", "INTERSECT", 30, 50, List.of("产品"), List.of("上海"),
                List.of(), "CONTAINS", List.of("外包"), List.of("某外包公司"), List.of(), List.of(),
                true, List.of("今日活跃"));
    }

    private static RulePayload payloadB() {
        return new RulePayload("硕士", "INTERSECT", 40, 60, List.of("后端"), List.of("北京"),
                List.of(), "CONTAINS", List.of(), List.of(), List.of(), List.of(),
                false, List.of());
    }

    private static RuleSetView ruleViewA() {
        return new RuleSetView(1, "hash-a", "2026-11-06 10:00:00", "模板A", payloadA());
    }

    private static RuleSetView ruleViewB() {
        return new RuleSetView(3, "hash-b", "2026-11-06 11:00:00", "模板B", payloadB());
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

