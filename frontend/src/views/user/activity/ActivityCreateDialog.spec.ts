import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ActivityCreateDialog from './ActivityCreateDialog.vue'
import SgDropdown from '@/components/SgDropdown.vue'

const activityApiMock = vi.hoisted(() => ({
  createActivity: vi.fn(),
  createCompanion: vi.fn()
}))
const communityApiMock = vi.hoisted(() => ({ groups: vi.fn() }))
const venueApiMock = vi.hoisted(() => ({ list: vi.fn(), spaces: vi.fn() }))
const reservationApiMock = vi.hoisted(() => ({ my: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))

vi.mock('@/api/activity', () => ({ activityApi: activityApiMock }))
vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() }
}))
// 只替换 communityApi：communityError 与 createIdempotencyRegistry 用真实实现，
// 幂等键的复用/换键语义正是本组用例要验证的合同，不能在 mock 里重写一份。
vi.mock('@/api/community', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/community')>()
  return { ...actual, communityApi: communityApiMock }
})
vi.mock('@/api/venue', () => ({ venueApi: venueApiMock }))
vi.mock('@/api/reservation', () => ({ reservationApi: reservationApiMock }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => ({ query: {} }) }))

const stubs = {
  'el-dialog': { props: ['modelValue'], template: '<aside v-if="modelValue"><slot /><slot name="footer" /></aside>' },
  'el-input': { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-input-number': { props: ['modelValue'], template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />' },
  'el-date-picker': { template: '<input />' },
  'el-radio-group': { template: '<div><slot /></div>' },
  'el-radio-button': { template: '<button type="button"><slot /></button>' }
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

interface DialogVm {
  open: (kind: 'ACTIVITY' | 'COMPANION_REQUEST', linked?: typeof activity | null) => Promise<void>
  createTarget: () => Promise<void>
  draft: {
    groupId?: number
    activityType: string
    title: string
    description: string
    timeRange: string[]
    capacityTotal: number
    locationType: string
    areaCode: string
    venueId?: number
    spaceId?: number
    reservationId?: number
  }
  canCreate: boolean
  formError: string
  createOpen: boolean
}

const mountDialog = () => mount(ActivityCreateDialog, { global: { stubs } })
const vmOf = (wrapper: ReturnType<typeof mountDialog>) => wrapper.vm as unknown as DialogVm

describe('ActivityCreateDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    activityApiMock.createActivity.mockResolvedValue({ ...activity, id: 42 })
    activityApiMock.createCompanion.mockResolvedValue({ id: 61, title: '自习找搭子' })
    communityApiMock.groups.mockResolvedValue({
      records: [
        { id: 11, name: '学习组', currentMemberStatus: 'ACTIVE' },
        { id: 12, name: '待审组', currentMemberStatus: 'PENDING' }
      ],
      nextCursor: null,
      hasMore: false
    })
    venueApiMock.list.mockResolvedValue({ records: [{ id: 9, name: '东区综合体育馆', status: 'ACTIVE' }], total: 1, pageNum: 1, pageSize: 100 })
    venueApiMock.spaces.mockResolvedValue([{ id: 21, name: '自习室 A', venueId: 9 }])
    reservationApiMock.my.mockResolvedValue({
      records: [{
        id: 33, venueId: 9, spaceId: 21, status: 'APPROVED', participantCount: 6,
        startTime: '2099-08-02T18:00:00', endTime: '2099-08-02T22:00:00'
      }],
      total: 1, pageNum: 1, pageSize: 100
    })
  })

  it('creates an independent activity through the real payload, idempotency key and deep link', async () => {
    const wrapper = mountDialog()
    const view = vmOf(wrapper)
    await view.open('ACTIVITY')
    await flushPromises()
    view.draft.groupId = 11
    view.draft.title = '晚间自习'
    view.draft.timeRange = ['2099-08-02T19:00:00', '2099-08-02T21:00:00']
    view.draft.locationType = 'CAMPUS_AREA'
    view.draft.areaCode = 'north_campus'
    view.draft.capacityTotal = 6
    expect(view.canCreate).toBe(true)

    await view.createTarget()
    await flushPromises()

    expect(activityApiMock.createActivity).toHaveBeenCalledWith(expect.objectContaining({
      groupId: 11,
      activityType: 'STUDY',
      title: '晚间自习',
      startTime: '2099-08-02T19:00:00',
      endTime: '2099-08-02T21:00:00',
      // 区域码统一大写，与原 hub 行为一致
      location: { type: 'CAMPUS_AREA', areaCode: 'NORTH_CAMPUS' }
    }), expect.any(String))
    expect(routerMock.push).toHaveBeenCalledWith('/activities/42')
    expect(view.createOpen).toBe(false)
  })

  it('publishes a standalone companion request with its own window and location', async () => {
    const wrapper = mountDialog()
    const view = vmOf(wrapper)
    await view.open('COMPANION_REQUEST')
    await flushPromises()
    view.draft.groupId = 11
    view.draft.title = '自习找搭子'
    view.draft.timeRange = ['2099-08-02T19:00:00', '2099-08-02T21:00:00']
    view.draft.capacityTotal = 3
    view.draft.locationType = 'VENUE_SPACE'
    view.draft.venueId = 9
    view.draft.spaceId = 21

    await view.createTarget()
    await flushPromises()

    expect(activityApiMock.createCompanion).toHaveBeenCalledWith(expect.objectContaining({
      groupId: 11,
      windowStart: '2099-08-02T19:00:00',
      windowEnd: '2099-08-02T21:00:00',
      location: { type: 'VENUE_SPACE', venueId: 9, spaceId: 21, reservationId: undefined }
    }), expect.any(String))
    expect(routerMock.push).toHaveBeenCalledWith('/companion-requests/61')
  })

  it('keeps an activity-linked companion on the activity group, type and id', async () => {
    const wrapper = mountDialog()
    const view = vmOf(wrapper)
    await view.open('COMPANION_REQUEST', activity)
    await flushPromises()

    // 关联活动时小组与类型锁定为活动自身，时间与地点由活动上下文决定，不要求再填
    expect(view.draft.groupId).toBe(11)
    expect(view.draft.activityType).toBe('STUDY')
    expect(view.draft.timeRange).toEqual(['2099-08-01T09:00:00', '2099-08-01T11:00:00'])
    view.draft.title = '一起复习'
    expect(view.canCreate).toBe(true)
    await flushPromises()
    expect(wrapper.get('[data-test="create-submit"]').attributes('disabled')).toBeUndefined()

    await view.createTarget()
    await flushPromises()

    const payload = activityApiMock.createCompanion.mock.calls[0][0] as Record<string, unknown>
    expect(payload).toMatchObject({ groupId: 11, activityType: 'STUDY', activityId: 41, title: '一起复习' })
    expect(payload.windowStart).toBeUndefined()
    expect(payload.location).toBeUndefined()
    expect(routerMock.push).toHaveBeenCalledWith('/companion-requests/61')
  })

  it('only offers groups the viewer is an active member of and blocks an incomplete draft', async () => {
    const wrapper = mountDialog()
    const view = vmOf(wrapper)
    expect(view.canCreate).toBe(false)
    await view.open('ACTIVITY')
    await flushPromises()

    // 小组选项只来自 ACTIVE 成员关系（待审小组不入列），且未填完时提交按钮保持禁用
    const groupDropdown = wrapper.findAllComponents(SgDropdown)[0]
    expect(groupDropdown.props('options')).toEqual([{ value: 11, label: '学习组' }])
    expect(wrapper.get('[data-test="create-submit"]').attributes('disabled')).toBeDefined()

    // 场馆空间模式下缺空间不能提交
    view.draft.groupId = 11
    view.draft.title = '晚间自习'
    view.draft.timeRange = ['2099-08-02T19:00:00', '2099-08-02T21:00:00']
    view.draft.locationType = 'VENUE_SPACE'
    expect(view.canCreate).toBe(false)
    view.draft.venueId = 9
    view.draft.spaceId = 21
    expect(view.canCreate).toBe(true)
    await view.createTarget()
    await flushPromises()
    expect(activityApiMock.createActivity).toHaveBeenCalledTimes(1)
  })

  it('reuses one request key while the payload is unchanged and reports a retryable failure', async () => {
    activityApiMock.createActivity.mockRejectedValueOnce({ response: { data: { message: '小组状态已变化' } } })
    const wrapper = mountDialog()
    const view = vmOf(wrapper)
    await view.open('ACTIVITY')
    await flushPromises()
    view.draft.groupId = 11
    view.draft.title = '晚间自习'
    view.draft.timeRange = ['2099-08-02T19:00:00', '2099-08-02T21:00:00']
    view.draft.locationType = 'CAMPUS_AREA'
    view.draft.areaCode = 'NORTH_CAMPUS'

    await view.createTarget()
    await flushPromises()
    expect(view.formError).toBe('小组状态已变化')
    expect(wrapper.get('[data-test="create-error"]').text()).toContain('小组状态已变化')

    await view.createTarget()
    await flushPromises()
    const firstKey = activityApiMock.createActivity.mock.calls[0][1]
    const secondKey = activityApiMock.createActivity.mock.calls[1][1]
    expect(secondKey).toBe(firstKey)
  })

  it('notes a partial reference-load failure instead of hiding it', async () => {
    communityApiMock.groups.mockRejectedValue({ response: { status: 403 } })
    const wrapper = mountDialog()
    const view = vmOf(wrapper)
    await view.open('ACTIVITY')
    await flushPromises()

    expect(wrapper.get('.sg-dialog-note').text()).toContain('部分选项暂时无法加载')
  })
})
