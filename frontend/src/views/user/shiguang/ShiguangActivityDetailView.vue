<template>
  <div class="sgv-activity sgv-page-detail">
    <div class="shell page-arrive">
      <div class="breadcrumb">
        <button class="text-button" type="button" data-test="back-link" @click="goBack">
          <span class="icon"><el-icon><Back /></el-icon></span>全部活动与同行
        </button>
        <el-dropdown v-if="item" trigger="click" @command="onMenuCommand">
          <button class="icon-button" type="button" aria-label="活动更多操作" title="更多操作">
            <span class="icon"><el-icon><MoreFilled /></el-icon></span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="isOwner" command="manage">管理{{ isActivity ? '活动' : '邀约' }}</el-dropdown-item>
              <el-dropdown-item command="my-time">查看我的安排</el-dropdown-item>
              <el-dropdown-item command="copy">复制{{ isActivity ? '活动' : '邀约' }}标题与时间</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <div v-if="loadError && !item" class="empty-state" role="alert" data-test="load-error">
        <div class="empty-art"><ShiguangArt scene="together" /></div>
        <div>
          <h2>这一页，暂时没能读出来。</h2>
          <p>{{ loadError }}</p>
          <button class="button" type="button" data-test="retry-load" @click="loadAll">
            <span class="icon"><el-icon><Refresh /></el-icon></span>重新加载
          </button>
        </div>
      </div>
      <div v-else-if="!item" class="empty-state" role="status" data-test="loading">
        <div class="empty-art"><ShiguangArt scene="quiet" /></div>
        <div>
          <h2>正在打开这场相聚。</h2>
          <p>活动与邀约正在加载。</p>
        </div>
      </div>

      <template v-else>
        <section class="detail-intro">
          <div class="detail-intro-copy">
            <div class="eyebrow">
              <router-link :to="`/community/groups/${item.groupId}`">{{ item.groupName || '所属小组' }}</router-link>
              <span>·</span>
              <span>{{ kindText }}</span>
              <span class="status" :class="item.status === 'PUBLISHED' ? '' : 'neutral'">{{ statusLabel(item.status) }}</span>
            </div>
            <h1 class="long-title">{{ item.title }}</h1>
            <p>{{ introText }}</p>
            <div class="organizer-line">
              <span class="avatar sage">{{ avatarText(item.organizerName) }}</span>
              <strong>{{ item.organizerName || '发起人' }}</strong>
              <span>发起了这场{{ isActivity ? '活动' : '邀约' }}</span>
            </div>
          </div>
          <div class="detail-art-stage feature-art">
            <ShiguangArt :scene="sceneFor" />
            <small>生活插画 · 非场地实景</small>
          </div>
        </section>

        <div class="detail-grid">
          <div class="detail-content">
            <section class="detail-section">
              <div class="facts-grid">
                <div class="fact-block">
                  <span class="fact-label"><span class="icon"><el-icon><Calendar /></el-icon></span>相聚的时间</span>
                  <strong>{{ dayTextOf(startTime) }}</strong>
                  <p>{{ timeRange }}</p>
                </div>
                <div class="fact-block">
                  <span class="fact-label"><span class="icon"><el-icon><Location /></el-icon></span>相聚的地方</span>
                  <strong>{{ placeTitle }}</strong>
                  <p>{{ placeDetail }}</p>
                  <span class="status" :class="commitmentTone">{{ commitmentText }}</span>
                </div>
              </div>
            </section>
            <section class="detail-section">
              <h2>关于这次相聚</h2>
              <div class="content-copy" data-test="description">
                <p v-for="(paragraph, index) in paragraphs" :key="index">{{ paragraph }}</p>
              </div>
            </section>
            <section class="detail-section">
              <div class="section-heading">
                <h2>已经在计划里的人</h2>
                <span>{{ item.participantCount }} 人已加入</span>
              </div>
              <div class="participant-list" data-test="participants">
                <div v-for="(person, index) in people" :key="`${person.name}-${index}`" class="participant">
                  <span class="avatar" :class="`color-${index % 4}`">{{ avatarText(person.name) }}</span>
                  <span>{{ person.name }}<small>{{ person.role }}</small></span>
                </div>
              </div>
              <p v-if="hiddenParticipants > 0" class="muted micro-note" data-test="more-participants">
                还有 {{ hiddenParticipants }} 位同行的人
              </p>
              <p v-if="participantsError" class="muted micro-note" role="status">{{ participantsError }}</p>
            </section>
            <div class="join-secondary">
              <button class="text-button" type="button" @click="goBack">继续看看其他活动<span class="icon"><el-icon><ArrowRight /></el-icon></span></button>
              <button v-if="isViewerActive && !isOwner" class="text-button accent" type="button" data-test="leave" @click="confirmLeave">退出参与</button>
              <button v-else-if="isActivity && isOwner" class="text-button" type="button" data-test="create-linked" @click="createLinkedCompanion">
                为这场活动找同行<span class="icon"><el-icon><TopRight /></el-icon></span>
              </button>
              <router-link v-else class="text-button" :to="`/community/groups/${item.groupId}`">了解这个小组<span class="icon"><el-icon><TopRight /></el-icon></span></router-link>
            </div>
          </div>

          <aside class="join-panel">
            <h2>{{ panelTitle }}</h2>
            <p class="panel-description">{{ isActivity ? '和节奏相近的人，一起过个好周末。' : '小小的邀约，也值得认真赴约。' }}</p>

            <div v-if="viewerNote" class="status-note" :class="viewerNote.tone">
              <strong>{{ viewerNote.title }}</strong>
              <p>{{ viewerNote.body }}</p>
            </div>

            <div class="join-capacity">
              <strong>{{ item.participantCount }}</strong><span>/ {{ item.capacityTotal }} 人已加入</span>
            </div>
            <div class="capacity-slots" :aria-label="`${item.participantCount} 人已加入，共 ${item.capacityTotal} 人`">
              <span v-for="index in 8" :key="index" :class="{ filled: index <= filledSlots }" />
            </div>
            <div class="capacity-caption">
              <span>{{ remainingSlots ? `还有 ${remainingSlots} 个位置` : '这一场已经坐满' }}</span>
              <span>{{ statusLabel(item.status) }}</span>
            </div>

            <div class="join-action">
              <button
                v-if="isOwner"
                class="button primary full"
                type="button"
                data-test="manage"
                @click="router.push(manageRoute)"
              >管理这场{{ isActivity ? '活动' : '邀约' }}<span class="icon"><el-icon><ArrowRight /></el-icon></span></button>
              <button v-else-if="terminalStatus" class="button full" type="button" disabled>{{ statusLabel(item.status) }}</button>
              <button v-else-if="isViewerActive" class="button primary full" type="button" @click="router.push('/me/time')">
                查看我的时间<span class="icon"><el-icon><ArrowRight /></el-icon></span>
              </button>
              <button v-else-if="isViewerPending" class="button full" type="button" data-test="withdraw" :disabled="commandBusy" @click="confirmWithdraw">撤回申请</button>
              <button v-else-if="item.status === 'FULL'" class="button full" type="button" disabled>这场已经满员</button>
              <button
                v-else
                class="button primary full"
                type="button"
                data-test="apply"
                :disabled="commandBusy"
                @click="openApplyDialog"
              >{{ isActivity ? '申请加入' : '申请同行' }}<span class="icon"><el-icon><ArrowRight /></el-icon></span></button>
              <p class="footnote">{{ isOwner ? '待处理的申请会显示在活动管理中。' : '申请由发起人确认，申请本身不产生扣款。' }}</p>
            </div>
            <div class="join-steps">
              <span :class="{ current: stepCurrent === 0 }">提出申请</span>
              <span class="icon"><el-icon><ArrowRight /></el-icon></span>
              <span :class="{ current: stepCurrent === 1 }">等待回应</span>
              <span class="icon"><el-icon><ArrowRight /></el-icon></span>
              <span :class="{ current: stepCurrent === 2 }">加入安排</span>
            </div>
          </aside>
        </div>

        <div class="mobile-join">
          <div>
            <strong>{{ mobileTitle }}</strong>
            <small>{{ dayTextOf(startTime) }} · {{ timeTextOf(startTime) }}</small>
          </div>
          <button
            v-if="isOwner"
            class="button primary"
            type="button"
            @click="router.push(manageRoute)"
          >管理</button>
          <button v-else-if="terminalStatus" class="button" type="button" disabled>{{ statusLabel(item.status) }}</button>
          <button v-else-if="isViewerActive" class="button primary" type="button" @click="router.push('/me/time')">已加入</button>
          <button v-else-if="isViewerPending" class="button" type="button" :disabled="commandBusy" @click="confirmWithdraw">撤回申请</button>
          <button v-else-if="item.status === 'FULL'" class="button" type="button" disabled>已满员</button>
          <button v-else class="button primary" type="button" :disabled="commandBusy" @click="openApplyDialog">{{ isActivity ? '申请加入' : '申请同行' }}</button>
        </div>
      </template>
    </div>

    <el-dialog v-model="applyDialogOpen" width="470px" @closed="focusReturner?.focus({ preventScroll: true })">
      <template #header><span class="eyebrow">拾光 · 活动与同行</span></template>
      <div class="sgv-activity">
        <h2 style="font-size:24px;margin-bottom:12px">{{ isActivity ? '把这段时间，留给相聚。' : '向这份邀约，走近一步。' }}</h2>
        <p class="muted" style="line-height:1.9;font-size:14px">
          申请将由{{ item?.organizerName || '发起人' }}确认。通过后，这场{{ isActivity ? '活动' : '邀约' }}才会成为你的参与安排。
        </p>
        <div class="status-note" style="border-left:0">
          <p><span>活动</span><strong>{{ item?.title }}</strong></p>
          <p><span>时间</span><strong>{{ item ? `${dayTextOf(startTime)} ${timeTextOf(startTime)}` : '' }}</strong></p>
          <p><span>地点</span><strong>{{ placeTitle }}</strong></p>
          <p><span>地点状态</span><strong>{{ commitmentStatus === 'CONFIRMED' ? '已确认' : '待确认' }}</strong></p>
        </div>
        <p v-if="applyError" role="alert" style="color:var(--accent);font-size:13px">{{ applyError }}</p>
        <div class="filter-dialog-actions">
          <button class="button" type="button" @click="applyDialogOpen = false">再想想</button>
          <button class="button primary" type="button" data-test="confirm-apply" :disabled="commandBusy" :class="{ busy: commandBusy }" @click="submitApply">
            <span v-if="commandBusy" class="icon"><el-icon class="is-loading"><Loading /></el-icon></span>
            <template v-else>提交申请<span class="icon"><el-icon><ArrowRight /></el-icon></span></template>
            {{ commandBusy ? '正在提交' : '' }}
          </button>
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { activityApi } from '@/api/activity'
import type { CommunityActivity, CompanionRequest } from '@/api/activity'
import { communityError, createIdempotencyRegistry } from '@/api/community'
import { communityApi } from '@/api/community'
import { useAuthStore } from '@/stores/auth'
import { fetchVenueName, fetchSpaceName } from '@/utils/entityNames'
import { campusAreaLabel } from '@/utils/activityPresentation'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import type { ShiguangScene } from '@/components/shiguang/types'

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '可申请', FULL: '名额已满',
  CLOSED: '报名已关闭', CANCELED: '已取消', EXPIRED: '已结束'
}
const TERMINAL_STATUSES = ['CANCELED', 'CLOSED', 'EXPIRED']

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const registry = createIdempotencyRegistry()

const item = ref<CommunityActivity | CompanionRequest | null>(null)
const viewerApplication = ref<{ id: number; status: string } | null>(null)
const viewerParticipation = ref<{ status: string; roleType: string } | null>(null)
const people = ref<Array<{ name: string; role: string }>>([])
const participantTotal = ref(0)
const loading = ref(true)
const loadError = ref('')
const participantsError = ref('')
const commandBusy = ref(false)
const applyDialogOpen = ref(false)
const applyError = ref('')
const confirmDialogOpen = ref(false)
const confirmError = ref('')
const confirmAction = ref<{ title: string; body: string; label: string; run: () => Promise<void> }>({
  title: '', body: '', label: '', run: async () => undefined
})
let focusReturner: HTMLElement | null = null

const isActivity = computed(() => route.name === 'ActivityDetail')
const currentId = computed(() => Number(route.params.id))
const location = computed(() => item.value?.location ?? null)
const startTime = computed(() => item.value
  ? (isActivity.value ? (item.value as CommunityActivity).startTime : (item.value as CompanionRequest).windowStart)
  : '')
const endTime = computed(() => item.value
  ? (isActivity.value ? (item.value as CommunityActivity).endTime : (item.value as CompanionRequest).windowEnd)
  : '')
const venueName = ref<string | null>(null)
const spaceName = ref<string | null>(null)
const commitmentStatus = computed(() => location.value?.commitmentStatus ?? 'UNCONFIRMED')

/** owner 判定仅用于界面呈现；写权限一律由服务端命令再校验 */
const isOwner = computed(() => {
  if (!item.value) return false
  const me = auth.user?.id
  if (me == null) return false
  return isActivity.value
    ? (item.value as CommunityActivity).organizerUserId === me
    : (item.value as CompanionRequest).ownerUserId === me
})

const isViewerActive = computed(() => viewerParticipation.value?.status === 'ACTIVE'
  && viewerParticipation.value.roleType !== 'ORGANIZER' && viewerParticipation.value.roleType !== 'OWNER')
const isViewerPending = computed(() => viewerApplication.value?.status === 'PENDING')
const terminalStatus = computed(() => Boolean(item.value && TERMINAL_STATUSES.includes(item.value.status)))

const kindText = computed(() => (isActivity.value ? '城市活动' : '同行邀约'))
const introText = computed(() => item.value?.description?.split('\n')[0] || '这场相聚的介绍还在路上。')
const paragraphs = computed(() => (item.value?.description || '').split('\n').map(part => part.trim()).filter(Boolean))
const sceneFor = computed<ShiguangScene>(() => {
  const type = item.value?.activityType
  return (({ SPORTS: 'move', STUDY: 'quiet', WORKSHOP: 'quiet', BOARD_GAME: 'together', OTHER: 'together' }) as Record<string, ShiguangScene>)[type || ''] || 'together'
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

const avatarText = (value?: string | null) => (value || '相').slice(0, 1)

const remainingSlots = computed(() => item.value ? Math.max(0, item.value.capacityTotal - item.value.participantCount) : 0)
const filledSlots = computed(() => item.value
  ? Math.round(item.value.participantCount / item.value.capacityTotal * 8) : 0)

const panelTitle = computed(() => {
  if (isOwner.value) return '你发起的这一场'
  if (isViewerActive.value) return '相聚，已经在计划里。'
  if (isViewerPending.value) return '给这次相遇一点时间。'
  return '给喜欢的事，留个位置。'
})
const mobileTitle = computed(() => {
  if (isViewerPending.value) return '等待发起人回应'
  if (isViewerActive.value) return '你已加入'
  return `${remainingSlots.value} 个位置待相遇`
})

const stepCurrent = computed(() => {
  if (isViewerActive.value) return 2
  if (isViewerPending.value) return 1
  return 0
})

const viewerNote = computed(() => {
  if (!item.value) return null
  if (isViewerPending.value) {
    return { tone: 'waiting', title: '申请已送达，等一个回应。',
      body: `${item.value.organizerName || '发起人'}确认后，你会收到站内消息。现在还没有加入${isActivity.value ? '活动' : '邀约'}。` }
  }
  if (isViewerActive.value) {
    return { tone: '', title: `你已加入这场${isActivity.value ? '活动' : '邀约'}。`,
      body: commitmentStatus.value === 'CONFIRMED'
        ? '时间与地点已列入你的安排，期待那天见。'
        : '参与已经确认，具体地点仍待发起人更新。' }
  }
  if (viewerApplication.value?.status === 'REJECTED') {
    return { tone: 'negative', title: '这次申请未被接受。',
      body: '也许下一场更合适。可以看看其他活动，继续寻找喜欢的安排。' }
  }
  if (item.value.status === 'CANCELED') {
    return { tone: 'negative', title: '活动已取消。',
      body: '这次相聚暂时取消，已加入的人会收到站内消息。' }
  }
  if (commitmentStatus.value === 'DEGRADED') {
    return { tone: 'waiting', title: '原场地预约有变化。',
      body: '地点承诺已失效。发起人正在重新确认，出发前请留意更新。' }
  }
  return null
})

const hiddenParticipants = computed(() => Math.max(0, participantTotal.value - people.value.length))

const manageRoute = computed(() => isActivity.value
  ? `/activities/${currentId.value}/manage` : `/companion-requests/${currentId.value}/manage`)

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
    const state = isActivity.value
      ? await activityApi.activityViewerState(currentId.value)
      : await activityApi.companionViewerState(currentId.value)
    viewerApplication.value = state.application ? { id: state.application.id, status: state.application.status } : null
    viewerParticipation.value = state.participation
      ? { status: state.participation.status, roleType: state.participation.roleType } : null
    participantTotal.value = detail.participantCount
    await loadParticipants(detail)
  } catch (error) {
    loadError.value = communityError(error, '活动无法加载，').message + '你的筛选条件还在，请稍后重试。'
  } finally {
    loading.value = false
  }
}

const loadParticipants = async (detail: CommunityActivity | CompanionRequest) => {
  participantsError.value = ''
  try {
    const page = isActivity.value
      ? await activityApi.activityParticipants(currentId.value, { limit: 20 })
      : await activityApi.companionParticipants(currentId.value, { limit: 20 })
    let nicknameById = new Map<number, string>()
    try {
      const members = await communityApi.members(detail.groupId, { status: 'ACTIVE', limit: 50 },
        { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true })
      nicknameById = new Map(members.records.map(member =>
        [member.userId, member.user?.nickname || `同行者 ${member.userId % 10000}`]))
    } catch {
      
    }
    people.value = page.records.map(record => ({
      name: nicknameById.get(record.userId) || `同行者 ${record.userId % 10000}`,
      role: record.roleType === 'ORGANIZER' || record.roleType === 'OWNER' ? '发起人' : '参与者'
    }))
    // 发起者排在首位（服务端按参与记录返回，补发起人行）
    participantTotal.value = item.value?.participantCount ?? people.value.length
  } catch (error) {
    const message = communityError(error, '参与者名单暂时无法加载').message
    participantsError.value = `${message}，稍后刷新重试。`
  }
}

const goBack = () => {
  if (window.history.length > 1) router.back()
  else router.push('/activities')
}

const onMenuCommand = async (command: string) => {
  if (command === 'manage') return void router.push(manageRoute.value)
  if (command === 'my-time') return void router.push('/me/time')
  if (command === 'copy' && item.value) {
    const text = `${item.value.title}\n${dayTextOf((item.value as CommunityActivity).startTime)} ${timeRange.value}\n${placeTitle.value}`
    try {
      await navigator.clipboard.writeText(text)
      ElMessage({ message: '活动信息已复制', type: 'success' })
    } catch {
      ElMessage({ message: '复制失败，请手动选择文本', type: 'info' })
    }
  }
}

const openApplyDialog = () => {
  applyError.value = ''
  focusReturner = document.activeElement as HTMLElement
  applyDialogOpen.value = true
}

const submitApply = async () => {
  if (!item.value || commandBusy.value) return
  commandBusy.value = true
  applyError.value = ''
  const scope = isActivity.value ? 'activity-apply' : 'companion-apply'
  try {
    const key = registry.keyFor(scope, currentId.value)
    if (isActivity.value) await activityApi.applyActivity(currentId.value, key)
    else await activityApi.applyCompanion(currentId.value, key)
    registry.complete(scope)
    applyDialogOpen.value = false
    router.push(isActivity.value ? `/activities/${currentId.value}/result` : `/companion-requests/${currentId.value}/result`)
  } catch (error) {
    registry.complete(scope)
    const state = communityError(error, '申请提交失败')
    applyError.value = state.kind === 'conflict'
      ? `${state.message}名额或状态可能已变化，请刷新后重试。`
      : state.kind === 'failed'
        ? '网络没有回音。申请可能已送达，请刷新页面核对我的状态，不要重复提交。'
        : `${state.message}请稍后重试。`
  } finally {
    commandBusy.value = false
  }
}

const openConfirm = (payload: { title: string; body: string; label: string; run: () => Promise<void> }) => {
  confirmAction.value = payload
  confirmError.value = ''
  focusReturner = document.activeElement as HTMLElement
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

const confirmWithdraw = () => {
  const applicationId = viewerApplication.value?.id
  if (!applicationId) return
  openConfirm({
    title: '撤回这次申请？',
    body: '撤回后，这条申请将不再等待发起人处理。活动仍开放时，可以重新申请。',
    label: '确认撤回',
    run: async () => {
      const scope = 'application-withdraw'
      const key = registry.keyFor(scope, applicationId)
      await activityApi.withdrawApplication(applicationId, key)
      registry.complete(scope)
      ElMessage({ message: '申请已撤回', type: 'success' })
    }
  })
}

const confirmLeave = () => {
  openConfirm({
    title: '退出这次相聚？',
    body: '退出后，你的位置会留给其他申请者。发起人会收到这次变化。',
    label: '确认退出',
    run: async () => {
      const scope = isActivity.value ? 'activity-leave' : 'companion-leave'
      const key = registry.keyFor(scope, currentId.value)
      if (isActivity.value) await activityApi.leaveActivity(currentId.value, key)
      else await activityApi.leaveCompanion(currentId.value, key)
      registry.complete(scope)
      ElMessage({ message: '已退出参与', type: 'success' })
    }
  })
}

const createLinkedCompanion = () => {
  void router.push({ path: '/activities', query: { create: 'companion', activityId: String(currentId.value) } })
}

const statusLabel = (value: string) => STATUS_LABELS[value] || value

onMounted(loadAll)
watch(() => route.fullPath, () => {
  if (Number(route.params.id)) void loadAll()
})
</script>
