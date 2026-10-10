/**
 * 场馆漏斗统计面共享展示层（SHIGUANG-BATCH-04-REFACTOR-20260914 S06）。
 *
 * 只收本面共用的口径、标签与格式化，全部可测纯函数。九步顺序与中文标签以**服务端下发为准**
 * （`be/funnel/service/FunnelAnalyticsService.java:30-49` 的 `EVENT_ORDER` + `STEP_LABELS`），
 * 面内表只在服务端未给出 label 时兜底，避免第二份顺序源漂移（B4-F11 已登记该风险）。
 */

/** 服务端九步顺序（仅用于「服务端未下发 label 时」兜底与样本事件名映射）。 */
export const FUNNEL_STEP_LABELS: Record<string, string> = {
  SEARCH_SUBMITTED: '搜索提交',
  SEARCH_RESULT_VIEWED: '搜索结果查看',
  VENUE_DETAIL_VIEWED: '场馆详情查看',
  SLOT_SELECTED: '时段选择',
  PRICE_PREVIEW_SHOWN: '价格预览',
  RESERVATION_SUBMITTED: '预约提交',
  ORDER_CREATED: '订单创建',
  PAYMENT_INITIATED: '支付发起',
  PAYMENT_RESULT_RECORDED: '支付结果记录'
}

export function stepLabel(eventName: string | null | undefined, serverLabel?: string | null): string {
  if (serverLabel) return serverLabel
  if (!eventName) return ''
  return FUNNEL_STEP_LABELS[eventName] ?? eventName
}

/**
 * 事件阶段（B4-F8）。真实写入值只有 8 个：前端埋点 `SEARCH/DETAIL/PRICE/COUPON/FAVORITE`
 * （`fe/layouts/ShiguangLayout.vue:370`、`fe/views/user/VenueDetailView.vue:781`、
 * `fe/views/user/ReservationConfirmView.vue:462`、`be/funnel/service/FunnelFavoriteService.java:185`），
 * 服务端里程碑 `RESERVATION/ORDER/PAYMENT`（`be/business/constant/BusinessDomainConst.java:13-15`
 * 与 `PaymentService.java:317`）。旧正文映射的 `DISCOVERY/CONSIDERATION/INTENT/CONVERSION`
 * 在真实数据里根本不出现，属虚构阶段，不得迁入。未知值原样显示。
 */
export const FUNNEL_STAGE_LABELS: Record<string, string> = {
  SEARCH: '搜索',
  DETAIL: '详情',
  PRICE: '价格',
  COUPON: '优惠券',
  RESERVATION: '预约',
  ORDER: '订单',
  PAYMENT: '支付',
  FAVORITE: '收藏'
}

export function stageLabel(stage: string | null | undefined): string {
  if (!stage) return ''
  return FUNNEL_STAGE_LABELS[stage.toUpperCase()] ?? stage
}

/** 事件结果（`resultStatus` 由写入方决定，无白名单；常见为 RECORDED / DEV 链路标记）。 */
export function resultLabel(result: string | null | undefined): string {
  if (!result) return '已记录'
  return result
}

/** 来源渠道：字典 `funnel_source_channel` 只有类型没有数据，实际写入以前端 store 的 WEB 为主。 */
export const SOURCE_CHANNEL_LABELS: Record<string, string> = {
  WEB: '网页端',
  H5: '移动网页',
  MINI_PROGRAM: '小程序',
  IN_APP: '站内入口',
  OFFLINE_QR: '线下扫码',
  UNKNOWN: '未知来源'
}

export function sourceChannelLabel(channel: string | null | undefined): string {
  if (!channel) return '未知来源'
  return SOURCE_CHANNEL_LABELS[channel.toUpperCase()] ?? channel
}

/* ---------------------------------------------------------------- 比率 */

/** F-22：不适用步骤的服务端取值。 */
export type FunnelMeasurement = 'MEASURED' | 'NOT_APPLICABLE'

/** 不适用步骤在界面上的统一表达：计数为空，口径写清楚，绝不显示 0。 */
export const NOT_APPLICABLE_TEXT = '—'
export const NOT_APPLICABLE_HINT = '平台口径'

/** 本模块四个展示函数共同消费的结构（服务端 `FunnelStepMetric` 的界面视图子集）。 */
export type FunnelStepView = {
  measurement?: FunnelMeasurement | null
  startStep?: boolean
  count?: number | null
  conversionRate?: number | string | null
}

export function isMeasured(step: FunnelStepView | null | undefined): boolean {
  // 服务端未下发计量状态时按已计量处理，保持旧响应可读；场馆口径一定会显式标注。
  return step?.measurement !== 'NOT_APPLICABLE'
}

/** 条长基准与「无下降时的默认步骤」都以首个可计量步骤为准（场馆口径即场馆详情）。 */
export function firstMeasuredIndex(steps: FunnelStepView[]): number {
  const index = steps.findIndex((step) => isMeasured(step))
  return index < 0 ? 0 : index
}

export function stepCountText(step: FunnelStepView | null | undefined): string {
  if (!isMeasured(step) || step?.count == null) return NOT_APPLICABLE_TEXT
  return Number(step.count).toLocaleString('zh-CN')
}

/**
 * 相邻转化：起点步骤显示「起点」（场馆口径的起点是场馆详情，不是数组第一项）；
 * 不适用步骤与分母缺失/为 0 都显示「—」，不能读成零转化。
 */
export function stepRateText(
  step: FunnelStepView | null | undefined,
  previousMeasuredCount: number | null | undefined
): string {
  if (!isMeasured(step)) return NOT_APPLICABLE_TEXT
  if (step?.startStep) return '起点'
  if (previousMeasuredCount == null || previousMeasuredCount <= 0) return NOT_APPLICABLE_TEXT
  return conversionRateText(step?.conversionRate, 1, previousMeasuredCount)
}

/**
 * 服务端下发的是 0–1 的 BigDecimal 比率（scale=4，HALF_UP），首步为 1.0000/0.0000
 * （`be/funnel/service/FunnelAnalyticsService.java`）。参考把首步显示为「起点」，
 * 其余保留 1 位小数并加百分号（`ref/b/funnel.js:5`）。分母为 0 时服务端给 0.0000，
 * 此时不能显示成「0.0% 转化」而应显示「—」，避免把「无上一步数据」读成「零转化」。
 */
export function conversionRateText(rate: number | string | null | undefined, index: number, previousCount?: number): string {
  if (index === 0) return '起点'
  if (previousCount != null && previousCount <= 0) return '—'
  const value = Number(rate ?? 0)
  if (!Number.isFinite(value)) return '—'
  return `${(value * 100).toFixed(1)}%`
}

/** 条形长度：以本口径首个可计量步骤为共同基准（F-22），不是相邻转化率（B4-F2）。 */
export function barPercent(count: number | null | undefined, base: number | null | undefined): number {
  const baseValue = Number(base ?? 0)
  const countValue = Number(count ?? 0)
  if (baseValue <= 0) return 0
  const ratio = Math.min(1, Math.max(0, countValue / baseValue))
  return Number((ratio * 100).toFixed(4))
}

/**
 * 默认停留步骤。参考夹具默认选中第 4 步「时段选择」（`ref/b/funnel.js:1` 的 `F.selected:3`），
 * 正文首句也把本面用途写成「识别变化发生在哪一步」。真实数据的默认值因此取**第一个出现下降的
 * 步骤**（变化发生处），没有下降时回到起点：既是有意义的产品默认（不把用户丢在最没有信息量的
 * 起点旁栏），也让与参考的「同状态」对照成立。
 * F-22：不适用步骤没有数值，既不参与下降判定，也不会被当成默认起点。
 */
export function firstDropIndex(counts: Array<number | null | undefined>): number {
  let previous: number | null = null
  for (let index = 0; index < counts.length; index += 1) {
    if (counts[index] == null) continue
    const value = Number(counts[index])
    if (previous != null && value < previous) return index
    previous = value
  }
  return firstMeasuredIndex(counts.map((count) => ({
    measurement: count == null ? ('NOT_APPLICABLE' as const) : ('MEASURED' as const)
  })))
}

/* ------------------------------------------------------------ 口径文案 */

/**
 * 逐步口径旁栏（B4-F1、F-22）。按**步骤身份**而不是数组位置解释，因为场馆口径的起点是「场馆详情查看」：
 * 不适用步骤直接说明它为什么没有数值（服务端给出归属差异），起点步骤说明它是起点而非转化结果，
 * 「时段选择」提示「减少不自动等于没有库存」，最后一步提示「结果记录不等于支付成功」，
 * 其余统一说明「按会话逐步交集统计」。参考文案含合成语境，这里按真实统计口径重写。
 */
export function stepNarrative(step: {
  eventName?: string | null
  startStep?: boolean
  measurement?: FunnelMeasurement | null
  notApplicableReason?: string | null
} | null | undefined, previousCount: number): string {
  if (!isMeasured(step)) {
    return `${step?.notApplicableReason ?? '该步骤在当前查询口径下没有可归属的事件。'}不适用步骤不显示计数，也不参与相邻转化。`
  }
  if (step?.startStep) {
    return '本口径的起点步骤（第 1 个可计量步骤），不是转化结果；它之前的步骤在场馆视角下不适用。'
  }
  if (step?.eventName === 'PAYMENT_RESULT_RECORDED') {
    return '“支付结果记录”不等于支付成功，也不证明真实资金到账；本地结算包含 DEV / 模拟链路。'
  }
  if (step?.eventName === 'SLOT_SELECTED') {
    return '此处的减少不自动等于“没有库存”；需要结合已记录的流失原因进一步核对。'
  }
  return `按会话逐步交集统计：本步必须也在之前各 ${Math.max(previousCount, 1)} 个可计量步骤的会话集合里。会话不是去重人数，也不构成完整时序旅程的证明。`
}

/** 口径弹层五条定义（B4-F1、F-22）。措辞按真实实现撰写，不逐字搬运预览。 */
export const METRIC_DEFINITIONS: Array<{ term: string; text: string }> = [
  { term: '会话队列', text: '同一步径下按同一会话标识（session_key）逐步取交集；后一步必须也在之前各可计量步骤的集合里。场馆口径从「场馆详情查看」起算，搜索两步没有场馆归属。' },
  { term: '相邻转化', text: '当前步骤会话数 ÷ 上一个可计量步骤会话数，由服务端按 4 位小数 HALF_UP 计算；本口径起点步骤不显示转化率；分母缺失或为 0 时以“—”表示无上一步数据，不适用步骤同样为空。' },
  { term: '条形长度', text: '以本口径首个可计量步骤的会话数为共同基准，用于横向比较规模，不代表转化率；不适用步骤没有条长。' },
  { term: '事件统计', text: '来源渠道、流失原因与关键词都是**事件次数**，不能与会话差值直接相抵。' },
  { term: '解释边界', text: '会话不是去重人数；交集不保证完整时序；支付结果不等于支付成功；相关变化不证明营销因果。缺失与零是两件事：不适用留空，真实零计数照常显示 0。' }
]

/** 样本事件条数上限（服务端 `SAMPLE_LIMIT = 20`，`FunnelAnalyticsService.java:62`）。 */
export const SAMPLE_LIMIT = 20

/** 样本说明：真实上限是 20 条，必须显式说明不是范围内全部事件（B4-F14）。 */
export function sampleCaption(returned: number): string {
  return `最多展示 ${SAMPLE_LIMIT} 条样本事件，本次返回 ${returned} 条；样本只是当前范围的局部记录，不代表范围内全部事件。`
}

/* ---------------------------------------------------------------- 时间 */

/** 参考日期块与过滤输入用 `YYYY-MM-DD`；服务端 `FunnelOverviewQuery` 绑定 `LocalDate`。 */
export function isoDate(value: string | null | undefined): string {
  return value ? String(value).slice(0, 10) : ''
}

/** 事件时间：`09-12 10:28`（参考样本表的时间列）。 */
export function shortDateTime(value: string | null | undefined): string {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.slice(5, 16)
}

/** 默认窗口：近 7 天（服务端 `today.minusDays(6)` 至 `today`，前端保持一致）。 */
export function defaultWindow(today: Date): { start: string; end: string } {
  const end = new Date(today.getTime())
  const start = new Date(today.getTime() - 6 * 86400000)
  return { start: localIso(start), end: localIso(end) }
}

/** 本地日历拼 `YYYY-MM-DD`，不用 toISOString（会按 UTC 切日）。 */
export function localIso(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/**
 * 过滤表单的渠道选项（B4-F13）。产品实际写入的渠道以前端 store 固定的 `WEB` 与服务端里程碑默认值
 * 为主，其余渠道只在外部调用方显式传值时才可能出现。因此选项由**服务端实际聚合值**驱动，
 * 再补上已知渠道的完整标签；不列没有数据、也不虚构提示「暂无数据」以外的语义。
 */
export function channelOptions(
  aggregated: Array<{ sourceChannel: string; count: number }>,
  allChannels: readonly string[],
  /** 聚合值是否覆盖「全部渠道」。为 false 时不标注计数（见下方注释）。 */
  withCounts = true
): Array<{ value: string; label: string; count: number | null }> {
  const counts = new Map<string, number>()
  for (const row of aggregated) counts.set(row.sourceChannel.toUpperCase(), row.count)
  const values = new Set<string>([...allChannels.map((c) => c.toUpperCase()), ...counts.keys()])
  return [...values]
    .filter((value) => value !== 'UNKNOWN' || counts.has('UNKNOWN'))
    .map((value) => ({
      value,
      label: sourceChannelLabel(value),
      // 服务端的来源聚合**服从同一 sourceChannel 过滤**：已经筛成单一渠道时，聚合里只剩该渠道，
      // 若照样标注「其它渠道当前范围无数据」就与事实相反（同一范围里其实有数据，只是被筛掉了）。
      // 因此只有「全部渠道」时才带计数与无数据标注，筛选中只给标签。
      count: withCounts ? counts.get(value) ?? 0 : null
    }))
}

/** 全部渠道选项（与服务端白名单一致；`FunnelEventService` 归一化后写入这些值）。 */
export const ALL_SOURCE_CHANNELS = ['WEB', 'H5', 'MINI_PROGRAM', 'IN_APP', 'OFFLINE_QR'] as const

/**
 * 转化率单元格的「无数据」判定：分母为 0 时服务端仍给 0.0000，上一步不适用时前端拿不到分母，
 * 两种情况界面都必须显示「—」而非 0.0%。
 */
export function rateIsUndefined(index: number, previousCount: number | null | undefined): boolean {
  return index > 0 && (previousCount == null || previousCount <= 0)
}
