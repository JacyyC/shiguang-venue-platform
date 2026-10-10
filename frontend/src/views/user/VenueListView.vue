<template>
  <div class="page-shell page-stack venue-page">
    <div class="page-head venue-page-head">
      <div class="page-head-main">
        <p class="sg-kicker">场馆检索</p>
        <h1 class="sg-display-l list-title">
          <span class="sg-mask-line"><span>场馆列表</span></span>
        </h1>
      </div>
      <span class="venue-count" aria-live="polite">共 {{ filteredVenues.length }} 家{{ filters.status === 'ACTIVE' ? '开放' : '' }}场馆</span>
    </div>

    <section class="sg-searchrow" role="search" aria-label="场馆筛选">
      <label class="sg-search-pill">
        <el-icon><Search /></el-icon>
        <input
          v-model="filters.keyword"
          type="search"
          placeholder="搜索场馆名称或地址"
          aria-label="搜索场馆名称或地址"
          @input="scheduleKeywordSearch"
          @keyup.enter="search"
        />
        <button v-if="filters.keyword" type="button" aria-label="清除搜索" title="清除搜索" @click="clearKeyword">
          <el-icon><Close /></el-icon>
        </button>
      </label>
    </section>

    <!-- V11 · 筛选索引栏（发现/活动/场馆统一形态）：类型维度承接 V10 的 facets.spaceTypes 归并口径（本地过滤），
         区域维度自搜索行下拉吸收（服务端过滤语义不变）；facets 失败两维度均空时整件隐藏，降级同前。 -->
    <SgFilterIndex
      v-if="railDimensions.length"
      :dimensions="railDimensions"
      :selection="railSelection"
      class="venue-indexrail"
      aria-label="场馆筛选维度"
      data-test="venue-indexrail"
      @update:selection="updateRailSelection"
    />

    <details class="venue-adv">
      <summary>高级条件<span class="venue-adv-caret" aria-hidden="true">▾</span></summary>
      <div class="venue-adv-body">
        <SgDropdown v-model="filters.status" variant="chip" :options="statusOptions" aria-label="开放状态筛选" @change="onFilterChange" />
      </div>
    </details>

    <el-skeleton v-if="loading && !venues.length" animated :rows="7" />
    <section v-else-if="loadError" class="sg-state-error" role="alert">
      <div>
        <strong>场馆列表暂时无法加载</strong>
        <span>{{ loadError }}</span>
      </div>
      <button type="button" class="retry" @click="load">重新加载</button>
    </section>
    <template v-else-if="venueSections.length">
      <section v-for="section in venueSections" :key="section.key" class="venue-section rv" v-reveal :aria-label="`${section.label}分区`">
        <div class="sg-secband">
          <h4>{{ section.label }}</h4>
          <span class="sg-secband-cnt">{{ section.items.length }} 家</span>
          <button
            v-if="section.items.length > SECTION_PREVIEW_COUNT || isSectionExpanded(section.key)"
            type="button"
            class="sg-secband-more"
            @click="toggleSection(section.key)"
          >{{ isSectionExpanded(section.key) ? '收起' : '查看全部 →' }}</button>
        </div>
        <div class="venue-grid" aria-live="polite">
          <router-link
            v-for="venue in visibleItems(section)"
            :key="venue.id"
            class="venue-card sg-press"
            :data-study="isStudyVenue(venue) ? 'true' : undefined"
            :data-wash="washOf(section.type)"
            :to="`/venues/${venue.id}`"
            @click="trackDetail(venue.id)"
          >
            <span class="venue-hero" aria-hidden="true">
              <VenueTypeMotif :motif="section.motif" class="venue-hero-motif" />
              <span v-if="venue.minPrice != null" class="venue-price-chip sg-num">¥{{ priceText(venue) }}/时</span>
            </span>
            <span class="venue-body">
              <strong class="venue-name sg-serif">{{ venue.name }}</strong>
              <span class="venue-cmeta">
                <span>{{ venue.area || '区域待更新' }}</span>
                <span>{{ spaceSummary(venue) }}</span>
                <span v-if="venue.minPrice == null" class="venue-price">价格待更新</span>
              </span>
            </span>
          </router-link>
          <span class="rail-peek" aria-hidden="true"></span>
        </div>
      </section>
    </template>
    <EmptyState v-else title="没有匹配场馆" description="调整筛选条件后重试" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Close, Search } from '@element-plus/icons-vue'
import { venueApi } from '@/api/venue'
import type { VenueFacets } from '@/api/venue'
import type { VenueListItem } from '@/api/types'
import SgDropdown, { type SgDropdownOption } from '@/components/SgDropdown.vue'
import SgFilterIndex from '@/components/SgFilterIndex.vue'
import EmptyState from '@/components/EmptyState.vue'
import VenueTypeMotif from '@/components/VenueTypeMotif.vue'
import { venueMotifOfType, venueWashOf, type VenueMotif } from '@/utils/venueVisual'
import { useFunnelStore } from '@/stores/funnel'
import { scrollToPageHead } from '@/utils/scroll'

/** 分区带默认条数：设计决策 5「默认 4–6 条」取上限 6，三列网格恰为两整行。 */
const SECTION_PREVIEW_COUNT = 6
/** 分区浏览一次载全量再本地分区；50 条/页循环拉取，40 页为防御异常大数据的保险上限（同发现社区模型）。 */
const FETCH_PAGE_SIZE = 50
const MAX_AUTO_PAGES = 40

/** 分区词表按 Spec 设计决策 2；STUDY/STUDY_ROOM、ACTIVITY/ACTIVITY_ROOM 为同义历史码，合并为一个分区。 */
const SECTION_LABELS: Record<string, string> = {
  BASKETBALL: '篮球馆',
  BADMINTON: '羽毛球馆',
  TENNIS: '网球场',
  SWIMMING: '游泳馆',
  MEETING_ROOM: '会议室',
  STUDY: '自习室',
  ACTIVITY: '活动室'
}
const CANONICAL_TYPE: Record<string, string> = {
  STUDY: 'STUDY',
  STUDY_ROOM: 'STUDY',
  ACTIVITY: 'ACTIVITY',
  ACTIVITY_ROOM: 'ACTIVITY'
}

interface VenueSection {
  key: string
  type: string
  label: string
  motif: VenueMotif
  items: VenueListItem[]
}

const route = useRoute()
const venues = ref<VenueListItem[]>([])
const facets = ref<VenueFacets>({ areas: [], spaceTypes: [] })
const loading = ref(false)
const loadError = ref('')
const initialKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : ''
const filters = reactive({ keyword: initialKeyword, area: '', spaceType: '', status: 'ACTIVE' })
const funnel = useFunnelStore()

const statusOptions: SgDropdownOption[] = [
  { value: 'ACTIVE', label: '仅看开放中（默认）', dataLabel: '仅看开放中' },
  { value: '', label: '含已停用' }
]
const areaOptions = computed<SgDropdownOption[]>(() => [
  { value: '', label: '全部区域' },
  ...[...facets.value.areas].sort((left, right) => left.localeCompare(right, 'zh-CN')).map((area) => ({ value: area, label: area }))
])

/** V10→V11 · 类型维度数据源：facets.spaceTypes 按 CANONICAL_TYPE 归并去重（STUDY_ROOM→STUDY 同义码），
    标签沿用分区词表，顺序与分区带顺序同源（facets 序）；区域升为索引栏第二维度（服务端过滤语义不变）。 */
const typeChips = computed<{ value: string; label: string }[]>(() => {
  const seen = new Set<string>()
  const chips: { value: string; label: string }[] = []
  for (const raw of facets.value.spaceTypes) {
    const key = canonicalOf(raw)
    if (!key || seen.has(key)) continue
    seen.add(key)
    chips.push({ value: key, label: sectionLabel(key) })
  }
  return chips
})
/** V11 索引栏维度：类型（本地过滤）+ 区域（服务端过滤）；facets 失败两维度均空 → 组件整件隐藏。 */
const railDimensions = computed(() => {
  const dimensions: Array<{ key: string; label: string; options: Array<{ value: string; label: string }> }> = []
  if (typeChips.value.length) {
    dimensions.push({ key: 'spaceType', label: '类型', options: [{ value: '', label: '全部类型' }, ...typeChips.value] })
  }
  if (facets.value.areas.length) {
    dimensions.push({
      key: 'area',
      label: '区域',
      options: areaOptions.value.map((option) => ({ value: String(option.value), label: option.label }))
    })
  }
  return dimensions
})
const railSelection = computed(() => ({ spaceType: filters.spaceType, area: filters.area }))
const updateRailSelection = (value: Record<string, string>) => {
  if (value.area !== filters.area) {
    filters.area = value.area
    onFilterChange()
  }
  if (value.spaceType !== filters.spaceType) {
    setType(value.spaceType)
  }
}
const setType = (spaceType: string) => {
  if (filters.spaceType === spaceType) return
  filters.spaceType = spaceType
  // 纯本地过滤不重拉服务端；仅重置分区展开态
  expandedSections.clear()
}

const expandedSections = reactive(new Set<string>())
const isSectionExpanded = (key: string) => expandedSections.has(key)
const toggleSection = (key: string) => {
  expandedSections.has(key) ? expandedSections.delete(key) : expandedSections.add(key)
}
const visibleItems = (section: VenueSection): VenueListItem[] =>
  expandedSections.has(section.key) ? section.items : section.items.slice(0, SECTION_PREVIEW_COUNT)

/** 海报 wash（V3 L4）：分区馆型专属色注入卡面（游泳雾蓝/球馆松绿/其余蜜金） */
const washOf = (type: string) => venueWashOf(type)
const canonicalOf = (type: string) => CANONICAL_TYPE[type] ?? type
const sectionLabel = (type: string) => SECTION_LABELS[type] || type
/** V10 · 类型本地过滤：列表本就「载全量再本地分区」，类型筛选在同一集合上就地收敛（不新增服务端参数）；
    无类型场馆（其他场馆兜底分区）仅在「全部类型」下可见。 */
const filteredVenues = computed(() => {
  if (!filters.spaceType) return venues.value
  return venues.value.filter((venue) => (venue.spaceTypes || []).some((type) => canonicalOf(type) === filters.spaceType))
})
/** 分区图标沿用 venueVisual 画笔四枚映射（V5 §04），未命中回落屋形。 */
const venueSections = computed<VenueSection[]>(() => {
  const buckets = new Map<string, VenueListItem[]>()
  const untyped: VenueListItem[] = []
  for (const venue of filteredVenues.value) {
    // 类型筛选生效时分区只按所选类型收拢（否则一馆多类会重复落入多个分区，与计数口径分裂）
    const types = filters.spaceType ? [filters.spaceType] : (venue.spaceTypes || []).filter(Boolean)
    if (!types.length) {
      // 无 ACTIVE 场地/类型的场馆不静默丢弃：归入「其他场馆」尾部分区，
      // 否则计数（venues.length）与可见卡片口径分裂（走查 2026-08-30 F-02）。
      untyped.push(venue)
      continue
    }
    for (const raw of types) {
      const key = canonicalOf(raw)
      const bucket = buckets.get(key)
      bucket ? bucket.push(venue) : buckets.set(key, [venue])
    }
  }
  const orderedKeys: string[] = []
  for (const type of facets.value.spaceTypes) {
    const key = canonicalOf(type)
    if (buckets.has(key) && !orderedKeys.includes(key)) orderedKeys.push(key)
  }
  const rest = [...buckets.keys()].filter((key) => !orderedKeys.includes(key))
    .sort((left, right) => sectionLabel(left).localeCompare(sectionLabel(right), 'zh-CN'))
  const sections: VenueSection[] = [...orderedKeys, ...rest].map((key) => ({
    key,
    type: key,
    label: sectionLabel(key),
    motif: venueMotifOfType(key),
    items: buckets.get(key) as VenueListItem[]
  }))
  if (untyped.length) {
    sections.push({
      key: 'OTHER',
      type: 'OTHER',
      label: '其他场馆',
      motif: venueMotifOfType('OTHER'),
      items: untyped
    })
  }
  return sections
})

const spaceTypeLabels: Record<string, string> = {
  BADMINTON: '羽毛球',
  BASKETBALL: '篮球',
  TENNIS: '网球',
  SWIMMING: '游泳',
  STUDY: '自习室',
  STUDY_ROOM: '自习室',
  ACTIVITY: '活动室',
  ACTIVITY_ROOM: '活动室',
  MEETING_ROOM: '会议室'
}
const priceText = (venue: VenueListItem) => Number(venue.minPrice).toFixed(0)
const spaceSummary = (venue: VenueListItem) => {
  const types = [...new Set((venue.spaceTypes || []).map((type) => spaceTypeLabels[type] || type))].slice(0, 2).join(' / ')
  const count = Number(venue.activeSpaceCount || 0)
  if (!types && !count) return '场地信息待完善'
  return [types, count ? `${count} 个可预约场地` : ''].filter(Boolean).join(' · ')
}
const isStudyVenue = (venue: VenueListItem) => (venue.spaceTypes || []).some((type) => ['STUDY', 'STUDY_ROOM'].includes(type))
const trackInBackground = (payload: Parameters<typeof funnel.track>[0]) => {
  void funnel.track(payload).catch(() => undefined)
}

const load = async () => {
  loading.value = true
  loadError.value = ''
  trackInBackground({
    eventName: 'SEARCH_SUBMITTED',
    eventStage: 'SEARCH',
    keyword: filters.keyword,
    pagePath: '/venues',
    metadata: {
      area: filters.area,
      spaceType: filters.spaceType,
      status: filters.status
    }
  })
  try {
    const collected: VenueListItem[] = []
    let totalSeen = 0
    for (let pageNum = 1; pageNum <= MAX_AUTO_PAGES; pageNum += 1) {
      const page = await venueApi.list({
        pageNum,
        pageSize: FETCH_PAGE_SIZE,
        keyword: filters.keyword,
        area: filters.area,
        status: filters.status
      })
      collected.push(...page.records)
      totalSeen = Number(page.total ?? collected.length)
      if (!page.records.length || collected.length >= totalSeen) break
    }
    venues.value = collected
    trackInBackground({
      eventName: 'SEARCH_RESULT_VIEWED',
      eventStage: 'SEARCH',
      keyword: filters.keyword,
      pagePath: '/venues',
      metadata: {
        resultCount: collected.length,
        total: totalSeen
      }
    })
  } catch {
    venues.value = []
    loadError.value = '请检查网络连接后重试'
  } finally {
    loading.value = false
  }
}

const loadFacets = async () => {
  try {
    facets.value = await venueApi.facets()
  } catch {
    // facets 失败不阻塞浏览：区域下拉退化为仅「全部区域」，分区顺序退化为数据出现序。
    facets.value = { areas: [], spaceTypes: [] }
  }
}

const trackDetail = (venueId: number) => {
  trackInBackground({
    eventName: 'VENUE_DETAIL_VIEWED',
    eventStage: 'DETAIL',
    venueId,
    pagePath: '/venues'
  })
}
let keywordTimer: ReturnType<typeof setTimeout> | undefined
const search = () => {
  if (keywordTimer) clearTimeout(keywordTimer)
  void load()
}
const clearKeyword = () => {
  if (!filters.keyword) return
  filters.keyword = ''
  search()
}
const scheduleKeywordSearch = () => {
  if (keywordTimer) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => {
    keywordTimer = undefined
    void load()
  }, 300)
}
const onFilterChange = () => {
  expandedSections.clear()
  void load()
}

watch(() => route.query.keyword, (value) => {
  const keyword = typeof value === 'string' ? value : ''
  if (filters.keyword === keyword) return
  if (keywordTimer) clearTimeout(keywordTimer)
  filters.keyword = keyword
  void load().then(scrollToPageHead)
})

onMounted(() => {
  void loadFacets()
  void load()
})
onUnmounted(() => {
  if (keywordTimer) clearTimeout(keywordTimer)
})
</script>

<style scoped>
.list-title {
  margin: 12px 0 4px;
}

/* V4 pg-sec-h 分区头（预览 §04）：展示字小标 + 计数 + more，底部发丝线收边 */
.venue-section .sg-secband {
  margin: 26px 0 12px;
  padding-bottom: 9px;
  border-bottom: 1px solid var(--sg-border);
  gap: 12px;
}

.venue-section .sg-secband h4 {
  font-family: var(--sg-font-display);
  font-weight: 400;
  letter-spacing: 0.015em;
}

.venue-page-head {
  align-items: flex-end;
}

.venue-count {
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-sm);
  white-space: nowrap;
}

.sg-state-error strong,
.sg-state-error span {
  display: block;
}

.sg-state-error span {
  margin-top: 6px;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-base);
}

/* 高级条件折叠（预览 details.adv）：默认收起，开放状态下拉藏于其内 */
.venue-adv {
  margin: 0;
}

.venue-adv summary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 2px;
  list-style: none;
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-sm);
  font-weight: 600;
  cursor: pointer;
}

.venue-adv summary::-webkit-details-marker {
  display: none;
}

.venue-adv summary:hover,
.venue-adv[open] summary {
  color: var(--sg-primary);
}

.venue-adv summary:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: 2px;
}

.venue-adv-caret {
  font-size: 10px;
  translate: 0 -1px;
}

.venue-adv-body {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 8px;
}

/* 类型分区版面（V4 §04 场馆列表原型）：分区网格（vgrid），3.5% tint 卡无描边，hover 加深上浮 */
.venue-grid {
  display: grid;
  /* V6 F-09（预览 §02 网格规则）上限列宽 + 整组居中。注意 auto-fit 列数按「上限宽」参与计算
     （minmax(300px,356px) 在 1080 版心恒得 2 列 356px 大卡——走查 2026-08-30 用户反馈「一行两张太疏」），
     上限收到 350 后 1080 版心恰为 3 列整组居中；min(300px,100%) 防窄视口单列溢出。 */
  grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 350px));
  justify-content: center;
  gap: 14px;
  margin-top: 2px;
  padding: 4px 0 6px;
}

/* V5 律一 · 版心分级：浏览型页面 1200 → 1080 */
.venue-page.page-shell {
  width: min(1080px, calc(100% - 32px));
}

.rail-peek {
  display: none;
}

.venue-card {
  display: block;
  padding: 0 0 13px;
  border-radius: var(--sg-radius-card);
  background: color-mix(in srgb, var(--sg-ink) 3.5%, transparent);
  overflow: hidden;
  color: inherit;
  text-decoration: none;
  transition: transform var(--sg-motion-base) var(--sg-ease-spring), background-color var(--sg-motion-fast) var(--sg-ease-standard);
}

.venue-card:hover {
  transform: translateY(-4px);
  background: color-mix(in srgb, var(--sg-ink) 6%, transparent);
}

.venue-card:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: 2px;
}

/* 海报缩版：馆型 wash + 纹样 hero 平铺 + 右下价格章（预览 v-card 构图） */
.venue-hero {
  display: block;
  position: relative;
  /* V5 §04 卡面语言：wash 面高 150px（与首页轨道卡一致） */
  height: 150px;
  display: grid;
  place-items: center;
  overflow: hidden;
  /* 上缘跟卡面档，下缘与卡体相接（§06 圆角违例同类清零） */
  border-radius: var(--sg-radius-card) var(--sg-radius-card) 0 0;
}

.venue-card[data-wash='mist'] .venue-hero {
  background: linear-gradient(150deg, color-mix(in srgb, var(--sg-mist) 15%, transparent), transparent 72%), color-mix(in srgb, var(--sg-mist) 9%, transparent);
  color: var(--sg-mist);
}

.venue-card[data-wash='pine'] .venue-hero {
  background: linear-gradient(150deg, color-mix(in srgb, var(--sg-pine) 15%, transparent), transparent 72%), color-mix(in srgb, var(--sg-pine) 9%, transparent);
  color: var(--sg-pine);
}

.venue-card[data-wash='honey'] .venue-hero {
  background: linear-gradient(150deg, color-mix(in srgb, var(--sg-honey) 16%, transparent), transparent 72%), color-mix(in srgb, var(--sg-honey) 10%, transparent);
  color: var(--sg-honey);
}

.venue-hero-motif {
  width: clamp(64px, 9vw, 84px);
  height: clamp(64px, 9vw, 84px);
  stroke: currentColor;
  opacity: 0.9;
}

.venue-price-chip {
  position: absolute;
  right: 8px;
  bottom: 8px;
  padding: 2px 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--sg-canvas) 92%, transparent);
  color: var(--sg-primary);
  font-size: var(--sg-fz-xs);
  font-weight: 700;
}

/* 卡底行文（V5 §04 .cb/.nm）：名称（展示字 17px）与元信息（13px 弱色）同行接排。
   必须 display:block：行内 span 的 padding 不作用于换行后的续行（走查 2026-08-30 F-04，
   元信息两行时第二行顶出卡缘），块化后各行共享内边距、垂直 padding 亦按设计生效。 */
.venue-body {
  display: block;
  padding: 12px 16px 15px;
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-sm);
  line-height: 1.6;
}

.venue-name {
  margin-right: 6px;
  font-family: var(--sg-font-display);
  font-size: 17px;
  font-weight: 400;
  letter-spacing: 0.02em;
  color: var(--sg-ink);
}

.venue-cmeta {
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-sm);
}

.venue-cmeta span + span::before {
  content: ' · ';
}

.venue-price {
  white-space: nowrap;
}

.venue-price b {
  color: var(--sg-primary);
  font-family: var(--sg-font-serif);
  font-size: var(--sg-fz-base);
  font-weight: 700;
}

@media (max-width: 640px) {
  .sg-state-error {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
