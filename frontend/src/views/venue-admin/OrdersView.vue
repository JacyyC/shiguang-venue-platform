<template>
  <section class="admin-page">
    <div class="page-head">
      <div>
        <p class="sg-kicker">空间运营 · 资金处理</p>
        <h1 class="sg-display-l page-title">订单与退款</h1>
        <p class="section-desc">查看场馆订单，集中处理退款审核与退款到账确认。</p>
      </div>
      <div class="toolbar-actions">
        <el-button :icon="'Refresh'" :loading="loading || refundLoading" @click="load">刷新</el-button>
      </div>
    </div>
    <AdminFilterBar label="订单筛选">
      <div class="filter-field">
        <span class="filter-label">场馆</span>
        <el-select v-model="venueId" aria-label="筛选场馆" placeholder="全部可管理场馆" clearable>
          <el-option v-for="venue in venues" :key="venue.id" :label="venue.name" :value="venue.id" />
        </el-select>
      </div>
      <template #actions>
        <el-button type="primary" :loading="loading || refundLoading" @click="search">查询</el-button>
      </template>
    </AdminFilterBar>

    <div v-if="loadError" class="page-state" role="alert">
      <div><strong>订单数据加载失败</strong><span>{{ loadError }}</span></div>
      <el-button :loading="loading || refundLoading" @click="load">重试</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="订单列表" name="orders">
        <div class="desktop-table data-panel">
          <div class="panel-title">
            <div><h2>场馆订单</h2><span class="muted-text">共 {{ total }} 条</span></div>
          </div>
          <el-table v-if="loading || orders.length" v-loading="loading" :data="orders" empty-text="暂无订单">
            <el-table-column prop="orderNo" label="订单号" min-width="190" />
            <el-table-column prop="reservationId" label="预约" width="90" />
            <el-table-column prop="venueId" label="场馆" width="90" />
            <el-table-column label="金额" width="120"><template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template></el-table-column>
            <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :value="row.status" kind="order" /></template></el-table-column>
            <el-table-column prop="createTime" label="创建时间" min-width="170" />
          </el-table>
          <EmptyState v-else compact title="暂无订单" description="当前筛选条件下暂无订单" />
        </div>
      </el-tab-pane>
      <el-tab-pane label="退款处理" name="refunds">
        <div class="desktop-table data-panel">
          <div class="panel-title">
            <div><h2>退款队列</h2><span class="muted-text">共 {{ total }} 条</span></div>
          </div>
          <el-table v-if="refundLoading || refunds.length" v-loading="refundLoading" :data="refunds" empty-text="暂无退款记录">
            <el-table-column prop="refundNo" label="退款单号" min-width="190" />
            <el-table-column prop="orderId" label="订单" width="90" />
            <el-table-column label="金额" width="120"><template #default="{ row }">{{ formatMoney(row.refundAmount) }}</template></el-table-column>
            <el-table-column prop="refundReason" label="原因" min-width="180" />
            <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :value="row.status" kind="refund" /></template></el-table-column>
            <el-table-column prop="updateTime" label="更新时间" min-width="170" />
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <div class="table-actions">
                  <el-button v-if="row.status === 'PENDING_REVIEW'" size="small" type="primary" :loading="isActing(row.id, 'approve')" :disabled="isBusy(row.id)" @click="approve(row.id)">通过</el-button>
                  <el-button v-if="row.status === 'PENDING_REVIEW'" size="small" type="danger" plain :loading="isActing(row.id, 'reject')" :disabled="isBusy(row.id)" @click="reject(row.id)">拒绝</el-button>
                  <el-button v-if="row.status === 'REFUNDING'" size="small" :loading="isActing(row.id, 'success')" :disabled="isBusy(row.id)" @click="markSuccess(row.id)">确认到账</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState v-else title="暂无可处理退款" description="退款申请和自动退款记录会出现在这里" />
        </div>
      </el-tab-pane>
    </el-tabs>
    <el-pagination
      v-model:current-page="filters.pageNum"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="filters.pageSize"
      @current-change="load"
    />
  </section>
</template>

<script setup lang="ts">
/* 场馆运营订单沿用 Intl CNY 形态：上提后别名保持模板调用点不变，输出字符串零改动 */
import { formatCurrencyCNY as formatMoney } from '@/utils/money'
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminOrderApi } from '@/api/order'
import { venueAdminApi } from '@/api/venue'
import type { Order, RefundRecord, Venue } from '@/api/types'
import StatusTag from '@/components/StatusTag.vue'
import AdminFilterBar from '@/components/AdminFilterBar.vue'
import EmptyState from '@/components/EmptyState.vue'

const venues = ref<Venue[]>([])
const venueId = ref<number | undefined>()
const orders = ref<Order[]>([])
const refunds = ref<RefundRecord[]>([])
const total = ref(0)
const loading = ref(false)
const refundLoading = ref(false)
const loadError = ref('')
const actingId = ref<number | null>(null)
const actingType = ref('')
const activeTab = ref('orders')
const filters = reactive({ pageNum: 1, pageSize: 20 })
const readableError = (error: unknown) => error instanceof Error && error.message ? error.message : '请检查网络后重试。'
const isBusy = (id: number) => actingId.value === id
const isActing = (id: number, type: string) => actingId.value === id && actingType.value === type
const runAction = async (id: number, type: string, action: () => Promise<void>) => {
  if (actingId.value !== null) return
  actingId.value = id
  actingType.value = type
  try {
    await action()
  } finally {
    actingId.value = null
    actingType.value = ''
  }
}
const search = () => {
  filters.pageNum = 1
  load()
}
const load = async () => {
  if (activeTab.value === 'refunds') {
    await loadRefunds()
    return
  }
  loading.value = true
  try {
    const page = await adminOrderApi.list({
      ...filters,
      ...(venueId.value ? { venueId: venueId.value } : {})
    })
    orders.value = page.records
    total.value = page.total
    loadError.value = ''
  } catch (error) {
    loadError.value = readableError(error)
  } finally {
    loading.value = false
  }
}
const loadRefunds = async () => {
  refundLoading.value = true
  try {
    const page = await adminOrderApi.refunds({
      ...filters,
      ...(venueId.value ? { venueId: venueId.value } : {})
    })
    refunds.value = page.records
    total.value = page.total
    loadError.value = ''
  } catch (error) {
    loadError.value = readableError(error)
  } finally {
    refundLoading.value = false
  }
}
const approve = async (id: number) => {
  await runAction(id, 'approve', async () => {
    const { value } = await ElMessageBox.prompt('请输入审核说明', '通过退款', {
      inputValue: '退款审核通过',
      confirmButtonText: '通过',
      cancelButtonText: '取消'
    })
    await adminOrderApi.approveRefund(id, value)
    ElMessage.success('退款审核已通过')
    await loadRefunds()
  })
}
const reject = async (id: number) => {
  await runAction(id, 'reject', async () => {
    const { value } = await ElMessageBox.prompt('请输入拒绝原因', '拒绝退款', {
      confirmButtonText: '拒绝',
      cancelButtonText: '取消'
    })
    await adminOrderApi.rejectRefund(id, value)
    ElMessage.success('退款申请已拒绝')
    await loadRefunds()
  })
}
const markSuccess = async (id: number) => {
  await runAction(id, 'success', async () => {
    await ElMessageBox.confirm('确认该退款已经到账？', '确认退款到账', {
      type: 'warning',
      confirmButtonText: '确认到账',
      cancelButtonText: '取消'
    })
    await adminOrderApi.markRefundSuccess(id)
    ElMessage.success('退款已确认到账')
    await loadRefunds()
  })
}
watch(activeTab, () => {
  filters.pageNum = 1
  load()
})
onMounted(async () => {
  try {
    venues.value = await venueAdminApi.manageable()
    venueId.value = venues.value[0]?.id
    await load()
  } catch (error) {
    loadError.value = readableError(error)
  }
})
</script>

<style scoped>
.sg-kicker {
  max-width: 420px;
}

/* 退款操作钮弹簧按压（预览 §08 订单退款处方） */
.table-actions :deep(.el-button) {
  transition: transform var(--sg-motion-base) var(--sg-ease-spring);
}

@media (prefers-reduced-motion: no-preference) {
  .table-actions :deep(.el-button:active) {
    transform: scale(0.97);
  }
}

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.filter-field,
.panel-title > div {
  display: grid;
  gap: 6px;
}

.filter-label {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
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
  font-size: var(--sg-fz-sm);
}

@media (max-width: 640px) {
  .page-state .el-button {
    width: 100%;
  }

  .page-state {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
