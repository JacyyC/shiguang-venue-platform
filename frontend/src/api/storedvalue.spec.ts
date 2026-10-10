import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn()
}))

vi.mock('./http', () => ({ default: httpMock }))

describe('storedValueApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('keeps the user endpoints scoped to the authenticated stored-value resource', async () => {
    const { storedValueApi } = await import('./storedvalue')
    await storedValueApi.self()
    await storedValueApi.bonusBatches()
    await storedValueApi.recharges({ pageNum: 2, pageSize: 10 })
    await storedValueApi.createRecharge({
      productId: 7,
      principalAmount: 100,
      currency: 'CNY',
      clientRequestId: 'client-1',
      providerKey: 'stored-value.dev'
    })
    await storedValueApi.confirmDevCallback('SVR-1', 'SUCCESS')
    await storedValueApi.ledger({ pageNum: 1, pageSize: 10 })
    await storedValueApi.paymentOption(11)
    await storedValueApi.payOrder(11, { productId: 7, idempotencyKey: 'web-order-11' })

    expect(httpMock.get).toHaveBeenNthCalledWith(1, '/stored-value/me', undefined)
    expect(httpMock.get).toHaveBeenNthCalledWith(2, '/stored-value/me/bonus-batches', undefined)
    expect(httpMock.get).toHaveBeenNthCalledWith(3, '/stored-value/me/recharges', { params: { pageNum: 2, pageSize: 10 } })
    expect(httpMock.post).toHaveBeenNthCalledWith(1, '/stored-value/me/recharges', {
      productId: 7,
      principalAmount: 100,
      currency: 'CNY',
      clientRequestId: 'client-1',
      providerKey: 'stored-value.dev'
    }, undefined)
    expect(httpMock.post).toHaveBeenNthCalledWith(2, '/stored-value/me/recharges/SVR-1/dev-callback', { status: 'SUCCESS' }, undefined)
    expect(httpMock.get).toHaveBeenNthCalledWith(4, '/stored-value/me/ledger', { params: { pageNum: 1, pageSize: 10 } })
    expect(httpMock.get).toHaveBeenNthCalledWith(5, '/stored-value/me/orders/11/payment-option')
    expect(httpMock.post).toHaveBeenNthCalledWith(3, '/stored-value/me/orders/11/payments', {
      productId: 7,
      idempotencyKey: 'web-order-11'
    }, undefined)
  })

  it('keeps finance reads and high-risk writes on their dedicated admin paths', async () => {
    const { storedValueAdminApi } = await import('./storedvalue')
    await storedValueAdminApi.summary()
    await storedValueAdminApi.products()
    await storedValueAdminApi.providers()
    await storedValueAdminApi.accounts({ pageNum: 1, pageSize: 10 })
    await storedValueAdminApi.recharges({ pageNum: 2, pageSize: 10 })
    await storedValueAdminApi.ledger({ pageNum: 1, pageSize: 10 })
    await storedValueAdminApi.reconciliationRuns({ pageNum: 1, pageSize: 10 })
    await storedValueAdminApi.reconciliationDifferences({ pageNum: 1, pageSize: 10 })
    await storedValueAdminApi.runReconciliation('2026-08-02')
    await storedValueAdminApi.freeze('SVA-ACCOUNT', { reason: '风险复核', businessRef: 'web-freeze-SVA-ACCOUNT' })
    await storedValueAdminApi.unfreeze('SVA-ACCOUNT', { reason: '解冻审批' })
    await storedValueAdminApi.refundRecharge('SVR-1', '人工复核')
    await storedValueAdminApi.recoverPosting('SVR-2', '待入账恢复')
    await storedValueAdminApi.disputeRecharge('SVR-3', 'provider-case-3', 10, '渠道争议')
    await storedValueAdminApi.requestAdjustment('SVD-DIFFERENCE', 10, '平衡调整')
    await storedValueAdminApi.approvals({ pageNum: 1, pageSize: 10 })
    await storedValueAdminApi.approval('SVA-1')
    await storedValueAdminApi.approveApproval('SVA-1', '批准')
    await storedValueAdminApi.rejectApproval('SVA-2', '拒绝')
    await storedValueAdminApi.executeApproval('SVA-1')
    await storedValueAdminApi.revokeApproval('SVA-2', '撤回')

    expect(httpMock.get).toHaveBeenNthCalledWith(1, '/admin/stored-value/summary')
    expect(httpMock.get).toHaveBeenNthCalledWith(2, '/admin/stored-value/products')
    expect(httpMock.get).toHaveBeenNthCalledWith(3, '/admin/stored-value/providers')
    expect(httpMock.get).toHaveBeenNthCalledWith(4, '/admin/stored-value/accounts', { params: { pageNum: 1, pageSize: 10 } })
    expect(httpMock.get).toHaveBeenNthCalledWith(5, '/admin/stored-value/recharges', { params: { pageNum: 2, pageSize: 10 } })
    expect(httpMock.get).toHaveBeenNthCalledWith(6, '/admin/stored-value/ledger', { params: { pageNum: 1, pageSize: 10 } })
    expect(httpMock.get).toHaveBeenNthCalledWith(7, '/admin/stored-value/reconciliation/runs', { params: { pageNum: 1, pageSize: 10 } })
    expect(httpMock.get).toHaveBeenNthCalledWith(8, '/admin/stored-value/reconciliation/differences', { params: { pageNum: 1, pageSize: 10 } })
    expect(httpMock.get).toHaveBeenNthCalledWith(9, '/admin/stored-value/approvals', { params: { pageNum: 1, pageSize: 10 } })
    expect(httpMock.get).toHaveBeenNthCalledWith(10, '/admin/stored-value/approvals/SVA-1')
    expect(httpMock.post).toHaveBeenNthCalledWith(1, '/admin/stored-value/reconciliation/run', { reconcileDate: '2026-08-02' })
    expect(httpMock.post).toHaveBeenNthCalledWith(2, '/admin/stored-value/accounts/SVA-ACCOUNT/freeze', { reason: '风险复核', businessRef: 'web-freeze-SVA-ACCOUNT' })
    expect(httpMock.post).toHaveBeenNthCalledWith(3, '/admin/stored-value/accounts/SVA-ACCOUNT/unfreeze', { reason: '解冻审批' })
    expect(httpMock.post).toHaveBeenNthCalledWith(4, '/admin/stored-value/recharges/SVR-1/refund', { reason: '人工复核' })
    expect(httpMock.post).toHaveBeenNthCalledWith(5, '/admin/stored-value/recharges/SVR-2/posting-recovery', { reason: '待入账恢复' })
    expect(httpMock.post).toHaveBeenNthCalledWith(6, '/admin/stored-value/recharges/SVR-3/disputes', { providerCaseId: 'provider-case-3', amount: 10, reason: '渠道争议' })
    expect(httpMock.post).toHaveBeenNthCalledWith(7, '/admin/stored-value/reconciliation/differences/SVD-DIFFERENCE/adjustment', { amount: 10, reason: '平衡调整' })
    expect(httpMock.post).toHaveBeenNthCalledWith(8, '/admin/stored-value/approvals/SVA-1/approve', { reason: '批准' })
    expect(httpMock.post).toHaveBeenNthCalledWith(9, '/admin/stored-value/approvals/SVA-2/reject', { reason: '拒绝' })
    expect(httpMock.post).toHaveBeenNthCalledWith(10, '/admin/stored-value/approvals/SVA-1/execute')
    expect(httpMock.post).toHaveBeenNthCalledWith(11, '/admin/stored-value/approvals/SVA-2/revoke', { reason: '撤回' })
  })
})
