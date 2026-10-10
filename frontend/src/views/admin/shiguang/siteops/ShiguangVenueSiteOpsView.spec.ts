import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

const siteOpsApiMock = vi.hoisted(() => ({
  shifts: vi.fn(),
  createShift: vi.fn(),
  updateShift: vi.fn(),
  clockIn: vi.fn(),
  clockOut: vi.fn(),
  tasks: vi.fn(),
  createTask: vi.fn(),
  updateTask: vi.fn(),
  startTask: vi.fn(),
  completeTask: vi.fn(),
  cancelTask: vi.fn(),
  handovers: vi.fn(),
  submitHandover: vi.fn(),
  acceptHandover: vi.fn(),
  incidents: vi.fn(),
  recordIncident: vi.fn(),
  resolveIncident: vi.fn()
}))
const assetsApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  changeStatus: vi.fn(),
  events: vi.fn(),
  inspections: vi.fn(),
  recordInspection: vi.fn(),
  repairs: vi.fn(),
  requestRepair: vi.fn(),
  startRepair: vi.fn(),
  completeRepair: vi.fn(),
  cancelRepair: vi.fn(),
  context: vi.fn()
}))
const showToast = vi.hoisted(() => vi.fn())

vi.mock('@/api/siteOps', () => ({ siteOpsApi: siteOpsApiMock }))
vi.mock('@/api/assets', () => ({ assetsApi: assetsApiMock }))
vi.mock('@/api/venue', () => ({
  venueApi: { spaces: vi.fn().mockResolvedValue([{ id: 73, name: '羽毛球 2 号场', venueId: 101 }]) },
  venueAdminApi: { manageable: vi.fn().mockResolvedValue([]) }
}))

import ShiguangVenueSiteOpsView from './ShiguangVenueSiteOpsView.vue'
import { useVenueAccessStore } from '@/stores/venueAccess'

const granted = new Set<string>(['venue-admin:view', 'site:ops:manage', 'asset:manage', 'asset:maintenance:manage'])

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: (key: string) => granted.has(key) })
}))
vi.mock('@/stores/app', () => ({
  useAppStore: () => ({ showToast, toastMessage: '' })
}))

const shift = (overrides: Record<string, unknown> = {}) => ({
  id: 41,
  shiftNo: 'SITE_SHIFT20260913160000A1B2C3D4',
  venueId: 101,
  spaceId: 73,
  shiftDate: '2026-09-13',
  shiftName: '晚班 · 到场与闭馆',
  roleCode: 'FLOOR',
  assignedUserId: 7,
  plannedStartTime: '2026-09-13T16:00:00',
  plannedEndTime: '2026-09-13T22:00:00',
  status: 'IN_PROGRESS',
  handoverStatus: 'NONE',
  ...overrides
})

const task = (overrides: Record<string, unknown> = {}) => ({
  id: 1,
  taskNo: 'SITE_TASK20260913A',
  venueId: 101,
  shiftId: 41,
  title: '羽毛球区灯具复核',
  taskType: 'INSPECTION',
  priority: 'HIGH',
  status: 'IN_PROGRESS',
  dueTime: '2026-09-13T18:30:00',
  assetId: 501,
  ...overrides
})

const incident = (overrides: Record<string, unknown> = {}) => ({
  id: 901,
  incidentNo: 'SITE_INCIDENT20260913A',
  venueId: 101,
  shiftId: 41,
  incidentType: 'ASSET_FAILURE',
  severity: 'MEDIUM',
  status: 'OPEN',
  occurredAt: '2026-09-13T18:10:00',
  description: '羽毛球2号场照明短暂闪烁',
  ...overrides
})

const asset = (overrides: Record<string, unknown> = {}) => ({
  id: 501,
  assetNo: 'ASSET20260913A',
  venueId: 101,
  spaceId: 73,
  assetType: 'LIGHTING',
  name: '2号场照明控制器',
  status: 'INSPECTION_DUE',
  responsibleUserId: 9,
  ...overrides
})

const page = <T>(records: T[]) => ({ records, total: records.length, pageNum: 1, pageSize: 100 })

function allow(...keys: string[]): void {
  granted.clear()
  for (const key of keys) granted.add(key)
}

beforeEach(() => {
  vi.clearAllMocks()
  allow('venue-admin:view', 'site:ops:manage', 'asset:manage', 'asset:maintenance:manage')
  setActivePinia(createPinia())
  const venueAccess = useVenueAccessStore()
  venueAccess.venues = [{ id: 101, name: '北岸生活场馆', area: '', address: '', status: 'ACTIVE' }] as never
  venueAccess.activeVenueId = 101

  siteOpsApiMock.shifts.mockResolvedValue(page([shift()]))
  siteOpsApiMock.tasks.mockResolvedValue(page([task(), task({ id: 2, title: '前台用品盘点', status: 'DONE', completionNote: '已核对' })]))
  siteOpsApiMock.handovers.mockResolvedValue(page([]))
  siteOpsApiMock.incidents.mockResolvedValue(page([incident()]))
  siteOpsApiMock.clockIn.mockResolvedValue(shift({ status: 'IN_PROGRESS' }))
  siteOpsApiMock.clockOut.mockResolvedValue(shift({ status: 'COMPLETED' }))
  siteOpsApiMock.createShift.mockResolvedValue(shift({ id: 42 }))
  siteOpsApiMock.updateShift.mockResolvedValue(shift())
  siteOpsApiMock.createTask.mockResolvedValue(task())
  siteOpsApiMock.updateTask.mockResolvedValue(task())
  siteOpsApiMock.startTask.mockResolvedValue(task({ status: 'IN_PROGRESS' }))
  siteOpsApiMock.completeTask.mockResolvedValue(task({ status: 'EXCEPTION' }))
  siteOpsApiMock.cancelTask.mockResolvedValue(task({ status: 'CANCELED' }))
  siteOpsApiMock.submitHandover.mockResolvedValue({ id: 61, fromShiftId: 41, status: 'SUBMITTED' })
  siteOpsApiMock.acceptHandover.mockResolvedValue({ id: 61, status: 'ACCEPTED' })
  siteOpsApiMock.recordIncident.mockResolvedValue(incident())
  siteOpsApiMock.resolveIncident.mockResolvedValue(incident({ status: 'RESOLVED', resolution: '已复核' }))

  assetsApiMock.list.mockResolvedValue(page([asset(), asset({ id: 502, name: '便携清洁设备', status: 'IN_USE' })]))
  assetsApiMock.events.mockResolvedValue(page([
    { id: 1, assetId: 501, eventType: 'REPAIR_REQUESTED', fromStatus: 'IN_USE', toStatus: 'REPAIRING', occurredAt: '2026-09-13T09:30:00' }
  ]))
  assetsApiMock.inspections.mockResolvedValue(page([
    { id: 11, assetId: 501, inspectionResult: 'REPAIR_REQUIRED', riskLevel: 'MEDIUM', inspectedAt: '2026-09-13T09:00:00' }
  ]))
  assetsApiMock.repairs.mockResolvedValue(page([
    { id: 21, assetId: 501, status: 'REQUESTED', failureDescription: '照明闪烁', unavailableBlockId: 88 }
  ]))
  assetsApiMock.create.mockResolvedValue(asset({ id: 503 }))
  assetsApiMock.update.mockResolvedValue(asset())
  assetsApiMock.changeStatus.mockResolvedValue(asset({ status: 'OUT_OF_SERVICE' }))
  assetsApiMock.recordInspection.mockResolvedValue({ id: 12 })
  assetsApiMock.requestRepair.mockResolvedValue({ id: 21 })
  assetsApiMock.startRepair.mockResolvedValue({ id: 21, status: 'IN_PROGRESS' })
  assetsApiMock.completeRepair.mockResolvedValue({ id: 21, status: 'COMPLETED' })
  assetsApiMock.cancelRepair.mockResolvedValue({ id: 21, status: 'CANCELED' })

  const proto = (globalThis.HTMLDialogElement as unknown as { prototype: Record<string, () => void> }).prototype
  proto.showModal = proto.showModal ?? (() => {})
  proto.close = proto.close ?? (() => {})
})

async function mountView(
  routePath = '/venue-admin/site-ops',
  routeName = 'VenueAdminSiteOps'
): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/venue-admin/site-ops', name: 'VenueAdminSiteOps', component: ShiguangVenueSiteOpsView },
      { path: '/venue-admin/assets', name: 'VenueAdminAssets', component: ShiguangVenueSiteOpsView },
      { path: '/venue-admin/calendar', name: 'VenueAdminCalendar', component: { template: '<div />' } }
    ]
  })
  await router.push(routePath)
  await router.isReady()
  const wrapper = mount(ShiguangVenueSiteOpsView, { global: { plugins: [router] } })
  await flushPromises()
  await flushPromises()
  await flushPromises()
  return { wrapper, router }
}

describe('ShiguangVenueSiteOpsView 工作面与范围', () => {
  it('site-ops 路由进入当班日志，按真实当日与场馆读取', async () => {
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="sga-siteops-root"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('把现场的事，交代清楚')
    expect(wrapper.text()).toContain('晚班 · 到场与闭馆')
    expect(wrapper.text()).toContain('16:00—22:00')
    expect(wrapper.find('[data-test="tab-shift"]').attributes('aria-current')).toBe('true')
    expect(siteOpsApiMock.shifts).toHaveBeenCalledWith(
      expect.objectContaining({ venueId: 101, date: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/) }),
      expect.objectContaining({ skipGlobalErrorToast: true })
    )
    expect(siteOpsApiMock.tasks).toHaveBeenCalledWith(expect.objectContaining({ shiftId: 41 }), expect.anything())
  })

  it('assets 路由进入设备档案并渲染台账与四项事实', async () => {
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    expect(wrapper.find('[data-test="tab-assets"]').attributes('aria-current')).toBe('true')
    const detail = wrapper.find('[data-test="asset-detail"]')
    expect(detail.text()).toContain('2号场照明控制器')
    expect(detail.text()).toContain('待巡检')
    expect(detail.text()).toContain('负责人 #9')
    expect(detail.text()).toContain('羽毛球 2 号场')
    expect(detail.find('[data-test="lifecycle"]').text()).toContain('维修申请 · 在用 → 维修中')
    expect(detail.find('a.f-link').attributes('href')).toBe('/venue-admin/calendar')
  })

  it('没有现场权限时不发请求并渲染该面空态', async () => {
    allow('venue-admin:view', 'asset:manage')
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="denied-shift"]').exists()).toBe(true)
    expect(siteOpsApiMock.shifts).not.toHaveBeenCalled()
    expect(assetsApiMock.list).toHaveBeenCalled()
  })

  it('没有维护权限时不读巡检与维修并显示权限提示', async () => {
    allow('venue-admin:view', 'asset:manage')
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    expect(assetsApiMock.inspections).not.toHaveBeenCalled()
    expect(assetsApiMock.repairs).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="maintenance-denied"]').text()).toContain(
      '当前有资产台账权限，没有巡检维修权限。维护记录与动作不可进入。'
    )
    expect(wrapper.find('[data-test="asset-detail"]').text()).toContain('维护权限未授权')
  })
})

describe('ShiguangVenueSiteOpsView 班次与任务', () => {
  it('已计划班次签到后重新读取', async () => {
    siteOpsApiMock.shifts.mockResolvedValue(page([shift({ status: 'PLANNED' })]))
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="clock-in"]').trigger('click')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(siteOpsApiMock.clockIn).toHaveBeenCalledWith(41, expect.objectContaining({ skipGlobalErrorToast: true }))
    expect(showToast).toHaveBeenCalledWith('班次已签到')
  })

  it('仍有开放任务且交接未接收时先给出签退前置提示', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="clock-out"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂时不能签退')
    expect(wrapper.text()).toContain('本班仍有 1 项开放任务，交接尚未接收。')
    expect(siteOpsApiMock.clockOut).not.toHaveBeenCalled()
  })

  it('交接已接收后允许签退', async () => {
    siteOpsApiMock.handovers.mockResolvedValue(page([{ id: 61, fromShiftId: 41, status: 'ACCEPTED' }]))
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="clock-out"]').trigger('click')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(siteOpsApiMock.clockOut).toHaveBeenCalledWith(41, expect.anything())
  })

  it('异常完成走 complete 端点并带 exceptionCode', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="open-task-1"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="reason"]').setValue('2 号场照明仍不稳定')
    await wrapper.find('[name="result"]').setValue('EXCEPTION')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(siteOpsApiMock.completeTask).toHaveBeenCalledWith(
      1,
      { completionNote: '2 号场照明仍不稳定', exceptionCode: 'SITE_TASK_EXCEPTION' },
      expect.anything()
    )
  })

  it('取消任务走 cancel 端点', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="open-task-1"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="reason"]').setValue('重复任务')
    await wrapper.find('[name="result"]').setValue('CANCELED')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(siteOpsApiMock.cancelTask).toHaveBeenCalledWith(1, { reason: '重复任务' }, expect.anything())
  })

  it('终态任务不再提供开始与改写入口', async () => {
    siteOpsApiMock.tasks.mockResolvedValue(page([task({ id: 3, status: 'DONE' })]))
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="open-task-3"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('本任务已进入终态，不再开始或改写核心归属。')
    expect(wrapper.find('[data-test="task-start"]').exists()).toBe(false)
  })
})

describe('ShiguangVenueSiteOpsView 交接与异常', () => {
  it('交接按接收班次提交，接班人取自该班次的指派人员', async () => {
    siteOpsApiMock.shifts.mockResolvedValue(page([
      shift(),
      shift({ id: 42, shiftName: '夜间保障', assignedUserId: 8, plannedStartTime: '2026-09-13T22:00:00', plannedEndTime: '2026-09-14T06:00:00' })
    ]))
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="handover-new"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[name="fromShiftId"]').exists()).toBe(false)
    expect(wrapper.find('[name="toUserId"]').exists()).toBe(false)
    await wrapper.find('[name="toShift"]').setValue('42')
    expect(wrapper.text()).toContain('交班：晚班 · 到场与闭馆 · 值班员 #7 → 接班：夜间保障')
    await wrapper.find('[name="reason"]').setValue('灯具待复检')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(siteOpsApiMock.submitHandover).toHaveBeenCalledWith(
      expect.objectContaining({ venueId: 101, fromShiftId: 41, fromUserId: 7, toShiftId: 42, toUserId: 8, note: '灯具待复检' }),
      expect.anything()
    )
    expect(showToast).toHaveBeenCalledWith('交接记录已提交')
  })

  it('未选接收班次或接收班次未指派人员时不发请求', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="handover-new"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="reason"]').setValue('灯具待复检')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('请选择本班之外的接收班次。')
    expect(siteOpsApiMock.submitHandover).not.toHaveBeenCalled()

    siteOpsApiMock.shifts.mockResolvedValue(page([shift(), shift({ id: 42, assignedUserId: undefined })]))
    const second = await mountView()
    await second.wrapper.find('[data-test="handover-new"]').trigger('click')
    await flushPromises()
    await second.wrapper.find('[name="toShift"]').setValue('42')
    await second.wrapper.find('[name="reason"]').setValue('灯具待复检')
    await second.wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(second.wrapper.find('[data-test="dialog-error"]').text()).toBe('接收班次尚未指派人员，请先为该班次指派人员。')
    expect(siteOpsApiMock.submitHandover).not.toHaveBeenCalled()
  })

  it('接收 SUBMITTED 交接后状态推进', async () => {
    siteOpsApiMock.handovers.mockResolvedValue(page([{ id: 61, fromShiftId: 41, toShiftId: 42, status: 'SUBMITTED', note: '待复检' }]))
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="handover-status"]').text()).toBe('已提交')
    await wrapper.find('[data-test="handover-accept"]').trigger('click')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(siteOpsApiMock.acceptHandover).toHaveBeenCalledWith(61, expect.anything())
  })

  it('异常记录带服务端必填的类型与等级', async () => {
    const { wrapper } = await mountView()
    await openIncidentsTab(wrapper)
    await wrapper.find('[data-test="new-incident"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="title"]').setValue('泳池入口湿滑')
    await wrapper.find('[name="severity"]').setValue('HIGH')
    await wrapper.find('[name="incidentType"]').setValue('SAFETY')
    await wrapper.find('[name="reason"]').setValue('已放置防滑提示')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(siteOpsApiMock.recordIncident).toHaveBeenCalledWith(
      expect.objectContaining({ venueId: 101, shiftId: 41, incidentType: 'SAFETY', severity: 'HIGH' }),
      expect.anything()
    )
  })

  it('解决异常只更新异常记录', async () => {
    const { wrapper } = await mountView()
    await openIncidentsTab(wrapper)
    await wrapper.find('[data-test="open-incident-901"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('此结果只更新异常记录，相关设备与库存需分别处理。')
    await wrapper.find('[name="reason"]').setValue('已复核并恢复')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(siteOpsApiMock.resolveIncident).toHaveBeenCalledWith(901, { resolution: '已复核并恢复' }, expect.anything())
  })
})

describe('ShiguangVenueSiteOpsView 资产与库存显式关联', () => {
  it('勾选联动但目标态不支持时按服务端语义拦截', async () => {
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    await wrapper.find('[data-test="asset-status"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="status"]').setValue('OUT_OF_SERVICE')
    await wrapper.find('[name="block"]').setValue(true)
    await wrapper.find('[name="blockStart"]').setValue('2026-09-13T22:00')
    await wrapper.find('[name="blockEnd"]').setValue('2026-09-13T19:00')
    await wrapper.find('[name="reason"]').setValue('照明检修')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('不可售开始时间必须早于结束时间。')
    expect(assetsApiMock.changeStatus).not.toHaveBeenCalled()
  })

  it('显式时间窗随状态变更提交，未勾选时不提交联动', async () => {
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    await wrapper.find('[data-test="asset-status"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="status"]').setValue('OUT_OF_SERVICE')
    await wrapper.find('[name="block"]').setValue(true)
    await wrapper.find('[name="blockStart"]').setValue('2026-09-13T19:00')
    await wrapper.find('[name="blockEnd"]').setValue('2026-09-13T22:00')
    await wrapper.find('[name="reason"]').setValue('照明检修')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(assetsApiMock.changeStatus).toHaveBeenCalledWith(
      501,
      expect.objectContaining({
        toStatus: 'OUT_OF_SERVICE',
        reasonText: '照明检修',
        blockStartTime: '2026-09-13T19:00:00',
        blockEndTime: '2026-09-13T22:00:00',
        releaseUnavailableBlock: false
      }),
      expect.anything()
    )
  })

  it('释放勾选只在恢复使用时提交', async () => {
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    await wrapper.find('[data-test="asset-status"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="status"]').setValue('OUT_OF_SERVICE')
    await wrapper.find('[name="release"]').setValue(true)
    await wrapper.find('[name="reason"]').setValue('恢复使用')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('只有恢复使用时可释放资产关联块。')
    await wrapper.find('[name="status"]').setValue('IN_USE')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(assetsApiMock.changeStatus).toHaveBeenCalledWith(
      501,
      expect.objectContaining({ toStatus: 'IN_USE', releaseUnavailableBlock: true }),
      expect.anything()
    )
  })

  it('巡检结果按真实字典提交', async () => {
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    await wrapper.find('[data-test="inspection"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="result"]').setValue('ATTENTION')
    await wrapper.find('[name="reason"]').setValue('需关注')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(assetsApiMock.recordInspection).toHaveBeenCalledWith(
      501,
      expect.objectContaining({ inspectionResult: 'ATTENTION', issueSummary: '需关注' }),
      expect.anything()
    )
  })

  it('维修动作按真实端点提交并透传释放选择', async () => {
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    await wrapper.find('[data-test="repair-start"]').trigger('click')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(assetsApiMock.startRepair).toHaveBeenCalledWith(21, expect.anything())

    await wrapper.find('[data-test="repair-cancel"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="reason"]').setValue('改约供应商')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(assetsApiMock.cancelRepair).toHaveBeenCalledWith(
      21,
      { reason: '改约供应商', cancelUnavailableBlock: false },
      expect.anything()
    )
  })

  it('发起维修要求故障描述且费用不为负', async () => {
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    assetsApiMock.repairs.mockResolvedValue(page([]))
    await selectSecondAsset(wrapper)
    await wrapper.find('[data-test="repair-request"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="cost"]').setValue(-1)
    await wrapper.find('[name="reason"]').setValue('泵体异响')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('费用不能为负数。')
    await wrapper.find('[name="cost"]').setValue(180)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(assetsApiMock.requestRepair).toHaveBeenCalledWith(
      502,
      expect.objectContaining({ failureDescription: '泵体异响', estimatedCostAmount: 180 }),
      expect.anything()
    )
  })

  it('已报废资产不提供状态变更入口', async () => {
    assetsApiMock.list.mockResolvedValue(page([asset({ status: 'RETIRED' })]))
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    expect(wrapper.find('[data-test="asset-status"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="asset-detail"]').text()).toContain('已报废资产不能重新启用，状态不再改写。')
  })

  it('服务端 409 原文留在弹层且不关闭', async () => {
    assetsApiMock.changeStatus.mockRejectedValue({ response: { data: { message: '资产状态已变更' } } })
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    await wrapper.find('[data-test="asset-status"]').trigger('click')
    await flushPromises()
    await wrapper.find('[name="reason"]').setValue('停用')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-error"]').text()).toBe('资产状态已变更')
    expect(wrapper.find('[data-test="dialog-confirm"]').exists()).toBe(true)
  })

  it('设备建档要求名称并自动生成编号说明', async () => {
    const { wrapper } = await mountView('/venue-admin/assets', 'VenueAdminAssets')
    await wrapper.find('[data-test="new-asset"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('资产编号在建档时自动生成。')
    await wrapper.find('[name="name"]').setValue('新风机')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(assetsApiMock.create).toHaveBeenCalledWith(
      expect.objectContaining({ venueId: 101, name: '新风机', assetType: 'EQUIPMENT' }),
      expect.anything()
    )
  })
})

async function openIncidentsTab(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.find('[data-test="tab-incidents"]').trigger('click')
  await flushPromises()
}

async function selectSecondAsset(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.find('[data-test="pick-asset-502"]').trigger('click')
  await flushPromises()
  await flushPromises()
}

describe('ShiguangVenueSiteOpsView 焦点与跨页签', () => {
  it('取消弹层不提交任何写请求', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="new-shift"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog-cancel"]').exists()).toBe(true)
    await wrapper.find('[data-test="dialog-cancel"]').trigger('click')
    await flushPromises()
    expect(siteOpsApiMock.createShift).not.toHaveBeenCalled()
    // jsdom 不派发 dialog 的 close 事件：弹层关闭、焦点回到触发控件与 Esc 恢复在 S04 隔离浏览器旅程核对（与第二批同口径）
  })

  it('任务行的设备档案跳转切到设备页签并选中该资产', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test="task-asset-1"]').trigger('click')
    await flushPromises()
    await flushPromises()
    expect(wrapper.find('[data-test="tab-assets"]').attributes('aria-current')).toBe('true')
    expect(wrapper.find('[data-test="pick-asset-501"]').attributes('aria-pressed')).toBe('true')
  })
})
