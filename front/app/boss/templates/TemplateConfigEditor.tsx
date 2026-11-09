'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  BiCheckCircle,
  BiErrorCircle,
  BiImage,
  BiInfoCircle,
  BiRefresh,
  BiSave,
  BiUpload,
  BiX,
} from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import {
  ApiError,
  getBossOptions,
  getTemplateDetail,
  templateResumeImageUrl,
  updateTemplateConfig,
  uploadTemplateResume,
} from '@/lib/api'
import type {
  BossOption,
  BossOptionType,
  RulePayload,
  TemplateBossConfig,
  TemplateConfigRequest,
  TemplateDetail,
  TemplateView,
} from '@/lib/boss-types'

/**
 * 配置中心 · 模板完整配置编辑器。
 *
 * 在 /boss/templates 内直接编辑一个模板的整套配置（搜索与投递 / 公司要求与筛选规则 /
 * AI 配置 / 简历），保存调 PUT /api/boss/templates/{id}/config：
 * - active 模板保存后立即应用到运行配置（applied=true）；
 * - 非 active 模板只更新快照，激活后下一轮生效。
 *
 * 打开时并行加载 getTemplateDetail + 各类型 BOSS 选项；加载失败可重试。
 */

const DEGREE_OPTIONS = ['高中', '中专', '大专', '本科', '硕士', '博士']

const MATCH_MODE_OPTIONS = [
  { value: 'CONTAINS', label: 'CONTAINS · 包含匹配' },
  { value: 'EXACT', label: 'EXACT · 精确匹配' },
]

const OPTION_TYPES: BossOptionType[] = ['city', 'jobType', 'industry', 'experience', 'degree', 'salary', 'scale', 'stage']

const RESUME_ACCEPT = 'image/png,image/jpeg,image/gif,image/webp,.png,.jpg,.jpeg,.gif,.webp'
const MAX_RESUME_BYTES = 10 * 1024 * 1024

interface BossForm {
  keywordsText: string
  city: string
  jobType: string
  industry: string[]
  experience: string[]
  degree: string[]
  salary: string[]
  scale: string[]
  stage: string[]
  enableAI: boolean
  sendImgResume: boolean
  allowRepeatChat: boolean
  sayHi: string
  repeatChatMessages: string
  waitTime: string
  // D1：模板级采集策略（留空=全局默认；0=不限制）
  collectMaxPerKeyword: string
  collectMaxDurationMinutes: string
  collectNoNewWindowSeconds: string
}

interface RuleForm {
  highestDegree: string
  matchMode: string
  expectedSalaryMin: string
  expectedSalaryMax: string
  blacklistCitiesText: string
  blacklistJobText: string
  blacklistCompanyText: string
  blacklistRecruiterText: string
  whitelistText: string
  filterDeadHr: boolean
  deadStatusText: string
}

interface AiForm {
  aiIntroduce: string
  aiPrompt: string
}

type OptionsMap = Partial<Record<BossOptionType, BossOption[]>>

interface SaveMessage {
  tone: 'success' | 'error'
  title: string
  text: string
  warnings?: string[]
}

// ==================== 解析 / 格式化工具 ====================

/** 解析模板里的列表字符串：JSON 数组、[a,b] 括号列表、逗号/换行分隔都兼容 */
function parseTextList(raw?: string | null): string[] {
  if (raw === null || raw === undefined) return []
  const trimmed = raw.trim()
  if (!trimmed) return []
  if (trimmed.startsWith('[') || trimmed.startsWith('"')) {
    try {
      const parsed: unknown = JSON.parse(trimmed)
      if (Array.isArray(parsed)) {
        return parsed.map((item) => String(item).trim()).filter((item) => item.length > 0)
      }
      if (typeof parsed === 'string') return [parsed.trim()]
    } catch {
      // 非 JSON：按括号列表继续解析
    }
  }
  const inner = trimmed.replace(/^\[/, '').replace(/\]$/, '')
  return inner
    .split(/[,，、\n\r]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

/** 输入框文本 → 字符串数组（逗号/顿号/换行分隔） */
function splitList(text: string): string[] {
  return text
    .split(/[,，、\n\r]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function joinLines(values?: string[] | null): string {
  return (values ?? []).join('\n')
}

function toIntOrNull(value: string): number | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? Math.trunc(parsed) : null
}

/** D1：空输入用 -1 表示“恢复全局默认”，后端接收 -1 后会清空该字段。 */
function toPolicyInput(value: string): number | null {
  return toIntOrNull(value) ?? -1
}

function numberText(value?: number | null): string {
  return value === null || value === undefined ? '' : String(value)
}

function asBool(value?: number | boolean | null): boolean {
  if (typeof value === 'boolean') return value
  return value === 1
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function bossFormFromDetail(detail: TemplateDetail): BossForm {
  const config: TemplateBossConfig = detail.bossConfig ?? {}
  const policy = detail.deliveryPolicy ?? null
  return {
    keywordsText: parseTextList(config.keywords).join(', '),
    city: config.cityCode ?? '',
    jobType: config.jobType ?? '',
    industry: parseTextList(config.industry),
    experience: parseTextList(config.experience),
    degree: parseTextList(config.degree),
    salary: parseTextList(config.salary),
    scale: parseTextList(config.scale),
    // 空值在 BOSS 侧表示不限；编辑器统一把它们显式展示为「不限」，避免看起来像漏选。
    stage: parseTextList(config.stage).length > 0 ? parseTextList(config.stage) : ['不限'],
    enableAI: asBool(config.enableAi),
    sendImgResume: asBool(config.sendImgResume),
    allowRepeatChat: asBool(config.allowRepeatChat),
    sayHi: config.sayHi ?? '',
    repeatChatMessages: config.repeatChatMessages ?? '',
    waitTime: numberText(config.waitTime),
    collectMaxPerKeyword: numberText(policy?.collectMaxPerKeyword),
    collectMaxDurationMinutes: numberText(policy?.collectMaxDurationMinutes),
    collectNoNewWindowSeconds: numberText(policy?.collectNoNewWindowSeconds),
  }
}

function ruleFormFromRule(rule?: RulePayload | null): RuleForm {
  return {
    highestDegree: rule?.highestDegree || '本科',
    matchMode: rule?.blacklistMatchMode || 'CONTAINS',
    expectedSalaryMin: numberText(rule?.expectedSalaryMin),
    expectedSalaryMax: numberText(rule?.expectedSalaryMax),
    blacklistCitiesText: joinLines(rule?.blacklistCities),
    blacklistJobText: joinLines(rule?.blacklistJob),
    blacklistCompanyText: joinLines(rule?.blacklistCompany),
    blacklistRecruiterText: joinLines(rule?.blacklistRecruiter),
    whitelistText: joinLines(rule?.whitelist),
    filterDeadHr: rule?.filterDeadHr === true,
    deadStatusText: joinLines(rule?.deadStatus),
  }
}

// ==================== 左栏锚点导航 ====================

const EDITOR_SECTIONS = [
  // navLabel 去掉字母前缀：右栏 section 标题已经有 A./B./C./D.，左栏再写一遍会读成两列标题
  { id: 'sec-a', label: 'A. 搜索与投递', navLabel: '搜索与投递' },
  { id: 'sec-b', label: 'B. 公司要求与筛选规则', navLabel: '公司要求与筛选规则' },
  { id: 'sec-c', label: 'C. AI 配置', navLabel: 'AI 配置' },
  { id: 'sec-d', label: 'D. 模板图片简历', navLabel: '模板图片简历' },
]

/**
 * 左栏 sticky 锚点（S7）。
 * - 纯 `<a href="#...">` 跳转（静态导出下不用 router），不做平滑滚动动画；
 * - 滚动高亮用 IntersectionObserver，拿不准时保持上一个高亮，不闪烁。
 * - 「高级调试」是独立的全局页面，不在模板编辑器内出现入口。
 */
function EditorAnchorNav() {
  const [active, setActive] = useState('sec-a')

  useEffect(() => {
    const nodes = EDITOR_SECTIONS.map((section) => document.getElementById(section.id)).filter(
      (node): node is HTMLElement => node !== null
    )
    if (nodes.length === 0) return
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
        if (visible[0]) setActive(visible[0].target.id)
      },
      { rootMargin: '-8% 0px -72% 0px', threshold: [0, 0.15] }
    )
    nodes.forEach((node) => observer.observe(node))
    return () => observer.disconnect()
  }, [])

  return (
    <nav aria-label="编辑器区块导航" className="sticky top-4 space-y-0.5">
      {EDITOR_SECTIONS.map((section) => {
        const isActive = active === section.id
        return (
          <a
            key={section.id}
            href={`#${section.id}`}
            aria-current={isActive ? 'true' : undefined}
            className={`flex h-[30px] items-center rounded-control px-2 text-xs transition-colors duration-100 ${
              isActive
                ? 'bg-card font-medium text-ink-900 shadow-[inset_2px_0_0_hsl(var(--primary))] dark:text-sand-100'
                : 'text-clay-700 hover:bg-surface-inset hover:text-ink-900 dark:text-clay-500 dark:hover:text-sand-100'
            }`}
          >
            <span className="truncate">{section.navLabel}</span>
          </a>
        )
      })}
    </nav>
  )
}

// ==================== 选项型组件 ====================

function singleOptions(options: BossOption[], current: string): { value: string; label: string }[] {
  const list = options.map((option) => ({ value: option.name, label: option.name }))
  if (current && !list.some((item) => item.value === current)) {
    list.unshift({ value: current, label: `${current}（未匹配选项）` })
  }
  return list
}

function MultiCheckList({
  label,
  options,
  selected,
  onChange,
  hint,
}: {
  label: string
  options: BossOption[]
  selected: string[]
  onChange: (next: string[]) => void
  hint?: string
}) {
  const selectedSet = new Set(selected)
  const unmatched = selected.filter((name) => !options.some((option) => option.name === name))
  const UNLIMITED = '不限'
  const toggle = (name: string) => {
    // 「不限」与具体选项互斥：选不限清空其它；选其它自动取消不限。
    if (name === UNLIMITED) {
      onChange(selectedSet.has(UNLIMITED) ? [] : [UNLIMITED])
      return
    }
    const withoutUnlimited = selected.filter((item) => item !== UNLIMITED)
    if (selectedSet.has(name)) {
      onChange(withoutUnlimited.filter((item) => item !== name))
    } else {
      onChange([...withoutUnlimited, name])
    }
  }
  return (
    <div className="space-y-1.5">
      <span className="block text-xs font-semibold text-clay-600 dark:text-clay-500">{label}</span>
      <div className="max-h-[158px] overflow-y-auto rounded-control border border-hairline bg-card p-2">
        {options.length === 0 ? (
          <p className="text-xs text-clay-500">暂无选项数据（仍可用下方未匹配项保留旧值）</p>
        ) : (
          options.map((option) => (
            <label
              key={option.code || option.name}
              className="flex min-h-7 cursor-pointer items-center gap-2 rounded-tag px-1 py-0.5 text-sm text-ink-800 hover:bg-surface-inset dark:text-sand-200"
            >
              <input
                type="checkbox"
                className="size-4 shrink-0 accent-[hsl(var(--primary))]"
                checked={selectedSet.has(option.name)}
                onChange={() => toggle(option.name)}
              />
              <span className="min-w-0 leading-5" title={option.name}>
                {option.name}
              </span>
            </label>
          ))
        )}
      </div>
      {unmatched.length > 0 ? (
        <div className="flex flex-wrap gap-1">
          {unmatched.map((name) => (
            <span
              key={name}
              className="inline-flex items-center gap-1 rounded-full border border-amber-500/40 bg-amber-500/10 px-2 py-0.5 text-[11px] text-amber-800 dark:text-amber-200"
            >
              {name}（未匹配）
              <button type="button" aria-label={`移除 ${name}`} onClick={() => toggle(name)}>
                <BiX />
              </button>
            </span>
          ))}
        </div>
      ) : null}
      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  )
}

function SwitchRow({
  title,
  description,
  checked,
  onChange,
}: {
  title: string
  description: string
  checked: boolean
  onChange: (next: boolean) => void
}) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-lg border bg-muted/20 px-3 py-2">
      <div>
        <p className="text-sm font-medium">{title}</p>
        <p className="text-xs text-muted-foreground">{description}</p>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={title}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors ${
          checked ? 'bg-primary' : 'bg-muted-foreground/40'
        }`}
      >
        <span
          aria-hidden="true"
          className={`pointer-events-none block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform ${
            checked ? 'translate-x-5' : 'translate-x-0.5'
          }`}
        />
      </button>
    </div>
  )
}

const TEXTAREA_CLASS =
  'flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-7 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring'

// ==================== 编辑器 ====================

export default function TemplateConfigEditor({
  template,
  onClose,
  onSaved,
  embedded = false,
}: {
  template: TemplateView
  onClose: () => void
  /** 保存后回调（父组件据此刷新模板列表/当前模板横幅） */
  onSaved: (template: TemplateView) => void
  /** 嵌入配置空间时隐藏「收起编辑器」按钮；保存仍只走底部 sticky 栏 */
  embedded?: boolean
}) {
  const [detail, setDetail] = useState<TemplateDetail | null>(null)
  const [options, setOptions] = useState<OptionsMap>({})
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [optionsWarning, setOptionsWarning] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  const [bossForm, setBossForm] = useState<BossForm>(() => bossFormFromDetail({ template }))
  const [ruleForm, setRuleForm] = useState<RuleForm>(() => ruleFormFromRule(null))
  const [aiForm, setAiForm] = useState<AiForm>({ aiIntroduce: '', aiPrompt: '' })
  const [aiBaseline, setAiBaseline] = useState<AiForm>({ aiIntroduce: '', aiPrompt: '' })

  const [saving, setSaving] = useState(false)
  const [saveMessage, setSaveMessage] = useState<SaveMessage | null>(null)

  const [uploading, setUploading] = useState(false)
  const [showResumePreview, setShowResumePreview] = useState(false)
  const [resumeError, setResumeError] = useState(false)
  const [resumeRequired, setResumeRequired] = useState(false)
  const resumeInputRef = useRef<HTMLInputElement | null>(null)

  // 并行加载详情 + 全部选项（选项失败不阻断编辑）
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    setOptionsWarning(null)

    const optionsTask = Promise.all(
      OPTION_TYPES.map((type) =>
        getBossOptions(type)
          .then((list) => ({ type, list: Array.isArray(list) ? list : [] }))
          .catch(() => ({ type, list: [] as BossOption[] }))
      )
    )

    Promise.all([getTemplateDetail(template.id), optionsTask])
      .then(([loadedDetail, optionEntries]) => {
        if (cancelled) return
        setDetail(loadedDetail)
        const nextBoss = bossFormFromDetail(loadedDetail)
        const nextRule = ruleFormFromRule(loadedDetail.rule)
        const nextAi = {
          aiIntroduce: loadedDetail.aiIntroduce ?? '',
          aiPrompt: loadedDetail.aiPrompt ?? '',
        }
        setBossForm(nextBoss)
        setRuleForm(nextRule)
        setAiForm(nextAi)
        setAiBaseline(nextAi)
        const map: OptionsMap = {}
        let emptyCount = 0
        for (const entry of optionEntries) {
          map[entry.type] = entry.list
          if (entry.list.length === 0) emptyCount += 1
        }
        setOptions(map)
        if (emptyCount === OPTION_TYPES.length) {
          setOptionsWarning('选项接口暂不可用：下拉/多选项为空，仍可查看并保存已回填的配置值。')
        }
        setShowResumePreview(false)
        setResumeError(false)
      })
      .catch((error) => {
        if (!cancelled) setLoadError(errorMessage(error))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [template.id, reloadKey])

  const updateBoss = <K extends keyof BossForm,>(key: K, value: BossForm[K]) =>
    setBossForm((prev) => ({ ...prev, [key]: value }))
  const updateRule = <K extends keyof RuleForm,>(key: K, value: RuleForm[K]) =>
    setRuleForm((prev) => ({ ...prev, [key]: value }))

  const requireTemplateResume = (message: string) => {
    setResumeRequired(true)
    setSaveMessage({ tone: 'error', title: '请先上传模板图片简历', text: message })
    document.getElementById('sec-d')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }

  const handleSave = async () => {
    if (saving || loading) return
    if (bossForm.sendImgResume && !(detail?.template ?? template).hasResume) {
      requireTemplateResume('已勾选「自动发送图片简历」，但该模板还没有独立图片简历。请先在 D. 模板图片简历上传，再保存。')
      return
    }
    const ruleMin = toIntOrNull(ruleForm.expectedSalaryMin)
    const ruleMax = toIntOrNull(ruleForm.expectedSalaryMax)
    if (ruleMin !== null && ruleMax !== null && ruleMax > 0 && ruleMax < ruleMin) {
      setSaveMessage({ tone: 'error', title: '保存失败', text: '薪资上限不能低于下限。' })
      return
    }

    // D1：模板级采集策略（留空=-1=恢复全局默认；0=不限制；其余必须 >=0）
    const deliveryPolicy = {
      collectMaxPerKeyword: toPolicyInput(bossForm.collectMaxPerKeyword),
      collectMaxDurationMinutes: toPolicyInput(bossForm.collectMaxDurationMinutes),
      collectNoNewWindowSeconds: toPolicyInput(bossForm.collectNoNewWindowSeconds),
    }
    if (Object.values(deliveryPolicy).some((value) => value !== null && value < -1)) {
      setSaveMessage({ tone: 'error', title: '保存失败', text: '采集策略数值不能小于 -1（-1 表示恢复全局默认，0 表示不限制）。' })
      return
    }

    // 搜索关键词同时作为岗位方向白名单，避免同一组词维护两份。
    const sharedDirections = splitList(bossForm.keywordsText)
    const rulePayload: RulePayload = {
      highestDegree: ruleForm.highestDegree,
      // 规则固定「区间有交集」策略；列表字段按最新语义清空旧过滤
      salaryStrategy: 'INTERSECT',
      expectedSalaryMin: ruleMin,
      // 留空=不设上限；规则固定「区间有交集」策略。
      expectedSalaryMax: ruleMax ?? 0,
      jobDirections: sharedDirections,
      cities: [],
      blacklistCities: splitList(ruleForm.blacklistCitiesText),
      workTypes: [],
      blacklistMatchMode: ruleForm.matchMode,
      blacklistJob: splitList(ruleForm.blacklistJobText),
      blacklistCompany: splitList(ruleForm.blacklistCompanyText),
      blacklistRecruiter: splitList(ruleForm.blacklistRecruiterText),
      whitelist: splitList(ruleForm.whitelistText),
      filterDeadHr: ruleForm.filterDeadHr,
      deadStatus: splitList(ruleForm.deadStatusText),
    }

    const aiChanged =
      aiForm.aiIntroduce !== aiBaseline.aiIntroduce ||
      aiForm.aiPrompt !== aiBaseline.aiPrompt

    const payload: TemplateConfigRequest = {
      keywords: splitList(bossForm.keywordsText),
      cityCode: bossForm.city.trim() || null,
      jobType: bossForm.jobType.trim() || null,
      industry: bossForm.industry,
      experience: bossForm.experience,
      degree: bossForm.degree,
      salary: bossForm.salary,
      scale: bossForm.scale,
      stage: bossForm.stage,
      enableAI: bossForm.enableAI,
      sendImgResume: bossForm.sendImgResume,
      allowRepeatChat: bossForm.allowRepeatChat,
      // bossConfig 与 rule 的 HR 活跃过滤保持一致（以「公司要求与筛选规则」区为准）
      filterDeadHr: ruleForm.filterDeadHr,
      deadStatus: ruleForm.deadStatusText.trim() ? ruleForm.deadStatusText.trim() : null,
      sayHi: bossForm.sayHi,
      repeatChatMessages: bossForm.repeatChatMessages,
      waitTime: toIntOrNull(bossForm.waitTime),
      rule: rulePayload,
      // 未编辑 AI 文本时不要回写，避免用模板编辑器里的旧值覆盖刚更新的全局 AI 提示词。
      ...(aiChanged
        ? { aiIntroduce: aiForm.aiIntroduce, aiPrompt: aiForm.aiPrompt }
        : {}),
      // D1：采集策略（只影响采集；每日首次沟通上限为全局预算，不在模板里）
      deliveryPolicy,
    }

    setSaving(true)
    setSaveMessage(null)
    try {
      const result = await updateTemplateConfig(template.id, payload)
      setSaveMessage({
        tone: 'success',
        title: result.applied ? '已保存并立即应用' : '已保存模板快照（激活后生效）',
        text: result.applied
          ? '该模板当前生效：配置已写入运行配置，下一轮任务按此运行（当前运行中的任务不受影响）。'
          : '该模板未激活：保存只更新模板快照，点击「激活」后下一轮任务才会使用。',
        warnings: result.warnings ?? [],
      })
      onSaved(result.template)
      setReloadKey((key) => key + 1)
    } catch (saveError) {
      if (saveError instanceof ApiError) {
        setSaveMessage({ tone: 'error', title: '保存失败', text: saveError.message })
      } else {
        setSaveMessage({ tone: 'error', title: '保存失败', text: errorMessage(saveError) })
      }
    } finally {
      setSaving(false)
    }
  }

  const handleResumeChange = useCallback(
    async (file: File | null) => {
      if (!file) return
      if (!file.type.startsWith('image/') && !/\.(png|jpe?g|gif|webp)$/i.test(file.name)) {
        setSaveMessage({ tone: 'error', title: '上传失败', text: `模板简历只支持图片文件（PNG/JPG/GIF/WebP），当前：${file.name}` })
        return
      }
      if (file.size > MAX_RESUME_BYTES) {
        setSaveMessage({ tone: 'error', title: '上传失败', text: `模板简历不能超过 10MB（当前 ${(file.size / 1024 / 1024).toFixed(1)}MB）` })
        return
      }
      setUploading(true)
      try {
        const updated = await uploadTemplateResume(template.id, file)
        onSaved(updated)
        setShowResumePreview(false)
        setResumeError(false)
        setResumeRequired(false)
        setSaveMessage({
          tone: 'success',
          title: '模板简历已更新',
          text: `已上传「${file.name}」作为模板「${updated.name}」的独立图片简历（仅该模板使用）。`,
        })
        setReloadKey((key) => key + 1)
      } catch (uploadError) {
        if (uploadError instanceof ApiError) {
          setSaveMessage({ tone: 'error', title: '上传失败', text: uploadError.message })
        } else {
          setSaveMessage({ tone: 'error', title: '上传失败', text: errorMessage(uploadError) })
        }
      } finally {
        setUploading(false)
      }
    },
    [template.id, onSaved]
  )

  const currentTemplate = detail?.template ?? template
  const namePrefix = currentTemplate.name

  /** 未保存改动：按「分段是否有变化」计数（0–3），不做逐字段 diff。
   *  基线用同一个 builder 从 detail 重算 —— 不依赖挂载时机，避免「刚打开就显示 3 处改动」。 */
  const dirtyCount = detail
    ? [
        JSON.stringify(bossForm) !== JSON.stringify(bossFormFromDetail(detail)),
        JSON.stringify(ruleForm) !== JSON.stringify(ruleFormFromRule(detail.rule)),
        JSON.stringify(aiForm) !==
          JSON.stringify({
            aiIntroduce: detail.aiIntroduce ?? '',
            aiPrompt: detail.aiPrompt ?? '',
          }),
      ].filter(Boolean).length
    : 0

  return (
    <Card className="border-[hsl(var(--primary)/0.4)]">
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <CardTitle className="flex flex-wrap items-center gap-2">
              <BiInfoCircle className="text-primary" />
              编辑配置 · {namePrefix}
              {currentTemplate.active ? (
                <span className="inline-flex h-5 items-center gap-1 rounded-tag border border-[hsl(var(--primary)/0.28)] bg-primary-subtle px-1.5 text-xs text-primary-on-subtle">
                  <BiCheckCircle />
                  当前生效
                </span>
              ) : null}
            </CardTitle>
            <CardDescription>
              在配置中心内直接编辑整套配置；保存后{' '}
              {currentTemplate.active ? '会立即应用到运行配置（下一轮任务生效）' : '只更新模板快照，激活后生效'}
            </CardDescription>
          </div>
          {embedded ? null : (
            <div className="flex items-center gap-2">
              {/* 保存/重新加载都收敛到底部 sticky 栏，卡头只留关闭 */}
              <Button type="button" size="sm" variant="ghost" onClick={onClose}>
                <BiX className="mr-1" />
                收起编辑器
              </Button>
            </div>
          )}
        </div>
      </CardHeader>
      <CardContent className="px-5 pb-16">
        {loading ? (
          <div className="grid gap-3 md:grid-cols-2">
            {Array.from({ length: 4 }).map((_, index) => (
              <div key={`editor-skeleton-${index}`} className="h-24 animate-pulse rounded-card bg-surface-inset" />
            ))}
          </div>
        ) : loadError ? (
          <div className="space-y-3 rounded-control border border-danger-border border-l-2 border-l-danger bg-danger-bg px-4 py-3 text-sm text-danger">
            <p className="flex items-center gap-2">
              <BiErrorCircle />
              模板配置加载失败：{loadError}
            </p>
            <Button type="button" size="sm" variant="outline" className="rounded-full" onClick={() => setReloadKey((key) => key + 1)}>
              <BiRefresh className="mr-1" />
              重试
            </Button>
          </div>
        ) : (
          <div className="grid grid-cols-12 gap-6">
            {/* 左栏：sticky 锚点（1280 以下隐藏，内容单列） */}
            <div className="col-span-12 hidden min-[1280px]:col-span-2 min-[1280px]:block">
              <EditorAnchorNav />
            </div>

            <div className="col-span-12 min-[1280px]:col-span-10">
              {optionsWarning ? (
                <p className="mb-4 rounded-control border border-warning-border border-l-2 border-l-warning bg-warning-bg px-3 py-2 text-sm text-warning">
                  {optionsWarning}
                </p>
              ) : null}

            {/* A. 搜索与投递 */}
            <section id="sec-a" className="scroll-mt-4 space-y-4">
              <h3 className="text-sm font-semibold">A. 搜索与投递</h3>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                <div className="space-y-1.5">
                  <Label htmlFor="cfg-keywords">搜索关键词（逗号/换行分隔）</Label>
                  <textarea
                    id="cfg-keywords"
                    rows={2}
                    value={bossForm.keywordsText}
                    onChange={(event) => updateBoss('keywordsText', event.target.value)}
                    placeholder="例如：AI产品经理, 产品经理"
                    className={TEXTAREA_CLASS}
                  />
                  <p className="text-[11px] text-clay-500">同一组词会自动作为岗位方向白名单：岗位标题需包含其中任一词（包含匹配，不是精确职位分类）。</p>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="cfg-city">城市（单选）</Label>
                  <Select id="cfg-city" value={bossForm.city} onChange={(event) => updateBoss('city', event.target.value)}>
                    <option value="">不指定</option>
                    {singleOptions(options.city ?? [], bossForm.city).map((item) => (
                      <option key={item.value} value={item.value}>
                        {item.label}
                      </option>
                    ))}
                  </Select>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="cfg-jobType">职位类型（单选）</Label>
                  <Select id="cfg-jobType" value={bossForm.jobType} onChange={(event) => updateBoss('jobType', event.target.value)}>
                    <option value="">不指定</option>
                    {singleOptions(options.jobType ?? [], bossForm.jobType).map((item) => (
                      <option key={item.value} value={item.value}>
                        {item.label}
                      </option>
                    ))}
                  </Select>
                </div>
              </div>
              <div className="grid grid-cols-1 gap-x-6 gap-y-5 md:grid-cols-3">
                {/* 先选行业/公司范围，再选个人条件，最后看薪资；保持同一组三列布局。 */}
                <MultiCheckList label="行业" options={options.industry ?? []} selected={bossForm.industry} onChange={(next) => updateBoss('industry', next)} />
                <MultiCheckList label="公司规模" options={options.scale ?? []} selected={bossForm.scale} onChange={(next) => updateBoss('scale', next)} />
                <MultiCheckList label="融资阶段" options={options.stage ?? []} selected={bossForm.stage} onChange={(next) => updateBoss('stage', next)} />
                <MultiCheckList label="经验要求" options={options.experience ?? []} selected={bossForm.experience} onChange={(next) => updateBoss('experience', next)} />
                <MultiCheckList label="学历要求" options={options.degree ?? []} selected={bossForm.degree} onChange={(next) => updateBoss('degree', next)} />
                <MultiCheckList label="薪资范围" options={options.salary ?? []} selected={bossForm.salary} onChange={(next) => updateBoss('salary', next)} />
              </div>
              <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                <div className="space-y-1.5">
                  <Label htmlFor="cfg-wait-time">岗位间等待（秒，可选）</Label>
                  <Input
                    id="cfg-wait-time"
                    type="number"
                    value={bossForm.waitTime}
                    onChange={(event) => updateBoss('waitTime', event.target.value)}
                    placeholder="留空=使用默认"
                  />
                </div>
              </div>
              <div className="grid grid-cols-1 gap-2 md:grid-cols-3">
                <SwitchRow
                  title="启用 AI"
                  description="用 AI 生成打招呼语/分析岗位"
                  checked={bossForm.enableAI}
                  onChange={(next) => updateBoss('enableAI', next)}
                />
                <SwitchRow
                  title="自动发送图片简历"
                  description="文字打招呼成功后发送该模板的图片简历"
                  checked={bossForm.sendImgResume}
                  onChange={(next) => {
                    if (next && !(detail?.template ?? template).hasResume) {
                      requireTemplateResume('勾选「自动发送图片简历」前，请先在 D. 模板图片简历上传图片。')
                      return
                    }
                    setResumeRequired(false)
                    updateBoss('sendImgResume', next)
                  }}
                />
                <SwitchRow
                  title="允许重复沟通"
                  description="现有跟进设置（沿用旧行为，不纳入 D1 模板采集策略）：遇到已沟通 Boss 时发送下面的重复沟通消息"
                  checked={bossForm.allowRepeatChat}
                  onChange={(next) => updateBoss('allowRepeatChat', next)}
                />
              </div>

              {/* D1：采集策略（仅本模板） */}
              <div className="space-y-3 rounded-control border border-hairline bg-surface-inset px-4 py-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <h4 className="text-sm font-medium">采集策略（仅本模板）</h4>
                  <span className="text-xs text-[hsl(var(--text-3))]">留空=恢复全局默认 · 0=不限制（无新增窗口 0=全局默认）</span>
                </div>
                <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                  <div className="space-y-1.5">
                    <Label htmlFor="cfg-collect-max-keyword">每关键词候选上限</Label>
                    <Input
                      id="cfg-collect-max-keyword"
                      type="number"
                      min={0}
                      value={bossForm.collectMaxPerKeyword}
                      onChange={(event) => updateBoss('collectMaxPerKeyword', event.target.value)}
                      placeholder="例如 60（留空=全局默认）"
                    />
                    <p className="text-xs text-[hsl(var(--text-3))]">每个关键词最多处理多少岗位</p>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="cfg-collect-max-duration">单关键词采集时长（分钟）</Label>
                    <Input
                      id="cfg-collect-max-duration"
                      type="number"
                      min={0}
                      value={bossForm.collectMaxDurationMinutes}
                      onChange={(event) => updateBoss('collectMaxDurationMinutes', event.target.value)}
                      placeholder="例如 15（留空=全局默认）"
                    />
                    <p className="text-xs text-[hsl(var(--text-3))]">单个关键词的采集时间上限</p>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="cfg-collect-no-new-window">连续无新增判定（秒）</Label>
                    <Input
                      id="cfg-collect-no-new-window"
                      type="number"
                      min={0}
                      value={bossForm.collectNoNewWindowSeconds}
                      onChange={(event) => updateBoss('collectNoNewWindowSeconds', event.target.value)}
                      placeholder="例如 30（留空=全局默认）"
                    />
                    <p className="text-xs text-[hsl(var(--text-3))]">连续多久无新增卡片即判定加载结束；0/留空=全局默认</p>
                  </div>
                </div>
                <p className="rounded-control border border-[hsl(var(--border-strong))] border-l-2 border-l-[hsl(var(--text-3))] bg-[hsl(var(--sunken))] px-3 py-2 text-xs text-[hsl(var(--text-2))]">
                  每日首次沟通上限由全局预算统一控制，全模板共享（所有方向模板共用），不随模板变化（此处不提供编辑）。
                </p>
              </div>

              <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="cfg-say-hi">默认招呼语</Label>
                  <textarea
                    id="cfg-say-hi"
                    rows={3}
                    value={bossForm.sayHi}
                    onChange={(event) => updateBoss('sayHi', event.target.value)}
                    className={TEXTAREA_CLASS}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="cfg-repeat">重复沟通消息（每行一条，现有跟进设置）</Label>
                  <textarea
                    id="cfg-repeat"
                    rows={3}
                    value={bossForm.repeatChatMessages}
                    onChange={(event) => updateBoss('repeatChatMessages', event.target.value)}
                    className={TEXTAREA_CLASS}
                  />
                  <p className="text-xs text-[hsl(var(--text-3))]">
                    现有跟进设置（沿用旧行为，不纳入 D1 模板采集策略）；跟进发送仍需人工批准，本编辑器不新增跟进逻辑。
                  </p>
                </div>
              </div>
            </section>

            {/* B. 公司要求与筛选规则 */}
            <section id="sec-b" className="mt-6 scroll-mt-4 space-y-4 border-t border-hairline pt-6">
              <h3 className="text-sm font-semibold">B. 公司要求与筛选规则</h3>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                <div className="space-y-1.5">
                  <Label htmlFor="cfg-degree">最高学历</Label>
                  <Select
                    id="cfg-degree"
                    value={ruleForm.highestDegree}
                    onChange={(event) => updateRule('highestDegree', event.target.value)}
                  >
                    {DEGREE_OPTIONS.map((degree) => (
                      <option key={degree} value={degree}>
                        {degree}
                      </option>
                    ))}
                  </Select>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="cfg-match-mode">黑名单匹配模式</Label>
                  <Select
                    id="cfg-match-mode"
                    value={ruleForm.matchMode}
                    onChange={(event) => updateRule('matchMode', event.target.value)}
                  >
                    {MATCH_MODE_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </Select>
                </div>
                <div className="space-y-1.5">
                  <Label>规则期望月薪区间（K，留空=不限）</Label>
                  <div className="grid grid-cols-2 gap-2">
                    <Input
                      id="rule-salary-min"
                      type="number"
                      aria-label="规则期望月薪下限"
                      placeholder="下限"
                      value={ruleForm.expectedSalaryMin}
                      onChange={(event) => updateRule('expectedSalaryMin', event.target.value)}
                    />
                    <Input
                      id="rule-salary-max"
                      type="number"
                      aria-label="规则期望月薪上限"
                      placeholder="上限（可不填）"
                      value={ruleForm.expectedSalaryMax}
                      onChange={(event) => updateRule('expectedSalaryMax', event.target.value)}
                    />
                  </div>
                  <p className="text-[11px] text-clay-500">
                    规则固定「区间有交集」策略；只拦截与期望区间完全无交集的岗位。上限不是硬性封顶，留空表示不设上限。
                  </p>
                </div>
              </div>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="rule-blacklist-cities">不可接受城市（逗号/换行，留空=不限）</Label>
                  <textarea
                    id="rule-blacklist-cities"
                    rows={3}
                    value={ruleForm.blacklistCitiesText}
                    placeholder="例如：北京、上海外派"
                    onChange={(event) => updateRule('blacklistCitiesText', event.target.value)}
                    className={TEXTAREA_CLASS}
                  />
                  <p className="text-xs text-clay-500">
                    岗位城市命中任一不可接受城市会被拒绝；BOSS 搜索城市仍在上方「搜索与投递」区设置。
                  </p>
                </div>
              </div>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
                <div className="space-y-1.5">
                  <Label htmlFor="rule-blacklist-job">岗位黑名单（每行一个）</Label>
                  <textarea
                    id="rule-blacklist-job"
                    rows={3}
                    value={ruleForm.blacklistJobText}
                    onChange={(event) => updateRule('blacklistJobText', event.target.value)}
                    className={TEXTAREA_CLASS}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="rule-blacklist-company">公司黑名单（每行一个）</Label>
                  <textarea
                    id="rule-blacklist-company"
                    rows={3}
                    value={ruleForm.blacklistCompanyText}
                    onChange={(event) => updateRule('blacklistCompanyText', event.target.value)}
                    className={TEXTAREA_CLASS}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="rule-blacklist-recruiter">HR 黑名单（每行一个）</Label>
                  <textarea
                    id="rule-blacklist-recruiter"
                    rows={3}
                    value={ruleForm.blacklistRecruiterText}
                    onChange={(event) => updateRule('blacklistRecruiterText', event.target.value)}
                    className={TEXTAREA_CLASS}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="rule-whitelist">公司白名单（每行一个）</Label>
                  <textarea
                    id="rule-whitelist"
                    rows={3}
                    value={ruleForm.whitelistText}
                    onChange={(event) => updateRule('whitelistText', event.target.value)}
                    className={TEXTAREA_CLASS}
                  />
                </div>
              </div>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                <div className="flex items-center justify-between gap-3 rounded-lg border bg-muted/20 px-3 py-2">
                  <div>
                    <p className="text-sm font-medium">HR 活跃过滤</p>
                    <p className="text-xs text-muted-foreground">开启后 HR 状态命中下方列表时拒绝</p>
                  </div>
                  <button
                    type="button"
                    role="switch"
                    aria-checked={ruleForm.filterDeadHr}
                    aria-label="HR 活跃过滤"
                    onClick={() => updateRule('filterDeadHr', !ruleForm.filterDeadHr)}
                    className={`relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors ${
                      ruleForm.filterDeadHr ? 'bg-primary' : 'bg-muted-foreground/40'
                    }`}
                  >
                    <span
                      aria-hidden="true"
                      className={`pointer-events-none block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform ${
                        ruleForm.filterDeadHr ? 'translate-x-5' : 'translate-x-0.5'
                      }`}
                    />
                  </button>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="rule-dead-status">不活跃状态（逗号/换行）</Label>
                  <textarea
                    id="rule-dead-status"
                    rows={3}
                    value={ruleForm.deadStatusText}
                    onChange={(event) => updateRule('deadStatusText', event.target.value)}
                    placeholder="例如：半年前活跃, 3月内活跃"
                    className={TEXTAREA_CLASS}
                  />
                  <p className="text-xs text-muted-foreground">
                    保存时会同时写入 BOSS 配置的 HR 活跃过滤字段，保持两处一致。
                  </p>
                </div>
              </div>
            </section>

            {/* C. AI 配置 */}
            <section id="sec-c" className="mt-6 scroll-mt-4 space-y-3 border-t border-hairline pt-6">
              <h3 className="text-sm font-semibold">C. AI 配置</h3>
              <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="cfg-ai-introduce">个人介绍（aiIntroduce）</Label>
                  <textarea
                    id="cfg-ai-introduce"
                    rows={6}
                    value={aiForm.aiIntroduce}
                    onChange={(event) => setAiForm((prev) => ({ ...prev, aiIntroduce: event.target.value }))}
                    className={TEXTAREA_CLASS}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="cfg-ai-prompt">提示词（aiPrompt）</Label>
                  <textarea
                    id="cfg-ai-prompt"
                    rows={6}
                    value={aiForm.aiPrompt}
                    onChange={(event) => setAiForm((prev) => ({ ...prev, aiPrompt: event.target.value }))}
                    className={TEXTAREA_CLASS}
                  />
                  <p className="text-xs text-muted-foreground">
                    格式由后端严格校验。未修改 AI 文本时保存不会回写，避免覆盖全局更新。
                  </p>
                </div>
              </div>
            </section>

            {/* D. 简历 */}
            <section
              id="sec-d"
              className="mt-6 scroll-mt-4 space-y-3 border-t border-hairline pt-6"
              style={resumeRequired ? { borderColor: 'var(--v3-bad)' } : undefined}
            >
              <h3 className="text-sm font-semibold">D. 模板图片简历</h3>
              {resumeRequired ? (
                <p role="alert" className="rounded-[8px] border border-[var(--v3-bad)]/40 bg-[var(--bad-soft)] px-3 py-2 text-xs" style={{ color: 'var(--v3-bad)' }}>
                  启用自动发送图片简历前，请先上传该模板的独立图片简历。
                </p>
              ) : null}
              <p className="text-xs text-clay-600 dark:text-clay-500">
                图片简历单独上传，不随「保存配置」提交。
              </p>
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <span className="text-muted-foreground">当前状态：</span>
                <span>{currentTemplate.hasResume ? '已上传（仅本模板使用）' : '无独立简历（使用当前配置的简历）'}</span>
                {detail?.resumeFile ? (
                  <span className="text-xs text-muted-foreground">文件：{detail.resumeFile}</span>
                ) : null}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <input
                  ref={resumeInputRef}
                  type="file"
                  accept={RESUME_ACCEPT}
                  className="hidden"
                  onChange={(event) => {
                    const file = event.target.files?.[0] ?? null
                    event.target.value = ''
                    void handleResumeChange(file)
                  }}
                />
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="rounded-full"
                  disabled={uploading}
                  onClick={() => resumeInputRef.current?.click()}
                >
                  <BiUpload className="mr-1" />
                  {uploading ? '上传中…' : currentTemplate.hasResume ? '重新上传模板简历' : '上传模板简历'}
                </Button>
                {currentTemplate.hasResume ? (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    className="rounded-full"
                    onClick={() => {
                      setResumeError(false)
                      setShowResumePreview((value) => !value)
                    }}
                  >
                    <BiImage className="mr-1" />
                    {showResumePreview ? '收起预览' : '预览模板简历'}
                  </Button>
                ) : null}
                <span className="text-xs text-muted-foreground">支持 PNG/JPG/GIF/WebP，≤10MB。</span>
              </div>
              {showResumePreview && currentTemplate.hasResume ? (
                resumeError ? (
                  <p className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-800 dark:text-amber-200">
                    简历图片加载失败：可能尚未上传，或后端未返回图片内容。
                  </p>
                ) : (
                  // eslint-disable-next-line @next/next/no-img-element -- 后端动态接口返回的图片，无法用 next/image 优化
                  <img
                    src={templateResumeImageUrl(currentTemplate.id, currentTemplate.updatedAt)}
                    alt={`模板「${currentTemplate.name}」的图片简历`}
                    className="mx-auto max-h-72 w-auto rounded-lg border object-contain"
                    onError={() => setResumeError(true)}
                  />
                )
              ) : null}
            </section>

              {/* 保存反馈：sticky 单行，贴在保存栏正上方；不自动消失 */}
              {saveMessage ? (
                <div
                  role="status"
                  className={`sticky bottom-14 z-10 mt-6 flex flex-wrap items-start gap-x-3 gap-y-1 rounded-control border px-3 py-2 text-sm ${
                    saveMessage.tone === 'success'
                      ? 'border-success-border bg-success-bg text-success'
                      : 'border-danger-border bg-danger-bg text-danger'
                  }`}
                >
                  <span className="font-medium">{saveMessage.title}</span>
                  <span className="min-w-0 flex-1">{saveMessage.text}</span>
                  {saveMessage.warnings && saveMessage.warnings.length > 0 ? (
                    <details className="w-full">
                      <summary className="cursor-pointer text-xs opacity-80">
                        {saveMessage.warnings.length} 条提醒
                      </summary>
                      <ul className="mt-1 list-inside list-disc pl-3 text-xs">
                        {saveMessage.warnings.map((warning, index) => (
                          <li key={`${index}-${warning}`}>{warning}</li>
                        ))}
                      </ul>
                    </details>
                  ) : null}
                </div>
              ) : null}

              {/* 底部 sticky 保存栏：唯一保存入口 */}
              <div className="sticky bottom-0 z-10 -mx-5 mt-6 flex items-center justify-between gap-3 border-t border-hairline bg-card px-5 py-3">
                <p className="min-w-0 truncate text-xs">
                  {dirtyCount > 0 ? (
                    <span className="inline-flex items-center gap-1.5 font-medium text-warning">
                      <span aria-hidden="true" className="size-1.5 rounded-full bg-warning" />
                      有 {dirtyCount} 处未保存改动
                    </span>
                  ) : (
                    <span className="text-clay-600 dark:text-clay-500">无改动</span>
                  )}
                </p>
                <div className="flex shrink-0 items-center gap-2">
                  <Button
                    type="button"
                    size="sm"
                    variant="secondary"
                    disabled={loading || saving}
                    onClick={() => setReloadKey((key) => key + 1)}
                  >
                    <BiRefresh className="mr-1" />
                    重新加载
                  </Button>
                  <Button
                    type="button"
                    size="md"
                    variant="primary"
                    disabled={saving || loading}
                    onClick={() => void handleSave()}
                    className="h-11 rounded-full px-6 text-sm font-semibold shadow-md"
                  >
                    <BiSave className="mr-1 size-4" />
                    {saving ? '保存中…' : '保存配置'}
                  </Button>
                </div>
              </div>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
