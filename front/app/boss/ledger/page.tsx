'use client'

import Link from 'next/link'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { ChevronRight, RefreshCw } from 'lucide-react'

import {
  ApiError,
  getAttempt,
  getAttempts,
  getContacts,
  getEvaluations,
  getRuns,
  putContactState,
  recheckEvaluations,
} from '@/lib/api'
import type {
  AttemptDetail,
  AttemptItem,
  ContactState,
  EvaluationItem,
  RunItem,
} from '@/lib/boss-types'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { DataRow, DataTable, TableEmptyRow, Td, Th, THead } from '@/components/ui/data-table'
import {
  aiDecisionLabel,
  decisionBadgeClass,
  formatDateTime,
  imageStatusLabel,
  kindLabel,
  newIdempotencyKey,
  replyStateLabel,
  ruleDecisionLabel,
  shortId,
  stateLabel,
  textStatusLabel,
} from '@/app/components/boss-shared'

/**
 * 记录（/boss/ledger）：投递明细 / 评估记录 / 任务记录 / 联系人 四实体共用工具栏与表格。
 * 只读 + 原有操作（重评、联系人标记、去待办处理/补发）；不新增发送路径。
 */

type Entity = 'attempts' | 'evaluations' | 'runs' | 'contacts'

const ENTITIES: { key: Entity; label: string; hint: string }[] = [
  { key: 'attempts', label: '投递明细', hint: '文字/图片状态与人工核对入口' },
  { key: 'evaluations', label: '评估记录', hint: '规则/AI 结论；REVIEW 不是发送成功' },
  { key: 'runs', label: '任务记录', hint: '每轮任务的状态与失败原因' },
  { key: 'contacts', label: '联系人', hint: '回复状态与人工接管/停止联系' },
]

const PAGE_SIZE = 20
const REPLY_STATE_OPTIONS = [
  { value: 'UNKNOWN', label: 'UNKNOWN · 未标记' },
  { value: 'REPLIED', label: 'REPLIED · 已回复' },
  { value: 'REJECTED', label: 'REJECTED · 已拒绝' },
  { value: 'INTERVIEW', label: 'INTERVIEW · 已约面' },
  { value: 'NO_REPLY', label: 'NO_REPLY · 未回复' },
]

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function matchesKeyword(value: string, keyword: string): boolean {
  if (!keyword) return true
  return value.toLowerCase().includes(keyword.toLowerCase())
}

function StatusPill({ tone, children }: { tone: 'ok' | 'warn' | 'bad' | 'idle'; children: React.ReactNode }) {
  const color =
    tone === 'ok' ? 'var(--v3-ok)' : tone === 'warn' ? 'var(--v3-warn)' : tone === 'bad' ? 'var(--v3-bad)' : 'var(--v3-muted)'
  const bg =
    tone === 'ok' ? 'var(--ok-soft)' : tone === 'warn' ? 'var(--warn-soft)' : tone === 'bad' ? 'var(--bad-soft)' : 'var(--surface2)'
  return (
    <span className="oc-chip inline-flex items-center rounded-full px-2 py-0.5 text-[11px]" data-tone={tone === 'ok' ? 'ok' : tone === 'warn' ? 'warn' : tone === 'bad' ? 'bad' : 'muted'} data-nodot style={{ color, backgroundColor: bg }}>
      {children}
    </span>
  )
}

function toneOfTextStatus(status?: string | null): 'ok' | 'warn' | 'bad' | 'idle' {
  if (status === 'CONFIRMED') return 'ok'
  if (status === 'UNKNOWN' || status === 'PENDING') return 'warn'
  if (status === 'FAILED') return 'bad'
  return 'idle'
}

function ToolButton({
  onClick,
  disabled,
  children,
  tone = 'quiet',
}: {
  onClick: () => void
  disabled?: boolean
  children: React.ReactNode
  tone?: 'quiet' | 'accent'
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="inline-flex h-9 items-center gap-1.5 rounded-[10px] px-3 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-50"
      style={
        tone === 'accent'
          ? { backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }
          : { border: '1px solid var(--v3-line)', color: 'var(--text)' }
      }
    >
      {children}
    </button>
  )
}

export default function LedgerPage() {
  const [entity, setEntity] = useState<Entity>('attempts')
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [refreshKey, setRefreshKey] = useState(0)

  // 列表数据
  const [attempts, setAttempts] = useState<AttemptItem[]>([])
  const [evaluations, setEvaluations] = useState<EvaluationItem[]>([])
  const [runs, setRuns] = useState<RunItem[]>([])
  const [contacts, setContacts] = useState<ContactState[]>([])

  // 筛选
  const [keyword, setKeyword] = useState('')
  const [attemptTextStatus, setAttemptTextStatus] = useState('')
  const [attemptImageStatus, setAttemptImageStatus] = useState('')
  const [latestOnly, setLatestOnly] = useState(false)
  const [evalRule, setEvalRule] = useState('')
  const [evalAi, setEvalAi] = useState('')
  const [runStatus, setRunStatus] = useState('')
  const [notice, setNotice] = useState<{ tone: 'ok' | 'bad'; text: string } | null>(null)
  const [recheckBusy, setRecheckBusy] = useState(false)

  // 展开
  const [expandedKey, setExpandedKey] = useState<string | null>(null)
  const [attemptDetail, setAttemptDetail] = useState<AttemptDetail | null>(null)
  const [attemptDetailLoading, setAttemptDetailLoading] = useState(false)

  // 联系人标记
  const [contactDraft, setContactDraft] = useState<{ replyState: string; takeover: boolean; stop: boolean; reason: string } | null>(null)
  const [contactSaving, setContactSaving] = useState(false)
  const [contactError, setContactError] = useState<string | null>(null)
  const [confirmContact, setConfirmContact] = useState<ContactState | null>(null)

  useEffect(() => {
    if (typeof window === 'undefined') return
    const params = new URLSearchParams(window.location.search)
    const raw = params.get('entity')
    const allowed: Entity[] = ['attempts', 'evaluations', 'runs', 'contacts']
    if (raw && (allowed as string[]).includes(raw)) setEntity(raw as Entity)
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    setExpandedKey(null)
    try {
      if (entity === 'attempts') {
        const result = await getAttempts({
          page,
          size: PAGE_SIZE,
          textStatus: attemptTextStatus || undefined,
          imageStatus: attemptImageStatus || undefined,
        })
        setAttempts(result.items ?? [])
        setTotal(result.total ?? 0)
      } else if (entity === 'evaluations') {
        const result = await getEvaluations({
          page,
          size: PAGE_SIZE,
          latestOnly: latestOnly || undefined,
          ruleDecision: evalRule || undefined,
          aiDecision: evalAi || undefined,
        })
        setEvaluations(result.items ?? [])
        setTotal(result.total ?? 0)
      } else if (entity === 'runs') {
        const result = await getRuns({ page, size: PAGE_SIZE, status: runStatus || undefined })
        setRuns(result.items ?? [])
        setTotal(result.total ?? 0)
      } else {
        const result = await getContacts({ page, size: PAGE_SIZE })
        setContacts(result.items ?? [])
        setTotal(result.total ?? 0)
      }
    } catch (loadError) {
      setError(errorText(loadError))
    } finally {
      setLoading(false)
    }
  }, [entity, page, attemptTextStatus, attemptImageStatus, latestOnly, evalRule, evalAi, runStatus])

  useEffect(() => {
    void load()
  }, [load, refreshKey])

  const resetFilters = () => {
    setKeyword('')
    setAttemptTextStatus('')
    setAttemptImageStatus('')
    setLatestOnly(false)
    setEvalRule('')
    setEvalAi('')
    setRunStatus('')
    setPage(1)
  }

  const refresh = () => setRefreshKey((value) => value + 1)

  const currentAttempt = useMemo(() => {
    if (entity !== 'attempts' || !expandedKey) return null
    return attempts.find((item) => item.attemptId === expandedKey) ?? null
  }, [entity, expandedKey, attempts])

  useEffect(() => {
    if (!currentAttempt) {
      setAttemptDetail(null)
      return
    }
    let alive = true
    setAttemptDetailLoading(true)
    getAttempt(currentAttempt.attemptId)
      .then((next) => {
        if (alive) setAttemptDetail(next)
      })
      .catch(() => {
        if (alive) setAttemptDetail(null)
      })
      .finally(() => {
        if (alive) setAttemptDetailLoading(false)
      })
    return () => {
      alive = false
    }
  }, [currentAttempt])

  const filteredAttempts = useMemo(
    () =>
      attempts.filter((item) =>
        matchesKeyword(`${item.jobName ?? ''} ${item.companyName ?? ''} ${item.runId ?? ''} ${item.attemptId}`, keyword)
      ),
    [attempts, keyword]
  )
  const filteredEvaluations = useMemo(
    () =>
      evaluations.filter((item) =>
        matchesKeyword(`${item.jobName ?? ''} ${item.companyName ?? ''} ${item.ruleDecision ?? ''} ${item.aiDecision ?? ''}`, keyword)
      ),
    [evaluations, keyword]
  )
  const filteredRuns = useMemo(
    () => runs.filter((item) => matchesKeyword(`${item.runId} ${item.templateName ?? ''} ${item.state ?? ''} ${item.stopReason ?? ''}`, keyword)),
    [runs, keyword]
  )
  const filteredContacts = useMemo(
    () => contacts.filter((item) => matchesKeyword(`${item.hrName ?? ''} ${item.companyName ?? ''} ${item.contactKey}`, keyword)),
    [contacts, keyword]
  )

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const rowsCount =
    entity === 'attempts'
      ? filteredAttempts.length
      : entity === 'evaluations'
        ? filteredEvaluations.length
        : entity === 'runs'
          ? filteredRuns.length
          : filteredContacts.length

  const handleRecheck = async () => {
    if (recheckBusy) return
    setRecheckBusy(true)
    setNotice(null)
    try {
      const result = await recheckEvaluations({ limit: 50 })
      setNotice({
        tone: 'ok',
        text: `重评完成：规则版本 v${result.ruleVersion ?? '—'}，评估 ${result.evaluated ?? 0} 条（通过 ${result.passed ?? 0} / 拒绝 ${result.rejected ?? 0} / 待复核 ${result.reviewed ?? 0}）。只新增评估，未发送任何消息。`,
      })
      refresh()
    } catch (recheckError) {
      setNotice({ tone: 'bad', text: `重评失败：${errorText(recheckError)}` })
    } finally {
      setRecheckBusy(false)
    }
  }

  const startContactEdit = (contact: ContactState) => {
    setContactDraft({
      replyState: contact.replyState || 'UNKNOWN',
      takeover: contact.manualTakeover === true,
      stop: contact.stopContact === true,
      reason: '',
    })
    setContactError(null)
  }

  const handleSaveContact = async (contact: ContactState) => {
    if (!contactDraft) return
    if (!contactDraft.reason.trim()) {
      setContactError('请填写变更原因（写入人工操作审计）')
      return
    }
    setContactSaving(true)
    setContactError(null)
    try {
      await putContactState(contact.contactKey, {
        replyState: contactDraft.replyState,
        manualTakeover: contactDraft.takeover,
        stopContact: contactDraft.stop,
        reason: contactDraft.reason.trim(),
        expectedVersion: contact.version ?? 0,
        idempotencyKey: newIdempotencyKey('contact'),
      })
      setNotice({ tone: 'ok', text: '联系人状态已更新；回复/接管/停止联系会使已批准的跟进计划失效。' })
      setConfirmContact(null)
      setContactDraft(null)
      refresh()
    } catch (saveError) {
      if (saveError instanceof ApiError && saveError.status === 409) {
        setContactError('联系人状态已被其他操作修改，已刷新最新数据，请核对后重试。')
        refresh()
      } else {
        setContactError(`保存失败：${errorText(saveError)}`)
      }
    } finally {
      setContactSaving(false)
    }
  }

  return (
    <div className="animate-v3-in space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs font-medium tracking-[0.1em] text-[var(--v3-muted)]">LEDGER · 只读记录</p>
          <h1 className="mt-1 font-serif text-[34px] font-medium leading-none tracking-[-0.03em]">记录</h1>
        </div>
        <span className="text-xs text-[var(--v3-muted)]">共 {total} 条 · 第 {page}/{totalPages} 页</span>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-1 rounded-full border p-1" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface)' }}>
          {ENTITIES.map((entry) => {
            const active = entry.key === entity
            return (
              <button
                key={entry.key}
                type="button"
                onClick={() => {
                  setEntity(entry.key)
                  setPage(1)
                  setKeyword('')
                  setExpandedKey(null)
                }}
                title={entry.hint}
                className="rounded-full px-3 py-1.5 text-sm font-medium transition-colors"
                style={active ? { backgroundColor: 'var(--accent-soft)', color: 'var(--v3-accent)' } : { color: 'var(--v3-muted)' }}
              >
                {entry.label}
              </button>
            )
          })}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <input
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="当前页搜索（岗位/公司/ID）"
            aria-label="当前页搜索"
            className="h-9 w-[220px] rounded-[10px] border bg-[var(--surface)] px-3 text-sm outline-none"
            style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
          />
          <ToolButton onClick={() => void load()} disabled={loading}>
            <RefreshCw aria-hidden="true" className="size-3.5" />
            刷新
          </ToolButton>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2 text-sm">
        {entity === 'attempts' ? (
          <>
            <FilterChip
              active={attemptTextStatus === 'UNKNOWN'}
              onClick={() => {
                setAttemptTextStatus((value) => (value === 'UNKNOWN' ? '' : 'UNKNOWN'))
                setPage(1)
              }}
            >
              只看待核对
            </FilterChip>
            <MiniSelect
              label="文字状态"
              value={attemptTextStatus}
              onChange={(value) => {
                setAttemptTextStatus(value)
                setPage(1)
              }}
              options={[
                { value: '', label: '全部文字状态' },
                { value: 'PENDING', label: '待确认' },
                { value: 'CONFIRMED', label: '已确认' },
                { value: 'FAILED', label: '发送失败' },
                { value: 'UNKNOWN', label: '结果未知' },
                { value: 'NOT_ATTEMPTED', label: '未尝试' },
              ]}
            />
            <MiniSelect
              label="图片状态"
              value={attemptImageStatus}
              onChange={(value) => {
                setAttemptImageStatus(value)
                setPage(1)
              }}
              options={[
                { value: '', label: '全部图片状态' },
                { value: 'PENDING', label: '待确认' },
                { value: 'CONFIRMED', label: '已确认' },
                { value: 'FAILED', label: '发送失败' },
                { value: 'UNKNOWN', label: '结果未知' },
                { value: 'NOT_ATTEMPTED', label: '未尝试' },
              ]}
            />
          </>
        ) : null}
        {entity === 'evaluations' ? (
          <>
            <FilterChip active={latestOnly} onClick={() => { setLatestOnly((v) => !v); setPage(1) }}>
              每岗位仅最新
            </FilterChip>
            <MiniSelect
              label="规则结果"
              value={evalRule}
              onChange={(value) => {
                setEvalRule(value)
                setPage(1)
              }}
              options={[
                { value: '', label: '全部规则结果' },
                { value: 'PASS', label: '通过' },
                { value: 'REJECT', label: '拒绝' },
                { value: 'REVIEW', label: '待复核' },
              ]}
            />
            <MiniSelect
              label="AI 结果"
              value={evalAi}
              onChange={(value) => {
                setEvalAi(value)
                setPage(1)
              }}
              options={[
                { value: '', label: '全部 AI 结果' },
                { value: 'MATCH', label: '匹配' },
                { value: 'REJECT', label: '拒绝' },
                { value: 'REVIEW', label: '待复核' },
                { value: 'ERROR', label: 'AI 异常' },
              ]}
            />
            <ToolButton tone="accent" onClick={() => void handleRecheck()} disabled={recheckBusy}>
              {recheckBusy ? '重评中…' : '用当前规则重评'}
            </ToolButton>
          </>
        ) : null}
        {entity === 'runs' ? (
          <>
            <FilterChip
              active={runStatus === 'FAILED'}
              onClick={() => {
                setRunStatus((value) => (value === 'FAILED' ? '' : 'FAILED'))
                setPage(1)
              }}
            >
              只看失败
            </FilterChip>
            <MiniSelect
              label="任务状态"
              value={runStatus}
              onChange={(value) => {
                setRunStatus(value)
                setPage(1)
              }}
              options={[
                { value: '', label: '全部任务状态' },
                { value: 'STARTING', label: '启动中' },
                { value: 'RUNNING', label: '运行中' },
                { value: 'PAUSED_VERIFICATION', label: '等待人工验证' },
                { value: 'STOPPING', label: '停止中' },
                { value: 'COMPLETED', label: '已完成' },
                { value: 'FAILED', label: '失败' },
              ]}
            />
          </>
        ) : null}
        <ToolButton onClick={resetFilters}>重置</ToolButton>
        {notice ? (
          <span className="text-xs" style={{ color: notice.tone === 'ok' ? 'var(--v3-ok)' : 'var(--v3-bad)' }}>
            {notice.text}
          </span>
        ) : null}
      </div>

      {error ? (
        <div className="rounded-[12px] px-4 py-3 text-sm" style={{ backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }}>
          加载失败：{error}（保留已有数据，不清空为空态）
        </div>
      ) : null}

      <div className="oc-card rounded-[20px] border bg-[var(--surface)] p-2 shadow-[var(--shadow)]" style={{ borderColor: 'var(--v3-line)' }}>
        {entity === 'attempts' ? (
          <DataTable bare caption="投递明细">
            <THead>
              <tr>
                <Th width={120}>编号</Th>
                <Th>岗位 / 公司</Th>
                <Th width={110}>类型</Th>
                <Th width={100}>文字状态</Th>
                <Th width={100}>图片状态</Th>
                <Th align="right" width={150}>时间</Th>
              </tr>
            </THead>
            <tbody>
              {loading ? (
                <SkeletonRows rows={6} cols={6} />
              ) : rowsCount === 0 ? (
                <TableEmptyRow colSpan={6} title="暂无投递明细" description="导入或运行任务后这里会出现记录" />
              ) : (
                filteredAttempts.map((item) => {
                  const expanded = expandedKey === item.attemptId
                  return (
                    <AttemptRow key={item.attemptId} item={item} expanded={expanded} onToggle={() => setExpandedKey(expanded ? null : item.attemptId)} detail={expanded ? attemptDetail : null} loading={expanded && attemptDetailLoading} />
                  )
                })
              )}
            </tbody>
          </DataTable>
        ) : null}

        {entity === 'evaluations' ? (
          <DataTable bare caption="评估记录">
            <THead>
              <tr>
                <Th width={80}>编号</Th>
                <Th>岗位 / 公司</Th>
                <Th width={100}>规则结果</Th>
                <Th width={100}>AI 结果</Th>
                <Th width={90}>版本</Th>
                <Th align="right" width={160}>时间</Th>
              </tr>
            </THead>
            <tbody>
              {loading ? (
                <SkeletonRows rows={6} cols={6} />
              ) : rowsCount === 0 ? (
                <TableEmptyRow colSpan={6} title="暂无评估记录" description="规则/AI 评估后这里会出现记录；REVIEW 不是发送成功" />
              ) : (
                filteredEvaluations.map((item) => {
                  const expanded = expandedKey === String(item.id)
                  return (
                    <EvaluationRow key={item.id} item={item} expanded={expanded} onToggle={() => setExpandedKey(expanded ? null : String(item.id))} />
                  )
                })
              )}
            </tbody>
          </DataTable>
        ) : null}

        {entity === 'runs' ? (
          <DataTable bare caption="任务记录">
            <THead>
              <tr>
                <Th width={130}>runId</Th>
                <Th width={110}>状态</Th>
                <Th>模板</Th>
                <Th align="right" width={160}>开始</Th>
                <Th>停止原因 / 错误</Th>
              </tr>
            </THead>
            <tbody>
              {loading ? (
                <SkeletonRows rows={6} cols={5} />
              ) : rowsCount === 0 ? (
                <TableEmptyRow colSpan={5} title="暂无任务记录" description="运行过任务后这里会出现记录" />
              ) : (
                filteredRuns.map((item) => {
                  const expanded = expandedKey === item.runId
                  return <RunRow key={item.runId} item={item} expanded={expanded} onToggle={() => setExpandedKey(expanded ? null : item.runId)} />
                })
              )}
            </tbody>
          </DataTable>
        ) : null}

        {entity === 'contacts' ? (
          <DataTable bare caption="联系人">
            <THead>
              <tr>
                <Th>联系人</Th>
                <Th>公司</Th>
                <Th width={110}>回复状态</Th>
                <Th width={100}>人工接管</Th>
                <Th width={100}>停止联系</Th>
                <Th align="right" width={160}>最近联系</Th>
              </tr>
            </THead>
            <tbody>
              {loading ? (
                <SkeletonRows rows={6} cols={6} />
              ) : rowsCount === 0 ? (
                <TableEmptyRow colSpan={6} title="暂无联系人" description="产生投递尝试后会出现联系人记录" />
              ) : (
                filteredContacts.map((item) => {
                  const expanded = expandedKey === item.contactKey
                  return (
                    <ContactRow
                      key={item.contactKey}
                      item={item}
                      expanded={expanded}
                      onToggle={() => {
                        if (expanded) {
                          setExpandedKey(null)
                          setContactDraft(null)
                          return
                        }
                        setExpandedKey(item.contactKey)
                        startContactEdit(item)
                      }}
                      draft={expanded ? contactDraft : null}
                      setDraft={setContactDraft}
                      error={expanded ? contactError : null}
                      saving={contactSaving}
                      onSave={() => {
                        if (!contactDraft?.reason.trim()) {
                          setContactError('请填写变更原因（写入人工操作审计）')
                          return
                        }
                        setConfirmContact(item)
                      }}
                    />
                  )
                })
              )}
            </tbody>
          </DataTable>
        ) : null}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
        <div className="flex items-center gap-2">
          <ToolButton onClick={() => setPage((value) => Math.max(1, value - 1))} disabled={loading || page <= 1}>
            上一页
          </ToolButton>
          <span className="tabular-nums text-[var(--v3-muted)]">第 {page} / {totalPages} 页</span>
          <ToolButton onClick={() => setPage((value) => Math.min(totalPages, value + 1))} disabled={loading || page >= totalPages}>
            下一页
          </ToolButton>
        </div>
        <span className="text-xs text-[var(--v3-muted)]">记录只读；决策在「待办」完成</span>
      </div>

      <ConfirmDialog
        open={confirmContact !== null}
        tone="warning"
        title="保存联系人标记？"
        description="只记录人工判定，不读取或发送任何消息；回复/接管/停止联系会使该联系人已批准的跟进计划失效。"
        confirmText="保存标记"
        busy={contactSaving}
        onCancel={() => setConfirmContact(null)}
        onConfirm={() => {
          if (confirmContact) void handleSaveContact(confirmContact)
        }}
      />
    </div>
  )
}

function FilterChip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className="inline-flex h-9 items-center gap-1.5 rounded-full border px-3 text-sm"
      style={
        active
          ? { borderColor: 'var(--v3-accent)', backgroundColor: 'var(--accent-soft)', color: 'var(--v3-accent)' }
          : { borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }
      }
    >
      <span aria-hidden="true" className="size-1.5 rounded-full" style={{ backgroundColor: active ? 'var(--v3-accent)' : 'var(--v3-muted)' }} />
      {children}
    </button>
  )
}

function MiniSelect({
  label,
  value,
  onChange,
  options,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  options: { value: string; label: string }[]
}) {
  return (
    <label className="inline-flex items-center gap-1.5 text-xs text-[var(--v3-muted)]">
      {label}
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-label={label}
        className="h-9 rounded-[10px] border bg-[var(--surface)] px-2 text-sm text-[var(--text)] outline-none"
        style={{ borderColor: 'var(--v3-line)' }}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  )
}

function SkeletonRows({ rows, cols }: { rows: number; cols: number }) {
  return (
    <>
      {Array.from({ length: rows }).map((_, rowIndex) => (
        <tr key={rowIndex} className="border-b" style={{ borderColor: 'var(--v3-line)' }}>
          {Array.from({ length: cols }).map((__, colIndex) => (
            <td key={colIndex} className="px-3 py-3">
              <div className="h-3 animate-pulse rounded bg-[var(--surface2)]" />
            </td>
          ))}
        </tr>
      ))}
    </>
  )
}

function AttemptRow({
  item,
  expanded,
  onToggle,
  detail,
  loading,
}: {
  item: AttemptItem
  expanded: boolean
  onToggle: () => void
  detail: AttemptDetail | null
  loading: boolean
}) {
  const needsAttention = item.textStatus === 'UNKNOWN' || item.imageStatus === 'UNKNOWN' || item.imageStatus === 'FAILED'
  return (
    <>
      <DataRow onClick={onToggle} ariaExpanded={expanded} tone={needsAttention ? 'warning' : undefined}>
        <Td>
          <code className="v3-mono text-xs">{shortId(item.attemptId, 12, 6)}</code>
        </Td>
        <Td>
          <span className="block truncate">{item.jobName || '—'}</span>
          <span className="block truncate text-xs text-[var(--v3-muted)]">{item.companyName || '—'}</span>
        </Td>
        <Td>{kindLabel(item.kind)}</Td>
        <Td>
          <StatusPill tone={toneOfTextStatus(item.textStatus)}>{textStatusLabel(item.textStatus)}</StatusPill>
        </Td>
        <Td>
          <StatusPill tone={toneOfTextStatus(item.imageStatus)}>{imageStatusLabel(item.imageStatus)}</StatusPill>
        </Td>
        <Td align="right" className="text-xs text-[var(--v3-muted)]">
          {formatDateTime(item.startedAt)}
        </Td>
      </DataRow>
      {expanded ? (
        <tr className="border-b" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }}>
          <Td colSpan={6}>
            <div className="grid gap-1 py-1 text-sm sm:grid-cols-2">
              <p>attemptId：<code className="v3-mono text-xs">{item.attemptId}</code></p>
              <p>版本：v{item.version ?? '—'}</p>
              <p>关联评估：{loading ? '加载中…' : detail?.evaluation ? `${ruleDecisionLabel(detail.evaluation.ruleDecision)} / AI ${aiDecisionLabel(detail.evaluation.aiDecision)}` : '—'}</p>
              <p>错误：{item.errorDetail || '—'}</p>
              <p className="sm:col-span-2">
                需要处理：
                {needsAttention ? (
                  <Link
                    href={item.textStatus === 'UNKNOWN' ? '/boss/inbox?kind=resolve' : '/boss/inbox?kind=resend'}
                    className="ml-1 inline-flex items-center gap-0.5 text-[var(--v3-accent)] underline underline-offset-2"
                  >
                    去待办处理
                    <ChevronRight aria-hidden="true" className="size-3" />
                  </Link>
                ) : (
                  <span className="ml-1 text-[var(--v3-muted)]">无需处理</span>
                )}
              </p>
            </div>
          </Td>
        </tr>
      ) : null}
    </>
  )
}

function EvaluationRow({ item, expanded, onToggle }: { item: EvaluationItem; expanded: boolean; onToggle: () => void }) {
  return (
    <>
      <DataRow onClick={onToggle} ariaExpanded={expanded}>
        <Td>{item.id}</Td>
        <Td>
          <span className="block truncate">{item.jobName || '—'}</span>
          <span className="block truncate text-xs text-[var(--v3-muted)]">{item.companyName || '—'}</span>
        </Td>
        <Td>
          <span className={`inline-flex rounded-full px-2 py-0.5 text-[11px] ${decisionBadgeClass(item.ruleDecision)}`}>
            {ruleDecisionLabel(item.ruleDecision)}
          </span>
        </Td>
        <Td>
          <span className={`inline-flex rounded-full px-2 py-0.5 text-[11px] ${decisionBadgeClass(item.aiDecision)}`}>
            {aiDecisionLabel(item.aiDecision)}
          </span>
        </Td>
        <Td>v{item.ruleConfigVersion ?? item.id}</Td>
        <Td align="right" className="text-xs text-[var(--v3-muted)]">
          {formatDateTime(item.createdAt)}
        </Td>
      </DataRow>
      {expanded ? (
        <tr className="border-b" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }}>
          <Td colSpan={6}>
            <div className="grid gap-1 py-1 text-sm">
              <p className="text-xs text-[var(--v3-warn)]">REVIEW / 待复核不是发送成功；真实发送由投递链路单独确认。</p>
              <p>规则原因：{item.ruleReasonCode || '—'} {item.ruleEvidence || ''}</p>
              <p>AI 分析：{item.aiAnalysis || '—'}</p>
              <p>AI 输出：{item.aiMessage || '—'}</p>
              <p>runId：<code className="v3-mono text-xs">{item.runId || '—'}</code></p>
            </div>
          </Td>
        </tr>
      ) : null}
    </>
  )
}

function RunRow({ item, expanded, onToggle }: { item: RunItem; expanded: boolean; onToggle: () => void }) {
  return (
    <>
      <DataRow onClick={onToggle} ariaExpanded={expanded}>
        <Td>
          <code className="v3-mono text-xs">{shortId(item.runId, 12, 6)}</code>
        </Td>
        <Td>
          <StatusPill tone={item.state === 'FAILED' ? 'bad' : item.state === 'RUNNING' ? 'warn' : 'ok'}>{stateLabel(item.state)}</StatusPill>
        </Td>
        <Td>{item.templateName || '—'}</Td>
        <Td align="right" className="text-xs text-[var(--v3-muted)]">
          {formatDateTime(item.startedAt)}
        </Td>
        <Td className="max-w-[320px]">
          <span className="block truncate">{item.stopReason || item.error || '—'}</span>
        </Td>
      </DataRow>
      {expanded ? (
        <tr className="border-b" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }}>
          <Td colSpan={5}>
            <div className="grid gap-1 py-1 text-sm sm:grid-cols-2">
              <p>runId：<code className="v3-mono text-xs">{item.runId}</code></p>
              <p>平台：{item.platform || '—'}</p>
              <p>模板：{item.templateName || '—'}{item.templateDirection ? ` · ${item.templateDirection}` : ''}</p>
              <p>结束：{formatDateTime(item.endedAt)}</p>
              <p>停止原因：{item.stopReason || '—'}</p>
              <p>错误：{item.error || '—'}</p>
            </div>
          </Td>
        </tr>
      ) : null}
    </>
  )
}

function ContactRow({
  item,
  expanded,
  onToggle,
  draft,
  setDraft,
  error,
  saving,
  onSave,
}: {
  item: ContactState
  expanded: boolean
  onToggle: () => void
  draft: { replyState: string; takeover: boolean; stop: boolean; reason: string } | null
  setDraft: (next: { replyState: string; takeover: boolean; stop: boolean; reason: string } | null) => void
  error: string | null
  saving: boolean
  onSave: () => void
}) {
  return (
    <>
      <DataRow onClick={onToggle} ariaExpanded={expanded}>
        <Td>
          <span className="block truncate">{item.hrName || '未知 HR'}</span>
          <span className="block truncate text-xs text-[var(--v3-muted)]">{item.contactKey}</span>
        </Td>
        <Td>{item.companyName || '—'}</Td>
        <Td>{replyStateLabel(item.replyState)}</Td>
        <Td>{item.manualTakeover ? '是' : '否'}</Td>
        <Td>{item.stopContact ? '是' : '否'}</Td>
        <Td align="right" className="text-xs text-[var(--v3-muted)]">
          {formatDateTime(item.lastContactAt)}
        </Td>
      </DataRow>
      {expanded && draft ? (
        <tr className="border-b" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }}>
          <Td colSpan={6}>
            <div className="space-y-3 py-2">
              <p className="text-xs text-[var(--v3-muted)]">
                只记录人工判定，不读取或发送任何消息。当前版本 v{item.version ?? 0}。
              </p>
              <div className="grid gap-3 sm:grid-cols-3">
                <label className="space-y-1 text-sm">
                  <span className="block text-xs text-[var(--v3-muted)]">回复状态</span>
                  <select
                    value={draft.replyState}
                    onChange={(event) => setDraft({ ...draft, replyState: event.target.value })}
                    className="h-9 w-full rounded-[10px] border bg-[var(--surface)] px-2 text-sm"
                    style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
                  >
                    {REPLY_STATE_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex items-center gap-2 pt-5 text-sm">
                  <input
                    type="checkbox"
                    checked={draft.takeover}
                    onChange={(event) => setDraft({ ...draft, takeover: event.target.checked })}
                  />
                  人工接管
                </label>
                <label className="flex items-center gap-2 pt-5 text-sm">
                  <input
                    type="checkbox"
                    checked={draft.stop}
                    onChange={(event) => setDraft({ ...draft, stop: event.target.checked })}
                  />
                  停止联系
                </label>
              </div>
              <label className="block space-y-1 text-sm">
                <span className="block text-xs text-[var(--v3-muted)]">变更原因（必填，≤500 字）</span>
                <textarea
                  rows={2}
                  maxLength={500}
                  value={draft.reason}
                  onChange={(event) => setDraft({ ...draft, reason: event.target.value })}
                  className="w-full rounded-[10px] border bg-[var(--surface)] px-3 py-2 text-sm"
                  style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
                />
              </label>
              {error ? <p className="text-sm" style={{ color: 'var(--v3-bad)' }}>{error}</p> : null}
              <div className="flex gap-2">
                <button
                  type="button"
                  disabled={saving}
                  onClick={onSave}
                  className="inline-flex h-9 items-center rounded-[10px] px-3 text-sm font-medium disabled:opacity-50"
                  style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
                >
                  {saving ? '保存中…' : '保存标记'}
                </button>
              </div>
            </div>
          </Td>
        </tr>
      ) : null}
    </>
  )
}

