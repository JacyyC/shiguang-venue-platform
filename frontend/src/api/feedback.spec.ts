import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))

vi.mock('./http', () => ({ default: httpMock }))

describe('feedbackApi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('uses locked B9 feedback endpoints', async () => {
    const { feedbackApi } = await import('./feedback')

    await feedbackApi.myReviews({ pageNum: 1 })
    await feedbackApi.submitReview({ venueId: 1, reservationId: 3, overallRating: 5, contentText: '很好' })
    await feedbackApi.myComplaints({ pageNum: 1 })
    await feedbackApi.submitComplaint({
      venueId: 1,
      complaintType: 'EQUIPMENT',
      severity: 'HIGH',
      title: '灯光',
      contentText: '灯光异常'
    })
    await feedbackApi.myCases({ pageNum: 1 })
    await feedbackApi.myCaseEvents(21)
    await feedbackApi.venueReviewSummary(1)
    await feedbackApi.venueReviews(1, { pageNum: 1 })

    expect(httpMock.get).toHaveBeenCalledWith('/me/feedback/reviews', { params: { pageNum: 1 } })
    expect(httpMock.post).toHaveBeenCalledWith('/me/feedback/reviews', expect.objectContaining({ overallRating: 5 }), undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/me/feedback/complaints', { params: { pageNum: 1 } })
    expect(httpMock.post).toHaveBeenCalledWith('/me/feedback/complaints', expect.objectContaining({ complaintType: 'EQUIPMENT' }), undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/me/feedback/cases', { params: { pageNum: 1 } })
    expect(httpMock.get).toHaveBeenCalledWith('/me/feedback/cases/21/events', undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/venues/1/reviews/summary')
    expect(httpMock.get).toHaveBeenCalledWith('/venues/1/reviews', { params: { pageNum: 1 } })
  })

  it('uses locked B9 venue-admin customer-service endpoints', async () => {
    const { feedbackApi } = await import('./feedback')

    await feedbackApi.adminComplaints({ status: 'SUBMITTED' })
    await feedbackApi.adminCases({ status: 'OPEN' })
    await feedbackApi.adminCaseEvents(21)
    await feedbackApi.assignCase(21, { assignedUserId: 8 })
    await feedbackApi.replyCase(21, { replyText: '已回复' })
    await feedbackApi.resolveCase(21, { resolutionType: 'EXPLAINED', replyText: '已处理' })
    await feedbackApi.closeCase(21, { closeReason: '用户确认' })
    await feedbackApi.reopenCase(21, { reopenReason: '用户补充' })
    await feedbackApi.adminReviews({ status: 'FLAGGED' })
    await feedbackApi.moderateReview(11, { action: 'HIDE', reasonCode: 'SENSITIVE' })
    await feedbackApi.adminContext({ venueId: 1 })

    expect(httpMock.get).toHaveBeenCalledWith('/admin/customer-service/complaints', { params: { status: 'SUBMITTED' } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/customer-service/cases', { params: { status: 'OPEN' } })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/customer-service/cases/21/events', undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/customer-service/cases/21/assign', { assignedUserId: 8 }, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/customer-service/cases/21/reply', { replyText: '已回复' }, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/customer-service/cases/21/resolve', { resolutionType: 'EXPLAINED', replyText: '已处理' }, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/customer-service/cases/21/close', { closeReason: '用户确认' }, undefined)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/customer-service/cases/21/reopen', { reopenReason: '用户补充' }, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/customer-service/reviews', { params: { status: 'FLAGGED' } })
    expect(httpMock.post).toHaveBeenCalledWith('/admin/customer-service/reviews/11/moderate', { action: 'HIDE', reasonCode: 'SENSITIVE' }, undefined)
    expect(httpMock.get).toHaveBeenCalledWith('/admin/customer-service/context', { params: { venueId: 1 } })
  })

  // SHIGUANG-BATCH-03 S03：接入真实唯一可达的工单状态动作与面内降级配置（contracts.md B3-T29）。
  it('reaches the case status endpoint and carries the in-page request config', async () => {
    const { feedbackApi } = await import('./feedback')
    const isolated = { skipGlobalErrorToast: true, skipGlobalErrorRedirect: true }

    await feedbackApi.changeCaseStatus(21, { status: 'IN_PROGRESS' }, isolated)
    await feedbackApi.adminCases({ venueId: 3, status: 'OPEN' }, isolated)
    await feedbackApi.adminCaseEvents(21, isolated)

    expect(httpMock.post).toHaveBeenCalledWith(
      '/admin/customer-service/cases/21/status',
      { status: 'IN_PROGRESS' },
      isolated
    )
    expect(httpMock.get).toHaveBeenCalledWith('/admin/customer-service/cases', {
      params: { venueId: 3, status: 'OPEN' },
      ...isolated
    })
    expect(httpMock.get).toHaveBeenCalledWith('/admin/customer-service/cases/21/events', isolated)
  })
})
