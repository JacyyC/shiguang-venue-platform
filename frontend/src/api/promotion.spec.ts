import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn()
}))

vi.mock('./http', () => ({
  default: httpMock
}))

describe('promotionApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses the locked B3 admin promotion endpoint contract', async () => {
    const { promotionAdminApi } = await import('./promotion')
    const campaignPayload = {
      campaignCode: 'SUMMER2026',
      campaignName: '暑期活动',
      campaignType: 'SEASONAL',
      venueId: 2,
      spaceId: 10,
      targetMemberLevel: 'GOLD',
      startTime: '2026-07-02T00:00:00',
      endTime: '2026-08-31T23:59:59',
      budgetAmount: 2000,
      discountType: 'CASH',
      discountValue: 20,
      maxDiscountAmount: 50,
      allowCouponStack: true,
      status: 'DRAFT'
    }
    const couponTemplatePayload = {
      templateCode: 'CASH20',
      templateName: '满 100 减 20',
      couponType: 'CASH',
      discountValue: 20,
      thresholdAmount: 100,
      maxDiscountAmount: 20,
      totalQuantity: 100,
      perUserLimit: 1,
      validStartTime: '2026-07-02T00:00:00',
      validEndTime: '2026-08-31T23:59:59',
      campaignId: 5,
      venueId: 2,
      spaceId: 10,
      allowActivityStack: true,
      costOwner: '运营',
      status: 'DRAFT'
    }
    const priceRulePayload = {
      ruleCode: 'WEEKEND_PEAK',
      ruleName: '周末峰值价',
      ruleType: 'PEAK',
      venueId: 2,
      spaceId: 10,
      memberLevelCode: 'GOLD',
      startTime: '2026-07-02T00:00:00',
      endTime: '2026-08-31T23:59:59',
      dayOfWeekMask: '6,7',
      startClock: '18:00:00',
      endClock: '22:00:00',
      adjustmentType: 'DELTA',
      adjustmentValue: 30,
      priority: 10,
      status: 'DRAFT',
      reasonText: '周末晚高峰'
    }

    await promotionAdminApi.campaigns({ pageNum: 1 })
    await promotionAdminApi.createCampaign(campaignPayload)
    await promotionAdminApi.updateCampaign(7, campaignPayload)
    await promotionAdminApi.updateCampaignStatus(7, 'ACTIVE')
    await promotionAdminApi.couponTemplates({ pageNum: 2, pageSize: 10 })
    // B4-P9：跨域只读调用方需要能跳过全局 403 跳转，config 必须透传。
    await promotionAdminApi.couponTemplates(
      { pageNum: 1, pageSize: 100, status: 'ACTIVE' },
      { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
    )
    await promotionAdminApi.createCouponTemplate(couponTemplatePayload)
    await promotionAdminApi.updateCouponTemplate(8, couponTemplatePayload)
    await promotionAdminApi.updateCouponTemplateStatus(8, 'PAUSED')
    await promotionAdminApi.couponRedemptions({ pageNum: 1, status: 'USED' })
    await promotionAdminApi.priceRules({ pageNum: 1 })
    await promotionAdminApi.createPriceRule(priceRulePayload)
    await promotionAdminApi.updatePriceRule(9, priceRulePayload)
    await promotionAdminApi.updatePriceRuleStatus(9, 'ACTIVE')
    await promotionAdminApi.overview()

    // S05：本面所有方法都接受可选的 per-call 配置（面内承接 403/409，不整页跳转），
    // 因此无配置调用多一个显式 undefined 实参，与第三批 notice/feedback 客户端同款。
    expect(httpMock.get).toHaveBeenCalledWith('/admin/promotions/campaigns', { params: { pageNum: 1 } })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/promotions/campaigns', campaignPayload, undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/promotions/campaigns/7', campaignPayload, undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/promotions/campaigns/7/status', undefined, { params: { status: 'ACTIVE' } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/promotions/coupon-templates', { params: { pageNum: 2, pageSize: 10 } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/promotions/coupon-templates', {
      params: { pageNum: 1, pageSize: 100, status: 'ACTIVE' },
      skipGlobalErrorRedirect: true,
      skipGlobalErrorToast: true
    })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/promotions/coupon-templates', couponTemplatePayload, undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/promotions/coupon-templates/8', couponTemplatePayload, undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/promotions/coupon-templates/8/status', undefined, { params: { status: 'PAUSED' } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/promotions/coupon-redemptions', { params: { pageNum: 1, status: 'USED' } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/promotions/price-rules', { params: { pageNum: 1 } })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/promotions/price-rules', priceRulePayload, undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/promotions/price-rules/9', priceRulePayload, undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/promotions/price-rules/9/status', undefined, { params: { status: 'ACTIVE' } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/promotions/overview', undefined)
  })

  it('uses the locked B3 user coupon endpoint contract', async () => {
    const { couponApi } = await import('./promotion')

    await couponApi.claimable({ venueId: 2, spaceId: 10 })
    await couponApi.my({ pageNum: 1, pageSize: 20, status: 'CLAIMED' })
    await couponApi.claim(5)

    expect(httpMock.get).toHaveBeenCalledWith('/coupons/claimable', { params: { venueId: 2, spaceId: 10 } })
    expect(httpMock.get).toHaveBeenCalledWith('/coupons/my', { params: { pageNum: 1, pageSize: 20, status: 'CLAIMED' } })
    expect(httpMock.post).toHaveBeenCalledWith('/coupons/5/claim', undefined, undefined)
  })
})
