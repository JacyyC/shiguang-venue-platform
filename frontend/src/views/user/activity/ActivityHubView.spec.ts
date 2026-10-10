import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ActivityHubView from './ActivityHubView.vue'
import ActivityCreateDialog from './ActivityCreateDialog.vue'

const activityApiMock = vi.hoisted(() => ({
  activities: vi.fn(), companions: vi.fn(), activity: vi.fn(), createActivity: vi.fn(), createCompanion: vi.fn()
}))
const communityApiMock = vi.hoisted(() => ({ groups: vi.fn() }))
const venueApiMock = vi.hoisted(() => ({ list: vi.fn(), spaces: vi.fn() }))
const reservationApiMock = vi.hoisted(() => ({ my: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({ query: {}, path: '/activities' }))

vi.mock('@/api/activity', () => ({ activityApi: activityApiMock }))
vi.mock('@/api/community', () => ({
  communityApi: communityApiMock,
  communityError: () => ({ kind: 'failed', message: '活动加载失败' }),
  createIdempotencyRegistry: () => ({ keyFor: () => 'stable-key', complete: vi.fn() })
}))
vi.mock('@/api/venue', () => ({ venueApi: venueApiMock }))
vi.mock('@/api/reservation', () => ({ reservationApi: reservationApiMock }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock }))

const stubs = {
  'el-tabs': { template: '<div><slot /></div>' },
  'el-tab-pane': { template: '<section><slot /></section>' },
  'el-button': { props: ['disabled', 'loading'], template: '<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>' },
  'el-input': { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-input-number': { template: '<input />' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-date-picker': { template: '<input />' },
  'el-popover': { template: '<div><slot name="reference" /><slot /></div>' },
  'el-radio-group': { template: '<div><slot /></div>' },
  'el-radio-button': { template: '<button type="button"><slot /></button>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  'el-dialog': { template: '<aside><slot /><slot name="footer" /></aside>' },
  'el-alert': { props: ['title'], template: '<div role="status">{{ title }}<slot /></div>' },
  'el-skeleton': { template: '<div>加载中</div>' },
  'router-link': { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : \'#\'"><slot /></a>' },
  'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-icon': { template: '<i><slot /></i>' },
  Search: true,
  Close: true,
  Plus: true,
  UserFilled: true,
  Clock: true,
  Location: true,
  User: true,
  Calendar: true
}

const activity = {
  id: 41,
  activityNo: 'ACT-41',
  groupId: 11,
  organizerUserId: 7,
  activityType: 'STUDY',
  title: '期末复习',
  description: '周末一起复习',
  startTime: '2099-08-01T09:00:00',
  endTime: '2099-08-01T11:00:00',
  location: { type: 'CAMPUS_AREA', areaCode: 'NORTH_CAMPUS', commitmentStatus: 'CONFIRMED' },
  capacityTotal: 8,
  participantCount: 1,
  status: 'PUBLISHED',
  versionNo: 1
}

describe('ActivityHubView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeMock.query = {}
    activityApiMock.activities.mockResolvedValue({ records: [activity], nextCursor: null, hasMore: false })
    activityApiMock.companions.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })
    activityApiMock.createActivity.mockResolvedValue({ ...activity, id: 42 })
    communityApiMock.groups.mockResolvedValue({ records: [{ id: 11, name: '学习组', currentMemberStatus: 'ACTIVE' }], nextCursor: null, hasMore: false })
    venueApiMock.list.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 100 })
    venueApiMock.spaces.mockResolvedValue([])
    reservationApiMock.my.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 100 })
  })

  it('renders real activity records and commitment state without placeholder content', async () => {
    const wrapper = mount(ActivityHubView, { global: { stubs } })
    await flushPromises()

    expect(wrapper.find('.activity-head h1.sg-display-xl').text()).toBe('好场地，也要好搭子。')
    expect(wrapper.findAll('.sg-searchrow')).toHaveLength(1)
    expect(wrapper.find('.sg-search-pill input[type="search"]').exists()).toBe(true)
    /* V11 索引栏：药丸行退役，类型/时间/场馆三维度，当前维度选项平铺且选中态放大 */
    expect(wrapper.find('.sg-indexrail').exists()).toBe(true)
    expect(wrapper.findAll('.sg-indexrail-dim')).toHaveLength(3)
    expect(wrapper.findAll('.sg-indexrail-opt').map((button) => button.text())).toContain('全部')
    expect(wrapper.find('[data-test="indexrail-opt-activityType-all"].on').exists()).toBe(true)
    expect(wrapper.find('.activity-time-chip').exists()).toBe(false)
    expect(wrapper.find('.activity-list.sg-stagger--loose').exists()).toBe(true)
    expect(wrapper.text()).toContain('期末复习')
    expect(wrapper.text()).toContain('地点待确认')
    expect(wrapper.text()).toContain('学习自习')
    expect(activityApiMock.activities).toHaveBeenCalledWith({
      keyword: undefined,
      activityType: undefined,
      fromTime: undefined,
      toTime: undefined,
      venueId: undefined,
      areaCode: undefined,
      limit: 20,
      cursorId: undefined
    })
    expect(wrapper.text()).not.toContain('示例活动')
  })

  it('debounces keyword search and applies index-rail type selection immediately without query buttons', async () => {
    vi.useFakeTimers()
    const wrapper = mount(ActivityHubView, { global: { stubs } })
    await flushPromises()
    activityApiMock.activities.mockClear()

    await wrapper.get('.sg-search-pill input').setValue('期末')
    expect(activityApiMock.activities).not.toHaveBeenCalled()
    vi.advanceTimersByTime(299)
    expect(activityApiMock.activities).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(activityApiMock.activities).toHaveBeenCalledWith(expect.objectContaining({ keyword: '期末' }))

    activityApiMock.activities.mockClear()
    await wrapper.get('[data-test="indexrail-opt-activityType-SPORTS"]').trigger('click')
    await flushPromises()
    expect(activityApiMock.activities).toHaveBeenCalledWith(expect.objectContaining({ activityType: 'SPORTS' }))
    expect(wrapper.get('[data-test="indexrail-opt-activityType-SPORTS"]').classes()).toContain('on')
    expect(wrapper.get('[data-test="indexrail-opt-activityType-all"]').classes()).not.toContain('on')
    expect(wrapper.findAll('button').map((button) => button.text())).not.toContain('查询')
    expect(wrapper.findAll('button').map((button) => button.text())).not.toContain('重置')

    wrapper.unmount()
    vi.useRealTimers()
  })

  it('index rail switches dimensions, applies time presets and venue filter with unchanged query contract', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2099, 7, 5, 10, 0, 0)) // 2099-08-05 周三
    venueApiMock.list.mockResolvedValue({ records: [{ id: 9, name: '东区综合体育馆', status: 'ACTIVE' }], total: 1, pageNum: 1, pageSize: 100 })
    const wrapper = mount(ActivityHubView, { global: { stubs } })
    await flushPromises()
    activityApiMock.activities.mockClear()
    // V11.1 默认收起：先点摘要条弹出索引
    await wrapper.get('[data-test="indexrail-toggle"]').trigger('click')

    // 切到时间维度：选项区随维度切换（v-show），时间不限为当前选中
    await wrapper.get('[data-test="indexrail-dim-time"]').trigger('click')
    expect(wrapper.find('[data-test="indexrail-opt-time-all"]').isVisible()).toBe(true)
    expect(wrapper.find('[data-test="indexrail-opt-activityType-all"]').isVisible()).toBe(false)

    // 今天预设：换算既有 fromTime/toTime 口径
    await wrapper.get('[data-test="indexrail-opt-time-today"]').trigger('click')
    await flushPromises()
    expect(activityApiMock.activities).toHaveBeenCalledWith(expect.objectContaining({
      fromTime: '2099-08-05T00:00:00',
      toTime: '2099-08-05T23:59:59'
    }))

    // 本周（周一起）：2099-08-05 为周三 → 08-03 至 08-09
    activityApiMock.activities.mockClear()
    await wrapper.get('[data-test="indexrail-opt-time-week"]').trigger('click')
    await flushPromises()
    expect(activityApiMock.activities).toHaveBeenCalledWith(expect.objectContaining({
      fromTime: '2099-08-03T00:00:00',
      toTime: '2099-08-09T23:59:59'
    }))

    // 自定义…：展开既有 datetimerange 面板（语义不变），清除按钮可选
    await wrapper.get('[data-test="indexrail-opt-time-custom"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.activity-time-panel').exists()).toBe(true)
    expect(wrapper.find('.activity-time-panel .el-date-editor, .activity-time-panel input').exists()).toBe(true)

    // 场馆维度：选场馆 → venueId 入参，接口合同不变
    await wrapper.get('[data-test="indexrail-dim-venue"]').trigger('click')
    activityApiMock.activities.mockClear()
    await wrapper.get('[data-test="indexrail-opt-venue-9"]').trigger('click')
    await flushPromises()
    expect(activityApiMock.activities).toHaveBeenCalledWith(expect.objectContaining({ venueId: 9 }))
    expect(wrapper.get('[data-test="indexrail-opt-venue-9"]').classes()).toContain('on')
    expect(wrapper.get('[data-test="indexrail-opt-venue-all"]').attributes('aria-checked')).toBe('false')

    // 键盘：方向键在组内换选（焦点随选）——从场馆 9 左移回「全部场馆」，venueId 还原为 undefined
    activityApiMock.activities.mockClear()
    await wrapper.get('[data-test="indexrail-opt-venue-9"]').trigger('keydown', { key: 'ArrowLeft' })
    await flushPromises()
    expect(wrapper.get('[data-test="indexrail-opt-venue-all"]').classes()).toContain('on')
    expect(activityApiMock.activities).toHaveBeenCalledWith(expect.objectContaining({ venueId: undefined }))

    // 更多条件折叠仅找搭子页签保留：activities 页签下不再渲染
    expect(wrapper.find('.activity-advanced-filters').exists()).toBe(false)

    wrapper.unmount()
    vi.useRealTimers()
  })

  it('uses the shared EmptyState action when discovery has no records', async () => {
    activityApiMock.activities.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })
    const wrapper = mount(ActivityHubView, { global: { stubs } })
    await flushPromises()

    expect(wrapper.findAll('.empty-state').length).toBeGreaterThanOrEqual(1)
    expect(wrapper.find('.empty-action .sg-btn-capsule').text()).toContain('清除筛选')
  })

  it('hands the create entry points to the shared dialog instead of keeping a second copy', async () => {
    const wrapper = mount(ActivityHubView, { global: { stubs } })
    await flushPromises()

    const dialog = wrapper.findComponent(ActivityCreateDialog)
    expect(dialog.exists(), '创建草稿弹窗应为共用件').toBe(true)
    const dialogVm = dialog.vm as unknown as { createOpen: boolean }
    expect(dialogVm.createOpen).toBe(false)

    const createButton = wrapper.findAll('button').find((button) => button.text().includes('发起活动'))
    expect(createButton, '活动页应保留发起活动入口').toBeTruthy()
    await createButton?.trigger('click')
    await flushPromises()

    // 入口把创建交给共用弹窗（成员校验与幂等 registry 都在这一个实现里）
    expect(dialogVm.createOpen).toBe(true)
  })

  it('opens the linked-companion dialog from the deep link and clears the query after closing', async () => {
    routeMock.query = { create: 'companion', activityId: '41' }
    activityApiMock.activity.mockResolvedValue(activity)
    const wrapper = mount(ActivityHubView, { global: { stubs } })
    await flushPromises()

    expect(activityApiMock.activity).toHaveBeenCalledWith(41)
    const dialog = wrapper.findComponent(ActivityCreateDialog)
    const dialogVm = dialog.vm as unknown as { createOpen: boolean; draft: { groupId?: number; activityType: string } }
    expect(dialogVm.createOpen).toBe(true)
    expect(dialogVm.draft.groupId).toBe(11)
    expect(dialogVm.draft.activityType).toBe('STUDY')

    // 关窗后清掉 create query，避免刷新重复开窗（此刻 query 里仍有 create）
    await dialog.vm.$emit('closed')
    expect(routerMock.replace).toHaveBeenCalledWith('/activities')  })

  it('shows a recoverable error when the authoritative discovery request is rate limited', async () => {
    activityApiMock.activities.mockRejectedValue({ response: { status: 429 } })
    const wrapper = mount(ActivityHubView, { global: { stubs } })
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('活动加载失败')
    expect(wrapper.text()).not.toContain('期末复习')
  })

  it('renders activity cards as whole-card links with serif participant counts', async () => {
    activityApiMock.companions.mockResolvedValue({
      records: [{
        id: 61,
        activityNo: 'CR-61',
        activityId: null,
        organizerUserId: 7,
        activityType: 'STUDY',
        title: '自习找搭子',
        description: '一起自习',
        windowStart: '2099-08-01T09:00:00',
        windowEnd: '2099-08-01T11:00:00',
        location: { type: 'CAMPUS_AREA', areaCode: 'NORTH_CAMPUS', commitmentStatus: 'TENTATIVE' },
        capacityTotal: 4,
        participantCount: 2,
        status: 'OPEN',
        versionNo: 1
      }],
      nextCursor: null,
      hasMore: false
    })
    const wrapper = mount(ActivityHubView, { global: { stubs } })
    await flushPromises()

    const card = wrapper.findAll('a.activity-card').find((node) => node.text().includes('期末复习'))
    expect(card, '活动卡应为整卡可点链接').toBeTruthy()
    expect(card?.attributes('href')).toBe('/activities/41')
    expect(card?.find('.activity-count-badge strong.sg-num').exists(), '参与数应为衬线大字角标').toBe(true)
    expect(card?.text()).toContain('查看活动')

    const view = wrapper.vm as unknown as { changeTab: (name: string) => Promise<void> }
    await view.changeTab('companions')
    await flushPromises()
    const companion = wrapper.findAll('a.activity-card').find((node) => node.text().includes('自习找搭子'))
    expect(companion, '找搭子卡应为整卡可点链接').toBeTruthy()
    expect(companion?.attributes('href')).toBe('/companion-requests/61')
  })
})
