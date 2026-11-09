package com.offerchaser.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.persistence.ProfileTemplateRepository;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.application.templates.TemplateValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * V11 模板拖拽排序验收（真实 SQLite V11 + 真实 Repository）：
 * reorder 后 list 顺序与 sort_order；重排不改 updated_at；新模板 NULL 排末尾；
 * 非法排序（缺 id/重复/未知）拒绝且顺序不变；active 不变；重新打开连接后顺序保持。
 */
class TemplateOrderingIntegrationTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private SQLiteDataSource dataSource;
    private ProfileTemplateRepository repository;
    private ProfileTemplateService service;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = migratedDataSource(tempDir);
        repository = new ProfileTemplateRepository(dataSource);
        service = new ProfileTemplateService(repository,
                mock(BossService.class), mock(AiService.class), mock(RuleConfigService.class),
                mock(BossResumeImageService.class), mapper);
    }

    @Test
    void reorderChangesListOrderAndSortOrderWithoutTouchingUpdatedAt() {
        var a = insert("A方向", "产品", false);
        var b = insert("B方向", "机器人", false);
        var c = insert("C方向", "测试", false);
        String updatedA = repository.findById(a.id()).updatedAt();
        String updatedB = repository.findById(b.id()).updatedAt();
        String updatedC = repository.findById(c.id()).updatedAt();

        repository.reorder(List.of(c.id(), a.id(), b.id()));

        List<ProfileTemplateRepository.ProfileTemplate> ordered = repository.list();
        assertEquals(List.of(c.id(), a.id(), b.id()),
                ordered.stream().map(ProfileTemplateRepository.ProfileTemplate::id).toList(),
                "list 应按 sort_order 升序返回");
        assertEquals(0, repository.findById(c.id()).sortOrder());
        assertEquals(1, repository.findById(a.id()).sortOrder());
        assertEquals(2, repository.findById(b.id()).sortOrder());
        assertEquals(updatedA, repository.findById(a.id()).updatedAt(), "重排不得修改 updated_at");
        assertEquals(updatedB, repository.findById(b.id()).updatedAt());
        assertEquals(updatedC, repository.findById(c.id()).updatedAt());
    }

    @Test
    void nullSortOrderTemplatesStayAtEnd() {
        var a = insert("A方向", "产品", false);
        var b = insert("B方向", "机器人", false);
        repository.reorder(List.of(b.id(), a.id()));

        var d = insert("D新模板", "X", false);
        assertNull(repository.findById(d.id()).sortOrder(), "新模板 sort_order 应为 null（未排序）");

        assertEquals(List.of(b.id(), a.id(), d.id()),
                repository.list().stream().map(ProfileTemplateRepository.ProfileTemplate::id).toList(),
                "未排序（NULL）模板应排在已排序模板之后");
    }

    @Test
    void serviceReorderValidatesFullSetAndReturnsNewOrder() {
        var a = insert("A方向", "产品", false);
        var b = insert("B方向", "机器人", false);
        var c = insert("C方向", "测试", false);

        List<ProfileTemplateService.TemplateView> views =
                service.reorderTemplates(List.of(a.id(), c.id(), b.id()));

        assertEquals(List.of(a.id(), c.id(), b.id()),
                views.stream().map(ProfileTemplateService.TemplateView::id).toList());
        assertEquals(List.of(a.id(), c.id(), b.id()),
                repository.list().stream().map(ProfileTemplateRepository.ProfileTemplate::id).toList());
    }

    @Test
    void invalidReorderIsRejectedAndOrderUnchanged() {
        var a = insert("A方向", "产品", false);
        var b = insert("B方向", "机器人", false);
        var c = insert("C方向", "测试", false);
        repository.reorder(List.of(a.id(), b.id(), c.id()));
        List<Long> before = repository.list().stream()
                .map(ProfileTemplateRepository.ProfileTemplate::id).toList();

        // 缺 id
        assertThrows(TemplateValidationException.class,
                () -> service.reorderTemplates(List.of(a.id(), b.id())));
        // 重复 id
        assertThrows(TemplateValidationException.class,
                () -> service.reorderTemplates(List.of(a.id(), a.id(), b.id())));
        // 未知 id（且数量一致）
        assertThrows(TemplateValidationException.class,
                () -> service.reorderTemplates(List.of(a.id(), b.id(), 99999L)));
        // null / 空
        assertThrows(TemplateValidationException.class, () -> service.reorderTemplates(null));
        assertThrows(TemplateValidationException.class, () -> service.reorderTemplates(List.of()));

        assertEquals(before, repository.list().stream()
                        .map(ProfileTemplateRepository.ProfileTemplate::id).toList(),
                "非法排序不得改变现有顺序");
        assertEquals(0, repository.findById(a.id()).sortOrder());
        assertEquals(1, repository.findById(b.id()).sortOrder());
        assertEquals(2, repository.findById(c.id()).sortOrder());
    }

    @Test
    void repositoryReorderRollsBackOnUnknownOrDuplicateId() {
        var a = insert("A方向", "产品", false);
        var b = insert("B方向", "机器人", false);
        repository.reorder(List.of(a.id(), b.id()));

        assertThrows(IllegalArgumentException.class,
                () -> repository.reorder(List.of(b.id(), 99999L)));
        assertThrows(IllegalArgumentException.class,
                () -> repository.reorder(List.of(b.id(), b.id())));

        assertEquals(List.of(a.id(), b.id()),
                repository.list().stream().map(ProfileTemplateRepository.ProfileTemplate::id).toList(),
                "事务应回滚，不得半更新");
        assertEquals(0, repository.findById(a.id()).sortOrder());
        assertEquals(1, repository.findById(b.id()).sortOrder());
    }

    @Test
    void activeTemplateUnchangedByReorder() {
        var a = insert("A方向", "产品", false);
        var b = insert("B方向", "机器人", true);
        var c = insert("C方向", "测试", false);

        repository.reorder(List.of(c.id(), b.id(), a.id()));

        assertNotNull(repository.findActive());
        assertEquals(b.id(), repository.findActive().id(), "拖拽排序不得改变生效模板");
        assertTrue(repository.findById(b.id()).active());
    }

    @Test
    void orderSurvivesNewRepositoryConnection() {
        var a = insert("A方向", "产品", false);
        var b = insert("B方向", "机器人", false);
        var c = insert("C方向", "测试", false);
        repository.reorder(List.of(c.id(), b.id(), a.id()));

        ProfileTemplateRepository reopened = new ProfileTemplateRepository(dataSource);
        assertEquals(List.of(c.id(), b.id(), a.id()),
                reopened.list().stream().map(ProfileTemplateRepository.ProfileTemplate::id).toList(),
                "重新查询（新 Repository 实例）仍应保持排序");
        Integer sortOrder = reopened.findById(b.id()).sortOrder();
        assertEquals(1, sortOrder);
    }

    @Test
    void emptyRepositoryReorderIsNoOp() {
        repository.reorder(List.of());
        repository.reorder(null);
        assertEquals(0, repository.list().size());
    }

    // ==================== 工具 ====================

    private ProfileTemplateRepository.ProfileTemplate insert(String name, String direction, boolean active) {
        ProfileTemplateRepository.ProfileTemplate inserted = repository.insert(
                new ProfileTemplateRepository.ProfileTemplate(0L, name, direction, null, active,
                        null, null, null, null, null, null, null, null, null));
        if (active) {
            execute(dataSource, "UPDATE profile_template SET active = 1 WHERE id = " + inserted.id());
        }
        return repository.findById(inserted.id());
    }
}
