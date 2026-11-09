import * as React from 'react'

import { cn } from '@/lib/utils'

/**
 * StatusTag —— v2 状态标签（暖纸色系）。
 *
 * 高 22px、圆角 4、12px/500、**6px 圆点 + 浅底 + 同色系深字**。
 * 设计意图：状态永远是「小色块 + 圆点」，不允许再出现「只有文字变色」的状态——
 * 纯文字变色在长表格里扫不动，且对色弱用户不可辨。
 */

const TONE: Record<'ok' | 'warn' | 'bad' | 'idle', string> = {
  ok: 'border-[hsl(var(--ok)/.28)] bg-[hsl(var(--ok-bg))] text-[hsl(var(--ok-text))]',
  warn: 'border-[hsl(var(--warn)/.3)] bg-[hsl(var(--warn-bg))] text-[hsl(var(--warn-text))]',
  bad: 'border-[hsl(var(--bad)/.28)] bg-[hsl(var(--bad-bg))] text-[hsl(var(--bad-text))]',
  // 文字用 --idle-text（≥4.6:1）。--idle 对 idle-bg 仅 3.2:1（浅）/ 3.6:1（深），只适合圆点。
  idle: 'border-[hsl(var(--border-strong)/.6)] bg-[hsl(var(--idle-bg))] text-[hsl(var(--idle-text))]',
}

const DOT: Record<'ok' | 'warn' | 'bad' | 'idle', string> = {
  ok: 'bg-[hsl(var(--ok))]',
  warn: 'bg-[hsl(var(--warn))]',
  bad: 'bg-[hsl(var(--bad))]',
  idle: 'bg-[hsl(var(--idle))]',
}

export function StatusTag({
  tone = 'idle',
  children,
  className,
}: {
  tone?: 'ok' | 'warn' | 'bad' | 'idle'
  children: React.ReactNode
  className?: string
}) {
  return (
    <span
      data-slot="status-tag"
      data-nodot
      data-tone={tone === 'ok' ? 'ok' : tone === 'warn' ? 'warn' : tone === 'bad' ? 'bad' : 'muted'}
      className={cn(
        'oc-chip inline-flex h-[22px] items-center gap-1.5 rounded-[4px] border px-1.5 text-xs font-medium',
        TONE[tone],
        className
      )}
    >
      <span aria-hidden="true" className={cn('size-1.5 rounded-full', DOT[tone])} />
      {children}
    </span>
  )
}
