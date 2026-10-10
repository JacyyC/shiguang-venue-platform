import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ShiguangCommunityPostView from './ShiguangCommunityPostView.vue'
import { useAuthStore } from '@/stores/auth'

const communityApiMock = vi.hoisted(() => ({
  post: vi.fn(),
  group: vi.fn(),
  posts: vi.fn(),
  comments: vi.fn(),
  createComment: vi.fn(),
  joinGroup: vi.fn(),
  deletePost: vi.fn(),
  block: vi.fn()
}))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({
  query: {} as Record<string, string>,
  params: { id: '21' } as Record<string, string>,
  fullPath: '/community/posts/21'
}))

vi.mock('@/api/community', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/community')>()
  return { ...actual, communityApi: communityApiMock }
})
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
  ReportDialog: { template: '<div />' },
  NativeDialog: { props: ['open', 'title'], template: '<div v-if="open"><h2>{{ title }}</h2><slot /></div>' }
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
  content: '今天把书读到一半，抬头看见雨。\n\n雨停之前，哪也不去。',
  status: 'PUBLISHED',
  versionNo: 0,
  publishedAt: '2026-09-10T19:04:00',
  ...overrides
})

const groupRow = (overrides: Record<string, unknown> = {}) => ({
  id: 11,
  name: '慢读俱乐部',
  description: '不赶进度，不设书单。',
  ownerUserId: 8,
  owner: { id: 8, nickname: '南栀', avatar: null },
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

const commentRow = (overrides: Record<string, unknown> = {}) => ({
  id: 31,
  postId: 21,
  groupId: 11,
  authorUserId: 9,
  author: { id: 9, nickname: '乔木', avatar: null },
  content: '这一段写得好像我家楼下。',
  status: 'PUBLISHED',
  versionNo: 0,
  publishedAt: '2026-09-10T20:00:00',
  ...overrides
})

let testPinia: Pinia

const mountView = () =>
  mount(ShiguangCommunityPostView, {
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
  routeMock.params = { id: '21' }
  communityApiMock.post.mockResolvedValue(postRow())
  communityApiMock.group.mockResolvedValue(groupRow())
  communityApiMock.posts.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })
  communityApiMock.comments.mockResolvedValue({ records: [commentRow()], nextCursor: null, hasMore: false })
})

describe('ShiguangCommunityPostView', () => {
  it('renders the aggregated detail with author, provenance and paragraphs', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(communityApiMock.post).toHaveBeenCalledWith(21)
    expect(wrapper.text()).toContain('窗边的二十分钟')
    expect(wrapper.text()).toContain('慢读俱乐部')
    expect(wrapper.text()).toContain('广场可见')
    expect(wrapper.text()).toContain('南栀')
    expect(wrapper.text()).toContain('今天把书读到一半，抬头看见雨。')
    expect(wrapper.text()).toContain('雨停之前，哪也不去。')
    expect(wrapper.text()).toContain('这一段写得好像我家楼下。')
  })

  it('creates a comment as an active member and prepends the server result', async () => {
    communityApiMock.createComment.mockResolvedValue(
      commentRow({ id: 32, authorUserId: 7, author: { id: 7, nickname: '你', avatar: null }, content: '也想去看一场雨。' })
    )

    const wrapper = mountView()
    await flushPromises()

    const textarea = wrapper.find('#comment-content')
    await textarea.setValue('也想去看一场雨。')
    await wrapper.find('[data-test="comment-submit"]').trigger('submit')
    await wrapper.find('form.comment-compose').trigger('submit.prevent')
    await flushPromises()

    expect(communityApiMock.createComment).toHaveBeenCalledWith(21, '也想去看一场雨。', expect.any(String))
    const first = wrapper.findAll('[data-test="comment-row"]')[0]
    expect(first.text()).toContain('也想去看一场雨。')
  })

  it('replaces the composer with a join entry for non-members', async () => {
    communityApiMock.group.mockResolvedValue(
      groupRow({ currentMemberStatus: undefined, currentMemberRole: undefined })
    )

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="comment-submit"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="join-button"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('加入小组后，就能参与这段对话。')

    communityApiMock.joinGroup.mockResolvedValue({ id: 44, status: 'ACTIVE' })
    communityApiMock.group.mockResolvedValue(groupRow())
    await wrapper.find('[data-test="join-button"]').trigger('click')
    await flushPromises()
    expect(communityApiMock.joinGroup).toHaveBeenCalledWith(11, expect.any(String))
  })

  it('fails closed when the post is not visible', async () => {
    communityApiMock.post.mockRejectedValue({ response: { status: 404, data: {} } })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="post-not-found"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('这篇分享暂时无法查看')
  })

  it('offers author actions to the author and governance actions to others', async () => {
    communityApiMock.post.mockResolvedValue(postRow({ authorUserId: 7, author: { id: 7, nickname: '你', avatar: null } }))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="post-menu"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('编辑帖子')
    expect(wrapper.text()).toContain('删除帖子')

    communityApiMock.post.mockResolvedValue(postRow())
    const other = mountView()
    await flushPromises()
    await other.find('[data-test="post-menu"]').trigger('click')
    await flushPromises()
    expect(other.text()).toContain('举报帖子')
    expect(other.text()).toContain('屏蔽 南栀')
  })

  it('restores the from context on the back row', async () => {
    routeMock.query = { from: 'my-posts' }

    const wrapper = mountView()
    await flushPromises()

    const back = wrapper.find('.back-row a')
    expect(back.text()).toContain('返回社区')
    expect(back.attributes('href')).toBe('/community')
  })
})
