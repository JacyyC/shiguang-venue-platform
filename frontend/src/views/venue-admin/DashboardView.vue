<template>
  <section class="admin-page venue-dashboard">
    <div class="page-head">
      <div class="page-head-main">
        <p class="sg-kicker">空间运营 · 场馆</p>
        <h1 class="sg-display-l page-title"><span class="sg-mask-line"><span>今日场馆工作台</span></span></h1>
        <p class="section-desc">先处理待审核和临近履约，再查看收入、占用率与现场任务。</p>
      </div>
      <div class="venue-switcher">
        <span class="control-label">当前场馆</span>
        <el-select v-model="venueId" aria-label="当前场馆" placeholder="选择场馆" :loading="loading" @change="loadSummary">
          <el-option v-for="venue in venues" :key="venue.id" :label="venue.name" :value="venue.id" />
        </el-select>
      </div>
    </div>

    <div v-if="loadError" class="page-state error-state" role="alert">
      <div>
        <strong>工作台暂时无法加载</strong>
        <span>{{ loadError }}</span>
      </div>
      <el-button :loading="loading" @click="initialize">重新加载</el-button>
    </div>

    <!-- C-09：未绑定场馆引导态（预览 §10）——玻璃空态卡，替代原整屏警告条。 -->
    <section v-else-if="!loading && !venues.length" class="data-panel bind-guide sg-glass" data-test="bind-guide">
      <h2 class="bind-guide-title">还差一步：绑定你要管理的场馆</h2>
      <p class="bind-guide-desc">
        场馆管理员角色需要系统管理员为你指定可管理的场馆。绑定后即可处理预约审核、核销与现场运营。
      </p>
      <div class="bind-guide-actions">
        <button type="button" class="guide-ink-btn" :aria-expanded="guideOpen ? 'true' : 'false'" @click="guideOpen = !guideOpen">
          查看绑定指引
        </button>
        <button type="button" class="guide-ghost-btn" @click="copyRequestText">{{ copyButtonLabel }}</button>
      </div>
      <div v-if="guideOpen" class="bind-guide-detail">
        <ol>
          <li>联系系统管理员（或在申请文案中说明你的账号）。</li>
          <li>系统管理员进入 <strong>平台治理 → 用户管理 → 场馆授权</strong>，将你绑定到目标场馆。</li>
          <li>绑定完成后刷新本页，左侧全部菜单与工作台即解锁。</li>
        </ol>
      </div>
    </section>

    <el-skeleton v-if="loading && !summary && venues.length" class="data-panel dashboard-skeleton" :rows="5" animated />

    <template v-else-if="venueId">
      <!-- 待办卡：全站玻璃白名单件（预览 F13 玻璃待办原型），行 hover 染 7% 柿橙+位移弹簧 -->
      <section class="data-panel duty-panel sg-glass" aria-labelledby="duty-title">
        <div class="panel-title duty-title-row">
          <div>
            <span class="panel-eyebrow">{{ currentDateLabel }}</span>
            <h2 id="duty-title">当班待办</h2>
          </div>
          <span class="duty-count" :class="{ 'is-clear': !(summary?.pendingReviews || 0) }">
            {{ summary?.pendingReviews || 0 }} 项待审核
          </span>
        </div>
        <div class="duty-list">
          <button type="button" class="duty-item" @click="$router.push('/venue-admin/reservations')">
            <span class="duty-icon duty-icon-warning"><el-icon><Checked /></el-icon></span>
            <span><strong>预约审核</strong><small>{{ summary?.pendingReviews || 0 }} 条等待处理</small></span>
            <el-icon><ArrowRight /></el-icon>
          </button>
          <button type="button" class="duty-item" @click="$router.push('/venue-admin/checkins')">
            <span class="duty-icon"><el-icon><Tickets /></el-icon></span>
            <span><strong>今日履约</strong><small>{{ summary?.todayReservations || 0 }} 笔预约需关注</small></span>
            <el-icon><ArrowRight /></el-icon>
          </button>
          <button type="button" class="duty-item" @click="$router.push('/venue-admin/site-ops')">
            <span class="duty-icon duty-icon-neutral"><el-icon><SetUp /></el-icon></span>
            <span><strong>现场运营</strong><small>查看班次、任务、交接与异常</small></span>
            <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
      </section>

      <section class="data-panel kpi-strip context-strip" aria-label="当前场馆经营概览">
        <div class="context-copy">
          <span class="context-label">当前场馆</span>
          <strong>{{ venues.find((venue) => venue.id === venueId)?.name || '未选择场馆' }}</strong>
          <small>{{ currentDateLabel }}</small>
        </div>
        <div class="kpi-item"><span>今日预约</span><strong><SgCountNumber :value="summary?.todayReservations ?? 0" /><small>单</small></strong></div>
        <div class="kpi-item"><span>待审核</span><strong :class="{ 'is-warning': (summary?.pendingReviews || 0) > 0 }"><SgCountNumber :value="summary?.pendingReviews ?? 0" /><small>项</small></strong></div>
        <div class="kpi-item"><span>今日收入</span><strong><SgCountNumber :value="Number(summary?.revenue || 0)" :decimals="2" prefix="￥" /></strong></div>
        <div class="kpi-item"><span>占用率</span><strong><SgCountNumber :value="summary?.occupancyRate ?? 0" /><small>%</small></strong></div>
      </section>

      <section class="data-panel">
      <div class="panel-title">
          <div>
            <h2>常用工具</h2>
            <span class="muted-text">处理库存、订单和场馆通知</span>
          </div>
      </div>
      <div class="quick-links">
        <el-button @click="$router.push('/venue-admin/orders')">
          <el-icon><Tickets /></el-icon>
            订单与退款
        </el-button>
          <el-button @click="$router.push('/venue-admin/calendar')">
            <el-icon><Calendar /></el-icon>
            营业日历
          </el-button>
          <el-button @click="$router.push('/venue-admin/spaces')">
          <el-icon><SetUp /></el-icon>
            场地与维护
          </el-button>
          <el-button @click="$router.push('/venue-admin/notices')">
            <el-icon><Promotion /></el-icon>
            发布场馆公告
        </el-button>
      </div>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { statisticsApi, type DashboardSummary } from '@/api/statistics'
import { venueAdminApi } from '@/api/venue'
import { useVenueAccessStore } from '@/stores/venueAccess'
import type { Venue } from '@/api/types'
import SgCountNumber from '@/components/SgCountNumber.vue'

const venues = ref<Venue[]>([])
const venueId = ref<number | undefined>()
const summary = ref<DashboardSummary | null>(null)
const loading = ref(false)
const loadError = ref('')
const guideOpen = ref(false)
const copyDone = ref(false)
const REQUEST_TEXT = '@系统管理员：请为我在 平台治理→用户管理→场馆授权 绑定可管理场馆'
const copyButtonLabel = computed(() => (copyDone.value ? '已复制，等待管理员处理' : '复制申请文案'))
const currentDateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'short'
}).format(new Date()))
const readableError = (error: unknown) => error instanceof Error && error.message ? error.message : '请检查网络后重试。'
const loadSummary = async () => {
  if (!venueId.value) {
    summary.value = null
    return
  }
  loading.value = true
  try {
    summary.value = await statisticsApi.dashboard({ venueId: venueId.value })
    loadError.value = ''
  } catch (error) {
    loadError.value = readableError(error)
  } finally {
    loading.value = false
  }
}

const copyRequestText = async () => {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(REQUEST_TEXT)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = REQUEST_TEXT
      textarea.setAttribute('readonly', '')
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    copyDone.value = true
    ElMessage.success('申请文案已复制，粘贴给系统管理员即可')
    window.setTimeout(() => {
      copyDone.value = false
    }, 4000)
  } catch {
    ElMessage.error('复制失败，请手动选择文案复制')
  }
}

const initialize = async () => {
  loading.value = true
  try {
    venues.value = await venueAdminApi.manageable()
    // 未绑定清单同步进 venueAccess 缓存，AdminLayout 据此收敛侧边栏菜单。
    useVenueAccessStore().$patch({ venues: venues.value, loaded: true, loading: false })
    if (!venues.value.some((venue) => venue.id === venueId.value)) {
      venueId.value = venues.value[0]?.id
    }
    if (venueId.value) {
      summary.value = await statisticsApi.dashboard({ venueId: venueId.value })
    } else {
      summary.value = null
    }
    loadError.value = ''
  } catch (error) {
    loadError.value = readableError(error)
  } finally {
    loading.value = false
  }
}

onMounted(initialize)
</script>

<style scoped>
.sg-kicker {
  max-width: 420px;
}

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.venue-dashboard {
  gap: 18px;
}

.venue-switcher {
  display: grid;
  gap: 6px;
  min-width: min(280px, 100%);
}

.control-label,
.panel-eyebrow {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
  font-weight: 700;
}

.page-state {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border: 1px solid color-mix(in srgb, var(--sg-danger) 28%, transparent);
  border-radius: var(--sg-radius-control);
  background: var(--sg-danger-soft);
}

.page-state strong,
.page-state span {
  display: block;
}

.page-state span {
  margin-top: 4px;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-base);
}

.dashboard-skeleton {
  min-height: 220px;
}

.bind-guide {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 24px;
  text-align: center;
}

.bind-guide-title {
  margin: 0;
  font-family: var(--sg-font-serif);
  font-size: var(--sg-fz-lg);
  font-weight: 600;
}

.bind-guide-desc {
  max-width: 460px;
  margin: 0;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
  line-height: 1.7;
}

.bind-guide-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 12px;
}

.guide-ink-btn,
.guide-ghost-btn {
  min-height: 38px;
  padding: 0 16px;
  border-radius: var(--sg-radius-control);
  font: inherit;
  font-size: var(--sg-fz-sm);
  cursor: pointer;
}

.guide-ink-btn {
  border: 1px solid var(--sg-pine);
  background: var(--sg-pine);
  color: var(--sg-canvas);
}

.guide-ink-btn:hover {
  filter: brightness(1.06);
}

.guide-ghost-btn {
  border: 1px solid var(--sg-border-strong);
  background: transparent;
  color: var(--sg-ink-muted);
}

.guide-ghost-btn:hover {
  border-color: var(--sg-pine);
  color: var(--sg-pine);
}

.guide-ink-btn:focus-visible,
.guide-ghost-btn:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: 2px;
}

.bind-guide-detail {
  max-width: 480px;
  margin-top: 14px;
  padding: 14px 18px;
  border: 1px dashed var(--sg-border);
  border-radius: var(--sg-radius-control);
  background: var(--sg-surface-subtle);
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
  line-height: 1.9;
  text-align: left;
}

.bind-guide-detail ol {
  margin: 0;
  padding-left: 18px;
}

.duty-title-row > div,
.panel-title > div {
  display: grid;
  gap: 4px;
}

/* KPI 上下文条：3px 左竖条退役（预览 §08），衬线数字走全局 kpi-strip + SgCountNumber 弹簧计数 */
.context-strip {
  align-items: center;
}

.context-copy {
  min-width: 210px;
  padding-right: 20px;
  border-right: 1px solid var(--sg-border-soft);
}

.context-copy strong,
.context-copy small {
  display: block;
}

.context-copy strong {
  margin-top: 3px;
  font-family: var(--sg-font-serif);
  font-size: var(--sg-fz-lg);
  font-weight: 600;
}

.context-copy small {
  margin-top: 2px;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
}

.context-label {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
}

.kpi-item strong.is-warning {
  color: var(--sg-warning);
}

.duty-count {
  color: var(--sg-warning);
  font-size: var(--sg-fz-sm);
  font-weight: 700;
}

.duty-count.is-clear {
  color: var(--sg-success);
}

.duty-list {
  display: grid;
  grid-template-columns: 1fr;
}

.duty-item {
  min-height: 64px;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 20px;
  align-items: center;
  gap: 12px;
  padding: 12px 10px;
  border: 0;
  border-top: 1px solid var(--sg-border-soft);
  border-radius: var(--sg-radius-control);
  background: transparent;
  color: var(--sg-ink);
  text-align: left;
  cursor: pointer;
  transition: background-color var(--sg-motion-fast) ease, transform var(--sg-motion-base) var(--sg-ease-spring), color var(--sg-motion-fast) ease;
}

@media (prefers-reduced-motion: no-preference) {
  .duty-item:hover {
    background: color-mix(in srgb, var(--sg-primary) 7%, transparent);
    transform: translateX(2px);
  }
}

.duty-item:focus-visible {
  background: color-mix(in srgb, var(--sg-primary) 7%, transparent);
  outline: 2px solid var(--sg-primary);
  outline-offset: 2px;
}

.duty-item strong,
.duty-item small {
  display: block;
}

.duty-item small {
  margin-top: 4px;
  color: var(--sg-ink-muted);
  line-height: 1.4;
}

.duty-icon {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: var(--sg-radius-control);
  background: var(--sg-primary-soft);
  color: var(--sg-primary);
  font-size: var(--sg-fz-lg);
}

.duty-icon-warning {
  background: var(--sg-honey-soft);
  color: var(--sg-honey);
}

.duty-icon-neutral {
  background: var(--sg-surface-subtle);
  color: var(--sg-ink-muted);
}

.kpi-card strong {
  font-variant-numeric: tabular-nums;
}

@media (max-width: 640px) {
  .venue-switcher {
    width: 100%;
  }

  .page-state {
    align-items: stretch;
    flex-direction: column;
  }

  .page-state .el-button,
  .quick-links .el-button {
    width: 100%;
    margin-left: 0;
  }

  .context-copy {
    width: 100%;
    padding: 0 0 12px;
    border-right: 0;
    border-bottom: 1px solid var(--sg-border-soft);
  }
}
</style>
