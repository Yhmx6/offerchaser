package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 每日投递预算服务（首次沟通 / 跟进分别独立计数），A-05 起改为「逐笔预留 + 原子结算」。
 * <p>
 * 预留：在<b>同一个数据库事务</b>内完成 确保 daily_budget 行 → 条件
 * {@code UPDATE daily_budget SET reserved = reserved + 1 WHERE ... AND (consumed + reserved + uncertain) < budget_limit}
 * → 插入 {@code budget_reservation(state='RESERVED')}，受影响行数 ≠ 1 则拒绝且不落预留。
 * <p>
 * 结算：每个 reservationId 单独一个短事务，先条件
 * {@code UPDATE budget_reservation SET state=?, version=version+1, settled_at=? WHERE reservation_id=? AND state='RESERVED'}，
 * 只有受影响行数 = 1 才更新 daily_budget 聚合；重复调用是幂等 no-op，不会动到其他预留的额度。
 * <p>
 * 失败策略：结果未知（UNKNOWN）按「已占用」处理（计入 uncertain），宁可少发也不超发。
 */
@Service
@Slf4j
public class BudgetService {

    /**
     * 一次逐笔预算预留的结果。
     * <p>
     * granted=false 时 {@code reservationId} 为 null，reason 说明拒绝原因。
     */
    public record Reservation(String reservationId, long budgetRowId, boolean granted, String reason,
                              int limit, int consumed, int reserved, int uncertain) {

        /**
         * @deprecated 兼容旧调用：返回 daily_budget 聚合行 id。新代码请用 {@link #reservationId()} 结算。
         */
        @Deprecated
        public long id() {
            return budgetRowId;
        }
    }

    private static final String ACTION_FIRST_CONTACT = "FIRST_CONTACT";
    private static final String ACTION_FOLLOWUP = "FOLLOWUP";
    private static final String DENIED_FIRST_CONTACT = "今日首次沟通预算已用尽";
    private static final String DENIED_FOLLOWUP = "今日跟进沟通额度已用尽";

    /** SQLite 写锁冲突（SQLITE_BUSY）时的最大重试次数与间隔。 */
    private static final int MAX_BUSY_RETRIES = 3;
    private static final long BUSY_RETRY_INTERVAL_MS = 100L;

    private final DataSource dataSource;
    private final boolean budgetEnabled;
    private final int dailyLimit;
    private final int followupDailyLimit;
    private final java.time.ZoneId zone;
    private final String account;

    public BudgetService(DataSource dataSource,
                         @Value("${offerchaser.boss.budget-enabled:true}") boolean budgetEnabled,
                         @Value("${offerchaser.boss.daily-limit:145}") int dailyLimit,
                         @Value("${offerchaser.boss.followup-daily-limit:30}") int followupDailyLimit,
                         @Value("${offerchaser.boss.budget-timezone:Asia/Shanghai}") String timezone,
                         @Value("${offerchaser.boss.budget-account:local}") String account) {
        this.dataSource = dataSource;
        this.budgetEnabled = budgetEnabled;
        this.dailyLimit = dailyLimit;
        this.followupDailyLimit = followupDailyLimit;
        this.zone = PersistenceSupport.zone(timezone);
        this.account = PersistenceSupport.defaultIfBlank(account, "local");
    }

    // ==================== 逐笔预留 ====================

    /** 预留一次首次沟通额度（单事务：预算行 + reserved+1 + 预留记录一起提交）。 */
    public Reservation reserveFirstContact(String runId, String encryptId, String jobName) {
        return reserve(ACTION_FIRST_CONTACT, dailyLimit, DENIED_FIRST_CONTACT, runId, encryptId, jobName);
    }

    /** 预留一次跟进额度（与首次沟通预算独立）。 */
    public Reservation reserveFollowup(String runId, String encryptId, String jobName) {
        return reserve(ACTION_FOLLOWUP, followupDailyLimit, DENIED_FOLLOWUP, runId, encryptId, jobName);
    }

    /**
     * 确认发送成功：预留 RESERVED→CONFIRMED，daily_budget reserved-1 / consumed+1。
     *
     * @return true=本次完成状态转移；false=已结算过（幂等 no-op）或写库失败
     */
    public boolean confirmSent(String reservationId) {
        return settleReservation(reservationId, ReservationSettlement.STATE_CONFIRMED, "CONFIRM");
    }

    /**
     * 结果未知：预留 RESERVED→UNKNOWN，daily_budget reserved-1 / uncertain+1（保守占额）。
     *
     * @return true=本次完成状态转移；false=已结算过（幂等 no-op）或写库失败
     */
    public boolean markUnknown(String reservationId) {
        return settleReservation(reservationId, ReservationSettlement.STATE_UNKNOWN, "UNKNOWN");
    }

    /**
     * 释放预留（未发送/发送前失败）：预留 RESERVED→RELEASED，daily_budget reserved-1。
     * 重复释放同一个 reservationId 是幂等 no-op，不会释放其他预留的额度。
     *
     * @return true=本次完成状态转移；false=已结算过（幂等 no-op）或写库失败
     */
    public boolean release(String reservationId, String reason) {
        if (reason != null) {
            log.info("释放预算预留 | reservationId={} | 原因={}", reservationId, reason);
        }
        return settleReservation(reservationId, ReservationSettlement.STATE_RELEASED, "RELEASE");
    }

    /**
     * 回收上次运行遗留的预留（客户端崩溃恢复）。
     * <p>
     * 1) 逐笔预留：{@code state='RESERVED'} 且（attempt_id 为空 或 关联 attempt 的 run_id ≠ 当前 run）
     * → 按 UNKNOWN 结算（reserved-1 / uncertain+1），关联 attempt 若为 PENDING 则标 UNKNOWN +
     * {@code CRASH_RECOVERY} + TEXT_UNKNOWN 事件；
     * 2) 兼容历史数据：没有对应 reservation 的 PENDING attempt 继续走原「按 budget_id 聚合兜底」逻辑。
     * 全部幂等。
     *
     * @param currentRunId 当前这轮任务的 runId；为空时视为所有遗留预留都需要回收
     * @return 处理条数（回收的预留数 + 历史兜底 attempt 数）
     */
    public int reconcileStaleReservations(String currentRunId) {
        String effectiveRunId = PersistenceSupport.trimToNull(currentRunId);
        try {
            return inTransaction(connection -> {
                String now = PersistenceSupport.now();
                int processed = reconcileStaleReservationRows(connection, effectiveRunId, now);
                processed += reconcileLegacyPendingAttempts(connection, effectiveRunId, now);
                if (processed > 0) {
                    log.warn("遗留预留回收完成：{} 条上次运行的未确认发送记录已按结果未知处理，并占用今日额度",
                            processed);
                }
                return processed;
            });
        } catch (SQLException e) {
            log.error("回收遗留预算预留失败：{}", e.getMessage(), e);
            return 0;
        }
    }

    // ==================== 旧接口兼容（Boss 不再调用） ====================

    /**
     * @deprecated 旧版按 daily_budget 行 id 直接结算，不涉及逐笔预留；仅保留给旧测试/旧调用。
     */
    @Deprecated
    public boolean confirmSent(long budgetRowId) {
        return changeLegacyAggregate(budgetRowId, ReservationSettlement.STATE_CONFIRMED, "CONFIRM(legacy)");
    }

    /**
     * @deprecated 旧版按 daily_budget 行 id 直接结算，不涉及逐笔预留；仅保留给旧测试/旧调用。
     */
    @Deprecated
    public boolean markUnknown(long budgetRowId) {
        return changeLegacyAggregate(budgetRowId, ReservationSettlement.STATE_UNKNOWN, "UNKNOWN(legacy)");
    }

    /**
     * @deprecated 旧版按 daily_budget 行 id 直接释放，不涉及逐笔预留；仅保留给旧测试/旧调用。
     */
    @Deprecated
    public boolean release(long budgetRowId, String reason) {
        if (reason != null) {
            log.info("释放预算预留（legacy）| budgetRowId={} | 原因={}", budgetRowId, reason);
        }
        return changeLegacyAggregate(budgetRowId, ReservationSettlement.STATE_RELEASED, "RELEASE(legacy)");
    }

    // ==================== 查询 ====================

    /** 首次沟通今日预算是否已耗尽（用于任务启动预检/熔断）。 */
    public boolean isFirstContactBudgetDepleted() {
        return isDepleted(ACTION_FIRST_CONTACT, dailyLimit);
    }

    /**
     * 今日预算汇总：首次沟通与跟进分别给出 limit/consumed/reserved/uncertain/remaining/pendingReservations，
     * 顶层额外返回 {@code pendingReservations}（当天两类的待结算预留总数）。
     */
    public Map<String, Object> todaySummary() {
        String budgetDate = PersistenceSupport.today(zone);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", budgetDate);
        summary.put("account", account);
        summary.put("enabled", budgetEnabled);
        summary.put("timezone", zone.getId());
        summary.put("firstContact", actionSummary(ACTION_FIRST_CONTACT, budgetDate, dailyLimit));
        summary.put("followup", actionSummary(ACTION_FOLLOWUP, budgetDate, followupDailyLimit));
        summary.put("pendingReservations", pendingReservations(null, budgetDate));
        return summary;
    }

    // ==================== 预留核心逻辑 ====================

    private Reservation reserve(String actionType, int configuredLimit, String deniedReason,
                                String runId, String encryptId, String jobName) {
        String budgetDate = PersistenceSupport.today(zone);
        int effectiveLimit = effectiveLimit(configuredLimit);
        try {
            return inTransaction(connection -> {
                String now = PersistenceSupport.now();
                ensureBudgetRow(connection, actionType, budgetDate, effectiveLimit, now);
                int updated = executeUpdate(connection, reserveSql(), now, account, budgetDate, actionType);
                if (updated != 1) {
                    // 行刚补齐或并发竞争：按契约重试一次，仍失败即视为额度已用尽
                    ensureBudgetRow(connection, actionType, budgetDate, effectiveLimit, now);
                    updated = executeUpdate(connection, reserveSql(), now, account, budgetDate, actionType);
                }
                BudgetRow row = selectBudgetRow(connection, actionType, budgetDate);
                if (updated == 1) {
                    String reservationId = UUID.randomUUID().toString();
                    insertReservation(connection, reservationId, row.id(), actionType, budgetDate, now);
                    BudgetRow after = row;
                    log.debug("预算预留成功 | action={} | runId={} | encryptId={} | jobName={} | 已用={}",
                            actionType, runId, encryptId, jobName,
                            after.consumed() + after.reserved() + after.uncertain());
                    return new Reservation(reservationId, row.id(), true, null, row.limit(),
                            row.consumed(), row.reserved(), row.uncertain());
                }
                BudgetRow deniedRow = selectBudgetRow(connection, actionType, budgetDate);
                int limit = deniedRow == null ? effectiveLimit : deniedRow.limit();
                int consumed = deniedRow == null ? 0 : deniedRow.consumed();
                int reserved = deniedRow == null ? 0 : deniedRow.reserved();
                int uncertain = deniedRow == null ? 0 : deniedRow.uncertain();
                log.info("预算预留被拒绝 | action={} | runId={} | encryptId={} | jobName={} | 已用={}/{} | 原因={}",
                        actionType, runId, encryptId, jobName, consumed + reserved + uncertain, limit, deniedReason);
                return new Reservation(null, deniedRow == null ? 0 : deniedRow.id(), false, deniedReason,
                        limit, consumed, reserved, uncertain);
            });
        } catch (SQLException e) {
            log.error("预算预留失败 | action={} | runId={} | encryptId={}：{}",
                    actionType, runId, encryptId, e.getMessage(), e);
            return new Reservation(null, 0, false, "预算服务异常：" + e.getMessage(),
                    effectiveLimit, 0, 0, 0);
        }
    }

    private String reserveSql() {
        return "UPDATE daily_budget SET reserved = reserved + 1, updated_at = ? " +
                "WHERE account = ? AND budget_date = ? AND action_type = ? " +
                "AND (budget_limit <= 0 OR (consumed + reserved + uncertain) < budget_limit)";
    }

    /** 保证当天的预算行存在，并把上限同步为最新配置（配置变更当天生效）。 */
    private void ensureBudgetRow(Connection connection, String actionType, String budgetDate,
                                 int effectiveLimit, String now) throws SQLException {
        String insertSql = "INSERT OR IGNORE INTO daily_budget" +
                "(account, budget_date, action_type, budget_limit, consumed, reserved, uncertain, updated_at) " +
                "VALUES(?, ?, ?, ?, 0, 0, 0, ?)";
        executeUpdate(connection, insertSql, account, budgetDate, actionType, effectiveLimit, now);
        String syncLimitSql = "UPDATE daily_budget SET budget_limit = ?, updated_at = ? " +
                "WHERE account = ? AND budget_date = ? AND action_type = ? AND budget_limit <> ?";
        executeUpdate(connection, syncLimitSql, effectiveLimit, now, account, budgetDate, actionType, effectiveLimit);
    }

    private void insertReservation(Connection connection, String reservationId, long budgetRowId,
                                   String actionType, String budgetDate, String now) throws SQLException {
        String sql = "INSERT INTO budget_reservation" +
                "(reservation_id, attempt_id, budget_id, account, action_type, budget_date, " +
                "state, version, created_at, settled_at) " +
                "VALUES(?, NULL, ?, ?, ?, ?, 'RESERVED', 0, ?, NULL)";
        executeUpdate(connection, sql, reservationId, budgetRowId, account, actionType, budgetDate, now);
    }

    /**
     * 结算单笔预留：先条件状态转移，只有转移成功才更新 daily_budget 聚合。
     * 每笔结算各自一个短事务。
     */
    private boolean settleReservation(String reservationId, String targetState, String actionLabel) {
        if (PersistenceSupport.trimToNull(reservationId) == null) {
            log.warn("预算结算参数无效，已忽略 | action={} | reservationId={}", actionLabel, reservationId);
            return false;
        }
        try {
            return inTransaction(connection -> {
                String now = PersistenceSupport.now();
                int updated = ReservationSettlement.transition(connection, reservationId, targetState, now);
                if (updated != 1) {
                    // 合同要求：受影响行数 ≠ 1 即幂等 no-op 返回 false，且不得更新聚合
                    log.info("预算结算幂等跳过（预留已结算或不存在）| action={} | reservationId={}",
                            actionLabel, reservationId);
                    return false;
                }
                ReservationSettlement.ReservationRow row =
                        ReservationSettlement.find(connection, reservationId);
                if (row == null) {
                    throw new SQLException("预留刚转移成功却读不到记录：reservationId=" + reservationId);
                }
                boolean aggregated = ReservationSettlement.applyAggregate(
                        connection, row.budgetRowId(), targetState, now);
                if (!aggregated) {
                    throw new SQLException("daily_budget 聚合更新失败（reserved<=0）：reservationId="
                            + reservationId + " | state=" + targetState);
                }
                log.info("预算预留已结算 | action={} | reservationId={} | state={} | budgetRowId={}",
                        actionLabel, reservationId, targetState, row.budgetRowId());
                return true;
            });
        } catch (SQLException e) {
            log.error("预算结算失败，本次结算已回滚 | action={} | reservationId={}：{}",
                    actionLabel, reservationId, e.getMessage(), e);
            return false;
        }
    }

    /** 旧接口：按 daily_budget 行 id 直接调整聚合（不涉及逐笔预留表）。 */
    private boolean changeLegacyAggregate(long budgetRowId, String targetState, String actionLabel) {
        if (budgetRowId <= 0) {
            log.debug("预算预留操作忽略：budgetRowId={} | action={}", budgetRowId, actionLabel);
            return false;
        }
        try {
            return inTransaction(connection -> {
                int updated = ReservationSettlement.applyLegacyAggregate(
                        connection, budgetRowId, targetState, PersistenceSupport.now());
                if (updated == 0) {
                    log.warn("预算聚合更新未命中（可能已处理或 id 无效）| action={} | budgetRowId={}",
                            actionLabel, budgetRowId);
                    return false;
                }
                return true;
            });
        } catch (SQLException e) {
            log.error("更新预算聚合失败 | action={} | budgetRowId={}：{}",
                    actionLabel, budgetRowId, e.getMessage(), e);
            return false;
        }
    }

    // ==================== 遗留预留回收 ====================

    /** 回收逐笔预留表中 RESERVED 的遗留记录（返回处理条数）。 */
    private int reconcileStaleReservationRows(Connection connection, String currentRunId, String now)
            throws SQLException {
        String sql = "SELECT r.reservation_id, r.budget_id, r.attempt_id, " +
                "a.id AS attempt_row_id, a.run_id AS attempt_run_id, a.text_status AS attempt_text_status " +
                "FROM budget_reservation r " +
                "LEFT JOIN delivery_attempt a ON a.attempt_id = r.attempt_id " +
                "WHERE r.state = 'RESERVED' " +
                (currentRunId == null ? "" : "AND (r.attempt_id IS NULL OR a.id IS NULL OR a.run_id <> ?) ") +
                "ORDER BY r.id";
        List<StaleReservation> staleReservations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (currentRunId != null) {
                statement.setString(1, currentRunId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    staleReservations.add(new StaleReservation(
                            resultSet.getString("reservation_id"),
                            resultSet.getLong("budget_id"),
                            resultSet.getString("attempt_id"),
                            resultSet.getObject("attempt_row_id") == null
                                    ? null : resultSet.getLong("attempt_row_id"),
                            resultSet.getString("attempt_run_id"),
                            resultSet.getString("attempt_text_status")));
                }
            }
        }
        int processed = 0;
        for (StaleReservation stale : staleReservations) {
            int transitioned = ReservationSettlement.transition(
                    connection, stale.reservationId(), ReservationSettlement.STATE_UNKNOWN, now);
            if (transitioned != 1) {
                continue;
            }
            if (stale.attemptRowId() != null && "PENDING".equals(stale.attemptTextStatus())) {
                markAttemptUnknown(connection, stale.attemptRowId(), now);
                appendCrashRecoveryEvent(connection, stale.attemptId(), stale.attemptRunId(), now);
            }
            if (!ReservationSettlement.applyAggregate(
                    connection, stale.budgetRowId(), ReservationSettlement.STATE_UNKNOWN, now)) {
                // 明细已转 UNKNOWN 但聚合未更新 = 明细与汇总不一致，必须回滚本次回收整体重试，
                // 不能只记日志后提交（否则额度会少算，违背"不确定就保守占额"）。
                throw new SQLException("回收遗留预留失败：daily_budget 聚合更新未命中（reserved<=0）"
                        + " | reservationId=" + stale.reservationId() + " | budgetRowId=" + stale.budgetRowId());
            }
            processed++;
        }
        return processed;
    }

    /** 兼容历史数据：没有 reservation 记录的 PENDING attempt 继续按 budget_id 聚合兜底回收。 */
    private int reconcileLegacyPendingAttempts(Connection connection, String currentRunId, String now)
            throws SQLException {
        String sql = "SELECT a.id, a.attempt_id, a.run_id, a.budget_id FROM delivery_attempt a " +
                "WHERE a.text_status = 'PENDING' AND a.budget_id > 0 " +
                (currentRunId == null ? "" : "AND a.run_id <> ? ") +
                "AND NOT EXISTS (SELECT 1 FROM budget_reservation r WHERE r.attempt_id = a.attempt_id) " +
                "ORDER BY a.id";
        List<StaleAttempt> staleAttempts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (currentRunId != null) {
                statement.setString(1, currentRunId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    staleAttempts.add(new StaleAttempt(
                            resultSet.getLong("id"),
                            resultSet.getString("attempt_id"),
                            resultSet.getString("run_id"),
                            resultSet.getLong("budget_id")));
                }
            }
        }
        int processed = 0;
        for (StaleAttempt attempt : staleAttempts) {
            if (markAttemptUnknown(connection, attempt.rowId(), now) == 0) {
                continue;
            }
            ReservationSettlement.applyLegacyAggregate(
                    connection, attempt.budgetId(), ReservationSettlement.STATE_UNKNOWN, now);
            appendCrashRecoveryEvent(connection, attempt.attemptId(), attempt.runId(), now);
            processed++;
        }
        return processed;
    }

    private int markAttemptUnknown(Connection connection, long attemptRowId, String now) throws SQLException {
        String sql = "UPDATE delivery_attempt SET text_status = 'UNKNOWN', error_code = 'CRASH_RECOVERY', " +
                "error_detail = COALESCE(error_detail, '启动时发现上次运行遗留的未确认发送记录'), finished_at = ? " +
                "WHERE id = ? AND text_status = 'PENDING'";
        return executeUpdate(connection, sql, now, attemptRowId);
    }

    private void appendCrashRecoveryEvent(Connection connection, String attemptId, String runId, String now)
            throws SQLException {
        String sql = "INSERT INTO delivery_event(attempt_id, run_id, event_type, evidence_level, detail, occurred_at) " +
                "VALUES(?, ?, 'TEXT_UNKNOWN', 'CRASH_RECOVERY', ?, ?)";
        executeUpdate(connection, sql, attemptId, runId,
                "启动恢复：上次运行遗留的未确认发送记录，按结果未知处理并占用预算", now);
    }

    // ==================== 查询实现 ====================

    private boolean isDepleted(String actionType, int configuredLimit) {
        int limit = effectiveLimit(configuredLimit);
        if (limit <= 0) {
            return false;
        }
        try {
            BudgetRow row = selectBudgetRow(actionType, PersistenceSupport.today(zone));
            if (row == null) {
                return false;
            }
            return row.consumed() + row.reserved() + row.uncertain() >= limit;
        } catch (SQLException e) {
            log.error("读取{}预算状态失败：{}", actionType, e.getMessage(), e);
            return false;
        }
    }

    private Map<String, Object> actionSummary(String actionType, String budgetDate, int configuredLimit) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int fallbackLimit = effectiveLimit(configuredLimit);
        try {
            BudgetRow row = selectBudgetRow(actionType, budgetDate);
            int limit = row == null ? fallbackLimit : row.limit();
            int consumed = row == null ? 0 : row.consumed();
            int reserved = row == null ? 0 : row.reserved();
            int uncertain = row == null ? 0 : row.uncertain();
            int used = consumed + reserved + uncertain;
            summary.put("limit", limit);
            summary.put("consumed", consumed);
            summary.put("reserved", reserved);
            summary.put("uncertain", uncertain);
            summary.put("used", used);
            summary.put("remaining", limit <= 0 ? -1 : Math.max(0, limit - used));
            summary.put("pendingReservations", pendingReservations(actionType, budgetDate));
        } catch (SQLException e) {
            summary.put("limit", fallbackLimit);
            summary.put("consumed", 0);
            summary.put("reserved", 0);
            summary.put("uncertain", 0);
            summary.put("used", 0);
            summary.put("remaining", fallbackLimit <= 0 ? -1 : fallbackLimit);
            summary.put("pendingReservations", 0);
            summary.put("error", "预算读取失败：" + e.getMessage());
            log.error("读取{}预算汇总失败：{}", actionType, e.getMessage(), e);
        }
        return summary;
    }

    private int pendingReservations(String actionType, String budgetDate) {
        try (Connection connection = dataSource.getConnection()) {
            return ReservationSettlement.countPending(connection, account, budgetDate, actionType);
        } catch (SQLException e) {
            log.error("读取待结算预留数量失败：{}", e.getMessage(), e);
            return 0;
        }
    }

    /** 预算上限：开关关闭或配置 <= 0 时用 -1 表示不限。 */
    private int effectiveLimit(int configuredLimit) {
        return budgetEnabled && configuredLimit > 0 ? configuredLimit : -1;
    }

    // ==================== 数据库工具 ====================

    private BudgetRow selectBudgetRow(String actionType, String budgetDate) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return selectBudgetRow(connection, actionType, budgetDate);
        }
    }

    private BudgetRow selectBudgetRow(Connection connection, String actionType, String budgetDate)
            throws SQLException {
        String sql = "SELECT id, budget_limit, consumed, reserved, uncertain FROM daily_budget " +
                "WHERE account = ? AND budget_date = ? AND action_type = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setString(2, budgetDate);
            statement.setString(3, actionType);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new BudgetRow(resultSet.getLong("id"), resultSet.getInt("budget_limit"),
                        resultSet.getInt("consumed"), resultSet.getInt("reserved"),
                        resultSet.getInt("uncertain"));
            }
        }
    }

    private int executeUpdate(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }

    /** 在单个事务中执行数据库操作；SQLITE_BUSY 时有界重试整个事务。 */
    private <T> T inTransaction(TransactionWork<T> work) throws SQLException {
        SQLException lastBusyError = null;
        for (int attempt = 1; attempt <= MAX_BUSY_RETRIES; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                boolean originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = work.execute(connection);
                    connection.commit();
                    return result;
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    if (isBusy(e) && attempt < MAX_BUSY_RETRIES) {
                        lastBusyError = e;
                        log.warn("数据库写入繁忙，{}ms 后重试整个事务（第 {} 次）：{}",
                                BUSY_RETRY_INTERVAL_MS, attempt, e.getMessage());
                        sleepQuietly();
                        continue;
                    }
                    throw e;
                } catch (RuntimeException e) {
                    rollbackQuietly(connection);
                    throw e;
                } finally {
                    restoreAutoCommit(connection, originalAutoCommit);
                }
            }
        }
        throw lastBusyError == null ? new SQLException("数据库事务执行失败") : lastBusyError;
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("事务回滚失败：{}", e.getMessage());
        }
    }

    private void restoreAutoCommit(Connection connection, boolean originalAutoCommit) {
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException ignore) {
            // 连接关闭时由连接池处理
        }
    }

    private boolean isBusy(SQLException error) {
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("busy") || lower.contains("locked");
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(BUSY_RETRY_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface TransactionWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private record BudgetRow(long id, int limit, int consumed, int reserved, int uncertain) {
    }

    private record StaleAttempt(long rowId, String attemptId, String runId, long budgetId) {
    }

    private record StaleReservation(String reservationId, long budgetRowId, String attemptId,
                                    Long attemptRowId, String attemptRunId, String attemptTextStatus) {
    }
}
