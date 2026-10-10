import http from './http'
import type {
  ActivityStatus,
  ActivityType,
  LocationCommitmentStatus
} from './activity'

export type DirectorySourceType = 'ACTIVITY' | 'COMPANION'

/**
 * V02 日期目录读取合同：服务端统一时间序 + 真数计数，游标为不透明字符串，
 * 携带的筛选条件必须与首次查询一致（否则服务端 400 要求回到第一页）。
 */
export interface DirectoryQuery {
  keyword?: string
  types?: DirectorySourceType[]
  fromTime?: string
  toTime?: string
  venueId?: number
  areaCode?: string
  groupId?: number
  referencePostId?: number
  onlyAvailable?: boolean
  cursor?: string
  limit?: number
}

export interface DirectoryItem {
  sourceType: DirectorySourceType
  sourceId: number
  groupId: number
  groupName?: string | null
  referencePostId?: number | null
  organizerUserId: number
  organizerName?: string | null
  activityType: ActivityType
  title: string
  description?: string | null
  startTime: string
  endTime: string
  locationType: 'VENUE_SPACE' | 'CAMPUS_AREA'
  venueId?: number | null
  venueName?: string | null
  spaceId?: number | null
  spaceName?: string | null
  areaCode?: string | null
  commitmentStatus: LocationCommitmentStatus
  capacityTotal: number
  participantCount: number
  status: ActivityStatus
  linkedActivityId?: number | null
  viewerApplicationStatus?: string | null
  viewerParticipationRole?: string | null
  updateTime?: string | null
}

export interface DirectoryDateCount {
  date: string
  count: number
}

export interface DirectoryPage {
  records: DirectoryItem[]
  nextCursor: string | null
  hasMore: boolean
  total: number
  typeCounts: Partial<Record<DirectorySourceType, number>>
  dateCounts: DirectoryDateCount[]
}

export interface DirectoryDayGroup {
  date: string
  items: DirectoryItem[]
}

export interface DirectoryCounts {
  ACTIVITY?: number
  COMPANION?: number
}

/** 稳定类型化键：数字 ID 相同也不互相覆盖（合同 §3.2）。 */
export const directoryKey = (item: Pick<DirectoryItem, 'sourceType' | 'sourceId'>): string =>
  `${item.sourceType}:${item.sourceId}`

/** 发起者视角：本人是活动组织者或同行所有者（合同 §5，权威为服务端 roleType）。 */
export const isHostedByViewer = (item: DirectoryItem): boolean =>
  item.viewerParticipationRole === 'ORGANIZER' || item.viewerParticipationRole === 'OWNER'

/**
 * 按开始日期分组（业务时区：服务端返回无时区墙钟，前端不换算）。
 * 输入须保持服务端时间序；同一天的条目维持原有相对顺序。
 */
export const groupDirectoryByDate = (items: DirectoryItem[]): DirectoryDayGroup[] => {
  const groups: DirectoryDayGroup[] = []
  const indexByDate = new Map<string, number>()
  for (const item of items) {
    const date = item.startTime.slice(0, 10)
    const existing = indexByDate.get(date)
    if (existing === undefined) {
      indexByDate.set(date, groups.length)
      groups.push({ date, items: [item] })
    } else {
      groups[existing].items.push(item)
    }
  }
  return groups
}

const serializeQuery = (query: DirectoryQuery): Record<string, unknown> => ({
  ...query,
  types: query.types?.length ? query.types.join(',') : undefined
})

export const directoryApi = {
  directory: (query: DirectoryQuery = {}) =>
    http.get<DirectoryPage>('/community/directory', { params: serializeQuery(query) }),
  myHosted: (params: { cursor?: string; limit?: number } = {}) =>
    http.get<DirectoryPage>('/community/my/hosted', { params }),
  applicationMetrics: (targetType: 'ACTIVITY' | 'COMPANION_REQUEST', targetId: number) =>
    http.get<{ pendingApplications: number }>('/community/application-metrics', {
      params: { targetType, targetId }
    })
}
