import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import SgDropdown, { type SgDropdownOption } from './SgDropdown.vue'

const options: SgDropdownOption[] = [
  { value: 'BASKETBALL', label: '篮球馆（默认）', dataLabel: '篮球馆' },
  { value: 'STUDY', label: '学习共读' },
  { value: 42, label: '数字值选项' }
]

// jsdom 的 getComputedStyle 不反映运行时切换的行内 display，v-show 断言直接读行内样式。
const panelDisplay = (wrapper: ReturnType<typeof mount>) =>
  (wrapper.find('.sg-dd-panel').element as HTMLElement).style.display

// sg-pop 过渡（V3 S02）下收起是异步的：等 leave 过渡在 jsdom（零时长）走完再读行内样式。
const frame = () => new Promise((resolve) => requestAnimationFrame(() => resolve(null)))
const settle = async () => {
  await flushPromises()
  await frame()
  await frame()
}

const mountDropdown = (props: Partial<InstanceType<typeof SgDropdown>['$props']> = {}) =>
  mount(SgDropdown, {
    props: { options, ariaLabel: '测试下拉', ...props }
  })

afterEach(() => {
  document.body.innerHTML = ''
})

describe('SgDropdown 浮层下拉', () => {
  it('默认收起，触发器带 chip 语言与 aria-haspopup，占位文案可见', () => {
    const wrapper = mountDropdown({ placeholder: '请选择类型' })
    const toggle = wrapper.get('.sg-dd-toggle')
    expect(toggle.classes()).toContain('sg-chip')
    expect(toggle.attributes('aria-haspopup')).toBe('listbox')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.sg-dd-label').text()).toBe('请选择类型')
    expect(panelDisplay(wrapper)).toBe('none')
  })

  it('点击展开面板并同步 aria-expanded，选项带 role 与选中态', async () => {
    const wrapper = mountDropdown({ modelValue: 'STUDY' })
    await wrapper.get('.sg-dd-toggle').trigger('click')
    expect(wrapper.get('.sg-dd-toggle').attributes('aria-expanded')).toBe('true')
    // V3 S02：展开态根挂 is-open（驱动 caret 弹簧旋转与触发器柿橙 ring）
    expect(wrapper.get('.sg-dd').classes()).toContain('is-open')
    const opts = wrapper.findAll('.sg-dd-opt')
    expect(opts).toHaveLength(3)
    expect(opts[1].attributes('role')).toBe('option')
    expect(opts[1].attributes('aria-selected')).toBe('true')
    expect(opts[1].classes()).toContain('is-on')
    expect(opts[0].attributes('aria-selected')).toBe('false')
  })

  it('选择即回写：emit 新值并自动收起；modelValue 同步后触发器显示 dataLabel 剥离后缀的短标签', async () => {
    const wrapper = mountDropdown({ modelValue: 'STUDY' })
    await wrapper.get('.sg-dd-toggle').trigger('click')
    await wrapper.findAll('.sg-dd-opt')[0].trigger('click')
    await settle()

    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['BASKETBALL'])
    expect(wrapper.emitted('change')?.[0]).toEqual(['BASKETBALL'])
    expect(panelDisplay(wrapper)).toBe('none')

    // 父组件按契约回写 modelValue 后，触发器展示剥离「（默认）」后缀的短标签
    await wrapper.setProps({ modelValue: 'BASKETBALL' })
    expect(wrapper.get('.sg-dd-label').text()).toBe('篮球馆')
  })

  it('Esc 关闭浮层；点击外部同样关闭且不误触内部选择', async () => {
    const wrapper = mountDropdown()
    await wrapper.get('.sg-dd-toggle').trigger('click')
    expect(panelDisplay(wrapper)).not.toBe('none')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await settle()
    expect(panelDisplay(wrapper)).toBe('none')

    await wrapper.get('.sg-dd-toggle').trigger('click')
    document.body.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    await settle()
    expect(panelDisplay(wrapper)).toBe('none')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('block 变体用于表单字段形态，不再携带 chip 类', () => {
    const wrapper = mountDropdown({ variant: 'block' })
    expect(wrapper.get('.sg-dd').classes()).toContain('sg-dd--block')
    expect(wrapper.get('.sg-dd-toggle').classes()).not.toContain('sg-chip')
  })

  it('disabled 时点击不展开', async () => {
    const wrapper = mountDropdown({ disabled: true })
    await wrapper.get('.sg-dd-toggle').trigger('click')
    expect(panelDisplay(wrapper)).toBe('none')
  })

  it('空选项展示中文空态而不是空白面板', async () => {
    const wrapper = mountDropdown({ options: [] })
    await wrapper.get('.sg-dd-toggle').trigger('click')
    expect(wrapper.get('.sg-dd-empty').text()).toBe('暂无可选项')
  })
})
