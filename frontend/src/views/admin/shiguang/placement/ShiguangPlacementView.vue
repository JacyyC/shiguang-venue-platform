<template>
  <div ref="rootEl" class="sga-gov" data-test="sga-pl-root">
    <!-- 自营宣传审计子视图（placement:audit:view 专有；参考 ownedAuditView） -->
    <template v-if="auditMode">
      <button class="btn" type="button" data-test="pl-audit-back" @click="closeAudit">返回候选</button>
      <div class="g-toolbar">
        <div>
          <h2>自营决策与事件</h2>
          <p>HOME_PRIMARY · 有界审计记录；不展示原始令牌</p>
        </div>
      </div>
      <h3>决策记录</h3>
      <div class="table-wrap" data-test="pl-decision-table">
        <table>
          <thead>
            <tr><th>决策 / 候选</th><th>状态</th><th>生成 / 到期</th><th>来源快照</th></tr>
          </thead>
          <tbody>
            <tr v-for="decision in decisions" :key="decision.id">
              <td>{{ decision.decisionNo }} / {{ candidateNoOf(decision.candidateVersionId) }}</td>
              <td>{{ decisionStatusLabel(decision.status) }}</td>
              <td>{{ shortTime(decision.createTime) }} / {{ shortTime(decision.expiresAt) }}</td>
              <td>{{ sourceSnapshotText(decision.sourceSnapshot) }}</td>
            </tr>
            <tr v-if="!decisions.length"><td colspan="4">当前没有决策记录。</td></tr>
          </tbody>
        </table>
      </div>
      <section class="g-section">
        <h3>事件与接收结果</h3>
        <div class="table-wrap" data-test="pl-event-table">
          <table>
            <thead>
              <tr><th>决策</th><th>事件</th><th>接收结果</th><th>可见条件</th></tr>
            </thead>
            <tbody>
              <tr v-for="event in events" :key="event.id">
                <td>{{ decisionNoOf(event.decisionId) }}</td>
                <td>{{ eventTypeLabel(event.eventType) }}</td>
                <td>{{ eventResultText(event) }}</td>
                <td>{{ visibleConditionText(event) }}</td>
              </tr>
              <tr v-if="!events.length"><td colspan="4">当前没有事件记录。</td></tr>
            </tbody>
          </table>
        </div>
        <p class="g-note">自营事件接收与商业归因独立，不能混成同一份曝光或转化统计。</p>
      </section>
    </template>

    <!-- 自营宣传主面（参考 ownedView） -->
    <template v-else>
      <div v-if="!canRead" class="g-empty" data-test="pl-denied">
        <h2>当前没有自营宣传权限</h2>
        <p>需要自营宣传的管理、发布或审计权限。</p>
      </div>

      <template v-else>
        <section v-if="loading" class="g-empty" role="status" data-test="pl-loading">
          <h2>正在读取候选</h2>
          <p>请稍候，正在核对所选范围。</p>
          <progress aria-label="正在加载"></progress>
        </section>

        <div v-else-if="loadError" class="g-empty" role="alert" data-test="pl-error">
          <h2>暂时无法读取候选</h2>
          <p>{{ loadError }}</p>
          <button class="btn primary" type="button" data-test="pl-reload" @click="reload">重新查询</button>
        </div>

        <div v-else-if="!candidates.length" class="g-empty" data-test="pl-empty">
          <h2>当前没有自营候选</h2>
          <p>从已有全局公告、价格活动或社区活动建立引用候选。</p>
        </div>

        <template v-else>
          <div class="g-toolbar">
            <div>
              <h2>首页自营宣传位</h2>
              <p>HOME_PRIMARY · 引用已有全局公告、价格活动与社区活动</p>
            </div>
            <div class="g-actions">
              <button
                v-if="canManage"
                class="btn primary"
                type="button"
                data-test="pl-new-candidate"
                @click="openCandidateDialog()"
              >新建引用候选</button>
              <button
                v-if="canAudit"
                class="btn"
                type="button"
                data-test="pl-open-audit"
                @click="openAudit"
              >决策与事件记录</button>
            </div>
          </div>

          <div class="g-source-layout">
            <div class="g-source-list" role="group" aria-label="自营候选目录" data-test="pl-candidate-list">
              <button
                v-for="candidate in candidates"
                :key="candidate.id"
                type="button"
                :data-test="`pl-candidate-${candidate.candidateNo}`"
                :aria-pressed="selected?.id === candidate.id"
                @click="selectCandidate(candidate)"
              >
                <span class="g-muted">{{ placementSourceLabel(candidate.sourceType) }} · {{ candidate.candidateNo }}</span>
                <h3>{{ previewTitle(candidate) }}</h3>
                <span class="g-state" :data-state="candidate.status">{{ placementStatusLabel(candidate.status) }}</span>
                <p>{{ candidate.sourceVersion || '—' }}</p>
              </button>
            </div>

            <section v-if="selected">
              <div class="g-proof" data-test="pl-proof">
                <div class="g-kicker">{{ placementSourceLabel(selected.sourceType) }} · 来源 {{ selected.sourceId }}</div>
                <h2 data-test="pl-proof-title">{{ previewTitle(selected) }}</h2>
                <template v-if="canPreview && sourcePreview && sourceAvailable">
                  <p>{{ sourcePreview.summary }}</p>
                  <span class="g-muted">站内目标：{{ sourcePreview.deepLink }} · 来源读取版本 {{ sourcePreview.sourceVersion || '—' }}</span>
                </template>
                <p v-else-if="!canPreview" class="g-note">当前仅有审计权限，不调用来源预览；候选引用元数据仍可核对。</p>
                <p v-else class="g-note" data-test="pl-source-unavailable">当前来源不可用，不能以旧摘要代替最新来源检查。</p>
              </div>
              <dl class="g-meta" data-test="pl-meta">
                <dt>候选状态</dt>
                <dd><span class="g-state" :data-state="selected.status">{{ placementStatusLabel(selected.status) }}</span></dd>
                <dt>引用版本</dt>
                <dd>{{ selected.sourceVersion || '—' }}</dd>
                <dt>生效时间</dt>
                <dd>{{ dateText(selected.effectiveFrom) }} 至 {{ dateText(selected.effectiveUntil) }}</dd>
                <dt>受众条件</dt>
                <dd>{{ audienceText(selected) }}</dd>
                <dt>优先级</dt>
                <dd>{{ selected.priority }}</dd>
                <dt>频次上限</dt>
                <dd>每人 {{ selected.frequencyCapWindowHours }} 小时内 {{ selected.frequencyCapPerUser }} 次</dd>
                <dt>规则版本</dt>
                <dd>{{ selected.ruleVersion || '—' }}</dd>
              </dl>
              <div class="g-actions">
                <button
                  v-if="canPublish && ['DRAFT', 'PAUSED'].includes(selected.status)"
                  class="btn primary"
                  type="button"
                  data-test="pl-publish"
                  @click="transition('publish')"
                >发布候选</button>
                <button
                  v-if="canPublish && selected.status === 'PUBLISHED'"
                  class="btn"
                  type="button"
                  data-test="pl-pause"
                  @click="transition('pause')"
                >暂停候选</button>
                <button
                  v-if="canPublish && ['PUBLISHED', 'PAUSED'].includes(selected.status)"
                  class="btn danger"
                  type="button"
                  data-test="pl-retire"
                  @click="transition('retire')"
                >下线候选</button>
                <button
                  v-if="canManage"
                  class="btn"
                  type="button"
                  data-test="pl-copy-candidate"
                  @click="openCandidateDialog(selected)"
                >建立新的引用候选</button>
              </div>
              <p class="g-note">候选发布后也不保证每次展示。决策仍核对来源、有效期、受众与频次；已有候选不直接改写，新的条件通过新候选承接。</p>
            </section>
          </div>
        </template>
      </template>
    </template>

    <!-- 新建候选弹层（B5-G4） -->
    <dialog ref="dialogEl" class="g-dialog" aria-labelledby="pl-dialog-title" @close="onDialogClose">
      <div class="g-dialog-header">
        <h2 id="pl-dialog-title">{{ copyBase ? '建立新的引用候选' : '新建自营引用候选' }}</h2>
        <button class="btn" type="button" aria-label="关闭对话框" @click="closeDialog">关闭</button>
      </div>
      <form @submit.prevent="submitCandidate">
        <p class="g-muted">引用已有来源，不在此另写标题、正文或外部跳转。</p>
        <div class="field">
          <label for="pl-source-type">来源类型</label>
          <select id="pl-source-type" v-model="form.sourceType" data-test="pl-source-type" @change="onFormSourceChange">
            <option value="GLOBAL_NOTICE">全局公告</option>
            <option value="PRICE_PROMOTION">价格活动</option>
            <option value="COMMUNITY_ACTIVITY">社区活动</option>
          </select>
        </div>
        <div class="field">
          <label for="pl-source-id">来源编号</label>
          <input id="pl-source-id" v-model.number="form.sourceId" data-test="pl-source-id" type="number" min="1" required @change="onFormSourceChange">
        </div>
        <p class="g-note" data-test="pl-source-preview">
          {{ formPreviewText }}
        </p>
        <div class="g-form-grid">
          <div class="field">
            <label for="pl-from">开始时间</label>
            <input id="pl-from" v-model="form.effectiveFrom" data-test="pl-from" type="datetime-local" required>
          </div>
          <div class="field">
            <label for="pl-until">结束时间</label>
            <input id="pl-until" v-model="form.effectiveUntil" data-test="pl-until" type="datetime-local" required>
          </div>
          <div class="field">
            <label for="pl-audience">受众</label>
            <select id="pl-audience" v-model="form.audienceType" data-test="pl-audience">
              <option value="ALL_AUTHENTICATED">所有已登录用户</option>
              <option value="MEMBER_LEVEL">指定会员等级</option>
            </select>
          </div>
          <div class="field">
            <label for="pl-level">会员等级代码（指定时必填）</label>
            <input id="pl-level" v-model="form.audienceLevelCode" data-test="pl-level" type="text" maxlength="64">
          </div>
          <div class="field">
            <label for="pl-priority">优先级</label>
            <input id="pl-priority" v-model.number="form.priority" data-test="pl-priority" type="number">
          </div>
          <div class="field">
            <label for="pl-cap">每人频次上限</label>
            <input id="pl-cap" v-model.number="form.frequencyCapPerUser" data-test="pl-cap" type="number" min="1" required>
          </div>
          <div class="field">
            <label for="pl-hours">频次窗口（小时）</label>
            <input id="pl-hours" v-model.number="form.frequencyCapWindowHours" data-test="pl-hours" type="number" min="1" required>
          </div>
        </div>
        <p class="g-error" role="alert">{{ dialogError }}</p>
        <div class="g-actions">
          <button class="btn" type="button" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="pl-candidate-submit" :disabled="dialogBusy">
            {{ dialogBusy ? '保存中…' : '保存候选草稿' }}
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
/* 拾光自营宣传面（SHIGUANG-BATCH-05 S06）：一比一承接冻结参考 advertising.js 的 owned 工作面与
   自营审计子视图（B5-G2/G3/G4）。g-* 基元沿用 B4 迁移的 shiguang-admin-governance.css（.sga-gov）。
   真实合同见本项 contracts.md §2（广告附件 §2）；预览审阅情景/固定示例数据不迁入。 */
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { placementApi, placementSourceLabel, placementStatusLabel } from '@/api/placement'
import { useAuthStore } from '@/stores/auth'
import type {
  PlacementSourceType,
  PlacementCandidateVersionResponse,
  PlacementDecisionAuditResponse,
  PlacementEventAuditResponse,
  PlacementSlotResponse,
  PlacementSourcePreviewResponse
} from '@/api/types'
import type { PlacementEventType } from '@/api/types'

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('placement:manage'))
const canPublish = computed(() => auth.hasPermission('placement:publish'))
const canAudit = computed(() => auth.hasPermission('placement:audit:view'))
const canRead = computed(() => canManage.value || canPublish.value || canAudit.value)
/* 审计者不调用来源预览（B5-G2）：canPreview = manage||publish，与真实合同一致 */
const canPreview = computed(() => canManage.value || canPublish.value)

const loading = ref(true)
const loadError = ref('')
const rootEl = ref<HTMLElement>()
const dialogEl = ref<HTMLDialogElement>()

const auditMode = ref(false)
const slots = ref<PlacementSlotResponse[]>([])
const candidates = ref<PlacementCandidateVersionResponse[]>([])
const selected = ref<PlacementCandidateVersionResponse | null>(null)
const sourcePreview = ref<PlacementSourcePreviewResponse | null>(null)
const sourceAvailable = ref(true)
const decisions = ref<PlacementDecisionAuditResponse[]>([])
const events = ref<PlacementEventAuditResponse[]>([])

const dialogBusy = ref(false)
const dialogError = ref('')
const copyBase = ref<PlacementCandidateVersionResponse | null>(null)
const form = ref({
  sourceType: 'GLOBAL_NOTICE' as PlacementSourceType,
  sourceId: 0,
  effectiveFrom: '',
  effectiveUntil: '',
  audienceType: 'ALL_AUTHENTICATED' as 'ALL_AUTHENTICATED' | 'MEMBER_LEVEL',
  audienceLevelCode: '',
  priority: 0,
  frequencyCapPerUser: 2,
  frequencyCapWindowHours: 24
})
const formPreviewText = ref('填写来源编号后读取预览摘要。')
let returnFocus: HTMLElement | null = null

const homeSlotId = computed(() =>
  slots.value.find((slot) => slot.slotCode === 'HOME_PRIMARY')?.id ?? slots.value[0]?.id)

const previewTitle = (candidate: PlacementCandidateVersionResponse) => {
  /* B5-G3：canPreview 且来源可用 → 来源标题；否则降级为「类型 + 编号」 */
  if (canPreview.value && sourceAvailable.value
    && sourcePreview.value
    && sourcePreview.value.sourceType === candidate.sourceType
    && sourcePreview.value.sourceId === candidate.sourceId) {
    return sourcePreview.value.title
  }
  return `${placementSourceLabel(candidate.sourceType)} ${candidate.sourceId}`
}

const audienceText = (candidate: PlacementCandidateVersionResponse) =>
  candidate.audienceType === 'MEMBER_LEVEL'
    ? `会员等级精确匹配 ${candidate.audienceLevelCode || '（未填）'}`
    : '所有已登录用户'

const candidateNoOf = (candidateVersionId: number) =>
  candidates.value.find((item) => item.id === candidateVersionId)?.candidateNo || `#${candidateVersionId}`
const decisionNoOf = (decisionId: number) =>
  decisions.value.find((item) => item.id === decisionId)?.decisionNo || `#${decisionId}`

const decisionStatusLabel = (status?: string) =>
  ({ ISSUED: '已签发', CONSUMED: '已消费', EXPIRED: '已过期', REVOKED: '已撤销' }[status || ''] || status || '—')

const eventTypeLabel = (type?: PlacementEventType) => {
  const labels: Record<string, string> = {
    VISIBLE: '可见曝光', CLICK: '点击', CONVERSION: '转化', ATTRIBUTED: '已归因', CLIENT_VIEWABLE: '客户端可见'
  }
  return labels[type || ''] || type || '—'
}

const eventResultText = (event: PlacementEventAuditResponse) =>
  event.resultStatus === 'REJECTED'
    ? `已拒绝 · ${event.rejectionCode || 'UNKNOWN'}`
    : event.resultStatus === 'REPLAYED' ? '重放接收' : '已接受'

const visibleConditionText = (event: PlacementEventAuditResponse) =>
  event.visibleRatio == null ? '—' : `${Math.round(Number(event.visibleRatio) * 100)}% · ${event.visibleDurationMs ?? 0}毫秒`

const sourceSnapshotText = (snapshot: PlacementDecisionAuditResponse['sourceSnapshot']) =>
  snapshot ? `${placementSourceLabel(snapshot.sourceType || '')}${snapshot.sourceId ?? ''} · ${snapshot.sourceVersion || '—'}` : '—'

const dateText = (value?: string | null) => (value ? value.replace('T', ' ').slice(0, 16) : '—')
const shortTime = (value?: string | null) => (value ? value.replace('T', ' ').slice(5, 16) : '—')

const readableError = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

async function loadSourcePreview(candidate: PlacementCandidateVersionResponse) {
  if (!canPreview.value) {
    sourcePreview.value = null
    sourceAvailable.value = false
    return
  }
  try {
    sourcePreview.value = await placementApi.admin.previewSource(candidate.sourceType, candidate.sourceId)
    sourceAvailable.value = true
  } catch {
    /* 来源读取失败/不可用：按 B5-G3 降级，不以旧摘要代替 */
    sourcePreview.value = null
    sourceAvailable.value = false
  }
}

async function selectCandidate(candidate: PlacementCandidateVersionResponse) {
  selected.value = candidate
  await loadSourcePreview(candidate)
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>('#pl-proof-title')?.focus({ preventScroll: true }))
}

async function bootstrap() {
  loading.value = true
  loadError.value = ''
  try {
    if (!slots.value.length) {
      slots.value = await placementApi.admin.slots()
    }
    const slotId = homeSlotId.value
    candidates.value = await placementApi.admin.candidates(slotId, 100)
    if (candidates.value.length) {
      const keep = candidates.value.find((item) => item.id === selected.value?.id)
      await selectCandidate(keep || candidates.value[0])
    } else {
      selected.value = null
      sourcePreview.value = null
    }
  } catch (error) {
    loadError.value = readableError(error, '候选读取失败，请重试。')
  } finally {
    loading.value = false
  }
}

function reload() {
  void bootstrap()
}

onMounted(() => {
  if (canRead.value) void bootstrap()
})

async function openAudit() {
  auditMode.value = true
  returnFocus = document.activeElement as HTMLElement | null
  try {
    const slotId = homeSlotId.value
    const [decisionPage, eventPage] = await Promise.all([
      placementApi.admin.decisions(slotId, 50),
      placementApi.admin.events(slotId, 50)
    ])
    decisions.value = decisionPage
    events.value = eventPage
  } catch (error) {
    ElMessage.error(readableError(error, '审计记录读取失败'))
  }
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>('[data-test="pl-audit-back"]')?.focus())
}

function closeAudit() {
  auditMode.value = false
  const target = returnFocus
  void nextTick(() => target?.focus({ preventScroll: true }))
  returnFocus = null
}

/* ---- 新建候选弹层（B5-G4） ---- */
function openCandidateDialog(base?: PlacementCandidateVersionResponse) {
  copyBase.value = base || null
  const from = base?.effectiveFrom ?? new Date(Date.now() + 3600_000).toISOString().slice(0, 16)
  const until = base?.effectiveUntil ?? new Date(Date.now() + 14 * 86400_000).toISOString().slice(0, 16)
  form.value = {
    sourceType: base?.sourceType || 'GLOBAL_NOTICE',
    sourceId: base?.sourceId || 0,
    effectiveFrom: from,
    effectiveUntil: until,
    audienceType: (base?.audienceType as 'ALL_AUTHENTICATED' | 'MEMBER_LEVEL') || 'ALL_AUTHENTICATED',
    audienceLevelCode: base?.audienceLevelCode || '',
    priority: base?.priority ?? 0,
    frequencyCapPerUser: base?.frequencyCapPerUser ?? 2,
    frequencyCapWindowHours: base?.frequencyCapWindowHours ?? 24
  }
  formPreviewText.value = '填写来源编号后读取预览摘要。'
  dialogError.value = ''
  dialogBusy.value = false
  void nextTick(() => dialogEl.value?.showModal())
}

function closeDialog() {
  dialogEl.value?.close()
}

function onDialogClose() {
  copyBase.value = null
  const target = returnFocus || rootEl.value?.querySelector<HTMLElement>('[data-test="pl-new-candidate"]')
  target?.focus({ preventScroll: true })
  returnFocus = null
}

async function onFormSourceChange() {
  if (!form.value.sourceId || !canPreview.value) {
    formPreviewText.value = '填写来源编号后读取预览摘要。'
    return
  }
  try {
    const preview = await placementApi.admin.previewSource(form.value.sourceType, Number(form.value.sourceId))
    formPreviewText.value = `${preview.summary} · ${preview.sourceVersion || '—'}`
  } catch {
    formPreviewText.value = '来源当前不可用，请选择仍有效的来源。'
  }
}

async function submitCandidate() {
  if (dialogBusy.value || !canManage.value) return
  if (!form.value.sourceId) {
    dialogError.value = '请填写来源编号。'
    return
  }
  if (form.value.effectiveUntil <= form.value.effectiveFrom) {
    dialogError.value = '结束时间必须晚于开始时间。'
    return
  }
  if (form.value.audienceType === 'MEMBER_LEVEL' && !form.value.audienceLevelCode.trim()) {
    dialogError.value = '指定会员等级时必须填写等级代码。'
    return
  }
  dialogBusy.value = true
  dialogError.value = ''
  try {
    const created = await placementApi.admin.createCandidate({
      slotId: homeSlotId.value!,
      sourceType: form.value.sourceType,
      sourceId: Number(form.value.sourceId),
      effectiveFrom: form.value.effectiveFrom,
      effectiveUntil: form.value.effectiveUntil,
      audienceType: form.value.audienceType,
      audienceLevelCode: form.value.audienceType === 'MEMBER_LEVEL' ? form.value.audienceLevelCode.trim() : undefined,
      priority: form.value.priority,
      frequencyCapPerUser: form.value.frequencyCapPerUser,
      frequencyCapWindowHours: form.value.frequencyCapWindowHours
    })
    candidates.value = [created, ...candidates.value]
    await selectCandidate(created)
    closeDialog()
    ElMessage.success('候选草稿已保存。发布前会重新核对来源。')
  } catch (error) {
    dialogError.value = readableError(error, '候选保存失败，请重试。')
  } finally {
    dialogBusy.value = false
  }
}

async function transition(action: 'publish' | 'pause' | 'retire') {
  const candidate = selected.value
  if (!candidate || !canPublish.value) return
  try {
    const updated = action === 'publish'
      ? await placementApi.admin.publish(candidate.id)
      : action === 'pause'
        ? await placementApi.admin.pause(candidate.id)
        : await placementApi.admin.retire(candidate.id)
    candidates.value = candidates.value.map((item) => item.id === updated.id ? updated : item)
    selected.value = updated
    ElMessage.success(`候选已${placementStatusLabel(updated.status)}。已有候选不直接改写，新条件用新候选承接。`)
  } catch (error) {
    ElMessage.error(readableError(error, '候选状态更新失败'))
  }
}
</script>
