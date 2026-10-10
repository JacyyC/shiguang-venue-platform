import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * §06 红线违例清单的回归门禁（V3-UI-UX-SHIGUANG S06）。
 *
 * 这里不测视觉效果，只锁「清零状态不被再次引入」：硬编码色值、绕过 Token 的
 * 等宽字体栈、越出三档的圆角字面量、越档的 z-index、重复实现的 formatMoney。
 * 任何一条被重新引入，本文件即失败——销账是可复核、可回归的事实，不是一次性声明。
 */

const srcRoot = (): string => {
  for (const base of [process.cwd(), resolve(process.cwd(), 'frontend')]) {
    if (existsSync(resolve(base, 'src'))) return resolve(base, 'src')
  }
  throw new Error('cannot locate src')
}

const SRC = srcRoot()

const walk = (dir: string, out: string[] = []): string[] => {
  for (const name of readdirSync(dir)) {
    const p = resolve(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (/\.(vue|ts|css)$/.test(p) && !p.endsWith('.spec.ts')) out.push(p)
  }
  return out
}

/** 去掉块注释与行注释（保留 :// 协议），避免注释里的样例值造成误报。 */
const stripComments = (s: string): string =>
  s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1')

/** main.css 的 :root 块是 Token 的合法定义区，不参与「硬编码色值」判定。 */
const stripTokenBlocks = (s: string): string =>
  s
    .replace(/:root,\s*:root\[data-theme="light"\]\s*\{([\s\S]*?)\}/g, ':root{}')
    .replace(/:root\[data-theme="dark"\]\s*\{([\s\S]*?)\}/g, ':root{}')

/** canvas 逐帧绘制只能用字面量取色（Canvas2D 不解析 CSS 变量），属已登记例外。 */
const CANVAS_PAINT_FILES = ['AmbientBackground.vue', 'ShiguangLayout.vue', 'ShiguangAuthView.vue']

// 拾光接入样式（styles/shiguang.css）是冻结参考 style.css 的机械变换件（SHIGUANG-PREVIEW-INTEGRATION-20260909），
// 采用 ADR-0002 独立配色体系与原型字面量，按设计不落 --sg-* Token；整文件登记为 §06 红线扫描的显式例外。
// styles/shiguang-booking.css 同理（SHIGUANG-BOOKING-REFACTOR-20260910 预约链路），其末尾「产品侧补充」段
// 只服务 .sgv-booking 作用域内的新元素，数值仍取自同一冻结配色体系，不向旧页面泄漏。
// styles/shiguang-activity.css 同理（SHIGUANG-ACTIVITY-REFACTOR-20260911 活动与同行 V02），仅服务 .sgv-activity 作用域。
// styles/shiguang-community.css 同理（SHIGUANG-COMMUNITY-REFACTOR-20260911 社区 V1），仅服务 .sgv-community 作用域。
// styles/shiguang-member.css 同理（SHIGUANG-MEMBER-REFACTOR-20260912 会员中心），仅服务 .sgv-member 作用域。
// styles/shiguang-user-service.css 同理（SHIGUANG-BATCH-01-REFACTOR-20260913 交易面/个人面），仅服务 .sgv-user-service 作用域。
// styles/shiguang-admin-framework.css 同理（SHIGUANG-BATCH-01-REFACTOR-20260913 S06 后台框架壳），
// 由冻结参考 framework.css 机械迁移，仅服务 .sga-framework 作用域，token 自含明暗两套，不向旧正文泄漏。
// styles/shiguang-admin-venue.css 同理（SHIGUANG-BATCH-02-REFACTOR-20260913 S04 资源面正文），
// 由冻结参考 e/resources.css 机械迁移，仅服务 .sga-venue 作用域，token 自含明暗两套。
// styles/shiguang-admin-fulfillment.css 同理（SHIGUANG-BATCH-02-REFACTOR-20260913 S05 履约面正文），
// 由冻结参考 f/styles.css 机械迁移（仅履约类，site-ops/asset 类属第三批），仅服务 .sga-fulfillment 作用域。
// styles/shiguang-admin-platform.css 同理（SHIGUANG-BATCH-02-REFACTOR-20260913 S06 平台面正文），
// 由冻结参考 c/styles.css 机械迁移，仅服务 .sga-c 作用域（S07 Provider 面共用同一作用域）。
// styles/shiguang-admin-siteops.css 同理（SHIGUANG-BATCH-03-REFACTOR-20260914 S04 现场与资产面正文），
// 由冻结参考 f/styles.css 与 shared/base.css 机械迁移（仅现场/资产类，履约类见 fulfillment），仅服务 .sga-siteops 作用域。
// styles/shiguang-admin-contact.css 同理（SHIGUANG-BATCH-03-REFACTOR-20260914 S05 客服面正文，S06 公告面共用），
// 由冻结参考 a/a.css 与 shared/base.css 机械迁移，两页共用的 a-* 基元挂 :is(.sga-service,.sga-notice)，
// 公告面专属类（a-compose/a-reading/a-evidence/表格等）在 S06 接入时补入，a-tools 审阅工具不迁入。
// styles/shiguang-admin-ops.css 同理（SHIGUANG-BATCH-04-REFACTOR-20260914 S04 会员与权益面正文，
// S05 促销与定价、S06 场馆漏斗共用），由冻结参考 b/b.css 与 shared/base.css 机械迁移，
// 三面共用的 b-* 基元挂 .sga-ops，预览壳 .shell-*/.page/.user-preview 与审阅工具 .review-lab 不迁入。
// styles/shiguang-admin-finance.css 同理（SHIGUANG-BATCH-05-REFACTOR-20260915 S04 财务面正文，
// S05 储值四面共用），由冻结参考 h/h.css 与 shared/base.css 机械迁移，
// 六面共用的 h-* 基元挂 .sga-fin，预览壳 .h-preview/.page/.shell-* 与审阅工具 .h-lab/.review-lab 不迁入。
const SHIGUANG_STYLE_FILES = ['styles/shiguang.css', 'styles/shiguang-booking.css', 'styles/shiguang-activity.css', 'styles/shiguang-community.css', 'styles/shiguang-member.css', 'styles/shiguang-user-service.css', 'styles/shiguang-admin-framework.css', 'styles/shiguang-admin-venue.css', 'styles/shiguang-admin-fulfillment.css', 'styles/shiguang-admin-platform.css', 'styles/shiguang-admin-siteops.css', 'styles/shiguang-admin-contact.css', 'styles/shiguang-admin-ops.css', 'styles/shiguang-admin-governance.css', 'styles/shiguang-admin-finance.css']

const files = walk(SRC)
  .filter((p) => !SHIGUANG_STYLE_FILES.includes(p.slice(SRC.length + 1)))
  .map((p) => ({
    path: p.slice(SRC.length + 1),
    body: stripTokenBlocks(stripComments(readFileSync(p, 'utf8')))
  }))

const linesOf = (body: string): string[] => body.split('\n')

describe('§06 红线：硬编码 Token 归零', () => {
  it('样式属性里不再出现硬编码色值（canvas 绘制例外已登记）', () => {
    const offenders = files
      .filter((f) => !CANVAS_PAINT_FILES.some((name) => f.path.endsWith(name)))
      .flatMap((f) =>
        linesOf(f.body)
          .map((line, i) => ({ line: line.trim(), at: `${f.path}:${i + 1}` }))
          .filter((e) => /#[0-9a-fA-F]{3,8}\b/.test(e.line) && !/\burl\(/.test(e.line))
      )

    expect(offenders).toEqual([])
  })

  it('等宽字体一律走 --sg-font-mono，不再写死字体栈', () => {
    const offenders = files.flatMap((f) =>
      linesOf(f.body)
        .map((line, i) => ({ line: line.trim(), at: `${f.path}:${i + 1}` }))
        .filter((e) => /font-family:[^;]*/.test(e.line))
        .filter((e) => !/font-family:\s*var\(--sg-font-(mono|sans|serif)\)/.test(e.line))
        .filter((e) => /(mono|Menlo|Consolas|SFMono)/i.test(e.line))
        .filter((e) => !/--sg-font-mono:/.test(e.line))
    )

    expect(offenders).toEqual([])
  })
})

describe('§06 红线：圆角三档与层级表', () => {
  const RADIUS_ALLOWED = new Set(['0', '50%', '999px', 'inherit'])

  it('border-radius 字面量只落在三档 Token 或胶囊/圆形表达上', () => {
    const offenders = files.flatMap((f) =>
      linesOf(f.body).flatMap((line, i) => {
        const m = /border-radius:\s*([^;]+);/.exec(line)
        if (!m) return []
        const raw = m[1]
        const bad = raw
          .trim()
          .split(/\s+/)
          .filter((token) => !RADIUS_ALLOWED.has(token) && !token.startsWith('var('))
        return bad.length ? [{ at: `${f.path}:${i + 1}`, token: bad.join(' '), raw }] : []
      })
    )

    expect(offenders).toEqual([])
  })

  it('z-index 不越档：页头 90 之上只允许 Element Plus 的 2000/3000', () => {
    const offenders = files.flatMap((f) =>
      linesOf(f.body).flatMap((line, i) => {
        const m = /z-index:\s*(\d+)/.exec(line)
        if (!m) return []
        const value = Number(m[1])
        return value > 90 && ![2000, 3000].includes(value)
          ? [{ at: `${f.path}:${i + 1}`, value }]
          : []
      })
    )

    expect(offenders).toEqual([])
  })
})

describe('§06 红线：重复实现上提与死代码清零', () => {
  it('formatMoney 不再有本地重复实现，统一来自 utils/money', () => {
    const offenders = files
      .filter((f) => !f.path.endsWith('utils/money.ts'))
      .flatMap((f) =>
        linesOf(f.body)
          .map((line, i) => ({ line: line.trim(), at: `${f.path}:${i + 1}` }))
          .filter((e) => /const formatMoney\s*=/.test(e.line))
      )

    expect(offenders).toEqual([])
  })

  it('会员卡渐变两端走 Token，--sg-pine-deepest 明暗双主题就位', () => {
    const css = readFileSync(resolve(SRC, 'styles/main.css'), 'utf8')
    const light = css.match(/:root,\s*:root\[data-theme="light"\]\s*\{([\s\S]*?)\}/)?.[1] ?? ''
    const dark = css.match(/:root\[data-theme="dark"\]\s*\{([\s\S]*?)\}/)?.[1] ?? ''

    // 旧 MembershipView（.mem-card 渐变消费方）已由 SHIGUANG-MEMBER-REFACTOR-20260912 S04
    // 替换为 .sgv-member 会员凭证（冻结配色体系，登记于上方 SHIGUANG_STYLE_FILES 例外）；
    // 此处仅保留 token 定义双侧就位的断言。
    expect(light).toMatch(/--sg-pine-deepest:/)
    expect(dark).toMatch(/--sg-pine-deepest:/)
  })

  it('system Dashboard 的 .kpi-grid 死样式已删除（模板无引用）', () => {
    const dash = readFileSync(resolve(SRC, 'views/system-admin/DashboardView.vue'), 'utf8')
    const styleStart = dash.search(/<style[^>]*>/)
    const markup = dash.slice(0, styleStart)
    const style = dash.slice(styleStart)

    expect(markup).not.toContain('kpi-grid')
    expect(style).not.toContain('kpi-grid')
  })
})

describe('V5 F-02 全站最小字号纪律（预览 §02）', () => {
  // 纪律：12px 仅装饰微字（字母间距 kicker 大写、章内标注、徽标数字），不承载必读信息；
  // 标签/计数/hint/时间戳 ≥13px；说明性正文 ≥15px（后两档语义判定随走查锁定）。
  // 本回归锁字面量：任何 <13px 的 font-size 字面量（含 clamp 下限）必须落在下方白名单
  // 配额（文件 × 数值 × 计数）内，新增即失败；var(--sg-fz-*) Token 不在扫描范围。
  // 白名单为 V5 S02 基线登记（既有 <13px 装饰/徽标位），随升档可收紧不可放宽。
  const SUB13_WHITELIST: Record<string, Record<string, number>> = {
    'components/SgDropdown.vue': { '10': 1 },
    'components/placement/HomePrimaryPlacement.vue': { '10': 1 },
    'layouts/UserLayout.vue': { '9': 1, '10': 2 },
    'views/public/auth.css': { '11': 1 },
    'views/system-admin/RolesView.vue': { '10': 1, '10.5': 3 },
    'views/user/HomeView.vue': { '11': 1, '11.5': 1, '12': 2, '12.5': 3 },
    'views/user/ReservationConfirmView.vue': { '10': 1 },
    'views/user/VenueDetailView.vue': { '12': 2 },
    'views/user/VenueListView.vue': { '10': 1 }
  }

  it('font-size 字面量 <13px 不超出白名单配额', () => {
    const offenders: Array<{ at: string; detail: string }> = []
    for (const f of files) {
      const found: Record<string, number> = {}
      for (const line of linesOf(f.body)) {
        const m = /font-size:\s*([^;]+);/.exec(line)
        if (!m) continue
        for (const num of m[1].matchAll(/(\d+(?:\.\d+)?)px/g)) {
          const value = Number(num[1])
          if (value < 13) found[num[1]] = (found[num[1]] || 0) + 1
        }
      }
      const quota = SUB13_WHITELIST[f.path] || {}
      for (const [value, count] of Object.entries(found)) {
        if (count > (quota[value] || 0)) offenders.push({ at: f.path, detail: `${value}px ×${count}（配额 ${quota[value] || 0}）` })
      }
    }

    expect(offenders).toEqual([])
  })
})
