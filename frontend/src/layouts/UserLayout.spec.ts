import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserLayout from './UserLayout.vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'

vi.mock('@/composables/useNotificationStream', () => ({
  useNotificationStream: () => ({ start: vi.fn(), stop: vi.fn() })
}))

const passthrough = { template: '<div><slot /><slot name="dropdown" /></div>' }
const buttonStub = { emits: ['click'], template: '<button type="button" v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' }

const stubs = {
  RouterView: true,
  ElTooltip: passthrough,
  ElDropdown: passthrough,
  ElDropdownMenu: passthrough,
  ElDropdownItem: buttonStub,
  ElIcon: passthrough,
  ArrowDown: true,
  ArrowUp: true,
  Bell: true,
  ChatLineSquare: true,
  CircleCheck: true,
  Clock: true,
  Compass: true,
  House: true,
  Location: true,
  Medal: true,
  OfficeBuilding: true,
  Setting: true,
  Star: true,
  SwitchButton: true,
  Ticket: true,
  Tickets: true,
  User: true,
  Wallet: true
}

function createFullRouter(): Router {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/venues', component: { template: '<div />' } },
      { path: '/venues/:id', component: { template: '<div />' } },
      { path: '/community', component: { template: '<div />' } },
      { path: '/community/groups/:id', component: { template: '<div />' } },
      { path: '/activities', component: { template: '<div />' } },
      { path: '/companion-requests/:id', component: { template: '<div />' } },
      { path: '/reservation/confirm', component: { template: '<div />' } },
      { path: '/me/reservations', component: { template: '<div />' } },
      { path: '/me/orders', component: { template: '<div />' } },
      { path: '/me/orders/:id/pay', component: { template: '<div />' } },
      { path: '/me/messages', component: { template: '<div />' } },
      { path: '/me/feedback', component: { template: '<div />' } },
      { path: '/me', component: { template: '<div />' } },
      { path: '/me/profile', alias: '/user/profile', component: { template: '<div />' } }
    ]
  })
}

async function mountLayout(pinia: ReturnType<typeof createPinia>, router: Router) {
  const wrapper = mount(UserLayout, { global: { plugins: [pinia, router], stubs } })
  await flushPromises()
  return wrapper
}

describe('UserLayout', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0, writable: true })
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: false }))
    vi.stubGlobal('scrollTo', vi.fn())
  })

  it('renders the six-item primary navigation on desktop and mobile', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' },
      roles: ['USER']
    })
    menu.$patch({
      loaded: true,
      tokenSnapshot: 'token',
      menus: [
        { id: 1, parentId: 0, name: '首页', path: '/', component: '', perms: 'user:view', type: 'MENU' },
        { id: 2, parentId: 0, name: '场馆', path: '/venues', component: '', perms: 'venue:view', type: 'MENU' },
        { id: 3, parentId: 0, name: '预约', path: '/me/reservations', component: '', perms: 'reservation:my', type: 'MENU' },
        { id: 7, parentId: 0, name: '社区', path: '/community', component: '', perms: 'community:access', type: 'MENU' },
        { id: 4, parentId: 0, name: '会员', path: '/me/membership', component: '', perms: 'member:me', type: 'MENU' },
        { id: 5, parentId: 0, name: '优惠券', path: '/me/coupons', component: '', perms: 'coupon:me', type: 'MENU' },
        { id: 6, parentId: 0, name: '收藏', path: '/me/favorites', component: '', perms: 'funnel:favorite:me', type: 'MENU' }
      ]
    })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)

    const router = createFullRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = await mountLayout(pinia, router)

    const mobileTabs = wrapper.findAll('.mobile-tabbar a')
    expect(mobileTabs).toHaveLength(6)
    expect(wrapper.find('.mobile-tabbar').attributes('style')).toContain('--mobile-tab-count: 6')
    const mobileLabels = mobileTabs.map((tab) => tab.text())
    expect(mobileLabels).toEqual(['首页', '场馆', '发现', '活动', '行程', '我的'])

    const desktopLinks = wrapper.findAll('.desktop-nav .nav-link')
    expect(desktopLinks).toHaveLength(6)
    expect(desktopLinks.map((link) => link.text())).toEqual(['首页', '场馆', '发现', '活动', '行程', '我的'])
    expect(wrapper.find('.desktop-nav').text()).not.toContain('优惠券')
    expect(wrapper.find('.desktop-nav').text()).not.toContain('会员中心')
    expect(wrapper.get('.header-icon-button').attributes('aria-label')).toBe('消息中心')
    expect(desktopLinks.at(-1)?.attributes('href')).toBe('/me')
    expect(mobileTabs.at(-1)?.attributes('href')).toBe('/me')
    expect(wrapper.findAll('.profile-menu-item')).toHaveLength(5)
    expect(wrapper.find('.profile-menu-group-title').exists()).toBe(false)
    expect(wrapper.text()).toContain('我的页承载全部业务入口')
    expect(wrapper.find('.profile-feedback-hint').text()).toBe('新')
    expect(wrapper.find('.user-footer-brand').text()).toBe('拾光')
    expect(wrapper.find('.user-footer-env').text()).toContain('DEV')
    expect(wrapper.find('.user-footer-links a[href="/me/feedback"]').exists()).toBe(true)
    expect(wrapper.find('.user-footer-links a[href="/venues"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('renders the workspace group only for granted backend permissions and remembers feedback hint dismissal', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' },
      roles: ['USER'],
      permissions: ['content-admin:view']
    })
    menu.$patch({
      loaded: true,
      tokenSnapshot: 'token',
      menus: [
        { id: 1, parentId: 0, name: '首页', path: '/', component: '', perms: 'user:view', type: 'MENU' },
        { id: 20, parentId: 0, name: '空间后台', path: '/venue-admin', component: '', perms: 'venue-admin:view', type: 'MENU' },
        { id: 21, parentId: 0, name: '系统后台', path: '/system-admin', component: '', perms: 'system:view', type: 'MENU' }
      ]
    })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)

    const router = createFullRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = await mountLayout(pinia, router)

    expect(wrapper.find('.profile-menu-group-title').text()).toBe('工作台')
    expect(wrapper.text()).toContain('空间运营后台')
    expect(wrapper.text()).toContain('系统后台')
    expect(wrapper.text()).toContain('内容运营后台')
    expect(wrapper.findAll('.profile-menu-item')).toHaveLength(8)

    await wrapper.find('.profile-feedback-item').trigger('click')
    await flushPromises()
    expect(localStorage.getItem('sg-feedback-hint-dismissed')).toBe('1')
    expect(router.currentRoute.value.path).toBe('/me/feedback')
    expect(wrapper.find('.profile-feedback-hint').exists()).toBe(false)

    wrapper.unmount()
  })

  it('keeps the desktop indicator aligned with the active link and exposes router current state', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' },
      roles: ['USER']
    })
    menu.$patch({
      loaded: true,
      tokenSnapshot: 'token',
      menus: [
        { id: 1, parentId: 0, name: '首页', path: '/', component: '', perms: 'user:view', type: 'MENU' },
        { id: 2, parentId: 0, name: '场馆', path: '/venues', component: '', perms: 'venue:view', type: 'MENU' },
        { id: 3, parentId: 0, name: '预约', path: '/me/reservations', component: '', perms: 'reservation:my', type: 'MENU' },
        { id: 7, parentId: 0, name: '社区', path: '/community', component: '', perms: 'community:access', type: 'MENU' }
      ]
    })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)

    const router = createFullRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = await mountLayout(pinia, router)

    const nav = wrapper.get('.desktop-nav')
    expect(nav.attributes('aria-label')).toBe('用户主导航')
    expect(wrapper.find('.nav-indicator').exists()).toBe(true)
    expect(wrapper.get('.desktop-nav .nav-link.is-active').attributes('aria-current')).toBe('page')
    expect(nav.attributes('style')).toContain('--nav-indicator-left')
    expect(nav.attributes('style')).toContain('--nav-indicator-width')

    await router.push('/venues')
    await flushPromises()
    expect(wrapper.get('.desktop-nav .nav-link.is-active').text()).toContain('场馆')
    expect(wrapper.get('.desktop-nav .nav-link.is-active').attributes('aria-current')).toBe('page')

    wrapper.unmount()
  })

  it('highlights the owning section for legacy deep links on both navigations', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' },
      roles: ['USER']
    })
    menu.$patch({
      loaded: true,
      tokenSnapshot: 'token',
      menus: [
        { id: 1, parentId: 0, name: '首页', path: '/', component: '', perms: 'user:view', type: 'MENU' },
        { id: 2, parentId: 0, name: '场馆', path: '/venues', component: '', perms: 'venue:view', type: 'MENU' },
        { id: 3, parentId: 0, name: '预约', path: '/me/reservations', component: '', perms: 'reservation:my', type: 'MENU' },
        { id: 7, parentId: 0, name: '社区', path: '/community', component: '', perms: 'community:access', type: 'MENU' }
      ]
    })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)

    const router = createFullRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = await mountLayout(pinia, router)

    const activeLabel = () => wrapper.findAll('.mobile-tabbar a').filter((tab) => tab.classes('is-active')).map((tab) => tab.text())
    const activeDesktop = () => wrapper.findAll('.desktop-nav .nav-link').filter((link) => link.classes('is-active')).map((link) => link.text())

    await router.push('/venues/12')
    await flushPromises()
    expect(activeLabel()).toEqual(['场馆'])
    expect(activeDesktop()).toEqual(['场馆'])

    await router.push('/community/groups/3')
    await flushPromises()
    expect(activeLabel()).toEqual(['发现'])
    expect(activeDesktop()).toEqual(['发现'])

    await router.push('/activities')
    await flushPromises()
    expect(activeLabel()).toEqual(['活动'])
    expect(activeDesktop()).toEqual(['活动'])

    await router.push('/companion-requests/8')
    await flushPromises()
    expect(activeLabel()).toEqual(['活动'])

    await router.push('/reservation/confirm')
    await flushPromises()
    expect(activeLabel()).toEqual(['行程'])
    expect(activeDesktop()).toEqual(['行程'])

    await router.push('/me/orders/55/pay')
    await flushPromises()
    expect(activeLabel()).toEqual(['行程'])

    await router.push('/me/messages')
    await flushPromises()
    expect(activeLabel()).toEqual(['我的'])

    await router.push('/me/feedback')
    await flushPromises()
    expect(activeLabel()).toEqual(['我的'])
    expect(activeDesktop()).toEqual(['我的'])

    await router.push('/user/profile')
    await flushPromises()
    expect(activeLabel()).toEqual(['我的'])
    expect(activeDesktop()).toEqual(['我的'])

    wrapper.unmount()
  })

  it('renders a static background: no scene attribute and no animated background layers (R-01 静谧晨光)', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' },
      roles: ['USER']
    })
    menu.$patch({
      loaded: true,
      tokenSnapshot: 'token',
      menus: [
        { id: 1, parentId: 0, name: '首页', path: '/', component: '', perms: 'user:view', type: 'MENU' },
        { id: 2, parentId: 0, name: '场馆', path: '/venues', component: '', perms: 'venue:view', type: 'MENU' },
        { id: 7, parentId: 0, name: '社区', path: '/community', component: '', perms: 'community:access', type: 'MENU' }
      ]
    })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)

    const router = createFullRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = await mountLayout(pinia, router)

    expect(wrapper.find('.user-layout').attributes('data-scene')).toBeUndefined()
    expect(wrapper.find('.user-background').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('user-background-glow')
    await router.push('/community')
    await flushPromises()
    expect(wrapper.find('.user-layout').attributes('data-scene')).toBeUndefined()

    wrapper.unmount()
  })

  it('distributes the mobile navigation across only the entries the user can see', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({
      token: 'token',
      user: { id: 2, username: 'limited', nickname: '访客用户', status: 'ACTIVE' },
      roles: ['USER']
    })
    menu.$patch({
      loaded: true,
      tokenSnapshot: 'token',
      menus: [{ id: 1, parentId: 0, name: '首页', path: '/', component: '', perms: 'user:view', type: 'MENU' }]
    })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/me', component: { template: '<div />' } },
        { path: '/me/messages', component: { template: '<div />' } },
        { path: '/me/feedback', component: { template: '<div />' } },
        { path: '/me/profile', component: { template: '<div />' } }
      ]
    })
    await router.push('/')
    await router.isReady()

    const wrapper = await mountLayout(pinia, router)

    const mobileTabbar = wrapper.find('.mobile-tabbar')
    expect(mobileTabbar.findAll('a')).toHaveLength(2)
    expect(mobileTabbar.attributes('style')).toContain('--mobile-tab-count: 2')
    expect(mobileTabbar.text()).toContain('首页')
    expect(mobileTabbar.text()).toContain('我的')
    expect(mobileTabbar.text()).not.toContain('场馆')
    expect(mobileTabbar.text()).not.toContain('发现')
    expect(mobileTabbar.text()).not.toContain('行程')
    expect(wrapper.findAll('.desktop-nav .nav-link')).toHaveLength(2)

    wrapper.unmount()
  })

  it('reveals the back-to-top control after 600px and honors reduced motion', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    const menu = useMenuStore()
    const app = useAppStore()
    auth.$patch({ token: 'token', user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' }, roles: ['USER'] })
    menu.$patch({ loaded: true, tokenSnapshot: 'token', menus: [{ id: 1, parentId: 0, name: '首页', path: '/', component: '', perms: 'user:view', type: 'MENU' }] })
    app.refreshUnreadCount = vi.fn().mockResolvedValue(undefined)

    const router = createFullRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = await mountLayout(pinia, router)
    expect(wrapper.find('[data-test="back-to-top"]').exists()).toBe(false)

    Object.defineProperty(window, 'scrollY', { configurable: true, value: 601, writable: true })
    window.dispatchEvent(new Event('scroll'))
    await flushPromises()
    const backToTop = wrapper.get('[data-test="back-to-top"]')
    expect(backToTop.attributes('aria-label')).toBe('回到顶部')

    vi.mocked(window.matchMedia).mockReturnValue({ matches: true } as MediaQueryList)
    await backToTop.trigger('click')
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'auto' })

    Object.defineProperty(window, 'scrollY', { configurable: true, value: 600, writable: true })
    window.dispatchEvent(new Event('scroll'))
    await flushPromises()
    expect(wrapper.find('[data-test="back-to-top"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
