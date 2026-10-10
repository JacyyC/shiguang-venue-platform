import { describe, expect, it } from 'vitest'
import type { MemberRewardRedemptionResponse, UserCouponResponse } from '@/api/types'
import {
  availablePointsOf,
  couponDisplayStatus,
  couponExpiringSoonDays,
  couponFaceValue,
  couponMatchesFilter,
  couponRuleText,
  couponSourceText,
  discountText,
  isCouponUsable,
  memberSerialText,
  pendingOffsetPointsOf,
  recordPointsText
} from './memberShared'

const coupon = (overrides: Partial<UserCouponResponse> = {}): UserCouponResponse => ({
  id: 1,
  templateId: 5,
  status: 'CLAIMED',
  couponType: 'CASH',
  discountValue: 15,
  thresholdAmount: 80,
  validStartTime: '2026-09-01T00:00:00',
  validEndTime: '2026-10-01T00:00:00',
  ...overrides
})

const NOW = new Date('2026-09-12T12:00:00')

describe('memberShared 积分口径', () => {
  it('available/pending 与 MemberRewardService 同口径', () => {
    expect(availablePointsOf(860)).toBe(860)
    expect(availablePointsOf(-80)).toBe(0)
    expect(pendingOffsetPointsOf(-80)).toBe(80)
    expect(pendingOffsetPointsOf(860)).toBe(0)
  })
})

describe('memberShared 券口径（G2 懒过期推导）', () => {
  it('存储列永无 EXPIRED：可用态过期后推导为 EXPIRED 且不可用', () => {
    const expired = coupon({ validEndTime: '2026-09-01T00:00:00' })
    expect(couponDisplayStatus(expired, NOW)).toBe('EXPIRED')
    expect(isCouponUsable(expired, NOW)).toBe(false)
    const valid = coupon()
    expect(couponDisplayStatus(valid, NOW)).toBe('CLAIMED')
    expect(isCouponUsable(valid, NOW)).toBe(true)
  })

  it('RELEASED 存量容忍：未过期可用、过期推导失效', () => {
    expect(isCouponUsable(coupon({ status: 'RELEASED' }), NOW)).toBe(true)
    expect(isCouponUsable(coupon({ status: 'RELEASED', validEndTime: '2026-09-01T00:00:00' }), NOW)).toBe(false)
  })

  it('LOCKED/USED/REFUNDED 不计入可用', () => {
    for (const status of ['LOCKED', 'USED', 'REFUNDED', 'VOIDED']) {
      expect(isCouponUsable(coupon({ status }), NOW)).toBe(false)
    }
  })

  it('券夹筛选：available/locked/used/inactive/all 五桶不混计', () => {
    const list = [
      coupon({ id: 1 }),
      coupon({ id: 2, validEndTime: '2026-09-01T00:00:00' }),
      coupon({ id: 3, status: 'LOCKED' }),
      coupon({ id: 4, status: 'USED' }),
      coupon({ id: 5, status: 'REFUNDED' })
    ]
    expect(list.filter((c) => couponMatchesFilter(c, 'available', NOW)).map((c) => c.id)).toEqual([1])
    expect(list.filter((c) => couponMatchesFilter(c, 'locked', NOW)).map((c) => c.id)).toEqual([3])
    expect(list.filter((c) => couponMatchesFilter(c, 'used', NOW)).map((c) => c.id)).toEqual([4])
    expect(list.filter((c) => couponMatchesFilter(c, 'inactive', NOW)).map((c) => c.id).sort()).toEqual([2, 5])
    expect(list.filter((c) => couponMatchesFilter(c, 'all', NOW))).toHaveLength(5)
  })

  it('临期 7 天口径：仅可用券且到期在 7 天内', () => {
    expect(couponExpiringSoonDays(coupon({ validEndTime: '2026-09-18T00:00:00' }), NOW)).toBe(6)
    expect(couponExpiringSoonDays(coupon({ validEndTime: '2026-10-12T00:00:00' }), NOW)).toBeNull()
    expect(couponExpiringSoonDays(coupon({ status: 'LOCKED', validEndTime: '2026-09-13T00:00:00' }), NOW)).toBeNull()
  })

  it('面额与规则文案：PERCENT 折、CASH 减、FIXED 券后一口价', () => {
    expect(couponFaceValue({ couponType: 'PERCENT', discountValue: 0.9 })).toBe('9')
    expect(couponFaceValue({ couponType: 'CASH', discountValue: 15 })).toBe('15.00')
    expect(couponRuleText({ couponType: 'PERCENT', discountValue: 0.9, thresholdAmount: 30 })).toBe('单笔订单满 30 元享 9 折')
    expect(couponRuleText({ couponType: 'FIXED', discountValue: 50, thresholdAmount: 200 })).toBe('单笔订单满 200 元，券后一口价 ¥50.00')
    expect(couponRuleText({ couponType: 'CASH', discountValue: 15, thresholdAmount: 80 })).toBe('单笔订单满 80 元减 ¥15.00')
  })

  it('来源文案（G4）：MEMBER_REWARD 积分兑换、releasedAt 释放、USER 自主领取', () => {
    expect(couponSourceText(coupon({ sourceType: 'MEMBER_REWARD' }))).toBe('积分兑换')
    expect(couponSourceText(coupon({ sourceType: 'USER', releasedAt: '2026-09-05T10:00:00' }))).toBe('取消未支付订单后释放')
    expect(couponSourceText(coupon({ sourceType: 'USER' }))).toBe('自主领取')
    expect(couponSourceText(coupon({ sourceType: undefined }))).toBe('平台发放')
  })
})

describe('memberShared 记录与身份表达', () => {
  const record = (status: string): MemberRewardRedemptionResponse => ({
    id: 1,
    redemptionNo: 'RD-1',
    itemCode: 'R1',
    itemName: '权益',
    status,
    pointsCost: 200
  })

  it('记录积分值：PENDING 待核对、COMPENSATED 返还、其余扣减', () => {
    expect(recordPointsText(record('PENDING'))).toBe('待核对')
    expect(recordPointsText(record('COMPENSATED'))).toBe('+200')
    expect(recordPointsText(record('SUCCEEDED'))).toBe('−200')
    expect(recordPointsText(record('REJECTED'))).toBe('−200')
  })

  it('序列号用 memberAccountId 格式化', () => {
    expect(memberSerialText(9621)).toBe('NO. 009621')
    expect(memberSerialText(undefined)).toBe('NO. 000000')
  })

  it('折扣：9.5 折与原价', () => {
    expect(discountText(0.95)).toEqual({ main: '9<span>.</span>5', suffix: '折', plain: false })
    expect(discountText(1)).toEqual({ main: '原价', suffix: '', plain: true })
    expect(discountText(undefined)).toEqual({ main: '原价', suffix: '', plain: true })
  })
})
