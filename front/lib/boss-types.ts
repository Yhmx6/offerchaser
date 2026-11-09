/**
 * 阶段 B · B1：BOSS 管理台共享类型。
 *
 * 字段名与《阶段B-B1-实施契约》第 2 节冻结的 JSON 契约保持一致，
 * 不允许在页面里另起一套字段名；后端字段缺失时页面按可选值兜底。
 */

/** 任务状态（与后端 BossRunContext.State 对齐） */
export type BossState =
  | 'IDLE'
  | 'STARTING'
  | 'RUNNING'
  | 'PAUSED_VERIFICATION'
  | 'STOPPING'
  | 'COMPLETED'
  | 'FAILED'

/** 文字发送状态 */
export type TextStatus = 'PENDING' | 'CONFIRMED' | 'FAILED' | 'UNKNOWN' | 'NOT_ATTEMPTED'

/** 图片简历发送状态 */
export type ImageStatus = 'NOT_ATTEMPTED' | 'PENDING' | 'CONFIRMED' | 'FAILED' | 'UNKNOWN'

/** 投递尝试类型：首次沟通 / 跟进 */
export type AttemptKind = 'FIRST_CONTACT' | 'FOLLOWUP'

/** 人工核对结论 */
export type ManualResolution = 'SENT' | 'NOT_SENT' | 'STILL_UNKNOWN'

/** 单个动作（首次沟通 / 跟进）的今日预算汇总 */
export interface BudgetActionSummary {
  /** 预算上限；-1 表示不限 */
  limit?: number
  consumed?: number
  reserved?: number
  uncertain?: number
  /** consumed + reserved + uncertain */
  used?: number
  /** 剩余额度；-1 表示不限 */
  remaining?: number
  pendingReservations?: number
  error?: string
}

/** 今日预算汇总（BudgetService.todaySummary） */
export interface BudgetSummary {
  date?: string
  account?: string
  enabled?: boolean
  timezone?: string
  firstContact?: BudgetActionSummary
  followup?: BudgetActionSummary
  pendingReservations?: number
  error?: string
}

/** 今日投递台账汇总（DeliveryLedgerService.todaySummary） */
export interface DeliverySummary {
  date?: string
  account?: string
  todayStart?: string
  attempts?: number
  textPending?: number
  textConfirmed?: number
  textFailed?: number
  textUnknown?: number
  imageConfirmed?: number
  imageFailed?: number
  imageUnknown?: number
  firstContactConfirmed?: number
  followupConfirmed?: number
  error?: string
}

/** 今日评估汇总（EvaluationRepository.summaryToday） */
export interface EvaluationSummary {
  date?: string
  total?: number
  rulePass?: number
  ruleReject?: number
  ruleReview?: number
  aiMatch?: number
  aiReject?: number
  aiReview?: number
  aiError?: number
  error?: string
}

/** /api/boss/delivery/summary 的 data 结构 */
export interface DeliveryOverallSummary {
  budget?: BudgetSummary
  attempts?: DeliverySummary
  evaluations?: EvaluationSummary
}

/** /api/boss/status 返回的运行状态（原始状态 map，无 data 外壳） */
export interface BossStatus {
  platform?: string
  state?: BossState | string
  runId?: string | null
  workerExited?: boolean
  workerExitHint?: string | null
  canStart?: boolean
  isRunning?: boolean
  isLoggedIn?: boolean
  stopRequested?: boolean
  verificationPaused?: boolean
  verificationReason?: string | null
  stopReason?: string | null
  error?: string | null
  startedAt?: number | null
  runningSeconds?: number
  deliveredCount?: number
  followupCount?: number
  ruleRejectedCount?: number
  skippedCount?: number
  unknownCount?: number
  budget?: BudgetSummary
  delivery?: DeliverySummary
  /** UNKNOWN 且尚未人工核对的首次沟通尝试数 */
  pendingResolutions?: number
  /** 投递流程快照（9 阶段汇总 + 最近流水）；无任务/无数据时可为 null */
  pipeline?: PipelineSnapshot | null
}

/** 统一分页响应 */
export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

/** /api/boss/runs 列表项 */
export interface RunItem {
  runId: string
  platform?: string | null
  state?: string | null
  stopReason?: string | null
  error?: string | null
  startedAt?: string | null
  endedAt?: string | null
  updatedAt?: string | null
  /** 本轮任务使用的方向模板 id（旧记录为 null） */
  templateId?: number | null
  /** 本轮任务使用的方向模板名称（旧记录为 null） */
  templateName?: string | null
  /** 本轮任务使用的方向模板方向（旧记录为 null） */
  templateDirection?: string | null
}

/** /api/boss/evaluations 列表项 */
export interface EvaluationItem {
  id: number
  runId?: string | null
  encryptId?: string | null
  encryptUserId?: string | null
  jobName?: string | null
  companyName?: string | null
  jobUrl?: string | null
  sourceKeyword?: string | null
  sourceCity?: string | null
  snapshotHash?: string | null
  ruleVersion?: string | null
  ruleDecision?: string | null
  ruleReasonCode?: string | null
  ruleEvidence?: string | null
  aiDecision?: string | null
  aiErrorCode?: string | null
  aiAnalysis?: string | null
  aiMessage?: string | null
  aiModel?: string | null
  promptTokens?: number | null
  completionTokens?: number | null
  durationMs?: number | null
  /** B2：产生该评估的规则配置版本（旧数据为 null） */
  ruleConfigVersion?: number | null
  /** B2：规则 JSON 的 sha256 摘要 */
  rulesHash?: string | null
  createdAt?: string | null
}

/** /api/boss/attempts 列表项 */
export interface AttemptItem {
  attemptId: string
  runId?: string | null
  kind?: string | null
  encryptId?: string | null
  encryptUserId?: string | null
  jobName?: string | null
  companyName?: string | null
  jobUrl?: string | null
  textStatus?: string | null
  imageStatus?: string | null
  textEvidence?: string | null
  imageEvidence?: string | null
  errorCode?: string | null
  errorDetail?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  version?: number | null
  manualResolution?: string | null
  resolvedAt?: string | null
  resolveReason?: string | null
  /** B3：联系人键（uid:<encryptUserId>，无可信 ID 时为 null） */
  contactKey?: string | null
  /** B3：图片补发尝试指向的原 attemptId（普通投递为 null） */
  parentAttemptId?: string | null
}

/** 投递证据事件 */
export interface AttemptEvent {
  eventType?: string | null
  evidenceLevel?: string | null
  detail?: string | null
  occurredAt?: string | null
}

/** /api/boss/attempts/{attemptId} 的 data 结构 */
export interface AttemptDetail {
  attempt: AttemptItem
  events?: AttemptEvent[]
  evaluation?: EvaluationItem | null
}

/** 人工核对请求体 */
export interface ResolveRequest {
  resolution: ManualResolution
  reason: string
  expectedVersion: number
  idempotencyKey?: string
}

/** 人工核对响应 data */
export interface ResolveResult {
  attemptId: string
  textStatus?: string | null
  imageStatus?: string | null
  version?: number | null
  manualResolution?: string | null
  resolvedAt?: string | null
  changed?: boolean
  idempotent?: boolean
}

/** SSE progress 事件（JobProgressMessage + B1/B5+ 新增字段） */
export interface SseProgress {
  platform?: string
  type?: string
  message?: string
  current?: number | null
  total?: number | null
  timestamp?: number
  runId?: string | null
  /** 服务实例内单调递增序号，用于去重/丢弃乱序事件 */
  seq?: number
  state?: BossState | string
  // ========== type="pipeline" 时携带的投递流程字段 ==========
  /** 阶段代码：COLLECT/RULE/AI/GATE/CHAT/IDENTITY/TEXT/IMAGE/RESULT */
  stage?: string
  /** 阶段中文名 */
  stageLabel?: string
  jobName?: string | null
  companyName?: string | null
  /** 阶段结果：RUNNING/PASS/REJECT/REVIEW/SKIP/UNKNOWN/FAIL/CONFIRM/DELIVERED/ERROR */
  outcome?: string
  /** 给人看的原因/细节 */
  detail?: string | null
}

/** SSE snapshot 事件（连接/重连时后端推送的状态快照） */
export type SseSnapshot = BossStatus

// ==================== 投递流程可视化（/api/boss/status.pipeline 与 SSE type="pipeline"） ====================

/** 投递流程阶段代码（顺序即流程顺序） */
export type PipelineStageCode =
  | 'COLLECT'
  | 'RULE'
  | 'AI'
  | 'GATE'
  | 'CHAT'
  | 'IDENTITY'
  | 'TEXT'
  | 'IMAGE'
  | 'RESULT'

/** 阶段结果 */
export type PipelineOutcome =
  | 'RUNNING'
  | 'PASS'
  | 'REJECT'
  | 'REVIEW'
  | 'SKIP'
  | 'UNKNOWN'
  | 'FAIL'
  | 'ERROR'
  | 'CONFIRM'
  | 'DELIVERED'

/** 单条投递流程事件（PipelineEvent） */
export interface PipelineEvent {
  timestamp: number
  stage: string
  stageLabel?: string | null
  jobName?: string | null
  companyName?: string | null
  outcome?: string | null
  detail?: string | null
}

/** 单阶段汇总（PipelineStageStat） */
export interface PipelineStageStat {
  stage: string
  label?: string | null
  entered?: number
  outcomes?: Record<string, number> | null
}

/** 投递流程快照（PipelineSnapshot，GET /api/boss/status.pipeline） */
export interface PipelineSnapshot {
  runId?: string | null
  currentJob?: string | null
  currentCompany?: string | null
  currentStage?: string | null
  currentStageLabel?: string | null
  updatedAt?: number | null
  stages?: PipelineStageStat[]
  recent?: PipelineEvent[]
}

/** 任务记录查询参数 */
export interface RunsQuery {
  status?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

/** 评估记录查询参数 */
export interface EvaluationsQuery {
  runId?: string
  ruleDecision?: string
  aiDecision?: string
  reasonCode?: string
  encryptId?: string
  keyword?: string
  from?: string
  to?: string
  /** B2：true 时每个 encryptId 只返回最新一条评估 */
  latestOnly?: boolean
  /** B2：按规则配置版本过滤 */
  ruleConfigVersion?: number
  page?: number
  size?: number
}

/** 投递明细查询参数 */
export interface AttemptsQuery {
  runId?: string
  kind?: string
  textStatus?: string
  imageStatus?: string
  encryptId?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

// ==================== 阶段 B · B2：可配置规则 ====================

/** 期望薪资过滤策略（与后端 SalaryStrategy 枚举一致） */
export type SalaryStrategy = 'INTERSECT' | 'FLOOR'

/** 黑名单匹配模式（与后端 BlacklistMatchMode 枚举一致） */
export type BlacklistMatchMode = 'CONTAINS' | 'EXACT'

/** 规则预览结论 */
export type PreviewDecision = 'PASS' | 'REJECT' | 'REVIEW'

/**
 * 可配置规则内容（RulePayload，契约第 4 节）。
 * 字段与后端 DTO 一一对应；列表字段空数组表示不限。
 */
export interface RulePayload {
  /** 用户最高学历：不限/高中/中专/大专/本科/硕士/博士 */
  highestDegree?: string | null
  /** INTERSECT | FLOOR */
  salaryStrategy?: string | null
  /** 期望月薪下限（K/月） */
  expectedSalaryMin?: number | null
  /** 期望月薪上限（K/月） */
  expectedSalaryMax?: number | null
  /** 可接受岗位方向关键词（已废弃；前端固定发送空数组，保留字段兼容旧快照） */
  jobDirections?: string[] | null
  /** 旧可接受城市（后端仍兼容；前端不再写入，固定发送空数组以清除旧过滤） */
  cities?: string[] | null
  /** 不可接受城市（空=不限；岗位城市命中任一则拒绝） */
  blacklistCities?: string[] | null
  /** 保留字段（已废弃；当前快照无工作类型，前端固定发送空数组） */
  workTypes?: string[] | null
  /** CONTAINS | EXACT */
  blacklistMatchMode?: string | null
  blacklistJob?: string[] | null
  blacklistCompany?: string[] | null
  blacklistRecruiter?: string[] | null
  whitelist?: string[] | null
  /** HR 活跃过滤开关（语义同 BossConfig.filterDeadHr） */
  filterDeadHr?: boolean | null
  /** 视为不活跃的 HR 状态（每行一个） */
  deadStatus?: string[] | null
}

/** 单个规则版本（RuleSetView） */
export interface RuleSetView {
  version: number
  sourceHash?: string | null
  createdAt?: string | null
  note?: string | null
  rules: RulePayload
}

/** GET /api/boss/rules 的 data */
export interface RulesOverview {
  active?: RuleSetView | null
  liveDefaults?: RulePayload | null
  versions?: RuleSetView[]
}

/** 规则预览样例（Sample） */
export interface PreviewSample {
  jobName?: string
  companyName?: string
  salary?: string
  degree?: string
  experience?: string
  city?: string
  jd?: string
  hrName?: string
  hrActiveStatus?: string
  encryptId?: string
}

/** 规则预览结果（PreviewResult） */
export interface PreviewResult {
  index?: number
  jobName?: string | null
  decision?: string | null
  /** 原因码（RuleReason 枚举名，如 DEGREE_TOO_HIGH） */
  reason?: string | null
  evidence?: string | null
  detail?: string | null
  salaryNormalized?: string | null
}

/** 用当前规则重评请求（RecheckRequest） */
export interface RecheckRequest {
  ruleVersion?: number
  runId?: string
  encryptIds?: string[]
  limit?: number
}

/** 用当前规则重评结果（RecheckResult） */
export interface RecheckResult {
  ruleVersion?: number
  evaluated?: number
  passed?: number
  rejected?: number
  reviewed?: number
}

// ==================== 阶段 B · B3：联系人去重与跟进闭环 ====================

/** 联系人回复状态（与后端枚举字符串一致） */
export type ReplyState = 'UNKNOWN' | 'REPLIED' | 'REJECTED' | 'INTERVIEW' | 'NO_REPLY'

/** 联系人状态（contact_state 行，接口字段为 camelCase） */
export interface ContactState {
  id?: number
  account?: string
  platform?: string
  /** 联系人键 "uid:<encryptUserId>"；无可信 ID 不落表 */
  contactKey: string
  encryptUserId?: string | null
  hrName?: string | null
  companyName?: string | null
  replyState?: string | null
  manualTakeover?: boolean
  stopContact?: boolean
  lastContactAt?: string | null
  lastReplyAt?: string | null
  version?: number
  createdAt?: string | null
  updatedAt?: string | null
}

/** GET /api/boss/contacts 分页 data */
export type ContactPage = PageResult<ContactState>

/** GET /api/boss/contacts/{contactKey} 的 data：联系人状态 + 最近投递尝试（最多 20 条） */
export interface ContactDetail {
  contact: ContactState
  attempts: AttemptItem[]
}

/** PUT /api/boss/contacts/{contactKey}/state 请求体 */
export interface ContactStateRequest {
  replyState: string
  manualTakeover: boolean
  stopContact: boolean
  reason: string
  expectedVersion: number
  idempotencyKey?: string
}

/** 联系人列表查询参数 */
export interface ContactsQuery {
  replyState?: string
  manualTakeover?: boolean
  stopContact?: boolean
  page?: number
  size?: number
}

/** 跟进计划类型 */
export type FollowupPlanType = 'FOLLOWUP' | 'IMAGE_RESEND'

/** 跟进计划状态 */
export type FollowupStatus = 'SUGGESTED' | 'APPROVED' | 'REJECTED' | 'EXECUTED' | 'CANCELLED' | 'EXPIRED'

/** 跟进待办计划（followup_plan 行，接口字段为 camelCase） */
/** 首页「跟进待办」：已经发生有效双向沟通的联系人 + 最近岗位信息。 */
export interface EffectiveFollowup {
  contactKey: string
  hrName?: string | null
  companyName?: string | null
  replyState?: string | null
  lastReplyAt?: string | null
  lastContactAt?: string | null
  attemptId?: string | null
  encryptId?: string | null
  imageStatus?: string | null
  jobName?: string | null
  jobUrl?: string | null
  salary?: string | null
  city?: string | null
  experience?: string | null
  degree?: string | null
  industry?: string | null
  financingStage?: string | null
  companyScale?: string | null
}

export interface EffectiveFollowupPage {
  items: EffectiveFollowup[]
  total: number
  limit?: number
}

export interface FollowupPlan {
  id?: number
  planId: string
  account?: string
  platform?: string
  encryptId?: string | null
  encryptUserId?: string | null
  contactKey?: string | null
  jobName?: string | null
  companyName?: string | null
  planType?: string | null
  status?: string | null
  reason?: string | null
  parentAttemptId?: string | null
  version?: number
  suggestedAt?: string | null
  decidedAt?: string | null
  executedAt?: string | null
  executedAttemptId?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** GET /api/boss/followups 分页 data */
export type FollowupPage = PageResult<FollowupPlan>

/** 跟进待办查询参数 */
export interface FollowupsQuery {
  status?: string
  page?: number
  size?: number
}

/** 批准 / 拒绝跟进计划的请求体 */
export interface FollowupDecisionRequest {
  expectedVersion: number
  reason: string
  idempotencyKey?: string
}

/** POST /api/boss/followups/generate 的 data */
export interface GenerateFollowupsResult {
  created?: number
}

/** POST /api/boss/attempts/{attemptId}/resend-image 请求体 */
export interface ImageResendRequest {
  reason: string
}

/** 图片补发计划（响应为跟进计划对象） */
export type ImageResendPlan = FollowupPlan

// ==================== 阶段 B · B4：采集与统计看板 ====================

/** 概览日期范围 */
export interface MetricsRange {
  from?: string
  to?: string
  days?: number
}

/** 规则统计（每个 (run_id, encrypt_id) 最新一条评估） */
export interface RuleMetrics {
  pass?: number
  reject?: number
  review?: number
  total?: number
  passRate?: number
}

/** AI 统计 */
export interface AiMetrics {
  match?: number
  reject?: number
  error?: number
  unset?: number
  total?: number
}

/** 首次沟通动作统计 */
export interface FirstContactMetrics {
  attempts?: number
  chatRequested?: number
  textConfirmed?: number
  textFailed?: number
  textUnknown?: number
  textPending?: number
}

/** 图片发送统计（total = 非 NOT_ATTEMPTED） */
export interface ImageMetrics {
  confirmed?: number
  failed?: number
  unknown?: number
  skipped?: number
  total?: number
}

/** 联系人回复统计 */
export interface ReplyMetrics {
  contacts?: number
  contacted?: number
  replied?: number
  interview?: number
  replyRate?: number
  manualTakeover?: number
  stopContact?: number
}

/** 跟进计划统计 */
export interface FollowupMetrics {
  suggested?: number
  approved?: number
  rejected?: number
  executed?: number
  cancelled?: number
  approvedPending?: number
}

/** 今日预算统计（daily_budget 指定日） */
export interface BudgetMetrics {
  date?: string
  limit?: number
  consumed?: number
  reserved?: number
  remaining?: number
  uncertain?: number
}

/** 采集来源统计 */
export interface CollectionMetrics {
  sourceRecords?: number
  keywords?: number
  uniqueJobs?: number
}

/** legacy / 未验证统计 */
export interface LegacyMetrics {
  deliveredUnverified?: number
}

/** overview data.metrics 主体 */
export interface MetricsBody {
  newJobs?: number
  legacyNewJobs?: number
  rule?: RuleMetrics
  ai?: AiMetrics
  firstContact?: FirstContactMetrics
  /** 文字确认成功的唯一 attempt 数（不含 boss_data 旧「已投递」） */
  textConfirmed?: number
  image?: ImageMetrics
  replies?: ReplyMetrics
  followups?: FollowupMetrics
  budget?: BudgetMetrics
  collection?: CollectionMetrics
  hrActive?: Record<string, number>
  legacy?: LegacyMetrics
}

/** 数据质量提示 */
export interface MetricsDataQuality {
  unknownText?: number
  legacyDelivered?: number
  firstSeenMissing?: number
  notes?: string[]
}

/** 下钻链接（后端已带当前范围参数） */
export interface MetricsLinks {
  evaluations?: string
  attempts?: string
  contacts?: string
  followups?: string
  collection?: string
}

/** GET /api/boss/metrics/overview 的 data */
export interface MetricsOverview {
  timezone?: string
  range?: MetricsRange
  metrics?: MetricsBody
  dataQuality?: MetricsDataQuality
  /** 每个指标的口径说明（key → 中文口径） */
  definitions?: Record<string, string>
  links?: MetricsLinks
}

/** 趋势指标枚举 */
export type MetricsSeriesMetric =
  | 'newJobs'
  | 'rulePass'
  | 'ruleReject'
  | 'textConfirmed'
  | 'firstContactAttempts'
  | 'replies'
  | 'followupsExecuted'

/** 趋势点 */
export interface MetricsPoint {
  date: string
  value: number
}

/** GET /api/boss/metrics/series 的 data */
export interface MetricsSeries {
  metric?: string
  timezone?: string
  points?: MetricsPoint[]
}

/** 按关键词汇总采集来源 */
export interface CollectionKeywordItem {
  keyword?: string | null
  sourceRecords?: number
  uniqueJobs?: number
  firstDiscoveredAt?: string | null
  lastDiscoveredAt?: string | null
}

/** 采集按日序列 */
export interface CollectionDailyItem {
  date: string
  newJobs?: number
  sourceRecords?: number
}

/** GET /api/boss/metrics/collection 的 data */
export interface MetricsCollection {
  timezone?: string
  keywords?: CollectionKeywordItem[]
  daily?: CollectionDailyItem[]
}

/** 概览查询参数（YYYY-MM-DD，含端点） */
export interface MetricsQuery {
  from: string
  to: string
  runId?: string
}

/** D2：一次投递状态同步（三类统计：自动/手动/HR主动招呼）。 */
export interface StatusSyncRun {
  syncId: string
  triggerType: 'ACTIVE' | 'STARTUP' | string
  scope: 'TODAY' | 'PREVIOUS_DAY' | string
  targetDate: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | string
  startedAt: string
  finishedAt?: string | null
  autoTotal: number
  autoRead: number
  autoReplied: number
  autoUnread: number
  autoUnknown: number
  manualTotal: number
  manualRead: number
  manualReplied: number
  manualUnread: number
  manualUnknown: number
  hrTotal: number
  hrRead: number
  hrReplied: number
  hrUnread: number
  hrUnknown: number
  error?: string | null
}

/** GET /api/boss/status-sync/summary */
export interface StatusSyncSummary {
  today: string
  previousDay: string
  active?: StatusSyncRun | null
  startup?: StatusSyncRun | null
  latest?: StatusSyncRun | null
  recent?: StatusSyncRun[]
}

/** POST /api/boss/status-sync */
export interface StatusSyncResult {
  run: StatusSyncRun
  snapshotCount: number
  /** D-02：本次同步回写为 REPLIED 的联系人数 */
  replyStateSynced?: number
  skipped: boolean
}

/** 趋势查询参数 */
export interface MetricsSeriesQuery {
  from: string
  to: string
  metric: MetricsSeriesMetric
}

// ==================== 方向模板（GET /api/boss/templates） ====================

/**
 * D1：模板级采集策略（只影响采集，不包含跟进）。
 * - 留空/null：使用全局默认（application.yaml 的 collect-* 配置）
 * - 数值 0：该维度不限制
 * 每日首次沟通上限由全局预算统一控制，所有模板共享，因此不在这里。
 */
export interface TemplateDeliveryPolicy {
  collectMaxPerKeyword?: number | null
  collectMaxDurationMinutes?: number | null
  collectNoNewWindowSeconds?: number | null
}

/**
 * 方向模板列表项（TemplateView）。
 * 模板保存一整套配置：BOSS 搜索/投递开关、AI 介绍与提示词、筛选规则、图片简历。
 */
export interface TemplateView {
  id: number
  name: string
  direction?: string | null
  description?: string | null
  /** 是否为当前生效模板（激活后下一轮任务按它运行） */
  active?: boolean
  updatedAt?: string | null
  keywords?: string | null
  expectedSalaryMin?: number | null
  expectedSalaryMax?: number | null
  hasResume?: boolean
  /** D1：模板级采集策略（未配置为 null） */
  deliveryPolicy?: TemplateDeliveryPolicy | null
}

/** GET /api/boss/templates 的 data */
export interface TemplateList {
  items: TemplateView[]
  active?: TemplateView | null
}

/** POST /api/boss/templates/{id}/activate 的 data */
export interface TemplateActivateResult {
  template: TemplateView
  warnings?: string[]
}

/**
 * GET /api/boss/templates/{id} 的 data（配置中心编辑器回填用）。
 * bossConfig 已解析为对象；rule 为 RulePayload；aiIntroduce/aiPrompt 为模板内 AI 配置。
 */
export interface TemplateDetail {
  template: TemplateView
  bossConfig?: TemplateBossConfig | null
  rule?: RulePayload | null
  aiIntroduce?: string | null
  aiPrompt?: string | null
  /** D1：模板级采集策略 */
  deliveryPolicy?: TemplateDeliveryPolicy | null
  resumeFile?: string | null
}

/** 创建模板请求体（从当前配置快照） */
export interface CreateTemplateRequest {
  name: string
  direction?: string
  description?: string
}

/** 更新模板元信息请求体 */
export interface UpdateTemplateRequest {
  name?: string
  direction?: string
  description?: string
}

/** 复制模板请求体 */
export interface DuplicateTemplateRequest {
  name?: string
}

/** 删除模板响应 data */
export interface DeleteTemplateResult {
  deleted?: boolean
}

// ==================== 配置中心：模板整包配置 / 选项 ====================

/**
 * 模板里保存的 BOSS 配置快照（BossConfigEntity，camelCase）。
 * 列表/JSON 型字段在 DB 里是字符串：keywords 为 JSON 数组字符串，
 * industry/experience/degree/salary/scale/stage 为 `[中文名,中文名]` 括号列表。
 */
export interface TemplateBossConfig {
  id?: number
  debugger?: number | null
  waitTime?: number | null
  keywords?: string | null
  cityCode?: string | null
  industry?: string | null
  jobType?: string | null
  experience?: string | null
  degree?: string | null
  salary?: string | null
  scale?: string | null
  stage?: string | null
  sayHi?: string | null
  expectedSalaryMin?: number | null
  expectedSalaryMax?: number | null
  enableAi?: number | null
  sendImgResume?: number | null
  allowRepeatChat?: number | null
  repeatChatMessages?: string | null
  filterDeadHr?: number | null
  deadStatus?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** PUT /api/boss/templates/{id}/config 请求体（整包保存；null 表示不改，空数组表示清空） */
export interface TemplateConfigRequest {
  keywords?: string[] | null
  cityCode?: string | null
  jobType?: string | null
  industry?: string[] | null
  experience?: string[] | null
  degree?: string[] | null
  salary?: string[] | null
  scale?: string[] | null
  stage?: string[] | null
  expectedSalaryMin?: number | null
  expectedSalaryMax?: number | null
  enableAI?: boolean | null
  sendImgResume?: boolean | null
  allowRepeatChat?: boolean | null
  filterDeadHr?: boolean | null
  deadStatus?: string | null
  sayHi?: string | null
  repeatChatMessages?: string | null
  waitTime?: number | null
  rule?: RulePayload | null
  aiIntroduce?: string | null
  aiPrompt?: string | null
  /** D1：模板级采集策略（字段 null=保持当前值；0=不限制） */
  deliveryPolicy?: TemplateDeliveryPolicy | null
}

/** PUT /api/boss/templates/{id}/config 的 data */
export interface TemplateConfigResult {
  template: TemplateView
  /** true = 模板当前生效，保存后已立即应用到运行配置 */
  applied?: boolean
  warnings?: string[]
}

/** BOSS 选项（GET /api/boss/config/options/{type}，原始数组） */
export interface BossOption {
  id?: number
  type?: string
  name: string
  code: string
  sortOrder?: number | null
}

/** 选项类型 */
export type BossOptionType =
  | 'city'
  | 'industry'
  | 'experience'
  | 'jobType'
  | 'salary'
  | 'degree'
  | 'scale'
  | 'stage'

// ==================== D3：人工放行队列（仅 REVIEW 岗位） ====================

/** 人工放行状态 */
export type ManualReleaseStatus = 'PENDING' | 'APPROVED' | 'FAILED' | 'CONSUMED' | 'REJECTED'

/** 人工放行动作 */
export type ManualReleaseAction = 'APPROVE' | 'REJECT'

/** GET /api/boss/manual-releases 的队列项 */
export interface ManualReleaseItem {
  evaluationId: number
  releaseId?: string | null
  releaseStatus?: string | null
  statusLabel?: string | null
  jobName?: string | null
  companyName?: string | null
  jobUrl?: string | null
  ruleDecision?: string | null
  ruleReasonCode?: string | null
  ruleReason?: string | null
  aiDecision?: string | null
  aiErrorCode?: string | null
  aiAnalysis?: string | null
  aiMessage?: string | null
  createdAt?: string | null
  reason?: string | null
  error?: string | null
  attemptId?: string | null
  requestedAt?: string | null
  consumedAt?: string | null
}

/** GET /api/boss/manual-releases 的 data */
export interface ManualReleaseQueue {
  items: ManualReleaseItem[]
  /** 下一轮最多执行的放行条数（后端 ManualReleaseService.MAX_RELEASES_PER_RUN） */
  maxPerRun?: number
  /** 各状态数量（后端 counts；未知 key 忽略） */
  counts?: Partial<Record<ManualReleaseStatus | 'ALL', number>>
}

/** POST /api/boss/manual-releases 请求体 */
export interface ManualReleaseDecision {
  evaluationId: number
  action: ManualReleaseAction
  /** 必填，≤500 字 */
  reason: string
}

/** POST /api/boss/manual-releases 成功响应（字段以后端为准，均可选） */
export interface ManualReleaseDecisionResult {
  evaluationId?: number
  releaseId?: string | null
  releaseStatus?: string | null
  statusLabel?: string | null
  message?: string
  [key: string]: unknown
}


