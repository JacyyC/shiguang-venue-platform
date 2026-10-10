<template>
  <div ref="rootEl" class="sga-fin" data-test="sga-fin-root">
    <header class="h-title">
      <div>
        <p class="eyebrow">平台财务 / CNY</p>
        <h1>支付与储值财务</h1>
        <p>从每笔资金的来源开始，核对证据、解释差异，按职责完成处理。</p>
      </div>
      <div>
        <span class="badge">{{ scopeBadge }}</span>
        <p class="h-caption">当前主体：{{ actorName }}</p>
      </div>
    </header>

    <nav class="h-nav" aria-label="财务工作面">
      <button
        v-for="tab in visibleTabs"
        :key="tab.key"
        type="button"
        :data-test="`fin-tab-${tab.key}`"
        :aria-pressed="view === tab.key"
        @click="go(tab)"
      >{{ tab.label }}</button>
    </nav>

    <div aria-live="polite">
      <!-- 无叶子权限（参考 role=none 情景的真实对应；入口守卫之外的第二道正文防线） -->
      <section v-if="denied" class="empty" data-test="fin-denied">
        <h2>没有财务访问权限</h2>
        <p>当前主体不能查看支付与储值数据。请联系平台管理员核对授权。</p>
        <RouterLink class="btn" :to="{ name: 'SystemAdminDashboard' }">返回平台管理</RouterLink>
      </section>

      <template v-else>
        <section v-if="surface === 'loading'" class="h-loading" role="status" data-test="fin-loading">
          <h2>正在读取财务记录</h2>
          <p>请稍候，正在核对所选范围。</p>
          <progress aria-label="正在加载"></progress>
        </section>

        <section v-else-if="surface === 'error'" class="empty" role="alert" data-test="fin-error">
          <h2>暂时无法读取记录</h2>
          <p>{{ loadError || '尚未取得本次查询结果。已提交的资金动作不会因此重发。' }}</p>
          <button class="btn primary" type="button" data-test="fin-reload" @click="reload">重新查询</button>
        </section>

        <section v-else-if="surface === 'empty'" class="empty" data-test="fin-empty">
          <h2>当前范围没有记录</h2>
          <p>可以调整日期、渠道或选择另一工作面。</p>
        </section>

        <!-- 支付证据：rail + 单笔三段证据（B5-H4） -->
        <template v-else-if="view === 'payments'">
          <div class="h-columns">
            <aside class="h-rail" aria-label="支付记录">
              <h2>渠道与记录</h2>
              <label class="field">
                <span>Provider 环境</span>
                <select data-test="fin-provider" @change="onProviderChange">
                  <option
                    v-for="provider in providers"
                    :key="provider.providerKey"
                    :value="provider.providerKey"
                    :selected="providerKey === provider.providerKey"
                  >{{ provider.providerEnv }} · {{ provider.displayName }}</option>
                </select>
              </label>
              <p class="h-caption">全部日期 · 全部场馆 · 共 {{ attemptTotal }} 条</p>
              <button
                v-for="attempt in attempts"
                :key="attempt.id"
                class="h-pick"
                type="button"
                :data-test="`fin-pick-${attempt.paymentNo}`"
                :aria-pressed="selectedPayment?.id === attempt.id"
                @click="selectPayment(attempt)"
              >
                <strong>{{ attempt.orderNo || attempt.paymentNo }}</strong>
                <small>{{ shortTime(attempt.createTime) }} · {{ money(attempt.paidAmount || attempt.payableAmount) }}</small>
                <span class="badge" :class="attemptBadgeTone(attempt.status)">{{ attemptStatusLabel(attempt.status) }}</span>
              </button>
              <div v-if="attemptTotal > attempts.length || attemptPage > 1" class="fin-rail-pager" data-test="fin-attempt-pager">
                <button class="btn" type="button" :disabled="attemptPage <= 1" @click="turnAttemptPage(-1)">上一页</button>
                <button class="btn" type="button" :disabled="attemptPage * attemptPageSize >= attemptTotal" @click="turnAttemptPage(1)">下一页</button>
              </div>
            </aside>

            <article v-if="selectedPayment" class="h-detail" :data-test="`fin-detail-${selectedPayment.paymentNo}`">
              <header class="h-detail-head">
                <div>
                  <p class="eyebrow">支付尝试 / {{ venueLabel }}</p>
                  <h2>{{ selectedPayment.orderNo || selectedPayment.paymentNo }}</h2>
                  <span class="badge" :class="attemptBadgeTone(selectedPayment.status)">{{ attemptStatusLabel(selectedPayment.status) }}</span>
                </div>
                <div class="h-money">{{ money(selectedPayment.paidAmount || selectedPayment.payableAmount) }}<br><small>本次尝试金额</small></div>
              </header>

              <p>{{ attemptContextLine }}</p>

              <dl class="h-meta">
                <div><dt>环境 / 渠道</dt><dd>{{ selectedPayment.providerEnv }} / {{ providerDisplay(selectedPayment) }}</dd></div>
                <div><dt>配置版本</dt><dd><span class="h-code">{{ selectedPayment.configVersion || '—' }}</span></dd></div>
              </dl>

              <div class="h-evidence">
                <section>
                  <h3>01　支付尝试</h3>
                  <p>
                    {{ attemptEvidenceLine }}
                    <template v-if="selectedPayment.failureCode"><br>稳定错误码：<span class="h-code">{{ selectedPayment.failureCode }}</span></template>
                  </p>
                  <small class="muted">{{ shortTime(selectedPayment.createTime) }} · 尝试引用 {{ selectedPayment.paymentNo }}</small>
                </section>
                <section>
                  <h3>02　回调证据</h3>
                  <p>{{ callbackEvidenceLine }}</p>
                  <button class="btn" type="button" data-test="fin-open-callback" @click="openCallbackDialog">查看最小证据</button>
                  <p class="h-caption">渠道回调与本地处理结果分别记录。</p>
                </section>
                <section>
                  <h3>03　成功入账与对账</h3>
                  <template v-if="selectedPayment.status === 'SUCCEEDED'">
                    <p>支付 {{ money(selectedPayment.paidAmount) }}　/　费用 {{ money(selectedPayment.feeAmount) }}　/　净额 {{ money(selectedPayment.netAmount) }}</p>
                    <button class="btn" type="button" data-test="fin-check-difference" @click="checkDifference">核对账实记录</button>
                  </template>
                  <p v-else>尚无对应的成功入账事实。回到支付尝试核对后再决定后续处理。</p>
                </section>
              </div>

              <p v-if="selectedPayment.reservationId" class="h-audit-note">
                关联预约编号 {{ selectedPayment.reservationId }} 已支付，到场核验与履约记录在场馆履约面处理。
                <RouterLink :to="{ name: 'VenueAdminReservations' }">查看场馆履约</RouterLink>
              </p>
            </article>
          </div>

          <!-- 真实概览聚合（IMPL_ONLY：参考无 KPI 区，既有能力不删除，默认收起） -->
          <details v-if="overview" class="fin-overview" data-test="fin-overview">
            <summary>本期范围概览（真实聚合）</summary>
            <dl class="h-meta">
              <div><dt>成功金额</dt><dd>{{ money(overview.successAmount) }}</dd></div>
              <div><dt>手续费</dt><dd>{{ money(overview.feeAmount) }}</dd></div>
              <div><dt>净额</dt><dd>{{ money(overview.netAmount) }}</dd></div>
              <div><dt>成功 / 失败 / 取消 / 超时</dt><dd>{{ overview.successCount }} / {{ overview.failedCount }} / {{ overview.canceledCount }} / {{ overview.timeoutCount }}</dd></div>
            </dl>
            <p v-if="overview.failureReasons.length" class="h-caption">
              失败聚类：{{ overview.failureReasons.map((reason) => `${reason.failureCode || '未归类'} × ${reason.count}`).join('；') }}
            </p>
            <p v-if="overview.dataGaps.length" class="h-caption">数据缺口：{{ overview.dataGaps.join('；') }}</p>
          </details>
        </template>

        <!-- 对账差异：三列对照 + 核对说明 + 调整边界（B5-H6/H7；仅支付对账，储值对账在 /system-admin/stored-value） -->
        <div v-else class="h-section-title">
          <div>
            <h2>把差异留在事实旁</h2>
            <p class="muted">先确认日期与渠道，再选择对应的处理方式。</p>
          </div>
          <button class="btn" type="button" data-test="fin-run-reconcile" @click="openRunDialog">运行对账</button>
        </div>
        <div v-if="view === 'reconciliation' && surface === 'ready'" class="h-columns">
          <aside class="h-rail" aria-label="对账明细">
            <h2>支付明细</h2>
            <p class="h-caption">全部日期 · 共 {{ itemTotal }} 条</p>
            <button
              v-for="item in items"
              :key="item.id"
              class="h-pick"
              type="button"
              :data-test="`fin-diff-${item.reconcileNo}`"
              :aria-pressed="selectedItem?.id === item.id"
              @click="selectItem(item)"
            >
              <strong>{{ itemTitle(item) }}</strong>
              <small>{{ providerRef(item.providerEnv, item.providerName) }}</small>
              <span class="badge" :class="reconcileBadgeTone(item.status)">{{ reconcileStatusLabel(item.status) }}</span>
            </button>
            <div v-if="itemTotal > items.length || itemPage > 1" class="fin-rail-pager" data-test="fin-item-pager">
              <button class="btn" type="button" :disabled="itemPage <= 1" @click="turnItemPage(-1)">上一页</button>
              <button class="btn" type="button" :disabled="itemPage * itemPageSize >= itemTotal" @click="turnItemPage(1)">下一页</button>
            </div>
          </aside>

          <article v-if="selectedItem" class="h-detail">
            <div class="h-detail-head">
              <div>
                <p class="eyebrow">{{ providerRef(selectedItem.providerEnv, selectedItem.providerName) }} / {{ selectedItem.reconcileDate }}</p>
                <h2>{{ itemTitle(selectedItem) }}</h2>
                <span class="badge" :class="reconcileBadgeTone(selectedItem.status)">{{ reconcileStatusLabel(selectedItem.status) }}</span>
              </div>
            </div>
            <div class="h-compare">
              <div><p>本地记录</p><strong>{{ localAmountText(selectedItem) }}</strong></div>
              <div><p>渠道记录</p><strong>{{ providerAmountText(selectedItem) }}</strong></div>
              <div><p>渠道 − 本地</p><strong>{{ differenceText(selectedItem) }}</strong></div>
            </div>
            <h3>核对说明</h3>
            <p>{{ reconcileNote }}</p>
            <p class="h-caption">
              业务 {{ businessTypeLabel(selectedItem.businessType) }} · 对账编号 <span class="h-code">{{ selectedItem.reconcileNo }}</span>
              <template v-if="selectedItem.externalRef"> · 外部流水 <span class="h-code">{{ selectedItem.externalRef }}</span></template>
            </p>
            <!-- 真实列 adjustment_amount NOT NULL DEFAULT 0：0 表示从未调整，不能渲染「已记录调整」 -->
            <div v-if="hasAdjustment(selectedItem)" class="notice" data-test="fin-adjusted-notice">
              已记录调整 {{ money(selectedItem.adjustmentAmount) }}。<br>
              处理说明：{{ selectedItem.adjustmentReason }}<br>
              <small>支付对账记录已更新，资金与储值账本没有变动。</small>
            </div>
            <div class="h-actions">
              <button
                class="btn"
                type="button"
                data-test="fin-adjust"
                :disabled="selectedItem.status === 'MATCHED'"
                @click="openAdjustDialog"
              >记录对账调整</button>
            </div>
            <p class="h-audit-note">此处记录对账调整及原因，不发起转账或更改订单支付金额。</p>
          </article>
        </div>
      </template>
    </div>

    <!-- 回调最小证据（服务端 PaymentCallbackEvidenceView 白名单出参，B5-H1） -->
    <dialog ref="dialogEl" class="h-dialog" @close="focusBack">
      <form v-if="dialogKind === 'callback' && selectedPayment" @submit.prevent>
        <div class="h-dialog-top">
          <h2 id="dialog-title">回调最小证据</h2>
          <button type="button" class="btn" @click="closeDialog">关闭</button>
        </div>
        <span class="badge" :class="callbackBadgeTone(selectedCallback?.status)">{{ callbackStatusLabel(selectedCallback?.status) }}</span>
        <dl class="h-meta">
          <div><dt>订单</dt><dd>{{ selectedPayment.orderNo || '—' }}</dd></div>
          <div><dt>处理结果</dt><dd>{{ callbackEvidenceLine }}</dd></div>
          <div><dt>Provider</dt><dd>{{ selectedCallback?.providerEnv || selectedPayment.providerEnv }} / {{ selectedCallback?.providerName || providerDisplay(selectedPayment) }}</dd></div>
          <div><dt>配置版本</dt><dd><span class="h-code">{{ selectedCallback?.configVersion || selectedPayment.configVersion }}</span></dd></div>
          <div><dt>事件引用</dt><dd><span class="h-code">{{ selectedCallback?.transactionId || '未取得' }}</span></dd></div>
          <div><dt>处理时间</dt><dd>{{ selectedCallback?.createTime || '未取得' }}</dd></div>
        </dl>
        <p class="h-audit-note">仅保留事件摘要与处理结果，不展示原始第三方载荷。</p>
        <footer><button type="button" class="btn" @click="closeDialog">返回</button></footer>
      </form>

      <!-- 运行对账 -->
      <form v-else-if="dialogKind === 'run'" @submit.prevent="submitRun">
        <div class="h-dialog-top">
          <h2 id="dialog-title">运行支付对账</h2>
          <button type="button" class="btn" @click="closeDialog">关闭</button>
        </div>
        <label class="field"><span>对账日期</span><input v-model="runDate" type="date" required data-test="fin-run-date"></label>
        <label v-if="providers.length" class="field">
          <span>渠道范围</span>
          <select v-model="runProviderKey" data-test="fin-run-provider">
            <option v-for="provider in providers" :key="provider.providerKey" :value="provider.providerKey">
              {{ provider.providerEnv }} · {{ provider.displayName }}
            </option>
          </select>
        </label>
        <p class="h-caption">运行只生成账实核对结果，不自动平账。</p>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="fin-run-submit" :disabled="dialogBusy">{{ dialogBusy ? '正在核对…' : '开始对账' }}</button>
        </footer>
      </form>

      <!-- 记录支付对账调整 -->
      <form v-else-if="dialogKind === 'adjust' && selectedItem" @submit.prevent="submitAdjust">
        <div class="h-dialog-top">
          <h2 id="dialog-title">记录支付对账调整</h2>
          <button type="button" class="btn" @click="closeDialog">关闭</button>
        </div>
        <p>{{ itemTitle(selectedItem) }}</p>
        <div class="notice">此操作只更新对账明细的调整金额、原因和处理状态。不会改变订单金额、储值账本或发起资金转移。</div>
        <label class="field">
          <span>记录调整金额（CNY）</span>
          <input v-model="adjustAmount" type="number" step="0.01" required data-test="fin-adjust-amount">
        </label>
        <label class="field">
          <span>处理原因</span>
          <textarea v-model="adjustReason" rows="3" required maxlength="512" placeholder="说明核对依据和本次处理原因" data-test="fin-adjust-reason"></textarea>
        </label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="fin-adjust-submit" :disabled="dialogBusy">保存差异处理</button>
        </footer>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
/* 拾光财务面（SHIGUANG-BATCH-05 S04）：一比一承接冻结参考 finance.js 的支付证据与对账差异（支付）两个工作面。
   参考的 支付/储值 对账双切换按 B5-H6 拆两条路由：本面只承载支付对账，储值对账在 /system-admin/stored-value。
   真实合同见本项 contracts.md；预览审阅情景/固定示例数据不迁入。 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { ElMessage } from 'element-plus'
// 参考 h 组 money()：半角 ¥ + 空格 + 固定两位小数（.sga-fin 作用域专用形态）
import { formatShiguangFinanceMoney as money } from '@/utils/money'
import { fetchVenueName } from '@/utils/entityNames'
import { businessTypeLabel } from '@/utils/presentation'
import { paymentAdminApi } from '@/api/payment'
import { useAuthStore } from '@/stores/auth'
import type {
  PaymentAdminOverviewResponse,
  PaymentAttempt,
  PaymentCallbackLog,
  PaymentProviderOption,
  ReconciliationItem
} from '@/api/types'

type FinanceView = 'payments' | 'reconciliation'
type Surface = 'loading' | 'error' | 'empty' | 'ready'
type DialogKind = 'callback' | 'run' | 'adjust' | null

interface FinanceTab {
  key: string
  label: string
  permission: string
  routeName: string
  /** 本组件就地承接的两个面；其余四条储值面在 /system-admin/stored-value（S05 重建） */
  inPlace: boolean
}

/* 参考六面导航（ref/h/finance.js LABELS 顺序不变）；叶子权限判定用 auth.hasPermission */
const TABS: FinanceTab[] = [
  { key: 'payments', label: '支付证据', permission: 'payment:admin', routeName: 'SystemPayments', inPlace: true },
  { key: 'reconciliation', label: '对账差异', permission: 'finance:reconcile', routeName: 'SystemReconciliation', inPlace: true },
  { key: 'accounts', label: '储值账户', permission: 'stored-value:finance:view', routeName: 'SystemStoredValue', inPlace: false },
  { key: 'recharges', label: '充值处理', permission: 'stored-value:finance:view', routeName: 'SystemStoredValue', inPlace: false },
  { key: 'approvals', label: '审批工作台', permission: 'stored-value:finance:view', routeName: 'SystemStoredValue', inPlace: false },
  { key: 'products', label: '产品与政策', permission: 'stored-value:finance:view', routeName: 'SystemStoredValue', inPlace: false }
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const actorName = computed(() => auth.user?.nickname || auth.user?.username || '管理员')
const canPayments = computed(() => auth.hasPermission('payment:admin'))
const canReconcile = computed(() => auth.hasPermission('finance:reconcile'))
const visibleTabs = computed(() => TABS.filter((tab) => auth.hasPermission(tab.permission)))
const denied = computed(() => visibleTabs.value.length === 0)

const initialView = (): FinanceView => (route?.name === 'SystemReconciliation' ? 'reconciliation' : 'payments')
const view = ref<FinanceView>(initialView())

const surface = ref<Surface>('loading')
const loadError = ref('')
const rootEl = ref<HTMLElement>()
const dialogEl = ref<HTMLDialogElement>()

const providers = ref<PaymentProviderOption[]>([])
const providerKey = ref('')
const attempts = ref<PaymentAttempt[]>([])
const attemptTotal = ref(0)
const attemptPage = ref(1)
const attemptPageSize = 10
const selectedPayment = ref<PaymentAttempt | null>(null)
const selectedCallback = ref<PaymentCallbackLog | null>(null)
const overview = ref<PaymentAdminOverviewResponse | null>(null)

const items = ref<ReconciliationItem[]>([])
const itemTotal = ref(0)
const itemPage = ref(1)
const itemPageSize = 10
const selectedItem = ref<ReconciliationItem | null>(null)

const dialogKind = ref<DialogKind>(null)
const dialogBusy = ref(false)
const dialogError = ref('')
const runDate = ref(todayText())
const runProviderKey = ref('')
const adjustAmount = ref('')
const adjustReason = ref('')
let returnFocus: HTMLElement | null = null

const scopeBadge = computed(() => {
  if (view.value === 'payments') {
    const provider = currentProvider.value
    return provider ? `${provider.providerEnv} / ${provider.providerName}` : '渠道未读取'
  }
  return '支付对账'
})

const currentProvider = computed(() => providers.value.find((provider) => provider.providerKey === providerKey.value) || null)

const providerDisplay = (attempt: PaymentAttempt) => providers.value.find(
  (provider) => provider.providerEnv === attempt.providerEnv && provider.providerName === attempt.providerName
)?.displayName || attempt.providerName

/** 参考详情眉标是场馆名；真实载荷只带 venueId，复用 entityNames 的公开场馆名称读取，失败回落到无名称形态 */
const venueLabel = ref('平台范围')
watch(() => selectedPayment.value?.venueId, async (venueId) => {
  if (!venueId) {
    venueLabel.value = '平台范围'
    return
  }
  venueLabel.value = (await fetchVenueName(venueId)) ?? `场馆 ${venueId}`
}, { immediate: true })

const attemptContextLine = computed(() => {
  const attempt = selectedPayment.value
  if (!attempt) return ''
  const paid = money(attempt.paidAmount || attempt.payableAmount)
  if (attempt.status === 'SUCCEEDED') return `订单 ${attempt.orderNo} 的本次尝试已按 ${paid} 完成支付。`
  return `订单 ${attempt.orderNo} 的本次尝试尚未形成成功支付事实，处理以证据为准。`
})

const attemptEvidenceLine = computed(() => {
  const attempt = selectedPayment.value
  if (!attempt) return ''
  if (attempt.status === 'SUCCEEDED') return '支付已确认成功，成功记录可进入对账。'
  if (attempt.status === 'FAILED') return '尝试失败；不能仅凭超时认定资金已扣除。'
  return '尝试尚未完成；以回调与终态证据为准。'
})

const callbackEvidenceLine = computed(() => {
  const callback = selectedCallback.value
  if (!callback) return '尚未收到成功回调'
  if (callback.status === 'SUCCESS') return callback.message || '已验签 · 已处理'
  if (callback.status === 'RECEIVED') return '回调已接收，处理结果以本地记录为准。'
  if (callback.status === 'IGNORED_DUPLICATE') return '重复回调已忽略，原始成功事实保留。'
  return callback.message || callbackStatusLabel(callback.status)
})

const itemTitle = (item: ReconciliationItem) => item.externalRef || item.reconcileNo

/** adjustment_amount 列 NOT NULL DEFAULT 0：仅非零才视为「已记录调整」 */
const hasAdjustment = (item: ReconciliationItem) =>
  item.adjustmentAmount != null && Number(item.adjustmentAmount) !== 0

/** 参考 pick 副文案与详情眉标的渠道形态：`环境 · 渠道名`（真实字段） */
const providerRef = (providerEnv: string, providerName: string) => `${providerEnv} · ${providerName}`

/**
 * 参考的「渠道 − 本地」列与服务端 `differenceAmount` 的口径相反：
 * `FinanceReconciliationPersistenceService` 写的是 `system_amount − provider_amount`，
 * 而参考（以及旧 `/system-admin/reconciliation` 的调整默认值 `-differenceAmount`）
 * 用的是 `渠道 − 本地`。本面按参考口径换算，直接显示服务端字段会显示成相反符号。
 */
const channelMinusLocal = (item: ReconciliationItem) =>
  Number(item.providerAmount ?? 0) - Number(item.systemAmount ?? 0)

/** 金额输入框沿用参考的裸数字形态（如 -2），不补零 */
const amountInputText = (value: number) => String(Number(value.toFixed(2)))

/** 参考在渠道缺失一侧显示「未取得」、差额「待核对」；真实 MISSING_PROVIDER/MISSING_SYSTEM 与该语义一一对应 */
const isMissingSide = (item: ReconciliationItem) =>
  item.status === 'MISSING_PROVIDER' || item.status === 'MISSING_SYSTEM'
const localAmountText = (item: ReconciliationItem) =>
  item.status === 'MISSING_SYSTEM' ? '未取得' : money(item.systemAmount)
const providerAmountText = (item: ReconciliationItem) =>
  item.status === 'MISSING_PROVIDER' ? '未取得' : money(item.providerAmount)
const differenceText = (item: ReconciliationItem) =>
  isMissingSide(item) ? '待核对' : money(channelMinusLocal(item))

const reconcileNote = computed(() => {
  const item = selectedItem.value
  if (!item) return ''
  if (item.status === 'MATCHED') return '成功金额、费用及净额与渠道账单一致。'
  if (item.status === 'DIFFERENCE') {
    return `渠道账单与本地支付记录相差 ${money(Math.abs(channelMinusLocal(item)))} 元，等待核对费用口径。`
  }
  if (item.status === 'MISSING_PROVIDER') return '当前渠道账单尚未包含本地成功记录；不能推断扣款失败。'
  if (item.status === 'MISSING_SYSTEM') return '渠道账单中的记录在本地没有对应支付事实；先核对来源，不据此入账。'
  if (item.status === 'ADJUSTED') return '差异已按核对结论记录调整；调整只改对账记录本身。'
  return '先核对本地与渠道两侧记录，再决定处理方式。'
})

/* 状态标签（参考 ST 映射 + 真实补充态） */
const ATTEMPT_LABELS: Record<string, string> = {
  CREATED: '已创建', PENDING: '待复核', SUCCEEDED: '已成功', FAILED: '失败',
  CANCELED: '已取消', TIMEOUT: '已超时', CLOSED: '已关闭'
}
const CALLBACK_LABELS: Record<string, string> = {
  RECEIVED: '已接收', SUCCESS: '成功', IGNORED_DUPLICATE: '重复已忽略', SIGNATURE_INVALID: '签名无效',
  AMOUNT_MISMATCH: '金额不一致', ORDER_STATE_INVALID: '订单状态无效', FAILED: '失败'
}
const RECONCILE_LABELS: Record<string, string> = {
  MATCHED: '账实一致', DIFFERENCE: '金额差异', MISSING_PROVIDER: '渠道缺失',
  MISSING_SYSTEM: '本地缺失', ADJUSTED: '已记录调整'
}
const DANGER = new Set(['FAILED', 'EXPIRED', 'MISSING_PROVIDER', 'MISSING_SYSTEM', 'SIGNATURE_INVALID', 'AMOUNT_MISMATCH', 'MANUAL_REVIEW'])
const WARN = new Set(['PENDING', 'APPROVED', 'OPEN', 'DIFFERENCE', 'FROZEN', 'PROVIDER_SUCCEEDED_PENDING_POSTING', 'REFUNDING', 'PENDING_REVIEW', 'UNDER_REVIEW', 'CANCELED', 'TIMEOUT', 'RECEIVED', 'IGNORED_DUPLICATE', 'ORDER_STATE_INVALID', 'CREATED'])
const tone = (status?: string) => {
  const value = status || ''
  if (DANGER.has(value)) return 'danger'
  if (WARN.has(value)) return 'warn'
  return 'success'
}
const attemptStatusLabel = (status?: string) => ATTEMPT_LABELS[status || ''] || status || '—'
const attemptBadgeTone = (status?: string) => tone(status)
const callbackStatusLabel = (status?: string) => CALLBACK_LABELS[status || ''] || status || '未取得'
const callbackBadgeTone = (status?: string) => (status ? tone(status) : 'warn')
const reconcileStatusLabel = (status?: string) => RECONCILE_LABELS[status || ''] || status || '—'
const reconcileBadgeTone = (status?: string) => tone(status)

const shortTime = (value?: string) => (value ? value.replace('T', ' ').slice(5, 16) : '—')

function todayText() {
  const now = new Date()
  now.setMinutes(now.getMinutes() - now.getTimezoneOffset())
  return now.toISOString().slice(0, 10)
}

const readableError = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

/** providers 端点要求 payment:admin；仅对账权限的主体不读取渠道目录，运行对账交由服务端默认渠道解析 */
async function loadProviders() {
  if (!canPayments.value) return
  const all = await paymentAdminApi.providers()
  providers.value = all.filter((provider) => provider.enabled)
  const preferred = providers.value.find((provider) => provider.defaultProvider) || providers.value[0]
  providerKey.value = preferred?.providerKey || ''
  runProviderKey.value = providerKey.value
}

async function loadAttempts(selectFirst = true) {
  const provider = currentProvider.value
  if (!provider) throw new Error('暂无可用支付渠道，请检查后端渠道配置。')
  const page = await paymentAdminApi.attempts({
    providerEnv: provider.providerEnv,
    providerName: provider.providerName,
    pageNum: attemptPage.value,
    pageSize: attemptPageSize
  })
  attempts.value = page.records
  attemptTotal.value = page.total
  if (selectFirst || !attempts.value.some((attempt) => attempt.id === selectedPayment.value?.id)) {
    selectedPayment.value = attempts.value[0] || null
    await loadSelectedCallback()
  }
}

async function loadSelectedCallback() {
  const attempt = selectedPayment.value
  if (!attempt) {
    selectedCallback.value = null
    return
  }
  const page = await paymentAdminApi.callbacks({
    providerEnv: attempt.providerEnv,
    providerName: attempt.providerName,
    orderNo: attempt.orderNo,
    pageNum: 1,
    pageSize: 1
  })
  selectedCallback.value = page.records[0] || null
}

async function loadOverview() {
  // IMPL_ONLY（B5-H4）：真实概览聚合保留为参考没有的实现侧必要件
  const provider = currentProvider.value
  overview.value = await paymentAdminApi.overview({
    providerEnv: provider?.providerEnv,
    providerName: provider?.providerName
  })
}

async function loadItems(selectFirst = true) {
  // 对账面按参考不设渠道筛选：明细 rail 同时呈现各渠道条目（渠道信息在每条明细上）
  const page = await paymentAdminApi.reconciliationItems({
    pageNum: itemPage.value,
    pageSize: itemPageSize
  })
  items.value = page.records
  itemTotal.value = page.total
  if (selectFirst || !items.value.some((item) => item.id === selectedItem.value?.id)) {
    selectedItem.value = items.value[0] || null
  }
}

async function bootstrap() {
  surface.value = 'loading'
  loadError.value = ''
  try {
    if (view.value === 'payments') {
      await loadProviders()
      await Promise.all([loadAttempts(), loadOverview()])
      if (!attempts.value.length) {
        surface.value = 'empty'
        return
      }
    } else {
      await loadProviders()
      await loadItems()
      if (!items.value.length) {
        surface.value = 'empty'
        return
      }
    }
    surface.value = 'ready'
  } catch (error) {
    surface.value = 'error'
    loadError.value = readableError(error, '财务记录加载失败，请重试。')
  }
}

function reload() {
  void bootstrap()
}

onMounted(bootstrap)

watch(() => route.name, (name) => {
  const next = name === 'SystemReconciliation' ? 'reconciliation' : name === 'SystemPayments' ? 'payments' : null
  if (next && next !== view.value) {
    view.value = next
    void bootstrap()
  }
})

function go(tab: FinanceTab) {
  if (!tab.inPlace) {
    // 储值四面由 /system-admin/stored-value 承接（S05 按参考重建）
    void router.push({ name: tab.routeName })
    return
  }
  if (tab.key === view.value) return
  view.value = tab.key as FinanceView
  // 面内切换同步到对应路由，保持六条 path/name/meta 的深链语义
  void router.push({ name: tab.routeName })
  void bootstrap()
}

function selectPayment(attempt: PaymentAttempt) {
  selectedPayment.value = attempt
  returnFocus = null
  void loadSelectedCallback()
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>('.h-detail h2')?.focus({ preventScroll: true }))
}

function onProviderChange(event: Event) {
  providerKey.value = (event.target as HTMLSelectElement).value
  runProviderKey.value = providerKey.value
  attemptPage.value = 1
  surface.value = 'loading'
  void Promise.all([loadAttempts(), loadOverview()]).then(() => {
    surface.value = attempts.value.length ? 'ready' : 'empty'
  }).catch((error) => {
    surface.value = 'error'
    loadError.value = readableError(error, '支付尝试加载失败，请重试。')
  })
}

function turnAttemptPage(delta: number) {
  attemptPage.value = Math.max(1, attemptPage.value + delta)
  surface.value = 'loading'
  void loadAttempts(false).then(() => { surface.value = 'ready' }).catch((error) => {
    surface.value = 'error'
    loadError.value = readableError(error, '支付尝试加载失败，请重试。')
  })
}

function turnItemPage(delta: number) {
  itemPage.value = Math.max(1, itemPage.value + delta)
  surface.value = 'loading'
  void loadItems(false).then(() => { surface.value = 'ready' }).catch((error) => {
    surface.value = 'error'
    loadError.value = readableError(error, '对账明细加载失败，请重试。')
  })
}

function selectItem(item: ReconciliationItem) {
  selectedItem.value = item
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>('.h-detail h2')?.focus({ preventScroll: true }))
}

/** 参考的「核对账实记录」跳转：切换到对账面并按外部流水定位对应明细 */
function checkDifference() {
  const attempt = selectedPayment.value
  if (!attempt) return
  view.value = 'reconciliation'
  void router.push({ name: 'SystemReconciliation' })
  surface.value = 'loading'
  void loadItems()
    .then(() => {
      const match = attempt.externalRef
        ? items.value.find((item) => item.externalRef === attempt.externalRef)
        : undefined
      if (match) selectedItem.value = match
      surface.value = 'ready'
    })
    .catch((error) => {
      surface.value = 'error'
      loadError.value = readableError(error, '对账明细加载失败，请重试。')
    })
}

/* ---- 弹层（原生 dialog，参考 h-dialog 结构） ---- */
function openDialog(kind: Exclude<DialogKind, null>) {
  dialogKind.value = kind
  dialogError.value = ''
  returnFocus = document.activeElement as HTMLElement | null
  if (kind === 'adjust' && selectedItem.value) {
    // 默认值同参考：渠道 − 本地（服务端 differenceAmount 是反号，见 channelMinusLocal）
    adjustAmount.value = amountInputText(channelMinusLocal(selectedItem.value))
    adjustReason.value = ''
  }
  void nextTick(() => dialogEl.value?.showModal())
}

function closeDialog() {
  dialogEl.value?.close()
  dialogKind.value = null
}

function focusBack() {
  const target = returnFocus || rootEl.value?.querySelector<HTMLElement>('[aria-pressed="true"]')
  target?.focus({ preventScroll: true })
  returnFocus = null
}

function openCallbackDialog() {
  openDialog('callback')
}

function openRunDialog() {
  runDate.value = todayText()
  runProviderKey.value = providerKey.value
  openDialog('run')
}

function openAdjustDialog() {
  openDialog('adjust')
}

async function submitRun() {
  if (dialogBusy.value) return
  dialogBusy.value = true
  dialogError.value = ''
  try {
    const provider = providers.value.find((row) => row.providerKey === runProviderKey.value)
    // 渠道字段仅在取得渠道目录时下发；仅对账权限的主体交由服务端默认渠道解析
    const result = await paymentAdminApi.runReconciliation({
      reconcileDate: runDate.value,
      ...(provider ? { providerEnv: provider.providerEnv, providerName: provider.providerName } : {})
    })
    ElMessage.success(`对账完成：${result.reconcileDate} / ${result.providerEnv} · ${result.providerName}，差异 ${result.differenceCount} 条。已打开对应明细。`)
    itemPage.value = 1
    selectedItem.value = null
    view.value = 'reconciliation'
    await loadItems()
    closeDialog()
    surface.value = 'ready'
  } catch (error) {
    dialogError.value = readableError(error, '对账执行失败，请稍后重试。')
  } finally {
    dialogBusy.value = false
  }
}

async function submitAdjust() {
  const item = selectedItem.value
  if (!item || dialogBusy.value) return
  if (!adjustReason.value.trim()) {
    dialogError.value = '处理原因不能为空。'
    return
  }
  dialogBusy.value = true
  dialogError.value = ''
  try {
    // 调整只改对账记录：读回服务端响应，确认资金字段未被改动
    const updated = await paymentAdminApi.adjustReconciliation(item.id, {
      adjustmentAmount: Number(adjustAmount.value),
      adjustmentReason: adjustReason.value.trim()
    })
    if (updated && updated.id != null) {
      const merged = { ...item, ...updated, id: item.id } as ReconciliationItem
      const index = items.value.findIndex((row) => row.id === item.id)
      if (index >= 0) items.value.splice(index, 1, merged)
      selectedItem.value = merged
    }
    closeDialog()
    ElMessage.success('差异处理已记录。资金与账本没有变化。')
  } catch (error) {
    dialogError.value = readableError(error, '调整保存失败，请重试。')
  } finally {
    dialogBusy.value = false
  }
}
</script>

<style scoped>
/* 实现侧必要件（IMPL_ONLY）：rail 服务端分页与概览收纳，参考是固定示例列表没有分页 */
.fin-rail-pager {
  display: flex;
  gap: 8px;
  padding: 12px 0;
}

.fin-overview {
  margin-top: 24px;
  border-top: 1px solid var(--line);
}

.fin-overview summary {
  color: var(--muted);
}

/* 实现侧必要件（IMPL_ONLY）：真实单号 25+ 字符（参考夹具是 O-0912-073 一类短号），
   窄屏实测 h2 撑破 .h-detail-head 造成横向滚动；换行纪律与 .h-pick strong/.h-meta dd 一致 */
.h-detail-head > div {
  min-width: 0;
}

.h-detail-head h2 {
  overflow-wrap: anywhere;
}
</style>
