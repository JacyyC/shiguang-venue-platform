import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn()
}))

vi.mock('./http', () => ({
  default: httpMock
}))

describe('checkinApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses the locked B4 user checkin endpoints', async () => {
    const { checkinApi } = await import('./checkin')

    await checkinApi.my()
    await checkinApi.reissueCredential(99)

    expect(httpMock.get).toHaveBeenCalledWith('/checkins/my', undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/reservations/99/checkin-credential', undefined, undefined)
  })

  it('uses the locked B4 venue-admin checkin endpoints', async () => {
    const { adminCheckinApi } = await import('./checkin')

    await adminCheckinApi.list({
      pageNum: 1,
      pageSize: 20,
      checkinStatus: 'NOT_CHECKED_IN',
      noShowStatus: 'NOT_EVALUATED'
    })
    await adminCheckinApi.verify({ credential: '123456', deviceRef: 'frontdesk' })
    await adminCheckinApi.manualPass(99, { reasonCode: 'MANUAL_PASS', reasonText: '身份证确认', deviceRef: 'frontdesk' })
    await adminCheckinApi.confirmNoShow(99, { reasonCode: 'NO_SHOW_CONFIRMED', reasonText: '超时未到' })
    await adminCheckinApi.revokeNoShow(99, { reasonCode: 'NO_SHOW_REVOKED', reasonText: '用户已补登记' })

    expect(httpMock.get).toHaveBeenCalledWith('/admin/checkins', {
      params: {
        pageNum: 1,
        pageSize: 20,
        checkinStatus: 'NOT_CHECKED_IN',
        noShowStatus: 'NOT_EVALUATED'
      }
    })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/checkins/verify', {
      credential: '123456',
      deviceRef: 'frontdesk'
    }, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/checkins/manual-pass/99', {
      reasonCode: 'MANUAL_PASS',
      reasonText: '身份证确认',
      deviceRef: 'frontdesk'
    }, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/checkins/no-show/99/confirm', {
      reasonCode: 'NO_SHOW_CONFIRMED',
      reasonText: '超时未到'
    }, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/checkins/no-show/99/revoke', {
      reasonCode: 'NO_SHOW_REVOKED',
      reasonText: '用户已补登记'
    }, undefined)
  })
})
