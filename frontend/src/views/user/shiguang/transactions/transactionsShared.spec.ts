import { describe, expect, it } from 'vitest'
import {
  ORDER_STATUS_LABELS,
  RESERVATION_STATUS_LABELS,
  credentialView,
  dateLabel,
  dateParts,
  dateTimeRange,
  isEndedStatus,
  maskEmail,
  maskPhone,
  money,
  nextUpcoming,
  orderDisplayText,
  statusTone,
  timeRange,
  whenLabel,
  type TransactionRow
} from './transactionsShared'

describe('transactionsShared', () => {
  it('covers the real status enums with preview-confirmed wording', () => {
    expect(RESERVATION_STATUS_LABELS.PAID).toBe('已支付')
    expect(RESERVATION_STATUS_LABELS.PENDING_REVIEW).toBe('待审核')
    expect(RESERVATION_STATUS_LABELS.CANCELED).toBe('已取消')
    expect(RESERVATION_STATUS_LABELS.APPROVED).toBe('已通过')
    expect(ORDER_STATUS_LABELS.CLOSED).toBe('已关闭')
    expect(ORDER_STATUS_LABELS.TIMEOUT).toBe('支付超时')
    expect(statusTone('PENDING_PAYMENT')).toBe('attention')
    expect(statusTone('REFUNDING')).toBe('attention')
    expect(statusTone('PAID')).toBe('')
    expect(isEndedStatus('COMPLETED')).toBe(true)
    expect(isEndedStatus('REFUNDED')).toBe(true)
    expect(isEndedStatus('PAID')).toBe(false)
  })

  it('formats dates, times and money in the frozen reference style', () => {
    expect(dateLabel('2026-09-13T19:00:00')).toBe('9 月 13 日')
    expect(whenLabel('2026-09-13T19:00:00')).toBe('9 月 13 日 · 周日')
    expect(dateParts('2026-09-13T19:00:00')).toEqual({ day: '13', month: '9 月' })
    expect(timeRange('2026-09-13T19:00:00', '2026-09-13T20:00:00')).toBe('19:00—20:00')
    expect(dateTimeRange('2026-09-14T14:00:00', '2026-09-14T16:00:00')).toBe('9 月 14 日 14:00—16:00')
    expect(money(80)).toBe('80.00')
    expect(money(null)).toBe('0.00')
    expect(money('120.5')).toBe('120.50')
  })

  it('masks contact fields for display only', () => {
    expect(maskPhone('13800000018')).toBe('138****0018')
    expect(maskEmail('lin@example.test')).toBe('l***@example.test')
    expect(maskPhone('')).toBe('未填写')
  })

  it('derives credential states incl. expired and checked-in that cannot be refreshed', () => {
    const base = { id: 1, status: 'PAID', startTime: '2026-09-13T19:00:00', endTime: '2026-09-13T20:00:00', spaceId: 7 }
    const issued = credentialView(base, {
      reservationId: 1,
      credentialStatus: 'ISSUED',
      checkinStatus: 'NOT_CHECKED_IN',
      checkinCodeLast4: '4821',
      checkinWindowStart: '2026-09-13T18:45:00',
      checkinWindowEnd: '2026-09-13T20:15:00'
    } as never, new Date('2026-09-13T19:00:00'))
    expect(issued).toMatchObject({ invalid: false, pill: '未核销', suffix: '尾号 4821' })

    const checkedIn = credentialView(base, {
      reservationId: 1,
      credentialStatus: 'ISSUED',
      checkinStatus: 'CHECKED_IN',
      checkinCodeLast4: '4821'
    } as never, new Date('2026-09-13T19:30:00'))
    expect(checkedIn.pill).toBe('已核销')
    expect(checkedIn.invalid).toBe(true)

    const expired = credentialView(base, {
      reservationId: 1,
      credentialStatus: 'ISSUED',
      checkinStatus: 'NOT_CHECKED_IN',
      checkinWindowEnd: '2026-09-13T20:15:00'
    } as never, new Date('2026-09-14T10:00:00'))
    expect(expired.pill).toBe('凭证已过期')
    expect(expired.invalid).toBe(true)

    const unpaid = credentialView({ ...base, status: 'PENDING_PAYMENT' }, null)
    expect(unpaid.pill).toBe('当前不可用')
  })

  it('picks the next upcoming paid reservation for the hero card', () => {
    const row = (id: number, startTime: string, status: string): TransactionRow => ({
      reservation: { id, status, startTime, endTime: startTime, spaceId: 1 } as never,
      order: null,
      lifecycle: null,
      reschedulePending: false
    })
    const rows = [
      row(2, '2026-09-14T09:00:00', 'PAID'),
      row(1, '2026-09-13T19:00:00', 'PAID'),
      row(3, '2026-09-15T09:00:00', 'PENDING_PAYMENT')
    ]
    expect(nextUpcoming(rows, new Date('2026-09-13T10:00:00'))?.reservation.id).toBe(1)
    expect(nextUpcoming([row(3, '2026-09-15T09:00:00', 'PENDING_PAYMENT')], new Date('2026-09-13T10:00:00'))).toBeNull()
    expect(orderDisplayText({ id: 5, orderNo: 'ORD-5', reservationId: 1, status: 'PAID', totalAmount: 80 } as never)).toBe('订单 ORD-5')
  })
})
