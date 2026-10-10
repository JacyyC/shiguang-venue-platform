<template>
  <div ref="root" class="sg-indexrail">
    <!-- 收起态：单行筛选摘要条（默认收起，点开弹出维度索引） -->
    <button
      type="button"
      class="sg-indexrail-toggle"
      :class="{ 'is-open': open }"
      :aria-expanded="open"
      aria-controls="sg-indexrail-body"
      data-test="indexrail-toggle"
      @click="toggle"
    >
      <span class="sg-indexrail-toggle-label">筛选</span>
      <span class="sg-indexrail-toggle-summary" aria-live="polite">{{ summary }}</span>
      <span class="sg-indexrail-acc-caret" aria-hidden="true">▾</span>
    </button>

    <div v-show="open" id="sg-indexrail-body" class="sg-indexrail-body">
      <!-- 桌面 rail：维度列（tablist）+ 活跃维度选项区（radiogroup，垂直居中平铺） -->
      <div v-if="!isMobile" class="sg-indexrail-rail">
        <div class="sg-indexrail-dims" role="tablist" :aria-label="ariaLabel || '筛选维度'">
          <button
            v-for="dimension in dimensions"
            :key="dimension.key"
            type="button"
            role="tab"
            class="sg-indexrail-dim"
            :class="{ on: activeKey === dimension.key }"
            :aria-selected="activeKey === dimension.key"
            :data-test="`indexrail-dim-${dimension.key}`"
            @click="activeKey = dimension.key"
          >{{ dimension.label }}</button>
        </div>
        <template v-for="dimension in dimensions" :key="dimension.key">
          <div
            v-show="activeKey === dimension.key"
            class="sg-indexrail-panel"
            role="radiogroup"
            :aria-label="`按${dimension.label}筛选`"
            @keydown="onKeydown"
          >
            <button
              v-for="option in dimension.options"
              :key="option.value"
              type="button"
              role="radio"
              class="sg-indexrail-opt"
              :class="{ on: selection[dimension.key] === option.value }"
              :aria-checked="selection[dimension.key] === option.value"
              :data-test="`indexrail-opt-${dimension.key}-${option.value || 'all'}`"
              @click="choose(dimension.key, option.value)"
            >{{ option.label }}</button>
            <div v-if="activeKey === dimension.key && slots[`panel-${dimension.key}`]" class="sg-indexrail-extra">
              <slot :name="`panel-${dimension.key}`" />
            </div>
          </div>
        </template>
      </div>

      <!-- 移动手风琴（≤640px）：维度行 = 维度名 + 当前值摘要 + 箭头，互斥展开 -->
      <div v-else class="sg-indexrail-acc" :aria-label="ariaLabel || '筛选维度'" @keydown="onKeydown">
        <template v-for="(dimension, index) in dimensions" :key="dimension.key">
          <button
            type="button"
            class="sg-indexrail-acc-dim"
            :class="{ 'is-first': index === 0 }"
            :aria-expanded="expandedKey === dimension.key"
            :data-test="`indexrail-dim-${dimension.key}`"
            @click="toggleAcc(dimension.key)"
          >
            <span class="sg-indexrail-acc-nm">{{ dimension.label }}</span>
            <span class="sg-indexrail-acc-val">{{ currentLabel(dimension) }}</span>
            <span class="sg-indexrail-acc-caret" aria-hidden="true">▾</span>
          </button>
          <div
            v-show="expandedKey === dimension.key"
            class="sg-indexrail-acc-panel"
            role="radiogroup"
            :aria-label="`按${dimension.label}筛选`"
          >
            <button
              v-for="option in dimension.options"
              :key="option.value"
              type="button"
              role="radio"
              class="sg-indexrail-acc-opt"
              :class="{ on: selection[dimension.key] === option.value }"
              :aria-checked="selection[dimension.key] === option.value"
              :data-test="`indexrail-opt-${dimension.key}-${option.value || 'all'}`"
              @click="choose(dimension.key, option.value)"
            >{{ option.label }}</button>
            <div v-if="expandedKey === dimension.key && slots[`panel-${dimension.key}`]" class="sg-indexrail-extra">
              <slot :name="`panel-${dimension.key}`" />
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useSlots, watch } from 'vue'

export interface SgFilterIndexOption {
  value: string
  label: string
}

export interface SgFilterIndexDimension {
  key: string
  label: string
  options: SgFilterIndexOption[]
}

const props = defineProps<{
  dimensions: SgFilterIndexDimension[]
  selection: Record<string, string>
  ariaLabel?: string
}>()

const emit = defineEmits<{
  (e: 'update:selection', value: Record<string, string>): void
}>()

const slots = useSlots()

/* 形态切换（spec §1）：matchMedia 640 对齐全站移动断点；环境无 matchMedia 时按桌面 rail。 */
const isMobile = ref(false)
let mobileQuery: MediaQueryList | null = null
const onMobileChange = (event: MediaQueryListEvent) => {
  isMobile.value = event.matches
}
onMounted(() => {
  if (typeof window.matchMedia === 'function') {
    mobileQuery = window.matchMedia('(max-width: 640px)')
    isMobile.value = mobileQuery.matches
    mobileQuery.addEventListener('change', onMobileChange)
  }
  document.addEventListener('keydown', onDocKeydown)
  document.addEventListener('click', onDocClick)
})
onBeforeUnmount(() => {
  mobileQuery?.removeEventListener('change', onMobileChange)
  mobileQuery = null
  document.removeEventListener('keydown', onDocKeydown)
  document.removeEventListener('click', onDocClick)
})

const activeKey = ref(props.dimensions[0]?.key ?? '')
watch(
  () => props.dimensions.map((dimension) => dimension.key).join('|'),
  () => {
    if (!props.dimensions.some((dimension) => dimension.key === activeKey.value)) {
      activeKey.value = props.dimensions[0]?.key ?? ''
    }
  }
)

/* 收起/弹出（用户定稿：不需要时最小化成一行摘要，点开再弹选项）。初始已带非默认筛选时直接展开。 */
const root = ref<HTMLElement | null>(null)
const open = ref(props.dimensions.some((dimension) => props.selection[dimension.key]))
const toggle = () => {
  open.value = !open.value
}
const close = () => {
  open.value = false
}
const onDocKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape' && open.value) close()
}
const onDocClick = (event: MouseEvent) => {
  if (!open.value) return
  const target = event.target as Node | null
  if (root.value && target && !root.value.contains(target)) close()
}

const summary = computed(() => {
  const parts = props.dimensions
    .map((dimension) => {
      const value = props.selection[dimension.key]
      if (!value) return null
      return dimension.options.find((option) => option.value === value)?.label ?? null
    })
    .filter((label): label is string => Boolean(label))
  return parts.length ? parts.join(' · ') : '全部'
})

const expandedKey = ref<string | null>(null)
const toggleAcc = (key: string) => {
  expandedKey.value = expandedKey.value === key ? null : key
}

const choose = (key: string, value: string) => {
  emit('update:selection', { ...props.selection, [key]: value })
}

const currentLabel = (dimension: SgFilterIndexDimension) => {
  const current = props.selection[dimension.key]
  return dimension.options.find((option) => option.value === current)?.label ?? dimension.options[0]?.label ?? dimension.label
}

/* 键盘（spec §3）：方向键在当前 radiogroup 内循环换选（焦点随选）；Enter/空格显式激活，
   与原生按钮语义一致且单选下幂等——规避合成键盘事件不触发原生 click 激活的环境差异。 */
const onKeydown = (event: KeyboardEvent) => {
  const target = event.target as HTMLElement | null
  if (!target || !target.closest) return
  if (event.key === 'Enter' || event.key === ' ') {
    const activator = target.closest<HTMLButtonElement>('.sg-indexrail-opt, .sg-indexrail-dim, .sg-indexrail-acc-opt, .sg-indexrail-acc-dim')
    if (activator) {
      event.preventDefault()
      activator.click()
    }
    return
  }
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
  const option = target.closest<HTMLElement>('.sg-indexrail-opt, .sg-indexrail-acc-opt')
  if (!option) return
  const group = option.parentElement
  if (!group) return
  const options = Array.from(group.querySelectorAll<HTMLElement>('.sg-indexrail-opt, .sg-indexrail-acc-opt'))
  const index = options.indexOf(option)
  const step = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : -1
  const next = options[(index + step + options.length) % options.length]
  next.focus()
  next.click()
  event.preventDefault()
}
</script>
