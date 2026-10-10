<template>
  <section class="d-limited">
    <div class="d-notice">支付宝沙箱返回 · 返回本身不是支付成功凭据。</div>
    <h2>已回到拾光</h2>
    <p>返回信息不作为支付成功凭据。{{ paymentNoLabel ? `请核对原支付单 ${paymentNo} 的当前状态。` : '未读到原支付单号，请从支付记录重新进入核对。' }}</p>
    <button class="d-btn primary" type="button" data-test="recover-payment" :disabled="busy || !paymentNo" @click="recoverPayment">
      {{ busy ? '正在核对…' : '核对付款结果' }}
    </button>
    <p v-if="recoverError" class="d-error" role="alert" style="margin-top:18px">{{ recoverError }}</p>
    <section v-if="paid" class="d-state" style="margin-top:28px">
      <h2>付款已确认</h2>
      <p>订单状态已更新为已支付，接下来可以准备到场。</p>
      <button class="d-btn primary" type="button" data-test="return-to-reservations" @click="goReservations">返回预约记录</button>
    </section>
    <section v-else-if="failed" class="d-state" style="margin-top:28px">
      <h2>这笔付款没有完成</h2>
      <p>订单仍待支付，可以返回收银处重新获取支付单。</p>
      <button class="d-btn" type="button" data-test="return-to-payment" @click="goPayment">返回收银处</button>
    </section>
    <p v-else class="d-muted" style="margin-top:25px">核对完成前，请勿重复发起付款。</p>
    <router-link class="d-link" to="/me/feedback">需要帮助</router-link>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { orderApi } from '@/api/order'
import type { PaymentIntentResponse } from '@/api/types'

/** 支付渠道返回承接（预览 transactions.js sandbox() 屏的真实路由版）：
 * 轮询核对原支付单直到终态，返回本身不作为成功凭据。 */
const MAX_POLLS = 30
const POLL_INTERVAL_MS = 2000
const TERMINAL_FAILURES = new Set(['FAILED', 'CANCELED', 'TIMEOUT', 'CLOSED'])
const paymentNoPattern = /^[A-Za-z0-9_-]{1,128}$/

const router = useRouter()
const props = defineProps<{ embedded?: boolean }>()

const paymentNo = ref<string | null>(null)
const intent = ref<PaymentIntentResponse | null>(null)
const busy = ref(false)
const recoverError = ref('')
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollCount = 0

const paymentNoLabel = computed(() => Boolean(paymentNo.value))
const status = computed(() => intent.value?.status || '')
const paid = computed(() => ['SUCCEEDED', 'SUCCESS'].includes(status.value))
const failed = computed(() => TERMINAL_FAILURES.has(status.value))

function readPaymentNo(): string | null {
  const params = new URLSearchParams(window.location.search)
  const candidates = [params.get('paymentNo'), params.get('out_trade_no')]
  for (const candidate of candidates) {
    if (candidate && paymentNoPattern.test(candidate)) return candidate
  }
  return null
}

async function poll(): Promise<void> {
  if (!paymentNo.value) return
  try {
    intent.value = await orderApi.paymentIntent(paymentNo.value)
  } catch {
    // 单次读取失败不终止轮询；超过上限后由用户手动重试
  }
  if (paid.value || failed.value) return
  pollCount += 1
  if (pollCount < MAX_POLLS) {
    pollTimer = setTimeout(() => { void poll() }, POLL_INTERVAL_MS)
  }
}

async function recoverPayment(): Promise<void> {
  if (!paymentNo.value || busy.value) return
  busy.value = true
  recoverError.value = ''
  try {
    intent.value = await orderApi.paymentIntent(paymentNo.value)
    if (!paid.value && !failed.value && intent.value.status) {
      recoverError.value = '这笔支付仍在处理中，请稍后再核对。'
    }
  } catch {
    recoverError.value = '暂时读不到原支付单结果，请稍后重试。'
  } finally {
    busy.value = false
  }
}

function goReservations(): void {
  if (props.embedded) {
    router.push({ hash: '#reservations' })
    return
  }
  router.push('/me/reservations')
}

function goPayment(): void {
  const orderId = intent.value?.orderId
  if (orderId != null) {
    router.push(`/me/orders/${orderId}/pay`)
    return
  }
  router.push('/me/reservations')
}

onMounted(() => {
  paymentNo.value = readPaymentNo()
  if (paymentNo.value) void poll()
})
</script>
