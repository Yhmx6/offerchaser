"use client"

import * as React from "react"
import { ChevronRight, X } from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * InlineAlert —— 设计系统 D.10「单行提示条」（治"三层文字墙"）
 *
 * 形态：语义色左侧 2px 竖线 + 淡底，收起态高度 ≤ 36px，文案一句 ≤ 40 字。
 * 展开：`details` 用 CSS grid-rows 160ms 展开（无 JS 测高，静态导出下稳定）。
 * 永不使用 accent 做提示条 —— 强调色留给按钮与选中态。
 */
export type InlineAlertVariant = "info" | "warning" | "danger" | "success"

/*
 * 与 StatusTag / 表格竖条统一用 v2 令牌（ok / warn / bad + 中性），不再用 v1 的
 * success / warning / danger / info。原先 info 是石板灰蓝（深色下偏青绿），是暖纸界面里唯一的冷色块；
 * 同时 v1 与 v2 两套语义色色相不同，同一页里 InlineAlert 和 StatusTag 的「警告」颜色对不上。
 * info 现在是中性：--sunken 底 + --text-2 字 + --text-3 竖线，不抢视线。
 * 对比度（字 / 底）：浅色 warn 4.8、bad 6.6、ok 5.2；深色 warn 6.7、bad 5.9、ok 6.1。
 */
const VARIANT_CLASS: Record<InlineAlertVariant, string> = {
  info: "border-[hsl(var(--border-strong))] border-l-[hsl(var(--text-3))] bg-[hsl(var(--sunken))] text-[hsl(var(--text-2))]",
  warning: "border-[hsl(var(--warn)/.3)] border-l-[hsl(var(--warn))] bg-[hsl(var(--warn-bg))] text-[hsl(var(--warn-text))]",
  danger: "border-[hsl(var(--bad)/.28)] border-l-[hsl(var(--bad))] bg-[hsl(var(--bad-bg))] text-[hsl(var(--bad-text))]",
  success: "border-[hsl(var(--ok)/.28)] border-l-[hsl(var(--ok))] bg-[hsl(var(--ok-bg))] text-[hsl(var(--ok-text))]",
}

const DETAILS_CLASS: Record<InlineAlertVariant, string> = {
  info: "border-l-[hsl(var(--text-3))]",
  warning: "border-l-[hsl(var(--warn))]",
  danger: "border-l-[hsl(var(--bad))]",
  success: "border-l-[hsl(var(--ok))]",
}

export interface InlineAlertProps {
  variant?: InlineAlertVariant
  /** 收起态文案：一句 ≤ 40 字，不加句号 */
  children: React.ReactNode
  /** 可展开的详细说明（列表 / 段落） */
  details?: React.ReactNode
  /** 右侧动作区（按钮或文字链接） */
  action?: React.ReactNode
  /** 是否允许关闭 */
  dismissible?: boolean
  className?: string
}

export function InlineAlert({
  variant = "info",
  children,
  details,
  action,
  dismissible = false,
  className,
}: InlineAlertProps) {
  const [open, setOpen] = React.useState(false)
  const [closed, setClosed] = React.useState(false)
  const detailsId = React.useId()

  if (closed) return null

  return (
    <div className={cn("w-full", className)}>
      <div
        className={cn(
          "flex items-start gap-2 rounded-control border border-l-2 px-3 py-2",
          VARIANT_CLASS[variant]
        )}
        role={variant === "danger" ? "alert" : "status"}
      >
        <div className="min-w-0 flex-1 text-sm leading-5">{children}</div>
        <div className="flex shrink-0 items-center gap-1">
          {action}
          {details ? (
            <button
              type="button"
              onClick={() => setOpen((v) => !v)}
              aria-expanded={open}
              aria-controls={detailsId}
              className="inline-flex h-6 items-center gap-0.5 rounded-tag px-1 text-xs opacity-80 transition-opacity duration-100 hover:opacity-100 focus-visible:outline-none focus-visible:shadow-focus"
            >
              详情
              <ChevronRight
                aria-hidden="true"
                className="size-3 transition-transform duration-[120ms]"
                style={{ transform: open ? "rotate(90deg)" : "none" }}
              />
            </button>
          ) : null}
          {dismissible ? (
            <button
              type="button"
              onClick={() => setClosed(true)}
              aria-label="关闭提示"
              className="inline-flex size-6 items-center justify-center rounded-tag opacity-70 transition-opacity duration-100 hover:opacity-100 focus-visible:outline-none focus-visible:shadow-focus"
            >
              <X aria-hidden="true" className="size-3.5" />
            </button>
          ) : null}
        </div>
      </div>

      {details ? (
        <div
          id={detailsId}
          className={cn("grid", open ? "collapsible-open" : "collapsible-closed")}
        >
          <div className="min-h-0 overflow-hidden">
            <div
              className={cn(
                "mt-2 rounded-control border border-hairline border-l-2 bg-surface-inset p-3 text-sm leading-5 text-[hsl(var(--text-2))]",
                DETAILS_CLASS[variant]
              )}
            >
              {details}
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}
