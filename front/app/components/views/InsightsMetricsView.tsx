'use client'

import Link from 'next/link'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import {
  BiCalendar,
  BiChevronDown,
  BiErrorCircle,
  BiInfoCircle,
  BiRefresh,
} from 'react-icons/bi'

import { Drawer } from '@/components/ui/drawer'
import { Skeleton, SkeletonStatGrid } from '@/components/ui/skeleton-v1'
import { TrendChart as IncrementTrendChart } from '@/components/charts/trend-chart'
import { ApiError, getMetricsCollection, getMetricsOverview, getMetricsSeries } from '@/lib/api'
import type {
  CollectionDailyItem,
  CollectionKeywordItem,
  MetricsCollection,
  MetricsOverview,
  MetricsSeries,
  MetricsSeriesMetric,
} from '@/lib/boss-types'

/**
 * 洞察 · 效果与采集。
 *
 * 页面结构与口径以《OfferChaser-洞察页优化-落地说明》为准：
 * - 页面只保留一个 H1（在 page.tsx），本组件从吸顶工具栏开始；
 * - 结果卡、趋势图、处理流水、采集概况、明细/口径抽屉均只读；
 * - 不做跨环节转化率；未知/复核单独标色，拒绝使用中性色；
 * - 保留原有 metrics API、范围校验和空数据/错误态；后端 INVALID_RANGE 等错误码直接展示 message。
 */

type RangePreset = 'today' | '7d' | '30d' | 'custom'
type DetailTab = 'daily' | 'keyword'

const PRESET_OPTIONS: { value: Exclude<RangePreset, 'custom'>; label: string }[] = [
  { value: 'today', label: '今天' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
]

const METRIC_OPTIONS: { value: MetricsSeriesMetric; label: string }[] = [
  { value: 'textConfirmed', label: '确认投递' },
  { value: 'newJobs', label: '新发现岗位' },
  { value: 'replies', label: 'HR回复' },
]

function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value)
}

function formatDate(date: Date): string {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

function todayStr(): string {
  return formatDate(new Date())
}

function addDays(dateStr: string, delta: number): string {
  const parts = dateStr.split('-').map(Number)
  const date = new Date(parts[0], parts[1] - 1, parts[2], 12, 0, 0, 0)
  date.setDate(date.getDate() + delta)
  return formatDate(date)
}

function isValidDateStr(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value)
}

function daysSpan(from: string, to: string): number {
  const fromParts = from.split('-').map(Number)
  const toParts = to.split('-').map(Number)
  const fromDate = new Date(fromParts[0], fromParts[1] - 1, fromParts[2], 12, 0, 0, 0)
  const toDate = new Date(toParts[0], toParts[1] - 1, toParts[2], 12, 0, 0, 0)
  return Math.round((toDate.getTime() - fromDate.getTime()) / 86400000) + 1
}

function defaultRange(): { from: string; to: string } {
  const to = todayStr()
  return { from: addDays(to, -6), to }
}

function num(value?: number | null): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

function percent(value?: number | null): string {
  return `${(num(value) * 100).toFixed(1)}%`
}

function ratio(part?: number | null, total?: number | null): number {
  return num(total) > 0 ? num(part) / num(total) : 0
}

/** 洞察页统一使用“额度”口径，避免动态文案里再次出现“预算”。 */
function quotaText(value?: string | null): string {
  return (value ?? '').replaceAll('预算', '额度')
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : String(error)
}

function shortDate(value?: string | null): string {
  return value && value.length >= 10 ? value.slice(5) : value ?? ''
}

function Segmented<T extends string>({
  items,
  value,
  onChange,
  ariaLabel,
}: {
  items: { value: T; label: ReactNode }[]
  value: T
  onChange: (value: T) => void
  ariaLabel: string
}) {
  return (
    <div className="inline-flex max-w-full items-center gap-0.5 overflow-x-auto rounded-full border p-1" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }} role="group" aria-label={ariaLabel}>
      {items.map((item) => {
        const active = item.value === value
        return (
          <button
            key={item.value}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(item.value)}
            className="shrink-0 rounded-full px-3 py-1.5 text-xs font-medium transition-colors"
            style={active ? { backgroundColor: 'var(--surface)', color: 'var(--v3-accent)', boxShadow: '0 2px 8px -4px rgba(0,0,0,.22)' } : { color: 'var(--v3-muted)' }}
          >
            {item.label}
          </button>
        )
      })}
    </div>
  )
}

function Sparkline({ values, color, fill = false }: { values: number[]; color: string; fill?: boolean }) {
  const safe = values.length > 1 ? values : [0, ...values]
  const max = Math.max(1, ...safe)
  const min = Math.min(...safe)
  const span = Math.max(1, max - min)
  const points = safe.map((value, index) => {
    const x = safe.length > 1 ? (index / (safe.length - 1)) * 100 : 50
    const y = 30 - ((value - min) / span) * 23 - 3
    return `${x.toFixed(2)},${y.toFixed(2)}`
  })
  const line = points.map((point, index) => `${index === 0 ? 'M' : 'L'}${point}`).join(' ')
  const area = `${line} L100,30 L0,30 Z`
  return (
    <svg viewBox="0 0 100 30" width="100%" height="34" aria-hidden="true" preserveAspectRatio="none">
      {fill ? <path d={area} fill={color} opacity="0.15" /> : null}
      <path d={line} fill="none" stroke={color} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
    </svg>
  )
}

function ResultCard({
  label,
  value,
  suffix,
  sub,
  values,
  color,
  primary = false,
  badge,
}: {
  label: string
  value: ReactNode
  suffix?: string
  sub: ReactNode
  values?: number[]
  color: string
  primary?: boolean
  badge?: ReactNode
}) {
  return (
    <section
      className="oc-card flex min-h-[142px] flex-col justify-between overflow-hidden rounded-[18px] border p-4 shadow-[var(--shadow)]"
      style={{
        borderColor: primary ? 'transparent' : 'var(--v3-line)',
        background: primary
          ? 'linear-gradient(135deg, var(--v3-accent), var(--accent-mid))'
          : 'var(--surface)',
        color: primary ? 'var(--accent-ink)' : 'var(--text)',
      }}
    >
      <div className="flex items-start justify-between gap-2">
        <p className="text-xs font-medium" style={{ color: primary ? 'var(--accent-ink)' : 'var(--v3-muted)' }}>{label}</p>
        {badge}
      </div>
      <div className="mt-2 flex items-baseline gap-1">
        <span className="font-serif text-[42px] leading-none tabular-nums tracking-[-0.04em]">{value}</span>
        {suffix ? <span className="text-sm opacity-75">{suffix}</span> : null}
      </div>
      <div className="mt-2 min-h-[16px] text-[11px] leading-4" style={{ color: primary ? 'color-mix(in srgb, var(--accent-ink) 72%, transparent)' : 'var(--v3-muted)' }}>{sub}</div>
      {values && values.length > 1 ? <Sparkline values={values} color={primary ? 'var(--accent-ink)' : color} fill /> : null}
    </section>
  )
}

function StackedFlow({
  title,
  totalLabel,
  total,
  badge,
  parts,
}: {
  title: string
  totalLabel: string
  total: number
  badge?: string
  parts: { label: string; value: number; color: string }[]
}) {
  const visible = parts.filter((part) => part.value > 0)
  return (
    <div className="border-b py-3 last:border-b-0" style={{ borderColor: 'var(--v3-line)' }}>
      <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
        <span className="font-medium">{title}</span>
        <span className="tabular-nums" style={{ color: 'var(--v3-muted)' }}>{total.toLocaleString()} {totalLabel}</span>
      </div>
      <div className="mt-2 flex h-2 w-full overflow-hidden rounded-full" style={{ backgroundColor: 'var(--v3-line)' }}>
        {visible.map((part) => (
          <span key={part.label} style={{ width: `${ratio(part.value, total) * 100}%`, backgroundColor: part.color }} title={`${part.label} ${part.value}`} />
        ))}
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]" style={{ color: 'var(--v3-muted)' }}>
        {parts.map((part) => (
          <span key={part.label} className="inline-flex items-center gap-1">
            <span className="size-1.5 rounded-full" style={{ backgroundColor: part.value > 0 ? part.color : 'transparent', border: part.value > 0 ? 'none' : `1px solid ${part.color}` }} />
            {part.label} <span className="tabular-nums" style={{ color: 'var(--text)' }}>{part.value}</span>
          </span>
        ))}
        {badge ? <span className="ml-auto">{badge}</span> : null}
      </div>
    </div>
  )
}

function CollectionCell({ label, value, suffix, sub }: { label: string; value: ReactNode; suffix?: string; sub: ReactNode }) {
  return (
    <div className="rounded-[12px] border px-3 py-2.5" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }}>
      <p className="text-[11px]" style={{ color: 'var(--v3-muted)' }}>{label}</p>
      <p className="mt-1 flex items-baseline gap-1 text-xl font-semibold tabular-nums">
        {value}
        {suffix ? <span className="text-[11px] font-normal" style={{ color: 'var(--v3-muted)' }}>{suffix}</span> : null}
      </p>
      <p className="mt-0.5 truncate text-[10px]" style={{ color: 'var(--v3-muted)' }}>{sub}</p>
    </div>
  )
}

export default function InsightsMetricsView() {
  const [preset, setPreset] = useState<RangePreset>('7d')
  const [fromInput, setFromInput] = useState('')
  const [toInput, setToInput] = useState('')
  const [applied, setApplied] = useState<{ from: string; to: string } | null>(null)
  const [rangeError, setRangeError] = useState<string | null>(null)
  const [qualityOpen, setQualityOpen] = useState(false)
  const [definitionsOpen, setDefinitionsOpen] = useState(false)
  const [detailTab, setDetailTab] = useState<DetailTab>('daily')

  const [overview, setOverview] = useState<MetricsOverview | null>(null)
  const [overviewLoading, setOverviewLoading] = useState(true)
  const [overviewError, setOverviewError] = useState<string | null>(null)
  const [collection, setCollection] = useState<MetricsCollection | null>(null)
  const [collectionLoading, setCollectionLoading] = useState(true)
  const [collectionError, setCollectionError] = useState<string | null>(null)
  const [metric, setMetric] = useState<MetricsSeriesMetric>('textConfirmed')
  const [series, setSeries] = useState<MetricsSeries | null>(null)
  const [seriesLoading, setSeriesLoading] = useState(true)
  const [seriesError, setSeriesError] = useState<string | null>(null)
  const [miniSeries, setMiniSeries] = useState<Record<string, MetricsSeries | null>>({})
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const overviewRequestRef = useRef(0)
  const collectionRequestRef = useRef(0)
  const seriesRequestRef = useRef(0)

  const loadOverview = useCallback(async (range: { from: string; to: string }) => {
    const requestId = ++overviewRequestRef.current
    setOverviewLoading(true)
    setOverviewError(null)
    try {
      const result = await getMetricsOverview({ from: range.from, to: range.to })
      if (requestId !== overviewRequestRef.current) return
      setOverview(result)
    } catch (error) {
      if (requestId !== overviewRequestRef.current) return
      setOverview(null)
      setOverviewError(errorMessage(error))
    } finally {
      if (requestId === overviewRequestRef.current) setOverviewLoading(false)
    }
  }, [])

  const loadCollection = useCallback(async (range: { from: string; to: string }) => {
    const requestId = ++collectionRequestRef.current
    setCollectionLoading(true)
    setCollectionError(null)
    try {
      const result = await getMetricsCollection({ from: range.from, to: range.to })
      if (requestId !== collectionRequestRef.current) return
      setCollection(result)
    } catch (error) {
      if (requestId !== collectionRequestRef.current) return
      setCollection(null)
      setCollectionError(errorMessage(error))
    } finally {
      if (requestId === collectionRequestRef.current) setCollectionLoading(false)
    }
  }, [])

  const loadSeries = useCallback(async (range: { from: string; to: string }, targetMetric: MetricsSeriesMetric) => {
    const requestId = ++seriesRequestRef.current
    setSeriesLoading(true)
    setSeriesError(null)
    try {
      const result = await getMetricsSeries({ from: range.from, to: range.to, metric: targetMetric })
      if (requestId !== seriesRequestRef.current) return
      setSeries(result)
    } catch (error) {
      if (requestId !== seriesRequestRef.current) return
      setSeries(null)
      setSeriesError(errorMessage(error))
    } finally {
      if (requestId === seriesRequestRef.current) setSeriesLoading(false)
    }
  }, [])

  const loadMiniSeries = useCallback(async (range: { from: string; to: string }) => {
    const targets: MetricsSeriesMetric[] = ['textConfirmed', 'replies', 'newJobs']
    const entries = await Promise.all(targets.map(async (target) => {
      try {
        return [target, await getMetricsSeries({ from: range.from, to: range.to, metric: target })] as const
      } catch {
        return [target, null] as const
      }
    }))
    setMiniSeries(Object.fromEntries(entries))
  }, [])

  useEffect(() => {
    const range = defaultRange()
    setFromInput(range.from)
    setToInput(range.to)
    setApplied(range)
  }, [])

  useEffect(() => {
    if (!applied) return
    void Promise.all([loadOverview(applied), loadCollection(applied), loadMiniSeries(applied)]).then(() => setLastUpdated(new Date()))
  }, [applied, loadOverview, loadCollection, loadMiniSeries])

  useEffect(() => {
    if (!applied) return
    void loadSeries(applied, metric).then(() => setLastUpdated(new Date()))
  }, [applied, metric, loadSeries])

  const applyPreset = (target: Exclude<RangePreset, 'custom'>) => {
    const to = todayStr()
    const from = target === 'today' ? to : target === '7d' ? addDays(to, -6) : addDays(to, -29)
    setPreset(target)
    setRangeError(null)
    setFromInput(from)
    setToInput(to)
    setApplied({ from, to })
  }

  const applyCustom = () => {
    const from = fromInput.trim()
    const to = toInput.trim()
    if (!from || !to) return setRangeError('请选择完整的开始日期和结束日期')
    if (!isValidDateStr(from) || !isValidDateStr(to)) return setRangeError('日期格式应为 YYYY-MM-DD')
    if (from > to) return setRangeError('开始日期不能晚于结束日期')
    const span = daysSpan(from, to)
    if (!Number.isFinite(span) || span < 1) return setRangeError('日期范围不合法')
    if (span > 366) return setRangeError('日期跨度不能超过 366 天')
    setRangeError(null)
    setApplied({ from, to })
  }

  const reloadAll = () => {
    if (!applied) return
    void Promise.all([loadOverview(applied), loadCollection(applied), loadSeries(applied, metric), loadMiniSeries(applied)])
      .then(() => setLastUpdated(new Date()))
  }

  const metrics = overview?.metrics
  const rule = metrics?.rule
  const ai = metrics?.ai
  const firstContact = metrics?.firstContact
  const image = metrics?.image
  const replies = metrics?.replies
  const followups = metrics?.followups
  const budget = metrics?.budget
  const collectionMetrics = metrics?.collection
  const dataQuality = overview?.dataQuality
  const definitions = overview?.definitions ?? {}
  const rangeDays = applied ? daysSpan(applied.from, applied.to) : 0
  const points = series?.points ?? []

  const isEmpty =
    !overviewLoading && !overviewError && !!overview &&
    num(metrics?.newJobs) === 0 && num(metrics?.legacyNewJobs) === 0 &&
    num(rule?.total) === 0 && num(ai?.total) === 0 && num(firstContact?.attempts) === 0 &&
    num(metrics?.textConfirmed) === 0 && num(image?.total) === 0 && num(replies?.contacts) === 0 &&
    num(collectionMetrics?.sourceRecords) === 0

  const miniValues = (metricKey: MetricsSeriesMetric): number[] =>
    miniSeries[metricKey]?.points?.map((point) => num(point.value)) ?? []

  const seriesValues = points.map((point) => num(point.value))
  const seriesTotal = seriesValues.reduce((sum, value) => sum + value, 0)
  const seriesPeakIndex = seriesValues.length > 0 ? seriesValues.indexOf(Math.max(...seriesValues)) : -1
  const seriesStats = {
    total: seriesTotal,
    peak: seriesPeakIndex >= 0 ? seriesValues[seriesPeakIndex] : 0,
    peakDate: seriesPeakIndex >= 0 ? points[seriesPeakIndex]?.date : null,
    average: points.length > 0 ? seriesTotal / points.length : 0,
    latest: seriesValues.length > 0 ? seriesValues[seriesValues.length - 1] : 0,
  }

  const replyRate = replies?.replyRate ?? ratio(replies?.replied, metrics?.textConfirmed)
  const todayBudget = budget ?? null
  const budgetRatio = num(todayBudget?.limit) > 0 ? Math.min(1, num(todayBudget?.consumed) / num(todayBudget?.limit)) : 0

  const flowStages = [
    {
      title: '规则筛选',
      totalLabel: '条评估',
      total: num(rule?.total),
      badge: `过滤率 ${percent(ratio(rule?.reject, rule?.total))}`,
      parts: [
        { label: '通过', value: num(rule?.pass), color: 'var(--v3-accent)' },
        { label: '拒绝', value: num(rule?.reject), color: 'var(--v3-muted)' },
        { label: '复核', value: num(rule?.review), color: 'var(--v3-warn)' },
      ],
    },
    {
      title: 'AI 判定',
      totalLabel: '条判定',
      total: num(ai?.total),
      badge: `匹配率 ${percent(ratio(ai?.match, ai?.total))}`,
      parts: [
        { label: '匹配', value: num(ai?.match), color: 'var(--v3-accent)' },
        { label: '拒绝', value: num(ai?.reject), color: 'var(--v3-muted)' },
        { label: '异常', value: num(ai?.error), color: 'var(--v3-warn)' },
      ],
    },
    {
      title: '文字发送',
      totalLabel: '条尝试',
      total: num(firstContact?.attempts),
      parts: [
        { label: '已确认', value: num(firstContact?.textConfirmed), color: 'var(--v3-accent)' },
        { label: '结果未知', value: num(firstContact?.textUnknown), color: 'var(--v3-warn)' },
        { label: '失败', value: num(firstContact?.textFailed), color: 'var(--v3-bad)' },
      ],
    },
    {
      title: '图片简历',
      totalLabel: '条尝试',
      total: num(image?.total),
      parts: [
        { label: '已发送', value: num(image?.confirmed), color: 'var(--v3-accent)' },
        { label: '失败', value: num(image?.failed), color: 'var(--v3-bad)' },
        { label: '未知', value: num(image?.unknown), color: 'var(--v3-warn)' },
        { label: '跳过', value: num(image?.skipped), color: 'var(--v3-muted)' },
      ],
    },
    {
      title: '联系人回复',
      totalLabel: '位联系人',
      total: num(replies?.contacts),
      badge: `回复率 ${percent(replyRate)}`,
      parts: [
        { label: '已回复', value: num(replies?.replied), color: 'var(--v3-accent)' },
        { label: '未回复', value: Math.max(0, num(replies?.contacts) - num(replies?.replied)), color: 'var(--v3-muted)' },
      ],
    },
  ]

  const dailyRows = (collection?.daily ?? []).filter((item) => num(item.newJobs) > 0 || num(item.sourceRecords) > 0)
  const hiddenZeroDays = (collection?.daily?.length ?? 0) - dailyRows.length
  const keywordRows = collection?.keywords ?? []

  const rangeLabel = applied ? `${shortDate(applied.from)} — ${shortDate(applied.to)}` : '—'
  const activeMetricLabel = METRIC_OPTIONS.find((item) => item.value === metric)?.label ?? metric
  const noSeries = !seriesLoading && !seriesError && points.length > 0 && points.every((point) => num(point.value) === 0)
  const singleDay = rangeDays === 1

  return (
    <div className="space-y-4">
      {/* 吸顶工具栏：范围 + 刷新 + 口径；时间只出现一处 */}
      <div
        className="sticky top-[64px] z-30 rounded-[16px] border bg-[color-mix(in_srgb,var(--surface)_92%,transparent)] p-2.5 shadow-[0_8px_24px_-20px_rgba(0,0,0,.28)] backdrop-blur-md"
        style={{ borderColor: 'var(--v3-line)' }}
      >
        <div className="flex flex-wrap items-center gap-2">
          <Segmented
            items={[
              ...PRESET_OPTIONS.map((item) => ({ value: item.value, label: item.label })),
              { value: 'custom', label: '自定义' },
            ]}
            value={preset}
            onChange={(value) => {
              if (value === 'custom') {
                setPreset('custom')
                setRangeError(null)
              } else {
                applyPreset(value)
              }
            }}
            ariaLabel="日期范围"
          />
          <span className="inline-flex items-center gap-1 text-xs tabular-nums" style={{ color: 'var(--v3-muted)' }}>
            <BiCalendar aria-hidden="true" className="size-3.5" />
            {rangeLabel} · {rangeDays} 天
          </span>
          <span className="hidden text-[10px] sm:inline" style={{ color: 'var(--v3-muted)' }}>
            {overview?.timezone || 'Asia/Shanghai'}
          </span>
          <div className="ml-auto flex flex-wrap items-center gap-2">
            <span className="text-[10px] tabular-nums" style={{ color: 'var(--v3-muted)' }}>
              {lastUpdated ? `${lastUpdated.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })} 更新` : '更新中…'}
            </span>
            <button
              type="button"
              onClick={reloadAll}
              className="inline-flex h-8 items-center gap-1 rounded-full border px-2.5 text-xs font-medium transition-colors hover:bg-[var(--surface2)]"
              style={{ borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }}
            >
              <BiRefresh aria-hidden="true" className={`size-3.5 ${overviewLoading ? 'animate-spin' : ''}`} />
              刷新
            </button>
            <button
              type="button"
              onClick={() => setDefinitionsOpen(true)}
              className="inline-flex h-8 items-center gap-1 rounded-full border px-2.5 text-xs font-medium transition-colors hover:bg-[var(--surface2)]"
              style={{ borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }}
            >
              <BiInfoCircle aria-hidden="true" className="size-3.5" />
              口径说明
            </button>
          </div>
        </div>

        {preset === 'custom' ? (
          <div className="mt-2 flex flex-wrap items-center gap-2 border-t pt-2" style={{ borderColor: 'var(--v3-line)' }}>
            <input aria-label="开始日期" type="date" value={fromInput} max={toInput || undefined} onChange={(event) => setFromInput(event.target.value)} className="h-8 rounded-[8px] border bg-[var(--surface2)] px-2 text-xs" style={{ borderColor: 'var(--v3-line)' }} />
            <span className="text-xs" style={{ color: 'var(--v3-muted)' }}>至</span>
            <input aria-label="结束日期" type="date" value={toInput} min={fromInput || undefined} onChange={(event) => setToInput(event.target.value)} className="h-8 rounded-[8px] border bg-[var(--surface2)] px-2 text-xs" style={{ borderColor: 'var(--v3-line)' }} />
            <button type="button" onClick={applyCustom} className="h-8 rounded-full px-3 text-xs font-medium" style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}>查询</button>
            <span className="text-[10px]" style={{ color: 'var(--v3-muted)' }}>含端点 · ≤366 天</span>
          </div>
        ) : null}
        {rangeError ? <p className="mt-2 text-xs" style={{ color: 'var(--v3-bad)' }}>{rangeError}</p> : null}
      </div>

      {overviewError ? (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-[14px] border px-4 py-3 text-sm" style={{ borderColor: 'var(--v3-bad)', backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }}>
          <span className="inline-flex items-center gap-2"><BiErrorCircle aria-hidden="true" />看板数据加载失败：{overviewError}</span>
          <button type="button" className="underline" onClick={reloadAll}>重试</button>
        </div>
      ) : null}

      {/* 数据质量：默认一行，可展开 */}
      {dataQuality?.notes?.length ? (
        <div className="overflow-hidden rounded-[14px] border" style={{ borderColor: 'color-mix(in srgb, var(--v3-warn) 28%, transparent)', backgroundColor: 'var(--warn-soft)' }}>
          <div className="flex flex-wrap items-center gap-2 px-3 py-2 text-xs" style={{ color: 'var(--v3-warn)' }}>
            <button type="button" onClick={() => setQualityOpen((value) => !value)} aria-expanded={qualityOpen} className="flex min-w-0 flex-1 items-center gap-2 text-left">
              <BiInfoCircle aria-hidden="true" className="size-4 shrink-0" />
              <span className="shrink-0 font-medium">{dataQuality.notes.length} 条数据口径提示</span>
              <span className="truncate opacity-80">· {quotaText(dataQuality.notes[0])}</span>
              <BiChevronDown aria-hidden="true" className={`ml-auto size-4 shrink-0 transition-transform ${qualityOpen ? 'rotate-180' : ''}`} />
            </button>
            <Link href="/boss/inbox?kind=resolve" className="shrink-0 underline underline-offset-2">去待办核对 →</Link>
          </div>
          {qualityOpen ? (
            <ul className="space-y-1 border-t px-10 py-2 text-xs leading-5" style={{ borderColor: 'color-mix(in srgb, var(--v3-warn) 22%, transparent)', color: 'var(--v3-muted)' }}>
              {dataQuality.notes.map((note, index) => <li key={`${index}-${note}`}>· {quotaText(note)}</li>)}
            </ul>
          ) : null}
        </div>
      ) : null}

      {/* 4 张结果卡 */}
      {overviewLoading && !overview ? (
        <SkeletonStatGrid count={4} />
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <ResultCard
            primary
            label="文字确认投递"
            value={num(metrics?.textConfirmed)}
            sub={`图片已发 ${num(image?.confirmed)} · 失败 ${num(image?.failed)}`}
            values={miniValues('textConfirmed')}
            color="var(--v3-accent)"
          />
          <ResultCard
            label="联系人回复率"
            value={percent(replyRate)}
            suffix=""
            sub={`已回复 ${num(replies?.replied)} / 文字确认 ${num(metrics?.textConfirmed)}`}
            values={miniValues('replies')}
            color="var(--accent-mid)"
          />
          <ResultCard
            label="新发现岗位"
            value={num(metrics?.newJobs)}
            sub={`日均 ${rangeDays > 0 ? Math.round(num(metrics?.newJobs) / rangeDays) : 0} · 旧数据 ${num(metrics?.legacyNewJobs)} 不计入`}
            values={miniValues('newJobs')}
            color="var(--v3-accent)"
          />
          <section className="oc-card flex min-h-[142px] flex-col justify-between rounded-[18px] border p-4 shadow-[var(--shadow)]" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface)' }}>
            <div className="flex items-start justify-between gap-2">
              <p className="text-xs font-medium" style={{ color: 'var(--v3-muted)' }}>今日首次沟通额度</p>
              <span className="rounded-full border px-2 py-0.5 text-[10px]" style={{ borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }}>今日</span>
            </div>
            <div className="mt-2 flex items-baseline gap-1">
              <span className="font-serif text-[42px] leading-none tabular-nums tracking-[-0.04em]">{num(todayBudget?.consumed)}</span>
              <span className="text-sm" style={{ color: 'var(--v3-muted)' }}>/ {num(todayBudget?.limit)}</span>
            </div>
            <p className="mt-2 text-[11px]" style={{ color: 'var(--v3-muted)' }}>剩余 {num(todayBudget?.remaining)} · 不随范围变化</p>
            <div className="mt-2 h-1.5 overflow-hidden rounded-full" style={{ backgroundColor: 'var(--v3-line)' }}>
              <div className="h-full rounded-full transition-[width] duration-700" style={{ width: `${budgetRatio * 100}%`, background: 'linear-gradient(90deg, var(--v3-accent), var(--accent-mid))' }} />
            </div>
          </section>
        </div>
      )}

      {isEmpty ? (
        <div className="rounded-[14px] border border-dashed px-4 py-5 text-sm" style={{ borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }}>所选范围暂无统计数据：可能尚未运行投递任务或采集来源未记录。</div>
      ) : null}

      {/* 趋势 + 处理流水 */}
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1.55fr)_minmax(0,1fr)]">
        <section className="oc-card rounded-[20px] border bg-[var(--surface)] p-4 shadow-[var(--shadow)]" style={{ borderColor: 'var(--v3-line)' }}>
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div>
              <h2 className="text-[15px] font-semibold">指标趋势</h2>
              <p className="mt-1 text-xs" style={{ color: 'var(--v3-muted)' }}>{rangeLabel} · {rangeDays} 天 · {activeMetricLabel}</p>
            </div>
            <Link href={overview?.links?.attempts || '/boss/ledger?entity=attempts'} className="text-[11px] underline underline-offset-2" style={{ color: 'var(--v3-muted)' }}>下钻投递明细 →</Link>
          </div>

          <div className="mt-3 flex flex-wrap gap-1.5" role="group" aria-label="趋势指标">
            {METRIC_OPTIONS.map((option) => (
              <button
                key={option.value}
                type="button"
                aria-pressed={metric === option.value}
                onClick={() => setMetric(option.value)}
                className="rounded-full border px-2.5 py-1 text-[11px] font-medium transition-colors"
                style={metric === option.value ? { borderColor: 'var(--v3-accent)', color: 'var(--v3-accent)', backgroundColor: 'var(--accent-soft)' } : { borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }}
              >
                {option.label}
              </button>
            ))}
          </div>

          <div className="mt-3 grid grid-cols-4 gap-2 rounded-[12px] px-3 py-2" style={{ backgroundColor: 'var(--surface2)' }}>
            {[
              { label: '合计', value: seriesStats.total, sub: rangeDays ? `${rangeDays} 天` : '' },
              { label: '峰值', value: seriesStats.peak, sub: shortDate(seriesStats.peakDate) },
              { label: '日均', value: seriesStats.average.toFixed(0), sub: '每天' },
              { label: '最新', value: seriesStats.latest, sub: points.length ? shortDate(points[points.length - 1]?.date) : '' },
            ].map((item) => (
              <div key={item.label} className="border-r pl-3 first:pl-0 last:border-r-0" style={{ borderColor: 'var(--v3-line)' }}>
                <p className="text-[10px]" style={{ color: 'var(--v3-muted)' }}>{item.label}</p>
                <p className="mt-0.5 text-lg font-semibold tabular-nums leading-tight">{item.value}</p>
                <p className="truncate text-[10px]" style={{ color: 'var(--v3-muted)' }}>{item.sub || '—'}</p>
              </div>
            ))}
          </div>

          <div data-slot="chart-card" className="mt-3 min-h-[256px] w-full">
            {seriesError ? (
              <p className="py-10 text-center text-sm" style={{ color: 'var(--v3-bad)' }}>趋势数据加载失败：{seriesError}</p>
            ) : seriesLoading && !series ? (
              <Skeleton height={224} radius={14} />
            ) : points.length === 0 || noSeries ? (
              <p className="py-12 text-center text-sm" style={{ color: 'var(--v3-muted)' }}>该范围内没有记录，不绘制趋势。</p>
            ) : singleDay ? (
              <div className="flex flex-col items-center justify-center gap-2 py-10">
                <p className="text-xs" style={{ color: 'var(--v3-muted)' }}>{points[0]?.date} · {activeMetricLabel}</p>
                <p className="font-serif text-5xl tabular-nums">{num(points[0]?.value)}</p>
                <button type="button" onClick={() => applyPreset('7d')} className="text-xs underline underline-offset-2" style={{ color: 'var(--v3-accent)' }}>查看近 7 天趋势 →</button>
              </div>
            ) : (
              <IncrementTrendChart
                labels={points.map((point) => point.date.slice(5))}
                series={[{ key: metric, name: activeMetricLabel, values: points.map((point) => num(point.value)) }]}
                activeKey={metric}
                animationKey={points.length}
                showDataTable={false}
                showPeak
                showAverage
              />
            )}
          </div>
        </section>

        <section className="oc-card rounded-[20px] border bg-[var(--surface)] p-4 shadow-[var(--shadow)]" style={{ borderColor: 'var(--v3-line)' }}>
          <div className="flex items-start justify-between gap-2">
            <div>
              <h2 className="text-[15px] font-semibold">处理流水</h2>
              <p className="mt-1 text-xs" style={{ color: 'var(--v3-muted)' }}>各环节自身结果占比 · {rangeLabel}</p>
            </div>
          </div>
          <div className="mt-1">
            {flowStages.map((stage) => <StackedFlow key={stage.title} {...stage} />)}
          </div>
          <p className="mt-2 text-[10px] leading-4" style={{ color: 'var(--v3-muted)' }}>各环节只显示自身占比，不做跨环节转化率。拒绝与复核是正常判定，只有发送失败才使用红色。</p>
        </section>
      </div>

      {/* 采集概况 + 明细 */}
      <div className="grid gap-4 xl:grid-cols-[minmax(260px,.72fr)_minmax(0,1.28fr)]">
        <section className="oc-card rounded-[20px] border bg-[var(--surface)] p-4 shadow-[var(--shadow)]" style={{ borderColor: 'var(--v3-line)' }}>
          <h2 className="text-[15px] font-semibold">采集概况</h2>
          <div className="mt-3 grid grid-cols-2 gap-2">
            <CollectionCell label="采集来源" value={num(collectionMetrics?.keywords)} suffix="组" sub={`去重岗位 ${num(collectionMetrics?.uniqueJobs)}`} />
            <CollectionCell label="日均采集" value={rangeDays > 0 ? Math.round(num(metrics?.newJobs) / rangeDays) : 0} suffix="个 / 天" sub={`近 ${rangeDays} 天`} />
            <CollectionCell label="旧数据" value={num(metrics?.legacyNewJobs)} suffix="条" sub="缺 first_seen_at，不计入" />
            <CollectionCell label="跟进待办" value={num(followups?.suggested)} suffix="条" sub={`待批准 ${num(followups?.approvedPending)}`} />
            <CollectionCell label="采集与投递" value={num(collectionMetrics?.sourceRecords)} suffix="条来源" sub={`已投 ${num(metrics?.textConfirmed)}`} />
            <CollectionCell label="过滤率" value={percent(ratio(rule?.reject, rule?.total))} sub={`${num(rule?.reject)} / ${num(rule?.total)}`} />
          </div>
          {collectionError ? <p className="mt-2 text-xs" style={{ color: 'var(--v3-muted)' }}>采集来源加载失败：{collectionError}</p> : null}
        </section>

        <section className="oc-card rounded-[20px] border bg-[var(--surface)] p-4 shadow-[var(--shadow)]" style={{ borderColor: 'var(--v3-line)' }}>
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div>
              <h2 className="text-[15px] font-semibold">明细</h2>
              <p className="mt-1 text-xs" style={{ color: 'var(--v3-muted)' }}>当前指标列：{activeMetricLabel} · {rangeLabel}</p>
            </div>
            <Segmented
              items={[{ value: 'daily', label: '按日' }, { value: 'keyword', label: '按关键词' }]}
              value={detailTab}
              onChange={setDetailTab}
              ariaLabel="明细切换"
            />
          </div>

          {collectionLoading && !collection ? (
            <Skeleton height={180} radius={14} />
          ) : detailTab === 'daily' ? (
            <div className="mt-3">
              {dailyRows.length === 0 ? (
                <p className="py-10 text-center text-sm" style={{ color: 'var(--v3-muted)' }}>该范围内没有采集按日记录。</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[520px] text-sm">
                    <thead>
                      <tr className="border-b text-left text-[11px]" style={{ borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }}>
                        <th className="px-2 py-2 font-medium">日期</th>
                        <th className="px-2 py-2 font-medium text-right">新发现岗位</th>
                        <th className="px-2 py-2 font-medium text-right">来源记录</th>
                        <th className="px-2 py-2 font-medium text-right">当前指标</th>
                      </tr>
                    </thead>
                    <tbody>
                      {dailyRows.slice(-8).reverse().map((item: CollectionDailyItem) => {
                        const activeValue = points.find((point) => point.date === item.date)?.value
                        return (
                          <tr key={item.date} className="border-b last:border-b-0" style={{ borderColor: 'var(--v3-line)' }}>
                            <td className="px-2 py-2 text-xs tabular-nums">{item.date}</td>
                            <td className="px-2 py-2 text-right tabular-nums">{num(item.newJobs)}</td>
                            <td className="px-2 py-2 text-right tabular-nums">{num(item.sourceRecords)}</td>
                            <td className="px-2 py-2 text-right tabular-nums" style={{ color: 'var(--v3-accent)' }}>{activeValue === undefined ? '—' : num(activeValue)}</td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                  {hiddenZeroDays > 0 ? <p className="mt-2 text-[10px]" style={{ color: 'var(--v3-muted)' }}>另有 {hiddenZeroDays} 天无记录，已折叠。</p> : null}
                </div>
              )}
            </div>
          ) : (
            <div className="mt-3 overflow-x-auto">
              {keywordRows.length === 0 ? (
                <p className="py-10 text-center text-sm" style={{ color: 'var(--v3-muted)' }}>该范围内没有关键词来源记录。</p>
              ) : (
                <table className="w-full min-w-[600px] text-sm">
                  <thead>
                    <tr className="border-b text-left text-[11px]" style={{ borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }}>
                      <th className="px-2 py-2 font-medium">关键词</th>
                      <th className="px-2 py-2 text-right font-medium">来源记录</th>
                      <th className="px-2 py-2 text-right font-medium">去重岗位</th>
                      <th className="px-2 py-2 text-right font-medium">最后发现</th>
                    </tr>
                  </thead>
                  <tbody>
                    {keywordRows.map((item: CollectionKeywordItem, index) => (
                      <tr key={`${item.keyword ?? 'unknown'}-${index}`} className="border-b last:border-b-0" style={{ borderColor: 'var(--v3-line)' }}>
                        <td className="max-w-[240px] px-2 py-2 text-xs">{item.keyword || '（未记录）'}</td>
                        <td className="px-2 py-2 text-right tabular-nums">{num(item.sourceRecords)}</td>
                        <td className="px-2 py-2 text-right tabular-nums">{num(item.uniqueJobs)}</td>
                        <td className="whitespace-nowrap px-2 py-2 text-right text-xs" style={{ color: 'var(--v3-muted)' }}>{item.lastDiscoveredAt?.slice(5, 16) || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}
          <p className="mt-2 text-[10px]" style={{ color: 'var(--v3-muted)' }}>口径：范围含首尾日期；来源记录按 job_source，去重岗位按 encrypt_id。</p>
        </section>
      </div>

      <Drawer
        open={definitionsOpen}
        onClose={() => setDefinitionsOpen(false)}
        title="口径说明"
        description="只读统计口径；未知与 legacy 单独统计，不并入成功数。"
        width={480}
      >
        <div className="space-y-3 text-sm leading-6">
          <p className="rounded-[10px] px-3 py-2 text-xs" style={{ backgroundColor: 'var(--surface2)', color: 'var(--v3-muted)' }}>
            范围按本地时区（含首尾日期）；下钻链接会带当前范围参数。未知结果按琥珀色单列，不显示成功色。
          </p>
          {Object.entries(definitions).length === 0 ? (
            <p className="text-sm" style={{ color: 'var(--v3-muted)' }}>暂无口径说明。</p>
          ) : Object.entries(definitions).map(([key, text]) => (
            <div key={key}>
              <p className="text-xs font-medium" style={{ color: 'var(--text)' }}>{key}</p>
              <p className="text-xs leading-5" style={{ color: 'var(--v3-muted)' }}>{quotaText(text)}</p>
            </div>
          ))}
        </div>
      </Drawer>
    </div>
  )
}
