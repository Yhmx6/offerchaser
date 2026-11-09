package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static com.offerchaser.application.persistence.PersistenceTestSupport.scalar;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-05 结算事务边界测试：在「文字写入 / 事件写入 / daily_budget 聚合写入」三个边界注入 SQL 异常，
 * 验证 {@link DeliveryLedgerService#settleAttempt} 会整体回滚（attempt 仍 PENDING、预留仍 RESERVED、
 * 聚合仍 reserved=1、无业务事件），且下次启动的 reconcile 能把遗留状态恢复成 UNKNOWN。
 */
class SettlementFailureRecoveryTest {

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource;
    private BudgetService budget;
    private DeliveryLedgerService ledger;

    @BeforeEach
    void setUp() {
        dataSource = PersistenceTestSupport.migratedDataSource(directory);
        budget = new BudgetService(dataSource, true, 5, 5,
                PersistenceTestSupport.TIMEZONE, PersistenceTestSupport.ACCOUNT);
        ledger = new DeliveryLedgerService(dataSource, PersistenceTestSupport.ACCOUNT,
                PersistenceTestSupport.TIMEZONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE delivery_attempt SET text_status",
            "INSERT INTO delivery_event",
            "UPDATE daily_budget"
    })
    void settlementRollsBackOnAnyWriteBoundaryAndReconcileRecovers(String failFragment) {
        BudgetService.Reservation reservation = budget.reserveFirstContact("run-1", "job-1", "岗位1");
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, reservation.reservationId());

        FaultInjectingDataSource failingDataSource = new FaultInjectingDataSource(dataSource);
        failingDataSource.failOn(failFragment);
        DeliveryLedgerService failingLedger = new DeliveryLedgerService(failingDataSource,
                PersistenceTestSupport.ACCOUNT, PersistenceTestSupport.TIMEZONE);

        boolean settled = failingLedger.settleAttempt(settlementRequest(attempt.rowId(),
                reservation.reservationId()));
        failingDataSource.stopFailing();

        assertFalse(settled, "写入故障时结算必须返回 false（表示失败，不得当成成功）");
        // 整体回滚：尝试仍是 PENDING、没有 finished_at、没有业务事件
        assertEquals("PENDING", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertNull(scalarString(dataSource,
                "SELECT finished_at FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? " +
                        "AND event_type IN ('TEXT_CONFIRMED','IMAGE_CONFIRMED','BUDGET_CONFIRMED')",
                attempt.attemptId()));
        // 预留与聚合保持原样，额度没有被吞掉
        assertEquals("RESERVED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertEquals(1, scalar(dataSource, "SELECT reserved FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT consumed FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT uncertain FROM daily_budget"));

        // 下次启动 reconcile 可恢复：预留转 UNKNOWN，attempt 转 UNKNOWN + CRASH_RECOVERY
        BudgetService recoveredBudget = new BudgetService(dataSource, true, 5, 5,
                PersistenceTestSupport.TIMEZONE, PersistenceTestSupport.ACCOUNT);
        assertEquals(1, recoveredBudget.reconcileStaleReservations("run-other"));
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("CRASH_RECOVERY", scalarString(dataSource,
                "SELECT error_code FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));
        assertEquals(1, scalar(dataSource, "SELECT uncertain FROM daily_budget"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'TEXT_UNKNOWN'",
                attempt.attemptId()));
        // 幂等，不会二次占额
        assertEquals(0, recoveredBudget.reconcileStaleReservations("run-other-2"));
    }

    @Test
    void failedSettlementRetryAfterRecoveryDoesNotDoubleConsume() {
        BudgetService.Reservation reservation = budget.reserveFirstContact("run-1", "job-1", "岗位1");
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, reservation.reservationId());

        FaultInjectingDataSource failingDataSource = new FaultInjectingDataSource(dataSource);
        failingDataSource.failOn("UPDATE daily_budget");
        assertFalse(new DeliveryLedgerService(failingDataSource, PersistenceTestSupport.ACCOUNT,
                PersistenceTestSupport.TIMEZONE).settleAttempt(settlementRequest(attempt.rowId(),
                reservation.reservationId())));
        failingDataSource.stopFailing();

        // 故障恢复后再次结算成功：额度只记一次
        assertTrue(ledger.settleAttempt(settlementRequest(attempt.rowId(), reservation.reservationId())));
        assertEquals(1, scalar(dataSource, "SELECT consumed FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT uncertain FROM daily_budget"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'BUDGET_CONFIRMED'",
                attempt.attemptId()));
    }

    @Test
    void beginAttemptFailureDoesNotLeakAttempt() {
        FaultInjectingDataSource failingDataSource = new FaultInjectingDataSource(dataSource);
        failingDataSource.failOn("INSERT INTO delivery_attempt");
        DeliveryLedgerService failingLedger = new DeliveryLedgerService(failingDataSource,
                PersistenceTestSupport.ACCOUNT, PersistenceTestSupport.TIMEZONE);

        BudgetService.Reservation reservation = budget.reserveFirstContact("run-1", "job-1", "岗位1");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> failingLedger.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, reservation.reservationId()));
        failingDataSource.stopFailing();

        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM delivery_attempt"));
        // 台账没建成 → 预留仍可被释放/回收，不会出现无主 reserved
        assertTrue(budget.release(reservation.reservationId(), "台账初始化失败"));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));
    }

    private DeliveryLedgerService.SettlementRequest settlementRequest(long attemptRowId, String reservationId) {
        return new DeliveryLedgerService.SettlementRequest(
                attemptRowId, TextSendStatus.CONFIRMED,
                "DOM_TEXT_MESSAGE", "本人新消息出现", null,
                ImageSendStatus.CONFIRMED, "DOM_IMAGE_MESSAGE", "图片消息出现",
                reservationId, DeliveryLedgerService.BudgetSettlement.CONFIRM, null, "投递完成");
    }
}
