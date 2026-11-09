"use client"

import * as React from "react"
import { ChevronDown } from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * FilterBar —— 设计系统 D.6「筛选区」（信息架构 §6.2 ①，修审计 A8）
 *
 * 基线不齐的根因：容器用 `items-center` + label 用 `<span block>` 且按钮塞在某个字段里。
 * 这里固定为：`flex flex-wrap items-end gap-3` —— label 在上、控件在下，所有控件 `h-8`，
 * 按钮靠 `items-end` 自然与控件**底部对齐**；「重置」永远 `ml-auto` 掉到最右。
 * 高级筛选用**原位 popover**（absolute），不撑高容器、不把查询按钮挤到第二行。
 */

export function FilterBar({
  children,
  onReset,
  actions,
  advanced,
  advancedLabel = "高级筛选",
  className,
  variant = "boxed",
}: {
  children: React.ReactNode
  onReset?: () => void
  /** 主操作（查询/应用） */
  actions?: React.ReactNode
  /** 高级筛选项：渲染在 popover 里，不占容器高度 */
  advanced?: React.ReactNode
  advancedLabel?: string
  className?: string
  /**
   * `boxed`（默认）：方框控件，自带分隔线；
   * `underline`：下划线形态——容器去边框与内边距，值下方 1px `--border-strong`，
   *   聚焦时 2px `--brand`（子控件由调用方渲染成无边框 input/select）。
   */
  variant?: 'boxed' | 'underline'
}) {
  const [advancedOpen, setAdvancedOpen] = React.useState(false)
  const wrapRef = React.useRef<HTMLDivElement>(null)

  React.useEffect(() => {
    if (!advancedOpen) return
    const onPointerDown = (event: MouseEvent) => {
      if (!wrapRef.current?.contains(event.target as Node)) setAdvancedOpen(false)
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setAdvancedOpen(false)
    }
    document.addEventListener("mousedown", onPointerDown)
    document.addEventListener("keydown", onKeyDown)
    return () => {
      document.removeEventListener("mousedown", onPointerDown)
      document.removeEventListener("keydown", onKeyDown)
    }
  }, [advancedOpen])

  return (
    <div
      data-slot="filter-bar"
      data-variant={variant}
      className={cn(
        "flex flex-wrap items-end gap-3",
        variant === 'boxed' ? "border-b border-hairline px-4 py-2" : "gap-x-8 gap-y-4",
        className
      )}
    >
      {children}

      {actions ? <div className="flex items-center gap-2">{actions}</div> : null}

      {advanced ? (
        <div ref={wrapRef} className="relative">
          <button
            type="button"
            onClick={() => setAdvancedOpen((v) => !v)}
            aria-expanded={advancedOpen}
            className="inline-flex h-8 items-center gap-1 rounded-control px-2 text-sm text-[hsl(var(--text-3))] transition-colors duration-100 hover:bg-surface-inset hover:text-[hsl(var(--foreground))] focus-visible:outline-none focus-visible:shadow-focus"
          >
            {advancedLabel}
            <ChevronDown
              aria-hidden="true"
              className={cn("size-3.5 transition-transform duration-100", advancedOpen && "rotate-180")}
            />
          </button>
          {advancedOpen ? (
            <div className="absolute left-0 top-full z-30 mt-1 w-[320px] rounded-card border border-border bg-popover p-3 shadow-e3">
              <div className="grid gap-3">{advanced}</div>
            </div>
          ) : null}
        </div>
      ) : null}

      {onReset ? (
        <button
          type="button"
          onClick={onReset}
          className="ml-auto inline-flex h-7 items-center rounded-control px-2 text-sm text-[hsl(var(--text-3))] transition-colors duration-100 hover:bg-surface-inset hover:text-[hsl(var(--foreground))] focus-visible:outline-none focus-visible:shadow-focus"
        >
          重置
        </button>
      ) : null}
    </div>
  )
}

/** 单个筛选字段：label 在上（12px/600，最小字号），控件在下 */
export function FilterField({
  label,
  width = 160,
  htmlFor,
  children,
}: {
  label: string
  width?: number
  htmlFor?: string
  children: React.ReactNode
}) {
  return (
    <div className="min-w-0" style={{ width }}>
      <label
        htmlFor={htmlFor}
        className="mb-1.5 block text-xs font-semibold text-[hsl(var(--text-3))]"
      >
        {label}
      </label>
      {children}
    </div>
  )
}
