<template>
  <AmbientBackground />
  <ShiguangSymbols />
  <router-view v-slot="{ Component, route }">
    <transition name="route" mode="out-in">
      <component :is="Component" :key="route.matched[0]?.path || route.fullPath" />
    </transition>
  </router-view>
</template>

<script setup lang="ts">
// 背景层挂载在 router-view 之外：路由级单例，不随页面重挂载（V3-UI-UX-SHIGUANG S01）。
// 路由容器转场回归 `route` CSS 过渡（V3 之前历代验证路径）：VT 容器转场集成时序缺陷
// （更新回调在导航确认前返回 → VT 捕获与路由确认竞争 → 中止后渲染抑制未释放）导致
// SPA 视图卡死（E2E-AUDIT-FIX-20260906 S05 根治，D-02），VT 正确集成留待未来视觉工作项。
import AmbientBackground from '@/components/AmbientBackground.vue'
// 拾光字标与工具符号：全局单实例（0 尺寸 aria-hidden，不影响旧页），供拾光视图 <use> 引用。
import ShiguangSymbols from '@/components/shiguang/ShiguangSymbols.vue'
</script>
