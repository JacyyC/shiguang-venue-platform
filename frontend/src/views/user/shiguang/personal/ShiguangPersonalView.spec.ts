import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { useAppStore } from '@/stores/app'

const authApiMock = vi.hoisted(() => ({ me: vi.fn(), updateProfile: vi.fn(), changePassword: vi.fn() }))
const noticeApiMock = vi.hoisted(() => ({
  messages: vi.fn(), unreadCount: vi.fn(), notificationPreferences: vi.fn(),
  updateNotificationPreferences: vi.fn(), markOneRead: vi.fn(), markRead: vi.fn()
}))
const feedbackApiMock = vi.hoisted(() => ({
  myComplaints: vi.fn(), myReviews: vi.fn(), myCases: vi.fn(), myCaseEvents: vi.fn(),
  submitComplaint: vi.fn(), submitReview: vi.fn()
}))
const funnelApiMock = vi.hoisted(() => ({ myFavorites: vi.fn(), favoriteVenue: vi.fn(), cancelFavorite: vi.fn() }))
const reservationApiMock = vi.hoisted(() => ({ my: vi.fn() }))
const venueApiMock = vi.hoisted(() => ({ detail: vi.fn(), spaces: vi.fn() }))
const advertisingApiMock = vi.hoisted(() => ({ preferences: vi.fn(), updatePreferences: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
const routeMock = vi.hoisted(() => ({ hash: '' as string, name: 'MeOverview', query: {} as Record<string, unknown>, params: {} }))
const menuPermissions = vi.hoisted(() => ({ value: new Set<string>() }))

vi.mock('@/api/auth', () => ({ authApi: authApiMock }))
vi.mock('@/api/notice', () => ({ noticeApi: noticeApiMock }))
vi.mock('@/api/feedback', () => ({ feedbackApi: feedbackApiMock }))
vi.mock('@/api/funnel', () => ({ funnelApi: funnelApiMock }))
vi.mock('@/api/reservation', () => ({ reservationApi: reservationApiMock }))
vi.mock('@/api/venue', () => ({ venueApi: venueApiMock }))
vi.mock('@/api/advertising', () => ({ advertisingApi: advertisingApiMock }))
vi.mock('@/stores/menu', () => ({ useMenuStore: () => ({ hasPermission: (key: string) => menuPermissions.value.has(key) }) }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock }))

import ShiguangPersonalView from './ShiguangPersonalView.vue'

HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) { this.open = true })

const stubs = {
  'router-link': { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>' }
}

const pageOf = <T,>(records: T[]) => ({ records, total: records.length, pageNum: 1, pageSize: 100 })

function prime(): void {
  menuPermissions.value = new Set(['user:view', 'reservation:my', 'funnel:favorite:me'])
  authApiMock.me.mockResolvedValue({ nickname: '北岸小林', phone: '13800000018', email: 'lin@example.test', avatar: '' })
  noticeApiMock.messages.mockResolvedValue(pageOf([
    { id: 401, type: 'RESERVATION', title: '周日羽毛球场预约已确认', content: '北岸生活场馆 · 羽毛球 2 号场', businessId: 12, readFlag: 0, createTime: '2026-09-13T09:40:00' },
    { id: 402, type: 'REFUND', title: '退款申请已提交', content: '订单 O-0912-074', businessId: 31, readFlag: 1, createTime: '2026-09-13T09:25:00' }
  ]))
  noticeApiMock.unreadCount.mockResolvedValue(1)
  noticeApiMock.notificationPreferences.mockResolvedValue({ transactionalEmailEnabled: false, maskedEmail: 'l***@example.test' })
  noticeApiMock.updateNotificationPreferences.mockResolvedValue({ transactionalEmailEnabled: true, maskedEmail: 'l***@example.test' })
  advertisingApiMock.preferences.mockResolvedValue({ commercialEnabled: false, measurementEnabled: false, policyVersion: 'v1', changedAt: null })
  advertisingApiMock.updatePreferences.mockResolvedValue({ commercialEnabled: true, measurementEnabled: false, policyVersion: 'v1', changedAt: null })
  funnelApiMock.myFavorites.mockResolvedValue([
    { id: 1, venueId: 1, status: 'ACTIVE' },
    { id: 2, venueId: 2, status: 'ACTIVE' }
  ])
  venueApiMock.detail.mockImplementation(async (id: number) => ({
    id, name: id === 1 ? '北岸生活场馆' : '东园运动中心', area: '高新区',
    address: '地址', openTime: '09:00', closeTime: '22:00', description: '介绍', status: 'ACTIVE'
  }))
  venueApiMock.spaces.mockResolvedValue([{ id: 73, venueId: 1, name: '羽毛球 2 号场', type: 'SPORTS', pricePerHour: 80 }])
  feedbackApiMock.myComplaints.mockResolvedValue(pageOf([
    { id: 18, venueId: 1, reservationId: 7, complaintType: 'EQUIPMENT', severity: 'HIGH', title: '预约的羽毛球场照明闪烁', contentSanitized: '灯光问题', status: 'OPEN', submittedAt: '2026-09-13T09:18:00' }
  ]))
  feedbackApiMock.myReviews.mockResolvedValue(pageOf([
    { id: 19, venueId: 1, reservationId: 3, overallRating: 4, venueRating: 5, spaceRating: null, serviceRating: 3, tagsJson: '["空间舒适","灯光柔和"]', contentSanitized: '空间安静', status: 'PENDING_REVIEW', submittedAt: '2026-09-12T17:20:00' }
  ]))
  feedbackApiMock.myCases.mockResolvedValue(pageOf([
    { id: 9, complaintId: 18, status: 'OPEN', caseNo: 'CS-0912-018' }
  ]))
  feedbackApiMock.myCaseEvents.mockResolvedValue([
    { id: 1, eventType: 'CUSTOMER_SERVICE_CASE_CREATED', noteSanitized: '问题进入处理队列', occurredAt: '2026-09-13T09:18:00' }
  ])
  feedbackApiMock.submitComplaint.mockResolvedValue({ id: 20 })
  reservationApiMock.my.mockResolvedValue(pageOf([
    { id: 12, venueId: 1, spaceId: 73, orderId: 3, status: 'PAID', startTime: '2026-09-13T19:00:00', endTime: '2026-09-13T20:00:00' },
    { id: 7, venueId: 1, spaceId: 73, orderId: 5, status: 'COMPLETED', startTime: '2026-09-11T19:00:00', endTime: '2026-09-11T20:00:00' }
  ]))
}

async function mountView() {
  const wrapper = mount(ShiguangPersonalView, { global: { stubs, plugins: [createPinia()] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('ShiguangPersonalView', () => {
  beforeEach(() => {
    // B3-T57：夹具日期与「今天/昨天」相对文案必须落在同一个固定系统时刻上，
    // 否则跨日后「今天 09:40」会渲染成「昨天 09:40」而整组用例漂移。
    vi.useFakeTimers({ now: new Date('2026-09-13T09:45:00+08:00'), shouldAdvanceTime: true })
    vi.clearAllMocks()
    routeMock.hash = ''
    routeMock.name = 'MeOverview'
    routeMock.query = {}
    prime()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the overview with masked contacts, unread inset and pending feedback card', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('你好，北岸小林。')
    expect(wrapper.text()).toContain('138****0018 · l***@example.test')
    expect(wrapper.text()).toContain('1 条未读')
    expect(wrapper.text()).toContain('周日羽毛球场预约已确认')
    expect(wrapper.text()).toContain('照明闪烁，继续查看处理记录。')
    expect(wrapper.text()).toContain('下一次到场 · 北岸生活场馆')
    wrapper.unmount()
  })

  it('maps message types to pills and real targets and keeps refund businessId out of deep links', async () => {
    routeMock.hash = '#messages'
    const wrapper = await mountView()
    const text = wrapper.text()
    expect(text).toContain('需要知道的变化，都留在这里。')
    expect(text).toContain('预约')
    expect(text).toContain('退款')
    const buttons = wrapper.findAll('button').map(b => b.text())
    expect(buttons).toContain('查看预约 →')
    expect(buttons).toContain('查看退款 →')
    expect(buttons.join('|')).not.toContain('#booking-31')
    wrapper.unmount()
  })

  it('marks one message read and refreshes the unread count locally', async () => {
    routeMock.hash = '#messages'
    noticeApiMock.markOneRead.mockResolvedValue(undefined)
    const wrapper = await mountView()
    await wrapper.find('[data-test="read-401"]').trigger('click')
    await flushPromises()
    expect(noticeApiMock.markOneRead).toHaveBeenCalledWith(401, { entry: 'PERSONAL_CENTER' }, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    expect(wrapper.text()).toContain('今天 09:40 · 已读')
    expect(useAppStore().unreadCount).toBe(0)
    // 参考行为：标为已读后焦点落到“只看未读”筛选按钮
    expect(document.activeElement?.getAttribute('data-test')).toBe('filter-unread')
    wrapper.unmount()
  })

  it('lists feedback with user-visible statuses and shows the processing record on deep link', async () => {
    routeMock.hash = '#feedback-complaint-18'
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('CS-0912-018 · 预约 #7')
    expect(wrapper.text()).toContain('预约的羽毛球场照明闪烁')
    expect(wrapper.text()).toContain('问题：设备 · 影响程度：高')
    // 真实 DTO 时间字段为 submittedAt，详情“场馆 · 时间”行要渲染出时间
    expect(wrapper.text()).toMatch(/(今天|昨天|\d+ 月 \d+ 日) \d{2}:\d{2}/)
    expect(wrapper.text()).toContain('待处理')
    expect(wrapper.text()).toContain('客服工单已建立')
    expect(wrapper.text()).toContain('问题进入处理队列')
    wrapper.unmount()
  })

  it('shows review sub-ratings and tags in the detail rows', async () => {
    routeMock.hash = '#feedback-review-19'
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('整体 4 分 · 场馆 5 / 场地 未评分 / 服务 3')
    expect(wrapper.text()).toContain('标签：空间舒适、灯光柔和')
    wrapper.unmount()
  })

  it('filters the message list to unread when only-unread is active', async () => {
    routeMock.hash = '#messages'
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('周日羽毛球场预约已确认')
    expect(wrapper.text()).toContain('退款申请已提交')
    await wrapper.find('[data-test="filter-unread"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('周日羽毛球场预约已确认')
    expect(wrapper.text()).not.toContain('退款申请已提交')
    await wrapper.find('[data-test="filter-all"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('退款申请已提交')
    wrapper.unmount()
  })

  it('shows the favorites denied state when the favorite permission is missing', async () => {
    menuPermissions.value = new Set(['user:view'])
    routeMock.hash = '#favorites'
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('当前缺少收藏权限')
    expect(wrapper.text()).toContain('收藏列表与操作不展示。')
    expect(funnelApiMock.myFavorites).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('degrades independently: feedback failure keeps overview and favorites usable', async () => {
    feedbackApiMock.myComplaints.mockRejectedValue({ response: { status: 403 } })
    feedbackApiMock.myReviews.mockRejectedValue({ response: { status: 403 } })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('你好，北岸小林。')
    routeMock.hash = '#feedback'
    await wrapper.vm.$nextTick()
    // 直接切到反馈区验证 denied 态
    await wrapper.find('[data-view="feedback"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前缺少本人反馈权限')
    // 收藏区不受影响
    await wrapper.find('[data-view="favorites"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('北岸生活场馆')
    wrapper.unmount()
  })

  it('removes a favorite with an undo affordance and restores on undo', async () => {
    routeMock.hash = '#favorites'
    funnelApiMock.cancelFavorite.mockResolvedValue(undefined)
    funnelApiMock.favoriteVenue.mockResolvedValue({ id: 9, venueId: 1, status: 'ACTIVE' })
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('北岸生活场馆')
    expect(wrapper.text()).toContain('东园运动中心')
    await wrapper.find('[data-test="remove-favorite-1"]').trigger('click')
    await flushPromises()
    expect(funnelApiMock.cancelFavorite).toHaveBeenCalledWith(1, {}, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    expect(wrapper.text()).toContain('已移出收藏。')
    await wrapper.find('[data-test="undo-favorite"]').trigger('click')
    await flushPromises()
    expect(funnelApiMock.favoriteVenue).toHaveBeenCalledWith(1, {}, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    expect(wrapper.text()).toContain('北岸生活场馆')
    wrapper.unmount()
  })

  it('toggles the transactional email preference independently', async () => {
    routeMock.hash = '#settings'
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('三项独立选择，初始都关闭。')
    expect(wrapper.text()).toContain('发送地址 l***@example.test')
    await wrapper.find('[data-test="pref-transactionalEmailEnabled"]').trigger('click')
    await flushPromises()
    const dialog = wrapper.find('dialog').element as HTMLDialogElement
    ;(dialog.querySelector('[data-test="confirm-preference"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(noticeApiMock.updateNotificationPreferences).toHaveBeenCalledWith(
      { transactionalEmailEnabled: true },
      { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
    )
    wrapper.unmount()
  })

  it('submits a complaint from the feedback dialog with mapped source fields', async () => {
    routeMock.hash = '#feedback'
    const wrapper = await mountView()
    await wrapper.find('[data-test="feedback-new"]').trigger('click')
    await flushPromises()
    const dialogWrapper = wrapper.find('dialog')
    await dialogWrapper.find('[data-test="complaint-title"]').setValue('照明闪烁')
    await dialogWrapper.find('[data-test="feedback-body"]').setValue('昨晚灯光持续闪烁')
    await dialogWrapper.find('[data-test="confirm-feedback-new"]').trigger('click')
    await flushPromises()
    expect(feedbackApiMock.submitComplaint).toHaveBeenCalledWith(
      expect.objectContaining({ venueId: 1, complaintType: 'EQUIPMENT', severity: 'MEDIUM', title: '照明闪烁' }),
      { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
    )
    wrapper.unmount()
  })

  it('blocks an invalid profile phone before the confirm dialog', async () => {
    routeMock.hash = '#settings'
    const wrapper = await mountView()
    const form = wrapper.find('form')

    await wrapper.find('[data-test="profile-phone"]').setValue('12345')
    await form.trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="profile-phone-error"]').text()).toBe('请输入 11 位有效手机号')
    expect((wrapper.find('dialog').element as HTMLDialogElement).open).toBe(false)

    await wrapper.find('[data-test="profile-phone"]').setValue('13800138000')
    await form.trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="profile-phone-error"]').exists()).toBe(false)
    const dialog = wrapper.find('dialog').element as HTMLDialogElement
    ;(dialog.querySelector('[data-test="confirm-profile-save"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(authApiMock.updateProfile).toHaveBeenCalledWith(
      expect.objectContaining({ phone: '13800138000' }),
      expect.anything()
    )
    wrapper.unmount()
  })

  it('shows the password dialog with reference validation copy', async () => {
    routeMock.hash = '#settings'
    const wrapper = await mountView()
    await wrapper.find('[data-test="password-open"]').trigger('click')
    await flushPromises()
    const dialog = wrapper.find('dialog').element as HTMLDialogElement
    expect(dialog.textContent).toContain('修改账号密码')
    expect(dialog.textContent).toContain('UTF-8 编码不超过 72 字节')
    wrapper.unmount()
  })
  it('keeps coupon and stored-value entrances independent from membership access', async () => {
    menuPermissions.value = new Set(['user:view', 'coupon:me', 'stored-value:me'])
    const wrapper = await mountView()
    const links = wrapper.find('.d-personal-areas').findAll('a').map(a => a.attributes('href'))
    expect(links).toContain('/me/coupons')
    expect(links).toContain('/me/stored-value')
    expect(links).not.toContain('/me/membership')
    expect(links).not.toContain('/me/reservations')
    expect(links).not.toContain('/me/orders')
    wrapper.unmount()
  })

  it('keeps all permitted static destinations usable when identity or optional reads fail', async () => {
    menuPermissions.value = new Set(['user:view', 'member:me', 'coupon:me', 'stored-value:me', 'reservation:my', 'order:my', 'reservation:checkin:me', 'funnel:favorite:me'])
    authApiMock.me.mockRejectedValue(new Error('offline'))
    const wrapper = await mountView()
    expect(wrapper.findAll('.d-personal-area').map(a => a.find('h2').text())).toEqual(['我的安排', '会员中心', '我的服务', '账号设置'])
    expect(wrapper.findAll('.d-area-links a')).toHaveLength(14)
    expect(wrapper.text()).toContain('这部分内容暂时无法读取')
    wrapper.unmount()
  })

  it('does not render failed optional reads as zero unread or no arrangements', async () => {
    noticeApiMock.unreadCount.mockRejectedValue(new Error('offline'))
    reservationApiMock.my.mockRejectedValue(new Error('offline'))
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('消息暂时无法读取')
    expect(wrapper.text()).toContain('到场安排暂时无法读取')
    expect(wrapper.text()).not.toContain('0 条未读')
    expect(wrapper.text()).not.toContain('暂时没有待到场的安排')
    expect(wrapper.find('.d-personal-areas').exists()).toBe(true)
    wrapper.unmount()
  })

})
