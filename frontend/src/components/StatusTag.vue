<template>
  <span class="sg-tag" :class="toneClass" :aria-label="`状态：${meta.label}`">{{ meta.label }}</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

type StampTone = 'success' | 'warning' | 'danger' | 'primary' | 'neutral' | 'honey' | 'mist' | 'pine' | 'unknown'

const props = defineProps<{
  value: string
  kind?: 'reservation' | 'order' | 'refund' | 'slot' | 'provider' | 'reconciliation' | 'stored-value'
    | 'community-group-role' | 'community-post-status'
    | 'advertising-lifecycle' | 'advertising-advertiser'
}>()

const UNKNOWN_META: { label: string; tone: StampTone } = { label: '暂无法识别的状态', tone: 'unknown' }

const maps: Record<string, Record<string, { label: string; tone: StampTone }>> = {
  reservation: {
    PENDING_REVIEW: { label: '待审核', tone: 'warning' },
    APPROVED: { label: '已通过', tone: 'primary' },
    PENDING_PAYMENT: { label: '待支付', tone: 'warning' },
    PAID: { label: '已支付', tone: 'success' },
    COMPLETED: { label: '已完成', tone: 'neutral' },
    REJECTED: { label: '已拒绝', tone: 'danger' },
    CANCELED: { label: '已取消', tone: 'neutral' },
    EXPIRED: { label: '已过期', tone: 'neutral' },
    REFUNDING: { label: '退款中', tone: 'warning' },
    REFUNDED: { label: '已退款', tone: 'neutral' }
  },
  order: {
    PENDING_PAYMENT: { label: '待支付', tone: 'warning' },
    PAYING: { label: '支付中', tone: 'primary' },
    PAID: { label: '已支付', tone: 'success' },
    CLOSED: { label: '已关闭', tone: 'neutral' },
    TIMEOUT: { label: '已超时', tone: 'neutral' },
    PAY_FAILED: { label: '支付失败', tone: 'danger' },
    REFUNDING: { label: '退款中', tone: 'warning' },
    REFUNDED: { label: '已退款', tone: 'neutral' },
    PARTIAL_REFUNDED: { label: '部分退款', tone: 'warning' }
  },
  refund: {
    PENDING_REVIEW: { label: '待审核', tone: 'warning' },
    REJECTED: { label: '已拒绝', tone: 'danger' },
    REFUNDING: { label: '退款中', tone: 'warning' },
    SUCCESS: { label: '退款成功', tone: 'success' },
    FAILED: { label: '退款失败', tone: 'danger' }
  },
  slot: {
    AVAILABLE: { label: '可预约', tone: 'success' },
    SELECTED: { label: '已选择', tone: 'primary' },
    LOCKED: { label: '临时锁定', tone: 'neutral' },
    RESERVED: { label: '已预约', tone: 'neutral' },
    PENDING_REVIEW: { label: '待审核', tone: 'warning' },
    IN_PROGRESS: { label: '进行中', tone: 'honey' },
    UNAVAILABLE: { label: '不可售', tone: 'danger' },
    MANUAL_HOLD: { label: '人工保留', tone: 'warning' },
    BUFFER: { label: '缓冲', tone: 'neutral' },
    MAINTENANCE: { label: '维护中', tone: 'warning' },
    CLOSED: { label: '已关闭', tone: 'neutral' },
    CONFLICT: { label: '冲突', tone: 'danger' }
  },
  provider: {
    SUCCESS: { label: '正常', tone: 'success' },
    HEALTHY: { label: '正常', tone: 'success' },
    ACTIVE: { label: '正常', tone: 'success' },
    OK: { label: '正常', tone: 'success' },
    DEV: { label: '开发环境', tone: 'primary' },
    ENABLED: { label: '已启用', tone: 'success' },
    FAILED: { label: '失败', tone: 'danger' },
    TIMEOUT: { label: '超时', tone: 'danger' },
    DEGRADED: { label: '已降级', tone: 'warning' },
    SKIPPED: { label: '已跳过', tone: 'neutral' },
    FRESH: { label: '新鲜', tone: 'success' },
    STALE: { label: '已过期', tone: 'warning' },
    EXPIRED: { label: '已过期', tone: 'danger' },
    DISABLED: { label: '已停用', tone: 'neutral' },
    WARNING: { label: '需关注', tone: 'warning' },
    CRITICAL: { label: '严重', tone: 'danger' },
    NONE: { label: '无告警', tone: 'neutral' },
    LOW: { label: '低', tone: 'neutral' },
    MEDIUM: { label: '中', tone: 'warning' },
    HIGH: { label: '高', tone: 'danger' }
  },
  reconciliation: {
    MATCHED: { label: '匹配', tone: 'success' },
    DIFFERENCE: { label: '差异', tone: 'danger' },
    MISSING_PROVIDER: { label: '缺渠道', tone: 'warning' },
    MISSING_SYSTEM: { label: '缺系统', tone: 'warning' },
    ADJUSTED: { label: '已调整', tone: 'primary' },
    OPEN: { label: '待复核', tone: 'warning' },
    UNDER_REVIEW: { label: '复核中', tone: 'primary' },
    RESOLVED: { label: '已解决', tone: 'success' },
    COMPLETED: { label: '已完成', tone: 'success' },
    FAILED: { label: '失败', tone: 'danger' }
  },
  'stored-value': {
    ENABLED: { label: '已启用', tone: 'success' },
    DISABLED: { label: '已停用', tone: 'neutral' },
    NORMAL: { label: '正常', tone: 'success' },
    FROZEN: { label: '已冻结', tone: 'warning' },
    CLOSING: { label: '注销中', tone: 'warning' },
    CLOSED: { label: '已注销', tone: 'neutral' },
    SUCCEEDED: { label: '已成功', tone: 'success' },
    FAILED: { label: '失败', tone: 'danger' },
    REFUNDING: { label: '退款中', tone: 'warning' },
    REFUNDED: { label: '已退款', tone: 'neutral' },
    PROVIDER_SUCCEEDED_PENDING_POSTING: { label: '待入账', tone: 'warning' },
    POSTING: { label: '入账中', tone: 'primary' },
    PENDING: { label: '待处理', tone: 'warning' },
    APPROVED: { label: '已批准', tone: 'success' },
    REJECTED: { label: '已驳回', tone: 'danger' },
    EXECUTED: { label: '已执行', tone: 'success' },
    OPEN: { label: '待复核', tone: 'warning' },
    RESOLVED: { label: '已解决', tone: 'success' }
  },
  'community-group-role': {
    OWNER: { label: '组长', tone: 'honey' },
    ADMIN: { label: '管理员', tone: 'mist' },
    MEMBER: { label: '活跃成员', tone: 'pine' }
  },
  'community-post-status': {
    PUBLISHED: { label: '已发布', tone: 'success' },
    HIDDEN: { label: '已隐藏', tone: 'neutral' },
    DELETED: { label: '已删除', tone: 'neutral' },
    PURGED: { label: '已清除', tone: 'neutral' }
  },
  'advertising-lifecycle': {
    DRAFT: { label: '草稿', tone: 'neutral' },
    PENDING_REVIEW: { label: '待审核', tone: 'warning' },
    REJECTED: { label: '已驳回', tone: 'danger' },
    APPROVED: { label: '已批准', tone: 'primary' },
    PUBLISHED: { label: '已发布', tone: 'success' },
    PAUSED: { label: '已暂停', tone: 'mist' },
    RETIRED: { label: '已停用', tone: 'neutral' }
  },
  'advertising-advertiser': {
    ACTIVE: { label: '合作中', tone: 'success' },
    SUSPENDED: { label: '已暂停', tone: 'mist' },
    RETIRED: { label: '已停用', tone: 'neutral' }
  }
}

// 未知枚举进入中文兜底，不回显原始技术值。
const meta = computed(() => maps[props.kind || 'reservation']?.[props.value] || UNKNOWN_META)

// 视觉委托 main.css .sg-tag 语义章全族（V3-UI-UX-SHIGUANG S02 收敛，组件只留 tone 映射）；
// 2px 圆角章/圆点/行高特征由全局族持有，unknown 为实底兜底章。
const toneClass = computed(() =>
  meta.value.tone === 'unknown' ? 'sg-tag--unknown' : `sg-tag--${meta.value.tone}`
)
</script>
