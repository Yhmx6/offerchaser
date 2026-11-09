package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.offerchaser.application.persistence.PersistenceTestSupport.ACCOUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 采集来源仓库测试：幂等记录、同岗位多关键词、时间范围统计、按关键词/按日汇总。
 */
class JobSourceRepositoryTest {

    private static final String PLATFORM = "boss";

    @TempDir
    Path directory;

    private JobSourceRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = PersistenceTestSupport.migratedDataSource(directory);
        repository = new JobSourceRepository(dataSource);
    }

    @Test
    void recordIfAbsentIsIdempotent() {
        assertTrue(repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-1", "Java", "101010100",
                "2026-11-01 10:00:00", "snap-1"), "首次记录应返回 true");
        assertFalse(repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-1", "Java", "101010100",
                "2026-11-01 10:00:00", "snap-1"), "重复采集同岗位同关键词应忽略");

        assertEquals(1, repository.countRecords(ACCOUNT, null, null));
        assertEquals(1, repository.countUniqueJobs(ACCOUNT, null, null));
    }

    @Test
    void sameJobWithDifferentKeywordsCreatesTwoSourceRowsButOneUniqueJob() {
        assertTrue(repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-1", "Java", "101010100",
                "2026-11-01 10:00:00", null));
        assertTrue(repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-1", "后端", "101010100",
                "2026-11-02 10:00:00", null));

        assertEquals(2, repository.countRecords(ACCOUNT, null, null), "同岗位多关键词 → 两行来源");
        assertEquals(1, repository.countUniqueJobs(ACCOUNT, null, null), "岗位不重复");
        // 不同账号互不影响
        assertTrue(repository.recordIfAbsent("other-account", PLATFORM, "enc-1", "Java", null,
                "2026-11-01 10:00:00", null));
        assertEquals(2, repository.countRecords(ACCOUNT, null, null));
    }

    @Test
    void countsUseDiscoveredAtRange() {
        repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-1", "Java", null, "2026-11-01 10:00:00", null);
        repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-2", "Java", null, "2026-11-03 10:00:00", null);
        repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-3", "后端", null, "2026-11-05 10:00:00", null);

        assertEquals(3, repository.countRecords(ACCOUNT, null, null));
        assertEquals(3, repository.countUniqueJobs(ACCOUNT, null, null));
        // 日期格式（yyyy-MM-dd）按当天 00:00:00 ~ 23:59:59 归一化
        assertEquals(1, repository.countRecords(ACCOUNT, "2026-11-02", "2026-11-04"));
        assertEquals(1, repository.countUniqueJobs(ACCOUNT, "2026-11-02", "2026-11-04"));
        assertEquals(2, repository.countRecords(ACCOUNT, "2026-11-01 00:00:00", "2026-11-03 23:59:59"));
        assertEquals(0, repository.countRecords(ACCOUNT, "2026-12-01", "2026-12-31"));
    }

    @Test
    void summaryByKeywordAggregates() {
        repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-1", "Java", null, "2026-11-01 10:00:00", null);
        repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-2", "Java", null, "2026-11-03 10:00:00", null);
        repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-1", "后端", null, "2026-11-02 10:00:00", null);

        List<Map<String, Object>> rows = repository.summaryByKeyword(ACCOUNT, "2026-11-01", "2026-11-07");
        assertEquals(2, rows.size(), "两个关键词");
        Map<String, Object> java = rows.get(0);
        assertEquals("Java", java.get("keyword"));
        assertEquals(2L, java.get("sourceRecords"));
        assertEquals(2L, java.get("uniqueJobs"));
        assertEquals("2026-11-01 10:00:00", java.get("firstDiscoveredAt"));
        assertEquals("2026-11-03 10:00:00", java.get("lastDiscoveredAt"));
        assertEquals("后端", rows.get(1).get("keyword"));
        assertEquals(1L, rows.get(1).get("sourceRecords"));

        assertTrue(repository.summaryByKeyword(ACCOUNT, "2026-12-01", "2026-12-31").isEmpty());
    }

    @Test
    void dailySeriesGroupsByDayAndDedupesJobs() {
        repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-1", "Java", null, "2026-11-01 10:00:00", null);
        repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-1", "后端", null, "2026-11-01 12:00:00", null);
        repository.recordIfAbsent(ACCOUNT, PLATFORM, "enc-2", "Java", null, "2026-11-02 10:00:00", null);

        List<Map<String, Object>> rows = repository.dailySeries(ACCOUNT, "2026-11-01", "2026-11-03");
        assertEquals(2, rows.size());
        Map<String, Object> first = rows.get(0);
        assertEquals("2026-11-01", first.get("date"));
        assertEquals(1L, first.get("newJobs"), "同一天同岗位多关键词只算一个岗位");
        assertEquals(2L, first.get("sourceRecords"));
        assertEquals(1L, rows.get(1).get("newJobs"));
        assertEquals(1L, rows.get(1).get("sourceRecords"));
    }

    @Test
    void invalidInputIsRejectedWithoutInsert() {
        assertFalse(repository.recordIfAbsent(null, PLATFORM, "enc-1", "Java", null, null, null));
        assertFalse(repository.recordIfAbsent(ACCOUNT, null, "enc-1", "Java", null, null, null));
        assertFalse(repository.recordIfAbsent(ACCOUNT, PLATFORM, "  ", "Java", null, null, null));
        assertEquals(0, repository.countRecords(ACCOUNT, null, null));
        assertNull(repository.summaryByKeyword(ACCOUNT, null, null).stream().findFirst().orElse(null));
    }
}
