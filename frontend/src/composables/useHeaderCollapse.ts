import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 大标题滚动收缩（V3-UI-UX-SHIGUANG S04，预览 §08 帖子/活动详情共用一件）：
 * 窗口滚动越过阈值即置 collapsed=true，页面据此浮出玻璃收缩条
 * （仅动 opacity/transform，玻璃底经 ::before 透明度过渡，reduced-motion 下瞬切）。
 */
export function useHeaderCollapse(threshold = 88) {
  const collapsed = ref(false)

  const sync = () => {
    collapsed.value = window.scrollY > threshold
  }

  onMounted(() => {
    window.addEventListener('scroll', sync, { passive: true })
    sync()
  })

  onBeforeUnmount(() => {
    window.removeEventListener('scroll', sync)
  })

  return { collapsed }
}
