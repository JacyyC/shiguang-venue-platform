<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Check, Clock } from '@element-plus/icons-vue'
import { storedValueApi, type StoredValueRecharge, type StoredValueSelf } from '@/api/storedvalue'
import { createIdempotencyAttempt } from '@/api/community'
import MemberDialog from './MemberDialog.vue'
import MemberIcon from './MemberIcon.vue'
import { formatMemberMoney, RECHARGE_STATUS_TEXT, RECHARGE_UNCERTAIN_STATUSES } from './memberShared'

/**
 * DEV 储值充值弹层（预览 openRecharge/createRecharge/showRechargeResult 同款）。
 * 真实命令合同：金额按产品快照约束（G7），clientRequestId 幂等（同会话同金额复用，
 * 双击/响应丢失不重复建单）；创建≠到账，结果未知用原单核对（GET /recharges/{rechargeNo}），
 * DEV 回调为开发环境显式动作。
 */
const props = defineProps<{ open: boolean; self: StoredValueSelf | null }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void; (e: 'settled'): void; (e: 'go-recharges'): void }>()

const isolated = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
const attempt = createIdempotencyAttempt('stored-value-recharge')

const stage = ref<'choose' | 'result'>('choose')
const amount = ref(100)
const customAmount = ref('')
const creating = ref(false)
const checking = ref(false)
const callbackBusy = ref(false)
const submitError = ref('')
const recharge = ref<StoredValueRecharge | null>(null)

const product = computed(() => props.self?.product ?? null)
const minAmount = computed(() => Math.max(1, Number(product.value?.minimumRecharge ?? 1)))
const maxAmount = computed(() => Math.max(minAmount.value, Number(product.value?.maximumRecharge ?? 10000)))
const presets = computed(() => {
  const candidates = [100, 200, 500]
  const within = candidates.filter((value) => value >= minAmount.value && value <= maxAmount.value)
  const base = within.length ? within : [minAmount.value]
  return base.slice(0, 3)
})
const bonusRate = computed(() => Number(product.value?.bonusRatePercent ?? 0))
const expectedBonus = computed(() => (amount.value * bonusRate.value) / 100)
const bonusDays = computed(() => Number(product.value?.bonusValidDays ?? 0))
const env = computed(() => product.value?.environment ?? 'DEV')

watch(
  () => props.open,
  (open) => {
    if (!open) return
    stage.value = 'choose'
    recharge.value = null
    submitError.value = ''
    amount.value = presets.value[0]
    customAmount.value = ''
  }
)

const choose = (value: number) => {
  amount.value = value
  customAmount.value = ''
}
const applyCustom = () => {
  const value = Number(customAmount.value)
  if (!Number.isFinite(value)) return
  amount.value = Math.min(maxAmount.value, Math.max(minAmount.value, Math.round(value * 100) / 100))
}

const createRecharge = async () => {
  const current = product.value
  if (!current || creating.value) return
  creating.value = true
  submitError.value = ''
  try {
    const row = await storedValueApi.createRecharge({
      productId: current.id,
      principalAmount: amount.value,
      currency: 'CNY',
      clientRequestId: attempt.keyFor({ productId: current.id, principalAmount: amount.value })
    }, isolated)
    recharge.value = row
    stage.value = 'result'
    emit('settled')
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    submitError.value = message || '充值创建失败或结果未知。请在充值记录中核对原请求，不要重复创建。'
  } finally {
    creating.value = false
  }
}

const checkOriginal = async () => {
  const row = recharge.value
  if (!row || checking.value) return
  checking.value = true
  try {
    const fresh = await storedValueApi.myRecharge(row.rechargeNo, isolated)
    recharge.value = fresh
    emit('settled')
  } catch {
    // 核对读取失败：保持原状态表达，不推定到账
  } finally {
    checking.value = false
  }
}

const devCallback = async (status: 'SUCCESS' | 'FAILED') => {
  const row = recharge.value
  if (!row || callbackBusy.value) return
  callbackBusy.value = true
  try {
    const fresh = await storedValueApi.confirmDevCallback(row.rechargeNo, status, isolated)
    recharge.value = fresh
    emit('settled')
  } catch {
    // 回调失败：以原单核对为准，不重试渠道
  } finally {
    callbackBusy.value = false
  }
}

const close = () => emit('update:open', false)

const currentStatus = computed(() => recharge.value?.status ?? '')
const uncertain = computed(() => RECHARGE_UNCERTAIN_STATUSES.has(currentStatus.value))
const succeeded = computed(() => currentStatus.value === 'SUCCEEDED')
const dialogTitle = computed(() => {
  if (stage.value === 'choose') return '给下一次出发，留一点余额'
  return succeeded.value ? '入账结果' : '已创建，等待渠道确认'
})
</script>

<template>
  <MemberDialog :open="open" :title="dialogTitle" :clarifier="`${env} 环境 · 不产生真实扣款`" @update:open="emit('update:open', $event)">
    <!-- 金额选择 -->
    <template v-if="stage === 'choose'">
      <h3>{{ env }} 环境充值</h3>
      <p>{{ product?.productName || '储值产品' }}：仅限平台内 CNY 订单，不提供提现、转账或分期。</p>
      <div class="choice-amounts" aria-label="充值本金">
        <button v-for="value in presets" :key="value" type="button" :aria-pressed="amount === value && !customAmount" @click="choose(value)">¥{{ value }}</button>
      </div>
      <dl class="detail-list">
        <div><dt>充值本金</dt><dd>¥{{ formatMemberMoney(amount) }}</dd></div>
        <div><dt>预计赠送</dt><dd>¥{{ formatMemberMoney(expectedBonus) }}</dd></div>
        <div><dt>赠送规则</dt><dd>{{ bonusRate }}% · 到账以实际查询为准</dd></div>
        <div v-if="bonusDays"><dt>赠送有效期</dt><dd>{{ bonusDays }} 个自然日</dd></div>
        <div><dt>单次限额</dt><dd>¥{{ formatMemberMoney(minAmount) }} — ¥{{ formatMemberMoney(maxAmount) }}</dd></div>
        <div><dt>环境</dt><dd>{{ env }} · 非真实扣款</dd></div>
      </dl>
      <div v-if="submitError" class="account-banner warning"><MemberIcon name="InfoFilled" /><p>{{ submitError }}</p></div>
      <div v-else class="account-banner"><MemberIcon name="InfoFilled" /><p>下一步只创建充值记录。不会自动声称到账；同一金额重复提交按同一请求核对。</p></div>
    </template>

    <!-- 结果与核对 -->
    <template v-else-if="recharge">
      <div class="dialog-mark" :class="{ pending: !succeeded }">
        <el-icon v-if="succeeded"><Check /></el-icon>
        <el-icon v-else><Clock /></el-icon>
      </div>
      <h3 v-if="succeeded">本金已入账</h3>
      <h3 v-else>创建充值 ≠ 到账</h3>
      <p v-if="succeeded">本金与赠送按实际批次入账，可用余额以账户查询为准。</p>
      <p v-else>原充值请求已经保留。等待期间余额不增加，也不再新建同一笔充值。</p>
      <div style="margin-top:24px">
        <dl class="detail-list">
          <div><dt>充值记录</dt><dd>{{ recharge.rechargeNo }}</dd></div>
          <div><dt>充值本金</dt><dd>¥{{ formatMemberMoney(recharge.principalAmount) }}</dd></div>
          <div><dt>状态</dt><dd>{{ RECHARGE_STATUS_TEXT[currentStatus] ?? currentStatus }}</dd></div>
          <div v-if="succeeded"><dt>实际赠送</dt><dd>¥{{ formatMemberMoney(recharge.actualBonusAmount) }}</dd></div>
        </dl>
      </div>
      <div v-if="!succeeded" class="account-banner"><MemberIcon name="InfoFilled" /><p>{{ env }} 环境可模拟渠道结果；正式环境到账必须经渠道异步确认与账户查询。</p></div>
    </template>

    <template #actions>
      <template v-if="stage === 'choose'">
        <button class="button" type="button" @click="close">取消</button>
        <button class="button primary" type="button" :disabled="creating || !product" data-test="create-recharge" @click="createRecharge">
          {{ creating ? '正在创建…' : '创建充值' }}
        </button>
      </template>
      <template v-else-if="recharge">
        <template v-if="succeeded">
          <button class="button primary" type="button" @click="close">回到账户</button>
        </template>
        <template v-else>
          <button class="button" type="button" @click="close">稍后查看</button>
          <button class="button" type="button" :disabled="checking" data-test="check-recharge" @click="checkOriginal">{{ checking ? '核对中…' : '查询原记录' }}</button>
          <button v-if="env === 'DEV'" class="button" type="button" :disabled="callbackBusy" data-test="dev-callback-success" @click="devCallback('SUCCESS')">模拟渠道确认</button>
          <button v-if="env === 'DEV'" class="button primary" type="button" :disabled="callbackBusy" data-test="dev-callback-failed" @click="devCallback('FAILED')">模拟渠道失败</button>
        </template>
      </template>
    </template>
  </MemberDialog>
</template>
