export const scrollToPageHead = () => {
  if (typeof document === 'undefined') return
  const target = document.querySelector<HTMLElement>('.page-head')
  if (!target || typeof target.scrollIntoView !== 'function') return
  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  target.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' })
}
