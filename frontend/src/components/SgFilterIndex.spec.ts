import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SgFilterIndex from './SgFilterIndex.vue'

const dimensions = [
  {
    key: 'type',
    label: '类型',
    options: [
      { value: '', label: '全部' },
      { value: 'SPORTS', label: '运动健身' },
      { value: 'STUDY', label: '学习自习' }
    ]
  },
  {
    key: 'time',
    label: '时间',
    options: [
      { value: '', label: '时间不限' },
      { value: 'custom', label: '自定义…' }
    ]
  }
]

afterEach(() => {
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

/* 区分形态：jsdom 无 matchMedia 时组件按桌面 rail 渲染；手风琴用例显式 stub 命中 ≤640px。 */
const stubMobile = () =>
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    matches: true,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn()
  })))

describe('SgFilterIndex 筛选索引栏', () => {
  it('默认收起：只渲染一行筛选摘要条（aria-expanded=false，摘要为「全部」）', () => {
    const rail = mount(SgFilterIndex, { props: { dimensions, selection: { type: '', time: '' } }, attachTo: document.body })

    const toggle = rail.get('[data-test="indexrail-toggle"]')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.get('.sg-indexrail-toggle-label').text()).toBe('筛选')
    expect(toggle.get('.sg-indexrail-toggle-summary').text()).toBe('全部')
    // 收起时弹出不渲染（v-show），收起状态由行内 display 兜底
    expect(rail.find('#sg-indexrail-body').isVisible()).toBe(false)

    rail.unmount()
  })

  it('点摘要条弹出索引：维度 tablist + 活跃维度选项 radiogroup，初始态 aria 正确', async () => {
    const rail = mount(SgFilterIndex, { props: { dimensions, selection: { type: '', time: '' } }, attachTo: document.body })
    await rail.get('[data-test="indexrail-toggle"]').trigger('click')
    expect(rail.get('[data-test="indexrail-toggle"]').attributes('aria-expanded')).toBe('true')

    const dims = rail.findAll('[role="tab"]')
    expect(dims.map((dimension) => dimension.text())).toEqual(['类型', '时间'])
    expect(dims[0].attributes('aria-selected')).toBe('true')
    expect(dims[1].attributes('aria-selected')).toBe('false')

    const group = rail.get('[role="radiogroup"]')
    expect(group.attributes('aria-label')).toBe('按类型筛选')
    const options = group.findAll('[role="radio"]')
    expect(options.map((option) => option.text())).toEqual(['全部', '运动健身', '学习自习'])
    expect(options[0].attributes('aria-checked')).toBe('true')
    expect(options[1].attributes('aria-checked')).toBe('false')

    expect(rail.find('[data-test="indexrail-opt-type-all"]').isVisible()).toBe(true)
    expect(rail.find('[data-test="indexrail-opt-time-all"]').isVisible()).toBe(false)

    rail.unmount()
  })

  it('带非默认筛选挂载时直接展开，摘要显示各维度当前值', () => {
    const rail = mount(SgFilterIndex, { props: { dimensions, selection: { type: 'SPORTS', time: 'custom' } }, attachTo: document.body })

    expect(rail.get('[data-test="indexrail-toggle"]').attributes('aria-expanded')).toBe('true')
    expect(rail.get('.sg-indexrail-toggle-summary').text()).toBe('运动健身 · 自定义…')

    rail.unmount()
  })

  it('弹出后点维度切换选项区，点选项发射 update:selection 且保留其余键', async () => {
    const updates: Record<string, string>[] = []
    const rail = mount(SgFilterIndex, {
      props: {
        dimensions,
        selection: { type: '', time: '' },
        'onUpdate:selection': (value: Record<string, string>) => updates.push(value)
      },
      attachTo: document.body
    })
    await rail.get('[data-test="indexrail-toggle"]').trigger('click')

    await rail.get('[data-test="indexrail-dim-time"]').trigger('click')
    expect(rail.get('[data-test="indexrail-dim-time"]').attributes('aria-selected')).toBe('true')
    expect(rail.find('[data-test="indexrail-opt-time-all"]').isVisible()).toBe(true)

    await rail.get('[data-test="indexrail-opt-time-custom"]').trigger('click')
    expect(updates).toEqual([{ type: '', time: 'custom' }])

    rail.unmount()
  })

  it('键盘：Enter/空格显式激活，方向键在组内循环换选并发射', async () => {
    const updates: Record<string, string>[] = []
    const rail = mount(SgFilterIndex, {
      props: {
        dimensions,
        selection: { type: '', time: '' },
        'onUpdate:selection': (value: Record<string, string>) => updates.push(value)
      },
      attachTo: document.body
    })
    await rail.get('[data-test="indexrail-toggle"]').trigger('click')
    const emitted = () => updates.at(-1)

    await rail.get('[data-test="indexrail-opt-type-SPORTS"]').trigger('keydown', { key: 'Enter' })
    expect(emitted()).toEqual({ type: 'SPORTS', time: '' })

    await rail.get('[data-test="indexrail-opt-type-SPORTS"]').trigger('keydown', { key: ' ' })
    expect(emitted()).toEqual({ type: 'SPORTS', time: '' })

    await rail.get('[data-test="indexrail-opt-type-SPORTS"]').trigger('keydown', { key: 'ArrowRight' })
    expect(emitted()).toEqual({ type: 'STUDY', time: '' })
    await rail.get('[data-test="indexrail-opt-type-all"]').trigger('keydown', { key: 'ArrowLeft' })
    expect(emitted()).toEqual({ type: 'STUDY', time: '' })

    rail.unmount()
  })

  it('Esc 与点外关闭弹出的索引', async () => {
    const rail = mount(SgFilterIndex, { props: { dimensions, selection: { type: '', time: '' } }, attachTo: document.body })
    await rail.get('[data-test="indexrail-toggle"]').trigger('click')
    expect(rail.get('[data-test="indexrail-toggle"]').attributes('aria-expanded')).toBe('true')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(rail.get('[data-test="indexrail-toggle"]').attributes('aria-expanded')).toBe('false')

    await rail.get('[data-test="indexrail-toggle"]').trigger('click')
    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(rail.get('[data-test="indexrail-toggle"]').attributes('aria-expanded')).toBe('false')

    rail.unmount()
  })

  it('panel-{key} 槽仅在对应维度激活时渲染', async () => {
    const rail = mount(SgFilterIndex, {
      props: { dimensions, selection: { type: '', time: '' } },
      slots: { 'panel-time': '<div class="time-extra">日期面板</div>' },
      attachTo: document.body
    })
    await rail.get('[data-test="indexrail-toggle"]').trigger('click')

    expect(rail.find('.time-extra').exists()).toBe(false)
    await rail.get('[data-test="indexrail-dim-time"]').trigger('click')
    expect(rail.find('.time-extra').exists()).toBe(true)
    expect(rail.find('.time-extra').element.closest('.sg-indexrail-extra')).not.toBeNull()

    rail.unmount()
  })

  it('手风琴形态：维度行摘要 + 互斥展开 + 选中即发射', async () => {
    stubMobile()
    const updates: Record<string, string>[] = []
    const acc = mount(SgFilterIndex, {
      props: {
        dimensions,
        selection: { type: 'SPORTS', time: '' },
        'onUpdate:selection': (value: Record<string, string>) => updates.push(value)
      },
      attachTo: document.body
    })
    await flushPromises()
    await acc.get('[data-test="indexrail-toggle"]').trigger('click')

    expect(acc.find('.sg-indexrail-acc').exists()).toBe(true)
    const rows = acc.findAll('.sg-indexrail-acc-dim')
    expect(rows.map((row) => row.get('.sg-indexrail-acc-val').text())).toEqual(['运动健身', '时间不限'])

    await rows[0].trigger('click')
    expect(rows[0].attributes('aria-expanded')).toBe('true')
    await rows[1].trigger('click')
    expect(rows[0].attributes('aria-expanded')).toBe('false')
    expect(rows[1].attributes('aria-expanded')).toBe('true')

    await acc.get('[data-test="indexrail-opt-time-custom"]').trigger('click')
    expect(updates).toEqual([{ type: 'SPORTS', time: 'custom' }])
    await acc.findAll('.sg-indexrail-acc-dim')[1].trigger('click')
    expect(acc.findAll('.sg-indexrail-acc-dim')[1].attributes('aria-expanded')).toBe('false')

    acc.unmount()
  })

  it('matchMedia 越过断点时在 rail 与手风琴间切换（展开态保持）', async () => {
    const listeners: Array<(event: MediaQueryListEvent) => void> = []
    let matches = false
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      get matches() {
        return matches
      },
      addEventListener: (_: string, listener: (event: MediaQueryListEvent) => void) => listeners.push(listener),
      removeEventListener: vi.fn()
    })))
    const rail = mount(SgFilterIndex, { props: { dimensions, selection: { type: '', time: '' } }, attachTo: document.body })
    await rail.get('[data-test="indexrail-toggle"]').trigger('click')
    expect(rail.find('.sg-indexrail-rail').exists()).toBe(true)

    matches = true
    listeners.forEach((listener) => listener({ matches: true } as MediaQueryListEvent))
    await flushPromises()
    expect(rail.find('.sg-indexrail-acc').exists()).toBe(true)

    rail.unmount()
  })

  it('dimensions 变化后活跃维度兜底回首个', async () => {
    const rail = mount(SgFilterIndex, { props: { dimensions, selection: { type: '', time: '' } }, attachTo: document.body })
    await rail.get('[data-test="indexrail-toggle"]').trigger('click')
    await rail.get('[data-test="indexrail-dim-time"]').trigger('click')
    await rail.setProps({ dimensions: [dimensions[0]] })
    expect(rail.get('[data-test="indexrail-dim-type"]').attributes('aria-selected')).toBe('true')

    rail.unmount()
  })
})
