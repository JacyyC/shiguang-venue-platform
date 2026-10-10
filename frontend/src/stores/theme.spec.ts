import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { THEME_STORAGE_KEY, useThemeStore } from './theme'

describe('theme store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.removeAttribute('data-bg')
    document.documentElement.classList.remove('dark')
    document.documentElement.style.removeProperty('color-scheme')
  })

  it('defaults to morning paper (light) without persisted preference', () => {
    const theme = useThemeStore()
    expect(theme.theme).toBe('light')
    expect(theme.isDark).toBe(false)
  })

  it('falls back to light when persisted value is invalid', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'neon')
    const theme = useThemeStore()
    expect(theme.theme).toBe('light')
  })

  it('restores persisted dark preference on init', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    const theme = useThemeStore()
    expect(theme.theme).toBe('dark')
    expect(theme.isDark).toBe(true)
  })

  it('applies data-theme, dark class and color-scheme when initializing', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    const theme = useThemeStore()
    theme.init()
    const root = document.documentElement
    expect(root.getAttribute('data-theme')).toBe('dark')
    expect(root.classList.contains('dark')).toBe(true)
    expect(root.style.colorScheme).toBe('dark')
  })

  it('toggle flips theme, persists preference and updates the same DOM', () => {
    const theme = useThemeStore()
    theme.init()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')

    theme.toggle()
    expect(theme.theme).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)

    theme.toggle()
    expect(theme.theme).toBe('light')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('V6 口味退役：store 不再持有 bgFlavor，html 不再落 data-bg', () => {
    const theme = useThemeStore()
    expect('bgFlavor' in theme).toBe(false)
    theme.init()
    expect(document.documentElement.getAttribute('data-bg')).toBeNull()
  })
})
