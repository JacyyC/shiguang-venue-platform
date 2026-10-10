<template>
  <section class="empty-state" :class="{ compact }" role="status" aria-live="polite">
    <div class="empty-min">
      <div class="empty-icon-wrap" aria-hidden="true">
        <span class="empty-glyph">{{ glyph }}</span>
      </div>
      <div class="empty-copy">
        <strong>{{ title }}</strong>
        <span>{{ description }}</span>
      </div>
      <div v-if="$slots.action" class="empty-action">
        <slot name="action" />
      </div>
      <nav v-if="$slots.links" class="empty-links" aria-label="空态引导链接">
        <slot name="links" />
      </nav>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  title?: string
  description?: string
  icon?: string
  compact?: boolean
}>(), {
  title: '这里还没有内容',
  description: '稍后再来看看，或调整当前筛选条件。',
  icon: 'DocumentRemove',
  compact: false
})

// 无图内容以首字占位，不引入业务假图。
const glyph = computed(() => (props.title || '空').trim().charAt(0) || '空')
</script>

<style scoped>
/* 极简空态（V4-UI-UX-SHIGUANG S03 零卡底底座 + V5-UI-UX-SHIGUANG S02 字号升档，预览 §02）：
   无卡底、零玻璃、零描边；展示字标题 21px、首字块 58px/26px、说明 15.5px/1.8，
   新增 NN/g 式引导链接槽（#links，1~2 枚胶囊链接）——空态是引导位不是死角落。 */
.empty-state {
  min-height: 168px;
  display: grid;
  place-items: center;
  padding: clamp(30px, 5vw, 52px) 20px;
}

.empty-state.compact {
  min-height: 124px;
  padding: 18px 12px;
}

.empty-min {
  width: min(340px, 100%);
  display: grid;
  place-items: center;
  align-content: center;
  color: var(--sg-ink-muted);
  text-align: center;
}

.empty-state strong,
.empty-state span {
  display: block;
}

.empty-state strong {
  color: var(--sg-ink);
  font-family: var(--sg-font-display);
  font-size: 21px;
  font-weight: 400;
  letter-spacing: 0.02em;
}

.empty-state.compact strong {
  font-size: var(--sg-fz-base);
}

/* 说明档只挂在 .empty-copy 内，不再通配 .empty-state span——
   否则会以更高特异性覆盖 .empty-glyph 首字块字号（V4 既有缺陷，V5 一并修正） */
.empty-copy span {
  margin-top: 8px;
  font-size: 15.5px;
  line-height: 1.8;
}

.empty-state.compact .empty-copy span {
  margin-top: 6px;
  font-size: var(--sg-fz-base);
  line-height: 1.55;
}

/* 展示字首字块：5% 墨底圆角块（预览 .empty-new .mk），不伪造业务图片 */
.empty-icon-wrap {
  width: 58px;
  height: 58px;
  margin-bottom: 16px;
  display: grid;
  place-items: center;
  border-radius: var(--sg-radius-glass);
  background: color-mix(in srgb, var(--sg-ink) 5%, transparent);
  color: var(--sg-ink-subtle);
}

.empty-state.compact .empty-icon-wrap {
  width: 38px;
  height: 38px;
  margin-bottom: 12px;
  border-radius: var(--sg-radius-control);
}

.empty-glyph {
  font-family: var(--sg-font-display);
  font-size: 26px;
  font-weight: 400;
  line-height: 1;
}

.empty-state.compact .empty-glyph {
  font-size: 16px;
}

.empty-copy {
  max-width: 300px;
}

.empty-action {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 14px;
}

/* NN/g 式引导链接槽（预览 .empty-new .links/.lk）：胶囊链接，1~2 枚 */
.empty-links {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.empty-links :deep(a) {
  padding: 9px 16px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--sg-ink) 4%, transparent);
  color: var(--sg-ink-muted);
  font-size: 13.5px;
  text-decoration: none;
  transition: background-color var(--sg-motion-fast) var(--sg-ease-standard), color var(--sg-motion-fast) var(--sg-ease-standard);
}

.empty-links :deep(a:hover) {
  background: color-mix(in srgb, var(--sg-ink) 7%, transparent);
  color: var(--sg-ink);
}

.empty-action :deep(.el-button),
.empty-action :deep(button) {
  border-radius: 999px;
}
</style>
