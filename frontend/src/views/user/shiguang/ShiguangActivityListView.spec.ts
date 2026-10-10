import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ShiguangActivityListView from './ShiguangActivityListView.vue'
import { useShiguangStore } from '@/stores/shiguang'

const directoryApiMock = vi.hoisted(() => ({
  directory: vi.fn(),
  myHosted: vi.fn(),
  applicationMetrics: vi.fn()
}))
const activityApiMock = vi.hoisted(() => ({
  activities: vi.fn(),
  companions: vi.fn(),
  activity: vi.fn()
}))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, string> }))

vi.mock('@/api/directory', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/directory')>()
  return { ...actual, directoryApi: directoryApiMock }
})
vi.mock('@/api/activity', () => ({ activityApi: activityApiMock }))
vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() }
}))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock, onBeforeRouteLeave: () => undefined }))

const openSpy = vi.hoisted(() => vi.fn())
const ActivityCreateDialogStub = {
  name: 'ActivityCreateDialog',
  methods: { open: openSpy },
  template: '<aside data-test="create-dialog" />'
}
const stubs = {
  'el-dialog': { props: ['modelValue'], template: '<aside v-if="modelValue"><slot /></aside>' },
  ActivityCreateDialog: ActivityCreateDialogStub,
  ShiguangArt: { props: ['scene'], template: '<span />' }
}

const item = (overrides: Record<string, unknown> = {}) => ({
  sourceType: 'ACTIVITY',
  sourceId: 1,
  groupId: 11,
  groupName: '慢读俱乐部',
  organizerUserId: 21,
  organizerName: '南栀',
  activityType: 'STUDY',
  title: '带一本书，交换一个下午。',
  description: '读到喜欢的地方，就停下来聊两句。',
  startTime: '2099-08-01T14:30:00',
  endTime: '2099-08-01T16:30:00',
  locationType: 'CAMPUS_AREA',
  venueId: null,
  venueName: null,
  spaceId: null,
  spaceName: null,
  areaCode: 'NORTH_CAMPUS',
  commitmentStatus: 'UNCONFIRMED',
  capacityTotal: 8,
  participantCount: 6,
  status: 'PUBLISHED',
  linkedActivityId: null,
  viewerApplicationStatus: null,
  viewerParticipationRole: null,
  ...overrides
})

const page = (records: unknown[], overrides: Record<string, unknown> = {}) => ({
  records,
  nextCursor: null,
  hasMore: false,
  total: records.length,
  typeCounts: { ACTIVITY: records.length, COMPANION: 0 },
  dateCounts: [{ date: '2099-08-01', count: records.length }],
  ...overrides
})

const mountView = () => mount(ShiguangActivityListView, { global: { stubs } })

describe('ShiguangActivityDirectoryView（V02 日期目录）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    routeMock.query = {}
    directoryApiMock.directory.mockResolvedValue(page([item()]))
  })

  it('reads the directory once and renders date groups with server truth counts', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(directoryApiMock.directory).toHaveBeenCalledTimes(1)
    expect(directoryApiMock.directory).toHaveBeenCalledWith(
      expect.objectContaining({ limit: 12, types: undefined })
    )
    expect(wrapper.find('.day-heading h2').text()).toContain('8 月 1 日')
    expect(wrapper.find('.day-heading > span').text()).toBe('1 场')
    expect(wrapper.find('.row-content h3').text()).toContain('带一本书')
    expect(wrapper.find('.row-capacity').text()).toContain('6')
    expect(wrapper.find('.directory-more').text()).toContain('已经看到全部活动')
  })

  it('switches the type tab by narrowing records without narrowing typeCounts', async () => {
    directoryApiMock.directory.mockResolvedValue(page([item()], {
      typeCounts: { ACTIVITY: 5, COMPANION: 3 },
      total: 8
    }))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="kind-ACTIVITY"]').text()).toContain('5')
    expect(wrapper.find('[data-test="kind-COMPANION"]').text()).toContain('3')
    await wrapper.find('[data-test="kind-ACTIVITY"]').trigger('click')
    await flushPromises()

    expect(directoryApiMock.directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ types: ['ACTIVITY'] })
    )
    expect(wrapper.find('.results-heading').text()).toContain('城市活动')
    expect(wrapper.find('.results-heading').text()).toContain('8')
  })

  it('forwards interest multi-select, area and availability as the same non-type filter set', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('input[type="checkbox"][value="SPORTS"]').setValue(true)
    await flushPromises()
    expect(directoryApiMock.directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ activityTypes: 'SPORTS' })
    )

    await wrapper.find('select[aria-label="活动区域"]').setValue('NORTH_CAMPUS')
    await flushPromises()
    expect(directoryApiMock.directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ areaCode: 'NORTH_CAMPUS', activityTypes: 'SPORTS' })
    )

    await wrapper.find('input[role="switch"]').setValue(true)
    await flushPromises()
    expect(directoryApiMock.directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ onlyAvailable: true })
    )
  })

  it('submits the search keyword and keeps it in the results heading', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('#directory-search-input').setValue('夜跑')
    await wrapper.find('.search-form').trigger('submit')
    await flushPromises()

    expect(directoryApiMock.directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '夜跑' })
    )
    expect(wrapper.find('.results-heading').text()).toContain('「夜跑」')
  })

  it('loads the next page through the opaque cursor and counts the remainder from total', async () => {
    directoryApiMock.directory
      .mockResolvedValueOnce(page([item({ sourceId: 1 })], {
        nextCursor: 'CUR-1', hasMore: true, total: 3
      }))
      .mockResolvedValueOnce(page([item({ sourceId: 2 }), item({ sourceId: 3 })]))
    const wrapper = mountView()
    await flushPromises()

    const more = wrapper.find('[data-test="load-more"]')
    expect(more.text()).toContain('还有 2 场')
    await more.trigger('click')
    await flushPromises()

    expect(directoryApiMock.directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ cursor: 'CUR-1' })
    )
    expect(wrapper.findAll('.activity-row')).toHaveLength(3)
    expect(wrapper.find('.directory-more').text()).toContain('已经看到全部活动')
  })

  it('labels viewer state combinations without collapsing one dimension into another', async () => {
    directoryApiMock.directory.mockResolvedValue(page([
      item({ sourceId: 1, viewerParticipationRole: 'ORGANIZER' }),
      item({ sourceId: 2, sourceType: 'COMPANION', viewerParticipationRole: 'PARTICIPANT' }),
      item({ sourceId: 3, viewerApplicationStatus: 'PENDING' }),
      item({ sourceId: 4, status: 'FULL' })
    ]))
    const wrapper = mountView()
    await flushPromises()

    const labels = wrapper.findAll('.row-action .status').map(node => node.text())
    expect(labels).toEqual(['你发起的', '你已加入', '等待回应', '名额已满'])
  })

  it('renders the retryable error state without erasing the filters', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('input[type="checkbox"][value="SPORTS"]').setValue(true)
    await flushPromises()

    directoryApiMock.directory.mockRejectedValueOnce(new Error('network down'))
    await wrapper.find('select[aria-label="活动时间"]').setValue('week')
    await flushPromises()
    expect(wrapper.find('[data-test="load-error"]').text()).toContain('重新加载')

    await wrapper.find('[data-test="retry-load"]').trigger('click')
    await flushPromises()
    expect(directoryApiMock.directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ activityTypes: 'SPORTS', toTime: expect.any(String) })
    )
    expect(wrapper.find('[data-test="load-error"]').exists()).toBe(false)
  })

  it('renders the real empty state after a successful empty page', async () => {
    directoryApiMock.directory.mockResolvedValue(page([]))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="empty"]').text()).toContain('还没找到这一场相遇')
  })

  it('consumes the linked-companion deep link through the create dialog', async () => {
    routeMock.query = { create: 'companion', activityId: '41' }
    activityApiMock.activity.mockResolvedValue(item({ sourceId: 41 }))
    const wrapper = mountView()
    await flushPromises()

    expect(activityApiMock.activity).toHaveBeenCalledWith(41)
    expect(wrapper.find('.filter-rail').exists()).toBe(true)
    expect(openSpy).toHaveBeenCalledWith('COMPANION_REQUEST', expect.objectContaining({ sourceId: 41 }))
  })

  it('keeps the restore point for returning to the directory', async () => {
    mountView()
    await flushPromises()
    const store = useShiguangStore()
    store.directoryScroll = 320
    expect(store.directoryScroll).toBe(320)
  })
})
