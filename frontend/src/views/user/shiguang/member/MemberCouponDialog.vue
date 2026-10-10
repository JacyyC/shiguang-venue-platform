<script setup lang="ts">
import { computed } from 'vue'
import type { UserCouponResponse } from '@/api/types'
import MemberDialog from './MemberDialog.vue'
import MemberIcon from './MemberIcon.vue'
import {
  couponFaceValue,
  couponNoticeText,
  couponRuleText,
  couponSourceText,
  couponStatusText,
  formatMemberDate,
  formatMemberMoney,
  isCouponUsable
} from './memberShared'

/** 券详情弹层（预览 openCoupon 同款：面额、规则、状态、来源、关联订单与状态说明）。 */
const props = defineProps<{ open: boolean; coupon: UserCouponResponse | null }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void; (e: 'browse'): void }>()

const coupon = computed(() => props.coupon)
const usable = computed(() => (coupon.value ? isCouponUsable(coupon.value) : false))
const notice = computed(() => (coupon.value ? couponNoticeText(coupon.value) : { warning: false, text: '' }))
const thresholdText = computed(() => formatMemberMoney(coupon.value?.thresholdAmount ?? 0))
const validityText = computed(() =>
  coupon.value ? `${formatMemberDate(coupon.value.validStartTime)} — ${formatMemberDate(coupon.value.validEndTime)}` : ''
)
</script>

<template>
  <MemberDialog :open="open" title="这一张，怎么用" @update:open="emit('update:open', $event)">
    <template v-if="coupon">
      <div class="dialog-cost"><strong class="accent num">{{ couponFaceValue(coupon) }}<small>{{ coupon.couponType === 'PERCENT' ? '折' : '' }}</small></strong></div>
      <h3>{{ coupon.templateName }}</h3>
      <p>{{ couponRuleText(coupon) }}</p>
      <div style="margin-top:24px">
        <dl class="detail-list">
          <div><dt>当前状态</dt><dd>{{ couponStatusText(coupon) }}</dd></div>
          <div><dt>最低订单额</dt><dd>¥{{ thresholdText }}</dd></div>
          <div><dt>有效期</dt><dd>{{ validityText }}</dd></div>
          <div><dt>来源</dt><dd>{{ couponSourceText(coupon) }}</dd></div>
          <div v-if="coupon.orderId"><dt>关联订单</dt><dd>订单 #{{ coupon.orderId }}</dd></div>
          <div v-else-if="coupon.reservationId"><dt>关联预约</dt><dd>预约 #{{ coupon.reservationId }}</dd></div>
        </dl>
      </div>
      <div class="account-banner" :class="{ warning: notice.warning }">
        <MemberIcon name="InfoFilled" /><p>{{ notice.text }}</p>
      </div>
    </template>
    <template #actions>
      <template v-if="usable">
        <button class="button" type="button" @click="emit('update:open', false)">收好</button>
        <button class="button primary" type="button" @click="emit('browse')">去安排下一次</button>
      </template>
      <button v-else class="button primary" type="button" @click="emit('update:open', false)">知道了</button>
    </template>
  </MemberDialog>
</template>
