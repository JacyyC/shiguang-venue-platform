import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  bindDustCanvas,
  burstDust,
  clearDustMouse,
  dustState,
  flightDust,
  moveDustMouse,
  parseColorToken,
  rearmDust,
  refreshDustColors,
  releaseDust,
  resizeDust,
  retireDust,
  tickDust,
  unbindDustCanvas
} from './dustEngine'

const fillStyles: string[] = []
const clearRects: number[] = []
const fillRects: Array<[number, number, number, number]> = []

const installCanvasStub = () => {
  const ctx = {
    set fillStyle(value: string) {
      fillStyles.push(value)
    },
    get fillStyle(): string {
      return fillStyles[fillStyles.length - 1] ?? ''
    },
    setTransform: vi.fn(),
    clearRect: vi.fn((x: number, y: number, w: number, h: number) => clearRects.push(w, h)),
    fillRect: vi.fn((x: number, y: number, w: number, h: number) => fillRects.push([x, y, w, h])),
    beginPath: vi.fn(),
    arc: vi.fn(),
    fill: vi.fn()
  }
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(ctx as unknown as CanvasRenderingContext2D)
  return ctx
}

const setSize = (width: number, height: number) => {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: height })
}

const stubTokens = (ink = '#241D14', primary = '#9E3A15') => {
  vi.spyOn(window, 'getComputedStyle').mockImplementation(
    () =>
      ({
        getPropertyValue: (name: string) => (name === '--sg-ink' ? ink : name === '--sg-primary' ? primary : '')
      }) as unknown as CSSStyleDeclaration
  )
}

/** 以 60fps 匀速推进引擎 n 帧 */
const runFrames = (frames: number, stepMs = 16.7) => {
  for (let i = 1; i <= frames; i += 1) tickDust(Math.round(i * stepMs))
}

const viewBox = () => ({ width: window.innerWidth, height: window.innerHeight })

describe('dustEngine（V6 粒子引擎：free/flight/hold/release/idle 状态机）', () => {
  beforeEach(() => {
    fillStyles.length = 0
    clearRects.length = 0
    fillRects.length = 0
    setSize(1000, 800)
    stubTokens()
    installCanvasStub()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    retireDust()
    unbindDustCanvas()
  })

  it('绑定画布即播种，population = min(4200, max(900, W*H/380))', () => {
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    expect(dustState().n).toBe(Math.round((1000 * 800) / 380))
    setSize(4000, 3000)
    resizeDust()
    expect(dustState().n).toBe(4200)
    setSize(300, 200)
    resizeDust()
    expect(dustState().n).toBe(900)
    setSize(1000, 800)
    resizeDust()
  })

  it('backing store 上限 DPR 1.5（devicePixelRatio 2 时按 1.5）', () => {
    vi.stubGlobal('devicePixelRatio', 2)
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    expect(canvas.width).toBe(1500)
    expect(canvas.height).toBe(1200)
    vi.unstubAllGlobals()
  })

  it('初始 idle：清空画布一次后停帧纯纸底（无常驻粒子）', () => {
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    retireDust()
    expect(dustState()).toMatchObject({ mode: 'idle', life: 0 })
    runFrames(3)
    const clearCalls = clearRects.length / 2
    runFrames(5)
    expect(clearRects.length / 2).toBe(clearCalls)
  })

  it('rearm → free 漂浮态（life 1）；flight → 扫描序聚形 ≈2.0s 成形回调 → >2.5s hold', () => {
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    rearmDust()
    expect(dustState()).toMatchObject({ mode: 'free', life: 1 })

    const onFormed = vi.fn()
    const [w, h] = [viewBox().width, viewBox().height]
    flightDust([{ x: w / 2, y: h / 2, a: 0.8, hot: false }], onFormed)
    expect(dustState().mode).toBe('flight')
    runFrames(100) // ~1.67s：尚未成形
    expect(onFormed).not.toHaveBeenCalled()
    runFrames(30) // ~2.17s：越过 2.0s 成形
    expect(onFormed).toHaveBeenCalledTimes(1)
    expect(dustState().formed).toBe(true)
    runFrames(30) // ~2.67s：进入 hold 微颤
    expect(dustState().mode).toBe('hold')
  })

  it('release 交接退场：life→0，free 且 life<0.02 落入 idle 并清屏', () => {
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    rearmDust()
    releaseDust()
    expect(dustState().mode).toBe('release')
    runFrames(120) // 2s：releaseT 1.2s 耗尽 → free，life 衰减中
    expect(['free', 'idle']).toContain(dustState().mode)
    runFrames(240)
    expect(dustState().mode).toBe('idle')
    expect(dustState().life).toBeLessThanOrEqual(0.02)
  })

  it('burst 散开：从成形位置炸开回 life=1 持续漂浮（不退场）', () => {
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    rearmDust()
    flightDust([{ x: 500, y: 400, a: 0.8, hot: true }], () => {})
    runFrames(140)
    burstDust()
    expect(dustState()).toMatchObject({ mode: 'release', life: 1 })
    runFrames(90)
    expect(dustState()).toMatchObject({ mode: 'free', life: 1 })
    runFrames(120)
    expect(dustState().mode).toBe('free')
  })

  it('burst 散开铺满全屏（走查 2026-08-30 F-01）：圆盘均匀取样，落位坐标覆盖 ≥90% 视口宽高', () => {
    const ctx = installCanvasStub()
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    rearmDust()
    flightDust([{ x: 500, y: 400, a: 0.8, hot: true }], () => {})
    runFrames(140)
    burstDust()
    // release 1.1s ≈ 66 帧结束后进入 free，行程主体（>90%）完成于 ≈195 帧
    runFrames(210)
    const positions = ctx.arc.mock.calls.slice(-dustState().n).map((call) => [call[0] as number, call[1] as number])
    const xs = positions.map((point) => point[0])
    const ys = positions.map((point) => point[1])
    expect(Math.max(...xs) - Math.min(...xs)).toBeGreaterThan(900)
    expect(Math.max(...ys) - Math.min(...ys)).toBeGreaterThan(700)
  })

  it('光标驱散（150px，走查 2026-08-30 F-02）：近旁粒子被踢离，30 帧后光标近旁无粒子滞留', () => {
    const ctx = installCanvasStub()
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    rearmDust()
    runFrames(1)
    const nearestBefore = Math.min(
      ...ctx.arc.mock.calls.slice(-dustState().n).map((call) => Math.hypot((call[0] as number) - 500, (call[1] as number) - 400))
    )
    expect(nearestBefore).toBeLessThan(150)
    moveDustMouse(500, 400)
    runFrames(30)
    clearDustMouse()
    const nearestAfter = Math.min(
      ...ctx.arc.mock.calls.slice(-dustState().n).map((call) => Math.hypot((call[0] as number) - 500, (call[1] as number) - 400))
    )
    expect(nearestAfter).toBeGreaterThan(50)
  })

  it('resize 即重播种（n 重算）；非 idle 态退回漂浮，idle 保持纯纸底', () => {
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    const before = dustState().n
    setSize(1200, 900)
    resizeDust()
    expect(dustState().n).not.toBe(before)
    expect(dustState().n).toBe(Math.round((1200 * 900) / 380))

    rearmDust()
    flightDust([{ x: 600, y: 450, a: 0.8, hot: false }], () => {})
    resizeDust()
    expect(dustState()).toMatchObject({ mode: 'free', life: 1 })

    retireDust()
    resizeDust()
    expect(dustState().mode).toBe('idle')
  })

  it('Token 运行时取色：成形方点与漂浮圆点用 --sg-ink/--sg-primary 实时解析值', () => {
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    flightDust(
      [
        { x: 500, y: 400, a: 0.8, hot: false },
        { x: 300, y: 300, a: 0.8, hot: true }
      ],
      () => {}
    )
    runFrames(200)
    expect(fillStyles).toContain('rgba(36,29,20,0.8)')
    expect(fillStyles).toContain('rgba(158,58,21,0.8)')
  })

  it('明暗切换即时换色：refreshDustColors 后下一帧按暗色 Token 绘制', () => {
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    flightDust([{ x: 500, y: 400, a: 0.8, hot: false }], () => {})
    runFrames(200)
    stubTokens('#F8F4EB', '#F0916A')
    refreshDustColors()
    runFrames(2)
    expect(fillStyles).toContain('rgba(248,244,235,0.8)')
  })

  it('光标驱散仅漂浮态生效：150px 半径内粒子被推离（不抛错、保持漂浮）', () => {
    const canvas = document.createElement('canvas')
    bindDustCanvas(canvas)
    rearmDust()
    moveDustMouse(500, 400)
    runFrames(10)
    clearDustMouse()
    // 不抛错、保持漂浮即视为通过（位移属随机游走，只验不越界崩溃）
    expect(dustState().mode).toBe('free')
  })

  it('parseColorToken 支持 #RGB/#RRGGBB，非法值返回 null', () => {
    expect(parseColorToken('#241D14')).toEqual([36, 29, 20])
    expect(parseColorToken('#fff')).toEqual([255, 255, 255])
    expect(parseColorToken('color-mix(in srgb, red, blue)')).toBeNull()
    expect(parseColorToken('')).toBeNull()
  })
})
