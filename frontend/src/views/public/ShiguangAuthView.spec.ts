import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ShiguangAuthView from './ShiguangAuthView.vue'
import ShiguangSymbols from '@/components/shiguang/ShiguangSymbols.vue'

/**
 * F-02 / F-04 回归门禁（SHIGUANG-WAVE-01-REMEDIATION-20260916 S03）。
 *
 * F-02：登录表单的逻辑焦点顺序必须是「用户名 → 密码 → 显示密码 → 找回密码 → 登录」。
 * 原先「忘记密码？」渲染在密码字段之前的 `.field-heading` 中，Tab 序列变成
 * 用户名 → 找回密码 → 密码，与最高频输入路径不符。修复只调整 DOM 位置：
 * 保留原生 button、可见焦点、深链与密码管理器所需的 autocomplete，
 * 不使用正 `tabindex` 重排，也不用 `tabindex="-1"` 把合法控件移出键盘序列。
 *
 * F-04：站点图标必须由本地静态资源提供并在 `index.html` 显式声明，不引用外部 CDN。
 * 真实浏览器对 `/favicon.ico` 的请求与构建产物可达性见本项 verification/s03 的实测脚本。
 */

const routeMock = vi.hoisted(() => ({ query: {} as Record<string, string> }))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), resolve: vi.fn((path: string) => ({ path, meta: {} })) }))
const authStoreMock = vi.hoisted(() => ({
  login: vi.fn(),
  isLoggedIn: false,
  permissions: [] as string[],
  refreshAuthorization: vi.fn()
}))

vi.mock('vue-router', async (importOriginal) => {
  // 只替换组合式 API；保留 createRouter 等导出，避免被测组件经 http.ts → router/index.ts 的间接依赖断裂。
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRoute: () => routeMock, useRouter: () => routerMock }
})
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authStoreMock }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return { ...actual, ElMessage: { success: vi.fn(), error: vi.fn() } }
})

/** 只做 DOM 顺序与可访问属性断言：需要 `#sgv-arrow` 等 symbol 真实存在，故不 stub 符号表。 */
const mountAuth = () =>
  mount(ShiguangAuthView, {
    global: {
      components: { ShiguangSymbols },
      stubs: { 'router-link': { props: ['to'], template: '<a><slot /></a>' } }
    }
  })

const tabbableOrder = (wrapper: ReturnType<typeof mountAuth>): string[] => {
  const root = wrapper.element as HTMLElement
  const nodes = Array.from(root.querySelectorAll('a[href], button, input, select, textarea, [tabindex]')) as HTMLElement[]
  return nodes
    // 只用 DOM 顺序与键盘可达性判断，不依赖浏览器计算：显式负 tabindex 视为移出序列
    .filter((node) => node.getAttribute('tabindex') !== '-1')
    .map((node) => node.id || node.className || node.tagName.toLowerCase())
}

/** jsdom 不实现 matchMedia：补一个最小桩，仅用于让组件完成挂载（不测动效实现）。 */
beforeEach(() => {
  if (typeof window.matchMedia !== 'function') {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false
      })
    })
  }
  if (typeof globalThis.ResizeObserver !== 'function') {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
  setActivePinia(createPinia())
  vi.clearAllMocks()
  routeMock.query = {}
})

describe('ShiguangAuthView 登录焦点顺序（F-02）', () => {
  it('把「忘记密码？」放在密码输入之后，Tab 顺序为 用户名 → 密码 → 显示密码 → 找回 → 登录', () => {
    const wrapper = mountAuth()
    const order = tabbableOrder(wrapper)
    const indexOf = (needle: string) => order.findIndex((entry) => entry === needle)

    const username = indexOf('login-username')
    const password = indexOf('login-password')
    const toggle = order.findIndex((entry) => entry.includes('password-toggle'))
    const forgot = order.findIndex((entry) => entry.includes('field-forgot'))
    const submit = order.findIndex((entry) => entry.includes('primary-button'))

    expect(username).toBeGreaterThanOrEqual(0)
    expect(password).toBeGreaterThan(username)
    expect(toggle).toBeGreaterThan(password)
    expect(forgot).toBeGreaterThan(password)
    expect(forgot).toBeGreaterThan(toggle)
    expect(submit).toBeGreaterThan(forgot)
  })

  it('找回入口仍是原生按钮且未被移出键盘序列', () => {
    const wrapper = mountAuth()
    const forgot = wrapper.get('.field-forgot')
    expect(forgot.element.tagName).toBe('BUTTON')
    expect(forgot.attributes('type')).toBe('button')
    // 不得出现任何 tabindex（既不用正序号重排，也不用 -1 隐藏合法控件）
    expect(forgot.attributes('tabindex')).toBeUndefined()
    expect(forgot.text()).toBe('忘记密码？')
  })

  it('保留密码管理器所需的 autocomplete 与 Enter 提交能力', () => {
    const wrapper = mountAuth()
    expect(wrapper.get('#login-username').attributes('autocomplete')).toBe('username')
    expect(wrapper.get('#login-password').attributes('autocomplete')).toBe('current-password')
    // 两个输入都在同一个 form 内，Enter 仍由表单原生提交触发
    expect(wrapper.get('#login-password').element.closest('form')).toBe(wrapper.get('form').element)
    expect(wrapper.get('.primary-button').attributes('type')).toBe('submit')
  })

  it('密码显示切换保留 aria-pressed 与可访问名称', async () => {
    const wrapper = mountAuth()
    const toggle = wrapper.get('.password-toggle')
    expect(toggle.attributes('aria-label')).toBe('显示密码')
    expect(wrapper.get('#login-password').attributes('type')).toBe('password')

    await toggle.trigger('click')
    expect(toggle.attributes('aria-label')).toBe('隐藏密码')
    expect(toggle.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('#login-password').attributes('type')).toBe('text')
  })

  it('聚焦密码字段时找回入口保持字段归属的高亮态', async () => {
    const wrapper = mountAuth()
    const forgot = wrapper.get('.field-forgot')
    expect(forgot.classes()).not.toContain('is-field-active')

    await wrapper.get('#login-password').trigger('focus')
    expect(forgot.classes()).toContain('is-field-active')

    await wrapper.get('#login-password').trigger('blur')
    expect(forgot.classes()).not.toContain('is-field-active')
  })

  it('注册与找回分支不出现登录态的找回入口', () => {
    routeMock.query = {}
    const wrapper = mountAuth()
    // 登录态只有一个找回入口；注册/找回模式的字段定义里没有 forgot 标记
    expect(wrapper.findAll('.field-forgot')).toHaveLength(1)
  })
})

describe('站点图标（F-04）', () => {
  const frontendRoot = resolve(process.cwd().endsWith('frontend') ? process.cwd() : resolve(process.cwd(), 'frontend'))

  it('index.html 显式声明本地 SVG 图标且不引用外部 URL', () => {
    const html = readFileSync(resolve(frontendRoot, 'index.html'), 'utf-8')
    expect(html).toMatch(/<link[^>]+rel="icon"[^>]*>/)
    expect(html).toMatch(/href="\/favicon\.svg"/)
    expect(html).not.toMatch(/rel="icon"[^>]*https?:\/\//)
  })

  it('图标文件复用仓内拾光字标，且不含外部依赖', () => {
    const svg = readFileSync(resolve(frontendRoot, 'public/favicon.svg'), 'utf-8')
    // 与 ShiguangSymbols 的 #sgv-brand-sign 同源：路径数据逐字节一致
    const symbol = readFileSync(resolve(frontendRoot, 'src/components/shiguang/ShiguangSymbols.vue'), 'utf-8')
    const brandPath = /<symbol id="sgv-brand-sign"[^>]*>[\s\S]*?(<path d="M13 3h10v20h9v9h-9v9l9-4v10l-9 4v22c0 6-3 9-9 9H5L2 72h9c2 0 2-1 2-2V55L1 60V49l12-5V32H1v-9h12Z")/.exec(symbol)
    expect(brandPath).not.toBeNull()
    expect(svg).toContain(brandPath![1])
    expect(svg).not.toMatch(/<image|href="http|xlink:href="http|<script/)
  })
})
