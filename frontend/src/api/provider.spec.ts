import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFile } from 'node:fs/promises'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))

vi.mock('./http', () => ({ default: httpMock }))

describe('providerApi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('calls C provider observability endpoints', async () => {
    const { providerApi } = await import('./provider')
    const payload = { latitude: 31.2304, longitude: 121.4737, locationLabel: '上海演示场馆' }

    await providerApi.overview()
    await providerApi.calls({ providerDomain: 'WEATHER', sourceType: 'SYSTEM' })
    await providerApi.weatherSnapshots({ venueId: 1 })
    await providerApi.refreshWeather(1)
    await providerApi.riskSnapshots({ venueId: 1 })
    await providerApi.refreshRisk(1)
    await providerApi.metricSnapshots({ metricKey: 'redis_hit_rate' })
    await providerApi.refreshMetrics()
    await providerApi.locations()
    await providerApi.saveLocation(1, payload)

    expect(httpMock.get).toHaveBeenCalledWith('/admin/providers/overview', undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/providers/calls', {
      params: { providerDomain: 'WEATHER', sourceType: 'SYSTEM' }
    })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/providers/weather/snapshots', { params: { venueId: 1 } })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/providers/weather/refresh', undefined, { params: { venueId: 1 } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/providers/risk/snapshots', { params: { venueId: 1 } })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/providers/risk/refresh', undefined, { params: { venueId: 1 } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/providers/metrics/snapshots', { params: { metricKey: 'redis_hit_rate' } })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/providers/metrics/refresh', undefined, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/providers/venues/locations', undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/providers/venues/1/location', payload, undefined)
  })

  it('declares the overview freshness fields while retaining dynamic provider identities', async () => {
    const source = await readFile('src/api/provider.ts', 'utf8')
    const start = source.indexOf('export interface ProviderStatusResponse {')
    const declaration = source.slice(start, source.indexOf('\n}', start) + 2)

    expect(declaration).toContain('providerKey: string')
    expect(declaration).toContain('providerName: string')
    expect(declaration).toContain('providerDomain: string')
    expect(declaration).toContain('enabled?: boolean')
    expect(declaration).toContain('configVersion?: string')
    expect(declaration).toContain('freshnessSeconds?: number')
    expect(declaration).toContain('freshnessDeadlineAt?: string')
    expect(declaration).toContain('evidenceAgeSeconds?: number')
  })

  it('passes the generic scheduled source filter as the exact call-log query object', async () => {
    const { providerApi } = await import('./provider')

    await providerApi.calls({ sourceType: 'SCHEDULED' })

    expect(httpMock.get).toHaveBeenCalledWith('/admin/providers/calls', {
      params: { sourceType: 'SCHEDULED' }
    })
  })
})
