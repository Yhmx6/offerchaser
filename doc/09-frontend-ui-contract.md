# OfferChaser 前端重构交接契约
> **状态：已被 V3 5 空间设计取代（2026-11-08）。**
> 本文件的**页面/按钮/API 行为清单**仍可作功能参考，但路由、Sidebar、GlobalRunBar 等结构已由 [10-ui-redesign-v3](10-ui-redesign-v3.md) 和实际实现取代。
> 新 UI 的权威规格请看 [10-ui-redesign-v3](10-ui-redesign-v3.md)。


> 读者：接手管理台 UI 重构的 agent / 前端。
>
> 目标：**只换 UI，不改后端契约与业务语义。** 本文按页面列出按钮、交互、API、状态和禁用/确认规则。当前代码基于 Next.js 16 静态导出、React 19；接口封装在 `front/lib/api.ts`，类型在 `front/lib/boss-types.ts`。

## 0. 硬性约束

1. **路由和页面职责不能变**：保留现有 15 个路由页与 `/` → `/boss` 跳转；不要合并页面导致功能入口消失。
2. **API 路径、方法、参数名、响应结构不能变**：所有请求必须继续走 `front/lib/api.ts` / `front/lib/auth.ts`，不要另起一套裸 `fetch`。
3. **认证不能绕过**：写操作依赖本机会话 Cookie + `X-Requested-With: offerchaser`；401/403 必须回到 `AuthGate`，不能吞掉或伪造成功。
4. **真实发送不能加“快捷键”**：任何按钮都不能绕过规则/AI/身份/预算/文字+图片确认。人工放行、跟进批准都只是“下一轮执行”，不是立即发送。
5. **错误/冲突不能吞**：409（版本冲突、队列状态冲突、运行状态冲突）要展示并刷新；UNKNOWN 不能显示成成功。
6. **不新增后端接口**；需要新字段时先提出，不在 UI 里猜。
7. **视觉可以重做，信息不能少**：状态、原因、版本、时间、证据、禁用原因必须保留。

---

## 1. 全局框架与入口

### 1.1 `/` 根路由

| 控件 | 行为 |
|---|---|
| 无按钮 | `useEffect` 执行 `router.replace('/boss')`，显示骨架，不请求接口 |

### 1.2 全局登录门 `AuthGate`

覆盖整个管理台；未认证时显示登录表单。

| 控件 | 行为 / API |
|---|---|
| 本地访问令牌输入框 | 用户粘贴 `data/local-session-token` 内容；可显示/隐藏 |
| 显示/隐藏令牌 | 只切换 input type，不发请求 |
| **登录管理台** | `POST /api/session/login`，body `{ token }`；成功后写入 HttpOnly Cookie 并重试原页面 |
| 登录失败 | 显示错误；失败过多时后端返回 429 `TOO_MANY_ATTEMPTS`，显示节流提示 |
| 启动检查 | `GET /api/session/status`；`authEnabled=false` 时直接放行 |

注意：令牌只随登录请求发送，不写 localStorage/sessionStorage。

### 1.3 Sidebar

三个导航组 + 底部设置区。

| 区域 | 入口 / 控件 | 行为 |
|---|---|---|
| 投递 | 总览 `/boss` | 路由跳转 |
| 投递 | 投递流程 `/boss/flow` | 路由跳转 |
| 投递 | 投递明细 `/boss/attempts` | 路由跳转；有数字徽章，显示待人工核对数，0 不渲染 |
| 投递 | 人工放行 `/boss/reviews` | 路由跳转 |
| 投递 | 跟进待办 `/boss/followups` | 路由跳转 |
| 配置 | 配置中心 `/boss/templates` | 路由跳转 |
| 配置 | 高级调试 `/boss/advanced` | 路由跳转 |
| 复盘 | 任务记录 `/boss/tasks` | 路由跳转 |
| 复盘 | 评估记录 `/boss/evaluations` | 路由跳转 |
| 复盘 | 联系人 `/boss/contacts` | 路由跳转 |
| 复盘 | 跟进策略 `/boss/followup-strategy` | 路由跳转 |
| 复盘 | 统计看板 `/boss/analysis` | 路由跳转 |
| 底部 | 主题切换 | 切换 light/dark，不发接口 |
| 底部 | 认证状态 | 展示“本机会话已认证/未认证” |
| 底部 | 锁定 / 退出登录 | `POST /api/session/logout`；清除会话后触发 `AuthGate` |

Sidebar 还负责：

- 健康状态：`GET /api/health`（失败回退 `/actuator/health`），展示运行正常/降级/异常；
- `GET /api/capabilities`：只渲染后端声明的平台；当前只有 BOSS；
- 待核对徽章：`/boss*` 路由下 30s 轮询 `GET /api/boss/status` 的 `pendingResolutions`，失败静默。

### 1.4 全局运行条 `GlobalRunBar`

仅 `/boss/*` 且不是 `/boss` 时显示。

| 控件 | 行为 |
|---|---|
| 状态文本/色点 | 由 `GET /api/boss/status` 的 `state` 映射：运行中、等待人工验证、失败、停止中、空闲、已完成 |
| **去处理 →** | 待核对 > 0 时链接 `/boss/attempts?textStatus=UNKNOWN` |
| **回总览** | 链接 `/boss` |
| 30s 轮询 | 失败保持上一次值，不阻塞页面 |

不要在这个组件里放开始/停止按钮，避免出现第二套状态机。

### 1.5 状态订阅与轮询

- SSE：`GET /api/boss/stream`，事件 `snapshot` / `progress` / `ping` / `connected`；
- 只保留当前 `runId` 的事件，按 `seq` 去重；
- 断线重连后主动拉一次 `GET /api/boss/status`；
- 状态兜底轮询 30s；
- UI 重构可以替换展示，但不要破坏“旧任务事件不能污染新任务”的规则。

### 1.6 请求约定

- 统一从 `front/lib/api.ts` 导入函数；返回已解包的 `data`；
- 非 2xx 抛 `ApiError`（含 status/code/message）；
- 401/403 由 `installApiFetchDefaults` / `dispatchAuthRequired` 交给登录门；
- 写操作必须带会话 Cookie 和 CSRF 头；不要再写 `fetch` 直连；
- 分页查询统一使用 `page`（从 1 开始）与 `size`；
- 金额/时间按现有语义显示，不要在前端重算业务规则。

---

## 2. 页面契约

### 2.1 `/boss` 总览

**目的**：展示今日投递、预算、流程阶段、运行控制、最近事件。

| 控件 | 行为 | API | 确认/禁用 |
|---|---|---|---|
| **开始投递** | 启动一轮真实投递 | `POST /api/boss/start` | 仅空闲时显示；加载中禁用；不会二次确认（后端还有完整安全链路） |
| **停止投递** | 停止当前运行任务 | `POST /api/boss/stop?runId=<runId>` | 先弹确认；`RUNNING/STARTING` 才显示；绑定当前 runId |
| **继续任务** | 恢复因人工验证暂停的任务 | `POST /api/boss/verification/resume` body `{runId?}` | 仅 `PAUSED_VERIFICATION` 显示 |
| **强制复位** | 处理卡死/worker 未退出 | `POST /api/boss/force-reset?confirm=true&runId=<runId>` | 必须弹危险确认；仅在运行中/暂停/workerExited=false 时出现 |
| **刷新** | 重拉状态、摘要、模板 | `GET /api/boss/status`、`GET /api/boss/delivery/summary`、`GET /api/boss/templates` | 加载中禁用 |
| **读取今日投递状态** | 读取 BOSS 聊天状态并统计 | `POST /api/boss/status-sync`；随后 `GET /api/boss/status-sync/summary` | 读取中禁用；只读，不发送 |
| 去配置中心 | 链接 `/boss/templates` | — | — |

相关展示：今日文字确认数、待人工核对数、预算 used/limit/remaining/reserved/uncertain、流程 9 阶段、最近事件、状态/停止原因/错误。

### 2.2 `/boss/flow` 投递流程

| 控件 | 行为 | API |
|---|---|---|
| **暂停刷新 / 继续实时** | 暂停/恢复页面自动刷新，不改变后端任务 | — |
| **刷新** | 手动拉状态 | `GET /api/boss/status` |
| 阶段卡片 | 点击筛选该阶段事件 | — |
| 清除阶段筛选 ✕ | 恢复全部事件 | — |
| **重试** | 仅错误态显示，重新拉状态 | `GET /api/boss/status` |

不写后端；SSE 由共享订阅层处理。

### 2.3 `/boss/tasks` 任务记录

| 控件 | 行为 | API |
|---|---|---|
| **刷新** | 重拉当前页 | `GET /api/boss/runs` |
| 状态筛选 | 选择状态 | query `status` |
| **查询** | 应用筛选并从第 1 页开始 | `GET /api/boss/runs` |
| **重置** | 清空筛选 | — |
| **只看失败 →** | 快捷设 `status=FAILED` | `GET /api/boss/runs` |
| 行展开 | 展示 runId、模板、平台、时间、停止原因、错误 | — |
| 分页 | 上一页/下一页 | `page` / `size` |

### 2.4 `/boss/evaluations` 评估记录

| 控件 | 行为 | API |
|---|---|---|
| **用当前规则重评** | 只新增评估，不发送、不改投递状态；按当前筛选提交 | `POST /api/boss/evaluations/recheck` |
| **刷新** | 重拉列表 | `GET /api/boss/evaluations` |
| 关键词 / runId 筛选 | 输入后查询 | query `keyword` / `runId` |
| 记录范围 | “每岗位仅最新一条” | query `latestOnly` |
| 规则结果 / AI 结果筛选 | 过滤 | query |
| **重置** | 清空筛选 | — |
| 行展开 | 展示 rule/AI 原因、输出、版本、时间、evidence | — |

重评返回 `evaluated/passed/rejected/reviewed`，UI 必须展示统计和错误；不能把 REVIEW 显示成发送成功。

### 2.5 `/boss/attempts` 投递明细 / 人工核对

| 控件 | 行为 | API |
|---|---|---|
| **刷新** | 重拉列表 | `GET /api/boss/attempts` |
| 筛选：runId / 投递类型 / 文字状态 / 图片状态 | 输入/选择后查询 | query 对应字段 |
| **重置** | 清空筛选 | — |
| 密度切换 | 舒适/紧凑，仅 UI，localStorage 记忆 | — |
| **只看待核对 / 取消筛选 ✕** | 快捷 `textStatus=UNKNOWN` | `GET /api/boss/attempts` |
| 行点击 | 打开详情抽屉 | `GET /api/boss/attempts/{attemptId}` |
| 岗位链接 | 打开岗位页 | — |
| **生成补发图片计划** | 仅“文字已确认 + 图片失败/未知”可显示；生成计划不自动执行 | `POST /api/boss/attempts/{attemptId}/resend-image` body `{reason}` |
| **提交人工核对** | 对 UNKNOWN/失败结果人工裁定 | `POST /api/boss/attempts/{attemptId}/resolve` body `{resolution,reason,expectedVersion,idempotencyKey?}` |
| 分页 | 上一页/下一页 | `page`/`size` |

详情必须展示文字/图片状态、事件证据、关联评估、版本和 409 冲突提示。补发计划生成后提示“需到跟进待办处理，不会自动执行”。

### 2.6 `/boss/contacts` 联系人

| 控件 | 行为 | API |
|---|---|---|
| **刷新** | 重拉列表 | `GET /api/boss/contacts` |
| 搜索框 + 搜索按钮 | 按联系人/HR/公司过滤 | query `keyword` |
| 行点击 / 详情按钮 | 打开详情 | `GET /api/boss/contacts/{contactKey}` |
| 行内 **标记状态** | 打开标记抽屉 | — |
| **标记状态**（详情内） | 切换编辑模式 | — |
| 回复状态 select | `UNKNOWN/REPLIED/REJECTED/INTERVIEW/NO_REPLY` | — |
| **人工接管** 开关 | 开启后禁止自动沟通 | — |
| **停止联系** 开关 | 开启后不再自动联系 | — |
| 原因 textarea | 必填；最多 500 字 | — |
| **保存标记** | 提交联系人状态；带 expectedVersion | `PUT /api/boss/contacts/{contactKey}/state` body `{replyState,manualTakeover,stopContact,reason,expectedVersion,idempotencyKey?}` |
| **取消** / **取消标记** | 关闭抽屉，返回查看模式 | — |

409 版本冲突必须提示并重新拉取详情；标记回复/接管/停止会使已批准跟进失效。

### 2.7 `/boss/followups` 跟进待办

| 控件 | 行为 | API |
|---|---|---|
| **生成建议跟进** | 只创建 SUGGESTED，不发送 | `POST /api/boss/followups/generate` body `{limit:50}` |
| **刷新** | 重拉列表 | `GET /api/boss/followups` |
| 状态/类型筛选 | 过滤；`重置` 清空 | query |
| 行展开 | 展示 planId、父尝试、联系人、建议时间、原因 | — |
| 行内 **批准** | 打开批准理由弹窗 | — |
| 行内 **拒绝** | 打开拒绝理由弹窗 | — |
| 弹窗 **确认批准** | 只允许下一轮执行，不立即发送 | `POST /api/boss/followups/{planId}/approve` body `{expectedVersion,reason,idempotencyKey?}` |
| 弹窗 **确认拒绝** | 取消该计划 | `POST /api/boss/followups/{planId}/reject` 同上 |
| **取消** | 关闭弹窗 | — |

说明文案必须保留：“批准只允许下一轮任务执行，不会立即发送；联系人回复/接管/停止会使批准失效”。

### 2.8 `/boss/followup-strategy` 跟进策略

只读说明页：当前已有措施、后续待做措施；链接到 `/boss/templates`、`/boss/contacts`、`/boss/followups`。无写按钮、无 API 变更。

### 2.9 `/boss/analysis` 采集与统计

| 控件 | 行为 | API |
|---|---|---|
| 日期范围 **今天 / 最近 7 天 / 最近 30 天** | 切换范围并重拉 | `GET /api/boss/metrics/overview`、`/series`、`/collection` |
| **自定义** | 打开开始/结束日期输入 | — |
| 自定义 **查询** | 应用范围；跨度 ≤366 天 | 同上 |
| **刷新** | 重拉当前范围 | 同上 |
| 指标切换 | 切换趋势指标 | `GET /api/boss/metrics/series` |
| 概览卡片下钻链接 | 跳转到对应详情页 | — |

400 `INVALID_RANGE` / `INVALID_METRIC` 直接展示后端 message；空数据展示空态，不能伪造 0 趋势。

### 2.10 `/boss/reviews` 人工放行

| 控件 | 行为 | API |
|---|---|---|
| 状态页签：**待放行 / 已批准 / 失败 / 已消费 / 已拒绝** | 切换并带计数 | `GET /api/boss/manual-releases?status=` |
| **刷新** | 重拉当前页签 | 同上 |
| 行展开 | 展示 evaluationId、releaseId、规则/AI 结论与原因、人工理由、错误、attemptId、时间 | — |
| 岗位链接 | 打开 BOSS 岗位页 | — |
| **批准** | 打开批准理由弹窗；只入队，不是立即发送 | `POST /api/boss/manual-releases` body `{evaluationId,action:'APPROVE',reason}` |
| **拒绝** | 打开拒绝理由弹窗 | 同上 `action:'REJECT'` |
| 弹窗 **确认批准/拒绝** | 理由必填 ≤500；409 刷新队列 | 同上 |
| 去评估记录 | 链接 `/boss/evaluations` | — |

必须保留说明：AI REVIEW 批准后执行时仍重新 AI 评估；身份、预算、文字+图片确认不可绕过；失败可重试。

### 2.11 `/boss/rules` 规则配置

| 控件 | 行为 | API |
|---|---|---|
| **刷新** | 重拉当前规则与版本 | `GET /api/boss/rules` |
| **保存为新版本** | 保存规则；带 expectedVersion | `PUT /api/boss/rules` body `{expectedVersion,note,rules}` |
| **载入当前生效版本** | 用 active 规则回填表单 | — |
| **载入实时配置默认值** | 用 liveDefaults 回填 | — |
| 单个样例 **预览** | 用表单当前规则预览 | `POST /api/boss/rules/preview` |
| **全部内置样例** 预览 | 批量预览 | 同上 |
| 自定义样例 **查询** | 用自定义 JD/岗位截图字段预览 | 同上 |
| **刷新版本** | 拉版本历史 | `GET /api/boss/rules/versions?limit=20` |
| 版本 **载入** | 把旧版本载入表单（保存时生成新版本） | — |
| HR 活跃过滤开关 | 修改表单字段 | — |

409 `RULE_VERSION_CONFLICT` 必须提示并刷新；预览绝不发送、不写评估。

### 2.12 `/boss/templates` 配置中心

**头部**

| 控件 | 行为 | API |
|---|---|---|
| **新建模板** | 打开创建抽屉；默认不激活 | `POST /api/boss/templates` body `{name,direction?,description?}` |
| **刷新** | 重拉模板/生效模板 | `GET /api/boss/templates` |

**模型服务（全局）卡片**

| 控件 | 行为 | API |
|---|---|---|
| **重新读取** | 读取 BASE_URL/MODEL/API_KEY 掩码 | `GET /api/config` |
| **保存模型配置** | 保存全局模型地址/模型/密钥；掩码原样提交表示保持 | `POST /api/config` body `{BASE_URL,API_KEY,MODEL}` |

**模板列表 / 详情**

| 控件 | 行为 | API |
|---|---|---|
| 模板行点击 | 选中右侧详情 | — |
| 拖拽行 | 调整模板排序 | `PUT /api/boss/templates/order` body `{order:[id...]}` |
| **激活** | 把模板应用到当前配置，下一轮生效；需确认 | `POST /api/boss/templates/{id}/activate` |
| **编辑配置** | 打开模板配置编辑器 | `GET /api/boss/templates/{id}` |
| 行菜单 **用当前配置覆盖** | 当前配置写回模板快照；需确认 | `POST /api/boss/templates/{id}/capture` |
| 行菜单 **复制为新模板** | 复制完整配置快照，默认不激活 | `POST /api/boss/templates/{id}/duplicate` body `{name}` |
| 行菜单 **改名称 / 描述** | 只改元信息 | `PUT /api/boss/templates/{id}` body `{name,direction?,description?}` |
| 行菜单 **上传简历** | 模板独立图片简历，≤10MB | `POST /api/boss/templates/{id}/resume-image` multipart `file` |
| 行菜单 **预览简历** | 打开预览 | `GET /api/boss/templates/{id}/resume-image/content` |
| 行菜单 **删除** | 删除模板；生效模板不可删（409） | `DELETE /api/boss/templates/{id}` |
| 弹窗 **激活/复制/保存/取消** | 按对应动作提交；失败保留表单 | 同上 |

**模板配置编辑器**

| 控件 | 行为 | API |
|---|---|---|
| **保存配置** | 整包保存搜索/投递开关、规则、AI、简历引用；active 立即应用 | `PUT /api/boss/templates/{id}/config` |
| **重新加载** | 丢弃未保存改动并重载模板详情/选项 | `GET /api/boss/templates/{id}` |
| **上传/重新上传模板简历** | 图片上传 | `POST /api/boss/templates/{id}/resume-image` |
| **预览模板简历 / 收起预览** | 打开/关闭简历图片 | `GET /api/boss/templates/{id}/resume-image/content` |
| **HR 活跃过滤** 等表单开关 | 只改表单，不单独请求 | — |

模板配置编辑器底部是唯一保存入口；必须有“有 N 处未保存改动”提示。保存后 active 模板显示“已保存并立即应用”，非 active 显示“只更新快照，需激活后生效”。

### 2.13 `/boss/advanced` 高级调试

| 控件 | 行为 |
|---|---|
| **筛选规则** | 链接 `/boss/rules` |
| **AI 配置** | 链接 `/ai-config` |
| **返回配置中心** | 链接 `/boss/templates` |

页面本身只读取 `GET /api/boss/templates` 展示当前生效模板；无写操作。

### 2.14 `/ai-config` AI 配置（高级入口，不在主 Sidebar）

| 控件 | 行为 | API |
|---|---|---|
| **保存 AI 配置** | 保存 AI 技能介绍/提示词，并自动写回当前生效模板 | `POST /api/ai/config` body `{introduce,prompt}` |
| **AI 启用开关** | 乐观切换；失败回滚 | `PUT /api/boss/config` body `{enableAi:0|1}` |
| 模板上下文横幅 | 展示当前生效模板；链接配置中心 | `GET /api/boss/templates`（组件内） |

### 2.15 `/boss` 总览里的共享组件

- **RunControlCard**：开始/停止/继续/强制复位；所有动作显示 busy 文案，成功后刷新状态；停止和强制复位必须确认。
- **StatusSyncCard**：`读取今日投递状态`；POST 后展示今日/昨日两类同步状态、AUTO/MANUAL/HR 来源统计、已读/已回复/未读/未知；只读，不发送。
- **RecentEvents / StageBand / CoverNumbers**：只读展示，不允许写接口。
- **GlobalRunBar**：只读状态与链接。

---

## 3. API 契约总表

> 统一前缀为 `API_BASE`；写操作需会话 Cookie + CSRF 头。以下“写”均指改变本地状态，不代表会发消息。

### 3.1 会话 / 健康 / 能力

| 方法 | 路径 | 用途 | 认证 |
|---|---|---|---|
| GET | `/api/session/status` | 会话认证开关与登录状态 | 公开 |
| POST | `/api/session/login` | 本地令牌换 HttpOnly 会话 | 公开 |
| POST | `/api/session/logout` | 清除会话 | 需要会话 |
| GET | `/api/health` | 管理台健康轮询 | 公开 |
| GET | `/api/capabilities` | 启用平台/能力 | 公开 |

### 3.2 运行控制

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/status` | 当前任务状态、预算、台账摘要、pipeline |
| GET | `/api/boss/stream` | SSE：snapshot/progress/ping/connected |
| POST | `/api/boss/start` | 开始一轮投递 |
| POST | `/api/boss/stop?runId=` | 停止任务；runId 不匹配 409 |
| POST | `/api/boss/verification/resume` | 恢复人工验证暂停 |
| POST | `/api/boss/force-reset?confirm=true&runId=` | 强制复位卡死任务 |
| GET | `/api/boss/delivery/summary` | 今日预算/投递/评估汇总 |
| GET | `/api/boss/status-sync/summary` | 今日/昨日状态同步摘要 |
| POST | `/api/boss/status-sync` | 主动读取今日 BOSS 聊天状态 |

### 3.3 记录 / 明细 / 评估

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/runs` | 任务记录分页 |
| GET | `/api/boss/evaluations` | 评估记录分页 |
| GET | `/api/boss/attempts` | 投递明细分页 |
| GET | `/api/boss/attempts/{attemptId}` | 明细详情/事件/关联评估 |
| POST | `/api/boss/attempts/{attemptId}/resolve` | 人工核对 UNKNOWN |
| POST | `/api/boss/attempts/{attemptId}/resend-image` | 创建图片补发计划（不执行） |
| POST | `/api/boss/evaluations/recheck` | 用当前规则重评（只新增评估） |

### 3.4 规则

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/rules` | active 规则 + liveDefaults + versions |
| PUT | `/api/boss/rules` | 保存新版本；带 expectedVersion |
| GET | `/api/boss/rules/versions?limit=` | 版本历史 |
| POST | `/api/boss/rules/preview` | 预览规则判定，零发送/零写评估 |

### 3.5 联系人 / 跟进

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/contacts` | 联系人分页 |
| GET | `/api/boss/contacts/{contactKey}` | 联系人详情 + 最近 attempt |
| PUT | `/api/boss/contacts/{contactKey}/state` | 人工标记状态；带 expectedVersion |
| GET | `/api/boss/followups` | 跟进计划分页 |
| POST | `/api/boss/followups/generate` | 生成 SUGGESTED，不发送 |
| POST | `/api/boss/followups/{planId}/approve` | 批准，仅下一轮执行 |
| POST | `/api/boss/followups/{planId}/reject` | 拒绝 |

### 3.6 统计

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/metrics/overview` | 统计概览（from/to/runId） |
| GET | `/api/boss/metrics/series` | 单指标趋势（metric） |
| GET | `/api/boss/metrics/collection` | 采集来源统计 |

### 3.7 方向模板 / 配置

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/templates` | 模板列表 + active |
| POST | `/api/boss/templates` | 从当前配置创建模板 |
| GET | `/api/boss/templates/{id}` | 模板完整详情 |
| PUT | `/api/boss/templates/{id}` | 改模板名称/方向/描述 |
| PUT | `/api/boss/templates/{id}/config` | 保存整包模板配置 |
| POST | `/api/boss/templates/{id}/activate` | 激活模板 |
| POST | `/api/boss/templates/{id}/capture` | 当前配置覆盖模板快照 |
| POST | `/api/boss/templates/{id}/duplicate` | 复制模板 |
| DELETE | `/api/boss/templates/{id}` | 删除模板 |
| PUT | `/api/boss/templates/order` | 保存排序 |
| POST | `/api/boss/templates/{id}/resume-image` | 上传模板图片简历（multipart） |
| GET | `/api/boss/templates/{id}/resume-image/content` | 读取模板简历图片 |
| GET | `/api/boss/config/options/{type}` | BOSS 选项（city/industry/experience/degree/salary/scale/stage/jobType） |
| GET | `/api/config` | 全局键值配置（API_KEY 掩码） |
| POST | `/api/config` | 保存全局模型服务配置 |
| GET | `/api/ai/config` | AI 介绍/提示词 |
| POST | `/api/ai/config` | 保存 AI 介绍/提示词并回写 active 模板 |
| GET | `/api/boss/config` | BOSS 配置（含 enableAi） |
| PUT | `/api/boss/config` | 更新 BOSS 配置（AI 启用开关） |

### 3.8 人工放行

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boss/manual-releases?status=` | 队列 + 各状态计数；status 可为 PENDING/APPROVED/FAILED/CONSUMED/REJECTED/ALL |
| POST | `/api/boss/manual-releases` | 批准/拒绝；body `{evaluationId,action,reason}`；理由必填 ≤500 |

---

## 4. 关键状态与交互规则

### 4.1 任务状态

`IDLE / STARTING / RUNNING / PAUSED_VERIFICATION / STOPPING / COMPLETED / FAILED`

| 状态 | 总览按钮 |
|---|---|
| IDLE/COMPLETED/FAILED | 开始投递 |
| STARTING/RUNNING | 停止投递；可显示强制复位 |
| PAUSED_VERIFICATION | 继续任务；可显示强制复位 |
| STOPPING | 停止中（禁用） |

### 4.2 写入按钮的通用要求

- 请求中显示 busy 文案，禁止重复点击；
- 成功提示必须来自后端 message 或明确固定文案；
- 409：展示冲突原因并刷新对应数据；不要重试到成功；
- 401/403：回到登录门；
- 网络错误：保留已有数据并显示错误，不清空成空态；
- 不允许把 `UNKNOWN`、`REVIEW`、`PENDING` 显示成“成功”。

### 4.3 确认弹窗

必须确认：

- 停止投递；
- 强制复位；
- 激活模板（说明下一轮生效）；
- 用当前配置覆盖模板；
- 删除模板；
- 人工放行批准/拒绝（理由必填）；
- 跟进批准/拒绝（理由必填）；
- 联系人状态标记（原因必填）；
- 投递 UNKNOWN 人工核对（原因必填）。

可选确认：生成建议跟进、重评、状态同步（这类无发送副作用，但建议给结果提示）。

### 4.4 关键文案

- 开始投递不是“自动跑”，真实发送仍由后端安全链路决定；
- 人工放行：“批准后下一轮执行，不立即发送”；
- 跟进：“批准只允许下一轮任务执行，不立即发送”；
- 补发图片：“生成计划不自动执行”；
- 模板：“active 保存立即应用；非 active 只更新快照”；
- 联系人标记：“只记录人工判定，不读取或发送任何消息”。

---

## 5. 类型与数据来源

- 所有 API 类型：`front/lib/boss-types.ts`
- 请求封装：`front/lib/api.ts`
- 认证：`front/lib/auth.ts`
- SSE 重连：`front/lib/sse.ts` + `front/lib/boss-events.ts`
- 状态 hook：`front/lib/use-boss-status.ts`
- 通用 UI：`front/components/ui/*`、`front/app/components/*`

重构时优先复用这些类型和函数；不要复制请求逻辑，也不要另造字段名。

---

## 6. 重构验收清单

- [ ] 15 个路由页面 + `/` 跳转全部存在；
- [ ] Sidebar 三个分组和全部入口存在，待核对徽章/健康/锁定不丢；
- [ ] AuthGate 登录、显示/隐藏令牌、429/失败提示正确；
- [ ] 总览四个运行控制动作和确认流程正确；
- [ ] 状态同步按钮及三类来源统计正确；
- [ ] 任务/评估/明细的分页、筛选、重置、空/错/加载状态正确；
- [ ] 明细人工核对和补发计划接口正确，409 有提示；
- [ ] 联系人状态编辑、版本冲突、接管/停止开关正确；
- [ ] 跟进生成/批准/拒绝和理由必填正确；
- [ ] 人工放行五页签、批准/拒绝、失败重试和“不立即发送”文案正确；
- [ ] 规则保存版本冲突、预览、版本载入正确；
- [ ] 模板创建/激活/编辑配置/复制/删除/排序/简历上传/预览/模型服务保存正确；
- [ ] AI 配置保存和启用开关正确；
- [ ] 所有写操作走现有 API 且带认证/CSRF；
- [ ] 任何 UI 路径都没有绕过预算、身份、确认或人工放行的入口；
- [ ] `next build`、`npm run lint`、UI E2E 通过。

---

## 7. 非目标 / 不要做的事

- 不新增后端接口；
- 不修改现有 API 参数名和响应字段；
- 不把“批准/放行/跟进”做成即时发送；
- 不直接 fetch 写接口；
- 不在前端重算规则/AI/预算结果；
- 不删除状态、错误、版本、证据等审计信息；
- 不引入会遮挡真实发送链路的前端“测试模式”。
