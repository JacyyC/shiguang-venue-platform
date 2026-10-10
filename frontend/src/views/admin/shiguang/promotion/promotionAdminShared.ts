/**
 * 促销、优惠券与定价管理面共享展示层（SHIGUANG-BATCH-04-REFACTOR-20260914 S05）。
 *
 * 只收本面共用的枚举标签、格式化与派生，全部可测纯函数。标签优先级：服务端字典中文 >
 * 面内白名单 > 原值回落（**未知值原样显示，不隐藏、不折叠、不猜**）。
 * 真实枚举与文件行号见 contracts.md §2 与 verification/s02/contracts-promotions.md §5。
 */

/* ------------------------------------------------------------ 对象状态 */

/**
 * 活动/券模板/定价规则共用同一状态字典（`promotion_campaign_status`、`coupon_template_status`
 * 在 `mig/V2__sports_reservation_bootstrap.sql:225-234` 里取值与中文完全一致）。
 * 预览只有「启用/停用」两态，真实是五态白名单 `ADMIN_STATUSES`
 * （`be/promotion/service/PromotionAdminService.java:58`）。按 contracts.md §0 的标签冲突规则，
 * 界面中文以字典为准，不沿用旧正文的「待生效/生效中」。
 */
export const OBJECT_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  SCHEDULED: '待开始',
  ACTIVE: '进行中',
  PAUSED: '已暂停',
  ENDED: '已结束'
}

export function objectStatusLabel(status: string | null | undefined): string {
  if (!status) return ''
  return OBJECT_STATUS_LABELS[status.toUpperCase()] ?? status
}

/** 启停目标：与服务端既有前端一致，只做 ACTIVE ↔ PAUSED 二元切换，不暴露 ENDED/SCHEDULED。 */
export function objectToggleTarget(status: string | null | undefined): 'ACTIVE' | 'PAUSED' {
  return (status ?? '').toUpperCase() === 'ACTIVE' ? 'PAUSED' : 'ACTIVE'
}

/* -------------------------------------------------------------- 券状态 */

/** 券实例状态（字典 `user_coupon_status`，`mig/V2__sports_reservation_bootstrap.sql:110-116`）。 */
export const USER_COUPON_STATUS_LABELS: Record<string, string> = {
  CLAIMED: '已领取',
  LOCKED: '已锁定',
  USED: '已使用',
  RELEASED: '已释放',
  EXPIRED: '已过期',
  VOIDED: '已作废',
  REFUNDED: '已退回'
}

export function userCouponStatusLabel(status: string | null | undefined): string {
  if (!status) return ''
  return USER_COUPON_STATUS_LABELS[status.toUpperCase()] ?? status
}

/**
 * 核销记录的状态筛选项（B4-P8）。服务端白名单是 7 值，但**只有 5 值有真实写入方**：
 * `CLAIMED`（领取）/`LOCKED`（锁单）/`USED`（核销）/`REFUNDED`（退款）/`VOIDED`（权益补偿作废，
 * `be/promotion/service/CouponRewardIssuanceService.java:108`）。`RELEASED` 与 `EXPIRED` 在全仓
 * 没有任何写入 `user_coupon` 的路径（`CouponService.java:47` 的 RELEASED 常量只用于比较，
 * 券域也没有过期调度器），列出来只会得到恒空结果，属「假空态」，因此不进筛选下拉并在页脚注明。
 */
export const REDEMPTION_FILTER_STATUSES = ['CLAIMED', 'LOCKED', 'USED', 'VOIDED', 'REFUNDED'] as const

export const REDEMPTION_STATUS_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '', label: '全部状态' },
  ...REDEMPTION_FILTER_STATUSES.map((value) => ({ value, label: userCouponStatusLabel(value) }))
]

/** 服务端白名单里有、但当前没有产生路径的券状态（用于页脚如实说明，不放进筛选）。 */
export const UNPRODUCED_COUPON_STATUSES = ['RELEASED', 'EXPIRED'] as const

/* -------------------------------------------------------- 优惠与调整方式 */

/** 优惠方式（活动 `discountType`、券 `couponType` 共用）：CASH 满减 / PERCENT 折扣 / FIXED 固定价格。 */
export const DISCOUNT_TYPE_LABELS: Record<string, string> = {
  CASH: '满减',
  PERCENT: '折扣',
  FIXED: '固定价格'
}

export function discountTypeLabel(type: string | null | undefined): string {
  if (!type) return ''
  return DISCOUNT_TYPE_LABELS[type.toUpperCase()] ?? type
}

/** 定价规则调整方式（`ADJUSTMENT_TYPES`，`be/promotion/service/PromotionAdminService.java:60`）。 */
export const ADJUSTMENT_TYPE_LABELS: Record<string, string> = {
  PERCENT: '按比例调整',
  DELTA: '固定金额调整',
  FIXED_PRICE: '固定价格'
}

export function adjustmentTypeLabel(type: string | null | undefined): string {
  if (!type) return ''
  return ADJUSTMENT_TYPE_LABELS[type.toUpperCase()] ?? type
}

/** 活动类型（真实取值只有 SEASONAL / DISCOUNT 两个代码字面量，无字典）。 */
export const CAMPAIGN_TYPE_LABELS: Record<string, string> = {
  SEASONAL: '季节活动',
  DISCOUNT: '通用优惠'
}

export function campaignTypeLabel(type: string | null | undefined): string {
  if (!type) return ''
  return CAMPAIGN_TYPE_LABELS[type.toUpperCase()] ?? type
}

/**
 * 定价规则场景（`ruleType`）。服务端 `@NotBlank` 但**无白名单、无字典**，真实种子里是
 * PEAK / OFF_PEAK / WEEKEND 三个代码字面量，因此这里只是「常见值中文 + 原值回落」，
 * 同时允许表单自由输入（与既有产品行为一致）。
 */
export const RULE_TYPE_LABELS: Record<string, string> = {
  PEAK: '高峰时段',
  OFF_PEAK: '低峰时段',
  WEEKEND: '周末'
}

export function ruleTypeLabel(type: string | null | undefined): string {
  if (!type) return ''
  return RULE_TYPE_LABELS[type.toUpperCase()] ?? type
}

/* ---------------------------------------------------------------- 金额 */

/** 千分位金额，保留 2 位（与交易面口径一致）。 */
export function amount(value: number | string | null | undefined): string {
  return Number(value ?? 0).toFixed(2)
}

/** 折扣率 → 「9 折」/「9.5 折」；1 或缺失按标准价处理。 */
export function rateLabel(value: number | null | undefined): string {
  if (value == null) return '标准价'
  if (value >= 1) return '标准价'
  return `${Number((value * 10).toFixed(2))} 折`
}

/**
 * 活动的优惠展示（预览 `ref/b/promotions.js:5` 的 `减 ¥ N` / `N 折` / `¥ N 固定价`，
 * 但真实枚举是 CASH/PERCENT/FIXED 而非预览的满减/折扣/固定价格字面量）。
 */
export function campaignValueText(type: string | null | undefined, value: number | null | undefined): string {
  const code = (type ?? '').toUpperCase()
  if (code === 'PERCENT') return rateLabel(value)
  if (code === 'FIXED') return `¥ ${amount(value)} 固定价`
  return `减 ¥ ${amount(value)}`
}

/** 券模板的优惠展示（预览 `ref/b/promotions.js:6` 的「满 ¥ N · 减 ¥ N」）。 */
export function couponValueText(
  type: string | null | undefined,
  value: number | null | undefined,
  threshold: number | null | undefined
): string {
  const code = (type ?? '').toUpperCase()
  const head = `满 ¥ ${amount(threshold)}`
  if (code === 'PERCENT') return `${head} · ${rateLabel(value)}`
  if (code === 'FIXED') return `${head} · 券后 ¥ ${amount(value)}`
  return `${head} · 减 ¥ ${amount(value)}`
}

/** 定价规则的调整值展示。 */
export function adjustmentValueText(type: string | null | undefined, value: number | null | undefined): string {
  const code = (type ?? '').toUpperCase()
  if (code === 'PERCENT') return rateLabel(value)
  return `¥ ${amount(value)}`
}

/* ------------------------------------------------------------ 生效星期 */

const WEEKDAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

/**
 * `dayOfWeekMask` 的真实语义是「逗号分隔的 1-7 星期序号」，服务端按 `like '%,{day},%'` 匹配
 * （`be/promotion/mapper/PriceRuleMapper.java:19-30`），因此存储形如 `,1,2,3,`。
 */
export function weekdayMaskToDays(mask: string | null | undefined): number[] {
  if (!mask) return []
  return mask
    .split(',')
    .map((part) => Number(part.trim()))
    .filter((day) => Number.isInteger(day) && day >= 1 && day <= 7)
    .sort((a, b) => a - b)
}

export function daysToWeekdayMask(days: number[]): string {
  const unique = [...new Set(days)].filter((day) => day >= 1 && day <= 7).sort((a, b) => a - b)
  if (!unique.length) return ''
  return `,${unique.join(',')},`
}

/** 星期文案：全选 →「每天」，周一至周五 →「周一至周五」，其余按列表拼接。 */
export function weekdayText(mask: string | null | undefined): string {
  const days = weekdayMaskToDays(mask)
  if (days.length === 7) return '每天'
  if (!days.length) return '每天'
  if (days.join(',') === '1,2,3,4,5') return '周一至周五'
  if (days.join(',') === '6,7') return '周六、周日'
  return days.map((day) => WEEKDAY_NAMES[day - 1]).join('、')
}

/** 表单用的星期多选项（1=周一 … 7=周日），与 `dayOfWeekMask` 的真实序号一致。 */
export const WEEKDAY_OPTIONS: Array<{ value: number; label: string }> =
  WEEKDAY_NAMES.map((label, index) => ({ value: index + 1, label }))

/* ---------------------------------------------------------------- 时间 */

/** 参考的日期块：`09 / 12`（`ref/b/promotions.js:5` 的 `start.slice(5,10).replace('-',' / ')`）。 */
export function monthDayBlock(value: string | null | undefined): string {
  const iso = isoDate(value)
  return iso ? `${iso.slice(5, 7)} / ${iso.slice(8, 10)}` : '—'
}

export function isoDate(value: string | null | undefined): string {
  if (!value) return ''
  return String(value).slice(0, 10)
}

/** 参考的日期范围：`09-12 至 09-30`。 */
export function dateRangeText(start: string | null | undefined, end: string | null | undefined): string {
  const from = isoDate(start).slice(5)
  const to = isoDate(end).slice(5)
  if (!from && !to) return '—'
  return `${from || '—'} 至 ${to || '—'}`
}

/** 时段：`13:00–17:00`（参考用 en dash）。 */
export function clockRangeText(from: string | null | undefined, to: string | null | undefined): string {
  const head = (from ?? '').slice(0, 5)
  const tail = (to ?? '').slice(0, 5)
  if (!head && !tail) return ''
  return `${head || '—'}–${tail || '—'}`
}

/* ---------------------------------------------------------------- 派生 */

/** 促销日期块首行的年份提示（参考只显示月/日，这里补年份避免跨年误读）。 */
export function yearOf(value: string | null | undefined): string {
  return isoDate(value).slice(0, 4)
}

/**
 * 券模板的发行量展示（预览「已发 / 总量」）。真实 `total_quantity <= 0` 是既有「不限量」约定
 * （`be/promotion/mapper/CouponTemplateMapper.java:41`），不能显示成「已发 N / 0 张」。
 */
export function issueText(issued: number | null | undefined, total: number | null | undefined): string {
  const issuedValue = Number(issued ?? 0)
  const totalValue = Number(total ?? 0)
  if (totalValue <= 0) return `${issuedValue} / 不限量`
  return `${issuedValue} / ${totalValue}`
}

/** 可发放性提示：服务端领取守卫为 `total_quantity <= 0 or issued_quantity < total_quantity`。 */
export function exhausted(issued: number | null | undefined, total: number | null | undefined): boolean {
  const totalValue = Number(total ?? 0)
  return totalValue > 0 && Number(issued ?? 0) >= totalValue
}

/**
 * 概览口径（B4-P14）：`refundedCoupons` 是 `coupon_status_log.reason_code='ORDER_REFUNDED'` 的
 * **状态日志条数**（历史退款次数），不是当前 `REFUNDED` 存量；`totalCouponDiscountAmount` 只累加
 * `status='USED'` 的券优惠。界面文案必须按这两个口径写。
 */
export function overviewNote(overview: {
  activeCampaigns: number
  activeCouponTemplates: number
  activePriceRules: number
  usedCoupons: number
  refundedCoupons: number
  totalCouponDiscountAmount: number
} | null): { counts: Array<{ label: string; value: number }>; note: string } {
  if (!overview) return { counts: [], note: '' }
  return {
    counts: [
      { label: '进行中活动', value: overview.activeCampaigns },
      { label: '进行中券模板', value: overview.activeCouponTemplates },
      { label: '进行中定价规则', value: overview.activePriceRules },
      { label: '已使用券', value: overview.usedCoupons },
      { label: '累计券优惠', value: overview.totalCouponDiscountAmount }
    ],
    note: `已退回为历史退款次数（状态日志口径，当前共 ${overview.refundedCoupons} 次），不等于当前处于已退回的券数；累计券优惠只累加已使用券的优惠金额。`
  }
}

/** 核销记录的订单/时间列：预览为「订单号 + 时间」两行；无订单时显示占位符。 */
export function redemptionOrderText(orderId: number | null | undefined, claimTime: string | null | undefined): {
  order: string
  time: string
} {
  return {
    order: orderId != null && orderId > 0 ? `订单 ${orderId}` : '—',
    time: String(claimTime ?? '').slice(5, 16).replace('T', ' ')
  }
}
