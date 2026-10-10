import http, { type HttpRequestConfig, type PageResult } from './http'
import type { AssetInspectionRecord, AssetItem, AssetLifecycleEvent, AssetRepairRecord } from './types'

export type { AssetInspectionRecord, AssetItem, AssetLifecycleEvent, AssetRepairRecord } from './types'

/** 资产上下文（GET /admin/site-ops/assets/context，权限 asset:analytics:view）的服务端真实键。 */
export interface AssetContext {
  totalAssets: number
  assetsByStatus: Record<string, number>
  inspectionCount: number
  openRepairCount: number
  completedRepairCount: number
  dataGaps: string[]
}

export const assetsApi = {
  list: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<AssetItem>>('/admin/assets', { params, ...config }),
  create: (payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<AssetItem>('/admin/assets', payload, config),
  update: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.put<AssetItem>(`/admin/assets/${id}`, payload, config),
  changeStatus: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<AssetItem>(`/admin/assets/${id}/status`, payload, config),
  events: (id: number, params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<AssetLifecycleEvent>>(`/admin/assets/${id}/events`, { params, ...config }),
  inspections: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<AssetInspectionRecord>>('/admin/assets/inspections', { params, ...config }),
  recordInspection: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<AssetInspectionRecord>(`/admin/assets/${id}/inspections`, payload, config),
  repairs: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<AssetRepairRecord>>('/admin/assets/repairs', { params, ...config }),
  requestRepair: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<AssetRepairRecord>(`/admin/assets/${id}/repairs`, payload, config),
  startRepair: (id: number, config?: HttpRequestConfig) =>
    http.post<AssetRepairRecord>(`/admin/assets/repairs/${id}/start`, undefined, config),
  completeRepair: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<AssetRepairRecord>(`/admin/assets/repairs/${id}/complete`, payload, config),
  cancelRepair: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<AssetRepairRecord>(`/admin/assets/repairs/${id}/cancel`, payload, config),
  context: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<AssetContext>('/admin/site-ops/assets/context', { params, ...config })
}
