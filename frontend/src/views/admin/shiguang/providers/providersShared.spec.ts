import { describe, expect, it } from 'vitest'
import type {
  OpsMetricSnapshotResponse,
  ProviderRefreshResponse,
  ProviderStatusResponse,
  RiskSnapshotResponse,
  VenueObservationLocationResponse,
  WeatherSnapshotResponse
} from '@/api/provider'
import {
  PROVIDER_CHANNELS,
  callFilterParams,
  callStamp,
  callStatusAttention,
  callStatusLabel,
  channelBadge,
  channelLastCall,
  channelOf,
  decimalText,
  initialChannelFromRoute,
  meterWidth,
  metricEvidence,
  metricWindow,
  orDash,
  providerOf,
  refreshNotice,
  riskEvidence,
  riskLevelLabel,
  sourceTypeLabel,
  operationTypeLabel,
  stampDateTime,
  validateLocation,
  venueObservationOptions,
  weatherEvidence,
  weatherExpired
} from './providersShared'

const provider = (overrides: Partial<ProviderStatusResponse> = {}): ProviderStatusResponse =>
  ({
    providerKey: 'weather.open_meteo.dev',
    providerDomain: 'WEATHER',
    providerEnv: 'DEV',
    providerName: 'OPEN_METEO',
    displayName: 'Open-Meteo 天气观测 DEV',
    status: 'ACTIVE',
    freshnessStatus: 'UNKNOWN',
    consecutiveFailures: 0,
    ...overrides
  })

describe('渠道合同', () => {
  it('五张卡与注册表五渠道一一对应', () => {
    expect(PROVIDER_CHANNELS.map((item) => item.providerKey)).toEqual([
      'weather.open_meteo.dev',
      'risk.internal_rules.dev',
      'metrics.local_runtime.dev',
      'payment.alipay.sandbox',
      'notice.smtp.sandbox'
    ])
    expect(PROVIDER_CHANNELS.filter((item) => item.refreshable).map((item) => item.key)).toEqual(['weather', 'risk', 'metrics'])
  })

  it('hash 深链与预览同形态，未知值回退天气', () => {
    expect(initialChannelFromRoute('#risk')).toBe('risk')
    expect(initialChannelFromRoute('')).toBe('weather')
    expect(initialChannelFromRoute('#nope')).toBe('weather')
    expect(channelOf('payment').name).toBe('支付渠道')
  })
})

describe('渠道卡徽标', () => {
  it('连续失败优先于新鲜度，未知与停用分别如实标注', () => {
    expect(channelBadge(provider({ consecutiveFailures: 2 }))).toEqual({ label: '连续失败 2 次', attention: true })
    expect(channelBadge(provider({ freshnessStatus: 'FRESH' }))).toEqual({ label: '证据有效', attention: false })
    expect(channelBadge(provider({ freshnessStatus: 'STALE' }))).toEqual({ label: '证据滞后', attention: true })
    expect(channelBadge(provider({ freshnessStatus: 'EXPIRED' }))).toEqual({ label: '证据过期', attention: true })
    expect(channelBadge(provider({ freshnessStatus: 'DISABLED' }))).toEqual({ label: '未启用', attention: false })
    expect(channelBadge(provider({ freshnessStatus: 'UNKNOWN' }))).toEqual({ label: '未取得证据', attention: true })
    expect(channelBadge(undefined).label).toBe('未登记')
  })

  it('次行取最近一次成功或失败时间，无记录不伪造', () => {
    expect(channelLastCall(provider({ lastSuccessAt: '2026-09-13T08:10:00' }))).toBe('最近成功 08:10')
    expect(channelLastCall(provider({
      lastSuccessAt: '2026-09-13T08:10:00',
      lastFailureAt: '2026-09-13T10:14:00'
    }))).toBe('最近失败 10:14')
    expect(channelLastCall(provider())).toBe('暂无调用记录')
  })

  it('按 providerKey 命中注册表条目', () => {
    const list = [provider(), provider({ providerKey: 'notice.smtp.sandbox' })]
    expect(providerOf(list, 'notice.smtp.sandbox')?.providerKey).toBe('notice.smtp.sandbox')
    expect(providerOf(list, 'missing.key')).toBeUndefined()
  })
})

describe('枚举标签全集', () => {
  it('风险等级、调用结果与来源', () => {
    expect(riskLevelLabel('LOW')).toBe('低风险')
    expect(riskLevelLabel('MEDIUM')).toBe('中风险')
    expect(riskLevelLabel('HIGH')).toBe('高风险')
    expect(riskLevelLabel('ODD')).toBe('ODD')
    expect(callStatusLabel('SUCCESS')).toBe('成功')
    expect(callStatusLabel('FAILED')).toBe('失败')
    expect(callStatusLabel('SKIPPED')).toBe('未执行')
    expect(callStatusLabel(null)).toBe('—')
    expect(callStatusAttention('FAILED')).toBe(true)
    expect(callStatusAttention('SKIPPED')).toBe(false)
    expect(sourceTypeLabel('MANUAL')).toBe('人工触发')
    expect(sourceTypeLabel('SCHEDULED')).toBe('定时任务')
    expect(sourceTypeLabel('SYSTEM')).toBe('系统调用')
  })

  it('操作类型未知值原样展示', () => {
    expect(operationTypeLabel('EVALUATE_RISK')).toBe('评估风险')
    expect(operationTypeLabel('FETCH_WEATHER_BATCH')).toBe('批量采集天气')
    expect(operationTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(operationTypeLabel(undefined)).toBe('—')
  })
})

describe('证据与时间', () => {
  const snapshot: WeatherSnapshotResponse = {
    id: 1,
    snapshotNo: 'WT20260913',
    venueId: 101,
    providerKey: 'weather.open_meteo.dev',
    providerEnv: 'DEV',
    observedAt: '2026-09-13T08:10:00',
    expiresAt: '2026-09-13T08:40:00',
    confidence: 0.9,
    configVersion: 'OPEN_METEO_V1'
  }

  it('未知值渲染为 — 而不是零', () => {
    expect(orDash(null)).toBe('—')
    expect(orDash(undefined)).toBe('—')
    expect(orDash('')).toBe('—')
    expect(orDash(0)).toBe('0')
  })

  it('天气证据卡取服务端快照字段', () => {
    const facts = weatherEvidence(snapshot)
    expect(facts).toEqual({
      env: 'DEV',
      confidence: '0.90',
      observedAt: '2026-09-13 08:10',
      expiresAt: '2026-09-13 08:40',
      providerKey: 'weather.open_meteo.dev',
      configVersion: 'OPEN_METEO_V1',
      snapshotNo: 'WT20260913'
    })
    expect(weatherEvidence(null).snapshotNo).toBe('—')
  })

  it('置信度只格式化位数，不改服务端数值', () => {
    expect(decimalText(0.9)).toBe('0.90')
    expect(decimalText(0.942, 3)).toBe('0.942')
    expect(decimalText(null)).toBe('—')
  })

  it('过期判定只看 expiresAt', () => {
    const now = new Date('2026-09-13T09:00:00')
    expect(weatherExpired(snapshot, now)).toBe(true)
    expect(weatherExpired(snapshot, new Date('2026-09-13T08:20:00'))).toBe(false)
    expect(weatherExpired({ ...snapshot, expiresAt: undefined }, now)).toBe(false)
    expect(weatherExpired(null, now)).toBe(false)
  })

  it('风险证据与窗口、指标窗口', () => {
    const risk = { id: 2, snapshotNo: 'RK2', providerEnv: 'DEV', confidence: 0.9, createTime: '2026-09-13T10:05:00' } as RiskSnapshotResponse
    expect(riskEvidence(risk).expiresAt).toBe('未提供')
    expect(riskEvidence(risk).observedAt).toBe('2026-09-13 10:05')
    const metrics = [
      { id: 3, metricKey: 'redis_ping_ms', providerEnv: 'DEV', observedAt: '2026-09-13T10:12:00', windowStart: '2026-09-13T10:07:00', windowEnd: '2026-09-13T10:12:00', expiresAt: '2026-09-13T10:17:00', confidence: 0.95, configVersion: 'LOCAL_RUNTIME_V1', snapshotNo: 'MT3' }
    ] as OpsMetricSnapshotResponse[]
    expect(metricWindow(metrics)).toBe('10:07—10:12 观测窗口')
    expect(metricWindow([])).toBe('未提供观测窗口')
    expect(metricEvidence(metrics).configVersion).toBe('LOCAL_RUNTIME_V1')
  })

  it('分值条宽度夹在 0 至 100', () => {
    expect(meterWidth(32)).toBe('32%')
    expect(meterWidth(180)).toBe('100%')
    expect(meterWidth(-5)).toBe('0%')
    expect(meterWidth(null)).toBe('0%')
  })

  it('两行时间格与完整时间戳', () => {
    expect(callStamp('2026-09-12T10:14:00')).toEqual({ day: '09/12', time: '10:14' })
    expect(callStamp(null)).toEqual({ day: '—', time: '—' })
    expect(stampDateTime('2026-09-12T10:14:00')).toBe('2026-09-12 10:14')
  })
})

describe('刷新结果三态', () => {
  const result = (overrides: Partial<ProviderRefreshResponse>): ProviderRefreshResponse =>
    ({ status: 'SUCCESS', providerKey: 'weather.open_meteo.dev', snapshotId: 9, dataGaps: [], ...overrides })

  it('成功、同范围执行中、缺位置与失败各给不同措辞', () => {
    expect(refreshNotice(result({}))).toContain('新的结果已加入当前证据')
    expect(refreshNotice(result({ dataGaps: ['完整慢查询数据未接入。'] }))).toBe(
      '刷新已完成，新的结果已加入当前证据；历史调用继续保留在下方。数据缺口：完整慢查询数据未接入。'
    )
    expect(refreshNotice(result({ status: 'SKIPPED', dataGaps: ['采集器正在运行，本轮未执行'] })))
      .toBe('同一范围已有任务正在执行，本次没有启动新任务。稍后重新读取证据。')
    expect(refreshNotice(result({ status: 'SKIPPED', dataGaps: ['场馆缺少经纬度观测位置，无法拉取天气快照。'] })))
      .toBe('场馆缺少经纬度观测位置，无法拉取天气快照。')
    expect(refreshNotice(result({ status: 'FAILED', dataGaps: ['上游响应超时'] })))
      .toBe('刷新失败：上游响应超时。已有证据保留，可再次刷新。')
    expect(refreshNotice(null)).toBe('刷新结果未能识别，已重新读取证据。')
  })
})

describe('观测位置与调用过滤', () => {
  const locations = [
    { id: 1, venueId: 101, latitude: 31.2304, longitude: 121.4737, locationLabel: '北岸主馆观测点', timezone: 'Asia/Shanghai' }
  ] as VenueObservationLocationResponse[]

  it('场馆选项合并服务端位置登记', () => {
    const options = venueObservationOptions([{ id: 101, name: '北岸生活场馆' }, { id: 102, name: '东园运动中心' }], locations)
    expect(options[0]).toEqual({
      venueId: 101,
      name: '北岸生活场馆',
      hasLocation: true,
      label: '北岸主馆观测点',
      latitude: '31.2304',
      longitude: '121.4737',
      timezone: 'Asia/Shanghai'
    })
    expect(options[1].hasLocation).toBe(false)
    expect(options[1].timezone).toBe('Asia/Shanghai')
  })

  it('位置校验按预览同口径（标签必填 + 经纬度范围）', () => {
    expect(validateLocation({ label: '  ', latitude: '31', longitude: '121' })).toBe('请填写位置标签。')
    expect(validateLocation({ label: '北岸', latitude: '91', longitude: '121' })).toContain('请输入有效纬度')
    expect(validateLocation({ label: '北岸', latitude: '31.23', longitude: '181' })).toContain('请输入有效纬度')
    expect(validateLocation({ label: '北岸', latitude: '31.23', longitude: '121.47' })).toBeNull()
  })

  it('只看失败走服务端 status 过滤', () => {
    expect(callFilterParams(true, 'WEATHER')).toEqual({ providerDomain: 'WEATHER', status: 'FAILED', pageNum: 1, pageSize: 20 })
    expect(callFilterParams(false, 'RISK')).toEqual({ providerDomain: 'RISK', pageNum: 1, pageSize: 20 })
  })
})
