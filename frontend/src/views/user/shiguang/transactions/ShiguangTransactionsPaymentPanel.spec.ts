import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const orderApiMock = vi.hoisted(() => ({ my: vi.fn(), createPaymentIntent: vi.fn(), paymentIntent: vi.fn(), simulateDevPayment: vi.fn(), applyRefund: vi.fn() }))
const reservationApiMock = vi.hoisted(() => ({ my: vi.fn(), detail: vi.fn() }))
const storedValueApiMock = vi.hoisted(() => ({ paymentOption: vi.fn(), payOrder: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('@/api/order', () => ({ orderApi: orderApiMock }))
vi.mock('@/api/reservation', () => ({ reservationApi: reservationApiMock }))
vi.mock('@/api/storedvalue', () => ({ storedValueApi: storedValueApiMock, storedValueAdminApi: {} }))
vi.mock('@/api/venue', () => ({ venueApi: { spaces: vi.fn().mockResolvedValue([]) } }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => ({ params: {}, query: {} }) }))

import ShiguangTransactionsPaymentPanel from './ShiguangTransactionsPaymentPanel.vue'

const stubs = {
  'router-link': { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>' }
}

const reservationFixture = {
  id: 1073, userId: 7, venueId: 1, spaceId: 73, venueName: '北岸生活场馆',
  startTime: '2026-09-13T19:00:00', endTime: '2026-09-13T20:00:00',
  status: 'PENDING_PAYMENT', orderId: 2073
}
const orderFixture = {
  id: 2073, orderNo: 'O-0912-072', reservationId: 1073, userId: 7, venueId: 1,
  totalAmount: 120, originalAmount: 140, couponDiscountAmount: 20,
  paidAmount: 0, status: 'PENDING_PAYMENT', expireTime: '2026-09-12T10:30:00'
}
const intentFixture = {
  paymentNo: 'P-0912-072', orderId: 2073, orderNo: 'O-0912-072',
  providerEnv: 'DEV', providerName: 'DEV_PAYMENT', configVersion: '1',
  status: 'CREATED', payableAmount: 120, paidAmount: 0, feeAmount: 0,
  netAmount: 120, currency: 'CNY', externalRef: '', expireTime: '2026-09-12T10:30:00',
  requestSummaryJson: '', responseSummaryJson: '',
  paymentAction: { type: 'DEV_SIMULATION' }
}

const pageOf = <T,>(records: T[]) => ({ records, total: records.length, pageNum: 1, pageSize: 100 })

describe('ShiguangTransactionsPaymentPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    reservationApiMock.my.mockResolvedValue(pageOf([reservationFixture]))
    reservationApiMock.detail.mockResolvedValue(reservationFixture)
    orderApiMock.my.mockResolvedValue(pageOf([orderFixture]))
  })

  it('renders the ready checkout with amount summary and reference copy', async () => {
    orderApiMock.createPaymentIntent.mockResolvedValue(intentFixture)
    storedValueApiMock.paymentOption.mockRejectedValue(new Error('not enabled'))
    const wrapper = mount(ShiguangTransactionsPaymentPanel, {
      props: { reservationId: 1073, embedded: true },
      global: { stubs }
    })
    await flushPromises()
    expect(wrapper.find('[data-test="payment-order-no"]').text()).toBe('O-0912-072')
    expect(wrapper.text()).toContain('核对付款金额')
    expect(wrapper.text()).toContain('已应用优惠')
    expect(wrapper.text()).toContain('− ¥20.00')
    expect(wrapper.text()).toContain('订单金额已经包含预约时应用的优惠，收银处不再次计算或叠加优惠。')
    expect(wrapper.text()).toContain('P-0912-072')
    expect(wrapper.text()).toContain('截止 9 月 12 日 10:30')
    expect(wrapper.find('[data-test="confirm-pay"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('confirms a DEV simulated payment and emits settled on success', async () => {
    orderApiMock.createPaymentIntent.mockResolvedValue(intentFixture)
    storedValueApiMock.paymentOption.mockRejectedValue(new Error('not enabled'))
    orderApiMock.simulateDevPayment.mockResolvedValue({
      paymentNo: 'P-0912-072', orderId: 2073, status: 'SUCCEEDED', orderStatus: 'PAID'
    })
    orderApiMock.my.mockResolvedValueOnce(pageOf([orderFixture]))
      .mockResolvedValue(pageOf([{ ...orderFixture, status: 'PAID', paidAmount: 120 }]))
    const wrapper = mount(ShiguangTransactionsPaymentPanel, {
      props: { reservationId: 1073, embedded: true },
      global: { stubs }
    })
    await flushPromises()
    await wrapper.find('[data-test="confirm-pay"]').trigger('click')
    await flushPromises()
    expect(orderApiMock.simulateDevPayment).toHaveBeenCalledWith('P-0912-072', { scenario: 'SUCCESS' }, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    expect(wrapper.find('[data-test="payment-to-credential"]').exists()).toBe(true)
    expect(wrapper.emitted('settled')).toHaveLength(1)
    wrapper.unmount()
  })

  it('switches to the unknown state without re-initiating payment when the confirm response is lost', async () => {
    orderApiMock.createPaymentIntent.mockResolvedValue(intentFixture)
    storedValueApiMock.paymentOption.mockRejectedValue(new Error('not enabled'))
    orderApiMock.simulateDevPayment.mockRejectedValue(new Error('network reset'))
    const wrapper = mount(ShiguangTransactionsPaymentPanel, {
      props: { reservationId: 1073, embedded: true },
      global: { stubs }
    })
    await flushPromises()
    await wrapper.find('[data-test="confirm-pay"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('付款结果尚不确定')
    expect(wrapper.text()).toContain('核对原支付单')
    expect(wrapper.text()).toContain('结果确认前暂不重新发起付款。')
    expect(orderApiMock.simulateDevPayment).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('recovers the original payment result and shows the paid state', async () => {
    orderApiMock.createPaymentIntent.mockResolvedValue(intentFixture)
    storedValueApiMock.paymentOption.mockRejectedValue(new Error('not enabled'))
    orderApiMock.simulateDevPayment.mockRejectedValue(new Error('network reset'))
    orderApiMock.paymentIntent.mockResolvedValue({ ...intentFixture, status: 'SUCCEEDED', paidAmount: 120 })
    orderApiMock.my.mockResolvedValueOnce(pageOf([orderFixture]))
      .mockResolvedValue(pageOf([{ ...orderFixture, status: 'PAID', paidAmount: 120 }]))
    const wrapper = mount(ShiguangTransactionsPaymentPanel, {
      props: { reservationId: 1073, embedded: true },
      global: { stubs }
    })
    await flushPromises()
    await wrapper.find('[data-test="confirm-pay"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="recover-payment"]').trigger('click')
    await flushPromises()
    expect(orderApiMock.paymentIntent).toHaveBeenCalledWith('P-0912-072', { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    expect(wrapper.text()).toContain('付款已确认')
    expect(wrapper.emitted('settled')).toHaveLength(1)
    wrapper.unmount()
  })

  it('shows the failed-attempt notice with a fresh intent action after a terminal result', async () => {
    orderApiMock.createPaymentIntent.mockResolvedValue({ ...intentFixture, status: 'FAILED' })
    storedValueApiMock.paymentOption.mockRejectedValue(new Error('not enabled'))
    orderApiMock.createPaymentIntent.mockResolvedValueOnce({ ...intentFixture, status: 'FAILED' })
    const wrapper = mount(ShiguangTransactionsPaymentPanel, {
      props: { reservationId: 1073, embedded: true },
      global: { stubs }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('本次付款未完成')
    expect(wrapper.find('[data-test="new-payment"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('renders the paid state for an already settled order with the credential next step', async () => {
    orderApiMock.my.mockResolvedValue(pageOf([{ ...orderFixture, status: 'PAID', paidAmount: 120 }]))
    const wrapper = mount(ShiguangTransactionsPaymentPanel, {
      props: { orderId: 2073 },
      global: { stubs }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('付款已确认')
    expect(wrapper.text()).toContain('订单状态已更新为已支付，接下来可以准备到场。')
    expect(wrapper.find('[data-test="payment-to-credential"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('offers stored-value full payment when the option is available', async () => {
    orderApiMock.createPaymentIntent.mockResolvedValue(intentFixture)
    storedValueApiMock.paymentOption.mockResolvedValue({
      orderId: 2073, amount: 120, currency: 'CNY', available: true,
      product: { id: 7, productNo: 'SVP-1', productName: '储值账户', principalPrice: 100, bonusAmount: 0, status: 'ACTIVE' },
      account: { accountId: 1, balanceAmount: 500 }, identityVerified: true
    })
    const wrapper = mount(ShiguangTransactionsPaymentPanel, {
      props: { reservationId: 1073, embedded: true },
      global: { stubs }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('储值支付')
    expect(wrapper.text()).toContain('不与外部支付混付，本金与赠送分摊由服务记录。')
    await wrapper.find('[data-test="method-stored"]').setValue()
    await wrapper.find('[data-test="confirm-pay"]').trigger('click')
    await flushPromises()
    expect(storedValueApiMock.payOrder).toHaveBeenCalledWith(2073, expect.objectContaining({ productId: 7, idempotencyKey: expect.any(String) }), { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    wrapper.unmount()
  })
})
