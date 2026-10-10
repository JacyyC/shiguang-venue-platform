import { defineStore } from 'pinia'
import { noticeApi } from '@/api/notice'

export const useAppStore = defineStore('app', {
  state: () => ({
    sidebarCollapsed: false,
    globalLoading: false,
    unreadCount: 0,
    // 后台框架壳 toast：App.vue 的 router-view 以 matched[0].path 为 key，
    // 跨工作区导航会重建 AdminLayout，壳层 toast 状态必须放在 store 才能跨重挂载存活
    toastMessage: '',
    toastTimer: undefined as ReturnType<typeof setTimeout> | undefined
  }),
  actions: {
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
    },
    showToast(text: string, duration = 2600) {
      this.toastMessage = text
      if (this.toastTimer) clearTimeout(this.toastTimer)
      this.toastTimer = setTimeout(() => {
        this.toastMessage = ''
        this.toastTimer = undefined
      }, duration)
    },
    async refreshUnreadCount() {
      this.unreadCount = await noticeApi.unreadCount()
    },
    setUnreadCount(count: number) {
      this.unreadCount = count
    },
    incrementUnreadCount() {
      this.unreadCount += 1
    },
    clearUnreadCount() {
      this.unreadCount = 0
    }
  }
})
