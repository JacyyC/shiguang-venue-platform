import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  directoryApi,
  directoryKey,
  groupDirectoryByDate,
  isHostedByViewer,
  type DirectoryItem
} from './directory'
import { meTimeApi } from './meTime'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), put: vi.fn()
}))

vi.mock('./http', () => ({ default: httpMock }))

const item = (overrides: Partial<DirectoryItem> = {}): DirectoryItem => ({
  sourceType: 'ACTIVITY',
  sourceId: 1,
  groupId: 11,
  organizerUserId: 21,
  activityType: 'SPORTS',
  title: '夜跑',
  startTime: '2099-08-01T09:00:00',
  endTime: '2099-08-01T11:00:00',
  locationType: 'CAMPUS_AREA',
  commitmentStatus: 'UNCONFIRMED',
  capacityTotal: 8,
  participantCount: 3,
  status: 'PUBLISHED',
  ...overrides
})

describe('directoryApi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('serializes multi-value types and forwards bounded cursor params', async () => {
    httpMock.get.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })

    await directoryApi.directory({
      keyword: '夜跑',
      types: ['ACTIVITY', 'COMPANION'],
      onlyAvailable: true,
      cursor: 'abc',
      limit: 50
    })

    expect(httpMock.get).toHaveBeenCalledWith('/community/directory', {
      params: {
        keyword: '夜跑',
        types: 'ACTIVITY,COMPANION',
        onlyAvailable: true,
        cursor: 'abc',
        limit: 50
      }
    })
  })

  it('omits empty type filters instead of sending a blank list', async () => {
    httpMock.get.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })

    await directoryApi.directory({ types: [] })

    expect(httpMock.get).toHaveBeenCalledWith('/community/directory', { params: { types: undefined } })
  })

  it('reads hosted records through the dedicated owner endpoint', async () => {
    httpMock.get.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })

    await directoryApi.myHosted({ cursor: 'xyz', limit: 20 })
    await meTimeApi.counts()

    expect(httpMock.get).toHaveBeenCalledWith('/community/my/hosted', {
      params: { cursor: 'xyz', limit: 20 }
    })
    expect(httpMock.get).toHaveBeenCalledWith('/me/time/counts')
  })
})

describe('directory presentation helpers', () => {
  it('keeps same numeric ids distinct across source types', () => {
    expect(directoryKey(item({ sourceType: 'ACTIVITY', sourceId: 9 })))
      .toBe('ACTIVITY:9')
    expect(directoryKey(item({ sourceType: 'COMPANION', sourceId: 9 })))
      .toBe('COMPANION:9')
  })

  it('groups rows by start date preserving server time order', () => {
    const groups = groupDirectoryByDate([
      item({ sourceType: 'ACTIVITY', sourceId: 1, startTime: '2099-08-01T09:00:00' }),
      item({ sourceType: 'COMPANION', sourceId: 2, startTime: '2099-08-01T14:00:00' }),
      item({ sourceType: 'ACTIVITY', sourceId: 3, startTime: '2099-08-02T08:00:00' })
    ])

    expect(groups.map(group => group.date)).toEqual(['2099-08-01', '2099-08-02'])
    expect(groups[0].items.map(row => directoryKey(row)))
      .toEqual(['ACTIVITY:1', 'COMPANION:2'])
    expect(groups[1].items.map(row => directoryKey(row))).toEqual(['ACTIVITY:3'])
  })

  it('treats only organizer and owner roles as hosted by viewer', () => {
    expect(isHostedByViewer(item({ viewerParticipationRole: 'ORGANIZER' }))).toBe(true)
    expect(isHostedByViewer(item({ viewerParticipationRole: 'OWNER' }))).toBe(true)
    expect(isHostedByViewer(item({ viewerParticipationRole: 'PARTICIPANT' }))).toBe(false)
    expect(isHostedByViewer(item({ viewerParticipationRole: null }))).toBe(false)
  })
})
