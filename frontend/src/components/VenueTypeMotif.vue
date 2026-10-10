<script setup lang="ts">
import type { VenueMotif } from '@/utils/venueVisual'

/**
 * 场馆画笔单线图标（V5-UI-UX-SHIGUANG S02，视觉权威 design-preview.html §04 定稿）：
 * 每类空间一枚 32-viewBox 细线图标（stroke 1.4 圆头笔触、fill none、透明度 0.9），
 * 居中于 wash 面，线色由使用方经由 currentColor / wash Token 注入，组件不持有颜色；
 * 尺寸语言由使用方布局持有——卡片 clamp(64→84px)、详情氛围图 clamp(96→140px)。
 * V3/V4 的满铺 pattern 与 tile/hero 两档已随「居中画笔单线图标」定稿整体退役。
 */
defineProps<{ motif: VenueMotif }>()
</script>

<template>
  <svg
    class="sg-motif"
    viewBox="0 0 32 32"
    fill="none"
    stroke="currentColor"
    stroke-width="1.4"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <g opacity="0.9">
      <template v-if="motif === 'sport'">
        <circle cx="16" cy="16" r="9" />
        <path d="M16 4v24M4 16h11M21 16h7" />
      </template>
      <template v-else-if="motif === 'pool'">
        <path d="M4 21c3-3 6-3 9 0s6 3 9 0 4-3 6-1" />
        <path d="M8 12V6m8 6V6m8 6V6" />
      </template>
      <template v-else-if="motif === 'study'">
        <rect x="6" y="7" width="8" height="18" rx="2" />
        <rect x="18" y="7" width="8" height="18" rx="2" />
        <path d="M10 9v14M22 9v14" />
      </template>
      <template v-else>
        <path d="M16 6l10 6v14H6V12z" />
        <path d="M12 26v-7h8v7" />
      </template>
    </g>
  </svg>
</template>

<style scoped>
.sg-motif {
  display: block;
}
</style>
