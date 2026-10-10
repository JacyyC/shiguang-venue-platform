import { describe, expect, it } from 'vitest'
import type { Order, RefundRecord, Reservation, ReservationLifecycleResponse, ReservationRescheduleRequest } from '@/api/types'
import {
  arrivalBlockedNote,
  arrivalCheckLabel,
  arrivalRows,
  buildRows,
  canManual,
  canVerify,
  credentialText,
  discountOf,
  initialTabFromRoute,
  matchArrivalQuery,
  monthDay,
  orderDisplayStatus,
  paySourceLabel,
  refundNote,
  reservationNo,
  reviewRows,
  sortArrival,
  timeSpan,
  verifyWindowLabel,
  whenRange
} from './fulfillmentShared'

const reservation = (overrides: Partial<Reservation> = {}): Reservation =>
  ({
    id: 1073,
    orderId: 2073,
    userId: 18,
    venueId: 101,
    spaceId: 73,
    status: 'PAID',
    startTime: '2026-09-13T19:00:00',
    endTime: '2026-09-13T20:00:00',
    contactName: '示例用户018',
    contactPhone: '13800000018',
    venueName: '北岸生活场馆',
    payableAmount: 80,
    paidAmount: 80,
    ...overrides
  }) as Reservation

const order = (overrides: Partial<Order> = {}): Order =>
  ({
    id: 2073,
    orderNo: 'O-20260913-0001',
    reservationId: 1073,
    userId: 18,
    venueId: 101,
    status: 'PAID',
    totalAmount: 80,
    originalAmount: 80,
    memberDiscountAmount: 0,
    couponDiscountAmount: 0,
    activityDiscountAmount: 0,
    paidAmount: 80,
    ...overrides
  }) as Order

const lifecycle = (overrides: Partial<ReservationLifecycleResponse> = {}): ReservationLifecycleResponse =>
  ({
    reservationId: 1073,
    venueId: 101,
    spaceId: 73,
    credentialStatus: 'ISSUED',
    checkinCodeLast4: '4821',
    checkinWindowStart: '2026-09-13T18:30:00',
    checkinWindowEnd: '2026-09-13T20:30:00',
    attendanceStatus: 'PENDING',
    checkinStatus: 'NOT_CHECKED_IN',
    noShowStatus: 'NOT_EVALUATED',
    ...overrides
  }) as ReservationLifecycleResponse

const reschedule = (overrides: Partial<ReservationRescheduleRequest> = {}): ReservationRescheduleRequest =>
  ({
    id: 5,
    reservationId: 1072,
    userId: 18,
    venueId: 101,
    spaceId: 72,
    fromStartTime: '2026-09-13T14:00:00',
    fromEndTime: '2026-09-13T16:00:00',
    toStartTime: '2026-09-14T14:00:00',
    toEndTime: '2026-09-14T16:00:00',
    status: 'PENDING_REVIEW',
    reasonText: '希望延后到场',
    ...overrides
  }) as ReservationRescheduleRequest

const refund = (overrides: Partial<RefundRecord> = {}): RefundRecord =>
  ({
    id: 3074,
    refundNo: 'RF-20260913-0001',
    orderId: 2074,
    userId: 18,
    refundAmount: 100,
    refundReason: '行程有变，无法按原计划到场',
    status: 'PENDING_REVIEW',
    ...overrides
  }) as RefundRecord

describe('initialTabFromRoute', () => {
  it('四条旧路由映射四个初始页签', () => {
    expect(initialTabFromRoute('VenueAdminDashboard')).toBe('day')
    expect(initialTabFromRoute('VenueAdminReservations')).toBe('review')
    expect(initialTabFromRoute('VenueAdminCheckins')).toBe('arrival')
    expect(initialTabFromRoute('VenueAdminOrders')).toBe('orders')
    expect(initialTabFromRoute(undefined)).toBe('day')
  })
})

describe('buildRows / 列表口径', () => {
  const rows = buildRows(
    [reservation(), reservation({ id: 1072, status: 'PENDING_REVIEW', startTime: '2026-09-13T14:00:00', endTime: '2026-09-13T16:00:00' }), reservation({ id: 1068, status: 'COMPLETED' })],
    [order(), order({ id: 2068, reservationId: 1068, status: 'CLOSED', paidAmount: 80 })],
    [lifecycle(), lifecycle({ reservationId: 1068, checkinStatus: 'CHECKED_IN', attendanceStatus: 'PRESENT' })],
    [reschedule()],
    [refund({ orderId: 2073 })]
  )

  it('按预约拼装订单/履约/改期/退款', () => {
    const main = rows.find((row) => row.reservation.id === 1073)!
    expect(main.order?.id).toBe(2073)
    expect(main.lifecycle?.checkinCodeLast4).toBe('4821')
    expect(main.refund?.status).toBe('PENDING_REVIEW')
    expect(rows.find((row) => row.reservation.id === 1072)?.reschedule?.id).toBe(5)
  })

  it('到场范围：当前=PAID/REFUNDING，历史=COMPLETED', () => {
    const current = arrivalRows(rows, false).map((row) => row.reservation.id)
    expect(current).toEqual([1073])
    const history = arrivalRows(rows, true).map((row) => row.reservation.id)
    expect(history).toEqual([1068])
  })

  it('审核列表：待审改期 + 处理中状态集合（COMPLETED 不入列）', () => {
    const ids = reviewRows(rows).map((row) => row.reservation.id)
    expect(ids).toContain(1072)
    expect(ids).toContain(1073)
    expect(ids).not.toContain(1068)
  })

  it('搜索匹配预约号/联系人/凭证尾号', () => {
    const main = rows.find((row) => row.reservation.id === 1073)!
    expect(matchArrivalQuery(main, '4821')).toBe(true)
    expect(matchArrivalQuery(main, '#1073')).toBe(true)
    expect(matchArrivalQuery(main, '示例用户018')).toBe(true)
    expect(matchArrivalQuery(main, '9999')).toBe(false)
  })

  it('排序：普通 < 爽约候选 < 退款中', () => {
    const withFlags = buildRows(
      [
        reservation({ id: 1, startTime: '2026-09-13T10:00:00', endTime: '2026-09-13T11:00:00' }),
        reservation({ id: 2, status: 'REFUNDING', startTime: '2026-09-13T09:00:00', endTime: '2026-09-13T10:00:00' }),
        reservation({ id: 3, startTime: '2026-09-13T11:00:00', endTime: '2026-09-13T12:00:00' })
      ],
      [], [],
      [],
      []
    )
    const withLifecycle = withFlags.map((row, index) => ({
      ...row,
      lifecycle: index === 2 ? lifecycle({ reservationId: 3, noShowStatus: 'CANDIDATE' }) : null
    }))
    expect(sortArrival(withLifecycle).map((row) => row.reservation.id)).toEqual([1, 3, 2])
  })
})

describe('核销资格与窗口', () => {
  const row = buildRows([reservation()], [order()], [lifecycle()], [], [])[0]
  const now = new Date('2026-09-13T19:00:00')

  it('人工放行资格按凭证与状态判断', () => {
    expect(canManual(row)).toBe(true)
    const checkedIn = buildRows([reservation()], [], [lifecycle({ checkinStatus: 'CHECKED_IN', attendanceStatus: 'PRESENT' })], [], [])[0]
    expect(canManual(checkedIn)).toBe(false)
  })

  it('普通核销要求在窗口内', () => {
    expect(canVerify(row, now)).toBe(true)
    expect(canVerify(row, new Date('2026-09-13T21:00:00'))).toBe(false)
  })

  it('窗口文案带今日/明日前缀', () => {
    expect(verifyWindowLabel(row, now)).toBe('今日 18:30—20:30')
    expect(verifyWindowLabel(row, new Date('2026-09-14T10:00:00'))).toBe('09月13日 18:30—20:30')
  })

  it('无凭证生命周期时不可核销并给出提示', () => {
    const noLifecycle = buildRows([reservation()], [order()], [], [], [])[0]
    expect(canVerify(noLifecycle, now)).toBe(false)
    expect(arrivalBlockedNote(noLifecycle)).toContain('没有已确认可用的核销凭证')
  })
})

describe('展示归纳', () => {
  it('凭证列：尾号/冻结/历史/待生成', () => {
    const row = buildRows([reservation()], [order()], [lifecycle()], [], [])[0]
    expect(credentialText(row)).toBe('仅显示尾号 4821')
    const refunding = buildRows([reservation({ status: 'REFUNDING' })], [], [], [], [])[0]
    expect(credentialText(refunding)).toBe('退款中，凭证冻结')
    const completed = buildRows([reservation({ status: 'COMPLETED' })], [], [], [], [])[0]
    expect(credentialText(completed)).toBe('历史履约记录')
  })

  it('到场徽标：未核销才并列爽约标记', () => {
    const candidate = buildRows([reservation()], [], [lifecycle({ noShowStatus: 'CANDIDATE' })], [], [])[0]
    expect(arrivalCheckLabel(candidate)).toBe('爽约待确认')
    const attended = buildRows([reservation()], [], [lifecycle({ noShowStatus: 'CANDIDATE', checkinStatus: 'CHECKED_IN', attendanceStatus: 'MANUAL_PRESENT' })], [], [])[0]
    expect(arrivalCheckLabel(attended)).toBe('人工到场')
  })

  it('订单状态与支付方式', () => {
    const row = buildRows([reservation()], [order()], [], [], [])[0]
    expect(orderDisplayStatus(row)).toBe('PAID')
    expect(paySourceLabel(row.order)).toBe('DEV 外部支付')
    const stored = buildRows([reservation()], [order({ paymentToolType: 'STORED_VALUE' })], [], [], [])[0]
    expect(paySourceLabel(stored.order)).toBe('储值余额')
    expect(discountOf(order({ couponDiscountAmount: 20, memberDiscountAmount: 5 }))).toBe(25)
  })

  it('退款说明按状态渲染（措辞对齐预览）', () => {
    expect(refundNote(refund({ status: 'REFUNDING' }))).toContain('不代表资金已到账')
    expect(refundNote(refund({ status: 'REJECTED' }))).toContain('恢复为已支付')
    expect(refundNote(refund({ status: 'SUCCESS' }))).toContain('核销凭证失效')
    expect(refundNote(null)).toContain('没有待审核退款')
  })

  it('时间格式对齐预览', () => {
    expect(whenRange('2026-09-13T19:00:00', '2026-09-13T20:00:00')).toBe('09月13日 19:00—20:00')
    expect(monthDay('2026-09-14T09:00:00')).toBe('09月14日')
    expect(timeSpan('2026-09-13T19:00:00', '2026-09-13T20:00:00')).toBe('19:00—20:00')
    expect(reservationNo(buildRows([reservation()], [], [], [], [])[0])).toBe('#1073')
  })
})
