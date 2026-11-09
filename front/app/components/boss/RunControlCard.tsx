'use client'

import { useState } from 'react'
import { Play, RotateCcw, Square } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { formatSeconds } from '@/lib/use-boss-status'
import type { BossStatusController } from '@/lib/use-boss-status'

/**
 * 运行控制（v2）。
 *
 * 主次分明靠「形状」而不是「颜色」：
 * - 运行中 → 停止投递是 **dangerOutline 描边**（实心红会把 Reading focus 拽走），
 *   继续任务 secondary，强制复位是文字链 + 二次确认；
 * - 空闲/结束 → 开始投递是全页**唯一实心 primary**；
 * - 诊断信息收进 `<details>`，12px 等宽、--text-3，默认折叠。
 */

function Row({ label, value, mono = true }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-0.5">
      <dt className="shrink-0 text-xs text-[hsl(var(--text-3))]">{label}</dt>
      <dd className={`min-w-0 truncate text-xs text-[hsl(var(--text-2))] ${mono ? 'font-mono' : ''}`} title={value}>
        {value}
      </dd>
    </div>
  )
}

export function RunControlCard({ boss, templateName }: { boss: BossStatusController; templateName: string }) {
  const [pending, setPending] = useState<'stop' | 'forceReset' | null>(null)
  const {
    status,
    state,
    sseState,
    actionBusy,
    actionMessage,
    startDisabled,
    stopDisabled,
    resumeDisabled,
    forceResetDisabled,
    loading,
    actions,
  } = boss

  const runId = status?.runId ?? null
  const seconds = status?.runningSeconds ?? 0
  // 少于 1 分钟不显示速度（0.0 条/分 或 Infinity 都是噪音）
  const speedText =
    seconds >= 60
      ? `${((status?.deliveredCount ?? 0) / (seconds / 60)).toFixed(1)} 条/分`
      : '—'
  const budgetUsed = boss.budget?.firstContact?.used ?? 0
  const budgetLimit = boss.budget?.firstContact?.limit ?? 0
  const budgetPctText =
    budgetLimit > 0
      ? `${Math.round((budgetUsed / budgetLimit) * 100)}%（${budgetUsed} / ${budgetLimit}）`
      : `${budgetUsed} 条（上限未设置）`
  const busy = actionBusy !== null
  const isRunning = state === 'RUNNING' || state === 'STARTING'

  return (
    <section aria-label="运行控制" className="flex h-full flex-col">
      <h2 className="text-xs font-medium tracking-[0.08em] text-[hsl(var(--text-3))]">运行控制</h2>

      <div className="mt-3 space-y-2">
        {/* 动作按状态渲染：只给"此刻可用"的按钮。禁用控件不如隐藏。 */}
        <div className="flex flex-wrap items-center gap-2">
          {isRunning ? (
            <Button
              type="button"
              size="md"
              variant="dangerOutline"
              className="h-9 w-auto px-4"
              disabled={stopDisabled || busy}
              onClick={() => setPending('stop')}
            >
              <Square aria-hidden="true" className="mr-1 size-3.5" />
              {actionBusy === 'stop' ? '停止中…' : '停止投递'}
            </Button>
          ) : state === 'PAUSED_VERIFICATION' ? (
            <Button
              type="button"
              size="md"
              variant="primary"
              className="h-9 w-auto px-4"
              disabled={resumeDisabled || busy}
              onClick={() => void actions.resume()}
            >
              {actionBusy === 'resume' ? '恢复中…' : '继续任务'}
            </Button>
          ) : state === 'STOPPING' ? (
            <Button type="button" size="md" variant="dangerOutline" className="h-9 w-auto px-4" disabled>
              停止中…
            </Button>
          ) : (
            <Button
              type="button"
              size="md"
              variant="primary"
              className="h-9 w-auto px-4"
              disabled={startDisabled || busy || loading}
              onClick={() => void actions.start()}
            >
              <Play aria-hidden="true" className="mr-1 size-3.5" />
              {actionBusy === 'start' ? '启动中…' : '开始投递'}
            </Button>
          )}

          {/* 强制复位只在"可能卡住"时出现：跑着的时候，或线程还没退（workerExited === false） */}
          {isRunning || state === 'PAUSED_VERIFICATION' || status?.workerExited === false ? (
            <button
              type="button"
              disabled={forceResetDisabled || busy}
              onClick={() => setPending('forceReset')}
              className="inline-flex items-center gap-1 text-xs text-[hsl(var(--brand-deep))] hover:underline disabled:opacity-50"
            >
              <RotateCcw aria-hidden="true" className="size-3" />
              强制复位
            </button>
          ) : null}
        </div>
      </div>

      {actionMessage ? (
        <p
          className={`mt-2 text-xs ${
            actionMessage.tone === 'success' ? 'text-[hsl(var(--ok-text))]' : 'text-[hsl(var(--bad-text))]'
          }`}
        >
          {actionMessage.text}
        </p>
      ) : null}

      {/* 本轮小结：先给人看的三个数，再给排查看的诊断信息（数据全部由 status 现算，不新增字段） */}
      <div className="mt-4 border-t border-[hsl(var(--border))] pt-2">
        <h3 className="text-xs text-[hsl(var(--text-3))]">本轮小结</h3>
        <dl>
          <div className="grid h-9 grid-cols-[76px_1fr] items-center gap-2 border-b border-[hsl(var(--line))] last:border-0">
            <dt className="text-xs text-[hsl(var(--text-3))]">运行时长</dt>
            <dd className="truncate text-sm tabular-nums text-[hsl(var(--text-2))]">
              {formatSeconds(status?.runningSeconds)}
            </dd>
          </div>
          <div className="grid h-9 grid-cols-[76px_1fr] items-center gap-2 border-b border-[hsl(var(--line))] last:border-0">
            <dt className="text-xs text-[hsl(var(--text-3))]">投递速度</dt>
            <dd className="truncate text-sm tabular-nums text-[hsl(var(--text-2))]">{speedText}</dd>
          </div>
          <div className="grid h-9 grid-cols-[76px_1fr] items-center gap-2 border-b border-[hsl(var(--line))] last:border-0">
            <dt className="text-xs text-[hsl(var(--text-3))]">预算消耗</dt>
            <dd className="truncate text-sm tabular-nums text-[hsl(var(--text-2))]">{budgetPctText}</dd>
          </div>
        </dl>
      </div>

      <details className="mt-3 border-t border-[hsl(var(--border))] pt-2">
        <summary className="cursor-pointer text-xs text-[hsl(var(--text-3))]">诊断信息 ▾</summary>
        <dl className="mt-1.5 space-y-0.5">
          <Row label="runId" value={runId ? runId.slice(0, 12) : '—'} />
          <Row
            label="SSE"
            value={sseState === 'online' ? '已连接' : sseState === 'offline' ? '重连中' : '连接中'}
            mono={false}
          />
          <Row label="canStart" value={status?.canStart === undefined ? '—' : String(status.canStart)} />
          <Row
            label="登录"
            value={status?.isLoggedIn === undefined ? '—' : status.isLoggedIn ? '已登录' : '未登录'}
            mono={false}
          />
          <Row label="workerExited" value={status?.workerExited === undefined ? '—' : String(status.workerExited)} />
          <Row label="生效模板" value={templateName} mono={false} />
        </dl>
      </details>

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
    </section>
  )
}
