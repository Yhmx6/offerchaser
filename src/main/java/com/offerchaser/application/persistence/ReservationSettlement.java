package com.offerchaser.application.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 逐笔预算预留的状态机与 daily_budget 聚合更新 SQL。
 * <p>
 * {@link BudgetService} 与 {@link DeliveryLedgerService} 共用这里的语义，保证「结算」在两处实现一致：
 * 预留状态只能是 RESERVED → CONFIRMED / UNKNOWN / RELEASED 的<b>条件转移</b>，
 * 且只有转移成功（受影响行数 = 1）才允许更新 daily_budget 聚合，从而保证重复结算幂等、
 * 不会动到其他预留的额度。
 * <p>
 * 所有方法都要求调用方传入已开启事务的 {@link Connection}，由调用方负责 commit / rollback。
 */
final class ReservationSettlement {

    static final String STATE_RESERVED = "RESERVED";
    static final String STATE_CONFIRMED = "CONFIRMED";
    static final String STATE_UNKNOWN = "UNKNOWN";
    static final String STATE_RELEASED = "RELEASED";

    private ReservationSettlement() {
    }

    /**
     * 条件状态转移：只有当前状态为 RESERVED 时才会成功（version+1、settled_at 写入）。
     *
     * @return 受影响行数：1=本次完成转移；0=已被结算过（幂等 no-op）或预留不存在
     */
    static int transition(Connection connection, String reservationId, String targetState, String now)
            throws SQLException {
        return transitionFrom(connection, reservationId, STATE_RESERVED, targetState, now);
    }

    /**
     * 条件状态转移：只有当前状态等于 {@code requiredState} 时才会成功（version+1、settled_at 写入）。
     * <p>
     * 用于人工核对补偿：UNKNOWN → CONFIRMED / UNKNOWN → RELEASED 也必须是条件转移，避免重复核对
     * 重复调整 daily_budget 聚合。
     *
     * @return 受影响行数：1=本次完成转移；0=状态已变化（幂等 no-op）或预留不存在
     */
    static int transitionFrom(Connection connection, String reservationId, String requiredState,
                              String targetState, String now) throws SQLException {
        String sql = "UPDATE budget_reservation SET state = ?, version = version + 1, settled_at = ? " +
                "WHERE reservation_id = ? AND state = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetState);
            statement.setString(2, now);
            statement.setString(3, reservationId);
            statement.setString(4, requiredState);
            return statement.executeUpdate();
        }
    }

    /** 按 attempt_id 反查该次投递使用的逐笔预留（最近一条）；没有返回 null。 */
    static ReservationRow findByAttemptId(Connection connection, String attemptId) throws SQLException {
        if (attemptId == null || attemptId.isBlank()) {
            return null;
        }
        String sql = "SELECT id, reservation_id, attempt_id, budget_id, account, action_type, budget_date, state " +
                "FROM budget_reservation WHERE attempt_id = ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attemptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRow(resultSet) : null;
            }
        }
    }

    /** 读取预留行；不存在返回 null。 */
    static ReservationRow find(Connection connection, String reservationId) throws SQLException {
        if (reservationId == null || reservationId.isBlank()) {
            return null;
        }
        String sql = "SELECT id, reservation_id, attempt_id, budget_id, account, action_type, budget_date, state " +
                "FROM budget_reservation WHERE reservation_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reservationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRow(resultSet) : null;
            }
        }
    }

    private static ReservationRow readRow(ResultSet resultSet) throws SQLException {
        return new ReservationRow(
                resultSet.getLong("id"),
                resultSet.getString("reservation_id"),
                resultSet.getString("attempt_id"),
                resultSet.getLong("budget_id"),
                resultSet.getString("account"),
                resultSet.getString("action_type"),
                resultSet.getString("budget_date"),
                resultSet.getString("state"));
    }

    /**
     * 按结算目标更新 daily_budget 聚合，只有 reserved &gt; 0 才生效。
     * 必须在 {@link #transition} 返回 1 之后调用。
     *
     * @return 是否命中（false 表示聚合计数与预留表不一致，属于异常状态，需要告警）
     */
    static boolean applyAggregate(Connection connection, long budgetRowId, String targetState, String now)
            throws SQLException {
        return executeAggregate(connection, aggregateSql(targetState), budgetRowId, now) == 1;
    }

    /** 旧接口（按 daily_budget 行 id 直接调整）的聚合兜底更新，不涉及逐笔预留表。 */
    static int applyLegacyAggregate(Connection connection, long budgetRowId, String targetState, String now)
            throws SQLException {
        return executeAggregate(connection, aggregateSql(targetState), budgetRowId, now);
    }

    /**
     * 人工核对补偿时按「原状态 → 目标状态」更新 daily_budget 聚合（B1）：
     * <ul>
     *   <li>RESERVED → CONFIRMED/UNKNOWN/RELEASED：复用标准预结算逻辑；</li>
     *   <li>UNKNOWN → CONFIRMED：uncertain-1、consumed+1（只一次）；</li>
     *   <li>UNKNOWN → RELEASED：uncertain-1（只一次）。</li>
     * </ul>
     * 必须在预留状态条件转移成功（受影响行数=1）之后调用。
     *
     * @return 是否命中；false 表示聚合计数与预留表不一致（需要告警并回滚整体事务）
     */
    static boolean applyAggregateTransition(Connection connection, long budgetRowId,
                                            String fromState, String targetState, String now)
            throws SQLException {
        String sql = switch (fromState + "->" + targetState) {
            case "RESERVED->CONFIRMED" -> aggregateSql(STATE_CONFIRMED);
            case "RESERVED->UNKNOWN" -> aggregateSql(STATE_UNKNOWN);
            case "RESERVED->RELEASED" -> aggregateSql(STATE_RELEASED);
            case "UNKNOWN->CONFIRMED" ->
                    "UPDATE daily_budget SET uncertain = uncertain - 1, consumed = consumed + 1, updated_at = ? " +
                            "WHERE id = ? AND uncertain > 0";
            case "UNKNOWN->RELEASED" ->
                    "UPDATE daily_budget SET uncertain = uncertain - 1, updated_at = ? " +
                            "WHERE id = ? AND uncertain > 0";
            default -> throw new IllegalArgumentException(
                    "不支持的人工核对聚合转移：" + fromState + "->" + targetState);
        };
        return executeAggregate(connection, sql, budgetRowId, now) == 1;
    }

    /** 某天某动作的待结算预留数量。 */
    static int countPending(Connection connection, String account, String budgetDate, String actionType)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM budget_reservation " +
                "WHERE account = ? AND budget_date = ? AND state = 'RESERVED'" +
                (actionType == null ? "" : " AND action_type = ?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setString(2, budgetDate);
            if (actionType != null) {
                statement.setString(3, actionType);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private static String aggregateSql(String targetState) {
        return switch (targetState) {
            case STATE_CONFIRMED ->
                    "UPDATE daily_budget SET reserved = reserved - 1, consumed = consumed + 1, updated_at = ? " +
                            "WHERE id = ? AND reserved > 0";
            case STATE_UNKNOWN ->
                    "UPDATE daily_budget SET reserved = reserved - 1, uncertain = uncertain + 1, updated_at = ? " +
                            "WHERE id = ? AND reserved > 0";
            case STATE_RELEASED ->
                    "UPDATE daily_budget SET reserved = reserved - 1, updated_at = ? " +
                            "WHERE id = ? AND reserved > 0";
            default -> throw new IllegalArgumentException("未知的结算目标状态：" + targetState);
        };
    }

    private static int executeAggregate(Connection connection, String sql, long budgetRowId, String now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, now);
            statement.setLong(2, budgetRowId);
            return statement.executeUpdate();
        }
    }

    /** 预留表行快照。 */
    record ReservationRow(long id, String reservationId, String attemptId, long budgetRowId,
                          String account, String actionType, String budgetDate, String state) {
    }
}
