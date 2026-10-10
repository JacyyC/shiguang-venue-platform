<template>
  <div ref="rootEl" class="sga-ops" data-test="sga-promotion-root">
    <div class="b-heading">
      <div>
        <div class="b-kicker">平台运营 · 营销规则</div>
        <h1>促销、优惠券与定价管理</h1>
        <p>把适用范围、优惠条件和生效时间放在一起核对，让每一条规则都能被清楚理解。</p>
      </div>
    </div>

    <div class="b-tabs" role="group" aria-label="工作对象">
      <button
        v-for="item in TABS"
        :id="`tab-${item.key}`"
        :key="item.key"
        :data-test="`tab-${item.key}`"
        type="button"
        :aria-pressed="view === item.key"
        @click="go(item.key)"
      >
        {{ item.label }}
      </button>
    </div>

    <section v-if="surface === 'loading'" class="b-state" aria-busy="true" data-test="promotion-loading">
      <h2>正在读取当前范围</h2>
      <p>活动、券模板与定价规则将在加载后完整显示。</p>
      <button class="btn primary" type="button" data-test="promotion-reload" @click="bootstrap">重新读取</button>
    </section>

    <section v-else-if="surface === 'error'" class="b-state" role="alert" data-test="promotion-error">
      <h2>暂时无法读取营销数据</h2>
      <p>{{ loadError || '尚未取得本次结果，请重试。' }}</p>
      <button class="btn primary" type="button" data-test="promotion-reload" @click="bootstrap">重试读取</button>
    </section>

    <template v-else>
      <div v-if="!canViewCurrent" class="b-state" role="status" :data-test="`denied-${view}`">
        <h2>{{ DENIED[view].title }}</h2>
        <p>{{ DENIED[view].text }}</p>
      </div>

      <!-- 促销活动 -->
      <template v-else-if="view === 'campaigns'">
        <div class="b-section-title">
          <div>
            <h2>按生效时间核对促销</h2>
            <p>{{ campaigns.length }} 条 · 先看范围，再检查优惠与叠加条件。</p>
          </div>
          <button class="btn primary" type="button" data-test="campaign-create" @click="openForm('campaigns', -1, $event)">新增促销</button>
        </div>
        <p v-if="!campaigns.length" class="b-readonly" data-test="campaign-empty">当前没有已登记的促销活动。</p>
        <article v-for="(row, index) in campaigns" v-else :key="row.id" class="b-promo-row">
          <div class="b-promo-date">
            <strong>{{ monthDayBlock(row.startTime) }}</strong>
            <span>至 {{ isoDate(row.endTime).slice(5) }}</span>
          </div>
          <div class="b-promo-main">
            <h3>{{ row.campaignName }}</h3>
            <div class="b-inline">
              <span class="b-status" :class="{ attention: statusAttention(objectStatusLabel(row.status)) }">
                {{ objectStatusLabel(row.status) }}
              </span>
              <span class="b-sub">{{ scopeText(row.venueId, row.spaceId) }}</span>
            </div>
            <p>{{ memberScopeText(row.targetMemberLevel) }} · {{ row.allowCouponStack ? '允许叠加优惠券' : '不叠加优惠券' }}</p>
            <span class="b-sub">预算 ¥ {{ amount(row.budgetAmount) }} · 优先级 <span data-test="campaign-priority">{{ row.priority ?? 0 }}</span> · {{ row.campaignCode }}</span>
          </div>
          <div class="b-promo-tail">
            <div class="b-promo-value">{{ campaignValueText(row.discountType, row.discountValue) }}</div>
            <div class="b-actions">
              <button class="btn" type="button" :data-test="`campaign-edit-${index}`" @click="openForm('campaigns', index, $event)">查看规则</button>
            </div>
          </div>
        </article>
        <details
          data-test="campaign-overview"
          style="margin-top: 24px; border-top: 1px solid var(--line); padding-top: 12px"
        >
          <summary style="min-height: 44px; cursor: pointer">营销概览</summary>
          <div v-if="overview" class="b-overview-note">
            <span v-for="row in overviewCounts" :key="row.label"><strong>{{ row.value }}</strong>{{ row.label }}</span>
          </div>
          <p v-if="overview" class="muted">{{ overviewText }}</p>
        </details>
        <p class="b-page-bottom">启用状态与活动日期分别表达；这里不将预算当作实际支出，也不推算交易应付金额。重叠活动由服务端按优先级与创建时间选取，本面不叠加计算。</p>
      </template>

      <!-- 优惠券模板 -->
      <template v-else-if="view === 'coupons'">
        <div class="b-section-title">
          <div>
            <h2>可被发放与兑换的券模板</h2>
            <p>模板规定额度、数量、有效期和适用范围。</p>
          </div>
          <button class="btn primary" type="button" data-test="coupon-create" @click="openForm('coupons', -1, $event)">新增模板</button>
        </div>
        <p v-if="!coupons.length" class="b-readonly" data-test="coupon-empty">当前没有已登记的优惠券模板。</p>
        <article v-for="(row, index) in coupons" v-else :key="row.id" class="b-list-item">
          <div>
            <div class="b-inline">
              <span class="b-kicker">{{ row.templateCode }}</span>
              <span class="b-status" :class="{ attention: statusAttention(objectStatusLabel(row.status)) }">
                {{ objectStatusLabel(row.status) }}
              </span>
            </div>
            <h3>{{ row.templateName }}</h3>
            <p>{{ couponValueText(row.couponType, row.discountValue, row.thresholdAmount) }} · {{ scopeText(row.venueId, row.spaceId) }}</p>
            <dl class="b-metadata">
              <div><dt>已发 / 总量</dt><dd>{{ issueText(row.issuedQuantity, row.totalQuantity) }} 张</dd></div>
              <div><dt>已使用</dt><dd>{{ row.usedQuantity ?? 0 }} 张</dd></div>
              <div><dt>每人上限</dt><dd>{{ row.perUserLimit ?? 1 }} 张</dd></div>
            </dl>
            <span class="b-sub">
              {{ isoDate(row.validStartTime) }} 至 {{ isoDate(row.validEndTime) }} · 成本承担：{{ row.costOwner || '未填写' }}
            </span>
            <span class="b-sub">关联促销：{{ campaignName(row.campaignId) }}</span>
          </div>
          <div class="b-actions">
            <button class="btn" type="button" :data-test="`coupon-edit-${index}`" @click="openForm('coupons', index, $event)">查看模板</button>
            <button class="b-link" type="button" :data-test="`coupon-redemptions-${index}`" @click="go('redemptions')">核销记录</button>
          </div>
        </article>
      </template>

      <!-- 定价规则 -->
      <template v-else-if="view === 'rules'">
        <div class="b-section-title">
          <div>
            <h2>按优先级检查定价条件</h2>
            <p>记录时间、场地与调整方式。成交金额继续以交易价格结果为准。</p>
          </div>
          <button class="btn primary" type="button" data-test="rule-create" @click="openForm('rules', -1, $event)">新增规则</button>
        </div>
        <p v-if="!rules.length" class="b-readonly" data-test="rule-empty">当前没有已登记的定价规则。</p>
        <div v-else class="b-rule-list">
          <article v-for="(row, index) in rules" :key="row.id" class="b-rule">
            <div class="b-rule-priority">{{ row.priority ?? 0 }}</div>
            <div>
              <h3>{{ row.ruleName }}</h3>
              <p>{{ scopeText(row.venueId, row.spaceId) }} · {{ memberScopeText(row.memberLevelCode) }}</p>
              <p>{{ weekdayText(row.dayOfWeekMask) }} {{ clockRangeText(row.startClock, row.endClock) }} · {{ adjustmentTypeLabel(row.adjustmentType) }} {{ adjustmentValueText(row.adjustmentType, row.adjustmentValue) }}</p>
              <div class="b-inline">
                <span class="b-status" :class="{ attention: statusAttention(objectStatusLabel(row.status)) }">
                  {{ objectStatusLabel(row.status) }}
                </span>
                <span class="b-sub">{{ isoDate(row.startTime) }} 至 {{ isoDate(row.endTime) }}</span>
              </div>
              <span class="b-sub">{{ row.ruleCode }} · {{ ruleTypeLabel(row.ruleType) }}{{ row.reasonText ? ` · ${row.reasonText}` : '' }}</span>
            </div>
            <div class="b-actions">
              <button class="btn" type="button" :data-test="`rule-edit-${index}`" @click="openForm('rules', index, $event)">查看条件</button>
            </div>
          </article>
        </div>
        <p class="b-page-bottom">
          优先级数值越大越先参与匹配；库存、折扣叠加顺序与最终应付由真实交易合同决定，本面只维护条件本身。
        </p>
      </template>

      <!-- 核销记录 -->
      <template v-else>
        <div class="b-section-title">
          <div>
            <h2>优惠券状态与核销记录</h2>
            <p>已锁定与已使用分别表达，不在此手动核销或退款。</p>
          </div>
        </div>
        <div class="b-field" style="max-width: 230px; margin-bottom: 22px">
          <label>券状态</label>
          <select v-model="redemptionStatus" data-test="redemption-status" @change="reloadRedemptions">
                      <option v-for="option in REDEMPTION_STATUS_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                    </select>
        </div>
        <p v-if="!redemptions.length" class="b-readonly" data-test="redemption-empty">当前条件下没有核销记录。</p>
        <div v-else class="b-table-wrap" tabindex="0" aria-label="优惠券核销记录，可横向滚动">
          <table class="b-ledger" data-test="redemption-table">
            <thead>
              <tr><th>优惠券 / 编号</th><th>状态</th><th>优惠</th><th>订单 / 时间</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in redemptions" :key="row.id">
                <td>
                  <strong>{{ row.templateName || '—' }}</strong>
                  <span class="b-sub">{{ row.templateCode || `券 ${row.id}` }}</span>
                </td>
                <td>
                  <span class="b-status" :class="{ attention: statusAttention(userCouponStatusLabel(row.status)) }">
                    {{ userCouponStatusLabel(row.status) }}
                  </span>
                </td>
                <td>¥ {{ amount(row.discountAmount ?? row.discountValue) }}</td>
                <td>
                  {{ redemptionOrderText(row.orderId, row.claimTime).order }}
                  <span class="b-sub">{{ redemptionOrderText(row.orderId, row.claimTime).time }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="b-sub" data-test="redemption-paging">
          共 {{ redemptionTotal }} 条，按 {{ redemptionPage.pageSize }} 条分页；当前第 {{ redemptionPage.pageNum }} 页。
        </p>
        <div class="b-actions">
          <button class="btn" type="button" data-test="redemption-prev" :disabled="redemptionPage.pageNum <= 1" @click="moveRedemptionPage(-1)">上一页</button>
          <button class="btn" type="button" data-test="redemption-next" :disabled="redemptionPage.pageNum * redemptionPage.pageSize >= redemptionTotal" @click="moveRedemptionPage(1)">下一页</button>
        </div>
        <!-- B4-P8 / B4-P14：服务端白名单 7 值中只有 5 值有真实写入方；已退回是日志条数口径。 -->
        <p class="b-page-bottom">
          该列表只读：本面不提供手动核销、退券或调账。服务端的「已释放」「已过期」两种券状态当前没有任何写入路径，因此不列入筛选，避免出现恒空的假空态。
        </p>
      </template>
    </template>

    <dialog ref="dialogEl" class="b-dialog" aria-labelledby="b-dialog-title" @close="onDialogClose" @keydown="onDialogKeydown">
      <form v-if="dialog" data-test="promotion-dialog-form" @submit.prevent="confirmDialog">
        <div class="b-dialog-head">
          <h2 id="b-dialog-title" data-test="promotion-dialog-title" tabindex="-1">{{ dialog.title }}</h2>
          <button class="b-close" type="button" aria-label="关闭详情" data-test="promotion-dialog-close" @click="closeDialog">×</button>
        </div>
        <div class="b-dialog-body">
          <template>
            <div class="b-edit-layout">
              <fieldset style="border: 0; padding: 0; margin: 0; min-width: 0">
                <div class="b-form-grid">
                  <div class="b-field">
                    <label>{{ dialog.noun }}名称</label>
                    <input v-model="form.name" data-test="p-name" required maxlength="60" autocomplete="off">
                  </div>
                  <div class="b-field">
                    <label>业务编码</label>
                    <input v-model="form.code" data-test="p-code" required autocomplete="off">
                  </div>
                  <div class="b-field">
                    <label>适用场馆</label>
                    <select v-model.number="form.venueId" data-test="p-venue">
                                          <option :value="0">全部场馆</option>
                                          <option v-for="venue in venues" :key="venue.id" :value="venue.id">{{ venue.name }}</option>
                                        </select>
                  </div>
                  <div class="b-field">
                    <label>适用场地</label>
                    <select v-model.number="form.spaceId" data-test="p-space">
                                          <option :value="0">全部场地</option>
                                          <option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}</option>
                                        </select>
                  </div>
                  <div class="b-field">
                    <label>生效开始</label>
                    <input v-model="form.start" data-test="p-start" type="datetime-local" required>
                  </div>
                  <div class="b-field">
                    <label>生效结束</label>
                    <input v-model="form.end" data-test="p-end" type="datetime-local" required>
                  </div>

                  <!-- B4-P13：会员范围是**精确等级**（member_level_code = ? or is null） -->
                  <template v-if="dialog.kind !== 'coupons'">
                    <div class="b-field">
                      <label>会员范围（精确等级）</label>
                      <select v-model="form.level" data-test="p-level">
                                              <option value="">不限等级</option>
                                              <option v-for="level in levels" :key="level.id" :value="level.levelCode">{{ level.levelName }}</option>
                                            </select>
                    </div>
                  </template>

                  <template v-if="dialog.kind === 'campaigns'">
                    <div class="b-field">
                      <label>促销类型</label>
                      <select v-model="form.campaignType" data-test="p-scenario">
                                              <option v-for="option in CAMPAIGN_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                                            </select>
                    </div>
                    <div class="b-field">
                      <label>优惠方式</label>
                      <select v-model="form.discountType" data-test="p-type">
                                              <option v-for="option in DISCOUNT_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                                            </select>
                    </div>
                    <div class="b-field">
                      <label>优惠值（折扣率 0–1）</label>
                      <input v-model.number="form.value" data-test="p-value" type="number" required min="0.01" step="0.01">
                    </div>
                    <div class="b-field">
                      <label>单笔优惠上限（元）</label>
                      <input v-model.number="form.maxDiscount" data-test="p-max" type="number" min="0" step="0.01">
                    </div>
                    <div class="b-field">
                      <label>预算金额（元）</label>
                      <input v-model.number="form.budget" data-test="p-budget" type="number" min="0" step="0.01">
                    </div>
                    <div class="b-field">
                      <label>优先级（重叠活动时数值大的生效）</label>
                      <input v-model.number="form.priority" data-test="p-campaign-priority" type="number" required min="0" step="1">
                    </div>
                    <div class="b-field">
                      <label>优惠券叠加</label>
                      <select v-model="form.stack" data-test="p-stack">
                                              <option :value="false">不允许叠加</option>
                                              <option :value="true">允许叠加</option>
                                            </select>
                    </div>
                  </template>

                  <template v-if="dialog.kind === 'coupons'">
                    <div class="b-field">
                      <label>优惠方式</label>
                      <select v-model="form.discountType" data-test="p-type">
                                              <option v-for="option in DISCOUNT_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                                            </select>
                    </div>
                    <div class="b-field">
                      <label>优惠值（折扣率 0–1）</label>
                      <input v-model.number="form.value" data-test="p-value" type="number" required min="0.01" step="0.01">
                    </div>
                    <div class="b-field">
                      <label>使用门槛（元）</label>
                      <input v-model.number="form.threshold" data-test="p-threshold" type="number" min="0" step="0.01">
                    </div>
                    <div class="b-field">
                      <label>单笔优惠上限（元）</label>
                      <input v-model.number="form.maxDiscount" data-test="p-max" type="number" min="0" step="0.01">
                    </div>
                    <div class="b-field">
                      <label>总发行量（张，0 表示不限量）</label>
                      <input v-model.number="form.total" data-test="p-total" type="number" :min="form.issued" step="1">
                    </div>
                    <div class="b-field">
                      <label>每人上限（张）</label>
                      <input v-model.number="form.limit" data-test="p-limit" type="number" min="1" step="1">
                    </div>
                    <div class="b-field">
                      <label>关联促销</label>
                      <select v-model.number="form.campaignId" data-test="p-campaign">
                                              <option :value="0">不关联促销</option>
                                              <option v-for="row in campaigns" :key="row.id" :value="row.id">{{ row.campaignName }}</option>
                                            </select>
                    </div>
                    <div class="b-field">
                      <label>成本承担</label>
                      <input v-model="form.costOwner" data-test="p-cost" maxlength="64" placeholder="例如 PLATFORM / VENUE" autocomplete="off">
                    </div>
                    <div class="b-field">
                      <label>活动优惠叠加</label>
                      <select v-model="form.stack" data-test="p-stack">
                                              <option :value="false">不允许叠加</option>
                                              <option :value="true">允许叠加</option>
                                            </select>
                    </div>
                  </template>

                  <template v-if="dialog.kind === 'rules'">
                    <div class="b-field">
                      <label>适用场景</label>
                      <input v-model="form.ruleType" data-test="p-scenario" list="rule-type-options" autocomplete="off">
                                            <datalist id="rule-type-options">
                                              <option v-for="option in RULE_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                                            </datalist>
                    </div>
                    <div class="b-field">
                      <label>调整方式</label>
                      <select v-model="form.adjustmentType" data-test="p-type">
                                              <option v-for="option in ADJUSTMENT_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                                            </select>
                    </div>
                    <div class="b-field b-span">
                      <span>生效星期</span>
                      <div class="b-inline">
                        <label v-for="day in WEEKDAY_OPTIONS" :key="day.value" class="row" style="gap: 6px">
                          <input
                            type="checkbox"
                            :value="day.value"
                            :checked="form.days.includes(day.value)"
                            :data-test="`p-day-${day.value}`"
                            @change="toggleDay(day.value, ($event.target as HTMLInputElement).checked)"
                          >
                          {{ day.label }}
                        </label>
                      </div>
                    </div>
                    <div class="b-field">
                      <label>每日开始时刻</label>
                      <input v-model="form.from" data-test="p-from" type="time" required>
                    </div>
                    <div class="b-field">
                      <label>每日结束时刻</label>
                      <input v-model="form.to" data-test="p-to" type="time" required>
                    </div>
                    <div class="b-field">
                      <label>调整值</label>
                      <input v-model.number="form.value" data-test="p-value" type="number" required step="0.01">
                    </div>
                    <div class="b-field">
                      <label>优先级</label>
                      <input v-model.number="form.priority" data-test="p-priority" type="number" required min="0" step="1">
                    </div>
                    <label class="b-field b-span">规则说明
                      <input v-model="form.reason" data-test="p-reason" required maxlength="255" autocomplete="off">
                    </label>
                  </template>

                  <div class="b-field">
                    <label>对象状态</label>
                    <select v-model="form.status" data-test="p-status">
                                          <option v-for="option in STATUS_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                                        </select>
                  </div>
                </div>
              </fieldset>
              <aside class="b-summary">
                <h3>规则核对</h3>
                <div data-test="p-live-summary">
                  <p><strong>{{ form.name || '尚未命名' }}</strong></p>
                  <p>
                    {{ venueLabel(form.venueId) }}<br>
                    {{ spaceLabel(form.spaceId) }}<br>
                    {{ (form.start || '').replace('T', ' ') }}<br>
                    至 {{ (form.end || '').replace('T', ' ') }}
                  </p>
                  <p>
                    {{ dialog.kind === 'rules' ? '规则调整值' : '优惠值' }}：{{ form.value }}<br>
                    保存后状态：{{ objectStatusLabel(form.status) }}
                  </p>
                </div>
                <hr class="b-divider">
                <p>{{ dialog.summaryNote }}</p>
              </aside>
            </div>
            <p class="b-sub">{{ dialog.footNote }}</p>
          </template>
        </div>
        <div class="b-dialog-foot">
          <button class="btn" type="button" data-test="promotion-dialog-back" @click="closeDialog">{{ dialog.cancelText }}</button>
          <button
            v-if="dialog.submit"
            class="btn primary"
            type="button"
            data-test="promotion-dialog-submit"
            :disabled="dialog.busy"
            @click="confirmDialog"
          >
            {{ dialog.busy ? '处理中…' : dialog.submit }}
          </button>
        </div>
        <p v-if="dialog.error" class="b-error" role="alert" data-test="promotion-dialog-error">{{ dialog.error }}</p>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { promotionAdminApi } from '@/api/promotion'
import { adminMemberApi } from '@/api/member'
import { venueAdminApi, venueApi } from '@/api/venue'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { ISOLATED_REQUEST as isolated, consumeTabFocus, rememberTabFocus, resolveInitialTab, restoreFocusChain } from '../adminShared'
import type {
  CouponTemplateResponse,
  MemberLevelResponse,
  PriceRuleResponse,
  PromotionCampaignResponse,
  PromotionOverviewResponse,
  UserCouponResponse,
  Venue,
  VenueSpace
} from '@/api/types'
import {
  ADJUSTMENT_TYPE_LABELS,
  CAMPAIGN_TYPE_LABELS,
  DISCOUNT_TYPE_LABELS,
  REDEMPTION_STATUS_OPTIONS,
  RULE_TYPE_LABELS,
  WEEKDAY_OPTIONS,
  adjustmentTypeLabel,
  adjustmentValueText,
  amount,
  campaignValueText,
  couponValueText,
  daysToWeekdayMask,
  isoDate,
  issueText,
  monthDayBlock,
  objectStatusLabel,
  objectToggleTarget,
  overviewNote,
  redemptionOrderText,
  ruleTypeLabel,
  userCouponStatusLabel,
  weekdayText,
  clockRangeText
} from './promotionAdminShared'

type PromotionView = 'campaigns' | 'coupons' | 'rules' | 'redemptions'
type ObjectKind = 'campaigns' | 'coupons' | 'rules'
type SurfaceState = 'loading' | 'ready' | 'error'
type DialogKind = ObjectKind

interface DialogState {
  kind: DialogKind
  title: string
  submit: string
  cancelText: string
  bodyText: string
  detail: string
  noun: string
  summaryNote: string
  footNote: string
  error: string
  busy: boolean
  index: number
}

const TABS: Array<{ key: PromotionView; label: string }> = [
  { key: 'campaigns', label: '促销活动' },
  { key: 'coupons', label: '优惠券模板' },
  { key: 'rules', label: '定价规则' },
  { key: 'redemptions', label: '核销记录' }
]

/** 每个页签的真实叶子权限点（`be/promotion/controller/PromotionController.java:35,64,100`）。 */
const TAB_PERMISSIONS: Record<PromotionView, string> = {
  campaigns: 'promotion:manage',
  coupons: 'coupon:manage',
  rules: 'pricing:manage',
  redemptions: 'coupon:manage'
}

const DENIED: Record<PromotionView, { title: string; text: string }> = {
  campaigns: { title: '没有促销活动管理权限', text: '活动登记、编辑与启停需要 promotion:manage。' },
  coupons: { title: '没有优惠券模板管理权限', text: '券模板与核销记录需要 coupon:manage。' },
  rules: { title: '没有定价规则管理权限', text: '定价规则登记、编辑与启停需要 pricing:manage。' },
  redemptions: { title: '没有优惠券管理权限', text: '核销记录需要 coupon:manage。' }
}

const ROUTE_VIEWS: Record<string, PromotionView> = {
  SystemPromotions: 'campaigns'
}

const STATUS_OPTIONS = Object.entries(
  // 选项顺序沿用字典 sort_order（草稿/待开始/进行中/已暂停/已结束）。
  { DRAFT: '草稿', SCHEDULED: '待开始', ACTIVE: '进行中', PAUSED: '已暂停', ENDED: '已结束' }
).map(([value, label]) => ({ value, label }))
const DISCOUNT_TYPE_OPTIONS = Object.entries(DISCOUNT_TYPE_LABELS).map(([value, label]) => ({ value, label }))
const CAMPAIGN_TYPE_OPTIONS = Object.entries(CAMPAIGN_TYPE_LABELS).map(([value, label]) => ({ value, label }))
const ADJUSTMENT_TYPE_OPTIONS = Object.entries(ADJUSTMENT_TYPE_LABELS).map(([value, label]) => ({ value, label }))
const RULE_TYPE_OPTIONS = Object.entries(RULE_TYPE_LABELS).map(([value, label]) => ({ value, label }))

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
const rootEl = ref<HTMLElement | null>(null)
const dialogEl = ref<HTMLDialogElement | null>(null)

const surface = ref<SurfaceState>('loading')
const loadError = ref('')
const view = ref<PromotionView>(initialView())
const dialog = ref<DialogState | null>(null)
const lastTrigger = ref<HTMLElement | null>(null)
const lastTriggerTest = ref<string | null>(null)
const restoring = ref(false)

const campaigns = ref<PromotionCampaignResponse[]>([])
const coupons = ref<CouponTemplateResponse[]>([])
const rules = ref<PriceRuleResponse[]>([])
const redemptions = ref<UserCouponResponse[]>([])
const redemptionTotal = ref(0)
const redemptionPage = reactive({ pageNum: 1, pageSize: 20 })
const redemptionStatus = ref('')
const overview = ref<PromotionOverviewResponse | null>(null)
const levels = ref<MemberLevelResponse[]>([])
const venues = ref<Venue[]>([])
const spaces = ref<VenueSpace[]>([])

const form = reactive({
  id: null as number | null,
  name: '',
  code: '',
  venueId: 0,
  spaceId: 0,
  start: '',
  end: '',
  level: '',
  campaignType: 'SEASONAL',
  ruleType: 'OFF_PEAK',
  discountType: 'CASH',
  adjustmentType: 'PERCENT',
  value: 10,
  maxDiscount: 0,
  budget: 0,
  threshold: 0,
  total: 0,
  issued: 0,
  limit: 1,
  costOwner: '',
  campaignId: 0,
  stack: false,
  days: [] as number[],
  from: '09:00',
  to: '22:00',
  priority: 0,
  reason: '',
  status: 'DRAFT'
})

const canViewCurrent = computed(() => auth.hasPermission(TAB_PERMISSIONS[view.value]))
const overviewCounts = computed(() => overviewNote(overview.value).counts)
const overviewText = computed(() => overviewNote(overview.value).note)

function initialView(): PromotionView {
  return resolveInitialTab<PromotionView>({
    tabKeys: TABS.map((tab) => tab.key),
    routeTabs: ROUTE_VIEWS,
    routeName: route.name,
    hash: typeof route.hash === 'string' ? route.hash : '',
    canView: (tab) => auth.hasPermission(TAB_PERMISSIONS[tab])
  })
}

function statusAttention(label: string): boolean {
  return /失败|待|异常|锁定|已发完/.test(label)
}

function scopeText(venueId: number | null | undefined, spaceId: number | null | undefined): string {
  return `${venueLabel(venueId)} · ${spaceLabel(spaceId)}`
}

function venueLabel(venueId: number | null | undefined): string {
  if (!venueId) return '全部场馆'
  return venues.value.find((row) => row.id === venueId)?.name ?? `场馆 ${venueId}`
}

function spaceLabel(spaceId: number | null | undefined): string {
  if (!spaceId) return '全部场地'
  return spaces.value.find((row) => row.id === spaceId)?.name ?? `场地 ${spaceId}`
}

function memberScopeText(code: string | null | undefined): string {
  if (!code) return '不限等级'
  const found = levels.value.find((row) => row.levelCode === code)
  return found ? `${found.levelName}（精确等级）` : `${code}（精确等级）`
}

function campaignName(id: number | null | undefined): string {
  if (!id) return '不关联促销'
  return campaigns.value.find((row) => row.id === id)?.campaignName ?? `活动 ${id}`
}

function readableError(error: unknown, fallback: string): string {
  const response = (error as { response?: { data?: { message?: string } } })?.response
  return response?.data?.message || (error as Error)?.message || fallback
}

/* --------------------------------------------------------------- 数据加载 */

async function loadVenueOptions(): Promise<void> {
  try {
    venues.value = await venueAdminApi.manageable(isolated)
  } catch {
    venues.value = []
  }
}

/**
 * 列表里要显示真实的场地名与等级名，而这些名称分别在场地接口与等级接口里。
 * 只对当前列表实际出现的场馆各取一次（有界、去重、失败即回落为编号），不做跨场馆全量抓取。
 */
async function ensureSpaceNames(venueIds: Array<number | null | undefined>): Promise<void> {
  const missing = [...new Set(venueIds.filter((id): id is number => !!id && !spaces.value.some((s) => s.venueId === id)))]
  if (!missing.length) return
  const results = await Promise.all(missing.map(async (id) => {
    try {
      return await venueApi.spaces(id, isolated)
    } catch {
      return [] as VenueSpace[]
    }
  }))
  const merged = new Map(spaces.value.map((row) => [row.id, row]))
  for (const rows of results) for (const row of rows) merged.set(row.id, row)
  spaces.value = [...merged.values()]
}

async function ensureLevelNames(): Promise<void> {
  if (levels.value.length) return
  try {
    levels.value = await adminMemberApi.levels(isolated)
  } catch {
    levels.value = []
  }
}

/** 场地选项跟随所选场馆；未选场馆时不请求（避免跨场馆拉取，B4-P4）。 */
async function loadSpaceOptions(): Promise<void> {
  if (!form.venueId) {
    spaces.value = []
    return
  }
  try {
    spaces.value = await venueApi.spaces(form.venueId, isolated)
  } catch {
    spaces.value = []
  }
}

async function loadCampaigns(): Promise<void> {
  const [page, summary] = await Promise.all([
    promotionAdminApi.campaigns({ pageNum: 1, pageSize: 50 }, isolated),
    promotionAdminApi.overview(isolated)
  ])
  campaigns.value = page.records || []
  overview.value = summary
  await Promise.all([ensureSpaceNames(campaigns.value.map((row) => row.venueId)), ensureLevelNames()])
}

async function loadCoupons(): Promise<void> {
  // 「关联促销」下拉需要活动列表（B4-P12）；活动读取失败只影响该下拉，不阻断券模板管理。
  if (!campaigns.value.length) {
    try {
      const page = await promotionAdminApi.campaigns({ pageNum: 1, pageSize: 50 }, isolated)
      campaigns.value = page.records || []
    } catch {
      campaigns.value = []
    }
  }
  const [page, levelList] = await Promise.all([
    promotionAdminApi.couponTemplates({ pageNum: 1, pageSize: 50 }, isolated),
    adminMemberApi.levels(isolated).catch(() => [] as MemberLevelResponse[])
  ])
  coupons.value = page.records || []
  levels.value = levelList
  await ensureSpaceNames(coupons.value.map((row) => row.venueId))
}

async function loadRules(): Promise<void> {
  const [page, levelList] = await Promise.all([
    promotionAdminApi.priceRules({ pageNum: 1, pageSize: 50 }, isolated),
    adminMemberApi.levels(isolated).catch(() => [] as MemberLevelResponse[])
  ])
  rules.value = page.records || []
  levels.value = levelList
  await ensureSpaceNames(rules.value.map((row) => row.venueId))
}

async function loadRedemptions(): Promise<void> {
  const page = await promotionAdminApi.couponRedemptions(
    {
      pageNum: redemptionPage.pageNum,
      pageSize: redemptionPage.pageSize,
      ...(redemptionStatus.value ? { status: redemptionStatus.value } : {})
    },
    isolated
  )
  redemptions.value = page.records || []
  redemptionTotal.value = page.total ?? 0
}

async function bootstrap(): Promise<void> {
  surface.value = 'loading'
  loadError.value = ''
  try {
    if (!canViewCurrent.value) {
      surface.value = 'ready'
      return
    }
    if (view.value === 'campaigns') await loadCampaigns()
    else if (view.value === 'coupons') await loadCoupons()
    else if (view.value === 'rules') await loadRules()
    else await loadRedemptions()
    surface.value = 'ready'
  } catch (error) {
    surface.value = 'error'
    loadError.value = readableError(error, '营销数据暂时无法读取。')
  }
}

/* ----------------------------------------------------------------- 页签 */

function applyView(next: PromotionView): void {
  view.value = next
  void router.replace({ hash: `#${next}` })
}

function go(next: PromotionView): void {
  if (view.value === next) return
  rememberTabFocus(next)
  applyView(next)
  void bootstrap()
  void nextTick(() => document.getElementById(`tab-${next}`)?.focus())
}

function reloadRedemptions(): void {
  redemptionPage.pageNum = 1
  void bootstrap()
}

function moveRedemptionPage(delta: number): void {
  redemptionPage.pageNum = Math.max(1, redemptionPage.pageNum + delta)
  void bootstrap()
}

function toggleDay(day: number, checked: boolean): void {
  const next = new Set(form.days)
  if (checked) next.add(day)
  else next.delete(day)
  form.days = [...next].sort((a, b) => a - b)
}

/* --------------------------------------------------------------- 表单弹层 */

const NOUNS: Record<ObjectKind, string> = { campaigns: '促销', coupons: '优惠券模板', rules: '定价规则' }

const SUMMARY_NOTES: Record<ObjectKind, string> = {
  campaigns: '启用状态与时间窗口一起决定活动是否可用；预算不是已发生的优惠成本。',
  coupons: '券模板定义发行条件；权益目录引用模板，会员兑换另有权限与版本。',
  rules: '这里只显示规则条件；库存、折扣叠加顺序与最终应付由真实交易合同决定。'
}

const FOOT_NOTES: Record<ObjectKind, string> = {
  campaigns: '会员范围为精确等级匹配；重叠活动由服务端按优先级与创建时间选取，本面不做叠加计算。',
  coupons: '总发行量填 0 表示不限量；编辑时不得小于已发行数量，否则该模板将无法再发放。',
  rules: '生效星期与每日时段按服务端的逗号掩码与时刻区间保存；结束时刻必须晚于开始时刻。'
}

function openForm(kind: ObjectKind, index: number, event?: Event): void {
  rememberTrigger(event, kind === 'campaigns' ? `campaign-${index < 0 ? 'create' : `edit-${index}`}`
    : kind === 'coupons' ? `coupon-${index < 0 ? 'create' : `edit-${index}`}`
      : `rule-${index < 0 ? 'create' : `edit-${index}`}`)
  const noun = NOUNS[kind]
  resetForm()
  if (index >= 0) fillForm(kind, index)
  openDialog({
    kind,
    title: `${index < 0 ? '新增' : '查看与编辑'}${noun}`,
    submit: `保存${noun}`,
    cancelText: '返回',
    bodyText: '',
    detail: '',
    noun,
    summaryNote: SUMMARY_NOTES[kind],
    footNote: FOOT_NOTES[kind],
    index
  })
}

function resetForm(): void {
  const now = new Date()
  const iso = (date: Date) => date.toISOString().slice(0, 16)
  form.id = null
  form.name = ''
  form.code = ''
  form.venueId = 0
  form.spaceId = 0
  form.start = iso(now)
  form.end = iso(new Date(now.getTime() + 30 * 86400000))
  form.level = ''
  form.campaignType = 'SEASONAL'
  form.ruleType = 'OFF_PEAK'
  form.discountType = 'CASH'
  form.adjustmentType = 'PERCENT'
  form.value = 10
  form.maxDiscount = 0
  form.budget = 0
  form.threshold = 0
  form.total = 0
  form.issued = 0
  form.limit = 1
  form.costOwner = ''
  form.campaignId = 0
  form.stack = false
  form.days = []
  form.from = '09:00'
  form.to = '22:00'
  form.priority = 0
  form.reason = ''
  form.status = 'DRAFT'
  spaces.value = []
}

function fillForm(kind: ObjectKind, index: number): void {
  if (kind === 'campaigns') {
    const row = campaigns.value[index]
    form.id = row.id
    form.name = row.campaignName
    form.code = row.campaignCode
    form.venueId = row.venueId ?? 0
    form.spaceId = row.spaceId ?? 0
    form.start = (row.startTime ?? '').slice(0, 16)
    form.end = (row.endTime ?? '').slice(0, 16)
    form.level = row.targetMemberLevel ?? ''
    form.campaignType = row.campaignType ?? 'SEASONAL'
    form.discountType = row.discountType ?? 'CASH'
    form.value = row.discountValue ?? 0
    form.maxDiscount = row.maxDiscountAmount ?? 0
    form.budget = row.budgetAmount ?? 0
    form.stack = row.allowCouponStack === true
    form.priority = row.priority ?? 0
    form.status = row.status
  } else if (kind === 'coupons') {
    const row = coupons.value[index]
    form.id = row.id
    form.name = row.templateName
    form.code = row.templateCode
    form.venueId = row.venueId ?? 0
    form.spaceId = row.spaceId ?? 0
    form.start = (row.validStartTime ?? '').slice(0, 16)
    form.end = (row.validEndTime ?? '').slice(0, 16)
    form.discountType = row.couponType ?? 'CASH'
    form.value = row.discountValue ?? 0
    form.maxDiscount = row.maxDiscountAmount ?? 0
    form.threshold = row.thresholdAmount ?? 0
    form.total = row.totalQuantity ?? 0
    form.issued = row.issuedQuantity ?? 0
    form.limit = row.perUserLimit ?? 1
    form.costOwner = row.costOwner ?? ''
    form.campaignId = row.campaignId ?? 0
    form.stack = row.allowActivityStack === true
    form.status = row.status
  } else {
    const row = rules.value[index]
    form.id = row.id
    form.name = row.ruleName
    form.code = row.ruleCode
    form.venueId = row.venueId ?? 0
    form.spaceId = row.spaceId ?? 0
    form.start = (row.startTime ?? '').slice(0, 16)
    form.end = (row.endTime ?? '').slice(0, 16)
    form.level = row.memberLevelCode ?? ''
    form.ruleType = row.ruleType ?? 'OFF_PEAK'
    form.adjustmentType = row.adjustmentType ?? 'PERCENT'
    form.value = row.adjustmentValue ?? 0
    form.priority = row.priority ?? 0
    form.reason = row.reasonText ?? ''
    form.days = (row.dayOfWeekMask ?? '').split(',').map((part) => Number(part.trim())).filter((day) => day >= 1 && day <= 7)
    form.from = (row.startClock ?? '09:00').slice(0, 5)
    form.to = (row.endClock ?? '22:00').slice(0, 5)
    form.status = row.status
  }
  void loadSpaceOptions()
}

function openDialog(partial: Omit<DialogState, 'error' | 'busy'>): void {
  dialog.value = { ...partial, error: '', busy: false }
  void nextTick(() => dialogEl.value?.showModal())
}

function setDialogError(message: string): void {
  if (dialog.value) dialog.value = { ...dialog.value, error: message }
}

function closeDialog(): void {
  dialogEl.value?.close()
}

async function finishDialog(message: string): Promise<void> {
  restoring.value = true
  closeDialog()
  await nextTick()
  await bootstrap()
  app.showToast(message)
  restoring.value = false
  restoreTriggerFocus()
}

function confirmDialog(): void {
  if (!dialog.value || dialog.value.busy) return
  void submitForm()
}

async function submitForm(): Promise<void> {
  const kind = dialog.value?.kind
  if (kind !== 'campaigns' && kind !== 'coupons' && kind !== 'rules') return
  if (!form.name.trim() || !form.code.trim()) {
    setDialogError('名称与业务编码都不能为空。')
    return
  }
  if (form.end <= form.start) {
    setDialogError('生效结束需晚于开始时间。')
    return
  }
  if (kind !== 'rules' && form.discountType === 'PERCENT' && (form.value <= 0 || form.value > 1)) {
    setDialogError('折扣率应大于 0 且不超过 1。')
    return
  }
  if (kind === 'rules' && form.to <= form.from) {
    setDialogError('每日结束时刻需晚于开始时刻。')
    return
  }
  if (kind === 'coupons' && form.total > 0 && form.total < form.issued) {
    setDialogError('发行总量不能小于已发行数量。')
    return
  }
  dialog.value = { ...dialog.value!, busy: true, error: '' }
  try {
    if (kind === 'campaigns') await saveCampaign()
    else if (kind === 'coupons') await saveCoupon()
    else await saveRule()
    await finishDialog('规则已保存。')
  } catch (error) {
    setDialogError(readableError(error, '保存失败，请核对条件后重试。'))
    dialog.value = { ...dialog.value!, busy: false }
  }
}

function optional(value: number): number | undefined {
  return value > 0 ? value : undefined
}

async function saveCampaign(): Promise<void> {
  const payload = {
    campaignCode: form.code.trim(),
    campaignName: form.name.trim(),
    campaignType: form.campaignType,
    venueId: optional(form.venueId),
    spaceId: optional(form.spaceId),
    targetMemberLevel: form.level || undefined,
    startTime: form.start,
    endTime: form.end,
    budgetAmount: form.budget,
    discountType: form.discountType,
    discountValue: form.value,
    maxDiscountAmount: form.maxDiscount,
    priority: form.priority,
    allowCouponStack: form.stack,
    status: form.status
  }
  if (form.id != null) await promotionAdminApi.updateCampaign(form.id, payload, isolated)
  else await promotionAdminApi.createCampaign(payload, isolated)
}

async function saveCoupon(): Promise<void> {
  const payload = {
    templateCode: form.code.trim(),
    templateName: form.name.trim(),
    couponType: form.discountType,
    discountValue: form.value,
    thresholdAmount: form.threshold,
    maxDiscountAmount: form.maxDiscount,
    totalQuantity: form.total,
    perUserLimit: form.limit,
    validStartTime: form.start,
    validEndTime: form.end,
    campaignId: optional(form.campaignId),
    venueId: optional(form.venueId),
    spaceId: optional(form.spaceId),
    allowActivityStack: form.stack,
    costOwner: form.costOwner.trim() || undefined,
    status: form.status
  }
  if (form.id != null) await promotionAdminApi.updateCouponTemplate(form.id, payload, isolated)
  else await promotionAdminApi.createCouponTemplate(payload, isolated)
}

async function saveRule(): Promise<void> {
  const payload = {
    ruleCode: form.code.trim(),
    ruleName: form.name.trim(),
    ruleType: form.ruleType.trim(),
    venueId: optional(form.venueId),
    spaceId: optional(form.spaceId),
    memberLevelCode: form.level || undefined,
    startTime: form.start,
    endTime: form.end,
    dayOfWeekMask: form.days.length ? daysToWeekdayMask(form.days) : undefined,
    startClock: form.from,
    endClock: form.to,
    adjustmentType: form.adjustmentType,
    adjustmentValue: form.value,
    priority: form.priority,
    status: form.status,
    reasonText: form.reason.trim()
  }
  if (form.id != null) await promotionAdminApi.updatePriceRule(form.id, payload, isolated)
  else await promotionAdminApi.createPriceRule(payload, isolated)
}

/* ------------------------------------------------------------ 焦点与键盘 */

function rememberTrigger(event: Event | undefined, test: string): void {
  const node = (event?.currentTarget as HTMLElement | undefined) ?? (document.activeElement as HTMLElement | null)
  lastTrigger.value = node ?? null
  lastTriggerTest.value = test
}

function restoreTriggerFocus(): void {
  restoreFocusChain({
    rootSelector: '.sga-ops',
    trigger: lastTrigger.value,
    triggerTest: lastTriggerTest.value,
    tabSelector: `.sga-ops #tab-${view.value}`
  })
}

function onDialogClose(): void {
  if (restoring.value) return
  restoreTriggerFocus()
}

function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Tab' || !dialogEl.value) return
  const nodes = [...dialogEl.value.querySelectorAll<HTMLElement>(
    'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
  )].filter((node) => !node.hasAttribute('disabled') && node.offsetParent !== null)
  if (!nodes.length) return
  const first = nodes[0]
  const last = nodes[nodes.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

watch(() => form.venueId, () => {
  if (dialog.value) void loadSpaceOptions()
})

watch(() => route.name, () => {
  const next = initialView()
  if (next !== view.value) {
    view.value = next
    void bootstrap()
  }
})

onMounted(() => {
  void loadVenueOptions()
  void bootstrap()
  if (consumeTabFocus(view.value)) void nextTick(() => document.getElementById(`tab-${view.value}`)?.focus())
})

defineExpose({ view, surface, campaigns, coupons, rules, redemptions, dialog, form })
</script>
