package com.offerchaser.application.persistence;

import com.offerchaser.application.entity.BossJobDataEntity;
import com.offerchaser.application.mapper.BossJobDataMapper;
import com.offerchaser.application.service.BossService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.offerchaser.application.persistence.PersistenceTestSupport.executeScript;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalar;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BossService 持久化相关改动的回归测试：
 * 安全的列补全（不重建表）、reloadBossData 只统计、upsertBossJob 的插入/刷新/终态保护。
 */
class BossServicePersistenceTest {

    @TempDir
    Path directory;

    @Test
    void ensureBossDataColumnOrderAddsMissingColumnsWithoutRebuildingTable() {
        SQLiteDataSource dataSource = PersistenceTestSupport.dataSource(directory.resolve("boss-data.db"));
        executeScript(dataSource,
                "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT)",
                "INSERT INTO boss_data(encrypt_id) VALUES('job-1')");
        BossService service = new BossService(null, null, null, null, null, dataSource, null);

        service.ensureBossDataColumnOrder();
        service.ensureBossDataColumnOrder();

        for (String column : List.of("encrypt_user_id", "job_name", "hr_active_status",
                "delivery_status", "job_description", "updated_at")) {
            assertEquals(1, scalar(dataSource,
                    "SELECT COUNT(*) FROM pragma_table_info('boss_data') WHERE name = ?", column),
                    "缺少列：" + column);
        }
        // 绝不重建表：没有中间表，原有数据保留，主键定义仍在
        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'boss_data_new'"));
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM boss_data"));
        assertEquals("job-1", scalarString(dataSource, "SELECT encrypt_id FROM boss_data LIMIT 1"));
        assertEquals("id", scalarString(dataSource,
                "SELECT name FROM pragma_table_info('boss_data') WHERE pk = 1 LIMIT 1"));
    }

    @Test
    void ensureBossDataColumnOrderIgnoresMissingTable() {
        SQLiteDataSource dataSource = PersistenceTestSupport.dataSource(directory.resolve("empty.db"));
        BossService service = new BossService(null, null, null, null, null, dataSource, null);

        service.ensureBossDataColumnOrder();

        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'boss_data'"));
    }

    @Test
    void upsertBossJobInsertsNewSnapshotWithDefaultDeliveryStatus() {
        BossJobDataMapper mapper = mock(BossJobDataMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        BossService service = new BossService(null, null, null, null, mapper, null, null);

        BossJobDataEntity snapshot = new BossJobDataEntity();
        snapshot.setEncryptId("job-1");
        snapshot.setEncryptUserId("user-1");
        snapshot.setJobName("Java开发");
        snapshot.setSalary("20-40K");
        service.upsertBossJob(snapshot);

        ArgumentCaptor<BossJobDataEntity> captor = ArgumentCaptor.forClass(BossJobDataEntity.class);
        verify(mapper).insert(captor.capture());
        BossJobDataEntity inserted = captor.getValue();
        assertEquals("job-1", inserted.getEncryptId());
        assertEquals("user-1", inserted.getEncryptUserId());
        assertEquals("Java开发", inserted.getJobName());
        assertEquals("未投递", inserted.getDeliveryStatus());
        assertNotNull(inserted.getCreatedAt());
        assertNotNull(inserted.getUpdatedAt());
        verify(mapper, never()).updateById(any(BossJobDataEntity.class));

        // encryptId 为空时不落库
        service.upsertBossJob(new BossJobDataEntity());
        verify(mapper, times(1)).insert(any(BossJobDataEntity.class));
    }

    @Test
    void upsertBossJobRefreshesSnapshotButKeepsTerminalDeliveryStatus() {
        BossJobDataMapper mapper = mock(BossJobDataMapper.class);
        BossJobDataEntity existing = new BossJobDataEntity();
        existing.setId(1L);
        existing.setEncryptId("job-1");
        existing.setEncryptUserId("user-1");
        existing.setJobName("旧岗位名");
        existing.setSalary("10-15K");
        existing.setDeliveryStatus("已投递");
        when(mapper.selectOne(any())).thenReturn(existing);
        BossService service = new BossService(null, null, null, null, mapper, null, null);

        BossJobDataEntity snapshot = new BossJobDataEntity();
        snapshot.setEncryptId("job-1");
        snapshot.setEncryptUserId("user-1");
        snapshot.setJobName("新岗位名");
        snapshot.setSalary("20-40K");
        snapshot.setCompanyName("某公司");
        snapshot.setHrActiveStatus("今日活跃");
        snapshot.setDeliveryStatus("已过滤");
        service.upsertBossJob(snapshot);

        ArgumentCaptor<BossJobDataEntity> captor = ArgumentCaptor.forClass(BossJobDataEntity.class);
        verify(mapper).updateById(captor.capture());
        BossJobDataEntity updated = captor.getValue();
        assertEquals("新岗位名", updated.getJobName(), "快照字段应刷新");
        assertEquals("20-40K", updated.getSalary());
        assertEquals("某公司", updated.getCompanyName());
        assertEquals("今日活跃", updated.getHrActiveStatus());
        assertEquals("已投递", updated.getDeliveryStatus(), "终态不应被快照回退成 已过滤");
        assertNotNull(updated.getUpdatedAt());
        verify(mapper, never()).insert(any(BossJobDataEntity.class));
    }

    @Test
    void upsertBossJobAllowsTerminalStatusUpgrade() {
        BossJobDataMapper mapper = mock(BossJobDataMapper.class);
        BossJobDataEntity existing = new BossJobDataEntity();
        existing.setId(1L);
        existing.setEncryptId("job-1");
        existing.setEncryptUserId("user-1");
        existing.setDeliveryStatus("未投递");
        when(mapper.selectOne(any())).thenReturn(existing);
        BossService service = new BossService(null, null, null, null, mapper, null, null);

        BossJobDataEntity snapshot = new BossJobDataEntity();
        snapshot.setEncryptId("job-1");
        snapshot.setEncryptUserId("user-1");
        snapshot.setDeliveryStatus("状态未知");
        service.upsertBossJob(snapshot);

        assertEquals("状态未知", existing.getDeliveryStatus(), "非终态可以被更新为终态");
    }

    @Test
    void reloadBossDataOnlyCountsRows() {
        SQLiteDataSource dataSource = PersistenceTestSupport.dataSource(directory.resolve("reload.db"));
        executeScript(dataSource,
                "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT)",
                "INSERT INTO boss_data(encrypt_id) VALUES('job-1')",
                "INSERT INTO boss_data(encrypt_id) VALUES('job-2')");
        BossService service = new BossService(null, null, null, null, null, dataSource, null);

        Map<String, Object> result = service.reloadBossData();

        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals(2L, result.get("total"));
    }

    @Test
    void reloadBossDataReportsFailureWhenTableMissing() {
        SQLiteDataSource dataSource = PersistenceTestSupport.dataSource(directory.resolve("no-table.db"));
        BossService service = new BossService(null, null, null, null, null, dataSource, null);

        Map<String, Object> result = service.reloadBossData();

        assertEquals(Boolean.FALSE, result.get("success"));
        assertTrue(String.valueOf(result.get("message")).contains("刷新失败"));
    }
}


