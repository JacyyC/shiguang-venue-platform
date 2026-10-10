/**
 * 履约面共享逻辑（SHIGUANG-BATCH-02-REFACTOR-20260913 S05）。
 * 预览 ref/f/fulfillment.js 的推导改为真实服务端事实驱动：
 * 行=预约（主表）×订单×履约记录×待审改期×退款单 的真实关联；固定示例行/合成单号/写死时段不迁入。
 */
import type { Order, RefundRecord, Reservation, ReservationLifecycleResponse, ReservationRescheduleRequest } from '@/api/types'

export type FulfillmentTab = 'arrival' | 'review' | 'orders' | 'day'

/** 旧路由 → 初始页签（contracts.md §2.3）。 */
export function initialTabFromRoute(name: string | symbol | null | undefined): FulfillmentTab {
  if (name === 'VenueAdminReservations') return 'review'
  if (name === 'VenueAdminCheckins') return 'arrival'
  if (name === 'VenueAdminOrders') return 'orders'
  return 'day'
}

/** 履约面行视图模型：预约为主体，其余按真实关联拼装。 */
export interface FulfillmentRow {
  reservation: Reservation
  order: Order | null
  lifecycle: ReservationLifecycleResponse | null
  reschedule: ReservationRescheduleRequest | null
  refund: RefundRecord | null
}

export function buildRows(
  reservations: Reservation[],
  orders: Order[],
  lifecycles: ReservationLifecycleResponse[],
  reschedules: ReservationRescheduleRequest[],
  refunds: RefundRecord[]
): FulfillmentRow[] {
  // 关联口径：admin 预约列表不回填 orderId，订单行自带 reservationId（服务端事实），
  // 故以 order.reservationId 为主键关联；reservation.orderId 仅作用户侧列表的回退。
  const ordersByReservation = new Map<number, Order>()
  for (const order of orders) {
    if (order.reservationId != null) ordersByReservation.set(order.reservationId, order)
  }
  const ordersById = new Map<number, Order>()
  for (const order of orders) ordersById.set(order.id, order)
  const lifecycleByReservation = new Map<number, ReservationLifecycleResponse>()
  for (const lifecycle of lifecycles) lifecycleByReservation.set(lifecycle.reservationId, lifecycle)
  const pendingRescheduleByReservation = new Map<number, ReservationRescheduleRequest>()
  for (const request of reschedules) {
    if (request.status === 'PENDING_REVIEW' && request.reservationId != null) {
      pendingRescheduleByReservation.set(request.reservationId, request)
    }
  }
  const refundRank = (status: string): number => (status === 'PENDING_REVIEW' ? 0 : status === 'REFUNDING' ? 1 : 2)
  const refundByOrder = new Map<number, RefundRecord>()
  for (const refund of refunds) {
    const existing = refundByOrder.get(refund.orderId)
    if (!existing || refundRank(refund.status) < refundRank(existing.status)) {
      refundByOrder.set(refund.orderId, refund)
    }
  }
  return reservations.map((reservation) => {
    const order = ordersByReservation.get(reservation.id) ?? (reservation.orderId != null ? ordersById.get(reservation.orderId) ?? null : null)
    return {
      reservation,
      order,
      lifecycle: lifecycleByReservation.get(reservation.id) ?? null,
      reschedule: pendingRescheduleByReservation.get(reservation.id) ?? null,
      refund: order ? refundByOrder.get(order.id) ?? null : null
    }
  })
}

/** 预览 orders() 页签口径：有订单的预约。 */
export function ordersOf(rows: FulfillmentRow[]): FulfillmentRow[] {
  return rows.filter((row) => row.order != null)
}

/** 预览 arrival() 记录范围：当前=非 COMPLETED 的 PAID/REFUNDING 等；历史=COMPLETED。 */
export function arrivalRows(rows: FulfillmentRow[], history: boolean): FulfillmentRow[] {
  return rows.filter((row) => {
    const status = row.reservation.status
    return history ? status === 'COMPLETED' : ['PAID', 'REFUNDING'].includes(status)
  })
}

/** 预览 arrival 排序：普通 < 爽约标记 < 退款中，再按开始时间。 */
export function sortArrival(rows: FulfillmentRow[]): FulfillmentRow[] {
  return [...rows].sort((a, b) => {
    const rank = (row: FulfillmentRow): number =>
      row.reservation.status === 'REFUNDING' ? 2 : row.lifecycle?.noShowStatus === 'CANDIDATE' ? 1 : 0
    return rank(a) - rank(b) || String(a.reservation.startTime).localeCompare(String(b.reservation.startTime))
  })
}

/** 预览搜索：预约号、用户（联系人）或凭证尾号。 */
export function matchArrivalQuery(row: FulfillmentRow, query: string): boolean {
  if (!query) return true
  const tail = row.lifecycle?.checkinCodeLast4 ? String(row.lifecycle.checkinCodeLast4) : ''
  return `#${row.reservation.id} ${row.reservation.contactName ?? ''} ${tail}`.includes(query)
}

/** 预览 reviews() 列表口径：有待审改期或状态在处理/已处理集合。 */
export function reviewRows(rows: FulfillmentRow[]): FulfillmentRow[] {
  const statuses = ['PENDING_REVIEW', 'PENDING_PAYMENT', 'PAID', 'CANCELED', 'REJECTED']
  return rows.filter((row) => row.reschedule != null || statuses.includes(row.reservation.status))
}

/** 人工放行资格（预览 canManual）：PAID+凭证可用+未核销+未确认爽约。 */
export function canManual(row: FulfillmentRow): boolean {
  const lifecycle = row.lifecycle
  if (!lifecycle) return false
  return (
    row.reservation.status === 'PAID' &&
    lifecycle.credentialStatus === 'ISSUED' &&
    lifecycle.checkinStatus !== 'CHECKED_IN' &&
    lifecycle.noShowStatus !== 'CONFIRMED'
  )
}

function parse(value: string | null | undefined): Date | null {
  if (!value) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

/** 普通核销资格：人工放行资格 + 核销窗口内 + 有可验凭证。 */
export function canVerify(row: FulfillmentRow, now: Date = new Date()): boolean {
  if (!canManual(row)) return false
  const start = parse(row.lifecycle?.checkinWindowStart ?? null)
  const end = parse(row.lifecycle?.checkinWindowEnd ?? null)
  return start != null && end != null && start.getTime() <= now.getTime() && now.getTime() <= end.getTime()
}

/** 核验窗口文案（预览"今日 18:45—20:15"）：跨日带日期，当日带"今日/明日"前缀。 */
export function verifyWindowLabel(row: FulfillmentRow, now: Date = new Date()): string {
  const start = parse(row.lifecycle?.checkinWindowStart ?? null)
  const end = parse(row.lifecycle?.checkinWindowEnd ?? null)
  if (!start || !end) return '本条当前不可普通核销'
  const pad = (value: number): string => String(value).padStart(2, '0')
  const hm = (value: Date): string => `${pad(value.getHours())}:${pad(value.getMinutes())}`
  const day = (value: Date): string => `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
  const monthDayText = (value: Date): string => `${pad(value.getMonth() + 1)}月${pad(value.getDate())}日`
  const range = `${hm(start)}—${hm(end)}`
  const today = day(now)
  const startDay = day(start)
  if (startDay === today) return `今日 ${range}`
  const tomorrow = new Date(now)
  tomorrow.setDate(now.getDate() + 1)
  if (startDay === day(tomorrow)) return `明日 ${range}`
  return `${monthDayText(start)} ${range}`
}

/** 订单状态展示口径（预览 orderLabel）：订单缺失时 COMPLETED 预约按已关闭。 */
export function orderDisplayStatus(row: FulfillmentRow): string {
  return row.order?.status ?? (row.reservation.status === 'COMPLETED' ? 'CLOSED' : row.reservation.status)
}

/** 支付方式（预览 orders facts）：储值余额 / DEV 外部支付 / 尚未支付。 */
export function paySourceLabel(order: Order | null): string {
  if (!order) return '尚未支付'
  if (order.paymentToolType === 'STORED_VALUE') return '储值余额'
  return order.paidAmount ? 'DEV 外部支付' : '尚未支付'
}

export function discountOf(order: Order | null): number {
  if (!order) return 0
  return (
    Number(order.memberDiscountAmount ?? 0) +
    Number(order.couponDiscountAmount ?? 0) +
    Number(order.activityDiscountAmount ?? 0)
  )
}

/** 退款审核说明（预览 refund 各状态文案，措辞逐字保留）。 */
export function refundNote(refund: RefundRecord | null): string {
  if (!refund) return '本订单没有待审核退款。已完成预约不能发起新的退款。'
  switch (refund.status) {
    case 'PENDING_REVIEW':
      return '用户申请已提交，等待场馆审核。'
    case 'REFUNDING':
      return '审核已通过，等待退款结果。本次审核不代表资金已到账。'
    case 'REJECTED':
      return '用户主动退款被拒绝，预约与订单已恢复为已支付。'
    case 'SUCCESS':
      return '普通退款确认已记录：退款成功，预约与订单已退款，核销凭证失效。'
    default:
      return '退款处理中，到账与账务结果需继续核对。'
  }
}

/** 凭证列展示（预览 arrival facts）：尾号优先；退款中冻结；已完成历史。 */
export function credentialText(row: FulfillmentRow): string {
  const lifecycle = row.lifecycle
  if (lifecycle?.checkinCodeLast4) return `仅显示尾号 ${lifecycle.checkinCodeLast4}`
  if (row.reservation.status === 'REFUNDING') return '退款中，凭证冻结'
  if (row.reservation.status === 'COMPLETED') return '历史履约记录'
  return '凭证待生成'
}

/** 预约号展示：产品显示预约编号（合成单号不迁入，B2 契约）。 */
export function reservationNo(row: FulfillmentRow): string {
  return `#${row.reservation.id}`
}

/** 到场徽标（预览 arrival 状态行）：未核销才并列爽约标记（noShowBadge 同口径）。 */
export function arrivalCheckLabel(row: FulfillmentRow): string {
  const lifecycle = row.lifecycle
  if (!lifecycle) {
    if (row.reservation.status === 'PENDING_REVIEW') return '待生成'
    return '未核销'
  }
  if (lifecycle.checkinStatus === 'CHECKED_IN') {
    return lifecycle.attendanceStatus === 'MANUAL_PRESENT' ? '人工到场' : '已核销'
  }
  if (row.reservation.status === 'REFUNDING') return '凭证冻结'
  if (lifecycle.noShowStatus === 'CONFIRMED') return '爽约已确认'
  if (lifecycle.noShowStatus === 'CANDIDATE') return '爽约待确认'
  if (lifecycle.checkinStatus === 'CANCELED') return '已取消'
  return '未核销'
}

/** 到场详情主提示（预览 ineligible 分支措辞）。 */
export function arrivalBlockedNote(row: FulfillmentRow): string | null {
  const status = row.reservation.status
  if (status === 'REFUNDING') return '退款处理中，普通核销与人工放行均不可用。'
  if (status === 'COMPLETED') return '已完成记录只读，不能重新签发凭证或再次放行。'
  if (canManual(row)) return null
  if (row.lifecycle?.noShowStatus === 'CONFIRMED') return null
  if (status === 'PAID' && row.lifecycle?.credentialStatus !== 'ISSUED') {
    return '本条当前没有已确认可用的核销凭证，需先核对凭证状态，不能直接放行。'
  }
  if (row.lifecycle?.checkinStatus === 'CHECKED_IN') return '本次到场已记录，不能重复核销。'
  return '本条预约当前不能核验，请核对凭证与窗口。'
}

/** 服务端错误消息读取。 */
export function readableError(error: unknown, fallback: string): string {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (responseMessage) return responseMessage
  return error instanceof Error && error.message ? error.message : fallback
}

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

/** "09月13日 19:00—20:00"（预览 f-pick/详情时间行）。 */
export function whenRange(startTime: string | null | undefined, endTime: string | null | undefined): string {
  const start = parse(startTime)
  const end = parse(endTime)
  if (!start || !end) return ''
  return `${pad2(start.getMonth() + 1)}月${pad2(start.getDate())}日 ${pad2(start.getHours())}:${pad2(start.getMinutes())}—${pad2(end.getHours())}:${pad2(end.getMinutes())}`
}

/** "09月13日"（列表行日期块）。 */
export function monthDay(startTime: string | null | undefined): string {
  const start = parse(startTime)
  if (!start) return ''
  return `${pad2(start.getMonth() + 1)}月${pad2(start.getDate())}日`
}

/** "19:00—20:00"。 */
export function timeSpan(startTime: string | null | undefined, endTime: string | null | undefined): string {
  const start = parse(startTime)
  const end = parse(endTime)
  if (!start || !end) return ''
  return `${pad2(start.getHours())}:${pad2(start.getMinutes())}—${pad2(end.getHours())}:${pad2(end.getMinutes())}`
}
