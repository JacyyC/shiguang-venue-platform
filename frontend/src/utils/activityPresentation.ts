import type {
  ActivityLocation,
  ActivityStatus,
  ActivityType,
  LocationCommitmentStatus
} from '@/api/activity'

export const activityTypeLabel = (value: ActivityType | string) => ({
  STUDY: '学习自习',
  SPORTS: '运动健身',
  BOARD_GAME: '桌游娱乐',
  WORKSHOP: '主题工作坊',
  OTHER: '其他活动'
}[value] || value)

export const activityStatusLabel = (value: ActivityStatus | string) => ({
  DRAFT: '草稿',
  PUBLISHED: '报名中',
  FULL: '名额已满',
  CLOSED: '已关闭',
  CANCELED: '已取消',
  EXPIRED: '已结束'
}[value] || value)

export const activityStatusTagType = (value: ActivityStatus | string) => {
  if (value === 'PUBLISHED') return 'success'
  if (value === 'FULL') return 'warning'
  if (value === 'CANCELED') return 'danger'
  return 'info'
}

/** EP 彩色 tag 清零（R-03.4）：沿用 activityStatusTagType 的类型映射，翻译为 .sg-tag tone 类。 */
export const activityStatusToneClass = (value: ActivityStatus | string) => {
  const type = activityStatusTagType(value)
  return `sg-tag sg-tag--${type === 'info' ? 'neutral' : type}`
}

export const commitmentLabel = (value: LocationCommitmentStatus | string) => ({
  CONFIRMED: '地点已定',
  UNCONFIRMED: '地点待确认',
  DEGRADED: '原预约已失效'
}[value] || value)

export const commitmentTagType = (value: LocationCommitmentStatus | string) => {
  if (value === 'CONFIRMED') return 'info'
  return 'danger'
}

export const formatActivityTime = (start?: string | null, end?: string | null) => {
  if (!start || !end) return '时间待定'
  const startDate = new Date(start)
  const endDate = new Date(end)
  if (Number.isNaN(startDate.getTime()) || Number.isNaN(endDate.getTime())) return `${start} - ${end}`
  const date = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', weekday: 'short' }).format(startDate)
  const time = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
  return `${date} ${time.format(startDate)}-${time.format(endDate)}`
}

const campusAreaLabels: Record<string, string> = {
  NORTH_CAMPUS: '北校区',
  SOUTH_CAMPUS: '南校区',
  MAIN_CAMPUS: '主校区'
}

export const campusAreaLabel = (value?: string | null) => {
  if (!value) return ''
  return campusAreaLabels[value] || value.replaceAll('_', ' ')
}

export const fallbackLocationLabel = (location?: ActivityLocation | null) => {
  if (!location) return '地点待确认'
  if (location.type === 'CAMPUS_AREA') {
    return location.areaCode ? `校区区域：${campusAreaLabel(location.areaCode)}` : '校区区域待确认'
  }
  return '场馆空间待确认'
}

export const matchReasonLabel = (value: string) => ({
  ACTIVITY_TYPE_MATCH: '活动类型一致',
  TIME_WINDOW_OVERLAP: '时间窗口重合',
  LOCATION_EXACT_SPACE: '同一场馆空间',
  LOCATION_SAME_AREA: '同一校区区域',
  CAPACITY_AVAILABLE: '仍有参与名额',
  SAME_LINKED_ACTIVITY: '关联同一活动'
}[value] || '符合当前匹配条件')

export const applicationStatusLabel = (value?: string | null) => ({
  PENDING: '申请待处理',
  ACCEPTED: '申请已接受',
  REJECTED: '申请未通过',
  WITHDRAWN: '申请已撤回'
}[value || ''] || '尚未申请')
