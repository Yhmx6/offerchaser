package com.offerchaser.verification;

import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryKind;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.ImageSendStatus;
import com.offerchaser.application.persistence.TextSendStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;

import static com.offerchaser.verification.VerificationSupport.budgetService;
import static com.offerchaser.verification.VerificationSupport.ledgerService;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 持久化层「模拟进程重启」集成验收（A+ 版：逐笔预留 + 原子结算）。
 * <p>
 * 用同一个临时 SQLite 库反复 new 服务实例来模拟应用重启，验证：
 * <ul>
 *   <li>每日预算持久化、跨重启不能被绕过；结果未知的占用（uncertain）不会被重启释放；</li>
 *   <li>逐笔预留（budget_reservation）与 PENDING attempt 的崩溃恢复：转 UNKNOWN 并保守占用额度；</li>
 *   <li>首次沟通去重与跟进记录跨重启仍可查询。</li>
 * </ul>
 */
class PersistenceRestartIntegrationTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
    }

    @Test
    void dailyBudgetCannotBeBypassedByRestart() {
        // 第一次运行：上限 2，预留两次后第三次被拒绝
        BudgetService beforeRestart = budgetService(dataSource, true, 2);
        BudgetService.Reservation first = beforeRestart.reserveFirstContact("run-1", "JOB-1", "后端开发");
        BudgetService.Reservation second = beforeRestart.reserveFirstContact("run-1", "JOB-2", "后端开发");
        BudgetService.Reservation third = beforeRestart.reserveFirstContact("run-1", "JOB-3", "后端开发");
        assertTrue(first.granted(), "第一次预留应成功");
        assertNotNull(first.reservationId(), "A-05：每次预留必须有独立 reservationId");
        assertTrue(second.granted(), "第二次预留应成功");
        assertFalse(third.granted(), "达到上限后第三次预留必须被拒绝");
        assertNull(third.reservationId(), "未授权的预留不应有 reservationId");
        assertEquals(2, scalar(dataSource,
                "SELECT COUNT(*) FROM budget_reservation WHERE state='RESERVED'"), "逐笔预留表应有 2 笔待结算");

        // 第一次确认成功、第二次结果未知：已用额度 = consumed 1 + uncertain 1 = 2
        assertTrue(beforeRestart.confirmSent(first.reservationId()));
        assertTrue(beforeRestart.markUnknown(second.reservationId()));
        assertFalse(beforeRestart.confirmSent(first.reservationId()), "同一预留重复确认必须是幂等 no-op");

        // 模拟重启：同一数据库上创建全新 BudgetService（内存计数器全部丢失）
        BudgetService afterRestart = budgetService(dataSource, true, 2);
        assertTrue(afterRestart.isFirstContactBudgetDepleted(), "重启后预算不应清零");
        BudgetService.Reservation afterRestartReservation =
                afterRestart.reserveFirstContact("run-2", "JOB-4", "后端开发");
        assertFalse(afterRestartReservation.granted(), "重启后仍然不能突破每日上限");

        Map<String, Object> firstContact = firstContactSummary(afterRestart);
        assertEquals(2, firstContact.get("limit"));
        assertEquals(1, firstContact.get("consumed"));
        assertEquals(1, firstContact.get("uncertain"), "不确定占用必须跨重启保留");
        assertEquals(0, firstContact.get("reserved"), "未结算的预留不应无故出现");
        assertEquals(0, firstContact.get("remaining"));
        assertEquals(0, firstContact.get("pendingReservations"));
        assertEquals(0, afterRestart.todaySummary().get("pendingReservations"));
    }

    @Test
    void unknownResultKeepsOccupyingBudgetAcrossRestartAndCannotBeReleasedByRestart() {
        BudgetService beforeRestart = budgetService(dataSource, true, 1);
        BudgetService.Reservation reservation =
                beforeRestart.reserveFirstContact("run-1", "JOB-1", "测试");
        assertTrue(reservation.granted());
        assertTrue(beforeRestart.markUnknown(reservation.reservationId()));

        // 重启：uncertain 仍为 1，额度仍被占用
        BudgetService afterRestart = budgetService(dataSource, true, 1);
        assertEquals(1, firstContactSummary(afterRestart).get("uncertain"));
        assertFalse(afterRestart.reserveFirstContact("run-2", "JOB-2", "测试").granted());

        // 重复结算 / 反向结算都不会改变已结算状态，也不会二次占额
        assertFalse(afterRestart.markUnknown(reservation.reservationId()),
                "重复 markUnknown 必须幂等 no-op（A-05）");
        assertFalse(afterRestart.confirmSent(reservation.reservationId()),
                "已 UNKNOWN 的预留不能被后到的 confirmSent 改写成成功");
        assertFalse(afterRestart.release(reservation.reservationId(), "重启后误释放"),
                "已结算的预留不能被重复释放");
        Map<String, Object> summary = firstContactSummary(afterRestart);
        assertEquals(1, summary.get("uncertain"), "重复结算不应重复占用额度");
        assertEquals(0, summary.get("consumed"), "不确定状态不能被后到的 confirmSent 改写成成功");
        assertEquals(0, summary.get("reserved"));
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
    }

    @Test
    void stalePendingAttemptIsReconciledToUnknownAndConsumesBudget() {
        BudgetService beforeCrash = budgetService(dataSource, true, 5);
        DeliveryLedgerService ledgerBeforeCrash = ledgerService(dataSource);
        BudgetService.Reservation reservation =
                beforeCrash.reserveFirstContact("run-crashed", "ENCRYPT-1", "Java 开发");
        assertTrue(reservation.granted());
        DeliveryLedgerService.AttemptStarted attempt = ledgerBeforeCrash.beginAttempt(
                "run-crashed", DeliveryKind.FIRST_CONTACT, "ENCRYPT-1", "USER-1",
                "Java 开发", "示例科技", "https://www.zhipin.com/job_detail/1.html",
                "snapshot-hash", "message-hash", null, reservation.reservationId());
        assertTrue(attempt.rowId() > 0);
        // A-05：预留与 attempt 在同一事务内互相绑定
        assertEquals(attempt.attemptId(), scalarString(dataSource,
                "SELECT attempt_id FROM budget_reservation WHERE reservation_id = ?",
                reservation.reservationId()));
        // 模拟进程崩溃：既没有 settleAttempt，也没有结算预算，attempt 停在 PENDING

        // 模拟重启后启动新任务：启动预检调用 reconcileStaleReservations
        BudgetService afterRestart = budgetService(dataSource, true, 5);
        int processed = afterRestart.reconcileStaleReservations("run-new");
        assertEquals(1, processed, "上次运行遗留的 PENDING 记录/逐笔预留应被回收 1 条");

        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("CRASH_RECOVERY", scalarString(dataSource,
                "SELECT error_code FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertNotNull(scalarString(dataSource,
                "SELECT finished_at FROM delivery_attempt WHERE id = ?", attempt.rowId()),
                "回收后的 attempt 应写入结束时间");
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?",
                reservation.reservationId()));
        assertEquals(1, scalar(dataSource, "SELECT version FROM budget_reservation WHERE reservation_id = ?",
                reservation.reservationId()), "逐笔预留状态转移应只发生一次（version+1）");
        assertNotNull(scalarString(dataSource,
                "SELECT settled_at FROM budget_reservation WHERE reservation_id = ?",
                reservation.reservationId()));
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM delivery_event " +
                        "WHERE attempt_id = ? AND event_type = 'TEXT_UNKNOWN'",
                attempt.attemptId()), "回收应追加 TEXT_UNKNOWN 事件");

        Map<String, Object> summary = firstContactSummary(afterRestart);
        assertEquals(0, summary.get("reserved"), "遗留预留应从 reserved 转出");
        assertEquals(1, summary.get("uncertain"), "崩溃遗留必须保守占用额度");
        assertEquals(0, summary.get("consumed"));

        // 幂等：同一条记录不会被二次回收
        assertEquals(0, afterRestart.reconcileStaleReservations("run-another"));
        assertEquals(1, firstContactSummary(afterRestart).get("uncertain"));
    }

    @Test
    void reconcileOnlyTouchesAttemptsFromOtherRuns() {
        BudgetService budget = budgetService(dataSource, true, 5);
        DeliveryLedgerService ledger = ledgerService(dataSource);
        BudgetService.Reservation reservation =
                budget.reserveFirstContact("run-current", "ENCRYPT-1", "Java 开发");
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-current", DeliveryKind.FIRST_CONTACT, "ENCRYPT-1", "USER-1",
                "Java 开发", "示例科技", "https://www.zhipin.com/job_detail/1.html",
                "snapshot-hash", "message-hash", null, reservation.reservationId());

        // 当前 run 的 PENDING 记录不能被当成"上次崩溃遗留"
        assertEquals(0, budget.reconcileStaleReservations("run-current"));
        assertEquals("PENDING", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("RESERVED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
        assertEquals(1, firstContactSummary(budget).get("reserved"));

        // 不传 currentRunId（被视为全部是遗留数据）时才回收
        assertEquals(1, budget.reconcileStaleReservations(null));
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservation.reservationId()));
    }

    @Test
    void ledgerFirstContactAndFollowupQueriesSurviveRestart() {
        DeliveryLedgerService beforeRestart = ledgerService(dataSource);
        DeliveryLedgerService.AttemptStarted firstContact = beforeRestart.beginAttempt(
                "run-1", DeliveryKind.FIRST_CONTACT, "ENCRYPT-1", "USER-1",
                "Java 开发", "示例科技", "https://www.zhipin.com/job_detail/1.html",
                "snapshot-hash", "message-hash", null, (String) null);
        beforeRestart.markTextSent(firstContact.rowId(), TextSendStatus.CONFIRMED,
                "DOM_TEXT_MESSAGE", "本人新消息出现", null);
        beforeRestart.markImageSent(firstContact.rowId(), ImageSendStatus.FAILED,
                "DOM_IMAGE_FAILED", "图片消息出现失败标记");
        beforeRestart.finishAttempt(firstContact.rowId(), null, null);
        beforeRestart.recordFollowup("ENCRYPT-1", "USER-1", "Java 开发");
        beforeRestart.recordFollowup("ENCRYPT-1", null, null);

        // 模拟重启：所有查询都必须来自数据库而不是内存
        DeliveryLedgerService afterRestart = ledgerService(dataSource);
        assertTrue(afterRestart.hasConfirmedFirstContact("ENCRYPT-1"), "已确认的首次沟通应跨重启保留");
        assertFalse(afterRestart.hasConfirmedFirstContact("ENCRYPT-UNKNOWN"));
        assertNotNull(afterRestart.lastConfirmedFirstContactAt("ENCRYPT-1"));
        assertNull(afterRestart.lastConfirmedFirstContactAt("ENCRYPT-UNKNOWN"));
        assertEquals(1, afterRestart.confirmedFirstContactCountToday());
        assertEquals(2, afterRestart.followupCount("ENCRYPT-1"), "跟进次数应跨重启累加");
        assertNotNull(afterRestart.lastFollowupAt("ENCRYPT-1"));
        assertEquals(0, afterRestart.followupCount("ENCRYPT-UNKNOWN"));

        // 文字确认 + 图片失败：两个状态互相独立
        Map<String, Object> summary = afterRestart.todaySummary();
        assertEquals(1, summary.get("attempts"));
        assertEquals(1, summary.get("textConfirmed"));
        assertEquals(1, summary.get("firstContactConfirmed"));
        assertEquals(1, summary.get("imageFailed"));
        assertEquals(0, summary.get("imageConfirmed"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstContactSummary(BudgetService budgetService) {
        Map<String, Object> summary = budgetService.todaySummary();
        return (Map<String, Object>) summary.get("firstContact");
    }
}
