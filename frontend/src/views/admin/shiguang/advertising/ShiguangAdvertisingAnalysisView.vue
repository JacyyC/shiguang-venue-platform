<template>
  <div ref="rootEl" class="sga-gov" data-test="sga-ad-analysis-root">
    <div v-if="!canRead" class="g-empty" data-test="ad-analysis-denied">
      <h2>当前没有商业分析权限</h2>
      <p>需要 advertising:analysis:view 权限读取聚合数据。</p>
    </div>

    <template v-else>
      <div class="g-toolbar">
        <div>
          <h2>商业决策与归因观察</h2>
          <p>平台授权范围 · 所选时间窗口的独立聚合，不能按广告主或计划推定细分。</p>
        </div>
      </div>

      <form class="g-tools" data-test="ad-analysis-form" @submit.prevent="readAnalysis">
        <div class="field">
          <label for="ad-analysis-from">起始日期</label>
          <input id="ad-analysis-from" v-model="from" data-test="ad-analysis-from" type="date" required>
        </div>
        <div class="field">
          <label for="ad-analysis-until">结束日期（不含）</label>
          <input id="ad-analysis-until" v-model="until" data-test="ad-analysis-until" type="date" required>
        </div>
        <button class="btn" type="submit" data-test="ad-read-analysis" :disabled="loading">
          {{ loading ? '读取中…' : '读取分析' }}
        </button>
      </form>

      <div v-if="loadError" class="g-error" role="alert" data-test="ad-analysis-error">{{ loadError }}</div>

      <template v-if="overview">
        <p class="g-note" data-test="ad-analysis-caliber">
          归因采用 7 天最后点击规则；计数是不同对象的独立聚合，不证明广告造成预约，
          也不表示收入、计费或投资回报。窗口上限 180 天。
        </p>
        <div class="g-analysis-layout">
          <section data-test="ad-analysis-measures">
            <h3>事件与已归因预约</h3>
            <div v-for="measure in measures" :key="measure.label" class="g-measure">
              <span>{{ measure.label }}</span>
              <div class="g-measure-bar" aria-hidden="true"><i :style="{ width: measure.ratio + '%' }"></i></div>
              <strong>{{ measure.value.toLocaleString() }}</strong>
            </div>
            <p class="g-muted" style="margin-top:18px">
              这些是不同对象的聚合，不是同一用户集合逐层相交的漏斗；不显示转换率。
            </p>
          </section>
          <aside class="g-analysis-side" data-test="ad-analysis-gaps">
            <h3>缺口与恢复中的记录</h3>
            <dl class="g-meta">
              <dt>缺口预约</dt><dd>{{ overview.gapReservations.toLocaleString() }}</dd>
              <dt>归因已撤销</dt><dd>{{ overview.revokedReservations.toLocaleString() }}</dd>
              <dt>无有效点击回连</dt><dd>{{ overview.noValidClickCallbacks.toLocaleString() }}</dd>
              <dt>隐私关闭回连</dt><dd>{{ overview.privacyDisabledCallbacks.toLocaleString() }}</dd>
              <dt>等待重试回连</dt><dd>{{ overview.pendingRetryCallbacks.toLocaleString() }}</dd>
              <dt>未匹配点击</dt><dd>{{ overview.unmatchedClicks.toLocaleString() }}</dd>
            </dl>
            <p class="g-muted">
              缺口与等待重试不是零转化。后台归因任务承接已有回连，不在此提供手工改写归因或重试按钮。
            </p>
          </aside>
        </div>
        <section class="g-section">
          <h3>与首页的承接</h3>
          <p class="g-muted">
            现有首页分别读取 HOME_PRIMARY 自营宣传和 HOME_COMMERCIAL 商业决策；可选请求失败可局部降级。
            管理侧发布不保证个人页面获得决策，用户隐私关闭时不能继续追踪。
          </p>
        </section>
      </template>

      <div v-else-if="!loading && !loadError" class="g-empty" data-test="ad-analysis-empty">
        <h2>选择时间窗口后读取分析</h2>
        <p>聚合按平台授权范围计算，不提供按广告主或计划的细分。</p>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/* 拾光商业分析面（SHIGUANG-BATCH-05 S07）：一比一承接冻结参考 advertising.js 的 analysis 工作面
   （B5-G10）。g-* 基元沿用 B4 迁移的 shiguang-admin-governance.css（.sga-gov）。
   计数/缺口/撤销/7 天最后点击口径按服务端呈现，不伪造广告主或计划维度细分。 */
import { computed, onMounted, ref } from 'vue'
import { advertisingApi } from '@/api/advertising'
import { useAuthStore } from '@/stores/auth'
import type { AdvertisingAnalysisOverview } from '@/api/types'

const auth = useAuthStore()
const canRead = computed(() => auth.hasPermission('advertising:analysis:view'))

const loading = ref(false)
const loadError = ref('')
const rootEl = ref<HTMLElement>()
const overview = ref<AdvertisingAnalysisOverview | null>(null)

const today = new Date()
const from = ref(new Date(today.getTime() - 11 * 86400_000).toISOString().slice(0, 10))
const until = ref(today.toISOString().slice(0, 10))

const measures = computed(() => {
  const data = overview.value
  if (!data) return []
  const rows: Array<[string, number]> = [
    ['决策', data.decisions],
    ['可见曝光事件', data.viewableEvents],
    ['点击事件', data.clickEvents],
    ['已归因预约', data.attributedReservations]
  ]
  const max = Math.max(...rows.map(([, value]) => value), 1)
  return rows.map(([label, value]) => ({ label, value, ratio: Math.round((value / max) * 100) }))
})

const readableError = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

async function readAnalysis() {
  if (loading.value) return
  if (until.value <= from.value) {
    loadError.value = '结束日期必须晚于起始日期。'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    // 服务端 from/until 为 LocalDateTime（ISO DATE_TIME）：日期按 [from T00:00, until T00:00) 窗口提交
    overview.value = await advertisingApi.admin.overview(`${from.value}T00:00:00`, `${until.value}T00:00:00`)
  } catch (error) {
    loadError.value = readableError(error, '分析读取失败，请重试。')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (canRead.value) void readAnalysis()
})
</script>
