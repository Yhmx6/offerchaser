# 02 · 运行与数据

## 1. 启动、停止与托盘

| 入口 | 行为 |
|---|---|
| `OfferChaser.vbs` | 无窗口拉起 `OfferChaser.ps1`：检测 9527 是否已在监听；未启动则用内置 JDK 启动后端，随后打开管理台并驻留托盘 |
| `启动.bat` | 控制台方式启动（调试用）：检查内置 JDK、清理外部 `SERVER_PORT` 覆盖、启动并等待就绪 |
| `停止OfferChaser.vbs` | 托盘“停止服务”：先请求后端停止投递并等待 worker 退出，再结束进程（不是强杀） |
| `start-local.ps1` | 开发模式：`gradlew bootRun`，读取 `db/offerchaser.db`，用于源码调试 |
| `打开Chrome.bat` | 手动打开本机 Chrome（调试账号/页面用，不启动系统） |

启动顺序（后端）：

1. 数据源连接 `db/offerchaser.db`；
2. `SchemaMigrationService` 检查/执行迁移（V1–V13，迁移前备份）；
3. `DefaultPageConfigInitializer` 对全新库写默认页面配置（已有数据/已写标记则跳过）；
4. 静态服务连接器探测 6866（占用则跳过，避免与前端 dev 冲突）；
5. `StartupRunner` 探测 6866→9527 并打开默认浏览器，然后初始化 Playwright（Chrome 持久化 profile）；
6. 投递状态补读在应用就绪 20s 后异步执行（不阻塞启动）。

停止：托盘停止会先调用后端停止接口，等 worker 线程退出后再结束进程；直接杀进程可能留下 PENDING 预算/尝试，
下次启动由 `reconcileStaleReservations` 回收（见 §5）。

## 2. 运行时端口与目录

| 路径/端口 | 说明 |
|---|---|
| `127.0.0.1:9527` | 后端 API；只绑定回环 |
| `127.0.0.1:6866` | 管理台静态页；dist 存在且端口空闲时监听 |
| `db/offerchaser.db` | SQLite 主库（V13） |
| `db/backups/pre-upgrade/` | 迁移前备份，保留最近 5 份 |
| `db/backups/pre-run/` | 任务前/手动备份，保留最近 10 份 |
| `data/local-session-token` | 本机会话令牌（敏感，不提交） |
| `data/boss/resume/resume.png` | 当前图片简历 |
| `data/boss/templates/<id>/` | 各方向模板独立简历 |
| `browser-data/` | Chrome 持久化 profile（含 BOSS 登录态，敏感） |
| `target/logs/offerchaser.log` | 应用日志（滚动：10MB × 30 份） |
| `target/logs/launcher.out.log` | 启动器 stdout/错误日志 |

## 3. 任务状态机

`task_run.state` 与内存 `BossRunContext` 一致：

```text
STARTING ──► RUNNING ──► COMPLETED
   │            │
   │            ├──► PAUSED_VERIFICATION ──resume──► RUNNING
   │            │            └──stop──► STOPPING ──► COMPLETED / FAILED
   │            └──stop──► STOPPING ──► COMPLETED / FAILED
   └──启动失败──► FAILED
```

规则：

- 同一时刻只允许一个投递任务；重复启动返回明确提示，不会产生第二个 runId。
- `STOPPING` 不是终态；必须等 worker 真正退出。终态且 worker 未退出时允许“强制复位”。
- 停止采用协作式：发停止指令后，发送前检查、身份检查、确认等待等节点都会再次检查；已发出的消息不可撤回。
- 事件流：`GET /api/boss/stream`（SSE）先发 `connected`，再发 `snapshot` 全量状态，然后持续 `progress`（带
  runId/seq/state）；30s 心跳；前端按 runId 过滤旧任务、按 seq 去重。
- `task_run.config_snapshot` 记录本轮 BOSS 配置、规则版本/哈希与生效模板（id/名称/方向），用于事后追溯；
  对外查询不返回原始快照，只返回模板三字段。

## 4. 数据库 V13：25 张表

`PRAGMA user_version=13`；`schema_migration` 记录 V1–V13 共 13 条迁移。表按职责分组：

### 4.1 任务与投递（7）

| 表 | 用途 |
|---|---|
| `task_run` | 每轮任务：run_id/状态/起止/停止原因/错误/配置快照 |
| `job_evaluation` | 岗位评估：规则版本与结论、AI 结论/分析/话术、模型用量、快照哈希 |
| `delivery_attempt` | 投递尝试：文字/图片状态、证据、预算预留、人工核对、版本 |
| `delivery_event` | 投递事件流：尝试/发送/确认/失败/核对等审计事件 |
| `budget_reservation` | 逐笔预算预留：状态机、attempt 绑定、金额与日期 |
| `daily_budget` | 每日预算聚合：预留/已用/不确定/上限（按账号/日期） |
| `contact_followup` | 旧跟进沟通记录（无跟进计划的补充记录） |

### 4.2 规则、模板与配置（9）

| 表 | 用途 |
|---|---|
| `rule_config_version` | 规则版本：payload、source_hash、live_hash（运行期只读版本） |
| `profile_template` | 方向模板：BOSS 配置/AI/规则/简历/采集策略/排序/是否生效 |
| `boss_config` | 当前 BOSS 搜索与投递开关 |
| `ai` | 当前 AI 介绍/提示词 |
| `config` | 通用配置键值（分类/类型） |
| `boss_blacklist` | 公司/岗位/招聘者黑名单 |
| `cookie` | 平台 Cookie（敏感，读取掩码） |
| `default_page_config_seed` | 默认页面配置已写入标记 |
| `schema_migration` | 迁移版本与描述 |

### 4.3 联系人、跟进与人工闭环（5）

| 表 | 用途 |
|---|---|
| `contact_state` | 联系人状态机、跨岗位去重、回复/拒绝/接管状态、版本 |
| `followup_plan` | 跟进计划：状态（建议/批准/拒绝/执行/失效）、间隔与次数 |
| `manual_action` | 人工核对操作审计（attempt 的 SENT/NOT_SENT/STILL_UNKNOWN） |
| `manual_release` | 人工放行：REVIEW 评估的唯一放行记录与状态 |
| `delivery_status_snapshot` | 单次状态同步中每个聊天的自动/手动/HR 分类快照 |

### 4.4 采集与统计（4）

| 表 | 用途 |
|---|---|
| `job_source` | 采集来源：关键词/城市/发现时间/快照哈希（幂等） |
| `boss_data` | 岗位数据快照（详情、HR、活跃状态、投递状态） |
| `boss_option` | BOSS 选项字典（城市/行业/薪资/学历等） |
| `delivery_status_sync` | 状态同步运行记录与三类统计计数 |

表总数：7 + 9 + 5 + 4 = **25**；另有 44 个业务索引（含唯一索引 `uk_boss_data_job`）。

## 5. 关键不变量

1. **任务单一**：内存 + `task_run` 共同保证同一时刻一个运行任务；旧 run 的停止/回调不得影响新 run。
2. **预算原子**：预留 `RESERVED → CONFIRMED/UNKNOWN/RELEASED` 只能按 reservation 状态机转移一次；
   聚合只在状态转移成功时更新；重复调用幂等；崩溃后的 RESERVED/PENDING 由启动回收转 UNKNOWN。
3. **投递台账不可回退**：`CONFIRMED` 不能被人工核对或重试改成未发送；人工核对有版本号与幂等键。
4. **规则版本只增**：运行中编辑规则只影响下一轮；每轮启动固定规则快照。
5. **模板唯一生效**：全库最多一个 active 模板；保存生效模板立即应用到当前配置；启动前再校正一次。
6. **人工放行不绕过安全**：`manual_release.APPROVED` 只表示下一轮候选；消费时重新 AI 评估，仍走身份/预算/文字+图片确认。
7. **状态同步只读**：读取聊天不发送消息；打开会话可能被 BOSS 标记已读，这是平台自身行为。
8. **迁移只追加**：已发布 DDL 不改；新版本只追加 DDL 与迁移记录；迁移失败 `isReady=false` 阻止任务启动。

## 6. 迁移与备份

- 迁移代码在 `SchemaMigrationService`：`TARGET_VERSION=13`；V2 为安全唯一索引逻辑，V8 拆两个 DDL 数组，其余为数组。
- 迁移前自动 `VACUUM INTO` 备份到 `db/backups/pre-upgrade/`，保留最近 5 份；空库/无表库跳过备份。
- 迁移成功后校验最新迁移前备份可打开（完整性检查）；失败则 `isReady=false`，任务启动被阻止。
- 任务开始前/手动维护备份写到 `db/backups/pre-run/`，保留最近 10 份；
  维护接口：`GET /api/maintenance/status`、`GET /api/maintenance/backups`、`POST /api/maintenance/backup`。
- 恢复流程：停止应用 → 用备份替换 `db/offerchaser.db` → 启动（自动按备份版本迁移到 V13）。
- 不建议手动 `DROP/ALTER` 业务表；结构变更一律新增迁移版本。

## 7. 默认数据与首次启动

- 全新库会写入默认 BOSS 配置/AI 内容/黑名单，并写 `default_page_config_seed` 标记；之后用户清空也不会自动回填。
- 已有任一配置表数据视为老库：保留用户设置，不写默认值，但仍写标记。
- 首次启动生成本机会话令牌文件；管理台需要解锁（见 [04-security](04-security.md)）。
- 首次使用需在配置中心配置模型服务/AI Key 并创建方向模板；真实投递仍需手动开始。
