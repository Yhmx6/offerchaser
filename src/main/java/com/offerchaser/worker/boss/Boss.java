package com.offerchaser.worker.boss;

import com.offerchaser.application.ai.AiMatchResult;
import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.ai.AiMessageValidator;
import com.offerchaser.application.ai.AiOutcome;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.delivery.JobRuleEngine;
import com.offerchaser.application.delivery.JobSnapshot;
import com.offerchaser.application.entity.BossJobDataEntity;
import com.offerchaser.application.manual.ManualReleaseService;
import com.offerchaser.application.delivery.RuleDecision;
import com.offerchaser.application.delivery.RuleOutcome;
import com.offerchaser.application.delivery.RuleReason;
import com.offerchaser.application.delivery.RuleSet;
import com.offerchaser.application.delivery.SalaryStrategy;
import com.offerchaser.application.entity.AiEntity;
import com.offerchaser.application.entity.BossJobDataEntity;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryKind;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationEntry;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.persistence.FollowupPlanRepository;
import com.offerchaser.application.persistence.ImageSendStatus;
import com.offerchaser.application.persistence.TextSendStatus;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.utils.Constant;
import com.offerchaser.worker.utils.Job;
import com.offerchaser.worker.utils.PlaywrightUtil;
import com.offerchaser.worker.dto.PipelineEvent;
import com.offerchaser.worker.dto.PipelineStage;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.offerchaser.worker.boss.Locators.*;

/**
 * <p>
 * Boss 直聘自动投递。
 * <p>
 * 阶段 A 优化后的执行顺序（严格"先评估、再沟通"）：
 * <pre>
 * 点击卡片 → 详情响应 → 解析 JobSnapshot → 卡片身份核对 → 入库/刷新快照
 *   → 硬规则（命中拒绝/复核立即跳过，不打开详情页、不点沟通）
 *   → 去重/跟进判断
 *   → AI 评估（仅首次沟通；输出不合法/失败一律跳过，绝不发原文或兜底话术）
 *   → 每日预算原子预留
 *   → 打开详情页/聊天页 → 会话身份核对 → 发送文字并确认 → 独立确认图片
 *   → 事件与状态落库（文字/图片分开记，不确定不记成功）
 * </pre>
 * 取消、熔断、人工验证暂停统一由 {@link BossRunContext} 承载；本类不再持有可变的停止标志。
 */
@Component
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class Boss {

    // ---- 构造器注入依赖（声明顺序即测试构造 Boss 的参数顺序，勿调整） ----
    private final BossService bossService;
    private final AiService aiService;
    private final AiMatchService aiMatchService;
    private final BossResumeImageService bossResumeImageService;
    private final BudgetService budgetService;
    private final DeliveryLedgerService deliveryLedgerService;
    private final EvaluationRepository evaluationRepository;
    /** B3：联系人状态（回复/拒绝/人工接管/停止联系、跨岗位去重） */
    private final ContactStateService contactStateService;
    /** B3：跟进待办（建议/批准/执行标记、补发图片计划） */
    private final FollowupService followupService;

    // ---- 运行期由 BOSS 任务线程注入 ----
    @Setter
    private Page page;
    @Setter
    private BossConfig config;
    /**
     * D3-B：人工放行队列（批准后下一轮消费；执行仍走身份/预算/发送确认）。
     * 使用可选字段注入，保持 Boss 既有构造器兼容测试与手工装配。
     */
    @Autowired(required = false)
    private ManualReleaseService manualReleaseService;


    private Set<String> blackCompanies;
    private Set<String> blackRecruiters;
    private Set<String> blackJobs;
    /** 岗位硬规则引擎，prepare() 时按当前配置与黑名单构造 */
    private JobRuleEngine ruleEngine;
    /** B2：本轮任务固定的规则快照（由 BossJobService 从 RuleConfigService 注入） */
    @Setter
    private RuleSet ruleSet;
    /** 聊天页发送确认/身份核对（DOM 探测） */
    private final ChatSendConfirmer chatConfirmer = new ChatSendConfirmer();

    @Setter
    private ProgressCallback progressCallback;
    /** 本轮任务上下文：取消、暂停、计数都在这里 */
    @Setter
    private BossRunContext runContext;

    // ==================== 投递熔断 ====================
    // BOSS 每日打招呼有上限，程序不应把额度打完才发现。这里保留两道闸：
    // 1) 数据库持久化的每日预算（BudgetService，重启不清零）；
    // 2) 连续打不开聊天窗口，判定为限流立即停止。
    // dailyLimit 现在作为「预算上限」的兜底配置，实际额度以 BudgetService 为准。

    /** 成功投递上限，0 或负数表示不限制（BudgetService 也读同一配置） */
    @Value("${offerchaser.boss.daily-limit:145}")
    private int dailyLimit = 145;

    /** 连续多少个岗位打不开聊天窗即判定被限流，0 表示不限制 */
    @Value("${offerchaser.boss.consecutive-fail-limit:8}")
    private int consecutiveFailLimit = 8;

    /** 期望薪资策略：INTERSECT=有交集即可，FLOOR=岗位下限达到期望 */
    @Value("${offerchaser.boss.salary-strategy:INTERSECT}")
    private String salaryStrategyName = "INTERSECT";

    /** 发送话术最大长度，超过视为 AI/配置输出异常，不发送 */
    @Value("${offerchaser.boss.max-message-length:120}")
    private int maxMessageLength = 120;

    /** 文字发送确认等待秒数 */
    @Value("${offerchaser.boss.text-send-confirm-timeout-seconds:15}")
    private int textSendConfirmTimeoutSeconds = 15;

    /** 图片发送确认等待秒数 */
    @Value("${offerchaser.boss.image-send-confirm-timeout-seconds:45}")
    private int imageSendConfirmTimeoutSeconds = 45;

    /** 人工验证最长等待秒数 */
    @Value("${offerchaser.boss.verification-timeout-seconds:300}")
    private int verificationTimeoutSeconds = 300;

    /** 跟进最小间隔小时数 */
    @Value("${offerchaser.boss.followup-min-interval-hours:72}")
    private int followupMinIntervalHours = 72;

    /** 同一岗位最多跟进次数 */
    @Value("${offerchaser.boss.followup-max-count:1}")
    private int followupMaxCount = 1;

    /** B3：同一联系人（可信 HR ID）在多少个自然日内跨岗位不重复问候；<=0 关闭 */
    @Value("${offerchaser.boss.contact-dedupe-days:30}")
    private int contactDedupeDays = 30;

    /** B3：跟进默认需要管理台批准（批准只允许下一轮执行，不会立即发送） */
    @Value("${offerchaser.boss.followup-require-approval:true}")
    private boolean followupRequireApproval = true;

    // ===== B4：有限增量采集 =====
    /** 每个关键词最多处理多少岗位（<=0 不限制） */
    @Value("${offerchaser.boss.collect-max-per-keyword:60}")
    private int collectMaxPerKeyword = 60;
    /** 单关键词采集阶段最长时长（分钟，<=0 不限制） */
    @Value("${offerchaser.boss.collect-max-duration-minutes:15}")
    private int collectMaxDurationMinutes = 15;
    /** 连续多久没有新增卡片才判定加载结束（秒） */
    @Value("${offerchaser.boss.collect-no-new-window-seconds:30}")
    private int collectNoNewWindowSeconds = 30;

    /** D1：模板采集策略优先于全局默认值（config 为 null 或字段为 null 时回退）。 */
    private int effectiveCollectMaxPerKeyword() {
        Integer value = config == null ? null : config.getCollectMaxPerKeyword();
        return value == null ? collectMaxPerKeyword : value;
    }

    private int effectiveCollectMaxDurationMinutes() {
        Integer value = config == null ? null : config.getCollectMaxDurationMinutes();
        return value == null ? collectMaxDurationMinutes : value;
    }

    private int effectiveCollectNoNewWindowSeconds() {
        Integer value = config == null ? null : config.getCollectNoNewWindowSeconds();
        // 0/负数表示回退全局默认；不支持“无新增窗口 0 秒”这种危险配置
        return value == null || value <= 0 ? collectNoNewWindowSeconds : value;
    }
    /** 本轮已处理过的 encryptId：虚拟列表重排/多关键词时不重复评估 */
    private final java.util.Set<String> processedEncryptIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 熔断触发标记 */
    private volatile boolean autoStopped = false;
    /** 熔断原因，用于日志和前端提示 */
    private volatile String autoStopReason = "";
    /** 连续打不开聊天窗的次数 */
    private int consecutiveOpenFailures = 0;

    /** Boss 首页，UI 搜索的入口 */
    private static final String BOSS_HOME_URL = "https://www.zhipin.com";
    /** BOSS 岗位列表页：城市/薪资/经验等筛选条件以查询参数形式挂在该地址上 */
    private static final String JOB_LIST_URL = "https://www.zhipin.com/web/geek/jobs";
    /** B3：联系人/跟进时间文本格式（与 SQLite 中 created_at/started_at 保持一致） */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * true=先回首页、在搜索框里做一次真实 UI 搜索再进列表页；false=直接拼 URL 跳转。
     * <p>默认 false：首页在受控标签页里会一直转圈，navigate 可能 60 秒才返回；
     * 期间 evaluate / locator 都没有默认超时，会无限期阻塞（启动慢、投递卡死、管理页按钮
     * 没反应都出在这）。想重新试 UI 搜索路径时再打开。
     */
    private static final boolean USE_UI_SEARCH = false;
    /** 等待页面加载状态的超时（毫秒），绝不能不设 —— 见 waitForPageSettled 的说明 */
    private static final double LOAD_STATE_TIMEOUT = 10_000;

    /** 会话列表页：黑名单回捞入口 */
    private static final String CHAT_LIST_URL = "https://www.zhipin.com/web/geek/chat";
    /** 黑名单回捞的滚动轮次上限，防御页面缺失“没有更多了”标记时无限滚动 */
    private static final int CHAT_BLACKLIST_MAX_ROUNDS = 200;
    /** 单条会话文本读取失败的重试次数 */
    private static final int CHAT_ROW_READ_RETRIES = 2;

    /** 单关键词滚动采集的硬轮次上限，防御页面异常导致死循环 */
    private static final int CARD_SCROLL_MAX_ROUNDS = 300;
    /** 滚动期间可容忍的“失效对象”异常次数 */
    private static final int CARD_SCROLL_STALE_TOLERANCE = 20;

    /** 岗位列表容器候选：推荐页与搜索结果页类名不同，组合成一个等待条件，避免逐个串行等待。 */
    private static final List<String> JOB_LIST_CONTAINER_CANDIDATES = List.of(
            "ul.rec-job-list",
            "li.job-card-box",
            "ul.job-list-box",
            "li.job-card-wrapper",
            ".job-list-box",
            ".search-job-result");
    private static final String JOB_LIST_CONTAINERS = String.join(", ", JOB_LIST_CONTAINER_CANDIDATES);

    /** 图片上传 input 的候选选择器：文件输入框通常隐藏在“发送图片”按钮内部 */
    private static final List<String> IMAGE_UPLOAD_INPUT_SELECTORS = List.of(
            "div.btn-sendimg[aria-label='发送图片'] input[type='file']",
            "div[aria-label='发送图片'].btn-sendimg input[type='file']",
            "[aria-label='发送图片'] input[type='file']",
            "input[type='file'][accept*='image/gif'][accept*='image/jpeg']");

    private final List<Job> resultList = new ArrayList<>();

    /** 进度回调：message + 当前/总数（total<=0 表示总数未知）。 */
    @FunctionalInterface
    public interface ProgressCallback {
        /** 接收一次进度更新。 */
        void accept(String message, Integer current, Integer total);
    }

    /** 投递流程可视化回调（Boss 阶段 → 管理台 SSE）。 */
    @FunctionalInterface
    public interface PipelineCallback {
        /** 接收一个投递流程事件，由管理台/SSE 消费。 */
        void accept(PipelineEvent event);
    }

    @Setter
    private PipelineCallback pipelineCallback;

    /** 记录一个投递流程阶段事件；失败不影响投递主流程。 */
    private void pipeline(PipelineStage stage, JobSnapshot snapshot, String outcome, String detail) {
        try {
            PipelineEvent event = PipelineEvent.of(stage,
                    snapshot == null ? null : snapshot.jobName(),
                    snapshot == null ? null : snapshot.companyName(),
                    outcome, detail);
            if (runContext != null) {
                runContext.recordPipeline(event);
            }
            if (pipelineCallback != null) {
                pipelineCallback.accept(event);
            }
        } catch (Throwable e) {
            log.debug("记录投递流程事件失败：{}", e.getMessage());
        }
    }

    private void pipeline(PipelineStage stage, String jobName, String companyName, String outcome, String detail) {
        try {
            PipelineEvent event = PipelineEvent.of(stage, jobName, companyName, outcome, detail);
            if (runContext != null) {
                runContext.recordPipeline(event);
            }
            if (pipelineCallback != null) {
                pipelineCallback.accept(event);
            }
        } catch (Throwable e) {
            log.debug("记录投递流程事件失败：{}", e.getMessage());
        }
    }

    // ==================== 生命周期 ====================

    public void prepare() {
        if (runContext == null) {
            throw new IllegalStateException("Boss 任务缺少运行上下文（BossRunContext），禁止启动");
        }
        // 安全的列补全（不会再重建/删除 boss_data）
        try {
            bossService.ensureBossDataColumnOrder();
        } catch (Throwable ignore) {
        }
        // 从数据库加载黑名单
        this.blackCompanies = bossService.getBlackCompanies();
        this.blackRecruiters = bossService.getBlackRecruiters();
        this.blackJobs = bossService.getBlackJobs();
        // B4：新一轮任务重置"本轮已处理岗位"集合（虚拟列表重排/多关键词去重用）
        this.processedEncryptIds.clear();

        SalaryStrategy strategy;
        try {
            strategy = SalaryStrategy.valueOf(
                    salaryStrategyName == null ? "INTERSECT" : salaryStrategyName.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("未知的薪资策略配置「{}」，回退为 INTERSECT", salaryStrategyName);
            strategy = SalaryStrategy.INTERSECT;
        }
        if (ruleSet != null) {
            this.ruleEngine = new JobRuleEngine(ruleSet);
            log.info("使用规则版本 v{} | hash={}", ruleSet.configVersion(), ruleSet.sourceHash());
        } else {
            // 兼容旧路径（单测/未注入规则快照）：用当前配置构造默认规则
            this.ruleEngine = new JobRuleEngine(config, blackCompanies, blackRecruiters, blackJobs, strategy);
        }

        log.info("投递前检查加载完成 | 黑名单：公司({}) 招聘者({}) 职位({}) | 薪资策略={} | 话术上限={}字 | 规则版本={}",
                sizeOf(blackCompanies), sizeOf(blackRecruiters), sizeOf(blackJobs), strategy, maxMessageLength,
                ruleSet == null ? "default(未注入)" : ("v" + ruleSet.configVersion() + "(hash " + ruleSet.sourceHash() + ")"));
    }

    private static int sizeOf(Set<String> set) {
        return set == null ? 0 : set.size();
    }

    /**
     * 执行投递。
     *
     * @return 本轮确认发送成功的首次沟通数量（不包含跟进，也不包含结果未知）
     */
    public int execute() {
        if (runContext == null) {
            throw new IllegalStateException("Boss 任务缺少运行上下文（BossRunContext），禁止启动");
        }
        autoStopped = false;
        autoStopReason = "";
        consecutiveOpenFailures = 0;
        log.info("投递配置 | 每日成功上限={}（0=不限） | 连续失败阈值={}",
                dailyLimit, consecutiveFailLimit);

        // D3-B：先消费人工放行队列，再进入正常搜索投递。
        try {
            processManualReleases();
        } catch (RunCancelledException cancelled) {
            throw cancelled;
        } catch (Exception releaseError) {
            log.warn("人工放行队列处理失败（正常投递继续）：{}", releaseError.getMessage());
        }

        for (String cityCode : config.getCityCode()) {
            if (isAborted()) {
                progressCallback.accept(abortMessage(), 0, 0);
                break;
            }
            try {
                postJobByCity(cityCode);
            } catch (RunCancelledException cancelled) {
                throw cancelled;
            } catch (Exception e) {
                log.error("城市[{}]处理失败，跳过继续下一个: {}", cityCode, e.getMessage(), e);
                if (progressCallback != null) {
                    progressCallback.accept("城市处理失败已跳过：" + e.getMessage(), 0, 0);
                }
            }
            if (isAborted()) {
                progressCallback.accept(abortMessage(), 0, 0);
                break;
            }
        }

        if (autoStopped) {
            log.warn("本轮投递已熔断停止：{} | 确认发送 {} 个，跟进 {} 个",
                    autoStopReason, runContext.getDeliveredCount(), runContext.getFollowupCount());
        } else if (runContext.isStopRequested()) {
            log.info("本轮投递被停止：{} | 确认发送 {} 个", runContext.getStopReason(), runContext.getDeliveredCount());
        } else {
            log.info("本轮投递结束 | 确认发送 {} 个，跟进 {} 个，规则拒绝 {} 个，跳过 {} 个，结果未知 {} 个",
                    runContext.getDeliveredCount(), runContext.getFollowupCount(),
                    runContext.getRuleRejectedCount(), runContext.getSkippedCount(), runContext.getUnknownCount());
        }
        return runContext.getDeliveredCount();
    }

    /** 本轮确认发送岗位的快照副本（调用方修改不影响内部列表）。 */
    public List<Job> getResultList() {
        List<Job> snapshot = new ArrayList<>(resultList);
        return snapshot;
    }

    // ==================== D3-B 人工放行队列 ====================

    /**
     * 消费已批准的人工放行记录：
     * 规则/AI REVIEW 已被人工放行，但发送前仍重新评估 AI、校验话术，
     * 并复用身份核对/预算/文字图片确认，绝不直接盲发。
     */
    private void processManualReleases() {
        if (manualReleaseService == null) {
            return;
        }
        // P0 修复（调试模式误发）：execute() 在进入普通卡片循环前会先消费人工放行队列，
        // 而调试模式的短路由历史上只存在于普通卡片路径（handleJobCard），导致 debugger=1
        // 且队列里有 APPROVED 记录时仍会真实发送文字+图片。人工放行是真实发送入口，这里
        // 必须在队列入口短路：调试模式=只评估，不消费任何放行记录。
        if (Boolean.TRUE.equals(config.getDebugger())) {
            log.info("调试模式：跳过人工放行队列（只评估不投递，不消费放行记录）");
            if (progressCallback != null) {
                progressCallback.accept("调试模式：跳过人工放行队列（不投递）", 0, 0);
            }
            return;
        }
        List<com.offerchaser.application.persistence.ManualReleaseRepository.ManualRelease> releases =
                manualReleaseService.approvedForExecution();
        if (releases == null || releases.isEmpty()) {
            return;
        }
        progressCallback.accept("人工放行队列：" + releases.size() + " 条待执行", 0, 0);
        for (com.offerchaser.application.persistence.ManualReleaseRepository.ManualRelease release : releases) {
            if (isAborted()) {
                progressCallback.accept(abortMessage(), 0, 0);
                return;
            }
            try {
                processManualRelease(release);
            } catch (RunCancelledException cancelled) {
                throw cancelled;
            } catch (Exception e) {
                log.warn("人工放行执行异常 | releaseId={} | {}", release.releaseId(), e.getMessage());
                manualReleaseService.markFailed(release.releaseId(), "执行异常：" + e.getMessage());
            }
        }
    }

    private void processManualRelease(
            com.offerchaser.application.persistence.ManualReleaseRepository.ManualRelease release) {
        ManualReleaseService.EvaluationData evaluation = manualReleaseService.findEvaluation(release.evaluationId());
        if (evaluation == null) {
            manualReleaseService.markFailed(release.releaseId(), "评估记录不存在");
            return;
        }
        BossJobDataEntity entity = bossService.findBossJobByEncryptId(evaluation.encryptId());
        if (entity == null) {
            manualReleaseService.markFailed(release.releaseId(), "岗位快照不存在，无法放行");
            return;
        }
        JobSnapshot snapshot = snapshotFromEntity(entity, evaluation);
        if (!snapshot.hasIdentity()) {
            manualReleaseService.markFailed(release.releaseId(), "岗位缺少 encryptId，无法确认身份");
            return;
        }
        String contactKey = ContactStateService.contactKey(snapshot.encryptUserId());
        if (snapshot.hasIdentity() && deliveryLedgerService.hasConfirmedFirstContact(snapshot.encryptId())) {
            manualReleaseService.markFailed(release.releaseId(), "该岗位已经确认沟通过，不重复放行");
            return;
        }
        if (contactKey != null && contactStateService.isAutoContactBlocked(contactKey)) {
            manualReleaseService.markFailed(release.releaseId(), "联系人已回复/拒绝/接管/停止联系，禁止放行");
            return;
        }
        if (contactKey != null && contactDedupeDays > 0
                && contactStateService.hasOtherJobConfirmedFirstContact(
                contactKey, snapshot.encryptId(), contactDedupeDays)) {
            manualReleaseService.markFailed(release.releaseId(), "同一 HR 近期已在其他岗位沟通过，避免重复打扰");
            return;
        }

        String message;
        String keyword = evaluation.sourceKeyword() == null || evaluation.sourceKeyword().isBlank()
                ? "人工放行" : evaluation.sourceKeyword();
        if (Boolean.TRUE.equals(config.getEnableAI())) {
            AiMatchResult aiResult;
            try {
                AiEntity aiConfig = aiService.getAiConfig();
                aiResult = aiMatchService.evaluate(aiConfig, snapshot.jobName(), snapshot.jobDescription(),
                        keyword, config.getSayHi(), maxMessageLength);
            } catch (Exception aiError) {
                manualReleaseService.markFailed(release.releaseId(), "重新生成话术异常：" + aiError.getMessage());
                runContext.recordSkipped();
                return;
            }
            if (aiResult.outcome() != AiOutcome.MATCH) {
                manualReleaseService.markFailed(release.releaseId(),
                        "放行后 AI 重新评估未通过：" + (aiResult.errorCode() != null
                                ? aiResult.errorCode() : aiResult.analysis()));
                runContext.recordSkipped();
                pipeline(PipelineStage.AI, snapshot, "REVIEW", "人工放行后 AI 重新评估未通过");
                return;
            }
            message = aiResult.message();
            pipeline(PipelineStage.AI, snapshot, "PASS", "人工放行后 AI 重新评估通过");
        } else {
            message = config.getSayHi();
            pipeline(PipelineStage.AI, snapshot, "SKIP", "AI 未启用，使用固定招呼语");
        }

        AiMessageValidator.Validation validation = AiMessageValidator.validate(message, maxMessageLength);
        if (!validation.valid()) {
            manualReleaseService.markFailed(release.releaseId(), "话术校验不通过：" + validation.reason());
            runContext.recordSkipped();
            return;
        }

        // D-02：放行执行前再次检查联系人；D2 状态同步可能刚回写 REPLIED。
        if (contactKey != null && contactStateService.isAutoContactBlocked(contactKey)) {
            manualReleaseService.markFailed(release.releaseId(), "发送前复检：联系人已回复/拒绝/接管/停止");
            runContext.recordSkipped();
            pipeline(PipelineStage.RESULT, snapshot, "SKIP", "人工放行发送前复检：联系人状态已阻止");
            return;
        }

        BudgetService.Reservation reservation = budgetService.reserveFirstContact(
                runId(), snapshot.encryptId(), snapshot.jobName());
        if (!reservation.granted()) {
            // 预算不足不消耗放行记录：保留 APPROVED，下一轮/次日仍可重试
            log.info("人工放行暂停：预算不足 | releaseId={} | {}", release.releaseId(), reservation.reason());
            progressCallback.accept("人工放行暂停：预算不足，" + reservation.reason(), 0, 0);
            return;
        }

        DeliveryOutcome outcome = deliverJob(snapshot, new DeliveryPlan(DeliveryKind.FIRST_CONTACT, message),
                reservation.reservationId(), true);
        switch (outcome.textStatus()) {
            case CONFIRMED -> {
                runContext.recordDelivered();
                resultList.add(toLegacyJob(snapshot));
                if (contactKey != null) {
                    contactStateService.ensureOnConfirmedFirstContact(contactKey, snapshot.encryptUserId(),
                            snapshot.hrName(), snapshot.companyName(),
                            LocalDateTime.now().format(TIME_FORMAT));
                }
                manualReleaseService.markConsumed(release.releaseId(), outcome.attemptId(),
                        "人工放行执行成功");
                pipeline(PipelineStage.RESULT, snapshot, "DELIVERED", "人工放行 + 文字/图片确认");
                log.info("人工放行发送成功 | releaseId={} | 岗位={} | attempt={}",
                        release.releaseId(), snapshot.jobName(), outcome.attemptId());
            }
            case UNKNOWN -> {
                runContext.recordUnknown();
                manualReleaseService.markConsumed(release.releaseId(), outcome.attemptId(),
                        "发送结果未知，已保守占用额度");
                pipeline(PipelineStage.RESULT, snapshot, "UNKNOWN", "人工放行发送结果未知");
            }
            default -> {
                manualReleaseService.markFailed(release.releaseId(),
                        outcome.detail() == null ? "人工放行未完成发送" : outcome.detail());
                runContext.recordSkipped();
                pipeline(PipelineStage.RESULT, snapshot, "FAIL",
                        outcome.detail() == null ? "人工放行未完成发送" : outcome.detail());
            }
        }
    }

    private JobSnapshot snapshotFromEntity(BossJobDataEntity entity, ManualReleaseService.EvaluationData evaluation) {
        return JobSnapshot.builder()
                .encryptId(entity.getEncryptId())
                .encryptUserId(entity.getEncryptUserId())
                .jobName(entity.getJobName())
                .salary(entity.getSalary())
                .city(entity.getLocation())
                .experience(entity.getExperience())
                .degree(entity.getDegree())
                .jobDescription(entity.getJobDescription())
                .companyName(entity.getCompanyName())
                .industry(entity.getIndustry())
                .financingStage(entity.getFinancingStage())
                .companyScale(entity.getCompanyScale())
                .companyAddress(entity.getCompanyAddress())
                .recruitmentStatus(entity.getRecruitmentStatus())
                .hrName(entity.getHrName())
                .hrPosition(entity.getHrPosition())
                .hrActiveStatus(entity.getHrActiveStatus())
                .jobUrl(entity.getJobUrl() != null ? entity.getJobUrl() : evaluation.jobUrl())
                .sourceKeyword(evaluation.sourceKeyword())
                .build();
    }

    /** 是否应当中止（用户手动停止 或 熔断触发） */
    private boolean isAborted() {
        if (autoStopped) {
            return true;
        }
        return runContext != null && runContext.isStopRequested();
    }

    /** 停止提示语：区分用户取消和自动熔断 */
    private String abortMessage() {
        if (autoStopped) {
            return autoStopReason;
        }
        if (runContext != null && runContext.getStopReason() != null) {
            return runContext.getStopReason();
        }
        return "用户取消投递";
    }

    private String runId() {
        return runContext == null ? "unknown" : runContext.getRunId();
    }

    /** 记录一次「聊天窗口打不开」的失败，连续失败达到阈值即熔断 */
    private void recordChatOpenFailure(String jobName) {
        consecutiveOpenFailures++;
        if (consecutiveFailLimit > 0 && consecutiveOpenFailures >= consecutiveFailLimit && !autoStopped) {
            autoStopped = true;
            autoStopReason = String.format(
                    "连续 %d 个岗位打不开聊天窗口，疑似已达每日沟通上限或被限流", consecutiveOpenFailures);
            if (runContext != null) {
                runContext.abortByCircuitBreaker(autoStopReason);
            }
            log.warn("【熔断】{} | 最后岗位：{} | 本轮已确认发送 {} 个",
                    autoStopReason, jobName, runContext == null ? 0 : runContext.getDeliveredCount());
        }
    }

    /** 记录一次成功确认发送：重置连续失败计数 */
    private void recordDeliverySuccess() {
        consecutiveOpenFailures = 0;
    }

    // ==================== 黑名单更新（保留原有能力） ====================

    /**
     * 从历史聊天记录里回捞“被拒绝/不合适”的会话，把公司名补进黑名单。
     * <p>
     * 这是运营辅助入口，不在投递主流程里：进入会话列表后按“滚动一屏 -> 扫当前已渲染会话”
     * 的节奏逐屏检查，直到列表出现“没有更多了”或达到防呆轮次上限。单条会话读取失败只跳过
     * 该条；滚动失败直接结束，避免页面已经异常还继续空转。
     *
     * @return 三类黑名单快照（公司/招聘者/职位），供管理端展示最新内存状态
     */
    public Map<String, Set<String>> updateBlacklistFromChats() {
        navigateTo(CHAT_LIST_URL);
        PlaywrightUtil.sleep(3);
        int added = scanChatListForRejectedCompanies();
        log.info("黑名单公司数量：{}，本次新增：{}", sizeOf(blackCompanies), added);
        return exportBlacklistSnapshot();
    }

    /** 逐屏扫描会话列表；返回本次新增的公司黑名单数量。 */
    private int scanChatListForRejectedCompanies() {
        int added = 0;
        for (int round = 1; round <= CHAT_BLACKLIST_MAX_ROUNDS; round++) {
            if (isChatListExhausted()) {
                log.debug("会话列表已到底（第{}轮），结束黑名单回捞", round);
                break;
            }
            added += scanVisibleChatRows();
            if (!scrollChatListOnce()) {
                log.warn("会话列表滚动失败，结束黑名单回捞（第{}轮）", round);
                break;
            }
        }
        return added;
    }

    /** 会话列表底部是否已明确提示“没有更多了”。 */
    private boolean isChatListExhausted() {
        try {
            Locator finished = page.locator(FINISHED_TEXT);
            return finished.count() > 0 && "没有更多了".equals(finished.textContent());
        } catch (Exception finishedProbeError) {
            // 到底标记还没渲染出来时按“未到底”继续滚动
            return false;
        }
    }

    /** 扫描当前已渲染的会话行，返回其中新增的黑名单公司数。 */
    private int scanVisibleChatRows() {
        int rows;
        try {
            rows = page.locator(CHAT_LIST_ITEM).count();
        } catch (Exception rowCountError) {
            log.debug("读取会话列表行数失败，本轮跳过：{}", rowCountError.getMessage());
            return 0;
        }
        int added = 0;
        for (int row = 0; row < rows; row++) {
            ChatRow chatRow = readChatRow(row);
            if (chatRow == null || !looksLikeRejection(chatRow.message())) {
                continue;
            }
            if (rememberRejectedCompany(chatRow.companyName(), chatRow.message())) {
                added++;
            }
        }
        return added;
    }

    /** 按行号读取“公司名 + 最后一条消息”；元素在滚动中可能被替换，做有限重试。 */
    private ChatRow readChatRow(int row) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= CHAT_ROW_READ_RETRIES; attempt++) {
            try {
                Locator companies = page.locator(COMPANY_NAME_IN_CHAT);
                Locator messages = page.locator(LAST_MESSAGE);
                if (row >= companies.count() || row >= messages.count()) {
                    return null;
                }
                String companyName = companies.nth(row).textContent();
                String message = messages.nth(row).textContent();
                if (companyName != null && message != null) {
                    return new ChatRow(companyName, message);
                }
                return null;
            } catch (Exception readError) {
                lastError = readError;
                if (attempt < CHAT_ROW_READ_RETRIES) {
                    log.info("会话列表元素已变更，重试读取第{}行（第{}/{}次）",
                            row, attempt, CHAT_ROW_READ_RETRIES);
                    PlaywrightUtil.sleep(1);
                }
            }
        }
        log.info("会话第{}行文本读取{}次失败，跳过本次获取：{}", row, CHAT_ROW_READ_RETRIES,
                lastError == null ? "空内容" : lastError.getMessage());
        return null;
    }

    /** 一条会话行里与黑名单判断相关的字段。 */
    private record ChatRow(String companyName, String message) {
    }

    /** 最后一条消息是否表达“不合适/拒绝”；关键词集合沿用旧实现，避免误伤正常沟通。 */
    private boolean looksLikeRejection(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        boolean rejected = message.contains("不") || message.contains("感谢") || message.contains("但")
                || message.contains("遗憾") || message.contains("需要本") || message.contains("对不");
        boolean exception = message.contains("不是") || message.contains("不生");
        return rejected && !exception;
    }

    /** 公司名是否足够具体到值得写入黑名单（至少两个汉字或四个拉丁字母）。 */
    private boolean isBlacklistableCompanyName(String companyName) {
        if (companyName == null || companyName.isEmpty()) {
            return false;
        }
        return companyName.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*");
    }

    /** 尝试把“被拒绝”的公司写入内存 + 数据库黑名单；返回是否新增。 */
    private boolean rememberRejectedCompany(String rawCompanyName, String message) {
        if (rawCompanyName == null || blackCompanies == null) {
            return false;
        }
        if (blackCompanies.stream().anyMatch(rawCompanyName::contains)) {
            return false;
        }
        String companyName = rawCompanyName.replaceAll("\\.{3}", "");
        if (!isBlacklistableCompanyName(companyName)) {
            return false;
        }
        blackCompanies.add(companyName);
        bossService.addBlacklist("company", companyName);
        log.info("黑名单公司：【{}】，信息：【{}】", companyName, message);
        return true;
    }

    /** 会话列表滚动一屏：优先滚“滚动加载更多”，没有就用整页滚动兜底。 */
    private boolean scrollChatListOnce() {
        try {
            Locator loadMore = page.locator(SCROLL_LOAD_MORE);
            if (loadMore.count() > 0) {
                loadMore.scrollIntoViewIfNeeded();
            } else {
                page.evaluate("window.scrollTo(0, document.body.scrollHeight);");
            }
            return true;
        } catch (Exception scrollError) {
            log.error("滚动会话列表出错：{}", scrollError.getMessage());
            return false;
        }
    }

    /** 导出当前三类黑名单快照（返回副本，不暴露内部集合本体）。 */
    private Map<String, Set<String>> exportBlacklistSnapshot() {
        Map<String, Set<String>> snapshot = new HashMap<>();
        snapshot.put("blackCompanies", copyOrEmpty(blackCompanies));
        snapshot.put("blackRecruiters", copyOrEmpty(blackRecruiters));
        snapshot.put("blackJobs", copyOrEmpty(blackJobs));
        return snapshot;
    }

    private static Set<String> copyOrEmpty(Set<String> source) {
        return source == null ? new HashSet<>() : new HashSet<>(source);
    }

    // ==================== 采集：城市 × 关键词 ====================

    /** 城市维度入口：按关键词逐个采集并投递，单个关键词失败只跳过该关键词。 */
    private void postJobByCity(String cityCode) {
        for (String keyword : config.getKeywords()) {
            if (isAborted()) {
                progressCallback.accept(abortMessage(), 0, 0);
                return;
            }
            String targetUrl = buildSearchUrl(config, cityCode, keyword);
            try {
                postJobsForKeyword(keyword, targetUrl, cityCode);
            } catch (RunCancelledException cancelled) {
                throw cancelled;
            } catch (Exception keywordError) {
                // 单个关键词失败不该让整个投递任务中止
                log.error("【{}】处理失败，跳过该关键词继续下一个：{}", keyword, keywordError.getMessage(), keywordError);
                if (progressCallback != null) {
                    progressCallback.accept("关键词[" + keyword + "]失败已跳过：" + keywordError.getMessage(), 0, 0);
                }
            }
            // 单个关键词异常不向外传播，继续下一个关键词
        }
    }

    // ==================== 搜索 URL 构造 ====================

    /** 追加单个查询参数：空值与「不限」占位值直接跳过，参数顺序即调用顺序。 */
    private static void appendQueryParam(UriComponentsBuilder builder, String name, String value) {
        if (value == null || value.isEmpty() || Objects.equals(Constant.UNLIMITED_CODE, value)) {
            return;
        }
        builder.queryParam(name, value);
    }

    /** 追加多选查询参数（逗号分隔）：空列表或列表里含「不限」占位值时整体跳过。 */
    private static void appendListQueryParam(UriComponentsBuilder builder, String name, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (String value : values) {
            if (Objects.equals(Constant.UNLIMITED_CODE, value)) {
                return;
            }
        }
        builder.queryParam(name, String.join(",", values));
    }

    /** 单个关键词：进列表页 -> 有限滚动采集 -> 逐卡片评估/投递。 */
    private void postJobsForKeyword(String keyword, String targetUrl, String cityCode) {
        openJobListWithRetry(keyword, targetUrl, cityCode);
        CardCollection collection = collectCardsForKeyword(keyword);
        if (!collection.loadedAll()) {
            log.debug("【{}】采集未自然到底（{}），按当前已加载卡片继续", keyword, collection.stopReason());
        }
        progressCallback.accept("岗位采集结束（" + collection.stopReason() + "）：" + keyword
                + "，已加载 " + collection.loadedCount() + " 个", 0, collection.loadedCount());
        returnToTopOfList();
        int delivered = traverseCardsAndDeliver(keyword, cityCode, collection);
        log.info("【{}】岗位处理完毕！确认发送数量:{}", keyword, delivered);
    }

    /** 一次采集的结果摘要。 */
    private record CardCollection(int loadedCount, int scrollRounds, boolean loadedAll, String stopReason) {
    }

    /**
     * 有限滚动加载卡片。
     * <p>
     * 不把“瞬时 count 相等”当作加载结束：只有连续 {@code collectNoNewWindowSeconds} 秒没有
     * 新卡片，或达到每关键词候选上限/采集时长上限/轮次上限，才结束。虚拟列表重排时 count
     * 可能变小，直接以当前值为准，避免误判成“持续无新增”。
     */
    private CardCollection collectCardsForKeyword(String keyword) {
        long collectStartedAt = System.currentTimeMillis();
        long lastGrowthAt = collectStartedAt;
        int visibleCards = -1;
        int rounds = 0;
        int staleErrors = 0;
        boolean loadedAll = false;
        String stopReason = "达到滚动轮次上限";
        while (rounds < CARD_SCROLL_MAX_ROUNDS) {
            if (isAborted()) {
                progressCallback.accept(abortMessage(), 0, 0);
                return new CardCollection(Math.max(0, visibleCards), rounds, loadedAll, "任务被取消");
            }
            if (collectDurationExceeded(collectStartedAt)) {
                stopReason = "达到采集时长上限（" + effectiveCollectMaxDurationMinutes() + " 分钟）";
                break;
            }
            if (collectCardLimitReached(visibleCards)) {
                stopReason = "达到每关键词候选上限（" + effectiveCollectMaxPerKeyword() + " 个）";
                break;
            }
            rounds++;
            try {
                page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * 1.5))");
                int currentCards = countVisibleCardsProbe();
                if (currentCards > visibleCards) {
                    lastGrowthAt = System.currentTimeMillis();
                }
                visibleCards = currentCards;
                if (currentCards == 0) {
                    // 列表还没渲染出来时多给一拍，避免直接判定“没有卡片”
                    PlaywrightUtil.sleep(1);
                }
                if (noNewCardsLongEnough(lastGrowthAt)) {
                    loadedAll = true;
                    stopReason = "连续 " + Math.max(1, effectiveCollectNoNewWindowSeconds()) + " 秒无新增，判定加载结束"
                            + (isListFooterVisible() ? "（已到底部）" : "");
                    break;
                }
            } catch (Exception scrollError) {
                if (!isStaleObjectError(scrollError)) {
                    throw scrollError;
                }
                staleErrors++;
                if (staleErrors > CARD_SCROLL_STALE_TOLERANCE) {
                    log.warn("【{}】滚动期间反复出现失效对象异常({}次)，停止继续加载", keyword, staleErrors);
                    stopReason = "滚动期间页面失效对象异常过多";
                    break;
                }
                PlaywrightUtil.sleep(1);
            }
        }
        int loadedCount = Math.max(visibleCards, countJobCards());
        log.info("【{}】采集阶段结束（{}），加载卡片数={}，滚动轮次={}", keyword, stopReason, loadedCount, rounds);
        return new CardCollection(loadedCount, rounds, loadedAll, stopReason);
    }

    /** 是否超过单关键词采集时长上限（配置 <=0 表示不限）。 */
    private boolean collectDurationExceeded(long collectStartedAt) {
        int maxMinutes = effectiveCollectMaxDurationMinutes();
        return maxMinutes > 0 && System.currentTimeMillis() - collectStartedAt > maxMinutes * 60_000L;
    }

    /** 是否达到每关键词候选上限（配置 <=0 表示不限）。 */
    private boolean collectCardLimitReached(int visibleCards) {
        int maxCards = effectiveCollectMaxPerKeyword();
        return maxCards > 0 && visibleCards >= maxCards;
    }

    /** 距离上次新增卡片是否已超过“连续无新增”判定窗口。 */
    private boolean noNewCardsLongEnough(long lastGrowthAt) {
        long windowSeconds = Math.max(1, effectiveCollectNoNewWindowSeconds());
        return System.currentTimeMillis() - lastGrowthAt >= windowSeconds * 1000L;
    }

    /** 滚动探测用卡片数：与遍历用 selector 保持一致，只看当前已渲染的卡片。 */
    private int countVisibleCardsProbe() {
        return page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]").count();
    }

    /** 列表页脚是否可见：只作为日志里的辅助说明，不单独作为加载结束依据。 */
    private boolean isListFooterVisible() {
        try {
            Locator footer = page.locator("div#footer, #footer");
            return footer.count() > 0 && footer.first().isVisible();
        } catch (Exception footerError) {
            return false;
        }
    }

    /** 采集完成后回到列表顶部，从第一张卡片开始处理。 */
    private void returnToTopOfList() {
        page.evaluate("window.scrollTo(0, 0);");
        PlaywrightUtil.sleep(1);
    }

    /** 逐个卡片评估/投递；返回本轮确认发送数量。 */
    private int traverseCardsAndDeliver(String keyword, String cityCode, CardCollection collection) {
        int cardLimit = effectiveCollectMaxPerKeyword();
        int planned = cardLimit > 0 ? Math.min(collection.loadedCount(), cardLimit) : collection.loadedCount();
        // 以实际渲染的卡片数为准，避免采集统计与点击目标不一致
        int renderedCards = page.locator(JOB_LIST_SELECTOR).count();
        int total = Math.min(planned, renderedCards);
        if (cardLimit > 0 && collection.loadedCount() > total) {
            log.info("【{}】候选上限 {}，本次只处理前 {} 个（共加载 {} 个）",
                    keyword, cardLimit, total, collection.loadedCount());
        }
        int delivered = 0;
        for (int index = 0; index < total; index++) {
            if (isAborted()) {
                progressCallback.accept(abortMessage(), index, total);
                return delivered;
            }
            // 每轮重新获取卡片，避免虚拟列表重排后元素过期
            Locator cards = page.locator(JOB_LIST_SELECTOR);
            Response detailResponse = clickCardAndWaitDetail(cards, index, total);
            PlaywrightUtil.sleep(1);

            CardAction action;
            try {
                action = handleJobCard(keyword, index, total, detailResponse, cityCode);
            } catch (RunCancelledException cancelled) {
                throw cancelled;
            }
            if (action == CardAction.DELIVERED) {
                delivered++;
            }
            if (action == CardAction.STOP_RUN) {
                return delivered;
            }
            nudgeListAfterDeepClick(index);
            pauseBetweenJobs();
            pauseForVerificationIfNeeded(keyword, index + 1, total);
        }
        return delivered;
    }

    /** 点得比较深之后轻微下滑，避免下一次点击命中的还是同一张卡片触发页面刷新。 */
    private void nudgeListAfterDeepClick(int index) {
        if (index < 5) {
            return;
        }
        try {
            page.evaluate("window.scrollBy(0, 140);");
            PlaywrightUtil.sleep(1);
        } catch (Throwable ignore) {
            // 下滑失败不影响卡片处理，下一轮会重新定位卡片
        }
    }

    /** 停顿/滚动期间可能被弹到安全验证页，发现后进入人工验证等待。 */
    private void pauseForVerificationIfNeeded(String keyword, int processed, int total) {
        String currentUrl = safeUrl();
        if (!isSecurityVerifyUrl(currentUrl)) {
            return;
        }
        log.warn("【{}】遍历过程中被跳转到安全验证页：{}", keyword, currentUrl);
        if (progressCallback != null) {
            progressCallback.accept("触发Boss安全校验，请在浏览器中手动完成验证", processed, total);
        }
        waitForSliderVerify(page);
    }
    /** 卡片处理结果 */
    private enum CardAction { DELIVERED, SKIPPED, STOP_RUN }

    /**
     * 点击岗位卡片并等待详情接口响应。
     * <p>
     * 第一张卡默认展开不会触发请求，所以先点第二张再点回第一张，并监听回到第一张时的响应。
     */
    private Response clickCardAndWaitDetail(Locator cards, int index, int count) {
        try {
            if (index == 0 && count > 1) {
                final Locator secondCard = cards.nth(1);
                secondCard.click();
                PlaywrightUtil.sleep(1);
                final Locator firstCard = cards.nth(0);
                return page.waitForResponse(r -> {
                    try {
                        return r.url() != null && r.url().contains("/wapi/zpgeek/job/detail.json")
                                && "GET".equalsIgnoreCase(r.request().method());
                    } catch (Throwable ignore) {
                        return false;
                    }
                }, firstCard::click);
            }
            final Locator cardToClick = cards.nth(index);
            return page.waitForResponse(r -> {
                try {
                    return r.url() != null && r.url().contains("/wapi/zpgeek/job/detail.json")
                            && "GET".equalsIgnoreCase(r.request().method());
                } catch (Throwable ignore) {
                    return false;
                }
            }, cardToClick::click);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * 处理单张岗位卡片：从详情响应到"是否发起沟通"的全部判断都在这里完成。
     * 不匹配/身份不明/预算不足的岗位不会打开详情页，更不会点击沟通按钮。
     */
    /**
     * 兼容旧调用（反射测试/历史代码）：不带城市码时来源只记录关键词。
     */
    @SuppressWarnings("unused")
    private CardAction handleJobCard(String keyword, int index, int total, Response detailResp) {
        return handleJobCard(keyword, index, total, detailResp, null);
    }

    private CardAction handleJobCard(String keyword, int index, int total, Response detailResp, String cityCode) {
        if (detailResp == null) {
            recordSkippedEvaluation(keyword, RuleReason.MISSING_DETAIL_RESPONSE, "岗位详情接口无响应");
            runContext.recordSkipped();
            progressCallback.accept("跳过：岗位详情接口无响应", index + 1, total);
            return CardAction.SKIPPED;
        }
        String body;
        try {
            body = detailResp.text();
        } catch (Throwable e) {
            recordSkippedEvaluation(keyword, RuleReason.MISSING_DETAIL_RESPONSE, "详情响应读取失败：" + e.getMessage());
            runContext.recordSkipped();
            progressCallback.accept("跳过：岗位详情读取失败", index + 1, total);
            return CardAction.SKIPPED;
        }
        if (Boolean.TRUE.equals(config.getDebugger())) {
            appendRawJson(body);
        }
        Optional<JobSnapshot> parsed = BossJobDetailParser.parse(body);
        if (parsed.isEmpty()) {
            recordSkippedEvaluation(keyword, RuleReason.MISSING_DETAIL_RESPONSE, "详情 JSON 解析失败或缺少 jobInfo");
            runContext.recordSkipped();
            progressCallback.accept("跳过：岗位详情解析失败", index + 1, total);
            return CardAction.SKIPPED;
        }
        JobSnapshot snapshot = parsed.get();
        String displayName = snapshot.jobName() == null ? "(未知岗位)" : snapshot.jobName();
        progressCallback.accept("正在评估：" + displayName, index + 1, total);

        // ===== 1. 岗位身份核对：详情响应必须属于刚点击的卡片 =====
        RuleDecision identity = checkIdentity(snapshot);
        if (identity.outcome() != RuleOutcome.PASS) {
            log.warn("被跳过（岗位身份无法确认）：{} | 公司：{} | 岗位：{} | 证据：{}",
                    identity.detail(), snapshot.companyName(), displayName, identity.evidence());
            updateJobStatus(snapshot, "已过滤");
            recordEvaluation(snapshot, keyword, identity, null, null);
            runContext.recordSkipped();
            return CardAction.SKIPPED;
        }

        // ===== 2. 入库/刷新岗位快照（已有记录也会更新薪资/JD/HR 活跃度） =====
        try {
            bossService.upsertBossJob(toEntity(snapshot));
        } catch (Exception e) {
            log.warn("岗位快照入库失败（不影响本次判断）：{}", e.getMessage());
        }

        // ===== 2.1 采集溯源：记录搜索来源（幂等）+ 本轮虚拟列表去重 =====
        try {
            bossService.recordJobSource(keyword, cityCode, snapshot.encryptId(), snapshot.snapshotHash());
        } catch (Exception e) {
            log.warn("记录岗位来源失败（不影响投递）：{}", e.getMessage());
        }
        if (snapshot.encryptId() != null && !processedEncryptIds.add(snapshot.encryptId())) {
            log.info("同岗位已在本轮处理过（虚拟列表重排/多关键词），仅记录来源 | 岗位：{}", displayName);
            progressCallback.accept("岗位已处理过，仅更新来源：" + displayName, index + 1, total);
            return CardAction.SKIPPED;
        }
        pipeline(PipelineStage.COLLECT, snapshot, "PASS", "详情已获取，来源关键词=" + keyword);

        // ===== 3. 硬规则（本地、无副作用） =====
        RuleDecision rule;
        pipeline(PipelineStage.RULE, snapshot, "RUNNING", "开始规则筛选");
        try {
            rule = ruleEngine.evaluate(snapshot);
        } catch (Exception e) {
            log.warn("岗位规则引擎异常，按待复核处理：{}", e.getMessage());
            rule = RuleDecision.review(RuleReason.MISSING_JOB_CONTENT, "RULE_ENGINE_ERROR", e.getMessage());
        }
        if (rule.outcome() == RuleOutcome.REJECT) {
            log.info("被规则拒绝：{} | 证据：{} | 公司：{} | 岗位：{} | 薪资：{}",
                    rule.reason(), rule.evidence(), snapshot.companyName(), displayName, snapshot.salary());
            pipeline(PipelineStage.RULE, snapshot, "REJECT", rule.reason() + " " + (rule.evidence() == null ? "" : rule.evidence()));
            updateJobStatus(snapshot, "已过滤");
            recordEvaluation(snapshot, keyword, rule, null, null);
            runContext.recordRuleRejected();
            progressCallback.accept("规则拒绝，跳过：" + displayName + "（" + rule.reason() + "）", index + 1, total);
            return CardAction.SKIPPED;
        }
        if (rule.outcome() == RuleOutcome.REVIEW) {
            log.info("待复核，暂不投递：{} | 证据：{} | 岗位：{}", rule.reason(), rule.evidence(), displayName);
            pipeline(PipelineStage.RULE, snapshot, "REVIEW", rule.reason() + " " + (rule.evidence() == null ? "" : rule.evidence()));
            recordEvaluation(snapshot, keyword, rule, null, null);
            runContext.recordSkipped();
            progressCallback.accept("待复核，跳过：" + displayName + "（" + rule.reason() + "）", index + 1, total);
            return CardAction.SKIPPED;
        }
        pipeline(PipelineStage.RULE, snapshot, "PASS", "规则通过");

        // ===== 4. 联系人状态 / 跨岗位去重 / 跟进批准判断 =====
        pipeline(PipelineStage.GATE, snapshot, "RUNNING", "检查联系人/去重/跟进批准");
        String contactKey = ContactStateService.contactKey(snapshot.encryptUserId());
        if (contactKey != null && contactStateService.isAutoContactBlocked(contactKey)) {
            RuleDecision blocked = RuleDecision.review(RuleReason.CONTACT_BLOCKED, contactKey,
                    "联系人已回复/明确拒绝/人工接管/停止联系，禁止自动沟通");
            log.info("联系人状态禁止自动沟通，跳过 | 岗位：{} | contactKey={}", displayName, contactKey);
            pipeline(PipelineStage.GATE, snapshot, "SKIP", "联系人已被标记（回复/接管/停止）");
            recordEvaluation(snapshot, keyword, blocked, null, null);
            runContext.recordSkipped();
            progressCallback.accept("联系人已标记（回复/接管/停止），跳过：" + displayName, index + 1, total);
            return CardAction.SKIPPED;
        }
        boolean firstConfirmed = snapshot.hasIdentity()
                && deliveryLedgerService.hasConfirmedFirstContact(snapshot.encryptId());
        DeliveryKind kind;
        FollowupPlanRepository.FollowupPlan approvedFollowupPlan = null;
        if (!firstConfirmed) {
            // 同一 HR 在其他岗位已确认沟通 → 不重复打招呼（跨岗位接触去重，只看可信联系人 ID）
            if (contactKey != null && contactDedupeDays > 0
                    && contactStateService.hasOtherJobConfirmedFirstContact(
                    contactKey, snapshot.encryptId(), contactDedupeDays)) {
                RuleDecision duplicate = RuleDecision.review(RuleReason.CONTACT_ALREADY_GREETED, contactKey,
                        "同一联系人已在其他岗位确认沟通（" + contactDedupeDays + " 天内），避免重复问候");
                log.info("同 HR 跨岗位去重，跳过 | 岗位：{} | contactKey={}", displayName, contactKey);
                pipeline(PipelineStage.GATE, snapshot, "SKIP", "同一 HR 已在其他岗位沟通过");
                recordEvaluation(snapshot, keyword, duplicate, null, null);
                runContext.recordSkipped();
                progressCallback.accept("同一 HR 已在其他岗位沟通过，跳过：" + displayName, index + 1, total);
                return CardAction.SKIPPED;
            }
            kind = DeliveryKind.FIRST_CONTACT;
        } else if (followupAllowed(snapshot)) {
            // B3：跟进默认需要管理台批准；批准不会立即发送，只允许下一轮任务执行
            if (followupRequireApproval) {
                approvedFollowupPlan = contactKey == null ? null
                        : followupService.findApproved(contactKey, snapshot.encryptId(),
                        FollowupService.PLAN_FOLLOWUP);
                if (approvedFollowupPlan == null) {
                    RuleDecision notApproved = RuleDecision.review(RuleReason.FOLLOWUP_NOT_APPROVED,
                            "no-approved-plan", "跟进需要先在管理台批准建议跟进（默认不自动发送）");
                    log.info("跟进未获批准，跳过 | 岗位：{} | contactKey={}", displayName, contactKey);
                    pipeline(PipelineStage.GATE, snapshot, "SKIP", "跟进未获批准（需先在跟进待办批准）");
                    recordEvaluation(snapshot, keyword, notApproved, null, null);
                    runContext.recordSkipped();
                    progressCallback.accept("跟进未获批准，跳过：" + displayName, index + 1, total);
                    return CardAction.SKIPPED;
                }
            }
            kind = DeliveryKind.FOLLOWUP;
        } else {
            log.info("已确认沟通过的岗位，跳过重复投递 | 公司：{} | 岗位：{}", snapshot.companyName(), displayName);
            pipeline(PipelineStage.GATE, snapshot, "SKIP", "已确认沟通过（跟进次数/间隔不满足或未开启重复沟通）");
            recordEvaluation(snapshot, keyword, rule, null, null);
            runContext.recordSkipped();
            progressCallback.accept("已沟通过，跳过：" + displayName, index + 1, total);
            return CardAction.SKIPPED;
        }

        // ===== 5. AI 评估（仅首次沟通；失败一律跳过，绝不发原文/兜底话术） =====
        pipeline(PipelineStage.GATE, snapshot, "PASS", "准入检查通过，类型=" + kind);
        AiMatchResult aiResult = null;
        String message;
        if (kind == DeliveryKind.FIRST_CONTACT) {
            if (Boolean.TRUE.equals(config.getEnableAI())) {
                pipeline(PipelineStage.AI, snapshot, "RUNNING", "AI 评估中");
                try {
                    AiEntity aiConfig = aiService.getAiConfig();
                    aiResult = aiMatchService.evaluate(aiConfig, snapshot.jobName(), snapshot.jobDescription(),
                            keyword, config.getSayHi(), maxMessageLength);
                } catch (Exception aiError) {
                    // AI 服务/配置异常：按失败处理，绝不用兜底话术绕过匹配判断
                    log.warn("AI 评估异常，按失败处理并跳过岗位 | 岗位：{} | {}", displayName, aiError.getMessage());
                    aiResult = AiMatchResult.error("AI_EVALUATE_EXCEPTION", aiError.getMessage());
                }
                if (aiResult.outcome() != AiOutcome.MATCH) {
                    log.warn("AI 未通过，跳过岗位 | 岗位：{} | 结果：{} | 原因：{}",
                            displayName, aiResult.outcome(),
                            aiResult.errorCode() != null ? aiResult.errorCode() : aiResult.analysis());
                    pipeline(PipelineStage.AI, snapshot,
                            aiResult.outcome() == AiOutcome.ERROR ? "ERROR" : aiResult.outcome().name(),
                            aiResult.errorCode() != null ? aiResult.errorCode() : aiResult.analysis());
                    recordEvaluation(snapshot, keyword, rule, aiResult, kind);
                    runContext.recordSkipped();
                    progressCallback.accept("AI 未通过，跳过：" + displayName, index + 1, total);
                    return CardAction.SKIPPED;
                }
                pipeline(PipelineStage.AI, snapshot, "PASS", "AI 匹配通过");
                message = aiResult.message();
            } else {
                // 用户明确关闭 AI：仅硬规则模式，使用配置的固定招呼语
                pipeline(PipelineStage.AI, snapshot, "SKIP", "AI 未启用，使用固定招呼语");
                message = config.getSayHi();
            }
        } else {
            pipeline(PipelineStage.AI, snapshot, "SKIP", "跟进消息不调用 AI");
            message = config.randomRepeatChatMessage();
            if (message == null) {
                log.info("重复沟通消息列表为空，跳过岗位：{}", displayName);
                recordEvaluation(snapshot, keyword, rule, null, kind);
                runContext.recordSkipped();
                return CardAction.SKIPPED;
            }
        }

        // ===== 6. 发送前最终校验话术（AI 输出、固定话术、跟进话术一视同仁） =====
        AiMessageValidator.Validation validation = AiMessageValidator.validate(message, maxMessageLength);
        if (!validation.valid()) {
            log.warn("话术校验不通过，跳过岗位 | 岗位：{} | 原因：{}", displayName, validation.reason());
            recordEvaluation(snapshot, keyword, rule, aiResult, kind);
            runContext.recordSkipped();
            progressCallback.accept("话术不合法，跳过：" + displayName, index + 1, total);
            return CardAction.SKIPPED;
        }

        // ===== 7. 调试模式：完整执行规则与 AI，但不建会话、不占预算 =====
        if (Boolean.TRUE.equals(config.getDebugger())) {
            log.info("调试模式：仅评估不投递 | 公司：{} | 岗位：{} | 薪资：{} | 话术：{}",
                    snapshot.companyName(), displayName, snapshot.salary(), message);
            recordEvaluation(snapshot, keyword, rule, aiResult, kind);
            runContext.recordSkipped();
            progressCallback.accept("调试模式：评估通过但不投递 " + displayName, index + 1, total);
            return CardAction.SKIPPED;
        }

        // AI 请求可能在停止指令之后才返回：预算预留和发送前必须重新确认任务仍然有效，
        // 避免"已经点了停止，任务却又发起了一次沟通"。
        if (isAborted()) {
            throw new RunCancelledException(abortMessage());
        }

        // D-02：进入预算/发送前再查一次联系人状态。D2 状态同步可能在
        // 本轮 AI 评估期间把“HR 已回复”回写为 REPLIED；此时必须停手。
        if (contactKey != null && contactStateService.isAutoContactBlocked(contactKey)) {
            pipeline(PipelineStage.RESULT, snapshot, "SKIP", "发送前复检：联系人已回复/拒绝/接管/停止");
            runContext.recordSkipped();
            log.info("发送前复检阻止投递 | 岗位：{} | contactKey={}", displayName, contactKey);
            return CardAction.SKIPPED;
        }

        // ===== 8. 每日预算（持久化、原子预留；重启不清零） =====
        BudgetService.Reservation reservation = kind == DeliveryKind.FIRST_CONTACT
                ? budgetService.reserveFirstContact(runId(), snapshot.encryptId(), snapshot.jobName())
                : budgetService.reserveFollowup(runId(), snapshot.encryptId(), snapshot.jobName());
        if (!reservation.granted()) {
            autoStopped = true;
            autoStopReason = reservation.reason() == null ? "今日沟通预算已用尽" : reservation.reason();
            pipeline(PipelineStage.RESULT, snapshot, "SKIP", autoStopReason);
            runContext.abortByCircuitBreaker(autoStopReason);
            log.warn("【停止】{} | 岗位：{}", autoStopReason, displayName);
            recordEvaluation(snapshot, keyword, rule, aiResult, kind);
            progressCallback.accept(autoStopReason, 0, 0);
            return CardAction.STOP_RUN;
        }
        recordEvaluation(snapshot, keyword, rule, aiResult, kind);
        pipeline(PipelineStage.CHAT, snapshot, "RUNNING", "准备打开沟通入口");
        try {
            if (progressCallback != null) {
                progressCallback.accept("正在投递：" + displayName, index + 1, total);
            }
        } catch (Exception callbackError) {
            // SSE/回调异常不能把已预留的预算丢在那里：释放预留并跳过本次发送
            log.warn("进度回调异常，释放本次预算预留：{}", callbackError.getMessage());
            pipeline(PipelineStage.RESULT, snapshot, "FAIL", "进度回调异常，未发起发送");
            budgetService.release(reservation.reservationId(), "进度回调异常，未发起发送");
            runContext.recordSkipped();
            return CardAction.SKIPPED;
        }

        // ===== 9. 打开详情页/聊天页，身份核对后发送并确认 =====
        DeliveryOutcome outcome;
        try {
            outcome = deliverJob(snapshot, new DeliveryPlan(kind, message), reservation.reservationId());
        } catch (RunCancelledException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            log.error("岗位投递流程异常 | 岗位：" + displayName, e);
            pipeline(PipelineStage.RESULT, snapshot, "FAIL", "投递流程异常：" + e.getMessage());
            runContext.recordUnknown();
            return CardAction.SKIPPED;
        }

        switch (outcome.textStatus()) {
            case CONFIRMED -> {
                pipeline(PipelineStage.RESULT, snapshot, "DELIVERED",
                        "文字+图片确认 | " + outcome.detail());
                if (kind == DeliveryKind.FIRST_CONTACT) {
                    runContext.recordDelivered();
                    resultList.add(toLegacyJob(snapshot));
                    // B3：首次沟通确认后回写联系人最后联系时间，供跨岗位去重/跟进建议使用
                    if (contactKey != null) {
                        contactStateService.ensureOnConfirmedFirstContact(contactKey,
                                snapshot.encryptUserId(), snapshot.hrName(), snapshot.companyName(),
                                LocalDateTime.now().format(TIME_FORMAT));
                    }
                } else {
                    runContext.recordFollowup();
                    try {
                        deliveryLedgerService.recordFollowup(snapshot.encryptId(), snapshot.encryptUserId(), snapshot.jobName());
                    } catch (Exception e) {
                        log.warn("跟进次数落库失败：{}", e.getMessage());
                    }
                    // B3：跟进已确认执行 → 标记计划 EXECUTED（避免再次批准/重复打扰）
                    if (approvedFollowupPlan != null) {
                        followupService.markExecuted(approvedFollowupPlan.planId(), outcome.attemptId());
                    }
                }
                recordDeliverySuccess();
                log.info("发送成功 | 类型：{} | 公司：{} | 岗位：{} | 薪资：{} | 图片：{} | 证据：{}",
                        kind, snapshot.companyName(), displayName, snapshot.salary(),
                        outcome.imageStatus(), outcome.detail());
                return CardAction.DELIVERED;
            }
            case UNKNOWN -> {
                pipeline(PipelineStage.RESULT, snapshot, "UNKNOWN",
                        "发送结果未知（额度保守占用）：" + outcome.detail());
                runContext.recordUnknown();
                if (kind == DeliveryKind.FOLLOWUP) {
                    // 跟进结果未知也要记账，避免下次把它当成"从未跟进"而反复打扰同一 HR
                    try {
                        deliveryLedgerService.recordFollowup(snapshot.encryptId(), snapshot.encryptUserId(), snapshot.jobName());
                    } catch (Exception e) {
                        log.warn("跟进次数落库失败：{}", e.getMessage());
                    }
                    // B3：结果未知也标记计划已执行（可能已发出，不能再次自动跟进）
                    if (approvedFollowupPlan != null) {
                        followupService.markExecuted(approvedFollowupPlan.planId(), outcome.attemptId());
                    }
                }
                log.warn("发送结果未知（未记成功，额度保守占用） | 岗位：{} | 原因：{}", displayName, outcome.detail());
                return CardAction.SKIPPED;
            }
            case FAILED -> {
                pipeline(PipelineStage.RESULT, snapshot, "FAIL", "发送失败：" + outcome.detail());
                log.warn("发送失败 | 岗位：{} | 原因：{}", displayName, outcome.detail());
                return CardAction.SKIPPED;
            }
            default -> {
                pipeline(PipelineStage.RESULT, snapshot, "SKIP", "未发起发送：" + outcome.detail());
                log.info("未发起发送 | 岗位：{} | 原因：{}", displayName, outcome.detail());
                return CardAction.SKIPPED;
            }
        }
    }

    // ==================== 投递执行 ====================

    private record DeliveryPlan(DeliveryKind kind, String message) {
    }

    private record DeliveryOutcome(String attemptId, boolean chatOpened, DeliveryKind kind,
                                   TextSendStatus textStatus, ImageSendStatus imageStatus,
                                   String detail) {
    }

    /** 结算动作：确认占用 / 结果未知继续占用 / 释放预留 */
    private enum BudgetAction { CONFIRM, UNKNOWN, RELEASE }

    /** 一次发送尝试的中间状态，最终在 settleAttempt 里一次性落库，避免半途状态 */
    private static final class DeliveryDraft {
        /** attempt 主键（settle 后用于跟进计划执行标记） */
        long attemptRowId;
        String attemptId;
        /** 是否点击过沟通入口（= 已产生对外沟通动作，预算不能轻易释放） */
        boolean chatRequested;
        boolean chatOpened;
        TextSendStatus textStatus = TextSendStatus.NOT_ATTEMPTED;
        ImageSendStatus imageStatus = ImageSendStatus.NOT_ATTEMPTED;
        BudgetAction budgetAction = BudgetAction.RELEASE;
        String evidenceLevel = "DOM";
        String detail = "";
        String errorCode;

        DeliveryOutcome toOutcome(DeliveryKind kind) {
            return new DeliveryOutcome(attemptId, chatOpened, kind, textStatus, imageStatus, detail);
        }
    }

    /**
     * 打开岗位详情页并完成一次发送尝试。
     * <p>
     * 所有退出路径都会经过 finally 里的 settleAttempt：文字/图片状态、预算结算、
     * boss_data 状态、attempt 收尾只在一个地方写，避免"点一下就当成功"。
     */
    private DeliveryOutcome deliverJob(JobSnapshot snapshot, DeliveryPlan plan, String reservationId) {
        return deliverJob(snapshot, plan, reservationId, false);
    }

    private DeliveryOutcome deliverJob(JobSnapshot snapshot, DeliveryPlan plan, String reservationId,
                                       boolean useSnapshotJobUrl) {
        DeliveryLedgerService.AttemptStarted attempt;
        String contactKey = ContactStateService.contactKey(snapshot.encryptUserId());
        try {
            attempt = deliveryLedgerService.beginAttempt(
                    runId(), plan.kind(), snapshot.encryptId(), snapshot.encryptUserId(),
                    snapshot.jobName(), snapshot.companyName(), snapshot.jobUrl(),
                    snapshot.snapshotHash(), sha256Hex(plan.message()), null, reservationId,
                    contactKey, null);
        } catch (Exception ledgerError) {
            // 台账写不进去时不允许"盲发"：释放预留，本次不发送
            log.error("投递台账初始化失败，取消本次发送 | 岗位：{} | {}", snapshot.jobName(), ledgerError.getMessage());
            try {
                budgetService.release(reservationId, "投递台账初始化失败");
            } catch (Exception ignore) {
            }
            return new DeliveryOutcome(null, false, plan.kind(), TextSendStatus.NOT_ATTEMPTED,
                    ImageSendStatus.NOT_ATTEMPTED, "投递台账初始化失败，未发送：" + ledgerError.getMessage());
        }
        long rowId = attempt.rowId();
        try {
            deliveryLedgerService.appendEvent(rowId, runId(), "BUDGET_RESERVED", "DB",
                    "预算预留成功 | reservationId=" + reservationId);
        } catch (Exception e) {
            log.debug("记录预算预留事件失败：{}", e.getMessage());
        }
        DeliveryDraft draft = new DeliveryDraft();
        draft.attemptRowId = rowId;
        draft.attemptId = attempt.attemptId();
        try {
            deliverJobInternal(snapshot, plan, rowId, draft, useSnapshotJobUrl);
        } catch (RunCancelledException cancelled) {
            if (draft.chatRequested || draft.chatOpened
                    || draft.textStatus != TextSendStatus.NOT_ATTEMPTED) {
                // 已经点击过沟通入口或进入会话：结果未知，保守占用额度
                draft.budgetAction = BudgetAction.UNKNOWN;
                if (draft.textStatus == TextSendStatus.NOT_ATTEMPTED) {
                    draft.textStatus = TextSendStatus.UNKNOWN;
                }
                draft.errorCode = "CANCEL";
                draft.detail = draft.detail.isBlank()
                        ? "任务取消时发送状态未知：" + cancelled.getMessage()
                        : draft.detail;
            } else {
                // 还没点击沟通入口、没触发任何外部动作：释放预留，绝不占用额度
                draft.budgetAction = BudgetAction.RELEASE;
                draft.errorCode = "CANCEL_BEFORE_CHAT";
                draft.detail = "任务在点击沟通入口前取消，未触发任何发送动作";
            }
            throw cancelled;
        } catch (Exception e) {
            if (draft.chatRequested || draft.chatOpened
                    || draft.textStatus != TextSendStatus.NOT_ATTEMPTED) {
                draft.textStatus = TextSendStatus.UNKNOWN;
                draft.budgetAction = BudgetAction.UNKNOWN;
                draft.errorCode = "UNEXPECTED_ERROR";
                draft.detail = "发送流程异常：" + e.getMessage();
            } else {
                draft.textStatus = TextSendStatus.NOT_ATTEMPTED;
                draft.budgetAction = BudgetAction.RELEASE;
                draft.errorCode = "ERROR_BEFORE_CHAT";
                draft.detail = "点击沟通入口前异常，未发起发送：" + e.getMessage();
            }
            log.error("发送流程异常 | 岗位：" + snapshot.jobName(), e);
        } finally {
            settleAttempt(snapshot, rowId, reservationId, draft);
        }
        // 注意：返回的 outcome 必须在 settleAttempt 之后构建，
        // 否则"点击过沟通入口 → 预算升级为 UNKNOWN"的修正不会体现在调用方统计里。
        return draft.toOutcome(plan.kind());
    }

    private void deliverJobInternal(JobSnapshot snapshot, DeliveryPlan plan, long rowId, DeliveryDraft draft,
                                    boolean useSnapshotJobUrl) {
        if (isAborted()) {
            throw new RunCancelledException(abortMessage());
        }
        // 人工放行必须直接使用快照里的岗位链接，不能误用当前列表页残留的“查看更多”。
        Locator moreInfoBtn = useSnapshotJobUrl ? null : page.locator("a.more-job-btn");
        String detailUrl;
        if (moreInfoBtn != null && moreInfoBtn.count() > 0) {
            String href = moreInfoBtn.first().getAttribute("href");
            if (href == null || !href.startsWith("/job_detail/")) {
                draft.detail = "未获取到岗位详情链接";
                return;
            }
            detailUrl = "https://www.zhipin.com" + href;
        } else if (snapshot.jobUrl() != null && snapshot.jobUrl().startsWith("https://www.zhipin.com/")) {
            detailUrl = snapshot.jobUrl();
        } else {
            draft.detail = "未找到“查看更多信息”按钮，且快照没有可用的岗位链接";
            return;
        }

        Page detailPage = page.context().newPage();
        try (BossChatNavigator navigator = new BossChatNavigator(detailPage)) {
            detailPage.navigate(detailUrl);
            PlaywrightUtil.sleep(1);

            // 1. 找沟通按钮
            Locator chatBtn = detailPage.locator("a.btn-startchat, a.op-btn-chat");
            boolean foundChatBtn = false;
            String buttonText = "";
            for (int i = 0; i < 5; i++) {
                if (isAborted()) {
                    throw new RunCancelledException(abortMessage());
                }
                if (chatBtn.count() > 0) {
                    String text = chatBtn.first().textContent();
                    if (text != null && (text.contains("立即沟通") || text.contains("继续沟通"))) {
                        foundChatBtn = true;
                        buttonText = text;
                        break;
                    }
                    if (text != null && !text.isBlank()) {
                        buttonText = text;
                    }
                }
                PlaywrightUtil.sleep(1);
            }
            if (!foundChatBtn) {
                draft.detail = "未找到可用的沟通按钮";
                draft.errorCode = "CHAT_BUTTON_NOT_FOUND";
                return;
            }

            boolean pageSaysRepeat = buttonText.contains("继续沟通");
            boolean allowRepeat = Boolean.TRUE.equals(config.getAllowRepeatChat());
            if (plan.kind() == DeliveryKind.FIRST_CONTACT && pageSaysRepeat) {
                // 本地没有确认记录、页面却显示已有会话：可能是历史遗留或人工沟通过，宁可跳过也不重复打扰
                draft.detail = allowRepeat
                        ? "页面显示已有会话但本地无确认记录，跳过以免重复打扰"
                        : "页面显示已沟通过且未开启重复沟通，跳过";
                draft.errorCode = "CHAT_ALREADY_EXISTS";
                return;
            }
            if (plan.kind() == DeliveryKind.FOLLOWUP) {
                if (!pageSaysRepeat) {
                    draft.detail = "页面显示无历史会话，不能发送跟进消息";
                    draft.errorCode = "NO_EXISTING_CHAT";
                    return;
                }
                if (!allowRepeat) {
                    draft.detail = "未开启重复沟通开关，跳过跟进";
                    draft.errorCode = "REPEAT_CHAT_DISABLED";
                    return;
                }
            }

            // 2. 打开聊天页。点击前先落库"沟通意图"：点击动作发生后即使页面没加载出来，
            //    也不能证明没有建立会话，预算必须按不确定处理（A-03）。
            deliveryLedgerService.appendEvent(rowId, runId(), "CHAT_REQUESTED", "DOM",
                    "即将点击沟通入口 | " + buttonText);
            BossChatNavigator.OpenResult openResult = navigator.open(chatBtn.first(), pageSaysRepeat, allowRepeat,
                    this::isAborted, 20_000);
            if (openResult.outcome() == BossChatNavigator.OpenOutcome.NOT_CLICKED) {
                draft.detail = "未点击沟通入口：" + openResult.detail();
                draft.errorCode = "CHAT_NOT_CLICKED";
                pipeline(PipelineStage.CHAT, snapshot, "FAIL", draft.detail);
                return;
            }
            if (openResult.outcome() == BossChatNavigator.OpenOutcome.CLICKED_UNKNOWN) {
                draft.chatRequested = true;
                draft.textStatus = TextSendStatus.NOT_ATTEMPTED;
                draft.budgetAction = BudgetAction.UNKNOWN;
                draft.detail = "沟通入口已点击但聊天页未确认：" + openResult.detail();
                draft.errorCode = "CHAT_CLICK_UNKNOWN";
                pipeline(PipelineStage.CHAT, snapshot, "UNKNOWN", draft.detail);
                recordChatOpenFailure(snapshot.jobName());
                return;
            }
            draft.chatRequested = true;
            draft.chatOpened = true;
            BossChatNavigator.Chat chat = openResult.chat();
            Page chatPage = chat.page();
            pipeline(PipelineStage.CHAT, snapshot, "PASS", "沟通入口已打开");
            deliveryLedgerService.appendEvent(rowId, runId(), "CHAT_OPENED", "DOM", "已打开聊天页，准备核对会话身份");

            // 3. 会话身份核对（fail-closed）：MISMATCH 与 UNKNOWN 都不得发送文字或图片
            pipeline(PipelineStage.IDENTITY, snapshot, "RUNNING", "核对聊天会话身份");
            ChatSendConfirmer.IdentityResult identity = chatConfirmer.verifyChatIdentity(
                    chatPage, snapshot.companyName(), snapshot.jobName(), snapshot.hrName(),
                    snapshot.encryptUserId(), true);
            deliveryLedgerService.appendEvent(rowId, runId(), "CHAT_IDENTITY_CHECK",
                    identity.evidenceLevel(), identity.detail());
            if (identity.outcome() == ChatSendConfirmer.IdentityOutcome.MISMATCH) {
                draft.textStatus = TextSendStatus.FAILED;
                draft.budgetAction = BudgetAction.UNKNOWN;
                draft.errorCode = "CHAT_IDENTITY_MISMATCH";
                draft.detail = "会话身份不符，已拒绝发送：" + identity.detail();
                pipeline(PipelineStage.IDENTITY, snapshot, "FAIL", draft.detail);
                return;
            }
            if (identity.outcome() == ChatSendConfirmer.IdentityOutcome.UNKNOWN) {
                draft.textStatus = TextSendStatus.UNKNOWN;
                draft.budgetAction = BudgetAction.UNKNOWN;
                draft.errorCode = "CHAT_IDENTITY_UNKNOWN";
                draft.detail = "无法核对会话身份，已拒绝发送：" + identity.detail();
                pipeline(PipelineStage.IDENTITY, snapshot, "UNKNOWN", draft.detail);
                return;
            }
            pipeline(PipelineStage.IDENTITY, snapshot, "PASS", "会话身份核对通过");

            // 4. 填写话术
            if (isAborted()) {
                throw new RunCancelledException(abortMessage());
            }
            Locator input = chat.input().first();
            input.click();
            Object tagObj = input.evaluate("el => el.tagName.toLowerCase()");
            if (tagObj instanceof String && "textarea".equals(tagObj)) {
                input.fill(plan.message());
            } else {
                input.evaluate("(el, msg) => { el.innerText = msg; el.dispatchEvent(new Event('input')); }",
                        plan.message());
            }

            // 5. 发送按钮必须在，且发送前做最后一次取消检查
            Locator sendText = chatPage.locator("div.send-message, button[type='send'].btn-send, button.btn-send");
            if (sendText.count() == 0) {
                // 已经点击沟通过入口并进入聊天页：即使没发文字，也不能证明没建立会话
                draft.textStatus = TextSendStatus.NOT_ATTEMPTED;
                draft.budgetAction = BudgetAction.UNKNOWN;
                draft.detail = "已进入聊天页但未找到文字发送按钮";
                draft.errorCode = "SEND_BUTTON_NOT_FOUND";
                return;
            }
            if (isAborted()) {
                throw new RunCancelledException(abortMessage());
            }
            pipeline(PipelineStage.TEXT, snapshot, "RUNNING", "填写并发送招呼语");
            deliveryLedgerService.appendEvent(rowId, runId(), "TEXT_SEND_STARTED", "DOM",
                    "点击发送前最后一次取消检查通过，开始发送文字");
            ChatSendConfirmer.TextResult textResult = chatConfirmer.clickAndConfirmText(
                    chatPage, sendText.first(), plan.message(), textSendConfirmTimeoutSeconds, this::isAborted);

            if (!textResult.clickAttempted()) {
                // 基线不可用等原因导致没有点击发送按钮：文字未发送；
                // 但若已经点击过沟通入口，settleAttempt 会按"不确定"保守占额。
                draft.textStatus = TextSendStatus.NOT_ATTEMPTED;
                draft.budgetAction = BudgetAction.RELEASE;
                draft.evidenceLevel = textResult.evidenceLevel();
                draft.detail = "文字发送按钮未点击：" + textResult.detail();
                draft.errorCode = "TEXT_NOT_CLICKED";
                pipeline(PipelineStage.TEXT, snapshot, "FAIL", draft.detail);
                return;
            }
            if (textResult.confirmed()) {
                draft.textStatus = TextSendStatus.CONFIRMED;
                draft.budgetAction = BudgetAction.CONFIRM;
                pipeline(PipelineStage.TEXT, snapshot, "CONFIRM", "文字发送已确认");
            } else if (textResult.failed()) {
                draft.textStatus = TextSendStatus.FAILED;
                draft.budgetAction = BudgetAction.UNKNOWN;
                draft.errorCode = "TEXT_SEND_FAILED";
                pipeline(PipelineStage.TEXT, snapshot, "FAIL", "文字发送失败：" + textResult.detail());
            } else {
                draft.textStatus = TextSendStatus.UNKNOWN;
                draft.budgetAction = BudgetAction.UNKNOWN;
                draft.errorCode = "TEXT_SEND_UNCONFIRMED";
                pipeline(PipelineStage.TEXT, snapshot, "UNKNOWN", "文字发送结果未知：" + textResult.detail());
            }
            draft.evidenceLevel = textResult.evidenceLevel();
            draft.detail = textResult.detail();

            // 6. 图片简历：文字确认后才发，独立确认、独立落库；图片失败不回退文字状态
            if (draft.textStatus == TextSendStatus.CONFIRMED) {
                if (Boolean.TRUE.equals(config.getSendImgResume())) {
                    pipeline(PipelineStage.IMAGE, snapshot, "RUNNING", "发送图片简历");
                    draft.imageStatus = sendImageResumeTracked(chatPage, rowId,
                            ContactStateService.contactKey(snapshot.encryptUserId()), snapshot.encryptId());
                    String imageOutcome = switch (draft.imageStatus) {
                        case CONFIRMED -> "CONFIRM";
                        case FAILED -> "FAIL";
                        case UNKNOWN -> "UNKNOWN";
                        case SKIPPED -> "SKIP";
                        default -> "UNKNOWN";
                    };
                    pipeline(PipelineStage.IMAGE, snapshot, imageOutcome, "图片简历：" + draft.imageStatus);
                } else {
                    draft.imageStatus = ImageSendStatus.SKIPPED;
                    pipeline(PipelineStage.IMAGE, snapshot, "SKIP", "未开启自动发送图片简历");
                }
            }
            // 图片发送结束：图片失败不回退文字状态
        }
    }

    // ==================== 结算 ====================

    /**
     * 发送完成后一次性结算：一次单事务写入 attempt 文字/图片状态、事件、逐笔预算与聚合。
     * <p>
     * 关键约束：
     * <ul>
     *   <li>点击过沟通入口后不能再按"未发生外部动作"释放预算（A-03）；</li>
     *   <li>结算失败（事务回滚）时只记 ERROR，不重试、不伪造成功；由下次启动的
     *       reconcile 或人工核对恢复，避免半写状态。</li>
     * </ul>
     */
    private void settleAttempt(JobSnapshot snapshot, long rowId, String reservationId, DeliveryDraft draft) {
        // 点击过沟通入口 = 已产生对外动作：即使文字没点击成功，也要保守占用额度
        if (draft.budgetAction == BudgetAction.RELEASE && draft.chatRequested) {
            draft.budgetAction = BudgetAction.UNKNOWN;
            if (draft.textStatus == TextSendStatus.NOT_ATTEMPTED) {
                draft.textStatus = TextSendStatus.UNKNOWN;
            }
            if (draft.errorCode == null) {
                draft.errorCode = "CHAT_ACTION_UNCONFIRMED";
            }
        }

        DeliveryLedgerService.BudgetSettlement budgetSettlement = switch (draft.budgetAction) {
            case CONFIRM -> DeliveryLedgerService.BudgetSettlement.CONFIRM;
            case UNKNOWN -> DeliveryLedgerService.BudgetSettlement.UNKNOWN;
            case RELEASE -> DeliveryLedgerService.BudgetSettlement.RELEASE;
        };
        boolean settled;
        try {
            settled = deliveryLedgerService.settleAttempt(new DeliveryLedgerService.SettlementRequest(
                    rowId,
                    draft.textStatus, draft.evidenceLevel, draft.detail, draft.errorCode,
                    draft.imageStatus, draft.evidenceLevel, draft.detail,
                    reservationId, budgetSettlement,
                    draft.errorCode, draft.detail));
        } catch (Exception e) {
            settled = false;
            log.error("投递结算事务异常（已回滚，等待 reconcile/人工核对）| attemptRowId={} | {}", rowId, e.getMessage(), e);
        }
        if (!settled) {
            log.error("投递结算未生效（文字/图片/预算/事件可能未落库）| runId={} | attemptRowId={} | reservationId={} | action={}",
                    runId(), rowId, reservationId, draft.budgetAction);
        }

        // 岗位投递状态：只有文字确认才算已投递；未知单独标记，失败不记成功
        if (draft.textStatus == TextSendStatus.CONFIRMED) {
            updateJobStatus(snapshot, "已投递");
        } else if (draft.textStatus == TextSendStatus.UNKNOWN) {
            updateJobStatus(snapshot, "状态未知");
        } else if (draft.textStatus == TextSendStatus.FAILED) {
            updateJobStatus(snapshot, "投递失败");
        }
    }

    /** 发送图片简历并独立确认。
     * <p>
     * 第一次“立即沟通”有时只打开简化打招呼层，发完文字后该层不提供图片按钮：
     * 此时关闭简化层并重新点击“继续沟通”，等待完整聊天 DOM。
     */
    private ImageSendStatus sendImageResumeTracked(Page chatPage, long rowId,
                                                   String contactKey, String encryptId) {
        try {
            Optional<java.nio.file.Path> resumeImage = bossResumeImageService.getCurrentImagePath();
            if (resumeImage.isEmpty()) {
                log.warn("未上传图片简历，跳过图片发送；请先在 Boss 管理页面上传");
                deliveryLedgerService.appendEvent(rowId, runId(), "IMAGE_SKIPPED", "CONFIG", "未配置图片简历");
                return ImageSendStatus.SKIPPED;
            }
            // B3：同一联系人+岗位已有确认成功的图片 → 绝不重复发送同一张图片
            if (contactStateService.hasConfirmedImage(contactKey, encryptId)) {
                log.info("该联系人该岗位已有确认成功的图片简历，跳过重复发送 | contactKey={} | encryptId={}",
                        contactKey, encryptId);
                deliveryLedgerService.appendEvent(rowId, runId(), "IMAGE_SKIPPED", "DB",
                        "重复图片保护：该联系人该岗位已有 CONFIRMED 图片，本次不再发送");
                return ImageSendStatus.SKIPPED;
            }
            java.nio.file.Path imagePath = resumeImage.get();
            Locator imageInput = prepareImageUploadInput(chatPage);
            if (imageInput == null) {
                log.error("发送图片简历失败：当前聊天层和重新进入的完整聊天页中均未找到图片上传控件，页面：{}",
                        safePageUrl(chatPage));
                deliveryLedgerService.appendEvent(rowId, runId(), "IMAGE_UNKNOWN", "DOM",
                        "未找到图片上传控件，无法确认是否发送");
                return ImageSendStatus.UNKNOWN;
            }

            // 必须在完整聊天页就绪后再记录基线；否则重新进入聊天时加载出的历史图片会被误判为本次发送
            int baseline = chatConfirmer.readOwnImageMessageCount(chatPage);
            if (baseline < 0) {
                // 基线不可用（聊天根/图片节点缺失）：无法区分历史图片与本次图片，不发送、不猜测
                log.warn("图片发送基线不可用（未找到聊天记录根节点），本次不发送图片简历");
                deliveryLedgerService.appendEvent(rowId, runId(), "IMAGE_SKIPPED", "DOM",
                        "图片基线不可用，未选择文件、未发送图片");
                return ImageSendStatus.SKIPPED;
            }
            deliveryLedgerService.appendEvent(rowId, runId(), "IMAGE_SEND_STARTED", "DOM",
                    "选择图片简历：" + imagePath.getFileName() + "，基线图片数=" + baseline);
            imageInput.setInputFiles(imagePath);
            log.info("已选择图片简历，等待 Boss 确认发送：{}", imagePath.getFileName());

            ChatSendConfirmer.ImageResult result = chatConfirmer.awaitImageConfirmed(
                    chatPage, baseline, imageSendConfirmTimeoutSeconds, this::isAborted);
            if (result.confirmed()) {
                log.info("图片简历已确认出现在聊天记录中：{}", imagePath.getFileName());
                return ImageSendStatus.CONFIRMED;
            }
            if (result.uncertain()) {
                log.warn("图片简历发送结果未知：{}", result.detail());
                return ImageSendStatus.UNKNOWN;
            }
            log.error("图片简历发送失败：{}", result.detail());
            return ImageSendStatus.FAILED;
        } catch (Throwable e) {
            log.error("发送图片简历异常：{}", e.getMessage(), e);
            return ImageSendStatus.UNKNOWN;
        }
    }

    // ==================== 身份 / 去重 / 评估记录 ====================

    /**
     * 核对详情响应与当前卡片身份。
     * <p>
     * 卡片身份来自列表页「查看更多信息」链接里的 encryptId；取不到或对不上都不允许继续，
     * 不用"当前页面看起来像"代替验证。
     */
    private RuleDecision checkIdentity(JobSnapshot snapshot) {
        if (!snapshot.hasIdentity()) {
            return RuleDecision.review(RuleReason.MISSING_IDENTITY, "encryptId 缺失",
                    "详情缺少 encryptId，无法确认岗位身份");
        }
        String cardHref = readCurrentCardDetailHref();
        String cardEncryptId = BossJobDetailParser.extractEncryptIdFromHref(cardHref);
        if (cardEncryptId == null) {
            return RuleDecision.review(RuleReason.MISSING_IDENTITY, "card-href-unavailable",
                    "无法读取当前卡片详情链接，不能确认详情响应属于该岗位");
        }
        if (!cardEncryptId.equals(snapshot.encryptId())) {
            return RuleDecision.review(RuleReason.IDENTITY_MISMATCH,
                    "card=" + cardEncryptId + ", detail=" + snapshot.encryptId(),
                    "详情响应的岗位ID与当前卡片不一致");
        }
        return RuleDecision.pass();
    }

    private String readCurrentCardDetailHref() {
        try {
            Locator btn = page.locator("a.more-job-btn");
            if (btn.count() > 0) {
                return btn.first().getAttribute("href");
            }
        } catch (Exception e) {
            log.debug("读取当前卡片详情链接失败：{}", e.getMessage());
        }
        return null;
    }

    /**
     * 跟进策略：默认关闭重复沟通；开启后也要求最小间隔与最大次数，避免反复打扰同一个 HR。
     */
    private boolean followupAllowed(JobSnapshot snapshot) {
        if (!Boolean.TRUE.equals(config.getAllowRepeatChat())) {
            return false;
        }
        if (followupMaxCount <= 0 || !snapshot.hasIdentity()) {
            return false;
        }
        int count;
        LocalDateTime lastFollowup;
        LocalDateTime lastContact;
        try {
            count = deliveryLedgerService.followupCount(snapshot.encryptId());
            lastFollowup = deliveryLedgerService.lastFollowupAt(snapshot.encryptId());
            lastContact = deliveryLedgerService.lastConfirmedFirstContactAt(snapshot.encryptId());
        } catch (Exception e) {
            log.warn("读取跟进记录失败，本次按不可跟进处理：{}", e.getMessage());
            return false;
        }
        if (count >= followupMaxCount) {
            return false;
        }
        LocalDateTime reference = lastFollowup;
        if (reference == null || (lastContact != null && lastContact.isAfter(reference))) {
            reference = lastContact;
        }
        if (reference != null && reference.plusHours(Math.max(1, followupMinIntervalHours)).isAfter(LocalDateTime.now())) {
            log.info("跟进间隔未到（最近一次沟通 {}），跳过 | 岗位：{}", reference, snapshot.jobName());
            return false;
        }
        return true;
    }

    /** 写库用的规则版本标识："job-rules-v3@v<版本号>"，无快照时退回引擎版本号。 */
    private String ruleVersionTag() {
        return ruleSet == null ? JobRuleEngine.RULE_VERSION
                : JobRuleEngine.RULE_VERSION + "@v" + ruleSet.configVersion();
    }

    /** 记录一次岗位评估（规则 + AI），供排查"为什么没投" */
    private void recordEvaluation(JobSnapshot snapshot, String keyword, RuleDecision rule,
                                  AiMatchResult ai, DeliveryKind kind) {
        try {
            EvaluationEntry.Builder builder = EvaluationEntry.builder()
                    .runId(runId())
                    .sourceKeyword(keyword);
            if (ruleSet != null) {
                builder.ruleConfigVersion(ruleSet.configVersion()).rulesHash(ruleSet.sourceHash());
            }
            if (snapshot != null) {
                builder.encryptId(snapshot.encryptId())
                        .encryptUserId(snapshot.encryptUserId())
                        .jobName(snapshot.jobName())
                        .companyName(snapshot.companyName())
                        .jobUrl(snapshot.jobUrl())
                        .sourceCity(snapshot.sourceCity())
                        .snapshotHash(snapshot.snapshotHash());
            }
            if (rule != null) {
                builder.ruleVersion(ruleVersionTag())
                        .ruleDecision(rule.outcome().name())
                        .ruleReasonCode(rule.reason().name())
                        .ruleEvidence(rule.evidence());
            }
            if (ai != null) {
                builder.aiDecision(ai.outcome().name())
                        .aiErrorCode(ai.errorCode())
                        .aiAnalysis(ai.analysis())
                        .aiMessage(ai.message())
                        .aiModel(ai.model())
                        .promptTokens(ai.promptTokens())
                        .completionTokens(ai.completionTokens())
                        .durationMs(ai.durationMs());
            }
            evaluationRepository.record(builder.build());
        } catch (Exception e) {
            log.debug("岗位评估记录写入失败：{}", e.getMessage());
        }
    }

    private void recordSkippedEvaluation(String keyword, RuleReason reason, String detail) {
        try {
            evaluationRepository.record(EvaluationEntry.builder()
                    .runId(runId())
                    .sourceKeyword(keyword)
                    .ruleVersion(ruleVersionTag())
                    .ruleConfigVersion(ruleSet == null ? null : ruleSet.configVersion())
                    .rulesHash(ruleSet == null ? null : ruleSet.sourceHash())
                    .ruleDecision(RuleOutcome.REVIEW.name())
                    .ruleReasonCode(reason.name())
                    .ruleEvidence(detail)
                    .build());
        } catch (Exception e) {
            log.debug("岗位评估记录写入失败：{}", e.getMessage());
        }
    }

    private void updateJobStatus(JobSnapshot snapshot, String status) {
        if (snapshot == null || snapshot.encryptId() == null) {
            return;
        }
        try {
            bossService.updateDeliveryStatus(snapshot.encryptId(), snapshot.encryptUserId(), status);
        } catch (Exception e) {
            log.warn("更新岗位投递状态失败：{}", e.getMessage());
        }
    }

    /** 把快照转成旧表 boss_data 的实体（旧页面/统计仍然读这张表） */
    private BossJobDataEntity toEntity(JobSnapshot snapshot) {
        BossJobDataEntity entity = new BossJobDataEntity();
        entity.setSnapshotHash(snapshot.snapshotHash());
        entity.setJobName(snapshot.jobName());
        entity.setSalary(snapshot.salary());
        entity.setLocation(snapshot.city());
        entity.setExperience(snapshot.experience());
        entity.setDegree(snapshot.degree());
        entity.setJobDescription(snapshot.jobDescription());
        entity.setRecruitmentStatus(snapshot.recruitmentStatus());
        entity.setCompanyAddress(snapshot.companyAddress());
        entity.setEncryptId(snapshot.encryptId());
        entity.setEncryptUserId(snapshot.encryptUserId());
        entity.setCompanyName(snapshot.companyName());
        entity.setIndustry(snapshot.industry());
        entity.setFinancingStage(snapshot.financingStage());
        entity.setCompanyScale(snapshot.companyScale());
        entity.setHrName(snapshot.hrName());
        entity.setHrPosition(snapshot.hrPosition());
        entity.setHrActiveStatus(snapshot.hrActiveStatus());
        entity.setJobUrl(snapshot.jobUrl());
        entity.setDeliveryStatus("未投递");
        return entity;
    }

    private Job toLegacyJob(JobSnapshot snapshot) {
        Job job = new Job();
        job.setJobName(snapshot.jobName() == null ? "" : snapshot.jobName());
        job.setSalary(snapshot.salary() == null ? "" : snapshot.salary());
        job.setJobArea(String.join(", ", nonBlank(snapshot.city(), snapshot.experience(), snapshot.degree())));
        job.setCompanyName(snapshot.companyName() == null ? "" : snapshot.companyName());
        job.setRecruiter(snapshot.hrName() == null ? "" : snapshot.hrName());
        job.setJobInfo(snapshot.jobDescription() == null ? "" : snapshot.jobDescription());
        job.setHref(snapshot.jobUrl());
        return job;
    }

    private static List<String> nonBlank(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    private static String sha256Hex(String text) {
        if (text == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception digestError) {
            log.debug("计算 SHA-256 失败：{}", digestError.getMessage());
            return null;
        }
    }

    // ==================== 页面导航 ====================

    /**
     * 只带筛选条件的搜索 URL（保留给旧调用方/测试；关键词入口见 {@link #buildSearchUrl(BossConfig, String, String)}）。
     */
    public static String buildSearchUrl(BossConfig config, String cityCode) {
        return buildSearchUrl(config, cityCode, null);
    }

    /**
     * 构建 BOSS 岗位列表搜索 URL。
     * <p>
     * 参数顺序固定：city → jobType → salary → experience → degree → scale → industry → stage → query；
     * 空值与「不限」占位值（{@link Constant#UNLIMITED_CODE}）不写入；所有值由
     * {@link UriComponentsBuilder} 统一做 UTF-8 查询参数编码，中文关键词不再手写 URLEncoder。
     */
    private static String buildSearchUrl(BossConfig config, String cityCode, String keyword) {
        if (config == null) {
            return JOB_LIST_URL;
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(JOB_LIST_URL);
        appendQueryParam(builder, "city", cityCode);
        appendQueryParam(builder, "jobType", config.getJobType());
        appendListQueryParam(builder, "salary", config.getSalary());
        appendListQueryParam(builder, "experience", config.getExperience());
        appendListQueryParam(builder, "degree", config.getDegree());
        appendListQueryParam(builder, "scale", config.getScale());
        appendListQueryParam(builder, "industry", config.getIndustry());
        appendListQueryParam(builder, "stage", config.getStage());
        appendQueryParam(builder, "query", keyword);
        if (builder.build().getQueryParams().isEmpty()) {
            return JOB_LIST_URL;
        }
        return builder.build().encode(StandardCharsets.UTF_8).toUriString();
    }

    /**
     * 进入 BOSS 岗位列表页（URL 直达，或启用 UI 搜索后再补筛选参数）。
     * <p>
     * 默认直接打开拼好的列表 URL（UI 搜索在受控标签页里实测容易卡死，原因见 USE_UI_SEARCH 注释）；
     * 若显式启用 UI 搜索，则先接受“首页输入关键词”的落地结果，只在落地页缺少 config 筛选条件时
     * 再用 URL 补一次跳转——此时已经是热会话 + 同源 referer，和冷启动直闯不是一回事。
     */
    private void openJobList(String keyword, String targetUrl, String cityCode) {
        boolean landedByUiSearch = USE_UI_SEARCH && searchFromHomePage(keyword);
        if (!landedByUiSearch) {
            navigateTo(targetUrl);
        } else if (needsUrlFilters(targetUrl, cityCode)) {
            log.info("【{}】UI搜索已落地，补充筛选条件跳转：{}", keyword, targetUrl);
            navigateTo(targetUrl);
        }
        // 落地后检查是否被风控重定向到验证页
        warnIfSecurityCheck(keyword);
    }

    /**
     * 在 Boss 首页搜索框里做一次真实 UI 搜索：逐字输入关键词再点搜索。
     * <p>
     * 首页落地后会自己再跳一次，必须等页面稳定后才找输入框，否则 locator 会卡在
     * “waiting for navigation to finish”。任何一步异常都返回 false，由调用方退回 URL 跳转。
     *
     * @return 成功进入岗位列表页返回 true，任何失败返回 false
     */
    private boolean searchFromHomePage(String keyword) {
        try {
            ensureHomePageReady();
            typeKeywordIntoHomeSearch(keyword);
            submitHomeSearch();
            page.waitForURL("**/web/geek/jobs**", new Page.WaitForURLOptions().setTimeout(15_000));
            log.info("【{}】已通过首页搜索框进入岗位列表：{}", keyword, page.url());
            return true;
        } catch (Exception uiSearchError) {
            log.warn("【{}】首页UI搜索失败，退回直接跳转：{}", keyword, uiSearchError.getMessage());
            return false;
        }
    }

    /** 把页面带回首页（已在首页则原地不动），并等首页自己的客户端跳转结束。 */
    private void ensureHomePageReady() {
        String current = page.url();
        boolean alreadyHome = current != null
                && current.startsWith(BOSS_HOME_URL)
                && !current.contains("/web/geek/");
        if (!alreadyHome) {
            navigateTo(BOSS_HOME_URL);
        }
        waitForPageSettled();
    }

    /** 在首页搜索框逐字输入关键词：一次性 fill 在输入行为层面太“机器”，保留打字节奏。 */
    private void typeKeywordIntoHomeSearch(String keyword) {
        Locator input = page.locator(HOME_SEARCH_INPUT).first();
        input.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
        input.click();
        input.fill("");
        input.pressSequentially(keyword, new Locator.PressSequentiallyOptions().setDelay(140));
        PlaywrightUtil.sleep(1);
    }

    /** 提交首页搜索：优先点搜索按钮，按钮缺失时用回车兜底。 */
    private void submitHomeSearch() {
        Locator searchButton = page.locator(HOME_SEARCH_BUTTON).first();
        if (searchButton.count() > 0) {
            searchButton.click();
            return;
        }
        page.locator(HOME_SEARCH_INPUT).first().press("Enter");
    }

    /**
     * UI 搜索落地后，判断目标 URL 里是否还有 UI 搜索带不上的筛选条件。
     * <p>
     * 首页搜索只带关键词和首页当前城市，薪资/经验/学历等筛选必须再走一次 URL；
     * 除 city/query 外只要存在非空参数，就认为需要补跳转。
     */
    private boolean needsUrlFilters(String targetUrl, String cityCode) {
        String currentUrl = page.url();
        if (currentUrl == null) {
            return true;
        }
        String expectedCity = cityCode == null ? "" : cityCode.trim();
        if (!expectedCity.isEmpty() && !currentUrl.contains("city=" + expectedCity)) {
            return true;
        }
        int queryIndex = targetUrl == null ? -1 : targetUrl.indexOf('?');
        if (queryIndex < 0) {
            return false;
        }
        for (String pair : targetUrl.substring(queryIndex + 1).split("&")) {
            int equals = pair.indexOf('=');
            String paramName = equals < 0 ? pair : pair.substring(0, equals);
            String paramValue = equals < 0 ? "" : pair.substring(equals + 1);
            boolean carriedByUi = paramValue.isEmpty()
                    || "city".equals(paramName) || "query".equals(paramName);
            if (!carriedByUi) {
                return true;
            }
        }
        return false;
    }

    /**
     * 导航到目标地址，失败重试一次。
     * <p>
     * Boss 页面自己会做客户端跳转，撞上时 Playwright 报 net::ERR_ABORTED / request 失效；
     * 只要落地 URL 已经到位就不该重试或中止任务。两次都失败才把异常抛给上层。
     */
    private void navigateTo(String url) {
        Page.NavigateOptions options = new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(45_000);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                page.navigate(url, options);
                break;
            } catch (Exception navigateError) {
                if (landedOn(url)) {
                    log.debug("导航报错但页面已到位，忽略（第{}次）：{}", attempt, navigateError.getMessage());
                    break;
                }
                if (attempt >= 2) {
                    throw navigateError;
                }
                log.warn("导航失败，1秒后重试一次：{} | {}", url, navigateError.getMessage());
                PlaywrightUtil.sleep(1);
            }
        }
        // 导航结束后统一等待页面稳定，避免后续 locator 撞上被替换的 frame
        waitForPageSettled();
    }

    /** 当前页面是否已经落在目标地址的路径上（查询参数会被 Boss 改写，只比较路径前缀）。 */
    private boolean landedOn(String targetUrl) {
        if (targetUrl == null) {
            return false;
        }
        String current = safeUrlOrNull();
        if (current == null) {
            return false;
        }
        String targetPath = targetUrl.split("\\?", 2)[0];
        return current.startsWith(targetPath);
    }

    /**
     * 等页面进入 domcontentloaded，再额外给一点客户端跳转缓冲。
     * <p>
     * 不能等 LOAD/NETWORKIDLE：Boss 是 SPA + WebSocket 长连接，这两个状态可能永远不到；
     * 也必须显式带超时，否则“等不到”会变成线程无限期挂起。超时后照常往下走，
     * 后续 locator 自己还有超时。
     */
    private void waitForPageSettled() {
        Page.WaitForLoadStateOptions waitOptions = new Page.WaitForLoadStateOptions()
                .setTimeout(LOAD_STATE_TIMEOUT);
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED, waitOptions);
        } catch (Exception loadStateError) {
            log.debug("等待 domcontentloaded 超时/被打断，继续后续步骤：{}", loadStateError.getMessage());
        }
        // DOMCONTENTLOADED 之后 Boss 还会自己跳一次，给客户端跳转留出缓冲
        PlaywrightUtil.sleep(2);
    }

    /** 页面失效异常的特征片段：命中任意一个即可安全重试。 */
    private static final List<String> STALE_OBJECT_ERROR_MARKERS = List.of(
            "Object doesn't exist",
            "Execution context was destroyed");

    /** 页面正在增删 frame / 换上下文时抛出的瞬时异常，跟当前调用本身无关，重试即可。 */
    private static boolean isStaleObjectError(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        for (String marker : STALE_OBJECT_ERROR_MARKERS) {
            if (message.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 进列表页 + 等列表渲染，整体最多两轮。
     * <p>
     * 只重试一次：两轮都失败就交给上层跳过该关键词，避免一个关键词耗掉几分钟、管理台看着像卡死。
     */
    private void openJobListWithRetry(String keyword, String targetUrl, String cityCode) {
        RuntimeException firstFailure = tryOpenJobList(keyword, targetUrl, cityCode, 1);
        if (firstFailure == null) {
            return;
        }
        PlaywrightUtil.sleep(3);
        RuntimeException retryFailure = tryOpenJobList(keyword, targetUrl, cityCode, 2);
        if (retryFailure == null) {
            return;
        }
        throw retryFailure;
    }

    /** 尝试进入列表页并等列表出现；成功返回 null，失败返回异常并记录本轮原因。 */
    private RuntimeException tryOpenJobList(String keyword, String targetUrl, String cityCode, int attempt) {
        try {
            openJobList(keyword, targetUrl, cityCode);
            waitForJobList();
            return null;
        } catch (RuntimeException listError) {
            String reason = listError.getMessage() == null
                    ? listError.toString()
                    : listError.getMessage().split("\n", 2)[0];
            log.warn("【{}】进入岗位列表失败（第{}/2次）：{}", keyword, attempt, reason);
            return listError;
        }
    }

    /** 统计岗位卡片数量；元素失效/上下文切换时最多重试 3 次，最终失败按 0 处理。 */
    private int countJobCards() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            Integer count = tryCountJobCards();
            if (count != null) {
                return count;
            }
            if (attempt < 3) {
                PlaywrightUtil.sleep(1);
            }
        }
        log.warn("统计岗位数量始终失败，按 0 处理");
        return 0;
    }

    /** 单次统计卡片数；页面失效返回 null 让调用方重试，其它异常直接抛出。 */
    private Integer tryCountJobCards() {
        try {
            return page.locator(JOB_LIST_SELECTOR).count();
        } catch (Exception countError) {
            if (!isStaleObjectError(countError)) {
                throw countError;
            }
            log.debug("统计岗位数量遇到页面失效，稍后重试：{}", countError.getMessage());
            return null;
        }
    }

    /**
     * 等岗位列表容器出现，最多三轮。
     * <p>
     * 每轮 15 秒；失败后先补一次页面稳定等待再重试，三轮都不行就 dump 结构并抛异常，
     * 让上层跳过当前关键词而不是无限等。
     */
    private void waitForJobList() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (tryWaitForJobListContainer(15_000)) {
                log.info("岗位列表容器已出现（第{}次尝试）", attempt);
                return;
            }
            log.warn("等待岗位列表失败（第{}/3次），当前页面: {}", attempt, safeUrl());
            waitForPageSettled();
        }
        dumpListPageStructure();
        throw new IllegalStateException("岗位列表始终未出现（当前页面: " + safeUrl() + "）");
    }

    /** 单次等待岗位列表容器（多个候选选择器交给 Playwright 一次等任意一个先出现）。 */
    private boolean tryWaitForJobListContainer(double timeoutMillis) {
        try {
            page.waitForSelector(JOB_LIST_CONTAINERS,
                    new Page.WaitForSelectorOptions().setTimeout(timeoutMillis));
            return true;
        } catch (Exception waitError) {
            log.debug("岗位列表容器尚未出现：{}", waitError.getMessage());
            return false;
        }
    }

    /** 读当前 URL，失败时返回占位文本（只用于日志，不用于判断逻辑）。 */
    private String safeUrl() {
        try {
            return page.url();
        } catch (Exception urlError) {
            return "(取不到URL)";
        }
    }

    /** 读当前 URL，页面不可用/切换时返回 null（用于判断逻辑，避免把占位文本当 URL）。 */
    private String safeUrlOrNull() {
        try {
            return page.url();
        } catch (Exception urlError) {
            return null;
        }
    }

    /** 列表容器一个都没出现时，把页面上像列表的结构打出来，方便修选择器。 */
    private void dumpListPageStructure() {
        try {
            Object snapshot = page.evaluate("""
                    () => {
                      const summary = [];
                      for (const el of document.querySelectorAll('ul, div')) {
                        if (summary.length >= 15) break;
                        const cls = typeof el.className === 'string' ? el.className.trim() : '';
                        if (!cls || !/job|list|rec/i.test(cls)) continue;
                        summary.push(el.tagName.toLowerCase() + '.' + cls.replace(/\\s+/g, '.')
                          + ' (children=' + el.childElementCount + ')');
                      }
                      return { title: document.title, url: location.href, candidates: summary };
                    }""");
            log.warn("列表页结构快照: {}", snapshot);
        } catch (Exception dumpError) {
            log.warn("抓取列表页结构失败: {}", dumpError.getMessage());
        }
    }

    /** 落地后如果已经在安全验证页，立即进入人工验证等待，并提示管理台。 */
    private void warnIfSecurityCheck(String keyword) {
        String current = safeUrlOrNull();
        if (current == null || !isSecurityVerifyUrl(current)) {
            return;
        }
        log.warn("【{}】进入岗位列表时被跳转到验证页：{}", keyword, current);
        if (progressCallback != null) {
            progressCallback.accept("触发Boss安全校验，请手动完成验证", 0, 0);
        }
        waitForSliderVerify(page);
    }

    // ==================== 文本/薪资工具（保留给历史调用与调试） ====================

    /** BOSS 反爬字体把数字 0-9 平移成私用区 U+E8F0..U+E8F9，按偏移量即可还原。 */
    private static final char FONT_DIGIT_START = '\uE8F0';
    private static final char FONT_DIGIT_END = '\uE8F9';

    /** 薪资/数字文本还原：命中私用区数字字符时换成 ASCII 数字，其它字符原样保留。 */
    public String decodeSalary(String text) {
        char[] chars = text.toCharArray();
        for (int index = 0; index < chars.length; index++) {
            char current = chars[index];
            if (current >= FONT_DIGIT_START && current <= FONT_DIGIT_END) {
                chars[index] = (char) ('0' + (current - FONT_DIGIT_START));
            }
        }
        return new String(chars);
    }

    /** 取第一个匹配元素的文本并 trim；元素不存在或读取失败时返回空串。 */
    public String safeText(Locator root, String selector) {
        Locator node = root.locator(selector);
        try {
            if (node.count() == 0) {
                return "";
            }
            String innerText = node.innerText();
            return innerText == null ? "" : innerText.trim();
        } catch (Exception readError) {
            log.debug("读取元素文本失败 | selector={} | {}", selector, readError.getMessage());
            return "";
        }
    }

    /** 读取所有匹配元素的 innerText；失败返回空列表而不是抛异常。 */
    public List<String> safeAllText(Locator root, String selector) {
        try {
            return root.locator(selector).allInnerTexts();
        } catch (Exception readError) {
            log.debug("批量读取元素文本失败 | selector={} | {}", selector, readError.getMessage());
            return new ArrayList<>();
        }
    }

    /** 拆分 BOSS 名称里的“姓名 + 活跃状态”：第一段是姓名，其余用单空格拼回状态。 */
    public String[] splitBossName(String raw) {
        String[] tokens = raw.trim().split("\\s+");
        String bossName = tokens[0];
        String bossActive = "";
        if (tokens.length > 1) {
            bossActive = String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length));
        }
        return new String[]{bossName, bossActive};
    }

    /** 拆分“公司 · 岗位”标题；没有分隔符时岗位为空，多个分隔符只取第二段（与历史行为一致）。 */
    public String[] splitBossTitle(String raw) {
        String trimmed = raw.trim();
        String delimiter = " · ";
        int first = trimmed.indexOf(delimiter);
        if (first < 0) {
            return new String[]{trimmed, ""};
        }
        int second = trimmed.indexOf(delimiter, first + delimiter.length());
        String company = trimmed.substring(0, first);
        String job = second < 0
                ? trimmed.substring(first + delimiter.length())
                : trimmed.substring(first + delimiter.length(), second);
        return new String[]{company, job};
    }

    /** 只有非 null 且非空的字符串才视为有效。 */
    public boolean isValidString(String str) {
        return str != null && str.length() > 0;
    }

    // ==================== 图片上传控件定位（DOM） ====================

    /**
     * 获取可用的图片上传控件。
     * <p>
     * Boss 第一次“立即沟通”有时只打开简化打招呼层，发完文字后该层不会提供图片按钮：
     * 此时关掉简化层，再点【继续沟通】重新进入完整聊天 DOM。全程不等待 URL 变化，
     * 避免导航期间 Patchright 持有旧 frame 而出现 Object doesn't exist: frame@...。
     */
    private Locator prepareImageUploadInput(Page chatPage) {
        Locator attachedInput = waitForImageUploadInput(chatPage, 2);
        if (attachedInput != null) {
            log.info("当前聊天层已找到图片上传控件");
            return attachedInput;
        }

        log.info("当前打招呼层没有图片上传控件，尝试重新进入完整聊天页面");
        dismissLightweightChatPanel(chatPage);
        Locator continueButton = findVisibleChatButton(chatPage);
        if (continueButton == null) {
            log.warn("重新进入完整聊天失败：未找到【继续沟通/立即沟通】按钮，页面：{}", safePageUrl(chatPage));
            return null;
        }

        clickContinueChatButton(continueButton);
        Locator reopenedInput = waitForImageUploadInput(chatPage, 15);
        logImageUploadInputProbe(reopenedInput, "重新进入完整聊天页面后");
        return reopenedInput;
    }

    /** 探测结果只影响日志：找到说明可继续发图，找不到由调用方按失败处理。 */
    private void logImageUploadInputProbe(Locator input, String phase) {
        if (input == null) {
            log.warn("{}仍未找到图片上传控件", phase);
        } else {
            log.info("{}已找到图片上传控件", phase);
        }
    }

    /** 点击重新进入完整聊天页的按钮；点击期间页面切换属正常，交给后续探测继续。 */
    private void clickContinueChatButton(Locator chatButton) {
        try {
            String label = Optional.ofNullable(chatButton.textContent()).orElse("").trim();
            chatButton.click(new Locator.ClickOptions().setTimeout(10_000));
            log.info("已点击【{}】，等待完整聊天页面的图片控件", label.isEmpty() ? "继续沟通" : label);
        } catch (Throwable clickError) {
            // 点击可能已经触发页面切换，只是旧 frame 回包时被销毁；继续用新 Locator 探测 DOM。
            log.debug("点击沟通按钮时页面发生切换，将继续检查新聊天页面：{}", clickError.getMessage());
        }
    }

    /** 关闭不含图片控件的简化打招呼层（取第一个可见的关闭图标）。 */
    private void dismissLightweightChatPanel(Page chatPage) {
        if (closeFirstLightweightPanel(chatPage)) {
            PlaywrightUtil.sleep(1);
            log.debug("已关闭不含图片控件的简化打招呼层");
        }
    }

    /** 尝试点掉第一个可见的关闭图标；返回是否真的点到了。 */
    private boolean closeFirstLightweightPanel(Page chatPage) {
        try {
            Locator closeIcons = chatPage.locator("i.icon-close");
            for (int index = 0; index < closeIcons.count(); index++) {
                Locator closeIcon = closeIcons.nth(index);
                if (!closeIcon.isVisible()) {
                    continue;
                }
                closeIcon.click(new Locator.ClickOptions().setTimeout(5_000));
                return true;
            }
        } catch (Throwable closeError) {
            log.debug("关闭简化打招呼层失败，将直接尝试重新进入聊天：{}", closeError.getMessage());
        }
        return false;
    }

    /** 页面上可见的【继续沟通/立即沟通】按钮，没有则返回 null。 */
    private Locator findVisibleChatButton(Page chatPage) {
        try {
            for (Locator candidate : chatPage.locator("a.btn-startchat, a.op-btn-chat").all()) {
                if (candidate.isVisible()) {
                    return candidate;
                }
            }
        } catch (Throwable findError) {
            log.debug("读取沟通按钮列表失败：{}", findError.getMessage());
        }
        return null;
    }

    /** 每轮重新获取 frame 与 locator：点击沟通按钮后主 frame 可能被替换。 */
    private Locator waitForImageUploadInput(Page chatPage, int timeoutSeconds) {
        int remainingPolls = Math.max(1, timeoutSeconds);
        while (remainingPolls-- > 0) {
            Locator input = probeImageUploadInput(chatPage, remainingPolls);
            if (input == null) {
                PlaywrightUtil.sleep(1);
                continue;
            }
            return input;
        }
        return null;
    }

    /** 单轮探测图片 input；聊天页已关闭时返回 null（由上层按发送失败处理）。 */
    private Locator probeImageUploadInput(Page chatPage, int remainingPolls) {
        if (chatPage.isClosed()) {
            log.debug("等待图片上传控件时聊天页已关闭（剩余探测 {} 次）", remainingPolls);
            return null;
        }
        return findAttachedImageUploadInput(chatPage);
    }

    /**
     * 在所有 frame 里找已挂载的图片文件输入框。
     * <p>
     * 完整聊天页可能位于主 frame，也可能是子 frame；文件输入框通常 display:none，
     * 因此只要求 ATTACHED，不能要求 visible。切换中的失效 frame 直接跳过，
     * 下一轮会重新取 page.frames()。
     */
    private Locator findAttachedImageUploadInput(Page chatPage) {
        List<Frame> frames;
        try {
            frames = chatPage.frames();
        } catch (Throwable frameListError) {
            log.debug("获取聊天页 frame 列表失败，页面可能正在切换：{}", frameListError.getMessage());
            return null;
        }
        for (Frame frame : frames) {
            if (frame == null || isDetachedFrame(frame)) {
                continue;
            }
            Locator candidate = firstAttachedUploadInputInFrame(frame);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** frame 是否已失效；读不到状态按失效处理，避免对死 frame 继续操作。 */
    private boolean isDetachedFrame(Frame frame) {
        try {
            return frame.isDetached();
        } catch (Throwable detachedError) {
            return true;
        }
    }

    /** 在单个 frame 内按候选选择器找第一个已挂载的 upload input。 */
    private Locator firstAttachedUploadInputInFrame(Frame frame) {
        for (String selector : IMAGE_UPLOAD_INPUT_SELECTORS) {
            try {
                Locator candidate = frame.locator(selector).first();
                if (candidate.count() == 0) {
                    continue;
                }
                Locator.WaitForOptions attachedOptions = new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.ATTACHED)
                        .setTimeout(1_000);
                candidate.waitFor(attachedOptions);
                return candidate;
            } catch (Throwable staleFrame) {
                // 旧 frame 在页面切换中失效：继续试下一个选择器/下一帧
            }
        }
        return null;
    }

    /** 读页面 URL，页面已关闭/切换时返回占位文本（只用于日志）。 */
    private String safePageUrl(Page target) {
        try {
            return target.url();
        } catch (Throwable urlError) {
            return "无法读取";
        }
    }

    // ==================== 节奏控制 / 人工验证 ====================

    /** wait_time 没配或配得不合法时用的秒数 */
    private static final int DEFAULT_WAIT_TIME_SECONDS = 3;

    /** 解析配置里的 wait_time（秒）；缺省/非数字/非正数一律回退默认值。 */
    private int resolveWaitTimeSeconds() {
        String rawWaitTime = config == null ? null : config.getWaitTime();
        Integer parsedWaitTime = parsePositiveSeconds(rawWaitTime);
        return parsedWaitTime == null ? DEFAULT_WAIT_TIME_SECONDS : parsedWaitTime;
    }

    /** 把字符串解析成正的秒数；解析不了或 <=0 返回 null。 */
    private static Integer parsePositiveSeconds(String rawSeconds) {
        if (rawSeconds == null || rawSeconds.isBlank()) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(rawSeconds.trim());
            return seconds > 0 ? seconds : null;
        } catch (NumberFormatException invalidNumber) {
            return null;
        }
    }

    /**
     * 每处理完一个岗位后的停顿，等待期间可被停止指令打断。
     * <p>
     * 上一轮实测 7 分钟连刷 297 个岗位详情触发风控：正常模式在 [wait_time/2, wait_time]
     * 之间随机，调试模式固定用最大值，避免固定节奏本身成为特征。
     */
    private void pauseBetweenJobs() {
        int waitTime = resolveWaitTimeSeconds();
        int seconds = Boolean.TRUE.equals(config.getDebugger())
                ? waitTime
                : randomWaitWithin(waitTime);
        log.debug("岗位间停顿 {} 秒（wait_time={}，debugger={}）", seconds, waitTime, config.getDebugger());
        sleepInterruptible(seconds);
    }

    /** 在 [wait_time/2, wait_time] 区间取随机秒数；区间退化时直接返回 waitTime。 */
    private static int randomWaitWithin(int waitTime) {
        int minWait = Math.max(1, waitTime / 2);
        if (minWait >= waitTime) {
            return waitTime;
        }
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(minWait, waitTime + 1);
    }

    /** 可取消的 sleep：每 250ms 检查一次停止标记，避免停止后还要等满整段等待 */
    private void sleepInterruptible(int seconds) {
        long deadline = System.currentTimeMillis() + Math.max(0, seconds) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isAborted()) {
                throw new RunCancelledException(abortMessage());
            }
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.max(1L, Math.min(250L, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RunCancelledException("任务被中断");
            }
        }
    }

    /** 安全验证页路径标记：命中任意一个即进入人工验证等待。 */
    private static final List<String> SECURITY_VERIFY_URL_MARKERS = List.of(
            "/web/user/safe/verify-slider",
            "/web/passport/zp/verify",
            "/web/passport/zp/security",
            "security-check");

    /** BOSS 安全验证页判定：命中任意路径标记即进入人工验证等待。 */
    private static boolean isSecurityVerifyUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        for (String marker : SECURITY_VERIFY_URL_MARKERS) {
            if (url.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 人工验证等待。
     * <p>
     * 原实现用 System.in.read() 阻塞控制台，既没有真正的超时（验证分支 continue 会跳过
     * 下方的超时检查），也无法取消。现在改为：
     * <ul>
     *   <li>进入 PAUSED_VERIFICATION 状态，管理台显示原因并提供"继续任务"；</li>
     *   <li>每 5 秒检查一次页面 URL，人工直接在浏览器里拖完滑块也能自动继续；</li>
     *   <li>取消指令与截止时间每个时间片都检查；</li>
     *   <li>"继续"只代表重新检查：页面仍在验证页时重新进入暂停，并至少等待 1 秒，
     *       避免状态变成 RUNNING 后立即 RESUMED 造成忙循环、刷日志、占住执行线程。</li>
     * </ul>
     */
    private void waitForSliderVerify(Page target) {
        long deadline = System.currentTimeMillis() + Math.max(30, verificationTimeoutSeconds) * 1000L;
        boolean announced = false;
        while (isSecurityVerifyUrl(safePageUrl(target))) {
            if (runContext != null && runContext.isStopRequested()) {
                throw new RunCancelledException(abortMessage());
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("人工验证等待超时（" + verificationTimeoutSeconds + " 秒），任务放弃当前流程");
            }
            if (runContext == null) {
                throw new IllegalStateException("缺少运行上下文，无法等待人工验证");
            }
            if (!announced) {
                announced = true;
                runContext.enterVerification("触发 Boss 安全校验，请完成验证后在管理台点击「继续任务」");
                if (progressCallback != null) {
                    progressCallback.accept(
                            "触发Boss安全校验：请在浏览器中手动完成验证，然后在管理台点击「继续任务」", 0, 0);
                }
                log.warn("触发 Boss 安全校验，任务进入暂停，等待人工验证（runId={}）", runId());
            }
            BossRunContext.VerificationWait wait;
            try {
                wait = runContext.awaitVerification(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RunCancelledException("等待人工验证时任务被中断");
            }
            if (wait == BossRunContext.VerificationWait.CANCELLED) {
                throw new RunCancelledException(abortMessage());
            }
            if (wait == BossRunContext.VerificationWait.RESUMED) {
                // 只有任务仍处于 RUNNING 才继续；若已被强制复位/结束，必须立即退出，
                // 否则会在验证页上无限空转并长期占住 Playwright 专用线程。
                if (runContext.getState() != BossRunContext.State.RUNNING) {
                    throw new RunCancelledException("任务状态已终止（" + runContext.getState() + "），停止等待人工验证");
                }
                log.info("收到继续任务指令，重新检查验证页面状态（runId={}）", runId());
                if (progressCallback != null) {
                    progressCallback.accept("已继续任务，重新检查页面状态", 0, 0);
                }
                // 页面如果还在验证页，说明验证并没有真正完成：重新进入暂停并做有界等待，
                // 不能因为状态被改成 RUNNING 就无等待地回到循环顶部空转。
                if (isSecurityVerifyUrl(safePageUrl(target))) {
                    announced = false;
                    sleepCancellableMillis(1_000L);
                }
            }
            // TIMEOUT：5 秒后再查一次 URL，人工在浏览器里直接完成验证也能继续
        }
        if (runContext != null && runContext.isVerificationPaused()) {
            runContext.resumeVerification();
        }
        log.info("安全校验已通过，任务继续（runId={}）", runId());
        if (progressCallback != null) {
            progressCallback.accept("安全校验已通过，任务继续", 0, 0);
        }
    }

    /** 可取消的毫秒级等待（10ms 粒度检查停止标记），用于验证恢复的有界等待。 */
    private void sleepCancellableMillis(long millis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, millis);
        while (System.currentTimeMillis() < deadline) {
            if (isAborted()) {
                throw new RunCancelledException(abortMessage());
            }
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.max(1L, Math.min(200L, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RunCancelledException("任务被中断");
            }
        }
    }

    // ==================== 调试辅助 ====================

    /**
     * 追加保存原始 JSON 到 target/job.txt（仅在调试模式下调用，避免正常运行时大量 I/O 与隐私落盘）。
     */
    private void appendRawJson(String body) {
        java.nio.file.Path targetDir = java.nio.file.Path.of("target");
        java.nio.file.Path targetFile = targetDir.resolve("job.txt");
        try {
            java.nio.file.Files.createDirectories(targetDir);
            java.nio.file.Files.writeString(targetFile, body + System.lineSeparator() + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception writeError) {
            log.debug("写入 target/job.txt 失败：{}", writeError.getMessage());
        }
    }

    /**
     * 判断当前页是不是未登录态/403 拦截页。
     * <p>
     * 两条路径：
     * 1) 页面导航区出现“登录”入口 —— 最常见；
     * 2) 被 403 页面拦截时，页头存在且带 403 登录链接，点击它让用户重新扫码。
     * 读不到元素（页面还没加载完等）按“已登录”返回，保持调用方原有流程不变。
     */
    private boolean isLoginRequired() {
        if (hasVisibleLoginEntry()) {
            return true;
        }
        return handleForbiddenLoginPage();
    }

    /** 导航区是否出现可见的“登录”按钮。 */
    private boolean hasVisibleLoginEntry() {
        try {
            Locator loginButtons = page.locator(LOGIN_BTNS);
            if (loginButtons.count() == 0) {
                return false;
            }
            String buttonText = loginButtons.textContent();
            return buttonText != null && buttonText.contains("登录");
        } catch (Exception noLoginEntry) {
            return false;
        }
    }

    /** 403 拦截页：页头出现后点击 403 登录入口，让用户重新扫码。 */
    private boolean handleForbiddenLoginPage() {
        try {
            page.locator(PAGE_HEADER).waitFor();
            Locator forbiddenLogin = page.locator(ERROR_PAGE_LOGIN);
            if (forbiddenLogin.count() > 0) {
                forbiddenLogin.click();
            }
            return true;
        } catch (Exception notForbidden) {
            log.info("没有出现403访问异常");
            log.info("cookie有效，已登录...");
            return false;
        }
    }
}
