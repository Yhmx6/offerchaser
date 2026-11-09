'use client'

import Link from 'next/link'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, ChevronRight, ExternalLink, RefreshCw } from 'lucide-react'
import { Skeleton } from '@/components/ui/skeleton-v1'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'

import {
  ApiError,
  approveFollowup,
  createImageResendPlan,
  decideManualRelease,
  getAttempt,
  getAttempts,
  getFollowups,
  getManualReleases,
  rejectFollowup,
  resolveAttempt,
} from '@/lib/api'
import type {
  AttemptDetail,
  AttemptItem,
  FollowupPlan,
  ManualReleaseItem,
  ManualResolution,
} from '@/lib/boss-types'
import {
  aiDecisionLabel,
  formatDateTime,
  imageStatusLabel,
  kindLabel,
  newIdempotencyKey,
  ruleDecisionLabel,
  textStatusLabel,
} from '@/app/components/boss-shared'

/**
 * 待办（/boss/inbox）：三栏决策中枢。
 * 左分类 → 中列表 → 右详情与决策；动作统一“原因 + 二次确认条”。
 * 绝不一键批量批准；批准只入队，下一轮任务执行，不是立即发送。
 */

type Kind = 'all' | 'resolve' | 'release' | 'followup' | 'resend'
type ActionType = 'resolve' | 'approve' | 'reject' | 'resend'

interface CategoryMeta {
  key: Kind
  label: string
  hint: string
}

const CATEGORIES: CategoryMeta[] = [
  { key: 'all', label: '全部', hint: '所有需要拍板的事项' },
  { key: 'resolve', label: '待核对', hint: '文字发送结果 UNKNOWN' },
  { key: 'release', label: '待放行', hint: '规则/AI REVIEW 的岗位' },
  { key: 'followup', label: '建议跟进', hint: 'SUGGESTED 跟进计划' },
  { key: 'resend', label: '图片补发', hint: '文字已确认、图片失败/未知' },
]

const RESOLUTION_OPTIONS: { value: ManualResolution; label: string }[] = [
  { value: 'SENT', label: 'SENT · 人工确认已发送' },
  { value: 'NOT_SENT', label: 'NOT_SENT · 人工确认未发送' },
  { value: 'STILL_UNKNOWN', label: 'STILL_UNKNOWN · 已核对但仍无法确认' },
]

interface InboxItem {
  key: string
  kind: Exclude<Kind, 'all'>
  title: string
  subtitle: string
  meta: string
  badge: string
  raw: AttemptItem | ManualReleaseItem | FollowupPlan
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function toAttemptItem(attempt: AttemptItem): InboxItem {
  const badge = attempt.textStatus === 'UNKNOWN' ? '待核对' : '补发图片'
  return {
    key: `resolve-${attempt.attemptId}`,
    kind: 'resolve',
    title: attempt.jobName || '未知岗位',
    subtitle: attempt.companyName || '—',
    meta: `${kindLabel(attempt.kind)} · 版本 v${attempt.version ?? '—'} · ${formatDateTime(attempt.startedAt)}`,
    badge,
    raw: attempt,
  }
}

function toResendItem(attempt: AttemptItem): InboxItem {
  return {
    key: `resend-${attempt.attemptId}`,
    kind: 'resend',
    title: attempt.jobName || '未知岗位',
    subtitle: attempt.companyName || '—',
    meta: `文字 ${textStatusLabel(attempt.textStatus)} · 图片 ${imageStatusLabel(attempt.imageStatus)}`,
    badge: '图片补发',
    raw: attempt,
  }
}

function toReleaseItem(release: ManualReleaseItem): InboxItem {
  return {
    key: `release-${release.evaluationId}`,
    kind: 'release',
    title: release.jobName || '未知岗位',
    subtitle: release.companyName || '—',
    meta: `${ruleDecisionLabel(release.ruleDecision)} / AI ${aiDecisionLabel(release.aiDecision)} · ${formatDateTime(release.createdAt)}`,
    badge: release.statusLabel || '待放行',
    raw: release,
  }
}

function toFollowupItem(plan: FollowupPlan): InboxItem {
  return {
    key: `followup-${plan.planId}`,
    kind: 'followup',
    title: plan.jobName || '未知岗位',
    subtitle: plan.companyName || '—',
    meta: `${plan.planType === 'IMAGE_RESEND' ? '图片补发' : '跟进'} · 建议 ${formatDateTime(plan.suggestedAt)} · 版本 v${plan.version ?? '—'}`,
    badge: '建议跟进',
    raw: plan,
  }
}

function isAttemptItem(item: InboxItem): item is InboxItem & { raw: AttemptItem } {
  return item.kind === 'resolve' || item.kind === 'resend'
}

function isReleaseItem(item: InboxItem): item is InboxItem & { raw: ManualReleaseItem } {
  return item.kind === 'release'
}

function safeExternalUrl(candidate?: string | null): string | null {
  if (!candidate || typeof candidate !== 'string') return null
  try {
    const parsed = new URL(candidate)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.toString() : null
  } catch {
    return null
  }
}

function safeJobUrl(item: InboxItem): string | null {
  // 跟进计划没有独立岗位链接入口；只对核对/放行/补发显示。
  if (item.kind === 'followup') return null
  return safeExternalUrl((item.raw as { jobUrl?: string | null }).jobUrl)
}

function isFollowupItem(item: InboxItem): item is InboxItem & { raw: FollowupPlan } {
  return item.kind === 'followup'
}

export default function InboxPage() {
  const [kind, setKind] = useState<Kind>('all')
  const [resolveItems, setResolveItems] = useState<InboxItem[]>([])
  const [resendItems, setResendItems] = useState<InboxItem[]>([])
  const [releaseItems, setReleaseItems] = useState<InboxItem[]>([])
  const [followupItems, setFollowupItems] = useState<InboxItem[]>([])
  const [counts, setCounts] = useState<Record<Kind, number | null>>({ all: null, resolve: null, release: null, followup: null, resend: null })
  const [releaseQueueMeta, setReleaseQueueMeta] = useState<{ approved: number; maxPerRun: number } | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [refreshKey, setRefreshKey] = useState(0)

  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [detail, setDetail] = useState<AttemptDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  const [reason, setReason] = useState('')
  const [resolution, setResolution] = useState<ManualResolution>('SENT')
  const [pendingAction, setPendingAction] = useState<ActionType | null>(null)
  const [busy, setBusy] = useState(false)
  const [decisionError, setDecisionError] = useState<string | null>(null)
  const [decisionResult, setDecisionResult] = useState<string | null>(null)
  const [bulkConfirmOpen, setBulkConfirmOpen] = useState(false)
  const [bulkClearing, setBulkClearing] = useState(false)
  const [bulkFeedback, setBulkFeedback] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)

  const requestRef = useRef(0)

  // 从查询参数读取分类（静态导出下不用 useSearchParams，避免 Suspense 约束）
  useEffect(() => {
    if (typeof window === 'undefined') return
    const params = new URLSearchParams(window.location.search)
    const rawKind = params.get('kind')
    const allowed: Kind[] = ['resolve', 'release', 'followup', 'resend']
    if (rawKind && (allowed as string[]).includes(rawKind)) setKind(rawKind as Kind)
  }, [])

  const loadAll = useCallback(async () => {
    const requestId = ++requestRef.current
    setLoading(true)
    setLoadError(null)
    try {
      const [resolveResult, releaseResult, followupResult, resendFailed, resendUnknown] = await Promise.all([
        getAttempts({ textStatus: 'UNKNOWN', page: 1, size: 50 }),
        getManualReleases('PENDING'),
        getFollowups({ status: 'SUGGESTED', page: 1, size: 50 }),
        getAttempts({ textStatus: 'CONFIRMED', imageStatus: 'FAILED', page: 1, size: 50 }),
        getAttempts({ textStatus: 'CONFIRMED', imageStatus: 'UNKNOWN', page: 1, size: 50 }),
      ])
      if (requestId !== requestRef.current) return

      const resolveList = (resolveResult.items ?? [])
        .filter((attempt) => !attempt.manualResolution)
        .map(toAttemptItem)
      const releaseList = (releaseResult.items ?? []).map(toReleaseItem)
      const followupList = (followupResult.items ?? []).map(toFollowupItem)
      const resendMap = new Map<string, InboxItem>()
      for (const attempt of [...(resendFailed.items ?? []), ...(resendUnknown.items ?? [])]) {
        resendMap.set(attempt.attemptId, toResendItem(attempt))
      }
      const resendList = Array.from(resendMap.values())

      setResolveItems(resolveList)
      setReleaseItems(releaseList)
      setReleaseQueueMeta({
        approved: releaseResult.counts?.APPROVED ?? 0,
        maxPerRun: releaseResult.maxPerRun ?? 10,
      })
      setFollowupItems(followupList)
      setResendItems(resendList)
      const resolveCount = resolveResult.total ?? resolveList.length
      const releaseCount = releaseResult.counts?.PENDING ?? releaseList.length
      const followupCount = followupResult.total ?? followupList.length
      const resendCount = (resendFailed.total ?? 0) + (resendUnknown.total ?? 0)
      setCounts({
        resolve: resolveCount,
        release: releaseCount,
        followup: followupCount,
        resend: resendCount,
        all: resolveCount + releaseCount + followupCount + resendCount,
      })
    } catch (error) {
      if (requestId !== requestRef.current) return
      setLoadError(errorText(error))
    } finally {
      if (requestId === requestRef.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadAll()
  }, [loadAll, refreshKey])

  const allItems = useMemo(
    () => [...resolveItems, ...releaseItems, ...followupItems, ...resendItems],
    [resolveItems, releaseItems, followupItems, resendItems]
  )
  const visibleItems = useMemo(() => {
    if (kind === 'all') return allItems
    if (kind === 'resolve') return resolveItems
    if (kind === 'release') return releaseItems
    if (kind === 'followup') return followupItems
    return resendItems
  }, [kind, allItems, resolveItems, releaseItems, followupItems, resendItems])

  // 选中项不在当前列表时，自动选中下一条（决策后自动前进）
  useEffect(() => {
    if (visibleItems.length === 0) {
      setSelectedKey(null)
      return
    }
    if (!selectedKey || !visibleItems.some((item) => item.key === selectedKey)) {
      setSelectedKey(visibleItems[0].key)
    }
  }, [visibleItems, selectedKey])

  const selected = useMemo(
    () => visibleItems.find((item) => item.key === selectedKey) ?? allItems.find((item) => item.key === selectedKey) ?? null,
    [visibleItems, allItems, selectedKey]
  )

  // 待核对项需要拉取详情（事件证据/关联评估）
  useEffect(() => {
    if (!selected || selected.kind !== 'resolve' || !isAttemptItem(selected)) {
      setDetail(null)
      return
    }
    let alive = true
    setDetailLoading(true)
    getAttempt(selected.raw.attemptId)
      .then((next) => {
        if (alive) setDetail(next)
      })
      .catch(() => {
        if (alive) setDetail(null)
      })
      .finally(() => {
        if (alive) setDetailLoading(false)
      })
    return () => {
      alive = false
    }
  }, [selected])

  const resetDecision = () => {
    setReason('')
    setPendingAction(null)
    setDecisionError(null)
    setDecisionResult(null)
  }

  const chooseAction = (action: ActionType) => {
    if (!selected) return
    if (!reason.trim()) {
      setDecisionError('请填写原因（必填，会写入人工操作审计）')
      return
    }
    if (reason.trim().length > 500) {
      setDecisionError('原因不能超过 500 字')
      return
    }
    setDecisionError(null)
    setPendingAction(action)
  }

  const refreshAndKeep = async () => {
    setRefreshKey((value) => value + 1)
  }

  const bulkClearResolve = async () => {
    if (bulkClearing) return
    const targets = resolveItems.filter(isAttemptItem)
    if (targets.length === 0) {
      setBulkConfirmOpen(false)
      return
    }
    const bulkReason = '人工已查看并自行投递，从待核对列表清除；系统不记成功、不结算或释放预算。'
    setBulkClearing(true)
    setBulkFeedback(null)
    let cleared = 0
    const failures: string[] = []
    for (const item of targets) {
      try {
        await resolveAttempt(item.raw.attemptId, {
          resolution: 'STILL_UNKNOWN',
          reason: bulkReason,
          expectedVersion: item.raw.version ?? 0,
          idempotencyKey: newIdempotencyKey('bulk-resolve'),
        })
        cleared += 1
      } catch (error) {
        failures.push(`${item.title}：${errorText(error)}`)
      }
    }
    setBulkClearing(false)
    setBulkConfirmOpen(false)
    if (failures.length === 0) {
      setBulkFeedback({ tone: 'ok', text: `已清理 ${cleared} 条待核对记录；未记成功、未结算/释放预算。` })
    } else {
      setBulkFeedback({
        tone: 'error',
        text: `已清理 ${cleared} 条，失败 ${failures.length} 条：${failures.slice(0, 2).join('；')}${failures.length > 2 ? '…' : ''}`,
      })
    }
    await refreshAndKeep()
  }

  const confirmAction = async () => {
    if (!selected || !pendingAction || busy) return
    const trimmed = reason.trim()
    setBusy(true)
    setDecisionError(null)
    try {
      if (pendingAction === 'resolve' && isAttemptItem(selected)) {
        const result = await resolveAttempt(selected.raw.attemptId, {
          resolution,
          reason: trimmed,
          expectedVersion: selected.raw.version ?? 0,
          idempotencyKey: newIdempotencyKey('resolve'),
        })
        setDecisionResult(
          `核对已提交：文字状态 ${textStatusLabel(result.textStatus)}，版本 v${result.version ?? '—'}${result.idempotent ? '（幂等返回，未重复改动）' : ''}`
        )
      } else if ((pendingAction === 'approve' || pendingAction === 'reject') && isReleaseItem(selected)) {
        const result = await decideManualRelease({
          evaluationId: selected.raw.evaluationId,
          action: pendingAction === 'approve' ? 'APPROVE' : 'REJECT',
          reason: trimmed,
        })
        setDecisionResult(
          pendingAction === 'approve'
            ? `已加入放行队列，下一轮任务执行（不会立即发送）${result.releaseId ? ` · releaseId ${result.releaseId}` : ''}`
            : '已拒绝该岗位的人工放行'
        )
      } else if ((pendingAction === 'approve' || pendingAction === 'reject') && isFollowupItem(selected)) {
        const body = {
          expectedVersion: typeof selected.raw.version === 'number' ? selected.raw.version : 0,
          reason: trimmed,
          idempotencyKey: newIdempotencyKey(pendingAction),
        }
        const updated =
          pendingAction === 'approve'
            ? await approveFollowup(selected.raw.planId, body)
            : await rejectFollowup(selected.raw.planId, body)
        setDecisionResult(
          pendingAction === 'approve'
            ? `已批准跟进计划（v${updated?.version ?? '—'}）：只允许下一轮任务执行，不会立即发送；联系人回复/接管/停止会使批准失效。`
            : '已拒绝该跟进计划。'
        )
      } else if (pendingAction === 'resend' && isAttemptItem(selected)) {
        const plan = await createImageResendPlan(selected.raw.attemptId, { reason: trimmed })
        setDecisionResult(
          `已生成图片补发计划${plan?.planId ? `（${plan.planId}）` : ''}：不会自动执行，需到「建议跟进」批准后下一轮才执行。`
        )
      }
      setPendingAction(null)
      setReason('')
      await refreshAndKeep()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setDecisionError(`${error.message}（队列/记录状态已变化，已刷新最新数据，请勿直接重试）`)
        setPendingAction(null)
        await refreshAndKeep()
      } else if (error instanceof ApiError && (error.status === 400 || error.status === 404)) {
        setDecisionError(`${error.message}（数据已刷新，请核对后重新操作）`)
        setPendingAction(null)
        await refreshAndKeep()
      } else {
        setDecisionError(`操作失败：${errorText(error)}`)
      }
    } finally {
      setBusy(false)
    }
  }

  const list = visibleItems

  return (
    <div className="animate-v3-in space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs font-medium tracking-[0.1em] text-[var(--v3-muted)]">INBOX · 决策中枢</p>
          <h1 className="mt-1 font-serif text-[34px] font-medium leading-none tracking-[-0.03em]">待办</h1>
        </div>
        <div className="flex items-center gap-2 text-xs text-[var(--v3-muted)]">
          <span>批准只入队，不会立即发送；409 会刷新数据、不自动重试</span>
          {kind === 'resolve' && resolveItems.length > 0 ? (
            <button
              type="button"
              onClick={() => {
                setBulkFeedback(null)
                setBulkConfirmOpen(true)
              }}
              className="inline-flex items-center gap-1 rounded-full border px-2.5 py-1"
              style={{ borderColor: 'var(--v3-warn)', color: 'var(--v3-warn)' }}
            >
              <AlertTriangle aria-hidden="true" className="size-3.5" />
              清理待核对（{resolveItems.length}）
            </button>
          ) : null}
          <button
            type="button"
            onClick={() => void refreshAndKeep()}
            className="inline-flex items-center gap-1 rounded-full border px-2.5 py-1"
            style={{ borderColor: 'var(--v3-line)' }}
          >
            <RefreshCw aria-hidden="true" className="size-3.5" />
            刷新
          </button>
        </div>
      </div>

      {loadError ? (
        <div
          className="rounded-[14px] border px-4 py-3 text-sm"
          style={{ borderColor: 'var(--v3-bad)', backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }}
        >
          待办数据加载失败：{loadError}（保留已有列表，不代表队列为空）
          <button type="button" className="ml-2 underline" onClick={() => void refreshAndKeep()}>
            重试
          </button>
        </div>
      ) : null}

      {bulkFeedback ? (
        <div
          className="rounded-[14px] border px-4 py-3 text-sm"
          style={
            bulkFeedback.tone === 'ok'
              ? { borderColor: 'var(--v3-ok)', backgroundColor: 'var(--ok-soft)', color: 'var(--v3-ok)' }
              : { borderColor: 'var(--v3-bad)', backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }
          }
        >
          {bulkFeedback.text}
        </div>
      ) : null}

      {releaseQueueMeta && releaseQueueMeta.approved > 0 ? (
        <div className="oc-note rounded-[14px] px-4 py-3 text-sm" data-tone="primary">
          人工放行：已批准 <strong>{releaseQueueMeta.approved}</strong> 条待执行；下一轮最多执行{' '}
          <strong>{releaseQueueMeta.maxPerRun}</strong> 条，剩余{' '}
          <strong>{Math.max(0, releaseQueueMeta.approved - releaseQueueMeta.maxPerRun)}</strong> 条留到后续轮次。
        </div>
      ) : null}

      <div className="grid gap-4 xl:grid-cols-[170px_minmax(280px,340px)_minmax(0,1fr)]">
        {/* 分类栏 */}
        <aside
          className="oc-card rounded-[20px] border bg-[var(--surface)] p-2 shadow-[var(--shadow)]"
          style={{ borderColor: 'var(--v3-line)' }}
          aria-label="待办分类"
        >
          {CATEGORIES.map((category) => {
            const active = kind === category.key
            const count = counts[category.key]
            return (
              <button
                key={category.key}
                type="button"
                onClick={() => {
                  setKind(category.key)
                  resetDecision()
                }}
                className="oc-row flex w-full items-center justify-between gap-2 rounded-[12px] px-3 py-2.5 text-left text-sm transition-colors"
                aria-pressed={active}
                data-selected={active ? true : undefined}
                data-tone={category.key === 'all' ? 'primary' : category.key === 'resolve' || category.key === 'release' ? 'warn' : 'muted'}
                style={
                  active
                    ? { backgroundColor: 'var(--accent-soft)', color: 'var(--v3-accent)', fontWeight: 600 }
                    : { color: 'var(--text)' }
                }
                title={category.hint}
              >
                <span className="min-w-0 truncate">{category.label}</span>
                <span className="oc-count shrink-0 text-xs tabular-nums text-[var(--v3-muted)]">{count ?? '—'}</span>
              </button>
            )
          })}
        </aside>

        {/* 列表栏 */}
        <section
          className="oc-card min-h-[420px] rounded-[20px] border bg-[var(--surface)] p-2 shadow-[var(--shadow)]"
          style={{ borderColor: 'var(--v3-line)' }}
          aria-label="待办列表"
        >
          {loading ? (
            <div className="space-y-2 p-2">
              {Array.from({ length: 5 }).map((_, index) => (
                <Skeleton key={index} height={64} radius={12} delay={index * 80} />
              ))}
            </div>
          ) : list.length === 0 ? (
            <div className="px-4 py-14 text-center text-sm text-[var(--v3-muted)]">
              <p className="font-medium text-[var(--text)]">{kind === 'all' ? '暂无待处理事项' : `暂无${CATEGORIES.find((item) => item.key === kind)?.label ?? ''}`}</p>
              <p className="mt-1">新事项由后端记账/评估产生，不会在前端伪造。</p>
              <Link href="/boss/ledger" className="mt-3 inline-block text-[var(--v3-accent)] underline underline-offset-2">
                查看记录
              </Link>
            </div>
          ) : (
            <div
              className="max-h-[calc(100vh-260px)] space-y-1 overflow-y-auto"
              role="listbox"
              aria-label="待办条目"
              tabIndex={0}
              onKeyDown={(event) => {
                if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
                if (list.length === 0) return
                event.preventDefault()
                const index = list.findIndex((item) => item.key === selectedKey)
                const nextIndex =
                  event.key === 'ArrowDown'
                    ? (index + 1) % list.length
                    : (index - 1 + list.length) % list.length
                setSelectedKey(list[nextIndex].key)
                resetDecision()
              }}
            >
              {list.map((item) => {
                const active = item.key === selectedKey
                return (
                  <button
                    key={item.key}
                    type="button"
                    role="option"
                    aria-selected={active}
                    onClick={() => {
                      setSelectedKey(item.key)
                      resetDecision()
                    }}
                    className="oc-row w-full rounded-[12px] px-3 py-2.5 text-left transition-colors"
                    style={active ? { backgroundColor: 'var(--accent-soft)' } : undefined}
                  >
                    <span className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-medium text-[var(--text)]">{item.title}</span>
                      <span
                        className="oc-chip shrink-0 rounded-full border px-1.5 py-0.5 text-[10px]"
                        data-tone={item.kind === 'resolve' || item.kind === 'release' ? 'warn' : item.kind === 'followup' ? 'primary' : 'muted'}
                        data-nodot
                      >
                        {item.badge}
                      </span>
                    </span>
                    <span className="mt-0.5 block truncate text-xs text-[var(--v3-muted)]">{item.subtitle}</span>
                    <span className="mt-1 block truncate text-[11px] text-[var(--v3-muted)]">{item.meta}</span>
                  </button>
                )
              })}
            </div>
          )}
        </section>

        {/* 详情与决策 */}
        <section
          className="oc-card min-h-[420px] rounded-[20px] border bg-[var(--surface)] p-5 shadow-[var(--shadow)]"
          style={{ borderColor: 'var(--v3-line)' }}
          aria-label="详情与决策"
        >
          {!selected ? (
            <div className="py-20 text-center text-sm text-[var(--v3-muted)]">从左/中栏选择一条待办</div>
          ) : (
            <div className="animate-v3-detail space-y-4">
              <div className="flex flex-wrap items-start justify-between gap-2 border-b pb-3" style={{ borderColor: 'var(--v3-line)' }}>
                <div className="min-w-0">
                  <p className="text-[11px] tracking-[0.08em] text-[var(--v3-muted)]">
                    {CATEGORIES.find((item) => item.key === selected.kind)?.label}
                  </p>
                  <h2 className="mt-0.5 truncate text-[17px] font-semibold">{selected.title}</h2>
                  <p className="truncate text-sm text-[var(--v3-muted)]">{selected.subtitle}</p>
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  {safeJobUrl(selected) ? (
                    <a
                      href={safeJobUrl(selected)!}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="oc-btn-ghost inline-flex items-center gap-1 rounded-full border px-3 py-1.5 text-xs font-medium"
                      style={{ borderColor: 'var(--v3-accent)', color: 'var(--v3-accent)' }}
                    >
                      <ExternalLink aria-hidden="true" className="size-3.5" />
                      打开岗位页
                    </a>
                  ) : null}
                  <span className="rounded-full border px-2 py-0.5 text-xs text-[var(--v3-muted)]" style={{ borderColor: 'var(--v3-line)' }}>
                    {selected.badge}
                  </span>
                </div>
              </div>

              {/* 详情内容 */}
              {selected.kind === 'resolve' && isAttemptItem(selected) ? (
                <ResolveDetail item={selected.raw} detail={detail} loading={detailLoading} />
              ) : null}
              {selected.kind === 'release' && isReleaseItem(selected) ? (
                <ReleaseDetail item={selected.raw} />
              ) : null}
              {selected.kind === 'followup' && isFollowupItem(selected) ? (
                <FollowupDetail item={selected.raw} />
              ) : null}
              {selected.kind === 'resend' && isAttemptItem(selected) ? <ResendDetail item={selected.raw} /> : null}

              {/* 决策表单 */}
              <div className="space-y-3 rounded-[16px] border p-4" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }}>
                {selected.kind === 'resolve' ? (
                  <div className="space-y-1.5">
                    <label htmlFor="inbox-resolution" className="text-sm font-medium">
                      核对结论
                    </label>
                    <select
                      id="inbox-resolution"
                      value={resolution}
                      onChange={(event) => setResolution(event.target.value as ManualResolution)}
                      className="h-10 w-full rounded-[10px] border bg-[var(--surface)] px-3 text-sm"
                      style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
                    >
                      {RESOLUTION_OPTIONS.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </div>
                ) : null}

                <div className="space-y-1.5">
                  <label htmlFor="inbox-reason" className="text-sm font-medium">
                    原因（必填，≤500 字，写入审计）
                  </label>
                  <textarea
                    id="inbox-reason"
                    rows={3}
                    maxLength={500}
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                    placeholder={
                      selected.kind === 'resolve'
                        ? '例如：在 BOSS 聊天窗口确认消息已发出'
                        : selected.kind === 'resend'
                          ? '例如：文字已确认，图片未送达，补发一次'
                          : '例如：岗位与方向匹配，同意下一轮放行'
                    }
                    className="w-full rounded-[10px] border bg-[var(--surface)] px-3 py-2 text-sm leading-6 outline-none focus:ring-2"
                    style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
                  />
                  <p className="text-right text-xs tabular-nums text-[var(--v3-muted)]">{reason.trim().length}/500</p>
                </div>

                {decisionError ? (
                  <div className="flex items-start gap-2 rounded-[10px] px-3 py-2 text-sm" style={{ backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }}>
                    <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                    <span>{decisionError}</span>
                  </div>
                ) : null}
                {decisionResult ? (
                  <div className="rounded-[10px] px-3 py-2 text-sm" style={{ backgroundColor: 'var(--ok-soft)', color: 'var(--v3-ok)' }}>
                    {decisionResult}
                  </div>
                ) : null}

                {/* 动作按钮 */}
                {pendingAction ? (
                  <div
                    className="animate-v3-in flex flex-wrap items-center justify-between gap-2 rounded-[12px] border px-3 py-2"
                    style={{ borderColor: 'var(--v3-warn)', backgroundColor: 'var(--warn-soft)' }}
                  >
                    <span className="text-sm" style={{ color: 'var(--v3-warn)' }}>
                      确认执行「{ACTION_LABEL[pendingAction]}」？原因会写入审计。
                    </span>
                    <span className="flex gap-2">
                      <button
                        type="button"
                        className="rounded-full border px-3 py-1.5 text-sm"
                        style={{ borderColor: 'var(--v3-warn)', color: 'var(--v3-warn)' }}
                        disabled={busy}
                        onClick={() => setPendingAction(null)}
                      >
                        返回
                      </button>
                      <button
                        type="button"
                        className="rounded-full px-3 py-1.5 text-sm font-medium"
                        style={{ backgroundColor: 'var(--v3-warn)', color: 'var(--accent-ink)' }}
                        disabled={busy}
                        onClick={() => void confirmAction()}
                      >
                        {busy ? '提交中…' : '确认'}
                      </button>
                    </span>
                  </div>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {selected.kind === 'resolve' ? (
                      <ActionButton tone="accent" onClick={() => chooseAction('resolve')}>
                        提交人工核对
                      </ActionButton>
                    ) : null}
                    {selected.kind === 'release' || selected.kind === 'followup' ? (
                      <>
                        <ActionButton tone="accent" onClick={() => chooseAction('approve')}>
                          {selected.kind === 'release' ? '批准入队' : '批准跟进'}
                        </ActionButton>
                        <ActionButton tone="quiet" onClick={() => chooseAction('reject')}>
                          拒绝
                        </ActionButton>
                      </>
                    ) : null}
                    {selected.kind === 'resend' ? (
                      <ActionButton tone="accent" onClick={() => chooseAction('resend')}>
                        生成补发图片计划
                      </ActionButton>
                    ) : null}
                  </div>
                )}

                <p className="oc-note text-xs leading-5 text-[var(--v3-muted)]" data-tone="warn">{SAFETY_TEXT[selected.kind]}</p>
              </div>
            </div>
          )}
        </section>
      </div>

      <ConfirmDialog
        open={bulkConfirmOpen}
        tone="warning"
        title={`清理 ${resolveItems.length} 条待核对？`}
        description="将统一按 STILL_UNKNOWN 记录，并把记录从待核对列表移除；不记为发送成功、不结算也不释放预算。仅在你已人工查看并自行投递/处理时使用，每条都会写入审计。"
        confirmText={bulkClearing ? '清理中…' : '确认清理'}
        busy={bulkClearing}
        onCancel={() => setBulkConfirmOpen(false)}
        onConfirm={() => void bulkClearResolve()}
      />
    </div>
  )
}

const ACTION_LABEL: Record<ActionType, string> = {
  resolve: '提交人工核对',
  approve: '批准入队 / 批准跟进',
  reject: '拒绝',
  resend: '生成补发图片计划',
}

const SAFETY_TEXT: Record<Exclude<Kind, 'all'>, string> = {
  resolve: 'UNKNOWN 不计入成功：SENT 会改为已确认并结算预算；NOT_SENT 改为发送失败并释放预留；STILL_UNKNOWN 只记录说明，不改状态与预算。',
  release: '批准后下一轮执行，不立即发送；AI REVIEW 批准后执行时仍重新 AI 评估；身份、预算、文字+图片确认不可绕过；失败可重试。',
  followup: '批准只允许下一轮任务执行，不会立即发送；联系人回复/接管/停止会使批准失效。',
  resend: '生成计划不自动执行；生成后进入「建议跟进」，仍需批准，且只允许下一轮执行。',
}

function ActionButton({
  tone,
  onClick,
  children,
}: {
  tone: 'accent' | 'quiet'
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`${tone === 'accent' ? 'oc-btn-primary oc-sweep' : ''} inline-flex h-10 items-center gap-1 rounded-[10px] px-4 text-sm font-medium transition-colors`}
      style={
        tone === 'accent'
          ? { backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }
          : { border: '1px solid var(--v3-line)', color: 'var(--text)' }
      }
    >
      {children}
      <ChevronRight aria-hidden="true" className="size-3.5" />
    </button>
  )
}

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[96px_1fr] gap-2 border-b py-1.5 text-sm last:border-0" style={{ borderColor: 'var(--v3-line)' }}>
      <span className="text-xs text-[var(--v3-muted)]">{label}</span>
      <span className="min-w-0 break-all">{value || '—'}</span>
    </div>
  )
}

function ResolveDetail({ item, detail, loading }: { item: AttemptItem; detail: AttemptDetail | null; loading: boolean }) {
  return (
    <div className="space-y-1">
      <DetailRow label="attemptId" value={<code className="v3-mono text-xs">{item.attemptId}</code>} />
      <DetailRow label="状态" value={`文字 ${textStatusLabel(item.textStatus)} · 图片 ${imageStatusLabel(item.imageStatus)}`} />
      <DetailRow label="版本" value={`v${item.version ?? '—'}`} />
      <DetailRow label="评估/规则" value={detail?.evaluation ? `${ruleDecisionLabel(detail.evaluation.ruleDecision)} / AI ${aiDecisionLabel(detail.evaluation.aiDecision)}` : loading ? '加载中…' : '—'} />
      {detail?.evaluation?.ruleEvidence ? <DetailRow label="规则证据" value={<span className="text-xs">{detail.evaluation.ruleEvidence}</span>} /> : null}
      {detail?.attempt.errorDetail ? <DetailRow label="错误" value={detail.attempt.errorDetail} /> : null}
      <div className="mt-2">
        <p className="text-xs font-medium text-[var(--v3-muted)]">事件证据时间线</p>
        {loading ? (
          <p className="mt-1 text-xs text-[var(--v3-muted)]">加载中…</p>
        ) : (detail?.events?.length ?? 0) > 0 ? (
          <ol className="mt-1 space-y-1">
            {detail?.events?.map((event, index) => (
              <li key={`${event.occurredAt ?? 'event'}-${index}`} className="grid grid-cols-[120px_1fr] gap-2 text-xs">
                <span className="tabular-nums text-[var(--v3-muted)]">{formatDateTime(event.occurredAt)}</span>
                <span className="min-w-0 break-all">
                  {event.eventType || '事件'}
                  {event.evidenceLevel ? ` · ${event.evidenceLevel}` : ''}
                  {event.detail ? ` · ${event.detail}` : ''}
                </span>
              </li>
            ))}
          </ol>
        ) : (
          <p className="mt-1 text-xs text-[var(--v3-muted)]">暂无证据事件</p>
        )}
      </div>
    </div>
  )
}

function ReleaseDetail({ item }: { item: ManualReleaseItem }) {
  return (
    <div className="space-y-1">
      <DetailRow label="evaluationId" value={<code className="v3-mono text-xs">{item.evaluationId}</code>} />
      <DetailRow label="规则结论" value={`${ruleDecisionLabel(item.ruleDecision)}${item.ruleReasonCode ? `（${item.ruleReasonCode}）` : ''}`} />
      {item.ruleReason ? <DetailRow label="规则原因" value={item.ruleReason} /> : null}
      <DetailRow label="AI 结论" value={aiDecisionLabel(item.aiDecision)} />
      {item.aiAnalysis ? <DetailRow label="AI 分析" value={item.aiAnalysis} /> : null}
      {item.aiMessage ? <DetailRow label="AI 输出" value={item.aiMessage} /> : null}
      <DetailRow label="时间" value={formatDateTime(item.createdAt)} />
    </div>
  )
}

function FollowupDetail({ item }: { item: FollowupPlan }) {
  return (
    <div className="space-y-1">
      <DetailRow label="planId" value={<code className="v3-mono text-xs">{item.planId}</code>} />
      <DetailRow label="类型" value={item.planType === 'IMAGE_RESEND' ? '图片补发' : '跟进'} />
      <DetailRow label="状态" value={item.status} />
      <DetailRow label="版本" value={`v${item.version ?? '—'}`} />
      {item.reason ? <DetailRow label="原因" value={item.reason} /> : null}
      {item.contactKey ? <DetailRow label="联系人" value={<code className="v3-mono text-xs">{item.contactKey}</code>} /> : null}
      <DetailRow label="建议时间" value={formatDateTime(item.suggestedAt)} />
    </div>
  )
}

function ResendDetail({ item }: { item: AttemptItem }) {
  return (
    <div className="space-y-1">
      <DetailRow label="attemptId" value={<code className="v3-mono text-xs">{item.attemptId}</code>} />
      <DetailRow label="文字状态" value={textStatusLabel(item.textStatus)} />
      <DetailRow label="图片状态" value={imageStatusLabel(item.imageStatus)} />
      <DetailRow label="版本" value={`v${item.version ?? '—'}`} />
      <DetailRow label="说明" value="只有「文字已确认 + 图片失败/未知」才可生成补发计划；计划不自动执行。" />
    </div>
  )
}
