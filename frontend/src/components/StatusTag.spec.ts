import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StatusTag from './StatusTag.vue'

describe('StatusTag', () => {
  it('renders readable reservation status text', () => {
    const wrapper = mount(StatusTag, {
      props: { value: 'PENDING_REVIEW', kind: 'reservation' }
    })
    expect(wrapper.text()).toContain('待审核')
  })

  it('renders approved reservation status text for legacy compatibility', () => {
    const wrapper = mount(StatusTag, {
      props: { value: 'APPROVED', kind: 'reservation' }
    })
    expect(wrapper.text()).toContain('已通过')
  })

  it('renders slot status text instead of color-only state', () => {
    const wrapper = mount(StatusTag, {
      props: { value: 'MAINTENANCE', kind: 'slot' }
    })
    expect(wrapper.text()).toContain('维护中')
    expect(wrapper.get('.sg-tag').classes()).toContain('sg-tag--warning')
    expect(mount(StatusTag, { props: { value: 'CLOSED', kind: 'slot' } }).text()).toContain('已关闭')
  })

  it('maps the in-progress slot semantics to a honey stamp (R-08 跨界时段)', () => {
    const wrapper = mount(StatusTag, {
      props: { value: 'IN_PROGRESS', kind: 'slot' }
    })
    expect(wrapper.text()).toContain('进行中')
    expect(wrapper.get('.sg-tag').classes()).toContain('sg-tag--honey')
  })

  it('renders b1 inventory slot statuses as readable text', () => {
    const wrapper = mount(StatusTag, {
      props: { value: 'MANUAL_HOLD', kind: 'slot' }
    })
    expect(wrapper.text()).toContain('人工保留')
  })

  it('delegates stamp visuals to the global .sg-tag family (V3 S02 收敛)', () => {
    const wrapper = mount(StatusTag, { props: { value: 'PAID', kind: 'order' } })
    const stamp = wrapper.get('.sg-tag')
    expect(stamp.classes()).toContain('sg-tag--success')
    expect(stamp.attributes('aria-label')).toBe('状态：已支付')
    expect(stamp.text()).toBe('已支付')
  })

  it('falls back to a solid unknown stamp without echoing unknown enum values', () => {
    const wrapper = mount(StatusTag, {
      props: { value: 'WEIRD_FUTURE_STATUS', kind: 'reservation' }
    })
    expect(wrapper.text()).toContain('暂无法识别的状态')
    expect(wrapper.text()).not.toContain('WEIRD_FUTURE_STATUS')
    expect(wrapper.get('.sg-tag').classes()).toContain('sg-tag--unknown')
  })

  it('falls back safely for an unmapped kind', () => {
    const wrapper = mount(StatusTag, {
      props: { value: 'PENDING_REVIEW', kind: undefined }
    })
    expect(wrapper.text()).toContain('待审核')
  })

  it('maps community group roles to honey/mist/pine stamps', () => {
    expect(mount(StatusTag, { props: { value: 'OWNER', kind: 'community-group-role' } }).get('.sg-tag').classes()).toContain('sg-tag--honey')
    const admin = mount(StatusTag, { props: { value: 'ADMIN', kind: 'community-group-role' } })
    expect(admin.get('.sg-tag').classes()).toContain('sg-tag--mist')
    expect(admin.text()).toContain('管理员')
    const member = mount(StatusTag, { props: { value: 'MEMBER', kind: 'community-group-role' } })
    expect(member.get('.sg-tag').classes()).toContain('sg-tag--pine')
    expect(member.text()).toContain('活跃成员')
  })

  it('maps community post statuses to readable stamps', () => {
    const published = mount(StatusTag, { props: { value: 'PUBLISHED', kind: 'community-post-status' } })
    expect(published.get('.sg-tag').classes()).toContain('sg-tag--success')
    expect(published.text()).toContain('已发布')
    expect(mount(StatusTag, { props: { value: 'HIDDEN', kind: 'community-post-status' } }).text()).toContain('已隐藏')
  })

  it('maps advertising lifecycle and advertiser statuses to preview stamp tones', () => {
    const pending = mount(StatusTag, { props: { value: 'PENDING_REVIEW', kind: 'advertising-lifecycle' } })
    expect(pending.text()).toContain('待审核')
    expect(pending.get('.sg-tag').classes()).toContain('sg-tag--warning')
    const paused = mount(StatusTag, { props: { value: 'PAUSED', kind: 'advertising-lifecycle' } })
    expect(paused.text()).toContain('已暂停')
    expect(paused.get('.sg-tag').classes()).toContain('sg-tag--mist')
    expect(mount(StatusTag, { props: { value: 'RETIRED', kind: 'advertising-lifecycle' } }).text()).toContain('已停用')
    const active = mount(StatusTag, { props: { value: 'ACTIVE', kind: 'advertising-advertiser' } })
    expect(active.text()).toContain('合作中')
    expect(active.get('.sg-tag').classes()).toContain('sg-tag--success')
  })
})
