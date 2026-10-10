import { beforeEach, describe, expect, it, vi } from 'vitest'
import { communityApi, communityError, contentAdminApi, createIdempotencyAttempt, createIdempotencyRegistry } from './community'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn()
}))

vi.mock('./http', () => ({ default: httpMock }))

describe('communityApi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('uses stable cursor params and forwards idempotency keys on community writes', async () => {
    httpMock.get.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })
    httpMock.post.mockResolvedValue({ id: 1 })
    httpMock.put.mockResolvedValue({ id: 2 })
    httpMock.delete.mockResolvedValue(undefined)

    await communityApi.groups({ namePrefix: '学习', category: 'STUDY', cursorId: 91, limit: 20 })
    await communityApi.groups({ mine: true, cursorId: 91, limit: 20 })
    await communityApi.createGroup({
      name: '学习组', visibility: 'PLATFORM_VISIBLE', joinPolicy: 'REVIEW_REQUIRED', category: 'STUDY'
    }, 'group-key')
    await communityApi.joinGroup(1, 'join-key')
    await communityApi.updatePost(2, { title: '新标题', content: '正文', versionNo: 3 }, 'post-key')
    await communityApi.deleteComment(5, 'comment-key')

    expect(httpMock.get).toHaveBeenNthCalledWith(1, '/community/groups', {
      params: { namePrefix: '学习', category: 'STUDY', cursorId: 91, limit: 20 }
    })
    expect(httpMock.get).toHaveBeenNthCalledWith(2, '/community/groups', {
      params: { mine: true, cursorId: 91, limit: 20 }
    })
    expect(httpMock.post).toHaveBeenCalledWith('/community/groups', expect.objectContaining({
      joinPolicy: 'REVIEW_REQUIRED',
      category: 'STUDY'
    }), { headers: { 'Idempotency-Key': 'group-key' } })
    expect(httpMock.post).toHaveBeenCalledWith('/community/groups/1/join', undefined, {
      headers: { 'Idempotency-Key': 'join-key' }
    })
    expect(httpMock.put).toHaveBeenCalledWith('/community/posts/2', expect.objectContaining({ versionNo: 3 }), {
      headers: { 'Idempotency-Key': 'post-key' }
    })
    expect(httpMock.delete).toHaveBeenCalledWith('/community/comments/5', {
      headers: { 'Idempotency-Key': 'comment-key' }
    })
  })

  it('queries the platform-visible echo digest with a bounded limit', async () => {
    httpMock.get.mockResolvedValue([
      { postId: 9, groupId: 2, groupName: '阅读共学小组', title: '一个人坐两小时，你会去哪里？', excerpt: '留白的窗边' }
    ])

    await communityApi.echo(5)

    expect(httpMock.get).toHaveBeenCalledWith('/community/echo', { params: { limit: 5 } })

    // AR-03：首页回声是可选附属内容，携带局部降级配置时 403 不触发全局跳转
    await communityApi.echo(1, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    expect(httpMock.get).toHaveBeenLastCalledWith('/community/echo', {
      params: { limit: 1 },
      skipGlobalErrorRedirect: true,
      skipGlobalErrorToast: true
    })
  })

  it('forwards the D-04 skipGlobalErrorRedirect switch on tolerant group metadata lookups', async () => {
    httpMock.get.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })
    await communityApi.group(3, { skipGlobalErrorRedirect: true })
    await communityApi.members(3, { limit: 50 }, { skipGlobalErrorRedirect: true })
    await communityApi.members(3, { status: 'ACTIVE' })

    expect(httpMock.get).toHaveBeenNthCalledWith(1, '/community/groups/3', { skipGlobalErrorRedirect: true })
    expect(httpMock.get).toHaveBeenNthCalledWith(2, '/community/groups/3/members', {
      params: { limit: 50 }, skipGlobalErrorRedirect: true
    })
    expect(httpMock.get).toHaveBeenNthCalledWith(3, '/community/groups/3/members', { params: { status: 'ACTIVE' } })
  })

  it('forwards membership-status and feed-scope filters on read contracts', async () => {
    httpMock.get.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })

    await communityApi.groups({ mine: true, memberStatus: 'ANY', limit: 20 })
    await communityApi.groups({ mine: true, memberStatus: 'PENDING', limit: 20 })
    await communityApi.posts({ titlePrefix: '共读', category: 'STUDY', visibilityScope: 'PLATFORM_VISIBLE', limit: 20 })
    await communityApi.posts({ groupId: 7, cursorId: 30, limit: 10 })

    expect(httpMock.get).toHaveBeenNthCalledWith(1, '/community/groups', {
      params: { mine: true, memberStatus: 'ANY', limit: 20 }
    })
    expect(httpMock.get).toHaveBeenNthCalledWith(2, '/community/groups', {
      params: { mine: true, memberStatus: 'PENDING', limit: 20 }
    })
    expect(httpMock.get).toHaveBeenNthCalledWith(3, '/community/posts', {
      params: { titlePrefix: '共读', category: 'STUDY', visibilityScope: 'PLATFORM_VISIBLE', limit: 20 }
    })
    expect(httpMock.get).toHaveBeenNthCalledWith(4, '/community/posts', {
      params: { groupId: 7, cursorId: 30, limit: 10 }
    })
  })

  it('keeps report review, content actions and audit pagination on the real admin contracts', async () => {
    httpMock.get.mockResolvedValue({ records: [], nextCursor: null, hasMore: false })
    httpMock.post.mockResolvedValue(undefined)

    await contentAdminApi.reports({ status: 'IN_REVIEW', cursorId: 40, limit: 20 })
    await contentAdminApi.decideReport(7, { decision: 'TAKE', decisionCode: 'REVIEW_STARTED' }, 'take-key')
    await contentAdminApi.moderate('POST', 9, false, { reasonCode: 'SPAM', reportId: 7 }, 'hide-key')
    await contentAdminApi.events({ reportId: 7, cursorId: 70, limit: 20 })

    expect(httpMock.get).toHaveBeenCalledWith('/content-admin/reports', {
      params: { status: 'IN_REVIEW', cursorId: 40, limit: 20 }
    })
    expect(httpMock.post).toHaveBeenCalledWith('/content-admin/reports/7/decision', {
      decision: 'TAKE', decisionCode: 'REVIEW_STARTED'
    }, { headers: { 'Idempotency-Key': 'take-key' } })
    expect(httpMock.post).toHaveBeenCalledWith('/content-admin/content/POST/9/hide', {
      reasonCode: 'SPAM', reportId: 7
    }, { headers: { 'Idempotency-Key': 'hide-key' } })
    expect(httpMock.get).toHaveBeenCalledWith('/content-admin/audit/events', {
      params: { reportId: 7, cursorId: 70, limit: 20 }
    })
  })

  it.each([
    [401, 'login', '登录已失效'],
    [403, 'forbidden', '无权'],
    [404, 'missing', '已隐藏'],
    [409, 'conflict', '已更新'],
    [429, 'rate-limit', '频繁']
  ] as const)('maps HTTP %s to a clear page state', (status, kind, text) => {
    const state = communityError({ response: { status, data: {} } })
    expect(state.kind).toBe(kind)
    expect(state.message).toContain(text)
  })

  it('reuses a create key after an uncertain failure and rotates it after payload change or success', () => {
    const attempt = createIdempotencyAttempt('post-create')
    const first = attempt.keyFor({ groupId: 1, title: '标题', content: '正文' })
    expect(attempt.keyFor({ groupId: 1, title: '标题', content: '正文' })).toBe(first)
    expect(attempt.keyFor({ groupId: 1, title: '新标题', content: '正文' })).not.toBe(first)

    const changed = attempt.keyFor({ groupId: 1, title: '新标题', content: '正文' })
    attempt.complete()
    expect(attempt.keyFor({ groupId: 1, title: '新标题', content: '正文' })).not.toBe(changed)
  })

  it('keeps independent retry keys for target-scoped update and moderation commands', () => {
    const registry = createIdempotencyRegistry()
    const updatePayload = { postId: 2, versionNo: 3, title: '标题' }
    const updateKey = registry.keyFor('post-update', updatePayload)
    const moderateKey = registry.keyFor('content-hide', { targetType: 'POST', targetId: 2, reasonCode: 'SPAM' })

    expect(registry.keyFor('post-update', updatePayload)).toBe(updateKey)
    expect(registry.keyFor('content-hide', { targetType: 'POST', targetId: 2, reasonCode: 'SPAM' })).toBe(moderateKey)
    expect(moderateKey).not.toBe(updateKey)
    registry.complete('post-update')
    expect(registry.keyFor('post-update', updatePayload)).not.toBe(updateKey)
  })
})
