import { defineStore } from 'pinia'
import { authApi, type AuthResponse } from '@/api/auth'
import { clearBookingDraft } from '@/stores/reservation'
import type { UserProfile } from '@/api/types'

interface AuthState {
  token: string
  user: UserProfile | null
  roles: string[]
  permissions: string[]
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem('token') || '',
    user: localStorage.getItem('user') ? JSON.parse(localStorage.getItem('user') as string) : null,
    roles: localStorage.getItem('roles') ? JSON.parse(localStorage.getItem('roles') as string) : [],
    permissions: localStorage.getItem('permissions') ? JSON.parse(localStorage.getItem('permissions') as string) : []
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token),
    hasRole: (state) => (role: string) => state.roles.includes(role),
    hasAnyRole: (state) => (roles: string[]) => roles.some((role) => state.roles.includes(role)),
    hasPermission: (state) => (perm: string) => state.permissions.includes(perm)
  },
  actions: {
    async login(username: string, password: string) {
      const response = await authApi.login({ username, password })
      this.applyAuth(response)
    },
    async refreshMe() {
      if (!this.token) return
      const user = await authApi.me()
      this.user = user
      localStorage.setItem('user', JSON.stringify(this.user))
    },
    async refreshAuthorization() {
      if (!this.token) return
      const snapshot = await authApi.authorization()
      this.roles = snapshot.roles || []
      this.permissions = snapshot.permissions || []
      localStorage.setItem('roles', JSON.stringify(this.roles))
      localStorage.setItem('permissions', JSON.stringify(this.permissions))
    },
    async logout() {
      try {
        await authApi.logout()
      } finally {
        this.logoutLocal()
      }
    },
    logoutLocal() {
      this.token = ''
      this.user = null
      this.roles = []
      this.permissions = []
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      localStorage.removeItem('roles')
      localStorage.removeItem('permissions')
      clearBookingDraft()
    },
    applyAuth(response: AuthResponse) {
      this.token = response.token
      this.user = response.user
      this.roles = response.roles || []
      this.permissions = response.permissions || []
      localStorage.setItem('token', this.token)
      localStorage.setItem('user', JSON.stringify(this.user))
      localStorage.setItem('roles', JSON.stringify(this.roles))
      localStorage.setItem('permissions', JSON.stringify(this.permissions))
    }
  }
})
