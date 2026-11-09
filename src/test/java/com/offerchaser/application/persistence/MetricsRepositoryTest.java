package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.offerchaser.application.persistence.PersistenceTestSupport.ACCOUNT;
import static com.offerchaser.application.persistence.PersistenceTestSupport.execute;
import static com.offerchaser.application.persistence.PersistenceTestSupport.executeScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 统计口径测试：空库全 0、新岗位 legacy 分离、评估 latest 口径、attempt 状态与时间边界、
 * 事件、联系人、跟进、预算、HR 活跃、legacy 已投递、按日序列补零与 collection。
 */
class MetricsRepositoryTest {

    private static final String FROM = "2026-11-01";
    private static final String TO = "2026-11-07";

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource;
    private MetricsRepository repository;
    private JobSourceRepository jobSourceRepository;

    @BeforeEach
    void setUp() {
        dataSource = PersistenceTestSupport.migratedDataSource(directory);
        repository = new MetricsRepository(dataSource);
        jobSourceRepository = new JobSourceRepository(dataSource);
        // Metrics 涉及旧表 boss_data（B4 三列）；测试里显式建表并带齐 B4 字段
        executeScript(dataSource,
                "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, " +
                        "encrypt_user_id TEXT, job_name TEXT, salary TEXT, location TEXT, experience TEXT, " +
                        "degree TEXT, job_description TEXT, company_name TEXT, industry TEXT, " +
                        "financing_stage TEXT, company_scale TEXT, company_address TEXT, " +
                        "recruitment_status TEXT, hr_name TEXT, hr_position TEXT, hr_active_status TEXT, " +
                        "job_url TEXT, delivery_status TEXT, created_at TEXT, updated_at TEXT, " +
                        "first_seen_at TEXT, last_detail_at TEXT, snapshot_hash TEXT)");
    }

    @Test
    void emptyDatabaseReturnsZerosAndZeroFilledSeries() {
        assertTrue(repository.countNewJobs(ACCOUNT, FROM, TO).values().stream().allMatch(v -> v == 0));
        assertTrue(repository.countLatestEvaluations(ACCOUNT, FROM, TO, null).values().stream()
                .allMatch(v -> v == 0));
        assertTrue(repository.countAttempts(ACCOUNT, FROM, TO).values().stream().allMatch(v -> v == 0));
        assertTrue(repository.countEvents(ACCOUNT, FROM, TO).values().stream().allMatch(v -> v == 0));
        assertTrue(repository.countContacts(ACCOUNT).values().stream().allMatch(v -> v == 0));
        assertTrue(repository.countFollowups(ACCOUNT, FROM, TO).values().stream().allMatch(v -> v == 0));
        assertEquals(0L, repository.countBudget(ACCOUNT, "2026-11-05").get("limit"));
        assertEquals(-1L, repository.countBudget(ACCOUNT, "2026-11-05").get("remaining"),
                "无预算行时 remaining=-1（不限）");
        assertTrue(repository.countHrActive(ACCOUNT, 10).isEmpty());
        assertEquals(0, repository.countLegacyDelivered(ACCOUNT));

        List<Map<String, Object>> points = repository.series(ACCOUNT, "newJobs", FROM, TO);
        assertEquals(7, points.size(), "无数据日期补 0");
        assertTrue(points.stream().allMatch(p -> ((Number) p.get("value")).longValue() == 0));
        assertEquals("2026-11-01", points.get(0).get("date"));
        assertEquals("2026-11-07", points.get(6).get("date"));

        List<Map<String, Object>> daily = repository.dailyCollection(ACCOUNT, FROM, TO);
        assertEquals(7, daily.size());
        assertTrue(daily.stream().allMatch(row -> ((Number) row.get("newJobs")).longValue() == 0
                && ((Number) row.get("sourceRecords")).longValue() == 0));

        assertThrows(IllegalArgumentException.class,
                () -> repository.series(ACCOUNT, "unknownMetric", FROM, TO));
        assertThrows(IllegalArgumentException.class,
                () -> repository.series(ACCOUNT, null, FROM, TO));
    }

    @Test
    void countNewJobsSeparatesLegacyRows() {
        insertBossData("job-1", "2026-11-01 10:00:00", "2026-11-01 10:00:00", "未投递", null);
        insertBossData("job-2", "2026-11-03 10:00:00", "2026-11-03 10:00:00", "未投递", null);
        insertBossData("job-3", null, "2026-11-02 10:00:00", "未投递", null);
        insertBossData("job-4", null, "2026-10-01 10:00:00", "未投递", null);

        Map<String, Long> counts = repository.countNewJobs(ACCOUNT, FROM, TO);
        assertEquals(2L, counts.get("newJobs"), "first_seen_at 在范围内去重岗位");
        assertEquals(1L, counts.get("legacyNewJobs"), "legacy 单独统计，不混入 newJobs");
    }

    @Test
    void countLatestEvaluationsUsesLatestPerRunAndJob() {
        insertEvaluation("run-a", "enc-1", "PASS", "MATCH", "2026-11-01 10:00:00");
        insertEvaluation("run-a", "enc-1", "REJECT", null, "2026-11-02 10:00:00");
        insertEvaluation("run-a", "enc-2", "PASS", "MATCH", "2026-11-01 11:00:00");
        insertEvaluation("run-b", "enc-1", "REVIEW", "ERROR", "2026-11-03 10:00:00");
        insertEvaluation("run-b", "enc-2", "PASS", "MATCH", "2026-10-01 10:00:00");
        insertEvaluation("run-c", "enc-3", "PASS", "MATCH", "2026-10-05 10:00:00");

        Map<String, Long> counts = repository.countLatestEvaluations(ACCOUNT, FROM, TO, null);
        assertEquals(3L, counts.get("evalTotal"), "每个 (run_id, encrypt_id) 只取最新一条");
        assertEquals(1L, counts.get("rulePass"));
        assertEquals(1L, counts.get("ruleReject"));
        assertEquals(1L, counts.get("ruleReview"));
        assertEquals(1L, counts.get("aiMatch"));
        assertEquals(0L, counts.get("aiReject"));
        assertEquals(1L, counts.get("aiError"));
        assertEquals(1L, counts.get("aiUnset"));

        Map<String, Long> runA = repository.countLatestEvaluations(ACCOUNT, FROM, TO, "run-a");
        assertEquals(2L, runA.get("evalTotal"));
        assertEquals(1L, runA.get("ruleReject"));
        assertEquals(1L, runA.get("rulePass"));
        assertEquals(0L, runA.get("aiError"));
    }

    @Test
    void countAttemptsUsesStartedAndFinishedBoundaries() {
        insertAttempt("att-1", "run-1", "FIRST_CONTACT", "CONFIRMED", "CONFIRMED",
                "2026-11-01 09:00:00", "2026-11-01 09:01:00");
        insertAttempt("att-2", "run-1", "FIRST_CONTACT", "UNKNOWN", "UNKNOWN",
                "2026-11-03 09:00:00", "2026-11-03 09:05:00");
        insertAttempt("att-3", "run-1", "FIRST_CONTACT", "PENDING", "NOT_ATTEMPTED",
                "2026-11-05 09:00:00", null);
        insertAttempt("att-4", "run-1", "FOLLOWUP", "CONFIRMED", "NOT_ATTEMPTED",
                "2026-11-04 09:00:00", "2026-11-04 09:01:00");
        // 开始/结束都在范围外
        insertAttempt("att-out", "run-0", "FIRST_CONTACT", "FAILED", "FAILED",
                "2026-10-01 09:00:00", "2026-10-01 09:01:00");
        // 开始时间在范围内但 finished_at 超出范围：计入 attemptsTotal，不计入 textConfirmed
        insertAttempt("att-confirmed-finished-out", "run-1", "FIRST_CONTACT", "CONFIRMED", "NOT_ATTEMPTED",
                "2026-11-06 09:00:00", "2026-10-20 09:00:00");
        // 其他账号不参与
        execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "started_at, finished_at, created_at) VALUES('att-other', 'run-x', 'other', " +
                        "'FIRST_CONTACT', 'CONFIRMED', 'NOT_ATTEMPTED', '2026-11-02 09:00:00', " +
                        "'2026-11-02 09:01:00', '2026-11-02 09:00:00')");

        Map<String, Long> counts = repository.countAttempts(ACCOUNT, FROM, TO);
        assertEquals(5L, counts.get("attemptsTotal"));
        assertEquals(4L, counts.get("firstAttempt"));
        assertEquals(1L, counts.get("followupAttempt"));
        assertEquals(2L, counts.get("textConfirmed"), "只认 finished_at 落在范围内的 CONFIRMED");
        assertEquals(0L, counts.get("textFailed"));
        assertEquals(1L, counts.get("textUnknown"));
        assertEquals(1L, counts.get("textPending"));
        assertEquals(1L, counts.get("imageConfirmed"));
        assertEquals(0L, counts.get("imageFailed"));
        assertEquals(1L, counts.get("imageUnknown"));
        assertEquals(0L, counts.get("imageSkipped"));
    }

    @Test
    void countEventsJoinsAttemptAccount() {
        insertAttempt("att-1", "run-1", "FIRST_CONTACT", "UNKNOWN", "NOT_ATTEMPTED",
                "2026-11-01 09:00:00", "2026-11-01 09:05:00");
        insertEvent("att-1", "run-1", "CHAT_REQUESTED", "2026-11-01 09:00:10");
        insertEvent("att-1", "run-1", "TEXT_CONFIRMED", "2026-11-01 09:00:30");
        insertEvent("att-1", "run-1", "TEXT_CONFIRMED", "2026-10-01 09:00:30");
        execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "started_at, created_at) VALUES('att-other', 'run-x', 'other', 'FIRST_CONTACT', " +
                        "'UNKNOWN', 'NOT_ATTEMPTED', '2026-11-01 09:00:00', '2026-11-01 09:00:00')");
        insertEvent("att-other", "run-x", "CHAT_REQUESTED", "2026-11-01 09:00:10");

        Map<String, Long> counts = repository.countEvents(ACCOUNT, FROM, TO);
        assertEquals(1L, counts.get("chatRequested"));
        assertEquals(1L, counts.get("textConfirmedEvents"), "范围外事件不计");
    }

    @Test
    void countContactsAndFollowupsAndBudget() {
        insertContact("uid:HR-1", "REPLIED", 0, 0, "2026-11-01 09:00:00", "2026-11-02 09:00:00");
        insertContact("uid:HR-2", "INTERVIEW", 0, 0, "2026-11-01 09:00:00", null);
        insertContact("uid:HR-3", "UNKNOWN", 0, 0, null, null);
        insertContact("uid:HR-4", "UNKNOWN", 1, 1, "2026-11-03 09:00:00", null);

        Map<String, Long> contacts = repository.countContacts(ACCOUNT);
        assertEquals(4L, contacts.get("contacts"));
        assertEquals(1L, contacts.get("replied"));
        assertEquals(1L, contacts.get("interview"));
        assertEquals(1L, contacts.get("manualTakeover"));
        assertEquals(1L, contacts.get("stopContact"));
        assertEquals(3L, contacts.get("contacted"));

        insertFollowup("plan-1", "SUGGESTED", "2026-11-02 10:00:00", null, null, "2026-11-02 10:00:00");
        insertFollowup("plan-2", "APPROVED", "2026-11-01 10:00:00", "2026-11-02 10:00:00", null,
                "2026-11-02 10:00:00");
        insertFollowup("plan-3", "APPROVED", "2026-10-01 10:00:00", "2026-10-02 10:00:00", null,
                "2026-10-02 10:00:00");
        insertFollowup("plan-4", "REJECTED", "2026-11-01 10:00:00", "2026-11-03 10:00:00", null,
                "2026-11-03 10:00:00");
        insertFollowup("plan-5", "EXECUTED", "2026-11-01 10:00:00", "2026-11-02 10:00:00",
                "2026-11-04 10:00:00", "2026-11-04 10:00:00");
        insertFollowup("plan-6", "CANCELLED", "2026-11-01 10:00:00", null, null, "2026-11-05 10:00:00");

        Map<String, Long> followups = repository.countFollowups(ACCOUNT, FROM, TO);
        assertEquals(1L, followups.get("suggested"));
        assertEquals(1L, followups.get("approved"), "approved 按 decided_at 落范围");
        assertEquals(1L, followups.get("rejected"));
        assertEquals(1L, followups.get("executed"));
        assertEquals(1L, followups.get("cancelled"));
        assertEquals(2L, followups.get("approvedPending"), "approvedPending 是当前 APPROVED 数（不限时间）");

        insertBudget("2026-11-05", "FIRST_CONTACT", 10, 1, 2, 0);
        insertBudget("2026-11-05", "FOLLOWUP", 5, 0, 1, 1);
        Map<String, Long> budget = repository.countBudget(ACCOUNT, "2026-11-05");
        assertEquals(15L, budget.get("limit"));
        assertEquals(1L, budget.get("consumed"));
        assertEquals(3L, budget.get("reserved"));
        assertEquals(1L, budget.get("uncertain"));
        assertEquals(10L, budget.get("remaining"));
    }

    @Test
    void countHrActiveUsesLatestBossDataPerJob() {
        // encrypt-1 两行：最新一行状态为「本周活跃」
        insertBossDataWithHr("enc-1", "今日活跃", "2026-10-01 10:00:00");
        insertBossDataWithHr("enc-1", "本周活跃", "2026-11-01 10:00:00");
        insertBossDataWithHr("enc-2", "今日活跃", "2026-11-01 10:00:00");
        insertBossDataWithHr("enc-3", null, "2026-11-01 10:00:00");

        Map<String, Long> active = repository.countHrActive(ACCOUNT, 10);
        assertEquals(2, active.size());
        assertEquals(1L, active.get("本周活跃"));
        assertEquals(1L, active.get("今日活跃"));
        assertEquals(1, repository.countHrActive(ACCOUNT, 1).size(), "limit 生效");
    }

    @Test
    void countLegacyDeliveredExcludesConfirmedAttempts() {
        insertBossData("legacy-1", null, "2026-10-01 10:00:00", "已投递", null);
        insertBossData("legacy-2", null, "2026-10-01 10:00:00", "已投递", null);
        insertBossData("new-1", null, "2026-10-01 10:00:00", "未投递", null);
        // legacy-2 有 CONFIRMED attempt（新口径确认过，不算 unverified）
        insertAttempt("att-legacy-2", "run-1", "FIRST_CONTACT", "CONFIRMED", "NOT_ATTEMPTED",
                "2026-11-01 09:00:00", "2026-11-01 09:01:00", "legacy-2");

        assertEquals(1, repository.countLegacyDelivered(ACCOUNT));
    }

    @Test
    void seriesZeroFillsAndSupportsAllMetrics() {
        insertBossData("job-1", "2026-11-01 10:00:00", "2026-11-01 10:00:00", "未投递", null);
        insertBossData("job-2", "2026-11-01 11:00:00", "2026-11-01 11:00:00", "未投递", null);
        insertBossData("job-3", "2026-11-03 10:00:00", "2026-11-03 10:00:00", "未投递", null);
        insertAttempt("att-1", "run-1", "FIRST_CONTACT", "CONFIRMED", "NOT_ATTEMPTED",
                "2026-11-01 09:00:00", "2026-11-01 09:01:00");
        insertAttempt("att-2", "run-1", "FIRST_CONTACT", "CONFIRMED", "NOT_ATTEMPTED",
                "2026-11-03 09:00:00", "2026-11-03 09:01:00");
        insertContact("uid:HR-1", "REPLIED", 0, 0, "2026-11-01 09:00:00", "2026-11-02 09:00:00");
        insertFollowup("plan-executed", "EXECUTED", "2026-11-01 10:00:00", "2026-11-02 10:00:00",
                "2026-11-03 10:00:00", "2026-11-03 10:00:00");
        insertEvaluation("run-1", "enc-1", "PASS", "MATCH", "2026-11-01 10:00:00");
        insertEvaluation("run-1", "enc-1", "REJECT", null, "2026-11-02 10:00:00");

        List<Map<String, Object>> newJobs = repository.series(ACCOUNT, "newJobs", "2026-11-01", "2026-11-03");
        assertEquals(List.of(2L, 0L, 1L), values(newJobs));

        List<Map<String, Object>> textConfirmed = repository.series(
                ACCOUNT, "textConfirmed", "2026-11-01", "2026-11-03");
        assertEquals(List.of(1L, 0L, 1L), values(textConfirmed));

        List<Map<String, Object>> firstContact = repository.series(
                ACCOUNT, "firstContactAttempts", "2026-11-01", "2026-11-03");
        assertEquals(List.of(1L, 0L, 1L), values(firstContact));

        // latest 口径：enc-1 在 11-02 的最新一条是 REJECT，因此没有 PASS 计入
        assertEquals(List.of(0L, 0L, 0L),
                values(repository.series(ACCOUNT, "rulePass", "2026-11-01", "2026-11-03")));
        assertEquals(List.of(0L, 1L, 0L),
                values(repository.series(ACCOUNT, "ruleReject", "2026-11-01", "2026-11-03")));

        assertEquals(List.of(0L, 1L, 0L),
                values(repository.series(ACCOUNT, "replies", "2026-11-01", "2026-11-03")));
        assertEquals(List.of(0L, 0L, 1L),
                values(repository.series(ACCOUNT, "followupsExecuted", "2026-11-01", "2026-11-03")));
    }

    @Test
    void dailyCollectionMergesNewJobsAndSourceRecords() {
        insertBossData("job-1", "2026-11-01 10:00:00", "2026-11-01 10:00:00", "未投递", null);
        insertBossData("job-2", "2026-11-01 11:00:00", "2026-11-01 11:00:00", "未投递", null);
        insertBossData("job-3", "2026-11-02 10:00:00", "2026-11-02 10:00:00", "未投递", null);
        jobSourceRepository.recordIfAbsent(ACCOUNT, "boss", "job-1", "Java", null, "2026-11-01 10:00:00", null);
        jobSourceRepository.recordIfAbsent(ACCOUNT, "boss", "job-1", "后端", null, "2026-11-01 11:00:00", null);
        jobSourceRepository.recordIfAbsent(ACCOUNT, "boss", "job-3", "Java", null, "2026-11-03 10:00:00", null);

        List<Map<String, Object>> daily = repository.dailyCollection(ACCOUNT, "2026-11-01", "2026-11-03");
        assertEquals(3, daily.size());
        assertEquals("2026-11-01", daily.get(0).get("date"));
        assertEquals(2L, daily.get(0).get("newJobs"));
        assertEquals(2L, daily.get(0).get("sourceRecords"));
        assertEquals(1L, daily.get(1).get("newJobs"));
        assertEquals(0L, daily.get(1).get("sourceRecords"));
        assertEquals(0L, daily.get(2).get("newJobs"));
        assertEquals(1L, daily.get(2).get("sourceRecords"));
    }

    // ==================== 数据构造工具 ====================

    private List<Long> values(List<Map<String, Object>> points) {
        return points.stream().map(point -> ((Number) point.get("value")).longValue()).toList();
    }

    private void insertBossData(String encryptId, String firstSeenAt, String createdAt,
                                String deliveryStatus, String hrActiveStatus) {
        execute(dataSource,
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name, delivery_status, created_at, " +
                        "updated_at, first_seen_at, last_detail_at, snapshot_hash, hr_active_status) " +
                        "VALUES(?, ?, '岗位', ?, ?, ?, ?, ?, 'snap', ?)",
                encryptId, "user-" + encryptId, deliveryStatus, createdAt, createdAt,
                firstSeenAt, firstSeenAt, hrActiveStatus);
    }

    private void insertBossDataWithHr(String encryptId, String hrActiveStatus, String createdAt) {
        execute(dataSource,
                "INSERT INTO boss_data(encrypt_id, job_name, hr_active_status, created_at, updated_at) " +
                        "VALUES(?, '岗位', ?, ?, ?)",
                encryptId, hrActiveStatus, createdAt, createdAt);
    }

    private void insertAttempt(String attemptId, String runId, String kind, String textStatus,
                               String imageStatus, String startedAt, String finishedAt) {
        insertAttempt(attemptId, runId, kind, textStatus, imageStatus, startedAt, finishedAt, null);
    }

    private void insertAttempt(String attemptId, String runId, String kind, String textStatus,
                               String imageStatus, String startedAt, String finishedAt, String encryptId) {
        execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, encrypt_id, job_name, " +
                        "text_status, image_status, started_at, finished_at, created_at) " +
                        "VALUES(?, ?, ?, ?, ?, '岗位', ?, ?, ?, ?, ?)",
                attemptId, runId, ACCOUNT, kind, encryptId == null ? "enc-" + attemptId : encryptId,
                textStatus, imageStatus, startedAt, finishedAt, startedAt);
    }

    private void insertEvaluation(String runId, String encryptId, String ruleDecision,
                                  String aiDecision, String createdAt) {
        execute(dataSource,
                "INSERT INTO job_evaluation(run_id, encrypt_id, rule_decision, ai_decision, created_at) " +
                        "VALUES(?, ?, ?, ?, COALESCE(?, '2026-11-01 00:00:00'))",
                runId, encryptId, ruleDecision, aiDecision, createdAt);
    }

    private void insertEvent(String attemptId, String runId, String eventType, String occurredAt) {
        execute(dataSource,
                "INSERT INTO delivery_event(attempt_id, run_id, event_type, evidence_level, detail, occurred_at) " +
                        "VALUES(?, ?, ?, 'DOM', '测试事件', ?)",
                attemptId, runId, eventType, occurredAt);
    }

    private void insertContact(String contactKey, String replyState, int manualTakeover, int stopContact,
                               String lastContactAt, String lastReplyAt) {
        execute(dataSource,
                "INSERT INTO contact_state(account, platform, contact_key, reply_state, manual_takeover, " +
                        "stop_contact, last_contact_at, last_reply_at, version, created_at, updated_at) " +
                        "VALUES(?, 'boss', ?, ?, ?, ?, ?, ?, 0, '2026-11-01 00:00:00', '2026-11-01 00:00:00')",
                ACCOUNT, contactKey, replyState, manualTakeover, stopContact, lastContactAt, lastReplyAt);
    }

    private void insertFollowup(String planId, String status, String suggestedAt, String decidedAt,
                                String executedAt, String updatedAt) {
        execute(dataSource,
                "INSERT INTO followup_plan(plan_id, account, platform, encrypt_id, contact_key, plan_type, " +
                        "status, version, suggested_at, decided_at, executed_at, created_at, updated_at) " +
                        "VALUES(?, ?, 'boss', 'enc-1', 'uid:HR-1', 'FOLLOWUP', ?, 0, " +
                        "COALESCE(?, '2026-11-01 00:00:00'), ?, ?, '2026-11-01 00:00:00', " +
                        "COALESCE(?, '2026-11-01 00:00:00'))",
                planId, ACCOUNT, status, suggestedAt, decidedAt, executedAt, updatedAt);
    }

    private void insertBudget(String budgetDate, String actionType, long limit, long consumed,
                              long reserved, long uncertain) {
        execute(dataSource,
                "INSERT INTO daily_budget(account, budget_date, action_type, budget_limit, consumed, " +
                        "reserved, uncertain, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?, '2026-11-05 00:00:00')",
                ACCOUNT, budgetDate, actionType, limit, consumed, reserved, uncertain);
    }
}

