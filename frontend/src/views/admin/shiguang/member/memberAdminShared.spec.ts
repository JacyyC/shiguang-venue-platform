import { describe, expect, it } from 'vitest'
import {
  accountDisplayName,
  accountSubtitle,
  canCompensate,
  canRetry,
  catalogConditionText,
  catalogStatusLabel,
  catalogToggleTarget,
  catalogVersionCaption,
  discountRateLabel,
  groupedInt,
  hasPublishedVersion,
  levelDistributionRows,
  levelStatusLabel,
  memberRewardEventTypeLabel,
  memberRewardSourceLabel,
  overviewCountRows,
  redemptionStatusLabel,
  shortDateTime,
  statusTransitionLabel
} from './memberAdminShared'

describe('会员面标签与格式化', () => {
  it('maps the real level statuses including the DISABLED code alias', () => {
    expect(levelStatusLabel('ACTIVE')).toBe('启用')
    expect(levelStatusLabel('INACTIVE')).toBe('停用')
    // 服务端把 DISABLED 归一为 INACTIVE；它不是字典值，但前端历史上就是发它。
    expect(levelStatusLabel('DISABLED')).toBe('停用')
    expect(levelStatusLabel(null)).toBe('启用')
    expect(levelStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })

  it('keeps the three real catalog states instead of the preview two (B4-M12)', () => {
    expect(catalogStatusLabel('DRAFT')).toBe('草稿')
    expect(catalogStatusLabel('ACTIVE')).toBe('已启用')
    expect(catalogStatusLabel('INACTIVE')).toBe('已停用')
    expect(catalogStatusLabel('UNKNOWN')).toBe('UNKNOWN')
    expect(catalogToggleTarget('ACTIVE')).toBe('INACTIVE')
    expect(catalogToggleTarget('DRAFT')).toBe('ACTIVE')
    expect(catalogToggleTarget('INACTIVE')).toBe('ACTIVE')
  })

  it('never claims the preview’s single synthetic failure cause (B4-M16)', () => {
    expect(redemptionStatusLabel('FAILED')).toBe('失败')
    expect(redemptionStatusLabel('FAILED')).not.toContain('发券')
    expect(redemptionStatusLabel('SUCCEEDED')).toBe('兑换成功')
    expect(redemptionStatusLabel('COMPENSATED')).toBe('已补偿')
    expect(redemptionStatusLabel('PENDING')).toBe('处理中')
  })

  it('gates retry and compensation by the real server-side preconditions (B4-M2)', () => {
    expect(canRetry('FAILED')).toBe(true)
    // 后端只认 FAILED：PENDING/REJECTED 一律 409，不能在前端放行。
    expect(canRetry('PENDING')).toBe(false)
    expect(canRetry('REJECTED')).toBe(false)
    expect(canRetry('SUCCEEDED')).toBe(false)
    expect(canCompensate('SUCCEEDED', 501)).toBe(true)
    // 补偿要求券存在：没有 userCouponId 的兑换服务端直接拒绝。
    expect(canCompensate('SUCCEEDED', null)).toBe(false)
    expect(canCompensate('FAILED', 501)).toBe(false)
  })

  it('labels events through a derived whitelist with raw-value fallback (B4-M10/M11)', () => {
    expect(memberRewardEventTypeLabel('REDEMPTION_SUCCEEDED')).toBe('积分兑换成功')
    expect(memberRewardEventTypeLabel('CATALOG_VERSION_PUBLISHED')).toBe('兑换版本发布')
    expect(memberRewardEventTypeLabel('REDEMPTION_SUCCEEDED_V2')).toBe('REDEMPTION_SUCCEEDED_V2')
    expect(memberRewardSourceLabel('USER')).toBe('会员自助')
    expect(memberRewardSourceLabel('ADMIN')).toBe('管理员操作')
    expect(memberRewardSourceLabel('SYSTEM')).toBe('系统触发')
    expect(memberRewardSourceLabel('PARTNER')).toBe('PARTNER')
  })

  it('composes the status transition column without inventing values', () => {
    expect(statusTransitionLabel('PENDING', 'SUCCEEDED')).toBe('处理中 → 兑换成功')
    expect(statusTransitionLabel('PENDING', 'PENDING')).toBe('处理中')
    expect(statusTransitionLabel(null, 'FAILED')).toBe('失败')
    expect(statusTransitionLabel('PENDING', null)).toBe('处理中')
    expect(statusTransitionLabel(null, null)).toBe('')
  })

  it('formats discount rates the way the reference does', () => {
    expect(discountRateLabel(1)).toBe('标准价')
    expect(discountRateLabel(0.9)).toBe('9 折')
    expect(discountRateLabel(0.95)).toBe('9.5 折')
    expect(discountRateLabel(0.85)).toBe('8.5 折')
    expect(discountRateLabel(null)).toBe('标准价')
  })

  it('formats integers and timestamps for the ledger, not preview strings', () => {
    expect(groupedInt(2480)).toBe('2,480')
    expect(groupedInt(0)).toBe('0')
    expect(groupedInt(null)).toBe('0')
    expect(shortDateTime('2026-09-12T09:40:00')).toBe('09-12 09:40')
    expect(shortDateTime(null)).toBe('')
  })

  it('falls back through nickname, username and id without fabricating a member code', () => {
    expect(accountDisplayName({ userId: 1, nickname: '林间小满', username: 'linjian' })).toBe('林间小满')
    expect(accountDisplayName({ userId: 1, nickname: '  ', username: 'linjian' })).toBe('linjian')
    expect(accountDisplayName({ userId: 2048 })).toBe('会员 2048')
    expect(accountSubtitle({ userId: 2048, username: 'linjian' })).toBe('2048 · linjian')
    expect(accountSubtitle({ userId: 2048 })).toBe('2048')
  })

  it('keeps the real overview semantics: active accounts equal total, samples are capped (B4-M8)', () => {
    const rows = overviewCountRows({ totalMembers: 128, activeMembers: 128, levelDistribution: {}, coreUsers: [], dormantUsers: [] })
    expect(rows).toEqual([
      { label: '会员总数', value: 128 },
      { label: '启用会员账户', value: 128 }
    ])
    // 文案不得声称活跃度口径。
    expect(rows.map((row) => row.label).join()).not.toContain('活跃')
    expect(overviewCountRows(null)).toEqual([])
    expect(levelDistributionRows({ totalMembers: 0, activeMembers: 0, levelDistribution: { 初见: 2, 常来: 3 }, coreUsers: [], dormantUsers: [] }))
      .toEqual([{ label: '初见', value: 2 }, { label: '常来', value: 3 }])
  })

  it('degrades explicitly when a catalog item has no published version (§9.2 B4-M5)', () => {
    expect(hasPublishedVersion(31)).toBe(true)
    expect(hasPublishedVersion(undefined)).toBe(false)
    expect(hasPublishedVersion(0)).toBe(false)
    expect(catalogVersionCaption(true, 3)).toBe('当前版本 V3')
    expect(catalogVersionCaption(false, undefined)).toBe('当前无生效版本')
    expect(catalogConditionText({ versionId: 31, pointsCost: 300, minimumLevelName: '常来', validityDays: 14 }))
      .toBe('300 积分 · 常来及以上 · 券有效 14 天')
    expect(catalogConditionText({ versionId: undefined })).toBe('尚无已发布的兑换条件')
  })
})
