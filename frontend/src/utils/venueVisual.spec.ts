import { describe, expect, it } from 'vitest'
import { venueMotifOfType, venuePrimaryType, venueVisualOf, venueWashOf } from './venueVisual'

describe('venueVisualOf 场馆卡类型视觉映射（V5 §04 删内置 stock 图）', () => {
  it('仅用户自传 venue.image 显示真实图（有图优先）', () => {
    expect(venueVisualOf({ spaceTypes: ['BADMINTON'], image: '/img/own.webp' })).toEqual({
      kind: 'image',
      src: '/img/own.webp'
    })
  })

  it('无图一律画笔图标：BASKETBALL/SWIMMING/TENNIS 等不再回退内置 stock 实拍图', () => {
    expect(venueVisualOf({ spaceTypes: ['BASKETBALL'] })).toEqual({ kind: 'motif', motif: 'sport' })
    expect(venueVisualOf({ spaceTypes: ['SWIMMING'] })).toEqual({ kind: 'motif', motif: 'pool' })
    expect(venueVisualOf({ spaceTypes: ['TENNIS'] })).toEqual({ kind: 'motif', motif: 'sport' })
  })

  it('BADMINTON/STUDY/ACTIVITY/MEETING_ROOM 用对应画笔图标', () => {
    expect(venueVisualOf({ spaceTypes: ['BADMINTON'] })).toEqual({ kind: 'motif', motif: 'sport' })
    expect(venueVisualOf({ spaceTypes: ['STUDY_ROOM'] })).toEqual({ kind: 'motif', motif: 'study' })
    expect(venueVisualOf({ spaceTypes: ['ACTIVITY'] })).toEqual({ kind: 'motif', motif: 'house' })
    expect(venueVisualOf({ spaceTypes: ['MEETING_ROOM'] })).toEqual({ kind: 'motif', motif: 'study' })
  })

  it('未知或缺失类型回落屋形图标，不随机轮换', () => {
    expect(venueVisualOf({ spaceTypes: ['SOMETHING_NEW'] })).toEqual({ kind: 'motif', motif: 'house' })
    expect(venueVisualOf({})).toEqual({ kind: 'motif', motif: 'house' })
  })

  it('主类型取第一个可识别类型', () => {
    expect(venuePrimaryType({ spaceTypes: ['BADMINTON', 'TENNIS'] })).toBe('BADMINTON')
    expect(venuePrimaryType({ spaceTypes: [] })).toBeUndefined()
  })

  it('画笔四枚映射沿用 venuePrimaryType 词表（预览 §04 定稿）', () => {
    expect(venueMotifOfType('BASKETBALL')).toBe('sport')
    expect(venueMotifOfType('BADMINTON')).toBe('sport')
    expect(venueMotifOfType('TENNIS')).toBe('sport')
    expect(venueMotifOfType('SWIMMING')).toBe('pool')
    expect(venueMotifOfType('STUDY')).toBe('study')
    expect(venueMotifOfType('MEETING_ROOM')).toBe('study')
    expect(venueMotifOfType('ACTIVITY_ROOM')).toBe('house')
  })

  it('图标—wash 配对随预览定稿：球馆·屋=松绿 / 泳=雾蓝 / 自习·会议=蜜金', () => {
    expect(venueWashOf('BASKETBALL')).toBe('pine')
    expect(venueWashOf('SWIMMING')).toBe('mist')
    expect(venueWashOf('STUDY')).toBe('honey')
    expect(venueWashOf('ACTIVITY')).toBe('pine')
    expect(venueWashOf('UNKNOWN_TYPE')).toBe('pine')
  })
})
