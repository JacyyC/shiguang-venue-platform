import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const readFrom = (rel: string): string => {
  for (const base of [process.cwd(), resolve(process.cwd(), 'frontend')]) {
    try {
      return readFileSync(resolve(base, rel), 'utf8')
    } catch {
      // 尝试下一个候选根（vitest 可从仓库根或 frontend 目录启动）
    }
  }
  throw new Error(`cannot locate ${rel}`)
}

const css = readFrom('src/styles/main.css')
const userLayout = readFrom('src/layouts/UserLayout.vue')

const lightBlock = css.match(/:root,\s*:root\[data-theme="light"\]\s*\{([\s\S]*?)\}/)?.[1] ?? ''
const darkBlock = css.match(/:root\[data-theme="dark"\]\s*\{([\s\S]*?)\}/)?.[1] ?? ''

const FAMILIES = ['primary', 'success', 'warning', 'danger', 'error', 'info'] as const
const SG_SOURCE: Record<(typeof FAMILIES)[number], string> = {
  primary: '--sg-primary',
  success: '--sg-success',
  warning: '--sg-warning',
  danger: '--sg-danger',
  error: '--sg-danger',
  info: '--sg-pine'
}
const LIGHT_STEPS = ['light-3', 'light-4', 'light-5', 'light-6', 'light-7', 'light-8', 'light-9'] as const

const declaration = (block: string, name: string): string =>
  block.match(new RegExp(`--${name}:\\s*([^;]+);`))?.[1]?.trim() ?? ''

/** EP 官方暗色 css-vars 挂在 html.dark（特异性 0,1,1），击穿 :root（0,1,0）的亮色映射。
 *  我们的暗色块必须是 :root[data-theme="dark"]（0,2,0）才能稳定压制，此断言守住该根因修复。 */
describe('main.css EP 颜色族根因修复', () => {
  it('亮色段为每个语义族补齐 light-4 与 light-6 梯度', () => {
    for (const family of FAMILIES) {
      expect(declaration(lightBlock, `el-color-${family}-light-4`)).toMatch(/color-mix/)
      expect(declaration(lightBlock, `el-color-${family}-light-6`)).toMatch(/color-mix/)
    }
  })

  it('暗色段完整映射每个语义族的 base、light-3..9 与 dark-2，且全部派生自 --sg-* Token', () => {
    expect(darkBlock).not.toBe('')
    for (const family of FAMILIES) {
      const base = declaration(darkBlock, `el-color-${family}`)
      expect(base, `--el-color-${family}`).toBe(`var(${SG_SOURCE[family]})`)

      for (const step of LIGHT_STEPS) {
        const value = declaration(darkBlock, `el-color-${family}-${step}`)
        expect(value, `--el-color-${family}-${step}`).toMatch(
          /^(var\(--sg-[a-z-]+\)|color-mix\(in srgb, var\(--sg-[a-z-]+\))/
        )
      }

      expect(declaration(darkBlock, `el-color-${family}-dark-2`)).toMatch(/var\(--sg-|color-mix/)
    }
  })

  it('暗色段 light 梯度向拾光画布混合（高步数趋暗，符合 EP 暗色语义）', () => {
    const l3 = declaration(darkBlock, 'el-color-primary-light-3')
    expect(l3).toBe('color-mix(in srgb, var(--sg-primary) 70%, var(--sg-canvas))')
    const l8 = declaration(darkBlock, 'el-color-primary-light-8')
    expect(l8).toBe('color-mix(in srgb, var(--sg-primary) 20%, var(--sg-canvas))')
    expect(declaration(darkBlock, 'el-color-primary-light-9')).toBe('var(--sg-primary-soft)')
  })

  it('暗色下 --el-color-white 接管为 --sg-on-primary，实心按钮文字随主题', () => {
    expect(declaration(darkBlock, 'el-color-white')).toBe('var(--sg-on-primary)')
  })

  it('暗色下开关圆点显式钉住，避免全局 white 接管后关闭态圆点沉没', () => {
    expect(css).toMatch(
      /:root\[data-theme="dark"\][^{]*\.el-switch__core\s+\.el-switch__action\s*\{[^}]*background-color:\s*var\(--sg-ink\)/
    )
    expect(css).toMatch(
      /:root\[data-theme="dark"\][^{]*\.el-switch\.is-checked[^{]*\.el-switch__action\s*\{[^}]*background-color:\s*var\(--sg-on-primary\)/
    )
  })

  it('非 plain 的 danger 按钮全局收敛为描边 ghost，hover 进入 danger-soft', () => {
    const ghost = css.match(
      /\.el-button--danger:not\(\.is-plain\):not\(\.is-text\):not\(\.is-link\):not\(\.is-disabled\)\s*\{([\s\S]*?)\}/
    )?.[1]
    expect(ghost).toBeDefined()
    expect(ghost).toContain('--el-button-text-color: var(--sg-danger);')
    expect(ghost).toContain('--el-button-bg-color: var(--sg-surface);')
    expect(ghost).toContain('--el-button-border-color: color-mix(in srgb, var(--sg-danger) 55%, transparent);')
    expect(ghost).toContain('--el-button-hover-bg-color: var(--sg-danger-soft);')
    expect(ghost).toContain('--el-button-hover-text-color: var(--sg-danger);')
  })

  it('KPI 卡去除柿橙顶边条，并提供上下文条大数字行工具类', () => {
    const kpi = css.match(/\.kpi-card\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(kpi).not.toMatch(/border-top/)
    expect(css).toMatch(/\.kpi-strip\s*\{/)
  })
})

describe('main.css Token 对比度复核', () => {
  const luminance = (hex: string): number => {
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    const lin = (c: number) => (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4))
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
  }

  const ratio = (fg: string, bg: string): number => {
    const l1 = luminance(fg)
    const l2 = luminance(bg)
    return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)
  }

  const tokenOf = (block: string, name: string): string => {
    const value = declaration(block, name)
    expect(value, `${name} 应为十六进制字面量`).toMatch(/^#[0-9A-Fa-f]{6}$/)
    return value
  }

  const cases: Array<[string, string, string]> = [
    ['正文墨色/画布', 'sg-ink', 'sg-canvas'],
    ['实心主按钮文字/主色', 'sg-on-primary', 'sg-primary'],
    ['danger 描边按钮文字/表面', 'sg-danger', 'sg-surface'],
    ['danger 描边按钮 hover 文字/soft 底', 'sg-danger', 'sg-danger-soft']
  ]

  for (const [theme, block] of [
    ['light', lightBlock],
    ['dark', darkBlock]
  ] as const) {
    it.each(cases)('%s 主题：%s 对比度满足 WCAG AA 4.5:1', (_label, fg, bg) => {
      expect(ratio(tokenOf(block, fg), tokenOf(block, bg))).toBeGreaterThanOrEqual(4.5)
    })
  }
})

describe('main.css 动效包与按钮工具类（S02）', () => {
  it('sg-rise 入场与交错档位、sg-lift 抬升、印章淡入、kicker 分隔线生长全部定义', () => {
    expect(css).toMatch(/\.sg-rise\s*\{[^}]*animation:\s*sg-rise/)
    for (const step of ['sg-rise-2', 'sg-rise-3', 'sg-rise-4']) {
      expect(css).toMatch(new RegExp(`\\.${step}\\s*\\{[^}]*animation-delay`))
    }
    expect(css).toMatch(/\.sg-lift\s*\{[^}]*transition:[^}]*transform/)
    expect(css).toMatch(/\.sg-lift:hover\s*\{[^}]*transform:\s*translateY\(-3px\)[^}]*--sg-shadow-2/)
    expect(css).toMatch(/\.sg-kicker::after\s*\{[^}]*animation:\s*sg-rule-grow/)
    expect(css).toMatch(/\.sg-tag\s*\{[^}]*animation:\s*sg-stamp-in/)
  })

  it('动效声明全部包裹在 prefers-reduced-motion: no-preference 内，退化即无动画', () => {
    const noPref = css.match(/@media\s*\(prefers-reduced-motion:\s*no-preference\)\s*\{([\s\S]*?)\n\}/)?.[1] ?? ''
    for (const util of ['.sg-rise', '.sg-rise-2', '.sg-lift', '.sg-kicker::after', '.sg-tag']) {
      expect(noPref, `${util} 应在 no-preference 媒体查询内`).toMatch(new RegExp(util.replace(/::/g, '::').replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
    }
    expect(css).toMatch(/@media\s*\(prefers-reduced-motion:\s*reduce\)[\s\S]*animation:\s*none\s*!important/)
  })

  it('sg-btn-ghost 描边次操作工具类：表面底 + 边界描边，hover 进主色', () => {
    const ghost = css.match(/\.sg-btn-ghost\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(ghost).toContain('border: 1px solid var(--sg-border-strong);')
    expect(ghost).toContain('background: var(--sg-surface);')
    expect(css).toMatch(/\.sg-btn-ghost:hover\s*\{[^}]*border-color:\s*var\(--sg-primary\);[^}]*color:\s*var\(--sg-primary\)/)
  })

  it('user-layout 不再挂动态背景层，布局画布透明；管理端为 fw 框架壳自含画布（R-01/S06）', () => {
    expect(userLayout).not.toMatch(/user-background/)
    expect(userLayout).not.toMatch(/sg-background-drift/)
    expect(userLayout).not.toMatch(/data-scene/)
    expect(userLayout).toMatch(/\.user-layout\s*\{[^}]*min-height:\s*100dvh;/)

    // S06：管理端换为预览框架壳，.sga-framework 自含 fw token 画布（不再透出静谧晨光全局背景）
    const fwCss = readFrom('src/styles/shiguang-admin-framework.css')
    expect(fwCss).toMatch(/\.sga-framework\s*\{[^}]*background:\s*var\(--canvas\);/)
  })
})

describe('main.css V3 背景系统三口味（V3-UI-UX-SHIGUANG S01）', () => {
  it('html 平底画布兜底：仅 background-color，无晨光带渐变、无贴图、无 attachment', () => {
    const htmlRule = css.match(/\nhtml\s*\{([\s\S]*?)\n\}/)?.[1] ?? ''
    expect(htmlRule).toContain('background-color: var(--sg-canvas);')
    expect(htmlRule).not.toMatch(/background-image|linear-gradient|background-attachment/)
  })

  it('body 画布透明，露出固定背景层', () => {
    const bodyRule = css.match(/\nbody\s*\{([\s\S]*?)\n\}/)?.[1] ?? ''
    expect(bodyRule).toContain('background: transparent;')
  })

  it('.sg-bg-field 固定层：z -1、pointer-events none、overflow hidden、画布色兜底', () => {
    const field = css.match(/\.sg-bg-field\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(field).toContain('position: fixed;')
    expect(field).toContain('z-index: -1;')
    expect(field).toContain('pointer-events: none;')
    expect(field).toContain('overflow: hidden;')
    expect(field).toContain('background: var(--sg-canvas);')
  })

  it('细噪点纸纹颗粒常驻：.sg-bg-field::after 260px fractalNoise 5%', () => {
    const grain = css.match(/\.sg-bg-field::after\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(grain).toMatch(/url\("data:image\/svg\+xml,%3Csvg[^"]*fractalNoise[^"]*opacity='0\.05'[^"]*"\)/)
    expect(grain).toContain('background-size: 260px 260px;')
  })

  it('V6 粒子画布常显：canvas display block，口味体系（data-bg/scrim/极光）退役清零', () => {
    const canvas = css.match(/\.sg-bg-canvas\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(canvas).toContain('display: block;')
    expect(css).not.toMatch(/html\[data-bg/)
    expect(css).not.toContain('.sg-bg-scrim')
    expect(css).not.toContain('.sg-bg-aur')
    expect(css).not.toContain('.sg-bg-blob')
  })

  it('V6 进场编排：ps-block/sg-intro-el 三个隐藏态守卫 + reveal-hero 先亮 hero + intro-reveal 窗口', () => {
    expect(css).toMatch(/\.ps-block\s*\{[^}]*transition: opacity 0\.55s var\(--sg-ease\);/)
    expect(css).toMatch(/body\.intro-wait \.ps-block[\s\S]*?body\.scattered \.sg-intro-el\s*\{[^}]*opacity: 0;/)
    expect(css).toMatch(/body\.reveal-hero \.ps-block\s*\{[^}]*opacity: 1;/)
    expect(css).toMatch(/body\.intro-reveal \.rv\s*\{[^}]*transition-delay: var\(--sg-irvd, var\(--rvd, 0ms\)\);/)
    expect(css).toMatch(/body\.intro-done \.sg-intro-el\s*\{[^}]*opacity: 1;[^}]*transition: opacity 0\.7s var\(--sg-ease\) var\(--sg-irvd, 0ms\);/)
  })

  it('V6 呼吸引导钮：玻璃胶囊 + 柿橙呼吸圆点，gather 态隐藏，reduced-motion/no-motion 兜底隐藏', () => {
    const hint = css.match(/\.intro-hint\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(hint).toContain('position: fixed;')
    expect(hint).toContain('z-index: 60;')
    expect(hint).toContain('border-radius: 999px;')
    expect(hint).toContain('background: var(--sg-glass-strong);')
    expect(hint).toContain('cursor: pointer;')
    expect(css).toMatch(/body\.intro-gather \.intro-hint\s*\{[^}]*display: none;/)
    expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\)\s*\{[\s\S]*?\.intro-hint\s*\{[^}]*display: none !important;/)
    expect(css).toMatch(/html\.no-motion \.intro-hint\s*\{[^}]*display: none !important;/)
  })
})

describe('main.css V3 S01 Token 基座（Apple 骨 × 拾光皮）', () => {
  it('弹簧曲线与第四档玻璃圆角入 Token，明暗共用不重复声明', () => {
    expect(css).toContain('--sg-ease-spring: cubic-bezier(0.34, 1.56, 0.64, 1);')
    expect(css).toContain('--sg-radius-glass: 18px;')
    expect(css).toContain('--sg-glass-blur: 22px;')
    expect(declaration(lightBlock, 'sg-ease')).toBe('cubic-bezier(0.22, 0.61, 0.36, 1)')
    expect(css.match(/--sg-ease-spring:/g)?.length).toBe(1)
  })

  it('玻璃 Token 族亮暗同构：亮 rgba(255,253,248,.68)/暗 rgba(38,33,27,.62)', () => {
    expect(declaration(lightBlock, 'sg-glass')).toBe('rgba(255, 253, 248, 0.68)')
    expect(declaration(lightBlock, 'sg-glass-strong')).toBe('rgba(255, 253, 248, 0.8)')
    expect(declaration(lightBlock, 'sg-sheen')).toBe('rgba(255, 255, 255, 0.55)')
    expect(declaration(lightBlock, 'sg-shadow-float')).toBe('0 24px 80px rgba(35, 31, 26, 0.14)')
    expect(declaration(darkBlock, 'sg-glass')).toBe('rgba(38, 33, 27, 0.62)')
    expect(declaration(darkBlock, 'sg-glass-strong')).toBe('rgba(42, 36, 29, 0.76)')
    expect(declaration(darkBlock, 'sg-sheen')).toBe('rgba(255, 240, 220, 0.12)')
    expect(declaration(darkBlock, 'sg-shadow-float')).toBe('0 24px 80px rgba(0, 0, 0, 0.5)')
  })

  it('柿橙聚焦环与柔光 Token：ring 走 primary color-mix，glow 明暗各一组', () => {
    expect(declaration(lightBlock, 'sg-ring')).toBe('color-mix(in srgb, var(--sg-primary) 55%, transparent)')
    expect(declaration(darkBlock, 'sg-ring')).toBe('color-mix(in srgb, var(--sg-primary) 60%, transparent)')
    expect(declaration(lightBlock, 'sg-glow-primary')).toBe('rgba(158, 58, 21, 0.22)')
    expect(declaration(darkBlock, 'sg-glow-primary')).toBe('rgba(240, 145, 106, 0.2)')
  })

  it('「媒体上文字」Token 族齐备：幕布压暗 + 文字亮字，暗色同构', () => {
    expect(declaration(lightBlock, 'sg-media-veil')).toBe('rgba(22, 17, 12, 0.5)')
    expect(declaration(lightBlock, 'sg-media-ink')).toBe('#fff8ef')
    expect(declaration(darkBlock, 'sg-media-ink')).toBe('#f3ede3')
    expect(declaration(darkBlock, 'sg-media-veil')).toBe('rgba(10, 8, 6, 0.55)')
  })

  it('页头高变量：user/admin 均 68px，≤640px 收窄 60px', () => {
    expect(declaration(lightBlock, 'user-header-h')).toBe('68px')
    expect(declaration(lightBlock, 'admin-header-h')).toBe('68px')
    expect(css).toMatch(/@media \(max-width: 640px\)\s*\{\s*:root\s*\{[^}]*--user-header-h: 60px;[^}]*--admin-header-h: 60px;/)
  })

  it('.sg-glass 工具类：blur+饱和、第四档圆角、浮影 + 顶部 1px 内高光', () => {
    const glass = css.match(/\.sg-glass\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(glass).toContain('background: var(--sg-glass);')
    expect(glass).toMatch(/backdrop-filter: blur\(var\(--sg-glass-blur\)\) saturate\(1\.25\)/)
    expect(glass).toContain('border-radius: var(--sg-radius-glass);')
    expect(glass).toContain('box-shadow: var(--sg-shadow-float), inset 0 1px 0 var(--sg-sheen);')
  })

  it('.sg-group-card inset 分组卡：整组一卡、发丝线分隔、整行 hover 染 7% 柿橙', () => {
    expect(css).toMatch(/\.sg-group-card\s*\{[^}]*border-radius: var\(--sg-radius-card\);[^}]*background: var\(--sg-surface\);[^}]*box-shadow: var\(--sg-shadow-2\), inset 0 1px 0 var\(--sg-sheen\);/)
    expect(css).toMatch(/\.sg-group-row \+ \.sg-group-row\s*\{[^}]*box-shadow: 0 -1px 0 var\(--sg-border-soft\);/)
    expect(css).toMatch(/\.sg-group-row:hover\s*\{[^}]*background: color-mix\(in srgb, var\(--sg-primary\) 7%, transparent\);/)
  })

  it('.sg-pick-block 可选块：6% 墨底、选中 primary-soft + 2px 柿橙 ring、禁用降字', () => {
    expect(css).toMatch(/\.sg-pick-block\s*\{[^}]*background: color-mix\(in srgb, var\(--sg-ink\) 6%, transparent\);/)
    expect(css).toMatch(/\.sg-pick-block\.is-on\s*\{[^}]*background: var\(--sg-primary-soft\);[^}]*box-shadow: 0 0 0 2px var\(--sg-ring\);/)
    expect(css).toMatch(/\.sg-pick-block small\s*\{[^}]*color: var\(--sg-ink-subtle\);/)
  })

  it('.sg-ticket-edge mask 打孔透出真实背景，无实色补底', () => {
    const edge = css.match(/\.sg-ticket-edge\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    // 孔位经 --sg-ticket-hole-a/b 变量化：默认横排票据左右缘 62% 高，竖撕票据（核销凭证）可覆写为上下缘
    expect(edge).toMatch(/--sg-ticket-hole-a: 0 62%;/)
    expect(edge).toMatch(/--sg-ticket-hole-b: 100% 62%;/)
    // mask 保留区锚点走 --sg-mask-ink Token（只取 alpha，不是可见色），全站硬编码色值扫描归零
    expect(edge).toMatch(/radial-gradient\(circle var\(--sg-ticket-hole\) at var\(--sg-ticket-hole-a\), transparent 96%, var\(--sg-mask-ink\)\)/)
    expect(edge).toMatch(/radial-gradient\(circle var\(--sg-ticket-hole\) at var\(--sg-ticket-hole-b\), transparent 96%, var\(--sg-mask-ink\)\)/)
    expect(edge).toContain('mask-composite: intersect;')
    expect(edge).not.toMatch(/--sg-canvas/)
    expect(edge).not.toMatch(/#[0-9a-fA-F]{3,8}/)
  })

  it('.sg-rail 横向轨道：隐藏滚动条、14px 卡距、子项 snap', () => {
    expect(css).toMatch(/\.sg-rail\s*\{[^}]*gap: 14px;[^}]*overflow-x: auto;[^}]*scrollbar-width: none;/)
    expect(css).toMatch(/\.sg-rail::-webkit-scrollbar\s*\{[^}]*display: none;/)
    expect(css).toMatch(/\.sg-rail > \*\s*\{[^}]*scroll-snap-align: start;/)
  })

  it('.sg-btn-capsule 胶囊双款式：墨底主操作 + ghost 次操作，hover 走令牌混合', () => {
    expect(css).toMatch(/\.sg-btn-capsule\s*\{[^}]*border-radius: 999px;[^}]*background: var\(--sg-ink\);[^}]*color: var\(--sg-canvas\);/)
    expect(css).toMatch(/\.sg-btn-capsule:hover\s*\{[^}]*background: color-mix\(in srgb, var\(--sg-ink\) 86%, var\(--sg-canvas\)\);/)
    expect(css).toMatch(/\.sg-btn-capsule--ghost\s*\{[^}]*border: 1px solid var\(--sg-border-strong\);[^}]*color: var\(--sg-ink\);/)
  })

  it('.sg-seg 分段控件：胶囊容器、6% 墨底、滑块弹簧位移', () => {
    expect(css).toMatch(/\.sg-seg\s*\{[^}]*border-radius: 999px;[^}]*background: color-mix\(in srgb, var\(--sg-ink\) 6%, transparent\);/)
    expect(css).toMatch(/\.sg-seg-ind\s*\{[^}]*transition: transform var\(--sg-motion-base\) var\(--sg-ease-spring\);/)
    expect(css).toMatch(/\.sg-seg-item\.is-on\s*\{[^}]*color: var\(--sg-ink\);/)
  })

  it('.sg-note 安静提示条双档：中性 subtle + warning 软底', () => {
    expect(css).toMatch(/\.sg-note\s*\{[^}]*background: var\(--sg-surface-subtle\);[^}]*color: var\(--sg-ink-muted\);/)
    expect(css).toMatch(/\.sg-note--warning\s*\{[^}]*background: var\(--sg-warning-soft\);[^}]*color: color-mix\(in srgb, var\(--sg-warning\) 80%, var\(--sg-ink\)\);/)
  })

  it('按压 .sg-press 与对焦进场 .sg-focus-in 只在非 reduce 播放，收尾 backwards 不留层叠上下文', () => {
    // V3 原子件段内：.sg-focus-in 声明必须出现在 no-preference 媒体查询之后
    const v3Section = css.slice(css.indexOf('V3 Apple 骨全局原子件'))
    const mediaIdx = v3Section.indexOf('@media (prefers-reduced-motion: no-preference)')
    const focusIdx = v3Section.indexOf('.sg-focus-in {')
    expect(mediaIdx).toBeGreaterThan(-1)
    expect(focusIdx).toBeGreaterThan(mediaIdx)
    expect(v3Section.slice(focusIdx)).toMatch(/animation: sg-focus-in 620ms var\(--sg-ease\) backwards;/)
    expect(css).toMatch(/\.sg-press:active,\s*\n\s*\.sg-btn-capsule:active,\s*\n\s*\.sg-pick-block:active\s*\{[^}]*transform: scale\(0\.97\);/)
    expect(css).toMatch(/@keyframes sg-focus-in\s*\{[^}]*opacity: 0;[^}]*transform: scale\(1\.035\);[^}]*filter: blur\(14px\);/)
  })

  it('EP 表单全局覆写为填充式：无描边、6% 墨底、hover 加深、聚焦玻璃亮底 + 2px 柿橙 ring', () => {
    const fill = css.match(/\.el-input__wrapper,\s*\n\.el-select__wrapper,\s*\n\.el-textarea__inner\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(fill).toContain('box-shadow: none;')
    expect(fill).toContain('background-color: color-mix(in srgb, var(--sg-ink) 6%, transparent);')
    expect(css).toMatch(/\.el-input__wrapper\.is-focus,\s*\n\.el-select__wrapper\.is-focused,\s*\n\.el-textarea__inner:focus\s*\{[^}]*background-color: var\(--sg-glass-strong\);[^}]*box-shadow: 0 0 0 2px var\(--sg-ring\);/)
    const focusRule = css.match(/button:focus-visible,\s*\na:focus-visible,\s*\n\.el-button:focus-visible\s*\{[^}]*\}/)?.[0] ?? ''
    expect(focusRule).not.toMatch(/el-input__wrapper/)
  })

  it('表格行 hover 由灰底换 7% 柿橙浅染', () => {
    expect(css).toMatch(/--el-table-row-hover-bg-color: color-mix\(in srgb, var\(--sg-primary\) 7%, transparent\);/)
  })
})

describe('main.css R6 §00C 动效语言五项', () => {
  it('① 渐入 .rv：上浮 16px 淡入 500ms、--rvd 交错、.in 落定、reduce 下直接可见', () => {
    expect(css).toMatch(/\.rv\s*\{[^}]*opacity:\s*0;[^}]*transform:\s*translateY\(16px\);[^}]*transition:\s*opacity 500ms var\(--sg-ease\), transform 500ms var\(--sg-ease\);[^}]*transition-delay:\s*var\(--rvd, 0ms\);/)
    expect(css).toMatch(/\.rv\.in\s*\{[^}]*opacity:\s*1;[^}]*transform:\s*none;/)
    expect(css).toMatch(/@media\s*\(prefers-reduced-motion:\s*reduce\)\s*\{[\s\S]*?\.rv\s*\{[^}]*opacity:\s*1;[^}]*transform:\s*none;[^}]*transition:\s*none;/)
  })

  it('② 收拉 .sg-fold-body：展开下滑淡入 280ms，仅在 no-preference 内生效', () => {
    expect(css).toMatch(/@keyframes sg-fold-open\s*\{[^}]*opacity:\s*0;[^}]*transform:\s*translateY\(-8px\);/)
    expect(css).toMatch(/@media\s*\(prefers-reduced-motion:\s*no-preference\)[\s\S]*?\.sg-fold-body\s*\{[^}]*animation:\s*sg-fold-open 280ms var\(--sg-ease\);/)
  })

  it('④ 数字跳动 .sg-tick：每秒 180ms 淡变 opacity 0.35→1', () => {
    expect(css).toMatch(/@keyframes sg-tick\s*\{\s*from\s*\{\s*opacity:\s*0\.35;/)
    expect(css).toMatch(/\.sg-tick\s*\{[^}]*animation:\s*sg-tick 180ms var\(--sg-ease\);/)
  })

  it('⑤ 弹层 sg-pop：200ms 开 / 150ms 关，scale(.96→1)；EP 弹窗与轻提示同参数接管', () => {
    expect(css).toMatch(/\.sg-pop-enter-active\s*\{[^}]*transition:\s*opacity 200ms var\(--sg-ease\), transform 200ms var\(--sg-ease\);/)
    expect(css).toMatch(/\.sg-pop-leave-active\s*\{[^}]*transition:\s*opacity 150ms var\(--sg-ease\), transform 150ms var\(--sg-ease\);/)
    expect(css).toMatch(/\.sg-pop-enter-from,[\s\S]*?\.sg-pop-leave-to\s*\{[^}]*opacity:\s*0;[^}]*transform:\s*scale\(0\.96\);/)
    expect(css).toMatch(/\.dialog-fade-enter-active\s*\{[^}]*animation:\s*none;[^}]*transition:\s*opacity 200ms var\(--sg-ease\);/)
    expect(css).toMatch(/\.dialog-fade-enter-from \.el-dialog\s*\{[^}]*opacity:\s*0;[^}]*transform:\s*scale\(0\.96\);/)
    expect(css).toMatch(/\.dialog-fade-leave-active \.el-dialog\s*\{[^}]*transition:\s*opacity 150ms var\(--sg-ease\), transform 150ms var\(--sg-ease\);/)
    expect(css).toMatch(/\.el-message-fade-leave-active\s*\{[^}]*transition-duration:\s*150ms;/)
  })
})

describe('main.css R6 §00 语义章全局族', () => {
  it('.sg-tag 族与纯文字形态齐备，tone 齐全', () => {
    expect(css).toMatch(/\.sg-tag\s*\{[^}]*border-radius:\s*var\(--sg-radius-stamp\);[^}]*font-size:\s*var\(--sg-fz-sm\);/)
    expect(css).toMatch(/\.sg-tag::before\s*\{[^}]*width:\s*5px;[^}]*height:\s*5px;[^}]*border-radius:\s*50%;/)
    for (const tone of ['success', 'warning', 'danger', 'honey', 'pine', 'mist', 'primary', 'neutral']) {
      expect(css).toMatch(new RegExp(`\\.sg-tag--${tone}\\s*\\{`))
    }
    expect(css).toMatch(/\.sg-tag--honey\s*\{[^}]*color:\s*var\(--sg-honey\);[^}]*background:\s*var\(--sg-honey-soft\);/)
    expect(css).toMatch(/\.sg-tag-text\s*\{[^}]*color:\s*var\(--sg-ink-subtle\);/)
  })
})

describe('main.css S01 动效与卡片底座', () => {
  it('提供五档动效时长与三条语义缓动曲线', () => {
    expect(css).toContain('--sg-motion-instant: 100ms;')
    expect(css).toContain('--sg-motion-fast: 160ms;')
    expect(css).toContain('--sg-motion-base: 240ms;')
    expect(css).toContain('--sg-motion-slow: 400ms;')
    expect(css).toContain('--sg-motion-max: 500ms;')
    expect(css).toContain('--sg-ease-standard: cubic-bezier(0.2, 0, 0, 1);')
    expect(css).toContain('--sg-ease-enter: cubic-bezier(0.05, 0.7, 0.1, 1);')
    expect(css).toContain('--sg-ease-exit: cubic-bezier(0.3, 0, 0.8, 0.15);')
  })

  it('提供 mask reveal 与通用 stagger 工具，并限制在非 reduce 动效媒体查询', () => {
    expect(css).toMatch(/\.sg-mask-line\s*\{[^}]*overflow:\s*hidden/)
    expect(css).toMatch(/\.sg-mask-line\s*>\s*span\s*\{[^}]*transform:\s*translateY\(115%\)/)
    expect(css).toMatch(/\.sg-stagger\s*>\s*\*,[\s\S]*?\.sg-stagger--loose\s*>\s*\*\s*\{[^}]*animation:\s*sg-rise/)
    expect(css).toMatch(/@media\s*\(prefers-reduced-motion:\s*no-preference\)[\s\S]*\.sg-mask-line/)
    expect(css).toMatch(/@media\s*\(prefers-reduced-motion:\s*reduce\)[\s\S]*\.sg-mask-line\s*>\s*span[^{]*\{[^}]*transform:\s*none/)
  })

  it('用户端卡片去边框，V4 框材质：data-panel 无底无描边（预览 §05）', () => {
    const card = css.match(/\.sg-card\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(card).toContain('border: 0;')
    expect(card).toContain('border-radius: var(--sg-radius-card);')
    expect(css).toMatch(/\.user-layout\s+\.data-panel\s*\{[^}]*border:\s*0;[^}]*box-shadow:\s*none/)
    const panel = css.match(/\n\.data-panel\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(panel).toContain('background: transparent;')
    expect(panel).toContain('border: 0;')
    expect(panel).toContain('box-shadow: none;')
  })

  it('用户端 data-panel 典型盒中盒改为无围合摘要', () => {
    expect(css).toMatch(/\.user-layout\s+\.email-summary\s*\{[^}]*border:\s*0;[^}]*background:\s*transparent/)
  })
})

describe('main.css S02 发现社区新基础件', () => {
  it('墨底主钮与文字钮两款式：圆角走控件档 Token，hover 走令牌混合色，无柿橙实底', () => {
    const primary = css.match(/\.sg-btn-primary,\s*\n\.sg-btn-text\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(primary).toContain('border-radius: var(--sg-radius-control);')
    const inkBtn = css.match(/\.sg-btn-primary\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(inkBtn).toContain('background: var(--sg-ink);')
    expect(inkBtn).toContain('color: var(--sg-canvas);')
    expect(css).toMatch(/\.sg-btn-primary:hover\s*\{[^}]*background:\s*color-mix\(in srgb, var\(--sg-ink\) 86%, var\(--sg-canvas\)\)/)
    expect(css).toMatch(/\.sg-btn-text:hover\s*\{[^}]*background:\s*var\(--sg-primary-soft\);[^}]*color:\s*var\(--sg-primary\)/)
    expect(css).toMatch(/\.sg-btn-sm\s*\{[^}]*font-size:\s*var\(--sg-fz-sm\)/)
  })

  it('tab 内嵌搜索行修饰类提供 16px 下缘间距', () => {
    expect(css).toMatch(/\.sg-searchrow--embedded\s*\{[^}]*margin-bottom:\s*16px;/)
  })

  it('分区带族：菱形章、衬线标题、横线与查看全部链接齐备，分区网格双列', () => {
    expect(css).toMatch(/\.sg-secband\s*\{[^}]*display:\s*flex[^}]*gap:\s*13px/)
    expect(css).toMatch(/\.sg-secband-orn\s*\{[^}]*background:\s*var\(--sg-primary\)[^}]*transform:\s*rotate\(45deg\)/)
    expect(css).toMatch(/\.sg-secband h4\s*\{[^}]*font-family:\s*var\(--sg-font-serif\)[^}]*font-size:\s*var\(--sg-fz-lg\)/)
    expect(css).toMatch(/\.sg-secband-rule\s*\{[^}]*height:\s*1px;[^}]*background:\s*var\(--sg-border-strong\)/)
    expect(css).toMatch(/\.sg-secband-more\s*\{[^}]*color:\s*var\(--sg-primary\)/)
    expect(css).toMatch(/\.sg-section-grid\s*\{[^}]*grid-template-columns:\s*repeat\(2, minmax\(0, 1fr\)\)/)
  })
})

describe('main.css S01 终版基础工具类与 EP 浮层主题', () => {
  it('提供搜索行、chip、统一错误态、caret、小节标签与落定卡基础类', () => {
    expect(css).toMatch(/\.sg-searchrow\s*\{[^}]*display:\s*flex[^}]*gap:\s*10px[^}]*flex-wrap:\s*wrap/)
    expect(css).toMatch(/\.sg-search-pill\s*\{[^}]*min-height:\s*44px[^}]*border-radius:\s*999px[^}]*box-shadow:\s*var\(--sg-shadow-1\)/)
    expect(css).toMatch(/\.sg-search-pill:focus-within\s*\{[^}]*0 0 0 2px var\(--sg-focus\)[^}]*transform:\s*translateY\(-1px\)/)
    expect(css).toMatch(/\.sg-chip\s*\{[^}]*min-height:\s*34px[^}]*border-radius:\s*999px[^}]*box-shadow:\s*0 0 0 1px var\(--sg-border\) inset/)
    expect(css).toMatch(/\.sg-chip\.is-on[^}]*background:\s*var\(--sg-primary-soft\)[^}]*var\(--sg-primary\)/)
    expect(css).toMatch(/\.sg-state-error\s*\{[^}]*border:\s*0[^}]*background:\s*var\(--sg-danger-soft\)/)
    expect(css).toMatch(/\.sg-details\s*>\s*summary::before\s*\{[^}]*transform:\s*rotate\(-45deg\)/)
    expect(css).toMatch(/\.sg-details\[open\]\s*>\s*summary::before\s*\{[^}]*transform:\s*rotate\(45deg\)/)
    expect(css).toMatch(/\.sg-section-label\s*\{[^}]*var\(--sg-ink-subtle\)[^}]*font-size:\s*var\(--sg-fz-xs\)[^}]*font-weight:\s*600/)
    expect(css).toMatch(/\.sg-landing-card\s*\{[^}]*box-shadow:\s*var\(--sg-shadow-2\)/)
    expect(css).toMatch(/\.sg-landing-stamp\s*\{[^}]*border:\s*2px solid var\(--sg-success\)/)
  })

  it('提供 dense/base/loose 三档 stagger，并将第 7 项起延迟封顶', () => {
    expect(css).toMatch(/\.sg-stagger\s*\{[^}]*--sg-stagger-gap:\s*40ms/)
    expect(css).toMatch(/\.sg-stagger--dense\s*\{[^}]*--sg-stagger-gap:\s*30ms/)
    expect(css).toMatch(/\.sg-stagger--loose\s*\{[^}]*--sg-stagger-gap:\s*60ms/)
    expect(css).toMatch(/nth-child\(n \+ 7\)[^{]*\{[^}]*calc\(var\(--sg-stagger-gap\) \* 7\)/)
  })

  it('将 EP message、message-box、select/picker、dropdown 统一到拾光表面与阴影，浮层圆角走卡面档 Token', () => {
    expect(css).toMatch(/\.el-message\s*\{[^}]*border-radius:\s*var\(--sg-radius-card\)[^}]*box-shadow:\s*var\(--sg-shadow-2\)[^}]*font-weight:\s*500/)
    expect(css).toMatch(/\.el-message-box\s*\{[^}]*border-radius:\s*var\(--sg-radius-card\)[^}]*box-shadow:\s*var\(--sg-shadow-overlay\)/)
    expect(css).toMatch(/\.el-select-dropdown,[\s\S]*?\.el-picker-panel\s*\{[^}]*border-radius:\s*var\(--sg-radius-card\)[^}]*box-shadow:\s*var\(--sg-shadow-2\)/)
    expect(css).toMatch(/\.el-dropdown-menu\s*\{[^}]*border-radius:\s*var\(--sg-radius-card\)[^}]*box-shadow:\s*var\(--sg-shadow-overlay\)/)
    expect(css).toMatch(/\.el-dropdown-menu__group-label\s*\{[^}]*var\(--sg-ink-subtle\)[^}]*font-size:\s*var\(--sg-fz-xs\)[^}]*font-weight:\s*700/)
  })

  it('光晕新增 strong token，且所有 --sg-motion-max 引用都是真实样式归属', () => {
    expect(css).toContain('--sg-pine-soft-strong: #D8E7DF;')
    expect(css).toContain('--sg-honey-soft-strong: #EFDDB9;')
    expect((css.match(/--sg-motion-max/g) ?? []).length).toBeGreaterThanOrEqual(2)
  })
})

describe('main.css S01 全局令牌收敛（UX-AUDIT-FIX-20260826）', () => {
  it('圆角三档 Token：卡面 12 / 控件 8 / 章 2，用户端不再单独覆写卡面档', () => {
    expect(css).toContain('--sg-radius-card: 12px;')
    expect(css).toContain('--sg-radius-control: 8px;')
    expect(css).toContain('--sg-radius-stamp: 2px;')
    expect(css).not.toMatch(/\.user-layout\s*\{[^}]*--sg-radius-card/)
    expect(css).toContain('--el-border-radius-base: var(--sg-radius-control);')
  })

  it('字号四档 Token：xs 12 / sm 13 / base 15 / lg 18，正文层字面量字号清零（展示级 ≥22 与装饰 ≤10 豁免）', () => {
    expect(css).toContain('--sg-fz-xs: 12px;')
    expect(css).toContain('--sg-fz-sm: 13px;')
    expect(css).toContain('--sg-fz-base: 15px;')
    expect(css).toContain('--sg-fz-lg: 18px;')
    const literals = [...css.matchAll(/font-size:\s*(\d+(?:\.\d+)?)px/g)].map((m) => Number(m[1]))
    expect(literals.length).toBeGreaterThan(0)
    for (const size of literals) {
      expect(size <= 10 || size >= 22, `font-size ${size}px 应回退到四档 Token 或属展示/装饰豁免`).toBe(true)
    }
  })

  it('legacy 迁移别名整段移除：--color-* 与 shadow/radius/transition 别名零残留', () => {
    expect(css).not.toMatch(/--color-[a-z-]+\s*:/)
    expect(css).not.toMatch(/--shadow-(card|soft|overlay)\s*:/)
    expect(css).not.toMatch(/--radius-card\s*:/)
    expect(css).not.toMatch(/--transition-(fast|base)\s*:/)
  })

  it('布局断点收敛为 640/900/1200 三档，区间查询下缘统一 641', () => {
    const widthQueries = [...css.matchAll(/@media[^{]+/g)].map((m) => m[0]).filter((q) => q.includes('width'))
    expect(widthQueries.length).toBeGreaterThan(0)
    for (const query of widthQueries) {
      expect(query, query).toMatch(/\((?:min-width:\s*641px|max-width:\s*(?:640|900|1200)px)\)/)
    }
  })
})

describe('main.css S04 全站主钮换装', () => {
  it('el-button type=primary 实底统一切换为墨底纸字，hover 走令牌混合色，plain/text/link 变体豁免', () => {
    const rule = css.match(/\.el-button--primary:not\(\.is-plain\):not\(\.is-text\):not\(\.is-link\)\s*\{([\s\S]*?)\n\}/)?.[1] ?? ''
    expect(rule).toContain('--el-button-bg-color: var(--sg-ink);')
    expect(rule).toContain('--el-button-text-color: var(--sg-canvas);')
    expect(rule).toMatch(/--el-button-hover-bg-color:\s*color-mix\(in srgb, var\(--sg-ink\) 86%, var\(--sg-canvas\)\)/)
    expect(rule).toMatch(/--el-button-disabled-bg-color:\s*var\(--sg-surface-subtle\)/)
  })

  it('sg-rise 一族以 backwards 收尾，不留永久 transform 层叠上下文困住页头浮层', () => {
    expect(css).toMatch(/\.sg-rise\s*\{[^}]*animation:\s*sg-rise[^}]*backwards;/)
    expect(css).toMatch(/\.sg-stagger > \*,[\s\S]*?\.sg-stagger--loose > \* \{[^}]*animation:[^}]*backwards;/)
    expect(css).toMatch(/\.user-main \.page-stack > \* \{[^}]*animation:[^}]*backwards;/)
    expect(css).not.toMatch(/\.user-main \.page-stack > \* \{[^}]*both;/)
  })
})

describe('main.css UX-FIX-ROUND-20260828 S01 页头层级与实底化', () => {
  const sgDropdown = readFrom('src/components/SgDropdown.vue')

  it('页头 z:90 压过页内浮层，V3 玻璃配方透出流动背景，保留底部发丝线（S03 起玻璃化）', () => {
    const header = userLayout.match(/\.user-header\s*\{([\s\S]*?)\n\}/)?.[1] ?? ''
    expect(header).toContain('z-index: 90;')
    expect(header).toMatch(/background: color-mix\(in srgb, var\(--sg-canvas\) 82%, transparent\);/)
    expect(header).toContain('border-bottom: 1px solid var(--sg-border);')
    expect(header).toMatch(/backdrop-filter: blur\(20px\) saturate\(1\.25\)/)
  })

  it('SgDropdown 触发器根层级从 70 收敛到面板层 60，面板保持 60', () => {
    const root = sgDropdown.match(/\.sg-dd\s*\{([\s\S]*?)\n\}/)?.[1] ?? ''
    expect(root).toContain('z-index: 60;')
    const panel = sgDropdown.match(/\.sg-dd-panel\s*\{([\s\S]*?)\n\}/)?.[1] ?? ''
    expect(panel).toContain('z-index: 60;')
  })

  it('html scroll-padding-top:76px 锚点不藏头；全站层级表注释落入 main.css', () => {
    const htmlRule = css.match(/\nhtml\s*\{([\s\S]*?)\n\}/)?.[1] ?? ''
    expect(htmlRule).toContain('scroll-padding-top: 76px;')
    expect(css).toMatch(/全站层级表[\s\S]*页头 \.user-header 90[\s\S]*Element Plus 弹窗遮罩 2000 \/ 轻提示 3000/)
  })

  it('移动端吸底 tabbar z:30，低于页头，矮视口不与页头相撞', () => {
    const tabbarBlocks = [...userLayout.matchAll(/\.mobile-tabbar\s*\{([\s\S]*?)\n\}/g)].map((match) => match[1])
    expect(tabbarBlocks.some((block) => block.includes('z-index: 30;'))).toBe(true)
  })

  it('button.sg-link 重置原生按钮皮肤：边框/底色清零即行程卡竖线杂讯根因修复（S02）', () => {
    expect(css).toMatch(/button\.sg-link\s*\{[^}]*border:\s*0;[^}]*background-color:\s*transparent;[^}]*font:\s*inherit;/)
  })
})

describe('main.css V3 S03 壳层与公共页合同', () => {
  const adminLayout = readFrom('src/layouts/AdminLayout.vue')
  const authCss = readFrom('src/views/public/auth.css')
  const loginView = readFrom('src/views/public/LoginView.vue')
  const notFoundView = readFrom('src/views/public/NotFoundView.vue')
  const appVue = readFrom('src/App.vue')

  it('两布局页头：user 走 68px Token；admin 框架壳 fw-top 77px、≤800px 69px（S06 预览值）', () => {
    expect(userLayout).toMatch(/\.user-header\s*\{[^}]*min-height: var\(--user-header-h, 68px\);/)
    expect(userLayout).toMatch(/\.user-main\s*\{[^}]*calc\(100dvh - var\(--user-header-h, 68px\)\)/)
    const fwCss = readFrom('src/styles/shiguang-admin-framework.css')
    expect(fwCss).toMatch(/\.sga-framework \.fw-top\s*\{[^}]*height:\s*77px;/)
    expect(fwCss).toMatch(/height:\s*69px;/)
  })

  it('管理端框架壳：fw 侧栏固定 234px 发丝线右边、collapsed 76px、当前项内嵌 2px accent（S06）', () => {
    const fwCss = readFrom('src/styles/shiguang-admin-framework.css')
    const sidebar = fwCss.match(/\.sga-framework \.fw-sidebar\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(sidebar).toContain('position: fixed;')
    expect(sidebar).toContain('width: 234px;')
    expect(sidebar).toContain('border-right: 1px solid var(--line);')
    expect(fwCss).toMatch(/\.sga-framework \.fw-collapsed \.fw-sidebar\s*\{[^}]*width:\s*76px;/)
    expect(fwCss).toMatch(/\.sga-framework \.fw-nav a\[aria-current="page"\]\s*\{[^}]*box-shadow:\s*inset 2px 0 var\(--accent\);/)
  })

  it('移动底栏对齐 20px 玻璃规格；用户端导航按压 .97 弹簧', () => {
    const tabbar = [...userLayout.matchAll(/\.mobile-tabbar\s*\{([\s\S]*?)\n\}/g)].map((m) => m[1]).join('\n')
    expect(tabbar).toMatch(/backdrop-filter: blur\(20px\) saturate\(1\.25\)/)
    expect(tabbar).toMatch(/z-index: 30;/)
    expect(userLayout).toMatch(/\.nav-link:active\s*\{[^}]*transform: scale\(0\.97\);/)
    expect(userLayout).toMatch(/transition: left var\(--sg-motion-base\) var\(--sg-ease-spring\), width var\(--sg-motion-base\) var\(--sg-ease-spring\);/)
  })

  it('auth 五页一体换装：玻璃卡+胶囊按钮+对焦进场+静态光野仅登录页，删本地平涂', () => {
    const panel = authCss.match(/\.auth-panel\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(panel).toContain('background: var(--sg-glass);')
    expect(panel).toMatch(/backdrop-filter: blur\(var\(--sg-glass-blur\)\) saturate\(1\.25\)/)
    expect(panel).toContain('border-radius: var(--sg-radius-glass);')
    expect(authCss).toMatch(/\.auth-panel :deep\(\.el-button\)\s*\{[^}]*border-radius: 999px;/)
    expect(authCss).toMatch(/\.simple-page\s*\{[^}]*background: transparent;/)
    expect(authCss).not.toMatch(/radial-gradient\(closest-side at 20% 15%/)
    const code = authCss.match(/\.simple-code\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(code).toContain('font-family: var(--sg-font-serif);')
    expect(code).toContain('-webkit-text-stroke: 1.5px var(--sg-ink);')
    expect(code).toContain('color: transparent;')
    expect(authCss).toMatch(/\.auth-halo::before\s*\{[^}]*background: var\(--sg-glow-primary\);/)
    expect(loginView).toContain('class="auth-halo"')
    expect(loginView).toContain('sg-focus-in')
    for (const view of ['src/views/public/RegisterView.vue', 'src/views/public/ForgotPasswordView.vue']) {
      expect(readFrom(view)).toContain('sg-focus-in')
    }
    expect(loginView).not.toMatch(/sg-rise/)
  })

  it('App.vue 路由容器转场回归 route CSS 过渡（D-02 根治：VT 容器转场退役）', () => {
    expect(appVue).not.toMatch(/useViewTransition/)
    expect(appVue).not.toMatch(/router\.beforeResolve/)
    expect(appVue).toMatch(/<transition name="route" mode="out-in">/)
    expect(userLayout).toMatch(/const fadeName = 'route'/)
    expect(adminLayout).toMatch(/const fadeName = 'route'/)
  })

  it('玻璃工具类压过结构卡类：.data-panel.sg-glass 保留半透明底、第四档圆角与大弥散阴影', () => {
    // 回归：.data-panel 定义在本文件后段，与 .sg-glass 同为 (0,1,0)，源序在后会把玻璃盖回实底白卡
    // （S05 走查实测 KPI 卡为 rgb(255,255,255) + 12px + --sg-shadow-1），故必须有更高特异性的接管规则。
    const takeover = css.match(/\.data-panel\.sg-glass\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    expect(takeover).toContain('background: var(--sg-glass);')
    expect(takeover).toContain('border-radius: var(--sg-radius-glass);')
    expect(takeover).toContain('box-shadow: var(--sg-shadow-float), inset 0 1px 0 var(--sg-sheen);')
    // 接管规则必须排在 .data-panel 结构类之后，否则源序仍会让它失效
    expect(css.indexOf('.data-panel.sg-glass')).toBeGreaterThan(css.indexOf('.data-panel {'))
  })

  it('管理端 KPI 玻璃白名单：拾光面接管后旧 kpi-strip 白名单清零（§08 处方）', () => {
    // BATCH-05 S05：旧 ReconciliationView / StoredValueFinanceView 均退役为拾光财务面（.sga-fin 无 kpi-strip）
    const storedValue = readFrom('src/views/admin/shiguang/finance/ShiguangStoredValueView.vue')
    expect(storedValue).not.toContain('kpi-strip')
  })

  it('V6 口味退役：两布局页头不再挂背景口味切换控件', () => {
    expect(userLayout).not.toContain('BgFlavorToggle')
    expect(adminLayout).not.toContain('BgFlavorToggle')
  })
})
