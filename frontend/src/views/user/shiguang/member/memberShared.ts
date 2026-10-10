import type { MemberRewardCatalogResponse, MemberRewardRedemptionResponse, UserCouponResponse } from '@/api/types'
import type { ShiguangScene } from '@/components/shiguang/types'
import { parseDate } from '@/utils/datetime'

/**
 * 会员中心页面共享的视觉派生与标签规则（SHIGUANG-MEMBER-REFACTOR-20260912 S04）。
 * 只负责把真实服务端字段映射到冻结参考的呈现位置；券可用口径（G2）、
 * 来源文案（G4 透出 sourceType/releasedAt）与懒过期推导以服务端合同为准，不承载业务判定。
 */

export const formatMemberMoney = (value?: number | string | null): string =>
  Number(value ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export const formatMemberInt = (value?: number | string | null): string =>
  Number(value ?? 0).toLocaleString('zh-CN')

export const formatMemberDate = (value?: string | Date | null): string => {
  const date = parseDate(value)
  if (!date) return ''
  return `${date.getFullYear()}.${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}`
}

export const formatMemberDateTime = (value?: string | Date | null): string => {
  const date = parseDate(value)
  if (!date) return ''
  return `${formatMemberDate(date)} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

/* ---------- 积分与成长值（member_account 事实，互不混账） ---------- */

/** 可用积分口径与 MemberRewardService 一致：max(0, pointsBalance)。 */
export const availablePointsOf = (pointsBalance?: number | null): number => Math.max(0, Number(pointsBalance ?? 0))
/** 待抵扣积分口径：max(0, -pointsBalance)。 */
export const pendingOffsetPointsOf = (pointsBalance?: number | null): number => Math.max(0, -Number(pointsBalance ?? 0))

/* ---------- 券（G2：可用口径与懒过期推导，后端零改动） ---------- */

export const COUPON_STATUS_TEXT: Record<string, string> = {
  CLAIMED: '可用',
  RELEASED: '已释放 · 可用',
  LOCKED: '订单锁定中',
  USED: '已使用',
  EXPIRED: '已过期',
  VOIDED: '已作废',
  REFUNDED: '已退款 · 不可复用'
}

const USABLE_STATUSES = new Set(['CLAIMED', 'RELEASED'])

/** 服务端存储列永无 EXPIRED（懒过期）：可用态且 validEndTime 已过 → 展示层推导 EXPIRED。 */
export const couponDisplayStatus = (coupon: UserCouponResponse, now: Date = new Date()): string => {
  const status = (coupon.status ?? '').toUpperCase()
  if (USABLE_STATUSES.has(status)) {
    const end = parseDate(coupon.validEndTime)
    if (end && end.getTime() < now.getTime()) return 'EXPIRED'
  }
  return status
}

export const isCouponUsable = (coupon: UserCouponResponse, now: Date = new Date()): boolean =>
  USABLE_STATUSES.has(couponDisplayStatus(coupon, now))

/** 临期：可用且 7 天内到期（与旧 CouponsView「剩 N 天」同口径）。 */
export const couponExpiringSoonDays = (coupon: UserCouponResponse, now: Date = new Date()): number | null => {
  if (!isCouponUsable(coupon, now)) return null
  const end = parseDate(coupon.validEndTime)
  if (!end) return null
  const days = Math.ceil((end.getTime() - now.getTime()) / 86400000)
  return days >= 0 && days <= 7 ? days : null
}

export type CouponFolderFilter = 'available' | 'locked' | 'used' | 'inactive' | 'all'

/** 券夹筛选口径（预览 mine 模式）：available=可用（CLAIMED/RELEASED 未过期）、locked=LOCKED、used=USED、inactive=EXPIRED/USED 以外失效（EXPIRED/VOIDED/REFUNDED）。 */
export const couponMatchesFilter = (coupon: UserCouponResponse, filter: CouponFolderFilter, now: Date = new Date()): boolean => {
  const status = couponDisplayStatus(coupon, now)
  switch (filter) {
    case 'available': return USABLE_STATUSES.has(status)
    case 'locked': return status === 'LOCKED'
    case 'used': return status === 'USED'
    case 'inactive': return status === 'EXPIRED' || status === 'VOIDED' || status === 'REFUNDED'
    default: return true
  }
}

export const couponStatusText = (coupon: UserCouponResponse, now: Date = new Date()): string =>
  COUPON_STATUS_TEXT[couponDisplayStatus(coupon, now)] ?? coupon.status ?? ''

export const couponStatusTone = (coupon: UserCouponResponse, now: Date = new Date()): '' | 'warning' | 'neutral' => {
  if (isCouponUsable(coupon, now)) return ''
  return couponDisplayStatus(coupon, now) === 'LOCKED' ? 'warning' : 'neutral'
}

/** 券面额表达：PERCENT 折、其余 ¥（CASH 减 / FIXED 券后一口价，门槛与规则在 ruleText 区分）。 */
export const couponFaceValue = (coupon: Pick<UserCouponResponse, 'couponType' | 'discountValue'>): string => {
  const value = Number(coupon.discountValue ?? 0)
  if (coupon.couponType === 'PERCENT') return `${Number((value * 10).toFixed(1))}`
  return formatMemberMoney(value)
}

export const couponRuleText = (coupon: Pick<UserCouponResponse, 'couponType' | 'discountValue' | 'thresholdAmount'>): string => {
  const threshold = Number(coupon.thresholdAmount ?? 0)
  const value = Number(coupon.discountValue ?? 0)
  if (coupon.couponType === 'PERCENT') return `单笔订单满 ${threshold.toFixed(0)} 元享 ${Number((value * 10).toFixed(2))} 折`
  if (coupon.couponType === 'FIXED') return `单笔订单满 ${threshold.toFixed(0)} 元，券后一口价 ¥${formatMemberMoney(value)}`
  return `单笔订单满 ${threshold.toFixed(0)} 元减 ¥${formatMemberMoney(value)}`
}

/** 来源文案（G4：sourceType/releasedAt 既有列透出后的如实映射；无事实时通用表达，不虚构）。 */
export const couponSourceText = (coupon: UserCouponResponse): string => {
  if (coupon.sourceType === 'MEMBER_REWARD') return '积分兑换'
  if (coupon.releasedAt) return '取消未支付订单后释放'
  if (coupon.sourceType === 'USER') return '自主领取'
  return '平台发放'
}

/** 券详情弹层的状态说明（预览 openCoupon 同款语义）。 */
export const couponNoticeText = (coupon: UserCouponResponse, now: Date = new Date()): { warning: boolean; text: string } => {
  const status = couponDisplayStatus(coupon, now)
  if (status === 'LOCKED') return { warning: true, text: '这张券被进行中的预约订单锁定，不能用于另一笔订单。取消未支付订单后的释放结果由服务端判定。' }
  if (status === 'REFUNDED') return { warning: true, text: '原订单已退款。这张券不自动恢复可用，不能当作未使用优惠券再次使用。' }
  if (USABLE_STATUSES.has(status)) return { warning: false, text: '可用不代表适用于所有订单。门槛、叠加与最终优惠需在结算页确认。' }
  return { warning: false, text: '这张券只作为历史记录保留，不能再次使用。' }
}

/* ---------- 权益目录与兑换记录 ---------- */

/** 目录行静物场景（视觉派生：名称关键词近运动/阅读/水/相聚，非业务事实）。 */
export const sceneForReward = (item: Pick<MemberRewardCatalogResponse, 'itemCode' | 'itemName'>): ShiguangScene => {
  const text = `${item.itemCode ?? ''} ${item.itemName ?? ''}`
  if (/球|运动|挥拍|MOVE|SPORT/i.test(text)) return 'move'
  if (/读|书|安静|自习|QUIET|READ/i.test(text)) return 'quiet'
  if (/泳|水|WATER|SWIM/i.test(text)) return 'water'
  return 'together'
}

/** 兑换门槛/原因表达：优先服务端 unavailableReason（唯一权威），缺省按字段推导。 */
export const rewardUnavailableText = (item: MemberRewardCatalogResponse): string => {
  if (item.eligible) return ''
  if (item.unavailableReason?.trim()) return item.unavailableReason.trim()
  return '暂不满足兑换条件'
}

export const RECORD_STATUS_TEXT: Record<string, string> = {
  SUCCEEDED: '已兑换',
  PENDING: '处理中',
  REJECTED: '未通过',
  FAILED: '兑换失败',
  COMPENSATED: '积分已补偿'
}

export const recordStatusText = (record: MemberRewardRedemptionResponse): string =>
  RECORD_STATUS_TEXT[(record.status ?? '').toUpperCase()] ?? record.status ?? ''

export const recordStatusTone = (record: MemberRewardRedemptionResponse): '' | 'warning' | 'error' => {
  const status = (record.status ?? '').toUpperCase()
  if (status === 'SUCCEEDED') return ''
  if (status === 'PENDING' || status === 'COMPENSATED') return 'warning'
  return 'error'
}

/** 记录行积分值：PENDING 待核对、COMPENSATED 返还为正、其余为扣减（预览 recordRow 同款）。 */
export const recordPointsText = (record: MemberRewardRedemptionResponse): string => {
  const status = (record.status ?? '').toUpperCase()
  if (status === 'PENDING') return '待核对'
  const cost = formatMemberInt(record.pointsCost ?? 0)
  return status === 'COMPENSATED' ? `+${cost}` : `−${cost}`
}

/** 记录行/详情说明：以服务端事实为准，不虚构成功。 */
export const recordDetailText = (record: MemberRewardRedemptionResponse): string => {
  const status = (record.status ?? '').toUpperCase()
  if (status === 'SUCCEEDED') return record.userCouponId ? '优惠券已放入券夹' : '兑换已完成'
  if (status === 'COMPENSATED') return '发放未完成，积分已退回'
  if (status === 'PENDING') return '结果尚未确认，请核对原请求；不会重复提交'
  return record.failureMessage?.trim() || '兑换未通过，积分未扣减'
}

/* ---------- 储值（资格五态与分账事实） ---------- */

export const STORED_BUCKET_TEXT: Record<string, string> = {
  PRINCIPAL_AVAILABLE: '本金',
  PRINCIPAL_FROZEN: '本金冻结',
  BONUS_AVAILABLE: '赠送',
  BONUS_FROZEN: '赠送冻结',
  CHANNEL_CLEARING: '渠道在途',
  PROVIDER_REFUND_CLEARING: '退款在途',
  ORDER_SETTLEMENT: '订单结算'
}

export const storedBucketText = (bucket?: string | null): string =>
  STORED_BUCKET_TEXT[(bucket ?? '').toUpperCase()] ?? bucket ?? ''

export const BONUS_BATCH_STATUS_TEXT: Record<string, string> = {
  AVAILABLE: '可用',
  EXPIRED: '已过期',
  EXHAUSTED: '已用完',
  FROZEN: '已冻结'
}

/** 充值单状态表达（创建≠到账；以服务端状态机为准）。 */
export const RECHARGE_STATUS_TEXT: Record<string, string> = {
  INITIATED: '已创建',
  WAITING_PROVIDER: '等待渠道确认',
  PROVIDER_SUCCEEDED_PENDING_POSTING: '渠道已确认 · 入账中',
  POSTING: '入账中',
  SUCCEEDED: '已入账',
  FAILED: '充值失败',
  CANCELED: '已取消',
  TIMED_OUT: '处理超时',
  MANUAL_REVIEW: '人工复核中',
  REFUNDING: '退款中',
  REFUNDED: '已退款',
  DISPUTED: '争议处理中'
}

/** 结果未知集合：这些状态下必须保留原请求身份、允许核对，不重复建单。 */
export const RECHARGE_UNCERTAIN_STATUSES = new Set(['INITIATED', 'WAITING_PROVIDER', 'PROVIDER_SUCCEEDED_PENDING_POSTING', 'POSTING', 'MANUAL_REVIEW'])

/* ---------- 序列号与折扣 ---------- */

/** 会员凭证序列号：memberAccountId 格式化（合同矩阵第 4 节；不虚构独立序列号列）。 */
export const memberSerialText = (memberAccountId?: number | null): string =>
  `NO. ${String(Math.max(0, Number(memberAccountId ?? 0))).padStart(6, '0')}`

/** 折扣表达：0.95 → 9.5；>=1 → 原价。 */
export const discountText = (discountRate?: number | null): { main: string; suffix: string; plain: boolean } => {
  const rate = Number(discountRate ?? 1)
  if (rate >= 1) return { main: '原价', suffix: '', plain: true }
  const text = Number((rate * 10).toFixed(2)).toString()
  const [int, frac] = text.split('.')
  return { main: frac ? `${int}<span>.</span>${frac}` : int, suffix: '折', plain: false }
}
