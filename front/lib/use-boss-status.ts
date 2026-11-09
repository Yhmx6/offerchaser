'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import {
  ApiError,
  forceReset,
  getBossStatus,
  getDeliverySummary,
  resumeVerification,
  startBoss,
  stopBoss,
} from '@/lib/api'
import { subscribeBossEvents } from '@/lib/boss-events'
import type { BossStatus, DeliveryOverallSummary, SseProgress } from '@/lib/boss-types'

/**
 * useBossStatus —— `/boss` 总览的数据层（S4 从 BossStatusCard 抽出，`lib/api.ts` 契约零改动）。
 *
 * 职责：
 * - GET /api/boss/status + GET /api/boss/delivery/summary；
 * - 订阅 /api/boss/stream：snapshot 直接覆盖状态，progress 触发状态刷新（摘要 3s 节流）；
 * - 30s 轮询兜底（SSE 不可用时保证最终一致）；
 * - 开始 / 停止 / 继续 / 强制复位四个动作与按钮禁用派生。
 *
 * 页面只调用一次本 hook，把返回值分发给 CoverNumbers / RunControlCard / PipelineStrip /
 * RecentEvents，避免每个组件各自轮询。
 */

export type BossSseState = 'connecting' | 'online' | 'offline'
export type BossActionMessage = { tone: 'success' | 'error'; text: string }

const STATUS_POLL_INTERVAL_MS = 30000
const SUMMARY_THROTTLE_MS = 3000

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function messageOf(result: unknown, fallback: string): string {
  if (isRecord(result) && typeof result.message === 'string' && result.message) return result.message
  return fallback
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function clockNow(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

/** 运行时长：`1 小时 3 分` / `12 分 5 秒` / `8 秒` */
export function formatSeconds(seconds?: number | null): string {
  if (seconds === undefined || seconds === null || !Number.isFinite(seconds) || seconds <= 0) return '-'
  const total = Math.floor(seconds)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  if (h > 0) return `${h} 小时 ${m} 分`
  if (m > 0) return `${m} 分 ${s} 秒`
  return `${s} 秒`
}

/** 预算数字：-1 = 不限 */
export function budgetText(value?: number | null): string {
  if (value === undefined || value === null) return '-'
  return value < 0 ? '不限' : String(value)
}

export function useBossStatus() {
  const [status, setStatus] = useState<BossStatus | null>(null)
  const [summary, setSummary] = useState<DeliveryOverallSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [statusError, setStatusError] = useState<string | null>(null)
  const [summaryError, setSummaryError] = useState<string | null>(null)
  const [lastUpdated, setLastUpdated] = useState<string>('')
  const [sseState, setSseState] = useState<BossSseState>('connecting')
  const [lastProgress, setLastProgress] = useState<SseProgress | null>(null)
  const [actionBusy, setActionBusy] = useState<string | null>(null)
  const [actionMessage, setActionMessage] = useState<BossActionMessage | null>(null)

  const summaryTimer = useRef<number | null>(null)
  const statusTimer = useRef<number | null>(null)

  const refreshStatus = useCallback(async () => {
    try {
      const next = await getBossStatus()
      setStatus(next)
      setStatusError(null)
      setLastUpdated(clockNow())
    } catch (error) {
      setStatusError(errorText(error))
    }
  }, [])

  const refreshSummary = useCallback(async () => {
    try {
      const next = await getDeliverySummary()
      setSummary(next)
      setSummaryError(null)
    } catch (error) {
      setSummaryError(errorText(error))
    }
  }, [])

  const refreshAll = useCallback(async () => {
    await Promise.all([refreshStatus(), refreshSummary()])
  }, [refreshStatus, refreshSummary])

  /** progress 事件很密集，status 也做短节流，避免每个阶段事件都发一次 GET */
  const scheduleStatusRefresh = useCallback(() => {
    if (typeof window === 'undefined') return
    if (statusTimer.current !== null) return
    statusTimer.current = window.setTimeout(() => {
      statusTimer.current = null
      void refreshStatus()
    }, 700)
  }, [refreshStatus])

  /** progress 事件很密集，摘要接口做 3s 节流 */
  const scheduleSummaryRefresh = useCallback(() => {
    if (typeof window === 'undefined') return
    if (summaryTimer.current !== null) return
    summaryTimer.current = window.setTimeout(() => {
      summaryTimer.current = null
      void refreshSummary()
    }, SUMMARY_THROTTLE_MS)
  }, [refreshSummary])

  useEffect(() => {
    let alive = true
    void Promise.all([refreshStatus(), refreshSummary()]).finally(() => {
      if (alive) setLoading(false)
    })

    const client = subscribeBossEvents({
      onSnapshot: (snapshot) => {
        setStatus(snapshot)
        setStatusError(null)
        setLastUpdated(clockNow())
      },
      onProgress: (progress) => {
        setLastProgress(progress)
        scheduleStatusRefresh()
        scheduleSummaryRefresh()
      },
      onOpen: () => setSseState('online'),
      onError: () => setSseState('offline'),
      onSnapshotError: () => {
        // 重连后快照拉取失败：保持 SSE 重连，状态由轮询兜底
      },
    })

    const poller = window.setInterval(() => {
      void refreshStatus()
    }, STATUS_POLL_INTERVAL_MS)

    return () => {
      alive = false
      client.close()
      window.clearInterval(poller)
      if (summaryTimer.current !== null) {
        window.clearTimeout(summaryTimer.current)
        summaryTimer.current = null
      }
      if (statusTimer.current !== null) {
        window.clearTimeout(statusTimer.current)
        statusTimer.current = null
      }
    }
  }, [refreshStatus, refreshSummary, scheduleStatusRefresh, scheduleSummaryRefresh])

  const runAction = useCallback(
    async (label: string, fallback: string, action: () => Promise<unknown>) => {
      setActionBusy(label)
      setActionMessage(null)
      try {
        const result = await action()
        setActionMessage({ tone: 'success', text: messageOf(result, fallback) })
        await refreshStatus()
        await refreshSummary()
      } catch (error) {
        const message = errorText(error)
        if (error instanceof ApiError && (error.code === 'RUN_ID_MISMATCH' || error.status === 409)) {
          setActionMessage({
            tone: 'error',
            text: `${message}（任务已切换，已重新拉取最新状态）`,
          })
          await refreshStatus()
        } else {
          setActionMessage({ tone: 'error', text: message })
        }
      } finally {
        setActionBusy(null)
      }
    },
    [refreshStatus, refreshSummary]
  )

  // —— 四个动作 ——
  const actions = useMemo(
    () => ({
      start: () => runAction('start', '任务启动成功', startBoss),
      stop: () => runAction('stop', '停止请求已发送', () => stopBoss(status?.runId ?? null)),
      resume: () => runAction('resume', '已恢复暂停的任务', () => resumeVerification(status?.runId ?? null)),
      forceReset: () =>
        runAction('forceReset', '强制复位请求已发送', () => forceReset(status?.runId ?? null)),
    }),
    [runAction, status?.runId]
  )

  // —— 派生：状态与按钮可用性（契约 3.4：STOPPING / workerExited=false 时禁用开始）——
  const state = status?.state ?? 'IDLE'
  const isTerminal = state === 'IDLE' || state === 'COMPLETED' || state === 'FAILED'
  const isStopping = state === 'STOPPING'
  const verificationPaused = status?.verificationPaused === true || state === 'PAUSED_VERIFICATION'
  const canStart = Boolean(
    status?.canStart ?? (status?.isRunning !== true && status?.workerExited !== false && !isStopping)
  )
  // 首屏 status 尚未返回时不要放行开始：此时 isLoggedIn/workerExited 还是 undefined，
  // 否则用户极快点击会在状态加载前触发启动请求。
  const startDisabled =
    loading || status == null || !canStart || isStopping || status.workerExited === false || status.isLoggedIn === false
  const stopDisabled = !status?.isRunning || isTerminal
  const resumeDisabled = !verificationPaused
  // 状态已终态但 worker 线程还没退出时，仍允许强制复位（否则开始按钮被 workerExited 卡住，复位也点不了）
  const forceResetDisabled = isTerminal && status?.workerExited !== false

  const budget = status?.budget ?? summary?.budget
  const delivery = status?.delivery ?? summary?.attempts

  return {
    // 原始数据
    status,
    summary,
    budget,
    delivery,
    evaluation: summary?.evaluations ?? null,
    pendingResolutions: status?.pendingResolutions ?? 0,
    // 加载与连接
    loading,
    statusError,
    summaryError,
    lastUpdated,
    sseState,
    lastProgress,
    // 动作
    actionBusy,
    actionMessage,
    refreshStatus,
    refreshSummary,
    refreshAll,
    runAction,
    actions,
    // 派生
    state,
    isTerminal,
    isStopping,
    verificationPaused,
    canStart,
    startDisabled,
    stopDisabled,
    resumeDisabled,
    forceResetDisabled,
  }
}

export type BossStatusController = ReturnType<typeof useBossStatus>
