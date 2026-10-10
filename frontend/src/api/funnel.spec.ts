import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn()
}))

vi.mock('./http', () => ({
  default: httpMock
}))

describe('funnelApi', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('uses B5 funnel event and favorite endpoints', async () => {
    const { funnelApi } = await import('./funnel')

    await funnelApi.record({ sessionKey: 'sess-1', eventName: 'SEARCH_SUBMITTED', eventStage: 'SEARCH' })
    await funnelApi.myFavorites()
    await funnelApi.favoriteVenue(8, { sessionKey: 'sess-1', eventName: 'FAVORITE_ADDED', eventStage: 'FAVORITE' })
    await funnelApi.cancelFavorite(8, { sessionKey: 'sess-1', eventName: 'FAVORITE_REMOVED', eventStage: 'FAVORITE' })
    await funnelApi.favoriteStatus(8)

    expect(httpMock.post).toHaveBeenCalledWith('/funnel/events', expect.any(Object), { skipGlobalErrorToast: true })
    expect(httpMock.get).toHaveBeenCalledWith('/funnel/favorites/my', undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/funnel/favorites/venues/8', expect.any(Object), undefined)
    expect(httpMock.delete).toHaveBeenCalledWith('/funnel/favorites/venues/8', { data: expect.any(Object) })
    expect(httpMock.get).toHaveBeenCalledWith('/funnel/favorites/venues/8/status')
  })

  it('keeps tracking failures out of core user flows', async () => {
    const debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => undefined)
    httpMock.post.mockRejectedValueOnce(new Error('network'))
    const { useFunnelStore } = await import('@/stores/funnel')
    const funnel = useFunnelStore()

    await expect(funnel.track({ eventName: 'SEARCH_SUBMITTED', eventStage: 'SEARCH' })).resolves.toBeUndefined()

    expect(debugSpy).toHaveBeenCalledWith('funnel track failed')
    debugSpy.mockRestore()
  })
})
