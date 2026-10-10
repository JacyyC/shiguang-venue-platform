/**
 * 拾光墨尘粒子引擎（V6-UI-UX-SHIGUANG S01，视觉唯一权威 design-preview.html §01 定稿）。
 *
 * 单例画布引擎，承担两态：
 * - 漂浮态（开场 / 散开态）：B2 微尘——0.55~1.3px 圆点、α 0.08~0.18、极慢漂移（±0.08/帧）、
 *   光标 150px 内驱散让位（速度踢 + 近旁轻推，上限 3.2px/帧，走查 2026-08-30 F-02）；数量
 *   min(4200, max(900, W*H/380))、DPR ≤1.5。
 * - 聚形态（仅进场）：B5 质感——1.6px 方形墨点、α 0.8、按目标扫描序打印式成形（wait 期原位微漂），
 *   成形期 ±0.4px 正弦微颤；交接 release 后 life→0 整体退场至 idle 画布清空（纯纸底，无常驻粒子）。
 * 散开（burst）从成形字形炸开并按圆盘均匀取样铺满全屏（见 burstDust 注）后回到 life=1 持续漂浮态。
 *
 * 颜色纪律：一律运行时解析主题 Token（--sg-ink / --sg-primary），明暗切换后下一帧即时换色，
 * 源码零硬编码色值。rAF 总控由 AmbientBackground.vue 驱动（tick 必须传 performance.now()，
 * 否则 dt=NaN 污染坐标——V6 登记坑③）；reduced-motion / 动效关 / 页面隐藏的停帧纪律由
 * useAmbientPause 统一落实，reduced-motion 下进场仪式直接成品、引擎保持 idle 不播种。
 */

export type DustMode = 'idle' | 'free' | 'flight' | 'hold' | 'release'

export interface DustTargetPoint {
  x: number
  y: number
  /** 目标点墨色 α（采样自真实 DOM 文字） */
  a: number
  /** 主色（柿橙）目标点 */
  hot: boolean
}

export interface DustState {
  mode: DustMode
  n: number
  formed: boolean
  life: number
}

interface DustDot {
  x: number
  y: number
  vx: number
  vy: number
  /** 漂浮态圆点半径（0.55~1.3） */
  r: number
  /** 漂浮态主色点（5%） */
  hot: boolean
  /** 聚形目标主色 */
  hotT: boolean
  tx: number
  ty: number
  /** 聚形目标 α（0.8 档，随目标点实测） */
  ca: number
  has: boolean
  /** 0=漂浮圆点 → 1=成形方点的混合量 */
  mix: number
  /** 扫描序起飞延迟（s） */
  wait: number
  seed: number
}

type Rgb = [number, number, number]

const MAX_DPR = 1.5
/** 成形 α 基准（预览 §01 引擎 ca 常量） */
const GATHER_ALPHA = 0.8
/** 聚形全部落位判定时长（s）与 hold 交接时长（s），预览原值 */
const FLIGHT_FORMED_AT = 2.0
const FLIGHT_HOLD_AT = 2.5

let canvas: HTMLCanvasElement | null = null
let ctx: CanvasRenderingContext2D | null = null
let width = 0
let height = 0
let dots: DustDot[] = []
let mode: DustMode = 'idle'
let life = 0
let lifeTarget = 0
let flightT = 0
let releaseT = 0
let formed = false
let cleared = true
let onFormed: (() => void) | null = null
let lastTs = 0
let inkRgb: Rgb = [36, 29, 20]
let primaryRgb: Rgb = [158, 58, 21]

const randomOf = (min: number, max: number) => min + Math.random() * (max - min)

/** 解析 Token 色值（亮暗主题下 --sg-ink/--sg-primary 均为 hex 字面量；失败返回 null 由调用方兜底） */
export function parseColorToken(raw: string | null | undefined): Rgb | null {
  if (!raw) return null
  const value = raw.trim()
  const hex = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.exec(value)
  if (!hex) return null
  const body = hex[1]
  const full = body.length === 3 ? body.split('').map((ch) => ch + ch).join('') : body
  return [parseInt(full.slice(0, 2), 16), parseInt(full.slice(2, 4), 16), parseInt(full.slice(4, 6), 16)]
}

const readToken = (name: string): Rgb | null => {
  if (typeof window === 'undefined' || typeof document === 'undefined') return null
  const raw = window.getComputedStyle(document.documentElement).getPropertyValue(name)
  return parseColorToken(raw)
}

/** 运行时取色：每次明暗切换（或绑定画布）调用，下一帧起全部粒子换色 */
export function refreshDustColors(): void {
  inkRgb = readToken('--sg-ink') ?? inkRgb
  primaryRgb = readToken('--sg-primary') ?? primaryRgb
}

const rgbaOf = (rgb: Rgb, alpha: number) => `rgba(${rgb[0]},${rgb[1]},${rgb[2]},${alpha})`

export const hasDustCanvas = (): boolean => Boolean(canvas && ctx)

const newDot = (): DustDot => ({
  x: Math.random() * width,
  y: Math.random() * height,
  vx: (Math.random() - 0.5) * 0.16,
  vy: (Math.random() - 0.5) * 0.16,
  r: 0.55 + Math.random() * 0.75,
  hot: Math.random() < 0.05,
  hotT: false,
  tx: 0,
  ty: 0,
  ca: GATHER_ALPHA,
  has: false,
  mix: 0,
  wait: 0,
  seed: Math.random() * 6.283
})

/** 播种：数量按人口公式，resize 即重播种（预览 §01 size() 同款行为） */
const seed = () => {
  const count = Math.round(Math.min(4200, Math.max(900, (width * height) / 380)))
  dots = Array.from({ length: count }, newDot)
}

export function bindDustCanvas(target: HTMLCanvasElement): void {
  canvas = target
  ctx = target.getContext('2d')
  lastTs = 0
  refreshDustColors()
  resizeDust()
}

/** 解绑画布（宿主卸载 / 测试隔离）：引擎回到未装配态 */
export function unbindDustCanvas(): void {
  canvas = null
  ctx = null
  dots = []
  mode = 'idle'
  life = 0
  lifeTarget = 0
  formed = false
  onFormed = null
  lastTs = 0
}

/** 尺寸变化：重建画布背衬并重播种；进行中的聚形退回漂浮态（控制器有兜底护栏） */
export function resizeDust(): void {
  if (!canvas || !ctx) return
  const dpr = Math.min(window.devicePixelRatio || 1, MAX_DPR)
  width = window.innerWidth
  height = window.innerHeight
  canvas.width = Math.round(width * dpr)
  canvas.height = Math.round(height * dpr)
  canvas.style.width = `${width}px`
  canvas.style.height = `${height}px`
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  seed()
  if (mode !== 'idle') {
    mode = 'free'
    life = 1
    lifeTarget = 1
    formed = false
    onFormed = null
  }
  cleared = false
}

/** 开场 / 重聚：回到满屏漂浮墨尘态（intro-wait 的视觉底） */
export function rearmDust(): void {
  if (!ctx) return
  seed()
  mode = 'free'
  life = 1
  lifeTarget = 1
  formed = false
  cleared = false
}

/** 交接退场：粒子整体淡出（life→0）至 idle 清空画布 */
export function releaseDust(): void {
  if (!ctx || mode === 'idle') return
  mode = 'release'
  releaseT = 1.2
  formed = false
  lifeTarget = 0
  for (const dot of dots) {
    dot.vx = (Math.random() - 0.5) * 0.55
    dot.vy = (Math.random() - 0.5) * 0.55
  }
}

/**
 * 散开：从成形字形炸开 → 铺满全屏的持续漂浮态（走查 2026-08-30 F-01）。
 * 终点按「圆盘均匀分布」取样（d = Dmax·√u，方向均匀随机）：端点密度与半径无关，
 * 保证散开后墨尘覆盖全屏而不是聚在原字形附近；Dmax 取质心到视口最远角 ×1.05，
 * 配合 ±6 边界环视，四角亦有着墨。初速按逐帧摩擦积分校准（release 0.985 与 free 0.99
 * 的总行程 ≈79×v0，与帧率无关，120Hz 只缩短墙钟时长），粒子按各自行程渐次落位。
 */
const BURST_TRAVEL_FRAMES = 79

export function burstDust(): void {
  if (!ctx) return
  mode = 'release'
  releaseT = 1.1
  life = 1
  lifeTarget = 1
  formed = false
  cleared = false
  let cx = 0
  let cy = 0
  let gathered = 0
  for (const dot of dots) {
    if (!dot.has) continue
    cx += dot.x
    cy += dot.y
    gathered += 1
  }
  if (gathered > 0) {
    cx /= gathered
    cy /= gathered
  } else {
    cx = width / 2
    cy = height / 2
  }
  const reach = Math.hypot(Math.max(cx, width - cx), Math.max(cy, height - cy)) * 1.05
  for (const dot of dots) {
    if (!dot.has) continue
    const angle = Math.random() * 6.283
    const speed = (reach * Math.sqrt(Math.random())) / BURST_TRAVEL_FRAMES
    dot.vx = Math.cos(angle) * speed
    dot.vy = Math.sin(angle) * speed
  }
}

/** 聚形：把粒子按扫描序派往目标点（wait=(i/n)*0.95s + rand*0.06），全部落位（≈2.0s）回调 */
export function flightDust(targets: DustTargetPoint[], callback: () => void): void {
  if (!ctx || !targets.length) return
  const n = dots.length
  const m = targets.length
  const stride = m / n
  for (let i = 0; i < n; i += 1) {
    const target = targets[Math.floor(i * stride) % m]
    const dot = dots[i]
    dot.tx = target.x + (Math.random() - 0.5) * 0.8
    dot.ty = target.y + (Math.random() - 0.5) * 0.8
    dot.ca = target.a
    dot.hotT = target.hot
    dot.has = true
    dot.mix = 0
    dot.wait = (i / n) * 0.95 + Math.random() * 0.06
  }
  mode = 'flight'
  flightT = 0
  formed = false
  life = 1
  lifeTarget = 1
  cleared = false
  onFormed = callback
}

/** 收尾：立即进入纯纸底（HomeView 卸载等场景） */
export function retireDust(): void {
  mode = 'idle'
  life = 0
  lifeTarget = 0
  formed = false
  onFormed = null
}

export const dustState = (): DustState => ({ mode, n: dots.length, formed, life: Number(life.toFixed(2)) })

const mouse = { x: -9999, y: -9999 }

/** 光标驱散参数（走查 2026-08-30 F-02）：半径内按 t² 衰减给远离速度踢 + 近旁位置轻推，
 *  速度上限钳制防光标追粒子无限累积；离开半径后由摩擦 0.99 自然回落漂浮。 */
const CURSOR_RADIUS = 150
const CURSOR_RADIUS2 = CURSOR_RADIUS * CURSOR_RADIUS
const CURSOR_KICK = 0.42
const CURSOR_NUDGE = 0.35
const DISPERSED_SPEED_CAP = 3.2

export const moveDustMouse = (x: number, y: number) => {
  mouse.x = x
  mouse.y = y
}

export const clearDustMouse = () => {
  mouse.x = -9999
  mouse.y = -9999
}

/** 每帧推进（总控必须传 performance.now()，坑③）；idle 且已清屏时快速返回 */
export function tickDust(nowMs: number): void {
  if (!ctx) return
  const delta = (nowMs - lastTs) / 1000
  lastTs = nowMs
  // 负增量（时钟回拨/重绑）按单帧步长兜底，避免 NaN/负 dt 污染坐标
  const dt = Math.min(0.05, Math.max(0, delta) || 0.016)
  if (mode === 'idle') {
    if (!cleared) {
      ctx.clearRect(0, 0, width, height)
      cleared = true
    }
    return
  }
  const tNow = nowMs / 1000
  life += (lifeTarget - life) * Math.min(1, dt * 2.1)

  if (mode === 'flight') {
    flightT += dt
    for (const dot of dots) {
      if (dot.wait > 0) {
        dot.wait -= dt
        dot.x += dot.vx * 0.4
        dot.y += dot.vy * 0.4
      } else {
        dot.mix += (1 - dot.mix) * Math.min(1, dt * 4.2)
        dot.x += (dot.tx - dot.x) * Math.min(1, dt * 4.6)
        dot.y += (dot.ty - dot.y) * Math.min(1, dt * 4.6)
      }
    }
    if (flightT > FLIGHT_FORMED_AT && !formed) {
      formed = true
      if (onFormed) {
        const callback = onFormed
        onFormed = null
        callback()
      }
    }
    if (flightT > FLIGHT_HOLD_AT) mode = 'hold'
  } else if (mode === 'hold') {
    for (const dot of dots) {
      if (!dot.has) continue
      dot.x += (dot.tx + Math.sin(tNow * 1.6 + dot.seed) * 0.4 - dot.x) * Math.min(1, dt * 2.6)
      dot.y += (dot.ty + Math.cos(tNow * 1.3 + dot.seed) * 0.4 - dot.y) * Math.min(1, dt * 2.6)
    }
  } else if (mode === 'release') {
    releaseT -= dt
    for (const dot of dots) {
      dot.mix -= dot.mix * Math.min(1, dt * 3)
      if (dot.mix < 0.02) {
        dot.has = false
        dot.mix = 0
      }
      dot.x += dot.vx
      dot.y += dot.vy
      dot.vx *= 0.985
      dot.vy *= 0.985
    }
    if (releaseT <= 0) mode = 'free'
  } else if (mode === 'free') {
    if (life < 0.02 && lifeTarget === 0) mode = 'idle'
    for (const dot of dots) {
      dot.x += dot.vx
      dot.y += dot.vy
      dot.vx += (Math.random() - 0.5) * 0.01
      dot.vy += (Math.random() - 0.5) * 0.01
      dot.vx *= 0.99
      dot.vy *= 0.99
    }
  }

  // 光标驱散仅漂浮态生效（150px 半径内让位）；边界环绕 ±6
  if (mode === 'free') {
    for (const dot of dots) {
      const dx = dot.x - mouse.x
      const dy = dot.y - mouse.y
      const d2 = dx * dx + dy * dy
      if (d2 > CURSOR_RADIUS2) continue
      const d = Math.sqrt(d2) || 1
      const t = (CURSOR_RADIUS - d) / CURSOR_RADIUS
      const f = t * t * CURSOR_KICK
      dot.vx += (dx / d) * f
      dot.vy += (dy / d) * f
      dot.x += (dx / d) * f * CURSOR_NUDGE
      dot.y += (dy / d) * f * CURSOR_NUDGE
      const speed2 = dot.vx * dot.vx + dot.vy * dot.vy
      if (speed2 > DISPERSED_SPEED_CAP * DISPERSED_SPEED_CAP) {
        const scale = DISPERSED_SPEED_CAP / Math.sqrt(speed2)
        dot.vx *= scale
        dot.vy *= scale
      }
    }
  }
  for (const dot of dots) {
    if (dot.x < -6) dot.x = width + 6
    if (dot.x > width + 6) dot.x = -6
    if (dot.y < -6) dot.y = height + 6
    if (dot.y > height + 6) dot.y = -6
  }
  drawDust()
}

const drawDust = () => {
  if (!ctx || mode === 'idle') return
  ctx.clearRect(0, 0, width, height)
  const globalLife = life
  for (let i = 0; i < dots.length; i += 1) {
    const dot = dots[i]
    if (dot.has && dot.mix > 0.5) {
      // 聚形态：1.6px 方形墨点（B5 质感）
      const alpha = dot.ca * globalLife
      if (alpha < 0.01) continue
      ctx.fillStyle = rgbaOf(dot.hotT ? primaryRgb : inkRgb, alpha)
      ctx.fillRect(dot.x - 0.8, dot.y - 0.8, 1.6, 1.6)
    } else {
      // 漂浮态：低α圆点（α 0.08~0.18），hot 点提亮
      const baseAlpha = 0.08 + (((i * 37) % 10) / 110)
      const alpha = (dot.has ? dot.ca * dot.mix + baseAlpha * (1 - dot.mix) : baseAlpha) * globalLife
      if (alpha < 0.01) continue
      ctx.beginPath()
      ctx.arc(dot.x, dot.y, dot.r, 0, 6.2832)
      const hot = dot.mix > 0.5 ? dot.hotT : dot.hot
      ctx.fillStyle = hot ? rgbaOf(primaryRgb, Math.min(0.6, alpha * 2)) : rgbaOf(inkRgb, alpha)
      ctx.fill()
    }
  }
}
