import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UsersView from './UsersView.vue'

const systemApiMock = vi.hoisted(() => ({
  users: vi.fn(),
  roles: vi.fn(),
  updateUser: vi.fn(),
  updateUserStatus: vi.fn(),
  assignRoles: vi.fn(),
  userRoles: vi.fn(),
  venueAccess: vi.fn(),
  updateVenueAccess: vi.fn(),
  resetUserPassword: vi.fn(),
  deleteUser: vi.fn()
}))

const promptMock = vi.hoisted(() => vi.fn())
const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/system', () => ({ systemApi: systemApiMock }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: { success: vi.fn(), warning: vi.fn(), info: vi.fn(), error: vi.fn() },
    ElMessageBox: { prompt: promptMock, confirm: confirmMock }
  }
})

const stubs = {
  'el-alert': { props: ['title', 'description'], template: '<div><strong>{{ title }}</strong><span>{{ description }}</span></div>' },
  'el-button': { emits: ['click'], template: '<button type="button" @click="$emit(\'click\')"><slot /></button>' },
  'el-checkbox': {
    props: ['label', 'value'],
    template: '<label :data-label="label" :data-value="value"><slot /></label>'
  },
  'el-checkbox-group': { template: '<div><slot /></div>' },
  'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
  'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-option': true,
  'el-pagination': true,
  'el-select': { template: '<select><slot /></select>' },
  'el-table': { template: '<div><slot /></div>' },
  'el-table-column': {
    data: () => ({ row: { id: 7, username: 'venue-admin', status: 'ACTIVE' } }),
    template: '<div><slot :row="row" /></div>'
  },
  'el-tag': { template: '<span><slot /></span>' }
}

const mountView = () => mount(UsersView, {
  global: {
    stubs,
    directives: { loading: {} }
  }
})

const clickButton = async (wrapper: ReturnType<typeof mount>, label: string) => {
  const button = wrapper.findAll('button').find((item) => item.text() === label)
  await button?.trigger('click')
}

describe('UsersView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    systemApiMock.users.mockResolvedValue({
      records: [{ id: 7, username: 'venue-admin', status: 'ACTIVE' }],
      total: 1,
      pageNum: 1,
      pageSize: 30
    })
    systemApiMock.roles.mockResolvedValue([
      { id: 1, name: '普通用户', key: 'USER', dataScope: 'SELF' },
      { id: 2, name: '场馆管理员', key: 'VENUE_ADMIN', dataScope: 'VENUE' },
      { id: 3, name: '系统管理员', key: 'SYSTEM_ADMIN', dataScope: 'ALL' }
    ])
    systemApiMock.userRoles.mockResolvedValue([2])
    systemApiMock.venueAccess.mockResolvedValue([11])
    systemApiMock.updateVenueAccess.mockResolvedValue(undefined)
    systemApiMock.assignRoles.mockResolvedValue(undefined)
    promptMock.mockResolvedValue({ value: '11,12' })
    confirmMock.mockResolvedValue('confirm')
  })

  it('prefills and saves the controlled venue access assignment with its current status', async () => {
    const wrapper = mountView()
    await flushPromises()

    await clickButton(wrapper, '场馆授权')
    await flushPromises()

    expect(systemApiMock.venueAccess).toHaveBeenCalledWith(7)
    expect(promptMock).toHaveBeenCalledWith(
      expect.stringContaining('当前已授权 1 个场馆'),
      '场馆授权',
      expect.objectContaining({ inputValue: '11' })
    )
    expect(systemApiMock.updateVenueAccess).toHaveBeenCalledWith(7, [11, 12])
  })

  it('loads click-selectable current roles and explains the venue scope prerequisite', async () => {
    systemApiMock.venueAccess.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()

    await clickButton(wrapper, '分配角色')
    await flushPromises()

    expect(systemApiMock.roles).toHaveBeenCalledOnce()
    expect(systemApiMock.userRoles).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('普通用户')
    expect(wrapper.text()).toContain('场馆管理员')
    expect(wrapper.text()).toContain('授权场馆')
    expect(wrapper.text()).toContain('场馆管理员还需要场馆范围授权')
    expect(wrapper.text()).toContain('当前尚未授权任何场馆')
    expect(wrapper.text()).not.toContain('输入角色 ID')
  })

  it('does not write role changes when the second confirmation is cancelled', async () => {
    confirmMock.mockRejectedValueOnce('cancel')
    const wrapper = mountView()
    await flushPromises()
    await clickButton(wrapper, '分配角色')
    await flushPromises()

    await clickButton(wrapper, '保存角色')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledWith(
      expect.stringContaining('venue-admin'),
      '确认角色分配',
      expect.objectContaining({ confirmButtonText: '确认保存' })
    )
    expect(systemApiMock.assignRoles).not.toHaveBeenCalled()
  })

  it('writes the selected server role ids only after explicit confirmation', async () => {
    const wrapper = mountView()
    await flushPromises()
    await clickButton(wrapper, '分配角色')
    await flushPromises()

    await clickButton(wrapper, '保存角色')
    await flushPromises()

    expect(systemApiMock.assignRoles).toHaveBeenCalledWith(7, [2])
    expect(promptMock).not.toHaveBeenCalledWith(expect.stringContaining('角色 ID'), expect.anything(), expect.anything())
  })

  it('blocks an invalid edited phone before calling the server', async () => {
    const wrapper = mountView()
    await flushPromises()
    await clickButton(wrapper, '编辑')
    await flushPromises()

    const phoneInput = wrapper.find('[data-test="user-phone"]')
    await phoneInput.setValue('12345')
    await clickButton(wrapper, '保存')
    await flushPromises()

    expect(wrapper.find('[data-test="user-phone-error"]').text()).toBe('请输入 11 位有效手机号')
    expect(systemApiMock.updateUser).not.toHaveBeenCalled()

    await phoneInput.setValue('13800138000')
    await clickButton(wrapper, '保存')
    await flushPromises()

    expect(systemApiMock.updateUser).toHaveBeenCalledWith(7, expect.objectContaining({ phone: '13800138000' }))
  })

  it('binds role choices through checkbox values without relying on deprecated labels', async () => {
    const wrapper = mountView()
    await flushPromises()
    await clickButton(wrapper, '分配角色')
    await flushPromises()

    expect(wrapper.findAll('[data-value]').map((item) => item.attributes('data-value'))).toEqual(['1', '2', '3'])
    expect(wrapper.findAll('[data-label]').every((item) => item.attributes('data-label') === undefined)).toBe(true)
  })
})
