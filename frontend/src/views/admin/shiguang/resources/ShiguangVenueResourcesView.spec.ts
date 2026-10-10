import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn()
}))
const venueApiMock = vi.hoisted(() => ({
  spaces: vi.fn()
}))
const venueAdminApiMock = vi.hoisted(() => ({
  maintenances: vi.fn(),
  createSpace: vi.fn(),
  updateSpace: vi.fn(),
  updateSpaceStatus: vi.fn(),
  configureResourceRule: vi.fn(),
  createMaintenance: vi.fn(),
  cancelMaintenance: vi.fn(),
  updateVenue: vi.fn(),
  createVenue: vi.fn(),
  manageable: vi.fn()
}))
const inventoryAdminApiMock = vi.hoisted(() => ({
  calendar: vi.fn(),
  blocks: vi.fn(),
  createBlock: vi.fn(),
  cancelBlock: vi.fn(),
  saveCalendar: vi.fn()
}))

vi.mock('@/api/http', () => ({ default: httpMock }))
vi.mock('@/api/venue', () => ({ venueApi: venueApiMock, venueAdminApi: venueAdminApiMock }))
vi.mock('@/api/inventory', () => ({ inventoryAdminApi: inventoryAdminApiMock }))
vi.mock('@/api/notice', () => ({ noticeApi: { unreadCount: vi.fn().mockResolvedValue(0) } }))

import ShiguangVenueResourcesView from './ShiguangVenueResourcesView.vue'
import { useVenueAccessStore } from '@/stores/venueAccess'

const slot = (hour: number, status = 'AVAILABLE', extra: Record<string, unknown> = {}) => ({
  venueId: 101,
  spaceId: 73,
  startTime: `2026-09-13T${String(hour).padStart(2, '0')}:00:00`,
  endTime: `2026-09-13T${String(hour + 1).padStart(2, '0')}:00:00`,
  status,
  label: '',
  ...extra
})

const seatSpace = {
  id: 72,
  venueId: 101,
  name: '北岸阅读空间',
  type: '阅读自习',
  code: 'READ-01',
  capacity: 24,
  pricePerHour: 70,
  facilityText: '桌椅、阅读灯、电源插座',
  description: '靠窗阅读区',
  status: 'ACTIVE',
  resourceRule: {
    spaceId: 72,
    resourceCategory: 'STUDY_ROOM',
    bookingMode: 'ASSIGNED_SEAT',
    versionNo: 3,
    status: 'ACTIVE',
    seats: [{ id: 1, seatCode: 'A-01', seatLabel: '阅读座位 1', status: 'ACTIVE' }]
  }
}

const courtSpace = {
  id: 73,
  venueId: 101,
  name: '羽毛球2号场',
  type: '羽毛球',
  code: 'BAD-02',
  capacity: 4,
  pricePerHour: 80,
  status: 'ACTIVE',
  resourceRule: {
    spaceId: 73,
    resourceCategory: 'SPORTS_FIELD',
    bookingMode: 'EXCLUSIVE',
    versionNo: 2,
    status: 'ACTIVE',
    seats: []
  }
}

const calendarResponse = {
  slots: [slot(9, 'AVAILABLE'), slot(10, 'RESERVED', { reservationId: 1073, reservationStatus: 'PAID' }), slot(11, 'MANUAL_HOLD')],
  summary: {},
  businessCalendar: {
    id: 1, venueId: 101, calendarDate: '2026-09-13', openTime: '08:00:00', closeTime: '22:00:00', businessStatus: 'OPEN'
  }
}

function venueSetup() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const venueAccess = useVenueAccessStore()
  venueAccess.venues = [{ id: 101, name: '北岸生活场馆', area: '北岸片区', address: '', status: 'ACTIVE', openTime: '08:00:00', closeTime: '22:00:00' }] as never
  venueAccess.activeVenueId = 101
  return venueAccess
}

async function mountView(initialTab: 'spaces' | 'calendar'): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/venue-admin/spaces', name: 'VenueAdminSpaces', component: ShiguangVenueResourcesView },
      { path: '/venue-admin/calendar', name: 'VenueAdminCalendar', component: ShiguangVenueResourcesView }
    ]
  })
  await router.push(initialTab === 'spaces' ? '/venue-admin/spaces' : '/venue-admin/calendar')
  await router.isReady()
  const wrapper = mount(ShiguangVenueResourcesView, {
    global: {
      plugins: [router],
      provide: {}
    }
  })
  await flushPromises()
  await flushPromises()
  await flushPromises()
  return { wrapper, router }
}

const grantedPermissions = new Set(['venue-admin:view', 'venue:inventory:view', 'venue:space:manage', 'venue:calendar:manage'])

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasPermission: (key: string) => grantedPermissions.has(key)
  })
}))

vi.mock('@/stores/app', () => ({
  useAppStore: () => ({ showToast: vi.fn(), toastMessage: '' })
}))

describe('ShiguangVenueResourcesView', () => {
  beforeEach(() => {
    // B3-T57：维护与不可售记录按 todayIso() 过滤，夹具固定在 2026-09-13，
    // 不同步冻结系统时刻就会跨日渲染成「当前日期没有维护或不可售记录」。
    vi.useFakeTimers({ now: new Date('2026-09-13T09:45:00+08:00'), shouldAdvanceTime: true })
    vi.clearAllMocks()
    venueSetup()
    venueApiMock.spaces.mockResolvedValue([seatSpace, courtSpace])
    inventoryAdminApiMock.calendar.mockImplementation(async (_params: unknown, spaceContainer: unknown) => {
      void spaceContainer
      return calendarResponse
    })
    inventoryAdminApiMock.blocks.mockResolvedValue([
      {
        id: 12, venueId: 101, spaceId: 73, blockType: 'MANUAL_HOLD', reasonCode: 'MANUAL_HOLD',
        reasonText: '校队训练保留', startTime: '2026-09-13T10:00:00', endTime: '2026-09-13T12:00:00',
        bufferBeforeMinutes: 0, bufferAfterMinutes: 0, status: 'ACTIVE'
      }
    ])
    venueAdminApiMock.maintenances.mockResolvedValue([
      { id: 7, spaceId: 72, startTime: '2026-09-13T16:00:00', endTime: '2026-09-13T18:00:00', reason: '阅读区灯具检修', status: 'ACTIVE' }
    ])
    ;(globalThis as unknown as { HTMLDialogElement: { prototype: { showModal: () => void; close: () => void } } }).HTMLDialogElement.prototype.showModal = () => {}
    ;(globalThis as unknown as { HTMLDialogElement: { prototype: { showModal: () => void; close: () => void } } }).HTMLDialogElement.prototype.close = () => {}
  })

  it('calendar 路由进入当日可用性页签并渲染库存矩阵与图例', async () => {
    const { wrapper } = await mountView('calendar')
    expect(wrapper.find('[data-test="inventory-matrix"]').exists()).toBe(true)
    const rowHeaders = wrapper.findAll('.e-matrix tbody th strong').map((node) => node.text())
    expect(rowHeaders).toEqual(['北岸阅读空间', '羽毛球2号场'])
    const reservedSlot = wrapper.find('[data-state="RESERVED"]')
    expect(reservedSlot.text()).toBe('已约')
    expect(reservedSlot.attributes('aria-pressed')).toBe('false')
    expect(wrapper.find('.e-legend').exists()).toBe(true)
    expect(wrapper.text()).toContain('此处不展示剩余人数或空闲座位数量')
  })

  it('点击槽位显示状态原因与关联预约， inspector 动作按权限可用', async () => {
    const { wrapper } = await mountView('calendar')
    await wrapper.find('[data-state="RESERVED"]').trigger('click')
    await flushPromises()
    const inspector = wrapper.find('[data-test="slot-inspector"]')
    expect(inspector.text()).toContain('已约')
    expect(inspector.text()).toContain('关联预约 #1073 · PAID')
    expect(wrapper.find('[data-test="open-maintenance"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-test="open-block"]').attributes('disabled')).toBeUndefined()
  })

  it('维护与不可售记录合并展示并各自动作', async () => {
    const { wrapper } = await mountView('calendar')
    const records = wrapper.find('[data-test="inventory-records"]')
    expect(records.text()).toContain('维护与不可售记录')
    expect(records.text()).toContain('阅读区灯具检修')
    expect(records.text()).toContain('校队训练保留')
    expect(records.find('[data-test="cancel-maintenance"]').exists()).toBe(true)
    expect(records.find('[data-test="cancel-block"]').exists()).toBe(true)
  })

  it('spaces 路由进入资源定义页签并渲染容量三元组与座位目录', async () => {
    const { wrapper } = await mountView('spaces')
    expect(wrapper.find('.e-resource-picker').exists()).toBe(true)
    expect(wrapper.text()).toContain('资源定义')
    expect(wrapper.text()).toContain('物理容量')
    expect(wrapper.text()).toContain('预订方式')
    expect(wrapper.text()).toContain('按当前 1 个座位配置')
    expect(wrapper.text()).toContain('V3 · 原预约使用原规则快照')
    expect(wrapper.find('.e-seat').exists()).toBe(true)
    expect(wrapper.text()).toContain('三件事分别维护')
  })

  it('新增场地弹层提交 createSpace 并置 stale', async () => {
    const { wrapper } = await mountView('spaces')
    await wrapper.find('[data-test="new-space"]').trigger('click')
    await wrapper.find('input[data-test="s-name"]').setValue('新活动室')
    venueAdminApiMock.createSpace.mockResolvedValue({ id: 80 })
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(venueAdminApiMock.createSpace).toHaveBeenCalled()
    expect(wrapper.find('[data-test="stale-note"]').exists()).toBe(true)
  })

  it('停用场地弹层按 ACTIVE 状态给出取消预约警示文案', async () => {
    const { wrapper } = await mountView('spaces')
    await wrapper.find('[data-test="toggle-space"]').trigger('click')
    expect(wrapper.text()).toContain('停用场地前核对影响')
    expect(wrapper.text()).toContain('停用会取消该场地未来可履约预约。退款处理结果需在订单中继续核对。')
  })

  it('预订规则弹层对空座位草稿给出服务端同款错误', async () => {
    const { wrapper } = await mountView('spaces')
    await wrapper.find('[data-test="edit-rule"]').trigger('click')
    await wrapper.find('select[data-test="r-mode"]').setValue('ASSIGNED_SEAT')
    // 通过弹层"移除座位"按钮清空草稿（与真实操作一致）
    await wrapper.find('[aria-label="移除座位 1"]').trigger('click')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('指定座位至少需要配置一个座位。')
  })

  it('场馆与营业安排页签渲染基础信息与当天安排', async () => {
    const { wrapper } = await mountView('calendar')
    await wrapper.find('[data-test="tab-venue"]').trigger('click')
    expect(wrapper.text()).toContain('场馆与营业安排')
    expect(wrapper.text()).toContain('北岸生活场馆')
    expect(wrapper.text()).toContain('正常营业')
    expect(wrapper.text()).toContain('按常规营业时间开放')
    expect(wrapper.find('[data-test="new-venue"]').exists()).toBe(false)
  })

  it('缺库存与资源权限时呈现对应空态', async () => {
    grantedPermissions.clear()
    grantedPermissions.add('venue-admin:view')
    const { wrapper } = await mountView('calendar')
    expect(wrapper.find('[data-test="surface-forbidden"]').exists()).toBe(true)
    grantedPermissions.add('venue:space:manage')
    const second = await mountView('calendar')
    expect(second.wrapper.find('[data-test="denied-inventory"]').exists()).toBe(true)
  })

  afterEach(() => {
    vi.useRealTimers()
  })
})
