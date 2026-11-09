'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'

import { legacyTarget, type LegacySlug } from '@/app/lib/spaces'

/** 旧路由重定向壳：保留原查询参数，书签不失效。 */
export default function LegacyRedirect({ slug }: { slug: LegacySlug }) {
  const router = useRouter()

  useEffect(() => {
    const search = typeof window === 'undefined' ? '' : window.location.search
    router.replace(legacyTarget(slug, search))
  }, [router, slug])

  return (
    <div
      aria-busy="true"
      className="flex min-h-[60vh] items-center justify-center bg-[var(--bg)] text-[var(--v3-muted)]"
    >
      <div className="flex flex-col items-center gap-3">
        <span
          aria-hidden="true"
          className="size-6 animate-spin rounded-full border-2 border-[var(--v3-line)] border-t-[var(--v3-accent)]"
        />
        <p className="text-sm">正在跳转到新工作台…</p>
      </div>
    </div>
  )
}
