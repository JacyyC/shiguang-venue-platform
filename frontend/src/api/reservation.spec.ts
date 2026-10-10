import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn()
}))

vi.mock('./http', () => ({ default: httpMock }))

import { reservationApi } from './reservation'

describe('reservationApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.post.mockResolvedValue({ id: 99 })
  })

  it('sends the browser funnel session with reservation submission', async () => {
    const payload = {
      spaceId: 10,
      startTime: '2026-08-20T10:00:00',
      endTime: '2026-08-20T11:00:00',
      contactName: '王小明',
      contactPhone: '13800000000'
    }

    await reservationApi.create(payload, 's03-browser-session')

    expect(httpMock.post).toHaveBeenCalledWith('/reservations', payload, {
      headers: { 'X-Funnel-Session': 's03-browser-session' }
    })
  })

  it('sends venue ids as a comma-joined bounded availability-summary query', async () => {
    httpMock.get.mockResolvedValue([{ venueId: 3, available: true, availableSlotCount: 4 }])

    await reservationApi.availabilitySummary({ date: '2026-09-09', venueIds: [3, 7, 9] })

    expect(httpMock.get).toHaveBeenCalledWith('/reservations/availability-summary', {
      params: { date: '2026-09-09', venueIds: '3,7,9' }
    })
  })

  it('quotes server-side pricing from selection facts only, without contact fields', async () => {
    httpMock.post.mockResolvedValue({ payableAmount: 120 })

    const payload = {
      spaceId: 10,
      startTime: '2026-08-20T10:00:00',
      endTime: '2026-08-20T12:00:00',
      slots: [{ spaceId: 10, startTime: '2026-08-20T10:00:00', endTime: '2026-08-20T11:00:00' }],
      participantCount: 2
    }

    await reservationApi.quote(payload as never)

    expect(httpMock.post).toHaveBeenCalledWith('/reservations/quote', payload)
    const sent = httpMock.post.mock.calls[0][1] as Record<string, unknown>
    expect(sent.contactName).toBeUndefined()
    expect(sent.contactPhone).toBeUndefined()
  })

  it('reads a single reservation by id for the deep-linked result page', async () => {
    httpMock.get.mockResolvedValue({ id: 42, status: 'PENDING_REVIEW' })

    await expect(reservationApi.detail(42)).resolves.toMatchObject({ id: 42 })

    expect(httpMock.get).toHaveBeenCalledWith('/reservations/42')
  })

  it('carries the submit intent key in the Idempotency-Key header next to the funnel session', async () => {
    const payload = {
      spaceId: 10,
      startTime: '2026-08-20T10:00:00',
      endTime: '2026-08-20T11:00:00',
      contactName: '王小明',
      contactPhone: '13800000000'
    }

    await reservationApi.create(payload, 's03-browser-session', 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')

    expect(httpMock.post).toHaveBeenCalledWith('/reservations', payload, {
      headers: {
        'X-Funnel-Session': 's03-browser-session',
        'Idempotency-Key': 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
      }
    })
  })

  it('looks up a submission result by key without creating anything', async () => {
    httpMock.get.mockResolvedValue({ status: 'COMPLETED', reservationId: 77 })

    await expect(reservationApi.submissionResult('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'))
      .resolves.toMatchObject({ status: 'COMPLETED', reservationId: 77 })

    expect(httpMock.get).toHaveBeenCalledWith('/reservations/submission-result', {
      params: { idempotencyKey: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' }
    })
    expect(httpMock.post).not.toHaveBeenCalled()
  })
})
