<template>
  <section class="filter-bar admin-filter-bar" :aria-label="label">
    <slot />
    <div v-if="$slots.actions" class="admin-filter-actions">
      <slot name="actions" />
    </div>
    <details v-if="$slots.more" class="admin-filter-more" :open="moreOpen" @toggle="moreOpen = ($event.target as HTMLDetailsElement).open">
      <summary>更多条件</summary>
      <div class="admin-filter-more-body">
        <slot name="more" />
      </div>
    </details>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'

/**
 * 管理端筛选条（V3-UI-UX-SHIGUANG S05，预览 §08 管理端总杠杆）：
 * 统一实底卡筛选容器（默认槽=常用条件字段，沿用全局 .filter-bar 网格），
 * `actions` 槽放查询/重置主操作，`more` 槽收二级条件（「常用+更多」两级收敛，
 * 对焦进场与 details 弹簧走全局件）。输入随 S01 全局填充式覆写，本组件不另设输入样式。
 */
withDefaults(defineProps<{
  /** 无障碍名（如「预约筛选」） */
  label: string
}>(), {})

const moreOpen = ref(false)
</script>

<style scoped>
.admin-filter-actions {
  display: flex;
  align-items: flex-end;
  min-height: 54px;
}

</style>
