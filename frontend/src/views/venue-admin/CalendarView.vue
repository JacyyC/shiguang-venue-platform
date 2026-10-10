<template>
  <section class="admin-page">
    <div class="page-head">
      <div>
        <p class="sg-kicker">空间运营 · 运营排期</p>
        <h1 class="sg-display-l page-title">场馆日历</h1>
        <p class="section-desc">按场馆、场地和日期查看库存时段，集中处理不可售、人工保留和缓冲。</p>
      </div>
      <div class="toolbar-actions">
        <el-button :icon="'Refresh'" @click="loadInventory">刷新</el-button>
        <el-button v-if="canManageCalendar" type="primary" :disabled="!canOperate" @click="openBlockDialog">
          <el-icon><Plus /></el-icon>
          新增不可售
        </el-button>
      </div>
    </div>

    <div class="filter-bar calendar-filter" aria-label="库存筛选">
      <div class="filter-field">
        <span class="filter-label">场馆</span>
        <el-select v-model="venueId" aria-label="选择场馆" placeholder="选择场馆" @change="handleVenueChange">
          <el-option v-for="venue in venues" :key="venue.id" :label="venue.name" :value="venue.id" />
        </el-select>
      </div>
      <div class="filter-field">
        <span class="filter-label">场地</span>
        <el-select v-model="spaceId" aria-label="选择场地" placeholder="选择场地" @change="loadInventory">
          <el-option v-for="space in spaces" :key="space.id" :label="space.name" :value="space.id" />
        </el-select>
      </div>
      <div class="filter-field">
        <span class="filter-label">运营日期</span>
        <el-date-picker
          v-model="selectedDate"
          aria-label="选择运营日期"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          placeholder="选择日期"
          @change="loadInventory"
        />
      </div>
      <div class="filter-action"><el-button type="primary" :loading="calendarLoading" :disabled="!canOperate" @click="loadInventory">查询库存</el-button></div>
    </div>

    <div v-if="loadError" class="page-state" role="alert">
      <div><strong>库存日历加载失败</strong><span>{{ loadError }}</span></div>
      <el-button :loading="calendarLoading" @click="loadInventory">重试</el-button>
    </div>
    <el-alert
      v-else-if="!calendarLoading && !venues.length"
      type="warning"
      title="当前账号没有可管理场馆"
      description="请联系系统管理员配置场馆权限。"
      :closable="false"
      show-icon
    />

    <div class="kpi-grid inventory-summary">
      <div v-for="item in summaryCards" :key="item.key" class="data-panel kpi-card inventory-kpi">
        <span>{{ item.label }}</span>
        <strong><SgCountNumber :value="item.value" /></strong>
        <small>{{ item.minutes }} 分钟</small>
      </div>
    </div>

    <section v-if="canManageCalendar" class="data-panel business-calendar-panel sg-focus-in">
      <div class="panel-title">
        <h2>营业日历</h2>
        <span class="muted-text">{{ selectedDate }}</span>
      </div>
      <el-form class="business-calendar-form" label-position="top" :model="calendarForm" @submit.prevent="saveCalendar">
        <el-form-item label="营业状态">
          <el-select v-model="calendarForm.businessStatus">
            <el-option label="正常营业" value="OPEN" />
            <el-option label="临时闭馆" value="CLOSED" />
            <el-option label="内部使用" value="INTERNAL_ONLY" />
            <el-option label="特殊时段" value="SPECIAL_HOURS" />
          </el-select>
        </el-form-item>
        <el-form-item label="开放时间">
          <el-time-picker v-model="calendarForm.openTime" value-format="HH:mm:ss" format="HH:mm" placeholder="选择开放时间" />
        </el-form-item>
        <el-form-item label="关闭时间">
          <el-time-picker v-model="calendarForm.closeTime" value-format="HH:mm:ss" format="HH:mm" placeholder="选择关闭时间" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="calendarForm.reasonText" placeholder="节假日、校内活动或临时安排" />
        </el-form-item>
        <div class="calendar-save-action">
          <el-button type="primary" :loading="savingCalendar" :disabled="!venueId" @click="saveCalendar">保存营业日历</el-button>
        </div>
      </el-form>
    </section>

    <section class="data-panel inventory-grid-panel sg-focus-in">
      <div class="panel-title">
        <h2>当日时段</h2>
        <span class="muted-text">{{ currentSpaceName }}</span>
      </div>
      <div v-loading="calendarLoading" class="slot-grid" aria-label="库存时段列表">
        <article v-for="slot in slots" :key="`${slot.spaceId}-${slot.startTime}`" class="slot-cell sg-slot-cell" :class="slotClass(slot.status)">
          <div class="slot-time">{{ timeRange(slot.startTime, slot.endTime) }}</div>
          <StatusTag :value="slot.status" kind="slot" />
          <p>{{ slot.reasonText || slot.label }}</p>
        </article>
        <div v-if="!calendarLoading && !slots.length" class="empty-inline">暂无库存时段</div>
      </div>
    </section>

    <section class="desktop-table data-panel">
      <div class="panel-title">
        <h2>当日不可售</h2>
        <span class="muted-text">{{ blocks.length }} 条</span>
      </div>
      <el-table v-loading="blocksLoading" :data="blocks" empty-text="暂无不可售记录">
        <el-table-column label="类型" width="120">
          <template #default="{ row }">{{ blockTypeText(row.blockType) }}</template>
        </el-table-column>
        <el-table-column prop="reasonText" label="原因" min-width="180" />
        <el-table-column label="开始时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="缓冲" width="120">
          <template #default="{ row }">{{ row.bufferBeforeMinutes || 0 }} / {{ row.bufferAfterMinutes || 0 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ row.status === 'ACTIVE' ? '生效中' : '已取消' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canManageCalendar && row.status === 'ACTIVE'" size="small" :loading="cancelingBlockId === row.id" :disabled="cancelingBlockId !== null" @click="cancelBlock(row.id)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-if="canManageCalendar" v-model="blockDialogVisible" title="新增不可售时段" width="min(620px, calc(100% - 24px))">
      <el-alert
        title="不可售时段会立即影响库存展示"
        description="请核对场馆、场地和时间范围，并填写可追溯的业务说明。"
        type="info"
        :closable="false"
        show-icon
      />
      <el-form label-position="top" :model="blockForm" @submit.prevent="saveBlock">
        <el-form-item label="不可售类型">
          <el-select v-model="blockForm.blockType">
            <el-option label="人工保留" value="MANUAL_HOLD" />
            <el-option label="维护" value="MAINTENANCE" />
            <el-option label="包场" value="PRIVATE_EVENT" />
            <el-option label="布场撤场" value="SETUP" />
            <el-option label="清洁" value="CLEANING" />
            <el-option label="闭馆" value="CLOSURE" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务原因">
          <el-select v-model="blockForm.reasonCode">
            <el-option label="人工保留" value="MANUAL_HOLD" />
            <el-option label="维护" value="MAINTENANCE" />
            <el-option label="包场" value="PRIVATE_EVENT" />
            <el-option label="布场撤场" value="SETUP_TEARDOWN" />
            <el-option label="清洁缓冲" value="CLEANING_BUFFER" />
            <el-option label="场馆关闭" value="VENUE_CLOSED" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间"><el-date-picker v-model="blockForm.startTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" format="YYYY-MM-DD HH:mm" /></el-form-item>
        <el-form-item label="结束时间"><el-date-picker v-model="blockForm.endTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" format="YYYY-MM-DD HH:mm" /></el-form-item>
        <div class="buffer-row">
          <el-form-item label="前置缓冲分钟">
            <el-input-number v-model="blockForm.bufferBeforeMinutes" :min="0" :step="5" />
          </el-form-item>
          <el-form-item label="后置缓冲分钟">
            <el-input-number v-model="blockForm.bufferAfterMinutes" :min="0" :step="5" />
          </el-form-item>
        </div>
        <el-form-item label="说明">
          <el-input v-model="blockForm.reasonText" type="textarea" :rows="3" placeholder="例如：校队训练、布场撤场、清洁消杀" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="form-actions">
          <el-button @click="blockDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="savingBlock" :disabled="!canOperate || !canManageCalendar" @click="saveBlock">保存不可售</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inventoryAdminApi } from '@/api/inventory'
import { venueAdminApi, venueApi } from '@/api/venue'
import { useAuthStore } from '@/stores/auth'
import type {
  InventoryCalendarResponse,
  InventorySlot,
  InventorySummary,
  SaveUnavailableBlockRequest,
  Venue,
  VenueBusinessCalendar,
  VenueSpace,
  VenueUnavailableBlock
} from '@/api/types'
import StatusTag from '@/components/StatusTag.vue'
import SgCountNumber from '@/components/SgCountNumber.vue'

type BlockForm = Required<Pick<
  SaveUnavailableBlockRequest,
  'blockType' | 'reasonCode' | 'reasonText' | 'startTime' | 'endTime' | 'bufferBeforeMinutes' | 'bufferAfterMinutes'
>>
type CalendarForm = {
  businessStatus: string
  openTime: string
  closeTime: string
  reasonText: string
}

const today = () => new Date().toISOString().slice(0, 10)
const defaultSummary = (): InventorySummary => ({
  availableSlots: 0,
  reservedSlots: 0,
  unavailableSlots: 0,
  manualHoldSlots: 0,
  closedSlots: 0,
  bufferSlots: 0,
  availableMinutes: 0,
  reservedMinutes: 0,
  unavailableMinutes: 0,
  manualHoldMinutes: 0,
  closedMinutes: 0,
  bufferMinutes: 0
})
const defaultBlockForm = (date = today()): BlockForm => ({
  blockType: 'MANUAL_HOLD',
  reasonCode: 'MANUAL_HOLD',
  reasonText: '',
  startTime: `${date}T09:00:00`,
  endTime: `${date}T10:00:00`,
  bufferBeforeMinutes: 0,
  bufferAfterMinutes: 0
})
const defaultCalendarForm = (): CalendarForm => ({
  businessStatus: 'OPEN',
  openTime: '08:00:00',
  closeTime: '18:00:00',
  reasonText: ''
})

const venues = ref<Venue[]>([])
const spaces = ref<VenueSpace[]>([])
const slots = ref<InventorySlot[]>([])
const blocks = ref<VenueUnavailableBlock[]>([])
const summary = ref<InventorySummary>(defaultSummary())
const venueId = ref<number | undefined>()
const spaceId = ref<number | undefined>()
const selectedDate = ref(today())
const calendarLoading = ref(false)
const blocksLoading = ref(false)
const blockDialogVisible = ref(false)
const savingBlock = ref(false)
const savingCalendar = ref(false)
const cancelingBlockId = ref<number | null>(null)
const loadError = ref('')
const blockForm = ref<BlockForm>(defaultBlockForm(selectedDate.value))
const calendarForm = ref<CalendarForm>(defaultCalendarForm())
const auth = useAuthStore()

const canOperate = computed(() => Boolean(venueId.value && spaceId.value && selectedDate.value))
const canManageCalendar = computed(() => auth.hasPermission('venue:calendar:manage'))
const readableError = (error: unknown) => error instanceof Error && error.message ? error.message : '请检查网络后重试。'
const currentSpaceName = computed(() => spaces.value.find((space) => space.id === spaceId.value)?.name || '未选择场地')
const summaryCards = computed(() => [
  { key: 'available', label: '可售', value: summary.value.availableSlots, minutes: summary.value.availableMinutes || summary.value.availableSlots * 60 },
  { key: 'reserved', label: '已售', value: summary.value.reservedSlots, minutes: summary.value.reservedMinutes || summary.value.reservedSlots * 60 },
  { key: 'unavailable', label: '不可售', value: summary.value.unavailableSlots, minutes: summary.value.unavailableMinutes || summary.value.unavailableSlots * 60 },
  { key: 'manualHold', label: '人工保留', value: summary.value.manualHoldSlots, minutes: summary.value.manualHoldMinutes || summary.value.manualHoldSlots * 60 },
  { key: 'buffer', label: '缓冲', value: summary.value.bufferSlots, minutes: summary.value.bufferMinutes || summary.value.bufferSlots * 60 },
  { key: 'closed', label: '关闭', value: summary.value.closedSlots, minutes: summary.value.closedMinutes || summary.value.closedSlots * 60 }
])

const loadSpaces = async () => {
  if (!venueId.value) {
    spaces.value = []
    spaceId.value = undefined
    return
  }
  spaces.value = await venueApi.spaces(venueId.value)
  if (!spaces.value.some((space) => space.id === spaceId.value)) {
    spaceId.value = spaces.value[0]?.id
  }
}

const loadBlocks = async () => {
  if (!canOperate.value || !venueId.value || !spaceId.value) {
    blocks.value = []
    return
  }
  blocksLoading.value = true
  try {
    blocks.value = await inventoryAdminApi.blocks({
      venueId: venueId.value,
      spaceId: spaceId.value,
      date: selectedDate.value
    })
  } finally {
    blocksLoading.value = false
  }
}

const applyBusinessCalendar = (calendar?: VenueBusinessCalendar | null) => {
  if (!calendar) {
    calendarForm.value = defaultCalendarForm()
    return
  }
  calendarForm.value = {
    businessStatus: calendar.businessStatus || 'OPEN',
    openTime: calendar.openTime || '',
    closeTime: calendar.closeTime || '',
    reasonText: calendar.reasonText || ''
  }
}

const loadInventory = async () => {
  if (!canOperate.value || !venueId.value || !spaceId.value) {
    slots.value = []
    blocks.value = []
    summary.value = defaultSummary()
    applyBusinessCalendar(null)
    return
  }
  calendarLoading.value = true
  try {
    const result: InventoryCalendarResponse = await inventoryAdminApi.calendar({
      venueId: venueId.value,
      spaceId: spaceId.value,
      date: selectedDate.value
    })
    slots.value = result.slots || []
    summary.value = { ...defaultSummary(), ...(result.summary || {}) }
    applyBusinessCalendar(result.businessCalendar)
    await loadBlocks()
    loadError.value = ''
  } catch (error) {
    loadError.value = readableError(error)
  } finally {
    calendarLoading.value = false
  }
}

const handleVenueChange = async () => {
  await loadSpaces()
  await loadInventory()
}

const openBlockDialog = () => {
  if (!canManageCalendar.value) {
    return
  }
  blockForm.value = defaultBlockForm(selectedDate.value)
  blockDialogVisible.value = true
}

const saveBlock = async () => {
  if (!canManageCalendar.value) {
    return
  }
  if (!venueId.value || !spaceId.value) {
    ElMessage.warning('请选择场馆和场地')
    return
  }
  if (!blockForm.value.reasonText.trim()) {
    ElMessage.warning('请输入不可售说明')
    return
  }
  savingBlock.value = true
  try {
    await inventoryAdminApi.createBlock({
      venueId: venueId.value,
      spaceId: spaceId.value,
      blockType: blockForm.value.blockType,
      reasonCode: blockForm.value.reasonCode,
      reasonText: blockForm.value.reasonText.trim(),
      startTime: blockForm.value.startTime,
      endTime: blockForm.value.endTime,
      bufferBeforeMinutes: blockForm.value.bufferBeforeMinutes,
      bufferAfterMinutes: blockForm.value.bufferAfterMinutes
    })
    ElMessage.success('不可售时段已新增')
    blockDialogVisible.value = false
    await loadInventory()
  } finally {
    savingBlock.value = false
  }
}

const saveCalendar = async () => {
  if (!canManageCalendar.value) {
    return
  }
  if (!venueId.value) {
    ElMessage.warning('请选择场馆')
    return
  }
  savingCalendar.value = true
  try {
    const status = calendarForm.value.businessStatus
    await inventoryAdminApi.saveCalendar({
      venueId: venueId.value,
      calendarDate: selectedDate.value,
      businessStatus: status,
      openTime: calendarForm.value.openTime || undefined,
      closeTime: calendarForm.value.closeTime || undefined,
      reasonCode: status,
      reasonText: calendarForm.value.reasonText.trim()
    })
    ElMessage.success('营业日历已保存')
    await loadInventory()
  } finally {
    savingCalendar.value = false
  }
}

const cancelBlock = async (id: number) => {
  if (!canManageCalendar.value) {
    return
  }
  cancelingBlockId.value = id
  try {
    await ElMessageBox.confirm('确认取消该不可售时段？', '取消不可售', {
      type: 'warning',
      confirmButtonText: '确认取消',
      cancelButtonText: '关闭'
    })
    await inventoryAdminApi.cancelBlock(id, '管理员取消不可售')
    ElMessage.success('不可售时段已取消')
    await loadInventory()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      throw error
    }
  } finally {
    cancelingBlockId.value = null
  }
}

const timeRange = (start: string, end: string) => `${start?.slice(11, 16) || '--:--'}-${end?.slice(11, 16) || '--:--'}`
const formatDateTime = (value: string) => value ? value.replace('T', ' ').slice(0, 16) : ''
const blockTypeText = (value: string) => ({
  MAINTENANCE: '维护',
  CLOSURE: '闭馆',
  PRIVATE_EVENT: '包场',
  SETUP: '布场',
  CLEANING: '清洁',
  MANUAL_HOLD: '人工保留',
  WEATHER: '天气保留',
  INTERNAL_ONLY: '内部使用'
}[value] || value)
const slotClass = (status: string) => ({
  'slot-available': status === 'AVAILABLE',
  'slot-reserved': status === 'RESERVED',
  'slot-unavailable': status === 'UNAVAILABLE' || status === 'MANUAL_HOLD',
  'slot-buffer': status === 'BUFFER',
  'slot-closed': status === 'CLOSED'
})

watch(selectedDate, (date) => {
  blockForm.value = defaultBlockForm(date || today())
})

onMounted(async () => {
  venues.value = await venueAdminApi.manageable()
  venueId.value = venues.value[0]?.id
  await loadSpaces()
  await loadInventory()
})
</script>

<style scoped>
.sg-kicker {
  max-width: 420px;
}

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.calendar-filter {
  grid-template-columns: minmax(180px, 1.2fr) minmax(180px, 1.2fr) minmax(160px, 0.8fr) minmax(120px, 0.5fr);
}

.filter-field {
  display: grid;
  gap: 6px;
}

.filter-label {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
  font-weight: 700;
}

.filter-action {
  display: flex;
  align-items: flex-end;
  min-height: 54px;
}

.filter-field :deep(.el-select),
.filter-field :deep(.el-date-editor),
/* 营业日历表单块降次级分层：次级表面、无主卡阴影（预览 §08 日历处方） */
.business-calendar-panel {
  background: var(--sg-surface-subtle);
  box-shadow: none;
  border: 0;
}

.business-calendar-form :deep(.el-date-editor),
:deep(.el-dialog .el-date-editor) {
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

:deep(.el-dialog .el-alert) + .el-form {
  margin-top: 16px;
}

.inventory-summary {
  grid-template-columns: repeat(6, minmax(118px, 1fr));
}

.inventory-kpi {
  min-height: 104px;
  padding: 14px;
}

.inventory-kpi strong {
  margin-top: 8px;
  font-family: var(--sg-font-serif);
  font-size: 26px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.inventory-kpi small {
  display: block;
  margin-top: 6px;
  color: var(--sg-ink-muted);
  font-weight: 700;
}

.inventory-grid-panel {
  overflow: hidden;
}

.slot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 10px;
  min-height: 120px;
}

.slot-cell {
  min-height: 112px;
  padding: 12px;
}

.slot-cell p {
  min-height: 34px;
  margin: 8px 0 0;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.slot-time {
  margin-bottom: 8px;
  color: var(--sg-ink);
  font-size: var(--sg-fz-base);
  font-weight: 800;
}

.slot-available {
  background: var(--sg-success-soft);
}

.slot-reserved {
  background: var(--sg-pine-soft);
}

.slot-unavailable {
  background: var(--sg-danger-soft);
}

.slot-buffer {
  background: var(--sg-surface-subtle);
}

.slot-closed {
  background: var(--sg-border-soft);
}

.empty-inline {
  grid-column: 1 / -1;
  padding: 28px;
  color: var(--sg-ink-muted);
  text-align: center;
}

.buffer-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.business-calendar-form {
  display: grid;
  grid-template-columns: minmax(140px, 0.8fr) repeat(2, minmax(120px, 0.7fr)) minmax(180px, 1.2fr) auto;
  gap: 12px;
  align-items: end;
}

.calendar-save-action {
  display: flex;
  align-items: end;
  min-height: 58px;
}

@media (max-width: 1200px) {
  .calendar-filter,
  .inventory-summary,
  .buffer-row,
  .business-calendar-form {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .filter-action .el-button,
  .page-state .el-button {
    width: 100%;
  }

  .page-state {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
