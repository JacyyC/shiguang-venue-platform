import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAmbientPause } from './useAmbientPause'

const setHidden = (hidden: boolean) => {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
}

const fireVisibilityChange = () => {
  document.dispatchEvent(new Event('visibilitychange'))
}

const mountHost = (options: { enabled?: () => boolean } = {}) => {
  const calls = { play: 0, pause: 0 }
  let syncFn: (() => void) | null = null
  const Host = defineComponent({
    setup() {
      const { sync } = useAmbientPause({
        play: () => {
          calls.play += 1
        },
        pause: () => {
          calls.pause += 1
        },
        enabled: options.enabled
      })
      syncFn = sync
      return () => h('div')
    }
  })
  const wrapper = mount(Host)
  return { wrapper, calls, sync: syncFn as unknown as () => void }
}

afterEach(() => {
  document.documentElement.classList.remove('no-motion')
  delete (document as unknown as Record<string, unknown>).hidden
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('useAmbientPause（V3-UI-UX-SHIGUANG S01 环境动画停帧纪律）', () => {
  it('plays once on mount when every gate is open', () => {
    const { calls } = mountHost()
    expect(calls.play).toBe(1)
    expect(calls.pause).toBe(0)
  })

  it('pauses on document.hidden and resumes when visible again', () => {
    const { calls } = mountHost()
    setHidden(true)
    fireVisibilityChange()
    expect(calls.pause).toBe(1)

    setHidden(false)
    fireVisibilityChange()
    expect(calls.play).toBe(2)
    expect(calls.pause).toBe(1)
  })

  it('does not repeat play while already playing (idempotent sync)', () => {
    const { calls, sync } = mountHost()
    sync()
    sync()
    expect(calls.play).toBe(1)
  })

  it('pauses when html.no-motion (动效 关) toggles on via class observer', async () => {
    const { calls } = mountHost()
    document.documentElement.classList.add('no-motion')
    await vi.waitFor(() => {
      expect(calls.pause).toBe(1)
    })
    document.documentElement.classList.remove('no-motion')
    await vi.waitFor(() => {
      expect(calls.play).toBe(2)
    })
  })

  it('never plays while the reduced-motion media query matches', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    const { calls } = mountHost()
    expect(calls.play).toBe(0)
    expect(calls.pause).toBe(0)
  })

  it('honors the business gate and resumes through the returned sync', () => {
    const enabled = ref(true)
    const { calls, sync } = mountHost({ enabled: () => enabled.value })
    expect(calls.play).toBe(1)

    enabled.value = false
    sync()
    expect(calls.pause).toBe(1)
    expect(calls.play).toBe(1)

    enabled.value = true
    sync()
    expect(calls.play).toBe(2)
  })

  it('stops for good on unmount', () => {
    const { wrapper, calls } = mountHost()
    wrapper.unmount()
    expect(calls.pause).toBeGreaterThanOrEqual(1)
    setHidden(true)
    fireVisibilityChange()
    expect(calls.play).toBe(1)
  })
})
