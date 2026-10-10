import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ShiguangHomeView from './ShiguangHomeView.vue'
import { useMenuStore } from '@/stores/menu'
import { useShiguangStore } from '@/stores/shiguang'
import type { MenuNode } from '@/api/types'

const venueApiMock = vi.hoisted(() => ({ list: vi.fn() }))
const reservationApiMock = vi.hoisted(() => ({ availabilitySummary: vi.fn() }))
const meTimeApiMock = vi.hoisted(() => ({ myTime: vi.fn() }))
const activityApiMock = vi.hoisted(() => ({ activities: vi.fn(), companions: vi.fn() }))
const communityApiMock = vi.hoisted(() => ({ echo: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('@/api/venue', () => ({ venueApi: venueApiMock }))
vi.mock('@/api/reservation', () => ({ reservationApi: reservationApiMock }))
vi.mock('@/api/meTime', () => ({ meTimeApi: meTimeApiMock }))
vi.mock('@/api/activity', () => ({ activityApi: activityApiMock }))
vi.mock('@/api/community', () => ({ communityApi: communityApiMock }))
vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() }
}))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => ({ path: '/', query: {} }) }))

const stubs = {
  ShiguangArt: true,
  HomePrimaryPlacement: true,
  HomeCommercialAdvertising: true,
  'router-link': { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : \'#\'"><slot /></a>' }
}

const withCommunity = (granted: boolean) => {
  const menu = useMenuStore()
  menu.menus = (granted ? [{ id: 1, name: '社区', path: '/community', perms: 'community:access' }] : []) as unknown as MenuNode[]
}

const mountView = () => mount(ShiguangHomeView, { global: { stubs } })

describe('ShiguangHomeView optional community content (AR-03)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    venueApiMock.list.mockResolvedValue({
      records: [{ id: 3, name: '星河体育馆', area: '东区', spaceTypes: ['BADMINTON'] }],
      total: 1, pageNum: 1, pageSize: 10
    })
    reservationApiMock.availabilitySummary.mockResolvedValue([{ venueId: 3, available: true, startTime: '2099-08-01T10:00:00' }])
    meTimeApiMock.myTime.mockResolvedValue({ records: [{ id: 1, sourceType: 'RESERVATION', title: '羽毛球', startTime: '2099-08-01T10:00:00', status: '已确认', pending: false }] })
    activityApiMock.activities.mockResolvedValue({ records: [{ id: 41, title: '期末复习', groupName: '学习组' }], nextCursor: null, hasMore: false })
    activityApiMock.companions.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })
    communityApiMock.echo.mockResolvedValue([{ postId: 1, groupId: 2, title: '回声一', excerpt: '分享' }])
  })

  it('omits the optional community requests for a viewer without community:access', async () => {
    withCommunity(false)
    const wrapper = mountView()
    await flushPromises()

    expect(activityApiMock.activities).not.toHaveBeenCalled()
    expect(activityApiMock.companions).not.toHaveBeenCalled()
    expect(communityApiMock.echo).not.toHaveBeenCalled()
    // 首页核心内容照常可用：场馆轨道与个人安排都在
    expect(venueApiMock.list).toHaveBeenCalled()
    expect(meTimeApiMock.myTime).toHaveBeenCalled()
    expect(wrapper.text()).toContain('星河体育馆')
    expect(wrapper.text()).toContain('社区里暂时没有公开的新分享。')
  })

  it('loads the optional content with a local degrade config when the viewer has access', async () => {
    withCommunity(true)
    const wrapper = mountView()
    await flushPromises()

    expect(activityApiMock.activities).toHaveBeenCalledWith({ limit: 4 }, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    expect(activityApiMock.companions).toHaveBeenCalledWith({ limit: 2 }, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    expect(communityApiMock.echo).toHaveBeenCalledWith(1, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    expect(wrapper.text()).toContain('期末复习')
    expect(wrapper.text()).toContain('回声一')
  })

  it('stays usable on the home page when the community permission was revoked server-side', async () => {
    withCommunity(true)
    activityApiMock.activities.mockRejectedValue({ response: { status: 403 } })
    activityApiMock.companions.mockRejectedValue({ response: { status: 403 } })
    communityApiMock.echo.mockRejectedValue({ response: { status: 403 } })
    const wrapper = mountView()
    await flushPromises()

    // 局部降级：附属块回落到空态，场馆与个人安排继续可用，不把用户交给 /403 全屏
    expect(wrapper.text()).toContain('星河体育馆')
    expect(wrapper.text()).toContain('还没有匹配的邀约，换个关键词看看。')
    expect(wrapper.text()).toContain('社区里暂时没有公开的新分享。')
  })

  it('never turns a failed optional request into a personal arrangement', async () => {
    withCommunity(true)
    activityApiMock.activities.mockRejectedValue(new Error('Network Error'))
    activityApiMock.companions.mockRejectedValue(new Error('Network Error'))
    communityApiMock.echo.mockRejectedValue(new Error('Network Error'))
    const wrapper = mountView()
    await flushPromises()

    // 失败不伪装成个人安排：我的时间仍只来自 meTimeApi
    expect(meTimeApiMock.myTime).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('羽毛球')
    expect(wrapper.text()).toContain('还没有匹配的邀约，换个关键词看看。')
  })
})

/**
 * F-03 回归（SHIGUANG-WAVE-01-REMEDIATION-20260916 S02）。
 *
 * 原始缺陷：`.daily-feature` 同时带 `v-if="feature"` 与 `:key="feature.id"`，两条分支
 * 共享同一 block；切回「全部发现」时 block 的 `dynamicChildren` 出现漂移，Vue 补丁在
 * 读 `oldChildren[i].el` 时抛 `TypeError: Cannot read properties of undefined (reading 'el')`，
 * 并让进入首页后的后续导航持续报同类错误。
 *
 * 说明（诚实边界）：该运行错误依赖真实浏览器里的异步时序与尺寸测量，jsdom 下无法稳定复现
 * （同一用例在缺陷版本上时而报错、时而不报）。因此**跨版本回归由真实浏览器脚本锁定**
 * （见本项 verification/s02 的 f03-variant-run.py：缺陷版 8/8 失败、修复版 8/8 通过）。
 * 这里的用例只锁定修复后的结构合同，避免结构被改回缺陷形态：
 *   1) 提案内容必须挂在一个带 `:key="feature.id"` 的身份层上（保证切换时重播入场动画）；
 *   2) `v-if`/`v-else` 容器不得再带 key，两条分支的共享 block 结构保持稳定；
 *   3) 真实 `ShiguangArt`（不 stub）参与渲染时不应产生 Vue 运行错误。
 */
describe('ShiguangHomeView feature block identity (F-03)', () => {
  const mountWithErrorCapture = () => {
    const errors: string[] = []
    const wrapper = mount(ShiguangHomeView, {
      global: {
        stubs: {
          HomePrimaryPlacement: true,
          HomeCommercialAdvertising: true,
          'router-link': { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : \'#\'"><slot /></a>' }
        },
        config: {
          errorHandler: (error: unknown) => { errors.push(String((error as Error)?.message ?? error)) }
        }
      }
    })
    return { wrapper, errors }
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    venueApiMock.list.mockResolvedValue({
      records: [
        { id: 11, name: '星河体育馆', area: '东区', spaceTypes: ['BADMINTON'] },
        { id: 12, name: '清溪泳馆', area: '西区', spaceTypes: ['SWIMMING'] }
      ],
      total: 2, pageNum: 1, pageSize: 10
    })
    reservationApiMock.availabilitySummary.mockResolvedValue([
      { venueId: 11, available: true, startTime: '2099-08-01T10:00:00' },
      { venueId: 12, available: false, startTime: null }
    ])
    meTimeApiMock.myTime.mockResolvedValue({ records: [] })
    activityApiMock.activities.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })
    activityApiMock.companions.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })
    communityApiMock.echo.mockResolvedValue([])
    useMenuStore().menus = [] as unknown as MenuNode[]
  })

  it('keeps the proposal key off the v-if/v-else container', async () => {
    const { wrapper, errors } = mountWithErrorCapture()
    await flushPromises()

    const container = wrapper.find('.daily-feature')
    expect(container.exists()).toBe(true)
    // 容器本身不得带 key（带 key 即回到缺陷形态）
    expect(container.attributes('key')).toBeUndefined()
    // 身份层承载 key，并由真实 ShiguangArt 插画参与渲染
    expect(wrapper.find('.daily-feature-inner').exists()).toBe(true)
    expect(wrapper.find('.sgv-art svg').exists()).toBe(true)
    expect(errors).toEqual([])

    // 逐一切换类别后两条分支仍各自可用：提案块与空态块恰好出现一个
    const store = useShiguangStore()
    for (const category of ['move', 'quiet', 'water', 'together', 'all']) {
      store.category = category
      await flushPromises()
      await wrapper.vm.$nextTick()
    }
    const featureVisible = wrapper.find('.daily-feature').exists()
    const emptyVisible = wrapper.find('.feature-empty').exists()
    expect(featureVisible !== emptyVisible).toBe(true)
    expect(errors).toEqual([])
  })

  it('remounts the proposal identity layer so the switch animation replays', async () => {
    const { wrapper, errors } = mountWithErrorCapture()
    await flushPromises()

    const before = wrapper.find('.daily-feature-inner').element
    const next = wrapper.find('[aria-label="下一条提案"]')
    expect(next.exists()).toBe(true)
    await next.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    const after = wrapper.find('.daily-feature-inner').element

    // 身份层重新挂载 → 入场动画重播；容器不变 → block 结构稳定
    expect(after).not.toBe(before)
    expect(wrapper.find('.daily-feature').exists()).toBe(true)
    expect(errors).toEqual([])
  })
})
