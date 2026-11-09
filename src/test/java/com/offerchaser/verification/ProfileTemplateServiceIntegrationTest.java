package com.offerchaser.verification;

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
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.application.templates.TemplateNotFoundException;
import com.offerchaser.application.templates.TemplateValidationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 方向模板服务集成验收（真实 SQLite V9 + 真实 Repository；BossService/AiService/RuleConfigService/
 * BossResumeImageService 用 Mockito 桩，不启动浏览器/Spring）：
 * create 从当前配置快照、activate 应用全部配置+同步黑名单+模板简历+setActive 唯一、
 * capture 覆盖不改元信息、duplicate 复制、delete 生效模板拒绝、名称冲突、上传简历。
 */
class ProfileTemplateServiceIntegrationTest {

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
        // ProfileTemplateService 的 TEMPLATE_ROOT 是 static final（按 user.dir 计算）；
        // 其他测试类可能已触发初始化，这里用 Unsafe 覆盖到 @TempDir，避免污染仓库 data/ 目录。
        Path templateRoot = tempRoot.resolve("templates");
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
        Files.write(currentResume, new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        when(resumeImageService.getCurrentImagePath()).thenReturn(Optional.of(currentResume));
        when(bossService.getAllBlacklist()).thenReturn(List.of());

        when(bossService.getFirstConfig()).thenReturn(configA());
        when(aiService.getAiConfig()).thenReturn(aiA());
        when(ruleConfigService.getActive()).thenReturn(ruleViewA());
        when(ruleConfigService.currentVersion()).thenReturn(1);
        when(ruleConfigService.save(any(RulePayload.class), any(Integer.class), anyString()))
                .thenReturn(ruleViewA());
    }

    /** 用 Unsafe 覆盖 ProfileTemplateService 的 static final TEMPLATE_ROOT（仅测试用）。 */
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

    // ==================== create：从当前配置生成快照 ====================

    @Test
    void createSnapshotsCurrentBossAiRulesAndResume() throws Exception {
        ProfileTemplateService.TemplateView view = service.create("产品方向", "产品", "面向产品经理方向");

        assertEquals("产品方向", view.name());
        assertEquals("产品", view.direction());
        assertFalse(view.active());
        assertEquals("Java、后端", view.keywords(), "列表摘要应美化关键词");
        assertEquals(30, view.expectedSalaryMin());
        assertEquals(50, view.expectedSalaryMax());
        assertTrue(view.hasResume(), "当前有简历时应复制到模板");

        ProfileTemplateRepository.ProfileTemplate stored = repository.list().get(0);
        assertTrue(stored.bossConfigJson().contains("Java"), stored.bossConfigJson());
        assertTrue(stored.bossConfigJson().contains("\"expectedSalaryMin\":30"));
        assertEquals("模板介绍A", stored.aiIntroduce());
        assertEquals("模板提示词A", stored.aiPrompt());
        assertTrue(stored.rulePayloadJson().contains("本科"));
        assertTrue(stored.rulePayloadJson().contains("产品"));
        assertNotNull(stored.resumeFile());
        assertTrue(Files.isRegularFile(Paths.get(stored.resumeFile())),
                "模板简历文件应真实复制到 data/boss/templates/<id>/");
        assertTrue(Paths.get(stored.resumeFile()).startsWith(
                tempRoot.resolve("templates").toAbsolutePath().normalize()),
                "模板简历目录应按测试覆盖的 templates 根目录组织");

        // 名称唯一 / 空名 / 超长名校验
        assertThrows(TemplateValidationException.class,
                () -> service.create("产品方向", "产品", "重复名称"));
        assertThrows(TemplateValidationException.class, () -> service.create("  ", null, null));
        assertThrows(TemplateValidationException.class,
                () -> service.create("x".repeat(61), null, null));
        assertEquals(1, repository.list().size(), "创建失败不得留下半成品模板");
    }

    @Test
    void createWithNoAiAndNoResumeStoresEmptySnapshot() {
        when(aiService.getAiConfig()).thenReturn(null);
        when(resumeImageService.getCurrentImagePath()).thenReturn(Optional.empty());

        ProfileTemplateService.TemplateView view = service.create("无AI方向", null, null);

        ProfileTemplateRepository.ProfileTemplate stored = repository.list().get(0);
        assertNull(stored.aiIntroduce());
        assertNull(stored.aiPrompt());
        assertNull(stored.resumeFile());
        assertFalse(view.hasResume());
    }

    // ==================== activate：应用配置 / 黑名单 / 简历 / setActive ====================

    @Test
    void activateAppliesTemplateToCurrentConfiguration() throws Exception {
        ProfileTemplateService.TemplateView template = service.create("产品方向", "产品", null);
        long id = template.id();
        String templateResume = repository.findById(id).resumeFile();

        // 模板创建后“当前配置”被改成另一套
        when(bossService.getFirstConfig()).thenReturn(configB());
        when(aiService.getAiConfig()).thenReturn(aiB());
        when(ruleConfigService.getActive()).thenReturn(ruleViewB());
        when(ruleConfigService.currentVersion()).thenReturn(3);
        BlacklistEntity existing = new BlacklistEntity();
        existing.setType("company");
        existing.setValue("旧黑名单");
        when(bossService.getAllBlacklist()).thenReturn(List.of(existing));

        ProfileTemplateService.ActivateResult result = service.activate(id);

        assertTrue(result.template().active(), "激活后模板应为 active");
        assertEquals(id, repository.findActive().id(), "同一时刻只能有一个生效模板");
        assertTrue(result.warnings().isEmpty(), "完整模板不应有警告：" + result.warnings());

        // BOSS 配置写回模板快照（关键词/薪资）
        ArgumentCaptor<BossConfigEntity> bossCaptor = ArgumentCaptor.forClass(BossConfigEntity.class);
        verify(bossService).updateConfig(bossCaptor.capture());
        assertTrue(bossCaptor.getValue().getKeywords().contains("Java"));
        assertEquals(30, bossCaptor.getValue().getExpectedSalaryMin());

        // AI 介绍/提示词写回
        verify(aiService).saveOrUpdateAiConfig("模板介绍A", "模板提示词A");

        // 规则保存模板 payload，并同步黑名单表（先清旧再写模板值）
        ArgumentCaptor<RulePayload> payloadCaptor = ArgumentCaptor.forClass(RulePayload.class);
        verify(ruleConfigService).save(payloadCaptor.capture(), eq(3), contains("激活方向模板"));
        assertEquals("本科", payloadCaptor.getValue().highestDegree());
        assertEquals(List.of("产品"), payloadCaptor.getValue().jobDirections());
        verify(bossService).removeBlacklist("company", "旧黑名单");
        verify(bossService).addBlacklist("job", "外包");
        verify(bossService).addBlacklist("company", "某外包公司");

        // 模板简历应用到当前图片简历
        verify(resumeImageService).saveFromPath(eq(Paths.get(templateResume)));
    }

    @Test
    void activateSecondTemplateDeactivatesFirst() {
        long first = service.create("方向A", "A", null).id();
        long second = service.create("方向B", "B", null).id();

        service.activate(first);
        service.activate(second);

        assertFalse(repository.findById(first).active());
        assertTrue(repository.findById(second).active());
        assertEquals(second, repository.findActive().id());
    }

    @Test
    void activateWarnsWhenTemplateLacksAiOrResumeAndRetriesVersionConflict() throws Exception {
        when(aiService.getAiConfig()).thenReturn(null);
        when(resumeImageService.getCurrentImagePath()).thenReturn(Optional.empty());
        long id = service.create("极简方向", null, null).id();

        doThrow(new RuleVersionConflictException(7))
                .doReturn(ruleViewA())
                .when(ruleConfigService).save(any(RulePayload.class), any(Integer.class), anyString());

        ProfileTemplateService.ActivateResult result = service.activate(id);

        assertEquals(2, result.warnings().size(), "无 AI/无简历都应给出警告：" + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(text -> text.contains("AI")));
        assertTrue(result.warnings().stream().anyMatch(text -> text.contains("简历")));
        verify(ruleConfigService).save(any(RulePayload.class), eq(1), contains("激活方向模板"));
        verify(ruleConfigService).save(any(RulePayload.class), eq(7), contains("激活方向模板"));
        verify(resumeImageService, never()).saveFromPath(any());
    }

    @Test
    void activateBrokenSnapshotIsRejectedWithoutCorruptingActive() {
        long good = service.create("正常方向", "A", null).id();
        long broken = service.create("坏方向", "B", null).id();
        repository.updateSnapshot(broken, "not-a-json", "x", "y", "{}", null, null);

        TemplateValidationException error = assertThrows(TemplateValidationException.class,
                () -> service.activate(broken));
        assertTrue(error.getMessage().contains("BOSS 配置"), error.getMessage());
        assertFalse(repository.findById(good).active());
        assertFalse(repository.findById(broken).active(), "激活失败不得改变 active");
        assertThrows(TemplateNotFoundException.class, () -> service.activate(99999));
    }

    // ==================== capture / update ====================

    @Test
    void captureOverwritesSnapshotButKeepsMetadata() {
        long id = service.create("产品方向", "产品", "原描述").id();

        when(bossService.getFirstConfig()).thenReturn(configB());
        when(aiService.getAiConfig()).thenReturn(aiB());
        when(ruleConfigService.getActive()).thenReturn(ruleViewB());

        ProfileTemplateService.TemplateView captured = service.capture(id);

        assertEquals("产品方向", captured.name(), "capture 不得改名称");
        assertEquals("产品", captured.direction(), "capture 不得改方向");
        ProfileTemplateRepository.ProfileTemplate stored = repository.findById(id);
        assertTrue(stored.bossConfigJson().contains("产品经理"), stored.bossConfigJson());
        assertEquals("模板介绍B", stored.aiIntroduce());
        assertTrue(stored.rulePayloadJson().contains("硕士"));
        assertThrows(TemplateNotFoundException.class, () -> service.capture(99999));
    }

    @Test
    void updateMetadataRenamesAndRejectsDuplicatesKeepsSnapshot() {
        long first = service.create("方向A", "A", null).id();
        long second = service.create("方向B", "B", null).id();
        String secondSnapshot = repository.findById(second).bossConfigJson();

        ProfileTemplateService.TemplateView updated = service.updateMetadata(first, "方向A2", "A2", "说明");
        assertEquals("方向A2", updated.name());
        assertEquals("A2", updated.direction());

        assertThrows(TemplateValidationException.class,
                () -> service.updateMetadata(first, "方向B", null, null),
                "改名冲突必须拒绝");
        assertEquals(secondSnapshot, repository.findById(second).bossConfigJson());
        assertThrows(TemplateNotFoundException.class,
                () -> service.updateMetadata(99999, "不存在", null, null));
    }

    // ==================== duplicate / delete / resume ====================

    @Test
    void duplicateCopiesSnapshotAndResume() {
        long source = service.create("产品方向", "产品", "原模板").id();
        String sourceResume = repository.findById(source).resumeFile();

        ProfileTemplateService.TemplateView copy = service.duplicate(source, "产品方向副本");

        assertNotNull(copy.id());
        assertFalse(copy.active(), "复制品默认不生效");
        assertEquals("产品方向副本", copy.name());
        ProfileTemplateRepository.ProfileTemplate copied = repository.findById(copy.id());
        assertEquals(repository.findById(source).bossConfigJson(), copied.bossConfigJson());
        assertEquals("模板介绍A", copied.aiIntroduce());
        assertNotNull(copied.resumeFile());
        assertTrue(Files.isRegularFile(Paths.get(copied.resumeFile())), "副本应复制模板简历文件");
        assertFalse(sourceResume.equals(copied.resumeFile()), "副本简历应是独立文件");

        assertThrows(TemplateValidationException.class,
                () -> service.duplicate(source, "产品方向副本"));
        assertThrows(TemplateNotFoundException.class, () -> service.duplicate(99999, "不存在"));
    }

    @Test
    void deleteRejectsActiveTemplateAndRemovesInactive() throws Exception {
        long active = service.create("生效方向", "A", null).id();
        long other = service.create("其他方向", "B", null).id();
        service.activate(active);

        TemplateValidationException error = assertThrows(TemplateValidationException.class,
                () -> service.delete(active));
        assertTrue(error.getMessage().contains("不能删除"));
        assertNotNull(repository.findById(active), "生效模板不得被删除");

        String otherResume = repository.findById(other).resumeFile();
        service.delete(other);
        assertNull(repository.findById(other));
        if (otherResume != null) {
            assertFalse(Files.exists(Paths.get(otherResume).getParent()), "删除模板应清理其简历目录");
        }
        assertThrows(TemplateNotFoundException.class, () -> service.delete(99999));
    }

    @Test
    void uploadResumeSavesToTemplateDirectoryAndAppliesWhenActive() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        when(resumeImageService.saveToDirectory(any(), any(), anyString())).thenAnswer(invocation -> {
            org.springframework.web.multipart.MultipartFile file = invocation.getArgument(0);
            Path directory = (Path) invocation.getArguments()[1];
            Files.createDirectories(directory);
            Path target = directory.resolve("resume.png");
            Files.write(target, file.getBytes());
            return target;
        });

        MockMultipartFile upload = new MockMultipartFile("file", "resume.png", "image/png",
                new byte[]{1, 2, 3, 4});
        ProfileTemplateService.TemplateView view = service.uploadResume(id, upload);

        assertTrue(view.hasResume());
        assertNotNull(repository.findById(id).resumeFile());
        assertEquals(4, Files.size(Paths.get(repository.findById(id).resumeFile())));
        verify(resumeImageService, never()).saveFromPath(any());

        service.activate(id);
        MockMultipartFile second = new MockMultipartFile("file", "resume2.png", "image/png",
                new byte[]{9});
        org.mockito.Mockito.doAnswer(invocation -> {
            Path directory = (Path) invocation.getArguments()[1];
            Files.createDirectories(directory);
            Path target = directory.resolve("resume2.png");
            Files.write(target, ((org.springframework.web.multipart.MultipartFile) invocation.getArgument(0)).getBytes());
            return target;
        }).when(resumeImageService).saveToDirectory(any(), any(), anyString());
        service.uploadResume(id, second);
        verify(resumeImageService, atLeastOnce()).saveFromPath(any());
    }

    @Test
    void uploadResumeValidationErrors() throws Exception {
        long id = service.create("产品方向", "产品", null).id();
        assertThrows(TemplateNotFoundException.class,
                () -> service.uploadResume(99999, new MockMultipartFile("file", "a.png", "image/png",
                        new byte[]{1})),
                "不存在模板 → NotFound");
        doThrow(new IllegalArgumentException("文件类型不支持"))
                .when(resumeImageService).saveToDirectory(any(), any(), anyString());
        assertThrows(TemplateValidationException.class,
                () -> service.uploadResume(id, new MockMultipartFile("file", "a.txt", "text/plain",
                        new byte[]{1})));
    }

    @Test
    void listActiveGetAndDetailWork() {
        long first = service.create("方向A", "A", "说明A").id();
        long second = service.create("方向B", "B", null).id();
        service.activate(second);

        assertEquals(2, service.list().size());
        assertEquals(second, service.active().orElseThrow().id());
        assertEquals("方向A", service.get(first).name());
        Map<String, Object> detail = service.detail(first);
        assertNotNull(detail.get("template"));
        @SuppressWarnings("unchecked")
        Map<String, Object> detailBossConfig = (Map<String, Object>) detail.get("bossConfig");
        assertTrue(String.valueOf(detailBossConfig.get("keywords")).contains("Java"),
                "detail 应返回解析后的 bossConfig 对象");
        assertNotNull(detail.get("rule"), "detail 应返回解析后的规则");
        assertEquals("模板介绍A", detail.get("aiIntroduce"));
        assertThrows(TemplateNotFoundException.class, () -> service.get(99999));
        assertThrows(TemplateNotFoundException.class, () -> service.detail(99999));
    }

    @Test
    void resumePathFindsTemplateFileOrEmpty() {
        long id = service.create("产品方向", "产品", null).id();
        ProfileTemplateRepository.ProfileTemplate stored = repository.findById(id);
        assertTrue(service.resumePath(id).isPresent());
        assertEquals(Paths.get(stored.resumeFile()), service.resumePath(id).orElseThrow());

        long noResume = service.create("无简历方向", "B", null).id();
        repository.updateResumeFile(noResume, null);
        assertTrue(service.resumePath(noResume).isEmpty());
        assertThrows(TemplateNotFoundException.class, () -> service.resumePath(99999));
    }

    // ==================== 工具 ====================

    private static BossConfigEntity configA() {
        BossConfigEntity config = new BossConfigEntity();
        config.setId(1L);
        config.setKeywords("[\"Java\",\"后端\"]");
        config.setCityCode("[\"101020100\"]");
        config.setExpectedSalaryMin(30);
        config.setExpectedSalaryMax(50);
        config.setEnableAi(1);
        config.setSendImgResume(1);
        config.setAllowRepeatChat(1);
        config.setFilterDeadHr(1);
        config.setDeadStatus("[\"今日活跃\"]");
        config.setCreatedAt(java.time.LocalDateTime.now());
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
        config.setCreatedAt(java.time.LocalDateTime.now());
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
}




