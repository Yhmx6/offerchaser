package com.offerchaser.verification;

import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 数据/查询回归：{@link BossQueryRepository} 的真实 SQLite 集成验收。
 * <p>
 * 锁定契约第 2.2 节的列表字段、分页外壳（page/size/total）、排序、筛选与详情结构；
 * 同时验证 V4 后 delivery_attempt 的人工核对字段可查、pendingResolutions 统计口径。
 */
class B1QueryIntegrationTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private BossQueryRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        repository = new BossQueryRepository(dataSource);
        seedRuns();
        seedEvaluations();
        seedAttempts();
        seedEvents();
    }

    @Test
    void runsPaginationEnvelopeSortingAndFilters() {
        PageResult<Map<String, Object>> firstPage = repository.listRuns(null, null, null, 1, 2);
        assertEquals(1, firstPage.page(), "page 从 1 开始");
        assertEquals(2, firstPage.size());
        assertEquals(3L, firstPage.total());
        assertEquals(2, firstPage.items().size());
        // started_at DESC：r3(12:00) 在 r2(11:00) 前
        assertEquals("run-3", firstPage.items().get(0).get("runId"));
        assertEquals("run-2", firstPage.items().get(1).get("runId"));
        Map<String, Object> run = firstPage.items().get(0);
        for (String field : List.of("runId", "platform", "state", "stopReason", "error",
                "startedAt", "endedAt", "updatedAt")) {
            assertTrue(run.containsKey(field), "任务记录缺少契约字段：" + field);
        }

        PageResult<Map<String, Object>> secondPage = repository.listRuns(null, null, null, 2, 2);
        assertEquals(1, secondPage.items().size());
        assertEquals("run-1", secondPage.items().get(0).get("runId"));

        assertEquals(2L, repository.listRuns("COMPLETED", null, null, 1, 20).total(),
                "status 筛选应只返回 COMPLETED");
        assertEquals(2L, repository.listRuns(null, "2026-11-04 11:00:00", null, 1, 20).total(),
                "from 应按 started_at >= 过滤");
        assertEquals(1L, repository.listRuns(null, null, "2026-11-04 10:30:00", 1, 20).total(),
                "to 应按 started_at <= 过滤");
        assertEquals(3L, repository.listRuns(null, "not-a-time", "also-bad", 1, 20).total(),
                "非法时间格式应被忽略而不是报错");

        PageResult<Map<String, Object>> clamped = repository.listRuns(null, null, null, 0, 500);
        assertEquals(1, clamped.page(), "page<1 应规范为 1");
        assertEquals(100, clamped.size(), "size 上限必须为 100");
    }

    @Test
    void evaluationsFiltersAndFields() {
        assertEquals(2L, repository.listEvaluations(null, null, null, null, null, null,
                null, null, 1, 20).total());
        assertEquals(1L, repository.listEvaluations("run-1", null, null, null, null, null,
                null, null, 1, 20).total());
        assertEquals(1L, repository.listEvaluations(null, "REJECT", null, null, null, null,
                null, null, 1, 20).total());
        assertEquals(1L, repository.listEvaluations(null, null, "ERROR", null, null, null,
                null, null, 1, 20).total());
        assertEquals(1L, repository.listEvaluations(null, null, null, "MISSING_JOB_CONTENT", null,
                null, null, null, 1, 20).total());
        assertEquals(1L, repository.listEvaluations(null, null, null, null, "ENC-A1", null,
                null, null, 1, 20).total());
        assertEquals(1L, repository.listEvaluations(null, null, null, null, null, "Java",
                null, null, 1, 20).total(), "keyword 应匹配岗位名");
        assertEquals(1L, repository.listEvaluations(null, null, null, null, null, "销售",
                null, null, 1, 20).total(), "keyword 应匹配公司名");
        assertEquals(1L, repository.listEvaluations(null, null, null, null, null, null,
                "2026-11-04 10:30:00", "2026-11-04 12:00:00", 1, 20).total(),
                "时间范围应按 created_at 过滤");

        Map<String, Object> item = repository.listEvaluations("run-1", null, null, null, null, null,
                null, null, 1, 20).items().get(0);
        for (String field : List.of("id", "runId", "encryptId", "encryptUserId", "jobName", "companyName",
                "jobUrl", "sourceKeyword", "sourceCity", "snapshotHash", "ruleVersion", "ruleDecision",
                "ruleReasonCode", "ruleEvidence", "aiDecision", "aiErrorCode", "aiAnalysis", "aiMessage",
                "aiModel", "promptTokens", "completionTokens", "durationMs", "createdAt")) {
            assertTrue(item.containsKey(field), "评估记录缺少契约字段：" + field);
        }
        assertEquals("PASS", item.get("ruleDecision"));
        assertEquals("MATCH", item.get("aiDecision"));
    }

    @Test
    void attemptsFiltersAndV4ManualResolutionFields() {
        assertEquals(5L, repository.listAttempts(null, null, null, null, null, null, null,
                1, 20).total());
        assertEquals(2L, repository.listAttempts(null, null, "UNKNOWN", null, null, null,
                null, 1, 20).total(), "textStatus 筛选");
        assertEquals(1L, repository.listAttempts(null, "FOLLOWUP", null, null, null, null,
                null, 1, 20).total(), "kind 筛选");
        assertEquals(2L, repository.listAttempts("run-1", null, null, null, null, null,
                null, 1, 20).total(), "runId 筛选");
        assertEquals(1L, repository.listAttempts(null, null, null, null, "ENC-A1", null,
                null, 1, 20).total(), "encryptId 筛选");

        Map<String, Object> attempt = repository.listAttempts("run-1", null, "CONFIRMED", null,
                null, null, null, 1, 20).items().get(0);
        for (String field : List.of("attemptId", "runId", "kind", "encryptId", "encryptUserId",
                "jobName", "companyName", "jobUrl", "textStatus", "imageStatus", "textEvidence",
                "imageEvidence", "errorCode", "errorDetail", "startedAt", "finishedAt", "version",
                "manualResolution", "resolvedAt", "resolveReason")) {
            assertTrue(attempt.containsKey(field), "投递明细缺少契约字段：" + field);
        }
        assertEquals("SENT", attempt.get("manualResolution"), "V4 人工核对字段应可查询");
        assertEquals(1, attempt.get("version"));
    }

    @Test
    void attemptDetailReturnsAttemptEventsAndEvaluation() {
        Map<String, Object> detail = repository.getAttemptDetail("ATT-A1");

        assertNotNull(detail);
        assertTrue(detail.containsKey("attempt"));
        assertTrue(detail.containsKey("events"));
        assertTrue(detail.containsKey("evaluation"));
        @SuppressWarnings("unchecked")
        Map<String, Object> attempt = (Map<String, Object>) detail.get("attempt");
        assertEquals("ATT-A1", attempt.get("attemptId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) detail.get("events");
        assertEquals(2, events.size(), "详情应返回该 attempt 的全部事件（按时间升序）");
        assertEquals("ATTEMPT_CREATED", events.get(0).get("eventType"));
        assertEquals("TEXT_UNKNOWN", events.get(1).get("eventType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) detail.get("evaluation");
        assertNotNull(evaluation, "详情应按 encryptId+runId 关联最近一条评估");
        assertEquals("ENC-A1", evaluation.get("encryptId"));

        assertNull(repository.getAttemptDetail("NOT-EXISTS"), "不存在时应返回 null 供 controller 映射 404");
    }

    @Test
    void pendingResolutionsCountsFirstContactUnknownOrPendingWithoutManualResolution() {
        // 夹具：ATT-A1(UNKNOWN,FIRST_CONTACT,manual=null) 计入；
        // ATT-A2(FAILED,FOLLOWUP) 不计；ATT-A3(CONFIRMED,FIRST_CONTACT,SENT) 不计；
        // ATT-A4(UNKNOWN,FIRST_CONTACT,STILL_UNKNOWN) 不计；ATT-A5(PENDING,FIRST_CONTACT,manual=null) 计入。
        assertEquals(2, repository.countPendingResolutions(),
                "待核对 = 首次沟通中 UNKNOWN/PENDING 且 manual_resolution 为空的尝试数");
    }

    // ==================== 脱敏夹具 ====================

    private void seedRuns() {
        execute(dataSource, "INSERT INTO task_run(run_id, platform, state, stop_reason, error, " +
                "started_at, ended_at, updated_at) VALUES" +
                "('run-1', 'boss', 'COMPLETED', NULL, NULL, '2026-11-04 10:00:00', " +
                "'2026-11-04 10:30:00', '2026-11-04 10:30:00')," +
                "('run-2', 'boss', 'FAILED', '人工停止', '模拟错误', '2026-11-04 11:00:00', " +
                "'2026-11-04 11:10:00', '2026-11-04 11:10:00')," +
                "('run-3', 'boss', 'COMPLETED', NULL, NULL, '2026-11-04 12:00:00', " +
                "'2026-11-04 12:20:00', '2026-11-04 12:20:00')");
    }

    private void seedEvaluations() {
        execute(dataSource, "INSERT INTO job_evaluation(run_id, encrypt_id, encrypt_user_id, " +
                "job_name, company_name, job_url, source_keyword, rule_decision, rule_reason_code, " +
                "ai_decision, ai_error_code, ai_analysis, ai_message, ai_model, prompt_tokens, " +
                "completion_tokens, duration_ms, created_at) VALUES" +
                "('run-1', 'ENC-A1', 'USER-A1', 'Java 开发', '示例科技', 'https://example.invalid/job/a1', " +
                "'Java', 'PASS', 'PASS', 'MATCH', NULL, '匹配', '您好', 'test-model', 100, 20, 50, " +
                "'2026-11-04 10:00:00')," +
                "('run-2', 'ENC-A2', 'USER-A2', '销售专员', '示例销售', 'https://example.invalid/job/a2', " +
                "'销售', 'REJECT', 'MISSING_JOB_CONTENT', 'ERROR', 'AI_OUTPUT_INVALID', '分析', NULL, " +
                "'test-model', 90, 0, 40, '2026-11-04 11:00:00')");
    }

    private void seedAttempts() {
        execute(dataSource, "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, " +
                "encrypt_id, encrypt_user_id, job_name, company_name, job_url, text_status, image_status, " +
                "error_code, version, manual_resolution, resolved_at, resolve_reason, " +
                "started_at, finished_at, created_at) VALUES" +
                "('ATT-A1', 'run-1', 'local', 'FIRST_CONTACT', 'ENC-A1', 'USER-A1', 'Java 开发', " +
                "'示例科技', 'https://example.invalid/job/a1', 'UNKNOWN', 'NOT_ATTEMPTED', " +
                "'TEXT_SEND_UNCONFIRMED', 0, NULL, NULL, NULL, '2026-11-04 10:01:00', NULL, " +
                "'2026-11-04 10:01:00')," +
                "('ATT-A2', 'run-2', 'local', 'FOLLOWUP', 'ENC-A2', 'USER-A2', '销售专员', " +
                "'示例销售', 'https://example.invalid/job/a2', 'FAILED', 'NOT_ATTEMPTED', " +
                "'TEXT_SEND_FAILED', 0, NULL, NULL, NULL, '2026-11-04 11:01:00', NULL, " +
                "'2026-11-04 11:01:00')," +
                "('ATT-A3', 'run-1', 'local', 'FIRST_CONTACT', 'ENC-A3', 'USER-A3', '后端开发', " +
                "'示例科技', 'https://example.invalid/job/a3', 'CONFIRMED', 'SKIPPED', NULL, " +
                "1, 'SENT', '2026-11-04 10:40:00', '人工确认', '2026-11-04 10:05:00', " +
                "'2026-11-04 10:06:00', '2026-11-04 10:05:00')," +
                "('ATT-A4', 'run-3', 'local', 'FIRST_CONTACT', 'ENC-A4', 'USER-A4', '测试开发', " +
                "'示例科技', 'https://example.invalid/job/a4', 'UNKNOWN', 'UNKNOWN', " +
                "'TEXT_SEND_UNCONFIRMED', 1, 'STILL_UNKNOWN', '2026-11-04 12:30:00', '仍无法确认', " +
                "'2026-11-04 12:01:00', NULL, '2026-11-04 12:01:00')," +
                "('ATT-A5', 'run-3', 'local', 'FIRST_CONTACT', 'ENC-A5', 'USER-A5', '产品经理', " +
                "'示例科技', 'https://example.invalid/job/a5', 'PENDING', 'NOT_ATTEMPTED', NULL, " +
                "0, NULL, NULL, NULL, '2026-11-04 12:02:00', NULL, '2026-11-04 12:02:00')");
    }

    private void seedEvents() {
        execute(dataSource, "INSERT INTO delivery_event(attempt_id, run_id, event_type, " +
                "evidence_level, detail, occurred_at) VALUES" +
                "('ATT-A1', 'run-1', 'ATTEMPT_CREATED', 'LOCAL', '创建投递尝试', '2026-11-04 10:01:00')," +
                "('ATT-A1', 'run-1', 'TEXT_UNKNOWN', 'DOM', '确认超时', '2026-11-04 10:02:00')");
    }

    @SuppressWarnings("unused")
    private static boolean unusedReference() {
        return false;
    }
}
