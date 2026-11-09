package com.offerchaser.application.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;

import static com.offerchaser.application.persistence.PersistenceTestSupport.scalar;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 岗位评估记录测试：落库、可空字段与今日汇总口径。
 */
class EvaluationRepositoryTest {

    @TempDir
    Path directory;

    @Test
    void recordsEntriesAndSummarizesToday() {
        SQLiteDataSource dataSource = PersistenceTestSupport.migratedDataSource(directory);
        EvaluationRepository repository = new EvaluationRepository(dataSource, PersistenceTestSupport.TIMEZONE);

        repository.record(EvaluationEntry.builder()
                .runId("run-1")
                .encryptId("job-1")
                .jobName("Java开发")
                .snapshotHash("hash-1")
                .ruleVersion("job-rules-v2")
                .ruleDecision("PASS")
                .ruleReasonCode("PASS")
                .build());
        repository.record(EvaluationEntry.builder()
                .runId("run-1")
                .encryptId("job-2")
                .jobName("前端开发")
                .ruleVersion("job-rules-v2")
                .ruleDecision("REJECT")
                .ruleReasonCode("JOB_BLACKLIST")
                .ruleEvidence("外包")
                .build());
        repository.record(EvaluationEntry.builder()
                .runId("run-1")
                .encryptId("job-3")
                .jobName("后端开发")
                .ruleVersion("job-rules-v3@v3")
                .ruleDecision("PASS")
                .ruleReasonCode("PASS")
                .ruleConfigVersion(3)
                .rulesHash("rules-hash-3")
                .aiDecision("MATCH")
                .aiMessage("您好，我对这个岗位很感兴趣")
                .aiAnalysis("匹配")
                .promptVersion("prompt-v1")
                .aiModel("test-model")
                .promptTokens(100)
                .completionTokens(20)
                .durationMs(1500L)
                .build());
        repository.record(EvaluationEntry.builder()
                .runId("run-1")
                .encryptId("job-4")
                .ruleDecision("PASS")
                .aiDecision("ERROR")
                .aiErrorCode("AI_OUTPUT_INVALID")
                .build());

        Map<String, Object> summary = repository.summaryToday();
        assertEquals(4, summary.get("total"));
        assertEquals(3, summary.get("rulePass"));
        assertEquals(1, summary.get("ruleReject"));
        assertEquals(0, summary.get("ruleReview"));
        assertEquals(1, summary.get("aiMatch"));
        assertEquals(1, summary.get("aiError"));
        assertNotNull(summary.get("date"));

        assertEquals(4, scalar(dataSource, "SELECT COUNT(*) FROM job_evaluation"));
        assertEquals(100, scalar(dataSource,
                "SELECT prompt_tokens FROM job_evaluation WHERE encrypt_id = 'job-3'"));
        assertEquals(20, scalar(dataSource,
                "SELECT completion_tokens FROM job_evaluation WHERE encrypt_id = 'job-3'"));
        assertEquals(1500, scalar(dataSource,
                "SELECT duration_ms FROM job_evaluation WHERE encrypt_id = 'job-3'"));
        // V5 规则版本/哈希字段落库；旧数据/不传时为 null
        assertEquals(3, scalar(dataSource,
                "SELECT rule_config_version FROM job_evaluation WHERE encrypt_id = 'job-3'"));
        assertEquals("rules-hash-3", scalarString(dataSource,
                "SELECT rules_hash FROM job_evaluation WHERE encrypt_id = 'job-3'"));
        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM job_evaluation WHERE encrypt_id = 'job-1' " +
                        "AND rule_config_version IS NOT NULL"));
        assertNull(scalarString(dataSource,
                "SELECT rules_hash FROM job_evaluation WHERE encrypt_id = 'job-1'"));
        assertEquals("test-model", scalarString(dataSource,
                "SELECT ai_model FROM job_evaluation WHERE encrypt_id = 'job-3'"));
        assertEquals("外包", scalarString(dataSource,
                "SELECT rule_evidence FROM job_evaluation WHERE encrypt_id = 'job-2'"));
        assertNotNull(scalarString(dataSource,
                "SELECT created_at FROM job_evaluation WHERE encrypt_id = 'job-1'"));
        // 可空字段落成 NULL 而不是空串
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM job_evaluation WHERE encrypt_id = 'job-4' AND ai_message IS NULL"));
    }

    @Test
    void skipsEntryWithoutRunId() {
        SQLiteDataSource dataSource = PersistenceTestSupport.migratedDataSource(directory);
        EvaluationRepository repository = new EvaluationRepository(dataSource, PersistenceTestSupport.TIMEZONE);

        repository.record(EvaluationEntry.builder().encryptId("job-1").ruleDecision("PASS").build());
        repository.record(null);

        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM job_evaluation"));
        assertEquals(0, repository.summaryToday().get("total"));
    }
}
