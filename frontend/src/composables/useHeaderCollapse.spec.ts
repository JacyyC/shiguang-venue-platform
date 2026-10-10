import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { useHeaderCollapse } from './useHeaderCollapse'

const Host = defineComponent({
  setup() {
    const { collapsed } = useHeaderCollapse(88)
    return () => h('div', collapsed.value ? 'collapsed' : 'expanded')
  }
})

describe('useHeaderCollapse', () => {
  it('starts expanded and collapses after scrolling past the threshold', async () => {
    vi.spyOn(window, 'scrollY', 'get').mockReturnValue(10)
    const wrapper = mount(Host)
    expect(wrapper.text()).toBe('expanded')

    vi.spyOn(window, 'scrollY', 'get').mockReturnValue(200)
    window.dispatchEvent(new Event('scroll'))
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toBe('collapsed')

    vi.spyOn(window, 'scrollY', 'get').mockReturnValue(0)
    window.dispatchEvent(new Event('scroll'))
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toBe('expanded')
    wrapper.unmount()
  })

  it('removes the scroll listener on unmount', async () => {
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    const wrapper = mount(Host)
    wrapper.unmount()
    expect(removeSpy).toHaveBeenCalledWith('scroll', expect.any(Function))
  })
})
