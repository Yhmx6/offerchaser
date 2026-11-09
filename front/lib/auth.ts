import { apiUrl } from './api-base'

/**
 * 本机会话认证（B5）。
 *
 * - 认证方式是后端下发的 HttpOnly Cookie（OFFERCHASER_SESSION），前端**不持久化 token**：
 *   登录时 token 只作为请求体发送一次，之后完全依赖 Cookie；
 * - 会话过期/未登录时，任何接口返回 401（或 403 CSRF_HEADER_MISSING）都会派发
 *   `offerchaser:auth-required` 事件，AuthGate 据此自动重新弹出登录层；
 * - 会话相关接口（status/login/logout）不走这里的 request 封装，
 *   避免登录失败（401 INVALID_TOKEN）反过来触发 auth-required 弹层循环。
 */

/** 会话状态（GET /api/session/status 的 data，绝不包含 token） */
export interface SessionStatus {
  authEnabled?: boolean
  authenticated?: boolean
  initialized?: boolean
  tokenFileHint?: string
}

/** 登录成功返回（POST /api/session/login 的 data） */
export interface LoginResult {
  authenticated?: boolean
  expiresAt?: string | number | null
}

/** 认证事件名：任意接口 401 / CSRF 头缺失时派发 */
export const AUTH_REQUIRED_EVENT = 'offerchaser:auth-required'

/** 派发认证事件的详细信息 */
export interface AuthRequiredDetail {
  status?: number
  code?: string
  message?: string
  /** 触发原因，登录层据此展示不同提示文案 */
  reason?: 'expired' | 'unauthorized' | 'csrf' | 'logout'
}

/**
 * 需要本机会话认证时抛出的错误。
 * 与 ApiError 区分：页面可以做通用错误处理，AuthGate 通过事件统一弹登录层。
 */
export class AuthRequiredError extends Error {
  readonly status: number
  readonly code?: string

  constructor(message = '需要本机会话登录', status = 401, code?: string) {
    super(message)
    this.name = 'AuthRequiredError'
    this.status = status
    this.code = code
  }
}

/** 会话接口错误：保留 HTTP 状态码与后端 code/message（登录失败、429 节流等） */
export class SessionError extends Error {
  readonly status: number
  readonly code?: string

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.name = 'SessionError'
    this.status = status
    this.code = code
  }
}

/** 派发 auth-required 事件（SSR 环境自动忽略） */
export function dispatchAuthRequired(detail: AuthRequiredDetail = {}): void {
  if (typeof window === 'undefined') return
  try {
    window.dispatchEvent(new CustomEvent<AuthRequiredDetail>(AUTH_REQUIRED_EVENT, { detail }))
  } catch {
    // 极端环境下 CustomEvent 不可用时忽略：调用方仍会抛 AuthRequiredError
  }
}

/** 订阅 auth-required 事件，返回取消订阅函数 */
export function subscribeAuthRequired(handler: (detail: AuthRequiredDetail) => void): () => void {
  if (typeof window === 'undefined') return () => {}
  const listener = (event: Event) => {
    const detail = (event as CustomEvent<AuthRequiredDetail>).detail
    handler(detail ?? {})
  }
  window.addEventListener(AUTH_REQUIRED_EVENT, listener)
  return () => {
    window.removeEventListener(AUTH_REQUIRED_EVENT, listener)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

/**
 * 会话接口专用请求封装。
 * 与 lib/api.ts 的 request 一样带 Cookie 与 CSRF 头，但**不派发 auth-required 事件**，
 * 失败统一抛 SessionError（登录错误 message 由后端给出，页面直接展示）。
 */
async function sessionRequest<T>(
  path: string,
  options: { method?: string; body?: unknown } = {}
): Promise<T> {
  const headers = new Headers({
    Accept: 'application/json',
    'X-Requested-With': 'offerchaser',
  })
  let requestBody: string | undefined
  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json')
    requestBody = JSON.stringify(options.body)
  }

  let response: Response
  try {
    response = await fetch(apiUrl(path), {
      method: options.method ?? 'GET',
      headers,
      body: requestBody,
      credentials: 'include',
    })
  } catch {
    throw new SessionError(0, '无法连接后端服务，请确认服务已启动', 'NETWORK_ERROR')
  }

  const text = await response.text()
  let payload: unknown = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = text
    }
  }
  const record = isRecord(payload) ? payload : null
  const message =
    typeof record?.message === 'string' && record.message
      ? record.message
      : `请求失败（HTTP ${response.status}）`
  const code = typeof record?.code === 'string' ? record.code : undefined

  if (!response.ok || (record && record.success === false)) {
    throw new SessionError(response.status, message, code)
  }
  if (record && Object.prototype.hasOwnProperty.call(record, 'data')) {
    return record.data as T
  }
  return payload as T
}

/** GET /api/session/status：当前实例认证开关与本机会话状态（无需认证，绝不返回 token） */
export function getSessionStatus(): Promise<SessionStatus> {
  return sessionRequest<SessionStatus>('/api/session/status')
}

/** POST /api/session/login：用本地令牌换取 HttpOnly 会话 Cookie */
export function login(token: string): Promise<LoginResult> {
  return sessionRequest<LoginResult>('/api/session/login', {
    method: 'POST',
    body: { token },
  })
}

/** POST /api/session/logout：清除会话 Cookie */
export function logout(): Promise<LoginResult> {
  return sessionRequest<LoginResult>('/api/session/logout', { method: 'POST' })
}
