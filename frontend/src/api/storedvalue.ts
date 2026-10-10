import http, { type HttpRequestConfig, type PageResult } from './http'

export interface StoredValueProduct {
  id: number
  productCode: string
  productName: string
  issuerCode: string
  currency: string
  environment: 'DEV' | 'SANDBOX' | string
  status: string
  rechargeEnabled: boolean
  spendEnabled: boolean
  policyVersion: string
  minimumRecharge: number
  maximumRecharge: number
  dailyRechargeLimit: number
  principalBalanceLimit: number
  totalBalanceLimit: number
  bonusRatePercent: number
  bonusPerRechargeLimit: number
  monthlyBonusLimit: number
  bonusValidDays: number
  refundCompensationValidDays: number
}

export interface StoredValueAccount {
  accountId: number
  userId: number
  productId: number
  status: string
  currency: string
  principalAvailable: number
  principalFrozen: number
  bonusAvailable: number
  bonusFrozen: number
  availableTotal: number
  totalBalance: number
  version: number
}

export interface StoredValueBonusBatch {
  id: number
  rechargeId: number
  originalAmount: number
  availableAmount: number
  frozenAmount: number
  issuedAt: string
  expiresAt: string
  status: string
}

export interface StoredValueSelf {
  product: StoredValueProduct | null
  products: StoredValueProduct[]
  account: StoredValueAccount | null
  identityVerified: boolean
  eligibilityStatus: string
  eligibilityMessage: string
}

export interface StoredValueRecharge {
  id: number
  rechargeNo: string
  productId: number
  status: string
  principalAmount: number
  expectedBonusAmount: number
  actualBonusAmount: number
  currency: string
  providerKey: string
  providerEnv: string
  providerName: string
  providerBusinessNo?: string
  externalRef?: string
  failureCode?: string
  createTime: string
  updateTime: string
}

export interface StoredValueLedgerEntry {
  transactionId: number
  transactionType: string
  sourceType: string
  sourceId: string
  bucket: string
  direction: 'DEBIT' | 'CREDIT' | string
  amount: number
  currency: string
  reason: string
  occurredAt: string
}

export interface StoredValueOrderPaymentOption {
  orderId: number
  amount: number
  currency: string
  available: boolean
  reason?: string
  product: StoredValueProduct | null
  account: StoredValueAccount | null
  identityVerified: boolean
}

export interface StoredValueOrderStoredStatus {
  payment: StoredValueOrderPayment | null
  refund: { refundNo?: string; status?: string; amount?: number; principalAmount?: number; bonusAmount?: number } | null
}

export interface StoredValueOrderPayment {
  paymentId: number
  paymentNo: string
  orderId: number
  status: string
  amount: number
  principalAmount: number
  bonusAmount: number
  replayed: boolean
}

export interface StoredValueOrderRefund {
  refundId: number
  refundNo: string
  orderId: number
  status: string
  amount: number
  principalAmount: number
  bonusAmount: number
  compensationAmount: number
  replayed: boolean
}

export interface StoredValueOrderStatus {
  payment: StoredValueOrderPayment | null
  refund: StoredValueOrderRefund | null
}

export interface StoredValueFinanceSummary {
  accountCount: number
  pendingRechargeCount: number
  openDifferenceCount: number
  availablePrincipal: number
  availableBonus: number
}

export interface StoredValueApproval {
  approvalNo: string
  actionType: string
  targetType: string
  targetRef: string
  requesterRef: string
  requesterIsCurrentActor: boolean
  amount?: number | null
  currency?: string | null
  reviewPermission: string
  executePermission: string
  status: string
  expiresAt: string
  reviewerRef?: string | null
  executorRef?: string | null
  reason?: string
  policyVersion?: string
  version?: number
  reviewedAt?: string | null
  reviewReason?: string | null
  executedAt?: string | null
  executionResult?: string | null
  createTime: string
  updateTime: string
}

export interface StoredValueFinanceAccount {
  accountRef: string
  userRef: string
  productCode: string
  environment: string
  status: string
  principalAvailable: number
  principalFrozen: number
  bonusAvailable: number
  bonusFrozen: number
  availableTotal: number
  totalBalance: number
  updateTime: string
}

export interface StoredValueFinanceRecharge {
  rechargeRef: string
  rechargeNoSuffix: string
  userRef: string
  productCode: string
  status: string
  principalAmount: number
  expectedBonusAmount: number
  actualBonusAmount: number
  currency: string
  providerKey: string
  providerEnv: string
  providerName: string
  externalRefSummary?: string
  failureCode?: string
  createTime: string
  updateTime: string
}

export interface StoredValueFinanceLedgerEntry {
  transactionRef: string
  accountRef: string
  userRef: string
  transactionType: string
  sourceType: string
  sourceRef: string
  bucket: string
  direction: string
  amount: number
  currency: string
  reason: string
  occurredAt: string
}

export interface StoredValueReconciliationRun {
  runRef: string
  reconcileDate: string
  providerEnv: string
  providerName: string
  scopeType: string
  status: string
  differenceCount: number
  completedAt?: string
}

export interface StoredValueReconciliationDifference {
  differenceRef: string
  runRef: string
  differenceType: string
  providerAmount?: number
  localAmount?: number
  currency: string
  status: string
  reason: string
  ledgerTransactionRef?: string
  createTime: string
}

export interface StoredValueProvider {
  providerKey: string
  providerEnv: string
  providerName: string
  configVersion: string
  enabled: boolean
}

/* B5-H11：财务侧原桶分摊只读摘要（受控引用，无内部主键） */
export interface StoredValueOrderFactPayment {
  paymentNoSuffix: string
  status: string
  amount: number
  principalAmount: number
  bonusAmount: number
}

export interface StoredValueOrderFactRefund {
  refundNoSuffix: string
  status: string
  amount: number
  principalAmount: number
  bonusAmount: number
  compensationAmount: number
}

export interface StoredValueOrderFacts {
  orderNo: string
  payment: StoredValueOrderFactPayment | null
  refund: StoredValueOrderFactRefund | null
}

export interface StoredValueActionResponse {
  status: string
  message: string
}

export interface CreateStoredValueRechargePayload {
  productId: number
  principalAmount: number
  currency: string
  clientRequestId: string
  providerKey?: string
}

export interface CreateStoredValueProductPayload {
  productCode: string
  productName: string
}

export interface StoredValueProductFlagsPayload {
  rechargeEnabled?: boolean
  spendEnabled?: boolean
  status?: string
}

export interface StoredValueAccountActionPayload {
  reason: string
  businessRef: string
}

export interface StoredValueApprovalReasonPayload {
  reason: string
}

const page = (params: Record<string, unknown> = {}) => ({ pageNum: 1, pageSize: 20, ...params })

export const storedValueApi = {
  self: (config?: HttpRequestConfig) => http.get<StoredValueSelf>('/stored-value/me', config),
  bonusBatches: (config?: HttpRequestConfig) => http.get<StoredValueBonusBatch[]>('/stored-value/me/bonus-batches', config),
  recharges: (params: Record<string, unknown> = {}, config?: HttpRequestConfig) =>
    http.get<PageResult<StoredValueRecharge>>('/stored-value/me/recharges', { params: page(params), ...config }),
  myRecharge: (rechargeNo: string, config?: HttpRequestConfig) =>
    http.get<StoredValueRecharge>(`/stored-value/me/recharges/${rechargeNo}`, config),
  createRecharge: (payload: CreateStoredValueRechargePayload, config?: HttpRequestConfig) =>
    http.post<StoredValueRecharge>('/stored-value/me/recharges', payload, config),
  payOrder: (orderId: number, payload: { productId: number; idempotencyKey: string }, config?: HttpRequestConfig) =>
    http.post<StoredValueOrderPayment>(`/stored-value/me/orders/${orderId}/payments`, payload, config),
  confirmDevCallback: (rechargeNo: string, status: 'SUCCESS' | 'FAILED', config?: HttpRequestConfig) =>
    http.post<StoredValueRecharge>(`/stored-value/me/recharges/${rechargeNo}/dev-callback`, { status }, config),
  ledger: (params: Record<string, unknown> = {}, config?: HttpRequestConfig) =>
    http.get<PageResult<StoredValueLedgerEntry>>('/stored-value/me/ledger', { params: page(params), ...config }),
  paymentOption: (orderId: number) =>
    http.get<StoredValueOrderPaymentOption>(`/stored-value/me/orders/${orderId}/payment-option`),
  /** 储值支付/退款结果核对（交易面读取本金/赠送分摊用，G-T1 配套）。 */
  orderStoredStatus: (orderId: number, config?: HttpRequestConfig) =>
    http.get<StoredValueOrderStoredStatus>(`/stored-value/me/orders/${orderId}/status`, config),
}

export const storedValueAdminApi = {
  summary: () => http.get<StoredValueFinanceSummary>('/admin/stored-value/summary'),
  products: () => http.get<StoredValueProduct[]>('/admin/stored-value/products'),
  createProduct: (payload: CreateStoredValueProductPayload) =>
    http.post<StoredValueProduct>('/admin/stored-value/products', payload),
  setProductFlags: (productId: number, payload: StoredValueProductFlagsPayload) =>
    http.put<StoredValueProduct>(`/admin/stored-value/products/${productId}/flags`, payload),
  providers: () => http.get<StoredValueProvider[]>('/admin/stored-value/providers'),
  accounts: (params: Record<string, unknown> = {}) =>
    http.get<PageResult<StoredValueFinanceAccount>>('/admin/stored-value/accounts', { params: page(params) }),
  recharges: (params: Record<string, unknown> = {}) =>
    http.get<PageResult<StoredValueFinanceRecharge>>('/admin/stored-value/recharges', { params: page(params) }),
  ledger: (params: Record<string, unknown> = {}) =>
    http.get<PageResult<StoredValueFinanceLedgerEntry>>('/admin/stored-value/ledger', { params: page(params) }),
  /* B5-H11：按订单号读取原桶支付/退款分摊摘要（stored-value:finance:view，只读） */
  orderStoredFacts: (orderNo: string) =>
    http.get<StoredValueOrderFacts>('/admin/stored-value/order-stored-facts', { params: { orderNo } }),
  reconciliationRuns: (params: Record<string, unknown> = {}) =>
    http.get<PageResult<StoredValueReconciliationRun>>('/admin/stored-value/reconciliation/runs', { params: page(params) }),
  reconciliationDifferences: (params: Record<string, unknown> = {}) =>
    http.get<PageResult<StoredValueReconciliationDifference>>('/admin/stored-value/reconciliation/differences', { params: page(params) }),
  runReconciliation: (reconcileDate: string) =>
    http.post<StoredValueReconciliationRun>('/admin/stored-value/reconciliation/run', { reconcileDate }),
  verifyIdentity: (userId: number, evidenceDigest: string, expiresOn?: string) =>
    http.post<void>(`/admin/stored-value/users/${userId}/identity-verifications`, {
      environment: 'DEV', evidenceDigest, expiresOn
    }),
  freeze: (accountRef: string, payload: StoredValueAccountActionPayload) =>
    http.post<StoredValueActionResponse>(`/admin/stored-value/accounts/${accountRef}/freeze`, payload),
  unfreeze: (accountRef: string, payload: StoredValueApprovalReasonPayload) =>
    http.post<StoredValueApproval>(`/admin/stored-value/accounts/${accountRef}/unfreeze`, payload),
  beginClosing: (accountRef: string, payload: StoredValueAccountActionPayload) =>
    http.post<StoredValueActionResponse>(`/admin/stored-value/accounts/${accountRef}/begin-closing`, payload),
  close: (accountRef: string, payload: StoredValueAccountActionPayload) =>
    http.post<StoredValueActionResponse>(`/admin/stored-value/accounts/${accountRef}/close`, payload),
  refundRecharge: (rechargeRef: string, reason: string) =>
    http.post<StoredValueApproval>(`/admin/stored-value/recharges/${rechargeRef}/refund`, { reason }),
  recoverPosting: (rechargeRef: string, reason: string) =>
    http.post<StoredValueApproval>(`/admin/stored-value/recharges/${rechargeRef}/posting-recovery`, { reason }),
  requestAdjustment: (differenceRef: string, amount: number, reason: string) =>
    http.post<StoredValueApproval>(`/admin/stored-value/reconciliation/differences/${differenceRef}/adjustment`, { amount, reason }),
  approvals: (params: Record<string, unknown> = {}) =>
    http.get<PageResult<StoredValueApproval>>('/admin/stored-value/approvals', { params: page(params) }),
  approval: (approvalNo: string) =>
    http.get<StoredValueApproval>(`/admin/stored-value/approvals/${approvalNo}`),
  approveApproval: (approvalNo: string, reason: string) =>
    http.post<StoredValueApproval>(`/admin/stored-value/approvals/${approvalNo}/approve`, { reason }),
  rejectApproval: (approvalNo: string, reason: string) =>
    http.post<StoredValueApproval>(`/admin/stored-value/approvals/${approvalNo}/reject`, { reason }),
  executeApproval: (approvalNo: string) =>
    http.post<StoredValueApproval>(`/admin/stored-value/approvals/${approvalNo}/execute`),
  revokeApproval: (approvalNo: string, reason: string) =>
    http.post<StoredValueApproval>(`/admin/stored-value/approvals/${approvalNo}/revoke`, { reason }),
  disputeRecharge: (rechargeRef: string, providerCaseId: string, amount: number, reason: string) =>
    http.post<StoredValueActionResponse>(`/admin/stored-value/recharges/${rechargeRef}/disputes`, {
      providerCaseId, amount, reason
    })
}
