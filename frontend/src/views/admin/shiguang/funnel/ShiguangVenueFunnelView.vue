<template>
  <div ref="rootEl" class="sga-ops" data-test="sga-funnel-root">
    <div class="b-heading">
      <div>
        <div class="b-kicker">场馆运营 · 浏览与转化</div>
        <h1>场馆漏斗统计</h1>
        <p>沿着搜索、选时段与预约的路径，识别变化发生在哪一步，再结合数据缺口理解结果。</p>
      </div>
    </div>

    <form class="b-filter-form" data-test="funnel-filter" @submit.prevent="query">
      <!-- 字段结构与冻结参考 `B.field` 一致：div.b-field 内 label[for] 与控件同级（`.b-field label` 为 500 字重）。 -->
      <div class="b-field">
        <label for="f-start">开始日期</label>
        <input id="f-start" name="f-start" v-model="start" data-test="f-start" type="date" required>
      </div>
      <div class="b-field">
        <label for="f-end">结束日期</label>
        <input id="f-end" name="f-end" v-model="end" data-test="f-end" type="date" required>
      </div>
      <div class="b-field">
        <label for="f-source">来源渠道</label>
        <select id="f-source" name="f-source" v-model="source" data-test="f-source">
          <option value="">全部渠道</option>
          <option v-for="option in channelSelectOptions" :key="option.value" :value="option.value">
            {{ option.label }}{{ option.count === null ? '' : option.count ? `（${option.count} 次事件）` : '（当前范围无数据）' }}
          </option>
        </select>
      </div>
      <button class="btn primary" type="submit" data-test="f-query" :disabled="surface === 'loading'">
        {{ surface === 'loading' ? '读取中…' : '查询范围' }}
      </button>
      <!-- 参考把错误节点常驻在表单末尾（无错误时为空），这里保持同一形态。 -->
      <p class="b-error" role="alert" style="width: 100%; margin: 0" data-test="f-error">{{ filterError }}</p>
    </form>

    <section v-if="noVenue" class="b-state" data-test="funnel-no-venue">
      <h2>当前没有可分析的授权场馆</h2>
      <p>请先恢复授权场馆后再读取统计；原场馆数据已清空，不会沿用此前范围。</p>
      <button class="btn" type="button" data-test="funnel-recheck" @click="recheckVenues">重新核对场馆授权</button>
    </section>

    <section v-else-if="surface === 'loading'" class="b-state" aria-busy="true" data-test="funnel-loading">
      <h2>正在读取当前范围</h2>
      <p>九步会话队列与证据三栏将在加载后完整显示。</p>
    </section>

    <section v-else-if="surface === 'error'" class="b-state" role="alert" data-test="funnel-error">
      <h2>暂时无法读取漏斗数据</h2>
      <p>{{ loadError || '尚未取得本次结果，请重试。' }}</p>
      <button class="btn primary" type="button" data-test="funnel-retry" @click="bootstrap">重试读取</button>
    </section>

    <template v-else>
      <div class="b-section-title" style="margin: 24px 0 0">
        <div>
          <h2>{{ venueName }}</h2>
          <p>{{ queriedRange }} · {{ selectedChannelText }} · 读取于 {{ queriedAt }}</p>
        </div>
        <button class="b-link" type="button" data-test="funnel-metrics" @click="openMetrics($event)">统计口径</button>
      </div>

      <div class="b-funnel-layout">
        <section class="b-funnel-chart" :aria-label="`${measuredSteps.length} 步会话漏斗（${scopeLabel}）`">
          <div class="b-chart-head">
            <span>步骤</span><span>逐步交集会话数</span><span style="text-align: right">相邻转化</span>
          </div>
          <button
            v-for="(step, index) in steps"
            :key="step.eventName"
            class="b-step"
            :class="{ 'is-not-applicable': !isMeasured(step) }"
            type="button"
            :id="`f-step-${index}`"
            :data-test="`f-step-${index}`"
            :data-measurement="step.measurement ?? 'MEASURED'"
            :aria-pressed="selected === index"
            :aria-label="`${step.label}，${isMeasured(step) ? `${step.count} 个会话` : '本口径不适用'}，${rateText(step, index)}，查看说明`"
            @click="select(index)"
          >
            <span class="b-step-label">{{ step.label }}</span>
            <span class="b-step-meter">
              <span class="b-step-bar" :style="{ display: 'block', width: `${barPercent(step.count, baseCount)}%` }"></span>
              <span class="b-step-count" :data-test="`f-step-count-${index}`">{{ stepCountText(step) }}</span>
              <span v-if="!isMeasured(step)" class="b-step-hint" :data-test="`f-step-hint-${index}`">{{ NOT_APPLICABLE_HINT }}</span>
            </span>
            <span class="b-step-rate" :data-test="`f-step-rate-${index}`">{{ rateText(step, index) }}</span>
          </button>
          <p class="b-sub" style="margin-top: 17px">
            点击任一步查看计数含义。条长相对本口径首个可计量步骤，相邻转化相对上一个可计量步骤；两者都由服务端计算。
          </p>
        </section>
        <aside class="b-chart-aside" data-test="f-step-detail" aria-live="polite">
          <span class="b-kicker">正在查看</span>
          <h3>{{ currentStep?.label }}</h3>
          <div class="b-chart-focus" data-test="f-step-focus">{{ stepCountText(currentStep) }}</div>
          <p v-if="isMeasured(currentStep)">进入前 {{ measuredIndexBeforeSelected + 1 }} 个可计量步骤交集的会话数（{{ scopeLabel }}）。</p>
          <p v-else data-test="f-step-not-applicable">{{ currentStep?.notApplicableReason }}</p>
          <p v-if="isMeasured(currentStep) && !currentStep?.startStep">
            <strong>{{ rateText(currentStep, selected) }}</strong> 相邻转化<br>
            本步交集会话 / 上一个可计量步骤交集会话。
          </p>
          <p v-else-if="isMeasured(currentStep)">本步是当前口径的起点，不显示转化率。</p>
          <p class="b-reason">{{ stepNote }}</p>
        </aside>
      </div>

      <div class="b-evidence-grid">
        <section>
          <h2>已记录的流失原因</h2>
          <div v-for="row in dropoffRows" :key="row.reasonCode" class="b-fact-row">
            <span>{{ row.label }}</span><strong>{{ groupedInt(row.count) }} 次</strong>
          </div>
          <p v-if="!dropoffRows.length" class="muted">当前范围没有记录到流失原因。</p>
          <p class="b-sub">事件次数，不能直接与会话差值相抵。</p>
        </section>
        <section>
          <h2>来源与关键词</h2>
          <div v-for="row in sourceRows" :key="row.sourceChannel" class="b-fact-row">
            <span>{{ sourceChannelLabel(row.sourceChannel) }}</span><strong>{{ groupedInt(row.count) }} 次事件</strong>
          </div>
          <p v-if="!sourceRows.length" class="muted">当前范围没有来源记录。</p>
          <div style="margin-top: 18px">
            <span v-for="keyword in keywords" :key="keyword" class="b-keyword">{{ keyword }}</span>
          </div>
          <p v-if="!keywords.length" class="muted">有搜索行为后会在这里聚合关键词。</p>
        </section>
        <section>
          <h2>解读前先看数据缺口</h2>
          <ul class="b-gap-list">
            <li v-for="gap in dataGaps" :key="gap">{{ gap }}</li>
          </ul>
          <p v-if="!dataGaps.length" class="muted">服务端本次未下发数据缺口说明。</p>
        </section>
      </div>

      <section class="b-samples">
        <div class="b-section-title">
          <div>
            <h2>本次范围的样本事件</h2>
            <p>只展示必要的事件、结果与时间，不包含用户身份或原始载荷。</p>
          </div>
          <button class="btn" type="button" data-test="funnel-samples" @click="openSamples($event)">
            查看 {{ sampleEvents.length }} 条样本
          </button>
        </div>
      </section>

      <p class="b-page-bottom">
        九步按同一会话标识逐步取交集，会话不是去重人数；相邻转化与条长口径见「统计口径」。
        本面不补零、不折算人数，也不把站内来源当作外部广告归因。
      </p>
    </template>

    <dialog ref="dialogEl" class="b-dialog" aria-labelledby="b-dialog-title" @close="onDialogClose" @keydown="onDialogKeydown">
      <div class="b-dialog-head">
        <h2 id="b-dialog-title" data-test="funnel-dialog-title" tabindex="-1">{{ dialogTitle }}</h2>
        <button class="b-close" type="button" aria-label="关闭详情" data-test="funnel-dialog-close" @click="closeDialog">×</button>
      </div>
      <div class="b-dialog-body">
        <template v-if="dialog === 'metrics'">
          <dl class="b-definition">
            <template v-for="row in METRIC_DEFINITIONS" :key="row.term">
              <dt>{{ row.term }}</dt>
              <dd>{{ row.text }}</dd>
            </template>
          </dl>
        </template>
        <template v-else>
          <p class="muted">{{ venueName }} · {{ queriedRange }}</p>
          <p v-if="samplesLoading" class="muted" data-test="funnel-samples-loading">正在读取当前范围的样本事件…</p>
          <template v-else-if="samplesError">
            <p class="b-error" role="alert" data-test="funnel-samples-error">{{ samplesError }}</p>
            <button class="btn" type="button" data-test="funnel-samples-retry" @click="loadSamples">重试读取样本</button>
          </template>
          <div v-else-if="sampleRows.length" class="b-table-wrap" tabindex="0" aria-label="样本事件表">
            <table class="b-ledger" data-test="funnel-sample-table">
              <thead><tr><th>事件</th><th>阶段</th><th>结果</th><th>时间</th></tr></thead>
              <tbody>
                <tr v-for="row in sampleRows" :key="row.id ?? `${row.eventName}-${row.occurredAt}`">
                  <td>{{ stepLabel(row.eventName) }}</td>
                  <td>{{ stageLabel(row.eventStage) }}</td>
                  <td>{{ resultLabel(row.resultStatus) }}</td>
                  <td>{{ shortDateTime(row.occurredAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="muted">当前范围没有样本事件。</p>
          <p v-if="!samplesLoading && !samplesError" class="b-reason" style="margin-top: 22px">{{ sampleCaption(sampleRows.length) }}</p>
        </template>
      </div>
      <div class="b-dialog-foot">
        <button class="btn" type="button" data-test="funnel-dialog-back" @click="closeDialog">
          {{ dialog === 'metrics' ? '返回漏斗' : '返回当前范围' }}
        </button>
      </div>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { funnelAdminApi } from '@/api/funnel'
import { useAppStore } from '@/stores/app'
import { useVenueAccessStore } from '@/stores/venueAccess'
import { ISOLATED_REQUEST as isolated, restoreFocusChain } from '../adminShared'
import type { FunnelEventResponse, FunnelOverviewResponse, FunnelStepMetric } from '@/api/types'
import {
  ALL_SOURCE_CHANNELS,
  METRIC_DEFINITIONS,
  NOT_APPLICABLE_HINT,
  barPercent,
  channelOptions,
  firstDropIndex,
  firstMeasuredIndex,
  defaultWindow,
  isMeasured,
  resultLabel,
  sampleCaption,
  shortDateTime,
  sourceChannelLabel,
  stageLabel,
  stepLabel,
  stepCountText,
  stepNarrative,
  stepRateText
} from './funnelAdminShared'

type SurfaceState = 'loading' | 'ready' | 'error'

const app = useAppStore()
const venueAccess = useVenueAccessStore()
const rootEl = ref<HTMLElement | null>(null)
const dialogEl = ref<HTMLDialogElement | null>(null)

const surface = ref<SurfaceState>('loading')
const loadError = ref('')
const filterError = ref('')
const overview = ref<FunnelOverviewResponse | null>(null)
const selected = ref(0)
const queriedAt = ref('—')
const dialog = ref<'metrics' | 'samples' | null>(null)
const lastTrigger = ref<HTMLElement | null>(null)
const lastTriggerTest = ref<string | null>(null)
const restoring = ref(false)
/** 样本弹层的数据来自独立端点 `/admin/funnel/samples`（B4-F7：不留无调用方端点）。 */
const sampleRows = ref<FunnelEventResponse[]>([])
const samplesLoading = ref(false)
const samplesError = ref('')
/** 请求序号：过期响应直接丢弃，避免快速切换条件时后发先至（B4-F4）。 */
let requestSeq = 0
/** 样本请求单独计数：弹层重试与切换条件时同样丢弃过期响应。 */
let sampleSeq = 0

const today = defaultWindow(new Date())
const start = ref(today.start)
const end = ref(today.end)
const source = ref('')
/** 已加载结果对应的查询条件快照，用于「当前画面是哪一组条件」的展示（不随表单联动）。 */
const applied = ref({ start: today.start, end: today.end, source: '' })

const activeVenueId = computed(() => venueAccess.activeVenueId)
/**
 * 无授权场馆态（B4-F6：预览的 `venue='none'` 没有真实取值，这里用真实语义承接）。
 * 必须等场馆清单真正加载完再判定，否则首帧会把「还没读到清单」误显示成「没有授权场馆」。
 * 系统管理员的可管理清单是全部场馆（`VenueService.manageableVenues`），因此这里不会
 * 把一个有权看全部场馆的账号锁在外面。
 */
const noVenue = computed(() => (venueAccess.loaded || venueAccess.venues.length > 0) && venueAccess.venues.length === 0)
const venueName = computed(() =>
  venueAccess.venues.find((item) => item.id === activeVenueId.value)?.name ?? '未选择场馆'
)
const steps = computed<FunnelStepMetric[]>(() => {
  const rows = overview.value?.stepMetrics ?? []
  /* 服务端未标起点时，界面按当前口径的首个可计量步骤补齐，不给起点一个虚假转化率。 */
  const start = firstMeasuredIndex(rows)
  return rows.map((row, index) => ({
    ...row,
    label: stepLabel(row.eventName, row.label),
    measurement: row.measurement ?? 'MEASURED',
    startStep: row.startStep ?? index === start
  }))
})
const measuredSteps = computed(() => steps.value.filter((step) => isMeasured(step)))
/** 场馆口径只从场馆详情起计量；标题与旁栏都要说清当前是哪一口径（F-22）。 */
const scopeLabel = computed(() => (measuredSteps.value.length === steps.value.length ? '平台口径' : '场馆口径'))
const baseCount = computed(() => steps.value[firstMeasuredIndex(steps.value)]?.count ?? 0)
const currentStep = computed<FunnelStepMetric | null>(() => steps.value[selected.value] ?? null)
const measuredIndexBeforeSelected = computed(() => {
  const upto = steps.value.slice(0, selected.value + 1).filter((step) => isMeasured(step)).length
  return Math.max(upto - 1, 0)
})
const stepNote = computed(() => stepNarrative(currentStep.value, measuredIndexBeforeSelected.value))
const dropoffRows = computed(() => overview.value?.dropoffReasons ?? [])
const sourceRows = computed(() => overview.value?.sourceChannels ?? [])
const keywords = computed(() => overview.value?.topKeywords ?? [])
const dataGaps = computed(() => overview.value?.dataGaps ?? [])
const sampleEvents = computed<FunnelEventResponse[]>(() => overview.value?.sampleEvents ?? [])
const channelSelectOptions = computed(() =>
  channelOptions(sourceRows.value, ALL_SOURCE_CHANNELS, applied.value.source === '')
)
const queriedRange = computed(() => `${applied.value.start} 至 ${applied.value.end}`)
const selectedChannelText = computed(() =>
  applied.value.source ? sourceChannelLabel(applied.value.source) : '全部渠道'
)
const dialogTitle = computed(() => (dialog.value === 'metrics' ? '统计口径' : '当前范围 · 样本事件'))

function rateText(step: FunnelStepMetric | null, index: number): string {
  if (!step) return '—'
  /* 分母取上一个「可计量」步骤，而不是数组前一项：场馆口径的前两步没有数值可作分母。 */
  const previousMeasured = steps.value.slice(0, index).filter((row) => isMeasured(row)).pop()?.count ?? null
  return stepRateText(step, previousMeasured)
}

function groupedInt(value: number | null | undefined): string {
  return Number(value ?? 0).toLocaleString('zh-CN')
}

function readableError(error: unknown, fallback: string): string {
  // 服务端 400 文案（如「漏斗查询开始日期不能晚于结束日期」）在 response.data.message 里（B4-F5）。
  const response = (error as { response?: { data?: { message?: string } } })?.response
  return response?.data?.message || (error as Error)?.message || fallback
}

/* --------------------------------------------------------------- 数据加载 */

async function loadOverview(): Promise<void> {
  const seq = ++requestSeq
  // 切换条件或场馆时先清空旧结果，失败或竞态下不留上一组条件的数据（B4-F3/F4）。
  overview.value = null
  surface.value = 'loading'
  loadError.value = ''
  try {
    const response = await funnelAdminApi.overview(
      {
        startDate: start.value,
        endDate: end.value,
        ...(source.value ? { sourceChannel: source.value } : {}),
        ...(activeVenueId.value ? { venueId: activeVenueId.value } : {})
      },
      isolated
    )
    if (seq !== requestSeq) return
    overview.value = response
    applied.value = { start: start.value, end: end.value, source: source.value }
    // 默认停在第一个出现下降的步骤（变化发生处；无下降时回到起点），见 firstDropIndex 注释。
    selected.value = firstDropIndex((response.stepMetrics ?? []).map((row) => row.count))
    queriedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    surface.value = 'ready'
  } catch (error) {
    if (seq !== requestSeq) return
    surface.value = 'error'
    loadError.value = readableError(error, '漏斗数据暂时无法读取。')
    app.showToast(readableError(error, '漏斗数据读取失败。'))
  }
}

async function bootstrap(): Promise<void> {
  if (!venueAccess.venues.length) {
    try {
      await venueAccess.refresh()
    } catch {
      // 场馆清单读不到时由无授权场馆状态承接
    }
  }
  if (!activeVenueId.value) {
    overview.value = null
    surface.value = 'ready'
    return
  }
  await loadOverview()
}

function query(): void {
  filterError.value = ''
  if (!start.value || !end.value) {
    filterError.value = '请选择开始与结束日期。'
    return
  }
  // 前端先拦一次，服务端仍有同一校验并返回原文（B4-F5）。
  if (end.value < start.value) {
    filterError.value = '结束日期不能早于开始日期。'
    return
  }
  void loadOverview()
}

function select(index: number): void {
  selected.value = index
}

function recheckVenues(): void {
  void (async () => {
    try {
      await venueAccess.refresh()
      app.showToast(venueAccess.venues.length ? '已重新核对场馆授权。' : '已重新核对，当前仍没有授权场馆。')
      await bootstrap()
    } catch (error) {
      app.showToast(readableError(error, '场馆授权核对失败，请稍后重试。'))
    }
  })()
}

/* ----------------------------------------------------------------- 弹层 */

function openMetrics(event?: Event): void {
  rememberTrigger(event, 'funnel-metrics')
  dialog.value = 'metrics'
  void nextTick(() => dialogEl.value?.showModal())
}

function openSamples(event?: Event): void {
  rememberTrigger(event, 'funnel-samples')
  dialog.value = 'samples'
  void nextTick(() => dialogEl.value?.showModal())
  void loadSamples()
}

/**
 * 样本弹层直接消费 `/admin/funnel/samples`（B4-F7：端点已鉴权齐备但此前无调用方）。
 * 条件与总览保持同一快照（applied），失败时只显示错误与重试，不用总览的内联样本顶替。
 */
async function loadSamples(): Promise<void> {
  const seq = ++sampleSeq
  samplesLoading.value = true
  samplesError.value = ''
  sampleRows.value = []
  try {
    const rows = await funnelAdminApi.samples(
      {
        startDate: applied.value.start,
        endDate: applied.value.end,
        ...(applied.value.source ? { sourceChannel: applied.value.source } : {}),
        ...(activeVenueId.value ? { venueId: activeVenueId.value } : {})
      },
      isolated
    )
    if (seq !== sampleSeq) return
    sampleRows.value = rows ?? []
  } catch (error) {
    if (seq !== sampleSeq) return
    samplesError.value = readableError(error, '样本事件暂时无法读取。')
  } finally {
    if (seq === sampleSeq) samplesLoading.value = false
  }
}

function closeDialog(): void {
  dialogEl.value?.close()
}

function rememberTrigger(event: Event | undefined, test: string): void {
  const node = (event?.currentTarget as HTMLElement | undefined) ?? (document.activeElement as HTMLElement | null)
  lastTrigger.value = node ?? null
  lastTriggerTest.value = test
}

function restoreTriggerFocus(): void {
  restoreFocusChain({
    rootSelector: '.sga-ops',
    trigger: lastTrigger.value,
    triggerTest: lastTriggerTest.value,
    tabSelector: '.sga-ops .b-link'
  })
}

function onDialogClose(): void {
  dialog.value = null
  if (restoring.value) return
  restoreTriggerFocus()
}

function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Tab' || !dialogEl.value) return
  const nodes = [...dialogEl.value.querySelectorAll<HTMLElement>(
    'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
  )].filter((node) => !node.hasAttribute('disabled') && node.offsetParent !== null)
  if (!nodes.length) return
  const first = nodes[0]
  const last = nodes[nodes.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

// 场馆切换（含切到无授权）：清空旧数据并按新场馆重查，避免把上一个场馆的数字留在屏幕上。
watch(() => venueAccess.scopeChangedAt, () => {
  dialog.value = null
  dialogEl.value?.close()
  overview.value = null
  // 样本是场馆/条件范围内的局部记录，切换后必须作废，避免弹层重开时回放上一个场馆的样本。
  sampleSeq += 1
  sampleRows.value = []
  samplesError.value = ''
  samplesLoading.value = false
  void bootstrap()
})

onMounted(() => {
  void bootstrap()
})

defineExpose({ surface, overview, selected, steps, start, end, source, dialog, applied, sampleRows, samplesLoading, samplesError, loadSamples })
</script>
