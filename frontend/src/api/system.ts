import http, { type HttpRequestConfig, type PageResult } from './http'
import type { LoginLog, ManagedRole, OperationLog, PermissionMenu, RoleOption, SystemConfig, SystemUser } from './system-types'

export interface DictData {
  label: string
  value: string
}

/** 后台拾光四面按 contracts.md §6 统一传 isolated 配置：403 不整页跳转、409 不全局弹窗，由面内降级态承接。 */
export const systemApi = {
  users: (params: Record<string, unknown>, config?: HttpRequestConfig) => http.get<PageResult<SystemUser>>('/system/users', { params, ...config }),
  roles: (config?: HttpRequestConfig) => http.get<RoleOption[]>('/system/roles', config),
  updateUser: (id: number, payload: Pick<SystemUser, 'username' | 'nickname' | 'phone' | 'email' | 'status'>, config?: HttpRequestConfig) =>
    http.put<SystemUser>(`/system/users/${id}`, payload, config),
  updateUserStatus: (id: number, status: string, config?: HttpRequestConfig) => http.put<void>(`/system/users/${id}/status`, { status }, config),
  assignRoles: (id: number, roleIds: number[], config?: HttpRequestConfig) => http.put<void>(`/system/users/${id}/roles`, roleIds, config),
  userRoles: (id: number, config?: HttpRequestConfig) => http.get<number[]>(`/system/users/${id}/roles`, config),
  venueAccess: (id: number, config?: HttpRequestConfig) => http.get<number[]>(`/system/users/${id}/venue-access`, config),
  updateVenueAccess: (id: number, venueIds: number[], config?: HttpRequestConfig) => http.put<void>(`/system/users/${id}/venue-access`, { venueIds }, config),
  resetUserPassword: (id: number, password: string, config?: HttpRequestConfig) => http.put<void>(`/system/users/${id}/password`, { password }, config),
  deleteUser: (id: number, config?: HttpRequestConfig) => http.delete<void>(`/system/users/${id}`, config),
  configs: (config?: HttpRequestConfig) => http.get<SystemConfig[]>('/system/configs', config),
  updateConfig: (key: string, value: string, config?: HttpRequestConfig) => http.put<void>(`/system/configs/${key}`, { value }, config),
  dict: (type: string, config?: HttpRequestConfig) => http.get<DictData[]>(`/system/dicts/${type}`, config),
  managedRoles: (config?: HttpRequestConfig) => http.get<ManagedRole[]>('/system/role-management/roles', config),
  permissionMenus: (config?: HttpRequestConfig) => http.get<PermissionMenu[]>('/system/role-management/permissions', config),
  rolePermissions: (id: number, config?: HttpRequestConfig) => http.get<number[]>(`/system/role-management/roles/${id}/permissions`, config),
  createManagedRole: (payload: { roleName: string; roleKey: string }, config?: HttpRequestConfig) =>
    http.post<ManagedRole>('/system/role-management/roles', payload, config),
  replaceRolePermissions: (id: number, menuIds: number[], config?: HttpRequestConfig) =>
    http.put<void>(`/system/role-management/roles/${id}/permissions`, { menuIds }, config),
  loginLogs: (params: Record<string, unknown>, config?: HttpRequestConfig) => http.get<PageResult<LoginLog>>('/system/logs/login', { params, ...config }),
  operationLogs: (params: Record<string, unknown>, config?: HttpRequestConfig) => http.get<PageResult<OperationLog>>('/system/logs/operation', { params, ...config })
}
