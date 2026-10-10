import { defineStore } from 'pinia'
import { systemApi, type DictData } from '@/api/system'

interface DictState {
  cache: Record<string, DictData[]>
}

export const useDictStore = defineStore('dict', {
  state: (): DictState => ({ cache: {} }),
  actions: {
    async load(type: string) {
      if (!this.cache[type]) {
        this.cache[type] = await systemApi.dict(type)
      }
      return this.cache[type]
    },
    label(type: string, value: string) {
      return this.cache[type]?.find((item) => item.value === value)?.label || value
    }
  }
})
