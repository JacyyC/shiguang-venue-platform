import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import {
  burstDust,
  flightDust,
  hasDustCanvas,
  parseColorToken,
  rearmDust,
  releaseDust,
  type DustTargetPoint
} from '@/components/ambient/dustEngine'

/**
 * 进场仪式控制器（V6-UI-UX-SHIGUANG S01，视觉唯一权威 design-preview.html §01 定稿）。
 *
 * 状态机：intro-wait（空白进场，满屏漂浮墨尘 + 呼吸引导钮）→ 点击 / 8s 自动 → intro-gather
 * （瞬时滚动到 hero 锚点、双 rAF 后采样真实 DOM 版面 → 粒子按扫描序打印式聚形 ≈2.0s）
 * → reveal-hero（真字 .55s 淡入；+360ms 渐变流动 + 时钟彩蛋；+430ms 粒子交接退场）
 * → +980ms intro-done（其余内容按 --sg-irvd 分层浮现；粒子退场后纯纸底）。
 * intro-done 后精确点击「时间」大字 → scattered（炸开回满屏漂浮态）→ 再点任意处重聚（同流程）。
 *
 * 护栏：8 秒未点自动聚形 / Esc 跳过（直达成品页，含彩蛋与渐变）/ reduced-motion 与
 * 「动效 关」直接成品不播 / 会话门（sessionStorage，同会话第二次进首页不重播）/
 * 聚形 4.5s 未成形兜底跳过（resize 等竞态）。
 * 已登记坑位：瞬时滚动覆盖 scroll-behavior:smooth 并等双 rAF 后采样（坑②）；
 * 总控 rAF 必须传 performance.now()（坑③）；真实 DOM 文本始终在 DOM（粒子层 aria-hidden，
 * 进场期仅视觉隐藏，不损害可选中/朗读/SEO）。
 */

export type IntroPhase = 'done' | 'wait' | 'gather' | 'scattered'

const SESSION_KEY = 'sg-intro-played'
const AUTO_GATHER_MS = 8000
/** 聚形兜底：flight 2.0s 成形 + 余量；超时未成形（resize 重播种等竞态）直达成品 */
const GATHER_WATCHDOG_MS = 4500
/** finish 后分层浮现窗口：最大 --sg-irvd(860ms) + 过渡(700ms) + 余量，之后回常规 --rvd 滚动渐入 */
const REVEAL_SETTLE_MS = 2000
/** 瞬时滚动的页头让位：与 html scroll-padding-top 同源（页头 68 + 8） */
const HEADER_CLEARANCE = 76

const HINT_LABEL_WAIT = '触一下，让时间聚形'
const HINT_LABEL_REGATHER = '再聚一次'

const INTRO_BODY_CLASSES = ['intro-wait', 'intro-gather', 'scattered', 'reveal-hero', 'intro-done', 'intro-reveal']

/** hero 语气层字体自托管子集按需加载：聚形采样必须用真字形的实测字距（零外链，V4 资产沿用；
    V11 A3 全同步后 hero 语气层走志莽行书，站酷小薇仍服务其余展示层） */
async function ensureDisplayFonts(): Promise<void> {
  const fonts = (document as Document & { fonts?: FontFaceSet }).fonts
  if (!fonts) return
  try {
    await Promise.race([
      Promise.all([
        fonts.load('400 100px "Zhi Mang Xing Web"', '时间自有去处把下一段时间先留给自己城里的球场泳馆与琴房今天都空着几格场地空间与活动平台「」今日可约个预约学习运动和管理自己的订单0123456789：'),
        fonts.load('400 100px "ZCOOL XiaoWei Web"', '时间自有去处0123456789：')
      ]),
      new Promise<void>((resolve) => setTimeout(resolve, 900))
    ])
  } catch {
    // 字体加载失败不阻断进场：采样会以当前渲染字体实测，仍与 DOM 呈现一致
  }
}

const pxValue = (raw: string | null | undefined) => Number.parseFloat(raw || '') || 0

/** 采样目标：与预览 §01 buildTargets 同构，选择器为产品 DOM（S01 hero B 版式） */
const PART_TARGETS: Array<[selector: string, mode: 'parts' | 'wrapped']> = [
  ['.hp-kicker', 'parts'],
  ['.hp-hero-title .t1', 'parts'],
  ['.egg .clk', 'parts'],
  ['.egg .egg-lb', 'parts'],
  ['.hp-hero-title .t2', 'parts'],
  ['.hp-hero-quote', 'wrapped'],
  ['.hp-hero-note', 'wrapped']
]

/**
 * 采样真实 DOM 版面 → 粒子目标点：把目标元素的文字按计算样式重画到视口尺寸的离屏画布，
 * 再按 3px 网格扫描出墨点。进场期滚动锁 0，视口坐标即可。
 * hot 判定按「离主色近还是离墨色近」的色距（预览的 r/g 阈值只适配亮色，暗色主色 #F0916A
 * g=145 会漏判；色距法明暗同构，结论登记 Evidence）。
 */
function buildTargets(): DustTargetPoint[] {
  const viewWidth = Math.max(1, window.innerWidth)
  const viewHeight = Math.max(1, window.innerHeight)
  const off = document.createElement('canvas')
  off.width = viewWidth
  off.height = viewHeight
  const context = off.getContext('2d')
  if (!context) return []
  context.textBaseline = 'middle'

  const rootStyle = getComputedStyle(document.documentElement)
  const primaryRgb = parseColorToken(rootStyle.getPropertyValue('--sg-primary')) ?? [158, 58, 21]
  const inkRgb = parseColorToken(rootStyle.getPropertyValue('--sg-ink')) ?? [36, 29, 20]
  const colorDist = (r: number, g: number, b: number, rgb: [number, number, number]) =>
    (r - rgb[0]) ** 2 + (g - rgb[1]) ** 2 + (b - rgb[2]) ** 2

  const fontOf = (el: Element) => {
    const style = getComputedStyle(el)
    return `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
  }
  const centerOf = (el: Element): [number, number] => {
    const rect = el.getBoundingClientRect()
    return [rect.left + rect.width / 2, rect.top + rect.height / 2]
  }
  /** 逐字绘制（含 <b>/<em> 等彩色子节点，letter-spacing 逐字累加） */
  const drawParts = (el: Element, x: number, y: number) => {
    const style = getComputedStyle(el)
    const letterSpacing = pxValue(style.letterSpacing)
    const parts: Array<{ text: string; color: string }> = []
    el.childNodes.forEach((node) => {
      const text = node.textContent || ''
      if (!text.trim()) return
      parts.push({
        text,
        color: node.nodeType === Node.ELEMENT_NODE ? getComputedStyle(node as Element).color : style.color
      })
    })
    context.font = fontOf(el)
    const widths = parts.map((part) => {
      let width = 0
      for (const ch of part.text) width += context.measureText(ch).width + letterSpacing
      return width
    })
    const total = widths.reduce((sum, w) => sum + w, 0) - letterSpacing
    let cursor = x - total / 2
    context.textAlign = 'left'
    parts.forEach((part) => {
      context.fillStyle = part.color
      for (const ch of part.text) {
        if (ch !== ' ') context.fillText(ch, cursor, y)
        cursor += context.measureText(ch).width + letterSpacing
      }
    })
  }
  /** 说明性长文按实际换行绘制（40ch lead 的多行版面） */
  const drawWrapped = (el: Element) => {
    const style = getComputedStyle(el)
    const rect = el.getBoundingClientRect()
    context.font = fontOf(el)
    const lineHeight = pxValue(style.lineHeight) || pxValue(style.fontSize) * 1.9
    const text = el.textContent || ''
    let line = ''
    const lines: Array<{ text: string; y: number }> = []
    let y = rect.top + lineHeight / 2
    for (const ch of text) {
      const candidate = line + ch
      if (context.measureText(candidate).width > rect.width && line) {
        lines.push({ text: line, y })
        line = ch
        y += lineHeight
      } else {
        line = candidate
      }
    }
    if (line) lines.push({ text: line, y })
    context.textAlign = 'center'
    context.fillStyle = style.color
    lines.forEach((row) => context.fillText(row.text, rect.left + rect.width / 2, row.y))
  }

  PART_TARGETS.forEach(([selector, mode]) => {
    const el = document.querySelector(selector)
    if (!el) return
    const [x, y] = centerOf(el)
    if (mode === 'wrapped') drawWrapped(el)
    else drawParts(el, x, y)
  })
  document.querySelectorAll('.hp-live .li').forEach((row) => {
    const num = row.querySelector('.n')
    const label = row.querySelector('.lb')
    if (num) {
      const [x, y] = centerOf(num)
      drawParts(num, x, y)
    }
    if (label) {
      const [x, y] = centerOf(label)
      drawParts(label, x, y)
    }
  })
  document.querySelectorAll('.hp-kind').forEach((kind) => {
    ;['.k-nm', '.k-ct'].forEach((selector) => {
      const el = kind.querySelector(selector)
      if (!el) return
      const [x, y] = centerOf(el)
      drawParts(el, x, y)
    })
  })

  const data = context.getImageData(0, 0, viewWidth, viewHeight).data
  const targets: DustTargetPoint[] = []
  for (let y = 0; y < viewHeight; y += 3) {
    for (let x = 0; x < viewWidth; x += 3) {
      const i = (y * viewWidth + x) * 4
      if (data[i + 3] <= 110) continue
      const r = data[i]
      const g = data[i + 1]
      targets.push({
        x,
        y,
        hot: colorDist(r, g, data[i + 2], primaryRgb) < colorDist(r, g, data[i + 2], inkRgb),
        a: Math.min(0.85, (data[i + 3] / 255) * 0.9 + 0.12)
      })
    }
  }
  return targets
}

/** 瞬时滚动覆盖 scroll-behavior:smooth（坑②）：只允许 auto，禁止平滑途中测量 */
function instantScrollTo(y: number): void {
  const root = document.documentElement
  const previous = root.style.scrollBehavior
  root.style.scrollBehavior = 'auto'
  window.scrollTo(0, Math.max(0, y))
  root.style.scrollBehavior = previous
}

const doubleRaf = () =>
  new Promise<void>((resolve) => {
    window.requestAnimationFrame(() => window.requestAnimationFrame(() => resolve()))
  })

export interface UseIntroCeremonyReturn {
  phase: Ref<IntroPhase>
  heroLit: Ref<boolean>
  hintLabel: Ref<string>
}

/**
 * 挂载于 HomeView：进场仪式全编排。画布未绑定（如单测环境无 AmbientBackground）时
 * 完全不介入，页面保持常规渲染；会话门命中 / reduced-motion / 「动效 关」直接成品不播。
 */
export function useIntroCeremony(): UseIntroCeremonyReturn {
  const phase = ref<IntroPhase>('done')
  const heroLit = ref(false)
  const hintLabel = computed(() => (phase.value === 'scattered' ? HINT_LABEL_REGATHER : HINT_LABEL_WAIT))

  let gatherLock = false
  let disposed = false
  let autoTimer: number | undefined
  let gatherWatchdog: number | undefined
  let settleTimer: number | undefined
  let handoffTimers: number[] = []
  let t1Element: Element | null = null

  const body = () => document.body

  const introStep = (step: string) => {
    const host = window as unknown as Record<string, unknown>
    const steps = (host.__sgIntroSteps as string[] | undefined) ?? []
    steps.push(`${step}@${Math.round(performance.now())}`)
    host.__sgIntroSteps = steps
  }

  const setBodyState = (add: string[], remove: string[]) => {
    const classes = body().classList
    remove.forEach((name) => classes.remove(name))
    add.forEach((name) => classes.add(name))
  }

  const clearTimers = () => {
    if (autoTimer !== undefined) window.clearTimeout(autoTimer)
    if (gatherWatchdog !== undefined) window.clearTimeout(gatherWatchdog)
    autoTimer = undefined
    gatherWatchdog = undefined
  }

  const afterMs = (delay: number, run: () => void) => {
    const id = window.setTimeout(() => {
      handoffTimers = handoffTimers.filter((timer) => timer !== id)
      run()
    }, delay)
    handoffTimers.push(id)
  }

  const resetHero = () => {
    heroLit.value = false
  }

  const lightHero = () => {
    heroLit.value = true
  }

  /** 瞬时滚到 hero 锚点：整段 hero（五段＋实况＋四格）入视口后再采样 */
  const scrollToHeroAnchor = () => {
    const anchor = document.querySelector('.hp-hero')
    const top = anchor ? anchor.getBoundingClientRect().top + window.scrollY - HEADER_CLEARANCE : 0
    instantScrollTo(top)
  }

  const finish = () => {
    setBodyState(['intro-done', 'intro-reveal'], ['intro-wait', 'intro-gather', 'scattered', 'reveal-hero'])
    body().style.overflow = ''
    phase.value = 'done'
    gatherLock = false
    try {
      window.sessionStorage.setItem(SESSION_KEY, '1')
    } catch {
      // 隐私模式等场景下会话门失效，仅导致同会话重播，不阻断
    }
    if (settleTimer !== undefined) window.clearTimeout(settleTimer)
    settleTimer = window.setTimeout(() => body().classList.remove('intro-reveal'), REVEAL_SETTLE_MS)
  }

  /** Esc / 兜底：直达成品页（含渐变流动与时钟彩蛋），粒子交接退场 */
  const skipAll = () => {
    clearTimers()
    resetHero()
    setBodyState(['reveal-hero', 'intro-done', 'intro-reveal'], ['intro-wait', 'intro-gather', 'scattered'])
    body().style.overflow = ''
    instantScrollTo(0)
    lightHero()
    releaseDust()
    phase.value = 'done'
    gatherLock = false
    try {
      window.sessionStorage.setItem(SESSION_KEY, '1')
    } catch {
      // 同上：会话门持久化失败不阻断
    }
    if (settleTimer !== undefined) window.clearTimeout(settleTimer)
    settleTimer = window.setTimeout(() => body().classList.remove('intro-reveal'), REVEAL_SETTLE_MS)
  }

  const onFormed = () => {
    if (gatherWatchdog !== undefined) window.clearTimeout(gatherWatchdog)
    gatherWatchdog = undefined
    body().classList.add('reveal-hero')
    afterMs(360, () => lightHero())
    afterMs(430, () => releaseDust())
    afterMs(980, () => finish())
  }

  const beginGather = () => {
    if (gatherLock) return
    if (phase.value !== 'wait' && phase.value !== 'scattered') return
    gatherLock = true
    clearTimers()
    phase.value = 'gather'
    setBodyState(['intro-gather'], ['intro-wait', 'scattered', 'intro-done', 'reveal-hero', 'intro-reveal'])
    body().style.overflow = 'hidden'
    instantScrollTo(0)
    resetHero()
    window
      .requestAnimationFrame(() => {
        if (import.meta.env.DEV) introStep('raf0')
        window.requestAnimationFrame(() => {
          if (import.meta.env.DEV) introStep('raf1')
          void ensureDisplayFonts()
            .then(() => {
              if (import.meta.env.DEV) introStep('fonts')
              if (disposed) return
              scrollToHeroAnchor()
              const targets = buildTargets()
              if (import.meta.env.DEV) {
                ;(window as unknown as Record<string, unknown>).__sgIntroSample = { count: targets.length }
              }
              gatherWatchdog = window.setTimeout(() => {
                if (phase.value === 'gather') skipAll()
              }, GATHER_WATCHDOG_MS)
              flightDust(targets, onFormed)
            })
            .catch((error) => {
              if (import.meta.env.DEV) {
                ;(window as unknown as Record<string, unknown>).__sgIntroErr = String(error?.stack || error)
              }
              skipAll()
            })
        })
      })
  }

  const scatter = () => {
    phase.value = 'scattered'
    setBodyState(['scattered'], ['reveal-hero', 'intro-done', 'intro-reveal'])
    // 散开态与 wait/gather 同为整屏密封态（内容全隐、仅星尘 + 引导钮），锁滚动防下滑穿帮
    body().style.overflow = 'hidden'
    resetHero()
    burstDust()
  }

  const armIntro = () => {
    resetHero()
    rearmDust()
    setBodyState(['intro-wait'], ['intro-done', 'reveal-hero', 'scattered', 'intro-gather', 'intro-reveal'])
    body().style.overflow = 'hidden'
    instantScrollTo(0)
    phase.value = 'wait'
    gatherLock = false
    clearTimers()
    autoTimer = window.setTimeout(() => beginGather(), AUTO_GATHER_MS)
  }

  const onDocumentClick = (event: MouseEvent) => {
    if (phase.value === 'wait' || phase.value === 'scattered') {
      beginGather()
      return
    }
    if (phase.value === 'done') {
      if (!t1Element) t1Element = document.querySelector('.hp-hero-title .t1')
      const target = event.target
      if (t1Element && target instanceof Node && (target === t1Element || t1Element.contains(target))) {
        scatter()
      }
    }
  }

  const onKeydown = (event: KeyboardEvent) => {
    if (event.key !== 'Escape') return
    if (phase.value === 'wait' || phase.value === 'gather') skipAll()
  }

  onMounted(() => {
    // 引擎画布未绑定（无 AmbientBackground 的环境）时不介入
    if (!hasDustCanvas()) return
    const reduced =
      typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const noMotion = document.documentElement.classList.contains('no-motion')
    let replayed = false
    try {
      replayed = window.sessionStorage.getItem(SESSION_KEY) === '1'
    } catch {
      replayed = false
    }
    if (reduced || noMotion || replayed) {
      // 会话门/降级直达成品页：时钟彩蛋必须亮、散开入口必须可用，否则二次进站后
      // hero 永久处于「无彩蛋且点不动」的半成品态（走查 2026-08-30 F-01）。
      phase.value = 'done'
      lightHero()
      document.addEventListener('click', onDocumentClick, true)
      return
    }
    document.addEventListener('click', onDocumentClick, true)
    document.addEventListener('keydown', onKeydown)
    armIntro()
  })

  onBeforeUnmount(() => {
    disposed = true
    clearTimers()
    if (settleTimer !== undefined) window.clearTimeout(settleTimer)
    settleTimer = undefined
    handoffTimers.forEach((timer) => window.clearTimeout(timer))
    handoffTimers = []
    document.removeEventListener('click', onDocumentClick, true)
    document.removeEventListener('keydown', onKeydown)
    // 任何阶段离页：粒子交接退场 + 编排类清理，目标页面回归纯纸底常规渲染
    releaseDust()
    const classes = body().classList
    INTRO_BODY_CLASSES.forEach((name) => classes.remove(name))
    body().style.overflow = ''
    phase.value = 'done'
  })

  return { phase, heroLit, hintLabel }
}
