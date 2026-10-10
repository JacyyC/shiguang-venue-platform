import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import TimeSlotGrid from './TimeSlotGrid.vue'
import type { AvailabilitySlot } from '@/api/types'

const baseSlot = (overrides: Partial<AvailabilitySlot> = {}): AvailabilitySlot => ({
  spaceId: 1,
  startTime: '2999-08-21T19:00:00',
  endTime: '2999-08-21T21:00:00',
  status: 'AVAILABLE',
  label: '室外硬地 3 号',
  price: 296,
  ...overrides
})

describe('TimeSlotGrid', () => {
  it('renders slot time range, status stamp and tabular price', () => {
    const wrapper = mount(TimeSlotGrid, {
      props: { slots: [baseSlot()] }
    })
    const slot = wrapper.get('.slot')
    expect(slot.text()).toContain('19:00 - 21:00')
    expect(slot.text()).toContain('可预约')
    expect(slot.text()).toContain('￥296.00')
    expect(slot.attributes('aria-pressed')).toBe('false')
  })

  it('emits toggle only for available slots', async () => {
    const wrapper = mount(TimeSlotGrid, {
      props: { slots: [baseSlot(), baseSlot({ startTime: '2026-08-21T20:00:00', status: 'RESERVED', reason: '已有预约' })] }
    })
    const buttons = wrapper.findAll('.slot')
    expect(buttons[1].attributes('disabled')).toBeDefined()

    await buttons[0].trigger('click')
    expect(wrapper.emitted('toggle')).toHaveLength(1)
    expect(wrapper.emitted('toggle')![0][0]).toMatchObject({ startTime: '2999-08-21T19:00:00' })

    await buttons[1].trigger('click')
    expect(wrapper.emitted('toggle')).toHaveLength(1)
  })

  it('marks the selected slot with class, stamp and aria state', () => {
    const selected = baseSlot()
    const wrapper = mount(TimeSlotGrid, {
      props: { slots: [selected, baseSlot({ startTime: '2026-08-21T20:00:00' })], selected: [selected] }
    })
    const buttons = wrapper.findAll('.slot')
    expect(buttons[0].classes()).toContain('selected')
    expect(buttons[0].classes()).toContain('is-on')
    expect(buttons[0].attributes('aria-pressed')).toBe('true')
    expect(buttons[0].text()).toContain('已选择')
    expect(buttons[1].classes()).not.toContain('selected')
  })

  it('hides prices when showPrice is false', () => {
    const wrapper = mount(TimeSlotGrid, {
      props: { slots: [baseSlot()], showPrice: false }
    })
    expect(wrapper.text()).not.toContain('￥')
    expect(wrapper.get('.slot').attributes('aria-label')).not.toContain('￥')
  })

  it('exposes the blocking reason on non-available slots', () => {
    const wrapper = mount(TimeSlotGrid, {
      props: { slots: [baseSlot({ status: 'UNAVAILABLE', reason: '场馆维护' })] }
    })
    expect(wrapper.get('.slot-reason').text()).toBe('场馆维护')
    expect(wrapper.text()).toContain('不可售')
  })

  it('renders one expired semantic for past slots regardless of backend blocking reason', () => {
    const future = baseSlot({ startTime: '2999-01-01T19:00:00', endTime: '2999-01-01T20:00:00' })
    const wrapper = mount(TimeSlotGrid, {
      props: { slots: [future] }
    })
    const slot = wrapper.get('.slot')
    // 未来时间不影响普通语义
    expect(slot.classes()).toContain('available')

    const expired = baseSlot({ startTime: '2020-01-01T19:00:00', endTime: '2020-01-01T21:00:00' })
    const expiredWrapper = mount(TimeSlotGrid, {
      props: {
        slots: [
          { ...expired, status: 'AVAILABLE' },
          { ...expired, status: 'MAINTENANCE', reason: '场地维护' },
          { ...expired, status: 'CLOSED', reason: '已关闭' }
        ]
      }
    })
    const buttons = expiredWrapper.findAll('.slot')
    buttons.forEach((button) => {
      expect(button.classes()).toContain('past')
      expect(button.attributes('disabled')).toBeDefined()
      expect(button.text()).toContain('已过期')
      expect(button.text()).not.toContain('维护')
      expect(button.text()).not.toContain('已关闭')
      expect(button.text()).not.toContain('场地维护')
      expect(button.text()).not.toContain('￥')
    })
  })

  it('never disables or expires the currently selected slot even when its time has passed', () => {
    const selected = baseSlot({ startTime: '2020-01-01T19:00:00', endTime: '2020-01-01T21:00:00' })
    const wrapper = mount(TimeSlotGrid, {
      props: { slots: [selected], selected: [selected] }
    })
    const slot = wrapper.get('.slot')
    expect(slot.classes()).toContain('selected')
    expect(slot.classes()).not.toContain('past')
    expect(slot.attributes('disabled')).toBeUndefined()
  })

  it('keeps an available-but-past slot unselectable via time expiry alone', async () => {
    const wrapper = mount(TimeSlotGrid, {
      props: { slots: [baseSlot({ startTime: '2020-01-01T19:00:00', endTime: '2020-01-01T20:00:00' })] }
    })
    const slot = wrapper.get('.slot')
    expect(slot.attributes('disabled')).toBeDefined()
    await slot.trigger('click')
    expect(wrapper.emitted('toggle')).toBeUndefined()
  })

  it('renders a single in-progress semantic for a backend PAST_TIME slot whose end has not passed (N2 跨界)', async () => {
    const now = Date.now()
    const inFlight = baseSlot({
      startTime: new Date(now - 30 * 60 * 1000).toISOString(),
      endTime: new Date(now + 30 * 60 * 1000).toISOString(),
      status: 'CLOSED',
      reasonCode: 'PAST_TIME',
      reason: '已过期'
    })
    const wrapper = mount(TimeSlotGrid, { props: { slots: [inFlight] } })
    const slot = wrapper.get('.slot')
    // 单一「进行中」蜜金语义：无价格、无原因、不出现已过期/已关闭字样
    expect(slot.classes()).toContain('ongoing')
    expect(slot.get('.sg-tag').classes()).toContain('sg-tag--honey')
    expect(slot.text()).toContain('进行中')
    expect(slot.text()).not.toContain('￥')
    expect(slot.text()).not.toContain('已过期')
    expect(slot.text()).not.toContain('已关闭')
    expect(slot.attributes('disabled')).toBeDefined()
    expect(slot.attributes('aria-label')).not.toContain('￥')
    await slot.trigger('click')
    expect(wrapper.emitted('toggle')).toBeUndefined()
  })

  it('renders a single expired semantic for a backend PAST_TIME slot whose end has passed (N2)', () => {
    const gone = baseSlot({
      startTime: '2020-01-01T19:00:00',
      endTime: '2020-01-01T20:00:00',
      status: 'CLOSED',
      reason: '已过期'
    })
    const wrapper = mount(TimeSlotGrid, {
      props: { slots: [{ ...gone, reasonCode: 'PAST_TIME' }] }
    })
    const slot = wrapper.get('.slot')
    expect(slot.classes()).toContain('past')
    expect(slot.text()).toContain('已过期')
    expect(slot.text()).not.toContain('￥')
    expect(slot.text()).not.toContain('已关闭')
    expect(slot.attributes('disabled')).toBeDefined()
  })
})
