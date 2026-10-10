import type { BizOrderLike, ReservationLike, ReservationLifecycleLike } from './transactionsTypes'

/**
 * 交易面共享展示层（SHIGUANG-BATCH-01-REFACTOR-20260913 S04）。
 * 文案与格式逐字对齐冻结参考 reference/preview/d（data.js 的 status 表、money、日期格式）；
 * 真实状态集中预览未展示的枚举按同风格补全（contracts.md §1.4），不改变已确认措辞。
 */

export const RESERVATION_STATUS_LABELS: Record<string, string> = {
  PENDING_REVIEW: '待审核',
  APPROVED: '已通过',
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  COMPLETED: '已完成',
  REJECTED: '已拒绝',
  CANCELED: '已取消',
  EXPIRED: '已过期',
  REFUNDING: '退款中',
  REFUNDED: '已退款'
}

export const ORDER_STATUS_LABELS: Record<string, string> = {
  PENDING_PAYMENT: '待支付',
  PAYING: '支付中',
  PAID: '已支付',
  CLOSED: '已关闭',
  TIMEOUT: '支付超时',
  PAY_FAILED: '支付失败',
  REFUNDING: '退款中',
  REFUNDED: '已退款',
  PARTIAL_REFUNDED: '部分退款'
}

export const PAYMENT_STATUS_LABELS: Record<string, string> = {
  CREATED: '已创建',
  PENDING: '处理中',
  SUCCEEDED: '已支付',
  FAILED: '未完成',
  CANCELED: '已取消',
  TIMEOUT: '已超时',
  CLOSED: '已关闭'
}

export function reservationStatusLabel(status: string): string {
  return RESERVATION_STATUS_LABELS[status] ?? status
}

export function orderStatusLabel(status: string): string {
  return ORDER_STATUS_LABELS[status] ?? status
}

export function paymentStatusLabel(status: string): string {
  return PAYMENT_STATUS_LABELS[status] ?? status
}

export const ENDED_RESERVATION_STATUSES = ['COMPLETED', 'CANCELED', 'EXPIRED', 'REFUNDED']
/** 预览"仍需关注/已结束"的记录范围口径（transactions.js list()）。 */
export function isEndedStatus(status: string): boolean {
  return ENDED_RESERVATION_STATUSES.includes(status)
}

export function statusTone(status: string): 'attention' | '' {
  return ['PENDING_PAYMENT', 'REFUNDING'].includes(status) ? 'attention' : ''
}

export function money(value: number | string | null | undefined): string {
  return Number(value ?? 0).toFixed(2)
}

const WEEKDAY_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function parseLocal(value: string | null | undefined): Date | null {
  if (!value) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** 参考格式："9 月 13 日" */
export function dateLabel(value: string | null | undefined): string {
  const parsed = parseLocal(value)
  if (!parsed) return ''
  return `${parsed.getMonth() + 1} 月 ${parsed.getDate()} 日`
}

/** 参考格式："9 月 13 日 · 周日" */
export function whenLabel(value: string | null | undefined): string {
  const parsed = parseLocal(value)
  if (!parsed) return ''
  return `${dateLabel(value)} · ${WEEKDAY_LABELS[parsed.getDay()]}`
}

/** 参考格式：列表日期块 day="13"、month="9 月" */
export function dateParts(value: string | null | undefined): { day: string; month: string } {
  const parsed = parseLocal(value)
  if (!parsed) return { day: '--', month: '' }
  return { day: String(parsed.getDate()), month: `${parsed.getMonth() + 1} 月` }
}

/** 参考格式："19:00—20:00"（破折号为 U+2014） */
export function timeRange(start: string | null | undefined, end: string | null | undefined): string {
  const from = parseLocal(start)
  const to = parseLocal(end)
  if (!from || !to) return ''
  return `${pad(from.getHours())}:${pad(from.getMinutes())}—${pad(to.getHours())}:${pad(to.getMinutes())}`
}

/** 参考格式："9 月 14 日 14:00—16:00" */
export function dateTimeRange(start: string | null | undefined, end: string | null | undefined): string {
  const range = timeRange(start, end)
  const day = dateLabel(start)
  return day && range ? `${day} ${range}` : ''
}

/** 手机号展示掩码（预览为 138****0018；仅展示层，不改存储值）。 */
export function maskPhone(phone: string | null | undefined): string {
  const value = (phone ?? '').trim()
  if (/^\d{11}$/.test(value)) return `${value.slice(0, 3)}****${value.slice(7)}`
  if (value.length > 4) return `${value.slice(0, Math.max(1, value.length - 4))}****${value.slice(-4)}`
  return value || '未填写'
}

/** 邮箱展示掩码（预览为 l***@example.test；仅展示层）。 */
export function maskEmail(email: string | null | undefined): string {
  const value = (email ?? '').trim()
  const at = value.indexOf('@')
  if (at <= 0) return value
  return `${value.slice(0, 1)}***${value.slice(at)}`
}

export type SectionKey = 'reservations' | 'orders' | 'credentials'

export const SECTION_LABELS: Array<[SectionKey, string]> = [
  ['reservations', '预约记录'],
  ['orders', '订单与退款'],
  ['credentials', '到场凭证']
]

/** 合并后的列表行视图模型：预约为主体，订单/凭证/改期按真实关联拼接。 */
export interface TransactionRow {
  reservation: ReservationLike
  order: BizOrderLike | null
  lifecycle: ReservationLifecycleLike | null
  reschedulePending: boolean
}

export function buildTransactionRow(
  reservation: ReservationLike,
  ordersById: Map<number, BizOrderLike>,
  lifecyclesByReservation: Map<number, ReservationLifecycleLike>,
  pendingRescheduleIds: Set<number>
): TransactionRow {
  return {
    reservation,
    order: reservation.orderId != null ? ordersById.get(reservation.orderId) ?? null : null,
    lifecycle: lifecyclesByReservation.get(reservation.id) ?? null,
    reschedulePending: pendingRescheduleIds.has(reservation.id)
  }
}

/** 订单展示口径：预览 row() 在订单页签显示订单描述与金额，未生成订单的预约不出现在订单页签。 */
export function orderDisplayText(order: BizOrderLike | null): string {
  return order ? `订单 ${order.orderNo}` : ''
}

/** 预约描述：预览用合成单号（G-T3 不迁入），产品显示预约编号。 */
export function reservationDisplayText(reservation: ReservationLike): string {
  return `预约 #${reservation.id}`
}

export interface CredentialView {
  invalid: boolean
  pill: string
  suffix: string
  hintText: string
}

/**
 * 凭证状态归纳（预览 credential()）：未核销 / 已核销 / 凭证已过期 / 当前不可用。
 * 过期与已核销不能靠更新恢复；非 PAID 预约不满足使用条件。
 */
export function credentialView(
  reservation: ReservationLike,
  lifecycle: ReservationLifecycleLike | null,
  now: Date = new Date()
): CredentialView {
  const suffix = lifecycle?.checkinCodeLast4 ? `尾号 ${lifecycle.checkinCodeLast4}` : '待签发'
  const checkedIn = lifecycle?.checkinStatus === 'CHECKED_IN'
  const windowEnd = parseLocal(lifecycle?.checkinWindowEnd ?? null)
  const windowExpired = windowEnd != null && windowEnd.getTime() < now.getTime()
  if (reservation.status !== 'PAID') {
    return { invalid: true, pill: '当前不可用', suffix, hintText: '凭证已结束或当前预约不满足使用条件，不能通过更新延长有效期。' }
  }
  if (checkedIn) {
    return { invalid: true, pill: '已核销', suffix, hintText: '凭证已结束或当前预约不满足使用条件，不能通过更新延长有效期。' }
  }
  if (windowExpired) {
    return { invalid: true, pill: '凭证已过期', suffix, hintText: '凭证已结束或当前预约不满足使用条件，不能通过更新延长有效期。' }
  }
  return { invalid: false, pill: '未核销', suffix, hintText: '更新会使旧码失效，请在到场时出示最新凭证。完整码仅保留在本次页面中，刷新后需重新领取。' }
}

/** "下一次到场"：PAID 且凭证窗口未结束的最近一条（预览口径为 PAID 且未过期）。 */
export function nextUpcoming(rows: TransactionRow[], now: Date = new Date()): TransactionRow | null {
  const candidates = rows
    .filter(row => row.reservation.status === 'PAID')
    .filter(row => {
      const end = parseLocal(row.reservation.endTime)
      return end == null || end.getTime() >= now.getTime()
    })
    .sort((a, b) => {
      const av = parseLocal(a.reservation.startTime)?.getTime() ?? 0
      const bv = parseLocal(b.reservation.startTime)?.getTime() ?? 0
      return av - bv
    })
  return candidates[0] ?? null
}
