'use client'

import { useCallback, useEffect, useState } from 'react'
import {
  BiRefresh,
  BiSave,
  BiHistory,
  BiPlay,
  BiCheckCircle,
  BiErrorCircle,
  BiInfoCircle,
  BiBookOpen,
} from 'react-icons/bi'
import PageHeader from '@/app/components/PageHeader'
import TemplateContextBanner from '@/app/components/TemplateContextBanner'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { ApiError, getRules, getRuleVersions, previewRules, saveRules } from '@/lib/api'
import type { PreviewResult, PreviewSample, RulePayload, RuleSetView } from '@/lib/boss-types'
import {
  Pill,
  decisionBadgeClass,
  formatDateTime,
  ruleDecisionLabel,
  ruleReasonLabel,
  shortId,
} from '@/app/components/boss-shared'

/**
 * B2：规则配置页 /boss/rules（契约第 6 节）。
 *
 * - 顶部展示当前生效规则版本 / 来源哈希 / 创建时间 / 说明；
 * - 表单覆盖 RulePayload 全部字段，保存时携带 expectedVersion（409 提示并自动重拉）；
 * - 内置样例预览调用 /api/boss/rules/preview（零发送、不写评估表），显示 decision/reason/evidence；
 * - 版本历史来自 /api/boss/rules/versions，可载入某版本到表单后再保存为新版本。
 */

// ==================== 选项与文案 ====================

// 与后端 RuleSet.highestDegree 的合法取值一致（默认「本科」）；"不限" 是岗位学历文案，不是用户最高学历
const DEGREE_OPTIONS = ['高中', '中专', '大专', '本科', '硕士', '博士']

const SALARY_STRATEGY_HINT = {
  INTERSECT: '岗位薪资区间与期望下限有交集才通过；规则固定使用该策略。',
}

const MATCH_MODE_OPTIONS = [
  { value: 'CONTAINS', label: 'CONTAINS · 包含匹配' },
  { value: 'EXACT', label: 'EXACT · 精确匹配' },
]

const MATCH_MODE_HINT: Record<string, string> = {
  CONTAINS: '岗位名/公司名/招聘者名称包含黑名单词即命中（默认）。',
  EXACT: '去掉首尾空白后完全相等才命中，避免误伤相似名称。',
}

// ==================== 表单模型与转换 ====================

interface RuleFormState {
  highestDegree: string
  expectedSalaryMin: string
  blacklistCities: string
  blacklistMatchMode: string
  blacklistJob: string
  blacklistCompany: string
  blacklistRecruiter: string
  whitelist: string
  filterDeadHr: boolean
  deadStatus: string
}

/** 逗号（中英文）、顿号、换行分隔 → 去空白非空列表 */
function splitList(value: string): string[] {
  return value
    .split(/[,，、\n\r]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function joinList(values?: string[] | null): string {
  return (values ?? []).join('\n')
}

function toIntOrNull(value: string): number | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? Math.trunc(parsed) : null
}

function formFromPayload(payload?: RulePayload | null): RuleFormState {
  return {
    highestDegree: payload?.highestDegree || '本科',
    expectedSalaryMin: payload?.expectedSalaryMin !== undefined && payload?.expectedSalaryMin !== null
      ? String(payload.expectedSalaryMin)
      : '',
    blacklistCities: joinList(payload?.blacklistCities),
    blacklistMatchMode: payload?.blacklistMatchMode || 'CONTAINS',
    blacklistJob: joinList(payload?.blacklistJob),
    blacklistCompany: joinList(payload?.blacklistCompany),
    blacklistRecruiter: joinList(payload?.blacklistRecruiter),
    whitelist: joinList(payload?.whitelist),
    filterDeadHr: payload?.filterDeadHr === true,
    deadStatus: joinList(payload?.deadStatus),
  }
}

function payloadFromForm(form: RuleFormState): RulePayload {
  return {
    highestDegree: form.highestDegree || undefined,
    // 规则固定「区间有交集」策略；方向/类型/可接受城市已废弃，统一清空避免旧过滤残留
    salaryStrategy: 'INTERSECT',
    expectedSalaryMin: toIntOrNull(form.expectedSalaryMin),
    // 只设下限；0 表示未设置上限。
    expectedSalaryMax: 0,
    jobDirections: [],
    cities: [],
    blacklistCities: splitList(form.blacklistCities),
    workTypes: [],
    blacklistMatchMode: form.blacklistMatchMode || undefined,
    blacklistJob: splitList(form.blacklistJob),
    blacklistCompany: splitList(form.blacklistCompany),
    blacklistRecruiter: splitList(form.blacklistRecruiter),
    whitelist: splitList(form.whitelist),
    filterDeadHr: form.filterDeadHr,
    deadStatus: splitList(form.deadStatus),
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/** 当前规则摘要（用于顶部卡片展示；不再展示已废弃的策略/方向/类型/可接受城市） */
function summarizeRules(payload?: RulePayload | null): string[] {
  if (!payload) return []
  const matchMode = payload.blacklistMatchMode === 'EXACT' ? 'EXACT（精确）' : 'CONTAINS（包含）'
  return [
    `最高学历：${payload.highestDegree || '未设置'}`,
    `期望月薪下限：${payload.expectedSalaryMin ?? '-'} K/月起`,
    `不可接受城市：${payload.blacklistCities?.length ? payload.blacklistCities.join('、') : '不限'}`,
    `黑名单匹配：${matchMode}；岗位 ${payload.blacklistJob?.length ?? 0} / 公司 ${payload.blacklistCompany?.length ?? 0} / HR ${payload.blacklistRecruiter?.length ?? 0}`,
    `白名单：${payload.whitelist?.length ? payload.whitelist.join('、') : '无'}`,
    `HR 活跃过滤：${payload.filterDeadHr ? '开启' : '关闭'}${
      payload.deadStatus?.length ? `（不活跃状态：${payload.deadStatus.join('、')}）` : ''
    }`,
  ]
}

// ==================== 内置样例 ====================

const SAMPLE_BASE: PreviewSample = {
  jobName: 'Java 后端开发工程师',
  companyName: '示例科技有限公司',
  salary: '20-30K',
  degree: '本科',
  experience: '3-5年',
  city: '北京',
  jd: '负责 Java 后端服务开发，熟悉 Spring Boot、MySQL；本科及以上学历，3 年以上经验。',
  hrName: '张女士',
  hrActiveStatus: '今日活跃',
}

const BUILTIN_SAMPLES: { key: string; label: string; hint?: string; sample: PreviewSample }[] = [
  {
    key: 'empty-degree',
    label: '空学历',
    hint: '岗位学历要求为空',
    sample: { ...SAMPLE_BASE, jobName: '前端开发工程师（空学历样例）', degree: '' },
  },
  {
    key: 'negotiable',
    label: '面议',
    hint: '薪资面议 → 无法解析',
    sample: { ...SAMPLE_BASE, jobName: '运营专员（面议样例）', salary: '面议' },
  },
  {
    key: 'decimal',
    label: '12.5K 小数',
    hint: '带小数的薪资解析',
    sample: { ...SAMPLE_BASE, jobName: '数据分析师（12.5K 样例）', salary: '12.5K-18K' },
  },
  {
    key: 'salary-low',
    label: '薪资超限',
    hint: '薪资低于期望下限时被拒（取决于表单期望值）',
    sample: { ...SAMPLE_BASE, jobName: '初级开发工程师（低薪样例）', salary: '6-8K' },
  },
  {
    key: 'whitelist',
    label: '白名单命中',
    hint: '白名单包含“白名单”一词时才会命中',
    sample: { ...SAMPLE_BASE, jobName: 'Java 开发工程师（白名单样例）', companyName: '白名单示例公司' },
  },
  {
    key: 'sales',
    label: '销售岗位',
    hint: '可在「岗位黑名单」加入“销售”验证拒绝',
    sample: {
      ...SAMPLE_BASE,
      jobName: '销售代表（黑名单样例）',
      jd: '负责客户开拓与销售目标达成，大专及以上学历，沟通能力强。',
    },
  },
]

// ==================== 页面 ====================

type Tone = 'success' | 'error'

interface Message {
  tone: Tone
  text: string
}

export default function BossRulesPage() {
  const [active, setActive] = useState<RuleSetView | null>(null)
  const [liveDefaults, setLiveDefaults] = useState<RulePayload | null>(null)
  const [versions, setVersions] = useState<RuleSetView[]>([])
  const [form, setForm] = useState<RuleFormState>(() => formFromPayload(null))
  const [note, setNote] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveMessage, setSaveMessage] = useState<Message | null>(null)
  const [loadFormMessage, setLoadFormMessage] = useState<string | null>(null)

  const [previews, setPreviews] = useState<PreviewResult[] | null>(null)
  const [previewSamples, setPreviewSamples] = useState<PreviewSample[]>([])
  const [previewLabel, setPreviewLabel] = useState('')
  const [previewing, setPreviewing] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [customSample, setCustomSample] = useState<PreviewSample>(SAMPLE_BASE)

  const refreshVersions = useCallback(async () => {
    try {
      const list = await getRuleVersions(20)
      setVersions(Array.isArray(list) ? list : [])
    } catch {
      // 版本历史刷新失败不覆盖当前页面的保存结果
    }
  }, [])

  const refreshAll = useCallback(async (resetForm: boolean) => {
    setLoading(true)
    setError(null)
    try {
      const [overview, versionList] = await Promise.all([
        getRules(),
        getRuleVersions(20).catch(() => null),
      ])
      setActive(overview?.active ?? null)
      setLiveDefaults(overview?.liveDefaults ?? null)
      const list = versionList && versionList.length > 0 ? versionList : overview?.versions ?? []
      setVersions(Array.isArray(list) ? list : [])
      if (resetForm) {
        setForm(formFromPayload(overview?.active?.rules ?? overview?.liveDefaults ?? null))
        setNote('')
        setLoadFormMessage(null)
      }
    } catch (loadError) {
      setError(errorMessage(loadError))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refreshAll(true)
  }, [refreshAll])

  function updateForm<K extends keyof RuleFormState>(key: K, value: RuleFormState[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  function updateCustomSample<K extends keyof PreviewSample>(key: K, value: PreviewSample[K]) {
    setCustomSample((prev) => ({ ...prev, [key]: value }))
  }

  const handleSave = async () => {
    if (saving) return
    const min = toIntOrNull(form.expectedSalaryMin)
    if (min !== null && (min < 0 || min > 1000)) {
      setSaveMessage({ tone: 'error', text: '期望月薪下限需在 0 ~ 1000 K/月之间。' })
      return
    }
    setSaving(true)
    setSaveMessage(null)
    try {
      const expectedVersion = active?.version ?? 0
      const saved = await saveRules(payloadFromForm(form), expectedVersion, note)
      setActive(saved)
      setForm(formFromPayload(saved.rules))
      setNote('')
      setSaveMessage({
        tone: 'success',
        text: `已保存为新版本 v${saved.version}（哈希 ${(saved.sourceHash ?? '').slice(0, 12) || '-'}…）。规则将在下一轮任务开始时生效。`,
      })
      await refreshVersions()
    } catch (saveError) {
      if (saveError instanceof ApiError && saveError.status === 409) {
        setSaveMessage({
          tone: 'error',
          text: '规则已被其他页面修改，请刷新后重试。已自动重新加载最新版本，可直接再次点击保存。',
        })
        await refreshAll(false)
      } else if (saveError instanceof ApiError && saveError.status === 400) {
        setSaveMessage({ tone: 'error', text: `规则校验失败：${saveError.message}` })
      } else {
        setSaveMessage({ tone: 'error', text: `保存失败：${errorMessage(saveError)}` })
      }
    } finally {
      setSaving(false)
    }
  }

  const runPreview = async (samples: PreviewSample[], label: string) => {
    if (samples.length === 0) return
    setPreviewing(true)
    setPreviewError(null)
    setPreviewLabel(label)
    setPreviewSamples(samples)
    try {
      const results = await previewRules(samples, payloadFromForm(form))
      setPreviews(Array.isArray(results) ? results : [])
    } catch (previewLoadError) {
      setPreviews(null)
      setPreviewError(errorMessage(previewLoadError))
    } finally {
      setPreviewing(false)
    }
  }

  const loadVersionIntoForm = (view: RuleSetView) => {
    setForm(formFromPayload(view.rules))
    setNote('')
    setLoadFormMessage(`已载入版本 v${view.version} 到表单，保存后会生成新版本（当前生效仍是 v${active?.version ?? '-'}）。`)
    if (typeof window !== 'undefined') {
      window.scrollTo({ top: 0, behavior: 'smooth' })
    }
  }

  const loadLiveDefaultsIntoForm = () => {
    if (!liveDefaults) return
    setForm(formFromPayload(liveDefaults))
    setNote('')
    setLoadFormMessage('已载入「实时配置默认值」到表单（来自 Boss 配置 / 黑名单表），保存后会生成新版本。')
  }

  const resetToActive = () => {
    setForm(formFromPayload(active?.rules ?? null))
    setNote('')
    setLoadFormMessage(null)
    setSaveMessage(null)
  }

  const summary = summarizeRules(active?.rules)

  return (
    <div className="space-y-6">
      <PageHeader
        title="规则配置"
        meta="可配置硬规则、内置样例预览与版本历史 · 保存后对下一轮任务生效"
        actions={
          <div className="flex items-center gap-2">
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={loading || saving}
              onClick={() => {
                void refreshAll(true)
              }}
            >
              <BiRefresh className="mr-1" />
              刷新
            </Button>
            <Button
              type="button"
              size="sm"
              disabled={loading || saving}
              onClick={() => void handleSave()}
            >
              <BiSave className="mr-1" />
              {saving ? '保存中…' : '保存为新版本'}
            </Button>
          </div>
        }
      />

      {/* 配置中心上下文：规则保存会自动写回当前生效模板 */}
      <TemplateContextBanner scope="筛选规则" />

      {error ? (
        <Card>
          <CardContent className="pt-6">
            <p className="rounded-lg border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-700 dark:text-red-300">
              规则数据加载失败：{error}（请确认后端已启动并完成 V5 迁移）
            </p>
          </CardContent>
        </Card>
      ) : null}

      {saveMessage ? (
        <p
          className={`rounded-lg border px-3 py-2 text-sm ${
            saveMessage.tone === 'success'
              ? 'border-success-border bg-success-bg text-success'
              : 'border-danger-border bg-danger-bg text-danger'
          }`}
        >
          {saveMessage.text}
        </p>
      ) : null}

      {loadFormMessage ? (
        <p className="rounded-control border border-info-border border-l-2 border-l-info bg-info-bg px-3 py-2 text-sm text-info">
          {loadFormMessage}
        </p>
      ) : null}

      {/* 当前生效规则 */}
      <Card className="animate-in fade-in slide-in-from-bottom-3 duration-500">
        <CardHeader className="pb-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <CardTitle className="flex items-center gap-2">
                <BiCheckCircle className="text-primary" />
                当前生效规则
              </CardTitle>
              <CardDescription>页面上所有保存都会生成新版本，旧版本保留在版本历史中</CardDescription>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button type="button" size="sm" variant="outline" className="rounded-full" onClick={resetToActive} disabled={loading}>
                载入当前生效版本
              </Button>
              <Button
                type="button"
                size="sm"
                variant="outline"
                className="rounded-full"
                onClick={loadLiveDefaultsIntoForm}
                disabled={!liveDefaults}
              >
                载入实时配置默认值
              </Button>
            </div>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          {loading ? (
            <p className="text-sm text-muted-foreground">加载中…</p>
          ) : active ? (
            <>
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Pill className="border-success-border bg-success-bg text-success">
                  当前生效 v{active.version}
                </Pill>
                <span className="rounded-full border px-2.5 py-1 text-xs text-muted-foreground">
                  来源哈希：
                  <code title={active.sourceHash ?? undefined}>{shortId(active.sourceHash, 12, 6)}</code>
                </span>
                <span className="rounded-full border px-2.5 py-1 text-xs text-muted-foreground">
                  创建时间：{formatDateTime(active.createdAt)}
                </span>
                <span className="rounded-full border px-2.5 py-1 text-xs text-muted-foreground">
                  说明：{active.note || '（无）'}
                </span>
              </div>
              <div className="grid gap-1.5 rounded-control bg-surface-inset px-3 py-2 text-sm">
                {summary.map((line) => (
                  <div key={line} className="text-muted-foreground">
                    {line}
                  </div>
                ))}
              </div>
            </>
          ) : (
            <p className="text-sm text-muted-foreground">
              尚无持久化规则版本；保存表单后会创建 v1，并作为后续任务的规则快照。
            </p>
          )}
        </CardContent>
      </Card>

      <Card className="border-amber-300/50 bg-amber-50/70 dark:border-amber-700/40 dark:bg-amber-950/20">
        <CardContent className="space-y-1.5 pt-4 text-sm text-muted-foreground">
          <p>
            <strong className="text-foreground">本页 = 投递前本地准入规则</strong>
            ；BOSS 站内搜索条件（关键词、工作城市、薪资待遇、学历要求、经验、行业、规模、融资、职位类型）
            仍在
            <a href="/boss" className="mx-1 text-primary underline underline-offset-2">BOSS 配置</a>
            页，两处含义不同。
          </p>
          <p>
            黑名单 / 白名单 / HR 活跃过滤以本页保存的规则版本为准；保存后只影响下一轮任务，
            运行中的任务继续使用旧快照。
          </p>
        </CardContent>
      </Card>

      {/* 规则表单 */}
      <Card className="animate-in fade-in slide-in-from-bottom-3 duration-500">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2">
            <BiBookOpen className="text-primary" />
            规则表单
          </CardTitle>
          <CardDescription>
            保存时携带当前生效版本号做并发校验；预览始终使用表单当前内容，未保存也可预览
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="highestDegree">最高学历</Label>
              <Select
                id="highestDegree"
                value={form.highestDegree}
                onChange={(event) => updateForm('highestDegree', event.target.value)}
              >
                {DEGREE_OPTIONS.map((degree) => (
                  <option key={degree} value={degree}>
                    {degree}
                  </option>
                ))}
              </Select>
              <p className="text-xs text-muted-foreground">
                岗位要求等级高于你的最高学历时拒绝；要求文案含「优先」时不拒绝；学历表述与 JD 冲突时转复核。
                这里是你的个人最高学历，不是 BOSS 搜索条件里的「学历要求」。
              </p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="expectedSalaryMin">期望月薪下限（K/月）</Label>
              <Input
                id="expectedSalaryMin"
                type="number"
                min={0}
                max={1000}
                value={form.expectedSalaryMin}
                placeholder="例如 15"
                onChange={(event) => updateForm('expectedSalaryMin', event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <p className="text-xs text-muted-foreground">
                只设下限，不设上限；{SALARY_STRATEGY_HINT.INTERSECT} BOSS 搜索条件里的「薪资待遇」只决定站内搜索范围，不是同一件事。
              </p>
            </div>
            <div className="space-y-1.5 md:col-span-2">
              <Label htmlFor="blacklistCities">不可接受城市（逗号/换行分隔，留空=不限）</Label>
              <Input
                id="blacklistCities"
                value={form.blacklistCities}
                placeholder="例如 北京,上海外派"
                onChange={(event) => updateForm('blacklistCities', event.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                岗位城市命中任一不可接受城市会被拒绝；BOSS 搜索城市在搜索配置里设置，本项只做本地准入过滤。
              </p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="blacklistMatchMode">黑名单匹配模式</Label>
              <Select
                id="blacklistMatchMode"
                value={form.blacklistMatchMode}
                onChange={(event) => updateForm('blacklistMatchMode', event.target.value)}
              >
                {MATCH_MODE_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </Select>
              <p className="text-xs text-muted-foreground">
                {MATCH_MODE_HINT[form.blacklistMatchMode] ?? MATCH_MODE_HINT.CONTAINS}
              </p>
            </div>
          </div>

          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
            <div className="space-y-1.5">
              <Label htmlFor="blacklistJob">岗位黑名单（每行一个）</Label>
              <textarea
                id="blacklistJob"
                rows={5}
                value={form.blacklistJob}
                placeholder={'例如：\n外包\n实习'}
                onChange={(event) => updateForm('blacklistJob', event.target.value)}
                className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-6 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="blacklistCompany">公司黑名单（每行一个）</Label>
              <textarea
                id="blacklistCompany"
                rows={5}
                value={form.blacklistCompany}
                placeholder={'例如：\n某某外包公司'}
                onChange={(event) => updateForm('blacklistCompany', event.target.value)}
                className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-6 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="blacklistRecruiter">HR 黑名单（每行一个）</Label>
              <textarea
                id="blacklistRecruiter"
                rows={5}
                value={form.blacklistRecruiter}
                placeholder={'例如：\n某招聘专员'}
                onChange={(event) => updateForm('blacklistRecruiter', event.target.value)}
                className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-6 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="whitelist">白名单（每行一个，优先通过）</Label>
              <textarea
                id="whitelist"
                rows={5}
                value={form.whitelist}
                placeholder={'例如：\n心仪公司'}
                onChange={(event) => updateForm('whitelist', event.target.value)}
                className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-6 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </div>
          </div>

          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div className="flex flex-col gap-3 rounded-lg border bg-muted/20 p-4 sm:flex-row sm:items-center sm:justify-between">
              <div className="space-y-1">
                <Label htmlFor="filterDeadHr" className="text-base font-medium">
                  HR 活跃过滤
                </Label>
                <p className="text-xs text-muted-foreground">
                  开启后，HR 活跃状态命中下方列表时拒绝（沿用 Boss 配置页 deadStatus 语义）。
                </p>
              </div>
              <button
                id="filterDeadHr"
                type="button"
                role="switch"
                aria-checked={form.filterDeadHr}
                aria-label="HR 活跃过滤"
                onClick={() => updateForm('filterDeadHr', !form.filterDeadHr)}
                className={`relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 ${
                  form.filterDeadHr ? 'bg-primary' : 'bg-muted-foreground/40'
                }`}
              >
                <span
                  aria-hidden="true"
                  className={`pointer-events-none block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform ${
                    form.filterDeadHr ? 'translate-x-5' : 'translate-x-0.5'
                  }`}
                />
              </button>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="deadStatus">不活跃状态（每行一个）</Label>
              <textarea
                id="deadStatus"
                rows={4}
                value={form.deadStatus}
                placeholder={'例如：\n半年前活跃\n3月内活跃'}
                onChange={(event) => updateForm('deadStatus', event.target.value)}
                className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-6 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="note">本次修改说明（可选，保存到版本历史）</Label>
            <Input id="note" value={note} placeholder="例如：收紧薪资下限，补充城市白名单" onChange={(event) => setNote(event.target.value)} />
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              size="sm"
              className="rounded-control"
              variant="primary"
              disabled={saving || loading}
              onClick={() => void handleSave()}
            >
              <BiSave className="mr-1" />
              {saving ? '保存中…' : `保存为新版本（基于 v${active?.version ?? '-'}）`}
            </Button>
            <Button type="button" size="sm" variant="outline" className="rounded-full" onClick={resetToActive} disabled={saving}>
              重置为当前生效版本
            </Button>
            <span className="text-xs text-muted-foreground">
              保存后版本号 +1；运行中的任务不受影响，下一轮任务使用新规则。
            </span>
          </div>
        </CardContent>
      </Card>

      {/* 内置样例预览 */}
      <Card className="animate-in fade-in slide-in-from-bottom-3 duration-500">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2">
            <BiPlay className="text-primary" />
            内置样例预览
          </CardTitle>
          <CardDescription>
            预览使用表单当前规则，调用 /api/boss/rules/preview —— 零发送、不写评估表、不改投递状态
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap gap-2">
            {BUILTIN_SAMPLES.map((item) => (
              <Button
                key={item.key}
                type="button"
                size="sm"
                variant="outline"
                className="rounded-full"
                disabled={previewing}
                title={item.hint ?? item.label}
                onClick={() => void runPreview([item.sample], item.label)}
              >
                {item.label}
              </Button>
            ))}
            <Button
              type="button"
              size="sm"
              variant="secondary"
              className="rounded-full"
              disabled={previewing}
              onClick={() => void runPreview(BUILTIN_SAMPLES.map((item) => item.sample), '全部内置样例')}
            >
              <BiPlay className="mr-1" />
              全部内置样例
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">
            「白名单命中」需白名单包含「白名单」一词；「销售岗位」在岗位黑名单包含「销售」时会触发拒绝；其余样例显示当前规则下的判定。
          </p>

          {previewError ? (
            <p className="rounded-lg border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-700 dark:text-red-300">
              预览失败：{previewError}
            </p>
          ) : null}
          {previewing ? <p className="text-sm text-muted-foreground">预览中…</p> : null}

          {previews && previews.length > 0 && !previewing ? (
            <div className="overflow-x-auto">
              <p className="mb-2 text-xs text-muted-foreground">预览来源：{previewLabel}</p>
              <table className="w-full min-w-[900px] text-sm">
                <thead>
                  <tr className="border-b bg-muted/30 text-left text-xs text-muted-foreground">
                    <th className="px-3 py-2 font-medium">#</th>
                    <th className="px-3 py-2 font-medium">样例岗位</th>
                    <th className="px-3 py-2 font-medium">决策</th>
                    <th className="px-3 py-2 font-medium">原因码</th>
                    <th className="px-3 py-2 font-medium">证据</th>
                    <th className="px-3 py-2 font-medium">详情</th>
                  </tr>
                </thead>
                <tbody>
                  {previews.map((result, index) => {
                    const sample = previewSamples[result.index ?? index]
                    return (
                      <tr key={`${result.index ?? index}-${result.reason ?? 'result'}`} className="border-b last:border-0">
                        <td className="px-3 py-2 text-muted-foreground">{(result.index ?? index) + 1}</td>
                        <td className="max-w-[200px] px-3 py-2">
                          <span className="line-clamp-2 break-all">
                            {result.jobName || sample?.jobName || '-'}
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          <Pill className={decisionBadgeClass(result.decision)}>{ruleDecisionLabel(result.decision)}</Pill>
                        </td>
                        <td className="px-3 py-2">
                          <span title={result.reason ?? undefined}>{ruleReasonLabel(result.reason)}</span>
                          {result.reason ? (
                            <span className="ml-1 block font-mono text-xs text-muted-foreground">{result.reason}</span>
                          ) : null}
                        </td>
                        <td className="max-w-[220px] px-3 py-2 text-xs text-muted-foreground">
                          <span className="line-clamp-3 break-all" title={result.evidence ?? undefined}>
                            {result.evidence || '-'}
                          </span>
                        </td>
                        <td className="max-w-[260px] px-3 py-2 text-xs">
                          <span className="line-clamp-3 break-all" title={result.detail ?? undefined}>
                            {result.detail || '-'}
                          </span>
                          {result.salaryNormalized ? (
                            <span className="mt-0.5 block text-muted-foreground">薪资解析：{result.salaryNormalized}</span>
                          ) : null}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          ) : null}
        </CardContent>
      </Card>

      {/* 自定义样例预览 */}
      <Card className="animate-in fade-in slide-in-from-bottom-3 duration-500">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2">
            <BiInfoCircle className="text-primary" />
            自定义样例预览
          </CardTitle>
          <CardDescription>可粘贴 JD 与岗位字段，用表单当前规则预览判定结果（不发送消息）</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
            <div className="space-y-1.5">
              <Label htmlFor="sample-jobName">岗位名称</Label>
              <Input
                id="sample-jobName"
                value={customSample.jobName ?? ''}
                onChange={(event) => updateCustomSample('jobName', event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="sample-companyName">公司名称</Label>
              <Input
                id="sample-companyName"
                value={customSample.companyName ?? ''}
                onChange={(event) => updateCustomSample('companyName', event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="sample-salary">薪资</Label>
              <Input
                id="sample-salary"
                value={customSample.salary ?? ''}
                placeholder="例如 20-30K / 面议 / 12.5K-18K"
                onChange={(event) => updateCustomSample('salary', event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="sample-degree">学历要求</Label>
              <Input
                id="sample-degree"
                value={customSample.degree ?? ''}
                placeholder="例如 本科"
                onChange={(event) => updateCustomSample('degree', event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="sample-experience">经验要求</Label>
              <Input
                id="sample-experience"
                value={customSample.experience ?? ''}
                placeholder="例如 3-5年"
                onChange={(event) => updateCustomSample('experience', event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="sample-city">城市</Label>
              <Input
                id="sample-city"
                value={customSample.city ?? ''}
                placeholder="例如 北京"
                onChange={(event) => updateCustomSample('city', event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="sample-hrName">HR 名称</Label>
              <Input
                id="sample-hrName"
                value={customSample.hrName ?? ''}
                onChange={(event) => updateCustomSample('hrName', event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="sample-hrActiveStatus">HR 活跃状态</Label>
              <Input
                id="sample-hrActiveStatus"
                value={customSample.hrActiveStatus ?? ''}
                placeholder="例如 今日活跃 / 半年前活跃"
                onChange={(event) => updateCustomSample('hrActiveStatus', event.target.value)}
              />
            </div>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="sample-jd">岗位描述 JD（可粘贴）</Label>
            <textarea
              id="sample-jd"
              rows={4}
              value={customSample.jd ?? ''}
              onChange={(event) => updateCustomSample('jd', event.target.value)}
              className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-6 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
          </div>
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="rounded-full"
            disabled={previewing}
            onClick={() => void runPreview([customSample], '自定义样例')}
          >
            <BiPlay className="mr-1" />
            预览自定义样例
          </Button>
        </CardContent>
      </Card>

      {/* 版本历史 */}
      <Card className="animate-in fade-in slide-in-from-bottom-3 duration-500">
        <CardHeader className="pb-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <CardTitle className="flex items-center gap-2">
                <BiHistory className="text-primary" />
                版本历史
              </CardTitle>
              <CardDescription>按版本号倒序；载入旧版本到表单后可保存为新版本</CardDescription>
            </div>
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="rounded-full"
              disabled={loading}
              onClick={() => void refreshVersions()}
            >
              <BiRefresh className="mr-1" />
              刷新历史
            </Button>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-sm">
              <thead>
                <tr className="border-b bg-muted/30 text-left text-xs text-muted-foreground">
                  <th className="px-3 py-2.5 font-medium">版本</th>
                  <th className="px-3 py-2.5 font-medium">创建时间</th>
                  <th className="px-3 py-2.5 font-medium">说明</th>
                  <th className="px-3 py-2.5 font-medium">来源哈希</th>
                  <th className="px-3 py-2.5 font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {versions.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="px-3 py-8 text-center text-sm text-muted-foreground">
                      暂无版本历史（保存后生成第一个版本）
                    </td>
                  </tr>
                ) : (
                  versions.map((version) => (
                    <tr key={`${version.version}-${version.sourceHash ?? ''}`} className="border-b last:border-0">
                      <td className="px-3 py-2.5">
                        <span
                          className={
                            version.version === active?.version
                              ? 'font-semibold text-success'
                              : 'font-medium'
                          }
                        >
                          v{version.version}
                          {version.version === active?.version ? '（生效）' : ''}
                        </span>
                      </td>
                      <td className="whitespace-nowrap px-3 py-2.5 text-muted-foreground">
                        {formatDateTime(version.createdAt)}
                      </td>
                      <td className="max-w-[280px] px-3 py-2.5">
                        <span className="line-clamp-2 break-all text-muted-foreground">{version.note || '-'}</span>
                      </td>
                      <td className="px-3 py-2.5 font-mono text-xs text-muted-foreground">
                        <span title={version.sourceHash ?? undefined}>{shortId(version.sourceHash, 12, 6)}</span>
                      </td>
                      <td className="px-3 py-2.5">
                        <Button type="button" size="sm" variant="ghost" className="rounded-full" onClick={() => loadVersionIntoForm(version)}>
                          载入表单
                        </Button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      {saveMessage?.tone === 'error' && error ? (
        <p className="flex items-center gap-1 text-xs text-red-600 dark:text-red-300">
          <BiErrorCircle />
          若持续失败，请检查后端版本（V5 迁移 / RuleConfigService）是否已就绪。
        </p>
      ) : null}
    </div>
  )
}
