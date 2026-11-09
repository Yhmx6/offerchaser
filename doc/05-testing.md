# 05 · 测试

## 1. 测试目标

1. **不误发**：任何不确定路径都有测试证明“不会发送/不会记成功”。
2. **数据不丢**：迁移、备份、预算/台账事务可恢复、可重入。
3. **契约稳定**：控制器/前端/迁移/配置的对外契约有测试锁定。
4. **真实可跑**：关键页面与浏览器自动化用真实 Chrome 的本地夹具验证，不依赖招聘网站。

## 2. 当前基线（以本机最新串行门禁为准）

| 指标 | 数值 |
|---|---|
| 全量测试 | **1118 tests / 0 failures / 0 errors / 54 skipped / 119 类** |
| 浏览器/DOM 夹具 | **79/79**（口径见 §4） |
| 管理台 UI E2E | **11/11** |
| 前端页面 | 5 个空间 / 20 个路由（含旧路由重定向；静态导出 22 个 HTML） |
| 数据库 | V13，25 张表 |

46 skipped = 环境变量门禁的测试（不设置环境变量时跳过）：3 个浏览器/DOM 夹具类 + 管理台 UI E2E。
设置对应环境变量后它们会实际执行：DOM 79/79、UI E2E 11/11。

## 3. 测试分层与目录

```
src/test/java/com/offerchaser/
├─ application/   单元/集成：persistence、delivery、ai、service、init、security、controller 契约
├─ worker/boss/   DOM 夹具：BossChatNavigator、ChatSendConfirmer（Mockito）、真实浏览器确认
└─ verification/  独立验收：迁移/备份/预算/规则/模板/状态同步/放行/前端交付/UI E2E
```

| 层 | 做法 | 覆盖重点 |
|---|---|---|
| 单元 | JUnit 5 + Mockito | 规则引擎、薪资解析、AI 解析/校验、预算状态机、配置合并 |
| 持久化 | @TempDir 临时 SQLite（不碰真实库） | 迁移 V1–V13、备份、预算事务、台账、查询分页、人工核对 |
| 服务/控制器 | 真实临时 SQLite + mock 外部依赖 | 业务分类、模板优先级、状态同步、放行消费、错误码 |
| 前端交付检查 | 读源码/构建产物断言 | API 调用、无 mock、页面文案/路由、dist 同步 |
| 浏览器夹具 | Playwright + 本地 HTML/路由拦截 | 聊天导航三态、发送确认、状态读取；不连真实网站 |
| UI E2E | 静态导出 dist + Chromium + 拦截 API | 总览/模板编辑/拖拽/状态同步卡/侧栏去重 |

## 4. 浏览器/DOM 夹具口径

`79/79` = 以下命令实际执行数：

| 类 | 数量 | 说明 |
|---|---|---|
| `worker.boss.BossChatNavigatorDomTest` | 17 | 真实 Chromium：首聊/同页/弹窗/1s 确认窗口/停止/超时/旧输入框 |
| `worker.boss.ChatSendConfirmerBrowserDomTest` | 14 | 真实 Chromium：发送确认/身份核对/图片确认 |
| `worker.boss.ChatSendConfirmerDomTest` | 41 | Mockito：页面/Frame 级发送确认分支（同属 `*DomTest` 门禁） |
| `verification.BossChatStatusReaderDomTest` | 7 | 真实 Chromium：聊天状态只读解析（已读/送达/未读/首条消息） |

管理台 UI E2E：`verification.ManagementConsoleUiE2ETest` **11/11**（静态 dist + Chromium + API fixture，
覆盖总览只读、模板编辑保存、拖拽排序、状态同步卡、侧栏去重等核心路径）。

## 5. 常用命令

```powershell
$env:JAVA_HOME='D:\OfferChaser\.jdk\jdk-21.0.13+9'
cd D:\OfferChaser

# 全量门禁（默认会跳过环境变量门禁的夹具）
.\gradlew.bat clean test

# 浏览器/DOM 夹具（真实 Chrome，需设置环境变量）
$env:BOSS_CHAT_DOM_TEST='true'
$env:BOSS_TEST_CHROME='C:\Program Files\Google\Chrome\Application\chrome.exe'
.\gradlew.bat test --tests "com.offerchaser.worker.boss.*DomTest" `
  --tests "com.offerchaser.verification.BossChatStatusReaderDomTest"

# 管理台 UI E2E
$env:BOSS_UI_E2E='true'
.\gradlew.bat test --tests "com.offerchaser.verification.ManagementConsoleUiE2ETest"

# 定向测试
.\gradlew.bat test --tests "com.offerchaser.application.persistence.*"
.\gradlew.bat test --tests "com.offerchaser.verification.*"
```

- 浏览器路径不存在时：先确认 `BOSS_TEST_CHROME` 指向本机 Chrome；未设置时使用 Playwright 自带浏览器（需已下载）。
- 并行跑 Gradle 会互相清 `build/`；**门禁必须串行执行**，出现 `NoSuchFile/EOF` 时等其它构建结束再重跑。
- 真实数据库 `db/offerchaser.db` 不参与常规测试；迁移/备份测试一律在 `@TempDir` 副本上执行。

## 6. 真实页面验证边界

- 自动化测试**不连接 BOSS 网站**、不发送任何真实消息：浏览器夹具使用 `page.setContent` 或本地路由拦截。
- UI E2E 使用构建后的 `src/main/resources/dist` + 本地静态服务器，`/api/**` 全部由 fixture 响应；不启动 Spring、不连真实后端。
- 真实 BOSS 页面行为（DOM 结构、登录/验证、已读标记）只能在真实环境人工验证；变更相关选择器后必须做一次实机 smoke：
  登录 → 打开聊天列表 → 点击一个岗位（不发送）→ 检查状态读取/身份核对日志。
- 状态同步的“打开会话会被 BOSS 标记已读”属于平台行为；验收时注意区分。

## 7. 新增测试约定

1. 涉及真实发送的路径：必须同时有“失败/未知不发送”用例，不允许只测成功。
2. 涉及数据库：用 `@TempDir` 临时库，迁移测试只加新版本断言，不修改已发布 DDL。
3. 涉及控制器：覆盖成功外壳与 400/401/403/404/409 关键错误码。
4. 涉及页面交付：断言真实 API 调用、无 mock、dist 同步；不要只测文案。
5. 涉及浏览器：给夹具固定超时上限、避免真实 sleep 长等待；用 `waitForSelector/waitForFunction` 等确定性等待。
6. 修复 bug 时先补回归测试，再改代码；测试通过后串行跑全量门禁与两组浏览器/UI 门禁。
