<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { TopRight } from '@element-plus/icons-vue'
import { storedValueApi, type StoredValueBonusBatch, type StoredValueLedgerEntry, type StoredValueRecharge, type StoredValueSelf } from '@/api/storedvalue'
import ShiguangMemberShell, { type DomainState } from './ShiguangMemberShell.vue'
import MemberDialog from './MemberDialog.vue'
import MemberIcon from './MemberIcon.vue'
import MemberRechargeDialog from './MemberRechargeDialog.vue'
import {
  BONUS_BATCH_STATUS_TEXT,
  formatMemberDate,
  formatMemberDateTime,
  formatMemberMoney,
  RECHARGE_STATUS_TEXT,
  RECHARGE_UNCERTAIN_STATUSES,
  storedBucketText
} from './memberShared'

/**
 * 储值账户（/me/stored-value，SHIGUANG-MEMBER-REFACTOR-20260912 S04）。
 * 分账事实（本金/赠送/冻结分桶）、赠送批次、资金流水与充值记录全部读取真实服务端合同；
 * 未开通不显示虚假 ¥0.00、冻结如实表达；?tab=ledger|bonus|recharges 深链刷新可恢复。
 */
type StoredTab = 'ledger' | 'bonus' | 'recharges'

const route = useRoute()
const router = useRouter()
const isolated = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

const tab = computed<StoredTab>(() => {
  const value = String(route.query.tab ?? '')
  return value === 'bonus' || value === 'recharges' ? value : 'ledger'
})
const setTab = (next: StoredTab) => {
  router.push({ path: '/me/stored-value', query: next === 'ledger' ? {} : { tab: next } })
}

const shellRef = ref<InstanceType<typeof ShiguangMemberShell> | null>(null)

/* ---------- 三个页签各自的独立读取（局部失败互不影响） ---------- */

const ledger = ref<StoredValueLedgerEntry[]>([])
const ledgerState = ref<DomainState>('loading')
const ledgerTotal = ref(0)
const ledgerPage = ref(1)
const PAGE_SIZE = 20

const batches = ref<StoredValueBonusBatch[]>([])
const batchesState = ref<DomainState>('loading')

const recharges = ref<StoredValueRecharge[]>([])
const rechargesState = ref<DomainState>('loading')
const rechargesTotal = ref(0)
const rechargesPage = ref(1)

const loadLedger = async (append = false, background = false) => {
  if (!append && !background) ledgerState.value = 'loading'
  try {
    const page = await storedValueApi.ledger({ pageNum: ledgerPage.value, pageSize: PAGE_SIZE }, isolated)
    ledger.value = append ? [...ledger.value, ...(page.records ?? [])] : (page.records ?? [])
    ledgerTotal.value = page.total ?? 0
    ledgerState.value = 'ready'
  } catch {
    if (background && ledgerState.value === 'ready') return
    if (!append) ledger.value = []
    ledgerState.value = 'error'
  }
}

const loadBatches = async (background = false) => {
  if (!background) batchesState.value = 'loading'
  try {
    batches.value = await storedValueApi.bonusBatches(isolated)
    batchesState.value = 'ready'
  } catch {
    if (background && batchesState.value === 'ready') return
    batches.value = []
    batchesState.value = 'error'
  }
}

const loadRecharges = async (append = false, background = false) => {
  if (!append && !background) rechargesState.value = 'loading'
  try {
    const page = await storedValueApi.recharges({ pageNum: rechargesPage.value, pageSize: PAGE_SIZE }, isolated)
    recharges.value = append ? [...recharges.value, ...(page.records ?? [])] : (page.records ?? [])
    rechargesTotal.value = page.total ?? 0
    rechargesState.value = 'ready'
  } catch {
    if (background && rechargesState.value === 'ready') return
    if (!append) recharges.value = []
    rechargesState.value = 'error'
  }
}

watch(tab, (current) => {
  if (current === 'ledger' && ledgerState.value === 'loading') void loadLedger()
  if (current === 'bonus' && batchesState.value === 'loading') void loadBatches()
  if (current === 'recharges' && rechargesState.value === 'loading') void loadRecharges()
}, { immediate: true })

const retryTab = (current: StoredTab) => {
  if (current === 'ledger') void loadLedger()
  else if (current === 'bonus') void loadBatches()
  else void loadRecharges()
}

/* ---------- 充值弹层与结果核对 ---------- */

const rechargeOpen = ref(false)
const rechargeSelf = ref<StoredValueSelf | null>(null)

const openRecharge = (self: StoredValueSelf | null) => {
  rechargeSelf.value = self
  rechargeOpen.value = true
}

const onRechargeSettled = () => {
  rechargesPage.value = 1
  // 成交后静默重读：重入 loading 会让页签闪现空态，也与打开中的弹层状态竞争
  void loadRecharges(false, true)
  void loadLedger(false, true)
  void loadBatches(true)
  shellRef.value?.reload?.()
}

/* ---------- 展示辅助 ---------- */

const storedFrozenAccount = (self: StoredValueSelf | null) =>
  !!self?.account && self.account.status !== 'NORMAL'
const usableTotal = (self: StoredValueSelf | null) =>
  Number(self?.account?.principalAvailable ?? 0) + Number(self?.account?.bonusAvailable ?? 0)
const frozenTotal = (self: StoredValueSelf | null) =>
  Number(self?.account?.principalFrozen ?? 0) + Number(self?.account?.bonusFrozen ?? 0)
const envText = (self: StoredValueSelf | null) => self?.product?.environment ?? 'DEV'

const ledgerTitle = (entry: StoredValueLedgerEntry) => entry.reason?.trim() || entry.transactionType
const ledgerAmountText = (entry: StoredValueLedgerEntry) =>
  `${entry.direction === 'CREDIT' ? '+' : '−'}¥${formatMemberMoney(Math.abs(Number(entry.amount ?? 0)))}`

const rechargeTitle = (row: StoredValueRecharge) => {
  if (row.status === 'SUCCEEDED') return '开发环境充值 · 已入账'
  if (RECHARGE_UNCERTAIN_STATUSES.has(row.status)) return '开发环境充值 · 等待核对'
  return '开发环境充值'
}
const rechargeDetail = (row: StoredValueRecharge) =>
  `本金 ¥${formatMemberMoney(row.principalAmount)} · 预计赠送 ¥${formatMemberMoney(row.expectedBonusAmount)}${row.status === 'SUCCEEDED' ? ` · 实际赠送 ¥${formatMemberMoney(row.actualBonusAmount)}` : ''}`
const rechargeStatusText = (row: StoredValueRecharge) => RECHARGE_STATUS_TEXT[row.status] ?? row.status

const batchTitle = (batch: StoredValueBonusBatch, index: number) => `充值赠送批次 #${batches.value.length - index}`
const rulesOpen = ref(false)
</script>

<template>
  <ShiguangMemberShell ref="shellRef" active-tab="stored" v-slot="{ storedSelf, storedState, retryDomain }">
    <!-- 储值域读取失败/无权限：独立错误态，不影响其他域 -->
    <div v-if="storedState === 'denied'" class="state-panel" data-test="stored-denied">
      <div class="state-line" aria-hidden="true"></div>
      <h2>当前账号没有储值访问权限</h2>
      <p>这里不显示储值账户信息；会员积分与优惠券不受影响。</p>
    </div>
    <div v-else-if="storedState === 'error'" class="state-panel" data-test="stored-error">
      <div class="state-line" aria-hidden="true"></div>
      <h2>储值账户暂时无法读取</h2>
      <p>这是读取失败，不是你的余额变成零。请重试读取。</p>
      <button class="button primary" type="button" @click="retryDomain('stored')">重新读取</button>
    </div>

    <!-- 未开通：不显示虚假 ¥0.00，不给不可执行的充值按钮 -->
    <section v-else-if="storedState === 'ready' && !storedSelf?.account">
      <div class="section-intro">
        <div>
          <h2>储值账户</h2>
          <p>可用余额、本金、赠送与冻结金额分别记录。</p>
        </div>
        <span class="environment">{{ envText(storedSelf) }}</span>
      </div>
      <div class="state-panel" data-test="stored-unavailable">
        <div class="state-line" aria-hidden="true"></div>
        <h2>储值服务暂未开通</h2>
        <p>{{ storedSelf?.eligibilityMessage || '当前没有可用的储值账户，因此不展示虚假的 ¥0.00，也不提供不可执行的充值按钮。会员积分与优惠券不受影响。' }}</p>
        <button class="button" type="button" @click="rulesOpen = true">了解储值规则</button>
      </div>
    </section>

    <section v-else-if="storedState === 'ready' && storedSelf?.account">
      <div class="section-intro">
        <div>
          <h2>每一笔，都清清楚楚。</h2>
          <p>只用于平台内人民币订单，不是可提现的钱包。</p>
        </div>
        <button class="text-button" type="button" @click="rulesOpen = true">账户规则<el-icon><TopRight /></el-icon></button>
      </div>
      <div class="account-banner" :class="{ warning: storedFrozenAccount(storedSelf) }">
        <MemberIcon name="InfoFilled" />
        <p v-if="storedFrozenAccount(storedSelf)">账户已冻结，充值与支付暂停。余额记录仍可查看，恢复需由有权限的运营人员处理。</p>
        <p v-else>{{ envText(storedSelf) }} 环境储值 · 不会产生真实扣款。正式到账必须经渠道结果与账户查询确认。</p>
      </div>
      <div class="stored-summary">
        <div class="stored-main">
          <h2>{{ storedFrozenAccount(storedSelf) ? '账面可用余额 · 当前不可支付' : '可用储值余额' }} <span class="environment">CNY</span></h2>
          <div class="stored-total"><small>¥</small>{{ formatMemberMoney(usableTotal(storedSelf)) }}</div>
          <p>不含冻结 ¥{{ formatMemberMoney(frozenTotal(storedSelf)) }} · 本金与可用赠送相加</p>
          <button class="button primary" type="button" :disabled="storedFrozenAccount(storedSelf)" data-test="open-recharge" @click="openRecharge(storedSelf)">
            {{ storedFrozenAccount(storedSelf) ? '账户冻结 · 暂不可充值' : '查看充值方案' }}<el-icon v-if="!storedFrozenAccount(storedSelf)"><TopRight /></el-icon>
          </button>
        </div>
        <dl class="stored-components">
          <div class="stored-component">
            <dt>本金可用<small>充值后实际入账的本金</small></dt>
            <dd><small>¥</small>{{ formatMemberMoney(storedSelf.account.principalAvailable) }}</dd>
          </div>
          <div class="stored-component">
            <dt>赠送可用<small>按最早到期批次优先使用</small></dt>
            <dd><small>¥</small>{{ formatMemberMoney(storedSelf.account.bonusAvailable) }}</dd>
          </div>
          <div class="stored-component">
            <dt>冻结金额<small>不可用于本次支付，不计入可用余额</small></dt>
            <dd><small>¥</small>{{ formatMemberMoney(frozenTotal(storedSelf)) }}</dd>
          </div>
        </dl>
      </div>

      <div class="filter-bar">
        <div class="filter-options" aria-label="储值明细页签">
          <button type="button" :aria-pressed="tab === 'ledger'" @click="setTab('ledger')">资金流水</button>
          <button type="button" :aria-pressed="tab === 'bonus'" @click="setTab('bonus')">赠送批次</button>
          <button type="button" :aria-pressed="tab === 'recharges'" @click="setTab('recharges')">充值记录</button>
        </div>
        <span class="filter-caption">各账桶独立记录，退款按原桶回补</span>
      </div>

      <!-- 资金流水 -->
      <template v-if="tab === 'ledger'">
        <div v-if="ledgerState === 'error'" class="state-panel">
          <div class="state-line" aria-hidden="true"></div>
          <h2>资金流水暂时无法读取</h2>
          <p>请重试读取；流水缺失不作为零流水处理。</p>
          <button class="button primary" type="button" @click="retryTab('ledger')">重新读取</button>
        </div>
        <template v-else-if="ledger.length">
          <div>
            <article v-for="entry in ledger" :key="`${entry.transactionId}-${entry.bucket}-${entry.direction}-${entry.amount}`" class="ledger-row">
              <div>
                <h3>{{ ledgerTitle(entry) }}</h3>
                <p>{{ entry.transactionType }} · {{ entry.sourceType }}</p>
              </div>
              <time>{{ formatMemberDateTime(entry.occurredAt) }}</time>
              <span class="ledger-bucket">{{ storedBucketText(entry.bucket) }}{{ entry.direction === 'CREDIT' ? '入账' : '支出' }}</span>
              <span class="ledger-amount" :class="{ positive: entry.direction === 'CREDIT' }">{{ ledgerAmountText(entry) }}</span>
            </article>
          </div>
          <button v-if="ledger.length < ledgerTotal" class="button" type="button" style="margin-top:18px" @click="ledgerPage += 1; loadLedger(true)">加载更早的流水</button>
        </template>
        <div v-else class="inline-empty">
          <h3>还没有资金流水</h3>
          <p>充值、支付与退款回补都会按账桶逐笔记录。</p>
        </div>
        <p class="footnote">此处是部分近期流水，不用可见行相加推算全部账户余额。完整账本需由服务端提供分页和对账依据。</p>
      </template>

      <!-- 赠送批次 -->
      <template v-else-if="tab === 'bonus'">
        <div v-if="batchesState === 'error'" class="state-panel">
          <div class="state-line" aria-hidden="true"></div>
          <h2>赠送批次暂时无法读取</h2>
          <p>请重试读取；批次缺失不作为无赠送处理。</p>
          <button class="button primary" type="button" @click="retryTab('bonus')">重新读取</button>
        </div>
        <div v-else-if="batches.length">
          <article v-for="(batch, index) in batches" :key="batch.id" class="bonus-row">
            <div>
              <h3>{{ batchTitle(batch, index) }}</h3>
              <p>{{ formatMemberDate(batch.issuedAt) }} 发放 · 原赠送 ¥{{ formatMemberMoney(batch.originalAmount) }}</p>
            </div>
            <div>
              <span class="small">{{ formatMemberDate(batch.expiresAt) }} 到期</span>
              <p>{{ batch.status === 'AVAILABLE' ? '按最早到期顺序使用' : '到期金额不计入可用余额' }}</p>
            </div>
            <strong class="num">¥{{ formatMemberMoney(batch.availableAmount) }}</strong>
            <span class="status" :class="batch.status === 'AVAILABLE' ? '' : 'neutral'">{{ BONUS_BATCH_STATUS_TEXT[batch.status] ?? batch.status }}</span>
          </article>
        </div>
        <div v-else class="inline-empty">
          <h3>还没有赠送批次</h3>
          <p>充值赠送按批次记录有效期，按最早到期顺序使用。</p>
        </div>
        <p class="footnote">批次金额以账户查询为准，不在前端自行延期或回补。</p>
      </template>

      <!-- 充值记录 -->
      <template v-else>
        <div v-if="rechargesState === 'error'" class="state-panel">
          <div class="state-line" aria-hidden="true"></div>
          <h2>充值记录暂时无法读取</h2>
          <p>请重试读取；记录缺失不作为无充值处理。</p>
          <button class="button primary" type="button" @click="retryTab('recharges')">重新读取</button>
        </div>
        <template v-else-if="recharges.length">
          <div>
            <article v-for="row in recharges" :key="row.rechargeNo" class="ledger-row">
              <div>
                <h3>{{ rechargeTitle(row) }}</h3>
                <p>{{ rechargeDetail(row) }}</p>
              </div>
              <time>{{ formatMemberDateTime(row.createTime) }}</time>
              <span class="ledger-bucket">{{ rechargeStatusText(row) }}</span>
              <span class="ledger-amount">¥{{ formatMemberMoney(row.principalAmount) }}</span>
            </article>
          </div>
          <button v-if="recharges.length < rechargesTotal" class="button" type="button" style="margin-top:18px" @click="rechargesPage += 1; loadRecharges(true)">加载更早的记录</button>
        </template>
        <div v-else class="inline-empty">
          <h3>还没有充值记录</h3>
          <p>创建充值不等于已经到账；返回、刷新或结果未知，都不应再创建同一笔充值。</p>
        </div>
        <p class="footnote">创建充值不等于已经到账。返回、刷新或结果未知，都不应再创建同一笔充值。</p>
      </template>
    </section>

    <!-- v-if 防止未拿到产品快照的关闭弹层把「预计赠送 ¥0.00」捏造进文档（未开通不显示虚假 ¥0.00） -->
    <MemberRechargeDialog
      v-if="rechargeSelf"
      v-model:open="rechargeOpen"
      :self="rechargeSelf"
      @settled="onRechargeSettled"
      @go-recharges="setTab('recharges')"
    />

    <MemberDialog v-model:open="rulesOpen" title="储值账户规则">
      <div class="document-copy">
        <h3>封闭使用，不是现金钱包。</h3>
        <p>储值仅用于平台内 CNY 订单，全额支付；不设计提现、转账、跨币种或分期能力。环境保留 DEV / SANDBOX 标识。</p>
        <h3>本金、赠送、冻结分开。</h3>
        <p>可用余额只含本金可用与赠送可用，不含冻结金额。赠送按最早到期批次优先使用；订单退款按原支付桶回补，不将赠送变成本金。特殊补偿有效期由政策决定。</p>
        <h3>创建充值不是到账。</h3>
        <p>响应未知不重复发起。渠道成功后仍须完成入账与账户查询；充值额度、赠送比例、身份核验、支付开关和政策版本均由服务端决定。</p>
        <h3>不在这一轮扩权。</h3>
        <p>身份核验、冻结解除、退款与人工调整继续由现有有权限的后台能力处理。个人页不给用户伪造自助权限。</p>
      </div>
      <template #actions>
        <button class="button primary" type="button" @click="rulesOpen = false">知道了</button>
      </template>
    </MemberDialog>
  </ShiguangMemberShell>
</template>
