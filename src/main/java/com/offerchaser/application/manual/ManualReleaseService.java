package com.offerchaser.application.manual;

import com.offerchaser.application.persistence.ManualReleaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * D3-B 人工放行队列。
 *
 * <p>规则/AI REVIEW 的岗位不会自动投递；人工批准后只写入 APPROVED 队列，
 * 由下一轮任务消费。执行时仍重新生成/校验话术，并走身份、预算、文字+图片确认。
 * 这里不做任何发送。
 */
@Slf4j
@Service
public class ManualReleaseService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_REASON = 500;
    private static final int MAX_QUEUE = 200;
    private static final int MAX_RELEASES_PER_RUN = 10;

    private final DataSource dataSource;
    private final ManualReleaseRepository repository;

    public ManualReleaseService(DataSource dataSource, ManualReleaseRepository repository) {
        this.dataSource = dataSource;
        this.repository = repository;
    }

    /** 放行队列中的一条 REVEIW 评估（release 可为空 = 尚未决策）。 */
    public record QueueItem(long evaluationId, String releaseId, String releaseStatus, String statusLabel,
                            String jobName, String companyName, String jobUrl,
                            String ruleDecision, String ruleReasonCode, String ruleEvidence,
                            String aiDecision, String aiErrorCode, String aiAnalysis, String aiMessage,
                            String sourceKeyword, String createdAt,
                            String reason, String error, String attemptId,
                            String requestedAt, String consumedAt, String runId) {
    }

    public record Queue(List<QueueItem> items, Map<String, Integer> counts, int maxPerRun) {
    }

    public record EvaluationData(long id, String runId, String encryptId, String encryptUserId,
                                 String jobName, String companyName, String jobUrl,
                                 String sourceKeyword, String ruleDecision, String ruleReasonCode,
                                 String ruleEvidence, String aiDecision, String aiErrorCode,
                                 String aiAnalysis, String aiMessage, String createdAt) {
    }

    public record DecisionResult(boolean approved, String releaseId, String status, String message) {
    }

    /** 评估记录详情（执行器用）。 */
    public EvaluationData findEvaluation(long evaluationId) {
        String sql = "SELECT id, run_id, encrypt_id, encrypt_user_id, job_name, company_name, job_url, " +
                "source_keyword, rule_decision, rule_reason_code, rule_evidence, ai_decision, ai_error_code, " +
                "ai_analysis, ai_message, created_at FROM job_evaluation WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, evaluationId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return new EvaluationData(
                        rs.getLong("id"), text(rs.getString("run_id")), text(rs.getString("encrypt_id")),
                        text(rs.getString("encrypt_user_id")), text(rs.getString("job_name")),
                        text(rs.getString("company_name")), text(rs.getString("job_url")),
                        text(rs.getString("source_keyword")), text(rs.getString("rule_decision")),
                        text(rs.getString("rule_reason_code")), text(rs.getString("rule_evidence")),
                        text(rs.getString("ai_decision")), text(rs.getString("ai_error_code")),
                        text(rs.getString("ai_analysis")), text(rs.getString("ai_message")),
                        text(rs.getString("created_at")));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取评估记录失败：" + e.getMessage(), e);
        }
    }

    /** 队列：每个 encryptId 取最新一条 REVIEW 评估，左连接放行记录。 */
    public Queue queue(String statusFilter) {
        String normalizedFilter = normalizeStatus(statusFilter);
        String sql = "SELECT e.id, e.run_id, e.encrypt_id, e.encrypt_user_id, e.job_name, e.company_name, e.job_url, " +
                "e.rule_decision, e.rule_reason_code, e.rule_evidence, e.ai_decision, e.ai_error_code, " +
                "e.ai_analysis, e.ai_message, e.source_keyword, e.created_at, " +
                "r.release_id, r.status AS release_status, r.reason AS release_reason, " +
                "r.error AS release_error, r.attempt_id AS release_attempt_id, " +
                "r.requested_at, r.consumed_at " +
                "FROM job_evaluation e LEFT JOIN manual_release r ON r.evaluation_id = e.id " +
                "WHERE (e.rule_decision = 'REVIEW' OR e.ai_decision = 'REVIEW') " +
                "AND e.id = (SELECT MAX(e2.id) FROM job_evaluation e2 WHERE e2.encrypt_id = e.encrypt_id " +
                "AND (e2.rule_decision = 'REVIEW' OR e2.ai_decision = 'REVIEW')) " +
                "ORDER BY e.id DESC LIMIT ?";
        List<QueueItem> items = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "APPROVED", "FAILED", "CONSUMED", "REJECTED")) {
            counts.put(status, 0);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, MAX_QUEUE);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String releasedStatus = normalizeReleaseStatus(rs.getString("release_status"));
                    String status = releasedStatus == null ? "PENDING" : releasedStatus;
                    counts.merge(status, 1, Integer::sum);
                    if (!"ALL".equals(normalizedFilter) && !status.equals(normalizedFilter)) {
                        continue;
                    }
                    items.add(new QueueItem(
                            rs.getLong("id"),
                            trimToNull(rs.getString("release_id")),
                            releasedStatus,
                            statusLabel(status),
                            text(rs.getString("job_name")),
                            text(rs.getString("company_name")),
                            text(rs.getString("job_url")),
                            text(rs.getString("rule_decision")),
                            text(rs.getString("rule_reason_code")),
                            text(rs.getString("rule_evidence")),
                            text(rs.getString("ai_decision")),
                            text(rs.getString("ai_error_code")),
                            text(rs.getString("ai_analysis")),
                            text(rs.getString("ai_message")),
                            text(rs.getString("source_keyword")),
                            text(rs.getString("created_at")),
                            text(rs.getString("release_reason")),
                            text(rs.getString("release_error")),
                            trimToNull(rs.getString("release_attempt_id")),
                            text(rs.getString("requested_at")),
                            text(rs.getString("consumed_at")),
                            text(rs.getString("run_id"))));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取人工放行队列失败：" + e.getMessage(), e);
        }
        return new Queue(items, counts, MAX_RELEASES_PER_RUN);
    }

    /**
     * 批准或拒绝一条 REVIEW 评估。
     * APPROVE：写 APPROVED 队列，下一轮任务消费；REJECT：只记拒绝，不投递。
     */
    public DecisionResult decide(long evaluationId, String action, String reason) {
        if (evaluationId <= 0) throw new IllegalArgumentException("evaluationId 非法");
        String normalizedAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(normalizedAction) && !"REJECT".equals(normalizedAction)) {
            throw new IllegalArgumentException("action 只能是 APPROVE 或 REJECT");
        }
        String safeReason = reason == null ? "" : reason.trim();
        if (safeReason.isEmpty()) throw new IllegalArgumentException("必须填写人工放行/拒绝理由");
        if (safeReason.length() > MAX_REASON) throw new IllegalArgumentException("理由不能超过 500 字");

        EvaluationData evaluation = findEvaluation(evaluationId);
        if (evaluation == null) throw new IllegalArgumentException("评估记录不存在：" + evaluationId);
        if (!"REVIEW".equalsIgnoreCase(evaluation.ruleDecision())
                && !"REVIEW".equalsIgnoreCase(evaluation.aiDecision())) {
            throw new IllegalArgumentException("只有规则/AI REVIEW 的评估可以人工放行");
        }

        String releaseId;
        String status;
        ManualReleaseRepository.ManualRelease existing = repository.findByEvaluationId(evaluationId);
        if (existing != null) {
            if ("APPROVED".equals(existing.status()) || "CONSUMED".equals(existing.status())) {
                throw new IllegalStateException("该评估已批准或已执行，不能重复决策");
            }
            releaseId = existing.releaseId();
            if ("APPROVE".equals(normalizedAction)) {
                // FAILED/REJECTED 重新放行：清掉旧错误/attempt/consumed_at，保留人工理由
                repository.reopen(releaseId);
                status = "APPROVED";
            } else {
                repository.updateStatus(releaseId, "REJECTED", null, safeReason);
                status = "REJECTED";
            }
        } else {
            releaseId = UUID.randomUUID().toString();
            status = "APPROVE".equals(normalizedAction) ? "APPROVED" : "REJECTED";
            repository.insert(new ManualReleaseRepository.ManualRelease(
                    releaseId, evaluationId, status, safeReason, now(), now(), null, null, null, null, null));
        }
        log.info("人工放行决策 | evaluationId={} | action={} | status={} | releaseId={}",
                evaluationId, normalizedAction, status, releaseId);
        String message = "APPROVE".equals(normalizedAction)
                ? "已加入人工放行队列；下一轮任务执行，执行时重新生成/校验话术并走完整发送安全链路"
                : "已拒绝该岗位，不会进入投递";
        return new DecisionResult("APPROVE".equals(normalizedAction), releaseId, status, message);
    }

    /** 下一轮任务消费的已批准放行记录（按申请时间升序，最多 10 条）。 */
    public List<ManualReleaseRepository.ManualRelease> approvedForExecution() {
        return repository.findApproved(MAX_RELEASES_PER_RUN);
    }

    /** 执行成功/结果未知：标记已消费并记录 attempt。 */
    public void markConsumed(String releaseId, String attemptId, String note) {
        repository.updateStatus(releaseId, "CONSUMED", attemptId, trimToNull(note));
    }

    /** 执行失败：保留原因，供人工重试/排查。 */
    public void markFailed(String releaseId, String error) {
        repository.updateStatus(releaseId, "FAILED", null, trimToNull(error));
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return "PENDING";
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) return "ALL";
        if (!List.of("PENDING", "APPROVED", "FAILED", "CONSUMED", "REJECTED").contains(normalized)) {
            return "PENDING";
        }
        return normalized;
    }

    private static String normalizeReleaseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return List.of("APPROVED", "REJECTED", "CONSUMED", "FAILED").contains(normalized)
                ? normalized : "FAILED";
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "PENDING" -> "待放行";
            case "APPROVED" -> "已批准 · 待下一轮";
            case "CONSUMED" -> "已执行";
            case "REJECTED" -> "已拒绝";
            case "FAILED" -> "执行失败";
            default -> status;
        };
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
