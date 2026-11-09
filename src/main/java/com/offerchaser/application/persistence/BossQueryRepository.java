package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B1 管理台只读查询仓库：任务记录、评估记录、投递明细与人工核对计数。
 * <p>
 * 全部使用 SQL 分页（LIMIT/OFFSET），不做内存分页；返回的 Map key 严格按
 * 《阶段B-B1-实施契约》第 2.2 节的 JSON 字段命名（camelCase），时间统一为
 * {@code yyyy-MM-dd HH:mm:ss} 字符串。page/size 会兜底规范化（page&ge;1，size 1..100），
 * from/to 非法格式直接忽略该筛选条件（controller 可能原样透传）。
 */
@Service
@Slf4j
public class BossQueryRepository {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final com.fasterxml.jackson.databind.ObjectMapper RUN_TEMPLATE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final String RUN_COLUMNS =
            "run_id, platform, state, stop_reason, error, started_at, ended_at, updated_at, config_snapshot";
    private static final String RUN_SELECT = "SELECT " + RUN_COLUMNS + " FROM task_run";
    private static final String RUN_ORDER = "started_at DESC, id DESC";

    private static final String EVALUATION_SELECT = "SELECT id, run_id, encrypt_id, encrypt_user_id, " +
            "job_name, company_name, job_url, source_keyword, source_city, snapshot_hash, rule_version, " +
            "rule_decision, rule_reason_code, rule_evidence, ai_decision, ai_error_code, ai_analysis, " +
            "ai_message, ai_model, prompt_tokens, completion_tokens, duration_ms, rule_config_version, " +
            "rules_hash, created_at FROM job_evaluation";
    private static final String EVALUATION_ORDER = "created_at DESC, id DESC";

    private static final String ATTEMPT_COLUMNS =
            "attempt_id, run_id, kind, encrypt_id, encrypt_user_id, job_name, company_name, job_url, " +
                    "text_status, image_status, text_evidence, image_evidence, error_code, error_detail, " +
                    "started_at, finished_at, version, manual_resolution, resolved_at, resolve_reason, " +
                    "contact_key, parent_attempt_id";
    private static final String ATTEMPT_SELECT = "SELECT " + ATTEMPT_COLUMNS + " FROM delivery_attempt";
    private static final String ATTEMPT_ORDER = "started_at DESC, id DESC";

    private static final String EVENT_SELECT =
            "SELECT event_type, evidence_level, detail, occurred_at FROM delivery_event";

    /** 重评数据源：从 boss_data 读取的 18 个字段（返回值 key 为 camelCase）。 */
    private static final String BOSS_DATA_COLUMNS =
            "encrypt_id, encrypt_user_id, job_name, salary, location, experience, degree, job_description, " +
                    "company_name, industry, financing_stage, company_scale, company_address, recruitment_status, " +
                    "hr_name, hr_position, hr_active_status, job_url";
    private static final String BOSS_DATA_SELECT = "SELECT " + BOSS_DATA_COLUMNS + " FROM boss_data";
    private static final int DEFAULT_RECHECK_LIMIT = 50;
    private static final int MAX_RECHECK_LIMIT = 500;

    private final DataSource dataSource;

    public BossQueryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ==================== 任务记录 ====================

    /**
     * 任务运行记录分页（按 started_at DESC）。
     *
     * @param status 任务状态（state 相等筛选；null/空白不过滤）
     * @param from   started_at &gt;= from（格式非法忽略）
     * @param to     started_at &lt;= to（格式非法忽略）
     */
    public PageResult<Map<String, Object>> listRuns(String status, String from, String to, int page, int size) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        SqlFilter filter = new SqlFilter();
        if (hasText(status)) {
            filter.add("state = ?", status.trim());
        }
        addTimeRange(filter, "started_at", from, to);
        try (Connection connection = dataSource.getConnection()) {
            long total = countRows(connection, "task_run", filter);
            List<Map<String, Object>> items = queryRows(connection, RUN_SELECT, filter, RUN_ORDER,
                    safeSize, offset(safePage, safeSize), this::mapRun);
            return new PageResult<>(items, safePage, safeSize, total);
        } catch (SQLException e) {
            log.error("查询任务运行记录失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询任务运行记录失败：" + e.getMessage(), e);
        }
    }

    // ==================== 评估记录 ====================

    /**
     * 岗位评估记录分页（按 created_at DESC, id DESC）。
     * keyword 同时匹配岗位名与公司名（LIKE）。
     * <p>
     * 旧签名：{@code latestOnly=false, ruleConfigVersion=null}。
     */
    public PageResult<Map<String, Object>> listEvaluations(String runId, String ruleDecision, String aiDecision,
                                                           String reasonCode, String encryptId, String keyword,
                                                           String from, String to, int page, int size) {
        return listEvaluations(runId, ruleDecision, aiDecision, reasonCode, encryptId, keyword,
                from, to, page, size, false, null);
    }

    /**
     * 岗位评估记录分页（B2 扩展的另一种参数顺序：latestOnly/ruleConfigVersion 在 page/size 之前）。
     * <p>
     * 保留两种重载是为了兼容 controller 调用顺序；语义与
     * {@link #listEvaluations(String, String, String, String, String, String, String, String, int, int, boolean, Integer)}
     * 完全一致。
     */
    public PageResult<Map<String, Object>> listEvaluations(String runId, String ruleDecision, String aiDecision,
                                                           String reasonCode, String encryptId, String keyword,
                                                           String from, String to,
                                                           boolean latestOnly, Integer ruleConfigVersion,
                                                           int page, int size) {
        return listEvaluations(runId, ruleDecision, aiDecision, reasonCode, encryptId, keyword,
                from, to, page, size, latestOnly, ruleConfigVersion);
    }

    /**
     * 岗位评估记录分页（B2 扩展）。
     *
     * @param latestOnly        true 时每个 encrypt_id 只取最新一条（{@code id IN (SELECT MAX(id) ... GROUP BY encrypt_id)}）
     * @param ruleConfigVersion 非 null 时只返回该规则版本的评估记录
     */
    public PageResult<Map<String, Object>> listEvaluations(String runId, String ruleDecision, String aiDecision,
                                                           String reasonCode, String encryptId, String keyword,
                                                           String from, String to, int page, int size,
                                                           boolean latestOnly, Integer ruleConfigVersion) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        SqlFilter filter = new SqlFilter();
        if (hasText(runId)) {
            filter.add("run_id = ?", runId.trim());
        }
        if (hasText(ruleDecision)) {
            filter.add("rule_decision = ?", ruleDecision.trim());
        }
        if (hasText(aiDecision)) {
            filter.add("ai_decision = ?", aiDecision.trim());
        }
        if (hasText(reasonCode)) {
            filter.add("rule_reason_code = ?", reasonCode.trim());
        }
        if (hasText(encryptId)) {
            filter.add("encrypt_id = ?", encryptId.trim());
        }
        if (hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            filter.add("(job_name LIKE ? OR company_name LIKE ?)", like, like);
        }
        if (latestOnly) {
            filter.add("id IN (SELECT MAX(id) FROM job_evaluation GROUP BY encrypt_id)");
        }
        if (ruleConfigVersion != null) {
            filter.add("rule_config_version = ?", ruleConfigVersion);
        }
        addTimeRange(filter, "created_at", from, to);
        try (Connection connection = dataSource.getConnection()) {
            long total = countRows(connection, "job_evaluation", filter);
            List<Map<String, Object>> items = queryRows(connection, EVALUATION_SELECT, filter, EVALUATION_ORDER,
                    safeSize, offset(safePage, safeSize), this::mapEvaluation);
            return new PageResult<>(items, safePage, safeSize, total);
        } catch (SQLException e) {
            log.error("查询岗位评估记录失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询岗位评估记录失败：" + e.getMessage(), e);
        }
    }

    // ==================== 投递明细 ====================

    /**
     * 投递尝试分页（按 started_at DESC, id DESC），包含 V4 人工核对字段。
     */
    public PageResult<Map<String, Object>> listAttempts(String runId, String kind, String textStatus,
                                                        String imageStatus, String encryptId,
                                                        String from, String to, int page, int size) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        SqlFilter filter = new SqlFilter();
        if (hasText(runId)) {
            filter.add("run_id = ?", runId.trim());
        }
        if (hasText(kind)) {
            filter.add("kind = ?", kind.trim());
        }
        if (hasText(textStatus)) {
            filter.add("text_status = ?", textStatus.trim());
        }
        if (hasText(imageStatus)) {
            filter.add("image_status = ?", imageStatus.trim());
        }
        if (hasText(encryptId)) {
            filter.add("encrypt_id = ?", encryptId.trim());
        }
        addTimeRange(filter, "started_at", from, to);
        try (Connection connection = dataSource.getConnection()) {
            long total = countRows(connection, "delivery_attempt", filter);
            List<Map<String, Object>> items = queryRows(connection, ATTEMPT_SELECT, filter, ATTEMPT_ORDER,
                    safeSize, offset(safePage, safeSize), this::mapAttempt);
            return new PageResult<>(items, safePage, safeSize, total);
        } catch (SQLException e) {
            log.error("查询投递明细失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询投递明细失败：" + e.getMessage(), e);
        }
    }

    /**
     * 投递尝试详情：{@code {attempt, events, evaluation}}。
     * <p>
     * evaluation 优先按 attempt 的 encrypt_id + run_id 匹配最近一条；找不到再按 encrypt_id 最近一条；
     * 都没有则为 null。attempt 不存在返回 null。
     */
    public Map<String, Object> getAttemptDetail(String attemptId) {
        if (!hasText(attemptId)) {
            return null;
        }
        try (Connection connection = dataSource.getConnection()) {
            Map<String, Object> attempt = null;
            String encryptId = null;
            String runId = null;
            try (PreparedStatement statement = connection.prepareStatement(
                    ATTEMPT_SELECT + " WHERE attempt_id = ? LIMIT 1")) {
                statement.setString(1, attemptId.trim());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        attempt = mapAttempt(resultSet);
                        encryptId = resultSet.getString("encrypt_id");
                        runId = resultSet.getString("run_id");
                    }
                }
            }
            if (attempt == null) {
                return null;
            }
            List<Map<String, Object>> events = queryEvents(connection, attemptId.trim());
            Map<String, Object> evaluation = findLatestEvaluation(connection, encryptId, runId);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("attempt", attempt);
            detail.put("events", events);
            detail.put("evaluation", evaluation);
            return detail;
        } catch (SQLException e) {
            log.error("查询投递详情失败 | attemptId={}：{}", attemptId, e.getMessage(), e);
            throw new IllegalStateException("查询投递详情失败：" + e.getMessage(), e);
        }
    }

    /**
     * 待人工核对数量：首次沟通中文字状态为 UNKNOWN/PENDING 且尚未人工核对的记录数。
     * 查询失败（例如迁移未完成）时返回 0，避免拖垮管理台状态接口。
     */
    public int countPendingResolutions() {
        String sql = "SELECT COUNT(*) FROM delivery_attempt " +
                "WHERE kind = 'FIRST_CONTACT' AND text_status IN ('UNKNOWN','PENDING') " +
                "AND manual_resolution IS NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("统计待人工核对数量失败：{}", e.getMessage(), e);
            return 0;
        }
    }

    // ==================== 规则重评数据源（B2） ====================

    /**
     * 为「只重评不发送」提供岗位快照数据（只读，不改任何投递状态）。
     * <p>
     * 取数优先级：
     * <ol>
     *   <li>{@code encryptIds} 非空：按传入的 encrypt_id 集合取 boss_data（每个 encrypt_id 取最新一行）；</li>
     *   <li>{@code encryptIds} 为空且 {@code runId} 非空：取该 run 在 job_evaluation 中出现过的 encrypt_id
     *       （按该 encrypt_id 最新评估 id 倒序）；</li>
     *   <li>都为空：取最近 {@code limit} 条 boss_data。</li>
     * </ol>
     * 结果一律按 boss_data.id DESC 排序，limit &lt;= 0 默认 50，最大 500。
     *
     * @return 每行 18 个 camelCase key 的 Map；空库/无匹配返回空列表
     */
    public List<Map<String, Object>> findJobRowsForRecheck(List<String> encryptIds, String runId, int limit) {
        int safeLimit = safeRecheckLimit(limit);
        List<String> normalizedIds = normalizeEncryptIds(encryptIds);
        try (Connection connection = dataSource.getConnection()) {
            if (!normalizedIds.isEmpty()) {
                return queryJobRowsByEncryptIds(connection, normalizedIds, safeLimit);
            }
            String effectiveRunId = PersistenceSupport.trimToNull(runId);
            if (effectiveRunId != null) {
                return queryJobRowsByRunId(connection, effectiveRunId, safeLimit);
            }
            return queryRecentJobRows(connection, safeLimit);
        } catch (SQLException e) {
            log.error("查询重评岗位数据失败 | runId={} | encryptIds={}：{}",
                    runId, normalizedIds.size(), e.getMessage(), e);
            throw new IllegalStateException("查询重评岗位数据失败：" + e.getMessage(), e);
        }
    }

    /** 按 encrypt_id 取 boss_data 最新一行；不存在返回 null（同一 encrypt_id 多行取 id 最大）。 */
    public Map<String, Object> findJobRowByEncryptId(String encryptId) {
        if (!hasText(encryptId)) {
            return null;
        }
        String sql = BOSS_DATA_SELECT + " WHERE encrypt_id = ? ORDER BY id DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, encryptId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapJobRow(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("按 encrypt_id 查询岗位失败 | encryptId={}：{}", encryptId, e.getMessage(), e);
            throw new IllegalStateException("按 encryptId 查询岗位失败：" + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> queryJobRowsByEncryptIds(Connection connection, List<String> encryptIds,
                                                               int limit) throws SQLException {
        String placeholders = placeholders(encryptIds.size());
        String sql = BOSS_DATA_SELECT +
                " WHERE id IN (SELECT MAX(id) FROM boss_data WHERE encrypt_id IN (" + placeholders + ")" +
                " GROUP BY encrypt_id) ORDER BY id DESC LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String encryptId : encryptIds) {
                statement.setString(index++, encryptId);
            }
            statement.setInt(index, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(mapJobRow(resultSet));
                }
            }
        }
        return rows;
    }

    private List<Map<String, Object>> queryJobRowsByRunId(Connection connection, String runId, int limit)
            throws SQLException {
        String sql = BOSS_DATA_SELECT +
                " WHERE id IN (" +
                "  SELECT MAX(b.id) FROM boss_data b WHERE b.encrypt_id IN (" +
                "    SELECT encrypt_id FROM job_evaluation WHERE run_id = ? AND encrypt_id IS NOT NULL" +
                "  ) GROUP BY b.encrypt_id" +
                ") ORDER BY id DESC LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(mapJobRow(resultSet));
                }
            }
        }
        return rows;
    }

    private List<Map<String, Object>> queryRecentJobRows(Connection connection, int limit) throws SQLException {
        String sql = BOSS_DATA_SELECT + " ORDER BY id DESC LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(mapJobRow(resultSet));
                }
            }
        }
        return rows;
    }

    private Map<String, Object> mapJobRow(ResultSet resultSet) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("encryptId", resultSet.getString("encrypt_id"));
        item.put("encryptUserId", resultSet.getString("encrypt_user_id"));
        item.put("jobName", resultSet.getString("job_name"));
        item.put("salary", resultSet.getString("salary"));
        item.put("location", resultSet.getString("location"));
        item.put("experience", resultSet.getString("experience"));
        item.put("degree", resultSet.getString("degree"));
        item.put("jobDescription", resultSet.getString("job_description"));
        item.put("companyName", resultSet.getString("company_name"));
        item.put("industry", resultSet.getString("industry"));
        item.put("financingStage", resultSet.getString("financing_stage"));
        item.put("companyScale", resultSet.getString("company_scale"));
        item.put("companyAddress", resultSet.getString("company_address"));
        item.put("recruitmentStatus", resultSet.getString("recruitment_status"));
        item.put("hrName", resultSet.getString("hr_name"));
        item.put("hrPosition", resultSet.getString("hr_position"));
        item.put("hrActiveStatus", resultSet.getString("hr_active_status"));
        item.put("jobUrl", resultSet.getString("job_url"));
        return item;
    }

    private List<String> normalizeEncryptIds(List<String> encryptIds) {
        if (encryptIds == null || encryptIds.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String encryptId : encryptIds) {
            String value = PersistenceSupport.trimToNull(encryptId);
            if (value != null) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private int safeRecheckLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_RECHECK_LIMIT;
        }
        return Math.min(limit, MAX_RECHECK_LIMIT);
    }

    // ==================== B3 联系人 / 跟进查询 ====================

    /**
     * 某联系人已确认的首次沟通（id DESC）。
     * 每行 key：attemptId, runId, encryptId, contactKey, jobName, companyName, startedAt。
     *
     * @param since 可空；非空时只取 started_at &gt;= since
     */
    public List<Map<String, Object>> findConfirmedFirstContactsForContact(String account, String contactKey,
                                                                         String since, int limit) {
        int safeLimit = safeRecheckLimit(limit);
        String sql = "SELECT attempt_id, run_id, encrypt_id, contact_key, job_name, company_name, started_at " +
                "FROM delivery_attempt " +
                "WHERE account = ? AND kind = 'FIRST_CONTACT' AND text_status = 'CONFIRMED' AND contact_key = ?" +
                (hasText(since) ? " AND started_at >= ?" : "") +
                " ORDER BY id DESC LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, account);
            statement.setString(index++, contactKey);
            if (hasText(since)) {
                statement.setString(index++, since.trim());
            }
            statement.setInt(index, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("attemptId", resultSet.getString("attempt_id"));
                    item.put("runId", resultSet.getString("run_id"));
                    item.put("encryptId", resultSet.getString("encrypt_id"));
                    item.put("contactKey", resultSet.getString("contact_key"));
                    item.put("jobName", resultSet.getString("job_name"));
                    item.put("companyName", resultSet.getString("company_name"));
                    item.put("startedAt", resultSet.getString("started_at"));
                    rows.add(item);
                }
            }
            return rows;
        } catch (SQLException e) {
            log.error("查询联系人已确认首次沟通失败 | contactKey={}：{}", contactKey, e.getMessage(), e);
            throw new IllegalStateException("查询联系人已确认首次沟通失败：" + e.getMessage(), e);
        }
    }

    /** 该 contact+job 是否已有确认成功的图片（B3 图片重复保护）。查询失败返回 false 并记 ERROR。 */
    public boolean hasConfirmedImageForContactJob(String account, String contactKey, String encryptId) {
        if (!hasText(account) || !hasText(contactKey) || !hasText(encryptId)) {
            return false;
        }
        String sql = "SELECT COUNT(*) FROM delivery_attempt " +
                "WHERE account = ? AND contact_key = ? AND encrypt_id = ? " +
                "AND text_status = 'CONFIRMED' AND image_status = 'CONFIRMED'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account.trim());
            statement.setString(2, contactKey.trim());
            statement.setString(3, encryptId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("查询联系人已确认图片失败 | contactKey={} | encryptId={}：{}",
                    contactKey, encryptId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 候选跟进：每个 (contact_key, encrypt_id) 最近的 CONFIRMED 首次沟通一条，contact_key 为空的不返回。
     * 每行 key：attemptId, runId, encryptId, encryptUserId, contactKey, jobName, companyName, startedAt。
     */
    public List<Map<String, Object>> findCandidateFollowupAttempts(String account, int limit) {
        int safeLimit = safeRecheckLimit(limit);
        String sql = "SELECT attempt_id, run_id, encrypt_id, encrypt_user_id, contact_key, job_name, " +
                "company_name, started_at FROM delivery_attempt " +
                "WHERE account = ? AND kind = 'FIRST_CONTACT' AND text_status = 'CONFIRMED' " +
                "AND contact_key IS NOT NULL AND id IN (" +
                "  SELECT MAX(id) FROM delivery_attempt WHERE account = ? AND kind = 'FIRST_CONTACT' " +
                "  AND text_status = 'CONFIRMED' AND contact_key IS NOT NULL " +
                "  GROUP BY contact_key, encrypt_id" +
                ") ORDER BY started_at DESC, id DESC LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setString(2, account);
            statement.setInt(3, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("attemptId", resultSet.getString("attempt_id"));
                    item.put("runId", resultSet.getString("run_id"));
                    item.put("encryptId", resultSet.getString("encrypt_id"));
                    item.put("encryptUserId", resultSet.getString("encrypt_user_id"));
                    item.put("contactKey", resultSet.getString("contact_key"));
                    item.put("jobName", resultSet.getString("job_name"));
                    item.put("companyName", resultSet.getString("company_name"));
                    item.put("startedAt", resultSet.getString("started_at"));
                    rows.add(item);
                }
            }
            return rows;
        } catch (SQLException e) {
            log.error("查询候选跟进列表失败 | account={}：{}", account, e.getMessage(), e);
            throw new IllegalStateException("查询候选跟进列表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 图片补发计划校验所需的原 attempt。
     * 每行 key：attemptId, runId, encryptId, encryptUserId, contactKey, parentAttemptId,
     * textStatus, imageStatus, jobName, companyName, startedAt, finishedAt；不存在返回 null。
     */
    public Map<String, Object> findAttemptForImageResend(String attemptId) {
        if (!hasText(attemptId)) {
            return null;
        }
        String sql = "SELECT " + ATTEMPT_COLUMNS + " FROM delivery_attempt WHERE attempt_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attemptId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("attemptId", resultSet.getString("attempt_id"));
                item.put("runId", resultSet.getString("run_id"));
                item.put("encryptId", resultSet.getString("encrypt_id"));
                item.put("encryptUserId", resultSet.getString("encrypt_user_id"));
                item.put("contactKey", resultSet.getString("contact_key"));
                item.put("parentAttemptId", resultSet.getString("parent_attempt_id"));
                item.put("textStatus", resultSet.getString("text_status"));
                item.put("imageStatus", resultSet.getString("image_status"));
                item.put("jobName", resultSet.getString("job_name"));
                item.put("companyName", resultSet.getString("company_name"));
                item.put("startedAt", resultSet.getString("started_at"));
                item.put("finishedAt", resultSet.getString("finished_at"));
                return item;
            }
        } catch (SQLException e) {
            log.error("查询图片补发原 attempt 失败 | attemptId={}：{}", attemptId, e.getMessage(), e);
            throw new IllegalStateException("查询图片补发原 attempt 失败：" + e.getMessage(), e);
        }
    }

    /** 某联系人最近投递尝试（attempts 列表既有 camelCase 字段 + contactKey/parentAttemptId）。 */
    public List<Map<String, Object>> findAttemptsByContact(String account, String contactKey, int limit) {
        int safeLimit = safeRecheckLimit(limit);
        String sql = ATTEMPT_SELECT + " WHERE account = ? AND contact_key = ? " +
                "ORDER BY started_at DESC, id DESC LIMIT ?";
        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setString(2, contactKey);
            statement.setInt(3, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    items.add(mapAttempt(resultSet));
                }
            }
            return items;
        } catch (SQLException e) {
            log.error("查询联系人投递尝试失败 | contactKey={}：{}", contactKey, e.getMessage(), e);
            throw new IllegalStateException("查询联系人投递尝试失败：" + e.getMessage(), e);
        }
    }

    // ==================== 行映射 ====================

    private Map<String, Object> mapRun(ResultSet resultSet) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("runId", resultSet.getString("run_id"));
        item.put("platform", resultSet.getString("platform"));
        item.put("state", resultSet.getString("state"));
        item.put("stopReason", resultSet.getString("stop_reason"));
        item.put("error", resultSet.getString("error"));
        item.put("startedAt", resultSet.getString("started_at"));
        item.put("endedAt", resultSet.getString("ended_at"));
        item.put("updatedAt", resultSet.getString("updated_at"));
        // 阶段 C 收尾：从 config_snapshot 解析本轮使用的方向模板；旧记录没有 template 时为 null
        Map<String, Object> template = parseRunTemplate(resultSet.getString("config_snapshot"));
        item.put("templateId", template.get("id"));
        item.put("templateName", template.get("name"));
        item.put("templateDirection", template.get("direction"));
        return item;
    }

    private Map<String, Object> parseRunTemplate(String configSnapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (configSnapshot == null || configSnapshot.isBlank()) {
            return result;
        }
        try {
            var node = RUN_TEMPLATE_MAPPER.readTree(configSnapshot).path("template");
            if (node != null && node.isObject()) {
                if (node.hasNonNull("id")) {
                    result.put("id", node.get("id").asLong());
                }
                if (node.hasNonNull("name")) {
                    result.put("name", node.get("name").asText());
                }
                if (node.hasNonNull("direction")) {
                    result.put("direction", node.get("direction").asText());
                }
            }
        } catch (Exception e) {
            log.debug("解析任务模板快照失败: {}", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> mapEvaluation(ResultSet resultSet) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", resultSet.getLong("id"));
        item.put("runId", resultSet.getString("run_id"));
        item.put("encryptId", resultSet.getString("encrypt_id"));
        item.put("encryptUserId", resultSet.getString("encrypt_user_id"));
        item.put("jobName", resultSet.getString("job_name"));
        item.put("companyName", resultSet.getString("company_name"));
        item.put("jobUrl", resultSet.getString("job_url"));
        item.put("sourceKeyword", resultSet.getString("source_keyword"));
        item.put("sourceCity", resultSet.getString("source_city"));
        item.put("snapshotHash", resultSet.getString("snapshot_hash"));
        item.put("ruleVersion", resultSet.getString("rule_version"));
        item.put("ruleDecision", resultSet.getString("rule_decision"));
        item.put("ruleReasonCode", resultSet.getString("rule_reason_code"));
        item.put("ruleEvidence", resultSet.getString("rule_evidence"));
        item.put("aiDecision", resultSet.getString("ai_decision"));
        item.put("aiErrorCode", resultSet.getString("ai_error_code"));
        item.put("aiAnalysis", resultSet.getString("ai_analysis"));
        item.put("aiMessage", resultSet.getString("ai_message"));
        item.put("aiModel", resultSet.getString("ai_model"));
        item.put("promptTokens", nullableInt(resultSet, "prompt_tokens"));
        item.put("completionTokens", nullableInt(resultSet, "completion_tokens"));
        item.put("durationMs", nullableLong(resultSet, "duration_ms"));
        item.put("ruleConfigVersion", nullableInt(resultSet, "rule_config_version"));
        item.put("rulesHash", resultSet.getString("rules_hash"));
        item.put("createdAt", resultSet.getString("created_at"));
        return item;
    }

    private Map<String, Object> mapAttempt(ResultSet resultSet) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("attemptId", resultSet.getString("attempt_id"));
        item.put("runId", resultSet.getString("run_id"));
        item.put("kind", resultSet.getString("kind"));
        item.put("encryptId", resultSet.getString("encrypt_id"));
        item.put("encryptUserId", resultSet.getString("encrypt_user_id"));
        item.put("jobName", resultSet.getString("job_name"));
        item.put("companyName", resultSet.getString("company_name"));
        item.put("jobUrl", resultSet.getString("job_url"));
        item.put("textStatus", resultSet.getString("text_status"));
        item.put("imageStatus", resultSet.getString("image_status"));
        item.put("textEvidence", resultSet.getString("text_evidence"));
        item.put("imageEvidence", resultSet.getString("image_evidence"));
        item.put("errorCode", resultSet.getString("error_code"));
        item.put("errorDetail", resultSet.getString("error_detail"));
        item.put("startedAt", resultSet.getString("started_at"));
        item.put("finishedAt", resultSet.getString("finished_at"));
        item.put("version", resultSet.getInt("version"));
        item.put("manualResolution", resultSet.getString("manual_resolution"));
        item.put("resolvedAt", resultSet.getString("resolved_at"));
        item.put("resolveReason", resultSet.getString("resolve_reason"));
        // B3：联系人关联（旧 attempt 为 null）
        item.put("contactKey", resultSet.getString("contact_key"));
        item.put("parentAttemptId", resultSet.getString("parent_attempt_id"));
        return item;
    }

    private Map<String, Object> mapEvent(ResultSet resultSet) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventType", resultSet.getString("event_type"));
        item.put("evidenceLevel", resultSet.getString("evidence_level"));
        item.put("detail", resultSet.getString("detail"));
        item.put("occurredAt", resultSet.getString("occurred_at"));
        return item;
    }

    // ==================== 详情子查询 ====================

    private List<Map<String, Object>> queryEvents(Connection connection, String attemptId) throws SQLException {
        String sql = EVENT_SELECT + " WHERE attempt_id = ? ORDER BY occurred_at ASC, id ASC";
        List<Map<String, Object>> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attemptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(mapEvent(resultSet));
                }
            }
        }
        return events;
    }

    /**
     * 详情里的评估记录：优先 encrypt_id + run_id 最近一条；找不到再退化为 encrypt_id 最近一条。
     */
    private Map<String, Object> findLatestEvaluation(Connection connection, String encryptId, String runId)
            throws SQLException {
        if (!hasText(encryptId)) {
            return null;
        }
        Map<String, Object> evaluation = queryLatestEvaluation(connection,
                EVALUATION_SELECT + " WHERE encrypt_id = ? AND run_id = ? ORDER BY " + EVALUATION_ORDER + " LIMIT 1",
                encryptId, runId);
        if (evaluation != null) {
            return evaluation;
        }
        return queryLatestEvaluation(connection,
                EVALUATION_SELECT + " WHERE encrypt_id = ? ORDER BY " + EVALUATION_ORDER + " LIMIT 1",
                encryptId);
    }

    private Map<String, Object> queryLatestEvaluation(Connection connection, String sql, String... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapEvaluation(resultSet) : null;
            }
        }
    }

    // ==================== 分页与 SQL 工具 ====================

    private long countRows(Connection connection, String table, SqlFilter filter) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + filter.whereSql();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, filter.parameters());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private List<Map<String, Object>> queryRows(Connection connection, String selectSql, SqlFilter filter,
                                                String orderBy, int size, long offset,
                                                RowMapper mapper) throws SQLException {
        String sql = selectSql + filter.whereSql() + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?";
        List<Map<String, Object>> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bind(statement, filter.parameters());
            statement.setInt(index++, size);
            statement.setLong(index, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    items.add(mapper.map(resultSet));
                }
            }
        }
        return items;
    }

    private int bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        int index = 1;
        for (Object parameter : parameters) {
            statement.setObject(index++, parameter);
        }
        return index;
    }

    /** from/to 只有格式合法才参与筛选（两者都非法则不加条件）。 */
    private void addTimeRange(SqlFilter filter, String column, String from, String to) {
        if (isValidTimestamp(from)) {
            filter.add(column + " >= ?", from.trim());
        }
        if (isValidTimestamp(to)) {
            filter.add(column + " <= ?", to.trim());
        }
    }

    private static boolean isValidTimestamp(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return PersistenceSupport.parseDateTime(trimmed) != null
                && trimmed.length() == PersistenceSupport.DATE_TIME_PATTERN.length();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static int safePage(int page) {
        return page < 1 ? 1 : page;
    }

    private static int safeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static long offset(int page, int size) {
        return (long) (page - 1) * size;
    }

    private static Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    @FunctionalInterface
    private interface RowMapper {
        Map<String, Object> map(ResultSet resultSet) throws SQLException;
    }

    /** 动态 WHERE 构造器（只拼接固定列名，值全部走 PreparedStatement 参数）。 */
    private static final class SqlFilter {
        private final List<String> clauses = new ArrayList<>();
        private final List<Object> parameters = new ArrayList<>();

        void add(String clause, Object... params) {
            clauses.add(clause);
            parameters.addAll(Arrays.asList(params));
        }

        String whereSql() {
            return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        }

        List<Object> parameters() {
            return parameters;
        }
    }
}
