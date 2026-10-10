import { beforeEach, describe, expect, it, vi } from 'vitest'
import { activityApi, type CreateActivityPayload } from './activity'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), put: vi.fn()
}))

vi.mock('./http', () => ({ default: httpMock }))

describe('activityApi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('forwards structured discovery filters and reads the authenticated viewer state', async () => {
    httpMock.get.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })

    await activityApi.activities({ activityType: 'STUDY', venueId: 3, cursorId: 80, limit: 20 })
    await activityApi.companionViewerState(71)

    expect(httpMock.get).toHaveBeenCalledWith('/community/activities', {
      params: { activityType: 'STUDY', venueId: 3, cursorId: 80, limit: 20 }
    })
    expect(httpMock.get).toHaveBeenCalledWith('/community/companion-requests/71/my-state')
  })

  it('forwards the local degrade config on tolerated discovery reads (AR-03)', async () => {
    httpMock.get.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })

    await activityApi.activities({ limit: 4 }, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    await activityApi.companions({ limit: 2 }, { skipGlobalErrorRedirect: true })

    expect(httpMock.get).toHaveBeenCalledWith('/community/activities', {
      params: { limit: 4 },
      skipGlobalErrorRedirect: true,
      skipGlobalErrorToast: true
    })
    expect(httpMock.get).toHaveBeenCalledWith('/community/companion-requests', {
      params: { limit: 2 },
      skipGlobalErrorRedirect: true
    })
  })

  it('forwards stable idempotency keys on lifecycle, application and matching writes', async () => {
    httpMock.post.mockResolvedValue({})
    const payload: CreateActivityPayload = {
      groupId: 11,
      activityType: 'STUDY',
      title: '期末复习',
      startTime: '2099-08-01T09:00:00',
      endTime: '2099-08-01T11:00:00',
      capacityTotal: 6,
      location: { type: 'CAMPUS_AREA', areaCode: 'NORTH_CAMPUS' }
    }

    await activityApi.createActivity(payload, 'activity-create-key')
    await activityApi.acceptApplication(51, 'application-accept-key')
    await activityApi.refreshMatches(71, 20, 'match-refresh-key')

    expect(httpMock.post).toHaveBeenCalledWith('/community/activities', payload, {
      headers: { 'Idempotency-Key': 'activity-create-key' }
    })
    expect(httpMock.post).toHaveBeenCalledWith('/community/applications/51/accept', undefined, {
      headers: { 'Idempotency-Key': 'application-accept-key' }
    })
    expect(httpMock.post).toHaveBeenCalledWith('/community/companion-requests/71/matches/refresh', { limit: 20 }, {
      headers: { 'Idempotency-Key': 'match-refresh-key' }
    })
  })

  it('keeps optimistic location versions and rejection reasons on the real contract', async () => {
    httpMock.put.mockResolvedValue({})
    httpMock.post.mockResolvedValue({})

    await activityApi.updateActivityLocation(41, {
      versionNo: 3,
      location: { type: 'VENUE_SPACE', venueId: 2, spaceId: 8, reservationId: 90 }
    }, 'location-key')
    await activityApi.rejectApplication(51, 'SCHEDULE_MISMATCH', 'reject-key')

    expect(httpMock.put).toHaveBeenCalledWith('/community/activities/41/location', {
      versionNo: 3,
      location: { type: 'VENUE_SPACE', venueId: 2, spaceId: 8, reservationId: 90 }
    }, { headers: { 'Idempotency-Key': 'location-key' } })
    expect(httpMock.post).toHaveBeenCalledWith('/community/applications/51/reject', {
      reasonCode: 'SCHEDULE_MISMATCH'
    }, { headers: { 'Idempotency-Key': 'reject-key' } })
  })
})
