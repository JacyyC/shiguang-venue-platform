import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn()
}))

vi.mock('./http', () => ({
  default: httpMock
}))

describe('paymentAdminApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('calls B6 payment admin endpoints', async () => {
    const { paymentAdminApi } = await import('./payment')

    httpMock.get.mockResolvedValueOnce({ successAmount: 100 })
    httpMock.get.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 20 })
    httpMock.post.mockResolvedValue({ matchedCount: 1, differenceCount: 0 })
    httpMock.put.mockResolvedValue(undefined)

    await paymentAdminApi.providers()
    await paymentAdminApi.overview({ providerEnv: 'DEV' })
    await paymentAdminApi.attempts({ providerEnv: 'DEV', pageNum: 1 })
    await paymentAdminApi.callbacks({ providerEnv: 'DEV', pageNum: 1 })
    await paymentAdminApi.runReconciliation({
      reconcileDate: '2026-07-04',
      providerEnv: 'DEV',
      providerName: 'DEV_PAYMENT'
    })
    await paymentAdminApi.reconciliationItems({ providerEnv: 'DEV', status: 'DIFFERENCE' })
    await paymentAdminApi.adjustReconciliation(8, {
      adjustmentAmount: -2,
      adjustmentReason: 'DEV 对账差异调整'
    })

    expect(httpMock.get).toHaveBeenCalledWith('/admin/payments/providers')
    expect(httpMock.get).toHaveBeenCalledWith('/admin/payments/overview', { params: { providerEnv: 'DEV' } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/payments/attempts', { params: { providerEnv: 'DEV', pageNum: 1 } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/payments/callbacks', { params: { providerEnv: 'DEV', pageNum: 1 } })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/finance/reconciliation/run', {
      reconcileDate: '2026-07-04',
      providerEnv: 'DEV',
      providerName: 'DEV_PAYMENT'
    })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/finance/reconciliation/items', {
      params: { providerEnv: 'DEV', status: 'DIFFERENCE' }
    })
    expect(httpMock.put).toHaveBeenCalledWith('/admin/finance/reconciliation/items/8/adjust', {
      adjustmentAmount: -2,
      adjustmentReason: 'DEV 对账差异调整'
    })
  })
})
