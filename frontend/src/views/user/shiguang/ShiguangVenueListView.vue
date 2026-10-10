<template>
  <section class="home-shell browse-view" aria-labelledby="sgv-home-title">
    <div class="browse-heading">
      <p id="sgv-browse-summary" role="status">{{ summary }}</p>
    </div>
    <div v-if="loading && !venues.length" class="empty-results" role="status">正在读取场馆…</div>
    <div v-else-if="loadError && !venues.length" class="empty-results" role="alert">
      <h3>场馆暂时读不出来。</h3>
      <p>{{ loadError }}</p>
      <button class="text-button" type="button" @click="loadPage">重新加载</button>
    </div>
    <div v-else-if="filteredVenues.length" class="browse-venues">
      <article v-for="venue in filteredVenues" :key="venue.id" class="venue-card">
        <button class="venue-art" type="button" :aria-label="`查看 ${venue.name}`" @click="goVenue(venue.id)">
          <ShiguangArt :scene="mapVenueScene(venue.spaceTypes)" />
        </button>
        <div class="venue-title-row">
          <button type="button" @click="goVenue(venue.id)">{{ venue.name }}</button>
          <span class="price">¥{{ venue.minPrice ?? '—' }}<small>/ 小时</small></span>
        </div>
        <p class="venue-location">{{ venue.area || venue.address }}</p>
        <div class="venue-bottom">
          <button class="time-chip" type="button" @click="goVenue(venue.id)">
            {{ availabilityText(venue.id) }} <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
          </button>
        </div>
      </article>
    </div>
    <div v-else class="empty-results">
      <h3>暂时没有合适的内容。</h3>
      <p>换个区域或关键词，给下一次出门多一点可能。</p>
      <button class="text-button" type="button" @click="resetFilters">重新看看全部场馆 <svg aria-hidden="true"><use href="#sgv-arrow" /></svg></button>
    </div>
    <div v-if="loadError && venues.length" class="empty-results" role="alert">
      <p>{{ loadError }}已读取的场馆仍可查看。</p>
      <button class="text-button" type="button" @click="loadPage">重试这一页</button>
    </div>
    <button v-if="hasMore && !loadError" class="text-button" type="button" :disabled="loading" @click="loadPage">{{ loading ? '正在加载…' : '加载更多场馆' }} <svg aria-hidden="true"><use href="#sgv-arrow" /></svg></button>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { venueApi } from '@/api/venue'
import { reservationApi } from '@/api/reservation'
import type { VenueListItem, VenueAvailabilitySummary } from '@/api/types'
import { useShiguangStore } from '@/stores/shiguang'
import { useFunnelStore } from '@/stores/funnel'

import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import { mapVenueScene, categoryMatchesSpaceTypes } from '@/utils/shiguangScene'

const store = useShiguangStore()
const funnel = useFunnelStore()
const venues = ref<VenueListItem[]>([])
const summaries = ref<Map<number, VenueAvailabilitySummary>>(new Map())
const pageNum = ref(0)
const total = ref<number | null>(null)
const loading = ref(false)
const loadError = ref('')
let queryGeneration = 0
let summaryGeneration = 0
let disposed = false
const PAGE_SIZE = 20

const hasMore = computed(() => total.value !== null && venues.value.length < total.value)
const filteredVenues = computed(() =>
  venues.value.filter((venue) => categoryMatchesSpaceTypes(store.category, venue.spaceTypes))
)
const dayMark = () => dateText().slice(5).replace('-', '.')
const summary = computed(() => total.value === null
  ? (loading.value ? '正在读取场馆…' : '场馆数量暂时未知')
  : `${filteredVenues.value.length} 处场馆 · ${store.day ? '明天' : '今天'} ${dayMark()}`)

const dateText = () => {
  const date = new Date()
  date.setDate(date.getDate() + store.day)
  return new Intl.DateTimeFormat('sv-SE').format(date)
}

// S05 裁决变更：卡片点击打开速览侧栏，完整详情由侧栏 CTA 进入
const goVenue = (id: number) => store.openVenueDrawer(id)

const dayText = () => (store.day ? '明天' : '今天')

const availabilityText = (venueId: number) => {
  const summaryItem = summaries.value.get(venueId)
  if (!summaryItem) return dayText()
  if (!summaryItem.available || !summaryItem.startTime) return `${dayText()}暂无可约`
  return `${dayText()} ${summaryItem.startTime.slice(11, 16)} 可约`
}

// 摘要属于当前查询与日期；失败仅降级卡片，不改变主列表状态。
const loadSummaries = async (ids: number[]) => {
  const generation = summaryGeneration
  const date = dateText()
  try {
    const list = await reservationApi.availabilitySummary({ date, venueIds: ids }, {
      skipGlobalErrorRedirect: true, skipGlobalErrorToast: true
    })
    if (disposed || generation !== summaryGeneration || date !== dateText()) return
    for (const item of list) summaries.value.set(item.venueId, item)
  } catch {
    // 可选摘要缺失时卡片保留日期入口。
  }
}

watch(() => store.day, () => {
  summaryGeneration += 1
  summaries.value = new Map()
  // 接口每次最多 25 个 ID，保留已加载分页的全部摘要。
  for (let offset = 0; offset < venues.value.length; offset += 25) {
    void loadSummaries(venues.value.slice(offset, offset + 25).map(venue => venue.id))
  }
})

const failureMessage = (error: unknown) => {
  const status = (error as { response?: { status?: number } })?.response?.status
  if (status === 429) return '请求有些频繁，请稍后重试。'
  if (status === 401) return '登录已失效，请重新登录后再试。'
  if (status === 403) return '当前身份无法读取场馆。'
  if (status && status >= 500) return '服务暂时不可用，请稍后重试。'
  return '暂时无法连接服务，请检查网络后重试。'
}

const loadPage = async () => {
  if (loading.value || disposed) return
  const generation = queryGeneration
  const requestedPage = pageNum.value + 1
  const keyword = store.query.trim()
  const area = store.area
  loading.value = true
  loadError.value = ''
  try {
    const params: Record<string, unknown> = { pageNum: requestedPage, pageSize: PAGE_SIZE, status: 'ACTIVE' }
    if (keyword) params.keyword = keyword
    if (area !== 'all') params.area = area
    const page = await venueApi.list(params)
    if (disposed || generation !== queryGeneration) return
    const existing = new Set(venues.value.map(venue => venue.id))
    const fresh = page.records.filter(venue => !existing.has(venue.id))
    venues.value.push(...fresh)
    total.value = page.total
    pageNum.value = requestedPage
    void funnel.track({
      eventName: 'SEARCH_RESULT_VIEWED', eventStage: 'SEARCH', keyword, pagePath: '/venues',
      metadata: { resultCount: page.records.length, total: page.total }
    }).catch(() => undefined)
    if (fresh.length) void loadSummaries(fresh.map(venue => venue.id))
  } catch (error) {
    if (!disposed && generation === queryGeneration) loadError.value = failureMessage(error)
  } finally {
    if (!disposed && generation === queryGeneration) loading.value = false
  }
}

const reload = () => {
  queryGeneration += 1
  summaryGeneration += 1
  venues.value = []
  summaries.value = new Map()
  pageNum.value = 0
  total.value = null
  loading.value = false
  void loadPage()
}

// 类别按既有合同在已读场馆上过滤；关键词/区域是服务端查询条件。
watch(() => [store.query.trim(), store.area], reload, { immediate: true })
const resetFilters = () => store.clearFilters()
onBeforeUnmount(() => {
  disposed = true
  queryGeneration += 1
  summaryGeneration += 1
})
</script>
