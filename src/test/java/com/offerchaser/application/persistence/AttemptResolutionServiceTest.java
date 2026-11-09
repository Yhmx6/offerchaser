package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static com.offerchaser.application.persistence.PersistenceTestSupport.ACCOUNT;
import static com.offerchaser.application.persistence.PersistenceTestSupport.TIMEZONE;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalar;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 人工核对服务测试：SENT/NOT_SENT/STILL_UNKNOWN 全分支、预算补偿、幂等键、版本冲突、
 * 已确认不可回退、无预留与参数校验。
 */
class AttemptResolutionServiceTest {

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource;
    private BudgetService budgetService;
    private DeliveryLedgerService ledgerService;
    private AttemptResolutionService resolutionService;

    @BeforeEach
    void setUp() {
        dataSource = PersistenceTestSupport.migratedDataSource(directory);
        budgetService = new BudgetService(dataSource, true, 5, 5, TIMEZONE, ACCOUNT);
        ledgerService = new DeliveryLedgerService(dataSource, ACCOUNT, TIMEZONE);
        resolutionService = new AttemptResolutionService(dataSource);
    }

    // ==================== 正常分支 ====================

    @Test
    void sentOnUnknownConfirmsTextAndMovesUncertainToConsumed() {
        Fixture fixture = createAttempt(TextSendStatus.UNKNOWN);
        assertEquals(1, budgetColumn("uncertain"));

        AttemptResolutionService.ResolutionResult result = resolutionService.resolve(
                fixture.attempt().attemptId(), "SENT", "人工核对确认已发送", 0, "idem-sent-1");

        assertTrue(result.changed());
        assertFalse(result.idempotent());
        assertEquals("CONFIRMED", result.textStatus());
        assertEquals(1, result.version());
        assertEquals("SENT", result.manualResolution());
        assertNotNull(result.resolvedAt());

        assertEquals("CONFIRMED", attemptTextStatus(fixture.attempt().attemptId()));
        assertEquals(1, attemptVersion(fixture.attempt().attemptId()));
        assertEquals("SENT", attemptManualResolution(fixture.attempt().attemptId()));
        assertEquals("人工核对确认已发送", scalarString(dataSource,
                "SELECT resolve_reason FROM delivery_attempt WHERE attempt_id = ?",
                fixture.attempt().attemptId()));
        assertEquals("CONFIRMED", reservationState(fixture.reservation().reservationId()));
        assertEquals(1, budgetColumn("consumed"));
        assertEquals(0, budgetColumn("uncertain"));
        assertEquals(0, budgetColumn("reserved"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE object_id = ? AND action = 'SENT' " +
                        "AND before_state = 'UNKNOWN' AND after_state = 'CONFIRMED' " +
                        "AND idempotency_key = 'idem-sent-1'",
                fixture.attempt().attemptId()));
    }

    @Test
    void sentOnPendingMovesReservedToConsumed() {
        Fixture fixture = createAttempt(TextSendStatus.PENDING);
        assertEquals(1, budgetColumn("reserved"));

        AttemptResolutionService.ResolutionResult result = resolutionService.resolve(
                fixture.attempt().attemptId(), "SENT", "人工确认已发送", 0, null);

        assertEquals("CONFIRMED", result.textStatus());
        assertEquals("CONFIRMED", reservationState(fixture.reservation().reservationId()));
        assertEquals(0, budgetColumn("reserved"));
        assertEquals(1, budgetColumn("consumed"));
    }

    @Test
    void notSentOnUnknownReleasesUncertainQuota() {
        Fixture fixture = createAttempt(TextSendStatus.UNKNOWN);

        AttemptResolutionService.ResolutionResult result = resolutionService.resolve(
                fixture.attempt().attemptId(), "NOT_SENT", "核对聊天记录确认未发送", 0, null);

        assertEquals("FAILED", result.textStatus());
        assertEquals("NOT_SENT", result.manualResolution());
        assertEquals(1, result.version());
        assertEquals("FAILED", attemptTextStatus(fixture.attempt().attemptId()));
        assertEquals("RELEASED", reservationState(fixture.reservation().reservationId()));
        assertEquals(0, budgetColumn("uncertain"));
        assertEquals(0, budgetColumn("consumed"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE object_id = ? AND action = 'NOT_SENT' " +
                        "AND before_state = 'UNKNOWN' AND after_state = 'FAILED'",
                fixture.attempt().attemptId()));
    }

    @Test
    void notSentOnPendingReleasesReservedQuota() {
        Fixture fixture = createAttempt(TextSendStatus.PENDING);

        AttemptResolutionService.ResolutionResult result = resolutionService.resolve(
                fixture.attempt().attemptId(), "NOT_SENT", "核对确认未发送", 0, null);

        assertEquals("FAILED", result.textStatus());
        assertEquals("RELEASED", reservationState(fixture.reservation().reservationId()));
        assertEquals(0, budgetColumn("reserved"));
        assertEquals(0, budgetColumn("consumed"));
    }

    @Test
    void stillUnknownKeepsTextStatusAndBudgetButBumpsVersion() {
        Fixture fixture = createAttempt(TextSendStatus.UNKNOWN);

        AttemptResolutionService.ResolutionResult result = resolutionService.resolve(
                fixture.attempt().attemptId(), "STILL_UNKNOWN", "人工核对后仍无法确认", 0, null);

        assertTrue(result.changed());
        assertEquals("UNKNOWN", result.textStatus());
        assertEquals(1, result.version());
        assertEquals("STILL_UNKNOWN", result.manualResolution());
        assertEquals("UNKNOWN", attemptTextStatus(fixture.attempt().attemptId()));
        assertEquals("UNKNOWN", reservationState(fixture.reservation().reservationId()),
                "STILL_UNKNOWN 不改预算预留状态");
        assertEquals(1, budgetColumn("uncertain"));

        // 再提交一次（expectedVersion 用最新值）→ version 继续 +1
        AttemptResolutionService.ResolutionResult second = resolutionService.resolve(
                fixture.attempt().attemptId(), "STILL_UNKNOWN", "二次核对仍未知", 1, null);
        assertEquals(2, second.version());
        assertEquals(1, budgetColumn("uncertain"));
    }

    @Test
    void sentOnAlreadyConfirmedIsIdempotentSuccess() {
        Fixture fixture = createAttempt(TextSendStatus.CONFIRMED);
        int consumedBefore = budgetColumn("consumed");

        AttemptResolutionService.ResolutionResult result = resolutionService.resolve(
                fixture.attempt().attemptId(), "SENT", "重复确认", 999, "idem-repeat");

        assertFalse(result.changed());
        assertTrue(result.idempotent());
        assertEquals("CONFIRMED", result.textStatus());
        assertEquals(0, result.version(), "已 CONFIRMED 再 SENT 不改变版本");
        assertEquals(consumedBefore, budgetColumn("consumed"), "不得重复占用额度");
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM manual_action"));
    }

    @Test
    void notSentAndStillUnknownOnConfirmedAreRejected() {
        Fixture fixture = createAttempt(TextSendStatus.CONFIRMED);

        AlreadyConfirmedException notSent = assertThrows(AlreadyConfirmedException.class,
                () -> resolutionService.resolve(fixture.attempt().attemptId(), "NOT_SENT", "试图回退", 0, null));
        assertEquals(fixture.attempt().attemptId(), notSent.getAttemptId());
        AlreadyConfirmedException stillUnknown = assertThrows(AlreadyConfirmedException.class,
                () -> resolutionService.resolve(fixture.attempt().attemptId(), "STILL_UNKNOWN", "仍未知", 0, null));
        assertEquals(fixture.attempt().attemptId(), stillUnknown.getAttemptId());
        assertEquals("CONFIRMED", attemptTextStatus(fixture.attempt().attemptId()));
        assertEquals(0, attemptVersion(fixture.attempt().attemptId()));
    }

    // ==================== 版本冲突 / 幂等 ====================

    @Test
    void versionConflictThenRetryWithLatestVersionSucceeds() {
        Fixture fixture = createAttempt(TextSendStatus.UNKNOWN);

        VersionConflictException conflict = assertThrows(VersionConflictException.class,
                () -> resolutionService.resolve(fixture.attempt().attemptId(), "SENT", "确认发送", 5, null));
        assertEquals(fixture.attempt().attemptId(), conflict.getAttemptId());
        assertEquals(0, conflict.getCurrentVersion());
        assertEquals("UNKNOWN", conflict.getCurrentStatus());
        // 冲突时不得产生任何改动
        assertEquals("UNKNOWN", attemptTextStatus(fixture.attempt().attemptId()));
        assertEquals(0, attemptVersion(fixture.attempt().attemptId()));
        assertEquals(1, budgetColumn("uncertain"));

        AttemptResolutionService.ResolutionResult result = resolutionService.resolve(
                fixture.attempt().attemptId(), "SENT", "确认发送", 0, null);
        assertEquals("CONFIRMED", result.textStatus());
        assertEquals(1, result.version());
    }

    @Test
    void idempotencyKeyReplaysFirstResultWithoutChanges() {
        Fixture fixture = createAttempt(TextSendStatus.UNKNOWN);

        AttemptResolutionService.ResolutionResult first = resolutionService.resolve(
                fixture.attempt().attemptId(), "SENT", "第一次提交", 0, "key-unique-1");
        assertTrue(first.changed());
        assertFalse(first.idempotent());

        // 同 key 重复提交（即使参数不同/expectedVersion 过期）→ 返回首次结果，不再改动
        AttemptResolutionService.ResolutionResult replay = resolutionService.resolve(
                fixture.attempt().attemptId(), "NOT_SENT", "不同的重复提交", 99, "key-unique-1");
        assertFalse(replay.changed());
        assertTrue(replay.idempotent());
        assertEquals("CONFIRMED", replay.textStatus(), "返回首次结果而不是第二次提交的内容");
        assertEquals(1, replay.version());
        assertEquals("SENT", replay.manualResolution());

        assertEquals("CONFIRMED", attemptTextStatus(fixture.attempt().attemptId()));
        assertEquals(1, attemptVersion(fixture.attempt().attemptId()));
        assertEquals(1, scalarl("SELECT COUNT(*) FROM manual_action"));
        assertEquals(1, budgetColumn("consumed"));
    }

    // ==================== 预算三种调整 ====================

    @Test
    void manualResolutionAppliesUncertainConsumedAndReservedAdjustments() {
        Fixture unknownOne = createAttempt(TextSendStatus.UNKNOWN);   // uncertain +1
        Fixture unknownTwo = createAttempt(TextSendStatus.UNKNOWN);   // uncertain +1
        Fixture pending = createAttempt(TextSendStatus.PENDING);      // reserved +1
        assertEquals(2, budgetColumn("uncertain"));
        assertEquals(1, budgetColumn("reserved"));
        assertEquals(0, budgetColumn("consumed"));

        // 1) UNKNOWN → CONFIRMED：uncertain-1 / consumed+1
        resolutionService.resolve(unknownOne.attempt().attemptId(), "SENT", "确认 A", 0, null);
        assertEquals(1, budgetColumn("uncertain"));
        assertEquals(1, budgetColumn("consumed"));

        // 2) UNKNOWN → RELEASED：uncertain-1
        resolutionService.resolve(unknownTwo.attempt().attemptId(), "NOT_SENT", "未发送 B", 0, null);
        assertEquals(0, budgetColumn("uncertain"));
        assertEquals(1, budgetColumn("consumed"));

        // 3) RESERVED → CONFIRMED：reserved-1 / consumed+1
        resolutionService.resolve(pending.attempt().attemptId(), "SENT", "确认 C", 0, null);
        assertEquals(0, budgetColumn("reserved"));
        assertEquals(2, budgetColumn("consumed"));
        assertEquals(3, scalarl("SELECT COUNT(*) FROM manual_action"));
    }

    // ==================== 无预留 / 404 / 参数校验 ====================

    @Test
    void attemptWithoutReservationDoesNotTouchBudgetAggregate() {
        DeliveryLedgerService.AttemptStarted attempt = ledgerService.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "encrypt-none", "user-1", "岗位", "公司",
                null, null, null, null, (String) null);
        ledgerService.settleAttempt(new DeliveryLedgerService.SettlementRequest(
                attempt.rowId(), TextSendStatus.UNKNOWN, "TIMEOUT", "超时", "TEXT_SEND_UNCONFIRMED",
                ImageSendStatus.NOT_ATTEMPTED, null, null, null,
                DeliveryLedgerService.BudgetSettlement.NONE, null, "超时"));
        assertEquals(0, scalarl("SELECT COUNT(*) FROM daily_budget"));

        AttemptResolutionService.ResolutionResult result = resolutionService.resolve(
                attempt.attemptId(), "SENT", "人工确认", 0, null);

        assertEquals("CONFIRMED", result.textStatus());
        assertEquals(0, scalarl("SELECT COUNT(*) FROM daily_budget"), "无预留不得创建/修改预算聚合");
    }

    @Test
    void missingAttemptThrowsNotFound() {
        AttemptNotFoundException error = assertThrows(AttemptNotFoundException.class,
                () -> resolutionService.resolve("not-exists", "SENT", "原因", 0, null));
        assertEquals("not-exists", error.getAttemptId());
    }

    @Test
    void invalidParametersAreRejected() {
        Fixture fixture = createAttempt(TextSendStatus.UNKNOWN);
        String attemptId = fixture.attempt().attemptId();

        assertThrows(InvalidResolutionException.class,
                () -> resolutionService.resolve(attemptId, "WHATEVER", "原因", 0, null));
        assertThrows(InvalidResolutionException.class,
                () -> resolutionService.resolve(attemptId, null, "原因", 0, null));
        assertThrows(InvalidResolutionException.class,
                () -> resolutionService.resolve(attemptId, "SENT", "  ", 0, null));
        assertThrows(InvalidResolutionException.class,
                () -> resolutionService.resolve(attemptId, "SENT", "x".repeat(501), 0, null));
        assertThrows(InvalidResolutionException.class,
                () -> resolutionService.resolve(attemptId, "SENT", "原因", -1, null));
        assertEquals("UNKNOWN", attemptTextStatus(attemptId), "参数非法不得产生改动");
    }

    @Test
    void notAttemptedStatusCannotBeManuallyMarkedSentOrNotSent() {
        // 未发起发送（NOT_ATTEMPTED）：按契约只允许 PENDING/UNKNOWN/FAILED 人工核对
        Fixture fixture = createAttempt(TextSendStatus.NOT_ATTEMPTED);

        assertThrows(InvalidResolutionException.class,
                () -> resolutionService.resolve(fixture.attempt().attemptId(), "SENT", "原因", 0, null));
        assertThrows(InvalidResolutionException.class,
                () -> resolutionService.resolve(fixture.attempt().attemptId(), "NOT_SENT", "原因", 0, null));
        // STILL_UNKNOWN 不改 text_status，允许
        assertTrue(resolutionService.resolve(fixture.attempt().attemptId(), "STILL_UNKNOWN", "仍未知", 0, null)
                .changed());
    }

    // ==================== 夹具与查询工具 ====================

    private record Fixture(DeliveryLedgerService.AttemptStarted attempt, BudgetService.Reservation reservation) {
    }

    /**
     * 用真实 A+ 流程造出指定文字状态的 attempt：reserve → beginAttempt → settleAttempt。
     */
    private Fixture createAttempt(TextSendStatus textStatus) {
        String encryptId = "encrypt-" + System.nanoTime();
        BudgetService.Reservation reservation =
                budgetService.reserveFirstContact("run-1", encryptId, "岗位");
        DeliveryLedgerService.AttemptStarted attempt = ledgerService.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, encryptId, "user-1", "岗位", "公司",
                null, null, null, null, reservation.reservationId());
        String errorCode;
        DeliveryLedgerService.BudgetSettlement budgetAction;
        if (textStatus == TextSendStatus.CONFIRMED) {
            errorCode = null;
            budgetAction = DeliveryLedgerService.BudgetSettlement.CONFIRM;
        } else if (textStatus == TextSendStatus.NOT_ATTEMPTED) {
            errorCode = "TEXT_NOT_CLICKED";
            budgetAction = DeliveryLedgerService.BudgetSettlement.RELEASE;
        } else {
            errorCode = textStatus == TextSendStatus.UNKNOWN ? "TEXT_SEND_UNCONFIRMED" : "TEXT_SEND_FAILED";
            budgetAction = DeliveryLedgerService.BudgetSettlement.UNKNOWN;
        }
        ledgerService.settleAttempt(new DeliveryLedgerService.SettlementRequest(
                attempt.rowId(), textStatus, "DOM", "测试状态设置", errorCode,
                ImageSendStatus.NOT_ATTEMPTED, null, null, reservation.reservationId(),
                budgetAction, errorCode, "测试状态设置"));
        return new Fixture(attempt, reservation);
    }

    private String attemptTextStatus(String attemptId) {
        return scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE attempt_id = ?", attemptId);
    }

    private int attemptVersion(String attemptId) {
        return scalar(dataSource,
                "SELECT version FROM delivery_attempt WHERE attempt_id = ?", attemptId);
    }

    private String attemptManualResolution(String attemptId) {
        return scalarString(dataSource,
                "SELECT manual_resolution FROM delivery_attempt WHERE attempt_id = ?", attemptId);
    }

    private String reservationState(String reservationId) {
        return scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservationId);
    }

    private int budgetColumn(String column) {
        return scalar(dataSource, "SELECT COALESCE(SUM(" + column + "), 0) FROM daily_budget");
    }

    private int scalarl(String sql) {
        return scalar(dataSource, sql);
    }
}
