import { defineComponent, h, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { useCountUp } from './useCountUp'

describe('useCountUp', () => {
  it('reveals the final value directly when IntersectionObserver is unavailable', async () => {
    const target = ref(128.5)
    const TestHost = defineComponent({
      setup() {
        const counter = useCountUp(target, { decimals: 2 })
        return () => h('output', { 'data-value': counter.displayValue.value }, String(counter.displayValue.value))
      }
    })
    const wrapper = mount(TestHost)
    await nextTick()
    expect(wrapper.attributes('data-value')).toBe('128.5')
  })

  it('honors reduced motion and updates when the target changes', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    const target = ref(3)
    const TestHost = defineComponent({
      setup() {
        const counter = useCountUp(target)
        return () => h('output', { 'data-value': counter.displayValue.value }, String(counter.displayValue.value))
      }
    })
    const wrapper = mount(TestHost)
    await nextTick()
    expect(wrapper.attributes('data-value')).toBe('3')
    target.value = 7
    await nextTick()
    expect(wrapper.attributes('data-value')).toBe('7')
    vi.unstubAllGlobals()
  })
})
