<template>
  <div class="sgv-activity">
    <div class="shell page-arrive">
      <div class="breadcrumb">
        <router-link class="text-button" :to="detailRoute"><span class="icon"><el-icon><Back /></el-icon></span>返回活动</router-link>
      </div>

      <div v-if="loadError && !item" class="empty-state" role="alert" data-test="load-error">
        <div class="empty-art"><ShiguangArt scene="together" /></div>
        <div>
          <h2>结果暂时读不出来。</h2>
          <p>{{ loadError }}</p>
          <button class="button" type="button" @click="loadAll"><span class="icon"><el-icon><Refresh /></el-icon></span>重新加载</button>
        </div>
      </div>
      <div v-else-if="!item" class="empty-state" role="status">
        <div class="empty-art"><ShiguangArt scene="quiet" /></div>
        <div><h2>正在确认申请结果。</h2><p>活动与邀约正在加载。</p></div>
      </div>

      <template v-else>
        <section class="result-heading">
          <span class="status" :class="joined ? '' : 'waiting'">{{ joined ? '已加入活动' : '申请已提交 · 等待回应' }}</span>
          <h1>{{ joined ? '这场相聚，已经在计划里。' : '喜欢的事，先向它走一步。' }}</h1>
          <p>{{ joined
            ? '参与已经确认。出发前，记得再看一眼时间与地点。'
            : `你的申请已经送达${item.organizerName || '发起人'}。确认后，你会收到站内消息；现在还没有加入活动。` }}</p>
        </section>
        <div class="result-overview">
          <div class="result-facts">
            <p class="eyebrow" style="margin-bottom:9px">{{ item.groupName || '所属小组' }} · {{ kindText }}</p>
            <h2>{{ item.title }}</h2>
            <div class="facts-grid">
              <div class="fact-block">
                <span class="fact-label"><span class="icon"><el-icon><Calendar /></el-icon></span>相聚时间</span>
                <strong>{{ dayText }}</strong>
                <p>{{ timeRange }}</p>
              </div>
              <div class="fact-block">
                <span class="fact-label"><span class="icon"><el-icon><Location /></el-icon></span>相聚地点</span>
                <strong>{{ placeTitle }}</strong>
                <p>{{ placeDetail }}</p>
              </div>
            </div>
            <div class="result-meta">
              <span>你的状态：{{ joined ? '已加入' : '等待发起人回应' }}</span>
              <span>不产生报名扣款</span>
            </div>
          </div>
          <div class="result-art feature-art"><ShiguangArt :scene="sceneFor" /></div>
        </div>
        <div class="result-actions">
          <router-link class="button primary" to="/me/time">查看我的时间<span class="icon"><el-icon><ArrowRight /></el-icon></span></router-link>
          <router-link class="button" to="/activities">再看看其他相聚</router-link>
          <button v-if="!joined && viewerApplication?.status === 'PENDING'" class="text-button" type="button" data-test="withdraw" @click="confirmWithdraw">撤回申请</button>
        </div>
      </template>
    </div>

    <el-dialog v-model="confirmDialogOpen" width="470px">
      <template #header><span class="eyebrow">拾光 · 活动与同行</span></template>
      <div class="sgv-activity">
        <h2 style="font-size:24px;margin-bottom:12px">撤回这次申请？</h2>
        <p class="muted" style="line-height:1.9;font-size:14px">撤回后，这条申请将不再等待发起人处理。活动仍开放时，可以重新申请。</p>
        <p v-if="confirmError" role="alert" style="color:var(--accent);font-size:13px">{{ confirmError }}</p>
        <div class="filter-dialog-actions">
          <button class="button" type="button" @click="confirmDialogOpen = false">再想想</button>
          <button class="button primary" type="button" data-test="confirm-withdraw" :disabled="busy" @click="runWithdraw">确认撤回<span class="icon"><el-icon><ArrowRight /></el-icon></span></button>
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
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import type { ShiguangScene } from '@/components/shiguang/types'

const route = useRoute()
const router = useRouter()
const registry = createIdempotencyRegistry()

const item = ref<CommunityActivity | CompanionRequest | null>(null)
const viewerApplication = ref<{ id: number; status: string } | null>(null)
const loadError = ref('')
const confirmDialogOpen = ref(false)
const confirmError = ref('')
const busy = ref(false)

const isActivity = computed(() => route.name === 'ActivityResult')
const currentId = computed(() => Number(route.params.id))
const joined = computed(() => Boolean(viewerApplication.value && viewerApplication.value.status === 'ACCEPTED'))
const kindText = computed(() => (isActivity.value ? '城市活动' : '同行邀约'))
const detailRoute = computed(() => isActivity.value ? `/activities/${currentId.value}` : `/companion-requests/${currentId.value}`)

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const startTime = computed(() => item.value
  ? (isActivity.value ? (item.value as CommunityActivity).startTime : (item.value as CompanionRequest).windowStart) : '')
const endTime = computed(() => item.value
  ? (isActivity.value ? (item.value as CommunityActivity).endTime : (item.value as CompanionRequest).windowEnd) : '')
const dayText = computed(() => {
  if (!startTime.value) return '时间待定'
  const date = new Date(startTime.value)
  return Number.isNaN(date.getTime()) ? '时间待定'
    : `${date.getMonth() + 1} 月 ${date.getDate()} 日 ${WEEKDAYS[date.getDay()]}`
})
const timeRange = computed(() => `${startTime.value.slice(11, 16)}–${endTime.value.slice(11, 16)}`)
const placeTitle = computed(() => {
  if (!item.value) return ''
  return item.value.location?.venueId ? (item.value.location.spaceId ? '场地空间' : '场地空间') : (item.value.location?.areaCode || '地点待确认')
})
const placeDetail = computed(() => item.value?.location?.areaCode || '')
const sceneFor = computed<ShiguangScene>(() => {
  const type = item.value?.activityType
  return (({ SPORTS: 'move', STUDY: 'quiet', WORKSHOP: 'quiet', BOARD_GAME: 'together', OTHER: 'together' }) as Record<string, ShiguangScene>)[type || ''] || 'together'
})

const loadAll = async () => {
  loadError.value = ''
  try {
    item.value = isActivity.value
      ? await activityApi.activity(currentId.value)
      : await activityApi.companion(currentId.value)
    const state = isActivity.value
      ? await activityApi.activityViewerState(currentId.value)
      : await activityApi.companionViewerState(currentId.value)
    viewerApplication.value = state.application
      ? { id: state.application.id, status: state.application.status } : null
  } catch (error) {
    loadError.value = communityError(error, '申请结果无法确认，').message
  }
}

const confirmWithdraw = () => {
  confirmError.value = ''
  confirmDialogOpen.value = true
}

const runWithdraw = async () => {
  if (!viewerApplication.value || busy.value) return
  busy.value = true
  confirmError.value = ''
  try {
    const key = registry.keyFor('application-withdraw', viewerApplication.value.id)
    await activityApi.withdrawApplication(viewerApplication.value.id, key)
    registry.complete('application-withdraw')
    confirmDialogOpen.value = false
    ElMessage({ message: '申请已撤回', type: 'success' })
    await loadAll()
  } catch (error) {
    const state = communityError(error, '撤回没有完成')
    confirmError.value = state.kind === 'failed'
      ? '网络没有回音。撤回可能已生效，请刷新页面核对。'
      : `${state.message}请刷新后重试。`
  } finally {
    busy.value = false
  }
}

onMounted(loadAll)
watch(() => route.fullPath, () => { if (Number(route.params.id)) void loadAll() })
</script>
