<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, ChatDotRound, Collection, Connection, Document,
  EditPen, Flag, Lock, Plus, Refresh, Remove, Right, Search, TopRight, View
} from '@element-plus/icons-vue'
import {
  communityApi,
  communityError,
  createIdempotencyAttempt,
  type BlockedUser,
  type CommunityGroup,
  type CommunityPost,
  type CommunityReport
} from '@/api/community'
import { systemApi } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import NativeDialog from './dialogs/NativeDialog.vue'
import ComposeDialog from './dialogs/ComposeDialog.vue'
import GroupFormDialog from './dialogs/GroupFormDialog.vue'
import {
  avatarColorClass,
  excerptOf,
  initialOf,
  joinActionText,
  listTimeText,
  memberBadgeText,
  reportStateText,
  reportTargetTypeText,
  sceneForGroup,
  visibilityLabel
} from './communityShared'

/**
 * 社区首页（冻结参考 #feed/#groups/#mine/#my-posts/#reports/#blocks 六表面）。
 * 数据全部来自服务端合同：阅读流仅广场可见帖、我的小组含等待审核（memberStatus=ANY）、
 * 举报目标摘要按 G3 在前端解析且失败降级为「内容已不可见」，不使用任何夹具数据。
 */
type Rail = 'feed' | 'groups' | 'mine' | 'my-posts' | 'reports' | 'blocks'

const RAIL_ENTRIES: Array<{ key: Rail; label: string; icon: unknown }> = [
  { key: 'feed', label: '大家的分享', icon: ChatDotRound },
  { key: 'groups', label: '发现小组', icon: Connection },
  { key: 'mine', label: '我的小组', icon: Collection },
  { key: 'my-posts', label: '我的帖子', icon: Document }
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const rail = computed<Rail>(() => {
  const value = String(route.query.rail ?? 'feed')
  return (['feed', 'groups', 'mine', 'my-posts', 'reports', 'blocks'] as Rail[]).includes(value as Rail)
    ? (value as Rail)
    : 'feed'
})
const queryText = computed(() => String(route.query.q ?? '').trim())
const category = computed(() => String(route.query.category ?? '').trim())

const searchDraft = ref('')
const categories = ref<Array<{ value: string; label: string }>>([])
const categoryTabs = computed(() => [
  { value: '', label: '全部' },
  ...categories.value,
  { value: 'OTHER', label: '其他' }
])

const posts = ref<CommunityPost[]>([])
const groups = ref<CommunityGroup[]>([])
const reports = ref<CommunityReport[]>([])
const blocks = ref<BlockedUser[]>([])
const asideGroups = ref<CommunityGroup[]>([])
const reportTitles = reactive(new Map<string, string>())

const loading = ref(false)
const errorState = ref<{ kind: string; message: string } | null>(null)
const nextCursor = ref<number | null>(null)
const hasMore = ref(false)
const loadingMore = ref(false)

const showSearch = computed(() => ['feed', 'groups', 'mine', 'my-posts'].includes(rail.value))
const isPostRail = computed(() => rail.value === 'feed' || rail.value === 'my-posts')

const railQuery = (target: Rail) => {
  const query: Record<string, string> = {}
  if (target !== 'feed') query.rail = target
  return query
}

const pushQuery = (updates: Record<string, string | undefined>) => {
  const query: Record<string, string> = {}
  const merged: Record<string, string | undefined> = {
    rail: route.query.rail as string | undefined,
    q: route.query.q as string | undefined,
    category: route.query.category as string | undefined,
    ...updates
  }
  for (const [key, value] of Object.entries(merged)) {
    if (value && !(key === 'rail' && value === 'feed')) query[key] = value
  }
  router.push({ path: '/community', query })
}

const applySearch = () => pushQuery({ q: searchDraft.value.trim() || undefined })
const applyCategory = (value: string) => pushQuery({ category: value || undefined })
const clearFilters = () => pushQuery({ q: undefined, category: undefined })

/* ---------- 读取 ---------- */

const loadError = (error: unknown, fallback: string) => {
  const state = communityError(error, fallback)
  errorState.value = state.kind === 'forbidden'
    ? { kind: 'forbidden', message: '当前账号没有社区访问权限。' }
    : { kind: 'failed', message: state.message }
}

const loadSurface = async () => {
  loading.value = true
  errorState.value = null
  posts.value = []
  groups.value = []
  reports.value = []
  blocks.value = []
  nextCursor.value = null
  hasMore.value = false
  try {
    switch (rail.value) {
      case 'feed': {
        const page = await communityApi.posts({
          visibilityScope: 'PLATFORM_VISIBLE',
          category: category.value || undefined,
          titlePrefix: queryText.value || undefined,
          limit: 20
        })
        posts.value = page.records
        nextCursor.value = page.nextCursor
        hasMore.value = page.hasMore
        break
      }
      case 'my-posts': {
        if (auth.user?.id == null) break
        const page = await communityApi.posts({
          authorId: auth.user.id,
          category: category.value || undefined,
          titlePrefix: queryText.value || undefined,
          limit: 20
        })
        posts.value = page.records
        nextCursor.value = page.nextCursor
        hasMore.value = page.hasMore
        break
      }
      case 'groups':
      case 'mine': {
        const page = await communityApi.groups({
          mine: rail.value === 'mine' ? true : undefined,
          memberStatus: rail.value === 'mine' ? 'ANY' : undefined,
          category: category.value || undefined,
          namePrefix: queryText.value || undefined,
          limit: 20
        })
        groups.value = page.records
        nextCursor.value = page.nextCursor
        hasMore.value = page.hasMore
        break
      }
      case 'reports': {
        const page = await communityApi.myReports({ limit: 20 })
        reports.value = page.records
        nextCursor.value = page.nextCursor
        hasMore.value = page.hasMore
        resolveReportTitles(page.records)
        break
      }
      case 'blocks': {
        const page = await communityApi.blockedUsers({ limit: 20 })
        blocks.value = page.records
        nextCursor.value = page.nextCursor
        hasMore.value = page.hasMore
        break
      }
    }
  } catch (error) {
    loadError(error, '加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

const loadMore = async () => {
  if (!hasMore.value || nextCursor.value == null || loadingMore.value) return
  loadingMore.value = true
  try {
    const cursorId = nextCursor.value
    if (isPostRail.value) {
      const page = await communityApi.posts({
        visibilityScope: rail.value === 'feed' ? 'PLATFORM_VISIBLE' : undefined,
        authorId: rail.value === 'my-posts' ? auth.user?.id ?? undefined : undefined,
        category: category.value || undefined,
        titlePrefix: queryText.value || undefined,
        cursorId,
        limit: 20
      })
      posts.value = [...posts.value, ...page.records]
      nextCursor.value = page.nextCursor
      hasMore.value = page.hasMore
    } else if (rail.value === 'groups' || rail.value === 'mine') {
      const page = await communityApi.groups({
        mine: rail.value === 'mine' ? true : undefined,
        memberStatus: rail.value === 'mine' ? 'ANY' : undefined,
        category: category.value || undefined,
        namePrefix: queryText.value || undefined,
        cursorId,
        limit: 20
      })
      groups.value = [...groups.value, ...page.records]
      nextCursor.value = page.nextCursor
      hasMore.value = page.hasMore
    } else if (rail.value === 'reports') {
      const page = await communityApi.myReports({ cursorId, limit: 20 })
      reports.value = [...reports.value, ...page.records]
      nextCursor.value = page.nextCursor
      hasMore.value = page.hasMore
      resolveReportTitles(page.records)
    } else if (rail.value === 'blocks') {
      const page = await communityApi.blockedUsers({ cursorId, limit: 20 })
      blocks.value = [...blocks.value, ...page.records]
      nextCursor.value = page.nextCursor
      hasMore.value = page.hasMore
    }
  } catch (error) {
    ElMessage({ message: communityError(error, '加载更多失败，请稍后重试').message, type: 'error' })
  } finally {
    loadingMore.value = false
  }
}

/* G3：举报目标摘要在前端按既有详情端点解析；目标不可见时降级占位，不开新枚举接口。 */
const resolveReportTitles = (items: CommunityReport[]) => {
  for (const report of items) {
    const key = `${report.targetType}:${report.targetId}`
    if (reportTitles.has(key)) continue
    if (report.targetType === 'COMMENT') {
      reportTitles.set(key, '一条评论')
      continue
    }
    reportTitles.set(key, '加载中…')
    const loader = report.targetType === 'GROUP'
      ? communityApi.group(report.targetId).then((group) => group.name)
      : communityApi.post(report.targetId).then((post) => post.title)
    loader
      .then((title) => reportTitles.set(key, title))
      .catch(() => reportTitles.set(key, '内容已不可见'))
  }
}

const reportTitleOf = (report: CommunityReport) =>
  reportTitles.get(`${report.targetType}:${report.targetId}`) ?? '加载中…'

const loadAside = async () => {
  try {
    const page = await communityApi.groups({ mine: true, memberStatus: 'ACTIVE', limit: 3 })
    asideGroups.value = page.records
  } catch {
    asideGroups.value = []
  }
}

const loadCategories = async () => {
  try {
    const dict = await systemApi.dict('community_group_category')
    categories.value = dict.map((item) => ({ value: item.value, label: item.label }))
  } catch {
    categories.value = []
  }
}

/* ---------- 屏蔽（真实命令；失败/未知结果硬化在 S05） ---------- */

const unblockAttempt = createIdempotencyAttempt('unblock')
const unblockingId = ref<number | null>(null)
const unblock = async (row: BlockedUser) => {
  if (row.user.id == null || unblockingId.value != null) return
  unblockingId.value = row.user.id
  try {
    await communityApi.unblock(row.user.id, unblockAttempt.keyFor({ userId: row.user.id }))
    unblockAttempt.complete()
    blocks.value = blocks.value.filter((item) => item.id !== row.id)
    ElMessage({ message: '已取消屏蔽', type: 'success' })
  } catch (error) {
    ElMessage({ message: communityError(error, '操作失败，请稍后重试').message, type: 'error' })
  } finally {
    unblockingId.value = null
  }
}

/* ---------- 对话框 ---------- */

const composeOpen = ref(false)
const eligibleGroups = ref<CommunityGroup[]>([])
const groupFormOpen = ref(false)
const guidelinesOpen = ref(false)

const openCompose = async () => {
  try {
    const page = await communityApi.groups({ mine: true, memberStatus: 'ACTIVE', limit: 50 })
    eligibleGroups.value = page.records
  } catch {
    eligibleGroups.value = []
  }
  composeOpen.value = true
}

const onPublished = (post: CommunityPost) => {
  router.push({ path: `/community/posts/${post.id}`, query: { from: 'my-posts' } })
}

watch([rail, queryText, category], () => {
  loadSurface()
})

onMounted(() => {
  searchDraft.value = queryText.value
  loadSurface()
  loadAside()
  loadCategories()
})
</script>

<template>
  <div class="sgv-community" data-route-focus tabindex="-1">
    <div class="shell page-arrive">
      <section class="page-heading">
        <div>
          <h1>社区<span class="dot-stop">.</span></h1>
          <p>留下一点日常，遇见与你合拍的人。</p>
        </div>
        <div class="heading-actions">
          <button type="button" class="text-button" data-test="create-group" @click="groupFormOpen = true">
            <span class="icon"><el-icon><Plus /></el-icon></span>创建小组
          </button>
          <button type="button" class="button primary" data-test="compose" aria-label="发布帖子" @click="openCompose">
            <span class="icon"><el-icon><EditPen /></el-icon></span><span class="button-label">发布帖子</span>
          </button>
        </div>
      </section>

      <div class="community-grid">
        <aside class="community-nav" aria-label="社区导航">
          <nav class="rail-list">
            <a
              v-for="entry in RAIL_ENTRIES"
              :key="entry.key"
              :class="{ active: rail === entry.key }"
              :aria-current="rail === entry.key ? 'page' : undefined"
              :data-test="`rail-${entry.key}`"
              href="#"
              @click.prevent="pushQuery({ rail: entry.key === 'feed' ? undefined : entry.key, q: undefined, category: undefined })"
            >
              <span class="icon"><el-icon><component :is="entry.icon" /></el-icon></span>{{ entry.label }}
            </a>
          </nav>
          <div class="rail-separator" aria-hidden="true" />
          <p class="rail-caption">我的社区记录</p>
          <nav class="rail-list rail-secondary">
            <a :class="{ active: rail === 'reports' }" href="#" data-test="rail-reports" @click.prevent="pushQuery({ rail: 'reports', q: undefined, category: undefined })">
              <span class="icon"><el-icon><Flag /></el-icon></span>我的举报
            </a>
            <a :class="{ active: rail === 'blocks' }" href="#" data-test="rail-blocks" @click.prevent="pushQuery({ rail: 'blocks', q: undefined, category: undefined })">
              <span class="icon"><el-icon><Remove /></el-icon></span>屏蔽名单
            </a>
          </nav>
          <p class="rail-note"><strong>相遇，从一句话开始。</strong><br>在这里，留一点日常，<br>也听听别人的生活。</p>
        </aside>

        <section class="feed-column" :aria-label="isPostRail ? '社区内容' : '社区列表'">
          <form v-if="showSearch" class="search-form" role="search" @submit.prevent="applySearch">
            <input
              v-model="searchDraft"
              name="query"
              type="search"
              :aria-label="isPostRail ? '搜索帖子标题' : '搜索小组名称'"
              :placeholder="isPostRail ? '搜索一篇分享，遇见一点共鸣' : '找一个与你合拍的小组'"
              maxlength="80"
            >
            <button class="icon-button search-submit" type="submit" aria-label="搜索" title="搜索">
              <el-icon><Search /></el-icon>
            </button>
          </form>
          <div v-if="showSearch" class="filter-line" aria-label="内容类别">
            <button
              v-for="tab in categoryTabs"
              :key="tab.value || 'all'"
              type="button"
              :aria-pressed="category === tab.value || (!category && !tab.value)"
              :data-test="`category-${tab.value || 'all'}`"
              @click="applyCategory(tab.value)"
            >{{ tab.label }}</button>
            <span class="feed-order">最近发布 <span class="icon"><el-icon><ArrowDown /></el-icon></span></span>
          </div>

          <!-- 加载 -->
          <template v-if="loading">
            <div v-for="index in 3" :key="index" class="skeleton" role="status" aria-label="正在加载">
              <span /><span /><span /><span />
            </div>
          </template>

          <!-- 失败 / 无权限 -->
          <template v-else-if="errorState">
            <div v-if="errorState.kind === 'forbidden'" class="empty-state" data-test="state-forbidden">
              <span class="icon"><el-icon><Lock /></el-icon></span>
              <h2>暂时无法查看社区</h2>
              <p>{{ errorState.message }}</p>
              <router-link class="button" to="/">回到发现首页 <span class="icon"><el-icon><Right /></el-icon></span></router-link>
            </div>
            <div v-else class="empty-state" data-test="state-error">
              <span class="icon"><el-icon><Refresh /></el-icon></span>
              <h2>暂时没能加载出来</h2>
              <p>你的浏览条件还在，稍后再试一次。</p>
              <button type="button" class="button" @click="loadSurface">重新加载 <span class="icon"><el-icon><Refresh /></el-icon></span></button>
            </div>
          </template>

          <!-- 帖子流（feed / my-posts） -->
          <template v-else-if="isPostRail">
            <template v-if="posts.length">
              <div data-test="post-list">
                <article v-for="(post, index) in posts" :key="post.id" class="post-row" :class="{ lead: index === 0 }">
                  <div class="post-source">
                    <router-link :to="`/community/groups/${post.groupId}`">{{ post.groupName }}</router-link>
                    <span class="source-dot" aria-hidden="true" />
                    <span class="icon"><el-icon><Lock v-if="post.groupVisibility === 'MEMBERS_ONLY'" /><View v-else /></el-icon></span>
                    <span>{{ visibilityLabel(post.groupVisibility) }}</span>
                  </div>
                  <div class="post-body">
                    <div class="post-copy">
                      <h2>
                        <router-link :to="{ path: `/community/posts/${post.id}`, query: { from: rail === 'feed' ? undefined : 'my-posts' } }">
                          {{ post.title }}
                        </router-link>
                      </h2>
                      <p>{{ excerptOf(post.content) }}</p>
                    </div>
                    <router-link class="post-art" :to="{ path: `/community/posts/${post.id}`, query: { from: rail === 'feed' ? undefined : 'my-posts' } }" tabindex="-1" aria-hidden="true">
                      <ShiguangArt :scene="sceneForGroup(post.groupCategory)" />
                    </router-link>
                  </div>
                  <div class="post-footer">
                    <span class="avatar" :class="avatarColorClass(post.authorUserId)">{{ initialOf(post.author?.nickname) }}</span>
                    <span class="author">{{ post.author?.nickname ?? '社区用户' }}</span>
                    <span class="source-dot" aria-hidden="true" />
                    <time>{{ listTimeText(post.publishedAt ?? post.createTime) }}</time>
                    <router-link class="read-link" :to="{ path: `/community/posts/${post.id}`, query: { from: rail === 'feed' ? undefined : 'my-posts' } }">
                      读全文 <span class="icon"><el-icon><TopRight /></el-icon></span>
                    </router-link>
                  </div>
                </article>
              </div>
              <div class="feed-end">
                <button v-if="hasMore" type="button" class="text-button" :disabled="loadingMore" data-test="load-more" @click="loadMore">
                  再看看更多分享 <span class="icon"><el-icon><ArrowDown /></el-icon></span>
                </button>
                <span v-else>这一页的分享，已经看完了。</span>
              </div>
            </template>
            <div v-else class="empty-state" data-test="state-empty">
              <span class="icon"><el-icon><ChatDotRound /></el-icon></span>
              <h2>{{ queryText ? '没有找到这篇分享' : rail === 'my-posts' ? '这里还很安静' : '还没有新的分享' }}</h2>
              <p>{{ queryText ? '换一个标题关键词，或看看其他类别。' : rail === 'my-posts' ? '第一篇分享，可以从今天的一件小事开始。' : '把今天的小事写下来，留给同样在过日子的人。' }}</p>
              <button v-if="queryText" type="button" class="button" @click="clearFilters">清除筛选 <span class="icon"><el-icon><Right /></el-icon></span></button>
              <button v-else type="button" class="button" @click="openCompose">发布帖子 <span class="icon"><el-icon><Right /></el-icon></span></button>
            </div>
          </template>

          <!-- 小组列表（groups / mine） -->
          <template v-else-if="rail === 'groups' || rail === 'mine'">
            <template v-if="groups.length">
              <article v-for="group in groups" :key="group.id" class="group-card" data-test="group-card">
                <router-link class="group-art" :to="`/community/groups/${group.id}`" aria-hidden="true" tabindex="-1">
                  <ShiguangArt :scene="sceneForGroup(group.categoryCode)" />
                </router-link>
                <div>
                  <h2><router-link class="text-link" :to="`/community/groups/${group.id}`">{{ group.name }}</router-link></h2>
                  <p>{{ group.description || '暂无简介' }}</p>
                  <div class="meta-line">
                    <span>{{ group.memberCount ?? 0 }} 位成员</span>
                    <span>
                      <span class="icon"><el-icon><Lock v-if="group.visibility === 'MEMBERS_ONLY'" /><View v-else /></el-icon></span>
                      {{ visibilityLabel(group.visibility) }}
                    </span>
                    <span :class="{ badge: true, pending: group.currentMemberStatus === 'PENDING' }">
                      {{ memberBadgeText(group, auth.user?.id) }}
                    </span>
                  </div>
                </div>
                <router-link class="icon-button" :to="`/community/groups/${group.id}`" :aria-label="`查看${group.name}`" :title="`查看${group.name}`">
                  <el-icon><TopRight /></el-icon>
                </router-link>
              </article>
              <div class="feed-end">
                <button v-if="hasMore" type="button" class="text-button" :disabled="loadingMore" data-test="load-more" @click="loadMore">
                  再看看更多小组 <span class="icon"><el-icon><ArrowDown /></el-icon></span>
                </button>
                <span v-else-if="groups.length > 3">这一页的小组，已经看完了。</span>
              </div>
            </template>
            <div v-else class="empty-state" data-test="state-empty">
              <span class="icon"><el-icon><Connection /></el-icon></span>
              <h2>还没找到合拍的小组</h2>
              <p>{{ queryText || category ? '换一个关键词，或清除筛选看看更多同好。' : '换一个关键词，或创建一个属于你的小组。' }}</p>
              <button v-if="queryText || category" type="button" class="button" @click="clearFilters">清除筛选 <span class="icon"><el-icon><Right /></el-icon></span></button>
              <button v-else type="button" class="button" @click="groupFormOpen = true">创建小组 <span class="icon"><el-icon><Right /></el-icon></span></button>
            </div>
          </template>

          <!-- 我的举报 -->
          <template v-else-if="rail === 'reports'">
            <div class="list-heading" data-test="reports-heading">
              <h2>我的举报</h2>
              <span class="small muted">{{ reports.length }}{{ hasMore ? '+' : '' }} 条记录</span>
            </div>
            <template v-if="reports.length">
              <article v-for="report in reports" :key="report.id" class="record-row" data-test="report-row">
                <span class="icon"><el-icon><Flag /></el-icon></span>
                <div>
                  <h3>{{ reportTitleOf(report) }}</h3>
                  <p>{{ reportTargetTypeText(report.targetType) }} · {{ report.reason }}</p>
                </div>
                <span class="badge" :class="{ pending: reportStateText(report.status) === '处理中' }">{{ reportStateText(report.status) }}</span>
              </article>
              <div class="feed-end">
                <button v-if="hasMore" type="button" class="text-button" :disabled="loadingMore" @click="loadMore">
                  加载更多记录 <span class="icon"><el-icon><ArrowDown /></el-icon></span>
                </button>
              </div>
            </template>
            <div v-else class="empty-state" data-test="state-empty">
              <span class="icon"><el-icon><Flag /></el-icon></span>
              <h2>没有举报记录</h2>
              <p>你提交的举报和处理结果，会留在这里。</p>
              <button type="button" class="button" @click="pushQuery({ rail: undefined })">去看看分享 <span class="icon"><el-icon><Right /></el-icon></span></button>
            </div>
          </template>

          <!-- 屏蔽名单 -->
          <template v-else-if="rail === 'blocks'">
            <div class="list-heading" data-test="blocks-heading">
              <h2>屏蔽名单</h2>
              <span class="small muted">{{ blocks.length }}{{ hasMore ? '+' : '' }} 人</span>
            </div>
            <template v-if="blocks.length">
              <article v-for="row in blocks" :key="row.id" class="member-row" data-test="block-row">
                <span class="avatar" :class="avatarColorClass(row.user.id)">{{ initialOf(row.user.nickname) }}</span>
                <div>
                  <h3>{{ row.user.nickname }}</h3>
                  <p>已屏蔽</p>
                </div>
                <button type="button" class="text-button" :disabled="unblockingId === row.user.id" @click="unblock(row)">取消屏蔽</button>
              </article>
              <div class="feed-end">
                <button v-if="hasMore" type="button" class="text-button" :disabled="loadingMore" @click="loadMore">
                  加载更多 <span class="icon"><el-icon><ArrowDown /></el-icon></span>
                </button>
              </div>
            </template>
            <div v-else class="empty-state" data-test="state-empty">
              <span class="icon"><el-icon><Remove /></el-icon></span>
              <h2>屏蔽名单是空的</h2>
              <p>暂无已屏蔽的用户。</p>
              <button type="button" class="button" @click="pushQuery({ rail: undefined })">去看看分享 <span class="icon"><el-icon><Right /></el-icon></span></button>
            </div>
          </template>
        </section>

        <aside class="community-aside">
          <section class="aside-section">
            <div class="section-head">
              <h2>我的小组</h2>
              <a href="#" @click.prevent="pushQuery({ rail: 'mine' })">全部 <span class="icon"><el-icon><TopRight /></el-icon></span></a>
            </div>
            <router-link v-for="group in asideGroups" :key="group.id" class="group-short" :to="`/community/groups/${group.id}`">
              <span class="group-mark"><ShiguangArt :scene="sceneForGroup(group.categoryCode)" /></span>
              <div>
                <h3>{{ group.name }}</h3>
                <p>{{ group.memberCount ?? 0 }} 位成员{{ group.ownerUserId === auth.user?.id ? ' · 你创建的' : '' }}</p>
              </div>
            </router-link>
            <p v-if="!asideGroups.length" class="muted small">加入一个小组，这里就有它的位置。</p>
          </section>
          <section class="aside-section aside-gathering">
            <div class="section-head"><h2>把话题，带到生活里</h2></div>
            <div class="feature-art"><ShiguangArt scene="together" /></div>
            <h3>聊得来，<br>也可以见一面。</h3>
            <p>书里没聊完的话，留到下一次见面。看看小组里正在发起的相聚。</p>
            <router-link class="text-button accent" to="/activities">去看看活动 <span class="icon"><el-icon><TopRight /></el-icon></span></router-link>
          </section>
          <div class="aside-foot">
            <a href="#" @click.prevent="guidelinesOpen = true">社区公约</a>
            <span> · </span>
            <a href="#" @click.prevent="pushQuery({ rail: 'reports' })">举报与反馈</a>
            <p>让每一种日常，都被温柔以待。</p>
          </div>
        </aside>
      </div>
    </div>

    <ComposeDialog
      v-model:open="composeOpen"
      :eligible-groups="eligibleGroups"
      @published="onPublished"
    />
    <GroupFormDialog
      v-model:open="groupFormOpen"
      :categories="categories"
      @saved="(group) => router.push(`/community/groups/${group.id}`)"
    />
    <NativeDialog :open="guidelinesOpen" title="社区公约" @update:open="guidelinesOpen = $event">
      <p>尊重不同的日常，也尊重彼此的边界。围绕小组主题交流，不发布骚扰、广告或他人的私人信息。</p>
      <p style="margin-top:16px">遇到不合适的内容，可以举报；不想继续看到某位用户，也可以选择屏蔽。</p>
      <div class="dialog-actions">
        <button type="button" class="button" @click="guidelinesOpen = false">知道了</button>
      </div>
    </NativeDialog>
  </div>
</template>
