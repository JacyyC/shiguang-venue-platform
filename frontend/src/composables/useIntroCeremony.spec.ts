import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useIntroCeremony } from './useIntroCeremony'
import {
  bindDustCanvas,
  dustState,
  retireDust,
  tickDust,
  unbindDustCanvas
} from '@/components/ambient/dustEngine'

/**
 * V6-UI-UX-SHIGUANG S01 进场仪式控制器：状态机 / 护栏 / 会话门 / 卸载清理。
 * 引擎画布在测试内绑定（dustEngine 单例），rAF 以手动队列推进，引擎帧手动驱动；
 * setTimeout 系（8s 自动聚形 / 4.5s 兜底 / 360·430·980 交接 / 2s settle）走假计时器。
 */

const fillCtx = () => {
  const data = new Uint8ClampedArray(1000 * 800 * 4)
  // 三枚墨色不透明采样点（buildTargets 以 3px 网格扫描 α>110，坐标取 3 的倍数）
  ;[102, 201, 300].forEach((x) => {
    const i = (102 * 1000 + x) * 4
    data[i] = 36
    data[i + 1] = 29
    data[i + 2] = 20
    data[i + 3] = 255
  })
  const ctx = {
    font: '',
    fillStyle: '',
    textAlign: '',
    textBaseline: '',
    setTransform: vi.fn(),
    clearRect: vi.fn(),
    fillRect: vi.fn(),
    beginPath: vi.fn(),
    arc: vi.fn(),
    fill: vi.fn(),
    fillText: vi.fn(),
    measureText: vi.fn(() => ({ width: 10 })),
    getImageData: vi.fn(() => ({ data }))
  }
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(ctx as unknown as CanvasRenderingContext2D)
  return ctx
}

let rafQueue: Array<(ts: number) => void> = []
let rafClock = 0
let engineClock = 0

const stubRaf = () => {
  const raf = (cb: (ts: number) => void) => {
    rafQueue.push(cb)
    return rafQueue.length
  }
  // 直接钉在 window 上（vitest jsdom 的 window 与 globalThis 属性非同一绑定）
  window.requestAnimationFrame = raf
  window.cancelAnimationFrame = vi.fn()
  vi.stubGlobal('requestAnimationFrame', raf)
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
}

const flushRaf = (times = 1) => {
  for (let i = 0; i < times; i += 1) {
    const queue = rafQueue
    rafQueue = []
    rafClock += 16
    queue.forEach((cb) => cb(rafClock))
  }
}

const flushMicrotasks = async () => {
  for (let i = 0; i < 4; i += 1) await Promise.resolve()
}

/** 驱动引擎 n 毫秒（模拟 AmbientBackground 的 rAF 总控，坑③：传 performance.now() 语义的时间戳） */
const driveEngine = (ms: number) => {
  const steps = Math.ceil(ms / 16.7)
  for (let i = 0; i < steps; i += 1) {
    engineClock += 16.7
    tickDust(Math.round(engineClock))
  }
}

const bodyClass = (name: string) => document.body.classList.contains(name)

const mountHost = () => {
  const Host = defineComponent({
    setup() {
      const intro = useIntroCeremony()
      return () =>
        h('div', { class: 'hp-hero' }, [
          h('p', { class: 'hp-kicker' }, '场地空间与活动平台'),
          h('h1', { class: 'hp-hero-title' }, [h('span', { class: 't1' }, '时间')]),
          h('p', { class: 'hp-hero-note' }, '城里的球场、泳馆与琴房，今天都空着几格。'),
          h('div', { class: 'hp-live' }, [
            h('div', { class: 'li' }, [h('span', { class: 'n' }, '4'), h('span', { class: 'lb' }, '今天可约场地')])
          ]),
          h('div', { class: 'hp-kinds' }, [
            h('a', { class: 'hp-kind' }, [h('span', { class: 'k-nm' }, '球馆'), h('span', { class: 'k-ct' }, '1 家')])
          ]),
          h('p', { class: 'intro-phase' }, String(intro.phase.value))
        ])
    }
  })
  return mount(Host, { attachTo: document.body })
}

describe('useIntroCeremony（V6-UI-UX-SHIGUANG S01 进场仪式控制器）', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    rafQueue = []
    rafClock = 0
    engineClock = 0
    stubRaf()
    fillCtx()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1000 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 })
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 })
    window.sessionStorage.clear()
    document.body.className = ''
    document.body.style.overflow = ''
    window.scrollTo = vi.fn()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    document.body.className = ''
    document.body.style.overflow = ''
    document.documentElement.classList.remove('no-motion')
    retireDust()
    unbindDustCanvas()
  })

  it('挂载即 armIntro：body.intro-wait + overflow hidden + 引擎回到漂浮态', () => {
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    const wrapper = mountHost()
    expect(bodyClass('intro-wait')).toBe(true)
    expect(document.body.style.overflow).toBe('hidden')
    expect(dustState()).toMatchObject({ mode: 'free', life: 1 })
    wrapper.unmount()
  })

  it('点击任意处开始聚形：intro-gather + 瞬时滚动后采样 → 粒子 flight', async () => {
    bindDustCanvas(document.createElement('canvas'))
    const wrapper = mountHost()
    document.body.click()
    expect(bodyClass('intro-wait')).toBe(false)
    expect(bodyClass('intro-gather')).toBe(true)
    expect(document.body.style.overflow).toBe('hidden')
    flushRaf(2)
    await flushMicrotasks()
    expect(dustState().mode).toBe('flight')
    wrapper.unmount()
  })

  it('聚形 2.0s 成形回调：reveal-hero 先亮 hero，+980ms intro-done 且写会话门', async () => {
    bindDustCanvas(document.createElement('canvas'))
    const wrapper = mountHost()
    document.body.click()
    flushRaf(2)
    await flushMicrotasks()
    driveEngine(2100) // 越过成形判定
    expect(bodyClass('reveal-hero')).toBe(true)
    vi.advanceTimersByTime(980)
    expect(bodyClass('intro-done')).toBe(true)
    expect(bodyClass('intro-gather')).toBe(false)
    expect(document.body.style.overflow).toBe('')
    expect(window.sessionStorage.getItem('sg-intro-played')).toBe('1')
    wrapper.unmount()
  })

  it('intro-done 后精确点击「时间」散开：scattered + burst 持续漂浮；再点任意处重聚', async () => {
    bindDustCanvas(document.createElement('canvas'))
    const wrapper = mountHost()
    document.body.click()
    flushRaf(2)
    await flushMicrotasks()
    driveEngine(2100)
    vi.advanceTimersByTime(980)
    const t1 = wrapper.get('.t1').element
    t1.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(bodyClass('scattered')).toBe(true)
    expect(dustState()).toMatchObject({ mode: 'release', life: 1 })
    driveEngine(1200)
    expect(dustState().mode).toBe('free')
    // 散开态点击其它区域 → 重聚
    document.body.click()
    expect(bodyClass('intro-gather')).toBe(true)
    wrapper.unmount()
  })

  it('8 秒未点自动聚形（护栏）', async () => {
    bindDustCanvas(document.createElement('canvas'))
    const wrapper = mountHost()
    expect(bodyClass('intro-wait')).toBe(true)
    vi.advanceTimersByTime(8000)
    expect(bodyClass('intro-gather')).toBe(true)
    flushRaf(2)
    await flushMicrotasks()
    expect(dustState().mode).toBe('flight')
    wrapper.unmount()
  })

  it('Esc 跳过：直达成品页（reveal-hero + intro-done + 彩蛋亮起）并交接退场', () => {
    bindDustCanvas(document.createElement('canvas'))
    const wrapper = mountHost()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(bodyClass('reveal-hero')).toBe(true)
    expect(bodyClass('intro-done')).toBe(true)
    expect(document.body.style.overflow).toBe('')
    expect(window.sessionStorage.getItem('sg-intro-played')).toBe('1')
    expect(dustState().mode).toBe('release')
    wrapper.unmount()
  })

  it('会话门：同会话第二次进首页不再重播', () => {
    bindDustCanvas(document.createElement('canvas'))
    window.sessionStorage.setItem('sg-intro-played', '1')
    const wrapper = mountHost()
    expect(bodyClass('intro-wait')).toBe(false)
    expect(bodyClass('intro-done')).toBe(false)
    wrapper.unmount()
  })

  it('reduced-motion 直接成品不播（无编排类、监听器不挂）', () => {
    bindDustCanvas(document.createElement('canvas'))
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true } as MediaQueryList)))
    const wrapper = mountHost()
    expect(bodyClass('intro-wait')).toBe(false)
    document.body.click()
    expect(bodyClass('intro-gather')).toBe(false)
    wrapper.unmount()
  })

  it('「动效 关」（html.no-motion）直接成品不播', () => {
    bindDustCanvas(document.createElement('canvas'))
    document.documentElement.classList.add('no-motion')
    const wrapper = mountHost()
    expect(bodyClass('intro-wait')).toBe(false)
    wrapper.unmount()
  })

  it('聚形 4.5s 未成形兜底跳过（resize 竞态护栏）', async () => {
    bindDustCanvas(document.createElement('canvas'))
    const wrapper = mountHost()
    document.body.click()
    flushRaf(2)
    await flushMicrotasks()
    // 不驱动引擎（模拟 resize 重播种打断成形）
    vi.advanceTimersByTime(4600)
    expect(bodyClass('intro-done')).toBe(true)
    expect(document.body.style.overflow).toBe('')
    wrapper.unmount()
  })

  it('卸载清理：编排类全清、粒子交接退场、溢出恢复', async () => {
    bindDustCanvas(document.createElement('canvas'))
    const wrapper = mountHost()
    document.body.click()
    flushRaf(2)
    await flushMicrotasks()
    wrapper.unmount()
    expect(bodyClass('intro-gather')).toBe(false)
    expect(bodyClass('intro-wait')).toBe(false)
    expect(bodyClass('intro-done')).toBe(false)
    expect(document.body.style.overflow).toBe('')
    expect(['release', 'free', 'idle']).toContain(dustState().mode)
  })

  it('引擎未绑定（无 AmbientBackground 环境）时完全不介入', () => {
    const wrapper = mountHost()
    expect(bodyClass('intro-wait')).toBe(false)
    document.body.click()
    expect(bodyClass('intro-gather')).toBe(false)
    wrapper.unmount()
  })
})
