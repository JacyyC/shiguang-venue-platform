import { beforeEach, describe, expect, it, vi } from 'vitest'
import { placementApi } from './placement'

const mocks = vi.hoisted(() => ({
  placementClient: {
    get: vi.fn(),
    post: vi.fn(),
    interceptors: { request: { use: vi.fn() } }
  },
  http: {
    get: vi.fn(),
    post: vi.fn()
  }
}))

vi.mock('axios', () => ({
  default: { create: vi.fn(() => mocks.placementClient) }
}))

vi.mock('./http', () => ({ default: mocks.http }))

describe('placementApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('keeps the public decision and event contract on an independent client', async () => {
    mocks.placementClient.get.mockResolvedValueOnce({
      data: {
        code: 0,
        message: 'ok',
        data: {
          decisionId: 7,
          candidateVersionId: 8,
          token: 'opaque-token',
          reused: false,
          title: '首页宣传',
          summary: '摘要',
          imageUrl: null,
          deepLink: '/activities/41',
          expiresAt: '2026-08-02T10:00:00'
        }
      }
    })
    mocks.placementClient.post.mockResolvedValueOnce({
      data: { code: 0, message: 'ok', data: { resultStatus: 'ACCEPTED', eventType: 'CLICK' } }
    })

    const decision = await placementApi.homePrimaryDecision()
    const result = await placementApi.recordEvent(7, { eventType: 'CLICK', token: 'opaque-token' }, 'home-primary:7:CLICK')

    expect(mocks.placementClient.get).toHaveBeenCalledWith('/placements/HOME_PRIMARY/decision')
    expect(mocks.placementClient.post).toHaveBeenCalledWith(
      '/placements/decisions/7/events',
      { eventType: 'CLICK', token: 'opaque-token' },
      { headers: { 'Idempotency-Key': 'home-primary:7:CLICK' } }
    )
    expect(decision?.deepLink).toBe('/activities/41')
    expect(result.resultStatus).toBe('ACCEPTED')
  })

  it('maps admin calls to the existing http client and preserves bounded query parameters', async () => {
    mocks.http.get
      .mockResolvedValueOnce([{ id: 1, slotCode: 'HOME_PRIMARY', slotName: '首页自营宣传位', status: 'ENABLED' }])
      .mockResolvedValueOnce([])
    mocks.http.post.mockResolvedValueOnce({ id: 3, status: 'DRAFT' })

    await placementApi.admin.slots()
    await placementApi.admin.candidates(1, 25)
    await placementApi.admin.publish(3)

    expect(mocks.http.get).toHaveBeenNthCalledWith(1, '/content-admin/placements/slots')
    expect(mocks.http.get).toHaveBeenNthCalledWith(2, '/content-admin/placements/candidates', {
      params: { slotId: 1, limit: 25 }
    })
    expect(mocks.http.post).toHaveBeenCalledWith('/content-admin/placements/candidates/3/publish')
  })

  it('rejects a non-zero placement payload without invoking a global redirect path', async () => {
    mocks.placementClient.get.mockResolvedValueOnce({
      data: { code: 403, message: 'forbidden', data: null }
    })

    await expect(placementApi.homePrimaryDecision()).rejects.toThrow('forbidden')
  })
})
