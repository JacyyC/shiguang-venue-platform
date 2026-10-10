import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ShiguangMeTimeView from './ShiguangMeTimeView.vue'

const meTimeApiMock = vi.hoisted(() => ({
  myTime: vi.fn(),
  counts: vi.fn()
}))
const directoryApiMock = vi.hoisted(() => ({
  directory: vi.fn(),
  myHosted: vi.fn(),
  applicationMetrics: vi.fn()
}))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({
  query: {} as Record<string, string>,
  fullPath: '/me/time'
}))

vi.mock('@/api/meTime', () => ({ meTimeApi: meTimeApiMock }))
vi.mock('@/api/directory', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/directory')>()
  return { ...actual, directoryApi: directoryApiMock }
})
vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() }
}))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock }))

const stubs = {
  'el-dialog': { props: ['modelValue'], template: '<aside v-if="modelValue"><slot /></aside>' },
  'el-icon': { template: '<i />' },
  'router-link': { props: ['to'], template: '<a :href="to"><slot /></a>' },
  ShiguangArt: { props: ['scene'], template: '<span />' }
}

const meItem = (overrides: Record<string, unknown> = {}) => ({
  sourceType: 'ACTIVITY',
  sourceId: 50,
  title: '城市夜跑',
  location: '西岸运动场',
  startTime: '2099-08-01T18:00:00',
  endTime: '2099-08-01T20:00:00',
  status: 'PUBLISHED',
  pending: false,
  detailPath: '/activities/50',
  ...overrides
})

const hostedItem = (overrides: Record<string, unknown> = {}) => ({
  sourceType: 'ACTIVITY',
  sourceId: 80,
  groupId: 11,
  groupName: '慢读俱乐部',
  organizerUserId: 7,
  organizerName: '我',
  activityType: 'STUDY',
  title: '我发起的共读',
  description: '一起读书',
  startTime: '2099-08-02T09:00:00',
  endTime: '2099-08-02T11:00:00',
  locationType: 'CAMPUS_AREA',
  venueId: null,
  venueName: null,
  spaceId: null,
  spaceName: null,
  areaCode: 'NORTH_CAMPUS',
  commitmentStatus: 'CONFIRMED',
  capacityTotal: 8,
  participantCount: 1,
  status: 'PUBLISHED',
  linkedActivityId: null,
  viewerApplicationStatus: null,
  viewerParticipationRole: 'ORGANIZER',
  updateTime: '2099-07-31T08:00:00'
})

const mountView = () => mount(ShiguangMeTimeView, { global: { stubs } })

describe('ShiguangMeTimeView（V02 我的时间）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    routeMock.query = {}
    meTimeApiMock.myTime.mockResolvedValue({
      records: [meItem()], total: 1, pageNum: 1, pageSize: 20
    })
    meTimeApiMock.counts.mockResolvedValue({ pendingApplications: 1, activeParticipations: 1, hosted: 1 })
    directoryApiMock.myHosted.mockResolvedValue({
      records: [hostedItem()], nextCursor: null, hasMore: false, total: 1, typeCounts: {}, dateCounts: []
    })
  })

  it('merges personal time and hosted records without duplicating owned targets', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(meTimeApiMock.myTime).toHaveBeenCalledWith({ pageNum: 1, pageSize: 20 })
    expect(directoryApiMock.myHosted).toHaveBeenCalled()
    // 全部安排 = 个人时间 + 我的发起（ORGANIZER 参与不重复出现）
    expect(wrapper.findAll('[data-test="time-record"]')).toHaveLength(2)
    const titles = wrapper.findAll('[data-test="time-record"] h3').map(node => node.text())
    expect(titles).toEqual(['城市夜跑', '我发起的共读'])
  })

  it('routes owned rows to the manage surface with the owned label', async () => {
    const wrapper = mountView()
    await flushPromises()

    const ownedRow = wrapper.findAll('[data-test="time-record"]').at(1)!
    expect(ownedRow.text()).toContain('我发起的')
    expect(ownedRow.find('a').attributes('href')).toBe('/activities/80/manage')
  })

  it('shows the waiting-response tab with the exact server count', async () => {
    meTimeApiMock.myTime.mockResolvedValue({
      records: [meItem({ pending: true, status: 'PENDING' })], total: 1, pageNum: 1, pageSize: 20
    })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="time-tab-pending"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="group-count"]').text()).toBe('1 段时间')
    const rows = wrapper.findAll('[data-test="time-record"]')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('等待回应')
  })

  it('keeps the joined tab free of reservations and pending applications', async () => {
    meTimeApiMock.myTime.mockResolvedValue({
      records: [
        meItem({ sourceId: 1 }),
        meItem({ sourceId: 2, sourceType: 'RESERVATION', title: '网球场地', status: 'PAID', detailPath: '/me/reservations' }),
        meItem({ sourceId: 3, pending: true, status: 'PENDING', title: '待回应的邀约' })
      ],
      total: 3, pageNum: 1, pageSize: 20
    })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="time-tab-joined"]').trigger('click')
    await flushPromises()

    const titles = wrapper.findAll('[data-test="time-record"] h3').map(node => node.text())
    expect(titles).toEqual(['城市夜跑'])
  })

  it('keeps reservation labels verbatim and links them to their own pages', async () => {
    meTimeApiMock.myTime.mockResolvedValue({
      records: [meItem({ sourceType: 'RESERVATION', sourceId: 20501, title: '西岸运动场', status: 'PENDING_REVIEW', detailPath: '/me/reservations' })],
      total: 1, pageNum: 1, pageSize: 20
    })
    const wrapper = mountView()
    await flushPromises()

    const row = wrapper.findAll('[data-test="time-record"]')[0]
    expect(row.text()).toContain('待审核')
    expect(row.text()).toContain('场地预约')
    expect(row.find('a').attributes('href')).toBe('/me/reservations')
  })

  it('pages through the personal feed with the page-based API', async () => {
    meTimeApiMock.myTime.mockResolvedValue({
      records: [meItem()], total: 3, pageNum: 1, pageSize: 20
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="load-more"]').exists()).toBe(true)
    meTimeApiMock.myTime.mockResolvedValue({
      records: [meItem({ sourceId: 51 }), meItem({ sourceId: 52 })], total: 3, pageNum: 2, pageSize: 20
    })
    await wrapper.find('[data-test="load-more"]').trigger('click')
    await flushPromises()

    expect(meTimeApiMock.myTime).toHaveBeenLastCalledWith({ pageNum: 2, pageSize: 20 })
    expect(wrapper.findAll('[data-test="time-record"]')).toHaveLength(4)
  })

  it('renders the retryable error state instead of faking an empty page', async () => {
    meTimeApiMock.myTime.mockRejectedValue(new Error('down'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="load-error"]').text()).toContain('重新加载')
    expect(wrapper.find('[data-test="empty"]').exists()).toBe(false)
    meTimeApiMock.myTime.mockResolvedValue({
      records: [meItem()], total: 1, pageNum: 1, pageSize: 20
    })
    await wrapper.find('[data-test="retry-time"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-test="time-record"]')).toHaveLength(2)
  })

  it('shows the real empty state only after a successful empty read', async () => {
    meTimeApiMock.myTime.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 20 })
    directoryApiMock.myHosted.mockResolvedValue({
      records: [], nextCursor: null, hasMore: false, total: 0, typeCounts: {}, dateCounts: []
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="empty"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('下一段时间，还没写下。')
  })

  it('presets the owned tab from the deep link (?tab=owned)', async () => {
    routeMock.query = { tab: 'owned' }
    const wrapper = mountView()
    await flushPromises()

    const titles = wrapper.findAll('[data-test="time-record"] h3').map(node => node.text())
    expect(titles).toEqual(['我发起的共读'])
    expect(wrapper.find('[data-test="group-count"]').text()).toBe('1 段时间')
  })
})
