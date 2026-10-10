import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const apiMock = vi.hoisted(() => ({
  slots: vi.fn(),
  candidates: vi.fn(),
  previewSource: vi.fn(),
  createCandidate: vi.fn(),
  publish: vi.fn(),
  pause: vi.fn(),
  retire: vi.fn(),
  decisions: vi.fn(),
  events: vi.fn(),
  decisionEvents: vi.fn()
}))
const authState = vi.hoisted(() => ({
  permissions: new Set(['content-admin:view', 'placement:manage', 'placement:publish', 'placement:audit:view'])
}))

vi.mock('@/api/placement', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/placement')>()
  return { ...actual, placementApi: { ...actual.placementApi, admin: apiMock } }
})
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: null,
    hasPermission: (permission: string) => authState.permissions.has(permission)
  })
}))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

import ShiguangPlacementView from './ShiguangPlacementView.vue'

const slotRow = { id: 3, slotCode: 'HOME_PRIMARY', slotName: '首页自营宣传位', status: 'ACTIVE' }
const candidateRow = {
  id: 301,
  slotId: 3,
  candidateNo: 'OWN-301',
  sourceType: 'GLOBAL_NOTICE',
  sourceId: 91,
  sourceVersion: 'notice-v3',
  effectiveFrom: '2026-09-12T08:00',
  effectiveUntil: '2026-09-30T22:00',
  audienceType: 'ALL_AUTHENTICATED',
  audienceLevelCode: null,
  priority: 20,
  frequencyCapPerUser: 2,
  frequencyCapWindowHours: 24,
  ruleVersion: 'placement-r1',
  status: 'PUBLISHED',
  publishedAt: '2026-09-12T08:00'
}
const draftRow = { ...candidateRow, id: 302, candidateNo: 'OWN-302', sourceType: 'COMMUNITY_ACTIVITY', sourceId: 93, sourceVersion: 'activity-v5', status: 'DRAFT' }
const previewRow = {
  sourceType: 'GLOBAL_NOTICE',
  sourceId: 91,
  sourceVersion: 'notice-v3',
  title: '北岸生活场馆秋季开放安排',
  summary: '阅读、运动与小组活动恢复常规开放，请在预约前核对各空间营业时间。',
  imageUrl: null,
  deepLink: '/venues',
  sourceEffectiveFrom: null,
  sourceEffectiveUntil: null
}
const decisionRow = {
  id: 401,
  decisionNo: 'DEC-401',
  userId: 8,
  slotId: 3,
  candidateVersionId: 301,
  status: 'ISSUED',
  expiresAt: '2026-09-12T09:15',
  consumedAt: null,
  createTime: '2026-09-12T09:00',
  sourceSnapshot: { sourceType: 'GLOBAL_NOTICE', sourceId: 91, sourceVersion: 'notice-v3', title: '北岸生活场馆秋季开放安排', summary: null, imageUrl: null, deepLink: '/venues' }
}
const eventRow = {
  id: 601,
  eventNo: 'EV-601',
  eventType: 'CLIENT_VIEWABLE',
  decisionId: 401,
  userId: 8,
  slotId: 3,
  candidateVersionId: 301,
  resultStatus: 'ACCEPTED',
  rejectionCode: null,
  visibleRatio: 0.6,
  visibleDurationMs: 1200,
  occurredAt: '2026-09-12T09:01'
}

const mountView = () => mount(ShiguangPlacementView)

beforeEach(() => {
  vi.clearAllMocks()
  const dialogProto = HTMLDialogElement.prototype as unknown as Record<string, unknown>
  if (typeof dialogProto.showModal !== 'function') {
    dialogProto.showModal = function mockShowModal(this: HTMLDialogElement) { this.setAttribute('open', '') }
  }
  if (typeof dialogProto.close !== 'function') {
    dialogProto.close = function mockClose(this: HTMLDialogElement) { this.removeAttribute('open') }
  }
  authState.permissions = new Set(['content-admin:view', 'placement:manage', 'placement:publish', 'placement:audit:view'])
  apiMock.slots.mockResolvedValue([slotRow])
  apiMock.candidates.mockResolvedValue([candidateRow, draftRow])
  apiMock.previewSource.mockResolvedValue(previewRow)
  apiMock.decisions.mockResolvedValue([decisionRow])
  apiMock.events.mockResolvedValue([eventRow])
})

describe('自营宣传面（B5-G2/G3/G4 一比一重建）', () => {
  it('渲染候选目录、来源证明与候选元数据（canPreview 读预览）', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.slots).toHaveBeenCalled()
    expect(apiMock.candidates).toHaveBeenCalledWith(3, 100)
    expect(apiMock.previewSource).toHaveBeenCalledWith('GLOBAL_NOTICE', 91)
    const text = wrapper.text()
    expect(text).toContain('首页自营宣传位')
    expect(text).toContain('HOME_PRIMARY · 引用已有全局公告、价格活动与社区活动')
    expect(text).toContain('北岸生活场馆秋季开放安排')
    expect(text).toContain('站内目标：/venues · 来源读取版本 notice-v3')
    expect(text).toContain('受众条件')
    expect(text).toContain('所有已登录用户')
    expect(text).toContain('每人 24 小时内 2 次')
    expect(text).toContain('候选发布后也不保证每次展示。决策仍核对来源、有效期、受众与频次；已有候选不直接改写，新的条件通过新候选承接。')
  })

  it('状态门禁按钮随状态变化（B5-H16 同款语义）', async () => {
    const wrapper = mountView()
    await flushPromises()
    // PUBLISHED：暂停 + 下线可见，发布不可见
    expect(wrapper.find('[data-test="pl-pause"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="pl-retire"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="pl-publish"]').exists()).toBe(false)
  })

  it('暂停后回到 DRAFT 门禁（发布可见）', async () => {
    apiMock.pause.mockResolvedValue({ ...candidateRow, status: 'PAUSED' })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="pl-pause"]').trigger('click')
    await flushPromises()
    expect(apiMock.pause).toHaveBeenCalledWith(301)
    expect(wrapper.find('[data-test="pl-publish"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="pl-pause"]').exists()).toBe(false)
  })

  it('审计者不调用来源预览且标题降级（B5-G2/G3）', async () => {
    authState.permissions = new Set(['content-admin:view', 'placement:audit:view'])
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.previewSource).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前仅有审计权限，不调用来源预览；候选引用元数据仍可核对。')
    expect(wrapper.text()).toContain('全局公告 91')
    expect(wrapper.find('[data-test="pl-new-candidate"]').exists()).toBe(false)
  })

  it('来源不可用时降级为类型+编号并提示不能以旧摘要代替（B5-G3）', async () => {
    apiMock.previewSource.mockRejectedValue(new Error('not found'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('当前来源不可用，不能以旧摘要代替最新来源检查。')
    expect(wrapper.text()).toContain('全局公告 91')
  })

  it('新建候选弹层校验结束时间与会员等级（B5-G4）', async () => {
    // createCandidate 必须返回创建结果：真实合同是新建候选进入目录并成为选中项；
    // 缺少 mockResolvedValue 会让 undefined 进入 candidates，渲染 `:key="candidate.id"` 时抛错。
    apiMock.createCandidate.mockResolvedValue({
      ...draftRow, id: 303, candidateNo: 'OWN-303', sourceType: 'GLOBAL_NOTICE', sourceId: 92, status: 'DRAFT'
    })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="pl-new-candidate"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-test="pl-source-id"]').setValue(92)
    await wrapper.find('[data-test="pl-from"]').setValue('2026-09-20T08:00')
    await wrapper.find('[data-test="pl-until"]').setValue('2026-09-13T08:00')
    await wrapper.find('[data-test="pl-candidate-submit"]').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('结束时间必须晚于开始时间。')
    expect(apiMock.createCandidate).not.toHaveBeenCalled()

    await wrapper.find('[data-test="pl-until"]').setValue('2026-09-30T22:00')
    await wrapper.find('[data-test="pl-audience"]').setValue('MEMBER_LEVEL')
    await wrapper.find('[data-test="pl-candidate-submit"]').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('指定会员等级时必须填写等级代码。')

    await wrapper.find('[data-test="pl-level"]').setValue('SILVER')
    await wrapper.find('[data-test="pl-candidate-submit"]').trigger('submit')
    await flushPromises()
    expect(apiMock.createCandidate).toHaveBeenCalledWith(expect.objectContaining({
      slotId: 3,
      sourceType: 'GLOBAL_NOTICE',
      sourceId: 92,
      audienceType: 'MEMBER_LEVEL',
      audienceLevelCode: 'SILVER'
    }))
  })

  it('审计子视图展示决策表与事件表（仅 audit 叶可达）', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="pl-open-audit"]').trigger('click')
    await flushPromises()

    expect(apiMock.decisions).toHaveBeenCalledWith(3, 50)
    expect(apiMock.events).toHaveBeenCalledWith(3, 50)
    expect(wrapper.text()).toContain('自营决策与事件')
    expect(wrapper.text()).toContain('DEC-401 / OWN-301')
    expect(wrapper.text()).toContain('已签发')
    expect(wrapper.text()).toContain('公告91 · notice-v3')
    expect(wrapper.text()).toContain('已接受')
    expect(wrapper.text()).toContain('自营事件接收与商业归因独立，不能混成同一份曝光或转化统计。')
  })

  it('无任何自营叶子时显示无权状态', async () => {
    authState.permissions = new Set(['content-admin:view'])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="pl-denied"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('当前没有自营宣传权限')
  })
})
