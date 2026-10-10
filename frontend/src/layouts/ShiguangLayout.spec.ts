import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ShiguangLayout from './ShiguangLayout.vue'

vi.mock('@/composables/useNotificationStream', () => ({ useNotificationStream: () => ({ start: vi.fn(), stop: vi.fn() }) }))
vi.mock('@/api/venue', () => ({ venueApi: { facets: vi.fn().mockResolvedValue({ areas: [] }) } }))
vi.mock('@/api/notice', () => ({ noticeApi: { unreadCount: vi.fn().mockResolvedValue(0) } }))
const names = ['UserHome', 'MeOverview', 'Profile', 'Messages', 'Favorites', 'MyFeedback']
const paths = ['/', '/me', '/me/profile', '/me/messages', '/me/favorites', '/me/feedback']
const wrappers: ReturnType<typeof mount>[] = []

beforeEach(() => {
  localStorage.clear()
  Element.prototype.animate = vi.fn().mockReturnValue({ cancel: vi.fn() })
  vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  vi.stubGlobal('IntersectionObserver', class { observe() {} disconnect() {} })
})
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals() })

it('keeps personal route titles and focus independent of discovery when navigating in the shared shell', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: names.map((name, i) => ({
    path: paths[i], name, ...(name === 'Profile' ? { alias: '/user/profile' } : {}),
    component: { template: `<div><h1>${name}</h1><div id="personal-content" data-route-focus tabindex="-1" /></div>` }
  })).concat([{path:'/:pathMatch(.*)*', name:'Other', component:{template:'<div />'}}]) })
  await router.push('/')
  const w = mount(ShiguangLayout, { attachTo: document.body, global: {
    plugins: [createPinia(), router], stubs: { VenueQuickDrawer: true, ElDropdown: true, ElDropdownItem: true, ElDropdownMenu: true, ElTooltip: { template: '<div><slot /></div>' } }
  } }); wrappers.push(w)
  await flushPromises()
  expect(w.find('.home-tools').exists()).toBe(true)
  for (const path of [...paths.slice(1), '/user/profile?source=nav#settings']) {
    await router.push(path); await flushPromises()
    expect(w.attributes('data-page')).toBe('personal')
    expect(w.find('.home-tools').exists()).toBe(false)
    expect(w.find('.main-nav .is-active').exists()).toBe(false)
    expect(document.activeElement?.id).toBe('personal-content')
    expect(router.currentRoute.value.fullPath).toBe(path)
  }
})
