import { describe, expect, it } from 'vitest'
import type {
  NoticeAudienceResponse,
  NoticeDeliveryBatchResponse,
  NoticeDeliveryOutboxResponse,
  NoticeDeliveryOverviewResponse,
  NotificationProviderOption
} from '@/api/types'
import {
  AUDIENCE_DELIVERY_LABELS,
  BATCH_STATUS_LABELS,
  OUTBOX_STATUS_LABELS,
  audienceRowCells,
  batchIdFromQuery,
  batchMenuEntries,
  canRetryOutbox,
  emailEvidence,
  excludeReasonRows,
  experimentAllocationError,
  experimentOption,
  experimentPayload,
  experimentResultRows,
  inAppEvidence,
  initialViewFromRoute,
  outboxAttemptText,
  outboxFactRows,
  outboxObjectText,
  outboxStatusLabel,
  percentText,
  providerLine,
  readRateSentence,
  targetScopeLabel
} from './noticeShared'

const batch = (over: Partial<NoticeDeliveryBatchResponse> = {}): NoticeDeliveryBatchResponse => ({
  batchId: 7,
  batchNo: 'BATCH202609140730',
  noticeId: 3,
  targetScope: 'VENUE_RECENT_RESERVERS',
  status: 'PENDING',
  audienceCount: 128,
  deliverableCount: 120,
  excludedCount: 8,
  sentCount: 118,
  failedCount: 2,
  readCount: 78,
  unreadCount: 40,
  providerEnv: 'DEV',
  providerName: 'in-app',
  createTime: '2026-09-14T07:30:00',
  ...over
})

const outbox = (over: Partial<NoticeDeliveryOutboxResponse> = {}): NoticeDeliveryOutboxResponse => ({
  outboxId: 11,
  outboxNo: 'OB202609140730',
  eventType: 'NOTICE_PUBLISHED',
  recipientKind: 'USER',
  maskedRecipient: null,
  channel: 'IN_APP',
  providerKey: 'dev-in-app',
  providerEnv: 'DEV',
  providerName: 'in-app',
  configVersion: 'dev-v1',
  templateKey: 'notice',
  templateVersion: 'v1',
  status: 'RETRY_WAITING',
  statusText: 'RETRY_WAITING',
  acceptanceStatus: null,
  downstreamStatus: null,
  attemptCount: 2,
  nextRetryTime: '2026-09-14T07:35:00',
  failureCode: 'PROVIDER_TIMEOUT',
  createTime: '2026-09-14T07:30:00',
  updateTime: '2026-09-14T07:33:00',
  acceptedAt: null,
  ...over
})

const emailOverview: NoticeDeliveryOverviewResponse = {
  batchCount: 0,
  audienceCount: 0,
  sentCount: 0,
  acceptedCount: 1,
  retryWaitingCount: 1,
  failedCount: 0,
  deliveredCount: 0,
  bouncedCount: 0,
  unreadCount: 0,
  readCount: 0,
  readRate: 0,
  failureReasonCounts: {},
  providerEnv: 'SANDBOX',
  providerName: 'smtp-sandbox',
  configVersion: 'sandbox-v1',
  channel: 'EMAIL',
  dataGaps: ['Mailpit 仅证明 SMTP 接受，不证明互联网送达或退信。']
}

const provider = (over: Partial<NotificationProviderOption> = {}): NotificationProviderOption => ({
  providerKey: 'sandbox-smtp',
  providerDomain: 'email',
  providerEnv: 'SANDBOX',
  providerName: 'smtp-sandbox',
  channel: 'EMAIL',
  displayName: '沙箱 SMTP',
  configVersion: 'sandbox-v1',
  enabled: true,
  defaultProvider: false,
  ...over
})

const audience = (over: Partial<NoticeAudienceResponse> = {}): NoticeAudienceResponse => ({
  audienceId: 21,
  batchId: 7,
  noticeId: 3,
  userId: 18,
  deliveryStatus: 'RETRY_WAITING',
  readStatus: 'UNREAD',
  ...over
})

describe('noticeShared 入口与参数', () => {
  it('三条旧路由各决定一个初始页签，页内 hash 优先', () => {
    expect(initialViewFromRoute('VenueAdminNotices', '')).toBe('compose')
    expect(initialViewFromRoute('SystemNoticeDelivery', '')).toBe('delivery')
    expect(initialViewFromRoute('SystemNoticeExperiments', '')).toBe('experiments')
    expect(initialViewFromRoute('SystemNoticeDelivery', '#experiments')).toBe('experiments')
    expect(initialViewFromRoute('VenueAdminNotices', '#nonsense')).toBe('compose')
  })

  it('只接受正整数批次号，其它查询值一律忽略', () => {
    expect(batchIdFromQuery('12')).toBe(12)
    expect(batchIdFromQuery(['13'])).toBe(13)
    expect(batchIdFromQuery('0')).toBeNull()
    expect(batchIdFromQuery(undefined)).toBeNull()
    expect(batchIdFromQuery('abc')).toBeNull()
  })
})

describe('noticeShared 标签口径', () => {
  it('批次状态只覆盖服务端真实写入五项', () => {
    expect(Object.keys(BATCH_STATUS_LABELS)).toEqual(['PENDING', 'PROCESSING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED'])
  })

  it('发件箱状态取字典中文且不列 SKIPPED', () => {
    expect(OUTBOX_STATUS_LABELS.RETRY_WAITING).toBe('等待重试')
    expect(OUTBOX_STATUS_LABELS.SKIPPED).toBeUndefined()
  })

  it('受众投递状态补齐字典缺项', () => {
    expect(AUDIENCE_DELIVERY_LABELS.RETRY_WAITING).toBe('等待重试')
    expect(AUDIENCE_DELIVERY_LABELS.EXCLUDED).toBe('已排除')
  })

  it('未知码原样显示，不折叠成默认值', () => {
    expect(outboxStatusLabel(outbox({ status: 'FUTURE_STATE', statusText: 'FUTURE_STATE' }))).toBe('FUTURE_STATE')
    expect(targetScopeLabel('UNKNOWN_SCOPE')).toBe('UNKNOWN_SCOPE')
    expect(targetScopeLabel(null)).toBe('—')
  })

  it('邮件已发送显示服务端 statusText 而不是本地改写', () => {
    expect(outboxStatusLabel(outbox({ channel: 'EMAIL', status: 'SENT', statusText: 'SMTP 已接受' }))).toBe('SMTP 已接受')
    expect(outboxStatusLabel(outbox({ channel: 'IN_APP', status: 'SENT', statusText: 'SENT' }))).toBe('已发送')
  })
})

describe('noticeShared 发件箱行与弹层', () => {
  it('站内行不虚构用户标识，邮件行用服务端脱敏地址', () => {
    expect(outboxObjectText(outbox())).toBe('收件人类型 USER')
    expect(outboxObjectText(outbox({ channel: 'EMAIL', maskedRecipient: 's***@example.test' }))).toBe('s***@example.test')
    expect(outboxObjectText(outbox({ channel: 'EMAIL', maskedRecipient: null }))).toBe('收件地址未记录')
  })

  it('尝试列保留真实错误码，无错误码时只写次数', () => {
    expect(outboxAttemptText(outbox())).toBe('2 次 · PROVIDER_TIMEOUT')
    expect(outboxAttemptText(outbox({ failureCode: null }))).toBe('2 次')
  })

  it('邮件只允许终态失败重试，站内允许失败与等待重试', () => {
    expect(canRetryOutbox(outbox({ status: 'RETRY_WAITING' }))).toBe(true)
    expect(canRetryOutbox(outbox({ status: 'SENT' }))).toBe(false)
    expect(canRetryOutbox(outbox({ channel: 'EMAIL', status: 'RETRY_WAITING' }))).toBe(false)
    expect(canRetryOutbox(outbox({ channel: 'EMAIL', status: 'FAILED' }))).toBe(true)
  })

  it('弹层只列服务端真实可得的事实，不虚构逐次流水', () => {
    const rows = outboxFactRows(outbox())
    expect(rows.map((row) => row.label)).toEqual([
      '当前状态',
      '已尝试',
      '进入发件箱',
      '最近更新',
      '下次重试'
    ])
    expect(rows.find((row) => row.label === '已尝试')?.value).toBe('2 次 · PROVIDER_TIMEOUT')
    expect(outboxFactRows(outbox({ acceptedAt: '2026-09-14T07:31:00', downstreamStatus: 'NOT_OBSERVED' }))
      .map((row) => row.label)).toContain('下游状态')
  })
})

describe('noticeShared 证据链与阅读率', () => {
  it('站内五层里客户端送达只能是未知', () => {
    const cells = inAppEvidence(batch())
    expect(cells).toHaveLength(5)
    expect(cells[3]).toMatchObject({ label: '客户端送达', value: '未知', unknown: true })
    expect(cells[1].note).toBe('排除 8 人')
    expect(inAppEvidence(batch({ excludedCount: undefined }))[1].note).toBe('排除数未记录')
  })

  it('邮件五层保留两处无证据，不把服务端零值写成送达', () => {
    const cells = emailEvidence(emailOverview, 2)
    expect(cells[0].value).toBe('2 封')
    expect(cells.filter((cell) => cell.unknown).map((cell) => cell.label)).toEqual(['下游送达', '阅读'])
    expect(emailEvidence(null, 0)[2].note).toBe('尚未取得渠道')
  })

  it('阅读率分母写受众快照数，不写成已生成消息', () => {
    const sentence = readRateSentence({ ...emailOverview, channel: 'IN_APP', audienceCount: 128, readCount: 78, readRate: 0.61 })
    expect(sentence).toBe('站内消息阅读率为 78 / 128 = 61.0%，分母为受众快照数（含排队、失败与排除项）。受众排队数不作为已送达人数。')
    expect(percentText(undefined)).toBe('0.0%')
  })

  it('排除说明按真实分解而不是笼统一句', () => {
    const rows = excludeReasonRows({
      targetScope: 'VENUE_RECENT_RESERVERS',
      audienceCount: 128,
      deliverableCount: 120,
      excludedCount: 8,
      excludeReasonCounts: { USER_NOT_ACTIVE: 5, DEVICE_LOST: 3 },
      samples: []
    })
    expect(rows).toEqual([{ label: '账号不可用', count: 5 }, { label: 'DEVICE_LOST', count: 3 }])
  })
})

describe('noticeShared 批次菜单与受众', () => {
  it('菜单由真实批次驱动，邮件渠道作为最后一条入口', () => {
    const entries = batchMenuEntries([batch(), batch({ batchId: 8, batchNo: 'BATCH-B' })], provider())
    expect(entries.map((entry) => entry.key)).toEqual(['batch-7', 'batch-8', 'email'])
    expect(entries[0].title).toBe('BATCH202609140730')
    expect(entries[0].note).toBe('场馆近期预约用户')
    expect(entries[0].detail).toBe('DEV / in-app · 07:30')
    expect(entries[2].note).toBe('平台 · 事务邮件')
    expect(entries[2].detail).toBe('SANDBOX / smtp-sandbox')
    expect(batchMenuEntries([], null)).toHaveLength(0)
  })

  it('渠道行用服务端原文，不本地翻译', () => {
    expect(providerLine(provider())).toBe('SANDBOX / smtp-sandbox · sandbox-v1')
    expect(providerLine(null)).toBe('渠道未登记')
  })

  it('受众一行按真实投递与阅读状态，排除时优先显示原因', () => {
    expect(audienceRowCells(audience())).toEqual({ subject: '用户 #18', delivery: '等待重试', read: '未读' })
    expect(audienceRowCells(audience({ readStatus: 'READ', readTime: '2026-09-14T07:40:00' })).read).toBe('已读 · 07:40')
    expect(audienceRowCells(audience({ deliveryStatus: 'EXCLUDED', excludeReason: 'USER_DELETED' })).read).toBe('账号已删除')
  })
})

describe('noticeShared 实验', () => {
  it('实验定义按服务端必填集提交并生成 variantCode', () => {
    const payload = experimentPayload({
      name: '周末维护对照',
      sampleScope: 'VENUE_RECENT_RESERVERS',
      titleA: '周末场地维护安排',
      contentA: '周日 08:00—12:00 部分场地维护。',
      titleB: '周日早场有调整',
      contentB: '计划周日早场运动的用户请提前查看。',
      allocationA: 50,
      allocationB: 50
    })
    expect(payload).toMatchObject({ name: '周末维护对照', targetMetric: 'READ_RATE', sampleScope: 'VENUE_RECENT_RESERVERS' })
    expect((payload.variants as Array<Record<string, unknown>>).map((row) => row.variantCode)).toEqual(['A', 'B'])
  })

  it('比例合计不是 100 时给出与服务端同一口径的提示', () => {
    expect(experimentAllocationError(50, 50)).toBe('')
    expect(experimentAllocationError(60, 30)).toBe('两个版本的比例之和需要为 100%。')
  })

  it('邮件渠道不出阅读表现而不是 0%', () => {
    const result = {
      variants: [
        { variantId: 1, variantCode: 'A', title: '甲', allocationPercent: 50, audienceCount: 60, sentCount: 58, failedCount: 2, readCount: 37, readRate: 0.6167 }
      ]
    }
    expect(experimentResultRows(null, true)).toEqual([])
    expect(experimentResultRows(result as never, false)[0]).toMatchObject({ code: 'A', rate: '61.7%', readCount: 37 })
  })

  it('刚保存的定义（两变体受众数为 0）算「尚无投递结果」而不是 0%', () => {
    const fresh = {
      variants: [
        { variantId: 1, variantCode: 'A', title: '甲', allocationPercent: 50, audienceCount: 0, sentCount: 0, failedCount: 0, readCount: 0, readRate: 0 },
        { variantId: 2, variantCode: 'B', title: '乙', allocationPercent: 50, audienceCount: 0, sentCount: 0, failedCount: 0, readCount: 0, readRate: 0 }
      ]
    }
    expect(experimentResultRows(fresh as never, false)).toEqual([])
    const partly = {
      variants: [
        { variantId: 1, variantCode: 'A', title: '甲', allocationPercent: 50, audienceCount: 12, sentCount: 12, failedCount: 0, readCount: 4, readRate: 0.3333 },
        { variantId: 2, variantCode: 'B', title: '乙', allocationPercent: 50, audienceCount: 0, sentCount: 0, failedCount: 0, readCount: 0, readRate: 0 }
      ]
    }
    expect(experimentResultRows(partly as never, false).map((row) => row.rate)).toEqual(['33.3%', '0.0%'])
  })

  it('实验下拉取真实名称，缺名时回落编号而不是伪造', () => {
    expect(experimentOption({ id: 4, name: '表达对照', status: 'RUNNING' }).label).toBe('表达对照')
    expect(experimentOption({ id: 4, name: '', status: 'RUNNING' }).label).toBe('实验 #4')
  })
})
