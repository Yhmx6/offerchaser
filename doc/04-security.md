# 04 · 安全

## 1. 威胁模型

本系统面向**单机单用户**：默认假设攻击者无法登录本机、无法在本机执行进程；主要风险是
**误发消息**、**跨站请求伪造**、**敏感信息泄露**、**预算超发**、**状态误判**。
因此安全策略以“本机回环 + 本机会话 + fail-closed”为核心，而不是云端多租户权限模型。

## 2. 网络边界

- 后端 `server.address=127.0.0.1`，只监听回环；静态服务 `offerchaser.static-server.address=127.0.0.1`。
- CORS 只允许 `offerchaser.admin.cors-allowed-origins` 中的白名单（默认 9527/6866 的 localhost/127.0.0.1）；
  不支持 `*`。
- Host 白名单 `LocalHostFilter`：只接受 `127.0.0.1`、`localhost`、`::1`，拒绝 DNS rebinding 和局域网 Host。
- 平台门 `PlatformGateFilter`：未启用平台（当前仅 `boss`）的 API 前缀直接 403，避免误调用。
- 管理与调试接口默认只在本机可访问；不要修改绑定地址为 `0.0.0.0`。

## 3. 本机会话与 CSRF

- 首次启动生成 `data/local-session-token`（URL-safe Base64，权限受限，不写日志、不提交）。
- 登录：`POST /api/session/login` 提交本机令牌；服务端用 SHA-256 **常量时间比较**校验；
  连续失败 5 次/60 秒窗口内触发节流。
- 会话：Cookie `OFFERCHASER_SESSION`（HttpOnly、SameSite=Strict、本机路径），默认有效期 168 小时；
  也支持 `X-Session-Token` 头给本机 API/SSE 客户端。
- CSRF：所有写方法必须携带 `X-Requested-With: offerchaser`；缺失返回 403 `CSRF_HEADER_MISSING`。
- 只读放行：GET/HEAD/OPTIONS 默认放行以便本地页面与 SSE；但 `/api/cookie*`、`/api/boss/reload`、
  `/api/playwright/**` 属于“会读敏感数据/触发动作”的 GET，必须认证。
- `offerchaser.security.auth-enabled=false` 只允许在明确知情时临时使用；关闭后写操作不再需要会话。

## 4. 发送链路 fail-closed

发送前必须全部通过；任一不确定都**不发送**并按 UNKNOWN/FAILED 记账，不允许“猜成功”：

1. **配置与规则**：任务启动前校验配置、迁移就绪、生效模板校正；规则版本固定。
2. **AI 严格输出**：模型返回必须是合法 JSON 且 `MATCH`，话术合法（长度/非 JSON 片段/非原文）；
   超时/异常/解析失败一律不发送。
3. **联系人状态**：发送前复检联系人是否已回复/拒绝/接管；命中则不发送。
4. **预算**：先原子预留额度，成功才允许发送；失败/未知按结算状态机处理，重启不清零。
5. **会话身份核对**：打开会话后核对 URL/标题/HR 证据；证据冲突或无法确认 → 不发送。
6. **文字确认**：点击发送后 15 秒内必须观察到本人消息新增且文本匹配；超时记 UNKNOWN，不记成功。
7. **图片确认**：图片发送后 45 秒内确认；失败/未知独立记录，不把文字成功当作图片成功。
8. **人工验证**：出现平台安全验证时暂停任务；恢复必须人工操作，等待有上限、不忙循环。
9. **停止与终态**：停止请求发出后，发送前节点再次检查；已发出的不可撤回；停止超时不伪造“已停止”。

## 5. 身份核对

- 证据优先级：URL 命中目标会话 id → 标题命中 HR 姓名 → 公司+岗位（仅在无 HR 信息时）→ 其他为 UNKNOWN。
- 标题存在但公司/岗位/HR 都对不上 → MISMATCH；同公司不同 HR 不得判 VERIFIED。
- 身份核对不通过或证据不足：关闭会话，按未发送处理（预算释放/回收由结算链路负责）。
- 核对结果写入投递事件（`CHAT_IDENTITY_CHECK`），可在明细详情查看。

## 6. 预算完整性

- 每日首次沟通额度全局共享，不进入方向模板；按 `budget-timezone` 的自然日计算。
- 预留：单事务插入 `budget_reservation(RESERVED)` 并条件更新 `daily_budget`，超过上限直接拒绝。
- 结算：`RESERVED → CONFIRMED/UNKNOWN/RELEASED` 只允许一次状态转移；重复结算幂等，不影响其他预留。
- 崩溃恢复：启动时 `reconcileStaleReservations` 将残留 RESERVED/PENDING 转 UNKNOWN 并记事件；
  禁止用重启绕过预算。
- 人工核对：UNKNOWN 结果可人工改判 SENT/NOT_SENT，但按预算语义调整聚合；`CONFIRMED` 不可回退为未发送。

## 7. 人工放行边界

- 只有规则/AI 结果为 REVIEW 的岗位进入放行队列；每个评估最多一条放行记录。
- 批准需要理由；批准**不代表立即发送**，只代表下一轮可被消费。
- 消费时重新执行 AI 严格评估；仍必须 MATCH，再走身份/预算/文字+图片确认；不通过记 FAILED，不绕过任何一步。
- 拒绝/已消费/失败状态可查询；不允许通过放行接口绕过规则引擎直接发送。

## 8. 敏感数据

| 数据 | 位置 | 保护 |
|---|---|---|
| 本机会话令牌 | `data/local-session-token` | 不提交、不写日志；文件系统权限限制 |
| BOSS 登录 Cookie | `cookie` 表 + `browser-data/` profile | 读取接口掩码；提交掩码值不覆盖真实值 |
| AI API Key / 模型配置 | `config`/应用配置 | 读取掩码；日志不打印原始值；掩码回传按“保持”处理 |
| 简历文件 | `data/boss/resume/`、`data/boss/templates/*` | 仅本机；不上传第三方（AI 只发送文本评估所需信息） |
| 数据库 | `db/offerchaser.db` + 备份 | 含投递与联系人；备份目录不提交 |
| 日志 | `target/logs/*` | 默认不打印 token/Key/Cookie 原值 |

`.env`、`data/`、`db/`、`browser-data/`、`target/` 已在版本控制中忽略；不要把本机运行目录发给他人。

## 9. 运行与变更安全

- 真实投递必须手动开始；系统不提供定时/无人值守自动投递。
- 停止服务优先用托盘“停止服务”（先停止任务再退出），避免残留 PENDING 状态。
- 修改数据库结构只能通过新增迁移版本；迁移前自动备份，迁移失败阻止任务启动。
- 升级前先备份 `db/offerchaser.db`；恢复时停止应用后替换。
- 对外分享日志/数据库前先脱敏（账号、公司、HR、Cookie、Key）。
