import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import VenueDetailView from './VenueDetailView.vue'
import { useReservationStore } from '@/stores/reservation'

const venueApiMock = vi.hoisted(() => ({
  detail: vi.fn(),
  spaces: vi.fn()
}))

const reservationApiMock = vi.hoisted(() => ({
  availability: vi.fn(),
  rules: vi.fn(),
  quote: vi.fn(),
  resourceAvailability: vi.fn()
}))

const funnelApiMock = vi.hoisted(() => ({
  favoriteStatus: vi.fn(),
  favoriteVenue: vi.fn(),
  cancelFavorite: vi.fn()
}))

const feedbackApiMock = vi.hoisted(() => ({
  venueReviewSummary: vi.fn(),
  venueReviews: vi.fn()
}))

const funnelStoreMock = vi.hoisted(() => ({
  sessionKey: 'detail-session',
  event: vi.fn((base: Record<string, unknown>) => ({
    sessionKey: 'detail-session',
    visitorId: 'visitor-1',
    sourceChannel: 'WEB',
    ...base
  })),
  track: vi.fn()
}))

const routerMock = vi.hoisted(() => ({ back: vi.fn(), push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({ params: { id: '2' } as Record<string, string>, query: {} as Record<string, string> }))
const permissionsMock = vi.hoisted(() => ({ granted: ['funnel:favorite:me'] as string[] }))
const elMessageMock = vi.hoisted(() => ({ success: vi.fn(), warning: vi.fn(), error: vi.fn() }))

vi.mock('@/api/venue', () => ({ venueApi: venueApiMock }))
vi.mock('@/api/reservation', () => ({ reservationApi: reservationApiMock }))
vi.mock('@/api/funnel', () => ({ funnelApi: funnelApiMock }))
vi.mock('@/api/feedback', () => ({ feedbackApi: feedbackApiMock }))
vi.mock('@/stores/funnel', () => ({ useFunnelStore: () => funnelStoreMock }))
vi.mock('@/stores/menu', () => ({ useMenuStore: () => ({ hasPermission: (permission: string) => permissionsMock.granted.includes(permission) }) }))
vi.mock('vue-router', () => ({ useRoute: () => routeMock, useRouter: () => routerMock }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return { ...actual, ElMessage: elMessageMock }
})

const pad = (value: number) => String(value).padStart(2, '0')
const dayValue = (offset: number) => {
  const day = new Date()
  day.setHours(0, 0, 0, 0)
  day.setDate(day.getDate() + offset)
  return `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`
}
const slotAt = (offset: number, startHour: number, endHour: number, overrides: Record<string, unknown> = {}) => ({
  spaceId: 10,
  startTime: `${dayValue(offset)}T${pad(startHour)}:00:00`,
  endTime: `${dayValue(offset)}T${pad(endHour)}:00:00`,
  status: 'AVAILABLE',
  label: '可约',
  price: 88,
  ...overrides
})

const VENUE = {
  id: 2,
  name: '星河体育馆',
  address: '东区 1 号',
  area: '东区',
  phone: '028-8533 8123',
  openTime: '09:00',
  closeTime: '22:00',
  description: '综合球馆',
  status: 'ACTIVE'
}

const spaceExclusive = (overrides: Record<string, unknown> = {}) => ({
  id: 10,
  venueId: 2,
  name: '一号场',
  type: 'BADMINTON',
  pricePerHour: 60,
  status: 'ACTIVE',
  facilityText: '空调、独立更衣',
  capacity: 12,
  ...overrides
})

const capacityResource = (remainingCapacity: number, overrides: Record<string, unknown> = {}) => ({
  spaceId: 10,
  rule: {
    id: 9,
    spaceId: 10,
    resourceCategory: 'STUDY_ROOM',
    bookingMode: 'CAPACITY',
    capacityLimit: 8,
    versionNo: 2,
    status: 'ACTIVE',
    seats: []
  },
  participantCount: 1,
  remainingCapacity,
  bookable: true,
  ...overrides
})

const seatResource = (seats: Array<Record<string, unknown>>, overrides: Record<string, unknown> = {}) => ({
  spaceId: 10,
  rule: {
    id: 10,
    spaceId: 10,
    resourceCategory: 'STUDY_ROOM',
    bookingMode: 'ASSIGNED_SEAT',
    versionNo: 3,
    status: 'ACTIVE',
    seats
  },
  participantCount: 1,
  remainingCapacity: 1,
  bookable: true,
  ...overrides
})

const EXCLUSIVE_RESOURCE = {
  spaceId: 10,
  rule: {
    id: 8,
    spaceId: 10,
    resourceCategory: 'SPORTS_FIELD',
    bookingMode: 'EXCLUSIVE',
    capacityLimit: 1,
    versionNo: 1,
    status: 'ACTIVE',
    seats: []
  },
  participantCount: 1,
  remainingCapacity: 1,
  bookable: true
}

const stubs = {
  'el-skeleton': { template: '<div class="skeleton-stub" />' },
  'router-link': {
    props: ['to'],
    template: '<a :href="typeof to === \'string\' ? to : (to && to.path) || \'#\'"><slot /></a>'
  }
}

const mountView = () => mount(VenueDetailView, { global: { stubs, mocks: { $router: routerMock } } })

const selectedSlotValues = () => useReservationStore().selectedSlots.map((slot) => slot.startTime.slice(11, 16))

describe('VenueDetailView booking flow', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    permissionsMock.granted = ['funnel:favorite:me']
    routeMock.params = { id: '2' }
    routeMock.query = {}
    venueApiMock.detail.mockResolvedValue(VENUE)
    venueApiMock.spaces.mockResolvedValue([spaceExclusive()])
    reservationApiMock.rules.mockResolvedValue({ maxSlotsPerBooking: 2, advanceDays: 7, freeCancelHours: 24, lockSeconds: 900 })
    reservationApiMock.availability.mockResolvedValue([slotAt(1, 10, 11)])
    reservationApiMock.quote.mockResolvedValue({
      originalAmount: 88,
      memberDiscountAmount: 0,
      couponDiscountAmount: 0,
      activityDiscountAmount: 0,
      payableAmount: 88
    })
    reservationApiMock.resourceAvailability.mockResolvedValue(EXCLUSIVE_RESOURCE)
    funnelApiMock.favoriteStatus.mockResolvedValue({ favorite: false })
    funnelApiMock.favoriteVenue.mockResolvedValue({ id: 9, venueId: 2, status: 'ACTIVE' })
    funnelApiMock.cancelFavorite.mockResolvedValue(undefined)
    funnelStoreMock.track.mockResolvedValue(undefined)
    feedbackApiMock.venueReviewSummary.mockResolvedValue({ venueId: 2, averageRating: 4.6, reviewCount: 3, tagSummary: [] })
    feedbackApiMock.venueReviews.mockResolvedValue({
      records: [
        {
          id: 12,
          venueId: 2,
          overallRating: 5,
          tagsJson: '["FRIENDLY_SERVICE"]',
          contentSanitized: '服务响应很快',
          contentText: 'raw-private-13800000000',
          submittedAt: '2026-07-06T09:30:00'
        }
      ],
      total: 3,
      pageNum: 1,
      pageSize: 5
    })
  })

  it('loads venue detail, spaces and rules before the first availability request', async () => {
    let resolveDetail!: (venue: Record<string, unknown>) => void
    venueApiMock.detail.mockReturnValue(new Promise((resolve) => { resolveDetail = resolve }))

    const wrapper = mountView()
    await flushPromises()

    const loading = wrapper.get('.booking-loading')
    expect(loading.attributes('role')).toBe('status')
    expect(wrapper.find('[data-test="venue-title"]').exists()).toBe(false)
    expect(reservationApiMock.availability).not.toHaveBeenCalled()

    resolveDetail(VENUE)
    await flushPromises()

    expect(wrapper.find('.booking-loading').exists()).toBe(false)
    expect(venueApiMock.detail).toHaveBeenCalledWith(2)
    expect(venueApiMock.spaces).toHaveBeenCalledWith(2)
    expect(reservationApiMock.rules).toHaveBeenCalledTimes(1)
    expect(reservationApiMock.availability).toHaveBeenCalledWith({ spaceId: 10, date: dayValue(0) })
    expect(wrapper.get('[data-test="venue-title"]').text()).toBe('星河体育馆')
    expect(wrapper.get('.eyebrow').text()).toBe('羽毛球 · 整场预约')
    expect(wrapper.get('.hero-meta').text()).toContain('028-8533****')
    expect(wrapper.get('.hero-meta').text()).toContain('09:00 - 22:00')
    expect(wrapper.get('.hero-meta').text()).toContain('营业中')
    expect(wrapper.get('[data-test="space-option-10"]').text()).toContain('空调、独立更衣 · 可容纳 12 人')
    expect(wrapper.get('[data-test="space-option-10"]').text()).toContain('¥60 / 小时')
    expect(wrapper.get('[data-test="space-option-10"]').attributes('aria-pressed')).toBe('true')

    wrapper.unmount()
  })

  it('renders the page-level error band and recovers through the reload action', async () => {
    venueApiMock.detail.mockRejectedValue({ response: { data: { message: '场馆服务暂时不可用' } } })
    const wrapper = mountView()
    await flushPromises()

    const band = wrapper.get('section[role="alert"]')
    expect(band.text()).toContain('场馆信息暂时无法加载。')
    expect(band.text()).toContain('场馆服务暂时不可用')
    expect(wrapper.find('[data-test="venue-title"]').exists()).toBe(false)
    expect(wrapper.find('.slot-grid').exists()).toBe(false)

    await band.findAll('button')[0].trigger('click')
    expect(routerMock.back).toHaveBeenCalledTimes(1)

    venueApiMock.detail.mockResolvedValue(VENUE)
    await band.get('.loading-retry').trigger('click')
    await flushPromises()

    expect(venueApiMock.detail).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-test="venue-title"]').text()).toBe('星河体育馆')

    wrapper.unmount()
  })

  it('leaves the date track empty and explains instead of assuming default limits when rules fail', async () => {
    reservationApiMock.rules.mockRejectedValue({ response: { data: { message: '预约规则服务暂不可用' } } })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('.date-button')).toHaveLength(0)
    expect(wrapper.find('[data-test="reservation-date-input"]').exists()).toBe(false)
    expect(wrapper.get('.help-line').text()).toContain('预约规则服务暂不可用')
    expect(wrapper.get('.help-line').text()).toContain('提交时服务端仍会校验可约范围')
    expect(wrapper.text()).not.toContain('单次最多可选择')
    expect(wrapper.text()).not.toMatch(/可提前 \d+ 天预约/)
    // 规则失败不阻断真实数据链路：时段仍按服务端结果渲染
    expect(wrapper.findAll('.slot')).toHaveLength(1)
    expect(wrapper.find('.slot-grid').exists()).toBe(true)

    wrapper.unmount()
  })

  it('builds the date track from the server advance window and opens the custom input past six days', async () => {
    const wide = mountView()
    await flushPromises()

    const buttons = wide.findAll('.date-button')
    expect(buttons).toHaveLength(7)
    expect(buttons[0].text()).toContain('今天')
    expect(buttons[1].text()).toContain('明天')
    expect(buttons[0].attributes('data-test')).toBe(`date-${dayValue(0)}`)
    expect(buttons[0].attributes('aria-pressed')).toBe('true')
    expect(buttons[6].attributes('data-test')).toBe(`date-${dayValue(6)}`)
    expect(wide.find(`[data-test="date-${dayValue(7)}"]`).exists()).toBe(false)

    const custom = wide.get('[data-test="reservation-date-input"]')
    expect(custom.attributes('min')).toBe(dayValue(0))
    expect(custom.attributes('max')).toBe(dayValue(7))
    expect(wide.get('.more-dates').text()).toContain(`可预约 ${dayValue(0)} 至 ${dayValue(7)}`)
    expect(wide.get('.help-line').text()).toContain('单次最多可选择 2 个时段，可提前 7 天预约。')
    expect(wide.get('.visit-guide').text()).toContain('星河体育馆的营业时间 09:00 - 22:00')
    expect(wide.get('.visit-guide').text()).toContain(`可预约日期为 ${dayValue(0)} 至 ${dayValue(7)}`)
    // 规则异步到齐后取消时限必须真的出现在到场须知里
    expect(wide.get('.visit-guide').text()).toContain('开始前 24 小时外可取消')

    // 自定义日期必须真的重新取该日时段，而不是被「与当前值相同」的判断挡掉
    const availabilityCallsBefore = reservationApiMock.availability.mock.calls.length
    custom.setValue(dayValue(5))
    await custom.trigger('change')
    await flushPromises()
    expect(reservationApiMock.availability.mock.calls.length).toBeGreaterThan(availabilityCallsBefore)
    expect(reservationApiMock.availability).toHaveBeenLastCalledWith({ spaceId: expect.anything(), date: dayValue(5) })
    wide.unmount()

    reservationApiMock.rules.mockResolvedValue({ maxSlotsPerBooking: 4, advanceDays: 3, freeCancelHours: 0, lockSeconds: 900 })
    const narrow = mountView()
    await flushPromises()

    expect(narrow.findAll('.date-button')).toHaveLength(4)
    expect(narrow.find('[data-test="reservation-date-input"]').exists()).toBe(false)
    expect(narrow.get('.help-line').text()).toContain('单次最多可选择 4 个时段，可提前 3 天预约。')
    expect(narrow.get('.visit-guide').text()).toContain(`可预约日期为 ${dayValue(0)} 至 ${dayValue(3)}`)
    // 说明：更多日期输入框只承载 min/max；选择日期不会触发时段重载（v-model 先改写 date，
    // pickDate 随即因 value === date 提前返回），因此这里不对其断言重载行为。
    narrow.unmount()
  })

  it('reloads availability against the clicked day of the track', async () => {
    const wrapper = mountView()
    await flushPromises()
    reservationApiMock.availability.mockResolvedValue([slotAt(2, 14, 15)])

    await wrapper.get(`[data-test="date-${dayValue(2)}"]`).trigger('click')
    await flushPromises()

    expect(reservationApiMock.availability).toHaveBeenLastCalledWith({ spaceId: 10, date: dayValue(2) })
    const picked = wrapper.get(`[data-test="date-${dayValue(2)}"]`)
    expect(picked.classes()).toContain('is-selected')
    expect(picked.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('.month-label').text()).toBe(`${dayValue(2).slice(0, 4)} / ${dayValue(2).slice(5, 7)}`)
    expect(wrapper.findAll('.slot').map((node) => node.attributes('data-test')))
      .toEqual([`slot-${dayValue(2)}T14:00:00`])
    expect(wrapper.get(`[data-test="date-${dayValue(0)}"]`).classes()).not.toContain('is-selected')

    wrapper.unmount()
  })

  it('reloads availability against the newly selected space and never reuses the first space result', async () => {
    venueApiMock.spaces.mockResolvedValue([
      spaceExclusive(),
      spaceExclusive({ id: 11, name: '二号场', pricePerHour: 45 })
    ])
    reservationApiMock.availability.mockImplementation(({ spaceId }: { spaceId: number }) =>
      Promise.resolve(spaceId === 11 ? [slotAt(1, 16, 17, { spaceId: 11, price: 45 })] : [slotAt(1, 10, 11)])
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).text()).toContain('¥88 / 小时')
    const store = useReservationStore()
    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await flushPromises()
    expect(store.selectedSlots).toHaveLength(1)

    reservationApiMock.availability.mockClear()
    await wrapper.get('[data-test="space-option-11"]').trigger('click')
    await flushPromises()

    expect(reservationApiMock.availability).toHaveBeenCalledWith({ spaceId: 11, date: dayValue(0) })
    const nextSpaceSlot = wrapper.get(`[data-test="slot-${dayValue(1)}T16:00:00"]`)
    expect(nextSpaceSlot.text()).toContain('¥45 / 小时')
    expect(wrapper.find(`[data-test="slot-${dayValue(1)}T10:00:00"]`).exists()).toBe(false)
    expect(store.selectedSlots).toHaveLength(0)
    expect(store.spaceId).toBeUndefined()
    expect(wrapper.get('[data-test="space-option-11"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-test="space-option-10"]').attributes('aria-pressed')).toBe('false')

    wrapper.unmount()
  })

  it('renders server prices for bookable slots and the server reason for every blocked state', async () => {
    reservationApiMock.availability.mockResolvedValue([
      slotAt(1, 10, 11, { price: 88 }),
      slotAt(1, 11, 12, { status: 'RESERVED', label: '已约', reason: '已被预约' }),
      slotAt(1, 12, 13, { status: 'UNAVAILABLE', label: '场地维护', reason: undefined }),
      slotAt(1, 13, 14, { status: 'MAINTENANCE', label: '', reason: undefined, price: 0 }),
      slotAt(-3, 10, 11)
    ])
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('.slot-caption').text()).toContain('每段 1 小时')
    expect(wrapper.get('.slot-caption').text()).toContain('可约 1 段')

    const bookable = wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`)
    expect(bookable.text()).toContain('¥88 / 小时')
    expect(bookable.attributes('disabled')).toBeUndefined()
    expect(bookable.attributes('aria-pressed')).toBe('false')

    const reserved = wrapper.get(`[data-test="slot-${dayValue(1)}T11:00:00"]`)
    expect(reserved.text()).toContain('已被预约')
    expect(reserved.attributes('disabled')).toBeDefined()

    const maintained = wrapper.get(`[data-test="slot-${dayValue(1)}T12:00:00"]`)
    expect(maintained.text()).toContain('场地维护')
    expect(maintained.attributes('disabled')).toBeDefined()

    const blocked = wrapper.get(`[data-test="slot-${dayValue(1)}T13:00:00"]`)
    expect(blocked.text()).toContain('不可预约')

    const expired = wrapper.get(`[data-test="slot-${dayValue(-3)}T10:00:00"]`)
    expect(expired.text()).toContain('已过时')
    expect(expired.attributes('disabled')).toBeDefined()
    expect(expired.attributes('aria-label')).toBe('10:00 至 11:00 已过时')

    wrapper.unmount()
  })

  it('refuses a selection beyond the server max slots per booking', async () => {
    reservationApiMock.rules.mockResolvedValue({ maxSlotsPerBooking: 2, advanceDays: 7, freeCancelHours: 24, lockSeconds: 900 })
    reservationApiMock.availability.mockResolvedValue([
      slotAt(1, 10, 11),
      slotAt(1, 11, 12),
      slotAt(1, 12, 13)
    ])
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await wrapper.get(`[data-test="slot-${dayValue(1)}T11:00:00"]`).trigger('click')
    await flushPromises()
    expect(selectedSlotValues()).toEqual(['10:00', '11:00'])

    const third = wrapper.get(`[data-test="slot-${dayValue(1)}T12:00:00"]`)
    await third.trigger('click')
    await flushPromises()

    expect(elMessageMock.warning).toHaveBeenCalledWith('单次最多可选择 2 个时段')
    expect(selectedSlotValues()).toEqual(['10:00', '11:00'])
    expect(third.attributes('aria-pressed')).toBe('false')
    expect(wrapper.get('.booking-summary').text()).toContain('10:00–12:00')

    wrapper.unmount()
  })

  it('refuses a non-contiguous slot selection', async () => {
    reservationApiMock.rules.mockResolvedValue({ maxSlotsPerBooking: 4, advanceDays: 7, freeCancelHours: 24, lockSeconds: 900 })
    reservationApiMock.availability.mockResolvedValue([
      slotAt(1, 10, 11),
      slotAt(1, 13, 14)
    ])
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await flushPromises()
    await wrapper.get(`[data-test="slot-${dayValue(1)}T13:00:00"]`).trigger('click')
    await flushPromises()

    expect(elMessageMock.warning).toHaveBeenCalledWith('请选择连续时段')
    expect(selectedSlotValues()).toEqual(['10:00'])
    expect(reservationApiMock.quote).toHaveBeenCalledTimes(1)
    expect(reservationApiMock.quote).toHaveBeenLastCalledWith(expect.objectContaining({
      startTime: `${dayValue(1)}T10:00:00`,
      endTime: `${dayValue(1)}T11:00:00`
    }))

    wrapper.unmount()
  })

  it('keeps the newest availability response when an earlier request resolves last', async () => {
    const wrapper = mountView()
    await flushPromises()

    let resolveStale!: (slots: Array<Record<string, unknown>>) => void
    let resolveLatest!: (slots: Array<Record<string, unknown>>) => void
    const staleRequest = new Promise<Array<Record<string, unknown>>>((resolve) => { resolveStale = resolve })
    const latestRequest = new Promise<Array<Record<string, unknown>>>((resolve) => { resolveLatest = resolve })
    reservationApiMock.availability
      .mockImplementationOnce(() => staleRequest)
      .mockImplementationOnce(() => latestRequest)
      .mockImplementation(({ spaceId, date }: { spaceId: number; date: string }) =>
        Promise.resolve([slotAt(1, 9, 10, { spaceId, date })])
      )

    await wrapper.get(`[data-test="date-${dayValue(1)}"]`).trigger('click')
    await wrapper.get(`[data-test="date-${dayValue(2)}"]`).trigger('click')
    await flushPromises()

    const latestSlot = slotAt(2, 20, 21)
    resolveLatest([latestSlot])
    await flushPromises()
    expect(wrapper.find(`[data-test="slot-${dayValue(2)}T20:00:00"]`).exists()).toBe(true)

    resolveStale([slotAt(1, 8, 9)])
    await flushPromises()

    expect(wrapper.findAll('.slot').map((node) => node.attributes('data-test'))).toEqual([`slot-${dayValue(2)}T20:00:00`])
    expect(wrapper.find(`[data-test="slot-${dayValue(1)}T08:00:00"]`).exists()).toBe(false)
    expect(wrapper.find('.state-message[role="status"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the slot error band with a reload action instead of an empty day', async () => {
    reservationApiMock.availability.mockRejectedValue({ response: { data: { message: '时段服务超时' } } })
    const wrapper = mountView()
    await flushPromises()

    const band = wrapper.get('.schedule .state-message[role="alert"]')
    expect(band.text()).toContain('时段暂时没有加载出来。')
    expect(band.text()).toContain('时段服务超时')
    expect(band.text()).toContain('你选择的场地和日期仍然保留')
    expect(band.text()).not.toContain('这一天没有可展示的时段')
    expect(wrapper.findAll('.slot')).toHaveLength(0)
    expect(useReservationStore().selectedSlots).toHaveLength(0)

    reservationApiMock.availability.mockResolvedValue([slotAt(1, 10, 11)])
    await band.get('[data-test="reload-slots"]').trigger('click')
    await flushPromises()

    expect(reservationApiMock.availability).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.slot')).toHaveLength(1)
    expect(wrapper.find('.schedule .state-message[role="alert"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows an honest empty day and jumps to the next day of the track', async () => {
    reservationApiMock.availability.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('.schedule .state-message').text()).toContain('这一天没有可展示的时段。')
    expect(wrapper.find('.slot-grid').exists()).toBe(false)
    expect(wrapper.findAll('.slot')).toHaveLength(0)
    expect(wrapper.get('[data-test="next-day"]').text()).toContain('看看下一天')

    await wrapper.get('[data-test="next-day"]').trigger('click')
    await flushPromises()

    expect(reservationApiMock.availability).toHaveBeenLastCalledWith({ spaceId: 10, date: dayValue(1) })

    wrapper.unmount()
  })

  it('prices the selection on the server and renders the server payable amount only', async () => {
    reservationApiMock.availability.mockResolvedValue([
      slotAt(1, 10, 11, { price: 60 }),
      slotAt(1, 11, 12, { price: 60 })
    ])
    reservationApiMock.quote.mockResolvedValue({
      originalAmount: 150,
      memberDiscountAmount: 18,
      couponDiscountAmount: 0,
      activityDiscountAmount: 0,
      payableAmount: 132,
      pricingVersion: 'B3_QUOTE_V1'
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('.booking-summary .cost-total strong').text()).toBe('—')
    expect(wrapper.get('.booking-summary .summary-duration').text()).toContain('从左侧选择时段')

    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await wrapper.get(`[data-test="slot-${dayValue(1)}T11:00:00"]`).trigger('click')
    await flushPromises()

    expect(reservationApiMock.quote).toHaveBeenLastCalledWith({
      spaceId: 10,
      startTime: `${dayValue(1)}T10:00:00`,
      endTime: `${dayValue(1)}T12:00:00`,
      slots: expect.any(Array)
    })
    const summary = wrapper.get('.booking-summary')
    expect(summary.text()).toContain('¥132')
    expect(summary.text()).toContain('¥150')
    expect(summary.text()).toContain('时长 2 小时')
    // 单价 × 时长的客户端乘法不允许出现在摘要里：120 只可能来自前端自算
    expect(summary.text()).not.toContain('120')
    expect(wrapper.get('.mobile-booking').text()).toContain('¥132')
    expect(wrapper.get('.summary-time').text()).toBe('10:00–12:00')
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })

  it('falls back to a dash and the confirmation-page note when the server quote fails', async () => {
    reservationApiMock.quote.mockRejectedValue({ response: { data: { message: '计价服务暂不可用' } } })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await flushPromises()

    expect(wrapper.get('.booking-summary .cost-total strong').text()).toBe('—')
    expect(wrapper.get('.booking-summary .fee-note').text()).toContain('计价服务暂不可用')
    expect(wrapper.get('.booking-summary .fee-note').text()).toContain('最终费用在确认页核对')
    expect(wrapper.get('.booking-summary').text()).not.toContain('¥0')
    expect(wrapper.get('.mobile-booking').text()).toContain('待选择')
    // 报价失败不阻断继续确认
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })

  it('limits the party size to the live remaining capacity of a CAPACITY space', async () => {
    venueApiMock.spaces.mockResolvedValue([
      spaceExclusive({
        type: 'STUDY_ROOM',
        resourceRule: {
          resourceCategory: 'STUDY_ROOM',
          bookingMode: 'CAPACITY',
          capacityLimit: 8,
          versionNo: 2,
          status: 'ACTIVE',
          seats: []
        }
      })
    ])
    reservationApiMock.availability.mockResolvedValue([slotAt(1, 10, 11), slotAt(1, 11, 12)])
    reservationApiMock.resourceAvailability.mockResolvedValue(capacityResource(3))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.resource-section').exists()).toBe(false)
    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await flushPromises()

    expect(reservationApiMock.resourceAvailability).toHaveBeenCalledWith(expect.objectContaining({
      spaceId: 10,
      startTime: `${dayValue(1)}T10:00:00`,
      endTime: `${dayValue(1)}T11:00:00`
    }))
    const capacity = wrapper.get('.resource-section')
    expect(capacity.text()).toContain('所选时段余量 3 人')
    expect(capacity.get('[data-test="people-minus"]').attributes('disabled')).toBeDefined()

    await capacity.get('[data-test="people-plus"]').trigger('click')
    await capacity.get('[data-test="people-plus"]').trigger('click')
    await flushPromises()
    expect(capacity.get('output').text()).toBe('3')
    expect(capacity.get('[data-test="people-plus"]').attributes('disabled')).toBeDefined()
    expect(reservationApiMock.quote).toHaveBeenLastCalledWith(expect.objectContaining({ participantCount: 3 }))
    expect(wrapper.get('.booking-summary').text()).toContain('3 人同行')

    reservationApiMock.resourceAvailability.mockResolvedValue(capacityResource(1))
    await wrapper.get(`[data-test="slot-${dayValue(1)}T11:00:00"]`).trigger('click')
    await flushPromises()

    expect(wrapper.get('.resource-section output').text()).toBe('1')
    expect(wrapper.get('.resource-section [data-test="people-plus"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.booking-summary').text()).toContain('1 人同行')
    expect(reservationApiMock.quote).toHaveBeenLastCalledWith(expect.objectContaining({ participantCount: 1 }))

    wrapper.unmount()
  })

  it('disables taken seats, holds the quote until a seat is chosen and drops a seat grabbed mid-flight', async () => {
    venueApiMock.spaces.mockResolvedValue([
      spaceExclusive({
        type: 'STUDY_ROOM',
        resourceRule: {
          resourceCategory: 'STUDY_ROOM',
          bookingMode: 'ASSIGNED_SEAT',
          versionNo: 3,
          status: 'ACTIVE',
          seats: []
        }
      })
    ])
    reservationApiMock.availability.mockResolvedValue([slotAt(1, 10, 11), slotAt(1, 11, 12)])
    let seatSnapshot: Array<Record<string, unknown>> = [
      { id: 501, seatCode: 'A-01', seatLabel: 'A区01座', status: 'ACTIVE', available: true },
      { id: 502, seatCode: 'A-02', seatLabel: 'A区02座', status: 'ACTIVE', available: false }
    ]
    reservationApiMock.resourceAvailability.mockImplementation(() => Promise.resolve(seatResource(seatSnapshot)))
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await flushPromises()

    const seatArea = wrapper.get('.seat-plan')
    expect(seatArea.text()).toContain('座位分布示意 · 不代表现场实际朝向')
    expect(seatArea.get('[data-test="seat-501"]').text()).toBe('A区01座')
    expect(seatArea.get('[data-test="seat-502"]').attributes('disabled')).toBeDefined()
    expect(seatArea.get('[data-test="seat-502"]').attributes('aria-label')).toBe('座位 A区02座 已占用')
    expect(reservationApiMock.quote).not.toHaveBeenCalled()
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').text()).toContain('请先选择座位')
    expect(wrapper.get('[data-test="mobile-continue"]').attributes('disabled')).toBeDefined()

    await seatArea.get('[data-test="seat-501"]').trigger('click')
    await flushPromises()

    expect(reservationApiMock.quote).toHaveBeenLastCalledWith(expect.objectContaining({ seatIds: [501] }))
    expect(wrapper.get('.booking-summary').text()).toContain('座位 A区01座')
    expect(wrapper.get('.booking-summary').text()).toContain('时长 1 小时')
    expect(wrapper.get('.seat-plan [data-test="seat-501"]').classes()).toContain('is-selected')
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeUndefined()

    // 扩展时段时服务端回报这个座位刚刚被占用
    seatSnapshot = [
      { id: 501, seatCode: 'A-01', seatLabel: 'A区01座', status: 'ACTIVE', available: false },
      { id: 502, seatCode: 'A-02', seatLabel: 'A区02座', status: 'ACTIVE', available: true }
    ]
    await wrapper.get(`[data-test="slot-${dayValue(1)}T11:00:00"]`).trigger('click')
    await flushPromises()

    expect(wrapper.get('.seat-plan [data-test="seat-501"]').classes()).not.toContain('is-selected')
    expect(wrapper.get('.seat-plan [data-test="seat-501"]').attributes('aria-pressed')).toBe('false')
    expect(wrapper.get('.seat-plan [data-test="seat-501"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.resource-section').text()).toContain('选择一个空位')
    // 静默纠正不可接受：被撤掉的座位必须有可见解释，且它是状态提示而非请求失败告警
    const notice = wrapper.get('[data-test="seat-adjusted"]')
    expect(notice.text()).toContain('座位刚刚被占用')
    expect(notice.attributes('role')).toBe('status')
    expect(wrapper.get('.booking-summary .summary-duration').text()).toContain('尚未选择座位')
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('gives assigned-seat spaces an explicit people stepper that stays in lockstep with the chosen seats', async () => {
    venueApiMock.spaces.mockResolvedValue([
      spaceExclusive({
        type: 'STUDY_ROOM',
        resourceRule: {
          resourceCategory: 'STUDY_ROOM',
          bookingMode: 'ASSIGNED_SEAT',
          versionNo: 3,
          status: 'ACTIVE',
          seats: []
        }
      })
    ])
    reservationApiMock.availability.mockResolvedValue([slotAt(1, 10, 11)])
    const twoFree = [
      { id: 501, seatCode: 'A-01', seatLabel: 'A区01座', status: 'ACTIVE', available: true },
      { id: 502, seatCode: 'A-02', seatLabel: 'A区02座', status: 'ACTIVE', available: true }
    ]
    reservationApiMock.resourceAvailability.mockImplementation(() => Promise.resolve(seatResource(twoFree, { remainingCapacity: 2 })))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await flushPromises()

    // 指定座位场地也要有明确的人数入口
    const stepper = wrapper.get('.seat-people .stepper')
    expect(stepper.text()).toContain('0')
    expect(wrapper.get('[data-test="seat-people-minus"]').attributes('disabled')).toBeDefined()

    // 加一人 = 自动补一个空位，且发给服务端的 participantCount 与 seatIds 数量一致
    await wrapper.get('[data-test="seat-people-plus"]').trigger('click')
    await flushPromises()
    expect(stepper.text()).toContain('1')
    expect(reservationApiMock.quote).toHaveBeenLastCalledWith(expect.objectContaining({ participantCount: 1, seatIds: [501] }))
    expect(wrapper.get('.booking-summary').text()).toContain('座位 A区01座')

    await wrapper.get('[data-test="seat-people-plus"]').trigger('click')
    await flushPromises()
    expect(stepper.text()).toContain('2')
    expect(reservationApiMock.quote).toHaveBeenLastCalledWith(expect.objectContaining({ participantCount: 2, seatIds: [501, 502] }))
    expect(wrapper.findAll('.seat.is-selected')).toHaveLength(2)
    // 空位用尽：禁用加号而不是给出错误
    expect(wrapper.get('[data-test="seat-people-plus"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test="seat-people-minus"]').trigger('click')
    await flushPromises()
    expect(stepper.text()).toContain('1')
    expect(reservationApiMock.quote).toHaveBeenLastCalledWith(expect.objectContaining({ participantCount: 1, seatIds: [501] }))
    wrapper.unmount()
  })

  it('gates the favorite control on funnel:favorite:me and degrades a favorite failure without blocking booking', async () => {
    permissionsMock.granted = []
    const anonymous = mountView()
    await flushPromises()

    expect(anonymous.find('[data-test="favorite-toggle"]').exists()).toBe(false)
    expect(funnelApiMock.favoriteStatus).not.toHaveBeenCalled()
    anonymous.unmount()

    permissionsMock.granted = ['funnel:favorite:me']
    funnelApiMock.favoriteStatus.mockResolvedValue({ favorite: true })
    const wrapper = mountView()
    await flushPromises()

    expect(funnelApiMock.favoriteStatus).toHaveBeenCalledWith(2)
    const toggle = wrapper.get('[data-test="favorite-toggle"]')
    expect(toggle.attributes('aria-pressed')).toBe('true')
    expect(toggle.text()).toContain('已收藏')

    funnelApiMock.cancelFavorite.mockRejectedValue({ response: { data: { message: '收藏服务暂不可用' } } })
    await toggle.trigger('click')
    await flushPromises()

    expect(elMessageMock.error).toHaveBeenCalledWith('收藏服务暂不可用')
    expect(wrapper.get('[data-test="favorite-toggle"]').attributes('aria-pressed')).toBe('true')

    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await flushPromises()
    expect(selectedSlotValues()).toEqual(['10:00'])
    expect(wrapper.get('[data-test="detail-loc-card"]').text()).toContain('位置示意 · 非真实地图')
    expect(wrapper.findAll('[data-test="copy-address"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-test="navigate-venue"]')).toHaveLength(1)
    expect(wrapper.get('[data-test="navigate-venue"]').attributes('href')).toBe('https://map.baidu.com/search/' + encodeURIComponent('东区 1 号'))

    wrapper.unmount()
  })

  it('keeps booking usable and shows a readable note when the review service fails', async () => {
    feedbackApiMock.venueReviewSummary.mockRejectedValue(new Error('评价服务暂不可用'))
    feedbackApiMock.venueReviews.mockRejectedValue(new Error('评价服务暂不可用'))
    const wrapper = mountView()
    await flushPromises()

    const band = wrapper.get('.venue-about .state-message')
    expect(band.text()).toContain('评价暂时无法加载。')
    expect(band.text()).toContain('不影响选择和预约')
    expect(wrapper.find('.review-list').exists()).toBe(false)
    expect(wrapper.get('[data-test="write-review-link"]').attributes('href')).toBe('/me/feedback')

    feedbackApiMock.venueReviewSummary.mockResolvedValue({ venueId: 2, averageRating: 4.8, reviewCount: 2 })
    feedbackApiMock.venueReviews.mockResolvedValue({
      records: [{ id: 13, venueId: 2, overallRating: 4, contentSanitized: '场地很干净', submittedAt: '2026-07-06T09:30:00' }],
      total: 2,
      pageNum: 1,
      pageSize: 5
    })
    await wrapper.get('.venue-about .state-message button').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.review-row')).toHaveLength(1)
    expect(wrapper.get('.review-row').text()).toContain('场地很干净')
    expect(wrapper.get('.review-heading').text()).toContain('4.8 · 2 条')

    wrapper.unmount()
  })

  it('renders only sanitized review content and the honest empty state', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(feedbackApiMock.venueReviewSummary).toHaveBeenCalledWith(2)
    expect(feedbackApiMock.venueReviews).toHaveBeenCalledWith(2, { pageNum: 1, pageSize: 5 })
    expect(wrapper.get('.review-row').text()).toContain('服务响应很快')
    expect(wrapper.get('.review-row').text()).toContain('评分 5.0 / 5')
    expect(wrapper.text()).not.toContain('raw-private-13800000000')
    expect(wrapper.get('.review-heading').text()).toContain('4.6 · 3 条')

    feedbackApiMock.venueReviewSummary.mockResolvedValue({ venueId: 2, averageRating: 0, reviewCount: 0 })
    feedbackApiMock.venueReviews.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 5 })
    const empty = mountView()
    await flushPromises()

    expect(empty.find('.review-list').exists()).toBe(false)
    expect(empty.get('.venue-about').text()).toContain('暂无公开评价——去过的话，欢迎在行程完成后留下第一条。')
    expect(empty.find('.review-heading .muted').exists()).toBe(false)

    wrapper.unmount()
    empty.unmount()
  })

  it('tracks venue views, slot selections and no-sellable dropoffs', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'VENUE_DETAIL_VIEWED',
      eventStage: 'DETAIL',
      venueId: 2,
      pagePath: '/venues/2'
    }))

    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await flushPromises()

    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'SLOT_SELECTED',
      eventStage: 'DETAIL',
      venueId: 2,
      spaceId: 10,
      pagePath: '/venues/2',
      metadata: expect.objectContaining({ price: 88 })
    }))

    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await flushPromises()
    expect(funnelStoreMock.track).toHaveBeenCalledTimes(2)

    reservationApiMock.availability.mockResolvedValue([slotAt(1, 11, 12, { status: 'RESERVED', label: '已约' })])
    funnelStoreMock.track.mockClear()
    await wrapper.get(`[data-test="date-${dayValue(2)}"]`).trigger('click')
    await flushPromises()

    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'DROPOFF_RECORDED',
      eventStage: 'DETAIL',
      venueId: 2,
      spaceId: 10,
      dropoffReasonCode: 'NO_SELLABLE_SLOT',
      pagePath: '/venues/2'
    }))

    wrapper.unmount()
  })

  it('honors a future ?date= deep link and ignores malformed or expired ones', async () => {
    routeMock.query = { date: dayValue(3) }
    const linked = mountView()
    await flushPromises()

    expect(reservationApiMock.availability).toHaveBeenLastCalledWith({ spaceId: 10, date: dayValue(3) })
    expect(linked.get(`[data-test="date-${dayValue(3)}"]`).classes()).toContain('is-selected')
    linked.unmount()

    routeMock.query = { date: dayValue(-2) }
    const expired = mountView()
    await flushPromises()
    expect(reservationApiMock.availability).toHaveBeenLastCalledWith({ spaceId: 10, date: dayValue(0) })
    expired.unmount()

    routeMock.query = { date: '2026-7-4' }
    const malformed = mountView()
    await flushPromises()
    expect(reservationApiMock.availability).toHaveBeenLastCalledWith({ spaceId: 10, date: dayValue(0) })
    malformed.unmount()

    reservationApiMock.rules.mockRejectedValue(new Error('预约规则暂时无法读取'))
    routeMock.query = { date: dayValue(30) }
    const ruleless = mountView()
    await flushPromises()
    expect(reservationApiMock.availability).toHaveBeenLastCalledWith({ spaceId: 10, date: dayValue(30) })
    expect(ruleless.findAll('.date-button')).toHaveLength(0)
    expect(ruleless.get('.help-line').text()).toContain('预约规则暂时无法读取')
    ruleless.unmount()
  })

  it('returns to the space and non-today date carried by the confirmation page', async () => {
    venueApiMock.spaces.mockResolvedValue([
      spaceExclusive(),
      spaceExclusive({ id: 11, name: '二号场', pricePerHour: 45 })
    ])
    reservationApiMock.availability.mockImplementation(({ spaceId }: { spaceId: number }) =>
      Promise.resolve([slotAt(3, 10, 11, { spaceId })])
    )
    window.sessionStorage.setItem('shiguang-booking-draft', JSON.stringify({
      slots: [slotAt(3, 10, 11, { spaceId: 11 })],
      venueId: 2,
      venueName: '星河体育馆',
      spaceId: 11,
      spaceName: '二号场',
      spaceType: 'BADMINTON',
      participantCount: 1,
      seatIds: []
    }))
    // 确认页返回时带上的 date + spaceId：场地要优先于草稿记忆，日期不能被重置成今天
    routeMock.query = { date: dayValue(3), spaceId: '11' }

    const wrapper = mountView()
    await flushPromises()

    expect(reservationApiMock.availability).toHaveBeenLastCalledWith({ spaceId: 11, date: dayValue(3) })
    expect(wrapper.get('[data-test="space-option-11"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get(`[data-test="slot-${dayValue(3)}T10:00:00"]`).attributes('aria-pressed')).toBe('true')
    expect(useReservationStore().selectedSlots).toHaveLength(1)
    expect(wrapper.find('[data-test="selection-restore-notice"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('says the carried selection is gone instead of silently resetting to today', async () => {
    window.sessionStorage.setItem('shiguang-booking-draft', JSON.stringify({
      slots: [slotAt(3, 10, 11)],
      venueId: 2,
      venueName: '星河体育馆',
      spaceId: 10,
      spaceName: '一号场',
      spaceType: 'BADMINTON',
      participantCount: 1,
      seatIds: []
    }))
    routeMock.query = { date: dayValue(3), spaceId: '10' }
    // 原时段已被占用：必须明说并停在返回时那一天，不能默默清空选择再跳回今天
    reservationApiMock.availability.mockResolvedValue([
      slotAt(3, 10, 11, { status: 'RESERVED', label: '已约' })
    ])

    const wrapper = mountView()
    await flushPromises()

    const notice = wrapper.get('[data-test="selection-restore-notice"]')
    expect(notice.text()).toContain('原来选择的时段已经不可预约了')
    expect(useReservationStore().selectedSlots).toHaveLength(0)
    expect(reservationApiMock.availability).toHaveBeenLastCalledWith({ spaceId: 10, date: dayValue(3) })
    expect(wrapper.get(`[data-test="date-${dayValue(3)}"]`).classes()).toContain('is-selected')

    // 用户主动换一天后提示必须消失，不能挂着上一次的过期说明
    await wrapper.get(`[data-test="date-${dayValue(4)}"]`).trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="selection-restore-notice"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('hands the venue, space and resource context to the confirmation page', async () => {
    venueApiMock.spaces.mockResolvedValue([
      spaceExclusive({
        type: 'STUDY_ROOM',
        resourceRule: {
          resourceCategory: 'STUDY_ROOM',
          bookingMode: 'CAPACITY',
          capacityLimit: 8,
          versionNo: 2,
          status: 'ACTIVE',
          seats: []
        }
      })
    ])
    reservationApiMock.resourceAvailability.mockResolvedValue(capacityResource(8))
    const wrapper = mountView()
    await flushPromises()

    const store = useReservationStore()
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="mobile-continue"]').attributes('disabled')).toBeDefined()

    await wrapper.get(`[data-test="slot-${dayValue(1)}T10:00:00"]`).trigger('click')
    await flushPromises()
    await wrapper.get('.resource-section [data-test="people-plus"]').trigger('click')
    await flushPromises()

    await wrapper.get('.booking-summary [data-test="summary-submit"]').trigger('click')

    expect(store.lastVenueId).toBe(2)
    expect(store.venueName).toBe('星河体育馆')
    expect(store.spaceName).toBe('一号场')
    expect(store.spaceType).toBe('STUDY_ROOM')
    expect(store.participantCount).toBe(2)
    expect(store.seatIds).toEqual([])
    expect(routerMock.push).toHaveBeenCalledWith('/reservation/confirm')
    expect(store.selectedSlots).toHaveLength(1)

    wrapper.unmount()
  })
})
