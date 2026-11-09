'use client'

import { createContext, useContext } from 'react'

import { useBossStatus } from '@/lib/use-boss-status'
import type { BossStatusController } from '@/lib/use-boss-status'

/** 全站唯一的运行状态数据层：顶栏胶囊与各空间共用同一份 useBossStatus。 */

const BossStatusContext = createContext<BossStatusController | null>(null)

export function BossStatusProvider({ children }: { children: React.ReactNode }) {
  const controller = useBossStatus()
  return <BossStatusContext.Provider value={controller}>{children}</BossStatusContext.Provider>
}

export function useSharedBossStatus(): BossStatusController {
  const context = useContext(BossStatusContext)
  if (!context) {
    throw new Error('useSharedBossStatus 必须在 BossStatusProvider 内使用')
  }
  return context
}
