<template>
  <div class="sgv-booking">
    <div v-if="!hasSelection" class="shell">
      <div class="state-message">
        <h3>还没有选择时间。</h3>
        <p>回到场馆详情，挑一处喜欢的空间和一段合适的时间。</p>
        <router-link class="text-button" to="/venues" data-test="find-venue">找一处喜欢的地方 <svg class="icon" aria-hidden="true"><use href="#sgv-arrow" /></svg></router-link>
      </div>
    </div>

    <template v-else>
      <div class="shell breadcrumb">
        <div class="breadcrumb-left">
          <router-link class="back-link" :to="venuePath" data-test="back-to-detail"><svg class="icon" aria-hidden="true"><use href="#sgv-back" /></svg>返回选择时间</router-link>
        </div>
      </div>

      <div class="shell flow-heading">
        <div>
          <p class="eyebrow">给喜欢的事，留个位置。</p>
          <h1 tabindex="-1">把这段时间，确认下来。</h1>
          <p>核对安排与费用，留下联系方式。提交后，我们等待场馆确认。</p>
        </div>
        <nav class="flow-steps" aria-label="预约进度">
          <span class="done"><svg class="icon" aria-hidden="true"><use href="#sgv-check" /></svg>选择时间</span>
          <i class="line" aria-hidden="true"></i>
          <span class="active">确认预约</span>
          <i class="line" aria-hidden="true"></i>
          <span>等待审核</span>
        </nav>
      </div>

      <div class="shell booking-layout confirm-layout">
        <div class="booking-content">
          <section class="booking-recap">
            <div class="recap-art" aria-hidden="true"><ShiguangArt :scene="scene" /></div>
            <div>
              <h2>{{ venueLabel }}</h2>
              <p>{{ spaceLabel }} · {{ resourceText }}</p>
              <p>{{ dateLabel }} <span class="recap-time">{{ timeLabel }}</span></p>
            </div>
            <router-link class="text-button" :to="venuePath">修改 <svg class="icon" aria-hidden="true"><use href="#sgv-back" /></svg></router-link>
          </section>

          <section class="confirm-section">
            <h2>这次预约，怎么联系你</h2>
            <p class="section-note">仅用于本次预约联系，会随预约一并交给场馆。</p>
            <div class="form-grid">
              <label class="form-field" for="contact-name">
                <span>联系人</span>
                <input id="contact-name" v-model.trim="form.contactName" type="text" maxlength="64" autocomplete="name" :aria-invalid="fieldErrors.contactName ? 'true' : 'false'" aria-describedby="contact-error" data-test="contact-name" placeholder="填写称呼" @blur="validateContact">
              </label>
              <label class="form-field" for="contact-phone">
                <span>联系电话</span>
                <input id="contact-phone" v-model.trim="form.contactPhone" type="tel" inputmode="tel" maxlength="11" autocomplete="tel" :aria-invalid="fieldErrors.contactPhone ? 'true' : 'false'" aria-describedby="contact-error" data-test="contact-phone" placeholder="11 位手机号" @blur="validateContact">
              </label>
              <label class="form-field full" for="contact-remark">
                <span>给场馆的话 <em>选填</em></span>
                <textarea id="contact-remark" v-model="form.remark" rows="2" maxlength="200" data-test="contact-remark" placeholder="有什么需要提前告诉场馆的，可以写在这里。"></textarea>
              </label>
            </div>
            <p v-if="contactError" id="contact-error" class="form-error" role="alert">{{ contactError }}</p>
          </section>

          <section v-if="canUseCoupons" class="confirm-section">
            <div class="section-heading">
              <h2>一点小小的心意</h2>
              <span class="muted">优惠券</span>
            </div>
            <p v-if="couponLoading" class="section-note">正在加载你的优惠券…</p>
            <p v-else-if="couponError" class="section-note">{{ couponError }}</p>
            <p v-else-if="!availableCoupons.length" class="section-note">当前没有可用于本次预约的优惠券。</p>
            <p v-else class="section-note">有 {{ availableCoupons.length }} 张券可选，是否抵扣以服务端计价结果为准。</p>
            <button
              v-for="coupon in availableCoupons"
              :key="coupon.id"
              type="button"
              class="coupon-option"
              :class="{ 'is-selected': selectedCouponId === coupon.id }"
              :aria-pressed="selectedCouponId === coupon.id"
              :disabled="resourceConflict"
              :data-test="`coupon-${coupon.id}`"
              @click="toggleCoupon(coupon.id)"
            >
              <span class="coupon-value">¥{{ money(coupon.discountValue) }}</span>
              <span><strong>{{ coupon.templateName || coupon.templateCode || `优惠券 ${coupon.id}` }}</strong><small>满 ¥{{ money(coupon.thresholdAmount) }} 可用{{ coupon.validEndTime ? ` · ${formatTime(coupon.validEndTime)} 前有效` : '' }}</small></span>
              <span class="coupon-check"><svg v-if="selectedCouponId === coupon.id" class="icon" aria-hidden="true"><use href="#sgv-check" /></svg></span>
            </button>
            <p v-if="pricingPreview?.couponUnavailableReason" class="section-note">{{ pricingPreview.couponUnavailableReason }}</p>
          </section>

          <section class="confirm-section">
            <h2>提交之前</h2>
            <div class="policy-copy">
              <div><h3>先确认，再出发</h3><p>提交表示预约申请已送达。审核通过后会有站内消息，请再按订单提示完成支付。</p></div>
              <div><h3>计划变化，也有回路</h3><p>在“我的时间”里查看最新状态。取消、改期与退款按预约状态提供对应操作。</p></div>
            </div>
          </section>

          <p v-if="adjustmentNotice" class="inline-error" role="status" data-test="adjustment-notice">
            <strong>{{ adjustmentNotice }}</strong>
          </p>
          <div v-if="resourceConflict" class="inline-error" role="alert" data-test="conflict-band">
            <strong>{{ conflictHeadline }}</strong>
            <p>预约尚未提交。你的联系人信息已保留，请返回选择其他时段。</p>
            <router-link class="text-button" :to="venuePath" data-test="resolve-conflict">重新选择时间 <svg class="icon" aria-hidden="true"><use href="#sgv-arrow" /></svg></router-link>
          </div>
          <div v-else-if="availabilityUnknown" class="inline-error" role="alert" data-test="availability-unknown">
            <strong>暂时无法确认这段时间还能不能预约。</strong>
            <p>预约尚未提交。请先重新确认可售状态，不要反复提交。</p>
            <button class="text-button" type="button" data-test="recheck-availability" :disabled="recheckingAvailability" @click="recheckAvailability">重新确认 <svg class="icon" aria-hidden="true"><use href="#sgv-reload" /></svg></button>
          </div>
          <div v-else-if="submitUnknown" class="inline-error" role="status" data-test="submit-unknown">
            <strong>结果待核对。</strong>
            <p>{{ submitUnknown }}</p>
            <button class="text-button" type="button" data-test="reconcile-submit" :disabled="reconciling" @click="reconcileSubmission()">核对原提交结果 <svg class="icon" aria-hidden="true"><use href="#sgv-reload" /></svg></button>
            <button class="text-button" type="button" data-test="retry-submit" :disabled="submitting || reconciling" @click="submit">重新提交 <svg class="icon" aria-hidden="true"><use href="#sgv-reload" /></svg></button>
          </div>
          <div v-else-if="submitFailure" class="inline-error" role="alert" data-test="submit-failure">
            <strong>这次提交没有成功。</strong>
            <p>{{ submitFailure }}</p>
            <button class="text-button" type="button" data-test="retry-submit" :disabled="submitting" @click="submit">重新提交 <svg class="icon" aria-hidden="true"><use href="#sgv-reload" /></svg></button>
          </div>
          <div v-else-if="previewError" class="inline-error" role="alert">
            <strong>{{ previewError }}</strong>
            <p>费用未能从服务端取回，暂时不能提交。</p>
            <button class="text-button" type="button" data-test="reload-preview" @click="loadPricingPreview">重新计算 <svg class="icon" aria-hidden="true"><use href="#sgv-reload" /></svg></button>
          </div>
        </div>

        <aside class="booking-summary" aria-label="预约费用确认">
          <BookingSummaryPanel
            confirm
            :scene="scene"
            :date-label="dateLabel"
            :time-label="timeLabel"
            :detail-label="summaryDetail"
            :venue-name="venueLabel"
            :space-name="spaceLabel"
            :unit-price="unitPrice"
            :quantity-line="quantityLine"
            :quantity-amount="pricingPreview?.originalAmount"
            :pending-label="pendingLabel"
            :payable="pricingPreview?.payableAmount"
            :quote-error="previewError"
            :loading="previewLoading"
            :submit-label="submitLabel"
            :submit-disabled="!canSubmit"
            :hint="submitHint"
            trust-text="审核通过后再付款"
            @submit="submit"
          />
          <div v-if="discountLines.length" class="summary-lines discount-lines">
            <div v-for="line in discountLines" :key="line.label" class="cost-row discount"><span>{{ line.label }}</span><span>− ¥{{ money(line.amount) }}</span></div>
          </div>
        </aside>
      </div>

      <div class="mobile-booking">
        <div>
          <strong>{{ pricingPreview && !previewError ? `¥${money(pricingPreview.payableAmount)}` : '待核对' }}</strong>
          <p>{{ submitting ? '正在提交…' : summaryDetail }}</p>
        </div>
        <button class="primary-button" type="button" :disabled="!canSubmit" data-test="mobile-submit" @click="submit">
          <span>{{ submitLabel }}</span><svg class="icon" aria-hidden="true"><use href="#sgv-arrow" /></svg>
        </button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { reservationApi } from '@/api/reservation'
import { couponApi } from '@/api/promotion'
import type {
  BookingMode,
  PricingPreviewResponse,
  ResourceAvailabilityResponse,
  UserCouponResponse
} from '@/api/types'
import BookingSummaryPanel from '@/components/shiguang/BookingSummaryPanel.vue'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import { useReservationStore } from '@/stores/reservation'
import { useMenuStore } from '@/stores/menu'
import { useFunnelStore } from '@/stores/funnel'
import { mapVenueScene } from '@/utils/shiguangScene'
import { formatMonthDayTime } from '@/utils/datetime'
import { formatShiguangAmount } from '@/utils/money'

const MOBILE_PHONE_PATTERN = /^1[3-9]\d{9}$/
const CONFLICT_HEADLINES: Record<string, string> = {
  EXCLUSIVE_OVERLAP: '刚刚，这段时间被先一步选中了。',
  CAPACITY_EXCEEDED: '这段时间的余量已经不够本次人数了。',
  SEAT_ALREADY_ALLOCATED: '你选的座位刚刚被占用了。',
  RESOURCE_RULE_UNAVAILABLE: '这个场地现在不能按当前方式预约了。'
}

const route = useRoute()
const router = useRouter()
const reservationStore = useReservationStore()
const menu = useMenuStore()
const funnel = useFunnelStore()

const slots = computed(() => reservationStore.selectedSlots)
const hasSelection = computed(() => slots.value.length > 0)
const form = reactive({ contactName: '', contactPhone: '', remark: '' })
const fieldErrors = reactive({ contactName: false, contactPhone: false })
const contactTouched = ref(false)
const participantCount = ref(1)
const selectedSeatIds = ref<number[]>([])

const resource = ref<ResourceAvailabilityResponse | null>(null)
const resourceLoading = ref(false)
const resourceError = ref('')
const pricingPreview = ref<PricingPreviewResponse | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const coupons = ref<UserCouponResponse[]>([])
const couponLoading = ref(false)
const couponError = ref('')
const selectedCouponId = ref<number | undefined>()
const submitting = ref(false)
const submitFailure = ref('')
const submitUnknown = ref('')
const reconciling = ref(false)
const availabilityUnknown = ref(false)
const recheckingAvailability = ref(false)
const conflictCode = ref('')
const adjustmentNotice = ref('')
const submitConflict = ref(false)

const bookingMode = computed<BookingMode>(() => resource.value?.rule.bookingMode || 'EXCLUSIVE')
const scene = computed(() => mapVenueScene(reservationStore.spaceType ? [reservationStore.spaceType] : []))
const venueLabel = computed(() => reservationStore.venueName || '场馆')
const spaceLabel = computed(() => reservationStore.spaceName || '场地')
const todayIsoDate = () => {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}
/**
 * 返回选择时间必须保留原场馆、场地与非当天日期：只回场馆根路径会把用户丢回今天的空页面。
 * 时段、人数与座位由预约 store 的会话草稿承载，这里只补场馆页无法从草稿恢复的两个维度。
 */
const venuePath = computed(() => {
  const venueId = reservationStore.lastVenueId
  if (!venueId) return '/venues'
  const params = new URLSearchParams()
  const selectedDate = sortedSlots.value[0]?.startTime?.slice(0, 10)
  if (selectedDate && selectedDate !== todayIsoDate()) params.set('date', selectedDate)
  const spaceId = reservationStore.spaceId ?? sortedSlots.value[0]?.spaceId
  if (spaceId) params.set('spaceId', String(spaceId))
  const query = params.toString()
  return query ? `/venues/${String(venueId)}?${query}` : `/venues/${String(venueId)}`
})
const unitPrice = computed(() => {
  const first = slots.value[0]
  return first ? Number(first.price) : null
})

const money = formatShiguangAmount
const formatTime = (value?: string) => (value ? formatMonthDayTime(value) : '')
const sortedSlots = computed(() => [...slots.value].sort((left, right) => left.startTime.localeCompare(right.startTime)))
const startTime = computed(() => sortedSlots.value[0]?.startTime || '')
const endTime = computed(() => sortedSlots.value[sortedSlots.value.length - 1]?.endTime || '')
const dateLabel = computed(() => {
  if (!startTime.value) return '选择日期'
  const parsed = new Date(startTime.value)
  if (Number.isNaN(parsed.getTime())) return startTime.value.slice(0, 10)
  return `${parsed.getMonth() + 1} 月 ${parsed.getDate()} 日 · ${['周日', '周一', '周二', '周三', '周四', '周五', '周六'][parsed.getDay()]}`
})
const timeLabel = computed(() => (startTime.value ? `${startTime.value.slice(11, 16)}–${endTime.value.slice(11, 16)}` : '—'))
const durationHours = computed(() => {
  if (!startTime.value) return ''
  const hours = (Date.parse(endTime.value) - Date.parse(startTime.value)) / 3_600_000
  return Number.isFinite(hours) && hours > 0 ? Number(hours.toFixed(1)) : ''
})
/** 缓存最近一次成功返回的座位标签，查询失败时也不外显内部 ID。 */
const seatLabels = ref<Record<number, string>>({})
const resourceText = computed(() => {
  if (bookingMode.value === 'ASSIGNED_SEAT') {
    const labels = selectedSeatIds.value.map((id) => seatLabels.value[id] || '待确认')
    return labels.length ? `座位 ${labels.join('、')}` : '尚未选择座位'
  }
  if (bookingMode.value === 'CAPACITY') return `${participantCount.value} 人同行`
  return '整空间独立使用'
})
const summaryDetail = computed(() => `${durationHours.value || sortedSlots.value.length} 小时 · ${resourceText.value}`)
const quantityLine = computed(() => (durationHours.value ? `${durationHours.value} 小时` : ''))
const pendingLabel = computed(() => (bookingMode.value === 'ASSIGNED_SEAT' && !selectedSeatIds.value.length ? '请先选择座位' : ''))
const discountLines = computed(() => {
  const preview = pricingPreview.value
  if (!preview) return []
  return [
    { label: '会员优惠', amount: preview.memberDiscountAmount },
    { label: '活动优惠', amount: preview.activityDiscountAmount },
    { label: '优惠券抵扣', amount: preview.couponDiscountAmount }
  ].filter((line) => Number(line.amount || 0) > 0)
})

const contactError = computed(() => {
  if (!contactTouched.value && !submitFailure.value) return ''
  if (fieldErrors.contactName) return '请填写联系人称呼，不超过 64 个字符。'
  if (fieldErrors.contactPhone) return '请输入 11 位有效手机号。'
  return ''
})
const hasRequiredContact = computed(() => Boolean(form.contactName.trim()) && MOBILE_PHONE_PATTERN.test(form.contactPhone.trim()))
const resourceConflict = computed(() => Boolean(conflictCode.value))
const canSubmit = computed(() => hasSelection.value
  && hasRequiredContact.value
  && !resourceConflict.value
  && !availabilityUnknown.value
  && !submitting.value
  && !resourceLoading.value
  && Boolean(pricingPreview.value)
  && (bookingMode.value !== 'ASSIGNED_SEAT' || selectedSeatIds.value.length > 0))
const submitLabel = computed(() => {
  if (submitting.value) return '正在提交…'
  if (bookingMode.value === 'ASSIGNED_SEAT' && !selectedSeatIds.value.length) return '请先选择座位'
  return '提交预约申请'
})
const submitHint = computed(() => {
  if (submitting.value) return ''
  if (resourceConflict.value) return '这段时间已经不属于你了，请返回重新选择时间。'
  if (availabilityUnknown.value) return '可售状态暂时无法确认，请先重新确认再提交。'
  if (submitUnknown.value) return '上次提交的结果还没确认，核对后可以决定是否重新提交。'
  if (!hasRequiredContact.value) return '填好联系人和 11 位手机号后可以提交。'
  if (bookingMode.value === 'ASSIGNED_SEAT' && !selectedSeatIds.value.length) return '这个场地需要选一个位置。'
  if (resourceLoading.value) return '正在复核这段时间还能不能约…'
  if (previewError.value || !pricingPreview.value) return '费用还没从服务端核对回来，请先重新计算。'
  return ''
})
const availableCoupons = computed(() => coupons.value.filter((coupon) => coupon.status === 'CLAIMED'))
const canUseCoupons = computed(() => menu.hasPermission('coupon:me'))

const selectionPayload = computed(() => {
  if (!hasSelection.value) return null
  return {
    spaceId: sortedSlots.value[0].spaceId,
    startTime: startTime.value,
    endTime: endTime.value,
    slots: slots.value,
    ...(bookingMode.value === 'CAPACITY' ? { participantCount: participantCount.value } : {}),
    // 服务端要求指定座位模式的人数等于座位数，缺省会按 1 归一并直接 400
    ...(bookingMode.value === 'ASSIGNED_SEAT'
      ? { participantCount: Math.max(1, selectedSeatIds.value.length), seatIds: selectedSeatIds.value }
      : {})
  }
})

const conflictHeadline = computed(() => CONFLICT_HEADLINES[conflictCode.value] || '刚刚，这段时间被先一步选中了。')

const readableError = (error: unknown, fallback: string) => {
  const response = (error as { response?: { data?: { message?: string } } })?.response
  if (response?.data?.message) return response.data.message
  return error instanceof Error && error.message ? error.message : fallback
}

const readConflictCode = (error: unknown) => {
  const data = (error as { response?: { data?: { data?: { reasonCode?: string } } } })?.response?.data?.data
  return data?.reasonCode || ''
}

const validateContact = () => {
  fieldErrors.contactName = !form.contactName.trim() || form.contactName.trim().length > 64
  fieldErrors.contactPhone = !MOBILE_PHONE_PATTERN.test(form.contactPhone.trim())
  contactTouched.value = true
  return !fieldErrors.contactName && !fieldErrors.contactPhone
}

const toggleCoupon = (couponId: number) => {
  selectedCouponId.value = selectedCouponId.value === couponId ? undefined : couponId
  if (couponId && selectedCouponId.value) {
    void funnel.track({
      eventName: 'COUPON_SELECTED',
      eventStage: 'COUPON',
      spaceId: sortedSlots.value[0]?.spaceId,
      userCouponId: couponId,
      pagePath: '/reservation/confirm'
    })
  }
}

let resourceSequence = 0
const loadResourceAvailability = async () => {
  const payload = selectionPayload.value
  if (!payload) return null
  const sequence = ++resourceSequence
  resourceLoading.value = true
  resourceError.value = ''
  try {
    const response = await reservationApi.resourceAvailability(payload)
    if (sequence !== resourceSequence) return response
    resource.value = response
    for (const seat of response.rule.seats) {
      seatLabels.value = { ...seatLabels.value, [seat.id]: seat.seatLabel || seat.seatCode }
    }
    if (response.rule.bookingMode === 'ASSIGNED_SEAT') {
      const available = new Set(response.rule.seats.filter((seat) => seat.available !== false).map((seat) => seat.id))
      const retained = selectedSeatIds.value.filter((id) => available.has(id))
      if (retained.length !== selectedSeatIds.value.length) {
        selectedSeatIds.value = retained
        reservationStore.setResourceSelection({ seatIds: retained })
        adjustmentNotice.value = retained.length
          ? '部分座位刚刚被占用，已为你保留仍可用的座位。'
          : '你选的座位刚刚被占用，请返回重新选择位置。'
      }
    }
    if (response.rule.bookingMode === 'CAPACITY' && response.remainingCapacity < participantCount.value) {
      participantCount.value = Math.max(1, response.remainingCapacity)
      adjustmentNotice.value = `所选时段余量只有 ${response.remainingCapacity} 人，已按余量调整同行人数。`
    }
    if (!response.bookable) {
      conflictCode.value = response.reasonCode || 'RESOURCE_RULE_UNAVAILABLE'
    } else if (!submitConflict.value) {
      // 服务端 409 已判定冲突时，只读复检不得抹掉冲突条
      conflictCode.value = ''
    }
    return response
  } catch (error) {
    if (sequence !== resourceSequence) return null
    resource.value = null
    resourceError.value = readableError(error, '资源可售暂时无法确认，请稍后重试')
    return null
  } finally {
    if (sequence === resourceSequence) resourceLoading.value = false
  }
}

const previewPayload = () => {
  const payload = selectionPayload.value
  if (!payload) return null
  return {
    ...payload,
    contactName: form.contactName,
    contactPhone: form.contactPhone,
    remark: form.remark,
    userCouponId: selectedCouponId.value || undefined
  }
}

let previewSequence = 0
const loadPricingPreview = async () => {
  const payload = previewPayload()
  if (!payload || !hasRequiredContact.value) {
    pricingPreview.value = null
    previewError.value = ''
    return
  }
  const sequence = ++previewSequence
  previewLoading.value = true
  previewError.value = ''
  try {
    const response = await reservationApi.pricingPreview(payload)
    if (sequence !== previewSequence) return
    pricingPreview.value = response
    void funnel.track({
      eventName: 'PRICE_PREVIEW_SHOWN',
      eventStage: 'PRICE',
      venueId: reservationStore.lastVenueId,
      spaceId: payload.spaceId,
      userCouponId: selectedCouponId.value || undefined,
      payableAmount: response.payableAmount,
      pagePath: '/reservation/confirm',
      metadata: {
        pricingVersion: response.pricingVersion,
        couponDiscountAmount: response.couponDiscountAmount,
        activityDiscountAmount: response.activityDiscountAmount,
        memberDiscountAmount: response.memberDiscountAmount
      }
    })
  } catch (error) {
    if (sequence !== previewSequence) return
    const status = (error as { response?: { status?: number } })?.response?.status
    pricingPreview.value = null
    if (status === 409) {
      conflictCode.value = readConflictCode(error) || 'EXCLUSIVE_OVERLAP'
      submitConflict.value = true
      previewError.value = ''
    } else {
      previewError.value = readableError(error, '费用暂时无法从服务端取回')
    }
  } finally {
    if (sequence === previewSequence) previewLoading.value = false
  }
}

const loadCoupons = async () => {
  if (!canUseCoupons.value) return
  couponLoading.value = true
  couponError.value = ''
  try {
    const page = await couponApi.my({ pageNum: 1, pageSize: 50, status: 'CLAIMED' })
    coupons.value = page.records
    void funnel.track({
      eventName: 'COUPON_SHOWN',
      eventStage: 'COUPON',
      spaceId: sortedSlots.value[0]?.spaceId,
      pagePath: '/reservation/confirm',
      metadata: { availableCount: page.records.length }
    })
  } catch {
    coupons.value = []
    couponError.value = '优惠券暂时无法加载，不影响原价预约。'
  } finally {
    couponLoading.value = false
  }
}

/** 请求身份冲突：同键仍在处理中，或同键被换了载荷。两者都必须先核对原请求，不能改换键重投。 */
const SUBMISSION_STATE_CODES = ['SUBMISSION_IN_PROGRESS', 'SUBMISSION_KEY_REUSED']

/**
 * 结果未知的判定：没有响应（超时、断连、响应解码失败）或服务端 5xx。
 * 这类情况服务端可能已经提交成功，单凭客户端异常判成「创建失败」正是 AR-01 的成因。
 */
const isUnknownOutcome = (error: unknown) => {
  const response = (error as { response?: { status?: number } })?.response
  if (!response) return true
  const status = response.status
  return typeof status === 'number' && status >= 500
}

/**
 * 只读核对当前提交意图的结果。
 * COMPLETED 直接进入真实预约；NONE 只说明当前查询未看到结果，原事务仍可能提交；
 * PROCESSING 继续待核对。任何分支都不在这里更换请求键。
 */
const reconcileSubmission = async () => {
  const key = reservationStore.submitIntentKey
  if (!key || reconciling.value) return
  reconciling.value = true
  submitUnknown.value = ''
  submitFailure.value = ''
  try {
    const result = await reservationApi.submissionResult(key)
    const reservationId = result?.reservationId ?? result?.reservation?.id
    if (result?.status === 'COMPLETED' && reservationId) {
      reservationStore.clearSubmitIntent()
      reservationStore.clearSlot()
      await router.push(`/reservation/result/${String(reservationId)}`)
      return
    }
    if (result?.status === 'NONE') {
      submitUnknown.value = '暂未查到提交结果，原请求仍可能处理中。请保留原预约内容，核对或重试同一次提交。'
      return
    }
    submitUnknown.value = '这次提交仍在处理中，请稍等片刻再核对。'
  } catch (error) {
    submitUnknown.value = readableError(error, '暂时无法核对提交结果，请稍后重试。')
  } finally {
    reconciling.value = false
  }
}

/** 可售查询失败后由用户显式重新确认：确认成功才解除提交阻断。 */
const recheckAvailability = async () => {
  if (recheckingAvailability.value) return
  recheckingAvailability.value = true
  try {
    const latest = await loadResourceAvailability()
    if (!latest) return
    availabilityUnknown.value = false
    submitFailure.value = ''
    if (!latest.bookable) conflictCode.value = latest.reasonCode || 'RESOURCE_RULE_UNAVAILABLE'
  } finally {
    recheckingAvailability.value = false
  }
}

const submit = async () => {
  if (submitting.value) return
  if (!validateContact()) return
  if (!hasSelection.value) return
  submitting.value = true
  submitFailure.value = ''
  submitUnknown.value = ''
  submitConflict.value = false
  try {
    let payload = previewPayload()
    if (!payload) return
    // A pending intent may already own the inventory. Replay that key without a new-sale preflight.
    if (!reservationStore.submitIntentKey) {
      const latest = await loadResourceAvailability()
      if (!latest) {
        // 可售查询本身失败：既不能判定为库存冲突，也不能继续提交。
        availabilityUnknown.value = true
        return
      }
      if (!latest.bookable) {
        conflictCode.value = latest.reasonCode || 'RESOURCE_RULE_UNAVAILABLE'
        return
      }
      if (bookingMode.value === 'ASSIGNED_SEAT' && !selectedSeatIds.value.length) {
        conflictCode.value = 'SEAT_ALREADY_ALLOCATED'
        return
      }
      payload = previewPayload()
      if (!payload) return
      let finalPreview: PricingPreviewResponse
      try {
        finalPreview = await reservationApi.pricingPreview(payload)
      } catch (error) {
        if ((error as { response?: { status?: number } })?.response?.status === 409) {
          conflictCode.value = readConflictCode(error) || 'EXCLUSIVE_OVERLAP'
          submitConflict.value = true
          return
        }
        throw error
      }
      pricingPreview.value = finalPreview
      reservationStore.setPreviewPricing({
        payableAmount: finalPreview.payableAmount,
        activityDiscountAmount: finalPreview.activityDiscountAmount
      })
    }
    // 同一个提交意图复用同一个请求键：响应丢失后重试由服务端还原原结果，而不是再建一条预约。
    const idempotencyKey = reservationStore.ensureSubmitIntentKey()
    const created = await reservationApi.create({
      ...payload,
      remark: form.remark || undefined
    }, funnel.sessionKey, idempotencyKey)
    reservationStore.clearSubmitIntent()
    reservationStore.clearSlot()
    await router.push(`/reservation/result/${String(created.id)}`)
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    const reasonCode = readConflictCode(error)
    if (status === 409) {
      if (SUBMISSION_STATE_CODES.includes(reasonCode)) {
        await reconcileSubmission()
        return
      }
      conflictCode.value = reasonCode || 'EXCLUSIVE_OVERLAP'
      submitConflict.value = true
      submitFailure.value = ''
    } else if (isUnknownOutcome(error)) {
      submitUnknown.value = readableError(error, '提交结果暂时无法确认，请先核对原提交结果。')
      // Inventory may have been occupied by this very request; preserve the original selection and recovery action.
      return
    } else {
      submitFailure.value = readableError(error, '预约提交失败，请稍后重试')
    }
    await loadResourceAvailability()
  } finally {
    submitting.value = false
  }
}

watch([startTime, endTime, participantCount, selectedSeatIds], () => {
  reservationStore.setResourceSelection({
    participantCount: participantCount.value,
    seatIds: selectedSeatIds.value
  })
})
watch([slotSignature, selectedCouponId, participantCount, selectedSeatIds, bookingMode], () => {
  void loadPricingPreview()
})
// Clear a corrected inline error while typing, before blur can shift a recovery button
// between pointerdown and pointerup (particularly visible after refresh).
watch([() => form.contactName, () => form.contactPhone], () => {
  if (contactTouched.value) validateContact()
})
watch(hasRequiredContact, (ready) => {
  if (ready) void loadPricingPreview()
})

function slotSignature() {
  return slots.value.map((slot) => `${slot.spaceId}:${slot.startTime}:${slot.endTime}`).join('|')
}

onMounted(() => {
  reservationStore.restoreDraft()
  participantCount.value = reservationStore.participantCount || 1
  selectedSeatIds.value = [...reservationStore.seatIds]
  void loadResourceAvailability()
  void loadCoupons()
  // 刷新或返回后仍有未完成的提交意图时，只读核对一次，命中已提交结果就直接进入真实预约。
  reservationStore.restoreSubmitIntent()
  void reconcileSubmission()
})
</script>
