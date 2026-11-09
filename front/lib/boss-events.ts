import { apiUrl, getBossStatus } from './api'
import type { BossStatus, SseProgress } from './boss-types'
import { createSSEWithBackoff, type SSEBackoffClient } from './sse'

/**
 * BOSS 状态事件订阅。
 *
 * 后端 /api/boss/stream 在连接建立时先推一条 `snapshot`（data = 状态 map），
 * 之后每条 `progress` 都带 `runId` / `seq` / `state`：
 * - 按 `runId` 丢弃旧任务的迟到事件（任务切换后不再污染新任务状态）；
 * - 按 `seq` 丢弃重复/乱序事件（断线重连、多标签页下尤其重要）；
 * - `onopen` / 重连成功后主动拉一次 `GET /api/boss/status`，
 *   保证断线期间错过的状态变化在重连后立刻补齐。
 */
export interface SubscribeBossEventsOptions {
  /** 收到状态快照（SSE snapshot 或重连后主动拉取的 status） */
  onSnapshot: (status: BossStatus) => void
  /** 收到去重后的进度事件 */
  onProgress?: (progress: SseProgress) => void
  /** SSE 连接/重连错误（指数退避中） */
  onError?: (error: unknown, attempt: number, delayMs: number) => void
  /** 连接建立时回调（此时会同时触发一次状态快照拉取） */
  onOpen?: () => void
  /** 断线重连后主动拉取状态失败时的回调（不影响 SSE 继续重连） */
  onSnapshotError?: (error: unknown) => void
}

function parseJSON(raw: unknown): unknown {
  if (typeof raw !== 'string') return raw
  try {
    return JSON.parse(raw) as unknown
  } catch {
    return null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

/** SSE `snapshot` 事件 → BossStatus */
function parseSnapshot(event: MessageEvent): BossStatus | null {
  const parsed = parseJSON(event.data)
  if (!isRecord(parsed)) return null
  // 兼容未来可能出现的 {success:true,data:{...}} 外壳
  const data = isRecord(parsed.data) ? parsed.data : parsed
  return data as BossStatus
}

/** SSE `progress` 事件 → SseProgress */
function parseProgress(event: MessageEvent): SseProgress | null {
  const parsed = parseJSON(event.data)
  if (!isRecord(parsed)) return null
  return parsed as SseProgress
}

/**
 * 订阅 BOSS 运行状态事件。
 *
 * @param options 回调集合
 * @returns SSE 客户端句柄；页面卸载时务必调用 `close()`
 */
export function subscribeBossEvents(options: SubscribeBossEventsOptions): SSEBackoffClient {
  /** 当前跟踪的 runId：一旦确定，其他 runId 的迟到事件一律丢弃 */
  let currentRunId: string | null = null
  /** 已处理的最大 seq；服务实例内全局单调递增，重连后也不清零 */
  let lastSeq = -1

  const refreshSnapshot = async () => {
    try {
      const status = await getBossStatus()
      if (status && typeof status === 'object') {
        if (typeof status.runId === 'string' && status.runId) {
          currentRunId = status.runId
        }
        options.onSnapshot(status)
      }
    } catch (error) {
      options.onSnapshotError?.(error)
    }
  }

  const acceptProgress = (progress: SseProgress): boolean => {
    const incomingRunId = typeof progress.runId === 'string' && progress.runId ? progress.runId : null

    if (incomingRunId) {
      if (currentRunId && incomingRunId !== currentRunId) {
        // 旧任务的迟到事件：只有明确是新任务启动时才切换跟踪对象
        if (progress.state !== 'STARTING') {
          return false
        }
      }
      currentRunId = incomingRunId
    }

    if (typeof progress.seq === 'number' && Number.isFinite(progress.seq)) {
      if (progress.seq <= lastSeq) {
        return false
      }
      lastSeq = progress.seq
    }
    return true
  }

  const client = createSSEWithBackoff(apiUrl('/api/boss/stream'), {
    onOpen: () => {
      // 断线重连后先拉一次快照，补齐断线期间的状态变化
      void refreshSnapshot()
      options.onOpen?.()
    },
    onError: (error, attempt, delayMs) => {
      options.onError?.(error, attempt, delayMs)
    },
    listeners: [
      {
        name: 'snapshot',
        handler: (event) => {
          const snapshot = parseSnapshot(event)
          if (!snapshot) return
          if (typeof snapshot.runId === 'string' && snapshot.runId) {
            currentRunId = snapshot.runId
          }
          options.onSnapshot(snapshot)
        },
      },
      {
        name: 'progress',
        handler: (event) => {
          const progress = parseProgress(event)
          if (!progress) return
          if (!acceptProgress(progress)) return
          options.onProgress?.(progress)
        },
      },
      // 服务端心跳/连接确认事件：订阅即可，不需要更新状态
      { name: 'ping', handler: () => {} },
      { name: 'connected', handler: () => {} },
    ],
  })

  return client
}
