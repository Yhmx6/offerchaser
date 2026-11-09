package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * B1 人工核对服务：把「结果未知」的首次沟通记录人工判定为已发送 / 未发送 / 仍未知。
 * <p>
 * 所有状态、版本号、预算预留与审计行都在<b>单个数据库事务</b>内完成：
 * <ul>
 *   <li>attempt.version 做乐观锁（expectedVersion 不匹配 → 409 VERSION_CONFLICT）；</li>
 *   <li>SENT：PENDING/UNKNOWN/FAILED → CONFIRMED，并补偿预算（UNKNOWN→CONFIRMED：
 *       uncertain-1/consumed+1；RESERVED→CONFIRMED：reserved-1/consumed+1；无预留不动）；</li>
 *   <li>NOT_SENT：PENDING/UNKNOWN/FAILED → FAILED，预算 RESERVED→RELEASED（reserved-1）
 *       或 UNKNOWN→RELEASED（uncertain-1）；</li>
 *   <li>STILL_UNKNOWN：不改 text_status/预算，只记录 manual_resolution 并 version+1；</li>
 *   <li>已 CONFIRMED 再提交 SENT 是幂等成功（changed=false, idempotent=true）；</li>
 *   <li>idempotencyKey 重复提交返回首次结果，不重复改动；</li>
 *   <li>每次实际改动都写 manual_action 审计行（before/after text_status）。</li>
 * </ul>
 */
@Service
@Slf4j
public class AttemptResolutionService {

    /** 人工核对结果（controller 直接作为 data 返回）。 */
    public record ResolutionResult(String attemptId, String textStatus, String imageStatus, int version,
                                   String manualResolution, String resolvedAt,
                                   boolean changed, boolean idempotent) {
    }

    public static final String RESOLUTION_SENT = "SENT";
    public static final String RESOLUTION_NOT_SENT = "NOT_SENT";
    public static final String RESOLUTION_STILL_UNKNOWN = "STILL_UNKNOWN";

    private static final Set<String> SUPPORTED_RESOLUTIONS =
            Set.of(RESOLUTION_SENT, RESOLUTION_NOT_SENT, RESOLUTION_STILL_UNKNOWN);
    /** 只允许对「结果未定」的状态做 SENT / NOT_SENT 人工判定。 */
    private static final Set<String> MANUALLY_RESOLVABLE_TEXT_STATUSES = Set.of("PENDING", "UNKNOWN", "FAILED");
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_BUSY_RETRIES = 3;
    private static final long BUSY_RETRY_INTERVAL_MS = 100L;

    private final DataSource dataSource;

    public AttemptResolutionService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 对一条投递尝试做人工核对。
     *
     * @param attemptId       业务 attemptId
     * @param resolution      SENT / NOT_SENT / STILL_UNKNOWN（大小写不敏感，空白非法）
     * @param reason          人工核对说明，必填且不超过 500 字
     * @param expectedVersion 前端拿到的 attempt.version，用于乐观锁
     * @param idempotencyKey  可选幂等键；重复提交返回首次结果
     * @throws AttemptNotFoundException    attempt 不存在（404）
     * @throws VersionConflictException   version 冲突（409 VERSION_CONFLICT）
     * @throws AlreadyConfirmedException  已确认记录不允许回退（409 ALREADY_CONFIRMED）
     * @throws InvalidResolutionException 参数非法/状态不允许（400）
     */
    public ResolutionResult resolve(String attemptId, String resolution, String reason,
                                    int expectedVersion, String idempotencyKey) {
        String validAttemptId = requireAttemptId(attemptId);
        String validResolution = requireResolution(resolution);
        String validReason = requireReason(reason);
        if (expectedVersion < 0) {
            throw new InvalidResolutionException("expectedVersion 不能为负数");
        }
        String validKey = PersistenceSupport.trimToNull(idempotencyKey);
        ResolutionResult result = inTransaction(connection ->
                doResolve(connection, validAttemptId, validResolution, validReason, expectedVersion, validKey));
        log.info("人工核对完成 | attemptId={} | resolution={} | version={} | changed={} | idempotent={}",
                result.attemptId(), validResolution, result.version(), result.changed(), result.idempotent());
        return result;
    }

    // ==================== 事务主体 ====================

    private ResolutionResult doResolve(Connection connection, String attemptId, String resolution,
                                       String reason, int expectedVersion, String idempotencyKey)
            throws SQLException {
        // 1) 幂等键重放：返回首次结果，绝不重复改动
        if (idempotencyKey != null) {
            String originalAttemptId = findManualActionObjectId(connection, idempotencyKey);
            if (originalAttemptId != null) {
                AttemptRow original = findAttempt(connection, originalAttemptId);
                if (original != null) {
                    log.info("人工核对幂等命中 | idempotencyKey={} | attemptId={}", idempotencyKey, originalAttemptId);
                    return replay(original);
                }
            }
        }

        // 2) attempt 必须存在
        AttemptRow row = findAttempt(connection, attemptId);
        if (row == null) {
            throw new AttemptNotFoundException(attemptId);
        }

        // 3) 已 CONFIRMED 再提交 SENT：幂等成功（不改变版本、不写审计）
        if (RESOLUTION_SENT.equals(resolution) && "CONFIRMED".equals(row.textStatus())) {
            log.info("记录已是 CONFIRMED，SENT 幂等成功 | attemptId={} | version={}", attemptId, row.version());
            return replay(row);
        }

        // 4) 乐观锁版本校验（契约顺序：VERSION_CONFLICT 优先于 ALREADY_CONFIRMED）
        if (row.version() != expectedVersion) {
            throw new VersionConflictException(attemptId, row.version(), row.textStatus());
        }

        // 5) 已确认记录不允许回退成 未发送/仍未知
        if (!RESOLUTION_SENT.equals(resolution) && "CONFIRMED".equals(row.textStatus())) {
            throw new AlreadyConfirmedException(attemptId);
        }

        // 6) 状态是否允许该人工动作
        if ((RESOLUTION_SENT.equals(resolution) || RESOLUTION_NOT_SENT.equals(resolution))
                && !MANUALLY_RESOLVABLE_TEXT_STATUSES.contains(row.textStatus())) {
            throw new InvalidResolutionException("当前 text_status=" + row.textStatus()
                    + " 不允许人工标记为 " + resolution);
        }

        String now = PersistenceSupport.now();
        String targetTextStatus = switch (resolution) {
            case RESOLUTION_SENT -> "CONFIRMED";
            case RESOLUTION_NOT_SENT -> "FAILED";
            default -> row.textStatus();
        };

        // 7) 条件更新 attempt（version 再兜一层，防止并发覆盖）
        int updated = executeUpdate(connection,
                "UPDATE delivery_attempt SET text_status = ?, version = version + 1, " +
                        "manual_resolution = ?, resolved_at = ?, resolve_reason = ? " +
                        "WHERE attempt_id = ? AND version = ?",
                targetTextStatus, resolution, now, reason, attemptId, expectedVersion);
        if (updated != 1) {
            AttemptRow latest = findAttempt(connection, attemptId);
            throw new VersionConflictException(attemptId,
                    latest == null ? 0 : latest.version(),
                    latest == null ? null : latest.textStatus());
        }

        // 8) 预算补偿（SENT / NOT_SENT 才可能调整；STILL_UNKNOWN 不动预算）
        if (!RESOLUTION_STILL_UNKNOWN.equals(resolution)) {
            adjustBudgetForManualResolution(connection, attemptId, resolution, now);
        }

        // 9) 审计
        insertManualAction(connection, attemptId, resolution, row.textStatus(), targetTextStatus,
                reason, idempotencyKey, now);

        return new ResolutionResult(attemptId, targetTextStatus, row.imageStatus(),
                expectedVersion + 1, resolution, now, true, false);
    }

    private ResolutionResult replay(AttemptRow row) {
        return new ResolutionResult(row.attemptId(), row.textStatus(), row.imageStatus(), row.version(),
                row.manualResolution(), row.resolvedAt(), false, true);
    }

    // ==================== 预算补偿 ====================

    /**
     * 人工核对后的预算补偿（复用 {@link ReservationSettlement} 的条件转移与聚合语义）：
     * <ul>
     *   <li>SENT：UNKNOWN→CONFIRMED（uncertain-1/consumed+1）或 RESERVED→CONFIRMED（reserved-1/consumed+1）；</li>
     *   <li>NOT_SENT：UNKNOWN→RELEASED（uncertain-1）或 RESERVED→RELEASED（reserved-1）；</li>
     *   <li>无预留 / 已是目标态 / RELEASED：不改聚合。</li>
     * </ul>
     */
    private void adjustBudgetForManualResolution(Connection connection, String attemptId,
                                                 String resolution, String now) throws SQLException {
        ReservationSettlement.ReservationRow reservation =
                ReservationSettlement.findByAttemptId(connection, attemptId);
        if (reservation == null) {
            return;
        }
        String fromState = reservation.state();
        String targetState;
        if (RESOLUTION_SENT.equals(resolution)) {
            if (!ReservationSettlement.STATE_RESERVED.equals(fromState)
                    && !ReservationSettlement.STATE_UNKNOWN.equals(fromState)) {
                // CONFIRMED 已是成功态、RELEASED 表示不占额度，均无需调整
                return;
            }
            targetState = ReservationSettlement.STATE_CONFIRMED;
        } else {
            if (!ReservationSettlement.STATE_RESERVED.equals(fromState)
                    && !ReservationSettlement.STATE_UNKNOWN.equals(fromState)) {
                return;
            }
            targetState = ReservationSettlement.STATE_RELEASED;
        }

        int transitioned = ReservationSettlement.transitionFrom(connection,
                reservation.reservationId(), fromState, targetState, now);
        if (transitioned != 1) {
            ReservationSettlement.ReservationRow latest =
                    ReservationSettlement.find(connection, reservation.reservationId());
            if (latest != null && targetState.equals(latest.state())) {
                return; // 幂等：已经被结算到目标态
            }
            throw new SQLException("预算预留状态转移失败 | reservationId="
                    + reservation.reservationId() + " | " + fromState + "->" + targetState);
        }
        if (!ReservationSettlement.applyAggregateTransition(connection,
                reservation.budgetRowId(), fromState, targetState, now)) {
            throw new SQLException("daily_budget 聚合调整失败（计数不一致）| reservationId="
                    + reservation.reservationId());
        }
        log.info("人工核对调整预算 | attemptId={} | reservationId={} | {} -> {}",
                attemptId, reservation.reservationId(), fromState, targetState);
    }

    // ==================== 数据库访问 ====================

    private AttemptRow findAttempt(Connection connection, String attemptId) throws SQLException {
        String sql = "SELECT id, attempt_id, run_id, text_status, image_status, version, " +
                "manual_resolution, resolved_at FROM delivery_attempt WHERE attempt_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attemptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new AttemptRow(
                        resultSet.getLong("id"),
                        resultSet.getString("attempt_id"),
                        resultSet.getString("run_id"),
                        resultSet.getString("text_status"),
                        resultSet.getString("image_status"),
                        resultSet.getInt("version"),
                        resultSet.getString("manual_resolution"),
                        resultSet.getString("resolved_at"));
            }
        }
    }

    private String findManualActionObjectId(Connection connection, String idempotencyKey) throws SQLException {
        String sql = "SELECT object_id FROM manual_action WHERE idempotency_key = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private void insertManualAction(Connection connection, String attemptId, String action,
                                    String beforeState, String afterState, String reason,
                                    String idempotencyKey, String now) throws SQLException {
        String sql = "INSERT INTO manual_action(object_type, object_id, action, before_state, after_state, " +
                "reason, idempotency_key, created_at) " +
                "VALUES('delivery_attempt', ?, ?, ?, ?, ?, ?, ?)";
        executeUpdate(connection, sql, attemptId, action, beforeState, afterState, reason, idempotencyKey, now);
    }

    private int executeUpdate(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }

    // ==================== 参数校验 ====================

    private String requireAttemptId(String attemptId) {
        String value = PersistenceSupport.trimToNull(attemptId);
        if (value == null) {
            throw new InvalidResolutionException("attemptId 不能为空");
        }
        return value;
    }

    private String requireResolution(String resolution) {
        String value = PersistenceSupport.trimToNull(resolution);
        if (value == null) {
            throw new InvalidResolutionException("resolution 不能为空，只能是 SENT/NOT_SENT/STILL_UNKNOWN");
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_RESOLUTIONS.contains(normalized)) {
            throw new InvalidResolutionException("不支持的 resolution：" + resolution
                    + "，只能是 SENT/NOT_SENT/STILL_UNKNOWN");
        }
        return normalized;
    }

    private String requireReason(String reason) {
        String value = PersistenceSupport.trimToNull(reason);
        if (value == null) {
            throw new InvalidResolutionException("reason 必填");
        }
        if (value.length() > MAX_REASON_LENGTH) {
            throw new InvalidResolutionException("reason 不能超过 " + MAX_REASON_LENGTH + " 字");
        }
        return value;
    }

    // ==================== 事务工具 ====================

    /** 在单个事务中执行；SQLITE_BUSY 时有界重试整个事务。 */
    private <T> T inTransaction(TransactionWork<T> work) {
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
                        log.warn("数据库写入繁忙，{}ms 后重试人工核对事务（第 {} 次）：{}",
                                BUSY_RETRY_INTERVAL_MS, attempt, e.getMessage());
                        sleepQuietly();
                        continue;
                    }
                    log.error("人工核对事务失败，已回滚：{}", e.getMessage(), e);
                    throw new IllegalStateException("人工核对失败，已回滚：" + e.getMessage(), e);
                } catch (RuntimeException e) {
                    rollbackQuietly(connection);
                    throw e;
                } finally {
                    restoreAutoCommit(connection, originalAutoCommit);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("人工核对失败（数据库连接异常）：" + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("人工核对失败（数据库繁忙）："
                + (lastBusyError == null ? "" : lastBusyError.getMessage()), lastBusyError);
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("人工核对事务回滚失败：{}", e.getMessage());
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

    private record AttemptRow(long rowId, String attemptId, String runId, String textStatus,
                              String imageStatus, int version, String manualResolution, String resolvedAt) {
    }
}
