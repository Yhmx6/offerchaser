package com.offerchaser.verification;

import com.offerchaser.application.metrics.MetricsService;
import com.offerchaser.application.metrics.MetricsValidationException;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.JobSourceRepository;
import com.offerchaser.application.persistence.MetricsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B4 统计口径真实 SQLite 集成验收：
 * 用真实 {@link MetricsRepository}/{@link JobSourceRepository} 构造岗位、评估、尝试、事件、联系人、
 * 跟进、预算与采集来源数据，逐项断言 overview 数值与《B4 契约》口径；unknown/legacy 不混入成功；
 * 空库全 0；series 补零；collection 汇总；非法范围/指标抛 400 异常。
 */
class MetricsServiceIntegrationTest {

    private static final String ACCOUNT = "local";
    private static final String FROM = "2026-11-01";
    private static final String TO = "2026-11-05";
    private static final String TODAY = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private MetricsService metricsService;

    @BeforeEach
    void setUp() {
        dataSource = VerificationSupport.dataSource(tempDir.resolve("metrics.db"));
        execute(dataSource, "CREATE TABLE IF NOT EXISTS boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "encrypt_id TEXT, encrypt_user_id TEXT, company_name TEXT, job_name TEXT, salary TEXT, " +
                "location TEXT, experience TEXT, degree TEXT, hr_name TEXT, hr_position TEXT, " +
                "hr_active_status TEXT, delivery_status TEXT, job_description TEXT, job_url TEXT, " +
                "recruitment_status TEXT, company_address TEXT, industry TEXT, introduce TEXT, " +
                "financing_stage TEXT, company_scale TEXT, created_at TEXT, updated_at TEXT)");
        migratedDataSourceOn(dataSource);
        MetricsService service = new MetricsService(new MetricsRepository(dataSource),
                new JobSourceRepository(dataSource));
        ReflectionTestUtils.setField(service, "account", ACCOUNT);
        ReflectionTestUtils.setField(service, "timezone", "Asia/Shanghai");
        metricsService = service;
    }

    /** 在已建旧表（boss_data）的库上执行迁移，触发 V8 的 boss_data 三列/索引。 */
    private static void migratedDataSourceOn(SQLiteDataSource dataSource) {
        com.offerchaser.application.persistence.DatabaseMaintenanceService maintenance =
                new com.offerchaser.application.persistence.DatabaseMaintenanceService(dataSource);
        new com.offerchaser.application.persistence.SchemaMigrationService(dataSource, maintenance).migrate();
    }

    // ==================== overview：逐项口径 ====================

    @Test
    void overviewBudgetUsesBudgetServiceWhenNoDailyRow() {
        BudgetService budgetService = mock(BudgetService.class);
        when(budgetService.todaySummary()).thenReturn(Map.of(
                "date", TODAY,
                "firstContact", Map.of(
                        "limit", 145,
                        "consumed", 0,
                        "reserved", 0,
                        "remaining", 145,
                        "uncertain", 0)));
        ReflectionTestUtils.setField(metricsService, "budgetService", budgetService);

        Map<String, Object> response = metricsService.overview(FROM, TO, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) response.get("metrics");
        @SuppressWarnings("unchecked")
        Map<String, Object> budget = (Map<String, Object>) metrics.get("budget");
        assertEquals(145L, ((Number) budget.get("limit")).longValue(), "无 daily_budget 行时仍应展示配置上限");
        assertEquals(145L, ((Number) budget.get("remaining")).longValue(), "不能显示 -1");
    }

    @Test
    void overviewComputesEachMetricWithDeclaredCaliber() {
        seedJobs();
        seedAttempts();
        seedEvents();
        seedEvaluations();
        seedContacts();
        seedFollowups();
        seedBudget();
        seedJobSources();

        Map<String, Object> response = metricsService.overview(FROM, TO, null);

        assertEquals("Asia/Shanghai", response.get("timezone"));
        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) response.get("range");
        assertEquals(FROM, range.get("from"));
        assertEquals(TO, range.get("to"));
        assertEquals(5, range.get("days"));

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) response.get("metrics");

        // newJobs：first_seen_at 在范围内去重；legacy（first_seen_at null）单列不混入
        assertEquals(4L, metrics.get("newJobs"), "B1/B2/B4/B5 首次发现时间在范围内");
        assertEquals(1L, metrics.get("legacyNewJobs"), "B3 缺 first_seen_at 单独计入 legacy");

        // rule：每个 (run_id, encrypt_id) 最新一条；R1/ENC1 的旧 PASS 被新 REJECT 覆盖
        @SuppressWarnings("unchecked")
        Map<String, Object> rule = (Map<String, Object>) metrics.get("rule");
        assertEquals(2L, rule.get("pass"), "R1/ENC2 PASS + R2/ENC1 PASS");
        assertEquals(1L, rule.get("reject"), "R1/ENC1 最新为 REJECT");
        assertEquals(1L, rule.get("review"));
        assertEquals(4L, rule.get("total"));
        assertEquals(0.5, rule.get("passRate"), "通过率=2/4");

        @SuppressWarnings("unchecked")
        Map<String, Object> ai = (Map<String, Object>) metrics.get("ai");
        assertEquals(1L, ai.get("match"), "R2/ENC1 最新评估 AI MATCH（R1/ENC1 旧 MATCH 被新 REJECT 覆盖）");
        assertEquals(1L, ai.get("reject"));
        assertEquals(1L, ai.get("error"));
        assertEquals(1L, ai.get("unset"), "ai_decision 为 null 的评估计入 unset，不进分母");
        assertEquals(3L, ai.get("total"), "AI 分母只算 MATCH/REJECT/ERROR");

        // firstContact：按 started_at 范围计数，按 text_status 拆分（只算 FIRST_CONTACT，不含 FOLLOWUP）
        @SuppressWarnings("unchecked")
        Map<String, Object> firstContact = (Map<String, Object>) metrics.get("firstContact");
        assertEquals(4L, firstContact.get("attempts"), "范围内 4 条 FIRST_CONTACT（不含 9 月旧数据）");
        assertEquals(2L, firstContact.get("chatRequested"), "CHAT_REQUESTED 只算范围内事件");
        assertEquals(1L, firstContact.get("textConfirmed"), "A1 首次沟通确认成功（A5 是 FOLLOWUP，不混入）");
        assertEquals(1L, firstContact.get("textFailed"), "A2 首次沟通失败");
        assertEquals(1L, firstContact.get("textUnknown"), "A3 首次沟通结果未知");
        assertEquals(1L, firstContact.get("textPending"), "A4 首次沟通挂起");

        // textConfirmed：唯一 attempt、按 finished_at 落范围；未知不混入
        assertEquals(2L, metrics.get("textConfirmed"), "未知/旧已投递不得混入文字确认成功");

        @SuppressWarnings("unchecked")
        Map<String, Object> image = (Map<String, Object>) metrics.get("image");
        assertEquals(1L, image.get("confirmed"));
        assertEquals(1L, image.get("failed"));
        assertEquals(0L, image.get("unknown"));
        assertEquals(2L, image.get("skipped"), "A3/A5 图片跳过");
        assertEquals(4L, image.get("total"));

        // replies：联系人状态计数 + 回复率（contacted=last_contact_at 非空）
        @SuppressWarnings("unchecked")
        Map<String, Object> replies = (Map<String, Object>) metrics.get("replies");
        assertEquals(3L, replies.get("contacts"));
        assertEquals(2L, replies.get("contacted"));
        assertEquals(1L, replies.get("replied"));
        assertEquals(1L, replies.get("interview"));
        assertEquals(1.0, replies.get("replyRate"), "回复率=(replied+interview)/contacted");
        assertEquals(1L, replies.get("manualTakeover"));
        assertEquals(0L, replies.get("stopContact"));

        // followups：按对应时间列计数；approvedPending 为当前仍 APPROVED 数
        @SuppressWarnings("unchecked")
        Map<String, Object> followups = (Map<String, Object>) metrics.get("followups");
        assertEquals(1L, followups.get("suggested"));
        assertEquals(2L, followups.get("approved"));
        assertEquals(1L, followups.get("rejected"));
        assertEquals(1L, followups.get("cancelled"));
        assertEquals(1L, followups.get("executed"));
        assertEquals(2L, followups.get("approvedPending"));

        // budget：今日两类动作汇总，remaining=limit-consumed-reserved-uncertain
        @SuppressWarnings("unchecked")
        Map<String, Object> budget = (Map<String, Object>) metrics.get("budget");
        assertEquals(TODAY, budget.get("date"));
        assertEquals(120L, budget.get("limit"));
        assertEquals(13L, budget.get("consumed"));
        assertEquals(6L, budget.get("reserved"));
        assertEquals(2L, budget.get("uncertain"));
        assertEquals(99L, budget.get("remaining"));

        // collection：来源记录/去重岗位/关键词数
        @SuppressWarnings("unchecked")
        Map<String, Object> collection = (Map<String, Object>) metrics.get("collection");
        assertEquals(4L, collection.get("sourceRecords"));
        assertEquals(3L, collection.get("uniqueJobs"), "同岗位多关键词只增加来源，不重复岗位");
        assertEquals(2L, collection.get("keywords"));

        // hrActive：最新 boss_data 每岗位分类
        @SuppressWarnings("unchecked")
        Map<String, Object> hrActive = (Map<String, Object>) metrics.get("hrActive");
        assertEquals(2L, hrActive.get("今日活跃"));
        assertEquals(1L, hrActive.get("本周活跃"));

        // legacy：已投递且无 TEXT_CONFIRMED 的 legacy/unverified
        @SuppressWarnings("unchecked")
        Map<String, Object> legacy = (Map<String, Object>) metrics.get("legacy");
        assertEquals(1L, legacy.get("deliveredUnverified"));

        // dataQuality / definitions / links
        @SuppressWarnings("unchecked")
        Map<String, Object> dataQuality = (Map<String, Object>) response.get("dataQuality");
        assertEquals(2L, dataQuality.get("unknownText"), "UNKNOWN+PENDING 单列");
        assertEquals(1L, dataQuality.get("legacyDelivered"));
        assertEquals(1L, dataQuality.get("firstSeenMissing"));
        assertFalse(((List<?>) dataQuality.get("notes")).isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> definitions = (Map<String, Object>) response.get("definitions");
        for (String key : List.of("newJobs", "rule", "ai", "firstContact", "textConfirmed", "image",
                "replies", "followups", "budget", "collection", "legacy")) {
            assertTrue(definitions.containsKey(key), "缺少口径定义：" + key);
        }
        assertTrue(String.valueOf(definitions.get("textConfirmed")).contains("CONFIRMED"));

        @SuppressWarnings("unchecked")
        Map<String, Object> links = (Map<String, Object>) response.get("links");
        assertEquals("/boss/evaluations?from=" + FROM + "&to=" + TO, links.get("evaluations"));
        assertEquals("/boss/attempts?from=" + FROM + "&to=" + TO, links.get("attempts"));
        assertEquals("/boss/contacts", links.get("contacts"));
        assertEquals("/boss/followups", links.get("followups"));
        assertEquals("/boss/analysis#collection", links.get("collection"));
    }

    @Test
    void runIdFilterOnlyCountsThatRunEvaluations() {
        seedEvaluations();
        seedJobs();
        seedAttempts();

        Map<String, Object> response = metricsService.overview(FROM, TO, "R1");
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) response.get("metrics");
        @SuppressWarnings("unchecked")
        Map<String, Object> rule = (Map<String, Object>) metrics.get("rule");
        assertEquals(3L, rule.get("total"), "runId=R1 只统计 R1 的 3 个岗位最新评估");
        assertEquals(1L, rule.get("pass"));
        assertEquals(1L, rule.get("reject"));
        assertEquals(1L, rule.get("review"));
    }

    // ==================== 空库 / 默认范围 ====================

    @Test
    void emptyDatabaseReturnsZerosAndDoesNotThrow() {
        Map<String, Object> response = metricsService.overview(FROM, TO, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) response.get("metrics");
        assertEquals(0L, metrics.get("newJobs"));
        assertEquals(0L, metrics.get("legacyNewJobs"));
        assertEquals(0L, metrics.get("textConfirmed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rule = (Map<String, Object>) metrics.get("rule");
        assertEquals(0L, rule.get("total"));
        assertEquals(0.0, rule.get("passRate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> budget = (Map<String, Object>) metrics.get("budget");
        assertEquals(0L, budget.get("limit"));
        assertEquals(-1L, budget.get("remaining"), "无预算时 remaining=-1（不假装有额度）");
        @SuppressWarnings("unchecked")
        Map<String, Object> dataQuality = (Map<String, Object>) response.get("dataQuality");
        assertEquals(0L, dataQuality.get("unknownText"));
        assertEquals(0L, dataQuality.get("legacyDelivered"));

        // 默认最近 7 天（含今天）
        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) metricsService.overview(null, null, null)
                .get("range");
        assertEquals(7, range.get("days"));
        assertEquals(TODAY, range.get("to"));
    }

    // ==================== series：补零 / 口径 ====================

    @Test
    void seriesZeroFillsEveryDayAndUsesDeclaredCaliber() {
        seedJobs();
        seedAttempts();
        seedEvaluations();
        seedContacts();
        seedFollowups();

        Map<String, Object> newJobs = metricsService.series(FROM, TO, "newJobs");
        assertEquals("newJobs", newJobs.get("metric"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) newJobs.get("points");
        assertEquals(5, points.size(), "范围内每天都应有一个点（无数据补 0）");
        assertEquals(FROM, points.get(0).get("date"));
        assertEquals(0L, points.get(0).get("value"));
        assertEquals(2L, points.get(1).get("value"), "11-02 首次发现 B1/B2");
        assertEquals(0L, points.get(2).get("value"));
        assertEquals(2L, points.get(3).get("value"), "11-04 首次发现 B4/B5");

        Map<String, Object> textConfirmed = metricsService.series(FROM, TO, "textConfirmed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> confirmedPoints =
                (List<Map<String, Object>>) textConfirmed.get("points");
        assertEquals(1L, confirmedPoints.get(1).get("value"), "11-02 A1 确认");
        assertEquals(1L, confirmedPoints.get(2).get("value"), "11-03 A5 确认");

        Map<String, Object> rulePass = metricsService.series(FROM, TO, "rulePass");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> passPoints = (List<Map<String, Object>>) rulePass.get("points");
        assertEquals(1L, passPoints.get(1).get("value"));
        assertEquals(1L, passPoints.get(2).get("value"));

        Map<String, Object> replies = metricsService.series(FROM, TO, "replies");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> replyPoints = (List<Map<String, Object>>) replies.get("points");
        assertEquals(1L, replyPoints.get(1).get("value"));

        Map<String, Object> executed = metricsService.series(FROM, TO, "followupsExecuted");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedPoints = (List<Map<String, Object>>) executed.get("points");
        assertEquals(1L, executedPoints.get(1).get("value"));
    }

    // ==================== collection：按关键词 / 按日 ====================

    @Test
    void collectionSummarizesKeywordsAndDailySeries() {
        seedJobSources();

        Map<String, Object> response = metricsService.collection(FROM, TO);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keywords = (List<Map<String, Object>>) response.get("keywords");
        assertEquals(2, keywords.size(), "两个关键词");
        Map<String, Object> java = keywords.stream()
                .filter(item -> "Java".equals(item.get("keyword"))).findFirst().orElseThrow();
        assertEquals(3L, java.get("sourceRecords"));
        assertEquals(3L, java.get("uniqueJobs"));
        assertTrue(String.valueOf(java.get("firstDiscoveredAt")).startsWith("2026-11-02"));
        Map<String, Object> backend = keywords.stream()
                .filter(item -> "后端".equals(item.get("keyword"))).findFirst().orElseThrow();
        assertEquals(1L, backend.get("sourceRecords"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) response.get("daily");
        assertEquals(3, daily.size(), "只有有来源记录的日期出现（按日聚合）");
        assertTrue(daily.stream().anyMatch(row -> "2026-11-02".equals(row.get("date"))
                && Long.valueOf(2L).equals(row.get("sourceRecords"))));
        assertTrue(daily.stream().anyMatch(row -> "2026-11-03".equals(row.get("date"))
                && Long.valueOf(1L).equals(row.get("sourceRecords"))));
        assertTrue(daily.stream().anyMatch(row -> "2026-11-04".equals(row.get("date"))
                && Long.valueOf(1L).equals(row.get("sourceRecords"))));
    }

    // ==================== 非法参数 ====================

    @Test
    void invalidRangesAndMetricsAreRejected() {
        MetricsValidationException reverse = assertThrows(MetricsValidationException.class,
                () -> metricsService.overview(TO, FROM, null));
        assertEquals("INVALID_RANGE", reverse.getCode());
        assertTrue(reverse.getMessage().contains("from"));

        MetricsValidationException tooLong = assertThrows(MetricsValidationException.class,
                () -> metricsService.overview("2024-01-01", "2026-01-01", null));
        assertEquals("INVALID_RANGE", tooLong.getCode());
        assertTrue(tooLong.getMessage().contains("366"));

        MetricsValidationException badFormat = assertThrows(MetricsValidationException.class,
                () -> metricsService.series("2026/11/01", TO, "newJobs"));
        assertEquals("INVALID_RANGE", badFormat.getCode());

        MetricsValidationException invalidMetric = assertThrows(MetricsValidationException.class,
                () -> metricsService.series(FROM, TO, "bogus"));
        assertEquals("INVALID_METRIC", invalidMetric.getCode());
        assertTrue(invalidMetric.getMessage().contains("newJobs"));
        assertThrows(MetricsValidationException.class, () -> metricsService.series(FROM, TO, null));

        MetricsService.DateRange maxRange = metricsService.resolveRange("2025-01-01", "2026-01-01");
        assertEquals(366, (int) (maxRange.to().toEpochDay() - maxRange.from().toEpochDay()) + 1,
                "恰好 366 天应允许");
    }

    // ==================== 数据构造 ====================

    private void seedJobs() {
        insertBossData("ENC-B1", "USER-B1", "假公司1", "Java 开发", "未投递", "今日活跃",
                "2026-11-02 10:00:00", "2026-11-01 10:00:00");
        insertBossData("ENC-B2", "USER-B2", "假公司2", "后端开发", "未投递", "本周活跃",
                "2026-11-02 11:00:00", "2026-11-01 11:00:00");
        insertBossData("ENC-B3", "USER-B3", "假公司3", "测试开发", "未投递", null,
                null, "2026-11-02 12:00:00");
        insertBossData("ENC-B4", "USER-B4", "假公司4", "Java 架构师", "已投递", "今日活跃",
                "2026-11-04 09:00:00", "2026-11-01 12:00:00");
        insertBossData("ENC-B5", "USER-B5", "假公司5", "后端专家", "已投递", null,
                "2026-11-04 09:30:00", "2026-11-01 13:00:00");
    }

    private void seedAttempts() {
        insertAttempt("A1", "R1", "FIRST_CONTACT", "CONFIRMED", "CONFIRMED", "ENC-B5", "uid:U1",
                "2026-11-02 10:10:00", "2026-11-02 10:11:00");
        insertAttempt("A2", "R1", "FIRST_CONTACT", "FAILED", "FAILED", "ENC-B1", "uid:U2",
                "2026-11-02 10:20:00", "2026-11-02 10:21:00");
        insertAttempt("A3", "R1", "FIRST_CONTACT", "UNKNOWN", "SKIPPED", "ENC-B2", "uid:U3",
                "2026-11-03 10:00:00", "2026-11-03 10:01:00");
        insertAttempt("A4", "R1", "FIRST_CONTACT", "PENDING", "NOT_ATTEMPTED", "ENC-B3", "uid:U4",
                "2026-11-03 11:00:00", null);
        insertAttempt("A5", "R2", "FOLLOWUP", "CONFIRMED", "SKIPPED", "ENC-B1", "uid:U2",
                "2026-11-03 12:00:00", "2026-11-03 12:01:00");
        insertAttempt("A6", "R2", "FIRST_CONTACT", "CONFIRMED", "CONFIRMED", "ENC-B2", "uid:U3",
                "2026-09-01 10:00:00", "2026-09-01 10:01:00");
    }

    private void seedEvents() {
        insertEvent("A1", "R1", "CHAT_REQUESTED", "2026-11-02 10:09:00");
        insertEvent("A2", "R1", "CHAT_REQUESTED", "2026-11-02 10:19:00");
        insertEvent("A1", "R1", "TEXT_CONFIRMED", "2026-11-02 10:11:00");
        insertEvent("A6", "R2", "CHAT_REQUESTED", "2026-09-01 09:59:00");
    }

    private void seedEvaluations() {
        insertEvaluation("R1", "ENC1", "PASS", "MATCH", "2026-11-02 10:00:00");
        insertEvaluation("R1", "ENC1", "REJECT", "REJECT", "2026-11-02 10:05:00");
        insertEvaluation("R1", "ENC2", "PASS", null, "2026-11-02 10:06:00");
        insertEvaluation("R1", "ENC3", "REVIEW", "ERROR", "2026-11-03 10:00:00");
        insertEvaluation("R2", "ENC1", "PASS", "MATCH", "2026-11-03 11:00:00");
    }

    private void seedContacts() {
        insertContact("uid:U1", "REPLIED", 1, 0, "2026-11-02 10:10:00", "2026-11-02 11:00:00");
        insertContact("uid:U2", "INTERVIEW", 0, 0, "2026-11-02 12:00:00", "2026-11-03 12:00:00");
        insertContact("uid:U3", "UNKNOWN", 0, 0, null, null);
    }

    private void seedFollowups() {
        // 参数顺序：planId,status,decidedAt,executedAt,executedAttemptId,suggestedAt,createdAt,updatedAt
        insertFollowup("P1", "SUGGESTED", null, null, null, "2026-11-02 10:00:00",
                "2026-11-01 09:00:00", "2026-11-01 09:00:00");
        insertFollowup("P2", "APPROVED", "2026-11-02 10:05:00", null, null, "2026-10-01 09:00:00",
                "2026-10-01 09:00:00", "2026-11-02 10:05:00");
        insertFollowup("P3", "APPROVED", "2026-11-03 10:05:00", null, null, "2026-10-02 09:00:00",
                "2026-10-02 09:00:00", "2026-11-03 10:05:00");
        insertFollowup("P4", "REJECTED", "2026-11-02 11:00:00", null, null, "2026-10-01 09:00:00",
                "2026-10-01 09:00:00", "2026-11-02 11:00:00");
        insertFollowup("P5", "CANCELLED", null, null, null, "2026-10-01 09:00:00",
                "2026-10-01 09:00:00", "2026-11-02 12:00:00");
        insertFollowup("P6", "EXECUTED", null, "2026-11-02 13:00:00", "attempt-p6",
                "2026-11-01 10:00:00", "2026-11-01 09:00:00", "2026-11-02 13:00:00");
    }

    private void seedBudget() {
        execute(dataSource, "INSERT INTO daily_budget(account, budget_date, action_type, budget_limit, " +
                "consumed, reserved, uncertain, updated_at) VALUES" +
                "('local', ?, 'FIRST_CONTACT', 100, 10, 5, 2, '2026-11-04 10:00:00')," +
                "('local', ?, 'FOLLOWUP', 20, 3, 1, 0, '2026-11-04 10:00:00')", TODAY, TODAY);
    }

    private void seedJobSources() {
        insertJobSource("ENC-B1", "Java", "2026-11-02 10:00:00");
        insertJobSource("ENC-B1", "后端", "2026-11-03 10:00:00");
        insertJobSource("ENC-B2", "Java", "2026-11-02 11:00:00");
        insertJobSource("ENC-B3", "Java", "2026-11-04 09:00:00");
    }

    // ==================== SQL 工具 ====================

    private void insertBossData(String encryptId, String encryptUserId, String company, String jobName,
                                String deliveryStatus, String hrActiveStatus,
                                String firstSeenAt, String createdAt) {
        execute(dataSource, "INSERT INTO boss_data(encrypt_id, encrypt_user_id, company_name, job_name, " +
                        "delivery_status, hr_active_status, created_at, updated_at, first_seen_at, " +
                        "last_detail_at, snapshot_hash) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                        "NULL, NULL)",
                encryptId, encryptUserId, company, jobName, deliveryStatus, hrActiveStatus,
                createdAt, createdAt, firstSeenAt);
    }

    private void insertAttempt(String attemptId, String runId, String kind, String textStatus,
                               String imageStatus, String encryptId, String contactKey,
                               String startedAt, String finishedAt) {
        execute(dataSource, "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, encrypt_id, " +
                        "contact_key, text_status, image_status, started_at, finished_at, created_at) " +
                        "VALUES(?, ?, 'local', ?, ?, ?, ?, ?, ?, ?, ?)",
                attemptId, runId, kind, encryptId, contactKey, textStatus, imageStatus,
                startedAt, finishedAt, startedAt);
    }

    private void insertEvent(String attemptId, String runId, String eventType, String occurredAt) {
        execute(dataSource, "INSERT INTO delivery_event(attempt_id, run_id, event_type, " +
                        "occurred_at) VALUES(?, ?, ?, ?)",
                attemptId, runId, eventType, occurredAt);
    }

    private void insertEvaluation(String runId, String encryptId, String ruleDecision,
                                  String aiDecision, String createdAt) {
        execute(dataSource, "INSERT INTO job_evaluation(run_id, encrypt_id, rule_decision, ai_decision, " +
                        "created_at) VALUES(?, ?, ?, ?, ?)",
                runId, encryptId, ruleDecision, aiDecision, createdAt);
    }

    private void insertContact(String contactKey, String replyState, int takeover, int stop,
                               String lastContactAt, String lastReplyAt) {
        execute(dataSource, "INSERT INTO contact_state(account, platform, contact_key, reply_state, " +
                        "manual_takeover, stop_contact, last_contact_at, last_reply_at, version, " +
                        "created_at, updated_at) VALUES('local','boss', ?, ?, ?, ?, ?, ?, 1, " +
                        "'2026-11-01 09:00:00', '2026-11-01 09:00:00')",
                contactKey, replyState, takeover, stop, lastContactAt, lastReplyAt);
    }

    private void insertFollowup(String planId, String status, String decidedAt, String executedAt,
                                String executedAttemptId, String suggestedAt,
                                String createdAt, String updatedAt) {
        execute(dataSource, "INSERT INTO followup_plan(plan_id, account, platform, encrypt_id, contact_key, " +
                        "plan_type, status, reason, version, suggested_at, decided_at, executed_at, " +
                        "executed_attempt_id, created_at, updated_at) VALUES(?, 'local', 'boss', 'ENC-1', " +
                        "'uid:U1', 'FOLLOWUP', ?, '原因', 1, ?, ?, ?, ?, ?, ?)",
                planId, status, suggestedAt, decidedAt, executedAt, executedAttemptId,
                createdAt, updatedAt);
    }

    private void insertJobSource(String encryptId, String keyword, String discoveredAt) {
        execute(dataSource, "INSERT INTO job_source(account, platform, encrypt_id, keyword, city_code, " +
                        "discovered_at, created_at) VALUES('local','boss', ?, ?, '101020100', ?, ?)",
                encryptId, keyword, discoveredAt, discoveredAt);
    }

    @SuppressWarnings("unused")
    private static boolean noop() {
        return true;
    }
}
