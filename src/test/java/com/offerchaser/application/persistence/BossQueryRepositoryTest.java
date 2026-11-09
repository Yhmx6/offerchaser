package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.offerchaser.application.persistence.PersistenceTestSupport.execute;
import static com.offerchaser.application.persistence.PersistenceTestSupport.executeScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 只读查询仓库测试：SQL 分页、筛选、排序、字段名、详情组装与待核对计数。
 */
class BossQueryRepositoryTest {

    private static final Set<String> RUN_KEYS = Set.of(
            "runId", "platform", "state", "stopReason", "error", "startedAt", "endedAt", "updatedAt",
            "templateId", "templateName", "templateDirection");
    private static final Set<String> EVALUATION_KEYS = Set.of(
            "id", "runId", "encryptId", "encryptUserId", "jobName", "companyName", "jobUrl",
            "sourceKeyword", "sourceCity", "snapshotHash", "ruleVersion", "ruleDecision",
            "ruleReasonCode", "ruleEvidence", "aiDecision", "aiErrorCode", "aiAnalysis",
            "aiMessage", "aiModel", "promptTokens", "completionTokens", "durationMs",
            "ruleConfigVersion", "rulesHash", "createdAt");
    private static final Set<String> JOB_ROW_KEYS = Set.of(
            "encryptId", "encryptUserId", "jobName", "salary", "location", "experience", "degree",
            "jobDescription", "companyName", "industry", "financingStage", "companyScale",
            "companyAddress", "recruitmentStatus", "hrName", "hrPosition", "hrActiveStatus", "jobUrl");
    private static final Set<String> ATTEMPT_KEYS = Set.of(
            "attemptId", "runId", "kind", "encryptId", "encryptUserId", "jobName", "companyName",
            "jobUrl", "textStatus", "imageStatus", "textEvidence", "imageEvidence", "errorCode",
            "errorDetail", "startedAt", "finishedAt", "version", "manualResolution", "resolvedAt",
            "resolveReason", "contactKey", "parentAttemptId");
    private static final Set<String> EVENT_KEYS = Set.of("eventType", "evidenceLevel", "detail", "occurredAt");

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource;
    private BossQueryRepository repository;
    private DeliveryLedgerService ledger;

    @BeforeEach
    void setUp() {
        dataSource = PersistenceTestSupport.migratedDataSource(directory);
        repository = new BossQueryRepository(dataSource);
        ledger = new DeliveryLedgerService(dataSource, PersistenceTestSupport.ACCOUNT,
                PersistenceTestSupport.TIMEZONE);
        seedRuns();
        seedEvaluations();
        seedBossData();
    }

    // ==================== 任务记录 ====================

    @Test
    void listRunsPaginatesAndSortsByStartedAtDesc() {
        PageResult<Map<String, Object>> firstPage = repository.listRuns(null, null, null, 1, 2);
        assertEquals(3, firstPage.total());
        assertEquals(1, firstPage.page());
        assertEquals(2, firstPage.size());
        assertEquals(2, firstPage.items().size());
        assertEquals("run-c", firstPage.items().get(0).get("runId"), "按 started_at DESC 排序");
        assertEquals("run-b", firstPage.items().get(1).get("runId"));
        assertEquals(RUN_KEYS, firstPage.items().get(0).keySet(), "字段名必须与契约 2.2 一致");

        PageResult<Map<String, Object>> secondPage = repository.listRuns(null, null, null, 2, 2);
        assertEquals(1, secondPage.items().size());
        assertEquals("run-a", secondPage.items().get(0).get("runId"));

        // page/size 兜底：page<1→1、size<=0→20、size>100→100
        assertEquals(1, repository.listRuns(null, null, null, 0, 0).page());
        assertEquals(20, repository.listRuns(null, null, null, 0, 0).size());
        assertEquals(100, repository.listRuns(null, null, null, 1, 500).size());
        assertEquals(3, repository.listRuns(null, null, null, 0, 0).items().size());
    }

    @Test
    void listRunsFiltersByStatusAndTimeRange() {
        PageResult<Map<String, Object>> failed = repository.listRuns("FAILED", null, null, 1, 20);
        assertEquals(1, failed.total());
        assertEquals("run-b", failed.items().get(0).get("runId"));

        // 空白状态不过滤
        assertEquals(3, repository.listRuns("", null, null, 1, 20).total());
        assertEquals(3, repository.listRuns(null, null, null, 1, 20).total());

        // from/to 过滤 started_at
        assertEquals(2, repository.listRuns(null, "2026-11-02 00:00:00", null, 1, 20).total());
        assertEquals(1, repository.listRuns(null, null, "2026-11-01 23:59:59", 1, 20).total());

        // 非法时间格式忽略该条件（controller 会原样透传）
        assertEquals(3, repository.listRuns(null, "not-a-time", "2026/11/02", 1, 20).total());
    }

    // ==================== 评估记录 ====================

    @Test
    void listEvaluationsPaginatesSortsAndMapsAllContractFields() {
        PageResult<Map<String, Object>> page = repository.listEvaluations(
                null, null, null, null, null, null, null, null, 1, 2);
        assertEquals(3, page.total());
        assertEquals(2, page.items().size());
        assertEquals(3L, page.items().get(0).get("id"), "按 created_at DESC, id DESC 排序");
        assertEquals(EVALUATION_KEYS, page.items().get(0).keySet(), "字段名必须与契约 2.2 一致");
        // 可空字段为 null
        assertNull(page.items().get(0).get("promptTokens"));
        PageResult<Map<String, Object>> secondPage = repository.listEvaluations(
                null, null, null, null, null, null, null, null, 2, 2);
        assertEquals(1L, secondPage.items().get(0).get("id"));
        assertEquals(1500L, secondPage.items().get(0).get("durationMs"));
    }

    @Test
    void listEvaluationsAppliesAllFilters() {
        assertEquals(2, repository.listEvaluations("run-b", null, null, null, null, null,
                null, null, 1, 20).total());
        assertEquals(1, repository.listEvaluations(null, "REJECT", null, null, null, null,
                null, null, 1, 20).total());
        assertEquals(1, repository.listEvaluations(null, null, "ERROR", null, null, null,
                null, null, 1, 20).total());
        assertEquals(2, repository.listEvaluations(null, null, null, "PASS", null, null,
                null, null, 1, 20).total());
        assertEquals(2, repository.listEvaluations(null, null, null, null, "encrypt-1", null,
                null, null, 1, 20).total());
        // keyword 同时匹配岗位名/公司名
        assertEquals(2, repository.listEvaluations(null, null, null, null, null, "Java",
                null, null, 1, 20).total());
        assertEquals(1, repository.listEvaluations(null, null, null, null, null, "乙公司",
                null, null, 1, 20).total());
        // 时间范围按 created_at
        assertEquals(2, repository.listEvaluations(null, null, null, null, null, null,
                "2026-11-02 00:00:00", null, 1, 20).total());
        assertEquals(1, repository.listEvaluations(null, null, null, null, null, null,
                null, "2026-11-01 23:59:59", 1, 20).total());
        // 无匹配
        assertEquals(0, repository.listEvaluations("run-zzz", null, null, null, null, null,
                null, null, 1, 20).items().size());
    }

    // ==================== 投递明细 ====================

    @Test
    void listAttemptsPaginatesFiltersAndMapsV4Fields() {
        AttemptSeed seed = seedAttempts();

        PageResult<Map<String, Object>> page = repository.listAttempts(
                null, null, null, null, null, null, null, 1, 2);
        assertEquals(3, page.total());
        assertEquals(2, page.items().size());
        Map<String, Object> newest = page.items().get(0);
        assertEquals(seed.confirmedAttemptId(), newest.get("attemptId"), "按 started_at DESC, id DESC 排序");
        assertEquals(0, newest.get("version"));
        assertNull(newest.get("manualResolution"));
        assertEquals(ATTEMPT_KEYS, newest.keySet(), "字段名必须与契约 2.2 一致");

        assertEquals(2, repository.listAttempts("run-b", null, null, null, null, null,
                null, 1, 20).total());
        assertEquals(1, repository.listAttempts(null, "FOLLOWUP", null, null, null, null,
                null, 1, 20).total());
        assertEquals(1, repository.listAttempts(null, null, "UNKNOWN", null, null, null,
                null, 1, 20).total());
        assertEquals(1, repository.listAttempts(null, null, null, "CONFIRMED", null, null,
                null, 1, 20).total());
        assertEquals(2, repository.listAttempts(null, null, null, null, "encrypt-1", null,
                null, 1, 20).total());
        assertEquals(2, repository.listAttempts(null, null, null, null, null,
                "2026-11-02 00:00:00", null, 1, 20).total());
    }

    @Test
    void getAttemptDetailReturnsAttemptEventsAndEvaluation() {
        AttemptSeed seed = seedAttempts();
        ledger.appendEvent(seed.unknownRowId(), "run-a", "TEXT_SEND_STARTED", "DOM", "点击发送");
        ledger.appendEvent(seed.unknownRowId(), "run-a", "TEXT_UNKNOWN", "TIMEOUT", "超时");

        Map<String, Object> detail = repository.getAttemptDetail(seed.unknownAttemptId());
        assertNotNull(detail);
        assertEquals(Set.of("attempt", "events", "evaluation"), detail.keySet());

        @SuppressWarnings("unchecked")
        Map<String, Object> attempt = (Map<String, Object>) detail.get("attempt");
        assertEquals("run-a", attempt.get("runId"));
        assertEquals("UNKNOWN", attempt.get("textStatus"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) detail.get("events");
        assertEquals(3, events.size(), "含 beginAttempt 的 ATTEMPT_CREATED + 2 条追加事件");
        assertEquals(EVENT_KEYS, events.get(0).keySet(), "事件字段名必须与契约 2.2 一致");
        assertEquals("ATTEMPT_CREATED", events.get(0).get("eventType"));
        // occurred_at 升序
        assertTrue(String.valueOf(events.get(0).get("occurredAt"))
                .compareTo(String.valueOf(events.get(2).get("occurredAt"))) <= 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) detail.get("evaluation");
        assertNotNull(evaluation);
        assertEquals(1L, evaluation.get("id"), "优先按 encrypt_id + run_id 匹配评估");
    }

    @Test
    void getAttemptDetailFallsBackToLatestEvaluationByEncryptId() {
        // 这个 attempt 的 run_id 没有任何评估，只能退化为按 encrypt_id 找最近一条
        execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "encrypt_id, started_at, created_at) VALUES('fallback-attempt', 'run-unknown', 'local', " +
                        "'FIRST_CONTACT', 'UNKNOWN', 'NOT_ATTEMPTED', 'encrypt-1', " +
                        "'2026-11-04 10:00:00', '2026-11-04 10:00:00')");

        Map<String, Object> detail = repository.getAttemptDetail("fallback-attempt");
        assertNotNull(detail);
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) detail.get("evaluation");
        assertNotNull(evaluation);
        assertEquals(3L, evaluation.get("id"), "退化后应取 encrypt-1 最近一条评估");

        execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "encrypt_id, started_at, created_at) VALUES('no-eval-attempt', 'run-none', 'local', " +
                        "'FIRST_CONTACT', 'FAILED', 'NOT_ATTEMPTED', 'encrypt-9', " +
                        "'2026-11-04 10:00:00', '2026-11-04 10:00:00')");
        Map<String, Object> noEvalDetail = repository.getAttemptDetail("no-eval-attempt");
        assertNotNull(noEvalDetail);
        assertNull(noEvalDetail.get("evaluation"));
    }

    @Test
    void getAttemptDetailReturnsNullForMissingAttempt() {
        assertNull(repository.getAttemptDetail("not-exists"));
        assertNull(repository.getAttemptDetail(null));
    }

    @Test
    void countPendingResolutionsOnlyCountsUnresolvedFirstContactUnknownOrPending() {
        AttemptSeed seed = seedAttempts();
        assertEquals(1, repository.countPendingResolutions(), "seed 里只有一条 UNKNOWN 首次沟通待核对");

        // PENDING 也计入
        execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "started_at, created_at) VALUES('pending-1', 'run-a', 'local', 'FIRST_CONTACT', " +
                        "'PENDING', 'NOT_ATTEMPTED', '2026-11-04 11:00:00', '2026-11-04 11:00:00')");
        assertEquals(2, repository.countPendingResolutions());

        // 已人工核对的不再计入
        execute(dataSource,
                "UPDATE delivery_attempt SET manual_resolution = 'STILL_UNKNOWN' WHERE attempt_id = ?",
                seed.unknownAttemptId());
        assertEquals(1, repository.countPendingResolutions());

        // 跟进类型 / 已确认 / FAILED 都不计入
        execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "started_at, created_at) VALUES('followup-unknown', 'run-a', 'local', 'FOLLOWUP', " +
                        "'UNKNOWN', 'NOT_ATTEMPTED', '2026-11-04 11:00:00', '2026-11-04 11:00:00')");
        assertEquals(1, repository.countPendingResolutions());
        execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "started_at, created_at) VALUES('failed-1', 'run-a', 'local', 'FIRST_CONTACT', " +
                        "'FAILED', 'NOT_ATTEMPTED', '2026-11-04 11:00:00', '2026-11-04 11:00:00')");
        assertEquals(1, repository.countPendingResolutions());
    }

    // ==================== B2 评估筛选 / 重评数据源 ====================

    @Test
    void listEvaluationsLatestOnlyKeepsNewestPerEncryptId() {
        PageResult<Map<String, Object>> latest = repository.listEvaluations(
                null, null, null, null, null, null, null, null, 1, 20, true, null);
        assertEquals(2, latest.total(), "每个 encrypt_id 只保留最新一条");
        assertEquals(List.of(3L, 2L),
                latest.items().stream().map(item -> item.get("id")).toList());
        assertEquals(EVALUATION_KEYS, latest.items().get(0).keySet());

        // 旧 10 参数签名保留：仍返回全部
        assertEquals(3, repository.listEvaluations(
                null, null, null, null, null, null, null, null, 1, 20).total());
    }

    @Test
    void listEvaluationsFiltersByRuleConfigVersion() {
        assertEquals(2, repository.listEvaluations(
                null, null, null, null, null, null, null, null, 1, 20, false, 2).total());
        assertEquals(1, repository.listEvaluations(
                null, null, null, null, null, null, null, null, 1, 20, false, 1).total());
        assertEquals(2, repository.listEvaluations(
                null, null, null, null, null, null, null, null, 1, 20, true, 2).total());
        assertEquals(0, repository.listEvaluations(
                null, null, null, null, null, null, null, null, 1, 20, false, 9).total());
        // 另一种参数顺序的重载（latestOnly/ruleConfigVersion 在 page/size 前）语义一致
        assertEquals(2, repository.listEvaluations(
                null, null, null, null, null, null, null, null, false, 2, 1, 20).total());
        // 规则版本/哈希字段可读
        Map<String, Object> first = repository.listEvaluations(
                null, null, null, null, null, null, null, null, 1, 20, false, 1).items().get(0);
        assertEquals(1, first.get("ruleConfigVersion"));
        assertEquals("rules-hash-1", first.get("rulesHash"));
    }

    @Test
    void findJobRowsForRecheckByEncryptIdsReturnsLatestRowAndContractFields() {
        List<Map<String, Object>> rows = repository.findJobRowsForRecheck(
                List.of("encrypt-1", "encrypt-2", "missing"), null, 10);

        assertEquals(2, rows.size(), "不存在的 encryptId 忽略");
        Map<String, Object> first = rows.get(0);
        assertEquals(JOB_ROW_KEYS, first.keySet(), "重评数据 key 必须与契约一致");
        assertEquals("encrypt-1", first.get("encryptId"));
        assertEquals("Java开发(急招)", first.get("jobName"), "同一 encrypt_id 取 boss_data 最新一行");
        assertEquals("https://example.com/1-new", first.get("jobUrl"));
        assertEquals("今日活跃", first.get("hrActiveStatus"));
        assertEquals("encrypt-2", rows.get(1).get("encryptId"));

        assertEquals(1, repository.findJobRowsForRecheck(
                List.of("encrypt-1", "encrypt-2"), null, 1).size(), "limit 生效");
        assertTrue(repository.findJobRowsForRecheck(List.of(), null, 10).size() == 3,
                "encryptIds 为空且 runId 为空时取最近 boss_data");
    }

    @Test
    void findJobRowsForRecheckByRunIdUsesEvaluationsOfThatRun() {
        List<Map<String, Object>> runA = repository.findJobRowsForRecheck(List.of(), "run-a", 10);
        assertEquals(1, runA.size(), "run-a 只评估过 encrypt-1");
        assertEquals("encrypt-1", runA.get(0).get("encryptId"));

        List<Map<String, Object>> runB = repository.findJobRowsForRecheck(null, "run-b", 10);
        assertEquals(2, runB.size(), "run-b 评估过 encrypt-1/encrypt-2");
        assertEquals(List.of("encrypt-1", "encrypt-2"),
                runB.stream().map(row -> row.get("encryptId")).toList());

        List<Map<String, Object>> unknownRun = repository.findJobRowsForRecheck(null, "run-zzz", 10);
        assertTrue(unknownRun.isEmpty());
    }

    @Test
    void findJobRowsForRecheckHandlesLimitFallbacks() {
        // limit<=0 → 默认 50（这里数据只有 3 条）
        assertEquals(3, repository.findJobRowsForRecheck(null, null, 0).size());
        // 最近 2 条按 id DESC
        List<Map<String, Object>> recent = repository.findJobRowsForRecheck(null, "  ", 2);
        assertEquals(2, recent.size());
        assertEquals("encrypt-1", recent.get(0).get("encryptId"));
        assertEquals("Java开发(急招)", recent.get(0).get("jobName"));
    }

    @Test
    void findJobRowByEncryptIdReturnsLatestOrNull() {
        Map<String, Object> row = repository.findJobRowByEncryptId("encrypt-1");
        assertNotNull(row);
        assertEquals(JOB_ROW_KEYS, row.keySet());
        assertEquals("Java开发(急招)", row.get("jobName"));
        assertEquals("25-45K", row.get("salary"));

        assertNull(repository.findJobRowByEncryptId("missing"));
        assertNull(repository.findJobRowByEncryptId(null));
        assertNull(repository.findJobRowByEncryptId("  "));
    }

    // ==================== B3 联系人 / 跟进查询 ====================

    @Test
    void findConfirmedFirstContactsForContactFiltersAndOrders() {
        insertAttempt("att-hr1-1", "run-a", "FIRST_CONTACT", "enc-1", "HR-1", "岗位1", "公司1",
                "uid:HR-1", "CONFIRMED", "NOT_ATTEMPTED", "2026-11-01 10:00:00", null);
        insertAttempt("att-hr1-2", "run-a", "FIRST_CONTACT", "enc-2", "HR-1", "岗位2", "公司2",
                "uid:HR-1", "CONFIRMED", "NOT_ATTEMPTED", "2026-11-03 10:00:00", null);
        insertAttempt("att-hr2-1", "run-a", "FIRST_CONTACT", "enc-1", "HR-2", "岗位3", "公司3",
                "uid:HR-2", "CONFIRMED", "NOT_ATTEMPTED", "2026-11-02 10:00:00", null);
        insertAttempt("att-hr1-unknown", "run-a", "FIRST_CONTACT", "enc-3", "HR-1", "岗位4", "公司4",
                "uid:HR-1", "UNKNOWN", "NOT_ATTEMPTED", "2026-11-04 10:00:00", null);

        List<Map<String, Object>> rows = repository.findConfirmedFirstContactsForContact(
                PersistenceTestSupport.ACCOUNT, "uid:HR-1", null, 10);
        assertEquals(2, rows.size(), "只返回该联系人 CONFIRMED 的首次沟通");
        assertEquals(Set.of("attemptId", "runId", "encryptId", "contactKey", "jobName",
                "companyName", "startedAt"), rows.get(0).keySet());
        assertEquals("att-hr1-2", rows.get(0).get("attemptId"), "按 id DESC，最新在前");
        assertEquals("enc-2", rows.get(0).get("encryptId"));
        assertEquals("uid:HR-1", rows.get(0).get("contactKey"));

        List<Map<String, Object>> since = repository.findConfirmedFirstContactsForContact(
                PersistenceTestSupport.ACCOUNT, "uid:HR-1", "2026-11-02 00:00:00", 10);
        assertEquals(1, since.size());
        assertEquals("att-hr1-2", since.get(0).get("attemptId"));
        assertEquals(1, repository.findConfirmedFirstContactsForContact(
                PersistenceTestSupport.ACCOUNT, "uid:HR-1", null, 1).size(), "limit 生效");
        assertTrue(repository.findConfirmedFirstContactsForContact(
                PersistenceTestSupport.ACCOUNT, "uid:missing", null, 10).isEmpty());
    }

    @Test
    void hasConfirmedImageForContactJob() {
        insertAttempt("att-img-confirmed", "run-a", "FIRST_CONTACT", "enc-1", "HR-1", "岗位1", "公司1",
                "uid:HR-1", "CONFIRMED", "CONFIRMED", "2026-11-01 10:00:00", null);
        insertAttempt("att-img-failed", "run-a", "FIRST_CONTACT", "enc-2", "HR-1", "岗位2", "公司2",
                "uid:HR-1", "CONFIRMED", "FAILED", "2026-11-01 10:00:00", null);

        assertTrue(repository.hasConfirmedImageForContactJob("local", "uid:HR-1", "enc-1"));
        assertFalse(repository.hasConfirmedImageForContactJob("local", "uid:HR-1", "enc-2"),
                "图片 FAILED 不算已确认");
        assertFalse(repository.hasConfirmedImageForContactJob("local", "uid:HR-1", "enc-missing"));
        assertFalse(repository.hasConfirmedImageForContactJob("local", "uid:missing", "enc-1"));
        assertFalse(repository.hasConfirmedImageForContactJob("local", null, "enc-1"));
    }

    @Test
    void findCandidateFollowupAttemptsDedupesLatestPerContactJob() {
        // 同一 contact+job 两条 CONFIRMED：只取最新一条
        insertAttempt("att-dup-old", "run-a", "FIRST_CONTACT", "enc-1", "HR-1", "岗位1", "公司1",
                "uid:HR-1", "CONFIRMED", "NOT_ATTEMPTED", "2026-11-01 10:00:00", null);
        insertAttempt("att-dup-new", "run-a", "FIRST_CONTACT", "enc-1", "HR-1", "岗位1", "公司1",
                "uid:HR-1", "CONFIRMED", "NOT_ATTEMPTED", "2026-11-03 10:00:00", null);
        insertAttempt("att-hr1-enc2", "run-a", "FIRST_CONTACT", "enc-2", "HR-1", "岗位2", "公司2",
                "uid:HR-1", "CONFIRMED", "NOT_ATTEMPTED", "2026-11-02 10:00:00", null);
        insertAttempt("att-hr2", "run-a", "FIRST_CONTACT", "enc-1", "HR-2", "岗位3", "公司3",
                "uid:HR-2", "CONFIRMED", "NOT_ATTEMPTED", "2026-11-04 10:00:00", null);
        // contactKey 为 null / 非 CONFIRMED 都不应出现
        insertAttempt("att-null-contact", "run-a", "FIRST_CONTACT", "enc-9", null, "岗位9", "公司9",
                null, "CONFIRMED", "NOT_ATTEMPTED", "2026-11-05 10:00:00", null);
        insertAttempt("att-unknown", "run-a", "FIRST_CONTACT", "enc-8", "HR-8", "岗位8", "公司8",
                "uid:HR-8", "UNKNOWN", "NOT_ATTEMPTED", "2026-11-05 10:00:00", null);

        List<Map<String, Object>> candidates = repository.findCandidateFollowupAttempts("local", 10);
        assertEquals(3, candidates.size());
        assertEquals(Set.of("attemptId", "runId", "encryptId", "encryptUserId", "contactKey",
                "jobName", "companyName", "startedAt"), candidates.get(0).keySet());
        assertEquals(List.of("att-hr2", "att-dup-new", "att-hr1-enc2"),
                candidates.stream().map(row -> row.get("attemptId")).toList(),
                "started_at DESC，且同 contact+job 只保留最新一条");
        assertEquals(2, repository.findCandidateFollowupAttempts("local", 2).size(), "limit 生效");
    }

    @Test
    void findAttemptForImageResendReturnsContractFields() {
        insertAttempt("att-origin", "run-a", "FIRST_CONTACT", "enc-1", "HR-1", "岗位1", "公司1",
                "uid:HR-1", "CONFIRMED", "FAILED", "2026-11-01 10:00:00", null);

        Map<String, Object> attempt = repository.findAttemptForImageResend("att-origin");
        assertNotNull(attempt);
        assertEquals(Set.of("attemptId", "runId", "encryptId", "encryptUserId", "contactKey",
                        "parentAttemptId", "textStatus", "imageStatus", "jobName", "companyName",
                        "startedAt", "finishedAt"),
                attempt.keySet());
        assertEquals("CONFIRMED", attempt.get("textStatus"));
        assertEquals("FAILED", attempt.get("imageStatus"));
        assertEquals("uid:HR-1", attempt.get("contactKey"));
        assertNull(repository.findAttemptForImageResend("missing"));
        assertNull(repository.findAttemptForImageResend(" "));
    }

    @Test
    void findAttemptsByContactReturnsLatestWithFullAttemptFields() {
        insertAttempt("att-contact-1", "run-a", "FIRST_CONTACT", "enc-1", "HR-1", "岗位1", "公司1",
                "uid:HR-1", "CONFIRMED", "NOT_ATTEMPTED", "2026-11-01 10:00:00", null);
        insertAttempt("att-contact-2", "run-a", "FOLLOWUP", "enc-2", "HR-1", "岗位2", "公司2",
                "uid:HR-1", "UNKNOWN", "NOT_ATTEMPTED", "2026-11-03 10:00:00", "att-parent");
        insertAttempt("att-other-contact", "run-a", "FIRST_CONTACT", "enc-3", "HR-2", "岗位3", "公司3",
                "uid:HR-2", "CONFIRMED", "NOT_ATTEMPTED", "2026-11-04 10:00:00", null);

        List<Map<String, Object>> attempts = repository.findAttemptsByContact("local", "uid:HR-1", 20);
        assertEquals(2, attempts.size());
        assertEquals("att-contact-2", attempts.get(0).get("attemptId"), "started_at DESC");
        assertEquals(ATTEMPT_KEYS, attempts.get(0).keySet());
        assertEquals("att-parent", attempts.get(0).get("parentAttemptId"));
        assertEquals("uid:HR-1", attempts.get(1).get("contactKey"));
        assertEquals(1, repository.findAttemptsByContact("local", "uid:HR-1", 1).size());
    }

    /** 直接插入一条 delivery_attempt（B3 查询测试用，可控制 contact_key/parent_attempt_id）。 */
    private void insertAttempt(String attemptId, String runId, String kind, String encryptId,
                               String encryptUserId, String jobName, String companyName,
                               String contactKey, String textStatus, String imageStatus,
                               String startedAt, String parentAttemptId) {
        execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, encrypt_id, encrypt_user_id, " +
                        "job_name, company_name, text_status, image_status, contact_key, parent_attempt_id, " +
                        "started_at, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                attemptId, runId, PersistenceTestSupport.ACCOUNT, kind, encryptId, encryptUserId,
                jobName, companyName, textStatus, imageStatus, contactKey, parentAttemptId,
                startedAt, startedAt);
    }

    // ==================== 种子数据 ====================

    private void seedRuns() {
        executeScript(dataSource,
                "INSERT INTO task_run(run_id, platform, state, config_snapshot, stop_reason, error, " +
                        "started_at, ended_at, updated_at) VALUES('run-a', 'boss', 'COMPLETED', null, null, null, " +
                        "'2026-11-01 10:00:00', '2026-11-01 10:30:00', '2026-11-01 10:30:00')",
                "INSERT INTO task_run(run_id, platform, state, config_snapshot, stop_reason, error, " +
                        "started_at, ended_at, updated_at) VALUES('run-b', 'boss', 'FAILED', null, null, '页面崩溃', " +
                        "'2026-11-02 12:00:00', null, '2026-11-02 12:10:00')",
                "INSERT INTO task_run(run_id, platform, state, config_snapshot, stop_reason, error, " +
                        "started_at, ended_at, updated_at) VALUES('run-c', 'boss', 'RUNNING', null, null, null, " +
                        "'2026-11-03 09:00:00', null, '2026-11-03 09:00:00')");
    }

    private void seedEvaluations() {
        executeScript(dataSource,
                "INSERT INTO job_evaluation(run_id, encrypt_id, encrypt_user_id, job_name, company_name, " +
                        "rule_version, rule_decision, rule_reason_code, ai_decision, ai_message, ai_model, " +
                        "prompt_tokens, completion_tokens, duration_ms, rule_config_version, rules_hash, " +
                        "created_at) VALUES('run-a', 'encrypt-1', 'user-1', 'Java开发', '甲公司', 'job-rules-v3@v1', " +
                        "'PASS', 'PASS', 'MATCH', '您好', 'test-model', 100, 20, 1500, 1, 'rules-hash-1', " +
                        "'2026-11-01 10:01:00')",
                "INSERT INTO job_evaluation(run_id, encrypt_id, job_name, company_name, rule_version, " +
                        "rule_decision, rule_reason_code, rule_evidence, rule_config_version, rules_hash, " +
                        "created_at) VALUES('run-b', 'encrypt-2', '前端开发', '乙公司', 'job-rules-v3@v2', " +
                        "'REJECT', 'JOB_BLACKLIST', '外包', 2, 'rules-hash-2', '2026-11-02 12:01:00')",
                "INSERT INTO job_evaluation(run_id, encrypt_id, job_name, company_name, rule_version, " +
                        "rule_decision, rule_reason_code, ai_decision, ai_error_code, rule_config_version, " +
                        "rules_hash, created_at) VALUES('run-b', 'encrypt-1', 'Java开发', '甲公司', " +
                        "'job-rules-v3@v2', 'PASS', 'PASS', 'ERROR', 'AI_OUTPUT_INVALID', 2, 'rules-hash-2', " +
                        "'2026-11-03 09:01:00')");
    }

    /**
     * boss_data 是旧表（不由迁移创建），B2 重评数据源从这里取岗位快照。
     * 造 3 行、其中 encrypt-1 有两条，用于验证「同一 encrypt_id 取最新一行」。
     */
    private void seedBossData() {
        executeScript(dataSource,
                "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, " +
                        "encrypt_user_id TEXT, job_name TEXT, salary TEXT, location TEXT, experience TEXT, " +
                        "degree TEXT, job_description TEXT, company_name TEXT, industry TEXT, financing_stage TEXT, " +
                        "company_scale TEXT, company_address TEXT, recruitment_status TEXT, hr_name TEXT, " +
                        "hr_position TEXT, hr_active_status TEXT, job_url TEXT, delivery_status TEXT, " +
                        "created_at TEXT, updated_at TEXT)",
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name, salary, location, degree, " +
                        "job_description, company_name, industry, financing_stage, company_scale, company_address, " +
                        "recruitment_status, hr_name, hr_position, hr_active_status, job_url, delivery_status) " +
                        "VALUES('encrypt-1', 'user-1', 'Java开发', '20-40K', '北京', '本科', '负责后端开发', " +
                        "'甲公司', '互联网', 'C轮', '100-499人', '北京朝阳', '在招', '张HR', '技术招聘', '今日活跃', " +
                        "'https://example.com/1', '已投递')",
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name, salary, location, degree, " +
                        "job_description, company_name, industry, financing_stage, company_scale, company_address, " +
                        "recruitment_status, hr_name, hr_position, hr_active_status, job_url, delivery_status) " +
                        "VALUES('encrypt-2', 'user-2', '前端开发', '15-25K', '上海', '大专', '负责前端开发', " +
                        "'乙公司', '互联网', 'B轮', '20-99人', '上海浦东', '在招', '李HR', 'HR', '本周活跃', " +
                        "'https://example.com/2', '未投递')",
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name, salary, location, degree, " +
                        "job_description, company_name, industry, financing_stage, company_scale, company_address, " +
                        "recruitment_status, hr_name, hr_position, hr_active_status, job_url, delivery_status) " +
                        "VALUES('encrypt-1', 'user-1', 'Java开发(急招)', '25-45K', '北京', '本科', '负责后端开发', " +
                        "'甲公司', '互联网', 'C轮', '100-499人', '北京朝阳', '在招', '张HR', '技术招聘', '今日活跃', " +
                        "'https://example.com/1-new', '未投递')");
    }

    /**
     * 通过真实台账服务创建三条 attempt，再用 SQL 固定 started_at/状态，保证排序与筛选可断言。
     */
    private AttemptSeed seedAttempts() {
        DeliveryLedgerService.AttemptStarted unknown = ledger.beginAttempt(
                "run-a", DeliveryKind.FIRST_CONTACT, "encrypt-1", "user-1", "Java开发", "甲公司",
                "https://example.com/job-1", "snap-1", "msg-1", null, (String) null);
        DeliveryLedgerService.AttemptStarted failed = ledger.beginAttempt(
                "run-b", DeliveryKind.FOLLOWUP, "encrypt-2", "user-2", "前端开发", "乙公司",
                null, null, null, null, (String) null);
        DeliveryLedgerService.AttemptStarted confirmed = ledger.beginAttempt(
                "run-b", DeliveryKind.FIRST_CONTACT, "encrypt-1", "user-1", "Java开发", "甲公司",
                null, null, null, null, (String) null);
        executeScript(dataSource,
                "UPDATE delivery_attempt SET text_status = 'UNKNOWN', image_status = 'UNKNOWN', " +
                        "started_at = '2026-11-01 10:00:00' WHERE id = " + unknown.rowId(),
                "UPDATE delivery_attempt SET text_status = 'FAILED', " +
                        "started_at = '2026-11-02 12:00:00' WHERE id = " + failed.rowId(),
                "UPDATE delivery_attempt SET text_status = 'CONFIRMED', image_status = 'CONFIRMED', " +
                        "started_at = '2026-11-03 09:00:00' WHERE id = " + confirmed.rowId());
        return new AttemptSeed(unknown.attemptId(), unknown.rowId(),
                failed.attemptId(), confirmed.attemptId());
    }

    private record AttemptSeed(String unknownAttemptId, long unknownRowId,
                               String failedAttemptId, String confirmedAttemptId) {
    }
}
