import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminTabbedLedger from './AdminTabbedLedger.vue'
import EvidenceDetail from './EvidenceDetail.vue'
import AdminExpandPanel from './AdminExpandPanel.vue'

const stubs = {
  'el-tabs': { props: ['modelValue'], emits: ['update:modelValue'], template: '<section><slot /></section>' },
  'el-tab-pane': { props: ['label', 'name'], template: '<div class="pane"><button type="button" class="pane-name">{{ name }}</button><slot /></div>' }
}

describe('admin shared ledger components', () => {
  it('AdminTabbedLedger renders one pane slot per tab and forwards v-model', async () => {
    const wrapper = mount({
      components: { AdminTabbedLedger },
      data: () => ({ active: 'ledger', tabs: [{ name: 'ledger', label: '资产台账' }, { name: 'inspections', label: '巡检' }] }),
      template: `<AdminTabbedLedger v-model="active" :tabs="tabs">
        <template #ledger><div class="ledger-body">台账</div></template>
        <template #inspections><div class="inspection-body">巡检</div></template>
      </AdminTabbedLedger>`
    }, { global: { stubs } })
    await flushPromises()

    expect(wrapper.findAll('.pane').length).toBe(2)
    expect(wrapper.find('.ledger-body').exists()).toBe(true)
    expect(wrapper.find('.inspection-body').exists()).toBe(true)
    expect(wrapper.vm.active).toBe('ledger')
  })

  it('EvidenceDetail renders hairline key-value rows with mono values and dash fallback', () => {
    const wrapper = mount(EvidenceDetail, {
      props: {
        items: [
          { label: '渠道环境', value: '沙箱' },
          { label: '外部流水号', value: '202608291234', mono: true },
          { label: '配置版本', value: '' }
        ]
      }
    })
    const rows = wrapper.findAll('div')
    expect(rows.length).toBe(3)
    expect(rows[1].find('dd').classes()).toContain('is-mono')
    expect(rows[2].find('dd').text()).toBe('-')
  })

  it('AdminExpandPanel provides the focus-in row drawer container', () => {
    const wrapper = mount(AdminExpandPanel, { slots: { default: '<div class="cluster">状态变更表单</div>' } })
    expect(wrapper.find('.admin-expand-panel.sg-focus-in').exists()).toBe(true)
    expect(wrapper.find('.cluster').exists()).toBe(true)
  })
})
