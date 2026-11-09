'use client'

import { useCallback, useEffect, useState } from 'react'
import { Eye, EyeOff, KeyRound, RefreshCw, Save, ServerCog, ShieldCheck } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { InlineAlert } from '@/components/ui/inline-alert'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { request } from '@/lib/api'

/**
 * 配置中心 · 全局模型服务。
 *
 * 全局 BASE_URL / API_KEY / MODEL 配置；
 * 这是全局配置，不随方向模板变化。API_KEY 读取时只回显掩码，原样提交表示保持原值。
 */

interface ModelForm {
  baseUrl: string
  apiKey: string
  model: string
}

type Notice = { tone: 'success' | 'danger'; text: string } | null

const MASKED = '********'
const EMPTY_FORM: ModelForm = { baseUrl: '', apiKey: '', model: '' }

function readText(source: Record<string, unknown> | null | undefined, key: string): string {
  const value = source?.[key]
  return value == null ? '' : String(value)
}

function describeError(error: unknown): string {
  return error instanceof Error && error.message ? error.message : '未知错误'
}

export default function ModelServiceCard() {
  const [form, setForm] = useState<ModelForm>(EMPTY_FORM)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [showApiKey, setShowApiKey] = useState(false)
  const [notice, setNotice] = useState<Notice>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await request<Record<string, unknown>>('/api/config')
      setForm({
        baseUrl: readText(data, 'BASE_URL'),
        apiKey: readText(data, 'API_KEY'),
        model: readText(data, 'MODEL'),
      })
      setNotice(null)
    } catch (error) {
      console.error('加载模型服务配置失败:', error)
      setNotice({ tone: 'danger', text: '模型服务配置读取失败，请检查后端服务' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const save = useCallback(async () => {
    setSaving(true)
    try {
      await request<Record<string, unknown>>('/api/config', {
        method: 'POST',
        body: {
          BASE_URL: form.baseUrl,
          API_KEY: form.apiKey,
          MODEL: form.model,
        },
      })
      setNotice({
        tone: 'success',
        text: '模型服务配置已保存；下一轮任务/下一次 AI 请求生效，运行中的任务不受影响。',
      })
    } catch (error) {
      console.error('保存模型服务配置失败:', error)
      setNotice({ tone: 'danger', text: `保存失败：${describeError(error)}` })
    } finally {
      setSaving(false)
    }
  }, [form])

  const maskedKey = form.apiKey === MASKED

  return (
    <Card className="oc-card">
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <CardTitle className="flex items-center gap-2">
              <ServerCog aria-hidden="true" className="size-4 text-[hsl(var(--brand-deep))]" />
              模型服务（全局）
            </CardTitle>
            <CardDescription>
              OpenAI 兼容接口地址、模型名与密钥；AI 匹配和招呼语都走这里，不随模板切换。
            </CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <Button
              type="button"
              size="sm"
              variant="secondary"
              onClick={() => void load()}
              disabled={loading || saving}
            >
              <RefreshCw aria-hidden="true" />
              重新读取
            </Button>
            <Button
              type="button"
              size="md"
              variant="primary"
              className="oc-btn-primary h-10 rounded-full px-5 text-sm font-semibold shadow-sm"
              onClick={() => void save()}
              disabled={loading || saving}
            >
              <Save aria-hidden="true" />
              {saving ? '保存中…' : '保存模型配置'}
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="global-base-url">API Base URL</Label>
            <Input
              id="global-base-url"
              value={form.baseUrl}
              onChange={(event) => setForm((prev) => ({ ...prev, baseUrl: event.target.value }))}
              placeholder="https://api.deepseek.com"
              autoComplete="off"
            />
            <p className="text-xs text-muted-foreground">兼容 OpenAI 协议的服务器地址</p>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="global-model">模型名称</Label>
            <Input
              id="global-model"
              value={form.model}
              onChange={(event) => setForm((prev) => ({ ...prev, model: event.target.value }))}
              placeholder="deepseek-chat"
              autoComplete="off"
            />
            <p className="text-xs text-muted-foreground">请求体中使用的模型名</p>
          </div>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="global-api-key" className="flex items-center gap-1.5">
            <KeyRound aria-hidden="true" className="size-3.5" />
            API Key
          </Label>
          <div className="relative">
            <Input
              id="global-api-key"
              type={showApiKey ? 'text' : 'password'}
              value={form.apiKey}
              onChange={(event) => setForm((prev) => ({ ...prev, apiKey: event.target.value }))}
              placeholder="sk-…"
              autoComplete="new-password"
              className="pr-16"
            />
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => setShowApiKey((visible) => !visible)}
              className="absolute right-1 top-1/2 -translate-y-1/2"
            >
              {showApiKey ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
              {showApiKey ? '隐藏' : '显示'}
            </Button>
          </div>
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <ShieldCheck aria-hidden="true" className="size-3.5 shrink-0" />
            {maskedKey
              ? '当前显示掩码；保持原样保存不会覆盖真实密钥。'
              : '读取时只返回掩码；输入新值会替换，清空会清除。'}
          </p>
        </div>

        {notice ? <InlineAlert variant={notice.tone}>{notice.text}</InlineAlert> : null}
      </CardContent>
    </Card>
  )
}
