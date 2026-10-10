<template>
  <article
    v-if="decision"
    ref="advertisingRef"
    class="home-commercial-ad sg-focus-in"
    :data-measurement-status="measurementStatus === 'idle' ? undefined : measurementStatus"
    :aria-label="decision.accessibleAdLabel || '广告内容'"
  >
    <button class="advertising-link" type="button" @click="handleClick">
      <span class="advertising-copy">
        <span class="advertising-kicker">
          <span class="advertising-badge sg-tag sg-tag--honey" aria-hidden="true">{{ decision.adLabel || '广告' }}</span>
          <span class="sr-only">{{ decision.accessibleAdLabel || '广告内容' }}</span>
          <span>平台商业内容</span>
        </span>
        <strong class="advertising-title">{{ decision.headline }}</strong>
        <span v-if="decision.bodyText" class="advertising-body">{{ decision.bodyText }}</span>
        <span class="advertising-cta">
          {{ callToActionLabel }}
          <el-icon aria-hidden="true"><ArrowRight /></el-icon>
        </span>
      </span>
    </button>
  </article>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import { advertisingApi } from '@/api/advertising'
import type { AdvertisingEventRequest, HomeCommercialDecision } from '@/api/types'

const router = useRouter()
const decision = ref<HomeCommercialDecision | null>(null)
const advertisingRef = ref<HTMLElement>()
const measurementStatus = ref<'idle' | 'pending' | 'recovered' | 'exhausted'>('idle')
let observer: IntersectionObserver | undefined
let visibleTimer: number | undefined
let visibleStartedAt = 0
let viewableReported = false
let clickReported = false
let mounted = false

const callToActionLabel = computed(() => ({
  VIEW: '查看详情',
  BOOK: '立即预约',
  LEARN_MORE: '了解更多'
}[decision.value?.callToAction || ''] || '查看详情'))

const eventKey = (type: string) => `home-commercial:${decision.value?.decisionId || 'unknown'}:${type}`

const report = (payload: AdvertisingEventRequest, key: string) => {
  const current = decision.value
  if (!current?.token?.trim()) return
  measurementStatus.value = 'pending'
  void advertisingApi.recordEventWithRecovery(current.decisionId, payload, key).then(
    () => {
      if (mounted) measurementStatus.value = 'recovered'
    },
    () => {
      if (mounted) measurementStatus.value = 'exhausted'
    }
  )
}

const clearVisibilityTimer = () => {
  if (visibleTimer !== undefined) {
    window.clearTimeout(visibleTimer)
    visibleTimer = undefined
  }
  visibleStartedAt = 0
}

const reportViewable = (ratio: number) => {
  const current = decision.value
  if (viewableReported || !current?.token?.trim()) return
  viewableReported = true
  report({
    eventType: 'CLIENT_VIEWABLE',
    token: current.token,
    visibleRatio: Number(ratio.toFixed(4)),
    visibleDurationMs: Math.max(1000, Date.now() - visibleStartedAt)
  }, eventKey('CLIENT_VIEWABLE'))
}

const observeVisibility = (entries: IntersectionObserverEntry[]) => {
  const entry = entries[0]
  if (!entry || !entry.isIntersecting || entry.intersectionRatio < 0.5 || viewableReported) {
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
  if (!current?.token?.trim()) return
  if (!clickReported) {
    clickReported = true
    report({ eventType: 'CLICK', token: current.token || '' }, eventKey('CLICK'))
  }
  if (current.deepLink) void router.push(current.deepLink).catch(() => undefined)
}

const load = async () => {
  try {
    const nextDecision = await advertisingApi.homeCommercialDecision()
    if (!nextDecision?.token?.trim()) {
      decision.value = null
      return
    }
    decision.value = nextDecision
    await nextTick()
    if (advertisingRef.value && typeof IntersectionObserver !== 'undefined') {
      observer = new IntersectionObserver(observeVisibility, { threshold: [0, 0.5, 1] })
      observer.observe(advertisingRef.value)
    }
  } catch {
    // Commercial advertising is optional and must never own home navigation errors.
    decision.value = null
  }
}

onMounted(() => {
  mounted = true
  void load()
})

onBeforeUnmount(() => {
  mounted = false
  clearVisibilityTimer()
  observer?.disconnect()
})
</script>

<style scoped>
/* 排印海报卡（预览 §04+ F15 / §08 首页商业广告卡）：与自营宣传位共用同一件，
   蜜金 wash 变体；去常驻描边与蜜金满铺，实底卡定性 + 内高光 + 弹簧 hover。 */
.home-commercial-ad {
  --advertising-ink: var(--sg-ink);
  --advertising-accent: var(--sg-honey);
  /* 间距归宿主 .ad-slot 栅格，卡内不再自带外边距（§08 间距归栅格） */
  position: relative;
  overflow: hidden;
  min-height: 142px;
  border-radius: var(--sg-radius-card);
  background: var(--sg-surface);
  box-shadow: var(--sg-shadow-2), inset 0 1px 0 var(--sg-sheen);
  transition: transform var(--sg-motion-base) var(--sg-ease-spring), box-shadow var(--sg-motion-fast) var(--sg-ease-standard);
}

.home-commercial-ad:hover {
  transform: translateY(-3px) scale(1.012);
  box-shadow: var(--sg-shadow-float), inset 0 1px 0 var(--sg-sheen);
}

/* 印点纹（排印海报面） */
.home-commercial-ad::before {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(color-mix(in srgb, var(--sg-ink) 8%, transparent) 1px, transparent 1.6px);
  background-size: 20px 20px;
  opacity: 0.45;
  pointer-events: none;
  content: '';
}

/* 蜜金 wash 柔光 */
.home-commercial-ad::after {
  position: absolute;
  inset: 0;
  background: radial-gradient(70% 90% at 88% 0%, color-mix(in srgb, var(--sg-honey) 24%, transparent), transparent 70%);
  pointer-events: none;
  content: '';
}

.advertising-link {
  position: relative;
  width: 100%;
  min-width: 0;
  min-height: 142px;
  box-sizing: border-box;
  display: block;
  padding: 20px 24px;
  border: 0;
  background: transparent;
  color: var(--advertising-ink);
  text-align: left;
}

.advertising-copy {
  min-width: 0;
  max-width: 100%;
  display: grid;
  align-content: center;
  justify-items: start;
  gap: 8px;
}

.advertising-kicker,
.advertising-cta {
  max-width: 100%;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--advertising-accent);
  font-size: var(--sg-fz-xs);
  font-weight: 700;
}

.advertising-kicker {
  letter-spacing: 0.16em;
}

/* 「广告」合规标识：语义与文案不变，视觉收敛为全局蜜金章（预览 §08 处方） */
.advertising-badge {
  flex: 0 0 auto;
}

.advertising-title,
.advertising-body {
  display: block;
  width: 100%;
  max-width: 760px;
  overflow-wrap: anywhere;
}

.advertising-title {
  color: var(--advertising-ink);
  font-family: var(--sg-font-serif);
  font-size: 23px;
  line-height: 1.35;
}

.advertising-body {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-base);
  line-height: 1.6;
}

.advertising-cta {
  gap: 5px;
  margin-top: 6px;
  transition: gap var(--sg-motion-base) var(--sg-ease-spring);
}

.home-commercial-ad:hover .advertising-cta {
  gap: 10px;
}

.advertising-link:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: -3px;
}

@media (max-width: 640px) {
  .home-commercial-ad {
    min-height: 0;
  }

  .advertising-link {
    min-height: 0;
    padding: 16px;
  }

  .advertising-title {
    font-size: var(--sg-fz-lg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .home-commercial-ad,
  .advertising-cta {
    transition: none;
  }

  .home-commercial-ad:hover {
    transform: none;
  }
}
</style>
