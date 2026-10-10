import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn()
}))

vi.mock('./http', () => ({
  default: httpMock
}))

describe('orderApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('locks the user order and payment endpoints (G-T1 contract)', async () => {
    const { orderApi } = await import('./order')

    await orderApi.my({ pageNum: 1, pageSize: 10 })
    expect(httpMock.get).toHaveBeenCalledWith('/orders/my', {
      params: { pageNum: 1, pageSize: 10 }
    })

    await orderApi.createPaymentIntent(501)
    expect(httpMock.post).toHaveBeenCalledWith('/orders/501/payment-intents', undefined, undefined)

    await orderApi.paymentIntent('P-0912-073')
    expect(httpMock.get).toHaveBeenCalledWith('/payments/P-0912-073', undefined)

    await orderApi.simulateDevPayment('P-0912-073', { scenario: 'SUCCESS' })
    expect(httpMock.post).toHaveBeenCalledWith('/payments/dev/P-0912-073/simulate', {
      scenario: 'SUCCESS'
    }, undefined)

    await orderApi.applyRefund(501, '行程调整，无法按时到场')
    expect(httpMock.post).toHaveBeenCalledWith('/orders/501/refunds', {
      reason: '行程调整，无法按时到场'
    }, undefined)
  })

  it('keeps admin order and refund endpoints on the admin contract', async () => {
    const { adminOrderApi } = await import('./order')

    await adminOrderApi.list({ venueId: 3 })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/orders', { params: { venueId: 3 } })

    await adminOrderApi.refunds({ status: 'PENDING_REVIEW' })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/refunds', {
      params: { status: 'PENDING_REVIEW' }
    })

    await adminOrderApi.approveRefund(31, '同意退款')
    expect(httpMock.put).toHaveBeenCalledWith('/admin/refunds/31/approve', { reason: '同意退款' }, undefined)

    await adminOrderApi.rejectRefund(31, '不符合退款条件')
    expect(httpMock.put).toHaveBeenCalledWith('/admin/refunds/31/reject', { reason: '不符合退款条件' }, undefined)

    await adminOrderApi.markRefundSuccess(31)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/refunds/31/success', undefined, undefined)
  })
})
