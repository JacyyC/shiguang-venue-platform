<template>
  <el-tabs :model-value="modelValue" class="admin-tabbed-ledger" @update:model-value="onUpdate">
    <el-tab-pane v-for="tab in tabs" :key="tab.name" :label="tab.label" :name="tab.name">
      <slot :name="tab.name" />
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
/**
 * 管理端页签台账（V3-UI-UX-SHIGUANG S05，预览 §08「tab+表合并同卡」共享件）：
 * 资产/现场运营/日志审计/储值财务等「多页签密表」统一容器——
 * 页签栏并入外层 data-panel 卡（合并同卡、密表页只透卡缝），
 * 每个页签内容经同名插槽注入，页签状态语义与 el-tabs 完全一致。
 */
export interface AdminLedgerTab {
  name: string
  label: string
}

defineProps<{ modelValue: string; tabs: AdminLedgerTab[] }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

const onUpdate = (value: string | number) => {
  emit('update:modelValue', String(value))
}
</script>

<style scoped>
.admin-tabbed-ledger {
  min-width: 0;
}

/* 页签栏并入外层卡：去 EP 重描边，保留下划线选中语义（--el 主色已全局映射柿橙） */
.admin-tabbed-ledger :deep(.el-tabs__header) {
  margin: 0 0 12px;
}

.admin-tabbed-ledger :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--sg-border-soft);
}
</style>
