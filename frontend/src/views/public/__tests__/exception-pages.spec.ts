import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ForbiddenView from '../ForbiddenView.vue'
import NotFoundView from '../NotFoundView.vue'

// F-24 直接组件合同：锁定公共异常页的原生 main 地标、标题命名与两个路由动作。
// 该合同在移除覆盖角色 / 补 aria-labelledby 之前必须失败（红），修复后通过（绿）。

const routerMock = vi.hoisted(() => ({
  back: vi.fn(),
  replace: vi.fn()
}))

const stubs = {
  'el-button': {
    emits: ['click'],
    template: '<button @click="$emit(\'click\', $event)"><slot /></button>'
  }
}

const mountPage = (component: typeof ForbiddenView | typeof NotFoundView) =>
  mount(component, {
    global: {
      stubs,
      mocks: { $router: routerMock }
    }
  })

const clickButtonByText = async (wrapper: ReturnType<typeof mountPage>, text: string) => {
  const button = wrapper
    .findAll('button')
    .find((b) => (b.text() || '').trim().includes(text))
  if (!button) throw new Error(`未找到文案包含「${text}」的按钮`)
  await button.trigger('click')
}

describe.each([
  {
    name: 'ForbiddenView (/403)',
    component: ForbiddenView,
    heading: '无权访问',
    code: '403'
  },
  {
    name: 'NotFoundView (404)',
    component: NotFoundView,
    heading: '页面不存在',
    code: '404'
  }
])('$name 主地标语义', ({ component, heading, code }) => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('根节点是原生 <main>，不被 alert/status 等角色覆盖隐式地标语义', () => {
    const wrapper = mountPage(component)
    const main = wrapper.find('main')
    expect(main.exists()).toBe(true)
    const role = main.attributes('role')
    // 关键：显式 role=alert/status 会替换 main 地标 —— 合同要求根 main 不得带覆盖角色
    expect(role).toBeUndefined()
    expect(main.attributes('aria-live')).toBeUndefined()
  })

  it('main 通过 aria-labelledby 指向本页唯一 h1，可访问名称与页面标题一致', () => {
    const wrapper = mountPage(component)
    const main = wrapper.find('main')
    const labelledBy = main.attributes('aria-labelledby')
    expect(labelledBy, 'main 必须通过 aria-labelledby 命名').toBeTruthy()
    const h1s = wrapper.findAll('h1')
    expect(h1s).toHaveLength(1)
    const h1 = h1s[0]
    expect(h1.attributes('id'), '唯一 h1 必须带稳定 id').toBeTruthy()
    expect(labelledBy).toBe(h1.attributes('id'))
    expect(h1.text().trim()).toBe(heading)
    // 关联节点确实存在于组件 DOM 树中，且文本即页面标题（可访问名称来源）
    const target = wrapper.find(`#${labelledBy}`)
    expect(target.exists(), 'aria-labelledby 指向的 id 必须存在于本页').toBe(true)
    expect(target.text().trim()).toBe(heading)
  })

  it('保留页面文案与两个既有路由动作', async () => {
    const wrapper = mountPage(component)
    expect(wrapper.find('.simple-code').text()).toBe(code)
    expect(wrapper.text()).toContain(heading)
    const buttons = wrapper.findAll('button').map((b) => b.text().trim())
    expect(buttons).toContain('返回上一页')
    expect(buttons).toContain('回到首页')

    await clickButtonByText(wrapper, '返回上一页')
    expect(routerMock.back).toHaveBeenCalledTimes(1)
    await clickButtonByText(wrapper, '回到首页')
    expect(routerMock.replace).toHaveBeenCalledWith('/')
  })
})

it('403 与 404 使用互不重复的标题 ID', () => {
  const forbidden = mountPage(ForbiddenView).find('main').attributes('aria-labelledby')
  const notFound = mountPage(NotFoundView).find('main').attributes('aria-labelledby')
  expect(forbidden).toBeTruthy()
  expect(notFound).toBeTruthy()
  expect(forbidden).not.toBe(notFound)
})
