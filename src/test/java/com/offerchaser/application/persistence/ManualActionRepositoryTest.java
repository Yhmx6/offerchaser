package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 人工操作审计仓库测试：幂等键、唯一冲突、按对象查询。
 */
class ManualActionRepositoryTest {

    @TempDir
    Path directory;

    private ManualActionRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = PersistenceTestSupport.migratedDataSource(directory);
        repository = new ManualActionRepository(dataSource);
    }

    @Test
    void insertAndFindByIdempotencyKey() {
        ManualActionRepository.ManualAction action = repository.insert(
                "contact_state", "uid:HR-1", "MARK_STATE", "UNKNOWN", "REPLIED", "人工标记", "key-1");

        assertTrue(action.id() > 0);
        assertEquals("contact_state", action.objectType());
        assertEquals("uid:HR-1", action.objectId());
        assertEquals("MARK_STATE", action.action());
        assertEquals("UNKNOWN", action.beforeState());
        assertEquals("REPLIED", action.afterState());
        assertEquals("人工标记", action.reason());
        assertEquals("key-1", action.idempotencyKey());
        assertNotNull(action.createdAt());

        assertEquals(action, repository.findByIdempotencyKey("key-1"));
        assertNull(repository.findByIdempotencyKey("missing"));
        assertNull(repository.findByIdempotencyKey(null));
        assertNull(repository.findByIdempotencyKey("  "));
    }

    @Test
    void nullIdempotencyKeyIsAllowedForMultipleRows() {
        repository.insert("contact_state", "uid:HR-1", "MARK_STATE", null, "REPLIED", null, null);
        repository.insert("contact_state", "uid:HR-1", "MARK_STATE", null, "REJECTED", null, null);
        assertEquals(2, repository.findByObject("contact_state", "uid:HR-1", 20).size());
    }

    @Test
    void duplicateIdempotencyKeyIsRejectedWithKeyInMessage() {
        repository.insert("followup_plan", "plan-1", "APPROVE", "SUGGESTED", "APPROVED", "批准", "key-2");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> repository.insert(
                "followup_plan", "plan-1", "APPROVE", "SUGGESTED", "APPROVED", "重复提交", "key-2"));
        assertTrue(error.getMessage().contains("key-2"), "异常信息需带 key：" + error.getMessage());
        assertEquals(1, repository.findByObject("followup_plan", "plan-1", 20).size());
    }

    @Test
    void findByObjectOrdersByIdDescAndHonoursLimit() {
        repository.insert("contact_state", "uid:HR-1", "MARK_STATE", "UNKNOWN", "REPLIED", null, null);
        repository.insert("contact_state", "uid:HR-1", "MARK_STATE", "REPLIED", "INTERVIEW", null, null);
        repository.insert("contact_state", "uid:HR-2", "MARK_STATE", null, "REJECTED", null, null);

        List<ManualActionRepository.ManualAction> actions =
                repository.findByObject("contact_state", "uid:HR-1", 20);
        assertEquals(2, actions.size());
        assertEquals("INTERVIEW", actions.get(0).afterState(), "id DESC：最新在前");
        assertEquals("REPLIED", actions.get(1).afterState());

        assertEquals(1, repository.findByObject("contact_state", "uid:HR-1", 1).size());
        assertEquals(2, repository.findByObject("contact_state", "uid:HR-1", 0).size(), "limit<=0 用默认值");
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.insert(null, "id", "action", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> repository.insert("type", " ", "action", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> repository.insert("type", "id", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findByObject(" ", "id", 20));
    }
}
