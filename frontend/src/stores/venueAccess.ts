import { ref } from 'vue'
import { defineStore } from 'pinia'
import { venueAdminApi } from '@/api/venue'
import type { Venue } from '@/api/types'

// 场馆工作台公共场馆上下文（G-F2）：可管理场馆清单 + 当前场馆（持久化）+ 切换广播。
// 服务端没有"当前场馆"状态；旧正文不强制消费 activeVenueId，各视图可在就绪后自行接入。
const STORAGE_KEY = 'sg-venue-scope'

const readStoredVenueId = (): number | null => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const parsed = raw != null ? Number(raw) : Number.NaN
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null
  } catch {
    return null
  }
}

const writeStoredVenueId = (id: number | null): void => {
  try {
    if (id == null) localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, String(id))
  } catch {
    // 持久化失败不阻断切换
  }
}

export const useVenueAccessStore = defineStore('venueAccess', () => {
  const venues = ref<Venue[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const activeVenueId = ref<number | null>(readStoredVenueId())
  // 切换广播：消费方 watch 此计数清理旧场馆范围的缓存/草稿（旧正文不强制消费）
  let scopeVersion = 0
  const scopeChangedAt = ref(0)

  const reset = () => {
    venues.value = []
    loaded.value = false
    loading.value = false
    activeVenueId.value = null
    writeStoredVenueId(null)
  }

  const refresh = async () => {
    if (loading.value) return
    loading.value = true
    try {
      venues.value = await venueAdminApi.manageable()
      loaded.value = true
      // 校验持久化的当前场馆仍可管理；不可管理时回落首个可管理场馆
      const stillManageable = activeVenueId.value != null && venues.value.some((venue) => venue.id === activeVenueId.value)
      if (!stillManageable) {
        activeVenueId.value = venues.value[0]?.id ?? null
        writeStoredVenueId(activeVenueId.value)
      }
    } finally {
      loading.value = false
    }
  }

  const switchVenue = (venueId: number | null) => {
    if (venueId === activeVenueId.value) return
    activeVenueId.value = venueId
    writeStoredVenueId(venueId)
    scopeVersion += 1
    scopeChangedAt.value = scopeVersion
  }

  return { venues, loaded, loading, activeVenueId, scopeChangedAt, refresh, reset, switchVenue }
})
