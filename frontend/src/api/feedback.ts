import http, { type HttpRequestConfig, type PageResult } from './http'

export interface VenueReview {
  id: number
  reviewNo?: string
  venueId?: number
  spaceId?: number
  reservationId?: number
  lifecycleId?: number
  overallRating?: number
  venueRating?: number
  spaceRating?: number
  serviceRating?: number
  tagsJson?: string
  contentSanitized?: string
  sensitiveFlag?: number
  status?: string
  submittedAt?: string
  publishedAt?: string
}

export interface CustomerComplaint {
  id: number
  complaintNo?: string
  venueId?: number
  spaceId?: number
  reservationId?: number
  lifecycleId?: number
  noticeId?: number
  assetId?: number
  siteIncidentId?: number
  complaintType?: string
  severity?: string
  status?: string
  title?: string
  contentSanitized?: string
  sensitiveFlag?: number
  submittedAt?: string
  triagedAt?: string
  resolvedAt?: string
  closedAt?: string
}

export interface CustomerServiceCase {
  id: number
  caseNo?: string
  complaintId?: number
  venueId?: number
  priority?: string
  status?: string
  rootCauseCode?: string
  resolutionType?: string
  customerVisibleReply?: string
  requiresRefundReview?: number | boolean
  requiresCouponReview?: number | boolean
  requiresSiteFollowUp?: number | boolean
  openedAt?: string
  assignedAt?: string
  resolvedAt?: string
  closedAt?: string
  reopenedAt?: string
}

export interface CustomerServiceCaseEvent {
  id: number
  eventNo?: string
  caseId?: number
  complaintId?: number
  eventType?: string
  fromStatus?: string
  toStatus?: string
  noteSanitized?: string
  sourceType?: string
  occurredAt?: string
}

export interface CustomerServiceUserCaseEvent {
  id: number
  eventType?: string
  fromStatus?: string
  toStatus?: string
  noteSanitized?: string
  occurredAt?: string
}

export interface CustomerServiceAdminCaseEvent extends CustomerServiceUserCaseEvent {
  internalNoteSanitized?: string
  actorUsername?: string
  sourceType?: string
}

export interface VenueReviewSummary {
  venueId?: number
  reviewCount?: number
  averageRating?: number | string
  ratingCounts?: Record<string, number>
  tagSummary?: unknown
  tagCounts?: Record<string, number>
  tags?: unknown
  topTags?: unknown
}

export interface ReviewSubmitPayload {
  venueId: number
  spaceId?: number
  reservationId?: number
  orderId?: number
  lifecycleId?: number
  overallRating: number
  venueRating?: number
  spaceRating?: number
  serviceRating?: number
  tags?: string[]
  contentText: string
}

export interface ComplaintSubmitPayload {
  venueId: number
  spaceId?: number
  reservationId?: number
  orderId?: number
  lifecycleId?: number
  noticeId?: number
  assetId?: number
  siteIncidentId?: number
  complaintType: string
  severity: string
  title: string
  contentText: string
  requestedResolution?: string
}

export interface CustomerServiceCaseActionPayload {
  assignedUserId?: number
  status?: string
  rootCauseCode?: string
  resolutionType?: string
  replyText?: string
  internalNote?: string
  closeReason?: string
  reopenReason?: string
  requiresRefundReview?: boolean
  requiresCouponReview?: boolean
  requiresSiteFollowUp?: boolean
}

export interface ReviewModerationPayload {
  action: string
  reasonCode?: string
  reasonText?: string
}

export interface CustomerServiceAdminContext {
  complaintCount?: number
  caseCount?: number
  reviewCount?: number
  complaints?: Array<Record<string, unknown>>
  cases?: Array<Record<string, unknown>>
  reviews?: Array<Record<string, unknown>>
  [key: string]: unknown
}

export const feedbackApi = {
  myReviews: (params?: Record<string, unknown>, config?: HttpRequestConfig) => http.get<PageResult<VenueReview>>('/me/feedback/reviews', { params, ...config }),
  submitReview: (payload: ReviewSubmitPayload | Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<VenueReview>('/me/feedback/reviews', payload, config),
  myComplaints: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<CustomerComplaint>>('/me/feedback/complaints', { params, ...config }),
  submitComplaint: (payload: ComplaintSubmitPayload | Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<CustomerComplaint>('/me/feedback/complaints', payload, config),
  myCases: (params?: Record<string, unknown>, config?: HttpRequestConfig) => http.get<PageResult<CustomerServiceCase>>('/me/feedback/cases', { params, ...config }),
  myCaseEvents: (caseId: number, config?: HttpRequestConfig) =>
    http.get<CustomerServiceUserCaseEvent[]>(`/me/feedback/cases/${caseId}/events`, config),
  venueReviewSummary: (venueId: number) => http.get<VenueReviewSummary>(`/venues/${venueId}/reviews/summary`),
  venueReviews: (venueId: number, params?: Record<string, unknown>) =>
    http.get<PageResult<VenueReview>>(`/venues/${venueId}/reviews`, { params }),
  adminComplaints: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<CustomerComplaint>>('/admin/customer-service/complaints', { params, ...config }),
  adminCases: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<CustomerServiceCase>>('/admin/customer-service/cases', { params, ...config }),
  adminCaseEvents: (caseId: number, config?: HttpRequestConfig) =>
    http.get<CustomerServiceAdminCaseEvent[]>(`/admin/customer-service/cases/${caseId}/events`, config),
  assignCase: (caseId: number, payload: CustomerServiceCaseActionPayload | Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<CustomerServiceCase>(`/admin/customer-service/cases/${caseId}/assign`, payload, config),
  changeCaseStatus: (
    caseId: number,
    payload: CustomerServiceCaseActionPayload | Record<string, unknown>,
    config?: HttpRequestConfig
  ) => http.post<CustomerServiceCase>(`/admin/customer-service/cases/${caseId}/status`, payload, config),
  replyCase: (caseId: number, payload: CustomerServiceCaseActionPayload | Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<CustomerServiceCaseEvent>(`/admin/customer-service/cases/${caseId}/reply`, payload, config),
  resolveCase: (caseId: number, payload: CustomerServiceCaseActionPayload | Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<CustomerServiceCase>(`/admin/customer-service/cases/${caseId}/resolve`, payload, config),
  closeCase: (caseId: number, payload: CustomerServiceCaseActionPayload | Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<CustomerServiceCase>(`/admin/customer-service/cases/${caseId}/close`, payload, config),
  reopenCase: (caseId: number, payload: CustomerServiceCaseActionPayload | Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<CustomerServiceCase>(`/admin/customer-service/cases/${caseId}/reopen`, payload, config),
  adminReviews: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<VenueReview>>('/admin/customer-service/reviews', { params, ...config }),
  moderateReview: (reviewId: number, payload: ReviewModerationPayload | Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<VenueReview>(`/admin/customer-service/reviews/${reviewId}/moderate`, payload, config),
  adminContext: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<CustomerServiceAdminContext>('/admin/customer-service/context', { params, ...config })
}
