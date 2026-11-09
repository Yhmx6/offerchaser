'use client'

import { useState } from 'react'
import { AlertTriangle, ClipboardCopy, Loader2, Sparkles, ShieldCheck } from 'lucide-react'

import { analyzeJobLink } from '@/lib/api'
import { useSpotlight } from '@/lib/use-spotlight'
import type { JobInsightAnalysis, JobInsightResult } from '@/lib/job-insight-types'

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function Tag({ children }: { children: React.ReactNode }) {
  return (
    <span className="oc-chip rounded-full border px-2 py-0.5 text-[11px]" data-tone="muted" data-nodot>
      {children}
    </span>
  )
}

function ListBlock({ title, items, tone = 'default' }: { title: string; items: string[]; tone?: 'default' | 'warn' | 'ok' }) {
  if (items.length === 0) return null
  const color = tone === 'warn' ? 'var(--v3-warn)' : tone === 'ok' ? 'var(--v3-ok)' : 'var(--text)'
  return (
    <div>
      <p className="text-xs font-medium" style={{ color }}>
        {title}
      </p>
      <ul className="mt-2 space-y-1.5">
        {items.map((item, index) => (
          <li key={`${item}-${index}`} className="flex gap-2 text-sm leading-6 text-[var(--text)]">
            <span className="mt-2 size-1 shrink-0 rounded-full" style={{ backgroundColor: color }} aria-hidden="true" />
            <span className="min-w-0">{item}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function AnalysisView({ result }: { result: JobInsightResult }) {
  const job = result.job
  const insight: JobInsightAnalysis = result.insight
  const [copied, setCopied] = useState<string | null>(null)

  const copy = async (text: string, key: string) => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(key)
      window.setTimeout(() => setCopied(null), 1500)
    } catch {
      setCopied('copy-failed')
    }
  }

  return (
    <div className="mt-5 space-y-5 border-t pt-5" style={{ borderColor: 'var(--v3-line)' }}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-xl font-semibold">{job.jobName || '未命名岗位'}</h3>
          <p className="mt-1 text-sm text-[var(--v3-muted)]">
            {job.companyName || '公司未识别'}{job.industry ? ` · ${job.industry}` : ''}
          </p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {job.salary ? <Tag>{job.salary}</Tag> : null}
            {job.city ? <Tag>{job.city}</Tag> : null}
            {job.experience ? <Tag>{job.experience}</Tag> : null}
            {job.degree ? <Tag>{job.degree}</Tag> : null}
            {job.companyScale ? <Tag>{job.companyScale}</Tag> : null}
            {job.financingStage ? <Tag>{job.financingStage}</Tag> : null}
          </div>
        </div>
        <span className="rounded-full border px-2.5 py-1 text-[11px] text-[var(--v3-muted)]" style={{ borderColor: 'var(--v3-line)' }}>
          只读 · 不自动沟通
        </span>
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <div className="rounded-[14px] border p-4" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }}>
          <p className="text-xs font-medium text-[var(--v3-muted)]">公司怎么看</p>
          <p className="mt-2 text-sm leading-6">{insight.companySummary}</p>
        </div>
        <div className="rounded-[14px] border p-4" style={{ borderColor: 'var(--v3-line)', backgroundColor: 'var(--surface2)' }}>
          <p className="text-xs font-medium text-[var(--v3-muted)]">岗位真正看重什么</p>
          <p className="mt-2 text-sm leading-6">{insight.jobSummary}</p>
        </div>
      </div>

      <div className="grid gap-5 md:grid-cols-3">
        <ListBlock title="你的匹配点" items={insight.matchPoints} tone="ok" />
        <ListBlock title="需要补/准备的缺口" items={insight.gaps} />
        <ListBlock title="岗位/公司风险（需核实）" items={insight.risks} tone="warn" />
      </div>

      <div className="rounded-[14px] border p-4" style={{ borderColor: 'var(--v3-line)' }}>
        <p className="text-sm font-semibold">沟通建议</p>
        <ListBlock title="" items={insight.communicationAdvice} />
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
          <p className="text-xs font-medium text-[var(--v3-muted)]">首次沟通话术</p>
          <button
            type="button"
            onClick={() => void copy(insight.openingScript, 'opening')}
            className="inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs"
            style={{ borderColor: 'var(--v3-line)' }}
          >
            <ClipboardCopy aria-hidden="true" className="size-3.5" />
            {copied === 'opening' ? '已复制' : copied === 'copy-failed' ? '复制失败' : '复制话术'}
          </button>
        </div>
        <p className="mt-2 whitespace-pre-wrap rounded-[10px] px-3 py-2 text-sm leading-6" style={{ backgroundColor: 'var(--surface2)' }}>
          {insight.openingScript}
        </p>
      </div>

      {insight.replyScripts.length > 0 ? (
        <div>
          <p className="text-sm font-semibold">可能被问到时的回复</p>
          <div className="mt-2 grid gap-3 md:grid-cols-2">
            {insight.replyScripts.map((item, index) => (
              <div key={`${item.scene}-${index}`} className="rounded-[14px] border p-3" style={{ borderColor: 'var(--v3-line)' }}>
                <p className="text-xs font-medium" style={{ color: 'var(--v3-accent)' }}>{item.scene}</p>
                <p className="mt-1.5 text-sm leading-6">{item.script}</p>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      <div className="grid gap-4 md:grid-cols-2">
        <ListBlock title="建议问 HR 的问题" items={insight.questionsToAsk} />
        <ListBlock title="首次沟通避免主动提" items={insight.avoidTopics} tone="warn" />
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2 border-t pt-3 text-xs text-[var(--v3-muted)]" style={{ borderColor: 'var(--v3-line)' }}>
        <span>{insight.disclaimer}</span>
        <span>置信度 {Math.round(insight.confidence * 100)}%</span>
      </div>
    </div>
  )
}

export function JobInsightCard() {
  const onSpot = useSpotlight()
  const [url, setUrl] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<JobInsightResult | null>(null)

  const analyze = async () => {
    const target = url.trim()
    if (!target) {
      setError('请先粘贴 BOSS 岗位链接')
      return
    }
    if (!/^https?:\/\/[^/]*zhipin\.com\/job_detail\//i.test(target)) {
      setError('只支持 BOSS 直聘岗位详情链接（/job_detail/…html）')
      return
    }
    setLoading(true)
    setError(null)
    try {
      setResult(await analyzeJobLink(target))
    } catch (loadError) {
      setResult(null)
      setError(errorText(loadError))
    } finally {
      setLoading(false)
    }
  }

  return (
    <section
      onPointerMove={onSpot}
      className="oc-card oc-spot rounded-[20px] border bg-[var(--surface)] p-5 shadow-[var(--shadow)]"
      style={{ borderColor: 'var(--v3-line)' }}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="text-[11px] font-medium tracking-[0.08em] text-[var(--v3-muted)]">CAREER BRIEF · 岗位解读</p>
          <h2 className="mt-1 text-[17px] font-semibold">读懂这家公司，再决定怎么聊</h2>
          <p className="mt-1 text-sm text-[var(--v3-muted)]">
            进入 BOSS 岗位详情页，在网址栏复制当前页面链接，结合当前模板画像生成岗位风险、匹配点和沟通话术。
          </p>
        </div>
        <span className="inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[11px] text-[var(--v3-ok)]" style={{ borderColor: 'var(--v3-line)' }}>
          <ShieldCheck aria-hidden="true" className="size-3.5" />
          只读，不会自动发消息
        </span>
      </div>

      <form
        className="mt-4 flex flex-wrap gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          void analyze()
        }}
      >
        <input
          type="url"
          aria-label="BOSS 岗位链接"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder="进入岗位详情页，在网址栏复制当前页面的链接"
          className="h-11 min-w-0 flex-1 rounded-[12px] border bg-[var(--surface2)] px-3 text-sm outline-none focus:ring-2"
          style={{ borderColor: 'var(--v3-line)', color: 'var(--text)' }}
        />
        <button
          type="submit"
          disabled={loading}
          className="oc-btn-primary oc-sweep inline-flex h-11 shrink-0 items-center gap-2 rounded-[12px] px-5 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-50"
          style={{ backgroundColor: 'var(--v3-accent)', color: 'var(--accent-ink)' }}
        >
          {loading ? <Loader2 aria-hidden="true" className="size-4 animate-spin" /> : <Sparkles aria-hidden="true" className="size-4" />}
          {loading ? '正在读取岗位…' : '解读岗位'}
        </button>
      </form>

      <p className="mt-2 text-xs leading-5 text-[var(--v3-muted)]">
        会使用程序已登录的 BOSS 浏览器只读打开岗位页；不会点击沟通、不会发送消息、不会投递。AI 结论需自行核实。
      </p>

      {error ? (
        <div className="mt-4 flex items-start gap-2 rounded-[12px] px-3 py-2 text-sm" style={{ backgroundColor: 'var(--bad-soft)', color: 'var(--v3-bad)' }}>
          <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span>{error}</span>
        </div>
      ) : null}

      {loading ? (
        <div className="mt-5 flex items-center gap-3 rounded-[12px] px-3 py-6 text-sm text-[var(--v3-muted)]" style={{ backgroundColor: 'var(--surface2)' }}>
          <Loader2 aria-hidden="true" className="size-5 animate-spin" style={{ color: 'var(--v3-accent)' }} />
          正在读取岗位并生成沟通解读，可能需要几十秒…
        </div>
      ) : null}

      {result ? <AnalysisView result={result} /> : null}
    </section>
  )
}
