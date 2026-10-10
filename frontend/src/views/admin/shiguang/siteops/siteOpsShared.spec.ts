import { describe, expect, it } from 'vitest'

import {
  ASSET_TARGET_STATUSES,
  INCIDENT_TYPE_LABELS,
  INSPECTION_RESULT_LABELS,
  PRIORITY_LABELS,
  SEVERITY_LABELS,
  assetStatusLabel,
  assetTypeOptions,
  blockLinkText,
  blockWindowError,
  dayKey,
  eventLine,
  handoverForShift,
  handoverStatusLabel,
  initialTabFromRoute,
  isMutableAsset,
  isStartableTask,
  isTerminalTask,
  latestInspectionOf,
  lifecycleLine,
  openIncidents,
  openRepairOf,
  openTasks,
  readableError,
  repairActions,
  repairStatusLabel,
  responsibleText,
  shiftAction,
  shiftStatusLabel,
  shiftTaskOrder,
  shiftTimeRange,
  spaceName,
  supportsBlockLink,
  taskNote,
  taskResultEndpoint,
  taskStatusLabel,
  taskTypeLabel
} from './siteOpsShared'

const task = (overrides: Record<string, unknown> = {}) => ({
  id: 1,
  title: '灯具复核',
  taskType: 'INSPECTION',
  priority: 'HIGH',
  status: 'TODO',
  ...overrides
})

describe('siteOpsShared 页签与范围', () => {
  it('两条旧深链决定初始页签', () => {
    expect(initialTabFromRoute('VenueAdminAssets')).toBe('assets')
    expect(initialTabFromRoute('VenueAdminSiteOps')).toBe('shift')
    expect(initialTabFromRoute(undefined)).toBe('shift')
  })

  it('任务序列按当班处理顺序阅读：开放在前、按计划时间升序、无时间最后', () => {
    const rows = [
      task({ id: 3, status: 'DONE', dueTime: '2026-09-13T17:00:00' }),
      task({ id: 1, status: 'IN_PROGRESS', dueTime: '2026-09-13T18:30:00' }),
      task({ id: 2, status: 'TODO', dueTime: '2026-09-13T18:45:00' }),
      task({ id: 4, status: 'PENDING', dueTime: undefined }),
      task({ id: 5, status: 'CANCELED', dueTime: '2026-09-13T16:00:00' })
    ]
    expect(shiftTaskOrder(rows as never[]).map((item) => item.id)).toEqual([1, 2, 4, 5, 3])
  })

  it('未完成任务与未解决异常按服务端写入集统计', () => {
    const rows = [task(), task({ id: 2, status: 'IN_PROGRESS' }), task({ id: 3, status: 'DONE' }), task({ id: 4, status: 'PENDING' })]
    expect(openTasks(rows as never[]).map((item) => item.id)).toEqual([1, 2, 4])
    expect(
      openIncidents([
        { id: 9, status: 'OPEN' },
        { id: 10, status: 'IN_PROGRESS' },
        { id: 11, status: 'RESOLVED' }
      ] as never[]).map((item) => item.id)
    ).toEqual([9, 10])
  })

  it('任务态决定可开始、终态与两个真实端点的分派', () => {
    expect(isStartableTask(task({ status: 'TODO' }) as never)).toBe(true)
    expect(isStartableTask(task({ status: 'PENDING' }) as never)).toBe(true)
    expect(isStartableTask(task({ status: 'IN_PROGRESS' }) as never)).toBe(false)
    expect(isTerminalTask(task({ status: 'EXCEPTION' }) as never)).toBe(true)
    expect(taskResultEndpoint('DONE')).toBe('complete')
    expect(taskResultEndpoint('EXCEPTION')).toBe('complete')
    expect(taskResultEndpoint('CANCELED')).toBe('cancel')
  })

  it('交接只看源班次', () => {
    const rows = [{ id: 5, fromShiftId: 11, status: 'SUBMITTED' }, { id: 6, fromShiftId: 12, status: 'ACCEPTED' }]
    expect(handoverForShift(rows as never[], 12)?.id).toBe(6)
    expect(handoverForShift(rows as never[], undefined)).toBeNull()
  })

  it('班次动作由真实状态决定', () => {
    expect(shiftAction({ status: 'PLANNED' } as never)).toBe('clock-in')
    expect(shiftAction({ status: 'IN_PROGRESS' } as never)).toBe('clock-out')
    expect(shiftAction({ status: 'COMPLETED' } as never)).toBe('none')
  })
})

describe('siteOpsShared 标签（服务端字典口径）', () => {
  it('状态名取 sys_dict_data 中文而不是预览措辞', () => {
    expect(shiftStatusLabel('PLANNED')).toBe('已计划')
    expect(handoverStatusLabel('ACCEPTED')).toBe('已接收')
    expect(taskStatusLabel('PENDING')).toBe('待处理')
    expect(taskTypeLabel('INCIDENT_FOLLOW_UP')).toBe('异常跟进')
    expect(assetStatusLabel('IN_USE')).toBe('在用')
    expect(assetStatusLabel('INSPECTION_DUE')).toBe('待巡检')
    expect(repairStatusLabel('REQUESTED')).toBe('已请求')
    expect(PRIORITY_LABELS.NORMAL).toBe('普通')
  })

  it('字典缺失的未知值原样显示，不折叠不隐藏', () => {
    expect(taskStatusLabel('FUTURE_STATE')).toBe('FUTURE_STATE')
    expect(assetStatusLabel('')).toBe('—')
    expect(INCIDENT_TYPE_LABELS.COMPLAINT_SIGNAL).toBe('客户投诉预警')
    expect(SEVERITY_LABELS.CRITICAL).toBe('紧急')
    expect(Object.keys(INSPECTION_RESULT_LABELS)).toEqual(['PASS', 'ATTENTION', 'REPAIR_REQUIRED'])
  })

  it('手动状态变更只提供真实可达的四项目标态', () => {
    expect(ASSET_TARGET_STATUSES).toEqual(['IN_USE', 'OUT_OF_SERVICE', 'INSPECTION_DUE', 'RETIRED'])
  })
})

describe('siteOpsShared 资产与库存显式关联', () => {
  it('仅停用或维修中支持不可售联动，只有恢复使用释放块', () => {
    expect(supportsBlockLink('OUT_OF_SERVICE')).toBe(true)
    expect(supportsBlockLink('REPAIRING')).toBe(true)
    expect(supportsBlockLink('IN_USE')).toBe(false)
    expect(isMutableAsset({ status: 'RETIRED' } as never)).toBe(false)
    expect(isMutableAsset({ status: 'IN_USE' } as never)).toBe(true)
  })

  it('不可售时间窗要求成对且有序', () => {
    expect(blockWindowError(false, '', '')).toBe('')
    expect(blockWindowError(true, '2026-09-13T19:00', '')).toBe('请填写不可售开始和结束时间。')
    expect(blockWindowError(true, '2026-09-13T19:00', '2026-09-13T19:00')).toBe('不可售开始时间必须早于结束时间。')
    expect(blockWindowError(true, '2026-09-13T19:00', '2026-09-13T22:00')).toBe('')
  })

  it('库存块与责任人只渲染真实字段', () => {
    expect(blockLinkText({ currentUnavailableBlockId: 88 } as never)).toBe('关联库存块 #88')
    expect(blockLinkText({} as never)).toBe('无关联库存块')
    expect(responsibleText({ responsibleUserId: 7 } as never)).toBe('负责人 #7')
    expect(responsibleText({} as never)).toBe('未指定')
  })

  it('开放维修与最近巡检按资产过滤', () => {
    const repairs = [
      { id: 1, assetId: 501, status: 'IN_PROGRESS' },
      { id: 2, assetId: 502, status: 'REQUESTED' },
      { id: 3, assetId: 501, status: 'COMPLETED' }
    ]
    expect(openRepairOf(repairs as never[], 501)?.id).toBe(1)
    expect(openRepairOf(repairs as never[], 503)).toBeNull()
    const inspections = [
      { id: 10, assetId: 501, inspectedAt: '2026-09-12T09:00:00' },
      { id: 11, assetId: 501, inspectedAt: '2026-09-13T09:00:00' }
    ]
    expect(latestInspectionOf(inspections as never[], 501)?.id).toBe(11)
    expect(repairActions('REQUESTED')).toEqual({ start: true, complete: false, cancel: true })
    expect(repairActions('COMPLETED').cancel).toBe(false)
  })

  it('资产类型下拉回填台账已存在的真实值', () => {
    const options = assetTypeOptions([{ assetType: 'PROJECTOR' }, { assetType: 'LIGHTING' }] as never[])
    expect(options.map((item) => item.value)).toEqual(['EQUIPMENT', 'LIGHTING', 'CLEANING', 'PROJECTOR'])
  })

  it('生命周期行只组合真实事件字段', () => {
    expect(
      lifecycleLine({ eventType: 'REPAIR_REQUESTED', fromStatus: 'IN_USE', toStatus: 'REPAIRING', reasonText: '照明闪烁' } as never)
    ).toBe('维修申请 · 在用 → 维修中 · 照明闪烁')
  })
})

describe('siteOpsShared 时间与错误', () => {
  it('时间行与运营日按本地时间渲染', () => {
    expect(eventLine('2026-09-13T18:10:00', '巡检发现闪烁')).toBe('09月13日 18:10 · 巡检发现闪烁')
    expect(eventLine('2026-09-13T18:10:00', '')).toBe('09月13日 18:10')
    expect(eventLine(null, '仅有文案')).toBe('仅有文案')
    expect(dayKey(new Date(2026, 8, 13))).toBe('2026-09-13')
    expect(shiftTimeRange({ plannedStartTime: '2026-09-13T16:00:00', plannedEndTime: '2026-09-13T22:00:00' } as never)).toBe(
      '16:00—22:00'
    )
    expect(shiftTimeRange({} as never)).toBe('未安排时间')
  })

  it('任务说明只渲染真实 completionNote', () => {
    expect(taskNote(task({ completionNote: '已核对' }) as never)).toBe('已核对')
    expect(taskNote(task() as never)).toBe('')
  })

  it('服务端错误原文优先', () => {
    expect(readableError({ response: { data: { message: '存在未完成任务，请先完成交接确认' } } }, '兜底')).toBe(
      '存在未完成任务，请先完成交接确认'
    )
    expect(readableError(new Error('x'), '本次读取未完成')).toBe('本次读取未完成')
  })

  it('场地名缺失时退回编号，不伪造名称', () => {
    const names = new Map([[73, '羽毛球 2 号场']])
    expect(spaceName(names, 73)).toBe('羽毛球 2 号场')
    expect(spaceName(names, 99)).toBe('场地 #99')
    expect(spaceName(names, undefined)).toBe('未绑定场地')
  })
})
