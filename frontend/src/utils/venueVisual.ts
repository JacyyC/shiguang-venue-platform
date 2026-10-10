import type { VenueListItem } from '@/api/types'

/**
 * 画笔单线图标四枚（V5-UI-UX-SHIGUANG S02，视觉权威 design-preview.html §04 定稿）：
 * sport=球馆准星 / pool=泳馆三点雨+波浪 / study=自习双书脊 / house=活动坡顶屋。
 * V3/V4 的七母题满铺 pattern（court/halls/net/waves/windows/table）已随画笔语言退役。
 */
export type VenueMotif = 'sport' | 'pool' | 'study' | 'house'

/** 类型分区词表与 VenueListView 分区一致；STUDY_ROOM/ACTIVITY_ROOM 为同义历史码。 */
const CANONICAL_TYPE: Record<string, string> = {
  STUDY: 'STUDY',
  STUDY_ROOM: 'STUDY',
  ACTIVITY: 'ACTIVITY',
  ACTIVITY_ROOM: 'ACTIVITY'
}

/** 图标映射沿用 venuePrimaryType 词表：球类三型→准星、游泳→雨浪、自习·会议→书脊；
 *  未命中的类型回落坡顶屋形（宁缺勿错），会议等专属图标如需另补走三方同改。 */
const MOTIFS: Record<string, VenueMotif> = {
  BASKETBALL: 'sport',
  BADMINTON: 'sport',
  TENNIS: 'sport',
  SWIMMING: 'pool',
  STUDY: 'study',
  MEETING_ROOM: 'study',
  ACTIVITY: 'house'
}

export const venueMotifOfType = (type?: string | null): VenueMotif => {
  if (!type) return 'house'
  return MOTIFS[CANONICAL_TYPE[type] ?? type] || 'house'
}

/** 场馆主类型：取第一个可识别的空间类型；无类型数据时返回 undefined。 */
export const venuePrimaryType = (venue: Pick<VenueListItem, 'spaceTypes'>): string | undefined =>
  venue.spaceTypes?.find((type) => Boolean(type))

/** 排印海报族 wash（V3 L4，V5 §04 图标—wash 配对随预览定稿）：泳馆雾蓝 /
 *  球馆·坡顶屋松绿 / 自习·会议蜜金；未知类型回落屋形→松绿。 */
export type VenueWash = 'mist' | 'pine' | 'honey'

const MOTIF_WASH: Record<VenueMotif, VenueWash> = {
  sport: 'pine',
  pool: 'mist',
  study: 'honey',
  house: 'pine'
}

export const venueWashOf = (type?: string | null): VenueWash => MOTIF_WASH[venueMotifOfType(type)]

export interface VenueVisual {
  kind: 'image' | 'motif'
  src?: string
  motif?: VenueMotif
}

/** 场馆卡视觉（V5 §04 删内置 stock 图）：仅用户自传 venue.image 显示真实图；
 *  无图一律画笔单线图标（与列表页同源映射），不伪造业务图片。 */
export const venueVisualOf = (venue: Pick<VenueListItem, 'spaceTypes' | 'image'>): VenueVisual => {
  if (venue.image) return { kind: 'image', src: venue.image }
  return { kind: 'motif', motif: venueMotifOfType(venuePrimaryType(venue)) }
}
