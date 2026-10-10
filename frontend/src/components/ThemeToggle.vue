<template>
  <button
    type="button"
    class="theme-toggle"
    :aria-pressed="theme.isDark"
    :aria-label="theme.isDark ? '切换到晨纸主题' : '切换到灯下主题'"
    :title="theme.isDark ? '切换到晨纸主题' : '切换到灯下主题'"
    @click="theme.toggle()"
  >
    <el-icon aria-hidden="true">
      <Sunny v-if="theme.isDark" />
      <Moon v-else />
    </el-icon>
    <span class="theme-toggle-label">{{ theme.isDark ? '晨纸' : '灯下' }}</span>
  </button>
</template>

<script setup lang="ts">
import { Moon, Sunny } from '@element-plus/icons-vue'
import { useThemeStore } from '@/stores/theme'

const theme = useThemeStore()
</script>

<style scoped>
.theme-toggle {
  min-width: 44px;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 12px;
  border: 1px solid var(--sg-border-strong);
  border-radius: var(--sg-radius-card);
  background: var(--sg-surface);
  color: var(--sg-ink);
  font-size: var(--sg-fz-sm);
  transition: border-color var(--sg-motion-fast) ease-out, color var(--sg-motion-fast) ease-out;
}

.theme-toggle:hover {
  border-color: var(--sg-primary);
  color: var(--sg-primary);
}

.theme-toggle:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: 2px;
}

@media (max-width: 640px) {
  .theme-toggle-label {
    display: none;
  }

  .theme-toggle {
    padding: 0;
    width: 44px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .theme-toggle {
    transition: none;
  }
}
</style>
