# 03 · API

## 1. 基本约定

- 后端地址：`http://127.0.0.1:9527`；管理台静态页：`http://127.0.0.1:6866`（页面不是 API）。
- 响应：只读/业务接口通常为 `{ "success": true, "data": ... }`；少数旧式接口直接返回对象（如部分 BOSS 状态）。
  `success=false` 时附 `code`/`message`（或仅 message），客户端按 HTTP 状态 + code 处理。
- 时间格式：字符串 `yyyy-MM-dd HH:mm:ss`（本机时区 `Asia/Shanghai` 用于预算/状态同步日期）。
- 认证（`offerchaser.security.auth-enabled=true` 时）：
  - 只读 `GET/HEAD/OPTIONS /api/**` 默认放行（本地回环）；
  - **例外（必须认证）**：`/api/cookie*`、`/api/boss/reload`、`/api/playwright/**`；
  - 所有写方法（POST/PUT/DELETE）必须带本机会话 Cookie `OFFERCHASER_SESSION`（或 `X-Session-Token`），
    且带 `X-Requested-With: offerchaser`（CSRF），否则 401/403；
  - `/api/session/*` 为登录相关公开路径。
- 错误语义：参数/业务校验 400；未认证 401；缺少 CSRF 头 403；版本/状态冲突 409（规则版本、人工核对、
  人工放行、状态同步 busy）；资源不存在 404；内部错误 500。

常用 code：`UNAUTHORIZED`、`CSRF_HEADER_MISSING`、`RULE_VERSION_CONFLICT`、`RULES_INVALID`、
`VERSION_CONFLICT`、`ALREADY_CONFIRMED`、`STATUS_SYNC_BUSY_OR_UNAVAILABLE`、`TEMPLATE_*`、`MANUAL_RELEASE_*`。

## 2. 接口分组

### 2.1 会话与健康

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/session/status` | 认证开关/是否已认证/初始化状态/令牌文件提示 |
| POST | `/api/session/login` | 用本机令牌登录，建立会话 Cookie |
| POST | `/api/session/logout` | 注销会话 |
| GET | `/api/health` | 健康检查（Actuator 兜底） |
| GET | `/api/capabilities` | 项目启用的平台（当前仅 `boss`） |

### 2.2 运行控制（BossController）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss` | 运行概览（平台/配置摘要） |
| POST | `/api/boss/execute` | 执行投递（等价入口，保留） |
| POST | `/api/boss/start` | 开始投递 |
| POST | `/api/boss/stop?runId=` | 停止投递（可绑定 runId，避免误停新任务） |
| POST | `/api/boss/logout` | 退出 BOSS 登录态 |
| GET | `/api/boss/status` | 当前运行状态/预算/待核对/流程快照（SSE 快照同源） |
| GET | `/api/boss/stream` | SSE：connected → snapshot → progress（runId/seq/state）+ 心跳 |
| POST | `/api/boss/verification/resume` | 人工安全验证后恢复任务 |
| POST | `/api/boss/force-reset` | 强制复位（仅终态且 worker 未退出时；需 confirm） |
| GET | `/api/boss/delivery/summary` | 今日预算/投递/评估汇总 |

### 2.3 BOSS 配置、选项、黑名单、简历

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/config` | 当前 BOSS 配置（含选项/黑名单） |
| GET | `/api/boss/config/options/{type}` | 选项字典（city/industry/experience/jobType/salary/degree/scale/stage） |
| PUT | `/api/boss/config` | 保存当前 BOSS 配置（写） |
| GET | `/api/boss/config/blacklist` | 黑名单列表 |
| POST | `/api/boss/config/blacklist` | 新增黑名单（写） |
| DELETE | `/api/boss/config/blacklist/{id}` | 删除黑名单（写） |
| POST | `/api/boss/config/resume-image` | 上传当前图片简历（multipart，写） |
| GET | `/api/boss/config/resume-image/content` | 当前简历预览（只读） |

### 2.4 方向模板（ProfileTemplateController）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/templates` | 模板列表 + 当前生效模板 |
| GET | `/api/boss/templates/active` | 当前生效模板 |
| GET | `/api/boss/templates/{id}` | 模板详情（解析后的 BOSS 配置/规则/策略/AI/简历路径） |
| POST | `/api/boss/templates` | 从当前配置创建模板（写） |
| PUT | `/api/boss/templates/{id}` | 修改名称/方向/描述（写） |
| PUT | `/api/boss/templates/{id}/config` | 整包编辑模板配置（搜索/薪资/公司要求/规则/AI；写） |
| PUT | `/api/boss/templates/order` | 保存拖拽排序（全量 id；写） |
| POST | `/api/boss/templates/{id}/activate` | 激活并应用模板（写） |
| POST | `/api/boss/templates/{id}/capture` | 用当前配置覆盖模板快照（写） |
| POST | `/api/boss/templates/{id}/duplicate` | 复制模板（写） |
| DELETE | `/api/boss/templates/{id}` | 删除模板（生效模板不可删；写） |
| POST | `/api/boss/templates/{id}/resume-image` | 上传模板专用简历（multipart，写） |
| GET | `/api/boss/templates/{id}/resume-image/content` | 模板简历预览（只读） |

### 2.5 规则与重评（BossRulesController）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/rules` | 当前生效规则 |
| GET | `/api/boss/rules/versions` | 规则版本列表 |
| PUT | `/api/boss/rules` | 保存规则（expectedVersion + payload；写，409 冲突） |
| POST | `/api/boss/rules/preview` | 规则预览（不落库） |
| POST | `/api/boss/evaluations/recheck` | 只重评不发送 |

### 2.6 查询与人工核对（BossAuditController）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/runs` | 任务记录分页（含模板三字段，不返回原始快照） |
| GET | `/api/boss/evaluations` | 评估分页与筛选 |
| GET | `/api/boss/attempts` | 投递明细分页与筛选 |
| GET | `/api/boss/attempts/{attemptId}` | 尝试详情（事件/评估/人工结果） |
| POST | `/api/boss/attempts/{attemptId}/resolve` | 人工核对 SENT/NOT_SENT/STILL_UNKNOWN（版本+幂等键；写） |

### 2.7 联系人、跟进、图片补发（BossContactsController）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/contacts` | 联系人列表 |
| GET | `/api/boss/contacts/{contactKey}` | 联系人详情 |
| PUT | `/api/boss/contacts/{contactKey}/state` | 人工标记联系人状态（写） |
| GET | `/api/boss/followups` | 跟进计划列表 |
| POST | `/api/boss/followups/generate` | 生成跟进建议（写） |
| POST | `/api/boss/followups/{planId}/approve` | 批准（下一轮执行；写） |
| POST | `/api/boss/followups/{planId}/reject` | 拒绝（写） |
| POST | `/api/boss/attempts/{attemptId}/resend-image` | 生成图片补发计划（不自动发送；写） |

### 2.8 人工放行（ManualReleaseController）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/manual-releases?status=` | 人工放行队列（PENDING/APPROVED/FAILED/CONSUMED/REJECTED/ALL） |
规则载荷 `RulePayload` 新增 `blacklistCities`（不可接受城市，空=不限；命中会 `REJECT / CITY_BLACKLISTED`）；旧 `cities` 仅作历史兼容。

| POST | `/api/boss/manual-releases` | 批准/拒绝 REVIEW 岗位（写；批准只入队，不立即发送） |

### 2.9 投递状态同步（DeliveryStatusSyncController）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/boss/status-sync` | 主动读取今天到目前的投递/已读/回复状态（只读聊天；写接口） |
| GET | `/api/boss/status-sync/summary` | 今日主动同步 + 昨天启动补读 + 最近记录 |

### 2.10 统计看板（BossMetricsController）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/metrics/overview` | 指标总览/口径定义/数据质量 |
| GET | `/api/boss/metrics/series` | 时间序列（≤366 天） |
| GET | `/api/boss/metrics/collection` | 采集来源按日/关键词汇总 |

### 2.11 全局 AI 配置（AiConfigController）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/ai/config` | 全局 AI 介绍/提示词/模型配置（Key 掩码） |
| POST | `/api/ai/config` | 保存全局 AI 配置（写） |

### 2.12 通用配置与 Cookie

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/config` | 配置列表 |
| GET | `/api/config/{key}` | 单个配置（敏感值掩码） |
| POST | `/api/config` | 创建配置（写） |
| PUT | `/api/config/{key}` | 更新配置（写） |
| DELETE | `/api/config/{key}` | 删除配置（写） |
| GET | `/api/config/health` | 配置健康检查 |
| GET | `/api/cookie` | Cookie 状态（必须认证，掩码） |
| POST | `/api/cookie/save` | 保存 Cookie（必须认证；写） |

### 2.13 AI 建模板（ResumeOnboardingController）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/boss/resume/analyze` | 上传简历图片（multipart `file`），AI 提取画像 + 推荐方向/薪资；不保存图片 |
| POST | `/api/boss/resume/analyze-text` | JSON `{text}`，视觉不可用时的文本分析兜底 |
| POST | `/api/boss/templates/ai-draft` | 根据已确认画像/问答生成模板草稿；支持 `cities`（≤4）、`industries`（≤6），自动映射学历/经验/20人以上规模/融资不限；不创建、不激活、不写库 |

错误码：`AI_CONFIG_MISSING`（400，先配置模型服务）、`INVALID_IMAGE`（400/413，图片非法或超 10MB）、
`INVALID_TEXT`（400）、`VISION_PARSE_FAILED`（422，视觉返回不可解析）、`RESUME_ANALYZE_FAILED`（422/502）、
`DRAFT_INVALID`（400）。三个接口都是写接口，需本机会话 + CSRF；图片只在内存压缩到长边 1600 JPEG，不落盘。

### 2.14 维护与调试


| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/maintenance/status` | 数据库/备份/迁移状态 |
| GET | `/api/maintenance/backups` | 备份列表（pre-upgrade/pre-run/all） |
| POST | `/api/maintenance/backup` | 立即备份（pre-run；写） |
| GET | `/api/boss/stats` | BOSS 数据统计 |
| GET | `/api/boss/list` | BOSS 岗位数据列表 |
| GET | `/api/boss/reload` | 重新加载配置/数据（敏感读：必须认证） |
| GET | `/api/playwright/status` | 浏览器管理器状态（敏感读：必须认证） |
| GET | `/api/playwright/fingerprint` | 浏览器指纹（敏感读） |
| GET | `/api/playwright/diagnose` | 诊断（敏感读） |
| GET | `/api/playwright/test-navigate` | 导航自检（敏感读） |

## 3. 前端调用约定

- 前端只用 `front/lib/api.ts` 的 `request<T>`：自动 `credentials:'include'`、`X-Requested-With: offerchaser`、
  401/403 CSRF 自动派发登录事件。
- 写操作失败保留 `ApiError.status/code/body`；页面据 409 做刷新/冲突提示。
- SSE 订阅使用 `front/lib/boss-events.ts`：快照 + 增量、runId 过滤、seq 去重、断线重连后退避。
- 静态导出页面不能直连数据库；所有数据来自上述 API，页面层不 mock。
