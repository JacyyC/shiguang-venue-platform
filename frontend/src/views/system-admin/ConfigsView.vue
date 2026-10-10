<template>
  <section class="admin-page configs-page" :aria-busy="loading">
    <div class="page-head">
      <div class="page-head-main">
        <p class="sg-kicker">平台治理 · 运行配置</p>
        <h1 class="sg-display-l page-title">系统参数</h1>
        <p class="section-desc">查看平台运行参数；修改会直接影响后续业务请求，请先确认用途与取值。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新参数</el-button>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      title="系统参数加载失败"
      :description="loadError"
    >
      <template #default><el-button size="small" @click="load">重新加载</el-button></template>
    </el-alert>

    <!-- 参数清单重排 inset 分组卡行列表（预览 §08 系统参数样板）：参数名+说明+等宽当前值+行尾修改 -->
    <div class="data-panel config-list sg-group-card">
      <div class="panel-title">
        <div>
          <h2>参数清单</h2>
          <p>仅修改已确认的可编辑参数。</p>
        </div>
        <span class="muted-text">{{ configs.length }} 项</span>
      </div>
      <div v-loading="loading" class="config-rows">
        <div v-for="row in configs" :key="row.configKey" class="config-row">
          <div class="config-copy">
            <strong>{{ row.configName }}</strong>
            <span class="config-desc">{{ row.description || '用途说明待补充' }}</span>
            <code class="config-key sg-mono sg-num">{{ row.configKey }}</code>
          </div>
          <code class="config-value sg-mono sg-num">{{ row.configValue }}</code>
          <el-button
            size="small"
            type="primary"
            plain
            :loading="editingKey === row.configKey"
            @click="edit(row)"
          >修改</el-button>
        </div>
        <EmptyState v-if="!loading && !configs.length" compact title="暂无系统参数" description="后端返回的运行参数会显示在这里" />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { systemApi } from '@/api/system'
import type { SystemConfig } from '@/api/system-types'
import EmptyState from '@/components/EmptyState.vue'

const configs = ref<SystemConfig[]>([])
const loading = ref(false)
const editingKey = ref('')
const loadError = ref('')

const readableError = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

const load = async () => {
  loading.value = true
  try {
    configs.value = await systemApi.configs()
    loadError.value = ''
  } catch (error) {
    loadError.value = readableError(error, '请检查网络连接后重试。')
  } finally {
    loading.value = false
  }
}

const edit = async (row: SystemConfig) => {
  const { value } = await ElMessageBox.prompt(`修改 ${row.configName}`, '系统参数二次确认', {
    inputValue: row.configValue,
    inputPlaceholder: '请输入新的参数值',
    confirmButtonText: '确认修改',
    cancelButtonText: '取消'
  })
  editingKey.value = row.configKey
  try {
    await systemApi.updateConfig(row.configKey, value)
    ElMessage.success('系统参数已更新')
    await load()
  } catch (error) {
    ElMessage.error(readableError(error, '系统参数修改失败'))
  } finally {
    editingKey.value = ''
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

.configs-page {
  max-width: 1440px;
}

.panel-title p {
  margin: 5px 0 0;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
}

/* inset 分组卡行列表：发丝线行+整行 hover 7% 柿橙（预览 §08 系统参数样板） */
.config-rows {
  min-height: 120px;
}

.config-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 14px 18px;
  padding: 12px 12px;
  transition: background-color var(--sg-motion-fast) var(--sg-ease-standard);
}

.config-row + .config-row {
  box-shadow: 0 -1px 0 var(--sg-border-soft);
}

.config-row:hover {
  background: color-mix(in srgb, var(--sg-primary) 7%, transparent);
}

.config-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.config-copy strong {
  font-weight: 600;
}

.config-desc {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
}

.config-key {
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-xs);
}

.config-value {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--sg-ink);
  font-size: var(--sg-fz-sm);
}

@media (max-width: 640px) {
  .config-row {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .config-value {
    grid-column: 1 / -1;
    max-width: 100%;
    overflow-wrap: anywhere;
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
}
</style>
