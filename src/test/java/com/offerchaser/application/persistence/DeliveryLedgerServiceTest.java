package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static com.offerchaser.application.persistence.PersistenceTestSupport.scalar;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-05 投递台账测试：尝试创建（与逐笔预留同事务关联）、文字/图片独立确认、事件追加、
 * 去重查询、跟进记录、今日汇总，以及原子结算 {@code settleAttempt}。
 */
class DeliveryLedgerServiceTest {

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource;
    private DeliveryLedgerService ledger;
    private BudgetService budget;

    @BeforeEach
    void setUp() {
        dataSource = PersistenceTestSupport.migratedDataSource(directory);
        ledger = new DeliveryLedgerService(dataSource, PersistenceTestSupport.ACCOUNT,
                PersistenceTestSupport.TIMEZONE);
        budget = new BudgetService(dataSource, true, 5, 5,
                PersistenceTestSupport.TIMEZONE, PersistenceTestSupport.ACCOUNT);
    }

    private BudgetService.Reservation reserve() {
        return budget.reserveFirstContact("run-1", "job-1", "岗位1");
    }

    private DeliveryLedgerService.SettlementRequest settlementRequest(
            long attemptRowId, TextSendStatus textStatus, ImageSendStatus imageStatus,
            String reservationId, DeliveryLedgerService.BudgetSettlement budgetAction) {
        return new DeliveryLedgerService.SettlementRequest(
                attemptRowId, textStatus,
                "DOM_TEXT_MESSAGE", "本人新消息出现", null,
                imageStatus, "DOM_IMAGE_MESSAGE", "图片消息出现",
                reservationId, budgetAction, null, "投递完成");
    }

    @Test
    void beginAttemptPersistsPendingRecordAndCreatedEvent() {
        BudgetService.Reservation reservation = reserve();
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "Java开发", "某公司",
                "https://example.com/job-1", "snapshot-hash", "message-hash", "resume-hash",
                reservation.reservationId());

        assertTrue(attempt.rowId() > 0);
        assertNotNull(attempt.attemptId());
        assertEquals("PENDING", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("NOT_ATTEMPTED", scalarString(dataSource,
                "SELECT image_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("FIRST_CONTACT", scalarString(dataSource,
                "SELECT kind FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals(PersistenceTestSupport.ACCOUNT, scalarString(dataSource,
                "SELECT account FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        // budget_id 保存逐笔预留行 id，并回填 reservation.attempt_id
        assertEquals(scalar(dataSource,
                        "SELECT id FROM budget_reservation WHERE reservation_id = ?",
                        reservation.reservationId()),
                scalar(dataSource, "SELECT budget_id FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals(attempt.attemptId(), scalarString(dataSource,
                "SELECT attempt_id FROM budget_reservation WHERE reservation_id = ?",
                reservation.reservationId()));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'ATTEMPT_CREATED'",
                attempt.attemptId()));
    }

    @Test
    void beginAttemptRejectsMissingReservationWithoutWritingAttempt() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, "missing-reservation-id"));
        assertTrue(error.getMessage().contains("预算预留不存在"));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt"));
    }

    @Test
    void legacyBeginAttemptBackfillsReservationAttemptId() {
        BudgetService.Reservation reservation = reserve();
        @SuppressWarnings("deprecation")
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, reservation.id());
        assertEquals(attempt.attemptId(), scalarString(dataSource,
                "SELECT attempt_id FROM budget_reservation WHERE reservation_id = ?",
                reservation.reservationId()));
    }

    @Test
    void settleAttemptConfirmsTextImageAndBudgetAtomically() {
        BudgetService.Reservation reservation = reserve();
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, reservation.reservationId());

        boolean settled = ledger.settleAttempt(settlementRequest(attempt.rowId(),
                TextSendStatus.CONFIRMED, ImageSendStatus.CONFIRMED, reservation.reservationId(),
                DeliveryLedgerService.BudgetSettlement.CONFIRM));

        assertTrue(settled);
        assertEquals("CONFIRMED", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("CONFIRMED", scalarString(dataSource,
                "SELECT image_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertNotNull(scalarString(dataSource,
                "SELECT finished_at FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("CONFIRMED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertEquals(1, scalar(dataSource, "SELECT consumed FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));

        // 事件使用 attempt 的字符串 attemptId，类型符合契约
        for (String eventType : new String[]{"TEXT_CONFIRMED", "IMAGE_CONFIRMED", "BUDGET_CONFIRMED"}) {
            assertEquals(1, scalar(dataSource,
                            "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = ?",
                            attempt.attemptId(), eventType),
                    "缺少事件：" + eventType);
        }

        // 重试幂等：不重复写事件、不重复占用额度
        assertTrue(ledger.settleAttempt(settlementRequest(attempt.rowId(),
                TextSendStatus.CONFIRMED, ImageSendStatus.CONFIRMED, reservation.reservationId(),
                DeliveryLedgerService.BudgetSettlement.CONFIRM)));
        assertEquals(1, scalar(dataSource, "SELECT consumed FROM daily_budget"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'TEXT_CONFIRMED'",
                attempt.attemptId()));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'BUDGET_CONFIRMED'",
                attempt.attemptId()));
    }

    @Test
    void settleAttemptReleasesReservationWhenNothingWasSent() {
        BudgetService.Reservation reservation = reserve();
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, reservation.reservationId());

        boolean settled = ledger.settleAttempt(settlementRequest(attempt.rowId(),
                TextSendStatus.NOT_ATTEMPTED, ImageSendStatus.NOT_ATTEMPTED, reservation.reservationId(),
                DeliveryLedgerService.BudgetSettlement.RELEASE));

        assertTrue(settled);
        assertEquals("NOT_ATTEMPTED", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertNotNull(scalarString(dataSource,
                "SELECT finished_at FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("RELEASED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT consumed FROM daily_budget"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'JOB_SKIPPED'",
                attempt.attemptId()));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'BUDGET_RELEASED'",
                attempt.attemptId()));
    }

    @Test
    void settleAttemptUnknownKeepsBudgetConservatively() {
        BudgetService.Reservation reservation = reserve();
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, reservation.reservationId());

        assertTrue(ledger.settleAttempt(new DeliveryLedgerService.SettlementRequest(
                attempt.rowId(), TextSendStatus.UNKNOWN, "TIMEOUT", "超时未确认", "TEXT_SEND_UNCONFIRMED",
                ImageSendStatus.UNKNOWN, "TIMEOUT", "图片超时", reservation.reservationId(),
                DeliveryLedgerService.BudgetSettlement.UNKNOWN, "TEXT_SEND_UNCONFIRMED", "等待确认超时")));

        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertEquals(1, scalar(dataSource, "SELECT uncertain FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'BUDGET_UNCERTAIN'",
                attempt.attemptId()));
        assertEquals("TEXT_SEND_UNCONFIRMED", scalarString(dataSource,
                "SELECT error_code FROM delivery_attempt WHERE id = ?", attempt.rowId()));
    }

    @Test
    void settleAttemptWithoutBudgetWritesStatusAndNoBudgetEvents() {
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, (String) null);

        assertTrue(ledger.settleAttempt(new DeliveryLedgerService.SettlementRequest(
                attempt.rowId(), TextSendStatus.CONFIRMED, "DOM", "已确认", null,
                ImageSendStatus.SKIPPED, "CONFIG", "未配置图片简历", null,
                DeliveryLedgerService.BudgetSettlement.NONE, null, "投递完成")));

        assertEquals("CONFIRMED", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type LIKE 'BUDGET_%'",
                attempt.attemptId()));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'IMAGE_SKIPPED'",
                attempt.attemptId()));
    }

    @Test
    void settleAttemptRejectsPendingStatusAndMissingReservation() {
        BudgetService.Reservation reservation = reserve();
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, reservation.reservationId());

        assertFalse(ledger.settleAttempt(settlementRequest(attempt.rowId(),
                TextSendStatus.PENDING, ImageSendStatus.NOT_ATTEMPTED, reservation.reservationId(),
                DeliveryLedgerService.BudgetSettlement.CONFIRM)));
        assertEquals("PENDING", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));

        assertFalse(ledger.settleAttempt(settlementRequest(attempt.rowId(),
                TextSendStatus.CONFIRMED, ImageSendStatus.NOT_ATTEMPTED, "missing-reservation",
                DeliveryLedgerService.BudgetSettlement.CONFIRM)));
        assertEquals("PENDING", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()),
                "预算预留不存在时也必须整体回滚");
        assertEquals("RESERVED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
    }

    @Test
    void settleAttemptDoesNotDuplicateImageEventsAlreadyWrittenDuringSending() {
        BudgetService.Reservation reservation = reserve();
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, reservation.reservationId());
        // 发送阶段（settle 之前）已经写过 IMAGE_SKIPPED，结算时不应重复写
        ledger.appendEvent(attempt.rowId(), "run-1", "IMAGE_SKIPPED", "CONFIG", "未配置图片简历");

        assertTrue(ledger.settleAttempt(settlementRequest(attempt.rowId(),
                TextSendStatus.CONFIRMED, ImageSendStatus.SKIPPED, reservation.reservationId(),
                DeliveryLedgerService.BudgetSettlement.CONFIRM)));

        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'IMAGE_SKIPPED'",
                attempt.attemptId()));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'TEXT_CONFIRMED'",
                attempt.attemptId()));
    }

    @Test
    void confirmedTextIsVisibleToDedupeAndFollowupQueries() {
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "Java开发", "某公司",
                "https://example.com/job-1", null, null, null, (String) null);

        assertFalse(ledger.hasConfirmedFirstContact("job-1"));
        ledger.markTextSent(attempt.rowId(), TextSendStatus.CONFIRMED, "DOM", "新消息包含话术", null);

        assertEquals("CONFIRMED", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertNotNull(scalarString(dataSource,
                "SELECT finished_at FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertTrue(scalarString(dataSource,
                "SELECT text_evidence FROM delivery_attempt WHERE id = ?", attempt.rowId()).contains("DOM"));
        assertTrue(ledger.hasConfirmedFirstContact("job-1"));
        assertFalse(ledger.hasConfirmedFirstContact("job-unknown"));
        assertEquals(1, ledger.confirmedFirstContactCountToday());

        LocalDateTime confirmedAt = ledger.lastConfirmedFirstContactAt("job-1");
        assertNotNull(confirmedAt);
        assertTrue(confirmedAt.isAfter(LocalDateTime.now().minusMinutes(5)));
        assertNull(ledger.lastConfirmedFirstContactAt("job-unknown"));
        assertNull(ledger.lastConfirmedFirstContactAt(null));
    }

    @Test
    void failedAndUnknownStatusesAreAuditedButNeverCountAsDelivered() {
        DeliveryLedgerService.AttemptStarted failed = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, (String) null);
        DeliveryLedgerService.AttemptStarted unknown = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-2", "user-2", "岗位2", "公司2",
                null, null, null, null, (String) null);

        ledger.markTextSent(failed.rowId(), TextSendStatus.FAILED, "DOM", "出现发送失败标记", "SEND_FAILED");
        ledger.markTextSent(unknown.rowId(), TextSendStatus.UNKNOWN, "TIMEOUT", "超时未确认", null);

        assertEquals("FAILED", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", failed.rowId()));
        assertEquals("SEND_FAILED", scalarString(dataSource,
                "SELECT error_code FROM delivery_attempt WHERE id = ?", failed.rowId()));
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", unknown.rowId()));
        assertFalse(ledger.hasConfirmedFirstContact("job-1"));
        assertFalse(ledger.hasConfirmedFirstContact("job-2"));
        assertEquals(0, ledger.confirmedFirstContactCountToday());
    }

    @Test
    void imageStatusIsIndependentFromTextStatus() {
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, (String) null);

        ledger.markTextSent(attempt.rowId(), TextSendStatus.CONFIRMED, "DOM", "文字已确认", null);
        ledger.markImageSent(attempt.rowId(), ImageSendStatus.UNKNOWN, "TIMEOUT", "图片超时未确认");

        assertEquals("CONFIRMED", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()),
                "图片结果不应影响文字状态");
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT image_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertTrue(scalarString(dataSource,
                "SELECT image_evidence FROM delivery_attempt WHERE id = ?", attempt.rowId()).contains("TIMEOUT"));
    }

    @Test
    void appendEventResolvesAttemptIdAndAllowsRunLevelEvents() {
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, (String) null);

        ledger.appendEvent(attempt.rowId(), "run-1", "TEXT_SEND_STARTED", "DOM", "点击发送按钮");
        ledger.appendEvent(0L, "run-1", "TASK_STARTED", null, "任务开始");

        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'TEXT_SEND_STARTED'",
                attempt.attemptId()));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id IS NULL AND event_type = 'TASK_STARTED'"));
    }

    @Test
    void finishAttemptWritesEndTimeAndError() {
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, (String) null);

        ledger.finishAttempt(attempt.rowId(), "SEND_TIMEOUT", "等待确认超时");

        assertNotNull(scalarString(dataSource,
                "SELECT finished_at FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("SEND_TIMEOUT", scalarString(dataSource,
                "SELECT error_code FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("等待确认超时", scalarString(dataSource,
                "SELECT error_detail FROM delivery_attempt WHERE id = ?", attempt.rowId()));
    }

    @Test
    void followupRecordsAccumulatePerAccountAndJob() {
        assertEquals(0, ledger.followupCount("job-1"));
        assertNull(ledger.lastFollowupAt("job-1"));

        ledger.recordFollowup("job-1", "user-1", "岗位1");
        ledger.recordFollowup("job-1", "user-1", "岗位1");

        assertEquals(2, ledger.followupCount("job-1"));
        assertNotNull(ledger.lastFollowupAt("job-1"));
        assertEquals(0, ledger.followupCount("job-unknown"));
        assertNull(ledger.lastFollowupAt("job-unknown"));
    }

    @Test
    void todaySummaryCountsTextImageAndKindStatuses() {
        DeliveryLedgerService.AttemptStarted firstContact = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, (String) null);
        ledger.markTextSent(firstContact.rowId(), TextSendStatus.CONFIRMED, "DOM", "已确认", null);
        ledger.markImageSent(firstContact.rowId(), ImageSendStatus.CONFIRMED, "DOM", "图片已确认");

        DeliveryLedgerService.AttemptStarted followup = ledger.beginAttempt(
                "run-1", DeliveryKind.FOLLOWUP, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, (String) null);
        ledger.markTextSent(followup.rowId(), TextSendStatus.FAILED, "DOM", "发送失败", "SEND_FAILED");
        ledger.markImageSent(followup.rowId(), ImageSendStatus.SKIPPED, "CONFIG", "配置关闭图片");

        Map<String, Object> summary = ledger.todaySummary();
        assertEquals(PersistenceTestSupport.ACCOUNT, summary.get("account"));
        assertEquals(2, summary.get("attempts"));
        assertEquals(1, summary.get("textConfirmed"));
        assertEquals(1, summary.get("textFailed"));
        assertEquals(0, summary.get("textUnknown"));
        assertEquals(1, summary.get("imageConfirmed"));
        assertEquals(0, summary.get("imageFailed"));
        assertEquals(1, summary.get("firstContactConfirmed"));
        assertEquals(0, summary.get("followupConfirmed"));
        assertNotNull(summary.get("date"));
    }

    @Test
    void beginAttemptWithB3FieldsPersistsContactKeyAndParentAttemptId() {
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FOLLOWUP, "enc-1", "user-1", "岗位1", "公司1",
                null, null, null, null, (String) null, "uid:user-1", "attempt-origin");

        assertEquals("uid:user-1", scalarString(dataSource,
                "SELECT contact_key FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("attempt-origin", scalarString(dataSource,
                "SELECT parent_attempt_id FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("FOLLOWUP", scalarString(dataSource,
                "SELECT kind FROM delivery_attempt WHERE id = ?", attempt.rowId()));

        // 旧 11 参重载不写联系人字段（保持向后兼容）
        DeliveryLedgerService.AttemptStarted legacy = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "enc-2", "user-2", "岗位2", "公司2",
                null, null, null, null, (String) null);
        assertNull(scalarString(dataSource,
                "SELECT contact_key FROM delivery_attempt WHERE id = ?", legacy.rowId()));
        assertNull(scalarString(dataSource,
                "SELECT parent_attempt_id FROM delivery_attempt WHERE id = ?", legacy.rowId()));
    }
}
