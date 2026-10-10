<template>
  <dl class="evidence-detail sg-focus-in">
    <div v-for="item in items" :key="item.label">
      <dt>{{ item.label }}</dt>
      <dd :class="{ 'is-mono': item.mono }">{{ item.value === '' || item.value === null || item.value === undefined ? '-' : item.value }}</dd>
    </div>
  </dl>
</template>

<script setup lang="ts">
/**
 * 触达/财务证据明细（V3-UI-UX-SHIGUANG S05，预览 §08「expand 统一 EvidenceDetail」）：
 * 支付尝试、服务商调用、通知投递等表格展开行的键值明细统一入口——
 * 发丝线行、等宽值列（mono 可选）、空值统一「-」，对焦进场。
 */
export interface EvidenceDetailItem {
  label: string
  value: string | number | null | undefined
  /** 流水号/版本/哈希等代码值用等宽+tabular */
  mono?: boolean
}

withDefaults(defineProps<{ items: EvidenceDetailItem[] }>(), {})
</script>

<style scoped>
.evidence-detail {
  display: grid;
  gap: 0;
  margin: 0;
}

.evidence-detail div {
  display: grid;
  grid-template-columns: minmax(88px, max-content) minmax(0, 1fr);
  gap: 4px 18px;
  padding: 7px 0;
}

.evidence-detail div + div {
  border-top: 1px solid var(--sg-border-soft);
}

.evidence-detail dt {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
  font-weight: 600;
}

.evidence-detail dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  font-size: var(--sg-fz-sm);
  font-variant-numeric: tabular-nums;
}

.evidence-detail dd.is-mono {
  font-family: var(--sg-font-mono);
  font-size: var(--sg-fz-xs);
  letter-spacing: 0.02em;
}
</style>
