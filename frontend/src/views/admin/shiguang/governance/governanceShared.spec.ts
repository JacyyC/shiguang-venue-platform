import { describe, expect, it } from 'vitest'
import {
  ACTOR_SCOPE_LABELS,
  AUDIT_STATUS_LABELS,
  CONTENT_STATUS_LABELS,
  CURSOR_PAGE_SIZE,
  DECISION_CODE_DEFAULTS,
  DECISION_TITLES,
  EVENT_TYPE_LABELS,
  HIDDEN_SCOPE_LABELS,
  MODERATE_REASON_DEFAULTS,
  REPORT_STATUS_LABELS,
  actorScopeLabel,
  auditStatusLabel,
  canHide,
  canRestore,
  contentStatusLabel,
  eventActionLabel,
  hiddenScopeLabel,
  moderationHint,
  reportStatusLabel,
  resultStatusLabel,
  shortEventNo,
  shortReportNo,
  sourceTypeLabel,
  subjectTypeLabel,
  targetTypeLabel
} from './governanceShared'

describe('治理面共享展示层', () => {
  it('举报四态用预览文案，未结案与已结案各自可读 (B4-G12)', () => {
    // 预览 ref/g/g.js:7 的 OPEN/IN_REVIEW/RESOLVED/DISMISSED 中文；产品原表另有「待处理/已处理」
    expect(Object.keys(REPORT_STATUS_LABELS)).toEqual(['OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED'])
    expect(reportStatusLabel('OPEN')).toBe('待受理')
    expect(reportStatusLabel('RESOLVED')).toBe('已解决')
    expect(reportStatusLabel('DISMISSED')).toBe('已驳回')
    expect(reportStatusLabel('WHATEVER')).toBe('WHATEVER')
    expect(reportStatusLabel(null)).toBe('—')
  })

  it('补齐预览与产品都缺的内容/小组状态（含 PURGED），不把广告域词条当内容状态 (B4-G12)', () => {
    expect(CONTENT_STATUS_LABELS.DELETED).toBe('已删除')
    expect(CONTENT_STATUS_LABELS.PURGED).toBe('已清除')
    expect(contentStatusLabel('ACTIVE')).toBe('有效')
    // 广告域生命周期状态不得被当成内容状态
    expect(contentStatusLabel('PENDING_REVIEW')).toBe('PENDING_REVIEW')
    expect(contentStatusLabel('PAUSED')).toBe('PAUSED')
  })

  it('审计状态词表覆盖内容/举报/成员/保留期四类真实值域 (B4-G12)', () => {
    for (const value of ['PUBLISHED', 'HIDDEN', 'DELETED', 'PURGED', 'OPEN', 'IN_REVIEW', 'PENDING', 'LEFT', 'REMOVED', 'REJECTED', 'DEIDENTIFIED', 'REASON_RETAINED', 'REASON_PURGED']) {
      expect(AUDIT_STATUS_LABELS[value], value).toBeTruthy()
    }
    expect(auditStatusLabel('LEFT')).toBe('已退出')
    expect(auditStatusLabel('DEIDENTIFIED')).toBe('已注销')
    expect(auditStatusLabel('REMOVED')).toBe('已移除')
  })

  it('审计动作：REPORT_DECIDED 按 toStatus 派生受理/解决/驳回，其余走全量表 (B4-G9)', () => {
    expect(eventActionLabel({ eventType: 'REPORT_DECIDED', toStatus: 'IN_REVIEW' })).toBe('受理举报')
    expect(eventActionLabel({ eventType: 'REPORT_DECIDED', toStatus: 'RESOLVED' })).toBe('解决举报')
    expect(eventActionLabel({ eventType: 'REPORT_DECIDED', toStatus: 'DISMISSED' })).toBe('驳回举报')
    // 同类型但状态不可判时不猜动作
    expect(eventActionLabel({ eventType: 'REPORT_DECIDED', toStatus: null })).toBe('举报决定')
    expect(eventActionLabel({ eventType: 'CONTENT_INSPECTED' })).toBe('检查内容')
    expect(eventActionLabel({ eventType: 'MEMBERSHIP_DECIDED' })).toBe('处理加入申请')
    expect(eventActionLabel({ eventType: 'POST_HIDDEN' })).toBe('隐藏帖子')
    expect(eventActionLabel({ eventType: 'GROUP_PURGED' })).toBe('清除小组')
  })

  it('审计事件类型表按写入方全集登记，未知值回落原文 (B4-G10)', () => {
    // 四个写入方的代表类型
    for (const value of ['CONTENT_INSPECTED', 'REPORT_DECIDED', 'GROUP_HIDDEN', 'POST_HIDDEN', 'COMMENT_RESTORED', 'USER_BLOCKED', 'MEMBER_REMOVED', 'REPORT_REASON_PURGED', 'USER_DEIDENTIFIED']) {
      expect(EVENT_TYPE_LABELS[value], value).toBeTruthy()
    }
    expect(eventActionLabel({ eventType: 'SOMETHING_NEW' })).toBe('SOMETHING_NEW')
    expect(eventActionLabel({ eventType: null })).toBe('—')
  })

  it('执行者与来源只有 ID + 范围可显示（B4-G9）', () => {
    expect(ACTOR_SCOPE_LABELS.CONTENT).toBe('平台内容运营')
    expect(actorScopeLabel('GROUP')).toBe('组内')
    expect(sourceTypeLabel('CONTENT_OPERATOR')).toBe('平台内容运营')
    expect(sourceTypeLabel('SCHEDULED_JOB')).toBe('定时任务')
    expect(resultStatusLabel('SUCCEEDED')).toBe('记录成功')
    expect(actorScopeLabel('NOPE')).toBe('NOPE')
  })

  it('对象类型词表含审计里的 REPORT/MEMBER/BLOCK/USER，举报筛选只用三值 (B4-G9)', () => {
    expect(subjectTypeLabel('REPORT')).toBe('举报')
    expect(subjectTypeLabel('MEMBER')).toBe('成员')
    expect(targetTypeLabel('POST')).toBe('帖子')
    expect(targetTypeLabel('GROUP')).toBe('小组')
    expect(targetTypeLabel('COMMENT')).toBe('评论')
  })

  it('隐藏来源判别：组内处置与平台处置可区分 (B4-G4)', () => {
    expect(HIDDEN_SCOPE_LABELS.GROUP).toBe('组内处置')
    expect(HIDDEN_SCOPE_LABELS.PLATFORM).toBe('平台处置')
    expect(hiddenScopeLabel('PLATFORM')).toBe('平台处置')
    // 无来源字段（未隐藏或旧数据）不编造来源
    expect(hiddenScopeLabel(null)).toBe('—')
  })

  it('处置可用性与真实状态机一致：只有 PUBLISHED/ACTIVE 可隐藏、HIDDEN 可恢复 (B4-G6/G7)', () => {
    expect(canHide('PUBLISHED')).toBe(true)
    expect(canHide('ACTIVE')).toBe(true)
    expect(canHide('HIDDEN')).toBe(false)
    expect(canHide('DELETED')).toBe(false)
    expect(canRestore('HIDDEN')).toBe(true)
    expect(canRestore('PUBLISHED')).toBe(false)
    expect(moderationHint('DELETED')).toContain('不提供隐藏或恢复命令')
    expect(moderationHint('PURGED')).toContain('已清除')
    expect(moderationHint('PUBLISHED')).toBe('')
  })

  it('决定与处置的默认原因代码取自参考表达，编号只展示可读片段', () => {
    expect(DECISION_TITLES.TAKE).toBe('受理这条举报')
    expect(DECISION_CODE_DEFAULTS.TAKE).toBe('REVIEW_STARTED')
    expect(DECISION_CODE_DEFAULTS.DISMISS).toBe('NO_VIOLATION')
    expect(MODERATE_REASON_DEFAULTS.hide).toBe('CONTENT_VIOLATION')
    expect(MODERATE_REASON_DEFAULTS.restore).toBe('REVIEW_RESTORED')
    expect(CURSOR_PAGE_SIZE).toBe(20)
    expect(shortReportNo('CR-2f3a1b9c-7777-4a2b-9d3e-abcdef012345')).toBe('CR-2f3a1…')
    expect(shortReportNo('CR-1234')).toBe('CR-1234')
    expect(shortReportNo(null)).toBe('—')
    expect(shortEventNo('CGE-9f8e7d6c-1111-2222-3333-444455556666')).toBe('CGE-9f8e…')
    expect(shortEventNo(null, 71)).toBe('#71')
  })
})
