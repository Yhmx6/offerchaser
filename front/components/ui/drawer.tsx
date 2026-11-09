"use client"

import * as React from "react"
import { X } from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * Drawer —— 设计系统（信息架构 §6.2 详情抽屉）
 *
 * 右侧 480px，手写 focus trap（不用新依赖）：
 * - 打开时记住触发元素、焦点移进面板；Tab/Shift+Tab 在面板内循环；Esc 关闭；关闭后焦点回到触发元素
 * - sticky 头部（标题 + 关闭）与 sticky 底部（操作区）
 * - 遮罩 `rgba(26,23,18,0.45)`，**无 blur**（暖色系统里模糊会显脏，且 GPU 开销大）
 * - 打开时锁 body 滚动
 * - onClose 存进 ref，effect 只依赖 open。此前依赖 [open, onClose]：调用方传内联箭头函数时，
 *   父组件每次重渲染（比如在抽屉表单里敲一个字）都会让 effect 先清理（焦点跳回触发按钮）
 *   再重跑（焦点跳到面板第一个输入框），表现为「打字时光标被抢走」。
 * - 挂载时遮罩淡入、面板右侧滑入（animate-fade-in / animate-drawer-in，见 globals.css）
 * - 背景内容在打开时加 `aria-hidden` 由调用方负责（本组件只渲染浮层）
 */

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

export function Drawer({
  open,
  onClose,
  title,
  description,
  footer,
  width = 480,
  children,
}: {
  open: boolean
  onClose: () => void
  title: React.ReactNode
  description?: React.ReactNode
  footer?: React.ReactNode
  width?: number
  children: React.ReactNode
}) {
  const panelRef = React.useRef<HTMLDivElement>(null)
  const restoreRef = React.useRef<HTMLElement | null>(null)
  const titleId = React.useId()
  const onCloseRef = React.useRef(onClose)
  React.useEffect(() => {
    onCloseRef.current = onClose
  })

  React.useEffect(() => {
    if (!open) return
    restoreRef.current = document.activeElement as HTMLElement | null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = "hidden"

    // 焦点移进面板（有可聚焦子元素则进第一个，否则聚焦面板本身）
    const panel = panelRef.current
    const first = panel?.querySelector<HTMLElement>(FOCUSABLE)
    ;(first ?? panel)?.focus()

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.stopPropagation()
        onCloseRef.current()
        return
      }
      if (event.key !== "Tab" || !panel) return
      const nodes = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
        (node) => node.offsetParent !== null || node === document.activeElement
      )
      if (nodes.length === 0) {
        event.preventDefault()
        panel.focus()
        return
      }
      const firstNode = nodes[0]
      const lastNode = nodes[nodes.length - 1]
      if (event.shiftKey && document.activeElement === firstNode) {
        event.preventDefault()
        lastNode.focus()
      } else if (!event.shiftKey && document.activeElement === lastNode) {
        event.preventDefault()
        firstNode.focus()
      }
    }

    document.addEventListener("keydown", onKeyDown)
    return () => {
      document.removeEventListener("keydown", onKeyDown)
      document.body.style.overflow = previousOverflow
      restoreRef.current?.focus?.()
    }
  }, [open])

  if (!open) return null

  return (
    <div className="fixed inset-0 z-[80] flex justify-end">
      {/* 遮罩：暖墨 45%，无 blur */}
      <div
        aria-hidden="true"
        onClick={onClose}
        className="absolute inset-0 animate-fade-in"
        style={{ backgroundColor: "rgba(26, 23, 18, 0.45)" }}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        style={{ width }}
        className="relative flex h-full max-w-full animate-drawer-in flex-col border-l border-border bg-card shadow-overlay focus:outline-none"
      >
        <div className="sticky top-0 z-10 flex items-start justify-between gap-3 border-b border-hairline bg-card px-4 py-3">
          <div className="min-w-0">
            <h2 id={titleId} className="truncate text-lg font-semibold text-ink-900 dark:text-sand-100">
              {title}
            </h2>
            {description ? (
              <p className="mt-0.5 truncate text-sm text-[hsl(var(--text-3))]">{description}</p>
            ) : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="inline-flex size-7 shrink-0 items-center justify-center rounded-control text-[hsl(var(--text-3))] transition-colors duration-100 hover:bg-surface-inset hover:text-[hsl(var(--foreground))] focus-visible:outline-none focus-visible:shadow-focus"
          >
            <X aria-hidden="true" className="size-4" />
          </button>
        </div>

        <div className={cn("min-h-0 flex-1 overflow-y-auto px-4 py-4")}>{children}</div>

        {footer ? (
          <div className="sticky bottom-0 border-t border-hairline bg-card px-4 py-3">{footer}</div>
        ) : null}
      </div>
    </div>
  )
}
