<template>
  <div class="sgv-booking">
    <div v-if="loading" class="shell booking-loading" role="status" aria-label="预约结果加载中">
      <el-skeleton animated :rows="6" />
    </div>

    <div v-else-if="loadError" class="shell">
      <div class="state-message" role="alert">
        <h3>{{ loadError }}</h3>
        <p>{{ loadErrorHint }}</p>
        <div class="loading-actions">
          <button class="text-button" type="button" data-test="result-retry" @click="load">重新加载 <svg class="icon" aria-hidden="true"><use href="#sgv-reload" /></svg></button>
          <router-link class="text-button" to="/me/reservations">查看我的预约 <svg class="icon" aria-hidden="true"><use href="#sgv-arrow" /></svg></router-link>
        </div>
      </div>
    </div>

    <template v-else-if="reservation">
      <div class="shell breadcrumb">
        <div class="breadcrumb-left">
          <router-link class="back-link" to="/me/time"><svg class="icon" aria-hidden="true"><use href="#sgv-back" /></svg>查看我的时间</router-link>
        </div>
      </div>

      <div class="shell">
        <section class="result-page">
          <div class="result-lead">
            <div>
              <span class="result-status" :class="{ 'is-negative': isNegative }" data-test="result-status">
                <svg class="icon" aria-hidden="true"><use :href="isNegative ? '#sgv-info' : '#sgv-check'" /></svg>{{ statusChip }}
              </span>
              <h1 tabindex="-1" data-test="result-headline">{{ headline }}</h1>
              <p>{{ lead }}</p>
            </div>
            <div class="result-art feature-art" aria-hidden="true"><ShiguangArt :scene="scene" /></div>
          </div>

          <section class="reservation-sheet" aria-label="本次预约">
            <div class="sheet-main">
              <div>
                <p class="eyebrow">这一次，去这里</p>
                <h2>{{ venueLabel }}</h2>
                <p>{{ spaceLabel }} · {{ resourceText }}</p>
                <p class="sheet-serial">预约编号 {{ reservation.id }}</p>
              </div>
              <div class="sheet-when">
                <p>{{ dateLabel }}</p>
                <strong>{{ timeLabel }}</strong>
                <p>{{ durationText }}，留给喜欢。</p>
              </div>
            </div>
            <div class="sheet-footer">
              <span>{{ footerStatus }}</span>
              <span>{{ amountLabel }} <strong>{{ amountText }}</strong></span>
            </div>
          </section>

          <p v-if="reservation.rejectReason" class="inline-error" role="alert">
            <strong>场馆给出的原因</strong>
            <span>{{ reservation.rejectReason }}</span>
          </p>
          <p v-if="priceNote" class="inline-error" role="alert" data-test="result-price-note">
            <strong>{{ priceNote }}</strong>
            <span>实付以本条预约记录 {{ amountText }} 为准。</span>
          </p>
          <p v-if="reservation.status === 'PENDING_PAYMENT' && reservation.payExpireTime" class="help-line">
            <svg class="icon" aria-hidden="true"><use href="#sgv-clock" /></svg>
            <span>请在 {{ expireText }} 前完成支付，超时后这次预约会自动释放。</span>
          </p>

          <div class="status-timeline" aria-label="后续预约步骤">
            <div v-for="(step, index) in timeline" :key="step.title" class="timeline-step" :class="{ current: index === activeStep }">
              <h3>{{ step.title }}</h3>
              <p>{{ step.text }}</p>
            </div>
          </div>

          <div class="result-actions">
            <router-link class="primary-button" to="/me/time" data-test="result-to-time"><span>查看我的时间</span><svg class="icon" aria-hidden="true"><use href="#sgv-arrow" /></svg></router-link>
            <router-link v-if="payPath" class="secondary-button" :to="payPath" data-test="result-to-pay">去支付</router-link>
            <router-link class="secondary-button" :to="venuePath" data-test="result-back-venue">返回场馆</router-link>
            <router-link class="text-button" to="/venues">再看看其他地方 <svg class="icon" aria-hidden="true"><use href="#sgv-diagonal" /></svg></router-link>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { reservationApi } from '@/api/reservation'
import { venueApi } from '@/api/venue'
import type { Reservation } from '@/api/types'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import { useReservationStore } from '@/stores/reservation'
import { mapVenueScene } from '@/utils/shiguangScene'
import { fetchSpaceName } from '@/utils/entityNames'
import { formatMonthDayTime } from '@/utils/datetime'
import { formatShiguangAmount } from '@/utils/money'

const STATUS_LABELS: Record<string, string> = {
  PENDING_REVIEW: '待审核',
  APPROVED: '审核通过',
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  COMPLETED: '已完成',
  REJECTED: '未通过',
  CANCELED: '已取消',
  EXPIRED: '已过期',
  REFUNDING: '退款中',
  REFUNDED: '已退款'
}
const NEGATIVE_STATUSES = ['REJECTED', 'CANCELED', 'EXPIRED']
const MODE_TEXT: Record<string, string> = {
  EXCLUSIVE: '整空间独立使用',
  CAPACITY: '按人数共享',
  ASSIGNED_SEAT: '指定座位'
}

const route = useRoute()
const reservationStore = useReservationStore()
const reservation = ref<Reservation | null>(null)
const loading = ref(true)
const loadError = ref('')
const loadErrorHint = ref('')
const spaceName = ref('')
const spaceType = ref('')

const statusText = computed(() => (reservation.value ? STATUS_LABELS[reservation.value.status] || reservation.value.status : ''))
const isNegative = computed(() => NEGATIVE_STATUSES.includes(reservation.value?.status || ''))
const statusChip = computed(() => {
  const status = reservation.value?.status
  if (!status) return ''
  if (isNegative.value) return `预约${statusText.value}`
  if (status === 'PENDING_REVIEW') return '预约申请已提交 · 待审核'
  if (status === 'APPROVED' || status === 'PENDING_PAYMENT') return '场馆已确认 · 待支付'
  if (status === 'PAID') return '已支付 · 等待到场'
  if (status === 'COMPLETED') return '这段安排已完成'
  if (status === 'REFUNDING') return '退款处理中'
  if (status === 'REFUNDED') return '款项已退回'
  return statusText.value
})
const headline = computed(() => {
  switch (reservation.value?.status) {
    case 'APPROVED':
    case 'PENDING_PAYMENT': return '场馆已确认，\n只差一次支付。'
    case 'PAID': return '时间已定，\n就等那一天。'
    case 'COMPLETED': return '这段时光已经用完，\n希望刚刚好。'
    case 'REJECTED': return '场馆这次没能接住\n你的这段时间。'
    case 'CANCELED': return '这段时间已经还给\n空白的日历。'
    case 'EXPIRED': return '这次支付超时了，\n时间被释放出来。'
    case 'PENDING_REVIEW': return '喜欢的事，\n已经在计划里了。'
    default: return '这段时间，\n已经记下。'
  }
})
const lead = computed(() => {
  switch (reservation.value?.status) {
    case 'APPROVED':
    case 'PENDING_PAYMENT': return '场馆已通过审核。完成支付后，你会在站内收到到场凭证。'
    case 'PAID': return '支付已完成。按预约时间到场即可，如需取消或改期，可在「我的预约」里操作。'
    case 'COMPLETED': return '这段安排已经完成。如果愿意，可以在「我的预约」里留下你的评价。'
    case 'REJECTED': return '场馆给出了未通过的原因。你可以换一段时间或场地，再次提交预约申请。'
    case 'CANCELED': return '这次预约已取消。想再安排的话，随时回到场馆挑一段时间。'
    case 'EXPIRED': return '订单支付超时后预约自动失效，库存已释放。你可以重新选择一段时间。'
    case 'PENDING_REVIEW': return '现在等待场馆审核。确认后，你会在站内收到消息，再完成支付，就可以安心出发。'
    default: return '这条预约的当前状态如下，后续操作可在「我的预约」里继续。'
  }
})
const footerStatus = computed(() => {
  switch (reservation.value?.status) {
    case 'APPROVED':
    case 'PENDING_PAYMENT': return '待支付 · 尚未付款'
    case 'PAID': return '已支付 · 等待到场'
    case 'COMPLETED': return '已完成'
    case 'REJECTED': return '未通过 · 未付款'
    case 'CANCELED': return '已取消'
    case 'EXPIRED': return '已过期 · 未付款'
    case 'REFUNDING': return '退款中'
    case 'REFUNDED': return '已退款'
    case 'PENDING_REVIEW': return '待审核 · 尚未付款'
    default: return statusText.value
  }
})
/** paid_amount 建表为 NOT NULL DEFAULT 0.00：只有真大于 0 才算已发生支付，否则回到应付口径。 */
const paidAmountValue = computed(() => Number(reservation.value?.paidAmount ?? 0))
const hasPaid = computed(() => paidAmountValue.value > 0)
const amountLabel = computed(() => (hasPaid.value ? '实付' : '预计应付'))
/** 实付口径解释：只在确认页→结果页的同一次提交里可比；深链进入没有预览快照就不臆造差异。 */
const priceNote = computed(() => {
  const value = reservation.value
  const preview = reservationStore.previewPricing
  if (!value || !preview) return ''
  const previewActivity = Number(preview.activityDiscountAmount || 0)
  const finalActivity = Number(value.activityDiscountAmount || 0)
  if (previewActivity > 0 && finalActivity === 0) {
    return '活动优惠在提交时已不可用（活动预算已耗尽）。'
  }
  const final = Number(value.payableAmount ?? value.amount ?? 0)
  const previewPayable = Number(preview.payableAmount || 0)
  if (previewPayable > 0 && final > previewPayable + 0.005) {
    return '实付金额高于确认页的服务端报价。'
  }
  return ''
})
const amountText = computed(() => {
  const value = reservation.value
  if (!value) return '—'
  const amount = hasPaid.value
    ? paidAmountValue.value
    : Number(value.payableAmount ?? value.amount ?? 0)
  return `¥${formatShiguangAmount(amount)}`
})

const venueLabel = computed(() => reservation.value?.venueName || reservationStore.venueName || '场馆')
const spaceLabel = computed(() => spaceName.value || reservationStore.spaceName || '场地')
const venuePath = computed(() => (reservation.value?.venueId ? `/venues/${reservation.value.venueId}` : '/venues'))
const payPath = computed(() => (
  reservation.value?.status === 'PENDING_PAYMENT' && reservation.value.orderId
    ? `/me/orders/${reservation.value.orderId}/pay`
    : ''
))
const allocation = computed(() => {
  const raw = reservation.value?.allocationSnapshotJson
  if (!raw) return null
  try {
    return JSON.parse(raw) as { bookingMode?: string; participantCount?: number; seats?: Array<{ seatCode?: string; seatLabel?: string }> }
  } catch {
    return null
  }
})
const resourceText = computed(() => {
  const mode = reservation.value?.bookingModeSnapshot || allocation.value?.bookingMode || 'EXCLUSIVE'
  if (mode === 'ASSIGNED_SEAT') {
    const labels = (allocation.value?.seats || []).map((seat) => seat.seatLabel || seat.seatCode).filter(Boolean)
    return labels.length ? `座位 ${labels.join('、')}` : '指定座位'
  }
  if (mode === 'CAPACITY') return `${reservation.value?.participantCount || allocation.value?.participantCount || 1} 人同行`
  return MODE_TEXT[mode] || '整空间独立使用'
})
const dateLabel = computed(() => {
  const value = reservation.value?.startTime
  if (!value) return '时间待定'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value.slice(0, 10)
  return `${parsed.getMonth() + 1} 月 ${parsed.getDate()} 日 · ${['周日', '周一', '周二', '周三', '周四', '周五', '周六'][parsed.getDay()]}`
})
const clockText = (value?: string | null) => (typeof value === 'string' && value.length >= 16 ? value.slice(11, 16) : '')
const timeLabel = computed(() => {
  const value = reservation.value
  const start = clockText(value?.startTime)
  const end = clockText(value?.endTime)
  if (!start || !end) return String(value?.startTime || '').replace('T', ' ') || '时间待定'
  return `${start}–${end}`
})
const durationText = computed(() => {
  const value = reservation.value
  if (!value) return ''
  const hours = (Date.parse(value.endTime) - Date.parse(value.startTime)) / 3_600_000
  return Number.isFinite(hours) && hours > 0 ? `${Number(hours.toFixed(1))} 小时` : '这段时间'
})
const expireText = computed(() => formatMonthDayTime(reservation.value?.payExpireTime))
const scene = computed(() => mapVenueScene(spaceType.value ? [spaceType.value] : []))
const activeStep = computed(() => {
  switch (reservation.value?.status) {
    case 'PENDING_PAYMENT': return 1
    case 'PAID':
    case 'COMPLETED': return 2
    default: return 0
  }
})
const timeline = computed(() => [
  { title: '等待场馆确认', text: '你的申请已送达，留意站内消息。' },
  { title: '审核通过后支付', text: '通过后查看订单，按提示付款。' },
  { title: '带着好心情到场', text: '支付后查看凭证，按预约时间出发。' }
])

const load = async () => {
  const id = Number(route.params.id)
  loading.value = true
  loadError.value = ''
  loadErrorHint.value = ''
  if (!Number.isFinite(id) || id <= 0) {
    loadError.value = '这条预约记录无法识别。'
    loadErrorHint.value = '链接里的预约编号不完整，请从「我的预约」重新进入。'
    loading.value = false
    return
  }
  try {
    const data = await reservationApi.detail(id)
    reservation.value = data
    if (data.venueId && data.spaceId) {
      try {
        const spaces = await venueApi.spaces(data.venueId)
        const space = spaces.find((item) => item.id === data.spaceId)
        spaceName.value = space?.name || ''
        spaceType.value = space?.type || ''
      } catch {
        spaceName.value = (await fetchSpaceName(data.venueId, data.spaceId)) || ''
      }
    }
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    reservation.value = null
    if (status === 404) {
      loadError.value = '找不到这条预约。'
      loadErrorHint.value = '它可能已被删除，或链接不属于当前账号。'
    } else if (status === 403) {
      loadError.value = '这条预约不属于当前账号。'
      loadErrorHint.value = '请登录后查看你自己的预约记录。'
    } else {
      loadError.value = '预约结果暂时无法加载。'
      loadErrorHint.value = '网络或服务异常时不会显示为「没有预约」，请稍后重试。'
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
