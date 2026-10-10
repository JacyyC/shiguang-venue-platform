import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardView from './DashboardView.vue'

const apiMock = vi.hoisted(() => ({
  dashboard: vi.fn(),
  manageable: vi.fn()
}))

vi.mock('@/api/statistics', () => ({ statisticsApi: apiMock }))
vi.mock('@/api/venue', () => ({ venueAdminApi: { manageable: apiMock.manageable } }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return { ...actual, ElMessage: { success: vi.fn(), error: vi.fn() } }
})

const venue = { id: 7, name: '羽跃羽毛球馆' }

const mountView = async () => {
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(DashboardView, { global: { plugins: [pinia] } })
  await flushPromises()
  return wrapper
}

describe('DashboardView 场馆工作台未绑定收敛（C-09）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.dashboard.mockResolvedValue({ pendingReviews: 0, todayReservations: 0 })
    apiMock.manageable.mockResolvedValue([])
  })

  it('shows the binding guide instead of a full-screen rejection when no venue is bound', async () => {
    const wrapper = await mountView()

    const guide = wrapper.find('[data-test="bind-guide"]')
    expect(guide.exists()).toBe(true)
    expect(guide.text()).toContain('还差一步：绑定你要管理的场馆')
    expect(guide.text()).toContain('绑定后即可处理预约审核、核销与现场运营')
    // 指引默认收起； KPI 区域不渲染。
    expect(guide.text()).not.toContain('平台治理 → 用户管理 → 场馆授权')
    expect(wrapper.find('.kpi-strip').exists()).toBe(false)
    // 同步 venueAccess 缓存供侧边栏锁定判定使用。
    const { useVenueAccessStore } = await import('@/stores/venueAccess')
    expect(useVenueAccessStore().venues).toEqual([])
    expect(useVenueAccessStore().loaded).toBe(true)
  })

  it('expands the step-by-step guidance and copies the request text to clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const wrapper = await mountView()

    const guide = wrapper.find('[data-test="bind-guide"]')
    const toggle = guide.findAll('button').find((button) => button.text().includes('查看绑定指引'))!
    await toggle.trigger('click')

    const detail = guide.find('.bind-guide-detail')
    expect(detail.exists()).toBe(true)
    expect(detail.text()).toContain('平台治理 → 用户管理 → 场馆授权')

    const copyButton = guide.findAll('button').find((button) => button.text().includes('复制申请文案'))!
    await copyButton.trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('@系统管理员：请为我在 平台治理→用户管理→场馆授权 绑定可管理场馆')
    expect(copyButton.text()).toContain('已复制，等待管理员处理')
  })

  it('keeps the full workspace for an account with bound venues', async () => {
    apiMock.manageable.mockResolvedValue([venue])
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="bind-guide"]').exists()).toBe(false)
    expect(wrapper.find('.duty-panel').exists()).toBe(true)
    expect(apiMock.dashboard).toHaveBeenCalledWith({ venueId: 7 })
  })
})
