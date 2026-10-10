import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RegisterView from './RegisterView.vue'

const authApiMock = vi.hoisted(() => ({
  register: vi.fn()
}))

const authStoreMock = vi.hoisted(() => ({
  login: vi.fn()
}))

const routerMock = vi.hoisted(() => ({
  replace: vi.fn()
}))

vi.mock('@/api/auth', () => ({ authApi: authApiMock }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authStoreMock }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: { success: vi.fn(), info: vi.fn() }
  }
})

const stubs = {
  'router-link': { props: ['to'], template: '<a><slot /></a>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  'el-input': {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-button': { template: '<button type="submit"><slot /></button>' }
}

const fillRequiredFields = async (wrapper: ReturnType<typeof mount>, confirmPassword: string) => {
  const inputs = wrapper.findAll('input')
  await inputs[0].setValue('alice')
  await inputs[3].setValue('password')
  await inputs[4].setValue(confirmPassword)
}

describe('RegisterView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authStoreMock.login.mockResolvedValue(undefined)
  })

  it('requires the confirmation password to match before calling the API', async () => {
    const wrapper = mount(RegisterView, { global: { stubs } })
    expect(wrapper.text()).toContain('确认密码')
    await fillRequiredFields(wrapper, 'different-password')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('两次输入的密码不一致')
    expect(authApiMock.register).not.toHaveBeenCalled()
  })

  it('sends only the registered API fields after matching confirmation', async () => {
    authApiMock.register.mockResolvedValueOnce(undefined)
    const wrapper = mount(RegisterView, { global: { stubs } })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('alice')
    await inputs[1].setValue('Alice')
    await inputs[2].setValue('alice@example.test')
    await inputs[3].setValue('password')
    await inputs[4].setValue('password')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(authApiMock.register).toHaveBeenCalledWith({
      username: 'alice',
      nickname: 'Alice',
      email: 'alice@example.test',
      password: 'password'
    })
    expect(authApiMock.register.mock.calls[0][0]).not.toHaveProperty('confirmPassword')
  })

  it('注册成功后自动登录并直达首页，附欢迎轻提示（AC-14）', async () => {
    const { ElMessage } = await import('element-plus')
    authApiMock.register.mockResolvedValueOnce(undefined)
    const wrapper = mount(RegisterView, { global: { stubs } })
    await fillRequiredFields(wrapper, 'password')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(authStoreMock.login).toHaveBeenCalledWith('alice', 'password')
    expect(routerMock.replace).toHaveBeenCalledWith('/')
    expect(ElMessage.success).toHaveBeenCalledWith('欢迎加入拾光')
  })

  it('自动登录失败时退回登录页并提示手动登录，不中断注册成功流', async () => {
    const { ElMessage } = await import('element-plus')
    authApiMock.register.mockResolvedValueOnce(undefined)
    authStoreMock.login.mockRejectedValueOnce(new Error('network down'))
    const wrapper = mount(RegisterView, { global: { stubs } })
    await fillRequiredFields(wrapper, 'password')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(ElMessage.info).toHaveBeenCalledWith('注册成功，请登录')
    expect(routerMock.replace).toHaveBeenCalledWith('/login')
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('maps backend validation errors to a field-level message instead of exposing Axios text', async () => {
    const error = Object.assign(new Error('Request failed with status code 422'), {
      response: {
        status: 422,
        data: {
          message: '请求参数不合法: username 个数必须在3和32之间',
          data: { username: '个数必须在3和32之间' }
        }
      }
    })
    authApiMock.register.mockRejectedValueOnce(error)
    const wrapper = mount(RegisterView, { global: { stubs } })
    await fillRequiredFields(wrapper, 'password')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('用户名：个数必须在3和32之间')
    expect(wrapper.text()).not.toContain('Request failed with status code 422')
  })
})
