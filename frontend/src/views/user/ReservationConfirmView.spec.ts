import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ReservationConfirmView from './ReservationConfirmView.vue'
import { useReservationStore } from '@/stores/reservation'

const reservationApiMock = vi.hoisted(() => ({
  resourceAvailability: vi.fn(),
  pricingPreview: vi.fn(),
  create: vi.fn(),
  submissionResult: vi.fn()
}))

const couponApiMock = vi.hoisted(() => ({
  my: vi.fn()
}))

const funnelStoreMock = vi.hoisted(() => ({
  sessionKey: 'confirm-session',
  event: vi.fn((base: Record<string, unknown>) => ({ sessionKey: 'confirm-session', visitorId: 'visitor-1', sourceChannel: 'WEB', ...base })),
  track: vi.fn()
}))

const routerMock = vi.hoisted(() => ({ back: vi.fn(), push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({ params: {} as Record<string, string>, query: {} as Record<string, string> }))
const permissionsMock = vi.hoisted(() => ({ granted: ['coupon:me'] as string[] }))

vi.mock('@/api/reservation', () => ({ reservationApi: reservationApiMock }))
vi.mock('@/api/promotion', () => ({ couponApi: couponApiMock }))
vi.mock('@/stores/funnel', () => ({ useFunnelStore: () => funnelStoreMock }))
vi.mock('@/stores/menu', () => ({ useMenuStore: () => ({ hasPermission: (permission: string) => permissionsMock.granted.includes(permission) }) }))
vi.mock('vue-router', () => ({ useRoute: () => routeMock, useRouter: () => routerMock }))

const DRAFT_KEY = 'shiguang-booking-draft'
const SUBMIT_INTENT_KEY = 'shiguang-submit-intent'

const SLOT_MORNING = {
  spaceId: 10,
  startTime: '2030-07-02T10:00:00',
  endTime: '2030-07-02T11:00:00',
  status: 'AVAILABLE',
  label: '可约',
  price: 60
}
const SLOT_NOON = {
  spaceId: 10,
  startTime: '2030-07-02T11:00:00',
  endTime: '2030-07-02T12:00:00',
  status: 'AVAILABLE',
  label: '可约',
  price: 60
}

const EXCLUSIVE_BOOKABLE = {
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

const PRICING = {
  originalAmount: 120,
  memberDiscountAmount: 12,
  couponDiscountAmount: 0,
  activityDiscountAmount: 0,
  payableAmount: 108,
  pricingVersion: 'B3_PRICING_V1'
}

const stubs = {
  'router-link': {
    props: ['to'],
    template: '<a :href="typeof to === \'string\' ? to : (to && to.path) || \'#\'"><slot /></a>'
  }
}

const mountView = () => mount(ReservationConfirmView, { global: { stubs, mocks: { $router: routerMock } } })

const seedSelection = (
  slots = [SLOT_MORNING, SLOT_NOON],
  context = { venueId: 2, venueName: '星河体育馆', spaceName: '一号场', spaceType: 'BADMINTON' }
) => {
  const store = useReservationStore()
  store.setSelectionContext(context)
  store.setSelectedSlots(slots)
  return store
}

const fillContact = async (wrapper: ReturnType<typeof mountView>) => {
  await wrapper.get('[data-test="contact-name"]').setValue('王小明')
  await wrapper.get('[data-test="contact-phone"]').setValue('13800000000')
  await flushPromises()
}

const submitViaSummary = async (wrapper: ReturnType<typeof mountView>) => {
  await wrapper.get('.booking-summary [data-test="summary-submit"]').trigger('click')
  await flushPromises()
}

describe('ReservationConfirmView confirmation flow', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    permissionsMock.granted = ['coupon:me']
    routeMock.params = {}
    routeMock.query = {}
    reservationApiMock.resourceAvailability.mockResolvedValue(EXCLUSIVE_BOOKABLE)
    reservationApiMock.pricingPreview.mockResolvedValue(PRICING)
    reservationApiMock.create.mockResolvedValue({ id: 101 })
    reservationApiMock.submissionResult.mockResolvedValue({ status: 'NONE' })
    couponApiMock.my.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 50 })
    funnelStoreMock.track.mockResolvedValue(undefined)
  })

  it('clears corrected contact errors before a recovery button can move on blur', async () => {
    seedSelection()
    const wrapper = mountView()
    await wrapper.get('[data-test="contact-phone"]').trigger('blur')
    expect(wrapper.get('#contact-error').text()).not.toBe('')
    await fillContact(wrapper)
    expect(wrapper.find('#contact-error').exists()).toBe(false)
    wrapper.unmount()
  })

  it('recalculates the quote when a delayed resource response reveals capacity mode', async () => {
    let resolveAvailability!: (value: unknown) => void
    reservationApiMock.resourceAvailability.mockImplementationOnce(() => new Promise(resolve => { resolveAvailability = resolve }))
    reservationApiMock.pricingPreview.mockImplementation(async (payload) => {
      if (!payload.participantCount) throw new Error('CAPACITY 模式必须填写预约人数')
      return PRICING
    })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    resolveAvailability({ ...EXCLUSIVE_BOOKABLE, rule: { ...EXCLUSIVE_BOOKABLE.rule, bookingMode: 'CAPACITY', capacityLimit: 10 }, remainingCapacity: 10 })
    await flushPromises()
    expect(reservationApiMock.pricingPreview.mock.lastCall?.[0].participantCount).toBe(1)
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('shows the empty state and a way back when nothing has been selected', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('.state-message').text()).toContain('还没有选择时间。')
    expect(wrapper.get('[data-test="find-venue"]').attributes('href')).toBe('/venues')
    expect(wrapper.find('[data-test="contact-name"]').exists()).toBe(false)
    expect(wrapper.find('.booking-recap').exists()).toBe(false)
    expect(reservationApiMock.resourceAvailability).not.toHaveBeenCalled()
    expect(reservationApiMock.pricingPreview).not.toHaveBeenCalled()
    expect(reservationApiMock.create).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('restores the sessionStorage draft so a refresh keeps the whole selection', async () => {
    const store = useReservationStore()
    const restoreSpy = vi.spyOn(store, 'restoreDraft')
    window.sessionStorage.setItem(DRAFT_KEY, JSON.stringify({
      slots: [SLOT_NOON, SLOT_MORNING],
      venueId: 2,
      venueName: '星河体育馆',
      spaceId: 10,
      spaceName: '一号场',
      spaceType: 'BADMINTON',
      participantCount: 1,
      seatIds: []
    }))
    const wrapper = mountView()
    await flushPromises()

    expect(restoreSpy).toHaveBeenCalledTimes(1)
    expect(store.selectedSlots.map((slot) => slot.startTime)).toEqual([
      '2030-07-02T10:00:00',
      '2030-07-02T11:00:00'
    ])
    expect(store.lastVenueId).toBe(2)
    // 返回链接必须带非当天日期与场地，否则会把用户丢回今天的空页面
    expect(wrapper.get('[data-test="back-to-detail"]').attributes('href'))
      .toBe('/venues/2?date=2030-07-02&spaceId=10')
    const recap = wrapper.get('.booking-recap')
    expect(recap.text()).toContain('星河体育馆')
    expect(recap.text()).toContain('一号场 · 整空间独立使用')
    expect(recap.text()).toContain('7 月 2 日')
    expect(recap.text()).toContain('10:00–12:00')
    expect(reservationApiMock.resourceAvailability).toHaveBeenCalledWith({
      spaceId: 10,
      startTime: '2030-07-02T10:00:00',
      endTime: '2030-07-02T12:00:00',
      slots: expect.any(Array)
    })
    expect(wrapper.find('[data-test="conflict-band"]').exists()).toBe(false)

    restoreSpy.mockRestore()
    wrapper.unmount()
  })

  it('waits for a valid contact before asking the server for the price', async () => {
    seedSelection()
    const wrapper = mountView()
    await flushPromises()

    expect(reservationApiMock.pricingPreview).not.toHaveBeenCalled()
    expect(wrapper.get('.booking-summary .fee-note').text()).toContain('提交后等待审核')
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="mobile-submit"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test="contact-name"]').setValue('王小明')
    await flushPromises()
    expect(reservationApiMock.pricingPreview).not.toHaveBeenCalled()

    await wrapper.get('[data-test="contact-phone"]').setValue('13800000000')
    await flushPromises()

    expect(reservationApiMock.pricingPreview).toHaveBeenCalledTimes(1)
    expect(reservationApiMock.pricingPreview).toHaveBeenCalledWith({
      spaceId: 10,
      startTime: '2030-07-02T10:00:00',
      endTime: '2030-07-02T12:00:00',
      slots: expect.any(Array),
      contactName: '王小明',
      contactPhone: '13800000000',
      remark: '',
      userCouponId: undefined
    })
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').text()).toContain('提交预约申请')
    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'PRICE_PREVIEW_SHOWN',
      eventStage: 'PRICE',
      venueId: 2,
      spaceId: 10,
      payableAmount: 108,
      pagePath: '/reservation/confirm'
    }))

    wrapper.unmount()
  })

  it('renders every amount from the server pricing preview instead of a client multiplication', async () => {
    reservationApiMock.pricingPreview.mockResolvedValue({
      originalAmount: 140,
      memberDiscountAmount: 14,
      couponDiscountAmount: 20,
      activityDiscountAmount: 10,
      payableAmount: 96,
      pricingVersion: 'B3_PRICING_V1'
    })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)

    const summary = wrapper.get('.booking-summary')
    expect(summary.text()).toContain('场地单价')
    expect(summary.text()).toContain('¥60 / 小时')
    expect(summary.text()).toContain('时长 2 小时')
    expect(summary.text()).toContain('¥140')
    expect(summary.get('.cost-total strong').text()).toBe('¥96')
    expect(summary.findAll('.discount-lines .cost-row').map((row) => row.text())).toEqual([
      '会员优惠− ¥14',
      '活动优惠− ¥10',
      '优惠券抵扣− ¥20'
    ])
    // 60 × 2 小时的客户端乘法结果不得出现
    expect(summary.text()).not.toContain('120')
    expect(wrapper.get('.mobile-booking').text()).toContain('¥96')

    wrapper.unmount()
  })

  it('blocks submission and offers a recompute when the server price cannot be fetched', async () => {
    reservationApiMock.pricingPreview.mockRejectedValue({
      response: { status: 500, data: { message: '计价服务暂时不可用' } }
    })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)

    const band = wrapper.get('.booking-content .inline-error')
    expect(band.attributes('role')).toBe('alert')
    expect(band.text()).toContain('计价服务暂时不可用')
    expect(band.text()).toContain('费用未能从服务端取回，暂时不能提交。')
    expect(wrapper.get('.booking-summary .cost-total strong').text()).toBe('—')
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.mobile-booking').text()).toContain('待核对')
    expect(reservationApiMock.create).not.toHaveBeenCalled()

    reservationApiMock.pricingPreview.mockResolvedValue(PRICING)
    await band.get('[data-test="reload-preview"]').trigger('click')
    await flushPromises()

    expect(reservationApiMock.pricingPreview).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.booking-content .inline-error').exists()).toBe(false)
    expect(wrapper.get('.booking-summary .cost-total strong').text()).toBe('¥108')
    expect(wrapper.get('.booking-summary [data-test="summary-submit"]').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })

  it('reports the offending contact field on a retry and blocks the retry until it is fixed', async () => {
    reservationApiMock.create.mockRejectedValueOnce({
      response: { status: 500, data: { message: '预约服务暂时不可用，请稍后重试' } }
    })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    await submitViaSummary(wrapper)

    expect(wrapper.find('[data-test="submit-unknown"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="contact-name"]').attributes('aria-invalid')).toBe('false')
    expect(wrapper.find('.form-error').exists()).toBe(false)

    await wrapper.get('[data-test="contact-name"]').setValue('')
    await wrapper.get('[data-test="retry-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="contact-name"]').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('.form-error').text()).toBe('请填写联系人称呼，不超过 64 个字符。')
    expect(reservationApiMock.create).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="summary-submit"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test="contact-name"]').setValue('王小明')
    await wrapper.get('[data-test="contact-phone"]').setValue('138000000000')
    await wrapper.get('[data-test="retry-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="contact-phone"]').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[data-test="contact-name"]').attributes('aria-invalid')).toBe('false')
    expect(wrapper.get('.form-error').text()).toBe('请输入 11 位有效手机号。')
    expect(reservationApiMock.create).toHaveBeenCalledTimes(1)

    await fillContact(wrapper)
    await wrapper.get('[data-test="retry-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('.form-error').exists()).toBe(false)
    expect(reservationApiMock.create).toHaveBeenCalledTimes(2)
    expect(routerMock.push).toHaveBeenCalledWith('/reservation/result/101')

    wrapper.unmount()
  })

  it('treats a server error as an unknown result and keeps the same request key for the retry', async () => {
    reservationApiMock.create.mockRejectedValueOnce({
      response: { status: 500, data: { message: '预约服务暂时不可用，请稍后重试' } }
    })
    seedSelection()
    const wrapper = mountView()
    await flushPromises()
    await fillContact(wrapper)
    await submitViaSummary(wrapper)

    const band = wrapper.get('[data-test="submit-unknown"]')
    expect(band.attributes('role')).toBe('status')
    expect(band.text()).toContain('结果待核对。')
    expect(band.text()).toContain('预约服务暂时不可用，请稍后重试')
    expect(wrapper.find('[data-test="conflict-band"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="submit-failure"]').exists()).toBe(false)
    expect(reservationApiMock.resourceAvailability).toHaveBeenCalledTimes(2)
    expect(useReservationStore().selectedSlots).toHaveLength(2)
    expect(routerMock.push).not.toHaveBeenCalled()

    reservationApiMock.resourceAvailability.mockResolvedValue({ bookable: false, reasonCode: 'EXCLUSIVE_OVERLAP' })
    await submitViaSummary(wrapper)
    expect(reservationApiMock.resourceAvailability).toHaveBeenCalledTimes(2)
    expect(reservationApiMock.create).toHaveBeenCalledTimes(2)
    // 同一次提交意图必须复用同一个请求键，否则服务端会再建一条预约
    const firstKey = reservationApiMock.create.mock.calls[0][2]
    const secondKey = reservationApiMock.create.mock.calls[1][2]
    expect(firstKey).toBeTruthy()
    expect(secondKey).toBe(firstKey)

    wrapper.unmount()
  })

  it('creates the reservation once when submit is triggered twice', async () => {
    let resolveCreate!: (reservation: Record<string, unknown>) => void
    reservationApiMock.create.mockReturnValue(new Promise((resolve) => { resolveCreate = resolve }))
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    const vm = wrapper.vm as unknown as { submit: () => Promise<void> }

    const first = vm.submit()
    const second = vm.submit()
    await flushPromises()

    expect(reservationApiMock.create).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="summary-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="summary-submit"]').text()).toContain('正在提交…')
    expect(wrapper.get('.mobile-booking').text()).toContain('正在提交…')

    resolveCreate({ id: 102 })
    await Promise.all([first, second])

    expect(reservationApiMock.create).toHaveBeenCalledTimes(1)
    expect(routerMock.push).toHaveBeenCalledWith('/reservation/result/102')

    wrapper.unmount()
  })

  it('re-checks live availability immediately before creating the reservation', async () => {
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    reservationApiMock.resourceAvailability.mockClear()

    await submitViaSummary(wrapper)

    expect(reservationApiMock.resourceAvailability).toHaveBeenCalledTimes(1)
    expect(reservationApiMock.resourceAvailability).toHaveBeenCalledWith({
      spaceId: 10,
      startTime: '2030-07-02T10:00:00',
      endTime: '2030-07-02T12:00:00',
      slots: expect.any(Array)
    })
    const resourceOrder = reservationApiMock.resourceAvailability.mock.invocationCallOrder
    const createOrder = reservationApiMock.create.mock.invocationCallOrder
    expect(resourceOrder[0]).toBeLessThan(createOrder[0])
    expect(reservationApiMock.create).toHaveBeenCalledWith({
      spaceId: 10,
      startTime: '2030-07-02T10:00:00',
      endTime: '2030-07-02T12:00:00',
      slots: expect.any(Array),
      contactName: '王小明',
      contactPhone: '13800000000',
      remark: undefined,
      userCouponId: undefined
    }, 'confirm-session', expect.any(String))

    wrapper.unmount()
  })

  it('drops the submission when the slot goes unsellable before create', async () => {
    reservationApiMock.resourceAvailability
      .mockResolvedValueOnce(EXCLUSIVE_BOOKABLE)
      .mockResolvedValue({ ...EXCLUSIVE_BOOKABLE, bookable: false, reasonCode: 'RESOURCE_RULE_UNAVAILABLE' })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)

    await submitViaSummary(wrapper)

    expect(reservationApiMock.create).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="conflict-band"] strong').text()).toBe('这个场地现在不能按当前方式预约了。')
    expect(wrapper.get('[data-test="summary-submit"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('turns a 409 create conflict into the overlap band and keeps the contact and selection', async () => {
    reservationApiMock.resourceAvailability
      .mockResolvedValueOnce(EXCLUSIVE_BOOKABLE)
      .mockResolvedValueOnce(EXCLUSIVE_BOOKABLE)
      .mockResolvedValue({
        ...EXCLUSIVE_BOOKABLE,
        bookable: false,
        reasonCode: 'EXCLUSIVE_OVERLAP',
        reasonText: '这段时间刚刚被占用'
      })
    reservationApiMock.create.mockRejectedValue({
      response: { status: 409, data: { data: { reasonCode: 'EXCLUSIVE_OVERLAP' } } }
    })
    couponApiMock.my.mockResolvedValue({
      records: [{
        id: 70,
        templateId: 5,
        templateCode: 'CASH20',
        templateName: '满 100 减 20',
        couponType: 'CASH',
        discountValue: 20,
        thresholdAmount: 100,
        status: 'CLAIMED'
      }],
      total: 1,
      pageNum: 1,
      pageSize: 50
    })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    await submitViaSummary(wrapper)

    const band = wrapper.get('[data-test="conflict-band"]')
    expect(band.attributes('role')).toBe('alert')
    expect(band.find('strong').text()).toBe('刚刚，这段时间被先一步选中了。')
    expect(band.text()).toContain('预约尚未提交。你的联系人信息已保留，请返回选择其他时段。')
    expect(wrapper.find('[data-test="submit-failure"]').exists()).toBe(false)
    expect((wrapper.get('[data-test="contact-name"]').element as HTMLInputElement).value).toBe('王小明')
    expect((wrapper.get('[data-test="contact-phone"]').element as HTMLInputElement).value).toBe('13800000000')
    expect(wrapper.get('[data-test="resolve-conflict"]').attributes('href'))
      .toBe('/venues/2?date=2030-07-02&spaceId=10')
    expect(wrapper.get('[data-test="summary-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="mobile-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="coupon-70"]').attributes('disabled')).toBeDefined()
    expect(reservationApiMock.create).toHaveBeenCalledTimes(1)
    expect(useReservationStore().selectedSlots).toHaveLength(2)

    await submitViaSummary(wrapper)
    expect(reservationApiMock.create).toHaveBeenCalledTimes(1)
    expect(routerMock.push).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('falls back to the overlap headline when a 409 carries no reason payload', async () => {
    reservationApiMock.resourceAvailability
      .mockResolvedValueOnce(EXCLUSIVE_BOOKABLE)
      .mockResolvedValueOnce(EXCLUSIVE_BOOKABLE)
      .mockRejectedValue({ response: { status: 500, data: { message: '可售查询失败' } } })
    reservationApiMock.create.mockRejectedValue({ response: { status: 409 } })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    await submitViaSummary(wrapper)

    expect(wrapper.get('[data-test="conflict-band"] strong').text()).toBe('刚刚，这段时间被先一步选中了。')
    expect(wrapper.find('[data-test="submit-failure"]').exists()).toBe(false)

    wrapper.unmount()
  })

  for (const [reasonCode, headline] of Object.entries({
    EXCLUSIVE_OVERLAP: '刚刚，这段时间被先一步选中了。',
    CAPACITY_EXCEEDED: '这段时间的余量已经不够本次人数了。',
    SEAT_ALREADY_ALLOCATED: '你选的座位刚刚被占用了。',
    RESOURCE_RULE_UNAVAILABLE: '这个场地现在不能按当前方式预约了。'
  })) {
    it(`maps the ${reasonCode} conflict to its own headline and blocks the summary submit`, async () => {
      reservationApiMock.resourceAvailability.mockResolvedValue({
        ...EXCLUSIVE_BOOKABLE,
        bookable: false,
        reasonCode,
        reasonText: '服务端说明文案'
      })
      seedSelection()
      const wrapper = mountView()
      await flushPromises()

      expect(wrapper.get('[data-test="conflict-band"] strong').text()).toBe(headline)
      expect(wrapper.find('.form-grid').exists()).toBe(true)
      expect(wrapper.get('[data-test="summary-submit"]').attributes('disabled')).toBeDefined()
      expect(wrapper.get('[data-test="mobile-submit"]').attributes('disabled')).toBeDefined()
      expect(reservationApiMock.create).not.toHaveBeenCalled()

      wrapper.unmount()
    })
  }

  it('reports a drafted seat that is no longer allocatable and syncs the cleared choice', async () => {
    const store = seedSelection()
    store.setResourceSelection({ seatIds: [501] })
    reservationApiMock.resourceAvailability.mockResolvedValue({
      spaceId: 10,
      rule: {
        id: 10,
        spaceId: 10,
        resourceCategory: 'STUDY_ROOM',
        bookingMode: 'ASSIGNED_SEAT',
        versionNo: 3,
        status: 'ACTIVE',
        seats: [
          { id: 501, seatCode: 'A-01', seatLabel: 'A区01座', status: 'ACTIVE', available: false },
          { id: 502, seatCode: 'A-02', seatLabel: 'A区02座', status: 'ACTIVE', available: true }
        ]
      },
      participantCount: 1,
      remainingCapacity: 1,
      bookable: false,
      reasonCode: 'SEAT_ALREADY_ALLOCATED'
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-test="conflict-band"] strong').text()).toBe('你选的座位刚刚被占用了。')
    expect(store.seatIds).toEqual([])
    expect(wrapper.get('.booking-recap').text()).toContain('尚未选择座位')
    expect(reservationApiMock.resourceAvailability).toHaveBeenLastCalledWith({
      spaceId: 10,
      startTime: '2030-07-02T10:00:00',
      endTime: '2030-07-02T12:00:00',
      slots: expect.any(Array)
    })

    wrapper.unmount()
  })

  it('loads claimed coupons only with the coupon:me permission and re-prices on selection', async () => {
    permissionsMock.granted = []
    seedSelection()
    const anonymous = mountView()
    await flushPromises()

    expect(anonymous.find('.coupon-option').exists()).toBe(false)
    expect(anonymous.text()).not.toContain('一点小小的心意')
    expect(anonymous.find('[data-test="contact-name"]').exists()).toBe(true)
    expect(couponApiMock.my).not.toHaveBeenCalled()
    anonymous.unmount()

    permissionsMock.granted = ['coupon:me']
    couponApiMock.my.mockResolvedValue({
      records: [
        {
          id: 70,
          templateId: 5,
          templateCode: 'CASH20',
          templateName: '满 100 减 20',
          couponType: 'CASH',
          discountValue: 20,
          thresholdAmount: 100,
          status: 'CLAIMED',
          validEndTime: '2030-08-31T23:59:59'
        },
        {
          id: 71,
          templateId: 6,
          templateCode: 'USED',
          templateName: '已使用的券',
          discountValue: 5,
          thresholdAmount: 10,
          status: 'USED'
        }
      ],
      total: 2,
      pageNum: 1,
      pageSize: 50
    })
    reservationApiMock.pricingPreview.mockResolvedValue({
      ...PRICING,
      originalAmount: 140,
      couponDiscountAmount: 20,
      payableAmount: 88,
      userCouponId: 70,
      couponUnavailableReason: ''
    })
    seedSelection()
    const wrapper = mountView()
    await flushPromises()

    expect(couponApiMock.my).toHaveBeenCalledWith({ pageNum: 1, pageSize: 50, status: 'CLAIMED' })
    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'COUPON_SHOWN',
      eventStage: 'COUPON',
      spaceId: 10,
      // 埋点上报的是服务端返回的原始条数，页面实际只渲染 CLAIMED 那一张
      metadata: expect.objectContaining({ availableCount: 2 })
    }))
    const coupon = wrapper.get('[data-test="coupon-70"]')
    expect(coupon.text()).toContain('¥20')
    expect(coupon.text()).toContain('满 100 减 20')
    expect(coupon.text()).toContain('满 ¥100 可用')
    expect(coupon.text()).toContain('8月31日 23:59 前有效')
    expect(wrapper.find('[data-test="coupon-71"]').exists()).toBe(false)
    expect(wrapper.findAll('.confirm-section')[1].text())
      .toContain('有 1 张券可选，是否抵扣以服务端计价结果为准。')

    await fillContact(wrapper)
    expect(reservationApiMock.pricingPreview).toHaveBeenLastCalledWith(expect.objectContaining({ userCouponId: undefined }))

    await coupon.trigger('click')
    await flushPromises()

    expect(reservationApiMock.pricingPreview).toHaveBeenCalledTimes(2)
    expect(reservationApiMock.pricingPreview).toHaveBeenLastCalledWith(expect.objectContaining({ userCouponId: 70 }))
    expect(wrapper.get('[data-test="coupon-70"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-test="coupon-70"]').classes()).toContain('is-selected')
    expect(wrapper.get('.booking-summary .cost-total strong').text()).toBe('¥88')
    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'COUPON_SELECTED',
      eventStage: 'COUPON',
      userCouponId: 70,
      spaceId: 10
    }))
    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'PRICE_PREVIEW_SHOWN',
      payableAmount: 88,
      userCouponId: 70
    }))

    reservationApiMock.pricingPreview.mockResolvedValue({
      ...PRICING,
      couponDiscountAmount: 0,
      payableAmount: 108,
      couponUnavailableReason: '本单金额未达到券的使用门槛'
    })
    await wrapper.get('[data-test="coupon-70"]').trigger('click')
    await flushPromises()

    expect(reservationApiMock.pricingPreview).toHaveBeenLastCalledWith(expect.objectContaining({ userCouponId: undefined }))
    expect(wrapper.get('[data-test="coupon-70"]').attributes('aria-pressed')).toBe('false')
    expect(wrapper.text()).toContain('本单金额未达到券的使用门槛')

    await submitViaSummary(wrapper)
    expect(reservationApiMock.create).toHaveBeenCalledWith(
      expect.objectContaining({ userCouponId: undefined }),
      'confirm-session',
      expect.any(String)
    )

    wrapper.unmount()
  })

  it('keeps the coupon list honest when the coupon service fails', async () => {
    couponApiMock.my.mockRejectedValue(new Error('coupon service down'))
    seedSelection()
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('优惠券暂时无法加载，不影响原价预约。')
    expect(wrapper.findAll('.coupon-option')).toHaveLength(0)

    await fillContact(wrapper)
    expect(reservationApiMock.pricingPreview).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.booking-summary .cost-total strong').text()).toBe('¥108')

    wrapper.unmount()
  })

  it('clears the selection context and routes to the result page on success', async () => {
    const store = seedSelection()
    expect(window.sessionStorage.getItem(DRAFT_KEY)).toContain('星河体育馆')
    const wrapper = mountView()
    await fillContact(wrapper)

    await submitViaSummary(wrapper)

    expect(routerMock.push).toHaveBeenCalledWith('/reservation/result/101')
    expect(store.selectedSlots).toEqual([])
    expect(store.spaceId).toBeUndefined()
    expect(store.spaceName).toBeUndefined()
    expect(store.participantCount).toBe(1)
    expect(store.seatIds).toEqual([])
    expect(store.submitIntentKey).toBeUndefined()
    expect(window.sessionStorage.getItem(DRAFT_KEY)).toBeNull()
    expect(wrapper.get('.state-message').text()).toContain('还没有选择时间。')

    wrapper.unmount()
  })

  it('never reports an availability query failure as an inventory conflict and blocks the submit', async () => {
    reservationApiMock.resourceAvailability
      .mockResolvedValueOnce(EXCLUSIVE_BOOKABLE)
      .mockRejectedValue({ response: { status: 503, data: { message: '可售服务暂时不可用' } } })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)

    await submitViaSummary(wrapper)

    const band = wrapper.get('[data-test="availability-unknown"]')
    expect(band.text()).toContain('暂时无法确认这段时间还能不能预约。')
    expect(wrapper.find('[data-test="conflict-band"]').exists()).toBe(false)
    expect(reservationApiMock.create).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="summary-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="mobile-submit"]').attributes('disabled')).toBeDefined()

    reservationApiMock.resourceAvailability.mockResolvedValue(EXCLUSIVE_BOOKABLE)
    await band.get('[data-test="recheck-availability"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="availability-unknown"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="summary-submit"]').attributes('disabled')).toBeUndefined()
    expect(reservationApiMock.create).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('treats a request-identity conflict as a pending result instead of an inventory conflict', async () => {
    reservationApiMock.create.mockRejectedValue({
      response: { status: 409, data: { data: { reasonCode: 'SUBMISSION_IN_PROGRESS' } } }
    })
    reservationApiMock.submissionResult.mockResolvedValue({ status: 'PROCESSING' })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    await submitViaSummary(wrapper)

    const band = wrapper.get('[data-test="submit-unknown"]')
    expect(band.text()).toContain('结果待核对。')
    expect(band.text()).toContain('仍在处理中')
    expect(wrapper.find('[data-test="conflict-band"]').exists()).toBe(false)
    const key = reservationApiMock.create.mock.calls[0][2]
    expect(reservationApiMock.submissionResult).toHaveBeenCalledWith(key)

    wrapper.unmount()
  })

  it('reconciles a lost response to the real reservation instead of creating a second one', async () => {
    reservationApiMock.create.mockRejectedValue(new Error('Network Error'))
    reservationApiMock.submissionResult.mockResolvedValue({ status: 'COMPLETED', reservationId: 202 })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    await submitViaSummary(wrapper)

    expect(wrapper.get('[data-test="submit-unknown"]').text()).toContain('结果待核对。')
    expect(useReservationStore().selectedSlots).toHaveLength(2)

    await wrapper.get('[data-test="reconcile-submit"]').trigger('click')
    await flushPromises()

    expect(routerMock.push).toHaveBeenCalledWith('/reservation/result/202')
    expect(reservationApiMock.create).toHaveBeenCalledTimes(1)
    expect(useReservationStore().submitIntentKey).toBeUndefined()

    wrapper.unmount()
  })

  it('keeps reconciliation reachable when a lost successful response has occupied the selected inventory', async () => {
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    reservationApiMock.create.mockImplementationOnce(async () => {
      const previous = await reservationApiMock.resourceAvailability()
      reservationApiMock.resourceAvailability.mockResolvedValue({ ...previous, bookable: false, reasonCode: 'EXCLUSIVE_OVERLAP' })
      throw new Error('Network Error after server commit')
    })
    await submitViaSummary(wrapper)
    expect(wrapper.find('[data-test="conflict-band"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="reconcile-submit"]').isVisible()).toBe(true)
    reservationApiMock.submissionResult.mockResolvedValue({ status: 'COMPLETED', reservationId: 202 })
    await wrapper.get('[data-test="reconcile-submit"]').trigger('click')
    await flushPromises()
    expect(routerMock.push).toHaveBeenCalledWith('/reservation/result/202')
    expect(reservationApiMock.create).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('recovers a pending submission after a refresh and only then allows a new intent', async () => {
    window.sessionStorage.setItem(DRAFT_KEY, JSON.stringify({
      slots: [SLOT_MORNING, SLOT_NOON],
      venueId: 2,
      venueName: '星河体育馆',
      spaceId: 10,
      spaceName: '一号场',
      spaceType: 'BADMINTON',
      participantCount: 1,
      seatIds: []
    }))
    window.sessionStorage.setItem(SUBMIT_INTENT_KEY, 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
    reservationApiMock.submissionResult.mockResolvedValue({ status: 'COMPLETED', reservationId: 303 })

    const wrapper = mountView()
    await flushPromises()

    expect(reservationApiMock.submissionResult).toHaveBeenCalledWith('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
    expect(routerMock.push).toHaveBeenCalledWith('/reservation/result/303')
    expect(useReservationStore().submitIntentKey).toBeUndefined()
    expect(window.sessionStorage.getItem(SUBMIT_INTENT_KEY)).toBeNull()

    wrapper.unmount()
  })

  it('preserves a pending key across refresh when the read cannot see an uncommitted transaction', async () => {
    window.sessionStorage.setItem(DRAFT_KEY, JSON.stringify({
      slots: [SLOT_MORNING, SLOT_NOON],
      venueId: 2,
      venueName: '星河体育馆',
      spaceId: 10,
      spaceName: '一号场',
      spaceType: 'BADMINTON',
      participantCount: 1,
      seatIds: [],
      submitIntentKey: 'ffffffff-1111-2222-3333-444444444444'
    }))
    window.sessionStorage.setItem(SUBMIT_INTENT_KEY, 'ffffffff-1111-2222-3333-444444444444')
    reservationApiMock.submissionResult.mockResolvedValue({ status: 'NONE' })

    const wrapper = mountView()
    await flushPromises()

    expect(useReservationStore().submitIntentKey).toBe('ffffffff-1111-2222-3333-444444444444')
    expect(routerMock.push).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="submit-unknown"]').text()).toContain('原请求仍可能处理中')

    wrapper.unmount()
  })

  it('never swaps the key after a payload conflict followed by an invisible result', async () => {
    reservationApiMock.create.mockRejectedValue({
      response: { status: 409, data: { data: { reasonCode: 'SUBMISSION_KEY_REUSED' } } }
    })
    reservationApiMock.submissionResult.mockResolvedValue({ status: 'NONE' })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    await submitViaSummary(wrapper)

    // 改过载荷也不能悄悄换键重投：先按原键核对，NONE不能证明原事务不会提交
    const key = reservationApiMock.create.mock.calls[0][2]
    expect(reservationApiMock.submissionResult).toHaveBeenCalledWith(key)
    expect(reservationApiMock.create).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="conflict-band"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="submit-unknown"]').text()).toContain('原请求仍可能处理中')
    expect(useReservationStore().submitIntentKey).toBe(key)

    reservationApiMock.create.mockResolvedValue({ id: 505 })
    await submitViaSummary(wrapper)
    const nextKey = reservationApiMock.create.mock.calls[1][2]
    expect(nextKey).toBeTruthy()
    expect(nextKey).toBe(key)

    wrapper.unmount()
  })

  it('surfaces an explicit client-side rejection as a retryable failure band', async () => {
    reservationApiMock.create.mockRejectedValue({
      response: { status: 422, data: { message: '联系人信息不合法' } }
    })
    seedSelection()
    const wrapper = mountView()
    await fillContact(wrapper)
    await submitViaSummary(wrapper)

    const band = wrapper.get('[data-test="submit-failure"]')
    expect(band.text()).toContain('这次提交没有成功。')
    expect(band.text()).toContain('联系人信息不合法')
    expect(wrapper.find('[data-test="submit-unknown"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="conflict-band"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
