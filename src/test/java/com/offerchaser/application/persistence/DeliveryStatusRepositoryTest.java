package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2 投递状态同步仓库测试：start/finish 计数、快照批量写入与回读、findLatest /
 * findCompletedStartup / findRecent、空值与事务边界。
 */
class DeliveryStatusRepositoryTest {

    @TempDir
    Path directory;

    private DeliveryStatusRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = PersistenceTestSupport.migratedDataSource(directory);
        repository = new DeliveryStatusRepository(dataSource);
    }

    // ==================== start / finish ====================

    @Test
    void startInsertsRunningRowWithCounts() {
        repository.start(run("sync-1", "ACTIVE", "TODAY", "2026-11-07", "RUNNING",
                "2026-11-07 10:00:00",
                new DeliveryStatusRepository.Counts(10, 6, 3, 3, 1),
                new DeliveryStatusRepository.Counts(5, 2, 1, 2, 1),
                new DeliveryStatusRepository.Counts(4, 3, 2, 1, 0), null));

        DeliveryStatusRepository.StatusSyncRun latest = repository.findLatest();
        assertNotNull(latest);
        assertEquals("sync-1", latest.syncId());
        assertEquals("ACTIVE", latest.triggerType());
        assertEquals("TODAY", latest.scope());
        assertEquals("2026-11-07", latest.targetDate());
        assertEquals("RUNNING", latest.status());
        assertEquals("2026-11-07 10:00:00", latest.startedAt());
        assertNull(latest.finishedAt());
        assertEquals(new DeliveryStatusRepository.Counts(10, 6, 3, 3, 1), latest.autoCounts());
        assertEquals(new DeliveryStatusRepository.Counts(5, 2, 1, 2, 1), latest.manualCounts());
        assertEquals(new DeliveryStatusRepository.Counts(4, 3, 2, 1, 0), latest.hrCounts());
        assertNull(latest.error());

        assertEquals(latest, repository.findBySyncId("sync-1"));
        assertNull(repository.findBySyncId("not-exists"));
        assertNull(repository.findBySyncId(null));
    }

    @Test
    void startDefaultsStatusStartedAtAndNullCountsToZero() {
        repository.start(new DeliveryStatusRepository.StatusSyncRun("sync-default", "STARTUP", "TODAY",
                "2026-11-07", null, null, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null));

        DeliveryStatusRepository.StatusSyncRun latest = repository.findBySyncId("sync-default");
        assertNotNull(latest);
        assertEquals("RUNNING", latest.status());
        assertNotNull(latest.startedAt(), "startedAt 缺省应填当前时间");
        assertEquals(DeliveryStatusRepository.Counts.zero(), latest.autoCounts());
    }

    @Test
    void startRejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> repository.start(null));
        assertThrows(IllegalArgumentException.class,
                () -> repository.start(run("", "ACTIVE", "TODAY", "2026-11-07", "RUNNING",
                        "2026-11-07 10:00:00", null, null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> repository.start(run("sync-x", null, "TODAY", "2026-11-07", "RUNNING",
                        "2026-11-07 10:00:00", null, null, null, null)));
        assertNull(repository.findLatest(), "非法 start 不得写入");
    }

    @Test
    void finishUpdatesStatusCountsAndFinishedAt() {
        repository.start(run("sync-1", "ACTIVE", "TODAY", "2026-11-07", "RUNNING",
                "2026-11-07 10:00:00", null, null, null, null));

        repository.finish("sync-1", "COMPLETED",
                new DeliveryStatusRepository.Counts(10, 6, 3, 3, 1),
                new DeliveryStatusRepository.Counts(5, 2, 1, 2, 1),
                new DeliveryStatusRepository.Counts(4, 3, 2, 1, 0), null);

        DeliveryStatusRepository.StatusSyncRun finished = repository.findBySyncId("sync-1");
        assertNotNull(finished);
        assertEquals("COMPLETED", finished.status());
        assertNotNull(finished.finishedAt());
        assertEquals(new DeliveryStatusRepository.Counts(10, 6, 3, 3, 1), finished.autoCounts());
        assertEquals(new DeliveryStatusRepository.Counts(5, 2, 1, 2, 1), finished.manualCounts());
        assertEquals(new DeliveryStatusRepository.Counts(4, 3, 2, 1, 0), finished.hrCounts());
        assertNull(finished.error());
    }

    @Test
    void finishFailedWithNullCountsAndErrorPersists() {
        repository.start(run("sync-2", "STARTUP", "PREVIOUS_DAY", "2026-11-06", "RUNNING",
                "2026-11-07 09:00:00", null, null, null, null));

        repository.finish("sync-2", "FAILED", null, null, null, "boss 登录态失效");

        DeliveryStatusRepository.StatusSyncRun failed = repository.findBySyncId("sync-2");
        assertEquals("FAILED", failed.status());
        assertEquals("boss 登录态失效", failed.error());
        assertEquals(DeliveryStatusRepository.Counts.zero(), failed.autoCounts());
        assertEquals(DeliveryStatusRepository.Counts.zero(), failed.manualCounts());
        assertEquals(DeliveryStatusRepository.Counts.zero(), failed.hrCounts());
    }

    @Test
    void finishMissingSyncThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.finish("not-exists", "COMPLETED", null, null, null, null));
    }

    // ==================== 快照 ====================

    @Test
    void insertSnapshotsAndReadBack() {
        repository.start(run("sync-1", "ACTIVE", "TODAY", "2026-11-07", "RUNNING",
                "2026-11-07 10:00:00", null, null, null, null));
        repository.insertSnapshots(List.of(
                snapshot("sync-1", "AUTO", "uid:1", "u-1", "张三", "甲公司", "Java 开发",
                        "已投递", "READ", "REPLIED", "SELF", "您好", "2026-11-07 09:00:00", "截图"),
                snapshot("sync-1", "MANUAL", "uid:2", "u-2", "李四", "乙公司", "后端开发",
                        "已投递", "UNREAD", "NO_REPLY", "SELF", "您好", "2026-11-07 09:10:00", null),
                snapshot("sync-1", "HR_INITIATED", "uid:3", "u-3", "王五", "丙公司", "全栈",
                        null, "UNKNOWN", "UNKNOWN", "HR", "在吗", null, null)));
        repository.insertSnapshots(List.of());

        List<DeliveryStatusRepository.DeliveryStatusSnapshot> snapshots =
                repository.findSnapshots("sync-1");
        assertEquals(3, snapshots.size(), "按 id ASC 回读");
        DeliveryStatusRepository.DeliveryStatusSnapshot first = snapshots.get(0);
        assertTrue(first.id() > 0);
        assertEquals("AUTO", first.source());
        assertEquals("uid:1", first.contactKey());
        assertEquals("u-1", first.encryptUserId());
        assertEquals("张三", first.hrName());
        assertEquals("甲公司", first.companyName());
        assertEquals("Java 开发", first.jobName());
        assertEquals("已投递", first.deliveredStatus());
        assertEquals("READ", first.readStatus());
        assertEquals("REPLIED", first.replyStatus());
        assertEquals("SELF", first.initiator());
        assertEquals("您好", first.lastMessage());
        assertEquals("2026-11-07 09:00:00", first.chatTime());
        assertEquals("截图", first.evidence());
        assertNotNull(first.createdAt());
        assertEquals("HR_INITIATED", snapshots.get(2).source());
        assertEquals("在吗", snapshots.get(2).lastMessage());
        assertTrue(repository.findSnapshots("sync-other").isEmpty());
        assertTrue(repository.findSnapshots(null).isEmpty());
    }

    @Test
    void insertSnapshotsRollsBackWhenAnyItemInvalid() {
        repository.start(run("sync-1", "ACTIVE", "TODAY", "2026-11-07", "RUNNING",
                "2026-11-07 10:00:00", null, null, null, null));

        List<DeliveryStatusRepository.DeliveryStatusSnapshot> batch = List.of(
                snapshot("sync-1", "AUTO", "uid:1", "u-1", "张三", "甲公司", "Java",
                        "已投递", "READ", "REPLIED", "SELF", "您好", null, null),
                snapshot("sync-1", " ", "uid:2", "u-2", "李四", "乙公司", "后端",
                        "已投递", "UNREAD", "NO_REPLY", "SELF", "您好", null, null));
        assertThrows(IllegalArgumentException.class, () -> repository.insertSnapshots(batch));

        assertTrue(repository.findSnapshots("sync-1").isEmpty(), "批量插入失败必须整体回滚");
    }

    // ==================== 查询 ====================

    @Test
    void emptyDatabaseReturnsNullsAndEmptyLists() {
        assertNull(repository.findLatest());
        assertNull(repository.findBySyncId("not-exists"));
        assertNull(repository.findCompletedStartup("2026-11-07"));
        assertTrue(repository.findSnapshots("sync-1").isEmpty());
        assertTrue(repository.findRecent(10).isEmpty());
    }

    @Test
    void findLatestReturnsNewestByStartedAt() {
        repository.start(run("sync-old", "ACTIVE", "TODAY", "2026-11-06", "COMPLETED",
                "2026-11-06 10:00:00", null, null, null, null));
        repository.start(run("sync-new", "STARTUP", "TODAY", "2026-11-07", "COMPLETED",
                "2026-11-07 10:00:00", null, null, null, null));

        assertEquals("sync-new", repository.findLatest().syncId());
    }

    @Test
    void findCompletedStartupFiltersByTriggerStatusAndDate() {
        repository.start(run("startup-1", "STARTUP", "TODAY", "2026-11-07", "COMPLETED",
                "2026-11-07 09:00:00", null, null, null, null));
        repository.start(run("startup-2", "STARTUP", "TODAY", "2026-11-07", "COMPLETED",
                "2026-11-07 10:00:00", null, null, null, null));
        repository.start(run("startup-running", "STARTUP", "TODAY", "2026-11-07", "RUNNING",
                "2026-11-07 11:00:00", null, null, null, null));
        repository.start(run("active-completed", "ACTIVE", "TODAY", "2026-11-07", "COMPLETED",
                "2026-11-07 12:00:00", null, null, null, null));
        repository.start(run("startup-prev", "STARTUP", "PREVIOUS_DAY", "2026-11-06", "COMPLETED",
                "2026-11-07 13:00:00", null, null, null, null));

        assertEquals("startup-2", repository.findCompletedStartup("2026-11-07").syncId(),
                "同日多条取 started_at 最新");
        assertEquals("startup-prev", repository.findCompletedStartup(null).syncId(),
                "不传日期取最近完成启动同步");
        assertEquals("startup-prev", repository.findCompletedStartup("  ").syncId());
        assertNull(repository.findCompletedStartup("2026-10-01"));
    }

    @Test
    void findRecentOrdersByStartedAtDescAndHonorsLimit() {
        for (int i = 0; i < 5; i++) {
            repository.start(run("sync-" + i, "ACTIVE", "TODAY", "2026-11-07", "COMPLETED",
                    "2026-11-07 0" + i + ":00:00", null, null, null, null));
        }

        List<DeliveryStatusRepository.StatusSyncRun> recent = repository.findRecent(3);
        assertEquals(3, recent.size());
        assertEquals("sync-4", recent.get(0).syncId());
        assertEquals("sync-3", recent.get(1).syncId());
        assertEquals("sync-2", recent.get(2).syncId());
        assertEquals(5, repository.findRecent(100).size());
        assertEquals(5, repository.findRecent(0).size(), "limit<=0 取默认值");
        assertEquals(5, repository.findRecent(-3).size());
    }

    // ==================== 辅助 ====================

    private DeliveryStatusRepository.StatusSyncRun run(String syncId, String triggerType, String scope,
                                                       String targetDate, String status, String startedAt,
                                                       DeliveryStatusRepository.Counts auto,
                                                       DeliveryStatusRepository.Counts manual,
                                                       DeliveryStatusRepository.Counts hr, String error) {
        DeliveryStatusRepository.Counts zero = DeliveryStatusRepository.Counts.zero();
        DeliveryStatusRepository.Counts a = auto == null ? zero : auto;
        DeliveryStatusRepository.Counts m = manual == null ? zero : manual;
        DeliveryStatusRepository.Counts h = hr == null ? zero : hr;
        return new DeliveryStatusRepository.StatusSyncRun(syncId, triggerType, scope, targetDate, status,
                startedAt, null, a.total(), a.read(), a.replied(), a.unread(), a.unknown(),
                m.total(), m.read(), m.replied(), m.unread(), m.unknown(),
                h.total(), h.read(), h.replied(), h.unread(), h.unknown(), error);
    }

    private DeliveryStatusRepository.DeliveryStatusSnapshot snapshot(String syncId, String source,
                                                                     String contactKey, String encryptUserId,
                                                                     String hrName, String companyName,
                                                                     String jobName, String deliveredStatus,
                                                                     String readStatus, String replyStatus,
                                                                     String initiator, String lastMessage,
                                                                     String chatTime, String evidence) {
        return new DeliveryStatusRepository.DeliveryStatusSnapshot(0, syncId, source, contactKey,
                encryptUserId, hrName, companyName, jobName, deliveredStatus, readStatus, replyStatus,
                initiator, lastMessage, chatTime, evidence, null);
    }


}



