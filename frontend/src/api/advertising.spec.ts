import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ADVERTISING_EVENT_RETRY_DELAYS_MS, advertisingApi } from './advertising'

const mocks = vi.hoisted(() => ({
  advertisingClient: {
    get: vi.fn(),
    post: vi.fn(),
    interceptors: { request: { use: vi.fn() } }
  },
  http: {
    get: vi.fn(),
    put: vi.fn()
  }
}))

vi.mock('axios', () => ({
  default: { create: vi.fn(() => mocks.advertisingClient) }
}))

vi.mock('./http', () => ({ default: mocks.http }))

describe('advertisingApi', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('keeps optional home decisions and events on the independent client', async () => {
    mocks.advertisingClient.get.mockResolvedValueOnce({
      data: {
        code: 0,
        message: 'ok',
        data: {
          decisionId: 21,
          creativeVersionId: 31,
          token: 'opaque-token',
          reused: false,
          adLabel: '广告',
          accessibleAdLabel: '广告内容',
          headline: '商业标题',
          bodyText: '商业正文',
          callToAction: 'BOOK',
          targetType: 'VENUE_LIST',
          targetId: null,
          deepLink: '/venues',
          policyVersion: 'G-R7-20260803-v1',
          expiresAt: '2026-08-03T16:00:00'
        }
      }
    })
    mocks.advertisingClient.post.mockResolvedValueOnce({
      data: { code: 0, message: 'ok', data: { resultStatus: 'ACCEPTED', eventType: 'CLICK', replayed: false } }
    })

    const decision = await advertisingApi.homeCommercialDecision()
    const result = await advertisingApi.recordEvent(
      21,
      { eventType: 'CLICK', token: 'opaque-token' },
      'home-commercial:21:CLICK'
    )

    expect(mocks.advertisingClient.get).toHaveBeenCalledWith('/advertising/HOME_COMMERCIAL/decision')
    expect(mocks.advertisingClient.post).toHaveBeenCalledWith(
      '/advertising/decisions/21/events',
      { eventType: 'CLICK', token: 'opaque-token' },
      { headers: { 'Idempotency-Key': 'home-commercial:21:CLICK' } }
    )
    expect(decision?.deepLink).toBe('/venues')
    expect(result.resultStatus).toBe('ACCEPTED')
  })

  it('uses the protected client for privacy preferences and bounded admin analysis', async () => {
    const preference = {
      commercialEnabled: false,
      measurementEnabled: true,
      policyVersion: 'G-R7-20260803-v1',
      changedAt: null
    }
    mocks.http.get.mockResolvedValueOnce(preference).mockResolvedValueOnce({ decisions: 2 })
    mocks.http.put.mockResolvedValueOnce(preference)

    await advertisingApi.preferences()
    await advertisingApi.updatePreferences({ commercialEnabled: true, measurementEnabled: true })
    await advertisingApi.admin.overview('2026-08-01T00:00:00', '2026-08-03T00:00:00')

    expect(mocks.http.get).toHaveBeenNthCalledWith(1, '/advertising/preferences', undefined)
    expect(mocks.http.put).toHaveBeenCalledWith('/advertising/preferences', {
      commercialEnabled: true,
      measurementEnabled: true
    }, undefined)
    expect(mocks.http.get).toHaveBeenNthCalledWith(2, '/content-admin/advertising/analysis/overview', {
      params: { from: '2026-08-01T00:00:00', until: '2026-08-03T00:00:00' }
    })
  })

  it('returns an optional null decision without creating a shell', async () => {
    mocks.advertisingClient.get.mockResolvedValueOnce({
      data: { code: 0, message: 'ok', data: null }
    })

    await expect(advertisingApi.homeCommercialDecision()).resolves.toBeNull()
  })

  it('fails closed when the server decision has no bindable token', async () => {
    mocks.advertisingClient.get.mockResolvedValueOnce({
      data: {
        code: 0,
        message: 'ok',
        data: {
          decisionId: 21,
          creativeVersionId: 31,
          token: null,
          reused: true,
          adLabel: '广告',
          accessibleAdLabel: '广告内容',
          headline: '不可测量的商业内容',
          bodyText: '',
          callToAction: 'VIEW',
          targetType: 'VENUE_LIST',
          targetId: null,
          deepLink: '/venues',
          policyVersion: 'G-R7-20260803-v1',
          expiresAt: '2026-08-03T16:00:00'
        }
      }
    })

    await expect(advertisingApi.homeCommercialDecision()).resolves.toBeNull()
  })

  it('deduplicates an in-flight event and recovers it with the same idempotency key', async () => {
    const result = { resultStatus: 'ACCEPTED', eventType: 'CLICK', replayed: false } as const
    mocks.advertisingClient.post
      .mockRejectedValueOnce(new Error('temporary network failure'))
      .mockResolvedValueOnce({ data: { code: 0, message: 'ok', data: result } })

    const first = advertisingApi.recordEventWithRecovery(
      21,
      { eventType: 'CLICK', token: 'opaque-token' },
      'home-commercial:21:CLICK'
    )
    const duplicate = advertisingApi.recordEventWithRecovery(
      21,
      { eventType: 'CLICK', token: 'opaque-token' },
      'home-commercial:21:CLICK'
    )

    expect(duplicate).toBe(first)
    expect(mocks.advertisingClient.post).toHaveBeenCalledOnce()

    await vi.advanceTimersByTimeAsync(ADVERTISING_EVENT_RETRY_DELAYS_MS[0])
    await expect(first).resolves.toEqual(result)
    expect(mocks.advertisingClient.post).toHaveBeenCalledTimes(2)
    expect(mocks.advertisingClient.post).toHaveBeenNthCalledWith(
      2,
      '/advertising/decisions/21/events',
      { eventType: 'CLICK', token: 'opaque-token' },
      { headers: { 'Idempotency-Key': 'home-commercial:21:CLICK' } }
    )
  })

  it('rejects after the bounded retry budget is exhausted', async () => {
    const failure = new Error('measurement unavailable')
    mocks.advertisingClient.post.mockRejectedValue(failure)

    const pending = advertisingApi.recordEventWithRecovery(
      22,
      { eventType: 'CLIENT_VIEWABLE', token: 'opaque-token', visibleRatio: 0.5, visibleDurationMs: 1000 },
      'home-commercial:22:CLIENT_VIEWABLE'
    )
    const outcome = expect(pending).rejects.toBe(failure)

    await vi.advanceTimersByTimeAsync(ADVERTISING_EVENT_RETRY_DELAYS_MS[0])
    await vi.advanceTimersByTimeAsync(ADVERTISING_EVENT_RETRY_DELAYS_MS[1])

    await outcome
    expect(mocks.advertisingClient.post).toHaveBeenCalledTimes(3)
  })

  it('does not retry a deterministic client rejection', async () => {
    const failure = { response: { status: 400 } }
    mocks.advertisingClient.post.mockRejectedValueOnce(failure)

    const pending = advertisingApi.recordEventWithRecovery(
      23,
      { eventType: 'CLICK', token: 'opaque-token' },
      'home-commercial:23:CLICK'
    )
    const outcome = expect(pending).rejects.toBe(failure)

    await outcome
    expect(mocks.advertisingClient.post).toHaveBeenCalledOnce()
  })
})
