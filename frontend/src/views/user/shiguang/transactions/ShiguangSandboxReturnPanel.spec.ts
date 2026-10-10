import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const orderApiMock = vi.hoisted(() => ({ my: vi.fn(), paymentIntent: vi.fn(), createPaymentIntent: vi.fn(), simulateDevPayment: vi.fn(), applyRefund: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('@/api/order', () => ({ orderApi: orderApiMock }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => ({ params: {}, query: {} }) }))

import ShiguangSandboxReturnPanel from './ShiguangSandboxReturnPanel.vue'

const stubs = {
  'router-link': { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>' }
}

const intentFixture = {
  paymentNo: 'P-SBX-072', orderId: 2073, orderNo: 'O-0912-072',
  providerEnv: 'SANDBOX', providerName: 'ALIPAY', configVersion: '1',
  status: 'PENDING', payableAmount: 120, paidAmount: 0, feeAmount: 0,
  netAmount: 120, currency: 'CNY', externalRef: 'ext-1', expireTime: '',
  requestSummaryJson: '', responseSummaryJson: '', paymentAction: null
}

describe('ShiguangSandboxReturnPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(window, 'setTimeout').mockImplementation(((fn: () => void) => fn()) as never)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the reference copy and reads the payment no from the sandbox return query', async () => {
    window.history.replaceState(null, '', '/me/payments/alipay/sandbox/return?out_trade_no=P-SBX-072&foo=1')
    orderApiMock.paymentIntent.mockResolvedValue(intentFixture)
    const wrapper = mount(ShiguangSandboxReturnPanel, { global: { stubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('已回到拾光')
    expect(wrapper.text()).toContain('返回信息不作为支付成功凭据。请核对原支付单 P-SBX-072 的当前状态。')
    expect(wrapper.text()).toContain('核对付款结果')
    expect(orderApiMock.paymentIntent).toHaveBeenCalledWith('P-SBX-072')
    wrapper.unmount()
  })

  it('confirms the paid result after recovery and routes back to reservations', async () => {
    window.history.replaceState(null, '', '/me/payments/alipay/sandbox/return?paymentNo=P-SBX-072')
    orderApiMock.paymentIntent.mockResolvedValue({ ...intentFixture, status: 'SUCCEEDED', paidAmount: 120 })
    const wrapper = mount(ShiguangSandboxReturnPanel, { global: { stubs } })
    await flushPromises()
    await wrapper.find('[data-test="recover-payment"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('付款已确认')
    await wrapper.find('[data-test="return-to-reservations"]').trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith('/me/reservations')
    wrapper.unmount()
  })

  it('routes a failed payment back to the cashier with the order id', async () => {
    window.history.replaceState(null, '', '/me/payments/alipay/sandbox/return?paymentNo=P-SBX-072')
    orderApiMock.paymentIntent.mockResolvedValue({ ...intentFixture, status: 'FAILED' })
    const wrapper = mount(ShiguangSandboxReturnPanel, { global: { stubs } })
    await flushPromises()
    await wrapper.find('[data-test="recover-payment"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('这笔付款没有完成')
    await wrapper.find('[data-test="return-to-payment"]').trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith('/me/orders/2073/pay')
    wrapper.unmount()
  })

  it('keeps asking for a manual retry when the payment is still pending', async () => {
    window.history.replaceState(null, '', '/me/payments/alipay/sandbox/return?paymentNo=P-SBX-072')
    orderApiMock.paymentIntent.mockResolvedValue(intentFixture)
    const wrapper = mount(ShiguangSandboxReturnPanel, { global: { stubs } })
    await flushPromises()
    await wrapper.find('[data-test="recover-payment"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('这笔支付仍在处理中，请稍后再核对。')
    wrapper.unmount()
  })

  it('disables recovery when no payment no is present', async () => {
    window.history.replaceState(null, '', '/me/payments/alipay/sandbox/return')
    const wrapper = mount(ShiguangSandboxReturnPanel, { global: { stubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('未读到原支付单号，请从支付记录重新进入核对。')
    expect((wrapper.find('[data-test="recover-payment"]').element as HTMLButtonElement).disabled).toBe(true)
    wrapper.unmount()
  })
})
