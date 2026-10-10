<template>
  <article
    v-if="decision"
    ref="placementRef"
    class="home-primary-placement sg-focus-in"
    :class="{ 'has-image': Boolean(decision.imageUrl && imageReady) }"
    aria-label="首页宣传"
  >
    <button class="placement-link" type="button" @click="handleClick">
      <span v-if="decision.imageUrl && imageReady" class="placement-media" aria-hidden="true">
        <img :src="decision.imageUrl" alt="" loading="lazy" decoding="async" @error="imageReady = false" />
      </span>
      <span class="placement-copy">
        <span class="placement-kicker"><span class="placement-mark" aria-hidden="true">01</span>站内推荐</span>
        <strong class="placement-title">{{ decision.title }}</strong>
        <span v-if="decision.summary" class="placement-summary">{{ decision.summary }}</span>
        <span class="placement-cta">查看详情 <span class="placement-arrow" aria-hidden="true">→</span></span>
      </span>
    </button>
  </article>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { placementApi } from '@/api/placement'
import type { HomePrimaryDecision, PlacementEventRequest } from '@/api/types'

const router = useRouter()
const decision = ref<HomePrimaryDecision | null>(null)
const imageReady = ref(true)
const placementRef = ref<HTMLElement>()
let observer: IntersectionObserver | undefined
let visibleTimer: number | undefined
let visibleStartedAt = 0
let viewableReported = false
let clickReported = false

const eventKey = (type: string) => `home-primary:${decision.value?.decisionId || 'unknown'}:${type}`

const report = (payload: PlacementEventRequest, key: string) => {
  const current = decision.value
  if (!current?.token) return
  void placementApi.recordEvent(current.decisionId, payload, key).catch(() => undefined)
}

const clearVisibilityTimer = () => {
  if (visibleTimer !== undefined) {
    window.clearTimeout(visibleTimer)
    visibleTimer = undefined
  }
  visibleStartedAt = 0
}

const reportViewable = (ratio: number) => {
  if (viewableReported || !decision.value?.token) return
  viewableReported = true
  report({
    eventType: 'CLIENT_VIEWABLE',
    token: decision.value.token,
    visibleRatio: Number(ratio.toFixed(4)),
    visibleDurationMs: Math.max(1000, Date.now() - visibleStartedAt)
  }, eventKey('CLIENT_VIEWABLE'))
}

const observeVisibility = (entries: IntersectionObserverEntry[]) => {
  const entry = entries[0]
  if (!entry || entry.intersectionRatio < 0.5 || viewableReported) {
    clearVisibilityTimer()
    return
  }
  if (visibleTimer !== undefined) return
  visibleStartedAt = Date.now()
  visibleTimer = window.setTimeout(() => {
    visibleTimer = undefined
    reportViewable(entry.intersectionRatio)
  }, 1000)
}

const handleClick = () => {
  const current = decision.value
  if (!current) return
  if (!clickReported && current.token) {
    clickReported = true
    report({ eventType: 'CLICK', token: current.token }, eventKey('CLICK'))
  }
  if (current.deepLink) void router.push(current.deepLink)
}

const load = async () => {
  try {
    const nextDecision = await placementApi.homePrimaryDecision()
    if (!nextDecision) return
    decision.value = nextDecision
    imageReady.value = Boolean(nextDecision.imageUrl)
    await nextTick()
    if (placementRef.value && typeof IntersectionObserver !== 'undefined') {
      observer = new IntersectionObserver(observeVisibility, { threshold: [0, 0.5, 1] })
      observer.observe(placementRef.value)
    }
  } catch {
    // The placement is optional. Home navigation and venue data own their errors.
    decision.value = null
  }
}

onMounted(load)

onBeforeUnmount(() => {
  clearVisibilityTimer()
  observer?.disconnect()
})
</script>

<style scoped>
/* 排印海报卡（预览 §04+ F15 / §08 首页自营宣传位）：实底卡定性 + 内高光，
   去松绿满铺色块；无素材时叠印点纹与松绿 wash，accent 收敛回柿橙。 */
.home-primary-placement {
  --placement-ink: var(--sg-ink);
  --placement-accent: var(--sg-primary);
  position: relative;
  overflow: hidden;
  min-height: 154px;
  border-radius: var(--sg-radius-card);
  background: var(--sg-surface);
  box-shadow: var(--sg-shadow-2), inset 0 1px 0 var(--sg-sheen);
  transition: transform var(--sg-motion-base) var(--sg-ease-spring), box-shadow var(--sg-motion-fast) var(--sg-ease-standard);
}

.home-primary-placement:hover {
  transform: translateY(-3px) scale(1.012);
  box-shadow: var(--sg-shadow-float), inset 0 1px 0 var(--sg-sheen);
}

/* 印点纹（无素材海报面） */
.home-primary-placement:not(.has-image)::before {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(color-mix(in srgb, var(--sg-ink) 8%, transparent) 1px, transparent 1.6px);
  background-size: 20px 20px;
  opacity: 0.45;
  pointer-events: none;
  content: '';
}

/* 松绿 wash 柔光（右上角入射，与场馆海报族同语言） */
.home-primary-placement:not(.has-image)::after {
  position: absolute;
  inset: 0;
  background: radial-gradient(70% 90% at 88% 0%, color-mix(in srgb, var(--sg-pine) 22%, transparent), transparent 70%);
  pointer-events: none;
  content: '';
}

.placement-link {
  position: relative;
  width: 100%;
  min-height: 154px;
  display: grid;
  grid-template-columns: minmax(150px, 0.42fr) minmax(0, 1fr);
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--placement-ink);
  text-align: left;
}

.home-primary-placement:not(.has-image) .placement-link {
  display: block;
}

.home-primary-placement:not(.has-image) .placement-copy {
  min-height: 154px;
  box-sizing: border-box;
}

.placement-media {
  min-height: 154px;
  overflow: hidden;
  background: color-mix(in srgb, var(--sg-pine) 15%, var(--sg-surface));
}

.placement-media img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.placement-copy {
  min-width: 0;
  display: grid;
  align-content: center;
  justify-items: start;
  gap: 8px;
  padding: 20px 24px;
}

.placement-kicker,
.placement-cta {
  color: var(--placement-accent);
  font-size: var(--sg-fz-xs);
  font-weight: 700;
}

.placement-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  letter-spacing: 0.16em;
}

.placement-mark {
  display: inline-grid;
  min-width: 24px;
  height: 20px;
  place-items: center;
  padding: 0 5px;
  border-radius: var(--sg-radius-stamp);
  background: var(--placement-accent);
  color: var(--sg-canvas);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  line-height: 1;
}

.placement-title {
  max-width: 680px;
  overflow-wrap: anywhere;
  color: var(--placement-ink);
  font-family: var(--sg-font-serif);
  font-size: 27px;
  line-height: 1.3;
}

.placement-summary {
  max-width: 680px;
  overflow-wrap: anywhere;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-base);
  line-height: 1.55;
}

.placement-cta {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: 6px;
  transition: gap var(--sg-motion-base) var(--sg-ease-spring);
}

.home-primary-placement:hover .placement-cta {
  gap: 10px;
}

.placement-link:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: -3px;
}

@media (max-width: 640px) {
  .home-primary-placement,
  .placement-link {
    min-height: 0;
  }

  .placement-link {
    display: block;
  }

  .placement-media {
    height: 126px;
    min-height: 0;
  }

  .placement-copy {
    padding: 16px;
  }

  .home-primary-placement:not(.has-image) .placement-copy {
    min-height: 0;
  }

  .placement-title {
    font-size: var(--sg-fz-lg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .home-primary-placement,
  .placement-cta {
    transition: none;
  }

  .home-primary-placement:hover {
    transform: none;
  }
}
</style>
