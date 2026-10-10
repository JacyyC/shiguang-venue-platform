import { describe, expect, it } from 'vitest'
import {
  ATTENDANCE_STATUS_LABELS,
  BLOCK_TYPE_LABELS,
  BOOKING_MODE_LABELS,
  BUSINESS_STATUS_LABELS,
  CHECKIN_STATUS_LABELS,
  NO_SHOW_STATUS_LABELS,
  ORDER_STATUS_LABELS,
  REFUND_STATUS_LABELS,
  RESOURCE_CATEGORY_LABELS,
  SLOT_STATUS_LABELS,
  SPACE_STATUS_LABELS,
  USER_STATUS_LABELS,
  attendanceStatusLabel,
  blockTypeLabel,
  bookingModeLabel,
  businessStatusLabel,
  checkinStatusLabel,
  formatHm,
  formatMonthDay,
  maskEmail,
  money,
  monthDayWithWeekday,
  noShowBadge,
  orderStatusLabel,
  refundStatusLabel,
  resourceCategoryLabel,
  slotStatusLabel,
  spaceStatusLabel,
  timeRange,
  userStatusLabel,
  focusable,
  restoreFocusChain,
  resolveInitialTab
} from './adminShared'

describe('adminShared 标签全集', () => {
  it('库存槽位六状态与冻结预览 labels 一一对应', () => {
    expect(SLOT_STATUS_LABELS).toEqual({
      AVAILABLE: '可约',
      RESERVED: '已约',
      UNAVAILABLE: '维护',
      MANUAL_HOLD: '保留',
      CLOSED: '关闭',
      BUFFER: '缓冲'
    })
  })

  it('不可售类型覆盖预览六类并补系统写入的 WEATHER', () => {
    expect(blockTypeLabel('MANUAL_HOLD')).toBe('人工保留')
    expect(blockTypeLabel('MAINTENANCE')).toBe('维护')
    expect(blockTypeLabel('PRIVATE_EVENT')).toBe('包场')
    expect(blockTypeLabel('SETUP')).toBe('布场撤场')
    expect(blockTypeLabel('CLEANING')).toBe('清洁')
    expect(blockTypeLabel('CLOSURE')).toBe('闭馆')
    expect(BLOCK_TYPE_LABELS.WEATHER).toBe('天气')
    expect(blockTypeLabel('OTHER')).toBe('OTHER')
  })

  it('预订方式与资源类型对齐预览 cats/modes', () => {
    expect(BOOKING_MODE_LABELS).toEqual({ EXCLUSIVE: '整空间', CAPACITY: '按人数', ASSIGNED_SEAT: '指定座位' })
    expect(RESOURCE_CATEGORY_LABELS).toEqual({
      SPORTS_FIELD: '体育场地',
      STUDY_ROOM: '自习空间',
      ACTIVITY_ROOM: '活动空间'
    })
    expect(bookingModeLabel('CAPACITY')).toBe('按人数')
    expect(resourceCategoryLabel('SPORTS_FIELD')).toBe('体育场地')
  })

  it('营业日历状态不含预览的 INTERNAL_ONLY（真实枚举无此值）', () => {
    expect(Object.keys(BUSINESS_STATUS_LABELS).sort()).toEqual(['CLOSED', 'OPEN', 'SPECIAL_HOURS'])
    expect(businessStatusLabel('OPEN')).toBe('正常营业')
    expect(businessStatusLabel('INTERNAL_ONLY')).toBe('INTERNAL_ONLY')
  })

  it('到场/核销/爽约/凭证标签覆盖服务端常量全集', () => {
    expect(ATTENDANCE_STATUS_LABELS).toEqual({
      PENDING: '待确认',
      PRESENT: '已到场',
      LATE: '迟到到场',
      MANUAL_PRESENT: '人工到场',
      ABSENT: '已确认爽约',
      CANCELED: '已取消'
    })
    expect(CHECKIN_STATUS_LABELS).toEqual({ NOT_CHECKED_IN: '未核销', CHECKED_IN: '已核销', CANCELED: '已取消' })
    expect(NO_SHOW_STATUS_LABELS).toEqual({
      NOT_EVALUATED: '未评估',
      CANDIDATE: '待确认',
      CONFIRMED: '已确认',
      REVOKED: '已撤销'
    })
    expect(attendanceStatusLabel('MANUAL_PRESENT')).toBe('人工到场')
    expect(attendanceStatusLabel(null)).toBe('待确认')
    expect(checkinStatusLabel('CHECKED_IN')).toBe('已核销')
  })

  it('退款单标签覆盖 RefundStatus 全集（FAILED 同风格补全）', () => {
    expect(Object.keys(REFUND_STATUS_LABELS).sort()).toEqual(['FAILED', 'PENDING_REVIEW', 'REFUNDING', 'REJECTED', 'SUCCESS'])
    expect(refundStatusLabel('PENDING_REVIEW')).toBe('待审核')
    expect(refundStatusLabel('SUCCESS')).toBe('退款成功')
    expect(refundStatusLabel('FAILED')).toBe('退款失败')
  })

  it('场地与用户状态对齐服务端枚举', () => {
    expect(SPACE_STATUS_LABELS).toEqual({ ACTIVE: '启用', MAINTENANCE: '维护中', CLOSED: '已关闭' })
    expect(spaceStatusLabel('MAINTENANCE')).toBe('维护中')
    expect(USER_STATUS_LABELS).toEqual({ ACTIVE: '正常', DISABLED: '停用' })
    expect(userStatusLabel('DISABLED')).toBe('停用')
  })

  it('订单状态沿用与用户交易面相同的映射（同一事实）', () => {
    expect(orderStatusLabel('PARTIAL_REFUNDED')).toBe('部分退款')
    expect(orderStatusLabel('TIMEOUT')).toBe('支付超时')
  })
})

describe('adminShared 格式化', () => {
  it('金额、时间段与月份日格式对齐预览', () => {
    expect(money(80)).toBe('80.00')
    expect(money(null)).toBe('0.00')
    expect(timeRange('2026-09-13T19:00:00', '2026-09-13T20:00:00')).toBe('19:00—20:00')
    expect(formatMonthDay('2026-09-13T19:00:00')).toBe('09月13日')
    expect(monthDayWithWeekday('2026-09-13T19:00:00')).toBe('09月13日 · 周日')
    expect(formatHm('2026-09-13T18:45:00')).toBe('18:45')
    expect(formatMonthDay('not-a-date')).toBe('')
  })

  it('邮箱掩码对齐预览 l***@example.test 形态', () => {
    expect(maskEmail('lin.cheng@example.test')).toBe('l***@example.test')
    expect(maskEmail('')).toBe('')
    expect(maskEmail('no-at-sign')).toBe('no-at-sign')
  })
})

describe('adminShared 爽约标记口径', () => {
  it('未核销时展示爽约候选/已确认，核销后不再并列展示', () => {
    expect(noShowBadge('NOT_CHECKED_IN', 'CANDIDATE')).toBe('爽约待确认')
    expect(noShowBadge('NOT_CHECKED_IN', 'CONFIRMED')).toBe('爽约已确认')
    expect(noShowBadge('CHECKED_IN', 'CANDIDATE')).toBe('')
    expect(noShowBadge('CHECKED_IN', 'CONFIRMED')).toBe('')
    expect(noShowBadge('NOT_CHECKED_IN', 'REVOKED')).toBe('')
    expect(noShowBadge('NOT_CHECKED_IN', null)).toBe('')
  })
})

describe('adminShared 弹层焦点恢复链（B3-T72，三面共用）', () => {
  const buildFace = (): HTMLElement => {
    const face = document.createElement('section')
    face.className = 'sga-probe'
    face.innerHTML = `
      <button data-test="trigger">触发</button>
      <button data-test="trigger" disabled>被禁用的同名触发</button>
      <button data-test="tab" aria-pressed="true">页签</button>`
    document.body.appendChild(face)
    return face
  }

  it('原触发元素仍可聚焦时直接回焦', () => {
    const face = buildFace()
    const trigger = face.querySelector<HTMLElement>('button[data-test="trigger"]')!
    const landed = restoreFocusChain({
      rootSelector: '.sga-probe', trigger, triggerTest: 'trigger', tabSelector: '.sga-probe [data-test="tab"]'
    })
    expect(landed).toBe(trigger)
    expect(document.activeElement).toBe(trigger)
    face.remove()
  })

  it('触发元素被重渲染替换时按 data-test 找回面内节点', () => {
    const face = buildFace()
    const stale = document.createElement('button')
    stale.setAttribute('data-test', 'trigger')
    const landed = restoreFocusChain({
      rootSelector: '.sga-probe', trigger: stale, triggerTest: 'trigger', tabSelector: '.sga-probe [data-test="tab"]'
    })
    expect(landed?.getAttribute('data-test')).toBe('trigger')
    expect(landed).not.toBe(stale)
    expect(document.activeElement).toBe(landed)
    face.remove()
  })

  it('触发元素被禁用且面内同名节点也不可聚焦时回落到页签，而不是 body', () => {
    const face = buildFace()
    const disabled = face.querySelectorAll<HTMLElement>('button[data-test="trigger"]')[1]
    const landed = restoreFocusChain({
      rootSelector: '.sga-probe', trigger: disabled, triggerTest: 'trigger', tabSelector: '.sga-probe [data-test="tab"]'
    })
    // 面内第一个同名节点可聚焦，所以这一跳仍应命中它而不是页签
    expect(landed?.getAttribute('data-test')).toBe('trigger')
    expect(document.activeElement).not.toBe(document.body)
    face.remove()
  })

  it('面内找不到可聚焦节点时回落到页签；都没有则返回 null', () => {
    const face = buildFace()
    const landed = restoreFocusChain({
      rootSelector: '.sga-probe', trigger: null, triggerTest: '不存在', tabSelector: '.sga-probe [data-test="tab"]'
    })
    expect(landed?.getAttribute('data-test')).toBe('tab')
    const empty = document.createElement('section')
    empty.className = 'sga-empty'
    document.body.appendChild(empty)
    expect(restoreFocusChain({ rootSelector: '.sga-empty', trigger: null, triggerTest: null, tabSelector: '.sga-empty button' })).toBeNull()
    face.remove()
    empty.remove()
  })

  it('focusable 把未挂载、禁用与不可见节点都判为不可聚焦', () => {
    const detached = document.createElement('button')
    expect(focusable(detached)).toBe(false)
    const disabled = document.createElement('button')
    disabled.setAttribute('disabled', '')
    document.body.appendChild(disabled)
    expect(focusable(disabled)).toBe(false)
    disabled.remove()
    const hidden = document.createElement('button')
    hidden.style.visibility = 'hidden'
    document.body.appendChild(hidden)
    expect(focusable(hidden)).toBe(false)
    hidden.remove()
    expect(focusable(null)).toBe(false)
  })
})

const TABS = ['accounts', 'levels', 'rewards'] as const
const ROUTE_TABS: Partial<Record<string, (typeof TABS)[number]>> = {
  SystemMembers: 'accounts',
  SystemRewardCatalog: 'rewards'
}

function initial(name: string | undefined, hash: string, allowed: readonly string[] = TABS) {
  return resolveInitialTab<typeof TABS[number]>({
    tabKeys: TABS,
    routeTabs: ROUTE_TABS,
    routeName: name,
    hash,
    canView: (tab) => allowed.includes(tab)
  })
}

describe('resolveInitialTab 页签初始口径（F-20）', () => {
  it('合法 hash 优先于路由默认面，两条路由都适用', () => {
    expect(initial('SystemMembers', '#rewards')).toBe('rewards')
    expect(initial('SystemRewardCatalog', '#levels')).toBe('levels')
  })

  it('无 hash 与非法 hash 都回落到该路由默认面', () => {
    expect(initial('SystemMembers', '')).toBe('accounts')
    expect(initial('SystemRewardCatalog', '')).toBe('rewards')
    expect(initial('SystemMembers', '#<页签>')).toBe('accounts')
    expect(initial('SystemRewardCatalog', '#nope')).toBe('rewards')
  })

  it('路由默认面无权时回落首个有权页签，未知路由同理', () => {
    expect(initial('SystemMembers', '', ['levels', 'rewards'])).toBe('levels')
    expect(initial('SystemRewardCatalog', '', ['levels'])).toBe('levels')
    expect(initial('VenueSomething', '', ['levels'])).toBe('levels')
    expect(initial(undefined, '', ['levels'])).toBe('levels')
  })

  it('一个页签都无权时确定性地停在首个页签，由叶子权限给出无权态', () => {
    expect(initial('SystemRewardCatalog', '', [])).toBe('accounts')
    expect(initial('SystemMembers', '', [])).toBe('accounts')
  })

  it('无权页签的 hash 深链保持激活，交给页内叶子权限判定', () => {
    expect(initial('SystemMembers', '#rewards', ['accounts'])).toBe('rewards')
  })
})
