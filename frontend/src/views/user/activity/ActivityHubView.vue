<template>
  <section class="activity-page page-stack sg-rise" data-route-focus>
    <header class="activity-head">
      <div>
        <p class="sg-kicker">活动与同伴</p>
        <h1 class="sg-display-xl">
          <span class="sg-mask-line"><span>好场地，</span></span>
          <span class="sg-mask-line"><span>也要好搭子。</span></span>
        </h1>
        <p>查找公开活动和结构化找搭子需求。</p>
      </div>
      <div class="activity-head-actions">
        <el-button @click="openCreate('COMPANION_REQUEST')"><el-icon><UserFilled /></el-icon>发布找搭子</el-button>
        <el-button type="primary" @click="openCreate('ACTIVITY')"><el-icon><Plus /></el-icon>发起活动</el-button>
      </div>
    </header>

    <section class="activity-discovery-filters" aria-label="活动筛选">
      <div class="sg-searchrow activity-searchrow" role="search">
        <label class="sg-search-pill">
          <el-icon><Search /></el-icon>
          <input
            v-model="filters.keyword"
            type="search"
            maxlength="80"
            :placeholder="searchPlaceholder"
            :aria-label="searchPlaceholder"
            @input="scheduleSearch"
            @keyup.enter="search"
          />
          <button v-if="filters.keyword" type="button" aria-label="清除搜索" title="清除搜索" @click="clearKeyword">
            <el-icon><Close /></el-icon>
          </button>
        </label>
        <span class="activity-result-count" aria-live="polite">当前显示 {{ visibleCount }} 个{{ hasMore ? '以上' : '' }}</span>
      </div>

      <SgFilterIndex
        :dimensions="railDimensions"
        :selection="railSelection"
        class="activity-indexrail"
        aria-label="活动筛选维度"
        @update:selection="updateRailSelection"
      >
        <template #panel-time>
          <div v-if="timeOption === 'custom'" class="activity-time-panel">
            <el-date-picker
              v-model="filters.timeRange"
              type="datetimerange"
              value-format="YYYY-MM-DDTHH:mm:ss"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              @change="onCustomTimeChange"
            />
            <button v-if="filters.timeRange.length" type="button" class="sg-chip" @click="clearTimeFilter">清除时间</button>
          </div>
        </template>
      </SgFilterIndex>

      <details v-if="activeTab !== 'activities'" class="sg-details activity-advanced-filters">
        <summary>更多条件</summary>
        <div class="activity-advanced-fields">
          <label>
            <span class="filter-label">校区区域</span>
            <el-input v-model.trim="filters.areaCode" maxlength="64" clearable placeholder="区域代码" @input="changeAreaFilter" />
          </label>
        </div>
      </details>
    </section>

    <el-tabs v-model="activeTab" @tab-change="changeTab">
      <el-tab-pane label="社区活动" name="activities">
        <el-skeleton v-if="loading && !activities.length" animated :rows="6" />
        <div v-else-if="activities.length" class="activity-list sg-stagger--loose" aria-live="polite">
          <router-link v-for="item in activities" :key="item.id" class="activity-card sg-lift" :data-wash="activityWashOf(item.activityType)" :to="`/activities/${item.id}`">
            <span class="activity-card-poster" aria-hidden="true">
              <VenueTypeMotif :motif="activityMotifOf(item.activityType)" class="activity-card-motif" />
              <span class="activity-count-badge"><strong class="sg-num">{{ item.participantCount }}</strong><span>/ {{ item.capacityTotal }} 人</span></span>
            </span>
            <div class="activity-card-head">
              <div><span class="page-kicker">{{ activityTypeLabel(item.activityType) }}</span><h2>{{ item.title }}</h2></div>
              <span :class="activityStatusToneClass(item.status)">{{ activityStatusLabel(item.status) }}</span>
            </div>
            <p class="activity-card-body">{{ item.description || '暂无活动说明' }}</p>
            <div class="activity-meta">
              <span><el-icon><Clock /></el-icon>{{ formatActivityTime(item.startTime, item.endTime) }}</span>
              <span><el-icon><Location /></el-icon>{{ locationLabel(item.location) }}</span>
            </div>
            <div class="activity-actions">
              <span class="sg-tag" :class="locationChip(item.location).type === 'danger' ? 'sg-tag--danger' : 'sg-tag--neutral'">{{ locationChip(item.location).label }}</span>
              <span class="sg-link">查看活动 →</span>
            </div>
          </router-link>
        </div>
        <EmptyState v-else-if="!loading && !error" title="暂无符合条件的活动" description="调整活动类型、时间或场馆筛选条件。">
          <template #action><button type="button" class="sg-btn-capsule" @click="resetFilters">清除筛选</button></template>
        </EmptyState>
      </el-tab-pane>

      <el-tab-pane label="找搭子" name="companions">
        <el-skeleton v-if="loading && !companions.length" animated :rows="6" />
        <div v-else-if="companions.length" class="activity-list sg-stagger--loose" aria-live="polite">
          <router-link v-for="item in companions" :key="item.id" class="activity-card sg-lift" :to="`/companion-requests/${item.id}`">
            <div class="activity-card-head">
              <div><span class="page-kicker">{{ item.activityId ? '活动关联需求' : activityTypeLabel(item.activityType) }}</span><h2>{{ item.title }}</h2></div>
              <span :class="activityStatusToneClass(item.status)">{{ activityStatusLabel(item.status) }}</span>
            </div>
            <p class="activity-card-body">{{ item.description || '暂无需求说明' }}</p>
            <div class="activity-meta">
              <span><el-icon><Clock /></el-icon>{{ formatActivityTime(item.windowStart, item.windowEnd) }}</span>
              <span><el-icon><Location /></el-icon>{{ locationLabel(item.location) }}</span>
              <span class="activity-count-wrap"><el-icon><User /></el-icon><strong class="activity-count sg-display-m sg-num">{{ item.participantCount }}</strong><span class="activity-count-total">/ {{ item.capacityTotal }} 人</span></span>
            </div>
            <div class="activity-actions">
              <span class="sg-tag" :class="locationChip(item.location).type === 'danger' ? 'sg-tag--danger' : 'sg-tag--neutral'">{{ locationChip(item.location).label }}</span>
              <span class="sg-link">查看需求 →</span>
            </div>
          </router-link>
        </div>
        <EmptyState v-else-if="!loading && !error" title="暂无符合条件的找搭子需求" description="换个时间窗口或活动类型，看看有没有合适的搭子。">
          <template #action><button type="button" class="sg-btn-capsule" @click="resetFilters">清除筛选</button></template>
        </EmptyState>
      </el-tab-pane>
    </el-tabs>

    <div v-if="error" class="sg-state-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="retry" @click="search">重新加载</button>
    </div>
    <el-button v-if="hasMore" class="activity-load-more" :loading="loadingMore" @click="loadMore">加载更多</el-button>

    <!-- AR-02：创建草稿弹窗与拾光活动列表共用同一份实现（载荷、幂等 registry 与成员校验只此一处） -->
    <ActivityCreateDialog ref="createDialog" @closed="clearCreateQuery" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import EmptyState from '@/components/EmptyState.vue'
import VenueTypeMotif from '@/components/VenueTypeMotif.vue'
import SgFilterIndex from '@/components/SgFilterIndex.vue'
import ActivityCreateDialog, { type CreateKind } from './ActivityCreateDialog.vue'
import { useRoute, useRouter } from 'vue-router'
import { activityApi, type ActivityType, type CommunityActivity, type CompanionRequest } from '@/api/activity'
import { communityError } from '@/api/community'
import type { ActivityLocation } from '@/api/activity'
import type { Venue } from '@/api/types'
import { venueApi } from '@/api/venue'
import type { VenueMotif } from '@/utils/venueVisual'
import { fetchSpaceName, fetchVenueName } from '@/utils/entityNames'
import {
  activityStatusLabel,
  activityStatusToneClass,
  activityTypeLabel,
  fallbackLocationLabel,
  formatActivityTime
} from '@/utils/activityPresentation'

const route = useRoute()
const router = useRouter()
const activeTab = ref<'activities' | 'companions'>('activities')
const activities = ref<CommunityActivity[]>([])
const companions = ref<CompanionRequest[]>([])
const cursor = reactive<{ activities?: number; companions?: number }>({})
const more = reactive({ activities: false, companions: false })
const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const venues = ref<Venue[]>([])
/** AR-02：创建草稿由共用弹窗组件承载，本页只保留入口与深链消费。 */
const createDialog = ref<InstanceType<typeof ActivityCreateDialog> | null>(null)
let searchTimer: ReturnType<typeof setTimeout> | undefined
const filters = reactive({
  keyword: '',
  activityType: '' as ActivityType | '',
  timeRange: [] as string[],
  venueId: undefined as number | undefined,
  areaCode: ''
})

const activityTypeOptions: Array<{ value: ActivityType; label: string }> = [
  { value: 'STUDY', label: '学习自习' },
  { value: 'SPORTS', label: '运动健身' },
  { value: 'BOARD_GAME', label: '桌游娱乐' },
  { value: 'WORKSHOP', label: '主题工作坊' },
  { value: 'OTHER', label: '其他活动' }
]

/* 活动卡排印海报窄条（预览 §08）：按类型定 wash 与母题——运动松绿球场、学习蜜金书窗、桌游/工作坊雾蓝 */
type ActivityWash = 'mist' | 'pine' | 'honey'
const ACTIVITY_WASH: Record<ActivityType, ActivityWash> = {
  STUDY: 'honey',
  SPORTS: 'pine',
  BOARD_GAME: 'mist',
  WORKSHOP: 'mist',
  OTHER: 'honey'
}
const ACTIVITY_MOTIF: Record<ActivityType, VenueMotif> = {
  STUDY: 'study',
  SPORTS: 'sport',
  BOARD_GAME: 'house',
  WORKSHOP: 'house',
  OTHER: 'house'
}
const activityWashOf = (type: ActivityType) => ACTIVITY_WASH[type] || 'honey'
const activityMotifOf = (type: ActivityType) => ACTIVITY_MOTIF[type] || 'house'
const activityFilterOptions: Array<{ value: ActivityType | ''; label: string }> = [
  { value: '', label: '全部' },
  ...activityTypeOptions
]
const hasMore = computed(() => more[activeTab.value])
const visibleCount = computed(() => activeTab.value === 'activities' ? activities.value.length : companions.value.length)
const searchPlaceholder = computed(() => activeTab.value === 'activities' ? '搜索活动标题或说明…' : '搜索找搭子标题或说明…')

/* V11 筛选索引栏（spec §4.1）：类型/场馆直映既有 filters（口径不变）；时间维度为预设选择器，
   今天/本周/本月本地换算为既有 fromTime/toTime，「自定义…」展开既有 datetimerange 面板。 */
const TIME_PRESET_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '', label: '时间不限' },
  { value: 'today', label: '今天' },
  { value: 'week', label: '本周' },
  { value: 'month', label: '本月' },
  { value: 'custom', label: '自定义…' }
]
const timeOption = ref('')
const pad2 = (value: number) => String(value).padStart(2, '0')
const formatBound = (date: Date) =>
  `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}T${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`
const startOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate(), 0, 0, 0)
const endOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59)
const railDimensions = computed(() => [
  {
    key: 'activityType',
    label: '类型',
    options: activityFilterOptions.map((option) => ({ value: option.value as string, label: option.label }))
  },
  { key: 'time', label: '时间', options: TIME_PRESET_OPTIONS },
  {
    key: 'venue',
    label: '场馆',
    options: [{ value: '', label: '全部场馆' }, ...venues.value.map((venue) => ({ value: String(venue.id), label: venue.name }))]
  }
])
const venueSelectionValue = () => (filters.venueId === undefined ? '' : String(filters.venueId))
const railSelection = computed(() => ({
  activityType: filters.activityType as string,
  time: timeOption.value,
  venue: venueSelectionValue()
}))
const updateRailSelection = (value: Record<string, string>) => {
  if (value.activityType !== filters.activityType) {
    filters.activityType = value.activityType as ActivityType | ''
    applyFilters()
  }
  if (value.time !== timeOption.value) {
    timeOption.value = value.time
    applyTimePreset(value.time)
  }
  if (value.venue !== venueSelectionValue()) {
    filters.venueId = value.venue === '' ? undefined : Number(value.venue)
    applyFilters()
  }
}
const applyTimePreset = (option: string) => {
  if (option === 'custom') {
    /* 自定义：保留当前范围，等待日期面板回填（onCustomTimeChange） */
    applyFilters()
    return
  }
  if (!option) {
    filters.timeRange = []
    applyFilters()
    return
  }
  const now = new Date()
  let from: Date
  let to: Date
  if (option === 'today') {
    from = startOfDay(now)
    to = endOfDay(now)
  } else if (option === 'week') {
    const weekday = now.getDay() === 0 ? 7 : now.getDay()
    from = startOfDay(new Date(now.getFullYear(), now.getMonth(), now.getDate() - (weekday - 1)))
    to = endOfDay(new Date(from.getFullYear(), from.getMonth(), from.getDate() + 6))
  } else {
    from = startOfDay(new Date(now.getFullYear(), now.getMonth(), 1))
    to = endOfDay(new Date(now.getFullYear(), now.getMonth() + 1, 0))
  }
  filters.timeRange = [formatBound(from), formatBound(to)]
  applyFilters()
}
const onCustomTimeChange = () => {
  timeOption.value = 'custom'
  applyFilters()
}

const queryParams = () => ({
  keyword: filters.keyword.trim() || undefined,
  activityType: filters.activityType || undefined,
  fromTime: filters.timeRange[0] || undefined,
  toTime: filters.timeRange[1] || undefined,
  venueId: filters.venueId,
  areaCode: filters.areaCode.trim().toUpperCase() || undefined,
  limit: 20
})

const loadList = async (append = false) => {
  append ? loadingMore.value = true : loading.value = true
  error.value = ''
  try {
    const key = activeTab.value
    const params = { ...queryParams(), cursorId: append ? cursor[key] : undefined }
    const page = key === 'activities'
      ? await activityApi.activities(params)
      : await activityApi.companions(params)
    if (key === 'activities') activities.value = append ? [...activities.value, ...page.records as CommunityActivity[]] : page.records as CommunityActivity[]
    else companions.value = append ? [...companions.value, ...page.records as CompanionRequest[]] : page.records as CompanionRequest[]
    void ensureLocationNames(page.records.map((record) => record.location))
    cursor[key] = page.nextCursor ?? undefined
    more[key] = page.hasMore
  } catch (caught) {
    error.value = communityError(caught, '活动内容加载失败，请稍后重试').message
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

const clearSearchTimer = () => {
  if (!searchTimer) return
  clearTimeout(searchTimer)
  searchTimer = undefined
}
const search = () => {
  clearSearchTimer()
  return loadList(false)
}
const scheduleSearch = () => {
  clearSearchTimer()
  searchTimer = setTimeout(() => {
    searchTimer = undefined
    void loadList(false)
  }, 300)
}
const applyFilters = () => {
  clearSearchTimer()
  void loadList(false)
}
const clearKeyword = () => {
  if (!filters.keyword) return
  filters.keyword = ''
  applyFilters()
}
const clearTimeFilter = () => {
  if (!filters.timeRange.length && !timeOption.value) return
  timeOption.value = ''
  filters.timeRange = []
  applyFilters()
}
const loadMore = () => loadList(true)
const resetFilters = () => {
  clearSearchTimer()
  timeOption.value = ''
  Object.assign(filters, { keyword: '', activityType: '', timeRange: [], venueId: undefined, areaCode: '' })
  void search()
}
const changeTab = async (name: string | number) => {
  activeTab.value = name === 'companions' ? 'companions' : 'activities'
  error.value = ''
  await loadList()
}

/** 索引栏「场馆」维度仍取本页数据；创建弹窗的小组/预约/场馆选项由共用件自行加载。 */
const loadReferenceData = async () => {
  try {
    const page = await venueApi.list({ pageNum: 1, pageSize: 100 })
    venues.value = page.records
  } catch {
    venues.value = []
  }
}

/** 入口与深链统一走共用弹窗：创建载荷、成员校验与幂等 registry 只此一份。 */
const openCreate = (kind: CreateKind, linked: CommunityActivity | null = null) => {
  void createDialog.value?.open(kind, linked)
}

const locationLabel = (location: ActivityLocation) => {
  if (location.type === 'CAMPUS_AREA' || !location.venueId) return fallbackLocationLabel(location)
  const venue = venueNames.value.get(location.venueId) || '场馆'
  const space = location.spaceId ? spaceNames.value.get(location.spaceId) : undefined
  return space ? `${venue} · ${space}` : venue
}
const venueNames = ref(new Map<number, string>())
const spaceNames = ref(new Map<number, string>())
const ensureLocationNames = async (locations: ActivityLocation[]) => {
  await Promise.all(locations.map(async (location) => {
    if (location.type !== 'VENUE_SPACE' || !location.venueId) return
    if (!venueNames.value.has(location.venueId)) {
      const name = await fetchVenueName(location.venueId)
      if (name) venueNames.value.set(location.venueId, name)
    }
    if (location.spaceId && !spaceNames.value.has(location.spaceId)) {
      const name = await fetchSpaceName(location.venueId, location.spaceId)
      if (name) spaceNames.value.set(location.spaceId, name)
    }
  }))
}
/** 地点章按类型定语义：绑定了场馆空间即「地点已定」；区域粗定位或未确认才是「地点待确认」；原预约失效单独红章。 */
const locationChip = (location: ActivityLocation): { label: string; type: 'info' | 'danger' } => {
  if (location.commitmentStatus === 'DEGRADED') return { label: '原预约已失效', type: 'danger' }
  if (location.type === 'VENUE_SPACE' && location.commitmentStatus === 'CONFIRMED') return { label: '地点已定', type: 'info' }
  return { label: '地点待确认', type: 'danger' }
}
const changeAreaFilter = () => {
  filters.venueId = undefined
  scheduleSearch()
}
/** 深链 `?create=companion&activityId=…` 消费后清掉 query，避免刷新重复开窗。 */
const clearCreateQuery = () => {
  if (route.query.create) void router.replace('/activities')
}

onMounted(async () => {
  await Promise.all([loadList(), loadReferenceData()])
  const activityId = Number(route.query.activityId)
  if (route.query.create === 'companion' && Number.isInteger(activityId) && activityId > 0) {
    try {
      openCreate('COMPANION_REQUEST', await activityApi.activity(activityId))
    } catch (caught) {
      error.value = communityError(caught, '关联活动无法加载').message
    }
  }
})
onUnmounted(clearSearchTimer)
</script>

<style scoped src="./activity.css"></style>
