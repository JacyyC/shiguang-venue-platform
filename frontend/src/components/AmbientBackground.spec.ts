import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AmbientBackground from './AmbientBackground.vue'
import { dustState, retireDust, unbindDustCanvas } from '@/components/ambient/dustEngine'
import { useThemeStore } from '@/stores/theme'

const installCanvasStub = () => {
  const ctx = {
    fillStyle: '',
    setTransform: vi.fn(),
    clearRect: vi.fn(),
    fillRect: vi.fn(),
    beginPath: vi.fn(),
    arc: vi.fn(),
    fill: vi.fn()
  }
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(ctx as unknown as CanvasRenderingContext2D)
  return ctx
}

const mountBackground = () => {
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(AmbientBackground, { global: { plugins: [pinia] } })
  const theme = useThemeStore()
  // 与 main.ts 启动序一致：init 落 data-theme
  theme.init()
  return { wrapper, theme }
}

describe('AmbientBackground（V6-UI-UX-SHIGUANG S01 墨尘粒子画布单例宿主）', () => {
  let rafSpy: ReturnType<typeof vi.fn>
  let cancelSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    localStorage.clear()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1000 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 })
    rafSpy = vi.fn(() => 1)
    cancelSpy = vi.fn()
    vi.stubGlobal('requestAnimationFrame', rafSpy)
    vi.stubGlobal('cancelAnimationFrame', cancelSpy)
    installCanvasStub()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    retireDust()
    unbindDustCanvas()
  })

  it('renders the fixed field with a single particle canvas (V6 退役 scrim/极光 blob)', () => {
    const { wrapper } = mountBackground()
    expect(wrapper.find('.sg-bg-field').element).toBeTruthy()
    expect(wrapper.find('canvas.sg-bg-canvas').element).toBeTruthy()
    expect(wrapper.find('.sg-bg-scrim').exists()).toBe(false)
    expect(wrapper.findAll('.sg-bg-blob')).toHaveLength(0)
  })

  it('binds the engine and caps the backing store at DPR 1.5', () => {
    vi.stubGlobal('devicePixelRatio', 2)
    const { wrapper } = mountBackground()
    const canvas = wrapper.get('canvas.sg-bg-canvas').element as HTMLCanvasElement
    expect(canvas.width).toBe(1500)
    expect(canvas.height).toBe(1200)
    expect(dustState().n).toBe(Math.round((1000 * 800) / 380))
  })

  it('starts the ambient loop and drives engine ticks with performance timestamps', async () => {
    mountBackground()
    await Promise.resolve()
    expect(rafSpy).toHaveBeenCalled()
  })

  it('resize reseeds the population at the new viewport size', async () => {
    const { wrapper } = mountBackground()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 500 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 400 })
    window.dispatchEvent(new Event('resize'))
    await nextTick()
    const canvas = wrapper.get('canvas.sg-bg-canvas').element as HTMLCanvasElement
    expect(canvas.style.width).toBe('500px')
    // population 下限 900（min(4200, max(900, W*H/380))）
    expect(dustState().n).toBe(900)
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1000 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 })
  })

  it('theme flips keep the loop alive (颜色由引擎下一帧按 Token 重取)', async () => {
    const { theme } = mountBackground()
    theme.setTheme('dark')
    await nextTick()
    expect(rafSpy).toHaveBeenCalled()
    expect(dustState().n).toBeGreaterThan(0)
  })
})
