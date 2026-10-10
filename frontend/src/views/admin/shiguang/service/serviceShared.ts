/**
 * 客服与反馈处理面的纯逻辑（SHIGUANG-BATCH-03-REFACTOR-20260914 S05）。
 *
 * 标签一律取服务端 `sys_dict_data` 中文（contracts.md §0 标签冲突规则、§2.3）；
 * 预览与字典同义不同词时取字典，字典与代码都没有的值不迁入（B3-T26/B3-T33）。
 * 这里只放可单测的映射与派生，不读写任何状态。
 */
import type {
  CustomerComplaint,
  CustomerServiceAdminCaseEvent,
  CustomerServiceCase,
  CustomerServiceAdminContext,
  VenueReview
} from '@/api/feedback'
import { formatHm } from '../adminShared'

function parsed(value: string | null | undefined): Date | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** `customer_service_case_status` 五项，无 `WAITING_USER`（B3-T26）。 */
export const CASE_STATUS_LABELS: Record<string, string> = {
  OPEN: '已打开',
  IN_PROGRESS: '处理中',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
  REOPENED: '已重开'
}

/** 队列筛选与徽标按预览顺序给出真实可达的五项。 */
export const CASE_STATUS_ORDER = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'REOPENED']

/** 投诉状态真实可达写入仅四项（B3-T39），其余字典值不列为筛选项。 */
export const COMPLAINT_STATUS_LABELS: Record<string, string> = {
  SUBMITTED: '已提交',
  TRIAGED: '已分诊',
  IN_PROGRESS: '处理中',
  WAITING_USER: '等待用户',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
  CANCELED: '已取消'
}

export const REACHABLE_COMPLAINT_STATUSES = ['SUBMITTED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED']

/** `complaint_type` 七项（§2.3）。 */
export const COMPLAINT_TYPE_LABELS: Record<string, string> = {
  EQUIPMENT: '设备问题',
  SERVICE_ATTITUDE: '服务态度',
  ORDER_PAYMENT: '订单支付',
  NOTICE_INFO: '公告信息',
  CLEANLINESS: '卫生环境',
  SITE: '现场秩序',
  OTHER: '其他问题'
}

export const SEVERITY_LABELS: Record<string, string> = { LOW: '低', MEDIUM: '中', HIGH: '高' }

export const ROOT_CAUSE_LABELS: Record<string, string> = {
  EQUIPMENT_MAINTENANCE: '设备维护',
  SERVICE_EXECUTION: '服务执行',
  ORDER_POLICY: '订单规则'
}

export const RESOLUTION_TYPE_LABELS: Record<string, string> = {
  EXPLAINED: '解释说明',
  MANUAL_FOLLOW_UP: '人工跟进',
  REFUND_REVIEW: '建议退款复核'
}

/** `review_status` 三项；预览「待审核」无真实对应，不迁入（B3-T33）。 */
export const REVIEW_STATUS_LABELS: Record<string, string> = {
  PUBLISHED: '已发布',
  FLAGGED: '待关注',
  HIDDEN: '已隐藏'
}

/** `review_moderation_action` 三项，取字典名（RESTORE 字典值为「恢复」）。 */
export const MODERATION_ACTIONS: Array<{ value: string; label: string }> = [
  { value: 'RESTORE', label: '恢复' },
  { value: 'HIDE', label: '隐藏' },
  { value: 'FLAG', label: '标记' }
]

/** 事件类型七值，沿用旧正文既有中文（B3-T28）。 */
export const CASE_EVENT_TYPE_LABELS: Record<string, string> = {
  CREATED: '工单已创建',
  ASSIGNED: '工单已分派',
  STATUS_CHANGED: '处理状态已更新',
  REPLY_ADDED: '客服回复',
  RESOLVED: '工单已解决',
  CLOSED: '工单已关闭',
  REOPENED: '工单已重开'
}

/** 三个人工复核标记：真实唯一写入点是 resolve（B3-T32），此处只做读取与表单值映射。 */
export const FOLLOW_UP_FLAGS: Array<{ key: 'requiresRefundReview' | 'requiresCouponReview' | 'requiresSiteFollowUp'; label: string }> = [
  { key: 'requiresRefundReview', label: '人工退款复核' },
  { key: 'requiresCouponReview', label: '人工发券复核' },
  { key: 'requiresSiteFollowUp', label: '现场人工跟进' }
]

export const caseStatusLabel = (value?: string | null) => CASE_STATUS_LABELS[value ?? ''] ?? value ?? ''
export const complaintStatusLabel = (value?: string | null) => COMPLAINT_STATUS_LABELS[value ?? ''] ?? value ?? ''
export const complaintTypeLabel = (value?: string | null) => COMPLAINT_TYPE_LABELS[value ?? ''] ?? value ?? ''
export const severityLabel = (value?: string | null) => SEVERITY_LABELS[value ?? ''] ?? value ?? ''
export const rootCauseLabel = (value?: string | null) => ROOT_CAUSE_LABELS[value ?? ''] ?? value ?? ''
export const resolutionTypeLabel = (value?: string | null) => RESOLUTION_TYPE_LABELS[value ?? ''] ?? value ?? ''
export const reviewStatusLabel = (value?: string | null) => REVIEW_STATUS_LABELS[value ?? ''] ?? value ?? ''
export const caseEventTypeLabel = (value?: string | null) => CASE_EVENT_TYPE_LABELS[value ?? ''] ?? value ?? '处理记录'

export type ServiceView = 'cases' | 'reviews' | 'context'

/** 深链兼容：`#reviews`/`#context` 决定初始页签，其余（含 `#case-<id>`）落在工单处理（§2.2）。 */
export function initialViewFromHash(hash: string): ServiceView {
  if (/#\s*reviews\b/.test(hash)) return 'reviews'
  if (/#\s*context\b/.test(hash)) return 'context'
  return 'cases'
}

/** `#case-<id>` 深链：命中队列时直接展开该工单（窄屏为详情态）。 */
export function caseIdFromHash(hash: string): number | null {
  const matched = /#case-(\d+)/.exec(hash)
  return matched ? Number(matched[1]) : null
}

/** 预览时间口径：今天 HH:mm、昨天 HH:mm，其余 M 月 D 日。 */
export function dayTimeLabel(value: string | null | undefined): string {
  const date = parsed(value)
  if (!date) return ''
  const now = new Date()
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (sameDay(date, now)) return `今天 ${formatHm(value)}`
  if (sameDay(date, yesterday)) return `昨天 ${formatHm(value)}`
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`
}

/** 队列与详情标题：`AdminCase` 无 title/content，只能按 `complaintId` 关联投诉（B3-T24）。 */
export interface CaseQueueRow {
  case: CustomerServiceCase
  complaint: CustomerComplaint | null
}

export function queueRows(cases: CustomerServiceCase[], complaints: CustomerComplaint[]): CaseQueueRow[] {
  const byId = new Map(complaints.map((item) => [item.id, item]))
  return cases.map((item) => ({ case: item, complaint: byId.get(item.complaintId ?? -1) ?? null }))
}

/**
 * 关键词按真实双查询取并集（B3-T25）：`cases` 侧服务端匹配 `case_no`/`customer_visible_reply`，
 * `complaints` 侧匹配 `title`/`content_sanitized`。这里只做已返回 id 集合的并，不在浏览器里比文本。
 */
export function filterQueueByKeyword(
  rows: CaseQueueRow[],
  caseMatches: Array<CustomerServiceCase | number>,
  complaintMatches: Array<CustomerComplaint | number>
): CaseQueueRow[] {
  const caseIds = new Set(caseMatches.map((item) => (typeof item === 'number' ? item : item.id)))
  const complaintIds = new Set(complaintMatches.map((item) => (typeof item === 'number' ? item : item.id)))
  return rows.filter((row) => caseIds.has(row.case.id) || (row.complaint && complaintIds.has(row.complaint.id)))
}

/** 队列单量文案与状态徽标共用。 */
export function queueTitle(row: CaseQueueRow): string {
  return row.complaint?.title?.trim() || row.case.caseNo || '（无标题）'
}

export function queuePriority(row: CaseQueueRow): string {
  return severityLabel(row.case.priority)
}

/** 优先级「高」时徽标用 attention 强调，与预览一致。 */
export function queueAttention(row: CaseQueueRow): boolean {
  return row.case.priority === 'HIGH'
}

/** 分派只按真实 `assignedAt` 说明事实，处理人身份不可得（B3-T27）。 */
export function assigneeText(item: CustomerServiceCase): string {
  return item.assignedAt ? '已分派' : '未分派'
}

export function flagsOf(item: CustomerServiceCase): string[] {
  return FOLLOW_UP_FLAGS.filter((flag) => Boolean(item[flag.key])).map((flag) => flag.label)
}

/** 解决弹层的三个勾选回显当前标记（真实唯一写入点）。 */
export function flagSelection(item: CustomerServiceCase | null): Record<string, boolean> {
  const selected = new Set(flagsOf(item ?? ({} as CustomerServiceCase)))
  return Object.fromEntries(FOLLOW_UP_FLAGS.map((flag) => [flag.key, selected.has(flag.label)]))
}

/** 终态判定：预览在已解决/已关闭时收起回复表单，与真实前置一致。 */
export function isTerminalCase(status?: string | null): boolean {
  return status === 'RESOLVED' || status === 'CLOSED'
}

/** 解决前置：服务端只接受 `IN_PROGRESS`/`REOPENED`（B3-T31）。 */
export function canResolveCase(status?: string | null): boolean {
  return status === 'IN_PROGRESS' || status === 'REOPENED'
}

/** 关闭前置 `RESOLVED`，重开前置 `CLOSED|RESOLVED`。 */
export function canCloseCase(status?: string | null): boolean {
  return status === 'RESOLVED'
}

export function canReopenCase(status?: string | null): boolean {
  return status === 'CLOSED' || status === 'RESOLVED'
}

/** 转处理中：真实唯一合法目标 `IN_PROGRESS`，前置 `OPEN|REOPENED`（B3-T29）。 */
export function canMoveToInProgress(status?: string | null): boolean {
  return status === 'OPEN' || status === 'REOPENED'
}

export interface TimelineRow {
  label: string
  time: string
  note: string
  internalNote: string
  actor: string
}

/**
 * 处理记录时间线：`fromStatus/toStatus` 必须过状态标签表而非原样英文（B3-T28），
 * `internalNoteSanitized` 单独成行并标注可见范围。
 */
export function timelineRows(events: CustomerServiceAdminCaseEvent[]): TimelineRow[] {
  return events.map((event) => ({
    label: caseEventTypeLabel(event.eventType),
    time: dayTimeLabel(event.occurredAt),
    note: event.noteSanitized || '',
    internalNote: event.internalNoteSanitized || '',
    actor: event.actorUsername || ''
  }))
}

export function statusChangeText(event: CustomerServiceAdminCaseEvent): string {
  const from = caseStatusLabel(event.fromStatus)
  const to = caseStatusLabel(event.toStatus)
  if (!from || !to || from === to) return ''
  return `${from} → ${to}`
}

/** 评分格：真实四个分值，主位取 `overallRating`，其余进元信息。 */
export function ratingText(review: VenueReview): string {
  const value = Number(review.overallRating)
  return Number.isFinite(value) ? value.toFixed(1) : '—'
}

/** 元信息不含用户名与场地名（服务端不返回），只列真实可得项（B3-T34）。 */
export function reviewMeta(review: VenueReview): string {
  const parts = [
    review.reviewNo ? `评价 ${review.reviewNo}` : null,
    dayTimeLabel(review.submittedAt) || null,
    review.sensitiveFlag ? '含敏感标记' : null
  ]
  return parts.filter(Boolean).join(' · ') || '无附加信息'
}

export function reviewDetailLine(review: VenueReview): string {
  const scores = [
    { label: '场地', value: review.venueRating },
    { label: '空间', value: review.spaceRating },
    { label: '服务', value: review.serviceRating }
  ].filter((item) => item.value != null)
  return scores.length ? scores.map((item) => `${item.label} ${item.value}`).join(' · ') : '未分项评分'
}

/** 关联上下文：沿投诉 `id` ↔ 工单 `complaintId` 关联，不虚构聚合（B3-T36）。 */
export interface ContextRow {
  complaint: Record<string, unknown>
  cases: Array<Record<string, unknown>>
}

export function contextRows(payload: CustomerServiceAdminContext | null): ContextRow[] {
  if (!payload) return []
  const complaints = (payload.complaints ?? []) as Array<Record<string, unknown>>
  const cases = (payload.cases ?? []) as Array<Record<string, unknown>>
  return complaints.map((complaint) => ({
    complaint,
    cases: cases.filter((item) => item.complaintId === complaint.id)
  }))
}

export function contextCounts(payload: CustomerServiceAdminContext | null) {
  return {
    complaintCount: payload?.complaintCount ?? 0,
    caseCount: payload?.caseCount ?? 0,
    reviewCount: payload?.reviewCount ?? 0
  }
}
