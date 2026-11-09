package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static com.offerchaser.application.persistence.PersistenceTestSupport.ACCOUNT;
import static com.offerchaser.application.persistence.PersistenceTestSupport.TIMEZONE;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalar;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * B1 人工核对事务边界测试：attempt 更新 / 审计行 / 预算聚合 / 预留状态任一步写入失败时，
 * 整个 resolve 事务必须回滚（状态、版本、预算、审计全部保持原样），且故障恢复后可用同一
 * expectedVersion 重试成功、额度只调整一次。
 */
class AttemptResolutionFailureTest {

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource;
    private BudgetService budgetService;
    private DeliveryLedgerService ledgerService;

    @BeforeEach
    void setUp() {
        dataSource = PersistenceTestSupport.migratedDataSource(directory);
        budgetService = new BudgetService(dataSource, true, 5, 5, TIMEZONE, ACCOUNT);
        ledgerService = new DeliveryLedgerService(dataSource, ACCOUNT, TIMEZONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE delivery_attempt SET text_status",
            "UPDATE budget_reservation",
            "UPDATE daily_budget",
            "INSERT INTO manual_action"
    })
    void resolveRollsBackOnAnyWriteBoundaryAndCanBeRetried(String failFragment) {
        Fixture fixture = createUnknownAttempt();

        FaultInjectingDataSource failingDataSource = new FaultInjectingDataSource(dataSource);
        failingDataSource.failOn(failFragment);
        AttemptResolutionService failingResolver = new AttemptResolutionService(failingDataSource);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> failingResolver.resolve(
                fixture.attemptId(), "SENT", "人工确认已发送", 0, "key-retry-1"));
        failingDataSource.stopFailing();
        assertEquals(true, error.getMessage().contains("人工核对失败"));
        assertRolledBack(fixture);

        // 故障恢复后重试（同一 expectedVersion / idempotencyKey）必须成功，且额度只调整一次
        AttemptResolutionService normalResolver = new AttemptResolutionService(dataSource);
        AttemptResolutionService.ResolutionResult result = normalResolver.resolve(
                fixture.attemptId(), "SENT", "人工确认已发送", 0, "key-retry-1");
        assertEquals("CONFIRMED", result.textStatus());
        assertEquals(1, result.version());
        assertEquals(1, budgetColumn("consumed"));
        assertEquals(0, budgetColumn("uncertain"));
        assertEquals(0, budgetColumn("reserved"));
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM manual_action"));
    }

    @Test
    void failedResolutionKeepsAttemptUnchangedForReconcileOrManualRetry() {
        Fixture fixture = createUnknownAttempt();
        FaultInjectingDataSource failingDataSource = new FaultInjectingDataSource(dataSource);
        failingDataSource.failOn("INSERT INTO manual_action");

        assertThrows(IllegalStateException.class, () -> new AttemptResolutionService(failingDataSource).resolve(
                fixture.attemptId(), "NOT_SENT", "未发送", 0, null));
        failingDataSource.stopFailing();

        assertRolledBack(fixture);
        // 之后用正确参数重试 NOT_SENT 仍可成功
        AttemptResolutionService.ResolutionResult result = new AttemptResolutionService(dataSource).resolve(
                fixture.attemptId(), "NOT_SENT", "未发送", 0, null);
        assertEquals("FAILED", result.textStatus());
        assertEquals(0, budgetColumn("uncertain"));
        assertEquals(0, budgetColumn("consumed"));
        assertEquals("RELEASED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?",
                fixture.reservationId()));
    }

    private void assertRolledBack(Fixture fixture) {
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE attempt_id = ?", fixture.attemptId()));
        assertEquals(0, scalar(dataSource,
                "SELECT version FROM delivery_attempt WHERE attempt_id = ?", fixture.attemptId()));
        assertNull(scalarString(dataSource,
                "SELECT manual_resolution FROM delivery_attempt WHERE attempt_id = ?", fixture.attemptId()));
        assertNull(scalarString(dataSource,
                "SELECT resolved_at FROM delivery_attempt WHERE attempt_id = ?", fixture.attemptId()));
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", fixture.reservationId()));
        assertEquals(1, budgetColumn("uncertain"));
        assertEquals(0, budgetColumn("consumed"));
        assertEquals(0, budgetColumn("reserved"));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM manual_action"));
    }

    private record Fixture(String attemptId, String reservationId) {
    }

    private Fixture createUnknownAttempt() {
        BudgetService.Reservation reservation =
                budgetService.reserveFirstContact("run-1", "encrypt-fault", "岗位");
        DeliveryLedgerService.AttemptStarted attempt = ledgerService.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "encrypt-fault", "user-1", "岗位", "公司",
                null, null, null, null, reservation.reservationId());
        ledgerService.settleAttempt(new DeliveryLedgerService.SettlementRequest(
                attempt.rowId(), TextSendStatus.UNKNOWN, "TIMEOUT", "超时", "TEXT_SEND_UNCONFIRMED",
                ImageSendStatus.NOT_ATTEMPTED, null, null, reservation.reservationId(),
                DeliveryLedgerService.BudgetSettlement.UNKNOWN, "TEXT_SEND_UNCONFIRMED", "超时"));
        return new Fixture(attempt.attemptId(), reservation.reservationId());
    }

    private int budgetColumn(String column) {
        return scalar(dataSource, "SELECT COALESCE(SUM(" + column + "), 0) FROM daily_budget");
    }
}
