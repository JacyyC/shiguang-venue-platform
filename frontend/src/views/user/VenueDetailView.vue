<template>
  <div class="sgv-booking">
    <div v-if="loading" class="shell booking-loading" role="status" aria-label="场馆信息加载中">
      <el-skeleton animated :rows="10" />
    </div>

    <section v-else-if="loadError" class="shell" role="alert">
      <div class="state-message">
        <h3>场馆信息暂时无法加载。</h3>
        <p>{{ loadError }}</p>
        <div class="loading-actions">
          <button class="text-button" type="button" @click="$router.back()">返回上一页</button>
          <button class="primary-button loading-retry" type="button" @click="loadCore">重新加载 <svg class="icon" aria-hidden="true"><use href="#sgv-reload" /></svg></button>
        </div>
      </div>
    </section>

    <template v-else-if="venue">
      <div class="shell breadcrumb">
        <div class="breadcrumb-left">
          <router-link class="back-link" to="/venues"><svg class="icon" aria-hidden="true"><use href="#sgv-back" /></svg>全部场馆</router-link>
          <span class="sep">/</span><span class="muted">场馆详情</span>
        </div>
        <button
          v-if="canFavorite"
          class="text-button favorite"
          :class="{ 'is-saved': isFavorite }"
          type="button"
          :aria-pressed="isFavorite"
          :disabled="favoriteLoading"
          data-test="favorite-toggle"
          @click="toggleFavorite"
        >
          <svg class="icon" aria-hidden="true"><use href="#sgv-heart" /></svg>
          <span>{{ isFavorite ? '已收藏' : '收藏场馆' }}</span>
        </button>
      </div>

      <div class="hero-band">
        <div class="shell venue-hero">
          <div>
            <p class="eyebrow">{{ heroEyebrow }}</p>
            <h1 tabindex="-1" data-test="venue-title">{{ venue.name }}</h1>
            <p class="hero-description">{{ venue.description || '这家场馆还没有介绍，先到下面看看可选的场地和时间。' }}</p>
            <div class="hero-meta">
              <span><svg class="icon" aria-hidden="true"><use href="#sgv-pin" /></svg>{{ locationText }}</span>
              <span><svg class="icon" aria-hidden="true"><use href="#sgv-clock" /></svg>{{ openTimeText }}</span>
              <span v-if="venue.phone" class="muted">{{ maskVenuePhone(venue.phone) }}</span>
              <span class="open-note"><i class="dot"></i>{{ venue.status === 'ACTIVE' ? '营业中' : '暂未开放' }}</span>
            </div>
            <button type="button" class="detail-loc-card" data-test="detail-loc-card" @click="locLightboxOpen = true">
              <span class="detail-loc-sketch" aria-hidden="true">
                <svg viewBox="0 0 48 48"><path d="M6 15h36M6 31h36M15 6v36M33 6v36" opacity="0.4" /><path d="M6 41h18" opacity="0.65" /><circle cx="24" cy="23" r="5.5" class="pin" /><path d="M24 28.5V35" class="pin" /></svg>
              </span>
              <span class="detail-loc-info">
                <span class="detail-loc-addr"><svg class="icon" aria-hidden="true"><use href="#sgv-pin" /></svg>{{ venue.address }}</span>
                <span class="detail-loc-acts">
                  <button type="button" class="text-button" data-test="copy-address" @click.stop="copyAddress">复制地址</button>
                  <a class="text-button" data-test="navigate-venue" :href="mapSearchUrl" target="_blank" rel="noopener" @click.stop>导航 <svg class="icon" aria-hidden="true"><use href="#sgv-diagonal" /></svg></a>
                  <button type="button" class="text-button" @click.stop="locLightboxOpen = true">放大示意</button>
                </span>
                <span class="micro">位置示意 · 非真实地图；「导航」查看真实路线</span>
              </span>
            </button>
          </div>
          <div class="hero-art feature-art">
            <ShiguangArt :scene="scene" />
            <span class="art-caption">生活插画 · 非场馆实景</span>
          </div>
        </div>
      </div>

      <div class="shell booking-layout">
        <div class="booking-content">
          <nav class="section-tabs" aria-label="场馆详情内容">
            <a href="#schedule" class="current">选择时间</a>
            <a href="#venue-about">关于场馆</a>
            <a href="#visit-guide">到场须知</a>
          </nav>

          <section aria-labelledby="space-title">
            <div class="section-heading">
              <h2 id="space-title">先选一处喜欢的空间</h2>
              <p>{{ activeSpace ? modeSubtitle(activeSpace) : '选择场地后查看可约时间' }}</p>
            </div>
            <div v-if="!spaces.length" class="state-message">
              <h3>该场馆暂未开放可预约场地。</h3>
              <p>请稍后再来，或返回场馆列表选择其他场馆。</p>
              <router-link class="text-button" to="/venues">再看看其他地方 <svg class="icon" aria-hidden="true"><use href="#sgv-diagonal" /></svg></router-link>
            </div>
            <div v-else class="space-list" role="group" aria-label="选择场地">
              <button
                v-for="space in spaces"
                :key="space.id"
                type="button"
                class="space-option"
                :class="{ 'is-selected': space.id === activeSpaceId }"
                :aria-pressed="space.id === activeSpaceId"
                :data-test="`space-option-${space.id}`"
                @click="selectSpace(space)"
              >
                <strong>{{ space.name }}</strong>
                <span><span>{{ spaceNote(space) }}</span><span>¥{{ money(space.pricePerHour) }} / 小时</span></span>
                <svg v-if="space.id === activeSpaceId" class="icon space-check" aria-hidden="true"><use href="#sgv-check" /></svg>
              </button>
            </div>
          </section>

          <section v-if="activeSpace" id="schedule" class="schedule">
            <div class="section-heading">
              <h2>再留一段时间</h2>
              <span class="month-label">{{ monthLabel }}</span>
            </div>
            <div class="date-strip" role="group" aria-label="选择预约日期">
              <button
                v-for="day in dateStrip"
                :key="day.value"
                type="button"
                class="date-button"
                :class="{ 'is-selected': day.value === date }"
                :aria-pressed="day.value === date"
                :aria-label="day.ariaLabel"
                :data-test="`date-${day.value}`"
                @click="pickDate(day.value)"
              ><span>{{ day.label }}</span><strong>{{ day.dayOfMonth }}</strong></button>
            </div>
            <div v-if="showMoreDates" class="more-dates">
              <label class="small-label" for="reservation-date">更多日期
                <input
                  id="reservation-date"
                  v-model="date"
                  type="date"
                  :min="minBookableDate"
                  :max="maxBookableDate"
                  data-test="reservation-date-input"
                  @change="void loadSlots()"
                >
              </label>
              <span class="muted">可预约 {{ minBookableDate }} 至 {{ maxBookableDate }}</span>
            </div>

            <div class="slot-caption">
              <p>{{ slotCaption }}</p>
              <div class="legend" aria-hidden="true"><span><i></i>可约</span><span><i class="selected-key"></i>已选</span><span><i class="unavailable-key"></i>不可约</span></div>
            </div>

            <div v-if="selectionRestoreNotice" class="state-message" role="status" data-test="selection-restore-notice">
              <h3>原来的时间已经不能预约了。</h3>
              <p>{{ selectionRestoreNotice }}</p>
            </div>

            <div v-if="slotsLoading" class="state-message" role="status"><h3>正在查询可约时段…</h3></div>
            <div v-else-if="slotsError" class="state-message" role="alert">
              <h3>时段暂时没有加载出来。</h3>
              <p>{{ slotsError }} 你选择的场地和日期仍然保留。</p>
              <button class="text-button" type="button" data-test="reload-slots" @click="loadSlots">重新加载 <svg class="icon" aria-hidden="true"><use href="#sgv-reload" /></svg></button>
            </div>
            <div v-else-if="!slots.length" class="state-message">
              <div class="state-art" aria-hidden="true"><ShiguangArt :scene="scene" /></div>
              <h3>这一天没有可展示的时段。</h3>
              <p>看看其他日期，或换一处喜欢的空间。</p>
              <button v-if="nextDate" class="text-button" type="button" data-test="next-day" @click="pickDate(nextDate)">看看下一天 <svg class="icon" aria-hidden="true"><use href="#sgv-arrow" /></svg></button>
            </div>
            <div v-else class="slot-grid" role="group" aria-label="选择连续时段">
              <button
                v-for="slot in slots"
                :key="slot.startTime"
                type="button"
                class="slot"
                :class="{ 'is-selected': isSelected(slot) }"
                :disabled="!isBookable(slot)"
                :aria-pressed="isSelected(slot)"
                :aria-label="`${clock(slot.startTime)} 至 ${clock(slot.endTime)} ${slotStateText(slot)}`"
                :data-test="`slot-${slot.startTime}`"
                @click="toggleSlot(slot)"
              >
                <strong>{{ clock(slot.startTime) }}–{{ clock(slot.endTime) }}</strong>
                <small>{{ slotStateText(slot) }}</small>
                <svg v-if="isSelected(slot)" class="icon slot-check" aria-hidden="true"><use href="#sgv-check" /></svg>
              </button>
            </div>
            <p class="help-line"><svg class="icon" aria-hidden="true"><use href="#sgv-info" /></svg><span>{{ helpLine }}</span></p>
          </section>

          <section v-if="showCapacity" class="resource-section">
            <div class="section-heading">
              <h2>几个人一起去</h2>
              <p>所选时段余量 {{ remainingCapacity ?? '待查询' }} 人</p>
            </div>
            <div class="capacity-box">
              <div><strong>同行人数</strong><p>每位同行者都需要一个名额。</p></div>
              <div class="stepper" aria-label="同行人数">
                <button type="button" data-test="people-minus" aria-label="减少人数" :disabled="participantCount <= 1" @click="stepPeople(-1)">−</button>
                <output aria-live="polite">{{ participantCount }}</output>
                <button type="button" data-test="people-plus" aria-label="增加人数" :disabled="participantCount >= maxParticipants" @click="stepPeople(1)">+</button>
              </div>
            </div>
            <p v-if="adjustmentNotice" class="inline-error" role="status" data-test="capacity-adjusted"><strong>{{ adjustmentNotice }}</strong></p>
            <p v-if="resourceError" class="inline-error" role="alert" data-test="capacity-error"><strong>{{ resourceError }}</strong></p>
          </section>

          <section v-else-if="showSeats" class="resource-section">
            <div class="section-heading">
              <h2>选一个喜欢的位置</h2>
              <p>{{ selectedSeatLabels.length ? `已选 ${selectedSeatLabels.join('、')}` : '选择一个空位' }}</p>
            </div>
            <div class="capacity-box seat-people">
              <div><strong>同行人数</strong><p>每位同行者需要一个座位，人数随所选座位同步。</p></div>
              <div class="stepper" aria-label="同行人数">
                <button type="button" data-test="seat-people-minus" aria-label="减少一人" :disabled="!selectedSeatIds.length" @click="stepSeatPeople(-1)">−</button>
                <output aria-live="polite">{{ selectedSeatIds.length || 0 }}</output>
                <button type="button" data-test="seat-people-plus" aria-label="增加一人" :disabled="!nextFreeSeat" @click="stepSeatPeople(1)">+</button>
              </div>
            </div>
            <p v-if="seats.length && !selectedSeatIds.length" class="help-line"><svg class="icon" aria-hidden="true"><use href="#sgv-info" /></svg><span>还没有选位置，选好后人数会自动对上。</span></p>
            <div v-if="resourceLoading && !seats.length" class="state-message" role="status"><h3>正在查询座位…</h3></div>
            <div v-else-if="!seats.length" class="state-message" role="alert">
              <h3>座位分布暂时无法加载。</h3>
              <p>{{ resourceError || '该场地还没有配置可用座位。' }}</p>
              <button class="text-button" type="button" data-test="reload-seats" @click="loadResource">重新加载 <svg class="icon" aria-hidden="true"><use href="#sgv-reload" /></svg></button>
            </div>
            <div v-else class="seat-plan">
              <p class="window-label">座位分布示意 · 不代表现场实际朝向</p>
              <div class="seats" role="group" aria-label="选择座位">
                <button
                  v-for="seat in seats"
                  :key="seat.id"
                  type="button"
                  class="seat"
                  :class="{ 'is-selected': selectedSeatIds.includes(seat.id) }"
                  :disabled="seat.available === false || resourceLoading"
                  :aria-pressed="selectedSeatIds.includes(seat.id)"
                  :aria-label="`座位 ${seat.seatLabel || seat.seatCode}${seat.available === false ? ' 已占用' : ''}`"
                  :data-test="`seat-${seat.id}`"
                  @click="toggleSeat(seat.id)"
                >{{ seat.seatLabel || seat.seatCode }}</button>
              </div>
              <p class="seat-note">浅色为已占用 · 带赤陶色边线的座位为你的选择</p>
            </div>
            <p v-if="adjustmentNotice" class="inline-error" role="status" data-test="seat-adjusted"><strong>{{ adjustmentNotice }}</strong></p>
            <p v-if="resourceError && seats.length" class="inline-error" role="alert"><strong>{{ resourceError }}</strong></p>
          </section>

          <section id="venue-about" class="venue-about">
            <h2>关于这里</h2>
            <p>{{ venue.description || '场馆还没有填写介绍。' }}</p>
            <div v-if="facilities.length" class="facilities">
              <span v-for="item in facilities" :key="item">{{ item }}</span>
            </div>
            <figure v-if="venue.image && !posterFailed" class="venue-photo">
              <img :src="venue.image" :alt="venue.name" loading="lazy" decoding="async" @error="posterFailed = true">
              <figcaption>场馆实拍</figcaption>
            </figure>
            <p v-else-if="venue.image && posterFailed" class="section-note">场馆图片暂时无法显示，不影响选择与预约。</p>

            <div class="review-heading">
              <h3>到访评价</h3>
              <span v-if="publicReviewCount" class="muted">{{ averageRatingText }} · {{ publicReviewCount }} 条</span>
              <router-link class="text-button" data-test="write-review-link" :to="{ path: '/me/feedback', query: { type: 'review', venueId: String(venue.id) } }">写评价 <svg class="icon" aria-hidden="true"><use href="#sgv-arrow" /></svg></router-link>
            </div>
            <div v-if="reviewsLoading" class="state-message" role="status"><h3>正在加载评价…</h3></div>
            <div v-else-if="reviewsError" class="state-message">
              <h3>评价暂时无法加载。</h3>
              <p>不影响选择和预约。</p>
              <button class="text-button" type="button" @click="loadReviewContext(Number(route.params.id))">重新加载 <svg class="icon" aria-hidden="true"><use href="#sgv-reload" /></svg></button>
            </div>
            <div v-else-if="venueReviews.length" class="review-list">
              <article v-for="review in venueReviews" :key="review.id" class="review-row">
                <p class="review-rating">评分 {{ (review.overallRating || 0).toFixed(1) }} / 5</p>
                <p>{{ review.contentSanitized }}</p>
                <p class="muted">{{ formatTime(review.submittedAt) }}</p>
              </article>
            </div>
            <p v-else class="section-note">暂无公开评价——去过的话，欢迎在行程完成后留下第一条。</p>
          </section>

          <details id="visit-guide" class="visit-guide">
            <summary>到场之前，了解这些 <svg class="icon" aria-hidden="true"><use href="#sgv-arrow" /></svg></summary>
            <div class="visit-copy">
              <p>{{ venue.name }}的营业时间 {{ openTimeText }}。可预约日期为 {{ minBookableDate }} 至 {{ maxBookableDate }}。</p>
              <p>预约提交后需要等待审核；通过后请按订单提示完成支付，再查看到场凭证。{{ freeCancelText }}取消、改期与退款以对应预约的规则和状态为准。</p>
            </div>
          </details>
        </div>

        <aside class="booking-summary" aria-label="当前选择与预计费用">
          <SummaryPanel
            :scene="scene"
            :date-label="dateLabel"
            :time-label="timeLabel"
            :detail-label="summaryDetail"
            :venue-name="venue.name"
            :space-name="activeSpace?.name"
            :unit-price="activeSpace?.pricePerHour"
            :quantity-line="quantityLine"
            :quantity-amount="quote?.originalAmount"
            :pending-label="selectionPendingLabel"
            :payable="quote?.payableAmount"
            :quote-error="quoteError"
            :loading="quoteLoading"
            :submit-label="continueLabel"
            :submit-disabled="!canContinue"
            :hint="continueHint"
            @submit="goConfirm"
          />
        </aside>
      </div>

      <div class="mobile-booking">
        <div>
          <strong>{{ quote && !quoteError ? `¥${money(quote.payableAmount)}` : '待选择' }}</strong>
          <p>{{ selectedSlots.length ? summaryDetail : (selectionPendingLabel || '选择一段喜欢的时间') }}</p>
        </div>
        <button class="primary-button" type="button" :disabled="!canContinue" data-test="mobile-continue" @click="goConfirm">
          <span>{{ continueLabel }}</span><svg class="icon" aria-hidden="true"><use href="#sgv-arrow" /></svg>
        </button>
      </div>
    </template>

    <!-- 位置示意放大弹层：留在 .sgv-booking 作用域内渲染，样式全部挂在该作用域下 -->
      <div v-if="locLightboxOpen && venue" class="loc-lightbox" role="dialog" aria-modal="true" aria-label="位置示意放大" data-test="loc-lightbox">
        <div class="loc-scrim" @click="locLightboxOpen = false"></div>
        <div class="loc-panel">
          <div class="loc-lb-head">
            <b>{{ venue.name }}</b>
            <button type="button" class="loc-close" aria-label="关闭" @click="locLightboxOpen = false">✕</button>
          </div>
          <div class="loc-map" aria-hidden="true">
            <svg viewBox="0 0 560 380">
              <rect class="lm-paper" width="560" height="380" />
              <g class="lm-block">
                <rect x="30" y="40" width="150" height="92" rx="6" transform="rotate(-1 105 86)" />
                <rect x="220" y="30" width="120" height="70" rx="6" transform="rotate(1 280 65)" />
                <rect x="380" y="52" width="140" height="84" rx="6" transform="rotate(-2 450 94)" />
                <rect x="46" y="180" width="118" height="76" rx="6" transform="rotate(1 105 218)" />
                <rect x="360" y="180" width="150" height="96" rx="6" transform="rotate(-1 435 228)" />
                <rect x="210" y="300" width="130" height="58" rx="6" transform="rotate(1 275 329)" />
              </g>
              <path class="lm-road" d="M0 150 C 140 138, 260 158, 380 146 S 540 152, 560 148" />
              <path class="lm-road-dash" d="M0 150 C 140 138, 260 158, 380 146 S 540 152, 560 148" />
              <path class="lm-road" d="M300 0 L306 380" stroke-width="11" />
              <path class="lm-road-dash" d="M300 0 L306 380" stroke-width="1.6" stroke-dasharray="1 12" />
              <path class="lm-river" d="M120 0 C 116 120, 124 260, 118 380" />
              <g transform="translate(303 149)"><path class="lm-pin" d="M0 10 C -9 -2, -8 -12, 0 -12 C 8 -12, 9 -2, 0 10 Z" /><circle class="lm-pin-hole" cx="0" cy="-4.5" r="3" /></g>
              <g transform="translate(36 330)">
                <rect class="lm-chip" width="216" height="34" rx="8" />
                <circle class="lm-chip-dot" cx="18" cy="17" r="4.5" />
                <text class="lm-chip-text" x="30" y="22" font-size="12.5">{{ venue.address }}</text>
              </g>
            </svg>
          </div>
          <div class="loc-lb-foot">
            <button type="button" class="text-button" data-test="lb-copy-address" @click="copyAddress">复制地址</button>
            <a class="text-button" :href="mapSearchUrl" target="_blank" rel="noopener">导航 <svg class="icon" aria-hidden="true"><use href="#sgv-diagonal" /></svg></a>
            <p class="section-note">位置示意 · 非真实地图；「导航」查看真实路线</p>
          </div>
        </div>
      </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { venueApi } from '@/api/venue'
import { reservationApi } from '@/api/reservation'
import { funnelApi } from '@/api/funnel'
import { feedbackApi, type VenueReview, type VenueReviewSummary } from '@/api/feedback'
import type { AvailabilitySlot, PricingPreviewResponse, ResourceAvailabilityResponse, Venue, VenueSpace } from '@/api/types'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import SummaryPanel from '@/components/shiguang/BookingSummaryPanel.vue'
import { useReservationStore } from '@/stores/reservation'
import { useFunnelStore } from '@/stores/funnel'
import { useMenuStore } from '@/stores/menu'
import { mapVenueScene } from '@/utils/shiguangScene'
import { maskVenuePhone } from '@/utils/entityNames'
import { formatClockText, formatDate, formatMonthDayTime } from '@/utils/datetime'
import { formatShiguangAmount } from '@/utils/money'
import { ElMessage } from 'element-plus'

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const BOOKING_MODE_LABELS: Record<string, string> = {
  EXCLUSIVE: '整场预约',
  CAPACITY: '人数预约',
  ASSIGNED_SEAT: '指定座位'
}
const MODE_SUBTITLES: Record<string, string> = {
  EXCLUSIVE: '整空间独立使用',
  CAPACITY: '按人数预约',
  ASSIGNED_SEAT: '按座位预约'
}
const SPACE_TYPE_LABELS: Record<string, string> = {
  BASKETBALL: '篮球',
  BADMINTON: '羽毛球',
  TENNIS: '网球',
  SWIM: '游泳',
  MEETING_ROOM: '会议空间',
  STUDY_ROOM: '自习空间',
  ACTIVITY_ROOM: '活动空间'
}

const route = useRoute()
const router = useRouter()
const reservationStore = useReservationStore()
const funnel = useFunnelStore()
const menu = useMenuStore()

const venue = ref<Venue | null>(null)
const spaces = ref<VenueSpace[]>([])
const slots = ref<AvailabilitySlot[]>([])
const activeSpaceId = ref<number | undefined>()
const date = ref(formatDate(new Date()))
const loading = ref(true)
const loadError = ref('')
const slotsLoading = ref(false)
const slotsError = ref('')
/** 从确认页返回时原选择已不可用：必须明确提示，不能静默重置为当天。 */
const selectionRestoreNotice = ref('')
const isFavorite = ref(false)
const favoriteLoading = ref(false)
const posterFailed = ref(false)
const locLightboxOpen = ref(false)
const reviewSummary = ref<VenueReviewSummary | null>(null)
const venueReviews = ref<VenueReview[]>([])
const reviewTotal = ref(0)
const reviewsLoading = ref(false)
const reviewsError = ref(false)
const rules = ref<{ maxSlotsPerBooking: number; advanceDays: number; freeCancelHours: number } | null>(null)
const rulesError = ref('')
const resource = ref<ResourceAvailabilityResponse | null>(null)
const resourceLoading = ref(false)
const resourceError = ref('')
const adjustmentNotice = ref('')
const quote = ref<PricingPreviewResponse | null>(null)
const quoteLoading = ref(false)
const quoteError = ref('')
const participantCount = ref(1)
const selectedSeatIds = ref<number[]>([])

const activeSpace = computed(() => spaces.value.find((space) => space.id === activeSpaceId.value) || null)
const bookingMode = computed(() => activeSpace.value?.resourceRule?.bookingMode || 'EXCLUSIVE')
const scene = computed(() => mapVenueScene(activeSpace.value ? [activeSpace.value.type] : []))
const heroEyebrow = computed(() => {
  const typeLabel = activeSpace.value ? (SPACE_TYPE_LABELS[activeSpace.value.type] || activeSpace.value.type) : '场馆'
  return `${typeLabel} · ${BOOKING_MODE_LABELS[bookingMode.value] || '预约'}`
})
const locationText = computed(() => {
  const area = (venue.value?.area || '').trim()
  const address = (venue.value?.address || '').trim()
  if (!address) return area || '地址待确认'
  if (!area || address.startsWith(area) || address.includes(area)) return address
  return `${area} · ${address}`
})
const openTimeText = computed(() =>
  `${formatClockText(venue.value?.openTime, '—')} - ${formatClockText(venue.value?.closeTime, '—')}`
)
const modeSubtitle = (space: VenueSpace) => MODE_SUBTITLES[space.resourceRule?.bookingMode || 'EXCLUSIVE'] || '预约使用'
const spaceNote = (space: VenueSpace) =>
  [space.facilityText, space.capacity ? `可容纳 ${space.capacity} 人` : ''].filter(Boolean).join(' · ') || '场地信息待补充'
const money = formatShiguangAmount
const clock = (value: string) => value?.slice(11, 16) || ''
const canFavorite = computed(() => menu.hasPermission('funnel:favorite:me'))
const facilities = computed(() => (activeSpace.value?.facilityText || '')
  .split(/[、,;/]/).map((text) => text.trim()).filter(Boolean))

/** 日期窗口完全由服务端 rules 决定；rules 未取到时不猜测天数。 */
const advanceDays = computed(() => (rules.value ? Math.max(0, Number(rules.value.advanceDays)) : null))
const startOfToday = () => {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return today
}
const shiftDate = (offset: number) => {
  const day = startOfToday()
  day.setDate(day.getDate() + offset)
  return day
}
const minBookableDate = computed(() => formatDate(shiftDate(0)))
const maxBookableDate = computed(() => (advanceDays.value === null ? '' : formatDate(shiftDate(advanceDays.value))))

const dateStrip = computed(() => {
  if (advanceDays.value === null) return []
  const limit = Math.min(advanceDays.value, 6)
  return Array.from({ length: limit + 1 }, (_, offset) => {
    const day = shiftDate(offset)
    const value = formatDate(day)
    const label = offset === 0 ? '今天' : offset === 1 ? '明天' : WEEKDAYS[day.getDay()]
    return { value, label, dayOfMonth: String(day.getDate()), ariaLabel: `${value} ${label}` }
  })
})
const showMoreDates = computed(() => (advanceDays.value ?? 0) > 6)
const nextDate = computed(() => {
  if (advanceDays.value === null) return ''
  const index = dateStrip.value.findIndex((day) => day.value === date.value)
  return dateStrip.value[index + 1]?.value || ''
})
const monthLabel = computed(() => `${date.value.slice(0, 4)} / ${date.value.slice(5, 7)}`)
const dateLabel = computed(() => {
  if (!date.value) return '选择日期'
  const parsed = new Date(`${date.value}T00:00:00`)
  if (Number.isNaN(parsed.getTime())) return date.value
  return `${parsed.getMonth() + 1} 月 ${parsed.getDate()} 日 · ${WEEKDAYS[parsed.getDay()]}`
})

const selectedSlots = computed(() => reservationStore.selectedSlots)
const isSelected = (slot: AvailabilitySlot) =>
  selectedSlots.value.some((item) => item.spaceId === slot.spaceId && item.startTime === slot.startTime)
const slotNotExpired = (slot: AvailabilitySlot) => {
  const end = Date.parse(slot.endTime)
  return !(Number.isFinite(end) && end <= Date.now())
}
const isBookable = (slot: AvailabilitySlot) => slot.status === 'AVAILABLE' && slotNotExpired(slot)
const slotStateText = (slot: AvailabilitySlot) => {
  if (!slotNotExpired(slot)) return '已过时'
  if (slot.status === 'AVAILABLE') return `¥${money(slot.price)} / 小时`
  return slot.reason || slot.label || '不可预约'
}
const slotDurationHours = computed(() => {
  const first = slots.value.find((slot) => slot.startTime && slot.endTime)
  if (!first) return ''
  const hours = (Date.parse(first.endTime) - Date.parse(first.startTime)) / 3_600_000
  return Number.isFinite(hours) && hours > 0 ? `每段 ${Number(hours.toFixed(1))} 小时` : ''
})
const slotCaption = computed(() => {
  const bookableCount = slots.value.filter(isBookable).length
  return [slotDurationHours.value, `可约 ${bookableCount} 段`].filter(Boolean).join(' · ')
})
const helpLine = computed(() => {
  if (rulesError.value) return rulesError.value
  if (!rules.value) return '正在读取预约规则…'
  const modeHint = bookingMode.value === 'ASSIGNED_SEAT'
    ? '先选连续时段，再选喜欢的位置；座位按所选时间保留。'
    : bookingMode.value === 'CAPACITY'
      ? '选择时段并填写同行人数，余量按人数计算。'
      : '整片场地独立使用，请选择连续时段。'
  return `${modeHint}单次最多可选择 ${rules.value.maxSlotsPerBooking} 个时段，可提前 ${rules.value.advanceDays} 天预约。`
})
const freeCancelText = computed(() => (rules.value?.freeCancelHours
  ? `已支付预约在开始前 ${rules.value.freeCancelHours} 小时外可取消。`
  : ''))

const timeLabel = computed(() => {
  if (!selectedSlots.value.length) return '挑一段好时间'
  const sorted = [...selectedSlots.value].sort((left, right) => left.startTime.localeCompare(right.startTime))
  return `${clock(sorted[0].startTime)}–${clock(sorted[sorted.length - 1].endTime)}`
})
const resourceText = computed(() => {
  if (bookingMode.value === 'ASSIGNED_SEAT') {
    return selectedSeatLabels.value.length ? `座位 ${selectedSeatLabels.value.join('、')}` : '尚未选择座位'
  }
  if (bookingMode.value === 'CAPACITY') return `${participantCount.value} 人同行`
  return '整空间独立使用'
})
const durationHours = computed(() => {
  if (!range.value) return ''
  const hours = (Date.parse(range.value.endTime) - Date.parse(range.value.startTime)) / 3_600_000
  return Number.isFinite(hours) && hours > 0 ? Number(hours.toFixed(1)) : ''
})
const summaryDetail = computed(() => {
  if (!selectedSlots.value.length) return '从左侧选择时段，慢慢安排。'
  return `${durationHours.value || selectedSlots.value.length} 小时 · ${resourceText.value}`
})
const quantityLine = computed(() => {
  if (!selectedSlots.value.length || !durationHours.value) return ''
  return `${durationHours.value} 小时`
})
const selectionPendingLabel = computed(() => {
  if (!selectedSlots.value.length) return '从左侧选择时段，慢慢安排。'
  if (bookingMode.value === 'ASSIGNED_SEAT' && !selectedSeatIds.value.length) return '请先选择座位'
  return ''
})
/** 缓存最近一次成功返回的座位标签，避免可售查询失败时把内部 ID 直接暴露给用户。 */
const seatLabels = ref<Record<number, string>>({})
const selectedSeatLabels = computed(() => selectedSeatIds.value.map((id) => seatLabels.value[id] || '待确认'))
const continueHint = computed(() => {
  if (slotsError.value) return '时段没有读出来，先重新加载再选。'
  if (!selectedSlots.value.length) return '先选一段时间，再确认预约。'
  if (bookingMode.value === 'ASSIGNED_SEAT' && !selectedSeatIds.value.length) return '这个场地要选位置，选好后就能继续。'
  return ''
})
const canContinue = computed(() => {
  if (!selectedSlots.value.length || slotsLoading.value || Boolean(slotsError.value)) return false
  if (bookingMode.value === 'ASSIGNED_SEAT') return selectedSeatIds.value.length > 0
  return true
})
const continueLabel = computed(() => {
  if (!selectedSlots.value.length) return '继续，确认预约'
  if (bookingMode.value === 'ASSIGNED_SEAT' && !selectedSeatIds.value.length) return '请先选择座位'
  return '继续，确认预约'
})

const seats = computed(() => resource.value?.rule.seats || [])
const remainingCapacity = computed(() => resource.value?.remainingCapacity ?? null)
const maxParticipants = computed(() => Math.max(1, remainingCapacity.value ?? 1))
const showCapacity = computed(() => selectedSlots.value.length > 0 && bookingMode.value === 'CAPACITY')
const showSeats = computed(() => selectedSlots.value.length > 0 && bookingMode.value === 'ASSIGNED_SEAT')

const range = computed(() => {
  if (!selectedSlots.value.length) return null
  const sorted = [...selectedSlots.value].sort((left, right) => left.startTime.localeCompare(right.startTime))
  return { startTime: sorted[0].startTime, endTime: sorted[sorted.length - 1].endTime }
})

const selectionPayload = computed(() => {
  if (!range.value || !activeSpaceId.value) return null
  return {
    spaceId: activeSpaceId.value,
    startTime: range.value.startTime,
    endTime: range.value.endTime,
    slots: selectedSlots.value,
    ...(bookingMode.value === 'CAPACITY' ? { participantCount: participantCount.value } : {}),
    // 服务端要求指定座位模式的人数等于座位数，缺省会按 1 归一并直接 400
    ...(bookingMode.value === 'ASSIGNED_SEAT' ? { participantCount: Math.max(1, selectedSeatIds.value.length), seatIds: selectedSeatIds.value } : {})
  }
})

let resourceSequence = 0
const loadResource = async () => {
  const payload = selectionPayload.value
  if (!payload || bookingMode.value === 'EXCLUSIVE') {
    resource.value = null
    resourceError.value = ''
    return
  }
  const sequence = ++resourceSequence
  resourceLoading.value = true
  resourceError.value = ''
  try {
    const response = await reservationApi.resourceAvailability(payload)
    if (sequence !== resourceSequence) return
    resource.value = response
    for (const seat of response.rule.seats) {
      seatLabels.value = { ...seatLabels.value, [seat.id]: seat.seatLabel || seat.seatCode }
    }
    if (response.rule.bookingMode === 'ASSIGNED_SEAT') {
      const available = new Set(response.rule.seats.filter((seat) => seat.available !== false).map((seat) => seat.id))
      const retained = selectedSeatIds.value.filter((id) => available.has(id))
      if (retained.length !== selectedSeatIds.value.length) {
        selectedSeatIds.value = retained
        adjustmentNotice.value = retained.length
          ? '部分座位刚刚被占用，已为你保留仍可用的座位。'
          : '你选的座位刚刚被占用，请重新选择一个位置。'
      }
    }
    if (response.rule.bookingMode === 'CAPACITY' && response.remainingCapacity < participantCount.value) {
      participantCount.value = Math.max(1, response.remainingCapacity)
      adjustmentNotice.value = `所选时段余量只有 ${response.remainingCapacity} 人，已按余量调整同行人数。`
    }
    if (!response.bookable && !resourceError.value) {
      resourceError.value = response.reasonText || '当前选择暂不可预约，请刷新后重选'
    }
  } catch (error) {
    if (sequence !== resourceSequence) return
    resource.value = null
    resourceError.value = readableError(error, '资源可售暂时无法确认，请稍后重试')
  } finally {
    if (sequence === resourceSequence) resourceLoading.value = false
  }
}

let quoteSequence = 0
const loadQuote = async () => {
  const payload = selectionPayload.value
  if (!payload || (bookingMode.value === 'ASSIGNED_SEAT' && !selectedSeatIds.value.length)) {
    quote.value = null
    quoteError.value = ''
    return
  }
  const sequence = ++quoteSequence
  quoteLoading.value = true
  quoteError.value = ''
  try {
    const response = await reservationApi.quote(payload)
    if (sequence !== quoteSequence) return
    quote.value = response
  } catch (error) {
    if (sequence !== quoteSequence) return
    quote.value = null
    quoteError.value = readableError(error, '费用暂时无法计算')
  } finally {
    if (sequence === quoteSequence) quoteLoading.value = false
  }
}

const readableError = (error: unknown, fallback: string) => {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (message) return message
  return error instanceof Error && error.message ? error.message : fallback
}

const selectSpace = (space: VenueSpace) => {
  if (space.id === activeSpaceId.value) return
  adjustmentNotice.value = ''
  activeSpaceId.value = space.id
  void loadSlots()
}

const pickDate = (value: string) => {
  if (!value || value === date.value) return
  adjustmentNotice.value = ''
  // 用户已经主动换日期，返回提示不再有意义
  selectionRestoreNotice.value = ''
  date.value = value
  void loadSlots()
}

const clearNotice = () => {
  adjustmentNotice.value = ''
  selectionRestoreNotice.value = ''
}

const stepPeople = (delta: number) => {
  const next = participantCount.value + delta
  if (next < 1 || next > maxParticipants.value) return
  clearNotice()
  participantCount.value = next
  reservationStore.setResourceSelection({ participantCount: next })
}

const toggleSeat = (seatId: number) => {
  clearNotice()
  selectedSeatIds.value = selectedSeatIds.value.includes(seatId)
    ? selectedSeatIds.value.filter((id) => id !== seatId)
    : [...selectedSeatIds.value, seatId]
  syncSeatPeople()
}

const nextFreeSeat = computed(() => (resource.value?.rule.seats || [])
  .find((seat) => seat.available !== false && !selectedSeatIds.value.includes(seat.id)) || null)

/** 人数与座位数在服务端必须一致，因此这里只允许「同步变更」，不给出不一致的报错。 */
const stepSeatPeople = (delta: number) => {
  if (delta > 0) {
    const next = nextFreeSeat.value
    if (!next) {
      adjustmentNotice.value = '这个时段的空位已经都选上了，换一个时段或日期试试。'
      return
    }
    clearNotice()
    selectedSeatIds.value = [...selectedSeatIds.value, next.id]
    adjustmentNotice.value = `已为你加上 ${(next.seatLabel || next.seatCode)}`
    syncSeatPeople()
    return
  }
  if (!selectedSeatIds.value.length) return
  clearNotice()
  const removed = selectedSeatIds.value[selectedSeatIds.value.length - 1]
  selectedSeatIds.value = selectedSeatIds.value.filter((id) => id !== removed)
  syncSeatPeople()
}

const syncSeatPeople = () => {
  participantCount.value = Math.max(1, selectedSeatIds.value.length)
  reservationStore.setResourceSelection({
    participantCount: participantCount.value,
    seatIds: selectedSeatIds.value
  })
}

const toggleSlot = (slot: AvailabilitySlot) => {
  const exists = isSelected(slot)
  const next = exists
    ? selectedSlots.value.filter((item) => !(item.spaceId === slot.spaceId && item.startTime === slot.startTime))
    : [...selectedSlots.value, slot]
  if (rules.value && next.length > rules.value.maxSlotsPerBooking) {
    ElMessage.warning(`单次最多可选择 ${rules.value.maxSlotsPerBooking} 个时段`)
    return
  }
  const sorted = [...next].sort((left, right) => left.startTime.localeCompare(right.startTime))
  for (let index = 1; index < sorted.length; index += 1) {
    if (sorted[index - 1].endTime !== sorted[index].startTime) {
      ElMessage.warning('请选择连续时段')
      return
    }
  }
  reservationStore.setSelectionContext({ venueId: Number(route.params.id), spaceName: activeSpace.value?.name })
  reservationStore.setSelectedSlots(sorted)
  if (!exists) {
    void funnel.track({
      eventName: 'SLOT_SELECTED',
      eventStage: 'DETAIL',
      venueId: Number(route.params.id),
      spaceId: slot.spaceId,
      pagePath: `/venues/${String(route.params.id)}`,
      metadata: { startTime: slot.startTime, endTime: slot.endTime, price: slot.price }
    })
  }
}

const goConfirm = () => {
  reservationStore.setSelectionContext({
    venueId: Number(route.params.id),
    venueName: venue.value?.name,
    spaceName: activeSpace.value?.name,
    spaceType: activeSpace.value?.type
  })
  reservationStore.setResourceSelection({
    participantCount: bookingMode.value === 'CAPACITY' ? participantCount.value : 1,
    seatIds: bookingMode.value === 'ASSIGNED_SEAT' ? selectedSeatIds.value : []
  })
  void router.push('/reservation/confirm')
}

let slotsSequence = 0
const loadSlots = async () => {
  if (!activeSpaceId.value) return
  const sequence = ++slotsSequence
  const requestedSpaceId = activeSpaceId.value
  const requestedDate = date.value
  const carriedSelection = reservationStore.selectedSlots
  slotsLoading.value = true
  slotsError.value = ''
  try {
    const response = await reservationApi.availability({ spaceId: requestedSpaceId, date: requestedDate })
    if (sequence !== slotsSequence) return
    slots.value = response
    // 选择只在仍属于本次场地与日期、且每一档依旧可约时保留；否则清空重选
    const stillValid = carriedSelection.length > 0
      && carriedSelection.every((chosen) => chosen.spaceId === requestedSpaceId
        && response.some((fresh) => fresh.startTime === chosen.startTime && fresh.status === 'AVAILABLE'))
    if (!stillValid) {
      if (carriedSelection.length > 0 && carriedSelection.some((chosen) => chosen.spaceId === requestedSpaceId)) {
        // 同场地同日期但已不可约：说明原时段过期或被占用，必须说清楚再让用户重选。
        selectionRestoreNotice.value = '原来选择的时段已经不可预约了，请重新选择时间。你的联系人信息不会被清空。'
      }
      reservationStore.clearSlot()
    } else {
      selectionRestoreNotice.value = ''
    }
    if (!response.some((slot) => isBookable(slot))) {
      void funnel.track({
        eventName: 'DROPOFF_RECORDED',
        eventStage: 'DETAIL',
        venueId: Number(route.params.id),
        spaceId: requestedSpaceId,
        dropoffReasonCode: 'NO_SELLABLE_SLOT',
        pagePath: `/venues/${String(route.params.id)}`
      })
    }
  } catch (error) {
    if (sequence !== slotsSequence) return
    slots.value = []
    slotsError.value = readableError(error, '时段暂时无法加载')
  } finally {
    if (sequence === slotsSequence) slotsLoading.value = false
  }
}

const loadRules = async () => {
  try {
    const data = await reservationApi.rules()
    rules.value = {
      maxSlotsPerBooking: data.maxSlotsPerBooking || 1,
      advanceDays: Number(data.advanceDays),
      freeCancelHours: data.freeCancelHours
    }
    reservationStore.setLockSecondsFromRules(data.lockSeconds)
  } catch (error) {
    rules.value = null
    rulesError.value = `${readableError(error, '预约规则暂时无法读取')}——请先选择日期，提交时服务端仍会校验可约范围。`
  }
}

const loadFavoriteStatus = async (venueId: number) => {
  if (!canFavorite.value) return
  try {
    const status = await funnelApi.favoriteStatus(venueId)
    isFavorite.value = Boolean(status.favorite)
  } catch {
    isFavorite.value = false
  }
}

const favoriteEvent = (eventName: string, venueId: number) =>
  funnel.event({ eventName, eventStage: 'FAVORITE', venueId, pagePath: `/venues/${venueId}` })

const toggleFavorite = async () => {
  if (favoriteLoading.value || !canFavorite.value) return
  const venueId = Number(route.params.id)
  favoriteLoading.value = true
  try {
    if (isFavorite.value) {
      await funnelApi.cancelFavorite(venueId, favoriteEvent('FAVORITE_REMOVED', venueId))
      isFavorite.value = false
      ElMessage.success('已取消收藏')
    } else {
      await funnelApi.favoriteVenue(venueId, favoriteEvent('FAVORITE_ADDED', venueId))
      isFavorite.value = true
      ElMessage.success('已加入收藏')
    }
  } catch (error) {
    ElMessage.error(readableError(error, '收藏状态未能更新'))
  } finally {
    favoriteLoading.value = false
  }
}

const loadReviewContext = async (venueId: number) => {
  reviewsLoading.value = true
  reviewsError.value = false
  try {
    const [summary, page] = await Promise.all([
      feedbackApi.venueReviewSummary(venueId),
      feedbackApi.venueReviews(venueId, { pageNum: 1, pageSize: 5 })
    ])
    reviewSummary.value = summary
    venueReviews.value = page.records
    reviewTotal.value = page.total
  } catch {
    reviewSummary.value = null
    venueReviews.value = []
    reviewTotal.value = 0
    reviewsError.value = true
  } finally {
    reviewsLoading.value = false
  }
}

const averageRatingText = computed(() => Number(reviewSummary.value?.averageRating ?? 0).toFixed(1))
const publicReviewCount = computed(() => Number(reviewSummary.value?.reviewCount ?? reviewTotal.value ?? venueReviews.value.length) || 0)
const formatTime = (value?: string) => (value ? formatMonthDayTime(value) : '-')

const mapSearchUrl = computed(() => `https://map.baidu.com/search/${encodeURIComponent(venue.value?.address || '')}`)
const copyAddress = async () => {
  try {
    await navigator.clipboard.writeText(venue.value?.address || '')
    ElMessage.success('地址已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

const onLocLightboxKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape') locLightboxOpen.value = false
}
watch(locLightboxOpen, (open) => {
  if (open) document.addEventListener('keydown', onLocLightboxKeydown)
  else document.removeEventListener('keydown', onLocLightboxKeydown)
})

const loadCore = async () => {
  const venueId = Number(route.params.id)
  loading.value = true
  loadError.value = ''
  posterFailed.value = false
  void loadFavoriteStatus(venueId)
  void loadReviewContext(venueId)
  void loadRules()
  try {
    const [detail, venueSpaces] = await Promise.all([venueApi.detail(venueId), venueApi.spaces(venueId)])
    venue.value = detail
    spaces.value = venueSpaces
    void funnel.track({
      eventName: 'VENUE_DETAIL_VIEWED',
      eventStage: 'DETAIL',
      venueId,
      pagePath: `/venues/${venueId}`
    })
    const queryDate = typeof route.query.date === 'string' ? route.query.date : ''
    if (/^\d{4}-\d{2}-\d{2}$/.test(queryDate) && queryDate >= minBookableDate.value) date.value = queryDate
    // 显式 spaceId 优先于草稿记忆：从确认页返回时它保证回到原来的场地。
    const querySpaceId = typeof route.query.spaceId === 'string' ? route.query.spaceId : ''
    const preferred = spaces.value.find((space) => String(space.id) === querySpaceId)
      || spaces.value.find((space) => String(space.id) === String(reservationStore.spaceId))
      || spaces.value[0]
    activeSpaceId.value = preferred?.id
    reservationStore.setSelectionContext({ venueId, venueName: detail.name, spaceName: preferred?.name })
    await loadSlots()
  } catch (error) {
    venue.value = null
    spaces.value = []
    loadError.value = readableError(error, '请检查网络连接后重试')
  } finally {
    loading.value = false
  }
}

watch([() => selectionPayload.value?.startTime, () => selectionPayload.value?.endTime, () => selectedSeatIds.value.join(','), participantCount], () => {
  void loadResource()
  void loadQuote()
})
watch(selectedSlots, () => {
  if (!selectedSlots.value.length) {
    selectedSeatIds.value = []
    participantCount.value = reservationStore.participantCount || 1
  }
}, { deep: true })

onBeforeUnmount(() => document.removeEventListener('keydown', onLocLightboxKeydown))

onMounted(() => {
  reservationStore.restoreDraft()
  participantCount.value = reservationStore.participantCount || 1
  selectedSeatIds.value = [...reservationStore.seatIds]
  void loadCore()
})
</script>
