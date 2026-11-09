'use client'

import { PIPELINE_STAGES, pipelineOutcomeLabel } from '@/app/components/boss-shared'
import type { PipelineSnapshot } from '@/lib/boss-types'

/**
 * 阶段带（v2）：9 格 01→09 顺序，每格「编号 / 名称 / 进入数 / 细横条 / 结果子行」。
 *
 * 两条硬规则：
 * 1. **顺序永远是 01→09**，不因为当前阶段而在视觉上重排（上一版把当前格提到第一位是错的：
 *    用户学不到流程顺序，且每轮任务阶段不同会让整条带子跳动）。
 * 2. 横条宽度 = 该阶段进入数 ÷ 首格进入数；首格为 0 时不画条。
 * 当前格只用 `--brand-soft` 底 + 顶部 2px 线标记，不做位移动画。
 */

const BAR_COLOR = (entered: number, first: number) => {
  if (first <= 0 || entered <= 0) return 'transparent'
  const ratio = entered / first
  if (ratio >= 0.99) return '#8A3E22'
  if (ratio >= 0.6) return '#B85C38'
  if (ratio >= 0.35) return '#D49A6C'
  if (ratio >= 0.2) return '#E4BE96'
  return '#F1DEC6'
}

/** 子行用的短标签：1600 下每格只有 ~120px，全称会折行 */
const SHORT: Record<string, string> = {
  DELIVERED: '已投',
  CONFIRM: '已确认',
  CONFIRMED: '已确认',
  REJECT: '拒绝',
  REJECTED: '拒绝',
  UNKNOWN: '未知',
  FAIL: '失败',
  FAILED: '失败',
  SKIP: '跳过',
  SKIPPED: '跳过',
  PASS: '通过',
  REVIEW: '复核',
  RUNNING: '进行中',
  PENDING: '待确认',
}

function outcomeLine(outcomes?: Record<string, number> | null) {
  if (!outcomes) return []
  return Object.entries(outcomes).filter(([, count]) => (count ?? 0) > 0)
}

export function StageBand({ pipeline, loading }: { pipeline: PipelineSnapshot | null; loading: boolean }) {
  const stages = pipeline?.stages ?? []
  const statOf = (code: string, label: string) =>
    stages.find((item) => item.stage === code) ?? stages.find((item) => item.label === label)
  const first = statOf(PIPELINE_STAGES[0].code, PIPELINE_STAGES[0].label)?.entered ?? 0
  const totalEntered = stages.reduce((sum, item) => sum + (typeof item.entered === 'number' ? item.entered : 0), 0)

  return (
    <section aria-label="9 阶段流程" className="bg-[hsl(var(--sunken))] px-5 py-[18px] max-[1439px]:py-3">
      <div className="mb-2.5 flex items-baseline justify-between">
        <h2 className="text-xs font-medium tracking-[0.08em] text-[hsl(var(--text-3))]">投递流程</h2>
        <span className="text-xs text-[hsl(var(--text-3))]">
          9 阶段 · 本轮累计进入 <span className="tabular-nums text-[hsl(var(--text-2))]">{totalEntered}</span>
        </span>
      </div>

      <ol className="grid grid-cols-5 gap-2 min-[1440px]:grid-cols-9">
        {PIPELINE_STAGES.map((stage, index) => {
          const stat = statOf(stage.code, stage.label)
          const entered = loading && !pipeline ? 0 : (stat?.entered ?? 0)
          const isCurrent = pipeline?.currentStage === stage.code
          const allOutcomes = outcomeLine(stat?.outcomes)
          const outs = allOutcomes.slice(0, 2)
          const hiddenOutcomeCount = allOutcomes.length - outs.length
          return (
            <li
              key={stage.code}
              aria-current={isCurrent ? 'step' : undefined}
              className="animate-dashboard-reveal min-w-0 rounded-[4px] border border-[hsl(var(--border))] px-2 py-1.5 max-[1439px]:py-1"
              style={{
                backgroundColor: isCurrent ? 'hsl(var(--brand-tint))' : 'hsl(var(--card))',
                animationDelay: `${index * 35}ms`,
              }}
            >
              {isCurrent ? (
                <span
                  aria-hidden="true"
                  className="mb-1 block h-[2px] w-full rounded-full"
                  style={{ backgroundColor: 'hsl(var(--brand))' }}
                />
              ) : (
                <span aria-hidden="true" className="mb-1 block h-[2px] w-full" />
              )}
              <p className={`font-mono text-xs ${isCurrent ? 'text-[hsl(var(--text-2))]' : 'text-[hsl(var(--text-3))]'}`}>{String(index + 1).padStart(2, '0')}</p>
              <p className="truncate text-xs text-[hsl(var(--text-2))]" title={stage.label}>
                {stage.label}
              </p>
              <p className="mt-0.5 font-serif text-[26px] leading-none tabular-nums text-[hsl(var(--foreground))]">
                {entered}
              </p>
              <span
                aria-hidden="true"
                className="animate-stage-bar mt-1 block h-[3px] rounded-full"
                style={{
                  width: first > 0 ? `${Math.max(4, Math.round((entered / first) * 100))}%` : '0%',
                  backgroundColor: BAR_COLOR(entered, first),
                  animationDelay: `${180 + index * 35}ms`,
                }}
              />
              <p
                className={`mt-1 min-h-[16px] truncate whitespace-nowrap text-xs ${isCurrent ? 'text-[hsl(var(--text-2))]' : 'text-[hsl(var(--text-3))]'}`}
                title={
                  allOutcomes.length
                    ? allOutcomes.map(([key, count]) => `${pipelineOutcomeLabel(key)} ${count}`).join(' · ')
                    : ''
                }
              >
                {outs.length
                  ? outs.map(([key, count], i) => (
                      <span key={key}>
                        {i > 0 ? ' · ' : ''}
                        <span className={key === 'REJECT' || key === 'REJECTED' || key === 'FAIL' || key === 'FAILED' ? 'text-[hsl(var(--bad-text))]' : ''}>
                          {SHORT[key] ?? pipelineOutcomeLabel(key)} {count}
                        </span>
                      </span>
                    ))
                  : '—'}
                {hiddenOutcomeCount > 0 ? (
                  <span title={allOutcomes.map(([key, count]) => `${pipelineOutcomeLabel(key)} ${count}`).join(' · ')}>
                    {' '}· +{hiddenOutcomeCount}
                  </span>
                ) : null}
              </p>
            </li>
          )
        })}
      </ol>
    </section>
  )
}
