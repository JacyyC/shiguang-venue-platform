import http from './http'
import type { HttpRequestConfig } from './http'
import type { CursorPage } from './community'

export type ActivityType = 'STUDY' | 'SPORTS' | 'BOARD_GAME' | 'WORKSHOP' | 'OTHER'
export type ActivityStatus = 'DRAFT' | 'PUBLISHED' | 'FULL' | 'CLOSED' | 'CANCELED' | 'EXPIRED'
export type ActivityLocationType = 'VENUE_SPACE' | 'CAMPUS_AREA'
export type LocationCommitmentStatus = 'CONFIRMED' | 'UNCONFIRMED' | 'DEGRADED'

export interface ActivityLocation {
  type: ActivityLocationType
  venueId?: number | null
  spaceId?: number | null
  areaCode?: string | null
  commitmentStatus: LocationCommitmentStatus
}

export interface ActivityLocationInput {
  type: ActivityLocationType
  venueId?: number
  spaceId?: number
  areaCode?: string
  reservationId?: number
}

export interface CommunityActivity {
  id: number
  activityNo: string
  groupId: number
  referencePostId?: number | null
  organizerUserId: number
  /** 卡片“来源”展示：发起小组名；服务端 join，缺失时前端回落默认文案。 */
  groupName?: string | null
  organizerName?: string | null
  activityType: ActivityType
  title: string
  description?: string | null
  startTime: string
  endTime: string
  location: ActivityLocation
  capacityTotal: number
  participantCount: number
  status: ActivityStatus
  versionNo: number
  publishedAt?: string | null
  createTime?: string | null
  updateTime?: string | null
}

export interface CompanionRequest {
  id: number
  requestNo: string
  groupId: number
  activityId?: number | null
  referencePostId?: number | null
  ownerUserId: number
  groupName?: string | null
  organizerName?: string | null
  activityType: ActivityType
  title: string
  description?: string | null
  windowStart: string
  windowEnd: string
  location: ActivityLocation
  capacityTotal: number
  participantCount: number
  status: ActivityStatus
  versionNo: number
  publishedAt?: string | null
  createTime?: string | null
  updateTime?: string | null
}

export interface ActivityApplication {
  id: number
  applicationNo: string
  targetType: 'ACTIVITY' | 'COMPANION_REQUEST'
  targetId: number
  applicantUserId: number
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN'
  reasonCode?: string | null
  appliedAt?: string | null
  decidedAt?: string | null
  withdrawnAt?: string | null
  versionNo: number
}

export interface ActivityParticipation {
  id: number
  participationNo: string
  targetType: 'ACTIVITY' | 'COMPANION_REQUEST'
  targetId: number
  userId: number
  roleType: 'ORGANIZER' | 'OWNER' | 'PARTICIPANT'
  status: 'ACTIVE' | 'LEFT' | 'CANCELED'
  joinedAt?: string | null
  leftAt?: string | null
}

export interface ActivityViewerState {
  application?: ActivityApplication | null
  participation?: ActivityParticipation | null
}

export interface CompanionMatch {
  decisionId: number
  candidate: CompanionRequest
  ruleVersion: string
  score: number
  reasonCodes: string[]
  decidedAt?: string | null
}

export interface CompanionMatchPage {
  records: CompanionMatch[]
  ruleVersion: string
}

export interface DiscoveryQuery {
  keyword?: string
  activityType?: ActivityType
  fromTime?: string
  toTime?: string
  venueId?: number
  areaCode?: string
  cursorId?: number
  limit?: number
}

export interface CreateActivityPayload {
  groupId: number
  referencePostId?: number
  activityType: ActivityType
  title: string
  description?: string
  startTime: string
  endTime: string
  capacityTotal: number
  location: ActivityLocationInput
}

export interface CreateCompanionPayload {
  groupId: number
  activityId?: number
  referencePostId?: number
  activityType: ActivityType
  title: string
  description?: string
  windowStart?: string
  windowEnd?: string
  capacityTotal: number
  location?: ActivityLocationInput
}

const writeConfig = (key: string) => ({ headers: { 'Idempotency-Key': key } })

export const activityApi = {
  activities: (params: DiscoveryQuery = {}, config?: HttpRequestConfig) =>
    http.get<CursorPage<CommunityActivity>>('/community/activities', { params, ...config }),
  activity: (activityId: number) =>
    http.get<CommunityActivity>(`/community/activities/${activityId}`),
  createActivity: (payload: CreateActivityPayload, key: string) =>
    http.post<CommunityActivity>('/community/activities', payload, writeConfig(key)),
  publishActivity: (activityId: number, key: string) =>
    http.post<CommunityActivity>(`/community/activities/${activityId}/publish`, undefined, writeConfig(key)),
  updateActivityLocation: (activityId: number, payload: { versionNo: number; location: ActivityLocationInput }, key: string) =>
    http.put<CommunityActivity>(`/community/activities/${activityId}/location`, payload, writeConfig(key)),
  closeActivity: (activityId: number, reasonCode: string, key: string) =>
    http.post<CommunityActivity>(`/community/activities/${activityId}/close`, { reasonCode }, writeConfig(key)),
  cancelActivity: (activityId: number, reasonCode: string, key: string) =>
    http.post<CommunityActivity>(`/community/activities/${activityId}/cancel`, { reasonCode }, writeConfig(key)),
  applyActivity: (activityId: number, key: string) =>
    http.post<ActivityApplication>(`/community/activities/${activityId}/applications`, undefined, writeConfig(key)),
  activityApplications: (activityId: number, params: { status?: string; cursorId?: number; limit?: number } = {}) =>
    http.get<CursorPage<ActivityApplication>>(`/community/activities/${activityId}/applications`, { params }),
  activityParticipants: (activityId: number, params: { cursorId?: number; limit?: number } = {}) =>
    http.get<CursorPage<ActivityParticipation>>(`/community/activities/${activityId}/participants`, { params }),
  activityViewerState: (activityId: number) =>
    http.get<ActivityViewerState>(`/community/activities/${activityId}/my-state`),
  leaveActivity: (activityId: number, key: string) =>
    http.post<ActivityParticipation>(`/community/activities/${activityId}/leave`, undefined, writeConfig(key)),

  companions: (params: DiscoveryQuery = {}, config?: HttpRequestConfig) =>
    http.get<CursorPage<CompanionRequest>>('/community/companion-requests', { params, ...config }),
  companion: (requestId: number) =>
    http.get<CompanionRequest>(`/community/companion-requests/${requestId}`),
  createCompanion: (payload: CreateCompanionPayload, key: string) =>
    http.post<CompanionRequest>('/community/companion-requests', payload, writeConfig(key)),
  publishCompanion: (requestId: number, key: string) =>
    http.post<CompanionRequest>(`/community/companion-requests/${requestId}/publish`, undefined, writeConfig(key)),
  updateCompanionLocation: (requestId: number, payload: { versionNo: number; location: ActivityLocationInput }, key: string) =>
    http.put<CompanionRequest>(`/community/companion-requests/${requestId}/location`, payload, writeConfig(key)),
  closeCompanion: (requestId: number, reasonCode: string, key: string) =>
    http.post<CompanionRequest>(`/community/companion-requests/${requestId}/close`, { reasonCode }, writeConfig(key)),
  cancelCompanion: (requestId: number, reasonCode: string, key: string) =>
    http.post<CompanionRequest>(`/community/companion-requests/${requestId}/cancel`, { reasonCode }, writeConfig(key)),
  applyCompanion: (requestId: number, key: string) =>
    http.post<ActivityApplication>(`/community/companion-requests/${requestId}/applications`, undefined, writeConfig(key)),
  companionApplications: (requestId: number, params: { status?: string; cursorId?: number; limit?: number } = {}) =>
    http.get<CursorPage<ActivityApplication>>(`/community/companion-requests/${requestId}/applications`, { params }),
  companionParticipants: (requestId: number, params: { cursorId?: number; limit?: number } = {}) =>
    http.get<CursorPage<ActivityParticipation>>(`/community/companion-requests/${requestId}/participants`, { params }),
  companionViewerState: (requestId: number) =>
    http.get<ActivityViewerState>(`/community/companion-requests/${requestId}/my-state`),
  leaveCompanion: (requestId: number, key: string) =>
    http.post<ActivityParticipation>(`/community/companion-requests/${requestId}/leave`, undefined, writeConfig(key)),
  matches: (requestId: number, limit = 20) =>
    http.get<CompanionMatchPage>(`/community/companion-requests/${requestId}/matches`, { params: { limit } }),
  refreshMatches: (requestId: number, limit: number, key: string) =>
    http.post<CompanionMatchPage>(`/community/companion-requests/${requestId}/matches/refresh`, { limit }, writeConfig(key)),

  acceptApplication: (applicationId: number, key: string) =>
    http.post<ActivityApplication>(`/community/applications/${applicationId}/accept`, undefined, writeConfig(key)),
  rejectApplication: (applicationId: number, reasonCode: string, key: string) =>
    http.post<ActivityApplication>(`/community/applications/${applicationId}/reject`, { reasonCode }, writeConfig(key)),
  withdrawApplication: (applicationId: number, key: string) =>
    http.post<ActivityApplication>(`/community/applications/${applicationId}/withdraw`, undefined, writeConfig(key))
}
