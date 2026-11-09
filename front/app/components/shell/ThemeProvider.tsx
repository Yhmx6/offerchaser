'use client'

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'

/** V3 三套配色：graphite（石墨铜）/ porcelain（瓷白靛）/ ivory（象牙松）。 */

export type ThemeName = 'graphite' | 'porcelain' | 'ivory'

export const THEME_ORDER: ThemeName[] = ['porcelain', 'graphite', 'ivory']

export const THEME_LABELS: Record<ThemeName, string> = {
  porcelain: '瓷白靛',
  graphite: '石墨铜',
  ivory: '象牙松',
}

interface ThemeContextValue {
  theme: ThemeName
  setTheme: (next: ThemeName) => void
  cycleTheme: () => void
}

const ThemeContext = createContext<ThemeContextValue | null>(null)

const STORAGE_KEY = 'offerchaser.ui-theme'

function isThemeName(value: string | null): value is ThemeName {
  return value === 'graphite' || value === 'porcelain' || value === 'ivory'
}

function applyTheme(theme: ThemeName) {
  if (typeof document === 'undefined') return
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme === 'graphite' ? 'dark' : 'light'
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setThemeState] = useState<ThemeName>(() => {
    if (typeof document === 'undefined') return 'porcelain'
    const current = document.documentElement.dataset.theme
    return isThemeName(current ?? null) ? (current as ThemeName) : 'porcelain'
  })

  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(STORAGE_KEY)
      if (isThemeName(saved)) {
        setThemeState(saved)
        applyTheme(saved)
        return
      }
    } catch {
      // 隐私模式等场景忽略存储失败
    }
    applyTheme(theme)
    // 只在挂载时读取一次存储
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const setTheme = useCallback((next: ThemeName) => {
    setThemeState(next)
    applyTheme(next)
    try {
      window.localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // 存储不可用时仅当前会话生效
    }
  }, [])

  const cycleTheme = useCallback(() => {
    setThemeState((prev) => {
      const index = THEME_ORDER.indexOf(prev)
      const next = THEME_ORDER[(index + 1) % THEME_ORDER.length]
      applyTheme(next)
      try {
        window.localStorage.setItem(STORAGE_KEY, next)
      } catch {
        // ignore
      }
      return next
    })
  }, [])

  const value = useMemo(() => ({ theme, setTheme, cycleTheme }), [theme, setTheme, cycleTheme])

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext)
  if (!context) {
    return { theme: 'porcelain', setTheme: () => {}, cycleTheme: () => {} }
  }
  return context
}

/** 首屏内联脚本：在 hydration 前写入 data-theme，避免主题闪烁。 */
export const THEME_INIT_SCRIPT = `(function(){try{var t=localStorage.getItem('${STORAGE_KEY}');if(t!=='graphite'&&t!=='porcelain'&&t!=='ivory'){t='porcelain';}document.documentElement.dataset.theme=t;document.documentElement.style.colorScheme=t==='graphite'?'dark':'light';}catch(e){document.documentElement.dataset.theme='porcelain';}})();`
