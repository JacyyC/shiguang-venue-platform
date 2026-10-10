import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SpacesView from './SpacesView.vue'

const venueApiMock = vi.hoisted(() => ({
  spaces: vi.fn()
}))

const venueAdminApiMock = vi.hoisted(() => ({
  manageable: vi.fn(),
  createVenue: vi.fn(),
  updateVenue: vi.fn(),
  createSpace: vi.fn(),
  updateSpace: vi.fn(),
  updateSpaceStatus: vi.fn(),
  resourceRule: vi.fn(),
  configureResourceRule: vi.fn(),
  maintenances: vi.fn(),
  createMaintenance: vi.fn(),
  cancelMaintenance: vi.fn()
}))

const reservationApiMock = vi.hoisted(() => ({
  availability: vi.fn()
}))

const menuStoreMock = vi.hoisted(() => ({
  hasPermission: vi.fn()
}))

const messageMock = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
  info: vi.fn(),
  error: vi.fn()
}))

vi.mock('@/api/venue', () => ({
  venueApi: venueApiMock,
  venueAdminApi: venueAdminApiMock
}))
vi.mock('@/api/reservation', () => ({ reservationApi: reservationApiMock }))
vi.mock('@/stores/menu', () => ({ useMenuStore: () => menuStoreMock }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: messageMock,
    ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) }
  }
})

const stubs = {
  'el-alert': { props: ['title', 'description'], template: '<div><strong>{{ title }}</strong><span>{{ description }}</span></div>' },
  'el-button': {
    props: ['disabled', 'loading', 'type'],
    emits: ['click'],
    template: '<button type="button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
  },
  'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
  'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-icon': { template: '<span><slot /></span>' },
  'el-input': { template: '<input />' },
  'el-input-number': { template: '<input type="number" />' },
  'el-option': true,
  'el-radio-group': { template: '<div><slot /></div>' },
  'el-radio-button': { template: '<button type="button"><slot /></button>' },
  'el-select': { template: '<select><slot /></select>' },
  'el-skeleton': { template: '<div><slot /></div>' },
  'el-table': { template: '<div><slot /></div>' },
  'el-table-column': true,
  'el-tag': { template: '<span><slot /></span>' },
  'el-time-picker': { template: '<input type="time" />' },
  'el-date-picker': { template: '<input type="date" />' },
  Plus: true,
  Delete: true,
  StatusTag: { template: '<span />' },
  TimeSlotGrid: { template: '<div />' }
}

const mountView = () => mount(SpacesView, {
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

const LazyPersistentDialogStub = defineComponent({
  props: { modelValue: Boolean },
  data() {
    return { rendered: this.modelValue }
  },
  watch: {
    modelValue(visible: boolean) {
      if (visible) this.rendered = true
    }
  },
  template: '<div v-if="rendered" v-show="modelValue"><slot /><slot name="footer" /></div>'
})

const StickyInputNumberStub = defineComponent({
  props: { modelValue: Number },
  data() {
    return { displayedValue: this.modelValue }
  },
  template: '<input class="sticky-input-number" type="number" :value="displayedValue" />'
})

const mountViewWithPersistentDialog = () => mount(SpacesView, {
  global: {
    stubs: {
      ...stubs,
      'el-dialog': LazyPersistentDialogStub,
      'el-input-number': StickyInputNumberStub
    },
    directives: {
      loading: {
        mounted: () => undefined,
        updated: () => undefined
      }
    }
  }
})

describe('SpacesView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    menuStoreMock.hasPermission.mockReturnValue(true)
    venueAdminApiMock.manageable.mockResolvedValue([])
    venueAdminApiMock.createVenue.mockResolvedValue({
      id: 21,
      name: '东区综合体育馆',
      area: '东区',
      address: '东区体育路 1 号',
      status: 'ACTIVE'
    })
    venueAdminApiMock.createSpace.mockResolvedValue({ id: 51, venueId: 21, name: '羽毛球 1 号场', status: 'ACTIVE' })
    venueAdminApiMock.resourceRule.mockResolvedValue({
      id: 80,
      spaceId: 51,
      resourceCategory: 'STUDY_ROOM',
      bookingMode: 'ASSIGNED_SEAT',
      versionNo: 2,
      status: 'ACTIVE',
      seats: [{ id: 501, seatCode: 'A-01', seatLabel: 'A区01座', status: 'ACTIVE' }]
    })
    venueAdminApiMock.configureResourceRule.mockResolvedValue({
      id: 81,
      spaceId: 51,
      resourceCategory: 'STUDY_ROOM',
      bookingMode: 'ASSIGNED_SEAT',
      versionNo: 3,
      status: 'ACTIVE',
      seats: [{ id: 601, seatCode: 'A-01', seatLabel: 'A区01座', status: 'ACTIVE' }]
    })
    venueApiMock.spaces.mockResolvedValue([])
    reservationApiMock.availability.mockResolvedValue([])
  })

  it('lets a system administrator create the first venue, selects it, then creates a space', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('尚未创建场馆')
    const createVenueButton = wrapper.findAll('button').find((button) => button.text().includes('新增场馆'))
    expect(createVenueButton).toBeDefined()
    await createVenueButton?.trigger('click')

    const vm = wrapper.vm as unknown as {
      venueId?: number
      venueForm: { name: string; area: string; address: string; phone: string; openTime: string; closeTime: string; description: string }
      spaceForm: { venueId?: number; name: string; type: string; code: string; pricePerHour: number; status: string }
      saveVenue: () => Promise<void>
      saveSpace: () => Promise<void>
    }
    vm.venueForm.name = '东区综合体育馆'
    vm.venueForm.area = '东区'
    vm.venueForm.address = '东区体育路 1 号'
    vm.venueForm.phone = '010-12345678'
    vm.venueForm.openTime = '08:00:00'
    vm.venueForm.closeTime = '22:00:00'
    vm.venueForm.description = '综合运动场馆'

    await vm.saveVenue()
    await flushPromises()

    expect(venueAdminApiMock.createVenue).toHaveBeenCalledWith({
      name: '东区综合体育馆',
      area: '东区',
      address: '东区体育路 1 号',
      phone: '010-12345678',
      openTime: '08:00:00',
      closeTime: '22:00:00',
      description: '综合运动场馆',
      status: 'ACTIVE'
    })
    expect(vm.venueId).toBe(21)
    expect(vm.spaceForm.venueId).toBe(21)
    expect(venueApiMock.spaces).toHaveBeenCalledWith(21)

    vm.spaceForm.name = '羽毛球 1 号场'
    vm.spaceForm.type = 'BADMINTON'
    vm.spaceForm.code = 'B-01'
    vm.spaceForm.pricePerHour = 40
    vm.spaceForm.status = 'ACTIVE'
    await vm.saveSpace()

    expect(venueAdminApiMock.createSpace).toHaveBeenCalledWith(expect.objectContaining({
      venueId: 21,
      name: '羽毛球 1 号场',
      type: 'BADMINTON'
    }))
  })

  it('shows a precise scope state and no creation action for a venue administrator without venue access', async () => {
    menuStoreMock.hasPermission.mockReturnValue(false)
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('尚未分配可管理场馆')
    expect(wrapper.text()).toContain('分配具体场馆范围')
    expect(wrapper.findAll('button').some((button) => button.text().includes('新增场馆'))).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text().includes('新增场地'))).toBe(false)
    expect(venueApiMock.spaces).not.toHaveBeenCalled()

    const vm = wrapper.vm as unknown as { venueForm: { name: string }; saveVenue: () => Promise<void> }
    vm.venueForm.name = '不应创建的场馆'
    await vm.saveVenue()
    expect(venueAdminApiMock.createVenue).not.toHaveBeenCalled()
  })

  it('keeps the first-venue form usable and explains a creation failure', async () => {
    venueAdminApiMock.createVenue.mockRejectedValueOnce(new Error('场馆名称已存在'))
    const wrapper = mountView()
    await flushPromises()
    const createVenueButton = wrapper.findAll('button').find((button) => button.text().includes('新增场馆'))
    await createVenueButton?.trigger('click')

    const vm = wrapper.vm as unknown as {
      venueForm: { name: string }
      venueDialogVisible: boolean
      saveVenue: () => Promise<void>
    }
    vm.venueForm.name = '重复场馆'
    await vm.saveVenue()

    expect(messageMock.error).toHaveBeenCalledWith('场馆名称已存在')
    expect(vm.venueDialogVisible).toBe(true)
  })

  it('rebuilds numeric controls with current values when editing after opening the create form', async () => {
    venueAdminApiMock.manageable.mockResolvedValue([
      { id: 21, name: '东区综合馆', status: 'ACTIVE' }
    ])
    const space = {
      id: 51,
      venueId: 21,
      name: '羽毛球 1 号场',
      type: 'BADMINTON',
      code: 'B-01',
      capacity: 4,
      pricePerHour: 50,
      status: 'ACTIVE'
    }
    venueApiMock.spaces.mockResolvedValue([space])
    const wrapper = mountViewWithPersistentDialog()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      spaceDialogVisible: boolean
      openCreate: () => void
      openEdit: (item: typeof space) => void
    }
    vm.openCreate()
    await flushPromises()
    expect(wrapper.findAll('.sticky-input-number').map((input) => input.attributes('value')))
      .toEqual(['40', undefined])

    vm.spaceDialogVisible = false
    await flushPromises()
    vm.openEdit(space)
    await flushPromises()

    expect(wrapper.findAll('.sticky-input-number').map((input) => input.attributes('value')))
      .toEqual(['50', '4'])
  })

  it('loads the selected venue scope for a venue administrator with a valid assignment', async () => {
    menuStoreMock.hasPermission.mockReturnValue(false)
    venueAdminApiMock.manageable.mockResolvedValue([
      { id: 7, name: '北区体育馆', area: '北区', address: '北区运动路 1 号', status: 'ACTIVE' }
    ])

    const wrapper = mountView()
    await flushPromises()

    expect(venueApiMock.spaces).toHaveBeenCalledWith(7)
    expect(wrapper.findAll('button').some((button) => button.text().includes('新增场地'))).toBe(true)
    expect(wrapper.findAll('button').some((button) => button.text().includes('新增场馆'))).toBe(false)
  })

  it('loads and saves an assigned-seat rule through the scoped admin API', async () => {
    venueAdminApiMock.manageable.mockResolvedValue([
      { id: 21, name: '东区综合馆', status: 'ACTIVE' }
    ])
    const space = {
      id: 51,
      venueId: 21,
      name: '自习区',
      type: 'STUDY',
      pricePerHour: 20,
      status: 'ACTIVE'
    }
    venueApiMock.spaces.mockResolvedValue([space])
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      resourceRuleForm: {
        resourceCategory: 'SPORTS_FIELD' | 'STUDY_ROOM' | 'ACTIVITY_ROOM'
        bookingMode: 'EXCLUSIVE' | 'CAPACITY' | 'ASSIGNED_SEAT'
        seats: Array<{ seatCode: string; seatLabel: string }>
        reasonText: string
      }
      openResourceRule: (item: typeof space) => Promise<void>
      saveResourceRule: () => Promise<void>
    }
    await vm.openResourceRule(space)

    expect(venueAdminApiMock.resourceRule).toHaveBeenCalledWith(51)
    expect(vm.resourceRuleForm.bookingMode).toBe('ASSIGNED_SEAT')
    expect(vm.resourceRuleForm.seats).toEqual([{ seatCode: 'A-01', seatLabel: 'A区01座' }])

    vm.resourceRuleForm.reasonText = '调整自习区座位规则'
    await vm.saveResourceRule()

    expect(venueAdminApiMock.configureResourceRule).toHaveBeenCalledWith(51, {
      resourceCategory: 'STUDY_ROOM',
      bookingMode: 'ASSIGNED_SEAT',
      seats: [{ seatCode: 'A-01', seatLabel: 'A区01座' }],
      reasonText: '调整自习区座位规则'
    })
    expect(messageMock.success).toHaveBeenCalledWith('预订规则新版本已生效')
  })

  it('does not expose or save a stale rule when scoped rule loading fails', async () => {
    venueAdminApiMock.manageable.mockResolvedValue([
      { id: 21, name: '东区综合馆', status: 'ACTIVE' }
    ])
    const space = {
      id: 51,
      venueId: 21,
      name: '自习区',
      type: 'STUDY',
      pricePerHour: 20,
      status: 'ACTIVE'
    }
    venueApiMock.spaces.mockResolvedValue([space])
    venueAdminApiMock.resourceRule.mockRejectedValueOnce(new Error('没有该场馆管理权限'))
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      openResourceRule: (item: typeof space) => Promise<void>
      saveResourceRule: () => Promise<void>
    }

    await vm.openResourceRule(space)
    await flushPromises()

    expect(wrapper.text()).toContain('预订规则加载失败')
    expect(wrapper.text()).toContain('没有该场馆管理权限')
    await vm.saveResourceRule()
    expect(venueAdminApiMock.configureResourceRule).not.toHaveBeenCalled()
  })
})
