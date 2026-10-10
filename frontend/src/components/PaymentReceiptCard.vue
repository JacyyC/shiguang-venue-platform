<template>
  <section class="receipt-card sg-ticket-edge">
    <div class="sg-landing-stamp" aria-hidden="true">✓</div>
    <p class="sg-kicker rc-kicker">已完成</p>
    <h1 class="rc-title">支付完成</h1>
    <p class="rc-no">
      NO. <CopyableCode :code="orderShortCode(orderNo)" :full="orderNo" />
    </p>
    <div class="rc-tear" aria-hidden="true"></div>
    <dl class="rc-amount-row">
      <dt>实付金额</dt>
      <dd class="rc-amount sg-num">{{ formatMoney(amount) }}</dd>
    </dl>
    <p v-if="couponDiscountAmount" class="rc-discount">已使用优惠券，优惠 {{ formatMoney(couponDiscountAmount) }}</p>
    <p class="rc-note">场地已为你保留，到场出示核销码即可入场；凭证可在「核销凭证」页随时查看。</p>
    <div class="rc-actions">
      <slot name="actions" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { formatMoney } from '@/utils/money'
import CopyableCode from '@/components/CopyableCode.vue'
import { orderShortCode } from '@/utils/entityNames'

/**
 * 支付回执票据卡（V3-UI-UX-SHIGUANG S04，预览 F2 票据族）：
 * 收银支付页与支付宝沙箱回跳共用一处回执——mask 打孔 + 撕裂线 + 序号 + 衬线金额，
 * 替换两页此前逐行重复的通用落定卡。动作按钮由页面经 actions 槽注入（data-test 契约留在页面）。
 */
withDefaults(defineProps<{
  orderNo: string
  amount: number
  couponDiscountAmount?: number
}>(), {
  couponDiscountAmount: undefined
})

</script>

<style scoped>
.receipt-card {
  width: min(560px, 100%);
  margin: 12px auto 0;
  padding: 36px 36px 30px;
  border-radius: var(--sg-radius-card);
  /* V4：票据回执去白底改极浅 tint（预览 §04 支付页备注：打孔与撕口品牌资产不动） */
  background: color-mix(in srgb, var(--sg-ink) 3.5%, transparent);
  text-align: center;
}

.rc-kicker {
  margin: 0;
}

.rc-title {
  margin: 10px 0 6px;
  color: var(--sg-ink);
  font-family: var(--sg-font-serif);
  font-size: clamp(28px, 4vw, 40px);
  line-height: 1.15;
}

.rc-no {
  margin: 0;
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-sm);
}

.rc-tear {
  border-top: 1px dashed var(--sg-border-strong);
  margin: 16px 24px;
}

.rc-amount-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin: 0;
}

.rc-amount-row dt {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
  font-weight: 600;
}

.rc-amount {
  margin: 0;
  font-family: var(--sg-font-serif);
  font-size: 28px;
  font-weight: 700;
  color: var(--sg-ink);
  font-variant-numeric: tabular-nums;
}

.rc-discount {
  margin: 8px 0 0;
  color: var(--sg-success);
  font-size: var(--sg-fz-sm);
}

.rc-note {
  max-width: 470px;
  margin: 14px auto 0;
  color: var(--sg-ink-muted);
  line-height: 1.65;
}

.rc-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 24px;
}
</style>
