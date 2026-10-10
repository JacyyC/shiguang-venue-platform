/**
 * 公告与通知触达面的纯逻辑（SHIGUANG-BATCH-03-REFACTOR-20260914 S06）。
 *
 * 标签以服务端 `sys_dict_data` 为准（contracts.md §3.3）：字典有值取字典词，
 * 字典缺项但服务端确实写入的值按同词补齐并在台账说明，字典有而代码永不写入的值不列为选项。
 * 这里只放可单测的映射与派生，不读写任何状态。
 */
import type {
  AudiencePreviewResponse,
  NoticeAudienceResponse,
  NoticeDeliveryBatchResponse,
  NoticeDeliveryOutboxResponse,
  NoticeDeliveryOverviewResponse,
  NoticeExperimentResponse,
  NoticeExperimentResultResponse,
  NotificationProviderOption
} from '@/api/types'
import { formatHm } from '../adminShared'

export type NoticeView = 'compose' | 'delivery' | 'experiments'

/** 三条旧入口共用一个组件：初始页签由路由名决定，页内 hash 可覆盖（B3-T40、§3.2）。 */
export function initialViewFromRoute(routeName: string | undefined, hash: string): NoticeView {
  const fromHash = hash.replace(/^#/, '')
  if (fromHash === 'compose' || fromHash === 'delivery' || fromHash === 'experiments') return fromHash
  if (routeName === 'SystemNoticeDelivery') return 'delivery'
  if (routeName === 'SystemNoticeExperiments') return 'experiments'
  return 'compose'
}

/** `?batch=<batchId>`：发布后「查看投递批次」跳到触达页签并定位真实批次（B3-T46）。 */
export function batchIdFromQuery(value: unknown): number | null {
  const parsed = Number(Array.isArray(value) ? value[0] : value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

/** `notice_delivery_batch_status` 真实写入五项；`DRAFT/READY/DISPATCHING/CANCELED` 代码不写，不列为选项（B3-T55）。 */
export const BATCH_STATUS_LABELS: Record<string, string> = {
  PENDING: '排队中',
  PROCESSING: '投递中',
  COMPLETED: '已完成',
  PARTIAL_FAILED: '部分失败',
  FAILED: '失败'
}

/** 受众投递状态：代码写入五项，字典缺 `RETRY_WAITING/EXCLUDED`、多 `SKIPPED/RETRYING`（B3-T51）。 */
export const AUDIENCE_DELIVERY_LABELS: Record<string, string> = {
  PENDING: '待投递',
  SENT: '已发送',
  RETRY_WAITING: '等待重试',
  FAILED: '失败',
  EXCLUDED: '已排除'
}

export const AUDIENCE_READ_LABELS: Record<string, string> = { UNREAD: '未读', READ: '已读' }

/** `notice_outbox_status` 字典中文；`SKIPPED` 服务端不写，不列为筛选项（B3-T49）。 */
export const OUTBOX_STATUS_LABELS: Record<string, string> = {
  PENDING: '待发送',
  SENDING: '发送中',
  SENT: '已发送',
  FAILED: '失败',
  RETRY_WAITING: '等待重试'
}

/** 实验状态只有 `RUNNING` 有写入路径（B3-T52）。 */
export const EXPERIMENT_STATUS_LABELS: Record<string, string> = { RUNNING: '运行中' }

/** `notice_target_scope` 字典全集；场馆管理员只能写 `VENUE_RECENT_RESERVERS`（B3-T42）。 */
export const TARGET_SCOPE_LABELS: Record<string, string> = {
  ALL_USERS: '全部用户',
  MEMBER_LEVEL: '会员等级',
  VENUE_RECENT_RESERVERS: '场馆近期预约用户',
  CAMPAIGN_PARTICIPANTS: '活动参与用户',
  SPECIFIC_USERS: '指定用户'
}

/** 排除原因真实码（`NoticeAudienceService.java:255-271` + 缺省 `UNKNOWN`），未知码原样显示不折叠（B3-T43）。 */
export const EXCLUDE_REASON_LABELS: Record<string, string> = {
  USER_NOT_FOUND: '账号不存在',
  USER_DELETED: '账号已删除',
  USER_NOT_ACTIVE: '账号不可用',
  UNKNOWN: '未标注原因'
}

function labelOf(table: Record<string, string>, value: string | null | undefined): string {
  if (!value) return '—'
  return table[value] ?? value
}

export const batchStatusLabel = (status: string | null | undefined) => labelOf(BATCH_STATUS_LABELS, status)
export const audienceDeliveryLabel = (status: string | null | undefined) => labelOf(AUDIENCE_DELIVERY_LABELS, status)
export const audienceReadLabel = (status: string | null | undefined) => labelOf(AUDIENCE_READ_LABELS, status)
export const experimentStatusLabel = (status: string | null | undefined) => labelOf(EXPERIMENT_STATUS_LABELS, status)
export const targetScopeLabel = (scope: string | null | undefined) => labelOf(TARGET_SCOPE_LABELS, scope)
export const excludeReasonLabel = (reason: string | null | undefined) => labelOf(EXCLUDE_REASON_LABELS, reason)

/** 邮件 `SENT` 的真实语义是「SMTP 已接受」，服务端已放在 `statusText`，直接取用不自行改写（B3-T49）。 */
export function outboxStatusLabel(row: NoticeDeliveryOutboxResponse): string {
  if (row.channel === 'EMAIL' && row.statusText) return row.statusText
  if (row.channel === 'EMAIL') return labelOf(OUTBOX_STATUS_LABELS, row.status)
  return labelOf(OUTBOX_STATUS_LABELS, row.status)
}

/** 站内发件箱不暴露用户标识，只有服务端返回的收件人种类与编号（B3-T27 同一纪律）。 */
export function outboxObjectText(row: NoticeDeliveryOutboxResponse): string {
  if (row.channel === 'EMAIL') return row.maskedRecipient || '收件地址未记录'
  return row.recipientKind ? `收件人类型 ${row.recipientKind}` : '收件人未记录'
}

export function outboxAttemptText(row: NoticeDeliveryOutboxResponse): string {
  const times = `${row.attemptCount ?? 0} 次`
  return row.failureCode ? `${times} · ${row.failureCode}` : times
}

/** 只有 `FAILED`/`RETRY_WAITING` 可重试；拒绝原文由服务端给出，界面只按状态预判（B3-T50）。 */
export function canRetryOutbox(row: NoticeDeliveryOutboxResponse): boolean {
  if (row.channel === 'EMAIL') return row.status === 'FAILED'
  return row.status === 'FAILED' || row.status === 'RETRY_WAITING'
}

/**
 * 投递记录弹层里能写的事实行：服务端没有逐次尝试流水的读取端点，
 * 因此只列当前状态、已尝试次数、最近错误码与真实时间戳，不虚构时间线（B3-T50）。
 */
export function outboxFactRows(row: NoticeDeliveryOutboxResponse): Array<{ label: string; value: string }> {
  const rows = [
    { label: '当前状态', value: outboxStatusLabel(row) },
    { label: '已尝试', value: outboxAttemptText(row) },
    { label: '进入发件箱', value: row.createTime ? String(row.createTime).replace('T', ' ') : '未记录' },
    { label: '最近更新', value: row.updateTime ? String(row.updateTime).replace('T', ' ') : '未记录' }
  ]
  if (row.nextRetryTime) rows.push({ label: '下次重试', value: String(row.nextRetryTime).replace('T', ' ') })
  if (row.acceptedAt) rows.push({ label: 'SMTP 接受时间', value: String(row.acceptedAt).replace('T', ' ') })
  if (row.downstreamStatus) rows.push({ label: '下游状态', value: row.downstreamStatus })
  return rows
}

/** 服务端阅读率是小数（`BigDecimal` 两位），界面只做百分号呈现，不重算分母（B3-T48）。 */
export function percentText(value: number | null | undefined): string {
  const num = Number(value ?? 0)
  return `${(num * 100).toFixed(1)}%`
}

/** 触达证据一格：标题 + 主值 + 脚注。 */
export interface EvidenceCell {
  label: string
  value: string
  note: string
  /** true 表示该层没有证据，必须留空而不写 0（B3-T47）。 */
  unknown?: boolean
}

/**
 * 站内批次证据链：全部取服务端真实字段。
 * 「客户端送达」服务端从不赋值 `deliveredCount`，只能写未知（B3-T47）。
 */
export function inAppEvidence(batch: NoticeDeliveryBatchResponse): EvidenceCell[] {
  return [
    { label: '公告发布', value: batchStatusLabel(batch.status), note: `批次 ${batch.batchNo}` },
    {
      label: '进入队列',
      value: `${batch.audienceCount ?? 0} 人`,
      note: batch.excludedCount == null ? '排除数未记录' : `排除 ${batch.excludedCount} 人`
    },
    { label: '站内消息生成', value: `${batch.sentCount ?? 0} 人`, note: `终态失败 ${batch.failedCount ?? 0} 人` },
    { label: '客户端送达', value: '未知', note: '未证明实际展示', unknown: true },
    { label: '已读回执', value: `${batch.readCount ?? 0} 人`, note: `${batch.unreadCount ?? 0} 人尚未读` }
  ]
}

/** 邮件渠道证据链：`acceptedCount/retryWaitingCount/failedCount` 真实，下游送达与阅读无证据（B3-T47）。 */
export function emailEvidence(overview: NoticeDeliveryOverviewResponse | null, outboxTotal: number): EvidenceCell[] {
  return [
    { label: '排队', value: `${outboxTotal} 封`, note: '进入发件箱' },
    { label: 'SMTP 接受', value: `${overview?.acceptedCount ?? 0} 封`, note: '不代表送达' },
    { label: '等待重试', value: `${overview?.retryWaitingCount ?? 0} 封`, note: overview?.providerName || '尚未取得渠道' },
    { label: '下游送达', value: '未知', note: '缺少送达回执', unknown: true },
    { label: '阅读', value: '不可用', note: '邮件无阅读数据', unknown: true }
  ]
}

/** 阅读率说明句按服务端真实分母改写，不自算、不写成「已生成消息」（B3-T48）。 */
export function readRateSentence(overview: NoticeDeliveryOverviewResponse): string {
  return `站内消息阅读率为 ${overview.readCount ?? 0} / ${overview.audienceCount ?? 0} = ${percentText(overview.readRate)}，分母为受众快照数（含排队、失败与排除项）。受众排队数不作为已送达人数。`
}

/** 排除原因真实分解，替代预览的「已排除不可用账号」笼统句（B3-T43）。 */
export function excludeReasonRows(preview: AudiencePreviewResponse): Array<{ label: string; count: number }> {
  return Object.entries(preview.excludeReasonCounts || {}).map(([code, count]) => ({
    label: excludeReasonLabel(code),
    count
  }))
}

/** 渠道目录按环境×渠道分组给批次菜单用；服务端已给 `displayName/configVersion`，不再翻译（§3.3）。 */
export function providerIsEmail(provider: NotificationProviderOption | null | undefined): boolean {
  return provider?.channel === 'EMAIL'
}

export function providerLine(provider: NotificationProviderOption | null | undefined): string {
  if (!provider) return '渠道未登记'
  return `${provider.providerEnv} / ${provider.providerName} · ${provider.configVersion}`
}

/** 批次菜单条目：真实批次列表 + 邮件发件箱入口；刚发布的批次由 `batchId` 定位并高亮（B3-T46）。 */
export interface BatchMenuEntry {
  key: string
  batchId: number | null
  title: string
  note: string
  detail: string
  channel: string
}

export function batchMenuEntries(
  batches: NoticeDeliveryBatchResponse[],
  emailProvider: NotificationProviderOption | null
): BatchMenuEntry[] {
  const rows: BatchMenuEntry[] = batches.map((batch) => ({
    key: `batch-${batch.batchId}`,
    batchId: batch.batchId,
    // 服务端批次没有标题列，主行只能用批次号；说明行按参考的两行结构填真实人群与渠道。
    title: batch.batchNo,
    note: targetScopeLabel(batch.targetScope),
    detail: `${batch.providerEnv} / ${batch.providerName} · ${formatHm(batch.createTime)}`,
    channel: 'IN_APP'
  }))
  if (emailProvider) {
    rows.push({
      key: 'email',
      batchId: null,
      title: '业务邮件发件箱',
      note: '平台 · 事务邮件',
      detail: `${emailProvider.providerEnv} / ${emailProvider.providerName}`,
      channel: 'EMAIL'
    })
  }
  return rows
}

/** 受众快照弹层一行：对象只报服务端真实列，不拼用户姓名（B3-T27）。 */
export function audienceRowCells(row: NoticeAudienceResponse): { subject: string; delivery: string; read: string } {
  const read = row.excludeReason
    ? excludeReasonLabel(row.excludeReason)
    : row.readStatus
      ? audienceReadLabel(row.readStatus)
      : '未产生消息'
  return {
    subject: `用户 #${row.userId}`,
    delivery: audienceDeliveryLabel(row.deliveryStatus),
    read: row.readTime ? `${read} · ${formatHm(row.readTime)}` : read
  }
}

/** 实验定义弹层的真实必填集：`variantCode` 预览未提供，按 A/B 生成（B3-T54）。 */
export function experimentPayload(input: {
  name: string
  sampleScope: string
  titleA: string
  contentA: string
  titleB: string
  contentB: string
  allocationA: number
  allocationB: number
}): Record<string, unknown> {
  return {
    name: input.name,
    targetMetric: 'READ_RATE',
    sampleScope: input.sampleScope,
    variants: [
      { variantCode: 'A', title: input.titleA, content: input.contentA, allocationPercent: input.allocationA },
      { variantCode: 'B', title: input.titleB, content: input.contentB, allocationPercent: input.allocationB }
    ]
  }
}

/** 服务端合计校验原文；界面在提交前给同一口径提示（B3-T54）。 */
export function experimentAllocationError(allocationA: number, allocationB: number): string {
  return allocationA + allocationB === 100 ? '' : '两个版本的比例之和需要为 100%。'
}

/** 阅读表现一格：邮件渠道真实返回空分桶，按缺口呈现而不是 0%（B3-T52）。 */
export function experimentResultRows(
  result: NoticeExperimentResultResponse | null,
  email: boolean
): Array<{ code: string; title: string; rate: string; readCount: number; audienceCount: number; failedCount: number }> {
  if (!result || email) return []
  const variants = result.variants || []
  // 刚创建的只有定义：服务端返回的两个变体受众数为 0，此时不是「阅读率 0%」而是「尚无投递结果」。
  if (!variants.some((variant) => (variant.audienceCount ?? 0) > 0)) return []
  return variants.map((variant) => ({
    code: variant.variantCode,
    title: variant.title,
    rate: percentText(variant.readRate),
    readCount: variant.readCount ?? 0,
    audienceCount: variant.audienceCount ?? 0,
    failedCount: variant.failedCount ?? 0
  }))
}

/** 实验列表下拉的真实字段（`sampleScope` 无字典，按 `notice_target_scope` 同词映射）。 */
export function experimentOption(experiment: NoticeExperimentResponse): { value: number; label: string } {
  return { value: experiment.id, label: experiment.name || `实验 #${experiment.id}` }
}
