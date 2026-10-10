import { describe, expect, it } from 'vitest'
import {
  PERSONAL_SECTIONS,
  caseStatusAttention,
  caseStatusLabel,
  complaintSeverityLabel,
  complaintTypeLabel,
  messageTarget,
  messageTypePill,
  reviewStatusLabel
} from './personalShared'

describe('personalShared', () => {
  it('lists the five reference sections in order', () => {
    expect(PERSONAL_SECTIONS.map(([, label]) => label)).toEqual([
      '个人概览', '消息中心', '我的反馈', '收藏场馆', '账号设置'
    ])
  })

  it('maps the real message type space onto the reference pills and targets', () => {
    expect(messageTypePill('RESERVATION')).toBe('预约')
    expect(messageTypePill('RESERVATION_APPROVED')).toBe('预约')
    expect(messageTypePill('REFUND_REVIEW_APPROVED')).toBe('退款')
    expect(messageTypePill('CUSTOMER_SERVICE_CASE_CREATED')).toBe('客服')
    expect(messageTypePill('NOTICE_PUBLISHED')).toBe('公告')
    expect(messageTypePill('COUPON_CLAIMED')).toBe('权益')
    expect(messageTypePill('PAYMENT_SUCCEEDED')).toBe('支付')
    expect(messageTypePill('SOMETHING_NEW')).toBe('系统消息')

    expect(messageTarget({ type: 'RESERVATION', businessId: 12 })).toEqual({ label: '查看预约 →', route: '/me/reservations#booking-12' })
    // G-T2：退款消息 businessId 是退款单 id，不能当订单/预约 id 深链
    expect(messageTarget({ type: 'REFUND', businessId: 31 })).toEqual({ label: '查看退款 →', route: '/me/orders' })
    expect(messageTarget({ type: 'CUSTOMER_SERVICE_CASE_CREATED', businessId: 9 })).toEqual({ label: '查看本人反馈 →', route: '/me/feedback?case=9' })
    expect(messageTarget({ type: 'NOTICE_PUBLISHED', businessId: 3 })).toBeNull()
  })

  it('derives user-visible feedback status labels', () => {
    expect(caseStatusLabel('OPEN')).toBe('待处理')
    expect(caseStatusAttention('OPEN')).toBe(true)
    expect(caseStatusLabel('PROCESSING')).toBe('处理中')
    expect(caseStatusLabel('RESOLVED')).toBe('已解决')
    expect(caseStatusAttention('RESOLVED')).toBe(false)
    expect(reviewStatusLabel('PENDING_REVIEW')).toBe('待审核')
    expect(reviewStatusLabel('PUBLISHED')).toBe('已公开')
    expect(reviewStatusLabel('REJECTED')).toBe('未通过')
  })

  it('labels the full backend status sets without leaking raw enums', () => {
    // 投诉/工单全集（CustomerFeedbackService COMPLAINT_STATUS / CASE_STATUS）
    expect(caseStatusLabel('SUBMITTED')).toBe('待处理')
    expect(caseStatusLabel('TRIAGED')).toBe('待处理')
    expect(caseStatusLabel('REOPENED')).toBe('待处理')
    expect(caseStatusLabel('IN_PROGRESS')).toBe('处理中')
    expect(caseStatusLabel('WAITING_USER')).toBe('待你补充')
    expect(caseStatusAttention('WAITING_USER')).toBe(true)
    expect(caseStatusLabel('CLOSED')).toBe('已解决')
    expect(caseStatusLabel('CANCELED')).toBe('已取消')
    // 评价全集（REVIEW_STATUS）
    expect(reviewStatusLabel('FLAGGED')).toBe('未通过')
    expect(reviewStatusLabel('HIDDEN')).toBe('未通过')
  })

  it('maps server enums back to reference display copy for detail rows', () => {
    expect(complaintTypeLabel('EQUIPMENT')).toBe('设备')
    expect(complaintTypeLabel('ORDER_PAYMENT')).toBe('订单支付')
    expect(complaintTypeLabel('OTHER')).toBe('其他')
    expect(complaintSeverityLabel('HIGH')).toBe('高')
    expect(complaintSeverityLabel('MEDIUM')).toBe('中')
    expect(complaintSeverityLabel('LOW')).toBe('低')
  })
})
