import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockHttpGet = vi.hoisted(() => vi.fn())
const mockHttpPost = vi.hoisted(() => vi.fn())
const mockHttpPut = vi.hoisted(() => vi.fn())

vi.mock('./http', () => ({
  default: {
    get: mockHttpGet,
    post: mockHttpPost,
    put: mockHttpPut
  }
}))

describe('noticeApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('calls notice delivery overview endpoint', async () => {
    const { noticeApi } = await import('./notice')
    mockHttpGet.mockResolvedValue({ batchCount: 1 })

    await noticeApi.deliveryOverview({ providerEnv: 'DEV' })

    expect(mockHttpGet).toHaveBeenCalledWith('/admin/notices/delivery/overview', { params: { providerEnv: 'DEV' } })
  })

  it('loads the notification provider catalog', async () => {
    const { noticeApi } = await import('./notice')
    mockHttpGet.mockResolvedValue([])

    await noticeApi.deliveryProviders()

    expect(mockHttpGet).toHaveBeenCalledWith('/admin/notices/delivery/providers', undefined)
  })

  it('loads paged business delivery outboxes with the selected provider context', async () => {
    const { noticeApi } = await import('./notice')
    mockHttpGet.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 20 })

    await noticeApi.deliveryOutboxes({
      providerEnv: 'SANDBOX',
      providerName: 'SMTP_EMAIL',
      pageNum: 1,
      pageSize: 20
    })

    expect(mockHttpGet).toHaveBeenCalledWith('/admin/notices/delivery/outboxes', {
      params: {
        providerEnv: 'SANDBOX',
        providerName: 'SMTP_EMAIL',
        pageNum: 1,
        pageSize: 20
      }
    })
  })

  it('uses the self-only transactional email preference contract', async () => {
    const { noticeApi } = await import('./notice')
    const preference = {
      transactionalEmailEnabled: true,
      emailConfigured: true,
      emailEligible: true,
      maskedEmail: 'c***n@e***e.test',
      addressValidated: true,
      addressVerified: false,
      exclusionCode: null
    }
    mockHttpGet.mockResolvedValue(preference)
    mockHttpPut.mockResolvedValue({ ...preference, transactionalEmailEnabled: false })

    await noticeApi.notificationPreferences()
    await noticeApi.updateNotificationPreferences({ transactionalEmailEnabled: false })

    expect(mockHttpGet).toHaveBeenCalledWith('/users/me/notification-preferences', undefined)
    expect(mockHttpPut).toHaveBeenCalledWith('/users/me/notification-preferences', {
      transactionalEmailEnabled: false
    }, undefined)
  })

  it('uses locked B7 admin delivery and experiment endpoint contracts', async () => {
    const { noticeApi } = await import('./notice')
    const experimentPayload = {
      name: '暑期通知',
      targetMetric: 'READ_RATE',
      sampleScope: 'ALL_USERS',
      variants: [
        { variantCode: 'A', title: '版本 A', content: '内容 A', allocationPercent: 50 },
        { variantCode: 'B', title: '版本 B', content: '内容 B', allocationPercent: 50 }
      ]
    }

    mockHttpGet.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 20 })
    mockHttpPost.mockResolvedValue(undefined)

    await noticeApi.deliveryBatches({ providerEnv: 'DEV', providerName: 'IN_APP_MESSAGE', pageNum: 1 })
    await noticeApi.deliveryBatch(61)
    await noticeApi.deliveryAudiences(61, { deliveryStatus: 'FAILED', readStatus: 'UNREAD' })
    await noticeApi.retryOutbox(81)
    await noticeApi.createExperiment(experimentPayload)
    await noticeApi.experiments({ status: 'RUNNING', pageNum: 1 })
    await noticeApi.experimentResults(11, { providerEnv: 'DEV', providerName: 'IN_APP_MESSAGE' })

    expect(mockHttpGet).toHaveBeenCalledWith('/admin/notices/delivery/batches', {
      params: { providerEnv: 'DEV', providerName: 'IN_APP_MESSAGE', pageNum: 1 }
    })
    // SHIGUANG-BATCH-03 S06：发布后按真实 batchId 定位单批次（预览的「刚刚发布」条目由它高亮）
    expect(mockHttpGet).toHaveBeenCalledWith('/admin/notices/delivery/batches/61', undefined)
    expect(mockHttpGet).toHaveBeenCalledWith('/admin/notices/delivery/batches/61/audiences', {
      params: { deliveryStatus: 'FAILED', readStatus: 'UNREAD' }
    })
    expect(mockHttpPost).toHaveBeenCalledWith('/admin/notices/delivery/outbox/81/retry', undefined, undefined)
    expect(mockHttpPost).toHaveBeenCalledWith('/admin/notices/experiments', experimentPayload, undefined)
    expect(mockHttpGet).toHaveBeenCalledWith('/admin/notices/experiments', { params: { status: 'RUNNING', pageNum: 1 } })
    expect(mockHttpGet).toHaveBeenCalledWith('/admin/notices/experiments/11/results', {
      params: { providerEnv: 'DEV', providerName: 'IN_APP_MESSAGE' }
    })
  })

  // SHIGUANG-BATCH-03 S03：公告面管理侧请求可携带面内降级配置，403/409 由面内状态与弹层承接（contracts.md §0）。
  it('carries the in-page degraded request config through admin notice calls', async () => {
    const { noticeApi } = await import('./notice')
    const isolated = { skipGlobalErrorToast: true, skipGlobalErrorRedirect: true }
    mockHttpGet.mockResolvedValue({ batchCount: 0 })
    mockHttpPost.mockResolvedValue({ id: 9, deliveryBatchId: 61 })

    await noticeApi.previewAudience({ targetScope: 'VENUE_RECENT_RESERVERS', targetVenueId: 3 }, isolated)
    await noticeApi.publish({ title: '照明维护', content: '部分时段暂停开放' }, isolated)
    await noticeApi.retryOutbox(81, isolated)
    await noticeApi.deliveryOverview({ providerEnv: 'DEV' }, isolated)

    expect(mockHttpPost).toHaveBeenNthCalledWith(
      1,
      '/admin/notices/audience/preview',
      { targetScope: 'VENUE_RECENT_RESERVERS', targetVenueId: 3 },
      isolated
    )
    expect(mockHttpPost).toHaveBeenNthCalledWith(
      2,
      '/admin/notices',
      { title: '照明维护', content: '部分时段暂停开放' },
      isolated
    )
    expect(mockHttpPost).toHaveBeenNthCalledWith(3, '/admin/notices/delivery/outbox/81/retry', undefined, isolated)
    expect(mockHttpGet).toHaveBeenNthCalledWith(1, '/admin/notices/delivery/overview', {
      params: { providerEnv: 'DEV' },
      ...isolated
    })
  })
})
