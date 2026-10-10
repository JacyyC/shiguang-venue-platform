/**
 * 平台面共享逻辑（SHIGUANG-BATCH-02-REFACTOR-20260913 S06）。
 * 预览 ref/c/platform.js 的展示推导改为服务端事实驱动：
 * 账号/角色/权限/参数/日志全部来自 systemApi；合成示例（观测值班员/固定 4 账号）不迁入。
 */
import type { ManagedRole, PermissionMenu, RoleOption, SystemConfig, SystemUser } from '@/api/system-types'
import type { VenueListItem } from '@/api/types'
import { formatHm, formatMonthDaySlash, formatStampDateTime, maskEmail, userStatusLabel } from '../adminShared'

export type PlatformTab = 'dashboard' | 'users' | 'roles' | 'configs' | 'logs'

/** 旧路由 → 初始页签（contracts.md §3.2）；hash 深链同预览 #users 等。 */
export function initialTabFromRoute(name: string | symbol | null | undefined, hash: string): PlatformTab {
  const hashTab = hash.replace('#', '') as PlatformTab
  if (['dashboard', 'users', 'roles', 'configs', 'logs'].includes(hashTab)) return hashTab
  if (name === 'SystemUsers') return 'users'
  if (name === 'SystemRoles') return 'roles'
  if (name === 'SystemConfigs') return 'configs'
  if (name === 'SystemLogs') return 'logs'
  return 'dashboard'
}

export const PLATFORM_TABS: Array<{ key: PlatformTab; name: string; note: string }> = [
  { key: 'dashboard', name: '平台看板', note: '概览与治理去向' },
  { key: 'users', name: '账号与授权', note: '账号 · 角色 · 场馆' },
  { key: 'roles', name: '角色与叶子权限', note: '内置只读 · 动态管理' },
  { key: 'configs', name: '平台参数', note: '现有规则与参数值' },
  { key: 'logs', name: '登录与操作日志', note: '只读审计记录' }
]

export function userDisplayName(user: SystemUser): string {
  return user.nickname?.trim() || user.username
}

export function roleLabelOf(user: SystemUser, roles: RoleOption[]): string {
  const role = roles.find((item) => item.id === user.roleId)
  return role?.name ?? '未分配角色'
}

/** 账号状态标签复用 S03 共享枚举表（USER_STATUS_LABELS）。 */
export function userStatusOf(user: SystemUser): string {
  return userStatusLabel(user.status)
}

export function matchUserQuery(user: SystemUser, query: string): boolean {
  if (!query) return true
  return `${userDisplayName(user)}${user.username}`.includes(query)
}

/** 账号详情行：编号/邮箱（掩码）/最近登录/注册时间（预览 data-pairs 结构）。 */
export interface UserFactPair {
  label: string
  value: string
}

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

function parse(value: string | null | undefined): Date | null {
  if (!value) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

export function fullDate(value: string | null | undefined): string {
  const parsed = parse(value)
  if (!parsed) return '—'
  return `${parsed.getFullYear()} 年 ${parsed.getMonth() + 1} 月 ${parsed.getDate()} 日`
}

export function fullDateTime(value: string | null | undefined): string {
  const parsed = parse(value)
  if (!parsed) return '—'
  return `${parsed.getMonth() + 1} 月 ${parsed.getDate()} 日 ${pad2(parsed.getHours())}:${pad2(parsed.getMinutes())}`
}

/** 邮箱掩码复用 S03 共享层；本面无邮箱时按缺省显示 —（编辑弹层仍用原值）。 */
export function emailText(email: string | null | undefined): string {
  return maskEmail(email) || '—'
}

/** 时间行 HH:mm：复用 S03 共享 formatHm，本面缺省显示 —。 */
export function hmTime(value: string | null | undefined): string {
  return formatHm(value) || '—'
}

/** 日志时间格首行 MM/DD（次行用 hmTime）。 */
export function logDay(value: string | null | undefined): string {
  return formatMonthDaySlash(value) || '—'
}

/** 详情弹层的"发生日期"：真实记录含分钟，按 `2026-09-13 21:48` 形态完整给出。 */
export function isoDateTime(value: string | null | undefined): string {
  return formatStampDateTime(value) || '—'
}

/**
 * 叶子权限分组：按根祖先菜单名分组（服务端已滤除不可委派的 system:user:manage/system:role:manage）。
 * 预览的两个组名（平台管理 / Provider 观测）在真实清单中由根菜单承担。
 */
export interface PermissionGroup {
  rootName: string
  items: Array<{ id: number; name: string; permission: string }>
}

export function groupPermissions(menus: PermissionMenu[]): PermissionGroup[] {
  const byId = new Map<number, PermissionMenu>()
  for (const menu of menus) byId.set(menu.id, menu)
  const rootNameOf = (menu: PermissionMenu): string => {
    let current: PermissionMenu | undefined = menu
    const guard = new Set<number>()
    while (current?.parentId != null && byId.has(current.parentId) && !guard.has(current.parentId)) {
      guard.add(current.parentId)
      current = byId.get(current.parentId)
    }
    return current?.name ?? '其他权限'
  }
  const groups = new Map<string, PermissionGroup>()
  for (const menu of menus) {
    if (!menu.permission) continue
    const rootName = rootNameOf(menu)
    let group = groups.get(rootName)
    if (!group) {
      group = { rootName, items: [] }
      groups.set(rootName, group)
    }
    group.items.push({ id: menu.id, name: menu.name, permission: menu.permission })
  }
  return [...groups.values()]
}

/** 参数值展示：BOOLEAN 类型显示 关闭/开启，其余按服务端登记值原样展示。 */
export function configValueText(config: SystemConfig): string {
  if (!configIsBoolean(config)) return config.configValue
  return config.configValue === 'true' ? '开启' : config.configValue === 'false' ? '关闭' : config.configValue
}

/** 布尔参数以服务端 valueType 为准（sys_config.value_type='BOOLEAN'）。 */
export function configIsBoolean(config: SystemConfig): boolean {
  return (config.valueType ?? '').trim().toUpperCase() === 'BOOLEAN'
}

/** 场馆授权复选：可管理场馆清单来自公开场馆列表（平台侧全量）。 */
export function venueOptions(venues: VenueListItem[]): Array<{ id: number; name: string; note: string }> {
  return venues.map((venue) => ({
    id: venue.id,
    name: venue.name,
    note: venue.area?.trim() ? `所在区域：${venue.area.trim()}` : '平台登记场馆'
  }))
}

/** 授权保存的核对摘要（预览"核对账号授权"弹层）。 */
export function accessSummary(roleNames: string[], venueNames: string[]): { roles: string; venues: string } {
  return {
    roles: roleNames.length ? roleNames.join('、') : '无角色',
    venues: venueNames.length ? venueNames.join('、') : '没有场馆授权'
  }
}

/** 服务端错误消息读取。 */
export function readableError(error: unknown, fallback: string): string {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (responseMessage) return responseMessage
  return error instanceof Error && error.message ? error.message : fallback
}

/** 日志徽标 attention 判定（失败类）。 */
export function logAttention(status: string | null | undefined): boolean {
  const value = (status ?? '').toUpperCase()
  return ['FAIL', 'FAILED', 'FAILURE', 'ERROR'].includes(value)
}

/** 日志结果徽标中文标签（真实枚举 SUCCESS/FAIL；未知值如实展示）。 */
export function logStatusLabel(status: string | null | undefined): string {
  const value = (status ?? '').toUpperCase()
  if (value === 'SUCCESS') return '成功'
  if (['FAIL', 'FAILED', 'FAILURE', 'ERROR'].includes(value)) return '失败'
  return status || '—'
}

/**
 * 地址脱敏：服务端 sys_*_log.ip 存原始值，展示按预览形态保留前段、掩末段
 * （IPv4 127.0.0.***；IPv6 0:0:0:0:0:0:***）。
 */
export function maskIp(ip: string | null | undefined): string {
  const value = (ip ?? '').trim()
  if (!value) return '—'
  const separator = value.includes(':') ? ':' : '.'
  const parts = value.split(separator)
  if (parts.length < 2) return '已脱敏'
  return `${parts.slice(0, -1).join(separator)}${separator}***`
}

/** 内置角色只读提示（服务端 requireMutableRole 同语义）。 */
export function roleBadge(role: ManagedRole): string {
  return role.builtIn ? '内置只读' : '动态角色'
}

/** 角色列表行标签（预览列表用完整措辞，与详情徽标区分）。 */
export function roleListLabel(role: ManagedRole): string {
  return role.builtIn ? '内置角色 · 只读' : '动态角色 · 可维护'
}
