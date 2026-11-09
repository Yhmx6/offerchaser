import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * SummaryRow —— 设计系统（信息架构 §6.2 ②）：表格上方的汇总徽章行。
 *
 * 高 36px（`py-2` + 20px 行高），`bg-surface-inset` + 底部 hairline，与筛选条同宽。
 * 各段用 `·` 分隔；`warning` 段带 4px 圆点（UNKNOWN 的编码之一），`danger` 段砖红文字。
 * 右侧可放一个 action（如「只看待核对」）。
 */

export type SummaryTone = "default" | "success" | "warning" | "danger" | "muted"

export interface SummaryItem {
  label: string
  value: React.ReactNode
  tone?: SummaryTone
}

const TONE_TEXT: Record<SummaryTone, string> = {
  default: "text-ink-800 dark:text-sand-200",
  success: "text-success",
  warning: "font-semibold text-warning",
  danger: "text-danger",
  muted: "text-clay-600 dark:text-clay-500",
}

export function SummaryRow({
  items,
  action,
  className,
}: {
  items: SummaryItem[]
  action?: React.ReactNode
  className?: string
}) {
  return (
    <div
      data-slot="summary-row"
      className={cn(
        "flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-hairline bg-surface-inset px-5 py-2.5 text-xs text-clay-600 dark:text-clay-500",
        className
      )}
    >
      {items.map((item, index) => (
        <React.Fragment key={`${item.label}-${index}`}>
          {index > 0 ? (
            <span aria-hidden="true" className="text-clay-400">
              ·
            </span>
          ) : null}
          <span className="inline-flex items-center gap-1">
            {item.tone === "warning" ? (
              <span aria-hidden="true" className="size-1 rounded-full bg-warning" />
            ) : null}
            <span>{item.label}</span>
            <span className={cn("tabular-nums", TONE_TEXT[item.tone ?? "default"])}>{item.value}</span>
          </span>
        </React.Fragment>
      ))}
      {action ? <span className="ml-auto flex items-center gap-2">{action}</span> : null}
    </div>
  )
}
