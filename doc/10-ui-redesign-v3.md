# OfferChaser 管理台 · UI 重构设计方案

> 版本：重构版 v1（配套 Design 画布「OfferChaser 管理台 UI 设计」，三个画板 A / B / C 即三套配色）
> 定位：只重做 UI 与交互组织，**不改后端接口、认证和发送安全语义**。接口与字段以《前端重构交接契约》和 `front/lib/api.ts`、`front/lib/boss-types.ts` 为准。
> 原型里的所有数据均为示例。

---

## 1. 设计目标

| 目标 | 做法 |
|---|---|
| 质感高级 | 一个强调色 + 大面积中性色；1px 细线分隔，少阴影；大号数字做视觉重心 |
| 简约 | 去掉侧边栏；每个空间只回答一个问题；同类信息归并，不堆卡片 |
| 实用 | 按“要做的事”组织而不是按“页面”；决策在原地完成；运行控制全站可达 |
| 动效适度 | 只用于表达状态与层级（切换、生长、呼吸），所有动效尊重 `prefers-reduced-motion` |
| 安全不退让 | 批准只入队、UNKNOWN/REVIEW/PENDING 不显示成功、409 必须展示、写操作必须有原因与确认 |

---

## 2. 信息架构：5 个空间

顶栏用滑块切换 5 个空间，取代原来的 3 组侧边栏 + 15 个页面。

| 空间 | 回答的问题 | 合并了哪些旧页面 |
|---|---|---|
| **今日** | 现在跑得怎么样？有什么要我管？ | `/boss` 总览、`/boss/flow` 投递流程 |
| **待办** | 有哪些事需要我拍板？ | `/boss/attempts` 里的 UNKNOWN 核对与补发图片、`/boss/reviews` 人工放行、`/boss/followups` 跟进待办 |
| **记录** | 之前发生了什么？ | `/boss/attempts` 明细、`/boss/evaluations`、`/boss/tasks`、`/boss/contacts` |
| **配置** | 怎么改投递策略？ | `/boss/templates`、`/boss/rules`、`/ai-config`、`/boss/advanced` |
| **洞察** | 效果如何？ | `/boss/analysis`、`/boss/followup-strategy` |

### 2.1 全局元素

| 元素 | 说明 | API / 规则 |
|---|---|---|
| Logo | 回到“今日” | — |
| 空间滑块 | 5 个标签；“待办”带数字徽章 | 徽章 = 全部待处理数（见 §8 差异） |
| **运行胶囊** | 状态点 + 状态文字 + 已运行时间 + 当前可用动作 | 全站唯一的开始/停止/继续/强制复位入口 |
| 跳转面板 | 输入功能名或旧路径（如 `rules`、`/boss/reviews`），15 个旧入口全部可达 | 纯前端导航 |
| 主题切换 | 循环三套配色（上线后建议只保留一套 + 明暗切换） | 不发请求 |
| 锁定 | 触发登录门 | `POST /api/session/logout` |
| 通知条 | 位于顶栏下方，仅在 PAUSED_VERIFICATION / FAILED / COMPLETED 出现，展示状态、停止原因、错误 | `GET /api/boss/status` |
| Toast | 保存、激活等结果提示，文案来自后端 message 或固定文案 | — |

### 2.2 运行胶囊状态表

| 状态 | 胶囊显示 | 可用动作 |
|---|---|---|
| IDLE / COMPLETED / FAILED | 空闲 / 已完成 / 失败 | **开始投递**（无二次确认，后端有完整安全链路） |
| STARTING / RUNNING | 启动中 / 运行中（色点呼吸） | **停止投递**（先确认，绑定 runId）；**强制复位**（危险确认） |
| PAUSED_VERIFICATION | 等待人工验证 | **继续任务**；**强制复位** |
| STOPPING | 停止中 | 按钮禁用 |

API：`POST /api/boss/start`、`POST /api/boss/stop?runId=`、`POST /api/boss/verification/resume`、`POST /api/boss/force-reset?confirm=true&runId=`。

### 2.3 登录门（AuthGate）

居中卡片：令牌输入框（默认隐藏，可显示/隐藏）+「登录管理台」。失败显示错误；429 `TOO_MANY_ATTEMPTS` 显示节流提示。令牌只随登录请求发送，不写任何浏览器存储。`GET /api/session/status` 的 `authEnabled=false` 时直接放行。

---

## 3. 各空间设计

### 3.1 今日

**布局**（自上而下）
1. 标题行：“今日”+ 生效模板 + 一句提醒“开始投递不是自动跑，真实发送仍由后端安全链路决定”。
2. **4 个数字卡**：今日文字确认（只读）｜待人工核对（UNKNOWN，警告色边框，点击进待办·待核对）｜待放行（点击进待办·待放行）｜建议跟进（点击进待办·跟进）。
3. 左栏：**投递管线**（9 阶段，柱高 = 通过该阶段的岗位数；点击阶段即筛选下方事件流）+ **事件流**。
4. 右栏：**今日预算环**（used / reserved / uncertain / remaining，以后端口径为准，前端不重算）+ **聊天状态同步**（今日/昨日 × 已读/已回复/未读/未知，来源 AUTO/MANUAL/HR）。

| 控件 | 行为 | API |
|---|---|---|
| 阶段按钮 | 筛选事件流；再点取消；顶部出现“筛选：xx ✕” | — |
| 读取今日投递状态 | 只读 BOSS 聊天状态，不发送 | `POST /api/boss/status-sync` → `GET /api/boss/status-sync/summary` |
| 数字卡 | 跳转到待办对应分类 | — |

数据：`GET /api/boss/status`、`GET /api/boss/delivery/summary`、SSE `GET /api/boss/stream`（沿用共享订阅层，旧 runId 事件不得污染新任务）。

### 3.2 待办（决策中枢）

**布局**：三栏 —— 左**分类栏**（全部 / 待核对 UNKNOWN / 待放行 / 建议跟进 / 图片补发，各带数量）｜中**列表**｜右**详情与决策区**。

| 类型 | 来源 | 决策动作 | API | 关键文案 |
|---|---|---|---|---|
| 待核对 | attempts 文字状态 UNKNOWN | 选择裁定结果 + 原因 → **提交人工核对** | `POST /api/boss/attempts/{attemptId}/resolve`（含 `expectedVersion`） | “UNKNOWN 不计入成功…” |
| 待放行 | manual-releases PENDING | **批准入队** / **拒绝** | `POST /api/boss/manual-releases` | “批准后下一轮执行，不立即发送；AI REVIEW 批准后执行时仍重新 AI 评估；身份、预算、文字+图片确认不可绕过；失败可重试” |
| 建议跟进 | followups SUGGESTED | **批准入队** / **拒绝** | `POST /api/boss/followups/{planId}/approve｜reject`（含 `expectedVersion`） | “批准只允许下一轮任务执行，不会立即发送；联系人回复/接管/停止会使批准失效” |
| 图片补发 | 文字已确认 + 图片失败/未知 | **生成补发图片计划** | `POST /api/boss/attempts/{attemptId}/resend-image` | “生成计划不自动执行”；生成后进入“建议跟进”，仍需批准 |

**决策流程（统一）**：填写原因（必填，≤500 字，带计数）→ 点击动作 → 详情区内出现**二次确认条**（“返回 / 确认”）→ 确认后结果回写，条目变灰并显示结果文案，自动选中下一条。没有弹层套弹层。

**不允许**：任何“一键批准”“全部批准”快捷入口；键盘快捷键只能用于移动选中，不能触发批准。

**错误**：409 版本冲突在详情区顶部红色提示，数据刷新到最新，不自动重试。

详情区固定展示：类型与标识（attemptId / evaluationId / planId、版本）、原因说明、规则/AI 结论、事件证据时间线、本类型的安全说明。

### 3.3 记录

顶部分段切换 4 种实体：**投递明细 / 评估记录 / 任务记录 / 联系人**。共用一套工具栏与表格：搜索框、实体专属筛选开关、重置、分页（`page` 从 1 起，`size`）。

| 实体 | 列 | 专属筛选 | 展开内容 |
|---|---|---|---|
| 投递明细 | 编号、岗位、类型、文字状态、图片状态、时间 | 只看待核对（`textStatus=UNKNOWN`） | 结果、关联评估、状态说明；UNKNOWN/图片失败行带“去待办处理 →” |
| 评估记录 | 编号、岗位、规则结果、AI 结果、版本、时间 | 每岗位仅最新（`latestOnly`）；**用当前规则重评** | 规则/AI 原因、evidence；注明“REVIEW 不是发送成功” |
| 任务记录 | runId、状态、模板、开始、停止原因/错误 | 只看失败（`status=FAILED`） | runId、模板、平台、时间、停止原因、错误 |
| 联系人 | 联系人、公司、回复状态、人工接管、停止联系 | — | 内嵌**标记状态**表单 |

- 重评：`POST /api/boss/evaluations/recheck`，只新增评估，不发送、不改投递状态；结果条展示 evaluated/passed/rejected/reviewed。
- 联系人标记：回复状态下拉（UNKNOWN/REPLIED/REJECTED/INTERVIEW/NO_REPLY）+ 人工接管开关 + 停止联系开关 + 原因（必填 ≤500）→ **保存标记** → 确认弹窗 → `PUT /api/boss/contacts/{contactKey}/state`（带 `expectedVersion`）。文案：“只记录人工判定，不读取或发送任何消息”。409 需提示并重新拉取详情。

### 3.4 配置

**布局**：左**模板栏**（名称、方向、版本，生效中徽章；支持拖拽排序 `PUT /api/boss/templates/order`）｜右**模板编辑区**。

- 编辑区头部：模板名 + 生效徽章 + **激活**（确认，说明“下一轮生效”）/ **复制为新模板** / **用当前配置覆盖**（确认）/ **删除**（确认；生效模板不可删，后端 409）。
- 四个分区卡片：
  1. 搜索条件（城市、薪资、经验…，选项来自 `GET /api/boss/config/options/{type}`）
  2. 筛选规则（HR 活跃过滤、拒绝关键词；规则版本 + **预览当前规则**，预览只读、不发送、不写评估；版本冲突 `RULE_VERSION_CONFLICT` 需提示并刷新）
  3. AI 评估（启用开关、技能介绍/提示词）
  4. 简历与基本信息（上传/预览模板简历，≤10MB；模板名称）
- **模型服务**为全局设置，单独放在最下方，虚线边框以区别于模板配置：重新读取 / 保存模型配置（API_KEY 掩码原样提交 = 保持不变）。
- **保存条**：仅当有改动时从底部浮出——“有 N 处未保存改动 · {active 保存立即应用 | 非 active 只更新快照，需激活后生效}”+ 重新加载 / 保存配置。字段旁有未保存小圆点。这是整包保存的**唯一入口**（`PUT /api/boss/templates/{id}/config`）。

> `/boss/advanced` 与 `/ai-config` 不再是独立页面，对应内容就是配置空间里的“筛选规则”“AI 评估”分区；`PUT /api/boss/config {enableAi}` 与 `POST /api/ai/config` 的调用保持不变。

### 3.5 洞察

- 日期范围分段：今天 / 最近 7 天 / 最近 30 天 / 自定义（开始/结束日期，跨度 ≤366 天）；刷新。
- 4 个指标卡（文字确认、评估数、待核对、收到回复），点击切换趋势指标。
- 趋势折线图（切换时重绘动画）+ 采集来源条形图。
- 跟进策略：已有措施 / 后续待做（只读说明）。
- API：`GET /api/boss/metrics/overview｜series｜collection`。400 `INVALID_RANGE` / `INVALID_METRIC` 直接展示后端 message；空数据展示空态，不得伪造 0 趋势。

---

## 4. 状态与颜色语义（全局一致）

| 语义 | 用色 | 说明 |
|---|---|---|
| 强调 / 进行中 / 可操作 | `--accent` | 只用一个强调色 |
| 已确认（真实送达） | `--ok` | **只**用于后端确认的结果 |
| UNKNOWN / REVIEW / PENDING / 待核对 | `--warn` | 绝不使用成功色 |
| 失败 / 危险操作 | `--bad` | 强制复位、删除、失败状态 |
| 已拒绝 / 无状态 | `--muted` | 弱化 |

“已批准·已入队”使用强调色浅底而不是成功绿，避免被误读为“已发送”。

---

## 5. 视觉系统

- **字体**：Inter（西文与数字，启用 tabular-nums）+ 系统中文字体；ID、版本、时间、runId 用 JetBrains Mono。
- **字号**：页面标题 30 / 空间内卡片标题 15 / 正文 14 / 辅助 12–13 / 大数字 38–46（字重 500，字距 -0.035em）。
- **圆角**：大卡 20、中卡 14–16、按钮与输入 10–12、胶囊 999。
- **间距**：页面左右 32、卡片内边距 22–28、区块间距 20。
- **触控**：按钮高度 ≥ 40（主要 44），图标按钮 40×40。
- **版心**：最大 1360，居中；窄屏下各栏 `flex-wrap`，表格容器横向滚动。

### 5.1 三套配色（CSS 变量，可直接放进 `app/globals.css`）

```css
/* 通用状态底色在深色里用透明度，浅色里用实色浅底 */

/* A · 石墨铜 Graphite & Copper（深色）*/
[data-theme="graphite"]{
  --bg:#0E0F10; --surface:#16171A; --surface2:#1D1F23;
  --line:#2A2C31; --line-strong:#3A3D43;
  --text:#EDEBE7; --muted:#A09D96;
  --accent:#D99467; --accent-ink:#1A1208;
  --accent-soft:rgba(217,148,103,.12); --accent-mid:rgba(217,148,103,.42);
  --ok:#7FC8A0;   --ok-soft:rgba(127,200,160,.13);
  --warn:#E5B567; --warn-soft:rgba(229,181,103,.13);
  --bad:#EE8A7E;  --bad-soft:rgba(238,138,126,.13); --bad-ink:#1F0B08;
  --bar-bg:#0A0B0C; --bar-text:#EDEBE7; --bar-muted:#A09D96;
  --bar-line:rgba(255,255,255,.09); --bar-hover:rgba(255,255,255,.05); --bar-active:rgba(217,148,103,.16);
  --b-accent:#D99467; --b-ok:#7FC8A0; --b-warn:#E5B567; --b-bad:#EE8A7E; --b-ink:#1A1208;
  --shadow:0 1px 0 rgba(255,255,255,.03) inset,0 18px 40px rgba(0,0,0,.35);
  --scrim:rgba(5,6,7,.64);
}

/* B · 瓷白靛 Porcelain & Indigo（浅色）*/
[data-theme="porcelain"]{
  --bg:#F3F4F6; --surface:#FFFFFF; --surface2:#F7F8FA;
  --line:#E4E6EB; --line-strong:#CDD1D9;
  --text:#14161C; --muted:#5F6470;
  --accent:#4A45D6; --accent-ink:#FFFFFF;
  --accent-soft:rgba(74,69,214,.08); --accent-mid:rgba(74,69,214,.4);
  --ok:#16795A;   --ok-soft:#E3F3EC;
  --warn:#9A5B00; --warn-soft:#FBF0DB;
  --bad:#B42D2D;  --bad-soft:#FBE7E5; --bad-ink:#FFFFFF;
  --bar-bg:#FFFFFF; --bar-text:#14161C; --bar-muted:#5F6470;
  --bar-line:#E4E6EB; --bar-hover:#F3F4F6; --bar-active:#FFFFFF;
  --b-accent:#4A45D6; --b-ok:#16795A; --b-warn:#9A5B00; --b-bad:#B42D2D; --b-ink:#FFFFFF;
  --shadow:0 1px 2px rgba(20,22,28,.04),0 12px 32px rgba(20,22,28,.06);
  --scrim:rgba(20,22,28,.38);
}

/* C · 象牙松 Ivory & Pine（浅色内容区 + 深绿顶栏）*/
[data-theme="ivory"]{
  --bg:#F4F1E8; --surface:#FFFDF8; --surface2:#F9F6EC;
  --line:#E3DECE; --line-strong:#CFC8B2;
  --text:#1A231F; --muted:#626C66;
  --accent:#1F6B4F; --accent-ink:#F4FBF7;
  --accent-soft:rgba(31,107,79,.1); --accent-mid:rgba(31,107,79,.4);
  --ok:#1F6B4F;   --ok-soft:#E1EFE7;
  --warn:#8F5A0A; --warn-soft:#F6EACB;
  --bad:#A63A2B;  --bad-soft:#F6E1DC; --bad-ink:#FFFFFF;
  --bar-bg:#11251D; --bar-text:#EAF2ED; --bar-muted:#9DB2A7;
  --bar-line:rgba(255,255,255,.12); --bar-hover:rgba(255,255,255,.06); --bar-active:rgba(255,255,255,.13);
  --b-accent:#8FD3B2; --b-ok:#8FD3B2; --b-warn:#F0C46E; --b-bad:#F29B8D; --b-ink:#0B1F16;
  --shadow:0 1px 2px rgba(26,35,31,.04),0 14px 34px rgba(26,35,31,.07);
  --scrim:rgba(17,37,29,.48);
}
```

| 方案 | 气质 | 适合场景 | 取舍 |
|---|---|---|---|
| **A 石墨铜** | 夜间工作台，数字发光，沉稳 | 晚上长时间盯盘 | 深色下状态色需单独调亮，已处理 |
| **B 瓷白靛** | 清爽的产品感，信息密度高 | 白天办公、投屏 | 最“标准”，辨识度靠靛蓝 |
| **C 象牙松** | 纸质温度 + 金融终端的稳重 | 追求质感与耐看 | 顶栏深色需 `--b-*` 一组提亮色 |

> 浅色三项对比度：正文与背景均 ≥ 4.5:1；`--warn` 在浅底上已选用 #9A5B00 / #8F5A0A 以保证可读。上线前请对实际页面再跑一遍对比度检查。

---

## 6. 动效规范

| 动效 | 时长 / 曲线 | 触发 | 目的 |
|---|---|---|---|
| 空间切换滑块 | 420ms，`cubic-bezier(.2,.8,.2,1)` | 切换空间 | 告诉用户“我在哪” |
| 内容上浮淡入 | 600ms，`cubic-bezier(.2,.7,.2,1)`，错峰 40–60ms | 进入空间、列表行出现 | 建立阅读顺序 |
| 右侧详情切换 | 380ms，右移 14px 淡入 | 选中不同待办 | 明确内容已更换 |
| 数字计数 / 预算环生长 / 管线柱生长 | 1000ms，ease-out-cubic | 首次进入今日、洞察 | 强化“数据活的” |
| 运行中色点呼吸 | 1.9s 循环 | RUNNING / STARTING / PAUSED | 表达“正在进行” |
| 进行中阶段流光 | 2.1s 循环 | RUNNING 的当前阶段 | 指示当前位置 |
| 弹窗淡入+位移+缩放 | 300ms | 确认弹窗、跳转面板 | 聚焦 |
| 确认条下落 | 350ms | 待办中点击动作后 | 提示需要二次确认 |
| 折线重绘 | 1000ms | 切换范围/指标 | 表达数据已更新 |
| 卡片悬浮 | 280ms，上移 2px | hover | 可点击反馈 |
| 按钮按下 | 150ms，缩放 .97 | active | 触感 |

**降级**：`@media (prefers-reduced-motion: reduce)` 下关闭全部动画与过渡；数字直接显示终值。

---

## 7. 落地到 Next.js 的建议

1. **路由策略（需你确认）**：契约原要求保留 15 个路由页。新结构建议：
   - 新增 5 个主路由：`/boss`（今日）、`/boss/inbox`、`/boss/ledger`、`/boss/setup`、`/boss/insights`；
   - 旧路由保留为**重定向**到新路由并带查询参数，例如 `/boss/reviews` → `/boss/inbox?kind=release`，`/boss/attempts?textStatus=UNKNOWN` → `/boss/inbox?kind=resolve`，`/boss/contacts` → `/boss/ledger?entity=contacts`；这样书签与外链不失效。
   - `/` → `/boss` 跳转保留。
2. **壳**：在 `app/boss/layout.tsx` 放顶栏（空间滑块 + 运行胶囊 + 跳转面板 + 登录门），替换 `Sidebar` 和 `GlobalRunBar`。运行胶囊直接复用 `use-boss-status` 与现有 `RunControlCard` 的动作函数，保证只有一份状态机。
3. **令牌**：把 §5.1 放进 `app/globals.css`（现有结构为浅色为主 + `.dark` 覆盖，可改为 `data-theme` 或保留明暗两套）；Tailwind 里用 `var(--…)` 映射颜色。
4. **组件拆分**：`RunCapsule`、`SpaceTabs`、`CommandPalette`、`StageFunnel`、`BudgetRing`、`DecisionPane`（待办右侧，三种表单共用）、`LedgerTable`（4 种实体共用列配置）、`TemplateEditor`、`SaveBar`、`ConfirmDialog`（单一实现，全站复用）。
5. **请求**：全部走 `front/lib/api.ts` / `auth.ts`；不新增裸 `fetch`；不新增后端接口。
6. **构建与部署**：沿用 `next build` → `node scripts/copy-dist.mjs`（需补齐新增路由的 RSC 预取别名）。

---

## 8. 与交接契约的差异（均为有意改动，请确认）

| 契约条款 | 本方案 | 原因 |
|---|---|---|
| §0.1 保留 15 个路由页、不合并页面 | 合并为 5 个空间，旧路由做重定向 | 你要求不拘泥原结构、彻底重构 |
| §1.4 GlobalRunBar 不放开始/停止按钮 | 顶栏运行胶囊放置全部运行控制，且**全站仅此一处** | 该条款的目的是避免第二套状态机；胶囊取代了总览里的 RunControlCard，仍是唯一一套 |
| §1.3 侧边栏徽章 = 待核对数 | 标签徽章 = 全部待处理数；UNKNOWN 数在今日卡片与待办分类栏单独显示 | 待办合并了多类事项 |
| §2.5 明细行点击开抽屉 | 待核对在待办里处理；记录里行内展开 | 减少层级 |
| §2.12 模板行菜单（改名称/描述、上传简历等） | 合并进编辑区分区；“改名称”为“简历与基本信息”里的字段 | 单页编辑更直接 |

其余均保持：API 路径与字段、认证与 CSRF、409 展示并刷新、确认弹窗清单（停止、强制复位、激活、覆盖、删除、放行、跟进、联系人标记、UNKNOWN 核对）、关键文案、审计信息（状态、原因、版本、时间、证据、禁用原因）。

---

## 9. 原型未覆盖 / 需要实现时补齐

- 模板拖拽排序的真实交互（原型只有手柄）；模板“改名称/描述”独立入口。
- `/boss/rules` 的版本历史载入列表、全部内置样例批量预览、自定义样例查询（原型只有入口说明与单次预览）。
- 任务记录的状态下拉筛选、评估记录的 runId / 规则结果 / AI 结果筛选（原型只做了搜索与专属开关）。
- 登录门的 `GET /api/session/status` 启动检查、健康轮询、30s 状态轮询。
- 所有空态、加载态、网络错误态（保留已有数据并显示错误，不清空成空态）。
- 无障碍：焦点环已在原型中定义；弹窗焦点陷阱、列表键盘导航需在实现时补齐。
- UI E2E：需新增“待办”决策流程、运行胶囊状态机、跳转面板的用例，并对照契约 §6 验收清单逐项复核。
