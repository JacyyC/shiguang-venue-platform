<template>
  <div ref="rootEl" class="sga-gov" data-test="sga-gov-root">
    <div class="g-head">
      <div>
        <div class="g-kicker">平台治理 · 内容与处置</div>
        <h1>平台内容治理</h1>
        <p>先读举报缘由与目标内容，再留下独立、可追溯的处理决定。这里承接平台处置，小组成员与组内治理仍由社区入口管理。</p>
      </div>
    </div>

    <div class="g-tabs" role="group" aria-label="平台治理工作面">
      <button data-test="gov-tab-reports" :aria-pressed="tab === 'reports'" @click="go('reports')">举报目录</button>
      <button data-test="gov-tab-inspect" :aria-pressed="tab === 'inspect'" @click="go('inspect')">按目标检查</button>
      <button data-test="gov-tab-audit" :aria-pressed="tab === 'audit'" @click="go('audit')">治理审计</button>
    </div>

    <div id="g-work">
      <!-- ------------------------------------------------------------ 详情（举报上下文 / 独立目标检查） -->
      <template v-if="detail">
        <div class="g-detail-top">
          <button class="btn" data-test="gov-back" data-g-focus @click="back">返回{{ detail.report ? '举报目录' : '目标检查' }}</button>
          <span class="g-muted">{{ detail.report ? `举报 ${detail.report.reportNo || detail.report.id}` : '独立目标检查' }}</span>
        </div>
        <div class="g-reading">
          <article>
            <p v-if="contentState === 'loading'" class="g-muted" data-test="gov-content-loading">正在读取目标内容…</p>
            <div v-else-if="contentState === 'missing' || contentState === 'forbidden' || contentState === 'error'" class="g-empty" role="alert" data-test="gov-content-unreadable">
              <h2 id="g-detail-title" tabindex="-1">{{ contentState === 'missing' ? '目标不存在或已清除' : '目标本次未能读取' }}</h2>
              <p>{{ contentError || '保留举报缘由供核对，不用旧正文替代不可读取内容。' }}</p>
              <button class="btn" type="button" data-test="gov-content-retry" @click="loadContent">重新读取目标</button>
            </div>
            <template v-else-if="content">
              <div class="g-kicker">
                {{ targetTypeLabel(content.targetType) }} {{ content.id }} · 内容版本 V{{ content.versionNo }}
              </div>
              <h2 id="g-detail-title" tabindex="-1">{{ content.title || `${targetTypeLabel(content.targetType)} ${content.id}` }}</h2>
              <div class="row" style="margin-bottom: 25px">
                <span class="g-state" :data-state="content.status" data-test="gov-content-status">{{ contentStatusLabel(content.status) }}</span>
                <span class="g-muted">
                  作者 {{ content.authorUserId }}<template v-if="content.groupId"> · 小组 {{ content.groupId }}</template><template v-if="content.parentId"> · 所属帖子 {{ content.parentId }}</template>
                </span>
              </div>
              <p v-if="content.hiddenScope" class="g-note" data-test="gov-hidden-scope">
                隐藏来源：{{ hiddenScopeLabel(content.hiddenScope) }}<template v-if="content.hiddenBy"> · 执行者 {{ content.hiddenBy }}</template>
                <template v-if="content.hiddenAt"> · {{ shortDateTime(content.hiddenAt) }}</template>。<template v-if="content.hiddenScope === 'PLATFORM'">平台处置可覆盖组内处置。</template><template v-else>组内处置不能由组管理员之外的角色恢复。</template>
              </p>
              <div class="g-copy" data-test="gov-content-body">{{ content.content || '（该目标没有正文，或正文已按保留期清除）' }}</div>
              <p v-if="content.hiddenReasonCode" class="g-note">最近隐藏原因：{{ content.hiddenReasonCode }}</p>
              <p v-if="content.status === 'DELETED'" class="g-note" data-test="gov-deleted-note">
                已删除内容仍可读，正文保留至 {{ content.retainUntil ? shortDateTime(content.retainUntil) : '保留期结束' }} 后清除；此状态不提供隐藏或恢复命令。
              </p>
              <section class="g-section">
                <h3>内容处置</h3>
                <p class="g-muted">
                  {{ detail.report ? '关联举报处置需先由本人受理。此动作只改变内容，不替代举报结案。' : '独立处置只提交原因代码，不附带无关联的内部备注。' }}
                </p>
                <div class="g-actions">
                  <button
                    class="btn danger"
                    type="button"
                    data-test="gov-hide"
                    :disabled="!canModerate || !canHideStatus(content.status) || !moderationAllowed"
                    @click="openModerate(false, $event)"
                  >隐藏内容</button>
                  <button
                    class="btn"
                    type="button"
                    data-test="gov-restore"
                    :disabled="!canRestore || !canRestoreStatus(content.status) || !moderationAllowed"
                    @click="openModerate(true, $event)"
                  >恢复内容</button>
                </div>
                <p v-if="moderationHint(content.status)" class="g-note">{{ moderationHint(content.status) }}</p>
                <p v-if="detail.report && !reportLinked" class="g-muted" style="margin-top: 14px" data-test="gov-moderation-locked">
                  当前举报未由本人审核，关联内容处置不可用。
                </p>
              </section>
            </template>
          </article>
          <aside>
            <template v-if="detail.report">
              <h3>举报缘由</h3>
              <p data-test="gov-report-reason">{{ detail.report.reason }}</p>
              <dl class="g-meta">
                <dt>举报状态</dt><dd>{{ reportStatusLabel(detail.report.status) }}</dd>
                <dt>举报人</dt><dd>{{ detail.report.reporter?.nickname || detail.report.reporter?.id || '—' }}</dd>
                <dt>处理人</dt><dd>{{ detail.report.assignedTo || '尚未受理' }}</dd>
                <dt>结论代码</dt><dd>{{ detail.report.decisionCode || '尚未结案' }}</dd>
              </dl>
              <h3>举报决定</h3>
              <p class="g-muted">完成核对后单独结案。解决或驳回都不会自动隐藏、恢复内容。</p>
              <div class="g-actions">
                <button
                  v-if="detail.report.status === 'OPEN'"
                  class="btn primary"
                  type="button"
                  data-test="gov-take"
                  :disabled="!canReview || busy"
                  @click="openDecision('TAKE', $event)"
                >由我受理</button>
                <button
                  class="btn"
                  type="button"
                  data-test="gov-resolve"
                  :disabled="!canDecide || busy"
                  @click="openDecision('RESOLVE', $event)"
                >解决举报</button>
                <button
                  class="btn"
                  type="button"
                  data-test="gov-dismiss"
                  :disabled="!canDecide || busy"
                  @click="openDecision('DISMISS', $event)"
                >驳回举报</button>
              </div>
              <p v-if="assignedToOther" class="g-note" data-test="gov-assigned-other">已由其他人员受理，当前不可代为结案。</p>
              <div v-if="detail.report.internalNote" class="g-section">
                <h3>内部备注</h3>
                <p>{{ detail.report.internalNote }}</p>
              </div>
            </template>
            <template v-else>
              <h3>平台检查边界</h3>
              <p class="g-muted">按目标检查，不枚举用户的私有内容。原内容的成员可见性不会因为被检查而变成公开。</p>
              <p class="g-note">
                读取范围：持「内容处置/内容恢复」可读任意目标（含已删除）；仅持「举报审核」时必须携带匹配的举报，
                只能读被举报目标。每次读取都会落一条检查审计。
              </p>
            </template>
            <div class="g-section">
              <button v-if="canAudit" class="btn" type="button" data-test="gov-to-audit" @click="toAudit($event)">查看相关治理记录</button>
              <p v-else class="g-muted">当前没有治理审计读取权限。</p>
            </div>
          </aside>
        </div>
      </template>

      <!-- ------------------------------------------------------------ 举报目录 -->
      <template v-else-if="tab === 'reports'">
        <div v-if="!canReview" class="g-empty" data-test="gov-denied-reports">
          <h2>当前没有举报审核权限</h2>
          <p>举报目录需要「举报审核」权限点。</p>
        </div>
        <template v-else>
          <div class="g-toolbar">
            <div>
              <h2>按缘由逐条核对</h2>
              <p>本页 {{ reports.length }} 条举报 · 结案不自动改变内容状态。</p>
            </div>
            <div class="g-tools">
              <div class="field">
                <label for="g-status">处理状态</label>
                <select id="g-status" v-model="reportFilters.status" data-test="gov-status-filter" @change="applyReportFilter">
                  <option value="">全部状态</option>
                  <option v-for="option in REPORT_STATUS_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                </select>
              </div>
              <div class="field">
                <label for="g-type">目标类型</label>
                <select id="g-type" v-model="reportFilters.targetType" data-test="gov-type-filter" @change="applyReportFilter">
                  <option value="">全部目标</option>
                  <option v-for="option in TARGET_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                </select>
              </div>
            </div>
          </div>
          <p v-if="listError" class="g-error" role="alert" data-test="gov-reports-error">{{ listError }}</p>
          <p v-if="listLoading" class="g-muted" data-test="gov-reports-loading">正在读取举报队列…</p>
          <div v-else-if="reports.length" class="g-list" data-test="gov-report-list">
            <article v-for="row in reports" :key="row.id" class="g-report">
              <div><span class="g-tag">{{ targetTypeLabel(row.targetType) }}</span><br><small>{{ row.targetId }}</small></div>
              <div>
                <h3>{{ row.reason }}</h3>
                <p>举报 {{ shortReportNo(row.reportNo) }}<template v-if="row.decisionCode"> · 结论 {{ row.decisionCode }}</template><template v-if="row.internalNote"> · 有内部备注</template></p>
                <small>{{ row.reporter?.nickname || row.reporter?.id || '—' }} · {{ shortDateTime(row.createTime) }}</small>
              </div>
              <div class="g-report-meta">
                <span class="g-state" :data-state="row.status">{{ reportStatusLabel(row.status) }}</span>
                <p>{{ row.assignedTo ? `处理人 ${row.assignedTo}` : '尚未受理' }}</p>
              </div>
              <button :id="`g-report-${row.id}`" class="btn" type="button" :data-test="`gov-open-${row.id}`" @click="openReport(row, $event)">检查举报</button>
            </article>
            <p v-if="reportHasMore" style="margin-top: 20px">
              <button class="btn" type="button" data-test="gov-reports-more" :disabled="listLoadingMore" @click="loadReports(true)">
                {{ listLoadingMore ? '读取中…' : '加载更多举报' }}
              </button>
            </p>
          </div>
          <div v-else class="g-empty" data-test="gov-reports-empty">
            <h3>没有符合条件的举报</h3>
            <p>调整筛选后继续核对。</p>
          </div>
        </template>
      </template>

      <!-- ------------------------------------------------------------ 按目标检查 -->
      <template v-else-if="tab === 'inspect'">
        <div v-if="!canInspect" class="g-empty" data-test="gov-denied-inspect">
          <h2>独立检查需要隐藏或恢复内容权限</h2>
          <p>当前账号既没有「内容处置」也没有「内容恢复」权限点。</p>
        </div>
        <template v-else>
          <h2>输入已有内容目标</h2>
          <p class="g-muted">按明确的对象类型与 ID 检查。这里只读取选定内容，每次检查留有治理审计。</p>
          <form class="g-find" data-test="gov-find-form" @submit.prevent="lookup">
            <div class="field">
              <label for="g-find-type">目标类型</label>
              <select id="g-find-type" v-model="inspectForm.targetType" data-test="gov-find-type">
                <option v-for="option in TARGET_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
              </select>
            </div>
            <div class="field">
              <label for="g-find-id">目标 ID</label>
              <input id="g-find-id" v-model="inspectForm.targetId" data-test="gov-find-id" type="number" min="1" required>
            </div>
            <button class="btn primary" type="submit" data-test="gov-find-button">检查内容</button>
          </form>
          <p v-if="findError" class="g-error" role="alert" data-test="gov-find-error">{{ findError }}</p>
          <p class="g-note">小组内的加入审核、成员角色、移除与组主操作由社区承接。平台在这里按独立权限检查、隐藏或恢复内容。</p>
        </template>
      </template>

      <!-- ------------------------------------------------------------ 治理审计 -->
      <template v-else>
        <div v-if="!canAudit" class="g-empty" data-test="gov-denied-audit">
          <h2>当前没有治理审计权限</h2>
          <p>治理审计需要「治理审计」权限点。</p>
        </div>
        <template v-else>
          <div class="g-toolbar">
            <div>
              <h2>治理审计</h2>
              <p>平台作用域 · 本页 {{ events.length }} 条记录；包含内容检查与状态变化。</p>
            </div>
            <!-- 与参考同形：该按钮常驻（参考 `M.auditView` 的工具栏按钮也没有条件），无筛选时等价于重新读取。 -->
            <button class="btn" type="button" data-test="gov-audit-clear" data-g-focus @click="clearEventFilter">查看全部记录</button>
          </div>
          <p v-if="eventFilters.reportId" class="g-note" data-test="gov-audit-report-filter">关联举报 {{ eventFilters.reportId }}</p>
          <p v-else-if="eventFilters.subjectType" class="g-note" data-test="gov-audit-subject-filter">
            关联目标 {{ subjectTypeLabel(eventFilters.subjectType) }} {{ eventFilters.subjectId }}
          </p>
          <p v-if="listError" class="g-error" role="alert" data-test="gov-audit-error">{{ listError }}</p>
          <p v-if="listLoading" class="g-muted" data-test="gov-audit-loading">正在读取治理记录…</p>
          <template v-else-if="events.length">
            <article v-for="row in events" :key="row.id" class="g-audit">
              <p class="g-muted">{{ shortDateTime(row.occurredAt) }}<br>记录 {{ shortEventNo(row.eventNo, row.id) }}</p>
              <div>
                <h3>{{ eventActionLabel(row) }} · {{ subjectTypeLabel(row.subjectType) }}{{ row.subjectId }}</h3>
                <p>{{ auditStatusLabel(row.fromStatus) }} → {{ auditStatusLabel(row.toStatus) }}</p>
                <p class="g-muted">
                  {{ row.reasonCode || row.reasonSummary || '—' }}<template v-if="row.reportId"> · 举报 {{ row.reportId }}</template>
                </p>
              </div>
              <p class="g-muted">
                执行者 {{ row.actorUserId ?? '—' }}<br>{{ actorScopeLabel(row.actorScope) }} · {{ sourceTypeLabel(row.sourceType) }}<br>{{ resultStatusLabel(row.resultStatus) }}
              </p>
            </article>
            <p v-if="eventHasMore" style="margin-top: 20px">
              <button class="btn" type="button" data-test="gov-audit-more" :disabled="listLoadingMore" @click="loadEvents(true)">
                {{ listLoadingMore ? '读取中…' : '加载更多记录' }}
              </button>
            </p>
          </template>
          <div v-else class="g-empty" data-test="gov-audit-empty">
            <h3>没有相关治理记录</h3>
            <p>当前筛选条件下没有事件；检查动作本身也会产生记录。</p>
          </div>
        </template>
      </template>
    </div>

    <!-- ------------------------------------------------------------ 处置 / 决定弹层 -->
    <dialog ref="dialogEl" class="g-dialog" aria-labelledby="g-dialog-title" @close="onDialogClose" @keydown="onDialogKeydown">
      <div class="g-dialog-header">
        <h2 id="g-dialog-title" data-test="gov-dialog-title" tabindex="-1">{{ dialogTitle }}</h2>
        <button class="btn" type="button" aria-label="关闭对话框" data-test="gov-dialog-close" @click="closeDialog">关闭</button>
      </div>
      <form id="g-form" @submit.prevent>
        <template v-if="dialog === 'decision' && detail?.report">
          <p>举报 {{ detail.report.reportNo || detail.report.id }} · {{ detail.report.reason }}</p>
          <p class="g-note">
            {{ decisionAction === 'TAKE' ? '受理后由当前审核者继续处理。' : '本次只更新举报结论，内容的隐藏或恢复状态保持独立。' }}
          </p>
          <div class="field">
            <label for="g-code">结论原因代码</label>
            <input id="g-code" v-model="form.code" data-test="gov-decision-code" required maxlength="64" autocomplete="off">
          </div>
          <div class="field">
            <label for="g-note">内部备注（可选）</label>
            <textarea id="g-note" v-model="form.note" data-test="gov-decision-note" rows="4" maxlength="500"></textarea>
          </div>
          <div class="g-actions">
            <button class="btn" type="button" data-test="gov-dialog-cancel" @click="closeDialog">返回核对</button>
            <button class="btn primary" type="submit" data-test="gov-dialog-submit" :disabled="busy" @click="submitDecision">
              {{ busy ? '处理中…' : `确认${DECISION_TITLES[decisionAction] || ''}` }}
            </button>
          </div>
        </template>
        <template v-else-if="dialog === 'moderate' && content">
          <p>{{ targetTypeLabel(content.targetType) }}{{ content.id }} · {{ content.title || '（无标题）' }}</p>
          <p class="g-note">
            {{ moderateRestore ? '恢复可见状态仍受原有小组可见性约束。' : '隐藏会影响用户侧读取；不会自动结案关联举报。' }}
          </p>
          <div class="field">
            <label for="g-code">处置原因代码</label>
            <input id="g-code" v-model="form.code" data-test="gov-moderate-code" required maxlength="64" autocomplete="off">
          </div>
          <div v-if="detail?.report" class="field">
            <label for="g-note">内部备注（关联本人审核中的举报）</label>
            <textarea id="g-note" v-model="form.note" data-test="gov-moderate-note" rows="4" maxlength="500"></textarea>
          </div>
          <div class="g-actions">
            <button class="btn" type="button" data-test="gov-dialog-cancel" @click="closeDialog">返回核对</button>
            <button class="btn primary" type="submit" data-test="gov-dialog-submit" :disabled="busy" @click="submitModerate">
              {{ busy ? '处理中…' : `确认${moderateRestore ? '恢复' : '隐藏'}` }}
            </button>
          </div>
        </template>
        <div id="g-error" class="g-error" role="alert" data-test="gov-dialog-error">{{ dialogError }}</div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  contentAdminApi,
  createIdempotencyRegistry,
  type AdminContent,
  type AdminEvent,
  type AdminReport
} from '@/api/community'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { ISOLATED_REQUEST as isolated, formatHm, restoreFocusChain } from '../adminShared'
import {
  CURSOR_PAGE_SIZE,
  DECISION_CODE_DEFAULTS,
  DECISION_TITLES,
  MODERATE_REASON_DEFAULTS,
  REPORT_STATUS_LABELS,
  actorScopeLabel,
  auditStatusLabel,
  canHide as canHideStatus,
  canRestore as canRestoreStatus,
  contentStatusLabel,
  eventActionLabel,
  hiddenScopeLabel,
  moderationHint,
  reportStatusLabel,
  resultStatusLabel,
  shortEventNo,
  shortReportNo,
  sourceTypeLabel,
  subjectTypeLabel,
  targetTypeLabel
} from './governanceShared'

type Tab = 'reports' | 'inspect' | 'audit'
type ContentState = 'idle' | 'loading' | 'ready' | 'missing' | 'forbidden' | 'error'

interface Detail {
  targetType: 'GROUP' | 'POST' | 'COMMENT'
  targetId: number
  report?: AdminReport
}

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const auth = useAuthStore()
const rootEl = ref<HTMLElement | null>(null)
const dialogEl = ref<HTMLDialogElement | null>(null)

const canReview = computed(() => auth.hasPermission('community:report:review'))
const canModerate = computed(() => auth.hasPermission('community:content:moderate'))
const canRestore = computed(() => auth.hasPermission('community:content:restore'))
const canAudit = computed(() => auth.hasPermission('community:audit:view'))
const canInspect = computed(() => canModerate.value || canRestore.value)
const myUserId = computed<number | null>(() => auth.user?.id ?? null)

const tab = ref<Tab>('reports')
const detail = ref<Detail | null>(null)
const reports = ref<AdminReport[]>([])
const events = ref<AdminEvent[]>([])
const content = ref<AdminContent | null>(null)
const contentState = ref<ContentState>('idle')
const contentError = ref('')
const listError = ref('')
const findError = ref('')
const listLoading = ref(false)
const listLoadingMore = ref(false)
const busy = ref(false)
const reportFilters = reactive({ status: '', targetType: '' })
const eventFilters = reactive<{ subjectType: string; subjectId: number | null; reportId: number | null }>({
  subjectType: '', subjectId: null, reportId: null
})
const inspectForm = reactive<{ targetType: 'GROUP' | 'POST' | 'COMMENT'; targetId: string }>({ targetType: 'POST', targetId: '' })
const dialog = ref<'decision' | 'moderate' | null>(null)
const decisionAction = ref<'TAKE' | 'RESOLVE' | 'DISMISS'>('RESOLVE')
const moderateRestore = ref(false)
const dialogError = ref('')
const form = reactive({ code: '', note: '' })
const reportCursor = ref<number | null>(null)
const eventCursor = ref<number | null>(null)
const reportHasMore = ref(false)
const eventHasMore = ref(false)
const lastTrigger = ref<HTMLElement | null>(null)
const lastTriggerTest = ref<string | null>(null)
const writes = createIdempotencyRegistry()

const REPORT_STATUS_OPTIONS = Object.entries(REPORT_STATUS_LABELS).map(([value, label]) => ({ value, label }))
const TARGET_TYPE_OPTIONS = (['GROUP', 'POST', 'COMMENT'] as const).map((value) => ({ value, label: targetTypeLabel(value) }))
const dialogTitle = computed(() =>
  dialog.value === 'decision'
    ? DECISION_TITLES[decisionAction.value] || '举报决定'
    : (moderateRestore.value ? '恢复内容' : '隐藏内容')
)

/** 关联处置只允许本人审核中的举报（服务端同样强制，403「只能关联由本人审核中的举报」）。 */
const reportLinked = computed(() => {
  const report = detail.value?.report
  if (!report) return false
  return report.status === 'IN_REVIEW' && report.assignedTo === myUserId.value
})
const moderationAllowed = computed(() => !detail.value?.report || reportLinked.value)
const assignedToOther = computed(() => {
  const report = detail.value?.report
  return !!report?.assignedTo && report.assignedTo !== myUserId.value
})
const canDecide = computed(() => {
  const report = detail.value?.report
  if (!report) return false
  if (!['OPEN', 'IN_REVIEW'].includes(report.status)) return false
  return !report.assignedTo || report.assignedTo === myUserId.value
})

/**
 * 错误文案：优先服务端 `message`（B4-G8）。既有 `communityError` 对 403/404 硬编码提示，
 * 会把「该举报已由其他内容运营人员审核」「只能关联由本人审核中的举报」等真实文案吞掉；
 * 这里只在治理面内做局部改造，不动其它社区页的既有降级。
 */
function describeError(error: unknown, fallback: string): { status?: number; message: string } {
  const response = (error as { response?: { status?: number; data?: { message?: string } } })?.response
  const status = response?.status
  const serverMessage = response?.data?.message
  if (status === 401) return { status, message: serverMessage || '登录已失效，请重新登录' }
  if (status === 403) return { status, message: serverMessage || '当前账号无权执行此操作' }
  if (status === 404) return { status, message: serverMessage || '内容不存在' }
  if (status === 409) return { status, message: serverMessage || '操作已处理或内容已更新，请刷新后重试' }
  if (status === 429) return { status, message: serverMessage || '操作过于频繁，请稍后再试' }
  return { status, message: serverMessage || (error as Error)?.message || fallback }
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** 参考治理面时间列格式 `09-12 09:35`（月-日 时:分）。 */
function shortDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(String(value).replace(' ', 'T'))
  if (Number.isNaN(parsed.getTime())) return String(value)
  return `${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${formatHm(value)}`
}

/* --------------------------------------------------------------- URL 状态 */

/** B4-G3：页签/详情/筛选写进 query，刷新与深链可恢复（游标不写 URL，避免翻页污染历史）。 */
function syncUrl(replace = true): void {
  const query: Record<string, string> = {}
  if (tab.value !== 'reports') query.tab = tab.value
  if (detail.value?.report) query.report = String(detail.value.report.id)
  if (detail.value) {
    query.type = detail.value.targetType
    query.id = String(detail.value.targetId)
    if (!detail.value.report) query.tab = tab.value
  }
  if (reportFilters.status) query.status = reportFilters.status
  if (reportFilters.targetType) query.targetType = reportFilters.targetType
  if (eventFilters.reportId) query.reportId = String(eventFilters.reportId)
  if (eventFilters.subjectType) query.subjectType = eventFilters.subjectType
  if (eventFilters.subjectId) query.subjectId = String(eventFilters.subjectId)
  const target = { path: route.path, query }
  void (replace ? router.replace(target) : router.push(target))
}

function applyRouteState(): void {
  const query = route.query
  const wantedTab = typeof query.tab === 'string' ? query.tab : ''
  const type = typeof query.type === 'string' ? query.type.toUpperCase() : ''
  const id = typeof query.id === 'string' ? Number(query.id) : Number.NaN
  if (typeof query.status === 'string') reportFilters.status = query.status
  if (typeof query.targetType === 'string') reportFilters.targetType = query.targetType
  if (typeof query.reportId === 'string') eventFilters.reportId = Number(query.reportId) || null
  if (typeof query.subjectType === 'string') eventFilters.subjectType = query.subjectType
  if (typeof query.subjectId === 'string') eventFilters.subjectId = Number(query.subjectId) || null
  tab.value = (['reports', 'inspect', 'audit'].includes(wantedTab) ? wantedTab : defaultTab()) as Tab
  const reportId = typeof query.report === 'string' ? Number(query.report) : Number.NaN
  if (reportId && ['GROUP', 'POST', 'COMMENT'].includes(type) && Number.isFinite(id)) {
    // 深链只带举报 id 时右侧仍可知，左侧按 type/id 读取（B4-G2 的三参数约定）。
    detail.value = { targetType: type as Detail['targetType'], targetId: id }
    void restoreDeepLink(reportId)
    return
  }
  if (wantedTab === 'inspect' && ['GROUP', 'POST', 'COMMENT'].includes(type) && Number.isFinite(id)) {
    detail.value = { targetType: type as Detail['targetType'], targetId: id }
    void loadContent()
  }
}

/** 深链恢复：先从列表里找回该举报行（无按 ID 端点，B4-G2），再读内容。 */
async function restoreDeepLink(reportId: number): Promise<void> {
  const found = reports.value.find((row) => row.id === reportId)
  if (found) {
    detail.value = { ...(detail.value as Detail), report: found }
  } else if (canReview.value) {
    await loadReports(false, { silent: true })
    const row = reports.value.find((item) => item.id === reportId)
    if (row) detail.value = { targetType: row.targetType, targetId: row.targetId, report: row }
  }
  await loadContent()
}

function defaultTab(): Tab {
  if (canReview.value) return 'reports'
  if (canInspect.value) return 'inspect'
  return 'audit'
}

/* ----------------------------------------------------------------- 数据 */

async function loadReports(append = false, options: { silent?: boolean } = {}): Promise<void> {
  if (!canReview.value) return
  if (append) listLoadingMore.value = true
  else listLoading.value = true
  listError.value = ''
  try {
    const page = await contentAdminApi.reports({
      status: reportFilters.status || undefined,
      targetType: reportFilters.targetType || undefined,
      cursorId: append ? reportCursor.value ?? undefined : undefined,
      limit: CURSOR_PAGE_SIZE
    }, isolated)
    reports.value = append ? [...reports.value, ...page.records] : page.records
    reportCursor.value = page.nextCursor ?? null
    reportHasMore.value = page.hasMore
  } catch (error) {
    const described = describeError(error, '举报队列加载失败')
    listError.value = described.message
    if (!options.silent) app.showToast(described.message)
  } finally {
    listLoading.value = false
    listLoadingMore.value = false
  }
}

async function loadEvents(append = false): Promise<void> {
  if (!canAudit.value) return
  if (append) listLoadingMore.value = true
  else listLoading.value = true
  listError.value = ''
  try {
    const page = await contentAdminApi.events({
      subjectType: eventFilters.subjectType || undefined,
      subjectId: eventFilters.subjectId ?? undefined,
      reportId: eventFilters.reportId ?? undefined,
      cursorId: append ? eventCursor.value ?? undefined : undefined,
      limit: CURSOR_PAGE_SIZE
    }, isolated)
    events.value = append ? [...events.value, ...page.records] : page.records
    eventCursor.value = page.nextCursor ?? null
    eventHasMore.value = page.hasMore
  } catch (error) {
    const described = describeError(error, '治理记录加载失败')
    listError.value = described.message
    app.showToast(described.message)
  } finally {
    listLoading.value = false
    listLoadingMore.value = false
  }
}

/**
 * 读取目标内容（端点 A）。404 是「不存在或已清除」，403 是「无权限读该举报的目标」，
 * 两种情况都保留右侧举报缘由、不拿旧正文顶替（B4-G7 的真实失效语义）。
 */
async function loadContent(): Promise<void> {
  const current = detail.value
  if (!current) return
  if (!canReview.value && !canInspect.value) {
    contentState.value = 'forbidden'
    contentError.value = '当前账号没有检查内容的权限点。'
    return
  }
  contentState.value = 'loading'
  contentError.value = ''
  try {
    content.value = await contentAdminApi.inspect(current.targetType, current.targetId, current.report?.id, isolated)
    contentState.value = 'ready'
  } catch (error) {
    const described = describeError(error, '目标内容读取失败')
    content.value = null
    contentState.value = described.status === 404 ? 'missing' : described.status === 403 ? 'forbidden' : 'error'
    contentError.value = described.message
  }
}

/* ----------------------------------------------------------------- 导航 */

function go(next: Tab): void {
  tab.value = next
  detail.value = null
  syncUrl()
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>(`[data-test="gov-tab-${next}"]`)?.focus())
  if (next === 'reports' && canReview.value && !reports.value.length) void loadReports()
  if (next === 'audit' && canAudit.value && !events.value.length) void loadEvents()
}

function openReport(row: AdminReport, event?: Event): void {
  if (!canReview.value) return
  rememberTrigger(event, `gov-open-${row.id}`)
  detail.value = { targetType: row.targetType, targetId: row.targetId, report: row }
  syncUrl()
  void nextTick(() => document.getElementById('g-detail-title')?.focus())
  void loadContent()
}

function lookup(): void {
  findError.value = ''
  const id = Number(inspectForm.targetId)
  if (!Number.isFinite(id) || id <= 0) {
    findError.value = '请输入有效的目标 ID。'
    return
  }
  detail.value = { targetType: inspectForm.targetType, targetId: id }
  syncUrl()
  void nextTick(() => document.getElementById('g-detail-title')?.focus())
  void loadContent()
}

function back(): void {
  detail.value = null
  content.value = null
  contentState.value = 'idle'
  syncUrl()
  void nextTick(() => restoreTriggerFocus())
}

function toAudit(event?: Event): void {
  rememberTrigger(event, 'gov-to-audit')
  eventFilters.reportId = detail.value?.report?.id ?? null
  eventFilters.subjectType = detail.value?.report ? '' : detail.value?.targetType ?? ''
  eventFilters.subjectId = detail.value?.report ? null : detail.value?.targetId ?? null
  tab.value = 'audit'
  detail.value = null
  syncUrl()
  void loadEvents()
}

function applyReportFilter(): void {
  reportCursor.value = null
  syncUrl()
  void loadReports()
}

function clearEventFilter(): void {
  eventFilters.reportId = null
  eventFilters.subjectType = ''
  eventFilters.subjectId = null
  eventCursor.value = null
  syncUrl()
  void loadEvents()
}

/* ----------------------------------------------------------------- 弹层 */

function rememberTrigger(event: Event | undefined, test: string): void {
  const node = (event?.currentTarget as HTMLElement | undefined) ?? (document.activeElement as HTMLElement | null)
  lastTrigger.value = node ?? null
  lastTriggerTest.value = test
}

function restoreTriggerFocus(): void {
  restoreFocusChain({
    rootSelector: '.sga-gov',
    trigger: lastTrigger.value,
    triggerTest: lastTriggerTest.value,
    tabSelector: '.sga-gov .g-tabs button[aria-pressed="true"]'
  })
}

function openDecision(action: 'TAKE' | 'RESOLVE' | 'DISMISS', event?: Event): void {
  rememberTrigger(event, action === 'TAKE' ? 'gov-take' : action === 'RESOLVE' ? 'gov-resolve' : 'gov-dismiss')
  decisionAction.value = action
  form.code = DECISION_CODE_DEFAULTS[action] ?? ''
  form.note = detail.value?.report?.internalNote || ''
  dialogError.value = ''
  dialog.value = 'decision'
  void nextTick(() => dialogEl.value?.showModal())
}

function openModerate(restore: boolean, event?: Event): void {
  rememberTrigger(event, restore ? 'gov-restore' : 'gov-hide')
  moderateRestore.value = restore
  form.code = restore ? MODERATE_REASON_DEFAULTS.restore : MODERATE_REASON_DEFAULTS.hide
  form.note = detail.value?.report?.internalNote || ''
  dialogError.value = ''
  dialog.value = 'moderate'
  void nextTick(() => dialogEl.value?.showModal())
}

function closeDialog(): void {
  dialogEl.value?.close()
}

async function submitDecision(): Promise<void> {
  const report = detail.value?.report
  if (!report) return
  if (!form.code.trim()) {
    dialogError.value = '请填写非空原因代码。'
    return
  }
  busy.value = true
  dialogError.value = ''
  const payload = { decision: decisionAction.value, decisionCode: form.code.trim().toUpperCase(), internalNote: form.note }
  const key = writes.keyFor(`report-${report.id}`, payload)
  try {
    await contentAdminApi.decideReport(report.id, payload, key, isolated)
    writes.complete(`report-${report.id}`)
    closeDialog()
    app.showToast(decisionAction.value === 'TAKE' ? '已由当前审核者受理' : '举报已单独结案；未修改内容状态')
    await refreshAfterWrite()
  } catch (error) {
    dialogError.value = describeError(error, '本次处理失败，未保存。填写内容仍保留，可以重试。').message
  } finally {
    busy.value = false
  }
}

async function submitModerate(): Promise<void> {
  const target = content.value
  if (!target) return
  if (!form.code.trim()) {
    dialogError.value = '请填写非空原因代码。'
    return
  }
  busy.value = true
  dialogError.value = ''
  const payload = {
    reasonCode: form.code.trim().toUpperCase(),
    internalNote: form.note || undefined,
    reportId: detail.value?.report?.id
  }
  const scope = `content-${target.targetType}-${target.id}-${moderateRestore.value ? 'restore' : 'hide'}`
  try {
    await contentAdminApi.moderate(target.targetType, target.id, moderateRestore.value, payload, writes.keyFor(scope, payload), isolated)
    writes.complete(scope)
    closeDialog()
    app.showToast(moderateRestore.value ? '内容已恢复；举报状态保持独立' : '内容已隐藏；举报状态保持独立')
    await refreshAfterWrite()
  } catch (error) {
    dialogError.value = describeError(error, '本次处理失败，未保存。填写内容仍保留，可以重试。').message
  } finally {
    busy.value = false
  }
}

/** 写入成功后按真实数据重读（举报行、目标内容与治理记录都以服务端为准）。 */
async function refreshAfterWrite(): Promise<void> {
  if (canReview.value) await loadReports(false, { silent: true })
  if (detail.value?.report) {
    const refreshed = reports.value.find((row) => row.id === detail.value?.report?.id)
    if (refreshed) detail.value = { ...detail.value, report: refreshed, targetType: refreshed.targetType, targetId: refreshed.targetId }
  }
  await loadContent()
  if (canAudit.value && tab.value === 'audit') await loadEvents()
}

function onDialogClose(): void {
  dialog.value = null
  dialogError.value = ''
  void nextTick(() => restoreTriggerFocus())
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

/* --------------------------------------------------------------- 生命周期 */

onMounted(() => {
  applyRouteState()
  if (tab.value === 'reports' && canReview.value) void loadReports()
  if (tab.value === 'audit' && canAudit.value) void loadEvents()
})

// 面内导航是**单向**写 URL：进入时读一次 query（刷新/深链恢复），之后由面内动作驱动 URL。
// 不监听 fullPath 以免与 syncUrl 互相触发、重复取数；浏览器前进后退的面内接管留给 S08 复核。

defineExpose({
  tab, detail, reports, events, content, contentState, contentError, listError, findError,
  reportFilters, eventFilters, inspectForm, dialog, decisionAction, moderateRestore, dialogError, form,
  canReview, canModerate, canRestore, canAudit, reportLinked, canDecide, assignedToOther,
  loadReports, loadEvents, loadContent, go, openReport, lookup, back, toAudit, loadMoreReports: () => loadReports(true), loadMoreEvents: () => loadEvents(true)
})
</script>
