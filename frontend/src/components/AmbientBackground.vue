<template>
  <div class="sg-bg-field" aria-hidden="true">
    <canvas ref="canvasRef" class="sg-bg-canvas"></canvas>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useThemeStore } from '@/stores/theme'
import { useAmbientPause } from '@/composables/useAmbientPause'
import {
  bindDustCanvas,
  clearDustMouse,
  dustState,
  moveDustMouse,
  refreshDustColors,
  resizeDust,
  tickDust
} from '@/components/ambient/dustEngine'
/**
 * 全屏墨尘画布宿主（V6-UI-UX-SHIGUANG S01，视觉唯一权威 design-preview.html §01 定稿）：
 * 粒子引擎本体在 ambient/dustEngine.ts（单例，漂浮/飞行/成形/退场状态机 + life 退场 +
 * Token 运行时取色 + population 公式 + resize 重播种）。本组件只负责：
 * - 挂载于 App.vue 的路由级单例画布（不随页面重挂载），进场仪式由 HomeView 的
 *   useIntroCeremony 驱动引擎，聚形交接退场后画布清空、全站纯纸底；
 * - useAmbientPause 三重停帧纪律（document.hidden / 「动效 关」/ reduced-motion）；
 * - 明暗切换 → refreshDustColors()，飞行中/成形中粒子下一帧即时换色（F-10）。
 * V5 的 mesh 调色板、scrim、极光 blob 与口味体系随本重构退役（结论登记 Evidence）。
 */
const theme = useThemeStore()
const canvasRef = ref<HTMLCanvasElement | null>(null)

let rafId: number | null = null

const frame = (now: number) => {
  tickDust(now)
  rafId = window.requestAnimationFrame(frame)
}

const play = () => {
  if (rafId != null) return
  rafId = window.requestAnimationFrame(frame)
}

const pause = () => {
  if (rafId == null) return
  window.cancelAnimationFrame(rafId)
  rafId = null
}

const onResize = () => resizeDust()

const onPointerMove = (event: PointerEvent) => moveDustMouse(event.clientX, event.clientY)

useAmbientPause({ play, pause })

// 明暗切换即时换色：引擎下一帧起以新 Token 取色（飞行中/成形中同样生效）
watch(
  () => theme.isDark,
  () => refreshDustColors()
)

onMounted(() => {
  const canvas = canvasRef.value
  if (canvas) bindDustCanvas(canvas)
  // 开发环境走查断言句柄（AC-01/AC-05 状态证据），生产构建不含
  if (import.meta.env.DEV) {
    ;(window as unknown as Record<string, unknown>).__sgDust = { state: dustState, tick: tickDust }
  }
  window.addEventListener('resize', onResize)
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerleave', clearDustMouse)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerleave', clearDustMouse)
  pause()
})
</script>
