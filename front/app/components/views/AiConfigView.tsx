'use client'

import { useCallback, useEffect, useState } from 'react'
import { Brain, FileText, Save, Sparkles } from 'lucide-react'

import { V2PageHeader } from '@/app/components/V2PageHeader'
import TemplateContextBanner from '@/app/components/TemplateContextBanner'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { InlineAlert } from '@/components/ui/inline-alert'
import { Label } from '@/components/ui/label'
import { StatusTag } from '@/components/ui/status-tag'
import { Textarea } from '@/components/ui/textarea'
import { request } from '@/lib/api'

/**
 * AI 配置 /ai-config
 *
 * 全局 AI 介绍与提示词，以及 BOSS 投递的 AI 启用开关（boss_config.enable_ai）。
 * 从「高级调试」进入；保存后后端会自动写回当前生效方向模板（模板优先级见配置中心）。
 */

interface AiConfigState {
  introduce: string
  prompt: string
}

type NoticeState = { tone: 'success' | 'danger' | 'warning'; text: string } | null

const EMPTY_AI_CONFIG: AiConfigState = { introduce: '', prompt: '' }

function describeError(error: unknown): string {
  return error instanceof Error && error.message ? error.message : '未知错误'
}

/** enable_ai 兼容 "1"/"true"/"on"/数字 1 三种历史写法。 */
function readEnableAi(raw: unknown): 0 | 1 {
  const text = String(raw ?? '').trim().toLowerCase()
  if (text === '1' || text === 'true' || text === 'on') return 1
  return Number(raw) === 1 ? 1 : 0
}

export default function AiConfigPage() {
  const [aiConfig, setAiConfig] = useState<AiConfigState>(EMPTY_AI_CONFIG)
  const [enableAi, setEnableAi] = useState<0 | 1>(0)
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<NoticeState>(null)

  // 读取 AI 介绍/提示词与 BOSS 配置里的 AI 开关
  useEffect(() => {
    let cancelled = false

    const loadAiConfig = async () => {
      try {
        const data = await request<{ introduce?: string | null; prompt?: string | null }>(
          '/api/ai/config'
        )
        if (cancelled) return
        setAiConfig({
          introduce: data?.introduce ?? '',
          prompt: data?.prompt ?? '',
        })
      } catch (error) {
        // 读取失败不影响使用：保持空配置，由用户决定是否填写
        console.error('加载AI配置失败:', error)
      }
    }

    const loadEnableAi = async () => {
      try {
        const data = await request<{ config?: { enableAi?: unknown } | null }>('/api/boss/config')
        if (cancelled) return
        setEnableAi(readEnableAi(data?.config?.enableAi))
      } catch (error) {
        console.error('加载enable_ai失败:', error)
      }
    }

    void loadAiConfig()
    void loadEnableAi()
    return () => {
      cancelled = true
    }
  }, [])

  // 切换 AI 开关：乐观更新，失败回滚并提示
  const toggleEnableAi = useCallback(async () => {
    const previous = enableAi
    const next: 0 | 1 = previous ? 0 : 1
    setEnableAi(next)
    try {
      await request<Record<string, unknown>>('/api/boss/config', {
        method: 'PUT',
        body: { enableAi: next },
      })
      setNotice({ tone: 'success', text: `AI 已${next ? '启用' : '关闭'}` })
    } catch (error) {
      console.error('更新enable_ai失败:', error)
      setEnableAi(previous)
      setNotice({ tone: 'danger', text: '切换失败，请检查后端服务连接' })
    }
  }, [enableAi])

  const handleSave = useCallback(async () => {
    setSaving(true)
    try {
      await request<Record<string, unknown>>('/api/ai/config', {
        method: 'POST',
        body: aiConfig,
      })
      setNotice({ tone: 'success', text: 'AI配置已保存！' })
    } catch (error) {
      console.error('保存AI配置失败:', error)
      setNotice({ tone: 'danger', text: `保存失败：${describeError(error)}` })
    } finally {
      setSaving(false)
    }
  }, [aiConfig])

  const updateField = useCallback((field: keyof AiConfigState, value: string) => {
    setAiConfig((prev) => ({ ...prev, [field]: value }))
  }, [])

  return (
    <div className="space-y-5">
      <V2PageHeader
        eyebrow="配置中心 · AI"
        title="AI 配置"
        status={
          <StatusTag tone={enableAi ? 'ok' : 'idle'}>
            {enableAi ? 'AI 已启用' : 'AI 未启用'}
          </StatusTag>
        }
        meta="AI 技能介绍与提示词 · 保存会自动同步到当前生效模板"
        actions={
          <Button
            type="button"
            size="md"
            variant="primary"
            onClick={() => void handleSave()}
            disabled={saving}
            className="h-11 rounded-full px-6 text-sm font-semibold shadow-md"
          >
            <Save aria-hidden="true" />
            {saving ? '保存中…' : '保存配置'}
          </Button>
        }
      />

      {/* 配置中心上下文：本页保存会自动同步到当前生效模板 */}
      <TemplateContextBanner scope="AI 配置" />

      {notice ? <InlineAlert variant={notice.tone}>{notice.text}</InlineAlert> : null}

      <Card>
        <CardHeader>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <CardTitle className="flex items-center gap-2">
                <Brain aria-hidden="true" className="size-4 text-[hsl(var(--brand-deep))]" />
                能力开关
              </CardTitle>
              <CardDescription>
                关闭后投递不再调用 AI，只使用固定招呼语；开启时 AI 失败按跳过处理，绝不回退原文
              </CardDescription>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <span className="text-xs font-medium text-[hsl(var(--text-3))]">
                {enableAi ? '已启用' : '未启用'}
              </span>
              <button
                type="button"
                role="switch"
                aria-checked={enableAi === 1}
                aria-label="AI启用开关"
                onClick={() => void toggleEnableAi()}
                className={`relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:shadow-focus ${
                  enableAi ? 'bg-primary' : 'bg-muted-foreground/40'
                }`}
              >
                <span
                  aria-hidden="true"
                  className={`pointer-events-none block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform ${
                    enableAi ? 'translate-x-5' : 'translate-x-0.5'
                  }`}
                />
              </button>
            </div>
          </div>
        </CardHeader>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Sparkles aria-hidden="true" className="size-4 text-[hsl(var(--brand-deep))]" />
            技能介绍
          </CardTitle>
          <CardDescription>描述技能、经验与专业背景，AI 基于它生成个性化求职内容</CardDescription>
        </CardHeader>
        <CardContent className="space-y-1.5">
          <Label htmlFor="ai-introduce">技能介绍</Label>
          <Textarea
            id="ai-introduce"
            value={aiConfig.introduce}
            onChange={(event) => updateField('introduce', event.target.value)}
            placeholder="请输入您的技能介绍，例如：我熟练使用Java、Python等语言进行开发..."
            className="min-h-[150px] resize-y"
          />
          <p className="text-xs text-muted-foreground">
            越具体越好（技术栈、项目类型、年限、成果），AI 不会编造这里没有的内容。
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <FileText aria-hidden="true" className="size-4 text-[hsl(var(--brand-deep))]" />
            提示词模板
          </CardTitle>
          <CardDescription>定义 AI 生成内容的模板与风格，支持占位符</CardDescription>
        </CardHeader>
        <CardContent className="space-y-1.5">
          <Label htmlFor="ai-prompt">AI 提示词</Label>
          <Textarea
            id="ai-prompt"
            value={aiConfig.prompt}
            onChange={(event) => updateField('prompt', event.target.value)}
            placeholder="请输入提示词模板，例如：根据岗位 {{job_name}} 和描述 {{job_description}}，判断候选人 {{introduce}} 是否匹配..."
            className="min-h-[150px] resize-y"
          />
          <p className="text-xs text-muted-foreground">
            支持命名占位符 <code className="font-mono text-xs">{'{{introduce}}'}</code>、
            <code className="font-mono text-xs">{'{{keyword}}'}</code>、
            <code className="font-mono text-xs">{'{{job_name}}'}</code>、
            <code className="font-mono text-xs">{'{{job_description}}'}</code>、
            <code className="font-mono text-xs">{'{{say_hi}}'}</code>；也兼容旧版 <code className="font-mono text-xs">%s</code>。
          </p>
        </CardContent>
      </Card>

      <InlineAlert
        variant="info"
        details={
          <ul className="list-inside list-disc space-y-1">
            <li>技能介绍：让 AI 了解你的专业能力、工作经验和背景，是生成个性化内容的基础。</li>
            <li>提示词：定义生成内容的模板和风格，建议使用 {'{{job_name}}'} / {'{{job_description}}'} 等命名占位符。</li>
            <li>效果：配置保存后，AI 会在自动投递时生成匹配度更高的沟通内容。</li>
            <li>建议：定期更新技能介绍，保持与最新项目经历一致。</li>
          </ul>
        }
      >
        介绍决定「AI 知道什么」，提示词决定「AI 怎么说」
      </InlineAlert>
    </div>
  )
}
