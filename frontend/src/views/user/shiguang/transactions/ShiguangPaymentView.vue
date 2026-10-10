<template>
  <div class="sgv-user-service">
    <div class="d-top">
      <div>
        <p class="d-eyebrow">我的预约与订单</p>
        <h1>先核对，再完成付款。</h1>
        <p class="d-intro">付款以订单金额为准。若结果尚不确定，先核对原支付单，再决定下一步。</p>
      </div>
    </div>
    <button class="d-link" type="button" @click="backToOrders">← 返回记录</button>
    <div id="transaction-content" data-route-focus tabindex="-1" style="margin-top:12px">
      <ShiguangTransactionsPaymentPanel :order-id="orderId" @settled="refresh" @leave="backToOrders" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ShiguangTransactionsPaymentPanel from '@/views/user/shiguang/transactions/ShiguangTransactionsPaymentPanel.vue'

/** 独立收银路由（承接旧 /me/orders/:id/pay 深链与预约创建流程跳转）。 */
const route = useRoute()
const router = useRouter()
const settledCount = ref(0)

const orderId = computed(() => Number(route.params.id))

function backToOrders(): void {
  router.push('/me/orders')
}

function refresh(): void {
  settledCount.value += 1
}
void settledCount
</script>
