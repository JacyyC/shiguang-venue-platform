import { describe, expect, it } from 'vitest'
import type { ManagedRole, PermissionMenu, RoleOption, SystemConfig, SystemUser } from '@/api/system-types'
import {
  accessSummary,
  configIsBoolean,
  configValueText,
  emailText,
  fullDate,
  fullDateTime,
  groupPermissions,
  hmTime,
  initialTabFromRoute,
  logAttention,
  logDay,
  logStatusLabel,
  maskIp,
  matchUserQuery,
  roleBadge,
  roleListLabel,
  roleLabelOf,
  userDisplayName,
  userStatusOf,
  venueOptions
} from './platformShared'

const user = (overrides: Partial<SystemUser> = {}): SystemUser =>
  ({ id: 1, username: 'lin.cheng', nickname: '林澄', status: 'ACTIVE', roleId: 2, email: 'lin.cheng@example.test', ...overrides })

const roleOption = (overrides: Partial<RoleOption> = {}): RoleOption =>
  ({ id: 2, name: '场馆管理员', key: 'VENUE_ADMIN', dataScope: 'VENUE', ...overrides })

describe('initialTabFromRoute', () => {
  it('hash 深链优先，其次路由名，缺省看板', () => {
    expect(initialTabFromRoute('SystemAdminDashboard', '#users')).toBe('users')
    expect(initialTabFromRoute('SystemAdminDashboard', '')).toBe('dashboard')
    expect(initialTabFromRoute('SystemUsers', '')).toBe('users')
    expect(initialTabFromRoute('SystemRoles', '')).toBe('roles')
    expect(initialTabFromRoute('SystemConfigs', '')).toBe('configs')
    expect(initialTabFromRoute('SystemLogs', '')).toBe('logs')
    expect(initialTabFromRoute(undefined, '#bad')).toBe('dashboard')
  })
})

describe('账号展示', () => {
  it('昵称回退用户名；角色与状态映射', () => {
    expect(userDisplayName(user())).toBe('林澄')
    expect(userDisplayName(user({ nickname: '  ' }))).toBe('lin.cheng')
    expect(roleLabelOf(user(), [roleOption()])).toBe('场馆管理员')
    expect(roleLabelOf(user({ roleId: 99 }), [roleOption()])).toBe('未分配角色')
    expect(userStatusOf(user())).toBe('正常')
    expect(userStatusOf(user({ status: 'DISABLED' }))).toBe('停用')
  })

  it('搜索匹配昵称与账号', () => {
    expect(matchUserQuery(user(), '林澄')).toBe(true)
    expect(matchUserQuery(user(), 'lin.cheng')).toBe(true)
    expect(matchUserQuery(user(), '周远')).toBe(false)
    expect(matchUserQuery(user(), '')).toBe(true)
  })

  it('邮箱掩码复用共享层 l***@ 形态；无邮箱给本面缺省', () => {
    expect(emailText('lin.cheng@example.test')).toBe('l***@example.test')
    expect(emailText(null)).toBe('—')
    expect(emailText(undefined)).toBe('—')
    expect(emailText('no-at')).toBe('no-at')
  })

  it('时间行与日期行格式', () => {
    expect(fullDateTime('2026-09-12T09:08:00')).toBe('9 月 12 日 09:08')
    expect(fullDate('2026-08-16T00:00:00')).toBe('2026 年 8 月 16 日')
    expect(hmTime('2026-09-12T10:12:00')).toBe('10:12')
    expect(logDay('2026-09-12T10:12:00')).toBe('09/12')
    expect(logDay(null)).toBe('—')
  })
})

describe('叶子权限分组', () => {
  const menus: PermissionMenu[] = [
    { id: 20, name: '系统后台', parentId: null, permission: 'system:view', menuType: 'MENU', visible: 'Y' },
    { id: 21, name: '用户管理', parentId: 20, permission: 'system:user:manage', menuType: 'MENU', visible: 'Y' },
    { id: 54, name: 'Provider 观测', parentId: 20, permission: 'provider:observe', menuType: 'MENU', visible: 'Y' },
    { id: 55, name: 'Provider 刷新', parentId: 54, permission: 'provider:refresh', menuType: 'BUTTON', visible: 'Y' },
    { id: 30, name: '运营日历', parentId: 20, permission: 'venue:inventory:view', menuType: 'MENU', visible: 'Y' }
  ]

  it('按根祖先分组，BUTTON 型权限点同样可授予（刷新分权需要）', () => {
    const groups = groupPermissions(menus)
    expect(groups).toHaveLength(1)
    expect(groups[0].rootName).toBe('系统后台')
    expect(groups[0].items.map((item) => item.permission)).toEqual([
      'system:view',
      'system:user:manage',
      'provider:observe',
      'provider:refresh',
      'venue:inventory:view'
    ])
  })

  it('空权限菜单不产生分组', () => {
    expect(groupPermissions([])).toEqual([])
  })
})

describe('参数与日志', () => {
  it('布尔参数按 valueType 显示 开启/关闭，数值原样', () => {
    const config = (value: string, valueType = 'NUMBER'): SystemConfig =>
      ({ id: 1, configKey: 'k', configValue: value, configName: '参数', description: 'd', valueType })
    expect(configValueText(config('true', 'BOOLEAN'))).toBe('开启')
    expect(configValueText(config('false', 'BOOLEAN'))).toBe('关闭')
    expect(configValueText(config('30'))).toBe('30')
    expect(configIsBoolean(config('false', 'BOOLEAN'))).toBe(true)
    expect(configIsBoolean(config('30'))).toBe(false)
  })

  it('日志徽标只对失败类结果告警', () => {
    expect(logAttention('FAIL')).toBe(true)
    expect(logAttention('ERROR')).toBe(true)
    expect(logAttention('SUCCESS')).toBe(false)
    expect(logAttention(null)).toBe(false)
  })

  it('日志结果按真实枚举出中文标签，未知值如实展示', () => {
    expect(logStatusLabel('SUCCESS')).toBe('成功')
    expect(logStatusLabel('FAIL')).toBe('失败')
    expect(logStatusLabel('fail')).toBe('失败')
    expect(logStatusLabel('TIMEOUT')).toBe('TIMEOUT')
    expect(logStatusLabel(null)).toBe('—')
  })

  it('地址脱敏保留前段掩末段（IPv4 与 IPv6）', () => {
    expect(maskIp('127.0.0.1')).toBe('127.0.0.***')
    expect(maskIp('0:0:0:0:0:0:0:1')).toBe('0:0:0:0:0:0:0:***')
    expect(maskIp(null)).toBe('—')
  })
})

describe('授权核对', () => {
  it('角色与场馆摘要（空值给预览措辞）', () => {
    expect(accessSummary(['场馆管理员'], ['北岸生活场馆'])).toEqual({
      roles: '场馆管理员',
      venues: '北岸生活场馆'
    })
    expect(accessSummary([], [])).toEqual({ roles: '无角色', venues: '没有场馆授权' })
  })

  it('场馆复选选项带区域说明', () => {
    expect(venueOptions([
      { id: 101, name: '北岸生活场馆', area: '北岸片区', status: 'ACTIVE' },
      { id: 102, name: '东园运动中心', status: 'ACTIVE' }
    ] as never)).toEqual([
      { id: 101, name: '北岸生活场馆', note: '所在区域：北岸片区' },
      { id: 102, name: '东园运动中心', note: '平台登记场馆' }
    ])
  })

  it('内置角色两种措辞：详情徽标与列表行', () => {
    const role = (builtIn: boolean): ManagedRole =>
      ({ id: 2, name: '场馆管理员', key: 'VENUE_ADMIN', dataScope: 'VENUE', status: 'ACTIVE', builtIn })
    expect(roleBadge(role(true))).toBe('内置只读')
    expect(roleBadge(role(false))).toBe('动态角色')
    expect(roleListLabel(role(true))).toBe('内置角色 · 只读')
    expect(roleListLabel(role(false))).toBe('动态角色 · 可维护')
  })
})
