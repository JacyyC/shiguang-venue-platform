import type { ShiguangScene } from '@/components/shiguang/types'

/** 分类 → 真实场地类型关键词（A9 可解释映射；未命中回落 together，插画仅装饰不暗示设施）。 */
const SCENE_KEYWORDS: Array<[ShiguangScene, string[]]> = [
  ['move', ['BASKETBALL', 'BADMINTON', 'TENNIS', '球']],
  ['quiet', ['MEETING_ROOM', '会议', '自习', '读']],
  ['water', ['SWIM', '泳']],
  ['together', ['聚会', '相聚']]
]

export const mapVenueScene = (spaceTypes?: string[] | null): ShiguangScene => {
  const joined = (spaceTypes ?? []).join(' ')
  for (const [scene, keywords] of SCENE_KEYWORDS) {
    if (keywords.some((keyword) => joined.includes(keyword))) return scene
  }
  return 'together'
}

export const CATEGORY_SCENES: Record<string, ShiguangScene> = {
  move: 'move',
  quiet: 'quiet',
  water: 'water',
  together: 'together'
}

export const FEATURE_EDITORIAL: Record<ShiguangScene, { kicker: string; lines: string[]; description: string }> = {
  move: { kicker: '网球 · 傍晚好去处', lines: ['下班之后，', '去接住一场落日。'], description: '让球拍接住今天最后一束光。剩下的事，明天再说。' },
  quiet: { kicker: '阅读 · 一个人也很好', lines: ['把世界调小，', '把这一页读完。'], description: '一张安静的桌子，一盏为你亮着的灯。不必着急。' },
  water: { kicker: '游泳 · 换一种呼吸', lines: ['今天的杂音，', '留在水面之外。'], description: '找回自己的节奏，让身体先抵达一段轻松的时间。' },
  together: { kicker: '相聚 · 见面正当时', lines: ['好久不见，', '不如就见一面。'], description: '不用准备一个完美的话题。带本书，来坐一会儿。' }
}

export const categoryMatchesSpaceTypes = (category: string, spaceTypes?: string[] | null): boolean => {
  if (category === 'all') return true
  const scene = mapVenueScene(spaceTypes)
  return scene === category
}
