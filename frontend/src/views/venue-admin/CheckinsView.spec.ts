import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, inject, provide, toRef, type Ref, type VNode } from 'vue'

const adminCheckinApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  verify: vi.fn(),
  manualPass: vi.fn(),
  confirmNoShow: vi.fn(),
  revokeNoShow: vi.fn()
}))
const adminReservationApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  cancel: vi.fn(),
  rescheduleRequests: vi.fn(),
  reviewReschedule: vi.fn()
}))
const venueAdminApiMock = vi.hoisted(() => ({
  manageable: vi.fn()
}))
const routerMock = vi.hoisted(() => ({
  push: vi.fn()
}))
const messageBoxMock = vi.hoisted(() => ({
  prompt: vi.fn(),
  confirm: vi.fn()
}))
const messageMock = vi.hoisted(() => ({ success: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn() }))

vi.mock('@/api/checkin', () => ({ adminCheckinApi: adminCheckinApiMock }))
vi.mock('@/api/reservation', () => ({ adminReservationApi: adminReservationApiMock }))
vi.mock('@/api/venue', () => ({ venueAdminApi: venueAdminApiMock }))
vi.mock('@/stores/app', () => ({ useAppStore: () => ({ refreshUnreadCount: vi.fn() }) }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: messageMock,
    ElMessageBox: messageBoxMock
  }
})

const ElTableStub = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide('tableRows', toRef(props, 'data'))
    return () => h('div', slots.default?.())
  }
})

const ElTableColumnStub = defineComponent({
  props: { prop: String, label: String },
  setup(props, { slots }) {
    const rows = inject<Ref<Record<string, unknown>[]>>('tableRows')
    return () =>
      h('div', (rows?.value || []).flatMap((row) => {
        if (slots.default) {
          return slots.default({ row }) as VNode[]
        }
        return [h('span', String(row[props.prop || ''] ?? ''))]
      }))
  }
})

const stubs = {
  StatusTag: { template: '<span>{{ value }}</span>', props: ['value'] },
  'el-alert': { props: ['title'], template: '<div>{{ title }}</div>' },
  'el-button': { emits: ['click'], template: '<button type="button" @click="$emit(\'click\')"><slot /></button>' },
  'el-input': { template: '<input />' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': true,
  'el-date-picker': true,
  'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
  'el-table': ElTableStub,
  'el-table-column': ElTableColumnStub,
  'el-tag': { template: '<span><slot /></span>' },
  'el-pagination': true
}

describe('VenueAdmin CheckinsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    messageBoxMock.prompt.mockResolvedValue({ value: '现场登记' })
    adminCheckinApiMock.list.mockResolvedValue({
      records: [
        {
          reservationId: 99,
          orderId: 88,
          venueId: 2,
          spaceId: 10,
          credentialStatus: 'ISSUED',
          checkinCodeLast4: '3456',
          checkinWindowStart: '2026-07-03T09:45:00',
          checkinWindowEnd: '2026-07-03T10:15:00',
          attendanceStatus: 'PENDING',
          checkinStatus: 'NOT_CHECKED_IN',
          noShowStatus: 'CANDIDATE',
          reasonText: '超时未核销',
          events: []
        }
      ],
      total: 1,
      pageNum: 1,
      pageSize: 20
    })
    adminCheckinApiMock.verify.mockResolvedValue({ reservationId: 99 })
    adminCheckinApiMock.manualPass.mockResolvedValue({ reservationId: 99 })
    adminCheckinApiMock.confirmNoShow.mockResolvedValue({ reservationId: 99 })
    adminCheckinApiMock.revokeNoShow.mockResolvedValue({ reservationId: 99 })
  })

  it('loads checkins and supports credential verification', async () => {
    const { default: CheckinsView } = await import('./CheckinsView.vue')
    const wrapper = mount(CheckinsView, {
      global: {
        stubs,
        directives: {
          loading: {
            mounted: () => undefined,
            updated: () => undefined
          }
        }
      }
    })
    await flushPromises()

    expect(adminCheckinApiMock.list).toHaveBeenCalledWith({ pageNum: 1, pageSize: 20 })
    expect(wrapper.text()).toContain('签到核销')
    expect(wrapper.text()).toContain('尾号 3456')
    expect(wrapper.text()).toContain('已发放')
    expect(wrapper.text()).toContain('待核销')
    expect(wrapper.text()).toContain('待确认爽约')

    ;(wrapper.vm as unknown as { credential: string; verify: () => Promise<void> }).credential = '123456'
    await (wrapper.vm as unknown as { verify: () => Promise<void> }).verify()
    await flushPromises()

    expect(adminCheckinApiMock.verify).toHaveBeenCalledWith({ credential: '123456', deviceRef: 'frontdesk' })
    expect(adminCheckinApiMock.list).toHaveBeenCalledTimes(2)
  })

  it('surfaces backend rejection messages for verify and manual-pass failures (D-06)', async () => {
    adminCheckinApiMock.verify.mockRejectedValue({
      response: { status: 400, data: { code: 40000, message: '核销窗口尚未开启' } }
    })
    adminCheckinApiMock.manualPass.mockRejectedValue({
      response: { status: 400, data: { code: 40000, message: '凭证已核销' } }
    })
    const { default: CheckinsView } = await import('./CheckinsView.vue')
    const wrapper = mount(CheckinsView, {
      global: {
        stubs,
        directives: {
          loading: {
            mounted: () => undefined,
            updated: () => undefined
          }
        }
      }
    })
    await flushPromises()

    ;(wrapper.vm as unknown as { credential: string; verify: () => Promise<void> }).credential = '123456'
    await (wrapper.vm as unknown as { verify: () => Promise<void> }).verify()
    await flushPromises()

    expect(messageMock.error).toHaveBeenCalledWith('核销窗口尚未开启')
    expect(messageMock.success).not.toHaveBeenCalledWith('核销通过')

    await (wrapper.vm as unknown as { manualPass: (id: number) => Promise<void> }).manualPass(99)
    await flushPromises()

    expect(messageMock.error).toHaveBeenCalledWith('凭证已核销')
    expect(messageMock.success).not.toHaveBeenCalledWith('已人工放行')
  })

  it('keeps success toasts for verify and manual-pass paths while surfacing failures (D-06 regression)', async () => {
    const { default: CheckinsView } = await import('./CheckinsView.vue')
    const wrapper = mount(CheckinsView, {
      global: {
        stubs,
        directives: {
          loading: {
            mounted: () => undefined,
            updated: () => undefined
          }
        }
      }
    })
    await flushPromises()

    ;(wrapper.vm as unknown as { credential: string; verify: () => Promise<void> }).credential = '654321'
    await (wrapper.vm as unknown as { verify: () => Promise<void> }).verify()
    await flushPromises()
    expect(messageMock.success).toHaveBeenCalledWith('核销通过')

    await (wrapper.vm as unknown as { manualPass: (id: number) => Promise<void> }).manualPass(99)
    await flushPromises()
    expect(messageMock.success).toHaveBeenCalledWith('已人工放行')
  })

  it('requires reasons for manual pass, no-show confirmation and no-show revoke actions', async () => {
    const { default: CheckinsView } = await import('./CheckinsView.vue')
    const wrapper = mount(CheckinsView, {
      global: {
        stubs,
        directives: {
          loading: {
            mounted: () => undefined,
            updated: () => undefined
          }
        }
      }
    })
    await flushPromises()

    await (wrapper.vm as unknown as { manualPass: (id: number) => Promise<void> }).manualPass(99)
    await (wrapper.vm as unknown as { confirmNoShow: (id: number) => Promise<void> }).confirmNoShow(99)
    await (wrapper.vm as unknown as { revokeNoShow: (id: number) => Promise<void> }).revokeNoShow(99)

    expect(adminCheckinApiMock.manualPass).toHaveBeenCalledWith(99, {
      reasonCode: 'MANUAL_PASS',
      reasonText: '现场登记',
      deviceRef: 'frontdesk'
    })
    expect(adminCheckinApiMock.confirmNoShow).toHaveBeenCalledWith(99, {
      reasonCode: 'NO_SHOW_CONFIRMED',
      reasonText: '现场登记'
    })
    expect(adminCheckinApiMock.revokeNoShow).toHaveBeenCalledWith(99, {
      reasonCode: 'NO_SHOW_REVOKED',
      reasonText: '现场登记'
    })
  })
})

describe('VenueAdmin ReservationsView checkin handoff', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    venueAdminApiMock.manageable.mockResolvedValue([{ id: 2, name: '东区综合体育馆', status: 'ACTIVE' }])
    adminReservationApiMock.list.mockResolvedValue({
      records: [
        {
          id: 99,
          userId: 7,
          venueId: 2,
          spaceId: 10,
          startTime: '2026-07-03T10:00:00',
          endTime: '2026-07-03T11:00:00',
          contactName: '陈同学',
          amount: 80,
          status: 'PAID'
        }
      ],
      total: 1,
      pageNum: 1,
      pageSize: 20
    })
    adminReservationApiMock.rescheduleRequests.mockResolvedValue({
      records: [],
      total: 0,
      pageNum: 1,
      pageSize: 20
    })
    adminReservationApiMock.reviewReschedule.mockResolvedValue(undefined)
  })

  it('links paid reservations to the venue-admin checkin workspace', async () => {
    const { default: ReservationsView } = await import('./ReservationsView.vue')
    const wrapper = mount(ReservationsView, {
      global: {
        stubs,
        directives: {
          loading: {
            mounted: () => undefined,
            updated: () => undefined
          }
        }
      }
    })
    await flushPromises()

    const checkinButton = wrapper.findAll('button').find((button) => button.text() === '签到核销')
    expect(checkinButton).toBeTruthy()
    await checkinButton?.trigger('click')

    expect(routerMock.push).toHaveBeenCalledWith('/venue-admin/checkins')
    wrapper.unmount()
  })

  it('loads and reviews reschedule requests with an explicit audit reason', async () => {
    adminReservationApiMock.rescheduleRequests.mockResolvedValue({
      records: [
        {
          id: 31,
          reservationId: 99,
          userId: 7,
          venueId: 2,
          spaceId: 10,
          fromStartTime: '2026-07-03T10:00:00',
          fromEndTime: '2026-07-03T11:00:00',
          toStartTime: '2026-07-04T14:00:00',
          toEndTime: '2026-07-04T15:00:00',
          reasonText: '课程时间调整',
          status: 'PENDING_REVIEW'
        }
      ],
      total: 1,
      pageNum: 1,
      pageSize: 20
    })
    messageBoxMock.prompt.mockResolvedValue({ value: '库存与价格已复核' })
    const { default: ReservationsView } = await import('./ReservationsView.vue')
    const wrapper = mount(ReservationsView, {
      global: {
        stubs,
        directives: {
          loading: {
            mounted: () => undefined,
            updated: () => undefined
          }
        }
      }
    })
    await flushPromises()

    expect(adminReservationApiMock.rescheduleRequests).toHaveBeenCalledWith({ pageNum: 1, pageSize: 20 })
    expect(wrapper.text()).toContain('课程时间调整')
    expect(wrapper.text()).toContain('2026-07-04 14:00 - 15:00')

    await (wrapper.vm as unknown as {
      reviewReschedule: (id: number, approve: boolean) => Promise<void>
    }).reviewReschedule(31, true)
    await flushPromises()

    expect(adminReservationApiMock.reviewReschedule).toHaveBeenCalledWith(31, true, '库存与价格已复核')
    expect(adminReservationApiMock.list).toHaveBeenCalledTimes(2)
    expect(adminReservationApiMock.rescheduleRequests).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('keeps the reservation queue usable when the reschedule queue fails', async () => {
    adminReservationApiMock.rescheduleRequests.mockRejectedValue(new Error('改期服务暂不可用'))
    const { default: ReservationsView } = await import('./ReservationsView.vue')
    const wrapper = mount(ReservationsView, {
      global: {
        stubs,
        directives: {
          loading: {
            mounted: () => undefined,
            updated: () => undefined
          }
        }
      }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('改期队列加载失败')
    expect(wrapper.text()).toContain('改期服务暂不可用')
    expect(wrapper.text()).toContain('东区综合体育馆')
    wrapper.unmount()
  })
})
