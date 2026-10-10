import { describe, expect, it } from 'vitest'
import {
  ALL_SOURCE_CHANNELS,
  FUNNEL_STAGE_LABELS,
  METRIC_DEFINITIONS,
  NOT_APPLICABLE_HINT,
  NOT_APPLICABLE_TEXT,
  barPercent,
  channelOptions,
  conversionRateText,
  defaultWindow,
  firstDropIndex,
  firstMeasuredIndex,
  isMeasured,
  localIso,
  rateIsUndefined,
  resultLabel,
  sampleCaption,
  shortDateTime,
  sourceChannelLabel,
  stageLabel,
  stepCountText,
  stepLabel,
  stepNarrative,
  stepRateText
} from './funnelAdminShared'

describe('漏斗面口径与标签', () => {
  it('prefers the server-provided step label and only falls back to the面内 table', () => {
    expect(stepLabel('SEARCH_SUBMITTED', '搜索提交')).toBe('搜索提交')
    expect(stepLabel('SLOT_SELECTED')).toBe('时段选择')
    expect(stepLabel('PAYMENT_RESULT_RECORDED')).toBe('支付结果记录')
    expect(stepLabel('BRAND_NEW_STEP')).toBe('BRAND_NEW_STEP')
  })

  it('maps only the eight stages that are actually written (B4-F8)', () => {
    expect(Object.keys(FUNNEL_STAGE_LABELS)).toEqual([
      'SEARCH', 'DETAIL', 'PRICE', 'COUPON', 'RESERVATION', 'ORDER', 'PAYMENT', 'FAVORITE'
    ])
    expect(stageLabel('RESERVATION')).toBe('预约')
    expect(stageLabel('PAYMENT')).toBe('支付')
    // 旧正文映射的四个阶段在真实写入集里不存在，不得迁入
    for (const retired of ['DISCOVERY', 'CONSIDERATION', 'INTENT', 'CONVERSION']) {
      expect(FUNNEL_STAGE_LABELS[retired]).toBeUndefined()
      expect(stageLabel(retired)).toBe(retired)
    }
    expect(stageLabel('SOMETHING')).toBe('SOMETHING')
  })

  it('formats the adjacent conversion rate with the reference semantics (B4-F15)', () => {
    // 首步是起点，不是 100% 转化
    expect(conversionRateText(1, 0)).toBe('起点')
    expect(conversionRateText(0.871, 1, 1240)).toBe('87.1%')
    expect(conversionRateText(0.9, 2, 100)).toBe('90.0%')
    // 分母为 0 时服务端仍给 0.0000，界面必须显示「—」而不是 0.0%
    expect(conversionRateText(0, 1, 0)).toBe('—')
    expect(rateIsUndefined(1, 0)).toBe(true)
    expect(rateIsUndefined(0, 0)).toBe(false)
  })

  it('sizes bars against the first step, not the adjacent rate (B4-F2)', () => {
    expect(barPercent(1240, 1240)).toBe(100)
    expect(barPercent(620, 1240)).toBe(50)
    expect(barPercent(0, 1240)).toBe(0)
    // 首步为 0 时不做除零
    expect(barPercent(10, 0)).toBe(0)
    // 不越过共同基准
    expect(barPercent(2000, 1240)).toBe(100)
  })

  it('writes the step narrative against the real cohort semantics (B4-F1)', () => {
    const step = (eventName: string, extra: Record<string, unknown> = {}) => ({ eventName, ...extra })
    expect(stepNarrative(step('SEARCH_SUBMITTED', { startStep: true }), 0)).toContain('起点')
    expect(stepNarrative(step('SLOT_SELECTED'), 3)).toContain('不自动等于“没有库存”')
    expect(stepNarrative(step('PAYMENT_RESULT_RECORDED'), 8)).toContain('不等于支付成功')
    expect(stepNarrative(step('PAYMENT_RESULT_RECORDED'), 8)).toContain('DEV')
    expect(stepNarrative(step('PRICE_PREVIEW_SHOWN'), 4)).toContain('逐步交集')
    expect(stepNarrative(step('PRICE_PREVIEW_SHOWN'), 4)).toContain('不是去重人数')
    // 五条口径定义齐全，且不出现预览的合成措辞
    expect(METRIC_DEFINITIONS.map((row) => row.term)).toEqual(['会话队列', '相邻转化', '条形长度', '事件统计', '解释边界'])
    expect(METRIC_DEFINITIONS.map((row) => row.text).join()).not.toContain('合成示例')
  })

  it('不适用步骤只说明口径归属，绝不渲染成零计数或零转化（F-22）', () => {
    const notApplicable = {
      eventName: 'SEARCH_SUBMITTED',
      measurement: 'NOT_APPLICABLE' as const,
      count: null,
      conversionRate: null,
      notApplicableReason: '搜索提交发生在选定具体场馆之前。'
    }
    expect(isMeasured(notApplicable)).toBe(false)
    expect(stepCountText(notApplicable)).toBe(NOT_APPLICABLE_TEXT)
    expect(stepRateText(notApplicable, 12)).toBe(NOT_APPLICABLE_TEXT)
    expect(stepNarrative(notApplicable, 0)).toContain('选定具体场馆之前')
    expect(stepNarrative(notApplicable, 0)).toContain('不参与相邻转化')
    // 真实零计数与「不适用」是两件事
    expect(isMeasured({ measurement: 'MEASURED', count: 0 })).toBe(true)
    expect(stepCountText({ measurement: 'MEASURED', count: 0 })).toBe('0')
    expect(stepRateText({ measurement: 'MEASURED', count: 0 }, 12)).toBe('0.0%')
    // 起点步骤不显示转化率；缺省 measurement（旧响应）仍按已计量处理
    expect(stepRateText({ measurement: 'MEASURED', startStep: true, count: 5 }, null)).toBe('起点')
    expect(isMeasured({ count: 7 })).toBe(true)
  })

  it('条长基准与默认步骤都落在首个可计量步骤上（F-22）', () => {
    const venueSteps = [
      { measurement: 'NOT_APPLICABLE' as const },
      { measurement: 'NOT_APPLICABLE' as const },
      { measurement: 'MEASURED' as const },
      { measurement: 'MEASURED' as const }
    ]
    expect(firstMeasuredIndex(venueSteps)).toBe(2)
    expect(firstMeasuredIndex([{ measurement: 'MEASURED' as const }])).toBe(0)
    expect(firstMeasuredIndex([])).toBe(0)
    // 前两步没有数值：不参与下降判定，也不会被选成默认停留步骤
    expect(firstDropIndex([null, null, 5, 5, 3])).toBe(4)
    expect(firstDropIndex([null, null, 5, 5, 5])).toBe(2)
    // 上一步不适用时同样显示「—」
    expect(rateIsUndefined(1, null)).toBe(true)
    expect(NOT_APPLICABLE_HINT).toBe('平台口径')
  })

  it('states the real sample cap of 20 and never implies completeness (B4-F14)', () => {
    expect(sampleCaption(20)).toContain('最多展示 20 条')
    expect(sampleCaption(3)).toContain('本次返回 3 条')
    expect(sampleCaption(3)).toContain('不代表范围内全部事件')
  })

  it('label channels and offers them from the real aggregation (B4-F13)', () => {
    expect(sourceChannelLabel('WEB')).toBe('网页端')
    expect(sourceChannelLabel('UNKNOWN')).toBe('未知来源')
    expect(sourceChannelLabel('PARTNER')).toBe('PARTNER')
    const options = channelOptions([{ sourceChannel: 'WEB', count: 1840 }], ALL_SOURCE_CHANNELS)
    expect(options.find((row) => row.value === 'WEB')).toEqual({ value: 'WEB', label: '网页端', count: 1840 })
    // 没有聚合数据的渠道也保留选项但计数为 0，界面据此标注「当前范围无数据」
    expect(options.find((row) => row.value === 'H5')?.count).toBe(0)
    // UNKNOWN 只在真实出现时才列出
    expect(options.some((row) => row.value === 'UNKNOWN')).toBe(false)
    expect(channelOptions([{ sourceChannel: 'UNKNOWN', count: 3 }], []).some((row) => row.value === 'UNKNOWN')).toBe(true)
    // 已按单一渠道筛选时聚合只含该渠道，不能据此宣称其它渠道「当前范围无数据」(B4-F13)
    const filtered = channelOptions([{ sourceChannel: 'WEB', count: 2 }], ALL_SOURCE_CHANNELS, false)
    expect(filtered.map((row) => row.count)).toEqual([null, null, null, null, null])
    expect(filtered.find((row) => row.value === 'H5')?.label).toBe('移动网页')
  })

  it('formats dates and event times with local-calendar arithmetic', () => {
    const window = defaultWindow(new Date(2026, 8, 12))
    expect(window).toEqual({ start: '2026-09-06', end: '2026-09-12' })
    expect(localIso(new Date(2026, 0, 5))).toBe('2026-01-05')
    expect(shortDateTime('2026-09-12T10:28:00')).toBe('09-12 10:28')
    expect(shortDateTime(null)).toBe('')
  })

  it('falls back to「已记录」for an empty result status instead of inventing DEV', () => {
    expect(resultLabel(null)).toBe('已记录')
    expect(resultLabel('DEV')).toBe('DEV')
  })
  it('默认停在第一个下降的步骤，与参考的默认选中状态一致，无下降则回起点 (B4-F1/F2)', () => {
    // 参考夹具 F.selected=3（时段选择），真实数据下「变化发生处」就是第一个下降步
    expect(firstDropIndex([6, 6, 6, 4, 4, 2, 2, 2, 2])).toBe(3)
    expect(firstDropIndex([1240, 1080, 756])).toBe(1)
    expect(firstDropIndex([5, 5, 5])).toBe(0)
    expect(firstDropIndex([0, 0, 0])).toBe(0)
    expect(firstDropIndex([])).toBe(0)
    // 服务端给 0 的步骤不能被当成 null 抛错，也不能把 0 视为「下降」以外的东西
    expect(firstDropIndex([undefined, 3, 3, 1])).toBe(3)
  })

})
