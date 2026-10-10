<template>
  <div class="slot-grid sg-stagger sg-stagger--dense" role="group" :aria-label="ariaLabel || '可预约时段'">
    <button
      v-for="slot in slots"
      :key="`${slot.spaceId}-${slot.startTime}`"
      type="button"
      :data-key="`${slot.spaceId}|${slot.startTime}`"
      class="slot sg-pick-block"
      :class="[slotClass(slot), isSelected(slot) ? 'is-on' : '']"
      :disabled="slot.status !== 'AVAILABLE' || slotSemantic(slot) === 'expired' || slotSemantic(slot) === 'inprogress'"
      :aria-pressed="isSelected(slot)"
      :aria-label="slotAriaLabel(slot)"
      @click="$emit('toggle', slot)"
    >
      <span class="slot-time">{{ timeLabel(slot.startTime) }} - {{ timeLabel(slot.endTime) }}</span>
      <span class="slot-state">
        <span v-if="slotSemantic(slot) === 'expired'" class="sg-tag sg-tag--neutral">已过期</span>
        <StatusTag v-else-if="slotSemantic(slot) === 'inprogress'" value="IN_PROGRESS" kind="slot" />
        <StatusTag v-else :value="isSelected(slot) ? 'SELECTED' : slot.status" kind="slot" />
      </span>
      <span v-if="slot.reason && detailVisible(slot)" class="slot-reason">{{ slot.reason }}</span>
      <span v-if="showPrice !== false && detailVisible(slot)" class="slot-price">{{ formatPrice(slot.price) }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import StatusTag from './StatusTag.vue'
import type { AvailabilitySlot } from '@/api/types'

const props = withDefaults(defineProps<{
  slots: AvailabilitySlot[]
  selected?: AvailabilitySlot[]
  ariaLabel?: string
  showPrice?: boolean
}>(), {
  // 显式默认 true：规避 Vue 对 boolean prop 缺省置 false 的转换，维持“未传即显示价格”的合同。
  showPrice: true
})

defineEmits<{
  toggle: [slot: AvailabilitySlot]
}>()

const timeLabel = (value: string) => value.match(/(?:T|^)(\d{2}:\d{2})/)?.[1] || value
const isSelected = (slot: AvailabilitySlot) =>
  props.selected?.some((item) => item.startTime === slot.startTime && item.spaceId === slot.spaceId) || false
type SlotSemantic = 'selected' | 'inprogress' | 'expired' | 'normal'
/** N2 单一语义（R-08）：凡后端 reasonCode=PAST_TIME——结束前=进行中（蜜金）、结束后=已过期（灰），
 *  均不显示价格与原因；后端字段缺失时才按结束时间兜底自判过期。选中态豁免一切过期/进行中语义。 */
const slotSemantic = (slot: AvailabilitySlot): SlotSemantic => {
  if (isSelected(slot)) return 'selected'
  const end = Date.parse(slot.endTime)
  const ended = Number.isFinite(end) && end <= Date.now()
  if (slot.reasonCode === 'PAST_TIME') return ended ? 'expired' : 'inprogress'
  return ended ? 'expired' : 'normal'
}
const detailVisible = (slot: AvailabilitySlot) => {
  const semantic = slotSemantic(slot)
  return semantic === 'normal' || semantic === 'selected'
}
const slotClass = (slot: AvailabilitySlot) => {
  const semantic = slotSemantic(slot)
  if (semantic === 'selected') return 'selected'
  if (semantic === 'expired') return 'past'
  if (semantic === 'inprogress') return 'ongoing'
  return slot.status.toLowerCase()
}
const formatPrice = (value: number) => `￥${Number(value || 0).toFixed(2)}`
const slotAriaLabel = (slot: AvailabilitySlot) => {
  const semantic = slotSemantic(slot)
  const selected = semantic === 'selected' ? '，已选择' : ''
  const reason = slot.reason && detailVisible(slot) ? `，${slot.reason}` : ''
  const price = props.showPrice !== false && detailVisible(slot) ? `，${formatPrice(slot.price)}` : ''
  const stateText = semantic === 'expired' ? '已过期' : semantic === 'inprogress' ? '进行中' : slot.label || slot.status
  return `${timeLabel(slot.startTime)} 至 ${timeLabel(slot.endTime)}，${stateText}${price}${reason}${selected}`
}
</script>

<style scoped>
.slot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(176px, 1fr));
  gap: 10px;
}

/* 可选块语言（V3 S02，预览 §08 TimeSlotGrid 处方）：基础取自全局 .sg-pick-block
   （6% 墨底、控件档圆角、弹簧位移），此处只保留格内布局与语义态；交互合同零变更。 */
.slot {
  position: relative;
  min-height: 112px;
  padding: 12px;
  display: grid;
  gap: 8px;
  align-content: start;
  text-align: left;
}

/* 三态弹簧：hover 抬升、按压回落、选中落章（全局 :active scale .97 由 .sg-pick-block 提供） */
.slot:hover:not(:disabled) {
  transform: translateY(-2px);
  background: color-mix(in srgb, var(--sg-ink) 9%, transparent);
  box-shadow: var(--sg-shadow-1);
}

.slot.selected {
  background: var(--sg-primary-soft);
  box-shadow: 0 0 0 2px var(--sg-ring);
}

.slot.selected:hover:not(:disabled) {
  background: var(--sg-primary-soft);
}

.slot.past {
  background: var(--sg-surface-subtle);
}

/* 进行中（N2 单一语义，预览 §03 is-now）：蜜金软底、时间不划线；格子尺寸/布局不变。 */
.slot.ongoing,
.slot.ongoing:disabled {
  background: var(--sg-warning-soft);
  opacity: 1;
}

.slot.ongoing:disabled .slot-time {
  color: var(--sg-warning);
  text-decoration: none;
}

/* 进入详情页自动定位首个可约槽的一次性高亮（由父页在加载后添加/移除）。 */
.slot.first-bookable-flash {
  animation: sg-slot-flash 1.6s ease-out both;
}

@keyframes sg-slot-flash {
  0% {
    box-shadow: 0 0 0 5px color-mix(in srgb, var(--sg-primary) 30%, transparent);
  }

  100% {
    box-shadow: 0 0 0 0 color-mix(in srgb, var(--sg-primary) 0%, transparent);
  }
}

.slot.selected .slot-time {
  color: var(--sg-primary);
}

.slot:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--sg-ring);
}

.slot:disabled {
  cursor: not-allowed;
  color: var(--sg-ink-muted);
  background: var(--sg-surface-subtle);
  opacity: 0.55;
}

.slot:disabled .slot-time {
  text-decoration: line-through;
}

.slot:disabled .slot-reason {
  text-decoration: none;
}

.slot-time {
  color: var(--sg-ink);
  font-size: var(--sg-fz-base);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.slot-state {
  min-height: 24px;
}

.slot-price {
  color: var(--sg-ink-muted);
  font-variant-numeric: tabular-nums;
}

.slot-reason {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
  line-height: 1.4;
}

@media (max-width: 640px) {
  .slot-grid {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .slot {
    transition: none;
    transform: none;
  }
}
</style>
