import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import type { MenuNode } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

interface MenuState {
  menus: MenuNode[]
  loaded: boolean
  loading: boolean
  unavailable: boolean
  tokenSnapshot: string
}

export const useMenuStore = defineStore('menu', {
  state: (): MenuState => ({
    menus: [],
    loaded: false,
    loading: false,
    unavailable: false,
    tokenSnapshot: ''
  }),
  getters: {
    /**
     * 菜单树可达性判定 —— **只用于入口**（导航项、路由 `meta.menuKey`）。
     *
     * 菜单接口按 `visible='Y'` 过滤（`be/system/mapper/SysMenuMapper`），而承载叶子能力的节点是
     * `visible='N'` 的 `BUTTON`，永远不在树里；对叶子权限点调用本方法恒为 false。
     * 判断「我是否持有某个权限点」必须用 `auth.hasPermission`（来源 `/users/me/authorization`，
     * 不过滤 visible）。该合同由 `src/utils/permissionChannel.spec.ts` 扫描源码强制执行（B4-P1）。
     */
    hasPermission: (state) => (perm: string) => {
      const visit = (nodes: MenuNode[]): boolean =>
        nodes.some((node) => node.perms === perm || (node.children?.length ? visit(node.children) : false))
      return visit(state.menus)
    },
    findByPath: (state) => (path: string) => {
      let matched: MenuNode | undefined
      const visit = (nodes: MenuNode[]) => {
        for (const node of nodes) {
          if (node.path === path) {
            matched = node
            return
          }
          if (node.children?.length) {
            visit(node.children)
          }
          if (matched) return
        }
      }
      visit(state.menus)
      return matched
    }
  },
  actions: {
    async loadMenus() {
      const auth = useAuthStore()
      this.loading = true
      try {
        this.menus = await authApi.menus()
        this.loaded = true
        this.unavailable = false
        this.tokenSnapshot = auth.token
        return true
      } catch (error) {
        const status = (error as { response?: { status?: number } })?.response?.status
        if (!auth.isLoggedIn || status === 401 || status === 403) throw error
        if (this.tokenSnapshot !== auth.token) this.menus = []
        this.unavailable = true
        return false
      } finally {
        this.loading = false
      }
    },
    reset() {
      this.menus = []
      this.loaded = false
      this.loading = false
      this.unavailable = false
      this.tokenSnapshot = ''
    }
  }
})
