'use client'

import './globals.css'
import './increment-v1.css'
import './increment-v2-visual.css'
import AuthGate from './components/AuthGate'
import { THEME_INIT_SCRIPT, ThemeProvider } from './components/shell/ThemeProvider'
import { LiveRegionProvider } from '@/components/ui/live-region'
import SpotlightProvider from '@/app/components/shell/SpotlightProvider'

type RootLayoutProps = Readonly<{
  children: React.ReactNode
}>

export default function RootLayout({ children }: RootLayoutProps) {
  return (
    <html lang="zh-CN" className="oc-v2" data-theme="porcelain" suppressHydrationWarning>
      <head>
        <title>OfferChaser · 求职工作台</title>
        <meta
          name="description"
          content="OfferChaser 管理台：今日运行、待办决策、记录审计、配置与洞察"
        />
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
        <link rel="icon" href="/offerchaser-icon.svg" type="image/svg+xml" />
        <link rel="alternate icon" href="/favicon.ico" />
      </head>
      <body className="v3-shell-font" suppressHydrationWarning>
        <ThemeProvider>
          <a
            href="#main"
            className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-[130] focus:rounded-[10px] focus:bg-[var(--surface)] focus:px-3 focus:py-2 focus:text-sm focus:text-[var(--text)] focus:shadow-[var(--shadow)]"
          >
            跳到主内容
          </a>
          <LiveRegionProvider>
            <SpotlightProvider>
              <AuthGate>{children}</AuthGate>
            </SpotlightProvider>
          </LiveRegionProvider>
        </ThemeProvider>
      </body>
    </html>
  )
}
