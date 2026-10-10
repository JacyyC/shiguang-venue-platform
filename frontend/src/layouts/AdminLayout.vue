<template>
  <div class="sga-framework" :class="{ 'fw-collapsed': app.sidebarCollapsed }">
    <a class="skip-link" href="#fw-main">跳到主要内容</a>

    <header class="fw-top">
      <button class="fw-icon-btn fw-mobile-menu" type="button" aria-label="打开后台导航" data-test="fw-mobile-menu" @click="openDrawer">
        <span>目录</span>
      </button>
      <router-link class="fw-brand-link" to="/" aria-label="回到用户端首页">
        <svg class="fw-brand" aria-hidden="true"><use href="#sgv-brand-sign" /></svg>
        <span class="fw-brand-label">运营工作空间</span>
      </router-link>
      <button v-if="activeWorkspace" class="fw-identity" type="button" aria-haspopup="dialog" data-test="fw-identity-open" @click="openIdentity($event)">
        <svg class="fw-icon" aria-hidden="true"><use :href="`#sgv-${activeWorkspace.icon}`" /></svg>
        <span>
          <strong>{{ activeWorkspace.label }}</strong>
          <small>{{ userName }} · 切换工作身份</small>
        </span>
        <svg class="fw-icon" aria-hidden="true"><use href="#sgv-arrow" /></svg>
      </button>
      <div class="fw-actions">
        <button class="fw-icon-btn" type="button" :aria-label="messageAriaLabel" data-test="fw-messages" @click="router.push('/me/messages')">
          <svg class="fw-icon" aria-hidden="true"><use href="#sgv-bell" /></svg>
          <span class="utility-label">消息 {{ unreadSuffix }}</span>
        </button>
        <button class="fw-icon-btn desktop-utility" type="button" aria-label="返回用户端" @click="router.push('/')">
          <svg class="fw-icon" aria-hidden="true"><use href="#sgv-cup" /></svg>
          <span class="utility-label">用户端</span>
        </button>
        <button class="fw-icon-btn" type="button" aria-label="切换明暗主题" :aria-pressed="theme.isDark" data-test="fw-theme" @click="theme.toggle()">
          <svg class="fw-icon" aria-hidden="true"><use href="#sgv-sun" /></svg>
        </button>
        <button class="fw-icon-btn" type="button" aria-label="账号与会话" data-test="fw-session" @click="openSession($event)">
          <svg class="fw-icon" aria-hidden="true"><use href="#sgv-shield" /></svg>
        </button>
      </div>
    </header>

    <aside class="fw-sidebar" aria-label="后台领域目录">
      <div class="fw-sidebar-top">
        <span>{{ activeWorkspace ? `${activeWorkspace.label}目录` : '后台目录' }}</span>
        <button class="fw-icon-btn" type="button" :aria-label="app.sidebarCollapsed ? '展开侧栏' : '收起侧栏'" :aria-expanded="!app.sidebarCollapsed" @click="app.toggleSidebar()">
          <svg class="fw-icon" aria-hidden="true"><use :href="app.sidebarCollapsed ? '#sgv-arrow' : '#sgv-back'" /></svg>
        </button>
      </div>
      <nav class="fw-nav" aria-label="后台业务导航">
        <p v-if="menu.loading && !menu.loaded" class="fw-nav-state">正在读取菜单…</p>
        <template v-else>
          <template v-for="group in menuGroups" :key="group.label">
            <p class="fw-group">{{ group.label }}</p>
            <template v-for="item in group.items" :key="item.path">
              <router-link
                v-if="!isItemLocked(item)"
                :to="item.path"
                :aria-current="isMenuActive(item.path) ? 'page' : undefined"
                :aria-label="menuDisplayName(item)"
              >
                <svg class="fw-icon" aria-hidden="true"><use :href="`#sgv-${navIcon(item.path)}`" /></svg>
                <span class="nav-label">{{ menuDisplayName(item) }}</span>
              </router-link>
              <span v-else class="locked" aria-disabled="true" title="尚未授权场馆">
                <svg class="fw-icon" aria-hidden="true"><use :href="`#sgv-${navIcon(item.path)}`" /></svg>
                <span class="nav-label">{{ menuDisplayName(item) }}</span>
              </span>
            </template>
          </template>
          <p v-if="!visibleMenus.length" class="fw-nav-state">当前没有后台菜单</p>
        </template>
      </nav>
      <div v-if="auth.user" class="fw-nav-foot">
        {{ userName }}<br>
        <span style="white-space: nowrap">{{ roleLabel }}</span>
      </div>
    </aside>

    <div class="fw-body">
      <div class="fw-scope">
        <div class="fw-breadcrumb">
          <span>{{ activeWorkspace ? activeWorkspace.label : '后台' }}</span>
          <span aria-hidden="true">/</span>
          <strong>{{ currentPageName }}</strong>
        </div>
        <button
          v-if="activeWorkspace"
          class="fw-identity platform-identity"
          type="button"
          aria-haspopup="dialog"
          data-test="fw-identity-open-mobile"
          @click="openIdentity($event)"
        >
          <svg class="fw-icon" aria-hidden="true"><use :href="`#sgv-${activeWorkspace.icon}`" /></svg>
          <span>
            <strong>{{ activeWorkspace.label }}</strong>
            <small>{{ userName }} · 切换工作身份</small>
          </span>
          <svg class="fw-icon" aria-hidden="true"><use href="#sgv-arrow" /></svg>
        </button>
        <div v-if="isVenueWorkspace" class="fw-venue-wrap">
          <label for="fw-venue">当前场馆</label>
          <select
            id="fw-venue"
            data-test="fw-venue"
            :disabled="!venueAccess.venues.length"
            :value="venueAccess.activeVenueId ?? ''"
            @change="onVenueChange(($event.target as HTMLSelectElement).value)"
          >
            <option v-if="!venueAccess.venues.length" value="">没有授权场馆</option>
            <option v-for="venue in venueAccess.venues" :key="venue.id" :value="venue.id">{{ venue.name }}</option>
          </select>
        </div>
        <span v-else class="muted fw-range">平台范围</span>
      </div>

      <main id="fw-main" ref="mainRef" class="fw-main" tabindex="-1">
        <template v-if="surfaceState?.lock">
          <header class="fw-heading-lock">
            <div class="eyebrow">空间运营 · 场馆范围</div>
            <h1>还没有授权场馆</h1>
            <p>场馆管理员身份已经具备，具体的管理场馆仍需由平台管理员授权。工作台可以进入，其他场馆菜单暂时不可用。</p>
          </header>
          <div class="fw-status">
            <h2>等待场馆授权</h2>
            <p>授权完成后重新读取可管理场馆清单，再进入对应业务。</p>
            <button class="btn" type="button" data-test="fw-recheck-venue" @click="recheckVenueAccess">重新核对场馆授权</button>
          </div>
        </template>
        <div v-else-if="surfaceState" class="fw-status" :role="surfaceState.role" :aria-live="surfaceState.live">
          <h1 v-if="surfaceState.heading1" style="margin-bottom:14px">{{ surfaceState.heading1 }}</h1>
          <h2 v-else>{{ surfaceState.title }}</h2>
          <p>{{ surfaceState.text }}</p>
          <button v-if="surfaceState.action" class="btn" :class="{ primary: surfaceState.primary }" type="button" :data-test="surfaceState.test" @click="surfaceState.action">
            {{ surfaceState.actionLabel }}
          </button>
        </div>
        <router-view v-else v-slot="{ Component, route: viewRoute }">
          <transition :name="fadeName" mode="out-in">
            <component :is="Component" :key="isVenueWorkspace ? `${viewRoute.fullPath}:${venueAccess.activeVenueId}` : viewRoute.fullPath" class="route-view" />
          </transition>
        </router-view>
      </main>
    </div>

    <dialog ref="drawerEl" class="fw-drawer" aria-label="后台导航" @close="onDrawerClose">
      <div class="fw-drawer-head">
        <svg class="fw-brand" aria-hidden="true"><use href="#sgv-brand-sign" /></svg>
        <button class="fw-icon-btn" type="button" aria-label="关闭导航" data-test="fw-drawer-close" @click="closeDrawer">
          <svg class="fw-icon" aria-hidden="true"><use href="#sgv-close" /></svg>
        </button>
      </div>
      <button
        v-if="activeWorkspace"
        class="fw-identity"
        type="button"
        aria-haspopup="dialog"
        @click="openIdentity(null, drawerEl)"
      >
        <svg class="fw-icon" aria-hidden="true"><use :href="`#sgv-${activeWorkspace.icon}`" /></svg>
        <span>
          <strong>{{ activeWorkspace.label }}</strong>
          <small>{{ userName }} · 切换工作身份</small>
        </span>
        <svg class="fw-icon" aria-hidden="true"><use href="#sgv-arrow" /></svg>
      </button>
      <nav class="fw-nav" aria-label="移动端后台导航">
        <template v-for="group in menuGroups" :key="group.label">
          <p class="fw-group">{{ group.label }}</p>
          <template v-for="item in group.items" :key="item.path">
            <router-link
              v-if="!isItemLocked(item)"
              :to="item.path"
              :aria-current="isMenuActive(item.path) ? 'page' : undefined"
              :aria-label="menuDisplayName(item)"
              @click="closeDrawer()"
            >
              <svg class="fw-icon" aria-hidden="true"><use :href="`#sgv-${navIcon(item.path)}`" /></svg>
              <span class="nav-label">{{ menuDisplayName(item) }}</span>
            </router-link>
            <span v-else class="locked" aria-disabled="true" title="尚未授权场馆">
              <svg class="fw-icon" aria-hidden="true"><use :href="`#sgv-${navIcon(item.path)}`" /></svg>
              <span class="nav-label">{{ menuDisplayName(item) }}</span>
            </span>
          </template>
        </template>
      </nav>
      <div v-if="auth.user" class="fw-nav-foot">
        {{ userName }}<br>
        <span style="white-space: nowrap">{{ roleLabel }}</span>
      </div>
    </dialog>

    <dialog ref="identityEl" class="fw-modal" aria-labelledby="fw-identity-title" @close="onModalClose(identityEl)">
      <h2 id="fw-identity-title">选择当前工作身份</h2>
      <p>{{ userName }}已具备：{{ roleLabel }}。</p>
      <p>切换的是工作区与工作视角，账号已有权限仍需在具体动作处核对。</p>
      <div class="modal-options" data-test="identity-options">
        <button
          v-for="target in adminTargets"
          :key="target.path"
          class="workspace-option"
          :class="{ selected: isWorkspaceActive(target.path) }"
          type="button"
          :data-test="`workspace-${target.path.slice(1)}`"
          @click="switchWorkspace(target.path)"
        >
          <svg class="fw-icon" aria-hidden="true"><use :href="`#sgv-${target.icon}`" /></svg>
          <span>
            <strong>{{ target.label }}</strong>
            <small>{{ target.description }}</small>
          </span>
          <span v-if="isWorkspaceActive(target.path)" class="badge">当前</span>
        </button>
        <p v-if="!adminTargets.length" class="muted">当前没有可进入的后台工作区。</p>
      </div>
      <div class="modal-actions">
        <button class="btn" type="button" @click="closeModal(identityEl)">关闭</button>
      </div>
    </dialog>

    <dialog ref="sessionEl" class="fw-modal" aria-labelledby="fw-session-title" @close="onModalClose(sessionEl)">
      <h2 id="fw-session-title">账号与会话</h2>
      <p>{{ userName }}</p>
      <p>{{ roleLabel }}</p>
      <p>退出会结束当前登录会话，并返回登录页。</p>
      <div class="modal-actions">
        <button class="btn" type="button" data-test="fw-context-open" @click="openContext">查看身份与范围</button>
        <button class="btn primary" type="button" data-test="fw-logout" @click="logoutFromSession">退出登录</button>
      </div>
    </dialog>

    <dialog ref="contextEl" class="fw-modal" aria-labelledby="fw-context-title" @close="onModalClose(contextEl)">
      <h2 id="fw-context-title">当前身份与访问范围</h2>
      <div class="fw-fact">
        <strong>账号角色</strong>
        <p>{{ roleLabel }}</p>
      </div>
      <div class="fw-fact">
        <strong>当前工作区</strong>
        <p>{{ activeWorkspace ? activeWorkspace.label : '无' }}</p>
      </div>
      <div class="fw-fact">
        <strong>场馆范围</strong>
        <p>{{ venueScopeText }}</p>
      </div>
      <div class="fw-fact">
        <strong>可访问的后台工作区</strong>
        <p>{{ adminTargets.map((target) => target.label).join('、') || '无' }}</p>
      </div>
      <p class="muted" style="margin-top:16px">动作仍按当前授权核对；拥有工作台入口不代表拥有此工作区的全部动作。</p>
      <div class="modal-actions">
        <button class="btn" type="button" @click="closeModal(contextEl)">关闭</button>
      </div>
    </dialog>

    <p class="fw-toast" role="status">{{ app.toastMessage }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { useMenuStore } from '@/stores/menu'
import { useVenueAccessStore } from '@/stores/venueAccess'
import { useThemeStore } from '@/stores/theme'
import { WORKSPACE_DEFS } from '@/utils/workspaceTargets'
import { useNotificationStream } from '@/composables/useNotificationStream'
import { canAccessContentAdmin } from '@/utils/contentAdminAccess'
import type { MenuNode } from '@/api/types'

interface MenuGroupRule {
  label: string
  paths: string[]
}

interface WorkspaceTarget {
  label: string
  description: string
  path: string
  icon: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
const theme = useThemeStore()
// 路由转场回归 `route` CSS 过渡（E2E-AUDIT-FIX-20260906 S05，D-02 根治：VT 容器转场退役）
const fadeName = 'route'
const menu = useMenuStore()
const venueAccess = useVenueAccessStore()
const mainRef = ref<HTMLElement>()
let lastUnreadCount: number | undefined

const drawerEl = ref<HTMLDialogElement | null>(null)
const identityEl = ref<HTMLDialogElement | null>(null)
const sessionEl = ref<HTMLDialogElement | null>(null)
const contextEl = ref<HTMLDialogElement | null>(null)
let lastTrigger: HTMLElement | null = null
let sessionTrigger: HTMLElement | null = null

const isSystemWorkspace = computed(() => route.path.startsWith('/system-admin'))
const isContentWorkspace = computed(() => route.path.startsWith('/content-admin'))
// 仅场馆工作区路由（/login 等非管理路径不算，避免登出过渡期误拉场馆清单）
const isVenueWorkspace = computed(() => route.path.startsWith('/venue-admin'))
// C-09：有场馆管理员角色但未绑定场馆时，侧边栏仅保留「场馆工作台」可用，其余菜单置灰禁点。
const venueMenuLocked = computed(() =>
  isVenueWorkspace.value &&
  auth.roles.includes('VENUE_ADMIN') &&
  !auth.roles.includes('SYSTEM_ADMIN') &&
  venueAccess.loaded &&
  venueAccess.venues.length === 0
)
const isItemLocked = (item: MenuNode) => venueMenuLocked.value && item.path !== '/venue-admin'
const canAccessContentWorkspace = computed(() => canAccessContentAdmin(auth.permissions))
const canAccessPlacementWorkspace = computed(() =>
  ['placement:manage', 'placement:publish', 'placement:audit:view'].some((permission) => auth.hasPermission(permission))
)
const canAccessAdvertisingAdmin = computed(() =>
  ['advertising:manage', 'advertising:review', 'advertising:publish', 'advertising:audit:view']
    .some((permission) => auth.hasPermission(permission))
)
const canAccessAdvertisingAnalysis = computed(() => auth.hasPermission('advertising:analysis:view'))
const rootPath = computed(() => isSystemWorkspace.value ? '/system-admin' : isContentWorkspace.value ? '/content-admin' : '/venue-admin')

// 预览 catalogs 的每项图标映射（sgv-* 符号），路径不在目录时回退 calendar
const NAV_ICONS: Record<string, string> = {
  '/venue-admin': 'calendar',
  '/venue-admin/reservations': 'calendar',
  '/venue-admin/checkins': 'check',
  '/venue-admin/spaces': 'pin',
  '/venue-admin/calendar': 'calendar',
  '/venue-admin/site-ops': 'cup',
  '/venue-admin/assets': 'bag',
  '/venue-admin/customer-service': 'cup',
  '/venue-admin/notices': 'bell',
  '/venue-admin/orders': 'bag',
  '/venue-admin/funnel': 'diagonal',
  '/system-admin': 'calendar',
  '/system-admin/users': 'shield',
  '/system-admin/roles': 'shield',
  '/system-admin/configs': 'calendar',
  '/system-admin/members': 'bag',
  '/system-admin/reward-catalog': 'bag',
  '/system-admin/promotions': 'calendar',
  '/system-admin/payments': 'bag',
  '/system-admin/reconciliation': 'check',
  '/system-admin/stored-value': 'bag',
  '/system-admin/notice-delivery': 'bell',
  '/system-admin/notice-experiments': 'bell',
  '/system-admin/providers': 'wifi',
  '/system-admin/logs': 'shield',
  '/content-admin': 'shield',
  '/content-admin/placements': 'bell',
  '/content-admin/advertising': 'bag',
  '/content-admin/advertising/analysis': 'diagonal'
}
const navIcon = (path: string) => NAV_ICONS[path] || 'calendar'

const groupRules = computed<MenuGroupRule[]>(() =>
  isContentWorkspace.value
    ? [
        { label: '社区治理', paths: ['/content-admin'] },
        { label: '宣传与广告', paths: ['/content-admin/placements', '/content-admin/advertising', '/content-admin/advertising/analysis'] }
      ]
    : isSystemWorkspace.value
    ? [
        { label: '平台概览', paths: ['/system-admin'] },
        { label: '平台治理', paths: ['/system-admin/users', '/system-admin/roles', '/system-admin/configs'] },
        { label: '会员营销', paths: ['/system-admin/members', '/system-admin/reward-catalog', '/system-admin/promotions', '/system-admin/promotion-pricing'] },
        { label: '支付财务', paths: ['/system-admin/payments', '/system-admin/reconciliation', '/system-admin/stored-value'] },
        { label: '消息触达', paths: ['/system-admin/notice-delivery', '/system-admin/notice-experiments'] },
        { label: '渠道与审计', paths: ['/system-admin/providers', '/system-admin/logs'] }
      ]    : [
        { label: '今日运营', paths: ['/venue-admin', '/venue-admin/reservations', '/venue-admin/checkins'] },
        { label: '场地库存', paths: ['/venue-admin/spaces', '/venue-admin/calendar'] },
        { label: '现场与资产', paths: ['/venue-admin/site-ops', '/venue-admin/assets'] },
        { label: '用户服务', paths: ['/venue-admin/customer-service', '/venue-admin/notices'] },
        { label: '经营分析', paths: ['/venue-admin/orders', '/venue-admin/funnel'] }
      ]
)

const visibleMenus = computed(() => {
  if (isContentWorkspace.value && canAccessContentWorkspace.value) {
    const workspace = {
      id: -1,
      parentId: 0,
      name: '举报处置',
      path: '/content-admin',
      component: 'ContentAdminDashboard',
      icon: 'ShieldCheck',
      perms: 'content-admin:view',
      type: 'MENU'
    } as MenuNode
    const placement = {
      id: -2,
      parentId: 0,
      name: '自营宣传位',
      path: '/content-admin/placements',
      component: 'PlacementDashboard',
      icon: 'Megaphone',
      type: 'MENU'
    } as MenuNode
    const advertising = {
      id: -4,
      parentId: 0,
      name: '广告管理',
      path: '/content-admin/advertising',
      component: 'AdvertisingAdminView',
      icon: 'BadgePercent',
      perms: 'advertising:manage',
      type: 'MENU'
    } as MenuNode
    const advertisingAnalysis = {
      id: -3,
      parentId: 0,
      name: '广告分析',
      path: '/content-admin/advertising/analysis',
      component: 'AdvertisingAnalysisView',
      icon: 'ChartNoAxesCombined',
      perms: 'advertising:analysis:view',
      type: 'MENU'
    } as MenuNode
    return [
      workspace,
      ...(canAccessPlacementWorkspace.value ? [placement] : []),
      ...(canAccessAdvertisingAdmin.value ? [advertising] : []),
      ...(canAccessAdvertisingAnalysis.value ? [advertisingAnalysis] : [])
    ]
  }
  const root = menu.findByPath(rootPath.value)
  if (!root) return []
  const children = root.children?.filter((item) => item.type === 'MENU') || []
  return [root, ...children] as MenuNode[]
})

const menuGroups = computed(() => {
  const claimed = new Set<string>()
  const groups = groupRules.value.map((group) => {
    const items = group.paths
      .map((path) => visibleMenus.value.find((item) => normalizePath(item.path) === path))
      .filter((item): item is MenuNode => Boolean(item))
    items.forEach((item) => claimed.add(item.path))
    return { label: group.label, items }
  }).filter((group) => group.items.length)
  const fallback = visibleMenus.value.filter((item) => !claimed.has(item.path))
  if (fallback.length) groups.push({ label: '其他功能', items: fallback })
  return groups
})

const menuDisplayName = (item: MenuNode) => item.path === '/system-admin/providers' ? '渠道观测' : item.name

const currentPageName = computed(() => {
  const matched = visibleMenus.value.find((item) => isMenuActive(item.path))
  return matched ? menuDisplayName(matched) : (isSystemWorkspace.value ? '系统工作台' : isContentWorkspace.value ? '内容治理' : '运营工作台')
})
const roleLabel = computed(() => {
  const labels = auth.roles.map((role) => ({
    SYSTEM_ADMIN: '系统管理员',
    VENUE_ADMIN: '场馆管理员',
    USER: '普通用户'
  }[role] || role))
  return [...new Set(labels)].join(' / ') || '后台用户'
})
const userName = computed(() => auth.user?.nickname || auth.user?.username || '管理员')
const compactCount = computed(() => (app.unreadCount > 99 ? '99+' : String(app.unreadCount)))
const unreadSuffix = computed(() => (app.unreadCount ? String(compactCount.value) : ''))
const messageAriaLabel = computed(() => (app.unreadCount ? `消息，${app.unreadCount} 条未读` : '消息'))
const venueScopeText = computed(() => {
  if (!isVenueWorkspace.value) return '平台范围'
  const names = venueAccess.venues.map((venue) => venue.name)
  return names.join('、') || '没有授权场馆'
})

// 工作身份（预览 workspaces 顺序与命名：空间运营/平台治理/内容治理；图标 pin/shield/bell）
const adminTargets = computed<WorkspaceTarget[]>(() => {
  const targets: WorkspaceTarget[] = []
  for (const def of WORKSPACE_DEFS) {
    if (def.key === 'content') {
      if (canAccessContentWorkspace.value) targets.push({ ...def })
      continue
    }
    if (menu.findByPath(def.path)) targets.push({ ...def })
  }
  return targets
})
const activeWorkspace = computed(() => adminTargets.value.find((target) => isWorkspaceActive(target.path)) || null)

const normalizePath = (path: string) => path.length > 1 ? path.replace(/\/$/, '') : path
const isMenuActive = (path: string) => {
  const normalized = normalizePath(path)
  if (normalized === rootPath.value) return route.path === normalized
  return route.path === normalized || route.path.startsWith(`${normalized}/`)
}
const isWorkspaceActive = (path: string) => route.path === path || route.path.startsWith(`${path}/`)

// —— 壳内四态（预览 body 状态合同）：无可进入后台 / 读取中 / 读取失败 / 入口不可访问 / 场馆锁定 ——
// 入口可访问性按守卫同一套 AND 语义复算（菜单不可用时允许缓存授权降级），拦截已挂载后的授权变化。
const entryAllowed = computed(() => {
  const meta = route.meta
  const menuKey = meta.menuKey as string | undefined
  const canUseCachedAuthorization = menu.unavailable
  if (menuKey && !menu.hasPermission(menuKey) && !(canUseCachedAuthorization && auth.hasPermission(menuKey))) return false
  const authorizationKey = meta.authorizationKey as string | undefined
  if (authorizationKey && !auth.hasPermission(authorizationKey)) return false
  const authorizationAny = meta.authorizationAny as string[] | undefined
  if (authorizationAny?.length && !authorizationAny.some((permission) => auth.hasPermission(permission))) return false
  const roles = (meta.roles as string[] | undefined) || []
  if (roles.length && !auth.hasAnyRole(roles)) return false
  return true
})

interface SurfaceState {
  heading1: string
  title: string
  text: string
  role?: string
  live?: 'polite' | 'assertive' | 'off'
  primary: boolean
  action: (() => void) | null
  actionLabel: string
  test: string
  lock?: boolean
}

const surfaceState = computed<SurfaceState | null>(() => {
  // 预览语义：读取中/读取失败优先于“无可进入后台”——菜单请求失败不代表账号没有权限
  if (menu.loading && !menu.loaded) {
    return {
      heading1: '',
      title: '正在核对导航与授权',
      text: '页面内容尚未就绪，当前不展示上一个账号或场馆的数据。',
      role: undefined,
      live: 'polite',
      primary: false,
      action: null,
      actionLabel: '',
      test: ''
    }
  }
  if (menu.unavailable) {
    return {
      heading1: '',
      title: '导航暂时无法读取',
      text: '这次读取失败，不代表账号没有权限。请重新读取后继续操作。',
      role: 'alert',
      live: undefined,
      primary: true,
      action: () => { void menu.loadMenus() },
      actionLabel: '重新读取',
      test: 'fw-menu-retry'
    }
  }
  if (menu.loaded && !adminTargets.value.length) {
    return {
      heading1: '当前没有可进入的后台',
      title: '',
      text: '这个账号可以使用用户端。后台入口需要相应角色和权限，不能从空菜单推导出管理能力。',
      role: undefined,
      live: undefined,
      primary: false,
      action: () => router.push('/'),
      actionLabel: '前往用户端',
      test: 'fw-go-user'
    }
  }
  if (!entryAllowed.value) {
    return {
      heading1: '',
      title: '当前入口不可访问',
      text: '当前角色与入口权限不满足这一页面的要求。不会保留之前页面中的受限内容。',
      role: undefined,
      live: undefined,
      primary: true,
      action: () => { const first = adminTargets.value[0]; if (first) void router.push(first.path) },
      actionLabel: '返回可访问工作台',
      test: 'fw-safe-home'
    }
  }
  if (venueMenuLocked.value) {
    return {
      heading1: '',
      title: '',
      text: '',
      role: undefined,
      live: undefined,
      primary: false,
      action: null,
      actionLabel: '',
      test: '',
      lock: true
    }
  }
  return null
})

// 壳层 toast：状态放 app store（App.vue 按顶层路由 key 重挂布局，组件内状态会随工作区切换丢失）
const shellToast = (text: string): void => {
  app.showToast(text)
}

// —— 弹层管理：原生 dialog + 焦点恢复（Escape 关闭回到触发钮） ——
function showModal(el: HTMLDialogElement | null, trigger: HTMLElement | null): void {
  if (!el || el.open) return
  lastTrigger = trigger ?? (document.activeElement as HTMLElement | null)
  el.showModal()
}

function closeModal(el: HTMLDialogElement | null): void {
  el?.close()
}

function onModalClose(el: HTMLDialogElement | null): void {
  if (el === sessionEl.value) sessionTrigger = null
  const trigger = lastTrigger
  lastTrigger = null
  if (trigger?.isConnected) trigger.focus()
  else mainRef.value?.focus({ preventScroll: true })
}

function openIdentity(trigger: Event | null, fallback?: HTMLDialogElement | null): void {
  if (fallback && fallback.open) fallback.close()
  showModal(identityEl.value, trigger?.target instanceof HTMLElement ? trigger.target : fallback ?? null)
}

function openSession(trigger: Event | null): void {
  sessionTrigger = trigger?.target instanceof HTMLElement ? trigger.target : null
  showModal(sessionEl.value, sessionTrigger)
}

function openContext(): void {
  const session = sessionEl.value
  sessionTrigger = sessionTrigger ?? lastTrigger
  session?.close()
  showModal(contextEl.value, sessionTrigger)
}

function logoutFromSession(): void {
  closeModal(sessionEl.value)
  void logout()
}

function openDrawer(): void {
  showModal(drawerEl.value, document.querySelector('[data-test="fw-mobile-menu"]'))
}

function closeDrawer(): void {
  drawerEl.value?.close()
}

function onDrawerClose(): void {
  const trigger = lastTrigger
  lastTrigger = null
  if (trigger?.isConnected) trigger.focus()
}

// —— 工作身份与场馆范围 ——
function switchWorkspace(path: string): void {
  const target = adminTargets.value.find((item) => item.path === path)
  closeModal(identityEl.value)
  if (!target || isWorkspaceActive(path)) return
  void router.push(path)
  shellToast(`已进入${target.label}；账号权限没有改变`)
}

function onVenueChange(value: string): void {
  const venueId = Number(value)
  const venue = venueAccess.venues.find((item) => item.id === venueId)
  if (!venue) return
  venueAccess.switchVenue(venue.id)
  shellToast(`当前范围已切换为${venue.name}`)
}

async function recheckVenueAccess(): Promise<void> {
  try {
    await venueAccess.refresh()
  } catch {
    // 清单读取失败维持锁定态
  }
  if (venueMenuLocked.value) shellToast('已重新核对，当前仍没有授权场馆。')
}

const refreshUnreadCount = async () => {
  if (!auth.isLoggedIn) {
    app.clearUnreadCount()
    lastUnreadCount = undefined
    return
  }
  try {
    await app.refreshUnreadCount()
  } catch {
    return
  }
  if (lastUnreadCount !== undefined && app.unreadCount > lastUnreadCount) {
    shellToast(`收到 ${app.unreadCount - lastUnreadCount} 条新的待处理消息`)
  }
  lastUnreadCount = app.unreadCount
}

// 场馆工作台可管理清单只在进入该工作区时拉取一次；失败静默，锁定态不生效即维持现状。
const ensureVenueScopeLoaded = () => {
  if (!auth.isLoggedIn || !venueAccess.loaded) void venueAccess.refresh().catch(() => undefined)
}

const focusMain = async () => {
  await nextTick()
  mainRef.value?.focus({ preventScroll: true })
}

const { start: startNotifications, stop: stopNotifications } = useNotificationStream({
  onNewMessage: () => {
    shellToast('收到 1 条新的待处理消息')
  },
  onPoll: () => refreshUnreadCount()
})

const logout = async () => {
  stopNotifications()
  await auth.logout()
  menu.reset()
  app.clearUnreadCount()
  venueAccess.reset()
  router.replace('/login')
}

watch(
  () => route.fullPath,
  async () => {
    closeDrawer()
    if (isVenueWorkspace.value) ensureVenueScopeLoaded()
    await refreshUnreadCount()
    await focusMain()
  }
)

onMounted(() => {
  startNotifications()
  if (isVenueWorkspace.value) ensureVenueScopeLoaded()
  void refreshUnreadCount().catch(() => undefined)
})

onBeforeUnmount(() => {
  stopNotifications()
})
</script>
