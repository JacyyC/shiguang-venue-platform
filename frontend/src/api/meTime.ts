import http, { type PageResult } from './http'

/** 「我的时间」聚合条目：sourceType 区分权威域，status 保留原始业务状态不做归一。 */
export interface MeTimeItem {
  sourceType: 'RESERVATION' | 'ACTIVITY' | 'COMPANION_REQUEST'
  sourceId: number
  title: string
  location?: string | null
  startTime?: string | null
  endTime?: string | null
  status: string
  pending: boolean
  detailPath: string
}

/** 标签精确计数：服务端 SQL count，不取截断集合。 */
export interface MeTimeCounts {
  pendingApplications: number
  activeParticipations: number
  hosted: number
}

export const meTimeApi = {
  myTime: (params: { pageNum?: number; pageSize?: number } = {}) =>
    http.get<PageResult<MeTimeItem>>('/me/time', { params }),
  counts: () => http.get<MeTimeCounts>('/me/time/counts')
}
