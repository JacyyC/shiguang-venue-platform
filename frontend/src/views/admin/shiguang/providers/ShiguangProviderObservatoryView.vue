<template>
  <div ref="rootEl" class="sga-c" data-test="sga-provider-root">
    <header class="heading">
      <div class="eyebrow">平台范围 · 渠道与观测证据</div>
      <h1>先看证据，再判断状态</h1>
      <p class="intro">沿着渠道、快照与调用记录，区分最新观测、过期信息和没有取得的数据。查看与刷新各有独立权限。</p>
    </header>

    <div v-if="surface === 'loading'" class="empty" role="status" data-test="surface-loading">
      <h2>正在读取记录</h2>
      <p>内容尚未返回，请稍候。</p>
    </div>
    <div v-else-if="surface === 'error'" class="empty" role="alert" data-test="surface-error">
      <h2>记录暂时无法读取</h2>
      <p>这次读取没有成功，不能据此判断当前范围没有数据。</p>
      <button class="btn" type="button" data-test="retry" @click="bootstrap">重新读取</button>
    </div>
    <div v-else-if="!canObserve" class="empty" data-test="denied-page">
      <h2>当前没有查看权限</h2>
      <p>进入平台工作台不代表获准查看 Provider 证据。当前没有 Provider 查看权限。</p>
    </div>

    <section v-else id="provider-workspace" data-test="provider-workspace">
      <div class="channels" aria-label="选择渠道领域">
        <button
          v-for="item in PROVIDER_CHANNELS"
          :key="item.key"
          class="channel"
          type="button"
          :data-test="`channel-${item.key}`"
          :aria-pressed="channel === item.key"
          @click="switchChannel(item.key)"
        >
          <small>{{ shortName(item) }}</small>
          <strong>{{ item.name }}</strong>
          <span class="badge" :class="{ attention: badgeOf(item).attention }">{{ badgeOf(item).label }}</span>
          <small style="display:block;margin-top:9px">{{ lastCallOf(item) }}</small>
        </button>
      </div>

      <div class="spread">
        <div><h2>{{ activeChannel.name }}</h2><p class="muted">{{ activeChannel.desc }}</p></div>
        <button
          v-if="activeChannel.refreshable"
          class="btn primary"
          type="button"
          data-test="refresh"
          :disabled="!canRefresh || refreshing"
          @click="openRefresh"
        >{{ refreshing ? '正在刷新…' : `刷新${refreshNoun}` }}</button>
      </div>
      <p v-if="!canRefresh" class="notice">当前只有查看权限。可以读取已有证据，刷新与观测位置维护不可用。</p>
      <p v-if="refreshMessage" class="notice" role="status" data-test="refresh-message">{{ refreshMessage }}</p>

      <!-- 天气观测 -->
      <template v-if="channel === 'weather'">
        <p v-for="gap in weatherGaps" :key="gap" class="notice" data-test="weather-gap">{{ gap }}</p>
        <div class="inline-select">
          <label for="observation-venue">观测场馆</label>
          <select id="observation-venue" v-model.number="weatherVenueId" data-test="observation-venue" @change="loadChannel">
            <option v-for="option in venueOptions" :key="option.venueId" :value="option.venueId">{{ option.name }}</option>
          </select>
        </div>
        <section v-if="currentVenue && !currentVenue.hasLocation" class="empty" data-test="location-missing">
          <h2>尚未配置观测位置</h2>
          <p>天气刷新需要场馆的观测位置。先维护位置，或切换到已有位置的场馆。</p>
          <button class="btn" type="button" data-test="maintain-location" :disabled="!canRefresh" @click="openLocation">维护观测位置</button>
        </section>
        <section v-else-if="!weather" class="empty" data-test="weather-empty">
          <h2>当前范围没有观测记录</h2>
          <p>该场馆还没有天气快照。取得观测位置后可刷新天气，历史调用继续保留。</p>
        </section>
        <div v-else class="observation" data-test="weather-observation">
          <section>
            <div class="spread">
              <h3>{{ currentVenue?.name || '未知场馆' }}</h3>
              <span class="badge" :class="{ attention: weatherIsExpired }">{{ weatherIsExpired ? '历史快照' : '最新快照' }} · {{ hm(weather.observedAt) }}</span>
            </div>
            <div class="reading">
              <div class="number">{{ orDash(weather.temperatureC) }}<span class="unit"> °C</span></div>
              <div class="reading-text">
                <h3>{{ weather.summaryText || '渠道未返回天气描述' }}</h3>
                <p class="muted">降水 {{ orDash(weather.precipitationMm) }} mm<br>风速 {{ orDash(weather.windSpeed) }} km/h</p>
              </div>
            </div>
            <p class="notice">{{ weatherFreshnessText }}</p>
            <div class="strip">
              <strong>观测位置</strong>
              <p class="muted">{{ currentVenue?.label || '—' }}<br>{{ currentVenue?.timezone || '—' }}</p>
              <button class="btn" type="button" data-test="location" :disabled="!canRefresh" @click="openLocation">查看与维护位置</button>
            </div>
          </section>
          <aside class="evidence-aside">
            <h3>快照依据</h3>
            <EvidenceBlock :facts="weatherFacts" />
            <button class="link-button" type="button" data-test="snapshot-detail" @click="openSnapshot">查看快照详情</button>
          </aside>
        </div>
      </template>

      <!-- 风险观测 -->
      <template v-else-if="channel === 'risk'">
        <div class="inline-select">
          <label for="risk-scope">评估范围</label>
          <select id="risk-scope" v-model="riskScope" data-test="risk-scope" @change="loadChannel">
            <option value="">全平台</option>
            <option v-for="option in venueOptions" :key="option.venueId" :value="String(option.venueId)">{{ option.name }}</option>
          </select>
        </div>
        <section v-if="!risk" class="empty" data-test="risk-empty">
          <h2>当前范围没有观测记录</h2>
          <p>该范围还没有风险快照。可刷新风险摘要取得新的观测。</p>
        </section>
        <div v-else class="observation" data-test="risk-observation">
          <section>
            <div class="reading">
              <div class="number">{{ orDash(risk.riskScore) }}<span class="unit"> / 100</span></div>
              <div class="reading-text">
                <h3>{{ riskLevelLabel(risk.riskLevel) }}</h3>
                <p class="muted">内部规则 · 最近 24 小时<br>生成于 {{ stampDateTime(risk.createTime) }}</p>
              </div>
            </div>
            <div class="meter" aria-hidden="true"><span :style="{ width: meterWidth(risk.riskScore) }"></span></div>
            <div class="metric-lines">
              <div v-for="line in riskLines" :key="line.name" class="metric-line">
                <div>{{ line.name }}<small class="muted" style="display:block">{{ line.note }}</small></div>
                <strong>{{ line.value }}</strong>
              </div>
            </div>
            <p class="label-note" style="margin-top:14px">规则分值用于事件归纳，不是安全保证；场馆评估中的登录失败仍为平台计数。</p>
          </section>
          <aside class="evidence-aside">
            <h3>快照依据</h3>
            <EvidenceBlock :facts="riskFacts" />
            <button class="link-button" type="button" data-test="snapshot-detail" @click="openSnapshot">查看快照详情</button>
          </aside>
        </div>
      </template>

      <!-- 运行指标 -->
      <template v-else-if="channel === 'metrics'">
        <div class="observation" data-test="metrics-observation">
          <section>
            <div class="context-line">DEV 环境 · {{ metricWindowText }}</div>
            <div v-if="!metrics.length" class="empty">
              <h2>当前范围没有观测记录</h2>
              <p>运行时还没有指标快照。可刷新指标取得新的观测。</p>
            </div>
            <div v-else class="metric-lines">
              <div v-for="metric in metrics" :key="metric.id" class="metric-line" :data-test="`metric-${metric.metricKey}`">
                <div><h3>{{ metricLabel(metric.metricKey) }}</h3><small class="muted">{{ metric.metricKey }} · {{ callStatusLabel(metric.status) }}</small></div>
                <div><strong>{{ orDash(metric.metricValue) }}</strong><small class="muted"> {{ metric.metricUnit || '' }}</small></div>
              </div>
            </div>
            <p v-for="gap in metricGaps" :key="gap" class="notice" data-test="metric-gap">{{ gap }}</p>
          </section>
          <aside class="evidence-aside">
            <h3>快照依据</h3>
            <EvidenceBlock :facts="metricFacts" />
            <button class="link-button" type="button" data-test="snapshot-detail" :disabled="!metrics.length" @click="openSnapshot">查看快照详情</button>
          </aside>
        </div>
      </template>

      <!-- 支付 / 通知渠道证据 -->
      <template v-else>
        <div class="observation" :data-test="`channel-${channel}-observation`">
          <section>
            <h3>{{ channel === 'payment' ? '开发支付证据' : 'SMTP 沙箱证据' }}</h3>
            <div class="reading">
              <div class="number">{{ channelHeadline.value }}<span class="unit"> {{ channelHeadline.unit }}</span></div>
              <div class="reading-text">
                <h3>{{ channelHeadline.title }}</h3>
                <p class="muted">{{ channelHeadline.note }}</p>
              </div>
            </div>
            <p class="notice">{{ channel === 'payment' ? '开发渠道调用成功只说明本次调用结果，不能证明真实资金结算或生产可用性。' : '渠道接受与最终送达、阅读分别记录。失败重试与投递批次请进入通知工作面。' }}</p>
            <a v-if="channel === 'notice'" class="btn" href="/system-admin/notice-delivery" data-test="goto-notice">查看通知投递与送达 →</a>
          </section>
          <aside class="evidence-aside">
            <h3>渠道登记</h3>
            <dl class="data-pairs">
              <div><dt>环境</dt><dd>{{ orDash(activeProvider?.providerEnv) }}</dd></div>
              <div><dt>启用状态</dt><dd>{{ activeProvider?.enabled ? '已启用' : '未启用' }}</dd></div>
              <div><dt>最近成功</dt><dd>{{ hmOrNone(activeProvider?.lastSuccessAt) }}</dd></div>
              <div><dt>最近失败</dt><dd>{{ hmOrNone(activeProvider?.lastFailureAt) }}</dd></div>
            </dl>
            <p class="label-note">状态来自服务端渠道登记与调用记录，不提供渠道启停或生产切换。</p>
          </aside>
        </div>
      </template>

      <!-- 调用时间线 -->
      <section class="timeline" data-test="timeline">
        <div class="spread">
          <div><h2>沿时间追溯调用</h2><p class="muted">当前渠道的调用来源、结果与快照关联。</p></div>
          <label class="row"><input v-model="showFailures" type="checkbox" data-test="failures-only" @change="loadChannel">只看失败</label>
        </div>
        <article v-for="call in calls" :key="call.id" class="call" :data-test="`call-${call.id}`">
          <time>{{ stampOf(call.occurredAt).day }}<br>{{ stampOf(call.occurredAt).time }}</time>
          <div>
            <strong>{{ operationTypeLabel(call.operationType) }}</strong>
            <span class="badge" :class="{ attention: callStatusAttention(call.status) }">{{ callStatusLabel(call.status) }}</span>
            <p>{{ sourceTypeLabel(call.sourceType) }} · 耗时 {{ orDash(call.durationMs) }} ms{{ call.failureCode ? ` · ${call.failureCode}` : '' }}</p>
          </div>
          <button class="btn" type="button" :data-test="`call-evidence-${call.id}`" @click="openCall(call)">查看证据</button>
        </article>
        <div v-if="!calls.length" class="call empty">
          <h3>{{ showFailures ? '当前没有失败调用' : '当前范围没有观测记录' }}</h3>
          <p>{{ showFailures ? '取消"只看失败"可查看其他结果。' : '该渠道还没有调用记录。刷新或等待定时任务后回到这里核对。' }}</p>
        </div>
      </section>
    </section>

    <dialog ref="dialogEl" class="c-modal" aria-labelledby="c-dialog-title" @close="onDialogClose">
      <form v-if="dialog" @submit.prevent="confirmDialog">
        <h2 id="c-dialog-title">{{ dialog.title }}</h2>

        <template v-if="dialog.kind === 'refresh'">
          <p>即将刷新：<strong>{{ activeChannel.name }}</strong></p>
          <p>范围：{{ refreshScopeText }}</p>
          <p class="muted">页面将保留已有证据，新的刷新结果单独显示。</p>
        </template>

        <template v-else-if="dialog.kind === 'location'">
          <p class="muted">{{ currentVenue?.name || '未知场馆' }} · 这是用于天气查询的观测点，不是用户定位。</p>
          <div class="form-field"><label for="pf-label">位置标签</label><input id="pf-label" v-model="form.label" data-test="pf-label" maxlength="64"></div>
          <div class="form-field"><label for="pf-lat">纬度</label><input id="pf-lat" v-model="form.latitude" type="text" inputmode="decimal" data-test="pf-lat"></div>
          <div class="form-field"><label for="pf-lng">经度</label><input id="pf-lng" v-model="form.longitude" type="text" inputmode="decimal" data-test="pf-lng"></div>
          <div class="form-field"><label for="pf-tz">时区</label><input id="pf-tz" v-model="form.timezone" type="text" maxlength="64" data-test="pf-tz"></div>
        </template>

        <template v-else-if="dialog.kind === 'snapshot'">
          <p>类型：{{ snapshotTitle }}</p>
          <p>编号：{{ snapshotFacts.snapshotNo }}</p>
          <p class="muted">{{ snapshotNote }}</p>
          <p class="notice">数据缺口保留在记录中，未知值不显示为零。</p>
        </template>

        <template v-else-if="dialog.kind === 'call' && dialog.call">
          <h3>{{ operationTypeLabel(dialog.call.operationType) }} · {{ callStatusLabel(dialog.call.status) }}</h3>
          <dl class="data-pairs">
            <div><dt>调用编号</dt><dd>{{ orDash(dialog.call.callNo) }}</dd></div>
            <div><dt>来源</dt><dd>{{ sourceTypeLabel(dialog.call.sourceType) }}</dd></div>
            <div><dt>耗时</dt><dd>{{ orDash(dialog.call.durationMs) }} ms</dd></div>
            <div><dt>失败码</dt><dd>{{ orDash(dialog.call.failureCode) }}</dd></div>
          </dl>
          <p class="muted">{{ callStatusLabel(dialog.call.status) === '失败'
            ? '本次未取得新快照。请保留失败证据，核对渠道状态后再决定是否刷新。'
            : '本次调用记录可关联快照，不包含原始第三方载荷。' }}</p>
        </template>

        <p v-if="dialog.error" class="error-text" role="alert" data-test="dialog-error">{{ dialog.error }}</p>
        <div class="row modal-actions">
          <button class="btn" type="button" data-test="dialog-cancel" @click="closeDialog">取消</button>
          <button
            v-if="dialog.submit"
            class="btn primary"
            type="submit"
            :disabled="dialog.busy"
            :data-test="`confirm-${dialog.kind}`"
          >{{ dialog.busy ? '处理中…' : dialog.submit }}</button>
          <button v-else class="btn primary" type="button" data-test="dialog-close-detail" @click="closeDialog">关闭详情</button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { providerApi } from '@/api/provider'
import type {
  OpsMetricSnapshotResponse,
  ProviderCallLog,
  ProviderRefreshResponse,
  ProviderStatusResponse,
  RiskSnapshotResponse,
  VenueObservationLocationResponse,
  WeatherSnapshotResponse
} from '@/api/provider'
import { venueApi } from '@/api/venue'
import type { VenueListItem } from '@/api/types'
import EvidenceBlock from './EvidenceBlock.vue'
import {
  PROVIDER_CHANNELS,
  callFilterParams,
  callStamp,
  callStatusAttention,
  callStatusLabel,
  channelBadge,
  channelLastCall,
  channelOf,
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
  weatherExpired,
  type ProviderChannel
} from './providersShared'
import { ISOLATED_REQUEST as isolated, formatHm } from '../adminShared'

type DialogKind = 'refresh' | 'location' | 'snapshot' | 'call'

interface DialogState {
  kind: DialogKind
  title: string
  submit: string
  error: string
  busy: boolean
  call?: ProviderCallLog
}

const route = useRoute()
const auth = useAuthStore()
const app = useAppStore()
const rootEl = ref<HTMLElement | null>(null)

const canObserve = computed(() => auth.hasPermission('provider:observe'))
const canRefresh = computed(() => auth.hasPermission('provider:refresh'))

const channel = ref<ProviderChannel>(initialChannelFromRoute(route?.hash ?? ''))
const surface = ref<'loading' | 'ready' | 'error'>('loading')
const providers = ref<ProviderStatusResponse[]>([])
const overviewGaps = ref<string[]>([])
const locations = ref<VenueObservationLocationResponse[]>([])
const venues = ref<VenueListItem[]>([])
const weather = ref<WeatherSnapshotResponse | null>(null)
const risk = ref<RiskSnapshotResponse | null>(null)
const metrics = ref<OpsMetricSnapshotResponse[]>([])
const calls = ref<ProviderCallLog[]>([])
const weatherVenueId = ref<number | null>(null)
const riskScope = ref('')
const showFailures = ref(false)
const refreshing = ref(false)
const refreshMessage = ref('')

const activeChannel = computed(() => channelOf(channel.value))
const activeProvider = computed(() => providerOf(providers.value, activeChannel.value.providerKey))
const venueOptions = computed(() => venueObservationOptions(
  venues.value.map((venue) => ({ id: venue.id, name: venue.name })),
  locations.value
))
const currentVenue = computed(() => venueOptions.value.find((option) => option.venueId === weatherVenueId.value) ?? null)
const refreshNoun = computed(() => ({ weather: '天气', risk: '风险', metrics: '指标' } as Record<string, string>)[channel.value] || '证据')
const weatherIsExpired = computed(() => weatherExpired(weather.value))
const weatherFreshnessText = computed(() => {
  if (!weather.value) return ''
  return weatherIsExpired.value
    ? `这条天气证据已于 ${hm(weather.value.expiresAt)} 过期。保留它用于追溯，当前天气需要取得新的观测。`
    : `最新天气证据采集于 ${hm(weather.value.observedAt)}，有效至 ${hm(weather.value.expiresAt)}。历史调用继续保留在下方。`
})
const metricWindowText = computed(() => metricWindow(metrics.value))
const metricGaps = computed(() => overviewGaps.value.filter((gap) => /慢查询|追踪|APM/i.test(gap)))
const weatherGaps = computed(() => overviewGaps.value.filter((gap) => /天气|SLA/i.test(gap)))
const riskLines = computed(() => {
  const snapshot = risk.value
  const scopeNote = riskScope.value ? '当前评估范围' : '平台计数'
  return [
    { name: '登录失败', value: countText(snapshot?.failedLoginCount, '次'), note: '平台计数' },
    { name: '支付失败', value: countText(snapshot?.paymentFailureCount, '次'), note: scopeNote },
    { name: '通知失败', value: countText(snapshot?.noticeFailureCount, '次'), note: scopeNote },
    { name: '未处理投诉', value: countText(snapshot?.openComplaintCount, '条'), note: scopeNote },
    { name: '高严重度投诉', value: countText(snapshot?.severeComplaintCount, '条'), note: scopeNote }
  ]
})
const channelHeadline = computed(() => {
  const provider = activeProvider.value
  const lastCall = calls.value[0]
  if (channel.value === 'payment') {
    return {
      value: orDash(lastCall?.durationMs),
      unit: 'ms',
      title: lastCall ? `最近调用${callStatusLabel(lastCall.status)}` : '暂无调用记录',
      note: lastCall ? `${operationTypeLabel(lastCall.operationType)} · ${hm(lastCall.occurredAt)}` : '渠道已登记，尚未产生调用'
    }
  }
  return {
    value: orDash(provider?.consecutiveFailures),
    unit: '次',
    title: (provider?.consecutiveFailures ?? 0) > 0 ? '连续调用失败' : '当前没有连续失败',
    note: provider?.lastFailureAt ? `最近失败 · ${hm(provider.lastFailureAt)}` : '最近没有失败记录'
  }
})

const weatherFacts = computed(() => weatherEvidence(weather.value))
const riskFacts = computed(() => riskEvidence(risk.value))
const metricFacts = computed(() => metricEvidence(metrics.value))
const snapshotFacts = computed(() => (channel.value === 'weather' ? weatherFacts.value : channel.value === 'risk' ? riskFacts.value : metricFacts.value))
const snapshotTitle = computed(() => ({ weather: '天气观测', risk: '内部风险评估', metrics: '运行指标' } as Record<string, string>)[channel.value] || '渠道证据')
const snapshotNote = computed(() => {
  if (channel.value === 'risk') {
    return risk.value?.windowStart && risk.value?.windowEnd
      ? `风险快照按内部规则汇总登录、支付、通知和投诉计数；本次评估窗口为 ${stampDateTime(risk.value.windowStart)} 至 ${stampDateTime(risk.value.windowEnd)}。`
      : '风险快照按内部规则汇总登录、支付、通知和投诉计数。服务端未返回评估窗口时按生成时间阅读。'
  }
  return '观测时间、配置版本、过期时间与置信度用于说明这条证据的来源和适用范围。'
})
const refreshScopeText = computed(() => {
  if (channel.value === 'metrics') return '平台运行时'
  if (channel.value === 'risk') return riskScope.value ? (venueOptions.value.find((option) => String(option.venueId) === riskScope.value)?.name ?? '所选场馆') : '全平台'
  return currentVenue.value?.name || '所选场馆'
})

onMounted(() => {
  void bootstrap()
})

function hm(value: string | null | undefined): string {
  return formatHm(value) || '—'
}

function hmOrNone(value: string | null | undefined): string {
  return value ? hm(value) : '暂无'
}

function countText(value: number | null | undefined, unit: string): string {
  return value === null || value === undefined ? '—' : `${value} ${unit}`
}

function stampOf(value: string | null | undefined): { day: string; time: string } {
  return callStamp(value)
}

function shortName(item: (typeof PROVIDER_CHANNELS)[number]): string {
  return providerOf(providers.value, item.providerKey)?.providerName || item.providerKey
}

function badgeOf(item: (typeof PROVIDER_CHANNELS)[number]) {
  return channelBadge(providerOf(providers.value, item.providerKey))
}

function lastCallOf(item: (typeof PROVIDER_CHANNELS)[number]) {
  return channelLastCall(providerOf(providers.value, item.providerKey))
}

function metricLabel(metricKey: string | undefined): string {
  const labels: Record<string, string> = {
    redis_ping_ms: 'Redis 连通耗时',
    redis_hit_rate: 'Redis 缓存命中率',
    db_ping_ms: '数据库连通耗时',
    jvm_memory_used_mb: 'JVM 已用内存'
  }
  return (metricKey && labels[metricKey]) || metricKey || '运行指标'
}

async function bootstrap(): Promise<void> {
  surface.value = 'loading'
  try {
    const [overview, locationList, venuePage] = await Promise.all([
      providerApi.overview(isolated),
      canObserve.value ? providerApi.locations(isolated) : Promise.resolve([] as VenueObservationLocationResponse[]),
      venueApi.list({ pageNum: 1, pageSize: 100 }, isolated)
    ])
    providers.value = overview.providers ?? []
    overviewGaps.value = overview.dataGaps ?? []
    locations.value = locationList ?? []
    venues.value = venuePage.records ?? []
    if (weatherVenueId.value == null) {
      const withLocation = venueOptions.value.find((option) => option.hasLocation)
      weatherVenueId.value = (withLocation ?? venueOptions.value[0])?.venueId ?? null
    }
    await loadChannel()
    surface.value = 'ready'
  } catch (error) {
    console.error('provider bootstrap failed', error)
    surface.value = 'error'
  }
}

async function loadChannel(): Promise<void> {
  if (!canObserve.value) return
  const domain = activeChannel.value.domain
  try {
    const [snapshotPage, callPage] = await Promise.all([
      channel.value === 'weather'
        ? (weatherVenueId.value != null
          ? providerApi.weatherSnapshots({ venueId: weatherVenueId.value, pageNum: 1, pageSize: 5 }, isolated)
          : Promise.resolve({ records: [], total: 0, pageNum: 1, pageSize: 5 }))
        : channel.value === 'risk'
          ? providerApi.riskSnapshots(riskScope.value ? { venueId: Number(riskScope.value), pageNum: 1, pageSize: 5 } : { pageNum: 1, pageSize: 5 }, isolated)
          : channel.value === 'metrics'
            ? providerApi.metricSnapshots({ pageNum: 1, pageSize: 20 }, isolated)
            : Promise.resolve({ records: [], total: 0, pageNum: 1, pageSize: 5 }),
      providerApi.calls(callFilterParams(showFailures.value, domain), isolated)
    ])
    const records = snapshotPage.records ?? []
    if (channel.value === 'weather') weather.value = (records[0] as WeatherSnapshotResponse) ?? null
    if (channel.value === 'risk') risk.value = (records[0] as RiskSnapshotResponse) ?? null
    if (channel.value === 'metrics') metrics.value = latestPerMetric(records as OpsMetricSnapshotResponse[])
    calls.value = callPage.records ?? []
  } catch (error) {
    console.error('provider channel load failed', error)
    app.showToast('这一渠道的证据暂时读不到，可稍后重新读取')
    calls.value = []
  }
}

/** 同一窗口内每个指标只保留最新一条（服务端按快照行返回）。 */
function latestPerMetric(rows: OpsMetricSnapshotResponse[]): OpsMetricSnapshotResponse[] {
  const byKey = new Map<string, OpsMetricSnapshotResponse>()
  for (const row of rows) {
    if (!row.metricKey) continue
    if (!byKey.has(row.metricKey)) byKey.set(row.metricKey, row)
  }
  return [...byKey.values()]
}

function switchChannel(next: ProviderChannel): void {
  channel.value = next
  refreshMessage.value = ''
  if (next === 'risk') riskScope.value = ''
  if (route && route.hash !== `#${next}`) void nextTick(() => history.replaceState(null, '', `#${next}`))
  void loadChannel()
  void nextTick(() => {
    ;(rootEl.value?.querySelector(`[data-test="channel-${next}"]`) as HTMLElement | null)?.focus()
  })
}

// ---------- 弹层 ----------

const dialogEl = ref<HTMLDialogElement | null>(null)
const dialog = ref<DialogState | null>(null)
let lastTrigger: HTMLElement | null = null
const form = reactive({ label: '', latitude: '', longitude: '', timezone: '' })

function openDialog(next: DialogState, trigger: Event | null): void {
  const target = trigger?.target
  lastTrigger = target instanceof HTMLElement ? target : (document.activeElement as HTMLElement | null)
  if (next.kind === 'location' && currentVenue.value) {
    form.label = currentVenue.value.label
    form.latitude = currentVenue.value.latitude
    form.longitude = currentVenue.value.longitude
    form.timezone = currentVenue.value.timezone
  }
  dialog.value = next
  void nextTick(() => dialogEl.value?.showModal())
}

function closeDialog(): void {
  dialogEl.value?.close()
}

function onDialogClose(): void {
  const trigger = lastTrigger
  dialog.value = null
  lastTrigger = null
  if (trigger?.isConnected) trigger.focus()
}

function openRefresh(event?: Event): void {
  if (channel.value === 'weather' && currentVenue.value && !currentVenue.value.hasLocation) {
    app.showToast('当前场馆缺少观测位置，请切换场馆或先维护位置')
    return
  }
  openDialog({ kind: 'refresh', title: '核对刷新范围', submit: '开始刷新', error: '', busy: false }, event ?? null)
}

function openLocation(event?: Event): void {
  openDialog({ kind: 'location', title: '场馆观测位置', submit: '保存观测位置', error: '', busy: false }, event ?? null)
}

function openSnapshot(event?: Event): void {
  openDialog({ kind: 'snapshot', title: '快照详情', submit: '', error: '', busy: false }, event ?? null)
}

function openCall(call: ProviderCallLog, event?: Event): void {
  openDialog({ kind: 'call', title: '调用证据', submit: '', error: '', busy: false, call }, event ?? null)
}

async function confirmDialog(): Promise<void> {
  const state = dialog.value
  if (!state || state.busy) return
  state.error = ''
  if (state.kind === 'location') {
    const invalid = validateLocation(form)
    if (invalid) {
      state.error = invalid
      return
    }
  }
  state.busy = true
  try {
    if (state.kind === 'refresh') await runRefresh()
    if (state.kind === 'location') await saveLocation()
  } catch (error) {
    state.busy = false
    state.error = readableError(error)
    return
  }
  state.busy = false
  closeDialog()
}

function readableError(error: unknown): string {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (message) return message
  return error instanceof Error && error.message ? error.message : '这次提交没有成功，内容已保留。请重试。'
}

async function runRefresh(): Promise<void> {
  refreshing.value = true
  refreshMessage.value = '正在执行本次刷新，已有快照仍可阅读。'
  let result: ProviderRefreshResponse | null = null
  try {
    result = channel.value === 'weather'
      ? await providerApi.refreshWeather(Number(weatherVenueId.value), isolated)
      : channel.value === 'risk'
        ? await providerApi.refreshRisk(riskScope.value ? Number(riskScope.value) : undefined, isolated)
        : await providerApi.refreshMetrics(isolated)
  } catch (error) {
    refreshMessage.value = `刷新失败：${readableError(error)}。已有证据保留，可再次刷新。`
    refreshing.value = false
    await loadChannel()
    throw new Error('刷新未完成')
  }
  refreshing.value = false
  refreshMessage.value = refreshNotice(result)
  await bootstrap()
  if ((result?.status ?? '').toUpperCase() === 'SUCCESS') app.showToast('刷新完成，证据已更新')
  else app.showToast('本次刷新未产生新快照')
}

async function saveLocation(): Promise<void> {
  if (weatherVenueId.value == null) throw new Error('尚未选择场馆')
  await providerApi.saveLocation(weatherVenueId.value, {
    locationLabel: form.label.trim(),
    latitude: Number(form.latitude),
    longitude: Number(form.longitude),
    timezone: form.timezone.trim() || 'Asia/Shanghai'
  }, isolated)
  app.showToast('观测位置已保存')
  await bootstrap()
}
</script>
