package com.offerchaser.application.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static com.offerchaser.application.persistence.PersistenceTestSupport.executeScript;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalar;
import static com.offerchaser.application.persistence.PersistenceTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库迁移测试：V1-V4 建表/版本双记录/幂等/备份/V2 唯一索引/失败保护，以及 V4 增量升级。
 */
class SchemaMigrationServiceTest {

    @TempDir
    Path directory;

    @Test
    void freshDatabaseCreatesAllTablesAndRecordsVersionTwice() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("fresh.db"));
        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        for (String table : List.of("schema_migration", "task_run", "job_evaluation", "delivery_attempt",
                "delivery_event", "daily_budget", "contact_followup", "budget_reservation", "manual_action",
                "rule_config_version", "contact_state", "followup_plan", "job_source", "profile_template", "delivery_status_sync", "delivery_status_snapshot", "manual_release")) {
            assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                    table), "缺少表：" + table);
        }
        assertEquals(13, scalar(dataSource, "SELECT COUNT(*) FROM schema_migration"));
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertNull(migration.lastBackupPath(), "全新空库没有旧数据，不需要备份");
    }

    @Test
    void migrationIsIdempotent() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("idempotent.db"));
        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        migration.migrate();
        migration.migrate();

        assertTrue(migration.isReady());
        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "SELECT COUNT(*) FROM schema_migration"));
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'task_run'"), "重复迁移不得重复建表");
        assertNull(migration.lastBackupPath(), "已是目标版本时不会新增迁移前备份");
    }

    @Test
    void backsUpExistingDataBeforeUpgrading() {
        Path databaseFile = directory.resolve("old.db");
        SQLiteDataSource dataSource = PersistenceTestSupport.dataSource(databaseFile);
        executeScript(dataSource,
                "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, " +
                        "encrypt_user_id TEXT, job_name TEXT)",
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name) VALUES('job-1', 'user-1', 'Java开发')");

        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        Path backup = migration.lastBackupPath();
        assertNotNull(backup, "已有数据的库在迁移前必须备份");
        assertTrue(Files.isRegularFile(backup));
        String backupName = backup.getFileName().toString();
        assertTrue(backupName.startsWith("offerchaser-"), "备份文件名应以 offerchaser- 开头：" + backupName);
        assertTrue(backupName.endsWith("-pre-upgrade.db"),
                "B5 起迁移前备份 reason=pre-upgrade，文件名应以 -pre-upgrade.db 结尾：" + backupName);
        assertEquals(directory.resolve("backups").resolve("pre-upgrade").toAbsolutePath().normalize(),
                backup.getParent().toAbsolutePath().normalize(), "迁移前备份应存放在 backups/pre-upgrade 目录");
        // B5：迁移成功后对最新 pre-upgrade 备份做完整性校验
        assertEquals(Boolean.TRUE, migration.latestPreUpgradeBackupVerified(),
                "迁移成功后 pre-upgrade 备份应校验通过");

        // 备份是迁移前的快照：有旧的 boss_data 数据，但还没有新表
        SQLiteDataSource backupDataSource = PersistenceTestSupport.dataSource(backup);
        assertEquals(1, scalar(backupDataSource, "SELECT COUNT(*) FROM boss_data"));
        assertEquals("Java开发", scalarString(backupDataSource, "SELECT job_name FROM boss_data LIMIT 1"));
        assertEquals(0, scalar(backupDataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'task_run'"));
        // 原库数据保留
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM boss_data"));
    }

    @Test
    void createsBossDataUniqueIndexWhenNoDuplicates() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("unique.db"));
        executeScript(dataSource,
                "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, " +
                        "encrypt_user_id TEXT, job_name TEXT)",
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name) VALUES('job-1', 'user-1', '岗位1')",
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name) VALUES('job-2', 'user-2', '岗位2')");

        PersistenceTestSupport.migrate(dataSource);

        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'uk_boss_data_job'"));
    }

    @Test
    void skipsUniqueIndexWhenDuplicatesExistAndKeepsAllRows() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("duplicate.db"));
        executeScript(dataSource,
                "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, " +
                        "encrypt_user_id TEXT, job_name TEXT)",
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name) VALUES('job-1', 'user-1', '岗位1')",
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name) VALUES('job-1', 'user-1', '岗位1副本')");

        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        assertTrue(migration.isReady(), "有重复数据时迁移仍应完成（跳过索引，不失败）");
        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'uk_boss_data_job'"));
        assertEquals(2, scalar(dataSource, "SELECT COUNT(*) FROM boss_data"), "不得删除重复数据");
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
    }

    @Test
    void failedMigrationKeepsReadyFalseAndPreservesOldData() throws Exception {
        Path databaseFile = directory.resolve("broken.db");
        SQLiteDataSource dataSource = PersistenceTestSupport.dataSource(databaseFile);
        executeScript(dataSource,
                "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, " +
                        "encrypt_user_id TEXT, job_name TEXT)",
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name) VALUES('job-1', 'user-1', 'Java开发')");
        // 在数据库同级放一个同名文件，使 backups 目录无法创建，从而让迁移前的备份失败
        Files.writeString(directory.resolve("backups"), "占位文件，阻止创建备份目录");

        DatabaseMaintenanceService maintenance = new DatabaseMaintenanceService(dataSource);
        SchemaMigrationService migration = new SchemaMigrationService(dataSource, maintenance);
        migration.migrate();

        assertFalse(migration.isReady(), "迁移失败时 isReady() 必须为 false");
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM boss_data"), "迁移失败不得删除旧数据");
        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'task_run'"),
                "迁移失败不应留下半成品表");
        assertEquals(0, scalar(dataSource, "PRAGMA user_version"), "迁移失败不推进 user_version");
    }

    @Test
    void migrationCreatesColumnsExactlyAsSpecified() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("columns.db"));
        PersistenceTestSupport.migrate(dataSource);

        assertColumns(dataSource, "task_run", List.of(
                "id", "run_id", "platform", "state", "config_snapshot", "stop_reason", "error",
                "started_at", "ended_at", "updated_at"));
        assertColumns(dataSource, "job_evaluation", List.of(
                "id", "run_id", "encrypt_id", "encrypt_user_id", "job_name", "company_name", "job_url",
                "source_keyword", "source_city", "snapshot_hash", "rule_version", "rule_decision",
                "rule_reason_code", "rule_evidence", "ai_decision", "ai_error_code", "ai_analysis",
                "ai_message", "prompt_version", "ai_model", "prompt_tokens", "completion_tokens",
                "duration_ms", "created_at",
                // V5 规则版本/哈希
                "rule_config_version", "rules_hash"));
        assertColumns(dataSource, "delivery_attempt", List.of(
                "id", "attempt_id", "run_id", "account", "kind", "encrypt_id", "encrypt_user_id",
                "job_name", "company_name", "job_url", "snapshot_hash", "message_hash", "resume_hash",
                "text_status", "image_status", "text_evidence", "image_evidence", "error_code",
                "error_detail", "budget_id", "started_at", "finished_at", "created_at",
                // V4 人工核对字段
                "version", "manual_resolution", "resolved_at", "resolve_reason",
                // V7 联系人关联字段
                "contact_key", "parent_attempt_id"));
        assertColumns(dataSource, "delivery_event", List.of(
                "id", "attempt_id", "run_id", "event_type", "evidence_level", "detail", "occurred_at"));
        assertColumns(dataSource, "daily_budget", List.of(
                "id", "account", "budget_date", "action_type", "budget_limit", "consumed", "reserved",
                "uncertain", "updated_at"));
        assertColumns(dataSource, "contact_followup", List.of(
                "id", "account", "encrypt_id", "encrypt_user_id", "job_name", "followup_count",
                "last_followup_at", "manual_takeover", "updated_at"));
        assertColumns(dataSource, "schema_migration", List.of("version", "description", "applied_at"));
        assertColumns(dataSource, "budget_reservation", List.of(
                "id", "reservation_id", "attempt_id", "budget_id", "account", "action_type",
                "budget_date", "state", "version", "created_at", "settled_at"));
        // V4 manual_action 审计表
        assertColumns(dataSource, "manual_action", List.of(
                "id", "object_type", "object_id", "action", "before_state", "after_state",
                "reason", "idempotency_key", "created_at"));
        // V5 规则配置版本表
        assertColumns(dataSource, "rule_config_version", List.of(
                "id", "version", "rules_json", "source_hash", "note", "created_at", "live_hash"));
        // V7 联系人状态表 / 跟进计划表
        assertColumns(dataSource, "contact_state", List.of(
                "id", "account", "platform", "contact_key", "encrypt_user_id", "hr_name", "company_name",
                "reply_state", "manual_takeover", "stop_contact", "last_contact_at", "last_reply_at",
                "version", "created_at", "updated_at"));
        assertColumns(dataSource, "followup_plan", List.of(
                "id", "plan_id", "account", "platform", "encrypt_id", "encrypt_user_id", "contact_key",
                "job_name", "company_name", "plan_type", "status", "reason", "parent_attempt_id",
                "version", "suggested_at", "decided_at", "executed_at", "executed_attempt_id",
                "created_at", "updated_at"));
        // V8 采集来源表
        assertColumns(dataSource, "job_source", List.of(
                "id", "account", "platform", "encrypt_id", "keyword", "city_code",
                "discovered_at", "snapshot_hash", "created_at"));
        // V9 方向模板表（V10 追加 delivery_policy_json，V11 追加 sort_order）
        assertColumns(dataSource, "profile_template", List.of(
                "id", "name", "direction", "description", "active", "boss_config_json",
                "ai_introduce", "ai_prompt", "rule_payload_json", "resume_file",
                "created_at", "updated_at", "delivery_policy_json", "sort_order"));
        // V12 投递状态同步运行表 / 快照明细表
        assertColumns(dataSource, "delivery_status_sync", List.of(
                "sync_id", "trigger_type", "scope", "target_date", "status", "started_at", "finished_at",
                "auto_total", "auto_read", "auto_replied", "auto_unread", "auto_unknown",
                "manual_total", "manual_read", "manual_replied", "manual_unread", "manual_unknown",
                "hr_total", "hr_read", "hr_replied", "hr_unread", "hr_unknown", "error"));
        assertColumns(dataSource, "delivery_status_snapshot", List.of(
                "id", "sync_id", "source", "contact_key", "encrypt_user_id", "hr_name", "company_name",
                "job_name", "delivered_status", "read_status", "reply_status", "initiator",
                "last_message", "chat_time", "evidence", "created_at"));
        // V13 人工放行表
        assertColumns(dataSource, "manual_release", List.of(
                "release_id", "evaluation_id", "status", "reason", "requested_at", "decided_at",
                "consumed_at", "attempt_id", "error", "created_at", "updated_at"));
        // V3 + V4 + V5 索引
        assertEquals(3, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN " +
                        "('idx_budget_reservation_state','idx_budget_reservation_budget'," +
                        "'idx_budget_reservation_attempt')"));
        assertEquals(6, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN " +
                        "('idx_manual_action_object','idx_task_run_started','idx_job_evaluation_run'," +
                        "'idx_delivery_attempt_run_started','idx_delivery_attempt_encrypt_started'," +
                        "'idx_delivery_attempt_text_status')"));
        assertEquals(3, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN " +
                        "('idx_rule_config_version_created','idx_rule_config_version_version'," +
                        "'idx_job_evaluation_encrypt_latest')"));
        // V7 联系人/跟进索引
        assertEquals(6, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN " +
                        "('idx_contact_state_key','idx_contact_state_reply','idx_followup_plan_status'," +
                        "'idx_followup_plan_contact','idx_followup_plan_job','idx_delivery_attempt_contact')"));
        // V8 来源索引（fresh 库没有 boss_data，idx_boss_data_first_seen 由 BossService 补列时创建）
        assertEquals(3, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN " +
                        "('idx_job_source_time','idx_job_source_keyword','idx_job_source_job')"));
        // V9 方向模板 active 索引
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_profile_template_active'"));
        // V12 同步索引
        assertEquals(2, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN " +
                        "('idx_delivery_status_sync_trigger','idx_delivery_status_sync_started')"));
        assertEquals(2, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN " +
                        "('idx_delivery_status_snapshot_sync','idx_delivery_status_snapshot_source')"));
        // V13 人工放行索引
        assertEquals(3, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN " +
                        "('idx_manual_release_status','idx_manual_release_evaluation'," +
                        "'idx_manual_release_requested')"));
    }

    @Test
    void migratesFromVersionSevenToThirteenOnly() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("v7.db"));
        PersistenceTestSupport.migrate(dataSource);
        // 模拟停在 v7 的旧库：撤掉 V8 的表/列/索引与版本记录
        downgradeToVersion7(dataSource);
        // v7 旧库里 boss_data 已存在（旧表，无 B4 三列）
        executeScript(dataSource,
                "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, " +
                        "encrypt_user_id TEXT, job_name TEXT, delivery_status TEXT, created_at TEXT)",
                "INSERT INTO boss_data(encrypt_id, encrypt_user_id, job_name, delivery_status, created_at) " +
                        "VALUES('legacy-job', 'legacy-user', '旧岗位', '已投递', '2026-10-01 10:00:00')");

        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertEquals(13, scalar(dataSource, "SELECT MAX(version) FROM schema_migration"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'job_source'"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'profile_template'"));
        for (String column : List.of("first_seen_at", "last_detail_at", "snapshot_hash")) {
            assertEquals(1, scalar(dataSource,
                    "SELECT COUNT(*) FROM pragma_table_info('boss_data') WHERE name = ?", column),
                    "V8 boss_data 缺列：" + column);
        }
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_boss_data_first_seen'"));
        // 旧数据保留：新增三列为 null
        assertNull(scalarString(dataSource,
                "SELECT first_seen_at FROM boss_data WHERE encrypt_id = 'legacy-job'"));
        assertNull(scalarString(dataSource,
                "SELECT last_detail_at FROM boss_data WHERE encrypt_id = 'legacy-job'"));
        assertNull(scalarString(dataSource,
                "SELECT snapshot_hash FROM boss_data WHERE encrypt_id = 'legacy-job'"));
        // 幂等：重复迁移不重复加列
        migration.migrate();
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('boss_data') WHERE name = 'first_seen_at'"));
    }

    @Test
    void v8AddsBossDataCollectionColumnsWhenTableExistsDuringMigration() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("with-boss-data.db"));
        executeScript(dataSource,
                "CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, " +
                        "encrypt_user_id TEXT, job_name TEXT, delivery_status TEXT, created_at TEXT)");
        PersistenceTestSupport.migrate(dataSource);

        for (String column : List.of("first_seen_at", "last_detail_at", "snapshot_hash")) {
            assertEquals(1, scalar(dataSource,
                    "SELECT COUNT(*) FROM pragma_table_info('boss_data') WHERE name = ?", column),
                    "V8 boss_data 缺列：" + column);
        }
    }

    @Test
    void migratesFromVersionSixToThirteenOnly() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("v6.db"));
        PersistenceTestSupport.migrate(dataSource);
        executeScript(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "started_at, created_at) VALUES('legacy-attempt', 'run-legacy', 'local', " +
                        "'FIRST_CONTACT', 'UNKNOWN', 'NOT_ATTEMPTED', '2026-11-01 10:00:00', " +
                        "'2026-11-01 10:00:00')");
        downgradeToVersion6(dataSource);

        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertEquals(13, scalar(dataSource, "SELECT MAX(version) FROM schema_migration"));
        for (String table : List.of("contact_state", "followup_plan", "job_source", "profile_template")) {
            assertEquals(1, scalar(dataSource,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", table),
                    "缺表：" + table);
        }
        for (String column : List.of("contact_key", "parent_attempt_id")) {
            assertEquals(1, scalar(dataSource,
                    "SELECT COUNT(*) FROM pragma_table_info('delivery_attempt') WHERE name = ?", column),
                    "V7 缺列：" + column);
        }
        // 旧 attempt 保留，新列为 null
        assertNull(scalarString(dataSource,
                "SELECT contact_key FROM delivery_attempt WHERE attempt_id = 'legacy-attempt'"));
        assertNull(scalarString(dataSource,
                "SELECT parent_attempt_id FROM delivery_attempt WHERE attempt_id = 'legacy-attempt'"));
    }

    @Test
    void migratesFromVersionThreeToThirteenOnly() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("v3.db"));
        PersistenceTestSupport.migrate(dataSource);
        executeScript(dataSource,
                "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, text_status, image_status, " +
                        "started_at, created_at) VALUES('legacy-attempt', 'run-legacy', 'local', " +
                        "'FIRST_CONTACT', 'UNKNOWN', 'NOT_ATTEMPTED', '2026-11-01 10:00:00', " +
                        "'2026-11-01 10:00:00')");
        downgradeToVersion3(dataSource);

        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertEquals(13, scalar(dataSource, "SELECT MAX(version) FROM schema_migration"));
        for (String table : List.of("manual_action", "rule_config_version", "contact_state", "followup_plan", "job_source", "profile_template", "delivery_status_sync", "delivery_status_snapshot", "manual_release")) {
            assertEquals(1, scalar(dataSource,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", table),
                    "缺表：" + table);
        }
        for (String column : List.of("version", "manual_resolution", "resolved_at", "resolve_reason",
                "contact_key", "parent_attempt_id")) {
            assertEquals(1, scalar(dataSource,
                    "SELECT COUNT(*) FROM pragma_table_info('delivery_attempt') WHERE name = ?", column),
                    "缺列：" + column);
        }
        for (String column : List.of("rule_config_version", "rules_hash")) {
            assertEquals(1, scalar(dataSource,
                    "SELECT COUNT(*) FROM pragma_table_info('job_evaluation') WHERE name = ?", column),
                    "缺列：" + column);
        }
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('rule_config_version') WHERE name = 'live_hash'"),
                "V6 缺列：live_hash");
        // 旧数据保留：新增列均为 null/0
        assertEquals(0, scalar(dataSource,
                "SELECT version FROM delivery_attempt WHERE attempt_id = 'legacy-attempt'"));
        assertNull(scalarString(dataSource,
                "SELECT manual_resolution FROM delivery_attempt WHERE attempt_id = 'legacy-attempt'"));
    }

    @Test
    void migratesFromVersionNineToThirteenOnly() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("v9.db"));
        PersistenceTestSupport.migrate(dataSource);
        executeScript(dataSource,
                "INSERT INTO profile_template(name, direction, description, active, boss_config_json, " +
                        "ai_introduce, ai_prompt, rule_payload_json, resume_file, created_at, updated_at) " +
                        "VALUES('旧模板', '后端', '旧描述', 0, '{\"cfg\":1}', '招呼语', '提示词', " +
                        "'{\"rules\":[1]}', 'old.pdf', '2026-11-01 10:00:00', '2026-11-01 10:00:00')");
        // 模拟停在 v9 的旧库：没有 delivery_policy_json 列、没有 v10 记录
        downgradeToVersion9(dataSource);

        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertEquals(13, scalar(dataSource, "SELECT MAX(version) FROM schema_migration"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('profile_template') WHERE name = 'delivery_policy_json'"),
                "V10 必须补出 delivery_policy_json 列");
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('profile_template') WHERE name = 'sort_order'"),
                "V11 必须补出 sort_order 列");
        // 旧数据保留：delivery_policy_json 为 null，其它字段不变
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM profile_template WHERE name = '旧模板'"));
        assertNull(scalarString(dataSource,
                "SELECT delivery_policy_json FROM profile_template WHERE name = '旧模板'"));
        assertNull(scalarString(dataSource,
                "SELECT sort_order FROM profile_template WHERE name = '旧模板'"));
        assertEquals("后端", scalarString(dataSource,
                "SELECT direction FROM profile_template WHERE name = '旧模板'"));
        assertEquals("{\"rules\":[1]}", scalarString(dataSource,
                "SELECT rule_payload_json FROM profile_template WHERE name = '旧模板'"));
        // 幂等：重复迁移不重复加列
        migration.migrate();
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('profile_template') WHERE name = 'delivery_policy_json'"));
    }

    @Test
    void migratesFromVersionTenToThirteenOnly() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("v10.db"));
        PersistenceTestSupport.migrate(dataSource);
        executeScript(dataSource,
                "INSERT INTO profile_template(name, direction, description, active, boss_config_json, " +
                        "ai_introduce, ai_prompt, rule_payload_json, delivery_policy_json, resume_file, " +
                        "created_at, updated_at) VALUES('旧模板', '后端', '旧描述', 0, '{\"cfg\":1}', " +
                        "'招呼语', '提示词', '{\"rules\":[1]}', '{\"maxNewJobs\":10}', 'old.pdf', " +
                        "'2026-11-01 10:00:00', '2026-11-01 10:00:00')");
        // 模拟停在 v10 的旧库：没有 sort_order 列、没有 v11 记录
        downgradeToVersion10(dataSource);

        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertEquals(13, scalar(dataSource, "SELECT MAX(version) FROM schema_migration"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('profile_template') WHERE name = 'sort_order'"),
                "V11 必须补出 sort_order 列");
        // 旧数据保留：sort_order 为 NULL，其它字段不再变化
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM profile_template WHERE name = '旧模板'"));
        assertNull(scalarString(dataSource,
                "SELECT sort_order FROM profile_template WHERE name = '旧模板'"));
        assertEquals("{\"maxNewJobs\":10}", scalarString(dataSource,
                "SELECT delivery_policy_json FROM profile_template WHERE name = '旧模板'"));
        assertEquals("后端", scalarString(dataSource,
                "SELECT direction FROM profile_template WHERE name = '旧模板'"));
        // 幂等：重复迁移不重复加列
        migration.migrate();
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('profile_template') WHERE name = 'sort_order'"));
    }

    @Test
    void migratesFromVersionElevenToThirteenOnly() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("v11.db"));
        PersistenceTestSupport.migrate(dataSource);
        executeScript(dataSource,
                "INSERT INTO profile_template(name, created_at, updated_at) " +
                        "VALUES('旧模板', '2026-11-01 10:00:00', '2026-11-01 10:00:00')");
        // 模拟停在 v11 的旧库：没有 D2 两张表、没有 v12 记录
        downgradeToVersion11(dataSource);

        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertEquals(13, scalar(dataSource, "SELECT MAX(version) FROM schema_migration"));
        for (String table : List.of("delivery_status_sync", "delivery_status_snapshot", "manual_release")) {
            assertEquals(1, scalar(dataSource,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", table),
                    "V12 缺表：" + table);
        }
        // 旧表数据保留
        assertEquals("旧模板", scalarString(dataSource,
                "SELECT name FROM profile_template LIMIT 1"));
        // 幂等：重复迁移不重复建表
        migration.migrate();
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'delivery_status_sync'"));
    }

    @Test
    void migratesFromVersionTwelveToThirteenOnly() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("v12.db"));
        PersistenceTestSupport.migrate(dataSource);
        executeScript(dataSource,
                "INSERT INTO profile_template(name, created_at, updated_at) " +
                        "VALUES('旧模板', '2026-11-01 10:00:00', '2026-11-01 10:00:00')",
                "INSERT INTO delivery_status_sync(sync_id, trigger_type, scope, target_date, status, " +
                        "started_at) VALUES('sync-old', 'STARTUP', 'TODAY', '2026-11-07', 'COMPLETED', " +
                        "'2026-11-07 09:00:00')");
        // 模拟停在 v12 的旧库：没有 manual_release、没有 v13 记录
        downgradeToVersion12(dataSource);

        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertEquals(13, scalar(dataSource, "SELECT MAX(version) FROM schema_migration"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'manual_release'"),
                "V13 必须建出 manual_release 表");
        // 旧模板/同步数据保留
        assertEquals("旧模板", scalarString(dataSource, "SELECT name FROM profile_template LIMIT 1"));
        assertEquals("sync-old", scalarString(dataSource, "SELECT sync_id FROM delivery_status_sync LIMIT 1"));
        assertEquals(2, scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('manual_release') WHERE name IN " +
                        "('release_id','evaluation_id')"), "manual_release 列定义异常");
        // 幂等：重复迁移不重复建表
        migration.migrate();
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'manual_release'"));
    }

    @Test
    void migratesFromVersionEightToThirteenOnly() {
        SQLiteDataSource dataSource =
                PersistenceTestSupport.dataSource(directory.resolve("v8.db"));
        PersistenceTestSupport.migrate(dataSource);
        // 模拟停在 v8 的旧库
        downgradeToVersion8(dataSource);

        SchemaMigrationService migration = PersistenceTestSupport.migrate(dataSource);

        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertEquals(13, scalar(dataSource, "SELECT MAX(version) FROM schema_migration"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'profile_template'"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_profile_template_active'"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('profile_template') WHERE name = 'delivery_policy_json'"));
        assertEquals(1, scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('profile_template') WHERE name = 'sort_order'"));
    }

    /** 把已完成迁移的临时库降级到 v12：撤掉 V13 的表与版本记录。 */
    private void downgradeToVersion12(SQLiteDataSource dataSource) {
        executeScript(dataSource,
                "DROP TABLE IF EXISTS manual_release",
                "DELETE FROM schema_migration WHERE version = 13",
                "PRAGMA user_version = 12");
    }

    /** 把已完成迁移的临时库降级到 v11：撤掉 V12/V13 的表与版本记录。 */
    private void downgradeToVersion11(SQLiteDataSource dataSource) {
        downgradeToVersion12(dataSource);
        executeScript(dataSource,
                "DROP TABLE IF EXISTS delivery_status_snapshot",
                "DROP TABLE IF EXISTS delivery_status_sync",
                "DELETE FROM schema_migration WHERE version = 12",
                "PRAGMA user_version = 11");
    }

    /** 把已完成迁移的临时库降级到 v10：撤掉 V11/V12 的列与版本记录。 */
    private void downgradeToVersion10(SQLiteDataSource dataSource) {
        downgradeToVersion11(dataSource);
        executeScript(dataSource,
                "ALTER TABLE profile_template DROP COLUMN sort_order",
                "DELETE FROM schema_migration WHERE version = 11",
                "PRAGMA user_version = 10");
    }

    /** 把已完成迁移的临时库降级到 v9：撤掉 V10/V11/V12 的列与版本记录。 */
    private void downgradeToVersion9(SQLiteDataSource dataSource) {
        downgradeToVersion10(dataSource);
        executeScript(dataSource,
                "ALTER TABLE profile_template DROP COLUMN delivery_policy_json",
                "DELETE FROM schema_migration WHERE version = 10",
                "PRAGMA user_version = 9");
    }

    /** 把已完成迁移的临时库降级到 v8：撤掉 V9–V13 的表/索引与版本记录。 */
    private void downgradeToVersion8(SQLiteDataSource dataSource) {
        executeScript(dataSource,
                "DROP TABLE IF EXISTS profile_template",
                "DELETE FROM schema_migration WHERE version IN (9, 10, 11, 12, 13)",
                "PRAGMA user_version = 8");
    }

    /** 把已完成迁移的临时库降级到 v7：撤掉 V9 + V8 的表/列/索引与版本记录。 */
    private void downgradeToVersion7(SQLiteDataSource dataSource) {
        downgradeToVersion8(dataSource);
        executeScript(dataSource,
                "DROP TABLE job_source",
                "DELETE FROM schema_migration WHERE version = 8",
                "PRAGMA user_version = 7");
        // boss_data 若存在（真实旧库/显式建表场景），同时撤掉 B4 三列与索引（可选列先判断）
        Integer columns = scalar(dataSource,
                "SELECT COUNT(*) FROM pragma_table_info('boss_data') WHERE name = 'first_seen_at'");
        if (columns != null && columns > 0) {
            executeScript(dataSource,
                    "DROP INDEX IF EXISTS idx_boss_data_first_seen",
                    "ALTER TABLE boss_data DROP COLUMN first_seen_at",
                    "ALTER TABLE boss_data DROP COLUMN last_detail_at",
                    "ALTER TABLE boss_data DROP COLUMN snapshot_hash");
        }
    }

    /** 把已完成迁移的临时库降级到 v6：撤掉 V8 + V7 的表/列/索引与版本记录。 */
    private void downgradeToVersion6(SQLiteDataSource dataSource) {
        downgradeToVersion7(dataSource);
        executeScript(dataSource,
                "DROP TABLE followup_plan",
                "DROP TABLE contact_state",
                "DROP INDEX IF EXISTS idx_delivery_attempt_contact",
                "ALTER TABLE delivery_attempt DROP COLUMN contact_key",
                "ALTER TABLE delivery_attempt DROP COLUMN parent_attempt_id",
                "DELETE FROM schema_migration WHERE version = 7",
                "PRAGMA user_version = 6");
    }

    /** 把已完成迁移的临时库降级到 v3：依次撤掉 V7/V6/V5/V4 的表/列/索引与版本记录。 */
    private void downgradeToVersion3(SQLiteDataSource dataSource) {
        downgradeToVersion6(dataSource);
        executeScript(dataSource,
                // V6
                "ALTER TABLE rule_config_version DROP COLUMN live_hash",
                "DELETE FROM schema_migration WHERE version = 6",
                "PRAGMA user_version = 5",
                // V5
                "DROP TABLE rule_config_version",
                "DROP INDEX IF EXISTS idx_job_evaluation_encrypt_latest",
                "ALTER TABLE job_evaluation DROP COLUMN rule_config_version",
                "ALTER TABLE job_evaluation DROP COLUMN rules_hash",
                "DELETE FROM schema_migration WHERE version = 5",
                "PRAGMA user_version = 4",
                // V4
                "DROP TABLE manual_action",
                "DROP INDEX IF EXISTS idx_task_run_started",
                "DROP INDEX IF EXISTS idx_job_evaluation_run",
                "DROP INDEX IF EXISTS idx_delivery_attempt_run_started",
                "DROP INDEX IF EXISTS idx_delivery_attempt_encrypt_started",
                "DROP INDEX IF EXISTS idx_delivery_attempt_text_status",
                "ALTER TABLE delivery_attempt DROP COLUMN version",
                "ALTER TABLE delivery_attempt DROP COLUMN manual_resolution",
                "ALTER TABLE delivery_attempt DROP COLUMN resolved_at",
                "ALTER TABLE delivery_attempt DROP COLUMN resolve_reason",
                "DELETE FROM schema_migration WHERE version = 4",
                "PRAGMA user_version = 3");
    }

    /** 逐列校验表结构，DDL 与冻结契约不一致时测试失败。 */
    private void assertColumns(SQLiteDataSource dataSource, String table, List<String> expectedColumns) {
        List<String> actualColumns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (resultSet.next()) {
                actualColumns.add(resultSet.getString("name").toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取表结构失败：" + table, e);
        }
        assertEquals(expectedColumns, actualColumns, "表 " + table + " 的列定义应与接口约定逐字一致");
    }
}











