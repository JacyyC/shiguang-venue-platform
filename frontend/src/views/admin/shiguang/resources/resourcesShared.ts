/**
 * 资源面共享逻辑（SHIGUANG-BATCH-02-REFACTOR-20260913 S04）。
 * 预览 ref/e/resources.js 的展示推导改为服务端事实驱动：
 * 小时轴来自营业窗口（日历覆盖或场馆常规营业时间），槽位/记录/规则版本全部来自真实接口，
 * 固定示例快照（fixture）、pendingOverrides 本地覆写与响应切换器不迁入。
 */
import type { InventorySlot, VenueBusinessCalendar, VenueMaintenance, VenueSpace, VenueUnavailableBlock } from '@/api/types'
import { bookingModeLabel, businessStatusLabel, spaceStatusLabel } from '../adminShared'

export interface SpaceCell {
  hour: number
  slot: InventorySlot | null
}

export interface ResourceRecordRow {
  kind: 'maintenance' | 'block'
  id: number
  spaceId: number | null
  spaceName: string
  typeLabel: string
  reason: string
  startTime: string
  endTime: string
  status: string
  active: boolean
  bufferBefore: number
  bufferAfter: number
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

export function todayIso(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

/** "08:00" → 8；解析失败返回 null。 */
export function hourFromTimeText(value: string | null | undefined): number | null {
  const match = /^(\d{1,2}):(\d{2})/.exec((value ?? '').trim())
  if (!match) return null
  const hour = Number(match[1])
  return hour >= 0 && hour <= 23 ? hour : null
}

/** 营业窗口小时轴：日历覆盖优先，其次场馆常规营业时间；闭馆日回退常规营业时间以保留矩阵骨架。 */
export function hoursAxis(
  venueOpen: string | null | undefined,
  venueClose: string | null | undefined,
  calendar: VenueBusinessCalendar | null | undefined
): number[] {
  let open = hourFromTimeText(calendar?.openTime ?? venueOpen)
  let close = hourFromTimeText(calendar?.closeTime ?? venueClose)
  if (open == null || close == null) {
    open = 8
    close = 22
  }
  if (close <= open) return []
  return Array.from({ length: close - open }, (_, index) => open + index)
}

export function hourOf(iso: string | null | undefined): number | null {
  if (!iso) return null
  const parsed = new Date(iso)
  return Number.isNaN(parsed.getTime()) ? null : parsed.getHours()
}

/** 服务端返回的当日槽位按起始小时建索引；跨小时/重复起始小时以后写为准（服务端保证 1 小时切片）。 */
export function indexSlotsByHour(slots: InventorySlot[]): Map<number, InventorySlot> {
  const map = new Map<number, InventorySlot>()
  for (const slot of slots) {
    const hour = hourOf(slot.startTime)
    if (hour != null) map.set(hour, slot)
  }
  return map
}

export function spaceRowCells(hours: number[], slots: InventorySlot[]): SpaceCell[] {
  const index = indexSlotsByHour(slots)
  return hours.map((hour) => ({ hour, slot: index.get(hour) ?? null }))
}

export function slotPeriodLabel(hour: number): string {
  return `${pad(hour)}:00–${pad((hour + 1) % 24)}:00`
}

/** 记录时段标签：预览格式 "16:00–18:00"（U+2013）。 */
export function recordPeriodLabel(startTime: string | null | undefined, endTime: string | null | undefined): string {
  const from = parseHm(startTime)
  const to = parseHm(endTime)
  return from && to ? `${from}–${to}` : ''
}

function parseHm(value: string | null | undefined): string | null {
  if (!value) return null
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return null
  return `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
}

export function recordDateKey(value: string | null | undefined): string | null {
  if (!value) return null
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return null
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}`
}

/** 维护与不可售合并为同一份当日记录（预览 recordsView：维护在前，各自保留类型标签与状态）。 */
export function mergeRecords(
  maintenances: VenueMaintenance[],
  blocks: VenueUnavailableBlock[],
  spaceNames: Map<number, string>,
  date: string
): ResourceRecordRow[] {
  const maintenanceRows: ResourceRecordRow[] = maintenances
    .filter((item) => recordDateKey(item.startTime) === date)
    .map((item) => ({
      kind: 'maintenance' as const,
      id: item.id,
      spaceId: item.spaceId,
      spaceName: spaceNames.get(item.spaceId) ?? '',
      typeLabel: '维护',
      reason: item.reason,
      startTime: item.startTime,
      endTime: item.endTime,
      status: item.status,
      active: item.status === 'ACTIVE',
      bufferBefore: 0,
      bufferAfter: 0
    }))
  const blockRows: ResourceRecordRow[] = blocks
    .filter((item) => recordDateKey(item.startTime) === date)
    .map((item) => ({
      kind: 'block' as const,
      id: item.id,
      spaceId: item.spaceId ?? null,
      spaceName: item.spaceId != null ? spaceNames.get(item.spaceId) ?? '' : '全场馆',
      typeLabel: blockTypeText(item),
      reason: item.reasonText ?? '',
      startTime: item.startTime,
      endTime: item.endTime,
      status: item.status,
      active: item.status === 'ACTIVE',
      bufferBefore: item.bufferBeforeMinutes ?? 0,
      bufferAfter: item.bufferAfterMinutes ?? 0
    }))
  return [...maintenanceRows, ...blockRows]
}

function blockTypeText(block: VenueUnavailableBlock): string {
  // 与 adminShared.blockTypeLabel 同源：这里独立成小函数避免资源面引用整张标签表。
  const labels: Record<string, string> = {
    MANUAL_HOLD: '人工保留',
    MAINTENANCE: '维护',
    PRIVATE_EVENT: '包场',
    SETUP: '布场撤场',
    CLEANING: '清洁',
    CLOSURE: '闭馆',
    WEATHER: '天气'
  }
  return (block.blockType && labels[block.blockType]) || (block.blockType ?? '')
}

export function recordMetaLine(row: ResourceRecordRow): string {
  const parts = [
    recordPeriodLabel(row.startTime, row.endTime),
    `#${row.id}`,
    row.active ? '生效中' : '已取消'
  ].filter(Boolean)
  if (row.bufferBefore || row.bufferAfter) parts.push(`前后缓冲 ${row.bufferBefore}/${row.bufferAfter} 分钟`)
  return parts.join(' · ')
}

/** 预订方式副标（预览 capacity 第二格说明）。 */
export function ruleSubtitle(space: VenueSpace): string {
  const rule = space.resourceRule
  if (!rule) return '尚未配置预订规则'
  if (rule.bookingMode === 'EXCLUSIVE') return '每笔占用整空间 1 份'
  if (rule.bookingMode === 'CAPACITY') return `共享预订上限 ${rule.capacityLimit ?? '—'} 人`
  return `按当前 ${rule.seats.length} 个座位配置`
}

export function ruleModeLabel(space: VenueSpace): string {
  return space.resourceRule ? bookingModeLabel(space.resourceRule.bookingMode) : '未配置'
}

export function ruleVersionLabel(space: VenueSpace | null): string {
  const rule = space?.resourceRule
  return rule ? `V${rule.versionNo} · 原预约使用原规则快照` : '尚未配置预订规则'
}

export function spaceStatusText(space: VenueSpace): string {
  return spaceStatusLabel(space.status)
}

export function businessStatusText(calendar: VenueBusinessCalendar | null | undefined): string {
  return businessStatusLabel(calendar?.businessStatus ?? 'OPEN')
}

/** 当天营业安排时段行：闭馆/特殊处理按服务端状态与时间渲染，不伪造。 */
export function calendarHoursText(calendar: VenueBusinessCalendar | null | undefined): string {
  if (!calendar) return '按场馆常规营业时间'
  if (calendar.businessStatus === 'CLOSED') return '不开放预约'
  const open = (calendar.openTime ?? '').slice(0, 5)
  const close = (calendar.closeTime ?? '').slice(0, 5)
  return open && close ? `${open}–${close}` : '按场馆常规营业时间'
}

export function calendarReasonText(calendar: VenueBusinessCalendar | null | undefined): string {
  return calendar?.reasonText?.trim() || '按常规营业时间开放'
}

export interface SeatDraft {
  seatCode: string
  seatLabel: string
}

/** 座位草稿服务端同款校验（B2 预览文案逐字保留，服务端为准）。 */
export function seatDraftError(seats: SeatDraft[]): string | null {
  if (!seats.length) return '指定座位至少需要配置一个座位。'
  const codes = seats.map((seat) => seat.seatCode.trim())
  if (new Set(codes).size !== codes.length) return '座位编码不能重复，请核对后保存。'
  return null
}

/** 配置与当前版本是否完全一致（预览"本次配置与当前版本相同，无需创建新版本"提示）。 */
export function ruleUnchanged(space: VenueSpace, next: {
  resourceCategory: string
  bookingMode: string
  capacityLimit: number | null
  seats: SeatDraft[]
}): boolean {
  const rule = space.resourceRule
  if (!rule) return false
  const seatsSame =
    JSON.stringify(rule.seats.map((seat) => ({ seatCode: seat.seatCode, seatLabel: seat.seatLabel }))) ===
    JSON.stringify(next.seats.map((seat) => ({ seatCode: seat.seatCode.trim(), seatLabel: seat.seatLabel.trim() })))
  return (
    rule.resourceCategory === next.resourceCategory &&
    rule.bookingMode === next.bookingMode &&
    (rule.capacityLimit ?? null) === next.capacityLimit &&
    seatsSame
  )
}

/** 预览停用弹层说明：未来有效预约存在性以服务端级联取消语义为准，本句按状态渲染。 */
export function toggleSpaceNote(space: VenueSpace): string {
  return space.status === 'ACTIVE'
    ? '停用会取消该场地未来可履约预约。退款处理结果需在订单中继续核对。'
    : '启用后仍需重新读取库存；已有维护、不可售与预约记录分别核对。'
}

export function venueHoursLabel(open: string | null | undefined, close: string | null | undefined): string {
  const from = (open ?? '').slice(0, 5)
  const to = (close ?? '').slice(0, 5)
  return from && to ? `${from}–${to}` : '—'
}

/** 服务端错误消息读取（409/422 的业务提示逐字展示）。 */
export function readableError(error: unknown, fallback: string): string {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (responseMessage) return responseMessage
  return error instanceof Error && error.message ? error.message : fallback
}
