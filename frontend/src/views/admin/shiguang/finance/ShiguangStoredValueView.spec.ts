import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const apiMock = vi.hoisted(() => ({
  summary: vi.fn(),
  products: vi.fn(),
  createProduct: vi.fn(),
  setProductFlags: vi.fn(),
  providers: vi.fn(),
  accounts: vi.fn(),
  recharges: vi.fn(),
  ledger: vi.fn(),
  orderStoredFacts: vi.fn(),
  reconciliationRuns: vi.fn(),
  reconciliationDifferences: vi.fn(),
  runReconciliation: vi.fn(),
  verifyIdentity: vi.fn(),
  freeze: vi.fn(),
  unfreeze: vi.fn(),
  beginClosing: vi.fn(),
  close: vi.fn(),
  refundRecharge: vi.fn(),
  recoverPosting: vi.fn(),
  requestAdjustment: vi.fn(),
  approvals: vi.fn(),
  approval: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
  executeApproval: vi.fn(),
  revokeApproval: vi.fn(),
  disputeRecharge: vi.fn()
}))
const authState = vi.hoisted(() => ({
  permissions: new Set<string>([
    'stored-value:finance:view', 'stored-value:account:freeze', 'stored-value:account:unfreeze',
    'stored-value:account:close', 'stored-value:recharge:execute', 'stored-value:recharge:review',
    'stored-value:refund:manage', 'stored-value:refund:review', 'stored-value:dispute:manage',
    'stored-value:adjustment:execute', 'stored-value:adjustment:review',
    'stored-value:account:unfreeze:review', 'stored-value:reconciliation:run', 'stored-value:policy:manage'
  ])
}))

vi.mock('@/api/storedvalue', () => ({ storedValueAdminApi: apiMock }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: null,
    hasPermission: (permission: string) => authState.permissions.has(permission)
  })
}))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

import ShiguangStoredValueView from './ShiguangStoredValueView.vue'

const page = <T,>(records: T[], total = records.length) => ({ records, total, pageNum: 1, pageSize: 10 })

const providerRow = {
  providerKey: 'storedvalue.dev', providerEnv: 'DEV', providerName: 'STORED_VALUE_DEV',
  configVersion: 'STORED_VALUE_DEV_V1', enabled: true
}

const accountRow = {
  accountRef: 'SVA-0123456789ABCDEF01234567',
  userRef: 'USR-0123456789ABCDEF01234567',
  productCode: 'SG_DEV',
  environment: 'DEV',
  status: 'NORMAL',
  principalAvailable: 320,
  principalFrozen: 0,
  bonusAvailable: 40,
  bonusFrozen: 0,
  availableTotal: 360,
  totalBalance: 360,
  updateTime: '2026-09-15T10:00:00'
}

const frozenAccountRow = { ...accountRow, accountRef: 'SVA-FROZ00000000000000000000', status: 'FROZEN', principalFrozen: 500, principalAvailable: 0, availableTotal: 25, bonusFrozen: 25, bonusAvailable: 0 }

const ledgerRow = {
  transactionRef: 'SVT-0123456789ABCDEF01234567',
  accountRef: accountRow.accountRef,
  userRef: accountRow.userRef,
  transactionType: 'RECHARGE',
  sourceType: 'RECHARGE',
  sourceRef: 'SVR-REF-TEST',
  bucket: 'PRINCIPAL_AVAILABLE',
  direction: 'CREDIT',
  amount: 200,
  currency: 'CNY',
  reason: '本金与赠送金分别入账',
  occurredAt: '2026-09-15T08:30:00'
}

const rechargeRow = {
  rechargeRef: 'SVR-REF-0123456789ABCDEF012345',
  rechargeNoSuffix: 'ABCD1234',
  userRef: accountRow.userRef,
  productCode: 'SG_DEV',
  status: 'SUCCEEDED',
  principalAmount: 100,
  expectedBonusAmount: 5,
  actualBonusAmount: 5,
  currency: 'CNY',
  providerKey: 'storedvalue.dev',
  providerEnv: 'DEV',
  providerName: 'STORED_VALUE_DEV',
  externalRefSummary: 'dev-…X1',
  createTime: '2026-09-15T08:30:00',
  updateTime: '2026-09-15T08:31:00'
}

const pendingPostingRecharge = {
  ...rechargeRow,
  rechargeRef: 'SVR-REF-PENDING00000000000000',
  rechargeNoSuffix: 'PEND1111',
  status: 'PROVIDER_SUCCEEDED_PENDING_POSTING',
  actualBonusAmount: 0,
  failureCode: undefined
}

const manualReviewRecharge = {
  ...rechargeRow,
  rechargeRef: 'SVR-REF-MANUAL000000000000000',
  rechargeNoSuffix: 'MANU2222',
  status: 'MANUAL_REVIEW',
  failureCode: 'PROVIDER_OUTCOME_UNKNOWN',
  actualBonusAmount: 0
}

const approvalRow = {
  approvalNo: 'SVA-20260915-001',
  actionType: 'ACCOUNT_UNFREEZE',
  targetType: 'ACCOUNT',
  targetRef: accountRow.accountRef,
  requesterRef: 'USR-OTHER0000000000000000000',
  requesterIsCurrentActor: false,
  amount: null,
  currency: null,
  reviewPermission: 'stored-value:account:unfreeze:review',
  executePermission: 'stored-value:account:unfreeze',
  status: 'PENDING',
  expiresAt: '2026-09-16T10:10:00',
  reviewerRef: null,
  executorRef: null,
  reason: '账户关联争议已完成核对，申请按原冻结记录解冻。',
  policyVersion: 'R5-DEV-SANDBOX-20260802-v1',
  version: 3,
  reviewedAt: null,
  reviewReason: null,
  executedAt: null,
  executionResult: null,
  createTime: '2026-09-15T10:10:00',
  updateTime: '2026-09-15T10:10:00'
}

const productRow = {
  id: 1,
  productCode: 'SG_DEV',
  productName: '拾光 DEV 储值账户',
  issuerCode: 'DEV',
  currency: 'CNY',
  environment: 'DEV',
  status: 'ENABLED',
  rechargeEnabled: true,
  spendEnabled: true,
  policyVersion: 'R5-DEV-SANDBOX-20260802-v1',
  minimumRecharge: 10,
  maximumRecharge: 5000,
  dailyRechargeLimit: 20000,
  principalBalanceLimit: 50000,
  totalBalanceLimit: 60000,
  bonusRatePercent: 5,
  bonusPerRechargeLimit: 300,
  monthlyBonusLimit: 2000,
  bonusValidDays: 180,
  refundCompensationValidDays: 30
}

const differenceRow = {
  differenceRef: 'SVD-TEST-0001',
  runRef: 'SVRUN-TEST-1',
  differenceType: 'RECHARGE_PENDING_POSTING',
  providerAmount: 100,
  localAmount: 100,
  currency: 'CNY',
  status: 'OPEN',
  reason: 'Provider 已有成功事实，但本地充值尚未完成入账',
  ledgerTransactionRef: null,
  createTime: '2026-09-15T10:12:00'
}

const adjustableDifferenceRow = {
  ...differenceRow,
  differenceRef: 'SVD-TEST-0002',
  differenceType: 'ACCOUNT_PROJECTION_DIFFERENCE',
  providerAmount: 330,
  localAmount: 320,
  reason: '账户投影与账本重建余额不一致: PRINCIPAL_AVAILABLE'
}

let router: Router

const mountView = () => mount(ShiguangStoredValueView, { global: { plugins: [router] } })

beforeEach(async () => {
  vi.clearAllMocks()
  const dialogProto = HTMLDialogElement.prototype as unknown as Record<string, unknown>
  if (typeof dialogProto.showModal !== 'function') {
    dialogProto.showModal = function mockShowModal(this: HTMLDialogElement) { this.setAttribute('open', '') }
  }
  if (typeof dialogProto.close !== 'function') {
    dialogProto.close = function mockClose(this: HTMLDialogElement) { this.removeAttribute('open') }
  }
  authState.permissions = new Set<string>([
    'stored-value:finance:view', 'stored-value:account:freeze', 'stored-value:account:unfreeze',
    'stored-value:account:close', 'stored-value:recharge:execute', 'stored-value:recharge:review',
    'stored-value:refund:manage', 'stored-value:refund:review', 'stored-value:dispute:manage',
    'stored-value:adjustment:execute', 'stored-value:adjustment:review',
    'stored-value:account:unfreeze:review', 'stored-value:reconciliation:run', 'stored-value:policy:manage'
  ])
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/system-admin', name: 'SystemAdminDashboard', component: { template: '<div />' } },
      { path: '/system-admin/payments', name: 'SystemPayments', component: { template: '<div />' } },
      { path: '/system-admin/stored-value', name: 'SystemStoredValue', component: { template: '<div />' } },
      { path: '/system-admin/providers', name: 'SystemProviders', component: { template: '<div />' } },
      { path: '/venue-admin/orders', name: 'VenueAdminOrders', component: { template: '<div />' } }
    ]
  })
  router.push({ name: 'SystemStoredValue' })
  await router.isReady()

  apiMock.providers.mockResolvedValue([providerRow])
  apiMock.accounts.mockResolvedValue(page([accountRow, frozenAccountRow]))
  apiMock.ledger.mockResolvedValue(page([ledgerRow]))
  apiMock.orderStoredFacts.mockResolvedValue({
    orderNo: 'ORD202609152217577602917',
    payment: { paymentNoSuffix: '222270', status: 'SUCCESS', amount: 72, principalAmount: 71.57, bonusAmount: 0.43 },
    refund: null
  })
  apiMock.recharges.mockResolvedValue(page([rechargeRow, pendingPostingRecharge, manualReviewRecharge]))
  apiMock.approvals.mockResolvedValue(page([approvalRow]))
  apiMock.approval.mockResolvedValue(approvalRow)
  apiMock.products.mockResolvedValue([productRow])
  apiMock.reconciliationRuns.mockResolvedValue(page([{
    runRef: 'SVRUN-TEST-1', reconcileDate: '2026-09-15', providerEnv: 'DEV',
    providerName: 'STORED_VALUE_DEV', scopeType: 'STORED_VALUE_FULL', status: 'COMPLETED',
    differenceCount: 2, completedAt: '2026-09-15T10:12:00'
  }]))
  apiMock.reconciliationDifferences.mockResolvedValue(page([differenceRow, adjustableDifferenceRow]))
  apiMock.setProductFlags.mockImplementation((_id: number, payload: Record<string, unknown>) =>
    Promise.resolve({ ...productRow, ...payload }))
  apiMock.approveApproval.mockResolvedValue({ ...approvalRow, status: 'APPROVED', reviewerRef: 'USR-ME' })
})

describe('储值账户面（B5-H8/H9/H13 一比一重建）', () => {
  it('渲染四桶卡片与账户总额', async () => {
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('支付与储值财务')
    expect(text).toContain('本金')
    expect(text).toContain('赠送金')
    expect(text).toContain('¥ 320.00')
    expect(text).toContain('¥ 40.00')
    expect(wrapper.text()).toContain('¥ 360.00')
  })

  it('分账账本按账户引用服务端筛选（B5-H9 回归）', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.ledger).toHaveBeenCalledWith(expect.objectContaining({
      accountRef: accountRow.accountRef
    }))
    expect(wrapper.text()).toContain('分账账本')
    expect(wrapper.text()).toContain('充值')
    expect(wrapper.text()).toContain('贷')
  })

  it('冻结弹层要求业务依据编号并保留转桶文案（B5-H10/H13）', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="sv-freeze"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('冻结储值账户')
    expect(wrapper.text()).toContain('将分别转入对应冻结桶，账户总额保持不变。')
    expect(wrapper.find('[data-test="sv-business-ref"]').exists()).toBe(true)
  })

  it('冻结提交携带业务依据编号与原因', async () => {
    apiMock.freeze.mockResolvedValue({ status: 'FROZEN', message: '账户已冻结' })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="sv-freeze"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="sv-business-ref"]').setValue('SG-B5-S05-01')
    await wrapper.find('[data-test="sv-action-reason"]').setValue('争议核对期间暂停使用')
    await wrapper.find('[data-test="sv-freeze-submit"]').trigger('submit')
    await flushPromises()

    expect(apiMock.freeze).toHaveBeenCalledWith(accountRow.accountRef, {
      reason: '争议核对期间暂停使用',
      businessRef: 'SG-B5-S05-01'
    })
  })

  it('原桶说明按订单号查询并展示分摊（B5-H11）', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="sv-order-facts"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="sv-order-no"]').setValue('ORD202609152217577602917')
    await wrapper.find('[data-test="sv-order-facts-submit"]').trigger('submit')
    await flushPromises()

    expect(apiMock.orderStoredFacts).toHaveBeenCalledWith('ORD202609152217577602917')
    expect(wrapper.text()).toContain('订单原桶说明')
    expect(wrapper.text()).toContain('原支付：本金 ¥ 71.57 + 赠送金 ¥ 0.43')
  })
})

describe('充值处理面（B5-H14/H15/H16）', () => {
  const gotoRecharges = async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="sv-tab-recharges"]').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('预计/实际赠送并列且未入账态实际为 0（B5-H15）', async () => {
    const wrapper = await gotoRecharges()
    expect(wrapper.text()).toContain('预计 / 实际赠送')
    expect(wrapper.text()).toContain('¥ 5.00 / ¥ 5.00')

    await wrapper.find('[data-test="sv-recharge-SVR-REF-PENDING00000000000000"]').trigger('click')
    await flushPromises()
    // 参考口径：未入账态「预计 5.00 / 实际 0.00」，本金展示在 h-money，不前端倒算
    expect(wrapper.text()).toContain('¥ 5.00 / ¥ 0.00')
    expect(wrapper.text()).toContain('尚未完成本金和赠送金入账。恢复前需要再次核对渠道与目标快照。')
  })

  it('状态门禁按钮对齐真实合同（B5-H16）', async () => {
    const wrapper = await gotoRecharges()
    // SUCCEEDED：退款可见、恢复不可见
    expect(wrapper.find('[data-test="sv-request-refund"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="sv-request-recovery"]').exists()).toBe(false)

    // PROVIDER_SUCCEEDED_PENDING_POSTING：恢复可见、退款不可见
    await wrapper.find('[data-test="sv-recharge-SVR-REF-PENDING00000000000000"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="sv-request-recovery"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="sv-request-refund"]').exists()).toBe(false)

    // MANUAL_REVIEW：只读异常证据入口（B5-H14）
    await wrapper.find('[data-test="sv-recharge-SVR-REF-MANUAL000000000000000"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="sv-recharge-evidence"]').exists()).toBe(true)

    await wrapper.find('[data-test="sv-recharge-evidence"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('充值异常最小证据')
    expect(wrapper.text()).toContain('PROVIDER_OUTCOME_UNKNOWN')
    expect(wrapper.text()).toContain('没有可确认的最新成功结果。当前不重复扣款、不自动恢复入账。')
  })
})

describe('审批工作台（B5-H17/H18/H19/H20）', () => {
  const gotoApprovals = async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="sv-tab-approvals"]').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('仅财务查看权限时不能查看审批事项（B5-H19）', async () => {
    authState.permissions = new Set(['stored-value:finance:view'])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="sv-tab-approvals"]').exists()).toBe(false)
  })

  it('三步职责轨道与占位文案逐字保留（B5-H18）', async () => {
    const wrapper = await gotoApprovals()
    const text = wrapper.text()
    expect(text).toContain('先核对，再分别负责')
    expect(text).toContain('申请与复核不能由同一人完成；复核人与执行人必须不同。')
    expect(text).toContain('02 / 独立复核')
    expect(text).toContain('申请人不能自审')
    expect(text).toContain('03 / 执行')
    expect(text).toContain('不能由本次复核人执行')
  })

  it('按编号查询终态可达（B5-H17）', async () => {
    const wrapper = await gotoApprovals()
    await wrapper.find('[data-test="sv-lookup-approval"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="sv-lookup-no"]').setValue('SVA-20260915-001')
    await wrapper.find('[data-test="sv-lookup-submit"]').trigger('submit')
    await flushPromises()
    expect(apiMock.approval).toHaveBeenCalledWith('SVA-20260915-001')
  })

  it('他人申请可复核、本人申请禁用复核入口（自审分离）', async () => {
    const wrapper = await gotoApprovals()
    expect(wrapper.find('[data-test="sv-approve"]').attributes('disabled')).toBeUndefined()

    const own = { ...approvalRow, requesterIsCurrentActor: true }
    apiMock.approvals.mockResolvedValue(page([own]))
    const ownView = mountView()
    await flushPromises()
    await ownView.find('[data-test="sv-tab-approvals"]').trigger('click')
    await flushPromises()
    expect(ownView.find('[data-test="sv-approve"]').attributes('disabled')).toBeDefined()
    expect(ownView.find('[data-test="sv-reject"]').attributes('disabled')).toBeDefined()
    expect(ownView.find('[data-test="sv-revoke"]').exists()).toBe(true)
  })

  it('批准后按钮切换为执行并提交执行', async () => {
    apiMock.executeApproval.mockResolvedValue({
      ...approvalRow,
      status: 'CONSUMED',
      executorRef: 'USR-ME',
      executedAt: '2026-09-15T11:00:00',
      executionResult: '账户解冻完成，按原冻结记录恢复分桶。'
    })
    const wrapper = await gotoApprovals()
    await wrapper.find('[data-test="sv-approve"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="sv-action-reason"]').setValue('金额、Provider 与原冻结记录一致。')
    await wrapper.find('[data-test="sv-approve-submit"]').trigger('submit')
    await flushPromises()

    expect(apiMock.approveApproval).toHaveBeenCalledWith('SVA-20260915-001', '金额、Provider 与原冻结记录一致。')
    expect(wrapper.find('[data-test="sv-execute"]').exists()).toBe(true)
    await wrapper.find('[data-test="sv-execute"]').trigger('click')
    await flushPromises()
    expect(apiMock.executeApproval).toHaveBeenCalledWith('SVA-20260915-001')
    expect(wrapper.text()).toContain('执行结果')
  })
})

describe('产品与政策（B5-H12/H21/H22）', () => {
  const gotoProducts = async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="sv-tab-products"]').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('允许充值/消费开关与产品状态齐全（B5-H21）', async () => {
    const wrapper = await gotoProducts()
    expect(wrapper.find('[data-test="sv-toggle-recharge-SG_DEV"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="sv-toggle-spend-SG_DEV"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="sv-toggle-status-SG_DEV"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('关闭入口不会重写已经发生的资金事实。')
  })

  it('政策快照弹层渲染真实参数（B5-H12）', async () => {
    const wrapper = await gotoProducts()
    await wrapper.find('[data-test="sv-policy-SG_DEV"]').trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('既有政策快照')
    expect(text).toContain('¥ 10.00 – ¥ 5000.00 元')
    expect(text).toContain('¥ 20000.00 元')
    expect(text).toContain('5% · 按分向下取整')
    expect(text).toContain('180 天')
    expect(text).toContain('只读快照，沿用已存金额规则。产品开关不编辑这些参数。')
  })

  it('关闭充值入口经确认后提交服务端', async () => {
    const wrapper = await gotoProducts()
    await wrapper.find('[data-test="sv-toggle-recharge-SG_DEV"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('关闭充值入口。已存在交易、账户和账本保留。')
    await wrapper.find('[data-test="sv-toggle-submit"]').trigger('submit')
    await flushPromises()
    expect(apiMock.setProductFlags).toHaveBeenCalledWith(1, { rechargeEnabled: false })
  })

  it('DEV 身份核验弹层提交用户、摘要与有效期（B5-H22）', async () => {
    const wrapper = await gotoProducts()
    await wrapper.find('[data-test="sv-identity-SG_DEV"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="sv-identity-user"]').setValue('9014')
    await wrapper.find('[data-test="sv-identity-digest"]').setValue('dev-evidence-synthetic-7A00')
    await wrapper.find('[data-test="sv-identity-expires"]').setValue('2027-09-12')
    await wrapper.find('[data-test="sv-identity-submit"]').trigger('submit')
    await flushPromises()

    expect(apiMock.verifyIdentity).toHaveBeenCalledWith(9014, 'dev-evidence-synthetic-7A00', '2027-09-12')
  })
})

describe('对账差异（储值侧，B5-H6/H7）', () => {
  const gotoReconciliation = async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="sv-tab-reconciliation"]').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('运行对账只生成核对结果不自动平账', async () => {
    apiMock.runReconciliation.mockResolvedValue({
      runRef: 'SVRUN-2', reconcileDate: '2026-09-15', providerEnv: 'DEV',
      providerName: 'STORED_VALUE_DEV', scopeType: 'STORED_VALUE_FULL', status: 'COMPLETED',
      differenceCount: 0, completedAt: '2026-09-15T11:00:00'
    })
    const wrapper = await gotoReconciliation()
    await wrapper.find('[data-test="sv-run-reconcile"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('运行只生成账实核对结果，不自动平账。')
    await wrapper.find('[data-test="sv-run-submit"]').trigger('submit')
    await flushPromises()
    expect(apiMock.runReconciliation).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/))
  })

  it('申请调账金额必须等于渠道减本地的非零差额', async () => {
    apiMock.reconciliationDifferences.mockResolvedValue(page([adjustableDifferenceRow]))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="sv-tab-reconciliation"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('¥ 10.00')
    await wrapper.find('[data-test="sv-request-adjustment"]').trigger('click')
    await flushPromises()
    // 预填差额 10
    expect((wrapper.find('[data-test="sv-adjust-amount"]').element as HTMLInputElement).value).toBe('10.00')
    await wrapper.find('[data-test="sv-adjust-amount"]').setValue('5')
    await wrapper.find('[data-test="sv-adjust-reason"]').setValue('口径核对')
    await wrapper.find('[data-test="sv-adjust-submit"]').trigger('submit')
    await flushPromises()

    expect(apiMock.requestAdjustment).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('申请金额必须等于渠道减本地的非零差额。')
  })

  it('零差额差异（RECHARGE_PENDING_POSTING）不提供调账可能', async () => {
    apiMock.reconciliationDifferences.mockResolvedValue(page([differenceRow]))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="sv-tab-reconciliation"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-test="sv-request-adjustment"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="sv-adjust-reason"]').setValue('口径核对')
    await wrapper.find('[data-test="sv-adjust-submit"]').trigger('submit')
    await flushPromises()
    // provider == local，差额为 0：非零校验拦截（服务端同样拦截）
    expect(apiMock.requestAdjustment).not.toHaveBeenCalled()
  })

  it('RESOLVED 差异展示完成提示', async () => {
    apiMock.reconciliationDifferences.mockResolvedValue(page([{ ...adjustableDifferenceRow, status: 'RESOLVED', ledgerTransactionRef: 'SVT-RESOLVED' }]))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="sv-tab-reconciliation"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('差异已完成审批执行，并追加了本金调账流水。')
    expect(wrapper.find('[data-test="sv-request-adjustment"]').attributes('disabled')).toBeDefined()
  })
})
