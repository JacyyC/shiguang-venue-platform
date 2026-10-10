<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { Close } from '@element-plus/icons-vue'

/**
 * 社区原生 <dialog> 壳（冻结参考同款：Esc 可关、背点可关、关闭后焦点归还触发者）。
 * 一次只开一个；开关闭合由 v-model:open 驱动。
 */
const props = defineProps<{ open: boolean; title: string }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()

const dialogRef = ref<HTMLDialogElement>()
const titleId = `community-dialog-title-${Math.random().toString(36).slice(2, 8)}`
let opener: Element | null = null

watch(
  () => props.open,
  async (value) => {
    await nextTick()
    const dialog = dialogRef.value
    if (!dialog) return
    if (value) {
      opener = document.activeElement
      if (!dialog.open) dialog.showModal()
    } else if (dialog.open) {
      dialog.close()
    }
  },
  { flush: 'post' }
)

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
    :aria-labelledby="titleId"
    data-test="community-dialog"
    @cancel="onCancel"
    @click="onBackdrop"
    @close="onClosed"
  >
    <div class="dialog-head">
      <h2 :id="titleId">{{ title }}</h2>
      <button type="button" class="icon-button" aria-label="关闭" title="关闭" @click="close">
        <el-icon><Close /></el-icon>
      </button>
    </div>
    <div class="dialog-body">
      <slot />
    </div>
  </dialog>
</template>
