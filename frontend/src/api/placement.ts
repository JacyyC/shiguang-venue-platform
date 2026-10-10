import axios, { type AxiosResponse } from 'axios'
import { useAuthStore } from '@/stores/auth'
import http from './http'
import type {
  CreatePlacementCandidateRequest,
  HomePrimaryDecision,
  PlacementCandidateVersionResponse,
  PlacementDecisionAuditResponse,
  PlacementEventAuditResponse,
  PlacementEventRequest,
  PlacementEventResult,
  PlacementSlotResponse,
  PlacementSourcePreviewResponse
} from './types'

interface PlacementApiResult<T> {
  code: number
  message: string
  data: T
}

// Placement user calls intentionally avoid the global response interceptor: a failed
// non-critical placement request must not redirect or toast over the home page.
const placementHttp = axios.create({
  baseURL: '/api',
  timeout: 8000
})

placementHttp.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

const unwrap = <T>(response: AxiosResponse<PlacementApiResult<T>>) => {
  const payload = response.data
  if (payload && payload.code !== 0) {
    throw new Error(payload.message || '宣传请求失败')
  }
  return payload.data
}

const eventConfig = (idempotencyKey: string) => ({
  headers: { 'Idempotency-Key': idempotencyKey }
})

export const placementApi = {
  async homePrimaryDecision() {
    const response = await placementHttp.get<PlacementApiResult<HomePrimaryDecision | null>>(
      '/placements/HOME_PRIMARY/decision'
    )
    return unwrap(response)
  },

  recordEvent: (decisionId: number, payload: PlacementEventRequest, idempotencyKey: string) =>
    placementHttp
      .post<PlacementApiResult<PlacementEventResult>>(
        `/placements/decisions/${decisionId}/events`,
        payload,
        eventConfig(idempotencyKey)
      )
      .then(unwrap),

  admin: {
    slots: () => http.get<PlacementSlotResponse[]>('/content-admin/placements/slots'),
    candidates: (slotId?: number, limit = 100) =>
      http.get<PlacementCandidateVersionResponse[]>('/content-admin/placements/candidates', {
        params: { slotId, limit }
      }),
    previewSource: (sourceType: string, sourceId: number) =>
      http.get<PlacementSourcePreviewResponse>('/content-admin/placements/sources/preview', {
        params: { sourceType, sourceId }
      }),
    createCandidate: (payload: CreatePlacementCandidateRequest) =>
      http.post<PlacementCandidateVersionResponse>('/content-admin/placements/candidates', payload),
    publish: (candidateId: number) =>
      http.post<PlacementCandidateVersionResponse>(`/content-admin/placements/candidates/${candidateId}/publish`),
    pause: (candidateId: number) =>
      http.post<PlacementCandidateVersionResponse>(`/content-admin/placements/candidates/${candidateId}/pause`),
    retire: (candidateId: number) =>
      http.post<PlacementCandidateVersionResponse>(`/content-admin/placements/candidates/${candidateId}/retire`),
    decisions: (slotId?: number, limit = 100) =>
      http.get<PlacementDecisionAuditResponse[]>('/content-admin/placements/decisions', {
        params: { slotId, limit }
      }),
    events: (slotId?: number, limit = 100) =>
      http.get<PlacementEventAuditResponse[]>('/content-admin/placements/events', {
        params: { slotId, limit }
      }),
    /* B5-G1：按决策读取事件与接收结果（审计权限） */
    decisionEvents: (decisionId: number, limit = 100) =>
      http.get<PlacementEventAuditResponse[]>(
        `/content-admin/placements/decisions/${decisionId}/events`,
        { params: { limit } }
      )
  }
}

export const placementSourceLabel = (sourceType: string) => ({
  GLOBAL_NOTICE: '全局公告',
  PRICE_PROMOTION: '价格活动',
  COMMUNITY_ACTIVITY: '社区活动'
}[sourceType] || sourceType)

export const placementStatusLabel = (status: string) => ({
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  PAUSED: '已暂停',
  RETIRED: '已下线'
}[status] || status)
