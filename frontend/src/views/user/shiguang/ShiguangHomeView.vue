<template>
  <div id="sgv-discovery-view">
    <div class="overview-band">
      <div class="home-shell overview-grid">
        <section class="daily-section" aria-labelledby="sgv-daily-title">
          <div class="section-heading">
            <h2 id="sgv-daily-title">今日提案</h2>
            <div class="feature-controls">
              <span class="micro">{{ featureIndex + 1 }} / {{ featureVenues.length }}</span>
              <button class="icon-button" type="button" aria-label="上一条提案" :disabled="featureVenues.length < 2" @click="stepFeature(-1)">
                <svg aria-hidden="true"><use href="#sgv-back" /></svg>
              </button>
              <button class="icon-button" type="button" aria-label="下一条提案" :disabled="featureVenues.length < 2" @click="stepFeature(1)">
                <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
              </button>
            </div>
          </div>
          <!--
            F-03 区块身份（SHIGUANG-WAVE-01-REMEDIATION-20260916 S02）：
            `v-if`/`v-else` 的容器不再携带 `:key`，两条分支共享的 block 结构因此稳定；
            提案身份与入场动画交给内层同一个 `:key="feature.id"` 元素重新挂载。
            原实现把 `:key` 与 `v-if` 放在同一元素上：切回「全部发现」时，
            两条分支共享的 block 出现 dynamicChildren 漂移，Vue 补丁在读
            `oldChildren[i].el` 时抛 `Cannot read properties of undefined (reading 'el')`，
            并让进入首页后的后续导航持续报同类错误。
          -->
          <div v-if="feature" class="daily-feature" :style="{ '--feature-direction': String(featureDirection) }">
            <div :key="feature.id" class="daily-feature-inner is-changing">
              <div class="feature-copy">
                <p class="feature-kicker">{{ editorial.kicker }}</p>
                <h3 v-html="editorial.lines.join('<br>')" />
                <p class="feature-description">{{ editorial.description }}</p>
                <p class="feature-venue">
                  <span>{{ feature.name }}</span>
                  <span>{{ availabilityText(feature.id) }}</span>
                </p>
                <button class="text-button" type="button" @click="store.openVenueDrawer(feature.id)">看看这段时间 <svg aria-hidden="true"><use href="#sgv-arrow" /></svg></button>
              </div>
              <div class="feature-art" aria-hidden="true"><ShiguangArt :scene="featureScene" /></div>
            </div>
          </div>
          <div v-else class="feature-empty">
            <h3>好地方，值得再找一找。</h3>
            <p>换个区域或关键词，让下一段时间有新的可能。</p>
          </div>
        </section>
        <section class="my-time-section" aria-labelledby="sgv-my-time-title">
          <div class="section-heading">
            <h2 id="sgv-my-time-title">我的时间</h2>
            <router-link class="all-link" to="/me/time">全部安排 <svg aria-hidden="true"><use href="#sgv-diagonal" /></svg></router-link>
          </div>
          <div v-if="nextItem" class="trip-summary">
            <p class="trip-date">
              <svg aria-hidden="true"><use href="#sgv-calendar" /></svg>
              {{ formatDay(nextItem.startTime) }}
              <span class="trip-status">{{ itemStatusLabel(nextItem) }}</span>
            </p>
            <div class="trip-time-row">
              <span class="trip-time">{{ formatTime(nextItem.startTime) }}</span>
              <span v-if="nextItem.endTime" class="trip-time-end">至 {{ formatTime(nextItem.endTime) }}</span>
            </div>
            <h3 class="trip-name">{{ nextItem.title }}</h3>
            <p class="trip-location">{{ nextItem.location || '地点待确认' }}</p>
            <div class="trip-action-row">
              <span>{{ nextItem.pending ? '等待发起者回应' : sourceLabel(nextItem.sourceType) }}</span>
              <router-link class="text-button" :to="nextItem.detailPath">查看我的安排 <svg aria-hidden="true"><use href="#sgv-arrow" /></svg></router-link>
            </div>
          </div>
          <div v-else-if="!myTimeLoading" class="trip-empty">
            <span class="empty-time-mark" aria-hidden="true"><svg><use href="#sgv-calendar" /></svg></span>
            <h3>下一段时间，<br>还没写下。</h3>
            <p>一场球、一本书，或一次相聚。<br>喜欢的事，值得慢慢安排。</p>
            <router-link class="text-button" to="/venues">找一处喜欢的地方 <svg aria-hidden="true"><use href="#sgv-arrow" /></svg></router-link>
          </div>
        </section>
      </div>
    </div>

    <div class="home-shell">
      <section class="places-section" aria-labelledby="sgv-places-title">
        <div class="section-heading">
          <h2 id="sgv-places-title" tabindex="-1">附近可约</h2>
          <router-link class="all-link" to="/venues">全部场馆 <svg aria-hidden="true"><use href="#sgv-diagonal" /></svg></router-link>
        </div>
        <div ref="railRef" class="venue-rail" role="region" aria-label="横向探索场馆" tabindex="0" @scroll.passive="updateRail" @keydown="onRailKeydown" @pointerdown="onRailPointerDown" @pointermove="onRailPointerMove" @pointerup="onRailPointerUp" @pointercancel="onRailPointerUp">
          <article v-for="venue in railVenues" :key="venue.id" class="venue-card" :data-venue="venue.id">
            <button class="venue-art" type="button" :aria-label="`查看 ${venue.name}`" @click="railClickGuard(venue.id)">
              <ShiguangArt :scene="mapVenueScene(venue.spaceTypes)" />
            </button>
            <div class="venue-title-row">
              <button type="button" @click="railClickGuard(venue.id)">{{ venue.name }}</button>
              <span class="price">¥{{ venue.minPrice ?? '—' }}<small>/ 小时</small></span>
            </div>
            <p class="venue-location">{{ venue.area || venue.address }}</p>
            <div class="venue-bottom">
              <button class="time-chip" type="button" @click="railClickGuard(venue.id)">
                {{ availabilityText(venue.id) }} <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
              </button>
            </div>
          </article>
        </div>
        <div v-if="!railVenues.length && !venuesLoading" class="empty-results">
          <h3>暂时没有合适的空位。</h3>
          <p>换个区域或关键词，再看看。</p>
        </div>
        <div class="rail-footer">
          <div class="rail-progress" aria-hidden="true"><span :style="{ width: railProgress }" /></div>
          <div class="rail-controls">
            <span id="sgv-rail-position" class="sr-only">{{ railPositionLabel }}</span>
            <button class="icon-button" type="button" aria-label="向左浏览场馆" :disabled="railAtStart" @click="scrollRail(-1)">
              <svg aria-hidden="true"><use href="#sgv-back" /></svg>
            </button>
            <button class="icon-button" type="button" aria-label="向右浏览场馆" :disabled="railAtEnd" @click="scrollRail(1)">
              <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
            </button>
          </div>
        </div>
      </section>

      <div class="home-lower">
        <section class="activity-section" aria-labelledby="sgv-activities-title">
          <div class="section-heading">
            <div>
              <h2 id="sgv-activities-title">这周，一起出门</h2>
              <p class="section-intro">精选活动，与等人同行的小邀约。</p>
            </div>
            <router-link class="all-link" to="/activities">全部活动 <svg aria-hidden="true"><use href="#sgv-diagonal" /></svg></router-link>
          </div>
          <div class="activity-list">
            <article v-for="card in activityCards" :key="card.key" class="activity-card">
              <p class="activity-source">{{ card.source }}</p>
              <h3><button type="button" @click="goCard(card)">{{ card.title }}</button></h3>
              <p class="activity-description">{{ card.description }}</p>
              <p class="activity-facts">{{ card.facts }}</p>
              <div class="activity-action">
                <span>{{ card.status }}</span>
                <button class="text-button" type="button" @click="goCard(card)">
                  {{ card.kind === 'invitation' ? '看看这场邀约' : '查看这场活动' }} <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
                </button>
              </div>
            </article>
          </div>
          <p v-if="!activityCards.length" class="activity-empty">还没有匹配的邀约，换个关键词看看。</p>
        </section>
        <section class="community-section" aria-labelledby="sgv-community-title">
          <div class="section-heading">
            <div>
              <h2 id="sgv-community-title">城市里的回声</h2>
              <p class="section-intro">来自社区里的日常分享。</p>
            </div>
            <router-link class="icon-button" to="/community" aria-label="查看社区话题"><svg aria-hidden="true"><use href="#sgv-diagonal" /></svg></router-link>
          </div>
          <button v-if="echo" class="topic-preview" type="button" @click="router.push('/community')">
            <strong>{{ echo.title }}</strong>
            <span>“{{ echo.excerpt }}”</span>
            <span class="topic-footer">
              <span>{{ echo.groupName }}</span>
              <span>看看大家怎么说 <svg aria-hidden="true"><use href="#sgv-arrow" /></svg></span>
            </span>
          </button>
          <p v-else class="activity-empty">社区里暂时没有公开的新分享。</p>
        </section>
      </div>

      <HomePrimaryPlacement />
      <HomeCommercialAdvertising />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { venueApi } from '@/api/venue'
import { activityApi } from '@/api/activity'
import type { CommunityActivity, CompanionRequest } from '@/api/activity'
import { meTimeApi, type MeTimeItem } from '@/api/meTime'
import { communityApi } from '@/api/community'
import { reservationApi } from '@/api/reservation'
import type { VenueListItem, VenueAvailabilitySummary } from '@/api/types'
import { useShiguangStore } from '@/stores/shiguang'
import { useMenuStore } from '@/stores/menu'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import { FEATURE_EDITORIAL, mapVenueScene, categoryMatchesSpaceTypes } from '@/utils/shiguangScene'
import HomePrimaryPlacement from '@/components/placement/HomePrimaryPlacement.vue'
import HomeCommercialAdvertising from '@/components/advertising/HomeCommercialAdvertising.vue'

const router = useRouter()
const store = useShiguangStore()
const menu = useMenuStore()
/**
 * AR-03：首页上的活动/同行/回声是可选附属内容。无 `community:access` 时省略请求；
 * 即便权限在服务端被撤销而返回 403，也用局部降级配置留在首页，而不是被全局跳转到 /403。
 * 401 与受限深链的全局行为不变。
 */
const canAccessCommunity = computed(() => menu.hasPermission('community:access'))
const communityFallback = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

const venuesLoading = ref(true)
const myTimeLoading = ref(true)
const railVenues = ref<VenueListItem[]>([])
const summaries = ref<Map<number, VenueAvailabilitySummary>>(new Map())
const myTimeItems = ref<MeTimeItem[]>([])
const featureIndex = ref(0)
const activities = ref<CommunityActivity[]>([])
const companions = ref<CompanionRequest[]>([])
const echo = ref<{ title: string; excerpt: string; groupName?: string | null } | null>(null)
const railRef = ref<HTMLElement | null>(null)
const railProgress = ref('0%')
const railAtStart = ref(true)
const railAtEnd = ref(false)
const railPositionLabel = ref('00 - 00 / 00')

// S05 裁决变更：卡片/提案点击打开速览侧栏；详情页保留为「查看完整详情」的深层入口
const goVenue = (id: number) => store.openVenueDrawer(id)

const railDragging = ref(false)
let railStartX = 0
let railStartScroll = 0
let railMoved = false
let suppressRailClick = false

const onRailPointerDown = (event: PointerEvent) => {
  if (event.pointerType === 'mouse' && event.button !== 0) return
  railDragging.value = true
  railMoved = false
  railStartX = event.clientX
  railStartScroll = railRef.value?.scrollLeft ?? 0
}

const onRailPointerMove = (event: PointerEvent) => {
  if (!railDragging.value) return
  const rail = railRef.value
  if (!rail) return
  const distance = event.clientX - railStartX
  if (Math.abs(distance) > 5) {
    railMoved = true
    rail.classList.add('is-dragging')
    rail.scrollLeft = railStartScroll - distance
  }
}

const onRailPointerUp = () => {
  if (!railDragging.value) return
  railDragging.value = false
  railRef.value?.classList.remove('is-dragging')
  if (railMoved) {
    suppressRailClick = true
    window.setTimeout(() => { suppressRailClick = false }, 120)
  }
  updateRail()
}

const railClickGuard = (id: number) => {
  if (suppressRailClick) return
  store.openVenueDrawer(id)
}

const dayLabel = () => (store.day ? '明天' : '今天')

const availabilityText = (venueId: number) => {
  const summary = summaries.value.get(venueId)
  if (!summary) return dayLabel()
  if (!summary.available || !summary.startTime) return `${dayLabel()}暂无可约`
  return `${dayLabel()} ${summary.startTime.slice(11, 16)} 可约`
}

watch(() => store.day, () => {
  const ids = railVenues.value.slice(0, 10).map((venue) => venue.id)
  if (!ids.length) return
  const date = new Date()
  date.setDate(date.getDate() + store.day)
  reservationApi.availabilitySummary({ date: new Intl.DateTimeFormat('sv-SE').format(date), venueIds: ids })
    .then((list) => { summaries.value = new Map(list.map((s) => [s.venueId, s])) })
    .catch(() => {})
})

const featureVenues = computed(() =>
  railVenues.value.filter((venue) => categoryMatchesSpaceTypes(store.category, venue.spaceTypes))
)
const feature = computed(() => featureVenues.value[featureIndex.value] ?? null)
const featureScene = computed(() => mapVenueScene(feature.value?.spaceTypes))
const editorial = computed(() => FEATURE_EDITORIAL[featureScene.value])

const featureDirection = ref(1)
const stepFeature = (direction: number) => {
  if (featureVenues.value.length < 2) return
  featureDirection.value = direction
  featureIndex.value = (featureIndex.value + direction + featureVenues.value.length) % featureVenues.value.length
}

const nextItem = computed(() => myTimeItems.value[0] ?? null)
const sourceLabel = (sourceType: string) =>
  ({ RESERVATION: '场地预约', ACTIVITY: '活动参与', COMPANION_REQUEST: '同行邀约' })[sourceType] ?? sourceType

const itemStatusLabel = (item: MeTimeItem) => item.pending ? '待确认' : item.status

const formatDay = (value?: string | null) => {
  if (!value) return '时间待定'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间待定'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', weekday: 'short' }).format(date)
}

const formatTime = (value?: string | null) => {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}

const RESERVATION_STATUS_LABELS: Record<string, string> = {
  PENDING_REVIEW: '待审核', APPROVED: '已确认', PENDING_PAYMENT: '待支付', PAID: '已支付',
  COMPLETED: '已完成', REJECTED: '未通过', CANCELED: '已取消', EXPIRED: '已过期',
  REFUNDING: '退款中', REFUNDED: '已退款'
}

const activityCards = computed(() => {
  const cards = [
    ...activities.value.map((item) => ({
      key: `a-${item.id}`, id: item.id, kind: 'event' as const,
      source: item.groupName || '社区活动', title: item.title,
      description: item.description ?? '', status: item.status,
      facts: `${formatDay(item.startTime)} ${formatTime(item.startTime)}-${formatTime(item.endTime)} · ${item.location?.areaCode ?? '地点待确认'}`
    })),
    ...companions.value.map((item) => ({
      key: `c-${item.id}`, id: item.id, kind: 'invitation' as const,
      source: item.groupName ? `${item.groupName}的同行邀约` : '同行邀约', title: item.title,
      description: item.description ?? '', status: item.status,
      facts: `${formatDay(item.windowStart)} ${formatTime(item.windowStart)}-${formatTime(item.windowEnd)} · ${item.location?.areaCode ?? '地点待确认'}`
    }))
  ]
  return cards
})

const goCard = (card: { id: number; kind: 'event' | 'invitation' }) => {
  router.push(card.kind === 'invitation' ? `/companion-requests/${card.id}` : `/activities/${card.id}`)
}

const updateRail = () => {
  const rail = railRef.value
  if (!rail) return
  const progress = rail.scrollWidth ? Math.min(1, (rail.scrollLeft + rail.clientWidth) / rail.scrollWidth) : 0
  railProgress.value = `${progress * 100}%`
  railAtStart.value = rail.scrollLeft <= 2
  railAtEnd.value = rail.scrollLeft + rail.clientWidth >= rail.scrollWidth - 2
  const cards = [...rail.querySelectorAll('.venue-card')]
  const railRect = rail.getBoundingClientRect()
  const visible = cards
    .map((card, index) => ({ rect: card.getBoundingClientRect(), index }))
    .filter(({ rect }) => Math.min(rect.right, railRect.right) - Math.max(rect.left, railRect.left) > rect.width * .5)
  const first = (visible[0]?.index ?? 0) + 1
  const end = (visible.at(-1)?.index ?? 0) + 1
  railPositionLabel.value = `${String(first).padStart(2, '0')} - ${String(end).padStart(2, '0')} / ${String(cards.length).padStart(2, '0')}`
}

const scrollRail = (direction: number) => {
  const rail = railRef.value
  if (!rail) return
  rail.scrollBy({ left: Math.max(rail.clientWidth * 0.82, 300) * direction, behavior: 'smooth' })
  window.setTimeout(updateRail, 360)
}

const onRailKeydown = (event: KeyboardEvent) => {
  if (event.key === 'ArrowRight') { event.preventDefault(); scrollRail(1) }
  if (event.key === 'ArrowLeft') { event.preventDefault(); scrollRail(-1) }
  if (event.key === 'Home') { event.preventDefault(); railRef.value?.scrollTo({ left: 0, behavior: 'smooth' }) }
  if (event.key === 'End') { event.preventDefault(); railRef.value?.scrollTo({ left: railRef.value?.scrollWidth ?? 0, behavior: 'smooth' }) }
}

onMounted(async () => {
  try {
    const page = await venueApi.list({ pageNum: 1, pageSize: 10, status: 'ACTIVE' })
    railVenues.value = page.records
    const ids = page.records.slice(0, 10).map((venue) => venue.id)
    if (ids.length) {
      const date = new Intl.DateTimeFormat('sv-SE').format(new Date())
      const summaryList = await reservationApi.availabilitySummary({ date, venueIds: ids })
      summaries.value = new Map(summaryList.map((summary) => [summary.venueId, summary]))
    }
  } catch {
    railVenues.value = []
  } finally {
    venuesLoading.value = false
  }
  try {
    const page = await meTimeApi.myTime({ pageNum: 1, pageSize: 5 })
    const records = page.records ?? []
    myTimeItems.value = [...records].sort((a, b) => Number(b.pending) - Number(a.pending) ||
      (a.startTime ?? '9999').localeCompare(b.startTime ?? '9999'))
  } catch {
    myTimeItems.value = []
  } finally {
    myTimeLoading.value = false
  }
  try {
    if (!canAccessCommunity.value) {
      // AR-03：无社区权限时附属内容直接省略请求，首页场馆与个人安排不受影响
      activities.value = []
      companions.value = []
    } else {
      const [activityPage, companionPage] = await Promise.all([
        activityApi.activities({ limit: 4 }, communityFallback).catch((): { records: CommunityActivity[] } => ({ records: [] })),
        activityApi.companions({ limit: 2 }, communityFallback).catch((): { records: CompanionRequest[] } => ({ records: [] }))
      ])
      activities.value = activityPage.records
      companions.value = companionPage.records
    }
  } catch {
    activities.value = []
    companions.value = []
  }
  try {
    if (!canAccessCommunity.value) {
      echo.value = null
    } else {
      const [first] = await communityApi.echo(1, communityFallback)
      echo.value = first ?? null
    }
  } catch {
    echo.value = null
  }
})
</script>
