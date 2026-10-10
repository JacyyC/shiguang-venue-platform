import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CalendarView from './CalendarView.vue'

const inventoryApiMock = vi.hoisted(() => ({
  calendar: vi.fn().mockResolvedValue({
    businessCalendar: null,
    slots: [
      {
        venueId: 1,
        spaceId: 10,
        startTime: '2026-07-02T10:00:00',
        endTime: '2026-07-02T11:00:00',
        status: 'AVAILABLE',
        label: '可售'
      },
      {
        venueId: 1,
        spaceId: 10,
        startTime: '2026-07-02T11:00:00',
        endTime: '2026-07-02T12:00:00',
        status: 'MANUAL_HOLD',
        label: '人工保留',
        reasonText: '校队训练'
      }
    ],
    summary: {
      availableSlots: 1,
      reservedSlots: 0,
      unavailableSlots: 0,
      manualHoldSlots: 1,
      closedSlots: 0,
      bufferSlots: 0
    }
  }),
  blocks: vi.fn().mockResolvedValue([]),
  createBlock: vi.fn().mockResolvedValue({ id: 1 }),
  saveCalendar: vi.fn().mockResolvedValue({ id: 2 }),
  cancelBlock: vi.fn().mockResolvedValue(undefined)
}))

const venueApiMock = vi.hoisted(() => ({
  manageable: vi.fn().mockResolvedValue([{ id: 1, name: '东区综合体育馆', status: 'ACTIVE' }]),
  spaces: vi.fn().mockResolvedValue([
    { id: 10, venueId: 1, name: '羽毛球 1 号场', type: 'BADMINTON', pricePerHour: 40, status: 'ACTIVE' }
  ])
}))

const authStoreMock = vi.hoisted(() => ({
  hasPermission: vi.fn()
}))

vi.mock('@/api/inventory', () => ({ inventoryAdminApi: inventoryApiMock }))
vi.mock('@/api/venue', () => ({ venueAdminApi: venueApiMock, venueApi: venueApiMock }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authStoreMock }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) }
  }
})

const stubs = {
  'el-alert': { props: ['title'], template: '<div>{{ title }}</div>' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': true,
  'el-date-picker': true,
  'el-time-picker': true,
  'el-button': { template: '<button type="button" @click="$emit(\'click\')"><slot /></button>' },
  'el-table': { template: '<table><slot /></table>' },
  'el-table-column': true,
  'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-input': { template: '<input />' },
  'el-input-number': { template: '<input type="number" />' },
  'el-icon': true,
  Plus: true,
  Refresh: true,
  StatusTag: { template: '<span>{{ value }}</span>', props: ['value'] }
}

const mountView = () => mount(CalendarView, {
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

describe('CalendarView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authStoreMock.hasPermission.mockImplementation((permission: string) => permission === 'venue:calendar:manage')
  })

  it('loads inventory summary and readable slot states', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(venueApiMock.manageable).toHaveBeenCalled()
    expect(venueApiMock.spaces).toHaveBeenCalledWith(1)
    expect(inventoryApiMock.calendar).toHaveBeenCalledWith({ venueId: 1, spaceId: 10, date: expect.any(String) })
    expect(inventoryApiMock.blocks).toHaveBeenCalledWith({ venueId: 1, spaceId: 10, date: expect.any(String) })
    expect(wrapper.text()).toContain('可售')
    expect(wrapper.text()).toContain('人工保留')
    expect(wrapper.text()).toContain('校队训练')
  })

  it('creates unavailable block with selected venue, space and buffer fields', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.vm.$nextTick()
    ;(wrapper.vm as unknown as {
      blockForm: {
        blockType: string
        reasonCode: string
        reasonText: string
        startTime: string
        endTime: string
        bufferBeforeMinutes: number
        bufferAfterMinutes: number
      }
      saveBlock: () => Promise<void>
    }).blockForm = {
      blockType: 'MANUAL_HOLD',
      reasonCode: 'MANUAL_HOLD',
      reasonText: '校队训练',
      startTime: '2026-07-02T11:00:00',
      endTime: '2026-07-02T12:00:00',
      bufferBeforeMinutes: 15,
      bufferAfterMinutes: 10
    }

    await (wrapper.vm as unknown as { saveBlock: () => Promise<void> }).saveBlock()

    expect(inventoryApiMock.createBlock).toHaveBeenCalledWith({
      venueId: 1,
      spaceId: 10,
      blockType: 'MANUAL_HOLD',
      reasonCode: 'MANUAL_HOLD',
      reasonText: '校队训练',
      startTime: '2026-07-02T11:00:00',
      endTime: '2026-07-02T12:00:00',
      bufferBeforeMinutes: 15,
      bufferAfterMinutes: 10
    })
  })

  it('hides and guards calendar write actions without manage permission', async () => {
    authStoreMock.hasPermission.mockReturnValue(false)
    const wrapper = mountView()
    await flushPromises()

    expect(authStoreMock.hasPermission).toHaveBeenCalledWith('venue:calendar:manage')
    expect(wrapper.text()).not.toContain('新增不可售')

    ;(wrapper.vm as unknown as {
      blockForm: {
        blockType: string
        reasonCode: string
        reasonText: string
        startTime: string
        endTime: string
        bufferBeforeMinutes: number
        bufferAfterMinutes: number
      }
      saveBlock: () => Promise<void>
      cancelBlock: (id: number) => Promise<void>
    }).blockForm = {
      blockType: 'MANUAL_HOLD',
      reasonCode: 'MANUAL_HOLD',
      reasonText: '校队训练',
      startTime: '2026-07-02T11:00:00',
      endTime: '2026-07-02T12:00:00',
      bufferBeforeMinutes: 15,
      bufferAfterMinutes: 10
    }

    await (wrapper.vm as unknown as { saveBlock: () => Promise<void> }).saveBlock()
    await (wrapper.vm as unknown as { cancelBlock: (id: number) => Promise<void> }).cancelBlock(1)

    expect(inventoryApiMock.createBlock).not.toHaveBeenCalled()
    expect(inventoryApiMock.cancelBlock).not.toHaveBeenCalled()
  })

  it('saves business calendar and refreshes inventory with manage permission', async () => {
    const wrapper = mountView()
    await flushPromises()

    ;(wrapper.vm as unknown as {
      calendarForm: {
        businessStatus: string
        openTime: string
        closeTime: string
        reasonText: string
      }
      saveCalendar: () => Promise<void>
    }).calendarForm = {
      businessStatus: 'SPECIAL_HOURS',
      openTime: '09:30:00',
      closeTime: '17:30:00',
      reasonText: '暑期开放'
    }

    await (wrapper.vm as unknown as { saveCalendar: () => Promise<void> }).saveCalendar()

    expect(inventoryApiMock.saveCalendar).toHaveBeenCalledWith({
      venueId: 1,
      calendarDate: expect.any(String),
      businessStatus: 'SPECIAL_HOURS',
      openTime: '09:30:00',
      closeTime: '17:30:00',
      reasonCode: 'SPECIAL_HOURS',
      reasonText: '暑期开放'
    })
    expect(inventoryApiMock.calendar).toHaveBeenCalledTimes(2)
  })

  it('backfills business calendar form from loaded inventory response', async () => {
    inventoryApiMock.calendar.mockResolvedValueOnce({
      businessCalendar: {
        id: 7,
        venueId: 1,
        calendarDate: '2026-07-02',
        businessStatus: 'SPECIAL_HOURS',
        openTime: '09:30:00',
        closeTime: '17:30:00',
        reasonText: '暑期开放'
      },
      slots: [],
      summary: {
        availableSlots: 0,
        reservedSlots: 0,
        unavailableSlots: 0,
        manualHoldSlots: 0,
        closedSlots: 0,
        bufferSlots: 0
      }
    })
    const wrapper = mountView()
    await flushPromises()

    const calendarForm = (wrapper.vm as unknown as {
      calendarForm: {
        businessStatus: string
        openTime: string
        closeTime: string
        reasonText: string
      }
    }).calendarForm

    expect(calendarForm).toMatchObject({
      businessStatus: 'SPECIAL_HOURS',
      openTime: '09:30:00',
      closeTime: '17:30:00',
      reasonText: '暑期开放'
    })
  })

  it('hides and guards business calendar save without manage permission', async () => {
    authStoreMock.hasPermission.mockReturnValue(false)
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).not.toContain('保存营业日历')

    await (wrapper.vm as unknown as { saveCalendar: () => Promise<void> }).saveCalendar()

    expect(inventoryApiMock.saveCalendar).not.toHaveBeenCalled()
  })
})
