import { describe, expect, it } from 'vitest'
import type { InventorySlot, VenueBusinessCalendar, VenueMaintenance, VenueSpace, VenueUnavailableBlock } from '@/api/types'
import {
  calendarHoursText,
  hoursAxis,
  mergeRecords,
  readableError,
  recordMetaLine,
  recordPeriodLabel,
  ruleSubtitle,
  ruleUnchanged,
  seatDraftError,
  spaceRowCells,
  toggleSpaceNote,
  venueHoursLabel
} from './resourcesShared'

const calendar = (overrides: Partial<VenueBusinessCalendar> = {}): VenueBusinessCalendar => ({
  id: 1,
  venueId: 101,
  calendarDate: '2026-09-13',
  openTime: '09:00:00',
  closeTime: '20:00:00',
  businessStatus: 'SPECIAL_HOURS',
  ...overrides
})

const slot = (hour: number, status = 'AVAILABLE'): InventorySlot => ({
  venueId: 101,
  spaceId: 73,
  startTime: `2026-09-13T${String(hour).padStart(2, '0')}:00:00`,
  endTime: `2026-09-13T${String(hour + 1).padStart(2, '0')}:00:00`,
  status,
  label: ''
})

const space = (overrides: Partial<VenueSpace> = {}): VenueSpace => ({
  id: 73,
  venueId: 101,
  name: '羽毛球2号场',
  type: '羽毛球',
  code: 'BAD-02',
  capacity: 4,
  pricePerHour: 80,
  status: 'ACTIVE',
  ...overrides
})

describe('hoursAxis', () => {
  it('日历覆盖优先于场馆常规营业时间', () => {
    expect(hoursAxis('08:00:00', '22:00:00', calendar())).toEqual([9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19])
  })

  it('无日历时用场馆常规营业时间', () => {
    expect(hoursAxis('08:00', '22:00', null)).toEqual([8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21])
  })

  it('缺省与闭馆日回退 08-22 骨架或空轴', () => {
    expect(hoursAxis(null, null, null)).toHaveLength(14)
    expect(hoursAxis('10:00', '10:00', null)).toEqual([])
  })
})

describe('spaceRowCells', () => {
  it('按小时索引槽位，缺失切片为 null（不伪造状态）', () => {
    const cells = spaceRowCells([8, 9, 10], [slot(9, 'RESERVED')])
    expect(cells[0].slot).toBeNull()
    expect(cells[1].slot?.status).toBe('RESERVED')
    expect(cells[2].slot).toBeNull()
  })
})

describe('mergeRecords', () => {
  const maintenance: VenueMaintenance = {
    id: 7,
    spaceId: 73,
    startTime: '2026-09-13T16:00:00',
    endTime: '2026-09-13T18:00:00',
    reason: '阅读区灯具检修',
    status: 'ACTIVE'
  }
  const block: VenueUnavailableBlock = {
    id: 12,
    venueId: 101,
    spaceId: 73,
    blockType: 'MANUAL_HOLD',
    reasonCode: 'MANUAL_HOLD',
    reasonText: '校队训练保留',
    startTime: '2026-09-13T10:00:00',
    endTime: '2026-09-13T12:00:00',
    bufferBeforeMinutes: 0,
    bufferAfterMinutes: 15,
    status: 'ACTIVE'
  }

  it('维护在前、按日期过滤、带类型标签与缓冲', () => {
    const rows = mergeRecords([maintenance], [block], new Map([[73, '羽毛球2号场']]), '2026-09-13')
    expect(rows).toHaveLength(2)
    expect(rows[0].kind).toBe('maintenance')
    expect(rows[0].typeLabel).toBe('维护')
    expect(rows[1].typeLabel).toBe('人工保留')
    expect(recordMetaLine(rows[1])).toBe('10:00–12:00 · #12 · 生效中 · 前后缓冲 0/15 分钟')
  })

  it('过滤其他日期，场馆级块显示全场馆', () => {
    const venueBlock: VenueUnavailableBlock = { ...block, id: 13, spaceId: undefined, startTime: '2026-09-14T10:00:00', endTime: '2026-09-14T12:00:00' }
    const rows = mergeRecords([], [venueBlock], new Map(), '2026-09-13')
    expect(rows).toEqual([])
    expect(mergeRecords([], [venueBlock], new Map(), '2026-09-14')[0].spaceName).toBe('全场馆')
  })
})

describe('规则展示', () => {
  it('三种预订方式与未配置的副标', () => {
    expect(ruleSubtitle(space({ resourceRule: undefined }))).toBe('尚未配置预订规则')
    expect(ruleSubtitle(space({ resourceRule: { spaceId: 73, resourceCategory: 'SPORTS_FIELD', bookingMode: 'EXCLUSIVE', versionNo: 2, status: 'ACTIVE', seats: [] } })))
      .toBe('每笔占用整空间 1 份')
    expect(ruleSubtitle(space({ resourceRule: { spaceId: 73, resourceCategory: 'SPORTS_FIELD', bookingMode: 'CAPACITY', capacityLimit: 20, versionNo: 4, status: 'ACTIVE', seats: [] } })))
      .toBe('共享预订上限 20 人')
    expect(ruleSubtitle(space({ resourceRule: { spaceId: 73, resourceCategory: 'SPORTS_FIELD', bookingMode: 'ASSIGNED_SEAT', versionNo: 3, status: 'ACTIVE', seats: [{ id: 1, seatCode: 'A-01', seatLabel: '座位 1', status: 'ACTIVE' }] } })))
      .toBe('按当前 1 个座位配置')
  })

  it('规则未变化判定（同版本去重提示）', () => {
    const target = space({ resourceRule: { spaceId: 73, resourceCategory: 'SPORTS_FIELD', bookingMode: 'CAPACITY', capacityLimit: 20, versionNo: 4, status: 'ACTIVE', seats: [] } })
    expect(ruleUnchanged(target, { resourceCategory: 'SPORTS_FIELD', bookingMode: 'CAPACITY', capacityLimit: 20, seats: [] })).toBe(true)
    expect(ruleUnchanged(target, { resourceCategory: 'SPORTS_FIELD', bookingMode: 'CAPACITY', capacityLimit: 10, seats: [] })).toBe(false)
  })

  it('座位草稿校验与服务端同文案', () => {
    expect(seatDraftError([])).toBe('指定座位至少需要配置一个座位。')
    expect(seatDraftError([{ seatCode: 'A-1', seatLabel: 'x' }, { seatCode: 'A-1', seatLabel: 'y' }])).toBe('座位编码不能重复，请核对后保存。')
    expect(seatDraftError([{ seatCode: 'A-1', seatLabel: 'x' }])).toBeNull()
  })
})

describe('营业与时间', () => {
  it('闭馆显示不开放预约，特殊时段显示覆盖时间', () => {
    expect(calendarHoursText(calendar({ businessStatus: 'CLOSED' }))).toBe('不开放预约')
    expect(calendarHoursText(calendar())).toBe('09:00–20:00')
    expect(calendarHoursText(null)).toBe('按场馆常规营业时间')
  })

  it('记录与常规营业时间标签', () => {
    expect(recordPeriodLabel('2026-09-13T16:00:00', '2026-09-13T18:00:00')).toBe('16:00–18:00')
    expect(venueHoursLabel('08:00:00', '22:00:00')).toBe('08:00–22:00')
  })

  it('停用/启用说明按状态渲染', () => {
    expect(toggleSpaceNote(space())).toContain('停用会取消该场地未来可履约预约')
    expect(toggleSpaceNote(space({ status: 'MAINTENANCE' }))).toContain('启用后仍需重新读取库存')
  })
})

describe('readableError', () => {
  it('优先展示服务端业务消息（409/422 冲突提示逐字透出）', () => {
    const conflict = Object.assign(new Error('Request failed'), {
      response: { data: { message: '营业日历与有效预约冲突，无法保存' } }
    })
    expect(readableError(conflict, 'fallback')).toBe('营业日历与有效预约冲突，无法保存')
    expect(readableError(new Error('网络错误'), 'fallback')).toBe('网络错误')
    expect(readableError({}, 'fallback')).toBe('fallback')
  })
})
