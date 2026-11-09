import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * DataTable —— 设计系统 D.6（信息架构 §6.2 ③）
 *
 * 统一 5 个列表页的表格外观，**只给样式与结构，不给数据逻辑**：
 * - 容器 `rounded-card border border-border bg-card overflow-hidden`（阴影同级 e1）
 * - 表头 32px、`sticky top-0`、`12px/600/tracking-[0.06em] --text-3`
 *   下线用 inset box-shadow（`--border-strong`）而不是 border：table 是 border-collapse，
 *   border 属于表格网格，sticky 表头滚动时下线会留在原地、表头悬浮后就没线了。
 * - 行 40px、`border-b border-hairline`、**无斑马纹**
 * - 数字列 `align="right"` 自动加 `tabular-nums`
 * - `tone` 只给「需要人工介入」的行（warning/danger）画 `inset 3px` 左侧竖条（与 README 规则 6 一致；
 *   此前组件是 2px，页面手写版是 3px）。颜色用 v2 的 --warn / --bad；
 *   40px 行高下 20px 徽章单独跳不出来，这是 UNKNOWN 的双编码之一（D.5）。
 * - 可点击行（传了 onClick）自动可聚焦，Enter / Space 触发，焦点态用内描边。
 * - 空态用 `TableEmptyRow`（D.8 左对齐块，`colSpan` 整行），不要居中大灰字。
 */

export function DataTable({
  children,
  minWidth,
  caption,
  className,
  bare = false,
  fixed = false,
  colWidths,
}: {
  children: React.ReactNode
  /** 列数 > 8 时给 1100，否则不传（≤8 列用 min-w-full） */
  minWidth?: number
  /** 读屏用表格标题 */
  caption?: React.ReactNode
  className?: string
  /** 放在已有卡片内部时用（不再画第二层 card 边框/底色） */
  bare?: boolean
  /** true → table-layout: fixed（配合 colWidths 固定列宽） */
  fixed?: boolean
  /** 固定列宽数组；对应列数，`null` 表示吃掉剩余宽度 */
  colWidths?: Array<number | null>
}) {
  return (
    <div
      className={cn(
        "overflow-x-auto",
        bare ? "" : "rounded-card border border-border bg-card",
        className
      )}
    >
      <table
        className={cn("w-full border-collapse", fixed && "table-fixed")}
        style={minWidth ? { minWidth } : undefined}
      >
        {colWidths ? (
          <colgroup>
            {colWidths.map((width, index) => (
              <col key={index} style={width === null ? undefined : { width }} />
            ))}
          </colgroup>
        ) : null}
        {caption ? <caption className="sr-only">{caption}</caption> : null}
        {children}
      </table>
    </div>
  )
}

export function THead({ children }: { children: React.ReactNode }) {
  return <thead data-slot="thead" className="sticky top-0 z-10 bg-card">{children}</thead>
}

export function Th({
  children,
  align = "left",
  width,
  className,
}: {
  children?: React.ReactNode
  align?: "left" | "right" | "center"
  /** 固定列宽（px），徽章列必须给，避免列抖动 */
  width?: number
  className?: string
}) {
  return (
    <th
      scope="col"
      style={width ? { width } : undefined}
      className={cn(
        "h-8 whitespace-nowrap bg-card px-3 text-xs font-semibold tracking-[0.06em] text-[hsl(var(--text-3))] shadow-[inset_0_-1px_0_hsl(var(--border-strong))]",
        align === "right" ? "text-right" : align === "center" ? "text-center" : "text-left",
        className
      )}
    >
      {children}
    </th>
  )
}

export function Td({
  children,
  align = "left",
  className,
  colSpan,
  title,
}: {
  children?: React.ReactNode
  align?: "left" | "right" | "center"
  className?: string
  colSpan?: number
  title?: string
}) {
  return (
    <td
      colSpan={colSpan}
      title={title}
      className={cn(
        "h-10 px-3 text-base text-ink-800 dark:text-sand-200",
        align === "right" ? "text-right tabular-nums" : align === "center" ? "text-center" : "text-left",
        className
      )}
    >
      {children}
    </td>
  )
}

const ROW_TONE: Record<"warning" | "danger", string> = {
  warning: "shadow-[inset_3px_0_0_hsl(var(--warn))]",
  danger: "shadow-[inset_3px_0_0_hsl(var(--bad))]",
}

export function DataRow({
  children,
  tone,
  onClick,
  className,
  ariaExpanded,
  height = 40,
  style,
}: {
  children: React.ReactNode
  /** 只给「需要人工介入」的行；其它行不要传（竖条是信号，滥用就失效） */
  tone?: "warning" | "danger"
  onClick?: () => void
  className?: string
  ariaExpanded?: boolean
  /** 行高，默认 40（attempts 用 44，templates 用 78） */
  height?: number
  /** 行级样式：底色走这里（与 tone 的左侧竖条互不冲突） */
  style?: React.CSSProperties
}) {
  const interactive = typeof onClick === "function"
  // 只响应行本身的按键：行内的按钮/链接按 Enter 时事件会冒泡上来，不能当成「点整行」
  const handleKeyDown = interactive
    ? (event: React.KeyboardEvent<HTMLTableRowElement>) => {
        if (event.target !== event.currentTarget) return
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault()
          onClick?.()
        }
      }
    : undefined
  return (
    <tr
      data-slot="row"
      style={{ height, ...style }}
      onClick={onClick}
      onKeyDown={handleKeyDown}
      tabIndex={interactive ? 0 : undefined}
      aria-expanded={ariaExpanded}
      className={cn(
        "border-b border-hairline transition-colors duration-100 last:border-0 hover:bg-surface-inset/60",
        tone ? ROW_TONE[tone] : "",
        interactive
          ? "cursor-pointer focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-[hsl(var(--ring))]"
          : "",
        className
      )}
    >
      {children}
    </tr>
  )
}

/** 表格内空态（D.8：左对齐、colSpan 整行、py-6 px-5） */
export function TableEmptyRow({
  colSpan,
  title,
  description,
  action,
}: {
  colSpan: number
  title: React.ReactNode
  description?: React.ReactNode
  action?: React.ReactNode
}) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-5 py-6">
        <p className="text-base font-medium text-ink-800 dark:text-sand-200">{title}</p>
        {description ? (
          <p className="mt-1.5 text-sm leading-5 text-[hsl(var(--text-3))]">{description}</p>
        ) : null}
        {action ? <div className="mt-3">{action}</div> : null}
      </td>
    </tr>
  )
}
