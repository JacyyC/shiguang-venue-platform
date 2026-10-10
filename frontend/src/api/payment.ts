import http, { type PageResult } from './http'
import type {
  AdjustReconciliationPayload,
  PaymentAdminOverviewQueryParams,
  PaymentAdminOverviewResponse,
  PaymentAttempt,
  PaymentAttemptQueryParams,
  PaymentCallbackLog,
  PaymentCallbackQueryParams,
  PaymentProviderOption,
  ReconciliationItem,
  ReconciliationItemQueryParams,
  ReconciliationRunPayload,
  ReconciliationRunResponse
} from './types'

export const paymentAdminApi = {
  providers: () => http.get<PaymentProviderOption[]>('/admin/payments/providers'),
  overview: (params: PaymentAdminOverviewQueryParams = {}) =>
    http.get<PaymentAdminOverviewResponse>('/admin/payments/overview', { params }),
  attempts: (params: PaymentAttemptQueryParams = {}) =>
    http.get<PageResult<PaymentAttempt>>('/admin/payments/attempts', { params }),
  callbacks: (params: PaymentCallbackQueryParams = {}) =>
    http.get<PageResult<PaymentCallbackLog>>('/admin/payments/callbacks', { params }),
  runReconciliation: (payload: ReconciliationRunPayload) =>
    http.post<ReconciliationRunResponse>('/admin/finance/reconciliation/run', payload),
  reconciliationItems: (params: ReconciliationItemQueryParams = {}) =>
    http.get<PageResult<ReconciliationItem>>('/admin/finance/reconciliation/items', { params }),
  // 后端返回 Result<FinanceReconciliationItem>：调整后读回整条记录，用于证明只改了差异记录本身
  adjustReconciliation: (id: number, payload: AdjustReconciliationPayload) =>
    http.put<ReconciliationItem>(`/admin/finance/reconciliation/items/${id}/adjust`, payload)
}
