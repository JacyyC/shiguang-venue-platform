import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn()
}))

vi.mock('./http', () => ({ default: httpMock }))

import { meTimeApi } from './meTime'

describe('meTimeApi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('paginates the read-only aggregated my-time feed', async () => {
    httpMock.get.mockResolvedValue({
      records: [
        {
          sourceType: 'ACTIVITY',
          sourceId: 50,
          title: '城市夜跑',
          status: 'PUBLISHED',
          pending: false,
          detailPath: '/activities/50'
        }
      ],
      total: 1,
      pageNum: 1,
      pageSize: 20
    })

    await meTimeApi.myTime({ pageNum: 1, pageSize: 20 })

    expect(httpMock.get).toHaveBeenCalledWith('/me/time', { params: { pageNum: 1, pageSize: 20 } })
  })

  it('defaults to the first page when called without params', async () => {
    httpMock.get.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 10 })

    await meTimeApi.myTime()

    expect(httpMock.get).toHaveBeenCalledWith('/me/time', { params: {} })
  })
})
