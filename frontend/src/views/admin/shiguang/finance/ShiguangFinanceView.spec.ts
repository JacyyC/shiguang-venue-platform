import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const paymentApiMock = vi.hoisted(() => ({
  providers: vi.fn(),
  overview: vi.fn(),
  attempts: vi.fn(),
  callbacks: vi.fn(),
  runReconciliation: vi.fn(),
  reconciliationItems: vi.fn(),
  adjustReconciliation: vi.fn()
}))
const authState = vi.hoisted(() => ({
  permissions: new Set(['payment:admin', 'finance:reconcile', 'stored-value:finance:view'])
}))

vi.mock('@/api/payment', () => ({ paymentAdminApi: paymentApiMock }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: null,
    hasPermission: (permission: string) => authState.permissions.has(permission)
  })
}))
// 场馆名称读取走公开接口；单测内固定返回，避免真实网络
vi.mock('@/utils/entityNames', () => ({ fetchVenueName: vi.fn(async (venueId: number) => `北岸生活场馆 ${venueId}`) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

import ShiguangFinanceView from './ShiguangFinanceView.vue'

const page = <T,>(records: T[], total = records.length) => ({ records, total, pageNum: 1, pageSize: 10 })

const providerRow = {
  providerKey: 'payment.dev',
  providerEnv: 'DEV',
  providerName: 'DEV_PAYMENT',
  displayName: 'DEV PaymentProvider',
  configVersion: 'DEV_PAYMENT_V1',
  enabled: true,
  defaultProvider: true
}

const attemptRow = {
  id: 3,
  paymentNo: 'PAY-20260912-073',
  orderId: 73,
  orderNo: 'O-0912-073',
  userId: 8,
  venueId: 21,
  reservationId: 5073,
  providerEnv: 'DEV',
  providerName: 'DEV_PAYMENT',
  channel: 'DEV_PAYMENT',
  status: 'SUCCEEDED',
  payableAmount: 80,
  paidAmount: 80,
  feeAmount: 0,
  netAmount: 80,
  currency: 'CNY',
  externalRef: 'EXT-073',
  configVersion: 'DEV_PAYMENT_V1',
  createTime: '2026-09-12T09:42:00'
}

const callbackRow = {
  id: 6,
  channel: 'DEV_PAYMENT',
  orderNo: 'O-0912-073',
  transactionId: 'TXN-6',
  status: 'SUCCESS',
  message: '验签通过，支付成功',
  sourceType: 'DEV_SIMULATION',
  providerEnv: 'DEV',
  providerName: 'DEV_PAYMENT',
  externalRef: 'EXT-073',
  failureCode: null,
  configVersion: 'DEV_PAYMENT_V1',
  durationMs: 18,
  createTime: '2026-09-12T09:43:00'
}

const differenceRow = {
  id: 9,
  reconcileNo: 'REC-20260912-PAYMENT-EXT-073',
  reconcileDate: '2026-09-12',
  businessType: 'PAYMENT',
  providerEnv: 'DEV',
  providerName: 'DEV_PAYMENT',
  externalRef: 'EXT-073',
  systemAmount: 80,
  providerAmount: 80,
  feeAmount: 0,
  netAmount: 80,
  differenceAmount: 0,
  status: 'MATCHED',
  createTime: '2026-09-12T10:00:00'
}

let router: Router

const mountView = () => mount(ShiguangFinanceView, { global: { plugins: [router] } })

beforeEach(async () => {
  vi.clearAllMocks()
  // happy-dom/jsdom 未实现原生 <dialog>.showModal/close：补齐打开态，使弹层内容可断言
  const dialogProto = HTMLDialogElement.prototype as unknown as Record<string, unknown>
  if (typeof dialogProto.showModal !== 'function') {
    dialogProto.showModal = function mockShowModal(this: HTMLDialogElement) { this.setAttribute('open', '') }
  }
  if (typeof dialogProto.close !== 'function') {
    dialogProto.close = function mockClose(this: HTMLDialogElement) { this.removeAttribute('open') }
  }
  authState.permissions = new Set(['payment:admin', 'finance:reconcile', 'stored-value:finance:view'])
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/system-admin', name: 'SystemAdminDashboard', component: { template: '<div />' } },
      { path: '/system-admin/payments', name: 'SystemPayments', component: { template: '<div />' } },
      { path: '/system-admin/reconciliation', name: 'SystemReconciliation', component: { template: '<div />' } },
      { path: '/venue-admin/reservations', name: 'VenueAdminReservations', component: { template: '<div />' } }
    ]
  })
  router.push({ name: 'SystemPayments' })
  await router.isReady()

  paymentApiMock.providers.mockResolvedValue([providerRow])
  paymentApiMock.attempts.mockResolvedValue(page([attemptRow]))
  paymentApiMock.callbacks.mockResolvedValue(page([callbackRow]))
  paymentApiMock.overview.mockResolvedValue({
    successAmount: 80,
    feeAmount: 0,
    netAmount: 80,
    successCount: 1,
    failedCount: 0,
    canceledCount: 0,
    timeoutCount: 0,
    failureReasons: [],
    dataGaps: []
  })
  paymentApiMock.reconciliationItems.mockResolvedValue(page([differenceRow]))
  paymentApiMock.runReconciliation.mockResolvedValue({
    reconcileDate: '2026-09-12',
    providerEnv: 'DEV',
    providerName: 'DEV_PAYMENT',
    totalCount: 1,
    matchedCount: 1,
    differenceCount: 0,
    missingProviderCount: 0,
    missingSystemCount: 0
  })
})

describe('支付证据面（B5-H4 一比一重建）', () => {
  it('按渠道加载支付尝试并渲染单笔三段证据', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(paymentApiMock.attempts).toHaveBeenCalledWith(expect.objectContaining({
      providerEnv: 'DEV',
      providerName: 'DEV_PAYMENT'
    }))
    const text = wrapper.text()
    expect(text).toContain('支付与储值财务')
    expect(text).toContain('O-0912-073')
    expect(text).toContain('01　支付尝试')
    expect(text).toContain('02　回调证据')
    expect(text).toContain('03　成功入账与对账')
    expect(text).toContain('支付已确认成功，成功记录可进入对账。')
    expect(text).toContain('支付 ¥ 80.00　/　费用 ¥ 0.00　/　净额 ¥ 80.00')
    expect(wrapper.find('[data-test="fin-check-difference"]').exists()).toBe(true)
  })

  it('venueId 不随支付查询下发（B5-H2 回归）', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(JSON.stringify(paymentApiMock.attempts.mock.lastCall?.[0])).not.toContain('venueId')
    expect(JSON.stringify(paymentApiMock.callbacks.mock.lastCall?.[0])).not.toContain('venueId')
  })

  it('真实概览聚合保留为 IMPL_ONLY 且不删除既有能力（B5-H4）', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(paymentApiMock.overview).toHaveBeenCalledWith(expect.objectContaining({
      providerEnv: 'DEV',
      providerName: 'DEV_PAYMENT'
    }))
    expect(wrapper.find('[data-test="fin-overview"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('本期范围概览')
  })

  it('最小证据弹层仅展示摘要字段且声明不展示原始载荷', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="fin-open-callback"]').trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('回调最小证据')
    expect(text).toContain('TXN-6')
    expect(text).toContain('仅保留事件摘要与处理结果，不展示原始第三方载荷。')
    expect(wrapper.find('[data-test="fin-open-callback"]').attributes('type')).toBe('button')
  })

  it('失败尝试不能进入对账跳转且不声称扣款', async () => {
    paymentApiMock.attempts.mockResolvedValue(page([{ ...attemptRow, status: 'FAILED', failureCode: 'PROVIDER_TIMEOUT', paidAmount: 0 }]))
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('尝试失败；不能仅凭超时认定资金已扣除。')
    expect(text).toContain('PROVIDER_TIMEOUT')
    expect(text).toContain('尚无对应的成功入账事实。回到支付尝试核对后再决定后续处理。')
    expect(wrapper.find('[data-test="fin-check-difference"]').exists()).toBe(false)
  })
})

describe('对账差异面（B5-H6/H7 一比一重建）', () => {
  it('运行对账后提示完成且不自动平账', async () => {
    await router.push({ name: 'SystemReconciliation' })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('把差异留在事实旁')
    await wrapper.find('[data-test="fin-run-reconcile"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('运行只生成账实核对结果，不自动平账。')
    await wrapper.find('[data-test="fin-run-submit"]').trigger('submit')
    await flushPromises()

    expect(paymentApiMock.runReconciliation).toHaveBeenCalledWith(expect.objectContaining({
      reconcileDate: expect.any(String),
      providerEnv: 'DEV',
      providerName: 'DEV_PAYMENT'
    }))
  })

  it('MATCHED 明细禁用调整；DIFFERENCE 明细按参考口径显示「渠道 − 本地」并读回更新', async () => {
    await router.push({ name: 'SystemReconciliation' })
    // 服务端 differenceAmount = 系统 − 渠道 = +2.00；参考的第三列是「渠道 − 本地」，必须是 −2.00
    paymentApiMock.reconciliationItems.mockResolvedValue(page([{
      ...differenceRow,
      id: 10,
      status: 'DIFFERENCE',
      systemAmount: 160,
      providerAmount: 158,
      differenceAmount: 2
    }]))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="fin-adjust"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('渠道 − 本地')
    expect(wrapper.text()).toContain('¥ -2.00')
    expect(wrapper.text()).toContain('渠道账单与本地支付记录相差 ¥ 2.00 元，等待核对费用口径。')

    paymentApiMock.adjustReconciliation.mockResolvedValue({
      ...differenceRow,
      id: 10,
      systemAmount: 160,
      providerAmount: 158,
      differenceAmount: 2,
      status: 'ADJUSTED',
      adjustmentAmount: -2,
      adjustmentReason: '费用口径核对'
    })
    await wrapper.find('[data-test="fin-adjust"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('此操作只更新对账明细的调整金额、原因和处理状态。不会改变订单金额、储值账本或发起资金转移。')
    // 参考弹层的金额默认值是裸差额数字（-2），不补零
    expect((wrapper.find('[data-test="fin-adjust-amount"]').element as HTMLInputElement).value).toBe('-2')

    await wrapper.find('[data-test="fin-adjust-reason"]').setValue('费用口径核对')
    await wrapper.find('[data-test="fin-adjust-submit"]').trigger('submit')
    await flushPromises()

    expect(paymentApiMock.adjustReconciliation).toHaveBeenCalledWith(10, {
      adjustmentAmount: -2,
      adjustmentReason: '费用口径核对'
    })
    expect(wrapper.text()).toContain('已记录调整 ¥ -2.00')
    expect(wrapper.text()).toContain('支付对账记录已更新，资金与储值账本没有变动。')
  })

  it('MATCHED 明细不提供调整入口语义（按钮禁用）且零调整不显示已记录提示', async () => {
    await router.push({ name: 'SystemReconciliation' })
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="fin-adjust"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('账实一致')
    // adjustment_amount 列 NOT NULL DEFAULT 0：未调整过的明细不得渲染「已记录调整」
    expect(wrapper.find('[data-test="fin-adjusted-notice"]').exists()).toBe(false)
  })

  it('渠道缺失明细按参考语义显示「未取得 / 待核对」且不声称扣款失败', async () => {
    await router.push({ name: 'SystemReconciliation' })
    paymentApiMock.reconciliationItems.mockResolvedValue(page([{
      ...differenceRow,
      id: 11,
      status: 'MISSING_PROVIDER',
      systemAmount: 60,
      providerAmount: 0,
      differenceAmount: -60
    }]))
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('当前渠道账单尚未包含本地成功记录；不能推断扣款失败。')
    expect(text).toContain('未取得')
    expect(text).toContain('待核对')
  })
})

describe('权限与状态反例', () => {
  it('无任何财务叶子权限时显示无权状态', async () => {
    authState.permissions = new Set<string>()
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="fin-denied"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('没有财务访问权限')
  })

  it('仅对账权限时不读取渠道目录、无储值切换且运行对账不携带渠道字段', async () => {
    // B5-H6：/system-admin/reconciliation 只承载支付对账；providers 端点需要 payment:admin
    authState.permissions = new Set(['finance:reconcile'])
    await router.push({ name: 'SystemReconciliation' })
    const wrapper = mountView()
    await flushPromises()

    expect(paymentApiMock.providers).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="fin-switch-stored"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('支付明细')

    await wrapper.find('[data-test="fin-run-reconcile"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="fin-run-provider"]').exists()).toBe(false)
    await wrapper.find('[data-test="fin-run-submit"]').trigger('submit')
    await flushPromises()
    expect(paymentApiMock.runReconciliation).toHaveBeenCalledWith(expect.objectContaining({
      reconcileDate: expect.any(String)
    }))
    expect(Object.keys(paymentApiMock.runReconciliation.mock.lastCall?.[0])).not.toContain('providerEnv')
  })

  it('查询失败提供重新查询且不伪造通过', async () => {
    paymentApiMock.attempts.mockRejectedValue(new Error('网络中断'))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="fin-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂时无法读取记录')
    // 参考错误态的「重新查询」是主按钮（.btn.primary）
    expect(wrapper.find('[data-test="fin-reload"]').classes()).toContain('primary')
  })
})
