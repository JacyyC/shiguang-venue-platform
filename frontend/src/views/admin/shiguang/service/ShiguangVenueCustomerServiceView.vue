<template>
  <div ref="rootEl" class="sga-service" data-test="sga-service-root">
    <div class="a-topline">
      <div>
        <p class="a-eyebrow">场馆运营 / 客服与反馈</p>
        <h1>把每一次反馈，接着处理好。</h1>
        <p class="a-intro">从用户的原始问题开始，保留沟通与处理上下文，让下一步回应有依据。</p>
      </div>
    </div>

    <div class="a-tabs" role="group" aria-label="客服任务">
      <button
        v-for="item in TABS"
        :key="item.key"
        type="button"
        class="a-tab"
        :data-test="`tab-${item.key}`"
        :aria-pressed="view === item.key"
        @click="switchView(item.key)"
      >
        {{ item.label }}
      </button>
    </div>

    <section v-if="surface === 'loading'" class="a-state" aria-busy="true" data-test="surface-loading">
      <h2>正在加载当前场馆反馈</h2>
      <p class="a-muted">保留场馆范围，加载完成后继续处理。</p>
      <button class="a-btn" type="button" data-test="surface-reload" @click="bootstrap">重新读取</button>
    </section>

    <section v-else-if="surface === 'error'" class="a-state" role="alert" data-test="surface-error">
      <h2>反馈暂时无法加载</h2>
      <p class="a-muted">请求未完成，当前状态不是“没有反馈”。</p>
      <button class="a-btn primary" type="button" data-test="surface-reload" @click="bootstrap">重试读取</button>
    </section>

    <section v-else-if="surface === 'lock'" class="a-state" data-test="surface-lock">
      <h2>没有可处理的场馆</h2>
      <p class="a-muted">请从顶部选择已授权场馆。场馆恢复后，可继续查看对应反馈。</p>
      <button class="a-btn" type="button" data-test="surface-recheck" @click="bootstrap">重新检查场馆</button>
    </section>

    <template v-else>
      <section v-if="view === 'cases'" data-test="pane-cases">
        <div v-if="!canManage" class="a-state" role="status" data-test="denied-cases">
          <h2>未获得工单处理权限</h2>
          <p>当前可以前往评价审核，工单正文不展示。</p>
        </div>
        <div v-else class="a-workspace" :class="{ 'show-detail': mobileDetail }">
        <aside class="a-queue" aria-label="工单队列">
          <div class="a-queue-head">
            <div class="a-row">
              <h2>当前队列</h2>
              <span class="a-muted">{{ queue.length }} 单</span>
            </div>
            <label class="a-field">
              查找反馈
              <input
                v-model.trim="query"
                data-test="case-search"
                placeholder="标题或工单编号"
                @input="scheduleQueueReload"
              >
            </label>
            <label>
              处理状态
              <select v-model="statusFilter" data-test="case-filter" @change="bootstrap">
                <option value="all">全部状态</option>
                <option v-for="code in CASE_STATUS_ORDER" :key="code" :value="code">{{ caseStatusLabel(code) }}</option>
              </select>
            </label>
          </div>
          <template v-if="queue.length">
            <button
              v-for="row in queue"
              :key="row.case.id"
              type="button"
              class="a-queue-item"
              :data-test="`case-${row.case.id}`"
              :aria-pressed="row.case.id === selectedCaseId"
              @click="selectCase(row.case.id)"
            >
              <div class="a-row">
                <span class="a-pill" :class="{ attention: queueAttention(row) }">{{ caseStatusLabel(row.case.status) }}</span>
                <span class="a-muted">{{ queuePriority(row) }}优先级</span>
              </div>
              <h3>{{ queueTitle(row) }}</h3>
              <p>{{ row.case.caseNo }} · {{ dayTimeLabel(row.case.openedAt) }}</p>
            </button>
          </template>
          <template v-else>
            <p class="a-muted">没有匹配的工单。</p>
            <button class="a-btn" type="button" data-test="clear-filters" @click="clearFilters">清除筛选</button>
          </template>
        </aside>

        <section
          v-if="selectedRow"
          id="case-detail"
          class="a-case"
          tabindex="-1"
          :data-test="`case-detail-${selectedRow.case.id}`"
        >
          <button class="a-btn a-mobile-back" type="button" data-test="back-to-queue" @click="backToQueue">← 返回队列</button>
          <p class="a-miniheading">{{ selectedRow.case.caseNo }} · {{ complaintTypeLabel(selectedRow.complaint?.complaintType) }}</p>
          <h2>{{ queueTitle(selectedRow) }}</h2>
          <p class="a-meta">
            {{ venueName }}<br>
            {{ dayTimeLabel(selectedRow.case.openedAt) }} 提交 · {{ assigneeText(selectedRow.case) }}
          </p>
          <p>{{ selectedRow.complaint?.contentSanitized || '投诉正文未随工单返回。' }}</p>
          <ol v-if="timeline.length" class="a-timeline" aria-label="处理记录">
            <li v-for="(event, index) in timeline" :key="event.id ?? index" :data-test="`timeline-${index}`">
              <strong>{{ timelineLabel(event) }}</strong> <small>{{ dayTimeLabel(event.occurredAt) }}</small>
              <p>{{ event.noteSanitized || statusChangeText(event) }}</p>
              <p v-if="event.internalNoteSanitized" class="a-muted">内部备注 · 仅授权后台人员可见：{{ event.internalNoteSanitized }}</p>
            </li>
          </ol>
          <p v-else class="a-muted" data-test="timeline-empty">这条工单还没有处理记录。</p>

          <form v-if="!isTerminalCase(selectedRow.case.status)" data-test="reply-form" @submit.prevent="openReplyConfirm">
            <label class="a-field">
              给用户的回复
              <textarea
                v-model="form.replyText"
                data-test="reply-text"
                required
                placeholder="说明已核对的情况，以及接下来会怎样处理。"
              ></textarea>
            </label>
            <label class="a-field">
              内部备注 <span class="a-muted">仅授权后台人员可见</span>
              <input v-model="form.internalNote" data-test="internal-note" placeholder="记录现场核对或交接信息">
            </label>
            <div class="a-row">
              <button class="a-btn primary" type="submit" data-test="send-reply">发送回复</button>
              <button class="a-btn" type="button" data-test="open-resolve" @click="openResolve($event)">标记解决</button>
            </div>
          </form>
          <template v-else>
            <div class="a-notice">
              <strong>{{ caseStatusLabel(selectedRow.case.status) }}</strong>
              <p style="margin: 8px 0 0">处理记录继续保留。若问题仍然存在，可填写原因重新打开。</p>
            </div>
            <div class="a-row">
              <button class="a-btn" type="button" data-test="open-reopen" @click="openReason('reopen', $event)">重开工单</button>
              <button
                v-if="selectedRow.case.status === 'RESOLVED'"
                class="a-btn"
                type="button"
                data-test="open-close"
                @click="openReason('close', $event)"
              >
                关闭工单
              </button>
            </div>
          </template>
        </section>
        <section v-else class="a-case">
          <h2>没有匹配的工单</h2>
          <p>调整队列条件后继续。</p>
        </section>

        <aside v-if="selectedRow" class="a-side">
          <section>
            <h3>来源与关联</h3>
            <dl>
              <dt>来源</dt>
              <dd>{{ complaintSourceText(selectedRow.complaint) }}</dd>
              <dt>关联对象</dt>
              <dd>{{ complaintLinkText(selectedRow.complaint) }}</dd>
              <dt>处理人</dt>
              <dd>{{ assigneeText(selectedRow.case) }}</dd>
            </dl>
            <button class="a-btn" type="button" data-test="open-assign" @click="openAssign($event)">
              {{ selectedRow.case.assignedAt ? '调整分派' : '分派处理人' }}
            </button>
          </section>
          <section>
            <h3>后续处理</h3>
            <p v-for="flag in flagsOf(selectedRow.case)" :key="flag" :data-test="`flag-${flag}`">{{ flag }}</p>
            <p v-if="!flagsOf(selectedRow.case).length" class="a-muted">暂未标记人工复核</p>
            <p class="a-muted">标记用于人工协作，退款、发券和现场操作各自审批处理。</p>
          </section>
          <section>
            <h3>当前进度</h3>
            <span class="a-pill" data-test="case-status">{{ caseStatusLabel(selectedRow.case.status) }}</span>
            <button
              v-if="canMoveToInProgress(selectedRow.case.status)"
              class="a-btn"
              type="button"
              data-test="open-in-progress"
              @click="openReason('in-progress', $event)"
            >
              转处理中
            </button>
            <button class="a-btn" type="button" data-test="open-close-side" @click="openReason('close', $event)">关闭工单</button>
          </section>
        </aside>
        </div>
      </section>

      <section v-else-if="view === 'reviews'" data-test="pane-reviews">
        <div v-if="!canReview" class="a-state" role="status" data-test="denied-reviews">
          <h2>未获得评价审核权限</h2>
          <p>评价正文和审核操作不向当前角色开放。</p>
        </div>
        <div v-else-if="reviewList.length" class="a-reviews">
          <div class="a-topline">
            <div>
              <h2>先读完内容，再决定如何公开。</h2>
              <p class="a-muted">评价按本场馆范围呈现，审核需要写明原因。</p>
            </div>
          </div>
          <article v-for="review in reviewList" :key="review.id" class="a-review" :data-test="`review-${review.id}`">
            <div class="a-rating">
              {{ ratingText(review) }}<br><small>/ 5 分</small>
            </div>
            <div>
              <div class="a-row">
                <span class="a-pill">{{ reviewStatusLabel(review.status) }}</span>
                <span class="a-muted">{{ reviewMeta(review) }}</span>
              </div>
              <p style="margin-top: 16px">{{ review.contentSanitized || '（未填写文字评价）' }}</p>
              <p class="a-muted">{{ reviewDetailLine(review) }}</p>
              <div class="a-row">
                <button class="a-btn" type="button" :data-test="`open-moderate-${review.id}`" @click="openModerate(review, $event)">
                  审核评价
                </button>
              </div>
            </div>
          </article>
        </div>
        <div v-else class="a-state" data-test="reviews-empty">
          <h2>这个场馆暂时没有评价</h2>
          <p class="a-muted">新的评价会按授权范围出现在这里。</p>
        </div>
      </section>

      <section v-else data-test="pane-context">
        <div v-if="!canAnalytics" class="a-state" role="status" data-test="denied-context">
          <h2>未获得客服分析权限</h2>
          <p>关联上下文需要单独授权。</p>
        </div>
        <div v-else class="a-text-block">
          <h2>{{ venueName }}的关联线索</h2>
          <p class="a-muted">沿投诉找到工单与评价，用于人工核对；计数按当前场馆全量统计，不随队列筛选变化。</p>
          <div class="a-row" style="gap: 35px">
            <span class="a-muted">投诉 {{ context.complaintCount }}</span>
            <span class="a-muted">工单 {{ context.caseCount }}</span>
            <span class="a-muted">评价 {{ context.reviewCount }}</span>
          </div>
          <article v-for="row in contextQueue" :key="String(row.complaint.id)" class="a-review" data-test="context-row">
            <div><span class="a-pill">{{ complaintTypeLabel(String(row.complaint.complaintType ?? '')) }}</span></div>
            <div>
              <h3>{{ String(row.complaint.title ?? '（无标题）') }}</h3>
              <p>{{ String(row.complaint.complaintNo ?? '') }}</p>
              <p class="a-meta">{{ contextLinkText(row) }}</p>
              <button
                v-if="row.cases.length"
                class="a-linkbtn"
                type="button"
                :data-test="`context-case-${String(row.cases[0].id)}`"
                @click="enterCase(Number(row.cases[0].id), $event)"
              >
                进入工单上下文 →
              </button>
            </div>
          </article>
          <p v-if="!contextQueue.length" class="a-muted" data-test="context-empty">当前场馆还没有可关联的投诉线索。</p>
        </div>
      </section>
    </template>

    <dialog
      ref="dialogEl"
      class="a-action-dialog"
      aria-labelledby="dialog-title"
      @close="onDialogClose"
      @keydown="onDialogKeydown"
    >
      <form data-test="action-form" @submit.prevent="confirmDialog">
        <h2 id="dialog-title">{{ dialog?.title }}</h2>

        <template v-if="dialog?.kind === 'assign'">
          <label class="a-field">
            处理人 ID
            <input v-model.number="form.assignedUserId" name="owner" type="number" min="1" required data-test="field-owner">
          </label>
          <p class="a-muted">本次分派保留在该工单的处理记录中。</p>
        </template>

        <template v-else-if="dialog?.kind === 'reply'">
          <p data-test="reply-preview">{{ dialog.bodyText }}</p>
          <p class="a-muted">内部备注会单独保存，不显示给用户。</p>
        </template>

        <template v-else-if="dialog?.kind === 'resolve'">
          <label class="a-field">
            根因分类
            <select v-model="form.rootCauseCode" name="root" data-test="field-root">
              <option v-for="(label, code) in ROOT_CAUSE_LABELS" :key="code" :value="code">{{ label }}</option>
            </select>
          </label>
          <label class="a-field">
            处理类型
            <select v-model="form.resolutionType" name="type" data-test="field-type">
              <option v-for="(label, code) in RESOLUTION_TYPE_LABELS" :key="code" :value="code">{{ label }}</option>
            </select>
          </label>
          <label class="a-field">
            用户可见处理说明
            <textarea v-model="form.reason" name="reason" required data-test="field-reason" placeholder="记录已完成的核对与处理"></textarea>
          </label>
          <label v-for="flag in FOLLOW_UP_FLAGS" :key="flag.key" class="a-check">
            <input v-model="form[flag.key]" type="checkbox" name="flags" :value="flag.label" :data-test="`field-flag-${flag.key}`">
            {{ flag.label }}
          </label>
          <p class="a-muted">此处只记录协作需求，不执行退款、发券或现场处置。</p>
        </template>

        <template v-else-if="dialog?.kind === 'moderate'">
          <p>{{ dialog.bodyText }}</p>
          <label class="a-field">
            审核动作
            <select v-model="form.moderation" name="moderation" data-test="field-moderation">
              <option v-for="option in MODERATION_ACTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
          <label class="a-field">
            审核原因
            <textarea v-model="form.reason" name="reason" required data-test="field-reason" placeholder="说明决定的依据"></textarea>
          </label>
        </template>

        <template v-else-if="dialog?.kind === 'close' || dialog?.kind === 'reopen' || dialog?.kind === 'in-progress'">
          <label class="a-field">
            {{ dialog.kind === 'close' ? '关闭' : dialog.kind === 'reopen' ? '重开' : '处理' }}说明
            <textarea v-model="form.reason" name="reason" required data-test="field-reason"></textarea>
          </label>
          <p class="a-muted">原因将保留在处理记录中。</p>
        </template>

        <p v-if="dialog?.error" class="a-error" role="alert" data-test="dialog-error">{{ dialog.error }}</p>

        <div class="a-dialog-actions">
          <button class="a-btn" type="button" data-test="dialog-cancel" @click="closeDialog">取消</button>
          <button v-if="dialog?.submit" class="a-btn primary" type="submit" data-test="dialog-confirm" :disabled="dialog.busy">
            {{ dialog.busy ? '处理中…' : dialog.submit }}
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useVenueAccessStore } from '@/stores/venueAccess'
import {
  feedbackApi,
  type CustomerComplaint,
  type CustomerServiceAdminCaseEvent,
  type CustomerServiceAdminContext,
  type CustomerServiceCase,
  type VenueReview
} from '@/api/feedback'
import { ISOLATED_REQUEST as isolated, restoreFocusChain } from '../adminShared'
import {
  CASE_STATUS_ORDER,
  FOLLOW_UP_FLAGS,
  MODERATION_ACTIONS,
  RESOLUTION_TYPE_LABELS,
  ROOT_CAUSE_LABELS,
  assigneeText,
  canCloseCase,
  canMoveToInProgress,
  caseEventTypeLabel,
  caseIdFromHash,
  caseStatusLabel,
  complaintTypeLabel,
  contextRows,
  dayTimeLabel,
  filterQueueByKeyword,
  flagSelection,
  flagsOf,
  initialViewFromHash,
  isTerminalCase,
  queueAttention,
  queuePriority,
  queueRows,
  queueTitle,
  ratingText,
  reviewDetailLine,
  reviewMeta,
  reviewStatusLabel,
  statusChangeText,
  canReopenCase,
  canResolveCase,
  type ServiceView
} from './serviceShared'

type SurfaceState = 'loading' | 'ready' | 'error' | 'lock'
type DialogKind = 'assign' | 'reply' | 'resolve' | 'close' | 'reopen' | 'in-progress' | 'moderate'

interface DialogState {
  kind: DialogKind
  title: string
  submit: string
  error: string
  busy: boolean
  bodyText: string
  reviewId: number | null
}

const TABS: Array<{ key: ServiceView; label: string }> = [
  { key: 'cases', label: '工单处理' },
  { key: 'reviews', label: '评价审核' },
  { key: 'context', label: '关联上下文' }
]

const route = useRoute()
const auth = useAuthStore()
const app = useAppStore()
const venueAccess = useVenueAccessStore()
const rootEl = ref<HTMLElement | null>(null)
const dialogEl = ref<HTMLDialogElement | null>(null)
let lastTrigger: HTMLElement | null = null
let lastTriggerTest: string | null = null
let searchTimer: ReturnType<typeof setTimeout> | null = null

const canManage = computed(() => auth.hasPermission('customer-service:manage'))
const canReview = computed(() => auth.hasPermission('review:manage'))
const canAnalytics = computed(() => auth.hasPermission('customer-service:analytics'))

const view = ref<ServiceView>(initialViewFromHash(typeof route?.hash === 'string' ? route.hash : ''))
const surface = ref<SurfaceState>('loading')
const cases = ref<CustomerServiceCase[]>([])
const complaints = ref<CustomerComplaint[]>([])
const timeline = ref<CustomerServiceAdminCaseEvent[]>([])
const reviewList = ref<VenueReview[]>([])
const context = ref<CustomerServiceAdminContext>({})
const selectedCaseId = ref<number | null>(null)
const mobileDetail = ref(false)
const query = ref('')
const statusFilter = ref('all')
const dialog = ref<DialogState | null>(null)

const form = reactive({
  replyText: '',
  internalNote: '',
  assignedUserId: undefined as number | undefined,
  rootCauseCode: 'SERVICE_EXECUTION',
  resolutionType: 'EXPLAINED',
  reason: '',
  moderation: 'FLAG',
  requiresRefundReview: false,
  requiresCouponReview: false,
  requiresSiteFollowUp: false
})

const activeVenueId = computed(() => venueAccess.activeVenueId)
const venueName = computed(() => venueAccess.venues.find((item) => item.id === activeVenueId.value)?.name ?? '未选择场馆')

const queue = computed(() => queueRows(cases.value, complaints.value))

const selectedRow = computed(() => {
  const list = queue.value
  if (!list.length) return null
  return list.find((row) => row.case.id === selectedCaseId.value) ?? list[0]
})

const contextQueue = computed(() => contextRows(context.value))

onMounted(() => {
  const deep = caseIdFromHash(typeof route?.hash === 'string' ? route.hash : '')
  if (deep) selectedCaseId.value = deep
  void bootstrap()
})

watch(
  () => venueAccess.scopeChangedAt,
  () => {
    void bootstrap()
  }
)

function readableError(error: unknown, fallback: string): string {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message && message.trim() ? message : fallback
}

/**
 * 队列读取：`AdminCase` 无标题与正文，工单与投诉必须并发取后按 `complaintId` 关联（B3-T24）；
 * 关键词按真实双查询取并集——工单侧匹配 `case_no`/`customer_visible_reply`，投诉侧匹配 `title`/`content_sanitized`（B3-T25）。
 * 缺 `customer-service:manage` 时一个工单请求都不发；缺评价与分析权限时同理。
 */
async function bootstrap(): Promise<void> {
  surface.value = 'loading'
  if (!venueAccess.venues.length) {
    try {
      await venueAccess.refresh()
    } catch {
      // 壳层锁定态兜底
    }
  }
  const venueId = activeVenueId.value
  if (!venueId) {
    surface.value = venueAccess.venues.length ? 'error' : 'lock'
    return
  }
  const scope = { venueId, pageNum: 1, pageSize: 100 }
  const statusScope = statusFilter.value === 'all' ? scope : { ...scope, status: statusFilter.value }
  const emptyPage = { records: [] as never[], total: 0, pageNum: 1, pageSize: 100 }
  try {
    const [casePage, complaintPage, reviewPage, contextPage, keywordCasePage, keywordComplaintPage] = await Promise.all([
      canManage.value ? feedbackApi.adminCases(statusScope, isolated) : Promise.resolve({ ...emptyPage, records: [] as CustomerServiceCase[] }),
      canManage.value ? feedbackApi.adminComplaints(scope, isolated) : Promise.resolve({ ...emptyPage, records: [] as CustomerComplaint[] }),
      canReview.value ? feedbackApi.adminReviews(scope, isolated) : Promise.resolve({ ...emptyPage, records: [] as VenueReview[] }),
      canAnalytics.value ? feedbackApi.adminContext(scope, isolated) : Promise.resolve({} as CustomerServiceAdminContext),
      canManage.value && query.value ? feedbackApi.adminCases({ ...statusScope, keyword: query.value }, isolated) : Promise.resolve(null),
      canManage.value && query.value ? feedbackApi.adminComplaints({ ...scope, keyword: query.value }, isolated) : Promise.resolve(null)
    ])
    cases.value = casePage.records
    complaints.value = complaintPage.records
    reviewList.value = reviewPage.records
    context.value = contextPage ?? {}
    if (query.value) {
      const rows = queueRows(cases.value, complaints.value)
      cases.value = filterQueueByKeyword(rows, keywordCasePage?.records ?? [], keywordComplaintPage?.records ?? []).map((row) => row.case)
    }
    if (!queue.value.some((row) => row.case.id === selectedCaseId.value)) selectedCaseId.value = queue.value[0]?.case.id ?? null
    await loadCaseScoped()
    surface.value = 'ready'
  } catch (error) {
    surface.value = 'error'
    app.showToast(readableError(error, '客服数据暂时无法读取。'))
  }
}

async function loadCaseScoped(): Promise<void> {
  const caseId = selectedRow.value?.case.id
  if (!canManage.value || caseId == null) {
    timeline.value = []
    return
  }
  timeline.value = await feedbackApi.adminCaseEvents(caseId, isolated).catch(() => [] as CustomerServiceAdminCaseEvent[])
}

function scheduleQueueReload(): void {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    void bootstrap()
  }, 320)
}

function switchView(next: ServiceView): void {
  view.value = next
  mobileDetail.value = false
}

async function selectCase(caseId: number): Promise<void> {
  selectedCaseId.value = caseId
  mobileDetail.value = true
  await loadCaseScoped()
  // 与 SgFilterIndex 同口径：环境无 matchMedia 时按桌面形态，不抢焦点
  if (typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 740px)').matches) {
    await nextTick()
    document.getElementById('case-detail')?.focus()
  }
}

function backToQueue(): void {
  mobileDetail.value = false
  void nextTick(() => document.querySelector<HTMLElement>(`[data-test="case-${selectedCaseId.value}"]`)?.focus())
}

function clearFilters(): void {
  query.value = ''
  statusFilter.value = 'all'
  void bootstrap()
}

async function enterCase(caseId: number, event?: Event): Promise<void> {
  rememberTrigger(event)
  view.value = 'cases'
  await selectCase(caseId)
}

function timelineLabel(event: CustomerServiceAdminCaseEvent): string {
  return `${caseEventTypeLabel(event.eventType)}${event.sourceType === 'USER' ? ' · 用户' : ''}`
}

/** 来源由投诉上真实存在的外键推导，服务端没有来源列就不声称有（B3-T27 同源口径）。 */
function complaintSourceText(complaint?: CustomerComplaint | null): string {
  if (!complaint) return '未关联投诉'
  if (complaint.reservationId) return '预约反馈'
  if (complaint.noticeId) return '公告反馈'
  if (complaint.siteIncidentId) return '现场异常反馈'
  if (complaint.assetId) return '设备反馈'
  if (complaint.lifecycleId) return '到场反馈'
  return '场馆反馈'
}

function complaintLinkText(complaint?: CustomerComplaint | null): string {
  if (!complaint) return '无关联对象'
  const parts = [`投诉 ${complaint.complaintNo ?? `#${complaint.id}`}`]
  if (complaint.reservationId) parts.push(`预约 #${complaint.reservationId}`)
  if (complaint.spaceId) parts.push(`场地 #${complaint.spaceId}`)
  if (complaint.noticeId) parts.push(`公告 #${complaint.noticeId}`)
  if (complaint.assetId) parts.push(`资产 #${complaint.assetId}`)
  return parts.join(' · ')
}

function contextLinkText(row: { cases: Array<Record<string, unknown>> }): string {
  if (!row.cases.length) return '关联投诉 → 尚无客服工单'
  const first = row.cases[0]
  return `关联投诉 → 工单 ${String(first.caseNo ?? '')} · ${caseStatusLabel(String(first.status ?? ''))}`
}

function rememberTrigger(event?: Event | null): void {
  const target = event?.target
  lastTrigger = target instanceof HTMLElement ? target : (document.activeElement instanceof HTMLElement ? document.activeElement : null)
  lastTriggerTest = lastTrigger?.getAttribute('data-test') ?? null
}

function openDialog(next: DialogState, event?: Event | null): void {
  rememberTrigger(event)
  dialog.value = next
  void nextTick(() => {
    if (dialogEl.value && !dialogEl.value.open) dialogEl.value.showModal()
  })
}

function closeDialog(): void {
  dialogEl.value?.close()
}

/** 焦点恢复链沿用 ref/f/common.js:2 与 ref/a/service.js:23：原触发元素 → 重渲染后的同 data-test → 当前页签（B3-T72）。 */
function restoreTriggerFocus(): void {
  const trigger = lastTrigger
  const test = lastTriggerTest
  lastTrigger = null
  lastTriggerTest = null
  restoreFocusChain({
    rootSelector: '.sga-service',
    trigger,
    triggerTest: test,
    tabSelector: '.sga-service .a-tab[aria-pressed="true"]'
  })
}

function onDialogClose(): void {
  // finishDialog 已自行收尾并清空 dialog 时，稍后送达的 close 事件不得再用空触发态覆盖回焦点
  if (!dialog.value) return
  dialog.value = null
  restoreTriggerFocus()
}

function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Tab') return
  const node = dialogEl.value
  if (!node) return
  const focusable = [...node.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href]'
  )]
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

/** 成功收尾按参考顺序：关闭 → 清空弹层态 → 重渲染 → 恢复焦点（@close 在提交路径上不保证送达）。 */
async function finishDialog(): Promise<void> {
  closeDialog()
  dialog.value = null
  await bootstrap()
  await nextTick()
  restoreTriggerFocus()
}

function newDialog(kind: DialogKind, title: string, submit = '', bodyText = '', reviewId: number | null = null): DialogState {
  return { kind, title, submit, error: '', busy: false, bodyText, reviewId }
}

function openAssign(event?: Event): void {
  form.assignedUserId = undefined
  openDialog(newDialog('assign', '分派工单', '确认'), event)
}

function openReplyConfirm(): void {
  openDialog(newDialog('reply', '确认用户可见回复', '确认发送', form.replyText.trim()))
}

function openResolve(event?: Event): void {
  const flags = flagSelection(selectedRow.value?.case ?? null)
  form.rootCauseCode = 'SERVICE_EXECUTION'
  form.resolutionType = 'EXPLAINED'
  form.reason = ''
  form.requiresRefundReview = Boolean(flags.requiresRefundReview)
  form.requiresCouponReview = Boolean(flags.requiresCouponReview)
  form.requiresSiteFollowUp = Boolean(flags.requiresSiteFollowUp)
  openDialog(newDialog('resolve', '记录解决结果', '确认'), event)
}

function openReason(kind: 'close' | 'reopen' | 'in-progress', event?: Event): void {
  form.reason = ''
  const title = kind === 'close' ? '关闭工单' : kind === 'reopen' ? '重新打开工单' : '转处理中'
  openDialog(newDialog(kind, title, '确认'), event)
}

function openModerate(review: VenueReview, event?: Event): void {
  form.moderation = 'FLAG'
  form.reason = ''
  openDialog(newDialog('moderate', '审核评价', '确认', review.contentSanitized ?? '', review.id), event)
}

async function runAction(call: () => Promise<unknown>, toast: string): Promise<boolean> {
  const state = dialog.value
  if (!state) return false
  state.busy = true
  state.error = ''
  try {
    await call()
    app.showToast(toast)
    return true
  } catch (error) {
    state.error = readableError(error, '操作未完成，内容已保留。请重试。')
    return false
  } finally {
    state.busy = false
  }
}

async function confirmDialog(): Promise<void> {
  const state = dialog.value
  const row = selectedRow.value
  if (!state) return

  if (state.kind === 'moderate') {
    const review = reviewList.value.find((item) => item.id === state.reviewId)
    if (!review) {
      state.error = '未找到该评价，请重新读取。'
      return
    }
    if (!form.reason.trim()) {
      state.error = '请填写审核原因。'
      return
    }
    const payload = { action: form.moderation, reasonCode: reasonCodeFor(form.moderation), reasonText: form.reason.trim() }
    if (await runAction(() => feedbackApi.moderateReview(review.id, payload, isolated), '审核结果已记录')) {
      await finishDialog()
    }
    return
  }

  if (!row) return
  const caseId = row.case.id

  if (state.kind === 'assign') {
    if (!form.assignedUserId) {
      state.error = '请填写处理人 ID。'
      return
    }
    if (await runAction(() => feedbackApi.assignCase(caseId, { assignedUserId: form.assignedUserId }, isolated), '工单已分派')) {
      await finishDialog()
    }
    return
  }

  if (state.kind === 'reply') {
    const replyText = form.replyText.trim()
    if (!replyText) {
      state.error = '请填写回复内容。'
      return
    }
    const internalNote = form.internalNote.trim()
    if (await runAction(
      () => feedbackApi.replyCase(caseId, { replyText, internalNote: internalNote || undefined }, isolated),
      '回复已发送'
    )) {
      form.replyText = ''
      form.internalNote = ''
      await finishDialog()
    }
    return
  }

  if (state.kind === 'resolve') {
    if (!form.reason.trim()) {
      state.error = '请填写用户可见处理说明。'
      return
    }
    if (!canResolveCase(row.case.status)) {
      state.error = '工单需先处于处理中或已重开状态才能标记解决。'
      return
    }
    const payload = {
      rootCauseCode: form.rootCauseCode,
      resolutionType: form.resolutionType,
      replyText: form.reason.trim(),
      requiresRefundReview: form.requiresRefundReview,
      requiresCouponReview: form.requiresCouponReview,
      requiresSiteFollowUp: form.requiresSiteFollowUp
    }
    if (await runAction(() => feedbackApi.resolveCase(caseId, payload, isolated), '工单已标记解决')) {
      await finishDialog()
    }
    return
  }

  if (state.kind === 'close' || state.kind === 'reopen' || state.kind === 'in-progress') {
    if (!form.reason.trim()) {
      state.error = '请填写原因。'
      return
    }
    if (state.kind === 'close' && !canCloseCase(row.case.status)) {
      state.error = '工单需先标记解决才能关闭。'
      return
    }
    if (state.kind === 'reopen' && !canReopenCase(row.case.status)) {
      state.error = '只有已解决或已关闭的工单可以重开。'
      return
    }
    if (state.kind === 'in-progress' && !canMoveToInProgress(row.case.status)) {
      state.error = '当前工单状态无需转处理中。'
      return
    }
    const reason = form.reason.trim()
    const call = state.kind === 'close'
      ? feedbackApi.closeCase(caseId, { closeReason: reason }, isolated)
      : state.kind === 'reopen'
        ? feedbackApi.reopenCase(caseId, { reopenReason: reason }, isolated)
        : feedbackApi.changeCaseStatus(caseId, { status: 'IN_PROGRESS' }, isolated)
    const toast = state.kind === 'close' ? '工单已关闭' : state.kind === 'reopen' ? '工单已重开' : '工单已转处理中'
    if (await runAction(() => call, toast)) {
      await finishDialog()
    }
  }
}

/** `reasonCode` 真实无字典，沿用旧正文三值（B3-T35）。 */
function reasonCodeFor(action: string): string {
  if (action === 'RESTORE') return 'RESTORE_REVIEW'
  if (action === 'HIDE') return 'MANUAL_REVIEW'
  return 'SENSITIVE'
}
</script>
