<template>
  <div class="page-shell home-page">
    <!-- 呼吸引导钮（预览 §01 intro-hint）：wait/scattered 态显示；点击经 document 捕获层触发聚形 -->
    <button class="intro-hint" type="button" data-test="intro-hint">
      <i aria-hidden="true"></i>{{ intro.hintLabel.value }}
    </button>

    <!-- 首屏 · 品牌叙事（V6 预览 §02 B 居中定稿）：中轴海报式单列，实况横排数据条；
         hero 五段/实况/四格为 ps-block（进场聚形交接对象），footline 按 --sg-irvd 分层浮现 -->
    <section class="hp-hero rv" v-reveal>
      <div class="hp-hero-main ps-block">
        <p class="hp-kicker"><i class="q" aria-hidden="true">「</i>场地空间与活动平台<i class="q" aria-hidden="true">」</i></p>
        <h1 class="hp-hero-title" data-test="home-hero-title">
          <span class="t1-row">
            <span
              class="t1"
              :class="{ 'grad-on': intro.heroLit.value, 'can-scatter': intro.phase.value === 'done' }"
              data-test="home-hero-t1"
              title="点我，散开成粒子"
            >时间</span>
            <span class="egg" :class="{ on: intro.heroLit.value }" aria-hidden="true">
              <span class="clk sg-num">{{ eggClockMain }}<b>:{{ eggClockSeconds }}</b></span>
              <span class="egg-lb">今日可约 <b class="sg-num">{{ openSpaceCount ?? '—' }}</b> 个场地</span>
            </span>
          </span>
          <span class="t2">自有<em>去处</em></span>
        </h1>
        <p class="hp-hero-quote"><i class="q" aria-hidden="true">「</i>{{ heroQuote }}<i class="q" aria-hidden="true">」</i></p>
        <p class="hp-hero-note">{{ heroNote }}</p>
      </div>

      <!-- 今日实况（V6 预览 §03）：横排四格数据条（上下发丝线、首格数字柿橙），
           全部现有 API 真实数据，零假数字；有数行可点跳转（悬停弹簧），「—」行纯展示不可点 -->
      <aside class="hp-live ps-block" aria-label="今日实况" data-test="home-live-panel">
        <component
          :is="row.value == null ? 'div' : 'router-link'"
          v-for="row in liveRows"
          :key="row.key"
          class="li"
          :to="row.value == null ? undefined : row.to"
        >
          <span class="n sg-num" :class="{ hot: row.hot }">{{ row.value ?? '—' }}</span>
          <span class="lb">{{ row.label }}<span v-if="row.value != null" class="to" aria-hidden="true">→</span></span>
        </component>
      </aside>

      <!-- 场景四格（V6 预览 §04）：通栏定距排列，随 hero 重构移出左栏（S02 复核坐标断言） -->
      <div class="hp-kinds ps-block" data-test="home-kinds">
        <router-link v-for="kind in venueKinds" :key="kind.name" class="hp-kind" :to="kind.to" :data-test="`home-kind-${kind.key}`">
          <svg viewBox="0 0 32 32" aria-hidden="true"><path :d="kind.path" /><circle v-if="kind.circle" cx="16" cy="16" r="9" /></svg>
          <span class="k-nm">{{ kind.name }}</span>
          <span class="k-ct sg-num">{{ kind.count }} 家</span>
        </router-link>
      </div>

      <div class="hp-footline sg-intro-el" style="--sg-irvd: 150ms">
        <form class="hp-search" role="search" @submit.prevent="submitSearch">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><circle cx="7" cy="7" r="4.5" /><path d="M10.5 10.5L14 14" /></svg>
          <input
            v-model="keyword"
            type="search"
            placeholder="找场地：场馆、场地或活动…"
            aria-label="搜索场馆"
          />
        </form>
      </div>
    </section>

    <!-- 第二屏 · 下一场（编辑排版，无卡底，上下发丝线） -->
    <section v-if="nextTrip" class="hp-band rv" v-reveal style="--rvd: 80ms; --sg-irvd: 420ms" data-test="home-next-band">
      <div class="hp-band-head">
        <h3>下一场</h3>
        <span class="hint">{{ nextDateLabel }}</span>
        <router-link class="more sg-link" to="/me/reservations">查看全部行程 →</router-link>
      </div>
      <div class="hp-next">
        <span class="t sg-num">{{ nextTimeText }}</span>
        <span class="hp-next-main">
          <span class="v">{{ nextVenueName }}<template v-if="nextSpaceLabel !== nextVenueName"> · {{ nextSpaceLabel }}</template></span>
          <span class="m sg-num">
            <template v-if="nextCountdown">倒计时 <b class="sg-mono sg-tick" :key="nextCountdown">{{ nextCountdown }}</b> · </template><template v-else>进行中 · </template>{{ nextStatusLabel }}
          </span>
        </span>
        <span class="a">
          <router-link
            v-if="nextPrimary"
            class="sg-btn-primary"
            :to="nextPrimary.to"
            data-test="next-primary-action"
          >{{ nextPrimary.label }}</router-link>
          <router-link class="sg-btn-text" to="/me/feedback">问题反馈</router-link>
        </span>
      </div>
    </section>

    <EmptyState
      v-else-if="emptyGuide"
      class="home-empty rv"
      v-reveal
      :title="emptyGuide.title"
      :description="emptyGuide.text"
      style="--rvd: 80ms; --sg-irvd: 420ms"
      data-test="home-empty-guide"
    >
      <template #action>
        <router-link class="sg-btn-capsule" :to="emptyGuide.to">{{ emptyGuide.linkLabel }}</router-link>
      </template>
    </EmptyState>

    <div class="ad-slot rv" v-reveal style="--rvd: 140ms; --sg-irvd: 540ms">
      <HomePrimaryPlacement />
    </div>

    <!-- 第三屏 · 此刻正在发生（左侧 2px 主色标） -->
    <section v-if="happeningItems.length" class="hp-band rv" v-reveal style="--rvd: 200ms; --sg-irvd: 660ms" data-test="home-now-band">
      <div class="hp-band-head">
        <h3>此刻正在发生</h3>
        <span class="hint">来自社区</span>
        <router-link v-if="canAccessCommunity" class="more sg-link" to="/community">去发现 →</router-link>
      </div>
      <div class="hp-now" data-test="home-now-list">
        <router-link v-for="item in happeningCards" :key="item.key" class="hp-now-item" :to="item.to">
          <span class="tag">{{ item.typeLabel }}</span>
          <span class="ti">{{ item.title }}</span>
          <span class="me">{{ item.meta }}</span>
        </router-link>
      </div>
    </section>

    <div class="ad-slot rv" v-reveal style="--rvd: 260ms; --sg-irvd: 760ms">
      <HomeCommercialAdvertising />
    </div>

    <!-- 第四屏 · 附近场馆（品类轨道，搜索入口已退到首屏底部） -->
    <section class="hp-band venue-rail-section rv" v-reveal style="--rvd: 320ms; --sg-irvd: 860ms">
      <div class="hp-band-head">
        <h3>附近的场馆</h3>
        <span class="hint sg-num">{{ openVenueCount }} 家开放</span>
        <router-link class="more sg-link" to="/venues">全部场馆 →</router-link>
      </div>
      <el-skeleton v-if="loading" animated :rows="5" />
      <div v-else-if="loadError" class="home-state" role="alert">
        <el-icon><Warning /></el-icon>
        <div>
          <strong>推荐场馆暂时没有加载出来</strong>
          <span>{{ loadError }}</span>
        </div>
        <el-button @click="loadVenues">重新加载</el-button>
      </div>
      <div v-else-if="venues.length" class="hp-rail sg-rail" data-test="home-feature-venue">
        <router-link v-for="venue in venues" :key="venue.id" class="hp-card sg-press" :to="`/venues/${venue.id}`">
          <span class="hp-th" :data-wash="washOf(venue)" aria-hidden="true">
            <img
              v-if="venueSrcOf(venue)"
              :src="venueSrcOf(venue)"
              :alt="venue.name"
              loading="lazy"
              decoding="async"
              @error="onVenueImageError(venue)"
            />
            <VenueTypeMotif v-else :motif="venueMotifOf(venue)" class="rail-motif" />
            <span v-if="venue.minPrice != null" class="price sg-num">¥{{ venue.minPrice }}/时</span>
          </span>
          <span class="hp-cb">
            <span class="nm">{{ venue.name }}</span>
            <span class="mt">{{ rowMeta(venue) }}</span>
          </span>
        </router-link>
        <span class="rail-peek" aria-hidden="true"></span>
      </div>
      <EmptyState v-else title="暂无推荐场馆" description="请稍后刷新或联系管理员维护场馆数据" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Warning } from '@element-plus/icons-vue'
import { venueApi } from '@/api/venue'
import { reservationApi } from '@/api/reservation'
import { activityApi } from '@/api/activity'
import { couponApi } from '@/api/promotion'
import type { CommunityActivity, CompanionRequest } from '@/api/activity'
import type { Reservation, VenueListItem } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import HomePrimaryPlacement from '@/components/placement/HomePrimaryPlacement.vue'
import HomeCommercialAdvertising from '@/components/advertising/HomeCommercialAdvertising.vue'
import VenueTypeMotif from '@/components/VenueTypeMotif.vue'
import { useIntroCeremony } from '@/composables/useIntroCeremony'
import { useMenuStore } from '@/stores/menu'
import { useAuthStore } from '@/stores/auth'
import { formatClockText } from '@/utils/datetime'
import { fetchVenueName, fetchSpaceName } from '@/utils/entityNames'
import { venuePrimaryType, venueVisualOf, venueWashOf } from '@/utils/venueVisual'

const router = useRouter()
const venues = ref<VenueListItem[]>([])
const trips = ref<Reservation[]>([])
const activities = ref<CommunityActivity[]>([])
const companions = ref<CompanionRequest[]>([])
const loading = ref(true)
const tripsLoading = ref(false)
const happeningLoading = ref(false)
const loadError = ref('')
const keyword = ref('')
const now = ref(Date.now())
let clockTimer: number | undefined
const menu = useMenuStore()
const auth = useAuthStore()
const canAccessCommunity = computed(() => menu.hasPermission('community:access'))
const canAccessTrips = computed(() => menu.hasPermission('reservation:my'))
/** 类型→视觉（V5 §04 删内置 stock 图）：仅用户自传 venue.image 显示真实图，无图一律画笔图标。 */
const imageErrorVenueIds = ref(new Set<number>())
const visualOf = (venue: VenueListItem) => venueVisualOf(venue)
const venueSrcOf = (venue: VenueListItem) => {
  if (imageErrorVenueIds.value.has(venue.id)) return undefined
  return visualOf(venue).kind === 'image' ? visualOf(venue).src : undefined
}
const venueMotifOf = (venue: VenueListItem) => visualOf(venue).motif || 'house'
const onVenueImageError = (venue: VenueListItem) => {
  imageErrorVenueIds.value.add(venue.id)
}
/** 排印海报 wash（V3 L4）：馆型专属色注入轨道卡（游泳雾蓝/球馆松绿/自习蜜金） */
const washOf = (venue: VenueListItem) => venueWashOf(venuePrimaryType(venue))

/** V11 hero 语气层（A3 全同步）：标语/大字/引语/注脚/时钟统一志莽行书（--sg-font-hero），
    引语为品牌主张一句，注脚按会话门区分社区/匿名口径。 */
const heroQuote = '把下一段时间，先留给自己。'

const heroNote = computed(() =>
  canAccessCommunity.value
    ? '城里的球场、泳馆与琴房，今天都空着几格。'
    : '预约学习、运动和活动空间，管理自己的预约与订单。'
)

/** V6 进场仪式（F-01，预览 §01）：状态机/护栏/会话门见 useIntroCeremony；
    hero 渐变流动与时钟彩蛋随 heroLit 在聚形交接 +360ms 亮起，散开/重聚复位。 */
const intro = useIntroCeremony()

/** 时钟彩蛋（预览 §02 egg）：实时 HH:MM:SS，秒数柿橙（b 段）；复用既有的每秒 now 心跳 */
const eggClockMain = computed(() => {
  const date = new Date(now.value)
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
})
const eggClockSeconds = computed(() => pad(new Date(now.value).getSeconds()))

const parseTime = (value: string) => new Date(value.replace(' ', 'T'))
const pad = (value: number) => String(value).padStart(2, '0')
const clockText = (value: string) => `${pad(parseTime(value).getHours())}:${pad(parseTime(value).getMinutes())}`

const typeLabels: Record<string, string> = {
  BASKETBALL: '篮球',
  BADMINTON: '羽毛球',
  TENNIS: '网球',
  SWIMMING: '游泳',
  MEETING_ROOM: '会议',
  STUDY: '自习',
  STUDY_ROOM: '自习',
  ACTIVITY: '活动',
  ACTIVITY_ROOM: '活动'
}
const primaryTypeLabel = (venue: VenueListItem) =>
  (venue.spaceTypes || []).map((type) => typeLabels[type]).find(Boolean)

/** 首屏四场景入口（预览 §03 hp-kinds）：按场馆 spaceTypes 真实归类计数（同一场馆可计入多个场景）。 */
const KIND_DEFS: Array<{ key: string; name: string; types: string[]; path: string; circle: boolean }> = [
  { key: 'sport', name: '球馆', types: ['BASKETBALL', 'BADMINTON', 'TENNIS'], path: 'M16 4v24M4 16h11M21 16h7', circle: true },
  { key: 'pool', name: '泳池', types: ['SWIMMING'], path: 'M4 21c3-3 6-3 9 0s6 3 9 0 4-3 6-1M8 12V6m8 6V6m8 6V6', circle: false },
  { key: 'study', name: '自习', types: ['STUDY', 'STUDY_ROOM', 'MEETING_ROOM'], path: 'M6 7h8v18H6zM18 7h8v18h-8zM10 9v14M22 9v14', circle: false },
  { key: 'activity', name: '活动', types: ['ACTIVITY', 'ACTIVITY_ROOM'], path: 'M16 6l10 6v14H6V12zM12 26v-7h8v7', circle: false }
]
const venueKinds = computed(() =>
  KIND_DEFS.map((def) => ({
    ...def,
    count: venues.value.filter(
      (venue) => venue.status === 'ACTIVE' && (venue.spaceTypes || []).some((type) => def.types.includes(type))
    ).length,
    to: '/venues'
  }))
)

const reservationStatusLabels: Record<string, string> = {
  PENDING_REVIEW: '待审核',
  APPROVED: '已通过',
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  COMPLETED: '已完成',
  REJECTED: '已拒绝',
  CANCELED: '已取消',
  EXPIRED: '已过期',
  REFUNDING: '退款中',
  REFUNDED: '已退款'
}

/** 今日实时口径：开放场馆的可约场地总数（真实 API 字段 activeSpaceCount 汇总；
    「时段」口径无面向用户的库存汇总 API，按场地口径诚实计数，见 Log S04 登记）。
    V5 §05：数字移居「今日实况」数据栏（hot 主色），footline 不再重复。 */
const openSpaceCount = computed(() =>
  venues.value
    .filter((venue) => venue.status === 'ACTIVE')
    .reduce((sum, venue) => sum + (venue.activeSpaceCount || 0), 0)
)

/** 本周社区活动：活动时间窗与本自然周（周一起）相交的活动数（activityApi 真实列表客户端计数）；
    无 community 权限或加载失败保持 null，数据栏显示「—」不造假。 */
const weekActivityCount = ref<number | null>(null)
const startOfWeek = (date: Date) => {
  const dayOffset = (date.getDay() + 6) % 7
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() - dayOffset).getTime()
}

/** 可用优惠券：会员接口 /coupons/my 中未使用的已领取券（CLAIMED/RELEASED，与优惠券页「去使用」口径一致）；
    未登录或加载失败保持 null 显示「—」。 */
const couponCount = ref<number | null>(null)
const USABLE_COUPON_STATUSES = ['CLAIMED', 'RELEASED']

/** 今日实况四行（V5 §05 修订版）：有数即可点入口，「—」（无权限/失败）行纯展示不可点。 */
const liveRows = computed(() => [
  { key: 'open-spaces', label: '今天可约场地', value: openSpaceCount.value as number | null, to: '/venues', hot: true },
  { key: 'open-venues', label: '开放场馆', value: openVenueCount.value as number | null, to: '/venues', hot: false },
  { key: 'week-activities', label: '本周社区活动', value: weekActivityCount.value, to: '/activities', hot: false },
  { key: 'coupons', label: '可用优惠券', value: couponCount.value, to: '/me/coupons', hot: false }
])

/** 今日主场候选：仍在有效状态且尚未结束的最近一场。 */
const ACTIVE_TRIP_STATUSES = ['PENDING_REVIEW', 'APPROVED', 'PENDING_PAYMENT', 'PAID']
const upcomingTrips = computed(() =>
  trips.value
    .filter((trip) => ACTIVE_TRIP_STATUSES.includes(trip.status))
    .filter((trip) => {
      const end = parseTime(trip.endTime).getTime()
      return Number.isFinite(end) && end > now.value
    })
    .sort((a, b) => parseTime(a.startTime).getTime() - parseTime(b.startTime).getTime())
)
const nextTrip = computed(() => upcomingTrips.value[0])

const nextVenueName = computed(() => {
  if (!nextTrip.value) return ''
  return nextTrip.value.venueName || venueLabel(nextTrip.value.venueId)
})
const nextTimeText = computed(() => (nextTrip.value ? clockText(nextTrip.value.startTime) : ''))
const nextDateLabel = computed(() => {
  if (!nextTrip.value) return ''
  const date = parseTime(nextTrip.value.startTime)
  const today = new Date()
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()
  const startOfDay = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  const dayOffset = Math.round((startOfDay - startOfToday) / 86400000)
  const week = `周${'日一二三四五六'[date.getDay()]}`
  if (dayOffset === 0) return `今天 ${week}`
  if (dayOffset === 1) return `明天 ${week}`
  return `${date.getMonth() + 1}月${date.getDate()}日 ${week}`
})
const nextCountdown = computed(() => {
  if (!nextTrip.value) return ''
  const start = parseTime(nextTrip.value.startTime).getTime()
  const delta = start - now.value
  if (!Number.isFinite(delta) || delta <= 0) return ''
  const totalSeconds = Math.floor(delta / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
})
const nextStatusLabel = computed(() =>
  nextTrip.value
    ? (nextCountdown.value ? reservationStatusLabels[nextTrip.value.status] || nextTrip.value.status : '进行中')
    : ''
)
const nextSpaceLabel = computed(() => {
  if (!nextTrip.value) return ''
  return tripSpaceNames.value.get(`${nextTrip.value.venueId}-${nextTrip.value.spaceId}`) || nextVenueName.value
})
const nextPrimary = computed(() => {
  const trip = nextTrip.value
  if (!trip) return null
  if (trip.status === 'PENDING_PAYMENT') {
    return { label: '去支付', to: trip.orderId ? `/me/orders/${trip.orderId}/pay` : '/me/orders' }
  }
  if (trip.status === 'PAID') {
    return { label: '出示核销凭证', to: '/me/reservations' }
  }
  return null
})

const tripVenueNames = ref(new Map<number, string>())
const tripSpaceNames = ref(new Map<string, string>())
const venueLabel = (venueId: number) => {
  const fromRecommendations = venues.value.find((item) => item.id === venueId)?.name
  if (fromRecommendations) return fromRecommendations
  return tripVenueNames.value.get(venueId) || '场馆'
}
const ensureTripVenueName = async (venueId: number) => {
  if (tripVenueNames.value.has(venueId) || venues.value.some((item) => item.id === venueId)) return
  const name = await fetchVenueName(venueId)
  if (name) tripVenueNames.value.set(venueId, name)
}
const ensureTripSpaceName = async (trip: Reservation) => {
  const key = `${trip.venueId}-${trip.spaceId}`
  if (tripSpaceNames.value.has(key)) return
  const name = await fetchSpaceName(trip.venueId, trip.spaceId)
  if (name) tripSpaceNames.value.set(key, name)
}

const happeningItems = computed(() => [
  ...activities.value.map((activity) => ({
    key: `activity-${activity.id}`,
    typeLabel: '活动',
    title: activity.title,
    meta: `${activity.participantCount}/${activity.capacityTotal} 人`,
    to: `/activities/${activity.id}`
  })),
  ...companions.value.map((request) => ({
    key: `companion-${request.id}`,
    typeLabel: '找搭子',
    title: request.title,
    meta: `${request.participantCount} 人响应`,
    to: `/companion-requests/${request.id}`
  }))
])
const happeningCards = computed(() => happeningItems.value.slice(0, 3))

const emptyGuide = computed(() => {
  if (tripsLoading.value || happeningLoading.value) return null
  if (auth.isLoggedIn && canAccessTrips.value && !upcomingTrips.value.length) {
    return { title: '还没有行程', text: '挑一块合适的场地，把下一段时间先留给自己。', to: '/venues', linkLabel: '去找场地' }
  }
  if (auth.isLoggedIn && canAccessCommunity.value && !happeningItems.value.length) {
    return { title: '还没有关注', text: '去发现社区看看大家在玩什么。', to: '/community', linkLabel: '去发现' }
  }
  return null
})

const openVenueCount = computed(() => venues.value.filter((venue) => venue.status === 'ACTIVE').length)

const venueHours = (venue: VenueListItem) =>
  `${formatClockText(venue.openTime, '08:00')}–${formatClockText(venue.closeTime, '22:00')}`
const rowMeta = (venue: VenueListItem) =>
  [
    venue.area,
    primaryTypeLabel(venue),
    venue.activeSpaceCount ? `${venue.activeSpaceCount} 个场地` : '',
    venueHours(venue)
  ].filter(Boolean).join(' · ')

const submitSearch = () => {
  const trimmed = keyword.value.trim()
  void router.push({ path: '/venues', query: trimmed ? { keyword: trimmed } : {} })
}

const loadVenues = async () => {
  loading.value = true
  loadError.value = ''
  try {
    // 一页取全量开放场馆：四场景入口计数与实时口径都要求接近全集的诚实数字
    const page = await venueApi.list({ pageNum: 1, pageSize: 100 })
    venues.value = page.records
  } catch {
    venues.value = []
    loadError.value = '请检查网络后重试'
  } finally {
    loading.value = false
  }
}

const loadTrips = async () => {
  if (!canAccessTrips.value) return
  tripsLoading.value = true
  try {
    const page = await reservationApi.my({ pageNum: 1, pageSize: 8 })
    trips.value = page.records
    page.records.forEach((trip) => {
      void ensureTripVenueName(trip.venueId)
      void ensureTripSpaceName(trip)
    })
  } catch {
    trips.value = []
  } finally {
    tripsLoading.value = false
  }
}

const loadHappening = async () => {
  if (!canAccessCommunity.value) return
  happeningLoading.value = true
  try {
    // 一次取近 50 条：横条展示前 3，「本周社区活动」计数需要足够窗口（活动库现共个位数，见 Log）
    const [activityPage, companionPage] = await Promise.all([
      activityApi.activities({ limit: 50 }),
      activityApi.companions({ limit: 3 })
    ])
    activities.value = activityPage.records
    companions.value = companionPage.records
    const weekStart = startOfWeek(new Date())
    const weekEnd = weekStart + 7 * 86400000
    weekActivityCount.value = activities.value.filter((activity) => {
      const start = parseTime(activity.startTime).getTime()
      const end = parseTime(activity.endTime).getTime()
      return Number.isFinite(start) && Number.isFinite(end) && start < weekEnd && end > weekStart
    }).length
  } catch {
    activities.value = []
    companions.value = []
    weekActivityCount.value = null
  } finally {
    happeningLoading.value = false
  }
}

const loadCoupons = async () => {
  if (!auth.isLoggedIn) return
  try {
    const page = await couponApi.my({ pageNum: 1, pageSize: 100 })
    couponCount.value = page.records.filter((coupon) => USABLE_COUPON_STATUSES.includes(coupon.status)).length
  } catch {
    couponCount.value = null
  }
}

onMounted(() => {
  void loadVenues()
  void loadTrips()
  void loadHappening()
  void loadCoupons()
  clockTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (clockTimer !== undefined) window.clearInterval(clockTimer)
})
</script>

<style scoped>
/* ============ V11 首页 hero（A3 全同步定稿）：语气层统一志莽行书 --sg-font-hero，
   功能信息层（实况/四格/表单）保持 sans；中轴海报式骨架沿用 V6 §02 ============ */

/* ---- 首屏：单列居中，hero 五段/实况/四格为 ps-block（聚形交接对象） ---- */
.hp-hero {
  display: block;
  text-align: center;
  padding: clamp(28px, 4.5vw, 56px) 0 clamp(26px, 4vw, 48px);
}

.hp-hero-main {
  max-width: 780px;
  margin: 0 auto;
}

.hp-kicker {
  margin: 0 0 clamp(14px, 2.4vw, 26px);
  color: var(--sg-ink-muted);
  font-family: var(--sg-font-hero);
  font-size: clamp(17px, 1.9vw, 21px);
  font-weight: 400;
  letter-spacing: 0.14em;
}

.hp-kicker .q {
  font-style: normal;
  color: var(--sg-primary);
}

/* 大字定稿（V11 A3 全同步）：志莽行书 hero 栈 + 渐变流动 + 时钟彩蛋；
   t1-row 用 last-baseline 底对齐——「今日可约」标签基线与大字基线同线，
   彩蛋整块收进大字字面高度（旧浏览器回退首行基线 baseline） */
.hp-hero-title {
  margin: 0;
  line-height: 1.04;
  font-weight: 400;
}

.t1-row {
  display: flex;
  align-items: baseline;
  align-items: last baseline;
  justify-content: center;
  gap: clamp(16px, 2.4vw, 30px);
}

.hp-hero-title .t1 {
  display: block;
  font-family: var(--sg-font-hero);
  font-weight: 400;
  font-size: clamp(72px, 10.5vw, 142px);
  letter-spacing: 0.04em;
  line-height: 1.04;
  color: var(--sg-ink);
}

/* 渐变流动（100deg、四停、background-size 260%、13s 往返）；background-clip:text 元素禁 text-shadow */
.hp-hero-title .t1.grad-on {
  background: linear-gradient(100deg, var(--sg-ink) 24%, var(--sg-primary) 44%, var(--sg-honey) 56%, var(--sg-ink) 80%);
  background-size: 260% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  animation: hpGradFlow 13s ease-in-out infinite alternate;
}

@keyframes hpGradFlow {
  from {
    background-position: 0% 0;
  }

  to {
    background-position: 100% 0;
  }
}

/* 散开入口：仅 intro-done 后生效；hover 手型 + 提示 + 轻降透明 */
.hp-hero-title .t1.can-scatter {
  cursor: pointer;
}

.hp-hero-title .t1.can-scatter:hover {
  opacity: 0.84;
}

/* 时钟彩蛋：发丝线左界竖排两行（V11 全同步：行书时钟 + 行书标签），聚形交接 +360ms 随 heroLit 浮现 */
.egg {
  display: grid;
  gap: 3px;
  padding-left: clamp(12px, 1.6vw, 18px);
  border-left: 1px solid var(--sg-border);
  text-align: left;
  opacity: 0;
  transform: translateY(6px);
  transition: opacity 0.6s var(--sg-ease), transform 0.6s var(--sg-ease-spring);
}

.egg.on {
  opacity: 1;
  transform: none;
}

.egg .clk {
  font-family: var(--sg-font-hero);
  font-size: clamp(22px, 2.6vw, 30px);
  line-height: 1;
  color: var(--sg-ink);
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}

.egg .clk b {
  color: var(--sg-primary);
  font-weight: 400;
}

.egg .egg-lb {
  font-family: var(--sg-font-hero);
  color: var(--sg-ink-subtle);
  font-size: clamp(13px, 1.5vw, 15px);
  letter-spacing: 0.06em;
}

.egg .egg-lb b {
  color: var(--sg-primary);
  font-weight: 400;
}

.hp-hero-title .t2 {
  display: block;
  margin-top: clamp(10px, 1.6vw, 18px);
  font-family: var(--sg-font-hero);
  font-weight: 400;
  font-size: clamp(22px, 3vw, 36px);
  letter-spacing: 0.4em;
  text-indent: 0.4em;
  color: var(--sg-ink-muted);
}

.hp-hero-title .t2 em {
  font-style: normal;
  color: var(--sg-primary);
}

/* 引语（V11 A3 全同步）：品牌主张一句行书 + 注脚行书小字；q 括号柿橙 */
.hp-hero-quote {
  margin: clamp(18px, 2.6vw, 30px) auto 0;
  color: var(--sg-ink);
  font-family: var(--sg-font-hero);
  font-size: clamp(24px, 3vw, 32px);
  font-weight: 400;
  letter-spacing: 0.07em;
  line-height: 1.4;
}

.hp-hero-quote .q {
  font-style: normal;
  color: var(--sg-primary);
}

.hp-hero-note {
  margin: 12px auto 0;
  color: var(--sg-ink-subtle);
  font-family: var(--sg-font-hero);
  font-size: clamp(15px, 1.7vw, 18px);
  font-weight: 400;
  letter-spacing: 0.08em;
  line-height: 1.8;
}

/* 今日实况（V6 预览 §03）：横排四格数据条（上下发丝线、格间竖发丝线、首格数字柿橙） */
.hp-live {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  max-width: 880px;
  margin: clamp(22px, 3.6vw, 38px) auto 0;
  padding: 13px 0;
  border-top: 1px solid var(--sg-border);
  border-bottom: 1px solid var(--sg-border);
}

.hp-live .li {
  display: grid;
  justify-items: center;
  gap: 3px;
  padding: 0 8px;
  color: inherit;
  text-decoration: none;
  border-left: 1px solid var(--sg-border-soft);
  transition: transform var(--sg-motion-base) var(--sg-ease-spring);
}

.hp-live .li:first-child {
  border-left: 0;
}

.hp-live a.li {
  cursor: pointer;
}

.hp-live a.li:hover {
  transform: translateY(-2px);
}

.hp-live a.li:hover .n {
  color: var(--sg-primary);
}

.hp-live .n {
  font-family: var(--sg-font-display);
  font-size: clamp(24px, 2.6vw, 30px);
  font-weight: 400;
  line-height: 1;
  color: var(--sg-ink);
  font-variant-numeric: tabular-nums;
  transition: color var(--sg-motion-fast) var(--sg-ease-standard);
}

.hp-live .n.hot {
  color: var(--sg-primary);
}

.hp-live .lb {
  position: relative;
  color: var(--sg-ink-muted);
  font-size: 13px;
}

/* V11 对齐修订：箭头绝对定位挂右侧，标签文字保持与数字同一条光轴居中 */
.hp-live .lb .to {
  position: absolute;
  right: -15px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--sg-ink-subtle);
}

/* 场景四格（V6 预览 §04）：通栏 flex 定距排列（不拉伸），格内同轴居中 */
.hp-kinds {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: clamp(30px, 6.5vw, 104px);
  margin-top: clamp(24px, 4vw, 44px);
  padding-top: clamp(18px, 3vw, 28px);
  border-top: 1px solid var(--sg-border);
}

.hp-kind {
  display: grid;
  justify-items: center;
  text-align: center;
  gap: 4px;
  min-width: 72px;
  padding: 6px 4px 14px;
  color: var(--sg-ink);
  border-bottom: 1px solid transparent;
  transition: border-color var(--sg-motion-base) var(--sg-ease-standard), transform var(--sg-motion-base) var(--sg-ease-spring);
}

.hp-kind:hover {
  border-bottom-color: var(--sg-primary);
  transform: translateY(-3px);
}

.hp-kind svg {
  width: clamp(26px, 3vw, 34px);
  height: clamp(26px, 3vw, 34px);
  fill: none;
  stroke: var(--sg-ink-muted);
  stroke-width: 1.4;
  stroke-linecap: round;
  stroke-linejoin: round;
  opacity: 0.8;
  transition: stroke var(--sg-motion-fast) var(--sg-ease-standard);
}

.hp-kind:hover svg {
  stroke: var(--sg-primary);
  opacity: 1;
}

.hp-kind .k-nm {
  font-size: 14px;
  font-weight: 600;
}

.hp-kind .k-ct {
  color: var(--sg-ink-subtle);
  font-size: 13px;
}

/* 首屏底部（V6 预览 §02 footline）：只留搜索胶囊，随 hero 中轴居中 */
.hp-footline {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: clamp(18px, 3vw, 30px);
  padding-top: 16px;
  border-top: 1px solid var(--sg-border-soft);
}

.hp-search {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-height: 44px;
  padding: 9px 15px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--sg-ink) 4%, transparent);
  color: var(--sg-ink-muted);
  font-size: 13.5px;
  transition: background-color var(--sg-motion-fast) var(--sg-ease-standard), color var(--sg-motion-fast) var(--sg-ease-standard);
}

.hp-search:hover,
.hp-search:focus-within {
  background: color-mix(in srgb, var(--sg-ink) 7%, transparent);
  color: var(--sg-ink);
}

.hp-search input {
  width: 170px;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--sg-ink);
  font: inherit;
}

.hp-search input::placeholder {
  color: var(--sg-ink-subtle);
}

/* ---- 通用区块：发丝线 + 展示字标题（无卡） ---- */
.hp-band {
  padding: clamp(30px, 5vw, 54px) 0;
  border-top: 1px solid var(--sg-border);
}

.hp-band-head {
  display: flex;
  align-items: baseline;
  gap: 14px;
  margin-bottom: clamp(16px, 2.6vw, 26px);
}

.hp-band-head h3 {
  margin: 0;
  font-family: var(--sg-font-display);
  font-size: clamp(18px, 2.3vw, 23px);
  font-weight: 400;
  letter-spacing: 0.015em;
}

.hp-band-head .hint {
  color: var(--sg-ink-subtle);
  font-size: 13px;
}

.hp-band-head .more {
  margin-left: auto;
  color: var(--sg-primary);
  font-size: 13px;
  font-weight: 600;
}

/* ---- 下一场：编辑排版，无卡底 ---- */
.hp-next {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: clamp(16px, 3vw, 40px);
  align-items: center;
}

.hp-next .t {
  font-family: var(--sg-font-display);
  font-size: clamp(40px, 6.4vw, 68px);
  font-weight: 400;
  letter-spacing: -0.01em;
  line-height: 1;
  color: var(--sg-ink);
}

.hp-next-main {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.hp-next .v {
  font-family: var(--sg-font-display);
  font-size: clamp(18px, 2.4vw, 25px);
  font-weight: 400;
  letter-spacing: 0.015em;
}

.hp-next .m {
  color: var(--sg-ink-subtle);
  font-size: 13px;
}

.hp-next .a {
  display: flex;
  gap: 9px;
  align-items: center;
}

/* ---- 此刻正在发生：左侧 2px 主色标行卡 ---- */
.hp-now {
  display: grid;
  /* V6 F-09：横条上限 320px + 整组居中（与场馆列表 356px 同族规则） */
  grid-template-columns: repeat(auto-fit, minmax(210px, 320px));
  justify-content: center;
  gap: 14px;
}

.hp-now-item {
  display: grid;
  gap: 6px;
  padding: 18px 20px;
  border-radius: var(--sg-radius-card);
  border-left: 2px solid var(--sg-primary);
  background: color-mix(in srgb, var(--sg-ink) 3.5%, transparent);
  color: inherit;
  transition: transform var(--sg-motion-base) var(--sg-ease-spring), background-color var(--sg-motion-fast) var(--sg-ease-standard);
}

.hp-now-item:hover {
  transform: translateX(4px);
  background: color-mix(in srgb, var(--sg-ink) 6%, transparent);
}

.hp-now-item .tag {
  color: var(--sg-primary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.hp-now-item .ti {
  font-family: var(--sg-font-display);
  font-size: 16.5px;
  font-weight: 400;
  letter-spacing: 0.015em;
  line-height: 1.45;
}

.hp-now-item .me {
  color: var(--sg-ink-subtle);
  font-size: 13px;
}

/* ---- 横滑轨道：极浅底卡，无描边 ---- */
.hp-rail {
  padding-bottom: 8px;
}

.hp-card {
  /* V6 预览 §04 修复 2b：随量变定宽——3 张恰好铺满版心（(100%−28px)/3 与 14px 栏距配平），
     右缘不再空档；>3 张同宽横滑、与区块头两端对齐 */
  flex: 0 0 calc((100% - 28px) / 3);
  min-width: 250px;
  display: block;
  border-radius: var(--sg-radius-card);
  overflow: hidden;
  color: inherit;
  background: color-mix(in srgb, var(--sg-ink) 3.5%, transparent);
  transition: transform var(--sg-motion-base) var(--sg-ease-spring), background-color var(--sg-motion-fast) var(--sg-ease-standard);
}

.hp-card:hover {
  transform: translateY(-4px);
  background: color-mix(in srgb, var(--sg-ink) 6%, transparent);
}

.hp-th {
  position: relative;
  display: grid;
  place-items: center;
  height: 150px;
  overflow: hidden;
}

.hp-th img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.hp-th .rail-motif {
  width: clamp(64px, 9vw, 84px);
  height: clamp(64px, 9vw, 84px);
}

.hp-th[data-wash='mist'] {
  background: linear-gradient(150deg, color-mix(in srgb, var(--sg-mist) 15%, transparent), transparent 72%), color-mix(in srgb, var(--sg-mist) 7%, transparent);
  color: var(--sg-mist);
}

.hp-th[data-wash='pine'] {
  background: linear-gradient(150deg, color-mix(in srgb, var(--sg-pine) 15%, transparent), transparent 72%), color-mix(in srgb, var(--sg-pine) 7%, transparent);
  color: var(--sg-pine);
}

.hp-th[data-wash='honey'] {
  background: linear-gradient(150deg, color-mix(in srgb, var(--sg-honey) 16%, transparent), transparent 72%), color-mix(in srgb, var(--sg-honey) 8%, transparent);
  color: var(--sg-honey);
}

.hp-th .price {
  position: absolute;
  right: 9px;
  bottom: 9px;
  padding: 2px 9px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--sg-canvas) 92%, transparent);
  color: var(--sg-primary);
  font-size: 12px;
  font-weight: 700;
}

/* 卡底行文（V5 预览 §04 .cb/.nm）：名称（展示字 17px）与元信息（13px 弱色）同行接排。
   display:block 与场馆列表 .venue-body 同修（走查 2026-08-30 F-04）：行内 span 的 padding
   不作用于换行续行，元信息两行时第二行会顶出卡缘。 */
.hp-cb {
  display: block;
  padding: 12px 16px 15px;
  color: var(--sg-ink-subtle);
  font-size: 13px;
  line-height: 1.6;
}

.hp-cb .nm {
  margin-right: 6px;
  font-family: var(--sg-font-display);
  font-size: 17px;
  font-weight: 400;
  letter-spacing: 0.02em;
  color: var(--sg-ink);
}

.hp-cb .mt {
  font-size: 13px;
}

/* ---- 状态与响应式 ---- */
.home-state {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 13px 16px;
  border-radius: var(--sg-radius-card);
  background: var(--sg-danger-soft);
  color: color-mix(in srgb, var(--sg-danger) 78%, var(--sg-ink));
  font-size: var(--sg-fz-sm);
}

.home-state strong,
.home-state span {
  display: block;
}

.home-state span {
  margin-top: 4px;
}

@media (max-width: 640px) {
  /* 预览 §03：实况横条折两列，奇数格去竖发丝线 */
  .hp-live {
    grid-template-columns: repeat(2, 1fr);
    row-gap: 14px;
    padding: 16px 0;
  }

  .hp-live .li:nth-child(odd) {
    border-left: 0;
  }
}

@media (max-width: 900px) {
  .hp-card {
    flex-basis: calc((100% - 14px) / 2);
  }

  .hp-next {
    grid-template-columns: auto 1fr;
  }

  .hp-next .a {
    grid-column: 1 / -1;
  }
}

@media (max-width: 560px) {
  .hp-card {
    flex-basis: 78%;
  }

  .hp-next {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .hp-search {
    margin-left: 0;
    width: 100%;
  }

  .hp-search input {
    flex: 1;
    width: auto;
  }

  .hp-footline {
    gap: 12px;
  }
}
</style>
