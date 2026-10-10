import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ShiguangMemberHomeView from './ShiguangMemberHomeView.vue'

const memberApiMock = vi.hoisted(() => ({ me: vi.fn() }))
const memberRewardApiMock = vi.hoisted(() => ({ catalog: vi.fn(), preview: vi.fn(), redeem: vi.fn(), history: vi.fn() }))
const couponApiMock = vi.hoisted(() => ({ my: vi.fn(), claimable: vi.fn(), claim: vi.fn() }))
const storedValueApiMock = vi.hoisted(() => ({
  self: vi.fn(), bonusBatches: vi.fn(), recharges: vi.fn(), ledger: vi.fn(), myRecharge: vi.fn()
}))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, string>, params: {}, fullPath: '/me/membership' }))
const menuPermissions = vi.hoisted(() => ({ value: new Set<string>(['member:me', 'member-reward:me', 'coupon:me', 'stored-value:me']) }))

vi.mock('@/api/member', () => ({ memberApi: memberApiMock, memberRewardApi: memberRewardApiMock, adminMemberApi: {}, adminMemberRewardApi: {} }))
vi.mock('@/api/promotion', () => ({ couponApi: couponApiMock, promotionAdminApi: {} }))
vi.mock('@/api/storedvalue', () => ({ storedValueApi: storedValueApiMock, storedValueAdminApi: {} }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock }))
vi.mock('@/stores/menu', () => ({ useMenuStore: () => ({ hasPermission: (key: string) => menuPermissions.value.has(key) }) }))
vi.mock('element-plus', () => ({ ElMessage: Object.assign(vi.fn(), { success: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn() }) }))
vi.mock('@/api/http', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() } }))

const stubs = {
  'el-icon': { template: '<i />' },
  'router-link': { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>' },
  ShiguangArt: { props: ['scene'], template: '<span class="art" />' }
}

const accountFixture = (overrides: Record<string, unknown> = {}) => ({
  userId: 7,
  username: 'linjian',
  nickname: '林间',
  memberAccountId: 9621,
  levelCode: 'SILVER',
  levelName: '银卡会员',
  growthValue: 1280,
  pointsBalance: 860,
  totalConsumptionAmount: 640,
  totalReservations: 12,
  totalMemberDiscountAmount: 64,
  discountRate: 0.95,
  benefitText: '预约享 9.5 折',
  nextLevel: { id: 3, levelCode: 'GOLD', levelName: '金卡会员', growthThreshold: 2000, discountRate: 0.9, status: 'ACTIVE' },
  nextLevelGrowthGap: 720,
  ...overrides
})

const catalogFixture = [
  {
    itemId: 1, itemCode: 'MOVE15', itemName: '周末运动抵扣券', descriptionText: '留一场球给周末。',
    status: 'ACTIVE', versionId: 31, versionNo: 3, pointsCost: 200, minimumLevelCode: 'BASIC',
    minimumLevelName: '普通会员', minimumGrowthThreshold: 0, couponTemplateId: 51, validityDays: 30,
    availablePoints: 860, pendingOffsetPoints: 0, eligible: true
  },
  {
    itemId: 2, itemCode: 'QUIET9', itemName: '给自己一个安静下午', descriptionText: '读到喜欢的地方。',
    status: 'ACTIVE', versionId: 22, versionNo: 2, pointsCost: 300, minimumLevelCode: 'SILVER',
    minimumLevelName: '银卡会员', minimumGrowthThreshold: 1000, couponTemplateId: 52, validityDays: 30,
    availablePoints: 860, pendingOffsetPoints: 0, eligible: false, unavailableReason: '可用积分不足'
  }
]

const recordFixture = (overrides: Record<string, unknown> = {}) => ({
  id: 91,
  redemptionNo: 'RD-20260910-1',
  itemCode: 'MOVE15',
  itemName: '周末运动抵扣券',
  versionNo: 3,
  pointsCost: 200,
  status: 'SUCCEEDED',
  userCouponId: 501,
  createTime: '2026-09-10T09:00:00',
  ...overrides
})

/** 临期判定读真实时钟（7 天内到期），有效期必须相对当天推算；写死日期会让该断言随日期越过而假性失败。 */
const inDays = (days: number): string => {
  const date = new Date(Date.now() + days * 86400000)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T12:00:00`
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
  validEndTime: inDays(3),
  ...overrides
})

const storedSelfFixture = (overrides: Record<string, unknown> = {}) => ({
  product: { id: 3, productCode: 'DEV-STORED', productName: '开发环境储值', environment: 'DEV', minimumRecharge: 50, maximumRecharge: 500, bonusRatePercent: 10, bonusValidDays: 30 },
  products: [],
  account: {
    accountId: 41, userId: 7, productId: 3, status: 'NORMAL', currency: 'CNY',
    principalAvailable: 280, principalFrozen: 20, bonusAvailable: 20, bonusFrozen: 20,
    availableTotal: 300, totalBalance: 340, version: 5
  },
  identityVerified: true,
  eligibilityStatus: 'READY',
  eligibilityMessage: 'DEV/SANDBOX 储值可用',
  ...overrides
})

const pageOf = (records: unknown[], total = records.length) => ({ records, total, pageNum: 1, pageSize: 100 })

let testPinia: Pinia

const mountView = () => mount(ShiguangMemberHomeView, { global: { plugins: [testPinia], stubs } })

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) { this.open = true })
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) { this.open = false })
})

beforeEach(() => {
  vi.clearAllMocks()
  testPinia = createPinia()
  setActivePinia(testPinia)
  menuPermissions.value = new Set(['member:me', 'member-reward:me', 'coupon:me', 'stored-value:me'])
  routeMock.query = {}
  memberApiMock.me.mockResolvedValue(accountFixture())
  memberRewardApiMock.catalog.mockResolvedValue(catalogFixture.map((row) => ({ ...row })))
  memberRewardApiMock.history.mockResolvedValue(pageOf([recordFixture()], 1))
  couponApiMock.my.mockResolvedValue(pageOf([couponFixture()]))
  storedValueApiMock.self.mockResolvedValue(storedSelfFixture())
})

describe('ShiguangMemberHomeView 身份与口袋条', () => {
  it('渲染会员凭证、分账口袋与五页内部导航（真实字段）', async () => {
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('林间')
    expect(text).toContain('银卡会员')
    expect(text).toContain('NO. 009621')
    expect(text).toContain('已享会员优惠')
    expect(text).toContain('¥64.00')
    expect(text).toContain('再积累')
    expect(text).toContain('720')
    expect(text).toContain('金卡会员')
    expect(text).toContain('860')
    expect(text).toContain('可用优惠券')
    expect(text).toContain('1 张即将到期'.replace('1', '1'))
    expect(text).toContain('¥300.00')
    expect(text).toContain('会员概览')
    expect(text).toContain('积分权益')
    expect(text).toContain('优惠券夹')
    expect(text).toContain('储值账户')
    expect(text).toContain('兑换记录')
  })

  it('负积分账户：可用积分置零、待抵扣如实表达（不混成长值）', async () => {
    memberApiMock.me.mockResolvedValue(accountFixture({ pointsBalance: -80 }))
    memberRewardApiMock.catalog.mockResolvedValue(
      catalogFixture.map((row) => ({ ...row, availablePoints: 0, pendingOffsetPoints: 80 }))
    )
    routeMock.query = { tab: 'rewards' }

    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('待抵扣 80 分 · 后续积分先抵扣')
    expect(text).toContain('待抵扣 80 积分')
    expect(text).toContain('成长值不因此变为负数')
  })

  it('券夹局部失败：概览券区错误重试、身份/权益/记录保留（G6）', async () => {
    couponApiMock.my.mockRejectedValue(new Error('network down'))

    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('券夹暂时没读到')
    expect(text).toContain('不将错误显示为 0 张')
    expect(text).toContain('暂未读到券夹，金额与张数不置零')
    expect(text).toContain('林间')
    expect(text).toContain('周末运动抵扣券')
    expect(wrapper.find('[data-test="overview-coupons-error"]').exists()).toBe(true)
  })

  it('会员域整页失败给重试、403 给权限态', async () => {
    memberApiMock.me.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="member-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('你的等级、积分和余额没有变成零')

    memberApiMock.me.mockResolvedValue(accountFixture())
    await wrapper.find('[data-test="member-error"] button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('林间')

    memberApiMock.me.mockRejectedValue({ response: { status: 403 } })
    const denied = mountView()
    await flushPromises()
    expect(denied.find('[data-test="member-denied"]').exists()).toBe(true)
    expect(denied.text()).toContain('当前账号没有会员访问权限')
  })

  it('无 stored-value:me 权限时储值口袋与页签不渲染', async () => {
    menuPermissions.value = new Set(['member:me', 'member-reward:me', 'coupon:me'])

    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toContain('储值账户')
    expect(text).toContain('可用优惠券')
    expect(storedValueApiMock.self).not.toHaveBeenCalled()
  })
})

describe('ShiguangMemberHomeView 积分权益与兑换', () => {
  it('权益页筛选当前可兑换，不可兑换行展示服务端原因', async () => {
    routeMock.query = { tab: 'rewards' }

    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('可用积分不足')

    const buttons = wrapper.findAll('.filter-options button')
    await buttons[1].trigger('click')
    expect(wrapper.text()).toContain('1 项权益')
    expect(wrapper.findAll('.catalog-row')).toHaveLength(1)
  })

  it('兑换流程：弹层重取 preview 绑定版本、同键防双重点击、成功入券夹', async () => {
    routeMock.query = { tab: 'rewards' }
    memberRewardApiMock.preview.mockResolvedValue({ ...catalogFixture[0] })
    memberRewardApiMock.redeem.mockResolvedValue(recordFixture({ id: 92, status: 'SUCCEEDED' }))

    const wrapper = mountView()
    await flushPromises()

    const redeemButtons = wrapper.findAll('.catalog-cta .button')
    await redeemButtons[0].trigger('click')
    await flushPromises()

    expect(memberRewardApiMock.preview).toHaveBeenCalledWith(1, expect.anything())
    const confirm = wrapper.find('[data-test="confirm-redeem"]')
    expect(confirm.attributes('disabled')).toBeUndefined()

    await confirm.trigger('click')
    await confirm.trigger('click')
    await flushPromises()

    expect(memberRewardApiMock.redeem).toHaveBeenCalledTimes(1)
    const [itemId, payload] = memberRewardApiMock.redeem.mock.calls[0]
    expect(itemId).toBe(1)
    expect(payload.catalogVersionId).toBe(31)
    expect(payload.idempotencyKey).toMatch(/^member-reward-redeem-/)
    expect(wrapper.text()).toContain('兑换完成')
    expect(wrapper.text()).toContain('优惠券已放入券夹')
  })
})

describe('ShiguangMemberHomeView 兑换记录', () => {
  it('记录页服务端 status 过滤（处理中传 PENDING）', async () => {
    routeMock.query = { tab: 'records' }
    memberRewardApiMock.history.mockResolvedValue(pageOf([recordFixture({ id: 93, status: 'PENDING' })], 1))

    const wrapper = mountView()
    await flushPromises()

    const buttons = wrapper.findAll('.filter-options button')
    await buttons[2].trigger('click')
    await flushPromises()

    expect(memberRewardApiMock.history).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'PENDING', pageNum: 1 }),
      expect.anything()
    )
    expect(wrapper.text()).toContain('待核对')
    expect(wrapper.text()).toContain('处理中')
  })

  it('PENDING 记录弹层提供核对原请求且不写成成功', async () => {
    routeMock.query = { tab: 'records' }
    const pending = recordFixture({ id: 94, status: 'PENDING' })
    memberRewardApiMock.history.mockResolvedValue(pageOf([pending], 1))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.record-row .text-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('结果待确认')
    expect(wrapper.text()).toContain('先核对，不重复兑换')
    expect(wrapper.text()).not.toContain('兑换完成')

    // 页面同时存在多个 member-dialog 壳；须命中「结果待确认」这条记录弹层，而非关闭弹层残留的动作区
    const recordDialog = wrapper
      .findAll('[data-test="member-dialog"]')
      .find((dialog) => dialog.text().includes('先核对，不重复兑换'))
    expect(recordDialog).toBeDefined()
    await recordDialog!.find('.button.primary').trigger('click')
    await flushPromises()
    expect(memberRewardApiMock.history).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'PENDING' }),
      expect.anything()
    )
  })
})
