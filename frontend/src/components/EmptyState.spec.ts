import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import EmptyState from './EmptyState.vue'

describe('EmptyState', () => {
  it('renders default copy as a polite status region', () => {
    const wrapper = mount(EmptyState)
    const region = wrapper.get('.empty-state')
    expect(region.attributes('role')).toBe('status')
    expect(region.attributes('aria-live')).toBe('polite')
    expect(wrapper.text()).toContain('这里还没有内容')
    expect(wrapper.text()).toContain('稍后再来看看，或调整当前筛选条件。')
  })

  it('renders custom title, description and the serif first-glyph placeholder', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无预约', description: '去场馆列表挑一个时段吧。' }
    })
    expect(wrapper.text()).toContain('暂无预约')
    expect(wrapper.text()).toContain('去场馆列表挑一个时段吧。')
    expect(wrapper.get('.empty-glyph').text()).toBe('暂')
  })

  it('projects the action slot', () => {
    const wrapper = mount(EmptyState, {
      slots: { action: '<button type="button">去看看</button>' }
    })
    expect(wrapper.get('.empty-action button').text()).toBe('去看看')
  })

  it('projects the NN/g guidance links slot only when provided (V5 §02)', () => {
    const without = mount(EmptyState)
    expect(without.find('.empty-links').exists()).toBe(false)

    const withLinks = mount(EmptyState, {
      slots: { links: '<a href="/venues">去看热门场馆</a><a href="/venues">浏览全部场地</a>' }
    })
    const nav = withLinks.get('.empty-links')
    expect(nav.attributes('aria-label')).toBe('空态引导链接')
    expect(nav.findAll('a')).toHaveLength(2)
    expect(withLinks.text()).toContain('去看热门场馆')
  })

  it('supports the compact modifier without changing copy', () => {
    const wrapper = mount(EmptyState, { props: { compact: true } })
    expect(wrapper.get('.empty-state').classes()).toContain('compact')
    expect(wrapper.text()).toContain('这里还没有内容')
  })

  it('renders zero-glass minimal empty state (V4 S03 预览 §05)：无卡底，compact 只改尺寸', () => {
    const plain = mount(EmptyState)
    expect(plain.find('.empty-card').exists()).toBe(false)
    expect(plain.find('.sg-glass').exists()).toBe(false)
    expect(plain.find('.empty-min').exists()).toBe(true)
    expect(plain.find('.empty-icon-wrap .empty-glyph').exists()).toBe(true)

    const compact = mount(EmptyState, { props: { compact: true } })
    expect(compact.get('.empty-state').classes()).toContain('compact')
    expect(compact.find('.sg-glass').exists()).toBe(false)
  })
})
