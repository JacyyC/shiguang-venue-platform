import { createRouter, createWebHistory } from 'vue-router'
import { nextTick } from 'vue'
import { routes } from './routes'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'

/**
 * 滚动行为（SHIGUANG-BATCH-04-REFACTOR-20260914 S04 / B4-C2）。
 *
 * 拾光后台面用 `#<页签>` 表示面内状态（第三批公告面与第四批会员面等），它**不是 DOM 锚点**；
 * 原实现对任何 hash 都返回 `{ el: to.hash }`，vue-router 找不到元素时每次导航都会打
 * `Couldn't find element using selector "#levels"` 警告并放弃滚动。这里先确认锚点存在，
 * 不存在就按普通导航回到顶部——行为等价、不再产生误导性警告，同时保留真实锚点跳转。
 *
 * 以命名导出暴露，便于单测直接验证该纯函数，无需实例化整个 router（避免测试里引入
 * `createWebHistory` 的真实副作用与额外加载）。
 */
export function scrollBehavior(
  to: { hash: string },
  _from: unknown,
  savedPosition: unknown
): { el: string; top: number } | { top: number; left: number } | unknown {
  if (savedPosition) return savedPosition
  if (isExistingAnchor(to.hash)) return { el: to.hash, top: 84 }
  return { top: 0, left: 0 }
}

/**
 * hash 只有在指向真实元素时才是锚点。后台面把页签状态写进 hash，非法选择器（如 `#<页签>`）
 * 会让 `querySelector` 直接抛错，这里按「不是锚点」处理，保持滚动行为不变。
 */
function isExistingAnchor(hash: string): boolean {
  if (!hash) return false
  try {
    return Boolean(document.querySelector(hash))
  } catch {
    return false
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: scrollBehavior as never
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  const menu = useMenuStore()
  if (to.meta.public) {
    return true
  }
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  let authorizationUnavailable = false
  if (auth.isLoggedIn) {
    const [menuResult, authorizationResult] = await Promise.allSettled([
      menu.loadMenus(),
      auth.refreshAuthorization()
    ])
    const failures = [menuResult, authorizationResult]
      .filter((result): result is PromiseRejectedResult => result.status === 'rejected')
    const hasUnauthenticatedFailure = failures.some((result) => {
      const status = (result.reason as { response?: { status?: number } })?.response?.status
      return status === 401
    })
    const hasForbiddenFailure = failures.some((result) => {
      const status = (result.reason as { response?: { status?: number } })?.response?.status
      return status === 403
    })
    if (!auth.isLoggedIn || hasUnauthenticatedFailure) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (hasForbiddenFailure) return '/403'
    authorizationUnavailable = authorizationResult.status === 'rejected'
  }
  const perm = to.meta.menuKey as string | undefined
  const canUseCachedAuthorization = menu.unavailable || authorizationUnavailable
  if (perm && !menu.hasPermission(perm) && !(canUseCachedAuthorization && auth.hasPermission(perm))) {
    return '/403'
  }
  const authorizationKey = to.meta.authorizationKey as string | undefined
  if (authorizationKey && !auth.hasPermission(authorizationKey)) {
    return '/403'
  }
  const authorizationAny = to.meta.authorizationAny as string[] | undefined
  if (authorizationAny?.length && !authorizationAny.some((permission) => auth.hasPermission(permission))) {
    return '/403'
  }
  // L8: roles 校验不再依赖 !perm 短路，两者独立校验（AND 逻辑）
  const roles = (to.meta.roles as string[] | undefined) || []
  if (roles.length && !auth.hasAnyRole(roles)) {
    return '/403'
  }
  return true
})

router.afterEach(async () => {
  await nextTick()
  const target = document.querySelector<HTMLElement>('[data-route-focus], main[tabindex="-1"], main')
  if (!target) return
  if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1')
  target.focus({ preventScroll: true })
})

export default router
