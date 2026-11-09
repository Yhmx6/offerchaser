# 06 · 运维

## 1. 环境与安装

| 项 | 要求 |
|---|---|
| 操作系统 | Windows 10/11 |
| JDK | 21（发行包内含 `.jdk/jdk-21.0.13+9`；开发机可用 JAVA_HOME 指向任意 JDK 21） |
| 浏览器 | 本机 Google Chrome（Playwright 以 channel=chrome 启动；测试可指定 `BOSS_TEST_CHROME`） |
| Node.js/pnpm | 仅修改前端时需要；运行使用已构建的 `src/main/resources/dist` |
| 数据库 | 发行包提供 `db/offerchaser.db` 模板；首次运行需存在该库文件 |

目录约定：程序目录下保留 `db/`、`data/`、`browser-data/`、`target/`；这些都是本机运行数据，
**不属于**可分享的代码/发行内容。

## 2. 启动与停止

| 操作 | 方式 |
|---|---|
| 托盘启动 | 双击 `OfferChaser.vbs`（隐藏窗口、驻留托盘，自动打开管理台） |
| 控制台启动 | 双击 `启动.bat` 或运行 `OfferChaser.ps1`（调试输出可见） |
| 开发启动 | `start-local.ps1`（`gradlew bootRun`，需 JDK 21 与 `db/offerchaser.db`） |
| 停止 | 双击 `停止OfferChaser.vbs` 或托盘菜单“停止服务”：先停止投递任务、等待 worker 退出，再退出进程 |
| 状态 | 托盘工具提示/`OfferChaser.ps1 -Status`；也可访问 `GET /api/health` |

管理台：<http://127.0.0.1:6866>；API：<http://127.0.0.1:9527>。
首次启动约 3–15 秒（Playwright 初始化较慢，管理台会先打开）。

启动器会：
- 检测 9527 已监听则直接打开管理台，不重复启动；
- 使用内置 JDK，清理外部 `SERVER_PORT/SERVER__PORT`，避免端口被环境变量覆盖；
- 日志：`target/logs/launcher.out.log`、`target/logs/launcher.err.log`。

## 3. 首次配置

1. 打开管理台，输入 `data/local-session-token` 中的本机令牌解锁（令牌文件由首次启动生成）。
2. 进入「配置中心」设置模型服务地址/模型名/API Key（AI 评估与话术生成依赖）。
3. 创建并激活方向模板：关键词、城市、行业、筛选规则、AI 介绍/提示词、图片简历、采集策略、投递开关。
4. 确认 BOSS 登录：托盘/管理台打开 BOSS 页面扫码登录，登录态保存在 `browser-data/`。
5. 需要投递时手动点击开始；系统不会自动开始真实投递。

## 4. 升级与迁移

- 直接替换程序文件（保留 `db/`、`data/`、`browser-data/`）。启动时自动执行数据库迁移（V1–V13）。
- 迁移前自动备份到 `db/backups/pre-upgrade/`（保留最近 5 份），迁移后校验备份可打开。
- 迁移失败时 `SchemaMigrationService.isReady=false`：任务启动被阻止，先看日志定位。
- 迁移只追加、不修改已发布 DDL；不要手工改表结构或删除 `schema_migration` 记录。
- 回滚：停止应用 → 用升级前备份替换 `db/offerchaser.db` → 启动；代码也回退到对应版本。

## 5. 备份与恢复

| 类型 | 位置 | 保留 |
|---|---|---|
| 迁移前自动备份 | `db/backups/pre-upgrade/` | 最近 5 份 |
| 任务前/手动备份 | `db/backups/pre-run/` | 最近 10 份 |
| 平面旧备份 | `db/backups/*.db` | 仅兼容读取，不自动清理 |

手动备份：管理台维护接口或 `POST /api/maintenance/backup`（需本机会话+CSRF）。备份使用 SQLite `VACUUM INTO`，
生成一致性快照；不要用复制文件的方式备份正在写入的库。

恢复步骤：
1. 托盘停止服务，确认 9527 无监听；
2. 备份当前故障库（改名保留）；
3. 将备份文件复制为 `db/offerchaser.db`；
4. 启动应用，确认 `GET /api/maintenance/status` 的 schemaVersion/完整性检查；
5. 登录管理台抽查任务/明细/模板是否正常。

## 6. 日志

| 文件 | 内容 |
|---|---|
| `target/logs/offerchaser.log` | 应用日志；滚动 10MB/份、保留 30 份 |
| `target/logs/launcher.out.log` | 启动器 stdout |
| `target/logs/launcher.err.log` | 启动器错误 |
| `debug.log`（根目录） | 调试输出（如存在）；不作为长期日志 |

排障时优先收集：应用日志中最近一次启动的迁移/浏览器初始化/登录/任务预检片段；不要外发含 Cookie/Key/账号的原文。

## 7. 常见故障排查

| 现象 | 排查顺序 |
|---|---|
| 管理台打不开 | 检查 9527 是否监听；`launcher.err.log`；JDK 路径；防火墙/代理；尝试 `http://localhost:6866/boss` |
| 端口被占用 | 9527/6866 被其他程序占用：先停其它实例；不要改为公网地址 |
| 浏览器初始化失败 | Chrome 未安装/路径异常；`browser-data/` profile 损坏（先备份后重命名再启动，会重新登录）；磁盘空间 |
| BOSS 登录失效 | 管理台重新扫码登录；检查 cookie 表/浏览器窗口是否被手动关闭 |
| 任务无法启动 | 看预检提示：迁移未就绪、配置缺关键词/城市、AI 配置不合法、预算耗尽、浏览器未就绪、已有任务在运行 |
| 等待人工验证 | 平台出现安全验证：按页面完成验证后，在管理台点“继续任务”；等待有上限，不会无限空转 |
| 状态同步 409 | 已有同步/投递/浏览器操作正在进行；等当前操作结束再点；刷新可见失败记录 |
| 数据库锁/写入失败 | 是否同时开了多个实例；停止多余实例；检查磁盘空间；必要时停止后从备份恢复 |
| AI 超时/不合法 | 检查模型服务地址/Key/网络；请求超时 45s；超时不会兜底发送原文；在评估记录查看错误码 |
| 会话过期/401 | 令牌文件是否被替换；重新登录；`auth-enabled` 是否被改为 false |
| CSRF 403 | 写接口必须带 `X-Requested-With: offerchaser`；用管理台页面操作而非手写 curl |
| 页面白屏但 API 正常 | dist 是否构建/复制；浏览器缓存强刷；静态服务 6866 是否被前端 dev 占用 |

## 8. 数据与安全注意

- `data/`、`db/`、`browser-data/`、`.env` 均含敏感信息：不提交、不外发、不放入共享目录。
- 不要把 `server.address` 改为 `0.0.0.0`；管理接口只应本机访问。
- 真实投递只能手动开始；停止服务会先停止任务，不建议直接杀进程。
- 分享日志/数据库前先脱敏（账号、公司、HR、Cookie、API Key）。
- 定期把 `db/offerchaser.db` 与 `data/boss/` 复制到本机其他盘作为冷备；备份文件同样按敏感数据处理。
