<template>
  <div ref="rootEl" class="sga-notice" data-test="sga-notice-root">
    <div class="a-topline">
      <div>
        <p class="a-eyebrow">公告与通知 / {{ view === 'compose' ? '发布前核对' : '平台触达证据' }}</p>
        <h1>{{ HEADINGS[view].title }}</h1>
        <p class="a-intro">{{ HEADINGS[view].intro }}</p>
      </div>
    </div>

    <div class="a-tabs" role="group" aria-label="公告与通知任务">
      <button
        v-for="item in TABS"
        :key="item.key"
        type="button"
        class="a-tab"
        :data-test="`tab-${item.key}`"
        :aria-pressed="view === item.key"
        @click="switchView(item.key)"
      >
        {{ item.label }}
      </button>
    </div>

    <section v-if="surface === 'loading'" class="a-state" aria-busy="true" data-test="surface-loading">
      <h2>正在读取当前范围</h2>
      <p class="a-muted">内容与统计口径将在加载后完整显示。</p>
      <button class="a-btn" type="button" data-test="surface-reload" @click="bootstrap">重新读取</button>
    </section>

    <section v-else-if="surface === 'error'" class="a-state" role="alert" data-test="surface-error">
      <h2>暂时无法读取内容</h2>
      <p class="a-muted">这不是空数据。编辑内容保留，可重试读取。</p>
      <button class="a-btn primary" type="button" data-test="surface-reload" @click="bootstrap">重试读取</button>
    </section>

    <template v-else>
      <section v-if="view === 'compose'" data-test="pane-compose">
        <div v-if="!canManage" class="a-state" role="status" data-test="denied-compose">
          <h2>当前仅有触达分析权限</h2>
          <p class="a-muted">可以查看投递与实验，不能预览受众或发布公告。</p>
        </div>
        <div v-else-if="!activeVenueId" class="a-state" data-test="lock-compose">
          <h2>没有可发布公告的场馆</h2>
          <p class="a-muted">请先恢复授权场馆，受众核对与发布不会沿用此前范围。</p>
        </div>
        <div v-else class="a-compose">
          <section>
            <h2>公告内容</h2>
            <p class="a-meta">发布场馆：{{ venueName }} · {{ targetScopeLabel(draft.targetScope) }}</p>
            <label class="a-field">
              公告标题 <span class="a-muted">必填，最多 80 字</span>
              <input
                id="notice-title"
                v-model="draft.title"
                data-test="notice-title"
                maxlength="80"
                autocomplete="off"
              >
            </label>
            <label class="a-field">
              公告正文 <span class="a-muted">必填，最多 2000 字</span>
              <textarea
                id="notice-body"
                v-model="draft.content"
                data-test="notice-body"
                maxlength="2000"
                rows="10"
              ></textarea>
            </label>
            <template v-if="canChooseScope">
              <label class="a-field">
                接收人群
                <select v-model="draft.targetScope" data-test="notice-scope">
                  <option v-for="option in scopeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
                </select>
              </label>
              <label v-if="draft.targetScope === 'MEMBER_LEVEL'" class="a-field">
                会员等级 ID <span class="a-muted">服务端必填</span>
                <input v-model="draft.memberLevelId" data-test="notice-member-level" inputmode="numeric">
              </label>
              <label v-if="draft.targetScope === 'CAMPAIGN_PARTICIPANTS'" class="a-field">
                活动 ID <span class="a-muted">服务端必填</span>
                <input v-model="draft.campaignId" data-test="notice-campaign" inputmode="numeric">
              </label>
              <label v-if="draft.targetScope === 'SPECIFIC_USERS'" class="a-field">
                指定用户 ID <span class="a-muted">以英文逗号分隔</span>
                <input v-model="draft.userIds" data-test="notice-user-ids" autocomplete="off">
              </label>
            </template>
            <p v-if="draftError" class="a-error" role="alert" data-test="draft-error">{{ draftError }}</p>

            <section class="a-audience">
              <h3>核对接收范围</h3>
              <p class="a-muted">{{ audienceScopeSentence }}</p>
              <div data-test="audience-summary">
                <template v-if="audience && !audienceStale">
                  <div class="a-counts">
                    <div><strong>{{ audience.audienceCount }}</strong><span>预计受众</span></div>
                    <div><strong>{{ audience.deliverableCount }}</strong><span>可投递</span></div>
                    <div><strong>{{ audience.excludedCount }}</strong><span>已排除</span></div>
                  </div>
                  <p v-for="row in excludeRows" :key="row.label" class="a-meta" data-test="exclude-row">
                    {{ row.label }} {{ row.count }} 人
                  </p>
                  <p class="a-meta">以上为预览时的范围，实际以发布后的批次快照为准。</p>
                </template>
                <p v-else class="a-muted" data-test="audience-pending">
                  {{ audienceStale ? '内容已修改，请重新预览受众。' : '尚未核对受众。' }}
                </p>
              </div>
              <div class="a-row">
                <button class="a-btn" type="button" data-test="open-preview" @click="openPreview($event)">
                  {{ audience && !audienceStale ? '重新预览受众' : '预览受众' }}
                </button>
                <button
                  class="a-btn primary"
                  type="button"
                  data-test="open-publish"
                  :disabled="!audience || audienceStale || publishing"
                  @click="openPublish($event)"
                >
                  确认发布
                </button>
              </div>
            </section>

            <div v-if="published" class="a-notice" style="margin-top: 24px" role="status" data-test="published-note">
              <strong>公告已发布 · 批次 {{ published.batchNo || published.batchId }}</strong>
              <p>投递仍在排队，发布成功不代表所有人已收到。</p>
              <button class="a-linkbtn" type="button" data-test="show-published" @click="gotoPublished">查看投递批次 →</button>
            </div>
          </section>

          <aside>
            <p class="a-miniheading">收件人阅读预览</p>
            <article class="a-reading">
              <span class="a-pill">场馆公告</span>
              <h2 data-test="reading-title">{{ draft.title || '公告标题' }}</h2>
              <p class="a-reading-body" data-test="reading-body">{{ draft.content || '正文会显示在这里。' }}</p>
              <footer>{{ venueName }} · 发布日期将以实际发布为准</footer>
            </article>
            <p class="a-meta" style="margin-top: 20px">保留完整调整范围与帮助入口，读者不必从标题猜测影响。</p>
          </aside>
        </div>
      </section>

      <section v-else-if="view === 'delivery'" data-test="pane-delivery">
        <div v-if="!canAnalytics" class="a-state" role="status" data-test="denied-delivery">
          <h2>未获得触达分析权限</h2>
          <p class="a-muted">当前可编辑场馆公告，不能查看平台批次、受众与实验。</p>
        </div>
        <div v-else-if="!batchEntries.length" class="a-state" data-test="empty-delivery">
          <h2>当前范围没有记录</h2>
          <p class="a-muted">还没有符合条件的投递批次或实验。</p>
        </div>
        <div v-else class="a-delivery-layout">
          <aside class="a-batch-menu" aria-label="批次与渠道">
            <button
              v-for="entry in batchEntries"
              :key="entry.key"
              type="button"
              :data-test="`menu-${entry.key}`"
              :aria-pressed="selectedKey === entry.key"
              @click="selectEntry(entry, $event)"
            >
              <strong>{{ entry.title }}</strong>
              <small>{{ entry.note }}<br>{{ entry.detail }}</small>
            </button>
          </aside>
          <section>
            <div class="a-topline">
              <div>
                <p class="a-miniheading">{{ selectedIsEmail ? '业务通知 · 发件箱记录' : `批次 ${selectedBatch?.batchNo || '—'} · 场馆公告` }}</p>
                <h2>{{ selectedIsEmail ? '渠道已接受，仍需区分下游结果。' : (selectedBatch?.batchNo || '批次未记录') }}</h2>
                <p class="a-meta">
                  {{ providerCaption }}<br>
                  {{ selectedIsEmail ? '事务邮件包含独立业务事件；此处不显示原始载荷。' : `受众范围：${scopeCaption}` }}
                </p>
              </div>
              <span class="a-pill" :class="{ attention: !selectedIsEmail }">{{ evidencePill }}</span>
            </div>
            <div class="a-evidence" aria-label="触达证据阶段" data-test="evidence">
              <div v-for="cell in evidenceCells" :key="cell.label" :data-test="`evidence-${cell.label}`">
                <small>{{ cell.label }}</small>
                <strong>{{ cell.value }}</strong>
                <span>{{ cell.note }}</span>
              </div>
            </div>
            <div class="a-notice" data-test="evidence-note">{{ evidenceNote }}</div>
            <div class="a-topline">
              <h3>{{ selectedIsEmail ? '业务邮件发件箱' : '需要继续处理的受众' }}</h3>
              <button
                v-if="!selectedIsEmail && selectedBatch"
                class="a-linkbtn"
                type="button"
                data-test="open-audiences"
                @click="openAudiences($event)"
              >
                查看完整受众快照 →
              </button>
            </div>
            <div v-if="outboxError" class="a-error" role="alert" data-test="outbox-error">{{ outboxError }}</div>
            <div v-else-if="!outboxes.length" class="a-meta" data-test="outbox-empty">当前范围内没有发件箱记录。</div>
            <div v-else class="a-scroll">
              <table data-test="outbox-table">
                <thead>
                  <tr><th>对象</th><th>当前状态</th><th>尝试 / 原因</th><th>记录</th></tr>
                </thead>
                <tbody>
                  <tr v-for="row in outboxes" :key="row.outboxId">
                    <td>{{ outboxObjectText(row) }}<br><small class="a-muted">{{ row.outboxNo }}</small></td>
                    <td>{{ outboxStatusLabel(row) }}</td>
                    <td>{{ outboxAttemptText(row) }}</td>
                    <td>
                      <button class="a-linkbtn" type="button" :data-test="`open-outbox-${row.outboxId}`" @click="openOutbox(row, $event)">
                        查看详情
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="a-meta" style="margin-top: 20px">{{ outboxFootnote }}</p>
            <div class="a-row" data-test="outbox-pager">
              <button
                class="a-btn"
                type="button"
                data-test="outbox-prev"
                :disabled="outboxPage.pageNum <= 1"
                @click="moveOutboxPage(-1)"
              >
                上一页
              </button>
              <span class="a-muted">{{ outboxPage.pageNum }} / {{ outboxPages }} 页 · 共 {{ outboxTotal }} 条</span>
              <button
                class="a-btn"
                type="button"
                data-test="outbox-next"
                :disabled="outboxPage.pageNum >= outboxPages"
                @click="moveOutboxPage(1)"
              >
                下一页
              </button>
            </div>
          </section>
        </div>
      </section>

      <section v-else data-test="pane-experiments">
        <div v-if="!canAnalytics" class="a-state" role="status" data-test="denied-experiments">
          <h2>未获得触达分析权限</h2>
          <p class="a-muted">当前可编辑场馆公告，不能查看平台批次、受众与实验。</p>
        </div>
        <template v-else>
          <div class="a-topline">
            <div>
              <h2>公告表达实验</h2>
              <p class="a-muted">定义只记录版本、样本范围与比例，渠道统计另行选择。</p>
            </div>
            <button class="a-btn primary" type="button" data-test="open-experiment" @click="openExperiment($event)">新建实验定义</button>
          </div>
          <div v-if="!experiments.length" class="a-state" data-test="empty-experiments">
            <h2>当前范围没有记录</h2>
            <p class="a-muted">还没有符合条件的投递批次或实验。</p>
          </div>
          <template v-else>
            <label class="a-field" style="max-width: 420px">
              查看实验
              <select v-model.number="selectedExperimentId" data-test="experiment-select" @change="loadExperimentResult">
                <option v-for="option in experimentOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
              </select>
            </label>
            <div class="a-topline">
              <div>
                <h2>{{ selectedExperiment?.name || '未命名实验' }}</h2>
                <p class="a-meta">{{ experimentMeta }}</p>
              </div>
              <span class="a-pill" data-test="experiment-status">{{ experimentStatusLabel(selectedExperiment?.status) }}</span>
            </div>
            <div class="a-versions" data-test="experiment-versions">
              <article v-for="variant in selectedVariants" :key="variant.variantCode" class="a-version" :data-test="`version-${variant.variantCode}`">
                <div class="a-row">
                  <strong>版本 {{ variant.variantCode }}</strong>
                  <span class="a-muted">{{ variant.allocationPercent }}% 样本</span>
                </div>
                <h3>{{ variant.title }}</h3>
                <p>{{ variant.content }}</p>
              </article>
            </div>
            <section class="a-text-block">
              <div class="a-topline">
                <h3>按渠道查看阅读表现</h3>
                <label>
                  统计来源
                  <select v-model="statsProviderKey" data-test="stats-provider" @change="loadExperimentResult">
                    <option v-for="provider in providerOptions" :key="provider.providerKey" :value="providerKey(provider)">
                      {{ provider.providerEnv }} / {{ provider.displayName }}
                    </option>
                  </select>
                </label>
              </div>
              <div data-test="experiment-results">
                <div v-if="statsIsEmail" class="a-notice">
                  当前选择为邮件渠道，站内阅读率不可用。请切回站内消息渠道查看该指标。
                </div>
                <div v-else-if="!resultRows.length" class="a-state" data-test="result-empty">
                  <h3>定义已保存，尚无投递结果</h3>
                  <p class="a-muted">创建定义不会自动发出公告，也不会生成统计样本。</p>
                </div>
                <template v-else>
                  <p class="a-meta">统计渠道：{{ resultCaption }}· 分母为该变体受众快照数。</p>
                  <template v-for="row in resultRows" :key="row.code">
                    <div class="a-result-row" :data-test="`result-${row.code}`">
                      <strong>版本 {{ row.code }}</strong>
                      <div class="a-bar" role="img" :aria-label="`版本 ${row.code} 阅读率 ${row.rate}`">
                        <span :style="{ width: row.rate }"></span>
                      </div>
                      <strong>{{ row.rate }}</strong>
                    </div>
                    <p class="a-meta">
                      已读 {{ row.readCount }} / 受众 {{ row.audienceCount }}，其余为未读；终态失败 {{ row.failedCount }}
                    </p>
                  </template>
                  <div class="a-notice">样本较小，阅读差异仅供观察。此处没有显著性结论，也不自动选定优胜版本。</div>
                </template>
              </div>
            </section>
          </template>
        </template>
      </section>
    </template>

    <dialog ref="dialogEl" aria-labelledby="a-dialog-title" @close="onDialogClose" @keydown="onDialogKeydown">
      <form data-test="dialog-form" @submit.prevent="confirmDialog">
        <h2 id="a-dialog-title">{{ dialog?.title }}</h2>
        <template v-if="dialog?.kind === 'preview'">
          <p>{{ dialog.bodyText }}</p>
          <div v-if="previewDraft" class="a-counts" data-test="preview-counts">
            <div><strong>{{ previewDraft.audienceCount }}</strong><span>预计受众</span></div>
            <div><strong>{{ previewDraft.deliverableCount }}</strong><span>可投递</span></div>
            <div><strong>{{ previewDraft.excludedCount }}</strong><span>已排除</span></div>
          </div>
          <p v-for="row in previewExcludeRows" :key="row.label" class="a-meta">{{ row.label }} {{ row.count }} 人</p>
          <p class="a-muted">人数由受众预览接口返回，实际以发布后的批次快照为准。</p>
        </template>

        <template v-else-if="dialog?.kind === 'publish'">
          <h3>{{ draft.title }}</h3>
          <p>{{ dialog.bodyText }}</p>
          <p class="a-muted">发布只创建公告与投递批次，站内消息与邮件任务由服务端排队后逐条投递。</p>
        </template>

        <template v-else-if="dialog?.kind === 'audiences'">
          <p class="a-meta">{{ dialog.bodyText }}</p>
          <div v-if="!audienceRows.length" class="a-muted" data-test="audience-empty">该批次暂无符合条件的受众记录。</div>
          <div v-else class="a-scroll">
            <table data-test="audience-table">
              <thead><tr><th>对象</th><th>投递</th><th>阅读 / 排除原因</th></tr></thead>
              <tbody>
                <tr v-for="row in audienceRows" :key="row.audienceId">
                  <td>{{ audienceRowCells(row).subject }}</td>
                  <td>{{ audienceRowCells(row).delivery }}</td>
                  <td>{{ audienceRowCells(row).read }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="a-meta">共 {{ audienceTotal }} 条，按 {{ audiencePage.pageSize }} 条分页；当前第 {{ audiencePage.pageNum }} 页。</p>
          <div class="a-row">
            <button class="a-btn" type="button" data-test="audience-prev" :disabled="audiencePage.pageNum <= 1" @click="moveAudiencePage(-1)">上一页</button>
            <button class="a-btn" type="button" data-test="audience-next" :disabled="audiencePage.pageNum * audiencePage.pageSize >= audienceTotal" @click="moveAudiencePage(1)">下一页</button>
          </div>
        </template>

        <template v-else-if="dialog?.kind === 'outbox'">
          <p><strong>{{ dialog.bodyText }}</strong></p>
          <p class="a-meta">{{ dialog.detail }}</p>
          <ol class="a-timeline" data-test="outbox-facts">
            <li v-for="row in dialog.facts" :key="row.label"><p>{{ row.label }}：{{ row.value }}</p></li>
          </ol>
          <div v-if="dialog.isEmail" class="a-notice">下游送达：未知；阅读：不可用。</div>
          <button
            v-if="dialog.retryable"
            type="button"
            class="a-btn"
            data-test="open-retry"
            :disabled="!canManage"
            @click="openRetry($event)"
          >
            重试此发件箱
          </button>
          <p v-if="dialog.retryable && !canManage" class="a-muted">当前可以查看，但缺少公告管理权限，不能重试。</p>
        </template>

        <template v-else-if="dialog?.kind === 'retry'">
          <p>{{ dialog.bodyText }}</p>
          <p class="a-meta">{{ dialog.detail }}</p>
          <p>{{ dialog.intro }}</p>
        </template>

        <template v-else-if="dialog?.kind === 'experiment'">
          <label class="a-field">
            实验名称
            <input v-model="experimentForm.name" name="name" data-test="field-experiment-name" maxlength="80" autocomplete="off">
          </label>
          <label class="a-field">
            样本范围
            <select v-model="experimentForm.sampleScope" name="scope" data-test="field-experiment-scope">
              <option v-for="option in scopeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
          <label class="a-field">
            版本 A 标题
            <input v-model="experimentForm.titleA" name="titleA" data-test="field-title-a" autocomplete="off">
          </label>
          <label class="a-field">
            版本 A 正文
            <textarea v-model="experimentForm.contentA" name="contentA" data-test="field-content-a" rows="3"></textarea>
          </label>
          <label class="a-field">
            版本 B 标题
            <input v-model="experimentForm.titleB" name="titleB" data-test="field-title-b" autocomplete="off">
          </label>
          <label class="a-field">
            版本 B 正文
            <textarea v-model="experimentForm.contentB" name="contentB" data-test="field-content-b" rows="3"></textarea>
          </label>
          <div class="a-formgrid">
            <label>
              版本 A 比例（%）
              <input v-model.number="experimentForm.allocationA" name="allocationA" data-test="field-allocation-a" type="number" min="1" max="99">
            </label>
            <label>
              版本 B 比例（%）
              <input v-model.number="experimentForm.allocationB" name="allocationB" data-test="field-allocation-b" type="number" min="1" max="99">
            </label>
          </div>
          <p class="a-meta" style="margin-top: 20px">目标指标：站内消息阅读率。创建定义不触发投递。</p>
        </template>

        <p v-if="dialog?.error" class="a-error" role="alert" data-test="dialog-error">{{ dialog.error }}</p>

        <div class="a-dialog-actions">
          <button type="button" class="a-btn" data-test="dialog-cancel" @click="closeDialog">
            {{ dialog?.kind === 'audiences' || dialog?.kind === 'outbox' ? '关闭' : '取消' }}
          </button>
          <button
            v-if="dialog?.submit"
            type="submit"
            class="a-btn primary"
            data-test="dialog-confirm"
            :disabled="dialog.busy"
          >
            {{ dialog.busy ? '处理中…' : dialog.submit }}
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useVenueAccessStore } from '@/stores/venueAccess'
import { noticeApi } from '@/api/notice'
import type {
  AudiencePreviewResponse,
  Notice,
  NoticeAudienceResponse,
  NoticeDeliveryBatchResponse,
  NoticeDeliveryOutboxResponse,
  NoticeDeliveryOverviewResponse,
  NoticeExperimentResponse,
  NoticeExperimentResultResponse,
  NotificationProviderOption
} from '@/api/types'
import { ISOLATED_REQUEST as isolated, focusable, formatHm, restoreFocusChain } from '../adminShared'
import {
  TARGET_SCOPE_LABELS,
  audienceRowCells,
  batchIdFromQuery,
  batchMenuEntries,
  batchStatusLabel,
  canRetryOutbox,
  emailEvidence,
  excludeReasonRows,
  experimentAllocationError,
  experimentOption,
  experimentPayload,
  experimentResultRows,
  experimentStatusLabel,
  inAppEvidence,
  initialViewFromRoute,
  outboxAttemptText,
  outboxFactRows,
  outboxObjectText,
  outboxStatusLabel,
  providerLine,
  readRateSentence,
  targetScopeLabel,
  type BatchMenuEntry,
  type EvidenceCell,
  type NoticeView
} from './noticeShared'

type SurfaceState = 'loading' | 'ready' | 'error'
type DialogKind = 'preview' | 'publish' | 'audiences' | 'outbox' | 'retry' | 'experiment'

interface DialogState {
  kind: DialogKind
  title: string
  submit: string
  error: string
  busy: boolean
  bodyText: string
  detail: string
  intro: string
  facts: Array<{ label: string; value: string }>
  isEmail: boolean
  retryable: boolean
  outboxId: number | null
}

const TABS: Array<{ key: NoticeView; label: string }> = [
  { key: 'compose', label: '场馆公告' },
  { key: 'delivery', label: '投递与触达' },
  { key: 'experiments', label: '公告实验' }
]

const HEADINGS: Record<NoticeView, { title: string; intro: string }> = {
  compose: {
    title: '让需要知道的人，读到清楚的消息。',
    intro: '写清变化、核对范围，再发布公告。发布完成后，继续以投递记录确认实际触达。'
  },
  delivery: {
    title: '消息发出之后，逐层核对结果。',
    intro: '把发布、排队、渠道接受、送达和阅读分开查看，沿失败记录继续处理。'
  },
  experiments: {
    title: '比较表达，保留结果的边界。',
    intro: '实验定义与统计渠道分开。对照同一范围内的阅读表现，不把观察到的差异写成因果结论。'
  }
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
const venueAccess = useVenueAccessStore()
const rootEl = ref<HTMLElement | null>(null)
const dialogEl = ref<HTMLDialogElement | null>(null)

const canManage = computed(() => auth.hasPermission('notice:manage'))
const canAnalytics = computed(() => auth.hasPermission('notice:analytics'))
// 真实合同里只有平台侧才能写其它四类人群（NoticeDeliveryService.java:319-333），界面按 system:view 开放（B3-T42）。
const canChooseScope = computed(() => auth.hasPermission('system:view') && canManage.value)

const view = ref<NoticeView>(initialViewFromRoute(route.name as string, typeof route.hash === 'string' ? route.hash : ''))
const surface = ref<SurfaceState>('loading')
const dialog = ref<DialogState | null>(null)
const publishing = ref(false)

// 阅读预览的初始文案与参考逐字一致：V01 的「收件人阅读预览」卡在空表单下仍以三段示例正文示人，
// 空串会把该卡压到 min-height:380px（实测 492→380、正文 205→29）。这里只做界面示例，
// 不预填场馆名、不预填批次或通知证据；提交时仍按用户实际输入校验（B3-T70）。
const READING_SAMPLE = {
  title: '本周日羽毛球场照明维护，部分时段暂停开放',
  content: '为改善晚间使用体验，场馆将于本周日 08:00—12:00 对羽毛球 1、2 号场进行照明维护。\n\n'
    + '其他场地正常开放。已有相关预约的用户，请查看预约记录中的处理信息；如需帮助，可通过场馆反馈入口联系我们。\n\n'
    + '感谢理解，期待更明亮的一场运动。'
}

const draft = reactive({
  title: READING_SAMPLE.title,
  content: READING_SAMPLE.content,
  targetScope: 'VENUE_RECENT_RESERVERS',
  memberLevelId: '',
  campaignId: '',
  userIds: ''
})
const draftError = ref('')
const audience = ref<AudiencePreviewResponse | null>(null)
const audienceStale = ref(false)
const previewDraft = ref<AudiencePreviewResponse | null>(null)
const published = ref<{ batchId: number | null; batchNo: string } | null>(null)

const providerOptions = ref<NotificationProviderOption[]>([])
const batches = ref<NoticeDeliveryBatchResponse[]>([])
const overview = ref<NoticeDeliveryOverviewResponse | null>(null)
const outboxes = ref<NoticeDeliveryOutboxResponse[]>([])
const outboxTotal = ref(0)
const outboxError = ref('')
const selectedKey = ref('')
const selectedBatch = ref<NoticeDeliveryBatchResponse | null>(null)
const outboxPage = reactive({ pageNum: 1, pageSize: 5 })
const audienceRows = ref<NoticeAudienceResponse[]>([])
const audienceTotal = ref(0)
const audiencePage = reactive({ pageNum: 1, pageSize: 5 })

const experiments = ref<NoticeExperimentResponse[]>([])
const selectedExperimentId = ref<number | null>(null)
const experimentResult = ref<NoticeExperimentResultResponse | null>(null)
const statsProviderKey = ref('')
const experimentForm = reactive({
  name: '',
  sampleScope: 'VENUE_RECENT_RESERVERS',
  titleA: '',
  contentA: '',
  titleB: '',
  contentB: '',
  allocationA: 50,
  allocationB: 50
})

const activeVenueId = computed(() => venueAccess.activeVenueId)
const venueName = computed(() => venueAccess.venues.find((item) => item.id === activeVenueId.value)?.name ?? '未选择场馆')
const scopeOptions = computed(() => Object.entries(TARGET_SCOPE_LABELS).map(([value, label]) => ({ value, label })))
const audienceScopeSentence = computed(() => (
  draft.targetScope === 'VENUE_RECENT_RESERVERS'
    ? `当前为${venueName.value}近期预约用户。内容或场馆变化后，需要重新预览受众。`
    : `当前接收人群为${targetScopeLabel(draft.targetScope)}。内容或范围变化后，需要重新预览受众。`
))
const excludeRows = computed(() => (audience.value ? excludeReasonRows(audience.value) : []))
const previewExcludeRows = computed(() => (previewDraft.value ? excludeReasonRows(previewDraft.value) : []))

const emailProvider = computed(() => providerOptions.value.find((item) => item.channel === 'EMAIL' && item.enabled) || null)
const inAppProviders = computed(() => providerOptions.value.filter((item) => item.channel === 'IN_APP' && item.enabled))
const batchEntries = computed<BatchMenuEntry[]>(() => batchMenuEntries(batches.value, emailProvider.value))
const selectedIsEmail = computed(() => selectedKey.value === 'email')
const selectedProvider = computed<NotificationProviderOption | null>(() => {
  if (selectedIsEmail.value) return emailProvider.value
  const batch = selectedBatch.value
  if (!batch) return inAppProviders.value[0] || null
  return providerOptions.value.find(
    (item) => item.providerEnv === batch.providerEnv && item.providerName === batch.providerName
  ) || null
})
const providerCaption = computed(() => {
  const provider = selectedProvider.value
  if (!provider) return '渠道未登记'
  if (selectedIsEmail.value) return `${providerLine(provider)} · 平台`
  const time = selectedBatch.value ? formatHm(selectedBatch.value.createTime) : ''
  return `${providerLine(provider)} · ${venueName.value}${time ? ` · ${time}` : ''}`
})
const scopeCaption = computed(() => {
  const batch = selectedBatch.value
  if (!batch) return '尚未选择批次'
  return `${targetScopeLabel(batch.targetScope)} · ${batch.audienceCount ?? 0} 人，${batch.excludedCount ?? 0} 人排除`
})
const evidenceCells = computed<EvidenceCell[]>(() => {
  if (selectedIsEmail.value) return emailEvidence(overview.value, outboxTotal.value)
  return selectedBatch.value ? inAppEvidence(selectedBatch.value) : []
})
const evidencePill = computed(() => {
  if (selectedIsEmail.value) {
    return selectedProvider.value?.providerEnv === 'SANDBOX' ? '沙箱证据' : '邮件渠道'
  }
  return batchStatusLabel(selectedBatch.value?.status)
})
const evidenceNote = computed(() => {
  if (selectedIsEmail.value) return 'SMTP 已接受只说明邮件服务器接受请求；下游送达与阅读暂无证据。未知指标保留为空缺。'
  const data = overview.value
  return data && data.channel === 'IN_APP' ? readRateSentence(data) : '阅读率由服务端按受众快照数计算，当前范围尚未取得概览。'
})
const outboxFootnote = computed(() => {
  if (selectedIsEmail.value) return '邮件仅显示脱敏地址和摘要；批次统计与业务邮件发件箱不混为一个发送量。'
  const batch = selectedBatch.value
  return `终态失败 ${batch?.failedCount ?? 0} 人；重试只新增投递记录，不覆写前次证据。`
})
const outboxPages = computed(() => Math.max(1, Math.ceil(outboxTotal.value / outboxPage.pageSize)))

const experimentOptions = computed(() => experiments.value.map(experimentOption))
const selectedExperiment = computed(() => experiments.value.find((item) => item.id === selectedExperimentId.value) || null)
const selectedVariants = computed(() => selectedExperiment.value?.variants || [])
const statsProvider = computed(() => providerOptions.value.find(
  (item) => providerKey(item) === statsProviderKey.value
) || null)
const statsIsEmail = computed(() => statsProvider.value?.channel === 'EMAIL')
const resultRows = computed(() => experimentResultRows(experimentResult.value, statsIsEmail.value))
const resultCaption = computed(() => (
  experimentResult.value
    ? `${experimentResult.value.providerEnv} / ${experimentResult.value.displayName || experimentResult.value.providerName} · `
    : ''
))
const experimentMeta = computed(() => {
  const experiment = selectedExperiment.value
  if (!experiment) return '尚未选择实验'
  return `${targetScopeLabel(experiment.sampleScope)} · 指标：站内消息阅读率`
})

function providerKey(provider: NotificationProviderOption): string {
  return `${provider.providerEnv}::${provider.providerName}`
}

function readableError(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

/** 面内所有请求都用隔离配置：单块读取失败只落该面状态，不整页跳走（B3-T56）。 */
function deliveryParams(over: Record<string, unknown> = {}): Record<string, unknown> {
  const provider = selectedProvider.value
  return {
    ...(provider ? { providerEnv: provider.providerEnv, providerName: provider.providerName } : {}),
    ...over
  }
}

async function loadDelivery(): Promise<void> {
  const providers = await noticeApi.deliveryProviders(isolated)
  providerOptions.value = providers || []
  const inApp = inAppProviders.value[0]
  if (!inApp) {
    batches.value = []
    selectedKey.value = emailProvider.value ? 'email' : ''
    selectedBatch.value = null
    overview.value = null
    outboxes.value = []
    outboxTotal.value = 0
    return
  }
  const page = await noticeApi.deliveryBatches(
    { providerEnv: inApp.providerEnv, providerName: inApp.providerName, pageNum: 1, pageSize: 10 },
    isolated
  )
  batches.value = page.records || []
  const first = batches.value[0] || null
  selectProviderScope(first ? `batch-${first.batchId}` : (emailProvider.value ? 'email' : ''), first)
  await loadDeliveryDetail()
}

function selectProviderScope(key: string, batch: NoticeDeliveryBatchResponse | null): void {
  selectedKey.value = key
  selectedBatch.value = batch
  outboxPage.pageNum = 1
}

async function loadDeliveryDetail(): Promise<void> {
  const provider = selectedProvider.value
  if (!provider) {
    overview.value = null
    outboxes.value = []
    outboxTotal.value = 0
    return
  }
  const requests: Promise<unknown>[] = [loadOverview(provider), loadOutboxes()]
  await Promise.all(requests)
}

async function loadOverview(provider: NotificationProviderOption): Promise<void> {
  try {
    overview.value = await noticeApi.deliveryOverview(
      { providerEnv: provider.providerEnv, providerName: provider.providerName },
      isolated
    )
  } catch (error) {
    overview.value = null
    outboxError.value = readableError(error, '触达概览暂时无法读取。')
  }
}

async function loadOutboxes(): Promise<void> {
  const provider = selectedProvider.value
  outboxError.value = ''
  if (!provider) {
    outboxes.value = []
    outboxTotal.value = 0
    return
  }
  try {
    const page = await noticeApi.deliveryOutboxes(deliveryParams({
      pageNum: outboxPage.pageNum,
      pageSize: outboxPage.pageSize,
      ...(selectedBatch.value ? { batchId: selectedBatch.value.batchId } : {})
    }), isolated)
    outboxes.value = page.records || []
    outboxTotal.value = page.total || 0
  } catch (error) {
    outboxes.value = []
    outboxTotal.value = 0
    outboxError.value = readableError(error, '发件箱记录暂时无法读取。')
  }
}

async function loadExperimentTab(): Promise<void> {
  const providers = providerOptions.value.length
    ? providerOptions.value
    : await noticeApi.deliveryProviders(isolated)
  providerOptions.value = providers || []
  const page = await noticeApi.experiments({ pageNum: 1, pageSize: 20 }, isolated)
  experiments.value = page.records || []
  if (!experiments.value.some((item) => item.id === selectedExperimentId.value)) {
    selectedExperimentId.value = experiments.value[0]?.id ?? null
  }
  if (!statsProviderKey.value) {
    const inApp = inAppProviders.value[0] || providerOptions.value[0]
    if (inApp) statsProviderKey.value = providerKey(inApp)
  }
  await loadExperimentResult()
}

async function loadExperimentResult(): Promise<void> {
  const provider = statsProvider.value
  if (!selectedExperimentId.value || !provider) {
    experimentResult.value = null
    return
  }
  try {
    experimentResult.value = await noticeApi.experimentResults(
      selectedExperimentId.value,
      { providerEnv: provider.providerEnv, providerName: provider.providerName },
      isolated
    )
  } catch (error) {
    experimentResult.value = null
    app.showToast(readableError(error, '实验统计暂时无法读取。'))
  }
}

async function bootstrap(): Promise<void> {
  surface.value = 'loading'
  if (view.value === 'compose' && !venueAccess.venues.length && canManage.value) {
    try {
      await venueAccess.refresh()
    } catch {
      // 场馆清单读不到时由 compose 的无场馆状态承接
    }
  }
  try {
    if (view.value === 'compose') {
      surface.value = 'ready'
      return
    }
    if (!canAnalytics.value) {
      surface.value = 'ready'
      return
    }
    if (view.value === 'delivery') await loadDelivery()
    else await loadExperimentTab()
    surface.value = 'ready'
  } catch (error) {
    surface.value = 'error'
    app.showToast(readableError(error, '公告与触达数据暂时无法读取。'))
  }
}

function applyView(next: NoticeView): void {
  view.value = next
  void router.replace({ hash: `#${next}` })
}

function switchView(next: NoticeView): void {
  if (view.value === next) return
  applyView(next)
  void bootstrap()
}

function selectEntry(entry: BatchMenuEntry, event?: Event): void {
  const trigger = event?.currentTarget as HTMLElement | undefined
  if (entry.channel === 'IN_APP') {
    const batch = batches.value.find((item) => item.batchId === entry.batchId) || null
    selectProviderScope(entry.key, batch)
  } else {
    selectProviderScope(entry.key, null)
  }
  void loadDeliveryDetail()
  trigger?.focus()
}

async function openBatchById(batchId: number): Promise<void> {
  const batch = await noticeApi.deliveryBatch(batchId, isolated)
  if (!batches.value.some((item) => item.batchId === batchId)) batches.value.unshift(batch)
  if (published.value?.batchId === batchId) published.value = { ...published.value, batchNo: batch.batchNo }
  selectProviderScope(`batch-${batchId}`, batch)
  await loadDeliveryDetail()
}

function gotoPublished(): void {
  applyView('delivery')
  void (async () => {
    await loadDelivery()
    const batchId = published.value?.batchId
    if (!batchId) return
    try {
      await openBatchById(batchId)
    } catch (error) {
      outboxError.value = readableError(error, '刚发布的批次暂时无法读取。')
    }
  })()
}

function moveOutboxPage(step: number): void {
  const next = outboxPage.pageNum + step
  if (next < 1 || next > outboxPages.value) return
  outboxPage.pageNum = next
  void loadOutboxes()
}

function moveAudiencePage(step: number): void {
  const next = audiencePage.pageNum + step
  if (next < 1) return
  audiencePage.pageNum = next
  void loadAudienceRows()
}

async function loadAudienceRows(): Promise<void> {
  const batch = selectedBatch.value
  if (!batch) return
  try {
    const page = await noticeApi.deliveryAudiences(batch.batchId, {
      providerEnv: batch.providerEnv,
      providerName: batch.providerName,
      pageNum: audiencePage.pageNum,
      pageSize: audiencePage.pageSize
    }, isolated)
    audienceRows.value = page.records || []
    audienceTotal.value = page.total || 0
  } catch (error) {
    audienceRows.value = []
    audienceTotal.value = 0
    if (dialog.value?.kind === 'audiences') dialog.value.error = readableError(error, '受众快照暂时无法读取。')
  }
}

let lastTrigger: HTMLElement | null = null
let lastTriggerTest = ''

function rememberTrigger(event?: Event | null, node?: HTMLElement | null): void {
  const target = node ?? ((event?.currentTarget ?? null) as HTMLElement | null)
  lastTrigger = target
  lastTriggerTest = target?.getAttribute('data-test') ?? ''
}

/**
 * 焦点恢复链（参考 ref/a/notices.js:23）：原触发元素 → 面内同 data-test 的重生节点 → 就近控件 → 当前页签。
 *
 * 原元素优先，但 `close` 事件先于 Vue 重渲染到达：发布成功后 `bootstrap()` 会把「确认发布」重渲染为
 * 禁用，此时旧引用、新节点都不可聚焦（focus() 对禁用按钮静默失败，实测焦点会掉到 body），
 * 因此第三跳在面内就近取第一个可聚焦控件，第四跳才是页签。前三跳与共享层同源（B3-T72）。
 */
function restoreTriggerFocus(): void {
  const trigger = lastTrigger
  const test = lastTriggerTest
  lastTrigger = null
  lastTriggerTest = ''
  const landed = restoreFocusChain({
    rootSelector: '.sga-notice',
    trigger,
    triggerTest: test || null,
    tabSelector: '.sga-notice .a-tab[aria-pressed="true"]'
  })
  if (landed) return
  // 触发元素在重渲染中被替换且自身已不可聚焦时（发布成功后的「确认发布」），就近把焦点交给
  // 当前页签正文里第一个可聚焦控件，避免直接掉到 body。
  const pane = document.querySelector<HTMLElement>('.sga-notice [data-test^="pane-"]')
  const fallback = pane?.querySelector<HTMLElement>('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled)')
  if (focusable(fallback)) fallback.focus()
}

function openDialog(next: DialogState, event?: Event | null, trigger?: HTMLElement | null): void {
  rememberTrigger(event, trigger)
  dialog.value = next
  void nextTick(() => {
    const node = dialogEl.value
    if (!node || node.open) return
    node.showModal()
    // 原生模态会把焦点交给第一个可聚焦元素（本面是「取消」）：那样关闭后触发元素已不在文档，
    // 参考 ref/a/notices.js:23 的恢复链第一跳永远落空。这里对齐参考的显式首焦点：
    // 弹层内第一个表单控件（受众核对、发布、实验定义），没有表单控件时才退到首个可聚焦元素。
    const form = node.querySelector<HTMLElement>('form')
    const target = form?.querySelector<HTMLElement>(
      'input:not([type="hidden"]):not(:disabled), select:not(:disabled), textarea:not(:disabled)'
    ) || form?.querySelector<HTMLElement>('button:not(:disabled), a[href]')
      || node.querySelector<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href]')
    // 原生模态的初始焦点校正发生在 showModal() 之后的一个任务里，同步 focus() 会被它覆盖
    // （实测同步调用后 activeElement 仍是「取消」）；延后一帧/一个宏任务再落焦点，并在弹层
    // 被提前关掉时取消（jsdom 里 rAF 由测试环境提供，不取消会抢走收尾后的焦点）。
    const schedule = typeof window.requestAnimationFrame === 'function'
      ? window.requestAnimationFrame.bind(window)
      : (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0)
    initialFocusHandle = schedule(() => {
      initialFocusHandle = null
      target?.focus()
    })
  })
}

/** 收尾中标记：finishDialog 自己关弹层时，原生的 close 事件不得抢先恢复焦点（见其注释）。 */
let finishing = false
/** 弹层首焦点的延后句柄：弹层在句柄触发前被关掉时必须取消，否则会给已收起的弹层抢焦点。 */
let initialFocusHandle: number | null = null

function cancelInitialFocus(): void {
  if (initialFocusHandle == null) return
  if (typeof window.cancelAnimationFrame === 'function') window.cancelAnimationFrame(initialFocusHandle)
  else window.clearTimeout(initialFocusHandle)
  initialFocusHandle = null
}

function closeDialog(): void {
  cancelInitialFocus()
  dialogEl.value?.close()
}

function onDialogClose(): void {
  cancelInitialFocus()
  // finishDialog 已自行收尾时（dialog 已清空），晚到的 close 事件不得再覆盖回焦点
  if (!dialog.value) return
  dialog.value = null
  // 提交路径上 close 事件同步到达，早于「清空弹层态 → 重渲染 → 恢复焦点」三步；
  // 这里若先恢复，会把禁用中的触发元素判成不可聚焦而整条链落到页签（实测）。
  if (finishing) return
  restoreTriggerFocus()
}

function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Tab') return
  const node = dialogEl.value
  if (!node) return
  const focusable = [...node.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href]'
  )]
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

async function finishDialog(toast?: string): Promise<void> {
  finishing = true
  closeDialog()
  dialog.value = null
  try {
    await bootstrap()
    await nextTick()
    restoreTriggerFocus()
  } finally {
    finishing = false
  }
  if (toast) app.showToast(toast)
}

function scopeIds(): Record<string, unknown> {
  const payload: Record<string, unknown> = {}
  if (activeVenueId.value) payload.targetVenueId = activeVenueId.value
  if (draft.targetScope === 'MEMBER_LEVEL') payload.memberLevelId = Number(draft.memberLevelId)
  if (draft.targetScope === 'CAMPAIGN_PARTICIPANTS') payload.campaignId = Number(draft.campaignId)
  if (draft.targetScope === 'SPECIFIC_USERS') {
    payload.userIds = draft.userIds.split(',').map((item) => Number(item.trim())).filter((item) => Number.isInteger(item))
  }
  return payload
}

function audiencePayload(): Record<string, unknown> {
  return { targetScope: draft.targetScope, ...scopeIds() }
}

async function openPreview(event?: Event): Promise<void> {
  // 受众核对要先 await 服务端预览再开弹层：事件派发结束后 event.currentTarget 已被浏览器清空，
  // 这里同步取一次触发元素，否则关闭弹层时恢复链的第一跳永远拿不到按钮（实测落到页签）。
  const trigger = (event?.currentTarget ?? null) as HTMLElement | null
  draftError.value = ''
  if (!draft.title.trim() || !draft.content.trim()) {
    draftError.value = '请填写公告标题和正文后再核对受众。'
    document.querySelector<HTMLElement>(draft.title.trim() ? '[data-test="notice-body"]' : '[data-test="notice-title"]')?.focus()
    return
  }
  try {
    const preview = await noticeApi.previewAudience(audiencePayload(), isolated)
    previewDraft.value = preview
    openDialog({
      kind: 'preview',
      title: '核对受众范围',
      submit: '采用本次受众预览',
      error: '',
      busy: false,
      bodyText: `${venueName.value} · ${targetScopeLabel(draft.targetScope)}`,
      detail: '',
      intro: '',
      facts: [],
      isEmail: false,
      retryable: false,
      outboxId: null
    }, null, trigger)
  } catch (error) {
    draftError.value = readableError(error, '受众核对未完成，草稿已保留。')
  }
}

function openPublish(event?: Event): void {
  if (!audience.value || audienceStale.value) return
  openDialog({
    kind: 'publish',
    title: '确认发布场馆公告',
    submit: '发布并创建投递批次',
    error: '',
    busy: false,
    bodyText: `发布到${venueName.value}的${targetScopeLabel(draft.targetScope)}。发布后创建投递批次，具体触达继续查看批次证据。`,
    detail: '',
    intro: '',
    facts: [],
    isEmail: false,
    retryable: false,
    outboxId: null
  }, event)
}

function openAudiences(event?: Event): void {
  const batch = selectedBatch.value
  if (!batch) return
  audiencePage.pageNum = 1
  audienceRows.value = []
  audienceTotal.value = 0
  openDialog({
    kind: 'audiences',
    title: `受众快照 · ${batch.batchNo}`,
    submit: '',
    error: '',
    busy: false,
    bodyText: `${batch.providerEnv} / ${batch.providerName} · ${venueName.value} · ${batch.audienceCount ?? 0} 人`,
    detail: '',
    intro: '',
    facts: [],
    isEmail: false,
    retryable: false,
    outboxId: null
  }, event)
  void loadAudienceRows()
}

function openOutbox(row: NoticeDeliveryOutboxResponse, event?: Event): void {
  openDialog({
    kind: 'outbox',
    title: '投递记录',
    submit: '',
    error: '',
    busy: false,
    bodyText: `${row.outboxNo} · ${outboxObjectText(row)}`,
    detail: `${row.providerEnv} / ${row.providerName} · ${row.channel} · ${row.templateKey || '模板未记录'}`,
    intro: '',
    facts: outboxFactRows(row),
    isEmail: row.channel === 'EMAIL',
    retryable: canRetryOutbox(row),
    outboxId: row.outboxId
  }, event)
}

function openRetry(event?: Event): void {
  const state = dialog.value
  if (!state) return
  const row = outboxes.value.find((item) => item.outboxId === state.outboxId)
  if (!row) return
  openDialog({
    kind: 'retry',
    title: '确认重新排队',
    submit: '提交重试',
    error: '',
    busy: false,
    bodyText: `${row.outboxNo} / ${outboxObjectText(row)}`,
    detail: `${row.providerEnv} / ${row.providerName}`,
    intro: `保留先前的 ${row.attemptCount ?? 0} 次尝试记录。本次只提交重试，后续投递结果仍需核对。`,
    facts: [],
    isEmail: row.channel === 'EMAIL',
    retryable: false,
    outboxId: row.outboxId
    // 链式弹层：重试弹层不覆盖触发元素，关闭后要能回到「查看详情」那一行（参考 ref/a/notices.js:23 的第二跳）
  })
}

function openExperiment(event?: Event): void {
  openDialog({
    kind: 'experiment',
    title: '新建公告实验定义',
    submit: '保存实验定义',
    error: '',
    busy: false,
    bodyText: '',
    detail: '',
    intro: '',
    facts: [],
    isEmail: false,
    retryable: false,
    outboxId: null
  }, event)
}

async function confirmDialog(): Promise<void> {
  const state = dialog.value
  if (!state || state.busy) return
  if (state.kind === 'preview') {
    if (!previewDraft.value) {
      state.error = '受众预览结果已失效，请重新核对。'
      return
    }
    audience.value = previewDraft.value
    audienceStale.value = false
    await finishDialog('受众范围已核对。')
    return
  }
  if (state.kind === 'publish') {
    if (!audience.value || audienceStale.value) {
      state.error = '请先核对受众范围。'
      return
    }
    state.busy = true
    publishing.value = true
    try {
      const payload: Partial<Notice> = {
        title: draft.title,
        content: draft.content,
        type: activeVenueId.value ? 'VENUE' : 'GLOBAL',
        status: 'PUBLISHED',
        targetScope: draft.targetScope,
        ...scopeIds()
      }
      const notice = await noticeApi.publish(payload, isolated)
      published.value = { batchId: notice.deliveryBatchId ?? null, batchNo: '' }
      audience.value = null
      audienceStale.value = false
      state.busy = false
      publishing.value = false
      await finishDialog('公告已发布，投递批次已创建。')
    } catch (error) {
      state.busy = false
      publishing.value = false
      // 服务端原文留在弹层：真实发布失败原因只有服务端知道
      state.error = readableError(error, '公告发布未完成，草稿内容已保留。')
    }
    return
  }
  if (state.kind === 'retry') {
    if (!canManage.value || state.outboxId == null) {
      state.error = '缺少公告管理权限，不能重试。'
      return
    }
    state.busy = true
    try {
      await noticeApi.retryOutbox(state.outboxId, isolated)
      state.busy = false
      await finishDialog('重试已提交，投递结果仍为等待处理。')
    } catch (error) {
      state.busy = false
      state.error = readableError(error, '重试未完成。')
    }
    return
  }
  if (state.kind === 'experiment') {
    const missing = [
      !experimentForm.name.trim() && '实验名称',
      !experimentForm.titleA.trim() && '版本 A 标题',
      !experimentForm.contentA.trim() && '版本 A 正文',
      !experimentForm.titleB.trim() && '版本 B 标题',
      !experimentForm.contentB.trim() && '版本 B 正文'
    ].filter(Boolean)
    if (missing.length) {
      state.error = `请填写${missing.join('、')}。`
      return
    }
    const allocationError = experimentAllocationError(experimentForm.allocationA, experimentForm.allocationB)
    if (allocationError) {
      state.error = allocationError
      return
    }
    state.busy = true
    try {
      const created = await noticeApi.createExperiment(
        experimentPayload({ ...experimentForm }),
        isolated
      )
      selectedExperimentId.value = created.id ?? null
      state.busy = false
      await finishDialog('实验定义已保存，未触发投递。')
    } catch (error) {
      state.busy = false
      state.error = readableError(error, '实验定义保存未完成。')
    }
  }
}

// 内容、人群或场馆变化后预览即失效（真实预览接口无状态，失效只在界面层保证，见 contracts §4）。
watch(
  () => [draft.title, draft.content, draft.targetScope, draft.memberLevelId, draft.campaignId, draft.userIds, activeVenueId.value],
  () => {
    if (audience.value && !audienceStale.value) audienceStale.value = true
  }
)

watch(() => route.name, () => {
  view.value = initialViewFromRoute(route.name as string, typeof route.hash === 'string' ? route.hash : '')
  selectedExperimentId.value = null
  void bootstrap()
})

onMounted(async () => {
  await bootstrap()
  const fromQuery = batchIdFromQuery(route.query.batch)
  if (fromQuery && view.value === 'delivery') {
    try {
      await openBatchById(fromQuery)
    } catch {
      // 深链批次不可读时保留当前选择，不整页报错
    }
  }
})
</script>
