import { defineStore, getActivePinia } from 'pinia'
import type { AvailabilitySlot } from '@/api/types'

let lockTimer: number | undefined

const DRAFT_KEY = 'shiguang-booking-draft'
/**
 * 提交意图的请求身份单独存放：它必须在选择被清空后仍然保留，
 * 否则「服务端已完成但响应丢失」的核对入口会随选择一起消失。
 * 只存随机标识，不含联系人等个人信息。
 */
const SUBMIT_INTENT_KEY = 'shiguang-submit-intent'

type BookingDraft = {
  slots: AvailabilitySlot[]
  venueId?: number
  venueName?: string
  spaceId?: number
  spaceName?: string
  spaceType?: string
  participantCount: number
  seatIds: number[]
}

const isDraft = (value: unknown): value is BookingDraft => {
  const draft = value as BookingDraft
  return Boolean(draft) && Array.isArray(draft.slots) && draft.slots.every((slot) => slot && slot.spaceId && slot.startTime && slot.endTime)
}

/**
 * 生成请求键：UUID 形态（十六进制与短横线）与服务端的键字符集一致。
 * 不使用联系人等个人信息参与生成，键本身也不落任何业务字段。
 */
const createSubmitIntentKey = () => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `intent-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}-${Math.random().toString(36).slice(2, 10)}`
}

const readDraft = (): BookingDraft | null => {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.sessionStorage.getItem(DRAFT_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    return isDraft(parsed) ? parsed : null
  } catch {
    return null
  }
}

const writeDraft = (draft: BookingDraft) => {
  if (typeof window === 'undefined') return
  try {
    if (!draft.slots.length) window.sessionStorage.removeItem(DRAFT_KEY)
    else window.sessionStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
  } catch {
    // 隐私模式或配额不足时选择上下文仅存于内存，不阻断预约链路。
  }
}

const readSubmitIntentKey = (): string | undefined => {
  if (typeof window === 'undefined') return undefined
  try {
    return window.sessionStorage.getItem(SUBMIT_INTENT_KEY) || undefined
  } catch {
    return undefined
  }
}

const writeSubmitIntentKey = (key: string | undefined) => {
  if (typeof window === 'undefined') return
  try {
    if (key) window.sessionStorage.setItem(SUBMIT_INTENT_KEY, key)
    else window.sessionStorage.removeItem(SUBMIT_INTENT_KEY)
  } catch {
    // 存储不可用时意图仅存于内存，不影响本次会话内的提交与核对。
  }
}

/** 登出等身份切换路径调用：有活跃 Pinia 时同步清理内存；未初始化时仍可清理存储。 */
export const clearBookingDraft = () => {
  const pinia = getActivePinia()
  if (pinia) {
    const store = useReservationStore(pinia)
    store.stopLockCountdown()
    store.$reset()
  }
  if (typeof window === 'undefined') return
  try {
    window.sessionStorage.removeItem(DRAFT_KEY)
    // 身份切换后不能继续沿用上一位用户的提交意图。
    window.sessionStorage.removeItem(SUBMIT_INTENT_KEY)
  } catch {
    // 存储不可用时内存态由调用方自行清理。
  }
}

export const useReservationStore = defineStore('reservation', {
  state: () => ({
    selectedSlots: [] as AvailabilitySlot[],
    lockSeconds: 0,
    lockSecondsFromRules: 0,
    /** 最近一次进入预约链路的场馆 ID：确认页摘要卡业务名解析用（展示层联查，零后端变更）。 */
    lastVenueId: undefined as number | undefined,
    venueName: undefined as string | undefined,
    spaceId: undefined as number | undefined,
    spaceName: undefined as string | undefined,
    spaceType: undefined as string | undefined,
    participantCount: 1,
    seatIds: [] as number[],
    /** 一次提交意图的请求身份：结果未明确前保持不变，成功后才清除；NONE不能证明原事务不会提交。 */
    submitIntentKey: undefined as string | undefined,
    /** 提交前最后一次服务端报价（仅内存）：结果页据此解释实付差异与活动优惠回退，不落 sessionStorage。 */
    previewPricing: null as { payableAmount?: number; activityDiscountAmount?: number } | null
  }),
  getters: {
    selectedSlot: (state) => state.selectedSlots[0] || null
  },
  actions: {
    setSelectedSlots(slots: AvailabilitySlot[]) {
      this.selectedSlots = [...slots].sort((left, right) => left.startTime.localeCompare(right.startTime))
      this.spaceId = this.selectedSlots[0]?.spaceId
      this.lockSeconds = this.selectedSlots.length ? (this.lockSecondsFromRules || 900) : 0
      this.startLockCountdown()
      this.persistDraft()
    },
    setLockSecondsFromRules(seconds: number) {
      this.lockSecondsFromRules = seconds || 900
    },
    setSelectionContext(context: { venueId?: number; venueName?: string; spaceName?: string; spaceType?: string }) {
      if (context.venueId !== undefined) {
        this.lastVenueId = context.venueId
      }
      if (context.venueName !== undefined) this.venueName = context.venueName
      if (context.spaceName !== undefined) this.spaceName = context.spaceName
      if (context.spaceType !== undefined) this.spaceType = context.spaceType
      this.persistDraft()
    },
    setResourceSelection(selection: { participantCount?: number; seatIds?: number[] }) {
      if (selection.participantCount !== undefined) {
        this.participantCount = Math.max(1, selection.participantCount)
      }
      if (selection.seatIds !== undefined) {
        this.seatIds = [...selection.seatIds]
      }
      this.persistDraft()
    },
    setPreviewPricing(snapshot: { payableAmount?: number; activityDiscountAmount?: number } | null) {
      this.previewPricing = snapshot
    },
    /**
     * 取得当前提交意图的请求键：已有则复用，没有才新建。
     * 复用是防止「服务端已完成但响应丢失」时重复创建的关键，不能在重试时换键。
     */
    ensureSubmitIntentKey() {
      if (!this.submitIntentKey) {
        this.submitIntentKey = readSubmitIntentKey() || createSubmitIntentKey()
        writeSubmitIntentKey(this.submitIntentKey)
      }
      return this.submitIntentKey
    },
    /** 仅在已取得成功结果后清除意图；身份切换另由clearBookingDraft清理。 */
    clearSubmitIntent() {
      this.submitIntentKey = undefined
      writeSubmitIntentKey(undefined)
    },
    clearSlot() {
      this.selectedSlots = []
      this.lockSeconds = 0
      this.spaceId = undefined
      this.spaceName = undefined
      this.participantCount = 1
      this.seatIds = []
      this.stopLockCountdown()
      this.persistDraft()
    },
    /** 同标签页刷新或前进后退后恢复选择上下文；服务端保留窗口不随之续期，因此恢复后按「提交时再确认可用性」呈现。 */
    restoreDraft() {
      if (this.selectedSlots.length) return
      const draft = readDraft()
      if (!draft || !draft.slots.length) return
      this.selectedSlots = [...draft.slots].sort((left, right) => left.startTime.localeCompare(right.startTime))
      this.lastVenueId = draft.venueId
      this.venueName = draft.venueName
      this.spaceId = draft.spaceId ?? this.selectedSlots[0]?.spaceId
      this.spaceName = draft.spaceName
      this.spaceType = draft.spaceType
      this.participantCount = draft.participantCount || 1
      this.seatIds = draft.seatIds || []
      this.lockSeconds = 0
    },
    /** 单独恢复提交意图：选择被清空后核对入口仍然需要它。 */
    restoreSubmitIntent() {
      if (!this.submitIntentKey) this.submitIntentKey = readSubmitIntentKey()
      return this.submitIntentKey
    },
    persistDraft() {
      writeDraft({
        slots: this.selectedSlots,
        venueId: this.lastVenueId,
        venueName: this.venueName,
        spaceId: this.spaceId,
        spaceName: this.spaceName,
        spaceType: this.spaceType,
        participantCount: this.participantCount,
        seatIds: this.seatIds
      })
    },
    startLockCountdown() {
      this.stopLockCountdown()
      if (!this.lockSeconds || typeof window === 'undefined') return
      lockTimer = window.setInterval(() => {
        if (!this.selectedSlots.length || this.lockSeconds <= 0) {
          this.stopLockCountdown()
          return
        }
        this.lockSeconds -= 1
      }, 1000)
    },
    stopLockCountdown() {
      if (lockTimer && typeof window !== 'undefined') {
        window.clearInterval(lockTimer)
      }
      lockTimer = undefined
    }
  }
})
