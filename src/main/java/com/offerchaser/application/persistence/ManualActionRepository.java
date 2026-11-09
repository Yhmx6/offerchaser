package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * B3 人工操作审计仓库（复用 V4 已创建的 manual_action 表，无需迁移）。
 * <p>
 * 供 ContactStateService / FollowupService 记录人工标记、批准/拒绝等动作；
 * {@code idempotency_key} 全局唯一，用于请求幂等，重复插入抛 {@link IllegalStateException}
 * （message 带 key），调用方可先用 {@link #findByIdempotencyKey(String)} 做幂等重放。
 */
@Service
@Slf4j
public class ManualActionRepository {

    /** 人工操作审计记录。 */
    public record ManualAction(long id, String objectType, String objectId, String action,
                               String beforeState, String afterState, String reason,
                               String idempotencyKey, String createdAt) {
    }

    private static final String SELECT_COLUMNS =
            "id, object_type, object_id, action, before_state, after_state, reason, idempotency_key, created_at";
    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 200;

    private final DataSource dataSource;

    public ManualActionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 按幂等键查询（key 为空返回 null）；不存在返回 null。 */
    public ManualAction findByIdempotencyKey(String idempotencyKey) {
        String key = PersistenceSupport.trimToNull(idempotencyKey);
        if (key == null) {
            return null;
        }
        String sql = "SELECT " + SELECT_COLUMNS + " FROM manual_action WHERE idempotency_key = ? LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("按幂等键查询人工操作失败 | key={}：{}", key, e.getMessage(), e);
            throw new IllegalStateException("按幂等键查询人工操作失败：" + e.getMessage(), e);
        }
    }

    /**
     * 写入一条人工操作审计。
     *
     * @param idempotencyKey 可空；非空时全局唯一
     * @return 写入后的记录
     * @throws IllegalStateException 幂等键冲突或数据库写入失败
     */
    public ManualAction insert(String objectType, String objectId, String action,
                               String beforeState, String afterState, String reason,
                               String idempotencyKey) {
        String type = requireText(objectType, "objectType");
        String id = requireText(objectId, "objectId");
        String actionText = requireText(action, "action");
        String key = PersistenceSupport.trimToNull(idempotencyKey);
        String now = PersistenceSupport.now();
        String sql = "INSERT INTO manual_action(object_type, object_id, action, before_state, after_state, " +
                "reason, idempotency_key, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, type);
            statement.setString(2, id);
            statement.setString(3, actionText);
            statement.setString(4, PersistenceSupport.trimToNull(beforeState));
            statement.setString(5, PersistenceSupport.trimToNull(afterState));
            statement.setString(6, PersistenceSupport.trimToNull(reason));
            statement.setString(7, key);
            statement.setString(8, now);
            statement.executeUpdate();
            long rowId = readGeneratedKey(statement, connection);
            log.info("人工操作审计已写入 | objectType={} | objectId={} | action={} | key={}",
                    type, id, actionText, key);
            return new ManualAction(rowId, type, id, actionText,
                    PersistenceSupport.trimToNull(beforeState), PersistenceSupport.trimToNull(afterState),
                    PersistenceSupport.trimToNull(reason), key, now);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                log.warn("人工操作幂等键冲突，拒绝重复写入 | key={}", key);
                throw new IllegalStateException("幂等键已存在：idempotencyKey=" + key, e);
            }
            log.error("写入人工操作审计失败 | objectType={} | objectId={}：{}", type, id, e.getMessage(), e);
            throw new IllegalStateException("写入人工操作审计失败：" + e.getMessage(), e);
        }
    }

    /** 某对象最近的人工操作（id DESC）。limit &lt;= 0 默认 20，最大 200。 */
    public List<ManualAction> findByObject(String objectType, String objectId, int limit) {
        String type = requireText(objectType, "objectType");
        String id = requireText(objectId, "objectId");
        int safeLimit = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        String sql = "SELECT " + SELECT_COLUMNS + " FROM manual_action " +
                "WHERE object_type = ? AND object_id = ? ORDER BY id DESC LIMIT ?";
        List<ManualAction> actions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type);
            statement.setString(2, id);
            statement.setInt(3, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    actions.add(mapRow(resultSet));
                }
            }
            return actions;
        } catch (SQLException e) {
            log.error("查询人工操作审计失败 | objectType={} | objectId={}：{}",
                    type, id, e.getMessage(), e);
            throw new IllegalStateException("查询人工操作审计失败：" + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    private ManualAction mapRow(ResultSet resultSet) throws SQLException {
        return new ManualAction(
                resultSet.getLong("id"),
                resultSet.getString("object_type"),
                resultSet.getString("object_id"),
                resultSet.getString("action"),
                resultSet.getString("before_state"),
                resultSet.getString("after_state"),
                resultSet.getString("reason"),
                resultSet.getString("idempotency_key"),
                resultSet.getString("created_at"));
    }

    private long readGeneratedKey(PreparedStatement statement, Connection connection) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys != null && keys.next()) {
                return keys.getLong(1);
            }
        }
        try (Statement fallback = connection.createStatement();
             ResultSet resultSet = fallback.executeQuery("SELECT last_insert_rowid()")) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
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
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("unique") || lower.contains("constraint failed");
    }
}
