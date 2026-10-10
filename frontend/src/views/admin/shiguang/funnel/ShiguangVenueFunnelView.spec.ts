import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const funnelApiMock = vi.hoisted(() => ({ overview: vi.fn(), samples: vi.fn() }))
const showToast = vi.hoisted(() => vi.fn())
/** 场馆 store 必须是响应式的：本面 watch scopeChangedAt 清理旧场馆数据（B4-F3）。 */
const venueHolder = vi.hoisted(() => ({ state: null as unknown }))

vi.mock('@/api/funnel', () => ({ funnelAdminApi: funnelApiMock }))
vi.mock('@/stores/app', () => ({ useAppStore: () => ({ showToast }) }))
vi.mock('@/stores/venueAccess', async () => {
  const { reactive } = await import('vue')
  venueHolder.state = reactive({
    venues: [{ id: 6, name: '北岸生活场馆' }] as Array<{ id: number; name: string }>,
    loaded: true,
    activeVenueId: 6 as number | null,
    scopeChangedAt: 0,
    refresh: vi.fn()
  })
  return { useVenueAccessStore: () => venueHolder.state }
})

type VenueState = {
  venues: Array<{ id: number; name: string }>
  loaded: boolean
  activeVenueId: number | null
  scopeChangedAt: number
  refresh: ReturnType<typeof vi.fn>
}
const venueState = venueHolder.state as VenueState

import ShiguangVenueFunnelView from './ShiguangVenueFunnelView.vue'

const dialogProto = (globalThis.HTMLDialogElement as unknown as { prototype: Record<string, () => void> } | undefined)?.prototype
if (dialogProto) {
  dialogProto.showModal = dialogProto.showModal ?? (() => {})
  dialogProto.close = dialogProto.close ?? (() => {})
}

const IOS = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

// 与隔离栈真实返回值同形（S06 实测 6/6/6/4/4/2/2/2/2）：默认停留在第一个下降步（第 4 步），
// 与参考夹具的默认选中状态一致（`ref/b/funnel.js:1` F.selected=3）。
const steps = [
  { eventName: 'SEARCH_SUBMITTED', label: '搜索提交', count: 6, conversionRate: 1 },
  { eventName: 'SEARCH_RESULT_VIEWED', label: '搜索结果查看', count: 6, conversionRate: 1 },
  { eventName: 'VENUE_DETAIL_VIEWED', label: '场馆详情查看', count: 6, conversionRate: 1 },
  { eventName: 'SLOT_SELECTED', label: '时段选择', count: 4, conversionRate: 0.6667 },
  { eventName: 'PRICE_PREVIEW_SHOWN', label: '价格预览', count: 4, conversionRate: 1 },
  { eventName: 'RESERVATION_SUBMITTED', label: '预约提交', count: 2, conversionRate: 0.5 },
  { eventName: 'ORDER_CREATED', label: '订单创建', count: 2, conversionRate: 1 },
  { eventName: 'PAYMENT_INITIATED', label: '支付发起', count: 2, conversionRate: 1 },
  { eventName: 'PAYMENT_RESULT_RECORDED', label: '支付结果记录', count: 2, conversionRate: 1 }
]

const overview = {
  stepMetrics: steps,
  dropoffReasons: [{ reasonCode: 'NO_SELLABLE_SLOT', label: '无可售时段', count: 86 }],
  sourceChannels: [{ sourceChannel: 'WEB', count: 1840 }],
  topKeywords: ['羽毛球', '安静的自习室'],
  dataGaps: [
    '真实支付网关未接入，支付结果来自 DEV/模拟支付链路。',
    '真实通知投递未接入，站内信/模拟记录不能代表短信、邮件、飞书或企业微信送达。',
    '外部广告归因未接入，sourceChannel/campaignId 仅代表站内或前端传入线索。'
  ],
  sampleEvents: [
    { id: 1, eventName: 'PAYMENT_RESULT_RECORDED', eventStage: 'PAYMENT', resultStatus: 'RECORDED', venueId: 6, occurredAt: '2026-09-12T10:28:00' },
    { id: 2, eventName: 'SLOT_SELECTED', eventStage: 'DETAIL', resultStatus: 'RECORDED', venueId: 6, occurredAt: '2026-09-12T10:21:00' }
  ]
}

/** 已挂载面：每例结束必须卸载，否则上一例的实例仍会响应场馆广播重复发起查询。 */
const mounted: Array<ReturnType<typeof mount>> = []

function resetMocks(): void {
  vi.clearAllMocks()
  venueState.venues = [{ id: 6, name: '北岸生活场馆' }]
  venueState.loaded = true
  venueState.activeVenueId = 6
  venueState.scopeChangedAt = 0
  venueState.refresh.mockResolvedValue(undefined)
  funnelApiMock.overview.mockResolvedValue(overview)
  funnelApiMock.samples.mockResolvedValue(overview.sampleEvents)
}

async function mountView(): Promise<ReturnType<typeof mount>> {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const wrapper = mount(ShiguangVenueFunnelView, { attachTo: host })
  mounted.push(wrapper)
  await flushPromises()
  await flushPromises()
  return wrapper
}

type Vm = {
  surface: string
  sampleRows: Array<{ eventName: string }>
  selected: number
  dialog: string | null
  start: string
  end: string
  applied: { start: string; end: string; source: string }
}

describe('拾光场馆漏斗面', () => {
  beforeEach(() => {
    resetMocks()
    document.body.innerHTML = ''
  })

  afterEach(() => {
    for (const wrapper of mounted.splice(0)) wrapper.unmount()
  })

  it('sends the real date window and venue so the filters actually reach the server (B4-F16)', async () => {
    const wrapper = await mountView()
    const call = funnelApiMock.overview.mock.calls[0]
    expect(call[0]).toMatchObject({ venueId: 6 })
    expect(call[0].startDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(call[0].endDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(call[1]).toEqual(IOS)
    expect(wrapper.text()).toContain('北岸生活场馆')
    expect(wrapper.text()).toContain('全部渠道')
  })

  it('renders the nine cohort steps with the bar relative to the first step (B4-F2)', async () => {
    const wrapper = await mountView()
    const bars = wrapper.findAll('.b-step-bar')
    expect(bars).toHaveLength(9)
    // 条长＝该步/首步（共同基准），首步 100%（内联 display:block 与参考一致），第 4 步 4/6
    expect(bars[0].attributes('style')).toContain('display: block')
    expect(bars[0].attributes('style')).toContain('100%')
    expect(Number(bars[3].attributes('style')?.match(/width: ([\d.]+)%/)?.[1])).toBeCloseTo(66.6667, 3)
    // 逐步交集会话数单调不增；相邻转化取服务端下发值
    const rates = wrapper.findAll('.b-step-rate').map((node) => node.text())
    expect(rates[0]).toBe('起点')
    expect(rates[1]).toBe('100.0%')
    expect(rates[3]).toBe('66.7%')
    expect(rates[5]).toBe('50.0%')
  })

  it('only annotates channel counts while the filter is 全部渠道 (B4-F13)', async () => {
    const wrapper = await mountView()
    const labels = () => wrapper.findAll('[data-test="f-source"] option').map((node) => node.text())
    // 未筛选时聚合覆盖全部渠道，可以标注计数；没有数据的渠道标注「当前范围无数据」
    expect(labels()[1]).toContain('网页端（1840 次事件）')
    expect(labels().some((text) => text.includes('当前范围无数据'))).toBe(true)
    funnelApiMock.overview.mockResolvedValue({ ...overview, sourceChannels: [{ sourceChannel: 'WEB', count: 1840 }] })
    await wrapper.find('[data-test="f-source"]').setValue('WEB')
    await wrapper.find('[data-test="funnel-filter"]').trigger('submit')
    await flushPromises()
    // 已经筛成单一渠道后聚合只含该渠道，此时只给标签，不断言其它渠道「无数据」
    expect(labels()[1]).toBe('网页端')
    expect(labels().some((text) => text.includes('无数据'))).toBe(false)
  })

  it('opens on the first dropping step and follows the selection in the aside (B4-F1)', async () => {
    const wrapper = await mountView()
    // 默认选中第 4 步「时段选择」：与参考夹具 F.selected=3 同状态，也是真实数据里第一个下降步
    expect((wrapper.vm as unknown as Vm).selected).toBe(3)
    expect(wrapper.find('[data-test="f-step-detail"]').text()).toContain('时段选择')
    expect(wrapper.find('[data-test="f-step-detail"]').text()).toContain('不自动等于“没有库存”')
    await wrapper.find('[data-test="f-step-0"]').trigger('click')
    await flushPromises()
    expect((wrapper.vm as unknown as Vm).selected).toBe(0)
    expect(wrapper.find('[data-test="f-step-detail"]').text()).toContain('搜索提交')
    expect(wrapper.find('[data-test="f-step-detail"]').text()).toContain('本步是当前口径的起点，不显示转化率')
  })

  it('场馆口径下前两步显示为「—/平台口径」，条长与默认停留都以首个可计量步骤为准（F-22）', async () => {
    funnelApiMock.overview.mockResolvedValue({
      ...overview,
      stepMetrics: [
        {
          eventName: 'SEARCH_SUBMITTED', label: '搜索提交', measurement: 'NOT_APPLICABLE', startStep: false,
          count: null, conversionRate: null, notApplicableReason: '搜索提交发生在选定具体场馆之前，没有本场馆的归属事实。'
        },
        {
          eventName: 'SEARCH_RESULT_VIEWED', label: '搜索结果查看', measurement: 'NOT_APPLICABLE', startStep: false,
          count: null, conversionRate: null, notApplicableReason: '搜索结果查看同样不归属具体场馆。'
        },
        { eventName: 'VENUE_DETAIL_VIEWED', label: '场馆详情查看', measurement: 'MEASURED', startStep: true, count: 6, conversionRate: 1 },
        { eventName: 'SLOT_SELECTED', label: '时段选择', measurement: 'MEASURED', startStep: false, count: 4, conversionRate: 0.6667 },
        { eventName: 'PRICE_PREVIEW_SHOWN', label: '价格预览', measurement: 'MEASURED', startStep: false, count: 4, conversionRate: 1 },
        { eventName: 'RESERVATION_SUBMITTED', label: '预约提交', measurement: 'MEASURED', startStep: false, count: 2, conversionRate: 0.5 },
        { eventName: 'ORDER_CREATED', label: '订单创建', measurement: 'MEASURED', startStep: false, count: 2, conversionRate: 1 },
        { eventName: 'PAYMENT_INITIATED', label: '支付发起', measurement: 'MEASURED', startStep: false, count: 1, conversionRate: 0.5 },
        { eventName: 'PAYMENT_RESULT_RECORDED', label: '支付结果记录', measurement: 'MEASURED', startStep: false, count: 0, conversionRate: 0 }
      ],
      dataGaps: [...overview.dataGaps, '场馆口径只计量从「场馆详情查看」起的会话交集；搜索提交与搜索结果查看没有场馆归属，标为不适用而不是 0。']
    })
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="f-step-count-0"]').text()).toBe('—')
    expect(wrapper.find('[data-test="f-step-hint-0"]').text()).toBe('平台口径')
    expect(wrapper.find('[data-test="f-step-rate-0"]').text()).toBe('—')
    expect(wrapper.find('[data-test="f-step-count-0"]').text()).not.toContain('0')
    // 起点没有虚假转化率；真实零计数照常显示 0
    expect(wrapper.find('[data-test="f-step-rate-2"]').text()).toBe('起点')
    expect(wrapper.find('[data-test="f-step-count-8"]').text()).toBe('0')
    // 条长基准是首个可计量步骤（6）：时段选择 4/6
    expect(wrapper.find('[data-test="f-step-3"]').find('.b-step-bar').attributes('style')).toContain('width: 66.6667%')
    expect(wrapper.find('[data-test="f-step-0"]').find('.b-step-bar').attributes('style')).toContain('width: 0%')
    // 默认停在第一个下降的可计量步骤（第 4 步），不是被排除的搜索步骤
    expect((wrapper.vm as unknown as Vm).selected).toBe(3)
    expect(wrapper.find('.b-funnel-chart').attributes('aria-label')).toBe('7 步会话漏斗（场馆口径）')

    await wrapper.find('[data-test="f-step-0"]').trigger('click')
    await flushPromises()
    const aside = wrapper.find('[data-test="f-step-detail"]').text()
    expect(aside).toContain('选定具体场馆之前')
    expect(aside).toContain('不适用步骤不显示计数')
    expect(wrapper.text()).toContain('场馆口径只计量从「场馆详情查看」起的会话交集')
  })

  it('opens the metric definitions dialog and restores focus after closing', async () => {
    const wrapper = await mountView()
    const trigger = wrapper.find('[data-test="funnel-metrics"]').element as HTMLElement
    await wrapper.find('[data-test="funnel-metrics"]').trigger('click')
    await flushPromises()
    expect((wrapper.vm as unknown as Vm).dialog).toBe('metrics')
    expect(wrapper.find('[data-test="funnel-dialog-title"]').text()).toBe('统计口径')
    expect(wrapper.find('.b-dialog-body').text()).toContain('会话队列')
    expect(wrapper.find('.b-dialog-body').text()).toContain('相邻转化')
    expect(wrapper.find('.b-dialog-body').text()).toContain('条形长度')
    wrapper.find('dialog').element.dispatchEvent(new Event('close'))
    await flushPromises()
    expect(document.activeElement).toBe(trigger)
  })

  it('loads the sample dialog from the dedicated endpoint so it stops being caller-less (B4-F7)', async () => {
    const wrapper = await mountView()
    expect(funnelApiMock.samples).not.toHaveBeenCalled()
    await wrapper.find('[data-test="funnel-samples"]').trigger('click')
    await flushPromises()
    const call = funnelApiMock.samples.mock.calls[0]
    expect(call[0]).toMatchObject({ venueId: 6, startDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/) })
    expect(call[1]).toEqual(IOS)
    const table = wrapper.find('[data-test="funnel-sample-table"]').text()
    expect(table).toContain('支付结果记录')
    expect(table).toContain('支付')
    expect(table).toContain('详情')
    expect(table).toContain('09-12 10:28')
    expect(wrapper.find('.b-dialog-body').text()).toContain('最多展示 20 条样本事件')
  })

  it('shows the real failure inside the sample dialog instead of falling back to embedded rows', async () => {
    const wrapper = await mountView()
    funnelApiMock.samples.mockRejectedValue({ response: { data: { message: '样本事件读取失败' } } })
    await wrapper.find('[data-test="funnel-samples"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="funnel-samples-error"]').text()).toContain('样本事件读取失败')
    expect(wrapper.find('[data-test="funnel-sample-table"]').exists()).toBe(false)
    funnelApiMock.samples.mockResolvedValue([overview.sampleEvents[1]])
    await wrapper.find('[data-test="funnel-samples-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="funnel-samples-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-sample-table"]').text()).toContain('时段选择')
  })

  it('renders the server data gaps verbatim and never implies causation', async () => {
    const wrapper = await mountView()
    const gaps = wrapper.find('.b-gap-list').text()
    expect(gaps).toContain('DEV/模拟支付链路')
    expect(gaps).toContain('不能代表短信、邮件、飞书或企业微信送达')
    expect(gaps).toContain('仅代表站内或前端传入线索')
  })

  it('rejects an inverted window before requesting and surfaces the server message on failure (B4-F5)', async () => {
    const wrapper = await mountView()
    funnelApiMock.overview.mockClear()
    await wrapper.find('[data-test="f-start"]').setValue('2026-09-12')
    await wrapper.find('[data-test="f-end"]').setValue('2026-09-06')
    await wrapper.find('[data-test="funnel-filter"]').trigger('submit')
    await flushPromises()
    expect(funnelApiMock.overview).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="f-error"]').text()).toContain('结束日期不能早于开始日期')

    // 服务端 400 的中文文案必须能显示出来，而不是 Axios 默认英文串
    funnelApiMock.overview.mockRejectedValue({ response: { data: { message: '漏斗查询开始日期不能晚于结束日期' } } })
    await wrapper.find('[data-test="f-end"]').setValue('2026-09-20')
    await wrapper.find('[data-test="funnel-filter"]').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test="funnel-error"]').text()).toContain('漏斗查询开始日期不能晚于结束日期')
    expect(showToast).toHaveBeenCalledWith('漏斗查询开始日期不能晚于结束日期')
  })

  it('drops stale responses so a slow earlier query cannot overwrite the latest one (B4-F4)', async () => {
    let resolveFirst: (value: unknown) => void = () => {}
    const slow = new Promise((resolve) => { resolveFirst = resolve })
    funnelApiMock.overview.mockReturnValueOnce(slow)
    const wrapper = await mountView()
    // 第二次查询立即返回新结果
    funnelApiMock.overview.mockResolvedValueOnce({ ...overview, topKeywords: ['篮球场'] })
    await wrapper.find('[data-test="f-start"]').setValue('2026-08-01')
    await wrapper.find('[data-test="funnel-filter"]').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('篮球场')
    // 迟到的第一次响应必须被丢弃
    resolveFirst({ ...overview, topKeywords: ['过期结果'] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('过期结果')
    expect(wrapper.text()).toContain('篮球场')
  })

  it('clears the previous venue result and reloads when the venue scope changes (B4-F3)', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('北岸生活场馆')
    funnelApiMock.overview.mockResolvedValue({ ...overview, topKeywords: ['东园关键词'] })
    venueState.activeVenueId = 7
    venueState.venues = [{ id: 6, name: '北岸生活场馆' }, { id: 7, name: '东园运动中心' }]
    venueState.scopeChangedAt = 1
    await flushPromises()
    await flushPromises()
    expect(funnelApiMock.overview).toHaveBeenCalledTimes(2)
    expect(funnelApiMock.overview.mock.calls[1][0]).toMatchObject({ venueId: 7 })
    expect(wrapper.text()).toContain('东园运动中心')
  })

  it('renders the no-venue state and never requests without an authorised venue (B4-F3)', async () => {
    venueState.activeVenueId = null
    venueState.venues = []
    venueState.loaded = true
    const wrapper = await mountView()
    expect(wrapper.find('[data-test="funnel-no-venue"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('原场馆数据已清空')
    await wrapper.find('[data-test="funnel-recheck"]').trigger('click')
    await flushPromises()
    expect(venueState.refresh).toHaveBeenCalled()
  })

  it('does not fabricate an adjacent rate when the previous step has no sessions', async () => {
    funnelApiMock.overview.mockResolvedValue({
      ...overview,
      stepMetrics: [
        { eventName: 'SEARCH_SUBMITTED', label: '搜索提交', count: 0, conversionRate: 0 },
        { eventName: 'SEARCH_RESULT_VIEWED', label: '搜索结果查看', count: 0, conversionRate: 0 }
      ]
    })
    const wrapper = await mountView()
    const rates = wrapper.findAll('.b-step-rate').map((node) => node.text())
    expect(rates[0]).toBe('起点')
    expect(rates[1]).toBe('—')
  })
})
