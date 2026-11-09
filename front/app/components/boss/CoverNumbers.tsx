'use client'

import Link from 'next/link'
import { CountUp } from './CountUp'

/**
 * KPI 三联（v2 暖纸色系）：今日已投递 / 待人工核对 / 首次沟通预算。
 *
 * v2 取消了 v1 的两块彩色渐变面板——色块会把「数字」的视觉权重让给「背景」，
 * 而这一屏的主角就是三个数字。改回排版：不等宽三列 + 列间 1px 渐隐竖线。
 * 数字用衬线（`font-serif`）56px，只有「待人工核对」用 warn 色，其余为焦糖墨。
 */

function Divider() {
  return (
    <span
      aria-hidden="true"
      className="absolute inset-y-0 right-0 w-px"
      style={{
        background:
          'linear-gradient(180deg, transparent, hsl(var(--border-strong)) 18%, hsl(var(--border-strong)) 82%, transparent)',
      }}
    />
  )
}

export function CoverNumbers({
  delivered,
  pending,
  budgetUsed,
  budgetLimit,
  budgetReserved,
  budgetUncertain,
  budgetRemaining,
  textConfirmed,
  imageConfirmed,
  deltaVsYesterday,
  idle,
}: {
  delivered: number
  pending: number
  budgetUsed: number
  budgetLimit?: number | null
  budgetReserved?: number | null
  budgetUncertain?: number | null
  budgetRemaining?: number | null
  textConfirmed: number
  imageConfirmed: number
  /** 较昨日差（正数为增），null 表示无对比数据 */
  deltaVsYesterday: number | null
  idle: boolean
}) {
  const limit = typeof budgetLimit === 'number' && budgetLimit > 0 ? budgetLimit : 0
  const reserved = budgetReserved ?? 0
  const uncertain = budgetUncertain ?? 0
  const remaining = budgetRemaining ?? Math.max(0, limit - budgetUsed - reserved - uncertain)
  const pct = (n: number) => (limit > 0 ? `${Math.max(0, Math.min(100, (n / limit) * 100))}%` : '0%')

  return (
    <section aria-label="今日关键指标" className="grid grid-cols-1 gap-y-8 min-[1200px]:grid-cols-[1.1fr_.95fr_1.45fr] min-[1200px]:gap-y-0">
      {/* ① 今日已投递 */}
      <div className="relative pr-8">
        <p className="font-serif text-[56px] leading-none text-[hsl(var(--foreground))]">
          <CountUp value={delivered} />
        </p>
        <p className="mt-2 text-sm text-[hsl(var(--text-2))]">今日已投递{idle ? ' · 尚未开始' : ''}</p>
        <p className="mt-1 text-xs text-[hsl(var(--text-3))]">
          文字确认 {textConfirmed} · 图片确认 {imageConfirmed}
          {deltaVsYesterday !== null ? ` · 较昨日 ${deltaVsYesterday >= 0 ? '+' : ''}${deltaVsYesterday}` : ''}
        </p>
        <Divider />
      </div>

      {/* ② 待人工核对 */}
      <div className="relative px-8">
        <p className="font-serif text-[56px] leading-none text-[hsl(var(--warn-text))]">
          <CountUp value={pending} />
        </p>
        <p className="mt-2 text-sm text-[hsl(var(--text-2))]">待人工核对</p>
        <p className="mt-1 text-xs text-[hsl(var(--text-3))]">结果未知且未裁定 · 累计未处理</p>
        {pending > 0 ? (
          <Link
            href="/boss/attempts?textStatus=UNKNOWN"
            className="mt-1 inline-block text-xs font-medium text-[hsl(var(--brand-deep))] hover:underline"
          >
            去核对 →
          </Link>
        ) : (
          <p className="mt-1 text-xs text-[hsl(var(--text-3))]">无需处理</p>
        )}
        <Divider />
      </div>

      {/* ③ 首次沟通预算 */}
      <div className="min-w-0 pl-8">
        <p className="flex items-baseline gap-1">
          <span className="font-serif text-[56px] leading-none text-[hsl(var(--foreground))]">
            <CountUp value={budgetUsed} />
          </span>
          <span className="text-xl leading-none tabular-nums text-[hsl(var(--text-3))]">/{limit || '—'}</span>
        </p>
        <p className="mt-2 text-sm text-[hsl(var(--text-2))]">
          首次沟通预算 <span className="text-[hsl(var(--text-3))]">· 今日</span>
        </p>

        <div className="mt-2 flex h-1.5 w-full overflow-hidden rounded-full bg-[hsl(var(--line))]">
          <span className="transition-[width] duration-700 ease-out" style={{ width: pct(budgetUsed), backgroundColor: 'hsl(var(--brand))' }} />
          <span className="transition-[width] duration-700 ease-out" style={{ width: pct(reserved), backgroundColor: 'hsl(var(--seq-3))' }} />
          <span className="transition-[width] duration-700 ease-out" style={{ width: pct(uncertain), backgroundColor: 'hsl(var(--seq-2))' }} />
        </div>
        <p className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-[hsl(var(--text-3))]">
          <span>已用 {budgetUsed}</span>
          <span>预留 {reserved}</span>
          <span>不确定 {uncertain}</span>
          <span>剩余 {remaining}</span>
        </p>
      </div>
    </section>
  )
}
