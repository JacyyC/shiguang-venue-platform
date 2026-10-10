/**
 * 平台内容治理面共享展示层（SHIGUANG-BATCH-04-REFACTOR-20260914 S07）。
 *
 * 全部为可测纯函数/词表，来源是**真实枚举与写入方**（B4-G9/G10/G12），不是预览的合成词表：
 *   * 状态词表以 `be/community/enums/*`、`mig/V15__community_governance.sql` 的值域为准，
 *     文案沿用冻结参考 `ref/g/g.js:7` 的 G 组中文（B4-G12 要求把产品里「待处理/已处理/正常」
 *     统一到预览的「待受理/已解决/有效」），并补上预览缺的真实值（`PURGED`、成员/屏蔽/注销类）。
 *   * `eventType` 没有 Java 枚举（`varchar(64)`，30 种散落字面量，B4-G10），因此这里只能按
 *     写入方全集手工建表，未知值一律回落原文；`REPORT_DECIDED` 一个类型承载受理/解决/驳回三种
 *     动作，按 `toStatus` 派生动作中文（与预览按动作命名 `kind` 的呈现一致）。
 *   * 执行者只有 `actorUserId` + `actorScope` + `sourceType`（无昵称，B4-G9），故只能显示 ID + 范围。
 */

/* --------------------------------------------------------------- 举报 */

/** `CommunityReportStatus`：OPEN/IN_REVIEW/RESOLVED/DISMISSED。 */
export const REPORT_STATUS_LABELS: Record<string, string> = {
  OPEN: '待受理',
  IN_REVIEW: '审核中',
  RESOLVED: '已解决',
  DISMISSED: '已驳回'
}

export function reportStatusLabel(status: string | null | undefined): string {
  if (!status) return '—'
  return REPORT_STATUS_LABELS[status] ?? status
}

/** 结案动作只接受这三个值（`CommunityGovernanceService.java:128`）。 */
export const REPORT_DECISIONS = ['TAKE', 'RESOLVE', 'DISMISS'] as const

export const DECISION_TITLES: Record<string, string> = {
  TAKE: '受理这条举报',
  RESOLVE: '解决举报',
  DISMISS: '驳回举报'
}

/** 预览的默认结论代码（`ref/g/moderation.js:31`）；服务端只要求非空，值域自由。 */
export const DECISION_CODE_DEFAULTS: Record<string, string> = {
  TAKE: 'REVIEW_STARTED',
  RESOLVE: 'CONTENT_REVIEWED',
  DISMISS: 'NO_VIOLATION'
}

/* --------------------------------------------------------------- 内容 */

/**
 * 帖子/评论 `CommunityContentStatus` 与小组 `CommunityGroupStatus` 的并集：
 * PUBLISHED/DELETED/PURGED（内容）+ ACTIVE/HIDDEN/DELETED/PURGED（小组）。
 * 预览 `G.labels` 缺 `PURGED`，产品原表缺 `DELETED/PURGED`（B4-G12 两条都补）。
 */
export const CONTENT_STATUS_LABELS: Record<string, string> = {
  PUBLISHED: '已发布',
  ACTIVE: '有效',
  HIDDEN: '已隐藏',
  DELETED: '已删除',
  PURGED: '已清除'
}

export function contentStatusLabel(status: string | null | undefined): string {
  if (!status) return '—'
  return CONTENT_STATUS_LABELS[status] ?? status
}

/** 角色可见性/加入策略沿用既有产品文案（`fe/utils/presentation.ts`），不在本面另造。 */
export const VISIBILITY_LABELS: Record<string, string> = {
  PLATFORM_VISIBLE: '平台可见',
  MEMBERS_ONLY: '仅成员可见'
}

export const JOIN_POLICY_LABELS: Record<string, string> = {
  DIRECT: '直接加入',
  REVIEW_REQUIRED: '需要审核'
}

/** 内容来源判别（B4-G4）：DB 有 `hidden_scope`，S03 起 `AdminContent` 才出参。 */
export const HIDDEN_SCOPE_LABELS: Record<string, string> = {
  GROUP: '组内处置',
  PLATFORM: '平台处置'
}

export function hiddenScopeLabel(scope: string | null | undefined): string {
  if (!scope) return '—'
  return HIDDEN_SCOPE_LABELS[scope] ?? scope
}

export const MODERATE_REASON_DEFAULTS = {
  hide: 'CONTENT_VIOLATION',
  restore: 'REVIEW_RESTORED'
} as const

/* --------------------------------------------------------- 审计用词表 */

/** 审计 `subjectType`（`mig/V15:361-362`）。`REPORT` 是参考也保留的对象类型。 */
export const SUBJECT_TYPE_LABELS: Record<string, string> = {
  GROUP: '小组',
  POST: '帖子',
  COMMENT: '评论',
  REPORT: '举报',
  MEMBER: '成员',
  BLOCK: '屏蔽',
  USER: '用户'
}

export function subjectTypeLabel(type: string | null | undefined): string {
  if (!type) return '—'
  return SUBJECT_TYPE_LABELS[type] ?? type
}

/** 目标类型（举报筛选与内容检查用），与 `subjectType` 前三个同义。 */
export const TARGET_TYPE_LABELS: Record<string, string> = {
  GROUP: '小组',
  POST: '帖子',
  COMMENT: '评论'
}

export function targetTypeLabel(type: string | null | undefined): string {
  if (!type) return '—'
  return TARGET_TYPE_LABELS[type] ?? type
}

/**
 * `eventType` 全集（B4-G10：无枚举常量，按写入方逐个登记）。
 * 键＝服务端字面量，值＝动作中文；未知值回落原文。
 */
export const EVENT_TYPE_LABELS: Record<string, string> = {
  CONTENT_INSPECTED: '检查内容',
  REPORT_DECIDED: '举报决定',
  REPORT_SUBMITTED: '提交举报',
  GROUP_HIDDEN: '隐藏小组',
  GROUP_RESTORED: '恢复小组',
  GROUP_CREATED: '创建小组',
  GROUP_UPDATED: '编辑小组',
  GROUP_DELETED: '删除小组',
  GROUP_PURGED: '清除小组',
  POST_HIDDEN: '隐藏帖子',
  POST_RESTORED: '恢复帖子',
  POST_CREATED: '发布帖子',
  POST_UPDATED: '编辑帖子',
  POST_DELETED: '删除帖子',
  POST_PURGED: '清除帖子',
  COMMENT_HIDDEN: '隐藏评论',
  COMMENT_RESTORED: '恢复评论',
  COMMENT_CREATED: '发表评论',
  COMMENT_UPDATED: '编辑评论',
  COMMENT_DELETED: '删除评论',
  COMMENT_PURGED: '清除评论',
  MEMBERSHIP_REQUESTED: '申请加入',
  MEMBERSHIP_DECIDED: '处理加入申请',
  MEMBER_ROLE_CHANGED: '变更成员角色',
  MEMBER_REMOVED: '移除成员',
  MEMBER_LEFT: '退出小组',
  USER_BLOCKED: '屏蔽用户',
  USER_UNBLOCKED: '解除屏蔽',
  REPORT_REASON_PURGED: '清除举报缘由',
  USER_DEIDENTIFIED: '注销用户'
}

/**
 * 审计行主标题：`eventType` 中文 + 对象。预览把「受理/解决/驳回」按动作命名（`kind`），
 * 真实只有一个 `REPORT_DECIDED`，因此按 `toStatus` 派生，避免三件事看起来一模一样。
 */
export function eventActionLabel(event: { eventType?: string | null; toStatus?: string | null }): string {
  const type = event?.eventType ?? ''
  if (type === 'REPORT_DECIDED') {
    const to = event?.toStatus ?? ''
    if (to === 'IN_REVIEW') return '受理举报'
    if (to === 'RESOLVED') return '解决举报'
    if (to === 'DISMISSED') return '驳回举报'
    return '举报决定'
  }
  return EVENT_TYPE_LABELS[type] ?? (type || '—')
}

/** 审计 `actorScope`（`mig/V15:363-364`）。 */
export const ACTOR_SCOPE_LABELS: Record<string, string> = {
  SELF: '本人',
  GROUP: '组内',
  CONTENT: '平台内容运营',
  SYSTEM: '系统'
}

export function actorScopeLabel(scope: string | null | undefined): string {
  if (!scope) return '—'
  return ACTOR_SCOPE_LABELS[scope] ?? scope
}

/** 审计 `sourceType`（`mig/V15:365-366`）。 */
export const SOURCE_TYPE_LABELS: Record<string, string> = {
  USER: '用户',
  GROUP_ADMIN: '组管理员',
  CONTENT_OPERATOR: '平台内容运营',
  SYSTEM_ADMIN: '系统管理员',
  SYSTEM: '系统',
  SCHEDULED_JOB: '定时任务'
}

export function sourceTypeLabel(type: string | null | undefined): string {
  if (!type) return '—'
  return SOURCE_TYPE_LABELS[type] ?? type
}

export const RESULT_STATUS_LABELS: Record<string, string> = {
  SUCCEEDED: '记录成功',
  REJECTED: '已拒绝',
  FAILED: '记录失败'
}

export function resultStatusLabel(status: string | null | undefined): string {
  if (!status) return '—'
  return RESULT_STATUS_LABELS[status] ?? status
}

/**
 * 成员状态（只在审计 `fromStatus/toStatus` 出现，`CommunityMemberStatus`）：
 * 预览缺 `PENDING/LEFT/REMOVED`，产品原表也没有（B4-G12 补齐）。
 */
export const MEMBER_STATUS_LABELS: Record<string, string> = {
  PENDING: '待审核',
  ACTIVE: '有效',
  REJECTED: '已拒绝',
  LEFT: '已退出',
  REMOVED: '已移除'
}

/** 注销/缘由清理两个保留期状态（`CommunityRetentionService`）。 */
export const RETENTION_STATUS_LABELS: Record<string, string> = {
  DEIDENTIFIED: '已注销',
  REASON_RETAINED: '缘由保留中',
  REASON_PURGED: '缘由已清除'
}

/**
 * 审计行 `fromStatus → toStatus` 中文：内容/小组、举报、成员、保留期四类真实值域并集，
 * 未知值原样显示（不把广告域词条当作内容状态，见 S02 §6 的非本域清单）。
 */
export const AUDIT_STATUS_LABELS: Record<string, string> = {
  ...CONTENT_STATUS_LABELS,
  ...REPORT_STATUS_LABELS,
  ...MEMBER_STATUS_LABELS,
  ...RETENTION_STATUS_LABELS
}

export function auditStatusLabel(status: string | null | undefined): string {
  if (!status) return '—'
  return AUDIT_STATUS_LABELS[status] ?? status
}

/* --------------------------------------------------------------- 其它 */

/** 举报缘由的保留期（服务端 `reasonRetainUntil = closedAt + 180d`）用于说明证据边界。 */
export const REPORT_RETAIN_DAYS = 180

/** 游标分页每页条数（服务端 `limit` 默认 20、上限 100）。 */
export const CURSOR_PAGE_SIZE = 20

/** 报告编号可能是 `CR-{uuid}`，列表里只展示前 8 位可读片段。 */
export function shortReportNo(reportNo: string | null | undefined): string {
  if (!reportNo) return '—'
  return reportNo.length > 10 ? `${reportNo.slice(0, 8)}…` : reportNo
}

/** 事件编号 `CGE-{uuid}` 同理。 */
export function shortEventNo(eventNo: string | null | undefined, id?: number | null): string {
  if (eventNo) return eventNo.length > 10 ? `${eventNo.slice(0, 8)}…` : eventNo
  return id != null ? `#${id}` : '—'
}

/** 处置按钮可用性（与参考 `M.detailView` 的布尔表达式同构，但只按真实状态判定）。 */
export function canHide(status: string | null | undefined): boolean {
  return status === 'PUBLISHED' || status === 'ACTIVE'
}

export function canRestore(status: string | null | undefined): boolean {
  return status === 'HIDDEN'
}

/** 仅 `PUBLISHED/ACTIVE↔HIDDEN` 可处置；`DELETED/PURGED` 既不隐藏也不恢复（服务端 409）。 */
export function moderationHint(status: string | null | undefined): string {
  if (status === 'DELETED') return '已删除状态不提供隐藏或恢复命令；正文保留至证据保留期后清除。'
  if (status === 'PURGED') return '该目标已清除，仅保留审计记录。'
  return ''
}
