'use client'

import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { BiChevronLeft, BiChevronRight } from 'react-icons/bi'

/**
 * BOSS 明细页共用的小组件与文案映射。
 * 只放纯展示逻辑，数据获取放在各页面里。
 */

/** 默认每页条数（后端默认 20，最大 100） */
export const PAGE_SIZE = 20

/**
 * 状态徽章变体 class（设计系统 D.5）。
 * 全站语义不变量：同一业务状态在所有页面同色，只允许这 5 个变体。
 * 不要再引入 Tailwind 调色板里的旧色相类名（旧青色 / 旧琥珀色等）——暖色系统一后只剩这 5 种。
 */
export const BADGE_VARIANTS = {
  neutral:
    'border-hairline bg-surface-inset text-clay-600 dark:border-border dark:text-clay-500',
  accent: 'border-[hsl(var(--primary)/0.28)] bg-primary-subtle text-primary-on-subtle',
  success: 'border-success-border bg-success-bg text-success',
  warning: 'border-warning-border bg-warning-bg text-warning font-semibold',
  danger: 'border-danger-border bg-danger-bg text-danger',
  /** 过程类「拒绝」：只给文字色，不给底色（避免把真实结果语义淹掉） */
  dangerText: 'border-hairline bg-transparent text-danger',
} as const

export type BadgeVariant = keyof typeof BADGE_VARIANTS

/** 表格表头 class（设计系统 D.6，供 DataTable 复用） */
export const TH_CLASS =
  'h-8 whitespace-nowrap border-b border-border bg-card px-3 text-left text-xs font-semibold tracking-[0.06em] text-clay-600 dark:text-clay-500'

/** 表格单元格 class（设计系统 D.6） */
export const TD_CLASS = 'px-3 text-base text-ink-800 dark:text-sand-200'

/** 数字列单元格（右对齐 + 等宽数字） */
export const TD_NUM_CLASS = `${TD_CLASS} text-right tabular-nums`

const STATE_LABELS: Record<string, string> = {
  IDLE: '空闲',
  STARTING: '启动中',
  RUNNING: '运行中',
  PAUSED_VERIFICATION: '等待人工验证',
  STOPPING: '停止中',
  COMPLETED: '已完成',
  FAILED: '失败',
}

/** 任务状态中文名 */
export function stateLabel(state?: string | null): string {
  if (!state) return '未知状态'
  return STATE_LABELS[state] ?? '未知状态'
}

/** 任务状态徽章配色（映射到设计系统 D.5 的 5 个语义变体） */
export function stateBadgeClass(state?: string | null): string {
  switch (state) {
    case 'RUNNING':
    case 'STARTING':
      return BADGE_VARIANTS.accent
    case 'PAUSED_VERIFICATION':
      return BADGE_VARIANTS.warning
    case 'STOPPING':
      return BADGE_VARIANTS.neutral
    case 'COMPLETED':
      return BADGE_VARIANTS.success
    case 'FAILED':
      return BADGE_VARIANTS.danger
    default:
      return BADGE_VARIANTS.neutral
  }
}

const TEXT_STATUS_LABELS: Record<string, string> = {
  PENDING: '待确认',
  CONFIRMED: '已确认',
  FAILED: '发送失败',
  UNKNOWN: '结果未知',
  NOT_ATTEMPTED: '未尝试',
}

const IMAGE_STATUS_LABELS: Record<string, string> = {
  PENDING: '待确认',
  CONFIRMED: '已确认',
  FAILED: '发送失败',
  UNKNOWN: '结果未知',
  NOT_ATTEMPTED: '未尝试',
}

const KIND_LABELS: Record<string, string> = {
  FIRST_CONTACT: '首次沟通',
  FOLLOWUP: '跟进',
}

const MANUAL_RESOLUTION_LABELS: Record<string, string> = {
  SENT: '人工确认已发送',
  NOT_SENT: '人工确认未发送',
  STILL_UNKNOWN: '仍无法确认',
  UNKNOWN: '结果未知',
}

const RULE_DECISION_LABELS: Record<string, string> = {
  PASS: '通过',
  REJECT: '拒绝',
  REVIEW: '待复核',
  SKIP: '跳过',
}

const AI_DECISION_LABELS: Record<string, string> = {
  MATCH: '匹配',
  REJECT: '拒绝',
  REVIEW: '待复核',
  ERROR: 'AI 异常',
}

/** 文字状态中文名 */
export function textStatusLabel(status?: string | null): string {
  if (!status) return '未知状态'
  return TEXT_STATUS_LABELS[status] ?? '未知状态'
}

/** 图片状态中文名 */
export function imageStatusLabel(status?: string | null): string {
  if (!status) return '未知状态'
  return IMAGE_STATUS_LABELS[status] ?? '未知状态'
}

/** 投递类型中文名 */
export function kindLabel(kind?: string | null): string {
  if (!kind) return '未知类型'
  return KIND_LABELS[kind] ?? '未知类型'
}

/** 人工核对结论中文名 */
export function manualResolutionLabel(resolution?: string | null): string {
  if (!resolution) return '未核对'
  return MANUAL_RESOLUTION_LABELS[resolution] ?? '未知裁定'
}

// ==================== B3：联系人 / 跟进待办 ====================

const REPLY_STATE_LABELS: Record<string, string> = {
  UNKNOWN: '未标记',
  REPLIED: '已回复',
  REJECTED: '已拒绝',
  INTERVIEW: '已约面',
  NO_REPLY: '未回复',
}

const FOLLOWUP_STATUS_LABELS: Record<string, string> = {
  SUGGESTED: '建议中',
  APPROVED: '已批准（待执行）',
  REJECTED: '已拒绝',
  EXECUTED: '已执行',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
}

const PLAN_TYPE_LABELS: Record<string, string> = {
  FOLLOWUP: '跟进',
  IMAGE_RESEND: '补发图片',
}

/** 联系人回复状态中文名 */
export function replyStateLabel(state?: string | null): string {
  if (!state) return '未标记'
  return REPLY_STATE_LABELS[state] ?? '未知状态'
}

/** 联系人回复状态徽章配色 */
export function replyStateBadgeClass(state?: string | null): string {
  switch (state) {
    case 'REPLIED':
    case 'INTERVIEW':
      return BADGE_VARIANTS.success
    case 'REJECTED':
      return BADGE_VARIANTS.danger
    case 'NO_REPLY':
      return BADGE_VARIANTS.warning
    default:
      return BADGE_VARIANTS.neutral
  }
}

/** 跟进计划状态中文名 */
export function followupStatusLabel(status?: string | null): string {
  if (!status) return '未知状态'
  return FOLLOWUP_STATUS_LABELS[status] ?? status
}

/** 跟进计划状态徽章配色 */
export function followupStatusBadgeClass(status?: string | null): string {
  switch (status) {
    case 'SUGGESTED':
      return BADGE_VARIANTS.warning
    case 'APPROVED':
      return BADGE_VARIANTS.accent
    case 'REJECTED':
      return BADGE_VARIANTS.danger
    case 'EXECUTED':
      return BADGE_VARIANTS.success
    default:
      return BADGE_VARIANTS.neutral
  }
}

/** 跟进计划类型中文名 */
export function planTypeLabel(planType?: string | null): string {
  if (!planType) return '未知类型'
  return PLAN_TYPE_LABELS[planType] ?? planType
}

/** 布尔开关展示为「是/否」 */
export function yesNoLabel(value?: boolean | null): string {
  if (value === undefined || value === null) return '-'
  return value ? '是' : '否'
}

/**
 * 生成人工操作的幂等键（每次提交生成一个；后端用 idempotencyKey 去重，
 * 网络重试/双击不会重复执行同一操作）。
 */
export function newIdempotencyKey(prefix = 'manual'): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    try {
      return crypto.randomUUID()
    } catch {
      // 非安全上下文下 randomUUID 可能抛错，走兜底
    }
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
}


/** 规则结论中文名 */
export function ruleDecisionLabel(decision?: string | null): string {
  if (!decision) return '-'
  return RULE_DECISION_LABELS[decision] ?? '未知判定'
}

/** 规则原因码中文名（B2：含方向/城市/学历冲突等新原因码） */
const RULE_REASON_LABELS: Record<string, string> = {
  PASS: '规则通过',
  MISSING_IDENTITY: '缺少岗位身份',
  MISSING_JOB_CONTENT: '岗位内容缺失',
  IDENTITY_MISMATCH: '岗位身份不一致',
  MISSING_DETAIL_RESPONSE: '详情响应缺失',
  DEGREE_TOO_HIGH: '学历要求过高',
  DIRECTION_NOT_ACCEPTED: '岗位方向不符',
  CITY_NOT_ACCEPTED: '城市不符',
  CITY_BLACKLISTED: '不可接受城市',
  EDUCATION_CONFLICT: '学历表述冲突（需复核）',
  JOB_BLACKLIST: '岗位黑名单命中',
  COMPANY_BLACKLIST: '公司黑名单命中',
  RECRUITER_BLACKLIST: '招聘者黑名单命中',
  HR_INACTIVE: 'HR 不活跃',
  SALARY_NOT_EXPECTED: '薪资不符合预期',
  SALARY_UNPARSABLE: '薪资无法解析（面议）',
  CONTACT_ALREADY_GREETED: '同 HR 其他岗位已打过招呼',
  CONTACT_BLOCKED: '联系人状态禁止自动沟通',
  FOLLOWUP_NOT_APPROVED: '跟进未批准（需人工批准）',
}

export function ruleReasonLabel(reason?: string | null): string {
  if (!reason) return '-'
  return RULE_REASON_LABELS[reason] ?? reason
}

/** AI 结论中文名 */
export function aiDecisionLabel(decision?: string | null): string {
  if (!decision) return '-'
  return AI_DECISION_LABELS[decision] ?? '未知判定'
}

/** 文字状态徽章配色 */
export function textStatusBadgeClass(status?: string | null): string {
  switch (status) {
    case 'CONFIRMED':
      return BADGE_VARIANTS.success
    case 'PENDING':
      return BADGE_VARIANTS.neutral
    case 'FAILED':
      return BADGE_VARIANTS.danger
    case 'UNKNOWN':
      return BADGE_VARIANTS.warning
    default:
      return BADGE_VARIANTS.neutral
  }
}

/** 图片状态徽章配色 */
export function imageStatusBadgeClass(status?: string | null): string {
  return textStatusBadgeClass(status)
}

/**
 * 规则/AI 结论徽章配色。
 * 反直觉但刻意：过程类结论（通过 / 匹配）用 neutral —— 一页几十个过程量上色会淹掉
 * 真实结果（已投递 / 待核对）；只有「拒绝」给文字红，且不加底色。
 */
export function decisionBadgeClass(decision?: string | null): string {
  switch (decision) {
    case 'PASS':
    case 'MATCH':
      return BADGE_VARIANTS.neutral
    case 'REJECT':
      return BADGE_VARIANTS.dangerText
    case 'REVIEW':
      return BADGE_VARIANTS.warning
    case 'ERROR':
      return BADGE_VARIANTS.danger
    default:
      return BADGE_VARIANTS.neutral
  }
}

/** 通用小徽章（设计系统 D.5：4px 圆角、20px 高，不用胶囊） */
export function Pill({
  variant = 'neutral',
  className = '',
  children,
}: {
  variant?: BadgeVariant
  className?: string
  children: ReactNode
}) {
  return (
    <span
      className={`inline-flex h-5 items-center gap-1 whitespace-nowrap rounded-tag border px-1.5 text-xs font-medium tracking-[0.02em] ${BADGE_VARIANTS[variant]} ${className}`}
    >
      {children}
    </span>
  )
}

/** 时间展示：后端返回 `yyyy-MM-dd HH:mm:ss` 字符串原样展示；毫秒时间戳转本地时间 */
export function formatDateTime(value?: string | number | null): string {
  if (value === undefined || value === null || value === '') return '-'
  const date = typeof value === 'number' ? (Number.isFinite(value) && value > 0 ? new Date(value) : null) : new Date(value)
  if (!date || Number.isNaN(date.getTime())) {
    // 无法解析的字符串原样返回（后端可能已给出本地化文案），但绝不再直出 ISO 串
    return typeof value === 'string' ? value : '-'
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 毫秒耗时展示 */
export function formatDuration(value?: number | null): string {
  if (value === undefined || value === null || !Number.isFinite(value) || value < 0) return '-'
  if (value < 1000) return `${Math.round(value)} ms`
  return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)} s`
}

/** 长 ID 截断展示（保留完整值到 title） */
export function shortId(value?: string | null, head = 8, tail = 4): string {
  if (!value) return '-'
  if (value.length <= head + tail + 3) return value
  return `${value.slice(0, head)}…${value.slice(-tail)}`
}

/** 空数据行（设计系统 D.8：表格内空态左对齐，不要居中大灰字） */
export function EmptyRow({ colSpan, text = '暂无数据' }: { colSpan: number; text?: string }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-5 py-6">
        <p className="text-base font-medium text-ink-800 dark:text-sand-200">{text}</p>
      </td>
    </tr>
  )
}

/** 统一分页条：上一页 / 页码 / 下一页 */
export function Pagination({
  page,
  size,
  total,
  onChange,
  disabled = false,
}: {
  page: number
  size: number
  total: number
  onChange: (page: number) => void
  disabled?: boolean
}) {
  const totalPages = Math.max(1, Math.ceil(total / Math.max(1, size)))
  return (
    <div
      data-slot="pagination"
      className="flex h-9 items-center justify-between gap-3 border-t border-hairline px-4 text-xs text-clay-600 dark:text-clay-500"
    >
      <span className="text-muted-foreground">
        共 {total} 条 · 第 {Math.min(page, totalPages)} / {totalPages} 页 · 每页 {size} 条
      </span>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled || page <= 1}
          onClick={() => onChange(page - 1)}
        >
          <BiChevronLeft className="mr-1" />
          上一页
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled || page >= totalPages}
          onClick={() => onChange(page + 1)}
        >
          下一页
          <BiChevronRight className="ml-1" />
        </Button>
      </div>
    </div>
  )
}

/** 筛选表单的字段容器 */
export function FilterField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-1.5">
      <span className="block text-xs font-medium text-muted-foreground">{label}</span>
      {children}
    </div>
  )
}

/** 卡片内的加载/错误提示 */
export function LoadingHint({ text = '加载中…' }: { text?: string }) {
  return <p className="px-3 py-8 text-sm text-muted-foreground">{text}</p>
}

/** 卡片内的错误提示（设计系统 8.2：单行 danger 条，贴在出错区块顶部） */
export function ErrorHint({ message }: { message: string }) {
  return (
    <p
      role="alert"
      className="mx-3 my-3 rounded-control border border-danger-border border-l-2 border-l-danger bg-danger-bg px-3 py-2 text-sm text-danger"
    >
      加载失败：{message}
    </p>
  )
}

// ==================== 投递流程可视化（/boss/flow） ====================

/** 9 个投递阶段（顺序即流程图顺序，label 与后端 PipelineStage 一致） */
export const PIPELINE_STAGES: { code: string; label: string }[] = [
  { code: 'COLLECT', label: '采集岗位' },
  { code: 'RULE', label: '规则筛选' },
  { code: 'GATE', label: '准入检查' },
  { code: 'AI', label: 'AI匹配' },
  { code: 'CHAT', label: '沟通入口' },
  { code: 'IDENTITY', label: '身份核对' },
  { code: 'TEXT', label: '文字发送' },
  { code: 'IMAGE', label: '图片简历' },
  { code: 'RESULT', label: '投递结果' },
]

/** 后端阶段编码的历史别名（旧事件/合成数据里出现过），避免直出英文 */
const PIPELINE_STAGE_ALIASES: Record<string, string> = {
  RULE_FILTER: '规则筛选',
  AI_MATCH: 'AI匹配',
  ADMISSION: '准入检查',
  CHAT_ENTRY: '沟通入口',
  IDENTITY_CHECK: '身份核对',
  TEXT_SEND: '文字发送',
  IMAGE_RESUME: '图片简历',
  DELIVERY: '投递结果',
}

const PIPELINE_OUTCOME_LABELS: Record<string, string> = {
  RUNNING: '进行中',
  PASS: '通过',
  PASSED: '通过',
  MATCH: '匹配',
  REJECT: '拒绝',
  REJECTED: '拒绝',
  REVIEW: '待复核',
  SKIP: '已跳过',
  SKIPPED: '已跳过',
  UNKNOWN: '结果未知',
  FAIL: '失败',
  FAILED: '失败',
  ERROR: '错误',
  CONFIRM: '已确认',
  CONFIRMED: '已确认',
  DELIVERED: '已投递',
  PENDING: '待确认',
  NOT_ATTEMPTED: '未尝试',
  SENT: '已发送',
  SUCCESS: '成功',
  SUCCEEDED: '成功',
}

/**
 * 阶段结果中文名。
 *
 * **禁止在页面里直出 `outcome` 原始枚举**（会漏出 CONFIRMED / REJECTED 这类英文）。
 * 未知编码兜底为「未知结果」而不是原样返回 —— 宁可少显示一个词的精度，
 * 也不能让英文枚举出现在中文界面里（S4.1 P0-A）。
 */
export function pipelineOutcomeLabel(outcome?: string | null): string {
  if (!outcome) return '未知结果'
  return PIPELINE_OUTCOME_LABELS[outcome] ?? '未知结果'
}

/** 阶段结果徽章配色 */
export function pipelineOutcomeBadgeClass(outcome?: string | null): string {
  switch (outcome) {
    case 'CONFIRM':
    case 'CONFIRMED':
    case 'DELIVERED':
    case 'SENT':
      return BADGE_VARIANTS.success
    case 'PASS':
    case 'PASSED':
    case 'MATCH':
      return BADGE_VARIANTS.neutral
    case 'RUNNING':
      return BADGE_VARIANTS.accent
    case 'REVIEW':
    case 'PENDING':
      return BADGE_VARIANTS.warning
    case 'REJECT':
    case 'REJECTED':
      return BADGE_VARIANTS.dangerText
    case 'FAIL':
    case 'FAILED':
    case 'ERROR':
      return BADGE_VARIANTS.danger
    default:
      return BADGE_VARIANTS.neutral
  }
}

/**
 * `/boss` 专用：把「已确认」降为中性。
 *
 * 理由（色相预算）：`/boss` 已经占用了陶土（运行中）+ 芥末赭（待核对）两个色相，
 * 若「已确认」再上橄榄绿，加上「拒绝」的红，「同屏有彩色相」会到 4 个，超过上限。
 * 更实际的原因：已确认是每行都有的常态，一旦染色，整张表变成一条绿带，
 * 反而把真正需要看的「拒绝 / 结果未知」淹掉 —— 与「文字确认成功不染色」同一条原则。
 * 其它页面仍用 `pipelineOutcomeBadgeClass`（那里彩色代表真实结果，是有意义的）。
 */
export function pipelineOutcomeBadgeClassQuiet(outcome?: string | null): string {
  switch (outcome) {
    case 'CONFIRM':
    case 'CONFIRMED':
    case 'DELIVERED':
    case 'SENT':
      return 'border-transparent bg-transparent text-clay-600 dark:text-clay-500'
    default:
      return pipelineOutcomeBadgeClass(outcome)
  }
}

/** 阶段中文名（优先用后端 label，回退本地映射；未知编码不直出英文） */
export function pipelineStageLabel(code?: string | null, label?: string | null): string {
  if (label) return label
  if (!code) return '未知阶段'
  const known = PIPELINE_STAGES.find((item) => item.code === code)
  if (known) return known.label
  return PIPELINE_STAGE_ALIASES[code] ?? '未知阶段'
}
