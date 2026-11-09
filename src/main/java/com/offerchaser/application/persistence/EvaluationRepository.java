package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 岗位评估结果仓库：记录每个岗位的规则结论与 AI 结论，供事后复盘与统计。
 * <p>
 * 评估属于审计数据，落库失败只记 ERROR 日志并继续投递流程，不会把失败当成成功。
 */
@Service
@Slf4j
public class EvaluationRepository {

    private static final String INSERT_SQL =
            "INSERT INTO job_evaluation(run_id, encrypt_id, encrypt_user_id, job_name, company_name, job_url, " +
                    "source_keyword, source_city, snapshot_hash, rule_version, rule_decision, rule_reason_code, " +
                    "rule_evidence, ai_decision, ai_error_code, ai_analysis, ai_message, prompt_version, ai_model, " +
                    "prompt_tokens, completion_tokens, duration_ms, rule_config_version, rules_hash, created_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String TODAY_SUMMARY_SQL =
            "SELECT COUNT(*), " +
                    "COALESCE(SUM(CASE WHEN rule_decision = 'PASS' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN rule_decision = 'REJECT' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN rule_decision = 'REVIEW' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN ai_decision = 'MATCH' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN ai_decision = 'REJECT' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN ai_decision = 'REVIEW' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN ai_decision = 'ERROR' THEN 1 ELSE 0 END), 0) " +
                    "FROM job_evaluation WHERE created_at >= ?";

    private final DataSource dataSource;
    private final ZoneId zone;

    public EvaluationRepository(DataSource dataSource,
                                @Value("${offerchaser.boss.budget-timezone:Asia/Shanghai}") String timezone) {
        this.dataSource = dataSource;
        this.zone = PersistenceSupport.zone(timezone);
    }

    /** 记录一条岗位评估结果。 */
    public void record(EvaluationEntry entry) {
        if (entry == null) {
            return;
        }
        if (PersistenceSupport.trimToNull(entry.getRunId()) == null) {
            log.warn("job_evaluation 记录跳过：runId 为空");
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, entry.getRunId());
            statement.setString(2, entry.getEncryptId());
            statement.setString(3, entry.getEncryptUserId());
            statement.setString(4, entry.getJobName());
            statement.setString(5, entry.getCompanyName());
            statement.setString(6, entry.getJobUrl());
            statement.setString(7, entry.getSourceKeyword());
            statement.setString(8, entry.getSourceCity());
            statement.setString(9, entry.getSnapshotHash());
            statement.setString(10, entry.getRuleVersion());
            statement.setString(11, entry.getRuleDecision());
            statement.setString(12, entry.getRuleReasonCode());
            statement.setString(13, entry.getRuleEvidence());
            statement.setString(14, entry.getAiDecision());
            statement.setString(15, entry.getAiErrorCode());
            statement.setString(16, entry.getAiAnalysis());
            statement.setString(17, entry.getAiMessage());
            statement.setString(18, entry.getPromptVersion());
            statement.setString(19, entry.getAiModel());
            setInteger(statement, 20, entry.getPromptTokens());
            setInteger(statement, 21, entry.getCompletionTokens());
            setLong(statement, 22, entry.getDurationMs());
            setInteger(statement, 23, entry.getRuleConfigVersion());
            statement.setString(24, PersistenceSupport.trimToNull(entry.getRulesHash()));
            statement.setString(25, PersistenceSupport.now());
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("写入 job_evaluation 失败 | runId={} | encryptId={}：{}",
                    entry.getRunId(), entry.getEncryptId(), e.getMessage(), e);
        }
    }

    /** 今日评估汇总：总数与规则/AI 各结论数量。 */
    public Map<String, Object> summaryToday() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", PersistenceSupport.today(zone));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TODAY_SUMMARY_SQL)) {
            statement.setString(1, PersistenceSupport.todayStart(zone));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    summary.put("total", resultSet.getInt(1));
                    summary.put("rulePass", resultSet.getInt(2));
                    summary.put("ruleReject", resultSet.getInt(3));
                    summary.put("ruleReview", resultSet.getInt(4));
                    summary.put("aiMatch", resultSet.getInt(5));
                    summary.put("aiReject", resultSet.getInt(6));
                    summary.put("aiReview", resultSet.getInt(7));
                    summary.put("aiError", resultSet.getInt(8));
                }
            }
        } catch (SQLException e) {
            log.error("读取 job_evaluation 今日汇总失败：{}", e.getMessage(), e);
            summary.put("error", "评估汇总读取失败：" + e.getMessage());
        }
        summary.putIfAbsent("total", 0);
        return summary;
    }

    private void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }
}
