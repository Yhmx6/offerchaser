'use client'

import Link from 'next/link'
import type { LucideIcon } from 'lucide-react'

/**
 * NavGroup —— 设计系统 D.1 / 信息架构 §1.2
 *
 * 一段导航 = 段标题（视觉 11px/600，语义 h2 供读屏跳转）+ items。
 * 导航项 30px 高、图标 14px/strokeWidth 1.5、无位移、无缩放、无图标变色。
 * 选中态：bg-card + inset 2px 主色条（不占布局）+ 文字 font-medium + 图标 text-primary。
 */
export interface NavItem {
  href: string
  label: string
  icon: LucideIcon
  /** 数字徽章（全侧边栏最多 1 个；0 / undefined 不渲染） */
  badge?: number
  /** 徽章的无障碍文案，如「待人工核对 11 条」 */
  badgeLabel?: string
}

const ITEM_BASE =
  'flex h-[30px] items-center gap-2 rounded-control px-2.5 text-base transition-colors duration-100'
const ITEM_IDLE =
  'text-clay-700 hover:bg-[hsl(36_44%_97%/0.7)] hover:text-ink-900 dark:text-clay-500 dark:hover:bg-white/[0.04] dark:hover:text-sand-100'
const ITEM_ACTIVE =
  'bg-card font-medium text-ink-900 shadow-e1 shadow-[inset_2px_0_0_hsl(var(--primary))] dark:bg-[hsl(36_13%_15%)] dark:text-sand-100'

export function NavGroup({
  id,
  title,
  items,
  pathname,
}: {
  id: string
  title: string
  items: NavItem[]
  pathname: string | null
}) {
  const headingId = `nav-${id}`
  return (
    <div role="group" aria-labelledby={headingId} className="mt-3">
      <h2
        id={headingId}
        className="px-3 pb-1 pt-1 text-xs font-semibold tracking-[0.08em] text-clay-600 dark:text-clay-500"
      >
        {title}
      </h2>
      <ul className="space-y-0.5">
        {items.map((item) => {
          const Icon = item.icon
          // `/boss` 总览只在完全等于 /boss 时高亮（startsWith 会让所有子页都标记总览）
          const isActive = pathname === item.href
          return (
            <li key={item.href}>
              <Link
                href={item.href}
                aria-current={isActive ? 'page' : undefined}
                className={`${ITEM_BASE} ${isActive ? ITEM_ACTIVE : ITEM_IDLE}`}
              >
                <Icon
                  aria-hidden="true"
                  className={`size-3.5 shrink-0 ${isActive ? 'text-primary' : ''}`}
                  strokeWidth={1.5}
                />
                <span className="truncate">{item.label}</span>
                {item.badge && item.badge > 0 ? (
                  <span
                    aria-label={item.badgeLabel ?? `${item.badge} 条待处理`}
                    className="ml-auto inline-flex h-5 min-w-5 items-center justify-center gap-1 rounded-tag border border-warning-border bg-warning-bg px-1 text-xs font-semibold text-warning"
                  >
                    <span aria-hidden="true" className="size-1 rounded-full bg-warning" />
                    {item.badge}
                  </span>
                ) : null}
              </Link>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
