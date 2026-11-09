'use client'

import { useEffect, useRef, useState } from 'react'
import { usePathname } from 'next/navigation'
import {
  BarChart3,
  BadgeCheck,
  History,
  Layers,
  ListChecks,
  LayoutDashboard,
  Route,
  Send,
  ShieldCheck,
  Users,
  Workflow,
  Wrench,
} from 'lucide-react'

import { API_BASE, getBossStatus, getCapabilities } from '@/lib/api'
import { dispatchAuthRequired, getSessionStatus, logout, subscribeAuthRequired } from '@/lib/auth'
import { NavGroup, type NavItem } from './shell/NavGroup'
import { SidebarSettings } from './shell/SidebarSettings'
import { OfferChaserMark } from './brand/OfferChaserMark'

/**
 * Sidebar —— 设计系统 D.1 / 信息架构 §1
 *
 * 240px 固定、纯色 bg-sidebar、三段式（投递 / 配置 / 复盘）+ 底部设置区。
 * 已删除：framer-motion 全部动画、紫色渐变、平台二级树、hover 位移、图标缩放、选中呼吸点。
 * 保留：getCapabilities（只渲染后端声明的平台）、健康轮询、getSessionStatus / handleLock。
 * 新增：投递明细右侧待核对数字徽章（仅 /boss* 路由下 30s 轮询，失败静默，0 时不渲染）。
 */

/** 三段导航（顺序即优先级，标签 ≤5 汉字） */
const DELIVERY_ITEMS = (pending: number): NavItem[] => [
  { href: '/boss', label: '总览', icon: LayoutDashboard },
  { href: '/boss/flow', label: '投递流程', icon: Workflow },
  {
    href: '/boss/attempts',
    label: '投递明细',
    icon: ListChecks,
    badge: pending,
    badgeLabel: `待人工核对 ${pending} 条`,
  },
  { href: '/boss/reviews', label: '人工放行', icon: ShieldCheck },
  { href: '/boss/followups', label: '跟进待办', icon: Send },
]

const CONFIG_ITEMS: NavItem[] = [
  { href: '/boss/templates', label: '配置中心', icon: Layers },
  { href: '/boss/advanced', label: '高级调试', icon: Wrench },
]

const REVIEW_ITEMS: NavItem[] = [
  { href: '/boss/tasks', label: '任务记录', icon: History },
  { href: '/boss/evaluations', label: '评估记录', icon: BadgeCheck },
  { href: '/boss/contacts', label: '联系人', icon: Users },
  { href: '/boss/followup-strategy', label: '跟进策略', icon: Route },
  { href: '/boss/analysis', label: '统计看板', icon: BarChart3 },
]

type Health = 'up' | 'degraded' | 'down' | 'unknown'

const HEALTH_TEXT: Record<Health, string> = {
  up: '系统运行正常',
  degraded: '服务降级',
  down: '服务异常',
  unknown: '未连接',
}

export default function Sidebar() {
  const pathname = usePathname()

  const [health, setHealth] = useState<Health>('unknown')
  const [enabledPlatforms, setEnabledPlatforms] = useState<string[]>(['boss'])
  const [authState, setAuthState] = useState<{ authEnabled: boolean; authenticated: boolean } | null>(
    null
  )
  const [locking, setLocking] = useState(false)
  /** 待人工核对条数（0 不渲染徽章） */
  const [pending, setPending] = useState(0)

  const checkingRef = useRef(false)

  // —— 本机会话认证状态（B5）——
  useEffect(() => {
    let cancelled = false
    getSessionStatus()
      .then((session) => {
        if (cancelled) return
        setAuthState({
          authEnabled: session.authEnabled === true,
          authenticated: session.authenticated === true,
        })
      })
      .catch(() => {
        // 会话接口不可达时不显示认证区块（页面自身会展示接口错误）
      })
    const unsubscribe = subscribeAuthRequired(() => {
      if (cancelled) return
      setAuthState((prev) => (prev ? { ...prev, authenticated: false } : prev))
    })
    return () => {
      cancelled = true
      unsubscribe()
    }
  }, [])

  const handleLock = async () => {
    if (locking) return
    setLocking(true)
    try {
      await logout()
    } catch {
      // 退出失败也继续弹登录层（Cookie 可能已失效/后端不可达）
    } finally {
      setAuthState((prev) => (prev ? { ...prev, authenticated: false } : prev))
      dispatchAuthRequired({ reason: 'logout' })
      setLocking(false)
    }
  }

  // —— 能力声明：只渲染后端启用平台；失败回退本地单平台默认（boss）——
  useEffect(() => {
    let cancelled = false
    getCapabilities()
      .then((capabilities) => {
        if (cancelled) return
        const platforms = Array.isArray(capabilities?.enabledPlatforms)
          ? capabilities.enabledPlatforms.filter(
              (item): item is string => typeof item === 'string' && item.trim().length > 0
            )
          : []
        setEnabledPlatforms(platforms.length > 0 ? platforms : ['boss'])
      })
      .catch(() => {
        if (!cancelled) setEnabledPlatforms(['boss'])
      })
    return () => {
      cancelled = true
    }
  }, [])

  // —— 健康检查轮询（30s，圆点不 pulse：pulse 只留给 RUNNING 任务态）——
  useEffect(() => {
    let interval: ReturnType<typeof setInterval> | null = null

    const check = async () => {
      if (checkingRef.current) return
      checkingRef.current = true
      const baseUrl = process.env.API_BASE_URL || `${API_BASE}`
      const controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), 3000)
      try {
        let res = await fetch(`${baseUrl}/api/health`, { signal: controller.signal })
        if (res.status === 404) {
          res = await fetch(`${baseUrl}/actuator/health`, { signal: controller.signal })
        }
        if (!res.ok) throw new Error(`status ${res.status}`)
        const data = await res.json()
        const statusRaw = (data.status || data.state || '').toString().toUpperCase()
        if (statusRaw === 'UP' || statusRaw === 'HEALTHY') setHealth('up')
        else if (statusRaw === 'DEGRADED' || statusRaw === 'WARN') setHealth('degraded')
        else setHealth('down')
      } catch {
        setHealth('unknown')
      } finally {
        clearTimeout(timeout)
        checkingRef.current = false
      }
    }

    void check()
    interval = setInterval(check, 30000)
    return () => {
      if (interval) clearInterval(interval)
    }
  }, [])

  // —— 待核对徽章：只在 /boss* 路由下轮询（30s），失败静默 ——
  useEffect(() => {
    if (!pathname || !pathname.startsWith('/boss')) {
      setPending(0)
      return
    }
    let cancelled = false
    const load = async () => {
      try {
        const status = await getBossStatus()
        if (cancelled) return
        const count = status.pendingResolutions
        setPending(typeof count === 'number' && count > 0 ? count : 0)
      } catch {
        // 失败静默：侧边栏徽章不是安全关键路径，不在导航里报错
      }
    }
    void load()
    const interval = setInterval(load, 30000)
    return () => {
      cancelled = true
      clearInterval(interval)
    }
  }, [pathname])

  const showBoss = enabledPlatforms.includes('boss')

  return (
    <aside className="fixed left-0 top-0 z-50 flex h-screen w-60 flex-col border-r border-hairline bg-sidebar">
      {/* 品牌区：56px，20×20 primary 方块 + 两行文字（OfferChaser 箭头标记） */}
      <div className="flex h-14 shrink-0 items-center gap-2.5 border-b border-hairline px-4">
        <span
          aria-hidden="true"
          className="flex size-5 shrink-0 items-center justify-center rounded-tag bg-primary text-primary-foreground"
        >
          <OfferChaserMark className="size-3.5" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-base font-semibold text-ink-900 dark:text-sand-100">OfferChaser</p>
          <p className="truncate text-xs text-clay-600 dark:text-clay-500">配置管理中心</p>
        </div>
      </div>

      {/* 健康状态：28px，6px 圆点，无动画 */}
      <div className="flex h-7 shrink-0 items-center gap-2 px-4">
        <span
          aria-hidden="true"
          className={`size-1.5 shrink-0 rounded-full ${
            health === 'up'
              ? 'bg-success'
              : health === 'degraded'
                ? 'bg-warning'
                : health === 'down'
                  ? 'bg-danger'
                  : 'bg-clay-400'
          }`}
        />
        <span className="truncate text-xs text-clay-600 dark:text-clay-500">
          {HEALTH_TEXT[health]}
        </span>
      </div>

      {/* 三段导航 */}
      <nav aria-label="主导航" className="min-h-0 flex-1 overflow-y-auto px-2 py-2 no-scrollbar">
        {showBoss ? (
          <>
            <NavGroup id="delivery" title="投递" items={DELIVERY_ITEMS(pending)} pathname={pathname} />
            <NavGroup id="config" title="配置" items={CONFIG_ITEMS} pathname={pathname} />
            <NavGroup id="review" title="复盘" items={REVIEW_ITEMS} pathname={pathname} />
          </>
        ) : null}
      </nav>

      <SidebarSettings authState={authState} locking={locking} onLock={() => void handleLock()} />

      {/* signature S2 竖版：侧边栏右缘渐隐分隔线（渐隐两端 8%） */}
      <span
        aria-hidden="true"
        className="pointer-events-none absolute right-0 top-0 h-full w-px bg-[linear-gradient(180deg,transparent_0%,hsl(var(--border))_8%,hsl(var(--border))_92%,transparent_100%)]"
      />
    </aside>
  )
}
