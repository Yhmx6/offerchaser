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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3-B 人工放行仓库测试：插入/唯一约束、查询排序与限额、状态流转时间戳、重新放行、空库边界。
 */
class ManualReleaseRepositoryTest {

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource;
    private ManualReleaseRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = PersistenceTestSupport.migratedDataSource(directory);
        repository = new ManualReleaseRepository(dataSource);
    }

    // ==================== 插入与查询 ====================

    @Test
    void insertAndFindByEvaluationAndReleaseId() {
        ManualReleaseRepository.ManualRelease inserted = repository.insert(release(
                "rel-1", 101L, "APPROVED", "人工批准：AI REVIEW 但岗位匹配",
                "2026-11-07 10:00:00", null, null, null, null));

        assertEquals("rel-1", inserted.releaseId());
        assertEquals(101L, inserted.evaluationId());
        assertEquals("APPROVED", inserted.status());
        assertEquals("人工批准：AI REVIEW 但岗位匹配", inserted.reason());
        assertEquals("2026-11-07 10:00:00", inserted.requestedAt());
        assertNull(inserted.decidedAt(), "APPROVED 初态不写 decided_at");
        assertNull(inserted.consumedAt());
        assertNull(inserted.attemptId());
        assertNull(inserted.error());
        assertNotNull(inserted.createdAt());
        assertNotNull(inserted.updatedAt());

        assertEquals(inserted, repository.findByReleaseId("rel-1"));
        assertEquals(inserted, repository.findByEvaluationId(101L));
        assertNull(repository.findByReleaseId("not-exists"));
        assertNull(repository.findByEvaluationId(999L));
        assertNull(repository.findByEvaluationId(0L));
        assertNull(repository.findByReleaseId(null));
    }

    @Test
    void insertGeneratesReleaseIdWhenBlank() {
        ManualReleaseRepository.ManualRelease inserted = repository.insert(release(
                null, 102L, "APPROVED", null, null, null, null, null, null));

        assertNotNull(inserted.releaseId());
        assertFalse(inserted.releaseId().isBlank());
        assertNotNull(repository.findByReleaseId(inserted.releaseId()));
    }

    @Test
    void insertRejectsDuplicateEvaluationId() {
        repository.insert(release("rel-1", 103L, "APPROVED", null, null, null, null, null, null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.insert(release("rel-2", 103L, "APPROVED", null,
                        null, null, null, null, null)));
        assertTrue(error.getMessage().contains("该岗位评估已存在人工放行记录"));
        assertEquals(1, repository.listAll(10).size());
    }

    @Test
    void insertRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> repository.insert(null));
        assertThrows(IllegalArgumentException.class,
                () -> repository.insert(release("rel-x", 0L, "APPROVED", null,
                        null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> repository.insert(release("rel-x", 104L, "BOGUS", null,
                        null, null, null, null, null)));
        assertEquals(0, repository.listAll(10).size());
    }

    @Test
    void insertRejectedStatusDefaultsDecidedAt() {
        ManualReleaseRepository.ManualRelease inserted = repository.insert(release(
                "rel-rej", 105L, "REJECTED", "不匹配", null, null, null, null, null));
        assertEquals("REJECTED", inserted.status());
        assertNotNull(inserted.decidedAt(), "非 APPROVED 初态应补 decided_at");
    }

    @Test
    void findApprovedOrdersByRequestedAtAscAndHonorsLimit() {
        repository.insert(release("rel-3", 203L, "APPROVED", null,
                "2026-11-07 12:00:00", null, null, null, null));
        repository.insert(release("rel-1", 201L, "APPROVED", null,
                "2026-11-07 10:00:00", null, null, null, null));
        repository.insert(release("rel-2", 202L, "APPROVED", null,
                "2026-11-07 11:00:00", null, null, null, null));
        repository.insert(release("rel-consumed", 204L, "CONSUMED", null,
                "2026-11-07 09:00:00", null, null, null, null));
        repository.insert(release("rel-rejected", 205L, "REJECTED", null,
                "2026-11-07 08:00:00", null, null, null, null));

        List<ManualReleaseRepository.ManualRelease> approved = repository.findApproved(10);
        assertEquals(3, approved.size(), "只返回 APPROVED");
        assertEquals(List.of("rel-1", "rel-2", "rel-3"),
                approved.stream().map(ManualReleaseRepository.ManualRelease::releaseId).toList(),
                "先批准先消费（requested_at ASC）");
        assertEquals(2, repository.findApproved(2).size());
        assertEquals(3, repository.findApproved(0).size(), "limit<=0 取默认值");
    }

    @Test
    void listAllOrdersByRequestedAtDescAndHonorsLimit() {
        repository.insert(release("rel-1", 301L, "APPROVED", null,
                "2026-11-07 10:00:00", null, null, null, null));
        repository.insert(release("rel-2", 302L, "CONSUMED", null,
                "2026-11-07 11:00:00", null, null, null, null));
        repository.insert(release("rel-3", 303L, "FAILED", "发送失败",
                "2026-11-07 12:00:00", null, null, null, null));

        List<ManualReleaseRepository.ManualRelease> all = repository.listAll(10);
        assertEquals(3, all.size());
        assertEquals(List.of("rel-3", "rel-2", "rel-1"),
                all.stream().map(ManualReleaseRepository.ManualRelease::releaseId).toList());
        assertEquals(2, repository.listAll(2).size());
        assertEquals(3, repository.listAll(-1).size());
    }

    // ==================== 状态流转 ====================

    @Test
    void updateStatusConsumedSetsDecidedAndConsumedAt() {
        repository.insert(release(
                "rel-1", 401L, "APPROVED", null, "2026-11-07 10:00:00", null, null, null, null));
        execute(dataSource, "UPDATE manual_release SET updated_at = '2020-01-01 00:00:00'");

        repository.updateStatus("rel-1", "CONSUMED", "att-1", null);

        ManualReleaseRepository.ManualRelease updated = repository.findByReleaseId("rel-1");
        assertEquals("CONSUMED", updated.status());
        assertEquals("att-1", updated.attemptId());
        assertNotNull(updated.consumedAt(), "CONSUMED 应补 consumed_at");
        assertNotNull(updated.decidedAt());
        assertNull(updated.error());
        assertNotEquals("2020-01-01 00:00:00", updated.updatedAt(), "updated_at 应刷新");
        assertTrue(repository.findApproved(10).isEmpty(), "消费后不再出现在待放行列表");
    }

    @Test
    void updateStatusFailedRecordsAttemptAndError() {
        repository.insert(release("rel-1", 402L, "APPROVED", null,
                "2026-11-07 10:00:00", null, null, null, null));

        repository.updateStatus("rel-1", "FAILED", "att-2", "发送失败：凭证过期");

        ManualReleaseRepository.ManualRelease failed = repository.findByReleaseId("rel-1");
        assertEquals("FAILED", failed.status());
        assertEquals("att-2", failed.attemptId());
        assertEquals("发送失败：凭证过期", failed.error());
        assertNotNull(failed.decidedAt());
        assertNull(failed.consumedAt());
    }

    @Test
    void updateStatusRejectsMissingOrInvalidStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.updateStatus("not-exists", "CONSUMED", "att", null));

        repository.insert(release("rel-1", 403L, "APPROVED", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> repository.updateStatus("rel-1", "BOGUS", null, null));
        assertEquals("APPROVED", repository.findByReleaseId("rel-1").status(), "非法状态不得写入");
    }

    // ==================== 重新放行 ====================

    @Test
    void reopenFailedReleaseClearsFailureFields() {
        repository.insert(release("rel-1", 501L, "APPROVED", null,
                "2026-11-07 10:00:00", null, null, null, null));
        repository.updateStatus("rel-1", "FAILED", "att-9", "发送确认超时");

        ManualReleaseRepository.ManualRelease reopened = repository.reopen("rel-1");

        assertEquals("APPROVED", reopened.status());
        assertNull(reopened.error(), "reopen 必须清空 error");
        assertNull(reopened.attemptId(), "reopen 必须清空 attempt_id");
        assertNull(reopened.consumedAt(), "reopen 必须清空 consumed_at");
        assertEquals(1, repository.findApproved(10).size());
    }

    @Test
    void reopenRejectedRelease() {
        repository.insert(release("rel-1", 502L, "REJECTED", "理由",
                "2026-11-07 10:00:00", null, null, null, null));

        assertEquals("APPROVED", repository.reopen("rel-1").status());
    }

    @Test
    void reopenRejectsWrongStatusOrMissing() {
        repository.insert(release("rel-1", 503L, "APPROVED", null,
                "2026-11-07 10:00:00", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> repository.reopen("rel-1"));
        assertThrows(IllegalArgumentException.class, () -> repository.reopen("not-exists"));
    }

    // ==================== 空库 ====================

    @Test
    void emptyDatabaseReturnsNullAndEmptyLists() {
        assertNull(repository.findByReleaseId("rel-1"));
        assertNull(repository.findByEvaluationId(1L));
        assertTrue(repository.findApproved(10).isEmpty());
        assertTrue(repository.listAll(10).isEmpty());
    }

    // ==================== 辅助 ====================

    private ManualReleaseRepository.ManualRelease release(String releaseId, long evaluationId, String status,
                                                          String reason, String requestedAt, String decidedAt,
                                                          String consumedAt, String attemptId, String error) {
        return new ManualReleaseRepository.ManualRelease(releaseId, evaluationId, status, reason,
                requestedAt, decidedAt, consumedAt, attemptId, error, null, null);
    }
}

