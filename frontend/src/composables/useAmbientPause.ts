import { onBeforeUnmount, onMounted } from 'vue'

const REDUCE_MOTION_QUERY = '(prefers-reduced-motion: reduce)'

export interface UseAmbientPauseOptions {
  /** 恢复播放：必须幂等，重复调用无副作用 */
  play: () => void
  /** 暂停：环境动画停帧并保留最后一帧，必须幂等 */
  pause: () => void
  /** 业务闸门（如背景口味非流动时返回 false）；缺省恒允许 */
  enabled?: () => boolean
}

/**
 * 全站唯一环境动画的统一暂停纪律（V3-UI-UX-SHIGUANG S01，预览 L6）：
 * document.hidden / html.no-motion（「动效 关」）/ prefers-reduced-motion 任一命中即停帧，
 * 条件退出后自动恢复；组件卸载时终停。供流动背景 canvas、登录光野等环境层注册。
 */
export function useAmbientPause({ play, pause, enabled }: UseAmbientPauseOptions) {
  let playing = false
  let reduceQuery: MediaQueryList | null = null
  let classObserver: MutationObserver | null = null

  const shouldPlay = () =>
    (enabled?.() ?? true) &&
    !document.hidden &&
    !document.documentElement.classList.contains('no-motion') &&
    !(reduceQuery?.matches ?? false)

  const sync = () => {
    if (shouldPlay()) {
      if (!playing) {
        playing = true
        play()
      }
      return
    }
    if (playing) {
      playing = false
      pause()
    }
  }

  onMounted(() => {
    reduceQuery = typeof window.matchMedia === 'function' ? window.matchMedia(REDUCE_MOTION_QUERY) : null
    reduceQuery?.addEventListener?.('change', sync)
    document.addEventListener('visibilitychange', sync)
    // 「动效 关」以 html.no-motion 表达：类变化即时触发停帧/恢复
    classObserver = new MutationObserver(sync)
    classObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
    sync()
  })

  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', sync)
    reduceQuery?.removeEventListener?.('change', sync)
    classObserver?.disconnect()
    classObserver = null
    playing = false
    pause()
  })

  return { sync }
}
