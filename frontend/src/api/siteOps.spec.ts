import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn()
}))

vi.mock('./http', () => ({
  default: httpMock
}))

describe('siteOpsApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('posts shift creation to admin site endpoint', async () => {
    const { siteOpsApi } = await import('./siteOps')
    httpMock.post.mockResolvedValue({ id: 1, status: 'PLANNED' })

    await siteOpsApi.createShift({ venueId: 1, shiftName: '早班', roleCode: 'FRONT_DESK', assignedUserId: 2 })

    expect(httpMock.post).toHaveBeenCalledWith(
      '/admin/site/shifts',
      expect.objectContaining({ venueId: 1 }),
      undefined
    )
  })

  it('uses locked B8 site ops endpoint contracts', async () => {
    const { siteOpsApi } = await import('./siteOps')
    const query = { venueId: 1, pageNum: 1, pageSize: 20 }
    const taskPayload = { venueId: 1, title: '巡场', taskType: 'INSPECTION' }
    const handoverPayload = { venueId: 1, fromShiftId: 11, toShiftId: 12, note: '正常' }
    const incidentPayload = { venueId: 1, incidentType: 'EQUIPMENT', severity: 'MEDIUM', description: '灯光异常' }

    httpMock.get.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 20 })
    httpMock.post.mockResolvedValue({ id: 1 })

    await siteOpsApi.shifts(query)
    await siteOpsApi.updateShift(11, { shiftName: '晚班' })
    await siteOpsApi.clockIn(11)
    await siteOpsApi.clockOut(11)
    await siteOpsApi.tasks(query)
    await siteOpsApi.createTask(taskPayload)
    await siteOpsApi.updateTask(21, { title: '复查灯光' })
    await siteOpsApi.startTask(21)
    await siteOpsApi.completeTask(21, { completionNote: '已处理', evidence: '照片' })
    await siteOpsApi.cancelTask(21, { reason: '重复任务' })
    await siteOpsApi.handovers(query)
    await siteOpsApi.submitHandover(handoverPayload)
    await siteOpsApi.acceptHandover(31)
    await siteOpsApi.incidents(query)
    await siteOpsApi.recordIncident(incidentPayload)
    await siteOpsApi.resolveIncident(41, { resolution: '已处理' })

    expect(httpMock.get).toHaveBeenCalledWith('/admin/site/shifts', { params: query })
    expect(httpMock.put).toHaveBeenCalledWith('/admin/site/shifts/11', { shiftName: '晚班' }, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/site/shifts/11/clock-in', undefined, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/site/shifts/11/clock-out', undefined, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/site/tasks', { params: query })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/site/tasks', taskPayload, undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/site/tasks/21', { title: '复查灯光' }, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/site/tasks/21/start', undefined, undefined)
    expect(httpMock.post).toHaveBeenCalledWith(
      '/admin/site/tasks/21/complete',
      { completionNote: '已处理', evidence: '照片' },
      undefined
    )
    expect(httpMock.post).toHaveBeenCalledWith('/admin/site/tasks/21/cancel', { reason: '重复任务' }, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/site/handovers', { params: query })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/site/handovers', handoverPayload, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/site/handovers/31/accept', undefined, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/site/incidents', { params: query })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/site/incidents', incidentPayload, undefined)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/site/incidents/41/resolve', { resolution: '已处理' }, undefined)
  })

  // SHIGUANG-BATCH-03 S03：当日范围改由服务端过滤（contracts.md B3-T2），并允许面内降级请求配置。
  it('sends the operational day filter and per-request config to the server', async () => {
    const { siteOpsApi } = await import('./siteOps')
    httpMock.get.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 50 })
    const isolated = { skipGlobalErrorToast: true, skipGlobalErrorRedirect: true }

    await siteOpsApi.shifts({ venueId: 3, date: '2026-09-13', pageSize: 50 }, isolated)
    await siteOpsApi.tasks({ shiftId: 41 }, isolated)
    await siteOpsApi.incidents({ shiftId: 41, status: 'OPEN' }, isolated)

    expect(httpMock.get).toHaveBeenNthCalledWith(
      1,
      '/admin/site/shifts',
      { params: { venueId: 3, date: '2026-09-13', pageSize: 50 }, ...isolated }
    )
    expect(httpMock.get).toHaveBeenNthCalledWith(2, '/admin/site/tasks', { params: { shiftId: 41 }, ...isolated })
    expect(httpMock.get).toHaveBeenNthCalledWith(
      3,
      '/admin/site/incidents',
      { params: { shiftId: 41, status: 'OPEN' }, ...isolated }
    )
  })
})
