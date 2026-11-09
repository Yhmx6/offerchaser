package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 人工放行仓库（D3-B）：人工批准规则拒绝 / AI REVIEW 的岗位后，下一轮任务消费该放行记录，
 * 重新生成/校验话术，仍走身份校验、预算与发送确认。
 * <p>
 * 一个 job_evaluation 至多一条放行记录（evaluation_id UNIQUE）；状态流转：
 * {@code APPROVED → CONSUMED / FAILED}，人工可把 {@code FAILED/REJECTED} 重新放行回 {@code APPROVED}。
 */
@Service
@Slf4j
public class ManualReleaseRepository {

    /** 人工放行记录（与 manual_release 列一一对应）。 */
    public record ManualRelease(String releaseId, long evaluationId, String status, String reason,
                                String requestedAt, String decidedAt, String consumedAt, String attemptId,
                                String error, String createdAt, String updatedAt) {
    }

    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_CONSUMED = "CONSUMED";
    private static final String STATUS_FAILED = "FAILED";
    private static final Set<String> ALLOWED_STATUSES =
            Set.of(STATUS_APPROVED, STATUS_REJECTED, STATUS_CONSUMED, STATUS_FAILED);

    private static final String SELECT_COLUMNS =
            "release_id, evaluation_id, status, reason, requested_at, decided_at, consumed_at, " +
                    "attempt_id, error, created_at, updated_at";

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final DataSource dataSource;

    public ManualReleaseRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ==================== 写入 ====================

    /**
     * 新增人工放行记录：释放 releaseId 为空时自动生成 UUID；status 缺省 APPROVED；
     * requested_at/created_at/updated_at 缺省当前时间。
     * 同一 evaluation_id 已有记录（或 releaseId 冲突）→ IllegalStateException。
     */
    public ManualRelease insert(ManualRelease release) {
        if (release == null) {
            throw new IllegalArgumentException("manualRelease 不能为空");
        }
        if (release.evaluationId() <= 0) {
            throw new IllegalArgumentException("evaluationId 必须为正数：" + release.evaluationId());
        }
        String releaseId = PersistenceSupport.defaultIfBlank(
                release.releaseId(), UUID.randomUUID().toString());
        String status = PersistenceSupport.defaultIfBlank(release.status(), STATUS_APPROVED);
        validateStatus(status);
        String now = PersistenceSupport.now();
        String requestedAt = PersistenceSupport.defaultIfBlank(release.requestedAt(), now);
        String createdAt = PersistenceSupport.defaultIfBlank(release.createdAt(), now);
        String updatedAt = PersistenceSupport.defaultIfBlank(release.updatedAt(), now);
        String decidedAt = PersistenceSupport.trimToNull(release.decidedAt());
        if (decidedAt == null && !STATUS_APPROVED.equals(status)) {
            // 非“待消费”的初始状态视为已有人工决定时间
            decidedAt = now;
        }
        String sql = "INSERT INTO manual_release(release_id, evaluation_id, status, reason, requested_at, " +
                "decided_at, consumed_at, attempt_id, error, created_at, updated_at) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, releaseId);
            statement.setLong(2, release.evaluationId());
            statement.setString(3, status);
            statement.setString(4, PersistenceSupport.trimToNull(release.reason()));
            statement.setString(5, requestedAt);
            statement.setString(6, decidedAt);
            statement.setString(7, PersistenceSupport.trimToNull(release.consumedAt()));
            statement.setString(8, PersistenceSupport.trimToNull(release.attemptId()));
            statement.setString(9, PersistenceSupport.trimToNull(release.error()));
            statement.setString(10, createdAt);
            statement.setString(11, updatedAt);
            statement.executeUpdate();
            log.info("人工放行记录已创建 | releaseId={} | evaluationId={} | status={}",
                    releaseId, release.evaluationId(), status);
            return new ManualRelease(releaseId, release.evaluationId(), status,
                    PersistenceSupport.trimToNull(release.reason()), requestedAt, decidedAt,
                    PersistenceSupport.trimToNull(release.consumedAt()),
                    PersistenceSupport.trimToNull(release.attemptId()),
                    PersistenceSupport.trimToNull(release.error()), createdAt, updatedAt);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                if (message.contains("evaluation_id")) {
                    log.warn("人工放行记录重复（evaluationId）| evaluationId={}", release.evaluationId());
                    throw new IllegalStateException(
                            "该岗位评估已存在人工放行记录：evaluationId=" + release.evaluationId(), e);
                }
                log.warn("人工放行记录重复（releaseId）| releaseId={}", releaseId);
                throw new IllegalStateException("人工放行记录已存在：releaseId=" + releaseId, e);
            }
            log.error("创建人工放行记录失败 | evaluationId={}：{}",
                    release.evaluationId(), e.getMessage(), e);
            throw new IllegalStateException("创建人工放行记录失败：" + e.getMessage(), e);
        }
    }

    /**
     * 更新放行状态（APPROVED/REJECTED/CONSUMED/FAILED）：
     * <ul>
     *   <li>REJECTED/FAILED/CONSUMED：decided_at 为空时补当前时间；</li>
     *   <li>CONSUMED：consumed_at 为空时补当前时间；</li>
     *   <li>同时更新 attempt_id、error 与 updated_at。</li>
     * </ul>
     *
     * @throws IllegalArgumentException releaseId 不存在或状态非法
     */
    public void updateStatus(String releaseId, String status, String attemptId, String error) {
        String validReleaseId = requireText(releaseId, "releaseId");
        String validStatus = requireText(status, "status");
        validateStatus(validStatus);
        String now = PersistenceSupport.now();
        String sql = "UPDATE manual_release SET status = ?, attempt_id = ?, error = ?, updated_at = ?, " +
                "decided_at = CASE WHEN ? <> 'APPROVED' AND decided_at IS NULL THEN ? ELSE decided_at END, " +
                "consumed_at = CASE WHEN ? = 'CONSUMED' THEN COALESCE(consumed_at, ?) ELSE consumed_at END " +
                "WHERE release_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, validStatus);
            statement.setString(2, PersistenceSupport.trimToNull(attemptId));
            statement.setString(3, PersistenceSupport.trimToNull(error));
            statement.setString(4, now);
            statement.setString(5, validStatus);
            statement.setString(6, now);
            statement.setString(7, validStatus);
            statement.setString(8, now);
            statement.setString(9, validReleaseId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("人工放行记录不存在：releaseId=" + validReleaseId);
            }
            log.info("人工放行状态已更新 | releaseId={} | status={}", validReleaseId, validStatus);
        } catch (SQLException e) {
            log.error("更新人工放行状态失败 | releaseId={}：{}", validReleaseId, e.getMessage(), e);
            throw new IllegalStateException("更新人工放行状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 重新放行（可选能力）：FAILED/REJECTED → APPROVED，清空 error/attempt_id/consumed_at，刷新 updated_at。
     *
     * @return 更新后的记录
     * @throws IllegalArgumentException releaseId 不存在或当前状态不允许重新放行
     */
    public ManualRelease reopen(String releaseId) {
        String validReleaseId = requireText(releaseId, "releaseId");
        ManualRelease existing = findByReleaseId(validReleaseId);
        if (existing == null) {
            throw new IllegalArgumentException("人工放行记录不存在：releaseId=" + validReleaseId);
        }
        if (!STATUS_FAILED.equals(existing.status()) && !STATUS_REJECTED.equals(existing.status())) {
            throw new IllegalArgumentException(
                    "只有 FAILED/REJECTED 状态可重新放行，当前状态：" + existing.status());
        }
        String sql = "UPDATE manual_release SET status = ?, error = NULL, attempt_id = NULL, " +
                "consumed_at = NULL, updated_at = ? WHERE release_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STATUS_APPROVED);
            statement.setString(2, PersistenceSupport.now());
            statement.setString(3, validReleaseId);
            statement.executeUpdate();
            log.info("人工放行记录已重新放行 | releaseId={}", validReleaseId);
            return findByReleaseId(validReleaseId);
        } catch (SQLException e) {
            log.error("重新放行失败 | releaseId={}：{}", validReleaseId, e.getMessage(), e);
            throw new IllegalStateException("重新放行失败：" + e.getMessage(), e);
        }
    }

    // ==================== 查询 ====================

    /** 按 evaluationId 查询；不存在返回 null。 */
    public ManualRelease findByEvaluationId(long evaluationId) {
        if (evaluationId <= 0) {
            return null;
        }
        String sql = "SELECT " + SELECT_COLUMNS + " FROM manual_release WHERE evaluation_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, evaluationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("查询人工放行记录失败 | evaluationId={}：{}", evaluationId, e.getMessage(), e);
            throw new IllegalStateException("查询人工放行记录失败：" + e.getMessage(), e);
        }
    }

    /** 按 releaseId 查询；不存在返回 null。 */
    public ManualRelease findByReleaseId(String releaseId) {
        String validReleaseId = PersistenceSupport.trimToNull(releaseId);
        if (validReleaseId == null) {
            return null;
        }
        String sql = "SELECT " + SELECT_COLUMNS + " FROM manual_release WHERE release_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, validReleaseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("查询人工放行记录失败 | releaseId={}：{}", releaseId, e.getMessage(), e);
            throw new IllegalStateException("查询人工放行记录失败：" + e.getMessage(), e);
        }
    }

    /** 待消费（APPROVED）的放行记录，按 requested_at ASC（先批准先消费）。 */
    public List<ManualRelease> findApproved(int limit) {
        int safeLimit = normalizeLimit(limit);
        String sql = "SELECT " + SELECT_COLUMNS + " FROM manual_release WHERE status = 'APPROVED' " +
                "ORDER BY requested_at ASC, release_id ASC LIMIT ?";
        List<ManualRelease> releases = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    releases.add(mapRow(resultSet));
                }
            }
            return releases;
        } catch (SQLException e) {
            log.error("查询待消费人工放行记录失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询待消费人工放行记录失败：" + e.getMessage(), e);
        }
    }

    /** 全部放行记录（按 requested_at DESC）；limit<=0 默认 20，最多 200。 */
    public List<ManualRelease> listAll(int limit) {
        int safeLimit = normalizeLimit(limit);
        String sql = "SELECT " + SELECT_COLUMNS + " FROM manual_release " +
                "ORDER BY requested_at DESC, release_id DESC LIMIT ?";
        List<ManualRelease> releases = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    releases.add(mapRow(resultSet));
                }
            }
            return releases;
        } catch (SQLException e) {
            log.error("查询人工放行记录列表失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询人工放行记录列表失败：" + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    private ManualRelease mapRow(ResultSet resultSet) throws SQLException {
        return new ManualRelease(
                resultSet.getString("release_id"),
                resultSet.getLong("evaluation_id"),
                resultSet.getString("status"),
                resultSet.getString("reason"),
                resultSet.getString("requested_at"),
                resultSet.getString("decided_at"),
                resultSet.getString("consumed_at"),
                resultSet.getString("attempt_id"),
                resultSet.getString("error"),
                resultSet.getString("created_at"),
                resultSet.getString("updated_at"));
    }

    private void validateStatus(String status) {
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("非法的人工放行状态：" + status
                    + "（允许 APPROVED/REJECTED/CONSUMED/FAILED）");
        }
    }

    private int normalizeLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    private String requireText(String value, String fieldName) {
        String trimmed = PersistenceSupport.trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return trimmed;
    }

    private boolean isUniqueViolation(SQLException error) {
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("unique") || lower.contains("constraint failed");
    }
}
