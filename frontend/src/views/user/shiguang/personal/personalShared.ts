import type { Message } from '@/api/types'

/**
 * 个人面共享展示层（SHIGUANG-BATCH-01-REFACTOR-20260913 S05）。
 * 文案与格式逐字对齐冻结参考 reference/preview/d/personal.js；消息类型标签与跳转
 * 按合同矩阵 §2.2 的真实 type 全集映射（businessId 语义按类型区分）。
 */

export const PERSONAL_SECTIONS = [
  ['overview', '个人概览'],
  ['messages', '消息中心'],
  ['feedback', '我的反馈'],
  ['favorites', '收藏场馆'],
  ['settings', '账号设置']
] as const

export type PersonalSection = (typeof PERSONAL_SECTIONS)[number][0]

/** 消息类型 → 预览 pill 文案（预约/退款/客服/公告/权益/系统消息）。 */
export function messageTypePill(type: string | null | undefined): string {
  const value = type || ''
  if (value.startsWith('RESERVATION')) return '预约'
  if (value.startsWith('REFUND')) return '退款'
  if (value.startsWith('CUSTOMER_SERVICE')) return '客服'
  if (value.startsWith('NOTICE')) return '公告'
  if (value.startsWith('COUPON') || value.startsWith('MEMBER')) return '权益'
  if (value.startsWith('PAYMENT') || value.startsWith('ORDER')) return '支付'
  return '系统消息'
}

export interface MessageTarget {
  label: string
  route: string
}

/** 消息来源跳转（G-T2/G-P5）：businessId 语义按类型区分，定位不到时不带焦点。 */
export function messageTarget(message: Pick<Message, 'type' | 'businessId'>): MessageTarget | null {
  const type = message.type || ''
  const id = message.businessId
  if (type.startsWith('RESERVATION')) {
    return { label: '查看预约 →', route: id != null ? `/me/reservations#booking-${id}` : '/me/reservations' }
  }
  if (type.startsWith('PAYMENT') || type.startsWith('ORDER')) {
    return { label: '查看订单 →', route: '/me/orders' }
  }
  if (type.startsWith('REFUND')) {
    return { label: '查看退款 →', route: '/me/orders' }
  }
  if (type.startsWith('CUSTOMER_SERVICE')) {
    return { label: '查看本人反馈 →', route: id != null ? `/me/feedback?case=${id}` : '/me/feedback' }
  }
  if (type.startsWith('COUPON')) return { label: '查看券夹 →', route: '/me/coupons' }
  if (type.startsWith('MEMBER')) return { label: '查看会员中心 →', route: '/me/membership' }
  return null
}

/** 投诉/工单的用户可见状态标签（预览风格：待处理/待你补充 attention / 处理中 / 已解决 / 已取消）。 */
export function caseStatusLabel(status: string | null | undefined): string {
  const value = (status || '').toUpperCase()
  if (['OPEN', 'SUBMITTED', 'PENDING', 'TRIAGED', 'REVIEW_REQUIRED', 'REOPENED'].includes(value)) return '待处理'
  if (value === 'WAITING_USER') return '待你补充'
  if (['PROCESSING', 'IN_PROGRESS', 'ASSIGNED'].includes(value)) return '处理中'
  if (['RESOLVED', 'CLOSED'].includes(value)) return '已解决'
  if (value === 'CANCELED') return '已取消'
  return value || '待处理'
}

export function caseStatusAttention(status: string | null | undefined): boolean {
  const label = caseStatusLabel(status)
  return label === '待处理' || label === '待你补充'
}

/** 评价的用户可见状态标签（待审核/已公开/未通过）。 */
export function reviewStatusLabel(status: string | null | undefined): string {
  const value = (status || '').toUpperCase()
  if (['PENDING_REVIEW', 'PENDING', 'SUBMITTED'].includes(value)) return '待审核'
  if (['PUBLISHED', 'APPROVED', 'VISIBLE'].includes(value)) return '已公开'
  if (['REJECTED', 'HIDDEN', 'FLAGGED'].includes(value)) return '未通过'
  return value || '待审核'
}

/** 投诉问题类型选项（预览 feedback-new 弹层全集，中文为展示文案）。 */
export const COMPLAINT_TYPES = ['设备', '服务态度', '订单支付', '通知信息', '卫生', '现场', '其他']
export const COMPLAINT_SEVERITIES = ['中', '低', '高']
export const RATING_OPTIONS = [5, 4, 3, 2, 1]

/** 展示文案 → 服务端枚举（CustomerFeedbackService 校验集）。 */
const COMPLAINT_TYPE_ENUMS: Record<string, string> = {
  设备: 'EQUIPMENT',
  服务态度: 'SERVICE_ATTITUDE',
  订单支付: 'ORDER_PAYMENT',
  通知信息: 'NOTICE_INFO',
  卫生: 'CLEANLINESS',
  现场: 'SITE',
  其他: 'OTHER'
}
const COMPLAINT_SEVERITY_ENUMS: Record<string, string> = { 高: 'HIGH', 中: 'MEDIUM', 低: 'LOW' }

export function complaintTypeEnum(label: string): string {
  return COMPLAINT_TYPE_ENUMS[label] ?? 'OTHER'
}

export function complaintSeverityEnum(label: string): string {
  return COMPLAINT_SEVERITY_ENUMS[label] ?? 'MEDIUM'
}

/** 服务端枚举 → 展示文案（反馈详情内容行用）。 */
export function complaintTypeLabel(enumValue: string | null | undefined): string {
  const entry = Object.entries(COMPLAINT_TYPE_ENUMS).find(([, code]) => code === (enumValue || '').toUpperCase())
  return entry?.[0] || enumValue || ''
}

export function complaintSeverityLabel(enumValue: string | null | undefined): string {
  const entry = Object.entries(COMPLAINT_SEVERITY_ENUMS).find(([, code]) => code === (enumValue || '').toUpperCase())
  return entry?.[0] || enumValue || ''
}

/** 展示入口逐项使用原路由的菜单权限；会员、券和储值互不作为总开关。 */
export const PERSONAL_AREAS = [
  { title: '我的安排', intro: '留给自己的时间，都有去处。', links: [
    { label: '我的时间', to: '/me/time', permission: 'user:view' },
    { label: '预约记录', to: '/me/reservations', permission: 'reservation:my' },
    { label: '订单与退款', to: '/me/orders', permission: 'order:my' },
    { label: '到场凭证', to: '/me/checkins', permission: 'reservation:checkin:me' }
  ] },
  { title: '会员中心', intro: '看看你的等级、权益与账户。', links: [
    { label: '会员等级', to: '/me/membership', permission: 'member:me' },
    { label: '积分权益', to: '/me/membership?tab=rewards', permission: 'member:me' },
    { label: '优惠券', to: '/me/coupons', permission: 'coupon:me' },
    { label: '储值账户', to: '/me/stored-value', permission: 'stored-value:me' }
  ] },
  { title: '我的服务', intro: '消息、喜欢的地方，以及你的反馈。', links: [
    { label: '消息中心', to: '/me/messages', permission: '' },
    { label: '收藏场馆', to: '/me/favorites', permission: 'funnel:favorite:me' },
    { label: '问题反馈', to: '/me/feedback', permission: 'reservation:my' }
  ] },
  { title: '账号设置', intro: '资料与偏好，由你来决定。', links: [
    { label: '个人资料', to: '/me/profile#settings', permission: 'user:view' },
    { label: '密码', to: '/me/profile#settings', permission: 'user:view' },
    { label: '通知与隐私偏好', to: '/me/profile#settings', permission: 'user:view' }
  ] }
] as const
