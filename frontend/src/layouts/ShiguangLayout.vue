<template>
  <div
    class="sgv-home home-page"
    :data-page="dataPage"
    :data-theme="theme.theme"
    :data-motion="reduced ? 'reduced' : 'full'"
    :data-page-hidden="pageHidden ? 'true' : 'false'"
    :data-overlay-open="overlayOpen ? 'true' : 'false'"
    :data-feature-visible="featureVisible ? 'true' : 'false'"
    :class="{ 'is-entering': entering }"
  >
    <header class="home-header">
      <div class="header-inner">
        <router-link class="brand" to="/" aria-label="回到发现首页">
          <span>
            <svg class="wordmark" aria-hidden="true"><use href="#sgv-brand-sign" /></svg>
            <small>SHIGUANG</small>
          </span>
        </router-link>
        <nav class="main-nav" aria-label="主导航">
          <router-link to="/" :class="{ 'is-active': dataPage === 'discovery' }" :aria-current="dataPage === 'discovery' ? 'page' : undefined">发现</router-link>
          <router-link to="/venues" :class="{ 'is-active': isVenueScope }" :aria-current="isVenueScope ? 'page' : undefined">场馆</router-link>
          <router-link to="/activities" :class="{ 'is-active': dataPage === 'activities' }" :aria-current="dataPage === 'activities' ? 'page' : undefined">活动</router-link>
          <router-link to="/community" :class="{ 'is-active': isCommunityScope }" :aria-current="isCommunityScope ? 'page' : undefined">社区</router-link>
          <router-link to="/me/time" :class="{ 'is-active': isTimeScope }" :aria-current="isTimeScope ? 'page' : undefined">我的时间</router-link>
        </nav>
        <div class="header-actions">
          <span class="location-label"><svg aria-hidden="true"><use href="#sgv-pin" /></svg>成都</span>
          <button class="icon-button" type="button" :aria-label="theme.theme === 'dark' ? '切换到明亮主题' : '切换到夜间主题'" @click="theme.setTheme(theme.theme === 'dark' ? 'light' : 'dark')">
            <svg aria-hidden="true"><use href="#sgv-sun" /></svg>
          </button>
          <el-tooltip content="消息中心" placement="bottom">
            <button class="icon-button message-bell" type="button" :aria-label="messageLabel" @click="router.push('/me/messages')">
              <svg aria-hidden="true"><use href="#sgv-bell" /></svg>
              <span v-if="unreadKnown && app.unreadCount > 0" class="unread-badge" aria-hidden="true">{{ app.unreadCount > 99 ? '99+' : app.unreadCount }}</span>
            </button>
          </el-tooltip>
          <el-dropdown v-if="auth.user" trigger="click" @visible-change="onMenuVisibility">
            <button ref="personalTrigger" class="personal-trigger" type="button" aria-label="我的，打开个人菜单">
              <span class="avatar" aria-hidden="true">{{ avatarText }}</span><span>我的</span>
              <svg class="menu-chevron" aria-hidden="true" viewBox="0 0 20 20"><path d="m5 8 5 5 5-5" /></svg>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="menu.hasPermission('user:view')" @click="router.push('/me')">个人中心</el-dropdown-item>
                <el-dropdown-item v-if="menu.hasPermission('member:me')" @click="router.push('/me/membership')">会员中心</el-dropdown-item>
                <el-dropdown-item v-if="menu.hasPermission('user:view')" @click="router.push('/me/profile')">账号设置</el-dropdown-item>
                <el-dropdown-item v-if="adminTargets.length" @click="openWorkspace">工作台</el-dropdown-item>
                <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </header>

    <div ref="contentRef" id="sgv-home-content">
      <div v-if="!isBookingChrome" class="home-shell home-tools">
        <div class="welcome-row">
          <div class="welcome-title">
            <h1 id="sgv-home-title" tabindex="-1">{{ pageTitle }}<span>.</span></h1>
            <p>{{ pageIntro }}</p>
          </div>
          <div v-if="dataPage !== 'my-time'" class="search-workspace">
            <form class="search-console" role="search" @submit.prevent="submitSearch">
              <label class="search-field">
                <span class="sr-only">搜索场馆、区域或活动</span>
                <input
                  ref="searchInputRef"
                  v-model="searchInput"
                  type="search"
                  placeholder="找场馆，也找一点新鲜事"
                  autocomplete="off"
                  aria-controls="sgv-search-suggestions"
                  :aria-expanded="suggestionsVisible ? 'true' : 'false'"
                  @input="onQueryInput"
                  @focus="onQueryInput"
                  @keydown.esc.prevent="closeSuggestions"
                  @keydown.down.prevent="focusFirstSuggestion"
                >
              </label>
              <button class="search-button" type="submit" aria-label="搜索">
                <svg aria-hidden="true"><use href="#sgv-search" /></svg>
              </button>
            </form>
            <section v-if="suggestionsVisible" id="sgv-search-suggestions" class="search-suggestions" aria-label="搜索建议" @keydown="onSuggestionKeydown">
              <div v-if="suggestVenues.length" class="suggestion-group">
                <h2>场馆</h2>
                <button
                  v-for="venue in suggestVenues"
                  :key="venue.id"
                  type="button"
                  @click="goVenue(venue.id)"
                >
                  <span><strong>{{ venue.name }}</strong><small>{{ venue.spaceTypes?.join(' · ') || venue.area }}</small></span>
                  <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
                </button>
              </div>
              <div v-if="suggestActivities.length" class="suggestion-group">
                <h2>活动与同行</h2>
                <button
                  v-for="activity in suggestActivities"
                  :key="activity.id"
                  type="button"
                  @click="goActivity(activity)"
                >
                  <span><strong>{{ activity.title }}</strong><small>{{ activity.kindLabel }}</small></span>
                  <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
                </button>
              </div>
              <p v-if="!suggestVenues.length && !suggestActivities.length" class="suggestion-empty">
                没有匹配的内容，换个关键词或筛选条件看看。
              </p>
              <button class="suggestion-submit" type="button" @click="submitSearch">
                查看匹配结果 <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
              </button>
            </section>
          </div>
        </div>
        <div v-if="dataPage !== 'my-time'" class="discovery-index">
          <div class="category-tabs" role="group" aria-label="生活活动分类">
            <button
              v-for="tab in CATEGORY_TABS"
              :key="tab.value"
              type="button"
              :class="{ 'is-active': store.category === tab.value }"
              :aria-pressed="store.category === tab.value ? 'true' : 'false'"
              @click="setCategory(tab.value)"
            >{{ tab.label }}</button>
          </div>
          <div class="search-conditions">
            <label v-if="dataPage !== 'activities'" class="select-field" id="sgv-date-condition">
              <svg aria-hidden="true"><use href="#sgv-calendar" /></svg>
              <span>场馆日期</span>
              <select v-model.number="store.day" aria-label="选择场馆预约日期" @change="onConditionChange">
                <option :value="0">{{ dayLabel(0) }}</option>
                <option :value="1">{{ dayLabel(1) }}</option>
              </select>
            </label>
            <label class="select-field">
              <svg aria-hidden="true"><use href="#sgv-pin" /></svg>
              <span class="sr-only">区域</span>
              <select v-model="store.area" aria-label="选择区域" @change="onConditionChange">
                <option value="all">成都 · 全城</option>
                <option v-for="area in areaOptions" :key="area" :value="area">{{ area }}</option>
              </select>
            </label>
          </div>
        </div>
        <div
          v-if="filterActive && dataPage !== 'my-time'"
          class="filter-summary"
        >
          <span role="status">{{ filterSummary }}</span>
          <button type="button" @click="clearFilters">清除筛选 <svg aria-hidden="true"><use href="#sgv-close" /></svg></button>
        </div>
      </div>

      <router-view v-slot="{ Component }">
        <component :is="Component" :key="dataPage" />
      </router-view>

      <footer class="home-shell home-footer">
        <span class="footer-brand">
          <svg class="wordmark" aria-hidden="true"><use href="#sgv-brand-sign" /></svg>
          <small>日常之外，生活之中。</small>
        </span>
        <span class="preview-disclosure">插画为示意，不是场馆实景或设施证明</span>
        <router-link v-if="menu.hasPermission('reservation:my')" class="quiet-link" to="/me/feedback">问题反馈</router-link>
        <span class="micro">SHIGUANG / OFF HOURS CLUB</span>
      </footer>
    </div>

    <dialog ref="workspaceDialog" class="sgv-workspace-dialog" aria-labelledby="sgv-workspace-title" @keydown="onWorkspaceKeydown" @close="closeWorkspace" @cancel.prevent="closeWorkspace">
      <h2 id="sgv-workspace-title">选择当前工作身份</h2>
      <p>进入相应工作台，继续处理你的工作。</p>
      <div class="workspace-options">
        <button v-for="target in adminTargets" :key="target.path" type="button" @click="enterWorkspace(target.path)">
          <svg aria-hidden="true"><use :href="`#sgv-${target.icon}`" /></svg>
          <span><strong>{{ target.label }}</strong><small>{{ target.description }}</small></span>
        </button>
      </div>
      <button class="quiet-link" type="button" @click="closeWorkspace">关闭</button>
    </dialog>

    <VenueQuickDrawer :venue-id="store.quickVenueId" @close="store.closeVenueDrawer()" />

    <div v-if="entryStageVisible" class="sgv-entry entry-stage" :data-motion="reduced ? 'reduced' : 'full'">
      <canvas ref="entryCanvasRef" aria-hidden="true" />
      <svg ref="entryMarkRef" class="entry-wordmark" viewBox="0 0 218 88" aria-hidden="true" />
      <p class="entry-caption" aria-hidden="true">日常之外，生活之中。</p>
      <span class="sr-only" role="status">正在进入拾光</span>
      <button class="icon-button entry-skip" aria-label="直接进入首页" @click="finishEntry">
        <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'
import { useAppStore } from '@/stores/app'
import { useThemeStore } from '@/stores/theme'
import { useShiguangStore } from '@/stores/shiguang'
import { canAccessContentAdmin } from '@/utils/contentAdminAccess'
import { venueApi } from '@/api/venue'
import { activityApi } from '@/api/activity'
import type { CommunityActivity, CompanionRequest } from '@/api/activity'
import type { VenueListItem } from '@/api/types'
import VenueQuickDrawer from '@/components/shiguang/VenueQuickDrawer.vue'
import { useFunnelStore } from '@/stores/funnel'
import { WORKSPACE_DEFS } from '@/utils/workspaceTargets'
import { useNotificationStream } from '@/composables/useNotificationStream'

const CATEGORY_TABS = [
  { value: 'all', label: '全部发现' },
  { value: 'move', label: '打一场球' },
  { value: 'quiet', label: '读书自习' },
  { value: 'water', label: '去游泳' },
  { value: 'together', label: '见个面' }
]

const PAGE_COPY: Record<string, [string, string]> = {
  discovery: ['今日拾光', '一点空闲，很多种可能。'],
  venues: ['场馆', '一处合适的地方，让喜欢的事慢慢发生。'],
  activities: ['活动与同行', '城里正在发生的活动，和等人同行的小邀约。'],
  'my-time': ['我的时间', '留给自己的时间，都在这里。']
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const menu = useMenuStore()
const app = useAppStore()
const theme = useThemeStore()
const store = useShiguangStore()
const funnel = useFunnelStore()

const reduced = ref(window.matchMedia('(prefers-reduced-motion: reduce)').matches)
const pageHidden = ref(false)
const overlayOpen = ref(false)
const featureVisible = ref(true)
const entering = ref(false)
const entryStageVisible = ref(false)
const searchInput = ref(store.query)
const suggestionsVisible = ref(false)
const suggestVenues = ref<VenueListItem[]>([])
const suggestActivities = ref<Array<{ id: number; title: string; kindLabel: string; kind: 'event' | 'invitation' }>>([])
const areaOptions = ref<string[]>([])
const searchInputRef = ref<HTMLInputElement | null>(null)
/**
 * AR-03：共享页头的搜索建议对活动/同行是可选附属内容。无 community:access 时不发起请求；
 * 权限在服务端被撤销时用局部降级配置留在原页，而不是被全局跳转到 /403。
 */
const canAccessCommunity = computed(() => menu.hasPermission('community:access'))
const communityFallback = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

const dataPage = computed(() => {
  if (['MeOverview', 'Profile', 'Messages', 'Favorites', 'MyFeedback'].includes(String(route.name))) return 'personal'
  if (route.name === 'VenueList') return 'venues'
  if (route.name === 'VenueDetail') return 'venue-detail'
  if (route.name === 'ActivityHub') return 'activities'
  if (route.name === 'ActivityManage' || route.name === 'CompanionManage') return 'my-time'
  if (route.name === 'ActivityResult' || route.name === 'CompanionResult') return 'activities'
  if (route.name === 'ActivityDetail' || route.name === 'CompanionDetail') return 'activities'
  if (route.name === 'CommunityGroups' || route.name === 'CommunityGroup' || route.name === 'CommunityPost') return 'community'
  if (route.name === 'Membership' || route.name === 'Coupons' || route.name === 'StoredValue') return 'member'
  if (route.name === 'MyReservations' || route.name === 'MyOrders' || route.name === 'MyCheckins'
    || route.name === 'Payment' || route.name === 'AlipaySandboxReturn') return 'transactions'
  if (route.name === 'MyTime') return 'my-time'
  if (route.name === 'ReservationConfirm') return 'confirm'
  if (route.name === 'ReservationResult') return 'result'
  return 'discovery'
})
/** 预约链路与「我的时间」为任务/聚合型页面：页面自带标题，不渲染布局级欢迎行、搜索台与筛选摘要。 */
// 活动域 V02 表面（目录/详情/结果）自带页面标题与节奏：布局级欢迎行、搜索台与筛选摘要不再渲染（SHIGUANG-ACTIVITY-REFACTOR-20260911）
// 社区 V1 表面（首页/小组/帖子）同理：rail、搜索与类别由页面自带（SHIGUANG-COMMUNITY-REFACTOR-20260911）
// 会员中心三入口（会员/券夹/储值）同理：身份、口袋条与五页内部导航由页面自带（SHIGUANG-MEMBER-REFACTOR-20260912）
// 交易面（预约记录/订单退款/凭证/收银/沙箱返回）同理：面级标题、页签与流程由页面自带（SHIGUANG-BATCH-01-REFACTOR-20260913）
const isBookingChrome = computed(() => ['my-time', 'venue-detail', 'confirm', 'result', 'activities', 'community', 'member', 'transactions', 'personal'].includes(dataPage.value))
const isVenueScope = computed(() => ['venues', 'venue-detail', 'confirm'].includes(dataPage.value))
const isTimeScope = computed(() => ['my-time', 'result'].includes(dataPage.value))
const isCommunityScope = computed(() => dataPage.value === 'community')
const pageTitle = computed(() => PAGE_COPY[dataPage.value]?.[0] ?? '今日拾光')
const pageIntro = computed(() => PAGE_COPY[dataPage.value]?.[1] ?? '')
const avatarText = computed(() => (auth.user?.nickname || auth.user?.username || '光').slice(0, 1))
const adminTargets = computed(() => WORKSPACE_DEFS.filter(target => target.key === 'content'
  ? canAccessContentAdmin(auth.permissions)
  : menu.hasPermission(target.key === 'venue' ? 'venue-admin:view' : 'system:view')))
const personalTrigger = ref<HTMLButtonElement | null>(null)
const workspaceDialog = ref<HTMLDialogElement | null>(null)
const unreadKnown = ref(false)
const unreadFailed = ref(false)
const messageLabel = computed(() => unreadFailed.value
  ? `消息中心，未读数量暂不可用${unreadKnown.value ? `，上次读取 ${app.unreadCount} 条未读` : ''}`
  : unreadKnown.value ? `消息中心，${app.unreadCount} 条未读消息` : '消息中心，正在读取未读数量')
async function refreshUnread() {
  try { await app.refreshUnreadCount(); unreadKnown.value = true; unreadFailed.value = false }
  catch { unreadFailed.value = true }
}
watch(() => app.unreadCount, () => { unreadKnown.value = true; unreadFailed.value = false })
const { start: startNotifications, stop: stopNotifications } = useNotificationStream({ onPoll: refreshUnread })
function onMenuVisibility(visible: boolean) {
  overlayOpen.value = visible
}
async function openWorkspace() {
  if (adminTargets.value.length === 1) return void router.push(adminTargets.value[0].path)
  if (!adminTargets.value.length) return
  await nextTick()
  personalTrigger.value?.focus({ preventScroll: true })
  workspaceDialog.value?.showModal()
}
function closeWorkspace() {
  if (workspaceDialog.value?.open) workspaceDialog.value.close()
  personalTrigger.value?.focus({ preventScroll: true })
}
function onWorkspaceKeydown(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const buttons = workspaceDialog.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
  if (!buttons?.length) return
  const first = buttons[0]
  const last = buttons[buttons.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
function enterWorkspace(path: string) {
  if (!adminTargets.value.some(target => target.path === path)) return
  closeWorkspace()
  void router.push(path)
}
const filterActive = computed(() => Boolean(
  store.query.trim() || store.area !== 'all' || store.category !== 'all' || (dataPage.value !== 'activities' && store.day !== 0)
))
const filterSummary = computed(() => {
  if (dataPage.value === 'venues') return `${store.query.trim() || '筛选'} · ${store.day ? '明天' : '今天'}可约`
  if (dataPage.value === 'activities') return `${store.query.trim() || '全部活动'} · 本周`
  return `${store.query.trim() || '全部发现'} · ${store.day ? '明天' : '今天'}`
})

const dayLabel = (day: number) => {
  const date = new Date()
  date.setDate(date.getDate() + day)
  const text = `${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}`
  return `${day === 0 ? '今天' : '明天'} · ${text}`
}

let suggestAbort: AbortController | null = null
let suggestTimer = 0

const runSuggestions = async () => {
  const keyword = searchInput.value.trim()
  suggestAbort?.abort()
  const controller = new AbortController()
  suggestAbort = controller
  const kindLabel = (kind: 'event' | 'invitation') => (kind === 'invitation' ? '同行邀约' : '城市活动')
  try {
    const [venuePage, activities, companions] = await Promise.all([
      venueApi.list({ ...(keyword ? { keyword } : {}), pageNum: 1, pageSize: 3, status: 'ACTIVE' }),
      canAccessCommunity.value
        ? activityApi.activities({ ...(keyword ? { keyword } : {}), limit: 2 }, communityFallback).catch(() => ({ records: [] as CommunityActivity[] }))
        : Promise.resolve({ records: [] as CommunityActivity[] }),
      canAccessCommunity.value
        ? activityApi.companions({ ...(keyword ? { keyword } : {}), limit: 2 }, communityFallback).catch(() => ({ records: [] as CompanionRequest[] }))
        : Promise.resolve({ records: [] as CompanionRequest[] })
    ])
    if (controller.signal.aborted) return
    suggestVenues.value = venuePage.records as VenueListItem[]
    suggestActivities.value = [
      ...activities.records.slice(0, 2).map((item) => ({ id: item.id, title: item.title, kind: 'event' as const, kindLabel: kindLabel('event') })),
      ...companions.records.slice(0, 2).map((item) => ({ id: item.id, title: item.title, kind: 'invitation' as const, kindLabel: kindLabel('invitation') }))
    ].slice(0, 2)
    suggestionsVisible.value = true
  } catch {
    if (!controller.signal.aborted) suggestionsVisible.value = false
  }
}

const onQueryInput = () => {
  window.clearTimeout(suggestTimer)
  suggestTimer = window.setTimeout(runSuggestions, 200)
}

const closeSuggestions = () => {
  suggestionsVisible.value = false
  searchInputRef.value?.focus()
}

const focusFirstSuggestion = () => {
  if (!suggestionsVisible.value) onQueryInput()
  else document.querySelector<HTMLElement>('#sgv-search-suggestions button')?.focus()
}

const onSuggestionKeydown = (event: KeyboardEvent) => {
  if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return
  const buttons = [...document.querySelectorAll<HTMLElement>('#sgv-search-suggestions button')]
  if (!buttons.length) return
  event.preventDefault()
  const index = buttons.indexOf(event.target as HTMLElement)
  const next = index + (event.key === 'ArrowDown' ? 1 : -1)
  if (next < 0) searchInputRef.value?.focus()
  else buttons[Math.min(next, buttons.length - 1)]?.focus()
}

const goVenue = (id: number) => {
  closeSuggestions()
  const date = new Date()
  date.setDate(date.getDate() + store.day)
  router.push({ path: `/venues/${id}`, query: { date: new Intl.DateTimeFormat('sv-SE').format(date) } })
}

const goActivity = (activity: { id: number; kind: 'event' | 'invitation' }) => {
  closeSuggestions()
  router.push(activity.kind === 'invitation' ? `/companion-requests/${activity.id}` : `/activities/${activity.id}`)
}

const submitSearch = () => {
  store.query = searchInput.value
  suggestionsVisible.value = false
  void funnel.track({
    eventName: 'SEARCH_SUBMITTED',
    eventStage: 'SEARCH',
    keyword: store.query.trim(),
    pagePath: route.path,
    metadata: { area: store.area, category: store.category, day: store.day }
  }).catch(() => undefined)
}

const setCategory = (value: string) => {
  store.category = value
}

const onConditionChange = () => {
  // 条件区变更立即应用（与输入建议的「提交才应用」不同，保持预览语义）
}

const clearFilters = () => {
  store.clearFilters()
  searchInput.value = ''
}

const logout = async () => {
  stopNotifications()
  await auth.logout()
  menu.reset()
  router.replace('/login')
}

// —— 品牌转场（UI-02）：完整仪式仅认证成功进入首页时执行；其余 260ms 淡入 ——
const contentRef = ref<HTMLElement | null>(null)
const entryCanvasRef = ref<HTMLCanvasElement | null>(null)
const entryMarkRef = ref<SVGSVGElement | null>(null)
let finishTimer = 0

const paintEntryCanvas = () => {
  const canvas = entryCanvasRef.value
  if (!canvas) return
  const width = canvas.clientWidth || window.innerWidth
  const height = canvas.clientHeight || window.innerHeight
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) return
  const plane = (points: Array<[number, number]>, fill: string) => {
    context.fillStyle = fill
    context.beginPath()
    points.forEach(([x, y], index) => {
      if (index === 0) context.moveTo(x * width, y * height)
      else context.lineTo(x * width, y * height)
    })
    context.closePath()
    context.fill()
  }
  plane([[0, 0], [.34, 0], [.12, 1], [0, 1]], '#f5f8ef70')
  plane([[.86, 0], [1, 0], [1, .86], [.70, 1]], '#9ab09d12')
  plane([[0, .72], [.33, .88], [.28, 1], [0, .90]], '#f5f8ef57')
  plane([[.80, .61], [1, .66], [1, .77], [.70, .72]], '#8fa69816')
}

const finishEntry = () => {
  window.clearTimeout(finishTimer)
  entering.value = false
  entryStageVisible.value = false
  contentRef.value?.getAnimations().forEach((animation) => animation.cancel())
}

const startEntry = () => {
  const full = window.sessionStorage.getItem('sgv-entry-full') === '1'
  window.sessionStorage.removeItem('sgv-entry-full')
  if (reduced.value) return
  if (!full) {
    contentRef.value?.animate([{ opacity: .55 }, { opacity: 1 }], { duration: 260, easing: 'ease-out' })
    return
  }
  entering.value = true
  entryStageVisible.value = true
  window.requestAnimationFrame(() => {
    paintEntryCanvas()
    const mark = entryMarkRef.value
    const stage = entryStageVisible ? document.querySelector<HTMLElement>('.sgv-entry') : null
    if (!mark || !stage) return finishEntry()
    mark.innerHTML = document.querySelector('#sgv-brand-sign')?.innerHTML ?? ''
    const markBounds = mark.getBoundingClientRect()
    const origin = document.querySelector('.auth-promise .wordmark')?.getBoundingClientRect()
    const destination = document.querySelector('.home-header .wordmark')?.getBoundingClientRect()
    const initial = origin
      ? `translate(${origin.left - markBounds.left}px,${origin.top - markBounds.top}px) scale(${origin.width / markBounds.width})`
      : 'translateY(20px) scale(.88)'
    const final = destination
      ? `translate(${destination.left - markBounds.left}px,${destination.top - markBounds.top}px) scale(${destination.width / markBounds.width})`
      : 'scale(.28) translateY(-4px)'
    mark.animate([
      { transform: initial, opacity: .25, offset: 0 },
      { transform: 'none', opacity: 1, offset: .43 },
      { transform: 'none', opacity: 1, offset: .62 },
      { transform: final, opacity: 1, offset: .94 },
      { transform: final, opacity: 0, offset: 1 }
    ], { duration: 1800, easing: 'linear', fill: 'both' })
    stage.animate([{ backgroundColor: '#e6ede800' }, { backgroundColor: '#e6ede8' }], { duration: 320, fill: 'both' })
    window.setTimeout(() => {
      stage.animate([{ backgroundColor: '#e6ede8' }, { backgroundColor: '#e6ede800' }], { duration: 550, fill: 'both' })
    }, 1120)
    contentRef.value?.animate(
      [{ opacity: 0, translate: '0 12px' }, { opacity: 1, translate: '0 0' }],
      { duration: 320, delay: 1480, easing: 'cubic-bezier(.22,.75,.25,1)', fill: 'backwards' }
    )
    finishTimer = window.setTimeout(finishEntry, 1810)
  })
}

const onKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape' && entering.value) finishEntry()
}

// V02 动效生命周期：主插画滚出视口即暂停、弹层打开即暂停（SHIGUANG-ACTIVITY-REFACTOR-20260911）。
let artObserver: IntersectionObserver | null = null
let overlayObserver: MutationObserver | null = null

const refreshArtVisibility = () => {
  const arts = document.querySelectorAll('.feature-art')
  if (!arts.length) {
    featureVisible.value = true
    return
  }
  featureVisible.value = [...arts].some((node) => {
    const rect = node.getBoundingClientRect()
    return rect.bottom > 0 && rect.top < window.innerHeight
  })
}

const startArtLifecycle = () => {
  artObserver?.disconnect()
  artObserver = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) featureVisible.value = true
    else refreshArtVisibility()
  }, { threshold: 0 })
  document.querySelectorAll('.feature-art').forEach((node) => artObserver?.observe(node))
  refreshArtVisibility()
}

const startOverlayLifecycle = () => {
  if (overlayObserver) return
  const sync = () => {
    // 社区 V1 使用原生 <dialog>（NativeDialog），弹层暂停需同时覆盖 el-overlay 与 dialog[open]（SHIGUANG-COMMUNITY-REFACTOR-20260911 S05）
    overlayOpen.value = Boolean(
      document.querySelector('.el-overlay:not([style*="display: none"])')
      || document.querySelector('dialog[open]')
    )
  }
  overlayObserver = new MutationObserver(sync)
  overlayObserver.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class', 'open'] })
}
const onResize = () => {
  if (entering.value) finishEntry()
}
const onVisibility = () => {
  pageHidden.value = document.hidden
  if (document.hidden && entering.value) finishEntry()
}
const media = window.matchMedia('(prefers-reduced-motion: reduce)')
const onMediaChange = (event: MediaQueryListEvent) => {
  reduced.value = event.matches
  if (event.matches) finishEntry()
}

onMounted(() => {
  void focusPersonalContent()
  document.addEventListener('keydown', onKeydown)
  window.addEventListener('scroll', refreshArtVisibility, { passive: true })
  startArtLifecycle()
  startOverlayLifecycle()
  document.addEventListener('visibilitychange', onVisibility)
  media.addEventListener('change', onMediaChange)
  window.addEventListener('resize', onResize)
  void refreshUnread()
  startNotifications()
  venueApi.facets?.().then((facets) => { areaOptions.value = facets.areas ?? [] }).catch(() => {})
  startEntry()
})

onBeforeUnmount(() => {
  artObserver?.disconnect()
  overlayObserver?.disconnect()
  window.removeEventListener('scroll', refreshArtVisibility)
  window.clearTimeout(finishTimer)
  window.clearTimeout(suggestTimer)
  suggestAbort?.abort()
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('visibilitychange', onVisibility)
  media.removeEventListener('change', onMediaChange)
  window.removeEventListener('resize', onResize)
})

watch(dataPage, () => {
  searchInput.value = store.query
  suggestionsVisible.value = false
  const title = document.getElementById('sgv-home-title')
  if (title) title.focus({ preventScroll: true })
})

// 个人路由共用同一正文组件；首屏及路由名切换后聚焦正文，hash 页签继续沿用自身焦点合同。
async function focusPersonalContent() {
  await nextTick()
  if (dataPage.value === 'personal') contentRef.value?.querySelector<HTMLElement>('[data-route-focus]')?.focus({ preventScroll: true })
}
watch(() => route.name, focusPersonalContent, { flush: 'post' })
</script>
