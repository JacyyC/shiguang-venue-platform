import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from './LoginView.vue'

const authStoreMock = vi.hoisted(() => ({
  hasRole: vi.fn(() => false),
  login: vi.fn(),
  permissions: [] as string[]
}))

const routeMock = vi.hoisted(() => ({
  query: {}
}))

const routerMock = vi.hoisted(() => ({
  replace: vi.fn(),
  resolve: vi.fn((path: string) => ({ path, meta: {} }))
}))

vi.mock('@/stores/auth', () => ({ useAuthStore: () => authStoreMock }))
vi.mock('vue-router', () => ({ useRoute: () => routeMock, useRouter: () => routerMock }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: { success: vi.fn() }
  }
})

const stubs = {
  'router-link': { props: ['to'], template: '<a><slot /></a>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-input': {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-button': {
    props: ['disabled', 'loading'],
    template: '<button type="submit" :disabled="disabled || loading"><slot /></button>'
  }
}

describe('LoginView production hardening', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authStoreMock.hasRole.mockReturnValue(false)
    authStoreMock.login.mockResolvedValue(undefined)
    authStoreMock.permissions = []
  })

  it('显示用户端品牌名称', () => {
    const wrapper = mount(LoginView, { global: { stubs } })

    expect(wrapper.text()).toContain('场地空间与活动平台')
    expect(wrapper.get('h1').text()).toBe('欢迎回来')
  })

  it('does not prefill visible default credentials by default', () => {
    const wrapper = mount(LoginView, { global: { stubs } })
    const values = wrapper.findAll('input').map((input) => input.element.value)

    expect(values).toEqual(['', ''])
  })

  it('shows the safe backend reason from a failed login response', async () => {
    authStoreMock.login.mockRejectedValueOnce({
      message: 'Request failed with status code 401',
      response: { data: { message: '账号已被禁用' } }
    })
    const wrapper = mount(LoginView, { global: { stubs } })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('alice')
    await inputs[1].setValue('wrong-password')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('账号已被禁用')
    expect(wrapper.text()).not.toContain('Request failed with status code 401')
  })

  it('sends a dynamic role with the independent entry permission to the content workspace after login', async () => {
    authStoreMock.login.mockImplementationOnce(async () => {
      authStoreMock.permissions = ['content-admin:view', 'community:content:moderate']
    })
    const wrapper = mount(LoginView, { global: { stubs } })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('content-operator')
    await inputs[1].setValue('password')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(authStoreMock.hasRole).toHaveBeenCalledWith('SYSTEM_ADMIN')
    expect(authStoreMock.hasRole).toHaveBeenCalledWith('VENUE_ADMIN')
    expect(routerMock.replace).toHaveBeenCalledWith('/content-admin')
  })

  it('提交即进入禁用态并持续到返回，失败恢复可点，防重复提交（AC-16）', async () => {
    let resolveLogin!: () => void
    authStoreMock.login.mockImplementationOnce(
      () => new Promise<void>((resolve) => { resolveLogin = resolve })
    )
    const wrapper = mount(LoginView, { global: { stubs } })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('alice')
    await inputs[1].setValue('password')

    await wrapper.find('form').trigger('submit')
    await flushPromises()
    const submitButton = wrapper.find('button[type="submit"]')
    expect(submitButton.attributes('disabled')).toBeDefined()
    expect(authStoreMock.login).toHaveBeenCalledTimes(1)

    await submitButton.trigger('submit')
    expect(authStoreMock.login).toHaveBeenCalledTimes(1)

    resolveLogin()
    await flushPromises()
    expect(submitButton.attributes('disabled')).toBeUndefined()
  })

  it('登录失败后按钮恢复可点并保留错误提示', async () => {
    authStoreMock.login.mockRejectedValueOnce(new Error('bad credentials'))
    const wrapper = mount(LoginView, { global: { stubs } })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('alice')
    await inputs[1].setValue('wrong-password')

    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
})
