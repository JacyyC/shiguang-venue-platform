import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ShiguangReservationResultView from './ShiguangReservationResultView.vue'
import type { Reservation } from '@/api/types'

const reservationApiMock = vi.hoisted(() => ({ detail: vi.fn() }))
const venueApiMock = vi.hoisted(() => ({ spaces: vi.fn() }))
const fetchSpaceNameMock = vi.hoisted(() => vi.fn())
const routeMock = vi.hoisted(() => ({ params: { id: '20501' } }))
const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
const reservationStoreMock = vi.hoisted(() => ({
  venueName: undefined as string | undefined,
  spaceName: undefined as string | undefined,
  spaceType: undefined as string | undefined
}))

vi.mock('@/api/reservation', () => ({ reservationApi: reservationApiMock }))
vi.mock('@/api/venue', () => ({ venueApi: venueApiMock }))
vi.mock('@/utils/entityNames', () => ({ fetchSpaceName: fetchSpaceNameMock }))
vi.mock('@/stores/reservation', () => ({ useReservationStore: () => reservationStoreMock }))
vi.mock('vue-router', () => ({ useRoute: () => routeMock, useRouter: () => routerMock }))
vi.mock('@/components/shiguang/ShiguangArt.vue', () => ({
  default: {
    name: 'ShiguangArtStub',
    props: { scene: { type: String, default: '' } },
    template: '<i class="sgv-art-stub" :data-scene="scene" aria-hidden="true"></i>'
  }
}))

const stubs = {
  'router-link': {
    props: ['to'],
    template: '<a :href="typeof to === \'string\' ? to : (to.path || \'\')"><slot /></a>'
  },
  'el-skeleton': { template: '<div data-test="skeleton"><slot /></div>' }
}

/** 金额三档刻意互不相同：amount 120 / payableAmount 96 / paidAmount 88，
 *  「实付」与「预计应付」的口径一旦串台，断言就会立刻失败。 */
const reservationFixture = (overrides: Partial<Reservation> = {}): Reservation => ({
  id: 20501,
  userId: 7,
  venueId: 2,
  spaceId: 10,
  startTime: '2026-07-03T10:00:00',
  endTime: '2026-07-03T11:30:00',
  status: 'PENDING_REVIEW',
  amount: 120,
  payableAmount: 96,
  paidAmount: 0,
  venueName: '城南体育馆',
  ...overrides
})

const mountView = async () => {
  const wrapper = mount(ShiguangReservationResultView, { global: { stubs } })
  await flushPromises()
  return wrapper
}

const linkByText = (wrapper: Awaited<ReturnType<typeof mountView>>, text: string) =>
  wrapper.findAll('a').find((node) => node.text().includes(text))

const footerSpans = (wrapper: Awaited<ReturnType<typeof mountView>>) => wrapper.findAll('.sheet-footer span')

const resourceLine = (wrapper: Awaited<ReturnType<typeof mountView>>) => wrapper.get('.sheet-main').text()

describe('ShiguangReservationResultView 结果读取', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeMock.params = { id: '20501' }
    reservationStoreMock.venueName = undefined
    reservationStoreMock.spaceName = undefined
    reservationStoreMock.spaceType = undefined
    venueApiMock.spaces.mockRejectedValue(new Error('spaces not mocked in this case'))
    fetchSpaceNameMock.mockResolvedValue('3 号羽毛球馆')
    reservationApiMock.detail.mockResolvedValue(reservationFixture())
  })

  it('shows the loading shell first and requests the detail exactly once with the route id', async () => {
    let resolveDetail!: (value: Reservation) => void
    reservationApiMock.detail.mockImplementationOnce(
      () => new Promise<Reservation>((resolve) => { resolveDetail = resolve })
    )

    const wrapper = mount(ShiguangReservationResultView, { global: { stubs } })

    expect(wrapper.find('.booking-loading').exists()).toBe(true)
    expect(wrapper.find('[data-test="skeleton"]').exists()).toBe(true)
    expect(reservationApiMock.detail).toHaveBeenCalledTimes(1)
    // 路由参数是字符串，必须转成数字后只请求一次
    expect(reservationApiMock.detail).toHaveBeenCalledWith(20501)
    expect(wrapper.find('.result-page').exists()).toBe(false)

    resolveDetail(reservationFixture())
    await flushPromises()

    expect(wrapper.find('.booking-loading').exists()).toBe(false)
    expect(reservationApiMock.detail).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.result-page').exists()).toBe(true)
    expect(fetchSpaceNameMock).toHaveBeenCalledWith(2, 10)
    wrapper.unmount()
  })

  it('renders venue, space, serial and the time block from the detail response', async () => {
    const wrapper = await mountView()

    const sheet = wrapper.get('.reservation-sheet')
    expect(sheet.text()).toContain('城南体育馆')
    expect(sheet.text()).toContain('3 号羽毛球馆 · 整空间独立使用')
    expect(sheet.get('.sheet-serial').text()).toBe('预约编号 20501')
    expect(sheet.find('.sheet-when').text()).toContain('7 月 3 日 · 周五')
    expect(sheet.find('.sheet-when strong').text()).toBe('10:00–11:30')
    expect(sheet.find('.sheet-when').text()).toContain('1.5 小时，留给喜欢。')
    wrapper.unmount()
  })

  it('keeps the store names as fallback when the response and lookup carry none', async () => {
    reservationStoreMock.venueName = '店内备忘场馆'
    reservationStoreMock.spaceName = '店内备忘场地'
    venueApiMock.spaces.mockRejectedValue(new Error('spaces not mocked in this case'))
    fetchSpaceNameMock.mockResolvedValue(null)
    reservationApiMock.detail.mockResolvedValue(reservationFixture({ venueName: undefined }))

    const wrapper = await mountView()

    expect(resourceLine(wrapper)).toContain('店内备忘场馆')
    expect(resourceLine(wrapper)).toContain('店内备忘场地 · 整空间独立使用')
    wrapper.unmount()
  })
})

describe('ShiguangReservationResultView 状态矩阵（10 个 ReservationStatus）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeMock.params = { id: '20501' }
    reservationStoreMock.venueName = undefined
    reservationStoreMock.spaceName = undefined
    venueApiMock.spaces.mockRejectedValue(new Error('spaces not mocked in this case'))
    fetchSpaceNameMock.mockResolvedValue('3 号羽毛球馆')
  })

  const cases: Array<{
    status: string
    chip: string
    footer: string
    amountLabel: string
    amountText: string
    negative: boolean
    leadFragment: string
    paidAmount?: number
  }> = [
    {
      status: 'PENDING_REVIEW',
      chip: '预约申请已提交 · 待审核',
      footer: '待审核 · 尚未付款',
      amountLabel: '预计应付',
      amountText: '¥96',
      negative: false,
      leadFragment: '现在等待场馆审核'
    },
    {
      status: 'APPROVED',
      chip: '场馆已确认 · 待支付',
      footer: '待支付 · 尚未付款',
      amountLabel: '预计应付',
      amountText: '¥96',
      negative: false,
      leadFragment: '场馆已通过审核'
    },
    {
      status: 'PENDING_PAYMENT',
      chip: '场馆已确认 · 待支付',
      footer: '待支付 · 尚未付款',
      amountLabel: '预计应付',
      amountText: '¥96',
      negative: false,
      leadFragment: '场馆已通过审核'
    },
    {
      status: 'PAID',
      chip: '已支付 · 等待到场',
      footer: '已支付 · 等待到场',
      amountLabel: '实付',
      amountText: '¥88',
      negative: false,
      leadFragment: '支付已完成',
      paidAmount: 88
    },
    {
      status: 'COMPLETED',
      chip: '这段安排已完成',
      footer: '已完成',
      amountLabel: '实付',
      amountText: '¥88',
      negative: false,
      leadFragment: '这段安排已经完成',
      paidAmount: 88
    },
    {
      status: 'REJECTED',
      chip: '预约未通过',
      footer: '未通过 · 未付款',
      amountLabel: '预计应付',
      amountText: '¥96',
      negative: true,
      leadFragment: '场馆给出了未通过的原因'
    },
    {
      status: 'CANCELED',
      chip: '预约已取消',
      footer: '已取消',
      amountLabel: '预计应付',
      amountText: '¥96',
      negative: true,
      leadFragment: '这次预约已取消'
    },
    {
      status: 'EXPIRED',
      chip: '预约已过期',
      footer: '已过期 · 未付款',
      amountLabel: '预计应付',
      amountText: '¥96',
      negative: true,
      leadFragment: '订单支付超时后预约自动失效'
    },
    {
      status: 'REFUNDING',
      chip: '退款处理中',
      footer: '退款中',
      amountLabel: '实付',
      amountText: '¥88',
      negative: false,
      leadFragment: '这条预约的当前状态如下',
      paidAmount: 88
    },
    {
      status: 'REFUNDED',
      chip: '款项已退回',
      footer: '已退款',
      amountLabel: '实付',
      amountText: '¥88',
      negative: false,
      leadFragment: '这条预约的当前状态如下',
      paidAmount: 88
    }
  ]

  cases.forEach((testCase) => {
    it(`renders ${testCase.status} with its own footer state, money basis and pay entry rule`, async () => {
      // 每个状态都带上 orderId：只有 PENDING_PAYMENT 允许出现支付入口，其余状态必须保持审核前不扣款口径。
      reservationApiMock.detail.mockResolvedValue(reservationFixture({
        status: testCase.status,
        orderId: 301,
        paidAmount: testCase.paidAmount ?? 0
      }))

      const wrapper = await mountView()

      const status = wrapper.get('[data-test="result-status"]')
      expect(status.text()).toBe(testCase.chip)
      expect(status.classes('is-negative')).toBe(testCase.negative)

      const footer = footerSpans(wrapper)
      expect(footer[0].text()).toBe(testCase.footer)
      expect(footer[1].text()).toBe(`${testCase.amountLabel} ${testCase.amountText}`)

      expect(wrapper.get('[data-test="result-headline"]').text()).toBeTruthy()
      expect(wrapper.find('.result-lead').text()).toContain(testCase.leadFragment)
      expect(wrapper.find('[data-test="result-to-pay"]').exists()).toBe(testCase.status === 'PENDING_PAYMENT')
      wrapper.unmount()
    })
  })

  it('derives the space name and the illustration scene from the reservation itself on a cold deep link', async () => {
    // 深链进入时会话内 store 没有场地上下文，名称与插画必须从预约自带的 venueId/spaceId 推导
    reservationStoreMock.spaceType = undefined
    reservationStoreMock.spaceName = undefined
    venueApiMock.spaces.mockResolvedValue([
      { id: 10, venueId: 2, name: '临河 1 号场', type: 'TENNIS', pricePerHour: 60, status: 'ACTIVE' }
    ])
    reservationApiMock.detail.mockResolvedValue(reservationFixture({ status: 'PENDING_REVIEW' }))

    const wrapper = await mountView()

    expect(venueApiMock.spaces).toHaveBeenCalledWith(2)
    expect(wrapper.text()).toContain('临河 1 号场 · 整空间独立使用')
    expect(wrapper.get('.sgv-art-stub').attributes('data-scene')).toBe('move')
    wrapper.unmount()
  })

  it('falls back to the shared name lookup when the space list fails, without inventing a scene', async () => {
    reservationStoreMock.spaceType = undefined
    reservationStoreMock.spaceName = undefined
    venueApiMock.spaces.mockRejectedValue(new Error('space list unavailable'))
    fetchSpaceNameMock.mockResolvedValue('3 号羽毛球馆')
    reservationApiMock.detail.mockResolvedValue(reservationFixture({ status: 'PENDING_REVIEW' }))

    const wrapper = await mountView()

    expect(fetchSpaceNameMock).toHaveBeenCalledWith(2, 10)
    expect(wrapper.text()).toContain('3 号羽毛球馆')
    expect(wrapper.get('.sgv-art-stub').attributes('data-scene')).toBe('together')
    wrapper.unmount()
  })

  it('never paints PENDING_REVIEW as confirmed or paid', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      status: 'PENDING_REVIEW',
      orderId: 301,
      paidAmount: 0
    }))
    const wrapper = await mountView()

    expect(wrapper.get('[data-test="result-status"]').text()).toBe('预约申请已提交 · 待审核')
    expect(wrapper.text()).toContain('待审核 · 尚未付款')
    expect(wrapper.text()).toContain('预计应付 ¥96')
    expect(wrapper.text()).not.toContain('已确认')
    expect(wrapper.text()).not.toContain('已支付')
    expect(wrapper.text()).not.toContain('实付')
    expect(wrapper.text()).not.toContain('去支付')
    expect(wrapper.find('.timeline-step.current h3').text()).toBe('等待场馆确认')
    wrapper.unmount()
  })

  it('keeps the pay entry on the existing order payment route and the expiry hint', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      status: 'PENDING_PAYMENT',
      orderId: 301,
      payExpireTime: '2026-07-03T09:45:00'
    }))
    const wrapper = await mountView()

    const payLink = wrapper.get('[data-test="result-to-pay"]')
    expect(payLink.text()).toBe('去支付')
    expect(payLink.attributes('href')).toBe('/me/orders/301/pay')
    expect(wrapper.get('.help-line').text()).toContain('前完成支付，超时后这次预约会自动释放')
    expect(wrapper.find('.timeline-step.current h3').text()).toBe('审核通过后支付')
    wrapper.unmount()
  })

  it('hides the pay entry for PENDING_PAYMENT without an order and for APPROVED with one', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({ status: 'PENDING_PAYMENT' }))
    const wrapper = await mountView()
    expect(wrapper.find('[data-test="result-to-pay"]').exists()).toBe(false)
    expect(wrapper.find('.help-line').exists()).toBe(false)
    wrapper.unmount()

    reservationApiMock.detail.mockResolvedValue(reservationFixture({ status: 'APPROVED', orderId: 302 }))
    const approved = await mountView()
    expect(approved.find('[data-test="result-to-pay"]').exists()).toBe(false)
    approved.unmount()
  })

  it('advances the step timeline for paid and completed reservations', async () => {
    for (const status of ['PAID', 'COMPLETED']) {
      reservationApiMock.detail.mockResolvedValue(reservationFixture({ status }))
      const wrapper = await mountView()
      expect(wrapper.findAll('.timeline-step')).toHaveLength(3)
      expect(wrapper.find('.timeline-step.current h3').text()).toBe('带着好心情到场')
      wrapper.unmount()
    }
  })

  it('uses the amount fallback chain and two-decimal formatting when the basis is missing', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      status: 'PENDING_REVIEW',
      payableAmount: undefined,
      amount: 96.5
    }))
    const wrapper = await mountView()
    expect(footerSpans(wrapper)[1].text()).toBe('预计应付 ¥96.50')
    wrapper.unmount()

    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      status: 'PAID',
      paidAmount: undefined,
      payableAmount: 96.5
    }))
    const paid = await mountView()
    // 标签与数字同源：没有真实支付金额时不宣称「实付」，回到应付口径
    expect(footerSpans(paid)[1].text()).toBe('预计应付 ¥96.50')
    paid.unmount()
  })

  it('shows the venue reject reason only when the response carries one', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      status: 'REJECTED',
      rejectReason: '该时段已被临时活动占用，建议改约周末。'
    }))
    const wrapper = await mountView()

    const reason = wrapper.get('.inline-error')
    expect(reason.attributes('role')).toBe('alert')
    expect(reason.text()).toContain('场馆给出的原因')
    expect(reason.get('span').text()).toBe('该时段已被临时活动占用，建议改约周末。')
    wrapper.unmount()

    reservationApiMock.detail.mockResolvedValue(reservationFixture({ status: 'REJECTED' }))
    const withoutReason = await mountView()
    expect(withoutReason.find('.inline-error').exists()).toBe(false)
    withoutReason.unmount()
  })
})

describe('ShiguangReservationResultView 预约编号与资源分配', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeMock.params = { id: '20501' }
    venueApiMock.spaces.mockRejectedValue(new Error('spaces not mocked in this case'))
    fetchSpaceNameMock.mockResolvedValue('')
    reservationApiMock.detail.mockResolvedValue(reservationFixture())
  })

  it('prints the serial from the response id instead of any fixed demo number', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({ id: 20501 }))
    const wrapper = await mountView()
    expect(wrapper.get('.sheet-serial').text()).toBe('预约编号 20501')
    wrapper.unmount()

    reservationApiMock.detail.mockResolvedValue(reservationFixture({ id: 41009 }))
    const other = await mountView()
    expect(other.get('.sheet-serial').text()).toBe('预约编号 41009')
    expect(other.text()).not.toContain('预约编号 20501')
    other.unmount()
  })

  it('renders the participant count for the CAPACITY mode', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      participantCount: 4,
      allocationSnapshotJson: JSON.stringify({
        resourceCategory: 'SPORTS_FIELD',
        bookingMode: 'CAPACITY',
        ruleId: 12,
        ruleVersion: 3,
        participantCount: 2,
        seats: []
      })
    }))
    const wrapper = await mountView()

    expect(resourceLine(wrapper)).toContain('场地 · 4 人同行')
    wrapper.unmount()
  })

  it('falls back to the snapshot participant count and to a single companion', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      participantCount: undefined,
      allocationSnapshotJson: JSON.stringify({ bookingMode: 'CAPACITY', participantCount: 3 })
    }))
    const wrapper = await mountView()
    expect(resourceLine(wrapper)).toContain('场地 · 3 人同行')
    wrapper.unmount()

    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      participantCount: undefined,
      allocationSnapshotJson: JSON.stringify({ bookingMode: 'CAPACITY' })
    }))
    const single = await mountView()
    expect(single.text()).toContain('场地 · 1 人同行')
    single.unmount()
  })

  it('lists the assigned seat labels for the ASSIGNED_SEAT mode', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      allocationSnapshotJson: JSON.stringify({
        bookingMode: 'ASSIGNED_SEAT',
        seats: [
          { seatId: 1, seatCode: 'A-01', seatLabel: 'A 区 1 号' },
          { seatId: 2, seatCode: 'A-02', seatLabel: 'A 区 2 号' },
          { seatId: 3, seatCode: 'B-07' }
        ]
      })
    }))
    const wrapper = await mountView()

    expect(resourceLine(wrapper)).toContain('场地 · 座位 A 区 1 号、A 区 2 号、B-07')
    wrapper.unmount()
  })

  it('keeps the assigned-seat wording when the snapshot carries no usable seat labels', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      allocationSnapshotJson: JSON.stringify({ bookingMode: 'ASSIGNED_SEAT', seats: [{ seatId: 9 }] })
    }))
    const wrapper = await mountView()
    expect(resourceLine(wrapper)).toContain('场地 · 指定座位')
    wrapper.unmount()
  })

  it('prefers the reservation bookingModeSnapshot over the allocation snapshot', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      bookingModeSnapshot: 'CAPACITY',
      participantCount: 6,
      allocationSnapshotJson: JSON.stringify({ bookingMode: 'EXCLUSIVE', seats: [] })
    }))
    const wrapper = await mountView()
    expect(resourceLine(wrapper)).toContain('场地 · 6 人同行')
    wrapper.unmount()
  })

  it('survives a malformed allocationSnapshotJson without crashing or blanking the page', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      allocationSnapshotJson: '{"bookingMode":"CAPACITY","seats":['
    }))
    const wrapper = await mountView()

    expect(wrapper.find('.result-page').exists()).toBe(true)
    expect(wrapper.find('[data-test="result-status"]').exists()).toBe(true)
    expect(resourceLine(wrapper)).toContain('场地 · 整空间独立使用')
    expect(wrapper.text()).not.toContain('NaN')
    wrapper.unmount()
  })

  it('survives an allocation snapshot that parses to null, a scalar or an array', async () => {
    for (const raw of ['null', '"CAPACITY"', '[]', '0', '{}']) {
      reservationApiMock.detail.mockResolvedValue(reservationFixture({ allocationSnapshotJson: raw }))
      const wrapper = await mountView()
      expect(wrapper.find('.result-page').exists()).toBe(true)
      expect(resourceLine(wrapper)).toContain('场地 · 整空间独立使用')
      wrapper.unmount()
    }
  })

  it('falls back to the exclusive wording when the booking mode is unknown', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      allocationSnapshotJson: JSON.stringify({ bookingMode: 'FUTURE_MODE' })
    }))
    const wrapper = await mountView()
    expect(resourceLine(wrapper)).toContain('场地 · 整空间独立使用')
    wrapper.unmount()
  })

  it('degrades the date, time and duration copy when the payload carries unusable timestamps', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      startTime: 'not-a-time',
      endTime: 'not-a-time'
    }))
    const wrapper = await mountView()

    const when = wrapper.get('.sheet-when')
    expect(when.text()).toContain('not-a-time')
    expect(when.find('strong').text()).toBe('not-a-time')
    expect(when.text()).toContain('这段时间，留给喜欢。')
    expect(wrapper.text()).not.toContain('NaN')
    wrapper.unmount()

    reservationApiMock.detail.mockResolvedValue(reservationFixture({
      startTime: undefined,
      endTime: undefined
    }))
    const empty = await mountView()
    expect(empty.get('.sheet-when').text()).toContain('时间待定')
    empty.unmount()
  })
})

describe('ShiguangReservationResultView 错误态与出口', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeMock.params = { id: '20501' }
    venueApiMock.spaces.mockRejectedValue(new Error('spaces not mocked in this case'))
    fetchSpaceNameMock.mockResolvedValue('')
    reservationApiMock.detail.mockResolvedValue(reservationFixture())
  })

  const expectReadableError = (wrapper: Awaited<ReturnType<typeof mountView>>, headline: string, hint: string) => {
    const alert = wrapper.get('.state-message')
    expect(alert.attributes('role')).toBe('alert')
    expect(alert.get('h3').text()).toBe(headline)
    expect(alert.get('p').text()).toBe(hint)
    expect(wrapper.find('[data-test="result-retry"]').exists()).toBe(true)
    // 失败绝不能被渲染成空态或成功态
    expect(wrapper.find('.booking-loading').exists()).toBe(false)
    expect(wrapper.find('.result-page').exists()).toBe(false)
    expect(wrapper.find('[data-test="result-status"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="result-headline"]').exists()).toBe(false)
    expect(wrapper.find('.reservation-sheet').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('预约编号 20501')
    expect(wrapper.text()).not.toContain('下一段时间，还没写下。')
    expect(wrapper.text()).not.toContain('去支付')
  }

  it('reads a 404 as a missing reservation, not as an empty account', async () => {
    reservationApiMock.detail.mockRejectedValue({ response: { status: 404 } })
    const wrapper = await mountView()

    expectReadableError(wrapper, '找不到这条预约。', '它可能已被删除，或链接不属于当前账号。')
    wrapper.unmount()
  })

  it('reads a 403 as an ownership problem and never shows another account data', async () => {
    reservationApiMock.detail.mockRejectedValue({ response: { status: 403 } })
    const wrapper = await mountView()

    expectReadableError(wrapper, '这条预约不属于当前账号。', '请登录后查看你自己的预约记录。')
    wrapper.unmount()
  })

  it('reads any other status code as a retryable load failure', async () => {
    reservationApiMock.detail.mockRejectedValue({ response: { status: 502 } })
    const wrapper = await mountView()

    expectReadableError(wrapper, '预约结果暂时无法加载。', '网络或服务异常时不会显示为「没有预约」，请稍后重试。')
    wrapper.unmount()
  })

  it('reads a network error without a response the same retryable way', async () => {
    reservationApiMock.detail.mockRejectedValue(new Error('Network Error'))
    const wrapper = await mountView()

    expectReadableError(wrapper, '预约结果暂时无法加载。', '网络或服务异常时不会显示为「没有预约」，请稍后重试。')
    wrapper.unmount()
  })

  it.each([['abc'], ['0'], ['-8'], ['']])('rejects the invalid route id %s without calling the detail api', async (id) => {
    routeMock.params = { id }
    const wrapper = mount(ShiguangReservationResultView, { global: { stubs } })
    await flushPromises()

    expect(reservationApiMock.detail).not.toHaveBeenCalled()
    expectReadableError(wrapper, '这条预约记录无法识别。', '链接里的预约编号不完整，请从「我的预约」重新进入。')
    wrapper.unmount()
  })

  it('offers a working retry that reloads the same reservation id', async () => {
    reservationApiMock.detail.mockRejectedValueOnce({ response: { status: 500 } })
    const wrapper = await mountView()
    expectReadableError(wrapper, '预约结果暂时无法加载。', '网络或服务异常时不会显示为「没有预约」，请稍后重试。')

    await wrapper.get('[data-test="result-retry"]').trigger('click')
    await flushPromises()

    expect(reservationApiMock.detail).toHaveBeenCalledTimes(2)
    expect(reservationApiMock.detail).toHaveBeenLastCalledWith(20501)
    expect(wrapper.find('.state-message').exists()).toBe(false)
    expect(wrapper.find('.result-page').exists()).toBe(true)
    wrapper.unmount()
  })

  it('links the error state back to the reservation list', async () => {
    reservationApiMock.detail.mockRejectedValue({ response: { status: 404 } })
    const wrapper = await mountView()

    expect(linkByText(wrapper, '查看我的预约')?.attributes('href')).toBe('/me/reservations')
    wrapper.unmount()
  })

  it('wires the three result exits to my time, the venue detail and the venue catalogue', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({ venueId: 12 }))
    const wrapper = await mountView()

    expect(wrapper.get('[data-test="result-to-time"]').attributes('href')).toBe('/me/time')
    expect(wrapper.get('.breadcrumb .back-link').attributes('href')).toBe('/me/time')
    expect(wrapper.get('[data-test="result-back-venue"]').attributes('href')).toBe('/venues/12')
    expect(linkByText(wrapper, '再看看其他地方')?.attributes('href')).toBe('/venues')
    wrapper.unmount()
  })

  it('falls back to the venue catalogue when the reservation carries no venue id', async () => {
    reservationApiMock.detail.mockResolvedValue(reservationFixture({ venueId: undefined }))
    const wrapper = await mountView()

    expect(wrapper.get('[data-test="result-back-venue"]').attributes('href')).toBe('/venues')
    wrapper.unmount()
  })
})
