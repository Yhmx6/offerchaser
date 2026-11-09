package com.offerchaser.application.metrics;

import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.JobSourceRepository;
import com.offerchaser.application.persistence.MetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 B B4：正确统计看板服务。
 * <p>
 * 所有数字都有明确口径（overview 的 definitions 原样返回给前端展示），
 * 未知、旧历史（legacy）单独列示，不混入"文字确认成功"；查询全部走数据库聚合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsService {

    public static final List<String> SERIES_METRICS = List.of(
            "newJobs", "rulePass", "ruleReject", "textConfirmed",
            "firstContactAttempts", "replies", "followupsExecuted");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MetricsRepository metricsRepository;
    private final JobSourceRepository jobSourceRepository;

    /** 可选注入：让看板与今日页使用同一份预算口径；单元测试可缺省。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BudgetService budgetService;

    @Value("${offerchaser.boss.budget-account:local}")
    private String account = "local";

    @Value("${offerchaser.boss.budget-timezone:Asia/Shanghai}")
    private String timezone = "Asia/Shanghai";

    /** 查询范围（含端点）。 */
    public record DateRange(LocalDate from, LocalDate to) {
        String fromIso() { return from.format(DATE); }
        String toIso() { return to.format(DATE); }
        String fromSql() { return from.format(DATE) + " 00:00:00"; }
        String toSql() { return to.format(DATE) + " 23:59:59"; }
        int days() { return (int) (to.toEpochDay() - from.toEpochDay()) + 1; }
    }

    public DateRange resolveRange(String from, String to) {
        LocalDate today = LocalDate.now(ZoneId.of(timezone));
        LocalDate fromDate;
        LocalDate toDate;
        try {
            fromDate = (from == null || from.isBlank()) ? today.minusDays(6) : LocalDate.parse(from.trim(), DATE);
            toDate = (to == null || to.isBlank()) ? today : LocalDate.parse(to.trim(), DATE);
        } catch (DateTimeParseException e) {
            throw new MetricsValidationException("INVALID_RANGE", "from/to 必须是 yyyy-MM-dd 格式");
        }
        if (fromDate.isAfter(toDate)) {
            throw new MetricsValidationException("INVALID_RANGE", "from 不能晚于 to");
        }
        if ((toDate.toEpochDay() - fromDate.toEpochDay()) + 1 > 366) {
            throw new MetricsValidationException("INVALID_RANGE", "时间跨度不能超过 366 天");
        }
        return new DateRange(fromDate, toDate);
    }

    public Map<String, Object> overview(String from, String to, String runId) {
        DateRange range = resolveRange(from, to);
        String fromSql = range.fromSql();
        String toSql = range.toSql();

        Map<String, Long> newJobs = safe(metricsRepository.countNewJobs(account, fromSql, toSql));
        Map<String, Long> evaluations = safe(metricsRepository.countLatestEvaluations(account, fromSql, toSql, runId));
        Map<String, Long> attempts = safe(metricsRepository.countAttempts(account, fromSql, toSql));
        Map<String, Long> events = safe(metricsRepository.countEvents(account, fromSql, toSql));
        Map<String, Long> contacts = safe(metricsRepository.countContacts(account));
        Map<String, Long> followups = safe(metricsRepository.countFollowups(account, fromSql, toSql));
        Map<String, Long> budget = safe(metricsRepository.countBudget(account, LocalDate.now(ZoneId.of(timezone)).format(DATE)));
        Map<String, Long> hrActive = safe(metricsRepository.countHrActive(account, 30));
        Map<String, Long> collection = new LinkedHashMap<>();
        try {
            collection.put("sourceRecords", (long) jobSourceRepository.countRecords(account, fromSql, toSql));
            collection.put("uniqueJobs", (long) jobSourceRepository.countUniqueJobs(account, fromSql, toSql));
            collection.put("keywords", (long) jobSourceRepository.summaryByKeyword(account, fromSql, toSql).size());
        } catch (Exception e) {
            log.warn("采集来源统计失败（按 0 处理）：{}", e.getMessage());
            collection.put("sourceRecords", 0L);
            collection.put("uniqueJobs", 0L);
            collection.put("keywords", 0L);
        }
        long legacyDelivered = 0;
        try {
            legacyDelivered = Math.max(0, metricsRepository.countLegacyDelivered(account));
        } catch (Exception e) {
            log.warn("legacy 统计失败（按 0 处理）：{}", e.getMessage());
        }

        long rulePass = get(evaluations, "rulePass");
        long ruleReject = get(evaluations, "ruleReject");
        long ruleReview = get(evaluations, "ruleReview");
        long ruleTotal = rulePass + ruleReject + ruleReview;
        long aiMatch = get(evaluations, "aiMatch");
        long aiReject = get(evaluations, "aiReject");
        long aiError = get(evaluations, "aiError");
        long aiUnset = get(evaluations, "aiUnset");

        long contacted = get(contacts, "contacted");
        long replied = get(contacts, "replied");
        long interview = get(contacts, "interview");
        long textUnknown = get(attempts, "textUnknown") + get(attempts, "textPending");
        long imageTotal = get(attempts, "imageConfirmed") + get(attempts, "imageFailed")
                + get(attempts, "imageUnknown") + get(attempts, "imageSkipped");

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("newJobs", get(newJobs, "newJobs"));
        metrics.put("legacyNewJobs", get(newJobs, "legacyNewJobs"));
        metrics.put("rule", linked(
                "pass", rulePass,
                "reject", ruleReject,
                "review", ruleReview,
                "total", ruleTotal,
                "passRate", ratio(rulePass, ruleTotal)));
        metrics.put("ai", linked(
                "match", aiMatch,
                "reject", aiReject,
                "error", aiError,
                "unset", aiUnset,
                "total", aiMatch + aiReject + aiError));
        metrics.put("firstContact", linked(
                "attempts", get(attempts, "firstAttempt"),
                "chatRequested", get(events, "chatRequested"),
                "textConfirmed", get(attempts, "firstTextConfirmed"),
                "textFailed", get(attempts, "firstTextFailed"),
                "textUnknown", get(attempts, "firstTextUnknown"),
                "textPending", get(attempts, "firstTextPending")));
        metrics.put("textConfirmed", get(attempts, "textConfirmed"));
        metrics.put("image", linked(
                "confirmed", get(attempts, "imageConfirmed"),
                "failed", get(attempts, "imageFailed"),
                "unknown", get(attempts, "imageUnknown"),
                "skipped", get(attempts, "imageSkipped"),
                "total", imageTotal));
        metrics.put("replies", linked(
                "contacts", get(contacts, "contacts"),
                "contacted", contacted,
                "replied", replied,
                "interview", interview,
                "replyRate", ratio(replied + interview, contacted),
                "manualTakeover", get(contacts, "manualTakeover"),
                "stopContact", get(contacts, "stopContact")));
        metrics.put("followups", linked(
                "suggested", get(followups, "suggested"),
                "approved", get(followups, "approved"),
                "rejected", get(followups, "rejected"),
                "executed", get(followups, "executed"),
                "cancelled", get(followups, "cancelled"),
                "approvedPending", get(followups, "approvedPending")));
        metrics.put("budget", budgetView(budget));
        metrics.put("collection", linked(
                "sourceRecords", get(collection, "sourceRecords"),
                "keywords", get(collection, "keywords"),
                "uniqueJobs", get(collection, "uniqueJobs")));
        metrics.put("hrActive", hrActive);
        metrics.put("legacy", linked("deliveredUnverified", legacyDelivered));

        Map<String, Object> dataQuality = new LinkedHashMap<>();
        dataQuality.put("unknownText", textUnknown);
        dataQuality.put("legacyDelivered", legacyDelivered);
        dataQuality.put("firstSeenMissing", get(newJobs, "legacyNewJobs"));
        List<String> notes = new ArrayList<>();
        if (textUnknown > 0) {
            notes.add("文字发送结果未知的尝试共 " + textUnknown + " 条，单独统计，不计入文字确认成功");
        }
        if (legacyDelivered > 0) {
            notes.add("旧「已投递」记录 " + legacyDelivered + " 条未关联 TEXT_CONFIRMED 事件，标记为 legacy/unverified");
        }
        if (get(newJobs, "legacyNewJobs") > 0) {
            notes.add("旧记录缺少 first_seen_at：" + get(newJobs, "legacyNewJobs") + " 条按 created_at 单独计入 legacyNewJobs");
        }
        if (notes.isEmpty()) {
            notes.add("当前范围内未发现未知/旧历史口径差异");
        }
        dataQuality.put("notes", notes);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timezone", timezone);
        Map<String, Object> rangeView = new LinkedHashMap<>();
        rangeView.put("from", range.fromIso());
        rangeView.put("to", range.toIso());
        rangeView.put("days", range.days());
        response.put("range", rangeView);
        response.put("metrics", metrics);
        response.put("dataQuality", dataQuality);
        response.put("definitions", definitions());
        response.put("links", links(range));
        return response;
    }

    public Map<String, Object> series(String from, String to, String metric) {
        DateRange range = resolveRange(from, to);
        String normalized = metric == null ? "" : metric.trim();
        if (!SERIES_METRICS.contains(normalized)) {
            throw new MetricsValidationException("INVALID_METRIC",
                    "metric 必须是 " + String.join("/", SERIES_METRICS) + " 之一");
        }
        Map<String, Long> values = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : metricsRepository.series(account, normalized, range.fromSql(), range.toSql())) {
                String date = string(row.get("date"));
                if (date != null) {
                    values.put(date, longValue(row.get("value")));
                }
            }
        } catch (Exception e) {
            log.warn("读取趋势序列失败（返回全 0）：{}", e.getMessage());
        }
        List<Map<String, Object>> points = new ArrayList<>();
        LocalDate cursor = range.from();
        while (!cursor.isAfter(range.to())) {
            String date = cursor.format(DATE);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date);
            point.put("value", values.getOrDefault(date, 0L));
            points.add(point);
            cursor = cursor.plusDays(1);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metric", normalized);
        response.put("timezone", timezone);
        response.put("points", points);
        return response;
    }

    public Map<String, Object> collection(String from, String to) {
        DateRange range = resolveRange(from, to);
        List<Map<String, Object>> keywords = new ArrayList<>();
        List<Map<String, Object>> daily = new ArrayList<>();
        try {
            for (Map<String, Object> row : jobSourceRepository.summaryByKeyword(account, range.fromSql(), range.toSql())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("keyword", string(row.get("keyword")));
                item.put("sourceRecords", longValue(row.get("sourceRecords")));
                item.put("uniqueJobs", longValue(row.get("uniqueJobs")));
                item.put("firstDiscoveredAt", string(row.get("firstDiscoveredAt")));
                item.put("lastDiscoveredAt", string(row.get("lastDiscoveredAt")));
                keywords.add(item);
            }
            for (Map<String, Object> row : jobSourceRepository.dailySeries(account, range.fromSql(), range.toSql())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", string(row.get("date")));
                item.put("newJobs", longValue(row.get("newJobs")));
                item.put("sourceRecords", longValue(row.get("sourceRecords")));
                daily.add(item);
            }
        } catch (Exception e) {
            log.warn("读取采集汇总失败（返回空数组）：{}", e.getMessage());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timezone", timezone);
        response.put("range", Map.of("from", range.fromIso(), "to", range.toIso(), "days", range.days()));
        response.put("keywords", keywords);
        response.put("daily", daily);
        return response;
    }

    // ==================== 定义 / 链接 ====================

    /** 真实运行时优先用 BudgetService.todaySummary，避免无预算行时显示 0/-1；测试可退回 repository 聚合。 */
    private Map<String, Object> budgetView(Map<String, Long> fallback) {
        if (budgetService != null) {
            try {
                Map<String, Object> summary = budgetService.todaySummary();
                Object first = summary.get("firstContact");
                if (first instanceof Map<?, ?> firstMap) {
                    return linked(
                            "date", summary.get("date"),
                            "limit", firstMap.get("limit"),
                            "consumed", firstMap.get("consumed"),
                            "reserved", firstMap.get("reserved"),
                            "remaining", firstMap.get("remaining"),
                            "uncertain", firstMap.get("uncertain"));
                }
            } catch (Exception e) {
                log.warn("读取预算口径失败，退回数据库聚合：{}", e.getMessage());
            }
        }
        return linked(
                "date", LocalDate.now(ZoneId.of(timezone)).format(DATE),
                "limit", get(fallback, "limit"),
                "consumed", get(fallback, "consumed"),
                "reserved", get(fallback, "reserved"),
                "remaining", get(fallback, "remaining"),
                "uncertain", get(fallback, "uncertain"));
    }

    private Map<String, String> definitions() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put("newJobs", "首次发现时间（first_seen_at）在范围内的去重岗位；旧记录缺 first_seen_at 的单独计入 legacyNewJobs");
        defs.put("rule", "每个 (run_id, encrypt_id) 最新一条评估的规则结论；通过率=通过/(通过+拒绝+待复核)");
        defs.put("ai", "同规则口径，按 ai_decision 统计；未执行 AI/无结果的计入 unset，不进分母");
        defs.put("firstContact", "首次沟通动作：delivery_attempt 中 kind=FIRST_CONTACT 的尝试；chatRequested 为点击沟通入口事件数（外部动作证据）");
        defs.put("textConfirmed", "文字发送确认成功：delivery_attempt.text_status=CONFIRMED 的唯一尝试（按完成时间落范围），不含旧「已投递」");
        defs.put("image", "图片步骤独立计数/独立分母：成功、失败、未知、跳过（未配置/基线不可用等）");
        defs.put("replies", "人工标记的联系人回复状态；回复率=(已回复+已面试)/已有最近联系时间的联系人数");
        defs.put("followups", "跟进待办状态：建议/已批准/拒绝/已执行/已取消；approvedPending 为当前仍待执行的批准数");
        defs.put("budget", "今日首次沟通预算（daily_budget + budget_reservation 对账），未知结果单独占用");
        defs.put("collection", "采集来源关联：同岗位多关键词只增加来源行，不重复岗位");
        defs.put("legacy", "旧「已投递」记录如果没有 TEXT_CONFIRMED 证据，标记 legacy/unverified，不伪造成功");
        return defs;
    }

    private Map<String, String> links(DateRange range) {
        String from = range.fromIso();
        String to = range.toIso();
        Map<String, String> links = new LinkedHashMap<>();
        links.put("evaluations", "/boss/evaluations?from=" + from + "&to=" + to);
        links.put("attempts", "/boss/attempts?from=" + from + "&to=" + to);
        links.put("contacts", "/boss/contacts");
        links.put("followups", "/boss/followups");
        links.put("collection", "/boss/analysis#collection");
        return links;
    }

    // ==================== 工具 ====================

    private static Map<String, Long> safe(Map<String, Long> map) {
        return map == null ? Map.of() : map;
    }

    private static long get(Map<String, Long> map, String key) {
        Long value = map.get(key);
        return value == null ? 0L : value;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignore) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }

    private static Map<String, Object> linked(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
