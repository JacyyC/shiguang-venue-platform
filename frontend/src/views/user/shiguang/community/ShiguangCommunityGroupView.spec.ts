import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ShiguangCommunityGroupView from './ShiguangCommunityGroupView.vue'
import { useAuthStore } from '@/stores/auth'

const communityApiMock = vi.hoisted(() => ({
  group: vi.fn(),
  posts: vi.fn(),
  members: vi.fn(),
  joinGroup: vi.fn(),
  leaveGroup: vi.fn(),
  decideMembership: vi.fn(),
  groupModerate: vi.fn(),
  block: vi.fn(),
  deleteGroup: vi.fn(),
  changeMemberRole: vi.fn(),
  removeMember: vi.fn()
}))
const systemApiMock = vi.hoisted(() => ({ dict: vi.fn() }))
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const routeMock = vi.hoisted(() => ({
  query: {} as Record<string, string>,
  params: { id: '11' } as Record<string, string>,
  fullPath: '/community/groups/11'
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
  ReportDialog: { template: '<div />' },
  NativeDialog: { props: ['open', 'title'], template: '<div v-if="open"><h2>{{ title }}</h2><slot /></div>' }
}

const groupRow = (overrides: Record<string, unknown> = {}) => ({
  id: 11,
  name: '晨泳之后',
  description: '按自己的节奏游。',
  ownerUserId: 9,
  owner: { id: 9, nickname: '栩栩', avatar: null },
  visibility: 'MEMBERS_ONLY',
  joinPolicy: 'REVIEW_REQUIRED',
  categoryCode: 'SPORTS',
  status: 'ACTIVE',
  currentMemberRole: undefined,
  currentMemberStatus: undefined,
  memberCount: 12,
  versionNo: 0,
  createTime: '2026-09-02T08:00:00',
  ...overrides
})

const postRow = (overrides: Record<string, unknown> = {}) => ({
  id: 21,
  groupId: 11,
  groupName: '晨泳之后',
  groupVisibility: 'MEMBERS_ONLY',
  groupCategory: 'SPORTS',
  authorUserId: 9,
  author: { id: 9, nickname: '栩栩', avatar: null },
  title: '今天的水温刚刚好',
  content: '六点四十下水，七点半天亮。',
  status: 'PUBLISHED',
  versionNo: 0,
  publishedAt: '2026-09-10T07:30:00',
  ...overrides
})

const memberRow = (overrides: Record<string, unknown> = {}) => ({
  id: 31,
  groupId: 11,
  userId: 42,
  user: { id: 42, nickname: '简宁', avatar: null },
  role: 'MEMBER',
  status: 'PENDING',
  joinedAt: null,
  ...overrides
})

const emptyPage = { records: [], nextCursor: null, hasMore: false }

let testPinia: Pinia

const mountView = () =>
  mount(ShiguangCommunityGroupView, {
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
  routeMock.params = { id: '11' }
  systemApiMock.dict.mockResolvedValue([{ value: 'SPORTS', label: '运动户外' }])
  communityApiMock.posts.mockResolvedValue(emptyPage)
  communityApiMock.members.mockResolvedValue(emptyPage)
})

describe('ShiguangCommunityGroupView', () => {
  it('shows the members-only notice instead of posts for non-members', async () => {
    communityApiMock.group.mockResolvedValue(groupRow())

    const wrapper = mountView()
    await flushPromises()

    expect(communityApiMock.posts).toHaveBeenCalledWith(expect.objectContaining({ groupId: 11 }))
    expect(wrapper.find('[data-test="members-only-empty"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('这是一个仅成员可见的小组')
    expect(wrapper.find('[data-test="tab-members"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="tab-governance"]').exists()).toBe(false)
  })

  it('shows pending membership as a waiting state without a retry', async () => {
    communityApiMock.group.mockResolvedValue(groupRow({ currentMemberStatus: 'PENDING' }))

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('申请已送达，等一个回应')
    const button = wrapper.find('[data-test="join-button"]')
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('joins a review group through the real command and refreshes membership', async () => {
    communityApiMock.group
      .mockResolvedValueOnce(groupRow())
      .mockResolvedValueOnce(groupRow({ currentMemberStatus: 'PENDING', currentMemberRole: 'MEMBER' }))
    communityApiMock.joinGroup.mockResolvedValue(memberRow({ userId: 7, status: 'PENDING' }))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="join-button"]').trigger('click')
    await flushPromises()

    expect(communityApiMock.joinGroup).toHaveBeenCalledWith(11, expect.any(String))
    expect(communityApiMock.group).toHaveBeenCalledTimes(2)
  })

  it('lists posts for active members with visibility provenance', async () => {
    communityApiMock.group.mockResolvedValue(
      groupRow({ currentMemberStatus: 'ACTIVE', currentMemberRole: 'MEMBER' })
    )
    communityApiMock.posts.mockResolvedValue({ records: [postRow()], nextCursor: null, hasMore: false })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('今天的水温刚刚好')
    expect(wrapper.text()).toContain('仅成员可见')
    expect(wrapper.find('[data-test="tab-members"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="compose-in-group"]').exists()).toBe(true)
  })

  it('lets a manager review pending applications through real decisions', async () => {
    communityApiMock.group.mockResolvedValue(
      groupRow({ currentMemberStatus: 'ACTIVE', currentMemberRole: 'OWNER', ownerUserId: 7 })
    )
    communityApiMock.members.mockResolvedValue({ records: [memberRow()], nextCursor: null, hasMore: false })
    communityApiMock.posts.mockResolvedValue({ records: [postRow()], nextCursor: null, hasMore: false })
    communityApiMock.decideMembership.mockResolvedValue(memberRow({ status: 'ACTIVE' }))
    routeMock.query = { tab: 'governance' }

    const wrapper = mountView()
    await flushPromises()

    expect(communityApiMock.members).toHaveBeenCalledWith(11, expect.objectContaining({ status: 'PENDING' }))
    expect(communityApiMock.posts).toHaveBeenCalledWith(
      expect.objectContaining({ groupId: 11, status: 'ALL' })
    )
    expect(wrapper.text()).toContain('简宁')

    const approve = wrapper.findAll('[data-test="pending-row"] button').at(-1)
    await approve!.trigger('click')
    await flushPromises()

    expect(communityApiMock.decideMembership).toHaveBeenCalledWith(
      11, 31, { decision: 'APPROVE' }, expect.any(String)
    )
    expect(wrapper.text()).not.toContain('简宁')
  })

  it('fails closed when the group cannot be read', async () => {
    communityApiMock.group.mockRejectedValue({ response: { status: 404, data: {} } })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="group-not-found"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('这个小组已无法查看')
  })

  it('lets the owner delete the group through the real command and leaves the page', async () => {
    communityApiMock.group.mockResolvedValue(
      groupRow({ currentMemberStatus: 'ACTIVE', currentMemberRole: 'OWNER', ownerUserId: 7 })
    )
    communityApiMock.deleteGroup.mockResolvedValue(undefined)

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="group-menu"]').trigger('click')
    await wrapper.find('[data-test="delete-group"]').trigger('click')
    await wrapper.find('[data-test="confirm-delete-group"]').trigger('click')
    await flushPromises()

    expect(communityApiMock.deleteGroup).toHaveBeenCalledWith(11, expect.any(String))
    expect(routerMock.push).toHaveBeenCalledWith('/community?rail=groups')
  })

  it('lets the owner change roles and remove members through real commands', async () => {
    communityApiMock.group.mockResolvedValue(
      groupRow({ currentMemberStatus: 'ACTIVE', currentMemberRole: 'OWNER', ownerUserId: 7 })
    )
    communityApiMock.members.mockResolvedValue({
      records: [memberRow({ status: 'ACTIVE' })],
      nextCursor: null,
      hasMore: false
    })
    communityApiMock.changeMemberRole.mockResolvedValue(memberRow({ status: 'ACTIVE', role: 'ADMIN' }))
    communityApiMock.removeMember.mockResolvedValue(undefined)
    routeMock.query = { tab: 'members' }

    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('button[aria-label="简宁的成员操作"]').trigger('click')
    await wrapper.get('[data-test="member-role"]').trigger('click')
    await wrapper.get('[data-test="confirm-role"]').trigger('click')
    await flushPromises()

    expect(communityApiMock.changeMemberRole).toHaveBeenCalledWith(11, 31, 'ADMIN', expect.any(String))
    expect(wrapper.text()).not.toContain('设为管理员')

    await wrapper.get('button[aria-label="简宁的成员操作"]').trigger('click')
    await wrapper.get('[data-test="member-remove"]').trigger('click')
    await wrapper.get('[data-test="confirm-remove"]').trigger('click')
    await flushPromises()

    expect(communityApiMock.removeMember).toHaveBeenCalledWith(11, 31, expect.any(String))
    expect(wrapper.find('[data-test="member-row"]').exists()).toBe(false)
  })

  it('scopes role change to the owner even when an admin manages members', async () => {
    communityApiMock.group.mockResolvedValue(
      groupRow({ currentMemberStatus: 'ACTIVE', currentMemberRole: 'ADMIN', ownerUserId: 9 })
    )
    communityApiMock.members.mockResolvedValue({
      records: [
        memberRow({ status: 'ACTIVE' }),
        memberRow({ id: 32, userId: 43, role: 'ADMIN', user: { id: 43, nickname: '柏舟', avatar: null } })
      ],
      nextCursor: null,
      hasMore: false
    })
    routeMock.query = { tab: 'members' }

    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('button[aria-label="简宁的成员操作"]').trigger('click')
    expect(wrapper.find('[data-test="member-role"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="member-remove"]').exists()).toBe(true)
    await wrapper.get('[data-test="member-remove"]').trigger('click')
    await wrapper.get('[data-test="confirm-remove"]').trigger('click')
    await flushPromises()
    expect(communityApiMock.removeMember).toHaveBeenCalledWith(11, 31, expect.any(String))

    await wrapper.get('button[aria-label="柏舟的成员操作"]').trigger('click')
    expect(wrapper.find('[data-test="member-remove"]').exists()).toBe(false)
  })

  it('hides a comment by id through the governance form', async () => {
    communityApiMock.group.mockResolvedValue(
      groupRow({ currentMemberStatus: 'ACTIVE', currentMemberRole: 'OWNER', ownerUserId: 7 })
    )
    communityApiMock.groupModerate.mockResolvedValue(undefined)
    routeMock.query = { tab: 'governance' }

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="moderate-by-id"]').trigger('click')
    await wrapper.find('#by-id-target').setValue('77')
    await wrapper.find('[data-test="by-id-form"]').trigger('submit')
    await flushPromises()

    expect(communityApiMock.groupModerate).toHaveBeenCalledWith(
      11, 'COMMENT', 77, false,
      { reasonCode: 'OFF_TOPIC', internalNote: undefined },
      expect.any(String)
    )
  })
})
