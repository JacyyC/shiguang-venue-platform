import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const promotionApiMock = vi.hoisted(() => ({
  campaigns: vi.fn(),
  createCampaign: vi.fn(),
  updateCampaign: vi.fn(),
  updateCampaignStatus: vi.fn(),
  couponTemplates: vi.fn(),
  createCouponTemplate: vi.fn(),
  updateCouponTemplate: vi.fn(),
  updateCouponTemplateStatus: vi.fn(),
  couponRedemptions: vi.fn(),
  priceRules: vi.fn(),
  createPriceRule: vi.fn(),
  updatePriceRule: vi.fn(),
  updatePriceRuleStatus: vi.fn(),
  overview: vi.fn()
}))
const memberApiMock = vi.hoisted(() => ({ levels: vi.fn() }))
const venueApiMock = vi.hoisted(() => ({
  venueApi: { spaces: vi.fn() },
  venueAdminApi: { manageable: vi.fn() }
}))
const showToast = vi.hoisted(() => vi.fn())
const authState = vi.hoisted(() => ({
  permissions: new Set(['promotion:manage', 'coupon:manage', 'pricing:manage'])
}))

vi.mock('@/api/promotion', () => ({ promotionAdminApi: promotionApiMock }))
vi.mock('@/api/member', () => ({ adminMemberApi: memberApiMock }))
vi.mock('@/api/venue', () => venueApiMock)
vi.mock('@/stores/app', () => ({ useAppStore: () => ({ showToast }) }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: (permission: string) => authState.permissions.has(permission) })
}))

import ShiguangPromotionAdminView from './ShiguangPromotionAdminView.vue'

const dialogProto = (globalThis.HTMLDialogElement as unknown as { prototype: Record<string, () => void> } | undefined)?.prototype
if (dialogProto) {
  dialogProto.showModal = dialogProto.showModal ?? (() => {})
  dialogProto.close = dialogProto.close ?? (() => {})
}

const page = <T>(records: T[], total = records.length) => ({ records, total, pageNum: 1, pageSize: 20 })
const IOS = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

const campaignRow = {
  id: 1,
  campaignCode: 'AUTUMN_WEEKEND',
  campaignName: '把周末留给相聚',
  campaignType: 'SEASONAL',
  venueId: 1,
  spaceId: 10,
  targetMemberLevel: 'SILVER',
  startTime: '2026-09-12T09:00:00',
  endTime: '2026-09-30T22:00:00',
  budgetAmount: 2000,
  discountType: 'CASH',
  discountValue: 20,
  maxDiscountAmount: 20,
  priority: 30,
  allowCouponStack: false,
  status: 'ACTIVE'
}

const couponRow = {
  id: 5,
  templateCode: 'AFTERNOON_10',
  templateName: '下午场馆 10 元券',
  couponType: 'CASH',
  discountValue: 10,
  thresholdAmount: 50,
  maxDiscountAmount: 10,
  totalQuantity: 500,
  issuedQuantity: 128,
  usedQuantity: 64,
  perUserLimit: 1,
  validStartTime: '2026-09-12T00:00:00',
  validEndTime: '2026-10-12T23:59:59',
  campaignId: 1,
  venueId: 1,
  spaceId: 10,
  allowActivityStack: false,
  costOwner: 'PLATFORM',
  status: 'ACTIVE'
}

const ruleRow = {
  id: 9,
  ruleCode: 'READING_OFFPEAK',
  ruleName: '工作日下午阅读时段',
  ruleType: 'OFF_PEAK',
  venueId: 1,
  spaceId: 10,
  memberLevelCode: 'SILVER',
  startTime: '2026-09-12T00:00:00',
  endTime: '2026-12-31T23:59:59',
  dayOfWeekMask: ',1,2,3,4,5,',
  startClock: '13:00:00',
  endClock: '17:00:00',
  adjustmentType: 'PERCENT',
  adjustmentValue: 0.85,
  priority: 20,
  status: 'ACTIVE',
  reasonText: '工作日下午低峰价格'
}

function resetMocks(): void {
  vi.clearAllMocks()
  authState.permissions = new Set(['promotion:manage', 'coupon:manage', 'pricing:manage'])
  promotionApiMock.campaigns.mockResolvedValue(page([campaignRow]))
  promotionApiMock.couponTemplates.mockResolvedValue(page([couponRow]))
  promotionApiMock.couponRedemptions.mockResolvedValue(page([{
    id: 3018,
    templateId: 5,
    templateCode: 'AFTERNOON_10',
    templateName: '下午场馆 10 元券',
    couponType: 'CASH',
    discountValue: 10,
    thresholdAmount: 50,
    status: 'USED',
    claimTime: '2026-09-12T10:12:00',
    orderId: 9018,
    discountAmount: 10
  }]))
  promotionApiMock.priceRules.mockResolvedValue(page([ruleRow]))
  promotionApiMock.overview.mockResolvedValue({
    activeCampaigns: 1,
    activeCouponTemplates: 2,
    activePriceRules: 3,
    claimedCoupons: 5,
    lockedCoupons: 6,
    usedCoupons: 85,
    refundedCoupons: 4,
    totalCouponDiscountAmount: 1060
  })
  promotionApiMock.createCampaign.mockResolvedValue(campaignRow)
  promotionApiMock.updateCampaign.mockResolvedValue(campaignRow)
  promotionApiMock.createCouponTemplate.mockResolvedValue(couponRow)
  promotionApiMock.updateCouponTemplate.mockResolvedValue(couponRow)
  promotionApiMock.createPriceRule.mockResolvedValue(ruleRow)
  promotionApiMock.updatePriceRule.mockResolvedValue(ruleRow)
  memberApiMock.levels.mockResolvedValue([
    { id: 1, levelCode: 'BASIC', levelName: '普通会员', growthThreshold: 0, discountRate: 1, status: 'ACTIVE' },
    { id: 2, levelCode: 'SILVER', levelName: '银卡会员', growthThreshold: 1000, discountRate: 0.95, status: 'ACTIVE' }
  ])
  venueApiMock.venueAdminApi.manageable.mockResolvedValue([{ id: 1, name: '北岸生活场馆' }])
  venueApiMock.venueApi.spaces.mockResolvedValue([{ id: 10, name: '羽毛球场' }])
}

async function mountView(path = '/system-admin/promotions'): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/system-admin/promotions', alias: '/system-admin/promotion-pricing', name: 'SystemPromotions', component: ShiguangPromotionAdminView }
    ]
  })
  await router.push(path)
  await router.isReady()
  const host = document.createElement('div')
  document.body.appendChild(host)
  const wrapper = mount(ShiguangPromotionAdminView, { global: { plugins: [router] }, attachTo: host })
  await flushPromises()
  await flushPromises()
  return { wrapper, router }
}

type Vm = { view: string; surface: string; dialog: { kind: string; title: string; submit: string } | null }

describe('拾光促销与定价面', () => {
  beforeEach(() => {
    resetMocks()
    document.body.innerHTML = ''
  })

  it('renders the campaign tab with the reference date block, scope and value', async () => {
    const { wrapper } = await mountView()
    expect((wrapper.vm as unknown as Vm).view).toBe('campaigns')
    expect(promotionApiMock.campaigns).toHaveBeenCalledWith({ pageNum: 1, pageSize: 50 }, IOS)
    expect(wrapper.text()).toContain('按生效时间核对促销')
    expect(wrapper.text()).toContain('09 / 12')
    expect(wrapper.text()).toContain('把周末留给相聚')
    expect(wrapper.text()).toContain('减 ¥ 20.00')
    expect(wrapper.text()).toContain('北岸生活场馆 · 羽毛球场')
    // B4-P13：会员范围按真实「精确等级」语义标注
    expect(wrapper.text()).toContain('银卡会员（精确等级）')
    expect(wrapper.text()).toContain('不叠加优惠券')
  })

  it('uses the server dictionary labels for status and reports the real overview semantics (B4-P14)', async () => {
    const { wrapper } = await mountView()
    expect(wrapper.text()).toContain('进行中')
    await wrapper.find('[data-test="campaign-overview"]').trigger('click')
    const overview = wrapper.find('[data-test="campaign-overview"]').text()
    expect(overview).toContain('进行中活动')
    expect(overview).toContain('进行中定价规则')
    // refundedCoupons 是状态日志条数（历史退款次数），不能写成当前存量
    expect(overview).toContain('历史退款次数')
    expect(overview).toContain('只累加已使用券')
  })

  it('keeps the coupon tab honest about real issuance and cost owner (B4-P5/P6)', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-coupons"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('可被发放与兑换的券模板')
    expect(wrapper.text()).toContain('满 ¥ 50.00 · 减 ¥ 10.00')
    expect(wrapper.text()).toContain('128 / 500')
    expect(wrapper.text()).toContain('成本承担：PLATFORM')
    expect(wrapper.text()).toContain('关联促销：把周末留给相聚')
    expect(promotionApiMock.couponTemplates).toHaveBeenCalledWith({ pageNum: 1, pageSize: 50 }, IOS)
  })

  it('blocks a total quantity below the issued count before calling the service (B4-P6)', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-coupons"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="coupon-edit-0"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="p-total"]').setValue('100')
    await wrapper.find('[data-test="promotion-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(promotionApiMock.updateCouponTemplate).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="promotion-dialog-error"]').text()).toContain('发行总量不能小于已发行数量')
  })

  it('sends venue, space, per-user limit, campaign link and cost owner on coupon save (B4-P4/P5/P12)', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-coupons"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="coupon-edit-0"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="promotion-dialog-submit"]').trigger('click')
    await flushPromises()
    const [id, payload] = promotionApiMock.updateCouponTemplate.mock.calls[0]
    expect(id).toBe(5)
    expect(payload).toMatchObject({
      venueId: 1,
      spaceId: 10,
      perUserLimit: 1,
      campaignId: 1,
      costOwner: 'PLATFORM',
      totalQuantity: 500
    })
    expect(promotionApiMock.updateCouponTemplate).toHaveBeenCalledWith(5, expect.anything(), IOS)
  })

  it('exposes the full price-rule condition set that the reference dialog shows (B4-P3)', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-rules"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('按优先级检查定价条件')
    expect(wrapper.text()).toContain('20')
    expect(wrapper.text()).toContain('周一至周五 13:00–17:00')
    expect(wrapper.text()).toContain('按比例调整 8.5 折')

    await wrapper.find('[data-test="rule-edit-0"]').trigger('click')
    await flushPromises()
    // 场馆/场地/会员范围/星期/每日时刻/调整方式/优先级/说明 全在表单里
    expect(wrapper.find('[data-test="p-venue"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="p-space"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="p-level"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="p-day-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="p-from"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="p-to"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="p-priority"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="p-reason"]').exists()).toBe(true)

    await wrapper.find('[data-test="promotion-dialog-submit"]').trigger('click')
    await flushPromises()
    const [, payload] = promotionApiMock.updatePriceRule.mock.calls[0]
    expect(payload).toMatchObject({
      venueId: 1,
      spaceId: 10,
      memberLevelCode: 'SILVER',
      dayOfWeekMask: ',1,2,3,4,5,',
      startClock: '13:00',
      endClock: '17:00',
      adjustmentType: 'PERCENT',
      priority: 20
    })
  })

  it('rejects a daily clock range that ends before it starts (real server-side rule)', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-rules"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="rule-edit-0"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="p-to"]').setValue('08:00')
    await wrapper.find('[data-test="promotion-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(promotionApiMock.updatePriceRule).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="promotion-dialog-error"]').text()).toContain('每日结束时刻需晚于开始时刻')
  })

  it('offers the redemption tab with a real status filter and pagination (B4-P2)', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-redemptions"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('优惠券状态与核销记录')
    expect(promotionApiMock.couponRedemptions).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 20 }, IOS)
    expect(wrapper.find('[data-test="redemption-table"]').text()).toContain('已使用')
    expect(wrapper.find('[data-test="redemption-table"]').text()).toContain('订单 9018')

    await wrapper.find('[data-test="redemption-status"]').setValue('LOCKED')
    await flushPromises()
    expect(promotionApiMock.couponRedemptions).toHaveBeenLastCalledWith(
      { pageNum: 1, pageSize: 20, status: 'LOCKED' },
      IOS
    )
  })

  it('offers only coupon statuses that have a real writer and explains the omitted ones (B4-P8)', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-redemptions"]').trigger('click')
    await flushPromises()
    const options = wrapper.find('[data-test="redemption-status"]').findAll('option').map((o) => o.text())
    expect(options).toContain('已领取')
    expect(options).toContain('已作废')
    expect(options).toContain('已退回')
    // 有枚举、有字典但没有写入方的两值不进筛选，避免恒空的假空态
    expect(options).not.toContain('已释放')
    expect(options).not.toContain('已过期')
    expect(wrapper.text()).toContain('没有任何写入路径')
    // 预览的「会员编号」列真实 DTO 不提供，不迁入
    expect(wrapper.find('[data-test="redemption-table"]').text()).not.toContain('会员编号')
  })

  it('denies a tab whose leaf permission is missing and does not call its API', async () => {
    authState.permissions = new Set(['promotion:manage'])
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-coupons"]').trigger('click')
    await flushPromises()
    const denied = wrapper.find('[data-test="denied-coupons"]')
    expect(denied.exists()).toBe(true)
    expect(denied.text()).toContain('没有优惠券模板管理权限')
    expect(denied.text()).toContain('coupon:manage')
    expect(promotionApiMock.couponTemplates).not.toHaveBeenCalled()
    expect(promotionApiMock.couponRedemptions).not.toHaveBeenCalled()

    await wrapper.find('[data-test="tab-rules"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="denied-rules"]').exists()).toBe(true)
    expect(promotionApiMock.priceRules).not.toHaveBeenCalled()
  })

  it('reaches the same component through the promotion-pricing alias', async () => {
    const { wrapper } = await mountView('/system-admin/promotion-pricing')
    expect((wrapper.vm as unknown as Vm).view).toBe('campaigns')
    expect(wrapper.find('[data-test="sga-promotion-root"]').exists()).toBe(true)
  })

  it('restores focus to the trigger after the dialog closes', async () => {
    const { wrapper } = await mountView()
    const trigger = wrapper.find('[data-test="campaign-edit-0"]').element as HTMLElement
    await wrapper.find('[data-test="campaign-edit-0"]').trigger('click')
    await flushPromises()
    wrapper.find('dialog').element.dispatchEvent(new Event('close'))
    await flushPromises()
    expect(document.activeElement).toBe(trigger)
  })

  it('hash 深链优先于默认页签，别名与旧入口同规则（F-20）', async () => {
    const cases: Array<[string, string]> = [
      ['/system-admin/promotions#rules', 'rules'],
      ['/system-admin/promotions#coupons', 'coupons'],
      ['/system-admin/promotions#redemptions', 'redemptions'],
      ['/system-admin/promotion-pricing#rules', 'rules'],
      ['/system-admin/promotion-pricing#redemptions', 'redemptions']
    ]
    for (const [path, tab] of cases) {
      resetMocks()
      const { wrapper } = await mountView(path)
      expect((wrapper.vm as unknown as Vm).view, path).toBe(tab)
    }
  })

  it('非法 hash 回落促销活动，券模板深链在别名上只拉自己的端点（F-20）', async () => {
    const invalid = await mountView('/system-admin/promotions#<页签>')
    expect((invalid.wrapper.vm as unknown as Vm).view).toBe('campaigns')
    invalid.wrapper.unmount()

    const viaAlias = await mountView('/system-admin/promotion-pricing#coupons')
    expect((viaAlias.wrapper.vm as unknown as Vm).view).toBe('coupons')
    expect(promotionApiMock.couponTemplates).toHaveBeenCalled()
    // 券模板面会为「关联促销」下拉读取活动清单（既有合同 B4-P12）；不相关的定价端点不得被请求
    expect(promotionApiMock.priceRules).not.toHaveBeenCalled()
  })

  it('无权页签的深链停在原页签并给出无权态，不请求该领域端点（F-20）', async () => {
    authState.permissions = new Set(['promotion:manage'])
    const { wrapper } = await mountView('/system-admin/promotions#rules')
    expect((wrapper.vm as unknown as Vm).view).toBe('rules')
    expect(wrapper.find('[data-test="denied-rules"]').exists()).toBe(true)
    expect(promotionApiMock.priceRules).not.toHaveBeenCalled()
  })

  it('活动优先级可读回、可编辑并随保存回传（F-21）', async () => {
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="campaign-priority"]').text()).toBe('30')

    await wrapper.find('[data-test="campaign-edit-0"]').trigger('click')
    await flushPromises()
    const priority = wrapper.find('[data-test="p-campaign-priority"]')
    expect(priority.exists()).toBe(true)
    expect((priority.element as HTMLInputElement).value).toBe('30')

    await priority.setValue('45')
    await wrapper.find('[data-test="promotion-dialog-submit"]').trigger('click')
    await flushPromises()
    await flushPromises()
    const [, payload] = promotionApiMock.updateCampaign.mock.calls[0]
    expect(payload).toMatchObject({ priority: 45, discountType: 'CASH', discountValue: 20 })
  })
})
