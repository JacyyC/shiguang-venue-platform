import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import VenueTypeMotif from './VenueTypeMotif.vue'

describe('VenueTypeMotif 画笔单线图标（V5-UI-UX-SHIGUANG S02，预览 §04 定稿）', () => {
  it('renders the four brush icons with the exact preview geometry per motif', () => {
    const expected: Record<string, string> = {
      sport: 'M16 4v24M4 16h11M21 16h7',
      pool: 'M4 21c3-3 6-3 9 0s6 3 9 0 4-3 6-1',
      study: 'M10 9v14M22 9v14',
      house: 'M16 6l10 6v14H6V12z'
    }
    for (const motif of ['sport', 'pool', 'study', 'house'] as const) {
      const wrapper = mount(VenueTypeMotif, { props: { motif } })
      const svg = wrapper.get('svg')
      expect(svg.attributes('viewBox')).toBe('0 0 32 32')
      expect(svg.attributes('stroke-width')).toBe('1.4')
      expect(svg.attributes('stroke')).toBe('currentColor')
      expect(svg.attributes('fill')).toBe('none')
      expect(svg.attributes('stroke-linecap')).toBe('round')
      expect(svg.attributes('stroke-linejoin')).toBe('round')
      expect(svg.attributes('aria-hidden')).toBe('true')
      // path 逐值与预览 §04 一致
      const paths = wrapper.findAll('path').map((p) => p.attributes('d'))
      expect(paths).toContain(expected[motif])
      // 透明度 0.9 随组件（预览 .mcard5 .th svg opacity）
      expect(svg.get('g').attributes('opacity')).toBe('0.9')
      // 画笔语言无满铺：不再渲染 pattern 平铺单元
      expect(wrapper.find('pattern').exists()).toBe(false)
      expect(wrapper.find('defs').exists()).toBe(false)
    }
  })

  it('draws the study icon as two rounded rect spines plus inner lines (rect, not path)', () => {
    const wrapper = mount(VenueTypeMotif, { props: { motif: 'study' } })
    const rects = wrapper.findAll('rect')
    expect(rects).toHaveLength(2)
    expect(rects[0].attributes('x')).toBe('6')
    expect(rects[0].attributes('rx')).toBe('2')
    expect(rects[1].attributes('x')).toBe('18')
  })

  it('draws the sport icon with the crosshair circle r9', () => {
    const wrapper = mount(VenueTypeMotif, { props: { motif: 'sport' } })
    const circle = wrapper.get('circle')
    expect(circle.attributes('cx')).toBe('16')
    expect(circle.attributes('cy')).toBe('16')
    expect(circle.attributes('r')).toBe('9')
  })

  it('keeps stroke color unowned so usage sites inject the wash via currentColor', () => {
    const wrapper = mount(VenueTypeMotif, { props: { motif: 'pool' } })
    expect(wrapper.get('svg').attributes('stroke')).toBe('currentColor')
    wrapper.findAll('path, circle, rect').forEach((node) => {
      expect(node.attributes('stroke')).toBeUndefined()
      expect(node.attributes('fill')).toBeUndefined()
    })
  })
})
