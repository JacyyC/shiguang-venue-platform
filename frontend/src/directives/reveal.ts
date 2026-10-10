import type { Directive } from 'vue'

type RevealHost = HTMLElement & { _sgReveal?: IntersectionObserver }

/** R6 §00C① 渐入：进入视口加 .in 上浮淡入，只播一次；配 main.css 的 .rv 使用。
 *  交错由模板上的 --rvd 显式声明，指令只负责「进入视口一次性触发」。
 *  无 IntersectionObserver 或用户偏好 reduced-motion 时直接显示。 */
export const vReveal: Directive<RevealHost> = {
  mounted(el) {
    const reduced = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    if (reduced || typeof IntersectionObserver === 'undefined') {
      el.classList.add('in')
      return
    }
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            el.classList.add('in')
            observer.disconnect()
            el._sgReveal = undefined
          }
        }
      },
      { rootMargin: '0px 0px -8% 0px' }
    )
    observer.observe(el)
    el._sgReveal = observer
  },
  unmounted(el) {
    el._sgReveal?.disconnect()
    el._sgReveal = undefined
  }
}
