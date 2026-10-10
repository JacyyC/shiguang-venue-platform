/**
 * 会员与权益管理面共享展示层（SHIGUANG-BATCH-04-REFACTOR-20260914 S04）。
 *
 * 只收本面共用的枚举标签、格式化与派生，全部可测纯函数。标签来源优先级：
 * 服务端下发/字典 > 面内白名单 > 原值回落（**未知值原样显示，不隐藏、不折叠、不猜**）。
 * 真实枚举集合与文件行号见 contracts.md §1 与 verification/s02/contracts-members.md §5。
 */

import { formatMonthDaySlash, formatStampDateTime } from '@/views/admin/shiguang/adminShared'
import type { MemberAccountSummary, MemberLevelResponse, MemberOverviewResponse } from '@/api/types'

/* ------------------------------------------------------------------ 等级 */

/**
 * 等级状态。服务端归一化只认 ACTIVE/INACTIVE，并把 `DISABLED` 作为代码内别名映射为 INACTIVE
 * （`be/member/service/MemberService.java:824-836`）。字典 `member_level_status` 只有前两条，
 * `DISABLED` 不在字典里，故这里同时接受它与小写写入。
 */
export const LEVEL_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  INACTIVE: '停用',
  DISABLED: '停用'
}

export function levelStatusLabel(status: string | null | undefined): string {
  if (!status) return '启用'
  return LEVEL_STATUS_LABELS[status.toUpperCase()] ?? status
}

/** 停用态的写入值：沿用既有前端发送的 `DISABLED` 别名（服务端将其归一为 INACTIVE）。 */
export const LEVEL_STATUS_DISABLED = 'DISABLED'
export const LEVEL_STATUS_ACTIVE = 'ACTIVE'

/**
 * 会员折扣展示（预览 `ref/b/members.js:8` 的 `rate===1?'标准价':rate*10+' 折'`）。
 * 真实 `discountRate` 是 BigDecimal，1 表示标准价；0.95→「9.5 折」、0.9→「9 折」。
 */
export function discountRateLabel(rate: number | null | undefined): string {
  if (rate == null || Number.isNaN(rate)) return '标准价'
  if (rate >= 1) return '标准价'
  const tenths = Math.round(rate * 1000) / 100
  return `${trimNumber(tenths)} 折`
}

function trimNumber(value: number): string {
  return String(Number(value.toFixed(2)))
}

/* -------------------------------------------------------------- 权益目录 */

/**
 * 目录项状态（B4-M12）。真实是三态 `DRAFT/ACTIVE/INACTIVE`
 * （`mig/V17__member_reward_redemption.sql:65-66`，建项即 DRAFT）。
 * 预览只有「启用/停用」两态，本面必须保留草稿，否则新建目录会被误读为已下架。
 */
export const CATALOG_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '已启用',
  INACTIVE: '已停用'
}

export function catalogStatusLabel(status: string | null | undefined): string {
  if (!status) return ''
  return CATALOG_STATUS_LABELS[status.toUpperCase()] ?? status
}

/** 目录状态的启停目标：草稿与停用都可启用，只有启用可停用（服务端两条 409 同口径）。 */
export function catalogToggleTarget(status: string | null | undefined): 'ACTIVE' | 'INACTIVE' {
  return (status ?? '').toUpperCase() === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
}

/** 目录项是否有已发布版本。无版本时预览不展示兑换条件，本面显式说明「当前无生效版本」（§9.2 B4-M5）。 */
export function hasPublishedVersion(versionId: number | null | undefined): boolean {
  return versionId != null && versionId > 0
}

/* ------------------------------------------------------------ 兑换与事件 */

/**
 * 兑换单状态。真实五态由 DB CHECK 锁定
 * （`mig/V17__member_reward_redemption.sql:147-148`）。
 * 预览把 `FAILED` 写成「发券失败」，那是单一合成归因（B4-M16）；真实 `failureCode` 至少还有
 * `EXECUTION_ERROR`/`RETRY_EXECUTION_ERROR` 两类执行期异常，故这里只写「失败」，
 * 具体原因由服务端 `failureMessage` 原样展示。
 */
export const REDEMPTION_STATUS_LABELS: Record<string, string> = {
  PENDING: '处理中',
  SUCCEEDED: '兑换成功',
  REJECTED: '已拒绝',
  FAILED: '失败',
  COMPENSATED: '已补偿'
}

export function redemptionStatusLabel(status: string | null | undefined): string {
  if (!status) return ''
  return REDEMPTION_STATUS_LABELS[status.toUpperCase()] ?? status
}

/** 异常兑换默认范围（服务端缺省三态）。SUCCEEDED 是补偿的唯一合法前置，需显式筛选才可达（B4-M1）。 */
export const EXCEPTION_DEFAULT_STATUSES = ['PENDING', 'FAILED', 'REJECTED'] as const
export const EXCEPTION_STATUS_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '', label: '异常默认范围（处理中 / 失败 / 已拒绝）' },
  { value: 'PENDING', label: '处理中' },
  { value: 'FAILED', label: '失败' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'SUCCEEDED', label: '兑换成功（可申请补偿）' },
  { value: 'COMPENSATED', label: '已补偿' }
]

/**
 * 兑换可用动作，按服务端真实前置（`MemberRewardRetryService.java:29-31` 只认 FAILED；
 * `MemberRewardExecutionService.java:112-117` 补偿只认 SUCCEEDED 且券非空）。
 * 处理中没有任何写动作，只需要重新读取（B4-M2）。
 */
export const RETRYABLE_STATUSES = ['FAILED'] as const
export const COMPENSABLE_STATUSES = ['SUCCEEDED'] as const

export function canRetry(status: string | null | undefined): boolean {
  return RETRYABLE_STATUSES.includes((status ?? '').toUpperCase() as 'FAILED')
}

export function canCompensate(status: string | null | undefined, userCouponId?: number | null): boolean {
  if (!COMPENSABLE_STATUSES.includes((status ?? '').toUpperCase() as 'SUCCEEDED')) return false
  // 补偿会作废可补偿的券并退回积分；没有券的兑换服务端直接 409，故不发请求。
  return userCouponId != null && userCouponId > 0
}

/**
 * 权益事件类型 → 中文。真实 `event_type` **无 DB CHECK、无 Java enum**，是散落的代码字面量
 * （B4-M11），因此这里是「代码派生白名单 + 原值回落」，不是封闭集合：未知值原样显示。
 * 取值与出处见 verification/s02/contracts-members.md §5.2。
 */
export const MEMBER_REWARD_EVENT_TYPE_LABELS: Record<string, string> = {
  REDEMPTION_SUCCEEDED: '积分兑换成功',
  REDEMPTION_REJECTED: '积分兑换被拒',
  REDEMPTION_FAILED: '兑换执行失败',
  REDEMPTION_COMPENSATED: '兑换已补偿',
  REDEMPTION_RETRY_STARTED: '授权重试开始',
  REDEMPTION_RETRY_RESULT: '授权重试结果',
  CATALOG_VERSION_PUBLISHED: '兑换版本发布',
  CATALOG_ITEM_STATUS_CHANGED: '兑换目录状态变更'
}

export function memberRewardEventTypeLabel(eventType: string | null | undefined): string {
  if (!eventType) return ''
  return MEMBER_REWARD_EVENT_TYPE_LABELS[eventType.toUpperCase()] ?? eventType
}

/**
 * 事件来源。真实只有 USER/ADMIN/SYSTEM（`MemberRewardExecutionService.java:104,133,146` 等）。
 * `USER` 在产品既有 `SOURCE_TYPE_LABELS` 中缺失并回落「其他来源」（B4-M10），本面按真实取值补全。
 */
export const MEMBER_REWARD_SOURCE_LABELS: Record<string, string> = {
  USER: '会员自助',
  ADMIN: '管理员操作',
  SYSTEM: '系统触发'
}

export function memberRewardSourceLabel(source: string | null | undefined): string {
  if (!source) return ''
  return MEMBER_REWARD_SOURCE_LABELS[source.toUpperCase()] ?? source
}

/** 事件「状态变化」列：`fromStatus → toStatus`；同值时参考只显示一次（`ref/b/members.js:20` 的对照语义）。 */
export function statusTransitionLabel(from: string | null | undefined, to: string | null | undefined): string {
  const fromLabel = from ? redemptionStatusLabel(from) : ''
  const toLabel = to ? redemptionStatusLabel(to) : ''
  if (!fromLabel && !toLabel) return ''
  if (!fromLabel) return toLabel
  if (!toLabel || fromLabel === toLabel) return fromLabel
  return `${fromLabel} → ${toLabel}`
}

/* ---------------------------------------------------------------- 格式化 */

/** 参考的活跃/事件时间：`09-12 09:40`（`ref/b/members.js:1`）。真实是 ISO LocalDateTime。 */
export function shortDateTime(value: string | null | undefined): string {
  if (!value) return ''
  const stamp = formatStampDateTime(value)
  if (!stamp) return ''
  // formatStampDateTime → "2026-09-12 09:40"；参考只保留月-日与时分。
  return stamp.slice(5)
}

/** 参考的日期：`2026-08-02`（`ref/b/members.js:6` 的沉睡样本）。 */
export function isoDate(value: string | null | undefined): string {
  if (!value) return ''
  const stamp = formatStampDateTime(value)
  return stamp ? stamp.slice(0, 10) : ''
}

/** 参考的两行时间格首行 `09/13`（后台日志与事件表）。 */
export { formatMonthDaySlash }

/** 千分位整数（成长值/积分）。真实是整数，不是预览的字符串型金额。 */
export function groupedInt(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '0'
  return Math.trunc(value).toLocaleString('zh-CN')
}

/** 账户展示名：优先昵称，回落用户名，最后回落编号（真实账户没有 `M-` 形态的业务编号）。 */
export function accountDisplayName(account: Pick<MemberAccountSummary, 'nickname' | 'username' | 'userId'>): string {
  return account.nickname?.trim() || account.username?.trim() || `会员 ${account.userId}`
}

/** 账户副标题：显示真实主键，不虚构编号。 */
export function accountSubtitle(account: Pick<MemberAccountSummary, 'userId' | 'username'>): string {
  return account.username?.trim() ? `${account.userId} · ${account.username}` : String(account.userId)
}

/* ------------------------------------------------------------------ 概览 */

/**
 * 概览计数行（预览 `ref/b/members.js:6` 的 `.b-overview-note`：会员总数 / 活跃会员 / 高价值 / 沉睡）。
 *
 * 真实口径两处必须如实说明（B4-M8）：
 * 1. `activeMembers` 统计的是 `member_account.status='ACTIVE'`，而全仓没有把账户置为 INACTIVE 的
 *    写入路径，因此它**恒等于** `totalMembers`；产品文案按字段本义写「启用会员账户」，不声称活跃度。
 * 2. `coreUsers`/`dormantUsers` 是 `LIMIT 10` 的展示样本，不是全量人数。
 */
export interface OverviewCountRow {
  label: string
  value: number
}

export function overviewCountRows(overview: MemberOverviewResponse | null): OverviewCountRow[] {
  if (!overview) return []
  return [
    { label: '会员总数', value: overview.totalMembers },
    { label: '启用会员账户', value: overview.activeMembers }
  ]
}

/** 等级分布计数行：以 `levelDistribution` 的真实键为序，值为该等级账户数。 */
export function levelDistributionRows(overview: MemberOverviewResponse | null): OverviewCountRow[] {
  if (!overview?.levelDistribution) return []
  return Object.entries(overview.levelDistribution).map(([label, value]) => ({ label, value }))
}

/**
 * 「当前无生效版本」判定（§9.2 B4-M5）：目录行没有 `versionId` 时左侧比较栏不能填任何值。
 * 服务端此时会下发 `unavailableReason='当前没有可兑换版本'`，本面据此显式降级。
 */
export function catalogVersionCaption(hasVersion: boolean, versionNo: number | null | undefined): string {
  if (!hasVersion) return '当前无生效版本'
  return `当前版本 V${versionNo ?? '—'}`
}

/** 目录行副标题：有版本时给出真实兑换条件，无版本时说明原因（不虚构条件）。 */
export function catalogConditionText(item: {
  versionId?: number
  pointsCost?: number
  minimumLevelName?: string
  validityDays?: number
}): string {
  if (!hasPublishedVersion(item.versionId)) return '尚无已发布的兑换条件'
  const parts = [`${groupedInt(item.pointsCost)} 积分`]
  if (item.minimumLevelName) parts.push(`${item.minimumLevelName}及以上`)
  if (item.validityDays != null) parts.push(`券有效 ${item.validityDays} 天`)
  return parts.join(' · ')
}

/** 等级卡片副标题「成长值门槛」。 */
export function growthThresholdText(level: Pick<MemberLevelResponse, 'growthThreshold'>): string {
  return groupedInt(level.growthThreshold)
}
