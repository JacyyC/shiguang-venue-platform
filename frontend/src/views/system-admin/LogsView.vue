<template>
  <section class="admin-page logs-page" :aria-busy="loading">
    <div class="page-head">
      <div class="page-head-main">
        <p class="sg-kicker">平台治理 · 安全审计</p>
        <h1 class="sg-display-l page-title">日志审计</h1>
        <p class="section-desc">分开查看账号登录与后台操作轨迹，优先核对失败结果和异常说明。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新日志</el-button>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      title="日志加载失败"
      :description="loadError"
    >
      <template #default><el-button size="small" @click="load">重新加载</el-button></template>
    </el-alert>

    <!-- tab+表合并同卡（AdminTabbedLedger）：密表页只透卡缝 -->
    <div class="desktop-table data-panel logs-card">
      <AdminTabbedLedger v-model="tab" :tabs="[{ name: 'login', label: '登录日志' }, { name: 'operation', label: '操作日志' }]">
        <template #login>
            <div class="panel-title">
              <div>
                <h2>登录记录</h2>
                <p>用于识别登录失败与账号访问异常。</p>
              </div>
              <span class="muted-text">{{ total }} 条</span>
            </div>
            <el-table v-loading="loading" :data="records" empty-text="当前范围暂无日志">
              <el-table-column prop="id" label="编号" width="90" />
              <el-table-column prop="username" label="操作用户" min-width="130" />
              <el-table-column label="IP" min-width="120">
                <template #default="{ row }"><code class="log-mono sg-num">{{ row.ip }}</code></template>
              </el-table-column>
              <el-table-column label="登录结果" min-width="110">
                <template #default="{ row }">
                  <span class="sg-tag" :class="`sg-tag--${statusTone(row.status)}`">{{ statusLabel(row.status) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="message" label="结果说明" min-width="220" show-overflow-tooltip />
              <el-table-column label="时间" min-width="170">
                <template #default="{ row }"><code class="log-mono sg-num">{{ row.createTime }}</code></template>
              </el-table-column>
            </el-table>
        </template>
        <template #operation>
            <div class="panel-title">
              <div>
                <h2>操作记录</h2>
                <p>用于追踪管理动作、执行结果与失败原因。</p>
              </div>
              <span class="muted-text">{{ total }} 条</span>
            </div>
            <el-table v-loading="loading" :data="records" empty-text="当前范围暂无日志">
              <el-table-column prop="id" label="编号" width="90" />
              <el-table-column prop="username" label="操作用户" min-width="130" />
              <el-table-column label="IP" min-width="120">
                <template #default="{ row }"><code class="log-mono sg-num">{{ row.ip }}</code></template>
              </el-table-column>
              <el-table-column prop="module" label="模块" min-width="120" />
              <el-table-column prop="action" label="操作" min-width="150" show-overflow-tooltip />
              <el-table-column label="执行结果" min-width="110">
                <template #default="{ row }">
                  <span class="sg-tag" :class="`sg-tag--${statusTone(row.resultStatus)}`">{{ statusLabel(row.resultStatus) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="errorMessage" label="失败说明" min-width="220" show-overflow-tooltip />
              <el-table-column label="时间" min-width="170">
                <template #default="{ row }"><code class="log-mono sg-num">{{ row.createTime }}</code></template>
              </el-table-column>
            </el-table>
        </template>
      </AdminTabbedLedger>
    </div>
    <el-pagination
      v-model:current-page="pageNum"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="pageSize"
      @current-change="load"
    />
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import AdminTabbedLedger from '@/components/AdminTabbedLedger.vue'
import { systemApi } from '@/api/system'
import type { LoginLog, OperationLog } from '@/api/system-types'

const tab = ref('login')
const records = ref<Array<LoginLog | OperationLog>>([])
const pageNum = ref(1)
const pageSize = 30
const total = ref(0)
const loading = ref(false)
const loadError = ref('')

const statusType = (status?: string) => {
  if (['SUCCESS', 'ACTIVE', 'OK'].includes(status || '')) return 'success'
  if (['FAILED', 'FAILURE', 'ERROR', 'DENIED'].includes(status || '')) return 'danger'
  return 'info'
}

const statusTone = (status?: string) => `sg-tag--${statusType(status) === 'info' ? 'neutral' : statusType(status)}`

const statusLabel = (status?: string) => {
  const labels: Record<string, string> = {
    SUCCESS: '成功',
    FAILED: '失败',
    FAILURE: '失败',
    ERROR: '异常',
    DENIED: '拒绝'
  }
  return labels[status || ''] || status || '未记录'
}

const load = async () => {
  loading.value = true
  try {
    const page = tab.value === 'login'
      ? await systemApi.loginLogs({ pageNum: pageNum.value, pageSize })
      : await systemApi.operationLogs({ pageNum: pageNum.value, pageSize })
    records.value = page.records
    total.value = page.total
    loadError.value = ''
  } catch (error) {
    loadError.value = error instanceof Error && error.message ? error.message : '请检查网络连接后重试。'
  } finally {
    loading.value = false
  }
}

watch(tab, () => {
  pageNum.value = 1
  load()
})

onMounted(load)
</script>

<style scoped>
.sg-kicker {
  max-width: 420px;
}

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.logs-page {
  max-width: 1440px;
}

.logs-card {
  overflow: hidden;
}

.log-mono {
  font-family: var(--sg-font-mono);
  font-size: var(--sg-fz-xs);
}

.panel-title p {
  margin: 5px 0 0;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
}

@media (max-width: 640px) {
  .page-head {
    align-items: stretch;
    flex-direction: column;
  }

  .page-head > .el-button {
    width: 100%;
  }
}
</style>
