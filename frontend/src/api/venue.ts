import http, { type HttpRequestConfig, type PageResult } from './http'
import type {
  ResourceRule,
  ResourceRuleChangeRequest,
  Venue,
  VenueListItem,
  VenueMaintenance,
  VenueSpace
} from './types'

export interface VenueFacets {
  areas: string[]
  spaceTypes: string[]
}

export const venueApi = {
  list: (params: Record<string, unknown>, config?: HttpRequestConfig) => http.get<PageResult<VenueListItem>>('/venues', { params, ...config }),
  facets: () => http.get<VenueFacets>('/venues/facets'),
  detail: (id: number, config?: HttpRequestConfig) => http.get<Venue>(`/venues/${id}`, config),
  spaces: (id: number, config?: HttpRequestConfig) => http.get<VenueSpace[]>(`/venues/${id}/spaces`, config)
}

export const venueAdminApi = {
  manageable: (config?: HttpRequestConfig) => http.get<Venue[]>('/admin/venues/manageable', config),
  createVenue: (payload: Partial<Venue>, config?: HttpRequestConfig) => http.post<Venue>('/admin/venues', payload, config),
  createSpace: (payload: Partial<VenueSpace>, config?: HttpRequestConfig) => http.post<VenueSpace>('/admin/spaces', payload, config),
  updateSpace: (id: number, payload: Partial<VenueSpace>, config?: HttpRequestConfig) => http.put<VenueSpace>(`/admin/spaces/${id}`, payload, config),
  updateSpaceStatus: (id: number, status: string, config?: HttpRequestConfig) => http.put<void>(`/admin/spaces/${id}/status`, { status }, config),
  updateVenue: (id: number, payload: Partial<Venue>, config?: HttpRequestConfig) => http.put<Venue>(`/admin/venues/${id}`, payload, config),
  resourceRule: (id: number, config?: HttpRequestConfig) => http.get<ResourceRule>(`/admin/spaces/${id}/resource-rule`, config),
  configureResourceRule: (id: number, payload: ResourceRuleChangeRequest, config?: HttpRequestConfig) =>
    http.put<ResourceRule>(`/admin/spaces/${id}/resource-rule`, payload, config),
  maintenances: (spaceId: number, config?: HttpRequestConfig) => http.get<VenueMaintenance[]>(`/admin/spaces/${spaceId}/maintenances`, config),
  createMaintenance: (spaceId: number, payload: Pick<VenueMaintenance, 'startTime' | 'endTime' | 'reason'>, config?: HttpRequestConfig) =>
    http.post<VenueMaintenance>(`/admin/spaces/${spaceId}/maintenances`, payload, config),
  cancelMaintenance: (id: number, config?: HttpRequestConfig) => http.put<void>(`/admin/maintenances/${id}/cancel`, undefined, config)
}
