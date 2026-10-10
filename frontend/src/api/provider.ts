import http, { type HttpRequestConfig, type PageResult } from './http'

export type ProviderCallSource = 'MANUAL' | 'SCHEDULED' | 'SYSTEM'

export interface ProviderOverviewResponse {
  providers: ProviderStatusResponse[]
  dataGaps: string[]
}

export interface ProviderStatusResponse {
  providerKey: string
  providerDomain: string
  providerEnv: string
  providerName: string
  displayName: string
  enabled?: boolean
  configVersion?: string
  status: string
  freshnessSeconds?: number
  freshnessStatus: string
  freshnessDeadlineAt?: string
  evidenceAgeSeconds?: number
  lastSuccessAt?: string
  lastFailureAt?: string
  consecutiveFailures: number
  lastFailureCode?: string
  lastFailureMessage?: string
}

export interface ProviderCallLog {
  id: number
  callNo?: string
  providerKey?: string
  providerDomain?: string
  providerEnv?: string
  providerName?: string
  operationType?: string
  status?: string
  durationMs?: number
  configVersion?: string
  failureCode?: string
  snapshotType?: string
  snapshotId?: number
  sourceType?: ProviderCallSource
  occurredAt?: string
  createTime?: string
}

export interface WeatherSnapshotResponse {
  id: number
  snapshotNo?: string
  venueId?: number
  locationId?: number
  providerKey?: string
  providerEnv?: string
  providerName?: string
  configVersion?: string
  observedAt?: string
  expiresAt?: string
  temperatureC?: number
  precipitationMm?: number
  windSpeed?: number
  weatherCode?: string
  alertLevel?: string
  summaryText?: string
  confidence?: number
  createTime?: string
}

export interface RiskSnapshotResponse {
  id: number
  snapshotNo?: string
  scopeType?: string
  scopeId?: number
  providerKey?: string
  providerEnv?: string
  providerName?: string
  configVersion?: string
  riskScore?: number
  riskLevel?: string
  windowStart?: string
  windowEnd?: string
  failedLoginCount?: number
  paymentFailureCount?: number
  noticeFailureCount?: number
  openComplaintCount?: number
  severeComplaintCount?: number
  confidence?: number
  createTime?: string
}

export interface OpsMetricSnapshotResponse {
  id: number
  snapshotNo?: string
  providerKey?: string
  providerEnv?: string
  providerName?: string
  configVersion?: string
  metricKey?: string
  metricValue?: number
  metricUnit?: string
  status?: string
  windowStart?: string
  windowEnd?: string
  confidence?: number
  observedAt?: string
  expiresAt?: string
  createTime?: string
}

export interface VenueObservationLocationRequest {
  latitude: number
  longitude: number
  locationLabel: string
  areaCode?: string
  timezone?: string
}

export interface VenueObservationLocationResponse {
  id: number
  venueId: number
  areaCode?: string
  latitude?: number
  longitude?: number
  locationLabel?: string
  timezone?: string
  status?: string
  sourceType?: string
  confidence?: number
  createTime?: string
  updateTime?: string
}

export interface ProviderRefreshResponse {
  status?: string
  providerKey?: string
  snapshotId?: number
  dataGaps?: string[]
}

export const providerApi = {
  overview: (config?: HttpRequestConfig) => http.get<ProviderOverviewResponse>('/admin/providers/overview', config),
  calls: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<ProviderCallLog>>('/admin/providers/calls', { params, ...config }),
  weatherSnapshots: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<WeatherSnapshotResponse>>('/admin/providers/weather/snapshots', { params, ...config }),
  refreshWeather: (venueId: number, config?: HttpRequestConfig) =>
    http.post<ProviderRefreshResponse>('/admin/providers/weather/refresh', undefined, { params: { venueId }, ...config }),
  riskSnapshots: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<RiskSnapshotResponse>>('/admin/providers/risk/snapshots', { params, ...config }),
  refreshRisk: (venueId?: number, config?: HttpRequestConfig) =>
    http.post<ProviderRefreshResponse>(
      '/admin/providers/risk/refresh',
      undefined,
      { params: typeof venueId === 'number' ? { venueId } : undefined, ...config }
    ),
  metricSnapshots: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<OpsMetricSnapshotResponse>>('/admin/providers/metrics/snapshots', { params, ...config }),
  refreshMetrics: (config?: HttpRequestConfig) => http.post<ProviderRefreshResponse>('/admin/providers/metrics/refresh', undefined, config),
  locations: (config?: HttpRequestConfig) => http.get<VenueObservationLocationResponse[]>('/admin/providers/venues/locations', config),
  saveLocation: (venueId: number, payload: VenueObservationLocationRequest, config?: HttpRequestConfig) =>
    http.put<VenueObservationLocationResponse>(`/admin/providers/venues/${venueId}/location`, payload, config)
}
