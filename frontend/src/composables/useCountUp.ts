import { onBeforeUnmount, onMounted, ref, toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue'

interface CountUpOptions {
  duration?: number
  element?: Ref<HTMLElement | null>
  decimals?: number
}

const prefersReducedMotion = () => {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** Reveal a numeric summary once it enters the viewport, with a direct reduced-motion fallback. */
export function useCountUp(target: MaybeRefOrGetter<number>, options: CountUpOptions = {}) {
  const displayValue = ref(0)
  const running = ref(false)
  const started = ref(false)
  const duration = options.duration ?? 500
  const decimals = options.decimals ?? 0
  let frame = 0
  let observer: IntersectionObserver | null = null
  let direct = false

  const value = () => Number(toValue(target) || 0)
  const finish = () => {
    displayValue.value = value()
    running.value = false
  }

  const start = () => {
    if (started.value && !running.value && displayValue.value === value()) return
    started.value = true
    if (direct || prefersReducedMotion() || typeof window === 'undefined' || typeof window.requestAnimationFrame !== 'function') {
      finish()
      return
    }
    const from = Number(displayValue.value || 0)
    const to = value()
    if (from === to) {
      finish()
      return
    }
    const startedAt = performance.now()
    running.value = true
    const tick = (now: number) => {
      const progress = Math.min((now - startedAt) / duration, 1)
      const eased = 1 - Math.pow(1 - progress, 3)
      const next = from + (to - from) * eased
      displayValue.value = Number(next.toFixed(decimals))
      if (progress < 1) frame = window.requestAnimationFrame(tick)
      else finish()
    }
    frame = window.requestAnimationFrame(tick)
  }

  const stop = () => {
    if (frame && typeof window !== 'undefined') window.cancelAnimationFrame(frame)
    frame = 0
    running.value = false
  }

  watch(() => value(), () => {
    if (started.value || direct) {
      stop()
      start()
    }
  })

  onMounted(() => {
    const element = options.element?.value
    if (!element || typeof window === 'undefined' || typeof IntersectionObserver === 'undefined') {
      direct = true
      start()
      return
    }
    observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        start()
        observer?.disconnect()
      }
    }, { threshold: 0.4 })
    observer.observe(element)
  })

  onBeforeUnmount(() => {
    stop()
    observer?.disconnect()
  })

  return { displayValue, started, running, start, stop }
}
