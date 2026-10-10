/**
 * 现场与资产面共享逻辑（SHIGUANG-BATCH-03-REFACTOR-20260914 S04）。
 *
 * 预览 ref/f/site-ops.js 的合成数据与本地拦截改为真实服务端事实驱动：行=班次×任务×交接×异常×资产
 * ×巡检×维修×生命周期事件的真实关联；固定示例行、写死时刻与假成功动作不迁入。
 * 状态中文取服务端字典 sys_dict_data（contracts.md §1.3），字典与代码写入集不一致时以写入集为准。
 */

import type { AssetInspectionRecord, AssetItem, AssetLifecycleEvent, AssetRepairRecord } from '@/api/assets'
import type { SiteIncidentLog, SiteShift, SiteShiftHandover, SiteTask } from '@/api/siteOps'

export type SiteOpsTab = 'shift' | 'incidents' | 'assets'

/** 旧深链决定初始页签：/venue-admin/assets 进设备档案，其余进当班日志（contracts.md §1.2）。 */
export function initialTabFromRoute(name: unknown): SiteOpsTab {
  return name === 'VenueAdminAssets' ? 'assets' : 'shift'
}

/** 开放任务口径与服务端一致：SiteOpsService.unfinishedTaskStatuses()。 */
export const OPEN_TASK_STATUSES = ['TODO', 'IN_PROGRESS', 'PENDING']
/** 可开始的任务态：TODO 或迁移兼容读态 PENDING。 */
export const STARTABLE_TASK_STATUSES = ['TODO', 'PENDING']
/** 终态任务不可改写核心归属：DONE / EXCEPTION / CANCELED。 */
export const TERMINAL_TASK_STATUSES = ['DONE', 'EXCEPTION', 'CANCELED']
/** 未解决异常口径与服务端签退前置一致：OPEN 或 IN_PROGRESS。 */
export const OPEN_INCIDENT_STATUSES = ['OPEN', 'IN_PROGRESS']
/** 维修开放单口径：AssetLifecycleService 的 hasOpenRepair。 */
export const OPEN_REPAIR_STATUSES = ['REQUESTED', 'IN_PROGRESS']

export const SHIFT_STATUS_LABELS: Record<string, string> = {
  PLANNED: '已计划',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  CANCELED: '已取消'
}

export const HANDOVER_STATUS_LABELS: Record<string, string> = {
  NONE: '无需交接',
  PENDING: '待交接',
  SUBMITTED: '已提交',
  ACCEPTED: '已接收'
}

export const TASK_STATUS_LABELS: Record<string, string> = {
  TODO: '待处理',
  PENDING: '待处理',
  IN_PROGRESS: '进行中',
  DONE: '已完成',
  EXCEPTION: '异常',
  CANCELED: '已取消'
}

export const TASK_TYPE_LABELS: Record<string, string> = {
  CHECKIN_SUPPORT: '签到支持',
  CLEANING: '清洁',
  INSPECTION: '巡检',
  HANDOVER: '交接',
  INCIDENT_FOLLOW_UP: '异常跟进',
  ASSET_REPAIR: '资产维修'
}

export const PRIORITY_LABELS: Record<string, string> = {
  LOW: '低',
  NORMAL: '普通',
  MEDIUM: '中',
  HIGH: '高'
}

export const INCIDENT_TYPE_LABELS: Record<string, string> = {
  RESERVATION_SITE_ISSUE: '预约现场问题',
  SAFETY: '安全事件',
  ASSET_FAILURE: '资产故障',
  COMPLAINT_SIGNAL: '客户投诉预警'
}

export const SEVERITY_LABELS: Record<string, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  CRITICAL: '紧急'
}

export const INCIDENT_STATUS_LABELS: Record<string, string> = {
  OPEN: '待处理',
  IN_PROGRESS: '处理中',
  RESOLVED: '已解决',
  CLOSED: '已关闭'
}

export const ASSET_STATUS_LABELS: Record<string, string> = {
  STOCKED: '入库',
  IN_USE: '在用',
  INSPECTION_DUE: '待巡检',
  REPAIRING: '维修中',
  OUT_OF_SERVICE: '停用',
  RETIRED: '已报废'
}

export const INSPECTION_RESULT_LABELS: Record<string, string> = {
  PASS: '通过',
  ATTENTION: '需关注',
  REPAIR_REQUIRED: '需维修'
}

export const RISK_LEVEL_LABELS: Record<string, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高'
}

export const REPAIR_STATUS_LABELS: Record<string, string> = {
  REQUESTED: '已请求',
  IN_PROGRESS: '维修中',
  COMPLETED: '已完成',
  CANCELED: '已取消'
}

export const LIFECYCLE_EVENT_LABELS: Record<string, string> = {
  STOCKED: '入库',
  STATUS_CHANGED: '状态变更',
  INSPECTION: '巡检',
  UNAVAILABLE_LINKED: '不可售联动',
  RETIRED: '报废',
  REPAIR_REQUESTED: '维修申请',
  REPAIR_STARTED: '维修开始',
  REPAIR_COMPLETED: '维修完成',
  REPAIR_CANCELED: '维修取消'
}

/** 岗位编码无字典，沿用旧正文四项（contracts.md B3-T5）。 */
export const ROLE_CODE_LABELS: Record<string, string> = {
  FRONT_DESK: '前台',
  FLOOR: '巡场',
  SECURITY: '安保',
  FACILITY: '设备'
}

function labelize(map: Record<string, string>, value: string | null | undefined): string {
  if (!value) return '—'
  return map[value] ?? value
}

export const shiftStatusLabel = (value?: string | null) => labelize(SHIFT_STATUS_LABELS, value)
export const handoverStatusLabel = (value?: string | null) => labelize(HANDOVER_STATUS_LABELS, value)
export const taskStatusLabel = (value?: string | null) => labelize(TASK_STATUS_LABELS, value)
export const taskTypeLabel = (value?: string | null) => labelize(TASK_TYPE_LABELS, value)
export const priorityLabel = (value?: string | null) => labelize(PRIORITY_LABELS, value)
export const incidentTypeLabel = (value?: string | null) => labelize(INCIDENT_TYPE_LABELS, value)
export const severityLabel = (value?: string | null) => labelize(SEVERITY_LABELS, value)
export const incidentStatusLabel = (value?: string | null) => labelize(INCIDENT_STATUS_LABELS, value)
export const assetStatusLabel = (value?: string | null) => labelize(ASSET_STATUS_LABELS, value)
export const inspectionResultLabel = (value?: string | null) => labelize(INSPECTION_RESULT_LABELS, value)
export const riskLevelLabel = (value?: string | null) => labelize(RISK_LEVEL_LABELS, value)
export const repairStatusLabel = (value?: string | null) => labelize(REPAIR_STATUS_LABELS, value)
export const lifecycleEventLabel = (value?: string | null) => labelize(LIFECYCLE_EVENT_LABELS, value)
export const roleCodeLabel = (value?: string | null) => labelize(ROLE_CODE_LABELS, value)

function parsed(value?: string | null): Date | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

const pad = (value: number): string => String(value).padStart(2, '0')

/** 服务端 LocalDateTime 无时区，按本地时间渲染 HH:MM。 */
export function hourMinute(value?: string | null): string {
  const date = parsed(value)
  return date ? `${pad(date.getHours())}:${pad(date.getMinutes())}` : ''
}

/** 班次时段："16:00—22:00"（跨日只显示端点时刻）。 */
export function shiftTimeRange(shift: SiteShift): string {
  const start = hourMinute(shift.plannedStartTime)
  const end = hourMinute(shift.plannedEndTime)
  if (!start && !end) return '未安排时间'
  return `${start || '—'}—${end || '—'}`
}

/** 本地运营日 YYYY-MM-DD，用于把「当日」范围作为服务端 date 参数（B3-T2）。 */
export function dayKey(value: Date = new Date()): string {
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
}

/** 事件时间线一行："09月13日 18:10 · 文案"。 */
export function eventLine(value: string | null | undefined, text: string): string {
  const date = parsed(value)
  if (!date) return text
  const stamp = `${pad(date.getMonth() + 1)}月${pad(date.getDate())}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`
  return text ? `${stamp} · ${text}` : stamp
}

export function openTasks(tasks: SiteTask[]): SiteTask[] {
  return tasks.filter((task) => OPEN_TASK_STATUSES.includes(task.status ?? ''))
}

/** 任务序列按当班处理顺序阅读：开放任务在前，组内按计划完成时间升序（无时间排最后），再按 ID 稳定兜底。 */
export function shiftTaskOrder(tasks: SiteTask[]): SiteTask[] {
  const rank = (task: SiteTask) => (OPEN_TASK_STATUSES.includes(task.status ?? '') ? 0 : 1)
  const due = (task: SiteTask) => (task.dueTime ? String(task.dueTime) : '9999-12-31T23:59:59')
  return [...tasks].sort((a, b) => rank(a) - rank(b) || due(a).localeCompare(due(b)) || (a.id ?? 0) - (b.id ?? 0))
}

export function openIncidents(incidents: SiteIncidentLog[]): SiteIncidentLog[] {
  return incidents.filter((incident) => OPEN_INCIDENT_STATUSES.includes(incident.status ?? ''))
}

export function isStartableTask(task: SiteTask): boolean {
  return STARTABLE_TASK_STATUSES.includes(task.status ?? '')
}

export function isTerminalTask(task: SiteTask): boolean {
  return TERMINAL_TASK_STATUSES.includes(task.status ?? '')
}

/** 预览的三选一处理结果分派到真实两个端点（B3-T7）。 */
export function taskResultEndpoint(result: string): 'complete' | 'cancel' {
  return result === 'CANCELED' ? 'cancel' : 'complete'
}

/** 交接侧栏只看源班次的交接；接收动作只允许 SUBMITTED。 */
export function handoverForShift(handovers: SiteShiftHandover[], shiftId: number | undefined): SiteShiftHandover | null {
  if (shiftId == null) return null
  return handovers.find((item) => item.fromShiftId === shiftId) ?? null
}

/** 已计划班次优先签到，进行中优先签退，已完成只读。 */
export function shiftAction(shift: SiteShift): 'clock-in' | 'clock-out' | 'none' {
  if (shift.status === 'PLANNED') return 'clock-in'
  if (shift.status === 'IN_PROGRESS') return 'clock-out'
  return 'none'
}

/** 资产状态不可变终态：RETIRED（AssetLifecycleService.requireMutableAsset）。 */
export function isMutableAsset(asset: AssetItem | null | undefined): boolean {
  return !!asset && asset.status !== 'RETIRED'
}

/** 手动状态变更只提供预览的四项目标态（B3-T12）。 */
export const ASSET_TARGET_STATUSES = ['IN_USE', 'OUT_OF_SERVICE', 'INSPECTION_DUE', 'RETIRED']

/** 仅停用或维修中支持不可售联动（服务端 400「仅维修中或停用状态支持不可售联动」）。 */
export function supportsBlockLink(toStatus: string): boolean {
  return toStatus === 'OUT_OF_SERVICE' || toStatus === 'REPAIRING'
}

/** 只有恢复使用时可释放资产关联块（服务端三条件之一，前端保留为校验）。 */
export function releasesBlockOn(toStatus: string): boolean {
  return toStatus === 'IN_USE'
}

/** 不可售时间窗校验：勾选联动才要求成对且有序。 */
export function blockWindowError(checked: boolean, start: string, end: string): string {
  if (!checked) return ''
  if (!start || !end) return '请填写不可售开始和结束时间。'
  if (start >= end) return '不可售开始时间必须早于结束时间。'
  return ''
}

/** 设备档案的开放维修单（同资产、状态为 REQUESTED/IN_PROGRESS）。 */
export function openRepairOf(repairs: AssetRepairRecord[], assetId: number | undefined): AssetRepairRecord | null {
  if (assetId == null) return null
  return repairs.find((item) => item.assetId === assetId && OPEN_REPAIR_STATUSES.includes(item.status ?? '')) ?? null
}

/** 设备档案的最近一次巡检（按 inspectedAt 倒序取首条）。 */
export function latestInspectionOf(
  inspections: AssetInspectionRecord[],
  assetId: number | undefined
): AssetInspectionRecord | null {
  if (assetId == null) return null
  const rows = inspections
    .filter((item) => item.assetId === assetId)
    .sort((left, right) => String(right.inspectedAt ?? '').localeCompare(String(left.inspectedAt ?? '')))
  return rows[0] ?? null
}

/** 维修记录区按钮：REQUESTED 可开始/取消，IN_PROGRESS 可完成/取消，终态只读。 */
export function repairActions(status?: string | null): { start: boolean; complete: boolean; cancel: boolean } {
  return {
    start: status === 'REQUESTED',
    complete: status === 'IN_PROGRESS',
    cancel: status === 'REQUESTED' || status === 'IN_PROGRESS'
  }
}

/** 任务列表的「说明」行只渲染真实 completionNote（B3-T6）。 */
export function taskNote(task: SiteTask): string {
  return task.completionNote?.trim() || ''
}

/** 异常列表的「结果」行只渲染真实 resolution（未解决时为空）。 */
export function incidentResolution(incident: SiteIncidentLog): string {
  return incident.resolution?.trim() || ''
}

/** 资产生命周期一行中文归纳：事件名 + 状态迁移 + 原因。 */
export function lifecycleLine(event: AssetLifecycleEvent): string {
  const parts = [lifecycleEventLabel(event.eventType)]
  if (event.fromStatus || event.toStatus) {
    parts.push(`${assetStatusLabel(event.fromStatus ?? '')} → ${assetStatusLabel(event.toStatus ?? '')}`)
  }
  if (event.reasonText) parts.push(event.reasonText)
  return parts.join(' · ')
}

/** 关联库存块只显示真实关联，不推算不可售时段（B3-T10）。 */
export function blockLinkText(asset: AssetItem): string {
  return asset.currentUnavailableBlockId ? `关联库存块 #${asset.currentUnavailableBlockId}` : '无关联库存块'
}

/** 责任人可空：真实服务端允许不指定负责人。 */
export function responsibleText(asset: AssetItem): string {
  return asset.responsibleUserId ? `负责人 #${asset.responsibleUserId}` : '未指定'
}

/** 台账已存在的资产类型并入下拉，避免将真实值改写成候选项（B3-T11）。 */
export function assetTypeOptions(assets: AssetItem[]): Array<{ value: string; label: string }> {
  const base: Array<{ value: string; label: string }> = [
    { value: 'EQUIPMENT', label: '设备' },
    { value: 'LIGHTING', label: '照明' },
    { value: 'CLEANING', label: '清洁' }
  ]
  const seen = new Set(base.map((item) => item.value))
  for (const asset of assets) {
    const value = asset.assetType?.trim()
    if (value && !seen.has(value)) {
      seen.add(value)
      base.push({ value, label: value })
    }
  }
  return base
}

/** 场地名映射：资产与任务只带 spaceId，场地名经公开场地接口补齐（B3-T4 同源，登录即可读）。 */
export function spaceName(names: Map<number, string>, spaceId: number | undefined, fallback = '未绑定场地'): string {
  if (spaceId == null) return fallback
  return names.get(spaceId) ?? `场地 #${spaceId}`
}

/** 服务端原文优先的错误归纳（与第二批 readableError 同口径）。 */
export function readableError(error: unknown, fallback: string): string {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message && message.trim() ? message : fallback
}
