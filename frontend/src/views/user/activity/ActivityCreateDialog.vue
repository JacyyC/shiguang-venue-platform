<template>
  <el-dialog v-model="createOpen" :title="dialogTitle" width="680px" @closed="emit('closed')">
    <div class="activity-form">
      <p v-if="referenceError" class="sg-dialog-note" role="status">{{ referenceError }}</p>
      <p v-if="linkedActivity" class="activity-form-note">关联活动：{{ linkedActivity.title }} · {{ formatActivityTime(linkedActivity.startTime, linkedActivity.endTime) }}</p>
      <div class="sg-fset">
        <p class="sg-fset-cap">基本信息</p>
        <label v-if="!linkedActivity" class="sg-field"><span class="field-label">所属小组 <i class="field-req" aria-hidden="true">*</i></span>
          <SgDropdown v-model="draft.groupId" variant="block" :options="groupOptions" placeholder="选择已加入的小组" aria-label="所属小组" data-test="create-group" />
        </label>
        <div class="sg-fgrid">
          <label class="sg-field"><span class="field-label">类型 <i class="field-req" aria-hidden="true">*</i></span>
            <SgDropdown v-model="draft.activityType" variant="block" :options="activityTypeOptions" :disabled="Boolean(linkedActivity)" aria-label="类型" data-test="create-type" />
          </label>
          <label class="sg-field"><span class="field-label">{{ createKind === 'ACTIVITY' ? '活动人数' : '需求人数' }} <i class="field-req" aria-hidden="true">*</i></span>
            <el-input-number v-model="draft.capacityTotal" :min="2" :max="createKind === 'ACTIVITY' ? 1000 : 100" controls-position="right" data-test="create-capacity" />
          </label>
        </div>
        <label class="sg-field"><span class="field-label">{{ createKind === 'ACTIVITY' ? '活动标题' : '需求标题' }} <i class="field-req" aria-hidden="true">*</i></span>
          <el-input v-model.trim="draft.title" maxlength="80" show-word-limit data-test="create-title" />
        </label>
        <label class="sg-field"><span class="field-label">说明（选填）</span>
          <el-input v-model="draft.description" type="textarea" :rows="4" maxlength="1000" show-word-limit data-test="create-description" />
        </label>
      </div>
      <div v-if="!linkedActivity" class="sg-fset">
        <p class="sg-fset-cap">时间与地点</p>
        <label class="sg-field"><span class="field-label">{{ createKind === 'ACTIVITY' ? '活动时间' : '时间窗口' }} <i class="field-req" aria-hidden="true">*</i></span>
          <el-date-picker v-model="draft.timeRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" start-placeholder="开始时间" end-placeholder="结束时间" data-test="create-time" />
        </label>
        <div class="sg-field"><span class="field-label">地点类型 <i class="field-req" aria-hidden="true">*</i></span>
          <el-radio-group v-model="draft.locationType">
            <el-radio-button value="CAMPUS_AREA">校区区域</el-radio-button>
            <el-radio-button value="VENUE_SPACE">场馆空间</el-radio-button>
          </el-radio-group>
        </div>
        <div v-if="draft.locationType === 'CAMPUS_AREA'" class="sg-fgrid">
          <label class="sg-field"><span class="field-label">校区区域代码 <i class="field-req" aria-hidden="true">*</i></span>
            <el-input v-model.trim="draft.areaCode" maxlength="64" placeholder="例如 NORTH_CAMPUS" data-test="create-area" />
          </label>
        </div>
        <div v-else class="sg-fgrid">
          <label class="sg-field"><span class="field-label">场馆 <i class="field-req" aria-hidden="true">*</i></span>
            <SgDropdown v-model="draft.venueId" variant="block" :options="venueOptions" placeholder="选择场馆" aria-label="场馆" data-test="create-venue" @change="onVenueChange" />
          </label>
          <label class="sg-field"><span class="field-label">空间 <i class="field-req" aria-hidden="true">*</i></span>
            <SgDropdown v-model="draft.spaceId" variant="block" :options="spaceOptions" :disabled="spacesLoading" placeholder="选择空间" aria-label="空间" data-test="create-space" />
          </label>
          <label class="sg-field"><span class="field-label">关联我的预约（选填）</span>
            <SgDropdown
              :model-value="draft.reservationId ?? ''"
              variant="block"
              :options="reservationOptions"
              aria-label="关联我的预约"
              data-test="create-reservation"
              @update:model-value="onReservationPick"
            />
          </label>
        </div>
      </div>
      <div v-if="formError" class="sg-state-error" role="alert" data-test="create-error"><span>{{ formError }}</span><button type="button" class="retry" @click="createTarget">重试</button></div>
    </div>
    <template #footer>
      <div class="sg-dialog-foot">
        <button type="button" class="sg-btn-ghost sg-btn-sm" :disabled="submitting" @click="createOpen = false">取消</button>
        <button type="button" class="sg-btn-primary sg-btn-sm" data-test="create-submit" :disabled="submitting || !canCreate" @click="createTarget">{{ submitting ? '提交中…' : '创建草稿' }}</button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import SgDropdown from '@/components/SgDropdown.vue'
import { activityApi, type ActivityType, type CommunityActivity } from '@/api/activity'
import { communityApi, communityError, createIdempotencyRegistry } from '@/api/community'
import { reservationApi } from '@/api/reservation'
import type { Reservation, Venue, VenueSpace } from '@/api/types'
import { venueApi } from '@/api/venue'
import { formatActivityTime } from '@/utils/activityPresentation'

/**
 * 活动/同行发起草稿弹窗（AR-02 共用件）。
 *
 * 从既有 `ActivityHubView` 原样抽出：载荷、成员校验（只列 ACTIVE 小组）、地点与关联预约
 * 规则、幂等 registry 与成功后深链跳转都保持原合同，新旧两个入口共用同一份实现，
 * 避免出现第二套创建业务逻辑。调用方只需在入口按钮或深链消费处调用 `open(kind, linked)`。
 */
export type CreateKind = 'ACTIVITY' | 'COMPANION_REQUEST'

interface DraftState {
  groupId?: number
  activityType: ActivityType
  title: string
  description: string
  timeRange: string[]
  capacityTotal: number
  locationType: 'CAMPUS_AREA' | 'VENUE_SPACE'
  areaCode: string
  venueId?: number
  spaceId?: number
  reservationId?: number
}

const emit = defineEmits<{ closed: []; created: [CreateKind, number] }>()
const router = useRouter()

const createOpen = ref(false)
const createKind = ref<CreateKind>('ACTIVITY')
const linkedActivity = ref<CommunityActivity | null>(null)
const submitting = ref(false)
const formError = ref('')
const writes = createIdempotencyRegistry()
const venues = ref<Venue[]>([])
const groups = ref<Array<{ id: number; name: string; currentMemberStatus?: string }>>([])
const reservations = ref<Reservation[]>([])
const spaces = ref<VenueSpace[]>([])
const spacesLoading = ref(false)
const referenceError = ref('')
/** 参考数据只在首次打开时拉取；上一次拉取不完整时下次打开重试，避免每次开窗三次请求。 */
const referenceLoaded = ref(false)
const draft = reactive<DraftState>({
  activityType: 'STUDY',
  title: '',
  description: '',
  timeRange: [],
  capacityTotal: 4,
  locationType: 'CAMPUS_AREA',
  areaCode: ''
})

const dialogTitle = computed(() => createKind.value === 'ACTIVITY'
  ? '发起活动'
  : linkedActivity.value ? '为活动发布找搭子' : '发布找搭子')

const activityTypeOptions: Array<{ value: ActivityType; label: string }> = [
  { value: 'STUDY', label: '学习自习' },
  { value: 'SPORTS', label: '运动健身' },
  { value: 'BOARD_GAME', label: '桌游娱乐' },
  { value: 'WORKSHOP', label: '主题工作坊' },
  { value: 'OTHER', label: '其他活动' }
]

const activeGroups = computed(() => groups.value.filter((group) => group.currentMemberStatus === 'ACTIVE'))
const groupOptions = computed(() => activeGroups.value.map((group) => ({ value: group.id, label: group.name })))
const venueOptions = computed(() => venues.value.map((venue) => ({ value: venue.id, label: venue.name })))
const spaceOptions = computed(() => spaces.value.map((space) => ({ value: space.id, label: space.name })))
const reservationLabel = (reservation: Reservation) =>
  `${formatActivityTime(reservation.startTime, reservation.endTime)} · ${reservation.participantCount || 1} 人`
const eligibleReservations = computed(() => reservations.value.filter((reservation) => {
  if (!draft.venueId || !draft.spaceId || draft.timeRange.length !== 2) return false
  const participantCount = reservation.participantCount || 0
  return ['APPROVED', 'PENDING_PAYMENT', 'PAID', 'REFUNDING'].includes(reservation.status)
    && reservation.venueId === draft.venueId
    && reservation.spaceId === draft.spaceId
    && reservation.startTime <= draft.timeRange[0]
    && reservation.endTime >= draft.timeRange[1]
    && participantCount >= draft.capacityTotal
}))
const reservationOptions = computed(() => [
  { value: '', label: '不关联（地点待确认）' },
  ...eligibleReservations.value.map((reservation) => ({ value: reservation.id, label: reservationLabel(reservation) }))
])

const canCreate = computed(() => Boolean(
  (linkedActivity.value || draft.groupId)
  && draft.title.trim()
  && draft.capacityTotal >= 2
  && (linkedActivity.value || (
    draft.timeRange.length === 2
    && (draft.locationType === 'CAMPUS_AREA'
      ? draft.areaCode.trim()
      : draft.venueId && draft.spaceId)
  ))
))

/** 关联预约可反选：空值选项映射回 undefined，提交载荷语义与原 clearable 一致。 */
const onVenueChange = (value: string | number) => {
  void loadSpaces(Number(value))
}
const onReservationPick = (value: string | number) => {
  draft.reservationId = value === '' ? undefined : Number(value)
}

const resetDraft = () => {
  Object.assign(draft, {
    groupId: undefined,
    activityType: 'STUDY',
    title: '',
    description: '',
    timeRange: [],
    capacityTotal: 4,
    locationType: 'CAMPUS_AREA',
    areaCode: '',
    venueId: undefined,
    spaceId: undefined,
    reservationId: undefined
  })
  spaces.value = []
  formError.value = ''
}

const loadReferenceData = async () => {
  const [groupResult, venueResult, reservationResult] = await Promise.allSettled([
    communityApi.groups({ limit: 50 }),
    venueApi.list({ pageNum: 1, pageSize: 100 }),
    reservationApi.my({ pageNum: 1, pageSize: 100 })
  ])
  groups.value = groupResult.status === 'fulfilled' ? groupResult.value.records : []
  venues.value = venueResult.status === 'fulfilled' ? venueResult.value.records : []
  reservations.value = reservationResult.status === 'fulfilled' ? reservationResult.value.records : []
  const failed = [groupResult, venueResult, reservationResult].some((result) => result.status === 'rejected')
  referenceError.value = failed ? '部分选项暂时无法加载，可关闭后重试' : ''
  referenceLoaded.value = !failed
}

const loadSpaces = async (venueId?: number) => {
  draft.spaceId = undefined
  draft.reservationId = undefined
  spaces.value = []
  if (!venueId) return
  spacesLoading.value = true
  try {
    spaces.value = await venueApi.spaces(venueId)
  } catch (caught) {
    formError.value = communityError(caught, '空间列表加载失败').message
  } finally {
    spacesLoading.value = false
  }
}

/**
 * 打开弹窗。`linked` 非空表示「为这场活动发布同行」：小组与类型锁定为活动自身，
 * 时间沿用活动时间，地点交由服务端按活动地点承诺处理。
 */
const open = async (kind: CreateKind, linked: CommunityActivity | null = null) => {
  resetDraft()
  createKind.value = kind
  linkedActivity.value = linked
  if (linked) {
    draft.groupId = linked.groupId
    draft.activityType = linked.activityType
    draft.timeRange = [linked.startTime, linked.endTime]
  }
  createOpen.value = true
  if (!referenceLoaded.value) await loadReferenceData()
}

const locationPayload = () => draft.locationType === 'CAMPUS_AREA'
  ? { type: 'CAMPUS_AREA' as const, areaCode: draft.areaCode.trim().toUpperCase() }
  : {
      type: 'VENUE_SPACE' as const,
      venueId: draft.venueId,
      spaceId: draft.spaceId,
      reservationId: draft.reservationId
    }

const createTarget = async () => {
  if (!canCreate.value || submitting.value) return
  submitting.value = true
  formError.value = ''
  try {
    const base = {
      groupId: linkedActivity.value?.groupId || draft.groupId as number,
      activityType: linkedActivity.value?.activityType || draft.activityType,
      title: draft.title.trim(),
      description: draft.description.trim() || undefined,
      capacityTotal: draft.capacityTotal
    }
    if (createKind.value === 'ACTIVITY') {
      const payload = {
        ...base,
        startTime: draft.timeRange[0],
        endTime: draft.timeRange[1],
        location: locationPayload()
      }
      const created = await activityApi.createActivity(payload, writes.keyFor('activity-create', payload))
      writes.complete('activity-create')
      createOpen.value = false
      emit('created', 'ACTIVITY', created.id)
      await router.push(`/activities/${created.id}`)
    } else {
      const payload = linkedActivity.value
        ? { ...base, activityId: linkedActivity.value.id }
        : { ...base, windowStart: draft.timeRange[0], windowEnd: draft.timeRange[1], location: locationPayload() }
      const created = await activityApi.createCompanion(payload, writes.keyFor('companion-create', payload))
      writes.complete('companion-create')
      createOpen.value = false
      emit('created', 'COMPANION_REQUEST', created.id)
      await router.push(`/companion-requests/${created.id}`)
    }
  } catch (caught) {
    formError.value = communityError(caught, '创建失败，请检查填写内容后重试').message
  } finally {
    submitting.value = false
  }
}

defineExpose({ open, createTarget, draft, canCreate, submitting, formError, createOpen })
</script>

<style scoped>
.activity-form {
  display: grid;
  gap: 14px;
}

.activity-form-note {
  margin: 0;
  padding: 10px 12px;
  border-left: 3px solid var(--sg-warning);
  background: var(--sg-warning-soft);
  color: var(--sg-warning);
  font-size: var(--sg-fz-sm);
}
</style>
