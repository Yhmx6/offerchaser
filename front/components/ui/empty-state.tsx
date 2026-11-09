import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * EmptyState —— 设计系统 D.8
 * 左对齐、限宽 480px、虚线容器、两行文案（结论 + 下一步），无插画、无 min-height。
 * 对齐左侧是关键：居中会让眼睛失去锚点，且与表头/卡头文字不对齐。
 */
export function EmptyState({
  title,
  description,
  icon,
  action,
  className,
}: {
  /** 结论行：≤ 14 字，font-medium */
  title: React.ReactNode
  /** 下一步动作行：含 1 个文字链接或按钮 */
  description?: React.ReactNode
  /** 可选 16px 图标（不做圆形底、不做渐变） */
  icon?: React.ReactNode
  action?: React.ReactNode
  className?: string
}) {
  return (
    <div
      className={cn(
        "max-w-[480px] rounded-card border border-dashed border-clay-400/60 px-4 py-5",
        className
      )}
    >
      <p className="flex items-center gap-2 text-base font-medium text-ink-800 dark:text-sand-200">
        {icon ? <span className="shrink-0 text-clay-500">{icon}</span> : null}
        {title}
      </p>
      {description ? (
        <p className="mt-1.5 text-sm leading-5 text-clay-600 dark:text-clay-500">
          {description}
        </p>
      ) : null}
      {action ? <div className="mt-3">{action}</div> : null}
    </div>
  )
}
