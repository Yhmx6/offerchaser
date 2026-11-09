'use client'

import { useCallback, useEffect, useState } from 'react'
import { MessageCircleReply, RefreshCw, Send, UserRound } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { InlineAlert } from '@/components/ui/inline-alert'
import { StatusTag } from '@/components/ui/status-tag'
import {
  ApiError,
  getDeliveryStatusSyncSummary,
  runDeliveryStatusSync,
} from '@/lib/api'
import type { StatusSyncRun, StatusSyncSummary } from '@/lib/boss-types'
import { formatDateTime } from '@/app/components/boss-shared'

type Tone = 'ok' | 'warn' | 'bad' | 'idle'

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function statusTone(status?: string | null): Tone {
  if (status === 'COMPLETED') return 'ok'
  if (status === 'RUNNING') return 'warn'
  if (status === 'FAILED') return 'bad'
  return 'idle'
}

function statusLabel(status?: string | null): string {
  if (status === 'COMPLETED') return '已完成'
  if (status === 'RUNNING') return '进行中'
  if (status === 'FAILED') return '失败'
  return '未同步'
}

function CategoryRow({
  label,
  icon,
  total,
  read,
  replied,
  unread,
  unknown,
}: {
  label: string
  icon: React.ReactNode
  total: number
  read: number
  replied: number
  unread: number
  unknown: number
}) {
  return (
    <div className="grid grid-cols-[1.35fr_repeat(5,1fr)] items-center gap-2 border-t border-hairline py-2 text-sm first:border-t-0">
      <span className="flex items-center gap-2 font-medium text-[hsl(var(--foreground))]">
        {icon}
        {label}
      </span>
      <span className="text-right tabular-nums">{total}</span>
      <span className="text-right tabular-nums text-[hsl(var(--ok-text))]">{read}</span>
      <span className="text-right tabular-nums text-[hsl(var(--brand-deep))]">{replied}</span>
      <span className="text-right tabular-nums text-[hsl(var(--warn-text))]">{unread}</span>
      <span className="text-right tabular-nums text-[hsl(var(--text-3))]">{unknown}</span>
    </div>
  )
}

function SyncRunBlock({ title, run }: { title: string; run?: StatusSyncRun | null }) {
  return (
    <div className="min-w-0">
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-xs font-medium tracking-[0.08em] text-[hsl(var(--text-3))]">{title}</p>
        <StatusTag tone={statusTone(run?.status)}>{statusLabel(run?.status)}</StatusTag>
      </div>
      {run ? (
        <p className="mt-1 text-xs tabular-nums text-[hsl(var(--text-3))]">
          {run.targetDate} · {formatDateTime(run.finishedAt || run.startedAt)}
          {run.error ? ` · ${run.error}` : ''}
        </p>
      ) : (
        <p className="mt-1 text-xs text-[hsl(var(--text-3))]">暂无记录</p>
      )}
    </div>
  )
}

/** 投递状态同步卡：只读统计。 */
export function StatusSyncCard() {
  const [summary, setSummary] = useState<StatusSyncSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [message, setMessage] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)

  const loadSummary = useCallback(async () => {
    try {
      setSummary(await getDeliveryStatusSyncSummary())
    } catch (error) {
      setMessage({ tone: 'error', text: `读取同步摘要失败：${errorText(error)}` })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadSummary()
  }, [loadSummary])

  const handleSync = async () => {
    if (syncing) return
    setSyncing(true)
    setMessage(null)
    try {
      const result = await runDeliveryStatusSync()
      await loadSummary()
      setMessage({
        tone: 'success',
        text: `已读取 ${result.run.autoTotal + result.run.manualTotal + result.run.hrTotal} 条会话`,
      })
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setMessage({ tone: 'error', text: error.message })
      } else {
        setMessage({ tone: 'error', text: `读取失败：${errorText(error)}` })
      }
    } finally {
      setSyncing(false)
    }
  }

  const active = summary?.active ?? null
  const startup = summary?.startup ?? null
  const current = active ?? summary?.latest ?? null
  const auto = current ?? {
    autoTotal: 0, autoRead: 0, autoReplied: 0, autoUnread: 0, autoUnknown: 0,
    manualTotal: 0, manualRead: 0, manualReplied: 0, manualUnread: 0, manualUnknown: 0,
    hrTotal: 0, hrRead: 0, hrReplied: 0, hrUnread: 0, hrUnknown: 0,
  }

  return (
    <section className="rounded-card border border-hairline bg-[hsl(var(--card))] p-4 md:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-xs font-medium tracking-[0.08em] text-[hsl(var(--text-3))]">投递状态同步</p>
        </div>
        <Button type="button" size="sm" variant="secondary" disabled={syncing || loading} onClick={() => void handleSync()}>
          <RefreshCw aria-hidden="true" className={`mr-1 size-3.5 ${syncing ? 'animate-spin' : ''}`} />
          {syncing ? '读取中…' : '读取今日投递状态'}
        </Button>
      </div>

      {message ? (
        <div className="mt-3">
          <InlineAlert variant={message.tone === 'error' ? 'danger' : 'success'}>{message.text}</InlineAlert>
        </div>
      ) : null}

      <div className="mt-4 grid grid-cols-1 gap-4 min-[900px]:grid-cols-[1fr_1fr]">
        <SyncRunBlock title="今日主动同步" run={active} />
        <SyncRunBlock title="昨天启动补读" run={startup} />
      </div>

      <div className="mt-4 rounded-control border border-hairline bg-surface-inset px-3 py-2">
        <div className="grid grid-cols-[1.35fr_repeat(5,1fr)] gap-2 pb-1 text-xs text-[hsl(var(--text-3))]">
          <span>来源</span>
          <span className="text-right">总数</span>
          <span className="text-right">已读</span>
          <span className="text-right">已回复</span>
          <span className="text-right">未读</span>
          <span className="text-right">未知</span>
        </div>
        <CategoryRow
          label="自动投递"
          icon={<Send aria-hidden="true" className="size-3.5 text-[hsl(var(--brand-deep))]" />}
          total={auto.autoTotal}
          read={auto.autoRead}
          replied={auto.autoReplied}
          unread={auto.autoUnread}
          unknown={auto.autoUnknown}
        />
        <CategoryRow
          label="手动投递"
          icon={<UserRound aria-hidden="true" className="size-3.5 text-[hsl(var(--clay-600))]" />}
          total={auto.manualTotal}
          read={auto.manualRead}
          replied={auto.manualReplied}
          unread={auto.manualUnread}
          unknown={auto.manualUnknown}
        />
        <CategoryRow
          label="HR 主动招呼"
          icon={<MessageCircleReply aria-hidden="true" className="size-3.5 text-[hsl(var(--ok-text))]" />}
          total={auto.hrTotal}
          read={auto.hrRead}
          replied={auto.hrReplied}
          unread={auto.hrUnread}
          unknown={auto.hrUnknown}
        />
      </div>

      <p className="mt-3 text-xs text-[hsl(var(--text-3))]">每日首次启动会自动补读前一天。</p>
    </section>
  )
}
