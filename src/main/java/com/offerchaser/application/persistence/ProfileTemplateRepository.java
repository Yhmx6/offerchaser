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
 * 方向模板仓库（B6）：每个模板保存一套方向相关的配置快照（Boss 配置、AI 招呼语/提示词、规则 payload、简历文件）。
 * <p>
 * 约束：
 * <ul>
 *   <li>{@code name} 唯一（重复插入/改名抛 {@link IllegalStateException("模板名称已存在")}）；</li>
 *   <li>同一时刻最多一个 {@code active=1}：{@link #setActive(long)} 在单事务内先全部置 0 再启用目标行；</li>
 *   <li>所有内容更新都会刷新 {@code updated_at}；{@code list()} 按自定义排序 + 更新时间兜底。</li>
 * </ul>
 */
@Service
@Slf4j
public class ProfileTemplateRepository {

    /** 方向模板记录（契约字段；sortOrder 为自定义排序，NULL 表示未排序）。 */
    public record ProfileTemplate(long id, String name, String direction, String description,
                                  boolean active, String bossConfigJson, String aiIntroduce, String aiPrompt,
                                  String rulePayloadJson, String deliveryPolicyJson, String resumeFile,
                                  String createdAt, String updatedAt, Integer sortOrder) {
    }

    private static final String SELECT_COLUMNS =
            "id, name, direction, description, active, boss_config_json, ai_introduce, ai_prompt, " +
                    "rule_payload_json, delivery_policy_json, resume_file, created_at, updated_at, sort_order";

    private static final String DUPLICATE_NAME_MESSAGE = "模板名称已存在";

    private final DataSource dataSource;

    public ProfileTemplateRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ==================== 查询 ====================

    /** 全部模板：已排序在前（sort_order 升序），未排序/旧数据在后按 updated_at DESC, id DESC。 */
    public List<ProfileTemplate> list() {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM profile_template " +
                "ORDER BY sort_order IS NULL, sort_order ASC, updated_at DESC, id DESC";
        List<ProfileTemplate> templates = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                templates.add(mapRow(resultSet));
            }
            return templates;
        } catch (SQLException e) {
            log.error("查询方向模板列表失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询方向模板列表失败：" + e.getMessage(), e);
        }
    }

    /** 按 id 查询；不存在返回 null。 */
    public ProfileTemplate findById(long id) {
        if (id <= 0) {
            return null;
        }
        String sql = "SELECT " + SELECT_COLUMNS + " FROM profile_template WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("查询方向模板失败 | id={}：{}", id, e.getMessage(), e);
            throw new IllegalStateException("查询方向模板失败：" + e.getMessage(), e);
        }
    }

    /** 当前启用的模板；没有返回 null。 */
    public ProfileTemplate findActive() {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM profile_template WHERE active = 1 " +
                "ORDER BY updated_at DESC, id DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? mapRow(resultSet) : null;
        } catch (SQLException e) {
            log.error("查询启用方向模板失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询启用方向模板失败：" + e.getMessage(), e);
        }
    }

    // ==================== 写入 ====================

    /**
     * 新建模板：id/active 忽略（id 由数据库生成，active 强制 false）。
     * name 为空 → IllegalArgumentException；name 重复 → IllegalStateException("模板名称已存在")。
     */
    public ProfileTemplate insert(ProfileTemplate template) {
        if (template == null) {
            throw new IllegalArgumentException("profileTemplate 不能为空");
        }
        String name = requireText(template.name(), "name");
        String now = PersistenceSupport.now();
        String createdAt = PersistenceSupport.defaultIfBlank(template.createdAt(), now);
        String updatedAt = PersistenceSupport.defaultIfBlank(template.updatedAt(), now);
        String sql = "INSERT INTO profile_template(name, direction, description, active, boss_config_json, " +
                "ai_introduce, ai_prompt, rule_payload_json, delivery_policy_json, resume_file, " +
                "created_at, updated_at) " +
                "VALUES(?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, PersistenceSupport.trimToNull(template.direction()));
            statement.setString(3, PersistenceSupport.trimToNull(template.description()));
            statement.setString(4, PersistenceSupport.trimToNull(template.bossConfigJson()));
            statement.setString(5, PersistenceSupport.trimToNull(template.aiIntroduce()));
            statement.setString(6, PersistenceSupport.trimToNull(template.aiPrompt()));
            statement.setString(7, PersistenceSupport.trimToNull(template.rulePayloadJson()));
            statement.setString(8, PersistenceSupport.trimToNull(template.deliveryPolicyJson()));
            statement.setString(9, PersistenceSupport.trimToNull(template.resumeFile()));
            statement.setString(10, createdAt);
            statement.setString(11, updatedAt);
            statement.executeUpdate();
            long id = readGeneratedKey(statement, connection);
            log.info("方向模板已创建 | id={} | name={}", id, name);
            return new ProfileTemplate(id, name,
                    PersistenceSupport.trimToNull(template.direction()),
                    PersistenceSupport.trimToNull(template.description()),
                    false,
                    PersistenceSupport.trimToNull(template.bossConfigJson()),
                    PersistenceSupport.trimToNull(template.aiIntroduce()),
                    PersistenceSupport.trimToNull(template.aiPrompt()),
                    PersistenceSupport.trimToNull(template.rulePayloadJson()),
                    PersistenceSupport.trimToNull(template.deliveryPolicyJson()),
                    PersistenceSupport.trimToNull(template.resumeFile()),
                    createdAt, updatedAt, null);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                log.warn("方向模板名称重复 | name={}", name);
                throw new IllegalStateException(DUPLICATE_NAME_MESSAGE, e);
            }
            log.error("创建方向模板失败 | name={}：{}", name, e.getMessage(), e);
            throw new IllegalStateException("创建方向模板失败：" + e.getMessage(), e);
        }
    }

    /**
     * 更新模板元信息（name/direction/description）。
     *
     * @return 更新后的记录；模板不存在返回 null
     */
    public ProfileTemplate updateMetadata(long id, String name, String direction, String description) {
        String validName = requireText(name, "name");
        String sql = "UPDATE profile_template SET name = ?, direction = ?, description = ?, updated_at = ? " +
                "WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, validName);
            statement.setString(2, PersistenceSupport.trimToNull(direction));
            statement.setString(3, PersistenceSupport.trimToNull(description));
            statement.setString(4, PersistenceSupport.now());
            statement.setLong(5, id);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.info("更新方向模板元信息未命中（模板不存在）| id={}", id);
                return null;
            }
            return findById(id);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                log.warn("方向模板名称重复 | name={}", validName);
                throw new IllegalStateException(DUPLICATE_NAME_MESSAGE, e);
            }
            log.error("更新方向模板元信息失败 | id={}：{}", id, e.getMessage(), e);
            throw new IllegalStateException("更新方向模板元信息失败：" + e.getMessage(), e);
        }
    }

    /**
     * 更新模板配置快照（bossConfig/aiIntroduce/aiPrompt/rulePayload/deliveryPolicy/resumeFile），
     * 不修改 name/direction/description。
     *
     * @return 更新后的记录；模板不存在返回 null
     */
    public ProfileTemplate updateSnapshot(long id, String bossConfigJson, String aiIntroduce, String aiPrompt,
                                          String rulePayloadJson, String deliveryPolicyJson,
                                          String resumeFile) {
        String sql = "UPDATE profile_template SET boss_config_json = ?, ai_introduce = ?, ai_prompt = ?, " +
                "rule_payload_json = ?, delivery_policy_json = ?, resume_file = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PersistenceSupport.trimToNull(bossConfigJson));
            statement.setString(2, PersistenceSupport.trimToNull(aiIntroduce));
            statement.setString(3, PersistenceSupport.trimToNull(aiPrompt));
            statement.setString(4, PersistenceSupport.trimToNull(rulePayloadJson));
            statement.setString(5, PersistenceSupport.trimToNull(deliveryPolicyJson));
            statement.setString(6, PersistenceSupport.trimToNull(resumeFile));
            statement.setString(7, PersistenceSupport.now());
            statement.setLong(8, id);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.info("更新方向模板快照未命中（模板不存在）| id={}", id);
                return null;
            }
            return findById(id);
        } catch (SQLException e) {
            log.error("更新方向模板快照失败 | id={}：{}", id, e.getMessage(), e);
            throw new IllegalStateException("更新方向模板快照失败：" + e.getMessage(), e);
        }
    }

    /**
     * 只更新模板的简历文件。
     *
     * @return 更新后的记录；模板不存在返回 null
     */
    public ProfileTemplate updateResumeFile(long id, String resumeFile) {
        String sql = "UPDATE profile_template SET resume_file = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PersistenceSupport.trimToNull(resumeFile));
            statement.setString(2, PersistenceSupport.now());
            statement.setLong(3, id);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.info("更新模板简历文件未命中（模板不存在）| id={}", id);
                return null;
            }
            return findById(id);
        } catch (SQLException e) {
            log.error("更新模板简历文件失败 | id={}：{}", id, e.getMessage(), e);
            throw new IllegalStateException("更新模板简历文件失败：" + e.getMessage(), e);
        }
    }

    /**
     * 启用指定模板（单事务）：先把所有模板置为 inactive，再把目标模板置为 active。
     *
     * @throws IllegalArgumentException id 不存在
     */
    public void setActive(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("模板不存在：id=" + id);
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!exists(connection, id)) {
                    connection.rollback();
                    throw new IllegalArgumentException("模板不存在：id=" + id);
                }
                String resetSql = "UPDATE profile_template SET active = 0, updated_at = ? WHERE active = 1";
                try (PreparedStatement statement = connection.prepareStatement(resetSql)) {
                    statement.setString(1, PersistenceSupport.now());
                    statement.executeUpdate();
                }
                String activateSql = "UPDATE profile_template SET active = 1, updated_at = ? WHERE id = ?";
                try (PreparedStatement statement = connection.prepareStatement(activateSql)) {
                    statement.setString(1, PersistenceSupport.now());
                    statement.setLong(2, id);
                    statement.executeUpdate();
                }
                connection.commit();
                log.info("已启用方向模板 | id={}", id);
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw e;
            } catch (RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignore) {
                    // 连接关闭时由连接池处理
                }
            }
        } catch (SQLException e) {
            log.error("启用方向模板失败 | id={}：{}", id, e.getMessage(), e);
            throw new IllegalStateException("启用方向模板失败：" + e.getMessage(), e);
        }
    }

    /** 清除启用状态（所有模板置为 inactive）。 */
    public void clearActive() {
        String sql = "UPDATE profile_template SET active = 0, updated_at = ? WHERE active = 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PersistenceSupport.now());
            int updated = statement.executeUpdate();
            if (updated > 0) {
                log.info("已清除方向模板启用状态，影响 {} 条", updated);
            }
        } catch (SQLException e) {
            log.error("清除方向模板启用状态失败：{}", e.getMessage(), e);
            throw new IllegalStateException("清除方向模板启用状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 批量重排模板顺序（单事务）：按 orderedIds 顺序写入 {@code sort_order = 0..n-1}，
     * 不修改 updated_at（排序不是内容更新）。
     * <p>
     * 空列表/ null → no-op；重复 id → IllegalArgumentException；id 不存在 → IllegalArgumentException，
     * 且事务回滚（不会半更新）。
     */
    public void reorder(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return;
        }
        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        for (Long id : orderedIds) {
            if (id == null) {
                throw new IllegalArgumentException("模板排序 id 不能为 null");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("模板排序 id 重复：" + id);
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String sql = "UPDATE profile_template SET sort_order = ? WHERE id = ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int index = 0;
                    for (Long id : orderedIds) {
                        statement.setInt(1, index);
                        statement.setLong(2, id);
                        int updated = statement.executeUpdate();
                        if (updated == 0) {
                            throw new IllegalArgumentException("模板不存在：id=" + id);
                        }
                        index++;
                    }
                }
                connection.commit();
                log.info("方向模板排序已更新 | count={}", orderedIds.size());
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw e;
            } catch (RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignore) {
                    // 连接关闭时由连接池处理
                }
            }
        } catch (SQLException e) {
            log.error("更新方向模板排序失败：{}", e.getMessage(), e);
            throw new IllegalStateException("更新方向模板排序失败：" + e.getMessage(), e);
        }
    }

    /** 删除模板，返回是否真的删除。 */
    public boolean delete(long id) {
        if (id <= 0) {
            return false;
        }
        String sql = "DELETE FROM profile_template WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            boolean deleted = statement.executeUpdate() > 0;
            if (deleted) {
                log.info("已删除方向模板 | id={}", id);
            }
            return deleted;
        } catch (SQLException e) {
            log.error("删除方向模板失败 | id={}：{}", id, e.getMessage(), e);
            throw new IllegalStateException("删除方向模板失败：" + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    private boolean exists(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM profile_template WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private ProfileTemplate mapRow(ResultSet resultSet) throws SQLException {
        return new ProfileTemplate(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("direction"),
                resultSet.getString("description"),
                resultSet.getInt("active") != 0,
                resultSet.getString("boss_config_json"),
                resultSet.getString("ai_introduce"),
                resultSet.getString("ai_prompt"),
                resultSet.getString("rule_payload_json"),
                resultSet.getString("delivery_policy_json"),
                resultSet.getString("resume_file"),
                resultSet.getString("created_at"),
                resultSet.getString("updated_at"),
                readNullableInt(resultSet, "sort_order"));
    }

    private Integer readNullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
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

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("方向模板事务回滚失败：{}", e.getMessage());
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
