# 01 · 架构

## 1. 定位

OfferChaser 是**本机单用户**的 BOSS 直聘投递系统：一个 Spring Boot 进程同时做后端 API、静态管理台托管与
Playwright/Chrome 自动化；管理台是预构建的 Next.js 静态导出目录，由本机浏览器访问。
系统不提供多用户、多账号、云端部署；真实投递只能由用户手动启动。

设计目标：

1. **不误发**优先于投得多；所有不确定路径 fail-closed。
2. 所有关键状态**持久化到 SQLite**，重启可恢复、可审计。
3. 自动化与业务规则**分层解耦**：规则可配置、模板可切换、模型可替换。
4. 管理台是**静态页面 + 本机 API**，前端不直连数据库、不内嵌浏览器。
5. 代码与文档可被后继开发者/AI 直接接管：约定清晰、目录稳定。

## 2. 总体结构

```text
┌──────────────────────────── Windows 本机 ────────────────────────────┐
│  OfferChaser.vbs / 启动.bat / OfferChaser.ps1（托盘启动器）             │
│        │ 启动 java -jar                                                │
│        ▼                                                               │
│  Spring Boot 进程                                                     │
│   ├─ HTTP 9527（API，127.0.0.1）                                      │
│   ├─ HTTP 6866（静态管理台，127.0.0.1；dist 存在时启用）                │
│   ├─ application/*（规则、AI、预算、台账、模板、状态同步、安全、持久化）  │
│   └─ worker/*（PlaywrightManager + Boss 页面自动化 + 聊天确认）         │
│        │ Patchright Node driver（子进程）                              │
│        ▼                                                               │
│  Chrome（真实浏览器，持久化 profile：browser-data/）                    │
└───────────────────────────────────────────────────────────────────────┘
```

- 后端固定 `server.port=9527`、`server.address=127.0.0.1`（只回环）。
- 静态服务连接器读 `offerchaser.static-server.port`（默认 6866），前端 dev 服务占用时不重复监听。
- 启动器只负责拉起/停止进程与托盘；不承载业务。

## 3. 后端分层

```
application/
├─ controller/   HTTP 接入层：参数校验、错误码映射、SSE、认证后路由
├─ security/     本机会话、令牌、CSRF、拦截器
├─ service/      跨域服务：BossService（配置/选项/黑名单）、AiService、ConfigService、CookieService
├─ rules/        规则模型与版本化：RulePayload/RuleSet/RuleConfigService/校验
├─ delivery/     规则引擎、JobSnapshot、薪资解析、评估与投递决策
├─ ai/           AI 严格输出：模板渲染、JSON 解析、话术校验、匹配服务
├─ templates/    方向模板：创建/激活/编辑/拖拽排序/启动前校正
├─ status/       投递状态同步：聊天列表只读解析 + 分类聚合 + 启动补读
├─ contacts/     联系人状态机与跨岗位去重
├─ manual/       人工放行队列（REVIEW → 批准/拒绝 → 下一轮消费）
├─ metrics/      统计看板聚合
├─ persistence/  SQLite 访问：迁移、备份、预算、台账、任务、查询、状态同步、放行
├─ config/       Spring 配置：静态服务、CORS、平台门、启动收尾
└─ init/         首次启动默认页面配置种子
worker/
├─ boss/         页面自动化：Boss（主流程）、BossChatNavigator、ChatSendConfirmer、BossChatStatusReader、Locators
├─ manager/      PlaywrightManager（线程/浏览器/登录态/持久化 profile）
└─ service/      BossJobService（任务生命周期编排、预检、SSE 事件）
front/           Next.js 16 静态管理台源码（构建产物进 src/main/resources/dist）
doc/             本套开发/运维文档
data/            本机敏感数据（会话令牌、简历、模板简历）
db/              SQLite 数据库与备份
browser-data/    Chrome 持久化 profile（登录态，敏感）
```

依赖方向：`controller → application 服务/领域 → persistence`；`worker/service → worker/boss + application`。
`persistence` 不反向依赖 `worker`；`worker` 不直接写 SQL（除 Playwright 相关的浏览器数据文件）。

## 4. 管理台结构

- Next.js 16 静态导出：构建产物在 `front/out`，复制到 `src/main/resources/dist`，由 9527 或 6866 托管。
- 应用外壳：`app/layout.tsx`（ThemeProvider + AuthGate）；BOSS 内容区壳在 `app/boss/layout.tsx`（TopBar + 运行胶囊 + 通知条）。
- 页面：`/boss/*` 投递控制台、`/ai-config` 全局 AI 配置、`/` 重定向到 `/boss`。
- 顶栏 `TopBar` 常驻：5 个空间滑块、运行胶囊、旧入口跳转面板、主题与锁定；运行状态由 `BossStatusProvider` 统一提供。
- 静态导出约束：不能使用动态路由；详情/编辑用 query、抽屉或内联面板；不使用 CDN/外部字体。

主要模块：

| 模块 | 职责 |
|---|---|
| `app/components/shell/*` | TopBar、运行胶囊、通知条、命令面板、主题、旧路由重定向 |
| `app/components/boss/*` | 总览卡片、流程带、运行控制、状态同步卡、全局运行条 |
| `app/components/V2PageHeader` | 页面统一头部（眉标/标题/状态/渐隐线） |
| `front/lib/api.ts` | 统一 API 封装：自动 CSRF 头、错误映射、401 重登 |
| `front/lib/boss-types.ts` | 后端响应/页面类型契约 |
| `front/lib/use-boss-status.ts` | 运行状态控制器（SSE + 轮询 + 动作 + 派生禁用逻辑） |

## 5. 主要数据流

### 5.1 投递任务（手动启动）

```
点击开始
→ BossJobService 预检：迁移就绪/模板校正/配置校验/AI 校验/预算/浏览器/登录
→ 创建 task_run（STARTING）→ 启动 worker 线程
→ 采集：滚动聊天列表；每关键词候选上限/时长/无新增窗口
→ 快照与硬规则（学历/薪资/方向/城市/黑白名单/HR 活跃）
→ AI 严格评估（必须 MATCH 且话术合法；否则不发送）
→ 联系人去重/回复检查 → 预算原子预留
→ 打开会话 → 身份核对（fail-closed）
→ 文字发送 + 15s 确认；图片发送 + 45s 确认
→ 台账/评估/事件落库；预算结算（确认/未知/释放）
→ 下一岗位或结束（COMPLETED / FAILED / 停止）
```

### 5.2 启动前模板校正

任务启动预检会读取**生效方向模板**：BOSS 搜索/开关、AI 介绍与提示词、筛选规则、图片简历、采集策略与当前配置
逐项比对，不一致则按模板恢复（规则差异才新建规则版本），再校验配置。每日首次沟通预算始终全局共享，不进模板。

### 5.3 投递状态同步

- 手动按钮：读取今天到当前时刻的聊天状态（ACTIVE/TODAY）。
- 每天首次启动：20s 后异步补读前一天（STARTUP/PREVIOUS_DAY），同日成功过则跳过。
- 分类：自动投递（当日首次沟通记录匹配聊天）、手动投递（自己发起且未匹配）、HR 主动招呼（首条消息来自 HR）。
- 只读判定：聊天列表已读/送达标记、未读红点、最后一条是否 HR；无法判断记为 UNKNOWN。
- 读取到 HR 回复时回写联系人，并在后续发送前复检。

### 5.4 人工放行

规则/AI 结果为 REVIEW 的岗位进入人工放行队列；人工批准只代表“进入下一轮候选”，不立即发送。
下一轮消费时重新执行 AI 严格评估（仍必须 MATCH），再走身份/预算/文字+图片确认；不通过则记失败，不绕过安全链路。

### 5.5 联系人/跟进

联系人状态与跨岗位去重由 `contact_state` 维护；跟进计划由 `followup_plan` 生成，**必须人工批准**，
只允许下一轮任务执行；跟进间隔/次数有上限；收到 HR 回复、拒绝、人工接管会终止该联系人的跟进计划。

## 6. 为什么这样设计

| 设计 | 原因 |
|---|---|
| fail-closed 发送链路 | 误发不可撤回；不确定状态一律不发并按 UNKNOWN 记账 |
| SQLite + 单写事务 | 本机单用户，无需外部 DB；预算预留/结算/台账需要事务保证 |
| 预算逐笔预留 | 防止并发/重试/崩溃导致超发；重启可恢复 |
| 模板优先级最高 | 多方向切换时有唯一配置来源，避免双源漂移 |
| 静态导出 | 无 Node 运行时依赖，产物可随包分发；本机后端托管 |
| 持久化 Chrome profile | 保留登录态；崩溃后可自愈，不用每次扫码 |
| 人工放行队列 | REVIEW 岗位不自动投；批准后仍重新评估，避免旧 AI 结论过期 |
| 只读状态同步 | 打开聊天会由 BOSS 标记已读；同步过程绝不发送消息 |

## 7. 扩展点

- **新增 BOSS 操作**：优先在 `worker/boss/Locators` 加选择器常量，在明确失败语义后接入 `BossJobService`。
- **新增业务数据**：`persistence` 加迁移 V14（只追加，不改已发布 DDL），同步更新 [02-runtime-data](02-runtime-data.md)。
- **新增 API**：controller 保持 `{success,data}` 外壳；写接口默认需要会话+CSRF（见 [04-security](04-security.md)）。
- **新增空间**：优先落在 5 个空间之一，旧路由只做重定向；禁止运行时远端资源；需要 API 时走 `front/lib/api.ts`。
- **新增配置**：默认进 `application.yaml` 的 `offerchaser.*`；密钥类只从环境变量/本机配置读取并掩码。
