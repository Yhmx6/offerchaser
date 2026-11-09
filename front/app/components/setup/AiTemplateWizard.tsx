'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { DialogShell } from '@/components/ui/dialog-shell'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  FileImage,
  Loader2,
  ShieldCheck,
  Sparkles,
  X,
} from 'lucide-react'

import {
  ApiError,
  activateTemplate,
  analyzeResume,
  analyzeResumeText,
  createTemplate,
  generateAiTemplateDraft,
  getBossOptions,
  updateTemplateConfig,
  uploadTemplateResume,
} from '@/lib/api'
import type { AiDraftRequest, AiDraftResult, ResumeAnalysis, ResumeDirection } from '@/lib/ai-template-types'
import type { BossOption, TemplateView } from '@/lib/boss-types'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'

/**
 * 「AI 帮我建模板」向导（/boss/setup 方向模板区入口）。
 *
 * 流程：隐私同意 + 上传/粘贴文本/跳过 → 分析 → 画像可编辑确认 → 选 AI 推荐方向 →
 * 逐条问答（城市/薪资/排除条件/强度/图片简历）→ 生成草稿 → 预览 → 保存为草稿或保存并激活。
 *
 * 安全约束：
 * - 不自动开始投递；不自动激活（只有用户显式点「保存并激活」并二次确认才会 activate）；
 * - 草稿未保存前不调用 createTemplate；
 * - 所有请求都走 lib/api.ts（带 Cookie/CSRF）。
 */

type Step =
  | 'upload'
  | 'analyzing'
  | 'profile'
  | 'direction'
  | 'qa-city'
  | 'qa-salary'
  | 'qa-exclusions'
  | 'qa-intensity'
  | 'qa-image'
  | 'drafting'
  | 'preview'
  | 'done'

const QA_STEPS: Step[] = ['qa-city', 'qa-salary', 'qa-exclusions', 'qa-intensity', 'qa-image']
const QA_TITLES: Record<string, string> = {
  'qa-city': '希望优先看哪些城市？（可多选，最多 4 个）',
  'qa-salary': '期望月薪下限是多少？',
  'qa-exclusions': '有哪些岗位/公司/词需要排除？',
  'qa-intensity': '投递强度选哪一档？',
  'qa-image': '需要自动发送图片简历吗？',
}

interface ProfileForm {
  roles: string
  skills: string
  cities: string
  industries: string
  strengths: string
}

interface Answers {
  /** 多城市（最多4，第 1 个主搜索城市） */
  cities: string[]
  salaryMin: string
  salaryMax: string
  /** 常用排除项里勾选的词 */
  exclusionSelected: string[]
  /** 自定义排除项文本（逗号/换行分隔） */
  exclusions: string
  /** 行业偏好（最多6，行业选项名称） */
  industries: string[]
  intensity: 'trial' | 'normal' | 'active'
  sendImgResume: boolean
}

interface AnalyzeError {
  code?: string
  message: string
}

const EMPTY_PROFILE: ProfileForm = {
  roles: '',
  skills: '',
  cities: '',
  industries: '',
  strengths: '',
}

/** 常用排除项（问答里默认勾选） */
const DEFAULT_EXCLUSIONS = ['外包', '销售', '保险', '房产中介', '培训', '劳务派遣']
const MAX_CITIES = 4
const MAX_INDUSTRIES = 6

const EMPTY_ANSWERS: Answers = {
  cities: [],
  salaryMin: '',
  salaryMax: '',
  exclusionSelected: [...DEFAULT_EXCLUSIONS],
  exclusions: '',
  industries: [],
  intensity: 'normal',
  sendImgResume: true,
}

const MAX_RESUME_BYTES = 10 * 1024 * 1024
const RESUME_ACCEPT = 'image/png,image/jpeg,image/webp,.png,.jpg,.jpeg,.webp'

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function errorCode(error: unknown): string | undefined {
  return error instanceof ApiError ? error.code : undefined
}

function splitList(value: string): string[] {
  return value
    .split(/[,，、\n\r]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function joinList(values?: string[] | null): string {
  return (values ?? []).join('、')
}

function numberOrNull(value: string): number | null {
  const text = value.trim()
  if (!text) return null
  const parsed = Number(text)
  return Number.isFinite(parsed) ? Math.trunc(parsed) : null
}

function uniqueList(values: string[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const value of values) {
    const text = value.trim()
    if (!text || seen.has(text)) continue
    seen.add(text)
    result.push(text)
  }
  return result
}

/** 合并「常用排除项 chips + 自定义文本」，去重后进入草稿请求 */
function mergeExclusions(selected: string[], custom: string): string[] {
  return uniqueList([...selected, ...splitList(custom)])
}

/** 画像里的年限 → BOSS 经验档（仅用于预览展示，最终以后端草稿为准） */
function experienceLabel(years?: number | null): string {
  if (years === null || years === undefined || !Number.isFinite(years)) return '不限'
  if (years < 1) return '1年以下'
  if (years < 3) return '1-3年'
  if (years < 5) return '3-5年'
  if (years < 10) return '5-10年'
  return '10年以上'
}

function profileToForm(profile?: ResumeAnalysis['profile'] | null): ProfileForm {
  if (!profile) return { ...EMPTY_PROFILE }
  return {
    roles: joinList(profile.roles),
    skills: joinList(profile.skills),
    cities: joinList(profile.cities),
    industries: joinList(profile.industries),
    strengths: joinList(profile.strengths),
  }
}

function formToProfile(form: ProfileForm, source?: ResumeAnalysis['profile'] | null): ResumeAnalysis['profile'] {
  return {
    // 学历/经验/专业/毕业年份由 AI 识别，前端只读透传（问答不重复询问）
    yearsExperience: source?.yearsExperience ?? null,
    highestDegree: source?.highestDegree ?? null,
    major: source?.major ?? null,
    graduationYear: source?.graduationYear ?? null,
    expectedSalaryMin: source?.expectedSalaryMin ?? null,
    expectedSalaryMax: source?.expectedSalaryMax ?? null,
    roles: splitList(form.roles),
    skills: splitList(form.skills),
    cities: splitList(form.cities),
    industries: splitList(form.industries),
    strengths: splitList(form.strengths),
    warnings: source?.warnings ?? [],
    evidence: source?.evidence ?? {},
    confidence: source?.confidence ?? null,
  }
}

function fitText(fit?: number | null): string {
  if (fit === null || fit === undefined) return '匹配度未知'
  const value = fit <= 1 ? Math.round(fit * 100) : Math.round(fit)
  return `匹配度 ${value}%`
}

function analyzeHint(code?: string): string {
  if (code === 'AI_CONFIG_MISSING') {
    return 'AI 未配置：请先在配置空间的「模型服务」中填写 BASE_URL / API_KEY / MODEL，再回来分析。'
  }
  if (code === 'INVALID_IMAGE') return '图片无法识别：请换一张清晰完整的简历截图，或改用「粘贴简历文本」。'
  if (code === 'VISION_PARSE_FAILED') return '视觉解析失败（模型可能不支持图片）：请改用「粘贴简历文本」。'
  if (code === 'RESUME_ANALYZE_FAILED') return '简历分析失败：可重试，或改用「粘贴简历文本」兜底。'
  return '分析失败：可重试，或改用「粘贴简历文本」兜底。'
}

export default function AiTemplateWizard({
  onClose,
  onOpenModelService,
  onSaved,
}: {
  onClose: () => void
  onOpenModelService: () => void
  onSaved: (template: TemplateView, activated: boolean) => void
}) {
  const [step, setStep] = useState<Step>('upload')
  const [mode, setMode] = useState<'image' | 'text'>('image')
  const [privacyAccepted, setPrivacyAccepted] = useState(false)
  const [privacyError, setPrivacyError] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const [resumeText, setResumeText] = useState('')
  const [supplement, setSupplement] = useState('')

  const [analyzing, setAnalyzing] = useState(false)
  const [analyzeError, setAnalyzeError] = useState<AnalyzeError | null>(null)
  const [analysis, setAnalysis] = useState<ResumeAnalysis | null>(null)

  const [profileForm, setProfileForm] = useState<ProfileForm>({ ...EMPTY_PROFILE })
  const [directions, setDirections] = useState<ResumeDirection[]>([])
  const [salaryBands, setSalaryBands] = useState<ResumeAnalysis['salaryBands']>([])
  const [selectedDirection, setSelectedDirection] = useState<number | null>(null)
  const [customDirection, setCustomDirection] = useState(false)
  const [customTitle, setCustomTitle] = useState('')
  const [keywordsText, setKeywordsText] = useState('')
  const [stepError, setStepError] = useState<string | null>(null)

  const [answers, setAnswers] = useState<Answers>({ ...EMPTY_ANSWERS })
  const [cityOptions, setCityOptions] = useState<BossOption[]>([])
  const [industryOptions, setIndustryOptions] = useState<BossOption[]>([])

  const [drafting, setDrafting] = useState(false)
  const [draftError, setDraftError] = useState<string | null>(null)
  const [draft, setDraft] = useState<AiDraftResult | null>(null)
  const [finalName, setFinalName] = useState('')

  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [createdTemplate, setCreatedTemplate] = useState<TemplateView | null>(null)
  const [savedActivated, setSavedActivated] = useState(false)
  const [activateConfirmOpen, setActivateConfirmOpen] = useState(false)
  const [closeConfirmOpen, setCloseConfirmOpen] = useState(false)

  const fileInputRef = useRef<HTMLInputElement | null>(null)

  // 城市/行业选项（失败时退化为文本输入，不阻断向导）
  useEffect(() => {
    let alive = true
    Promise.allSettled([getBossOptions('city'), getBossOptions('industry')]).then(([cityResult, industryResult]) => {
      if (!alive) return
      setCityOptions(
        cityResult.status === 'fulfilled' && Array.isArray(cityResult.value) ? cityResult.value : []
      )
      setIndustryOptions(
        industryResult.status === 'fulfilled' && Array.isArray(industryResult.value) ? industryResult.value : []
      )
    })
    return () => {
      alive = false
    }
  }, [])

  // 打开时锁定 body 滚动
  useEffect(() => {
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previous
    }
  }, [])

  // Esc：有二次确认时交给确认组件处理，否则请求关闭
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      if (activateConfirmOpen || closeConfirmOpen) return
      if (step === 'upload' || step === 'done') {
        onClose()
      } else {
        setCloseConfirmOpen(true)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [activateConfirmOpen, closeConfirmOpen, onClose, step])

  const selectedAiDirection = useMemo(
    () => (customDirection ? null : selectedDirection === null ? null : directions[selectedDirection] ?? null),
    [customDirection, directions, selectedDirection]
  )

  const directionTitle = customDirection ? customTitle.trim() : selectedAiDirection?.title?.trim() ?? ''

  const industryNames = useMemo(() => industryOptions.map((option) => option.name), [industryOptions])

  /** 方向/画像里的行业 → 与 BOSS 行业选项取交集，最多 6 个 */
  const defaultIndustriesFor = (direction?: ResumeDirection | null, profile?: ResumeAnalysis['profile'] | null) => {
    const pool = uniqueList([...(direction?.industries ?? []), ...(profile?.industries ?? [])])
    const matched = industryNames.length > 0 ? pool.filter((name) => industryNames.includes(name)) : pool
    return matched.slice(0, MAX_INDUSTRIES)
  }

  const qaIndex = QA_STEPS.indexOf(step)
  const isQaStep = qaIndex >= 0

  const requestClose = () => {
    if (step === 'upload' || step === 'done') {
      onClose()
      return
    }
    setCloseConfirmOpen(true)
  }

  // ==================== 分析 ====================
  const applyAnalysis = (result: ResumeAnalysis) => {
    setAnalysis(result)
    setProfileForm(profileToForm(result.profile))
    const aiDirections = Array.isArray(result.directions) ? result.directions : []
    const bands = Array.isArray(result.salaryBands) ? result.salaryBands : []
    setDirections(aiDirections)
    setSalaryBands(bands)
    setSelectedDirection(aiDirections.length > 0 ? 0 : null)
    setCustomDirection(aiDirections.length === 0)
    setCustomTitle('')
    setKeywordsText(aiDirections[0]?.keywords?.join('、') ?? '')
    // 薪资只在问答里确认：默认优先取画像，其次取 AI 推荐的第一档
    const firstBand = bands[0]
    const defaultSalaryMin =
      result.profile?.expectedSalaryMin != null
        ? String(result.profile.expectedSalaryMin)
        : firstBand
          ? String(firstBand.min)
          : ''
    const defaultSalaryMax =
      result.profile?.expectedSalaryMax != null
        ? String(result.profile.expectedSalaryMax)
        : firstBand
          ? String(firstBand.max)
          : ''
    setAnswers({
      ...EMPTY_ANSWERS,
      cities: uniqueList(result.profile?.cities ?? []).slice(0, MAX_CITIES),
      salaryMin: defaultSalaryMin,
      salaryMax: defaultSalaryMax,
      industries: defaultIndustriesFor(aiDirections[0], result.profile),
    })
    setStep('profile')
    setAnalyzeError(null)
  }

  const runAnalyze = async (kind: 'image' | 'text') => {
    if (!privacyAccepted) {
      setPrivacyError(true)
      setFileError('请先阅读并勾选隐私说明，再继续下一步')
      return
    }
    setPrivacyError(false)
    setAnalyzing(true)
    setAnalyzeError(null)
    setStep('analyzing')
    try {
      const result = kind === 'image' && file
        ? await analyzeResume(file, supplement.trim())
        : await analyzeResumeText(resumeText.trim(), supplement.trim())
      applyAnalysis(result)
    } catch (error) {
      setAnalyzeError({ code: errorCode(error), message: errorText(error) })
    } finally {
      setAnalyzing(false)
    }
  }

  const handlePickFile = (next: File | null) => {
    setFileError(null)
    if (!next) {
      setFile(null)
      return
    }
    if (!next.type.startsWith('image/') && !/\.(png|jpe?g|webp)$/i.test(next.name)) {
      setFileError('仅支持 PNG/JPG/WebP 图片简历')
      setFile(null)
      return
    }
    if (next.size > MAX_RESUME_BYTES) {
      setFileError(`图片不能超过 10MB（当前 ${(next.size / 1024 / 1024).toFixed(1)}MB）`)
      setFile(null)
      return
    }
    setFile(next)
  }

  const skipUpload = () => {
    setFile(null)
    setAnalysis(null)
    setProfileForm({ ...EMPTY_PROFILE })
    setDirections([])
    setSalaryBands([])
    setSelectedDirection(null)
    setCustomDirection(true)
    setCustomTitle('')
    setKeywordsText('')
    setAnswers({ ...EMPTY_ANSWERS })
    setStep('profile')
  }

  // ==================== 步骤导航 ====================
  const validateDirection = (): string | null => {
    if (!directionTitle) return '请选择一个 AI 推荐方向，或手动填写方向名称'
    if (splitList(keywordsText).length === 0) return '请至少填写一个搜索关键词'
    return null
  }

  const validateSalary = (): string | null => {
    const min = numberOrNull(answers.salaryMin)
    if (min !== null && min < 0) return '期望月薪下限不能为负数'
    return null
  }

  const generateDraft = async () => {
    if (answers.sendImgResume && !(mode === 'image' && file)) {
      setStepError('已选择“自动发送图片简历”，但还没有上传图片简历。请返回上传步骤添加图片，或关闭自动发送图片简历。')
      return
    }
    setStep('drafting')
    setDraftError(null)
    setDrafting(true)
    try {
      const payload: AiDraftRequest = {
        profile: formToProfile(profileForm, analysis?.profile),
        direction: directionTitle,
        keywords: splitList(keywordsText),
        cities: answers.cities.slice(0, MAX_CITIES),
        industries: answers.industries.slice(0, MAX_INDUSTRIES),
        // 兼容旧后端：cityCode 仍传第一个城市；新后端优先使用 cities
        cityCode: answers.cities[0] ?? null,
        expectedSalaryMin: numberOrNull(answers.salaryMin),
        // 规则只设下限；上限不再提交，避免出现“设了上限但实际不生效”的误解。
        expectedSalaryMax: null,
        exclusions: mergeExclusions(answers.exclusionSelected, answers.exclusions),
        intensity: answers.intensity,
        sendImgResume: answers.sendImgResume,
        templateName: `${directionTitle} 模板`,
      }
      const result = await generateAiTemplateDraft(payload)
      setDraft(result)
      setFinalName(result.name || `${directionTitle} 模板`)
      setStep('preview')
    } catch (error) {
      setDraftError(errorText(error))
    } finally {
      setDrafting(false)
    }
  }

  const goNext = () => {
    setStepError(null)
    if (step === 'upload') return
    if (step === 'analyzing') return
    if (step === 'profile') {
      setStep('direction')
      return
    }
    if (step === 'direction') {
      const error = validateDirection()
      if (error) {
        setStepError(error)
        return
      }
      setStep('qa-city')
      return
    }
    if (isQaStep) {
      if (step === 'qa-city' && answers.cities.length === 0) {
        setStepError('请至少选择 1 个城市（最多 4 个；第 1 个是主搜索城市）')
        return
      }
      if (step === 'qa-salary') {
        const error = validateSalary()
        if (error) {
          setStepError(error)
          return
        }
      }
      if (qaIndex < QA_STEPS.length - 1) {
        setStep(QA_STEPS[qaIndex + 1])
      } else {
        void generateDraft()
      }
    }
  }

  const goBack = () => {
    setStepError(null)
    if (step === 'profile') {
      setStep('upload')
      return
    }
    if (step === 'direction') {
      setStep('profile')
      return
    }
    if (isQaStep) {
      if (qaIndex === 0) setStep('direction')
      else setStep(QA_STEPS[qaIndex - 1])
    }
  }

  // ==================== 保存 ====================
  const persistDraft = async (activate: boolean) => {
    if (!draft || saving) return
    setActivateConfirmOpen(false)
    setSaving(true)
    setSaveError(null)
    let created: TemplateView | null = null
    try {
      created = await createTemplate({
        name: (finalName.trim() || draft.name).slice(0, 60),
        direction: draft.direction ?? directionTitle,
        description: draft.description ?? undefined,
      })
      // 用户在本向导上传的图片简历，保存模板时一并保存为该模板的独立图片简历。
      if (mode === 'image' && file) {
        created = await uploadTemplateResume(created.id, file)
      }
      await updateTemplateConfig(created.id, draft.config)
      if (activate) {
        await activateTemplate(created.id)
      }
      setCreatedTemplate(created)
      setSavedActivated(activate)
      setStep('done')
      onSaved(created, activate)
    } catch (error) {
      if (created) {
        setSaveError(
          `模板「${created.name}」已创建（未激活），但配置保存失败：${errorText(error)}。可到配置空间模板列表重新编辑保存。`
        )
        onSaved(created, false)
      } else {
        setSaveError(`保存失败：${errorText(error)}`)
      }
    } finally {
      setSaving(false)
    }
  }

  const renderStepBadge = () => {
    const labels = ['上传', '画像', '方向', '问答', '草稿']
    let active = 0
    if (step === 'upload' || step === 'analyzing') active = 0
    else if (step === 'profile') active = 1
    else if (step === 'direction') active = 2
    else if (isQaStep || step === 'drafting' || step === 'preview' || step === 'done') active = 3
    if (step === 'preview' || step === 'done') active = 4
    return (
      <ol className="flex flex-wrap items-center gap-1.5 text-[11px] text-[var(--v3-muted)]">
        {labels.map((label, index) => (
          <li
            key={label}
            className="inline-flex items-center gap-1 rounded-full border px-2 py-0.5"
            style={
              index === active
                ? { borderColor: 'var(--v3-accent)', color: 'var(--v3-accent)', backgroundColor: 'var(--accent-soft)' }
                : index < active
                  ? { borderColor: 'var(--v3-ok)', color: 'var(--v3-ok)' }
                  : { borderColor: 'var(--v3-line)' }
            }
          >
            {index < active ? <Check aria-hidden="true" className="size-3" /> : <span>{index + 1}</span>}
            {label}
          </li>
        ))}
      </ol>
    )
  }

  if (typeof document === 'undefined') {
    return null
  }

  return (
    <DialogShell
      open
      onClose={requestClose}
      bare
      ariaLabel="AI 帮我建模板"
      width={900}
      dismissable={false}
      closeOnScrim={false}
      panelClassName="flex min-h-0 max-h-[76vh] flex-col overflow-hidden"
    >
      {/* Header */}
      <header
        className="flex shrink-0 items-start justify-between gap-3 border-b px-6 py-4"
        style={{ borderColor: 'var(--v3-line)' }}
      >
        <div className="min-w-0">
          <p className="flex items-center gap-1.5 text-[11px] font-medium tracking-[0.08em] text-[var(--v3-muted)]">
            <Sparkles aria-hidden="true" className="size-3.5" />
            AI 帮我建模板 · 简历只用于本次生成
          </p>
          <h2 className="mt-1 text-lg font-semibold">用简历生成一个方向模板</h2>
          <div className="mt-2">{renderStepBadge()}</div>
        </div>
        <button
          type="button"
          aria-label="关闭向导"
          onClick={requestClose}
          className="inline-flex size-9 shrink-0 items-center justify-center rounded-full border text-[var(--v3-muted)]"
          style={{ borderColor: 'var(--v3-line)' }}
        >
          <X aria-hidden="true" className="size-4" />
        </button>
      </header>

      {/* Body */}
      <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
          {step === 'upload' ? (
            <UploadStep
              mode={mode}
              setMode={setMode}
              privacyAccepted={privacyAccepted}
              setPrivacyAccepted={(value) => {
                setPrivacyAccepted(value)
                if (value) {
                  setPrivacyError(false)
                  setFileError(null)
                }
              }}
              privacyError={privacyError}
              file={file}
              fileError={fileError}
              onPickFile={handlePickFile}
              accept={RESUME_ACCEPT}
              fileInputRef={fileInputRef}
              resumeText={resumeText}
              setResumeText={setResumeText}
              supplement={supplement}
              setSupplement={setSupplement}
              onAnalyze={runAnalyze}
              onSkip={skipUpload}
            />
          ) : null}

          {step === 'analyzing' ? (
            <div className="py-8">
              {analyzeError ? (
                <div className="space-y-4">
                  <div
                    className="rounded-[12px] px-4 py-3 text-sm"
                    style={{ backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }}
                  >
                    <p className="font-medium">{analyzeError.message}</p>
                    <p className="mt-1">{analyzeHint(analyzeError.code)}</p>
                    {analyzeError.code ? (
                      <p className="mt-1 text-xs opacity-80">错误码：{analyzeError.code}</p>
                    ) : null}
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {analyzeError.code === 'AI_CONFIG_MISSING' ? (
                      <button
                        type="button"
                        onClick={onOpenModelService}
                        className="inline-flex h-9 items-center rounded-[10px] px-3 text-sm font-medium"
                        style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
                      >
                        去模型服务配置
                      </button>
                    ) : null}
                    <button
                      type="button"
                      onClick={() => {
                        setMode('text')
                        setAnalyzeError(null)
                        setStep('upload')
                      }}
                      className="inline-flex h-9 items-center rounded-[10px] border px-3 text-sm"
                      style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
                    >
                      粘贴简历文本
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setAnalyzeError(null)
                        void runAnalyze(mode)
                      }}
                      className="inline-flex h-9 items-center rounded-[10px] border px-3 text-sm"
                      style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
                    >
                      重试
                    </button>
                    <button type="button" onClick={() => setStep('upload')} className="text-sm text-[var(--v3-muted)] underline">
                      返回
                    </button>
                  </div>
                </div>
              ) : (
                <div className="flex flex-col items-center gap-3 py-12 text-sm text-[var(--v3-muted)]">
                  <Loader2 aria-hidden="true" className="size-6 animate-spin text-[var(--v3-accent)]" />
                  <p>{analyzing ? '正在读取简历并生成画像…' : '准备分析…'}</p>
                  <p className="text-xs">只做分析，不会创建模板或开始投递</p>
                </div>
              )}
            </div>
          ) : null}

          {step === 'profile' ? (
            <ProfileStep
              form={profileForm}
              setForm={setProfileForm}
              analysis={analysis}
              salaryBands={salaryBands}
            />
          ) : null}

          {step === 'direction' ? (
            <DirectionStep
              directions={directions}
              selectedDirection={selectedDirection}
              onSelect={(index) => {
                setSelectedDirection(index)
                setCustomDirection(false)
                setKeywordsText(directions[index]?.keywords?.join('、') ?? '')
                setAnswers((prev) => ({
                  ...prev,
                  industries: defaultIndustriesFor(directions[index], analysis?.profile),
                }))
              }}
              customDirection={customDirection}
              setCustomDirection={(value) => {
                setCustomDirection(value)
                if (value) {
                  setAnswers((prev) => ({
                    ...prev,
                    industries: defaultIndustriesFor(null, analysis?.profile),
                  }))
                } else if (selectedDirection !== null) {
                  setAnswers((prev) => ({
                    ...prev,
                    industries: defaultIndustriesFor(directions[selectedDirection], analysis?.profile),
                  }))
                }
              }}
              customTitle={customTitle}
              setCustomTitle={setCustomTitle}
              keywordsText={keywordsText}
              setKeywordsText={setKeywordsText}
              industryOptions={industryOptions}
              selectedIndustries={answers.industries}
              setSelectedIndustries={(next) => setAnswers((prev) => ({ ...prev, industries: next.slice(0, MAX_INDUSTRIES) }))}
              stepError={stepError}
            />
          ) : null}

          {isQaStep ? (
            <QaStep
              step={step as (typeof QA_STEPS)[number]}
              index={qaIndex}
              answers={answers}
              setAnswers={setAnswers}
              cityOptions={cityOptions}
              salaryBands={salaryBands}
              stepError={stepError}
            />
          ) : null}

          {step === 'drafting' ? (
            <div className="py-10">
              {draftError ? (
                <div className="space-y-3">
                  <div
                    className="rounded-[12px] px-4 py-3 text-sm"
                    style={{ backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }}
                  >
                    生成草稿失败：{draftError}
                  </div>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => setStep('qa-image')}
                      className="inline-flex h-9 items-center rounded-[10px] border px-3 text-sm"
                      style={{ borderColor: 'var(--v3-line)' }}
                    >
                      返回修改
                    </button>
                    <button
                      type="button"
                      onClick={() => void generateDraft()}
                      className="inline-flex h-9 items-center rounded-[10px] px-3 text-sm font-medium"
                      style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
                    >
                      重试
                    </button>
                  </div>
                </div>
              ) : (
                <div className="flex flex-col items-center gap-3 py-12 text-sm text-[var(--v3-muted)]">
                  <Loader2 aria-hidden="true" className="size-6 animate-spin text-[var(--v3-accent)]" />
                  <p>{drafting ? '正在生成模板草稿…' : '准备生成…'}</p>
                  <p className="text-xs">生成草稿不会创建模板，也不会开始投递</p>
                </div>
              )}
            </div>
          ) : null}

          {step === 'preview' && draft ? (
            <PreviewStep
              draft={draft}
              finalName={finalName}
              setFinalName={setFinalName}
              directionTitle={directionTitle}
              keywords={splitList(keywordsText)}
              answers={answers}
              profile={analysis?.profile ?? null}
              saveError={saveError}
              saving={saving}
            />
          ) : null}

          {step === 'done' && createdTemplate ? (
            <div className="py-8 text-center">
              <div
                className="mx-auto mb-3 inline-flex size-12 items-center justify-center rounded-full"
                style={{ backgroundColor: 'var(--ok-soft)', color: 'var(--v3-ok)' }}
              >
                <Check aria-hidden="true" className="size-6" />
              </div>
              <h3 className="text-lg font-semibold">模板已保存</h3>
              <p className="mt-1 text-sm text-[var(--v3-muted)]">
                「{createdTemplate.name}」{savedActivated ? '已激活：下一轮任务生效，不会立即开始投递。' : '已保存为草稿（未激活）：可在配置空间随时编辑或激活。'}
              </p>
              <button
                type="button"
                onClick={onClose}
                className="mt-4 inline-flex h-10 items-center rounded-[10px] px-4 text-sm font-medium"
                style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
              >
                完成
              </button>
            </div>
          ) : null}
        </div>

        {/* Footer */}
        {step !== 'done' && step !== 'upload' && step !== 'analyzing' && step !== 'drafting' ? (
          <footer
            className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t px-6 py-3"
            style={{ borderColor: 'var(--v3-line)' }}
          >
            <button
              type="button"
              onClick={goBack}
              disabled={saving}
              className="inline-flex h-10 items-center gap-1 rounded-[10px] border px-3 text-sm disabled:opacity-50"
              style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
            >
              <ArrowLeft aria-hidden="true" className="size-4" />
              上一步
            </button>
            <span className="text-xs text-[var(--v3-muted)]">
              {isQaStep ? `问题 ${qaIndex + 1}/${QA_STEPS.length}` : ''}
            </span>
            {step === 'preview' ? (
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  disabled={saving}
                  onClick={() => void persistDraft(false)}
                  className="inline-flex h-10 items-center gap-1 rounded-[10px] border px-4 text-sm font-medium disabled:opacity-50"
                  style={{ borderColor: 'var(--v3-accent)', color: 'var(--v3-accent)' }}
                >
                  {saving ? '保存中…' : '保存为草稿'}
                </button>
                <button
                  type="button"
                  disabled={saving}
                  onClick={() => setActivateConfirmOpen(true)}
                  className="inline-flex h-10 items-center gap-1 rounded-[10px] px-4 text-sm font-medium disabled:opacity-50"
                  style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
                >
                  保存并激活
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={goNext}
                disabled={saving}
                className="inline-flex h-10 items-center gap-1 rounded-[10px] px-4 text-sm font-medium disabled:opacity-50"
                style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
              >
                {step === 'qa-image' ? '生成草稿' : '下一步'}
                <ArrowRight aria-hidden="true" className="size-4" />
              </button>
            )}
          </footer>
        ) : null}

      <ConfirmDialog
        open={activateConfirmOpen}
        tone="warning"
        title="保存并激活该模板？"
        description="激活只表示下一轮任务按该模板运行：不会立即发送、不会自动开始投递；真实发送仍由后端规则/AI/身份/预算与文字+图片确认链路决定。"
        confirmText="保存并激活"
        busy={saving}
        onCancel={() => setActivateConfirmOpen(false)}
        onConfirm={() => void persistDraft(true)}
      />

      <ConfirmDialog
        open={closeConfirmOpen}
        tone="warning"
        title="关闭向导？"
        description="尚未保存的画像/草稿会丢失；未点击保存前不会创建任何模板。"
        confirmText="关闭"
        onCancel={() => setCloseConfirmOpen(false)}
        onConfirm={() => {
          setCloseConfirmOpen(false)
          onClose()
        }}
      />
    </DialogShell>
  )
}

// ==================== 步骤子组件 ====================

function UploadStep({
  mode,
  setMode,
  privacyAccepted,
  setPrivacyAccepted,
  privacyError,
  file,
  fileError,
  onPickFile,
  accept,
  fileInputRef,
  resumeText,
  setResumeText,
  supplement,
  setSupplement,
  onAnalyze,
  onSkip,
}: {
  mode: 'image' | 'text'
  setMode: (mode: 'image' | 'text') => void
  privacyAccepted: boolean
  setPrivacyAccepted: (value: boolean) => void
  privacyError: boolean
  file: File | null
  fileError: string | null
  onPickFile: (file: File | null) => void
  accept: string
  fileInputRef: React.RefObject<HTMLInputElement | null>
  resumeText: string
  setResumeText: (value: string) => void
  supplement: string
  setSupplement: (value: string) => void
  onAnalyze: (kind: 'image' | 'text') => Promise<void>
  onSkip: () => void
}) {
  return (
    <div className="space-y-4">
      <div
        className="rounded-[12px] px-4 py-3 text-sm leading-6 transition-colors"
        style={{
          backgroundColor: privacyError ? 'var(--bad-soft)' : 'var(--surface2)',
          border: privacyError ? '2px solid var(--v3-bad)' : '2px solid transparent',
          color: 'var(--v3-muted)',
        }}
      >
        <p className="flex items-center gap-1.5 font-medium text-[var(--text)]">
          <ShieldCheck aria-hidden="true" className="size-4 text-[var(--v3-ok)]" />
          隐私说明
        </p>
        <p className="mt-1">
          简历图片/文本仅用于本次生成模板画像。文本和补充说明不落盘；如果你上传了图片并保存模板，
          图片会作为该模板的独立图片简历保存在本机，供后续经你确认的投递使用。分析由本机配置的 AI 服务完成，
          请勿上传与求职无关的敏感证件材料。
        </p>
        <label
          className="mt-2 flex items-start gap-2 rounded-[8px] px-1 py-1 text-sm text-[var(--text)]"
          style={privacyError ? { backgroundColor: 'var(--bad-soft)' } : undefined}
        >
          <input
            type="checkbox"
            checked={privacyAccepted}
            aria-invalid={privacyError}
            onChange={(event) => setPrivacyAccepted(event.target.checked)}
            className="mt-1"
            style={privacyError ? { outline: '2px solid var(--v3-bad)', outlineOffset: '2px' } : undefined}
          />
          我已阅读并同意上述隐私说明（必选）
        </label>
        {privacyError ? (
          <p role="alert" className="mt-2 text-sm font-medium" style={{ color: 'var(--v3-bad)' }}>
            请先勾选上方隐私说明，再继续下一步。
          </p>
        ) : null}
      </div>

      <div className="flex items-center gap-1 rounded-full border p-1" style={{ borderColor: 'var(--v3-line)' }}>
        {[
          { key: 'image' as const, label: '上传简历图片' },
          { key: 'text' as const, label: '粘贴简历文本' },
        ].map((entry) => {
          const active = mode === entry.key
          return (
            <button
              key={entry.key}
              type="button"
              onClick={() => setMode(entry.key)}
              className="rounded-full px-3 py-1.5 text-sm font-medium"
              style={active ? { backgroundColor: 'var(--accent-soft)', color: 'var(--v3-accent)' } : { color: 'var(--v3-muted)' }}
            >
              {entry.label}
            </button>
          )
        })}
      </div>

      <label className="block space-y-1 text-sm">
        <span className="flex flex-wrap items-center justify-between gap-2 text-[var(--v3-muted)]">
          <span className="font-medium text-[var(--text)]">补充说明（可选）</span>
          <span>会和简历一起交给 AI，仅用于本次分析，不保存</span>
        </span>
        <textarea
          rows={3}
          maxLength={1000}
          aria-label="补充说明"
          value={supplement}
          onChange={(event) => setSupplement(event.target.value)}
          placeholder="例如：我的核心优势、技能、工作年限、代表项目或想强调的经验；简历里没写清楚的部分可以在这里补充…"
          className="w-full rounded-[10px] border bg-[var(--surface2)] px-3 py-2 text-sm leading-6 outline-none"
          style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
        />
        <span className="block text-right text-xs tabular-nums text-[var(--v3-muted)]">
          {supplement.trim().length}/1000
        </span>
      </label>

      {mode === 'image' ? (
        <div className="space-y-3">
          <input
            ref={fileInputRef}
            type="file"
            accept={accept}
            className="hidden"
            onChange={(event) => {
              const next = event.target.files?.[0] ?? null
              event.target.value = ''
              onPickFile(next)
            }}
          />
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            className="flex w-full flex-col items-center gap-2 rounded-[14px] border border-dashed px-4 py-8 text-sm text-[var(--v3-muted)]"
            style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }}
          >
            <FileImage aria-hidden="true" className="size-8" />
            <span className="font-medium text-[var(--text)]">{file ? file.name : '选择简历图片（PNG/JPG/WebP，≤10MB）'}</span>
            <span>图片或截图均可；识别失败时可随时切到「粘贴简历文本」</span>
          </button>
          {fileError ? <p className="text-sm" style={{ color: 'var(--v3-bad)' }}>{fileError}</p> : null}
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              disabled={!file}
              onClick={() => void onAnalyze('image')}
              className="inline-flex h-10 items-center gap-1 rounded-[10px] px-4 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-45"
              style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
            >
              <Sparkles aria-hidden="true" className="size-4" />
              开始分析
            </button>
            <button type="button" onClick={onSkip} className="text-sm text-[var(--v3-muted)] underline">
              跳过上传，手动填写画像
            </button>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          <label className="block space-y-1 text-sm">
            <span className="block text-[var(--v3-muted)]">粘贴简历文本（≥20 字；不会写入浏览器存储）</span>
            <textarea
              rows={10}
              aria-label="简历文本"
              value={resumeText}
              onChange={(event) => setResumeText(event.target.value)}
              placeholder="例如：教育背景、工作经历、项目经验、技能、期望城市与薪资…"
              className="w-full rounded-[10px] border bg-[var(--surface2)] px-3 py-2 text-sm leading-6 outline-none"
              style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
            />
            <span className="block text-right text-xs tabular-nums text-[var(--v3-muted)]">
              {resumeText.trim().length} 字
            </span>
          </label>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              disabled={resumeText.trim().length < 20}
              onClick={() => void onAnalyze('text')}
              className="inline-flex h-10 items-center gap-1 rounded-[10px] px-4 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-45"
              style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
            >
              <Sparkles aria-hidden="true" className="size-4" />
              分析文本
            </button>
            <button type="button" onClick={onSkip} className="text-sm text-[var(--v3-muted)] underline">
              跳过，手动填写画像
            </button>
          </div>
        </div>
      )}

      <p className="text-xs leading-5 text-[var(--v3-muted)]">
        分析完成后可以逐项修改画像、选择方向并回答 5 个问题；保存前不会创建模板，也不会开始投递。
      </p>
    </div>
  )
}

function ProfileStep({
  form,
  setForm,
  analysis,
  salaryBands,
}: {
  form: ProfileForm
  setForm: (form: ProfileForm) => void
  analysis: ResumeAnalysis | null
  salaryBands: ResumeAnalysis['salaryBands']
}) {
  const fields: { key: keyof ProfileForm; label: string; placeholder: string }[] = [
    { key: 'roles', label: '目标岗位 / 角色（逗号分隔）', placeholder: '如 产品经理、AI 产品经理' },
    { key: 'skills', label: '核心技能（逗号分隔）', placeholder: '如 SQL、数据分析、用户研究' },
    { key: 'cities', label: '期望城市（逗号分隔）', placeholder: '如 上海、杭州' },
    { key: 'industries', label: '目标行业（逗号分隔）', placeholder: '如 互联网、人工智能' },
    { key: 'strengths', label: '个人优势（逗号分隔）', placeholder: '如 B 端产品经验、跨团队协作' },
  ]
  const profile = analysis?.profile ?? null
  const recommendedSalary =
    profile?.expectedSalaryMin != null || profile?.expectedSalaryMax != null
      ? `${profile?.expectedSalaryMin ?? '—'}–${profile?.expectedSalaryMax ?? '—'}K`
      : null

  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-[15px] font-semibold">确认识别出的画像（可编辑）</h3>
        <p className="mt-1 text-sm text-[var(--v3-muted)]">
          AI 只做建议：请逐项核对；姓名/联系方式等敏感信息不会被使用，也不会写入模板。
        </p>
      </div>

      {/* 只读：AI 识别的学历/经验/专业/毕业年份 */}
      <div className="grid gap-2 rounded-[12px] border px-3 py-2 text-sm sm:grid-cols-2" style={{ borderColor: 'var(--v3-line)' }}>
        <div>
          <p className="text-xs text-[var(--v3-muted)]">专业</p>
          <p className="mt-0.5">{profile?.major || '未识别'}</p>
        </div>
        <div>
          <p className="text-xs text-[var(--v3-muted)]">毕业年份</p>
          <p className="mt-0.5">{profile?.graduationYear != null ? `${profile.graduationYear} 年` : '未识别'}</p>
        </div>
        <div>
          <p className="text-xs text-[var(--v3-muted)]">AI 判断最高学历（只读）</p>
          <p className="mt-0.5">{profile?.highestDegree || '未识别'}</p>
        </div>
        <div>
          <p className="text-xs text-[var(--v3-muted)]">AI 判断工作经验（只读）</p>
          <p className="mt-0.5">
            {profile?.yearsExperience != null
              ? `${profile.yearsExperience} 年（${experienceLabel(profile.yearsExperience)}）`
              : '未识别'}
          </p>
        </div>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        {fields.map((field) => (
          <label key={field.key} className="space-y-1 text-sm">
            <span className="block text-xs text-[var(--v3-muted)]">{field.label}</span>
            <input
              type="text"
              value={form[field.key]}
              onChange={(event) => setForm({ ...form, [field.key]: event.target.value })}
              placeholder={field.placeholder}
              className="h-10 w-full rounded-[10px] border bg-[var(--surface2)] px-3 text-sm outline-none"
              style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
            />
          </label>
        ))}
      </div>

      <div className="rounded-[12px] border px-3 py-2 text-sm" style={{ borderColor: 'var(--v3-line)' }}>
        <p className="text-xs text-[var(--v3-muted)]">AI 建议薪资（只读，下一步问答中确认）</p>
        <p className="mt-0.5">
          {recommendedSalary ?? (salaryBands && salaryBands.length > 0 ? '见下方 AI 参考区间' : '未识别')}
        </p>
        {salaryBands && salaryBands.length > 0 ? (
          <ul className="mt-1 space-y-0.5 text-xs text-[var(--v3-muted)]">
            {salaryBands.map((band, index) => (
              <li key={`${band.label}-${index}`}>
                {band.label}：{band.min}–{band.max}K{band.basis ? ` · ${band.basis}` : ''}
              </li>
            ))}
          </ul>
        ) : null}
        <p className="mt-1 text-xs text-[var(--v3-muted)]">学历/经验由后端按画像自动带入草稿，问答不再重复询问。</p>
      </div>

      {analysis?.profile?.warnings?.length || analysis?.warnings?.length ? (
        <div className="rounded-[12px] px-3 py-2 text-sm" style={{ backgroundColor: 'var(--warn-soft)', color: 'var(--v3-warn)' }}>
          <p className="font-medium">识别提示</p>
          <ul className="mt-1 list-inside list-disc text-xs">
            {[...(analysis?.profile?.warnings ?? []), ...(analysis?.warnings ?? [])].map((warning, index) => (
              <li key={`${warning}-${index}`}>{warning}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {analysis?.visionUsed === false ? (
        <p className="text-xs text-[var(--v3-muted)]">本次使用文本分析（未使用视觉模型）。</p>
      ) : null}
    </div>
  )
}

function DirectionStep({
  directions,
  selectedDirection,
  onSelect,
  customDirection,
  setCustomDirection,
  customTitle,
  setCustomTitle,
  keywordsText,
  setKeywordsText,
  industryOptions,
  selectedIndustries,
  setSelectedIndustries,
  stepError,
}: {
  directions: ResumeDirection[]
  selectedDirection: number | null
  onSelect: (index: number) => void
  customDirection: boolean
  setCustomDirection: (value: boolean) => void
  customTitle: string
  setCustomTitle: (value: string) => void
  keywordsText: string
  setKeywordsText: (value: string) => void
  industryOptions: BossOption[]
  selectedIndustries: string[]
  setSelectedIndustries: (next: string[]) => void
  stepError: string | null
}) {
  const toggleIndustry = (name: string) => {
    if (selectedIndustries.includes(name)) {
      setSelectedIndustries(selectedIndustries.filter((item) => item !== name))
      return
    }
    if (selectedIndustries.length >= MAX_INDUSTRIES) return
    setSelectedIndustries([...selectedIndustries, name])
  }

  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-[15px] font-semibold">选择一个投递方向</h3>
        <p className="mt-1 text-sm text-[var(--v3-muted)]">
          AI 推荐方向只是建议；选定后会用它生成搜索关键词与筛选规则，保存前都可以改。
        </p>
      </div>

      {!customDirection && directions.length > 0 ? (
        <div className="space-y-2">
          {directions.map((direction, index) => {
            const active = selectedDirection === index
            return (
              <button
                key={`${direction.title}-${index}`}
                type="button"
                onClick={() => onSelect(index)}
                className="w-full rounded-[14px] border px-4 py-3 text-left transition-colors"
                style={
                  active
                    ? { borderColor: 'var(--v3-accent)', backgroundColor: 'var(--accent-soft)' }
                    : { borderColor: 'var(--v3-line)' }
                }
              >
                <span className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-sm font-semibold">{direction.title}</span>
                  <span className="text-xs" style={{ color: 'var(--v3-accent)' }}>
                    {fitText(direction.fit)}
                  </span>
                </span>
                {direction.reason ? <span className="mt-1 block text-xs text-[var(--v3-muted)]">{direction.reason}</span> : null}
                {direction.keywords?.length ? (
                  <span className="mt-1 block text-xs text-[var(--v3-muted)]">
                    关键词：{direction.keywords.join('、')}
                  </span>
                ) : null}
                {direction.risks?.length ? (
                  <span className="mt-1 block text-xs" style={{ color: 'var(--v3-warn)' }}>
                    风险：{direction.risks.join('；')}
                  </span>
                ) : null}
              </button>
            )
          })}
        </div>
      ) : null}

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={customDirection}
          onChange={(event) => {
            setCustomDirection(event.target.checked)
            if (!event.target.checked && directions.length > 0 && selectedDirection === null) onSelect(0)
          }}
        />
        自定义方向（不使用 AI 推荐）
      </label>

      {customDirection ? (
        <label className="block space-y-1 text-sm">
          <span className="block text-xs text-[var(--v3-muted)]">方向名称</span>
          <input
            value={customTitle}
            onChange={(event) => setCustomTitle(event.target.value)}
            placeholder="例如：AI 产品经理"
            className="h-10 w-full rounded-[10px] border bg-[var(--surface2)] px-3 text-sm outline-none"
            style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
          />
        </label>
      ) : null}

      <label className="block space-y-1 text-sm">
        <span className="block text-xs text-[var(--v3-muted)]">搜索关键词（逗号/顿号分隔；会写入模板搜索条件）</span>
        <textarea
          rows={2}
          value={keywordsText}
          onChange={(event) => setKeywordsText(event.target.value)}
          placeholder="例如：AI产品经理、大模型产品、产品经理"
          className="w-full rounded-[10px] border bg-[var(--surface2)] px-3 py-2 text-sm outline-none"
          style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
        />
      </label>

      {/* 行业偏好：默认按所选方向/画像匹配行业选项，最多 6 个 */}
      <div className="space-y-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className="text-xs text-[var(--v3-muted)]">
            行业偏好（默认勾选方向/画像推荐，最多 {MAX_INDUSTRIES} 个）
          </span>
          <span className="text-xs tabular-nums text-[var(--v3-muted)]">
            已选 {selectedIndustries.length}/{MAX_INDUSTRIES}
          </span>
        </div>
        {selectedIndustries.length > 0 ? (
          <div
            className="flex flex-wrap items-center gap-1.5 rounded-[12px] border px-2.5 py-2"
            style={{ borderColor: 'var(--v3-accent)', backgroundColor: 'var(--accent-soft)' }}
            aria-label="已选行业"
          >
            <span className="mr-0.5 text-xs font-medium" style={{ color: 'var(--v3-accent)' }}>
              已选行业：
            </span>
            {selectedIndustries.map((name) => (
              <span
                key={name}
                className="inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs"
                style={{ borderColor: 'var(--v3-accent)', color: 'var(--v3-accent)', backgroundColor: 'var(--surface)' }}
              >
                {name}
                <button
                  type="button"
                  aria-label={`取消已选行业 ${name}`}
                  title={`取消选择 ${name}`}
                  onClick={() => toggleIndustry(name)}
                  className="inline-flex size-4 items-center justify-center rounded-full transition-opacity hover:opacity-70"
                  style={{ color: 'var(--v3-accent)' }}
                >
                  <X aria-hidden="true" className="size-3" />
                </button>
              </span>
            ))}
          </div>
        ) : (
          <p className="text-xs text-[var(--v3-muted)]">还没有选择行业；可从下方选项点击添加。</p>
        )}
        {industryOptions.length > 0 ? (
          <div
            className="flex max-h-44 flex-wrap gap-1.5 overflow-y-auto rounded-[12px] border p-2"
            style={{ borderColor: 'var(--v3-line)' }}
            role="group"
            aria-label="行业偏好"
          >
            {industryOptions.map((option) => {
              const active = selectedIndustries.includes(option.name)
              const disabled = !active && selectedIndustries.length >= MAX_INDUSTRIES
              return (
                <button
                  key={option.code || option.name}
                  type="button"
                  aria-pressed={active}
                  disabled={disabled}
                  onClick={() => toggleIndustry(option.name)}
                  className="inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs disabled:cursor-not-allowed disabled:opacity-40"
                  style={
                    active
                      ? { borderColor: 'var(--v3-accent)', backgroundColor: 'var(--accent-soft)', color: 'var(--v3-accent)' }
                      : { borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }
                  }
                >
                  {active ? <Check aria-hidden="true" className="size-3" /> : null}
                  {option.name}
                </button>
              )
            })}
          </div>
        ) : (
          <input
            value={selectedIndustries.join('、')}
            onChange={(event) => setSelectedIndustries(uniqueList(splitList(event.target.value)).slice(0, MAX_INDUSTRIES))}
            placeholder="手动输入行业（顿号分隔，最多 6 个）"
            className="h-10 w-full rounded-[10px] border bg-[var(--surface2)] px-3 text-sm outline-none"
            style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
          />
        )}
        <p className="text-xs text-[var(--v3-muted)]">
          行业会写入模板搜索条件（config.industry）；留空表示不限行业。
        </p>
      </div>

      {stepError ? <p className="text-sm" style={{ color: 'var(--v3-bad)' }}>{stepError}</p> : null}
    </div>
  )
}

function QaStep({
  step,
  index,
  answers,
  setAnswers,
  cityOptions,
  salaryBands,
  stepError,
}: {
  step: (typeof QA_STEPS)[number]
  index: number
  answers: Answers
  setAnswers: (answers: Answers) => void
  cityOptions: BossOption[]
  salaryBands: ResumeAnalysis['salaryBands']
  stepError: string | null
}) {
  const set = (patch: Partial<Answers>) => setAnswers({ ...answers, ...patch })
  const [citySearch, setCitySearch] = useState('')
  const [cityError, setCityError] = useState<string | null>(null)

  const toggleCity = (name: string) => {
    setCityError(null)
    if (answers.cities.includes(name)) {
      set({ cities: answers.cities.filter((item) => item !== name) })
      return
    }
    if (answers.cities.length >= MAX_CITIES) {
      setCityError(`最多选择 ${MAX_CITIES} 个城市；可先取消一个再添加`)
      return
    }
    set({ cities: [...answers.cities, name] })
  }

  const filteredCities = cityOptions.filter((option) =>
    citySearch.trim() ? option.name.includes(citySearch.trim()) : true
  )
  const mergedExclusions = mergeExclusions(answers.exclusionSelected, answers.exclusions)

  return (
    <div className="space-y-4">
      <div>
        <p className="text-xs text-[var(--v3-muted)]">
          逐条问答 · {index + 1}/{QA_STEPS.length}
        </p>
        <h3 className="mt-1 text-[17px] font-semibold">{QA_TITLES[step]}</h3>
        <p className="mt-1 text-sm text-[var(--v3-muted)]">
          这些答案只用于生成模板草稿；保存的也是模板配置，不会立即执行任何投递。
        </p>
      </div>

      {step === 'qa-city' ? (
        <div className="space-y-2">
          {answers.cities.length > 0 ? (
            <div className="flex flex-wrap gap-1.5">
              {answers.cities.map((city, index) => (
                <span
                  key={city}
                  className="inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs"
                  style={{
                    borderColor: index === 0 ? 'var(--v3-accent)' : 'var(--v3-line)',
                    backgroundColor: index === 0 ? 'var(--accent-soft)' : 'transparent',
                    color: index === 0 ? 'var(--v3-accent)' : 'var(--text)',
                  }}
                >
                  {city}
                  {index === 0 ? <strong className="font-semibold">（主搜索城市）</strong> : null}
                  <button
                    type="button"
                    aria-label={`移除 ${city}`}
                    onClick={() => toggleCity(city)}
                    className="ml-0.5 text-[var(--v3-muted)] hover:text-[var(--v3-bad)]"
                  >
                    ×
                  </button>
                </span>
              ))}
              <span className="self-center text-xs tabular-nums text-[var(--v3-muted)]">
                已选 {answers.cities.length}/{MAX_CITIES}
              </span>
            </div>
          ) : (
            <p className="text-xs text-[var(--v3-muted)]">尚未选择城市（至少选 1 个才能继续）。</p>
          )}

          {cityOptions.length > 0 ? (
            <div className="space-y-2">
              <input
                value={citySearch}
                onChange={(event) => setCitySearch(event.target.value)}
                placeholder="搜索城市，如：上海 / 杭州"
                aria-label="搜索城市"
                className="h-9 w-full rounded-[10px] border bg-[var(--surface2)] px-3 text-sm outline-none"
                style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
              />
              <div
                className="flex max-h-48 flex-wrap gap-1.5 overflow-y-auto rounded-[12px] border p-2"
                style={{ borderColor: 'var(--v3-line)' }}
                role="group"
                aria-label="城市候选"
              >
                {filteredCities.length === 0 ? (
                  <p className="px-1 py-2 text-xs text-[var(--v3-muted)]">没有匹配的城市</p>
                ) : (
                  filteredCities.map((option) => {
                    const active = answers.cities.includes(option.name)
                    const disabled = !active && answers.cities.length >= MAX_CITIES
                    return (
                      <button
                        key={option.code || option.name}
                        type="button"
                        aria-pressed={active}
                        disabled={disabled}
                        onClick={() => toggleCity(option.name)}
                        className="inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs disabled:cursor-not-allowed disabled:opacity-40"
                        style={
                          active
                            ? { borderColor: 'var(--v3-accent)', backgroundColor: 'var(--accent-soft)', color: 'var(--v3-accent)' }
                            : { borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }
                        }
                      >
                        {active ? <Check aria-hidden="true" className="size-3" /> : null}
                        {option.name}
                      </button>
                    )
                  })
                )}
              </div>
            </div>
          ) : (
            <input
              value={answers.cities.join('、')}
              onChange={(event) => set({ cities: uniqueList(splitList(event.target.value)).slice(0, MAX_CITIES) })}
              placeholder="城市选项不可用：手动输入城市（顿号分隔，最多 4 个）"
              className="h-10 w-full rounded-[10px] border bg-[var(--surface2)] px-3 text-sm outline-none"
              style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
            />
          )}

          {cityError ? <p className="text-sm" style={{ color: 'var(--v3-bad)' }}>{cityError}</p> : null}
          <p className="text-xs text-[var(--v3-muted)]">
            第 1 个城市作为模板主搜索城市（config.cityCode），其余进入规则城市筛选；城市只影响搜索条件，不会改变预算或安全确认。
          </p>
        </div>
      ) : null}

      {step === 'qa-salary' ? (
        <div className="space-y-2">
          <label className="block space-y-1 text-sm">
            <span className="block text-xs text-[var(--v3-muted)]">月薪下限（K，只设下限）</span>
            <input
              type="number"
              value={answers.salaryMin}
              onChange={(event) => set({ salaryMin: event.target.value })}
              className="h-10 w-full rounded-[10px] border bg-[var(--surface2)] px-3 text-sm outline-none"
              style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
            />
          </label>
          {salaryBands && salaryBands.length > 0 ? (
            <div className="space-y-1">
              <p className="text-xs text-[var(--v3-muted)]">AI 参考区间（点击可取参考下限，仍可修改）</p>
              <div className="flex flex-wrap gap-1.5">
                {salaryBands.map((band, index) => (
                  <button
                    key={`${band.label}-${index}`}
                    type="button"
                    onClick={() => set({ salaryMin: String(band.min) })}
                    className="rounded-full border px-2.5 py-1 text-xs"
                    style={{ borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }}
                  >
                    {band.label}：{band.min}–{band.max}K
                  </button>
                ))}
              </div>
            </div>
          ) : null}
          <p className="text-xs text-[var(--v3-muted)]">只设下限，不设上限；岗位薪资上限达到下限即可通过。</p>
        </div>
      ) : null}

      {step === 'qa-exclusions' ? (
        <div className="space-y-3">
          <div className="space-y-1">
            <p className="text-xs text-[var(--v3-muted)]">常用排除项（默认已勾选，可取消）</p>
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="常用排除项">
              {DEFAULT_EXCLUSIONS.map((name) => {
                const active = answers.exclusionSelected.includes(name)
                return (
                  <button
                    key={name}
                    type="button"
                    aria-pressed={active}
                    onClick={() =>
                      set({
                        exclusionSelected: active
                          ? answers.exclusionSelected.filter((item) => item !== name)
                          : [...answers.exclusionSelected, name],
                      })
                    }
                    className="inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs"
                    style={
                      active
                        ? { borderColor: 'var(--v3-accent)', backgroundColor: 'var(--accent-soft)', color: 'var(--v3-accent)' }
                        : { borderColor: 'var(--v3-line)', color: 'var(--v3-muted)' }
                    }
                  >
                    {active ? <Check aria-hidden="true" className="size-3" /> : null}
                    {name}
                  </button>
                )
              })}
            </div>
          </div>
          <label className="block space-y-1 text-sm">
            <span className="block text-xs text-[var(--v3-muted)]">自定义排除词（逗号/顿号/换行分隔，可选）</span>
            <textarea
              rows={4}
              value={answers.exclusions}
              onChange={(event) => set({ exclusions: event.target.value })}
              placeholder={'例如：外包驻场、保险电销、房产中介\n（会与上方勾选项合并去重）'}
              className="w-full rounded-[10px] border bg-[var(--surface2)] px-3 py-2 text-sm leading-6 outline-none"
              style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
            />
          </label>
          <p className="text-xs text-[var(--v3-muted)]">
            最终排除项 {mergedExclusions.length} 个：{mergedExclusions.length > 0 ? mergedExclusions.join('、') : '无'}
          </p>
          <p className="text-xs text-[var(--v3-muted)]">
            排除词会进入草稿的规则黑名单（具体字段以预览为准）；不会绕过任何发送安全链路。
          </p>
        </div>
      ) : null}

      {step === 'qa-intensity' ? (
        <div className="space-y-2">
          {[
            { value: 'trial' as const, label: '尝试期（更保守）', hint: '采集更少候选、更短时长；适合先验证方向匹配。' },
            { value: 'normal' as const, label: '日常（推荐）', hint: '默认强度，仍受每日预算与全局安全链路约束。' },
            { value: 'active' as const, label: '积极（更主动）', hint: '提高采集上限；不改变发送安全确认与预算上限。' },
          ].map((option) => {
            const active = answers.intensity === option.value
            return (
              <button
                key={option.value}
                type="button"
                onClick={() => set({ intensity: option.value })}
                className="w-full rounded-[12px] border px-3 py-2 text-left"
                style={
                  active
                    ? { borderColor: 'var(--v3-accent)', backgroundColor: 'var(--accent-soft)' }
                    : { borderColor: 'var(--v3-line)' }
                }
              >
                <span className="text-sm font-medium">{option.label}</span>
                <span className="mt-0.5 block text-xs text-[var(--v3-muted)]">{option.hint}</span>
              </button>
            )
          })}
        </div>
      ) : null}

      {step === 'qa-image' ? (
        <div className="space-y-2">
          {[
            { value: true, label: '发送图片简历', hint: '仅表示模板偏好；实际发送仍需要图片确认与后端安全链路。' },
            { value: false, label: '不自动发送图片简历', hint: '只发文字招呼语；之后仍可在模板配置里调整。' },
          ].map((option) => {
            const active = answers.sendImgResume === option.value
            return (
              <button
                key={String(option.value)}
                type="button"
                onClick={() => set({ sendImgResume: option.value })}
                className="w-full rounded-[12px] border px-3 py-2 text-left"
                style={
                  active
                    ? { borderColor: 'var(--v3-accent)', backgroundColor: 'var(--accent-soft)' }
                    : { borderColor: 'var(--v3-line)' }
                }
              >
                <span className="text-sm font-medium">{option.label}</span>
                <span className="mt-0.5 block text-xs text-[var(--v3-muted)]">{option.hint}</span>
              </button>
            )
          })}
        </div>
      ) : null}

      {stepError ? <p className="text-sm" style={{ color: 'var(--v3-bad)' }}>{stepError}</p> : null}
    </div>
  )
}

function PreviewStep({
  draft,
  finalName,
  setFinalName,
  directionTitle,
  keywords,
  answers,
  profile,
  saveError,
  saving,
}: {
  draft: AiDraftResult
  finalName: string
  setFinalName: (value: string) => void
  directionTitle: string
  keywords: string[]
  answers: Answers
  profile?: ResumeAnalysis['profile'] | null
  saveError: string | null
  saving: boolean
}) {
  const config = draft.config ?? {}
  const policy = config.deliveryPolicy ?? null
  const rule = config.rule ?? null
  const cityText = answers.cities.length
    ? answers.cities.map((city, index) => (index === 0 ? `${city}（主搜索城市）` : city)).join('、')
    : config.cityCode || '不限'
  const industryText = config.industry?.length
    ? config.industry.join('、')
    : answers.industries.length
      ? answers.industries.join('、')
      : profile?.industries?.length
        ? profile.industries.join('、')
        : '不限'
  const degreeText = config.degree?.length ? config.degree.join('、') : profile?.highestDegree || '由画像推断'
  const experienceText = config.experience?.length ? config.experience.join('、') : experienceLabel(profile?.yearsExperience)
  const scaleText = config.scale?.length
    ? config.scale.join('、')
    : '20 人以上（20-99 / 100-499 / 500-999 / 1000-9999 / 10000 人以上）'
  const stageText = config.stage?.length ? config.stage.join('、') : '不限'

  const rows: { label: string; value: React.ReactNode }[] = [
    { label: '方向', value: directionTitle || draft.direction || '—' },
    { label: '搜索关键词', value: keywords.length ? keywords.join('、') : '—' },
    { label: '城市', value: cityText },
    { label: '行业', value: industryText },
    { label: '学历要求', value: degreeText },
    { label: '经验要求', value: experienceText },
    { label: '公司规模', value: scaleText },
    { label: '融资阶段', value: stageText },
    {
      label: '期望月薪下限',
      value:
        rule?.expectedSalaryMin != null && rule.expectedSalaryMin > 0
          ? `${rule.expectedSalaryMin}K 起`
          : config.expectedSalaryMin != null && config.expectedSalaryMin > 0
            ? `${config.expectedSalaryMin}K 起`
            : '未设置',
    },
    { label: 'AI 评估', value: config.enableAI ? '启用' : '未启用' },
    { label: '图片简历', value: config.sendImgResume ? '自动发送（仍走图片确认）' : '不自动发送' },
    {
      label: '采集策略',
      value: policy
        ? `每关键词 ${policy.collectMaxPerKeyword ?? '默认'} · 单关键词 ${policy.collectMaxDurationMinutes ?? '默认'} 分钟 · 无新增判定 ${policy.collectNoNewWindowSeconds ?? '默认'} 秒`
        : '默认',
    },
    {
      label: '筛选规则',
      value: rule
        ? `岗位黑名单 ${rule.blacklistJob?.length ?? 0} 个 · 不可接受城市 ${rule.blacklistCities?.length ?? 0} 个 · 白名单 ${rule.whitelist?.length ?? 0} 个`
        : '默认',
    },
  ]

  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-[15px] font-semibold">预览草稿</h3>
        <p className="mt-1 text-sm text-[var(--v3-muted)]">
          这是将要写入模板的配置预览；保存前不会创建模板、不会激活、不会开始投递。
        </p>
      </div>

      <label className="block space-y-1 text-sm">
        <span className="block text-xs text-[var(--v3-muted)]">模板名称（可改）</span>
        <input
          value={finalName}
          onChange={(event) => setFinalName(event.target.value)}
          maxLength={60}
          className="h-10 w-full rounded-[10px] border bg-[var(--surface2)] px-3 text-sm outline-none"
          style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
        />
      </label>

      {draft.description ? <p className="text-sm text-[var(--v3-muted)]">{draft.description}</p> : null}

      <dl className="divide-y rounded-[12px] border" style={{ borderColor: 'var(--v3-line)' }}>
        {rows.map((row) => (
          <div key={row.label} className="grid grid-cols-[110px_1fr] gap-2 px-3 py-2 text-sm" style={{ borderColor: 'var(--v3-line)' }}>
            <dt className="text-xs text-[var(--v3-muted)]">{row.label}</dt>
            <dd className="min-w-0 break-all">{row.value}</dd>
          </div>
        ))}
      </dl>

      {(draft.warnings?.length ?? 0) > 0 ? (
        <div className="rounded-[12px] px-3 py-2 text-sm" style={{ backgroundColor: 'var(--warn-soft)', color: 'var(--v3-warn)' }}>
          <p className="font-medium">草稿提醒</p>
          <ul className="mt-1 list-inside list-disc text-xs">
            {draft.warnings?.map((warning, index) => (
              <li key={`${warning}-${index}`}>{warning}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {saveError ? (
        <div className="rounded-[12px] px-3 py-2 text-sm" style={{ backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }}>
          {saveError}
        </div>
      ) : null}

      <p className="text-xs leading-5 text-[var(--v3-muted)]">
        保存并激活表示下一轮任务按该模板运行，但不会立即发送；真实发送仍由后端规则/AI/身份/预算与文字+图片确认链路决定。
        {saving ? ' 正在保存…' : ''}
      </p>
    </div>
  )
}
