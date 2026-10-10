<template>
  <div class="sgv-activity">
    <div class="shell page-arrive activity-directory">
      <div class="page-heading directory-heading">
        <div>
          <h1>活动与同行<span class="dot-stop">.</span></h1>
          <p>把空闲的时间，留给想做的事。</p>
        </div>
        <div class="heading-actions">
          <router-link class="text-button" data-test="owned-time" to="/me/time?tab=owned">我的发起 <span class="icon"><el-icon><TopRight /></el-icon></span></router-link>
          <button class="button primary" data-test="create-activity" type="button" @click="openCreate('ACTIVITY')">
            <span class="icon"><el-icon><Plus /></el-icon></span>发起一场
          </button>
        </div>
      </div>

      <div class="directory-layout">
        <aside class="filter-rail" aria-label="活动筛选">
          <div class="filter-title">
            <h2>筛选</h2>
            <button
              class="icon-button small"
              type="button"
              aria-label="重置筛选"
              title="重置筛选"
              :disabled="filterCount === 0"
              data-test="reset-filters"
              @click="resetFilters"
            ><span class="icon"><el-icon><Refresh /></el-icon></span></button>
          </div>
          <div class="filter-fields">
            <label class="filter-field">
              <span>时间</span>
              <select v-model="filters.when" aria-label="活动时间" @change="reload">
                <option value="all">不限日期</option>
                <option value="weekend">这个周末</option>
                <option value="week">未来一周</option>
              </select>
            </label>
            <label class="filter-field">
              <span>区域</span>
              <select v-model="filters.areaCode" aria-label="活动区域" @change="reload">
                <option value="">全部区域</option>
                <option v-for="area in AREA_OPTIONS" :key="area.value" :value="area.value">{{ area.label }}</option>
              </select>
            </label>
            <fieldset class="interest-filter">
              <legend>想做点什么</legend>
              <label v-for="type in INTEREST_TYPES" :key="type.value" class="filter-check">
                <input v-model="filters.activityTypes" type="checkbox" :value="type.value" @change="reload">
                <span>{{ type.label }}</span>
              </label>
            </fieldset>
            <label class="availability-filter">
              <span>只看仍有名额</span>
              <input v-model="filters.onlyAvailable" type="checkbox" role="switch" @change="reload">
            </label>
          </div>
          <router-link class="rail-time-link" to="/me/time">
            <span class="icon"><el-icon><Calendar /></el-icon></span>我的时间<span class="icon"><el-icon><TopRight /></el-icon></span>
          </router-link>
        </aside>

        <section class="directory-results" aria-label="活动与邀约列表">
          <div class="directory-toolbar">
            <div class="tabs" role="tablist" aria-label="活动类型" @keydown="onTabKeydown">
              <button
                v-for="tab in TABS"
                :key="tab.value"
                role="tab"
                type="button"
                :aria-selected="kind === tab.value ? 'true' : 'false'"
                :data-test="`kind-${tab.value}`"
                @click="setKind(tab.value)"
              >{{ tab.label }}<span class="count">{{ tabCount(tab.value) }}</span></button>
            </div>
            <form class="search-form" role="search" @submit.prevent="submitSearch">
              <label class="sr-only" for="directory-search-input">搜索活动或邀约</label>
              <input
                id="directory-search-input"
                v-model="searchInput"
                type="search"
                placeholder="搜索活动、邀约或小组"
                @submit="reload"
              >
              <button type="submit" aria-label="搜索" title="搜索"><span class="icon"><el-icon><Search /></el-icon></span></button>
            </form>
            <button
              class="icon-button mobile-filter-button"
              type="button"
              aria-label="筛选活动"
              title="筛选活动"
              data-test="mobile-filters"
              @click="openFilterDialog"
            ><span class="icon"><el-icon><Operation /></el-icon></span><span v-if="filterCount" class="filter-count">{{ filterCount }}</span></button>
          </div>

          <div class="results-heading">
            <p><span v-if="keyword">「{{ keyword }}」 · </span><strong>{{ total }}</strong> 场{{ kind === 'ACTIVITY' ? '城市活动' : kind === 'COMPANION' ? '同行邀约' : '活动与邀约' }}<span v-if="filterCount" class="active-filter-note"> · {{ filterCount }} 项筛选</span></p>
            <span>按开始时间排序</span>
          </div>

          <div v-if="loading && !records.length" class="empty-state" role="status" data-test="loading">
            <div class="empty-art"><ShiguangArt scene="quiet" /></div>
            <div>
              <h2>正在找下一场相遇。</h2>
              <p>活动与邀约正在加载。</p>
            </div>
          </div>
          <div v-else-if="loadError && !records.length" class="empty-state" role="alert" data-test="load-error">
            <div class="empty-art"><ShiguangArt scene="together" /></div>
            <div>
              <h2>这一页，暂时没能读出来。</h2>
              <p>{{ loadError }}你的筛选条件还在，请稍后重试。</p>
              <button class="button" type="button" data-test="retry-load" @click="reload">
                <span class="icon"><el-icon><Refresh /></el-icon></span>重新加载
              </button>
            </div>
          </div>
          <div v-else-if="!records.length" class="empty-state" role="status" data-test="empty">
            <div class="empty-art"><ShiguangArt scene="quiet" /></div>
            <div>
              <h2>还没找到这一场相遇。</h2>
              <p>试试别的时间或区域，也可以发起自己的邀约。</p>
              <button class="button" type="button" data-test="clear-filters" @click="resetAll">
                <span class="icon"><el-icon><Back /></el-icon></span>清除筛选
              </button>
            </div>
          </div>

          <template v-else>
            <section
              v-for="group in dayGroups"
              :key="group.date"
              class="activity-day"
              :aria-label="dayLabel(group.date)"
            >
              <div class="day-heading">
                <h2>{{ dayLabel(group.date) }}<span>{{ weekdayLabel(group.date) }}</span><em v-if="isTomorrow(group.date)">明天</em></h2>
                <span>{{ dayCount(group.date) }} 场</span>
              </div>
              <article v-for="item in group.items" :key="directoryKey(item)" class="activity-row">
                <div class="row-time">
                  <time :datetime="item.startTime">{{ timeText(item.startTime) }}</time>
                  <span>至 {{ timeText(item.endTime) }}</span>
                </div>
                <router-link class="row-art" :to="detailRoute(item)" :aria-label="`查看${item.title}`">
                  <ShiguangArt :scene="sceneFor(item)" />
                </router-link>
                <div class="row-content">
                  <p class="row-source">
                    <span :class="{ 'companion-source': item.sourceType === 'COMPANION' }">{{ kindText(item) }}</span>
                    <span>{{ item.groupName || '小组待补充' }}</span>
                  </p>
                  <h3><router-link :to="detailRoute(item)">{{ item.title }}</router-link></h3>
                  <p class="row-intro">{{ item.description || '这场相聚的介绍还在路上。' }}</p>
                  <div class="row-location">
                    <span class="icon"><el-icon><Location /></el-icon></span>
                    <span>{{ locationText(item) }}</span>
                    <span v-if="item.commitmentStatus !== 'CONFIRMED'" class="location-pending">地点待确认</span>
                  </div>
                </div>
                <div class="row-action">
                  <span class="status" :class="rowStatusTone(item)">{{ rowStatusLabel(item) }}</span>
                  <span class="row-capacity"><strong>{{ item.participantCount }}</strong><span> / {{ item.capacityTotal }} 人{{ item.sourceType === 'COMPANION' ? '同行' : '已加入' }}</span></span>
                  <router-link class="text-button" :to="detailRoute(item)">{{ item.sourceType === 'COMPANION' ? '查看邀约' : '查看活动' }}<span class="icon"><el-icon><ArrowRight /></el-icon></span></router-link>
                </div>
              </article>
            </section>

            <div class="directory-more">
              <button v-if="hasMore" class="text-button" type="button" data-test="load-more" :disabled="loadingMore" @click="loadMore">
                {{ loadingMore ? '正在加载…' : `继续查看，还有 ${remaining} 场` }}<span class="icon"><el-icon><ArrowDown /></el-icon></span>
              </button>
              <span v-else>已经看到全部活动</span>
            </div>
          </template>
        </section>
      </div>
    </div>

    <el-dialog v-model="filterDialogOpen" title="筛选活动" width="470px" @closed="onFilterDialogClosed">
      <div class="sgv-activity">
        <div class="filter-fields">
          <label class="filter-field">
            <span>时间</span>
            <select v-model="draftFilters.when" aria-label="活动时间">
              <option value="all">不限日期</option>
              <option value="weekend">这个周末</option>
              <option value="week">未来一周</option>
            </select>
          </label>
          <label class="filter-field">
            <span>区域</span>
            <select v-model="draftFilters.areaCode" aria-label="活动区域">
              <option value="">全部区域</option>
              <option v-for="area in AREA_OPTIONS" :key="area.value" :value="area.value">{{ area.label }}</option>
            </select>
          </label>
          <fieldset class="interest-filter">
            <legend>想做点什么</legend>
            <label v-for="type in INTEREST_TYPES" :key="type.value" class="filter-check">
              <input v-model="draftFilters.activityTypes" type="checkbox" :value="type.value">
              <span>{{ type.label }}</span>
            </label>
          </fieldset>
          <label class="availability-filter">
            <span>只看仍有名额</span>
            <input v-model="draftFilters.onlyAvailable" type="checkbox" role="switch">
          </label>
        </div>
        <div class="filter-dialog-actions">
          <button class="button" type="button" @click="resetDraftFilters">重置</button>
          <button class="button primary" type="button" @click="applyDraftFilters">查看 {{ total }} 场活动</button>
        </div>
      </div>
    </el-dialog>

    <ActivityCreateDialog ref="createDialog" @closed="clearCreateQuery" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { directoryApi, directoryKey, groupDirectoryByDate, isHostedByViewer,
  type DirectoryItem, type DirectorySourceType } from '@/api/directory'
import { activityApi } from '@/api/activity'
import { campusAreaLabel } from '@/utils/activityPresentation'
import { communityError } from '@/api/community'
import type { ShiguangScene } from '@/components/shiguang/types'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import ActivityCreateDialog, { type CreateKind } from '@/views/user/activity/ActivityCreateDialog.vue'
import { useShiguangStore } from '@/stores/shiguang'

const TABS = [
  { value: 'all', label: '全部' },
  { value: 'ACTIVITY', label: '城市活动' },
  { value: 'COMPANION', label: '同行邀约' }
] as const

const INTEREST_TYPES = [
  { value: 'SPORTS', label: '运动' },
  { value: 'STUDY', label: '阅读自习' },
  { value: 'BOARD_GAME', label: '桌游' },
  { value: 'WORKSHOP', label: '手作' },
  { value: 'OTHER', label: '其他' }
] as const

const AREA_OPTIONS = [
  { value: 'NORTH_CAMPUS', label: '北校区' },
  { value: 'SOUTH_CAMPUS', label: '南校区' },
  { value: 'MAIN_CAMPUS', label: '主校区' }
] as const

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '开放申请', FULL: '名额已满',
  CLOSED: '报名已关闭', CANCELED: '已取消', EXPIRED: '已结束'
}
const CLOSED_STATUSES = ['CANCELED', 'CLOSED', 'EXPIRED', 'FULL']

const route = useRoute()
const router = useRouter()
const store = useShiguangStore()
const createDialog = ref<InstanceType<typeof ActivityCreateDialog> | null>(null)

const kind = ref<'all' | DirectorySourceType>('all')
const searchInput = ref('')
const keyword = ref('')
const filters = reactive({
  when: 'all',
  areaCode: '',
  activityTypes: [] as string[],
  onlyAvailable: false
})
const draftFilters = reactive({ ...filters, activityTypes: [] as string[] })
const filterDialogOpen = ref(false)

const records = ref<DirectoryItem[]>([])
const cursor = ref<string | null>(null)
const hasMore = ref(false)
const total = ref(0)
const typeCounts = ref<Partial<Record<DirectorySourceType, number>>>({})
const dateCounts = ref<Array<{ date: string; count: number }>>([])
const loading = ref(false)
const loadingMore = ref(false)
const loadError = ref('')
let generation = 0

const filterCount = computed(() =>
  Number(filters.when !== 'all') + Number(filters.areaCode !== '')
  + filters.activityTypes.length + Number(filters.onlyAvailable))

const dayGroups = computed(() => groupDirectoryByDate(records.value))
const remaining = computed(() => Math.max(0, total.value - records.value.length))

const tabCount = (value: 'all' | DirectorySourceType) =>
  value === 'all' ? total.value : typeCounts.value[value] ?? 0

const queryParams = (withCursor: boolean) => ({
  keyword: keyword.value || undefined,
  types: kind.value === 'all' ? undefined : [kind.value],
  activityTypes: filters.activityTypes.length ? filters.activityTypes.join(',') : undefined,
  areaCode: filters.areaCode || undefined,
  onlyAvailable: filters.onlyAvailable || undefined,
  ...whenWindow(filters.when),
  cursor: withCursor ? cursor.value ?? undefined : undefined,
  limit: 12
})

/** 时间预设按动态今天换算为绝对窗口（本地业务时区），服务端只校验边界。 */
const whenWindow = (preset: string) => {
  if (preset === 'all') return {}
  const now = new Date()
  const end = new Date(now)
  if (preset === 'weekend') {
    const day = now.getDay()
    const untilSunday = day === 0 ? 0 : 7 - day
    end.setDate(now.getDate() + untilSunday)
    end.setHours(23, 59, 59, 0)
  } else {
    end.setDate(now.getDate() + 7)
    end.setHours(23, 59, 59, 0)
  }
  return { toTime: toLocalIso(end) }
}

const toLocalIso = (date: Date) => {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

const load = async (append: boolean, gen: number) => {
  try {
    const page = await directoryApi.directory(queryParams(append))
    if (gen !== generation) return
    records.value = append
      ? [...records.value, ...page.records.filter(row =>
          !records.value.some(item => directoryKey(item) === directoryKey(row)))]
      : page.records
    cursor.value = page.nextCursor
    hasMore.value = page.hasMore
    total.value = page.total
    typeCounts.value = page.typeCounts
    dateCounts.value = page.dateCounts
    loadError.value = ''
  } catch (error) {
    if (gen !== generation) return
    if (!append) records.value = []
    loadError.value = communityError(error, '活动加载失败，').message + ' '
  }
}

const reload = async () => {
  const gen = ++generation
  records.value = []
  cursor.value = null
  hasMore.value = false
  loading.value = true
  try {
    await load(false, gen)
  } finally {
    if (gen === generation) loading.value = false
  }
}

const loadMore = async () => {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    await load(true, generation)
  } finally {
    loadingMore.value = false
  }
}

const setKind = (value: 'all' | DirectorySourceType) => {
  if (kind.value === value) return
  kind.value = value
  void reload()
}

const onTabKeydown = (event: KeyboardEvent) => {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  const tabs = [...(event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="tab"]')]
  const current = tabs.indexOf(document.activeElement as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
    : (current + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
  event.preventDefault()
  tabs[next]?.focus()
  tabs[next]?.click()
}

const submitSearch = () => {
  keyword.value = searchInput.value.trim()
  void reload()
}

const resetFilters = () => {
  filters.when = 'all'
  filters.areaCode = ''
  filters.activityTypes = []
  filters.onlyAvailable = false
  void reload()
}

const resetAll = () => {
  searchInput.value = ''
  keyword.value = ''
  kind.value = 'all'
  resetFilters()
}

const openFilterDialog = () => {
  Object.assign(draftFilters, filters, { activityTypes: [...filters.activityTypes] })
  filterDialogOpen.value = true
}

const applyDraftFilters = () => {
  Object.assign(filters, draftFilters, { activityTypes: [...draftFilters.activityTypes] })
  filterDialogOpen.value = false
  void reload()
}

const resetDraftFilters = () => {
  draftFilters.when = 'all'
  draftFilters.areaCode = ''
  draftFilters.activityTypes = []
  draftFilters.onlyAvailable = false
}

const onFilterDialogClosed = () => {
  document.querySelector<HTMLElement>('.mobile-filter-button')?.focus({ preventScroll: true })
}

const dayCount = (date: string) =>
  dateCounts.value.find(row => row.date === date)?.count ?? 0

const pad = (value: number) => String(value).padStart(2, '0')
const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

const dayLabel = (date: string) => `${Number(date.slice(5, 7))} 月 ${Number(date.slice(8, 10))} 日`
const weekdayLabel = (date: string) => WEEKDAYS[new Date(`${date}T12:00:00`).getDay()]
const isTomorrow = (date: string) => {
  const tomorrow = new Date()
  tomorrow.setDate(tomorrow.getDate() + 1)
  return date === `${tomorrow.getFullYear()}-${pad(tomorrow.getMonth() + 1)}-${pad(tomorrow.getDate())}`
}

const timeText = (value: string) => value.slice(11, 16)
const kindText = (item: DirectoryItem) => item.sourceType === 'COMPANION' ? '同行邀约' : '城市活动'
const detailRoute = (item: DirectoryItem) =>
  item.sourceType === 'COMPANION' ? `/companion-requests/${item.sourceId}` : `/activities/${item.sourceId}`

const sceneFor = (item: DirectoryItem): ShiguangScene =>
  (({ SPORTS: 'move', STUDY: 'quiet', WORKSHOP: 'quiet', BOARD_GAME: 'together', OTHER: 'together' }) as Record<string, ShiguangScene>)[item.activityType] || 'together'

const locationText = (item: DirectoryItem) => {
  if (item.venueName) return item.spaceName ? `${item.venueName} · ${item.spaceName}` : item.venueName
  if (item.areaCode) return campusAreaLabel(item.areaCode)
  return '地点待确认'
}

const rowStatusLabel = (item: DirectoryItem) => {
  if (isHostedByViewer(item)) return '你发起的'
  if (item.viewerParticipationRole === 'PARTICIPANT') return '你已加入'
  if (item.viewerApplicationStatus === 'PENDING') return '等待回应'
  if (CLOSED_STATUSES.includes(item.status)) return STATUS_LABELS[item.status] || item.status
  return '开放申请'
}

const rowStatusTone = (item: DirectoryItem) => {
  if (item.viewerApplicationStatus === 'PENDING') return 'waiting'
  if (!isHostedByViewer(item) && item.viewerParticipationRole !== 'PARTICIPANT'
    && CLOSED_STATUSES.includes(item.status)) return 'neutral'
  return ''
}

const openCreate = (kind: CreateKind) => {
  void createDialog.value?.open(kind)
}

const clearCreateQuery = () => {
  if (route.query.create) void router.replace('/activities')
}

onMounted(async () => {
  await reload()
  // 返回目录时恢复离开前的浏览位置（合同 §2.2）
  if (store.directoryScroll) {
    window.scrollTo({ top: store.directoryScroll, behavior: 'instant' as ScrollBehavior })
    store.directoryScroll = 0
  }
  const activityId = Number(route.query.activityId)
  if (route.query.create === 'companion' && Number.isInteger(activityId) && activityId > 0) {
    try {
      const linked = await activityApi.activity(activityId)
      await createDialog.value?.open('COMPANION_REQUEST', linked)
    } catch {
      loadError.value = '关联活动无法加载。'
    }
  }
})

onBeforeRouteLeave(() => {
  store.directoryScroll = window.scrollY
})

</script>
