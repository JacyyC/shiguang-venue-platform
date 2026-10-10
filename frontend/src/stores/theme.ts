import { defineStore } from 'pinia'

export type ShiguangTheme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'sg-theme'

const readStoredTheme = (): ShiguangTheme => {
  try {
    return localStorage.getItem(THEME_STORAGE_KEY) === 'dark' ? 'dark' : 'light'
  } catch {
    return 'light'
  }
}

const persistTheme = (theme: ShiguangTheme) => {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme)
  } catch {
    // 隐私模式等场景下持久化失败不应阻断当前会话的主题切换。
  }
}

const applyTheme = (theme: ShiguangTheme) => {
  const root = document.documentElement
  root.setAttribute('data-theme', theme)
  root.classList.toggle('dark', theme === 'dark')
  root.style.colorScheme = theme
}

export const useThemeStore = defineStore('theme', {
  state: () => ({
    theme: readStoredTheme()
  }),
  getters: {
    isDark: (state): boolean => state.theme === 'dark'
  },
  actions: {
    init() {
      applyTheme(this.theme)
    },
    setTheme(theme: ShiguangTheme) {
      this.theme = theme
      persistTheme(theme)
      applyTheme(theme)
    },
    toggle() {
      this.setTheme(this.isDark ? 'light' : 'dark')
    }
  }
})
