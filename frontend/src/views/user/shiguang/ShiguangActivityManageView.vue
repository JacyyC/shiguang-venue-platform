<template>
  <div class="sgv-activity">
    <div class="shell page-arrive">
      <div class="breadcrumb">
        <router-link class="text-button" to="/me/time?tab=owned"><span class="icon"><el-icon><Back /></el-icon></span>我的发起</router-link>
        <router-link v-if="item" class="text-button" :to="detailRoute">查看{{ isActivity ? '活动' : '邀约' }}页面<span class="icon"><el-icon><TopRight /></el-icon></span></router-link>
      </div>

      <div v-if="loadError && !item" class="empty-state" role="alert" data-test="load-error">
        <div class="empty-art"><ShiguangArt scene="together" /></div>
        <div>
          <h2>这场相聚暂时管理不了。</h2>
          <p>{{ loadError }}</p>
          <button class="button" type="button" @click="loadAll"><span class="icon"><el-icon><Refresh /></el-icon></span>重新加载</button>
        </div>
      </div>
      <div v-else-if="!item" class="empty-state" role="status" data-test="loading">
        <div class="empty-art"><ShiguangArt scene="quiet" /></div>
        <div><h2>正在打开管理面板。</h2><p>活动与邀约正在加载。</p></div>
      </div>

      <template v-else>
        <div class="page-heading">
          <div>
            <h1>{{ isDraft ? '相聚，先写在这里' : '照顾好这场相聚' }}<span class="dot-stop">.</span></h1>
            <p>{{ isDraft ? '草稿已保存。准备好后，再把邀请发出去。' : '回应每一个申请，也留意时间与地点的变化。' }}</p>
          </div>
          <div class="heading-actions">
            <button v-if="isDraft" class="button primary" type="button" data-test="publish" @click="confirmPublish">
              发布{{ isActivity ? '活动' : '邀约' }}<span class="icon"><el-icon><ArrowRight /></el-icon></span>
            </button>
            <button v-else-if="isActivity" class="button" type="button" data-test="create-linked" @click="openLinkedCreate">
              <span class="icon"><el-icon><Plus /></el-icon></span>为活动找同行
            </button>
            <button v-else class="button" type="button" data-test="matches-toggle" @click="showMatches = !showMatches">
              <span class="icon"><el-icon><Connection /></el-icon></span>{{ showMatches ? '收起匹配' : '看看匹配的搭子' }}
            </button>
            <button class="button" type="button" data-test="edit-location" @click="openLocationEditor">
              <span class="icon"><el-icon><Location /></el-icon></span>调整地点
            </button>
          </div>
        </div>

        <div class="manage-summary">
          <div>
            <p class="eyebrow">{{ item.groupName || '所属小组' }} · {{ statusLabel(item.status) }}<template v-if="commitmentStatus !== 'CONFIRMED'"><span class="location-pending"> · 地点待确认</span></template></p>
            <h2>{{ item.title }}</h2>
            <p class="muted" style="font-size:13px;margin-top:8px">{{ dayTextOf(startTime) }} {{ timeRange }} · {{ placeTitle }}</p>
          </div>
          <div class="metric"><strong data-test="pending-metric">{{ metricsPending }}</strong><span>等待回应</span></div>
          <div class="metric"><strong>{{ item.participantCount }}</strong><span>已加入</span></div>
          <div class="metric"><strong>{{ item.capacityTotal }}</strong><span>人数上限</span></div>
        </div>

        <section class="manage-section">
          <div class="section-heading">
            <h2>{{ isDraft ? '发布之前，再确认一下' : '回应这些想来的人' }}</h2>
            <span>{{ isDraft ? `${kindText}草稿` : `${metricsPending} 条待处理` }}</span>
          </div>

          <template v-if="isDraft">
            <div class="facts-grid">
              <div class="fact-block">
                <span class="fact-label"><span class="icon"><el-icon><Calendar /></el-icon></span>{{ kindText }}时间</span>
                <strong>{{ dayTextOf(startTime) }}</strong>
                <p>{{ timeRange }}</p>
              </div>
              <div class="fact-block">
                <span class="fact-label"><span class="icon"><el-icon><Location /></el-icon></span>{{ kindText }}地点</span>
                <strong>{{ placeTitle }}</strong>
                <p>{{ placeDetail }}</p>
                <span class="status" :class="commitmentTone">{{ commitmentText }}</span>
              </div>
            </div>
            <div class="content-copy" style="margin-top:26px">{{ item.description || '这场相聚的介绍还在路上。' }}</div>
          </template>

          <template v-else>
            <div v-if="applications.length">
              <div v-for="application in applications" :key="application.id" class="application-row" data-test="application-row">
                <span class="avatar" :class="`color-${application.id % 4}`">{{ avatarText(memberName(application.applicantUserId)) }}</span>
                <div class="application-person">
                  <strong>{{ memberName(application.applicantUserId) }}</strong>
                  <p>想加入这场{{ isActivity ? '活动' : '邀约' }} · {{ appliedAtText(application.appliedAt) }}</p>
                </div>
                <div class="application-actions">
                  <template v-if="application.status === 'PENDING'">
                    <button class="text-button" type="button" :disabled="commandBusy" @click="confirmReject(application)">暂不接受</button>
                    <button class="button small" type="button" data-test="accept" :disabled="commandBusy || item.participantCount >= item.capacityTotal || item.status !== 'PUBLISHED'" @click="confirmAccept(application)">
                      <span class="icon"><el-icon><Check /></el-icon></span>接受申请
                    </button>
                  </template>
                  <span v-else class="status" :class="application.status === 'ACCEPTED' ? '' : 'neutral'">{{ application.status === 'ACCEPTED' ? '已接受' : '未接受' }}</span>
                </div>
              </div>
              <div class="directory-more">
                <button v-if="applicationsHasMore" class="text-button" type="button" data-test="load-more-applications" :disabled="applicationsLoading" @click="loadMoreApplications">
                  {{ applicationsLoading ? '正在加载…' : '继续查看更多申请' }}
                </button>
                <span v-else>已经看到全部申请</span>
              </div>
            </div>
            <p v-else class="content-copy">暂时还没有收到申请。</p>
            <p v-if="applicationsError" class="muted micro-hint" role="status">{{ applicationsError }}</p>
          </template>
        </section>

        <section v-if="showMatches && !isActivity && item" class="manage-extra" data-test="matches">
          <div class="section-heading">
            <h2>这些同行需求，节奏很近</h2>
            <span>{{ matchPage?.ruleVersion || 'COMPANION_MATCH_V1' }}</span>
          </div>
          <div v-if="matches.length">
            <div v-for="match in matches" :key="match.decisionId" class="match-candidate">
              <div>
                <strong>{{ match.candidate.title }}</strong>
                <p class="muted micro-hint">{{ match.candidate.groupName || '' }} · {{ matchReasonText(match.reasonCodes) }}</p>
              </div>
              <router-link class="text-button" :to="`/companion-requests/${match.candidate.id}`">查看<span class="icon"><el-icon><ArrowRight /></el-icon></span></router-link>
            </div>
          </div>
          <p v-else class="content-copy">还没有匹配到相近的同行需求，稍后可以再刷新看看。</p>
          <button class="text-button" type="button" :disabled="matchesBusy" @click="refreshMatches">
            <span class="icon"><el-icon><Refresh /></el-icon></span>{{ matchesBusy ? '正在刷新…' : '刷新匹配' }}
          </button>
        </section>

        <div class="manage-lifecycle">
          <p>{{ isDraft ? '发布后，其他人可以查看活动并提出申请。' : '计划有变化时，及时更新这场相聚。' }}</p>
          <div class="heading-actions">
            <button v-if="!isDraft && ['PUBLISHED', 'FULL'].includes(item.status)" class="text-button" type="button" data-test="close" @click="confirmClose">关闭报名</button>
            <button class="text-button accent" type="button" data-test="cancel" :disabled="['CANCELED', 'CLOSED'].includes(item.status)" @click="confirmCancel">{{ isDraft ? '放下这份草稿' : '取消活动' }}</button>
          </div>
        </div>
      </template>
    </div>

    <el-dialog v-model="locationDialogOpen" width="470px" title="调整地点">
      <div class="sgv-activity">
        <div class="filter-fields">
          <label class="filter-field">
            <span>地点方式</span>
            <select v-model="locationDraft.type" data-test="location-type">
              <option value="CAMPUS_AREA">先约一个区域 · 具体地点待确认</option>
              <option value="VENUE_SPACE">关联已有场地预约</option>
            </select>
          </label>
          <label v-if="locationDraft.type === 'CAMPUS_AREA'" class="filter-field">
            <span>集合区域</span>
            <select v-model="locationDraft.areaCode">
              <option v-for="area in AREA_OPTIONS" :key="area.value" :value="area.value">{{ area.label }}</option>
            </select>
          </label>
          <label v-else class="filter-field">
            <span>选择已有预约（时间与人数须覆盖本场）</span>
            <select v-model="locationDraft.reservationId" data-test="reservation-select">
              <option v-for="reservation in reservations" :key="reservation.id" :value="reservation.id">
                {{ reservation.label }}
              </option>
            </select>
          </label>
        </div>
        <p v-if="locationError" role="alert" style="color:var(--accent);font-size:13px;margin-top:12px">{{ locationError }}</p>
        <div class="filter-dialog-actions">
          <button class="button" type="button" @click="locationDialogOpen = false">再想想</button>
          <button class="button primary" type="button" data-test="save-location" :disabled="commandBusy" @click="saveLocation">保存地点</button>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="confirmDialogOpen" width="470px">
      <template #header><span class="eyebrow">拾光 · 活动与同行</span></template>
      <div class="sgv-activity">
        <h2 style="font-size:24px;margin-bottom:12px">{{ confirmAction.title }}</h2>
        <p class="muted" style="line-height:1.9;font-size:14px">{{ confirmAction.body }}</p>
        <p v-if="confirmError" role="alert" style="color:var(--accent);font-size:13px">{{ confirmError }}</p>
        <div class="filter-dialog-actions">
          <button class="button" type="button" @click="confirmDialogOpen = false">再想想</button>
          <button class="button primary" type="button" data-test="confirm-action" :disabled="commandBusy" @click="runConfirm">{{ confirmAction.label }}<span class="icon"><el-icon><ArrowRight /></el-icon></span></button>
        </div>
      </div>
    </el-dialog>

    <ActivityCreateDialog ref="createDialog" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { activityApi } from '@/api/activity'
import type { ActivityApplication, CommunityActivity, CompanionRequest } from '@/api/activity'
import { communityApi } from '@/api/community'
import { communityError, createIdempotencyRegistry } from '@/api/community'
import { directoryApi } from '@/api/directory'
import { reservationApi } from '@/api/reservation'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import ActivityCreateDialog from '@/views/user/activity/ActivityCreateDialog.vue'
import { campusAreaLabel, matchReasonLabel, formatActivityTime } from '@/utils/activityPresentation'
import { useAuthStore } from '@/stores/auth'
import { fetchVenueName, fetchSpaceName } from '@/utils/entityNames'

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '可申请', FULL: '名额已满',
  CLOSED: '报名已关闭', CANCELED: '已取消', EXPIRED: '已结束'
}

const AREA_OPTIONS = [
  { value: 'NORTH_CAMPUS', label: '北校区' },
  { value: 'SOUTH_CAMPUS', label: '南校区' },
  { value: 'MAIN_CAMPUS', label: '主校区' }
]

const route = useRoute()
const auth = useAuthStore()
const registry = createIdempotencyRegistry()

const item = ref<CommunityActivity | CompanionRequest | null>(null)
const metricsPending = ref(0)
const applications = ref<ActivityApplication[]>([])
const applicationsCursor = ref<number | null>(null)
const applicationsHasMore = ref(false)
const applicationsLoading = ref(false)
const applicationsError = ref('')
const memberNicknames = ref(new Map<number, string>())
const showMatches = ref(false)
const matches = ref<Array<{ decisionId: number; candidate: CompanionRequest; reasonCodes: string[]; ruleVersion: string }>>([])
const matchPage = ref<{ records: unknown[]; ruleVersion: string } | null>(null)
const matchesBusy = ref(false)
const reservations = ref<Array<{ id: number; label: string; venueId: number; spaceId: number }>>([])
const locationDialogOpen = ref(false)
const locationError = ref('')
const locationDraft = reactive<{ type: 'CAMPUS_AREA' | 'VENUE_SPACE'; areaCode: string; reservationId: number | null }>({
  type: 'CAMPUS_AREA', areaCode: 'NORTH_CAMPUS', reservationId: null
})
const commandBusy = ref(false)
const confirmDialogOpen = ref(false)
const confirmError = ref('')
const confirmAction = ref<{ title: string; body: string; label: string; run: () => Promise<void> }>({
  title: '', body: '', label: '', run: async () => undefined
})
const loading = ref(true)
const loadError = ref('')
const createDialog = ref<InstanceType<typeof ActivityCreateDialog> | null>(null)

const isActivity = computed(() => route.name === 'ActivityManage')
const currentId = computed(() => Number(route.params.id))
const isOwner = computed(() => {
  if (!item.value) return false
  const me = auth.user?.id
  if (me == null) return false
  return isActivity.value
    ? (item.value as CommunityActivity).organizerUserId === me
    : (item.value as CompanionRequest).ownerUserId === me
})
const location = computed(() => item.value?.location ?? null)
const venueName = ref<string | null>(null)
const spaceName = ref<string | null>(null)
const commitmentStatus = computed(() => location.value?.commitmentStatus ?? 'UNCONFIRMED')

const isDraft = computed(() => item.value?.status === 'DRAFT')
const kindText = computed(() => (isActivity.value ? '城市活动' : '同行邀约'))
const detailRoute = computed(() => isActivity.value ? `/activities/${currentId.value}` : `/companion-requests/${currentId.value}`)

const startTime = computed(() => item.value
  ? (isActivity.value ? (item.value as CommunityActivity).startTime : (item.value as CompanionRequest).windowStart) : '')
const endTime = computed(() => item.value
  ? (isActivity.value ? (item.value as CommunityActivity).endTime : (item.value as CompanionRequest).windowEnd) : '')
const timeRange = computed(() => `${timeTextOf(startTime.value)}–${timeTextOf(endTime.value)}`)

const placeTitle = computed(() => {
  if (!item.value) return ''
  if (location.value?.venueId) return spaceName.value || venueName.value || '场地空间'
  return location.value?.areaCode ? campusAreaLabel(location.value.areaCode) : '地点待确认'
})
const placeDetail = computed(() => [spaceName.value, location.value?.areaCode ? campusAreaLabel(location.value.areaCode) : ''].filter(Boolean).join(' · '))
const commitmentText = computed(() => {
  const status = commitmentStatus.value
  if (status === 'CONFIRMED') return '场地已确认'
  if (status === 'DEGRADED') return '地点有变，待重新确认'
  return '地点待确认'
})
const commitmentTone = computed(() => {
  const status = commitmentStatus.value
  if (status === 'CONFIRMED') return ''
  if (status === 'DEGRADED') return 'negative'
  return 'waiting'
})

const pad = (value: number) => String(value).padStart(2, '0')
const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const dayTextOf = (value?: string | null) => {
  if (!value) return '时间待定'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间待定'
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日 ${WEEKDAYS[date.getDay()]}`
}
const timeTextOf = (value?: string | null) => value ? value.slice(11, 16) : '--:--'
const appliedAtText = (value?: string | null) => {
  if (!value) return '刚刚'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : `${pad(date.getMonth() + 1)}.${pad(date.getDate())} ${timeTextOf(value)}`
}
const avatarText = (value?: string | null) => (value || '申').slice(0, 1)
const memberName = (userId: number) => memberNicknames.value.get(userId) || `申请者 ${userId % 10000}`
const matchReasonText = (codes: string[]) => codes.map(matchReasonLabel).join(' · ')
const statusLabel = (value: string) => STATUS_LABELS[value] || value

const loadAll = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const detail = isActivity.value
      ? await activityApi.activity(currentId.value)
      : await activityApi.companion(currentId.value)
    item.value = detail
    if (detail.location?.venueId) {
      venueName.value = await fetchVenueName(detail.location.venueId)
      if (detail.location.spaceId) {
        spaceName.value = await fetchSpaceName(detail.location.venueId, detail.location.spaceId)
      }
    }
    if (!isOwner.value) {
      loadError.value = '只有发起人可以管理这场相聚。'
      item.value = null
      return
    }
    const metrics = await directoryApi.applicationMetrics(isActivity.value ? 'ACTIVITY' : 'COMPANION_REQUEST', currentId.value)
    metricsPending.value = metrics.pendingApplications
    applications.value = []
    applicationsCursor.value = null
    await loadApplications(false)
    void loadMemberNicknames(detail.groupId)
  } catch (error) {
    loadError.value = communityError(error, '管理面板无法加载，').message
  } finally {
    loading.value = false
  }
}

const loadMemberNicknames = async (groupId: number) => {
  try {
    const members = await communityApi.members(groupId, { limit: 50 },
      { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
    memberNicknames.value = new Map(members.records.map(member =>
      [member.userId, member.user?.nickname || `申请者 ${member.userId % 10000}`]))
  } catch {
    // 昵称是附属读取，失败时保留中性占位
  }
}

const loadApplications = async (append: boolean) => {
  applicationsLoading.value = true
  applicationsError.value = ''
  try {
    const page = isActivity.value
      ? await activityApi.activityApplications(currentId.value, { cursorId: append ? applicationsCursor.value ?? undefined : undefined, limit: 20 })
      : await activityApi.companionApplications(currentId.value, { cursorId: append ? applicationsCursor.value ?? undefined : undefined, limit: 20 })
    applications.value = append ? [...applications.value, ...page.records] : page.records
    applicationsCursor.value = page.nextCursor
    applicationsHasMore.value = page.hasMore
  } catch (error) {
    applicationsError.value = communityError(error, '申请列表暂时无法加载').message
  } finally {
    applicationsLoading.value = false
  }
}

const loadMoreApplications = () => loadApplications(true)

const openConfirm = (payload: { title: string; body: string; label: string; run: () => Promise<void> }) => {
  confirmAction.value = payload
  confirmError.value = ''
  confirmDialogOpen.value = true
}

const runConfirm = async () => {
  if (commandBusy.value) return
  commandBusy.value = true
  confirmError.value = ''
  try {
    await confirmAction.value.run()
    confirmDialogOpen.value = false
    await loadAll()
  } catch (error) {
    const state = communityError(error, '操作没有完成')
    confirmError.value = state.kind === 'failed'
      ? '网络没有回音。操作可能已经生效，请刷新页面核对，再决定是否重试。'
      : `${state.message}请刷新后重试。`
  } finally {
    commandBusy.value = false
  }
}

const confirmPublish = () => {
  openConfirm({
    title: '准备好发出邀请了吗？',
    body: `「${item.value?.title}」将进入活动浏览，其他人可以提出申请。${commitmentStatus.value !== 'CONFIRMED' ? '当前具体地点仍待确认。' : ''}`,
    label: '确认发布',
    run: async () => {
      const scope = isActivity.value ? 'activity-publish' : 'companion-publish'
      const key = registry.keyFor(scope, currentId.value)
      if (isActivity.value) await activityApi.publishActivity(currentId.value, key)
      else await activityApi.publishCompanion(currentId.value, key)
      registry.complete(scope)
      ElMessage({ message: '已发布', type: 'success' })
    }
  })
}

const confirmAccept = (application: ActivityApplication) => {
  openConfirm({
    title: `接受${memberName(application.applicantUserId)}的申请？`,
    body: '接受后对方将加入这场相聚，人数会立即更新。',
    label: '确认接受',
    run: async () => {
      const key = registry.keyFor('application-accept', application.id)
      await activityApi.acceptApplication(application.id, key)
      registry.complete('application-accept')
      ElMessage({ message: `已接受${memberName(application.applicantUserId)}的申请`, type: 'success' })
    }
  })
}

const confirmReject = (application: ActivityApplication) => {
  openConfirm({
    title: '暂不接受这条申请？',
    body: '对方会看到申请未被接受。确认前，请再核对一下活动安排。',
    label: '确认不接受',
    run: async () => {
      const key = registry.keyFor('application-reject', application.id)
      await activityApi.rejectApplication(application.id, 'NOT_SELECTED', key)
      registry.complete('application-reject')
      ElMessage({ message: '申请已处理', type: 'success' })
    }
  })
}

const confirmClose = () => {
  openConfirm({
    title: '关闭这场相聚的报名？',
    body: '关闭后不再接受新申请，已有参与安排会保留。',
    label: '关闭报名',
    run: async () => {
      const scope = isActivity.value ? 'activity-close' : 'companion-close'
      const key = registry.keyFor(scope, currentId.value)
      if (isActivity.value) await activityApi.closeActivity(currentId.value, 'OWNER_CLOSED', key)
      else await activityApi.closeCompanion(currentId.value, 'OWNER_CLOSED', key)
      registry.complete(scope)
      ElMessage({ message: '报名已关闭', type: 'success' })
    }
  })
}

const confirmCancel = () => {
  openConfirm({
    title: isDraft.value ? '放下这份草稿？' : '取消这场相聚？',
    body: '取消后，这场相聚将不再接受申请。已有参与者会收到通知。',
    label: '确认取消',
    run: async () => {
      const scope = isActivity.value ? 'activity-cancel' : 'companion-cancel'
      const key = registry.keyFor(scope, currentId.value)
      if (isActivity.value) await activityApi.cancelActivity(currentId.value, 'OWNER_CANCELED', key)
      else await activityApi.cancelCompanion(currentId.value, 'OWNER_CANCELED', key)
      registry.complete(scope)
      ElMessage({ message: '已取消', type: 'success' })
    }
  })
}

const openLocationEditor = () => {
  if (!item.value) return
  locationDraft.type = location.value?.type === 'VENUE_SPACE' ? 'VENUE_SPACE' : 'CAMPUS_AREA'
  locationDraft.areaCode = location.value?.areaCode || 'NORTH_CAMPUS'
  locationDraft.reservationId = null
  locationError.value = ''
  locationDialogOpen.value = true
  if (location.value?.type === 'VENUE_SPACE') void loadEligibleReservations()
}

const loadEligibleReservations = async () => {
  const source = item.value
  if (!source) return
  try {
    const page = await reservationApi.my({ pageNum: 1, pageSize: 100 })
    const start = startTime.value
    const end = endTime.value
    reservations.value = page.records
      .filter(reservation => ['APPROVED', 'PENDING_PAYMENT', 'PAID', 'REFUNDING'].includes(reservation.status)
        && reservation.startTime <= start && reservation.endTime >= end
        && (reservation.participantCount || 0) >= source.capacityTotal)
      .map(reservation => ({
        id: reservation.id,
        label: `${formatActivityTime(reservation.startTime, reservation.endTime)} · ${reservation.participantCount || 1} 人`,
        venueId: reservation.venueId,
        spaceId: reservation.spaceId
      }))
  } catch {
    reservations.value = []
  }
}

const saveLocation = async () => {
  if (!item.value || commandBusy.value) return
  commandBusy.value = true
  locationError.value = ''
  try {
    const scope = isActivity.value ? 'activity-location' : 'companion-location'
    const key = registry.keyFor(scope, `${currentId.value}:${item.value.versionNo}:${JSON.stringify(locationDraft)}`)
    const location = locationDraft.type === 'CAMPUS_AREA'
      ? { type: 'CAMPUS_AREA' as const, areaCode: locationDraft.areaCode }
      : (() => {
          const chosen = reservations.value.find(reservation => reservation.id === locationDraft.reservationId)
          return { type: 'VENUE_SPACE' as const, venueId: chosen?.venueId, spaceId: chosen?.spaceId, reservationId: locationDraft.reservationId ?? undefined }
        })()
    if (isActivity.value) {
      await activityApi.updateActivityLocation(currentId.value, { versionNo: item.value.versionNo, location }, key)
    } else {
      await activityApi.updateCompanionLocation(currentId.value, { versionNo: item.value.versionNo, location }, key)
    }
    registry.complete(scope)
    locationDialogOpen.value = false
    ElMessage({ message: '地点已更新', type: 'success' })
    await loadAll()
  } catch (error) {
    const state = communityError(error, '地点更新没有完成')
    locationError.value = state.kind === 'conflict'
      ? '页面上的信息已经不是最新，请关闭弹窗刷新后再试。'
      : state.kind === 'failed'
        ? '网络没有回音。更新可能已生效，请刷新页面核对。'
        : `${state.message}请稍后重试。`
  } finally {
    commandBusy.value = false
  }
}

const loadMatches = async () => {
  if (matches.value.length) return
  matchesBusy.value = true
  try {
    matchPage.value = await activityApi.matches(currentId.value, 20)
    matches.value = matchPage.value.records as typeof matches.value
  } catch {
    matches.value = []
  } finally {
    matchesBusy.value = false
  }
}

const refreshMatches = async () => {
  matchesBusy.value = true
  try {
    const key = registry.keyFor('companion-match-refresh', currentId.value)
    matchPage.value = await activityApi.refreshMatches(currentId.value, 20, key)
    registry.complete('companion-match-refresh')
    matches.value = matchPage.value.records as typeof matches.value
    ElMessage({ message: '匹配已刷新', type: 'success' })
  } catch (error) {
    ElMessage({ message: communityError(error, '匹配刷新没有完成').message, type: 'warning' })
  } finally {
    matchesBusy.value = false
  }
}

const openLinkedCreate = async () => {
  if (!item.value) return
  try {
    const linked = await activityApi.activity(currentId.value)
    await createDialog.value?.open('COMPANION_REQUEST', linked)
  } catch (error) {
    ElMessage({ message: communityError(error, '关联活动无法加载').message, type: 'warning' })
  }
}

watch(() => locationDraft.type, value => { if (value === 'VENUE_SPACE' && !reservations.value.length) void loadEligibleReservations() })
watch(showMatches, value => { if (value) void loadMatches() })
onMounted(loadAll)
</script>
