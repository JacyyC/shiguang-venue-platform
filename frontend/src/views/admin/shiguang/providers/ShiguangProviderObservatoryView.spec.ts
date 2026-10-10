import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

const providerApiMock = vi.hoisted(() => ({
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
}))
const venueApiMock = vi.hoisted(() => ({ list: vi.fn() }))
const toastMock = vi.hoisted(() => vi.fn())
const granted = vi.hoisted(() => new Set<string>())

vi.mock('@/api/provider', () => ({ providerApi: providerApiMock }))
vi.mock('@/api/venue', () => ({ venueApi: venueApiMock, venueAdminApi: {} }))
vi.mock('@/api/notice', () => ({ noticeApi: { unreadCount: vi.fn().mockResolvedValue(0) } }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: (key: string) => granted.has(key) }) }))
vi.mock('@/stores/app', () => ({ useAppStore: () => ({ showToast: toastMock, toastMessage: '' }) }))

import ShiguangProviderObservatoryView from './ShiguangProviderObservatoryView.vue'
import { ISOLATED_REQUEST } from '../adminShared'

const OVERVIEW = {
  providers: [
    { providerKey: 'weather.open_meteo.dev', providerDomain: 'WEATHER', providerEnv: 'DEV', providerName: 'OPEN_METEO', displayName: 'Open-Meteo 天气观测 DEV', status: 'ACTIVE', freshnessStatus: 'UNKNOWN', consecutiveFailures: 0, enabled: true, configVersion: 'OPEN_METEO_V1' },
    { providerKey: 'risk.internal_rules.dev', providerDomain: 'RISK', providerEnv: 'DEV', providerName: 'INTERNAL_RISK_RULES', displayName: '内部风险规则 DEV', status: 'ACTIVE', freshnessStatus: 'FRESH', consecutiveFailures: 0, enabled: true, configVersion: 'INTERNAL_RISK_RULES_V1', lastSuccessAt: '2026-09-13T10:05:00' },
    { providerKey: 'metrics.local_runtime.dev', providerDomain: 'METRICS', providerEnv: 'DEV', providerName: 'LOCAL_RUNTIME', displayName: '本地运行指标 DEV', status: 'ACTIVE', freshnessStatus: 'EXPIRED', consecutiveFailures: 0, enabled: true, configVersion: 'LOCAL_RUNTIME_V1', lastSuccessAt: '2026-09-13T10:12:00' },
    { providerKey: 'payment.alipay.sandbox', providerDomain: 'PAYMENT', providerEnv: 'SANDBOX', providerName: 'ALIPAY_SANDBOX', displayName: 'Alipay Sandbox', status: 'DISABLED', freshnessStatus: 'DISABLED', consecutiveFailures: 0, enabled: false, configVersion: 'ALIPAY_SANDBOX_V1' },
    { providerKey: 'notice.smtp.sandbox', providerDomain: 'NOTICE', providerEnv: 'SANDBOX', providerName: 'SMTP_SANDBOX', displayName: 'SMTP 邮件沙箱', status: 'DISABLED', freshnessStatus: 'DISABLED', consecutiveFailures: 2, enabled: false, configVersion: 'SMTP_SANDBOX_V1', lastFailureAt: '2026-09-13T10:14:00' }
  ],
  dataGaps: ['Prometheus/APM、完整慢查询和分布式追踪未接入。', '真实外部消息渠道和生产支付 provider 未接入。']
}

const VENUES = { records: [{ id: 101, name: '北岸生活场馆' }, { id: 102, name: '东园运动中心' }], total: 2, pageNum: 1, pageSize: 100 }

const weatherSnapshot = (overrides: Record<string, unknown> = {}) => ({
  id: 5,
  snapshotNo: 'WT20260913081',
  venueId: 101,
  providerKey: 'weather.open_meteo.dev',
  providerEnv: 'DEV',
  observedAt: '2026-09-13T08:10:00',
  expiresAt: '2026-09-13T08:40:00',
  temperatureC: 24.6,
  precipitationMm: 0.4,
  windSpeed: 8.2,
  summaryText: '多云，有短时阵雨',
  confidence: 0.9,
  configVersion: 'OPEN_METEO_V1',
  ...overrides
})

const riskSnapshot = {
  id: 111,
  snapshotNo: 'RK20260913100',
  scopeType: 'SYSTEM',
  providerKey: 'risk.internal_rules.dev',
  providerEnv: 'DEV',
  riskScore: 32,
  riskLevel: 'MEDIUM',
  windowStart: '2026-09-12T22:56:55',
  windowEnd: '2026-09-13T22:56:55',
  failedLoginCount: 2,
  paymentFailureCount: 2,
  noticeFailureCount: 0,
  openComplaintCount: 1,
  severeComplaintCount: 0,
  confidence: 0.9,
  configVersion: 'INTERNAL_RISK_RULES_V1',
  createTime: '2026-09-13T10:05:00'
}

const metricRows = [
  { id: 2184, snapshotNo: 'MT2184', metricKey: 'jvm_memory_used_mb', metricValue: 216.79, metricUnit: 'MB', status: 'OK', providerKey: 'metrics.local_runtime.dev', providerEnv: 'DEV', windowStart: '2026-09-13T10:07:00', windowEnd: '2026-09-13T10:12:00', observedAt: '2026-09-13T10:12:00', expiresAt: '2026-09-13T10:17:00', confidence: 0.95, configVersion: 'LOCAL_RUNTIME_V1' },
  { id: 2183, snapshotNo: 'MT2183', metricKey: 'db_ping_ms', metricValue: 8, metricUnit: 'ms', status: 'OK', providerKey: 'metrics.local_runtime.dev', providerEnv: 'DEV', windowStart: '2026-09-13T10:07:00', windowEnd: '2026-09-13T10:12:00', observedAt: '2026-09-13T10:12:00', expiresAt: '2026-09-13T10:17:00', confidence: 0.95, configVersion: 'LOCAL_RUNTIME_V1' },
  { id: 2182, snapshotNo: 'MT2182', metricKey: 'redis_hit_rate', metricValue: 0.942, metricUnit: 'ratio', status: 'OK', providerKey: 'metrics.local_runtime.dev', providerEnv: 'DEV', windowStart: '2026-09-13T10:07:00', windowEnd: '2026-09-13T10:12:00', observedAt: '2026-09-13T10:12:00', expiresAt: '2026-09-13T10:17:00', confidence: 0.95, configVersion: 'LOCAL_RUNTIME_V1' },
  { id: 2181, snapshotNo: 'MT2181', metricKey: 'redis_ping_ms', metricValue: 2, metricUnit: 'ms', status: 'OK', providerKey: 'metrics.local_runtime.dev', providerEnv: 'DEV', windowStart: '2026-09-13T10:07:00', windowEnd: '2026-09-13T10:12:00', observedAt: '2026-09-13T10:12:00', expiresAt: '2026-09-13T10:17:00', confidence: 0.95, configVersion: 'LOCAL_RUNTIME_V1' }
]

const calls = [
  { id: 695, callNo: 'PC695', providerDomain: 'WEATHER', operationType: 'FETCH_WEATHER_BATCH', status: 'SKIPPED', durationMs: 1, failureCode: 'NO_ACTIVE_OBSERVATION_LOCATION', sourceType: 'SCHEDULED', occurredAt: '2026-09-13T10:14:00', snapshotType: 'WEATHER' },
  { id: 690, callNo: 'PC690', providerDomain: 'WEATHER', operationType: 'FETCH_WEATHER', status: 'FAILED', durationMs: 3000, failureCode: 'UPSTREAM_TIMEOUT', sourceType: 'MANUAL', occurredAt: '2026-09-13T09:10:00', snapshotType: 'WEATHER' }
]

const page = (records: unknown[]) => ({ records, total: records.length, pageNum: 1, pageSize: 20 })

beforeEach(() => {
  vi.clearAllMocks()
  setActivePinia(createPinia())
  granted.clear()
  granted.add('provider:observe')
  granted.add('provider:refresh')
  providerApiMock.overview.mockResolvedValue(OVERVIEW)
  providerApiMock.locations.mockResolvedValue([{ id: 1, venueId: 101, latitude: 31.2304, longitude: 121.4737, locationLabel: '北岸主馆观测点', timezone: 'Asia/Shanghai', status: 'ACTIVE' }])
  providerApiMock.weatherSnapshots.mockResolvedValue(page([weatherSnapshot()]))
  providerApiMock.riskSnapshots.mockResolvedValue(page([riskSnapshot]))
  providerApiMock.metricSnapshots.mockResolvedValue(page(metricRows))
  providerApiMock.calls.mockResolvedValue(page(calls))
  venueApiMock.list.mockResolvedValue(VENUES)
  const proto = (globalThis.HTMLDialogElement as unknown as { prototype: Record<string, () => void> }).prototype
  proto.showModal = proto.showModal ?? (() => {})
  proto.close = proto.close ?? (() => {})
})

async function mountView(path = '/system-admin/providers'): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/system-admin/providers', name: 'SystemProviders', component: ShiguangProviderObservatoryView }]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(ShiguangProviderObservatoryView, { global: { plugins: [router] } })
  for (let i = 0; i < 4; i += 1) await flushPromises()
  return wrapper
}

async function openChannel(wrapper: ReturnType<typeof mount>, key: string): Promise<void> {
  await wrapper.find(`[data-test="channel-${key}"]`).trigger('click')
  for (let i = 0; i < 3; i += 1) await flushPromises()
}

describe('ShiguangProviderObservatoryView', () => {
  it('五渠道卡来自服务端注册表并保留分权说明', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('先看证据，再判断状态')
    expect(wrapper.text()).toContain('沿着渠道、快照与调用记录，区分最新观测、过期信息和没有取得的数据。查看与刷新各有独立权限。')
    expect(wrapper.findAll('.channel')).toHaveLength(5)
    const weather = wrapper.find('[data-test="channel-weather"]')
    expect(weather.text()).toContain('天气观测')
    expect(weather.text()).toContain('OPEN_METEO')
    expect(weather.text()).toContain('未取得证据')
    expect(weather.text()).toContain('暂无调用记录')
    expect(wrapper.find('[data-test="channel-risk"]').text()).toContain('最近成功 10:05')
    expect(wrapper.find('[data-test="channel-notice"]').text()).toContain('连续失败 2 次')
    expect(wrapper.find('[data-test="channel-payment"]').text()).toContain('未启用')
  })

  it('天气渠道默认按有位置场馆读取快照与证据', async () => {
    const wrapper = await mountView()
    expect(providerApiMock.weatherSnapshots).toHaveBeenCalledWith({ venueId: 101, pageNum: 1, pageSize: 5 }, ISOLATED_REQUEST)
    const observation = wrapper.find('[data-test="weather-observation"]')
    expect(observation.text()).toContain('北岸生活场馆')
    expect(observation.text()).toContain('24.6')
    expect(observation.text()).toContain('多云，有短时阵雨')
    expect(observation.text()).toContain('降水 0.4 mm')
    // 服务端 expiresAt 已过（测试时钟固定为读取时刻之后的真实比较）
    expect(observation.text()).toContain('北岸主馆观测点')
    expect(observation.text()).toContain('OPEN_METEO_V1')
    expect(observation.text()).toContain('WT20260913081')
  })

  it('无观测位置的场馆呈现维护空态', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-test="observation-venue"]').setValue('102')
    for (let i = 0; i < 3; i += 1) await flushPromises()
    expect(wrapper.find('[data-test="location-missing"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('天气刷新需要场馆的观测位置。先维护位置，或切换到已有位置的场馆。')
    expect(providerApiMock.weatherSnapshots).toHaveBeenCalledWith({ venueId: 102, pageNum: 1, pageSize: 5 }, ISOLATED_REQUEST)
  })

  it('有位置但无快照时如实呈现空记录', async () => {
    providerApiMock.weatherSnapshots.mockResolvedValue(page([]))
    const wrapper = await mountView()
    expect(wrapper.find('[data-test="weather-empty"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('当前范围没有观测记录')
  })

  it('风险渠道渲染分值、等级与五类计数', async () => {
    const wrapper = await mountView()
    await openChannel(wrapper, 'risk')
    const observation = wrapper.find('[data-test="risk-observation"]')
    expect(observation.text()).toContain('32')
    expect(observation.text()).toContain('中风险')
    expect(observation.text()).toContain('登录失败')
    expect(observation.text()).toContain('2 次')
    expect(observation.text()).toContain('未处理投诉')
    expect(observation.text()).toContain('1 条')
    expect(observation.text()).toContain('规则分值用于事件归纳，不是安全保证；场馆评估中的登录失败仍为平台计数。')
    expect(observation.find('.meter span').attributes('style')).toContain('width: 32%')
    expect(wrapper.find('[data-test="refresh"]').text()).toBe('刷新风险')
  })

  it('指标渠道按服务端指标集渲染并保留单位与真实缺口', async () => {
    const wrapper = await mountView()
    await openChannel(wrapper, 'metrics')
    const observation = wrapper.find('[data-test="metrics-observation"]')
    expect(observation.text()).toContain('DEV 环境 · 10:07—10:12 观测窗口')
    expect(observation.find('[data-test="metric-redis_hit_rate"]').text()).toContain('Redis 缓存命中率')
    expect(observation.find('[data-test="metric-redis_hit_rate"]').text()).toContain('ratio')
    expect(observation.find('[data-test="metric-jvm_memory_used_mb"]').text()).toContain('216.79')
    expect(observation.text()).toContain('Prometheus/APM、完整慢查询和分布式追踪未接入。')
    expect(wrapper.find('[data-test="refresh"]').text()).toBe('刷新指标')
  })

  it('支付与通知渠道只给登记证据且无刷新按钮', async () => {
    const wrapper = await mountView()
    await openChannel(wrapper, 'payment')
    const observation = wrapper.find('[data-test="channel-payment-observation"]')
    expect(observation.text()).toContain('开发支付证据')
    expect(observation.text()).toContain('开发渠道调用成功只说明本次调用结果，不能证明真实资金结算或生产可用性。')
    expect(observation.text()).toContain('状态来自服务端渠道登记与调用记录，不提供渠道启停或生产切换。')
    expect(wrapper.find('[data-test="refresh"]').exists()).toBe(false)
    await openChannel(wrapper, 'notice')
    const notice = wrapper.find('[data-test="channel-notice-observation"]')
    expect(notice.text()).toContain('渠道接受与最终送达、阅读分别记录。失败重试与投递批次请进入通知工作面。')
    expect(notice.find('[data-test="goto-notice"]').attributes('href')).toBe('/system-admin/notice-delivery')
    expect(notice.text()).toContain('连续调用失败')
  })

  it('只看失败走服务端 status 过滤并给出空态措辞', async () => {
    providerApiMock.calls.mockResolvedValue(page([]))
    const wrapper = await mountView()
    await wrapper.find('[data-test="failures-only"]').setValue(true)
    for (let i = 0; i < 3; i += 1) await flushPromises()
    expect(providerApiMock.calls).toHaveBeenCalledWith({ providerDomain: 'WEATHER', status: 'FAILED', pageNum: 1, pageSize: 20 }, ISOLATED_REQUEST)
    expect(wrapper.find('[data-test="timeline"]').text()).toContain('当前没有失败调用')
    expect(wrapper.find('[data-test="timeline"]').text()).toContain('取消"只看失败"可查看其他结果。')
  })

  it('调用证据弹层只读并展示来源与失败码', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-test="call-evidence-690"]').trigger('click')
    const modal = wrapper.find('.c-modal')
    expect(modal.text()).toContain('采集天气 · 失败')
    expect(modal.text()).toContain('PC690')
    expect(modal.text()).toContain('人工触发')
    expect(modal.text()).toContain('UPSTREAM_TIMEOUT')
    expect(modal.text()).toContain('本次未取得新快照。请保留失败证据，核对渠道状态后再决定是否刷新。')
    expect(wrapper.find('[data-test="dialog-close-detail"]').exists()).toBe(true)
  })

  it('缺刷新权限时按钮禁用并说明边界', async () => {
    granted.delete('provider:refresh')
    const wrapper = await mountView()
    expect(wrapper.find('[data-test="refresh"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('当前只有查看权限。可以读取已有证据，刷新与观测位置维护不可用。')
    expect(wrapper.find('[data-test="location"]').attributes('disabled')).toBeDefined()
  })

  it('刷新前核对渠道与范围，成功后重读证据', async () => {
    providerApiMock.refreshWeather.mockResolvedValue({ status: 'SUCCESS', providerKey: 'weather.open_meteo.dev', snapshotId: 9, dataGaps: [] })
    const wrapper = await mountView()
    await wrapper.find('[data-test="refresh"]').trigger('click')
    const modal = wrapper.find('.c-modal')
    expect(modal.text()).toContain('即将刷新：天气观测')
    expect(modal.text()).toContain('范围：北岸生活场馆')
    expect(modal.text()).toContain('页面将保留已有证据，新的刷新结果单独显示。')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 6; i += 1) await flushPromises()
    expect(providerApiMock.refreshWeather).toHaveBeenCalledWith(101, ISOLATED_REQUEST)
    expect(wrapper.find('[data-test="refresh-message"]').text()).toContain('新的结果已加入当前证据')
    expect(providerApiMock.overview).toHaveBeenCalledTimes(2)
  })

  it('同范围任务在执行时按服务端 SKIPPED 措辞提示', async () => {
    providerApiMock.refreshWeather.mockResolvedValue({ status: 'SKIPPED', dataGaps: ['采集器正在运行，本轮未执行'] })
    const wrapper = await mountView()
    await wrapper.find('[data-test="refresh"]').trigger('click')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 6; i += 1) await flushPromises()
    expect(wrapper.find('[data-test="refresh-message"]').text()).toBe('同一范围已有任务正在执行，本次没有启动新任务。稍后重新读取证据。')
  })

  it('上游异常时保留已有证据并如实报错', async () => {
    providerApiMock.refreshWeather.mockRejectedValue({ response: { data: { message: '上游响应超时' } } })
    const wrapper = await mountView()
    await wrapper.find('[data-test="refresh"]').trigger('click')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 6; i += 1) await flushPromises()
    expect(wrapper.find('[data-test="refresh-message"]').text()).toBe('刷新失败：上游响应超时。已有证据保留，可再次刷新。')
    expect(wrapper.find('[data-test="weather-observation"]').exists()).toBe(true)
  })

  it('观测位置维护：本地校验保留弹层，保存走真实 PUT', async () => {
    providerApiMock.saveLocation.mockResolvedValue({ id: 1, venueId: 101 })
    const wrapper = await mountView()
    await wrapper.find('[data-test="location"]').trigger('click')
    expect(wrapper.text()).toContain('这是用于天气查询的观测点，不是用户定位。')
    await wrapper.find('input[data-test="pf-lat"]').setValue('91')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 3; i += 1) await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toContain('请输入有效纬度')
    expect(providerApiMock.saveLocation).not.toHaveBeenCalled()
    await wrapper.find('input[data-test="pf-lat"]').setValue('31.2304')
    await wrapper.find('form').trigger('submit')
    for (let i = 0; i < 6; i += 1) await flushPromises()
    expect(providerApiMock.saveLocation).toHaveBeenCalledWith(101, {
      locationLabel: '北岸主馆观测点',
      latitude: 31.2304,
      longitude: 121.4737,
      timezone: 'Asia/Shanghai'
    }, ISOLATED_REQUEST)
  })

  it('无查看权限时整页呈现无权态且不读取证据', async () => {
    granted.delete('provider:observe')
    const wrapper = await mountView()
    expect(wrapper.find('[data-test="denied-page"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('进入平台工作台不代表获准查看 Provider 证据。当前没有 Provider 查看权限。')
    expect(providerApiMock.weatherSnapshots).not.toHaveBeenCalled()
    expect(providerApiMock.calls).not.toHaveBeenCalled()
  })

  it('登记读取失败呈现可重试错误态', async () => {
    providerApiMock.overview.mockRejectedValue(new Error('network'))
    const wrapper = await mountView()
    expect(wrapper.find('[data-test="surface-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('这次读取没有成功，不能据此判断当前范围没有数据。')
  })

  it('快照详情说明风险评估窗口来自服务端', async () => {
    const wrapper = await mountView()
    await openChannel(wrapper, 'risk')
    await wrapper.find('[data-test="snapshot-detail"]').trigger('click')
    const modal = wrapper.find('.c-modal')
    expect(modal.text()).toContain('类型：内部风险评估')
    expect(modal.text()).toContain('RK20260913100')
    expect(modal.text()).toContain('2026-09-12 22:56')
    expect(modal.text()).toContain('数据缺口保留在记录中，未知值不显示为零。')
  })
})
