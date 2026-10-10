import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AvailabilitySlot } from '@/api/types'
import { clearBookingDraft, useReservationStore } from './reservation'

const DRAFT_KEY = 'shiguang-booking-draft'
const SUBMIT_INTENT_KEY = 'shiguang-submit-intent'

const slot = (startTime: string, endTime: string, spaceId = 10): AvailabilitySlot => ({
  spaceId,
  startTime,
  endTime,
  status: 'AVAILABLE',
  label: '',
  price: 60
})

const readDraft = () => {
  const raw = sessionStorage.getItem(DRAFT_KEY)
  return raw ? JSON.parse(raw) as Record<string, unknown> : null
}

describe('reservation store booking draft', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    vi.clearAllMocks()
  })

  it('clears the live intent and selection on identity change', () => {
    const store = useReservationStore()
    store.setSelectedSlots([slot('2030-09-10T17:00:00', '2030-09-10T18:00:00')])
    store.ensureSubmitIntentKey()
    clearBookingDraft()
    expect(store.submitIntentKey).toBeUndefined()
    expect(store.selectedSlots).toEqual([])
    expect(sessionStorage.getItem(SUBMIT_INTENT_KEY)).toBeNull()
  })

  it('persists the sorted selection together with the venue and space context', () => {
    const store = useReservationStore()
    store.setSelectionContext({ venueId: 3, venueName: '西岸 · 日落网球场', spaceName: '临河 1 号场', spaceType: 'TENNIS' })
    store.setSelectedSlots([
      slot('2026-09-10T18:00:00', '2026-09-10T19:00:00'),
      slot('2026-09-10T17:00:00', '2026-09-10T18:00:00')
    ])

    expect(store.selectedSlots[0].startTime).toBe('2026-09-10T17:00:00')
    const draft = readDraft()
    expect(draft?.venueId).toBe(3)
    expect(draft?.spaceName).toBe('临河 1 号场')
    expect((draft?.slots as AvailabilitySlot[]).map((item) => item.startTime))
      .toEqual(['2026-09-10T17:00:00', '2026-09-10T18:00:00'])
  })

  it('restores the selection after a reload without pretending the server hold is still running', () => {
    const first = useReservationStore()
    first.setLockSecondsFromRules(900)
    first.setSelectionContext({ venueId: 3 })
    first.setSelectedSlots([slot('2026-09-10T17:00:00', '2026-09-10T18:00:00')])
    expect(first.lockSeconds).toBe(900)

    setActivePinia(createPinia())
    const restored = useReservationStore()
    restored.restoreDraft()

    expect(restored.selectedSlots).toHaveLength(1)
    expect(restored.lastVenueId).toBe(3)
    // 服务端保留窗口不随前端刷新续期：恢复后按「提交时再确认可用性」呈现
    expect(restored.lockSeconds).toBe(0)
  })

  it('keeps the resource selection so capacity and seat choices survive a reload', () => {
    const store = useReservationStore()
    store.setSelectionContext({ venueId: 3 })
    store.setSelectedSlots([slot('2026-09-10T17:00:00', '2026-09-10T18:00:00')])
    store.setResourceSelection({ participantCount: 3, seatIds: [7, 9] })

    setActivePinia(createPinia())
    const restored = useReservationStore()
    restored.restoreDraft()

    expect(restored.participantCount).toBe(3)
    expect(restored.seatIds).toEqual([7, 9])
  })

  it('drops the stored draft once the selection is cleared', () => {
    const store = useReservationStore()
    store.setSelectionContext({ venueId: 3 })
    store.setSelectedSlots([slot('2026-09-10T17:00:00', '2026-09-10T18:00:00')])
    store.clearSlot()

    expect(sessionStorage.getItem(DRAFT_KEY)).toBeNull()
    expect(store.participantCount).toBe(1)
    expect(store.seatIds).toEqual([])
  })

  it('ignores a malformed draft instead of crashing the booking chain', () => {
    sessionStorage.setItem(DRAFT_KEY, '{not json')
    setActivePinia(createPinia())
    const store = useReservationStore()
    store.restoreDraft()
    expect(store.selectedSlots).toEqual([])

    sessionStorage.setItem(DRAFT_KEY, JSON.stringify({ slots: [{ nope: true }] }))
    setActivePinia(createPinia())
    const second = useReservationStore()
    second.restoreDraft()
    expect(second.selectedSlots).toEqual([])
  })

  it('does not overwrite an in-memory selection with a stale draft', () => {
    sessionStorage.setItem(DRAFT_KEY, JSON.stringify({
      slots: [slot('2026-09-11T09:00:00', '2026-09-11T10:00:00')],
      participantCount: 1,
      seatIds: []
    }))
    const store = useReservationStore()
    store.setSelectionContext({ venueId: 8 })
    store.setSelectedSlots([slot('2026-09-10T17:00:00', '2026-09-10T18:00:00')])

    store.restoreDraft()

    expect(store.selectedSlots[0].startTime).toBe('2026-09-10T17:00:00')
    expect(store.lastVenueId).toBe(8)
  })

  it('keeps one request key for a submit intent across a reload and only drops it on demand', () => {
    const store = useReservationStore()
    store.setSelectionContext({ venueId: 3 })
    store.setSelectedSlots([slot('2026-09-10T17:00:00', '2026-09-10T18:00:00')])

    const key = store.ensureSubmitIntentKey()
    expect(key).toBeTruthy()
    // 重试必须复用同一个键，否则服务端无法还原原结果
    expect(store.ensureSubmitIntentKey()).toBe(key)
    expect(sessionStorage.getItem(SUBMIT_INTENT_KEY)).toBe(key)

    setActivePinia(createPinia())
    const restored = useReservationStore()
    restored.restoreDraft()
    restored.restoreSubmitIntent()
    expect(restored.submitIntentKey).toBe(key)
    // 刷新后仍复用同一个键，而不是换一个新的
    expect(restored.ensureSubmitIntentKey()).toBe(key)

    restored.clearSubmitIntent()
    expect(restored.submitIntentKey).toBeUndefined()
    expect(sessionStorage.getItem(SUBMIT_INTENT_KEY)).toBeNull()
  })

  it('keeps the request key when only the looked-up selection is cleared', () => {
    const store = useReservationStore()
    store.setSelectionContext({ venueId: 3 })
    store.setSelectedSlots([slot('2026-09-10T17:00:00', '2026-09-10T18:00:00')])
    const key = store.ensureSubmitIntentKey()

    // 返回场馆页后发现原时段不可用会清空选择，但未完成的提交意图必须保留，否则核对入口会丢
    store.clearSlot()
    expect(store.submitIntentKey).toBe(key)
    expect(sessionStorage.getItem(SUBMIT_INTENT_KEY)).toBe(key)
  })

  it('drops the submit intent together with the booking draft on an identity switch', () => {
    const store = useReservationStore()
    store.setSelectionContext({ venueId: 3 })
    store.setSelectedSlots([slot('2026-09-10T17:00:00', '2026-09-10T18:00:00')])
    store.ensureSubmitIntentKey()

    clearBookingDraft()
    expect(sessionStorage.getItem(DRAFT_KEY)).toBeNull()
    expect(sessionStorage.getItem(SUBMIT_INTENT_KEY)).toBeNull()
  })

  it('keeps working when session storage is unavailable', () => {
    const original = window.sessionStorage
    Object.defineProperty(window, 'sessionStorage', {
      configurable: true,
      value: { getItem: vi.fn(() => { throw new Error('blocked') }), setItem: vi.fn(() => { throw new Error('blocked') }), removeItem: vi.fn(() => { throw new Error('blocked') }) }
    })

    const store = useReservationStore()
    store.setSelectionContext({ venueId: 3 })
    store.setSelectedSlots([slot('2026-09-10T17:00:00', '2026-09-10T18:00:00')])
    store.restoreDraft()
    expect(store.selectedSlots).toHaveLength(1)
    clearBookingDraft()

    expect(store.selectedSlots).toHaveLength(0)
    Object.defineProperty(window, 'sessionStorage', { configurable: true, value: original })
  })
})
