import { onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'

interface SseEvent {
  type: string
  unreadCount?: number
  [key: string]: unknown
}

interface NotificationStreamOptions {
  /** 轮询降级时的回调（默认调用 app.refreshUnreadCount） */
  onPoll?: () => void | Promise<void>
  /** 收到 NEW_MESSAGE 事件时的回调 */
  onNewMessage?: () => void
}

const SSE_ENDPOINT = '/api/notifications/sse'
const MAX_BACKOFF_MS = 30_000
const MAX_SSE_FAILURES = 3
const POLLING_INTERVAL_MS = 15_000

/**
 * SSE 实时通知连接 composable。
 *
 * 后端 JWT 过滤器仅从 Authorization header 读取 token，EventSource 不支持自定义
 * header，因此改用 fetch + ReadableStream 模拟 SSE。
 *
 * 行为：
 * - 连接 /api/notifications/sse，解析 text/event-stream
 * - UNREAD_COUNT → app.setUnreadCount
 * - NEW_MESSAGE  → app.incrementUnreadCount + onNewMessage 回调
 * - HEARTBEAT    → 忽略（仅保活）
 * - 断线自动重连（指数退避：1s → 2s → 4s → 8s → 最大 30s）
 * - 连续失败 3 次后降级为 15s 轮询
 * - 组件卸载时自动关闭连接并清理定时器
 */
export function useNotificationStream(options?: NotificationStreamOptions) {
  const auth = useAuthStore()
  const app = useAppStore()

  let abortController: AbortController | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined
  let pollingTimer: ReturnType<typeof setInterval> | undefined
  let failureCount = 0
  let stopped = false

  /** 解析单个 SSE 事件块（已按 \n\n 分割），提取 data: 字段的 JSON */
  const parseSseEvent = (block: string): SseEvent | null => {
    for (const line of block.split('\n')) {
      const trimmed = line.trim()
      if (trimmed.startsWith('data:')) {
        const json = trimmed.slice(5).trim()
        if (json) {
          try {
            return JSON.parse(json) as SseEvent
          } catch {
            return null
          }
        }
      }
    }
    return null
  }

  const handleEvent = (event: SseEvent) => {
    switch (event.type) {
      case 'UNREAD_COUNT':
        app.setUnreadCount(Number(event.unreadCount) || 0)
        break
      case 'NEW_MESSAGE':
        app.incrementUnreadCount()
        options?.onNewMessage?.()
        break
      case 'HEARTBEAT':
        break
    }
  }

  const startPollingFallback = () => {
    if (pollingTimer) return
    pollingTimer = setInterval(() => {
      if (options?.onPoll) {
        void options.onPoll()
      } else {
        void app.refreshUnreadCount()
      }
    }, POLLING_INTERVAL_MS)
  }

  const scheduleReconnect = () => {
    if (stopped || !auth.token) return
    const delay = Math.min(1000 * Math.pow(2, failureCount - 1), MAX_BACKOFF_MS)
    reconnectTimer = setTimeout(() => {
      reconnectTimer = undefined
      void connectSse()
    }, delay)
  }

  const connectSse = async () => {
    if (stopped || !auth.token) return

    abortController = new AbortController()

    try {
      const response = await fetch(SSE_ENDPOINT, {
        headers: {
          Authorization: `Bearer ${auth.token}`,
          Accept: 'text/event-stream'
        },
        signal: abortController.signal
      })

      if (!response.ok || !response.body) {
        throw new Error(`SSE HTTP ${response.status}`)
      }

      // 连接成功，重置失败计数
      failureCount = 0

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // 按 \n\n 分割为完整事件，最后一段可能不完整，保留为 buffer
        const parts = buffer.split('\n\n')
        buffer = parts.pop() || ''

        for (const part of parts) {
          const event = parseSseEvent(part)
          if (event) {
            handleEvent(event)
          }
        }
      }

      // 流正常结束（服务端关闭），尝试重连
      if (!stopped) {
        scheduleReconnect()
      }
    } catch (error) {
      if (stopped) return
      if (error instanceof Error && error.name === 'AbortError') return

      failureCount++

      if (failureCount >= MAX_SSE_FAILURES) {
        // 连续失败超过阈值，降级为轮询
        startPollingFallback()
      } else {
        scheduleReconnect()
      }
    } finally {
      abortController = null
    }
  }

  const start = () => {
    if (!auth.isLoggedIn) return
    void connectSse()
  }

  const stop = () => {
    stopped = true
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = undefined
    }
    if (pollingTimer) {
      clearInterval(pollingTimer)
      pollingTimer = undefined
    }
  }

  onUnmounted(stop)

  return { start, stop }
}
