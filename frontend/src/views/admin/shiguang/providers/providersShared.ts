/**
 * Provider 观测面共享逻辑（SHIGUANG-BATCH-02-REFACTOR-20260913 S07）。
 * 预览 ref/c/providers.js 的展示推导改为服务端事实驱动：五渠道、快照、调用记录与数据缺口
 * 全部来自 providerApi；预览的合成登记（固定分值、DEMO 编号、假刷新结果）不迁入。
 */
import type {
  OpsMetricSnapshotResponse,
  ProviderCallLog,
  ProviderRefreshResponse,
  ProviderStatusResponse,
  RiskSnapshotResponse,
  VenueObservationLocationResponse,
  WeatherSnapshotResponse
} from '@/api/provider'
import { formatHm, formatMonthDaySlash, formatStampDateTime } from '../adminShared'

export type ProviderChannel = 'weather' | 'risk' | 'metrics' | 'payment' | 'notice'

/** 预览五张渠道卡与注册表五渠道一一对应（contracts.md §4.1）。 */
export const PROVIDER_CHANNELS: Array<{
  key: ProviderChannel
  domain: string
  providerKey: string
  name: string
  desc: string
  refreshable: boolean
}> = [
  {
    key: 'weather',
    domain: 'WEATHER',
    providerKey: 'weather.open_meteo.dev',
    name: '天气观测',
    desc: '按场馆读取天气与观测位置。过期快照仍可追溯，但不代表当前天气。',
    refreshable: true
  },
  {
    key: 'risk',
    domain: 'RISK',
    providerKey: 'risk.internal_rules.dev',
    name: '风险观测',
    desc: '读取内部规则生成的风险摘要，回到事件计数核对影响来源。',
    refreshable: true
  },
  {
    key: 'metrics',
    domain: 'METRICS',
    providerKey: 'metrics.local_runtime.dev',
    name: '运行指标',
    desc: '查看开发环境运行快照，各指标保留自己的单位与数据状态。',
    refreshable: true
  },
  {
    key: 'payment',
    domain: 'PAYMENT',
    providerKey: 'payment.alipay.sandbox',
    name: '支付渠道',
    desc: '查看开发支付渠道的调用证据；这里不执行扣款、退款或对账。',
    refreshable: false
  },
  {
    key: 'notice',
    domain: 'NOTICE',
    providerKey: 'notice.smtp.sandbox',
    name: '通知渠道',
    desc: '查看沙箱通知渠道的调用状态；投递处置由通知工作面承接。',
    refreshable: false
  }
]

const CHANNEL_KEYS = PROVIDER_CHANNELS.map((channel) => channel.key)

/** 旧路由 /system-admin/providers 承接；hash 深链同预览 #weather 等。 */
export function initialChannelFromRoute(hash: string): ProviderChannel {
  const next = hash.replace('#', '') as ProviderChannel
  return CHANNEL_KEYS.includes(next) ? next : 'weather'
}

export function channelOf(key: string): (typeof PROVIDER_CHANNELS)[number] {
  return PROVIDER_CHANNELS.find((channel) => channel.key === key) ?? PROVIDER_CHANNELS[0]
}

/** 渠道卡徽标：新鲜度 + 连续失败 + 停用状态（B2-T30 按真实枚举全集定标签）。 */
export function channelBadge(provider: ProviderStatusResponse | undefined): { label: string; attention: boolean } {
  if (!provider) return { label: '未登记', attention: true }
  const freshness = (provider.freshnessStatus ?? '').toUpperCase()
  if (provider.consecutiveFailures > 0) return { label: `连续失败 ${provider.consecutiveFailures} 次`, attention: true }
  if (freshness === 'FRESH') return { label: '证据有效', attention: false }
  if (freshness === 'STALE') return { label: '证据滞后', attention: true }
  if (freshness === 'EXPIRED') return { label: '证据过期', attention: true }
  if (freshness === 'DISABLED') return { label: '未启用', attention: false }
  if ((provider.status ?? '').toUpperCase() === 'DISABLED') return { label: '已停用', attention: false }
  return { label: '未取得证据', attention: true }
}

/** 渠道卡次行：最近成功/失败时间，无记录时如实说明。 */
export function channelLastCall(provider: ProviderStatusResponse | undefined): string {
  if (!provider) return '暂无登记'
  const { lastSuccessAt: successAt, lastFailureAt: failureAt } = provider
  const failure = failureAt ? formatHm(failureAt) : ''
  const success = successAt ? formatHm(successAt) : ''
  if (failure && (!success || (failureAt ?? '') > (successAt ?? ''))) return `最近失败 ${failure}`
  if (success) return `最近成功 ${success}`
  return '暂无调用记录'
}

export function providerOf(
  providers: ProviderStatusResponse[],
  providerKey: string
): ProviderStatusResponse | undefined {
  return providers.find((provider) => provider.providerKey === providerKey)
}

/** 风险等级与调用结果标签（真实枚举全集）。 */
export function riskLevelLabel(level: string | null | undefined): string {
  const value = (level ?? '').toUpperCase()
  if (value === 'LOW') return '低风险'
  if (value === 'MEDIUM') return '中风险'
  if (value === 'HIGH') return '高风险'
  if (value === 'SEVERE') return '严重风险'
  return level || '—'
}

export function callStatusLabel(status: string | null | undefined): string {
  const value = (status ?? '').toUpperCase()
  if (value === 'SUCCESS') return '成功'
  if (value === 'FAILED' || value === 'ERROR') return '失败'
  if (value === 'PARTIAL' || value === 'PARTIAL_SUCCESS') return '部分完成'
  if (value === 'SKIPPED') return '未执行'
  return status || '—'
}

export function callStatusAttention(status: string | null | undefined): boolean {
  const value = (status ?? '').toUpperCase()
  return ['FAILED', 'ERROR'].includes(value)
}

/** 调用来源：MANUAL 人工触发 / SCHEDULED 定时任务 / SYSTEM 系统调用。 */
export function sourceTypeLabel(sourceType: string | null | undefined): string {
  const value = (sourceType ?? '').toUpperCase()
  if (value === 'MANUAL') return '人工触发'
  if (value === 'SCHEDULED') return '定时任务'
  if (value === 'SYSTEM') return '系统调用'
  return sourceType || '—'
}

/** 操作类型：服务端存英文动作码，预览为中文短语；未知值如实展示。 */
export const OPERATION_TYPE_LABELS: Record<string, string> = {
  FETCH_WEATHER: '采集天气',
  FETCH_WEATHER_BATCH: '批量采集天气',
  EVALUATE_RISK: '评估风险',
  COLLECT_METRICS: '采集运行指标',
  QUERY_PAYMENT: '查询开发支付结果',
  DELIVER_NOTICE: '投递通知'
}

export function operationTypeLabel(operationType: string | null | undefined): string {
  if (!operationType) return '—'
  return OPERATION_TYPE_LABELS[operationType.toUpperCase()] ?? operationType
}

/** 未知值不显示为零：缺字段一律渲染 —（预览"数据缺口保留在记录中"）。 */
export function orDash(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}

/** 置信度等小数的展示位数（参考为 0.90）；只格式化位数，不改变服务端数值。 */
export function decimalText(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—'
  return Number(value).toFixed(digits)
}

/** 时间格两行：首行 MM/DD，次行 HH:mm。 */
export function callStamp(value: string | null | undefined): { day: string; time: string } {
  return { day: formatMonthDaySlash(value) || '—', time: formatHm(value) || '—' }
}

export function stampDateTime(value: string | null | undefined): string {
  return formatStampDateTime(value) || '—'
}

/** 天气证据是否已过期（服务端 expiresAt 为唯一依据）。 */
export function weatherExpired(snapshot: WeatherSnapshotResponse | null | undefined, now: Date = new Date()): boolean {
  if (!snapshot?.expiresAt) return false
  const expires = new Date(snapshot.expiresAt)
  return !Number.isNaN(expires.getTime()) && expires.getTime() < now.getTime()
}

/** 快照依据卡（预览 evidenceAside）：观测/过期时间、置信度、渠道标识、配置版本、快照编号。 */
export interface EvidenceFacts {
  env: string
  confidence: string
  observedAt: string
  expiresAt: string
  providerKey: string
  configVersion: string
  snapshotNo: string
}

export function weatherEvidence(snapshot: WeatherSnapshotResponse | null | undefined): EvidenceFacts {
  return {
    env: snapshot?.providerEnv || '—',
    confidence: decimalText(snapshot?.confidence),
    observedAt: stampDateTime(snapshot?.observedAt),
    expiresAt: snapshot?.expiresAt ? stampDateTime(snapshot.expiresAt) : '未提供',
    providerKey: snapshot?.providerKey || '—',
    configVersion: orDash(snapshot?.configVersion),
    snapshotNo: orDash(snapshot?.snapshotNo)
  }
}

export function riskEvidence(snapshot: RiskSnapshotResponse | null | undefined): EvidenceFacts {
  return {
    env: snapshot?.providerEnv || '—',
    confidence: decimalText(snapshot?.confidence),
    observedAt: stampDateTime(snapshot?.createTime),
    expiresAt: '未提供',
    providerKey: snapshot?.providerKey || '—',
    configVersion: orDash(snapshot?.configVersion),
    snapshotNo: orDash(snapshot?.snapshotNo)
  }
}

export function metricEvidence(snapshots: OpsMetricSnapshotResponse[]): EvidenceFacts {
  const first = snapshots[0]
  return {
    env: first?.providerEnv || '—',
    confidence: decimalText(first?.confidence),
    observedAt: stampDateTime(first?.observedAt),
    expiresAt: first?.expiresAt ? stampDateTime(first.expiresAt) : '未提供',
    providerKey: first?.providerKey || '—',
    configVersion: orDash(first?.configVersion),
    snapshotNo: orDash(first?.snapshotNo)
  }
}

/** 指标观测窗口（服务端 windowStart/windowEnd）。 */
export function metricWindow(snapshots: OpsMetricSnapshotResponse[]): string {
  const first = snapshots[0]
  if (!first?.windowStart || !first?.windowEnd) return '未提供观测窗口'
  return `${formatHm(first.windowStart)}—${formatHm(first.windowEnd)} 观测窗口`
}

/** 风险分值条宽度（0-100 分值，缺值不画）。 */
export function meterWidth(score: number | null | undefined): string {
  const value = Number(score)
  if (!Number.isFinite(value)) return '0%'
  return `${Math.min(100, Math.max(0, value))}%`
}

/** 刷新结果三态（B2-T33：busy 的真实响应是 status=SKIPPED + dataGaps，不是异常）。 */
export function refreshNotice(result: ProviderRefreshResponse | null | undefined): string {
  const status = (result?.status ?? '').toUpperCase()
  const gap = result?.dataGaps?.[0] ?? ''
  if (status === 'SUCCESS') {
    const gaps = (result?.dataGaps ?? []).filter(Boolean)
    return gaps.length
      ? `刷新已完成，新的结果已加入当前证据；历史调用继续保留在下方。数据缺口：${gaps.join('、')}`
      : '刷新已完成，新的结果已加入当前证据；历史调用继续保留在下方。'
  }
  if (status === 'SKIPPED') {
    if (gap.includes('正在运行')) return '同一范围已有任务正在执行，本次没有启动新任务。稍后重新读取证据。'
    return gap || '本次刷新未执行，已有证据保留。'
  }
  if (status === 'FAILED') return `刷新失败：${gap || '上游未返回可用结果'}。已有证据保留，可再次刷新。`
  return gap || '刷新结果未能识别，已重新读取证据。'
}

/** 观测位置选项：可管理场馆全集 + 服务端位置登记合并。 */
export interface VenueObservationOption {
  venueId: number
  name: string
  hasLocation: boolean
  label: string
  latitude: string
  longitude: string
  timezone: string
}

export function venueObservationOptions(
  venues: Array<{ id: number; name: string }>,
  locations: VenueObservationLocationResponse[]
): VenueObservationOption[] {
  return venues.map((venue) => {
    const location = locations.find((item) => item.venueId === venue.id)
    return {
      venueId: venue.id,
      name: venue.name,
      hasLocation: !!location && location.latitude != null && location.longitude != null,
      label: location?.locationLabel ?? '',
      latitude: location?.latitude != null ? String(location.latitude) : '',
      longitude: location?.longitude != null ? String(location.longitude) : '',
      timezone: location?.timezone ?? 'Asia/Shanghai'
    }
  })
}

/** 观测位置维护的本地校验（预览同口径；服务端另有 ±90/±180 守卫）。 */
export function validateLocation(form: { label: string; latitude: string; longitude: string }): string | null {
  if (!form.label.trim()) return '请填写位置标签。'
  const latitude = Number(form.latitude)
  const longitude = Number(form.longitude)
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    return '请输入有效纬度（−90 至 90）和经度（−180 至 180）。'
  }
  return null
}

/** 调用时间线口径：只看失败时按服务端 status=FAILED 过滤。 */
export function callFilterParams(showFailures: boolean, domain: string): Record<string, unknown> {
  return showFailures
    ? { providerDomain: domain, status: 'FAILED', pageNum: 1, pageSize: 20 }
    : { providerDomain: domain, pageNum: 1, pageSize: 20 }
}

export function callMatchesChannel(call: ProviderCallLog, domain: string): boolean {
  return (call.providerDomain ?? '').toUpperCase() === domain
}
