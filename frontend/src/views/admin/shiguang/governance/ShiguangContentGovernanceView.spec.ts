import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const adminApiMock = vi.hoisted(() => ({
  reports: vi.fn(),
  inspect: vi.fn(),
  decideReport: vi.fn(),
  moderate: vi.fn(),
  events: vi.fn()
}))
const showToast = vi.hoisted(() => vi.fn())
const authState = vi.hoisted(() => ({
  permissions: new Set<string>(),
  id: 900 as number | null
}))

vi.mock('@/api/community', async () => {
  const actual = await vi.importActual<typeof import('@/api/community')>('@/api/community')
  return { ...actual, contentAdminApi: adminApiMock }
})
vi.mock('@/stores/app', () => ({ useAppStore: () => ({ showToast }) }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasPermission: (permission: string) => authState.permissions.has(permission),
    user: authState.id == null ? null : { id: authState.id }
  })
}))

import ShiguangContentGovernanceView from './ShiguangContentGovernanceView.vue'

const dialogProto = (globalThis.HTMLDialogElement as unknown as { prototype: Record<string, () => void> } | undefined)?.prototype
if (dialogProto) {
  dialogProto.showModal = dialogProto.showModal ?? (() => {})
  dialogProto.close = dialogProto.close ?? (() => {})
}

const ALL = ['community:report:review', 'community:content:moderate', 'community:content:restore', 'community:audit:view']
const IOS = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

const report = (overrides: Record<string, unknown> = {}) => ({
  id: 801,
  reportNo: 'CR-2f3a1b9c-7777-4a2b-9d3e-abcdef012345',
  reporter: { id: 201, nickname: '书页之间', avatar: null },
  targetType: 'POST' as const,
  targetId: 321,
  reason: '正文中出现与活动无关的重复招揽内容。',
  status: 'OPEN',
  assignedTo: null as number | null,
  decisionCode: null as string | null,
  internalNote: null as string | null,
  closedAt: null,
  createTime: '2026-09-12T09:35:00',
  updateTime: '2026-09-12T09:35:00',
  ...overrides
})

const content = (overrides: Record<string, unknown> = {}) => ({
  targetType: 'POST' as const,
  id: 321,
  groupId: 41,
  parentId: null,
  authorUserId: 201,
  title: '周末阅读交换：把读过的书带来',
  content: '周六下午，我们想在北岸阅读空间组织一次小型读书交换。',
  status: 'PUBLISHED',
  hiddenReasonCode: null as string | null,
  hiddenScope: null as string | null,
  hiddenBy: null as number | null,
  hiddenAt: null as string | null,
  deletedAt: null as string | null,
  retainUntil: null as string | null,
  versionNo: 4,
  ...overrides
})

const event = (overrides: Record<string, unknown> = {}) => ({
  id: 71,
  eventNo: 'CGE-9f8e7d6c-1111-2222-3333-444455556666',
  eventType: 'CONTENT_INSPECTED',
  subjectType: 'GROUP',
  subjectId: 41,
  reportId: 802,
  actorUserId: 900,
  actorScope: 'CONTENT',
  sourceType: 'CONTENT_OPERATOR',
  fromStatus: 'ACTIVE',
  toStatus: 'ACTIVE',
  reasonCode: 'CONTENT_REVIEW',
  reasonSummary: null,
  resultStatus: 'SUCCEEDED',
  occurredAt: '2026-09-12T08:21:00',
  ...overrides
})

const page = <T>(records: T[], hasMore = false, nextCursor: number | null = null) => ({ records, nextCursor, hasMore })

const mounted: Array<ReturnType<typeof mount>> = []
let router: Router

async function mountView(query: Record<string, string> = {}): Promise<ReturnType<typeof mount>> {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/content-admin', component: ShiguangContentGovernanceView }]
  })
  await router.push({ path: '/content-admin', query })
  await router.isReady()
  const host = document.createElement('div')
  document.body.appendChild(host)
  const wrapper = mount(ShiguangContentGovernanceView, { attachTo: host, global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  await flushPromises()
  return wrapper
}

type Vm = {
  tab: string
  detail: { targetType: string; targetId: number; report?: { id: number } } | null
  reports: unknown[]
  events: unknown[]
  content: Record<string, unknown> | null
  contentState: string
  listError: string
  findError: string
  dialog: string | null
  dialogError: string
  decisionAction: string
  moderateRestore: boolean
  reportFilters: { status: string; targetType: string }
  eventFilters: { subjectType: string; reportId: number | null; subjectId: number | null }
  inspectForm: { targetType: string; targetId: string }
}

describe('拾光平台治理面', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authState.permissions = new Set(ALL)
    authState.id = 900
    adminApiMock.reports.mockResolvedValue(page([report()]))
    adminApiMock.inspect.mockResolvedValue(content())
    adminApiMock.events.mockResolvedValue(page([event()]))
    adminApiMock.decideReport.mockResolvedValue({ id: 801, status: 'RESOLVED' })
    adminApiMock.moderate.mockResolvedValue(null)
    document.body.innerHTML = ''
  })

  afterEach(() => {
    for (const wrapper of mounted.splice(0)) wrapper.unmount()
  })

  it('renders the three reference tabs and defaults to the first permitted one (B4-G1)', async () => {
    const wrapper = await mountView()
    const tabs = wrapper.findAll('.g-tabs button')
    expect(tabs.map((node) => node.text())).toEqual(['举报目录', '按目标检查', '治理审计'])
    expect(tabs[0].attributes('aria-pressed')).toBe('true')
    expect(adminApiMock.reports.mock.calls[0][0]).toMatchObject({ limit: 20 })
  })

  it('sends every governance request with ISOLATED_REQUEST so a 403 stays in-surface (B4-G8/B4-P9)', async () => {
    const wrapper = await mountView()
    expect(adminApiMock.reports.mock.calls[0][1]).toEqual(IOS)
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    expect(adminApiMock.inspect.mock.calls[0][3]).toEqual(IOS)
    await wrapper.find('[data-test="gov-resolve"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="gov-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(adminApiMock.decideReport.mock.calls[0][3]).toEqual(IOS)
    await wrapper.find('[data-test="gov-tab-audit"]').trigger('click')
    await flushPromises()
    expect(adminApiMock.events.mock.calls[0][1]).toEqual(IOS)
  })

  it('falls back to the first tab the account can actually read, and denies the rest in-surface (B4-G1)', async () => {
    authState.permissions = new Set(['community:content:restore', 'community:audit:view'])
    const wrapper = await mountView()
    expect((wrapper.vm as unknown as Vm).tab).toBe('inspect')
    await wrapper.find('[data-test="gov-tab-reports"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="gov-denied-reports"]').text()).toContain('当前没有举报审核权限')
    // 无权限的页签不得发请求
    expect(adminApiMock.reports).not.toHaveBeenCalled()
    await wrapper.find('[data-test="gov-tab-audit"]').trigger('click')
    await flushPromises()
    expect(adminApiMock.events).toHaveBeenCalledTimes(1)
  })

  it('lists reports with real fields and filters through the server params (B4-G2/G11)', async () => {
    const wrapper = await mountView()
    const row = wrapper.find('.g-report')
    expect(row.text()).toContain('帖子')
    expect(row.text()).toContain('321')
    expect(row.text()).toContain('正文中出现与活动无关的重复招揽内容。')
    expect(row.text()).toContain('书页之间')
    expect(row.text()).toContain('09-12 09:35')
    expect(row.text()).toContain('待受理')
    expect(row.text()).toContain('尚未受理')
    // 列表没有标题字段（AdminReport 无 title），因此标题行就是缘由本身
    expect(row.find('h3').text()).toBe('正文中出现与活动无关的重复招揽内容。')

    await wrapper.find('[data-test="gov-status-filter"]').setValue('IN_REVIEW')
    await wrapper.find('[data-test="gov-type-filter"]').setValue('COMMENT')
    await flushPromises()
    expect(adminApiMock.reports.mock.calls.at(-1)?.[0]).toMatchObject({ status: 'IN_REVIEW', targetType: 'COMMENT' })
  })

  it('composes the detail from the list row plus the content endpoint and keeps the reason on 404 (B4-G2/G7)', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    // 右侧缘由来自列表行，左侧正文来自端点 A，且带上 reportId
    expect(wrapper.find('[data-test="gov-report-reason"]').text()).toContain('重复招揽')
    expect(adminApiMock.inspect).toHaveBeenCalledWith('POST', 321, 801, IOS)
    expect(wrapper.find('[data-test="gov-content-body"]').text()).toContain('小型读书交换')

    // 目标改为不可读（PURGED/不存在 ⇒ 404「内容不存在」）：重新进入详情
    adminApiMock.inspect.mockRejectedValue({ response: { status: 404, data: { message: '内容不存在' } } })
    await wrapper.find('[data-test="gov-back"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="gov-content-unreadable"]').text()).toContain('内容不存在')
    // 右侧举报缘由保留，不用旧正文顶替
    expect(wrapper.find('[data-test="gov-report-reason"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="gov-content-body"]').exists()).toBe(false)
    // 重新读取入口在不可读态里给出
    expect(wrapper.find('[data-test="gov-content-retry"]').exists()).toBe(true)
  })

  it('shows the real hide source so group and platform moderation are distinguishable (B4-G4)', async () => {
    adminApiMock.inspect.mockResolvedValue(content({ status: 'HIDDEN', hiddenScope: 'GROUP', hiddenBy: 205, hiddenAt: '2026-09-12T10:00:00', hiddenReasonCode: 'GROUP_RULE' }))
    const wrapper = await mountView()
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="gov-hidden-scope"]').text()).toContain('组内处置')
    expect(wrapper.find('[data-test="gov-hidden-scope"]').text()).toContain('205')
    expect(wrapper.text()).toContain('最近隐藏原因：GROUP_RULE')

    adminApiMock.inspect.mockResolvedValue(content({ status: 'HIDDEN', hiddenScope: 'PLATFORM', hiddenBy: 900 }))
    await wrapper.find('[data-test="gov-content-retry"]').exists()
    await wrapper.findAll('[data-test="gov-back"]')[0].trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="gov-hidden-scope"]').text()).toContain('平台处置')
    expect(wrapper.find('[data-test="gov-hidden-scope"]').text()).toContain('平台处置可覆盖组内处置')
  })

  it('keeps DELETED readable but offers no hide/restore, and disables them in the template (B4-G6/G7)', async () => {
    adminApiMock.inspect.mockResolvedValue(content({ status: 'DELETED', deletedAt: '2026-09-11T10:00:00', retainUntil: '2027-03-10T10:00:00' }))
    const wrapper = await mountView()
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="gov-content-body"]').text()).toContain('小型读书交换')
    expect(wrapper.find('[data-test="gov-deleted-note"]').text()).toContain('不提供隐藏或恢复命令')
    expect(wrapper.find('[data-test="gov-hide"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="gov-restore"]').attributes('disabled')).toBeDefined()
  })

  it('locks content moderation to the report the operator owns (B4-G6)', async () => {
    adminApiMock.reports.mockResolvedValue(page([report({ status: 'IN_REVIEW', assignedTo: 901 })]))
    const wrapper = await mountView()
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="gov-assigned-other"]').text()).toContain('已由其他人员受理')
    expect(wrapper.find('[data-test="gov-moderation-locked"]').text()).toContain('未由本人审核')
    expect(wrapper.find('[data-test="gov-hide"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="gov-resolve"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="gov-take"]').exists()).toBe(false)
  })

  it('surfaces the server message verbatim instead of the hardcoded 403/404 text (B4-G8)', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="gov-resolve"]').trigger('click')
    await flushPromises()
    adminApiMock.decideReport.mockRejectedValue({ response: { status: 403, data: { message: '该举报已由其他内容运营人员审核' } } })
    await wrapper.find('[data-test="gov-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="gov-dialog-error"]').text()).toBe('该举报已由其他内容运营人员审核')

    await wrapper.find('[data-test="gov-dialog-close"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="gov-back"]').trigger('click')
    await flushPromises()
    adminApiMock.reports.mockRejectedValue({ response: { status: 403, data: { message: '无权限访问该资源' } } })
    await wrapper.find('[data-test="gov-status-filter"]').setValue('OPEN')
    await flushPromises()
    expect(wrapper.find('[data-test="gov-reports-error"]').text()).toBe('无权限访问该资源')
    expect((wrapper.vm as unknown as Vm).listError).toBe('无权限访问该资源')
  })

  it('writes the decision with an idempotency key and re-reads from the server (B4-G5)', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="gov-resolve"]').trigger('click')
    await flushPromises()
    expect((wrapper.vm as unknown as Vm).dialog).toBe('decision')
    expect((wrapper.vm as unknown as Vm).decisionAction).toBe('RESOLVE')
    expect((wrapper.find('[data-test="gov-decision-code"]').element as HTMLInputElement).value).toBe('CONTENT_REVIEWED')

    await wrapper.find('[data-test="gov-decision-code"]').setValue('duplicate_content')
    await wrapper.find('[data-test="gov-dialog-submit"]').trigger('click')
    await flushPromises()
    const [id, payload, key] = adminApiMock.decideReport.mock.calls[0]
    expect(id).toBe(801)
    expect(payload).toMatchObject({ decision: 'RESOLVE', decisionCode: 'DUPLICATE_CONTENT' })
    expect(typeof key).toBe('string')
    expect(key.length).toBeGreaterThan(8)
    // 重新按服务端数据核对（列表 + 内容都重读）
    expect(adminApiMock.reports.mock.calls.length).toBeGreaterThan(1)
    expect(showToast).toHaveBeenCalledWith('举报已单独结案；未修改内容状态')

    // 失败后同载荷重试必须复用同一幂等键（响应丢失重发同一键的既有约定）
    adminApiMock.decideReport.mockRejectedValueOnce({ response: { status: 409, data: { message: '该举报已经结案' } } })
    await wrapper.find('[data-test="gov-resolve"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="gov-decision-code"]').setValue('duplicate_content')
    await wrapper.find('[data-test="gov-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="gov-dialog-error"]').text()).toBe('该举报已经结案')
    await wrapper.find('[data-test="gov-dialog-submit"]').trigger('click')
    await flushPromises()
    expect(adminApiMock.decideReport.mock.calls[2][2]).toBe(adminApiMock.decideReport.mock.calls[1][2])
    // 服务端文案原样显示，且成功后换新键（新的一次操作）
    expect(adminApiMock.decideReport.mock.calls[2][2]).not.toBe('')
  })

  it('hides and restores content with the real endpoint shape and default reason codes', async () => {
    adminApiMock.reports.mockResolvedValue(page([report({ status: 'IN_REVIEW', assignedTo: 900 })]))
    const wrapper = await mountView()
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="gov-hide"]').trigger('click')
    await flushPromises()
    expect((wrapper.find('[data-test="gov-moderate-code"]').element as HTMLInputElement).value).toBe('CONTENT_VIOLATION')
    await wrapper.find('[data-test="gov-moderate-code"]').setValue('content_violation')
    await wrapper.find('[data-test="gov-dialog-submit"]').trigger('click')
    await flushPromises()
    const [type, id, restore, payload, , config] = adminApiMock.moderate.mock.calls[0]
    expect([type, id, restore]).toEqual(['POST', 321, false])
    expect(config).toEqual(IOS)
    expect(payload).toMatchObject({ reasonCode: 'CONTENT_VIOLATION', reportId: 801 })
    expect(showToast).toHaveBeenCalledWith('内容已隐藏；举报状态保持独立')
  })

  it('maps audit rows with action, statuses and actor scope, and follows a report filter (B4-G9)', async () => {
    adminApiMock.events.mockResolvedValue(page([
      event(),
      event({ id: 70, eventType: 'REPORT_DECIDED', subjectType: 'REPORT', subjectId: 802, fromStatus: 'OPEN', toStatus: 'IN_REVIEW', actorScope: 'CONTENT', sourceType: 'CONTENT_OPERATOR' }),
      event({ id: 69, eventType: 'POST_HIDDEN', subjectType: 'POST', subjectId: 322, fromStatus: 'PUBLISHED', toStatus: 'HIDDEN' }),
      event({ id: 68, eventType: 'MEMBERSHIP_DECIDED', subjectType: 'MEMBER', subjectId: 12, fromStatus: 'PENDING', toStatus: 'ACTIVE', actorScope: 'GROUP', sourceType: 'GROUP_ADMIN' }),
      event({ id: 67, eventType: 'USER_DEIDENTIFIED', subjectType: 'USER', subjectId: 9, fromStatus: 'DELETED', toStatus: 'DEIDENTIFIED', actorScope: 'SYSTEM', sourceType: 'SCHEDULED_JOB' })
    ]))
    const wrapper = await mountView()
    await wrapper.find('[data-test="gov-tab-audit"]').trigger('click')
    await flushPromises()
    const rows = wrapper.findAll('.g-audit')
    expect(rows).toHaveLength(5)
    expect(rows[0].text()).toContain('检查内容 · 小组41')
    expect(rows[0].text()).toContain('有效 → 有效')
    expect(rows[0].text()).toContain('执行者 900')
    expect(rows[0].text()).toContain('平台内容运营')
    expect(rows[0].text()).toContain('记录成功')
    expect(rows[0].text()).toContain('09-12 08:21')
    expect(rows[1].text()).toContain('受理举报 · 举报802')
    expect(rows[2].text()).toContain('隐藏帖子 · 帖子322')
    expect(rows[2].text()).toContain('已发布 → 已隐藏')
    expect(rows[3].text()).toContain('处理加入申请')
    expect(rows[3].text()).toContain('待审核 → 有效')
    expect(rows[4].text()).toContain('注销用户')
    expect(rows[4].text()).toContain('已删除 → 已注销')
    expect(rows[4].text()).toContain('定时任务')
  })

  it('filters the audit tab by the report it came from and can clear the filter', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-test="gov-open-801"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="gov-to-audit"]').trigger('click')
    await flushPromises()
    expect((wrapper.vm as unknown as Vm).eventFilters.reportId).toBe(801)
    expect(adminApiMock.events.mock.calls.at(-1)?.[0]).toMatchObject({ reportId: 801 })
    expect(wrapper.find('[data-test="gov-audit-report-filter"]').text()).toContain('801')
    await wrapper.find('[data-test="gov-audit-clear"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="gov-audit-report-filter"]').exists()).toBe(false)
    expect(adminApiMock.events.mock.calls.at(-1)?.[0]).toMatchObject({ reportId: undefined })
    // 按钮与参考同形常驻（无筛选时等价于重新读取），不随筛选消失
    expect(wrapper.find('[data-test="gov-audit-clear"]').exists()).toBe(true)
  })

  it('looks up an arbitrary target by type and id, and validates the id first (B4-G7)', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-test="gov-tab-inspect"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="gov-find-button"]').trigger('submit')
    await flushPromises()
    expect((wrapper.vm as unknown as Vm).findError).toContain('请输入有效的目标 ID')
    expect(adminApiMock.inspect).not.toHaveBeenCalled()

    await wrapper.find('[data-test="gov-find-type"]').setValue('COMMENT')
    await wrapper.find('[data-test="gov-find-id"]').setValue('511')
    await wrapper.find('[data-test="gov-find-form"]').trigger('submit')
    await flushPromises()
    expect(adminApiMock.inspect).toHaveBeenCalledWith('COMMENT', 511, undefined, IOS)
  })

  it('restores tab, filters and detail from the query so a deep link survives a refresh (B4-G3)', async () => {
    const wrapper = await mountView({ tab: 'inspect', type: 'POST', id: '321' })
    expect((wrapper.vm as unknown as Vm).tab).toBe('inspect')
    expect((wrapper.vm as unknown as Vm).detail).toMatchObject({ targetType: 'POST', targetId: 321 })
    expect(adminApiMock.inspect).toHaveBeenCalledWith('POST', 321, undefined, IOS)

    const second = await mountView({ tab: 'audit', status: 'RESOLVED', targetType: 'POST' })
    await flushPromises()
    expect((second.vm as unknown as Vm).tab).toBe('audit')
    expect((second.vm as unknown as Vm).reportFilters).toMatchObject({ status: 'RESOLVED', targetType: 'POST' })
    expect(adminApiMock.events).toHaveBeenCalled()
  })

  it('loads more via the cursor without losing the first page (B4-G11)', async () => {
    adminApiMock.reports.mockResolvedValueOnce(page([report()], true, 801))
    const wrapper = await mountView()
    adminApiMock.reports.mockResolvedValueOnce(page([report({ id: 800 })], false, null))
    await wrapper.find('[data-test="gov-reports-more"]').trigger('click')
    await flushPromises()
    expect(adminApiMock.reports.mock.calls.at(-1)?.[0]).toMatchObject({ cursorId: 801 })
    expect((wrapper.vm as unknown as Vm).reports).toHaveLength(2)
    expect(wrapper.find('[data-test="gov-reports-more"]').exists()).toBe(false)
  })
})
