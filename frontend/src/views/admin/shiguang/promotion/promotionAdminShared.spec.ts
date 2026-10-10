import { describe, expect, it } from 'vitest'
import {
  ADJUSTMENT_TYPE_LABELS,
  DISCOUNT_TYPE_LABELS,
  REDEMPTION_FILTER_STATUSES,
  REDEMPTION_STATUS_OPTIONS,
  adjustmentValueText,
  amount,
  campaignValueText,
  clockRangeText,
  couponValueText,
  dateRangeText,
  daysToWeekdayMask,
  exhausted,
  issueText,
  monthDayBlock,
  objectStatusLabel,
  objectToggleTarget,
  overviewNote,
  rateLabel,
  redemptionOrderText,
  userCouponStatusLabel,
  weekdayMaskToDays,
  weekdayText
} from './promotionAdminShared'

describe('营销面标签与格式化', () => {
  it('uses the server dictionary labels, not the retired view’s wording', () => {
    expect(objectStatusLabel('DRAFT')).toBe('草稿')
    expect(objectStatusLabel('SCHEDULED')).toBe('待开始')
    expect(objectStatusLabel('ACTIVE')).toBe('进行中')
    expect(objectStatusLabel('PAUSED')).toBe('已暂停')
    expect(objectStatusLabel('ENDED')).toBe('已结束')
    // 旧正文的「待生效/生效中」与字典不一致，不得回归
    expect(objectStatusLabel('SCHEDULED')).not.toBe('待生效')
    expect(objectStatusLabel('ACTIVE')).not.toBe('生效中')
    expect(objectStatusLabel('WHATEVER')).toBe('WHATEVER')
  })

  it('keeps the documented T5 binary toggle and does not expose ENDED/SCHEDULED', () => {
    expect(objectToggleTarget('ACTIVE')).toBe('PAUSED')
    expect(objectToggleTarget('PAUSED')).toBe('ACTIVE')
    expect(objectToggleTarget('DRAFT')).toBe('ACTIVE')
    expect(objectToggleTarget('ENDED')).toBe('ACTIVE')
  })

  it('labels all seven coupon statuses so nothing falls back to a raw code (B4-P8)', () => {
    expect(userCouponStatusLabel('CLAIMED')).toBe('已领取')
    expect(userCouponStatusLabel('LOCKED')).toBe('已锁定')
    expect(userCouponStatusLabel('USED')).toBe('已使用')
    expect(userCouponStatusLabel('VOIDED')).toBe('已作废')
    expect(userCouponStatusLabel('REFUNDED')).toBe('已退回')
    // 有枚举、有字典但没有写入方的两值也必须能显示（历史数据可能出现），只是不进筛选
    expect(userCouponStatusLabel('RELEASED')).toBe('已释放')
    expect(userCouponStatusLabel('EXPIRED')).toBe('已过期')
    expect(userCouponStatusLabel('NEW_CODE')).toBe('NEW_CODE')
  })

  it('offers only coupon statuses with a real writer in the filter (B4-P8)', () => {
    expect([...REDEMPTION_FILTER_STATUSES]).toEqual(['CLAIMED', 'LOCKED', 'USED', 'VOIDED', 'REFUNDED'])
    const values = REDEMPTION_STATUS_OPTIONS.map((option) => option.value)
    expect(values[0]).toBe('')
    expect(values).not.toContain('RELEASED')
    expect(values).not.toContain('EXPIRED')
    expect(REDEMPTION_STATUS_OPTIONS[1]).toEqual({ value: 'CLAIMED', label: '已领取' })
  })

  it('maps the real discount and adjustment enums', () => {
    expect(DISCOUNT_TYPE_LABELS.PERCENT).toBe('折扣')
    expect(ADJUSTMENT_TYPE_LABELS.DELTA).toBe('固定金额调整')
    expect(adjustmentValueText('PERCENT', 0.85)).toBe('8.5 折')
    expect(adjustmentValueText('DELTA', 10)).toBe('¥ 10.00')
  })

  it('formats money, rates and object values the way the reference does', () => {
    expect(amount(2480)).toBe('2480.00')
    expect(amount(null)).toBe('0.00')
    expect(rateLabel(1)).toBe('标准价')
    expect(rateLabel(0.9)).toBe('9 折')
    expect(rateLabel(0.85)).toBe('8.5 折')
    expect(campaignValueText('CASH', 20)).toBe('减 ¥ 20.00')
    expect(campaignValueText('PERCENT', 0.9)).toBe('9 折')
    expect(campaignValueText('FIXED', 30)).toBe('¥ 30.00 固定价')
    expect(couponValueText('CASH', 10, 50)).toBe('满 ¥ 50.00 · 减 ¥ 10.00')
    expect(couponValueText('FIXED', 30, 50)).toBe('满 ¥ 50.00 · 券后 ¥ 30.00')
  })

  it('reads the comma weekday mask with the server’s own semantics', () => {
    expect(weekdayMaskToDays(',1,2,3,4,5,')).toEqual([1, 2, 3, 4, 5])
    expect(weekdayMaskToDays('6,7')).toEqual([6, 7])
    expect(weekdayMaskToDays(null)).toEqual([])
    expect(daysToWeekdayMask([2, 1, 1])).toBe(',1,2,')
    expect(daysToWeekdayMask([])).toBe('')
    expect(weekdayText(',1,2,3,4,5,')).toBe('周一至周五')
    expect(weekdayText('6,7')).toBe('周六、周日')
    expect(weekdayText(',1,2,3,4,5,6,7,')).toBe('每天')
    expect(weekdayText('')).toBe('每天')
    expect(weekdayText(',1,3,')).toBe('周一、周三')
  })

  it('formats the reference date block and clock range', () => {
    expect(monthDayBlock('2026-09-12T09:00:00')).toBe('09 / 12')
    expect(monthDayBlock(null)).toBe('—')
    expect(dateRangeText('2026-09-12T09:00:00', '2026-09-30T22:00:00')).toBe('09-12 至 09-30')
    expect(clockRangeText('13:00:00', '17:00:00')).toBe('13:00–17:00')
    expect(clockRangeText(null, null)).toBe('')
  })

  it('treats a non-positive total as the existing unlimited convention (B4-P6)', () => {
    expect(issueText(128, 500)).toBe('128 / 500')
    expect(issueText(7, 0)).toBe('7 / 不限量')
    expect(issueText(7, null)).toBe('7 / 不限量')
    expect(exhausted(500, 500)).toBe(true)
    expect(exhausted(499, 500)).toBe(false)
    expect(exhausted(999, 0)).toBe(false)
  })

  it('states the real overview semantics instead of the preview wording (B4-P14)', () => {
    const { counts, note } = overviewNote({
      activeCampaigns: 1,
      activeCouponTemplates: 2,
      activePriceRules: 3,
      usedCoupons: 85,
      refundedCoupons: 4,
      totalCouponDiscountAmount: 1060
    })
    expect(counts.map((row) => row.label)).toEqual([
      '进行中活动', '进行中券模板', '进行中定价规则', '已使用券', '累计券优惠'
    ])
    // 已退回是状态日志条数（历史退款次数），不是当前存量
    expect(note).toContain('历史退款次数')
    expect(note).toContain('不等于当前处于已退回的券数')
    expect(note).toContain('只累加已使用券')
    expect(overviewNote(null)).toEqual({ counts: [], note: '' })
  })

  it('renders the redemption order/time cell without inventing an order', () => {
    expect(redemptionOrderText(9018, '2026-09-12T10:12:00')).toEqual({ order: '订单 9018', time: '09-12 10:12' })
    expect(redemptionOrderText(null, '2026-09-11T18:22:00')).toEqual({ order: '—', time: '09-11 18:22' })
    expect(redemptionOrderText(undefined, null).order).toBe('—')
  })
})
