import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

const noticeApiMock = vi.hoisted(() => ({
  messages: vi.fn(),
  unreadCount: vi.fn(),
  previewAudience: vi.fn(),
  publish: vi.fn(),
  deliveryProviders: vi.fn(),
  deliveryOverview: vi.fn(),
  deliveryBatches: vi.fn(),
  deliveryBatch: vi.fn(),
  deliveryOutboxes: vi.fn(),
  deliveryAudiences: vi.fn(),
  retryOutbox: vi.fn(),
  createExperiment: vi.fn(),
  experiments: vi.fn(),
  experimentResults: vi.fn()
}))
const showToast = vi.hoisted(() => vi.fn())

vi.mock('@/api/notice', () => ({ noticeApi: noticeApiMock }))
vi.mock('@/stores/app', () => ({ useAppStore: () => ({ showToast, toastMessage: '' }) }))
vi.mock('@/api/venue', () => ({
  venueApi: { spaces: vi.fn().mockResolvedValue([]) },
  venueAdminApi: { manageable: vi.fn().mockResolvedValue([]) }
}))

import ShiguangNoticeJourneyView from './ShiguangNoticeJourneyView.vue'
import { useVenueAccessStore } from '@/stores/venueAccess'

const granted = new Set<string>(['venue-admin:view', 'system:view', 'notice:manage', 'notice:analytics'])

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: (key: string) => granted.has(key) })
}))

const inAppProvider = {
  providerKey: 'dev-in-app',
  providerDomain: 'in_app',
  providerEnv: 'DEV',
  providerName: 'in-app',
  channel: 'IN_APP',
  displayName: '站内消息',
  configVersion: 'dev-v1',
  enabled: true,
  defaultProvider: true
}

const emailProvider = {
  providerKey: 'sandbox-smtp',
  providerDomain: 'email',
  providerEnv: 'SANDBOX',
  providerName: 'smtp-sandbox',
  channel: 'EMAIL',
  displayName: '沙箱 SMTP',
  configVersion: 'sandbox-v1',
  enabled: true,
  defaultProvider: false
}

const batch = (over: Record<string, unknown> = {}) => ({
  batchId: 7,
  batchNo: 'BATCH202609140800',
  noticeId: 3,
  targetScope: 'VENUE_RECENT_RESERVERS',
  status: 'PARTIAL_FAILED',
  audienceCount: 128,
  deliverableCount: 120,
  excludedCount: 8,
  sentCount: 118,
  failedCount: 2,
  readCount: 78,
  unreadCount: 40,
  providerEnv: 'DEV',
  providerName: 'in-app',
  configVersion: 'dev-v1',
  createTime: '2026-09-14T08:00:00',
  ...over
})

const inAppOutbox = (over: Record<string, unknown> = {}) => ({
  outboxId: 41,
  outboxNo: 'OB202609140801',
  eventType: 'NOTICE_PUBLISHED',
  recipientKind: 'USER',
  maskedRecipient: null,
  channel: 'IN_APP',
  providerKey: 'dev-in-app',
  providerEnv: 'DEV',
  providerName: 'in-app',
  configVersion: 'dev-v1',
  templateKey: 'notice-in-app',
  templateVersion: 'v1',
  status: 'RETRY_WAITING',
  statusText: 'RETRY_WAITING',
  acceptanceStatus: null,
  downstreamStatus: null,
  attemptCount: 2,
  nextRetryTime: '2026-09-14T08:05:00',
  failureCode: 'PROVIDER_TIMEOUT',
  createTime: '2026-09-14T08:01:00',
  updateTime: '2026-09-14T08:03:00',
  acceptedAt: null,
  ...over
})

const emailRow = (over: Record<string, unknown> = {}) => ({
  outboxId: 42,
  outboxNo: 'OB202609140802',
  eventType: 'RESERVATION_CONFIRMED',
  recipientKind: 'EMAIL',
  maskedRecipient: 's***@example.test',
  channel: 'EMAIL',
  providerKey: 'sandbox-smtp',
  providerEnv: 'SANDBOX',
  providerName: 'smtp-sandbox',
  configVersion: 'sandbox-v1',
  templateKey: 'reservation-confirmed',
  templateVersion: 'v1',
  status: 'SENT',
  statusText: 'SMTP 已接受',
  acceptanceStatus: 'ACCEPTED',
  downstreamStatus: 'NOT_OBSERVED',
  attemptCount: 1,
  nextRetryTime: null,
  failureCode: null,
  createTime: '2026-09-14T08:02:00',
  updateTime: '2026-09-14T08:02:30',
  acceptedAt: '2026-09-14T08:02:30',
  ...over
})

const overview = (over: Record<string, unknown> = {}) => ({
  batchCount: 1,
  audienceCount: 128,
  sentCount: 118,
  acceptedCount: 0,
  retryWaitingCount: 0,
  failedCount: 2,
  deliveredCount: 0,
  bouncedCount: 0,
  unreadCount: 40,
  readCount: 78,
  readRate: 0.61,
  failureReasonCounts: { PROVIDER_TIMEOUT: 2 },
  providerEnv: 'DEV',
  providerName: 'in-app',
  configVersion: 'dev-v1',
  channel: 'IN_APP',
  dataGaps: [],
  ...over
})

const experiment = (over: Record<string, unknown> = {}) => ({
  id: 5,
  experimentNo: 'NE20260914080000AB',
  name: '开放调整通知的阅读表达',
  status: 'RUNNING',
  targetMetric: 'READ_RATE',
  sampleScope: 'VENUE_RECENT_RESERVERS',
  variants: [
    { id: 51, variantCode: 'A', title: '周末场地维护安排', content: '周日 08:00—12:00 部分场地维护。', allocationPercent: 50 },
    { id: 52, variantCode: 'B', title: '周日早场暂停开放', content: '计划周日早场来打球的用户请先查看安排。', allocationPercent: 50 }
  ],
  ...over
})

const page = <T>(records: T[]) => ({ records, total: records.length, pageNum: 1, pageSize: 10 })

function allow(...keys: string[]): void {
  granted.clear()
  for (const key of keys) granted.add(key)
}

beforeEach(() => {
  vi.clearAllMocks()
  allow('venue-admin:view', 'system:view', 'notice:manage', 'notice:analytics')
  setActivePinia(createPinia())
  const venueAccess = useVenueAccessStore()
  venueAccess.venues = [{ id: 101, name: '北岸生活场馆', area: '', address: '', status: 'ACTIVE' }] as never
  venueAccess.activeVenueId = 101

  noticeApiMock.deliveryProviders.mockResolvedValue([inAppProvider, emailProvider])
  noticeApiMock.deliveryBatches.mockResolvedValue(page([batch()]))
  noticeApiMock.deliveryBatch.mockResolvedValue(batch({ batchId: 9, batchNo: 'BATCH-NEW' }))
  noticeApiMock.deliveryOverview.mockResolvedValue(overview())
  noticeApiMock.deliveryOutboxes.mockResolvedValue(page([inAppOutbox()]))
  noticeApiMock.deliveryAudiences.mockResolvedValue({
    records: [
      { audienceId: 61, batchId: 7, noticeId: 3, userId: 18, deliveryStatus: 'RETRY_WAITING', readStatus: 'UNREAD' },
      { audienceId: 62, batchId: 7, noticeId: 3, userId: 29, deliveryStatus: 'EXCLUDED', excludeReason: 'USER_NOT_ACTIVE' }
    ],
    total: 2,
    pageNum: 1,
    pageSize: 5
  })
  noticeApiMock.retryOutbox.mockResolvedValue(undefined)
  noticeApiMock.previewAudience.mockResolvedValue({
    targetScope: 'VENUE_RECENT_RESERVERS',
    audienceCount: 128,
    deliverableCount: 120,
    excludedCount: 8,
    excludeReasonCounts: { USER_NOT_ACTIVE: 5, USER_DELETED: 3 },
    samples: []
  })
  noticeApiMock.publish.mockResolvedValue({ id: 3, title: '标题', content: '正文', type: 'VENUE', status: 'PUBLISHED', deliveryBatchId: 9 })
  noticeApiMock.experiments.mockResolvedValue(page([experiment()]))
  noticeApiMock.experimentResults.mockResolvedValue({
    experimentId: 5,
    experimentNo: 'NE20260914080000AB',
    name: '开放调整通知的阅读表达',
    status: 'RUNNING',
    targetMetric: 'READ_RATE',
    sampleScope: 'VENUE_RECENT_RESERVERS',
    providerKey: 'dev-in-app',
    providerEnv: 'DEV',
    providerName: 'in-app',
    channel: 'IN_APP',
    displayName: '站内消息',
    configVersion: 'dev-v1',
    audienceCount: 120,
    sentCount: 118,
    failedCount: 2,
    readCount: 78,
    readRate: 0.65,
    variants: [
      { variantId: 51, variantCode: 'A', title: '周末场地维护安排', allocationPercent: 50, audienceCount: 60, sentCount: 58, failedCount: 2, readCount: 37, readRate: 0.6167 },
      { variantId: 52, variantCode: 'B', title: '周日早场暂停开放', allocationPercent: 50, audienceCount: 60, sentCount: 60, failedCount: 0, readCount: 41, readRate: 0.6833 }
    ]
  })
  noticeApiMock.createExperiment.mockResolvedValue(experiment({ id: 6, name: '新实验定义' }))

  const proto = (globalThis.HTMLDialogElement as unknown as { prototype: Record<string, () => void> }).prototype
  proto.showModal = proto.showModal ?? (() => {})
  proto.close = proto.close ?? (() => {})
})

async function mountView(path = '/venue-admin/notices'): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/venue-admin/notices', name: 'VenueAdminNotices', component: ShiguangNoticeJourneyView },
      { path: '/system-admin/notice-delivery', name: 'SystemNoticeDelivery', component: ShiguangNoticeJourneyView },
      { path: '/system-admin/notice-experiments', name: 'SystemNoticeExperiments', component: ShiguangNoticeJourneyView }
    ]
  })
  await router.push(path)
  await router.isReady()
  const host = document.createElement('div')
  document.body.appendChild(host)
  const wrapper = mount(ShiguangNoticeJourneyView, { global: { plugins: [router] }, attachTo: host })
  await flushPromises()
  await flushPromises()
  await flushPromises()
  return { wrapper, router }
}

async function fillDraft(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.find('[data-test="notice-title"]').setValue('周日场地维护安排')
  await wrapper.find('[data-test="notice-body"]').setValue('周日 08:00—12:00 部分场地暂停开放。')
  await flushPromises()
}

describe('ShiguangNoticeJourneyView 页头与入口', () => {
  it('三条旧路由各决定初始页签，页内 hash 优先', async () => {
    const venue = await mountView()
    expect(venue.wrapper.find('[data-test="tab-compose"]').attributes('aria-pressed')).toBe('true')
    venue.wrapper.unmount()

    const delivery = await mountView('/system-admin/notice-delivery')
    expect(delivery.wrapper.find('[data-test="tab-delivery"]').attributes('aria-pressed')).toBe('true')
    delivery.wrapper.unmount()

    const experiments = await mountView('/system-admin/notice-experiments#compose')
    expect(experiments.wrapper.find('[data-test="tab-compose"]').attributes('aria-pressed')).toBe('true')
    experiments.wrapper.unmount()
  })

  it('三页签标题与导语逐字复刻参考，且不出现「示例数据」胶囊', async () => {
    const { wrapper } = await mountView()
    expect(wrapper.find('.a-eyebrow').text()).toBe('公告与通知 / 发布前核对')
    expect(wrapper.find('h1').text()).toBe('让需要知道的人，读到清楚的消息。')
    expect(wrapper.find('.a-intro').text()).toContain('写清变化、核对范围，再发布公告。')
    expect(wrapper.find('.a-topline .a-pill').exists()).toBe(false)
    expect(wrapper.findAll('.a-tab').map((tab) => tab.text())).toEqual(['场馆公告', '投递与触达', '公告实验'])
    wrapper.unmount()
  })

  it('切换页签写入页内 hash 并改标题', async () => {
    const { wrapper, router } = await mountView()
    await wrapper.find('[data-test="tab-delivery"]').trigger('click')
    await flushPromises()
    await flushPromises()
    expect(router.currentRoute.value.hash).toBe('#delivery')
    expect(wrapper.find('h1').text()).toBe('消息发出之后，逐层核对结果。')
    expect(wrapper.find('.a-eyebrow').text()).toBe('公告与通知 / 平台触达证据')
    wrapper.unmount()
  })
})

describe('ShiguangNoticeJourneyView 场馆公告页签', () => {
  it('缺 notice:manage 时该页签只给分权说明，不发受众预览', async () => {
    allow('venue-admin:view', 'notice:analytics')
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="denied-compose"]').text()).toContain('当前仅有触达分析权限')
    expect(noticeApiMock.previewAudience).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="open-publish"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('无授权场馆时提示恢复授权而不是允许发布', async () => {
    const venueAccess = useVenueAccessStore()
    venueAccess.venues = [] as never
    venueAccess.activeVenueId = null
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="lock-compose"]').text()).toContain('没有可发布公告的场馆')
    expect(wrapper.find('[data-test="lock-compose"]').text()).toContain('受众核对与发布不会沿用此前范围')
    wrapper.unmount()
  })

  it('收件人阅读预览按参考给示例文案，输入后实时回显真实草稿', async () => {
    const { wrapper } = await mountView()
    // 参考的空表单不是空卡：V01 的阅读预览始终以示例标题与三段正文示人（B3-T70）
    expect(wrapper.find('[data-test="reading-title"]').text()).toBe('本周日羽毛球场照明维护，部分时段暂停开放')
    expect(wrapper.find('[data-test="reading-body"]').text()).toContain('为改善晚间使用体验')
    await fillDraft(wrapper)
    expect(wrapper.find('[data-test="reading-title"]').text()).toBe('周日场地维护安排')
    expect(wrapper.find('[data-test="reading-body"]').text()).toContain('部分场地暂停开放')
    expect(wrapper.find('[data-test="reading-body"]').text()).not.toContain('示例')
    // 清空后回到参考占位句，不留下上一次的草稿
    await wrapper.find('[data-test="notice-title"]').setValue('')
    await wrapper.find('[data-test="notice-body"]').setValue('')
    expect(wrapper.find('[data-test="reading-title"]').text()).toBe('公告标题')
    expect(wrapper.find('[data-test="reading-body"]').text()).toBe('正文会显示在这里。')
    wrapper.unmount()
  })

  it('未填正文时不请求受众预览，并给出参考提示与聚焦', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="notice-title"]').setValue('只有标题')
    await wrapper.find('[data-test="notice-body"]').setValue('')
    await wrapper.find('[data-test="open-preview"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="draft-error"]').text()).toBe('请填写公告标题和正文后再核对受众。')
    expect(noticeApiMock.previewAudience).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('受众预览取服务端真实三计数与排除分解，采用后才允许发布', async () => {
    const { wrapper } = await mountView()
    await fillDraft(wrapper)
    await wrapper.find('[data-test="open-preview"]').trigger('click')
    await flushPromises()
    expect(noticeApiMock.previewAudience).toHaveBeenCalledWith(
      { targetScope: 'VENUE_RECENT_RESERVERS', targetVenueId: 101 },
      expect.anything()
    )
    const counts = wrapper.findAll('[data-test="preview-counts"] div')
    expect(counts.map((cell) => cell.text())).toEqual(['128预计受众', '120可投递', '8已排除'])
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    const face = wrapper.findAll('[data-test="audience-summary"] .a-counts div')
    expect(face.map((cell) => cell.text())).toEqual(['128预计受众', '120可投递', '8已排除'])
    expect(wrapper.findAll('[data-test="exclude-row"]').map((row) => row.text()))
      .toEqual(['账号不可用 5 人', '账号已删除 3 人'])
    expect(wrapper.find('[data-test="open-publish"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('内容或场馆变化后预览失效，发布按钮重新禁用', async () => {
    const { wrapper } = await mountView()
    await fillDraft(wrapper)
    await wrapper.find('[data-test="open-preview"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    await wrapper.find('[data-test="notice-body"]').setValue('正文改了。')
    await flushPromises()
    expect(wrapper.find('[data-test="audience-pending"]').text()).toBe('内容已修改，请重新预览受众。')
    expect(wrapper.find('[data-test="open-publish"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('发布按真实合同提交类型、状态与人群，并只提交一次', async () => {
    const { wrapper } = await mountView()
    await fillDraft(wrapper)
    await wrapper.find('[data-test="open-preview"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    await wrapper.find('[data-test="open-publish"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="published-note"]').exists()).toBe(false)
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    await flushPromises()
    expect(noticeApiMock.publish).toHaveBeenCalledTimes(1)
    expect(noticeApiMock.publish.mock.calls[0][0]).toMatchObject({
      title: '周日场地维护安排',
      type: 'VENUE',
      status: 'PUBLISHED',
      targetScope: 'VENUE_RECENT_RESERVERS',
      targetVenueId: 101
    })
    expect(wrapper.find('[data-test="published-note"]').text()).toContain('投递仍在排队，发布成功不代表所有人已收到。')
    wrapper.unmount()
  })

  it('发布成功后可跳到真实批次并高亮', async () => {
    const { wrapper } = await mountView()
    await fillDraft(wrapper)
    await wrapper.find('[data-test="open-preview"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    await wrapper.find('[data-test="open-publish"]').trigger('click')
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    await flushPromises()
    await wrapper.find('[data-test="show-published"]').trigger('click')
    await flushPromises()
    await flushPromises()
    await flushPromises()
    expect(noticeApiMock.deliveryBatch).toHaveBeenCalledWith(9, expect.anything())
    expect(wrapper.find('[data-test="menu-batch-9"]').text()).toContain('BATCH-NEW')
    expect(wrapper.find('[data-test="menu-batch-9"]').attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })

  it('服务端发布原文留在弹层而不是整页提示', async () => {
    noticeApiMock.publish.mockRejectedValueOnce(new Error('场馆管理员只能发布场馆近期预约用户公告'))
    const { wrapper } = await mountView()
    await fillDraft(wrapper)
    await wrapper.find('[data-test="open-preview"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    await wrapper.find('[data-test="open-publish"]').trigger('click')
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('场馆管理员只能发布场馆近期预约用户公告')
    expect(wrapper.find('[data-test="published-note"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('只有平台侧才出现人群下拉与其从属 ID 字段', async () => {
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="notice-scope"]').exists()).toBe(true)
    allow('venue-admin:view', 'notice:manage')
    wrapper.unmount()

    const second = await mountView()
    expect(second.wrapper.find('[data-test="notice-scope"]').exists()).toBe(false)
    expect(second.wrapper.find('.a-compose .a-meta').text()).toBe('发布场馆：北岸生活场馆 · 场馆近期预约用户')
    second.wrapper.unmount()
  })

  it('会员等级人群按真实必填带上 memberLevelId', async () => {
    const { wrapper } = await mountView()
    await fillDraft(wrapper)
    await wrapper.find('[data-test="notice-scope"]').setValue('MEMBER_LEVEL')
    await wrapper.find('[data-test="notice-member-level"]').setValue('4')
    await flushPromises()
    await wrapper.find('[data-test="open-preview"]').trigger('click')
    await flushPromises()
    expect(noticeApiMock.previewAudience).toHaveBeenLastCalledWith(
      { targetScope: 'MEMBER_LEVEL', targetVenueId: 101, memberLevelId: 4 },
      expect.anything()
    )
    expect(wrapper.find('[data-test="audience-summary"]').text()).toContain('尚未核对受众')
    wrapper.unmount()
  })
})

describe('ShiguangNoticeJourneyView 投递与触达页签', () => {
  it('缺 notice:analytics 时不发任何触达请求', async () => {
    allow('venue-admin:view', 'notice:manage')
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    expect(wrapper.find('[data-test="denied-delivery"]').text()).toContain('未获得触达分析权限')
    expect(noticeApiMock.deliveryProviders).not.toHaveBeenCalled()
    expect(noticeApiMock.deliveryBatches).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('批次菜单由真实批次驱动，邮件渠道作为最后一条入口', async () => {
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    const entries = wrapper.findAll('.a-batch-menu button')
    // 菜单条目的两行来自模板里的 <br>；jsdom 未实现 innerText，只能按 textContent 断言
    expect(entries.map((entry) => entry.text())).toEqual([
      'BATCH202609140800场馆近期预约用户DEV / in-app · 08:00',
      '业务邮件发件箱平台 · 事务邮件SANDBOX / smtp-sandbox'
    ])
    expect(entries[0].find('small').html()).toContain('<br>')
    expect(entries[0].attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })

  it('站内证据链五层里客户端送达只能写未知', async () => {
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    const cells = wrapper.findAll('[data-test="evidence"] div')
    expect(cells.map((cell) => cell.text())).toEqual([
      '公告发布部分失败批次 BATCH202609140800',
      '进入队列128 人排除 8 人',
      '站内消息生成118 人终态失败 2 人',
      '客户端送达未知未证明实际展示',
      '已读回执78 人40 人尚未读'
    ])
    expect(wrapper.find('[data-test="evidence-note"]').text())
      .toBe('站内消息阅读率为 78 / 128 = 61.0%，分母为受众快照数（含排队、失败与排除项）。受众排队数不作为已送达人数。')
    wrapper.unmount()
  })

  it('切到邮件渠道后按邮件口径出证据与缺口，不把零值写成送达', async () => {
    noticeApiMock.deliveryOverview.mockResolvedValue(overview({
      providerEnv: 'SANDBOX',
      providerName: 'smtp-sandbox',
      configVersion: 'sandbox-v1',
      channel: 'EMAIL',
      batchCount: 0,
      audienceCount: 0,
      acceptedCount: 1,
      retryWaitingCount: 1,
      dataGaps: ['Mailpit 仅证明 SMTP 接受，不证明互联网送达或退信。']
    }))
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    await wrapper.find('[data-test="menu-email"]').trigger('click')
    await flushPromises()
    await flushPromises()
    const cells = wrapper.findAll('[data-test="evidence"] div')
    expect(cells.map((cell) => cell.text())).toEqual([
      '排队1 封进入发件箱',
      'SMTP 接受1 封不代表送达',
      '等待重试1 封smtp-sandbox',
      '下游送达未知缺少送达回执',
      '阅读不可用邮件无阅读数据'
    ])
    expect(wrapper.find('[data-test="evidence-note"]').text()).toContain('下游送达与阅读暂无证据')
    expect(wrapper.find('[data-test="open-audiences"]').exists()).toBe(false)
    expect(wrapper.find('.a-pill').text()).toBe('沙箱证据')
    wrapper.unmount()
  })

  it('发件箱表格：站内行不虚构用户标识，邮件行用服务端脱敏与状态原文', async () => {
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    expect(wrapper.find('[data-test="outbox-table"]').text()).toContain('收件人类型 USER')
    expect(wrapper.find('[data-test="outbox-table"]').text()).toContain('OB202609140801')
    expect(wrapper.find('[data-test="outbox-table"]').text()).toContain('2 次 · PROVIDER_TIMEOUT')
    wrapper.unmount()

    noticeApiMock.deliveryOutboxes.mockResolvedValue(page([emailRow()]))
    const mail = await mountView('/system-admin/notice-delivery')
    await mail.wrapper.find('[data-test="menu-email"]').trigger('click')
    await flushPromises()
    await flushPromises()
    expect(mail.wrapper.find('[data-test="outbox-table"]').text()).toContain('s***@example.test')
    expect(mail.wrapper.find('[data-test="outbox-table"]').text()).toContain('SMTP 已接受')
    mail.wrapper.unmount()
  })

  it('发件箱按批次过滤并可翻页', async () => {
    noticeApiMock.deliveryOutboxes.mockResolvedValue({
      records: [1, 2, 3, 4, 5].map((id) => inAppOutbox({ outboxId: id })),
      total: 7,
      pageNum: 1,
      pageSize: 5
    })
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    expect(noticeApiMock.deliveryOutboxes).toHaveBeenCalledWith(
      expect.objectContaining({ providerEnv: 'DEV', providerName: 'in-app', batchId: 7, pageNum: 1, pageSize: 5 }),
      expect.anything()
    )
    noticeApiMock.deliveryOutboxes.mockResolvedValue({ records: [inAppOutbox({ outboxId: 43 })], total: 7, pageNum: 2, pageSize: 5 })
    await wrapper.find('[data-test="outbox-next"]').trigger('click')
    await flushPromises()
    expect(noticeApiMock.deliveryOutboxes).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNum: 2 }),
      expect.anything()
    )
    expect(wrapper.text()).toContain('2 / 2 页 · 共 7 条')
    wrapper.unmount()
  })

  it('投递记录弹层只列真实可得事实并可重试', async () => {
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    await wrapper.find('[data-test="open-outbox-41"]').trigger('click')
    await flushPromises()
    const facts = wrapper.findAll('[data-test="outbox-facts"] li').map((row) => row.text())
    expect(facts).toEqual([
      '当前状态：等待重试',
      '已尝试：2 次 · PROVIDER_TIMEOUT',
      '进入发件箱：2026-09-14 08:01:00',
      '最近更新：2026-09-14 08:03:00',
      '下次重试：2026-09-14 08:05:00'
    ])
    await wrapper.find('[data-test="open-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-form"]').text()).toContain('保留先前的 2 次尝试记录')
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    await flushPromises()
    expect(noticeApiMock.retryOutbox).toHaveBeenCalledWith(41, expect.anything())
    wrapper.unmount()
  })

  it('仅有分析权限时可以查看但不能重试', async () => {
    allow('system:view', 'notice:analytics')
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    await wrapper.find('[data-test="open-outbox-41"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="open-retry"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="dialog-form"]').text()).toContain('当前可以查看，但缺少公告管理权限，不能重试。')
    wrapper.unmount()
  })

  it('受众快照弹层按真实分页读取批次受众与排除原因', async () => {
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    await wrapper.find('[data-test="open-audiences"]').trigger('click')
    await flushPromises()
    expect(noticeApiMock.deliveryAudiences).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ providerEnv: 'DEV', providerName: 'in-app', pageNum: 1, pageSize: 5 }),
      expect.anything()
    )
    expect(wrapper.find('[data-test="audience-table"]').text()).toContain('用户 #18')
    expect(wrapper.find('[data-test="audience-table"]').text()).toContain('已排除')
    expect(wrapper.find('[data-test="audience-table"]').text()).toContain('账号不可用')
    wrapper.unmount()
  })

  it('没有任何批次与邮件渠道时给真实空态', async () => {
    noticeApiMock.deliveryBatches.mockResolvedValue(page([]))
    noticeApiMock.deliveryProviders.mockResolvedValue([inAppProvider])
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    expect(wrapper.find('[data-test="empty-delivery"]').text()).toContain('还没有符合条件的投递批次或实验')
    wrapper.unmount()
  })

  it('读取渠道目录失败时进入可重试的错误态而不是空数据', async () => {
    noticeApiMock.deliveryProviders.mockRejectedValueOnce(new Error('服务暂不可用'))
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    expect(wrapper.find('[data-test="surface-error"]').text()).toContain('这不是空数据')
    noticeApiMock.deliveryProviders.mockResolvedValue([inAppProvider, emailProvider])
    await wrapper.find('[data-test="surface-reload"]').trigger('click')
    await flushPromises()
    await flushPromises()
    await flushPromises()
    expect(wrapper.find('[data-test="pane-delivery"] .a-delivery-layout').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('ShiguangNoticeJourneyView 公告实验页签', () => {
  it('实验与版本按服务端真实定义呈现，状态取真实值', async () => {
    const { wrapper } = await mountView('/system-admin/notice-experiments')
    expect(wrapper.find('[data-test="experiment-select"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="experiment-status"]').text()).toBe('运行中')
    expect(wrapper.findAll('[data-test="experiment-versions"] article')).toHaveLength(2)
    expect(wrapper.find('[data-test="version-A"]').text()).toContain('周末场地维护安排')
    expect(wrapper.find('[data-test="version-A"]').text()).toContain('50% 样本')
    wrapper.unmount()
  })

  it('阅读表现分母为受众快照且不写显著性结论', async () => {
    const { wrapper } = await mountView('/system-admin/notice-experiments')
    const rows = wrapper.findAll('[data-test="result-A"], [data-test="result-B"]')
    expect(rows).toHaveLength(2)
    expect(wrapper.find('[data-test="result-A"] .a-bar span').attributes('style')).toContain('width: 61.7%')
    expect(wrapper.text()).toContain('已读 37 / 受众 60，其余为未读；终态失败 2')
    expect(wrapper.text()).toContain('样本较小，阅读差异仅供观察。此处没有显著性结论，也不自动选定优胜版本。')
    wrapper.unmount()
  })

  it('邮件统计显示指标不适用而不是 0%', async () => {
    const { wrapper } = await mountView('/system-admin/notice-experiments')
    await wrapper.find('[data-test="stats-provider"]').setValue('SANDBOX::smtp-sandbox')
    await flushPromises()
    await flushPromises()
    expect(wrapper.find('[data-test="experiment-results"]').text()).toContain('当前选择为邮件渠道，站内阅读率不可用')
    expect(wrapper.find('[data-test="result-A"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('只有定义、两个变体受众数为 0 时说明定义不会自动投递', async () => {
    noticeApiMock.experimentResults.mockResolvedValue({
      experimentId: 5,
      experimentNo: 'NE202609140809122A9A177D',
      name: '开放调整通知的阅读表达',
      status: 'RUNNING',
      targetMetric: 'READ_RATE',
      sampleScope: 'VENUE_RECENT_RESERVERS',
      providerKey: 'dev-in-app',
      providerEnv: 'DEV',
      providerName: 'IN_APP_MESSAGE',
      channel: 'IN_APP',
      displayName: '站内消息',
      configVersion: 'v1',
      audienceCount: 0,
      sentCount: 0,
      failedCount: 0,
      readCount: 0,
      readRate: 0,
      variants: [
        { variantId: 51, variantCode: 'A', title: '周末场地维护安排', allocationPercent: 50, audienceCount: 0, sentCount: 0, failedCount: 0, readCount: 0, readRate: 0 },
        { variantId: 52, variantCode: 'B', title: '周日早场暂停开放', allocationPercent: 50, audienceCount: 0, sentCount: 0, failedCount: 0, readCount: 0, readRate: 0 }
      ]
    })
    const { wrapper } = await mountView('/system-admin/notice-experiments')
    expect(wrapper.find('[data-test="result-empty"]').text()).toContain('定义已保存，尚无投递结果')
    expect(wrapper.find('[data-test="result-empty"]').text()).toContain('创建定义不会自动发出公告')
    expect(wrapper.find('[data-test="result-A"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('比例之和不为 100 时不发请求并给出参考提示', async () => {
    const { wrapper } = await mountView('/system-admin/notice-experiments')
    await wrapper.find('[data-test="open-experiment"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="field-experiment-name"]').setValue('新的对照定义')
    await wrapper.find('[data-test="field-title-a"]').setValue('甲')
    await wrapper.find('[data-test="field-content-a"]').setValue('甲正文')
    await wrapper.find('[data-test="field-title-b"]').setValue('乙')
    await wrapper.find('[data-test="field-content-b"]').setValue('乙正文')
    await wrapper.find('[data-test="field-allocation-a"]').setValue('60')
    await wrapper.find('[data-test="field-allocation-b"]').setValue('30')
    await wrapper.find('[data-test="dialog-form"]').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('两个版本的比例之和需要为 100%。')
    expect(noticeApiMock.createExperiment).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('实验定义按服务端必填集提交并选中新定义', async () => {
    const { wrapper } = await mountView('/system-admin/notice-experiments')
    await wrapper.find('[data-test="open-experiment"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="field-experiment-name"]').setValue('新的对照定义')
    await wrapper.find('[data-test="field-title-a"]').setValue('甲')
    await wrapper.find('[data-test="field-content-a"]').setValue('甲正文')
    await wrapper.find('[data-test="field-title-b"]').setValue('乙')
    await wrapper.find('[data-test="field-content-b"]').setValue('乙正文')
    await wrapper.find('[data-test="dialog-form"]').trigger('submit')
    await flushPromises()
    await flushPromises()
    await flushPromises()
    expect(noticeApiMock.createExperiment).toHaveBeenCalledWith(
      expect.objectContaining({
        name: '新的对照定义',
        targetMetric: 'READ_RATE',
        sampleScope: 'VENUE_RECENT_RESERVERS',
        variants: [
          expect.objectContaining({ variantCode: 'A', title: '甲', allocationPercent: 50 }),
          expect.objectContaining({ variantCode: 'B', title: '乙', allocationPercent: 50 })
        ]
      }),
      expect.anything()
    )
    expect(noticeApiMock.experiments).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('服务端校验原文留在弹层', async () => {
    noticeApiMock.createExperiment.mockRejectedValueOnce(new Error('实验版本流量占比合计必须为 100'))
    const { wrapper } = await mountView('/system-admin/notice-experiments')
    await wrapper.find('[data-test="open-experiment"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="field-experiment-name"]').setValue('新的对照定义')
    await wrapper.find('[data-test="field-title-a"]').setValue('甲')
    await wrapper.find('[data-test="field-content-a"]').setValue('甲正文')
    await wrapper.find('[data-test="field-title-b"]').setValue('乙')
    await wrapper.find('[data-test="field-content-b"]').setValue('乙正文')
    await wrapper.find('[data-test="dialog-form"]').trigger('submit')
    await flushPromises()
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('实验版本流量占比合计必须为 100')
    wrapper.unmount()
  })
})

describe('ShiguangNoticeJourneyView 面内合同', () => {
  it('?batch 深链会定位该批次并高亮菜单', async () => {
    const { wrapper } = await mountView('/system-admin/notice-delivery?batch=9')
    expect(noticeApiMock.deliveryBatch).toHaveBeenCalledWith(9, expect.anything())
    expect(wrapper.find('[data-test="menu-batch-9"]').attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })

  it('面内所有读取都带隔离配置，失败不整页跳转', async () => {
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    expect(noticeApiMock.deliveryProviders).toHaveBeenCalledWith(expect.objectContaining({ skipGlobalErrorRedirect: true }))
    expect(noticeApiMock.deliveryBatches).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ skipGlobalErrorToast: true }))
    wrapper.unmount()
  })

  it('弹层取消后焦点回到触发控件', async () => {
    const { wrapper } = await mountView('/system-admin/notice-delivery')
    await wrapper.find('[data-test="open-audiences"]').trigger('click')
    await flushPromises()
    wrapper.find('dialog').element.dispatchEvent(new Event('close'))
    await flushPromises()
    const live = wrapper.find('[data-test="open-audiences"]').element
    expect(document.activeElement).toBe(live)
    wrapper.unmount()
  })

  it('受众预览经 await 后仍能记住触发控件并回焦', async () => {
    const { wrapper } = await mountView()
    const trigger = wrapper.find('[data-test="open-preview"]').element as HTMLElement
    // 真实点击事件在 await 之后 currentTarget 已被清空，实现必须同步取一次触发元素
    trigger.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(noticeApiMock.previewAudience).toHaveBeenCalled()
    // 这里断言实现确实记住了触发控件：即时 DOM 里已经有 dialog 子树的取消按钮，
    // jsdom 不像 Chrome 那样在 showModal() 后自动把焦点交给弹层首控件，所以焦点结果由恢复链决定。
    const dialogCancel = wrapper.find('[data-test="dialog-cancel"]').element as HTMLElement
    expect(document.activeElement).not.toBe(dialogCancel)
    wrapper.find('dialog').element.dispatchEvent(new Event('close'))
    await flushPromises()
    const live = wrapper.find('[data-test="open-preview"]').element
    expect(document.activeElement).toBe(live)
    wrapper.unmount()
  })

  it('触发控件在重渲染后不可聚焦时就近回焦而不是掉到 body', async () => {
    const { wrapper } = await mountView()
    await fillDraft(wrapper)
    await wrapper.find('[data-test="open-preview"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    // 核对通过后「确认发布」解禁，以它作为触发元素打开发布弹层
    const publish = wrapper.find('[data-test="open-publish"]').element as HTMLElement
    await wrapper.find('[data-test="open-publish"]').trigger('click')
    await flushPromises()
    noticeApiMock.publish.mockResolvedValue({ id: 1, deliveryBatchId: 9 })
    await wrapper.find('[data-test="dialog-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    // 发布成功后「确认发布」被置为禁用：焦点必须留在面内可聚焦控件上，而不是 body
    expect((publish as HTMLButtonElement).disabled).toBe(true)
    expect(document.activeElement).not.toBe(document.body)
    expect((document.activeElement as HTMLElement).closest('.sga-notice')).not.toBeNull()
    wrapper.unmount()
  })
})
