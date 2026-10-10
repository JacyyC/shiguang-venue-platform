import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import SgCountNumber from './SgCountNumber.vue'

describe('SgCountNumber（V3 KPI 弹簧计数；V5 S02 预览 §03 根元素 span 化）', () => {
  it('renders the settled value with the tabular-numeral hook class when IntersectionObserver is unavailable', async () => {
    const wrapper = mount(SgCountNumber, { props: { value: 134.19, decimals: 2 } })
    await nextTick()
    const node = wrapper.get('.sg-count')
    expect(node.text()).toBe('134.19')
    expect(node.classes()).toContain('sg-count')
  })

  it('uses a span root so nested strong no longer hits .kpi-item strong descendant selectors', () => {
    const wrapper = mount(SgCountNumber, { props: { value: 1 } })
    expect(wrapper.get('.sg-count').element.tagName).toBe('SPAN')
    expect(wrapper.find('strong').exists()).toBe(false)
  })

  it('supports a display-layer currency prefix without touching the raw value', async () => {
    const wrapper = mount(SgCountNumber, { props: { value: 42, prefix: '¥' } })
    await nextTick()
    expect(wrapper.get('.sg-count').text()).toBe('¥42')
  })

  it('falls back to the final value under reduced motion', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    const wrapper = mount(SgCountNumber, { props: { value: 7 } })
    await nextTick()
    expect(wrapper.get('.sg-count').text()).toBe('7')
    vi.unstubAllGlobals()
  })
})
