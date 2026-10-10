import http, { type HttpRequestConfig, type PageResult } from './http'
import type { DevPaymentScenario, Order, PaymentCallbackProcessResult, PaymentIntentResponse, RefundRecord } from './types'

export const orderApi = {
  my: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<Order>>('/orders/my', { params, ...config }),
  createPaymentIntent: (orderId: number, config?: HttpRequestConfig) =>
    http.post<PaymentIntentResponse>(`/orders/${orderId}/payment-intents`, undefined, config),
  paymentIntent: (paymentNo: string, config?: HttpRequestConfig) =>
    http.get<PaymentIntentResponse>(`/payments/${paymentNo}`, config),
  simulateDevPayment: (paymentNo: string, payload: { scenario: DevPaymentScenario }, config?: HttpRequestConfig) =>
    http.post<PaymentCallbackProcessResult>(`/payments/dev/${paymentNo}/simulate`, payload, config),
  applyRefund: (id: number, reason: string, config?: HttpRequestConfig) =>
    http.post<unknown>(`/orders/${id}/refunds`, { reason }, config)
}

export const adminOrderApi = {
  list: (params: Record<string, unknown>, config?: HttpRequestConfig) => http.get<PageResult<Order>>('/admin/orders', { params, ...config }),
  refunds: (params: Record<string, unknown>, config?: HttpRequestConfig) => http.get<PageResult<RefundRecord>>('/admin/refunds', { params, ...config }),
  approveRefund: (id: number, reason: string, config?: HttpRequestConfig) => http.put<void>(`/admin/refunds/${id}/approve`, { reason }, config),
  rejectRefund: (id: number, reason: string, config?: HttpRequestConfig) => http.put<void>(`/admin/refunds/${id}/reject`, { reason }, config),
  markRefundSuccess: (id: number, config?: HttpRequestConfig) => http.put<void>(`/admin/refunds/${id}/success`, undefined, config)
}
