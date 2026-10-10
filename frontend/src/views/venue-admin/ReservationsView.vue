<template>
  <section class="admin-page">
    <div class="page-head">
      <div>
        <p class="sg-kicker">空间运营 · 待办处理</p>
        <h1 class="sg-display-l page-title">预约审核</h1>
        <p class="section-desc">按场馆、状态和日期筛选预约，优先处理待审核记录。</p>
      </div>
      <div class="toolbar-actions">
        <el-button :icon="'Refresh'" @click="loadAll">刷新</el-button>
      </div>
    </div>
    <AdminFilterBar label="预约筛选">
      <div class="filter-field">
        <span class="filter-label">场馆</span>
        <el-select v-model="filters.venueId" aria-label="筛选场馆" placeholder="全部可管理场馆" clearable>
          <el-option v-for="venue in venues" :key="venue.id" :label="venue.name" :value="venue.id" />
        </el-select>
      </div>
      <div class="filter-field">
        <span class="filter-label">预约状态</span>
        <el-select v-model="filters.status" aria-label="筛选预约状态" placeholder="全部状态" clearable>
          <el-option label="待审核" value="PENDING_REVIEW" />
          <el-option label="待支付" value="PENDING_PAYMENT" />
          <el-option label="已支付" value="PAID" />
          <el-option label="已取消" value="CANCELED" />
        </el-select>
      </div>
      <div class="filter-field">
        <span class="filter-label">预约日期</span>
        <el-date-picker v-model="filters.date" aria-label="筛选预约日期" value-format="YYYY-MM-DD" type="date" placeholder="全部日期" />
      </div>
      <template #actions>
        <el-button type="primary" :loading="loading" @click="search">查询</el-button>
      </template>
    </AdminFilterBar>

    <div v-if="loadError" class="page-state" role="alert">
      <div><strong>预约列表加载失败</strong><span>{{ loadError }}</span></div>
      <el-button :loading="loading" @click="load()">重试</el-button>
    </div>
    <el-alert
      v-else-if="!loading && !venues.length"
      type="warning"
      title="当前账号没有可管理场馆"
      description="请联系系统管理员配置场馆权限。"
      :closable="false"
      show-icon
    />

    <div v-else class="desktop-table data-panel reservation-table">
      <div class="panel-title">
        <div><h2>预约队列</h2><span class="muted-text">优先处理待审核记录</span></div>
        <span class="sg-tag sg-tag--neutral">共 {{ total }} 条</span>
      </div>
      <el-table v-if="loading || records.length" v-loading="loading" :data="records" empty-text="暂无预约">
        <el-table-column prop="id" label="编号" width="80" />
        <el-table-column label="场馆" min-width="140">
          <template #default="{ row }">{{ venueName(row.venueId) }}</template>
        </el-table-column>
        <el-table-column label="场地" min-width="130">
          <template #default="{ row }">{{ spaceNameOf(row.venueId, row.spaceId) }}</template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" min-width="170" />
        <el-table-column prop="contactName" label="联系人" width="110" />
        <el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :value="row.status" /></template></el-table-column>
        <el-table-column label="操作" width="300">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button v-if="row.status === 'PENDING_REVIEW'" type="primary" size="small" :loading="isActing(row.id, 'approve')" :disabled="isRowBusy(row.id)" @click="approve(row.id)">通过</el-button>
              <el-button v-if="row.status === 'PENDING_REVIEW'" type="danger" plain size="small" :loading="isActing(row.id, 'reject')" :disabled="isRowBusy(row.id)" @click="reject(row.id)">拒绝</el-button>
              <el-button v-if="canCancel(row.status)" size="small" :loading="isActing(row.id, 'cancel')" :disabled="isRowBusy(row.id)" @click="cancel(row.id)">取消</el-button>
              <el-button v-if="row.status === 'PAID'" size="small" @click="router.push('/venue-admin/checkins')">签到核销</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else compact title="暂无预约" description="当前筛选条件下暂无预约" />
    </div>

    <el-pagination
      v-if="!loadError && total > filters.pageSize"
      v-model:current-page="filters.pageNum"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="filters.pageSize"
      @current-change="load"
    />

    <section class="data-panel reschedule-panel" aria-labelledby="reschedule-queue-title">
      <div class="panel-title">
        <div>
          <h2 id="reschedule-queue-title">改期审核</h2>
          <span class="muted-text">未支付预约沿用原人数和座位，审核通过时重新校验库存与价格</span>
        </div>
        <span class="sg-tag sg-tag--neutral">共 {{ rescheduleTotal }} 条</span>
      </div>
      <div v-if="rescheduleLoadError" class="page-state" role="alert">
        <div><strong>改期队列加载失败</strong><span>{{ rescheduleLoadError }}</span></div>
        <el-button :loading="rescheduleLoading" @click="loadRescheduleRequests">重试</el-button>
      </div>
      <div v-else class="desktop-table">
        <el-table v-loading="rescheduleLoading" :data="rescheduleRecords" empty-text="暂无改期申请">
          <el-table-column prop="id" label="申请" width="80" />
          <el-table-column label="预约 / 场馆" min-width="150">
            <template #default="{ row }">{{ venueName(row.venueId) }} · {{ spaceNameOf(row.venueId, row.spaceId) }}</template>
          </el-table-column>
          <el-table-column label="原时段" min-width="190">
            <template #default="{ row }">{{ formatRange(row.fromStartTime, row.fromEndTime) }}</template>
          </el-table-column>
          <el-table-column label="目标时段" min-width="190">
            <template #default="{ row }">{{ formatRange(row.toStartTime, row.toEndTime) }}</template>
          </el-table-column>
          <el-table-column prop="reasonText" label="原因" min-width="150" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><span class="sg-tag" :class="sgTagTone(rescheduleTagType(row.status))">{{ rescheduleStatusText(row.status) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <div v-if="row.status === 'PENDING_REVIEW'" class="table-actions">
                <el-button size="small" type="primary" :loading="isRescheduleActing(row.id, true)" :disabled="rescheduleActingId !== null" @click="reviewReschedule(row.id, true)">通过</el-button>
                <el-button size="small" type="danger" plain :loading="isRescheduleActing(row.id, false)" :disabled="rescheduleActingId !== null" @click="reviewReschedule(row.id, false)">拒绝</el-button>
              </div>
              <span v-else class="muted-text">{{ row.reviewReason || '已处理' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-if="!rescheduleLoadError && rescheduleRecords.length" class="mobile-reschedule-list">
        <article v-for="row in rescheduleRecords" :key="row.id" class="reschedule-card">
          <div class="reschedule-card-head">
            <strong>改期申请</strong>
            <span class="sg-tag" :class="sgTagTone(rescheduleTagType(row.status))">{{ rescheduleStatusText(row.status) }}</span>
          </div>
          <dl>
            <dt>预约</dt><dd>{{ venueName(row.venueId) }} · {{ spaceNameOf(row.venueId, row.spaceId) }}</dd>
            <dt>原时段</dt><dd>{{ formatRange(row.fromStartTime, row.fromEndTime) }}</dd>
            <dt>目标时段</dt><dd>{{ formatRange(row.toStartTime, row.toEndTime) }}</dd>
            <dt>原因</dt><dd>{{ row.reasonText || '-' }}</dd>
          </dl>
          <div v-if="row.status === 'PENDING_REVIEW'" class="card-actions">
            <el-button type="primary" :loading="isRescheduleActing(row.id, true)" :disabled="rescheduleActingId !== null" @click="reviewReschedule(row.id, true)">通过</el-button>
            <el-button :loading="isRescheduleActing(row.id, false)" :disabled="rescheduleActingId !== null" @click="reviewReschedule(row.id, false)">拒绝</el-button>
          </div>
          <p v-else>{{ row.reviewReason || '已处理' }}</p>
        </article>
      </div>
      <EmptyState
        v-if="!rescheduleLoading && !rescheduleLoadError && !rescheduleRecords.length"
        class="mobile-reschedule-empty"
        compact
        title="暂无改期申请"
      />
      <el-pagination
        v-if="!rescheduleLoadError && rescheduleTotal > reschedulePageSize"
        v-model:current-page="reschedulePageNum"
        layout="prev, pager, next, total"
        :total="rescheduleTotal"
        :page-size="reschedulePageSize"
        @current-change="loadRescheduleRequests"
      />
    </section>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminReservationApi } from '@/api/reservation'
import { venueAdminApi } from '@/api/venue'
import type { Reservation, ReservationRescheduleRequest, Venue } from '@/api/types'
import StatusTag from '@/components/StatusTag.vue'
import AdminFilterBar from '@/components/AdminFilterBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAppStore } from '@/stores/app'
import { fetchSpaceName } from '@/utils/entityNames'
import { sgTagTone } from '@/utils/presentation'

const records = ref<Reservation[]>([])
const venues = ref<Venue[]>([])
const loading = ref(false)
const total = ref(0)
const loadError = ref('')
const actingId = ref<number | null>(null)
const actingType = ref('')
const rescheduleRecords = ref<ReservationRescheduleRequest[]>([])
const rescheduleLoading = ref(false)
const rescheduleLoadError = ref('')
const rescheduleTotal = ref(0)
const reschedulePageNum = ref(1)
const reschedulePageSize = 20
const rescheduleActingId = ref<number | null>(null)
const rescheduleActingApprove = ref<boolean | null>(null)
const app = useAppStore()
const router = useRouter()
let refreshTimer: number | undefined
let knownPendingIds = new Set<number>()
const filters = reactive<{ pageNum: number; pageSize: number; venueId?: number; status: string; date: string }>({
  pageNum: 1,
  pageSize: 20,
  venueId: undefined,
  status: '',
  date: ''
})
const venueName = (venueId: number) => venues.value.find((venue) => venue.id === venueId)?.name || '场馆'
const spaceNames = ref(new Map<string, string>())
const spaceNameOf = (venueId: number, spaceId?: number) => {
  if (!spaceId) return '场地'
  return spaceNames.value.get(`${venueId}:${spaceId}`) || '场地'
}
const ensureSpaceNames = async (pairs: Array<{ venueId: number; spaceId?: number }>) => {
  await Promise.all(pairs.map(async ({ venueId, spaceId }) => {
    if (!spaceId || spaceNames.value.has(`${venueId}:${spaceId}`)) return
    const name = await fetchSpaceName(venueId, spaceId)
    if (name) spaceNames.value.set(`${venueId}:${spaceId}`, name)
  }))
}
const canCancel = (status: string) => ['PENDING_REVIEW', 'APPROVED', 'PENDING_PAYMENT', 'PAID'].includes(status)
const isRowBusy = (id: number) => actingId.value === id
const isActing = (id: number, type: string) => actingId.value === id && actingType.value === type
const readableError = (error: unknown) => error instanceof Error && error.message ? error.message : '请检查网络后重试。'
const formatDateTime = (value: string) => value ? value.slice(0, 16).replace('T', ' ') : '-'
const formatRange = (start: string, end: string) => {
  if (start?.slice(0, 10) === end?.slice(0, 10)) {
    return `${formatDateTime(start)} - ${end.slice(11, 16)}`
  }
  return `${formatDateTime(start)} - ${formatDateTime(end)}`
}
const rescheduleStatusText = (status: ReservationRescheduleRequest['status']) => ({
  PENDING_REVIEW: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝'
}[status])
const rescheduleTagType = (status: ReservationRescheduleRequest['status']) => {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  return 'warning'
}
const isRescheduleActing = (id: number, approve: boolean) =>
  rescheduleActingId.value === id && rescheduleActingApprove.value === approve
const buildParams = () => ({
  pageNum: filters.pageNum,
  pageSize: filters.pageSize,
  ...(filters.venueId ? { venueId: filters.venueId } : {}),
  ...(filters.status ? { status: filters.status } : {}),
  ...(filters.date ? { date: filters.date } : {})
})
const load = async (silent = false) => {
  if (!silent) {
    loading.value = true
  }
  try {
    const page = await adminReservationApi.list(buildParams())
    const pendingRecords = page.records.filter((record) => record.status === 'PENDING_REVIEW')
    if (silent) {
      const newPendingCount = pendingRecords.filter((record) => !knownPendingIds.has(record.id)).length
      if (newPendingCount > 0) {
        ElMessage.info(`收到 ${newPendingCount} 条新的待审核预约`)
      }
    }
    knownPendingIds = new Set(pendingRecords.map((record) => record.id))
    records.value = page.records
    total.value = page.total
    void ensureSpaceNames(page.records)
    loadError.value = ''
    await app.refreshUnreadCount()
  } catch (error) {
    if (!silent) {
      loadError.value = readableError(error)
    }
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}
const loadRescheduleRequests = async (silent = false) => {
  if (!silent) rescheduleLoading.value = true
  try {
    const page = await adminReservationApi.rescheduleRequests({
      pageNum: reschedulePageNum.value,
      pageSize: reschedulePageSize
    })
    rescheduleRecords.value = page.records
    rescheduleTotal.value = page.total
    void ensureSpaceNames(page.records)
    rescheduleLoadError.value = ''
  } catch (error) {
    if (!silent) {
      rescheduleRecords.value = []
      rescheduleTotal.value = 0
      rescheduleLoadError.value = readableError(error)
    }
  } finally {
    if (!silent) rescheduleLoading.value = false
  }
}
const loadAll = async () => {
  await Promise.all([load(), loadRescheduleRequests()])
}
const search = () => {
  filters.pageNum = 1
  void load()
}
const runRowAction = async (id: number, type: string, action: () => Promise<void>) => {
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
const approve = async (id: number) => {
  await runRowAction(id, 'approve', async () => {
    await ElMessageBox.confirm('确认通过该预约？通过后用户将进入后续支付流程。', '通过预约', {
      confirmButtonText: '确认通过',
      cancelButtonText: '返回'
    })
    await adminReservationApi.approve(id, '审核通过')
    ElMessage.success('已通过预约')
    await load()
  })
}
const reject = async (id: number) => {
  await runRowAction(id, 'reject', async () => {
    const { value } = await ElMessageBox.prompt('请输入拒绝原因', '拒绝预约')
    await adminReservationApi.reject(id, value)
    ElMessage.success('已拒绝预约')
    await load()
  })
}
const cancel = async (id: number) => {
  await runRowAction(id, 'cancel', async () => {
    const { value } = await ElMessageBox.prompt('请输入取消原因', '取消预约')
    await adminReservationApi.cancel(id, value)
    ElMessage.success('已取消预约')
    await load()
  })
}
const reviewReschedule = async (id: number, approveRequest: boolean) => {
  if (rescheduleActingId.value !== null) return
  rescheduleActingId.value = id
  rescheduleActingApprove.value = approveRequest
  try {
    const actionText = approveRequest ? '通过' : '拒绝'
    const promptText = approveRequest
      ? '通过时会重新校验目标时段库存与价格，请填写审核说明。'
      : '请填写拒绝原因，用户将在预约列表中看到该反馈。'
    const { value } = await ElMessageBox.prompt(promptText, `${actionText}改期申请`, {
      confirmButtonText: `确认${actionText}`,
      cancelButtonText: '返回',
      inputPlaceholder: '请输入审核说明',
      inputValidator: (input) => Boolean(input.trim()) || '请填写审核说明'
    })
    await adminReservationApi.reviewReschedule(id, approveRequest, value.trim())
    ElMessage.success(`已${actionText}改期申请`)
    await Promise.all([load(), loadRescheduleRequests()])
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(readableError(error))
    }
  } finally {
    rescheduleActingId.value = null
    rescheduleActingApprove.value = null
  }
}
onMounted(async () => {
  try {
    venues.value = await venueAdminApi.manageable()
  } catch (error) {
    const message = readableError(error)
    loadError.value = message
    rescheduleLoadError.value = message
    return
  }
  await loadAll()
  refreshTimer = window.setInterval(() => {
    void load(true)
    void loadRescheduleRequests(true)
  }, 10000)
})
onBeforeUnmount(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
  }
})
</script>

<style scoped>
.sg-kicker {
  max-width: 420px;
}

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.filter-field {
  display: grid;
  gap: 7px;
}

.filter-label {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
  font-weight: 700;
}

.filter-field :deep(.el-select),
.filter-field :deep(.el-date-editor) {
  width: 100%;
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
.page-state span,
.panel-title > div {
  display: block;
}

.page-state span,
.panel-title .muted-text {
  margin-top: 4px;
  font-size: var(--sg-fz-sm);
}

.reschedule-panel {
  min-width: 0;
}

.mobile-reschedule-list,
.mobile-reschedule-empty {
  display: none;
}

@media (max-width: 640px) {
  .page-state .el-button {
    width: 100%;
  }

  .page-state {
    align-items: stretch;
    flex-direction: column;
  }

  .reschedule-panel > .desktop-table {
    display: none;
  }

  .mobile-reschedule-list {
    display: grid;
    gap: 12px;
  }

  .mobile-reschedule-empty {
    display: flex;
  }

  .reschedule-card {
    display: grid;
    gap: 12px;
    padding: 14px;
    border: 1px solid var(--sg-border);
    border-radius: var(--sg-radius-control);
    background: var(--sg-surface-subtle);
  }

  .reschedule-card-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  .reschedule-card dl {
    display: grid;
    grid-template-columns: minmax(64px, auto) minmax(0, 1fr);
    gap: 8px 12px;
    margin: 0;
    font-size: var(--sg-fz-base);
  }

  .reschedule-card dt {
    color: var(--sg-ink-muted);
  }

  .reschedule-card dd {
    min-width: 0;
    margin: 0;
    overflow-wrap: anywhere;
  }

  .reschedule-card > p {
    margin: 0;
    color: var(--sg-ink-muted);
    font-size: var(--sg-fz-sm);
  }

  .card-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .card-actions .el-button {
    width: 100%;
    margin-left: 0;
  }
}
</style>
