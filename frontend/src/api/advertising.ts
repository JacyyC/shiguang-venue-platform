import axios, { type AxiosResponse } from 'axios'
import { useAuthStore } from '@/stores/auth'
import http, { type HttpRequestConfig } from './http'
import type {
  AdvertisingAdvertiserResponse,
  AdvertisingAnalysisOverview,
  AdvertisingAuditResponse,
  AdvertisingCampaignResponse,
  AdvertisingCreativeResponse,
  AdvertisingEventRequest,
  AdvertisingEventResult,
  AdvertisingPrivacyPreferenceResponse,
  AdvertisingReviewDecisionRequest,
  AdvertisingTransitionRequest,
  CreateAdvertisingAdvertiserRequest,
  CreateAdvertisingCampaignRequest,
  CreateAdvertisingCreativeRequest,
  HomeCommercialDecision
} from './types'

interface AdvertisingApiResult<T> {
  code: number
  message: string
  data: T
}

// User-facing advertising is optional. Keep its decision and measurement calls
// off the global interceptor so a failed card cannot redirect the home page.
const publicAdvertisingHttp = axios.create({
  baseURL: '/api',
  timeout: 8000
})

publicAdvertisingHttp.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

const unwrap = <T>(response: AxiosResponse<AdvertisingApiResult<T>>) => {
  const payload = response.data
  if (payload && payload.code !== 0) {
    throw new Error(payload.message || '广告请求失败')
  }
  return payload.data
}

const eventConfig = (idempotencyKey: string) => ({
  headers: { 'Idempotency-Key': idempotencyKey }
})

export const ADVERTISING_EVENT_RETRY_DELAYS_MS = [250, 1000] as const
const pendingEventRecoveries = new Map<string, Promise<AdvertisingEventResult>>()

const isRetryableAdvertisingEventError = (error: unknown) => {
  const status = (error as { response?: { status?: unknown } })?.response?.status
  return typeof status !== 'number' || status === 408 || status === 429 || status >= 500
}

const recordEvent = (decisionId: number, payload: AdvertisingEventRequest, idempotencyKey: string) =>
  publicAdvertisingHttp
    .post<AdvertisingApiResult<AdvertisingEventResult>>(
      `/advertising/decisions/${decisionId}/events`,
      payload,
      eventConfig(idempotencyKey)
    )
    .then(unwrap)

const recordEventWithRecovery = (
  decisionId: number,
  payload: AdvertisingEventRequest,
  idempotencyKey: string
) => {
  const recoveryKey = `${decisionId}:${idempotencyKey}`
  const existing = pendingEventRecoveries.get(recoveryKey)
  if (existing) return existing

  let attempts = 0
  const recovery = new Promise<AdvertisingEventResult>((resolve, reject) => {
    const attempt = async (): Promise<void> => {
      attempts += 1
      try {
        const result = await recordEvent(decisionId, payload, idempotencyKey)
        pendingEventRecoveries.delete(recoveryKey)
        resolve(result)
      } catch (error) {
        const retryDelay = ADVERTISING_EVENT_RETRY_DELAYS_MS[attempts - 1]
        if (!isRetryableAdvertisingEventError(error) || retryDelay === undefined) {
          pendingEventRecoveries.delete(recoveryKey)
          reject(error)
          return
        }
        setTimeout(() => { void attempt() }, retryDelay)
      }
    }

    void attempt()
  })

  pendingEventRecoveries.set(recoveryKey, recovery)
  return recovery
}

export const advertisingApi = {
  preferences: (config?: HttpRequestConfig) => http.get<AdvertisingPrivacyPreferenceResponse>('/advertising/preferences', config),

  updatePreferences: (payload: Pick<AdvertisingPrivacyPreferenceResponse, 'commercialEnabled' | 'measurementEnabled'>, config?: HttpRequestConfig) =>
    http.put<AdvertisingPrivacyPreferenceResponse>('/advertising/preferences', payload, config),

  async homeCommercialDecision() {
    const response = await publicAdvertisingHttp.get<AdvertisingApiResult<HomeCommercialDecision | null>>(
      '/advertising/HOME_COMMERCIAL/decision'
    )
    const decision = unwrap(response)
    return decision?.token?.trim() ? decision : null
  },

  recordEvent,
  recordEventWithRecovery,

  admin: {
    overview: (from?: string, until?: string) =>
      http.get<AdvertisingAnalysisOverview>('/content-admin/advertising/analysis/overview', {
        params: { from, until }
      }),

    advertisers: (limit?: number) =>
      http.get<AdvertisingAdvertiserResponse[]>('/content-admin/advertising/advertisers', { params: { limit } }),
    createAdvertiser: (payload: CreateAdvertisingAdvertiserRequest) =>
      http.post<AdvertisingAdvertiserResponse>('/content-admin/advertising/advertisers', payload),
    suspendAdvertiser: (advertiserId: number, payload?: AdvertisingTransitionRequest) =>
      http.post<AdvertisingAdvertiserResponse>(`/content-admin/advertising/advertisers/${advertiserId}/suspend`, payload ?? {}),
    retireAdvertiser: (advertiserId: number, payload?: AdvertisingTransitionRequest) =>
      http.post<AdvertisingAdvertiserResponse>(`/content-admin/advertising/advertisers/${advertiserId}/retire`, payload ?? {}),

    campaigns: (params?: { venueId?: number; limit?: number }) =>
      http.get<AdvertisingCampaignResponse[]>('/content-admin/advertising/campaigns', { params }),
    createCampaign: (payload: CreateAdvertisingCampaignRequest) =>
      http.post<AdvertisingCampaignResponse>('/content-admin/advertising/campaigns', payload),
    submitCampaignReview: (campaignId: number) =>
      http.post<AdvertisingCampaignResponse>(`/content-admin/advertising/campaigns/${campaignId}/submit-review`),
    approveCampaign: (campaignId: number) =>
      http.post<AdvertisingCampaignResponse>(`/content-admin/advertising/campaigns/${campaignId}/approve`),
    rejectCampaign: (campaignId: number, payload: AdvertisingReviewDecisionRequest) =>
      http.post<AdvertisingCampaignResponse>(`/content-admin/advertising/campaigns/${campaignId}/reject`, payload),
    publishCampaign: (campaignId: number) =>
      http.post<AdvertisingCampaignResponse>(`/content-admin/advertising/campaigns/${campaignId}/publish`),
    pauseCampaign: (campaignId: number, payload?: AdvertisingTransitionRequest) =>
      http.post<AdvertisingCampaignResponse>(`/content-admin/advertising/campaigns/${campaignId}/pause`, payload ?? {}),
    retireCampaign: (campaignId: number, payload?: AdvertisingTransitionRequest) =>
      http.post<AdvertisingCampaignResponse>(`/content-admin/advertising/campaigns/${campaignId}/retire`, payload ?? {}),

    creatives: (campaignId: number, limit?: number) =>
      http.get<AdvertisingCreativeResponse[]>(`/content-admin/advertising/campaigns/${campaignId}/creatives`, {
        params: { limit }
      }),

    /* B5-G5：商业生命周期审计（advertising:audit:view），分实体查询 */
    audits: (params?: { entityType?: string; entityId?: number; venueId?: number; limit?: number }) =>
      http.get<AdvertisingAuditResponse[]>('/content-admin/advertising/audits', { params }),

    createCreative: (payload: CreateAdvertisingCreativeRequest) =>
      http.post<AdvertisingCreativeResponse>('/content-admin/advertising/creatives', payload),
    submitCreativeReview: (creativeId: number) =>
      http.post<AdvertisingCreativeResponse>(`/content-admin/advertising/creatives/${creativeId}/submit-review`),
    approveCreative: (creativeId: number) =>
      http.post<AdvertisingCreativeResponse>(`/content-admin/advertising/creatives/${creativeId}/approve`),
    rejectCreative: (creativeId: number, payload: AdvertisingReviewDecisionRequest) =>
      http.post<AdvertisingCreativeResponse>(`/content-admin/advertising/creatives/${creativeId}/reject`, payload),
    publishCreative: (creativeId: number) =>
      http.post<AdvertisingCreativeResponse>(`/content-admin/advertising/creatives/${creativeId}/publish`),
    pauseCreative: (creativeId: number, payload?: AdvertisingTransitionRequest) =>
      http.post<AdvertisingCreativeResponse>(`/content-admin/advertising/creatives/${creativeId}/pause`, payload ?? {}),
    retireCreative: (creativeId: number, payload?: AdvertisingTransitionRequest) =>
      http.post<AdvertisingCreativeResponse>(`/content-admin/advertising/creatives/${creativeId}/retire`, payload ?? {})
  }
}
