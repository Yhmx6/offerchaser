package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B4 正确统计仓库：所有指标都用 SQL 聚合计算，不把历史数据拉进内存。
 * <p>
 * 口径严格按《阶段B-B4-实施契约》第 4 节表格：
 * <ul>
 *   <li>时间范围参数为本地时间字符串 {@code yyyy-MM-dd HH:mm:ss}；只给日期（yyyy-MM-dd）时按当天
 *       00:00:00 ~ 23:59:59 处理；null/空白表示不限；</li>
 *   <li>{@code textConfirmed} 只认 {@code delivery_attempt.text_status='CONFIRMED'} 且按
 *       {@code finished_at} 落范围；旧 {@code boss_data.delivery_status='已投递'} 单独由
 *       {@link #countLegacyDelivered(String)} 统计，绝不混入成功；</li>
 *   <li>未知状态（UNKNOWN/PENDING）单列，不计入成功；</li>
 *   <li>空数据返回 0 / 空列表，不抛异常（非法 metric 除外）。</li>
 * </ul>
 * 注意：{@code boss_data} 是旧表，没有 account 列（单账户历史数据），因此 boss_data 相关统计忽略
 * account 参数（保留签名以统一调用）。
 */
@Service
@Slf4j
public class MetricsRepository {

    private static final Set<String> SERIES_METRICS = Set.of(
            "newJobs", "rulePass", "ruleReject", "textConfirmed",
            "firstContactAttempts", "replies", "followupsExecuted");

    private final DataSource dataSource;

    public MetricsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ==================== 新岗位 ====================

    /**
     * 新增岗位：boss_data.first_seen_at 在范围内去重 encrypt_id；legacyNewJobs 单独统计
     * first_seen_at 为空且 created_at 在范围内的（不混入 newJobs）。
     */
    public Map<String, Long> countNewJobs(String account, String from, String to) {
        Range range = Range.of(from, to);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("(SELECT COUNT(DISTINCT encrypt_id) FROM boss_data WHERE first_seen_at IS NOT NULL");
        appendRange(sql, params, "first_seen_at", range);
        sql.append("), (SELECT COUNT(DISTINCT encrypt_id) FROM boss_data WHERE first_seen_at IS NULL");
        appendRange(sql, params, "created_at", range);
        sql.append(")");
        long[] values = queryLongs(sql.toString(), params, 2);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("newJobs", values[0]);
        result.put("legacyNewJobs", values[1]);
        return result;
    }

    // ==================== 评估（每 run+岗位最新一条） ====================

    /**
     * 范围内每个 (run_id, encrypt_id) 最新一条 job_evaluation 的 rule/ai 计数。
     * 返回 rulePass/ruleReject/ruleReview/aiMatch/aiReject/aiError/aiUnset/evalTotal。
     */
    public Map<String, Long> countLatestEvaluations(String account, String from, String to, String runId) {
        Range range = Range.of(from, to);
        List<Object> filters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        appendRange(where, filters, "created_at", range);
        if (hasText(runId)) {
            where.append(" AND run_id = ?");
            filters.add(runId.trim());
        }
        String whereSql = where.toString();
        String sql = "WITH latest AS (SELECT id, rule_decision, ai_decision FROM job_evaluation" + whereSql +
                " AND id IN (SELECT MAX(id) FROM job_evaluation" + whereSql +
                " GROUP BY run_id, encrypt_id)) " +
                "SELECT COUNT(*), " +
                "COALESCE(SUM(CASE WHEN rule_decision = 'PASS' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN rule_decision = 'REJECT' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN rule_decision = 'REVIEW' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN ai_decision = 'MATCH' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN ai_decision = 'REJECT' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN ai_decision = 'ERROR' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN ai_decision IS NULL THEN 1 ELSE 0 END), 0) " +
                "FROM latest";
        List<Object> params = new ArrayList<>();
        params.addAll(filters);
        params.addAll(filters);
        long[] values = queryLongs(sql, params, 8);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("rulePass", values[1]);
        result.put("ruleReject", values[2]);
        result.put("ruleReview", values[3]);
        result.put("aiMatch", values[4]);
        result.put("aiReject", values[5]);
        result.put("aiError", values[6]);
        result.put("aiUnset", values[7]);
        result.put("evalTotal", values[0]);
        return result;
    }

    // ==================== 投递尝试 ====================

    /**
     * 投递尝试计数（全部 SQL 子查询聚合）。
     * <p>
     * attemptsTotal/firstAttempt/followupAttempt/textPending/image* 按 {@code started_at} 落范围；
     * textConfirmed/textFailed/textUnknown 按 {@code finished_at} 落范围（契约要求 textConfirmed 以
     * finished_at 为准）。
     */
    public Map<String, Long> countAttempts(String account, String from, String to) {
        Range started = Range.of(from, to);
        Range finished = Range.of(from, to);
        StringBuilder sql = new StringBuilder("SELECT ");
        List<Object> params = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        appendAttemptSub(sql, params, aliases, "attemptsTotal", "COUNT(*)",
                "account = ?", account, "started_at", started);
        appendAttemptSub(sql, params, aliases, "firstAttempt", "COUNT(*)",
                "account = ? AND kind = 'FIRST_CONTACT'", account, "started_at", started);
        appendAttemptSub(sql, params, aliases, "followupAttempt", "COUNT(*)",
                "account = ? AND kind = 'FOLLOWUP'", account, "started_at", started);
        appendAttemptSub(sql, params, aliases, "textConfirmed", "COUNT(DISTINCT attempt_id)",
                "account = ? AND text_status = 'CONFIRMED'", account, "finished_at", finished);
        appendAttemptSub(sql, params, aliases, "textFailed", "COUNT(*)",
                "account = ? AND text_status = 'FAILED'", account, "finished_at", finished);
        appendAttemptSub(sql, params, aliases, "textUnknown", "COUNT(*)",
                "account = ? AND text_status = 'UNKNOWN'", account, "finished_at", finished);
        appendAttemptSub(sql, params, aliases, "textPending", "COUNT(*)",
                "account = ? AND text_status = 'PENDING'", account, "started_at", started);
        // 首次沟通（FIRST_CONTACT）的文字状态拆分：供看板"首次沟通动作"卡片使用，不与跟进混在一起
        appendAttemptSub(sql, params, aliases, "firstTextConfirmed", "COUNT(DISTINCT attempt_id)",
                "account = ? AND kind = 'FIRST_CONTACT' AND text_status = 'CONFIRMED'", account, "finished_at", finished);
        appendAttemptSub(sql, params, aliases, "firstTextFailed", "COUNT(*)",
                "account = ? AND kind = 'FIRST_CONTACT' AND text_status = 'FAILED'", account, "finished_at", finished);
        appendAttemptSub(sql, params, aliases, "firstTextUnknown", "COUNT(*)",
                "account = ? AND kind = 'FIRST_CONTACT' AND text_status = 'UNKNOWN'", account, "finished_at", finished);
        appendAttemptSub(sql, params, aliases, "firstTextPending", "COUNT(*)",
                "account = ? AND kind = 'FIRST_CONTACT' AND text_status = 'PENDING'", account, "started_at", started);
        appendAttemptSub(sql, params, aliases, "imageConfirmed", "COUNT(*)",
                "account = ? AND image_status = 'CONFIRMED'", account, "started_at", started);
        appendAttemptSub(sql, params, aliases, "imageFailed", "COUNT(*)",
                "account = ? AND image_status = 'FAILED'", account, "started_at", started);
        appendAttemptSub(sql, params, aliases, "imageUnknown", "COUNT(*)",
                "account = ? AND image_status = 'UNKNOWN'", account, "started_at", started);
        appendAttemptSub(sql, params, aliases, "imageSkipped", "COUNT(*)",
                "account = ? AND image_status = 'SKIPPED'", account, "started_at", started);
        long[] values = queryLongs(sql.toString(), params, aliases.size());
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < aliases.size(); i++) {
            result.put(aliases.get(i), values[i]);
        }
        return result;
    }

    // ==================== 投递事件 ====================

    /** 事件计数：chatRequested（外部动作证据）、textConfirmedEvents。 */
    public Map<String, Long> countEvents(String account, String from, String to) {
        Range range = Range.of(from, to);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        appendEventSub(sql, params, "chatRequested", "CHAT_REQUESTED", account, range);
        sql.append(", ");
        appendEventSub(sql, params, "textConfirmedEvents", "TEXT_CONFIRMED", account, range);
        long[] values = queryLongs(sql.toString(), params, 2);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("chatRequested", values[0]);
        result.put("textConfirmedEvents", values[1]);
        return result;
    }

    // ==================== 联系人 ====================

    /** 联系人状态计数（contact_state 当前状态，无时间范围）。 */
    public Map<String, Long> countContacts(String account) {
        String sql = "SELECT COUNT(*), " +
                "COALESCE(SUM(CASE WHEN reply_state = 'REPLIED' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN reply_state = 'INTERVIEW' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN manual_takeover = 1 THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN stop_contact = 1 THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN last_contact_at IS NOT NULL THEN 1 ELSE 0 END), 0) " +
                "FROM contact_state WHERE account = ?";
        long[] values = queryLongs(sql, List.of(account), 6);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("contacts", values[0]);
        result.put("replied", values[1]);
        result.put("interview", values[2]);
        result.put("manualTakeover", values[3]);
        result.put("stopContact", values[4]);
        result.put("contacted", values[5]);
        return result;
    }

    // ==================== 跟进计划 ====================

    /**
     * 跟进计划状态计数：suggested 按 suggested_at、executed 按 executed_at、
     * approved/rejected 按 decided_at、cancelled 按 updated_at；approvedPending 是当前 APPROVED 数（不限时间）。
     */
    public Map<String, Long> countFollowups(String account, String from, String to) {
        Range range = Range.of(from, to);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        appendFollowupSub(sql, params, "suggested", "status = 'SUGGESTED'", "suggested_at", account, range);
        sql.append(", ");
        appendFollowupSub(sql, params, "approved", "status = 'APPROVED'", "decided_at", account, range);
        sql.append(", ");
        appendFollowupSub(sql, params, "rejected", "status = 'REJECTED'", "decided_at", account, range);
        sql.append(", ");
        appendFollowupSub(sql, params, "cancelled", "status = 'CANCELLED'", "updated_at", account, range);
        sql.append(", ");
        appendFollowupSub(sql, params, "executed", "status = 'EXECUTED'", "executed_at", account, range);
        sql.append(", (SELECT COUNT(*) FROM followup_plan WHERE account = ? AND status = 'APPROVED')");
        params.add(account);
        long[] values = queryLongs(sql.toString(), params, 6);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("suggested", values[0]);
        result.put("approved", values[1]);
        result.put("rejected", values[2]);
        result.put("cancelled", values[3]);
        result.put("executed", values[4]);
        result.put("approvedPending", values[5]);
        return result;
    }

    // ==================== 每日预算 ====================

    /** 指定日预算：limit/consumed/reserved/uncertain 汇总（两类动作相加），remaining 单独计算。 */
    public Map<String, Long> countBudget(String account, String budgetDate) {
        String sql = "SELECT COALESCE(SUM(budget_limit), 0), COALESCE(SUM(consumed), 0), " +
                "COALESCE(SUM(reserved), 0), COALESCE(SUM(uncertain), 0) " +
                "FROM daily_budget WHERE account = ? AND budget_date = ?";
        long[] values = queryLongs(sql, List.of(account, budgetDate), 4);
        long limit = values[0];
        long consumed = values[1];
        long reserved = values[2];
        long uncertain = values[3];
        long remaining = limit > 0 ? Math.max(0, limit - consumed - reserved - uncertain) : -1;
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("limit", limit);
        result.put("consumed", consumed);
        result.put("reserved", reserved);
        result.put("remaining", remaining);
        result.put("uncertain", uncertain);
        return result;
    }

    // ==================== HR 活跃度 ====================

    /** 各 hr_active_status 类别数量（最新 boss_data 每岗位一条），按数量倒序取前 limit 类。 */
    public Map<String, Long> countHrActive(String account, int limit) {
        int safeLimit = limit <= 0 ? 10 : Math.min(limit, 50);
        String sql = "SELECT hr_active_status, COUNT(*) AS cnt FROM boss_data " +
                "WHERE id IN (SELECT MAX(id) FROM boss_data GROUP BY encrypt_id) " +
                "AND hr_active_status IS NOT NULL AND TRIM(hr_active_status) <> '' " +
                "GROUP BY hr_active_status ORDER BY cnt DESC, hr_active_status ASC LIMIT ?";
        Map<String, Long> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.put(resultSet.getString(1), resultSet.getLong(2));
                }
            }
            return result;
        } catch (SQLException e) {
            log.error("统计 HR 活跃度失败：{}", e.getMessage(), e);
            throw new IllegalStateException("统计 HR 活跃度失败：" + e.getMessage(), e);
        }
    }

    // ==================== 旧数据 ====================

    /** 旧数据「已投递」但没有任何 TEXT_CONFIRMED attempt 的数量（不计入成功）。 */
    public int countLegacyDelivered(String account) {
        String sql = "SELECT COUNT(*) FROM boss_data b WHERE b.delivery_status = '已投递' " +
                "AND NOT EXISTS (SELECT 1 FROM delivery_attempt a WHERE a.account = ? " +
                "AND a.encrypt_id = b.encrypt_id AND a.text_status = 'CONFIRMED')";
        try {
            long[] values = queryLongs(sql, List.of(account), 1);
            return (int) values[0];
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    // ==================== 序列 ====================

    /**
     * 指定指标按日序列（date,value），无数据日期补 0。
     * 合法 metric：newJobs/rulePass/ruleReject/textConfirmed/firstContactAttempts/replies/followupsExecuted。
     */
    public List<Map<String, Object>> series(String account, String metric, String from, String to) {
        if (!hasText(metric) || !SERIES_METRICS.contains(metric.trim())) {
            throw new IllegalArgumentException("INVALID_METRIC：" + metric);
        }
        String effectiveMetric = metric.trim();
        Range range = Range.of(from, to);
        List<LocalDate> dates = datesBetween(from, to);
        Map<String, Long> byDay = switch (effectiveMetric) {
            case "newJobs" -> groupedCount(
                    "SELECT substr(first_seen_at, 1, 10) AS d, COUNT(DISTINCT encrypt_id) FROM boss_data " +
                            "WHERE first_seen_at IS NOT NULL", "first_seen_at", range, new ArrayList<>());
            case "rulePass" -> evaluationSeries("PASS", from, to, range);
            case "ruleReject" -> evaluationSeries("REJECT", from, to, range);
            case "textConfirmed" -> groupedCount(
                    "SELECT substr(finished_at, 1, 10) AS d, COUNT(DISTINCT attempt_id) FROM delivery_attempt " +
                            "WHERE account = ? AND text_status = 'CONFIRMED' AND finished_at IS NOT NULL",
                    "finished_at", range, new ArrayList<>(List.of(account)));
            case "firstContactAttempts" -> groupedCount(
                    "SELECT substr(started_at, 1, 10) AS d, COUNT(*) FROM delivery_attempt " +
                            "WHERE account = ? AND kind = 'FIRST_CONTACT'",
                    "started_at", range, new ArrayList<>(List.of(account)));
            case "replies" -> groupedCount(
                    "SELECT substr(last_reply_at, 1, 10) AS d, COUNT(*) FROM contact_state " +
                            "WHERE account = ? AND reply_state IN ('REPLIED','INTERVIEW') " +
                            "AND last_reply_at IS NOT NULL",
                    "last_reply_at", range, new ArrayList<>(List.of(account)));
            case "followupsExecuted" -> groupedCount(
                    "SELECT substr(executed_at, 1, 10) AS d, COUNT(*) FROM followup_plan " +
                            "WHERE account = ? AND status = 'EXECUTED' AND executed_at IS NOT NULL",
                    "executed_at", range, new ArrayList<>(List.of(account)));
            default -> throw new IllegalArgumentException("INVALID_METRIC：" + metric);
        };
        return zeroFilledPoints(dates, byDay);
    }

    /** 按日新发现（boss_data first_seen_at）与来源记录数（job_source discovered_at）。 */
    public List<Map<String, Object>> dailyCollection(String account, String from, String to) {
        Range range = Range.of(from, to);
        Map<String, Long> newJobs = groupedCount(
                "SELECT substr(first_seen_at, 1, 10) AS d, COUNT(DISTINCT encrypt_id) FROM boss_data " +
                        "WHERE first_seen_at IS NOT NULL", "first_seen_at", range, new ArrayList<>());
        Map<String, Long> sourceRecords = groupedCount(
                "SELECT substr(discovered_at, 1, 10) AS d, COUNT(*) FROM job_source WHERE account = ?",
                "discovered_at", range, new ArrayList<>(List.of(account)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate date : datesBetween(from, to)) {
            String key = date.toString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", key);
            row.put("newJobs", newJobs.getOrDefault(key, 0L));
            row.put("sourceRecords", sourceRecords.getOrDefault(key, 0L));
            rows.add(row);
        }
        return rows;
    }

    // ==================== 内部工具 ====================

    private Map<String, Long> evaluationSeries(String decision, String from, String to, Range range) {
        List<Object> filters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        appendRange(where, filters, "created_at", range);
        String whereSql = where.toString();
        String sql = "SELECT substr(created_at, 1, 10) AS d, COUNT(*) FROM job_evaluation" + whereSql +
                " AND rule_decision = ? AND id IN (SELECT MAX(id) FROM job_evaluation" + whereSql +
                " GROUP BY run_id, encrypt_id) GROUP BY d";
        List<Object> params = new ArrayList<>(filters);
        params.add(decision);
        params.addAll(filters);
        return queryGrouped(sql, params);
    }

    /**
     * 通用按日分组查询：baseSql 必须以「SELECT ... FROM ... WHERE ...」结尾（不含范围条件），
     * 本方法在末尾追加 column 的范围条件（若有）后 GROUP BY d。
     */
    private Map<String, Long> groupedCount(String baseSql, String column, Range range,
                                           List<Object> extraParams) {
        StringBuilder sql = new StringBuilder(baseSql);
        List<Object> params = new ArrayList<>(extraParams);
        appendRange(sql, params, column, range);
        sql.append(" GROUP BY d");
        return queryGrouped(sql.toString(), params);
    }

    private Map<String, Long> queryGrouped(String sql, List<Object> params) {
        Map<String, Long> values = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String day = resultSet.getString(1);
                    if (day != null) {
                        values.put(day, resultSet.getLong(2));
                    }
                }
            }
            return values;
        } catch (SQLException e) {
            log.error("统计按日序列失败：{}", e.getMessage(), e);
            throw new IllegalStateException("统计按日序列失败：" + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> zeroFilledPoints(List<LocalDate> dates, Map<String, Long> byDay) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (LocalDate date : dates) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date.toString());
            point.put("value", byDay.getOrDefault(date.toString(), 0L));
            points.add(point);
        }
        return points;
    }

    private void appendAttemptSub(StringBuilder sql, List<Object> params, List<String> aliases,
                                  String alias, String expression, String baseCondition,
                                  String account, String timeColumn, Range range) {
        if (!aliases.isEmpty()) {
            sql.append(", ");
        }
        sql.append("(SELECT ").append(expression).append(" FROM delivery_attempt WHERE ")
                .append(baseCondition);
        params.add(account);
        appendRange(sql, params, timeColumn, range);
        sql.append(") AS ").append(alias);
        aliases.add(alias);
    }

    private void appendEventSub(StringBuilder sql, List<Object> params, String alias,
                                String eventType, String account, Range range) {
        sql.append("(SELECT COUNT(*) FROM delivery_event e JOIN delivery_attempt a " +
                "ON a.attempt_id = e.attempt_id WHERE a.account = ? AND e.event_type = ?");
        params.add(account);
        params.add(eventType);
        appendRange(sql, params, "e.occurred_at", range);
        sql.append(") AS ").append(alias);
    }

    private void appendFollowupSub(StringBuilder sql, List<Object> params, String alias,
                                   String condition, String timeColumn, String account, Range range) {
        sql.append("(SELECT COUNT(*) FROM followup_plan WHERE account = ? AND ").append(condition);
        params.add(account);
        appendRange(sql, params, timeColumn, range);
        sql.append(") AS ").append(alias);
    }

    private void appendRange(StringBuilder sql, List<Object> params, String column, Range range) {
        if (range == null || !hasText(column)) {
            return;
        }
        if (range.start() != null) {
            sql.append(" AND ").append(column).append(" >= ?");
            params.add(range.start());
        }
        if (range.end() != null) {
            sql.append(" AND ").append(column).append(" <= ?");
            params.add(range.end());
        }
    }

    private long[] queryLongs(String sql, List<Object> params, int expected) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                long[] values = new long[expected];
                if (resultSet.next()) {
                    for (int i = 0; i < expected; i++) {
                        values[i] = resultSet.getLong(i + 1);
                    }
                }
                return values;
            }
        } catch (SQLException e) {
            log.error("SQL 聚合统计失败：{}", e.getMessage(), e);
            throw new IllegalStateException("SQL 聚合统计失败：" + e.getMessage(), e);
        }
    }

    private void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        int index = 1;
        for (Object param : params) {
            statement.setObject(index++, param);
        }
    }

    private List<LocalDate> datesBetween(String from, String to) {
        LocalDate start = parseDate(from);
        LocalDate end = parseDate(to);
        if (start == null || end == null || start.isAfter(end)) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = start;
        int guard = 0;
        while (!cursor.isAfter(end) && guard <= 3660) {
            dates.add(cursor);
            cursor = cursor.plusDays(1);
            guard++;
        }
        return dates;
    }

    private LocalDate parseDate(String value) {
        String trimmed = PersistenceSupport.trimToNull(value);
        if (trimmed == null || trimmed.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 时间范围（本地时间字符串，已归一化为 [start,end]；字段为 null 表示不限）。 */
    private record Range(String start, String end) {
        static Range of(String from, String to) {
            String start = normalizeStart(from);
            String end = normalizeEnd(to);
            return new Range(start, end);
        }

        private static String normalizeStart(String value) {
            String trimmed = PersistenceSupport.trimToNull(value);
            if (trimmed == null) {
                return null;
            }
            return trimmed.length() == 10 ? trimmed + " 00:00:00" : trimmed;
        }

        private static String normalizeEnd(String value) {
            String trimmed = PersistenceSupport.trimToNull(value);
            if (trimmed == null) {
                return null;
            }
            return trimmed.length() == 10 ? trimmed + " 23:59:59" : trimmed;
        }
    }
}

