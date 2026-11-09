'use client'

import { useSharedBossStatus } from './BossStatusProvider'

const NOTIFY_STATES = new Set(['PAUSED_VERIFICATION', 'FAILED', 'COMPLETED'])

function stateLabelOf(state: string): string {
  switch (state) {
    case 'PAUSED_VERIFICATION':
      return '等待人工验证'
    case 'FAILED':
      return '任务失败'
    case 'COMPLETED':
      return '任务已完成'
    default:
      return state
  }
}

/**
 * 通知条：仅 PAUSED_VERIFICATION / FAILED / COMPLETED 时出现在顶栏下方。
 * 展示状态、停止原因与错误；不提供操作，运行控制只在顶栏胶囊。
 */
export default function NotificationBar() {
  const { status, state } = useSharedBossStatus()
  const rawState = String(state ?? 'IDLE')
  if (!NOTIFY_STATES.has(rawState)) return null

  const tone =
    rawState === 'FAILED'
      ? 'bad'
      : rawState === 'PAUSED_VERIFICATION'
        ? 'warn'
        : 'ok'
  const borderTone =
    tone === 'bad' ? 'var(--v3-bad)' : tone === 'warn' ? 'var(--v3-warn)' : 'var(--v3-ok)'
  const reason = status?.stopReason || status?.verificationReason || status?.error || ''

  return (
    <div
      role="status"
      className="border-b bg-[var(--surface)] text-[var(--text)]"
      style={{ borderColor: 'var(--v3-line)' }}
    >
      <div className="mx-auto flex max-w-[1360px] flex-wrap items-center gap-x-3 gap-y-1 px-8 py-2 text-sm max-sm:px-4">
        <span
          aria-hidden="true"
          className="size-2 shrink-0 rounded-full"
          style={{ backgroundColor: borderTone }}
        />
        <span className="font-medium" style={{ color: borderTone }}>
          {stateLabelOf(rawState)}
        </span>
        {reason ? (
          <span className="min-w-0 truncate text-[var(--v3-muted)]" title={reason}>
            {reason}
          </span>
        ) : null}
        {rawState === 'COMPLETED' ? (
          <span className="text-xs text-[var(--v3-muted)]">结果以投递台账/记录为准，已完成不代表每条都成功</span>
        ) : null}
      </div>
    </div>
  )
}
