import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

const feedbackApiMock = vi.hoisted(() => ({
  adminCases: vi.fn(),
  adminComplaints: vi.fn(),
  adminCaseEvents: vi.fn(),
  assignCase: vi.fn(),
  changeCaseStatus: vi.fn(),
  replyCase: vi.fn(),
  resolveCase: vi.fn(),
  closeCase: vi.fn(),
  reopenCase: vi.fn(),
  adminReviews: vi.fn(),
  moderateReview: vi.fn(),
  adminContext: vi.fn()
}))
const showToast = vi.hoisted(() => vi.fn())

vi.mock('@/api/feedback', () => ({ feedbackApi: feedbackApiMock }))
vi.mock('@/stores/app', () => ({ useAppStore: () => ({ showToast, toastMessage: '' }) }))
vi.mock('@/api/venue', () => ({
  venueApi: { spaces: vi.fn().mockResolvedValue([]) },
  venueAdminApi: { manageable: vi.fn().mockResolvedValue([]) }
}))

import ShiguangVenueCustomerServiceView from './ShiguangVenueCustomerServiceView.vue'
import { useVenueAccessStore } from '@/stores/venueAccess'

const granted = new Set<string>(['venue-admin:view', 'customer-service:manage', 'review:manage', 'customer-service:analytics'])

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: (key: string) => granted.has(key) })
}))

const today = new Date()
const iso = (offsetDays: number, hour = 9, minute = 18) => {
  const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() + offsetDays, hour, minute)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
}

const kase = (overrides: Record<string, unknown> = {}) => ({
  id: 1,
  caseNo: 'CS-0912-018',
  complaintId: 11,
  venueId: 101,
  priority: 'HIGH',
  status: 'OPEN',
  openedAt: iso(0),
  ...overrides
})

const complaint = (overrides: Record<string, unknown> = {}) => ({
  id: 11,
  complaintNo: 'CP-0912-018',
  venueId: 101,
  spaceId: 73,
  reservationId: 900,
  complaintType: 'EQUIPMENT',
  severity: 'HIGH',
  status: 'SUBMITTED',
  title: '预约的羽毛球场照明闪烁，希望安排后续处理',
  contentSanitized: '昨晚 19:00 的预约中，靠近后场的灯持续闪烁。',
  ...overrides
})

const review = (overrides: Record<string, unknown> = {}) => ({
  id: 101,
  reviewNo: 'RV-0912-101',
  venueId: 101,
  overallRating: 4,
  venueRating: 4,
  contentSanitized: '场地本身不错，但灯光问题影响了使用。',
  status: 'PUBLISHED',
  submittedAt: iso(0),
  ...overrides
})

const page = <T>(records: T[]) => ({ records, total: records.length, pageNum: 1, pageSize: 100 })

function allow(...keys: string[]): void {
  granted.clear()
  for (const key of keys) granted.add(key)
}

beforeEach(() => {
  vi.clearAllMocks()
  allow('venue-admin:view', 'customer-service:manage', 'review:manage', 'customer-service:analytics')
  setActivePinia(createPinia())
  const venueAccess = useVenueAccessStore()
  venueAccess.venues = [{ id: 101, name: '北岸生活场馆', area: '', address: '', status: 'ACTIVE' }] as never
  venueAccess.activeVenueId = 101

  feedbackApiMock.adminCases.mockResolvedValue(page([kase()]))
  feedbackApiMock.adminComplaints.mockResolvedValue(page([complaint()]))
  feedbackApiMock.adminCaseEvents.mockResolvedValue([
    { id: 1, eventType: 'CREATED', sourceType: 'USER', occurredAt: iso(0), noteSanitized: '现场已换场，仍希望了解后续处理。' },
    { id: 2, eventType: 'STATUS_CHANGED', fromStatus: 'OPEN', toStatus: 'IN_PROGRESS', occurredAt: iso(0), internalNoteSanitized: '已通知现场同事' }
  ])
  feedbackApiMock.adminReviews.mockResolvedValue(page([review()]))
  feedbackApiMock.adminContext.mockResolvedValue({
    venueIds: [101],
    complaintCount: 7,
    caseCount: 5,
    reviewCount: 3,
    complaints: [{ id: 11, title: '预约的羽毛球场照明闪烁', complaintNo: 'CP-0912-018', complaintType: 'EQUIPMENT' }],
    cases: [{ id: 1, caseNo: 'CS-0912-018', status: 'OPEN', complaintId: 11 }]
  })
  feedbackApiMock.assignCase.mockResolvedValue(kase({ status: 'IN_PROGRESS', assignedAt: iso(0) }))
  feedbackApiMock.changeCaseStatus.mockResolvedValue(kase({ status: 'IN_PROGRESS' }))
  feedbackApiMock.replyCase.mockResolvedValue({ id: 9, eventType: 'REPLY_ADDED' })
  feedbackApiMock.resolveCase.mockResolvedValue(kase({ status: 'RESOLVED' }))
  feedbackApiMock.closeCase.mockResolvedValue(kase({ status: 'CLOSED' }))
  feedbackApiMock.reopenCase.mockResolvedValue(kase({ status: 'REOPENED' }))
  feedbackApiMock.moderateReview.mockResolvedValue(review({ status: 'FLAGGED' }))

  const proto = (globalThis.HTMLDialogElement as unknown as { prototype: Record<string, () => void> }).prototype
  proto.showModal = proto.showModal ?? (() => {})
  proto.close = proto.close ?? (() => {})
})

async function mountView(hash = ''): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/venue-admin/customer-service', name: 'VenueAdminCustomerService', component: ShiguangVenueCustomerServiceView }
    ]
  })
  await router.push(`/venue-admin/customer-service${hash}`)
  await router.isReady()
  // 焦点断言要求组件挂在真实 document 上，否则 focus() 不生效
  const host = document.createElement('div')
  document.body.appendChild(host)
  const wrapper = mount(ShiguangVenueCustomerServiceView, { global: { plugins: [router] }, attachTo: host })
  await flushPromises()
  await flushPromises()
  await flushPromises()
  return { wrapper, router }
}

describe('ShiguangVenueCustomerServiceView 队列与权限门控', () => {
  it('页头逐字保留，工单与投诉并发关联出标题', async () => {
    const { wrapper } = await mountView()
    expect(wrapper.text()).toContain('把每一次反馈，接着处理好。')
    expect(wrapper.text()).toContain('从用户的原始问题开始，保留沟通与处理上下文，让下一步回应有依据。')
    expect(wrapper.find('[data-test="tab-cases"]').attributes('aria-pressed')).toBe('true')
    const item = wrapper.find('[data-test="case-1"]')
    expect(item.text()).toContain('预约的羽毛球场照明闪烁，希望安排后续处理')
    expect(item.text()).toContain('CS-0912-018')
    expect(item.text()).toContain('已打开')
    expect(item.text()).toContain('高优先级')
    expect(feedbackApiMock.adminCases).toHaveBeenCalledWith(
      expect.objectContaining({ venueId: 101 }),
      expect.objectContaining({ skipGlobalErrorToast: true })
    )
    expect(feedbackApiMock.adminComplaints).toHaveBeenCalledWith(expect.objectContaining({ venueId: 101 }), expect.anything())
  })

  it('缺 customer-service:manage 时工单侧一个请求都不发并呈现该面无权态', async () => {
    allow('venue-admin:view', 'review:manage', 'customer-service:analytics')
    const { wrapper } = await mountView()
    expect(wrapper.text()).toContain('未获得工单处理权限')
    expect(feedbackApiMock.adminCases).not.toHaveBeenCalled()
    expect(feedbackApiMock.adminComplaints).not.toHaveBeenCalled()
    expect(feedbackApiMock.adminCaseEvents).not.toHaveBeenCalled()
    expect(feedbackApiMock.adminReviews).toHaveBeenCalled()
  })

  it('缺 review:manage 时不读评价并呈现该面无权态', async () => {
    allow('venue-admin:view', 'customer-service:manage', 'customer-service:analytics')
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-reviews"]').trigger('click')
    expect(wrapper.find('[data-test="denied-reviews"]').text()).toContain('未获得评价审核权限')
    expect(feedbackApiMock.adminReviews).not.toHaveBeenCalled()
  })

  it('缺 customer-service:analytics 时不读上下文', async () => {
    allow('venue-admin:view', 'customer-service:manage', 'review:manage')
    const { wrapper } = await mountView()
    expect(feedbackApiMock.adminContext).not.toHaveBeenCalled()
    await wrapper.find('[data-test="tab-context"]').trigger('click')
    expect(wrapper.find('[data-test="denied-context"]').text()).toContain('关联上下文需要单独授权')
  })

  it('#reviews 深链决定初始页签', async () => {
    const { wrapper } = await mountView('#reviews')
    expect(wrapper.find('[data-test="tab-reviews"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('先读完内容，再决定如何公开。')
    expect(wrapper.text()).toContain('已发布')
  })

  it('状态筛选把真实状态码发到服务端', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="case-filter"]').setValue('IN_PROGRESS')
    await flushPromises()
    expect(feedbackApiMock.adminCases).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'IN_PROGRESS' }),
      expect.anything()
    )
  })

  it('关键词走真实双查询并按并集呈现', async () => {
    const second = kase({ id: 2, caseNo: 'CS-0912-012', complaintId: 12 })
    const secondComplaint = complaint({ id: 12, title: '雨天活动通知与现场指引不一致' })
    // 关键词只命中投诉标题、没命中任何工单编号：并集仍应带出该工单（B3-T25）
    feedbackApiMock.adminCases.mockImplementation(async (params: Record<string, unknown>) =>
      (params.keyword ? page([]) : page([kase(), second])))
    feedbackApiMock.adminComplaints.mockImplementation(async (params: Record<string, unknown>) =>
      (params.keyword ? page([secondComplaint]) : page([complaint(), secondComplaint])))
    const { wrapper } = await mountView()
    expect(wrapper.findAll('.a-queue-item')).toHaveLength(2)
    await wrapper.find('[data-test="case-search"]').setValue('雨天')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 400))
    await flushPromises()
    const titles = wrapper.findAll('.a-queue-item h3').map((node) => node.text())
    expect(titles).toEqual(['雨天活动通知与现场指引不一致'])
    expect(feedbackApiMock.adminCases).toHaveBeenCalledWith(expect.objectContaining({ keyword: '雨天' }), expect.anything())
    expect(feedbackApiMock.adminComplaints).toHaveBeenCalledWith(expect.objectContaining({ keyword: '雨天' }), expect.anything())
  })

  it('无匹配时给出清除筛选而不是空表', async () => {
    feedbackApiMock.adminCases.mockResolvedValue(page([]))
    feedbackApiMock.adminComplaints.mockResolvedValue(page([]))
    const { wrapper } = await mountView()
    expect(wrapper.text()).toContain('没有匹配的工单。')
    expect(wrapper.find('[data-test="clear-filters"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('调整队列条件后继续。')
  })

  it('无可管理场馆时进入场馆锁定态', async () => {
    const venueAccess = useVenueAccessStore()
    venueAccess.venues = [] as never
    venueAccess.activeVenueId = null
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="surface-lock"]').text()).toContain('没有可处理的场馆')
    expect(feedbackApiMock.adminCases).not.toHaveBeenCalled()
  })
})

describe('ShiguangVenueCustomerServiceView 详情与处理记录', () => {
  it('详情按真实字段呈现来源、关联与处理人', async () => {
    const { wrapper } = await mountView()
    const detail = wrapper.find('[id="case-detail"]')
    expect(detail.text()).toContain('CS-0912-018 · 设备问题')
    expect(detail.text()).toContain('北岸生活场馆')
    expect(detail.text()).toContain('提交 · 未分派')
    expect(detail.text()).toContain('昨晚 19:00 的预约中，靠近后场的灯持续闪烁。')
    const side = wrapper.find('.a-side')
    expect(side.text()).toContain('预约反馈')
    expect(side.text()).toContain('投诉 CP-0912-018 · 预约 #900 · 场地 #73')
    expect(side.text()).toContain('未分派')
    expect(wrapper.text()).not.toContain('示例用户')
  })

  it('时间线保留内部备注行并把状态转移译成中文', async () => {
    const { wrapper } = await mountView()
    const rows = wrapper.findAll('.a-timeline li')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('工单已创建 · 用户')
    expect(rows[1].text()).toContain('处理状态已更新')
    expect(rows[1].text()).toContain('已打开 → 处理中')
    expect(rows[1].text()).toContain('内部备注 · 仅授权后台人员可见：已通知现场同事')
  })

  it('终态工单收起回复表单并给出重开与关闭入口', async () => {
    feedbackApiMock.adminCases.mockResolvedValue(page([kase({ status: 'RESOLVED' })]))
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="reply-form"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('处理记录继续保留。若问题仍然存在，可填写原因重新打开。')
    expect(wrapper.find('[data-test="open-reopen"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="open-close"]').exists()).toBe(true)
  })
})

describe('ShiguangVenueCustomerServiceView 写动作', () => {
  it('回复经二次确认提交，且不改工单状态', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="reply-text"]').setValue('我们已安排复核。')
    await wrapper.find('[data-test="internal-note"]').setValue('已通知现场')
    await wrapper.find('[data-test="reply-form"]').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="reply-preview"]').text()).toBe('我们已安排复核。')
    await wrapper.find('[data-test="action-form"]').trigger('submit')
    await flushPromises()
    expect(feedbackApiMock.replyCase).toHaveBeenCalledWith(1, { replyText: '我们已安排复核。', internalNote: '已通知现场' }, expect.anything())
    expect(feedbackApiMock.changeCaseStatus).not.toHaveBeenCalled()
    expect(showToast).toHaveBeenCalledWith('回复已发送')
  })

  it('转处理中走真实 status 端点', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="open-in-progress"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="field-reason"]').setValue('现场已换场，继续跟进设备。')
    await wrapper.find('[data-test="action-form"]').trigger('submit')
    await flushPromises()
    expect(feedbackApiMock.changeCaseStatus).toHaveBeenCalledWith(1, { status: 'IN_PROGRESS' }, expect.anything())
  })

  it('解决提交真实根因、处理类型与三个复核标记', async () => {
    feedbackApiMock.adminCases.mockResolvedValue(page([kase({ status: 'IN_PROGRESS', assignedAt: iso(0) })]))
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="open-resolve"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="field-root"]').setValue('EQUIPMENT_MAINTENANCE')
    await wrapper.find('[data-test="field-type"]').setValue('REFUND_REVIEW')
    await wrapper.find('[data-test="field-reason"]').setValue('已安排检修并说明处理结果。')
    await wrapper.find('[data-test="field-flag-requiresSiteFollowUp"]').setValue(true)
    await wrapper.find('[data-test="action-form"]').trigger('submit')
    await flushPromises()
    expect(feedbackApiMock.resolveCase).toHaveBeenCalledWith(1, {
      rootCauseCode: 'EQUIPMENT_MAINTENANCE',
      resolutionType: 'REFUND_REVIEW',
      replyText: '已安排检修并说明处理结果。',
      requiresRefundReview: false,
      requiresCouponReview: false,
      requiresSiteFollowUp: true
    }, expect.anything())
  })

  it('未分派工单直接解决时按服务端原文留在弹层', async () => {
    feedbackApiMock.resolveCase.mockRejectedValue({ response: { data: { message: '当前客服工单状态不可解决' } } })
    feedbackApiMock.adminCases.mockResolvedValue(page([kase({ status: 'IN_PROGRESS' })]))
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="open-resolve"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="field-reason"]').setValue('已处理')
    await wrapper.find('[data-test="action-form"]').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('当前客服工单状态不可解决')
    expect(wrapper.find('[data-test="dialog-confirm"]').exists()).toBe(true)
  })

  it('关闭与重开各自前置不满足时不发请求', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="open-close-side"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="field-reason"]').setValue('重复反馈')
    await wrapper.find('[data-test="action-form"]').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('工单需先标记解决才能关闭。')
    expect(feedbackApiMock.closeCase).not.toHaveBeenCalled()
  })

  it('分派按真实处理人 ID 提交', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="open-assign"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="field-owner"]').setValue('102')
    await wrapper.find('[data-test="action-form"]').trigger('submit')
    await flushPromises()
    expect(feedbackApiMock.assignCase).toHaveBeenCalledWith(1, { assignedUserId: 102 }, expect.anything())
    expect(showToast).toHaveBeenCalledWith('工单已分派')
  })
})

describe('ShiguangVenueCustomerServiceView 评价审核与关联上下文', () => {
  it('审核弹层按字典动作与真实 reasonCode 提交', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-reviews"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已发布')
    await wrapper.find('[data-test="open-moderate-101"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="field-moderation"]').setValue('HIDE')
    await wrapper.find('[data-test="field-reason"]').setValue('含未核实指控')
    await wrapper.find('[data-test="action-form"]').trigger('submit')
    await flushPromises()
    expect(feedbackApiMock.moderateReview).toHaveBeenCalledWith(
      101,
      { action: 'HIDE', reasonCode: 'MANUAL_REVIEW', reasonText: '含未核实指控' },
      expect.anything()
    )
  })

  it('审核不写原因时不发请求', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-reviews"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="open-moderate-101"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="action-form"]').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('请填写审核原因。')
    expect(feedbackApiMock.moderateReview).not.toHaveBeenCalled()
  })

  it('上下文按真实计数关联工单并可跳入', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="tab-context"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('北岸生活场馆的关联线索')
    expect(wrapper.text()).toContain('投诉 7')
    expect(wrapper.text()).toContain('工单 5')
    expect(wrapper.text()).toContain('评价 3')
    const row = wrapper.find('[data-test="context-row"]')
    expect(row.text()).toContain('关联投诉 → 工单 CS-0912-018 · 已打开')
    await row.find('[data-test="context-case-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="tab-cases"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-test="case-1"]').attributes('aria-pressed')).toBe('true')
  })
})

describe('ShiguangVenueCustomerServiceView 弹层焦点合同', () => {
  it('取消关闭后焦点回到触发控件（重渲染后按 data-test 复位）', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="open-assign"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-cancel"]').exists()).toBe(true)
    // jsdom 不派发 dialog 的 close 事件，这里直接派发原生事件以核对恢复链
    wrapper.find('dialog').element.dispatchEvent(new Event('close'))
    await flushPromises()
    // 每个用例都往 document.body 挂一个新 host，全局 querySelector 会命中别的用例，这里只比对本组件子树
    const live = wrapper.find('[data-test="open-assign"]').element
    expect(document.activeElement).toBe(live)
    expect(live.getAttribute('data-test')).toBe('open-assign')
    wrapper.unmount()
  })
})
