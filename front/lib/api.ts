import type {
  AttemptDetail,
  AttemptsQuery,
  BossStatus,
  ContactDetail,
  ContactPage,
  ContactState,
  ContactStateRequest,
  ContactsQuery,
  CreateTemplateRequest,
  DeleteTemplateResult,
  DeliveryOverallSummary,
  DuplicateTemplateRequest,
  EvaluationsQuery,
  FollowupDecisionRequest,
  FollowupPage,
  FollowupPlan,
  FollowupsQuery,
  EffectiveFollowupPage,
  GenerateFollowupsResult,
  ImageResendPlan,
  ImageResendRequest,
  MetricsCollection,
  MetricsOverview,
  MetricsQuery,
  MetricsSeries,
  MetricsSeriesQuery,
  PageResult,
  PreviewResult,
  PreviewSample,
  RecheckRequest,
  RecheckResult,
  ResolveRequest,
  ResolveResult,
  RulePayload,
  RulesOverview,
  RuleSetView,
  RunsQuery,
  RunItem,
  EvaluationItem,
  AttemptItem,
  TemplateActivateResult,
  TemplateConfigRequest,
  TemplateConfigResult,
  TemplateDetail,
  TemplateList,
  TemplateView,
  UpdateTemplateRequest,
  BossOption,
  BossOptionType,
  ManualReleaseDecision,
  ManualReleaseDecisionResult,
  ManualReleaseQueue,
  ManualReleaseStatus,
  StatusSyncResult,
  StatusSyncSummary,
} from './boss-types'
import type { JobInsightResult } from './job-insight-types'
import type { AiDraftRequest, AiDraftResult, ResumeAnalysis } from './ai-template-types'
import { API_BASE, apiUrl } from './api-base'
import { AuthRequiredError, dispatchAuthRequired } from './auth'

// 保持既有调用方 `import { API_BASE, apiUrl } from '@/lib/api'` 兼容
export { API_BASE, apiUrl }

/**
 * 统一的后端错误。
 *
 * 后端错误响应形如 {@code {success:false,message,code?,currentVersion?}}；
 * 这里把 HTTP 状态码、业务 code 与原始响应体都保留下来，页面可以针对
 * 409 VERSION_CONFLICT / RUN_ID_MISMATCH 等做专门提示。
 */
export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly body: unknown

  constructor(status: number, message: string, code?: string, body?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.body = body
  }
}

export type QueryValue = string | number | boolean | null | undefined

/** 把查询参数对象拼成 `?a=1&b=2`（跳过空值） */
export function buildQuery(params: Record<string, QueryValue>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    search.set(key, String(value))
  }
  const query = search.toString()
  return query ? `?${query}` : ''
}

export type RequestOptions = Omit<RequestInit, 'body'> & { body?: unknown }

/**
 * 统一请求封装：
 * - JSON 请求/响应；
 * - 统一携带浏览器 Cookie（credentials: 'include'）与 CSRF 头 `X-Requested-With: offerchaser`；
 * - 401（未登录/会话过期）或 403 CSRF_HEADER_MISSING：派发 `offerchaser:auth-required` 事件，
 *   并抛 {@link AuthRequiredError}，AuthGate 据此自动弹出登录层；
 * - 其余非 2xx、`success:false`、网络异常统一抛 {@link ApiError}；
 * - 只读接口响应外壳 `{success:true,data:...}` 自动解出 `data`；
 *   旧接口（status/start/stop 等）没有 data 外壳时原样返回。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, headers, ...rest } = options
  const requestHeaders = new Headers(headers)
  requestHeaders.set('Accept', 'application/json')
  // B5：写操作/API 客户端标识（后端 AuthInterceptor 用它做 CSRF 防御）
  if (!requestHeaders.has('X-Requested-With')) {
    requestHeaders.set('X-Requested-With', 'offerchaser')
  }

  let requestBody: BodyInit | undefined
  if (body !== undefined && body !== null) {
    if (
      typeof body === 'string' ||
      body instanceof FormData ||
      body instanceof Blob ||
      body instanceof URLSearchParams
    ) {
      requestBody = body
    } else {
      requestHeaders.set('Content-Type', 'application/json')
      requestBody = JSON.stringify(body)
    }
  }

  let response: Response
  try {
    response = await fetch(apiUrl(path), {
      // B5：本机会话使用 HttpOnly Cookie，必须携带凭据（CORS 已 allowCredentials）
      credentials: 'include',
      ...rest,
      headers: requestHeaders,
      body: requestBody,
    })
  } catch (error) {
    throw new ApiError(0, `无法连接后端服务（${API_BASE}），请确认服务已启动`, 'NETWORK_ERROR', error)
  }

  const text = await response.text()
  let payload: unknown = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = text
    }
  }

  const record =
    payload !== null && typeof payload === 'object' && !Array.isArray(payload)
      ? (payload as Record<string, unknown>)
      : null

  const message =
    typeof record?.message === 'string' && record.message
      ? record.message
      : `请求失败（HTTP ${response.status}）`
  const code = typeof record?.code === 'string' ? record.code : undefined

  // B5：未登录/会话过期（401）或缺少 CSRF 头（403）时统一通知 AuthGate 弹登录层
  const authRequired = response.status === 401 || (response.status === 403 && code === 'CSRF_HEADER_MISSING')
  if (authRequired) {
    dispatchAuthRequired({
      status: response.status,
      code,
      message,
      reason: response.status === 403 ? 'csrf' : 'expired',
    })
    throw new AuthRequiredError(message, response.status, code)
  }

  if (!response.ok) {
    throw new ApiError(response.status, message, code, payload)
  }

  if (record && record.success === false) {
    throw new ApiError(response.status, message, code, payload)
  }

  if (record && Object.prototype.hasOwnProperty.call(record, 'data')) {
    return record.data as T
  }
  return payload as T
}

// ==================== 能力声明 ====================

export interface Capabilities {
  enabledPlatforms: string[]
  features?: Record<string, boolean>
}

/** GET /api/capabilities：读取当前实例启用的平台与能力位 */
export function getCapabilities(): Promise<Capabilities> {
  return request<Capabilities>('/api/capabilities')
}

// ==================== BOSS 运行状态与操作 ====================

/** GET /api/boss/status：当前运行状态（原始状态 map，无 data 外壳） */
export function getBossStatus(): Promise<BossStatus> {
  return request<BossStatus>('/api/boss/status')
}

/** GET /api/boss/delivery/summary：今日预算 / 投递台账 / 评估汇总 */
export function getDeliverySummary(): Promise<DeliveryOverallSummary> {
  return request<DeliveryOverallSummary>('/api/boss/delivery/summary')
}

/** D2：主动读取今天到目前的投递/已读/回复情况。 */
export function runDeliveryStatusSync(): Promise<StatusSyncResult> {
  return request<StatusSyncResult>('/api/boss/status-sync', { method: 'POST' })
}

/** D2：最近同步摘要（今日主动同步 + 昨天启动补读）。 */
export function getDeliveryStatusSyncSummary(): Promise<StatusSyncSummary> {
  return request<StatusSyncSummary>('/api/boss/status-sync/summary')
}

/** POST /api/boss/start：开始投递 */
export function startBoss(): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>('/api/boss/start', { method: 'POST' })
}

/** POST /api/boss/stop?runId=：停止投递（可绑定 runId，任务已切换时后端返回 409） */
export function stopBoss(runId?: string | null): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>(`/api/boss/stop${buildQuery({ runId })}`, { method: 'POST' })
}

/** POST /api/boss/verification/resume：恢复因人工验证暂停的任务 */
export function resumeVerification(runId?: string | null): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>('/api/boss/verification/resume', {
    method: 'POST',
    body: runId ? { runId } : undefined,
  })
}

/** POST /api/boss/force-reset?confirm=true&runId=：强制复位卡死任务 */
export function forceReset(runId?: string | null): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>(
    `/api/boss/force-reset${buildQuery({ confirm: 'true', runId })}`,
    { method: 'POST' }
  )
}

// ==================== B1 只读记录接口 ====================

/** GET /api/boss/runs：任务运行记录（分页） */
export function getRuns(params: RunsQuery = {}): Promise<PageResult<RunItem>> {
  return request<PageResult<RunItem>>(`/api/boss/runs${buildQuery({ ...params })}`)
}

/** GET /api/boss/evaluations：岗位评估记录（分页） */
export function getEvaluations(params: EvaluationsQuery = {}): Promise<PageResult<EvaluationItem>> {
  return request<PageResult<EvaluationItem>>(`/api/boss/evaluations${buildQuery({ ...params })}`)
}

/** GET /api/boss/attempts：投递明细（分页） */
export function getAttempts(params: AttemptsQuery = {}): Promise<PageResult<AttemptItem>> {
  return request<PageResult<AttemptItem>>(`/api/boss/attempts${buildQuery({ ...params })}`)
}

/** GET /api/boss/attempts/{attemptId}：单条投递详情（attempt + 事件 + 关联评估） */
export function getAttempt(attemptId: string): Promise<AttemptDetail> {
  return request<AttemptDetail>(`/api/boss/attempts/${encodeURIComponent(attemptId)}`)
}

/** POST /api/boss/attempts/{attemptId}/resolve：人工核对未知结果 */
export function resolveAttempt(attemptId: string, body: ResolveRequest): Promise<ResolveResult> {
  return request<ResolveResult>(`/api/boss/attempts/${encodeURIComponent(attemptId)}/resolve`, {
    method: 'POST',
    body,
  })
}

// ==================== B2 可配置规则接口 ====================

/** GET /api/boss/rules：当前生效规则 + 实时配置默认值 + 版本列表 */
export function getRules(): Promise<RulesOverview> {
  return request<RulesOverview>('/api/boss/rules')
}

/**
 * PUT /api/boss/rules：保存为新版本。
 * 必须携带当前生效版本号 expectedVersion；版本冲突时后端返回 409 RULE_VERSION_CONFLICT。
 */
export function saveRules(payload: RulePayload, expectedVersion: number, note?: string): Promise<RuleSetView> {
  return request<RuleSetView>('/api/boss/rules', {
    method: 'PUT',
    body: {
      expectedVersion,
      note: note && note.trim() ? note.trim() : undefined,
      rules: payload,
    },
  })
}

/** GET /api/boss/rules/versions?limit=：规则版本历史（version DESC） */
export function getRuleVersions(limit = 20): Promise<RuleSetView[]> {
  return request<RuleSetView[]>(`/api/boss/rules/versions${buildQuery({ limit })}`)
}

/**
 * POST /api/boss/rules/preview：用给定规则（为空则用当前生效规则）预览样例判定。
 * 零发送、不写评估表。
 */
export function previewRules(samples: PreviewSample[], rules?: RulePayload | null): Promise<PreviewResult[]> {
  return request<PreviewResult[]>('/api/boss/rules/preview', {
    method: 'POST',
    body: rules ? { rules, samples } : { samples },
  })
}

/**
 * POST /api/boss/evaluations/recheck：用当前规则重评岗位。
 * 只新增评估记录，绝不发送消息、不改投递状态。
 */
export function recheckEvaluations(body: RecheckRequest = {}): Promise<RecheckResult> {
  return request<RecheckResult>('/api/boss/evaluations/recheck', {
    method: 'POST',
    body,
  })
}

// ==================== B3 联系人 / 跟进待办 / 图片补发 ====================

/** GET /api/boss/contacts：联系人状态分页列表 */
export function getContacts(params: ContactsQuery = {}): Promise<ContactPage> {
  return request<ContactPage>(`/api/boss/contacts${buildQuery({ ...params })}`)
}

/**
 * GET /api/boss/contacts/{contactKey}：联系人状态 + 最近投递尝试（最多 20 条）。
 * 返回体做容错：兼容 {contact,attempts} / {state,attempts} 两种包裹。
 */
export async function getContact(contactKey: string): Promise<ContactDetail> {
  const raw = await request<Record<string, unknown>>(`/api/boss/contacts/${encodeURIComponent(contactKey)}`)
  const record = raw !== null && typeof raw === 'object' ? raw : {}
  const attemptsRaw = Array.isArray(record.attempts)
    ? record.attempts
    : Array.isArray(record.recentAttempts)
      ? record.recentAttempts
      : []
  const contactRaw =
    record.contact !== null && typeof record.contact === 'object'
      ? record.contact
      : record.state !== null && typeof record.state === 'object'
        ? record.state
        : record.contactState !== null && typeof record.contactState === 'object'
          ? record.contactState
          : record.contactKey
            ? record
            : { contactKey }
  return {
    contact: contactRaw as unknown as ContactState,
    attempts: attemptsRaw as unknown as AttemptItem[],
  }
}

/** PUT /api/boss/contacts/{contactKey}/state：人工标记联系人状态（带 expectedVersion 并发校验） */
export function putContactState(contactKey: string, body: ContactStateRequest): Promise<ContactState> {
  return request<ContactState>(`/api/boss/contacts/${encodeURIComponent(contactKey)}/state`, {
    method: 'PUT',
    body,
  })
}

/** GET /api/boss/followups：跟进待办分页列表 */
export function getFollowups(params: FollowupsQuery = {}): Promise<FollowupPage> {
  return request<FollowupPage>(`/api/boss/followups${buildQuery({ ...params })}`)
}

/** GET /api/boss/followups/effective：已回复/面试中的有效沟通跟进（带岗位信息）。 */
export function getEffectiveFollowups(limit = 20): Promise<EffectiveFollowupPage> {
  return request<EffectiveFollowupPage>(`/api/boss/followups/effective?limit=${limit}`)
}

/** POST /api/boss/followups/generate：生成建议跟进（只创建 SUGGESTED，不发送） */
export function generateFollowups(limit = 50): Promise<GenerateFollowupsResult> {
  return request<GenerateFollowupsResult>('/api/boss/followups/generate', {
    method: 'POST',
    body: { limit },
  })
}

/** POST /api/boss/followups/{planId}/approve：批准跟进（只允许下一轮任务执行，不立即发送） */
export function approveFollowup(planId: string, body: FollowupDecisionRequest): Promise<FollowupPlan> {
  return request<FollowupPlan>(`/api/boss/followups/${encodeURIComponent(planId)}/approve`, {
    method: 'POST',
    body,
  })
}

/** POST /api/boss/followups/{planId}/reject：拒绝跟进建议 */
export function rejectFollowup(planId: string, body: FollowupDecisionRequest): Promise<FollowupPlan> {
  return request<FollowupPlan>(`/api/boss/followups/${encodeURIComponent(planId)}/reject`, {
    method: 'POST',
    body,
  })
}

/** POST /api/boss/attempts/{attemptId}/resend-image：为「文字已确认 + 图片失败/未知」的投递生成补发图片计划 */
export function createImageResendPlan(attemptId: string, body: ImageResendRequest): Promise<ImageResendPlan> {
  return request<ImageResendPlan>(`/api/boss/attempts/${encodeURIComponent(attemptId)}/resend-image`, {
    method: 'POST',
    body,
  })
}

// ==================== B4 采集与统计看板接口 ====================

/**
 * GET /api/boss/metrics/overview：统计概览（口径/数据质量/下钻链接随响应返回）。
 * from/to 为 YYYY-MM-DD（含端点）；非法范围后端返回 400 INVALID_RANGE。
 */
export function getMetricsOverview(params: MetricsQuery): Promise<MetricsOverview> {
  return request<MetricsOverview>(
    `/api/boss/metrics/overview${buildQuery({ from: params.from, to: params.to, runId: params.runId })}`
  )
}

/** GET /api/boss/metrics/series：单指标按日趋势（无数据日期补 0） */
export function getMetricsSeries(params: MetricsSeriesQuery): Promise<MetricsSeries> {
  return request<MetricsSeries>(
    `/api/boss/metrics/series${buildQuery({ from: params.from, to: params.to, metric: params.metric })}`
  )
}

/** GET /api/boss/metrics/collection：采集来源统计（按关键词 + 按日） */
export function getMetricsCollection(params: { from: string; to: string }): Promise<MetricsCollection> {
  return request<MetricsCollection>(
    `/api/boss/metrics/collection${buildQuery({ from: params.from, to: params.to })}`
  )
}

// ==================== 方向模板接口（/api/boss/templates） ====================

/** GET /api/boss/templates：模板列表 + 当前生效模板 */
export function getTemplates(): Promise<TemplateList> {
  return request<TemplateList>('/api/boss/templates')
}

/** D1：保存模板拖拽后的顺序；order 必须包含全部模板 id。 */
export function reorderTemplates(order: number[]): Promise<TemplateView[]> {
  return request<TemplateView[]>('/api/boss/templates/order', {
    method: 'PUT',
    body: { order },
  })
}

/** GET /api/boss/templates/{id}：模板完整快照（BOSS 配置/AI/规则 JSON，详情/调试用） */
export function getTemplateDetail(id: number): Promise<TemplateDetail> {
  return request<TemplateDetail>(`/api/boss/templates/${id}`)
}

/** POST /api/boss/templates：从当前配置创建模板快照 */
export function createTemplate(body: CreateTemplateRequest): Promise<TemplateView> {
  return request<TemplateView>('/api/boss/templates', { method: 'POST', body })
}

/** PUT /api/boss/templates/{id}：更新模板名称/方向/描述（不改配置快照） */
export function updateTemplate(id: number, body: UpdateTemplateRequest): Promise<TemplateView> {
  return request<TemplateView>(`/api/boss/templates/${id}`, { method: 'PUT', body })
}

/**
 * POST /api/boss/templates/{id}/activate：把模板配置应用到当前配置（下一轮任务生效）。
 * 返回 {template, warnings}；warnings 为部分配置未能应用时的提示。
 */
export function activateTemplate(id: number): Promise<TemplateActivateResult> {
  return request<TemplateActivateResult>(`/api/boss/templates/${id}/activate`, { method: 'POST' })
}

/** POST /api/boss/templates/{id}/capture：用当前配置覆盖模板快照 */
export function captureTemplate(id: number): Promise<TemplateView> {
  return request<TemplateView>(`/api/boss/templates/${id}/capture`, { method: 'POST' })
}

/** POST /api/boss/templates/{id}/duplicate：复制模板 */
export function duplicateTemplate(id: number, body: DuplicateTemplateRequest = {}): Promise<TemplateView> {
  return request<TemplateView>(`/api/boss/templates/${id}/duplicate`, { method: 'POST', body })
}

/** DELETE /api/boss/templates/{id}：删除模板（生效模板不可删，409 TEMPLATE_CONFLICT） */
export function deleteTemplate(id: number): Promise<DeleteTemplateResult> {
  return request<DeleteTemplateResult>(`/api/boss/templates/${id}`, { method: 'DELETE' })
}

/**
 * POST /api/boss/templates/{id}/resume-image：上传模板独立图片简历（multipart，字段名 file）。
 * 走统一 request 封装：自动带 Cookie/CSRF 头，Content-Type 由浏览器按 FormData 生成。
 */
export function uploadTemplateResume(id: number, file: File): Promise<TemplateView> {
  const form = new FormData()
  form.append('file', file)
  return request<TemplateView>(`/api/boss/templates/${id}/resume-image`, {
    method: 'POST',
    body: form,
  })
}

/**
 * 模板图片简历预览地址（GET /{id}/resume-image/content）。
 * cacheBust 用模板 updatedAt，避免浏览器缓存旧图。
 */
export function templateResumeImageUrl(id: number, cacheBust?: string | number | null): string {
  const suffix = cacheBust ? `?v=${encodeURIComponent(String(cacheBust))}` : ''
  return apiUrl(`/api/boss/templates/${id}/resume-image/content${suffix}`)
}

// ==================== 配置中心：模板整包配置 + BOSS 选项 ====================

/**
 * PUT /api/boss/templates/{id}/config：在配置中心内整包保存模板配置
 * （搜索条件/薪资/公司要求/黑名单/筛选规则/AI 配置）。
 * active 模板保存后后端会立即应用到运行配置（applied=true）。
 */
export function updateTemplateConfig(id: number, body: TemplateConfigRequest): Promise<TemplateConfigResult> {
  return request<TemplateConfigResult>(`/api/boss/templates/${id}/config`, {
    method: 'PUT',
    body,
  })
}

/**
 * GET /api/boss/config/options/{type}：按类型读取 BOSS 选项（city/industry/experience/
 * jobType/salary/degree/scale/stage）。后端返回原始数组 [{code,name,sortOrder?}]。
 */
export function getBossOptions(type: BossOptionType): Promise<BossOption[]> {
  return request<BossOption[]>(`/api/boss/config/options/${encodeURIComponent(type)}`)
}

// ==================== D3：人工放行队列（仅 REVIEW 岗位） ====================

/**
 * GET /api/boss/manual-releases?status=：人工放行队列 + 各状态计数。
 * status 省略时由后端决定默认（通常 PENDING）；只读接口。
 */
export function getManualReleases(status?: ManualReleaseStatus | 'ALL'): Promise<ManualReleaseQueue> {
  return request<ManualReleaseQueue>(`/api/boss/manual-releases${buildQuery({ status })}`)
}

/**
 * POST /api/boss/manual-releases：批准/拒绝一个人工放行项。
 * 批准只加入放行队列，下一轮任务才执行（不是立即发送）；理由必填。
 * 409 表示已批准/已消费等队列状态冲突。
 */
export function decideManualRelease(body: ManualReleaseDecision): Promise<ManualReleaseDecisionResult> {
  return request<ManualReleaseDecisionResult>('/api/boss/manual-releases', {
    method: 'POST',
    body,
  })
}

// ==================== AI 帮我建模板（简历分析 / 草稿生成） ====================

/**
 * POST /api/boss/resume/analyze：上传简历图片（multipart file）做视觉解析。
 * 只读分析：后端不保存图片与文本；失败码 AI_CONFIG_MISSING / INVALID_IMAGE /
 * VISION_PARSE_FAILED / RESUME_ANALYZE_FAILED 由调用方展示并引导粘贴文本兜底。
 */
export function analyzeResume(file: File, supplement?: string): Promise<ResumeAnalysis> {
  const form = new FormData()
  form.append('file', file)
  const extra = supplement?.trim()
  if (extra) form.append('supplement', extra)
  return request<ResumeAnalysis>('/api/boss/resume/analyze', {
    method: 'POST',
    body: form,
  })
}

/**
 * POST /api/boss/resume/analyze-text：粘贴简历文本解析（视觉不可用时的兜底）。
 */
export function analyzeResumeText(text: string, supplement?: string): Promise<ResumeAnalysis> {
  const extra = supplement?.trim()
  return request<ResumeAnalysis>('/api/boss/resume/analyze-text', {
    method: 'POST',
    body: extra ? { text, supplement: extra } : { text },
  })
}

/**
 * POST /api/boss/jobs/insight：读取 BOSS 岗位链接并生成只读解读。
 * 只会打开岗位页，不发送消息、不投递、不改变联系人状态。
 */
export function analyzeJobLink(url: string): Promise<JobInsightResult> {
  return request<JobInsightResult>('/api/boss/jobs/insight', {
    method: 'POST',
    body: { url },
  })
}

/**
 * POST /api/boss/templates/ai-draft：根据已确认画像与问答生成模板草稿。
 * 只生成草稿（不创建/不激活模板）；保存仍由前端调 createTemplate + updateTemplateConfig。
 */
export function generateAiTemplateDraft(body: AiDraftRequest): Promise<AiDraftResult> {
  return request<AiDraftResult>('/api/boss/templates/ai-draft', {
    method: 'POST',
    body,
  })
}

/** POST /api/playwright/focus：把自动化 BOSS Chrome 窗口置前。 */
export function focusBossWindow(): Promise<{ success?: boolean; message?: string }> {
  return request<{ success?: boolean; message?: string }>('/api/playwright/focus', { method: 'POST' })
}

// ==================== B5：裸 fetch 兼容垫片 ====================

let fetchDefaultsInstalled = false

/**
 * 给「未走 request 封装的历史页面」的裸 fetch 补上 B5 需要的两个默认值：
 * - `credentials: 'include'`：本机会话是 HttpOnly Cookie，不带凭据会 401；
 * - `X-Requested-With: offerchaser`：写操作要求 CSRF 头，否则 403 CSRF_HEADER_MISSING。
 *
 * 仅对发往后端 API_BASE 的请求生效（SSE/静态资源/CDN 不受影响）；
 * 只设置缺省值，调用方显式传入的 init 优先。模块加载时自动安装一次。
 */
export function installApiFetchDefaults(): void {
  if (typeof window === 'undefined' || fetchDefaultsInstalled) return
  fetchDefaultsInstalled = true
  const originalFetch = window.fetch.bind(window)
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    try {
      const url =
        typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
      if (url && url.startsWith(API_BASE)) {
        const nextInit: RequestInit = { ...(init ?? {}) }
        if (!nextInit.credentials) nextInit.credentials = 'include'
        const headers = new Headers(nextInit.headers)
        if (!headers.has('X-Requested-With')) headers.set('X-Requested-With', 'offerchaser')
        nextInit.headers = headers
        const response = await originalFetch(input, nextInit)
        // 历史页面的裸 fetch 也要能触发重新登录（排除登录/状态/登出接口，避免登录失败弹出循环）
        if (!url.includes('/api/session/') && (response.status === 401 || response.status === 403)) {
          if (response.status === 401) {
            dispatchAuthRequired({
              status: 401,
              reason: 'expired',
              message: '会话已过期或尚未登录',
            })
          } else {
            try {
              const bodyText = await response.clone().text()
              if (bodyText.includes('CSRF_HEADER_MISSING')) {
                dispatchAuthRequired({
                  status: 403,
                  code: 'CSRF_HEADER_MISSING',
                  reason: 'csrf',
                  message: '写操作缺少认证标识',
                })
              }
            } catch {
              // 读取响应体失败时忽略（原始响应交给调用方处理）
            }
          }
        }
        return response
      }
    } catch {
      // 解析失败时按原始请求处理
    }
    return originalFetch(input, init)
  }
}

// 客户端模块加载即安装（服务端/构建期自动跳过）
installApiFetchDefaults()
