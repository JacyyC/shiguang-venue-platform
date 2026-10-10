<template>
  <div class="user-layout">
    <a class="skip-link" href="#user-main">跳到主要内容</a>

    <header class="user-header sg-intro-el">
      <router-link class="brand" to="/" aria-label="拾光 · 场地空间与活动平台首页">
        <span class="brand-mark" aria-hidden="true">拾<i>光</i></span>
        <span class="brand-copy">
          <strong>拾光 Shiguang</strong>
          <small>场地空间与活动平台</small>
        </span>
      </router-link>

      <nav ref="desktopNavRef" class="desktop-nav" aria-label="用户主导航">
        <router-link class="nav-link" to="/" :class="{ 'is-active': currentSection === 'home' }">
          <el-icon><House /></el-icon>
          <span>首页</span>
        </router-link>
        <router-link
          v-if="menu.hasPermission('venue:view')"
          class="nav-link"
          to="/venues"
          :class="{ 'is-active': currentSection === 'venues' }"
        >
          <el-icon><Location /></el-icon>
          <span>场馆</span>
        </router-link>
        <router-link
          v-if="menu.hasPermission('community:access')"
          class="nav-link"
          to="/community"
          :class="{ 'is-active': currentSection === 'discover' }"
        >
          <el-icon><Compass /></el-icon>
          <span>发现</span>
        </router-link>
        <router-link
          v-if="menu.hasPermission('community:access')"
          class="nav-link"
          to="/activities"
          :class="{ 'is-active': currentSection === 'activities' }"
        >
          <el-icon><Flag /></el-icon>
          <span>活动</span>
        </router-link>
        <router-link
          v-if="menu.hasPermission('reservation:my')"
          class="nav-link"
          to="/me/reservations"
          :class="{ 'is-active': currentSection === 'trips' }"
        >
          <el-icon><Clock /></el-icon>
          <span>行程</span>
        </router-link>
        <router-link class="nav-link" to="/me" :class="{ 'is-active': currentSection === 'me' }">
          <el-icon><User /></el-icon>
          <span>我的</span>
        </router-link>
        <span class="nav-indicator" aria-hidden="true" />
      </nav>

      <div class="header-actions">
        <ThemeToggle />
        <el-tooltip content="消息中心" placement="bottom">
          <button class="header-icon-button" type="button" :aria-label="messageAriaLabel" @click="router.push('/me/messages')">
            <el-icon><Bell /></el-icon>
            <span v-if="app.unreadCount" class="message-indicator" aria-hidden="true" />
          </button>
        </el-tooltip>

        <el-dropdown v-if="auth.user" trigger="click">
          <button class="profile-button" type="button" aria-label="打开个人菜单">
            <span class="profile-avatar" aria-hidden="true">{{ avatarText }}</span>
            <span class="profile-name">{{ auth.user.nickname || auth.user.username }}</span>
            <el-icon class="profile-chevron"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item class="profile-menu-item" :icon="User" @click="router.push('/me')">
                <span class="profile-menu-item-copy"><span>个人中心</span><small>我的页承载全部业务入口</small></span>
              </el-dropdown-item>
              <el-dropdown-item class="profile-menu-item" :icon="Setting" @click="router.push('/me/profile')">账号设置</el-dropdown-item>
              <el-dropdown-item class="profile-menu-item" :icon="ChatLineSquare" @click="router.push('/me/messages')">消息中心</el-dropdown-item>
              <el-dropdown-item class="profile-menu-item profile-feedback-item" :icon="ChatLineSquare" @click="openFeedback">
                问题反馈<span v-if="feedbackHintVisible" class="profile-feedback-hint">新</span>
              </el-dropdown-item>
              <div v-if="hasWorkspaceAccess" class="profile-menu-group-title" role="separator">工作台</div>
              <el-dropdown-item v-if="menu.hasPermission('venue-admin:view')" class="profile-menu-item" :icon="OfficeBuilding" @click="router.push('/venue-admin')">空间运营后台</el-dropdown-item>
              <el-dropdown-item v-if="menu.hasPermission('system:view')" class="profile-menu-item" :icon="Setting" @click="router.push('/system-admin')">系统后台</el-dropdown-item>
              <el-dropdown-item v-if="canAccessContentWorkspace" class="profile-menu-item" :icon="CircleCheck" @click="router.push('/content-admin')">内容运营后台</el-dropdown-item>
              <el-dropdown-item class="profile-menu-item profile-logout-item" divided :icon="SwitchButton" @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <main id="user-main" ref="mainRef" class="user-main" tabindex="-1">
      <router-view v-slot="{ Component, route: viewRoute }">
        <transition :name="fadeName" mode="out-in">
          <component :is="Component" :key="viewRoute.fullPath" class="route-view" />
        </transition>
      </router-view>
    </main>

    <footer class="user-footer sg-intro-el" aria-label="拾光服务链接">
      <span class="user-footer-brand">拾光</span>
      <span class="user-footer-copy">让每段时间，都有合适的去处。</span>
      <span v-if="environmentBadge" class="user-footer-env" aria-label="当前环境">{{ environmentBadge }}</span>
      <span class="user-footer-links">
        <router-link v-if="auth.isLoggedIn" to="/me/feedback">问题反馈</router-link>
        <router-link v-if="menu.hasPermission('venue:view')" to="/venues">场馆入驻</router-link>
      </span>
    </footer>

    <button
      v-if="showBackToTop"
      class="back-to-top sg-intro-el"
      type="button"
      aria-label="回到顶部"
      data-test="back-to-top"
      @click="scrollToTop"
    >
      <el-icon><ArrowUp /></el-icon>
    </button>

    <nav
      class="mobile-tabbar sg-intro-el"
      aria-label="移动端主导航"
      :style="{ '--mobile-tab-count': String(mobileTabCount) }"
    >
      <router-link to="/" :class="{ 'is-active': currentSection === 'home' }" aria-label="首页">
        <el-icon><House /></el-icon>
        <span>首页</span>
      </router-link>
      <router-link v-if="menu.hasPermission('venue:view')" to="/venues" :class="{ 'is-active': currentSection === 'venues' }" aria-label="场馆">
        <el-icon><Location /></el-icon>
        <span>场馆</span>
      </router-link>
      <router-link v-if="menu.hasPermission('community:access')" to="/community" :class="{ 'is-active': currentSection === 'discover' }" aria-label="发现">
        <el-icon><Compass /></el-icon>
        <span>发现</span>
      </router-link>
      <router-link v-if="menu.hasPermission('community:access')" to="/activities" :class="{ 'is-active': currentSection === 'activities' }" aria-label="活动">
        <el-icon><Flag /></el-icon>
        <span>活动</span>
      </router-link>
      <router-link v-if="menu.hasPermission('reservation:my')" to="/me/reservations" :class="{ 'is-active': currentSection === 'trips' }" aria-label="行程">
        <el-icon><Clock /></el-icon>
        <span>行程</span>
      </router-link>
      <router-link to="/me" :class="{ 'is-active': currentSection === 'me' }" aria-label="我的">
        <el-icon><User /></el-icon>
        <span>我的</span>
      </router-link>
    </nav>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
  ArrowUp,
  Bell,
  ChatLineSquare,
  CircleCheck,
  Clock,
  Compass,
  Flag,
  House,
  Location,
  OfficeBuilding,
  Setting,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { useMenuStore } from '@/stores/menu'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { useNotificationStream } from '@/composables/useNotificationStream'
import { canAccessContentAdmin } from '@/utils/contentAdminAccess'

const auth = useAuthStore()
const app = useAppStore()
// 路由转场回归 `route` CSS 过渡（E2E-AUDIT-FIX-20260906 S05，D-02 根治：VT 容器转场退役）
const fadeName = 'route'
const menu = useMenuStore()
const route = useRoute()
const router = useRouter()
const mainRef = ref<HTMLElement>()
const desktopNavRef = ref<HTMLElement>()
const feedbackHintVisible = ref(false)
const showBackToTop = ref(false)
const { start: startNotifications, stop: stopNotifications } = useNotificationStream()

const compactCount = computed(() => (app.unreadCount > 99 ? '99+' : String(app.unreadCount)))
const messageAriaLabel = computed(() => (app.unreadCount ? `消息中心，${app.unreadCount} 条未读消息` : '消息中心'))
const avatarText = computed(() => (auth.user?.nickname || auth.user?.username || '用').slice(0, 1))
const canAccessContentWorkspace = computed(() => canAccessContentAdmin(auth.permissions))
const hasWorkspaceAccess = computed(() => menu.hasPermission('venue-admin:view') || menu.hasPermission('system:view') || canAccessContentWorkspace.value)
const mobileTabCount = computed(() => Math.min(6,
  2
  + Number(menu.hasPermission('venue:view'))
  + Number(menu.hasPermission('community:access')) * 2
  + Number(menu.hasPermission('reservation:my'))
))
const environmentBadge = computed(() => {
  if (import.meta.env.MODE.toLowerCase().includes('sandbox')) return 'SANDBOX · 沙箱'
  if (import.meta.env.PROD) return ''
  return import.meta.env.MODE === 'test' ? 'DEV · 测试' : 'DEV · 开发'
})
const currentSection = computed(() => {
  const path = route.path
  if (path === '/') return 'home'
  if (path.startsWith('/venues')) return 'venues'
  if (path.startsWith('/activities') || path.startsWith('/companion-requests')) return 'activities'
  if (path.startsWith('/community')) return 'discover'
  if (
    path.startsWith('/reservation') ||
    /^\/me\/(reservations|orders|payments|checkins|stored-value)/.test(path)
  ) {
    return 'trips'
  }
  return 'me'
})

const loadUnreadCount = async () => {
  if (!auth.isLoggedIn) {
    app.clearUnreadCount()
    return
  }
  try {
    await app.refreshUnreadCount()
  } catch {
    return
  }
}

const focusMain = async () => {
  await nextTick()
  mainRef.value?.focus({ preventScroll: true })
}

const updateNavIndicator = () => {
  const nav = desktopNavRef.value
  const active = nav?.querySelector<HTMLElement>('.nav-link.is-active')
  if (!nav || !active) return
  const navRect = nav.getBoundingClientRect()
  const activeRect = active.getBoundingClientRect()
  nav.style.setProperty('--nav-indicator-left', `${activeRect.left - navRect.left}px`)
  nav.style.setProperty('--nav-indicator-width', `${activeRect.width}px`)
}

const logout = async () => {
  stopNotifications()
  await auth.logout()
  menu.reset()
  router.replace('/login')
}

const openFeedback = () => {
  feedbackHintVisible.value = false
  if (typeof window !== 'undefined') window.localStorage.setItem('sg-feedback-hint-dismissed', '1')
  router.push('/me/feedback')
}

const updateBackToTop = () => {
  showBackToTop.value = typeof window !== 'undefined' && window.scrollY > 600
}

const scrollToTop = () => {
  if (typeof window === 'undefined') return
  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  window.scrollTo({ top: 0, behavior: reducedMotion ? 'auto' : 'smooth' })
}

watch(
  () => route.fullPath,
  async () => {
    await loadUnreadCount()
    await focusMain()
    updateNavIndicator()
  }
)

watch(
  () => [menu.loaded, menu.menus.length],
  async () => {
    await nextTick()
    updateNavIndicator()
  }
)

onMounted(async () => {
  feedbackHintVisible.value = typeof window !== 'undefined' && window.localStorage.getItem('sg-feedback-hint-dismissed') !== '1'
  if (auth.isLoggedIn && (!menu.loaded || menu.tokenSnapshot !== auth.token)) {
    await menu.loadMenus()
  }
  await loadUnreadCount()
  startNotifications()
  await nextTick()
  updateNavIndicator()
  updateBackToTop()
  window.addEventListener('scroll', updateBackToTop, { passive: true })
  window.addEventListener('resize', updateNavIndicator)
})

onBeforeUnmount(() => {
  stopNotifications()
  window.removeEventListener('scroll', updateBackToTop)
  window.removeEventListener('resize', updateNavIndicator)
})
</script>

<style scoped>
.user-layout {
  position: relative;
  isolation: isolate;
  min-height: 100dvh;
}

/* 层级取值遵循 main.css「全站层级表」：页头 90 是唯一允许盖过页内浮层（SgDropdown 60）的元素。
   V3 玻璃页头（预览 §08 UserLayout 处方）：半透明 + blur 让流动背景透出，z-90 与发丝线语义不变。 */
.user-header {
  position: sticky;
  top: 0;
  z-index: 90;
  min-height: var(--user-header-h, 68px);
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 10px max(20px, calc((100vw - 1200px) / 2));
  background: color-mix(in srgb, var(--sg-canvas) 82%, transparent);
  border-bottom: 1px solid var(--sg-border);
  box-shadow: var(--sg-shadow-1);
  -webkit-backdrop-filter: blur(20px) saturate(1.25);
  backdrop-filter: blur(20px) saturate(1.25);
}

.brand {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
  color: var(--sg-ink);
}

.brand-mark {
  font-family: var(--sg-font-serif);
  font-size: 21px;
  font-weight: 700;
  letter-spacing: 0.06em;
  line-height: 1;
  color: var(--sg-ink);
}

.brand-mark i {
  font-style: normal;
  color: var(--sg-primary);
}

.brand-copy {
  display: grid;
  line-height: 1.25;
}

.brand-copy strong {
  font-size: var(--sg-fz-sm);
  font-weight: 600;
}

.brand-copy small {
  margin-top: 2px;
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-xs);
  font-weight: 500;
  letter-spacing: 0.02em;
}

.desktop-nav {
  position: relative;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
}

.nav-link {
  position: relative;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 12px;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-base);
  font-weight: 500;
  transition: color var(--sg-motion-fast) var(--sg-ease-standard), background-color var(--sg-motion-fast) var(--sg-ease-standard), transform var(--sg-motion-base) var(--sg-ease-spring);
}

.nav-link:hover {
  color: var(--sg-ink);
  background: var(--sg-surface-subtle);
  transform: translateY(-1px);
}

@media (prefers-reduced-motion: no-preference) {
  .nav-link:active {
    transform: scale(0.97);
  }
}

.nav-link.is-active {
  color: var(--sg-primary);
  font-weight: 600;
}

.nav-indicator {
  position: absolute;
  left: var(--nav-indicator-left, 0px);
  bottom: 5px;
  width: var(--nav-indicator-width, 0px);
  height: 3px;
  border-radius: 999px;
  background: var(--sg-primary);
  pointer-events: none;
  transition: left var(--sg-motion-base) var(--sg-ease-spring), width var(--sg-motion-base) var(--sg-ease-spring);
}

.nav-count {
  min-width: 20px;
  height: 20px;
  display: inline-grid;
  place-items: center;
  padding: 0 5px;
  border-radius: var(--sg-radius-card);
  background: var(--sg-danger);
  color: var(--sg-surface);
  font-size: 10px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-icon-button {
  position: relative;
  width: 44px;
  height: 44px;
  display: inline-grid;
  place-items: center;
  border: 1px solid transparent;
  border-radius: var(--sg-radius-card);
  background: transparent;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-lg);
  transition: color var(--sg-motion-fast) var(--sg-ease-standard), border-color var(--sg-motion-fast) var(--sg-ease-standard), background-color var(--sg-motion-fast) var(--sg-ease-standard);
}

.header-icon-button:hover {
  border-color: var(--sg-border);
  background: var(--sg-surface-subtle);
  color: var(--sg-primary);
}

.message-indicator {
  position: absolute;
  top: 9px;
  right: 9px;
  width: 7px;
  height: 7px;
  border: 2px solid var(--sg-surface);
  border-radius: 50%;
  background: var(--sg-danger);
}

.profile-button {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 210px;
  padding: 4px 9px 4px 5px;
  border: 1px solid var(--sg-border);
  border-radius: var(--sg-radius-card);
  background: var(--sg-surface);
  color: var(--sg-ink);
  font: inherit;
  font-weight: 600;
  transition: border-color var(--sg-motion-fast) var(--sg-ease-standard), box-shadow var(--sg-motion-fast) var(--sg-ease-standard);
}

.profile-button:hover {
  border-color: var(--sg-primary);
  box-shadow: var(--sg-shadow-1);
}

.profile-avatar {
  width: 34px;
  height: 34px;
  display: inline-grid;
  flex: 0 0 auto;
  place-items: center;
  border-radius: var(--sg-radius-card);
  background: var(--sg-pine-soft);
  color: var(--sg-pine);
}

.profile-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-chevron {
  flex: 0 0 auto;
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-xs);
}

:global(.profile-menu-item-copy) {
  display: inline-flex;
  flex-direction: column;
  gap: 2px;
}

:global(.profile-menu-item-copy small) {
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-xs);
  font-weight: 400;
}

:global(.profile-menu-group-title) {
  padding: 8px 12px 4px;
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-xs);
  font-weight: 700;
  letter-spacing: 0.08em;
}

:global(.profile-feedback-item) {
  color: var(--sg-primary) !important;
  font-weight: 600;
}

:global(.profile-feedback-hint) {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 5px;
  border-radius: 999px;
  background: var(--sg-primary-soft);
  color: var(--sg-primary);
  font-size: 10px;
  line-height: 1.4;
}

:global(.profile-logout-item) {
  color: var(--sg-danger) !important;
}

.user-main {
  min-height: calc(100dvh - var(--user-header-h, 68px));
  padding: 28px 0 48px;
  outline: none;
}

.user-footer {
  min-height: 52px;
  max-width: 1200px;
  margin: 0 auto;
  padding: 12px max(20px, calc((100vw - 1200px) / 2));
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-xs);
}

.user-footer-brand {
  color: var(--sg-ink);
  font-family: var(--sg-font-serif);
  font-size: var(--sg-fz-base);
  font-weight: 700;
}

.user-footer-copy { flex: 1; }

.user-footer-env {
  padding: 3px 8px;
  border-radius: var(--sg-radius-stamp);
  background: var(--sg-honey-soft);
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
  font-weight: 700;
  letter-spacing: 0.04em;
}

.user-footer-links {
  display: inline-flex;
  gap: 14px;
}

.user-footer-links a {
  color: var(--sg-ink-muted);
}

.user-footer-links a:hover { color: var(--sg-primary); }

.back-to-top {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 25;
  width: 44px;
  height: 44px;
  display: inline-grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: var(--sg-surface);
  color: var(--sg-ink-muted);
  box-shadow: var(--sg-shadow-2);
  cursor: pointer;
  animation: sg-rise var(--sg-motion-fast) var(--sg-ease-standard) both;
}

.back-to-top:hover { color: var(--sg-primary); }

@media (prefers-reduced-motion: reduce) {
  .back-to-top { animation: none; }
}

.mobile-tabbar {
  display: none;
}

@media (max-width: 900px) {
  .user-header {
    padding-inline: 14px;
  }

  .desktop-nav {
    gap: 0;
  }

  .nav-link {
    padding-inline: 9px;
  }

  .nav-link .el-icon {
    display: none;
  }
}

@media (max-width: 640px) {
  .user-header {
    min-height: var(--user-header-h, 60px);
    padding: 8px 10px;
  }

  .brand-mark {
    font-size: var(--sg-fz-lg);
  }

  .brand-copy,
  .desktop-nav,
  .header-icon-button,
  .profile-name,
  .profile-chevron {
    display: none;
  }

  .header-actions {
    margin-left: auto;
  }

  .profile-button {
    width: 44px;
    padding: 4px;
    border-color: transparent;
  }

  .profile-avatar {
    width: 34px;
    height: 34px;
  }

  .user-main {
    min-height: calc(100dvh - 132px);
    padding: 18px 0 calc(92px + env(safe-area-inset-bottom));
  }

  .user-footer {
    min-height: 48px;
    padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
    justify-content: space-between;
  }

  .user-footer-copy { display: none; }

  .back-to-top {
    right: 16px;
    bottom: calc(92px + env(safe-area-inset-bottom));
  }

  .mobile-tabbar {
    position: fixed;
    inset: auto 0 0;
    z-index: 30;
    min-height: 68px;
    display: grid;
    grid-template-columns: repeat(var(--mobile-tab-count, 3), minmax(0, 1fr));
    padding: 6px 4px calc(6px + env(safe-area-inset-bottom));
    border-top: 1px solid var(--sg-border);
    background: color-mix(in srgb, var(--sg-surface) 94%, transparent);
    box-shadow: 0 -6px 20px color-mix(in srgb, var(--sg-ink) 8%, transparent);
    -webkit-backdrop-filter: blur(20px) saturate(1.25);
    backdrop-filter: blur(20px) saturate(1.25);
  }

  .mobile-tabbar a {
    position: relative;
    min-width: 0;
    width: fit-content;
    min-height: 52px;
    display: flex;
    justify-self: center;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    padding-inline: 14px;
    border-radius: 999px;
    color: var(--sg-ink-subtle);
    font-size: var(--sg-fz-xs);
    font-weight: 600;
  }

  .mobile-tabbar a:hover,
  .mobile-tabbar a.is-active {
    background: var(--sg-primary-soft);
    color: var(--sg-primary);
  }

  .mobile-tabbar .el-icon {
    font-size: var(--sg-fz-lg);
  }

  .tab-icon-wrap {
    position: relative;
    display: inline-flex;
  }

  .tab-count {
    position: absolute;
    top: -8px;
    left: 13px;
    min-width: 17px;
    height: 17px;
    display: inline-grid;
    place-items: center;
    padding: 0 4px;
    border: 2px solid var(--sg-surface);
    border-radius: var(--sg-radius-control);
    background: var(--sg-danger);
    color: var(--sg-surface);
    font-size: 9px;
    line-height: 1;
  }
}
</style>
