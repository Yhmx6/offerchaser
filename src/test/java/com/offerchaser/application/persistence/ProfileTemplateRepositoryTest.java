package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static com.offerchaser.application.persistence.PersistenceTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方向模板仓库测试：CRUD、名称唯一、active 单一生效、list 排序与删除。
 */
class ProfileTemplateRepositoryTest {

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource;
    private ProfileTemplateRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = PersistenceTestSupport.migratedDataSource(directory);
        repository = new ProfileTemplateRepository(dataSource);
    }

    // ==================== 创建与查询 ====================

    @Test
    void insertAndFindById() {
        ProfileTemplateRepository.ProfileTemplate inserted = repository.insert(template(
                "Java 后端", "后端", "Java 方向模板", "{\"jobType\":\"Java\"}", "您好，我是...",
                "请输出招呼语", "{\"rules\":[]}", "{\"policy\":{\"maxNewJobs\":50}}", "resume-java.pdf"));

        assertTrue(inserted.id() > 0);
        assertFalse(inserted.active(), "新模板默认不启用");
        assertEquals("Java 后端", inserted.name());
        assertEquals("后端", inserted.direction());
        assertEquals("Java 方向模板", inserted.description());
        assertEquals("{\"jobType\":\"Java\"}", inserted.bossConfigJson());
        assertEquals("您好，我是...", inserted.aiIntroduce());
        assertEquals("请输出招呼语", inserted.aiPrompt());
        assertEquals("{\"rules\":[]}", inserted.rulePayloadJson());
        assertEquals("{\"policy\":{\"maxNewJobs\":50}}", inserted.deliveryPolicyJson());
        assertEquals("resume-java.pdf", inserted.resumeFile());
        assertNotNull(inserted.createdAt());
        assertNotNull(inserted.updatedAt());
        assertNull(inserted.sortOrder(), "insert 不写 sort_order，默认未排序（NULL）");

        assertEquals(inserted, repository.findById(inserted.id()));
        assertNull(repository.findById(inserted.id() + 100));
        assertNull(repository.findById(0));
    }

    @Test
    void insertIgnoresProvidedIdAndActiveFlag() {
        ProfileTemplateRepository.ProfileTemplate input = new ProfileTemplateRepository.ProfileTemplate(
                999, "前端", null, null, true, null, null, null, null, null, null, null, null, null);
        ProfileTemplateRepository.ProfileTemplate inserted = repository.insert(input);
        assertTrue(inserted.id() != 999, "id 由数据库生成");
        assertFalse(inserted.active(), "insert 强制 active=false");
    }

    @Test
    void duplicateNameIsRejected() {
        repository.insert(template("Java 后端", null, null, null, null, null, null, null));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.insert(template("Java 后端", "后端", null, null, null, null, null, null)));
        assertEquals("模板名称已存在", error.getMessage());
        assertEquals(1, repository.list().size());
    }

    @Test
    void insertRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.insert(template("  ", null, null, null, null, null, null, null)));
        assertEquals(0, repository.list().size());
    }

    // ==================== 更新 ====================

    @Test
    void updateMetadataChangesOnlyMetadata() {
        ProfileTemplateRepository.ProfileTemplate inserted = repository.insert(template(
                "旧名称", "后端", "旧描述", "{\"cfg\":1}", "旧招呼语", "旧提示词", "{\"rules\":[1]}", "old.pdf"));

        ProfileTemplateRepository.ProfileTemplate updated = repository.updateMetadata(
                inserted.id(), "新名称", "全栈", "新描述");

        assertNotNull(updated);
        assertEquals("新名称", updated.name());
        assertEquals("全栈", updated.direction());
        assertEquals("新描述", updated.description());
        // 快照字段不变
        assertEquals("{\"cfg\":1}", updated.bossConfigJson());
        assertEquals("旧招呼语", updated.aiIntroduce());
        assertEquals("旧提示词", updated.aiPrompt());
        assertEquals("{\"rules\":[1]}", updated.rulePayloadJson());
        assertEquals("old.pdf", updated.resumeFile());

        assertNull(repository.updateMetadata(inserted.id() + 100, "不存在", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> repository.updateMetadata(inserted.id(), "  ", null, null));
    }

    @Test
    void updateMetadataRejectsDuplicateName() {
        ProfileTemplateRepository.ProfileTemplate first =
                repository.insert(template("模板A", null, null, null, null, null, null, null));
        repository.insert(template("模板B", null, null, null, null, null, null, null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.updateMetadata(first.id(), "模板B", null, null));
        assertEquals("模板名称已存在", error.getMessage());
        assertEquals("模板A", repository.findById(first.id()).name(), "冲突时不得改名");
    }

    @Test
    void updateSnapshotChangesConfigFieldsButNotMetadata() {
        ProfileTemplateRepository.ProfileTemplate inserted = repository.insert(template(
                "Java 后端", "后端", "描述", "{\"cfg\":1}", "旧招呼语", "旧提示词", "{\"rules\":[1]}",
                "{\"policy\":{\"maxNewJobs\":10}}", "old.pdf"));

        ProfileTemplateRepository.ProfileTemplate updated = repository.updateSnapshot(
                inserted.id(), "{\"cfg\":2}", "新招呼语", "新提示词", "{\"rules\":[2]}",
                "{\"policy\":{\"maxNewJobs\":20}}", "new.pdf");

        assertNotNull(updated);
        assertEquals("{\"cfg\":2}", updated.bossConfigJson());
        assertEquals("新招呼语", updated.aiIntroduce());
        assertEquals("新提示词", updated.aiPrompt());
        assertEquals("{\"rules\":[2]}", updated.rulePayloadJson());
        assertEquals("{\"policy\":{\"maxNewJobs\":20}}", updated.deliveryPolicyJson());
        assertEquals("new.pdf", updated.resumeFile());
        // name/direction/description 不变
        assertEquals("Java 后端", updated.name());
        assertEquals("后端", updated.direction());
        assertEquals("描述", updated.description());

        assertNull(repository.updateSnapshot(inserted.id() + 100, "{}", null, null, null, null, null));
    }

    @Test
    void deliveryPolicyJsonRoundTripsThroughInsertAndUpdate() {
        ProfileTemplateRepository.ProfileTemplate inserted = repository.insert(template(
                "采集策略", "后端", null, null, null, null, null,
                "{\"maxNewJobs\":100,\"limits\":{\"perKeyword\":20}}", null));
        assertEquals("{\"maxNewJobs\":100,\"limits\":{\"perKeyword\":20}}", inserted.deliveryPolicyJson());
        assertEquals("{\"maxNewJobs\":100,\"limits\":{\"perKeyword\":20}}",
                repository.findById(inserted.id()).deliveryPolicyJson());

        ProfileTemplateRepository.ProfileTemplate cleared = repository.updateSnapshot(
                inserted.id(), null, null, null, null, null, null);
        assertNull(cleared.deliveryPolicyJson(), "updateSnapshot 传 null 应清空该列");
    }

    @Test
    void updateResumeFileChangesOnlyResumeFile() {
        ProfileTemplateRepository.ProfileTemplate inserted = repository.insert(template(
                "Java 后端", "后端", "描述", "{\"cfg\":1}", "招呼语", "提示词", "{\"rules\":[1]}", "old.pdf"));

        ProfileTemplateRepository.ProfileTemplate updated =
                repository.updateResumeFile(inserted.id(), "new-resume.pdf");

        assertNotNull(updated);
        assertEquals("new-resume.pdf", updated.resumeFile());
        assertEquals("{\"cfg\":1}", updated.bossConfigJson());
        assertEquals("Java 后端", updated.name());
        assertNull(repository.updateResumeFile(inserted.id() + 100, "x.pdf"));
    }

    // ==================== active 唯一 ====================

    @Test
    void setActiveGuaranteesOnlyOneActiveTemplate() {
        ProfileTemplateRepository.ProfileTemplate first =
                repository.insert(template("模板A", null, null, null, null, null, null, null));
        ProfileTemplateRepository.ProfileTemplate second =
                repository.insert(template("模板B", null, null, null, null, null, null, null));
        ProfileTemplateRepository.ProfileTemplate third =
                repository.insert(template("模板C", null, null, null, null, null, null, null));

        repository.setActive(first.id());
        assertEquals(1, countActive());
        assertEquals(first.id(), repository.findActive().id());

        repository.setActive(second.id());
        assertEquals(1, countActive(), "切换后仍只有一条 active");
        assertEquals(second.id(), repository.findActive().id());
        assertFalse(repository.findById(first.id()).active());
        assertFalse(repository.findById(third.id()).active());

        // 重复启用同一模板仍只有一条 active
        repository.setActive(second.id());
        assertEquals(1, countActive());

        // 不存在的 id → IllegalArgumentException 且不改动现有 active
        assertThrows(IllegalArgumentException.class, () -> repository.setActive(second.id() + 100));
        assertEquals(second.id(), repository.findActive().id(), "异常后 active 不得被清空");
    }

    @Test
    void clearActiveDisablesAllTemplates() {
        ProfileTemplateRepository.ProfileTemplate template =
                repository.insert(template("模板A", null, null, null, null, null, null, null));
        repository.setActive(template.id());
        assertEquals(1, countActive());

        repository.clearActive();

        assertEquals(0, countActive());
        assertNull(repository.findActive());
        assertFalse(repository.findById(template.id()).active());
    }

    @Test
    void findActiveReturnsNullWhenNone() {
        assertNull(repository.findActive());
        repository.insert(template("模板A", null, null, null, null, null, null, null));
        assertNull(repository.findActive(), "未启用的模板不算 active");
    }

    // ==================== 列表与删除 ====================

    @Test
    void listOrdersByUpdatedAtDescThenIdDesc() {
        ProfileTemplateRepository.ProfileTemplate first =
                repository.insert(template("模板A", null, null, null, null, null, null, null));
        ProfileTemplateRepository.ProfileTemplate second =
                repository.insert(template("模板B", null, null, null, null, null, null, null));
        repository.insert(template("模板C", null, null, null, null, null, null, null));

        // 同一秒插入时按 id DESC
        List<ProfileTemplateRepository.ProfileTemplate> ordered = repository.list();
        assertEquals(3, ordered.size());
        assertEquals(second.id() + 1, ordered.get(0).id());

        // 把最早插入的模板 updated_at 改到未来 → 排到最前
        execute(dataSource,
                "UPDATE profile_template SET updated_at = '2030-01-01 00:00:00' WHERE id = ?", first.id());
        assertEquals(first.id(), repository.list().get(0).id(), "updated_at DESC 优先");
    }

    @Test
    void reorderWritesOrderAndKeepsUpdatedAt() {
        ProfileTemplateRepository.ProfileTemplate first =
                repository.insert(template("模板A", null, null, null, null, null, null, null));
        ProfileTemplateRepository.ProfileTemplate second =
                repository.insert(template("模板B", null, null, null, null, null, null, null));
        ProfileTemplateRepository.ProfileTemplate third =
                repository.insert(template("模板C", null, null, null, null, null, null, null));
        String firstUpdatedAt = first.updatedAt();

        repository.reorder(List.of(third.id(), first.id(), second.id()));

        List<ProfileTemplateRepository.ProfileTemplate> ordered = repository.list();
        assertEquals(List.of(third.id(), first.id(), second.id()), ids(ordered), "按传入顺序生效");
        assertEquals(0, ordered.get(0).sortOrder());
        assertEquals(1, ordered.get(1).sortOrder());
        assertEquals(2, ordered.get(2).sortOrder());
        assertEquals(firstUpdatedAt, repository.findById(first.id()).updatedAt(),
                "reorder 不修改 updated_at");

        // 新插入模板 sort_order 为 NULL → 排在已排序模板之后（旧数据兜底 updated_at DESC）
        ProfileTemplateRepository.ProfileTemplate fourth =
                repository.insert(template("模板D", null, null, null, null, null, null, null));
        List<ProfileTemplateRepository.ProfileTemplate> after = repository.list();
        assertEquals(third.id(), after.get(0).id());
        assertEquals(fourth.id(), after.get(3).id(), "未排序模板排在已排序模板之后");
        assertNull(after.get(3).sortOrder());
    }

    @Test
    void reorderEmptyOrNullListIsNoOp() {
        ProfileTemplateRepository.ProfileTemplate first =
                repository.insert(template("模板A", null, null, null, null, null, null, null));
        repository.insert(template("模板B", null, null, null, null, null, null, null));

        repository.reorder(null);
        repository.reorder(List.of());

        assertNull(repository.findById(first.id()).sortOrder(), "no-op 不得写入 sort_order");
    }

    @Test
    void reorderRejectsDuplicateIds() {
        ProfileTemplateRepository.ProfileTemplate first =
                repository.insert(template("模板A", null, null, null, null, null, null, null));
        repository.insert(template("模板B", null, null, null, null, null, null, null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.reorder(List.of(first.id(), first.id())));
        assertTrue(error.getMessage().contains("重复"));
        assertNull(repository.findById(first.id()).sortOrder(), "重复 id 不得部分写入");
    }

    @Test
    void reorderRejectsMissingIdAndRollsBackPartialUpdate() {
        ProfileTemplateRepository.ProfileTemplate first =
                repository.insert(template("模板A", null, null, null, null, null, null, null));
        ProfileTemplateRepository.ProfileTemplate second =
                repository.insert(template("模板B", null, null, null, null, null, null, null));
        repository.reorder(List.of(second.id(), first.id()));
        assertEquals(1, repository.findById(first.id()).sortOrder());
        assertEquals(0, repository.findById(second.id()).sortOrder());

        // 先更新 first（0），再遇到不存在 id → 整体回滚
        assertThrows(IllegalArgumentException.class,
                () -> repository.reorder(List.of(first.id(), second.id() + 100, second.id())));

        assertEquals(1, repository.findById(first.id()).sortOrder(), "失败后不得半更新");
        assertEquals(0, repository.findById(second.id()).sortOrder());
        assertEquals(List.of(second.id(), first.id()), ids(repository.list()));
    }

    @Test
    void legacyRowsSortByUpdatedAtWhenAllSortOrderNull() {
        ProfileTemplateRepository.ProfileTemplate first =
                repository.insert(template("模板A", null, null, null, null, null, null, null));
        ProfileTemplateRepository.ProfileTemplate second =
                repository.insert(template("模板B", null, null, null, null, null, null, null));

        assertTrue(repository.list().stream().allMatch(item -> item.sortOrder() == null));
        assertEquals(second.id(), repository.list().get(0).id(), "全 NULL 时按 id DESC 兜底");

        execute(dataSource,
                "UPDATE profile_template SET updated_at = '2030-01-01 00:00:00' WHERE id = ?", first.id());
        assertEquals(first.id(), repository.list().get(0).id(), "全 NULL 时按 updated_at DESC 兜底");
    }

    @Test
    void deleteReturnsWhetherRowDeleted() {
        ProfileTemplateRepository.ProfileTemplate inserted =
                repository.insert(template("模板A", null, null, null, null, null, null, null));

        assertTrue(repository.delete(inserted.id()));
        assertNull(repository.findById(inserted.id()));
        assertFalse(repository.delete(inserted.id()));
        assertFalse(repository.delete(0));
        assertTrue(repository.list().isEmpty());
    }

    // ==================== 辅助 ====================

    private List<Long> ids(List<ProfileTemplateRepository.ProfileTemplate> templates) {
        return templates.stream().map(ProfileTemplateRepository.ProfileTemplate::id).toList();
    }

    private int countActive() {
        return PersistenceTestSupport.scalar(dataSource,
                "SELECT COUNT(*) FROM profile_template WHERE active = 1");
    }

    private ProfileTemplateRepository.ProfileTemplate template(String name, String direction, String description,
                                                               String bossConfigJson, String aiIntroduce,
                                                               String aiPrompt, String rulePayloadJson,
                                                               String resumeFile) {
        return template(name, direction, description, bossConfigJson, aiIntroduce, aiPrompt,
                rulePayloadJson, null, resumeFile);
    }

    private ProfileTemplateRepository.ProfileTemplate template(String name, String direction, String description,
                                                               String bossConfigJson, String aiIntroduce,
                                                               String aiPrompt, String rulePayloadJson,
                                                               String deliveryPolicyJson, String resumeFile) {
        return new ProfileTemplateRepository.ProfileTemplate(0, name, direction, description, false,
                bossConfigJson, aiIntroduce, aiPrompt, rulePayloadJson, deliveryPolicyJson, resumeFile,
                null, null, null);
    }
}



