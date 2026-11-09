# OfferChaser 文档索引

本目录是 OfferChaser 当前代码与运行事实的权威文档集。README 级的快速介绍在仓库根目录 [README.md](../README.md)，
产品与运维手册在 [项目说明.md](../项目说明.md)，第一次使用可看 [guides/README.md](guides/README.md)。
**本目录不记录过时内容**；内容按当前代码、数据库 V13 与最新门禁结果维护。第三方来源、许可证合规与贡献划分单独记录在 [14-open-source-compliance](14-open-source-compliance.md)。

## 当前基线

| 项目 | 数值 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.5.16 · MyBatis-Plus · SQLite JDBC |
| 浏览器自动化 | Playwright Java 1.62 + Patchright driver · 持久化 profile `browser-data/` |
| 管理台 | Next.js 16 静态导出 · React 19 · Tailwind · shadcn/ui |
| 服务端口 | 后端 API `127.0.0.1:9527` · 管理台静态 `127.0.0.1:6866` |
| 数据库 | `db/offerchaser.db` · `PRAGMA user_version=13` · **25 张表** |
| 测试 | **1118 tests / 0 failures / 0 errors / 54 skipped / 119 类** |
| 浏览器/DOM 夹具 | **79/79**（见 [05-testing](05-testing.md) 的口径拆分） |
| 管理台 UI E2E | **19/19** |
| 前端页面 | **5 个空间 / 20 个路由**（含旧路由重定向；静态导出 22 个 HTML + 登录门） |

## 阅读顺序

1. **想先跑起来** → [06-operations](06-operations.md)
2. **想理解整体** → [01-architecture](01-architecture.md)
3. **想看数据与状态** → [02-runtime-data](02-runtime-data.md)
4. **想接 API/改前端** → [03-api](03-api.md)
5. **关心安全不变量** → [04-security](04-security.md)
6. **改代码后怎么验证** → [05-testing](05-testing.md)
7. **设计语言与页面** → [07-ui-design](07-ui-design.md)
8. **接手 UI 重构** → [10-ui-redesign-v3](10-ui-redesign-v3.md)
9. **旧前端交接契约（API 仍有效）** → [09-frontend-ui-contract](09-frontend-ui-contract.md)
10. **第一次使用** → [11-beginner-guide](11-beginner-guide.md)
11. **产品全貌与演进** → [12-product-prd](12-product-prd.md)
12. **接下来做什么** → [08-roadmap](08-roadmap.md)
13. **上游归属与许可证边界** → [14-open-source-compliance](14-open-source-compliance.md)

## 文档地图

| 文档 | 内容 | 主要读者 |
|---|---|---|
| [README.md](README.md) | 索引、基线、阅读顺序 | 所有人 |
| [01-architecture.md](01-architecture.md) | 分层/模块/目录、进程与页面结构、主要数据流、设计取舍 | 开发者 |
| [02-runtime-data.md](02-runtime-data.md) | 启动/停止/托盘、任务状态机、V13 的 25 张表分组、迁移/备份 | 开发者、运维 |
| [03-api.md](03-api.md) | 按控制器分组的 API 清单、认证要求、错误语义 | 前端、集成方 |
| [04-security.md](04-security.md) | 本机会话/CSRF/回环、发送 fail-closed、预算/确认/人工放行边界 | 开发者、安全审阅 |
| [05-testing.md](05-testing.md) | 测试体系、当前数字、门禁命令、真实页面验证边界 | 开发者、验收 |
| [06-operations.md](06-operations.md) | 安装/启动/停止/升级/备份恢复/日志/排障 | 使用者、运维 |
| [07-ui-design.md](07-ui-design.md) | UI V2 设计语言、组件、页面清单与交互规范 | 前端、设计 |
| [08-roadmap.md](08-roadmap.md) | 已知限制、下一步优先级、非目标 | 产品、开发者 |
| [09-frontend-ui-contract.md](09-frontend-ui-contract.md) | 页面/按钮/API/交互/确认/验收清单；UI 重构交接 | 前端、重构 agent |
| [10-ui-redesign-v3.md](10-ui-redesign-v3.md) | V3 5 空间 UI 设计规格与已落地决策 | 前端、设计、重构 agent |
| [11-beginner-guide.md](11-beginner-guide.md) | 新手 7 步快速上手（推荐先看） | 第一次使用者 |
| [11a-beginner-guide-full.md](11a-beginner-guide-full.md) | 新手上手完整细节与异常处理 | 第一次使用者、支持 |
| [12-product-prd.md](12-product-prd.md) | 从 0 到当前版本的完整 PRD、验收口径与路线图 | 产品、开发、验收 |
| [13-environment-compatibility.md](13-environment-compatibility.md) | 发布包换电脑/全新安装的兼容性检查与边界 | 使用者、运维 |
| [14-open-source-compliance.md](14-open-source-compliance.md) | 上游归属、逐文件清单、许可证判定依据与贡献划分措辞 | 维护者、法务、对外说明 |
| [guides/README.md](guides/README.md) | 从配置到投递的 4 步图解使用说明 | 第一次使用者 |
| [../DISCLAIMER.md](../DISCLAIMER.md) | 法律、平台规则、隐私与非商业许可边界 | 使用者、分发者 |

## 文档约定

- **以代码为准**：文档与代码不一致时，先改代码或改文档，不允许“文档描述了但代码没有”的能力。
- **数字可复现**：测试/页面/表数量附有生成命令（见 [05-testing](05-testing.md)）。
- **安全默认 fail-closed**：涉及发送、放行、预算的描述必须写清“不确定时不发送”的边界（见 [04-security](04-security.md)）。
- **不写承诺**：路线图只写可验证的下一步，不写日期承诺（见 [08-roadmap](08-roadmap.md)）。
- **敏感信息不入库文档**：不在文档中记录真实 token、Cookie、API Key、账号数据。

## 维护规则

- 数据库结构、迁移版本、表数量变化 → 更新 [02-runtime-data](02-runtime-data.md)。
- 新增/修改控制器或接口 → 更新 [03-api](03-api.md)。
- 认证/发送链路/人工放行语义变化 → 更新 [04-security](04-security.md) 并补测试。
- 门禁数字变化 → 更新本页基线表与 [05-testing](05-testing.md)。
- 页面清单/设计令牌变化 → 更新 [07-ui-design](07-ui-design.md)。
- 上游归属、许可证或贡献划分变化 → 更新 [14-open-source-compliance](14-open-source-compliance.md)，并同步根目录 `LICENSE`、`NOTICE`、`AUTHORS.md`。发布包和 jar 内必须带 `LICENSE` 与 `NOTICE`。
