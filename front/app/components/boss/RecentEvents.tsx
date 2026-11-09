'use client'

import Link from 'next/link'

import { StatusTag } from '@/components/ui/status-tag'
import { pipelineOutcomeLabel, pipelineStageLabel } from '@/app/components/boss-shared'
import type { PipelineEvent } from '@/lib/boss-types'

/**
 * 最近事件（v2）：8 行、行高 36、无表格线（仅底部 1px --line）。
 *
 * 列：时间(mono) / 阶段 / StatusTag / 岗位 / 公司(--text-3)。
 * 结果一律走 StatusTag（圆点 + 浅底），不再用「纯文字变色」表达状态。
 */

function formatClock(timestamp?: number | null): string {
  if (typeof timestamp !== 'number' || !Number.isFinite(timestamp) || timestamp <= 0) return '—'
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
}

function toneOf(outcome?: string | null): 'ok' | 'warn' | 'bad' | 'idle' {
  switch (outcome) {
    case 'CONFIRM':
    case 'CONFIRMED':
    case 'DELIVERED':
    case 'SENT':
    case 'PASS':
      return 'ok'
    case 'REJECT':
    case 'REJECTED':
    case 'FAIL':
    case 'FAILED':
    case 'ERROR':
      return 'bad'
    case 'UNKNOWN':
    case 'REVIEW':
    case 'PENDING':
      return 'warn'
    default:
      return 'idle'
  }
}

export function RecentEvents({ events }: { events: PipelineEvent[] }) {
  const rows = events.slice(0, 8)

  return (
    <section aria-label="最近事件">
      <div className="flex items-baseline justify-between gap-3">
        <h2 className="text-xs font-medium tracking-[0.08em] text-[hsl(var(--text-3))]">最近事件</h2>
        <span className="text-xs text-[hsl(var(--text-3))]">
          最近 {rows.length} 条{rows.length === 0 ? '' : ''}
        </span>
      </div>

      <div className="mt-2">
        <div className="grid grid-cols-[64px_80px_88px_1fr_120px] gap-3 border-b border-[hsl(var(--border))] pb-1.5 text-xs text-[hsl(var(--text-3))]">
          <span>时间</span>
          <span>阶段</span>
          <span>结果</span>
          <span>岗位</span>
          <span>公司</span>
        </div>

        {rows.length === 0 ? (
          <p className="py-6 text-xs text-[hsl(var(--text-3))]">
            等待第一轮任务 —— 点「开始投递」后，这里按阶段实时出现流水
          </p>
        ) : (
          rows.map((event, index) => (
            <div
              key={`${event.timestamp}-${index}`}
              className="animate-row-in grid h-9 grid-cols-[64px_80px_88px_1fr_120px] items-center gap-3 border-b border-[hsl(var(--line))] text-sm last:border-0"
              style={{ animationDelay: `${Math.min(index, 7) * 35}ms` }}
            >
              <span className="truncate font-mono text-xs text-[hsl(var(--text-3))]">{formatClock(event.timestamp)}</span>
              <span className="truncate text-xs text-[hsl(var(--text-2))]">
                {pipelineStageLabel(event.stage, event.stageLabel)}
              </span>
              <span>
                <StatusTag tone={toneOf(event.outcome)}>{pipelineOutcomeLabel(event.outcome)}</StatusTag>
              </span>
              <span className="truncate text-[hsl(var(--foreground))]" title={event.jobName ?? ''}>
                {event.jobName || '—'}
              </span>
              <span className="truncate text-xs text-[hsl(var(--text-3))]" title={event.companyName ?? ''}>
                {event.companyName || '—'}
              </span>
            </div>
          ))
        )}
      </div>

      <div className="mt-2 flex items-center justify-between text-xs text-[hsl(var(--text-3))]">
        <span>共 {events.length} 条流水</span>
        <Link href="/boss/flow" className="font-medium text-[hsl(var(--brand-deep))] hover:underline">
          查看全部 →
        </Link>
      </div>
    </section>
  )
}
