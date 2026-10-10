<template>
  <div class="admin-expand-panel sg-focus-in">
    <slot />
  </div>
</template>

<script setup lang="ts">
/**
 * 管理端行下探面板（V3-UI-UX-SHIGUANG S05，预览 §08「行内编辑簇改行下探」共享容器）：
 * el-table type="expand" 展开行的统一容器——次级表面、表单行网格、
 * 对焦进场动画。行内簇（状态变更/任务处理/工单动作）整体移入本容器，
 * confirm 流程保持不变。与 AdminTabbedLedger 同为资产/现场运营/客服工单同构件。
 */
withDefaults(defineProps<{ ariaLabel?: string }>(), {})
</script>

<style scoped>
.admin-expand-panel {
  display: grid;
  gap: 10px;
  padding: 12px 14px;
  border-radius: var(--sg-radius-control);
  background: var(--sg-surface-subtle);
}
</style>
