import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'
import { setActivePinia, createPinia } from 'pinia'
import { ADVERTISING_ANALYSIS_ENTRY_PERMISSIONS, PLACEMENT_ENTRY_PERMISSIONS, routes } from './routes'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth'
import { CONTENT_ADMIN_ENTRY_PERMISSION } from '@/utils/contentAdminAccess'
import { scrollBehavior } from './index'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn()
}))

vi.mock('@/api/http', () => ({
  default: httpMock
}))

vi.mock('@/api/auth', () => ({
  authApi: {
    menus: vi.fn().mockResolvedValue([]),
    me: vi.fn(),
    authorization: vi.fn().mockResolvedValue({ roles: [], permissions: [] })
  }
}))

describe('router guards', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
    vi.unstubAllEnvs()
  })

  it('redirects anonymous users to login', async () => {
    const router = createRouter({ history: createWebHistory(), routes })
    const result = await router.resolve('/me/orders')
    expect(result.meta.requiresAuth).toBe(true)
  })

  it('identifies system admin routes with role metadata', () => {
    const route = routes.find((item) => item.path === '/system-admin')
    expect(route?.meta?.roles).toContain('SYSTEM_ADMIN')
  })

  it('registers community deep links and protects content operations with authorization permissions', () => {
    const router = createRouter({ history: createWebHistory(), routes })
    expect(router.resolve('/community').meta.menuKey).toBe('community:access')
    expect(router.resolve('/community/groups/12').meta.menuKey).toBe('community:access')
    expect(router.resolve('/community/posts/23').meta.menuKey).toBe('community:access')
    expect(router.resolve('/activities').meta.menuKey).toBe('community:access')
    expect(router.resolve('/activities/41').meta.menuKey).toBe('community:access')
    expect(router.resolve('/companion-requests/71').meta.menuKey).toBe('community:access')
    expect(router.resolve('/activities/41').name).toBe('ActivityDetail')
    expect(router.resolve('/companion-requests/71').name).toBe('CompanionDetail')

    const contentRoute = router.resolve('/content-admin')
    expect(contentRoute.meta.authorizationKey).toBe(CONTENT_ADMIN_ENTRY_PERMISSION)
    expect(contentRoute.meta.authorizationAny).toBeUndefined()
    expect(contentRoute.meta.roles).toBeUndefined()
    const placementRoute = router.resolve('/content-admin/placements')
    expect(placementRoute.name).toBe('ContentAdminPlacements')
    expect(placementRoute.meta.authorizationKey).toBe(CONTENT_ADMIN_ENTRY_PERMISSION)
    expect(placementRoute.meta.authorizationAny).toEqual(PLACEMENT_ENTRY_PERMISSIONS)
    const advertisingRoute = router.resolve('/content-admin/advertising/analysis')
    expect(advertisingRoute.name).toBe('ContentAdminAdvertisingAnalysis')
    expect(advertisingRoute.meta.authorizationKey).toBe(CONTENT_ADMIN_ENTRY_PERMISSION)
    expect(advertisingRoute.meta.authorizationAny).toEqual(ADVERTISING_ANALYSIS_ENTRY_PERMISSIONS)
    expect(router.resolve('/community').meta.roles).toBeUndefined()
  })

  it('registers venue notice route with notice management permission', () => {
    const root = routes.find((item) => item.path === '/venue-admin')
    const noticeRoute = root?.children?.find((item) => item.path === 'notices')
    expect(noticeRoute?.name).toBe('VenueAdminNotices')
    expect(noticeRoute?.meta?.menuKey).toBe('notice:manage')
  })

  it('registers B9 venue customer-service route with locked permission metadata', () => {
    const root = routes.find((item) => item.path === '/venue-admin')
    const route = root?.children?.find((item) => item.path === 'customer-service')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/venue-admin/customer-service')

    expect(route?.name).toBe('VenueAdminCustomerService')
    expect(route?.meta?.menuKey).toBe('customer-service:manage')
    expect(resolved.name).toBe('VenueAdminCustomerService')
  })

  it('registers B8 site ops route with locked permission metadata', () => {
    const root = routes.find((item) => item.path === '/venue-admin')
    const route = root?.children?.find((item) => item.path === 'site-ops')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/venue-admin/site-ops')

    expect(route?.name).toBe('VenueAdminSiteOps')
    expect(route?.meta?.menuKey).toBe('site:ops:manage')
    expect(resolved.name).toBe('VenueAdminSiteOps')
  })

  it('registers B8 asset route with locked permission metadata', () => {
    const root = routes.find((item) => item.path === '/venue-admin')
    const route = root?.children?.find((item) => item.path === 'assets')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/venue-admin/assets')

    expect(route?.name).toBe('VenueAdminAssets')
    expect(route?.meta?.menuKey).toBe('asset:manage')
    expect(resolved.name).toBe('VenueAdminAssets')
  })

  it('registers system notice delivery routes with notice analytics permission', () => {
    const systemRoot = routes.find((route) => route.path === '/system-admin')
    const delivery = systemRoot?.children?.find((item) => item.path === 'notice-delivery')
    const experiments = systemRoot?.children?.find((item) => item.path === 'notice-experiments')
    expect(delivery?.meta?.menuKey).toBe('notice:analytics')
    expect(experiments?.meta?.menuKey).toBe('notice:analytics')
  })

  it('registers C provider observability route with locked permission metadata', () => {
    const systemRoot = routes.find((item) => item.path === '/system-admin')
    const route = systemRoot?.children?.find((item) => item.path === 'providers')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/system-admin/providers')

    expect(route?.name).toBe('SystemProviders')
    expect(route?.meta?.menuKey).toBe('provider:observe')
    expect(resolved.name).toBe('SystemProviders')
    expect(resolved.meta.menuKey).toBe('provider:observe')
  })

  it('registers venue calendar route with inventory view permission', () => {
    const root = routes.find((item) => item.path === '/venue-admin')
    const calendarRoute = root?.children?.find((item) => item.path === 'calendar')
    expect(calendarRoute?.name).toBe('VenueAdminCalendar')
    expect(calendarRoute?.meta?.menuKey).toBe('venue:inventory:view')
  })

  it('registers user checkin route with locked B4 permission metadata', () => {
    const userRoot = routes.find((item) => item.path === '/')
    const checkinRoute = userRoot?.children?.find((item) => item.path === 'me/checkins')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/me/checkins')

    expect(checkinRoute?.name).toBe('MyCheckins')
    expect(checkinRoute?.meta?.menuKey).toBe('reservation:checkin:me')
    expect(resolved.name).toBe('MyCheckins')
    expect(resolved.meta.menuKey).toBe('reservation:checkin:me')
  })

  it('resolves the P2 profile acceptance path to the protected profile page', () => {
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/user/profile')

    expect(resolved.name).toBe('Profile')
    expect(resolved.meta.menuKey).toBe('user:view')
  })

  it('registers the user aggregation page as the primary personal destination', () => {
    const userRoot = routes.find((item) => item.path === '/')
    const overviewRoute = userRoot?.children?.find((item) => item.path === 'me')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/me')

    expect(overviewRoute?.name).toBe('MeOverview')
    expect(overviewRoute?.meta?.menuKey).toBe('user:view')
    expect(resolved.name).toBe('MeOverview')
    expect(resolved.meta.menuKey).toBe('user:view')
  })

  it('registers user feedback route with locked B9 permission metadata', () => {
    const userRoot = routes.find((item) => item.path === '/')
    const route = userRoot?.children?.find((item) => item.path === 'me/feedback')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/me/feedback')

    expect(route?.name).toBe('MyFeedback')
    expect(route?.meta?.menuKey).toBe('reservation:my')
    expect(resolved.name).toBe('MyFeedback')
    expect(resolved.meta.menuKey).toBe('reservation:my')
  })

  it('registers user favorites route with locked B5 funnel permission metadata', () => {
    const userRoot = routes.find((item) => item.path === '/')
    const favoriteRoute = userRoot?.children?.find((item) => item.path === 'me/favorites')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/me/favorites')

    expect(favoriteRoute?.name).toBe('Favorites')
    expect(favoriteRoute?.meta?.menuKey).toBe('funnel:favorite:me')
    expect(resolved.name).toBe('Favorites')
    expect(resolved.meta.menuKey).toBe('funnel:favorite:me')
  })

  it('registers user payment route with locked order menu metadata', () => {
    const userRoot = routes.find((item) => item.path === '/')
    const paymentRoute = userRoot?.children?.find((item) => item.path === 'me/orders/:id/pay')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/me/orders/11/pay')

    expect(paymentRoute?.name).toBe('Payment')
    expect(paymentRoute?.meta?.menuKey).toBe('order:my')
    expect(resolved.name).toBe('Payment')
    expect(resolved.meta.menuKey).toBe('order:my')
  })

  it('registers the protected Alipay sandbox return route with the order menu metadata', () => {
    const userRoot = routes.find((item) => item.path === '/')
    const returnRoute = userRoot?.children?.find((item) => item.path === 'me/payments/alipay/sandbox/return')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/me/payments/alipay/sandbox/return?out_trade_no=PAY001')

    expect(returnRoute?.name).toBe('AlipaySandboxReturn')
    expect(returnRoute?.meta?.menuKey).toBe('order:my')
    expect(resolved.name).toBe('AlipaySandboxReturn')
    expect(resolved.meta.menuKey).toBe('order:my')
  })

  it('registers B6 system payment and reconciliation routes with locked permission metadata', () => {
    const systemRoot = routes.find((item) => item.path === '/system-admin')
    const paymentsRoute = systemRoot?.children?.find((item) => item.path === 'payments')
    const reconciliationRoute = systemRoot?.children?.find((item) => item.path === 'reconciliation')
    const router = createRouter({ history: createWebHistory(), routes })
    const paymentsResolved = router.resolve('/system-admin/payments')
    const reconciliationResolved = router.resolve('/system-admin/reconciliation')

    expect(paymentsRoute?.name).toBe('SystemPayments')
    expect(paymentsRoute?.meta?.menuKey).toBe('payment:admin')
    expect(paymentsResolved.name).toBe('SystemPayments')
    expect(paymentsResolved.meta.menuKey).toBe('payment:admin')
    expect(reconciliationRoute?.name).toBe('SystemReconciliation')
    expect(reconciliationRoute?.meta?.menuKey).toBe('finance:reconcile')
    expect(reconciliationResolved.name).toBe('SystemReconciliation')
    expect(reconciliationResolved.meta.menuKey).toBe('finance:reconcile')
  })

  it('registers venue-admin checkin route with locked B4 permission metadata', () => {
    const adminRoot = routes.find((item) => item.path === '/venue-admin')
    const checkinRoute = adminRoot?.children?.find((item) => item.path === 'checkins')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/venue-admin/checkins')

    expect(checkinRoute?.name).toBe('VenueAdminCheckins')
    expect(checkinRoute?.meta?.menuKey).toBe('reservation:checkin:manage')
    expect(resolved.name).toBe('VenueAdminCheckins')
    expect(resolved.meta.menuKey).toBe('reservation:checkin:manage')
  })

  it('registers venue-admin funnel analytics route with locked B5 permission metadata', () => {
    const adminRoot = routes.find((item) => item.path === '/venue-admin')
    const funnelRoute = adminRoot?.children?.find((item) => item.path === 'funnel')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/venue-admin/funnel')

    expect(funnelRoute?.name).toBe('VenueAdminFunnel')
    expect(funnelRoute?.meta?.menuKey).toBe('funnel:analytics:view')
    expect(resolved.name).toBe('VenueAdminFunnel')
    expect(resolved.meta.menuKey).toBe('funnel:analytics:view')
  })

  it('keeps message center available to every authenticated role', () => {
    const userRoot = routes.find((item) => item.path === '/')
    const messageRoute = userRoot?.children?.find((item) => item.path === 'me/messages')
    expect(messageRoute?.meta?.menuKey).toBeUndefined()
  })

  it('registers B2 member routes with locked permission metadata', () => {
    const userRoot = routes.find((item) => item.path === '/')
    const systemRoot = routes.find((item) => item.path === '/system-admin')
    const memberRoute = userRoot?.children?.find((item) => item.path === 'me/membership')
    expect(memberRoute?.meta?.menuKey).toBe('member:me')

    const adminMemberRoute = systemRoot?.children?.find((item) => item.path === 'members')
    expect(adminMemberRoute?.meta?.menuKey).toBe('member:manage')
  })

  it('registers R4 reward catalog admin route with member manage permission', () => {
    const systemRoot = routes.find((item) => item.path === '/system-admin')
    const route = systemRoot?.children?.find((item) => item.path === 'reward-catalog')
    const router = createRouter({ history: createWebHistory(), routes })
    const resolved = router.resolve('/system-admin/reward-catalog')

    expect(route?.name).toBe('SystemRewardCatalog')
    expect(route?.meta?.menuKey).toBe('member:manage')
    expect(resolved.name).toBe('SystemRewardCatalog')
    expect(resolved.meta.menuKey).toBe('member:manage')
  })

  it('registers B3 promotion and coupon routes with locked permission metadata', () => {
    const userRoot = routes.find((item) => item.path === '/')
    const systemRoot = routes.find((item) => item.path === '/system-admin')

    const couponsRoute = userRoot?.children?.find((item) => item.path === 'me/coupons')
    expect(couponsRoute?.name).toBe('Coupons')
    expect(couponsRoute?.meta?.menuKey).toBe('coupon:me')

    const promotionsRoute = systemRoot?.children?.find((item) => item.path === 'promotions')
    expect(promotionsRoute?.name).toBe('SystemPromotions')
    expect(promotionsRoute?.meta?.menuKey).toBe('promotion:manage')
  })

  it('resolves seeded promotion pricing menu path to the promotion admin page', () => {
    const router = createRouter({ history: createWebHistory(), routes })
    const seededRoute = router.resolve('/system-admin/promotion-pricing')

    expect(seededRoute.name).toBe('SystemPromotions')
    expect(seededRoute.meta.menuKey).toBe('promotion:manage')
  })

  it('stores role and permissions from login response', () => {
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'token',
      expiresIn: 3600,
      user: { id: 1, username: 'admin', status: 'ACTIVE' },
      roles: ['SYSTEM_ADMIN'],
      permissions: ['system:view']
    })
    expect(auth.hasRole('SYSTEM_ADMIN')).toBe(true)
    expect(auth.hasPermission('system:view')).toBe(true)
  })

  it('refreshes the server authorization snapshot before checking an admin route role', async () => {
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'token',
      expiresIn: 3600,
      user: { id: 7, username: 'venue-admin', status: 'ACTIVE' },
      roles: ['USER'],
      permissions: ['user:view']
    })
    vi.mocked(authApi.menus).mockResolvedValue([
      {
        id: 10,
        parentId: 0,
        name: '场馆后台',
        path: '/venue-admin',
        component: 'VenueAdminDashboard',
        perms: 'venue-admin:view',
        type: 'MENU'
      }
    ])
    vi.mocked(authApi.authorization).mockResolvedValue({
      roles: ['VENUE_ADMIN'],
      permissions: ['venue-admin:view']
    })
    const { default: router } = await import('./index')

    await router.push('/venue-admin')

    expect(authApi.authorization).toHaveBeenCalledOnce()
    expect(auth.hasRole('VENUE_ADMIN')).toBe(true)
    expect(router.currentRoute.value.path).toBe('/venue-admin')
  })

  it('turns an expired authorization refresh into an explicit login redirect', async () => {
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'expired-token', expiresIn: 3600,
      user: { id: 12, username: 'expired-user', status: 'ACTIVE' },
      roles: ['USER'], permissions: ['community:access']
    })
    vi.mocked(authApi.menus).mockResolvedValue([])
    vi.mocked(authApi.authorization).mockImplementationOnce(async () => {
      auth.logoutLocal()
      throw new Error('Request failed with status code 401')
    })
    const { default: router } = await import('./index')

    await expect(router.push('/community')).resolves.toBeUndefined()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/community')
  })

  it('denies content operations without the server permission even for a venue admin', async () => {
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'token', expiresIn: 3600,
      user: { id: 8, username: 'venue-admin', status: 'ACTIVE' },
      roles: ['VENUE_ADMIN'], permissions: ['venue-admin:view']
    })
    vi.mocked(authApi.menus).mockResolvedValue([])
    vi.mocked(authApi.authorization).mockResolvedValue({
      roles: ['VENUE_ADMIN'], permissions: ['venue-admin:view']
    })
    const { default: router } = await import('./index')

    await router.push('/content-admin')

    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('denies a leaf-only content role without the independent workspace entry permission', async () => {
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'token', expiresIn: 3600,
      user: { id: 9, username: 'content-operator', status: 'ACTIVE' },
      roles: ['CONTENT_OPERATOR'], permissions: []
    })
    vi.mocked(authApi.menus).mockResolvedValue([])
    vi.mocked(authApi.authorization).mockResolvedValue({
      roles: ['CONTENT_OPERATOR'], permissions: ['community:content:moderate']
    })
    const { default: router } = await import('./index')

    await router.push('/content-admin')

    expect(router.currentRoute.value.path).toBe('/403')
    expect(auth.hasPermission('community:content:moderate')).toBe(true)
  })

  it('denies the placement route when content entry exists but no placement leaf exists', async () => {
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'token', expiresIn: 3600,
      user: { id: 15, username: 'content-viewer', status: 'ACTIVE' },
      roles: ['CONTENT_OPERATOR'], permissions: ['content-admin:view']
    })
    vi.mocked(authApi.menus).mockResolvedValue([])
    vi.mocked(authApi.authorization).mockResolvedValue({
      roles: ['CONTENT_OPERATOR'], permissions: ['content-admin:view']
    })
    const { default: router } = await import('./index')

    await router.push('/content-admin/placements')

    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('allows a dynamic content role with both workspace entry and leaf permissions', async () => {
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'token', expiresIn: 3600,
      user: { id: 10, username: 'content-operator', status: 'ACTIVE' },
      roles: ['CONTENT_OPERATOR'], permissions: []
    })
    vi.mocked(authApi.menus).mockResolvedValue([])
    vi.mocked(authApi.authorization).mockResolvedValue({
      roles: ['CONTENT_OPERATOR'],
      permissions: ['content-admin:view', 'community:content:moderate']
    })
    const { default: router } = await import('./index')

    await router.push('/content-admin')

    expect(router.currentRoute.value.path).toBe('/content-admin')
    expect(auth.hasPermission('community:content:moderate')).toBe(true)
  })

  it('allows a dynamic community role through the permission contract without a hard-coded role', async () => {
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'token', expiresIn: 3600,
      user: { id: 11, username: 'community-member', status: 'ACTIVE' },
      roles: ['COMMUNITY_MEMBER'], permissions: []
    })
    vi.mocked(authApi.menus).mockResolvedValue([{ id: 30, parentId: 0, name: '社区', path: '/community', component: '', perms: 'community:access', type: 'MENU' }])
    vi.mocked(authApi.authorization).mockResolvedValue({
      roles: ['COMMUNITY_MEMBER'], permissions: ['community:access']
    })
    const { default: router } = await import('./index')

    await router.push('/community')

    expect(router.currentRoute.value.path).toBe('/community')
    expect(auth.hasPermission('community:access')).toBe(true)
  })

  it('keeps a cached authorized page renderable during a transient backend outage', async () => {
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'token', expiresIn: 3600,
      user: { id: 16, username: 'content-operator', status: 'ACTIVE' },
      roles: ['CONTENT_OPERATOR'],
      permissions: ['content-admin:view', 'advertising:analysis:view']
    })
    vi.mocked(authApi.menus).mockRejectedValue(new Error('Request failed with status code 500'))
    vi.mocked(authApi.authorization).mockRejectedValue(new Error('Request failed with status code 500'))
    const { default: router } = await import('./index')

    await expect(router.push('/content-admin/advertising/analysis')).resolves.toBeUndefined()

    expect(router.currentRoute.value.path).toBe('/content-admin/advertising/analysis')
  })

})

describe('SHIGUANG-BATCH-02 后台路由合同', () => {
  // 第二批四个新面承接 12 条旧路由：换组件不换 path/name/menuKey/roles，
  // 本合同在 S04-S07 组件替换期间持续锁定旧入口可达性（contracts.md §1.2/§2.3/§3.2/§4.2）。
  it('venue-admin 四条履约路由与两条资源路由保持 path/name/menuKey', () => {
    const root = routes.find((item) => item.path === '/venue-admin')
    const expected: Array<[string, string, string]> = [
      ['', 'VenueAdminDashboard', 'venue-admin:view'],
      ['reservations', 'VenueAdminReservations', 'reservation:audit'],
      ['checkins', 'VenueAdminCheckins', 'reservation:checkin:manage'],
      ['orders', 'VenueAdminOrders', 'order:admin'],
      ['spaces', 'VenueAdminSpaces', 'venue:space:manage'],
      ['calendar', 'VenueAdminCalendar', 'venue:inventory:view']
    ]
    for (const [path, name, menuKey] of expected) {
      const route = root?.children?.find((item) => item.path === path)
      expect(route?.name, `path ${path}`).toBe(name)
      expect(route?.meta?.menuKey, `path ${path}`).toBe(menuKey)
    }
    expect(root?.meta?.roles).toEqual(['VENUE_ADMIN', 'SYSTEM_ADMIN'])
  })

  it('system-admin 五条路由与 providers 路由保持 path/name/menuKey', () => {
    const root = routes.find((item) => item.path === '/system-admin')
    const expected: Array<[string, string, string]> = [
      ['', 'SystemAdminDashboard', 'system:view'],
      ['users', 'SystemUsers', 'system:user:manage'],
      ['roles', 'SystemRoles', 'system:role:manage'],
      ['configs', 'SystemConfigs', 'system:config:manage'],
      ['logs', 'SystemLogs', 'system:log:view'],
      ['providers', 'SystemProviders', 'provider:observe']
    ]
    for (const [path, name, menuKey] of expected) {
      const route = root?.children?.find((item) => item.path === path)
      expect(route?.name, `path ${path}`).toBe(name)
      expect(route?.meta?.menuKey, `path ${path}`).toBe(menuKey)
    }
    expect(root?.meta?.roles).toEqual(['SYSTEM_ADMIN'])
  })
})

describe('router scrollBehavior 与面内页签 hash（B4-C2）', () => {
  it('只对真实存在的锚点返回 el，避免 #<页签> 状态产生 vue-router 警告', async () => {
    // 面内页签用 #<view> 表示状态，页面上没有同 id 的元素；此时不应返回 el。
    document.body.innerHTML = '<div id="real-anchor"></div>'
    expect(scrollBehavior({ hash: '#levels' }, {}, null)).toEqual({ top: 0, left: 0 })
    expect(scrollBehavior({ hash: '#real-anchor' }, {}, null)).toEqual({ el: '#real-anchor', top: 84 })
    // 无 hash 与浏览器前进后退的既有行为不变。
    expect(scrollBehavior({ hash: '' }, {}, null)).toEqual({ top: 0, left: 0 })
    expect(scrollBehavior({ hash: '#levels' }, {}, { top: 40 })).toEqual({ top: 40 })
    document.body.innerHTML = ''
  })

  it('不是合法选择器的 hash 按页面状态处理，不让 querySelector 抛到浏览器控制台', async () => {
    // 深链 `#<页签>` 与数字锚点 `#3` 都会让 querySelector 抛 SyntaxError；
    // 它们都只表示面内状态，应与 #levels 一样回落顶部而不是报错。
    document.body.innerHTML = '<div id="real-anchor"></div>'
    expect(() => scrollBehavior({ hash: '#<页签>' }, {}, null)).not.toThrow()
    expect(scrollBehavior({ hash: '#<页签>' }, {}, null)).toEqual({ top: 0, left: 0 })
    expect(scrollBehavior({ hash: '#3' }, {}, null)).toEqual({ top: 0, left: 0 })
    expect(scrollBehavior({ hash: '#real-anchor' }, {}, null)).toEqual({ el: '#real-anchor', top: 84 })
    document.body.innerHTML = ''
  })
})
