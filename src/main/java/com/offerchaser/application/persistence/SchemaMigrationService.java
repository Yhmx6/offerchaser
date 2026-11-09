package com.offerchaser.application.persistence;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据库架构迁移服务。
 * <p>
 * 版本通过 {@code PRAGMA user_version} 与 {@code schema_migration} 表双记录，每步幂等：
 * <ul>
 *   <li>V1：创建任务运行、岗位评估、投递台账、投递事件、每日预算、跟进记录等基础表；</li>
 *   <li>V2：在 boss_data 无重复数据时创建安全唯一索引（有重复则只告警跳过，绝不删数据）；</li>
 *   <li>V3：创建逐笔预算预留表 budget_reservation（A-05 逐笔预算 + 原子结算）；</li>
 *   <li>V4：投递尝试增加人工核对字段 + manual_action 审计表 + B1 管理台查询索引；</li>
 *   <li>V5：规则配置版本表 rule_config_version + 岗位评估规则版本/哈希字段 + 重评查询索引；</li>
 *   <li>V6：规则配置版本增加 live_hash（记录生成版本时 live 配置的哈希，避免自动同步误覆盖显式保存）；</li>
 *   <li>V7：联系人状态 contact_state + 跟进计划 followup_plan + 投递尝试联系人关联字段（B3）；</li>
 *   <li>V8：采集来源 job_source 表 + boss_data 采集时间/快照哈希字段（B4）。</li>
 *   <li>V9：方向模板 profile_template 表及 active 索引（B6）；</li>
 *   <li>V10：方向模板增加采集策略 delivery_policy_json（D1）；</li>
 *   <li>V11：方向模板增加自定义排序 sort_order（D1 排序）；</li>
 *   <li>V12：投递状态同步两表 delivery_status_sync / delivery_status_snapshot（D2）；</li>
 *   <li>V13：人工放行表 manual_release（D3-B）。</li>
 * </ul>
 * 迁移前会用 {@code VACUUM INTO} 备份；迁移失败时旧数据保持原样，{@link #isReady()} 返回 false，
 * 上层（任务启动预检）据此阻止投递。
 */
@Service
@Slf4j
public class SchemaMigrationService {

    /** 目标架构版本 */
    public static final int TARGET_VERSION = 13;

    private static final String DESCRIPTION_V1 =
            "V1 建表：任务运行/岗位评估/投递台账/投递事件/每日预算/跟进记录";
    private static final String DESCRIPTION_V2 =
            "V2 boss_data 安全唯一索引（encrypt_id + encrypt_user_id）";
    private static final String DESCRIPTION_V3 =
            "V3 逐笔预算预留表 budget_reservation 及索引";
    private static final String DESCRIPTION_V4 =
            "V4 投递尝试人工核对字段 + manual_action 审计表 + B1 查询索引";
    private static final String DESCRIPTION_V5 =
            "V5 规则配置版本表 + 岗位评估规则版本/哈希字段 + 重评索引";
    private static final String DESCRIPTION_V6 =
            "V6 规则配置版本增加 live_hash（live 配置哈希）";
    private static final String DESCRIPTION_V7 =
            "V7 联系人状态表 + 跟进计划表 + 投递尝试联系人关联字段（B3）";
    private static final String DESCRIPTION_V8 =
            "V8 采集来源表 job_source + boss_data 采集时间/快照哈希字段（B4）";
    private static final String DESCRIPTION_V9 =
            "V9 方向模板表 profile_template 及 active 索引（B6）";
    private static final String DESCRIPTION_V10 =
            "V10 方向模板采集策略 delivery_policy_json（D1）";
    private static final String DESCRIPTION_V11 =
            "V11 方向模板自定义排序 sort_order（D1 排序）";
    private static final String DESCRIPTION_V12 =
            "V12 投递状态同步表 delivery_status_sync + delivery_status_snapshot（D2）";
    private static final String DESCRIPTION_V13 =
            "V13 人工放行表 manual_release（D3-B）";

    /**
     * V1 全部 DDL。表结构是团队接口契约的一部分，必须与《优化实施-阶段A-接口约定》第 4.1 节逐字一致，
     * 修改前必须先与 Lead 确认。
     */
    private static final String[] DDL_V1 = {
            "CREATE TABLE IF NOT EXISTS schema_migration (\n" +
                    "  version INTEGER PRIMARY KEY, description TEXT NOT NULL, applied_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS task_run (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL UNIQUE, platform TEXT NOT NULL,\n" +
                    "  state TEXT NOT NULL, config_snapshot TEXT, stop_reason TEXT, error TEXT,\n" +
                    "  started_at TEXT NOT NULL, ended_at TEXT, updated_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS job_evaluation (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL,\n" +
                    "  encrypt_id TEXT, encrypt_user_id TEXT, job_name TEXT, company_name TEXT, job_url TEXT,\n" +
                    "  source_keyword TEXT, source_city TEXT, snapshot_hash TEXT, rule_version TEXT,\n" +
                    "  rule_decision TEXT, rule_reason_code TEXT, rule_evidence TEXT,\n" +
                    "  ai_decision TEXT, ai_error_code TEXT, ai_analysis TEXT, ai_message TEXT,\n" +
                    "  prompt_version TEXT, ai_model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER,\n" +
                    "  duration_ms INTEGER, created_at TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_job_evaluation_encrypt ON job_evaluation(encrypt_id)",
            "CREATE INDEX IF NOT EXISTS idx_job_evaluation_created ON job_evaluation(created_at)",
            "CREATE TABLE IF NOT EXISTS delivery_attempt (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT, attempt_id TEXT NOT NULL UNIQUE,\n" +
                    "  run_id TEXT NOT NULL, account TEXT NOT NULL, kind TEXT NOT NULL,\n" +
                    "  encrypt_id TEXT, encrypt_user_id TEXT, job_name TEXT, company_name TEXT, job_url TEXT,\n" +
                    "  snapshot_hash TEXT, message_hash TEXT, resume_hash TEXT,\n" +
                    "  text_status TEXT NOT NULL, image_status TEXT NOT NULL,\n" +
                    "  text_evidence TEXT, image_evidence TEXT, error_code TEXT, error_detail TEXT,\n" +
                    "  budget_id INTEGER, started_at TEXT NOT NULL, finished_at TEXT, created_at TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_attempt_encrypt ON delivery_attempt(encrypt_id)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_attempt_run ON delivery_attempt(run_id)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_attempt_started ON delivery_attempt(started_at)",
            "CREATE TABLE IF NOT EXISTS delivery_event (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT, attempt_id TEXT, run_id TEXT,\n" +
                    "  event_type TEXT NOT NULL, evidence_level TEXT, detail TEXT, occurred_at TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_event_occurred ON delivery_event(occurred_at)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_event_attempt ON delivery_event(attempt_id)",
            "CREATE TABLE IF NOT EXISTS daily_budget (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT, account TEXT NOT NULL, budget_date TEXT NOT NULL,\n" +
                    "  action_type TEXT NOT NULL, budget_limit INTEGER NOT NULL,\n" +
                    "  consumed INTEGER NOT NULL DEFAULT 0, reserved INTEGER NOT NULL DEFAULT 0,\n" +
                    "  uncertain INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL,\n" +
                    "  UNIQUE(account, budget_date, action_type))",
            "CREATE TABLE IF NOT EXISTS contact_followup (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT, account TEXT NOT NULL, encrypt_id TEXT NOT NULL,\n" +
                    "  encrypt_user_id TEXT, job_name TEXT, followup_count INTEGER NOT NULL DEFAULT 0,\n" +
                    "  last_followup_at TEXT, manual_takeover INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL,\n" +
                    "  UNIQUE(account, encrypt_id))"
    };

    /** V2：boss_data 无重复数据时才创建的安全唯一索引（部分索引，允许空值行存在）。 */
    private static final String UNIQUE_INDEX_BOSS_DATA_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_boss_data_job ON boss_data(encrypt_id, encrypt_user_id) " +
                    "WHERE encrypt_id IS NOT NULL AND encrypt_user_id IS NOT NULL";

    private static final String DUPLICATE_BOSS_DATA_SQL =
            "SELECT encrypt_id, encrypt_user_id, COUNT(*) AS duplicate_count FROM boss_data " +
                    "WHERE encrypt_id IS NOT NULL AND encrypt_user_id IS NOT NULL " +
                    "GROUP BY encrypt_id, encrypt_user_id HAVING COUNT(*) > 1 LIMIT 1";

    /**
     * V3 全部 DDL：逐笔预算预留表及索引。表结构是团队接口契约的一部分，
     * 必须与《阶段A+修复-接口约定》第 4.1 节逐字一致；已发布迁移不得修改，只能追加新版本。
     */
    private static final String[] DDL_V3 = {
            "CREATE TABLE IF NOT EXISTS budget_reservation (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "  reservation_id TEXT NOT NULL UNIQUE,\n" +
                    "  attempt_id TEXT,\n" +
                    "  budget_id INTEGER NOT NULL,\n" +
                    "  account TEXT NOT NULL,\n" +
                    "  action_type TEXT NOT NULL,\n" +
                    "  budget_date TEXT NOT NULL,\n" +
                    "  state TEXT NOT NULL DEFAULT 'RESERVED',   -- RESERVED / CONFIRMED / UNKNOWN / RELEASED\n" +
                    "  version INTEGER NOT NULL DEFAULT 0,\n" +
                    "  created_at TEXT NOT NULL,\n" +
                    "  settled_at TEXT\n" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_budget_reservation_state ON budget_reservation(state)",
            "CREATE INDEX IF NOT EXISTS idx_budget_reservation_budget ON budget_reservation(budget_id)",
            "CREATE INDEX IF NOT EXISTS idx_budget_reservation_attempt ON budget_reservation(attempt_id)"
    };

    /**
     * V4 全部 DDL：投递尝试人工核对字段 + manual_action 审计表 + B1 管理台查询索引。
     * 表结构是团队接口契约的一部分，必须与《阶段B-B1-实施契约》第 1 节逐字一致；
     * 已发布迁移不得修改，只能追加新版本。
     */
    private static final String[] DDL_V4 = {
            "ALTER TABLE delivery_attempt ADD COLUMN version INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE delivery_attempt ADD COLUMN manual_resolution TEXT",
            "ALTER TABLE delivery_attempt ADD COLUMN resolved_at TEXT",
            "ALTER TABLE delivery_attempt ADD COLUMN resolve_reason TEXT",
            "CREATE TABLE IF NOT EXISTS manual_action (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "  object_type TEXT NOT NULL,          -- 'delivery_attempt'\n" +
                    "  object_id TEXT NOT NULL,\n" +
                    "  action TEXT NOT NULL,               -- SENT / NOT_SENT / STILL_UNKNOWN\n" +
                    "  before_state TEXT,\n" +
                    "  after_state TEXT,\n" +
                    "  reason TEXT,\n" +
                    "  idempotency_key TEXT UNIQUE,\n" +
                    "  created_at TEXT NOT NULL\n" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_manual_action_object ON manual_action(object_type, object_id)",
            "CREATE INDEX IF NOT EXISTS idx_task_run_started ON task_run(started_at)",
            "CREATE INDEX IF NOT EXISTS idx_job_evaluation_run ON job_evaluation(run_id)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_attempt_run_started ON delivery_attempt(run_id, started_at)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_attempt_encrypt_started ON delivery_attempt(encrypt_id, started_at)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_attempt_text_status ON delivery_attempt(text_status)"
    };

    /**
     * V5 全部 DDL：规则配置版本表 + 岗位评估规则版本/哈希字段 + 重评查询索引。
     * 表结构是团队接口契约的一部分，必须与《阶段B-B2-实施契约》第 1 节逐字一致；
     * 已发布迁移不得修改，只能追加新版本。
     */
    private static final String[] DDL_V5 = {
            "CREATE TABLE IF NOT EXISTS rule_config_version (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "  version INTEGER NOT NULL UNIQUE,\n" +
                    "  rules_json TEXT NOT NULL,\n" +
                    "  source_hash TEXT,\n" +
                    "  note TEXT,\n" +
                    "  created_at TEXT NOT NULL\n" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_rule_config_version_created ON rule_config_version(created_at)",
            "CREATE INDEX IF NOT EXISTS idx_rule_config_version_version ON rule_config_version(version)",
            "ALTER TABLE job_evaluation ADD COLUMN rule_config_version INTEGER",
            "ALTER TABLE job_evaluation ADD COLUMN rules_hash TEXT",
            "CREATE INDEX IF NOT EXISTS idx_job_evaluation_encrypt_latest ON job_evaluation(encrypt_id, id DESC)"
    };

    /**
     * V6 DDL：规则配置版本增加 live_hash。只追加一列，V1–V5 不动。
     */
    private static final String[] DDL_V6 = {
            "ALTER TABLE rule_config_version ADD COLUMN live_hash TEXT"
    };

    /**
     * V7 DDL：联系人状态 + 跟进计划两表及索引、投递尝试联系人关联字段。
     * 表结构是团队接口契约的一部分，必须与《阶段B-B3-实施契约》第 1 节逐字一致；
     * 已发布迁移不得修改，只能追加新版本。
     */
    private static final String[] DDL_V7 = {
            "CREATE TABLE IF NOT EXISTS contact_state (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "  account TEXT NOT NULL,\n" +
                    "  platform TEXT NOT NULL,\n" +
                    "  contact_key TEXT NOT NULL,              -- \"uid:<encryptUserId>\"；无可信 ID 不落表\n" +
                    "  encrypt_user_id TEXT,\n" +
                    "  hr_name TEXT, company_name TEXT,\n" +
                    "  reply_state TEXT NOT NULL DEFAULT 'UNKNOWN',  -- UNKNOWN/REPLIED/REJECTED/INTERVIEW/NO_REPLY\n" +
                    "  manual_takeover INTEGER NOT NULL DEFAULT 0,\n" +
                    "  stop_contact INTEGER NOT NULL DEFAULT 0,\n" +
                    "  last_contact_at TEXT,\n" +
                    "  last_reply_at TEXT,\n" +
                    "  version INTEGER NOT NULL DEFAULT 0,\n" +
                    "  created_at TEXT NOT NULL,\n" +
                    "  updated_at TEXT NOT NULL,\n" +
                    "  UNIQUE(account, platform, contact_key)\n" +
                    ")",
            "CREATE TABLE IF NOT EXISTS followup_plan (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "  plan_id TEXT NOT NULL UNIQUE,\n" +
                    "  account TEXT NOT NULL,\n" +
                    "  platform TEXT NOT NULL,\n" +
                    "  encrypt_id TEXT,\n" +
                    "  encrypt_user_id TEXT,\n" +
                    "  contact_key TEXT,\n" +
                    "  job_name TEXT, company_name TEXT,\n" +
                    "  plan_type TEXT NOT NULL,                -- FOLLOWUP / IMAGE_RESEND\n" +
                    "  status TEXT NOT NULL,                   -- SUGGESTED/APPROVED/REJECTED/EXECUTED/CANCELLED/EXPIRED\n" +
                    "  reason TEXT,\n" +
                    "  parent_attempt_id TEXT,                 -- IMAGE_RESEND 指向原 attempt\n" +
                    "  version INTEGER NOT NULL DEFAULT 0,\n" +
                    "  suggested_at TEXT NOT NULL,\n" +
                    "  decided_at TEXT,\n" +
                    "  executed_at TEXT,\n" +
                    "  executed_attempt_id TEXT,\n" +
                    "  created_at TEXT NOT NULL,\n" +
                    "  updated_at TEXT NOT NULL\n" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_contact_state_key ON contact_state(account, platform, contact_key)",
            "CREATE INDEX IF NOT EXISTS idx_contact_state_reply ON contact_state(reply_state, updated_at)",
            "CREATE INDEX IF NOT EXISTS idx_followup_plan_status ON followup_plan(status, suggested_at)",
            "CREATE INDEX IF NOT EXISTS idx_followup_plan_contact ON followup_plan(account, platform, contact_key, status)",
            "CREATE INDEX IF NOT EXISTS idx_followup_plan_job ON followup_plan(account, platform, encrypt_id, plan_type, status)",
            "ALTER TABLE delivery_attempt ADD COLUMN contact_key TEXT",
            "ALTER TABLE delivery_attempt ADD COLUMN parent_attempt_id TEXT",
            "CREATE INDEX IF NOT EXISTS idx_delivery_attempt_contact ON delivery_attempt(account, contact_key)"
    };

    /**
     * V8 DDL（job_source 部分）：采集来源关联表及索引。
     * 表结构是团队接口契约的一部分，必须与《阶段B-B4-实施契约》第 1 节逐字一致。
     */
    private static final String[] DDL_V8_JOB_SOURCE = {
            "CREATE TABLE IF NOT EXISTS job_source (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "  account TEXT NOT NULL,\n" +
                    "  platform TEXT NOT NULL,\n" +
                    "  encrypt_id TEXT NOT NULL,          -- 稳定岗位 ID（与 boss_data 相同）\n" +
                    "  keyword TEXT,                      -- 搜索关键词\n" +
                    "  city_code TEXT,                    -- 搜索城市码\n" +
                    "  discovered_at TEXT NOT NULL,       -- 首次发现时间（本次采集）\n" +
                    "  snapshot_hash TEXT,\n" +
                    "  created_at TEXT NOT NULL,\n" +
                    "  UNIQUE(account, platform, encrypt_id, keyword)\n" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_job_source_time ON job_source(discovered_at)",
            "CREATE INDEX IF NOT EXISTS idx_job_source_keyword ON job_source(account, platform, keyword, discovered_at)",
            "CREATE INDEX IF NOT EXISTS idx_job_source_job ON job_source(account, platform, encrypt_id)"
    };

    /**
     * V8 DDL（boss_data 部分）：采集时间/快照哈希字段 + 索引。
     * boss_data 是旧库表（不由迁移创建），缺失时跳过（由 BossService.ensureBossDataColumnOrder 兜底补列）。
     */
    private static final String[] DDL_V8_BOSS_DATA = {
            "ALTER TABLE boss_data ADD COLUMN first_seen_at TEXT",
            "ALTER TABLE boss_data ADD COLUMN last_detail_at TEXT",
            "ALTER TABLE boss_data ADD COLUMN snapshot_hash TEXT",
            "CREATE INDEX IF NOT EXISTS idx_boss_data_first_seen ON boss_data(first_seen_at)"
    };

    /**
     * V9 DDL：方向模板表及 active 索引。
     * 表结构是团队接口契约的一部分，已发布迁移不得修改，只能追加新版本。
     */
    private static final String[] DDL_V9 = {
            "CREATE TABLE IF NOT EXISTS profile_template (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "  name TEXT NOT NULL UNIQUE,\n" +
                    "  direction TEXT,\n" +
                    "  description TEXT,\n" +
                    "  active INTEGER NOT NULL DEFAULT 0,\n" +
                    "  boss_config_json TEXT,\n" +
                    "  ai_introduce TEXT,\n" +
                    "  ai_prompt TEXT,\n" +
                    "  rule_payload_json TEXT,\n" +
                    "  resume_file TEXT,\n" +
                    "  created_at TEXT NOT NULL,\n" +
                    "  updated_at TEXT NOT NULL\n" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_profile_template_active ON profile_template(active)"
    };

    /**
     * V10 DDL：方向模板增加采集策略 JSON。只追加一列，V1–V9 不动。
     */
    private static final String[] DDL_V10 = {
            "ALTER TABLE profile_template ADD COLUMN delivery_policy_json TEXT"
    };

    /**
     * V11 DDL：方向模板增加自定义排序字段。只追加一列，V1–V10 不动。
     */
    private static final String[] DDL_V11 = {
            "ALTER TABLE profile_template ADD COLUMN sort_order INTEGER"
    };

    /**
     * V12 DDL：投递状态同步运行表 + 快照明细表及索引（D2）。
     * 表结构是团队接口契约的一部分，已发布迁移不得修改，只能追加新版本。
     */
    private static final String[] DDL_V12 = {
            "CREATE TABLE IF NOT EXISTS delivery_status_sync (\n" +
                    "  sync_id TEXT PRIMARY KEY,\n" +
                    "  trigger_type TEXT NOT NULL,\n" +
                    "  scope TEXT NOT NULL,\n" +
                    "  target_date TEXT NOT NULL,\n" +
                    "  status TEXT NOT NULL,\n" +
                    "  started_at TEXT NOT NULL,\n" +
                    "  finished_at TEXT,\n" +
                    "  auto_total INTEGER NOT NULL DEFAULT 0,\n" +
                    "  auto_read INTEGER NOT NULL DEFAULT 0,\n" +
                    "  auto_replied INTEGER NOT NULL DEFAULT 0,\n" +
                    "  auto_unread INTEGER NOT NULL DEFAULT 0,\n" +
                    "  auto_unknown INTEGER NOT NULL DEFAULT 0,\n" +
                    "  manual_total INTEGER NOT NULL DEFAULT 0,\n" +
                    "  manual_read INTEGER NOT NULL DEFAULT 0,\n" +
                    "  manual_replied INTEGER NOT NULL DEFAULT 0,\n" +
                    "  manual_unread INTEGER NOT NULL DEFAULT 0,\n" +
                    "  manual_unknown INTEGER NOT NULL DEFAULT 0,\n" +
                    "  hr_total INTEGER NOT NULL DEFAULT 0,\n" +
                    "  hr_read INTEGER NOT NULL DEFAULT 0,\n" +
                    "  hr_replied INTEGER NOT NULL DEFAULT 0,\n" +
                    "  hr_unread INTEGER NOT NULL DEFAULT 0,\n" +
                    "  hr_unknown INTEGER NOT NULL DEFAULT 0,\n" +
                    "  error TEXT\n" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_delivery_status_sync_trigger " +
                    "ON delivery_status_sync(trigger_type, target_date)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_status_sync_started " +
                    "ON delivery_status_sync(started_at)",
            "CREATE TABLE IF NOT EXISTS delivery_status_snapshot (\n" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "  sync_id TEXT NOT NULL,\n" +
                    "  source TEXT NOT NULL,\n" +
                    "  contact_key TEXT,\n" +
                    "  encrypt_user_id TEXT,\n" +
                    "  hr_name TEXT,\n" +
                    "  company_name TEXT,\n" +
                    "  job_name TEXT,\n" +
                    "  delivered_status TEXT,\n" +
                    "  read_status TEXT,\n" +
                    "  reply_status TEXT,\n" +
                    "  initiator TEXT,\n" +
                    "  last_message TEXT,\n" +
                    "  chat_time TEXT,\n" +
                    "  evidence TEXT,\n" +
                    "  created_at TEXT NOT NULL\n" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_delivery_status_snapshot_sync " +
                    "ON delivery_status_snapshot(sync_id)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_status_snapshot_source " +
                    "ON delivery_status_snapshot(source)"
    };

    /**
     * V13 DDL：人工放行表及索引（D3-B）：按 job_evaluation.id 唯一记录人工批准/拒绝/消费结果。
     * 表结构是团队接口契约的一部分，已发布迁移不得修改，只能追加新版本。
     */
    private static final String[] DDL_V13 = {
            "CREATE TABLE IF NOT EXISTS manual_release (\n" +
                    "  release_id TEXT PRIMARY KEY,\n" +
                    "  evaluation_id INTEGER NOT NULL UNIQUE,\n" +
                    "  status TEXT NOT NULL DEFAULT 'APPROVED',\n" +
                    "  reason TEXT,\n" +
                    "  requested_at TEXT NOT NULL,\n" +
                    "  decided_at TEXT,\n" +
                    "  consumed_at TEXT,\n" +
                    "  attempt_id TEXT,\n" +
                    "  error TEXT,\n" +
                    "  created_at TEXT NOT NULL,\n" +
                    "  updated_at TEXT NOT NULL\n" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_manual_release_status ON manual_release(status)",
            "CREATE INDEX IF NOT EXISTS idx_manual_release_evaluation ON manual_release(evaluation_id)",
            "CREATE INDEX IF NOT EXISTS idx_manual_release_requested ON manual_release(requested_at)"
    };

    private final DataSource dataSource;
    private final DatabaseMaintenanceService databaseMaintenanceService;

    /** 迁移是否成功完成；未完成时上层不得执行投递任务。 */
    private volatile boolean ready = false;
    /** 最近一次迁移后的架构版本；-1 表示尚未执行过迁移。 */
    private volatile int currentVersion = -1;
    /** 最近一次迁移前生成的备份文件路径，未备份时为 null。 */
    private volatile Path lastBackupPath;
    /** 迁移成功后对最新 pre-upgrade 备份做完整性校验的结果；无备份为 null。 */
    private volatile Boolean latestPreUpgradeBackupVerified;

    public SchemaMigrationService(DataSource dataSource,
                                  DatabaseMaintenanceService databaseMaintenanceService) {
        this.dataSource = dataSource;
        this.databaseMaintenanceService = databaseMaintenanceService;
    }

    /**
     * 执行架构迁移（应用启动时由 {@code @PostConstruct} 调用）。
     * <p>
     * 幂等：版本已达标时直接返回。失败不抛异常（避免应用无法启动、无法查看报错页面），
     * 而是落 ERROR 日志并让 {@link #isReady()} 保持 false，旧数据不会被删除或覆盖。
     */
    @PostConstruct
    public synchronized void migrate() {
        lastBackupPath = null;
        latestPreUpgradeBackupVerified = null;
        try (Connection connection = dataSource.getConnection()) {
            int version = readEffectiveVersion(connection);
            currentVersion = version;
            if (version >= TARGET_VERSION) {
                ready = true;
                log.info("数据库架构已是目标版本 v{}，无需迁移", version);
                return;
            }
            log.info("开始数据库架构迁移：v{} -> v{}", version, TARGET_VERSION);
            // 迁移前备份：已有表（可能是旧版本数据）时必须先备份；全新空库没有数据可丢，跳过备份。
            if (version > 0 || hasUserTables(connection)) {
                lastBackupPath = databaseMaintenanceService.backup("pre-upgrade");
                if (lastBackupPath == null) {
                    log.warn("迁移前未能生成备份文件（数据库可能不是文件型），继续执行迁移");
                }
            }
            applyMigrations(connection, version);
            currentVersion = TARGET_VERSION;
            ready = true;
            verifyLatestPreUpgradeBackup();
            log.info("数据库架构迁移完成，当前版本 v{}", currentVersion);
        } catch (Exception e) {
            ready = false;
            log.error("数据库架构迁移失败，持久化层不可用（isReady=false），旧数据保持不变：{}",
                    e.getMessage(), e);
        }
    }

    /** 迁移是否成功完成。 */
    public boolean isReady() {
        return ready;
    }

    /** 当前架构版本；迁移尚未执行时实时读取数据库。 */
    public int currentVersion() {
        int version = currentVersion;
        if (version >= 0) {
            return version;
        }
        try (Connection connection = dataSource.getConnection()) {
            return readEffectiveVersion(connection);
        } catch (Exception e) {
            log.debug("读取数据库架构版本失败：{}", e.getMessage());
            return -1;
        }
    }

    /** 最近一次迁移前生成的备份文件路径；未生成备份时返回 null。 */
    public Path lastBackupPath() {
        return lastBackupPath;
    }

    /** 迁移成功后对最新 pre-upgrade 备份的完整性校验结果；没有备份时为 null。 */
    public Boolean latestPreUpgradeBackupVerified() {
        return latestPreUpgradeBackupVerified;
    }

    /** 迁移成功后校验最新 pre-upgrade 备份可恢复性；失败只 warn，不阻断启动。 */
    private void verifyLatestPreUpgradeBackup() {
        try {
            java.util.List<java.util.Map<String, Object>> backups =
                    databaseMaintenanceService.listBackups("pre-upgrade", 1);
            if (backups.isEmpty()) {
                log.info("没有可校验的 pre-upgrade 备份（可能是全新库首次迁移）");
                return;
            }
            Object pathValue = backups.get(0).get("path");
            if (pathValue == null) {
                return;
            }
            java.util.Map<String, Object> verification =
                    databaseMaintenanceService.verifyBackup(Path.of(pathValue.toString()));
            boolean verified = Boolean.TRUE.equals(verification.get("verified"));
            latestPreUpgradeBackupVerified = verified;
            if (verified) {
                log.info("迁移前备份校验通过 | path={} | integrity={} | tableCount={} | schemaVersion={}",
                        pathValue, verification.get("integrity"),
                        verification.get("tableCount"), verification.get("schemaVersion"));
            } else {
                log.warn("迁移前备份校验失败（不阻断启动，但恢复前请人工核对）| path={} | reason={}",
                        pathValue, verification.get("reason"));
            }
        } catch (Exception e) {
            latestPreUpgradeBackupVerified = false;
            log.warn("迁移前备份校验异常（不阻断启动）：{}", e.getMessage());
        }
    }

    // ==================== 迁移执行 ====================

    private void applyMigrations(Connection connection, int fromVersion) throws Exception {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (int version = fromVersion + 1; version <= TARGET_VERSION; version++) {
                applyStep(connection, version);
            }
            connection.commit();
        } catch (Exception e) {
            try {
                connection.rollback();
                log.warn("数据库迁移事务已回滚，旧数据保持不变");
            } catch (SQLException rollbackError) {
                log.error("数据库迁移事务回滚失败：{}", rollbackError.getMessage());
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignore) {
                // 关闭连接时由连接池处理
            }
        }
    }

    private void applyStep(Connection connection, int version) throws SQLException {
        switch (version) {
            case 1 -> {
                for (String ddl : DDL_V1) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 1, DESCRIPTION_V1);
                setUserVersion(connection, 1);
                log.info("迁移 V1 完成：已创建 {} 条基础表/索引 DDL", DDL_V1.length);
            }
            case 2 -> {
                createBossDataUniqueIndexIfSafe(connection);
                recordMigration(connection, 2, DESCRIPTION_V2);
                setUserVersion(connection, 2);
                log.info("迁移 V2 完成：boss_data 唯一索引处理结束");
            }
            case 3 -> {
                for (String ddl : DDL_V3) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 3, DESCRIPTION_V3);
                setUserVersion(connection, 3);
                log.info("迁移 V3 完成：已创建 budget_reservation 表及 {} 条索引 DDL", DDL_V3.length - 1);
            }
            case 4 -> {
                for (String ddl : DDL_V4) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 4, DESCRIPTION_V4);
                setUserVersion(connection, 4);
                log.info("迁移 V4 完成：人工核对字段/审计表/查询索引共 {} 条 DDL", DDL_V4.length);
            }
            case 5 -> {
                for (String ddl : DDL_V5) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 5, DESCRIPTION_V5);
                setUserVersion(connection, 5);
                log.info("迁移 V5 完成：规则版本表/评估规则字段/重评索引共 {} 条 DDL", DDL_V5.length);
            }
            case 6 -> {
                for (String ddl : DDL_V6) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 6, DESCRIPTION_V6);
                setUserVersion(connection, 6);
                log.info("迁移 V6 完成：rule_config_version 增加 live_hash");
            }
            case 7 -> {
                for (String ddl : DDL_V7) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 7, DESCRIPTION_V7);
                setUserVersion(connection, 7);
                log.info("迁移 V7 完成：联系人/跟进两表 + 投递尝试联系人字段共 {} 条 DDL", DDL_V7.length);
            }
            case 8 -> {
                for (String ddl : DDL_V8_JOB_SOURCE) {
                    execute(connection, ddl);
                }
                if (tableExists(connection, "boss_data")) {
                    for (String ddl : DDL_V8_BOSS_DATA) {
                        execute(connection, ddl);
                    }
                } else {
                    log.warn("boss_data 表不存在，跳过 B4 的 first_seen_at/last_detail_at/snapshot_hash 列与索引；"
                            + "旧库首次建表后由 BossService.ensureBossDataColumnOrder 补列");
                }
                recordMigration(connection, 8, DESCRIPTION_V8);
                setUserVersion(connection, 8);
                log.info("迁移 V8 完成：job_source 表及索引 + boss_data 采集字段");
            }
            case 9 -> {
                for (String ddl : DDL_V9) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 9, DESCRIPTION_V9);
                setUserVersion(connection, 9);
                log.info("迁移 V9 完成：方向模板表 profile_template + active 索引");
            }
            case 10 -> {
                for (String ddl : DDL_V10) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 10, DESCRIPTION_V10);
                setUserVersion(connection, 10);
                log.info("迁移 V10 完成：profile_template 增加 delivery_policy_json");
            }
            case 11 -> {
                for (String ddl : DDL_V11) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 11, DESCRIPTION_V11);
                setUserVersion(connection, 11);
                log.info("迁移 V11 完成：profile_template 增加 sort_order");
            }
            case 12 -> {
                for (String ddl : DDL_V12) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 12, DESCRIPTION_V12);
                setUserVersion(connection, 12);
                log.info("迁移 V12 完成：delivery_status_sync + delivery_status_snapshot 两表及索引");
            }
            case 13 -> {
                for (String ddl : DDL_V13) {
                    execute(connection, ddl);
                }
                recordMigration(connection, 13, DESCRIPTION_V13);
                setUserVersion(connection, 13);
                log.info("迁移 V13 完成：manual_release 表及索引");
            }
            default -> throw new IllegalStateException("未知的数据库迁移版本：" + version);
        }
    }

    private void createBossDataUniqueIndexIfSafe(Connection connection) throws SQLException {
        if (!tableExists(connection, "boss_data")) {
            log.warn("boss_data 表不存在，跳过唯一索引创建（不视为迁移失败）");
            return;
        }
        try (Statement statement = connection.createStatement();
             ResultSet duplicates = statement.executeQuery(DUPLICATE_BOSS_DATA_SQL)) {
            if (duplicates.next()) {
                log.warn("boss_data 存在重复的 encrypt_id + encrypt_user_id 记录"
                                + "（例如 encrypt_id={}、encrypt_user_id={}），已跳过唯一索引创建，不删除任何数据；"
                                + "请人工清理重复数据后重新迁移",
                        duplicates.getString(1), duplicates.getString(2));
                return;
            }
        }
        execute(connection, UNIQUE_INDEX_BOSS_DATA_SQL);
        log.info("已创建 boss_data 安全唯一索引 uk_boss_data_job");
    }

    private void recordMigration(Connection connection, int version, String description) throws SQLException {
        String sql = "INSERT OR IGNORE INTO schema_migration(version, description, applied_at) VALUES(?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, version);
            statement.setString(2, description);
            statement.setString(3, PersistenceSupport.now());
            statement.executeUpdate();
        }
    }

    private void setUserVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // ==================== 版本读取 ====================

    /**
     * boss_data 必须具备的业务列（只补不删，类型与历史表结构保持一致）。
     * 表结构变更走「缺列补列」，绝不重建表。
     */
    private static final Map<String, String> BOSS_DATA_REQUIRED_COLUMNS = new LinkedHashMap<>();

    static {
        BOSS_DATA_REQUIRED_COLUMNS.put("encrypt_id", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("encrypt_user_id", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("company_name", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("job_name", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("salary", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("location", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("experience", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("degree", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("hr_name", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("hr_position", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("hr_active_status", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("delivery_status", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("job_description", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("job_url", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("recruitment_status", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("company_address", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("industry", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("introduce", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("financing_stage", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("company_scale", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("created_at", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("updated_at", "TEXT");
        // 阶段 B B4：采集溯源字段（旧库自动补列，旧数据为 null）
        BOSS_DATA_REQUIRED_COLUMNS.put("first_seen_at", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("last_detail_at", "TEXT");
        BOSS_DATA_REQUIRED_COLUMNS.put("snapshot_hash", "TEXT");
    }

    /**
     * 确保 boss_data 表具备全部业务列（SQLite 旧表结构补全，属于迁移职责）。
     * <p>
     * 只做只增不减的 {@code ALTER TABLE ADD COLUMN}：缺哪列补哪列，<b>绝不 DROP / RENAME 表</b>；
     * 补列失败只记 warn，不阻断启动。Boss 启动链通过
     * {@code BossService.ensureBossDataColumnOrder()} 委托到这里。
     */
    public static void ensureBossDataColumnOrder(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            Set<String> existingColumns = new LinkedHashSet<>();
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info('boss_data')")) {
                while (columns.next()) {
                    String name = columns.getString("name");
                    if (name != null) {
                        existingColumns.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
            if (existingColumns.isEmpty()) {
                log.debug("boss_data 表不存在，跳过列补全");
                return;
            }
            int added = 0;
            for (Map.Entry<String, String> required : BOSS_DATA_REQUIRED_COLUMNS.entrySet()) {
                if (existingColumns.contains(required.getKey())) {
                    continue;
                }
                try {
                    statement.executeUpdate("ALTER TABLE boss_data ADD COLUMN "
                            + required.getKey() + " " + required.getValue());
                    added++;
                    log.info("boss_data 补充缺失列：{} {}", required.getKey(), required.getValue());
                } catch (Exception alterError) {
                    log.warn("boss_data 补充列 {} 失败（已跳过，不影响既有数据）：{}",
                            required.getKey(), alterError.getMessage());
                }
            }
            if (added > 0) {
                log.info("boss_data 列补全完成，本次新增 {} 列", added);
            }
        } catch (Exception e) {
            log.warn("检查/补全 boss_data 列失败：{}", e.getMessage());
        }
    }

    /**
     * 有效版本 = max(PRAGMA user_version, schema_migration 中最大版本)。
     * 双记录可以覆盖“DDL 已执行但 user_version 未写入”这类中断场景，保证重跑时不会重复建表出错。
     */
    private int readEffectiveVersion(Connection connection) throws SQLException {
        int userVersion = queryInt(connection, "PRAGMA user_version");
        int recordedVersion = 0;
        if (tableExists(connection, "schema_migration")) {
            recordedVersion = queryInt(connection, "SELECT COALESCE(MAX(version), 0) FROM schema_migration");
        }
        return Math.max(userVersion, recordedVersion);
    }

    private int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean hasUserTables(Connection connection) throws SQLException {
        return queryInt(connection,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'") > 0;
    }
}
