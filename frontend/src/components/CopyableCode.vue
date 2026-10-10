<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps<{ code: string; full: string; label?: string }>()
const copying = ref(false)

const copyFull = async () => {
  if (copying.value) return
  copying.value = true
  try {
    await navigator.clipboard.writeText(props.full)
    ElMessage.success('已复制完整单号')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  } finally {
    copying.value = false
  }
}
</script>

<template>
  <span class="order-code">
    <code class="sg-mono sg-num">{{ code }}</code>
    <button type="button" class="order-code-copy" :disabled="copying" :aria-label="`复制完整单号 ${label || full}`" @click="copyFull">复制</button>
  </span>
</template>

<style scoped>
.order-code {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.order-code code {
  padding: 1px 6px;
  border-radius: var(--sg-radius-stamp);
  background: var(--sg-surface-subtle);
  font-size: var(--sg-fz-xs);
  line-height: 1.6;
}

.order-code-copy {
  padding: 0;
  border: 0;
  background: none;
  color: var(--sg-primary);
  font: inherit;
  font-size: var(--sg-fz-xs);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.order-code-copy:disabled {
  color: var(--sg-ink-subtle);
  cursor: wait;
}
</style>
