import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

const adminCheckinApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  lifecycle: vi.fn(),
  verify: vi.fn(),
  manualPass: vi.fn(),
  confirmNoShow: vi.fn(),
  revokeNoShow: vi.fn()
}))
const adminOrderApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  refunds: vi.fn(),
  approveRefund: vi.fn(),
  rejectRefund: vi.fn(),
  markRefundSuccess: vi.fn()
}))
const adminReservationApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  cancel: vi.fn(),
  rescheduleRequests: vi.fn(),
  reviewReschedule: vi.fn()
}))
const statisticsApiMock = vi.hoisted(() => ({
  dashboard: vi.fn()
}))

vi.mock('@/api/checkin', () => ({ adminCheckinApi: adminCheckinApiMock, checkinApi: { my: vi.fn(), reissueCredential: vi.fn() } }))
vi.mock('@/api/order', () => ({ adminOrderApi: adminOrderApiMock, orderApi: { my: vi.fn() } }))
vi.mock('@/api/reservation', () => ({ adminReservationApi: adminReservationApiMock, reservationApi: {} }))
vi.mock('@/api/statistics', () => ({ statisticsApi: statisticsApiMock }))
vi.mock('@/api/venue', () => ({ venueApi: { spaces: vi.fn() }, venueAdminApi: { manageable: vi.fn().mockResolvedValue([]) } }))
vi.mock('@/api/notice', () => ({ noticeApi: { unreadCount: vi.fn().mockResolvedValue(0) } }))

import ShiguangVenueFulfillmentView from './ShiguangVenueFulfillmentView.vue'
import { ISOLATED_REQUEST } from '../adminShared'
import { useVenueAccessStore } from '@/stores/venueAccess'

const granted = new Set(['venue-admin:view', 'reservation:audit', 'reservation:checkin:manage', 'reservation:lifecycle:view', 'order:admin'])

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: (key: string) => granted.has(key) })
}))
vi.mock('@/stores/app', () => ({
  useAppStore: () => ({ showToast: vi.fn(), toastMessage: '' })
}))

const reservation = (overrides: Record<string, unknown> = {}) => ({
  id: 1073,
  orderId: 2073,
  userId: 18,
  venueId: 101,
  spaceId: 73,
  status: 'PAID',
  startTime: '2026-09-13T19:00:00',
  endTime: '2026-09-13T20:00:00',
  contactName: '示例用户018',
  contactPhone: '13800000018',
  venueName: '北岸生活场馆',
  payableAmount: 80,
  paidAmount: 80,
  ...overrides
})

const order = (overrides: Record<string, unknown> = {}) => ({
  id: 2073,
  orderNo: 'O-20260913-0001',
  reservationId: 1073,
  userId: 18,
  venueId: 101,
  status: 'PAID',
  totalAmount: 80,
  originalAmount: 80,
  memberDiscountAmount: 0,
  couponDiscountAmount: 0,
  activityDiscountAmount: 0,
  payableAmount: 80,
  paidAmount: 80,
  ...overrides
})

const lifecycle = (overrides: Record<string, unknown> = {}) => ({
  reservationId: 1073,
  venueId: 101,
  spaceId: 73,
  credentialStatus: 'ISSUED',
  checkinCodeLast4: '4821',
  checkinWindowStart: '2026-09-13T18:30:00',
  checkinWindowEnd: '2099-12-31T20:30:00',
  attendanceStatus: 'PENDING',
  checkinStatus: 'NOT_CHECKED_IN',
  noShowStatus: 'NOT_EVALUATED',
  ...overrides
})

const page = (records: unknown[]) => ({ records, total: records.length, pageNum: 1, pageSize: 100 })

function setupStores(): void {
  setActivePinia(createPinia())
  const venueAccess = useVenueAccessStore()
  venueAccess.venues = [{ id: 101, name: '北岸生活场馆', area: '', address: '', status: 'ACTIVE' }] as never
  venueAccess.activeVenueId = 101
}

beforeEach(() => {
  vi.clearAllMocks()
  setupStores()
  adminReservationApiMock.list.mockResolvedValue(page([
    reservation(),
    reservation({ id: 1072, orderId: null, status: 'PENDING_REVIEW', startTime: '2026-09-13T14:00:00', endTime: '2026-09-13T16:00:00' })
  ]))
  adminCheckinApiMock.list.mockResolvedValue(page([lifecycle()]))
  adminCheckinApiMock.lifecycle.mockResolvedValue(lifecycle({ events: [{ eventType: 'CHECKIN_VERIFIED', sourceType: 'ADMIN', createTime: '2026-09-13T19:05:00', reasonText: '按时到场' }] }))
  adminOrderApiMock.list.mockResolvedValue(page([order()]))
  adminOrderApiMock.refunds.mockResolvedValue(page([{ id: 3074, refundNo: 'RF-20260913-0001', orderId: 2073, userId: 18, refundAmount: 80, refundReason: '行程有变', status: 'PENDING_REVIEW' }]))
  adminReservationApiMock.rescheduleRequests.mockResolvedValue(page([]))
  statisticsApiMock.dashboard.mockResolvedValue({ todayReservations: 28, pendingReviews: 1, totalUsers: 100, revenue: 1680, occupancyRate: 68 })
  const proto = (globalThis.HTMLDialogElement as unknown as { prototype: Record<string, () => void> }).prototype
  proto.showModal = proto.showModal ?? (() => {})
  proto.close = proto.close ?? (() => {})
})

async function mountView(routePath = '/venue-admin/checkins', routeName = 'VenueAdminCheckins'): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/venue-admin', name: 'VenueAdminDashboard', component: ShiguangVenueFulfillmentView },
      { path: '/venue-admin/reservations', name: 'VenueAdminReservations', component: ShiguangVenueFulfillmentView },
      { path: '/venue-admin/checkins', name: 'VenueAdminCheckins', component: ShiguangVenueFulfillmentView },
      { path: '/venue-admin/orders', name: 'VenueAdminOrders', component: ShiguangVenueFulfillmentView }
    ]
  })
  await router.push(routePath)
  await router.isReady()
  const wrapper = mount(ShiguangVenueFulfillmentView, { global: { plugins: [router] } })
  await flushPromises()
  await flushPromises()
  await flushPromises()
  return { wrapper, router }
}

describe('ShiguangVenueFulfillmentView', () => {
  it('checkins 路由进入到场核验并渲染列表与详情', async () => {
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="sga-fulfillment-root"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('让每一场预约，顺利发生')
    expect(wrapper.text()).toContain('当前预约与异常')
    const detail = wrapper.find('[data-test="arrival-detail"]')
    expect(detail.text()).toContain('仅显示尾号 4821')
    expect(detail.text()).toContain('#1073')
    expect(wrapper.find('[data-test="verify"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="manual-pass"]').exists()).toBe(true)
  })

  it('核验弹层提交六位短码', async () => {
    adminCheckinApiMock.verify.mockResolvedValue(lifecycle())
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="verify"]').trigger('click')
    await wrapper.find('input[data-test="verify-code"]').setValue('674821')
    await wrapper.find('textarea[data-test="dialog-reason"]').setValue('现场核对无误')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(adminCheckinApiMock.verify).toHaveBeenCalledWith({ credential: '674821' }, ISOLATED_REQUEST)
  })

  it('人工放行提交原因类别与说明', async () => {
    adminCheckinApiMock.manualPass.mockResolvedValue(lifecycle())
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="manual-pass"]').trigger('click')
    await wrapper.find('select[data-test="manual-reason-code"]').setValue('DEVICE_ISSUE')
    await wrapper.find('textarea[data-test="dialog-reason"]').setValue('设备故障，现场已核对')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(adminCheckinApiMock.manualPass).toHaveBeenCalledWith(1073, { reasonCode: 'DEVICE_ISSUE', reasonText: '设备故障，现场已核对' }, ISOLATED_REQUEST)
  })

  it('履约记录弹层读取 lifecycle 事件', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="lifecycle"]').trigger('click')
    await flushPromises()
    expect(adminCheckinApiMock.lifecycle).toHaveBeenCalledWith(1073, ISOLATED_REQUEST)
    expect(wrapper.find('dialog.f-modal').text()).toContain('到场核验完成')
  })

  it('reservations 路由进入预约与改期页签并可批准', async () => {
    adminReservationApiMock.approve.mockResolvedValue({})
    const { wrapper } = await mountView('/venue-admin/reservations', 'VenueAdminReservations')
    expect(wrapper.find('[data-test="review-detail"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('预约审核与管理')
    // 默认选中第一条（PAID）；批准动作属于待审核预约 1072
    await wrapper.find('[data-test="review-1072"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="approve"]').trigger('click')
    await wrapper.find('textarea[data-test="dialog-reason"]').setValue('库存与报价已复核')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(adminReservationApiMock.approve).toHaveBeenCalledWith(1072, '库存与报价已复核', ISOLATED_REQUEST)
  })

  it('orders 路由进入订单退款账本并可同意退款', async () => {
    adminOrderApiMock.approveRefund.mockResolvedValue({})
    const { wrapper } = await mountView('/venue-admin/orders', 'VenueAdminOrders')
    const ledger = wrapper.find('[data-test="order-ledger"]')
    expect(ledger.text()).toContain('O-20260913-0001')
    expect(ledger.text()).toContain('¥80.00')
    expect(wrapper.find('[data-test="refund-approve"]').exists()).toBe(true)
    await wrapper.find('[data-test="refund-approve"]').trigger('click')
    await wrapper.find('textarea[data-test="dialog-reason"]').setValue('凭证与金额已核对')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(adminOrderApiMock.approveRefund).toHaveBeenCalledWith(3074, '凭证与金额已核对', ISOLATED_REQUEST)
  })

  it('退款到账确认需要先勾选核对', async () => {
    adminOrderApiMock.markRefundSuccess.mockResolvedValue({})
    adminOrderApiMock.refunds.mockResolvedValue(page([{ id: 3074, refundNo: 'RF-1', orderId: 2073, userId: 18, refundAmount: 80, refundReason: 'x', status: 'REFUNDING' }]))
    const { wrapper } = await mountView('/venue-admin/orders', 'VenueAdminOrders')
    await wrapper.find('[data-test="refund-success"]').trigger('click')
    await wrapper.find('textarea[data-test="dialog-reason"]').setValue('已独立核对')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toContain('请先完成退款结果与金额的独立核对')
    await wrapper.find('input[data-test="refund-checked"]').setValue(true)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(adminOrderApiMock.markRefundSuccess).toHaveBeenCalledWith(3074, ISOLATED_REQUEST)
  })

  it('day 路由展示当日经营参照与当班事项', async () => {
    const { wrapper } = await mountView('/venue-admin', 'VenueAdminDashboard')
    const day = wrapper.find('[data-test="day-tab"]')
    expect(day.text()).toContain('当日经营参照')
    expect(day.text()).toContain('28')
    expect(day.text()).toContain('68.0')
    expect(wrapper.find('[data-test="duty-review"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="duty-arrival"]').exists()).toBe(true)
  })

  it('无核销权限时到场页签渲染 denied 空态', async () => {
    granted.delete('reservation:checkin:manage')
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="denied-arrival"]').exists()).toBe(true)
    granted.add('reservation:checkin:manage')
  })

  it('无场馆时呈现锁定空态', async () => {
    const venueAccess = useVenueAccessStore()
    venueAccess.venues = []
    venueAccess.activeVenueId = null
    const { wrapper } = await mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="surface-lock"]').exists()).toBe(true)
  })
})
