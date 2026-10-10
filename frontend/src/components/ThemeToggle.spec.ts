import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import ThemeToggle from './ThemeToggle.vue'
import { THEME_STORAGE_KEY, useThemeStore } from '@/stores/theme'

describe('ThemeToggle', () => {
  const stubs = {
    'el-icon': { template: '<span><slot /></span>' },
    Moon: true,
    Sunny: true
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    useThemeStore().init()
  })

  it('offers the lamp theme on morning paper by default', () => {
    const wrapper = mount(ThemeToggle, { global: { stubs } })
    const button = wrapper.get('.theme-toggle')
    expect(button.attributes('aria-pressed')).toBe('false')
    expect(button.text()).toContain('灯下')
    expect(button.attributes('aria-label')).toBe('切换到灯下主题')
  })

  it('toggles the shared theme store and persists the preference', async () => {
    const wrapper = mount(ThemeToggle, { global: { stubs } })
    await wrapper.get('.theme-toggle').trigger('click')
    const theme = useThemeStore()
    expect(theme.theme).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(wrapper.get('.theme-toggle').text()).toContain('晨纸')
  })
})
