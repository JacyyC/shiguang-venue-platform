import { venueApi } from '@/api/venue'

/**
 * 场地名称解析（S04）：预约/订单接口只带 spaceId，场地名经公开场馆空间接口解析并按进程缓存。
 * 只读公开数据；读取失败的场馆记录为已解析（回退为空，展示层回退到场馆名）。
 */
const venueSpacesLoaded = new Set<number>()
const spaceNames = new Map<number, string>()

export async function ensureSpaceNames(venueIds: number[]): Promise<void> {
  await Promise.all([...new Set(venueIds.filter(id => id != null))].map(async venueId => {
    if (venueSpacesLoaded.has(venueId)) return
    venueSpacesLoaded.add(venueId)
    try {
      const spaces = await venueApi.spaces(venueId)
      for (const space of spaces) spaceNames.set(space.id, space.name)
    } catch {
      // 名称缺失时展示层回退
    }
  }))
}

export function spaceNameOf(spaceId: number | null | undefined): string {
  if (spaceId == null) return ''
  return spaceNames.get(spaceId) ?? ''
}

export function resetSpaceNameCacheForTests(): void {
  venueSpacesLoaded.clear()
  spaceNames.clear()
}
