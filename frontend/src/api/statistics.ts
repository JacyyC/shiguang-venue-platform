import http, { type HttpRequestConfig } from './http'

export interface DashboardSummary {
  todayReservations: number
  pendingReviews: number
  totalUsers: number
  revenue: number
  occupancyRate: number
}

export interface ChartPoint {
  label: string
  value: number
}

export const statisticsApi = {
  dashboard: (params: Record<string, unknown>, config?: HttpRequestConfig) => http.get<DashboardSummary>('/admin/statistics/dashboard', { params, ...config }),
  topSpaces: (params: Record<string, unknown>) => http.get<ChartPoint[]>('/admin/statistics/top-spaces', { params }),
  revenue: (params: Record<string, unknown>) => http.get<ChartPoint[]>('/admin/statistics/revenue', { params })
}
