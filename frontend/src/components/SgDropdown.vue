<template>
  <span ref="rootRef" class="sg-dd" :class="[`sg-dd--${variant}`, { 'is-open': open }]">
    <button
      ref="toggleRef"
      type="button"
      class="sg-dd-toggle"
      :class="{ 'sg-chip': variant === 'chip' }"
      :disabled="disabled"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :aria-label="ariaLabel"
      @click="toggle"
    >
      <span class="sg-dd-label" :class="{ 'is-placeholder': !selected }">{{ displayLabel }}</span>
      <span class="sg-dd-caret" aria-hidden="true">▾</span>
    </button>
    <Transition name="sg-pop">
      <div v-show="open" class="sg-dd-panel" role="listbox" :aria-label="ariaLabel">
        <button
          v-for="option in options"
          :key="option.value"
          type="button"
          class="sg-dd-opt"
          role="option"
          :class="{ 'is-on': option.value === modelValue }"
          :aria-selected="option.value === modelValue"
          @click="select(option)"
        >{{ option.label }}</button>
        <p v-if="!options.length" class="sg-dd-empty">暂无可选项</p>
      </div>
    </Transition>
  </span>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'

export interface SgDropdownOption {
  value: string | number
  label: string
  /** 触发器回写用的短标签：提供时剥离「（默认）」类后缀只显示它，面板内仍展示完整 label。 */
  dataLabel?: string
}

const props = withDefaults(defineProps<{
  modelValue?: string | number
  options?: SgDropdownOption[]
  placeholder?: string
  variant?: 'chip' | 'block'
  disabled?: boolean
  ariaLabel?: string
}>(), {
  modelValue: undefined,
  options: () => [],
  placeholder: '请选择',
  variant: 'chip',
  disabled: false,
  ariaLabel: undefined
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | number): void
  (e: 'change', value: string | number): void
}>()

const rootRef = ref<HTMLElement>()
const toggleRef = ref<HTMLButtonElement>()
const open = ref(false)

const selected = computed(() => props.options.find((option) => option.value === props.modelValue))
const displayLabel = computed(() => {
  if (!selected.value) return props.placeholder
  return selected.value.dataLabel ?? selected.value.label
})

const onDocumentPointerDown = (event: MouseEvent) => {
  if (open.value && rootRef.value && !rootRef.value.contains(event.target as Node)) close()
}

const onDocumentKeyDown = (event: KeyboardEvent) => {
  if (!open.value || event.key !== 'Escape') return
  event.stopPropagation()
  close()
  toggleRef.value?.focus()
}

const openPanel = () => {
  open.value = true
  document.addEventListener('pointerdown', onDocumentPointerDown, true)
  document.addEventListener('keydown', onDocumentKeyDown, true)
}

const close = () => {
  open.value = false
  document.removeEventListener('pointerdown', onDocumentPointerDown, true)
  document.removeEventListener('keydown', onDocumentKeyDown, true)
}

const toggle = () => {
  if (props.disabled) return
  open.value ? close() : openPanel()
}

const select = (option: SgDropdownOption) => {
  emit('update:modelValue', option.value)
  emit('change', option.value)
  close()
  toggleRef.value?.focus()
}

onBeforeUnmount(close)

defineExpose({ close })
</script>

<style scoped>
.sg-dd {
  position: relative;
  /* 根元素持有正层级：浮层在父层叠上下文内稳定压过普通流内容。
     60 = 全站下拉面板层（main.css「全站层级表」），压得住页内内容、压不过页头 90。
     注意：若祖先被 fill both 的入场动画变成 z-auto 永久层叠上下文，
     这里的 z-index 会被困住——main.css 的 sg-rise 一族因此用 backwards 收尾。 */
  z-index: 60;
  display: inline-flex;
  max-width: 100%;
}

.sg-dd--block {
  display: flex;
  width: 100%;
}

.sg-dd-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 0;
  border: 0;
  background: none;
  font: inherit;
  cursor: pointer;
}

.sg-dd-toggle:disabled {
  cursor: not-allowed;
  color: var(--sg-ink-subtle);
}

.sg-dd-toggle:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: 2px;
}

/* V3 填充式触发器（block 款）：无描边、6% 墨底、聚焦/展开 2px 柿橙 ring（S02） */
.sg-dd--block .sg-dd-toggle {
  width: 100%;
  justify-content: space-between;
  min-height: 37px;
  padding: 0 12px;
  border-radius: var(--sg-radius-control);
  background: color-mix(in srgb, var(--sg-ink) 6%, transparent);
  color: var(--sg-ink);
  font-size: var(--sg-fz-sm);
  font-weight: 500;
  transition: background-color var(--sg-motion-fast) var(--sg-ease-standard), box-shadow var(--sg-motion-fast) var(--sg-ease-standard);
}

.sg-dd--block .sg-dd-toggle:hover {
  background: color-mix(in srgb, var(--sg-ink) 9%, transparent);
}

.sg-dd--block .sg-dd-toggle:focus-visible {
  outline: none;
}

.sg-dd--block .sg-dd-toggle:focus-visible,
.sg-dd.is-open .sg-dd-toggle {
  box-shadow: 0 0 0 2px var(--sg-ring);
}

.sg-dd-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sg-dd-label.is-placeholder {
  color: var(--sg-ink-subtle);
}

/* caret 弹簧翻转（预览 F16）：展开 180° 过冲回弹 */
.sg-dd-caret {
  flex: none;
  margin-left: 2px;
  translate: 0 -1px;
  color: var(--sg-ink-subtle);
  font-size: 10px;
  transition: transform var(--sg-motion-base) var(--sg-ease-spring);
}

.sg-dd.is-open .sg-dd-caret {
  transform: rotate(180deg);
}

.sg-dd-panel {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 60;
  min-width: 172px;
  padding: 6px;
  border: 1px solid var(--sg-border);
  border-radius: var(--sg-radius-card);
  background: var(--sg-surface);
  box-shadow: var(--sg-shadow-2);
}

.sg-dd--block .sg-dd-panel {
  right: 0;
}

.sg-dd-opt {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--sg-radius-control);
  background: none;
  color: var(--sg-ink-muted);
  font: inherit;
  font-size: var(--sg-fz-sm);
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
}

.sg-dd-opt:hover {
  background: color-mix(in srgb, var(--sg-primary) 7%, transparent);
  color: var(--sg-primary);
}

.sg-dd-opt.is-on {
  color: var(--sg-primary);
  font-weight: 600;
}

.sg-dd-opt.is-on::before {
  content: '✓';
  flex: none;
  font-size: var(--sg-fz-xs);
}

.sg-dd-empty {
  margin: 4px 2px;
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-sm);
}
</style>
