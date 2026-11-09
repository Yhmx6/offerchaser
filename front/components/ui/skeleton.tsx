import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * Skeleton —— 设计系统 8.2「加载态」
 * 表格：表头照常渲染 + 5 行 40px 骨架；卡片：2–4 个骨架块（label 12×80 + value 24×120）。
 * 只有 opacity 0.4↔1 的 1.4s 呼吸，遵守 prefers-reduced-motion（globals.css 已全局处理）。
 */
const bar = "animate-pulse rounded-tag bg-surface-inset opacity-70"

/** 表格骨架行：放进 <tbody>，列数由 cols 决定 */
export function SkeletonRows({ rows = 5, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <>
      {Array.from({ length: rows }).map((_, rowIndex) => (
        <tr key={rowIndex} className="h-10 border-b border-hairline last:border-0">
          {Array.from({ length: cols }).map((__, colIndex) => (
            <td key={colIndex} className="px-3">
              <div
                className={cn(bar, "h-3")}
                style={{ width: colIndex === 0 ? "60%" : "40%" }}
              />
            </td>
          ))}
        </tr>
      ))}
    </>
  )
}

/** 卡片骨架：label 12×80 + value 24×120，可重复 count 组 */
export function SkeletonCard({
  count = 3,
  className,
}: {
  count?: number
  className?: string
}) {
  return (
    <div className={cn("space-y-4 p-5", className)} aria-busy="true" aria-live="polite">
      {Array.from({ length: count }).map((_, index) => (
        <div key={index} className="space-y-2">
          <div className={cn(bar, "h-3 w-20")} />
          <div className={cn(bar, "h-6 w-32")} />
        </div>
      ))}
    </div>
  )
}

/** 单块骨架（给定宽高） */
export function SkeletonBlock({ className }: { className?: string }) {
  return <div className={cn(bar, "h-3 w-full", className)} />
}
