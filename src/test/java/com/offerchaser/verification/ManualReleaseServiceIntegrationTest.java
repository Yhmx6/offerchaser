package com.offerchaser.verification;

import com.offerchaser.application.manual.ManualReleaseService;
import com.offerchaser.application.persistence.ManualReleaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3-B 人工放行服务验收（真实临时 SQLite，不启动 Spring、不做任何发送）：
 * <ul>
 *   <li>队列只收规则/AI REVIEW，同一岗位取最新一条 REVIEW；</li>
 *   <li>PENDING/APPROVED/FAILED/CONSUMED/REJECTED 计数与状态筛选；</li>
 *   <li>批准只写 APPROVED 队列（下一轮执行，不是立即发送）；拒绝只记录；</li>
 *   <li>理由必填（≤500）、非 REVIEW 400、重复决策 409、失败/拒绝可重新放行；</li>
 *   <li>下一轮消费顺序（requested_at 升序、每次最多 10 条）与消费/失败状态流转。</li>
 * </ul>
 */
class ManualReleaseServiceIntegrationTest {

    private static final String NOW = "2026-11-07 10:00:00";

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private ManualReleaseRepository repository;
    private ManualReleaseService service;
    private int sequence;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        repository = new ManualReleaseRepository(dataSource);
        service = new ManualReleaseService(dataSource, repository);
    }

    // ==================== 队列：只收 REVIEW / 每人取最新 ====================

    @Test
    void queueOnlyContainsRuleOrAiReviewEvaluations() {
        long ruleReview = seedEvaluation("enc-rule-review", "岗位A", "REVIEW", "PASS");
        long aiReview = seedEvaluation("enc-ai-review", "岗位B", "PASS", "REVIEW");
        seedEvaluation("enc-match", "岗位C", "PASS", "MATCH");
        seedEvaluation("enc-reject", "岗位D", "REJECT", "REJECT");
        seedEvaluation("enc-error", "岗位E", "PASS", "ERROR");

        ManualReleaseService.Queue queue = service.queue("ALL");

        assertEquals(List.of(aiReview, ruleReview),
                queue.items().stream().map(ManualReleaseService.QueueItem::evaluationId).toList(),
                "队列只应包含规则 REVIEW 或 AI REVIEW 的评估（按评估 id 倒序展示）");
        Map<String, Integer> counts = queue.counts();
        assertEquals(2, counts.get("PENDING"), "无放行记录视为 PENDING");
        assertEquals(0, counts.get("APPROVED"));
        assertEquals(0, counts.get("FAILED"));
        assertEquals(0, counts.get("CONSUMED"));
        assertEquals(0, counts.get("REJECTED"));
        assertEquals("待放行", queue.items().get(0).statusLabel());
        assertNull(queue.items().get(0).releaseId(), "未决策的队列项不应有 releaseId");
    }

    @Test
    void queueUsesLatestReviewPerJobAndIgnoresNonReviewNewerRows() {
        seedEvaluation("enc-same", "旧 REVIEW", "REVIEW", "REVIEW", "2026-11-06 09:00:00");
        long latest = seedEvaluation("enc-same", "新 REVIEW", "PASS", "REVIEW", "2026-11-07 09:00:00");
        // 同一岗位更新的非 REVIEW 评估不影响“最新 REVIEW”口径
        seedEvaluation("enc-same", "更新但已通过", "PASS", "MATCH", "2026-11-07 11:00:00");
        long other = seedEvaluation("enc-other", "另一个 REVIEW", "REVIEW", "PASS");

        List<Long> ids = service.queue("ALL").items().stream()
                .map(ManualReleaseService.QueueItem::evaluationId).toList();

        assertEquals(List.of(other, latest), ids,
                "同一 encryptId 只保留最新一条 REVIEW 评估，且不会被更新的 PASS 记录顶掉");
        assertEquals("另一个 REVIEW", service.queue("ALL").items().get(0).jobName());
    }

    @Test
    void queueCountsEveryReleaseStatusAndFiltersByStatus() {
        long pending = seedEvaluation("enc-pending", "待放行岗位", "REVIEW", "PASS");
        long approved = seedEvaluation("enc-approved", "已批准岗位", "REVIEW", "PASS");
        long failed = seedEvaluation("enc-failed", "失败岗位", "REVIEW", "PASS");
        long consumed = seedEvaluation("enc-consumed", "已执行岗位", "REVIEW", "PASS");
        long rejected = seedEvaluation("enc-rejected", "已拒绝岗位", "REVIEW", "PASS");
        insertRelease(approved, "APPROVED", "同意放行", "2026-11-07 10:00:01", null);
        insertRelease(failed, "FAILED", "执行失败", "2026-11-07 10:00:02", "AI 重新评估未通过");
        insertRelease(consumed, "CONSUMED", "执行成功", "2026-11-07 10:00:03", null);
        insertRelease(rejected, "REJECTED", "岗位不符", "2026-11-07 10:00:04", null);
        repository.updateStatus(
                repository.findByEvaluationId(consumed).releaseId(), "CONSUMED", "attempt-1", "已发送");

        ManualReleaseService.Queue all = service.queue("ALL");
        assertEquals(Map.of("PENDING", 1, "APPROVED", 1, "FAILED", 1, "CONSUMED", 1, "REJECTED", 1),
                all.counts(), "计数应覆盖全部五种状态");
        assertEquals(5, all.items().size());

        assertEquals(List.of(pending), idsOf(service.queue("PENDING")));
        assertEquals(List.of(approved), idsOf(service.queue("APPROVED")));
        assertEquals(List.of(failed), idsOf(service.queue("FAILED")));
        assertEquals(List.of(consumed), idsOf(service.queue("CONSUMED")));
        assertEquals(List.of(rejected), idsOf(service.queue("REJECTED")));
        // 未传/非法状态回退 PENDING（默认页签）
        assertEquals(List.of(pending), idsOf(service.queue("")));
        assertEquals(List.of(pending), idsOf(service.queue("NOT_A_STATUS")));

        ManualReleaseService.QueueItem consumedItem = service.queue("CONSUMED").items().get(0);
        assertEquals("已执行", consumedItem.statusLabel());
        assertEquals("attempt-1", consumedItem.attemptId());
        assertNotNull(consumedItem.consumedAt(), "已执行记录应有消费时间");
        assertEquals("执行失败", service.queue("FAILED").items().get(0).statusLabel());
        assertTrue(service.queue("APPROVED").items().get(0).statusLabel().contains("待下一轮"),
                "已批准应明确提示下一轮执行");
        assertEquals("已拒绝", service.queue("REJECTED").items().get(0).statusLabel());
    }

    @Test
    void emptyQueueHasZeroCountsAndNoItems() {
        ManualReleaseService.Queue queue = service.queue("ALL");
        assertTrue(queue.items().isEmpty());
        assertEquals(0, queue.counts().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(5, queue.counts().size(), "即使空队列也应返回五种状态的零计数");
    }

    // ==================== 决策：批准 / 拒绝 / 校验 ====================

    @Test
    void approveOnlyWritesApprovedQueueForNextRunNotImmediateSend() {
        long evaluationId = seedEvaluation("enc-approve", "Java 开发", "REVIEW", "REVIEW");

        ManualReleaseService.DecisionResult result =
                service.decide(evaluationId, "approve", " 技术栈匹配，同意下一轮放行 ");

        assertTrue(result.approved());
        assertEquals("APPROVED", result.status());
        assertTrue(result.message().contains("下一轮任务执行"), result.message());
        assertTrue(result.message().contains("完整发送安全链路"), result.message());
        assertTrue(result.message().contains("不是立即发送") || result.message().contains("已加入"),
                result.message());
        ManualReleaseRepository.ManualRelease stored = repository.findByEvaluationId(evaluationId);
        assertNotNull(stored);
        assertEquals("APPROVED", stored.status());
        assertEquals("技术栈匹配，同意下一轮放行", stored.reason(), "理由应去首尾空白后保存");
        assertNotNull(stored.decidedAt(), "人工决定时间应落库");
        assertNull(stored.consumedAt(), "批准只入队，尚未执行/消费");
        assertNull(stored.attemptId());
        assertNull(stored.error());
        // 批准 = 进入下一轮消费列表；服务本身不发送任何消息
        assertEquals(List.of(stored.releaseId()),
                service.approvedForExecution().stream()
                        .map(ManualReleaseRepository.ManualRelease::releaseId).toList());
        assertEquals(List.of(evaluationId), idsOf(service.queue("APPROVED")));
        assertEquals(1, service.queue("PENDING").counts().get("APPROVED"));
    }

    @Test
    void rejectIsRecordedAndNeverExecuted() {
        long evaluationId = seedEvaluation("enc-reject", "外包岗位", "REJECT", "REVIEW");

        ManualReleaseService.DecisionResult result =
                service.decide(evaluationId, "REJECT", "岗位方向不符，拒绝放行");

        assertFalse(result.approved());
        assertEquals("REJECTED", result.status());
        assertTrue(result.message().contains("不会进入投递"), result.message());
        ManualReleaseRepository.ManualRelease stored = repository.findByEvaluationId(evaluationId);
        assertEquals("REJECTED", stored.status());
        assertEquals("岗位方向不符，拒绝放行", stored.reason());
        assertTrue(service.approvedForExecution().isEmpty(), "拒绝不得进入下一轮消费列表");
        assertEquals(List.of(evaluationId), idsOf(service.queue("REJECTED")));
    }

    @Test
    void decisionValidatesReasonActionAndReviewSource() {
        long reviewId = seedEvaluation("enc-validate", "岗位", "REVIEW", "PASS");
        long passId = seedEvaluation("enc-pass", "已通过岗位", "PASS", "MATCH");

        assertThrows(IllegalArgumentException.class, () -> service.decide(reviewId, "APPROVE", null),
                "理由必填（null）");
        assertThrows(IllegalArgumentException.class, () -> service.decide(reviewId, "APPROVE", "   "),
                "理由必填（空白）");
        assertThrows(IllegalArgumentException.class,
                () -> service.decide(reviewId, "APPROVE", "x".repeat(501)), "理由不能超过 500 字");
        assertThrows(IllegalArgumentException.class, () -> service.decide(reviewId, "PASS", "理由"),
                "action 只能是 APPROVE/REJECT");
        assertThrows(IllegalArgumentException.class, () -> service.decide(reviewId, null, "理由"),
                "action 缺失应拒绝");
        assertThrows(IllegalArgumentException.class, () -> service.decide(0, "APPROVE", "理由"),
                "evaluationId 非法");
        assertThrows(IllegalArgumentException.class, () -> service.decide(99999, "APPROVE", "理由"),
                "评估记录不存在");
        IllegalArgumentException notReview = assertThrows(IllegalArgumentException.class,
                () -> service.decide(passId, "APPROVE", "理由"));
        assertTrue(notReview.getMessage().contains("REVIEW"), notReview.getMessage());
        assertTrue(service.approvedForExecution().isEmpty(), "校验失败不得写入放行队列");
        assertNull(repository.findByEvaluationId(reviewId));
        assertEquals(500, "x".repeat(500).length());
        // 恰好 500 字允许
        ManualReleaseService.DecisionResult ok = service.decide(reviewId, "APPROVE", "y".repeat(500));
        assertEquals("APPROVED", ok.status());
    }

    @Test
    void duplicateDecisionIsConflictUntilReopenable() {
        long evaluationId = seedEvaluation("enc-duplicate", "岗位", "REVIEW", "PASS");
        service.decide(evaluationId, "APPROVE", "第一次批准");

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> service.decide(evaluationId, "APPROVE", "重复批准"));
        assertTrue(conflict.getMessage().contains("不能重复决策"), conflict.getMessage());

        // 已执行也不可重复决策
        String releaseId = repository.findByEvaluationId(evaluationId).releaseId();
        service.markConsumed(releaseId, "attempt-9", "人工放行执行成功");
        assertThrows(IllegalStateException.class,
                () -> service.decide(evaluationId, "REJECT", "已执行还想拒绝"));

        // 拒绝后再次批准 = 重新放行（REJECTED → APPROVED，保留人工理由）
        long rejectedId = seedEvaluation("enc-reopen", "岗位2", "REVIEW", "PASS");
        service.decide(rejectedId, "REJECT", "先拒绝");
        ManualReleaseService.DecisionResult reopened = service.decide(rejectedId, "APPROVE", "再看一次，批准");
        assertEquals("APPROVED", reopened.status());
        ManualReleaseRepository.ManualRelease afterReopen = repository.findByEvaluationId(rejectedId);
        assertEquals("APPROVED", afterReopen.status());
        // 重新放行保留首次人工理由（reopen 只重置执行结论，不覆盖人工记录）
        assertEquals("先拒绝", afterReopen.reason());
        assertNull(afterReopen.error());
        assertEquals(List.of(rejectedId), idsOf(service.queue("APPROVED")));
    }

    @Test
    void failedReleaseCanBeReapprovedAndClearsPreviousFailureFact() {
        long evaluationId = seedEvaluation("enc-failed-retry", "岗位", "REVIEW", "REVIEW");
        service.decide(evaluationId, "APPROVE", "先批准");
        String releaseId = repository.findByEvaluationId(evaluationId).releaseId();
        service.markFailed(releaseId, "放行后 AI 重新评估未通过：REVIEW");

        ManualReleaseRepository.ManualRelease failed = repository.findByEvaluationId(evaluationId);
        assertEquals("FAILED", failed.status());
        assertEquals("放行后 AI 重新评估未通过：REVIEW", failed.error());
        assertEquals("执行失败", service.queue("FAILED").items().get(0).statusLabel());

        ManualReleaseService.DecisionResult retry = service.decide(evaluationId, "APPROVE", "人工复核后重试");
        assertEquals("APPROVED", retry.status());
        ManualReleaseRepository.ManualRelease reopened = repository.findByEvaluationId(evaluationId);
        assertEquals("APPROVED", reopened.status());
        assertNull(reopened.error(), "重新放行必须清空上次失败原因");
        assertNull(reopened.attemptId());
        assertEquals(List.of(evaluationId), idsOf(service.queue("APPROVED")));
    }

    // ==================== 下一轮消费 / 状态流转 ====================

    @Test
    void approvedForExecutionOrdersByRequestedAtAndLimitsBatch() {
        for (int i = 0; i < 12; i++) {
            long evaluationId = seedEvaluation("enc-order-" + i, "岗位" + i, "REVIEW", "PASS");
            insertRelease(evaluationId, "APPROVED", "批量批准", String.format("2026-11-07 10:00:%02d", i), null);
        }
        // 更早 requested_at 的一项应排最前
        long early = seedEvaluation("enc-order-early", "更早批准", "REVIEW", "PASS");
        insertRelease(early, "APPROVED", "更早", "2026-11-07 09:59:59", null);

        List<ManualReleaseRepository.ManualRelease> batch = service.approvedForExecution();
        assertEquals(10, batch.size(), "下一轮最多消费 10 条");
        assertEquals(early, batch.get(0).evaluationId(), "按 requested_at 升序：先批准先消费");
        for (int i = 1; i < batch.size(); i++) {
            assertTrue(batch.get(i - 1).requestedAt().compareTo(batch.get(i).requestedAt()) <= 0,
                    "消费顺序必须按 requested_at 升序");
        }
    }

    @Test
    void findEvaluationReturnsExecutionFactsAndHandlesMissing() {
        long evaluationId = seedEvaluation("enc-data", "Java 开发", "REVIEW", "REVIEW");
        execute(dataSource, "UPDATE job_evaluation SET job_url=?, source_keyword=?, ai_error_code=?, " +
                        "ai_analysis=?, ai_message=? WHERE id=?",
                "https://www.zhipin.com/job_detail/d3b.html", "Java", "AI_TIMEOUT",
                "AI 复核：技术栈接近", "原始输出片段", evaluationId);

        ManualReleaseService.EvaluationData data = service.findEvaluation(evaluationId);
        assertNotNull(data);
        assertEquals("enc-data", data.encryptId());
        assertEquals("Java 开发", data.jobName());
        assertEquals("https://www.zhipin.com/job_detail/d3b.html", data.jobUrl());
        assertEquals("Java", data.sourceKeyword());
        assertEquals("REVIEW", data.ruleDecision());
        assertEquals("REVIEW", data.aiDecision());
        assertEquals("AI_TIMEOUT", data.aiErrorCode());
        assertEquals("AI 复核：技术栈接近", data.aiAnalysis());
        assertNull(service.findEvaluation(99999), "不存在的评估应返回 null（执行器据此 markFailed）");
    }

    @Test
    void markConsumedAndMarkFailedPersistExecutionFacts() {
        long evaluationId = seedEvaluation("enc-facts", "岗位", "REVIEW", "PASS");
        String releaseId = service.decide(evaluationId, "APPROVE", "批准").releaseId();

        service.markConsumed(releaseId, "attempt-d3b-1", "人工放行执行成功");
        ManualReleaseRepository.ManualRelease consumed = repository.findByReleaseId(releaseId);
        assertEquals("CONSUMED", consumed.status());
        assertEquals("attempt-d3b-1", consumed.attemptId());
        assertNotNull(consumed.consumedAt(), "消费时间必须落库");
        assertNotNull(consumed.decidedAt(), "决定时间必须落库");
        assertEquals(0, service.approvedForExecution().size(), "已消费不再进入下一轮");

        long failedId = seedEvaluation("enc-facts-fail", "岗位2", "REVIEW", "PASS");
        String failedRelease = service.decide(failedId, "APPROVE", "批准").releaseId();
        service.markFailed(failedRelease, "缺少 encryptId");
        ManualReleaseRepository.ManualRelease failed = repository.findByReleaseId(failedRelease);
        assertEquals("FAILED", failed.status());
        assertEquals("缺少 encryptId", failed.error());
        assertNull(failed.attemptId());
    }

    // ==================== 工具 ====================

    private long seedEvaluation(String encryptId, String jobName, String ruleDecision, String aiDecision) {
        return seedEvaluation(encryptId, jobName, ruleDecision, aiDecision, NOW);
    }

    private long seedEvaluation(String encryptId, String jobName, String ruleDecision, String aiDecision,
                                String createdAt) {
        sequence++;
        execute(dataSource, "INSERT INTO job_evaluation(run_id, encrypt_id, encrypt_user_id, job_name, " +
                        "company_name, job_url, source_keyword, snapshot_hash, rule_decision, rule_reason_code, " +
                        "rule_evidence, ai_decision, ai_error_code, ai_analysis, ai_message, created_at) " +
                        "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "run-d3b", encryptId, "user-" + encryptId, jobName, "测试公司",
                "https://www.zhipin.com/job_detail/" + encryptId + ".html", "Java", "snapshot-" + sequence,
                ruleDecision, "REVIEW".equals(ruleDecision) ? "SALARY_REVIEW" : null,
                "测试证据", aiDecision, "ERROR".equals(aiDecision) ? "AI_TIMEOUT" : null,
                "REMARK".equals(jobName) ? null : ("AI 分析-" + jobName), "AI 话术-" + sequence, createdAt);
        return scalar(dataSource, "SELECT MAX(id) FROM job_evaluation");
    }

    private String insertRelease(long evaluationId, String status, String reason,
                                 String requestedAt, String error) {
        String releaseId = "rel-" + UUID.randomUUID();
        repository.insert(new ManualReleaseRepository.ManualRelease(
                releaseId, evaluationId, status, reason,
                requestedAt, null, null, null, error, requestedAt, requestedAt));
        return releaseId;
    }

    private List<Long> idsOf(ManualReleaseService.Queue queue) {
        return queue.items().stream().map(ManualReleaseService.QueueItem::evaluationId).toList();
    }
}
