'use client'

import { useSyncExternalStore } from 'react'
import { useTheme } from 'next-themes'
import { Lock, Moon, Sun } from 'lucide-react'

/**
 * SidebarSettings —— 设计系统 D.1 底部设置区 / 信息架构 §1.4
 *
 * 主题切换、认证状态、锁定退出、版本号一起贴在侧边栏底部，
 * 它们都是「一个月改一次或永不改」的设置；模型服务/API Key 已归入配置中心。
 * 全部 30px 行高（认证状态行 28px），无动画、无位移。
 */
const ROW =
  'flex h-[30px] w-full items-center gap-2 rounded-control px-2.5 text-base transition-colors duration-100 text-clay-700 hover:bg-[hsl(36_44%_97%/0.7)] hover:text-ink-900 dark:text-clay-500 dark:hover:bg-white/[0.04] dark:hover:text-sand-100'

export function SidebarSettings({
  authState,
  locking,
  onLock,
}: {
  authState: { authEnabled: boolean; authenticated: boolean } | null
  locking: boolean
  onLock: () => void
}) {
  const { theme, setTheme } = useTheme()
  // 是否已挂载（避免 SSR 与首帧主题文案不一致）。用 useSyncExternalStore 取快照，
  // 不在 effect 里 setState（会触发级联渲染，eslint react-hooks 会报错）。
  const mounted = useSyncExternalStore(
    () => () => {},
    () => true,
    () => false
  )

  const isDark = theme === 'dark'

  return (
    <div className="shrink-0 border-t border-hairline p-2">
      {/* 主题切换：图标随主题变，文字跟着变（桌面工具里只留图标语义太弱） */}
      {mounted ? (
        <button
          type="button"
          onClick={() => setTheme(isDark ? 'light' : 'dark')}
          className={ROW}
          aria-label={isDark ? '切换到浅色主题' : '切换到深色主题'}
        >
          {isDark ? (
            <Sun aria-hidden="true" className="size-3.5 shrink-0" strokeWidth={1.5} />
          ) : (
            <Moon aria-hidden="true" className="size-3.5 shrink-0" strokeWidth={1.5} />
          )}
          <span className="truncate">{isDark ? '切换到浅色' : '切换到深色'}</span>
        </button>
      ) : (
        <div className={ROW} aria-hidden="true">
          <Moon className="size-3.5 shrink-0" strokeWidth={1.5} />
          <span className="truncate">切换到深色</span>
        </div>
      )}

      {/* 认证状态行：不可点、无 hover，11px clay-600 + 6px 圆点 */}
      {authState?.authEnabled ? (
        <div className="flex h-7 items-center gap-2 px-2.5">
          <span
            aria-hidden="true"
            className={`size-1.5 shrink-0 rounded-full ${
              authState.authenticated ? 'bg-success' : 'bg-warning'
            }`}
          />
          <span className="truncate text-xs text-clay-600 dark:text-clay-500">
            {authState.authenticated ? '本机会话已认证' : '未认证（需登录）'}
          </span>
        </div>
      ) : null}

      {authState?.authEnabled && authState.authenticated ? (
        <button
          type="button"
          onClick={onLock}
          disabled={locking}
          title="清除本机会话 Cookie 并重新弹出登录层"
          className={`${ROW} disabled:cursor-not-allowed disabled:opacity-45`}
        >
          <Lock aria-hidden="true" className="size-3.5 shrink-0" strokeWidth={1.5} />
          <span className="truncate">{locking ? '退出中…' : '锁定 / 退出登录'}</span>
        </button>
      ) : null}

      <p className="px-2.5 pt-1 text-xs text-clay-500">v1.0.0</p>
    </div>
  )
}
