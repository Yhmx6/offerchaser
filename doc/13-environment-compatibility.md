# OfferChaser 环境兼容性检查

> 检查日期：2026-11-09  
> 检查对象：桌面发布包 `OfferChaser-2.0.1`  
> 结论：**Windows x64 全新电脑可运行；需要本机 Chrome；不要直接复制用户数据。**

---

## 1. 结论先行

发布包已经自带：

- Windows x64 JDK **21.0.13**（`runtime/`）
- Node.js（`node/node.exe`）
- Patchright/Playwright 驱动（`patchright-driver/`）
- 后端 JAR（`OfferChaser.jar`）
- 前端静态资源（包含在 JAR 内）
- 干净数据库模板（`db/offerchaser.db.template`）

因此目标电脑**不需要安装 Java、Node.js 或 npm**。

但仍需要：

- Windows 10/11 **x64**；
- 本机安装 **Google Chrome**；
- 可访问 BOSS 直聘和你的 AI 接口；
- `9527`、`6866` 端口空闲；
- 首次启动后手动扫码登录 BOSS、配置 AI。

---

## 2. 已验证

### 2.1 启动器路径

`OfferChaser.vbs` 使用脚本所在目录；`启动.bat` 使用 `%~dp0`；`OfferChaser.ps1` 使用 `$PSScriptRoot`。

启动器中没有硬编码 `D:\OfferChaser` 这类安装路径，复制到其他目录后仍可启动。

### 2.2 干净副本启动

用不含 `db` 数据、`browser-data`、个人 `data` 的临时副本启动：

```text
health = true
session.authEnabled = false
session.authenticated = true
boss state = IDLE
db/offerchaser.db 自动创建
```

说明：

- 首次启动会自动创建运行目录；
- 缺少数据库时从 `db/offerchaser.db.template` 复制干净库；
- 不要求登录管理台；
- 不依赖当前机器的用户档案。

### 2.3 数据库

- 发布模板：`db/offerchaser.db.template`，schema V13，无业务数据；
- 首次启动复制为 `db/offerchaser.db`；
- 后续升级保留已有数据库，不覆盖；
- 空数据库直接启动会失败（应用启动时先读 `boss_config`），所以必须保留模板或已有数据库。

---

## 3. 当前发布包内的数据风险

当前的 `OfferChaser-2.0.1` 目录是从本机直接打出来的，包含：

```text
db/offerchaser.db        — 本机投递/配置数据
data/                    — 本机运行数据、模板/简历
browser-data/            — BOSS 登录 Cookie
```

这些内容**属于本机用户数据**，不要复制给其他人，也不要上传网盘公开分享。

如果只是自己换电脑：

1. 复制发布包的运行环境、JAR、启动脚本和文档；
2. 不要复制 `db/`、`data/`、`browser-data/`；
3. 保留 `db/offerchaser.db.template`；
4. 在新电脑首次启动后重新扫码登录 BOSS、配置 AI、重新建模板。

或者：在旧电脑上导出需要的配置/模板，再在新电脑重新配置。

---

## 4. 兼容矩阵

| 环境 | 结果 | 说明 |
|---|---|---|
| Windows 10/11 x64 | ✅ 支持 | 发布包自带 x64 JDK/Node |
| Windows ARM64 | ⚠️ 未验证 | 自带 `runtime` 是 x64，可能无法直接运行 |
| Windows 7/8 | ❌ 不支持 | Spring Boot 3 / Chrome 新版本不保证 |
| macOS | ❌ 不支持 | 启动器、VBS、PowerShell 和运行时均为 Windows |
| Linux | ❌ 不支持 | 同上 |
| 无 Google Chrome | ❌ 不能完整运行 | 自动化使用系统 Chrome，不内置浏览器 |
| 端口 9527/6866 被占用 | ❌ 启动失败 | 启动器会提示或拒绝启动 |
| OneDrive/网盘同步目录 | ⚠️ 不推荐 | `browser-data` 可能被同步/锁定；建议放本机非同步目录 |
| 路径含空格/中文 | ✅ 一般可用 | 建议避免过深路径和超过 260 字符 |
| 无网络/无法访问 BOSS | ❌ 不能投递 | 需要 BOSS 可访问 |
| 无 AI 接口 | ⚠️ 只读/规则可部分使用 | AI 匹配、话术、岗位解读不可用 |

---

## 5. 推荐安装步骤（另一台电脑）

1. 复制一份**干净发布包**，不要带旧 `db/`、`data/`、`browser-data/`。
2. 解压到本机非 OneDrive 路径，例如：

   ```text
   D:\OfferChaser
   ```

3. 确认本机已安装 Google Chrome。
4. 确认 `9527`、`6866` 端口没有被占用。
5. 双击 `OfferChaser.vbs`。
6. 打开 `http://127.0.0.1:6866/boss`。
7. 在配置中心填写 AI 配置、创建/激活方向模板、上传图片简历。
8. 扫码登录 BOSS，手动开始第一次任务。

---

## 6. 已知兼容性限制

- 浏览器自动化依赖 BOSS 当前 DOM；页面结构变化会失败并停住，不会误发。
- 首次启动需要 Chrome 可被启动；杀毒软件/防火墙可能拦截。
- BOSS Cookie 不适合作为跨机迁移手段，建议重新扫码登录。
- 同一台电脑跑多份 OfferChaser 时，启动器通过目录识别进程，不会误杀其他副本；但端口仍然互斥。
- 发布包默认免登录，只应在本机回环使用；不要把 `6866/9527` 暴露到公网。

---

## 7. 最后检查清单

- [x] 启动器无硬编码 `D:\OfferChaser`
- [x] 自带 JDK 21 / Node / Patchright
- [x] 空目录能自动创建 `db`、`data`、`target/logs`、`browser-data`
- [x] 无数据库时自动复制干净模板
- [x] 干净副本启动后 `health=UP`、`state=IDLE`
- [x] 默认免登录可进入管理台
- [ ] Windows ARM64 实机验证（未做）
- [ ] macOS/Linux 不支持，无需验证
- [x] 发布包个人数据风险已标注
