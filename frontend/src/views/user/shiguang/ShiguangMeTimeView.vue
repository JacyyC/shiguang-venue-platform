<template>
  <div class="sgv-activity">
    <div class="shell page-arrive">
      <div class="page-heading">
        <div>
          <h1 tabindex="-1">我的时间<span class="dot-stop">.</span></h1>
          <p>留给喜欢的事，也留给一起的人。</p>
        </div>
        <div class="heading-actions">
          <router-link v-if="menu.hasPermission('reservation:my')" class="text-button" to="/me/reservations">预约记录</router-link>
          <router-link class="text-button" to="/activities">再找一场相聚 <span class="icon"><el-icon><ArrowRight /></el-icon></span></router-link>
        </div>
      </div>

      <div class="time-filters">
        <div class="tabs" role="tablist" aria-label="我的安排类型" @keydown="onTabKeydown">
          <button
            v-for="tab in TABS"
            :key="tab.value"
            role="tab"
            type="button"
            :aria-selected="activeTab === tab.value ? 'true' : 'false'"
            :data-test="`time-tab-${tab.value}`"
            @click="setTab(tab.value)"
          >{{ tab.label }}</button>
        </div>
      </div>

      <div class="time-layout">
        <div>
          <p v-if="loading && !visibleItems.length" class="content-copy" role="status">正在读取你的时间安排…</p>
          <div v-else-if="loadError && !visibleItems.length" class="empty-state" role="alert" data-test="load-error">
            <div class="empty-art"><ShiguangArt scene="together" /></div>
            <div>
              <h2>这段时间暂时读不出来。</h2>
              <p>{{ loadError }}这里显示的是加载失败，不是你没有安排。</p>
              <button class="button" type="button" data-test="retry-time" @click="reload">
                <span class="icon"><el-icon><Refresh /></el-icon></span>重新加载
              </button>
            </div>
          </div>
          <div v-else-if="!visibleItems.length" class="empty-state" data-test="empty">
            <div class="empty-art"><ShiguangArt scene="quiet" /></div>
            <div>
              <h2>下一段时间，还没写下。</h2>
              <p>一场球、一本书，或一次相聚。喜欢的事，值得慢慢安排。</p>
              <router-link class="button" to="/activities">找一场相聚</router-link>
            </div>
          </div>

          <template v-else>
            <div class="time-group-heading">
              <h2>{{ activeTab === 'pending' ? '等一个回应' : '接下来的安排' }}</h2>
              <span data-test="group-count">{{ groupCount }} 段时间</span>
            </div>
            <article
              v-for="record in visibleItems"
              :key="record.key"
              class="time-record"
              data-test="time-record"
            >
              <router-link class="thumb" :to="record.link" :aria-label="`查看${record.title}`">
                <ShiguangArt :scene="record.scene" />
              </router-link>
              <div>
                <span class="status" :class="record.tone">{{ record.statusLabel }}</span>
                <h3><router-link :to="record.link">{{ record.title }}</router-link></h3>
                <div class="item-facts">
                  <span v-if="record.location">{{ record.location }}</span>
                  <span>{{ record.kindText }}</span>
                </div>
                <p v-if="record.locationPending" class="muted micro-hint">地点仍待确认</p>
              </div>
              <div class="time-side">
                <strong>{{ record.timeText }}</strong>
                <small>{{ record.dayText }}</small>
                <router-link class="text-button" :to="record.link">{{ record.owned ? '管理活动' : '查看安排' }}<span class="icon"><el-icon><ArrowRight /></el-icon></span></router-link>
              </div>
            </article>
            <p v-if="loadError" class="muted" role="status" style="margin-top:12px">{{ loadError }}，以上为已加载到的部分。</p>
            <div class="directory-more">
              <button v-if="hasMore" class="text-button" type="button" data-test="load-more" :disabled="loading" @click="loadMore">
                {{ loading ? '正在加载…' : '看更多安排' }}
              </button>
              <span v-else>已经看到全部安排</span>
            </div>
          </template>
        </div>

        <aside class="time-note">
          <h2>空闲，也有很多种可能。</h2>
          <div class="note-art"><ShiguangArt scene="together" /></div>
          <p>一场球、一本书，或者一杯咖啡。下一次相聚，也可以由你发起。</p>
          <button class="text-button" type="button" data-test="create-companion" @click="openCreate">
            写下一个小邀约<span class="icon"><el-icon><ArrowRight /></el-icon></span>
          </button>
        </aside>
      </div>
    </div>

    <ActivityCreateDialog ref="createDialog" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMenuStore } from '@/stores/menu'
import { meTimeApi, type MeTimeItem } from '@/api/meTime'
import { directoryApi, directoryKey } from '@/api/directory'
import type { DirectoryItem } from '@/api/directory'
import { communityError } from '@/api/community'
import { campusAreaLabel } from '@/utils/activityPresentation'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import type { ShiguangScene } from '@/components/shiguang/types'
import ActivityCreateDialog, { type CreateKind } from '@/views/user/activity/ActivityCreateDialog.vue'

const TABS = [
  { value: 'all', label: '全部安排' },
  { value: 'pending', label: '等待回应' },
  { value: 'joined', label: '已加入' },
  { value: 'owned', label: '我发起的' }
] as const
type TabValue = (typeof TABS)[number]['value']

const RESERVATION_STATUS_LABELS: Record<string, string> = {
  PENDING_REVIEW: '待审核', APPROVED: '审核通过', PENDING_PAYMENT: '待支付', PAID: '已支付',
  COMPLETED: '已完成', REJECTED: '未通过', CANCELED: '已取消', EXPIRED: '已过期',
  REFUNDING: '退款中', REFUNDED: '已退款'
}
const ACTIVITY_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '报名中', FULL: '名额已满',
  CLOSED: '已关闭', CANCELED: '已取消', EXPIRED: '已结束'
}
/** 已取消/过期领域的待回应申请不再进入等待列表（与 MeTimeService 口径一致） */
const DEAD_COMPANION_STATUSES = ['CANCELED', 'EXPIRED']

const menu = useMenuStore()
const route = useRoute()
const router = useRouter()
const createDialog = ref<InstanceType<typeof ActivityCreateDialog> | null>(null)

const activeTab = ref<TabValue>('all')
const meTimeItems = ref<MeTimeItem[]>([])
const hostedItems = ref<DirectoryItem[]>([])
const meTimeTotal = ref(0)
const hostedCursor = ref<string | null>(null)
const hostedHasMore = ref(false)
const counts = ref<{ pendingApplications: number; activeParticipations: number; hosted: number } | null>(null)
const loading = ref(true)
const loadError = ref('')
const meTimePage = ref(1)

const setTab = (value: TabValue) => {
  activeTab.value = value
  void router.replace({ query: value === 'all' ? {} : { tab: value } })
}

const hostedKeys = ref(new Set<string>())
const isOwnedDuplicate = (item: MeTimeItem) => hostedKeys.value.has(`${item.sourceType}:${item.sourceId}`)

interface TimeRecord {
  key: string
  title: string
  link: string
  scene: ShiguangScene
  statusLabel: string
  tone: string
  location?: string | null
  locationPending: boolean
  kindText: string
  timeText: string
  dayText: string
  owned: boolean
}

const pad = (value: number) => String(value).padStart(2, '0')
const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const dayTextOf = (value?: string | null) => {
  if (!value) return '时间待定'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间待定'
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日 ${WEEKDAYS[date.getDay()]}`
}
const timeTextOf = (value?: string | null) => {
  if (!value) return '--:--'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '--:--'
    : `${pad(date.getHours())}:${pad(date.getMinutes())}`
}
const sceneByType = (sourceType: string): ShiguangScene =>
  (({ RESERVATION: 'quiet', ACTIVITY: 'move', COMPANION_REQUEST: 'together' }) as Record<string, ShiguangScene>)[sourceType] || 'together'

const hostedRecords = computed<TimeRecord[]>(() => hostedItems.value.map(record => ({
  key: directoryKey(record),
  title: record.title,
  link: record.sourceType === 'COMPANION'
    ? `/companion-requests/${record.sourceId}/manage` : `/activities/${record.sourceId}/manage`,
  scene: sceneByType(record.sourceType),
  statusLabel: record.status === 'DRAFT' ? '草稿' : '我发起的',
  tone: record.status === 'DRAFT' ? 'waiting' : '',
  location: record.venueName || (record.areaCode ? campusAreaLabel(record.areaCode) : undefined),
  locationPending: record.commitmentStatus !== 'CONFIRMED',
  kindText: record.sourceType === 'COMPANION' ? '同行邀约' : '城市活动',
  timeText: timeTextOf(record.startTime),
  dayText: dayTextOf(record.startTime),
  owned: true
})))

const meTimeRecords = computed<TimeRecord[]>(() => meTimeItems.value
  .filter(item => !(activeTab.value !== 'owned' && isOwnedDuplicate(item)))
  .map(item => {
    const owned = item.sourceType !== 'RESERVATION'
      && hostedKeys.value.has(`${item.sourceType}:${item.sourceId}`)
    const pending = Boolean(item.pending)
    const statusLabel = item.sourceType === 'RESERVATION'
      ? RESERVATION_STATUS_LABELS[item.status] || item.status
      : owned ? '我发起的'
        : pending ? '等待回应'
          : item.status === 'WITHDRAWN' ? '已撤回'
            : item.status === 'REJECTED' ? '未被接受'
              : ACTIVITY_STATUS_LABELS[item.status] || item.status
    const tone = pending ? 'waiting' : owned || (!pending && item.sourceType !== 'RESERVATION') ? '' : 'neutral'
    return {
      key: `${item.sourceType}-${item.sourceId}`,
      title: item.title,
      link: owned
        ? (item.sourceType === 'COMPANION_REQUEST'
          ? `/companion-requests/${item.sourceId}/manage` : `/activities/${item.sourceId}/manage`)
        : item.detailPath,
      scene: sceneByType(item.sourceType),
      statusLabel,
      tone,
      location: item.location,
      locationPending: false,
      kindText: item.sourceType === 'RESERVATION' ? '场地预约'
        : item.sourceType === 'ACTIVITY' ? '城市活动' : '同行邀约',
      timeText: timeTextOf(item.startTime),
      dayText: dayTextOf(item.startTime),
      owned
    }
  }))

const visibleItems = computed<TimeRecord[]>(() => {
  if (activeTab.value === 'owned') return hostedRecords.value
  if (activeTab.value === 'pending') return meTimeRecords.value.filter(record => record.statusLabel === '等待回应')
  if (activeTab.value === 'joined') {
    return meTimeRecords.value.filter(record =>
      record.kindText !== '场地预约' && record.statusLabel !== '等待回应'
      && record.statusLabel !== '已撤回' && record.statusLabel !== '未被接受')
  }
  return [...meTimeRecords.value, ...hostedRecords.value.filter(hosted =>
    !meTimeRecords.value.some(record => record.key === hosted.key))]
})

const groupCount = computed(() => {
  if (!counts.value) return visibleItems.value.length
  if (activeTab.value === 'owned') return counts.value.hosted
  if (activeTab.value === 'pending') return counts.value.pendingApplications
  if (activeTab.value === 'joined') return counts.value.activeParticipations
  return visibleItems.value.length
})

const hasMore = computed(() => activeTab.value === 'owned'
  ? hostedHasMore.value
  : meTimeItems.value.length < meTimeTotal.value)

const loadPage = async () => {
  meTimePage.value = 1
  loading.value = true
  try {
    const [page, hostPage, serverCounts] = await Promise.all([
      meTimeApi.myTime({ pageNum: 1, pageSize: 20 }),
      directoryApi.myHosted({ limit: 20 }),
      meTimeApi.counts()
    ])
    const existing = new Set(meTimeItems.value.map(item => `${item.sourceType}-${item.sourceId}`))
    meTimeItems.value.push(...page.records.filter(item => !existing.has(`${item.sourceType}-${item.sourceId}`)))
    meTimeTotal.value = page.total
    hostedItems.value = hostPage.records
    hostedKeys.value = new Set(hostPage.records.map(record => directoryKey(record)))
    hostedCursor.value = hostPage.nextCursor
    hostedHasMore.value = hostPage.hasMore
    counts.value = serverCounts
    loadError.value = ''
  } catch {
    loadError.value = '时间安排暂时无法加载。'
  } finally {
    loading.value = false
  }
}

const loadMore = async () => {
  if (loading.value) return
  if (activeTab.value === 'owned') {
    return void loadMoreHosted()
  }
  return void loadMoreMeTime()
}

const loadMoreHosted = async () => {
  loading.value = true
  try {
    const page = await directoryApi.myHosted({ cursor: hostedCursor.value ?? undefined, limit: 20 })
    const existing = new Set(hostedItems.value.map(record => directoryKey(record)))
    hostedItems.value.push(...page.records.filter(record => !existing.has(directoryKey(record))))
    hostedCursor.value = page.nextCursor
    hostedHasMore.value = page.hasMore
  } catch (error) {
    loadError.value = communityError(error, '我的发起暂时无法加载').message
  } finally {
    loading.value = false
  }
}

const loadMoreMeTime = async () => {
  loading.value = true
  try {
    const page = await meTimeApi.myTime({ pageNum: meTimePage.value + 1, pageSize: 20 })
    if (!page.records.length) return
    const existing = new Set(meTimeItems.value.map(item => `${item.sourceType}-${item.sourceId}`))
    meTimeItems.value.push(...page.records.filter(item => !existing.has(`${item.sourceType}-${item.sourceId}`)))
    meTimePage.value += 1
    meTimeTotal.value = page.total
    loadError.value = ''
  } catch {
    loadError.value = '时间安排暂时无法加载。'
  } finally {
    loading.value = false
  }
}

const reload = () => {
  meTimeItems.value = []
  hostedItems.value = []
  void loadPage()
}

const onTabKeydown = (event: KeyboardEvent) => {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  const tabs = [...(event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="tab"]')]
  const current = tabs.indexOf(document.activeElement as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
    : (current + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
  event.preventDefault()
  tabs[next]?.focus()
  setTab(TABS[next]?.value || 'all')
}

const openCreate = () => {
  void createDialog.value?.open('COMPANION_REQUEST')
}

watch(() => route.query.tab, value => {
  if (value === 'pending' || value === 'joined' || value === 'owned') activeTab.value = value
  else if (!value) activeTab.value = 'all'
})

onMounted(() => {
  const value = route.query.tab
  if (value === 'pending' || value === 'joined' || value === 'owned') activeTab.value = value
  void loadPage()
})
</script>
