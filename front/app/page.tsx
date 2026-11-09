'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'

/** `/` → `/boss`（静态导出的客户端重定向）。 */
export default function HomePage() {
  const router = useRouter()

  useEffect(() => {
    router.replace('/boss')
  }, [router])

  return (
    <div
      aria-busy="true"
      className="flex min-h-screen items-center justify-center bg-[var(--bg)] text-[var(--text)]"
    >
      <div className="flex flex-col items-center gap-3">
        <span
          aria-hidden="true"
          className="size-6 animate-spin rounded-full border-2 border-[var(--v3-line)] border-t-[var(--v3-accent)]"
        />
        <p className="text-sm text-[var(--v3-muted)]">正在打开工作台…</p>
      </div>
    </div>
  )
}
