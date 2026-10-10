import { describe, expect, it } from 'vitest'

import type { CustomerComplaint, CustomerServiceAdminCaseEvent, CustomerServiceCase, VenueReview } from '@/api/feedback'
import {
  CASE_STATUS_ORDER,
  MODERATION_ACTIONS,
  assigneeText,
  canCloseCase,
  canMoveToInProgress,
  canReopenCase,
  canResolveCase,
  caseEventTypeLabel,
  caseIdFromHash,
  caseStatusLabel,
  complaintTypeLabel,
  contextRows,
  dayTimeLabel,
  filterQueueByKeyword,
  flagSelection,
  flagsOf,
  initialViewFromHash,
  isTerminalCase,
  queueAttention,
  queueRows,
  queueTitle,
  ratingText,
  reviewDetailLine,
  reviewMeta,
  reviewStatusLabel,
  statusChangeText,
  timelineRows
} from './serviceShared'

const today = new Date()
const iso = (offsetDays: number, hour = 9, minute = 18) => {
  const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() + offsetDays, hour, minute)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
}

const kase = (overrides: Record<string, unknown> = {}): CustomerServiceCase => ({
  id: 1,
  caseNo: 'CS-0912-018',
  complaintId: 11,
  venueId: 6,
  priority: 'HIGH',
  status: 'OPEN',
  openedAt: iso(0),
  ...overrides
} as CustomerServiceCase)

const complaint = (overrides: Record<string, unknown> = {}): CustomerComplaint => ({
  id: 11,
  complaintNo: 'CP-0912-018',
  venueId: 6,
  complaintType: 'EQUIPMENT',
  severity: 'HIGH',
  status: 'SUBMITTED',
  title: '预约的羽毛球场照明闪烁，希望安排后续处理',
  contentSanitized: '昨晚 19:00 的预约中，靠近后场的灯持续闪烁。',
  ...overrides
} as CustomerComplaint)

describe('serviceShared 标签与可达集', () => {
  it('工单状态取字典五项，无「待用户补充」', () => {
    expect(CASE_STATUS_ORDER).toEqual(['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'REOPENED'])
    expect(caseStatusLabel('OPEN')).toBe('已打开')
    expect(caseStatusLabel('REOPENED')).toBe('已重开')
    expect(CASE_STATUS_ORDER.map(caseStatusLabel).join()).not.toContain('待用户补充')
  })

  it('评价状态取字典三项，旧「已标记」改「待关注」', () => {
    expect(reviewStatusLabel('FLAGGED')).toBe('待关注')
    expect(reviewStatusLabel('PUBLISHED')).toBe('已发布')
    expect(MODERATION_ACTIONS.map((item) => item.label).join()).toBe('恢复,隐藏,标记')
  })

  it('投诉类型七项与严重程度三项按字典全名', () => {
    expect(complaintTypeLabel('NOTICE_INFO')).toBe('公告信息')
    expect(complaintTypeLabel('SERVICE_ATTITUDE')).toBe('服务态度')
    expect(Object.keys(complaintTypeLabel('未知') as string)).toBeTruthy()
    expect(complaintTypeLabel('UNKNOWN_CODE')).toBe('UNKNOWN_CODE')
  })

  it('事件类型七值沿用旧正文中文', () => {
    expect(caseEventTypeLabel('CREATED')).toBe('工单已创建')
    expect(caseEventTypeLabel('REPLY_ADDED')).toBe('客服回复')
    expect(caseEventTypeLabel(undefined)).toBe('处理记录')
  })

  it('状态转移文字过状态表而非原样英文', () => {
    expect(statusChangeText({ fromStatus: 'OPEN', toStatus: 'IN_PROGRESS' } as CustomerServiceAdminCaseEvent)).toBe('已打开 → 处理中')
    expect(statusChangeText({ fromStatus: 'OPEN', toStatus: 'OPEN' } as CustomerServiceAdminCaseEvent)).toBe('')
  })
})

describe('serviceShared 深链与时间', () => {
  it('hash 决定初始页签与工单深链', () => {
    expect(initialViewFromHash('#reviews')).toBe('reviews')
    expect(initialViewFromHash('#context')).toBe('context')
    expect(initialViewFromHash('#case-12')).toBe('cases')
    expect(initialViewFromHash('')).toBe('cases')
    expect(caseIdFromHash('#case-12')).toBe(12)
    expect(caseIdFromHash('#reviews')).toBeNull()
  })

  it('今天/昨天/其余日期三种口径', () => {
    expect(dayTimeLabel(iso(0, 9, 18))).toBe('今天 09:18')
    expect(dayTimeLabel(iso(-1, 17, 36))).toBe('昨天 17:36')
    expect(dayTimeLabel(iso(-9))).toMatch(/^\d+ 月 \d+ 日$/)
    expect(dayTimeLabel(null)).toBe('')
  })
})

describe('serviceShared 队列关联与关键词并集', () => {
  it('工单按 complaintId 关联投诉标题，缺投诉时回落工单编号', () => {
    const rows = queueRows([kase(), kase({ id: 2, complaintId: 99 })], [complaint()])
    expect(queueTitle(rows[0])).toBe('预约的羽毛球场照明闪烁，希望安排后续处理')
    expect(queueTitle(rows[1])).toBe('CS-0912-018')
    expect(queueAttention(rows[0])).toBe(true)
    expect(queueAttention(rows[1])).toBe(true)
  })

  it('关键词并集只认服务端返回的 id 集合，不在浏览器比文本', () => {
    const rows = queueRows(
      [kase(), kase({ id: 2, caseNo: 'CS-0912-012', complaintId: 12 })],
      [complaint(), complaint({ id: 12, title: '雨天活动通知与现场指引不一致' })]
    )
    expect(filterQueueByKeyword(rows, [kase()], []).map((row) => row.case.id)).toEqual([1])
    expect(filterQueueByKeyword(rows, [], [complaint({ id: 12 })]).map((row) => row.case.id)).toEqual([2])
    expect(filterQueueByKeyword(rows, [2], [11]).map((row) => row.case.id)).toEqual([1, 2])
    expect(filterQueueByKeyword(rows, [], []).length).toBe(0)
  })
})

describe('serviceShared 状态前置与标记', () => {
  it('终态、解决、关闭、重开与转处理中各自的前置', () => {
    expect(isTerminalCase('RESOLVED')).toBe(true)
    expect(isTerminalCase('CLOSED')).toBe(true)
    expect(isTerminalCase('REOPENED')).toBe(false)
    expect(canResolveCase('IN_PROGRESS')).toBe(true)
    expect(canResolveCase('OPEN')).toBe(false)
    expect(canCloseCase('RESOLVED')).toBe(true)
    expect(canCloseCase('OPEN')).toBe(false)
    expect(canReopenCase('CLOSED')).toBe(true)
    expect(canReopenCase('OPEN')).toBe(false)
    expect(canMoveToInProgress('OPEN')).toBe(true)
    expect(canMoveToInProgress('IN_PROGRESS')).toBe(false)
  })

  it('分派只报真实 assignedAt，不伪造处理人身份', () => {
    expect(assigneeText(kase())).toBe('未分派')
    expect(assigneeText(kase({ assignedAt: iso(0) }))).toBe('已分派')
  })

  it('三个复核标记读取与回显', () => {
    const item = kase({ requiresRefundReview: 1, requiresSiteFollowUp: true })
    expect(flagsOf(item)).toEqual(['人工退款复核', '现场人工跟进'])
    expect(flagSelection(item)).toEqual({
      requiresRefundReview: true,
      requiresCouponReview: false,
      requiresSiteFollowUp: true
    })
    expect(flagSelection(null).requiresRefundReview).toBe(false)
  })

  it('时间线保留内部备注与用户来源，不合并成一条', () => {
    const rows = timelineRows([
      { id: 1, eventType: 'CREATED', sourceType: 'USER', occurredAt: iso(0), noteSanitized: '现场已换场' },
      { id: 2, eventType: 'REPLY_ADDED', occurredAt: iso(0), noteSanitized: '正在核对', internalNoteSanitized: '已通知现场' }
    ] as CustomerServiceAdminCaseEvent[])
    expect(rows[0].label).toBe('工单已创建')
    expect(rows[0].time).toBe('今天 09:18')
    expect(rows[1].internalNote).toBe('已通知现场')
  })
})

describe('serviceShared 评价与关联上下文', () => {
  it('评分与元信息只用真实字段', () => {
    const review = { id: 3, reviewNo: 'RV-1', overallRating: 4, submittedAt: iso(0), sensitiveFlag: 1 } as VenueReview
    expect(ratingText(review)).toBe('4.0')
    expect(reviewMeta(review)).toBe('评价 RV-1 · 今天 09:18 · 含敏感标记')
    expect(reviewDetailLine(review)).toBe('未分项评分')
    expect(reviewDetailLine({ id: 4, venueRating: 4, serviceRating: 5 } as VenueReview)).toBe('场地 4 · 服务 5')
    expect(ratingText({ id: 5 } as VenueReview)).toBe('—')
    expect(reviewMeta({ id: 5 } as VenueReview)).toBe('无附加信息')
  })

  it('上下文沿 complaintId 关联工单', () => {
    const rows = contextRows({
      complaints: [{ id: 11, title: '照明闪烁', complaintNo: 'CP-11' }],
      cases: [{ id: 1, caseNo: 'CS-1', status: 'IN_PROGRESS', complaintId: 11 }, { id: 2, caseNo: 'CS-2', status: 'OPEN', complaintId: 12 }]
    } as never)
    expect(rows[0].cases.map((item) => item.caseNo)).toEqual(['CS-1'])
    expect(contextRows(null).length).toBe(0)
  })
})
