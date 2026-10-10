import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn()
}))

vi.mock('./http', () => ({
  default: httpMock
}))

describe('assetsApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('posts repair request to asset repair endpoint', async () => {
    const { assetsApi } = await import('./assets')
    httpMock.post.mockResolvedValue({ id: 9, status: 'REQUESTED' })

    await assetsApi.requestRepair(7, { failureDescription: '灯光故障', estimatedCostAmount: 120 })

    expect(httpMock.post).toHaveBeenCalledWith(
      '/admin/assets/7/repairs',
      expect.objectContaining({
        failureDescription: '灯光故障'
      }),
      undefined
    )
  })

  it('uses locked B8 asset endpoint contracts', async () => {
    const { assetsApi } = await import('./assets')
    const query = { venueId: 1, status: 'IN_USE', pageNum: 1, pageSize: 20 }
    const assetPayload = { venueId: 1, assetType: 'LIGHTING', name: '一号灯光', supplierName: '本地供应商' }
    const statusPayload = { toStatus: 'OUT_OF_SERVICE', reasonCode: 'MANUAL', reasonText: '暂停使用' }
    const inspectionPayload = { inspectionResult: 'PASS', riskLevel: 'LOW', inspectedAt: '2026-07-06T09:00:00' }
    const repairPayload = { failureDescription: '灯光闪烁', estimatedCostAmount: 120 }
    const completePayload = { actualCostAmount: 90, resolution: '更换驱动', cancelUnavailableBlock: true }

    httpMock.get.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 20 })
    httpMock.post.mockResolvedValue({ id: 1 })
    httpMock.put.mockResolvedValue({ id: 1 })

    await assetsApi.list(query)
    await assetsApi.create(assetPayload)
    await assetsApi.changeStatus(7, statusPayload)
    await assetsApi.events(7)
    await assetsApi.inspections(query)
    await assetsApi.recordInspection(7, inspectionPayload)
    await assetsApi.repairs(query)
    await assetsApi.requestRepair(7, repairPayload)
    await assetsApi.startRepair(9)
    await assetsApi.completeRepair(9, completePayload)
    await assetsApi.cancelRepair(9, { reason: '重复报修', cancelUnavailableBlock: true })

    expect(httpMock.get).toHaveBeenCalledWith('/admin/assets', { params: query })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/assets', assetPayload, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/assets/7/status', statusPayload, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/assets/7/events', { params: undefined })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/assets/inspections', { params: query })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/assets/7/inspections', inspectionPayload, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/assets/repairs', { params: query })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/assets/7/repairs', repairPayload, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/assets/repairs/9/start', undefined, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/assets/repairs/9/complete', completePayload, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/assets/repairs/9/cancel', {
      reason: '重复报修',
      cancelUnavailableBlock: true
    }, undefined)
  })

  it('keeps out-of-service block window fields in status payloads', async () => {
    const { assetsApi } = await import('./assets')
    const payload = {
      toStatus: 'OUT_OF_SERVICE',
      reasonCode: 'ASSET_REPAIR',
      reasonText: '灯光故障停用',
      blockStartTime: '2026-07-07T10:00:00',
      blockEndTime: '2026-07-07T12:00:00'
    }
    httpMock.post.mockResolvedValue({ id: 7, status: 'OUT_OF_SERVICE' })

    await assetsApi.changeStatus(7, payload)

    expect(httpMock.post).toHaveBeenCalledWith('/admin/assets/7/status', payload, undefined)
  })

  // SHIGUANG-BATCH-03 S03：补齐设备档案编辑、生命周期分页与资产上下文三个既有端点的前端入口（contracts.md §1.1）。
  it('reaches the ledger update, paged lifecycle and asset context endpoints', async () => {
    const { assetsApi } = await import('./assets')
    const isolated = { skipGlobalErrorToast: true, skipGlobalErrorRedirect: true }
    httpMock.get.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 20 })
    httpMock.put.mockResolvedValue({ id: 7 })

    await assetsApi.update(7, { name: '2号场照明控制器', serialNo: 'LT-0201' }, isolated)
    await assetsApi.events(7, { pageNum: 2, pageSize: 5 }, isolated)
    await assetsApi.context({ venueId: 3 }, isolated)

    expect(httpMock.put).toHaveBeenCalledWith(
      '/admin/assets/7',
      { name: '2号场照明控制器', serialNo: 'LT-0201' },
      isolated
    )
    expect(httpMock.get).toHaveBeenNthCalledWith(
      1,
      '/admin/assets/7/events',
      { params: { pageNum: 2, pageSize: 5 }, ...isolated }
    )
    expect(httpMock.get).toHaveBeenNthCalledWith(
      2,
      '/admin/site-ops/assets/context',
      { params: { venueId: 3 }, ...isolated }
    )
  })
})
