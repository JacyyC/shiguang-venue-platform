import http, { type HttpRequestConfig } from './http'
import type { MenuNode, UserProfile } from './types'

export interface AuthResponse {
  token: string
  expiresIn: number
  user: UserProfile
  roles: string[]
  permissions: string[]
}

export interface AuthorizationSnapshot {
  roles: string[]
  permissions: string[]
}

export const authApi = {
  login: (payload: { username: string; password: string }) => http.post<AuthResponse>('/auth/login', payload),
  register: (payload: { username: string; password: string; nickname?: string; phone?: string; email?: string }) =>
    http.post<void>('/auth/register', payload),
  forgotPassword: (payload: { username: string }) => http.post<void>('/auth/forgot-password', payload),
  resetPassword: (payload: { token: string; password: string }) => http.post<void>('/auth/reset-password', payload),
  logout: () => http.post<void>('/auth/logout'),
  me: (config?: HttpRequestConfig) => http.get<UserProfile>('/users/me', config),
  updateProfile: (payload: Record<string, unknown>, config?: HttpRequestConfig) => http.put<UserProfile>('/users/me', payload, config),
  authorization: () => http.get<AuthorizationSnapshot>('/users/me/authorization'),
  menus: () => http.get<MenuNode[]>('/users/me/menus'),
  changePassword: (payload: { oldPassword: string; newPassword: string }, config?: HttpRequestConfig) => http.put<void>('/users/me/password', payload, config)
}
