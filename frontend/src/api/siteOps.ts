import http, { type HttpRequestConfig, type PageResult } from './http'

export interface SiteShift {
  id: number
  shiftNo?: string
  venueId?: number
  spaceId?: number
  shiftDate?: string
  shiftName?: string
  roleCode?: string
  assignedUserId?: number
  plannedStartTime?: string
  plannedEndTime?: string
  actualClockInTime?: string
  actualClockOutTime?: string
  status?: string
  sourceType?: string
  handoverStatus?: string
  metadataJson?: string
  createTime?: string
  updateTime?: string
}

export interface SiteTask {
  id: number
  taskNo?: string
  venueId?: number
  spaceId?: number
  shiftId?: number
  taskType?: string
  title?: string
  priority?: string
  status?: string
  assignedUserId?: number
  reservationId?: number
  orderId?: number
  lifecycleId?: number
  assetId?: number
  unavailableBlockId?: number
  complaintId?: number
  dueTime?: string
  startedAt?: string
  completedAt?: string
  exceptionCode?: string
  completionNote?: string
  evidenceJson?: string
  sourceType?: string
  createTime?: string
  updateTime?: string
}

export interface SiteShiftHandover {
  id: number
  handoverNo?: string
  fromShiftId?: number
  toShiftId?: number
  venueId?: number
  fromUserId?: number
  toUserId?: number
  status?: string
  unresolvedTaskCount?: number
  incidentSummary?: string
  assetIssueSummary?: string
  note?: string
  acceptedAt?: string
  createTime?: string
  updateTime?: string
}

export interface SiteIncidentLog {
  id: number
  incidentNo?: string
  venueId?: number
  spaceId?: number
  shiftId?: number
  taskId?: number
  reservationId?: number
  orderId?: number
  assetId?: number
  complaintId?: number
  incidentType?: string
  severity?: string
  status?: string
  occurredAt?: string
  reportedBy?: number
  description?: string
  resolution?: string
  sourceType?: string
  createTime?: string
  updateTime?: string
}

export const siteOpsApi = {
  shifts: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<SiteShift>>('/admin/site/shifts', { params, ...config }),
  createShift: (payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<SiteShift>('/admin/site/shifts', payload, config),
  updateShift: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.put<SiteShift>(`/admin/site/shifts/${id}`, payload, config),
  clockIn: (id: number, config?: HttpRequestConfig) =>
    http.post<SiteShift>(`/admin/site/shifts/${id}/clock-in`, undefined, config),
  clockOut: (id: number, config?: HttpRequestConfig) =>
    http.post<SiteShift>(`/admin/site/shifts/${id}/clock-out`, undefined, config),
  tasks: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<SiteTask>>('/admin/site/tasks', { params, ...config }),
  createTask: (payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<SiteTask>('/admin/site/tasks', payload, config),
  updateTask: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.put<SiteTask>(`/admin/site/tasks/${id}`, payload, config),
  startTask: (id: number, config?: HttpRequestConfig) =>
    http.post<SiteTask>(`/admin/site/tasks/${id}/start`, undefined, config),
  completeTask: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<SiteTask>(`/admin/site/tasks/${id}/complete`, payload, config),
  cancelTask: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<SiteTask>(`/admin/site/tasks/${id}/cancel`, payload, config),
  handovers: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<SiteShiftHandover>>('/admin/site/handovers', { params, ...config }),
  submitHandover: (payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<SiteShiftHandover>('/admin/site/handovers', payload, config),
  acceptHandover: (id: number, config?: HttpRequestConfig) =>
    http.post<SiteShiftHandover>(`/admin/site/handovers/${id}/accept`, undefined, config),
  incidents: (params?: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<SiteIncidentLog>>('/admin/site/incidents', { params, ...config }),
  recordIncident: (payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.post<SiteIncidentLog>('/admin/site/incidents', payload, config),
  resolveIncident: (id: number, payload: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.put<SiteIncidentLog>(`/admin/site/incidents/${id}/resolve`, payload, config)
}
