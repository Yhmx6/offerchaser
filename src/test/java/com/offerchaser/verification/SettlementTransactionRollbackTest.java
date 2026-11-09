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

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Logger;

import static com.offerchaser.verification.VerificationSupport.budgetService;
import static com.offerchaser.verification.VerificationSupport.ledgerService;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-05 原子结算验收：{@code DeliveryLedgerService.settleAttempt} 必须是单连接单事务。
 * <ul>
 *   <li>在预算预留状态转移处注入 SQL 异常 → 整个结算回滚（attempt 仍 PENDING、聚合 reserved 不变、无半写事件），
 *       然后下一次启动的 reconcile 能把该预留恢复为 UNKNOWN；</li>
 *   <li>结算成功后重试是幂等 no-op，不会重复记账，也不会动到其他预留。</li>
 * </ul>
 */
class SettlementTransactionRollbackTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private BudgetService budgetService;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        budgetService = budgetService(dataSource, true, 3);
    }

    @Test
    void injectedSettlementFailureRollsBackEverythingAndReconcileRecovers() {
        DeliveryLedgerService ledger = ledgerService(dataSource);
        BudgetService.Reservation reservation =
                budgetService.reserveFirstContact("run-tx", "JOB-TX", "事务测试");
        assertTrue(reservation.granted());
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-tx", DeliveryKind.FIRST_CONTACT, "JOB-TX", "USER-TX",
                "事务测试", "示例科技", "https://www.zhipin.com/job_detail/tx.html",
                "snapshot-hash", "message-hash", null, reservation.reservationId());
        assertTrue(attempt.rowId() > 0);
        assertEquals(1, scalar(dataSource, "SELECT reserved FROM daily_budget"));

        // 在「预算预留状态转移」这一步注入 SQL 异常，模拟数据库写入中断
        FaultyDataSource faulty = new FaultyDataSource(dataSource);
        faulty.failOn("UPDATE budget_reservation SET state");
        DeliveryLedgerService faultyLedger = new DeliveryLedgerService(faulty, "local", "Asia/Shanghai");

        boolean settled = faultyLedger.settleAttempt(confirmedRequest(attempt.rowId(), reservation.reservationId()));

        assertFalse(settled, "写入中断时 settleAttempt 必须返回 false，不能伪造成功");
        // ===== 事务回滚：文字/事件/预算/流水都不能有半写状态 =====
        assertEquals("PENDING", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()),
                "结算失败必须回滚 attempt 终态（A-05 原子性）");
        assertNull(scalarString(dataSource,
                "SELECT finished_at FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals(1, scalar(dataSource, "SELECT reserved FROM daily_budget"),
                "回滚后聚合 reserved 必须保持原值");
        assertEquals(0, scalar(dataSource, "SELECT consumed FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT uncertain FROM daily_budget"));
        assertEquals("RESERVED", reservationState(reservation.reservationId()),
                "回滚后逐笔预留必须仍是 RESERVED");
        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE event_type = 'TEXT_CONFIRMED'"),
                "回滚后不得留下半写的文字确认事件");
        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE event_type = 'BUDGET_CONFIRMED'"),
                "回滚后不得留下半写的预算结算事件");

        // ===== 恢复：下一次启动的 reconcile 把遗留 RESERVED 按 UNKNOWN 回收 =====
        faulty.clearFault();
        BudgetService afterRestart = budgetService(dataSource, true, 3);
        int processed = afterRestart.reconcileStaleReservations("run-next");
        assertEquals(1, processed, "回滚后的遗留预留在下次启动应被回收");
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("CRASH_RECOVERY", scalarString(dataSource,
                "SELECT error_code FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("UNKNOWN", reservationState(reservation.reservationId()));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));
        assertEquals(1, scalar(dataSource, "SELECT uncertain FROM daily_budget"));
    }

    @Test
    void successfulSettleIsIdempotentOnRetryAndLeavesOtherReservationsUntouched() {
        DeliveryLedgerService ledger = ledgerService(dataSource);
        BudgetService.Reservation first =
                budgetService.reserveFirstContact("run-a", "JOB-A", "A");
        BudgetService.Reservation second =
                budgetService.reserveFirstContact("run-b", "JOB-B", "B");
        assertTrue(first.granted() && second.granted());
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-a", DeliveryKind.FIRST_CONTACT, "JOB-A", "USER-A",
                "A", "示例科技", "https://www.zhipin.com/job_detail/a.html",
                "snapshot-hash", "message-hash", null, first.reservationId());

        DeliveryLedgerService.SettlementRequest request = confirmedRequest(attempt.rowId(), first.reservationId());
        assertTrue(ledger.settleAttempt(request), "首次结算应成功");
        assertTrue(ledger.settleAttempt(request), "已结算的 attempt 重试应幂等返回 true");
        // 用不同预算动作重试也不能二次结算
        DeliveryLedgerService.SettlementRequest conflictingRetry = new DeliveryLedgerService.SettlementRequest(
                attempt.rowId(), TextSendStatus.CONFIRMED, "DOM", "重试", null,
                ImageSendStatus.NOT_ATTEMPTED, null, null,
                first.reservationId(), DeliveryLedgerService.BudgetSettlement.RELEASE, null, "重试");
        assertTrue(ledger.settleAttempt(conflictingRetry), "已结算 attempt 的重试必须是幂等 no-op");

        assertEquals(1, scalar(dataSource, "SELECT consumed FROM daily_budget"), "不得重复记 consumed");
        assertEquals(1, scalar(dataSource, "SELECT reserved FROM daily_budget"),
                "重试不能释放/结算其他预留（B 仍应 reserved=1）");
        assertEquals(0, scalar(dataSource, "SELECT uncertain FROM daily_budget"));
        assertEquals("CONFIRMED", reservationState(first.reservationId()));
        assertEquals("RESERVED", reservationState(second.reservationId()), "B 的预留不得被影响");
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE event_type = 'TEXT_CONFIRMED'"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_event WHERE event_type = 'BUDGET_CONFIRMED'"));
    }

    private static DeliveryLedgerService.SettlementRequest confirmedRequest(long attemptRowId, String reservationId) {
        return new DeliveryLedgerService.SettlementRequest(
                attemptRowId, TextSendStatus.CONFIRMED, "DOM_TEXT_MESSAGE", "本人新消息出现", null,
                ImageSendStatus.NOT_ATTEMPTED, null, null,
                reservationId, DeliveryLedgerService.BudgetSettlement.CONFIRM, null, "本人新消息出现");
    }

    private String reservationState(String reservationId) {
        return scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservationId);
    }

    /**
     * 可注入 SQL 失败的 DataSource 代理：只在匹配指定 SQL 片段的 PreparedStatement 上让 execute* 抛异常，
     * 其余读写全部透传。用于验证 settleAttempt 的事务边界。
     */
    private static final class FaultyDataSource implements DataSource {

        private final DataSource delegate;
        private volatile String failingSqlFragment;

        FaultyDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void failOn(String sqlFragment) {
            this.failingSqlFragment = sqlFragment;
        }

        void clearFault() {
            this.failingSqlFragment = null;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrapConnection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrapConnection(delegate.getConnection(username, password));
        }

        private Connection wrapConnection(Connection real) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(real, method, args);
                        String fragment = failingSqlFragment;
                        if (fragment != null && result instanceof PreparedStatement statement
                                && args != null && args.length > 0 && args[0] instanceof String sql
                                && sql.contains(fragment)) {
                            return wrapFailingStatement(statement);
                        }
                        return result;
                    });
        }

        private static PreparedStatement wrapFailingStatement(PreparedStatement real) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        if (method.getName().startsWith("execute")) {
                            throw new SQLException("injected settlement failure");
                        }
                        return invoke(real, method, args);
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("faulty-datasource");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
