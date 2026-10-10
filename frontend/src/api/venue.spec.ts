import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn()
}))

vi.mock('./http', () => ({
  default: httpMock
}))

describe('venueAdminApi 场馆与场地端点合同（BATCH-02）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('覆盖资源面所需的读取与写入端点', async () => {
    const { venueAdminApi } = await import('./venue')

    await venueAdminApi.manageable()
    expect(httpMock.get).toHaveBeenCalledWith('/admin/venues/manageable', undefined)

    await venueAdminApi.updateVenue(101, { name: '北岸生活场馆', area: '北岸片区' })
    expect(httpMock.put).toHaveBeenCalledWith('/admin/venues/101', {
      name: '北岸生活场馆',
      area: '北岸片区'
    }, undefined)

    await venueAdminApi.createSpace({ venueId: 101, name: '羽毛球2号场' })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/spaces', { venueId: 101, name: '羽毛球2号场' }, undefined)

    await venueAdminApi.updateSpace(73, { name: '羽毛球2号场' })
    expect(httpMock.put).toHaveBeenCalledWith('/admin/spaces/73', { name: '羽毛球2号场' }, undefined)

    await venueAdminApi.updateSpaceStatus(73, 'MAINTENANCE')
    expect(httpMock.put).toHaveBeenCalledWith('/admin/spaces/73/status', { status: 'MAINTENANCE' }, undefined)

    await venueAdminApi.resourceRule(73)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/spaces/73/resource-rule', undefined)

    await venueAdminApi.configureResourceRule(73, {
      resourceCategory: 'SPORTS_FIELD',
      bookingMode: 'ASSIGNED_SEAT',
      capacityLimit: null,
      seats: [{ seatCode: 'A-01', seatLabel: '座位 1' }],
      reasonText: '新增座位'
    } as never)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/spaces/73/resource-rule', {
      resourceCategory: 'SPORTS_FIELD',
      bookingMode: 'ASSIGNED_SEAT',
      capacityLimit: null,
      seats: [{ seatCode: 'A-01', seatLabel: '座位 1' }],
      reasonText: '新增座位'
    }, undefined)

    await venueAdminApi.maintenances(73)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/spaces/73/maintenances', undefined)

    await venueAdminApi.createMaintenance(73, {
      startTime: '2026-09-13T16:00:00',
      endTime: '2026-09-13T18:00:00',
      reason: '阅读区灯具检修'
    })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/spaces/73/maintenances', {
      startTime: '2026-09-13T16:00:00',
      endTime: '2026-09-13T18:00:00',
      reason: '阅读区灯具检修'
    }, undefined)

    await venueAdminApi.cancelMaintenance(9)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/maintenances/9/cancel', undefined, undefined)
  })
})
