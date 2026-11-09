package com.offerchaser.verification;

import com.offerchaser.application.persistence.DatabaseMaintenanceService;
import com.offerchaser.application.persistence.SchemaMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.offerchaser.verification.VerificationSupport.countFiles;
import static com.offerchaser.verification.VerificationSupport.dataSource;
import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migrate;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5 备份分离与可恢复性验收（真实 SQLite + 真实 {@link DatabaseMaintenanceService}/迁移服务）：
 * pre-upgrade/pre-run 目录分离与保留数互不影响、旧扁平文件只读兼容、verifyBackup 正常/损坏不抛、
 * rollbackWarning 计算、迁移前备份落 pre-upgrade 并自动校验、v8 幂等/发送事实保留、迁移只增量。
 */
class B5BackupIntegrationTest {

    @TempDir
    Path tempDir;

    private Path databaseFile;
    private SQLiteDataSource dataSource;
    private DatabaseMaintenanceService maintenanceService;

    @BeforeEach
    void setUp() throws Exception {
        databaseFile = tempDir.resolve("db").resolve("offerchaser.db");
        Files.createDirectories(databaseFile.getParent());
        dataSource = dataSource(databaseFile);
        migrate(dataSource);
        assertTrue(Files.isRegularFile(databaseFile), "迁移后数据库文件应存在");
        maintenanceService = new DatabaseMaintenanceService(dataSource);
    }

    // ==================== 目录分离 / 保留数 ====================

    @Test
    void preUpgradeAndPreRunBackupsUseSeparateDirectoriesAndRetention() throws Exception {
        for (int i = 0; i < 12; i++) {
            Path backup = maintenanceService.backup("pre-run");
            assertNotNull(backup);
            assertEquals(tempDir.resolve("db").resolve("backups").resolve("pre-run")
                            .toAbsolutePath().normalize(),
                    backup.getParent().toAbsolutePath().normalize(),
                    "任务前备份必须落在 backups/pre-run/");
        }
        assertEquals(0, listBackups("pre-upgrade").size());
        assertEquals(10, listBackups("pre-run").size(), "pre-run 只保留最近 10 份");

        for (int i = 0; i < 6; i++) {
            Path backup = maintenanceService.backup("pre-upgrade");
            assertEquals(tempDir.resolve("db").resolve("backups").resolve("pre-upgrade")
                            .toAbsolutePath().normalize(),
                    backup.getParent().toAbsolutePath().normalize(),
                    "升级前备份必须落在 backups/pre-upgrade/");
        }
        assertEquals(5, listBackups("pre-upgrade").size(), "pre-upgrade 只保留最近 5 份");
        assertEquals(10, listBackups("pre-run").size(), "两类备份互不挤占：补升级前备份不得删运行前备份");

        Map<String, Object> status = maintenanceService.backupStatus();
        @SuppressWarnings("unchecked")
        Map<String, Object> preUpgrade = (Map<String, Object>) status.get("preUpgrade");
        @SuppressWarnings("unchecked")
        Map<String, Object> preRun = (Map<String, Object>) status.get("preRun");
        assertEquals(5, ((Number) preUpgrade.get("count")).intValue());
        assertEquals(10, ((Number) preRun.get("count")).intValue());
        assertEquals(Boolean.TRUE, preUpgrade.get("latestVerified"), "backupStatus 必须验证最新升级前备份");
        assertTrue(String.valueOf(preUpgrade.get("dir")).contains("pre-upgrade"));
        assertTrue(String.valueOf(preRun.get("dir")).contains("pre-run"));
        assertNotNull(status.get("lastBackup"));
    }

    @Test
    void legacyFlatBackupsRemainReadableAndAreNotDeletedByNewBackups() throws Exception {
        Path backupRoot = databaseFile.getParent().resolve("backups");
        Files.createDirectories(backupRoot);
        Path legacyFlat = backupRoot.resolve("offerchaser-20260101-000000-pre-migrate.db");
        Files.copy(databaseFile, legacyFlat);

        List<Map<String, Object>> all = maintenanceService.listBackups("all", 100);
        assertTrue(all.stream().anyMatch(item -> "legacy".equals(item.get("kind"))),
                "旧扁平备份应以 legacy kind 兼容读取");
        @SuppressWarnings("unchecked")
        Map<String, Object> legacyStatus = (Map<String, Object>) maintenanceService.backupStatus().get("legacyFlat");
        assertEquals(1, ((Number) legacyStatus.get("count")).intValue());

        maintenanceService.backup("pre-run");
        maintenanceService.backup("pre-upgrade");
        assertTrue(Files.isRegularFile(legacyFlat), "新备份不得删除旧扁平备份");
        assertEquals(1, ((Number) ((Map<?, ?>) maintenanceService.backupStatus()
                .get("legacyFlat")).get("count")).intValue());
    }

    // ==================== verifyBackup / rollbackWarning ====================

    @Test
    void verifyBackupDetectsHealthyAndCorruptFilesWithoutThrowing() throws Exception {
        Path healthy = maintenanceService.backup("pre-run");
        Map<String, Object> verified = maintenanceService.verifyBackup(healthy);
        assertEquals(Boolean.TRUE, verified.get("verified"));
        assertEquals("ok", verified.get("integrity"));
        assertTrue(((Number) verified.get("tableCount")).intValue() > 0);
        assertEquals(SchemaMigrationService.TARGET_VERSION,
                ((Number) verified.get("schemaVersion")).intValue());
        assertTrue(Files.size(healthy) > 0);
        assertTrue(((Number) verified.get("sizeBytes")).longValue() > 0);
        assertNotNull(verified.get("verifiedAt"));

        Path corrupt = tempDir.resolve("corrupt.db");
        Files.writeString(corrupt, "this is not a sqlite database", StandardCharsets.UTF_8);
        // verifyBackup 契约：损坏/不可读返回 verified=false + reason，不抛异常
        Map<String, Object> corruptResult = maintenanceService.verifyBackup(corrupt);
        assertEquals(Boolean.FALSE, corruptResult.get("verified"));
        assertEquals(Boolean.TRUE, corruptResult.get("exists"));
        assertNotNull(corruptResult.get("reason"));
        assertEquals(0, ((Number) corruptResult.get("tableCount")).intValue());

        Map<String, Object> missing = maintenanceService.verifyBackup(tempDir.resolve("not-exists.db"));
        assertEquals(Boolean.FALSE, missing.get("verified"));
        assertTrue(String.valueOf(missing.get("reason")).contains("不存在"));
    }

    @Test
    void rollbackWarningWhenLatestUpgradeBackupIsOlderThanLatestSendFact() throws Exception {
        maintenanceService.backup("pre-upgrade");
        execute(dataSource, "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, " +
                        "text_status, image_status, started_at, finished_at, created_at) " +
                        "VALUES('attempt-b5','run-b5','local','FIRST_CONTACT','CONFIRMED','SKIPPED'," +
                        "'2999-01-01 10:00:00','2999-01-01 12:00:00','2999-01-01 10:00:00')");

        Map<String, Object> status = maintenanceService.backupStatus();
        assertEquals("2999-01-01 12:00:00", status.get("latestSendFactAt"));
        String warning = (String) status.get("rollbackWarning");
        assertNotNull(warning, "升级前备份早于最近发送事实时必须提示回滚风险");
        assertTrue(warning.contains("恢复后不要直接恢复自动投递"), warning);
        assertTrue(warning.contains("先核对"), warning);

        execute(dataSource, "UPDATE delivery_attempt SET finished_at='2020-01-01 00:00:00' " +
                "WHERE attempt_id='attempt-b5'");
        assertNull(maintenanceService.backupStatus().get("rollbackWarning"),
                "发送事实早于备份时不应提示回滚风险");
    }

    // ==================== 迁移前备份 / 幂等 ====================

    @Test
    void migrationBackupLandsInPreUpgradeAndIsVerified() throws Exception {
        Path legacyDb = tempDir.resolve("legacy").resolve("offerchaser.db");
        Files.createDirectories(legacyDb.getParent());
        SQLiteDataSource legacySource = dataSource(legacyDb);
        execute(legacySource, "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "encrypt_id TEXT, encrypt_user_id TEXT, company_name TEXT, job_name TEXT, salary TEXT, " +
                "location TEXT, experience TEXT, degree TEXT, hr_name TEXT, hr_position TEXT, " +
                "hr_active_status TEXT, delivery_status TEXT, job_description TEXT, job_url TEXT, " +
                "recruitment_status TEXT, company_address TEXT, industry TEXT, introduce TEXT, " +
                "financing_stage TEXT, company_scale TEXT, created_at TEXT, updated_at TEXT)");
        execute(legacySource, "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name, " +
                "created_at, updated_at) VALUES('OLD-1','OLD-USER-1','旧岗位'," +
                "'2026-01-01 00:00:00','2026-01-01 00:00:00')");

        SchemaMigrationService migration = new SchemaMigrationService(legacySource,
                new DatabaseMaintenanceService(legacySource));
        migration.migrate();

        assertTrue(migration.isReady());
        assertEquals(SchemaMigrationService.TARGET_VERSION, migration.currentVersion());
        Path backup = migration.lastBackupPath();
        assertNotNull(backup);
        assertEquals(legacyDb.getParent().resolve("backups").resolve("pre-upgrade")
                        .toAbsolutePath().normalize(),
                backup.getParent().toAbsolutePath().normalize(),
                "迁移前备份必须落在 backups/pre-upgrade/");
        assertEquals(Boolean.TRUE, migration.latestPreUpgradeBackupVerified(),
                "迁移成功后必须校验最新升级前备份可恢复");
        DatabaseMaintenanceService legacyMaintenance = new DatabaseMaintenanceService(legacySource);
        Map<String, Object> verification = legacyMaintenance.verifyBackup(backup);
        assertEquals(Boolean.TRUE, verification.get("verified"));
        assertEquals(0, ((Number) verification.get("schemaVersion")).intValue(), "备份应是迁移前版本");
        assertEquals(1, scalar(legacySource,
                "SELECT COUNT(*) FROM boss_data WHERE encrypt_id='OLD-1'"), "旧岗位数据应保留");
        assertEquals(0, scalar(legacySource,
                "SELECT COUNT(*) FROM job_source WHERE encrypt_id='OLD-1'"), "旧库迁移后 job_source 为空");
    }

    @Test
    void alreadyMigratedTargetDatabaseIsIdempotentAndKeepsSendFacts() throws Exception {
        execute(dataSource, "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, " +
                "text_status, image_status, started_at, finished_at, created_at) " +
                "VALUES('attempt-keep','run-keep','local','FIRST_CONTACT','CONFIRMED','SKIPPED'," +
                "'2026-11-04 10:00:00','2026-11-04 10:01:00','2026-11-04 10:00:00')");
        int backupsBefore = countFiles(databaseFile.getParent().resolve("backups"), "offerchaser-", ".db");

        SchemaMigrationService second = new SchemaMigrationService(dataSource,
                new DatabaseMaintenanceService(dataSource));
        second.migrate();

        assertTrue(second.isReady());
        assertEquals(SchemaMigrationService.TARGET_VERSION, second.currentVersion());
        assertNull(second.lastBackupPath(), "已迁移到目标版本时不应再备份");
        assertEquals(backupsBefore,
                countFiles(databaseFile.getParent().resolve("backups"), "offerchaser-", ".db"));
        assertEquals("CONFIRMED", scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE attempt_id='attempt-keep'"));
    }

    @Test
    void migrationSqlIsAppendOnlyV1ToV13() throws Exception {
        assertEquals(13, SchemaMigrationService.TARGET_VERSION);
        for (int version = 1; version <= 13; version++) {
            if (version == 2) {
                continue; // V2 是“安全唯一索引”逻辑，不是 DDL 数组
            }
            if (version == 8) {
                // V8 拆成两个 DDL 数组（job_source / boss_data 部分）
                for (String field : List.of("DDL_V8_JOB_SOURCE", "DDL_V8_BOSS_DATA")) {
                    Field declared = SchemaMigrationService.class.getDeclaredField(field);
                    declared.setAccessible(true);
                    assertTrue(((String[]) declared.get(null)).length > 0, field + " 不应为空");
                }
                continue;
            }
            Field field = SchemaMigrationService.class.getDeclaredField("DDL_V" + version);
            field.setAccessible(true);
            String[] ddls = (String[]) field.get(null);
            assertTrue(ddls.length > 0, "V" + version + " 迁移 DDL 不应为空");
        }
        Field v13 = SchemaMigrationService.class.getDeclaredField("DDL_V13");
        v13.setAccessible(true);
        assertTrue(((String[]) v13.get(null)).length > 0, "DDL_V13 不应为空（目标版本已是 v13）");
        String source = Files.readString(Path.of(
                "src/main/java/com/offerchaser/application/persistence/SchemaMigrationService.java"));
        assertTrue(source.contains("已发布迁移不得修改"), "迁移源码必须保留只增量约束说明");
        String descriptionV12 = (String) ReflectionTestUtils.getField("DESCRIPTION_V12");
        assertNotNull(descriptionV12);
        String descriptionV13 = (String) ReflectionTestUtils.getField("DESCRIPTION_V13");
        assertNotNull(descriptionV13);
        assertTrue(descriptionV13.contains("manual_release"), "V13 描述应说明人工放行表：" + descriptionV13);
    }

    private List<Map<String, Object>> listBackups(String kind) {
        return maintenanceService.listBackups(kind, 100);
    }

    /** 反射读取私有常量的小工具（避免 ReflectionTestUtils 名称冲突）。 */
    private static final class ReflectionTestUtils {
        static Object getField(String name) throws Exception {
            Field field = SchemaMigrationService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        }
    }
}