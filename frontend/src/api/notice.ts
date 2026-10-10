import http, { type HttpRequestConfig, type PageResult } from './http'
import type {
  AudiencePreviewResponse,
  Message,
  Notice,
  PublicNotice,
  NoticeAudienceResponse,
  NoticeDeliveryBatchResponse,
  NoticeDeliveryOutboxResponse,
  NoticeDeliveryOverviewResponse,
  NoticeExperimentResponse,
  NoticeExperimentResultResponse,
  NotificationProviderOption,
  TransactionalEmailPreferenceResponse
} from './types'

export const noticeApi = {
  messages: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<Message>>('/messages', { params, ...config }),
  unreadCount: (config?: HttpRequestConfig) => http.get<number>('/messages/unread-count', config),
  notificationPreferences: (config?: HttpRequestConfig) =>
    http.get<TransactionalEmailPreferenceResponse>('/users/me/notification-preferences', config),
  updateNotificationPreferences: (payload: { transactionalEmailEnabled: boolean }, config?: HttpRequestConfig) =>
    http.put<TransactionalEmailPreferenceResponse>('/users/me/notification-preferences', payload, config),
  markOneRead: (id: number, payload: Record<string, unknown> = { entry: 'MESSAGE_CENTER' }, config?: HttpRequestConfig) =>
    http.put<void>(`/messages/${id}/read`, payload, config),
  markRead: (payload: Record<string, unknown> = { entry: 'MESSAGE_CENTER' }, config?: HttpRequestConfig) => http.put<void>('/messages/read', payload, config),
  previewAudience: (payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<AudiencePreviewResponse>('/admin/notices/audience/preview', payload, config),
  publish: (payload: Partial<Notice>, config?: HttpRequestConfig) => http.post<Notice>('/admin/notices', payload, config),
  deliveryProviders: (config?: HttpRequestConfig) =>
    http.get<NotificationProviderOption[]>('/admin/notices/delivery/providers', config),
  deliveryOverview: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<NoticeDeliveryOverviewResponse>('/admin/notices/delivery/overview', { params, ...config }),
  deliveryBatches: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<NoticeDeliveryBatchResponse>>('/admin/notices/delivery/batches', { params, ...config }),
  deliveryBatch: (batchId: number, config?: HttpRequestConfig) =>
    http.get<NoticeDeliveryBatchResponse>(`/admin/notices/delivery/batches/${batchId}`, config),
  deliveryOutboxes: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<NoticeDeliveryOutboxResponse>>('/admin/notices/delivery/outboxes', { params, ...config }),
  deliveryAudiences: (batchId: number, params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<NoticeAudienceResponse>>(`/admin/notices/delivery/batches/${batchId}/audiences`, { params, ...config }),
  retryOutbox: (outboxId: number, config?: HttpRequestConfig) =>
    http.post<void>(`/admin/notices/delivery/outbox/${outboxId}/retry`, undefined, config),
  createExperiment: (payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<NoticeExperimentResponse>('/admin/notices/experiments', payload, config),
  experiments: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<NoticeExperimentResponse>>('/admin/notices/experiments', { params, ...config }),
  experimentResults: (id: number, params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<NoticeExperimentResultResponse>(`/admin/notices/experiments/${id}/results`, { params, ...config })
}
