'use client'

import { BossStatusProvider } from '@/app/components/shell/BossStatusProvider'
import NotificationBar from '@/app/components/shell/NotificationBar'
import TopBar from '@/app/components/shell/TopBar'

export default function BossLayout({ children }: { children: React.ReactNode }) {
  return (
    <BossStatusProvider>
      <div className="min-h-screen bg-[var(--bg)] text-[var(--text)]">
        <TopBar />
        <NotificationBar />
        <main
          id="main"
          className="mx-auto w-full max-w-[1360px] px-8 pb-28 pt-6 max-sm:px-4 lg:pb-12"
        >
          {children}
        </main>
      </div>
    </BossStatusProvider>
  )
}
