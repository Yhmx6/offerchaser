'use client'

import { Card, CardContent, CardHeader } from '@/components/ui/card'
import {
  PIPELINE_STAGES,
  pipelineOutcomeBadgeClassQuiet,
  pipelineOutcomeLabel,
} from '@/app/components/boss-shared'
import type { PipelineSnapshot } from '@/lib/boss-types'

/**
 * PipelineStrip —— `/boss` Row B：9 阶段条带（只读）。
 *
 * 断点按主理人裁定：`grid-cols-5`（1280 折 5+4）+ `min-[1440px]:grid-cols-9`，
 * **禁止 overflow-x-auto / 固定宽度**（那是审计 A7「9 阶段被裁切」的根因）。
 * 当前阶段格用 inset 2px 左侧条 + accent 淡底（与侧栏选中态同一手法，不推挤布局）。
 */

/** 该阶段全部结果（按次数降序）；渲染时只取前 2 个，其余折成 `+N`（IA §5.2） */
/** 格子渐变：普通格极轻 180°，当前格陶土撞色渐变 */
function cellStyle(active: boolean) {
  return {
    background: active
      ? 'var(--stage-cur-bg)'
      : 'var(--stage-cell-bg)',
  }
}

function outcomeEntries(outcomes?: Record<string, number> | null) {
  if (!outcomes) return []
  return Object.entries(outcomes)
    .filter(([, count]) => typeof count === 'number' && count > 0)
    .sort((a, b) => b[1] - a[1])
}

export function PipelineStrip({
  pipeline,
  loading,
  selectable = false,
  selectedStage = null,
  onSelectStage,
}: {
  pipeline: PipelineSnapshot | null
  loading: boolean
  /** 为真时每格是可点选的 `<button>`（`/boss/flow` 用）；为假时是只读（`/boss` 用） */
  selectable?: boolean
  selectedStage?: string | null
  onSelectStage?: (code: string) => void
}) {
  const stages = pipeline?.stages ?? []
  const currentStage = pipeline?.currentStage ?? null
  /** 是否已有真实流程数据（没数据时条带退化为「地图」，不逐格重复说"0 进入"） */
  const hasData = Boolean(pipeline && (pipeline.stages?.length || pipeline.currentStage || pipeline.runId))
  /** 卡头只放聚合说明：当前阶段/岗位/公司由左栏「当前任务」独占，避免同屏三处重复 */
  const totalEntered = stages.reduce((sum, item) => sum + (typeof item.entered === 'number' ? item.entered : 0), 0)

  return (
    <Card
      style={{
        // 中段过渡：极低 alpha 的陶土→松绿横向渐变，把"大面色块"和"细线"之间的量级断层接上
        backgroundImage:
          'var(--strip-wash)',
      }}
    >
      <CardHeader className="flex-row items-center justify-between gap-3">
        <h2 className="text-lg font-semibold text-ink-900 dark:text-sand-100">投递流程</h2>
        {hasData ? (
          <span className="min-w-0 truncate text-xs text-clay-600 dark:text-clay-500">
            9 阶段 · 本轮累计进入{' '}
            <span className="tabular-nums text-ink-800 dark:text-sand-200">{totalEntered}</span>
          </span>
        ) : (
          <span className="shrink-0 text-xs text-clay-600 dark:text-clay-500">尚未启动任务</span>
        )}
      </CardHeader>

      <CardContent className="pt-4">
        <ol className="grid grid-cols-5 gap-2 min-[1440px]:grid-cols-9">
          {PIPELINE_STAGES.map((stage, index) => {
            // 后端 code 与本地 PIPELINE_STAGES 偶尔不同名（旧事件/合成数据），按 label 兜底匹配
            const stat =
              stages.find((item) => item.stage === stage.code) ??
              stages.find((item) => item.label === stage.label)
            const isCurrent = currentStage === stage.code
            const isSelected = selectedStage === stage.code
            const allOutcomes = outcomeEntries(stat?.outcomes)
            const outcomes = allOutcomes.slice(0, 2)
            const moreOutcomes = allOutcomes.length - outcomes.length

            const cellClass = `min-w-0 rounded-control border px-2 py-2 text-left ${
              isSelected || isCurrent
                ? 'border-[hsl(var(--primary)/0.4)] shadow-[inset_2px_0_0_hsl(var(--primary))]'
                : selectable
                  ? 'border-hairline hover:brightness-110'
                  : 'border-hairline'
            }`

            const body = (
              <>
                <div className="flex items-center justify-between text-xs text-clay-500">
                  <span className="font-mono">#{index + 1}</span>
                  {isCurrent ? (
                    <span aria-hidden="true" className="size-1.5 animate-pulse rounded-full bg-primary" />
                  ) : null}
                </div>
                <p
                  className={`mt-0.5 truncate text-sm ${
                    isCurrent || isSelected
                      ? 'font-medium text-ink-900 dark:text-sand-100'
                      : 'text-ink-800 dark:text-sand-200'
                  }`}
                  title={stage.label}
                >
                  {stage.label}
                </p>
                {hasData ? (
                  <>
                    <p className="mt-0.5 flex items-baseline gap-1">
                      <span className="text-lg tabular-nums text-ink-900 dark:text-sand-100">
                        {loading && !pipeline ? '—' : (stat?.entered ?? 0)}
                      </span>
                      <span className="text-xs text-clay-500">进入</span>
                    </p>
                    <div className="mt-1 flex flex-wrap gap-1">
                      {outcomes.length === 0 ? (
                        <span className="text-xs text-clay-500">—</span>
                      ) : (
                        outcomes.map(([outcome, count]) => (
                          <span
                            key={outcome}
                            className={`inline-flex h-5 items-center gap-1 rounded-tag border px-1 text-xs ${pipelineOutcomeBadgeClassQuiet(outcome)}`}
                          >
                            <span className="truncate">{pipelineOutcomeLabel(outcome)}</span>
                            <span className="tabular-nums">{count}</span>
                          </span>
                        ))
                      )}
                      {moreOutcomes > 0 ? (
                        <span className="inline-flex h-5 items-center text-xs text-clay-500">
                          +{moreOutcomes}
                        </span>
                      ) : null}
                    </div>
                  </>
                ) : (
                  <p className="mt-1 text-lg leading-6 text-clay-500">—</p>
                )}
              </>
            )

            return (
              <li key={stage.code} aria-current={isCurrent ? 'step' : undefined} className="min-w-0">
                {/* 格子渐变：普通格极轻，当前格撞色渐变（结果 chip 不用渐变——它是语义色） */}
                {selectable ? (
                  <button
                    type="button"
                    aria-pressed={isSelected}
                    onClick={() => onSelectStage?.(stage.code)}
                    className={`${cellClass} block w-full focus-visible:outline-none focus-visible:shadow-focus`}
                    style={cellStyle(isCurrent || isSelected)}
                  >
                    {body}
                  </button>
                ) : (
                  <div className={cellClass} style={cellStyle(isCurrent)}>{body}</div>
                )}
              </li>
            )
          })}
        </ol>

        {hasData ? null : (
          <p className="mt-3 text-xs leading-4 text-clay-600 dark:text-clay-500">
            启动后这里按 9 个阶段实时显示进入数与结果
          </p>
        )}
      </CardContent>
    </Card>
  )
}
