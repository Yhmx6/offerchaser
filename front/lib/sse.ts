/**
 * SSE 断线重连客户端（指数退避）。
 *
 * 供 BOSS 进度流等长连接使用：连接建立后由调用方挂事件监听；连接异常断开时
 * 不交给浏览器默认的固定 3s 重连，而是按 `初始间隔 × factor^(n-1)` 指数退避、
 * 封顶 `maxDelayMs`，并可选向下抖动 30%，避免多标签页同时重连打满后端。
 */

export type SSEListener = { name: string; handler: (event: MessageEvent) => void }

export type BackoffConfig = {
  initialDelayMs?: number
  maxDelayMs?: number
  factor?: number
  jitter?: boolean
}

export type SSEBackoffOptions = {
  listeners: SSEListener[]
  onOpen?: () => void
  onError?: (error: unknown, attempt: number, delayMs: number) => void
  backoff?: BackoffConfig
}

export type SSEBackoffClient = {
  close: () => void
}

/** 退避默认参数：1s 起、30s 封顶、每次约 1.7 倍增长、开启抖动。 */
const DEFAULT_INITIAL_DELAY_MS = 1000
const DEFAULT_MAX_DELAY_MS = 30_000
const DEFAULT_FACTOR = 1.7
const JITTER_RATIO = 0.3

type BackoffSettings = {
  initialDelayMs: number
  maxDelayMs: number
  factor: number
  jitter: boolean
}

/** 合并调用方配置与默认值。 */
function resolveBackoff(overrides?: BackoffConfig): BackoffSettings {
  return {
    initialDelayMs: overrides?.initialDelayMs ?? DEFAULT_INITIAL_DELAY_MS,
    maxDelayMs: overrides?.maxDelayMs ?? DEFAULT_MAX_DELAY_MS,
    factor: overrides?.factor ?? DEFAULT_FACTOR,
    jitter: overrides?.jitter ?? true,
  }
}

/**
 * 第 attempt 次失败后的等待时间（attempt 从 1 开始）。
 * 基础值 = min(initial × factor^(attempt-1), max)；开启抖动时只向下扣，
 * 因此实际重连永远不会比基础值更慢。
 */
function backoffDelayMs(settings: BackoffSettings, attempt: number): number {
  const exponent = Math.max(0, attempt - 1)
  const base = Math.min(
    settings.initialDelayMs * Math.pow(settings.factor, exponent),
    settings.maxDelayMs
  )
  if (!settings.jitter) return base
  return Math.floor(base - base * JITTER_RATIO * Math.random())
}

/**
 * 创建带指数退避的 EventSource 客户端。
 *
 * @param url      SSE 地址（`/api/boss/stream` 的绝对地址）
 * @param options  事件监听、开/错误回调与自定义退避参数
 * @returns 客户端句柄；页面卸载前必须调用 `close()` 释放连接与定时器
 */
export function createSSEWithBackoff(url: string, options: SSEBackoffOptions): SSEBackoffClient {
  const settings = resolveBackoff(options.backoff)
  const inBrowser = typeof window !== 'undefined'
  // 构建期/SSR 没有 EventSource；此时创建句柄但不发起连接
  const canConnect = inBrowser && typeof EventSource !== 'undefined'

  let source: EventSource | null = null
  let attempt = 0
  let stopped = false
  let retryTimer: number | null = null

  /** 关闭当前连接并清空引用；重复调用安全。 */
  const dropSource = () => {
    if (source) {
      try {
        source.close()
      } catch {
        // 连接可能已被浏览器关闭，忽略
      }
      source = null
    }
  }

  /** 取消排队中的重连定时器。 */
  const cancelPendingRetry = () => {
    if (retryTimer !== null) {
      window.clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  /** 记录一次失败并按退避延迟安排下一次连接。 */
  const scheduleRetry = (error: unknown) => {
    if (stopped) return
    const nextAttempt = attempt + 1
    const delayMs = backoffDelayMs(settings, nextAttempt)
    options.onError?.(error, nextAttempt, delayMs)
    attempt = nextAttempt
    cancelPendingRetry()
    retryTimer = window.setTimeout(open, delayMs)
  }

  /** 建立一次连接；所有监听器在每个新连接上重新注册（EventSource 是一次性的）。 */
  function open() {
    if (stopped) return

    let nextSource: EventSource
    try {
      nextSource = new EventSource(url)
    } catch (error) {
      scheduleRetry(error)
      return
    }

    source = nextSource
    options.listeners.forEach(({ name, handler }) => nextSource.addEventListener(name, handler))

    nextSource.onopen = () => {
      attempt = 0
      options.onOpen?.()
    }

    nextSource.onerror = (error) => {
      // 先显式关闭这条连接，再交给退避调度；浏览器默认重连不再生效
      if (source === nextSource) {
        dropSource()
      } else {
        try {
          nextSource.close()
        } catch {
          // 忽略
        }
      }
      scheduleRetry(error)
    }
  }

  /** 页面卸载/主动关闭：停止重连并释放连接。 */
  const shutdown = () => {
    stopped = true
    dropSource()
    cancelPendingRetry()
  }

  if (canConnect) {
    open()
  }

  const handleBeforeUnload = () => {
    shutdown()
  }

  if (inBrowser) {
    window.addEventListener('beforeunload', handleBeforeUnload)
  }

  return {
    close() {
      if (inBrowser) {
        window.removeEventListener('beforeunload', handleBeforeUnload)
      }
      shutdown()
    },
  }
}
