import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminLayout from './AdminLayout.vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'
import { useVenueAccessStore } from '@/stores/venueAccess'

const notificationStreamMock = vi.hoisted(() => ({
  start: vi.fn(),
  stop: vi.fn()
}))

const venueApiMock = vi.hoisted(() => ({
  manageable: vi.fn()
}))

vi.mock('@/composables/useNotificationStream', () => ({
  useNotificationStream: () => notificationStreamMock
}))

vi.mock('@/api/venue', () => ({ venueAdminApi: venueApiMock }))

// jsdom 未实现原生 dialog 的 showModal/close 事件，与拾光面系列 spec 相同的桩
HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) { this.open = true })
HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
  this.open = false
  this.dispatchEvent(new Event('close'))
})

const VENUE_MENUS = [
  {
    id: 10,
    parentId: 0,
    name: '空间运营工作台',
    path: '/venue-admin',
    component: '',
    perms: 'venue-admin:view',
    type: 'MENU',
    children: [
      { id: 11, parentId: 10, name: '预约与审核', path: '/venue-admin/reservations', component: '', perms: 'reservation:audit', type: 'MENU' },
      { id: 12, parentId: 10, name: '到场核销', path: '/venue-admin/checkins', component: '', perms: 'reservation:checkin:manage', type: 'MENU' },
      { id: 13, parentId: 10, name: '场馆与场地', path: '/venue-admin/spaces', component: '', perms: 'venue:space:manage', type: 'MENU' },
      { id: 14, parentId: 10, name: '营业与库存', path: '/venue-admin/calendar', component: '', perms: 'venue:inventory:view', type: 'MENU' },
      { id: 15, parentId: 10, name: '现场运营', path: '/venue-admin/site-ops', component: '', perms: 'site:ops:manage', type: 'MENU' },
      { id: 16, parentId: 10, name: '资产维护', path: '/venue-admin/assets', component: '', perms: 'asset:manage', type: 'MENU' },
      { id: 17, parentId: 10, name: '客服与反馈', path: '/venue-admin/customer-service', component: '', perms: 'customer-service:manage', type: 'MENU' },
      { id: 18, parentId: 10, name: '场馆公告', path: '/venue-admin/notices', component: '', perms: 'notice:manage', type: 'MENU' },
      { id: 19, parentId: 10, name: '场馆订单', path: '/venue-admin/orders', component: '', perms: 'order:admin', type: 'MENU' },
      { id: 20, parentId: 10, name: '场馆漏斗', path: '/venue-admin/funnel', component: '', perms: 'funnel:analytics:view', type: 'MENU' }
    ]
  }
] as never

function makeRouter() {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/venue-admin', component: { template: '<div />' }, meta: { adminTitle: '空间运营后台' } },
      { path: '/venue-admin/reservations', component: { template: '<div />' }, meta: { menuKey: 'reservation:audit' } },
      { path: '/venue-admin/spaces', component: { template: '<div />' }, meta: { menuKey: 'venue:space:manage' } },
      { path: '/venue-admin/customer-service', component: { template: '<div />' }, meta: { menuKey: 'customer-service:manage' } },
      { path: '/system-admin', component: { template: '<div />' }, meta: { adminTitle: '平台治理后台' } },
      { path: '/content-admin', component: { template: '<div />' }, meta: { adminTitle: '内容治理后台', authorizationKey: 'content-admin:view' } },
      { path: '/me/messages', component: { template: '<div />' } },
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } }
    ]
  })
}

async function mountLayout(router: ReturnType<typeof makeRouter>) {
  const wrapper = mount(AdminLayout, {
    global: {
      plugins: [router],
      stubs: {
        RouterView: true,
        Transition: false
      }
    },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

describe('AdminLayout', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    // 默认绑定一个场馆，避免影响既有用例；未绑定锁定用例单独覆写。
    venueApiMock.manageable.mockResolvedValue([{ id: 7, name: '羽跃羽毛球馆' }])
  })

  it('recreates venue content and discards local drafts when the scope changes', async () => {
    setActivePinia(createPinia())
    useAuthStore().$patch({ token: 'token', roles: ['SYSTEM_ADMIN'], user: { id: 1, username: 'admin', status: 'ACTIVE' } })
    useMenuStore().$patch({ loaded: true, tokenSnapshot: 'token', menus: VENUE_MENUS })
    useAppStore().refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const scope = useVenueAccessStore()
    scope.$patch({ loaded: true, activeVenueId: 7, venues: [{ id: 7, name: 'A' }, { id: 8, name: 'B' }] as never })
    const router = makeRouter()
    router.addRoute({ path: '/venue-admin/draft', component: { data: () => ({ draft: '' }), template: '<input aria-label="draft" v-model="draft">' } })
    await router.push('/venue-admin/draft')
    await router.isReady()
    const wrapper = mount(AdminLayout, { global: { plugins: [router], stubs: { Transition: true } } })
    await flushPromises()
    await wrapper.get('input[aria-label="draft"]').setValue('A private draft')
    scope.switchVenue(8)
    await flushPromises()
    expect((wrapper.get('input[aria-label="draft"]').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('organizes authorized venue menus by the confirmed task domains', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 2, username: 'operator', nickname: '陈老师', status: 'ACTIVE' },
      roles: ['VENUE_ADMIN']
    })
    menu.$patch({ loaded: true, tokenSnapshot: 'token', menus: VENUE_MENUS })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)

    const router = makeRouter()
    await router.push('/venue-admin/reservations')
    await router.isReady()

    const wrapper = await mountLayout(router)

    const navText = wrapper.find('.fw-sidebar .fw-nav').text()
    expect(navText).toContain('今日运营')
    expect(navText).toContain('场地库存')
    expect(navText).toContain('现场与资产')
    expect(navText).toContain('用户服务')
    expect(navText).toContain('经营分析')
    expect(wrapper.find('.fw-breadcrumb').text()).toContain('预约与审核')
    expect(wrapper.find('.fw-brand-label').text()).toBe('运营工作空间')
    expect(wrapper.find('.fw-nav-foot').text()).toContain('场馆管理员')
    // 预览：顶部身份按钮常显（单工作区也显示，弹层内标“当前”）
    expect(wrapper.find('[data-test="fw-identity-open"]').exists()).toBe(true)
    expect(wrapper.find('.fw-actions').text()).toContain('消息')
    expect(notificationStreamMock.start).toHaveBeenCalledOnce()

    wrapper.unmount()
  })

  it('starts realtime notifications when the initial unread refresh fails and keeps the authenticated role', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 1, username: 'admin', nickname: '系统管理员', status: 'ACTIVE' },
      roles: ['SYSTEM_ADMIN']
    })
    menu.$patch({
      loaded: true,
      tokenSnapshot: 'token',
      menus: [{ id: 10, parentId: 0, name: '平台看板', path: '/system-admin', component: '', type: 'MENU' }]
    })
    app.refreshUnreadCount = vi.fn().mockRejectedValue(new Error('unread unavailable'))
    const router = makeRouter()
    await router.push('/system-admin')
    await router.isReady()

    const wrapper = await mountLayout(router)

    expect(app.refreshUnreadCount).toHaveBeenCalledOnce()
    expect(notificationStreamMock.start).toHaveBeenCalledOnce()
    expect(wrapper.find('.fw-nav-foot').text()).toContain('系统管理员')

    wrapper.unmount()
  })

  it('shows the independent workspace to a dynamic role with entry and moderate permissions', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 3, username: 'content', nickname: '内容运营', status: 'ACTIVE' },
      roles: ['CONTENT_OPERATOR'],
      permissions: ['content-admin:view', 'community:content:moderate', 'placement:audit:view', 'advertising:analysis:view']
    })
    menu.$patch({ loaded: true, tokenSnapshot: 'token', menus: [] })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const router = makeRouter()
    await router.push('/content-admin')
    await router.isReady()

    const wrapper = await mountLayout(router)

    const navText = wrapper.find('.fw-sidebar .fw-nav').text()
    expect(navText).toContain('社区治理')
    expect(navText).toContain('宣传与广告')
    expect(navText).toContain('举报处置')
    expect(navText).toContain('自营宣传位')
    expect(navText).not.toContain('广告管理')
    expect(navText).toContain('广告分析')
    expect(wrapper.find('.fw-actions').text()).toContain('消息')
    expect(wrapper.find('.fw-actions').text()).toContain('用户端')
    expect(wrapper.find('[data-test="fw-session"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('splits content governance into community and advertising groups with the new ad console entry', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 3, username: 'content', nickname: '内容运营', status: 'ACTIVE' },
      roles: ['CONTENT_OPERATOR'],
      permissions: ['content-admin:view', 'placement:audit:view', 'advertising:manage', 'advertising:analysis:view']
    })
    menu.$patch({ loaded: true, tokenSnapshot: 'token', menus: [] })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const router = makeRouter()
    await router.push('/content-admin')
    await router.isReady()

    const wrapper = await mountLayout(router)

    const navText = wrapper.find('.fw-sidebar .fw-nav').text()
    expect(navText).toContain('社区治理')
    expect(navText).toContain('宣传与广告')
    expect(navText).toContain('举报处置')
    expect(navText).toContain('自营宣传位')
    expect(navText).toContain('广告管理')
    expect(navText).toContain('广告分析')
    expect(wrapper.findAll('.fw-sidebar .fw-group').map((group) => group.text())).toEqual([
      '社区治理',
      '宣传与广告'
    ])

    wrapper.unmount()
  })

  it('offers every authorized workspace in the identity dialog with preview copy and switch toast', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 1, username: 'admin', nickname: '系统管理员', status: 'ACTIVE' },
      roles: ['SYSTEM_ADMIN'],
      permissions: ['content-admin:view']
    })
    menu.$patch({
      loaded: true,
      tokenSnapshot: 'token',
      menus: [
        { id: 10, parentId: 0, name: '空间运营工作台', path: '/venue-admin', component: '', type: 'MENU' },
        { id: 20, parentId: 0, name: '平台看板', path: '/system-admin', component: '', type: 'MENU' }
      ]
    })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const router = makeRouter()
    await router.push('/venue-admin')
    await router.isReady()

    const wrapper = await mountLayout(router)

    // 预览顺序：空间运营 → 平台治理 → 内容治理
    await wrapper.find('[data-test="fw-identity-open"]').trigger('click')
    await flushPromises()
    const dialog = wrapper.find('dialog.fw-modal')
    expect((dialog.element as HTMLDialogElement).open).toBe(true)
    expect(dialog.text()).toContain('选择当前工作身份')
    expect(dialog.text()).toContain('切换的是工作区与工作视角，账号已有权限仍需在具体动作处核对。')
    const options = dialog.findAll('.workspace-option')
    expect(options.map((option) => option.find('strong').text())).toEqual(['空间运营', '平台治理', '内容治理'])
    expect(options[0].text()).toContain('当前')
    expect(options[0].classes()).toContain('selected')

    await options[1].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/system-admin')
    expect(wrapper.find('.fw-toast').text()).toBe('已进入平台治理；账号权限没有改变')

    wrapper.unmount()
  })

  it('opens the session dialog with identity scope facts and the real logout chain', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    const venueAccess = useVenueAccessStore()
    auth.$patch({
      token: 'token',
      user: { id: 1, username: 'admin', nickname: '系统管理员', status: 'ACTIVE' },
      roles: ['SYSTEM_ADMIN']
    })
    menu.$patch({
      loaded: true,
      tokenSnapshot: 'token',
      menus: [{ id: 20, parentId: 0, name: '平台看板', path: '/system-admin', component: '', type: 'MENU' }]
    })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const logoutSpy = vi.fn().mockResolvedValue(undefined)
    auth.logout = logoutSpy
    const router = makeRouter()
    await router.push('/system-admin')
    await router.isReady()

    const wrapper = await mountLayout(router)

    await wrapper.find('[data-test="fw-session"]').trigger('click')
    await flushPromises()
    const sessionDialog = wrapper.findAll('dialog.fw-modal').find(d => d.text().includes('账号与会话'))!
    expect(sessionDialog.text()).toContain('账号与会话')
    expect(sessionDialog.text()).toContain('退出会结束当前登录会话，并返回登录页。')

    await sessionDialog.find('[data-test="fw-context-open"]').trigger('click')
    await flushPromises()
    const contextDialog = wrapper.findAll('dialog.fw-modal').find(d => d.text().includes('当前身份与访问范围'))!
    expect(contextDialog.text()).toContain('账号角色')
    expect(contextDialog.text()).toContain('平台范围')
    expect(contextDialog.text()).toContain('动作仍按当前授权核对；拥有工作台入口不代表拥有此工作区的全部动作。')
    await contextDialog.find('button.btn').trigger('click')

    await wrapper.find('[data-test="fw-session"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="fw-logout"]').trigger('click')
    await flushPromises()
    expect(logoutSpy).toHaveBeenCalledOnce()
    expect(menu.menus).toEqual([])
    expect(venueAccess.activeVenueId).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')

    wrapper.unmount()
  })

  it('shows the menu error state with retry when the menu request fails', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 1, username: 'admin', nickname: '系统管理员', status: 'ACTIVE' },
      roles: ['SYSTEM_ADMIN']
    })
    menu.$patch({ loaded: false, unavailable: true, tokenSnapshot: 'token', menus: [] })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const loadSpy = vi.fn().mockResolvedValue(true)
    menu.loadMenus = loadSpy
    const router = makeRouter()
    await router.push('/system-admin')
    await router.isReady()

    const wrapper = await mountLayout(router)

    const status = wrapper.find('.fw-status')
    expect(status.text()).toContain('导航暂时无法读取')
    expect(status.text()).toContain('这次读取失败，不代表账号没有权限。请重新读取后继续操作。')
    await status.find('[data-test="fw-menu-retry"]').trigger('click')
    await flushPromises()
    expect(loadSpy).toHaveBeenCalledOnce()

    wrapper.unmount()
  })

  it('shows the denied entry state with a return path to an accessible workspace', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 1, username: 'admin', nickname: '系统管理员', status: 'ACTIVE' },
      roles: ['SYSTEM_ADMIN']
    })
    // 菜单已加载且当前入口的 perms 不在树中（模拟加载后授权变化；工作区根保留）
    menu.$patch({ loaded: true, tokenSnapshot: 'token', menus: VENUE_MENUS })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const router = makeRouter()
    await router.push('/venue-admin/reservations')
    await router.isReady()
    menu.$patch({
      menus: [
        {
          id: 10, parentId: 0, name: '空间运营工作台', path: '/venue-admin', component: '', perms: 'venue-admin:view', type: 'MENU'
        }
      ] as never
    })

    const wrapper = await mountLayout(router)

    const status = wrapper.find('.fw-status')
    expect(status.text()).toContain('当前入口不可访问')
    expect(status.text()).toContain('不会保留之前页面中的受限内容。')
    await status.find('[data-test="fw-safe-home"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/venue-admin')

    wrapper.unmount()
  })

  it('shows the no-workspace state for an account without admin entries', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 9, username: 'guest', nickname: '普通用户', status: 'ACTIVE' },
      roles: ['USER']
    })
    menu.$patch({ loaded: true, tokenSnapshot: 'token', menus: [] })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const router = makeRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = await mountLayout(router)

    const status = wrapper.find('.fw-status')
    expect(status.text()).toContain('当前没有可进入的后台')
    expect(status.text()).toContain('后台入口需要相应角色和权限，不能从空菜单推导出管理能力。')
    expect(status.find('[data-test="fw-go-user"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('locks venue workspace menus to the dashboard entry when no venue is bound (C-09)', async () => {
    venueApiMock.manageable.mockResolvedValue([])
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 5, username: 'manager', nickname: '王场馆', status: 'ACTIVE' },
      roles: ['VENUE_ADMIN']
    })
    menu.$patch({ loaded: true, tokenSnapshot: 'token', menus: VENUE_MENUS })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const router = makeRouter()
    await router.push('/venue-admin')
    await router.isReady()

    const wrapper = await mountLayout(router)

    // 工作台入口保持可达；其余菜单全部降级为禁点占位。
    const links = wrapper.findAll('.fw-sidebar .fw-nav a')
    expect(links.map((link) => link.text())).toEqual(['空间运营工作台'])
    const locked = wrapper.findAll('.fw-sidebar .fw-nav .locked')
    expect(locked.map((item) => item.text())).toEqual([
      '预约与审核', '到场核销', '场馆与场地', '营业与库存', '现场运营',
      '资产维护', '客服与反馈', '场馆公告', '场馆订单', '场馆漏斗'
    ])
    locked.forEach((item) => {
      expect(item.attributes('aria-disabled')).toBe('true')
      expect(item.attributes('title')).toBe('尚未授权场馆')
    })
    // 预览锁定正文：还没有授权场馆 + 等待场馆授权 + 重新核对入口
    const lockHeading = wrapper.find('.fw-heading-lock')
    expect(lockHeading.text()).toContain('还没有授权场馆')
    expect(lockHeading.text()).toContain('工作台可以进入，其他场馆菜单暂时不可用。')
    const lockStatus = wrapper.findAll('.fw-status').find(s => s.text().includes('等待场馆授权'))!
    expect(lockStatus.find('[data-test="fw-recheck-venue"]').exists()).toBe(true)
    // 场馆 select 禁用且显示占位
    const select = wrapper.get('[data-test="fw-venue"]')
    expect(select.attributes('disabled')).toBeDefined()
    expect(select.text()).toContain('没有授权场馆')

    wrapper.unmount()
  })

  it('keeps all venue menus usable for a bound account and never locks system admins', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 6, username: 'keeper', nickname: '李绑定', status: 'ACTIVE' },
      roles: ['VENUE_ADMIN']
    })
    menu.$patch({ loaded: true, tokenSnapshot: 'token', menus: VENUE_MENUS })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const router = makeRouter()
    await router.push('/venue-admin')
    await router.isReady()

    const wrapper = await mountLayout(router)

    expect(wrapper.findAll('.fw-sidebar .fw-nav .locked').length).toBe(0)
    expect(wrapper.find('.fw-heading-lock').exists()).toBe(false)
    // 场馆上下文 select 可用且选中可管理场馆
    const select = wrapper.get('[data-test="fw-venue"]')
    expect(select.attributes('disabled')).toBeUndefined()
    expect((select.element as HTMLSelectElement).value).toBe('7')

    wrapper.unmount()
  })

  it('switches the venue scope from the header select and broadcasts a toast', async () => {
    venueApiMock.manageable.mockResolvedValue([
      { id: 7, name: '羽跃羽毛球馆' },
      { id: 9, name: '东园运动中心' }
    ])
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    const venueAccess = useVenueAccessStore()
    auth.$patch({
      token: 'token',
      user: { id: 6, username: 'keeper', nickname: '李绑定', status: 'ACTIVE' },
      roles: ['VENUE_ADMIN']
    })
    menu.$patch({ loaded: true, tokenSnapshot: 'token', menus: VENUE_MENUS })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const router = makeRouter()
    await router.push('/venue-admin')
    await router.isReady()

    const wrapper = await mountLayout(router)
    const before = venueAccess.scopeChangedAt
    await wrapper.get('[data-test="fw-venue"]').setValue('9')
    expect(venueAccess.activeVenueId).toBe(9)
    expect(venueAccess.scopeChangedAt).toBe(before + 1)
    expect(wrapper.find('.fw-toast').text()).toBe('当前范围已切换为东园运动中心')

    wrapper.unmount()
  })

  it('opens the mobile drawer with navigation and closes it from the drawer button', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 5, username: 'manager', nickname: '王场馆', status: 'ACTIVE' },
      roles: ['VENUE_ADMIN']
    })
    menu.$patch({ loaded: true, tokenSnapshot: 'token', menus: VENUE_MENUS })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const router = makeRouter()
    await router.push('/venue-admin')
    await router.isReady()

    const wrapper = await mountLayout(router)

    await wrapper.find('[data-test="fw-mobile-menu"]').trigger('click')
    const drawer = wrapper.find('dialog.fw-drawer')
    expect((drawer.element as HTMLDialogElement).open).toBe(true)
    expect(drawer.text()).toContain('预约与审核')
    await drawer.find('[data-test="fw-drawer-close"]').trigger('click')
    expect((drawer.element as HTMLDialogElement).open).toBe(false)

    wrapper.unmount()
  })
})
