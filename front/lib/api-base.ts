/**
 * 后端 API 根地址 —— 全站唯一读取入口。
 *
 * 取值优先级：
 *   1. NEXT_PUBLIC_API_BASE —— 显式覆盖（NEXT_PUBLIC_ 前缀是 Next.js 注入浏览器端的要求）
 *   2. API_BASE_URL         —— next.config.ts 从 front/server.config.js 的 api.baseUrl 注入；
 *                              若它被改成非默认地址，视为显式配置，保持最高优先级
 *   3. 浏览器端按页面 host 推导 —— `http://${window.location.hostname}:9527`
 *      （B5 会话 Cookie 同源性：SameSite=Strict 的 HttpOnly Cookie 属于 "site"，
 *       页面开在 127.0.0.1:6866 而 API 走 localhost:9527 会被浏览器当作跨站，
 *       登录 Cookie 不会随请求发送 → 登录循环。按页面 host 推导可保证同 host 不同端口）
 *   4. 兜底字面量 —— 服务端/构建期没有 window 时使用，需与后端 application.yaml 的 server.port 一致
 *
 * 也就是说：平时改端口只改 front/server.config.js 和后端 application.yaml 两处；
 * 默认地址会跟随浏览器访问的 host（localhost / 127.0.0.1）自动对齐，避免 Cookie 跨站。
 *
 * 单独成模块是为了避免 lib/api.ts 与 lib/auth.ts 互相 import 形成循环依赖
 * （api.ts 需要 auth 的 401 事件派发，auth.ts 需要这里的地址拼接）。
 */

/** 后端 API 默认端口（与 server.config.js / application.yaml 的 9527 保持一致） */
const DEFAULT_API_PORT = '9527'

/** 兜底默认地址（服务端渲染/构建期使用） */
const FALLBACK_API_BASE = `http://localhost:${DEFAULT_API_PORT}`

/** 是否回环主机名（浏览器端只有这两种访问方式会做同源推导） */
function isLoopbackHostname(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1'
}

/** 判断注入的 API_BASE_URL 是否就是默认地址（默认地址在浏览器端可以被同 host 推导覆盖） */
function isDefaultLoopbackBase(value: string): boolean {
  const trimmed = value.trim()
  if (!trimmed) return true
  try {
    const parsed = new URL(trimmed)
    const port = parsed.port || (parsed.protocol === 'https:' ? '443' : '80')
    return isLoopbackHostname(parsed.hostname) && port === DEFAULT_API_PORT
  } catch {
    // 解析失败按「非默认」处理，保留原有显式注入行为
    return false
  }
}

function resolveApiBase(): string {
  // 1. 显式覆盖（构建时注入的 NEXT_PUBLIC_* 变量）
  const explicit = process.env.NEXT_PUBLIC_API_BASE
  if (explicit && explicit.trim()) {
    return explicit.trim()
  }

  // 2. server.config.js 注入的地址：非默认值视为显式配置
  const injected = process.env.API_BASE_URL
  if (injected && injected.trim() && !isDefaultLoopbackBase(injected)) {
    return injected.trim()
  }

  // 3. 浏览器端：按页面 host 推导，保证会话 Cookie 同 host（SameSite=Strict 可发送）
  if (typeof window !== 'undefined' && window.location && isLoopbackHostname(window.location.hostname)) {
    return `http://${window.location.hostname}:${DEFAULT_API_PORT}`
  }

  // 4. 构建期/SSR/非回环访问：回退注入值或字面量
  return injected && injected.trim() ? injected.trim() : FALLBACK_API_BASE
}

export const API_BASE = resolveApiBase()

/** 拼接后端接口地址，path 以 / 开头，例如 apiUrl('/api/boss/config') */
export function apiUrl(path: string): string {
  return `${API_BASE}${path}`
}
