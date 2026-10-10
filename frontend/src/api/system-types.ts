export interface SystemConfig {
  id: number
  configKey: string
  configValue: string
  configName: string
  description?: string
  valueType?: string
  editable?: string
}

export interface SystemUser {
  id: number
  username: string
  nickname?: string
  phone?: string
  email?: string
  avatar?: string
  status: string
  roleId?: number
  lastLoginTime?: string
  createTime?: string
  updateTime?: string
}

export interface RoleOption {
  id: number
  name: string
  key: string
  dataScope: string
}

export interface ManagedRole {
  id: number
  name: string
  key: string
  dataScope: string
  status: string
  builtIn: boolean
}

export interface PermissionMenu {
  id: number
  name: string
  parentId: number | null
  path?: string | null
  permission: string
  menuType: string
  visible: string
}

export interface LoginLog {
  id: number
  username?: string
  ip?: string
  status?: string
  message?: string
  createTime?: string
}

export interface OperationLog {
  id: number
  username?: string
  ip?: string
  module?: string
  action?: string
  resultStatus?: string
  errorMessage?: string
  createTime?: string
}
