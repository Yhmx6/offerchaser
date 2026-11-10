'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useCallback, useEffect, useState } from 'react'
import { Command, Lock, Palette, Search } from 'lucide-react'

import { OfferChaserLockup } from '@/app/components/brand/OfferChaserLockup'
import { SpaceTabs } from '@/components/ui/space-tabs'
import { API_BASE, getFollowups, getManualReleases } from '@/lib/api'
import { dispatchAuthRequired, getSessionStatus, logout } from '@/lib/auth'

import { useSharedBossStatus } from './BossStatusProvider'
import CommandPalette from './CommandPalette'
import RunCapsule from './RunCapsule'
import { SPACES } from '@/app/lib/spaces'
import { THEME_LABELS, useTheme } from './ThemeProvider'

type Health = 'up' | 'degraded' | 'down' | 'unknown'

const HEALTH_COLOR: Record<Health, string> = {
  up: 'var(--v3-ok)',
  degraded: 'var(--v3-warn)',
  down: 'var(--v3-bad)',
  unknown: 'var(--v3-muted)',
}

const HEALTH_TEXT: Record<Health, string> = {
  up: '系统运行正常',
  degraded: '服务降级',
  down: '服务异常',
  unknown: '未连接',
}

function activeSpace(pathname: string | null): string {
  if (!pathname) return 'today'
  if (pathname === '/boss' || pathname === '/boss/') return 'today'
  const match = SPACES.find((space) => space.key !== 'today' && pathname.startsWith(space.href))
  return match?.key ?? 'today'
}

/** V3 顶栏：Logo + 空间滑块 + 运行胶囊 + 跳转面板 + 主题 + 锁定 + 健康。 */
export default function TopBar() {
  const pathname = usePathname()
  const { theme, cycleTheme } = useTheme()
  const { pendingResolutions } = useSharedBossStatus()

  const [paletteOpen, setPaletteOpen] = useState(false)
  const [health, setHealth] = useState<Health>('unknown')
  const [todoCount, setTodoCount] = useState(0)
  const [locking, setLocking] = useState(false)
  const [authEnabled, setAuthEnabled] = useState(false)

  const loadTodoCount = useCallback(async () => {
    const [releaseResult, followupResult] = await Promise.allSettled([
      getManualReleases('PENDING'),
      getFollowups({ status: 'SUGGESTED', page: 1, size: 1 }),
    ])
    const releaseCount =
      releaseResult.status === 'fulfilled' && typeof releaseResult.value?.counts?.PENDING === 'number'
        ? releaseResult.value.counts.PENDING
        : 0
    const followupCount =
      followupResult.status === 'fulfilled' && typeof followupResult.value?.total === 'number'
        ? followupResult.value.total
        : 0
    setTodoCount(Math.max(0, pendingResolutions) + releaseCount + followupCount)
  }, [pendingResolutions])

  useEffect(() => {
    void loadTodoCount()
    const timer = window.setInterval(() => void loadTodoCount(), 60000)
    return () => window.clearInterval(timer)
  }, [loadTodoCount])

  useEffect(() => {
    let alive = true
    const checkHealth = async () => {
      try {
        const response = await fetch(`${API_BASE}/api/health`)
        if (!response.ok) throw new Error(String(response.status))
        const data = (await response.json()) as { status?: string }
        const raw = String(data?.status ?? '').toUpperCase()
        if (!alive) return
        setHealth(raw === 'UP' || raw === 'HEALTHY' ? 'up' : raw === 'DEGRADED' ? 'degraded' : 'down')
      } catch {
        if (alive) setHealth('unknown')
      }
    }
    void checkHealth()
    const timer = window.setInterval(() => void checkHealth(), 30000)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setPaletteOpen((open) => !open)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  useEffect(() => {
    let alive = true
    getSessionStatus()
      .then((session) => {
        if (alive) setAuthEnabled(session.authEnabled === true)
      })
      .catch(() => {
        if (alive) setAuthEnabled(false)
      })
    return () => {
      alive = false
    }
  }, [])

  const handleLock = async () => {
    if (locking) return
    setLocking(true)
    try {
      await logout()
    } catch {
      // 退出失败也直接弹登录层：Cookie 可能已失效/后端不可达
    } finally {
      setLocking(false)
      dispatchAuthRequired({ reason: 'logout' })
    }
  }

  const current = activeSpace(pathname)
  const iconBtn =
    'inline-flex size-9 items-center justify-center rounded-full border text-[var(--bar-muted)] transition-colors hover:text-[var(--bar-text)]'
  const barBorder = { borderColor: 'var(--bar-line)' }

  return (
    <>
      <header
        className="oc-topbar sticky top-0 z-[60] border-b bg-[var(--bar-bg)] text-[var(--bar-text)]"
        style={barBorder}
      >
        <div className="mx-auto flex min-h-16 max-w-[1360px] flex-wrap items-center gap-3 px-8 py-2 max-lg:gap-2 max-lg:px-4">
          <Link href="/boss" className="flex shrink-0 items-center" aria-label="回到今日">
            <OfferChaserLockup className="h-[26px] w-auto" />
          </Link>

          <div className="hidden min-w-0 flex-1 justify-center lg:flex">
            <SpaceTabs
              ariaLabel="空间切换"
              activeKey={current}
              items={SPACES.map((space) => ({
                key: space.key,
                label: space.label,
                href: space.href,
                badge: space.key === 'inbox' ? todoCount : 0,
              }))}
            />
          </div>

          <div className="flex shrink-0 items-center gap-2">
            <div className="hidden md:flex">
              <RunCapsule />
            </div>

            <button
              type="button"
              className={`${iconBtn} hidden sm:inline-flex`}
              style={barBorder}
              onClick={() => setPaletteOpen(true)}
              title="跳转面板（Ctrl/⌘ + K）"
              aria-label="打开跳转面板"
            >
              <Command aria-hidden="true" className="size-4" />
            </button>

            <button
              type="button"
              className={iconBtn}
              style={barBorder}
              onClick={cycleTheme}
              title={`切换配色：当前 ${THEME_LABELS[theme]}`}
              aria-label="切换配色"
            >
              <Palette aria-hidden="true" className="size-4" />
            </button>

            {authEnabled ? (
              <button
                type="button"
                className={iconBtn}
                style={barBorder}
                onClick={() => void handleLock()}
                disabled={locking}
                title="锁定管理台"
                aria-label="锁定管理台"
              >
                <Lock aria-hidden="true" className="size-4" />
              </button>
            ) : null}

            <span
              className="ml-1 hidden size-2 shrink-0 rounded-full lg:block"
              style={{ backgroundColor: HEALTH_COLOR[health] }}
              title={`${HEALTH_TEXT[health]} · 主题：${THEME_LABELS[theme]}`}
              aria-label={`健康状态：${HEALTH_TEXT[health]}`}
            />
          </div>
        </div>

        <div className="border-t px-8 pb-2 pt-1 md:hidden" style={barBorder}>
          <div className="flex items-center justify-between gap-2">
            <RunCapsule />
            <button
              type="button"
              className="inline-flex h-8 items-center gap-1 rounded-full border px-2.5 text-xs text-[var(--bar-muted)]"
              style={barBorder}
              onClick={() => setPaletteOpen(true)}
            >
              <Search aria-hidden="true" className="size-3.5" />
              跳转
            </button>
          </div>
        </div>
      </header>

      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />

      {/* ≤820px：空间标签改成底部悬浮导航，顶栏只保留 Logo / 运行 / 主题 */}
      <nav
        aria-label="空间切换（底部）"
        className="fixed inset-x-3 bottom-3 z-[70] grid grid-cols-5 gap-1 rounded-[18px] border bg-[var(--surface)] p-1.5 shadow-[0_12px_32px_-16px_rgba(0,0,0,.4)] lg:hidden"
        style={{ borderColor: 'var(--v3-line)' }}
      >
        {SPACES.map((space) => {
          const active = space.key === current
          const badge = space.key === 'inbox' ? todoCount : 0
          return (
            <Link
              key={space.key}
              href={space.href}
              aria-current={active ? 'page' : undefined}
              className="relative inline-flex min-w-0 flex-col items-center justify-center gap-0.5 rounded-[13px] px-1 py-1.5 text-[10px] font-medium transition-colors"
              style={active ? { backgroundColor: 'var(--accent-soft)', color: 'var(--v3-accent)' } : { color: 'var(--v3-muted)' }}
            >
              <span className="truncate">{space.label}</span>
              {badge > 0 ? (
                <span className="absolute right-1.5 top-1 inline-flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[9px]" style={{ backgroundColor: 'var(--v3-warn)', color: 'var(--accent-ink)' }}>{badge}</span>
              ) : null}
            </Link>
          )
        })}
      </nav>
    </>
  )
}
