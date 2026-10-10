import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminFilterBar from './AdminFilterBar.vue'

const mountBar = () => mount(AdminFilterBar, {
  props: { label: '预约筛选' },
  slots: {
    default: '<div class="filter-field"><span class="filter-label">场馆</span></div>',
    actions: '<button type="button" class="query">查询</button>',
    more: '<div class="more-field">校区区域</div>'
  }
})

describe('AdminFilterBar', () => {
  it('renders common fields, actions and the collapsed more tier inside one solid card', async () => {
    const wrapper = mountBar()
    await flushPromises()

    expect(wrapper.find('.filter-bar.admin-filter-bar').exists()).toBe(true)
    expect(wrapper.find('.filter-field .filter-label').text()).toBe('场馆')
    expect(wrapper.find('.admin-filter-actions .query').exists()).toBe(true)
    // 二级条件默认收起：more 槽内容随 details 折叠存在但不展开
    expect(wrapper.find('.admin-filter-more').exists()).toBe(true)
    expect((wrapper.find('.admin-filter-more').element as HTMLDetailsElement).open).toBe(false)
    expect(wrapper.text()).toContain('更多条件')
  })

  it('toggles the more tier open through the details summary', async () => {
    const wrapper = mountBar()
    await wrapper.find('.admin-filter-more summary').trigger('click')
    expect((wrapper.find('.admin-filter-more').element as HTMLDetailsElement).open).toBe(true)
    expect(wrapper.find('.admin-filter-more-body .more-field').isVisible()).toBe(true)
  })

  it('omits the actions and more tiers when slots are absent', () => {
    const wrapper = mount(AdminFilterBar, { props: { label: '筛选' }, slots: { default: '<div />' } })
    expect(wrapper.find('.admin-filter-actions').exists()).toBe(false)
    expect(wrapper.find('.admin-filter-more').exists()).toBe(false)
  })
})
