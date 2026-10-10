<template>
  <section class="admin-page dashboard-page" :aria-busy="loading">
    <div class="page-head">
      <div class="page-head-main">
        <p class="sg-kicker">平台治理 · 总览</p>
        <h1 class="sg-display-l page-title">平台看板</h1>
        <p class="section-desc">先看全局运行信号，再进入财务、渠道与触达治理任务。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新数据</el-button>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      title="平台概览暂时无法加载"
      :description="loadError"
    >
      <template #default>
        <el-button size="small" @click="load">重新加载</el-button>
      </template>
    </el-alert>

    <section v-if="loading && !summary" class="data-panel kpi-strip context-strip" aria-label="正在加载平台概览">
      <el-skeleton :rows="2" animated />
    </section>
    <section v-else-if="summary" class="data-panel kpi-strip context-strip" aria-label="平台概览">
      <div class="context-copy"><span class="context-label">当前工作区</span><strong>平台治理</strong><small>全局运行信号与待处理事项</small></div>
      <div class="kpi-item"><span>平台用户</span><strong><SgCountNumber :value="summary.totalUsers" /></strong></div>
      <div class="kpi-item"><span>平台收入</span><strong><SgCountNumber :value="Number(summary.revenue || 0)" :decimals="2" prefix="￥" /></strong></div>
      <div class="kpi-item"><span>今日预约</span><strong><SgCountNumber :value="summary.todayReservations" /><small>单</small></strong></div>
      <div class="kpi-item"><span>待处理事项</span><strong :class="{ 'is-warning': summary.pendingReviews > 0 }"><SgCountNumber :value="summary.pendingReviews" /></strong></div>
    </section>

    <!-- 待办卡：全站玻璃待办原型（预览 F13） -->
    <section class="data-panel todo-panel sg-glass" aria-labelledby="dashboard-todo-title">
      <div class="panel-title"><div><h2 id="dashboard-todo-title">待办 · 按优先级</h2><p>先处理需要人工复核的财务和渠道事项。</p></div><span class="todo-badge">{{ summary ? formatNumber(summary.pendingReviews) : '—' }} 项</span></div>
      <div class="todo-list">
        <button v-for="task in governanceTasks.slice(0, 2)" :key="`todo-${task.path}`" class="todo-item" type="button" @click="router.push(task.path)">
          <span class="todo-index">{{ governanceTasks.indexOf(task) + 1 }}</span>
          <span class="task-copy"><strong>{{ task.title }}</strong><small>{{ task.description }}</small></span>
          <span class="text-link">进入处理 <ArrowRight /></span>
        </button>
      </div>
    </section>

    <div class="data-panel governance-panel">
      <div class="panel-title">
        <div>
          <h2>治理入口</h2>
          <p>按任务进入对应证据页面，避免在多个模块之间反复查找。</p>
        </div>
      </div>
      <div class="governance-grid">
        <button
          v-for="task in governanceTasks"
          :key="task.path"
          class="governance-task"
          type="button"
          @click="router.push(task.path)"
        >
          <span class="task-icon"><el-icon><component :is="task.icon" /></el-icon></span>
          <span class="task-copy">
            <strong>{{ task.title }}</strong>
            <small>{{ task.description }}</small>
          </span>
          <el-icon class="task-arrow"><ArrowRight /></el-icon>
        </button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowRight, Bell, Coin, DocumentChecked, Refresh, SetUp } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { statisticsApi, type DashboardSummary } from '@/api/statistics'
import SgCountNumber from '@/components/SgCountNumber.vue'

const router = useRouter()
const summary = ref<DashboardSummary | null>(null)
const loading = ref(false)
const loadError = ref('')

const governanceTasks = [
  { title: '财务差异复核', description: '核对支付渠道与系统账目', path: '/system-admin/reconciliation', icon: Coin },
  { title: '渠道健康', description: '查看降级、失败与证据新鲜度', path: '/system-admin/providers', icon: SetUp },
  { title: '触达证据', description: '检查批次、发件箱与阅读结果', path: '/system-admin/notice-delivery', icon: Bell },
  { title: '安全审计', description: '追踪登录风险与后台操作', path: '/system-admin/logs', icon: DocumentChecked }
]

const formatNumber = (value?: number) => Number(value || 0).toLocaleString('zh-CN')
const load = async () => {
  loading.value = true
  try {
    summary.value = await statisticsApi.dashboard({})
    loadError.value = ''
  } catch (error) {
    loadError.value = error instanceof Error && error.message ? error.message : '请检查网络连接后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.sg-kicker {
  max-width: 420px;
}

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.dashboard-page {
  max-width: 1440px;
}

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

.todo-panel .panel-title p {
  margin: 6px 0 0;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
}

.todo-badge {
  color: var(--sg-warning);
  font-size: var(--sg-fz-sm);
  font-weight: 700;
}

.todo-list {
  display: grid;
}

.todo-item {
  width: 100%;
  min-height: 64px;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 12px 2px;
  border: 0;
  border-top: 1px solid var(--sg-border-soft);
  border-radius: 0;
  color: var(--sg-ink);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.todo-item {
  transition: background-color var(--sg-motion-fast) ease, transform var(--sg-motion-base) var(--sg-ease-spring);
}

@media (prefers-reduced-motion: no-preference) {
  .todo-item:hover {
    background: color-mix(in srgb, var(--sg-primary) 7%, transparent);
    transform: translateX(2px);
  }
}

.todo-item:focus-visible {
  background: color-mix(in srgb, var(--sg-primary) 7%, transparent);
  outline: 2px solid var(--sg-primary);
  outline-offset: 2px;
}

.todo-index {
  color: var(--sg-ink-subtle);
  font-family: var(--sg-font-serif);
  font-size: var(--sg-fz-lg);
}

.text-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  width: fit-content;
  color: var(--sg-primary);
  font-size: var(--sg-fz-sm);
  font-weight: 600;
  white-space: nowrap;
}

.text-link :deep(svg) {
  width: 1em;
  height: 1em;
  flex: 0 0 auto;
}

.governance-panel .panel-title p {
  margin: 6px 0 0;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
  line-height: 1.5;
}

.governance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.governance-task {
  min-height: 78px;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 20px;
  align-items: center;
  gap: 12px;
  padding: 14px 8px;
  color: var(--sg-ink);
  background: transparent;
  border: 0;
  border-top: 1px solid var(--sg-border-soft);
  border-radius: 0;
  text-align: left;
  transition: background-color 180ms ease, color 180ms ease;
}

.governance-task:hover {
  background: color-mix(in srgb, var(--sg-primary) 7%, transparent);
}

.governance-task:hover .task-arrow {
  transform: translateX(3px);
  color: var(--sg-primary);
}

.task-arrow {
  transition: transform var(--sg-motion-base) var(--sg-ease-spring), color var(--sg-motion-fast) ease;
}

.governance-task:focus-visible {
  background: color-mix(in srgb, var(--sg-primary) 7%, transparent);
  outline: 2px solid var(--sg-primary);
  outline-offset: 2px;
}

.task-icon {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  color: var(--sg-primary);
  background: var(--sg-primary-soft);
  border-radius: var(--sg-radius-control);
  font-size: var(--sg-fz-lg);
}

.task-copy,
.task-copy strong,
.task-copy small {
  display: block;
  min-width: 0;
}

.task-copy small {
  margin-top: 5px;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
  line-height: 1.45;
}

.task-arrow {
  color: var(--sg-ink-subtle);
}

@media (max-width: 900px) {
  .governance-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .page-head {
    align-items: stretch;
    flex-direction: column;
  }

  .page-head > .el-button {
    width: 100%;
  }

  .governance-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .context-copy {
    width: 100%;
    padding: 0 0 12px;
    border-right: 0;
    border-bottom: 1px solid var(--sg-border-soft);
  }

  .todo-item {
    grid-template-columns: 24px minmax(0, 1fr);
  }

  .text-link {
    grid-column: 2;
  }
}

@media (prefers-reduced-motion: reduce) {
  .governance-task {
    transition: none;
  }
}
</style>
