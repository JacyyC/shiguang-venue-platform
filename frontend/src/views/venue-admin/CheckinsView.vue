<template>
  <section class="admin-page">
    <div class="page-head">
      <div class="page-head-main">
        <p class="sg-kicker">空间运营 · 现场履约</p>
        <h1 class="sg-display-l page-title">签到核销</h1>
        <p class="section-desc">先完成到场核销，再处理人工放行与爽约异常。</p>
      </div>
      <el-button :icon="'Refresh'" :loading="loading" @click="load">刷新</el-button>
    </div>

    <section class="data-panel checkin-action-panel" aria-labelledby="checkin-action-title">
      <div class="panel-title">
        <div>
          <h2 id="checkin-action-title">快速核销</h2>
          <span class="muted-text">支持扫码枪回车提交，也可手动输入凭证</span>
        </div>
      </div>
      <div class="checkin-console">
        <div class="filter-field credential-field">
          <span class="filter-label">核销凭证</span>
        <el-input
          v-model="credential"
          class="credential-input"
            aria-label="核销凭证"
            placeholder="扫码或输入核销码"
          clearable
          @keyup.enter="verify"
        />
        </div>
        <div class="filter-field device-field">
          <span class="filter-label">核销设备</span>
          <el-input v-model="deviceRef" class="device-input" aria-label="核销设备" placeholder="前台设备标识（可选）" />
        </div>
        <div class="checkin-submit">
          <el-button type="primary" :loading="verifying" @click="verify">确认核销</el-button>
        </div>
      </div>
    </section>

    <div class="filter-bar checkin-filter">
      <div class="filter-field">
        <span class="filter-label">核销状态</span>
        <el-select v-model="filters.checkinStatus" aria-label="筛选核销状态" placeholder="全部状态" clearable @change="search">
          <el-option label="待核销" value="NOT_CHECKED_IN" />
          <el-option label="已核销" value="CHECKED_IN" />
          <el-option label="已取消" value="CANCELED" />
        </el-select>
      </div>
      <div class="filter-field">
        <span class="filter-label">爽约状态</span>
        <el-select v-model="filters.noShowStatus" aria-label="筛选爽约状态" placeholder="全部状态" clearable @change="search">
          <el-option label="未评估爽约" value="NOT_EVALUATED" />
          <el-option label="待确认爽约" value="CANDIDATE" />
          <el-option label="已确认爽约" value="CONFIRMED" />
          <el-option label="已撤销爽约" value="REVOKED" />
        </el-select>
      </div>
      <div class="filter-field">
        <span class="filter-label">预约日期</span>
        <el-date-picker v-model="filters.date" aria-label="筛选预约日期" value-format="YYYY-MM-DD" type="date" placeholder="全部日期" @change="search" />
      </div>
      <div class="filter-action"><el-button type="primary" :loading="loading" @click="search">查询</el-button></div>
    </div>

    <div v-if="loadError" class="page-state" role="alert">
      <div><strong>履约记录加载失败</strong><span>{{ loadError }}</span></div>
      <el-button :loading="loading" @click="load">重试</el-button>
    </div>

    <div v-else class="desktop-table data-panel">
      <div class="panel-title">
        <div><h2>履约队列</h2><span class="muted-text">共 {{ total }} 条</span></div>
      </div>
      <el-table v-if="loading || records.length" v-loading="loading" :data="records" empty-text="暂无待核销预约">
        <el-table-column prop="reservationId" label="预约" width="90" />
        <el-table-column prop="checkinCodeLast4" label="尾号" width="100">
          <template #default="{ row }">{{ row.checkinCodeLast4 ? `尾号 ${row.checkinCodeLast4}` : '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="230">
          <template #default="{ row }">
            <div class="state-stack">
              <span class="sg-tag" :class="sgTagTone(statusType(row.credentialStatus, 'credential'))">{{ credentialStatusLabel(row.credentialStatus) }}</span>
              <span class="sg-tag" :class="sgTagTone(statusType(row.checkinStatus, 'checkin'))">{{ checkinStatusLabel(row.checkinStatus) }}</span>
              <span class="sg-tag" :class="sgTagTone(statusType(row.attendanceStatus, 'attendance'))">{{ attendanceStatusLabel(row.attendanceStatus) }}</span>
              <span class="sg-tag" :class="sgTagTone(statusType(row.noShowStatus, 'noshow'))">{{ noShowStatusLabel(row.noShowStatus) }}</span>
            </div>
            <span v-if="row.reasonText" class="cell-note">{{ row.reasonText }}</span>
          </template>
        </el-table-column>
        <el-table-column label="窗口" min-width="190">
          <template #default="{ row }">
            <div>{{ formatWindow(row) }}</div>
            <span v-if="row.checkedInAt" class="cell-note">核销 {{ formatTime(row.checkedInAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近事件" min-width="220">
          <template #default="{ row }">
            <ul v-if="row.events?.length" class="event-list">
              <li v-for="event in recentEvents(row)" :key="`${event.eventType}-${event.createTime}`">
                <span>{{ formatTime(event.createTime) }}</span>
                <strong>{{ eventLabel(event.eventType) }}</strong>
                <em>{{ event.reasonText || sourceTypeLabel(event.sourceType) }}</em>
              </li>
            </ul>
            <span v-else class="cell-note">暂无事件</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button v-if="canManualPass(row)" size="small" :loading="isActing(row.reservationId, 'pass')" :disabled="isBusy(row.reservationId)" @click="manualPass(row.reservationId)">人工放行</el-button>
              <el-button v-if="canConfirmNoShow(row)" size="small" type="warning" :loading="isActing(row.reservationId, 'confirm')" :disabled="isBusy(row.reservationId)" @click="confirmNoShow(row.reservationId)">确认爽约</el-button>
              <el-button v-if="canRevokeNoShow(row)" size="small" :loading="isActing(row.reservationId, 'revoke')" :disabled="isBusy(row.reservationId)" @click="revokeNoShow(row.reservationId)">撤销爽约</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else compact title="当前筛选条件下暂无履约记录" />
    </div>

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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminCheckinApi } from '@/api/checkin'
import type { LifecycleEventResponse, ReservationLifecycleResponse } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import { sgTagTone, sourceTypeLabel } from '@/utils/presentation'

type StatusKind = 'credential' | 'checkin' | 'attendance' | 'noshow'
type TagType = 'success' | 'warning' | 'danger' | 'info' | 'primary'

const records = ref<ReservationLifecycleResponse[]>([])
const total = ref(0)
const loading = ref(false)
const verifying = ref(false)
const loadError = ref('')
const actingId = ref<number | null>(null)
const actingType = ref('')
const credential = ref('')
const deviceRef = ref('frontdesk')
const filters = reactive({
  pageNum: 1,
  pageSize: 20,
  checkinStatus: '',
  noShowStatus: '',
  date: ''
})

const credentialLabels: Record<string, string> = {
  ISSUED: '已发放',
  INVALIDATED: '已失效'
}

const checkinLabels: Record<string, string> = {
  NOT_CHECKED_IN: '待核销',
  CHECKED_IN: '已核销',
  CANCELED: '已取消'
}

const attendanceLabels: Record<string, string> = {
  PENDING: '待到场',
  PRESENT: '已到场',
  LATE: '迟到到场',
  MANUAL_PRESENT: '人工到场',
  ABSENT: '未到场',
  CANCELED: '已取消'
}

const noShowLabels: Record<string, string> = {
  NOT_EVALUATED: '未评估爽约',
  CANDIDATE: '待确认爽约',
  CONFIRMED: '已确认爽约',
  REVOKED: '已撤销爽约'
}

const eventLabels: Record<string, string> = {
  CHECKIN_CREDENTIAL_ISSUED: '凭证发放',
  CHECKIN_VERIFIED: '核销通过',
  CHECKIN_EXCEPTION_RECORDED: '异常记录',
  CHECKIN_CREDENTIAL_INVALIDATED: '凭证失效',
  NO_SHOW_CANDIDATE_MARKED: '爽约候选',
  NO_SHOW_CONFIRMED: '确认爽约',
  NO_SHOW_REVOKED: '撤销爽约',
  RESERVATION_CANCELED: '预约取消'
}

const labelFrom = (map: Record<string, string>, status: string) => map[status] || status || '-'
const credentialStatusLabel = (status: string) => labelFrom(credentialLabels, status)
const checkinStatusLabel = (status: string) => labelFrom(checkinLabels, status)
const attendanceStatusLabel = (status: string) => labelFrom(attendanceLabels, status)
const noShowStatusLabel = (status: string) => labelFrom(noShowLabels, status)
const eventLabel = (eventType: string) => labelFrom(eventLabels, eventType)

const statusType = (status: string, kind: StatusKind): TagType => {
  if (kind === 'noshow' && status === 'CONFIRMED') return 'danger'
  if (kind === 'noshow' && status === 'CANDIDATE') return 'warning'
  if (['CHECKED_IN', 'PRESENT', 'MANUAL_PRESENT', 'ISSUED', 'NOT_EVALUATED', 'REVOKED'].includes(status)) {
    return 'success'
  }
  if (['NOT_CHECKED_IN', 'PENDING', 'CANDIDATE', 'LATE'].includes(status)) return 'warning'
  if (['INVALIDATED', 'CANCELED', 'ABSENT'].includes(status)) return 'danger'
  return 'info'
}

const formatTime = (value?: string) => {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).replace(/\//g, '-')
}

const formatWindow = (row: ReservationLifecycleResponse) => {
  if (!row.checkinWindowStart && !row.checkinWindowEnd) return '-'
  return `${formatTime(row.checkinWindowStart)} - ${formatTime(row.checkinWindowEnd)}`
}

const recentEvents = (row: ReservationLifecycleResponse): LifecycleEventResponse[] => (row.events || []).slice(0, 2)
const canManualPass = (row: ReservationLifecycleResponse) =>
  row.credentialStatus === 'ISSUED'
  && row.checkinStatus === 'NOT_CHECKED_IN'
  && row.attendanceStatus === 'PENDING'
  && row.noShowStatus !== 'CONFIRMED'
const canConfirmNoShow = (row: ReservationLifecycleResponse) => row.noShowStatus === 'CANDIDATE'
const canRevokeNoShow = (row: ReservationLifecycleResponse) => row.noShowStatus === 'CONFIRMED'
const isBusy = (reservationId: number) => actingId.value === reservationId
const isActing = (reservationId: number, type: string) => actingId.value === reservationId && actingType.value === type
const readableError = (error: unknown) => {
  // 核销失败多为后端业务拒绝（如「核销窗口尚未开启」），优先取响应体 message，避免 axios 英文报错直出。
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (responseMessage) return responseMessage
  return error instanceof Error && error.message ? error.message : '请检查网络后重试。'
}

const buildParams = () => ({
  pageNum: filters.pageNum,
  pageSize: filters.pageSize,
  ...(filters.checkinStatus ? { checkinStatus: filters.checkinStatus } : {}),
  ...(filters.noShowStatus ? { noShowStatus: filters.noShowStatus } : {}),
  ...(filters.date ? { date: filters.date } : {})
})

const load = async () => {
  loading.value = true
  try {
    const page = await adminCheckinApi.list(buildParams())
    records.value = page.records
    total.value = page.total
    loadError.value = ''
  } catch (error) {
    loadError.value = readableError(error)
  } finally {
    loading.value = false
  }
}

const search = () => {
  filters.pageNum = 1
  void load()
}

const currentDeviceRef = () => deviceRef.value.trim() || undefined

const verify = async () => {
  const value = credential.value.trim()
  if (!value) {
    ElMessage.warning('请输入核销码')
    return
  }
  verifying.value = true
  try {
    await adminCheckinApi.verify({ credential: value, deviceRef: currentDeviceRef() })
    ElMessage.success('核销通过')
    credential.value = ''
    await load()
  } catch (error) {
    ElMessage.error(readableError(error))
  } finally {
    verifying.value = false
  }
}

const promptReason = async (message: string, title: string) => {
  try {
    const { value } = await ElMessageBox.prompt(message, title, {
      confirmButtonText: '提交',
      cancelButtonText: '取消',
      inputPlaceholder: '请输入原因'
    })
    return String(value || '').trim()
  } catch {
    return ''
  }
}

const manualPass = async (reservationId: number) => {
  const reasonText = await promptReason('请输入人工放行原因', '人工放行')
  if (!reasonText) return
  actingId.value = reservationId
  actingType.value = 'pass'
  try {
    await adminCheckinApi.manualPass(reservationId, {
      reasonCode: 'MANUAL_PASS',
      reasonText,
      deviceRef: currentDeviceRef()
    })
    ElMessage.success('已人工放行')
    await load()
  } catch (error) {
    ElMessage.error(readableError(error))
  } finally {
    actingId.value = null
    actingType.value = ''
  }
}

const confirmNoShow = async (reservationId: number) => {
  const reasonText = await promptReason('请输入确认爽约原因', '确认爽约')
  if (!reasonText) return
  actingId.value = reservationId
  actingType.value = 'confirm'
  try {
    await adminCheckinApi.confirmNoShow(reservationId, {
      reasonCode: 'NO_SHOW_CONFIRMED',
      reasonText
    })
    ElMessage.success('已确认爽约')
    await load()
  } catch (error) {
    ElMessage.error(readableError(error))
  } finally {
    actingId.value = null
    actingType.value = ''
  }
}

const revokeNoShow = async (reservationId: number) => {
  const reasonText = await promptReason('请输入撤销爽约原因', '撤销爽约')
  if (!reasonText) return
  actingId.value = reservationId
  actingType.value = 'revoke'
  try {
    await adminCheckinApi.revokeNoShow(reservationId, {
      reasonCode: 'NO_SHOW_REVOKED',
      reasonText
    })
    ElMessage.success('已撤销爽约')
    await load()
  } catch (error) {
    ElMessage.error(readableError(error))
  } finally {
    actingId.value = null
    actingType.value = ''
  }
}

onMounted(load)
</script>

<style scoped>
/* 高频核销主钮弹簧按压：只做按压缩放，无位移动效以免干扰连打（预览 §08 核销台处方） */
.checkin-action-panel :deep(.el-button--primary) {
  transition: transform var(--sg-motion-base) var(--sg-ease-spring);
}

@media (prefers-reduced-motion: no-preference) {
  .checkin-action-panel :deep(.el-button--primary:active) {
    transform: scale(0.97);
  }
}

.sg-kicker {
  max-width: 420px;
}

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.checkin-console {
  display: grid;
  grid-template-columns: minmax(240px, 1.4fr) minmax(180px, 0.8fr) auto;
  gap: 10px;
  align-items: end;
}

.checkin-filter {
  grid-template-columns: repeat(4, minmax(150px, 1fr));
}

.panel-title > div,
.filter-field {
  display: grid;
  gap: 6px;
}

.filter-label {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
  font-weight: 700;
}

.filter-action,
.checkin-submit {
  display: flex;
  align-items: flex-end;
  min-height: 54px;
}

.filter-field :deep(.el-select),
.filter-field :deep(.el-date-editor),
.filter-field :deep(.el-input) {
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
.page-state span {
  display: block;
}

.page-state span {
  margin-top: 4px;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
}

.state-stack {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.cell-note {
  display: block;
  margin-top: 5px;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
  line-height: 1.45;
}

.event-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.event-list li {
  display: grid;
  grid-template-columns: 76px minmax(64px, auto) 1fr;
  gap: 8px;
  align-items: start;
  font-size: var(--sg-fz-xs);
  line-height: 1.45;
}

.event-list span,
.event-list em {
  color: var(--sg-ink-muted);
  font-style: normal;
}

@media (max-width: 900px) {
  .page-head {
    display: grid;
  }

  .checkin-console,
  .checkin-filter {
    grid-template-columns: 1fr;
    justify-content: stretch;
  }

  .checkin-submit .el-button,
  .filter-action .el-button {
    width: 100%;
  }
}

@media (max-width: 640px) {
  .page-state {
    align-items: stretch;
    flex-direction: column;
  }

  .page-state .el-button {
    width: 100%;
  }
}
</style>
