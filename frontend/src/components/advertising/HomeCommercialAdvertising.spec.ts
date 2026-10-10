import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HomeCommercialAdvertising from './HomeCommercialAdvertising.vue'
import componentSource from './HomeCommercialAdvertising.vue?raw'

const mocks = vi.hoisted(() => ({
  advertisingApi: {
    homeCommercialDecision: vi.fn(),
    recordEventWithRecovery: vi.fn()
  },
  router: { push: vi.fn() }
}))

vi.mock('@/api/advertising', () => ({ advertisingApi: mocks.advertisingApi }))
vi.mock('vue-router', () => ({ useRouter: () => mocks.router }))

const decision = {
  decisionId: 21,
  creativeVersionId: 31,
  token: 'opaque-ad-token',
  reused: false,
  adLabel: '广告',
  accessibleAdLabel: '广告内容',
  headline: '本周预约有新场地',
  bodyText: '查看符合你时间的开放时段。',
  callToAction: 'BOOK',
  targetType: 'VENUE_LIST',
  targetId: null,
  deepLink: '/venues',
  policyVersion: 'G-R7-20260803-v1',
  expiresAt: '2026-08-03T16:00:00'
}

const stubs = {
  'el-icon': { template: '<i><slot /></i>' }
}

let observerCallback: IntersectionObserverCallback | undefined
const observerDisconnect = vi.fn()

const emitIntersection = (ratio: number) => {
  observerCallback?.([
    { intersectionRatio: ratio, isIntersecting: ratio >= 0.5 } as IntersectionObserverEntry
  ], {} as IntersectionObserver)
}

describe('HomeCommercialAdvertising', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    observerCallback = undefined
    mocks.advertisingApi.recordEventWithRecovery.mockResolvedValue({
      resultStatus: 'ACCEPTED', eventType: 'CLICK', replayed: false
    })
    mocks.router.push.mockResolvedValue(undefined)
    vi.stubGlobal('IntersectionObserver', class {
      constructor(callback: IntersectionObserverCallback) {
        observerCallback = callback
      }

      observe() {}
      disconnect() { observerDisconnect() }
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it.each([
    ['empty', null],
    ['unavailable', new Error('advertising unavailable')]
  ])('does not render an optional shell when the decision is %s', async (_label, result) => {
    if (result instanceof Error) mocks.advertisingApi.homeCommercialDecision.mockRejectedValueOnce(result)
    else mocks.advertisingApi.homeCommercialDecision.mockResolvedValueOnce(result)

    const wrapper = mount(HomeCommercialAdvertising, { global: { stubs } })
    await flushPromises()

    expect(wrapper.find('.home-commercial-ad').exists()).toBe(false)
    expect(wrapper.text()).toBe('')
  })

  it('keeps the server ad label and follows the server deep link after reporting a click', async () => {
    mocks.advertisingApi.homeCommercialDecision.mockResolvedValueOnce(decision)

    const wrapper = mount(HomeCommercialAdvertising, { global: { stubs } })
    await flushPromises()

    expect(wrapper.get('.advertising-badge').text()).toBe('广告')
    expect(wrapper.get('.home-commercial-ad').attributes('aria-label')).toBe('广告内容')
    expect(wrapper.text()).toContain('本周预约有新场地')
    expect(wrapper.text()).toContain('立即预约')

    await wrapper.get('.advertising-link').trigger('click')
    await wrapper.get('.advertising-link').trigger('click')

    expect(mocks.advertisingApi.recordEventWithRecovery).toHaveBeenCalledOnce()
    expect(mocks.advertisingApi.recordEventWithRecovery).toHaveBeenCalledWith(
      decision.decisionId,
      { eventType: 'CLICK', token: decision.token },
      'home-commercial:21:CLICK'
    )
    expect(mocks.router.push).toHaveBeenCalledWith('/venues')
    expect(mocks.advertisingApi.recordEventWithRecovery.mock.invocationCallOrder[0])
      .toBeLessThan(mocks.router.push.mock.invocationCallOrder[0])
  })

  it('reports one viewable event only after the 50 percent threshold remains for 1000ms', async () => {
    mocks.advertisingApi.homeCommercialDecision.mockResolvedValueOnce(decision)

    const wrapper = mount(HomeCommercialAdvertising, { global: { stubs } })
    await flushPromises()

    emitIntersection(0.5)
    vi.advanceTimersByTime(999)
    expect(mocks.advertisingApi.recordEventWithRecovery).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)

    expect(mocks.advertisingApi.recordEventWithRecovery).toHaveBeenCalledOnce()
    expect(mocks.advertisingApi.recordEventWithRecovery).toHaveBeenCalledWith(
      decision.decisionId,
      expect.objectContaining({
        eventType: 'CLIENT_VIEWABLE',
        token: decision.token,
        visibleRatio: 0.5,
        visibleDurationMs: 1000
      }),
      'home-commercial:21:CLIENT_VIEWABLE'
    )

    emitIntersection(1)
    vi.advanceTimersByTime(1000)
    expect(mocks.advertisingApi.recordEventWithRecovery).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('fails closed when a reused decision has no server-issued token', async () => {
    mocks.advertisingApi.homeCommercialDecision.mockResolvedValueOnce({ ...decision, token: null, reused: true })

    const wrapper = mount(HomeCommercialAdvertising, { global: { stubs } })
    await flushPromises()

    expect(wrapper.find('.home-commercial-ad').exists()).toBe(false)
    expect(mocks.advertisingApi.recordEventWithRecovery).not.toHaveBeenCalled()
    expect(mocks.router.push).not.toHaveBeenCalled()
  })

  it('records an exhausted recovery state without blocking the deep link', async () => {
    mocks.advertisingApi.homeCommercialDecision.mockResolvedValueOnce(decision)
    mocks.advertisingApi.recordEventWithRecovery.mockRejectedValueOnce(new Error('measurement unavailable'))

    const wrapper = mount(HomeCommercialAdvertising, { global: { stubs } })
    await flushPromises()
    await wrapper.get('.advertising-link').trigger('click')
    await flushPromises()

    expect(wrapper.get('.home-commercial-ad').attributes('data-measurement-status')).toBe('exhausted')
    expect(mocks.router.push).toHaveBeenCalledWith(decision.deepLink)
  })

  it('clears visibility work and disconnects the observer on unmount', async () => {
    mocks.advertisingApi.homeCommercialDecision.mockResolvedValueOnce(decision)

    const wrapper = mount(HomeCommercialAdvertising, { global: { stubs } })
    await flushPromises()
    emitIntersection(1)
    wrapper.unmount()
    vi.advanceTimersByTime(1000)

    expect(mocks.advertisingApi.recordEventWithRecovery).not.toHaveBeenCalled()
    expect(observerDisconnect).toHaveBeenCalledOnce()
  })

  it('renders the server ad label as a honey chapter on a solid poster card', async () => {
    mocks.advertisingApi.homeCommercialDecision.mockResolvedValueOnce(decision)

    const wrapper = mount(HomeCommercialAdvertising, { global: { stubs } })
    await flushPromises()

    const card = wrapper.get('.home-commercial-ad')
    const badge = wrapper.get('.advertising-badge')

    // 合规语义不变：标识文本仍由服务端下发，无障碍标签与 sr-only 副本不动
    expect(badge.text()).toBe('广告')
    expect(badge.classes()).toContain('sg-tag')
    expect(badge.classes()).toContain('sg-tag--honey')
    expect(card.attributes('aria-label')).toBe('广告内容')
    expect(wrapper.get('.sr-only').text()).toBe('广告内容')

    // 视觉换装：实底卡 + 内高光 + 弹簧 hover + 对焦进场；去常驻描边与蜜金满铺
    expect(card.classes()).toContain('sg-focus-in')
    const cardBlock = componentSource.match(/\.home-commercial-ad\s*\{[^}]*\}/)?.[0] ?? ''
    expect(cardBlock).toContain('background: var(--sg-surface)')
    expect(cardBlock).toContain('inset 0 1px 0 var(--sg-sheen)')
    expect(cardBlock).toContain('var(--sg-ease-spring)')
    expect(cardBlock).not.toContain('border:')
    expect(cardBlock).not.toContain('margin-bottom')
    expect(componentSource).toMatch(/\.home-commercial-ad::after\s*\{[^}]*--sg-honey/)
  })

  it('keeps long commercial copy breakable on a narrow screen', async () => {
    mocks.advertisingApi.homeCommercialDecision.mockResolvedValueOnce({
      ...decision,
      headline: 'a'.repeat(240),
      bodyText: 'b'.repeat(240)
    })

    const wrapper = mount(HomeCommercialAdvertising, { global: { stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('a'.repeat(240))
    expect(componentSource).toMatch(/\.advertising-copy\s*\{[\s\S]*?min-width:\s*0/)
    expect(componentSource).toMatch(/\.advertising-title,[\s\S]*?\.advertising-body\s*\{[\s\S]*?overflow-wrap:\s*anywhere/)
    expect(componentSource).toMatch(/\.advertising-kicker,[\s\S]*?\.advertising-cta\s*\{[\s\S]*?flex-wrap:\s*wrap/)
  })
})
