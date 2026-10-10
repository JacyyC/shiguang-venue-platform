import http, { type HttpRequestConfig, type PageResult } from './http'
import type { CheckinVerifyRequest, ReservationLifecycleResponse } from './types'

type QueryParams = Record<string, unknown>
type ReasonPayload = { reasonCode: string; reasonText: string; deviceRef?: string }

export const checkinApi = {
  my: (config?: HttpRequestConfig) => http.get<ReservationLifecycleResponse[]>('/checkins/my', config),
  reissueCredential: (reservationId: number, config?: HttpRequestConfig) =>
    http.post<ReservationLifecycleResponse>(`/reservations/${reservationId}/checkin-credential`, undefined, config)
}

export const adminCheckinApi = {
  list: (params: QueryParams, config?: HttpRequestConfig) =>
    http.get<PageResult<ReservationLifecycleResponse>>('/admin/checkins', { params, ...config }),
  /** 场馆端履约记录（CheckinController GET /api/admin/reservations/{id}/lifecycle）。 */
  lifecycle: (reservationId: number, config?: HttpRequestConfig) =>
    http.get<ReservationLifecycleResponse>(`/admin/reservations/${reservationId}/lifecycle`, config),
  verify: (payload: CheckinVerifyRequest, config?: HttpRequestConfig) =>
    http.post<ReservationLifecycleResponse>('/admin/checkins/verify', payload, config),
  manualPass: (reservationId: number, payload: ReasonPayload, config?: HttpRequestConfig) =>
    http.post<ReservationLifecycleResponse>(`/admin/checkins/manual-pass/${reservationId}`, payload, config),
  confirmNoShow: (reservationId: number, payload: ReasonPayload, config?: HttpRequestConfig) =>
    http.post<ReservationLifecycleResponse>(`/admin/checkins/no-show/${reservationId}/confirm`, payload, config),
  revokeNoShow: (reservationId: number, payload: ReasonPayload, config?: HttpRequestConfig) =>
    http.post<ReservationLifecycleResponse>(`/admin/checkins/no-show/${reservationId}/revoke`, payload, config),
}
