package com.offerchaser.verification;

import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.ContactValidationException;
import com.offerchaser.application.contacts.ContactVersionConflictException;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.ContactStateRepository;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.FollowupPlanRepository;
import com.offerchaser.application.persistence.ManualActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 跟进待办真实 SQLite 集成验收：
 * generate 只生成 SUGGESTED、间隔/次数/拦截/空闲去重、approve 不发送、reject、
 * markExecuted（失败保留 APPROVED）、联系人状态变化使批准失效、幂等键只执行一次。
 */
class FollowupServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private FollowupPlanRepository followupPlanRepository;
    private ManualActionRepository manualActionRepository;
    private ContactStateService contactStateService;
    private FollowupService followupService;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        followupPlanRepository = new FollowupPlanRepository(dataSource);
        manualActionRepository = new ManualActionRepository(dataSource);
        ContactStateRepository contactStateRepository = new ContactStateRepository(dataSource);
        DeliveryLedgerService ledgerService = new DeliveryLedgerService(dataSource, "local", "Asia/Shanghai");
        BossQueryRepository bossQueryRepository = new BossQueryRepository(dataSource);
        contactStateService = new ContactStateService(contactStateRepository, followupPlanRepository,
                manualActionRepository, bossQueryRepository);
        followupService = new FollowupService(followupPlanRepository, contactStateService, ledgerService,
                bossQueryRepository, manualActionRepository);
    }

    // ==================== generate：只生成 SUGGESTED ====================

    @Test
    void generateCreatesOnlySuggestedForEligibleContact() {
        insertConfirmedFirstContact("attempt-1", "ENC-1", "U1", "uid:U1", tenDaysAgo());
        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张女士", "示例科技",
                tenDaysAgo());

        int created = followupService.generateSuggestions(10);

        assertEquals(1, created, "满足间隔/次数/未拦截/未回复的联系人应生成一条建议跟进");
        FollowupPlanRepository.FollowupPlan plan = findByEncryptId("ENC-1");
        assertNotNull(plan);
        assertEquals("SUGGESTED", plan.status(), "生成只能落 SUGGESTED（不发送）");
        assertEquals("FOLLOWUP", plan.planType());
        assertEquals("uid:U1", plan.contactKey());
        assertEquals(0, plan.version());
        assertEquals(1, countAttempts(), "生成建议跟进不得发送/新增投递尝试（仅保留种子尝试）");
        // 去重：再次生成不会重复
        assertEquals(0, followupService.generateSuggestions(10), "已有 active 计划不重复生成");
        assertEquals(1, countPlans());
    }

    @Test
    void generateSkipsBlockedFreshNullContactAndMaxedOutCandidates() {
        // 已回复联系人
        insertConfirmedFirstContact("attempt-blocked", "ENC-B", "UB", "uid:UB", tenDaysAgo());
        contactStateService.ensureOnConfirmedFirstContact("uid:UB", "UB", "HR-B", "公司B", tenDaysAgo());
        contactStateService.markState("uid:UB", new ContactStateService.MarkStateRequest(
                "REPLIED", null, null, "已回复", 0, null));
        // contactKey 为空的候选（findCandidateFollowupAttempts 不返回）
        insertConfirmedFirstContact("attempt-null", "ENC-N", "UN", null, tenDaysAgo());
        // 间隔不足（刚刚确认沟通；不能用固定日期，否则测试会随时间推移变成"已满 72 小时"）
        String freshContactAt = java.time.LocalDateTime.now().minusHours(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        insertConfirmedFirstContact("attempt-fresh", "ENC-F", "UF", "uid:UF", freshContactAt);
        contactStateService.ensureOnConfirmedFirstContact("uid:UF", "UF", "HR-F", "公司F", null);
        // 次数已满
        insertConfirmedFirstContact("attempt-max", "ENC-M", "UM", "uid:UM", tenDaysAgo());
        contactStateService.ensureOnConfirmedFirstContact("uid:UM", "UM", "HR-M", "公司M", tenDaysAgo());
        new DeliveryLedgerService(dataSource, "local", "Asia/Shanghai").recordFollowup("ENC-M", "UM", "岗位M");

        assertEquals(0, followupService.generateSuggestions(10),
                "已拦截/无联系人键/间隔不足/次数已满都不应生成建议跟进");
        assertEquals(0, countPlans());
    }

    // ==================== approve / reject：不发送 ====================

    @Test
    void approveOnlyChangesStatusAndNeverSends() {
        String planId = insertPlan("ENC-1", "uid:U1", "FOLLOWUP", "SUGGESTED", 0);
        int attemptsBefore = countAttempts();

        FollowupPlanRepository.FollowupPlan approved = followupService.approve(planId, 0, "同意跟进", null);

        assertEquals("APPROVED", approved.status());
        assertEquals(1, approved.version());
        assertNotNull(approved.decidedAt());
        assertEquals(attemptsBefore, countAttempts(), "批准跟进不得产生任何发送/投递尝试");
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE object_type='followup_plan' " +
                        "AND object_id=? AND action='APPROVE'", planId));

        // 批准后再次批准：状态不是 SUGGESTED → INVALID_STATE
        assertThrows(ContactValidationException.class, () -> followupService.approve(planId, 1, "重复", null));
        // 仍为 SUGGESTED 但携带过期版本 → 409 VERSION_CONFLICT
        String pendingPlan = insertPlan("ENC-2", "uid:U2", "FOLLOWUP", "SUGGESTED", 0);
        ContactVersionConflictException conflict = assertThrows(ContactVersionConflictException.class,
                () -> followupService.approve(pendingPlan, 5, "旧版本", null));
        assertEquals(0, conflict.getCurrentVersion());
    }

    @Test
    void approveIsRejectedWhenContactAlreadyBlocked() {
        insertConfirmedFirstContact("attempt-1", "ENC-1", "U1", "uid:U1", tenDaysAgo());
        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张女士", "示例科技", tenDaysAgo());
        String planId = insertPlan("ENC-1", "uid:U1", "FOLLOWUP", "SUGGESTED", 0);
        contactStateService.markState("uid:U1", new ContactStateService.MarkStateRequest(
                "REPLIED", null, null, "已回复", 0, null));

        assertThrows(ContactValidationException.class, () -> followupService.approve(planId, 0, "批准", null),
                "联系人已回复/接管/停止时批准必须被拒绝");
        assertEquals("CANCELLED", scalarString(dataSource,
                "SELECT status FROM followup_plan WHERE plan_id=?", planId),
                "状态变化后计划应已被取消");
    }

    @Test
    void rejectWorksFromSuggestedAndApproved() {
        String suggested = insertPlan("ENC-1", "uid:U1", "FOLLOWUP", "SUGGESTED", 0);
        assertEquals("REJECTED", followupService.reject(suggested, 0, "不需要", null).status());

        String approved = insertPlan("ENC-2", "uid:U2", "FOLLOWUP", "SUGGESTED", 0);
        followupService.approve(approved, 0, "先批准", null);
        FollowupPlanRepository.FollowupPlan rejected = followupService.reject(approved, 1, "撤销", null);
        assertEquals("REJECTED", rejected.status());
        assertEquals(2, rejected.version());
        assertEquals(0, countAttempts(), "拒绝不得发送任何消息");
    }

    // ==================== markExecuted / 失效 ====================

    @Test
    void markExecutedMovesApprovedToExecutedAndKeepsFailedApproved() {
        String planId = insertPlan("ENC-1", "uid:U1", "FOLLOWUP", "APPROVED", 1);
        followupService.markExecuted(planId, "attempt-followup-1");

        FollowupPlanRepository.FollowupPlan executed = followupPlanRepository.findByPlanId(planId);
        assertEquals("EXECUTED", executed.status());
        assertEquals("attempt-followup-1", executed.executedAttemptId(),
                "执行未知也要标记 EXECUTED（避免重复打扰），并记录 attemptId");
        assertNotNull(executed.executedAt());

        // 失败保留 APPROVED（不调用 markExecuted）——下面验证 APPROVED 仍是 active
        String failedPlan = insertPlan("ENC-2", "uid:U2", "FOLLOWUP", "APPROVED", 1);
        assertEquals("APPROVED", followupPlanRepository.findByPlanId(failedPlan).status());
        assertNotNull(followupService.findApproved("uid:U2", "ENC-2", FollowupService.PLAN_FOLLOWUP),
                "跟进失败时保留 APPROVED 以便下一轮重试");
        assertNull(followupService.findApproved("uid:U1", "ENC-1", FollowupService.PLAN_FOLLOWUP),
                "已执行计划不再作为 active 返回");

        String suggested = insertPlan("ENC-3", "uid:U3", "FOLLOWUP", "SUGGESTED", 0);
        followupService.markExecuted(suggested, "attempt-x");
        assertEquals("SUGGESTED", followupPlanRepository.findByPlanId(suggested).status(),
                "未批准/未执行计划不应被 markExecuted 改写");
    }

    @Test
    void contactStateChangeCancelsApprovedPlan() {
        insertConfirmedFirstContact("attempt-1", "ENC-1", "U1", "uid:U1", tenDaysAgo());
        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张女士", "示例科技", tenDaysAgo());
        String planId = insertPlan("ENC-1", "uid:U1", "FOLLOWUP", "APPROVED", 1);

        contactStateService.markState("uid:U1", new ContactStateService.MarkStateRequest(
                "INTERVIEW", null, null, "进入面试", 0, null));

        assertEquals("CANCELLED", followupPlanRepository.findByPlanId(planId).status(),
                "联系人状态变化后已批准计划必须失效");
        assertNull(followupService.findApproved("uid:U1", "ENC-1", FollowupService.PLAN_FOLLOWUP));
    }

    // ==================== 幂等 ====================

    @Test
    void approveAndRejectAreIdempotentWithIdempotencyKey() {
        String planId = insertPlan("ENC-1", "uid:U1", "FOLLOWUP", "SUGGESTED", 0);
        followupService.approve(planId, 0, "同意", "idem-approve-1");
        FollowupPlanRepository.FollowupPlan repeated = followupService.approve(planId, 0, "重复提交", "idem-approve-1");

        assertEquals("APPROVED", repeated.status());
        assertEquals(1, repeated.version(), "幂等重复批准不得再次改状态");
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE idempotency_key='idem-approve-1'"),
                "幂等键只能产生一条审计");

        String rejectPlan = insertPlan("ENC-2", "uid:U2", "FOLLOWUP", "SUGGESTED", 0);
        followupService.reject(rejectPlan, 0, "拒绝", "idem-reject-1");
        FollowupPlanRepository.FollowupPlan rejectRepeated = followupService.reject(
                rejectPlan, 0, "重复拒绝", "idem-reject-1");
        assertEquals("REJECTED", rejectRepeated.status());
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE idempotency_key='idem-reject-1'"));
    }

    // ==================== 工具 ====================

    private int countAttempts() {
        return scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt");
    }

    private long countPlans() {
        return scalar(dataSource, "SELECT COUNT(*) FROM followup_plan");
    }

    private String insertPlan(String encryptId, String contactKey, String planType,
                              String status, int version) {
        String planId = UUID.randomUUID().toString();
        execute(dataSource, "INSERT INTO followup_plan(plan_id, account, platform, encrypt_id, contact_key, " +
                        "job_name, company_name, plan_type, status, version, suggested_at, created_at, updated_at) " +
                        "VALUES(?, 'local', 'boss', ?, ?, 'Java 开发', '示例科技', ?, ?, ?, " +
                        "'2026-11-01 10:00:00', '2026-11-01 10:00:00', '2026-11-01 10:00:00')",
                planId, encryptId, contactKey, planType, status, version);
        return planId;
    }

    private FollowupPlanRepository.FollowupPlan findByEncryptId(String encryptId) {
        return followupPlanRepository.list(null, 1, 20).items().stream()
                .filter(plan -> encryptId.equals(plan.encryptId()))
                .findFirst().orElse(null);
    }

    private void insertConfirmedFirstContact(String attemptId, String encryptId, String encryptUserId,
                                             String contactKey, String startedAt) {
        execute(dataSource, "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, encrypt_id, " +
                        "encrypt_user_id, contact_key, text_status, image_status, started_at, created_at) " +
                        "VALUES(?, 'run-1', 'local', 'FIRST_CONTACT', ?, ?, ?, 'CONFIRMED', 'SKIPPED', ?, ?)",
                attemptId, encryptId, encryptUserId, contactKey, startedAt, startedAt);
    }

    private static String tenDaysAgo() {
        return java.time.LocalDateTime.now().minusDays(10)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @SuppressWarnings("unused")
    private static List<String> noop() {
        return List.of();
    }
}
