package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;

import static com.offerchaser.application.persistence.PersistenceTestSupport.scalar;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-05 每日预算测试：逐笔预留、原子结算、重复 release 不影响其他预留、重启保持、
 * 不确定占用、遗留预留回收，以及兼容旧接口。
 */
class BudgetServiceTest {

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource;
    private DeliveryLedgerService ledgerService;

    @BeforeEach
    void setUp() {
        dataSource = PersistenceTestSupport.migratedDataSource(directory);
        ledgerService = new DeliveryLedgerService(dataSource, PersistenceTestSupport.ACCOUNT,
                PersistenceTestSupport.TIMEZONE);
    }

    private BudgetService budget(boolean enabled, int dailyLimit, int followupLimit) {
        return new BudgetService(dataSource, enabled, dailyLimit, followupLimit,
                PersistenceTestSupport.TIMEZONE, PersistenceTestSupport.ACCOUNT);
    }

    @Test
    void reservationCreatesPerReservationRecordsAndStopsAtLimit() {
        BudgetService budget = budget(true, 2, 2);

        BudgetService.Reservation first = budget.reserveFirstContact("run-1", "job-1", "岗位1");
        BudgetService.Reservation second = budget.reserveFirstContact("run-1", "job-2", "岗位2");
        assertTrue(first.granted());
        assertTrue(second.granted());
        assertNotNull(first.reservationId());
        assertNotNull(second.reservationId());
        assertFalse(first.reservationId().equals(second.reservationId()));
        assertEquals(first.budgetRowId(), second.budgetRowId(), "同一天同一动作共用一条 daily_budget 聚合行");
        assertTrue(first.budgetRowId() > 0);

        assertEquals(2, scalar(dataSource,
                "SELECT COUNT(*) FROM budget_reservation WHERE state = 'RESERVED'"));
        assertEquals(2, scalar(dataSource, "SELECT reserved FROM daily_budget"));

        BudgetService.Reservation denied = budget.reserveFirstContact("run-1", "job-3", "岗位3");
        assertFalse(denied.granted());
        assertNull(denied.reservationId(), "被拒绝的预留不落记录");
        assertEquals("今日首次沟通预算已用尽", denied.reason());
        assertEquals(2, scalar(dataSource, "SELECT COUNT(*) FROM budget_reservation"));
    }

    @Test
    void followupBudgetIsIndependentFromFirstContact() {
        BudgetService budget = budget(true, 1, 2);

        assertTrue(budget.reserveFirstContact("run-1", "job-1", "岗位1").granted());
        assertFalse(budget.reserveFirstContact("run-1", "job-2", "岗位2").granted());

        assertTrue(budget.reserveFollowup("run-1", "job-1", "岗位1").granted());
        assertTrue(budget.reserveFollowup("run-1", "job-1", "岗位1").granted());
        BudgetService.Reservation denied = budget.reserveFollowup("run-1", "job-1", "岗位1");
        assertFalse(denied.granted());
        assertEquals("今日跟进沟通额度已用尽", denied.reason());
    }

    @Test
    void confirmSentTransitionsOnceAndUpdatesAggregate() {
        BudgetService budget = budget(true, 2, 2);
        BudgetService.Reservation reservation = budget.reserveFirstContact("run-1", "job-1", "岗位1");

        assertTrue(budget.confirmSent(reservation.reservationId()));
        assertEquals("CONFIRMED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertEquals(1, scalar(dataSource,
                "SELECT version FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertNotNull(scalarString(dataSource,
                "SELECT settled_at FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertEquals(1, scalar(dataSource, "SELECT consumed FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));

        // 重复 confirm 是幂等 no-op，返回 false 且不重复累加
        assertFalse(budget.confirmSent(reservation.reservationId()));
        assertEquals(1, scalar(dataSource, "SELECT consumed FROM daily_budget"));
    }

    @Test
    void repeatedReleaseIsIdempotentAndNeverReleasesOtherReservation() {
        // 外部验收 AuditBudgetProbeTest 的核心场景
        BudgetService budget = budget(true, 3, 3);
        BudgetService.Reservation first = budget.reserveFirstContact("run-1", "job-1", "岗位1");
        BudgetService.Reservation second = budget.reserveFirstContact("run-1", "job-2", "岗位2");

        assertTrue(budget.release(first.reservationId(), "未发送"));
        assertFalse(budget.release(first.reservationId(), "重复释放"));
        assertFalse(budget.release(first.reservationId(), "第三次释放"));
        // 第二次预留的额度必须原封不动
        assertEquals(1, scalar(dataSource,
                "SELECT reserved FROM daily_budget WHERE id = ?", second.budgetRowId()));
        assertEquals("RESERVED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", second.reservationId()));
        assertEquals(1, budget.todaySummary().get("pendingReservations"));

        // 释放别人之后，自己的预留仍然能正常确认
        assertTrue(budget.confirmSent(second.reservationId()));
        assertEquals(1, scalar(dataSource, "SELECT consumed FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));
        assertEquals(0, budget.todaySummary().get("pendingReservations"));
        assertFalse(budget.confirmSent(second.reservationId()), "同一预留不能重复确认");
    }

    @Test
    void unknownResultConsumesBudgetConservatively() {
        BudgetService budget = budget(true, 2, 2);
        BudgetService.Reservation reservation = budget.reserveFirstContact("run-1", "job-1", "岗位1");

        assertTrue(budget.markUnknown(reservation.reservationId()));
        assertFalse(budget.markUnknown(reservation.reservationId()));

        Map<String, Object> summary = budget.todaySummary();
        @SuppressWarnings("unchecked")
        Map<String, Object> firstContact = (Map<String, Object>) summary.get("firstContact");
        assertEquals(1, firstContact.get("uncertain"));
        assertEquals(0, firstContact.get("reserved"));
        assertEquals(0, firstContact.get("consumed"));

        assertTrue(budget.reserveFirstContact("run-1", "job-2", "岗位2").granted());
        assertFalse(budget.reserveFirstContact("run-1", "job-3", "岗位3").granted());
    }

    @Test
    void reservationsSurviveRestartAndCanBeSettledByNewInstance() {
        BudgetService firstInstance = budget(true, 2, 2);
        BudgetService.Reservation reservation = firstInstance.reserveFirstContact("run-1", "job-1", "岗位1");
        assertTrue(reservation.granted());

        // 全新实例（模拟进程重启）读取的仍是数据库里的逐笔预留与聚合计数
        BudgetService restarted = budget(true, 2, 2);
        assertEquals(1, restarted.todaySummary().get("pendingReservations"));
        assertTrue(restarted.confirmSent(reservation.reservationId()));
        assertEquals(1, scalar(dataSource, "SELECT consumed FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));
    }

    @Test
    void nonPositiveOrDisabledLimitMeansUnlimited() {
        BudgetService unlimited = budget(true, 0, 0);
        for (int i = 0; i < 5; i++) {
            assertTrue(unlimited.reserveFirstContact("run-1", "job-" + i, "岗位" + i).granted());
        }
        assertFalse(unlimited.isFirstContactBudgetDepleted());

        BudgetService disabled = budget(false, 1, 1);
        for (int i = 0; i < 3; i++) {
            assertTrue(disabled.reserveFirstContact("run-2", "disabled-" + i, "岗位" + i).granted());
        }
        assertFalse(disabled.isFirstContactBudgetDepleted());
    }

    @Test
    void reconcileStaleReservationSettlesAttemptAndBudget() {
        BudgetService budget = budget(true, 5, 5);
        BudgetService.Reservation reservation =
                budget.reserveFirstContact("run-crashed", "job-1", "岗位1");
        DeliveryLedgerService.AttemptStarted attempt = ledgerService.beginAttempt(
                "run-crashed", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                "https://example.com/job-1", "snapshot-hash", "message-hash", "resume-hash",
                reservation.reservationId());

        // 模拟崩溃：attempt 停在 PENDING、预留停在 RESERVED，没有任何结算
        BudgetService afterRestart = budget(true, 5, 5);
        assertEquals(1, afterRestart.reconcileStaleReservations("run-new"));

        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("CRASH_RECOVERY", scalarString(dataSource,
                "SELECT error_code FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertNotNull(scalarString(dataSource,
                "SELECT finished_at FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = 'TEXT_UNKNOWN'",
                attempt.attemptId()));

        Map<String, Object> summary = afterRestart.todaySummary();
        @SuppressWarnings("unchecked")
        Map<String, Object> firstContact = (Map<String, Object>) summary.get("firstContact");
        assertEquals(0, firstContact.get("reserved"));
        assertEquals(1, firstContact.get("uncertain"));
        assertEquals(0, summary.get("pendingReservations"));

        // 幂等
        assertEquals(0, afterRestart.reconcileStaleReservations("run-newer"));
        assertEquals(1, firstContact(afterRestart).get("uncertain"));
    }

    @Test
    void reconcileLeavesCurrentRunAndSettledReservationsAlone() {
        BudgetService budget = budget(true, 5, 5);
        BudgetService.Reservation current =
                budget.reserveFirstContact("run-current", "job-1", "岗位1");
        DeliveryLedgerService.AttemptStarted attempt = ledgerService.beginAttempt(
                "run-current", DeliveryKind.FIRST_CONTACT, "job-1", "user-1", "岗位1", "公司1",
                null, null, null, null, current.reservationId());

        // 当前 run 的 RESERVED 预留不能被回收
        assertEquals(0, budget.reconcileStaleReservations("run-current"));
        assertEquals("PENDING", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("RESERVED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", current.reservationId()));

        // 已结算的预留也不再回收
        assertTrue(budget.confirmSent(current.reservationId()));
        assertEquals(0, budget.reconcileStaleReservations("run-current"));
        assertEquals(0, budget.reconcileStaleReservations(null));
    }

    @Test
    void reconcileTreatsUnlinkedReservationAsStale() {
        // 预留后、beginAttempt 前崩溃：attempt_id 为空，也应按 UNKNOWN 保守占额
        BudgetService budget = budget(true, 5, 5);
        BudgetService.Reservation reservation =
                budget.reserveFirstContact("run-crashed", "job-1", "岗位1");

        assertEquals(1, budget.reconcileStaleReservations("run-new"));
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertEquals(1, firstContact(budget).get("uncertain"));
        assertEquals(0, budget.reconcileStaleReservations("run-newer"));
    }

    @Test
    void reconcileFallsBackToLegacyPendingAttemptWithoutReservation() {
        BudgetService budget = budget(true, 5, 5);
        String now = PersistenceSupport.now();
        String budgetDate = PersistenceSupport.today(
                PersistenceSupport.zone(PersistenceTestSupport.TIMEZONE));
        // 模拟旧版本数据：只有 daily_budget 聚合行 + PENDING attempt，没有 budget_reservation
        PersistenceTestSupport.execute(dataSource,
                "INSERT INTO daily_budget(account, budget_date, action_type, budget_limit, consumed, " +
                        "reserved, uncertain, updated_at) VALUES(?, ?, 'FIRST_CONTACT', 5, 0, 1, 0, ?)",
                PersistenceTestSupport.ACCOUNT, budgetDate, now);
        int budgetRowId = scalar(dataSource,
                "SELECT id FROM daily_budget WHERE account = ? AND budget_date = ? AND action_type = 'FIRST_CONTACT'",
                PersistenceTestSupport.ACCOUNT, budgetDate);
        PersistenceTestSupport.execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "budget_id, started_at, created_at) VALUES(?, ?, ?, 'FIRST_CONTACT', 'PENDING', " +
                        "'NOT_ATTEMPTED', ?, ?, ?)",
                "legacy-attempt-1", "run-legacy", PersistenceTestSupport.ACCOUNT, budgetRowId, now, now);

        assertEquals(1, budget.reconcileStaleReservations("run-new"));

        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE attempt_id = 'legacy-attempt-1'"));
        assertEquals("CRASH_RECOVERY", scalarString(dataSource,
                "SELECT error_code FROM delivery_attempt WHERE attempt_id = 'legacy-attempt-1'"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = 'legacy-attempt-1' " +
                        "AND event_type = 'TEXT_UNKNOWN' AND evidence_level = 'CRASH_RECOVERY'"));
        assertEquals(1, firstContact(budget).get("uncertain"));
        assertEquals(0, firstContact(budget).get("reserved"));
    }

    @Test
    void todaySummaryExposesLimitUsageRemainingAndPendingReservations() {
        BudgetService budget = budget(true, 3, 2);
        budget.reserveFirstContact("run-1", "job-1", "岗位1");
        budget.reserveFollowup("run-1", "job-1", "岗位1");

        Map<String, Object> summary = budget.todaySummary();
        assertEquals(PersistenceTestSupport.ACCOUNT, summary.get("account"));
        assertEquals(Boolean.TRUE, summary.get("enabled"));
        assertEquals(2, summary.get("pendingReservations"));

        Map<String, Object> firstContact = firstContact(budget);
        assertEquals(3, firstContact.get("limit"));
        assertEquals(1, firstContact.get("reserved"));
        assertEquals(2, firstContact.get("remaining"));
        assertEquals(1, firstContact.get("pendingReservations"));

        @SuppressWarnings("unchecked")
        Map<String, Object> followup = (Map<String, Object>) summary.get("followup");
        assertEquals(2, followup.get("limit"));
        assertEquals(1, followup.get("reserved"));
        assertEquals(1, followup.get("remaining"));
        assertEquals(1, followup.get("pendingReservations"));
    }

    @Test
    void invalidReservationIdIsIgnored() {
        BudgetService budget = budget(true, 3, 3);
        assertFalse(budget.confirmSent((String) null));
        assertFalse(budget.confirmSent(""));
        assertFalse(budget.markUnknown("not-exists"));
        assertFalse(budget.release("not-exists", "x"));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM daily_budget"));
    }

    @Test
    void legacyLongApiStillWorksForOldTests() {
        BudgetService budget = budget(true, 2, 2);
        BudgetService.Reservation reservation = budget.reserveFirstContact("run-1", "job-1", "岗位1");

        @SuppressWarnings("deprecation")
        boolean confirmed = budget.confirmSent(reservation.id());
        assertTrue(confirmed);
        assertEquals(1, scalar(dataSource, "SELECT consumed FROM daily_budget"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstContact(BudgetService budgetService) {
        return (Map<String, Object>) budgetService.todaySummary().get("firstContact");
    }
}
