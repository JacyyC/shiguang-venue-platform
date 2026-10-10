import http, { type HttpRequestConfig } from './http'
import type {
  InventoryCalendarResponse,
  SaveBusinessCalendarRequest,
  SaveUnavailableBlockRequest,
  VenueBusinessCalendar,
  VenueUnavailableBlock
} from './types'

export const inventoryAdminApi = {
  calendar: (params: { venueId: number; spaceId: number; date: string }, config?: HttpRequestConfig) =>
    http.get<InventoryCalendarResponse>('/admin/inventory/calendar', { params, ...config }),
  saveCalendar: (payload: SaveBusinessCalendarRequest, config?: HttpRequestConfig) =>
    http.put<VenueBusinessCalendar>('/admin/inventory/calendar', payload, config),
  blocks: (params: { venueId: number; spaceId?: number; date: string }, config?: HttpRequestConfig) =>
    http.get<VenueUnavailableBlock[]>('/admin/inventory/blocks', { params, ...config }),
  createBlock: (payload: SaveUnavailableBlockRequest, config?: HttpRequestConfig) =>
    http.post<VenueUnavailableBlock>('/admin/inventory/blocks', payload, config),
  cancelBlock: (id: number, reason: string, config?: HttpRequestConfig) =>
    http.put<void>(`/admin/inventory/blocks/${id}/cancel`, { reason }, config)
}
