package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.offerchaser.application.persistence.PersistenceTestSupport.execute;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5 备份门禁测试：pre-upgrade/pre-run 目录分离与保留、旧扁平兼容、完整性校验、
 * 备份列表、回滚提示与数据库状态。
 */
class DatabaseMaintenanceServiceTest {

    private static final String PRE_UPGRADE = "pre-upgrade";
    private static final String PRE_RUN = "pre-run";

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource;
    private DatabaseMaintenanceService maintenance;

    @BeforeEach
    void setUp() {
        dataSource = PersistenceTestSupport.migratedDataSource(directory);
        maintenance = new DatabaseMaintenanceService(dataSource);
    }

    // ==================== 目录分离与保留 ====================

    @Test
    void preRunBackupsKeepLatestTenInSeparateDirectory() throws Exception {
        for (int i = 0; i < 12; i++) {
            maintenance.backup("manual");
        }
        Path preRunDir = directory.resolve("backups").resolve(PRE_RUN);
        assertTrue(Files.isDirectory(preRunDir), "普通备份应写入 backups/pre-run");
        try (Stream<Path> stream = Files.list(preRunDir)) {
            assertEquals(10, stream.filter(Files::isRegularFile).count(), "pre-run 保留最近 10 份");
        }
        // 备份可打开且是迁移后的 v8 结构
        SQLiteDataSource backupDataSource = PersistenceTestSupport.dataSource(newestIn(preRunDir));
        assertEquals(13, scalar(backupDataSource, "PRAGMA user_version"));
        assertEquals(13, scalar(backupDataSource, "SELECT COUNT(*) FROM schema_migration"));
    }

    @Test
    void preUpgradeBackupsKeepLatestFiveAndAreNotPrunedByPreRun() throws Exception {
        for (int i = 0; i < 6; i++) {
            maintenance.backup("pre-upgrade");
        }
        Path preUpgradeDir = directory.resolve("backups").resolve(PRE_UPGRADE);
        try (Stream<Path> stream = Files.list(preUpgradeDir)) {
            assertEquals(5, stream.filter(Files::isRegularFile).count(), "pre-upgrade 保留最近 5 份");
        }
        // 再生成 12 份 pre-run，不得挤掉 pre-upgrade
        for (int i = 0; i < 12; i++) {
            maintenance.backup("manual");
        }
        try (Stream<Path> stream = Files.list(preUpgradeDir)) {
            assertEquals(5, stream.filter(Files::isRegularFile).count(),
                    "两类备份互不挤占：pre-run 不清理 pre-upgrade");
        }
        // backup(reason) 旧签名兼容：pre-migrate 也落 pre-upgrade
        Path legacyReason = maintenance.backup("pre-migrate");
        assertEquals(preUpgradeDir.toAbsolutePath().normalize(),
                legacyReason.getParent().toAbsolutePath().normalize());
        assertTrue(legacyReason.getFileName().toString().endsWith("-pre-migrate.db"));
    }

    @Test
    void legacyFlatBackupsAreReadButNotDeletedOrNewlyCreated() throws Exception {
        // 手工造一个旧版扁平备份（真实备份复制过去）
        Path realBackup = maintenance.backup("manual");
        Path flatBackup = directory.resolve("backups").resolve("offerchaser-20260101-000000-manual.db");
        Files.copy(realBackup, flatBackup);

        List<Map<String, Object>> all = maintenance.listBackups("all", 100);
        assertTrue(all.stream().anyMatch(entry -> "legacy".equals(entry.get("kind"))
                        && flatBackup.toString().equals(entry.get("path"))),
                "旧扁平备份应能在 all 中兼容读取");
        // 再次备份并清理，不删除旧扁平文件
        for (int i = 0; i < 12; i++) {
            maintenance.backup("manual");
        }
        assertTrue(Files.isRegularFile(flatBackup), "旧扁平备份不应被新清理逻辑删除");
        Map<String, Object> status = maintenance.status();
        assertEquals(1, status.get("legacyFlatBackupCount"));
    }

    // ==================== 完整性校验 ====================

    @Test
    void verifyBackupValidFileIsVerified() {
        Path backup = maintenance.backup("manual");

        Map<String, Object> verification = maintenance.verifyBackup(backup);

        assertEquals(Boolean.TRUE, verification.get("verified"));
        assertEquals("ok", verification.get("integrity"));
        assertTrue(((Number) verification.get("tableCount")).intValue() >= 17);
        assertEquals(13, verification.get("schemaVersion"));
        assertTrue(((Number) verification.get("sizeBytes")).longValue() > 0);
        assertEquals(Boolean.TRUE, verification.get("exists"));
        assertNotNull(verification.get("verifiedAt"));
        assertNull(verification.get("reason"));
    }

    @Test
    void verifyBackupCorruptOrMissingFileReturnsFalseWithoutThrowing() throws Exception {
        Path corrupt = directory.resolve("backups").resolve(PRE_RUN).resolve("offerchaser-corrupt.db");
        Files.createDirectories(corrupt.getParent());
        Files.writeString(corrupt, "这不是一个 SQLite 数据库文件");

        Map<String, Object> corruptResult = maintenance.verifyBackup(corrupt);
        assertEquals(Boolean.FALSE, corruptResult.get("verified"), "损坏文件必须 verified=false");
        assertEquals(Boolean.TRUE, corruptResult.get("exists"));
        assertNotNull(corruptResult.get("reason"));

        Map<String, Object> missingResult = maintenance.verifyBackup(
                directory.resolve("backups").resolve("not-exists.db"));
        assertEquals(Boolean.FALSE, missingResult.get("verified"));
        assertEquals(Boolean.FALSE, missingResult.get("exists"));
        assertNotNull(missingResult.get("reason"));
        assertEquals(Boolean.FALSE, maintenance.verifyBackup(null).get("verified"));
    }

    // ==================== 列表 ====================

    @Test
    void listBackupsFiltersKindsOrdersByMtimeAndLimits() throws Exception {
        maintenance.backup("pre-upgrade");
        maintenance.backup("pre-upgrade");
        maintenance.backup("manual");
        maintenance.backup("manual");
        maintenance.backup("manual");
        Path realBackup = maintenance.backup("manual");
        Files.copy(realBackup, directory.resolve("backups").resolve("offerchaser-20260101-000000-manual.db"));

        assertEquals(2, maintenance.listBackups(PRE_UPGRADE, 100).size());
        assertEquals(2, maintenance.listBackups(PRE_RUN, 2).size());
        assertEquals(1, maintenance.listBackups(PRE_RUN, 1).size());
        List<Map<String, Object>> all = maintenance.listBackups("all", 100);
        assertEquals(7, all.size(), "all = pre-upgrade(2) + pre-run(4) + legacy(1)");
        assertTrue(((Number) all.get(0).get("mtimeMs")).longValue()
                        >= ((Number) all.get(all.size() - 1).get("mtimeMs")).longValue(),
                "按 mtime DESC");
        Map<String, Object> entry = all.get(0);
        assertTrue(entry.keySet().containsAll(List.of("path", "kind", "sizeBytes", "mtime", "mtimeMs")));
        assertThrows(IllegalArgumentException.class, () -> maintenance.listBackups("bad-kind", 10));
    }

    // ==================== 备份状态 / 回滚提示 ====================

    @Test
    void backupStatusReportsSeparatedKindsAndVerifiedLatestPreUpgrade() {
        Path upgradeBackup = maintenance.backup("pre-upgrade");
        maintenance.backup("manual");

        Map<String, Object> backupStatus = maintenance.backupStatus();
        @SuppressWarnings("unchecked")
        Map<String, Object> preUpgrade = (Map<String, Object>) backupStatus.get("preUpgrade");
        @SuppressWarnings("unchecked")
        Map<String, Object> preRun = (Map<String, Object>) backupStatus.get("preRun");
        assertEquals(1, preUpgrade.get("count"));
        assertEquals(upgradeBackup.toString(), preUpgrade.get("latest"));
        assertEquals(Boolean.TRUE, preUpgrade.get("latestVerified"));
        assertEquals(1, preRun.get("count"));
        assertNotNull(backupStatus.get("lastBackup"));
        assertNull(backupStatus.get("rollbackWarning"), "没有发送事实时不提示");
        assertNull(backupStatus.get("latestSendFactAt"));
    }

    @Test
    void rollbackWarningWhenLatestPreUpgradeBackupOlderThanSendFact() {
        maintenance.backup("pre-upgrade");
        String futureSendFact = LocalDateTime.now().plusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        execute(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "started_at, finished_at, created_at) VALUES('att-fact', 'run-1', 'local', " +
                        "'FIRST_CONTACT', 'CONFIRMED', 'NOT_ATTEMPTED', '2026-11-01 10:00:00', ?, " +
                        "'2026-11-01 10:00:00')", futureSendFact);

        Map<String, Object> backupStatus = maintenance.backupStatus();
        assertEquals(futureSendFact, backupStatus.get("latestSendFactAt"));
        String warning = String.valueOf(backupStatus.get("rollbackWarning"));
        assertTrue(warning.contains("旧库可能不含最近已发送记录"), "应提示回滚风险：" + warning);

        // 发送事实早于备份时间 → 不提示
        execute(dataSource, "UPDATE delivery_attempt SET finished_at = '2020-01-01 00:00:00'");
        assertNull(maintenance.backupStatus().get("rollbackWarning"), "发送事实早于备份时间不应提示");
    }

    // ==================== 数据库状态兼容 ====================

    @Test
    void statusReportsDatabaseAndBackupInformation() {
        Path backup = maintenance.backup("manual");

        Map<String, Object> status = maintenance.status();

        Path expectedDatabase = directory.resolve("offerchaser-test.db").toAbsolutePath().normalize();
        assertEquals(expectedDatabase.toString(), status.get("databasePath"));
        assertEquals(Boolean.TRUE, status.get("databaseExists"));
        assertTrue(((Number) status.get("databaseSizeBytes")).longValue() > 0);
        assertEquals(13, status.get("schemaVersion"));
        assertTrue(((Number) status.get("tableCount")).intValue() >= 17);
        assertEquals(10, status.get("backupRetention"));
        assertEquals(5, status.get("preUpgradeRetention"));
        assertEquals(1, status.get("backupCount"));
        assertEquals(1, status.get("preRunBackupCount"));
        assertEquals(0, status.get("preUpgradeBackupCount"));
        assertEquals(backup.toString(), status.get("lastBackup"));
        assertNull(status.get("error"));
    }

    @Test
    void backupReturnsNullForInMemoryDatabase() {
        SQLiteDataSource memoryDataSource = new SQLiteDataSource();
        memoryDataSource.setUrl("jdbc:sqlite::memory:");
        DatabaseMaintenanceService memoryMaintenance = new DatabaseMaintenanceService(memoryDataSource);

        assertNull(memoryMaintenance.backup("manual"), "内存库无法 VACUUM INTO 到文件，应返回 null 而不是抛异常");
        assertNull(memoryMaintenance.backupStatus().get("rollbackWarning"));
        assertTrue(memoryMaintenance.listBackups("all", 10).isEmpty());
    }

    private Path newestIn(Path backupDir) throws Exception {
        try (Stream<Path> stream = Files.list(backupDir)) {
            return stream.filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(this::lastModified))
                    .orElseThrow();
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }
}





