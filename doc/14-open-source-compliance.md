# 上游归属与许可证合规审查（get_jobs → OfferChaser）

审查对象：`OfferChaser 2.0.1` 发布包
上游项目：[`loks666/get_jobs`](https://github.com/loks666/get_jobs)（`main` 分支）
上游许可证：PolyForm Noncommercial License 1.0.0
审查方法：上游源码树 + 本包 `OfferChaser.jar` 内 class 反查（类路径、字段、方法、字符串常量逐一比对）

---

## 0. 结论

**定性：完整 fork，不是"部分参考"。**

上游 `src/main/java` 共 88 个顶层类，本项目的处置是二选一，没有第三种：

| 处置 | 数量 | 说明 |
|---|---|---|
| 原样保留（仅改包名 `com.getjobs` → `com.offerchaser`） | **49** | 除包名外路径、类名完全一致 |
| 删除 | **39** | 全部是 51job / 猎聘 / 智联 三套平台实现 + `Bot`/`Platform` + 入口类 |
| 保留但改名 | 0 | — |

也就是说：**上游所有非"其他招聘平台"的骨架都还在**。你自己的 118 个类建在这个骨架上，不是另起一套。这两句话不矛盾，一个说底子，一个说上面盖了什么。

许可证层面，PolyForm Noncommercial 1.0.0 **不是 copyleft**：允许修改、允许再分发，不强制衍生作品沿用同一许可证，但有两个硬约束：

1. 只能用于**非商业目的**；
2. 任何拿到软件"任一部分"副本的人，必须同时拿到**许可证条款或 URL** + **`Required Notice:` 开头的原文行**。

当前文档层做得基本到位；发布物层已由 `build-release.ps1` 负责把 `LICENSE` / `NOTICE` 注入 jar（见 §3.1）。

---

## 1. 属于 get_jobs 的部分

### 1.1 原样保留的 49 个类

| 包路径 | 保留 / 上游总数 | 类名 |
|---|---|---|
| `application/config` | 7 / 7 | AsyncConfig、BossResumeImageUploadConfig、CorsConfig、DataMapperConfig、StartupRunner、StaticResourceConfiguration、StaticServerConfiguration |
| `application/entity` | 8 / 8 | AiEntity、BlacklistEntity、BossConfigEntity、BossIndustryEntity、BossJobDataEntity、BossOptionEntity、ConfigEntity、CookieEntity |
| `application/mapper` | 8 / 8 | AiMapper、BlacklistMapper、BossConfigMapper、BossIndustryMapper、BossJobDataMapper、BossOptionMapper、ConfigMapper、CookieMapper |
| `application/service` | 5 / 5 | AiService、BossResumeImageService、BossService、ConfigService、CookieService |
| `application/controller` | 8 / 15 | AiConfigController、BossAnalyticsController、BossConfigController、BossController、ConfigController、CookieController、HealthController、PlaywrightController |
| `application/init` | 2 / 2 | CookieSeedInitializer、DefaultPageConfigInitializer |
| `worker/boss` | 4 / 4 | Boss、BossChatNavigator、BossConfig、Locators |
| `worker/utils` | 4 / 4 | Constant、Job、JobUtils、PlaywrightUtil |
| `worker/manager` | 1 / 1 | PlaywrightManager |
| `worker/service` | 1 / 1 | BossJobService |
| `worker/dto` | 1 / 1 | JobProgressMessage |

注意：内部类也大量同名保留，例如 `BossService` 下的 `Kpi` / `Charts` / `StatsResponse` / `SalaryRange` / `PagedResult`，`AiService` 下的 `ConnectionConfig` / `HttpExchange` / `TokenUsage`，`PlaywrightManager` 下的 `LifecycleState` / `LoginStatusChange`。

### 1.2 判定依据（抽样实证）

**① `worker/boss/Locators.java` —— 33 个常量逐一对应，连顺序都一致**

上游 33 个选择器常量（`LOGIN_BTN`、`HOME_SEARCH_INPUT`、`JOB_CARD_BOX`、`CHAT_BUTTON`、`IMAGE_UPLOAD`、`SCROLL_LOAD_MORE`、`CHAT_LIST_ITEM`、`ERROR_PAGE_LOGIN` …）在本包 class 中**名称、XPath/CSS 值、声明顺序全部一致**。这是"直接拷贝后改包名"的最强证据。

**② `worker/utils/Job.java` —— 字段完全一致**

`href, jobName, jobArea, jobInfo, salary, companyTag, recruiter, companyName, companyInfo`，含同名字段 `PRIME`、同款 Lombok `@Data`（`canEqual`/`equals`/`hashCode` 全套生成）。

**③ `application/entity/AiEntity.java` —— 5 个字段 100% 相同**

上游：`id, introduce, prompt, createdAt, updatedAt`；本包：完全相同，连 MyBatis-Plus 的 `@TableId(AUTO)`、`@TableField("created_at")`、`@TableField("updated_at")` 都一样。

**④ `application/entity/BossJobDataEntity.java` —— 上游 24 个字段 + 本项目新增 3 个**

上游 24 个字段（`encryptId, encryptUserId, companyName, jobName, salary, location, experience, degree, hrName, hrPosition, hrActiveStatus, deliveryStatus, jobDescription, jobUrl, recruitmentStatus, companyAddress, industry, introduce, financingStage, companyScale, createdAt, updatedAt` + `id`）全部保留；本项目追加 `firstSeenAt`、`lastDetailAt`、`snapshotHash`。这是教科书式的"衍生作品"：基类沿用、字段追加。

**⑤ `worker/boss/Boss.java` —— 65 个方法中 36 个同名保留（55%）**

保留的核心链路方法包括：
`execute`、`prepare`、`navigateTo`、`openJobList`、`openJobListWithRetry`、`searchFromHomePage`、`buildSearchUrl`、`postJobByCity`、`waitForJobList`、`waitForPageSettled`、`waitForSliderVerify`、`waitForImageUploadInput`、`countJobCards`、`getResultList`、`findVisibleChatButton`、`prepareImageUploadInput`、`findAttachedImageUploadInput`、`splitBossName`、`splitBossTitle`、`updateBlacklistFromChats`、`decodeSalary`、`isLoginRequired`、`isSecurityVerifyUrl`、`isStaleObjectError`、`safeText`/`safeAllText`/`safeUrl`/`safePageUrl`、`landedOn`、`pauseBetweenJobs`、`dumpListPageStructure`、`appendRawJson`、`resolveWaitTimeSeconds`、`needsUrlFilters`、`isValidString`、`warnIfSecurityCheck`。

上游存在、本项目已移除/改写的：`AiMessageDecision`、`buildDefaultPrompt`、`generateAiMessage`、`sendImageResume`、`attachJobDetailResponseListener`、`processJobDetailJsonAndInsert`、`parseSalaryRange` 等（AI 与薪资解析已拆分到 `application/ai`、`application/delivery/SalaryParser`，发送确认拆到 `worker/boss/ChatSendConfirmer`）。

**⑥ `worker/utils/Constant.java` —— 从 30+ 常量裁到 1 个**

本包只剩 `UNLIMITED_CODE`（对应"薪资无上限"判断）。`JobUtils.containsUnlimited()` 仍依赖它，所以类被保留、内容被大幅裁剪。

**⑦ `PlaywrightUtil.java` —— 只剩 `sleep`，但保留了上游的中文日志**

日志文本 `"Sleep被中断"` 原样出现在 class 常量池中。

### 1.3 已删除的 39 个类（功能裁剪，不是"没用上游"）

- `worker/job51/*`、`worker/liepin/*`、`worker/zhilian/*`
- `application/entity/{Job51*,Liepin*,Zhilian*}Entity`（8 个）
- `application/mapper/{Job51*,Liepin*,Zhilian*}Mapper`（9 个）
- `application/service/{Job51,Liepin,Zhilian}Service`
- `application/controller/{JobController,LiepinController,ZhilianController}`
- `worker/service/{JobPlatformService,Job51JobService,LiepinJobService,ZhilianJobService}`
- `worker/utils/{Bot,Platform}`
- `GetJobsApplication` → 改名 `OfferChaserApplication`

### 1.4 本项目新增的 118 个类（原创部分）

`application/delivery`（规则引擎、黑名单、薪资解析）、`application/ai`（严格 JSON、校验、模板）、`application/rules`、`application/persistence`（台账、额度预留、迁移）、`application/manual`（人工放行）、`application/metrics`、`application/insight`、`application/onboarding`（AI 建模板）、`application/templates`、`application/contacts`（跟进）、`application/status`（状态同步）、`application/security`（本机会话）……

以及 `worker/boss` 下拆分出来的：`BossRunContext`、`ChatSendConfirmer`、`BossChatStatusReader`、`BossJobDetailParser`、`RunCancelledException`。

### 1.5 前端

| 项目 | 归属 |
|---|---|
| 路由 `/boss`、`/boss/analysis`、`/ai-config` | 继承上游同名路由 |
| `front/scripts/copy-dist.mjs` | 上游同名脚本（README 仍在引用） |
| `dist/{file,globe,next,vercel,window}.svg` | 上游 `front/public/` 的 Next.js 默认资产，原样带到构建产物 |
| `front/app/favicon.ico` | 上游同名文件 |
| `/boss/{advanced,attempts,contacts,evaluations,flow,followup-strategy,followups,inbox,insights,ledger,reviews,rules,setup,tasks,templates}` | 本项目新增 |

**注意**：本发布包的 `front/` 没有随包分发源码（只有一个 favicon），因此前端**无法逐文件比对**。建议在源码仓库补一份比对记录，否则将来无法证明前端是重写而非照搬。

### 1.6 与 get_jobs 无关的第三方组件

`runtime/`（JDK 21，随包自带）、`node/node.exe`、`patchright-driver/`、`third-party/Node-LICENSE.txt`、`third-party/Patchright-ThirdPartyNotices.txt`。这些**不是** get_jobs 带来的，走各自的许可证（Node.js MIT、Patchright Apache-2.0、JDK GPLv2+CE）。

---

## 2. 当前做得对的地方

1. **`LICENSE` 与上游 byte 级一致**——`diff` 只差一个结尾空行，`Required Notice: Copyright (c) 2024-2026 GET JOBS (https://github.com/loks666/get_jobs)` 原样保留在第 4 行。这是最关键的一条，没被改坏。
2. **五处文档互相指向**：README / NOTICE / ATTRIBUTION.md / AUTHORS.md / CITATION.cff 都写了上游归属和 Required Notice，措辞是"非独立实现""衍生版本""大幅改写 fork"，定性正确。
3. **明确"上游未背书"**：`NOTICE` 里有 *No endorsement*，README 也有对应说明。
4. **清除干净**：jar 内 class 中 `com/getjobs`、`loks666`、`51job`、`liepin`、`zhilian` 残留 **0 处**；上游作者的私人黑名单、个人招呼语、AI 提示词默认值也已移除。

---

## 3. 必须修的缺口

> **当前状态**：3.2 到 3.6 已在 2026-11-09 这轮修订里改掉了（改动落在 `README.md`、`NOTICE`、`LICENSE`、`AUTHORS.md`、`ATTRIBUTION.md`）。**3.1 已由发布脚本处理**：`build-release.ps1` 会注入 `META-INF/LICENSE` 与 `META-INF/NOTICE`；直接执行源码 `bootJar` 仍不会自动注入，发布时必须走该脚本。下面保留原文，方便回溯当时怎么判断的。

### 3.1 【已由发布脚本修复】`OfferChaser.jar` 里没有 LICENSE / NOTICE

已核查：jar 的 `META-INF/` 下**没有任何许可证或 NOTICE 文件**。

PolyForm 的 Notices 条款要求"任何人从你这里获得软件**任一部分**的副本时，也必须获得条款或 URL，以及以 `Required Notice:` 开头的原文行"。

目前 `OfferChaser.jar` 单独流出（发群里、传网盘、别人只下载 jar）时，这两个东西一个都不在 → **不合规**。

修复（Gradle，任选其一）：

```kotlin
// build.gradle.kts
tasks.processResources {
    from("LICENSE", "NOTICE") { into("META-INF") }
}
```

或：

```kotlin
tasks.jar {
    from("LICENSE", "NOTICE") { into("META-INF") }
}
```

验证：`unzip -l OfferChaser.jar | grep META-INF` 应能看到 `META-INF/LICENSE`、`META-INF/NOTICE`。

### 3.2 【措辞】README 第 3 行"未署名衍生版本"

原文：

> OfferChaser 不是独立实现，而是 loks666/get_jobs 的**未署名衍生版本**经大幅改写而来。

"未署名"（unattributed）读起来是"这是个去掉了署名的改版"，正好是**不合规**的那种描述。应改成"非官方衍生版本（unofficial fork）"。

### 3.3 【LICENSE】缺少维护者自己的 Require Notice 行

PolyForm 允许 licensor 提供自己的 `Required Notice:` 行。现在只有 GET JOBS 一行，后果是：**下游只要删掉 `@Yhmx6` 的署名，依然完全合规**——因为许可证只要求传递 GET JOBS 那一行。

建议在 LICENSE 头部加第二行（年份按你首次发布年份调整）：

```text
Required Notice: Copyright (c) 2026 Yhmx6 (https://github.com/Yhmx6/OfferChaser)
```

### 3.4 【LICENSE】"历史版本 MIT"段落照搬上游，会产生误读

`LICENSE` 末尾那段：

> 本项目在 2025-11-11 之前以 MIT 协议发布……

说的是 **get_jobs** 的历史。放在 OfferChaser 的 LICENSE 里，读者会理解成"OfferChaser 曾经是 MIT"，属于事实性错误。应移到 `NOTICE` 并明确主语是上游。

### 3.5 【商业授权路径】不能只留 GET JOBS 的邮箱

现 `LICENSE` 写"商业授权仅由版权持有人 GET JOBS 授予"，对上游代码成立；但整个产品还包含 Yhmx6 的 118 个新类。而 PolyForm 的 *No Other Rights* 条款规定**不得转授权**，所以你也没法替上游发商业许可。

正确表述（放 `NOTICE`）：

- 上游 `get_jobs` 部分的商业授权：只能由 GET JOBS 授予（通过上游仓库联系）；
- 本项目修改与新增部分的商业授权：由维护者 Yhmx6 授予；
- **商业使用需同时取得两者**，缺一不可。

### 3.6 【可追溯性】缺"修改文件清单"和内部文档的谱系记录

- `NOTICE` 目前只有"改了什么"的概述，没有逐文件清单。建议加一张表（可直接引用 §1.1 / §1.3）。
- `AI_PROJECT_CONTEXT.md`、`AGENTS.md` 完全没提 get_jobs。这类内部文档正是"三年后忘了自己 fork 过谁"的源头，建议各加一行谱系声明。

---

## 4. README 怎么改的

开头的声明块已经换掉了。原来那句"未署名衍生版本"改成了第一人称的交代：先说清这是基于 get_jobs 做的、不是官方版，紧接着把自己的活摆出来（118/167 个类、`Boss` 扩到 10 个内部类型），最后放两行 Required Notice。

顺序是有意的。先讲清上游是谁，是为了免掉"假装独立"的嫌疑；紧接着讲自己做了什么，是为了不让整段读起来像在认领别人的东西。反过来写（先讲贡献再补一句"其实基于 get_jobs"）会显得在藏。

底部「开源、衍生与许可证」改成了类数对账表：

| 范围 | 数量 |
|---|---|
| 我新写的 | 118 / 167 个类 |
| 从上游沿用 | 49 / 88 个类（路径类名未动，只换包名） |
| 从上游删掉 | 39 个类（51job、猎聘、智联 + `Bot`、`Platform` + 原入口） |
| 前端 | 三个路由与 `copy-dist` 沿用，其余 15 个页面重写 |

顺手把「快速开始」里重复了一遍的 `Windows 10/11；` 删了。

---

## 5. LICENSE 怎么处理

### 5.1 原则

1. **官方条款正文一个字都不改**（PolyForm 1.0.0 英文全文）；
2. **`Required Notice:` 行原样保留**，且**增加维护者自己的一行**；
3. **协议正文之外的"附加说明"（商业授权联系方式、历史版本）移出 LICENSE，改放 NOTICE**——这些本来就不属于条款，放进去只会混淆授权主体。

### 5.2 最终结构

```text
LICENSE                     PolyForm 1.0.0 官方全文 + 两行 Required Notice（正文不增删）
NOTICE                      双方版权分割、改动清单、商业授权路径、上游无背书、上游 MIT 历史说明
AUTHORS.md                  作者与维护者（保持现状）
ATTRIBUTION.md              致谢（保持现状，可精简，避免与 NOTICE 重复吹成两份）
CITATION.cff                引用信息（保持现状）
third-party/                第三方组件许可证（Node / Patchright，保持现状）
```

### 5.3 LICENSE 头部

```text
PolyForm Noncommercial License 1.0.0
====================================

Required Notice: Copyright (c) 2024-2026 GET JOBS (https://github.com/loks666/get_jobs)
Required Notice: Copyright (c) 2026 Yhmx6 (https://github.com/Yhmx6/OfferChaser)

--------------------------------------------------------------------------------

# PolyForm Noncommercial License 1.0.0

<https://polyformproject.org/licenses/noncommercial/1.0.0>

## Acceptance
……（以下官方全文，一字不改，直到 Definitions 结束）
```

**这一轮实际只做了第 2 条**：在 `LICENSE` 第 4 行下面补了一行维护者的 `Required Notice`，正文一个字没动，和上游仍然保持 byte 级一致（差这一行）。

第 1、3 条没动，是有意的。删掉"简而言之"和末尾那两节附加说明确实更干净，但 LICENSE 是别人会拿去逐字比对的文件，改动面越大越容易被误读成"改过协议"。等真要做商业授权谈判时，让律师连 NOTICE 一起过一遍再动，比现在自己搬更稳妥。

> 判断依据：PolyForm 要求传递的是"条款或 URL"和"以 `Required Notice:` 开头的原文行"，这两样现在都在。附加说明那两节本来就被明确标注为"不属于协议条款"，去留都不影响合规，所以不急着动。

### 5.4 NOTICE 补了什么

`Copyright`、`Commercial licensing`、`Upstream license history`、`Distributing a copy` 四节都写进去了，另加一节 `Authorship` 讲贡献划分和 `Boss` 的改动量。原文就是 `NOTICE` 本身，这一页不再抄一遍，省得两边说法慢慢漂移。

---

## 6. 落地清单

- [x] `[硬]` jar 打包把 `LICENSE` / `NOTICE` 放进 `META-INF/`，并验证（`build-release.ps1`）
- [x] `[硬]` README 开头声明块重写（去掉"未署名"，改第一人称交代贡献）
- [x] `[硬]` LICENSE 头部加维护者 Required Notice 行（只加这一行，正文未动）
- [x] `[中]` NOTICE 补 Copyright 分割、商业授权路径、上游 MIT 历史、分发要求
- [x] `[中]` README 增加"开源、衍生与许可证"清单（含类数对账）
- [x] `[中]` `AUTHORS.md`、`ATTRIBUTION.md`、`项目说明.md` 同步贡献划分
- [x] `[低]` `AI_PROJECT_CONTEXT.md`、`AGENTS.md` 各加一行血缘声明
- [x] `[低]` `doc/README.md` 索引收录本页，并加维护规则
- [x] `[低]` 发布脚本加校验：发布目录必须同时存在 `LICENSE`、`NOTICE`；jar 内必须存在 `META-INF/LICENSE`
- [ ] `[低]` 源码仓库加 `CONTRIBUTING.md`：说明贡献即同意以 PolyForm NC 1.0.0 授权

> LICENSE 尾部"商业授权 / 历史版本"两节这轮没搬。那两节本来就不属于协议条款，搬与不搬都不影响合规，搬的唯一好处是让 LICENSE 只剩条款本身。等你有空再动，或者交给律师过一遍更稳。

发布前自检命令：

```bash
unzip -l OfferChaser.jar | grep -E "META-INF/(LICENSE|NOTICE)"
grep -c "Required Notice:" LICENSE NOTICE README.md
```

---

## 7. 贡献划分怎么说（措辞规范）

### 7.1 一条规矩

**义务跟着整个作品走，不跟着代码行数走。**

118 个类是你写的，这些新增部分的著作权就该主张，不主张是白干。但主张它，跟"整体因此不再是衍生作品"是两件事，混在一起讲就会出问题。所以每段话都要同时办到两件事：

- 说清自己做了什么。不说，等于把权利让掉。
- 说清这仍建立在谁的基础之上。不说，就是虚假陈述，比老实标注更危险。

顺序也有讲究：先交代上游，再讲自己的活。反过来说（先讲贡献，末尾补一句"其实基于 get_jobs"）读起来像在藏。

### 7.2 已经用上的三种表述

**① README 顶部**（实际文字见 `README.md` 第 3–11 行）：先说这是基于 get_jobs 做的、不是官方版，紧接着给数字（167 个类里 118 个新增、`Boss` 扩到 10 个内部类型），最后放两行 Required Notice。

**② NOTICE 的 `## Authorship`**：英文版，同样先写维护者的部分，再写从上游继承了什么。

**③ 对外解释时只用数字**：`88 个上游类 → 保留 49 / 删除 39`、`新增 118 / 总计 167`、`Boss 内部类型 1 → 10`。形容词经不起核，数字可以。

### 7.3 不能写的说法

| 别写 | 为什么 |
|---|---|
| **从零构建 / from scratch / 从零实现** | 这等同于声称 clean-room。一旦有人把两边的类结构、方法划分、字段命名摆在一起，就变成虚假陈述。而且事实是：新代码是**长在上游文件里**的——`Boss` 里 10 个内部类型、`BossJobService` 里的回调挂载，都不是"零"。 |
| **独立实现 / independent implementation** | 与 NOTICE 自相矛盾，且整体仍是衍生作品。 |
| **大部分功能是我做的** | 不可核验，读起来像辩解。改成数字。 |
| **用 AI 生成的 / AI 从零构建** | 两个问题：① 法律文件里主动削弱自己对新增部分的著作权主张（AI 辅助不构成独立创作抗辩）；② AI 是在**你已有仓库的上下文里**写的，法律上不构成 clean-room，写了反而给人把柄。技术文档可以说明用了 AI 辅助，**许可/版权文件里不写**。 |
| **已移除全部上游代码** | 49 个类还在，一查即穿。要写就写清"保留哪些、删除哪些"。 |

### 7.4 定性的判据（自己心里要有数）

是不是衍生，看的是**表达是否实质相似**，不是看你删了多少文件：

- `Locators` 33 个常量名称与取值逐一对应 → 高度相似
- `AiEntity` 5 个字段与注解完全一致 → 高度相似
- 包结构 `application/{config,entity,mapper,service,controller}` + `worker/*` 分层一致 → 结构相似
- `Boss` 65 个上游方法中 36 个同名保留 → 局部相似

要真正拉开距离，**改结构比删文件有效一个量级**：重组分层、改类名与方法划分、重设数据模型命名。

### 7.5 已发布版本不受后续改动影响

2.0.1 的 jar 已经在别人机器上。那些副本永远是衍生作品，NOTICE 义务跟着副本走。**后续版本删多少代码，都不能追溯洗白历史版本。**

---

## 8. 边界说明

本文是对照上游源码与许可证条款做的技术性合规审查，不构成法律意见。涉及实际商业发行、商标使用或纠纷时，请咨询执业律师。
