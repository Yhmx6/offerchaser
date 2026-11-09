'use client'

import { useEffect, useRef, useState } from 'react'
import { usePathname } from 'next/navigation'
import { Loader2, Play, RotateCcw, Square } from 'lucide-react'

import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { useAnnounce } from '@/components/ui/live-region'
import { formatSeconds } from '@/lib/use-boss-status'

import { useSharedBossStatus } from './BossStatusProvider'

type Pending = 'stop' | 'forceReset' | null

const STATE_LABELS: Record<string, string> = {
  IDLE: '空闲',
  STARTING: '启动中',
  RUNNING: '运行中',
  PAUSED_VERIFICATION: '等待人工验证',
  STOPPING: '停止中',
  COMPLETED: '已完成',
  FAILED: '失败',
}

function dotColor(state: string): string {
  if (state === 'RUNNING' || state === 'STARTING') return 'var(--v3-ok)'
  if (state === 'PAUSED_VERIFICATION') return 'var(--v3-warn)'
  if (state === 'FAILED') return 'var(--v3-bad)'
  if (state === 'COMPLETED') return 'var(--v3-ok)'
  return 'var(--v3-muted)'
}

/**
 * 运行胶囊：全站唯一的开始 / 停止 / 继续 / 强制复位入口。
 * 动作与确认文案直接复用 use-boss-status 与既有的 ConfirmDialog 语义。
 */
export default function RunCapsule() {
  const pathname = usePathname()
  const onToday = pathname === '/boss'
  const boss = useSharedBossStatus()
  const {
    status,
    state,
    actionBusy,
    actionMessage,
    loading,
    startDisabled,
    stopDisabled,
    resumeDisabled,
    forceResetDisabled,
    actions,
  } = boss
  const [pending, setPending] = useState<Pending>(null)
  const announce = useAnnounce()
  const announcedState = useRef<string | null>(null)

  const rawState = String(state ?? 'IDLE')
  const label = STATE_LABELS[rawState] ?? rawState
  const busy = actionBusy !== null
  const isRunning = rawState === 'RUNNING' || rawState === 'STARTING'
  const isPaused = rawState === 'PAUSED_VERIFICATION'
  const isStopping = rawState === 'STOPPING'
  const showForceReset = isRunning || isPaused || status?.workerExited === false

  useEffect(() => {
    if (announcedState.current === rawState) return
    announcedState.current = rawState
    announce(`运行状态：${label}`, rawState === 'FAILED' ? 'assertive' : 'polite')
  }, [announce, label, rawState])

  useEffect(() => {
    if (actionMessage) {
      announce(actionMessage.text, actionMessage.tone === 'error' ? 'assertive' : 'polite')
    }
  }, [actionMessage, announce])

  const baseBtn =
    'inline-flex h-7 items-center gap-1 rounded-full px-2.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50'
  const accentBtn = `${baseBtn} bg-[var(--v3-accent)] text-[var(--accent-ink)] hover:opacity-90`
  const dangerBtn = `${baseBtn} border border-[var(--v3-bad)] text-[var(--v3-bad)] hover:bg-[var(--bad-soft)]`

  return (
    <>
      <div
        className="flex items-center gap-1.5 rounded-full border px-2 py-1"
        style={{ borderColor: 'var(--bar-line)', backgroundColor: 'var(--bar-hover)' }}
        title={status?.runId ? `runId ${status.runId}` : '尚未运行'}
      >
        <span
          aria-hidden="true"
          className={`size-2 shrink-0 rounded-full ${isRunning || isPaused ? 'animate-v3-dot' : ''}`}
          style={{ backgroundColor: dotColor(rawState) }}
        />
        <span className="whitespace-nowrap text-xs font-medium text-[var(--bar-text)]">{label}</span>
        {rawState !== 'IDLE' && typeof status?.runningSeconds === 'number' && status.runningSeconds > 0 ? (
          <span className="hidden whitespace-nowrap text-[11px] tabular-nums text-[var(--bar-muted)] sm:inline">
            {formatSeconds(status.runningSeconds)}
          </span>
        ) : null}

        {isStopping ? (
          <span className="inline-flex h-7 items-center gap-1 rounded-full border border-[var(--bar-line)] px-2.5 text-xs text-[var(--bar-muted)]">
            <Loader2 aria-hidden="true" className="size-3 animate-spin" />
            停止中
          </span>
        ) : isRunning ? (
          <>
            <button
              type="button"
              className={dangerBtn}
              disabled={stopDisabled || busy}
              onClick={() => setPending('stop')}
            >
              <Square aria-hidden="true" className="size-3" />
              {actionBusy === 'stop' ? '停止中…' : '停止投递'}
            </button>
          </>
        ) : isPaused ? (
          <button
            type="button"
            className={accentBtn}
            disabled={resumeDisabled || busy}
            onClick={() => void actions.resume()}
          >
            {actionBusy === 'resume' ? '恢复中…' : '继续任务'}
          </button>
        ) : onToday ? null : (
          <button
            type="button"
            className={accentBtn}
            disabled={startDisabled || busy || loading}
            onClick={() => void actions.start()}
          >
            <Play aria-hidden="true" className="size-3" />
            {actionBusy === 'start' ? '启动中…' : '开始投递'}
          </button>
        )}

        {showForceReset ? (
          <button
            type="button"
            className={`${baseBtn} text-[var(--bar-muted)] hover:text-[var(--bar-text)]`}
            disabled={forceResetDisabled || busy}
            onClick={() => setPending('forceReset')}
            title="只改任务状态，不保证后台浏览器操作已经停止"
          >
            <RotateCcw aria-hidden="true" className="size-3" />
            <span className="hidden sm:inline">强制复位</span>
          </button>
        ) : null}
      </div>

      {actionMessage ? (
        <span
          role="status"
          className="hidden max-w-[220px] truncate text-[11px] xl:inline"
          style={{ color: actionMessage.tone === 'success' ? 'var(--v3-ok)' : 'var(--v3-bad)' }}
          title={actionMessage.text}
        >
          {actionMessage.text}
        </span>
      ) : null}

      {transitionDialogs()}
    </>
  )

  function transitionDialogs() {
    return (
      <>
        <ConfirmDialog
          open={pending === 'stop'}
          tone="warning"
          title="停止投递？"
          description="停止会中断当前任务，已发送的消息不会撤回。"
          confirmText="停止投递"
          busy={actionBusy === 'stop'}
          onCancel={() => setPending(null)}
          onConfirm={() => {
            setPending(null)
            void actions.stop()
          }}
        />
        <ConfirmDialog
          open={pending === 'forceReset'}
          tone="danger"
          title="强制复位任务状态？"
          description="只改任务状态，不保证后台浏览器操作已经停止。"
          confirmText="强制复位"
          busy={actionBusy === 'forceReset'}
          onCancel={() => setPending(null)}
          onConfirm={() => {
            setPending(null)
            void actions.forceReset()
          }}
        />
      </>
    )
  }
}

