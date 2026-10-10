import { defineStore } from 'pinia'
import { funnelApi } from '@/api/funnel'
import type { FunnelEventPayload } from '@/api/types'

type FunnelEventInput = Omit<FunnelEventPayload, 'sessionKey' | 'visitorId' | 'sourceChannel'>

const randomToken = () => {
  const cryptoRef = globalThis.crypto
  if (cryptoRef && 'randomUUID' in cryptoRef && typeof cryptoRef.randomUUID === 'function') {
    return cryptoRef.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

const loadOrCreate = (key: string) => {
  if (typeof window === 'undefined') return randomToken()
  const existing = window.localStorage.getItem(key)
  if (existing) return existing
  const value = randomToken()
  window.localStorage.setItem(key, value)
  return value
}

export const useFunnelStore = defineStore('funnel', {
  state: () => ({
    sessionKey: loadOrCreate('venue_funnel_session_key'),
    visitorId: loadOrCreate('venue_funnel_visitor_id'),
    sourceChannel: 'WEB'
  }),
  actions: {
    event(base: FunnelEventInput): FunnelEventPayload {
      return {
        sessionKey: this.sessionKey,
        visitorId: this.visitorId,
        sourceChannel: this.sourceChannel,
        ...base
      }
    },
    async track(base: FunnelEventInput) {
      try {
        await funnelApi.record(this.event(base))
      } catch {
        console.debug('funnel track failed')
      }
    }
  }
})
