import type { CommunityGroup } from '@/api/community'
import type { ShiguangScene } from '@/components/shiguang/types'
import { formatMonthDayTime, formatDateTimeZh } from '@/utils/datetime'

/**
 * 社区页面共享的视觉派生与标签规则（SHIGUANG-COMMUNITY-REFACTOR-20260911 S04）。
 * 只负责把真实服务端字段映射到冻结参考的呈现位置；不承载业务判定。
 */

/** 小组类别 → 静物场景（视觉派生：STUDY/CAREER 近阅读、SPORTS 近运动、ART 近水、其余相聚；非业务事实）。 */
export const sceneForGroup = (categoryCode?: string | null): ShiguangScene => {
  switch (categoryCode) {
    case 'STUDY':
    case 'CAREER':
      return 'quiet'
    case 'SPORTS':
      return 'move'
    case 'ART':
      return 'water'
    default:
      return 'together'
  }
}

export const visibilityLabel = (visibility?: string | null) =>
  visibility === 'MEMBERS_ONLY' ? '仅成员可见' : '广场可见'

export const joinPolicyLabel = (joinPolicy?: string | null) =>
  joinPolicy === 'DIRECT' ? '直接加入' : '加入需审核'

/** 头像底色按用户 id 稳定取色（对应 .avatar.color-0..3）。 */
export const avatarColorClass = (userId?: number | null) =>
  `color-${Math.abs(userId ?? 0) % 4}`

export const initialOf = (nickname?: string | null) => {
  const trimmed = (nickname ?? '').trim()
  return trimmed ? Array.from(trimmed)[0] : '社'
}

/** 小组卡片/详情的成员状态徽标（预览 groupCard 同款）。 */
export const memberBadgeText = (group: CommunityGroup, viewerId?: number | null) => {
  if (group.currentMemberStatus === 'ACTIVE') return group.ownerUserId === viewerId ? '你创建的' : '已加入'
  if (group.currentMemberStatus === 'PENDING') return '等待审核'
  return group.joinPolicy === 'DIRECT' ? '直接加入' : '需审核'
}

/** 加入按钮文案（预览 joinButton 同款）。 */
export const joinActionText = (group: CommunityGroup) => {
  if (group.currentMemberStatus === 'ACTIVE') return '已加入'
  if (group.currentMemberStatus === 'PENDING') return '等待审核'
  return group.joinPolicy === 'REVIEW_REQUIRED' ? '申请加入' : '加入小组'
}

export const isGroupManager = (group?: CommunityGroup | null) =>
  !!group && group.currentMemberStatus === 'ACTIVE'
  && (group.currentMemberRole === 'OWNER' || group.currentMemberRole === 'ADMIN')

export const isGroupOwner = (group?: CommunityGroup | null) =>
  !!group && group.currentMemberStatus === 'ACTIVE' && group.currentMemberRole === 'OWNER'

/** 帖子摘要：与 echo 服务同口径的扁平化截断。 */
export const excerptOf = (content?: string | null, max = 120) => {
  const flattened = (content ?? '').replace(/\s+/g, ' ').trim()
  return flattened.length <= max ? flattened : `${flattened.slice(0, max)}…`
}

export const listTimeText = (value?: string | null) => formatMonthDayTime(value)
export const detailTimeText = (value?: string | null) => formatDateTimeZh(value)

/** 我的举报状态徽标（OPEN/IN_REVIEW 处理中，RESOLVED 已处理，DISMISSED 已驳回）。 */
export const reportStateText = (status?: string | null) => {
  if (status === 'RESOLVED') return '已处理'
  if (status === 'DISMISSED') return '已驳回'
  return '处理中'
}

export const reportTargetTypeText = (targetType?: string | null) => {
  if (targetType === 'GROUP') return '小组'
  if (targetType === 'COMMENT') return '评论'
  return '帖子'
}

export const memberRoleText = (role?: string | null) => {
  if (role === 'OWNER') return '组主'
  if (role === 'ADMIN') return '管理员'
  return '成员'
}
