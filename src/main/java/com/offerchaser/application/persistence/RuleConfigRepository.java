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
 * 规则配置版本仓库（B2）：每次保存规则生成一条不可变版本记录，供运行期固定规则快照与重评使用。
 * <p>
 * {@code version} 在数据库中 UNIQUE：并发插入同一 version 时抛 {@link IllegalStateException}
 * （message 带 version，调用方据此映射 409）。
 */
@Service
@Slf4j
public class RuleConfigRepository {

    /** 规则配置版本记录（嵌套类型，RuleConfigService 直接引用）。 */
    public record RuleConfigVersion(long id, int version, String rulesJson, String sourceHash,
                                    String note, String liveHash, String createdAt)
            implements com.offerchaser.application.persistence.RuleConfigVersion {
    }

    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 200;

    private static final String SELECT_COLUMNS =
            "id, version, rules_json, source_hash, note, live_hash, created_at FROM rule_config_version";

    private final DataSource dataSource;

    public RuleConfigRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 最新版本（version 最大）；没有任何记录返回 null。 */
    public RuleConfigVersion latest() {
        String sql = "SELECT " + SELECT_COLUMNS + " ORDER BY version DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? mapRow(resultSet) : null;
        } catch (SQLException e) {
            log.error("读取最新规则配置版本失败：{}", e.getMessage(), e);
            throw new IllegalStateException("读取最新规则配置版本失败：" + e.getMessage(), e);
        }
    }

    /** 按版本号查询；不存在返回 null。 */
    public RuleConfigVersion findByVersion(int version) {
        String sql = "SELECT " + SELECT_COLUMNS + " WHERE version = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("读取规则配置版本失败 | version={}：{}", version, e.getMessage(), e);
            throw new IllegalStateException("读取规则配置版本失败：" + e.getMessage(), e);
        }
    }

    /**
     * 插入一个新的规则配置版本（兼容旧调用，liveHash 为 null）。
     *
     * @deprecated 请使用 {@link #insert(int, String, String, String, String)} 并持久化生成版本时的 live 配置哈希。
     */
    @Deprecated
    public RuleConfigVersion insert(int version, String rulesJson, String sourceHash, String note) {
        return insert(version, rulesJson, sourceHash, note, null);
    }

    /**
     * 插入一个新的规则配置版本。
     *
     * @param version    版本号（必须 &gt;= 1，且不能与已有版本重复）
     * @param rulesJson  规则 JSON（必填）
     * @param sourceHash 规则 JSON 的 sha256（可空）
     * @param note       备注（可空）
     * @param liveHash   生成该版本时 live 配置（BossConfig+黑名单）的哈希（可空；旧数据为 null）
     * @throws IllegalArgumentException version/rulesJson 非法
     * @throws IllegalStateException    version 已存在（UNIQUE 冲突）或数据库写入失败
     */
    public RuleConfigVersion insert(int version, String rulesJson, String sourceHash, String note,
                                    String liveHash) {
        if (version < 1) {
            throw new IllegalArgumentException("规则版本号必须 >= 1，当前：" + version);
        }
        if (PersistenceSupport.trimToNull(rulesJson) == null) {
            throw new IllegalArgumentException("rulesJson 不能为空");
        }
        String createdAt = PersistenceSupport.now();
        String normalizedLiveHash = PersistenceSupport.trimToNull(liveHash);
        String sql = "INSERT INTO rule_config_version" +
                "(version, rules_json, source_hash, note, live_hash, created_at) " +
                "VALUES(?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, version);
            statement.setString(2, rulesJson);
            statement.setString(3, PersistenceSupport.trimToNull(sourceHash));
            statement.setString(4, PersistenceSupport.trimToNull(note));
            statement.setString(5, normalizedLiveHash);
            statement.setString(6, createdAt);
            statement.executeUpdate();
            long id = readGeneratedKey(statement, connection);
            log.info("规则配置版本已保存 | version={} | id={} | sourceHash={} | liveHash={}",
                    version, id, sourceHash, normalizedLiveHash);
            return new RuleConfigVersion(id, version, rulesJson,
                    PersistenceSupport.trimToNull(sourceHash), PersistenceSupport.trimToNull(note),
                    normalizedLiveHash, createdAt);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                log.warn("规则配置版本已存在，拒绝重复插入 | version={}", version);
                throw new IllegalStateException("规则版本已存在：version=" + version
                        + "（并发保存冲突，请刷新后重试）", e);
            }
            log.error("写入规则配置版本失败 | version={}：{}", version, e.getMessage(), e);
            throw new IllegalStateException("写入规则配置版本失败：" + e.getMessage(), e);
        }
    }

    /** 版本历史（version DESC）。limit &lt;= 0 默认 20，最大 200。 */
    public List<RuleConfigVersion> list(int limit) {
        int safeLimit = safeLimit(limit);
        String sql = "SELECT " + SELECT_COLUMNS + " ORDER BY version DESC LIMIT ?";
        List<RuleConfigVersion> versions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    versions.add(mapRow(resultSet));
                }
            }
            return versions;
        } catch (SQLException e) {
            log.error("读取规则配置版本列表失败：{}", e.getMessage(), e);
            throw new IllegalStateException("读取规则配置版本列表失败：" + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    private RuleConfigVersion mapRow(ResultSet resultSet) throws SQLException {
        return new RuleConfigVersion(
                resultSet.getLong("id"),
                resultSet.getInt("version"),
                resultSet.getString("rules_json"),
                resultSet.getString("source_hash"),
                resultSet.getString("note"),
                resultSet.getString("live_hash"),
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

    private boolean isUniqueViolation(SQLException error) {
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("unique") || lower.contains("constraint failed");
    }

    private int safeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }
}
