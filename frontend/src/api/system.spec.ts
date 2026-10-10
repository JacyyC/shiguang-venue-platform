import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  delete: vi.fn()
}))

vi.mock('./http', () => ({
  default: httpMock
}))

describe('systemApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses the controlled venue-admin access contracts', async () => {
    const { systemApi } = await import('./system')
    httpMock.get.mockResolvedValue([11, 12])
    httpMock.put.mockResolvedValue(undefined)

    await systemApi.venueAccess(7)
    await systemApi.updateVenueAccess(7, [11, 12])

    expect(httpMock.get).toHaveBeenCalledWith('/system/users/7/venue-access', undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/system/users/7/venue-access', { venueIds: [11, 12] }, undefined)
  })

  it('reads role catalog and current user role selections from controlled endpoints', async () => {
    const { systemApi } = await import('./system')
    httpMock.get.mockResolvedValue([])

    await systemApi.roles()
    await systemApi.userRoles(7)

    expect(httpMock.get).toHaveBeenCalledWith('/system/roles', undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/system/users/7/roles', undefined)
  })
})
