/**
 * 后台拾光四面共享展示层（SHIGUANG-BATCH-02-REFACTOR-20260913 S03）。
 * 只收四面共用的枚举标签与格式化；文案逐字对齐冻结参考 reference/preview 的 e/c/f 标签表，
 * 预览未展示的真实枚举（退款 FAILED 等）按同风格补全，不改变已确认措辞。
 * 预约/订单/支付标签、金额与时间段格式直接复用交易面共享层，保证场馆侧与用户侧同一事实。
 */

import type { HttpRequestConfig } from '@/api/http'

export {
  RESERVATION_STATUS_LABELS,
  ORDER_STATUS_LABELS,
  PAYMENT_STATUS_LABELS,
  reservationStatusLabel,
  orderStatusLabel,
  paymentStatusLabel,
  money,
  timeRange,
  dateTimeRange,
  whenLabel,
  maskPhone,
  maskEmail
} from '@/views/user/shiguang/transactions/transactionsShared'

/**
 * 后台四面统一请求降级配置（contracts.md §6）：403 不整页跳转、409 不全局弹窗，
 * 由面内 denied/lock/error 态与弹层错误承接，避免跳走时丢失正在核对的内容。
 */
export const ISOLATED_REQUEST: HttpRequestConfig = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

/** 到场状态（be/checkin ReservationLifecycleService 常量全集）。 */
export const ATTENDANCE_STATUS_LABELS: Record<string, string> = {
  PENDING: '待确认',
  PRESENT: '已到场',
  LATE: '迟到到场',
  MANUAL_PRESENT: '人工到场',
  ABSENT: '已确认爽约',
  CANCELED: '已取消'
}

/** 核销状态。 */
export const CHECKIN_STATUS_LABELS: Record<string, string> = {
  NOT_CHECKED_IN: '未核销',
  CHECKED_IN: '已核销',
  CANCELED: '已取消'
}

/** 爽约认定状态。 */
export const NO_SHOW_STATUS_LABELS: Record<string, string> = {
  NOT_EVALUATED: '未评估',
  CANDIDATE: '待确认',
  CONFIRMED: '已确认',
  REVOKED: '已撤销'
}

/** 凭证状态。 */
export const CREDENTIAL_STATUS_LABELS: Record<string, string> = {
  ISSUED: '已签发',
  INVALIDATED: '已失效'
}

/** 退款单状态（be/order/enums/RefundStatus 全集；FAILED 为预览未展示的真实枚举，同风格补全）。 */
export const REFUND_STATUS_LABELS: Record<string, string> = {
  PENDING_REVIEW: '待审核',
  REFUNDING: '退款中',
  SUCCESS: '退款成功',
  REJECTED: '已拒绝',
  FAILED: '退款失败'
}

/** 库存槽位状态（be/inventory VenueInventorySlotStatus 全集，与预览 labels 六值一一对应）。 */
export const SLOT_STATUS_LABELS: Record<string, string> = {
  AVAILABLE: '可约',
  RESERVED: '已约',
  UNAVAILABLE: '维护',
  MANUAL_HOLD: '保留',
  CLOSED: '关闭',
  BUFFER: '缓冲'
}

/** 不可售区间类型（预览 blockLabels 六类；WEATHER 由系统写入，不在人工表单提供）。 */
export const BLOCK_TYPE_LABELS: Record<string, string> = {
  MANUAL_HOLD: '人工保留',
  MAINTENANCE: '维护',
  PRIVATE_EVENT: '包场',
  SETUP: '布场撤场',
  CLEANING: '清洁',
  CLOSURE: '闭馆',
  WEATHER: '天气'
}

/** 预订方式（be/resource BookingMode）。 */
export const BOOKING_MODE_LABELS: Record<string, string> = {
  EXCLUSIVE: '整空间',
  CAPACITY: '按人数',
  ASSIGNED_SEAT: '指定座位'
}

/** 资源类型（be/resource ResourceCategory）。 */
export const RESOURCE_CATEGORY_LABELS: Record<string, string> = {
  SPORTS_FIELD: '体育场地',
  STUDY_ROOM: '自习空间',
  ACTIVITY_ROOM: '活动空间'
}

/** 营业日历状态（be BusinessCalendarStatus；预览 INTERNAL_ONLY 无真实枚举，不提供）。 */
export const BUSINESS_STATUS_LABELS: Record<string, string> = {
  OPEN: '正常营业',
  CLOSED: '临时闭馆',
  SPECIAL_HOURS: '特殊时段'
}

/** 场地状态（be/venue VenueResourceStatus）。 */
export const SPACE_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  MAINTENANCE: '维护中',
  CLOSED: '已关闭'
}

/** 平台账号状态。 */
export const USER_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '正常',
  DISABLED: '停用'
}

export function attendanceStatusLabel(status: string | null | undefined): string {
  return (status && ATTENDANCE_STATUS_LABELS[status]) || '待确认'
}

export function checkinStatusLabel(status: string | null | undefined): string {
  return (status && CHECKIN_STATUS_LABELS[status]) || '未核销'
}

export function noShowStatusLabel(status: string | null | undefined): string {
  return (status && NO_SHOW_STATUS_LABELS[status]) || ''
}

export function refundStatusLabel(status: string | null | undefined): string {
  return (status && REFUND_STATUS_LABELS[status]) || (status ?? '')
}

export function slotStatusLabel(status: string | null | undefined): string {
  return (status && SLOT_STATUS_LABELS[status]) || (status ?? '')
}

export function blockTypeLabel(type: string | null | undefined): string {
  return (type && BLOCK_TYPE_LABELS[type]) || (type ?? '')
}

export function bookingModeLabel(mode: string | null | undefined): string {
  return (mode && BOOKING_MODE_LABELS[mode]) || (mode ?? '')
}

export function resourceCategoryLabel(category: string | null | undefined): string {
  return (category && RESOURCE_CATEGORY_LABELS[category]) || (category ?? '')
}

export function businessStatusLabel(status: string | null | undefined): string {
  return (status && BUSINESS_STATUS_LABELS[status]) || (status ?? '')
}

export function spaceStatusLabel(status: string | null | undefined): string {
  return (status && SPACE_STATUS_LABELS[status]) || (status ?? '')
}

export function userStatusLabel(status: string | null | undefined): string {
  return (status && USER_STATUS_LABELS[status]) || (status ?? '')
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function parseLocal(value: string | null | undefined): Date | null {
  if (!value) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

const WEEKDAY_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 参考格式："09月13日"（履约列表与详情日期块）。 */
export function formatMonthDay(value: string | null | undefined): string {
  const parsed = parseLocal(value)
  if (!parsed) return ''
  return `${pad(parsed.getMonth() + 1)}月${pad(parsed.getDate())}日`
}

/** 参考格式："09月13日 · 周日"（履约页头日期）。 */
export function monthDayWithWeekday(value: string | null | undefined): string {
  const parsed = parseLocal(value)
  if (!parsed) return ''
  return `${formatMonthDay(value)} · ${WEEKDAY_LABELS[parsed.getDay()]}`
}

/** 参考格式："19:00"（不含日期的时间）。 */
export function formatHm(value: string | null | undefined): string {
  const parsed = parseLocal(value)
  if (!parsed) return ''
  return `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
}

/** 参考格式："09/13"（后台日志与调用时间线两行时间格的首行）。 */
export function formatMonthDaySlash(value: string | null | undefined): string {
  const parsed = parseLocal(value)
  if (!parsed) return ''
  return `${pad(parsed.getMonth() + 1)}/${pad(parsed.getDate())}`
}

/** 参考格式："2026-09-13 21:48"（详情弹层的"发生日期"含分钟）。 */
export function formatStampDateTime(value: string | null | undefined): string {
  const parsed = parseLocal(value)
  if (!parsed) return ''
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${formatHm(value)}`
}

/**
 * 爽约标记展示口径（预览 arrival()：未核销才显示爽约标记，人工到场后不并列展示）。
 * 已核销或已有人工/正常到场记录时返回空串。
 */
export function noShowBadge(
  checkinStatus: string | null | undefined,
  noShowStatus: string | null | undefined
): string {
  if (checkinStatus === 'CHECKED_IN') return ''
  if (noShowStatus === 'CANDIDATE') return '爽约待确认'
  if (noShowStatus === 'CONFIRMED') return '爽约已确认'
  return ''
}

/**
 * 平台/后台共用权限页签口径：任一权限点满足即可进入该页签。
 * menu.hasPermission 由第一批 menu store 提供，这里只做集合判断，保持纯函数可测。
 */
export function anyPermission(has: (key: string) => boolean, keys: string[]): boolean {
  return keys.some((key) => has(key))
}

/**
 * 弹层焦点恢复的可聚焦判定（BATCH-03 S07 横向复核统一到共享层）。
 * 真实写入成功后触发按钮常被服务端结果置为禁用（「确认发布」「提交重试」等），
 * `focus()` 对禁用按钮静默失败，会把焦点留给 `body`；不可见元素同理。
 */
export function focusable(node: HTMLElement | null | undefined): node is HTMLElement {
  if (!node || !node.isConnected) return false
  if (node.hasAttribute('disabled')) return false
  return getComputedStyle(node).visibility !== 'hidden'
}

export interface FocusRestoreOptions {
  /** 面作用域选择器（如 `.sga-notice`），第二跳只在本面内按 data-test 取重生节点。 */
  rootSelector: string
  trigger: HTMLElement | null
  triggerTest: string | null
  /** 页签兜底选择器（如 `.sga-notice .a-tab[aria-pressed="true"]`）。 */
  tabSelector: string
}

/**
 * 弹层关闭后的焦点恢复链，三面共用（参考 `ref/f/common.js:2`、`ref/a/service.js:23`、`ref/a/notices.js:23`）：
 *
 * 1. 原触发元素——仍是同一节点且可聚焦时直接回焦；
 * 2. 面内同 `data-test` 的重生节点——Vue 重渲染会换掉节点，按属性找回（不用 `CSS.escape`：jsdom 不实现 `CSS` 命名空间）；
 * 3. 原触发元素即使被替换也仍然可聚焦时回焦；
 * 4. 当前页签——最后一跳，保证焦点不落到 `body`。
 */
export function restoreFocusChain(options: FocusRestoreOptions): HTMLElement | null {
  const { rootSelector, trigger, triggerTest, tabSelector } = options
  if (focusable(trigger)) {
    trigger.focus()
    return trigger
  }
  const revived = triggerTest
    ? [...document.querySelectorAll<HTMLElement>(`${rootSelector} [data-test]`)]
      .find((node) => node.getAttribute('data-test') === triggerTest && focusable(node))
    : null
  if (revived) {
    revived.focus()
    return revived
  }
  const tab = document.querySelector<HTMLElement>(tabSelector)
  if (focusable(tab)) {
    tab.focus()
    return tab
  }
  return null
}

export interface InitialTabOptions<T extends string> {
  /** 该面全部页签，顺序即「首个有权页签」的回落顺序。 */
  tabKeys: readonly T[]
  /** 路由名 → 该路由的默认页签；path/name/meta 均不在这里改动。 */
  routeTabs: Partial<Record<string, T>>
  routeName: unknown
  /** `route.hash`，含前导 `#`。 */
  hash: string
  canView: (tab: T) => boolean
}

/**
 * F-20：`AdminLayout` 以 `fullPath` 为动态组件 key，页签写入 hash 会重建本视图，
 * 因此初始页签必须 hash 优先——先合法 hash，再该路由默认面，最后首个有权页签；
 * 三者都不可读时回落首个页签，让叶子权限去决定无权态而不是发越权请求。
 */
export function resolveInitialTab<T extends string>(options: InitialTabOptions<T>): T {
  const fromHash = options.hash.replace(/^#/, '') as T
  const hashed = options.tabKeys.find((key) => key === fromHash)
  if (hashed) return hashed
  const byRoute = typeof options.routeName === 'string' ? options.routeTabs[options.routeName] : undefined
  const ordered = byRoute ? [byRoute, ...options.tabKeys] : [...options.tabKeys]
  return ordered.find((key) => options.canView(key)) ?? options.tabKeys[0]
}

let pendingTabFocus: string | null = null

/**
 * F-20：壳以 `fullPath` 为 key 重建视图，点击页签时拿到的焦点会随旧节点一起销毁。
 * 面内在切换前记下目标页签，重建后的新实例只把焦点放回同一个页签。
 */
export function rememberTabFocus(tab: string): void {
  pendingTabFocus = tab
}

/** 只有重建后的实例停在同一页签时才认领这份焦点，深链与刷新不抢焦点。 */
export function consumeTabFocus(tab: string): boolean {
  if (pendingTabFocus !== tab) return false
  pendingTabFocus = null
  return true
}
