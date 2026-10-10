import type { Order, Reservation, ReservationLifecycleResponse } from '@/api/types'

/**
 * 交易面本地视图类型别名（SHIGUANG-BATCH-01-REFACTOR-20260913 S04）。
 * 直接引用全局 API 类型，测试可以提供最小结构而无需完整实体。
 */
export type ReservationLike = Pick<
  Reservation,
  'id' | 'status' | 'startTime' | 'endTime' | 'spaceId'
> & {
  orderId?: number | null
  venueId?: number
  venueName?: string
  contactName?: string
  contactPhone?: string
  payableAmount?: number
  paidAmount?: number
  originalAmount?: number
  memberDiscountAmount?: number
  couponDiscountAmount?: number
  activityDiscountAmount?: number
  payExpireTime?: string
  rejectReason?: string
}

export type BizOrderLike = Pick<
  Order,
  'id' | 'orderNo' | 'reservationId' | 'status' | 'totalAmount'
> & {
  venueName?: string
  originalAmount?: number
  memberDiscountAmount?: number
  couponDiscountAmount?: number
  activityDiscountAmount?: number
  paidAmount?: number
  payableAmount?: number
  paymentToolType?: string
  expireTime?: string
  refundSummary?: Order['refundSummary']
}

export type ReservationLifecycleLike = ReservationLifecycleResponse
