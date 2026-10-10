import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminLayout from './AdminLayout.vue'
import ShiguangMemberAdminView from '@/views/admin/shiguang/member/ShiguangMemberAdminView.vue'
import ShiguangPromotionAdminView from '@/views/admin/shiguang/promotion/ShiguangPromotionAdminView.vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'

/**
 * F-20：`AdminLayout` 以 `fullPath` 作为动态组件 key，页签写入 hash 会把子视图整体重建。
 * 这些用例必须挂在真实壳上跑——直接挂子视图的单测看不到重建后的初始页签。
 */

const memberApiMock = vi.hoisted(() => ({
  levels: vi.fn(),
  accounts: vi.fn(),
  overview: vi.fn()
}))
const rewardApiMock = vi.hoisted(() => ({
  listCatalogItems: vi.fn(),
  exceptions: vi.fn(),
  events: vi.fn()
}))
const promotionApiMock = vi.hoisted(() => ({
  campaigns: vi.fn(),
  couponTemplates: vi.fn(),
  couponRedemptions: vi.fn(),
  priceRules: vi.fn(),
  overview: vi.fn()
}))
const venueApiMock = vi.hoisted(() => ({
  venueApi: { spaces: vi.fn() },
  venueAdminApi: { manageable: vi.fn() }
}))
const notificationStreamMock = vi.hoisted(() => ({ start: vi.fn(), stop: vi.fn() }))

vi.mock('@/api/member', () => ({ adminMemberApi: memberApiMock, adminMemberRewardApi: rewardApiMock }))
vi.mock('@/api/promotion', () => ({ promotionAdminApi: promotionApiMock }))
vi.mock('@/api/venue', () => venueApiMock)
vi.mock('@/composables/useNotificationStream', () => ({ useNotificationStream: () => notificationStreamMock }))

// jsdom 不实现原生 dialog 的 showModal/close，与 AdminLayout.spec.ts 用同一桩
HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) { this.open = true })
HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
  this.open = false
  this.dispatchEvent(new Event('close'))
})

const MEMBER_TABS = ['accounts', 'levels', 'rewards', 'exceptions', 'events']
const PROMOTION_TABS = ['campaigns', 'coupons', 'rules', 'redemptions']

const SYSTEM_MENUS = [
  {
    id: 40,
    parentId: 0,
    name: '平台治理后台',
    path: '/system-admin',
    component: '',
    perms: 'system:view',
    type: 'MENU',
    children: [
      { id: 41, parentId: 40, name: '会员与权益', path: '/system-admin/members', component: '', perms: 'member:manage', type: 'MENU' },
      { id: 42, parentId: 40, name: '权益目录', path: '/system-admin/reward-catalog', component: '', perms: 'member:manage', type: 'MENU' },
      { id: 43, parentId: 40, name: '促销与优惠', path: '/system-admin/promotions', component: '', perms: 'promotion:manage', type: 'MENU' }
    ]
  }
] as never

const page = <T>(records: T[]) => ({ records, total: records.length, pageNum: 1, pageSize: 20 })

const ALL_PERMISSIONS = [
  'member:manage',
  'member-reward:catalog:manage',
  'member-reward:exception:manage',
  'member-reward:audit:view',
  'promotion:manage',
  'coupon:manage',
  'pricing:manage'
]

const mounted: VueWrapper[] = []

function makeRouter(): Router {
  return createRouter({
    history: createWebHistory(),
    routes: [
      {
        path: '/system-admin',
        component: AdminLayout,
        meta: { requiresAuth: true, roles: ['SYSTEM_ADMIN'], adminTitle: '系统管理后台' },
        children: [
          { path: 'members', name: 'SystemMembers', component: ShiguangMemberAdminView, meta: { menuKey: 'member:manage' } },
          { path: 'reward-catalog', name: 'SystemRewardCatalog', component: ShiguangMemberAdminView, meta: { menuKey: 'member:manage' } },
          {
            path: 'promotions',
            name: 'SystemPromotions',
            alias: '/system-admin/promotion-pricing',
            component: ShiguangPromotionAdminView,
            meta: { menuKey: 'promotion:manage' }
          }
        ]
      },
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } }
    ]
  })
}

function activeTab(wrapper: VueWrapper): string {
  return wrapper.find('.b-tabs button[aria-pressed="true"]').attributes('data-test') ?? ''
}

/** out-in 过渡下的重建跨多帧，轮询到稳定态再断言。 */
async function until(check: () => boolean, label: string): Promise<void> {
  const deadline = Date.now() + 1000
  for (;;) {
    await flushPromises()
    if (check()) return
    if (Date.now() > deadline) throw new Error(`等待超时：${label}`)
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
}

async function mountAt(path: string, permissions = ALL_PERMISSIONS): Promise<{ wrapper: VueWrapper; router: Router }> {
  useAuthStore().$patch({ roles: ['SYSTEM_ADMIN'], permissions })
  const router = makeRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AdminLayout, { global: { plugins: [router], stubs: { Transition: false } }, attachTo: document.body })
  mounted.push(wrapper)
  await until(() => wrapper.find('.b-tabs button[aria-pressed="true"]').exists(), `${path} 页签渲染`)
  return { wrapper, router }
}

function focusedTab(wrapper: VueWrapper): string {
  const active = wrapper.element.ownerDocument.activeElement as HTMLElement | null
  return active?.getAttribute('data-test') ?? ''
}

async function clickTab(wrapper: VueWrapper, router: Router, tab: string): Promise<void> {
  await wrapper.find(`[data-test="tab-${tab}"]`).trigger('click')
  await until(() => activeTab(wrapper) === `tab-${tab}` && router.currentRoute.value.hash === `#${tab}`,
    `页签 ${tab} 在重建后仍然激活`)
  await until(() => focusedTab(wrapper) === `tab-${tab}`, `重建后焦点回到页签 ${tab}`)
}

describe('AdminLayout 内的会员/营销页签深链（F-20）', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    setActivePinia(createPinia())
    useAuthStore().$patch({
      token: 'token',
      user: { id: 1, username: 'root', nickname: '平台管理员', status: 'ACTIVE' },
      roles: ['SYSTEM_ADMIN'],
      permissions: ALL_PERMISSIONS
    })
    useMenuStore().$patch({ loaded: true, unavailable: false, tokenSnapshot: 'token', menus: SYSTEM_MENUS })
    useAppStore().refreshUnreadCount = vi.fn().mockResolvedValue(undefined)
    venueApiMock.venueAdminApi.manageable.mockResolvedValue([])
    memberApiMock.accounts.mockResolvedValue(page([]))
    memberApiMock.levels.mockResolvedValue([])
    memberApiMock.overview.mockResolvedValue({})
    rewardApiMock.listCatalogItems.mockResolvedValue(page([]))
    rewardApiMock.exceptions.mockResolvedValue(page([]))
    rewardApiMock.events.mockResolvedValue(page([]))
    promotionApiMock.campaigns.mockResolvedValue(page([]))
    promotionApiMock.couponTemplates.mockResolvedValue(page([]))
    promotionApiMock.couponRedemptions.mockResolvedValue(page([]))
    promotionApiMock.priceRules.mockResolvedValue(page([]))
    promotionApiMock.overview.mockResolvedValue({})
  })

  afterEach(() => {
    while (mounted.length) mounted.pop()!.unmount()
    document.body.innerHTML = ''
  })

  it('三条入口与别名各自落到约定的默认页签', async () => {
    const cases: Array<[string, string]> = [
      ['/system-admin/members', 'tab-accounts'],
      ['/system-admin/reward-catalog', 'tab-rewards'],
      ['/system-admin/promotions', 'tab-campaigns'],
      ['/system-admin/promotion-pricing', 'tab-campaigns']
    ]
    for (const [path, tab] of cases) {
      const { wrapper } = await mountAt(path)
      expect(activeTab(wrapper)).toBe(tab)
      wrapper.unmount()
    }
  })

  it('会员面点击页签写入 hash，壳重建视图后仍停在被点页签', async () => {
    const { wrapper, router } = await mountAt('/system-admin/members')
    const accountsCalls = memberApiMock.accounts.mock.calls.length
    await clickTab(wrapper, router, 'events')
    expect(rewardApiMock.events).toHaveBeenCalled()
    // 重建只回到被点页签，不得把账户面当作默认落地再拉一次
    expect(memberApiMock.accounts.mock.calls.length).toBe(accountsCalls)
  })

  it('会员与营销全部页签都能点到位，URL 与激活页签始终一致', async () => {
    const member = await mountAt('/system-admin/members')
    for (const tab of [...MEMBER_TABS].reverse()) {
      await clickTab(member.wrapper, member.router, tab)
    }
    member.wrapper.unmount()

    const promotion = await mountAt('/system-admin/promotions')
    for (const tab of [...PROMOTION_TABS].reverse()) {
      await clickTab(promotion.wrapper, promotion.router, tab)
    }
  })

  it('别名路由上的 hash 深链直达目标页签，且不请求其它页签的端点', async () => {
    const { wrapper } = await mountAt('/system-admin/promotion-pricing#rules')
    expect(activeTab(wrapper)).toBe('tab-rules')
    expect(promotionApiMock.priceRules).toHaveBeenCalled()
    expect(promotionApiMock.campaigns).not.toHaveBeenCalled()
  })

  it('跨入口深链生效，非法 hash 回落该路由默认页签', async () => {
    const cross = await mountAt('/system-admin/reward-catalog#events')
    expect(activeTab(cross.wrapper)).toBe('tab-events')
    cross.wrapper.unmount()

    const invalid = await mountAt('/system-admin/members#<页签>')
    expect(activeTab(invalid.wrapper)).toBe('tab-accounts')
  })

  it('刷新同一 URL 后激活页签不变', async () => {
    const first = await mountAt('/system-admin/members#levels')
    expect(activeTab(first.wrapper)).toBe('tab-levels')
    const url = first.router.currentRoute.value.fullPath
    first.wrapper.unmount()

    // 刷新 = 用同一 fullPath 重新挂载整棵壳树
    const again = await mountAt(url)
    expect(activeTab(again.wrapper)).toBe('tab-levels')
  })

  it('历史条目里的页签 URL 重新解析时仍回到该页签（前进/后退的解析面）', async () => {
    // jsdom 不实现 session history 导航，`router.back()` 不会派发 popstate；
    // 真实前进/后退在 S02 的浏览器闭环里证（见本项 Evidence）。
    // 这里覆盖历史导航落地时的同一解析路径：目标 URL 带 hash 就必须落到该页签。
    const { wrapper, router } = await mountAt('/system-admin/members')
    await clickTab(wrapper, router, 'levels')
    await clickTab(wrapper, router, 'events')

    await router.push('/system-admin/members#levels')
    await until(() => router.currentRoute.value.hash === '#levels', '回到 #levels')
    await until(() => activeTab(wrapper) === 'tab-levels', '历史条目落地后页签回到等级规则')

    await router.push('/system-admin/members#events')
    await until(() => router.currentRoute.value.hash === '#events', '回到 #events')
    await until(() => activeTab(wrapper) === 'tab-events', '再进一层页签回到权益事件')
  })

  it('无权页签的深链只呈现无权态，不发越权请求', async () => {
    const { wrapper } = await mountAt('/system-admin/members#rewards', ['member:manage'])
    expect(activeTab(wrapper)).toBe('tab-rewards')
    expect(wrapper.find('[data-test="denied-rewards"]').exists()).toBe(true)
    expect(rewardApiMock.listCatalogItems).not.toHaveBeenCalled()
    expect(memberApiMock.accounts).not.toHaveBeenCalled()
  })
})
