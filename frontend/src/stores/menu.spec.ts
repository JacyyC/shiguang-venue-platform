import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { authApi } from '@/api/auth'
import { useAuthStore } from './auth'
import { useMenuStore } from './menu'

vi.mock('@/api/auth', () => ({
  authApi: {
    menus: vi.fn(),
    me: vi.fn(),
    authorization: vi.fn()
  }
}))

describe('menu store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('keeps login action permissions when the navigation menu omits a hidden action permission', async () => {
    vi.mocked(authApi.menus).mockResolvedValue([
      {
        id: 1,
        parentId: 0,
        name: 'Provider 观测',
        path: '/system-admin/providers',
        component: 'ProvidersView',
        perms: 'provider:observe',
        type: 'MENU'
      }
    ])
    const auth = useAuthStore()
    const menu = useMenuStore()
    auth.applyAuth({
      token: 'test-token',
      expiresIn: 3600,
      user: { id: 1, username: 'admin', status: 'ACTIVE' },
      roles: ['SYSTEM_ADMIN'],
      permissions: ['provider:observe', 'provider:refresh']
    })

    await menu.loadMenus()

    expect(auth.hasPermission('provider:refresh')).toBe(true)
    expect(menu.hasPermission('provider:observe')).toBe(true)
    expect(menu.hasPermission('provider:refresh')).toBe(false)
  })

  it('keeps login action permissions when refreshing the current user profile', async () => {
    vi.mocked(authApi.me).mockResolvedValue({ id: 1, username: 'admin', status: 'ACTIVE' })
    vi.mocked(authApi.menus).mockResolvedValue([
      {
        id: 1,
        parentId: 0,
        name: 'Provider 观测',
        path: '/system-admin/providers',
        component: 'ProvidersView',
        perms: 'provider:observe',
        type: 'MENU'
      }
    ])
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'test-token',
      expiresIn: 3600,
      user: { id: 1, username: 'admin', status: 'ACTIVE' },
      roles: ['SYSTEM_ADMIN'],
      permissions: ['provider:observe', 'provider:refresh']
    })

    await auth.refreshMe()

    expect(auth.hasPermission('provider:refresh')).toBe(true)
    expect(authApi.menus).not.toHaveBeenCalled()
  })

  it('replaces cached roles and permissions with the server authorization snapshot', async () => {
    vi.mocked(authApi.authorization).mockResolvedValue({
      roles: ['VENUE_ADMIN'],
      permissions: ['venue-admin:view', 'venue:space:manage']
    })
    const auth = useAuthStore()
    auth.applyAuth({
      token: 'test-token',
      expiresIn: 3600,
      user: { id: 1, username: 'alice', status: 'ACTIVE' },
      roles: ['USER'],
      permissions: ['user:view']
    })

    await auth.refreshAuthorization()

    expect(auth.hasRole('VENUE_ADMIN')).toBe(true)
    expect(auth.hasRole('USER')).toBe(false)
    expect(auth.hasPermission('venue:space:manage')).toBe(true)
    expect(JSON.parse(localStorage.getItem('roles') || '[]')).toEqual(['VENUE_ADMIN'])
  })

  it('keeps cached navigation available when the menu service is temporarily unavailable', async () => {
    vi.mocked(authApi.menus).mockResolvedValueOnce([
      {
        id: 1,
        parentId: 0,
        name: '社区',
        path: '/community',
        component: 'CommunityGroupsView',
        perms: 'community:access',
        type: 'MENU'
      }
    ]).mockRejectedValueOnce(new Error('Request failed with status code 500'))
    const auth = useAuthStore()
    const menu = useMenuStore()
    auth.applyAuth({
      token: 'test-token',
      expiresIn: 3600,
      user: { id: 1, username: 'alice', status: 'ACTIVE' },
      roles: ['USER'],
      permissions: ['community:access']
    })

    await menu.loadMenus()
    const result = await menu.loadMenus()

    expect(result).toBe(false)
    expect(menu.unavailable).toBe(true)
    expect(menu.hasPermission('community:access')).toBe(true)
  })

  it('keeps the last known menus for a same-token network failure without a response status', async () => {
    // F-06：网络层故障（无 response.status，如断网/超时）与 5xx 同属「同 token 瞬时故障」，
    // 合同是入口可用最近快照，而不是清空导航。
    vi.mocked(authApi.menus).mockResolvedValueOnce([
      {
        id: 1,
        parentId: 0,
        name: '社区',
        path: '/community',
        component: 'CommunityGroupsView',
        perms: 'community:access',
        type: 'MENU'
      }
    ]).mockRejectedValueOnce(new Error('Network Error'))
    const auth = useAuthStore()
    const menu = useMenuStore()
    auth.applyAuth({
      token: 'test-token',
      expiresIn: 3600,
      user: { id: 1, username: 'alice', status: 'ACTIVE' },
      roles: ['USER'],
      permissions: ['community:access']
    })

    await menu.loadMenus()
    const result = await menu.loadMenus()

    expect(result).toBe(false)
    expect(menu.unavailable).toBe(true)
    expect(menu.hasPermission('community:access')).toBe(true)
  })

  it('clears cached menus when a degraded failure follows a token change', async () => {
    // F-06：菜单快照绑定 token；换 token（新会话）后失败不得复用旧菜单，
    // 否则旧账号的入口会在新账号的页面上残留。
    vi.mocked(authApi.menus).mockResolvedValueOnce([
      {
        id: 1,
        parentId: 0,
        name: '社区',
        path: '/community',
        component: 'CommunityGroupsView',
        perms: 'community:access',
        type: 'MENU'
      }
    ]).mockRejectedValue(new Error('Request failed with status code 500'))
    const auth = useAuthStore()
    const menu = useMenuStore()
    auth.applyAuth({
      token: 'token-a',
      expiresIn: 3600,
      user: { id: 1, username: 'alice', status: 'ACTIVE' },
      roles: ['USER'],
      permissions: ['community:access']
    })
    await menu.loadMenus()
    expect(menu.hasPermission('community:access')).toBe(true)

    auth.applyAuth({
      token: 'token-b',
      expiresIn: 3600,
      user: { id: 2, username: 'bob', status: 'ACTIVE' },
      roles: ['USER'],
      permissions: []
    })
    const result = await menu.loadMenus()

    expect(result).toBe(false)
    expect(menu.unavailable).toBe(true)
    expect(menu.menus).toEqual([])
    expect(menu.hasPermission('community:access')).toBe(false)
  })

  it('rethrows explicit permission rejections instead of degrading to a cached render', async () => {
    // F-06：401/403 是服务端的明确授权结论，路由/拦截器要据此回登录或显示 403，
    // 菜单层不得吞掉后转为「快照降级」。
    const auth = useAuthStore()
    const menu = useMenuStore()
    auth.applyAuth({
      token: 'test-token',
      expiresIn: 3600,
      user: { id: 1, username: 'alice', status: 'ACTIVE' },
      roles: ['USER'],
      permissions: []
    })
    vi.mocked(authApi.menus).mockRejectedValue(
      Object.assign(new Error('Request failed with status code 403'), {
        response: { status: 403 }
      })
    )

    await expect(menu.loadMenus()).rejects.toMatchObject({ response: { status: 403 } })
    expect(menu.unavailable).toBe(false)
  })
})
