import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ShiguangMemberStoredView from './ShiguangMemberStoredView.vue'

const memberApiMock = vi.hoisted(() => ({ me: vi.fn() }))
const memberRewardApiMock = vi.hoisted(() => ({ catalog: vi.fn(), preview: vi.fn(), redeem: vi.fn(), history: vi.fn() }))
const couponApiMock = vi.hoisted(() => ({ my: vi.fn() }))
const storedValueApiMock = vi.hoisted(() => ({
  self: vi.fn(), bonusBatches: vi.fn(), recharges: vi.fn(), ledger: vi.fn(),
  myRecharge: vi.fn(), createRecharge: vi.fn(), confirmDevCallback: vi.fn()
}))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, string>, params: {}, fullPath: '/me/stored-value' }))

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

const productFixture = {
  id: 3, productCode: 'DEV-STORED', productName: '开发环境储值', environment: 'DEV',
  minimumRecharge: 50, maximumRecharge: 500, bonusRatePercent: 10, bonusValidDays: 30
}

const accountRow = (overrides: Record<string, unknown> = {}) => ({
  accountId: 41, userId: 7, productId: 3, status: 'NORMAL', currency: 'CNY',
  principalAvailable: 280, principalFrozen: 20, bonusAvailable: 20, bonusFrozen: 20,
  availableTotal: 300, totalBalance: 340, version: 5,
  ...overrides
})

const selfFixture = (overrides: Record<string, unknown> = {}) => ({
  product: productFixture,
  products: [productFixture],
  account: accountRow(),
  identityVerified: true,
  eligibilityStatus: 'READY',
  eligibilityMessage: 'DEV/SANDBOX 储值可用',
  ...overrides
})

const ledgerFixture = {
  records: [
    { transactionId: 71, transactionType: 'ORDER_PAYMENT', sourceType: 'ORDER', sourceId: '88', bucket: 'PRINCIPAL_AVAILABLE', direction: 'DEBIT', amount: 40, currency: 'CNY', reason: '预约订单支付', occurredAt: '2026-09-08T15:26:00' },
    { transactionId: 70, transactionType: 'RECHARGE_POSTING', sourceType: 'RECHARGE', sourceId: 'RC-1', bucket: 'BONUS_AVAILABLE', direction: 'CREDIT', amount: 20, currency: 'CNY', reason: '充值赠送入账', occurredAt: '2026-09-01T18:36:00' }
  ],
  total: 2, pageNum: 1, pageSize: 20
}

const batchesFixture = [
  { id: 81, rechargeId: 61, originalAmount: 20, availableAmount: 20, frozenAmount: 0, issuedAt: '2026-09-01T18:36:00', expiresAt: '2026-10-01T00:00:00', status: 'AVAILABLE' }
]

const rechargeFixture = (overrides: Record<string, unknown> = {}) => ({
  id: 61,
  rechargeNo: 'RC-20260901-1',
  productId: 3,
  status: 'WAITING_PROVIDER',
  principalAmount: 200,
  expectedBonusAmount: 20,
  actualBonusAmount: 0,
  currency: 'CNY',
  providerKey: 'stored-value.dev',
  providerEnv: 'DEV',
  providerName: '开发环境',
  createTime: '2026-09-01T18:36:00',
  updateTime: '2026-09-01T18:36:00',
  ...overrides
})

const pageOf = (records: unknown[], total = records.length) => ({ records, total, pageNum: 1, pageSize: 100 })

let testPinia: Pinia
const mountView = () => mount(ShiguangMemberStoredView, { global: { plugins: [testPinia], stubs } })

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
  couponApiMock.my.mockResolvedValue(pageOf([]))
  storedValueApiMock.self.mockResolvedValue(selfFixture())
  storedValueApiMock.ledger.mockResolvedValue(ledgerFixture)
  storedValueApiMock.bonusBatches.mockResolvedValue(batchesFixture)
  storedValueApiMock.recharges.mockResolvedValue(pageOf([rechargeFixture()]))
})

describe('ShiguangMemberStoredView 分账与页签', () => {
  it('本金/赠送/冻结分桶展示，合计不含冻结', async () => {
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('¥300.00')
    expect(text).toContain('不含冻结 ¥40.00')
    expect(text).toContain('本金可用')
    expect(text).toContain('赠送可用')
    expect(text).toContain('冻结金额')
    expect(text).toContain('DEV 环境储值')
  })

  it('三页签深链切换：流水/批次/充值记录各自真实读取', async () => {
    routeMock.query = { tab: 'bonus' }
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('充值赠送批次')
    expect(wrapper.text()).toContain('¥20.00')
    expect(wrapper.text()).toContain('2026.10.01 到期')

    routeMock.query = { tab: 'recharges' }
    const recharges = mountView()
    await flushPromises()
    expect(recharges.text()).toContain('开发环境充值 · 等待核对')
    expect(recharges.text()).toContain('等待渠道确认')
    expect(recharges.text()).toContain('创建充值不等于已经到账')

    routeMock.query = {}
    const ledger = mountView()
    await flushPromises()
    expect(ledger.text()).toContain('预约订单支付')
    expect(ledger.text()).toContain('−¥40.00')
    expect(ledger.text()).toContain('+¥20.00')
  })

  it('未开通：不显示虚假 ¥0.00、不给充值按钮', async () => {
    storedValueApiMock.self.mockResolvedValue(selfFixture({
      account: null,
      eligibilityStatus: 'ACCOUNT_REQUIRED',
      eligibilityMessage: '储值资格已满足，首次充值时会开通账户'
    }))

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="stored-unavailable"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('储值服务暂未开通')
    expect(wrapper.text()).toContain('首次充值时会开通账户')
    expect(wrapper.text()).not.toContain('¥0.00')
    expect(wrapper.find('[data-test="open-recharge"]').exists()).toBe(false)
  })

  it('冻结账户：余额照显、充值禁用并如实说明', async () => {
    storedValueApiMock.self.mockResolvedValue(selfFixture({ account: accountRow({ status: 'FROZEN' }) }))

    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('账面可用余额 · 当前不可支付')
    expect(text).toContain('账户已冻结，充值与支付暂停')
    const button = wrapper.find('[data-test="open-recharge"]')
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('储值域读取失败：独立错误重试，不显示为零', async () => {
    storedValueApiMock.self.mockRejectedValue(new Error('boom'))

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="stored-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('这是读取失败，不是你的余额变成零')
  })
})

describe('ShiguangMemberStoredView DEV 充值', () => {
  it('创建充值：金额按产品快照、clientRequestId 同键防双重点击', async () => {
    storedValueApiMock.createRecharge.mockResolvedValue(rechargeFixture({ rechargeNo: 'RC-NEW-1' }))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="open-recharge"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('开发环境储值')
    expect(wrapper.text()).toContain('预计赠送')
    expect(wrapper.text()).toContain('10%')

    const createButton = wrapper.find('[data-test="create-recharge"]')
    await createButton.trigger('click')
    await createButton.trigger('click')
    await flushPromises()

    expect(storedValueApiMock.createRecharge).toHaveBeenCalledTimes(1)
    const payload = storedValueApiMock.createRecharge.mock.calls[0][0]
    expect(payload.productId).toBe(3)
    expect(payload.principalAmount).toBe(100)
    expect(payload.clientRequestId).toMatch(/^stored-value-recharge-/)

    expect(wrapper.text()).toContain('创建充值 ≠ 到账')
    expect(wrapper.text()).toContain('等待渠道确认')
  })

  it('未知结果：查询原记录核对，DEV 环境可模拟渠道结果', async () => {
    storedValueApiMock.createRecharge.mockResolvedValue(rechargeFixture({ rechargeNo: 'RC-NEW-2' }))
    storedValueApiMock.myRecharge.mockResolvedValue(rechargeFixture({ rechargeNo: 'RC-NEW-2' }))
    storedValueApiMock.confirmDevCallback.mockResolvedValue(rechargeFixture({ rechargeNo: 'RC-NEW-2', status: 'SUCCEEDED', actualBonusAmount: 20 }))

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="open-recharge"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="create-recharge"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-test="check-recharge"]').trigger('click')
    await flushPromises()
    expect(storedValueApiMock.myRecharge).toHaveBeenCalledWith('RC-NEW-2', expect.anything())

    await wrapper.find('[data-test="dev-callback-success"]').trigger('click')
    await flushPromises()
    expect(storedValueApiMock.confirmDevCallback).toHaveBeenCalledWith('RC-NEW-2', 'SUCCESS', expect.anything())
    expect(wrapper.text()).toContain('本金已入账')
  })
})
