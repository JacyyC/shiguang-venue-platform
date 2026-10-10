import { flushPromises, mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineComponent, h, type VNode } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProvidersView from './ProvidersView.vue'
import { providerApi } from '@/api/provider'

const menuState = vi.hoisted(() => ({
  permissions: new Set(['provider:observe', 'provider:refresh'])
}))

const authState = vi.hoisted(() => ({
  permissions: new Set(['provider:observe', 'provider:refresh'])
}))

vi.mock('@/api/provider', () => ({
  providerApi: {
    overview: vi.fn(),
    calls: vi.fn(),
    weatherSnapshots: vi.fn(),
    refreshWeather: vi.fn(),
    riskSnapshots: vi.fn(),
    refreshRisk: vi.fn(),
    metricSnapshots: vi.fn(),
    refreshMetrics: vi.fn(),
    locations: vi.fn(),
    saveLocation: vi.fn()
  }
}))

vi.mock('@/stores/menu', () => ({
  useMenuStore: () => ({
    hasPermission: (permission: string) => menuState.permissions.has(permission)
  })
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasPermission: (permission: string) => authState.permissions.has(permission)
  })
}))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn() }
  }
})

const pageOf = <T,>(records: T[]) => ({
  records,
  total: records.length,
  pageNum: 1,
  pageSize: 20
})

const providersViewSource = readFileSync(resolve(process.cwd(), 'src/views/system-admin/ProvidersView.vue'), 'utf8')

const currentProviders = [
  {
    providerKey: 'weather.open_meteo.dev',
    providerDomain: 'WEATHER',
    providerEnv: 'DEV',
    providerName: 'OPEN_METEO',
    displayName: 'Open-Meteo 天气观测',
    enabled: true,
    configVersion: 'OPEN_METEO_V1',
    status: 'ACTIVE',
    freshnessSeconds: 900,
    freshnessStatus: 'FRESH',
    freshnessDeadlineAt: '2026-07-13T10:15:00',
    evidenceAgeSeconds: 300,
    lastSuccessAt: '2026-07-13T10:00:00',
    consecutiveFailures: 0
  },
  {
    providerKey: 'risk.internal_rules.dev',
    providerDomain: 'RISK',
    providerEnv: 'DEV',
    providerName: 'INTERNAL_RISK_RULES',
    displayName: '本地风险规则',
    enabled: true,
    configVersion: 'INTERNAL_RISK_RULES_V2',
    status: 'ACTIVE',
    freshnessSeconds: 300,
    freshnessStatus: 'STALE',
    freshnessDeadlineAt: '2026-07-13T10:05:00',
    evidenceAgeSeconds: 600,
    lastSuccessAt: '2026-07-13T09:55:00',
    lastFailureAt: '2026-07-13T10:06:00',
    consecutiveFailures: 1,
    lastFailureCode: 'RISK_PROVIDER_FAILED',
    lastFailureMessage: '风险渠道请求超时，已保留最近一次成功证据。'
  },
  {
    providerKey: 'metrics.local_runtime.dev',
    providerDomain: 'METRICS',
    providerEnv: 'DEV',
    providerName: 'LOCAL_RUNTIME',
    displayName: '本地运行指标',
    enabled: false,
    configVersion: 'LOCAL_RUNTIME_V3',
    status: 'DEGRADED',
    freshnessSeconds: 60,
    freshnessStatus: 'STALE',
    freshnessDeadlineAt: '2026-07-13T09:51:00',
    evidenceAgeSeconds: 1140,
    lastSuccessAt: '2026-07-13T09:50:00',
    lastFailureAt: '2026-07-13T10:08:00',
    consecutiveFailures: 2,
    lastFailureCode: 'METRICS_PARTIAL_UNAVAILABLE',
    lastFailureMessage: 'Redis 指标暂不可用，已降级展示其余本地运行指标。'
  }
]

const futureProvider = {
  providerKey: 'future.domain.long_provider_name.dev',
  providerDomain: 'FUTURE_DOMAIN',
  providerEnv: 'DEV',
  providerName: 'FUTURE_PROVIDER_WITH_A_DELIBERATELY_LONG_DYNAMIC_NAME',
  displayName: '未来动态渠道观测',
  enabled: true,
  configVersion: 'FUTURE_DYNAMIC_V1',
  status: 'FAILED',
  freshnessSeconds: 120,
  freshnessStatus: 'STALE',
  freshnessDeadlineAt: '2026-07-13T10:02:00',
  evidenceAgeSeconds: 780,
  lastSuccessAt: '2026-07-13T09:49:00',
  lastFailureAt: '2026-07-13T10:09:00',
  consecutiveFailures: 4,
  lastFailureCode: 'FUTURE_DYNAMIC_FAILURE',
  lastFailureMessage: '未来动态渠道的失败消息用于验证长文本在窄屏表格中的可读性和溢出处理，不应触发布局遮挡或固定身份分支。'
}

const renderColumnSlot = (column: VNode, row: Record<string, unknown>): VNode[] => {
  const children = (column as { children?: { default?: (props: { row: Record<string, unknown> }) => VNode[] } }).children
  return typeof children?.default === 'function' ? children.default({ row }) : []
}

const columnProp = (column: VNode) => {
  const prop = (column.props as { prop?: unknown } | null)?.prop
  return typeof prop === 'string' ? prop : undefined
}

const columnLabel = (column: VNode) => {
  const label = (column.props as { label?: unknown } | null)?.label
  return typeof label === 'string' ? label : ''
}

const ElTableStub = defineComponent({
  props: {
    data: { type: Array, default: () => [] }
  },
  setup(props, { attrs, slots }) {
    return () => {
      const columns = slots.default?.() || []
      return h('div', attrs, [
        h('header', columns.map((column) => h('span', { class: 'provider-table-header' }, columnLabel(column)))),
        ...(props.data as Record<string, unknown>[]).map((row) => h('article', {
          key: String(row.id || row.providerKey || row.snapshotNo)
        }, [
          ...columns.flatMap((column) => {
            const prop = columnProp(column)
            return prop
              ? [h('span', { class: 'provider-table-cell', 'data-prop': prop }, String(row[prop] ?? ''))]
              : renderColumnSlot(column, row)
          })
        ]))
      ])
    }
  }
})

const ElTableColumnStub = defineComponent({
  props: {
    label: { type: String, default: '' },
    prop: { type: String, default: '' }
  },
  setup(props, { slots }) {
    return () => h('div', { 'data-label': props.label, 'data-prop': props.prop }, slots.default?.())
  }
})

const globalStubs = () => ({
  stubs: {
    'el-alert': { props: ['title'], template: '<div><strong>{{ title }}</strong><slot /></div>' },
    'el-button': {
      emits: ['click'],
      template: '<button type="button" v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>'
    },
    'el-form': { template: '<form><slot /></form>' },
    'el-form-item': { template: '<label><slot /></label>' },
    'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
    'el-input': { template: '<input />' },
    'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
    'el-pagination': true,
    'el-select': {
      props: ['modelValue'],
      emits: ['update:modelValue'],
      template: '<select v-bind="$attrs" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>'
    },
    'el-tab-pane': { template: '<section><slot /></section>' },
    'el-tabs': { template: '<div><slot /></div>' },
    'el-table': ElTableStub,
    'el-table-column': ElTableColumnStub,
    'el-tooltip': { template: '<span><slot /></span>' }
  },
  directives: {
    loading: {}
  }
})

describe('ProvidersView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    menuState.permissions = new Set(['provider:observe', 'provider:refresh'])
    authState.permissions = new Set(['provider:observe', 'provider:refresh'])
    vi.mocked(providerApi.overview).mockResolvedValue({
      providers: currentProviders,
      dataGaps: [
        '生产天气 SLA 未接入。',
        '商业风控未接入。',
        'Prometheus/APM 未接入。'
      ]
    })
    vi.mocked(providerApi.weatherSnapshots).mockResolvedValue(pageOf([
      {
        id: 1,
        snapshotNo: 'W20260707001',
        venueId: 1,
        providerName: 'OPEN_METEO',
        temperatureC: 28.6,
        precipitationMm: 1.2,
        windSpeed: 4.1,
        alertLevel: 'LOW',
        summaryText: '小雨，低风险',
        rawSummaryJson: '{"secretToken":"weather-secret-token"}'
      }
    ]))
    vi.mocked(providerApi.riskSnapshots).mockResolvedValue(pageOf([
      {
        id: 2,
        snapshotNo: 'R20260707001',
        scopeType: 'VENUE',
        scopeId: 1,
        providerName: 'INTERNAL_RISK_RULES',
        riskScore: 72,
        riskLevel: 'MEDIUM',
        failedLoginCount: 3,
        openComplaintCount: 4,
        severeComplaintCount: 1,
        ruleSummaryJson: '{"complaints":["用户手机号13800000000投诉：空调坏了"],"ipList":["10.1.1.1","10.1.1.2"]}'
      }
    ]))
    vi.mocked(providerApi.metricSnapshots).mockResolvedValue(pageOf([
      {
        id: 3,
        snapshotNo: 'M20260707001',
        providerName: 'LOCAL_RUNTIME',
        metricKey: 'redis_hit_rate',
        metricValue: 0.98,
        metricUnit: 'ratio',
        status: 'OK',
        sourceSummaryJson: '{"password":"redis-secret"}'
      }
    ]))
    vi.mocked(providerApi.calls).mockResolvedValue(pageOf([
      {
        id: 4,
        callNo: 'CALL20260707001',
        providerDomain: 'WEATHER',
        providerName: 'OPEN_METEO',
        operationType: 'FETCH_WEATHER',
        status: 'SUCCESS',
        sourceType: 'SYSTEM',
        durationMs: 88,
        requestSummaryJson: '{"lat":31.2,"token":"request-secret"}',
        responseSummaryJson: '{"raw":"response-json"}'
      }
    ]))
    vi.mocked(providerApi.locations).mockResolvedValue([
      {
        id: 5,
        venueId: 1,
        locationLabel: '上海演示场馆',
        areaCode: '310000',
        latitude: 31.2304,
        longitude: 121.4737,
        timezone: 'Asia/Shanghai',
        status: 'ACTIVE',
        sourceType: 'ADMIN'
      }
    ])
  })

  it('renders provider observability sections without leaking raw provider payloads', async () => {
    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    expect(providerApi.overview).toHaveBeenCalled()
    expect(providerApi.calls).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 20 }))
    expect(providerApi.weatherSnapshots).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 20 }))
    expect(providerApi.riskSnapshots).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 20 }))
    expect(providerApi.metricSnapshots).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 20 }))
    expect(providerApi.locations).toHaveBeenCalled()

    const text = wrapper.text()
    expect(text).toContain('OPEN_METEO')
    expect(text).toContain('INTERNAL_RISK_RULES')
    expect(text).toContain('LOCAL_RUNTIME')
    expect(text).toContain('数据新鲜度')
    expect(text).toContain('生产天气 SLA 未接入')
    expect(text).toContain('商业风控未接入')
    expect(text).toContain('Prometheus/APM 未接入')
    expect(text).toContain('渠道状态')
    expect(text).toContain('天气快照')
    expect(text).toContain('风险快照')
    expect(text).toContain('运行指标')
    expect(text).toContain('调用日志')
    expect(text).toContain('场馆观测位置')
    expect(text).not.toContain('weather-secret-token')
    expect(text).not.toContain('request-secret')
    expect(text).not.toContain('redis-secret')
    expect(text).not.toContain('13800000000')
    expect(text).not.toContain('10.1.1.1')
  })

  it('renders registry status, freshness, and evidence fields in the provider status table', async () => {
    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    const statusTable = wrapper.get('.provider-status-table')
    const text = statusTable.text()
    expect(text).toContain('启用状态')
    expect(text).toContain('配置版本')
    expect(text).toContain('证据年龄')
    expect(text).toContain('新鲜度截止')
    expect(text).toContain('最近失败')
    expect(text).toContain('最近失败消息')
    expect(text).toContain('OPEN_METEO_V1')
    expect(text).toContain('300 秒')
    expect(text).toContain('2026-07-13T10:15:00')
    expect(text).toContain('2026-07-13T10:06:00')
    expect(text).toContain('RISK_PROVIDER_FAILED')
    expect(text).toContain('风险渠道请求超时，已保留最近一次成功证据。')
    expect(text).toContain('已启用')
    expect(text).toContain('已停用')
  })

  it('counts only failed timeout and degraded calls as provider failures', async () => {
    vi.mocked(providerApi.calls).mockResolvedValueOnce(pageOf([
      { id: 1, status: 'FAILED', sourceType: 'MANUAL' },
      { id: 2, status: 'TIMEOUT', sourceType: 'SYSTEM' },
      { id: 3, status: 'DEGRADED', sourceType: 'SCHEDULED' },
      { id: 4, status: 'SKIPPED', sourceType: 'SCHEDULED' }
    ]))

    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    expect(wrapper.get('.provider-failure-kpi').text()).toContain('3')
    expect(wrapper.get('.provider-call-log-table').text()).toContain('定时任务')
  })

  it('keeps successful sections visible and renders a local error when the risk request fails', async () => {
    vi.mocked(providerApi.riskSnapshots).mockRejectedValueOnce(new Error('risk unavailable'))

    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    expect(wrapper.get('.provider-risk-error').text()).toContain('risk unavailable')
    expect(wrapper.get('.provider-status-table').text()).toContain('OPEN_METEO')
    expect(wrapper.text()).toContain('redis_hit_rate')
    expect(wrapper.text()).not.toContain('R20260707001')
    expect(wrapper.text()).not.toContain('request-secret')
  })

  it('renders unknown KPI values and no stale content when provider requests fail', async () => {
    vi.mocked(providerApi.overview).mockRejectedValueOnce(new Error('overview unavailable'))
    vi.mocked(providerApi.calls).mockRejectedValueOnce(new Error('calls unavailable'))
    vi.mocked(providerApi.locations).mockRejectedValueOnce(new Error('locations unavailable'))

    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    const cards = wrapper.findAll('[aria-label="渠道概览"] .kpi-item')
    expect(cards[0]?.find('strong').text()).toBe('—')
    expect(cards[1]?.find('strong').text()).toBe('—')
    expect(cards[2]?.find('strong').text()).toBe('—')
    expect(cards[3]?.find('strong').text()).toBe('—')
    expect(wrapper.text()).toContain('overview unavailable')
    expect(wrapper.text()).toContain('calls unavailable')
    expect(wrapper.text()).toContain('locations unavailable')
    expect(wrapper.find('.provider-status-table').exists()).toBe(false)
    expect(wrapper.find('.provider-call-log-table').exists()).toBe(false)
    expect(wrapper.findAll('[aria-label="渠道清单"] .provider-tag')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('CALL20260707001')
    expect(wrapper.text()).not.toContain('上海演示场馆')
  })

  it('derives header tags and sorted domain options from future overview providers', async () => {
    vi.mocked(providerApi.overview).mockResolvedValueOnce({
      providers: [
        ...currentProviders,
        {
          providerKey: 'payment.test_pay.dev',
          providerDomain: 'PAYMENT',
          providerEnv: 'DEV',
          providerName: 'TEST_PAY',
          displayName: '测试支付',
          status: 'EXPERIMENTAL',
          freshnessStatus: 'UNKNOWN',
          consecutiveFailures: 0
        }
      ],
      dataGaps: []
    })

    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    const headerTags = wrapper.find('[aria-label="渠道清单"]')
    expect(headerTags.findAll('.provider-tag')).toHaveLength(4)
    expect(headerTags.text()).toContain('测试支付')
    // el-tag 已收敛为 .sg-tag 语义章：未知/EXPERIMENTAL 状态沿用 info 语义 → neutral 章
    expect(headerTags.findAll('.provider-tag')[3].classes()).toContain('sg-tag--neutral')
    expect(wrapper.findAll('.provider-domain-select option').map((option) => option.text())).toEqual([
      'METRICS',
      'PAYMENT',
      'RISK',
      'WEATHER'
    ])
  })

  it('renders a future dynamic provider and domain in tags, tables, and call results without a fixed branch', async () => {
    vi.mocked(providerApi.overview).mockResolvedValueOnce({
      providers: [...currentProviders, futureProvider],
      dataGaps: []
    })
    vi.mocked(providerApi.calls).mockResolvedValueOnce(pageOf([{
      id: 6,
      providerDomain: futureProvider.providerDomain,
      providerName: futureProvider.providerName,
      operationType: 'FUTURE_DYNAMIC_COLLECTION',
      status: 'FAILED',
      sourceType: 'SCHEDULED',
      failureCode: futureProvider.lastFailureCode
    }]))

    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    expect(wrapper.get('[aria-label="渠道清单"]').text()).toContain(futureProvider.displayName)
    expect(wrapper.findAll('.provider-domain-select option').map((option) => option.text())).toEqual([
      'FUTURE_DOMAIN',
      'METRICS',
      'RISK',
      'WEATHER'
    ])
    expect(wrapper.get('.provider-status-table').text()).toContain(futureProvider.displayName)
    expect(wrapper.get('.provider-status-table').text()).toContain(futureProvider.lastFailureMessage)
    expect(wrapper.get('.provider-call-log-table').text()).toContain(futureProvider.providerDomain)
    expect(wrapper.get('.provider-call-log-table').text()).toContain(futureProvider.displayName)
  })

  it('keeps provider panels shrinkable and long dynamic evidence contained without fixed provider cards', async () => {
    vi.mocked(providerApi.overview).mockResolvedValueOnce({
      providers: [...currentProviders, futureProvider],
      dataGaps: []
    })

    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    expect(wrapper.findAll('.provider-section-panel')).toHaveLength(7)
    expect(wrapper.get('.provider-status-table').text()).toContain(futureProvider.displayName)
    expect(providersViewSource).toMatch(/\.provider-section-panel[\s\S]*?min-width:\s*0/)
    expect(providersViewSource).not.toContain('<el-table-column prop="providerName" label="Provider"')
    expect(providersViewSource).toContain("{ label: '内部渠道标识', value: row.providerName, mono: true }")
    expect(providersViewSource).toContain('EvidenceDetail')
    expect(providersViewSource).toMatch(
      /<el-table v-else class="provider-call-log-table"[\s\S]*?<el-table-column label="渠道" min-width="150">/
    )
    expect(providersViewSource).toMatch(/\.provider-tag\s*{[\s\S]*?overflow-wrap:\s*anywhere/)
    expect(wrapper.find('.fixed-provider-card').exists()).toBe(false)
    expect(providersViewSource).not.toContain('fixed-provider-card')
  })

  it('localizes source labels while preserving source filter values', async () => {
    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    expect(wrapper.get('.provider-call-log-table').text()).toContain('系统触发')
    expect(wrapper.findAll('.provider-source-select option').map((option) => option.text())).toEqual([
      '人工操作',
      '定时任务',
      '系统触发'
    ])

    for (const sourceType of ['MANUAL', 'SCHEDULED', 'SYSTEM']) {
      await wrapper.get('.provider-source-select').setValue(sourceType)
      await wrapper.get('.provider-filter-bar').trigger('submit')
      await flushPromises()

      expect(providerApi.calls).toHaveBeenLastCalledWith(expect.objectContaining({
        pageNum: 1,
        pageSize: 20,
        sourceType
      }))
    }
  })

  it('omits blank and duplicate provider domains while keeping all provider tags', async () => {
    vi.mocked(providerApi.overview).mockResolvedValueOnce({
      providers: [
        ...currentProviders,
        {
          providerKey: 'payment.test_pay.dev',
          providerDomain: 'PAYMENT',
          providerEnv: 'DEV',
          providerName: 'TEST_PAY',
          displayName: '测试支付',
          status: 'ACTIVE',
          freshnessStatus: 'FRESH',
          consecutiveFailures: 0
        },
        {
          providerKey: 'payment.test_pay.backup.dev',
          providerDomain: 'PAYMENT',
          providerEnv: 'DEV',
          providerName: 'TEST_PAY_BACKUP',
          displayName: '测试支付备用',
          status: 'UNKNOWN_STATUS',
          freshnessStatus: 'UNKNOWN',
          consecutiveFailures: 0
        },
        {
          providerKey: 'experimental.no_domain.dev',
          providerDomain: '',
          providerEnv: 'DEV',
          providerName: 'NO_DOMAIN_PROVIDER',
          displayName: '无域 Provider',
          status: 'UNKNOWN_STATUS',
          freshnessStatus: 'UNKNOWN',
          consecutiveFailures: 0
        }
      ],
      dataGaps: []
    })

    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    expect(wrapper.findAll('[aria-label="渠道清单"] .provider-tag')).toHaveLength(6)
    expect(wrapper.findAll('.provider-domain-select option').map((option) => option.text())).toEqual([
      'METRICS',
      'PAYMENT',
      'RISK',
      'WEATHER'
    ])
  })

  it('renders empty dynamic provider controls when overview has no providers', async () => {
    vi.mocked(providerApi.overview).mockResolvedValueOnce({ providers: [], dataGaps: [] })

    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    expect(wrapper.findAll('[aria-label="渠道清单"] .provider-tag')).toHaveLength(0)
    expect(wrapper.findAll('.provider-domain-select option')).toHaveLength(0)
    expect(wrapper.find('.toolbar-actions').text()).not.toContain('OPEN_METEO')
    expect(wrapper.find('.toolbar-actions').text()).not.toContain('INTERNAL_RISK_RULES')
    expect(wrapper.find('.toolbar-actions').text()).not.toContain('LOCAL_RUNTIME')
  })

  it('updates provider tags and domains when the overview is refreshed', async () => {
    vi.mocked(providerApi.overview)
      .mockResolvedValueOnce({ providers: currentProviders, dataGaps: [] })
      .mockResolvedValueOnce({
        providers: [{
          providerKey: 'payment.test_pay.dev',
          providerDomain: 'PAYMENT',
          providerEnv: 'DEV',
          providerName: 'TEST_PAY',
          displayName: '测试支付',
          status: 'ACTIVE',
          freshnessStatus: 'FRESH',
          consecutiveFailures: 0
        }],
        dataGaps: []
      })

    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()
    await wrapper.get('[aria-label="刷新当前观测数据"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[aria-label="渠道清单"]').text()).toBe('测试支付')
    expect(wrapper.findAll('.provider-domain-select option').map((option) => option.text())).toEqual(['PAYMENT'])
  })

  it('renders provider refresh controls from the authenticated action permission when navigation menus omit it', async () => {
    menuState.permissions = new Set(['provider:observe'])
    authState.permissions = new Set(['provider:observe', 'provider:refresh'])

    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    expect(wrapper.text()).toContain('保存位置')
    expect(wrapper.find('[aria-label="刷新天气快照"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="刷新风险快照"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="刷新运行指标"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="编辑观测位置"]').exists()).toBe(true)
  })

  it('hides and guards provider refresh and save controls without provider refresh permission', async () => {
    menuState.permissions = new Set(['provider:observe'])
    authState.permissions = new Set(['provider:observe'])
    const wrapper = mount(ProvidersView, { global: globalStubs() })
    await flushPromises()

    expect(wrapper.text()).toContain('查询')
    expect(wrapper.text()).not.toContain('保存位置')
    expect(wrapper.find('[aria-label="刷新天气快照"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="刷新风险快照"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="刷新运行指标"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="编辑观测位置"]').exists()).toBe(false)

    await (wrapper.vm as unknown as { refreshWeather: () => Promise<void> }).refreshWeather()
    await (wrapper.vm as unknown as { refreshRisk: () => Promise<void> }).refreshRisk()
    await (wrapper.vm as unknown as { refreshMetrics: () => Promise<void> }).refreshMetrics()
    await (wrapper.vm as unknown as { saveLocation: () => Promise<void> }).saveLocation()

    expect(providerApi.refreshWeather).not.toHaveBeenCalled()
    expect(providerApi.refreshRisk).not.toHaveBeenCalled()
    expect(providerApi.refreshMetrics).not.toHaveBeenCalled()
    expect(providerApi.saveLocation).not.toHaveBeenCalled()
  })
})
