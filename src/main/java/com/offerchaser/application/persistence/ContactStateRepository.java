package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B3 联系人状态仓库（按 account + platform + contact_key 去重）。
 * <p>
 * contact_key 约定为 {@code "uid:<encryptUserId>"}；没有可信 encryptUserId 的联系人不落表，
 * 避免把未知 HR 错误合并。所有写操作使用 {@code version} 乐观锁（条件 UPDATE ... WHERE version=?），
 * 行数 0 表示版本冲突，返回 null 交给上层映射 409。
 */
@Service
@Slf4j
public class ContactStateRepository {

    /** 联系人状态记录（契约字段）。 */
    public record ContactState(long id, String account, String platform, String contactKey,
                               String encryptUserId, String hrName, String companyName, String replyState,
                               boolean manualTakeover, boolean stopContact,
                               String lastContactAt, String lastReplyAt,
                               int version, String createdAt, String updatedAt) {
    }

    private static final String SELECT_COLUMNS =
            "id, account, platform, contact_key, encrypt_user_id, hr_name, company_name, reply_state, " +
                    "manual_takeover, stop_contact, last_contact_at, last_reply_at, version, created_at, updated_at";

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final DataSource dataSource;

    public ContactStateRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 按唯一键查询联系人状态；不存在返回 null。 */
    public ContactState findByKey(String account, String platform, String contactKey) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM contact_state " +
                "WHERE account = ? AND platform = ? AND contact_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setString(2, platform);
            statement.setString(3, contactKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("查询联系人状态失败 | account={} | contactKey={}：{}", account, contactKey, e.getMessage(), e);
            throw new IllegalStateException("查询联系人状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 新建联系人状态（id 传 0，version 由数据库默认 0）。
     * 唯一键 (account, platform, contact_key) 冲突时抛 {@link IllegalStateException}。
     */
    public ContactState insert(ContactState state) {
        if (state == null) {
            throw new IllegalArgumentException("contactState 不能为空");
        }
        String account = requireText(state.account(), "account");
        String platform = requireText(state.platform(), "platform");
        String contactKey = requireText(state.contactKey(), "contactKey");
        String now = PersistenceSupport.now();
        String createdAt = PersistenceSupport.defaultIfBlank(state.createdAt(), now);
        String updatedAt = PersistenceSupport.defaultIfBlank(state.updatedAt(), now);
        String replyState = PersistenceSupport.defaultIfBlank(state.replyState(), "UNKNOWN");
        String sql = "INSERT INTO contact_state(account, platform, contact_key, encrypt_user_id, hr_name, " +
                "company_name, reply_state, manual_takeover, stop_contact, last_contact_at, last_reply_at, " +
                "version, created_at, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, account);
            statement.setString(2, platform);
            statement.setString(3, contactKey);
            statement.setString(4, PersistenceSupport.trimToNull(state.encryptUserId()));
            statement.setString(5, PersistenceSupport.trimToNull(state.hrName()));
            statement.setString(6, PersistenceSupport.trimToNull(state.companyName()));
            statement.setString(7, replyState);
            statement.setInt(8, state.manualTakeover() ? 1 : 0);
            statement.setInt(9, state.stopContact() ? 1 : 0);
            statement.setString(10, PersistenceSupport.trimToNull(state.lastContactAt()));
            statement.setString(11, PersistenceSupport.trimToNull(state.lastReplyAt()));
            statement.setString(12, createdAt);
            statement.setString(13, updatedAt);
            statement.executeUpdate();
            long id = readGeneratedKey(statement, connection);
            log.info("联系人状态已创建 | account={} | platform={} | contactKey={} | id={}",
                    account, platform, contactKey, id);
            return new ContactState(id, account, platform, contactKey,
                    PersistenceSupport.trimToNull(state.encryptUserId()),
                    PersistenceSupport.trimToNull(state.hrName()),
                    PersistenceSupport.trimToNull(state.companyName()),
                    replyState, state.manualTakeover(), state.stopContact(),
                    PersistenceSupport.trimToNull(state.lastContactAt()),
                    PersistenceSupport.trimToNull(state.lastReplyAt()), 0, createdAt, updatedAt);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                log.warn("联系人状态已存在，拒绝重复插入 | contactKey={}", contactKey);
                throw new IllegalStateException("联系人状态已存在：contactKey=" + contactKey, e);
            }
            log.error("创建联系人状态失败 | contactKey={}：{}", contactKey, e.getMessage(), e);
            throw new IllegalStateException("创建联系人状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 更新回复状态/接管/停止联系（乐观锁）。
     *
     * @return 更新后的记录；version 不匹配（行数 0）返回 null
     */
    public ContactState updateState(String account, String platform, String contactKey,
                                    String replyState, boolean manualTakeover, boolean stopContact,
                                    String lastReplyAt, int expectedVersion) {
        String sql = "UPDATE contact_state SET " +
                "reply_state = COALESCE(?, reply_state), " +
                "manual_takeover = ?, stop_contact = ?, " +
                "last_reply_at = COALESCE(?, last_reply_at), " +
                "version = version + 1, updated_at = ? " +
                "WHERE account = ? AND platform = ? AND contact_key = ? AND version = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PersistenceSupport.trimToNull(replyState));
            statement.setInt(2, manualTakeover ? 1 : 0);
            statement.setInt(3, stopContact ? 1 : 0);
            statement.setString(4, PersistenceSupport.trimToNull(lastReplyAt));
            statement.setString(5, PersistenceSupport.now());
            statement.setString(6, account);
            statement.setString(7, platform);
            statement.setString(8, contactKey);
            statement.setInt(9, expectedVersion);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.info("联系人状态更新未命中（版本冲突）| contactKey={} | expectedVersion={}",
                        contactKey, expectedVersion);
                return null;
            }
            return findByKey(account, platform, contactKey);
        } catch (SQLException e) {
            log.error("更新联系人状态失败 | contactKey={}：{}", contactKey, e.getMessage(), e);
            throw new IllegalStateException("更新联系人状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * D-02：投递状态同步读到 HR 已回复时，写回联系人应答状态。
     * <p>
     * 只在“没有人工接管/停止联系，且当前不是人工拒绝/面试/已回复”时更新，
     * 避免自动同步覆盖人工判断。只把 reply_state 置为 REPLIED 并补 last_reply_at，
     * 不触碰 last_contact_at。
     *
     * @return 实际更新行数（0 表示联系人不存在或人工状态优先）
     */
    public int markRepliedFromStatus(String account, String platform, String contactKey, String lastReplyAt) {
        String sql = "UPDATE contact_state SET reply_state = 'REPLIED', " +
                "last_reply_at = COALESCE(last_reply_at, ?), version = version + 1, updated_at = ? " +
                "WHERE account = ? AND platform = ? AND contact_key = ? " +
                "AND manual_takeover = 0 AND stop_contact = 0 " +
                "AND reply_state NOT IN ('REJECTED', 'INTERVIEW', 'REPLIED')";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PersistenceSupport.trimToNull(lastReplyAt));
            statement.setString(2, PersistenceSupport.now());
            statement.setString(3, account);
            statement.setString(4, platform);
            statement.setString(5, contactKey);
            return statement.executeUpdate();
        } catch (SQLException e) {
            log.error("状态同步回写联系人回复失败 | contactKey={}：{}", contactKey, e.getMessage(), e);
            throw new IllegalStateException("状态同步回写联系人回复失败：" + e.getMessage(), e);
        }
    }

    /**
     * 刷新最近联系时间（不 bump version、不做乐观锁）。
     *
     * @return 更新后的记录；联系人不存在返回 null
     */
    public ContactState touchLastContact(String account, String platform, String contactKey,
                                         String lastContactAt) {
        String sql = "UPDATE contact_state SET last_contact_at = COALESCE(?, last_contact_at), updated_at = ? " +
                "WHERE account = ? AND platform = ? AND contact_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PersistenceSupport.trimToNull(lastContactAt));
            statement.setString(2, PersistenceSupport.now());
            statement.setString(3, account);
            statement.setString(4, platform);
            statement.setString(5, contactKey);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                return null;
            }
            return findByKey(account, platform, contactKey);
        } catch (SQLException e) {
            log.error("刷新联系人最近联系时间失败 | contactKey={}：{}", contactKey, e.getMessage(), e);
            throw new IllegalStateException("刷新联系人最近联系时间失败：" + e.getMessage(), e);
        }
    }

    /** 联系人列表（SQL 分页，updated_at DESC, id DESC）。筛选参数为 null/空白时不过滤。 */
    public PageResult<ContactState> list(String replyState, Boolean manualTakeover, Boolean stopContact,
                                         int page, int size) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<String> clauses = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (hasText(replyState)) {
            clauses.add("reply_state = ?");
            parameters.add(replyState.trim());
        }
        if (manualTakeover != null) {
            clauses.add("manual_takeover = ?");
            parameters.add(manualTakeover ? 1 : 0);
        }
        if (stopContact != null) {
            clauses.add("stop_contact = ?");
            parameters.add(stopContact ? 1 : 0);
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        try (Connection connection = dataSource.getConnection()) {
            long total = count(connection, "contact_state" + where, parameters);
            List<ContactState> items = new ArrayList<>();
            String sql = "SELECT " + SELECT_COLUMNS + " FROM contact_state" + where +
                    " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = bind(statement, parameters);
                statement.setInt(index++, safeSize);
                statement.setLong(index, offset(safePage, safeSize));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        items.add(mapRow(resultSet));
                    }
                }
            }
            return new PageResult<>(items, safePage, safeSize, total);
        } catch (SQLException e) {
            log.error("查询联系人列表失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询联系人列表失败：" + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    private ContactState mapRow(ResultSet resultSet) throws SQLException {
        return new ContactState(
                resultSet.getLong("id"),
                resultSet.getString("account"),
                resultSet.getString("platform"),
                resultSet.getString("contact_key"),
                resultSet.getString("encrypt_user_id"),
                resultSet.getString("hr_name"),
                resultSet.getString("company_name"),
                resultSet.getString("reply_state"),
                resultSet.getInt("manual_takeover") != 0,
                resultSet.getInt("stop_contact") != 0,
                resultSet.getString("last_contact_at"),
                resultSet.getString("last_reply_at"),
                resultSet.getInt("version"),
                resultSet.getString("created_at"),
                resultSet.getString("updated_at"));
    }

    private long count(Connection connection, String fromWhere, List<Object> parameters) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + fromWhere;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private int bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        int index = 1;
        for (Object parameter : parameters) {
            statement.setObject(index++, parameter);
        }
        return index;
    }

    private long readGeneratedKey(PreparedStatement statement, Connection connection) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys != null && keys.next()) {
                return keys.getLong(1);
            }
        }
        try (java.sql.Statement fallback = connection.createStatement();
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
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("unique");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private int safePage(int page) {
        return page < 1 ? 1 : page;
    }

    private int safeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private long offset(int page, int size) {
        return (long) (page - 1) * size;
    }
}
