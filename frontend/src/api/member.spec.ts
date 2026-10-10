import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn()
}))

vi.mock('./http', () => ({
  default: httpMock
}))

describe('memberApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses the locked B2 member endpoint contract', async () => {
    const { adminMemberApi, memberApi } = await import('./member')
    const { reservationApi } = await import('./reservation')
    const payload = {
      spaceId: 10,
      startTime: '2026-07-02T10:00:00',
      endTime: '2026-07-02T12:00:00',
      slots: [],
      contactName: '陈同学',
      contactPhone: '13800000000',
      remark: ''
    }

    await adminMemberApi.levels()
    await adminMemberApi.createLevel({
      levelCode: 'GOLD',
      levelName: '金卡会员',
      growthThreshold: 2000,
      discountRate: 0.9,
      benefitText: '9 折预约'
    })
    await adminMemberApi.updateLevel(3, {
      levelCode: 'GOLD',
      levelName: '金卡会员',
      growthThreshold: 2000,
      discountRate: 0.9,
      benefitText: '9 折预约'
    })
    await adminMemberApi.updateLevelStatus(3, 'DISABLED')
    await adminMemberApi.accounts({ pageNum: 1, pageSize: 20, keyword: 'chen' })
    await adminMemberApi.account(8)
    await adminMemberApi.overview()
    await memberApi.me()
    await reservationApi.pricingPreview(payload)

    expect(httpMock.get).toHaveBeenCalledWith('/admin/members/levels', undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/members/levels', {
      levelCode: 'GOLD',
      levelName: '金卡会员',
      growthThreshold: 2000,
      discountRate: 0.9,
      benefitText: '9 折预约'
    }, undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/members/levels/3', {
      levelCode: 'GOLD',
      levelName: '金卡会员',
      growthThreshold: 2000,
      discountRate: 0.9,
      benefitText: '9 折预约'
    }, undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/members/levels/3/status', { status: 'DISABLED' }, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/members/accounts', {
      params: { pageNum: 1, pageSize: 20, keyword: 'chen' }
    })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/members/accounts/8', undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/members/overview', undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/members/me', undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/reservations/pricing-preview', payload)
  })

  it('calls the member reward self-service endpoints with the locked contract', async () => {
    const { memberRewardApi } = await import('./member')
    const redeemPayload = { catalogVersionId: 11, idempotencyKey: 'idem-1' }

    await memberRewardApi.catalog()
    await memberRewardApi.preview(5)
    await memberRewardApi.redeem(5, redeemPayload)
    await memberRewardApi.history({ pageNum: 1, pageSize: 10 })

    expect(httpMock.get).toHaveBeenCalledWith('/member-rewards/catalog', undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/member-rewards/catalog/5/preview', undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/member-rewards/catalog/5/redemptions', redeemPayload, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/member-rewards/redemptions', { params: { pageNum: 1, pageSize: 10 } })
  })

  it('calls the admin reward management endpoints with the locked contract', async () => {
    const { adminMemberRewardApi } = await import('./member')
    const catalogPayload = { itemCode: 'R1', itemName: '权益', descriptionText: '说明' }
    const versionPayload = {
      pointsCost: 100,
      minimumLevelCode: 'GOLD',
      couponTemplateId: 7,
      validityDays: 30
    }

    await adminMemberRewardApi.createCatalogItem(catalogPayload)
    await adminMemberRewardApi.listCatalogItems()
    await adminMemberRewardApi.updateCatalogItemStatus(5, 'INACTIVE')
    await adminMemberRewardApi.publishVersion(5, versionPayload)
    await adminMemberRewardApi.exceptions({ pageNum: 1, pageSize: 10 })
    await adminMemberRewardApi.retry(88)
    await adminMemberRewardApi.compensate(88, '券失效补偿')
    await adminMemberRewardApi.events({ pageNum: 1, pageSize: 10 })

    expect(httpMock.post).toHaveBeenCalledWith('/member-rewards/admin/catalog', catalogPayload, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/member-rewards/admin/catalog', undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/member-rewards/admin/catalog/5/status', { status: 'INACTIVE' }, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/member-rewards/admin/catalog/5/versions', versionPayload, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/member-rewards/admin/exceptions', { params: { pageNum: 1, pageSize: 10 } })
    expect(httpMock.post).toHaveBeenCalledWith('/member-rewards/admin/redemptions/88/retry', undefined, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/member-rewards/admin/redemptions/88/compensate', { reason: '券失效补偿' }, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/member-rewards/admin/events', { params: { pageNum: 1, pageSize: 10 } })
  })
})
