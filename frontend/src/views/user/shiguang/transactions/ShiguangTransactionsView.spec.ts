import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const reservationApiMock = vi.hoisted(() => ({
  my: vi.fn(),
  cancel: vi.fn(),
  rescheduleSlots: vi.fn(),
  requestReschedule: vi.fn(),
  myRescheduleRequests: vi.fn()
}))
const orderApiMock = vi.hoisted(() => ({ my: vi.fn(), applyRefund: vi.fn(), createPaymentIntent: vi.fn(), paymentIntent: vi.fn(), simulateDevPayment: vi.fn() }))
const checkinApiMock = vi.hoisted(() => ({ my: vi.fn(), reissueCredential: vi.fn() }))
const feedbackApiMock = vi.hoisted(() => ({ myComplaints: vi.fn() }))
const storedValueApiMock = vi.hoisted(() => ({ orderStoredStatus: vi.fn(), paymentOption: vi.fn(), payOrder: vi.fn() }))
const venueApiMock = vi.hoisted(() => ({ spaces: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({
  hash: '' as string,
  name: 'MyReservations' as string,
  params: {},
  query: {} as Record<string, unknown>
}))
const menuPermissions = vi.hoisted(() => ({ value: new Set<string>() }))

vi.mock('@/api/reservation', () => ({ reservationApi: reservationApiMock }))
vi.mock('@/api/order', () => ({ orderApi: orderApiMock }))
vi.mock('@/api/checkin', () => ({ checkinApi: checkinApiMock }))
vi.mock('@/api/feedback', () => ({ feedbackApi: feedbackApiMock }))
vi.mock('@/api/storedvalue', () => ({ storedValueApi: storedValueApiMock, storedValueAdminApi: {} }))
vi.mock('@/api/venue', () => ({ venueApi: venueApiMock }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock }))
vi.mock('@/stores/menu', () => ({ useMenuStore: () => ({ hasPermission: (key: string) => menuPermissions.value.has(key) }) }))

const stubs = {
  'router-link': { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>' },
  ShiguangTransactionsPaymentPanel: { props: ['reservationId', 'embedded'], template: '<section class="panel-stub" />' }
}

import ShiguangTransactionsView from './ShiguangTransactionsView.vue'

// jsdom 未实现原生 dialog 的 showModal，与 member 系列 spec 相同的桩
HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) { this.open = true })

const reservationFixture = (overrides: Record<string, unknown> = {}) => ({
  id: 1073,
  userId: 7,
  venueId: 1,
  spaceId: 73,
  venueName: '北岸生活场馆',
  startTime: '2026-09-13T19:00:00',
  endTime: '2026-09-13T20:00:00',
  status: 'PAID',
  contactName: '小林',
  contactPhone: '13800000018',
  totalAmount: 80,
  orderId: 2073,
  ...overrides
})

const orderFixture = (overrides: Record<string, unknown> = {}) => ({
  id: 2073,
  orderNo: 'O-0912-073',
  reservationId: 1073,
  userId: 7,
  venueId: 1,
  totalAmount: 80,
  originalAmount: 80,
  paidAmount: 80,
  status: 'PAID',
  ...overrides
})

const lifecycleFixture = (overrides: Record<string, unknown> = {}) => ({
  reservationId: 1073,
  credentialStatus: 'ISSUED',
  checkinStatus: 'NOT_CHECKED_IN',
  checkinCodeLast4: '4821',
  checkinWindowStart: '2026-09-13T18:45:00',
  checkinWindowEnd: '2026-09-13T20:15:00',
  ...overrides
})

function pageOf<T>(records: T[]) {
  return { records, total: records.length, pageNum: 1, pageSize: 100 }
}

function mockPermissions(keys: string[]): void {
  menuPermissions.value = new Set(keys)
}

function primeApiMocks(): void {
  reservationApiMock.my.mockResolvedValue(pageOf([reservationFixture()]))
  reservationApiMock.myRescheduleRequests.mockResolvedValue(pageOf([]))
  orderApiMock.my.mockResolvedValue(pageOf([orderFixture()]))
  checkinApiMock.my.mockResolvedValue([lifecycleFixture()])
  feedbackApiMock.myComplaints.mockResolvedValue(pageOf([]))
  venueApiMock.spaces.mockResolvedValue([{ id: 73, venueId: 1, name: '羽毛球 2 号场', type: 'SPORTS', pricePerHour: 80 }])
}

async function mountView(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(ShiguangTransactionsView, {
    global: { stubs },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

describe('ShiguangTransactionsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeMock.hash = ''
    routeMock.name = 'MyReservations'
    mockPermissions(['reservation:my', 'order:my', 'reservation:checkin:me'])
    primeApiMocks()
  })

  it('renders the reservations tab with hero card, todo links and reference copy', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('每一段安排，都有下一步。')
    expect(wrapper.text()).toContain('下一次到场')
    expect(wrapper.text()).toContain('北岸生活场馆')
    expect(wrapper.text()).toContain('羽毛球 2 号场')
    expect(wrapper.text()).toContain('我的时间')
    expect(wrapper.text()).toContain('还有这些待办')
    expect(wrapper.findAll('.d-booking-row').length).toBe(1)
    expect(wrapper.text()).toContain('预约 #1073')
    wrapper.unmount()
  })

  it('renders order tab rows with order amounts and refund status from G-T1 summary', async () => {
    routeMock.hash = '#orders'
    const refunding = reservationFixture({
      id: 1074, orderId: 2074, status: 'REFUNDING', startTime: '2026-09-14T09:00:00', endTime: '2026-09-14T10:00:00', venueName: '东园运动中心'
    })
    const refundOrder = orderFixture({
      id: 2074, orderNo: 'O-0912-074', reservationId: 1074, status: 'REFUNDING',
      refundSummary: { refundId: 31, refundNo: 'RF-0912-074', refundAmount: 100, status: 'REFUNDING', userVisibleReason: '行程调整' }
    })
    reservationApiMock.my.mockResolvedValue(pageOf([reservationFixture(), refunding]))
    orderApiMock.my.mockResolvedValue(pageOf([orderFixture(), refundOrder]))
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('订单与申请记录')
    expect(wrapper.text()).toContain('O-0912-073')
    expect(wrapper.text()).toContain('¥80.00')
    expect(wrapper.text()).toContain('退款中')
    wrapper.unmount()
  })

  it('shows permission states when order or credential permissions are missing', async () => {
    routeMock.hash = '#orders'
    mockPermissions(['reservation:my'])
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('尚未获得本人订单权限')
    expect(wrapper.text()).toContain('可以继续查看预约记录；订单金额与付款动作不展示。')
    wrapper.unmount()
  })

  it('opens the booking detail from the hash deep link with statuses and actions', async () => {
    routeMock.hash = '#booking-1073'
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('本人预约')
    expect(wrapper.text()).toContain('预约：已支付')
    expect(wrapper.text()).toContain('订单：已支付')
    expect(wrapper.text()).toContain('查看到场凭证')
    expect(wrapper.text()).toContain('未支付预约可申请改期，原时段在审核通过前保留。已支付预约暂不支持改期。')
    expect(wrapper.text()).toContain('申请全额退款')
    wrapper.unmount()
  })

  it('renders the in-page payment panel from the #pay- deep link', async () => {
    routeMock.hash = '#pay-1073'
    reservationApiMock.my.mockResolvedValue(pageOf([reservationFixture({ status: 'PENDING_PAYMENT' })]))
    orderApiMock.my.mockResolvedValue(pageOf([orderFixture({ status: 'PENDING_PAYMENT', paidAmount: 0 })]))
    const wrapper = await mountView()
    expect(wrapper.find('.panel-stub').exists()).toBe(true)
    expect(wrapper.text()).toContain('先核对，再完成付款。')
    wrapper.unmount()
  })

  it('submits the cancel command with the dialog reason and reloads', async () => {
    routeMock.hash = '#booking-1073'
    reservationApiMock.cancel.mockResolvedValue(undefined)
    const wrapper = await mountView()
    await wrapper.find('[data-test="open-cancel"]').trigger('click')
    await flushPromises()
    const dialog = document.querySelector('dialog')
    expect(dialog).toBeTruthy()
    expect(dialog!.textContent).toContain('取消这条预约')
    const reason = dialog!.querySelector('[data-test="cancel-reason"]') as HTMLTextAreaElement
    reason.value = '行程有变'
    reason.dispatchEvent(new Event('input'))
    await flushPromises()
    ;(dialog!.querySelector('[data-test="confirm-cancel"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(reservationApiMock.cancel).toHaveBeenCalledWith(1073, '行程有变', { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    wrapper.unmount()
  })

  it('keeps dialog input and shows the server message when cancel is rejected', async () => {
    routeMock.hash = '#booking-1073'
    reservationApiMock.cancel.mockRejectedValue({
      response: { data: { message: '已超过免费取消时间，请走退款申请' } }
    })
    const wrapper = await mountView()
    await wrapper.find('[data-test="open-cancel"]').trigger('click')
    await flushPromises()
    const dialog = document.querySelector('dialog')!
    const reason = dialog.querySelector('[data-test="cancel-reason"]') as HTMLTextAreaElement
    reason.value = '临时有事'
    reason.dispatchEvent(new Event('input'))
    await flushPromises()
    ;(dialog.querySelector('[data-test="confirm-cancel"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(dialog.textContent).toContain('已超过免费取消时间，请走退款申请')
    expect(reservationApiMock.cancel).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('claims the full credential code via reissue and shows it once in-page', async () => {
    // 凭证窗口在固件里是 2026-09-13 18:45-20:15；真实时间越过窗口后该用例会假性失败（BATCH-02 S04 基线修复），与下方过期用例一致地钉住时钟。
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-13T19:00:00'))
    try {
      routeMock.hash = '#credential-1073'
      checkinApiMock.reissueCredential.mockResolvedValue({ ...lifecycleFixture(), checkinCode: '674821' })
      const wrapper = await mountView()
      expect(wrapper.find('[data-test="credential-code"]').text()).toBe('尾号 4821')
      await wrapper.find('[data-test="credential-claim"]').trigger('click')
      await flushPromises()
      const dialog = document.querySelector('dialog')!
      ;(dialog.querySelector('[data-test="confirm-credential-update"]') as HTMLButtonElement).click()
      await flushPromises()
      expect(checkinApiMock.reissueCredential).toHaveBeenCalledWith(1073, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
      expect(wrapper.find('[data-test="credential-code"]').text()).toBe('674821')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('marks expired credentials as invalid without a claim action', async () => {
    routeMock.hash = '#credential-1073'
    checkinApiMock.my.mockResolvedValue([lifecycleFixture({
      checkinWindowEnd: '2026-09-13T20:15:00',
      checkinWindowStart: '2026-09-13T18:45:00'
    })])
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-14T10:00:00'))
    try {
      const wrapper = await mountView()
      expect(wrapper.find('[data-test="credential-code"]').text()).toBe('已失效')
      expect(wrapper.text()).toContain('凭证已过期')
      expect(wrapper.find('[data-test="credential-claim"]').exists()).toBe(false)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('renders the empty state with a real next step when no reservations exist', async () => {
    reservationApiMock.my.mockResolvedValue(pageOf([]))
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('这里还没有预约记录')
    expect(wrapper.text()).toContain('去看看场馆')
    wrapper.unmount()
  })

  it('shows the error state with retry when reads fail', async () => {
    reservationApiMock.my.mockRejectedValue(new Error('network down'))
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('暂时读不到这段安排')
    expect(wrapper.text()).toContain('重新读取')
    reservationApiMock.my.mockResolvedValue(pageOf([reservationFixture()]))
    await wrapper.findAll('button').find(b => b.text() === '重新读取')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('你的预约记录')
    wrapper.unmount()
  })

  // F-23：付款入口写入的 hash 曾是 #payment-<id>，而 parseHash 与已登记深链合同只认 #pay-<id>，
  // 三个页内付款入口点开后仍停留在列表（DEV 付款无法进入）。放在最后，失败时不泄漏已挂载节点。
  it('pushes the payment deep link with the #pay- prefix the parser reads', async () => {
    routeMock.hash = '#orders'
    reservationApiMock.my.mockResolvedValue(pageOf([reservationFixture({ status: 'PENDING_PAYMENT' })]))
    orderApiMock.my.mockResolvedValue(pageOf([orderFixture({ status: 'PENDING_PAYMENT', paidAmount: 0 })]))
    const wrapper = await mountView()
    await wrapper.find('[data-test="row-payment"]').trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith({ hash: '#pay-1073' })
    wrapper.unmount()
  })
})
