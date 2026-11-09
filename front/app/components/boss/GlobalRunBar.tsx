'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useEffect, useState } from 'react'

import { getBossStatus } from '@/lib/api'
import type { BossStatus } from '@/lib/boss-types'
import { formatSeconds } from '@/lib/use-boss-status'

type Tone = 'ok' | 'warn' | 'bad' | 'idle'

const TONE_DOT: Record<Tone, string> = {
  ok: 'bg-[hsl(var(--ok))]',
  warn: 'bg-[hsl(var(--warn))]',
  bad: 'bg-[hsl(var(--bad))]',
  idle: 'bg-[hsl(var(--idle))]',
}

const TONE_TEXT: Record<Tone, string> = {
  ok: 'text-[hsl(var(--ok-text))]',
  warn: 'text-[hsl(var(--warn-text))]',
  bad: 'text-[hsl(var(--bad-text))]',
  idle: 'text-[hsl(var(--idle-text))]',
}

function stateView(status: BossStatus | null): { tone: Tone; label: string; detail: string } {
  if (!status) return { tone: 'idle', label: '状态读取中', detail: '' }
  const state = status.state
  const running = state === 'RUNNING' || state === 'STARTING'
  if (state === 'PAUSED_VERIFICATION') {
    return { tone: 'warn', label: '等待人工验证', detail: '需要处理' }
  }
  if (state === 'FAILED') return { tone: 'bad', label: '失败', detail: '查看总览' }
  if (state === 'STOPPING') return { tone: 'idle', label: '停止中', detail: '' }
  if (running) {
    return { tone: 'ok', label: '运行中', detail: formatSeconds(status.runningSeconds) }
  }
  if (state === 'COMPLETED') return { tone: 'ok', label: '已完成', detail: '' }
  return { tone: 'idle', label: '空闲', detail: '' }
}

/**
 * 全局运行条：在除总览以外的 BOSS 页面常驻显示任务状态与待核对队列。
 *
 * 它只做「看见状态、回到处理」；不复制开始/停止动作，避免和总览页的
 * RunControlCard 产生两套状态机。30 秒轮询一次，失败静默降级。
 */
export function GlobalRunBar() {
  const pathname = usePathname()
  const visible = Boolean(pathname?.startsWith('/boss') && pathname !== '/boss')
  const [status, setStatus] = useState<BossStatus | null>(null)

  useEffect(() => {
    if (!visible) return
    let cancelled = false
    const refresh = () => {
      getBossStatus()
        .then((next) => {
          if (!cancelled) setStatus(next)
        })
        .catch(() => {
          // 状态条不是关键路径，失败时保持上一次值
        })
    }
    refresh()
    const timer = window.setInterval(refresh, 30_000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [visible])

  if (!visible) return null

  const view = stateView(status)
  const pending = status?.pendingResolutions ?? 0

  return (
    <div
      role="status"
      aria-live="polite"
      data-slot="global-run-bar"
      className="sticky top-0 z-30 -mx-8 mb-6 border-b border-hairline bg-[hsl(var(--background)/0.94)] px-8 py-2 backdrop-blur"
    >
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
        <span className="flex items-center gap-1.5 font-medium">
          <span aria-hidden="true" className={`size-1.5 rounded-full ${TONE_DOT[view.tone]} ${status?.state === 'RUNNING' || status?.state === 'STARTING' ? 'animate-live-dot' : ''}`} />
          <span className={TONE_TEXT[view.tone]}>{view.label}</span>
        </span>
        {view.detail ? <span className="tabular-nums text-[hsl(var(--text-3))]">{view.detail}</span> : null}
        <span className="text-[hsl(var(--text-4))]">·</span>
        <span className="text-[hsl(var(--text-3))]">
          待人工核对 <span className={pending > 0 ? 'font-medium tabular-nums text-[hsl(var(--warn-text))]' : 'tabular-nums'}>{pending}</span>
        </span>
        {pending > 0 ? (
          <Link href="/boss/attempts?textStatus=UNKNOWN" className="font-medium text-[hsl(var(--brand-deep))] hover:underline">
            去处理 →
          </Link>
        ) : (
          <span className="text-[hsl(var(--text-3))]">暂无待处理</span>
        )}
        {status?.state === 'FAILED' ? (
          <span className="text-[hsl(var(--bad-text))]">最近一次任务失败，请回到总览查看原因</span>
        ) : null}
        <Link href="/boss" className="ml-auto font-medium text-[hsl(var(--brand-deep))] hover:underline">
          回总览
        </Link>
      </div>
    </div>
  )
}
