import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { createTestRouter } from './test-router'
import VenueListView from './VenueListView.vue'
import type { VenueListItem } from '@/api/types'

const venueApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  facets: vi.fn()
}))

const funnelStoreMock = vi.hoisted(() => ({
  track: vi.fn()
}))

vi.mock('@/api/venue', () => ({ venueApi: venueApiMock }))
vi.mock('@/stores/funnel', () => ({ useFunnelStore: () => funnelStoreMock }))

const stubs = {
  EmptyState: { template: '<div>empty</div>' },
  'el-skeleton': true,
  'el-icon': { template: '<span><slot /></span>' },
  Search: true,
  Close: true
}

const buildVenue = (overrides: Partial<VenueListItem> & { id: number; name: string }): VenueListItem =>
  ({
    address: `${overrides.name}地址`,
    area: '城东',
    status: 'ACTIVE',
    activeSpaceCount: 2,
    minPrice: 30,
    spaceTypes: ['BASKETBALL'],
    ...overrides
  }) as VenueListItem

const morningVenue = buildVenue({
  id: 2,
  name: '晨曦体育中心',
  area: '城东',
  minPrice: 35,
  activeSpaceCount: 6,
  spaceTypes: ['BASKETBALL', 'BADMINTON']
})

async function mountAt(path: string, provide?: Record<symbol, unknown>) {
  const router = await createTestRouter(path)
  const wrapper = mount(VenueListView, { global: { plugins: [router], stubs }, provide })
  await flushPromises()
  return { wrapper, router }
}

const ddRoots = (wrapper: ReturnType<typeof mount>) => wrapper.findAll('.sg-dd')

const openDd = async (wrapper: ReturnType<typeof mount>, index: number) => {
  await ddRoots(wrapper)[index].find('.sg-dd-toggle').trigger('click')
}

describe('VenueListView 类型分区浏览', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    funnelStoreMock.track.mockResolvedValue(undefined)
    venueApiMock.facets.mockResolvedValue({ areas: ['大学城', '城东', '城西'], spaceTypes: ['BADMINTON', 'BASKETBALL', 'STUDY_ROOM'] })
    venueApiMock.list.mockImplementation(async () => ({
      records: [
        morningVenue,
        buildVenue({ id: 3, name: '劲浪篮球公园', area: '城西', minPrice: undefined }),
        buildVenue({ id: 4, name: '云杉自习书院', area: '大学城', minPrice: 8, spaceTypes: ['STUDY_ROOM'], activeSpaceCount: 3 })
      ],
      total: 3,
      pageNum: 1,
      pageSize: 50
    }))
  })

  it('renders type secbands with multi-type venues repeated across sections', async () => {
    const { wrapper } = await mountAt('/venues')

    const sections = wrapper.findAll('.venue-section')
    expect(sections.map((section) => section.get('h4').text())).toEqual(['羽毛球馆', '篮球馆', '自习室'])
    expect(sections[0].get('.sg-secband-cnt').text()).toBe('1 家')
    expect(sections[1].get('.sg-secband-cnt').text()).toBe('2 家')
    // 一馆多类：晨曦体育中心同时出现在羽毛球馆与篮球馆分区
    expect(sections[0].text()).toContain('晨曦体育中心')
    expect(sections[1].text()).toContain('晨曦体育中心')

    wrapper.unmount()
  })

  it('renders preview card language: chinese kicker, head count, serif name, area, type summary and price', async () => {
    const { wrapper } = await mountAt('/venues')

    expect(wrapper.get('.sg-kicker').text()).toBe('场馆检索')
    expect(wrapper.text()).not.toContain('VENUES')
    expect(wrapper.get('.venue-count').text()).toBe('共 3 家开放场馆')
    expect(wrapper.find('button[title="刷新"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('刷新')

    const card = wrapper.findAll('.venue-card').find((card) => card.text().includes('晨曦体育中心'))!
    expect(card.get('.venue-name').classes()).toContain('sg-serif')
    expect(card.get('.venue-cmeta').text()).toContain('城东')
    expect(card.get('.venue-cmeta').text()).toContain('篮球 / 羽毛球')
    expect(card.get('.venue-cmeta').text()).toContain('6 个可预约场地')
    // V3 海报缩版：价格移入卡面右下价格章（¥35/时）
    expect(card.get('.venue-price-chip').text()).toBe('¥35/时')
    expect(card.find('.venue-price').exists()).toBe(false)

    const noPriceCard = wrapper.findAll('.venue-card').find((card) => card.text().includes('劲浪篮球公园'))!
    expect(noPriceCard.find('.venue-price').text()).toBe('价格待更新')

    wrapper.unmount()
  })

  it('renders the filter index rail below the search row and filters venues locally', async () => {
    const { wrapper } = await mountAt('/venues')
    venueApiMock.list.mockClear()

    // V11 结构：搜索行在上、索引栏在下（发丝线分区）
    const searchRow = wrapper.element.querySelector('.sg-searchrow[role="search"]')!
    const rail = wrapper.element.querySelector('[data-test="venue-indexrail"]')!
    expect(rail.compareDocumentPosition(searchRow) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy()

    // 类型维度选项 = facets.spaceTypes canonical 归并去重（V10 口径承接），区域升为第二维度
    const typeOptions = wrapper.findAll('[data-test^="indexrail-opt-spaceType-"]')
    expect(typeOptions.map((option) => option.text())).toEqual(['全部类型', '羽毛球馆', '篮球馆', '自习室'])
    expect(wrapper.get('[data-test="indexrail-opt-spaceType-all"]').attributes('aria-checked')).toBe('true')

    // 点「羽毛球馆」：本地过滤不新增请求，计数与分区联动
    await wrapper.get('[data-test="indexrail-opt-spaceType-BADMINTON"]').trigger('click')
    await flushPromises()
    expect(venueApiMock.list).not.toHaveBeenCalled()
    expect(wrapper.get('.venue-count').text()).toBe('共 1 家开放场馆')
    expect(wrapper.findAll('.venue-section').map((section) => section.get('h4').text())).toEqual(['羽毛球馆'])
    expect(wrapper.get('[data-test="indexrail-opt-spaceType-BADMINTON"]').classes()).toContain('on')

    // 「全部类型」还原全量
    await wrapper.get('[data-test="indexrail-opt-spaceType-all"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.venue-count').text()).toBe('共 3 家开放场馆')
    expect(wrapper.findAll('.venue-section')).toHaveLength(3)

    wrapper.unmount()
  })

  it('shows the empty state when the selected type has no venues', async () => {
    venueApiMock.list.mockResolvedValue({
      records: [buildVenue({ id: 9, name: '纯篮球馆' })],
      total: 1,
      pageNum: 1,
      pageSize: 50
    })
    const { wrapper } = await mountAt('/venues')

    await wrapper.get('[data-test="indexrail-opt-spaceType-BADMINTON"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.venue-section')).toHaveLength(0)
    expect(wrapper.text()).toContain('empty')

    wrapper.unmount()
  })

  it('loads facets and filters server-side when an area is picked from the rail', async () => {
    const { wrapper } = await mountAt('/venues')
    expect(venueApiMock.facets).toHaveBeenCalledTimes(1)

    // 区域自搜索行下拉吸收为索引栏维度（V11），选项来自 facets 全量并按 zh-CN 排序
    venueApiMock.list.mockClear()
    await wrapper.get('[data-test="indexrail-dim-area"]').trigger('click')
    const areaOptions = wrapper.findAll('[data-test^="indexrail-opt-area-"]')
    expect(areaOptions.map((option) => option.text())).toEqual(['全部区域', '城东', '城西', '大学城'])

    await wrapper.get('[data-test="indexrail-opt-area-城西"]').trigger('click')
    await flushPromises()
    expect(venueApiMock.list).toHaveBeenCalledWith(expect.objectContaining({ area: '城西', pageNum: 1 }))

    wrapper.unmount()
  })

  it('defaults to open-only venues and exposes 含已停用 inside advanced conditions', async () => {
    const { wrapper } = await mountAt('/venues')
    expect(venueApiMock.list).toHaveBeenCalledWith(expect.objectContaining({ status: 'ACTIVE' }))

    const adv = wrapper.get('.venue-adv')
    expect(adv.get('summary').text()).toContain('高级条件')
    expect(adv.find('.sg-dd-toggle').exists()).toBe(true)

    venueApiMock.list.mockClear()
    await openDd(wrapper, 0)
    const labels = ddRoots(wrapper)[0].findAll('.sg-dd-opt').map((option) => option.text())
    expect(labels).toEqual(['仅看开放中（默认）', '含已停用'])

    await ddRoots(wrapper)[0].findAll('.sg-dd-opt')[1].trigger('click')
    await flushPromises()
    expect(venueApiMock.list).toHaveBeenCalledWith(expect.objectContaining({ status: '', pageNum: 1 }))
    expect(wrapper.get('.venue-count').text()).toBe('共 3 家场馆')

    wrapper.unmount()
  })

  it('expands a section in place beyond the six-item preview and collapses back', async () => {
    venueApiMock.list.mockResolvedValue({
      records: Array.from({ length: 7 }, (_, index) =>
        buildVenue({ id: index + 1, name: `篮球馆 ${index + 1} 号`, minPrice: 20 + index })),
      total: 7,
      pageNum: 1,
      pageSize: 50
    })
    const { wrapper } = await mountAt('/venues')

    const section = wrapper.get('.venue-section')
    expect(section.findAll('.venue-card')).toHaveLength(6)
    expect(section.get('.sg-secband-more').text()).toBe('查看全部 →')

    await section.get('.sg-secband-more').trigger('click')
    await flushPromises()
    const expanded = wrapper.get('.venue-section')
    expect(expanded.findAll('.venue-card')).toHaveLength(7)
    expect(expanded.get('.sg-secband-more').text()).toBe('收起')

    await expanded.get('.sg-secband-more').trigger('click')
    await flushPromises()
    expect(wrapper.get('.venue-section').findAll('.venue-card')).toHaveLength(6)

    wrapper.unmount()
  })

  it('shows the empty state when no venue matches the filters', async () => {
    venueApiMock.list.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 50 })
    const { wrapper } = await mountAt('/venues')

    expect(wrapper.findAll('.venue-section')).toHaveLength(0)
    expect(wrapper.text()).toContain('empty')

    wrapper.unmount()
  })

  it('keeps the error state with a working retry action', async () => {
    venueApiMock.list.mockRejectedValue(new Error('network down'))
    const { wrapper } = await mountAt('/venues')

    const errorState = wrapper.get('.sg-state-error')
    expect(errorState.text()).toContain('场馆列表暂时无法加载')

    venueApiMock.list.mockResolvedValue({ records: [morningVenue], total: 1, pageNum: 1, pageSize: 50 })
    await errorState.get('button.retry').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.venue-card').length).toBeGreaterThan(0)

    wrapper.unmount()
  })

  it('still renders sections when the facets request fails', async () => {
    venueApiMock.facets.mockRejectedValue(new Error('facets down'))
    const { wrapper } = await mountAt('/venues')

    expect(wrapper.findAll('.venue-section')).toHaveLength(3)
    // facets 失败：索引栏两维度均空 → 整件隐藏（降级同 V10）
    expect(wrapper.find('[data-test="venue-indexrail"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('keeps the study marker on study venue cards with the static background (no scene switching)', async () => {
    const router = await createTestRouter('/venues')
    const wrapper = mount(VenueListView, {
      global: {
        plugins: [router],
        stubs
      }
    })
    await flushPromises()

    const studyCard = wrapper.findAll('.venue-card').find((card) => card.text().includes('云杉自习书院'))!
    expect(studyCard.attributes('data-study')).toBe('true')
    await studyCard.trigger('mouseenter')
    await studyCard.trigger('mouseleave')
    expect(studyCard.attributes('data-study')).toBe('true')
    wrapper.unmount()
  })

  it('debounces keyword changes before reloading', async () => {
    vi.useFakeTimers()
    const { wrapper } = await mountAt('/venues')
    venueApiMock.list.mockClear()

    await wrapper.get('.sg-search-pill input').setValue('网球')
    expect(venueApiMock.list).not.toHaveBeenCalled()
    vi.advanceTimersByTime(299)
    expect(venueApiMock.list).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(venueApiMock.list).toHaveBeenCalledWith(expect.objectContaining({ keyword: '网球', pageNum: 1 }))

    wrapper.unmount()
    vi.useRealTimers()
  })

  it('reloads when the venue keyword query changes in place', async () => {
    const { wrapper, router } = await mountAt('/venues')
    venueApiMock.list.mockClear()

    await router.push({ path: '/venues', query: { keyword: '羽毛球' } })
    await flushPromises()

    expect(venueApiMock.list).toHaveBeenCalledWith(expect.objectContaining({ keyword: '羽毛球' }))
    wrapper.unmount()
  })
})

describe('VenueListView funnel tracking', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    funnelStoreMock.track.mockResolvedValue(undefined)
    venueApiMock.facets.mockResolvedValue({ areas: [], spaceTypes: [] })
    venueApiMock.list.mockResolvedValue({
      records: [morningVenue],
      total: 1,
      pageNum: 1,
      pageSize: 50
    })
  })

  it('records search submit and result viewed events around venue search', async () => {
    const { wrapper, router } = await mountAt('/venues')

    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'SEARCH_SUBMITTED',
      eventStage: 'SEARCH'
    }))
    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'SEARCH_RESULT_VIEWED',
      eventStage: 'SEARCH',
      metadata: expect.objectContaining({ resultCount: 1, total: 1 })
    }))

    await wrapper.get('.venue-card').trigger('click')
    await flushPromises()

    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'VENUE_DETAIL_VIEWED',
      eventStage: 'DETAIL',
      venueId: 2
    }))
    expect(router.currentRoute.value.path).toBe('/venues/2')

    wrapper.unmount()
  })

  it('does not wait for search tracking before loading venues', async () => {
    funnelStoreMock.track.mockReturnValue(new Promise(() => undefined))

    const { wrapper } = await mountAt('/venues')
    await nextTick()

    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'SEARCH_SUBMITTED',
      eventStage: 'SEARCH'
    }))
    expect(venueApiMock.list).toHaveBeenCalledWith(expect.objectContaining({ keyword: '', status: 'ACTIVE' }))
    expect(funnelStoreMock.track.mock.invocationCallOrder[0]).toBeLessThan(venueApiMock.list.mock.invocationCallOrder[0])

    wrapper.unmount()
  })

  it('seeds the keyword filter from the search query deep link', async () => {
    const { wrapper } = await mountAt('/venues?keyword=网球场')

    expect(venueApiMock.list).toHaveBeenCalledWith(expect.objectContaining({ keyword: '网球场', status: 'ACTIVE' }))
    expect(funnelStoreMock.track).toHaveBeenCalledWith(expect.objectContaining({
      eventName: 'SEARCH_SUBMITTED',
      eventStage: 'SEARCH',
      keyword: '网球场'
    }))

    wrapper.unmount()
  })
})
