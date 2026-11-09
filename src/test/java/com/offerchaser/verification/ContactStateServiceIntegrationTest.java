package com.offerchaser.verification;

import com.offerchaser.application.contacts.ContactNotFoundException;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.ContactValidationException;
import com.offerchaser.application.contacts.ContactVersionConflictException;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.ContactStateRepository;
import com.offerchaser.application.persistence.FollowupPlanRepository;
import com.offerchaser.application.persistence.ManualActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarLong;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 联系人状态真实 SQLite 集成验收：
 * 创建/回写、版本冲突、幂等键、回复/接管/停止使自动沟通失效、状态变化取消未执行计划并审计、
 * touchLastContact 不 bump version、跨岗位去重与图片已确认查询。
 */
class ContactStateServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private ContactStateRepository contactStateRepository;
    private FollowupPlanRepository followupPlanRepository;
    private ManualActionRepository manualActionRepository;
    private ContactStateService contactStateService;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        contactStateRepository = new ContactStateRepository(dataSource);
        followupPlanRepository = new FollowupPlanRepository(dataSource);
        manualActionRepository = new ManualActionRepository(dataSource);
        BossQueryRepository bossQueryRepository = new BossQueryRepository(dataSource);
        contactStateService = new ContactStateService(contactStateRepository,
                followupPlanRepository, manualActionRepository, bossQueryRepository);
    }

    // ==================== 创建 / 回写 ====================

    @Test
    void contactKeyNeedsTrustedEncryptUserId() {
        assertEquals("uid:U1", ContactStateService.contactKey(" U1 "));
        assertNull(ContactStateService.contactKey(null), "无可信 HR ID 不应生成联系人键");
        assertNull(ContactStateService.contactKey("  "));
    }

    @Test
    void confirmedFirstContactCreatesContactAndTouchDoesNotBumpVersion() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张女士", "示例科技",
                "2026-11-04 10:00:00");

        ContactStateRepository.ContactState created =
                contactStateRepository.findByKey("local", "boss", "uid:U1");
        assertNotNull(created, "确认首次沟通后应创建联系人记录");
        assertEquals(0, created.version());
        assertEquals("UNKNOWN", created.replyState());
        assertEquals("2026-11-04 10:00:00", created.lastContactAt());
        assertEquals("U1", created.encryptUserId());

        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张女士", "示例科技",
                "2026-11-05 10:00:00");
        ContactStateRepository.ContactState touched =
                contactStateRepository.findByKey("local", "boss", "uid:U1");
        assertEquals(0, touched.version(), "touchLastContact 不应 bump version");
        assertEquals("2026-11-05 10:00:00", touched.lastContactAt());

        // contactKey 为空：不落表
        contactStateService.ensureOnConfirmedFirstContact(null, null, null, null, null);
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM contact_state"));
    }

    // ==================== 人工标记：版本 / 幂等 / 拦截 ====================

    @Test
    void markStateUpdatesWithVersionProtection() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张女士", "示例科技", null);

        ContactStateService.ContactStateView replied = contactStateService.markState("uid:U1",
                new ContactStateService.MarkStateRequest("REPLIED", null, null,
                        "HR 已回复", 0, null));
        assertEquals(1, replied.version(), "状态更新后 version+1");
        assertEquals("REPLIED", replied.replyState());
        assertNotNull(replied.lastReplyAt());
        assertTrue(contactStateService.isAutoContactBlocked("uid:U1"));

        // 旧版本再提交 → 冲突，携带最新版本号
        ContactVersionConflictException conflict = assertThrows(ContactVersionConflictException.class,
                () -> contactStateService.markState("uid:U1",
                        new ContactStateService.MarkStateRequest("REJECTED", null, null,
                                "试图用旧版本覆盖", 0, null)));
        assertEquals(1, conflict.getCurrentVersion());
        assertEquals("REPLIED", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key='uid:U1'"),
                "版本冲突不得写入");
    }

    @Test
    void markStateIsIdempotentWithIdempotencyKey() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张女士", "示例科技", null);
        contactStateService.markState("uid:U1", new ContactStateService.MarkStateRequest(
                "REPLIED", null, null, "第一次标记", 0, "idem-contact-1"));

        // 用同一个幂等键重复提交（内容不同且版本已过期）：应返回当前状态而不是报错或再次修改
        ContactStateService.ContactStateView repeated = contactStateService.markState("uid:U1",
                new ContactStateService.MarkStateRequest("STOP", true, true,
                        "重复提交", 0, "idem-contact-1"));
        assertEquals(1, repeated.version());
        assertEquals("REPLIED", repeated.replyState(), "幂等重复提交不得应用新状态");
        assertFalse(repeated.stopContact());

        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE object_type='contact_state' " +
                        "AND object_id='uid:U1' AND idempotency_key='idem-contact-1'"),
                "重复提交只能产生一条审计");
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE object_type='contact_state'"));
    }

    @Test
    void blockedStatesForbidAutoContact() {
        record Case(String key, ContactStateService.MarkStateRequest request, boolean blocked) {
        }
        List<Case> cases = List.of(
                new Case("uid:UNKNOWN", new ContactStateService.MarkStateRequest("UNKNOWN", null, null,
                        "保持未知", 0, null), false),
                new Case("uid:REPLIED", new ContactStateService.MarkStateRequest("REPLIED", null, null,
                        "已回复", 0, null), true),
                new Case("uid:REJECTED", new ContactStateService.MarkStateRequest("REJECTED", null, null,
                        "明确拒绝", 0, null), true),
                new Case("uid:INTERVIEW", new ContactStateService.MarkStateRequest("INTERVIEW", null, null,
                        "已面试", 0, null), true),
                new Case("uid:TAKEOVER", new ContactStateService.MarkStateRequest(null, true, null,
                        "人工接管", 0, null), true),
                new Case("uid:STOP", new ContactStateService.MarkStateRequest(null, null, true,
                        "停止联系", 0, null), true),
                new Case("uid:NO_REPLY", new ContactStateService.MarkStateRequest("NO_REPLY", null, null,
                        "暂无回复", 0, null), false));
        for (Case testCase : cases) {
            contactStateService.ensureOnConfirmedFirstContact(testCase.key(), "U-" + testCase.key(),
                    "HR", "公司", null);
            contactStateService.markState(testCase.key(), testCase.request());
            assertEquals(testCase.blocked(), contactStateService.isAutoContactBlocked(testCase.key()),
                    "联系人状态 " + testCase.key() + " 的自动沟通拦截语义不符");
        }
        assertFalse(contactStateService.isAutoContactBlocked(null));
        assertFalse(contactStateService.isAutoContactBlocked("uid:NOT-EXIST"));
    }

    @Test
    void stateChangeCancelsActivePlansAndWritesAudit() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张女士", "示例科技", null);
        insertPlan("plan-suggested", "uid:U1", "ENC-1", "FOLLOWUP", "SUGGESTED");
        insertPlan("plan-approved", "uid:U1", "ENC-1", "FOLLOWUP", "APPROVED");
        insertPlan("plan-rejected", "uid:U1", "ENC-1", "FOLLOWUP", "REJECTED");

        contactStateService.markState("uid:U1", new ContactStateService.MarkStateRequest(
                "REPLIED", null, null, "HR 已回复，撤销跟进", 0, null));

        assertEquals("CANCELLED", scalarString(dataSource,
                "SELECT status FROM followup_plan WHERE plan_id='plan-suggested'"),
                "状态变化必须取消 SUGGESTED 计划");
        assertEquals("CANCELLED", scalarString(dataSource,
                "SELECT status FROM followup_plan WHERE plan_id='plan-approved'"),
                "状态变化必须让已批准计划失效（CANCELLED）");
        assertEquals("REJECTED", scalarString(dataSource,
                "SELECT status FROM followup_plan WHERE plan_id='plan-rejected'"),
                "已拒绝计划不应被改写");
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE object_type='contact_state' " +
                        "AND object_id='uid:U1' AND action='MARK_STATE'"),
                "人工状态变更必须写审计");
        assertTrue(scalarString(dataSource,
                "SELECT after_state FROM manual_action WHERE object_type='contact_state'")
                .contains("REPLIED"));
    }

    @Test
    void invalidMarkStateRequestsAreRejected() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张女士", "示例科技", null);

        assertThrows(ContactValidationException.class, () -> contactStateService.markState(null,
                new ContactStateService.MarkStateRequest("REPLIED", null, null, "reason", 0, null)));
        assertThrows(ContactValidationException.class, () -> contactStateService.markState("uid:U1",
                null));
        assertThrows(ContactValidationException.class, () -> contactStateService.markState("uid:U1",
                new ContactStateService.MarkStateRequest("REPLIED", null, null, " ", 0, null)),
                "人工操作必须留下原因");
        assertThrows(ContactValidationException.class, () -> contactStateService.markState("uid:U1",
                new ContactStateService.MarkStateRequest("WRONG", null, null, "非法状态", 0, null)));
        assertEquals(0, scalar(dataSource, "SELECT version FROM contact_state WHERE contact_key='uid:U1'"));
    }

    @Test
    void detailIncludesContactAndAttemptsAndNotFoundIsReported() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张女士", "示例科技",
                "2026-11-04 10:00:00");
        insertAttempt("attempt-1", "ENC-1", "uid:U1", "CONFIRMED", "CONFIRMED");
        insertAttempt("attempt-2", "ENC-2", "uid:U1", "CONFIRMED", "FAILED");

        Map<String, Object> detail = contactStateService.detail("uid:U1");
        assertNotNull(detail.get("contact"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) detail.get("attempts");
        assertEquals(2, attempts.size(), "联系人详情应包含该联系人投递尝试");
        assertEquals("uid:U1", ((ContactStateService.ContactStateView) detail.get("contact")).contactKey());

        contactStateService.ensureOnConfirmedFirstContact("uid:U2", "U2", "李先生", "另一公司", null);
        assertEquals(1, contactStateService.list(null, null, null, 1, 20).items().stream()
                .filter(state -> "uid:U1".equals(state.contactKey())).count());
        assertTrue(contactStateService.list("UNKNOWN", null, null, 1, 20).total() >= 1);
        assertThrows(ContactNotFoundException.class, () -> contactStateService.getOrThrow("uid:NOT-EXIST"));
        assertNull(contactStateService.getOrNull("uid:NOT-EXIST"));
    }

    // ==================== 跨岗位去重 / 图片确认 ====================

    @Test
    void crossJobDedupeOnlyCountsOtherJobsWithinWindow() {
        // 10 天前与 ENC-A 的确认首次沟通
        insertAttempt("attempt-other", "ENC-A", "uid:U1", "CONFIRMED", "SKIPPED", "2026-10-25 10:00:00");
        assertTrue(contactStateService.hasOtherJobConfirmedFirstContact("uid:U1", "ENC-B", 30),
                "同 HR 在 30 天内其他岗位确认过 → 应去重");
        assertFalse(contactStateService.hasOtherJobConfirmedFirstContact("uid:U1", "ENC-A", 30),
                "同岗位确认不应跨岗位去重");
        assertFalse(contactStateService.hasOtherJobConfirmedFirstContact("uid:U1", "ENC-B", 1),
                "超出天数窗口不应去重");
        assertFalse(contactStateService.hasOtherJobConfirmedFirstContact(null, "ENC-B", 30),
                "contactKey 为空不合并");
        assertFalse(contactStateService.hasOtherJobConfirmedFirstContact("uid:NOT-EXIST", "ENC-B", 30));
    }

    @Test
    void confirmedImageIsDetectedPerContactAndJob() {
        insertAttempt("attempt-img", "ENC-1", "uid:U1", "CONFIRMED", "CONFIRMED");
        assertTrue(contactStateService.hasConfirmedImage("uid:U1", "ENC-1"));
        assertFalse(contactStateService.hasConfirmedImage("uid:U1", "ENC-2"),
                "图片确认应按岗位隔离");
        assertFalse(contactStateService.hasConfirmedImage("uid:U2", "ENC-1"));
        assertFalse(contactStateService.hasConfirmedImage(null, "ENC-1"));
        assertFalse(contactStateService.hasConfirmedImage("uid:U1", null));
    }

    // ==================== 工具 ====================

    private void insertPlan(String planId, String contactKey, String encryptId,
                            String planType, String status) {
        execute(dataSource, "INSERT INTO followup_plan(plan_id, account, platform, encrypt_id, contact_key, " +
                        "plan_type, status, version, suggested_at, created_at, updated_at) " +
                        "VALUES(?, 'local', 'boss', ?, ?, ?, ?, 0, '2026-11-04 09:00:00', " +
                        "'2026-11-04 09:00:00', '2026-11-04 09:00:00')",
                planId, encryptId, contactKey, planType, status);
    }

    private void insertAttempt(String attemptId, String encryptId, String contactKey,
                               String textStatus, String imageStatus) {
        insertAttempt(attemptId, encryptId, contactKey, textStatus, imageStatus, "2026-11-04 10:00:00");
    }

    private void insertAttempt(String attemptId, String encryptId, String contactKey,
                               String textStatus, String imageStatus, String startedAt) {
        execute(dataSource, "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, encrypt_id, " +
                        "encrypt_user_id, contact_key, text_status, image_status, started_at, created_at) " +
                        "VALUES(?, 'run-1', 'local', 'FIRST_CONTACT', ?, 'U1', ?, ?, ?, ?, ?)",
                attemptId, encryptId, contactKey, textStatus, imageStatus, startedAt, startedAt);
    }
}
