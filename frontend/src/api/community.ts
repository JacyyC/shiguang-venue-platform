import http, { type HttpRequestConfig } from './http'

export interface CursorPage<T> {
  records: T[]
  nextCursor: number | null
  hasMore: boolean
}

export interface CommunityUserSummary {
  id: number | null
  nickname: string
  avatar?: string | null
}

export interface CommunityGroup {
  id: number
  groupNo?: string
  name: string
  description?: string
  ownerUserId: number
  owner?: CommunityUserSummary
  visibility: 'PLATFORM_VISIBLE' | 'MEMBERS_ONLY'
  joinPolicy: 'DIRECT' | 'REVIEW_REQUIRED'
  /** 字典 community_group_category 的值；存量未分类小组为空，前端归「其他」分区。 */
  categoryCode?: string
  status: string
  currentMemberRole?: 'OWNER' | 'ADMIN' | 'MEMBER'
  currentMemberStatus?: 'PENDING' | 'ACTIVE' | 'REJECTED' | 'LEFT' | 'REMOVED'
  memberCount?: number
  versionNo: number
  createTime?: string
  updateTime?: string
}

export interface CommunityMember {
  id: number
  groupId: number
  userId: number
  user?: CommunityUserSummary
  role: 'OWNER' | 'ADMIN' | 'MEMBER'
  status: string
  joinedAt?: string
  createTime?: string
}

export interface CommunityPost {
  id: number
  postNo?: string
  groupId: number
  groupName?: string
  /** 所属小组可见范围：PLATFORM_VISIBLE（广场可见）/ MEMBERS_ONLY（仅成员）；列表与详情端点均返回（S03 起详情为受控聚合读取）。 */
  groupVisibility?: 'PLATFORM_VISIBLE' | 'MEMBERS_ONLY'
  /** 所属小组类别（字典 community_group_category；未分类为空），S04 起随列表与详情返回，用于静物场景派生。 */
  groupCategory?: string
  authorUserId: number
  author?: CommunityUserSummary
  title: string
  content: string
  status: string
  versionNo: number
  publishedAt?: string
  createTime?: string
  updateTime?: string
}

export interface CommunityComment {
  id: number
  commentNo?: string
  postId: number
  groupId: number
  authorUserId: number
  author?: CommunityUserSummary
  content: string
  status: string
  versionNo: number
  publishedAt?: string
  createTime?: string
  updateTime?: string
}

export interface CommunityReport {
  id: number
  reportNo?: string
  targetType: 'GROUP' | 'POST' | 'COMMENT'
  targetId: number
  reason: string
  status: string
  decisionCode?: string
  closedAt?: string
  createTime?: string
}

export interface BlockedUser {
  id: number
  user: CommunityUserSummary
  createdAt?: string
}

export interface AdminReport extends CommunityReport {
  reporter: CommunityUserSummary
  assignedTo?: number
  internalNote?: string
  updateTime?: string
}

export interface AdminContent {
  targetType: 'GROUP' | 'POST' | 'COMMENT'
  id: number
  groupId?: number
  parentId?: number
  authorUserId: number
  title?: string
  content?: string
  status: string
  hiddenReasonCode?: string
  /** S03 起服务端出参（B4-G4）：判别组内处置 / 平台处置与执行者。 */
  hiddenScope?: 'GROUP' | 'PLATFORM' | string
  hiddenBy?: number | null
  hiddenAt?: string
  deletedAt?: string
  retainUntil?: string
  versionNo: number
}

export interface AdminEvent {
  id: number
  eventNo?: string
  eventType: string
  subjectType: string
  subjectId: number
  reportId?: number
  actorUserId?: number
  actorScope?: string
  sourceType?: string
  fromStatus?: string
  toStatus?: string
  reasonCode?: string
  reasonSummary?: string
  resultStatus: string
  occurredAt?: string
}

export interface CommunityErrorState {
  status?: number
  kind: 'login' | 'forbidden' | 'missing' | 'conflict' | 'rate-limit' | 'failed'
  message: string
}

const idempotencyHeaders = (key: string) => ({ headers: { 'Idempotency-Key': key } })

export const newIdempotencyKey = (scope: string) => {
  const random = globalThis.crypto?.randomUUID?.() || Math.random().toString(36).slice(2)
  return `${scope}-${Date.now()}-${random}`
}

export interface IdempotencyAttempt {
  keyFor(payload: unknown): string
  complete(): void
}

export const createIdempotencyAttempt = (scope: string): IdempotencyAttempt => {
  let key = ''
  let signature = ''
  return {
    keyFor(payload: unknown) {
      const nextSignature = JSON.stringify(payload ?? null)
      if (!key || signature !== nextSignature) {
        key = newIdempotencyKey(scope)
        signature = nextSignature
      }
      return key
    },
    complete() {
      key = ''
      signature = ''
    }
  }
}

export interface IdempotencyRegistry {
  keyFor(scope: string, payload: unknown): string
  complete(scope: string): void
}

export const createIdempotencyRegistry = (): IdempotencyRegistry => {
  const attempts = new Map<string, IdempotencyAttempt>()
  return {
    keyFor(scope, payload) {
      let attempt = attempts.get(scope)
      if (!attempt) {
        attempt = createIdempotencyAttempt(scope)
        attempts.set(scope, attempt)
      }
      return attempt.keyFor(payload)
    },
    complete(scope) {
      attempts.get(scope)?.complete()
      attempts.delete(scope)
    }
  }
}

export const communityError = (error: unknown, fallback = '请求失败，请稍后重试'): CommunityErrorState => {
  const response = (error as { response?: { status?: number; data?: { message?: string } }; message?: string })?.response
  const status = response?.status
  const serverMessage = response?.data?.message
  if (status === 401) return { status, kind: 'login', message: '登录已失效，请重新登录' }
  if (status === 403) return { status, kind: 'forbidden', message: '当前账号无权执行此操作' }
  if (status === 404) return { status, kind: 'missing', message: '内容已隐藏、已删除或不存在' }
  if (status === 409) return { status, kind: 'conflict', message: serverMessage || '操作已处理或内容已更新，请刷新后重试' }
  if (status === 429) return { status, kind: 'rate-limit', message: serverMessage || '操作过于频繁，请稍后再试' }
  return { status, kind: 'failed', message: serverMessage || fallback }
}

export interface CommunityEchoItem {
  postId: number
  groupId: number
  groupName?: string | null
  title: string
  excerpt: string
  authorNickname?: string | null
  publishedAt?: string | null
}

export const communityApi = {
  /** 首页「城市里的回声」公开摘要：仅 PLATFORM_VISIBLE 小组帖子（服务端过滤）。 */
  echo: (limit = 5, config?: HttpRequestConfig) =>
    http.get<CommunityEchoItem[]>('/community/echo', { params: { limit }, ...config }),
  groups: (params: { namePrefix?: string; category?: string; mine?: boolean; memberStatus?: 'ACTIVE' | 'PENDING' | 'ANY'; cursorId?: number; limit?: number } = {}) =>
    http.get<CursorPage<CommunityGroup>>('/community/groups', { params }),
  group: (groupId: number, config?: HttpRequestConfig) => http.get<CommunityGroup>(`/community/groups/${groupId}`, config),
  createGroup: (payload: { name: string; description?: string; visibility: 'PLATFORM_VISIBLE' | 'MEMBERS_ONLY'; joinPolicy: 'DIRECT' | 'REVIEW_REQUIRED'; category: string }, key: string) =>
    http.post<CommunityGroup>('/community/groups', payload, idempotencyHeaders(key)),
  updateGroup: (groupId: number, payload: { name: string; description?: string; visibility: 'PLATFORM_VISIBLE' | 'MEMBERS_ONLY'; joinPolicy: 'DIRECT' | 'REVIEW_REQUIRED'; category: string; versionNo: number }, key: string) =>
    http.put<CommunityGroup>(`/community/groups/${groupId}`, payload, idempotencyHeaders(key)),
  deleteGroup: (groupId: number, key: string) => http.delete<void>(`/community/groups/${groupId}`, idempotencyHeaders(key)),
  joinGroup: (groupId: number, key: string) =>
    http.post<CommunityMember>(`/community/groups/${groupId}/join`, undefined, idempotencyHeaders(key)),
  leaveGroup: (groupId: number, key: string, config?: HttpRequestConfig) =>
    http.post<void>(`/community/groups/${groupId}/leave`, undefined, { ...idempotencyHeaders(key), ...config }),
  members: (groupId: number, params: { status?: string; cursorId?: number; limit?: number } = {}, config?: HttpRequestConfig) =>
    http.get<CursorPage<CommunityMember>>(`/community/groups/${groupId}/members`, { params, ...config }),
  decideMembership: (groupId: number, memberId: number, payload: { decision: 'APPROVE' | 'REJECT'; reason?: string }, key: string) =>
    http.post<CommunityMember>(`/community/groups/${groupId}/members/${memberId}/decision`, payload, idempotencyHeaders(key)),
  changeMemberRole: (groupId: number, memberId: number, role: 'ADMIN' | 'MEMBER', key: string) =>
    http.put<CommunityMember>(`/community/groups/${groupId}/members/${memberId}/role`, { role }, idempotencyHeaders(key)),
  removeMember: (groupId: number, memberId: number, key: string) =>
    http.delete<void>(`/community/groups/${groupId}/members/${memberId}`, idempotencyHeaders(key)),
  groupModerate: (groupId: number, targetType: string, targetId: number, restore: boolean,
    payload: { reasonCode: string; internalNote?: string }, key: string) =>
    http.post<void>(`/community/groups/${groupId}/content/${targetType}/${targetId}/${restore ? 'restore' : 'hide'}`,
      payload, idempotencyHeaders(key)),
  posts: (params: { groupId?: number; authorId?: number; titlePrefix?: string; category?: string; visibilityScope?: 'PLATFORM_VISIBLE'; status?: 'PUBLISHED' | 'HIDDEN' | 'ALL'; cursorId?: number; limit?: number } = {}) =>
    http.get<CursorPage<CommunityPost>>('/community/posts', { params }),
  post: (postId: number) => http.get<CommunityPost>(`/community/posts/${postId}`),
  createPost: (payload: { groupId: number; title: string; content: string }, key: string) =>
    http.post<CommunityPost>('/community/posts', payload, idempotencyHeaders(key)),
  updatePost: (postId: number, payload: { title: string; content: string; versionNo: number }, key: string) =>
    http.put<CommunityPost>(`/community/posts/${postId}`, payload, idempotencyHeaders(key)),
  deletePost: (postId: number, key: string) => http.delete<void>(`/community/posts/${postId}`, idempotencyHeaders(key)),
  comments: (postId: number, params: { cursorId?: number; limit?: number } = {}) =>
    http.get<CursorPage<CommunityComment>>(`/community/posts/${postId}/comments`, { params }),
  createComment: (postId: number, content: string, key: string) =>
    http.post<CommunityComment>(`/community/posts/${postId}/comments`, { content }, idempotencyHeaders(key)),
  updateComment: (commentId: number, content: string, versionNo: number, key: string) =>
    http.put<CommunityComment>(`/community/comments/${commentId}`, { content, versionNo }, idempotencyHeaders(key)),
  deleteComment: (commentId: number, key: string) => http.delete<void>(`/community/comments/${commentId}`, idempotencyHeaders(key)),
  report: (payload: { targetType: 'GROUP' | 'POST' | 'COMMENT'; targetId: number; reason: string }, key: string) =>
    http.post<CommunityReport>('/community/reports', payload, idempotencyHeaders(key)),
  myReports: (params: { cursorId?: number; limit?: number } = {}) =>
    http.get<CursorPage<CommunityReport>>('/community/reports/mine', { params }),
  block: (userId: number, key: string) => http.post<void>(`/community/blocks/${userId}`, undefined, idempotencyHeaders(key)),
  unblock: (userId: number, key: string) => http.delete<void>(`/community/blocks/${userId}`, idempotencyHeaders(key)),
  blockedUsers: (params: { cursorId?: number; limit?: number } = {}) =>
    http.get<CursorPage<BlockedUser>>('/community/blocks', { params })
}

/**
 * 平台治理（内容审核）管理端接口。
 *
 * BATCH-04 S07：全部方法接受可选 `config`，治理面内统一传 `ISOLATED_REQUEST`——否则叶子权限被撤后
 * 任一 403 都会被全局拦截器整页跳去 `/403`，面内既看不到服务端文案（B4-G8）也丢了当前上下文。
 * 这与 B4-P9 在促销面的调用侧修正是同一条纪律。
 */
export const contentAdminApi = {
  reports: (params: { status?: string; targetType?: string; cursorId?: number; limit?: number } = {}, config?: HttpRequestConfig) =>
    http.get<CursorPage<AdminReport>>('/content-admin/reports', { params, ...config }),
  inspect: (targetType: string, targetId: number, reportId?: number, config?: HttpRequestConfig) =>
    http.get<AdminContent>(`/content-admin/content/${targetType}/${targetId}`, { params: { reportId }, ...config }),
  decideReport: (reportId: number,
    payload: { decision: 'TAKE' | 'RESOLVE' | 'DISMISS'; decisionCode: string; internalNote?: string }, key: string,
    config?: HttpRequestConfig) =>
    http.post<CommunityReport>(`/content-admin/reports/${reportId}/decision`, payload, { ...idempotencyHeaders(key), ...config }),
  moderate: (targetType: string, targetId: number, restore: boolean,
    payload: { reasonCode: string; internalNote?: string; reportId?: number }, key: string, config?: HttpRequestConfig) =>
    http.post<void>(`/content-admin/content/${targetType}/${targetId}/${restore ? 'restore' : 'hide'}`,
      payload, { ...idempotencyHeaders(key), ...config }),
  events: (params: { subjectType?: string; subjectId?: number; reportId?: number; cursorId?: number; limit?: number } = {}, config?: HttpRequestConfig) =>
    http.get<CursorPage<AdminEvent>>('/content-admin/audit/events', { params, ...config })
}
