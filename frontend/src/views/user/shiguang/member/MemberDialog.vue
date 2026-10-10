<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { Close } from '@element-plus/icons-vue'

/**
 * 会员中心原生 <dialog> 壳（冻结参考同款：侧拉面板、Esc 可关、背点可关、关闭后焦点归还触发者）。
 * 一次只开一个；开关闭合由 v-model:open 驱动。
 */
const props = withDefaults(defineProps<{ open: boolean; title: string; clarifier?: string }>(), { clarifier: '' })
const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()

const dialogRef = ref<HTMLDialogElement>()
const titleId = `member-dialog-title-${Math.random().toString(36).slice(2, 8)}`
let opener: Element | null = null

const sync = async (value: boolean) => {
  await nextTick()
  const dialog = dialogRef.value
  if (!dialog) return
  if (value) {
    opener = document.activeElement
    if (!dialog.open) dialog.showModal()
  } else if (dialog.open) {
    dialog.close()
  }
}

watch(() => props.open, sync, { flush: 'post' })
// 允许「挂载即打开」：v-if 延迟挂载的弹层（如充值弹层在拿到 self 前不渲染）
// 首次以 open=true 出现时没有 props 变化，须在挂载后补开。
onMounted(() => {
  if (props.open) void sync(true)
})

const close = () => emit('update:open', false)

const onCancel = (event: Event) => {
  event.preventDefault()
  close()
}

const onBackdrop = (event: MouseEvent) => {
  const dialog = dialogRef.value
  if (!dialog || event.target !== dialog) return
  const rect = dialog.getBoundingClientRect()
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) close()
}

const onClosed = () => {
  if (opener && (opener as HTMLElement).isConnected) (opener as HTMLElement).focus({ preventScroll: true })
  opener = null
  if (props.open) emit('update:open', false)
}
</script>

<template>
  <dialog
    ref="dialogRef"
    class="side-sheet"
    :aria-labelledby="titleId"
    data-test="member-dialog"
    @cancel="onCancel"
    @click="onBackdrop"
    @close="onClosed"
  >
    <header class="dialog-header">
      <div>
        <h2 :id="titleId">{{ title }}</h2>
        <p v-if="clarifier">{{ clarifier }}</p>
      </div>
      <button class="icon-button" type="button" aria-label="关闭弹层" @click="close">
        <el-icon><Close /></el-icon>
      </button>
    </header>
    <div class="dialog-body">
      <slot />
    </div>
    <div v-if="$slots.actions" class="dialog-actions">
      <slot name="actions" />
    </div>
  </dialog>
</template>
