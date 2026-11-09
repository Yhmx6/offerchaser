'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { GripVertical, Pencil, Plus, RefreshCw, Sparkles } from 'lucide-react'

import AiTemplateWizard from '@/app/components/setup/AiTemplateWizard'
import ModelServiceCard from '@/app/boss/templates/ModelServiceCard'
import TemplateConfigEditor from '@/app/boss/templates/TemplateConfigEditor'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import {
  ApiError,
  activateTemplate,
  captureTemplate,
  createTemplate,
  deleteTemplate,
  duplicateTemplate,
  getTemplates,
  reorderTemplates,
  updateTemplate,
} from '@/lib/api'
import type { TemplateView } from '@/lib/boss-types'
import { formatDateTime } from '@/app/components/boss-shared'

/**
 * 配置（/boss/setup）：方向模板栏 + 编辑区 + 模型服务。
 * 规则/AI 只在当前方向的模板配置里维护；旧 /boss/rules、/ai-config、/boss/advanced 重定向到这里。
 */

type ConfirmKind = 'activate' | 'capture' | 'delete' | null

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export default function SetupPage() {
  const [templates, setTemplates] = useState<TemplateView[]>([])
  const [activeTemplate, setActiveTemplate] = useState<TemplateView | null>(null)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [editing, setEditing] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ tone: 'ok' | 'bad'; text: string } | null>(null)
  const [busyKey, setBusyKey] = useState<string | null>(null)

  const [creating, setCreating] = useState(false)
  const [aiWizardOpen, setAiWizardOpen] = useState(false)
  const [createForm, setCreateForm] = useState({ name: '', direction: '', description: '' })
  const [metaForm, setMetaForm] = useState({ name: '', direction: '', description: '' })
  const [confirm, setConfirm] = useState<{ kind: ConfirmKind; template: TemplateView | null }>({ kind: null, template: null })

  const dragIdRef = useRef<number | null>(null)

  const loadTemplates = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    setError(null)
    try {
      const data = await getTemplates()
      const list = data?.items ?? []
      setTemplates(list)
      setActiveTemplate(data?.active ?? null)
      setSelectedId((prev) => {
        if (prev && list.some((item) => item.id === prev)) return prev
        return data?.active?.id ?? list[0]?.id ?? null
      })
    } catch (loadError) {
      setError(errorText(loadError))
    } finally {
      if (!silent) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadTemplates()
  }, [loadTemplates])

  const selected = useMemo(
    () => templates.find((item) => item.id === selectedId) ?? null,
    [templates, selectedId]
  )

  useEffect(() => {
    setEditing(false)
  }, [selectedId])

  useEffect(() => {
    if (!selected) {
      setMetaForm({ name: '', direction: '', description: '' })
      return
    }
    setMetaForm({
      name: selected.name ?? '',
      direction: selected.direction ?? '',
      description: selected.description ?? '',
    })
  }, [selected])

  const refreshQuietly = () => loadTemplates(true)

  const runAction = async (key: string, action: () => Promise<unknown>, successText: string) => {
    setBusyKey(key)
    setNotice(null)
    try {
      await action()
      setNotice({ tone: 'ok', text: successText })
      await refreshQuietly()
    } catch (actionError) {
      const suffix =
        actionError instanceof ApiError && actionError.status === 409
          ? '（模板状态已变化，已刷新列表）'
          : ''
      setNotice({ tone: 'bad', text: `${errorText(actionError)}${suffix}` })
      await refreshQuietly()
    } finally {
      setBusyKey(null)
    }
  }

  const handleCreate = async () => {
    const name = createForm.name.trim()
    if (!name) {
      setNotice({ tone: 'bad', text: '请填写模板名称' })
      return
    }
    await runAction(
      'create',
      async () => {
        const created = await createTemplate({
          name,
          direction: createForm.direction.trim() || undefined,
          description: createForm.description.trim() || undefined,
        })
        setSelectedId(created.id)
        setCreating(false)
        setCreateForm({ name: '', direction: '', description: '' })
        return created
      },
      `已创建模板「${name}」（默认不激活，只在保存配置后更新快照）`
    )
  }

  const handleSaveMeta = async () => {
    if (!selected) return
    const name = metaForm.name.trim()
    if (!name) {
      setNotice({ tone: 'bad', text: '模板名称不能为空' })
      return
    }
    await runAction(
      'meta',
      () =>
        updateTemplate(selected.id, {
          name,
          direction: metaForm.direction.trim(),
          description: metaForm.description.trim(),
        }),
      '模板基本信息已保存'
    )
  }

  const handleDuplicate = async () => {
    if (!selected) return
    const name = window.prompt('复制为新模板的名称', `${selected.name} 副本`)
    if (!name || !name.trim()) return
    await runAction(
      'duplicate',
      () => duplicateTemplate(selected.id, { name: name.trim() }),
      '已复制模板完整配置快照（默认不激活）'
    )
  }

  const confirmAction = async () => {
    const template = confirm.template
    const kind = confirm.kind
    setConfirm({ kind: null, template: null })
    if (!template || !kind) return
    if (kind === 'activate') {
      await runAction('activate', () => activateTemplate(template.id), `已激活模板「${template.name}」：下一轮任务生效`)
    } else if (kind === 'capture') {
      await runAction('capture', () => captureTemplate(template.id), `已用当前配置覆盖模板「${template.name}」`)
    } else if (kind === 'delete') {
      await runAction('delete', () => deleteTemplate(template.id), `已删除模板「${template.name}」`)
    }
  }

  const handleDrop = async (targetId: number) => {
    const sourceId = dragIdRef.current
    dragIdRef.current = null
    if (!sourceId || sourceId === targetId) return
    const order = templates.map((item) => item.id)
    const from = order.indexOf(sourceId)
    const to = order.indexOf(targetId)
    if (from < 0 || to < 0) return
    order.splice(to, 0, order.splice(from, 1)[0])
    setBusyKey('reorder')
    try {
      await reorderTemplates(order)
      await refreshQuietly()
    } catch (reorderError) {
      setNotice({ tone: 'bad', text: `排序保存失败：${errorText(reorderError)}（已回滚到服务端顺序）` })
      await refreshQuietly()
    } finally {
      setBusyKey(null)
    }
  }

  /** AI 未配置：关闭向导并滚到全局模型服务卡片 */
  const openModelService = () => {
    setAiWizardOpen(false)
    setNotice({
      tone: 'ok',
      text: '请在下方「模型服务（全局）」填写 BASE_URL / API_KEY / MODEL，保存后再回到「AI 帮我建模板」。',
    })
    window.setTimeout(() => {
      document.getElementById('model-service')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }, 80)
  }

  return (
    <div className="animate-v3-in space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs font-medium tracking-[0.1em] text-[var(--v3-muted)]">SETUP · 配置空间</p>
          <h1 className="mt-1 font-serif text-[34px] font-medium leading-none tracking-[-0.03em]">配置</h1>
        </div>
        <span className="text-xs text-[var(--v3-muted)]">
          {'生效模板 > 当前配置；保存后 active 立即应用、非 active 只更新快照'}
        </span>
      </div>

      <p className="text-xs text-[var(--v3-muted)]">
        方向模板 = 搜索条件 + 筛选规则 + AI + 简历；模型服务为全局配置。
      </p>

      <>
          {notice ? (
            <div
              className="rounded-[12px] px-4 py-3 text-sm"
              style={
                notice.tone === 'ok'
                  ? { backgroundColor: 'var(--ok-soft)', color: 'var(--v3-ok)' }
                  : { backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }
              }
            >
              {notice.text}
            </div>
          ) : null}
          {error ? (
            <div className="rounded-[12px] px-4 py-3 text-sm" style={{ backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }}>
              模板加载失败：{error}
              <button type="button" className="ml-2 underline" onClick={() => void loadTemplates()}>
                重试
              </button>
            </div>
          ) : null}

          <div className="grid gap-4 xl:grid-cols-[280px_minmax(0,1fr)]">
            {/* 模板栏 */}
            <aside
              className="oc-card rounded-[20px] border bg-[var(--surface)] p-3 shadow-[var(--shadow)]"
              style={{ borderColor: 'var(--v3-line)' }}
              aria-label="模板栏"
            >
              <div className="mb-2 flex items-center justify-between gap-2">
                <h2 className="text-sm font-semibold">方向模板</h2>
                <div className="flex items-center gap-1">
                  <IconButton label="刷新模板" onClick={() => void loadTemplates()}>
                    <RefreshCw aria-hidden="true" className="size-3.5" />
                  </IconButton>
                  <IconButton label="新建模板" onClick={() => setCreating((value) => !value)}>
                    <Plus aria-hidden="true" className="size-4" />
                  </IconButton>
                </div>
              </div>

              <button
                type="button"
                onClick={() => setAiWizardOpen(true)}
                className="oc-btn-primary oc-sweep mb-3 flex w-full items-center justify-center gap-1.5 rounded-[10px] px-3 py-2 text-sm font-medium transition-opacity hover:opacity-90"
                style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
              >
                <Sparkles aria-hidden="true" className="size-4" />
                AI 帮我建模板
              </button>

              {creating ? (
                <div className="mb-3 space-y-2 rounded-[12px] border p-2" style={{ borderColor: 'var(--v3-line)' }}>
                  <input
                    value={createForm.name}
                    onChange={(event) => setCreateForm({ ...createForm, name: event.target.value })}
                    placeholder="模板名称（必填）"
                    className="h-8 w-full rounded-[8px] border bg-[var(--surface2)] px-2 text-sm outline-none"
                    style={{ borderColor: 'var(--v3-line)' }}
                  />
                  <input
                    value={createForm.direction}
                    onChange={(event) => setCreateForm({ ...createForm, direction: event.target.value })}
                    placeholder="方向（可选）"
                    className="h-8 w-full rounded-[8px] border bg-[var(--surface2)] px-2 text-sm outline-none"
                    style={{ borderColor: 'var(--v3-line)' }}
                  />
                  <input
                    value={createForm.description}
                    onChange={(event) => setCreateForm({ ...createForm, description: event.target.value })}
                    placeholder="描述（可选）"
                    className="h-8 w-full rounded-[8px] border bg-[var(--surface2)] px-2 text-sm outline-none"
                    style={{ borderColor: 'var(--v3-line)' }}
                  />
                  <div className="flex justify-end gap-2">
                    <button type="button" className="text-xs text-[var(--v3-muted)]" onClick={() => setCreating(false)}>
                      取消
                    </button>
                    <button
                      type="button"
                      className="rounded-full px-2.5 py-1 text-xs font-medium"
                      style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
                      disabled={busyKey === 'create'}
                      onClick={() => void handleCreate()}
                    >
                      {busyKey === 'create' ? '创建中…' : '创建'}
                    </button>
                  </div>
                </div>
              ) : null}

              {loading ? (
                <div className="space-y-2">
                  {Array.from({ length: 4 }).map((_, index) => (
                    <div key={index} className="h-14 animate-pulse rounded-[10px] bg-[var(--surface2)]" />
                  ))}
                </div>
              ) : templates.length === 0 ? (
                <p className="px-2 py-8 text-center text-sm text-[var(--v3-muted)]">
                  还没有方向模板；新建后保存配置即可使用。
                </p>
              ) : (
                <ul className="max-h-[calc(100vh-260px)] space-y-1 overflow-y-auto" aria-label="模板列表">
                  {templates.map((template) => {
                    const isSelected = template.id === selectedId
                    const isActive = template.active === true || activeTemplate?.id === template.id
                    return (
                      <li
                        key={template.id}
                        draggable
                        onDragStart={() => {
                          dragIdRef.current = template.id
                        }}
                        onDragOver={(event) => event.preventDefault()}
                        onDrop={() => void handleDrop(template.id)}
                        className="oc-row rounded-[12px] border transition-colors" data-selected={isSelected ? true : undefined}
                        style={{
                          borderColor: isSelected ? 'var(--v3-accent)' : 'var(--v3-line)',
                          backgroundColor: isSelected ? 'var(--accent-soft)' : 'transparent',
                        }}
                      >
                        <button
                          type="button"
                          onClick={() => setSelectedId(template.id)}
                          className="flex w-full items-start gap-2 px-2.5 py-2 text-left"
                        >
                          <GripVertical aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 cursor-grab text-[var(--v3-muted)]" />
                          <span className="min-w-0 flex-1">
                            <span className="flex items-center gap-1.5">
                              <span className="truncate text-sm font-medium">{template.name}</span>
                              {isActive ? (
                                <span
                                  className="oc-chip shrink-0 rounded-full px-1.5 py-0.5 text-[10px]" data-tone="ok" data-nodot
                                  style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
                                >
                                  生效中
                                </span>
                              ) : null}
                            </span>
                            <span className="mt-0.5 block truncate text-[11px] text-[var(--v3-muted)]">
                              {template.direction || '未设方向'} · {formatDateTime(template.updatedAt)}
                            </span>
                          </span>
                        </button>
                      </li>
                    )
                  })}
                </ul>
              )}
            </aside>

            {/* 编辑区 */}
            <div className="min-w-0 space-y-4">
              {selected ? (
                <>
                  <section
                    className="oc-card rounded-[20px] border bg-[var(--surface)] p-4 shadow-[var(--shadow)]"
                    style={{ borderColor: 'var(--v3-line)' }}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <h2 className="text-[15px] font-semibold">{selected.name}</h2>
                        {selected.active ? (
                          <span className="oc-chip rounded-full px-2 py-0.5 text-[11px]" data-tone="primary" data-nodot style={{ backgroundColor: 'var(--accent-soft)', color: 'var(--v3-accent)' }}>
                            当前生效
                          </span>
                        ) : null}
                        {selected.direction ? <span className="text-xs text-[var(--v3-muted)]">方向：{selected.direction}</span> : null}
                      </div>
                      <div className="flex flex-wrap gap-1.5">
                        {!editing ? (
                          <button
                            type="button"
                            disabled={busyKey !== null}
                            onClick={() => setEditing(true)}
                            className="oc-btn-primary inline-flex h-11 items-center gap-2 rounded-full px-5 text-sm font-semibold shadow-md disabled:cursor-not-allowed disabled:opacity-45"
                            style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
                          >
                            <Pencil aria-hidden="true" className="size-4" />
                            编辑配置
                          </button>
                        ) : null}
                        <TinyButton tone="accent" disabled={selected.active || busyKey !== null} onClick={() => setConfirm({ kind: 'activate', template: selected })}>
                          激活
                        </TinyButton>
                        <TinyButton disabled={busyKey !== null} onClick={() => void handleDuplicate()}>
                          复制为新模板
                        </TinyButton>
                        <TinyButton disabled={busyKey !== null} onClick={() => setConfirm({ kind: 'capture', template: selected })}>
                          用当前配置覆盖
                        </TinyButton>
                        <TinyButton
                          tone="danger"
                          disabled={selected.active || busyKey !== null}
                          onClick={() => setConfirm({ kind: 'delete', template: selected })}
                        >
                          删除
                        </TinyButton>
                      </div>
                    </div>
                    {editing ? (
                      <>
                        <div className="mt-3 grid gap-2 sm:grid-cols-[1fr_160px_1fr_auto]">
                          <input
                            value={metaForm.name}
                            onChange={(event) => setMetaForm({ ...metaForm, name: event.target.value })}
                            placeholder="模板名称"
                            aria-label="模板名称"
                            className="h-9 rounded-[10px] border bg-[var(--surface2)] px-2.5 text-sm outline-none"
                            style={{ borderColor: 'var(--v3-line)' }}
                          />
                          <input
                            value={metaForm.direction}
                            onChange={(event) => setMetaForm({ ...metaForm, direction: event.target.value })}
                            placeholder="方向"
                            aria-label="方向"
                            className="h-9 rounded-[10px] border bg-[var(--surface2)] px-2.5 text-sm outline-none"
                            style={{ borderColor: 'var(--v3-line)' }}
                          />
                          <input
                            value={metaForm.description}
                            onChange={(event) => setMetaForm({ ...metaForm, description: event.target.value })}
                            placeholder="描述"
                            aria-label="描述"
                            className="h-9 rounded-[10px] border bg-[var(--surface2)] px-2.5 text-sm outline-none"
                            style={{ borderColor: 'var(--v3-line)' }}
                          />
                          <TinyButton disabled={busyKey === 'meta'} onClick={() => void handleSaveMeta()}>
                            保存基本信息
                          </TinyButton>
                        </div>
                        <TemplateConfigEditor
                          key={selected.id}
                          template={selected}
                          embedded
                          onClose={() => {}}
                          onSaved={() => {
                            setEditing(false)
                            setNotice({
                              tone: 'ok',
                              text: selected.active
                                ? '模板配置已保存，并已应用到运行配置（下一轮生效）。'
                                : '模板配置已保存为快照；激活后下一轮生效。',
                            })
                            void refreshQuietly()
                          }}
                        />
                      </>
                    ) : (
                      <div className="mt-3 space-y-3">
                        <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
                          <div className="flex min-w-0 items-baseline justify-between gap-3 border-b py-1.5" style={{ borderColor: 'var(--v3-line)' }}>
                            <dt className="shrink-0 text-xs text-[var(--v3-muted)]">方向</dt>
                            <dd className="truncate text-right">{selected.direction || '未设置'}</dd>
                          </div>
                          <div className="flex min-w-0 items-baseline justify-between gap-3 border-b py-1.5" style={{ borderColor: 'var(--v3-line)' }}>
                            <dt className="shrink-0 text-xs text-[var(--v3-muted)]">关键词</dt>
                            <dd className="truncate text-right" title={selected.keywords ?? ''}>{selected.keywords || '未设置'}</dd>
                          </div>
                          <div className="flex min-w-0 items-baseline justify-between gap-3 border-b py-1.5" style={{ borderColor: 'var(--v3-line)' }}>
                            <dt className="shrink-0 text-xs text-[var(--v3-muted)]">期望月薪下限</dt>
                            <dd className="text-right">
                              {selected.expectedSalaryMin != null && selected.expectedSalaryMin > 0
                                ? (selected.expectedSalaryMax != null && selected.expectedSalaryMax > 0
                                    ? `${selected.expectedSalaryMin}K–${selected.expectedSalaryMax}K`
                                    : `${selected.expectedSalaryMin}K 起`)
                                : '未设置'}
                            </dd>
                          </div>
                          <div className="flex min-w-0 items-baseline justify-between gap-3 border-b py-1.5" style={{ borderColor: 'var(--v3-line)' }}>
                            <dt className="shrink-0 text-xs text-[var(--v3-muted)]">图片简历</dt>
                            <dd className="text-right">{selected.hasResume ? '已上传' : '使用当前简历'}</dd>
                          </div>
                          <div className="flex min-w-0 items-baseline justify-between gap-3 border-b py-1.5" style={{ borderColor: 'var(--v3-line)' }}>
                            <dt className="shrink-0 text-xs text-[var(--v3-muted)]">最近更新</dt>
                            <dd className="text-right">{formatDateTime(selected.updatedAt)}</dd>
                          </div>
                          <div className="flex min-w-0 items-baseline justify-between gap-3 border-b py-1.5" style={{ borderColor: 'var(--v3-line)' }}>
                            <dt className="shrink-0 text-xs text-[var(--v3-muted)]">描述</dt>
                            <dd className="truncate text-right" title={selected.description ?? ''}>{selected.description || '未填写'}</dd>
                          </div>
                        </dl>

                      </div>
                    )}
                  </section>
                </>
              ) : (
                <section
                  className="oc-card rounded-[20px] border bg-[var(--surface)] p-8 text-center text-sm text-[var(--v3-muted)] shadow-[var(--shadow)]"
                  style={{ borderColor: 'var(--v3-line)' }}
                >
                  选择一个模板开始编辑，或点击「+」新建模板。
                </section>
              )}
            </div>
          </div>

          <div
            id="model-service"
            className="oc-card scroll-mt-24 rounded-[20px] border border-dashed bg-[var(--surface)] p-1 shadow-[var(--shadow)]"
            style={{ borderColor: 'var(--line-strong)' }}
          >
            <ModelServiceCard />
          </div>
        </>

      <ConfirmDialog
        open={confirm.kind !== null}
        tone={confirm.kind === 'delete' ? 'danger' : 'warning'}
        title={
          confirm.kind === 'activate'
            ? '激活该模板？'
            : confirm.kind === 'capture'
              ? '用当前配置覆盖该模板？'
              : '删除该模板？'
        }
        description={
          confirm.kind === 'activate'
            ? '激活会把模板快照应用到当前配置，下一轮任务生效；不会立即发送。'
            : confirm.kind === 'capture'
              ? '会用当前配置写回该模板快照；不会改变当前配置。'
              : '删除后不可恢复；生效模板不可删除（请先激活其他模板）。'
        }
        confirmText={confirm.kind === 'delete' ? '删除模板' : confirm.kind === 'activate' ? '激活模板' : '覆盖模板'}
        busy={busyKey !== null}
        onCancel={() => setConfirm({ kind: null, template: null })}
        onConfirm={() => void confirmAction()}
      />

      {aiWizardOpen ? (
        <AiTemplateWizard
          onClose={() => setAiWizardOpen(false)}
          onOpenModelService={openModelService}
          onSaved={(template, activated) => {
            setSelectedId(template.id)
            setEditing(false)
            setAiWizardOpen(false)
            setNotice({
              tone: 'ok',
              text: activated
                ? `已保存并激活模板「${template.name}」：下一轮任务生效，不会立即开始投递。`
                : `已保存为草稿模板「${template.name}」（未激活）：可在模板列表中继续编辑或手动激活。`,
            })
            void loadTemplates(true)
          }}
        />
      ) : null}
    </div>
  )
}

function IconButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className="inline-flex size-8 items-center justify-center rounded-full border text-[var(--v3-muted)] hover:text-[var(--text)]"
      style={{ borderColor: 'var(--v3-line)' }}
    >
      {children}
    </button>
  )
}

function TinyButton({
  children,
  onClick,
  disabled,
  tone = 'quiet',
}: {
  children: React.ReactNode
  onClick: () => void
  disabled?: boolean
  tone?: 'quiet' | 'accent' | 'danger'
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`${tone === 'accent' ? 'oc-btn-primary' : ''} inline-flex h-8 items-center rounded-full px-2.5 text-xs font-medium disabled:cursor-not-allowed disabled:opacity-45`}
      style={
        tone === 'accent'
          ? { backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }
          : tone === 'danger'
            ? { border: '1px solid var(--v3-bad)', color: 'var(--v3-bad)' }
            : { border: '1px solid var(--v3-line)', color: 'var(--text)' }
      }
    >
      {children}
    </button>
  )
}
