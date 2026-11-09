'use client'

import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { BiErrorCircle, BiHide, BiInfoCircle, BiKey, BiLockAlt, BiShow } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { DialogShell } from '@/components/ui/dialog-shell'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  getSessionStatus,
  login,
  subscribeAuthRequired,
  type AuthRequiredDetail,
  type SessionStatus,
} from '@/lib/auth'

/**
 * B5：本机会话认证门（根布局外层）。
 *
 * - 启动调用 GET /api/session/status；
 * - authEnabled && !authenticated → 全屏登录弹层（输入本地令牌；提示令牌文件路径；
 *   说明令牌只在本地、登录后存 HttpOnly Cookie，不写 localStorage/sessionStorage）；
 * - 任意接口返回 401（会话过期）或 403 CSRF 头缺失 → api 封装派发 auth-required 事件，
 *   这里自动重新弹出登录层；
 * - 登录成功关闭弹层并刷新页面数据；接口不可达时不阻塞页面（页面自身展示错误）。
 */

const REASON_NOTICE: Record<NonNullable<AuthRequiredDetail['reason']> | 'default', string> = {
  default: '管理台已开启本机会话认证，请输入本地访问令牌解锁。',
  unauthorized: '管理台已开启本机会话认证，请输入本地访问令牌解锁。',
  expired: '会话已过期或尚未登录，请输入本地令牌重新登录。',
  csrf: '写操作缺少认证标识（CSRF 头），请重新登录后重试。',
  logout: '已退出登录，请重新输入本地令牌解锁管理台。',
}

export default function AuthGate({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus | null>(null)
  const [gateOpen, setGateOpen] = useState(false)
  const [gateReason, setGateReason] = useState<NonNullable<AuthRequiredDetail['reason']>>('unauthorized')
  const [token, setToken] = useState('')
  const [showToken, setShowToken] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [loginError, setLoginError] = useState<string | null>(null)

  // 启动检查会话 + 订阅 401 事件
  useEffect(() => {
    let cancelled = false

    const check = async () => {
      try {
        const result = await getSessionStatus()
        if (cancelled) return
        setStatus(result)
        if (result.authEnabled && !result.authenticated) {
          setGateReason('unauthorized')
          setGateOpen(true)
        }
      } catch {
        // 后端不可达/接口尚未实现：不阻塞页面，页面自身会展示接口错误
      }
    }
    void check()

    const unsubscribe = subscribeAuthRequired((detail) => {
      if (cancelled) return
      setGateReason(detail.reason ?? 'unauthorized')
      // 登录层打开时保留用户正在输入的内容；只在首次弹出时清空错误
      setLoginError(null)
      setGateOpen(true)
    })

    return () => {
      cancelled = true
      unsubscribe()
    }
  }, [])

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const value = token.trim()
    if (!value) {
      setLoginError('请输入本地访问令牌')
      return
    }
    if (submitting) return
    setSubmitting(true)
    setLoginError(null)
    try {
      const result = await login(value)
      if (result && result.authenticated === false) {
        setLoginError('登录未生效，请检查令牌文件后重试')
        return
      }
      setStatus((prev) => (prev ? { ...prev, authenticated: true } : prev))
      setGateOpen(false)
      setToken('')
      // 会话 Cookie 已生效：刷新页面数据，让所有已发起的接口重新拉取
      if (typeof window !== 'undefined') {
        window.location.reload()
      }
    } catch (loginFailure) {
      // 后端 message 直接展示（INVALID_TOKEN / TOO_MANY_ATTEMPTS / 网络错误）
      setLoginError(loginFailure instanceof Error ? loginFailure.message : String(loginFailure))
    } finally {
      setSubmitting(false)
    }
  }

  const tokenFileHint = status?.tokenFileHint || 'data/local-session-token'
  const notice = REASON_NOTICE[gateReason] ?? REASON_NOTICE.default

  return (
    <>
      {children}

      <DialogShell
        open={gateOpen}
        onClose={() => undefined}
        title="OfferChaser · 本机登录"
        description="管理台写操作需要本机会话认证"
        dismissable={false}
        closeOnScrim={false}
        width={480}
      >
        <div className="mt-4 space-y-4">
          <p
            className={`flex items-start gap-2 rounded-control border border-l-2 px-3 py-2 text-sm ${
              gateReason === 'expired' || gateReason === 'csrf'
                ? 'border-warning-border border-l-warning bg-warning-bg text-warning'
                : 'border-info-border border-l-info bg-info-bg text-info'
            }`}
          >
            {gateReason === 'expired' || gateReason === 'csrf' ? (
              <BiErrorCircle className="mt-0.5 shrink-0" />
            ) : (
              <BiLockAlt className="mt-0.5 shrink-0" />
            )}
            {notice}
          </p>

          <form onSubmit={(event) => void handleSubmit(event)} className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="session-token">本地访问令牌</Label>
              <div className="relative">
                <Input
                  id="session-token"
                  type={showToken ? 'text' : 'password'}
                  value={token}
                  data-autofocus
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="粘贴 data/local-session-token 文件中的令牌"
                  onChange={(event) => setToken(event.target.value)}
                  className="pr-10"
                />
                <button
                  type="button"
                  aria-label={showToken ? '隐藏令牌' : '显示令牌'}
                  className="absolute right-1 top-1/2 -translate-y-1/2 rounded-full p-2 text-muted-foreground hover:text-foreground"
                  onClick={() => setShowToken((value) => !value)}
                >
                  {showToken ? <BiHide /> : <BiShow />}
                </button>
              </div>
            </div>

            {loginError ? (
              <p className="rounded-lg border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-700 dark:text-red-300">
                {loginError}
              </p>
            ) : null}

            <Button type="submit" className="w-full rounded-full" disabled={submitting || !token.trim()}>
              <BiKey className="mr-1" />
              {submitting ? '登录中…' : '登录管理台'}
            </Button>
          </form>

          <div className="space-y-1.5 rounded-lg border bg-muted/20 px-3 py-2 text-xs text-muted-foreground">
            <p className="flex items-start gap-1">
              <BiInfoCircle className="mt-0.5 shrink-0" />
              令牌文件：<code className="break-all font-mono">{tokenFileHint}</code>（本机 data/ 目录，首次启动自动生成）
            </p>
            <p className="pl-4">
              令牌只在本次登录请求中发送，之后使用 HttpOnly 会话 Cookie；不会写入 localStorage / sessionStorage。
            </p>
            <p className="pl-4">登录失败次数过多会触发 1 分钟节流（429 TOO_MANY_ATTEMPTS），请稍后再试。</p>
          </div>
        </div>
      </DialogShell>

    </>
  )
}
