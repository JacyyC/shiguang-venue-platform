import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RolesView from './RolesView.vue'

const apiMock = vi.hoisted(() => ({
  managedRoles: vi.fn(),
  permissionMenus: vi.fn(),
  rolePermissions: vi.fn(),
  createManagedRole: vi.fn(),
  replaceRolePermissions: vi.fn()
}))
const permissions = vi.hoisted(() => new Set<string>())

vi.mock('@/api/system', () => ({ systemApi: apiMock }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: (permission: string) => permissions.has(permission) })
}))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return { ...actual, ElMessage: { success: vi.fn() } }
})

const stubs = {
  'el-button': {
    props: ['disabled', 'loading'],
    emits: ['click'],
    template: '<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>'
  },
  'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue" class="stub-dialog"><slot /><slot name="footer" /></div>' },
  'el-skeleton': { template: '<div>加载中</div>' },
  Plus: true
}

const roles = [
  { id: 10, name: '普通用户', key: 'USER', dataScope: 'SELF', status: 'ACTIVE', builtIn: true },
  { id: 11, name: '内容运营', key: 'CONTENT_OPERATOR', dataScope: 'SELF', status: 'ACTIVE', builtIn: false },
  { id: 12, name: '系统管理员', key: 'SYSTEM_ADMIN', dataScope: 'ALL', status: 'ACTIVE', builtIn: true }
]

// 覆盖三类分组与父子结构：BUTTON 子项挂在可见 MENU 父项下。
const menus = [
  { id: 1, name: '内容运营后台', parentId: null, path: '/content-admin', permission: 'content-admin:view', menuType: 'MENU', visible: 'Y' },
  { id: 2, name: '举报审核', parentId: 1, path: '', permission: 'community:report:review', menuType: 'BUTTON', visible: 'N' },
  { id: 3, name: '宣传位候选管理', parentId: 1, path: '', permission: 'placement:manage', menuType: 'BUTTON', visible: 'N' },
  { id: 4, name: '广告资料管理', parentId: 1, path: '', permission: 'advertising:manage', menuType: 'BUTTON', visible: 'N' },
  { id: 5, name: '系统后台', parentId: null, path: '/system-admin', permission: 'system:view', menuType: 'MENU', visible: 'Y' }
]

const mountView = async () => {
  const wrapper = mount(RolesView, { global: { stubs } })
  await flushPromises()
  return wrapper
}

const findGroup = (wrapper: Awaited<ReturnType<typeof mountView>>, label: string) =>
  wrapper.findAll('.perm-group').find((group) => group.find('.perm-group-name').text() === label)

describe('RolesView 授权工作台', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    permissions.clear()
    permissions.add('system:role:manage')
    apiMock.managedRoles.mockResolvedValue(roles)
    apiMock.permissionMenus.mockResolvedValue(menus)
    apiMock.rolePermissions.mockResolvedValue([])
    apiMock.createManagedRole.mockResolvedValue({ ...roles[1], id: 20, name: '客服专员', key: 'CUSTOM_SERVICE' })
    apiMock.replaceRolePermissions.mockResolvedValue(undefined)
  })

  it('loads roles and groups delegable permissions into preview-named domains with tech strings', async () => {
    const wrapper = await mountView()

    const groupLabels = wrapper.findAll('.perm-group-name').map((node) => node.text())
    expect(groupLabels).toEqual(['社 区 治 理', '宣 传 与 广 告', '平 台 治 理'])
    // 默认全部折叠：组体不渲染内容行（C-03.1）。
    expect(wrapper.findAll('.perm-row').length).toBe(0)
    expect(wrapper.text()).toContain('已选 0 / 共 2')
    // 技术串在组头小字与展开后的行尾可见。
    expect(wrapper.find('.perm-group-keys').text()).toContain('content-admin')
  })

  it('expands a group on toggle and shows rows with counts and tech keys', async () => {
    const wrapper = await mountView()

    const group = findGroup(wrapper, '社 区 治 理')!
    expect(group.classes()).not.toContain('is-open')
    await group.find('.perm-group-toggle').trigger('click')

    expect(group.classes()).toContain('is-open')
    const rows = group.findAll('.perm-row')
    expect(rows.length).toBe(2)
    const groupText = group.text()
    expect(groupText).toContain('接单审核举报')
    expect(groupText).toContain('community:report:review')
  })

  it('selects and clears a whole group via the header select-all control', async () => {
    const wrapper = await mountView()

    const group = findGroup(wrapper, '宣 传 与 广 告')!
    await group.find('.perm-group-toggle').trigger('click')

    await group.find('.perm-select-all').trigger('click')
    // 勾选叶子 BUTTON 时保存链路自动补齐父级（既有合同）。
    expect(apiMock.replaceRolePermissions).not.toHaveBeenCalled()
    let checked = wrapper.findAll('input[type="checkbox"]:checked')
    expect(checked.map((box) => (box.element as HTMLInputElement).value)).toEqual(['3', '4'])
    expect(wrapper.text()).toContain('已选 2 / 共 2')
    expect(wrapper.text()).toContain('保存授权（2 项）')

    await group.find('.perm-select-all').trigger('click')
    checked = wrapper.findAll('input[type="checkbox"]:checked')
    expect(checked.length).toBe(0)
  })

  it('filters by chinese name or permission key through the search box and highlights hits', async () => {
    const wrapper = await mountView()

    const search = wrapper.find('.perm-search')
    await search.setValue('advertising')

    // 命中项所在组在搜索态保持展开并高亮。
    expect(findGroup(wrapper, '宣 传 与 广 告')!.classes()).toContain('is-open')
    const hitRows = wrapper.findAll('.perm-row.is-hit')
    expect(hitRows.map((row) => row.text())).toEqual([expect.stringContaining('广告资料管理')])
    expect(wrapper.text()).not.toContain('接单审核举报')

    await search.setValue('')
    // 清空恢复默认折叠态。
    expect(wrapper.findAll('.perm-group.is-open').length).toBe(0)
    expect(wrapper.findAll('.perm-row').length).toBe(0)
  })

  it('renders the sticky save bar with guidance copy', async () => {
    const wrapper = await mountView()

    const savebar = wrapper.find('[data-test="perm-savebar"]')
    expect(savebar.exists()).toBe(true)
    expect(savebar.text()).toContain('保存自动补齐可见子菜单的父级链 · 内置角色只读')
  })

  it('renders built-in roles read-only without a usable save and captions SYSTEM_ADMIN full grants', async () => {
    const wrapper = await mountView()

    const builtinItem = wrapper.findAll('.role-item').find((item) => item.text().includes('系统管理员'))
    await builtinItem?.trigger('click')
    await flushPromises()

    expect(wrapper.find('.perm-readonly-hint').exists()).toBe(true)
    expect(builtinItem?.text()).toContain('全量权限（V35 起）')
    const disabledBoxes = wrapper.findAll('.perm-row input[type="checkbox"]:disabled')
    expect(disabledBoxes.length).toBe(wrapper.findAll('.perm-row input[type="checkbox"]').length)
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存'))
    expect(saveButton?.attributes('disabled')).toBeDefined()
  })

  it('auto-adds parent menu ids when saving permissions of a dynamic role', async () => {
    const wrapper = await mountView()

    await findGroup(wrapper, '社 区 治 理')!.find('.perm-group-toggle').trigger('click')
    const reviewBox = wrapper.find('input[type="checkbox"][value="2"]')
    expect(reviewBox.exists()).toBe(true)
    await reviewBox.setValue(true)

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存授权'))
    expect(saveButton?.attributes('disabled')).toBeUndefined()
    await saveButton?.trigger('click')
    await flushPromises()

    // 勾选 BUTTON 子项时自动带上可见 MENU 父项，满足后端「子菜单必须带父级」校验。
    expect(apiMock.replaceRolePermissions).toHaveBeenCalledWith(11, [2, 1])
  })

  it('keeps saved permissions visible when reopening groups after switching roles', async () => {
    apiMock.rolePermissions.mockResolvedValue([2, 1])
    const wrapper = await mountView()

    await findGroup(wrapper, '社 区 治 理')!.find('.perm-group-toggle').trigger('click')
    const group = findGroup(wrapper, '社 区 治 理')!
    expect((group.find('input[type="checkbox"][value="2"]').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.text()).toContain('已选 2 / 共 2')
    expect(wrapper.text()).toContain('已选 2 / 5')

    // 内置角色切换后 dirty 不受影响、保存仍禁用。
    const builtinItem = wrapper.findAll('.role-item').find((item) => item.text().includes('系统管理员'))
    await builtinItem?.trigger('click')
    await flushPromises()
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存'))
    expect(saveButton?.attributes('disabled')).toBeDefined()
  })

  it('creates a role through the existing endpoint and selects it for editing', async () => {
    const createdRole = { id: 20, name: '客服专员', key: 'CUSTOM_SERVICE', dataScope: 'SELF', status: 'ACTIVE', builtIn: false }
    apiMock.managedRoles.mockResolvedValueOnce(roles).mockResolvedValueOnce([...roles, createdRole])
    const wrapper = await mountView()

    const createEntry = wrapper.findAll('.role-item').find((item) => item.text().includes('新建角色'))
    await createEntry?.trigger('click')

    const nameInput = wrapper.find('.stub-dialog input[placeholder*="客服专员"]')
    const keyInput = wrapper.find('.stub-dialog input[placeholder*="CUSTOM_SERVICE"]')
    await nameInput.setValue('客服专员')
    await keyInput.setValue('CUSTOM_SERVICE')
    const submit = wrapper.findAll('.stub-dialog button').find((button) => button.text().includes('创建'))
    await submit?.trigger('click')
    await flushPromises()

    expect(apiMock.createManagedRole).toHaveBeenCalledWith({ roleName: '客服专员', roleKey: 'CUSTOM_SERVICE' })
    expect(apiMock.rolePermissions).toHaveBeenCalledWith(20)
    expect(wrapper.text()).toContain('客服专员')
  })

  it('hides write entries when the operator lacks system:role:manage', async () => {
    permissions.clear()
    const wrapper = await mountView()

    expect(wrapper.findAll('.role-item.create').length).toBe(0)
    expect(wrapper.text()).not.toContain('保存授权')
  })
})
