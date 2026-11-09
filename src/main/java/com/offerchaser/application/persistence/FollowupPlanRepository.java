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

/**
 * B3 跟进计划仓库：建议跟进（FOLLOWUP）/ 补发图片（IMAGE_RESEND）计划及其状态机。
 * <p>
 * 默认不自动跟进：计划先以 SUGGESTED 生成，用户批准后变 APPROVED，只有下一轮任务看到 APPROVED
 * 才允许执行。状态变更统一使用 {@code version} 乐观锁（条件 UPDATE plan_id + fromStatus + version），
 * 行数 0 返回 null 交给上层映射 409。
 */
@Service
@Slf4j
public class FollowupPlanRepository {

    /** 跟进计划记录（契约字段）。 */
    public record FollowupPlan(long id, String planId, String account, String platform,
                               String encryptId, String encryptUserId, String contactKey,
                               String jobName, String companyName, String planType, String status,
                               String reason, String parentAttemptId, int version,
                               String suggestedAt, String decidedAt, String executedAt,
                               String executedAttemptId, String createdAt, String updatedAt) {
    }

    private static final String SELECT_COLUMNS =
            "id, plan_id, account, platform, encrypt_id, encrypt_user_id, contact_key, job_name, company_name, " +
                    "plan_type, status, reason, parent_attempt_id, version, suggested_at, decided_at, executed_at, " +
                    "executed_attempt_id, created_at, updated_at";
    private static final String ACTIVE_STATUSES = "status IN ('SUGGESTED','APPROVED')";

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final DataSource dataSource;

    public FollowupPlanRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 按 plan_id 查询；不存在返回 null。 */
    public FollowupPlan findByPlanId(String planId) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM followup_plan WHERE plan_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, planId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("查询跟进计划失败 | planId={}：{}", planId, e.getMessage(), e);
            throw new IllegalStateException("查询跟进计划失败：" + e.getMessage(), e);
        }
    }

    /**
     * 新建跟进计划（id 传 0，version 由数据库默认 0）。
     * plan_id 唯一冲突抛 {@link IllegalStateException}。
     */
    public FollowupPlan insert(FollowupPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("followupPlan 不能为空");
        }
        String planId = requireText(plan.planId(), "planId");
        String account = requireText(plan.account(), "account");
        String platform = requireText(plan.platform(), "platform");
        String planType = requireText(plan.planType(), "planType");
        String status = requireText(plan.status(), "status");
        String now = PersistenceSupport.now();
        String suggestedAt = PersistenceSupport.defaultIfBlank(plan.suggestedAt(), now);
        String createdAt = PersistenceSupport.defaultIfBlank(plan.createdAt(), now);
        String updatedAt = PersistenceSupport.defaultIfBlank(plan.updatedAt(), now);
        String sql = "INSERT INTO followup_plan(plan_id, account, platform, encrypt_id, encrypt_user_id, " +
                "contact_key, job_name, company_name, plan_type, status, reason, parent_attempt_id, version, " +
                "suggested_at, decided_at, executed_at, executed_attempt_id, created_at, updated_at) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, planId);
            statement.setString(2, account);
            statement.setString(3, platform);
            statement.setString(4, PersistenceSupport.trimToNull(plan.encryptId()));
            statement.setString(5, PersistenceSupport.trimToNull(plan.encryptUserId()));
            statement.setString(6, PersistenceSupport.trimToNull(plan.contactKey()));
            statement.setString(7, PersistenceSupport.trimToNull(plan.jobName()));
            statement.setString(8, PersistenceSupport.trimToNull(plan.companyName()));
            statement.setString(9, planType);
            statement.setString(10, status);
            statement.setString(11, PersistenceSupport.trimToNull(plan.reason()));
            statement.setString(12, PersistenceSupport.trimToNull(plan.parentAttemptId()));
            statement.setString(13, suggestedAt);
            statement.setString(14, PersistenceSupport.trimToNull(plan.decidedAt()));
            statement.setString(15, PersistenceSupport.trimToNull(plan.executedAt()));
            statement.setString(16, PersistenceSupport.trimToNull(plan.executedAttemptId()));
            statement.setString(17, createdAt);
            statement.setString(18, updatedAt);
            statement.executeUpdate();
            long id = readGeneratedKey(statement, connection);
            log.info("跟进计划已创建 | planId={} | type={} | status={} | contactKey={}",
                    planId, planType, status, plan.contactKey());
            return new FollowupPlan(id, planId, account, platform,
                    PersistenceSupport.trimToNull(plan.encryptId()),
                    PersistenceSupport.trimToNull(plan.encryptUserId()),
                    PersistenceSupport.trimToNull(plan.contactKey()),
                    PersistenceSupport.trimToNull(plan.jobName()),
                    PersistenceSupport.trimToNull(plan.companyName()),
                    planType, status, PersistenceSupport.trimToNull(plan.reason()),
                    PersistenceSupport.trimToNull(plan.parentAttemptId()), 0, suggestedAt,
                    PersistenceSupport.trimToNull(plan.decidedAt()),
                    PersistenceSupport.trimToNull(plan.executedAt()),
                    PersistenceSupport.trimToNull(plan.executedAttemptId()), createdAt, updatedAt);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                log.warn("跟进计划已存在，拒绝重复插入 | planId={}", planId);
                throw new IllegalStateException("跟进计划已存在：planId=" + planId, e);
            }
            log.error("创建跟进计划失败 | planId={}：{}", planId, e.getMessage(), e);
            throw new IllegalStateException("创建跟进计划失败：" + e.getMessage(), e);
        }
    }

    /**
     * 查找某联系人+岗位+类型下仍在流程中的计划（SUGGESTED/APPROVED），最近一条优先。
     * 非空参数才参与筛选。
     */
    public FollowupPlan findActive(String account, String platform, String contactKey,
                                   String encryptId, String planType) {
        List<String> clauses = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        clauses.add(ACTIVE_STATUSES);
        addEquals(clauses, parameters, "account", account);
        addEquals(clauses, parameters, "platform", platform);
        addEquals(clauses, parameters, "contact_key", contactKey);
        addEquals(clauses, parameters, "encrypt_id", encryptId);
        addEquals(clauses, parameters, "plan_type", planType);
        String sql = "SELECT " + SELECT_COLUMNS + " FROM followup_plan WHERE " +
                String.join(" AND ", clauses) + " ORDER BY id DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("查询活跃跟进计划失败 | contactKey={}：{}", contactKey, e.getMessage(), e);
            throw new IllegalStateException("查询活跃跟进计划失败：" + e.getMessage(), e);
        }
    }

    /** 跟进计划列表（SQL 分页，suggested_at DESC, id DESC）。status 为 null/空白时不过滤。 */
    public PageResult<FollowupPlan> list(String status, int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        List<String> clauses = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (hasText(status)) {
            clauses.add("status = ?");
            parameters.add(status.trim());
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        try (Connection connection = dataSource.getConnection()) {
            long total;
            String countSql = "SELECT COUNT(*) FROM followup_plan" + where;
            try (PreparedStatement statement = connection.prepareStatement(countSql)) {
                bind(statement, parameters);
                try (ResultSet resultSet = statement.executeQuery()) {
                    total = resultSet.next() ? resultSet.getLong(1) : 0L;
                }
            }
            List<FollowupPlan> items = new ArrayList<>();
            String sql = "SELECT " + SELECT_COLUMNS + " FROM followup_plan" + where +
                    " ORDER BY suggested_at DESC, id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = bind(statement, parameters);
                statement.setInt(index++, safeSize);
                statement.setLong(index, (long) (safePage - 1) * safeSize);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        items.add(mapRow(resultSet));
                    }
                }
            }
            return new PageResult<>(items, safePage, safeSize, total);
        } catch (SQLException e) {
            log.error("查询跟进计划列表失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询跟进计划列表失败：" + e.getMessage(), e);
        }
    }

    /** 某联系人当前所有 SUGGESTED/APPROVED 计划（取消时用），按 id DESC。 */
    public List<FollowupPlan> listActiveByContact(String account, String platform, String contactKey) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM followup_plan WHERE " + ACTIVE_STATUSES +
                " AND account = ? AND platform = ? AND contact_key = ? ORDER BY id DESC";
        List<FollowupPlan> plans = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setString(2, platform);
            statement.setString(3, contactKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    plans.add(mapRow(resultSet));
                }
            }
            return plans;
        } catch (SQLException e) {
            log.error("查询联系人活跃跟进计划失败 | contactKey={}：{}", contactKey, e.getMessage(), e);
            throw new IllegalStateException("查询联系人活跃跟进计划失败：" + e.getMessage(), e);
        }
    }

    /**
     * 条件状态转移：{@code WHERE plan_id=? AND status=? AND version=?}，成功 version+1。
     *
     * @return 更新后的计划；状态/版本不匹配（行数 0）返回 null
     */
    public FollowupPlan transitionStatus(String planId, String fromStatus, String toStatus,
                                         String reason, int expectedVersion,
                                         String decidedAt, String executedAt, String executedAttemptId) {
        String sql = "UPDATE followup_plan SET status = ?, " +
                "reason = COALESCE(?, reason), decided_at = COALESCE(?, decided_at), " +
                "executed_at = COALESCE(?, executed_at), executed_attempt_id = COALESCE(?, executed_attempt_id), " +
                "version = version + 1, updated_at = ? " +
                "WHERE plan_id = ? AND status = ? AND version = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toStatus);
            statement.setString(2, PersistenceSupport.trimToNull(reason));
            statement.setString(3, PersistenceSupport.trimToNull(decidedAt));
            statement.setString(4, PersistenceSupport.trimToNull(executedAt));
            statement.setString(5, PersistenceSupport.trimToNull(executedAttemptId));
            statement.setString(6, PersistenceSupport.now());
            statement.setString(7, planId);
            statement.setString(8, fromStatus);
            statement.setInt(9, expectedVersion);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.info("跟进计划状态转移未命中（状态/版本冲突）| planId={} | from={} | to={} | version={}",
                        planId, fromStatus, toStatus, expectedVersion);
                return null;
            }
            return findByPlanId(planId);
        } catch (SQLException e) {
            log.error("跟进计划状态转移失败 | planId={}：{}", planId, e.getMessage(), e);
            throw new IllegalStateException("跟进计划状态转移失败：" + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    private FollowupPlan mapRow(ResultSet resultSet) throws SQLException {
        return new FollowupPlan(
                resultSet.getLong("id"),
                resultSet.getString("plan_id"),
                resultSet.getString("account"),
                resultSet.getString("platform"),
                resultSet.getString("encrypt_id"),
                resultSet.getString("encrypt_user_id"),
                resultSet.getString("contact_key"),
                resultSet.getString("job_name"),
                resultSet.getString("company_name"),
                resultSet.getString("plan_type"),
                resultSet.getString("status"),
                resultSet.getString("reason"),
                resultSet.getString("parent_attempt_id"),
                resultSet.getInt("version"),
                resultSet.getString("suggested_at"),
                resultSet.getString("decided_at"),
                resultSet.getString("executed_at"),
                resultSet.getString("executed_attempt_id"),
                resultSet.getString("created_at"),
                resultSet.getString("updated_at"));
    }

    private void addEquals(List<String> clauses, List<Object> parameters, String column, String value) {
        if (hasText(value)) {
            clauses.add(column + " = ?");
            parameters.add(value.trim());
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
}
