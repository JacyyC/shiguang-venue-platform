import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ShiguangCommunityHomeView from './ShiguangCommunityHomeView.vue'
import { useAuthStore } from '@/stores/auth'

const communityApiMock = vi.hoisted(() => ({
  posts: vi.fn(),
  groups: vi.fn(),
  myReports: vi.fn(),
  blockedUsers: vi.fn(),
  post: vi.fn(),
  group: vi.fn(),
  unblock: vi.fn()
}))
const systemApiMock = vi.hoisted(() => ({ dict: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({
  query: {} as Record<string, string>,
  params: {} as Record<string, string>,
  fullPath: '/community'
}))

vi.mock('@/api/community', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/community')>()
  return { ...actual, communityApi: communityApiMock }
})
vi.mock('@/api/system', () => ({ systemApi: systemApiMock }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock }))
vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() }
}))
vi.mock('element-plus', () => ({ ElMessage: vi.fn() }))

const stubs = {
  'el-icon': { template: '<i />' },
  'router-link': { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>' },
  ShiguangArt: { props: ['scene'], template: '<span class="art" />' },
  ComposeDialog: { template: '<div />' },
  GroupFormDialog: { template: '<div />' },
  NativeDialog: { template: '<div />' }
}

const postRow = (overrides: Record<string, unknown> = {}) => ({
  id: 21,
  groupId: 11,
  groupName: '慢读俱乐部',
  groupVisibility: 'PLATFORM_VISIBLE',
  groupCategory: 'STUDY',
  authorUserId: 8,
  author: { id: 8, nickname: '南栀', avatar: null },
  title: '窗边的二十分钟',
  content: '今天把书读到一半，抬头看见雨。',
  status: 'PUBLISHED',
  versionNo: 0,
  publishedAt: '2026-09-10T19:04:00',
  ...overrides
})

const groupRow = (overrides: Record<string, unknown> = {}) => ({
  id: 11,
  name: '慢读俱乐部',
  description: '不赶进度，不设书单。',
  ownerUserId: 9,
  owner: { id: 9, nickname: '南栀', avatar: null },
  visibility: 'PLATFORM_VISIBLE',
  joinPolicy: 'DIRECT',
  categoryCode: 'STUDY',
  status: 'ACTIVE',
  currentMemberRole: 'MEMBER',
  currentMemberStatus: 'ACTIVE',
  memberCount: 24,
  versionNo: 0,
  ...overrides
})

const emptyPage = { records: [], nextCursor: null, hasMore: false }

let testPinia: Pinia

const mountView = () =>
  mount(ShiguangCommunityHomeView, {
    global: { plugins: [testPinia], stubs }
  })

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    this.open = true
  })
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.open = false
  })
})

beforeEach(() => {
  vi.clearAllMocks()
  testPinia = createPinia()
  setActivePinia(testPinia)
  const auth = useAuthStore()
  auth.user = { id: 7, nickname: '你' } as never
  routeMock.query = {}
  systemApiMock.dict.mockResolvedValue([{ value: 'STUDY', label: '学习共读' }])
  communityApiMock.posts.mockResolvedValue(emptyPage)
  communityApiMock.groups.mockResolvedValue(emptyPage)
  communityApiMock.myReports.mockResolvedValue(emptyPage)
  communityApiMock.blockedUsers.mockResolvedValue(emptyPage)
})

describe('ShiguangCommunityHomeView', () => {
  it('loads the platform-visible feed on the default rail', async () => {
    communityApiMock.posts.mockResolvedValue({ records: [postRow()], nextCursor: null, hasMore: false })

    const wrapper = mountView()
    await flushPromises()

    expect(communityApiMock.posts).toHaveBeenCalledWith(
      expect.objectContaining({ visibilityScope: 'PLATFORM_VISIBLE', limit: 20 })
    )
    expect(wrapper.text()).toContain('窗边的二十分钟')
    expect(wrapper.text()).toContain('慢读俱乐部')
    expect(wrapper.text()).toContain('广场可见')
    expect(wrapper.text()).toContain('南栀')
  })

  it('scopes the groups rail by category and the mine rail by ANY membership status', async () => {
    routeMock.query = { rail: 'groups', category: 'STUDY', q: '慢读' }
    communityApiMock.groups.mockResolvedValue({ records: [groupRow()], nextCursor: null, hasMore: false })

    const wrapper = mountView()
    await flushPromises()

    expect(communityApiMock.groups).toHaveBeenCalledWith(
      expect.objectContaining({ category: 'STUDY', namePrefix: '慢读', limit: 20 })
    )
    expect(wrapper.text()).toContain('慢读俱乐部')
    expect(wrapper.text()).toContain('已加入')
  })

  it('queries my groups including pending memberships on the mine rail', async () => {
    routeMock.query = { rail: 'mine' }
    communityApiMock.groups.mockResolvedValue({
      records: [groupRow({ currentMemberStatus: 'PENDING', joinPolicy: 'REVIEW_REQUIRED' })],
      nextCursor: null,
      hasMore: false
    })

    const wrapper = mountView()
    await flushPromises()

    expect(communityApiMock.groups).toHaveBeenCalledWith(
      expect.objectContaining({ mine: true, memberStatus: 'ANY' })
    )
    expect(wrapper.text()).toContain('等待审核')
  })

  it('scopes my-posts to the current author', async () => {
    routeMock.query = { rail: 'my-posts' }
    communityApiMock.posts.mockResolvedValue({ records: [postRow()], nextCursor: null, hasMore: false })

    mountView()
    await flushPromises()

    expect(communityApiMock.posts).toHaveBeenCalledWith(
      expect.objectContaining({ authorId: 7 })
    )
    expect(communityApiMock.posts.mock.calls[0][0].visibilityScope).toBeUndefined()
  })

  it('resolves report target titles and degrades missing ones without fabricating content', async () => {
    routeMock.query = { rail: 'reports' }
    communityApiMock.myReports.mockResolvedValue({
      records: [
        { id: 1, targetType: 'POST', targetId: 21, reason: '广告内容', status: 'OPEN', createTime: '2026-09-10T10:00:00' },
        { id: 2, targetType: 'COMMENT', targetId: 31, reason: '不友善', status: 'RESOLVED', closedAt: '2026-09-11T10:00:00', createTime: '2026-09-10T11:00:00' },
        { id: 3, targetType: 'POST', targetId: 99, reason: '已消失的目标', status: 'IN_REVIEW', createTime: '2026-09-10T12:00:00' }
      ],
      nextCursor: null,
      hasMore: false
    })
    communityApiMock.post
      .mockResolvedValueOnce(postRow())
      .mockRejectedValueOnce({ response: { status: 404, data: {} } })

    const wrapper = mountView()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('窗边的二十分钟')
    expect(wrapper.text()).toContain('一条评论')
    expect(wrapper.text()).toContain('内容已不可见')
    expect(wrapper.text()).toContain('处理中')
    expect(wrapper.text()).toContain('已处理')
  })

  it('unblocks a user through the real command and removes the row', async () => {
    routeMock.query = { rail: 'blocks' }
    communityApiMock.blockedUsers.mockResolvedValue({
      records: [{ id: 5, user: { id: 66, nickname: '乔木', avatar: null }, createdAt: '2026-09-01T08:00:00' }],
      nextCursor: null,
      hasMore: false
    })
    communityApiMock.unblock.mockResolvedValue(undefined)

    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('乔木')

    await wrapper.find('[data-test="block-row"] button').trigger('click')
    await flushPromises()

    expect(communityApiMock.unblock).toHaveBeenCalledWith(66, expect.any(String))
    expect(wrapper.text()).toContain('屏蔽名单是空的')
  })

  it('shows the forbidden state instead of content when access is denied', async () => {
    communityApiMock.posts.mockRejectedValue({ response: { status: 403, data: {} } })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="state-forbidden"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂时无法查看社区')
  })

  it('offers filter clearing when a search yields nothing', async () => {
    routeMock.query = { q: '不存在的标题' }
    communityApiMock.posts.mockResolvedValue(emptyPage)

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('没有找到这篇分享')
    await wrapper.find('[data-test="state-empty"] button').trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith(
      expect.objectContaining({ path: '/community', query: {} })
    )
  })
})
