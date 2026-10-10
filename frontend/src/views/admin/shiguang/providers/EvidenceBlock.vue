<template>
  <dl class="data-pairs">
    <div><dt>环境</dt><dd>{{ facts.env }}</dd></div>
    <div><dt>置信度</dt><dd>{{ facts.confidence }}</dd></div>
    <div><dt>观测 / 生成时间</dt><dd>{{ facts.observedAt }}</dd></div>
    <div><dt>过期时间</dt><dd>{{ facts.expiresAt }}</dd></div>
  </dl>
  <div class="strip evidence-meta"><strong>渠道标识</strong><span>{{ facts.providerKey }}</span></div>
  <div class="strip evidence-meta"><strong>配置版本</strong><span>{{ facts.configVersion }}</span></div>
  <div class="strip evidence-meta"><strong>快照编号</strong><span>{{ facts.snapshotNo }}</span></div>
</template>

<script setup lang="ts">
import type { EvidenceFacts } from './providersShared'

defineProps<{ facts: EvidenceFacts }>()
</script>
