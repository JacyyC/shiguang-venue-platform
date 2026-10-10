<template>
  <div ref="rootEl" class="sga-gov" data-test="sga-ad-root">
    <!-- 商业生命周期审计子视图（advertising:audit:view 专有；B5-G5/G9） -->
    <template v-if="auditMode">
      <button class="btn" type="button" data-test="ad-audit-back" @click="closeAudit">返回广告管理</button>
      <div class="g-toolbar">
        <div>
          <h2>商业生命周期审计</h2>
          <p>广告主、计划、创意分实体留痕；不含原始令牌或个人信息。</p>
        </div>
      </div>
      <div v-if="audits.length" data-test="ad-audit-list">
        <article v-for="audit in audits" :key="audit.id" class="g-audit">
          <p class="g-muted">{{ shortTime(audit.createTime) }}</p>
          <div>
            <h3>{{ entityLabel(audit.entityType) }}{{ audit.entityNo || audit.entityId }} · {{ audit.action }}</h3>
            <p>{{ statusLabel(audit.beforeStatus) }} → {{ statusLabel(audit.afterStatus) }}</p>
            <p v-if="audit.reasonCode" class="g-muted">原因代码 {{ audit.reasonCode }}</p>
          </div>
          <p class="g-muted">生命周期审计</p>
        </article>
      </div>
      <p v-else class="g-muted" data-test="ad-audit-empty">当前没有生命周期审计记录。</p>
    </template>

    <!-- 商业广告主面（参考 commercialView） -->
    <template v-else>
      <div v-if="!canRead" class="g-empty" data-test="ad-denied">
        <h2>当前没有商业广告读取权限</h2>
        <p>需要商业广告的管理、审核、发布或审计权限。</p>
      </div>

      <template v-else>
        <section v-if="loading" class="g-empty" role="status" data-test="ad-loading">
          <h2>正在读取广告主</h2>
          <p>请稍候，正在核对所选范围。</p>
          <progress aria-label="正在加载"></progress>
        </section>

        <div v-else-if="loadError" class="g-empty" role="alert" data-test="ad-error">
          <h2>暂时无法读取广告主</h2>
          <p>{{ loadError }}</p>
          <button class="btn primary" type="button" data-test="ad-reload" @click="reload">重新查询</button>
        </div>

        <div v-else-if="!advertisers.length" class="g-empty" data-test="ad-empty">
          <h2>当前没有广告主</h2>
          <p>先新增广告主，再建立计划与创意。</p>
        </div>

        <template v-else>
          <div class="g-commercial-head">
            <div>
              <div class="field">
                <label for="ad-advertiser">先选择广告主</label>
                <select id="ad-advertiser" v-model.number="selectedAdvertiserId" data-test="ad-advertiser-select" @change="onAdvertiserChange">
                  <option v-for="advertiser in advertisers" :key="advertiser.id" :value="advertiser.id">
                    {{ advertiser.advertiserName }}
                  </option>
                </select>
              </div>
              <div class="row">
                <span v-if="selectedAdvertiser" class="g-state" :data-state="selectedAdvertiser.status">{{ advertiserStatusLabel(selectedAdvertiser.status) }}</span>
                <span class="g-muted">广告主 {{ selectedAdvertiserId }} · {{ campaigns.length }} 个已有计划</span>
              </div>
            </div>
            <div class="g-actions">
              <button v-if="canManage" class="btn" type="button" data-test="ad-new-advertiser" @click="openAdvertiserDialog">新增广告主</button>
              <button
                v-if="canManage"
                class="btn primary"
                type="button"
                data-test="ad-new-plan"
                :disabled="selectedAdvertiser?.status !== 'ACTIVE'"
                @click="openPlanDialog"
              >新建计划</button>
              <button v-if="canAudit" class="btn" type="button" data-test="ad-open-audit" @click="openAudit">生命周期审计</button>
            </div>
          </div>

          <div class="g-actions" style="margin:18px 0">
            <button
              v-if="canPublish && selectedAdvertiser?.status === 'ACTIVE'"
              class="btn danger"
              type="button"
              data-test="ad-suspend-advertiser"
              @click="transitionAdvertiser('suspend')"
            >停用广告主</button>
            <button
              v-if="canPublish && ['ACTIVE', 'SUSPENDED'].includes(selectedAdvertiser?.status || '')"
              class="btn danger"
              type="button"
              data-test="ad-retire-advertiser"
              @click="transitionAdvertiser('retire')"
            >下线广告主</button>
          </div>
          <p v-if="selectedAdvertiser && selectedAdvertiser.status !== 'ACTIVE'" class="g-note" data-test="ad-advertiser-inactive-note">
            广告主不可用，不能创建新计划或发布计划/创意。已有实体状态分别保留；当前没有广告主重新启用入口。
          </p>

          <section aria-label="广告计划" data-test="ad-campaign-list">
            <article
              v-for="campaign in campaigns"
              :key="campaign.id"
              class="g-campaign"
              :aria-current="campaign.id === selectedCampaignId"
            >
              <div>
                <h3>{{ campaign.campaignName }}</h3>
                <p>计划{{ campaign.campaignNo }} · {{ venueText(campaign.venueId) }}</p>
              </div>
              <p>
                <span class="g-state" :data-state="campaign.status">{{ statusLabel(campaign.status) }}</span><br>
                {{ dateText(campaign.effectiveFrom) }} 至 {{ dateText(campaign.effectiveUntil) }}
              </p>
              <button class="btn" type="button" :data-test="`ad-plan-${campaign.id}`" @click="selectCampaign(campaign)">查看计划</button>
            </article>
            <div v-if="!campaigns.length" class="g-empty">
              <h2>这个广告主还没有计划</h2>
              <p>从一个有明确时间窗的计划开始，再添加创意。</p>
            </div>
          </section>

          <!-- 计划详情与创意版本（参考 planView） -->
          <section v-if="selectedCampaign" class="g-plan">
            <div class="g-plan-header">
              <div>
                <div class="g-kicker">计划 {{ selectedCampaign.campaignNo }} · 计划和创意分别核对</div>
                <h2 data-test="ad-plan-title">{{ selectedCampaign.campaignName }}</h2>
                <p class="g-muted">{{ dateText(selectedCampaign.effectiveFrom) }} 至 {{ dateText(selectedCampaign.effectiveUntil) }}</p>
              </div>
              <span class="g-state" :data-state="selectedCampaign.status">{{ statusLabel(selectedCampaign.status) }}</span>
            </div>
            <p class="g-lifecycle">草稿 → 待审核 → 批准 / 拒绝 → 发布 → 暂停 / 下线</p>
            <div class="g-actions" data-test="ad-plan-actions">
              <button
                v-if="canManage && ['DRAFT', 'REJECTED'].includes(selectedCampaign.status)"
                class="btn"
                type="button"
                data-test="ad-plan-submit"
                @click="campaignAction('submit')"
              >提交审核</button>
              <button
                v-if="canReview && selectedCampaign.status === 'PENDING_REVIEW'"
                class="btn"
                type="button"
                data-test="ad-plan-approve"
                @click="campaignAction('approve')"
              >批准</button>
              <button
                v-if="canReview && selectedCampaign.status === 'PENDING_REVIEW'"
                class="btn danger"
                type="button"
                data-test="ad-plan-reject"
                @click="openRejectDialog('CAMPAIGN', selectedCampaign.id)"
              >拒绝</button>
              <button
                v-if="canPublish && ['APPROVED', 'PAUSED'].includes(selectedCampaign.status)"
                class="btn primary"
                type="button"
                data-test="ad-plan-publish"
                @click="campaignAction('publish')"
              >发布</button>
              <button
                v-if="canPublish && selectedCampaign.status === 'PUBLISHED'"
                class="btn"
                type="button"
                data-test="ad-plan-pause"
                @click="campaignAction('pause')"
              >暂停</button>
              <button
                v-if="canPublish && ['APPROVED', 'PUBLISHED', 'PAUSED'].includes(selectedCampaign.status)"
                class="btn danger"
                type="button"
                data-test="ad-plan-retire"
                @click="campaignAction('retire')"
              >下线</button>
              <span v-if="!hasCampaignAction" class="g-muted">当前状态没有可用动作</span>
            </div>

            <div class="g-toolbar">
              <div>
                <h3>计划中的创意版本</h3>
                <p>{{ creatives.length }} 个版本 · 创意内容按版本新增，不在发布后原地改稿</p>
              </div>
              <button
                v-if="canManage && selectedCampaign.status !== 'RETIRED'"
                class="btn"
                type="button"
                data-test="ad-new-creative"
                @click="openCreativeDialog"
              >新增创意版本</button>
            </div>
            <article v-for="creative in creatives" :key="creative.id" class="g-creative" :data-test="`ad-creative-${creative.id}`">
              <div>
                <div class="row">
                  <span class="g-muted">创意 {{ creative.creativeNo }} · V{{ creative.versionNo }}</span>
                  <span class="g-state" :data-state="creative.status">{{ statusLabel(creative.status) }}</span>
                </div>
                <h3>{{ creative.headline }}</h3>
                <p>{{ creative.bodyText }}</p>
                <p class="g-muted">按钮：{{ ctaLabel(creative.callToAction) }} · 站内目标：{{ targetLabel(creative.targetType) }} {{ creative.targetId || '' }}</p>
              </div>
              <aside>
                <h3>这个版本的动作</h3>
                <div class="g-actions" :data-test="`ad-creative-actions-${creative.id}`">
                  <button
                    v-if="canManage && ['DRAFT', 'REJECTED'].includes(creative.status)"
                    class="btn"
                    type="button"
                    :data-test="`ad-creative-${creative.id}-submit`"
                    @click="creativeAction(creative, 'submit')"
                  >提交审核</button>
                  <button
                    v-if="canReview && creative.status === 'PENDING_REVIEW'"
                    class="btn"
                    type="button"
                    :data-test="`ad-creative-${creative.id}-approve`"
                    @click="creativeAction(creative, 'approve')"
                  >批准</button>
                  <button
                    v-if="canReview && creative.status === 'PENDING_REVIEW'"
                    class="btn danger"
                    type="button"
                    :data-test="`ad-creative-${creative.id}-reject`"
                    @click="openRejectDialog('CREATIVE', creative.id)"
                  >拒绝</button>
                  <button
                    v-if="canPublish && ['APPROVED', 'PAUSED'].includes(creative.status)"
                    class="btn primary"
                    type="button"
                    :data-test="`ad-creative-${creative.id}-publish`"
                    @click="creativeAction(creative, 'publish')"
                  >发布</button>
                  <button
                    v-if="canPublish && creative.status === 'PUBLISHED'"
                    class="btn"
                    type="button"
                    :data-test="`ad-creative-${creative.id}-pause`"
                    @click="creativeAction(creative, 'pause')"
                  >暂停</button>
                  <button
                    v-if="canPublish && ['APPROVED', 'PUBLISHED', 'PAUSED'].includes(creative.status)"
                    class="btn danger"
                    type="button"
                    :data-test="`ad-creative-${creative.id}-retire`"
                    @click="creativeAction(creative, 'retire')"
                  >下线</button>
                </div>
                <p class="g-muted" style="margin-top:14px">创意发布不级联所属计划；计划与创意分别核对。</p>
              </aside>
            </article>
            <p v-if="!creatives.length" class="g-muted">这个计划还没有创意版本。</p>
          </section>
          <p class="g-note">本页为隔离生命周期预览。计划与创意的发布状态各自独立，实际商业决策还需双开关、用户偏好与有效条件共同满足。</p>
        </template>
      </template>
    </template>

    <!-- 弹层：新增广告主 / 新建计划 / 新增创意 / 拒绝（双原因 B5-G8） -->
    <dialog ref="dialogEl" class="g-dialog" aria-labelledby="ad-dialog-title" @close="onDialogClose">
      <div class="g-dialog-header">
        <h2 id="ad-dialog-title">{{ dialogTitle }}</h2>
        <button class="btn" type="button" aria-label="关闭对话框" @click="closeDialog">关闭</button>
      </div>
      <form @submit.prevent="submitDialog">
        <template v-if="dialogKind === 'advertiser'">
          <div class="field">
            <label for="ad-adv-name">广告主名称</label>
            <input id="ad-adv-name" v-model="form.name" data-test="ad-adv-name" required maxlength="128">
          </div>
          <p class="g-muted">创建后为有效状态；名称不涉及真实签约、账户或计费。</p>
        </template>

        <template v-else-if="dialogKind === 'plan'">
          <div class="field">
            <label for="ad-plan-name">计划名称</label>
            <input id="ad-plan-name" v-model="form.campaignName" data-test="ad-plan-name" required maxlength="128">
          </div>
          <div class="field">
            <label for="ad-plan-venue">投放场馆</label>
            <select id="ad-plan-venue" v-model="form.venueId" data-test="ad-plan-venue">
              <option :value="null">平台范围</option>
              <option v-for="venue in venues" :key="venue.id" :value="venue.id">{{ venue.name }}</option>
            </select>
          </div>
          <div class="field">
            <label for="ad-plan-from">开始日期</label>
            <input id="ad-plan-from" v-model="form.effectiveFrom" data-test="ad-plan-from" type="datetime-local" required>
          </div>
          <div class="field">
            <label for="ad-plan-until">结束日期</label>
            <input id="ad-plan-until" v-model="form.effectiveUntil" data-test="ad-plan-until" type="datetime-local" required>
          </div>
          <p class="g-muted">计划时间窗必须为正区间；审核通过前创意不能发布。</p>
        </template>

        <template v-else-if="dialogKind === 'creative'">
          <div class="field">
            <label for="ad-c-headline">创意标题</label>
            <input id="ad-c-headline" v-model="form.headline" data-test="ad-c-headline" required maxlength="128">
          </div>
          <div class="field">
            <label for="ad-c-body">创意正文</label>
            <textarea id="ad-c-body" v-model="form.bodyText" data-test="ad-c-body" rows="4" required maxlength="500"></textarea>
          </div>
          <div class="field">
            <label for="ad-c-cta">按钮</label>
            <select id="ad-c-cta" v-model="form.callToAction" data-test="ad-c-cta">
              <option value="VIEW">查看</option>
              <option value="BOOK">预约</option>
              <option value="LEARN_MORE">了解更多</option>
            </select>
          </div>
          <div class="field">
            <label for="ad-c-target-type">站内目标</label>
            <select id="ad-c-target-type" v-model="form.targetType" data-test="ad-c-target-type">
              <option value="VENUE_LIST">场馆目录</option>
              <option value="VENUE_DETAIL">场馆详情</option>
              <option value="ACTIVITY_DETAIL">活动详情</option>
            </select>
          </div>
          <div class="field">
            <label for="ad-c-target-id">目标编号（详情类必填）</label>
            <input id="ad-c-target-id" v-model.number="form.targetId" data-test="ad-c-target-id" type="number" min="1">
          </div>
          <p class="g-muted">创意按版本新增；发布后不在原版本上改稿。</p>
        </template>

        <template v-else-if="dialogKind === 'reject'">
          <p>{{ rejectTargetLabel }}</p>
          <div class="field">
            <label for="ad-reject-code">拒绝原因代码</label>
            <input id="ad-reject-code" v-model="form.reasonCode" data-test="ad-reject-code" required maxlength="64">
          </div>
          <div class="field">
            <label for="ad-reject-text">拒绝说明</label>
            <textarea id="ad-reject-text" v-model="form.reasonText" data-test="ad-reject-text" rows="4" required maxlength="500"></textarea>
          </div>
          <p class="g-muted">原因代码与说明都会进入生命周期审计；审计出参不含说明原文。</p>
        </template>

        <p class="g-error" role="alert">{{ dialogError }}</p>
        <div class="g-actions">
          <button class="btn" type="button" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="ad-dialog-submit" :disabled="dialogBusy">
            {{ dialogBusy ? '处理中…' : dialogSubmitLabel }}
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
/* 拾光商业广告面（SHIGUANG-BATCH-05 S07）：一比一承接冻结参考 advertising.js 的 commercial 工作面
   与生命周期审计子视图（B5-G5…G9）。g-* 基元沿用 B4 迁移的 shiguang-admin-governance.css（.sga-gov）。
   三实体状态机独立、发布不级联；拒绝双原因必填（B5-G8）；审计出参不含说明原文（B5-G9）。
   真实合同见本项 contracts.md 广告附件 §3；预览审阅情景/固定示例数据不迁入。 */
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { advertisingApi } from '@/api/advertising'
import { venueApi } from '@/api/venue'
import { useAuthStore } from '@/stores/auth'
import type {
  AdvertisingAdvertiserResponse,
  AdvertisingAuditResponse,
  AdvertisingCallToAction,
  AdvertisingCampaignResponse,
  AdvertisingCreativeResponse,
  AdvertisingTargetType
} from '@/api/types'

interface VenueOption { id: number; name: string }

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('advertising:manage'))
const canReview = computed(() => auth.hasPermission('advertising:review'))
const canPublish = computed(() => auth.hasPermission('advertising:publish'))
const canAudit = computed(() => auth.hasPermission('advertising:audit:view'))
const canRead = computed(() => canManage.value || canReview.value || canPublish.value || canAudit.value)

const loading = ref(true)
const loadError = ref('')
const rootEl = ref<HTMLElement>()
const dialogEl = ref<HTMLDialogElement>()

const auditMode = ref(false)
const advertisers = ref<AdvertisingAdvertiserResponse[]>([])
const selectedAdvertiserId = ref<number | null>(null)
const campaigns = ref<AdvertisingCampaignResponse[]>([])
const allCampaigns = ref<AdvertisingCampaignResponse[]>([])
const selectedCampaignId = ref<number | null>(null)
const creatives = ref<AdvertisingCreativeResponse[]>([])
const audits = ref<AdvertisingAuditResponse[]>([])
const venues = ref<VenueOption[]>([])

const dialogKind = ref<'advertiser' | 'plan' | 'creative' | 'reject' | null>(null)
const dialogBusy = ref(false)
const dialogError = ref('')
const rejectTarget = ref<{ type: 'CAMPAIGN' | 'CREATIVE'; id: number } | null>(null)
const form = ref({
  name: '',
  campaignName: '',
  venueId: null as number | null,
  effectiveFrom: '',
  effectiveUntil: '',
  headline: '',
  bodyText: '',
  callToAction: 'VIEW' as AdvertisingCallToAction,
  targetType: 'VENUE_LIST' as AdvertisingTargetType,
  targetId: null as number | null,
  versionNo: 1,
  reasonCode: '',
  reasonText: ''
})

const selectedAdvertiser = computed(() =>
  advertisers.value.find((item) => item.id === selectedAdvertiserId.value) || null)
const selectedCampaign = computed(() =>
  campaigns.value.find((item) => item.id === selectedCampaignId.value) || null)
const hasCampaignAction = computed(() => {
  const status = selectedCampaign.value?.status || ''
  const manage = canManage.value && ['DRAFT', 'REJECTED'].includes(status)
  const review = canReview.value && status === 'PENDING_REVIEW'
  const publish = canPublish.value && ['APPROVED', 'PUBLISHED', 'PAUSED'].includes(status)
  return manage || review || publish
})

const dialogTitle = computed(() => {
  const titles: Record<string, string> = {
    advertiser: '新增广告主', plan: '新建计划', creative: '新增创意版本', reject: '拒绝并记录原因'
  }
  return titles[dialogKind.value || ''] || ''
})
const dialogSubmitLabel = computed(() => {
  const labels: Record<string, string> = {
    advertiser: '创建广告主', plan: '保存计划草稿', creative: '保存创意版本', reject: '确认拒绝'
  }
  return labels[dialogKind.value || ''] || '确认'
})
const rejectTargetLabel = computed(() => {
  if (!rejectTarget.value) return ''
  if (rejectTarget.value.type === 'CAMPAIGN') {
    const campaign = campaigns.value.find((item) => item.id === rejectTarget.value?.id)
    return `计划 ${campaign?.campaignNo || rejectTarget.value.id} · ${campaign?.campaignName || ''}`
  }
  const creative = creatives.value.find((item) => item.id === rejectTarget.value?.id)
  return `创意 ${creative?.creativeNo || rejectTarget.value.id} · V${creative?.versionNo ?? ''}`
})

const advertiserStatusLabel = (status?: string | null) => statusLabel(status)
const statusLabel = (status?: string | null) =>
  ({ DRAFT: '草稿', PENDING_REVIEW: '待审核', APPROVED: '已批准', REJECTED: '已拒绝', PUBLISHED: '已发布', PAUSED: '已暂停', RETIRED: '已下线', ACTIVE: '有效', SUSPENDED: '已停用' }[status || ''] || status || '—')
const entityLabel = (type?: string) =>
  ({ ADVERTISER: '广告主', CAMPAIGN: '计划', CREATIVE: '创意' }[type || ''] || type || '')
const ctaLabel = (cta?: string) =>
  ({ VIEW: '查看', BOOK: '预约', LEARN_MORE: '了解更多' }[cta || ''] || cta || '—')
const targetLabel = (type?: string) =>
  ({ VENUE_LIST: '场馆目录', VENUE_DETAIL: '场馆详情', ACTIVITY_DETAIL: '活动详情' }[type || ''] || type || '—')
const venueText = (venueId?: number | null) => {
  if (!venueId) return '平台范围'
  return venues.value.find((venue) => venue.id === venueId)?.name || `场馆 ${venueId}`
}
const dateText = (value?: string | null) => (value ? value.replace('T', ' ').slice(0, 10) : '—')
const shortTime = (value?: string | null) => (value ? value.replace('T', ' ').slice(0, 16).replace(' ', ' ') : '—')

const readableError = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

async function bootstrap() {
  loading.value = true
  loadError.value = ''
  try {
    advertisers.value = await advertisingApi.admin.advertisers(100)
    if (advertisers.value.length) {
      const keep = advertisers.value.find((item) => item.id === selectedAdvertiserId.value)
      selectedAdvertiserId.value = (keep || advertisers.value[0]).id
      await loadAdvertiserScope()
    }
    if (!venues.value.length) {
      try {
        const venuePage = await venueApi.list({ pageNum: 1, pageSize: 50 })
        venues.value = (venuePage.records || []).map((venue) => ({ id: venue.id, name: venue.name }))
      } catch {
        venues.value = []
      }
    }
  } catch (error) {
    loadError.value = readableError(error, '广告主读取失败，请重试。')
  } finally {
    loading.value = false
  }
}

async function loadAdvertiserScope() {
  const advertiserId = selectedAdvertiserId.value
  if (!advertiserId) return
  if (!allCampaigns.value.length) {
    allCampaigns.value = await advertisingApi.admin.campaigns({ limit: 200 })
  }
  campaigns.value = allCampaigns.value.filter((item) => item.advertiserId === advertiserId)
  const keep = campaigns.value.find((item) => item.id === selectedCampaignId.value)
  selectedCampaignId.value = keep?.id ?? campaigns.value[0]?.id ?? null
  await loadCreatives()
}

async function loadCreatives() {
  if (!selectedCampaignId.value) {
    creatives.value = []
    return
  }
  creatives.value = await advertisingApi.admin.creatives(selectedCampaignId.value, 50)
}

function reload() {
  void bootstrap()
}

onMounted(() => {
  if (canRead.value) void bootstrap()
})

function onAdvertiserChange() {
  selectedCampaignId.value = null
  void loadAdvertiserScope()
}

function selectCampaign(campaign: AdvertisingCampaignResponse) {
  selectedCampaignId.value = campaign.id
  void loadCreatives().then(() => {
    void nextTick(() => rootEl.value?.querySelector<HTMLElement>('[data-test="ad-plan-title"]')?.focus({ preventScroll: true }))
  })
}

/* ---- 审计子视图 ---- */
async function openAudit() {
  auditMode.value = true
  try {
    audits.value = await advertisingApi.admin.audits({ limit: 100 })
  } catch (error) {
    ElMessage.error(readableError(error, '审计记录读取失败'))
  }
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>('[data-test="ad-audit-back"]')?.focus())
}

function closeAudit() {
  auditMode.value = false
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>('[data-test="ad-open-audit"]')?.focus())
}

/* ---- 弹层 ---- */
function openDialog(kind: Exclude<typeof dialogKind.value, null>) {
  dialogKind.value = kind
  dialogError.value = ''
  dialogBusy.value = false
  form.value.reasonCode = ''
  form.value.reasonText = ''
  if (kind === 'plan') {
    form.value.campaignName = ''
    form.value.venueId = null
    form.value.effectiveFrom = new Date(Date.now() + 3600_000).toISOString().slice(0, 16)
    form.value.effectiveUntil = new Date(Date.now() + 14 * 86400_000).toISOString().slice(0, 16)
  }
  if (kind === 'creative') {
    form.value.versionNo = creatives.value.reduce((max, item) => Math.max(max, item.versionNo), 0) + 1
    form.value.headline = ''
    form.value.bodyText = ''
    form.value.callToAction = 'VIEW'
    form.value.targetType = 'VENUE_LIST'
    form.value.targetId = null
  }
  void nextTick(() => dialogEl.value?.showModal())
}

function openAdvertiserDialog() {
  form.value.name = ''
  openDialog('advertiser')
}

function openPlanDialog() {
  openDialog('plan')
}

function openCreativeDialog() {
  openDialog('creative')
}

function openRejectDialog(type: 'CAMPAIGN' | 'CREATIVE', id: number) {
  rejectTarget.value = { type, id }
  openDialog('reject')
}

function closeDialog() {
  dialogEl.value?.close()
}

function onDialogClose() {
  dialogKind.value = null
  rejectTarget.value = null
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>('[data-test="ad-new-advertiser"]')?.focus({ preventScroll: true }))
}

async function submitDialog() {
  if (dialogBusy.value) return
  dialogBusy.value = true
  dialogError.value = ''
  try {
    if (dialogKind.value === 'advertiser') {
      const created = await advertisingApi.admin.createAdvertiser({ advertiserName: form.value.name.trim() })
      advertisers.value = [...advertisers.value, created]
      selectedAdvertiserId.value = created.id
      allCampaigns.value = []
      await loadAdvertiserScope()
      ElMessage.success('广告主已创建（有效状态）。')
    } else if (dialogKind.value === 'plan') {
      if (form.value.effectiveUntil <= form.value.effectiveFrom) {
        dialogError.value = '结束时间必须晚于开始时间。'
        return
      }
      const created = await advertisingApi.admin.createCampaign({
        advertiserId: selectedAdvertiserId.value!,
        venueId: form.value.venueId,
        campaignName: form.value.campaignName.trim(),
        effectiveFrom: form.value.effectiveFrom,
        effectiveUntil: form.value.effectiveUntil
      })
      campaigns.value = [...campaigns.value, created]
      selectedCampaignId.value = created.id
      await loadCreatives()
      ElMessage.success('计划草稿已保存。提交审核前创意不能发布。')
    } else if (dialogKind.value === 'creative') {
      if (form.value.targetType !== 'VENUE_LIST' && !form.value.targetId) {
        dialogError.value = '详情类站内目标必须填写目标编号。'
        return
      }
      const created = await advertisingApi.admin.createCreative({
        campaignId: selectedCampaignId.value!,
        versionNo: form.value.versionNo,
        headline: form.value.headline.trim(),
        bodyText: form.value.bodyText.trim(),
        callToAction: form.value.callToAction,
        targetType: form.value.targetType,
        targetId: form.value.targetId
      })
      creatives.value = [...creatives.value, created]
      ElMessage.success('创意版本已保存。')
    } else if (dialogKind.value === 'reject' && rejectTarget.value) {
      if (!form.value.reasonCode.trim() || !form.value.reasonText.trim()) {
        dialogError.value = '拒绝原因代码与说明都必须填写。'
        return
      }
      if (rejectTarget.value.type === 'CAMPAIGN') {
        await advertisingApi.admin.rejectCampaign(rejectTarget.value.id, {
          reasonCode: form.value.reasonCode.trim(), reasonText: form.value.reasonText.trim()
        })
      } else {
        await advertisingApi.admin.rejectCreative(rejectTarget.value.id, {
          reasonCode: form.value.reasonCode.trim(), reasonText: form.value.reasonText.trim()
        })
      }
      ElMessage.success('已拒绝并记录原因。')
      await refreshScope()
    }
    closeDialog()
  } catch (error) {
    dialogError.value = readableError(error, '操作失败，请重试。')
  } finally {
    dialogBusy.value = false
  }
}

async function refreshScope() {
  allCampaigns.value = await advertisingApi.admin.campaigns({ limit: 200 })
  campaigns.value = allCampaigns.value.filter((item) => item.advertiserId === selectedAdvertiserId.value)
  const keep = campaigns.value.find((item) => item.id === selectedCampaignId.value)
  selectedCampaignId.value = keep?.id ?? campaigns.value[0]?.id ?? null
  await loadCreatives()
}

async function runTransition(task: () => Promise<unknown>, successText: string) {
  try {
    await task()
    ElMessage.success(successText)
    await refreshScope()
  } catch (error) {
    ElMessage.error(readableError(error, '状态更新失败'))
  }
}

function campaignAction(action: 'submit' | 'approve' | 'publish' | 'pause' | 'retire') {
  const campaign = selectedCampaign.value
  if (!campaign) return
  const id = campaign.id
  const text = { submit: '计划已提交审核。', approve: '计划已批准，可发布。', publish: '计划已发布（不级联创意）。', pause: '计划已暂停。', retire: '计划已下线。' }[action]
  const task = () => {
    if (action === 'submit') return advertisingApi.admin.submitCampaignReview(id)
    if (action === 'approve') return advertisingApi.admin.approveCampaign(id)
    if (action === 'publish') return advertisingApi.admin.publishCampaign(id)
    if (action === 'pause') return advertisingApi.admin.pauseCampaign(id, {})
    return advertisingApi.admin.retireCampaign(id, {})
  }
  void runTransition(task, text)
}

function creativeAction(creative: AdvertisingCreativeResponse, action: 'submit' | 'approve' | 'publish' | 'pause' | 'retire') {
  const id = creative.id
  const text = { submit: '创意已提交审核。', approve: '创意已批准，可发布。', publish: '创意已发布（不级联计划）。', pause: '创意已暂停。', retire: '创意已下线。' }[action]
  const task = () => {
    if (action === 'submit') return advertisingApi.admin.submitCreativeReview(id)
    if (action === 'approve') return advertisingApi.admin.approveCreative(id)
    if (action === 'publish') return advertisingApi.admin.publishCreative(id)
    if (action === 'pause') return advertisingApi.admin.pauseCreative(id, {})
    return advertisingApi.admin.retireCreative(id, {})
  }
  void runTransition(task, text)
}

function transitionAdvertiser(action: 'suspend' | 'retire') {
  const advertiser = selectedAdvertiser.value
  if (!advertiser) return
  const task = () => action === 'suspend'
    ? advertisingApi.admin.suspendAdvertiser(advertiser.id, {})
    : advertisingApi.admin.retireAdvertiser(advertiser.id, {})
  void runTransition(task, action === 'suspend' ? '广告主已停用。' : '广告主已下线。')
}
</script>
