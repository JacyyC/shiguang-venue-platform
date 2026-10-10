import http, { type HttpRequestConfig, type PageResult } from './http'
import type {
  AvailabilitySlot,
  PricingPreviewResponse,
  Reservation,
  ReservationRescheduleRequest,
  ReservationRules,
  ReservationSubmissionResult,
  ResourceAvailabilityResponse,
  VenueAvailabilitySummary
} from './types'

type ResourceSelection = {
  participantCount?: number
  seatIds?: number[]
}

const resourceAvailabilityParams = (params: {
  spaceId: number
  startTime: string
  endTime: string
} & ResourceSelection) => ({
  spaceId: params.spaceId,
  startTime: params.startTime,
  endTime: params.endTime,
  ...(params.participantCount ? { participantCount: params.participantCount } : {}),
  ...(params.seatIds?.length ? { seatIds: params.seatIds.join(',') } : {})
})

export const reservationApi = {
  availability: (params: { spaceId: number; date: string }) =>
    http.get<AvailabilitySlot[]>('/reservations/availability', { params }),
  availabilitySummary: (params: { date: string; venueIds: number[] }, config?: HttpRequestConfig) =>
    http.get<VenueAvailabilitySummary[]>('/reservations/availability-summary', {
      params: { date: params.date, venueIds: params.venueIds.join(',') }, ...config
    }),
  rescheduleSlots: (id: number, params: { date: string }, config?: HttpRequestConfig) =>
    http.get<AvailabilitySlot[]>(`/reservations/${id}/reschedule-slots`, { params, ...config }),
  resourceAvailability: (params: {
    spaceId: number
    startTime: string
    endTime: string
  } & ResourceSelection) => http.get<ResourceAvailabilityResponse>('/reservations/resource-availability', {
    params: resourceAvailabilityParams(params)
  }),
  rules: () => http.get<ReservationRules>('/reservations/rules'),
  create: (payload: {
    spaceId: number
    startTime: string
    endTime: string
    slots?: AvailabilitySlot[]
    contactName: string
    contactPhone: string
    remark?: string
    userCouponId?: number
  } & ResourceSelection, funnelSession?: string, idempotencyKey?: string) => {
    const headers: Record<string, string> = {}
    if (funnelSession?.trim()) headers['X-Funnel-Session'] = funnelSession.trim()
    // 一次提交意图的请求身份：响应丢失后重试必须复用同一个键，服务端据此返回原结果。
    if (idempotencyKey?.trim()) headers['Idempotency-Key'] = idempotencyKey.trim()
    return http.post<Reservation>('/reservations', payload,
      Object.keys(headers).length ? { headers } : undefined)
  },
  /** 只读核对一次提交意图的结果：NONE 表示从未产生结果，PROCESSING 表示仍在处理中，COMPLETED 携带预约。 */
  submissionResult: (idempotencyKey: string) =>
    http.get<ReservationSubmissionResult>('/reservations/submission-result', {
      params: { idempotencyKey }
    }),
  pricingPreview: (payload: {
    spaceId: number
    startTime: string
    endTime: string
    slots?: AvailabilitySlot[]
    contactName?: string
    contactPhone?: string
    remark?: string
    userCouponId?: number
  } & ResourceSelection) => http.post<PricingPreviewResponse>('/reservations/pricing-preview', payload),
  /** 与 pricingPreview 同一服务端报价路径，但不要求联系人：供详情页在填写联系方式前给出权威价格。 */
  quote: (payload: {
    spaceId: number
    startTime: string
    endTime: string
    slots?: AvailabilitySlot[]
    userCouponId?: number
  } & ResourceSelection) => http.post<PricingPreviewResponse>('/reservations/quote', payload),
  detail: (id: number) => http.get<Reservation>(`/reservations/${id}`),
  my: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<Reservation>>('/reservations/my', { params, ...config }),
  cancel: (id: number, reason: string, config?: HttpRequestConfig) =>
    http.put<void>(`/reservations/${id}/cancel`, { reasonText: reason }, config),
  rescheduleAvailability: (id: number, params: { startTime: string; endTime: string }) =>
    http.get<ResourceAvailabilityResponse>(`/reservations/${id}/reschedule-availability`, { params }),
  requestReschedule: (id: number, payload: {
    startTime: string
    endTime: string
    reasonCode: string
    reasonText: string
  }, config?: HttpRequestConfig) =>
    http.post<ReservationRescheduleRequest>(`/reservations/${id}/reschedule-requests`, payload, config),
  myRescheduleRequests: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<ReservationRescheduleRequest>>('/reservations/reschedule-requests/my', { params, ...config })
}

export const adminReservationApi = {
  list: (params: Record<string, unknown>, config?: HttpRequestConfig) => http.get<PageResult<Reservation>>('/admin/reservations', { params, ...config }),
  approve: (id: number, reason: string, config?: HttpRequestConfig) => http.put<void>(`/admin/reservations/${id}/approve`, { reason }, config),
  reject: (id: number, reason: string, config?: HttpRequestConfig) => http.put<void>(`/admin/reservations/${id}/reject`, { reason }, config),
  cancel: (id: number, reason: string, config?: HttpRequestConfig) => http.put<void>(`/admin/reservations/${id}/cancel`, { reasonText: reason }, config),
  rescheduleRequests: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<ReservationRescheduleRequest>>('/admin/reschedule-requests', { params, ...config }),
  reviewReschedule: (id: number, approve: boolean, reviewReason: string, config?: HttpRequestConfig) =>
    http.put<void>(`/admin/reschedule-requests/${id}/review`, { approve, reviewReason }, config)
}
