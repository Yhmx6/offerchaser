package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static com.offerchaser.application.persistence.PersistenceTestSupport.ACCOUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 跟进计划仓库测试：插入/唯一键、findActive、分页筛选、状态迁移条件更新。
 */
class FollowupPlanRepositoryTest {

    private static final String PLATFORM = "boss";

    @TempDir
    Path directory;

    private FollowupPlanRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = PersistenceTestSupport.migratedDataSource(directory);
        repository = new FollowupPlanRepository(dataSource);
    }

    @Test
    void insertAndFindByPlanId() {
        FollowupPlanRepository.FollowupPlan inserted =
                repository.insert(plan("plan-1", "enc-1", "uid:HR-1", "FOLLOWUP", "SUGGESTED"));

        assertTrue(inserted.id() > 0);
        assertEquals(0, inserted.version());
        assertNotNull(inserted.createdAt());
        assertNotNull(inserted.suggestedAt());
        assertEquals("plan-1", inserted.planId());
        assertEquals("enc-1", inserted.encryptId());
        assertEquals("uid:HR-1", inserted.contactKey());

        assertEquals(inserted, repository.findByPlanId("plan-1"));
        assertNull(repository.findByPlanId("missing"));
    }

    @Test
    void duplicatePlanIdIsRejected() {
        repository.insert(plan("plan-1", "enc-1", "uid:HR-1", "FOLLOWUP", "SUGGESTED"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.insert(plan("plan-1", "enc-1", "uid:HR-1", "FOLLOWUP", "APPROVED")));
        assertTrue(error.getMessage().contains("plan-1"));
        assertEquals(1, repository.list(null, 1, 20).total());
    }

    @Test
    void insertValidatesRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.insert(plan(" ", "enc-1", "uid:HR-1", "FOLLOWUP", "SUGGESTED")));
        assertThrows(IllegalArgumentException.class,
                () -> repository.insert(plan("plan-x", "enc-1", "uid:HR-1", null, "SUGGESTED")));
        assertThrows(IllegalArgumentException.class,
                () -> repository.insert(plan("plan-y", "enc-1", "uid:HR-1", "FOLLOWUP", " ")));
        assertEquals(0, repository.list(null, 1, 20).total());
    }

    @Test
    void findActiveOnlyReturnsSuggestedOrApproved() {
        repository.insert(plan("plan-suggested", "enc-1", "uid:HR-1", "FOLLOWUP", "SUGGESTED"));
        repository.insert(plan("plan-rejected", "enc-1", "uid:HR-1", "FOLLOWUP", "REJECTED"));

        FollowupPlanRepository.FollowupPlan active = repository.findActive(
                ACCOUNT, PLATFORM, "uid:HR-1", "enc-1", "FOLLOWUP");
        assertNotNull(active);
        assertEquals("plan-suggested", active.planId());

        // 不同 encryptId / planType 不应命中
        assertNull(repository.findActive(ACCOUNT, PLATFORM, "uid:HR-1", "enc-2", "FOLLOWUP"));
        assertNull(repository.findActive(ACCOUNT, PLATFORM, "uid:HR-1", "enc-1", "IMAGE_RESEND"));
        // 非空参数才参与筛选（planType 为空时放宽）
        assertNotNull(repository.findActive(ACCOUNT, PLATFORM, "uid:HR-1", "enc-1", null));
    }

    @Test
    void listFiltersPaginatesAndOrderBySuggestedAtDesc() {
        repository.insert(plan("plan-1", "enc-1", "uid:HR-1", "FOLLOWUP", "SUGGESTED"));
        repository.insert(plan("plan-2", "enc-2", "uid:HR-2", "FOLLOWUP", "APPROVED"));
        repository.insert(plan("plan-3", "enc-3", "uid:HR-3", "IMAGE_RESEND", "REJECTED"));

        PageResult<FollowupPlanRepository.FollowupPlan> all = repository.list(null, 1, 2);
        assertEquals(3, all.total());
        assertEquals(2, all.items().size());
        assertEquals(1, repository.list(null, 2, 2).items().size(), "第 2 页只剩 1 条");

        assertEquals(1, repository.list("APPROVED", 1, 20).total());
        assertEquals("plan-2", repository.list("APPROVED", 1, 20).items().get(0).planId());
        assertEquals(2, repository.list("SUGGESTED", 1, 20).total()
                + repository.list("REJECTED", 1, 20).total());

        assertEquals(1, repository.list(null, 0, 0).page());
        assertEquals(20, repository.list(null, 0, 0).size());
        assertEquals(100, repository.list(null, 1, 500).size());
    }

    @Test
    void listActiveByContactReturnsOnlyActiveOfThatContact() {
        repository.insert(plan("plan-1", "enc-1", "uid:HR-1", "FOLLOWUP", "SUGGESTED"));
        repository.insert(plan("plan-2", "enc-2", "uid:HR-1", "IMAGE_RESEND", "APPROVED"));
        repository.insert(plan("plan-3", "enc-1", "uid:HR-1", "FOLLOWUP", "EXECUTED"));
        repository.insert(plan("plan-4", "enc-1", "uid:HR-2", "FOLLOWUP", "SUGGESTED"));

        List<FollowupPlanRepository.FollowupPlan> active =
                repository.listActiveByContact(ACCOUNT, PLATFORM, "uid:HR-1");
        assertEquals(List.of("plan-2", "plan-1"),
                active.stream().map(FollowupPlanRepository.FollowupPlan::planId).toList());
        assertTrue(repository.listActiveByContact(ACCOUNT, PLATFORM, "uid:missing").isEmpty());
    }

    @Test
    void transitionStatusIsConditionalOnStatusAndVersion() {
        repository.insert(plan("plan-1", "enc-1", "uid:HR-1", "FOLLOWUP", "SUGGESTED"));

        FollowupPlanRepository.FollowupPlan approved = repository.transitionStatus(
                "plan-1", "SUGGESTED", "APPROVED", "用户批准", 0,
                "2026-11-05 10:00:00", null, null);
        assertNotNull(approved);
        assertEquals("APPROVED", approved.status());
        assertEquals(1, approved.version());
        assertEquals("用户批准", approved.reason());
        assertEquals("2026-11-05 10:00:00", approved.decidedAt());

        // 版本号不匹配 → null 且不改库
        assertNull(repository.transitionStatus("plan-1", "APPROVED", "EXECUTED", "执行", 0,
                null, "2026-11-05 11:00:00", "attempt-1"));
        assertEquals("APPROVED", repository.findByPlanId("plan-1").status());
        // fromStatus 不匹配 → null
        assertNull(repository.transitionStatus("plan-1", "SUGGESTED", "REJECTED", "拒绝", 1,
                null, null, null));
        // 正确状态+版本 → 成功，执行字段落库
        FollowupPlanRepository.FollowupPlan executed = repository.transitionStatus(
                "plan-1", "APPROVED", "EXECUTED", "执行完成", 1, null,
                "2026-11-05 11:00:00", "attempt-1");
        assertNotNull(executed);
        assertEquals("EXECUTED", executed.status());
        assertEquals(2, executed.version());
        assertEquals("2026-11-05 11:00:00", executed.executedAt());
        assertEquals("attempt-1", executed.executedAttemptId());
        // 不存在的 plan → null
        assertNull(repository.transitionStatus("missing", "SUGGESTED", "APPROVED", null, 0,
                null, null, null));
    }

    @Test
    void imageResendPlanKeepsParentAttemptId() {
        FollowupPlanRepository.FollowupPlan plan = new FollowupPlanRepository.FollowupPlan(
                0, "plan-image-1", ACCOUNT, PLATFORM, "enc-1", "HR-1", "uid:HR-1",
                "Java开发", "甲公司", "IMAGE_RESEND", "SUGGESTED", "图片失败补发", "attempt-origin",
                0, null, null, null, null, null, null);
        FollowupPlanRepository.FollowupPlan inserted = repository.insert(plan);
        assertEquals("attempt-origin", inserted.parentAttemptId());
        assertEquals("IMAGE_RESEND", inserted.planType());
        assertEquals("图片失败补发", inserted.reason());
    }

    private FollowupPlanRepository.FollowupPlan plan(String planId, String encryptId, String contactKey,
                                                     String planType, String status) {
        return new FollowupPlanRepository.FollowupPlan(
                0, planId, ACCOUNT, PLATFORM, encryptId, "HR-1", contactKey,
                "Java开发", "甲公司", planType, status, "建议跟进", null,
                0, null, null, null, null, null, null);
    }
}
