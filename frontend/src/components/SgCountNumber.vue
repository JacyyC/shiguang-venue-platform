<template>
  <span ref="elRef" class="sg-count">{{ prefix }}{{ format(displayValue) }}</span>
</template>

<script setup lang="ts">
import { ref, toRef } from 'vue'
import { useCountUp } from '@/composables/useCountUp'

/**
 * KPI 弹簧计数（V3-UI-UX-SHIGUANG S02，预览 §06 杠杆 L7）：
 * 衬线大数字 + tabular-nums 的滚动计数条目——进入视口后缓动走到目标值，
 * reduced-motion 直接落定终值。字体/颜色由外层 KPI 结构（kpi-strip/kpi-card）持有。
 * V5（V5-UI-UX-SHIGUANG S03 预览 §03）：根元素由 strong 改 span——嵌套 strong 曾命中
 * `.kpi-item strong` 后代选择器造成单位换行与行数不一致；现由 `.kpi-item > strong`
 * 直接子选择器 + inline-flex baseline 行承担结构语义。
 */
const props = withDefaults(defineProps<{
  value: number
  /** 小数位（金额 KPI 传 2）；展示层格式化，不改任何金额计算 */
  decimals?: number
  /** 数字前缀（如 ¥），展示层拼装 */
  prefix?: string
  duration?: number
}>(), {
  decimals: 0,
  prefix: '',
  duration: 500
})

const elRef = ref<HTMLElement | null>(null)
const { displayValue } = useCountUp(toRef(props, 'value'), {
  element: elRef,
  decimals: props.decimals,
  duration: props.duration
})

const format = (value: number) => value.toFixed(props.decimals)
</script>

<style scoped>
.sg-count {
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum';
}
</style>
