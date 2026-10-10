import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const memberApiMock = vi.hoisted(() => ({
  levels: vi.fn(),
  createLevel: vi.fn(),
  updateLevel: vi.fn(),
  updateLevelStatus: vi.fn(),
  accounts: vi.fn(),
  account: vi.fn(),
  overview: vi.fn()
}))
const rewardApiMock = vi.hoisted(() => ({
  listCatalogItems: vi.fn(),
  createCatalogItem: vi.fn(),
  updateCatalogItemStatus: vi.fn(),
  publishVersion: vi.fn(),
  exceptions: vi.fn(),
  retry: vi.fn(),
  compensate: vi.fn(),
  events: vi.fn()
}))
const promotionApiMock = vi.hoisted(() => ({ couponTemplates: vi.fn() }))
const showToast = vi.hoisted(() => vi.fn())
const authState = vi.hoisted(() => ({
  permissions: new Set([
    'member:manage',
    'member-reward:catalog:manage',
    'member-reward:exception:manage',
    'member-reward:audit:view'
  ])
}))

vi.mock('@/api/member', () => ({
  adminMemberApi: memberApiMock,
  adminMemberRewardApi: rewardApiMock
}))
vi.mock('@/api/promotion', () => ({ promotionAdminApi: promotionApiMock }))
vi.mock('@/stores/app', () => ({ useAppStore: () => ({ showToast }) }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasPermission: (permission: string) => authState.permissions.has(permission)
  })
}))

import ShiguangMemberAdminView from './ShiguangMemberAdminView.vue'

const page = <T>(records: T[], total = records.length) => ({ records, total, pageNum: 1, pageSize: 10 })

const accountRow = {
  userId: 2048,
  username: 'linjian',
  nickname: '林间小满',
  levelCode: 'REGULAR',
  levelName: '常来',
  growthValue: 2480,
  pointsBalance: 680,
  totalConsumptionAmount: 2480,
  totalReservations: 32,
  totalMemberDiscountAmount: 186,
  lastActiveTime: '2026-09-12T09:40:00'
}

const levelRow = {
  id: 1,
  levelCode: 'BASIC',
  levelName: '初见',
  growthThreshold: 0,
  discountRate: 1,
  benefitText: '从第一次到访开始，记录每一次闲暇。',
  ruleVersion: 'B2_MEMBER_V1',
  status: 'ACTIVE'
}

const catalogRow = {
  itemId: 11,
  itemCode: 'AFTERNOON_10',
  itemName: '工作日下午小憩 · 10 元场馆券',
  descriptionText: '工作日下午可用',
  status: 'ACTIVE',
  versionId: 31,
  versionNo: 3,
  pointsCost: 300,
  minimumLevelCode: 'REGULAR',
  minimumLevelName: '常来',
  couponTemplateId: 5,
  validityDays: 14,
  eligible: true
}

const draftCatalogRow = { ...catalogRow, itemId: 12, itemCode: 'READING_10', itemName: '秋日阅读券', status: 'DRAFT', versionId: undefined, versionNo: undefined }

const failedRedemption = {
  id: 82,
  redemptionNo: 'MR20260912094100ABCDEFGHIJ',
  itemCode: 'AFTERNOON_10',
  itemName: '工作日下午小憩 · 10 元场馆券',
  versionNo: 3,
  pointsCost: 300,
  status: 'FAILED',
  failureCode: 'EXECUTION_ERROR',
  failureMessage: '兑换执行失败，可由授权人员重试',
  userCouponId: null
}

const succeededRedemption = {
  ...failedRedemption,
  id: 70,
  status: 'SUCCEEDED',
  failureCode: null,
  failureMessage: null,
  userCouponId: 501
}

const eventRow = {
  id: 71,
  eventType: 'REDEMPTION_SUCCEEDED',
  redemptionId: 70,
  sourceType: 'USER',
  fromStatus: 'PENDING',
  toStatus: 'SUCCEEDED',
  occurredAt: '2026-09-12T09:41:00'
}

// jsdom 未实现原生 <dialog> 的 showModal/close，按第三批同样做法补齐原型后再断言弹层内容。
const dialogProto = (globalThis.HTMLDialogElement as unknown as { prototype: Record<string, () => void> } | undefined)?.prototype
if (dialogProto) {
  dialogProto.showModal = dialogProto.showModal ?? (() => {})
  dialogProto.close = dialogProto.close ?? (() => {})
}

function resetMocks(): void {
  // 跨用例必须清空调用记录，否则「无权时不发请求」这类断言会被前一个用例的调用污染。
  vi.clearAllMocks()
  authState.permissions = new Set([
    'member:manage',
    'member-reward:catalog:manage',
    'member-reward:exception:manage',
    'member-reward:audit:view'
  ])
  memberApiMock.accounts.mockResolvedValue(page([accountRow]))
  memberApiMock.account.mockResolvedValue({ ...accountRow, discountRate: 0.95 })
  memberApiMock.overview.mockResolvedValue({
    totalMembers: 128,
    activeMembers: 128,
    levelDistribution: { 初见: 2, 常来: 3, 相知: 1 },
    coreUsers: [accountRow],
    dormantUsers: []
  })
  memberApiMock.levels.mockResolvedValue([levelRow])
  memberApiMock.createLevel.mockResolvedValue(levelRow)
  memberApiMock.updateLevel.mockResolvedValue(levelRow)
  memberApiMock.updateLevelStatus.mockResolvedValue(undefined)
  rewardApiMock.listCatalogItems.mockResolvedValue([catalogRow, draftCatalogRow])
  rewardApiMock.createCatalogItem.mockResolvedValue(draftCatalogRow)
  rewardApiMock.updateCatalogItemStatus.mockResolvedValue(catalogRow)
  rewardApiMock.publishVersion.mockResolvedValue(catalogRow)
  rewardApiMock.exceptions.mockResolvedValue(page([failedRedemption]))
  rewardApiMock.retry.mockResolvedValue({ ...failedRedemption, status: 'SUCCEEDED' })
  rewardApiMock.compensate.mockResolvedValue({ ...succeededRedemption, status: 'COMPENSATED' })
  rewardApiMock.events.mockResolvedValue(page([eventRow]))
  promotionApiMock.couponTemplates.mockResolvedValue(page([{ id: 5, templateName: '下午场馆 10 元券', status: 'ACTIVE' }]))
}

async function mountView(path = '/system-admin/members'): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/system-admin/members', name: 'SystemMembers', component: ShiguangMemberAdminView },
      { path: '/system-admin/reward-catalog', name: 'SystemRewardCatalog', component: ShiguangMemberAdminView }
    ]
  })
  await router.push(path)
  await router.isReady()
  const host = document.createElement('div')
  document.body.appendChild(host)
  const wrapper = mount(ShiguangMemberAdminView, { global: { plugins: [router] }, attachTo: host })
  await flushPromises()
  await flushPromises()
  return { wrapper, router }
}

type Vm = {
  view: string
  surface: string
  dialog: { kind: string; title: string; submit: string; error: string } | null
}

describe('拾光会员与权益面', () => {
  beforeEach(() => {
    resetMocks()
    showToast.mockClear()
    document.body.innerHTML = ''
  })

  it('routes /system-admin/members to the accounts tab and renders the real registry fields', async () => {
    const { wrapper } = await mountView('/system-admin/members')
    expect((wrapper.vm as unknown as Vm).view).toBe('accounts')
    expect(memberApiMock.accounts).toHaveBeenCalledWith({ pageNum: 1, pageSize: 10 }, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    expect(memberApiMock.overview).toHaveBeenCalled()
    expect(wrapper.text()).toContain('会员账户登记册')
    expect(wrapper.text()).toContain('林间小满')
    expect(wrapper.text()).toContain('常来')
    expect(wrapper.text()).toContain('2,480')
    expect(wrapper.text()).toContain('¥ 2480.00')
    expect(wrapper.text()).toContain('09-12 09:40')
    expect(wrapper.find('[data-test="account-table"]').exists()).toBe(true)
  })

  it('keeps the upstream search capability honest and clears the condition', async () => {
    const { wrapper } = await mountView('/system-admin/members')
    const input = wrapper.find('[data-test="account-search"]')
    expect(input.attributes('placeholder')).toBe('输入会员用户名或昵称')
    await input.setValue('林间')
    await wrapper.find('[data-test="account-search-form"]').trigger('submit')
    await flushPromises()
    expect(memberApiMock.accounts).toHaveBeenLastCalledWith(
      { pageNum: 1, pageSize: 10, keyword: '林间' },
      { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
    )
  })

  it('renders the empty state when the register has no matching member', async () => {
    memberApiMock.accounts.mockResolvedValue(page([], 0))
    const { wrapper } = await mountView('/system-admin/members')
    expect(wrapper.find('[data-test="account-empty"]').exists()).toBe(true)
    await wrapper.find('[data-test="account-clear"]').trigger('click')
    await flushPromises()
    expect(memberApiMock.accounts).toHaveBeenLastCalledWith(
      { pageNum: 1, pageSize: 10 },
      { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
    )
  })

  it('shows the account detail with real discount and next-level facts', async () => {
    const { wrapper } = await mountView('/system-admin/members')
    await wrapper.find('[data-test="account-open-2048"]').trigger('click')
    await flushPromises()
    const dialog = wrapper.find('[data-test="member-dialog-form"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('9.5 折')
    expect(dialog.text()).toContain('¥ 186.00')
    expect(dialog.text()).toContain('32 次')
    expect(dialog.text()).toContain('已是当前最高等级。')
    expect(memberApiMock.account).toHaveBeenCalledWith(2048, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
  })

  it('renders level cards from real fields and resends ruleVersion when editing (B4-M6)', async () => {
    const { wrapper } = await mountView('/system-admin/members')
    await wrapper.find('[data-test="tab-levels"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('成长对应的会员规则')
    expect(wrapper.text()).toContain('标准价')
    expect(wrapper.text()).toContain('B2_MEMBER_V1')

    await wrapper.find('[data-test="level-edit-0"]').trigger('click')
    await flushPromises()
    const versionInput = wrapper.find('[data-test="level-version"]')
    expect((versionInput.element as HTMLInputElement).value).toBe('B2_MEMBER_V1')

    await wrapper.find('[data-test="member-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(memberApiMock.updateLevel).toHaveBeenCalledWith(1, expect.objectContaining({
      ruleVersion: 'B2_MEMBER_V1',
      levelCode: 'BASIC',
      discountRate: 1
    }), { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
  })

  it('rejects an out-of-range discount rate before calling the service', async () => {
    const { wrapper } = await mountView('/system-admin/members')
    await wrapper.find('[data-test="tab-levels"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="level-create"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="level-name"]').setValue('新档')
    await wrapper.find('[data-test="level-code"]').setValue('NEW')
    await wrapper.find('[data-test="level-version"]').setValue('L9')
    await wrapper.find('[data-test="level-benefit"]').setValue('说明')
    await wrapper.find('[data-test="level-rate"]').setValue('1.4')
    await wrapper.find('[data-test="member-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(memberApiMock.createLevel).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="member-dialog-error"]').text()).toContain('折扣率应大于 0 且不超过 1')
  })

  it('routes /system-admin/reward-catalog to the rewards tab and keeps the three catalog states (B4-M12)', async () => {
    const { wrapper } = await mountView('/system-admin/reward-catalog')
    expect((wrapper.vm as unknown as Vm).view).toBe('rewards')
    expect(wrapper.text()).toContain('权益目录与当前版本')
    expect(wrapper.text()).toContain('当前版本 V3')
    expect(wrapper.text()).toContain('300 积分 · 常来及以上 · 券有效 14 天')
    expect(wrapper.text()).toContain('草稿')
    expect(wrapper.text()).toContain('当前无生效版本')
    expect(wrapper.text()).toContain('尚无已发布的兑换条件')
    expect(promotionApiMock.couponTemplates).toHaveBeenCalledWith(
      { pageNum: 1, pageSize: 100, status: 'ACTIVE' },
      { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
    )
  })

  it('surfaces the extra read permissions instead of showing empty selects (B4-M4)', async () => {
    memberApiMock.levels.mockRejectedValue({ response: { data: { message: '无权限访问该资源' } } })
    promotionApiMock.couponTemplates.mockRejectedValue({ response: { data: { message: '无权限访问该资源' } } })
    const { wrapper } = await mountView('/system-admin/reward-catalog')
    const note = wrapper.find('[data-test="reward-dependency-note"]')
    expect(note.exists()).toBe(true)
    expect(note.text()).toContain('会员等级选项不可用')
    expect(note.text()).toContain('优惠券模板选项不可用')
    // 目录本身仍可用，不因域外依赖失败而整页报错
    expect((wrapper.vm as unknown as Vm).surface).toBe('ready')
    expect(wrapper.text()).toContain('工作日下午小憩')
  })

  it('offers retry only for FAILED and compensation only for SUCCEEDED with a coupon (B4-M2/M16)', async () => {
    rewardApiMock.exceptions.mockResolvedValue(page([failedRedemption, succeededRedemption]))
    const { wrapper } = await mountView('/system-admin/members')
    await wrapper.find('[data-test="tab-exceptions"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-test="exception-open-0"]').trigger('click')
    await flushPromises()
    expect((wrapper.vm as unknown as Vm).dialog?.submit).toBe('重试原兑换')
    expect(wrapper.find('[data-test="compensate-reason"]').exists()).toBe(false)
    // 失败态如实显示服务端原因，不写成预览的「发券失败」
    expect(wrapper.find('[data-test="member-dialog-form"]').text()).toContain('兑换执行失败，可由授权人员重试')
    expect(wrapper.find('[data-test="member-dialog-form"]').text()).not.toContain('发券失败')
    await wrapper.find('[data-test="member-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(rewardApiMock.retry).toHaveBeenCalledWith(82, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })

    await wrapper.find('[data-test="exception-open-1"]').trigger('click')
    await flushPromises()
    expect((wrapper.vm as unknown as Vm).dialog?.submit).toBe('申请补偿并退回积分')
    expect(wrapper.find('[data-test="compensate-reason"]').exists()).toBe(true)
    // 补偿原因必填：空原因不发请求
    await wrapper.find('[data-test="member-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(rewardApiMock.compensate).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="member-dialog-error"]').text()).toContain('请先填写补偿原因')
  })

  it('passes a real status filter so SUCCEEDED becomes reachable, and keeps PENDING read-only (B4-M1)', async () => {
    rewardApiMock.exceptions.mockResolvedValue(page([{ ...failedRedemption, id: 79, status: 'PENDING' }]))
    const { wrapper } = await mountView('/system-admin/members')
    await wrapper.find('[data-test="tab-exceptions"]').trigger('click')
    await flushPromises()
    expect(rewardApiMock.exceptions).toHaveBeenLastCalledWith(
      { pageNum: 1, pageSize: 10 },
      { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
    )
    await wrapper.find('[data-test="exception-status"]').setValue('SUCCEEDED')
    await flushPromises()
    expect(rewardApiMock.exceptions).toHaveBeenLastCalledWith(
      { pageNum: 1, pageSize: 10, status: 'SUCCEEDED' },
      { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
    )
    // 处理中没有任何写动作
    expect((wrapper.vm as unknown as Vm).surface).toBe('ready')
  })

  it('labels events through the derived whitelist and falls back to the raw value (B4-M10/M11)', async () => {
    rewardApiMock.events.mockResolvedValue(page([
      eventRow,
      { ...eventRow, id: 72, eventType: 'REDEMPTION_FAILED', sourceType: 'SYSTEM', fromStatus: 'PENDING', toStatus: 'FAILED' },
      { ...eventRow, id: 73, eventType: 'FUTURE_UNKNOWN_EVENT', sourceType: 'PARTNER' }
    ]))
    const { wrapper } = await mountView('/system-admin/members')
    await wrapper.find('[data-test="tab-events"]').trigger('click')
    await flushPromises()
    const table = wrapper.find('[data-test="event-table"]').text()
    expect(table).toContain('积分兑换成功')
    expect(table).toContain('会员自助')
    expect(table).toContain('处理中 → 兑换成功')
    expect(table).toContain('兑换执行失败')
    expect(table).toContain('系统触发')
    expect(table).toContain('FUTURE_UNKNOWN_EVENT')
    expect(table).toContain('PARTNER')
  })

  it('denies a pane whose leaf permission is missing and does not call its API', async () => {
    authState.permissions = new Set(['member:manage'])
    const { wrapper } = await mountView('/system-admin/members')
    await wrapper.find('[data-test="tab-rewards"]').trigger('click')
    await flushPromises()
    const denied = wrapper.find('[data-test="denied-rewards"]')
    expect(denied.exists()).toBe(true)
    expect(denied.text()).toContain('没有权益目录管理权限')
    expect(denied.text()).toContain('member-reward:catalog:manage')
    expect(rewardApiMock.listCatalogItems).not.toHaveBeenCalled()
    expect(promotionApiMock.couponTemplates).not.toHaveBeenCalled()

    await wrapper.find('[data-test="tab-events"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="denied-events"]').exists()).toBe(true)
    expect(rewardApiMock.events).not.toHaveBeenCalled()
  })

  it('publishes the first version without fabricating a history list (§9.2 B4-M5)', async () => {
    const { wrapper } = await mountView('/system-admin/reward-catalog')
    await wrapper.find('[data-test="reward-publish-1"]').trigger('click')
    await flushPromises()
    const form = wrapper.find('[data-test="member-dialog-form"]').text()
    expect(form).toContain('当前没有已发布的兑换版本')
    expect((wrapper.vm as unknown as Vm).dialog?.submit).toBe('确认发布首个版本')
  })

  it('sends the effective window as optional so “leave blank = effective now” keeps working (B4-M13)', async () => {
    const { wrapper } = await mountView('/system-admin/reward-catalog')
    await wrapper.find('[data-test="reward-publish-0"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="member-dialog-submit"]').trigger('click')
    await flushPromises()
    const [, payload] = rewardApiMock.publishVersion.mock.calls[0]
    expect(payload.effectiveStartTime).toBeUndefined()
    expect(payload.effectiveEndTime).toBeUndefined()
    expect(payload.couponTemplateId).toBe(5)
  })

  it('restores focus to the trigger after the dialog closes (B3-T72 chain)', async () => {
    const { wrapper } = await mountView('/system-admin/members')
    const trigger = wrapper.find('[data-test="account-open-2048"]').element as HTMLElement
    await wrapper.find('[data-test="account-open-2048"]').trigger('click')
    await flushPromises()
    wrapper.find('dialog').element.dispatchEvent(new Event('close'))
    await flushPromises()
    expect(document.activeElement).toBe(trigger)
  })

  it('hash 深链优先于两条路由各自的默认页签（F-20）', async () => {
    const cases: Array<[string, string]> = [
      ['/system-admin/members#levels', 'levels'],
      ['/system-admin/members#rewards', 'rewards'],
      ['/system-admin/members#exceptions', 'exceptions'],
      ['/system-admin/members#events', 'events'],
      ['/system-admin/reward-catalog#accounts', 'accounts'],
      ['/system-admin/reward-catalog#levels', 'levels']
    ]
    for (const [path, tab] of cases) {
      resetMocks()
      const { wrapper } = await mountView(path)
      expect((wrapper.vm as unknown as Vm).view, path).toBe(tab)
    }
  })

  it('非法 hash 回落该路由默认页签，账户深链在权益目录同样成立（F-20）', async () => {
    const byMembers = await mountView('/system-admin/members#<页签>')
    expect((byMembers.wrapper.vm as unknown as Vm).view).toBe('accounts')

    const byCatalog = await mountView('/system-admin/reward-catalog#nope')
    expect((byCatalog.wrapper.vm as unknown as Vm).view).toBe('rewards')

    const backToAccounts = await mountView('/system-admin/reward-catalog#accounts')
    expect((backToAccounts.wrapper.vm as unknown as Vm).view).toBe('accounts')
    expect(memberApiMock.accounts).toHaveBeenCalled()
  })

  it('无权页签的深链停在原页签并给出无权态，不请求该领域端点（F-20）', async () => {
    authState.permissions = new Set(['member:manage'])
    const { wrapper } = await mountView('/system-admin/members#exceptions')
    expect((wrapper.vm as unknown as Vm).view).toBe('exceptions')
    expect(wrapper.find('[data-test="denied-exceptions"]').exists()).toBe(true)
    expect(rewardApiMock.exceptions).not.toHaveBeenCalled()
  })

  it('一条权限都没有时确定性停在首个页签（F-20）', async () => {
    authState.permissions = new Set<string>()
    const { wrapper } = await mountView('/system-admin/members')
    expect((wrapper.vm as unknown as Vm).view).toBe('accounts')
    const catalog = await mountView('/system-admin/reward-catalog')
    expect((catalog.wrapper.vm as unknown as Vm).view).toBe('accounts')
  })
})
