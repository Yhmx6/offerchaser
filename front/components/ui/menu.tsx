"use client"

import * as React from "react"
import { MoreHorizontal } from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * RowMenu —— `⋯` 溢出菜单（信息架构 §9.1 #12）
 *
 * 用于表格行 / 模板行的次级操作（上传/预览简历等不该进入主按钮的操作）。
 * - 面板 `rounded-control border border-border bg-popover shadow-e3`，项高 30px、12px 文字、左图标 14px
 * - Esc / 点外部关闭；`↑↓` 在可用项间移动焦点；`danger` 项独立分隔且文字砖红
 * - 关闭后焦点回到触发按钮
 */

function MenuIcon({ icon: Icon }: { icon: NonNullable<MenuItem["icon"]> }) {
  return <Icon aria-hidden="true" className="size-3.5 shrink-0" strokeWidth={1.5} />
}

export interface MenuItem {
  label: string
  icon?: React.ComponentType<{ className?: string; strokeWidth?: number }>
  onSelect: () => void
  danger?: boolean
  disabled?: boolean
  /** 与前一项之间画一条分隔线（`danger` 项常见） */
  separatorBefore?: boolean
}

export function RowMenu({
  items,
  label = "更多操作",
  className,
}: {
  items: MenuItem[]
  label?: string
  className?: string
}) {
  const [open, setOpen] = React.useState(false)
  const wrapRef = React.useRef<HTMLDivElement>(null)
  const triggerRef = React.useRef<HTMLButtonElement>(null)
  const itemRefs = React.useRef<(HTMLButtonElement | null)[]>([])

  React.useEffect(() => {
    if (!open) return
    const onPointerDown = (event: MouseEvent) => {
      if (!wrapRef.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener("mousedown", onPointerDown)
    return () => document.removeEventListener("mousedown", onPointerDown)
  }, [open])

  React.useEffect(() => {
    if (!open) return
    // 打开后聚焦第一个可用项
    const firstEnabled = items.findIndex((item) => !item.disabled)
    if (firstEnabled >= 0) itemRefs.current[firstEnabled]?.focus()
  }, [open, items])

  const closeAndRestore = () => {
    setOpen(false)
    triggerRef.current?.focus()
  }

  const onPanelKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape") {
      event.stopPropagation()
      closeAndRestore()
      return
    }
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return
    event.preventDefault()
    const enabled = items
      .map((item, index) => ({ item, index }))
      .filter(({ item }) => !item.disabled)
    if (enabled.length === 0) return
    const current = itemRefs.current.findIndex((node) => node === document.activeElement)
    const position = enabled.findIndex(({ index }) => index === current)
    const delta = event.key === "ArrowDown" ? 1 : -1
    const next = enabled[(position + delta + enabled.length) % enabled.length]
    itemRefs.current[next.index]?.focus()
  }

  return (
    <div ref={wrapRef} className={cn("relative inline-flex", className)}>
      <button
        ref={triggerRef}
        type="button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="inline-flex size-7 items-center justify-center rounded-control text-clay-600 transition-colors duration-100 hover:bg-surface-inset hover:text-ink-900 focus-visible:outline-none focus-visible:shadow-focus dark:text-clay-500 dark:hover:text-sand-100"
      >
        <MoreHorizontal aria-hidden="true" className="size-4" />
      </button>

      {open ? (
        <div
          role="menu"
          aria-label={label}
          onKeyDown={onPanelKeyDown}
          className="absolute right-0 top-full z-30 mt-1 w-44 rounded-control border border-border bg-popover py-1 shadow-e3"
        >
          {items.map((item, index) => (
            <React.Fragment key={`${item.label}-${index}`}>
              {item.separatorBefore ? <div className="my-1 h-px bg-hairline" /> : null}
              <button
                ref={(node) => {
                  itemRefs.current[index] = node
                }}
                type="button"
                role="menuitem"
                disabled={item.disabled}
                onClick={() => {
                  if (item.disabled) return
                  closeAndRestore()
                  item.onSelect()
                }}
                className={cn(
                  "flex h-[30px] w-full items-center gap-2 px-2.5 text-left text-sm transition-colors duration-100",
                  item.danger
                    ? "text-danger hover:bg-danger-bg"
                    : "text-ink-800 hover:bg-surface-inset dark:text-sand-200",
                  item.disabled ? "cursor-not-allowed opacity-45" : ""
                )}
              >
                {item.icon ? <MenuIcon icon={item.icon} /> : null}
                <span className="truncate">{item.label}</span>
              </button>
            </React.Fragment>
          ))}
        </div>
      ) : null}
    </div>
  )
}
