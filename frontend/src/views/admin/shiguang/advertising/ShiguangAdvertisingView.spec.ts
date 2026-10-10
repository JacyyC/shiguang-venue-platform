import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const apiMock = vi.hoisted(() => ({
  overview: vi.fn(),
  advertisers: vi.fn(),
  createAdvertiser: vi.fn(),
  suspendAdvertiser: vi.fn(),
  retireAdvertiser: vi.fn(),
  campaigns: vi.fn(),
  createCampaign: vi.fn(),
  submitCampaignReview: vi.fn(),
  approveCampaign: vi.fn(),
  rejectCampaign: vi.fn(),
  publishCampaign: vi.fn(),
  pauseCampaign: vi.fn(),
  retireCampaign: vi.fn(),
  creatives: vi.fn(),
  audits: vi.fn(),
  createCreative: vi.fn(),
  submitCreativeReview: vi.fn(),
  approveCreative: vi.fn(),
  rejectCreative: vi.fn(),
  publishCreative: vi.fn(),
  pauseCreative: vi.fn(),
  retireCreative: vi.fn()
}))
const venueApiMock = vi.hoisted(() => ({
  list: vi.fn()
}))
const authState = vi.hoisted(() => ({
  permissions: new Set(['content-admin:view', 'advertising:manage', 'advertising:review', 'advertising:publish', 'advertising:audit:view'])
}))

vi.mock('@/api/advertising', () => ({ advertisingApi: { admin: apiMock } }))
vi.mock('@/api/venue', () => ({ venueApi: venueApiMock }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: null,
    hasPermission: (permission: string) => authState.permissions.has(permission)
  })
}))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

import ShiguangAdvertisingView from './ShiguangAdvertisingView.vue'

const advertiserRow = {
  id: 51, advertiserNo: 'AD-51', advertiserName: '北岸生活运营（走查）', status: 'ACTIVE',
  createTime: '2026-09-14T08:00', updateTime: '2026-09-14T08:00'
}
const campaignRow = {
  id: 61, campaignNo: 'CP-61', advertiserId: 51, venueId: 1,
  campaignName: '秋季阅读与运动介绍', effectiveFrom: '2026-09-12T08:00', effectiveUntil: '2026-09-30T22:00',
  status: 'APPROVED', submittedAt: null, reviewedAt: null, publishedAt: null, pausedAt: null, retiredAt: null,
  createTime: '2026-09-14T08:00', updateTime: '2026-09-14T08:00'
}
const creativeRow = {
  id: 71, creativeNo: 'CR-71', campaignId: 61, versionNo: 1,
  headline: '下班之后，把一小时留给自己', bodyText: '阅读、练球或参加一场小组交流。',
  callToAction: 'VIEW', targetType: 'VENUE_DETAIL', targetId: 1, status: 'DRAFT',
  submittedAt: null, reviewedAt: null, publishedAt: null, pausedAt: null, retiredAt: null,
  createTime: '2026-09-14T08:00', updateTime: '2026-09-14T08:00'
}
const auditRow = {
  id: 1, auditNo: 'ADA-1', entityType: 'CAMPAIGN', entityId: 61, entityNo: 'CP-61',
  venueId: null, relatedEntityType: null, relatedEntityId: null,
  action: '拒绝', sourceType: null, reasonCode: 'CREATIVE_QUALITY',
  beforeStatus: 'PENDING_REVIEW', afterStatus: 'REJECTED', resultStatus: 'SUCCESS',
  createTime: '2026-09-14T09:00'
}

const mountView = () => mount(ShiguangAdvertisingView)

beforeEach(() => {
  vi.clearAllMocks()
  const dialogProto = HTMLDialogElement.prototype as unknown as Record<string, unknown>
  if (typeof dialogProto.showModal !== 'function') {
    dialogProto.showModal = function mockShowModal(this: HTMLDialogElement) { this.setAttribute('open', '') }
  }
  if (typeof dialogProto.close !== 'function') {
    dialogProto.close = function mockClose(this: HTMLDialogElement) { this.removeAttribute('open') }
  }
  authState.permissions = new Set(['content-admin:view', 'advertising:manage', 'advertising:review', 'advertising:publish', 'advertising:audit:view'])
  venueApiMock.list.mockResolvedValue({ records: [{ id: 1, name: '北岸生活场馆' }, { id: 2, name: '东园运动中心' }], total: 2, pageNum: 1, pageSize: 50 })
  apiMock.advertisers.mockResolvedValue([advertiserRow])
  apiMock.campaigns.mockResolvedValue([campaignRow])
  apiMock.creatives.mockResolvedValue([creativeRow])
  apiMock.audits.mockResolvedValue([auditRow])
})

describe('商业广告面（B5-G5…G9 一比一重建）', () => {
  it('渲染广告主选择、计划卡片与计划详情三段', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.advertisers).toHaveBeenCalledWith(100)
    expect(apiMock.campaigns).toHaveBeenCalledWith({ limit: 200 })
    expect(apiMock.creatives).toHaveBeenCalledWith(61, 50)
    const text = wrapper.text()
    expect(text).toContain('先选择广告主')
    expect(text).toContain('秋季阅读与运动介绍')
    expect(text).toContain('草稿 → 待审核 → 批准 / 拒绝 → 发布 → 暂停 / 下线')
    expect(text).toContain('创意内容按版本新增，不在发布后原地改稿')
    expect(text).toContain('本页为隔离生命周期预览。计划与创意的发布状态各自独立，实际商业决策还需双开关、用户偏好与有效条件共同满足。')
  })

  it('生命周期按钮随状态与权限出现（APPROVED：发布/下线）', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="ad-plan-publish"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="ad-plan-retire"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="ad-plan-approve"]').exists()).toBe(false)
  })

  it('计划发布不级联创意（创意保持自身状态）', async () => {
    apiMock.publishCampaign.mockResolvedValue({ ...campaignRow, status: 'PUBLISHED' })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="ad-plan-publish"]').trigger('click')
    await flushPromises()
    expect(apiMock.publishCampaign).toHaveBeenCalledWith(61)
    expect(apiMock.publishCreative).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('创意发布不级联所属计划；计划与创意分别核对。')
  })

  it('拒绝弹层要求原因代码与说明双必填（B5-G8）', async () => {
    const wrapper = mountView()
    await flushPromises()
    // 计划先到 PENDING_REVIEW 才有拒绝入口
    apiMock.campaigns.mockResolvedValue([{ ...campaignRow, status: 'PENDING_REVIEW' }])
    const pendingView = mountView()
    await flushPromises()
    await pendingView.find('[data-test="ad-plan-reject"]').trigger('click')
    await flushPromises()

    await pendingView.find('[data-test="ad-dialog-submit"]').trigger('submit')
    await flushPromises()
    expect(pendingView.text()).toContain('拒绝原因代码与说明都必须填写。')
    expect(apiMock.rejectCampaign).not.toHaveBeenCalled()

    await pendingView.find('[data-test="ad-reject-code"]').setValue('CREATIVE_QUALITY')
    await pendingView.find('[data-test="ad-reject-text"]').setValue('文案与场馆事实不符，请核对后重新提交。')
    await pendingView.find('[data-test="ad-dialog-submit"]').trigger('submit')
    await flushPromises()
    expect(apiMock.rejectCampaign).toHaveBeenCalledWith(61, {
      reasonCode: 'CREATIVE_QUALITY',
      reasonText: '文案与场馆事实不符，请核对后重新提交。'
    })
  })

  it('审计子视图以原因代码+状态变迁呈现（不含说明原文，B5-G5/G9）', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="ad-open-audit"]').trigger('click')
    await flushPromises()

    expect(apiMock.audits).toHaveBeenCalledWith({ limit: 100 })
    const text = wrapper.text()
    expect(text).toContain('商业生命周期审计')
    expect(text).toContain('计划CP-61 · 拒绝')
    expect(text).toContain('待审核 → 已拒绝')
    expect(text).toContain('原因代码 CREATIVE_QUALITY')
  })

  it('仅审计权限时写按钮不可见', async () => {
    authState.permissions = new Set(['content-admin:view', 'advertising:audit:view'])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="ad-new-advertiser"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="ad-open-audit"]').exists()).toBe(true)
  })
})
