import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HomePrimaryPlacement from './HomePrimaryPlacement.vue'
import componentSource from './HomePrimaryPlacement.vue?raw'

const mocks = vi.hoisted(() => ({
  placementApi: {
    homePrimaryDecision: vi.fn(),
    recordEvent: vi.fn()
  },
  router: { push: vi.fn() }
}))

vi.mock('@/api/placement', () => ({ placementApi: mocks.placementApi }))
vi.mock('vue-router', () => ({ useRouter: () => mocks.router }))

const decision = {
  decisionId: 7,
  candidateVersionId: 8,
  token: 'opaque-token',
  reused: false,
  title: '首页宣传标题',
  summary: '来源摘要',
  imageUrl: 'https://cdn.invalid/placement.webp',
  deepLink: '/activities/41',
  expiresAt: '2026-08-02T10:00:00'
}

let observerCallback: IntersectionObserverCallback | undefined

const emitIntersection = (ratio: number) => {
  observerCallback?.([
    { intersectionRatio: ratio, isIntersecting: ratio >= 0.5 } as IntersectionObserverEntry
  ], {} as IntersectionObserver)
}

describe('HomePrimaryPlacement', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    observerCallback = undefined
    mocks.router.push.mockResolvedValue(undefined)
    mocks.placementApi.recordEvent.mockResolvedValue({ resultStatus: 'ACCEPTED', eventType: 'CLICK' })
    vi.stubGlobal('IntersectionObserver', class {
      constructor(callback: IntersectionObserverCallback) {
        observerCallback = callback
      }

      observe() {}
      disconnect() {}
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it.each([
    ['empty', null],
    ['401/403 or timeout/5xx', new Error('placement unavailable')]
  ])('does not render an empty shell when the decision is %s', async (_label, result) => {
    if (result instanceof Error) mocks.placementApi.homePrimaryDecision.mockRejectedValueOnce(result)
    else mocks.placementApi.homePrimaryDecision.mockResolvedValueOnce(result)

    const wrapper = mount(HomePrimaryPlacement)
    await flushPromises()

    expect(wrapper.find('.home-primary-placement').exists()).toBe(false)
    expect(wrapper.text()).toBe('')
  })

  it('keeps text content when the optional image fails', async () => {
    mocks.placementApi.homePrimaryDecision.mockResolvedValueOnce(decision)

    const wrapper = mount(HomePrimaryPlacement)
    await flushPromises()
    await wrapper.get('img').trigger('error')

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('.placement-title').text()).toBe(decision.title)
    expect(wrapper.get('.placement-summary').text()).toBe(decision.summary)
    await wrapper.get('.placement-link').trigger('click')
    expect(mocks.router.push).toHaveBeenCalledWith(decision.deepLink)
    wrapper.unmount()
  })

  it('reports one viewable event only after ratio 0.5 remains visible for 1000ms', async () => {
    mocks.placementApi.homePrimaryDecision.mockResolvedValueOnce(decision)

    const wrapper = mount(HomePrimaryPlacement)
    await flushPromises()

    emitIntersection(0.5)
    vi.advanceTimersByTime(500)
    emitIntersection(0.49)
    vi.advanceTimersByTime(1000)
    expect(mocks.placementApi.recordEvent).not.toHaveBeenCalled()

    emitIntersection(0.5)
    vi.advanceTimersByTime(999)
    expect(mocks.placementApi.recordEvent).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)

    expect(mocks.placementApi.recordEvent).toHaveBeenCalledOnce()
    expect(mocks.placementApi.recordEvent).toHaveBeenCalledWith(
      decision.decisionId,
      expect.objectContaining({
        eventType: 'CLIENT_VIEWABLE',
        token: decision.token,
        visibleRatio: 0.5,
        visibleDurationMs: 1000
      }),
      'home-primary:7:CLIENT_VIEWABLE'
    )

    emitIntersection(1)
    vi.advanceTimersByTime(2000)
    expect(mocks.placementApi.recordEvent).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('records a click before following the server deep link and de-duplicates event writes', async () => {
    mocks.placementApi.homePrimaryDecision.mockResolvedValueOnce(decision)
    mocks.placementApi.recordEvent.mockRejectedValue(new Error('measurement unavailable'))

    const wrapper = mount(HomePrimaryPlacement)
    await flushPromises()
    await wrapper.get('.placement-link').trigger('click')
    await wrapper.get('.placement-link').trigger('click')
    await flushPromises()

    expect(mocks.placementApi.recordEvent).toHaveBeenCalledOnce()
    expect(mocks.placementApi.recordEvent).toHaveBeenCalledWith(
      decision.decisionId,
      { eventType: 'CLICK', token: decision.token },
      'home-primary:7:CLICK'
    )
    expect(mocks.router.push).toHaveBeenCalledWith(decision.deepLink)
    expect(mocks.placementApi.recordEvent.mock.invocationCallOrder[0]).toBeLessThan(mocks.router.push.mock.invocationCallOrder[0])
  })

  it('falls back to the typographic poster face when the creative has no image', async () => {
    mocks.placementApi.homePrimaryDecision.mockResolvedValueOnce({ ...decision, imageUrl: null })

    const wrapper = mount(HomePrimaryPlacement)
    await flushPromises()

    const card = wrapper.get('.home-primary-placement')
    expect(card.classes()).toContain('sg-focus-in')
    expect(card.classes()).not.toContain('has-image')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('.placement-mark').text()).toBe('01')
    expect(wrapper.get('.placement-kicker').text()).toContain('站内推荐')

    // 无素材海报面：印点纹平铺 + 松绿 wash 柔光（预览 §04+ F15 w-pine）
    expect(componentSource).toMatch(/\.home-primary-placement:not\(\.has-image\)::before\s*\{[^}]*background-size:\s*20px 20px/)
    expect(componentSource).toMatch(/\.home-primary-placement:not\(\.has-image\)::after\s*\{[^}]*--sg-pine/)
    wrapper.unmount()
  })

  it('moves the placement accent back to persimmon on a solid card with an inner sheen', async () => {
    mocks.placementApi.homePrimaryDecision.mockResolvedValueOnce(decision)

    const wrapper = mount(HomePrimaryPlacement)
    await flushPromises()

    // 去松绿满铺：实底卡定性 + 内高光 + 弹簧 hover；accent 收敛回柿橙（§08 处方）
    const cardBlock = componentSource.match(/\.home-primary-placement\s*\{[^}]*\}/)?.[0] ?? ''
    expect(cardBlock).toContain('--placement-accent: var(--sg-primary)')
    expect(cardBlock).toContain('background: var(--sg-surface)')
    expect(cardBlock).toContain('inset 0 1px 0 var(--sg-sheen)')
    expect(cardBlock).toContain('var(--sg-ease-spring)')
    expect(cardBlock).not.toContain('border:')
    wrapper.unmount()
  })

  it('reports a reused decision with the server-issued token', async () => {
    const reusedDecision = { ...decision, token: 'rotated-token', reused: true }
    mocks.placementApi.homePrimaryDecision.mockResolvedValueOnce(reusedDecision)

    const wrapper = mount(HomePrimaryPlacement)
    await flushPromises()
    await wrapper.get('.placement-link').trigger('click')

    expect(mocks.placementApi.recordEvent).toHaveBeenCalledWith(
      reusedDecision.decisionId,
      { eventType: 'CLICK', token: 'rotated-token' },
      'home-primary:7:CLICK'
    )
    wrapper.unmount()
  })
})
