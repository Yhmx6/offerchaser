'use client'

import Link from 'next/link'
import { useEffect, useMemo, useState } from 'react'
import { ArrowRight, Loader2, Play, RefreshCw } from 'lucide-react'

import { useSharedBossStatus } from '@/app/components/shell/BossStatusProvider'
import { useSpotlight } from '@/lib/use-spotlight'
import { DialogShell } from '@/components/ui/dialog-shell'
import { StatusSyncCard } from '@/app/components/boss/StatusSyncCard'
import { JobInsightCard } from '@/app/components/JobInsightCard'
import { PIPELINE_STAGES, formatDateTime, pipelineOutcomeLabel, stateLabel } from '@/app/components/boss-shared'
import { focusBossWindow, getAttempts, getContacts, getEffectiveFollowups, getTemplates } from '@/lib/api'
import type { BudgetActionSummary, PipelineEvent, PipelineStageStat, TemplateView } from '@/lib/boss-types'

/**
 * 今日（/boss）：数字卡 + 9 阶段管线 + 事件流 + 预算环 + 聊天状态同步。
 */

function formatClock(timestamp?: number | null): string {
  if (typeof timestamp !== 'number' || !Number.isFinite(timestamp) || timestamp <= 0) return '—'
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
}

function budgetNumber(value?: number | null): string {
  if (value === undefined || value === null) return '—'
  return value < 0 ? '不限' : String(value)
}

function outcomeTone(outcome?: string | null): string {
  if (outcome === 'CONFIRMED' || outcome === 'PASS' || outcome === 'DELIVERED') return 'var(--v3-ok)'
  if (outcome === 'UNKNOWN' || outcome === 'REVIEW' || outcome === 'PENDING') return 'var(--v3-warn)'
  if (outcome === 'FAILED' || outcome === 'REJECT') return 'var(--v3-bad)'
  return 'var(--v3-muted)'
}

function SurfaceCard({
  title,
  action,
  children,
  className = '',
}: {
  title?: string
  action?: React.ReactNode
  children: React.ReactNode
  className?: string
}) {
  const onSpot = useSpotlight()
  return (
    <section
      onPointerMove={onSpot}
      className={`oc-card oc-spot rounded-[20px] border bg-[var(--surface)] p-5 shadow-[var(--shadow)] ${className}`}
      style={{ borderColor: 'var(--v3-line)' }}
    >
      {title || action ? (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
          {title ? <h2 className="text-[15px] font-semibold">{title}</h2> : <span />}
          {action}
        </div>
      ) : null}
      {children}
    </section>
  )
}

function NumberCard({
  label,
  value,
  hint,
  onClick,
  tone = 'normal',
}: {
  label: string
  value: number | string
  hint: string
  onClick: () => void
  tone?: 'normal' | 'warn'
}) {
  const onSpot = useSpotlight()
  return (
    <button
      type="button"
      onPointerMove={onSpot}
      onClick={onClick}
      className="oc-card oc-kpi oc-spot group rounded-[18px] border bg-[var(--surface)] p-5 text-left shadow-[var(--shadow)] transition-transform duration-300 hover:-translate-y-0.5" data-tone={tone === 'warn' ? 'warn' : 'primary'}
      style={{ borderColor: tone === 'warn' ? 'var(--v3-warn)' : 'var(--v3-line)' }}
    >
      <p className="text-xs text-[var(--v3-muted)]">{label}</p>
      <p
        className="oc-kpi__value mt-2 font-serif text-[42px] leading-none tabular-nums tracking-[-0.035em]"
        style={{ color: tone === 'warn' ? 'var(--v3-warn)' : 'var(--text)' }}
      >
        {value}
      </p>
      <p className="oc-kpi__hint mt-2 flex items-center gap-1 text-xs text-[var(--v3-muted)]">
        {hint}
        <ArrowRight aria-hidden="true" className="size-3 transition-transform group-hover:translate-x-0.5" />
      </p>
    </button>
  )
}

type MetricDetailKind = 'today' | 'total' | 'replies' | 'followups' | 'pending'

type MetricDetailItem = {
  key: string
  title: string
  subtitle?: string | null
  meta?: string | null
  href?: string
  /** BOSS 岗位等外部链接：新标签页打开，带 noopener */
  external?: boolean
}

type MetricDetail = {
  kind: MetricDetailKind
  title: string
  count: number | null
  loading: boolean
  error: string | null
  items: MetricDetailItem[]
  href?: string
}

function localDayBounds(offset: number): { from: string; to: string } {
  const start = new Date()
  start.setHours(0, 0, 0, 0)
  start.setDate(start.getDate() + offset)
  const end = new Date(start)
  end.setHours(23, 59, 59, 0)
  const pad = (value: number) => String(value).padStart(2, '0')
  const format = (date: Date) =>
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  return { from: format(start), to: format(end) }
}

function MetricDetailDialog({ detail, onClose }: { detail: MetricDetail | null; onClose: () => void }) {
  return (
    <DialogShell
      open={Boolean(detail)}
      onClose={onClose}
      title={detail?.title ?? '详情'}
      ariaLabel={detail?.title}
      description={detail ? `详情 · 只读 · ${detail.count === null ? '统计读取中' : `共 ${detail.count} 条`} · 最多展示 20 条` : undefined}
      width={720}
      showClose
      panelClassName="max-h-[76vh] overflow-y-auto"
    >
      <div className="mt-4 min-h-0">
        {!detail || detail.loading ? (
          <p className="py-10 text-center text-sm text-[var(--v3-muted)]">正在读取…</p>
        ) : detail.error ? (
          <p className="py-10 text-center text-sm" style={{ color: 'var(--v3-bad)' }}>{detail.error}</p>
        ) : detail.items.length === 0 ? (
          <p className="py-10 text-center text-sm text-[var(--v3-muted)]">暂无明细</p>
        ) : (
          <ul className="divide-y" style={{ borderColor: 'var(--v3-line)' }}>
            {detail.items.map((item) => {
              const content = (
                <>
                  <span className="block truncate text-sm font-medium">{item.title}</span>
                  {item.subtitle ? <span className="mt-0.5 block truncate text-xs text-[var(--v3-muted)]">{item.subtitle}</span> : null}
                  {item.meta ? <span className="mt-0.5 block truncate text-[11px] text-[var(--v3-muted)]">{item.meta}</span> : null}
                </>
              )
              return (
                <li key={item.key} className="py-2.5">
                  {item.href ? (
                    item.external ? (
                      <a href={item.href} target="_blank" rel="noopener noreferrer" className="block hover:underline">
                        {content}
                      </a>
                    ) : (
                      <Link href={item.href} className="block hover:underline">{content}</Link>
                    )
                  ) : content}
                </li>
              )
            })}
          </ul>
        )}
        {detail?.href ? (
          <div className="mt-4 flex justify-end border-t pt-3" style={{ borderColor: 'var(--v3-line)' }}>
            <Link href={detail.href} className="text-xs font-medium text-[var(--v3-accent)] underline underline-offset-2">
              去对应页面处理 →
            </Link>
          </div>
        ) : null}
      </div>
    </DialogShell>
  )
}

function StageFunnel({
  stages,
  currentStage,
  selected,
  onSelect,
}: {
  stages: PipelineStageStat[]
  currentStage?: string | null
  selected: string | null
  onSelect: (stage: string | null) => void
}) {
  const statOf = (code: string, label: string) =>
    stages.find((item) => item.stage === code) ?? stages.find((item) => item.label === label)
  const max = Math.max(1, ...PIPELINE_STAGES.map((stage) => statOf(stage.code, stage.label)?.entered ?? 0))

  return (
    <div className="grid grid-cols-9 items-end gap-1.5 sm:gap-2" role="group" aria-label="投递 9 阶段">
      {PIPELINE_STAGES.map((stage, stageIndex) => {
        const stat = statOf(stage.code, stage.label)
        const entered = stat?.entered ?? 0
        const isCurrent = currentStage === stage.code
        const isSelected = selected === stage.code
        const height = Math.max(6, Math.round((entered / max) * 108))
        return (
          <button
            key={stage.code}
            type="button"
            onClick={() => onSelect(isSelected ? null : stage.code)}
            title={`${stage.label}：进入 ${entered}`}
            className="group relative flex min-w-0 flex-col items-center gap-1.5 rounded-[10px] px-0.5 pb-1 pt-2 transition-colors"
            aria-current={isCurrent ? 'step' : undefined}
            style={isSelected ? { backgroundColor: 'var(--accent-soft)' } : undefined}
          >
            {isCurrent ? <span className="oc-stage-current-mark" aria-hidden="true">▼ 当前</span> : null}
            <span className="text-[11px] font-semibold tabular-nums text-[var(--text)]">{entered}</span>
            <span className="flex h-[112px] w-full items-end justify-center">
              <span
                className={`oc-stage-bar block w-full max-w-[34px] rounded-t-[4px] transition-[height] duration-700 ${isCurrent ? 'animate-v3-bar' : ''}`}
                data-final={stageIndex === PIPELINE_STAGES.length - 1 ? true : undefined}
                data-current={isCurrent ? true : undefined}
                style={{ height, ['--i' as string]: stageIndex }}
              />
            </span>
            <span className={`line-clamp-1 text-[10px] ${isCurrent ? 'font-semibold text-[var(--v3-accent)]' : 'text-[var(--v3-muted)]'}`}>
              {stage.label}{isCurrent ? ' · 当前' : ''}
            </span>
          </button>
        )
      })}
    </div>
  )
}

function BudgetRing({ budget }: { budget?: BudgetActionSummary | null }) {
  const limit = typeof budget?.limit === 'number' ? budget.limit : -1
  const used = budget?.used ?? 0
  const consumed = budget?.consumed ?? 0
  const reserved = budget?.reserved ?? 0
  const uncertain = budget?.uncertain ?? 0
  const unlimited = limit < 0
  const ratio = unlimited ? 0 : limit > 0 ? Math.min(1, used / limit) : 0
  const radius = 42
  const circumference = 2 * Math.PI * radius
  const consumedRatio = unlimited ? 0 : limit > 0 ? Math.min(1, consumed / limit) : 0
  const reservedRatio = unlimited ? 0 : limit > 0 ? Math.min(1, reserved / limit) : 0
  const uncertainRatio = unlimited ? 0 : limit > 0 ? Math.min(1, uncertain / limit) : 0
  const segments = unlimited
    ? []
    : [
        { color: 'var(--v3-accent)', ratio: consumedRatio },
        { color: 'var(--v3-warn)', ratio: reservedRatio },
        { color: 'var(--v3-bad)', ratio: uncertainRatio },
      ]
  let offset = 0

  return (
    <div className="flex flex-col items-center gap-4 sm:flex-row">
      <svg viewBox="0 0 110 110" className="oc-ring-wrap size-[118px] shrink-0" role="img" aria-label="今日首次沟通预算">
        <circle cx="55" cy="55" r={radius} fill="none" stroke="var(--surface2)" strokeWidth={12} />
        {unlimited ? (
          <circle
            cx="55"
            cy="55"
            r={radius}
            fill="none"
            stroke="var(--v3-muted)"
            strokeWidth={12}
            strokeDasharray="4 7"
            opacity={0.5}
          />
        ) : (
          segments.map((segment, index) => {
            const dash = segment.ratio * circumference
            const circle = (
              <circle
                key={index}
                cx="55"
                cy="55"
                r={radius}
                fill="none"
                stroke={segment.color}
                strokeWidth={12}
                strokeDasharray={`${dash} ${Math.max(0, circumference - dash)}`}
                strokeDashoffset={-offset}
                transform="rotate(-90 55 55)"
              />
            )
            offset += dash
            return circle
          })
        )}
        <text x="55" y="50" textAnchor="middle" className="fill-[var(--v3-muted)] text-[10px]">
          已用 / 上限
        </text>
        <text
          x="55"
          y="70"
          textAnchor="middle"
          className="fill-[var(--text)] text-[17px] font-semibold tabular-nums"
        >
          {used}
          <tspan className="fill-[var(--v3-muted)] text-[11px]"> / {unlimited ? '不限' : budgetNumber(limit)}</tspan>
        </text>
      </svg>

      <dl className="min-w-0 flex-1 space-y-1.5 text-sm">
        {[
          { label: '已确认消耗', value: budgetNumber(budget?.consumed), color: 'var(--v3-accent)' },
          { label: '预占', value: budgetNumber(budget?.reserved), color: 'var(--v3-warn)' },
          { label: '结果未知', value: budgetNumber(budget?.uncertain), color: 'var(--v3-bad)' },
          { label: '剩余额度', value: budgetNumber(budget?.remaining), color: 'var(--v3-ok)' },
        ].map((row) => (
          <div key={row.label} className="flex items-center justify-between gap-3 border-b py-1 last:border-0" style={{ borderColor: 'var(--v3-line)' }}>
            <dt className="flex items-center gap-2 text-[var(--v3-muted)]">
              <span aria-hidden="true" className="size-2 rounded-full" style={{ backgroundColor: row.color }} />
              {row.label}
            </dt>
            <dd className="tabular-nums text-[var(--text)]">{row.value}</dd>
          </div>
        ))}
      </dl>
      {budget?.error ? <p className="text-xs text-[var(--v3-bad)]">{budget.error}</p> : null}
      {ratio >= 1 && !unlimited ? (
        <p className="text-xs text-[var(--v3-bad)]">今日预算已用尽，后续发送会被后端拦截</p>
      ) : null}
    </div>
  )
}

export default function TodayPage() {
  const onSpot = useSpotlight()
  const boss = useSharedBossStatus()
  const { status, statusError, refreshAll, refreshStatus, lastUpdated, state, actionBusy, actionMessage, startDisabled, actions } = boss

  const [activeTemplate, setActiveTemplate] = useState<TemplateView | null>(null)
  const [todayDelivered, setTodayDelivered] = useState<number | null>(null)
  const [totalDelivered, setTotalDelivered] = useState<number | null>(null)
  const [replyCount, setReplyCount] = useState<number | null>(null)
  const [followupCount, setFollowupCount] = useState<number | null>(null)
  const [detail, setDetail] = useState<MetricDetail | null>(null)
  const [focusingBoss, setFocusingBoss] = useState(false)
  const [focusBossMessage, setFocusBossMessage] = useState<string | null>(null)
  const [selectedStage, setSelectedStage] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    getTemplates()
      .then((data) => {
        if (!alive) return
        setActiveTemplate(data?.active ?? null)
      })
      .catch(() => {
        if (alive) setActiveTemplate(null)
      })
    return () => {
      alive = false
    }
  }, [])

  useEffect(() => {
    let alive = true
    const loadMetricCounts = async () => {
      const today = localDayBounds(0)
      const [todayResult, totalResult, replyResult, followupResult] = await Promise.allSettled([
        getAttempts({ kind: 'FIRST_CONTACT', textStatus: 'CONFIRMED', from: today.from, to: today.to, page: 1, size: 1 }),
        getAttempts({ kind: 'FIRST_CONTACT', textStatus: 'CONFIRMED', page: 1, size: 1 }),
        getContacts({ replyState: 'REPLIED', page: 1, size: 1 }),
        getEffectiveFollowups(1),
      ])
      if (!alive) return
      setTodayDelivered(todayResult.status === 'fulfilled' ? todayResult.value.total ?? 0 : null)
      setTotalDelivered(totalResult.status === 'fulfilled' ? totalResult.value.total ?? 0 : null)
      setReplyCount(replyResult.status === 'fulfilled' ? replyResult.value.total ?? 0 : null)
      setFollowupCount(followupResult.status === 'fulfilled' ? followupResult.value.total ?? 0 : null)
    }
    void loadMetricCounts()
    const timer = window.setInterval(() => void loadMetricCounts(), 60000)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [])

  const pipeline = status?.pipeline ?? null
  const stages = pipeline?.stages ?? []
  const events: PipelineEvent[] = useMemo(() => {
    const recent = pipeline?.recent ?? []
    return selectedStage ? recent.filter((event) => event.stage === selectedStage) : recent
  }, [pipeline?.recent, selectedStage])

  const budget = boss.budget?.firstContact ?? null
  const pendingResolutions = status?.pendingResolutions ?? 0
  const pipelineCollectCount = stages.find((item) => item.stage === 'COLLECT')?.entered ?? 0
  const pipelineAiCount = stages.find((item) => item.stage === 'AI')?.entered ?? 0
  const manualReleasePhase = pipelineCollectCount === 0 && pipelineAiCount > 0
  const currentState = String(state ?? 'IDLE')

  const handleFocusBossWindow = async () => {
    setFocusingBoss(true)
    setFocusBossMessage(null)
    try {
      await focusBossWindow()
      setFocusBossMessage('已尝试把自动化 BOSS Chrome 切到前台。如果仍看不到，请检查任务栏中的另一个 Chrome 窗口。')
      window.setTimeout(() => void refreshStatus(), 800)
    } catch (error) {
      setFocusBossMessage(error instanceof Error ? error.message : String(error))
    } finally {
      setFocusingBoss(false)
    }
  }

  const openMetricDetail = async (kind: MetricDetailKind) => {
    const titles: Record<MetricDetailKind, string> = {
      today: '今日投递明细',
      total: '总投递明细',
      replies: '已回复联系人',
      followups: '有效沟通跟进',
      pending: '需手动核对',
    }
    const hrefs: Record<MetricDetailKind, string> = {
      today: '/boss/ledger?entity=attempts',
      total: '/boss/ledger?entity=attempts',
      replies: '/boss/ledger?entity=contacts',
      followups: '/boss/ledger?entity=contacts',
      pending: '/boss/inbox?kind=resolve',
    }
    setDetail({ kind, title: titles[kind], count: null, loading: true, error: null, items: [], href: hrefs[kind] })
    try {
      let count = 0
      let items: MetricDetailItem[] = []
      if (kind === 'today' || kind === 'total') {
        const range = kind === 'today' ? localDayBounds(0) : null
        const result = await getAttempts({
          kind: 'FIRST_CONTACT',
          textStatus: 'CONFIRMED',
          ...(range ? { from: range.from, to: range.to } : {}),
          page: 1,
          size: 20,
        })
        count = result.total ?? 0
        items = (result.items ?? []).map((item) => ({
          key: item.attemptId,
          title: item.jobName || '未命名岗位',
          subtitle: item.companyName,
          meta: `${item.textStatus ?? 'CONFIRMED'} · ${formatDateTime(item.startedAt || undefined)}`,
          href: '/boss/ledger?entity=attempts',
        }))
      } else if (kind === 'replies') {
        const result = await getContacts({ replyState: 'REPLIED', page: 1, size: 20 })
        count = result.total ?? 0
        items = (result.items ?? []).map((item) => ({
          key: item.contactKey,
          title: item.hrName || item.contactKey,
          subtitle: item.companyName,
          meta: `${item.replyState ?? 'REPLIED'} · ${formatDateTime(item.lastReplyAt || item.updatedAt || undefined)}`,
          href: '/boss/ledger?entity=contacts',
        }))
      } else if (kind === 'followups') {
        const result = await getEffectiveFollowups(20)
        count = result.total ?? 0
        const replyLabel = (state?: string | null) =>
          state === 'INTERVIEW' ? '面试中' : state === 'REPLIED' ? '已回复' : (state ?? '有效沟通')
        items = (result.items ?? []).map((item) => {
          const meta = [
            item.salary ? `薪资 ${item.salary}` : '薪资未记录',
            item.city,
            item.experience,
            item.degree,
            item.imageStatus === 'CONFIRMED' ? '简历附件已确认' : null,
          ].filter(Boolean).join(' · ')
          return {
            key: item.contactKey,
            title: item.jobName || '已沟通岗位',
            subtitle: [item.companyName, item.hrName ? `HR ${item.hrName}` : null, replyLabel(item.replyState)]
              .filter(Boolean).join(' · '),
            meta,
            href: item.jobUrl ?? undefined,
            external: Boolean(item.jobUrl),
          }
        })
      } else {
        const result = await getAttempts({ textStatus: 'UNKNOWN', page: 1, size: 20 })
        count = result.total ?? 0
        items = (result.items ?? []).map((item) => ({
          key: item.attemptId,
          title: item.jobName || '未命名岗位',
          subtitle: item.companyName,
          meta: `UNKNOWN · ${formatDateTime(item.startedAt || undefined)}`,
          href: '/boss/inbox?kind=resolve',
        }))
      }
      setDetail((prev) => (prev?.kind === kind ? { ...prev, count, items, loading: false } : prev))
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      setDetail((prev) => (prev?.kind === kind ? { ...prev, loading: false, error: message } : prev))
    }
  }

  const selectedLabel = selectedStage
    ? (PIPELINE_STAGES.find((stage) => stage.code === selectedStage)?.label ?? selectedStage)
    : null

  return (
    <div className="animate-v3-in space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="oc-eyebrow text-xs font-medium tracking-[0.1em] text-[var(--v3-muted)]">TODAY · {formatDateTime(lastUpdated || undefined)}</p>
          <h1 className="oc-title mt-1 font-serif text-[34px] font-medium leading-none tracking-[-0.03em]">今日</h1>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          {activeTemplate ? (
            <span
              className="inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5"
              style={{ borderColor: 'var(--v3-line)' }}
              title={`当前生效模板：${activeTemplate.name}`}
            >
              <span aria-hidden="true" className="size-1.5 rounded-full" style={{ backgroundColor: 'var(--v3-ok)' }} />
              生效模板：<strong className="font-medium">{activeTemplate.name}</strong>
            </span>
          ) : (
            <Link href="/boss/setup" className="text-[var(--v3-accent)] underline underline-offset-2">
              尚无生效模板，去配置
            </Link>
          )}
          <span className="rounded-full border px-3 py-1.5 text-xs text-[var(--v3-muted)]" style={{ borderColor: 'var(--v3-line)' }}>
            {stateLabel(currentState)}
          </span>
        </div>
      </div>

      {statusError ? (
        <div
          className="flex flex-wrap items-center justify-between gap-2 rounded-[14px] border px-4 py-3 text-sm"
          style={{ borderColor: 'var(--v3-bad)', backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }}
        >
          <span>运行状态读取失败：{statusError}（保留上次数据，不影响选择与决策）</span>
          <button type="button" onClick={() => void refreshStatus()} className="inline-flex items-center gap-1 underline">
            <RefreshCw aria-hidden="true" className="size-3.5" />
            重试
          </button>
        </div>
      ) : null}

      {(() => {
        const raw = String(state ?? 'IDLE')
        const canShowStart = raw === 'IDLE' || raw === 'COMPLETED' || raw === 'FAILED'
        if (!canShowStart) return null
        const blockedReason =
          status?.isLoggedIn === false
            ? 'BOSS 未登录，请先扫码。'
            : status?.workerExited === false
              ? 'worker 尚未退出，可先强制复位。'
              : !boss.canStart
                ? '当前状态不可开始。'
                : null
        return (
          <section
            onPointerMove={onSpot}
            className="oc-hero oc-spot flex flex-wrap items-center justify-between gap-4 rounded-[20px] border bg-[var(--surface)] p-5 shadow-[var(--shadow)]"
            style={{ borderColor: blockedReason ? 'var(--v3-warn)' : 'var(--v3-accent)' }}
          >
            <div className="min-w-0">
              <p className="text-[11px] font-medium tracking-[0.08em] text-[var(--v3-muted)]">RUN · 开始一轮投递</p>
              <h2 className="mt-1 text-xl font-semibold">开始投递</h2>
              <p className="mt-1 text-sm text-[var(--v3-muted)]">按当前生效模板执行。</p>
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <p className="text-sm" style={{ color: blockedReason ? 'var(--v3-warn)' : 'var(--v3-muted)' }}>
                  {blockedReason ?? '需手动点击开始。' }
                </p>
                <button
                  type="button"
                  disabled={focusingBoss}
                  onClick={() => void handleFocusBossWindow()}
                  className="rounded-full border px-3 py-1 text-xs font-medium disabled:opacity-50"
                  style={{ borderColor: 'var(--v3-accent)', color: 'var(--v3-accent)' }}
                >
                  {focusingBoss ? '正在切到前台…' : '显示 BOSS 窗口'}
                </button>
              </div>
              {focusBossMessage ? (
                <p className="mt-2 text-xs text-[var(--v3-muted)]">{focusBossMessage}</p>
              ) : null}
              {actionMessage ? (
                <p className="mt-2 text-xs" style={{ color: actionMessage.tone === 'success' ? 'var(--v3-ok)' : 'var(--v3-bad)' }}>
                  {actionMessage.text}
                </p>
              ) : null}
            </div>
            <button
              type="button"
              disabled={startDisabled || actionBusy !== null}
              onClick={() => void actions.start()}
              className="oc-btn-primary oc-sweep inline-flex h-14 shrink-0 items-center gap-2 rounded-[16px] px-8 text-base font-semibold shadow-sm transition-opacity disabled:cursor-not-allowed disabled:opacity-45"
              style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
            >
              {actionBusy === 'start' ? <Loader2 aria-hidden="true" className="size-5 animate-spin" /> : <Play aria-hidden="true" className="size-5" />}
              {actionBusy === 'start' ? '启动中…' : '开始投递'}
            </button>
          </section>
        )
      })()}

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-5 [&>*:last-child]:col-span-2 lg:[&>*:last-child]:col-span-1">
        <NumberCard label="今日投递数" value={todayDelivered ?? '—'} hint="文字已确认的首次沟通" onClick={() => void openMetricDetail('today')} />
        <NumberCard label="总投递数" value={totalDelivered ?? '—'} hint="历史文字已确认的首次沟通" onClick={() => void openMetricDetail('total')} />
        <NumberCard label="总回复数" value={replyCount ?? '—'} hint="联系人中标记为已回复" onClick={() => void openMetricDetail('replies')} />
        <NumberCard label="跟进待办" value={followupCount ?? '—'} hint="已回复且简历附件已确认" onClick={() => void openMetricDetail('followups')} />
        <NumberCard label="需手动核对" value={pendingResolutions} hint="UNKNOWN 不计入成功" onClick={() => void openMetricDetail('pending')} />
      </div>

      <JobInsightCard />

      <div className="grid gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="space-y-4">
          <SurfaceCard
            title="今日投递管线"
            action={
              <div className="flex items-center gap-2 text-xs text-[var(--v3-muted)]">
                {selectedLabel ? (
                  <button
                    type="button"
                    onClick={() => setSelectedStage(null)}
                    className="inline-flex items-center gap-1 rounded-full border px-2 py-1"
                    style={{ borderColor: 'var(--v3-accent)', color: 'var(--v3-accent)' }}
                  >
                    筛选：{selectedLabel} ✕
                  </button>
                ) : manualReleasePhase ? (
                  <span style={{ color: 'var(--v3-warn)' }}>人工放行优先：跳过采集/规则/准入</span>
                ) : (
                  <span>点击阶段筛选下方事件流</span>
                )}
                {pipeline?.currentStage ? (
                  <span className="hidden sm:inline">
                    当前：{pipeline.currentStageLabel ?? pipeline.currentStage}
                    {pipeline.currentJob ? ` · ${pipeline.currentJob}` : ''}
                  </span>
                ) : null}
              </div>
            }
          >
            {stages.length === 0 ? (
              <p className="py-8 text-center text-sm text-[var(--v3-muted)]">今日暂无管线数据：任务启动后按 9 阶段累积。</p>
            ) : (
              <StageFunnel
                stages={stages}
                currentStage={pipeline?.currentStage}
                selected={selectedStage}
                onSelect={setSelectedStage}
              />
            )}
          </SurfaceCard>

          <SurfaceCard
            title="事件流 · 最近 30 条"
            action={
              <div className="flex items-center gap-2 text-xs text-[var(--v3-muted)]">
                <span>{events.length} 条</span>
                <button type="button" onClick={() => void refreshAll()} className="inline-flex items-center gap-1 hover:underline">
                  <RefreshCw aria-hidden="true" className="size-3.5" />
                  刷新
                </button>
              </div>
            }
          >
            {events.length === 0 ? (
              <p className="py-8 text-center text-sm text-[var(--v3-muted)]">
                {selectedLabel ? `「${selectedLabel}」阶段暂无事件` : '暂无流水事件'}
              </p>
            ) : (
              <ol className="max-h-[420px] space-y-0 overflow-y-auto pr-1">
                {events.slice(0, 80).map((event, index) => (
                  <li
                    key={`${event.timestamp}-${event.stage}-${index}`}
                    className="flex items-start gap-3 border-b py-2 text-sm last:border-0"
                    style={{ borderColor: 'var(--v3-line)' }}
                  >
                    <span className="w-16 shrink-0 tabular-nums text-xs text-[var(--v3-muted)]">{formatClock(event.timestamp)}</span>
                    <span className="w-16 shrink-0 truncate text-xs text-[var(--v3-muted)]" title={event.stageLabel ?? event.stage}>
                      {event.stageLabel ?? event.stage}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate">{event.jobName || '—'}</span>
                      {event.detail ? <span className="block truncate text-xs text-[var(--v3-muted)]">{event.detail}</span> : null}
                    </span>
                    {event.outcome ? (
                      <span
                        className="shrink-0 rounded-full border px-2 py-0.5 text-[11px]"
                        style={{ borderColor: 'var(--v3-line)', color: outcomeTone(event.outcome) }}
                        title={event.outcome}
                      >
                        {pipelineOutcomeLabel(event.outcome)}
                      </span>
                    ) : null}
                  </li>
                ))}
              </ol>
            )}
          </SurfaceCard>
        </div>

        <div className="space-y-4">
          <SurfaceCard title="今日预算环">
            <BudgetRing budget={budget} />
          </SurfaceCard>
          <SurfaceCard title="聊天状态同步">
            <StatusSyncCard />
          </SurfaceCard>
        </div>
      </div>
      <MetricDetailDialog detail={detail} onClose={() => setDetail(null)} />
    </div>
  )
}

