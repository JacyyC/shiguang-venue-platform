<template>
  <section class="admin-page">
    <div class="page-head">
      <div>
        <p class="sg-kicker">空间运营 · 资源维护</p>
        <h1 class="sg-display-l page-title">场地管理</h1>
        <p class="section-desc">维护场地基础信息、启停状态和临时维护时段，避免用户选择不可用资源。</p>
      </div>
      <div class="toolbar-actions">
        <el-button
          v-if="canCreateVenue"
          :type="venueId ? 'default' : 'primary'"
          :loading="savingVenue"
          @click="openVenueCreate"
        >
          <el-icon><Plus /></el-icon>
          新增场馆
        </el-button>
        <el-button v-if="venueId" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          新增场地
        </el-button>
      </div>
    </div>
    <div v-if="venues.length" class="filter-bar spaces-filter" aria-label="场地筛选">
      <div class="filter-field">
        <span class="filter-label">当前场馆</span>
        <el-select v-model="venueId" aria-label="当前场馆" placeholder="选择场馆" @change="handleVenueChange">
          <el-option v-for="venue in venues" :key="venue.id" :label="venue.name" :value="venue.id" />
        </el-select>
      </div>
      <div class="filter-action"><el-button type="primary" :loading="loading" :disabled="!venueId" @click="load">查询场地</el-button></div>
    </div>

    <div v-if="loadError" class="page-state" role="alert">
      <div><strong>场地数据加载失败</strong><span>{{ loadError }}</span></div>
      <el-button :loading="loading" @click="load">重试</el-button>
    </div>
    <el-alert
      v-else-if="!loading && !venues.length"
      type="warning"
      :title="canCreateVenue ? '尚未创建场馆' : '尚未分配可管理场馆'"
      :description="canCreateVenue
        ? '请先新增首个场馆，再维护该场馆的场地和可售库存。'
        : '场馆管理员需要由系统管理员分配具体场馆范围后，才能查询或维护场地。'"
      :closable="false"
      show-icon
    />
    <div v-else class="desktop-table data-panel">
      <div class="panel-title">
        <div><h2>场地列表</h2><span class="muted-text">{{ spaces.length }} 个场地</span></div>
      </div>
      <el-table v-if="loading || spaces.length" v-loading="loading" :data="spaces" empty-text="暂无场地">
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="type" label="类型" width="130" />
        <el-table-column prop="code" label="编号" width="120" />
        <el-table-column label="价格/小时" width="120">
          <template #default="{ row }">￥{{ Number(row.pricePerHour || 0).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="capacity" label="物理容量" width="100" />
        <el-table-column label="资源类型" width="120">
          <template #default="{ row }">{{ resourceCategoryLabel(row.resourceRule?.resourceCategory) }}</template>
        </el-table-column>
        <el-table-column label="预订方式" width="120">
          <template #default="{ row }">{{ bookingModeLabel(row.resourceRule?.bookingMode) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <StatusTag :value="row.status === 'ACTIVE' ? 'AVAILABLE' : 'MAINTENANCE'" kind="slot" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="390" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" @click="openResourceRule(row)">预订规则</el-button>
              <el-button size="small" :loading="togglingId === row.id" :disabled="togglingId !== null" @click="toggleStatus(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
              <el-button size="small" @click="openMaintenance(row)">维护时段</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else compact title="暂无场地" description="该场馆暂未配置场地" />
    </div>

    <el-dialog v-model="venueDialogVisible" title="新增场馆" width="min(560px, calc(100% - 24px))">
      <el-form :model="venueForm" label-position="top">
        <el-form-item label="场馆名称" required>
          <el-input v-model="venueForm.name" maxlength="128" placeholder="例如：东区综合体育馆" />
        </el-form-item>
        <el-form-item label="所在区域">
          <el-input v-model="venueForm.area" maxlength="64" placeholder="例如：东区" />
        </el-form-item>
        <el-form-item label="详细地址">
          <el-input v-model="venueForm.address" maxlength="255" placeholder="例如：东区体育路 1 号" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="venueForm.phone" maxlength="32" placeholder="场馆服务电话" />
        </el-form-item>
        <el-form-item label="营业时间">
          <div class="venue-hours">
            <el-time-picker v-model="venueForm.openTime" value-format="HH:mm:ss" format="HH:mm" placeholder="开放时间" />
            <span aria-hidden="true">至</span>
            <el-time-picker v-model="venueForm.closeTime" value-format="HH:mm:ss" format="HH:mm" placeholder="关闭时间" />
          </div>
        </el-form-item>
        <el-form-item label="场馆说明">
          <el-input v-model="venueForm.description" type="textarea" :rows="3" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="form-actions">
          <el-button :disabled="savingVenue" @click="venueDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="savingVenue" @click="saveVenue">创建场馆</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="spaceDialogVisible" :title="spaceForm.id ? '编辑场地' : '新增场地'" width="min(560px, calc(100% - 24px))">
      <el-form :key="spaceForm.id ?? 'create'" :model="spaceForm" label-position="top">
        <el-form-item label="场馆">
          <el-select v-model="spaceForm.venueId" placeholder="选择场馆">
            <el-option v-for="venue in venues" :key="venue.id" :label="venue.name" :value="venue.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="spaceForm.name" /></el-form-item>
        <el-form-item label="类型"><el-input v-model="spaceForm.type" /></el-form-item>
        <el-form-item label="编号"><el-input v-model="spaceForm.code" /></el-form-item>
        <el-form-item label="价格/小时"><el-input-number v-model="spaceForm.pricePerHour" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="容量"><el-input-number v-model="spaceForm.capacity" :min="0" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="spaceForm.status">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="停用" value="MAINTENANCE" />
          </el-select>
        </el-form-item>
        <el-form-item label="设施说明"><el-input v-model="spaceForm.facilityText" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="spaceForm.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <div class="form-actions">
          <el-button @click="spaceDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="savingSpace" @click="saveSpace">保存</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="resourceRuleDialogVisible" :title="resourceRuleTitle" width="min(680px, calc(100% - 24px))">
      <el-alert
        title="规则变更会创建新版本"
        description="已有预约继续使用历史快照；存在未来有效预约时，后端会拒绝切换模式、缩容或变更座位。"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-skeleton v-if="resourceRuleLoading" animated :rows="7" />
      <div v-else-if="resourceRuleLoadError" class="resource-rule-load-state" role="alert">
        <div>
          <strong>预订规则加载失败</strong>
          <span>{{ resourceRuleLoadError }}</span>
        </div>
        <el-button @click="loadResourceRule">重试</el-button>
      </div>
      <el-form v-else :model="resourceRuleForm" label-position="top" class="resource-rule-form">
        <el-form-item label="资源类型" required>
          <el-select v-model="resourceRuleForm.resourceCategory" aria-label="资源类型">
            <el-option label="体育场地" value="SPORTS_FIELD" />
            <el-option label="自习空间" value="STUDY_ROOM" />
            <el-option label="活动空间" value="ACTIVITY_ROOM" />
          </el-select>
        </el-form-item>
        <el-form-item label="预订方式" required>
          <el-radio-group v-model="resourceRuleForm.bookingMode" aria-label="预订方式">
            <el-radio-button value="EXCLUSIVE">整空间</el-radio-button>
            <el-radio-button value="CAPACITY">按人数</el-radio-button>
            <el-radio-button value="ASSIGNED_SEAT">指定座位</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="resourceRuleForm.bookingMode === 'CAPACITY'" label="可售容量" required>
          <el-input-number v-model="resourceRuleForm.capacityLimit" :min="1" :max="10000" aria-label="可售容量" />
          <span class="field-help">这里是共享预约的库存上限，不读取基础信息中的物理容量。</span>
        </el-form-item>
        <el-form-item v-if="resourceRuleForm.bookingMode === 'ASSIGNED_SEAT'" label="座位" required>
          <div class="seat-editor">
            <div v-for="(seat, index) in resourceRuleForm.seats" :key="index" class="seat-editor-row">
              <el-input v-model="seat.seatCode" maxlength="64" :aria-label="`座位 ${index + 1} 编码`" placeholder="编码，如 A-01" />
              <el-input v-model="seat.seatLabel" maxlength="128" :aria-label="`座位 ${index + 1} 名称`" placeholder="名称，如 A区01座" />
              <el-button
                circle
                :icon="'Delete'"
                :aria-label="`删除座位 ${index + 1}`"
                title="删除座位"
                @click="removeSeat(index)"
              />
            </div>
            <el-button :icon="'Plus'" @click="addSeat">添加座位</el-button>
          </div>
        </el-form-item>
        <el-form-item label="变更原因" required>
          <el-input v-model="resourceRuleForm.reasonText" type="textarea" :rows="3" maxlength="255" show-word-limit />
        </el-form-item>
        <p v-if="resourceRuleError" class="rule-error" role="alert">{{ resourceRuleError }}</p>
      </el-form>
      <template #footer>
        <div class="form-actions">
          <el-button :disabled="savingResourceRule" @click="resourceRuleDialogVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="savingResourceRule"
            :disabled="resourceRuleLoading || Boolean(resourceRuleLoadError)"
            @click="saveResourceRule"
          >保存新版本</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="maintenanceDialogVisible" :title="maintenanceTitle" width="min(860px, calc(100% - 24px))">
      <el-alert
        title="维护时段会影响用户可预约库存"
        description="请选择连续空闲时段并填写真实原因，已售时段不会在此处变更。"
        type="info"
        :closable="false"
        show-icon
      />
      <el-form :model="maintenanceForm" label-position="top" class="maintenance-form">
        <el-form-item label="维护日期">
          <el-date-picker
            v-model="maintenanceDate"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
            @change="loadMaintenanceSlots"
          />
        </el-form-item>
        <el-form-item label="选择维护时段">
          <div v-loading="maintenanceSlotsLoading" class="maintenance-picker">
            <TimeSlotGrid
              v-if="maintenanceSlots.length"
              :slots="maintenanceSlots"
              :selected="selectedMaintenanceSlots"
              :show-price="false"
              aria-label="可维护时段"
              @toggle="toggleMaintenanceSlot"
            />
            <EmptyState v-else compact title="暂无可选时段" description="该日期下没有可预约时段" />
          </div>
        </el-form-item>
        <div class="maintenance-summary">
          <span v-if="selectedMaintenanceSlots.length">已选择 {{ selectedMaintenanceLabel }}</span>
          <span v-else>请选择需要维护的连续空闲时段</span>
          <el-button size="small" :disabled="!selectedMaintenanceSlots.length" @click="clearMaintenanceSelection">清空</el-button>
        </div>
        <el-form-item label="维护原因"><el-input v-model="maintenanceForm.reason" placeholder="例如：灯光检修、地板保养" /></el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="savingMaintenance"
            :disabled="!selectedSpace || !selectedMaintenanceSlots.length"
            @click="createMaintenance"
          >
            新增维护时段
          </el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="maintenanceTableLoading" :data="maintenances" empty-text="暂无维护时段">
        <el-table-column label="开始时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="180" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="sg-tag" :class="row.status === 'ACTIVE' ? 'sg-tag--success' : 'sg-tag--neutral'">{{ row.status === 'ACTIVE' ? '生效中' : '已取消' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="row.status === 'ACTIVE'" size="small" @click="cancelMaintenance(row.id)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { venueApi, venueAdminApi } from '@/api/venue'
import { reservationApi } from '@/api/reservation'
import type {
  AvailabilitySlot,
  BookingMode,
  ResourceCategory,
  ResourceRule,
  Venue,
  VenueMaintenance,
  VenueSpace
} from '@/api/types'
import StatusTag from '@/components/StatusTag.vue'
import TimeSlotGrid from '@/components/TimeSlotGrid.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useMenuStore } from '@/stores/menu'

type SpaceForm = {
  id?: number
  venueId?: number
  name: string
  type: string
  code: string
  capacity?: number
  pricePerHour: number
  facilityText: string
  description: string
  status: string
}

type VenueForm = {
  name: string
  area: string
  address: string
  phone: string
  openTime: string
  closeTime: string
  description: string
  status: string
}

type ResourceRuleForm = {
  resourceCategory: ResourceCategory
  bookingMode: BookingMode
  capacityLimit?: number
  seats: Array<{ seatCode: string; seatLabel: string }>
  reasonText: string
}

const menu = useMenuStore()
const venueId = ref<number | undefined>()
const venues = ref<Venue[]>([])
const spaces = ref<VenueSpace[]>([])
const maintenances = ref<VenueMaintenance[]>([])
const maintenanceSlots = ref<AvailabilitySlot[]>([])
const selectedMaintenanceSlots = ref<AvailabilitySlot[]>([])
const selectedSpace = ref<VenueSpace | null>(null)
const resourceRuleSpace = ref<VenueSpace | null>(null)
const loading = ref(false)
const savingVenue = ref(false)
const savingSpace = ref(false)
const savingMaintenance = ref(false)
const savingResourceRule = ref(false)
const togglingId = ref<number | null>(null)
const loadError = ref('')
const maintenanceSlotsLoading = ref(false)
const maintenanceTableLoading = ref(false)
const resourceRuleLoading = ref(false)
const spaceDialogVisible = ref(false)
const venueDialogVisible = ref(false)
const maintenanceDialogVisible = ref(false)
const resourceRuleDialogVisible = ref(false)
const resourceRuleError = ref('')
const resourceRuleLoadError = ref('')
const maintenanceDate = ref(new Date().toISOString().slice(0, 10))
const maintenanceForm = reactive({ reason: '' })
const resourceRuleForm = reactive<ResourceRuleForm>({
  resourceCategory: 'SPORTS_FIELD',
  bookingMode: 'EXCLUSIVE',
  capacityLimit: 1,
  seats: [],
  reasonText: ''
})
const spaceForm = reactive<SpaceForm>({
  venueId: undefined,
  name: '',
  type: 'BADMINTON',
  code: '',
  capacity: undefined,
  pricePerHour: 40,
  facilityText: '',
  description: '',
  status: 'ACTIVE'
})
const venueForm = reactive<VenueForm>({
  name: '',
  area: '',
  address: '',
  phone: '',
  openTime: '08:00:00',
  closeTime: '22:00:00',
  description: '',
  status: 'ACTIVE'
})
const canCreateVenue = computed(() => menu.hasPermission('system:view'))
const maintenanceTitle = computed(() => (selectedSpace.value ? `维护时段 - ${selectedSpace.value.name}` : '维护时段'))
const resourceRuleTitle = computed(() => resourceRuleSpace.value ? `预订规则 - ${resourceRuleSpace.value.name}` : '预订规则')
const readableError = (error: unknown) => {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (responseMessage) return responseMessage
  return error instanceof Error && error.message ? error.message : '请检查网络后重试。'
}
const resourceCategoryLabel = (category?: string) => ({
  SPORTS_FIELD: '体育场地',
  STUDY_ROOM: '自习空间',
  ACTIVITY_ROOM: '活动空间'
}[category || 'SPORTS_FIELD'] || '体育场地')
const bookingModeLabel = (mode?: string) => ({
  EXCLUSIVE: '整空间',
  CAPACITY: '按人数',
  ASSIGNED_SEAT: '指定座位'
}[mode || 'EXCLUSIVE'] || '整空间')
const selectedMaintenanceLabel = computed(() => {
  const sorted = sortedMaintenanceSlots()
  if (!sorted.length) return ''
  return `${sorted[0].startTime.slice(0, 16)} - ${sorted[sorted.length - 1].endTime.slice(11, 16)} 共 ${sorted.length} 段`
})

const resetSpaceForm = () => {
  spaceForm.id = undefined
  spaceForm.venueId = venueId.value
  spaceForm.name = ''
  spaceForm.type = 'BADMINTON'
  spaceForm.code = ''
  spaceForm.capacity = undefined
  spaceForm.pricePerHour = 40
  spaceForm.facilityText = ''
  spaceForm.description = ''
  spaceForm.status = 'ACTIVE'
}

const resetVenueForm = () => {
  venueForm.name = ''
  venueForm.area = ''
  venueForm.address = ''
  venueForm.phone = ''
  venueForm.openTime = '08:00:00'
  venueForm.closeTime = '22:00:00'
  venueForm.description = ''
  venueForm.status = 'ACTIVE'
}

const load = async () => {
  if (!venueId.value) {
    spaces.value = []
    return
  }
  loading.value = true
  try {
    spaces.value = await venueApi.spaces(venueId.value)
    loadError.value = ''
  } catch (error) {
    loadError.value = readableError(error)
  } finally {
    loading.value = false
  }
}

const handleVenueChange = async () => {
  spaceForm.venueId = venueId.value
  await load()
}

const openCreate = () => {
  resetSpaceForm()
  spaceDialogVisible.value = true
}

const openVenueCreate = () => {
  resetVenueForm()
  venueDialogVisible.value = true
}

const saveVenue = async () => {
  if (!canCreateVenue.value) {
    ElMessage.warning('仅系统管理员可以创建场馆')
    return
  }
  if (!venueForm.name.trim()) {
    ElMessage.warning('请输入场馆名称')
    return
  }
  if (venueForm.openTime && venueForm.closeTime && venueForm.openTime >= venueForm.closeTime) {
    ElMessage.warning('场馆开放时间必须早于关闭时间')
    return
  }
  savingVenue.value = true
  try {
    const venue = await venueAdminApi.createVenue({
      name: venueForm.name.trim(),
      area: venueForm.area.trim() || undefined,
      address: venueForm.address.trim() || undefined,
      phone: venueForm.phone.trim() || undefined,
      openTime: venueForm.openTime || undefined,
      closeTime: venueForm.closeTime || undefined,
      description: venueForm.description.trim() || undefined,
      status: venueForm.status
    })
    venues.value = [...venues.value.filter((item) => item.id !== venue.id), venue].sort((left, right) => left.id - right.id)
    venueId.value = venue.id
    spaceForm.venueId = venue.id
    spaces.value = []
    venueDialogVisible.value = false
    ElMessage.success('场馆已创建，现在可以新增场地')
    await load()
  } catch (error) {
    ElMessage.error(readableError(error))
  } finally {
    savingVenue.value = false
  }
}

const openEdit = (space: VenueSpace) => {
  spaceForm.id = space.id
  spaceForm.venueId = space.venueId
  spaceForm.name = space.name
  spaceForm.type = space.type
  spaceForm.code = space.code || ''
  spaceForm.capacity = space.capacity
  spaceForm.pricePerHour = Number(space.pricePerHour || 0)
  spaceForm.facilityText = space.facilityText || ''
  spaceForm.description = space.description || ''
  spaceForm.status = space.status || 'ACTIVE'
  spaceDialogVisible.value = true
}

const applyResourceRule = (rule: ResourceRule) => {
  resourceRuleForm.resourceCategory = rule.resourceCategory
  resourceRuleForm.bookingMode = rule.bookingMode
  resourceRuleForm.capacityLimit = rule.bookingMode === 'CAPACITY' ? rule.capacityLimit : 1
  resourceRuleForm.seats = rule.seats.map((seat) => ({
    seatCode: seat.seatCode,
    seatLabel: seat.seatLabel
  }))
  resourceRuleForm.reasonText = ''
}

const resetResourceRuleForm = () => {
  resourceRuleForm.resourceCategory = 'SPORTS_FIELD'
  resourceRuleForm.bookingMode = 'EXCLUSIVE'
  resourceRuleForm.capacityLimit = 1
  resourceRuleForm.seats = []
  resourceRuleForm.reasonText = ''
}

const loadResourceRule = async () => {
  if (!resourceRuleSpace.value) return
  resourceRuleLoading.value = true
  resourceRuleError.value = ''
  resourceRuleLoadError.value = ''
  try {
    applyResourceRule(await venueAdminApi.resourceRule(resourceRuleSpace.value.id))
  } catch (error) {
    resourceRuleLoadError.value = readableError(error)
  } finally {
    resourceRuleLoading.value = false
  }
}

const openResourceRule = async (space: VenueSpace) => {
  resourceRuleSpace.value = space
  resetResourceRuleForm()
  resourceRuleDialogVisible.value = true
  await loadResourceRule()
}

const addSeat = () => {
  resourceRuleForm.seats.push({ seatCode: '', seatLabel: '' })
}

const removeSeat = (index: number) => {
  resourceRuleForm.seats.splice(index, 1)
}

const saveResourceRule = async () => {
  if (!resourceRuleSpace.value || resourceRuleLoadError.value) return
  const reasonText = resourceRuleForm.reasonText.trim()
  if (!reasonText) {
    resourceRuleError.value = '请填写规则变更原因'
    return
  }
  if (resourceRuleForm.bookingMode === 'CAPACITY'
    && (!resourceRuleForm.capacityLimit || resourceRuleForm.capacityLimit < 1)) {
    resourceRuleError.value = '按人数预订必须填写正整数可售容量'
    return
  }
  const seats = resourceRuleForm.bookingMode === 'ASSIGNED_SEAT'
    ? resourceRuleForm.seats.map((seat) => ({
        seatCode: seat.seatCode.trim(),
        seatLabel: seat.seatLabel.trim()
      }))
    : []
  if (resourceRuleForm.bookingMode === 'ASSIGNED_SEAT'
    && (!seats.length || seats.some((seat) => !seat.seatCode || !seat.seatLabel))) {
    resourceRuleError.value = '指定座位模式至少需要一个完整的座位编码和名称'
    return
  }
  try {
    await ElMessageBox.confirm(
      '保存后会生成新的资源规则版本。若存在未来有效预约，危险切换将被后端拒绝。确认继续？',
      '保存预订规则',
      {
        type: 'warning',
        confirmButtonText: '保存新版本',
        cancelButtonText: '返回检查'
      }
    )
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    throw error
  }
  savingResourceRule.value = true
  resourceRuleError.value = ''
  try {
    const rule = await venueAdminApi.configureResourceRule(resourceRuleSpace.value.id, {
      resourceCategory: resourceRuleForm.resourceCategory,
      bookingMode: resourceRuleForm.bookingMode,
      ...(resourceRuleForm.bookingMode === 'CAPACITY'
        ? { capacityLimit: resourceRuleForm.capacityLimit }
        : {}),
      seats,
      reasonText
    })
    resourceRuleSpace.value.resourceRule = rule
    resourceRuleDialogVisible.value = false
    ElMessage.success('预订规则新版本已生效')
    await load()
  } catch (error) {
    resourceRuleError.value = readableError(error)
  } finally {
    savingResourceRule.value = false
  }
}

const saveSpace = async () => {
  if (!spaceForm.venueId) {
    ElMessage.warning('请选择场馆')
    return
  }
  if (!spaceForm.name.trim()) {
    ElMessage.warning('请输入场地名称')
    return
  }
  savingSpace.value = true
  try {
    const payload = { ...spaceForm, name: spaceForm.name.trim() }
    if (spaceForm.id) {
      await venueAdminApi.updateSpace(spaceForm.id, payload)
      ElMessage.success('场地已更新')
    } else {
      await venueAdminApi.createSpace(payload)
      ElMessage.success('场地已新增')
    }
    spaceDialogVisible.value = false
    venueId.value = spaceForm.venueId
    await load()
  } finally {
    savingSpace.value = false
  }
}

const toggleStatus = async (space: VenueSpace) => {
  const nextStatus = space.status === 'ACTIVE' ? 'MAINTENANCE' : 'ACTIVE'
  if (nextStatus !== 'ACTIVE') {
    await ElMessageBox.confirm('停用后该场地将不再作为正常可用资源展示，确认继续？', '停用场地', {
      type: 'warning',
      confirmButtonText: '确认停用',
      cancelButtonText: '返回'
    })
  }
  togglingId.value = space.id
  try {
    await venueAdminApi.updateSpaceStatus(space.id, nextStatus)
    ElMessage.success(nextStatus === 'ACTIVE' ? '场地已启用' : '场地已停用')
    await load()
  } finally {
    togglingId.value = null
  }
}

const openMaintenance = async (space: VenueSpace) => {
  selectedSpace.value = space
  maintenanceDate.value = new Date().toISOString().slice(0, 10)
  selectedMaintenanceSlots.value = []
  maintenanceSlots.value = []
  maintenanceForm.reason = ''
  maintenanceDialogVisible.value = true
  await Promise.all([loadMaintenances(), loadMaintenanceSlots()])
}

const loadMaintenances = async () => {
  if (!selectedSpace.value) {
    maintenances.value = []
    return
  }
  maintenanceTableLoading.value = true
  try {
    maintenances.value = await venueAdminApi.maintenances(selectedSpace.value.id)
  } finally {
    maintenanceTableLoading.value = false
  }
}

const loadMaintenanceSlots = async () => {
  if (!selectedSpace.value || !maintenanceDate.value) {
    maintenanceSlots.value = []
    selectedMaintenanceSlots.value = []
    return
  }
  maintenanceSlotsLoading.value = true
  try {
    maintenanceSlots.value = await reservationApi.availability({
      spaceId: selectedSpace.value.id,
      date: maintenanceDate.value
    })
    selectedMaintenanceSlots.value = []
  } finally {
    maintenanceSlotsLoading.value = false
  }
}

const sortedMaintenanceSlots = () =>
  [...selectedMaintenanceSlots.value].sort((left, right) => left.startTime.localeCompare(right.startTime))

const toggleMaintenanceSlot = (slot: AvailabilitySlot) => {
  const selected = selectedMaintenanceSlots.value
  const exists = selected.some((item) => item.spaceId === slot.spaceId && item.startTime === slot.startTime)
  const next = exists
    ? selected.filter((item) => !(item.spaceId === slot.spaceId && item.startTime === slot.startTime))
    : [...selected, slot]
  const sorted = [...next].sort((left, right) => left.startTime.localeCompare(right.startTime))
  for (let index = 1; index < sorted.length; index += 1) {
    if (sorted[index - 1].endTime !== sorted[index].startTime) {
      ElMessage.warning('请选择连续时段')
      return
    }
  }
  selectedMaintenanceSlots.value = sorted
}

const clearMaintenanceSelection = () => {
  selectedMaintenanceSlots.value = []
}

const createMaintenance = async () => {
  const selected = sortedMaintenanceSlots()
  if (!selectedSpace.value || !selected.length) {
    ElMessage.warning('请选择维护时段')
    return
  }
  if (!maintenanceForm.reason.trim()) {
    ElMessage.warning('请输入维护原因')
    return
  }
  savingMaintenance.value = true
  try {
    await venueAdminApi.createMaintenance(selectedSpace.value.id, {
      startTime: selected[0].startTime,
      endTime: selected[selected.length - 1].endTime,
      reason: maintenanceForm.reason.trim()
    })
    ElMessage.success('维护时段已新增')
    selectedMaintenanceSlots.value = []
    maintenanceForm.reason = ''
    await Promise.all([loadMaintenances(), loadMaintenanceSlots()])
  } finally {
    savingMaintenance.value = false
  }
}

const cancelMaintenance = async (id: number) => {
  try {
    await ElMessageBox.confirm('确认取消该维护时段？', '取消维护时段', {
      type: 'warning',
      confirmButtonText: '确认取消',
      cancelButtonText: '关闭'
    })
    await venueAdminApi.cancelMaintenance(id)
    ElMessage.success('维护时段已取消')
    await Promise.all([loadMaintenances(), loadMaintenanceSlots()])
  } catch (error) {
    if (error === 'cancel' || error === 'close') {
      ElMessage.info('已保留维护时段')
    } else {
      throw error
    }
  }
}

const formatDateTime = (value: string) => value ? value.replace('T', ' ').slice(0, 16) : ''

onMounted(async () => {
  try {
    venues.value = await venueAdminApi.manageable()
    venueId.value = venues.value[0]?.id
    spaceForm.venueId = venueId.value
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

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.maintenance-form {
  margin-bottom: 12px;
}

.resource-rule-form {
  margin-top: 16px;
}

.resource-rule-load-state {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 16px;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--sg-danger) 28%, transparent);
  border-radius: var(--sg-radius-control);
  background: var(--sg-danger-soft);
}

.resource-rule-load-state strong,
.resource-rule-load-state span {
  display: block;
}

.resource-rule-load-state span {
  margin-top: 4px;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
}

.resource-rule-form :deep(.el-select),
.resource-rule-form :deep(.el-radio-group) {
  width: 100%;
}

.resource-rule-form :deep(.el-radio-button) {
  flex: 1;
}

.field-help {
  display: block;
  width: 100%;
  margin-top: 6px;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
  line-height: 1.5;
}

.seat-editor {
  display: grid;
  gap: 8px;
  width: 100%;
}

.seat-editor-row {
  display: grid;
  grid-template-columns: minmax(120px, 0.8fr) minmax(160px, 1.2fr) 40px;
  gap: 8px;
  align-items: center;
}

.seat-editor-row :deep(.el-button) {
  width: 40px;
  height: 40px;
}

.rule-error {
  margin: 0;
  color: var(--sg-danger);
  font-size: var(--sg-fz-sm);
  line-height: 1.5;
}

.venue-hours {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  width: 100%;
}

.spaces-filter {
  grid-template-columns: minmax(220px, 1fr) auto;
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

.filter-action {
  display: flex;
  align-items: flex-end;
  min-height: 54px;
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

:deep(.el-alert) + .maintenance-form {
  margin-top: 16px;
}

.maintenance-picker {
  width: 100%;
  min-height: 120px;
}

.maintenance-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: -2px 0 12px;
  padding: 10px 12px;
  border: 1px solid var(--sg-border);
  border-radius: var(--sg-radius-control);
  background: var(--sg-surface-subtle);
  color: var(--sg-ink-muted);
}

.maintenance-summary span:first-child {
  color: var(--sg-ink);
  font-weight: 700;
}

@media (max-width: 640px) {
  .spaces-filter {
    grid-template-columns: 1fr;
  }

  .filter-action .el-button,
  .page-state .el-button,
  .resource-rule-load-state .el-button {
    width: 100%;
  }

  .page-state,
  .resource-rule-load-state,
  .maintenance-summary {
    align-items: stretch;
    flex-direction: column;
  }

  .seat-editor-row {
    grid-template-columns: 1fr 44px;
  }

  .seat-editor-row :deep(.el-input:nth-child(2)) {
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .seat-editor-row :deep(.el-button) {
    grid-column: 2;
    grid-row: 1;
    width: 44px;
    height: 44px;
  }
}
</style>
