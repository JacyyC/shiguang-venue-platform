import { defineStore } from 'pinia'

/** 拾光一级浏览共享筛选状态：跨 / /venues /activities 路由保持，输入不重排内容、提交才应用。 */
export const useShiguangStore = defineStore('shiguang', {
  state: () => ({
    query: '',
    area: 'all',
    category: 'all',
    day: 0,
    activityKind: 'all' as 'all' | 'event' | 'invitation',
    /** 场馆速览侧栏（S05 裁决变更）：非空即打开 */
    quickVenueId: null as number | null,
    /** V02 目录离开时的浏览位置：返回时恢复，进入即清零 */
    directoryScroll: 0
  }),
  actions: {
    openVenueDrawer(venueId: number) {
      this.quickVenueId = venueId
    },
    closeVenueDrawer() {
      this.quickVenueId = null
    },
    clearFilters() {
      this.query = ''
      this.area = 'all'
      this.category = 'all'
      this.day = 0
      this.activityKind = 'all'
    }
  }
})
