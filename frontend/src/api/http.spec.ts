import { beforeEach, describe, expect, it, vi } from 'vitest'

const routerMock = vi.hoisted(() => ({
  replace: vi.fn(),
  currentRoute: { value: { meta: {}, path: '/activities/41', fullPath: '/activities/41' } }
}))
const authStoreMock = vi.hoisted(() => ({ logoutLocal: vi.fn(), token: 'token-1' }))
const messageMock = vi.hoisted(() => ({ warning: vi.fn(), error: vi.fn(), success: vi.fn() }))

const handlers = vi.hoisted(() => ({
  request: [] as unknown[][],
  response: [] as unknown[][]
}))

vi.mock('axios', () => {
  const instance = {
    interceptors: {
      request: { use: (...args: unknown[]) => { handlers.request.push(args) } },
      response: { use: (...args: unknown[]) => { handlers.response.push(args) } }
    }
  }
  return { default: { create: vi.fn(() => instance) } }
})
vi.mock('@/router', () => ({ default: routerMock }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authStoreMock }))
vi.mock('element-plus', () => ({ ElMessage: messageMock }))

import './http'

const onRejected = () => {
  const entry = handlers.response[handlers.response.length - 1] as [unknown, (error: unknown) => Promise<unknown>]
  return entry[1]
}

const httpError = (status: number, config: Record<string, unknown> = {}) => ({
  response: { status, data: { code: status === 403 ? 40300 : status, message: '被拒绝' } },
  config
})

describe('http response interceptor 403 layering (D-04)', () => {
  beforeEach(() => {
    routerMock.replace.mockClear()
    authStoreMock.logoutLocal.mockClear()
    messageMock.warning.mockClear()
  })

  it('keeps the default contract: a 403 still redirects to /403 and rejects to the caller', async () => {
    await expect(onRejected()(httpError(403))).rejects.toMatchObject({ response: { status: 403 } })
    expect(routerMock.replace).toHaveBeenCalledTimes(1)
    expect(routerMock.replace).toHaveBeenCalledWith('/403')
  })

  it('skips the global /403 redirect when the request opts in, and still rejects to the caller', async () => {
    await expect(
      onRejected()(httpError(403, { skipGlobalErrorRedirect: true }))
    ).rejects.toMatchObject({ response: { status: 403 } })
    expect(routerMock.replace).not.toHaveBeenCalled()
  })

  it('keeps the 401 session teardown contract unchanged', async () => {
    await expect(onRejected()(httpError(401))).rejects.toMatchObject({ response: { status: 401 } })
    expect(authStoreMock.logoutLocal).toHaveBeenCalledTimes(1)
    expect(routerMock.replace).toHaveBeenCalledWith({ path: '/login', query: { redirect: '/activities/41' } })
  })

  it('still tears the session down on a 401 even when the caller asked for a local degrade (AR-03)', async () => {
    await expect(
      onRejected()(httpError(401, { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }))
    ).rejects.toMatchObject({ response: { status: 401 } })
    expect(authStoreMock.logoutLocal).toHaveBeenCalledTimes(1)
    expect(routerMock.replace).toHaveBeenCalledWith({ path: '/login', query: { redirect: '/activities/41' } })
  })
})
