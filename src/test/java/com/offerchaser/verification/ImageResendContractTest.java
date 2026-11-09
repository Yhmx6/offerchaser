package com.offerchaser.verification;

import com.offerchaser.application.contacts.ContactNotFoundException;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.ContactValidationException;
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
import java.util.UUID;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * B3 补发图片计划契约验收（第一版只生成 SUGGESTED，不自动发送）：
 * 条件校验（文字未确认/图片不可补发/无联系人键/已有确认图片/已有 active 计划 → 400）、
 * parent_attempt_id 引用、同一图片重复保护、批准不触发发送。
 */
class ImageResendContractTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private FollowupPlanRepository followupPlanRepository;
    private ContactStateService contactStateService;
    private FollowupService followupService;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        followupPlanRepository = new FollowupPlanRepository(dataSource);
        ManualActionRepository manualActionRepository = new ManualActionRepository(dataSource);
        ContactStateRepository contactStateRepository = new ContactStateRepository(dataSource);
        BossQueryRepository bossQueryRepository = new BossQueryRepository(dataSource);
        DeliveryLedgerService ledgerService = new DeliveryLedgerService(dataSource, "local", "Asia/Shanghai");
        contactStateService = new ContactStateService(contactStateRepository, followupPlanRepository,
                manualActionRepository, bossQueryRepository);
        followupService = new FollowupService(followupPlanRepository, contactStateService, ledgerService,
                bossQueryRepository, manualActionRepository);
    }

    @Test
    void onlyConfirmedTextAndRetryableImageCanResend() {
        insertAttempt("attempt-pending", "ENC-P", "U1", "uid:U1", "PENDING", "FAILED");
        insertAttempt("attempt-confirmed-image", "ENC-C", "U2", "uid:U2", "CONFIRMED", "CONFIRMED");
        insertAttempt("attempt-no-image", "ENC-N", "U3", "uid:U3", "CONFIRMED", "NOT_ATTEMPTED");
        insertAttempt("attempt-skipped-image", "ENC-S", "U4", "uid:U4", "CONFIRMED", "SKIPPED");
        insertAttempt("attempt-null-contact", "ENC-X", null, null, "CONFIRMED", "FAILED");

        assertThrows(ContactValidationException.class,
                () -> followupService.createImageResendPlan("attempt-pending", "补发"),
                "文字未确认不能补发图片");
        assertThrows(ContactValidationException.class,
                () -> followupService.createImageResendPlan("attempt-confirmed-image", "补发"),
                "图片已确认成功不能重复补发");
        assertThrows(ContactValidationException.class,
                () -> followupService.createImageResendPlan("attempt-no-image", "补发"),
                "无图片步骤/未尝试不能补发");
        assertThrows(ContactValidationException.class,
                () -> followupService.createImageResendPlan("attempt-skipped-image", "补发"));
        assertThrows(ContactValidationException.class,
                () -> followupService.createImageResendPlan("attempt-null-contact", "补发"),
                "没有可信联系人键不能生成补发计划");
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM followup_plan"));
    }

    @Test
    void missingAttemptAndBlankAttemptIdAreRejected() {
        assertThrows(ContactValidationException.class,
                () -> followupService.createImageResendPlan(" ", "补发"));
        assertThrows(ContactNotFoundException.class,
                () -> followupService.createImageResendPlan("attempt-not-exist", "补发"));
    }

    @Test
    void successfulPlanIsSuggestedWithParentAttemptAndNoSend() {
        insertAttempt("attempt-1", "ENC-1", "U1", "uid:U1", "CONFIRMED", "FAILED");
        int attemptsBefore = (int) scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt");

        FollowupPlanRepository.FollowupPlan plan =
                followupService.createImageResendPlan("attempt-1", "图片发送失败，需补发");

        assertEquals("SUGGESTED", plan.status());
        assertEquals("IMAGE_RESEND", plan.planType());
        assertEquals("attempt-1", plan.parentAttemptId(), "补发计划必须引用原 attempt");
        assertEquals("uid:U1", plan.contactKey());
        assertEquals("ENC-1", plan.encryptId());
        assertNotNull(plan.suggestedAt());
        assertEquals(attemptsBefore, scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt"),
                "生成补发计划不得发送/新增投递尝试");
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE object_type='followup_plan' " +
                        "AND object_id=? AND action='CREATE_IMAGE_RESEND'", plan.planId()));

        // 已有 active（SUGGESTED/APPROVED）补发计划 → 不再重复创建
        assertThrows(ContactValidationException.class,
                () -> followupService.createImageResendPlan("attempt-1", "重复补发"));
        followupService.approve(plan.planId(), 0, "同意补发", null);
        assertThrows(ContactValidationException.class,
                () -> followupService.createImageResendPlan("attempt-1", "APPROVED 后仍重复"),
                "已批准但未执行的补发计划仍属 active，不能重复创建");
    }

    @Test
    void confirmedImageForSameContactJobBlocksNewResendPlan() {
        // 同一联系人同一岗位已有一次确认成功的图片（另一次尝试）
        insertAttempt("attempt-old", "ENC-1", "U1", "uid:U1", "CONFIRMED", "CONFIRMED");
        // 本次尝试图片失败，但该岗位已成功过图片 → 重复保护
        insertAttempt("attempt-new", "ENC-1", "U1", "uid:U1", "CONFIRMED", "FAILED");

        assertThrows(ContactValidationException.class,
                () -> followupService.createImageResendPlan("attempt-new", "补发"),
                "该联系人该岗位已有确认成功的图片，不能重复发送");
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM followup_plan"));
    }

    @Test
    void approvingResendPlanNeverSendsAnything() {
        insertAttempt("attempt-1", "ENC-1", "U1", "uid:U1", "CONFIRMED", "UNKNOWN");
        FollowupPlanRepository.FollowupPlan plan =
                followupService.createImageResendPlan("attempt-1", "图片结果未知，计划补发");
        int attemptsBefore = (int) scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt");
        int eventsBefore = (int) scalar(dataSource, "SELECT COUNT(*) FROM delivery_event");

        FollowupPlanRepository.FollowupPlan approved = followupService.approve(plan.planId(), 0, "同意", null);

        assertEquals("APPROVED", approved.status());
        assertEquals(attemptsBefore, scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt"),
                "批准补发计划不得产生发送尝试（第一版不自动执行补发）");
        assertEquals(eventsBefore, scalar(dataSource, "SELECT COUNT(*) FROM delivery_event"),
                "批准补发计划不得产生发送事件");
    }

    // ==================== 工具 ====================

    private void insertAttempt(String attemptId, String encryptId, String encryptUserId,
                               String contactKey, String textStatus, String imageStatus) {
        execute(dataSource, "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, encrypt_id, " +
                        "encrypt_user_id, contact_key, text_status, image_status, job_name, company_name, " +
                        "started_at, created_at) " +
                        "VALUES(?, 'run-1', 'local', 'FIRST_CONTACT', ?, ?, ?, ?, ?, 'Java 开发', '示例科技', " +
                        "'2026-11-01 10:00:00', '2026-11-01 10:00:00')",
                attemptId, encryptId, encryptUserId, contactKey, textStatus, imageStatus);
    }

    @SuppressWarnings("unused")
    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
