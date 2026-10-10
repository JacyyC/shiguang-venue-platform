import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

const systemApiMock = vi.hoisted(() => ({
  users: vi.fn(),
  roles: vi.fn(),
  managedRoles: vi.fn(),
  permissionMenus: vi.fn(),
  configs: vi.fn(),
  operationLogs: vi.fn(),
  loginLogs: vi.fn(),
  userRoles: vi.fn(),
  venueAccess: vi.fn(),
  rolePermissions: vi.fn(),
  updateUser: vi.fn(),
  updateUserStatus: vi.fn(),
  resetUserPassword: vi.fn(),
  deleteUser: vi.fn(),
  assignRoles: vi.fn(),
  updateVenueAccess: vi.fn(),
  createManagedRole: vi.fn(),
  replaceRolePermissions: vi.fn(),
  updateConfig: vi.fn(),
  dict: vi.fn()
}))
const statisticsApiMock = vi.hoisted(() => ({ dashboard: vi.fn() }))
const venueApiMock = vi.hoisted(() => ({ list: vi.fn() }))
const toastMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/system', () => ({ systemApi: systemApiMock }))
vi.mock('@/api/statistics', () => ({ statisticsApi: statisticsApiMock }))
vi.mock('@/api/venue', () => ({ venueApi: venueApiMock, venueAdminApi: {} }))
vi.mock('@/api/notice', () => ({ noticeApi: { unreadCount: vi.fn().mockResolvedValue(0) } }))

const granted = vi.hoisted(() => new Set<string>())

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: (key: string) => granted.has(key) })
}))
vi.mock('@/stores/app', () => ({
  useAppStore: () => ({ showToast: toastMock, toastMessage: '' })
}))

import ShiguangPlatformAdminView from './ShiguangPlatformAdminView.vue'
import { ISOLATED_REQUEST } from '../adminShared'

const ALL_PLATFORM_PERMISSIONS = [
  'system:view',
  'system:user:manage',
  'system:role:manage',
  'system:config:manage',
  'system:log:view'
]

const users = [
  { id: 11, username: 'lin.cheng.demo', nickname: '林澄', status: 'ACTIVE', roleId: 2, email: 'lin.cheng@example.test', lastLoginTime: '2026-09-12T09:08:00', createTime: '2026-08-16T00:00:00' },
  { id: 9, username: 'zhou.yuan.demo', status: 'DISABLED', roleId: 2, lastLoginTime: '2026-09-11T18:20:00', createTime: '2026-08-02T00:00:00' }
]

const roleOptions = [
  { id: 1, name: '普通用户', key: 'USER', dataScope: 'SELF' },
  { id: 2, name: '场馆管理员', key: 'VENUE_ADMIN', dataScope: 'VENUE' },
  { id: 3, name: '系统管理员', key: 'SYSTEM_ADMIN', dataScope: 'ALL' }
]

const managedRoles = [
  { id: 2, name: '场馆管理员', key: 'VENUE_ADMIN', dataScope: 'VENUE', status: 'ACTIVE', builtIn: true },
  { id: 4, name: '渠道只读值班员', key: 'CHANNEL_OBSERVER', dataScope: 'SELF', status: 'ACTIVE', builtIn: false }
]

const permissionMenus = [
  { id: 20, name: '系统后台', parentId: 0, permission: 'system:view', menuType: 'MENU', visible: 'Y' },
  { id: 23, name: '参数管理', parentId: 20, permission: 'system:config:manage', menuType: 'MENU', visible: 'Y' },
  { id: 54, name: 'Provider 观测', parentId: 20, permission: 'provider:observe', menuType: 'MENU', visible: 'Y' },
  { id: 55, name: 'Provider 刷新', parentId: 54, permission: 'provider:refresh', menuType: 'BUTTON', visible: 'N' }
]

const configs = [
  { id: 1, configKey: 'reservation.advance_days', configValue: '90', configName: '可提前预约天数', description: '用户最多可提前预约的天数', valueType: 'NUMBER', editable: 'Y' },
  { id: 3, configKey: 'reservation.auto_approval', configValue: 'false', configName: '自动审核开关', description: '开启后提交预约直接进入待支付', valueType: 'BOOLEAN', editable: 'Y' }
]

const operationLogs = [
  { id: 114, username: 'sgc1_va', ip: '0:0:0:0:0:0:0:1', module: '预约审核', action: '审核通过', resultStatus: 'SUCCESS', createTime: '2026-09-13T21:48:12' },
  { id: 112, username: 'sgc1_user2', ip: '127.0.0.1', module: '预约管理', action: '提交预约', resultStatus: 'FAIL', errorMessage: '所选座位在该时段已被预约', createTime: '2026-09-12T09:26:00' }
]

const loginLogs = [
  { id: 173, username: 'sgc1_va', ip: '127.0.0.1', status: 'SUCCESS', message: '登录成功', createTime: '2026-09-13T21:48:00' }
]

const venues = [
  { id: 101, name: '北岸生活场馆', area: '北岸片区', status: 'ACTIVE' },
  { id: 102, name: '东园运动中心', status: 'ACTIVE' }
]

const page = (records: unknown[]) => ({ records, total: records.length, pageNum: 1, pageSize: 100 })

beforeEach(() => {
  vi.clearAllMocks()
  setActivePinia(createPinia())
  granted.clear()
  ALL_PLATFORM_PERMISSIONS.forEach((key) => granted.add(key))
  systemApiMock.users.mockResolvedValue(page(users))
  systemApiMock.roles.mockResolvedValue(roleOptions)
  systemApiMock.managedRoles.mockResolvedValue(managedRoles)
  systemApiMock.permissionMenus.mockResolvedValue(permissionMenus)
  systemApiMock.configs.mockResolvedValue(configs)
  systemApiMock.operationLogs.mockResolvedValue(page(operationLogs))
  systemApiMock.loginLogs.mockResolvedValue(page(loginLogs))
  systemApiMock.userRoles.mockResolvedValue([2])
  systemApiMock.venueAccess.mockResolvedValue([101])
  systemApiMock.rolePermissions.mockResolvedValue([20, 23, 54, 55])
  systemApiMock.updateUser.mockResolvedValue(users[0])
  systemApiMock.assignRoles.mockResolvedValue(undefined)
  systemApiMock.updateVenueAccess.mockResolvedValue(undefined)
  systemApiMock.createManagedRole.mockResolvedValue(managedRoles[1])
  systemApiMock.replaceRolePermissions.mockResolvedValue(undefined)
  systemApiMock.updateConfig.mockResolvedValue(undefined)
  venueApiMock.list.mockResolvedValue(page(venues))
  statisticsApiMock.dashboard.mockResolvedValue({ todayReservations: 7, pendingReviews: 2, totalUsers: 5, revenue: 437.36, occupancyRate: 46.8 })
  const proto = (globalThis.HTMLDialogElement as unknown as { prototype: Record<string, () => void> }).prototype
  proto.showModal = proto.showModal ?? (() => {})
  proto.close = proto.close ?? (() => {})
})

async function mountView(routePath = '/system-admin/users', routeName = 'SystemUsers'): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/system-admin', name: 'SystemAdminDashboard', component: ShiguangPlatformAdminView },
      { path: '/system-admin/users', name: 'SystemUsers', component: ShiguangPlatformAdminView },
      { path: '/system-admin/roles', name: 'SystemRoles', component: ShiguangPlatformAdminView },
      { path: '/system-admin/configs', name: 'SystemConfigs', component: ShiguangPlatformAdminView },
      { path: '/system-admin/logs', name: 'SystemLogs', component: ShiguangPlatformAdminView }
    ]
  })
  await router.push(routePath)
  await router.isReady()
  const wrapper = mount(ShiguangPlatformAdminView, { global: { plugins: [router] } })
  for (let i = 0; i < 4; i += 1) await flushPromises()
  return wrapper
}

/** 切页签并等待该页签的读取完成（bootstrap 内含两次串行回读）。 */
async function openTab(wrapper: ReturnType<typeof mount>, tab: string): Promise<void> {
  await wrapper.find(`[data-test="tab-${tab}"]`).trigger('click')
  for (let i = 0; i < 3; i += 1) await flushPromises()
}

describe('ShiguangPlatformAdminView', () => {
  it('users 路由进入账号与授权并按真实字段渲染列表与详情', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('把权限交给合适的人')
    expect(wrapper.find('[data-test="users-context"]').text()).toBe('账号与授权 / 2 个账号')
    const rows = wrapper.findAll('[data-test^="user-"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('林澄')
    expect(rows[0].text()).toContain('场馆管理员 · 正常')
    // 无昵称账号回退用户名；停用账号走共享标签与告警徽标
    expect(rows[1].text()).toContain('zhou.yuan.demo')
    expect(rows[1].text()).toContain('停用')
    const detail = wrapper.text()
    expect(detail).toContain('#11')
    expect(detail).toContain('l***@example.test')
    expect(detail).toContain('9 月 12 日 09:08')
    expect(detail).toContain('2026 年 8 月 16 日')
  })

  it('切换账号按所选账号回读角色与场馆授权', async () => {
    systemApiMock.userRoles.mockResolvedValueOnce([2]).mockResolvedValue([1])
    systemApiMock.venueAccess.mockResolvedValueOnce([101]).mockResolvedValue([])
    const wrapper = await mountView()
    expect(systemApiMock.userRoles).toHaveBeenCalledWith(11, ISOLATED_REQUEST)
    await wrapper.find('[data-test="user-9"]').trigger('click')
    for (let i = 0; i < 3; i += 1) await flushPromises()
    // 回归：早期实现把下标当账号 id 传入，点击不会换选中账号
    expect(systemApiMock.userRoles).toHaveBeenLastCalledWith(9, ISOLATED_REQUEST)
    expect(wrapper.find('[data-test="user-9"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-test="save-access"]').exists()).toBe(true)
  })

  it('场馆授权复选带服务端区域说明与无授权提示', async () => {
    systemApiMock.venueAccess.mockResolvedValue([])
    const wrapper = await mountView()
    const checked = wrapper.findAll('[data-test^="venue-check-"]')
    expect(checked).toHaveLength(2)
    // 回归：早期实现直接渲染 VenueListItem，note 字段不存在导致区域说明为空
    expect(checked[0].attributes('value')).toBe('101')
    expect(wrapper.text()).toContain('所在区域：北岸片区')
    expect(wrapper.text()).toContain('平台登记场馆')
    expect(wrapper.text()).toContain('这个账号当前没有场馆授权。平台观测角色不会因此获得场馆管理范围。')
  })

  it('编辑资料提交服务端必填全集（含 username 与 status）', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-test="edit-user"]').trigger('click')
    await wrapper.find('input[data-test="pf-nickname"]').setValue('林澄·改名')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 4; i += 1) await flushPromises()
    expect(systemApiMock.updateUser).toHaveBeenCalledWith(11, {
      username: 'lin.cheng.demo',
      nickname: '林澄·改名',
      phone: undefined,
      email: 'lin.cheng@example.test',
      status: 'ACTIVE'
    }, ISOLATED_REQUEST)
  })

  it('显示名称留空时不关闭弹层也不发请求', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-test="edit-user"]').trigger('click')
    await wrapper.find('input[data-test="pf-nickname"]').setValue('   ')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 3; i += 1) await flushPromises()
    // 回归：校验写在提交流程里会被随后的关闭动作吞掉
    expect(systemApiMock.updateUser).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('请输入显示名称。')
    expect(wrapper.find('[data-test="edit-user"]').exists()).toBe(true)
  })

  it('联系电话格式不合法时在提交前拦截并保留弹层', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-test="edit-user"]').trigger('click')
    await wrapper.find('input[data-test="pf-nickname"]').setValue('林澄·改名')
    await wrapper.find('input[data-test="pf-phone"]').setValue('12345')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 3; i += 1) await flushPromises()
    expect(systemApiMock.updateUser).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('请输入 11 位有效手机号')

    await wrapper.find('input[data-test="pf-phone"]').setValue('13800138000')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 4; i += 1) await flushPromises()
    expect(systemApiMock.updateUser).toHaveBeenCalledWith(
      11, expect.objectContaining({ phone: '13800138000' }), ISOLATED_REQUEST)
  })

  it('删除账号需要完整输入 DELETE，服务端守卫消息如实展示', async () => {
    systemApiMock.deleteUser.mockRejectedValue({ response: { data: { message: '不能删除最后一个系统管理员' } } })
    const wrapper = await mountView()
    await wrapper.find('[data-test="delete-user"]').trigger('click')
    expect(wrapper.text()).toContain('并保留审计快照，删除后不可恢复')
    await wrapper.find('input[data-test="pf-delete"]').setValue('delete')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 3; i += 1) await flushPromises()
    expect(systemApiMock.deleteUser).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('请完整输入 DELETE。')
    await wrapper.find('input[data-test="pf-delete"]').setValue('DELETE')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 3; i += 1) await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('不能删除最后一个系统管理员')
  })

  it('授权核对按角色→场馆两次独立提交，第二项失败保留已生效事实', async () => {
    systemApiMock.updateVenueAccess.mockRejectedValue({ response: { data: { message: '用户未拥有有效场馆管理员角色' } } })
    const wrapper = await mountView()
    await wrapper.find('[data-test="save-access"]').trigger('click')
    const summary = wrapper.find('[data-test="dialog-error"]').exists()
    expect(summary).toBe(false)
    expect(wrapper.text()).toContain('角色')
    expect(wrapper.text()).toContain('北岸生活场馆')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 5; i += 1) await flushPromises()
    expect(systemApiMock.assignRoles).toHaveBeenCalledWith(11, [2], ISOLATED_REQUEST)
    expect(systemApiMock.updateVenueAccess).toHaveBeenCalledWith(11, [101], ISOLATED_REQUEST)
    // 回归：部分失败消息曾被服务端原始消息覆盖，丢失"角色已保存"半句
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe(
      '角色已保存，但场馆授权未能保存：用户未拥有有效场馆管理员角色'
    )
    expect(toastMock).not.toHaveBeenCalledWith('角色与场馆范围已保存')
  })

  it('看板读真实统计且不出现预览固定数值', async () => {
    const wrapper = await mountView()
    await openTab(wrapper, 'dashboard')
    const panel = wrapper.find('[data-test="dashboard-panel"]')
    expect(panel.text()).toContain('7')
    expect(panel.text()).toContain('¥ 437.36')
    expect(panel.text()).toContain('46.8%')
    expect(panel.text()).not.toContain('2,486')
    expect(panel.text()).not.toContain('¥ 6,240.00')
    expect(wrapper.text()).toContain('数值来自服务端当日统计')
    expect(wrapper.find('[data-test="goto-providers"]').attributes('href')).toBe('/system-admin/providers')
  })

  it('内置角色只读：勾选禁用、无保存条、提示逐字', async () => {
    const wrapper = await mountView('/system-admin/roles', 'SystemRoles')
    expect(wrapper.text()).toContain('角色与叶子权限')
    expect(wrapper.text()).toContain('工作台入口、查看与执行动作分别授予。')
    const builtIn = wrapper.find('[data-test="managed-role-2"]')
    expect(builtIn.text()).toContain('内置角色 · 只读')
    expect(builtIn.attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('内置角色的权限仅供核对，不能在这里修改。需要独立授权组合时，请创建动态角色。')
    expect(wrapper.find('[data-test="save-role"]').exists()).toBe(false)
    expect(wrapper.find('input[data-test="perm-check-23"]').attributes('disabled')).toBeDefined()
  })

  it('动态角色按根菜单分组回显已配权限并整组替换', async () => {
    const wrapper = await mountView('/system-admin/roles', 'SystemRoles')
    await wrapper.find('[data-test="managed-role-4"]').trigger('click')
    for (let i = 0; i < 3; i += 1) await flushPromises()
    expect(systemApiMock.rolePermissions).toHaveBeenCalledWith(4, ISOLATED_REQUEST)
    // 真实清单含按钮型权限点，刷新分权必须可授予
    expect(wrapper.find('input[data-test="perm-check-55"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('4 项已保存权限')
    await wrapper.find('input[data-test="perm-check-23"]').setValue(false)
    await wrapper.find('[data-test="save-role"]').trigger('click')
    expect(wrapper.text()).toContain('将完整替换为以下 3 项权限：')
    expect(wrapper.text()).toContain('拥有 Provider 查看权限，不会自动获得刷新权限。')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 5; i += 1) await flushPromises()
    expect(systemApiMock.replaceRolePermissions).toHaveBeenCalledWith(4, [20, 54, 55], ISOLATED_REQUEST)
  })

  it('创建动态角色提交名称与标识', async () => {
    const wrapper = await mountView('/system-admin/roles', 'SystemRoles')
    await wrapper.find('[data-test="create-role"]').trigger('click')
    await wrapper.find('input[data-test="pf-role-name"]').setValue('渠道只读值班员')
    await wrapper.find('input[data-test="pf-role-key"]').setValue('channel_observer')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 5; i += 1) await flushPromises()
    // roleKey 归一化大写由服务端负责（B2-T28），前端原样提交
    expect(systemApiMock.createManagedRole).toHaveBeenCalledWith({ roleName: '渠道只读值班员', roleKey: 'channel_observer' }, ISOLATED_REQUEST)
  })

  it('参数页签按服务端登记全集渲染，布尔用开关、数值按 valueType', async () => {
    const wrapper = await mountView('/system-admin/configs', 'SystemConfigs')
    expect(wrapper.text()).toContain('这里展示既有预约与支付参数，不提供环境部署或渠道生产开关。')
    expect(wrapper.find('[data-test="config-edit-reservation.advance_days"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="config-edit-reservation.auto_approval"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('关闭')
    await wrapper.find('[data-test="config-edit-reservation.advance_days"]').trigger('click')
    expect(wrapper.find('select[data-test="pf-config"]').exists()).toBe(false)
    await wrapper.find('input[data-test="pf-config"]').setValue('120')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 5; i += 1) await flushPromises()
    expect(systemApiMock.updateConfig).toHaveBeenCalledWith('reservation.advance_days', '120', ISOLATED_REQUEST)
  })

  it('参数越界时展示服务端范围消息', async () => {
    systemApiMock.updateConfig.mockRejectedValue({ response: { data: { message: '配置 reservation.max_slots_per_booking 必须在 1 到 48 之间' } } })
    const wrapper = await mountView('/system-admin/configs', 'SystemConfigs')
    await wrapper.find('[data-test="config-edit-reservation.advance_days"]').trigger('click')
    await wrapper.find('input[data-test="pf-config"]').setValue('9999')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 3; i += 1) await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toContain('必须在')
  })

  it('日志页签出中文徽标、两行时间与脱敏地址，详情弹层只读', async () => {
    const wrapper = await mountView('/system-admin/logs', 'SystemLogs')
    const rows = wrapper.findAll('[data-test^="log-detail-"]')
    expect(rows).toHaveLength(2)
    const panel = wrapper.find('[data-test="logs-panel"]')
    expect(panel.text()).toContain('审核通过')
    expect(panel.text()).toContain('sgc1_va · 预约审核')
    expect(panel.text()).toContain('所选座位在该时段已被预约')
    expect(panel.text()).toContain('成功')
    expect(panel.text()).toContain('失败')
    expect(panel.text()).not.toContain('SUCCESS')
    expect(panel.find('time').text()).toContain('09/13')
    expect(panel.text()).toContain('日志只有查看入口，不能在这里修改或删除。')
    await wrapper.find('[data-test="log-detail-112"]').trigger('click')
    const modal = wrapper.find('.c-modal')
    expect(modal.text()).toContain('127.0.0.***')
    expect(modal.text()).not.toContain('127.0.0.1')
    expect(modal.text()).toContain('2026-09-12 09:26')
    expect(modal.text()).toContain('发生日期')
    expect(wrapper.find('[data-test="confirm-log-detail"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="dialog-close-detail"]').exists()).toBe(true)
  })

  it('登录子页签切换后读同一批真实记录', async () => {
    const wrapper = await mountView('/system-admin/logs', 'SystemLogs')
    await wrapper.find('[data-test="log-login"]').trigger('click')
    for (let i = 0; i < 2; i += 1) await flushPromises()
    const panel = wrapper.find('[data-test="logs-panel"]')
    expect(panel.text()).toContain('登录成功')
    expect(panel.text()).toContain('sgc1_va')
    expect(systemApiMock.loginLogs).toHaveBeenCalled()
  })

  it('缺动作权限时按页签呈现无权态且不请求该领域端点', async () => {
    granted.delete('system:user:manage')
    const wrapper = await mountView()
    expect(wrapper.find('[data-test="denied-users"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="users-panel"]').exists()).toBe(false)
    expect(systemApiMock.users).not.toHaveBeenCalled()
    expect(systemApiMock.userRoles).not.toHaveBeenCalled()
    expect(systemApiMock.venueAccess).not.toHaveBeenCalled()
    // 角色领域由各自权限点驱动，未受影响
    expect(systemApiMock.managedRoles).toHaveBeenCalled()
    // 其余领域仍可用
    expect(wrapper.find('[data-test="logs-panel"]').exists()).toBe(false)
    await openTab(wrapper, 'logs')
    expect(wrapper.find('[data-test="logs-panel"]').exists()).toBe(true)
  })

  it('hash 深链优先于路由名，与预览 #roles 形态兼容', async () => {
    const wrapper = await mountView({ path: '/system-admin/users', hash: '#roles' } as never, 'SystemUsers')
    expect(wrapper.find('[data-test="tab-roles"]').attributes('aria-current')).toBe('true')
    expect(wrapper.find('[data-test="roles-panel"]').exists()).toBe(true)
  })

  it('读取失败呈现可重试错误态而不是空范围', async () => {
    systemApiMock.configs.mockRejectedValue(new Error('network'))
    const wrapper = await mountView('/system-admin', 'SystemAdminDashboard')
    expect(wrapper.find('[data-test="surface-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('这次读取没有成功，不能据此判断当前范围没有数据。')
  })
})
