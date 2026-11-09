package com.offerchaser.verification;

import com.offerchaser.application.persistence.SchemaMigrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.offerchaser.verification.VerificationSupport.countFiles;
import static com.offerchaser.verification.VerificationSupport.dataSource;
import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.indexExists;
import static com.offerchaser.verification.VerificationSupport.migrate;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static com.offerchaser.verification.VerificationSupport.sha256;
import static com.offerchaser.verification.VerificationSupport.tableCounts;
import static com.offerchaser.verification.VerificationSupport.tableNames;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 数据库迁移验收（人工放行 / V13 版：TARGET_VERSION=13）。
 * <p>
 * 不依赖个人真实库：测试内用代码构造一份<b>脱敏的 v0 固定夹具</b>（旧表 + 少量假数据），
 * 迁移到 v13 后断言旧表行数逐表不变、17 张新表齐全、V4–V13 新列/审计表/规则版本表/联系人/跟进表/
 * 采集来源表/方向模板表/状态同步两表/人工放行表/索引存在、备份可打开且是迁移前快照、二次迁移幂等；
 * 另有「模拟 v8 已迁移库升级到 v13 且旧数据保留」、已迁移 v13 幂等、v12→v13 专项、
 * 空库/新文件库，以及可选的“仓库存在 db/offerchaser.db 就复制副本迁移并比对 hash”用例。
 */
class RealDatabaseMigrationTest {

    /** 阶段 A/A+/B1–B5/方向模板/状态同步/人工放行 新增表：V1 的 7 张 + V3 budget_reservation + V4 manual_action
     *  + V5 rule_config_version + V7 contact_state/followup_plan + V8 job_source + V9 profile_template + V10 delivery_policy_json + V11 sort_order + V12 delivery_status_sync/delivery_status_snapshot
     *  + V13 manual_release。 */
    private static final List<String> NEW_TABLES = List.of(
            "schema_migration", "task_run", "job_evaluation", "delivery_attempt",
            "delivery_event", "daily_budget", "contact_followup", "budget_reservation",
            "manual_action", "rule_config_version", "contact_state", "followup_plan",
            "job_source", "profile_template", "delivery_status_sync", "delivery_status_snapshot",
            "manual_release");

    /** V1 delivery_attempt 原始列 + V4 人工核对列 + V7 联系人关联列。 */
    private static final List<String> DELIVERY_ATTEMPT_COLUMNS = List.of(
            "id", "attempt_id", "run_id", "account", "kind", "encrypt_id", "encrypt_user_id",
            "job_name", "company_name", "job_url", "snapshot_hash", "message_hash", "resume_hash",
            "text_status", "image_status", "text_evidence", "image_evidence", "error_code",
            "error_detail", "budget_id", "started_at", "finished_at", "created_at",
            // V4
            "version", "manual_resolution", "resolved_at", "resolve_reason",
            // V7
            "contact_key", "parent_attempt_id");

    /** V1 job_evaluation 原始列 + V5 规则版本/哈希列。 */
    private static final List<String> JOB_EVALUATION_COLUMNS = List.of(
            "id", "run_id", "encrypt_id", "encrypt_user_id", "job_name", "company_name", "job_url",
            "source_keyword", "source_city", "snapshot_hash", "rule_version", "rule_decision",
            "rule_reason_code", "rule_evidence", "ai_decision", "ai_error_code", "ai_analysis",
            "ai_message", "prompt_version", "ai_model", "prompt_tokens", "completion_tokens",
            "duration_ms", "created_at",
            // V5
            "rule_config_version", "rules_hash");

    /** V5 rule_config_version 原始列 + V6 live_hash 列。 */
    private static final List<String> RULE_CONFIG_VERSION_COLUMNS = List.of(
            "id", "version", "rules_json", "source_hash", "note", "created_at",
            // V6
            "live_hash");

    private static final List<String> CONTACT_STATE_COLUMNS = List.of(
            "id", "account", "platform", "contact_key", "encrypt_user_id", "hr_name", "company_name",
            "reply_state", "manual_takeover", "stop_contact", "last_contact_at", "last_reply_at",
            "version", "created_at", "updated_at");

    private static final List<String> FOLLOWUP_PLAN_COLUMNS = List.of(
            "id", "plan_id", "account", "platform", "encrypt_id", "encrypt_user_id", "contact_key",
            "job_name", "company_name", "plan_type", "status", "reason", "parent_attempt_id",
            "version", "suggested_at", "decided_at", "executed_at", "executed_attempt_id",
            "created_at", "updated_at");

    /** V8：采集来源关联表（同一岗位多关键词多行，唯一键防重复采集）。 */
    private static final List<String> JOB_SOURCE_COLUMNS = List.of(
            "id", "account", "platform", "encrypt_id", "keyword", "city_code",
            "discovered_at", "snapshot_hash", "created_at");

    /** V9：方向模板表（name 唯一，active 0/1，快照 JSON 与模板简历；V10 追加采集策略列、V11 追加 sort_order）。 */
    private static final List<String> PROFILE_TEMPLATE_COLUMNS = List.of(
            "id", "name", "direction", "description", "active", "boss_config_json",
            "ai_introduce", "ai_prompt", "rule_payload_json", "resume_file",
            "created_at", "updated_at", "delivery_policy_json", "sort_order");

    /** 脱敏夹具中 boss_data 的旧列（真实旧库结构）+ V8 追加的三列。 */
    private static final List<String> BOSS_DATA_COLUMNS = List.of(
            "id", "encrypt_id", "encrypt_user_id", "company_name", "job_name", "salary",
            "location", "experience", "degree", "hr_name", "hr_position", "hr_active_status",
            "delivery_status", "job_description", "job_url", "recruitment_status",
            "company_address", "industry", "introduce", "financing_stage", "company_scale",
            "created_at", "updated_at",
            // V8
            "first_seen_at", "last_detail_at", "snapshot_hash");

    private static final List<String> B1_INDEXES = List.of(
            "idx_manual_action_object",
            "idx_task_run_started",
            "idx_job_evaluation_run",
            "idx_delivery_attempt_run_started",
            "idx_delivery_attempt_encrypt_started",
            "idx_delivery_attempt_text_status");

    private static final List<String> B2_INDEXES = List.of(
            "idx_rule_config_version_created",
            "idx_rule_config_version_version",
            "idx_job_evaluation_encrypt_latest");

    private static final List<String> B3_INDEXES = List.of(
            "idx_contact_state_key",
            "idx_contact_state_reply",
            "idx_followup_plan_status",
            "idx_followup_plan_contact",
            "idx_followup_plan_job",
            "idx_delivery_attempt_contact");

    private static final List<String> B4_INDEXES = List.of(
            "idx_job_source_time",
            "idx_job_source_keyword",
            "idx_job_source_job",
            "idx_boss_data_first_seen");

    private static final List<String> V9_INDEXES = List.of("idx_profile_template_active");
    /** V12：状态同步运行表（每类来源的计数）与快照表。 */
    private static final List<String> DELIVERY_STATUS_SYNC_COLUMNS = List.of(
            "sync_id", "trigger_type", "scope", "target_date", "status", "started_at", "finished_at",
            "auto_total", "auto_read", "auto_replied", "auto_unread", "auto_unknown",
            "manual_total", "manual_read", "manual_replied", "manual_unread", "manual_unknown",
            "hr_total", "hr_read", "hr_replied", "hr_unread", "hr_unknown", "error");
    private static final List<String> DELIVERY_STATUS_SNAPSHOT_COLUMNS = List.of(
            "id", "sync_id", "source", "contact_key", "encrypt_user_id", "hr_name", "company_name",
            "job_name", "delivered_status", "read_status", "reply_status", "initiator",
            "last_message", "chat_time", "evidence", "created_at");
    private static final List<String> V12_INDEXES = List.of(
            "idx_delivery_status_sync_trigger", "idx_delivery_status_sync_started",
            "idx_delivery_status_snapshot_sync", "idx_delivery_status_snapshot_source");

    /** V13：人工放行表（一条 job_evaluation 至多一条放行记录，按岗位评估唯一）。 */
    private static final List<String> MANUAL_RELEASE_COLUMNS = List.of(
            "release_id", "evaluation_id", "status", "reason", "requested_at", "decided_at",
            "consumed_at", "attempt_id", "error", "created_at", "updated_at");
    private static final List<String> V13_INDEXES = List.of(
            "idx_manual_release_status", "idx_manual_release_evaluation", "idx_manual_release_requested");


    /** 脱敏夹具里的旧表（模拟真实库 v0 结构，含真实 boss_data 的列）。 */
    private static final List<String> LEGACY_TABLES = List.of(
            "ai", "boss_blacklist", "boss_config", "boss_data", "config");

    private static final Path REAL_DATABASE = Path.of("db", "offerchaser.db");

    @TempDir
    Path tempDir;

    @Test
    void sanitizedLegacyFixtureMigratesFromV0ToV13WithoutDataLoss() throws Exception {
        Path databaseFile = tempDir.resolve("legacy-v0").resolve("offerchaser.db");
        Files.createDirectories(databaseFile.getParent());
        SQLiteDataSource dataSource = createSanitizedLegacyFixture(databaseFile);

        Map<String, Integer> countsBefore = tableCounts(dataSource);
        assertFalse(tableNames(dataSource).contains("task_run"), "夹具应是迁移前的 v0 结构");
        assertEquals(0, scalar(dataSource, "PRAGMA user_version"));
        assertEquals(3, countsBefore.get("boss_data"), "夹具应包含 3 条假岗位数据");

        SchemaMigrationService migration = migrate(dataSource);

        assertTrue(migration.isReady(), "脱敏 v0 夹具迁移必须成功");
        assertEquals(SchemaMigrationService.TARGET_VERSION, migration.currentVersion());
        assertEquals(13, migration.currentVersion(), "方向模板+采集策略+排序+状态同步+人工放行目标版本应为 v13");
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));

        List<String> tablesAfter = tableNames(dataSource);
        for (String table : NEW_TABLES) {
            assertTrue(tablesAfter.contains(table), "迁移后缺少新表：" + table);
        }
        assertEquals(13, scalar(dataSource, "SELECT COUNT(*) FROM schema_migration"),
                "schema_migration 应记录 V1..V13 十三条版本");
        assertEquals(13, scalar(dataSource,
                "SELECT COUNT(*) FROM schema_migration WHERE version IN " +
                        "(1,2,3,4,5,6,7,8,9,10,11,12,13)"));

        Map<String, Integer> countsAfter = tableCounts(dataSource);
        for (String table : LEGACY_TABLES) {
            assertEquals(countsBefore.get(table), countsAfter.get(table),
                    "迁移后旧表行数发生变化：" + table);
        }
        assertEquals("ok", scalarString(dataSource, "PRAGMA integrity_check"));

        // V2 安全唯一索引（夹具身份数据无重复）
        assertTrue(indexExists(dataSource, "uk_boss_data_job"),
                "V2 应在 boss_data 上创建 (encrypt_id, encrypt_user_id) 唯一索引");
        // V3 逐笔预留表结构
        assertColumns(dataSource, "budget_reservation", List.of(
                "id", "reservation_id", "attempt_id", "budget_id", "account", "action_type",
                "budget_date", "state", "version", "created_at", "settled_at"));
        for (String index : List.of("idx_budget_reservation_state",
                "idx_budget_reservation_budget", "idx_budget_reservation_attempt")) {
            assertTrue(indexExists(dataSource, index), "缺少索引：" + index);
        }
        // V4：人工核对四列 + manual_action + B1 索引
        assertColumns(dataSource, "delivery_attempt", DELIVERY_ATTEMPT_COLUMNS);
        assertColumns(dataSource, "manual_action", List.of(
                "id", "object_type", "object_id", "action", "before_state", "after_state",
                "reason", "idempotency_key", "created_at"));
        for (String index : B1_INDEXES) {
            assertTrue(indexExists(dataSource, index), "缺少 B1 索引：" + index);
        }
        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM delivery_attempt WHERE version <> 0"), "旧数据 version 默认 0");
        // V5/V6：规则版本表 + 评估规则字段 + live_hash
        assertColumns(dataSource, "rule_config_version", RULE_CONFIG_VERSION_COLUMNS);
        assertColumns(dataSource, "job_evaluation", JOB_EVALUATION_COLUMNS);
        for (String index : B2_INDEXES) {
            assertTrue(indexExists(dataSource, index), "缺少 B2 索引：" + index);
        }
        // V7：联系人状态 + 跟进计划两表及索引
        assertColumns(dataSource, "contact_state", CONTACT_STATE_COLUMNS);
        assertColumns(dataSource, "followup_plan", FOLLOWUP_PLAN_COLUMNS);
        for (String index : B3_INDEXES) {
            assertTrue(indexExists(dataSource, index), "缺少 B3 索引：" + index);
        }
        // V8：采集来源表 + boss_data 三列/索引
        assertColumns(dataSource, "job_source", JOB_SOURCE_COLUMNS);
        assertColumns(dataSource, "boss_data", BOSS_DATA_COLUMNS);
        for (String index : B4_INDEXES) {
            assertTrue(indexExists(dataSource, index), "缺少 B4 索引：" + index);
        }
        assertEquals(3, scalar(dataSource,
                "SELECT COUNT(*) FROM boss_data WHERE first_seen_at IS NULL"),
                "旧岗位数据 first_seen_at 应为 null（legacy）");
        // V9：方向模板表 + active 索引
        assertColumns(dataSource, "profile_template", PROFILE_TEMPLATE_COLUMNS);
        for (String index : V9_INDEXES) {
            assertTrue(indexExists(dataSource, index), "缺少 V9 索引：" + index);
        }
        // V12：投递状态同步两表（运行记录 + 快照）及索引
        assertColumns(dataSource, "delivery_status_sync", DELIVERY_STATUS_SYNC_COLUMNS);
        assertColumns(dataSource, "delivery_status_snapshot", DELIVERY_STATUS_SNAPSHOT_COLUMNS);
        for (String index : V12_INDEXES) {
            assertTrue(indexExists(dataSource, index), "缺少 V12 索引：" + index);
        }
        // V13：人工放行表（列逐列 + 3 个索引，旧数据不自动生成放行记录）
        assertColumns(dataSource, "manual_release", MANUAL_RELEASE_COLUMNS);
        for (String index : V13_INDEXES) {
            assertTrue(indexExists(dataSource, index), "缺少 V13 索引：" + index);
        }
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM manual_release"),
                "迁移不得自动为历史 REVIEW 评估生成放行记录（需人工决策）");
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM delivery_status_sync"));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM delivery_status_snapshot"));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM profile_template"));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM job_source"));

        // ===== 迁移前备份：可打开、是迁移前快照 =====
        Path backup = migration.lastBackupPath();
        assertNotNull(backup, "已有旧表的库升级前必须自动备份");
        assertTrue(Files.isRegularFile(backup), "备份文件不存在：" + backup);
        assertEquals(databaseFile.getParent().resolve("backups").resolve("pre-upgrade")
                        .toAbsolutePath().normalize(),
                backup.getParent().toAbsolutePath().normalize(),
                "迁移前备份应位于数据库同级 backups/pre-upgrade 目录");
        assertTrue(backup.getFileName().toString()
                        .matches("offerchaser-\\d{8}-\\d{6}-pre-upgrade\\.db"),
                "备份命名应为 offerchaser-<yyyyMMdd-HHmmss>-pre-upgrade.db，实际："
                        + backup.getFileName());
        assertEquals(Boolean.TRUE, migration.latestPreUpgradeBackupVerified(),
                "迁移成功后必须校验最新升级前备份可恢复");
        SQLiteDataSource backupDataSource = dataSource(backup);
        assertEquals(0, scalar(backupDataSource, "PRAGMA user_version"), "备份应是迁移前版本");
        assertEquals(0, scalar(backupDataSource,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' " +
                                "AND name IN ('budget_reservation','manual_action','rule_config_version'," +
                                "'contact_state','followup_plan','job_source','profile_template'," +
                                "'delivery_status_sync','delivery_status_snapshot','manual_release')"),
                "备份应是迁移前快照，不应包含 V3–V13 新表");
        assertEquals("ok", scalarString(backupDataSource, "PRAGMA integrity_check"));

        // ===== 二次迁移幂等：不备份、不改数据 =====
        int backupsBefore = countFiles(databaseFile.getParent().resolve("backups").resolve("pre-upgrade"),
                "offerchaser-", ".db");
        migration.migrate();
        assertTrue(migration.isReady());
        assertEquals(13, migration.currentVersion());
        assertEquals(backupsBefore,
                countFiles(databaseFile.getParent().resolve("backups").resolve("pre-upgrade"),
                        "offerchaser-", ".db"),
                "版本已达标时二次迁移不应再生成备份");
        assertEquals(13, scalar(dataSource, "SELECT COUNT(*) FROM schema_migration"));
        assertNull(migration.lastBackupPath(), "幂等迁移直接返回时不应记录新的备份路径");
    }

    /**
     * 方向模板专项：模拟一个已经迁移到 v8 的库（反射应用 V1–V8 的 DDL 并写入旧业务数据），
     * 再升级到 v13，断言旧数据保留且补齐 V9 结构、V10 采集策略列、V11 sort_order、
     * V12 状态同步两表与 V13 人工放行表。
     */
    @Test
    void simulatedV8DatabaseUpgradesToV13AndPreservesData() throws Exception {
        Path databaseFile = tempDir.resolve("v8-upgrade").resolve("offerchaser.db");
        Files.createDirectories(databaseFile.getParent());
        SQLiteDataSource dataSource = createSimulatedV8Database(databaseFile);

        execute(dataSource, "INSERT INTO task_run(run_id, platform, state, started_at, updated_at) " +
                "VALUES('run-v8', 'boss', 'COMPLETED', '2026-11-06 10:00:00', '2026-11-06 10:30:00')");
        execute(dataSource, "INSERT INTO contact_state(account, platform, contact_key, reply_state, " +
                "version, created_at, updated_at) VALUES('local','boss','uid:U8','REPLIED',1," +
                "'2026-11-06 10:00:00','2026-11-06 10:00:00')");
        execute(dataSource, "INSERT INTO rule_config_version(version, rules_json, source_hash, live_hash, " +
                "created_at) VALUES(8, '{}', 'hash-v8', 'live-v8', '2026-11-06 10:00:00')");
        Map<String, Integer> countsBefore = tableCounts(dataSource);
        assertEquals(8, scalar(dataSource, "PRAGMA user_version"));

        SchemaMigrationService migration = migrate(dataSource);

        assertTrue(migration.isReady(), "v8→v13 升级必须成功");
        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertNotNull(migration.lastBackupPath(), "已有数据的 v8 库升级前必须备份");
        assertTrue(Files.isRegularFile(migration.lastBackupPath()));

        Map<String, Integer> countsAfter = tableCounts(dataSource);
        for (Map.Entry<String, Integer> entry : countsBefore.entrySet()) {
            if ("schema_migration".equals(entry.getKey())) {
                continue; // 迁移记账表会 +1
            }
            assertEquals(entry.getValue(), countsAfter.get(entry.getKey()),
                    "v8→v13 升级后旧表行数变化：" + entry.getKey());
        }
        assertEquals("COMPLETED", scalarString(dataSource,
                "SELECT state FROM task_run WHERE run_id='run-v8'"));
        assertEquals("REPLIED", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key='uid:U8'"));
        assertEquals("hash-v8", scalarString(dataSource,
                "SELECT source_hash FROM rule_config_version WHERE version=8"));
        assertTrue(tableNames(dataSource).contains("profile_template"));
        assertColumns(dataSource, "profile_template", PROFILE_TEMPLATE_COLUMNS);
        assertTrue(indexExists(dataSource, "idx_profile_template_active"));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM profile_template"));
        // V12 + V13 表也应在 v8 升级后补齐
        assertTrue(tableNames(dataSource).contains("delivery_status_sync"));
        assertTrue(tableNames(dataSource).contains("delivery_status_snapshot"));
        assertColumns(dataSource, "manual_release", MANUAL_RELEASE_COLUMNS);
        for (String index : V13_INDEXES) {
            assertTrue(indexExists(dataSource, index), "缺少 V13 索引：" + index);
        }

        // 再次迁移幂等
        int backups = countFiles(databaseFile.getParent().resolve("backups").resolve("pre-upgrade"),
                "offerchaser-", ".db");
        migration.migrate();
        assertEquals(13, migration.currentVersion());
        assertEquals(backups, countFiles(databaseFile.getParent().resolve("backups").resolve("pre-upgrade"),
                "offerchaser-", ".db"));
    }

    @Test
    void alreadyMigratedV13DatabaseIsNoOpOnSecondRun() throws Exception {
        Path databaseFile = tempDir.resolve("already-v13").resolve("offerchaser.db");
        Files.createDirectories(databaseFile.getParent());
        SQLiteDataSource dataSource = dataSource(databaseFile);
        SchemaMigrationService first = migrate(dataSource);
        assertTrue(first.isReady());
        assertEquals(13, first.currentVersion());

        execute(dataSource, "INSERT INTO profile_template(name, active, boss_config_json, created_at, updated_at) " +
                "VALUES('产品方向', 1, '{}', '2026-11-06 12:00:00', '2026-11-06 12:00:00')");
        execute(dataSource, "INSERT INTO manual_release(release_id, evaluation_id, status, reason, " +
                "requested_at, created_at, updated_at) VALUES('rel-keep', 100, 'APPROVED', '保留检查', " +
                "'2026-11-07 10:00:00', '2026-11-07 10:00:00', '2026-11-07 10:00:00')");
        Map<String, Integer> countsBefore = tableCounts(dataSource);

        SchemaMigrationService second = new SchemaMigrationService(dataSource,
                new com.offerchaser.application.persistence.DatabaseMaintenanceService(dataSource));
        second.migrate();

        assertTrue(second.isReady());
        assertEquals(13, second.currentVersion());
        assertNull(second.lastBackupPath(), "已迁移到目标版本的库不应再备份");
        assertEquals(countsBefore, tableCounts(dataSource), "二次 migrate 不得修改任何表数据");
        assertEquals("产品方向", scalarString(dataSource,
                "SELECT name FROM profile_template WHERE active=1"));
        assertEquals("APPROVED", scalarString(dataSource,
                "SELECT status FROM manual_release WHERE release_id='rel-keep'"),
                "已存在的人工放行记录不得被幂等迁移修改");
    }

    /**
     * V13 专项：模拟已经迁移到 v12 的库（有历史模板数据、历史 REVIEW 评估、无人工放行表），
     * 升级到 v13 后补齐 manual_release 表、旧数据保留、表数量 +1，且重复迁移幂等、不重复备份。
     */
    @Test
    void simulatedV12DatabaseUpgradesToV13AndPreservesData() throws Exception {
        Path databaseFile = tempDir.resolve("v12-upgrade").resolve("offerchaser.db");
        Files.createDirectories(databaseFile.getParent());
        SQLiteDataSource dataSource = dataSource(databaseFile);
        migrate(dataSource);
        execute(dataSource, "INSERT INTO profile_template(name, direction, description, active, " +
                "boss_config_json, ai_introduce, ai_prompt, rule_payload_json, delivery_policy_json, " +
                "resume_file, created_at, updated_at, sort_order) VALUES('产品方向', '产品', '旧描述', 1, " +
                "'{\"keywords\":[\"Java\"]}', '旧介绍', '旧提示词', '{\"rules\":[1]}', " +
                "'{\"collectMaxPerKeyword\":12,\"collectMaxDurationMinutes\":4,\"collectNoNewWindowSeconds\":9}', " +
                "'old.png', '2026-11-06 10:00:00', '2026-11-06 10:00:00', 0)");
        execute(dataSource, "INSERT INTO job_evaluation(run_id, encrypt_id, encrypt_user_id, job_name, " +
                "company_name, job_url, source_keyword, snapshot_hash, rule_decision, rule_reason_code, " +
                "rule_evidence, ai_decision, ai_analysis, created_at) VALUES('run-v12', 'ENC-REVIEW-1', " +
                "'USER-REVIEW-1', 'Java 开发', '旧公司', 'https://www.zhipin.com/job_detail/fake1.html', " +
                "'Java', 'snapshot-v12', 'REVIEW', 'SALARY_REVIEW', '薪资需人工确认', 'REVIEW', " +
                "'AI 也不确定，需要人工复核', '2026-11-06 10:10:00')");
        // 模拟停在 v12 的旧库：撤掉 V13 人工放行表与迁移记录
        execute(dataSource, "DROP TABLE IF EXISTS manual_release");
        execute(dataSource, "DELETE FROM schema_migration WHERE version = 13");
        execute(dataSource, "PRAGMA user_version = 12");
        assertEquals(12, scalar(dataSource, "PRAGMA user_version"));
        java.util.Set<String> tablesBefore = new java.util.LinkedHashSet<>(tableNames(dataSource));
        assertFalse(tablesBefore.contains("manual_release"), "v12 模拟库不应已有人工放行表");

        SchemaMigrationService migration = migrate(dataSource);

        assertTrue(migration.isReady(), "v12→v13 升级必须成功");
        assertEquals(13, migration.currentVersion());
        assertEquals(13, scalar(dataSource, "PRAGMA user_version"));
        assertNotNull(migration.lastBackupPath(), "已有数据的 v12 库升级前必须备份");
        java.util.Set<String> tablesAfter = new java.util.LinkedHashSet<>(tableNames(dataSource));
        assertTrue(tablesAfter.contains("manual_release"), "V13 必须补出 manual_release");
        assertEquals(tablesBefore.size() + 1, tablesAfter.size(), "V13 只应新增 manual_release 一张表");
        assertColumns(dataSource, "manual_release", MANUAL_RELEASE_COLUMNS);
        for (String index : V13_INDEXES) {
            assertTrue(indexExists(dataSource, index), "缺少 V13 索引：" + index);
        }
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM manual_release"),
                "V13 迁移不得自动为历史 REVIEW 评估生成放行记录");
        // 旧模板数据保留（含 V10 采集策略与 V11 排序）
        assertEquals("产品方向", scalarString(dataSource,
                "SELECT name FROM profile_template WHERE active=1"));
        assertEquals("{\"rules\":[1]}", scalarString(dataSource,
                "SELECT rule_payload_json FROM profile_template WHERE active=1"));
        assertEquals("{\"collectMaxPerKeyword\":12,\"collectMaxDurationMinutes\":4," +
                        "\"collectNoNewWindowSeconds\":9}",
                scalarString(dataSource,
                        "SELECT delivery_policy_json FROM profile_template WHERE active=1"));
        assertEquals(0, scalar(dataSource, "SELECT sort_order FROM profile_template WHERE active=1"));
        // 历史 REVIEW 评估保留，可供人工放行队列读取（此处只验证迁移未丢数据）
        assertEquals("REVIEW", scalarString(dataSource,
                "SELECT rule_decision FROM job_evaluation WHERE encrypt_id='ENC-REVIEW-1'"));
        assertEquals("AI 也不确定，需要人工复核", scalarString(dataSource,
                "SELECT ai_analysis FROM job_evaluation WHERE encrypt_id='ENC-REVIEW-1'"));
        // 幂等：重复迁移不重复建表/索引、不生成新备份
        int backups = countFiles(databaseFile.getParent().resolve("backups").resolve("pre-upgrade"),
                "offerchaser-", ".db");
        migration.migrate();
        assertEquals(13, migration.currentVersion());
        assertEquals(tablesAfter, new java.util.LinkedHashSet<>(tableNames(dataSource)));
        assertEquals(backups, countFiles(databaseFile.getParent().resolve("backups").resolve("pre-upgrade"),
                "offerchaser-", ".db"));
    }


    @Test
    void emptyAndNonExistingDatabasesMigrateToV13() throws Exception {
        Path emptyDatabase = tempDir.resolve("empty-offerchaser.db");
        Files.createFile(emptyDatabase);
        SQLiteDataSource emptySource = dataSource(emptyDatabase);
        SchemaMigrationService emptyMigration = migrate(emptySource);
        assertTrue(emptyMigration.isReady(), "空库迁移必须成功（最容易被忽略的路径）");
        assertEquals(13, emptyMigration.currentVersion());
        assertTrue(tableNames(emptySource).containsAll(NEW_TABLES));
        assertNull(emptyMigration.lastBackupPath(), "空库没有数据可丢，迁移前不需要备份");
        assertNull(emptyMigration.latestPreUpgradeBackupVerified(),
                "没有迁移前备份时校验结果应为 null");
        assertEquals(0, countFiles(tempDir.resolve("backups"), "offerchaser-", ".db"));

        Path brandNew = tempDir.resolve("brand-new.db");
        assertFalse(Files.exists(brandNew));
        SchemaMigrationService newMigration = migrate(dataSource(brandNew));
        assertTrue(Files.isRegularFile(brandNew));
        assertTrue(newMigration.isReady());
        assertEquals(13, newMigration.currentVersion());
    }

    /**
     * 真实库副本用例：仓库存在真实库时复制到 @TempDir 迁移（v13 已达标则幂等）并用 SHA-256
     * 证明测试没有改动真实库；同时断言副本具备 V13 人工放行表/索引，且历史 REVIEW 数据可由队列读取。
     * 该用例不依赖个人数据内容（只比对前后 hash），没有真实库时跳过。
     */
    @Test
    void realDatabaseCopyIsV13AndIsNotModifiedByTest() throws Exception {
        assumeTrue(Files.isRegularFile(REAL_DATABASE), "仓库无 db/offerchaser.db，跳过真实库副本用例");
        assumeTrue(Files.isReadable(REAL_DATABASE), "db/offerchaser.db 不可读，跳过真实库副本用例");

        String hashBefore = sha256(REAL_DATABASE);
        Path workDir = Files.createDirectories(tempDir.resolve("real-db-copy"));
        Path copy = workDir.resolve("offerchaser.db");
        Files.copy(REAL_DATABASE, copy, StandardCopyOption.REPLACE_EXISTING);
        assumeTrue(hashBefore.equals(sha256(copy)), "复制期间真实库被外部进程写入，跳过本次用例");

        SQLiteDataSource copySource = dataSource(copy);
        int versionBefore = scalar(copySource, "PRAGMA user_version");
        assertEquals(SchemaMigrationService.TARGET_VERSION, versionBefore,
                "门禁要求真实库 db/offerchaser.db 当前已是目标版本 v13（应用启动时应已完成迁移）");
        assertTrue(tableNames(copySource).contains("manual_release"),
                "真实库当前应已有 V13 manual_release 表");
        Map<String, Integer> countsBefore = tableCounts(copySource);
        int reviewBefore = scalar(copySource,
                "SELECT COUNT(*) FROM job_evaluation WHERE rule_decision='REVIEW' OR ai_decision='REVIEW'");

        SchemaMigrationService migration = migrate(copySource);
        assertTrue(migration.isReady(), "真实库副本迁移必须成功");
        assertEquals(SchemaMigrationService.TARGET_VERSION, migration.currentVersion());
        assertEquals(13, migration.currentVersion(), "真实库副本应为 v13");
        assertEquals(13, scalar(copySource, "PRAGMA user_version"), "真实库副本 user_version 应为 13");
        assertEquals(13, scalar(copySource, "SELECT MAX(version) FROM schema_migration"));
        Map<String, Integer> countsAfter = tableCounts(copySource);
        for (Map.Entry<String, Integer> entry : countsBefore.entrySet()) {
            if ("schema_migration".equals(entry.getKey())) {
                continue; // 迁移记账表会因本次升级变化，不属于用户数据
            }
            assertEquals(entry.getValue(), countsAfter.get(entry.getKey()),
                    "真实库副本迁移后旧表行数变化：" + entry.getKey());
        }
        assertTrue(tableNames(copySource).contains("profile_template"),
                "真实库副本迁移后应存在 profile_template 表");
        assertTrue(indexExists(copySource, "idx_profile_template_active"),
                "真实库副本迁移后应存在 V9 active 索引与 V10 采集策略列");
        // V13：真实库副本必须有人工放行表/索引，且历史 REVIEW 评估仍在（供队列读取）
        assertTrue(tableNames(copySource).contains("manual_release"),
                "真实库副本应存在 manual_release 表");
        assertColumns(copySource, "manual_release", MANUAL_RELEASE_COLUMNS);
        for (String index : V13_INDEXES) {
            assertTrue(indexExists(copySource, index), "真实库副本缺少 V13 索引：" + index);
        }
        assertEquals(reviewBefore, scalar(copySource,
                        "SELECT COUNT(*) FROM job_evaluation WHERE rule_decision='REVIEW' OR ai_decision='REVIEW'"),
                "真实库副本迁移后历史 REVIEW 评估不应丢失");
        if (versionBefore < SchemaMigrationService.TARGET_VERSION) {
            assertNotNull(migration.lastBackupPath(), "真实库副本升级前应自动备份");
            assertTrue(Files.isRegularFile(migration.lastBackupPath()));
        }
        assertEquals(hashBefore, sha256(REAL_DATABASE),
                "真实库 db/offerchaser.db 的 SHA-256 在测试前后不一致：测试改动了真实库");
    }

    // ==================== 脱敏夹具 ====================

    /**
     * 构造一份 v0 结构的脱敏夹具：包含真实库的旧表名与 boss_data 真实列，写入少量假数据。
     */
    private SQLiteDataSource createSanitizedLegacyFixture(Path databaseFile) {
        SQLiteDataSource dataSource = dataSource(databaseFile);
        execute(dataSource, "CREATE TABLE ai (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "introduce TEXT, prompt TEXT, created_at TEXT)");
        execute(dataSource, "CREATE TABLE config (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "config_key TEXT, config_value TEXT, config_type TEXT, category TEXT)");
        execute(dataSource, "CREATE TABLE boss_blacklist (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "type TEXT, value TEXT, created_at TEXT)");
        execute(dataSource, "CREATE TABLE boss_config (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "keywords TEXT, city_code TEXT, created_at TEXT)");
        createLegacyBossDataTable(dataSource);

        execute(dataSource, "INSERT INTO ai(introduce, prompt, created_at) VALUES('假介绍', '假提示词', " +
                "'2026-11-01 10:00:00')");
        execute(dataSource, "INSERT INTO config(config_key, config_value, config_type, category) " +
                "VALUES('BASE_URL', 'https://example.invalid/v1', 'string', 'ai'), " +
                "('API_KEY', 'FAKE-KEY-NOT-REAL', 'string', 'ai')");
        execute(dataSource, "INSERT INTO boss_blacklist(type, value, created_at) VALUES" +
                "('company', '假黑名单公司', '2026-11-01 10:00:00'), " +
                "('job', '假黑名单岗位', '2026-11-01 10:00:00')");
        execute(dataSource, "INSERT INTO boss_config(keywords, city_code, created_at) " +
                "VALUES('Java', '101020100', '2026-11-01 10:00:00')");
        execute(dataSource, "INSERT INTO boss_data(encrypt_id, encrypt_user_id, company_name, job_name, " +
                "salary, location, experience, degree, hr_name, hr_position, hr_active_status, delivery_status, " +
                "job_description, job_url, recruitment_status, created_at, updated_at) VALUES" +
                "('FAKE-ENC-1', 'FAKE-USER-1', '假公司A', 'Java 开发', '20-30K', '上海', '3-5年', '本科', " +
                "'张女士', '招聘专员', '今日活跃', '已投递', '假 JD 内容，用于迁移测试。', " +
                "'https://example.invalid/job/1', '招聘中', '2026-11-01 10:00:00', '2026-11-02 10:00:00'), " +
                "('FAKE-ENC-2', 'FAKE-USER-2', '假公司B', '后端开发', '25-35K', '北京', '3-5年', '硕士', " +
                "'李先生', '技术经理', '本周活跃', '未投递', '假 JD 内容，用于迁移测试。', " +
                "'https://example.invalid/job/2', '招聘中', '2026-11-01 11:00:00', '2026-11-02 11:00:00'), " +
                "('FAKE-ENC-3', 'FAKE-USER-3', '假公司C', '测试开发', '面议', '杭州', '1-3年', '本科', " +
                "'王女士', 'HR', '刚刚活跃', '已过滤', '假 JD 内容，用于迁移测试。', " +
                "'https://example.invalid/job/3', '招聘中', '2026-11-01 12:00:00', '2026-11-02 12:00:00')");
        return dataSource;
    }

    private void createLegacyBossDataTable(SQLiteDataSource dataSource) {
        execute(dataSource, "CREATE TABLE IF NOT EXISTS boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "encrypt_id TEXT, encrypt_user_id TEXT, company_name TEXT, job_name TEXT, salary TEXT, " +
                "location TEXT, experience TEXT, degree TEXT, hr_name TEXT, hr_position TEXT, " +
                "hr_active_status TEXT, delivery_status TEXT, job_description TEXT, job_url TEXT, " +
                "recruitment_status TEXT, company_address TEXT, industry TEXT, introduce TEXT, " +
                "financing_stage TEXT, company_scale TEXT, created_at TEXT, updated_at TEXT)");
    }

    /**
     * 模拟已迁移到 v8 的数据库：反射取 SchemaMigrationService 私有 DDL_V1..DDL_V8 逐条应用
     * （V2 是索引逻辑、V8 拆成两个数组），写入 schema_migration 记录并把 user_version 设为 8。
     */
    private SQLiteDataSource createSimulatedV8Database(Path databaseFile) throws Exception {
        SQLiteDataSource dataSource = dataSource(databaseFile);
        // 先建旧 boss_data 表：V8 的 boss_data DDL（ALTER 三列）依赖该表存在
        createLegacyBossDataTable(dataSource);
        for (int version = 1; version <= 8; version++) {
            if (version == 2) {
                // V2 不是 DDL 数组而是“boss_data 安全唯一索引”方法；夹具另行建表/索引即可。
                continue;
            }
            if (version == 8) {
                for (String name : List.of("DDL_V8_JOB_SOURCE", "DDL_V8_BOSS_DATA")) {
                    Field field = SchemaMigrationService.class.getDeclaredField(name);
                    field.setAccessible(true);
                    for (String ddl : (String[]) field.get(null)) {
                        execute(dataSource, ddl);
                    }
                }
                continue;
            }
            Field field = SchemaMigrationService.class.getDeclaredField("DDL_V" + version);
            field.setAccessible(true);
            String[] ddls = (String[]) field.get(null);
            for (String ddl : ddls) {
                execute(dataSource, ddl);
            }
        }
        execute(dataSource, "INSERT INTO schema_migration(version, description, applied_at) " +
                "VALUES(1, 'V1 模拟', '2026-11-04 09:00:00'), (2, 'V2 模拟', '2026-11-04 09:00:00'), " +
                "(3, 'V3 模拟', '2026-11-04 09:00:00'), (4, 'V4 模拟', '2026-11-04 09:00:00'), " +
                "(5, 'V5 模拟', '2026-11-04 09:00:00'), (6, 'V6 模拟', '2026-11-04 09:00:00'), " +
                "(7, 'V7 模拟', '2026-11-04 09:00:00'), (8, 'V8 模拟', '2026-11-04 09:00:00')");
        execute(dataSource, "PRAGMA user_version = 8");
        assertEquals(8, scalar(dataSource, "PRAGMA user_version"));
        for (String required : List.of("task_run", "job_evaluation", "delivery_attempt", "delivery_event",
                "daily_budget", "contact_followup", "budget_reservation", "rule_config_version",
                "contact_state", "followup_plan", "job_source", "boss_data")) {
            assertTrue(tableNames(dataSource).contains(required), "v8 模拟库缺少表：" + required);
        }
        assertFalse(tableNames(dataSource).contains("profile_template"), "v8 模拟库不应已有 V9/V10 表");
        return dataSource;
    }

    /** 逐列校验表结构（新表/新增列与 V13 契约不一致时失败）。 */
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
        assertEquals(expectedColumns, actualColumns, "表 " + table + " 的列定义应与 V13 契约一致");
    }
}