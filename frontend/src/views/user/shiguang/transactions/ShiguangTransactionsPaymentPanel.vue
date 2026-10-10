<template>
  <div class="d-detail">
    <section>
      <p class="d-eyebrow" data-test="payment-order-no">{{ order?.orderNo || '订单读取中' }}</p>
      <h2>{{ venueLabel }}<template v-if="spaceLabel"><br>{{ spaceLabel }}</template></h2>
      <p>{{ whenLabel(reservation?.startTime) }} {{ timeRange(reservation?.startTime, reservation?.endTime) }}</p>

      <section v-if="screenState === 'paid'" class="d-state">
        <h2>付款已确认</h2>
        <p>订单状态已更新为已支付，接下来可以准备到场。</p>
        <button class="d-btn primary" type="button" data-test="payment-to-credential" @click="goCredential">查看到场凭证</button>
      </section>

      <section v-else-if="screenState === 'closed'" class="d-state">
        <h2>当前订单{{ orderStatusLabel(order?.status || '') }}</h2>
        <p>不能继续发起付款，请返回订单查看当前结果。</p>
        <button class="d-btn" type="button" @click="$emit('leave')">返回记录</button>
      </section>

      <template v-else-if="screenState === 'uncertain'">
        <div class="d-notice" role="alert">
          <strong>付款结果尚不确定</strong>
          <p>原支付单 {{ intent?.paymentNo || '读取中' }} 的读取没有完成。先核对这笔结果，避免重复付款。</p>
          <button class="d-btn primary" type="button" data-test="recover-payment" :disabled="busy" @click="recoverPayment">核对原支付单</button>
          <p>结果确认前暂不重新发起付款。</p>
        </div>
      </template>

      <template v-else-if="loadFailed">
        <section class="d-state" role="alert">
          <h2>暂时读不到这笔订单</h2>
          <p>订单金额与支付方式需要核对后才能继续，请返回记录重试。</p>
          <button class="d-btn primary" type="button" @click="load">重新读取</button>
        </section>
      </template>

      <template v-else>
        <div v-if="lastAttemptFailed" class="d-notice">
          <strong>{{ failedHeadline }}</strong>
          <p>订单仍待支付。可以重新获取支付单，也可以先返回记录。</p>
          <button class="d-btn" type="button" data-test="new-payment" :disabled="busy" @click="recreateIntent">重新获取支付单</button>
        </div>
        <h3>支付方式</h3>
        <label v-if="intent?.paymentAction" class="d-method">
          <input v-model="method" type="radio" name="sg-payment-method" value="external" data-test="method-external">
          <span>
            <strong>{{ externalTitle }}</strong>
            <small>{{ externalHint }}</small>
          </span>
        </label>
        <label v-if="storedOption?.available" class="d-method">
          <input v-model="method" type="radio" name="sg-payment-method" value="stored" data-test="method-stored">
          <span>
            <strong>储值支付</strong>
            <small>可支付本单全额 ¥{{ money(storedOption.amount) }}。不与外部支付混付，本金与赠送分摊由服务记录。</small>
          </span>
        </label>
        <p class="d-muted" style="margin-top:25px">支付单 {{ intent?.paymentNo || '—' }}<template v-if="deadlineLabel"> · 截止 {{ deadlineLabel }}</template></p>
        <p v-if="actionError" class="d-error" role="alert">{{ actionError }}</p>
      </template>

      <router-link class="d-link" to="/me/feedback">付款遇到问题？反馈给我们 →</router-link>
    </section>
    <aside class="d-summary">
      <h3>核对付款金额</h3>
      <template v-if="order">
        <dl>
          <dt>原价</dt><dd>¥{{ money(order.originalAmount ?? order.totalAmount) }}</dd>
          <dt>已应用优惠</dt><dd>− ¥{{ money(discountOf(order)) }}</dd>
          <dt>实付</dt><dd>¥{{ money(order.paidAmount ?? order.totalAmount) }}</dd>
        </dl>
        <div class="d-total">
          <span>订单金额</span>
          <strong>¥{{ money(order.totalAmount) }}</strong>
        </div>
        <button
          v-if="screenState === 'ready'"
          class="d-btn primary"
          type="button"
          data-test="confirm-pay"
          :disabled="busy"
          @click="confirmPay"
        >确认{{ method === 'stored' ? '储值' : '' }}付款 ¥{{ money(order.totalAmount) }}</button>
        <p class="d-muted">订单金额已经包含预约时应用的优惠，收银处不再次计算或叠加优惠。</p>
      </template>
      <p v-else-if="!loadFailed" class="d-muted">正在读取订单金额…</p>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { orderApi } from '@/api/order'
import { reservationApi } from '@/api/reservation'
import { storedValueApi, type StoredValueOrderPaymentOption } from '@/api/storedvalue'
import type { Order, PaymentAction, PaymentIntentResponse, Reservation } from '@/api/types'
import { orderStatusLabel, money, timeRange, whenLabel } from './transactionsShared'
import { ensureSpaceNames, spaceNameOf } from './spaceNameLookup'

const props = defineProps<{
  /** 页内深链以预约定位；独立路由以订单定位。 */
  reservationId?: number | null
  orderId?: number | null
  embedded?: boolean
}>()

const emit = defineEmits<{
  (e: 'settled'): void
  (e: 'leave'): void
  (e: 'leave-credential'): void
}>()

const router = useRouter()
const isolated = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

function goCredential(): void {
  if (props.embedded) {
    emit('leave-credential')
    return
  }
  const rid = reservation.value?.id
  if (rid != null) router.push(`/me/reservations#credential-${rid}`)
  else router.push('/me/reservations')
}

const reservation = ref<Reservation | null>(null)
const order = ref<Order | null>(null)
const intent = ref<PaymentIntentResponse | null>(null)
const storedOption = ref<StoredValueOrderPaymentOption | null>(null)
const method = ref<'external' | 'stored'>('external')
const busy = ref(false)
const loadFailed = ref(false)
/** 结果未核实：确认动作已发出但未读到确定响应时置位，只影响本次收银，不新增业务状态。 */
const resultUncertain = ref(false)
const lastAttemptFailed = ref(false)
const lastPaymentStatus = ref('')
const actionError = ref('')

const readableError = (error: unknown, fallback: string) => {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (responseMessage) return responseMessage
  return error instanceof Error && error.message ? error.message : fallback
}

const externalTitle = computed(() => {
  if (intent.value?.providerEnv === 'SANDBOX') return '支付宝（沙箱）'
  if (intent.value?.providerEnv === 'DEV') return 'DEV 模拟支付'
  return intent.value?.providerName || '外部支付'
})

const externalHint = computed(() => {
  if (intent.value?.paymentAction?.type === 'SUBMIT_FORM') {
    return '跳转支付渠道完成付款；返回拾光后需核对原支付单，返回本身不作为成功凭据。'
  }
  return '在当前页完成模拟支付；结果以原支付单核对为准。'
})

const failedHeadline = computed(() => {
  if (lastPaymentStatus.value === 'CANCELED') return '本次付款已取消'
  if (lastPaymentStatus.value === 'TIMEOUT') return '本次付款已超时'
  return '本次付款未完成'
})

const screenState = computed<'loading' | 'paid' | 'closed' | 'uncertain' | 'ready'>(() => {
  if (resultUncertain.value) return 'uncertain'
  const status = order.value?.status
  if (!status) return 'loading'
  if (status === 'PAID') return 'paid'
  if (status !== 'PENDING_PAYMENT' && status !== 'PAYING') return 'closed'
  return 'ready'
})

const venueLabel = computed(() => reservation.value?.venueName || order.value?.venueName || '场馆信息读取中')
const spaceLabel = computed(() => {
  const name = spaceNameOf(reservation.value?.spaceId)
  return name || (reservation.value ? `场地 #${reservation.value.spaceId}` : '')
})
const deadlineLabel = computed(() => {
  const raw = intent.value?.expireTime || order.value?.expireTime
  if (!raw) return ''
  const parsed = new Date(raw)
  if (Number.isNaN(parsed.getTime())) return ''
  return `${parsed.getMonth() + 1} 月 ${parsed.getDate()} 日 ${String(parsed.getHours()).padStart(2, '0')}:${String(parsed.getMinutes()).padStart(2, '0')}`
})

function discountOf(value: Order): number {
  return Number(value.memberDiscountAmount ?? 0) + Number(value.couponDiscountAmount ?? 0) + Number(value.activityDiscountAmount ?? 0)
}

async function load(): Promise<void> {
  busy.value = true
  loadFailed.value = false
  actionError.value = ''
  try {
    if (props.reservationId != null) {
      reservation.value = await reservationApi.detail(props.reservationId)
    }
    const targetOrderId = props.orderId ?? reservation.value?.orderId ?? null
    if (targetOrderId == null) {
      loadFailed.value = true
      return
    }
    if (!reservation.value) {
      const page = await reservationApi.my({ pageNum: 1, pageSize: 100 }, isolated)
      reservation.value = page.records.find(item => item.orderId === targetOrderId) ?? null
    }
    const orderPage = await orderApi.my({ pageNum: 1, pageSize: 100 }, isolated)
    order.value = orderPage.records.find(item => item.id === targetOrderId) ?? null
    if (reservation.value?.venueId != null) {
      await ensureSpaceNames([reservation.value.venueId])
    }
    if (order.value && ['PENDING_PAYMENT', 'PAYING'].includes(order.value.status)) {
      intent.value = await orderApi.createPaymentIntent(order.value.id, isolated)
      lastPaymentStatus.value = intent.value.status || ''
      lastAttemptFailed.value = ['FAILED', 'CANCELED', 'TIMEOUT'].includes(lastPaymentStatus.value)
      await loadStoredOption(order.value.id)
      if (!intent.value.paymentAction && storedOption.value?.available) method.value = 'stored'
    }
  } catch {
    loadFailed.value = true
  } finally {
    busy.value = false
  }
}

async function loadStoredOption(targetOrderId: number): Promise<void> {
  try {
    storedOption.value = await storedValueApi.paymentOption(targetOrderId)
  } catch {
    storedOption.value = null
  }
}

function recoverPayment(): void {
  void doRecover()
}

async function doRecover(): Promise<void> {
  if (!intent.value || busy.value) return
  busy.value = true
  actionError.value = ''
  try {
    intent.value = await orderApi.paymentIntent(intent.value.paymentNo, isolated)
    lastPaymentStatus.value = intent.value.status || ''
    if (intent.value.status === 'SUCCEEDED') {
      resultUncertain.value = false
      await refreshAfterSettle()
    } else if (['FAILED', 'CANCELED', 'TIMEOUT', 'CLOSED'].includes(intent.value.status || '')) {
      resultUncertain.value = false
      lastAttemptFailed.value = true
      await refreshOrder()
    }
    // PENDING/CREATED：结果仍未核实，保持不确定态，不重新发起付款。
  } catch (error) {
    actionError.value = readableError(error, '暂时读不到原支付单结果，请稍后重试。')
  } finally {
    busy.value = false
  }
}

function recreateIntent(): void {
  void doRecreate()
}

async function doRecreate(): Promise<void> {
  if (!order.value || busy.value) return
  busy.value = true
  actionError.value = ''
  try {
    intent.value = await orderApi.createPaymentIntent(order.value.id, isolated)
    lastPaymentStatus.value = intent.value.status || ''
    lastAttemptFailed.value = ['FAILED', 'CANCELED', 'TIMEOUT'].includes(lastPaymentStatus.value)
    resultUncertain.value = false
  } catch (error) {
    actionError.value = readableError(error, '暂时无法获取新的支付单，请稍后重试。')
  } finally {
    busy.value = false
  }
}

async function confirmPay(): Promise<void> {
  if (!order.value || !intent.value || busy.value) return
  busy.value = true
  actionError.value = ''
  try {
    if (method.value === 'stored') {
      const productId = storedOption.value?.product?.id
      if (productId == null) throw new Error('储值支付暂不可用')
      const response = await storedValueApi.payOrder(order.value.id, {
        productId,
        idempotencyKey: `sg-order-${order.value.id}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
      }, isolated)
      if (['SUCCEEDED', 'SUCCESS', 'PAID'].includes(response.status)) {
        await refreshAfterSettle()
      } else {
        // 响应到达但状态未成功：按结果未知处理，先核对原支付单。
        resultUncertain.value = true
      }
    } else {
      const action: PaymentAction | null | undefined = intent.value.paymentAction
      if (action?.type === 'SUBMIT_FORM' && action.url) {
        submitExternalForm(action.url, (action.fields ?? {}) as Record<string, string>)
        return
      }
      if (action?.type === 'DEV_SIMULATION') {
        const result = await orderApi.simulateDevPayment(intent.value.paymentNo, { scenario: 'SUCCESS' }, isolated)
        lastPaymentStatus.value = result.status || ''
        if (['SUCCEEDED', 'SUCCESS'].includes(result.status) || result.orderStatus === 'PAID') {
          await refreshAfterSettle()
        } else {
          resultUncertain.value = true
        }
      } else {
        actionError.value = '当前支付渠道暂不可用，请稍后重试。'
      }
    }
  } catch (error) {
    // 确认动作发出但响应丢失：结果未知，先核对原支付单，不自动重试付款。
    resultUncertain.value = true
    actionError.value = readableError(error, '付款结果尚不确定，请核对原支付单。')
  } finally {
    busy.value = false
  }
}

function submitExternalForm(url: string, fields: Record<string, string>): void {
  const form = document.createElement('form')
  form.method = 'POST'
  form.action = url
  form.style.display = 'none'
  for (const [name, value] of Object.entries(fields)) {
    const input = document.createElement('input')
    input.type = 'hidden'
    input.name = name
    input.value = value
    form.appendChild(input)
  }
  document.body.appendChild(form)
  try {
    HTMLFormElement.prototype.submit.call(form)
  } finally {
    form.remove()
  }
}

async function refreshOrder(): Promise<void> {
  if (!order.value) return
  const page = await orderApi.my({ pageNum: 1, pageSize: 100 }, isolated)
  order.value = page.records.find(item => item.id === order.value!.id) ?? order.value
}

async function refreshAfterSettle(): Promise<void> {
  await refreshOrder()
  emit('settled')
}

onMounted(load)
</script>
