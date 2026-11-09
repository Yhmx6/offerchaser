package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2 规则配置版本仓库测试：空库、插入、latest/list 顺序、版本唯一冲突与参数校验。
 */
class RuleConfigRepositoryTest {

    @TempDir
    Path directory;

    private RuleConfigRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = PersistenceTestSupport.migratedDataSource(directory);
        repository = new RuleConfigRepository(dataSource);
    }

    @Test
    void emptyDatabaseReturnsNullLatestAndEmptyList() {
        assertNull(repository.latest());
        assertTrue(repository.list(20).isEmpty());
        assertNull(repository.findByVersion(1));
    }

    @Test
    void insertLatestAndListOrder() {
        RuleConfigRepository.RuleConfigVersion v1 =
                repository.insert(1, "{\"highestDegree\":\"本科\"}", "hash-1", "初始版本");
        RuleConfigRepository.RuleConfigVersion v2 =
                repository.insert(2, "{\"highestDegree\":\"硕士\"}", "hash-2", null);

        assertTrue(v1.id() > 0);
        assertEquals(1, v1.version());
        assertEquals("{\"highestDegree\":\"本科\"}", v1.rulesJson());
        assertEquals("hash-1", v1.sourceHash());
        assertEquals("初始版本", v1.note());
        assertNotNull(v1.createdAt());
        assertNull(v1.liveHash(), "旧 4 参重载默认 liveHash=null");
        assertTrue(v2.id() > v1.id());

        assertEquals(v2, repository.latest(), "latest 取 version 最大的记录");
        List<RuleConfigRepository.RuleConfigVersion> versions = repository.list(10);
        assertEquals(List.of(2, 1), versions.stream().map(RuleConfigRepository.RuleConfigVersion::version).toList(),
                "list 按 version DESC");
        assertEquals(1, repository.list(1).size());
        assertEquals(2, repository.list(0).size(), "limit<=0 使用默认值");
        assertEquals(v1, repository.findByVersion(1));
    }

    @Test
    void liveHashIsPersistedAndReturnedByLatestListAndFindByVersion() {
        RuleConfigRepository.RuleConfigVersion v1 = repository.insert(
                1, "{\"highestDegree\":\"本科\"}", "hash-1", "显式保存", "live-hash-1");
        assertEquals("live-hash-1", v1.liveHash(), "insert 返回值带 liveHash");

        RuleConfigRepository.RuleConfigVersion latest = repository.latest();
        assertNotNull(latest);
        assertEquals(1, latest.version());
        assertEquals("live-hash-1", latest.liveHash(), "latest 返回 liveHash");

        List<RuleConfigRepository.RuleConfigVersion> versions = repository.list(10);
        assertEquals(1, versions.size());
        assertEquals("live-hash-1", versions.get(0).liveHash(), "list 返回 liveHash");

        RuleConfigRepository.RuleConfigVersion byVersion = repository.findByVersion(1);
        assertNotNull(byVersion);
        assertEquals("live-hash-1", byVersion.liveHash(), "findByVersion 返回 liveHash");

        // 顶层契约接口同样能读到 liveHash
        RuleConfigVersion topLevel = repository.latest();
        assertNotNull(topLevel);
        assertEquals("live-hash-1", topLevel.liveHash());

        // 新版本不传 liveHash → 该行 liveHash 为 null，但旧版本值不受影响
        RuleConfigRepository.RuleConfigVersion v2 = repository.insert(2, "{}", "hash-2", "旧调用", null);
        assertNull(v2.liveHash());
        assertEquals("live-hash-1", repository.findByVersion(1).liveHash());
    }

    @Test
    void duplicateVersionIsRejectedWithVersionInMessage() {
        repository.insert(1, "{}", "hash", null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.insert(1, "{}", "hash-2", "并发保存"));
        assertTrue(error.getMessage().contains("version=1"), "异常信息需带 version 便于映射 409：" + error.getMessage());
        assertEquals(1, repository.list(10).size(), "冲突不得覆盖/新增记录");
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repository.insert(0, "{}", null, null));
        assertThrows(IllegalArgumentException.class, () -> repository.insert(-1, "{}", null, null));
        assertThrows(IllegalArgumentException.class, () -> repository.insert(1, "  ", null, null));
        assertThrows(IllegalArgumentException.class, () -> repository.insert(1, null, null, null));
        assertEquals(0, repository.list(10).size());
    }

    @Test
    void nestedRecordIsCompatibleWithContractTopLevelType() {
        repository.insert(1, "{}", "hash-1", null);
        // 契约按顶层 RuleConfigVersion 类型使用；实际返回嵌套 record，接口声明保证可赋值/遍历
        RuleConfigVersion latest = repository.latest();
        assertNotNull(latest);
        assertEquals(1, latest.version());
        int count = 0;
        for (RuleConfigVersion version : repository.list(10)) {
            assertNotNull(version);
            count++;
        }
        assertEquals(1, count);
    }
}
