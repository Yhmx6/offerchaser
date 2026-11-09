'use client'

import { useEffect, useState } from 'react'

import FollowupStrategyView from '@/app/components/views/FollowupStrategyView'
import InsightsMetricsView from '@/app/components/views/InsightsMetricsView'

type InsightsTab = 'metrics' | 'strategy'

/** 洞察（/boss/insights）：效果/采集看板 + 跟进策略（只读说明）。 */
export default function InsightsPage() {
  const [tab, setTab] = useState<InsightsTab>('metrics')

  useEffect(() => {
    if (typeof window === 'undefined') return
    const timer = window.setTimeout(() => {
      const params = new URLSearchParams(window.location.search)
      if (params.get('tab') === 'strategy') setTab('strategy')
    }, 0)
    return () => window.clearTimeout(timer)
  }, [])

  return (
    <div className="animate-v3-in space-y-4">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="font-serif text-[36px] font-medium leading-none tracking-[-0.035em]">洞察</h1>
        <div
          className="inline-flex items-center gap-0.5 rounded-full border p-1"
          style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }}
          role="group"
          aria-label="洞察页签"
        >
          {[
            { key: 'metrics' as InsightsTab, label: '效果与采集' },
            { key: 'strategy' as InsightsTab, label: '跟进策略' },
          ].map((entry) => {
            const active = tab === entry.key
            return (
              <button
                key={entry.key}
                type="button"
                aria-pressed={active}
                onClick={() => setTab(entry.key)}
                className="rounded-full px-3 py-1.5 text-xs font-medium transition-colors"
                style={active ? { backgroundColor: 'var(--surface)', color: 'var(--v3-accent)', boxShadow: '0 2px 8px -4px rgba(0,0,0,.22)' } : { color: 'var(--v3-muted)' }}
              >
                {entry.label}
              </button>
            )
          })}
        </div>
      </header>

      {tab === 'metrics' ? <InsightsMetricsView /> : <FollowupStrategyView />}
    </div>
  )
}
