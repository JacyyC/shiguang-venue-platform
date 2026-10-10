<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Check, Clock, Warning } from '@element-plus/icons-vue'
import { memberRewardApi } from '@/api/member'
import { createIdempotencyAttempt } from '@/api/community'
import type { MemberAccountResponse, MemberRewardCatalogResponse, MemberRewardRedemptionResponse } from '@/api/types'
import MemberDialog from './MemberDialog.vue'
import MemberIcon from './MemberIcon.vue'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import { formatMemberInt, rewardUnavailableText, sceneForReward } from './memberShared'

/**
 * 积分权益兑换弹层（预览 openReward/confirmReward/showRewardResult 同款）。
 * 真实命令合同：弹层打开时重取 preview 绑定生效版本（不凭列表缓存提交），
 * 同一弹层会话复用同一幂等键（双击防重、响应丢失重放不重复扣减），
 * 未知结果保留原请求身份并允许核对（G8）。
 */
const props = defineProps<{ open: boolean; item: MemberRewardCatalogResponse | null; account: MemberAccountResponse | null }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void; (e: 'settled'): void; (e: 'go-coupons'): void; (e: 'go-records'): void }>()

const isolated = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
const attempt = createIdempotencyAttempt('member-reward-redeem')

const stage = ref<'confirm' | 'result'>('confirm')
const preview = ref<MemberRewardCatalogResponse | null>(null)
const previewError = ref('')
const redeeming = ref(false)
const checking = ref(false)
const submitError = ref('')
const result = ref<MemberRewardRedemptionResponse | null>(null)

const item = computed(() => props.item)
const scene = computed(() => (item.value ? sceneForReward(item.value) : 'together'))
const reason = computed(() => (preview.value ? rewardUnavailableText(preview.value) : ''))
const dialogTitle = computed(() => {
  if (stage.value === 'confirm') return '兑换这份权益'
  const status = (result.value?.status ?? '').toUpperCase()
  if (status === 'PENDING' || status === 'FAILED') return '结果待确认'
  if (status === 'REJECTED') return '未能完成兑换'
  return '这份心意，已收进券夹'
})

watch(
  () => [props.open, props.item?.itemId] as const,
  async ([open]) => {
    if (!open || !item.value) return
    stage.value = 'confirm'
    result.value = null
    submitError.value = ''
    previewError.value = ''
    preview.value = null
    try {
      preview.value = await memberRewardApi.preview(item.value.itemId, isolated)
    } catch {
      previewError.value = '权益条件暂时读取失败，请稍后重试。'
    }
  }
)

const close = () => emit('update:open', false)

const confirmRedeem = async () => {
  const current = preview.value
  if (!current?.versionId || !current.eligible || redeeming.value) return
  redeeming.value = true
  submitError.value = ''
  try {
    const record = await memberRewardApi.redeem(
      current.itemId,
      { catalogVersionId: current.versionId, idempotencyKey: attempt.keyFor({ itemId: current.itemId, versionId: current.versionId }) },
      isolated
    )
    result.value = record
    stage.value = 'result'
    const status = (record.status ?? '').toUpperCase()
    if (status !== 'PENDING') attempt.complete()
    emit('settled')
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    if (status === 409) {
      // 版本失效或并发变更：重取 preview 绑定新版本，不吞错不伪装
      submitError.value = (error as { response?: { data?: { message?: string } } })?.response?.data?.message || '兑换条件已变化，请重新确认。'
      try {
        preview.value = await memberRewardApi.preview(current.itemId, isolated)
      } catch {
        previewError.value = '权益条件暂时读取失败，请稍后重试。'
      }
    } else {
      // 响应丢失/未知：保留同一幂等键，允许以「核对原请求」重放（不换新键重复提交）
      submitError.value = '兑换结果未知。可再次确认，系统会按同一请求核对，不会重复扣减。'
    }
  } finally {
    redeeming.value = false
  }
}

const checkOriginal = async () => {
  const current = preview.value
  if (!current?.versionId || checking.value) return
  checking.value = true
  try {
    const record = await memberRewardApi.redeem(
      current.itemId,
      { catalogVersionId: current.versionId, idempotencyKey: attempt.keyFor({ itemId: current.itemId, versionId: current.versionId }) },
      isolated
    )
    result.value = record
    const status = (record.status ?? '').toUpperCase()
    if (status !== 'PENDING') attempt.complete()
    emit('settled')
  } catch {
    // 核对仍未确定：保持 PENDING 表达，不写成成功也不写成失败
  } finally {
    checking.value = false
  }
}

const resultStatus = computed(() => (result.value?.status ?? '').toUpperCase())
</script>

<template>
  <MemberDialog :open="open" :title="dialogTitle" @update:open="emit('update:open', $event)">
    <!-- 确认阶段 -->
    <template v-if="stage === 'confirm' && item">
      <figure class="dialog-art"><ShiguangArt :scene="scene" /></figure>
      <h3>{{ item.itemName }}</h3>
      <p>{{ item.descriptionText || '兑换后优惠券将放入券夹。' }}</p>
      <div class="dialog-cost"><strong class="num">{{ formatMemberInt(item.pointsCost) }}</strong><small>积分 · 不扣成长值</small></div>
      <dl class="detail-list">
        <div><dt>可用积分</dt><dd>{{ preview ? `${formatMemberInt(preview.availablePoints)} 分` : '读取中…' }}</dd></div>
        <div v-if="preview && Number(preview.pendingOffsetPoints) > 0"><dt>待抵扣</dt><dd>{{ formatMemberInt(preview.pendingOffsetPoints) }} 分</dd></div>
        <div><dt>会员门槛</dt><dd>{{ item.minimumLevelName }} · 成长值 ≥ {{ formatMemberInt(item.minimumGrowthThreshold) }}</dd></div>
        <div><dt>当前成长值</dt><dd>{{ formatMemberInt(account?.growthValue) }}</dd></div>
        <div v-if="item.validityDays"><dt>券有效期</dt><dd>兑换后 {{ item.validityDays }} 天</dd></div>
        <div><dt>兑换版本</dt><dd>{{ preview?.versionNo ? `v${preview.versionNo} · 当前生效版本` : '读取中…' }}</dd></div>
      </dl>
      <div v-if="previewError" class="account-banner warning"><MemberIcon name="InfoFilled" /><p>{{ previewError }}</p></div>
      <div v-else-if="reason" class="account-banner warning"><MemberIcon name="InfoFilled" /><p>{{ reason }}，暂不能确认兑换。可以返回目录查看其他权益。</p></div>
      <div v-else-if="submitError" class="account-banner warning"><MemberIcon name="InfoFilled" /><p>{{ submitError }}</p></div>
      <div v-else class="account-banner"><MemberIcon name="InfoFilled" /><p>正式兑换由服务端再次检查版本、积分与资格；同一请求重复提交不会重复扣减。</p></div>
    </template>

    <!-- 结果阶段 -->
    <template v-else-if="stage === 'result' && result">
      <div class="dialog-mark" :class="{ pending: resultStatus === 'PENDING' || resultStatus === 'FAILED' }">
        <el-icon v-if="resultStatus === 'SUCCEEDED'"><Check /></el-icon>
        <el-icon v-else-if="resultStatus === 'PENDING' || resultStatus === 'FAILED'"><Clock /></el-icon>
        <el-icon v-else><Warning /></el-icon>
      </div>
      <h3 v-if="resultStatus === 'SUCCEEDED'">兑换完成</h3>
      <h3 v-else-if="resultStatus === 'PENDING'">先核对，不重复兑换。</h3>
      <h3 v-else-if="resultStatus === 'FAILED'">结果尚未确定，请核对。</h3>
      <h3 v-else>这次没有兑换成功。</h3>
      <p v-if="resultStatus === 'SUCCEEDED'">已使用 {{ formatMemberInt(result.pointsCost) }} 积分，成长值不变。优惠券已放入券夹。</p>
      <p v-else-if="resultStatus === 'PENDING' || resultStatus === 'FAILED'">保留同一请求身份，积分与券夹暂不推定变化；关掉页面也不代表取消。</p>
      <p v-else>{{ result.failureMessage || '兑换未通过，积分未扣减。' }}</p>
      <div style="margin-top:24px">
        <dl class="detail-list">
          <div><dt>权益</dt><dd>{{ result.itemName }}</dd></div>
          <div><dt>请求记录</dt><dd>{{ result.redemptionNo }}</dd></div>
          <div><dt>当前结果</dt><dd>{{ resultStatus === 'SUCCEEDED' ? '已兑换' : resultStatus === 'PENDING' ? '处理中 · 非成功' : resultStatus === 'FAILED' ? '失败 · 待核对' : '未通过' }}</dd></div>
        </dl>
      </div>
    </template>

    <template #actions>
      <template v-if="stage === 'confirm'">
        <button class="button" type="button" @click="close">再想想</button>
        <button class="button primary" type="button" :disabled="!preview || !preview.eligible || redeeming" data-test="confirm-redeem" @click="confirmRedeem">
          {{ redeeming ? '正在核对兑换结果…' : reason ? '暂不可兑换' : '确认兑换' }}
        </button>
      </template>
      <template v-else-if="resultStatus === 'SUCCEEDED'">
        <button class="button" type="button" @click="close">继续看看</button>
        <button class="button primary" type="button" @click="emit('go-coupons')">打开优惠券夹</button>
      </template>
      <template v-else-if="resultStatus === 'PENDING' || resultStatus === 'FAILED'">
        <button class="button" type="button" @click="emit('go-records')">查看记录</button>
        <button class="button primary" type="button" :disabled="checking" data-test="check-redeem" @click="checkOriginal">{{ checking ? '核对原请求中…' : '核对原请求' }}</button>
      </template>
      <button v-else class="button primary" type="button" @click="close">知道了</button>
    </template>
  </MemberDialog>
</template>
