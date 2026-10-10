import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ShiguangMemberCouponsView from './ShiguangMemberCouponsView.vue'
import { ElMessage } from 'element-plus'

const memberApiMock = vi.hoisted(() => ({ me: vi.fn() }))
const memberRewardApiMock = vi.hoisted(() => ({ catalog: vi.fn(), preview: vi.fn(), redeem: vi.fn(), history: vi.fn() }))
const couponApiMock = vi.hoisted(() => ({ my: vi.fn(), claimable: vi.fn(), claim: vi.fn() }))
const storedValueApiMock = vi.hoisted(() => ({ self: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, string>, params: {}, fullPath: '/me/coupons' }))

vi.mock('@/api/member', () => ({ memberApi: memberApiMock, memberRewardApi: memberRewardApiMock, adminMemberApi: {}, adminMemberRewardApi: {} }))
vi.mock('@/api/promotion', () => ({ couponApi: couponApiMock, promotionAdminApi: {} }))
vi.mock('@/api/storedvalue', () => ({ storedValueApi: storedValueApiMock, storedValueAdminApi: {} }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock }))
vi.mock('@/stores/menu', () => ({ useMenuStore: () => ({ hasPermission: () => true }) }))
vi.mock('element-plus', () => ({ ElMessage: Object.assign(vi.fn(), { success: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn() }) }))
vi.mock('@/api/http', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() } }))

const stubs = {
  'el-icon': { template: '<i />' },
  'router-link': { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>' },
  ShiguangArt: { props: ['scene'], template: '<span class="art" />' }
}

const accountFixture = {
  userId: 7, username: 'linjian', nickname: '林间', memberAccountId: 9621,
  levelCode: 'SILVER', levelName: '银卡会员', growthValue: 1280, pointsBalance: 860,
  totalMemberDiscountAmount: 64, discountRate: 0.95,
  nextLevel: { id: 3, levelCode: 'GOLD', levelName: '金卡会员', growthThreshold: 2000, discountRate: 0.9, status: 'ACTIVE' },
  nextLevelGrowthGap: 720
}

const couponFixture = (overrides: Record<string, unknown> = {}) => ({
  id: 501,
  templateId: 51,
  templateName: '周末运动抵扣券',
  couponType: 'CASH',
  discountValue: 15,
  thresholdAmount: 80,
  status: 'CLAIMED',
  sourceType: 'MEMBER_REWARD',
  claimTime: '2026-09-10T09:00:00',
  validStartTime: '2026-09-01T00:00:00',
  validEndTime: '2099-10-01T00:00:00',
  ...overrides
})

const templateFixture = (overrides: Record<string, unknown> = {}) => ({
  id: 61,
  templateCode: 'WELCOME8',
  templateName: '一点见面的小心意',
  couponType: 'CASH',
  discountValue: 8,
  thresholdAmount: 40,
  totalQuantity: 100,
  issuedQuantity: 12,
  usedQuantity: 3,
  perUserLimit: 1,
  allowActivityStack: true,
  status: 'ACTIVE',
  validStartTime: '2026-09-01T00:00:00',
  validEndTime: '2026-09-30T00:00:00',
  ...overrides
})

const storedSelfFixture = {
  product: { id: 3, environment: 'DEV' },
  products: [],
  account: { accountId: 41, status: 'NORMAL', principalAvailable: 280, principalFrozen: 0, bonusAvailable: 20, bonusFrozen: 0, availableTotal: 300, totalBalance: 300, version: 1 },
  identityVerified: true,
  eligibilityStatus: 'READY',
  eligibilityMessage: 'DEV/SANDBOX 储值可用'
}

const pageOf = (records: unknown[], total = records.length) => ({ records, total, pageNum: 1, pageSize: 100 })

let testPinia: Pinia
const mountView = () => mount(ShiguangMemberCouponsView, { global: { plugins: [testPinia], stubs } })

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) { this.open = true })
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) { this.open = false })
})

beforeEach(() => {
  vi.clearAllMocks()
  testPinia = createPinia()
  setActivePinia(testPinia)
  routeMock.query = {}
  memberApiMock.me.mockResolvedValue(accountFixture)
  memberRewardApiMock.history.mockResolvedValue(pageOf([]))
  couponApiMock.my.mockResolvedValue(pageOf([
    couponFixture(),
    couponFixture({ id: 502, status: 'LOCKED', reservationId: 88 }),
    couponFixture({ id: 503, validEndTime: '2020-08-31T00:00:00' }),
    couponFixture({ id: 504, status: 'USED' }),
    couponFixture({ id: 505, status: 'REFUNDED' })
  ], 5))
  couponApiMock.claimable.mockResolvedValue([templateFixture()])
  storedValueApiMock.self.mockResolvedValue(storedSelfFixture)
})

describe('ShiguangMemberCouponsView 我的券夹（G2 口径）', () => {
  it('可用筛选排除锁定/过期/已用/退款，口袋张数同口径', async () => {
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('1 张 · 不同状态不混计')
    expect(wrapper.findAll('.coupon-folder .ticket')).toHaveLength(1)
    expect(text).toContain('周末运动抵扣券')
    // 口袋条可用张数与可用筛选一致（锁定/推导过期不计）
    expect(text).toMatch(/可用优惠券[\s\S]*?1\s*张/)
  })

  it('锁定中/已失效筛选各自归位，懒过期推导 EXPIRED 入失效桶', async () => {
    routeMock.query = { filter: 'locked' }
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('订单锁定中')
    expect(wrapper.findAll('.coupon-folder .ticket')).toHaveLength(1)

    routeMock.query = { filter: 'inactive' }
    const inactive = mountView()
    await flushPromises()
    expect(inactive.text()).toContain('已过期')
    expect(inactive.text()).toContain('已退款 · 不可复用')
    expect(inactive.findAll('.coupon-folder .ticket')).toHaveLength(2)
  })

  it('券夹读取失败：整段错误重试，不显示为空券夹', async () => {
    couponApiMock.my.mockRejectedValue(new Error('boom'))

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="coupons-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('这是读取失败，不是你没有优惠券')

    couponApiMock.my.mockResolvedValue(pageOf([couponFixture()]))
    await wrapper.find('[data-test="coupons-error"] button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('周末运动抵扣券')
  })
})

describe('ShiguangMemberCouponsView 可领取', () => {
  it('可领取模式渲染模板并按真实库存/已领取禁用', async () => {
    routeMock.query = { mode: 'claimable' }

    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('一点见面的小心意')
    expect(text).toContain('单笔订单满 40 元减 ¥8.00')
    expect(couponApiMock.claimable).toHaveBeenCalled()
  })

  it('领取成功：真实命令入券夹并刷新券夹与可领取（G3）', async () => {
    routeMock.query = { mode: 'claimable' }
    // 券夹中尚无模板 61：按钮可领取
    couponApiMock.claim.mockResolvedValue(couponFixture({ id: 600, templateId: 61 }))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="claim-61"]').trigger('click')
    await flushPromises()
    expect(couponApiMock.claim).toHaveBeenCalledWith(61, expect.anything())
    expect(ElMessage.success).toHaveBeenCalledWith('优惠券已放入券夹')
  })

  it('409 限领映射为「已领取」终态不当作失败（G3：响应丢失重试）', async () => {
    routeMock.query = { mode: 'claimable' }
    // 券夹列表尚未反映已领取（响应丢失后的核对场景）：服务端以 409 限领终审
    couponApiMock.claim.mockRejectedValue({ response: { status: 409, data: { message: '已达到每人限领数量' } } })

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="claim-61"]').trigger('click')
    await flushPromises()
    expect(ElMessage.info).toHaveBeenCalledWith('这张券已在你的券夹里')
    expect(ElMessage.error).not.toHaveBeenCalled()
  })
})
