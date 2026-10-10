<template>
  <div ref="rootEl" class="sgv-user-service">
    <div class="d-top">
      <div>
        <p class="d-eyebrow">我的预约与订单</p>
        <h1>{{ heading }}</h1>
        <p class="d-intro">{{ intro }}</p>
      </div>
    </div>

    <nav v-if="screen === 'list'" class="d-tabs" aria-label="我的交易任务">
      <button
        v-for="[key, label] in SECTION_LABELS"
        :key="key"
        class="d-tab"
        type="button"
        :aria-pressed="section === key"
        @click="selectSection(key)"
      >{{ label }}</button>
    </nav>
    <button v-else class="d-link" type="button" @click="backToList">← 返回记录</button>

    <div id="transaction-content" data-route-focus tabindex="-1">
      <section v-if="surfaceState" class="d-state" :role="surfaceState.role" :aria-busy="surfaceState.busy">
        <h2>{{ surfaceState.title }}</h2>
        <p>{{ surfaceState.text }}</p>
        <button v-if="surfaceState.action" class="d-btn" :class="{ primary: surfaceState.primary }" type="button" @click="surfaceState.action.handler">
          {{ surfaceState.action.label }}
        </button>
      </section>

      <template v-else>
        <!-- 列表 -->
        <template v-if="screen === 'list'">
          <section v-if="sectionDenied" class="d-state">
            <h2>{{ section === 'orders' ? '尚未获得本人订单权限' : '尚未获得到场凭证权限' }}</h2>
            <p>{{ section === 'orders' ? '可以继续查看预约记录；订单金额与付款动作不展示。' : '请恢复授权后读取凭证，不能依据预约状态直接生成核销码。' }}</p>
          </section>

          <template v-else>
            <section v-if="section === 'reservations' && filter === 'all'" class="d-journey">
              <article class="d-next">
                <span class="d-pill">下一次到场</span>
                <template v-if="nextUp">
                  <h2>
                    <template v-if="spaceName(nextUp)">{{ venueName(nextUp) }}<br>{{ spaceName(nextUp) }}</template>
                    <template v-else>{{ venueName(nextUp) }}</template>
                  </h2>
                  <p>{{ whenLabel(nextUp.reservation.startTime) }} · {{ timeRange(nextUp.reservation.startTime, nextUp.reservation.endTime) }}</p>
                  <div class="d-row">
                    <button v-if="canCredential" class="d-btn primary" type="button" @click="openCredential(nextUp.reservation.id)">准备到场凭证</button>
                    <button class="d-link" type="button" @click="openDetail(nextUp.reservation.id)">查看预约</button>
                  </div>
                </template>
                <template v-else>
                  <h2>新的安排，慢慢再选。</h2>
                  <p>当前没有待到场的已支付预约。</p>
                </template>
              </article>
              <div class="d-quiet-links">
                <h3>还有这些待办</h3>
                <router-link to="/me/time"><span><strong>我的时间</strong><small>打开已确认的安排汇合预览</small></span><span aria-hidden="true">↗</span></router-link>
                <button v-if="pendingOrderRow" type="button" data-test="todo-pending-payment" @click="openPayment(pendingOrderRow.reservation.id)">
                  <span>
                    <strong>{{ rowTitle(pendingOrderRow) }} · 待支付</strong>
                    <small>{{ whenLabel(pendingOrderRow.reservation.startTime) }} {{ timeRange(pendingOrderRow.reservation.startTime, pendingOrderRow.reservation.endTime) }}<br>订单金额 ¥{{ money(pendingOrderRow.order?.totalAmount) }}</small>
                  </span>
                  <span aria-hidden="true">→</span>
                </button>
                <p v-else class="d-muted">当前没有待支付订单。</p>
                <button v-if="refundingRow" type="button" @click="openDetail(refundingRow.reservation.id)">
                  <span>
                    <strong>{{ rowTitle(refundingRow) }} · 退款申请已提交</strong>
                    <small>{{ refundingRow.reservation.status === 'REFUNDING' ? '等待审核结果；尚未确认到账' : '退款有最新进展，点击查看' }}</small>
                  </span>
                  <span aria-hidden="true">→</span>
                </button>
                <router-link v-if="hasPendingFeedback" to="/me/feedback">
                  <span><strong>查看场馆反馈</strong><small>本人处理记录</small></span><span aria-hidden="true">→</span>
                </router-link>
              </div>
            </section>

            <div class="d-top" style="margin-top:32px">
              <div>
                <h2>{{ sectionHeading }}</h2>
                <p class="d-muted">{{ sectionIntro }}</p>
              </div>
              <label>记录范围
                <select v-model="filter" data-test="record-filter">
                  <option value="all">全部记录</option>
                  <option value="active">仍需关注</option>
                  <option value="history">已结束</option>
                </select>
              </label>
            </div>

            <template v-if="section === 'reservations'">
              <article v-for="row in filteredRows" :key="`r-${row.reservation.id}`" class="d-booking-row">
                <div class="d-date">
                  <strong>{{ dateParts(row.reservation.startTime).day }}</strong>
                  {{ dateParts(row.reservation.startTime).month }}
                </div>
                <div>
                  <span class="d-pill" :class="statusTone(row.reservation.status)">{{ reservationStatusLabel(row.reservation.status) }}</span>
                  <h3>{{ venueName(row) }} · {{ spaceName(row) }}</h3>
                  <p>{{ whenLabel(row.reservation.startTime) }} · {{ timeRange(row.reservation.startTime, row.reservation.endTime) }}</p>
                  <p>{{ reservationDisplayText(row.reservation) }}</p>
                  <p v-if="row.reschedulePending">改期申请待审核</p>
                </div>
                <div class="d-record-actions">
                  <button class="d-btn" type="button" @click="openDetail(row.reservation.id)">查看记录</button>
                </div>
              </article>
              <div v-if="!filteredRows.length" class="d-state">
                <h3>这个范围没有记录</h3>
                <button class="d-btn" type="button" @click="filter = 'all'">查看全部</button>
              </div>
            </template>

            <template v-else-if="section === 'orders'">
              <article v-for="row in filteredRows" :key="`o-${row.reservation.id}`" class="d-booking-row">
                <div class="d-date">
                  <strong>{{ dateParts(row.reservation.startTime).day }}</strong>
                  {{ dateParts(row.reservation.startTime).month }}
                </div>
                <div>
                  <span class="d-pill" :class="statusTone(orderStatusOf(row) || '')">{{ orderStatusLabel(orderStatusOf(row) || '') }}</span>
                  <h3>{{ venueName(row) }} · {{ spaceName(row) }}</h3>
                  <p>{{ whenLabel(row.reservation.startTime) }} · {{ timeRange(row.reservation.startTime, row.reservation.endTime) }}</p>
                  <p>{{ orderDisplayText(row.order) }}</p>
                  <p v-if="row.reschedulePending">改期申请待审核</p>
                </div>
                <div class="d-record-actions">
                  <div class="d-money">¥{{ money(row.order?.totalAmount) }}</div>
                  <button class="d-btn" type="button" @click="openDetail(row.reservation.id)">查看记录</button>
                  <button v-if="orderStatusOf(row) === 'PENDING_PAYMENT'" class="d-link" type="button" data-test="row-payment" @click="openPayment(row.reservation.id)">继续付款</button>
                </div>
              </article>
              <div v-if="!filteredRows.length" class="d-state">
                <h3>这个范围没有记录</h3>
                <button class="d-btn" type="button" @click="filter = 'all'">查看全部</button>
              </div>
            </template>

            <template v-else>
              <article v-for="row in filteredRows" :key="`c-${row.reservation.id}`" class="d-booking-row">
                <div class="d-date">
                  <strong>{{ dateParts(row.reservation.startTime).day }}</strong>
                  {{ dateParts(row.reservation.startTime).month }}
                </div>
                <div>
                  <span class="d-pill" :class="statusTone(row.reservation.status)">{{ reservationStatusLabel(row.reservation.status) }}</span>
                  <h3>{{ venueName(row) }} · {{ spaceName(row) }}</h3>
                  <p>{{ whenLabel(row.reservation.startTime) }} · {{ timeRange(row.reservation.startTime, row.reservation.endTime) }}</p>
                  <p>{{ reservationDisplayText(row.reservation) }}</p>
                  <p>{{ credentialLine(row) }}</p>
                </div>
                <div class="d-record-actions">
                  <button class="d-btn primary" type="button" @click="openCredential(row.reservation.id)">查看凭证</button>
                </div>
              </article>
              <div v-if="!filteredRows.length" class="d-state">
                <h3>这个范围没有记录</h3>
                <button class="d-btn" type="button" @click="filter = 'all'">查看全部</button>
              </div>
            </template>
          </template>
        </template>

        <!-- 详情 -->
        <div v-else-if="screen === 'detail' && detailRow" class="d-detail">
          <section class="d-detail-main">
            <p class="d-eyebrow">{{ reservationDisplayText(detailRow.reservation) }} · 本人预约</p>
            <h2>{{ venueName(detailRow) }}<br>{{ spaceName(detailRow) }}</h2>
            <p>{{ whenLabel(detailRow.reservation.startTime) }} · {{ timeRange(detailRow.reservation.startTime, detailRow.reservation.endTime) }}</p>
            <div class="d-status-list">
              <span class="current">预约：{{ reservationStatusLabel(detailRow.reservation.status) }}</span>
              <span v-if="detailRow.order && canOrder">订单：{{ orderStatusLabel(orderStatusOf(detailRow) || '') }}</span>
            </div>
            <div v-if="detailRow.reservation.rejectReason" class="d-notice">取消原因：{{ detailRow.reservation.rejectReason }}</div>
            <div v-if="detailRow.reschedulePending" class="d-notice">
              <strong>改期申请待审核</strong>
              <p>希望调整的时段在审核中。原时段 {{ whenLabel(detailRow.reservation.startTime) }} {{ timeRange(detailRow.reservation.startTime, detailRow.reservation.endTime) }} 保留，审核通过后才变更。</p>
            </div>
            <div v-if="refundSummaryOf(detailRow)" class="d-notice">
              <strong>退款申请已提交</strong>
              <p>{{ refundSummaryOf(detailRow)!.refundNo }} · 申请金额 ¥{{ money(refundSummaryOf(detailRow)!.refundAmount) }}</p>
              <p>当前订单为{{ ORDER_STATUS_LABELS[refundSummaryOf(detailRow)!.status] || refundSummaryOf(detailRow)!.status }}。审核与到账结果请以最新订单和通知为准。</p>
            </div>
            <div class="d-row">
              <button v-if="detailRow.reservation.status === 'PENDING_PAYMENT' && canOrder" class="d-btn primary" type="button" @click="openPayment(detailRow.reservation.id)">去支付</button>
              <button v-if="detailRow.reservation.status === 'PAID' && canCredential" class="d-btn primary" type="button" @click="openCredential(detailRow.reservation.id)">查看到场凭证</button>
              <button v-if="canReschedule(detailRow)" class="d-btn" type="button" data-test="open-reschedule" @click="openReschedule(detailRow.reservation.id, $event)">申请改期</button>
              <button v-if="canCancel(detailRow)" class="d-btn" type="button" data-test="open-cancel" @click="openCancel(detailRow.reservation.id)">取消预约</button>
            </div>
            <section class="d-info">
              <h3>预约信息</h3>
              <dl>
                <dt>使用时段</dt><dd>{{ whenLabel(detailRow.reservation.startTime) }} {{ timeRange(detailRow.reservation.startTime, detailRow.reservation.endTime) }}</dd>
                <dt>场馆与位置</dt><dd>{{ venueName(detailRow) }} · {{ spaceName(detailRow) }}</dd>
                <dt>联系信息</dt><dd>{{ contactLine(detailRow) }}</dd>
                <dt>改期说明</dt><dd>未支付预约可申请改期，原时段在审核通过前保留。已支付预约暂不支持改期。</dd>
              </dl>
            </section>
            <router-link class="d-link" :to="detailFeedbackLink">对这次预约有疑问？查看或提交反馈 →</router-link>
          </section>
          <aside class="d-summary">
            <h3>{{ detailRow.order ? '关联订单' : '尚未生成订单' }}</h3>
            <template v-if="detailRow.order && canOrder">
              <p class="d-muted">{{ detailRow.order.orderNo }}</p>
              <dl>
                <dt>原价</dt><dd>¥{{ money(detailRow.order.originalAmount ?? detailRow.order.totalAmount) }}</dd>
                <dt>已应用优惠</dt><dd>− ¥{{ money(discountOf(detailRow.order)) }}</dd>
                <dt>实付</dt><dd>¥{{ money(detailRow.order.paidAmount ?? detailRow.order.totalAmount) }}</dd>
                <template v-if="detailRow.order.paymentToolType === 'STORED_VALUE'">
                  <dt>付款来源</dt><dd>储值支付</dd>
                  <template v-if="storedSplitOf(detailRow.reservation.id)">
                    <dt>本金 / 赠送</dt><dd>¥{{ money(storedSplitOf(detailRow.reservation.id)!.principalAmount) }} / ¥{{ money(storedSplitOf(detailRow.reservation.id)!.bonusAmount) }}</dd>
                  </template>
                </template>
                <template v-else>
                  <dt>付款来源</dt><dd>{{ paySourceOf(detailRow.order) }}</dd>
                </template>
              </dl>
              <div class="d-total">
                <span>订单金额</span>
                <strong>¥{{ money(detailRow.order.totalAmount) }}</strong>
              </div>
              <button
                v-if="detailRow.order.status === 'PAID' && detailRow.reservation.status === 'PAID'"
                class="d-btn"
                type="button"
                data-test="apply-refund"
                @click="openRefund(detailRow.reservation.id)"
              >申请全额退款</button>
              <p class="d-muted">申请先进入人工审核，不代表退款已到账。</p>
            </template>
            <p v-else-if="detailRow.order" class="d-muted">缺少本人订单权限，金额与订单处理不展示。</p>
            <p v-else class="d-muted">预约仍待审核，审核结果会通过消息通知。此处不以预估价格代替订单。</p>
            <template v-if="refundSummaryOf(detailRow) && canOrder">
              <p>申请原因：{{ refundSummaryOf(detailRow)!.userVisibleReason }}</p>
              <router-link class="d-link" to="/me/messages#messages">查看结果通知 →</router-link>
            </template>
          </aside>
        </div>
        <section v-else-if="screen === 'detail'" class="d-state">
          <h2>没有找到这条本人记录</h2>
          <p>请返回列表重新选择，不能根据链接推断对象归属。</p>
          <button class="d-btn" type="button" @click="backToList">返回记录</button>
        </section>

        <!-- 支付（页内深链，与独立路由共用面板） -->
        <template v-else-if="screen === 'payment' && activeId">
          <ShiguangTransactionsPaymentPanel
            :reservation-id="activeId"
            embedded
            @settled="load"
            @leave="backToList"
          />
        </template>

        <!-- 凭证 -->
        <div v-else-if="screen === 'credential' && credentialRow" class="d-credential">
          <div class="d-top">
            <div>
              <p class="d-eyebrow">{{ reservationDisplayText(credentialRow.reservation) }}</p>
              <h2>{{ venueName(credentialRow) }}<br>{{ spaceName(credentialRow) }}</h2>
            </div>
            <span class="d-pill">{{ credentialState(credentialRow).pill }}</span>
          </div>
          <p>{{ whenLabel(credentialRow.reservation.startTime) }} · {{ timeRange(credentialRow.reservation.startTime, credentialRow.reservation.endTime) }}</p>
          <div class="d-credential-band">
            <p class="d-muted">{{ credentialState(credentialRow).invalid ? '凭证记录' : '到场核销码' }}</p>
            <div class="d-code" data-test="credential-code">{{ credentialState(credentialRow).invalid ? '已失效' : (activeCode || credentialState(credentialRow).suffix) }}</div>
            <p>可核销时间：{{ credentialWindow(credentialRow) }}</p>
            <template v-if="!credentialState(credentialRow).invalid">
              <div v-if="activeCode" class="d-row">
                <button class="d-btn primary" type="button" @click="openShowCode">放大出示</button>
                <button class="d-btn" type="button" data-test="credential-update" @click="openCredentialUpdate(credentialRow.reservation.id)">更新凭证</button>
              </div>
              <template v-else>
                <button class="d-btn primary" type="button" data-test="credential-claim" @click="openCredentialUpdate(credentialRow.reservation.id)">领取完整凭证</button>
                <p class="d-muted" style="margin-top:18px">查询记录只保留尾号。领取后会生成本次可出示的完整码。</p>
              </template>
            </template>
            <p v-else class="d-muted">凭证已结束或当前预约不满足使用条件，不能通过更新延长有效期。</p>
          </div>
          <p class="d-muted">{{ credentialState(credentialRow).hintText }}</p>
          <router-link class="d-link" :to="detailFeedbackLink">对到场记录有疑问？提交反馈 →</router-link>
        </div>
        <section v-else-if="screen === 'credential'" class="d-state">
          <h2>没有找到这条本人记录</h2>
          <p>请返回列表重新选择，不能根据链接推断对象归属。</p>
          <button class="d-btn" type="button" @click="backToList">返回记录</button>
        </section>
      </template>
    </div>

    <dialog ref="dialogEl" aria-labelledby="dialog-title" @close="onDialogClose">
      <form v-if="dialog" @submit.prevent="confirmDialog">
        <h2 id="dialog-title">{{ dialog.title }}</h2>
        <template v-if="dialog.kind === 'cancel'">
          <p>{{ dialog.row!.reservation.venueName }} · {{ whenLabel(dialog.row!.reservation.startTime) }} {{ timeRange(dialog.row!.reservation.startTime, dialog.row!.reservation.endTime) }}</p>
          <label class="d-field">取消原因<textarea v-model="dialog.reason" name="reason" data-test="cancel-reason" required></textarea></label>
          <p class="d-muted">{{ dialog.row!.reservation.status === 'PAID' ? '已支付预约取消后会进入退款处理，取消不代表款项已经退回。' : '取消后释放原预约，不能直接恢复为已确认。' }}</p>
        </template>
        <template v-else-if="dialog.kind === 'reschedule'">
          <p>原时段：{{ whenLabel(dialog.row!.reservation.startTime) }} {{ timeRange(dialog.row!.reservation.startTime, dialog.row!.reservation.endTime) }}</p>
          <label class="d-field">目标时段
            <select v-model="dialog.slot" name="slot" data-test="reschedule-slot" required>
              <option v-if="!dialog.slots.length" value="">正在读取可申请时段…</option>
              <option v-for="slot in dialog.slots" :key="slot.startTime" :value="slotKey(slot)">{{ slotLabel(slot) }}</option>
            </select>
          </label>
          <label class="d-field">改期原因<textarea v-model="dialog.reason" name="reason" required></textarea></label>
          <div class="d-notice">提交后待场馆审核；原时段在审核通过前保留，价格与可用性会再次核对。</div>
        </template>
        <template v-else-if="dialog.kind === 'refund'">
          <p>{{ dialog.row!.order!.orderNo }} · 申请金额 ¥{{ money(dialog.row!.order!.paidAmount ?? dialog.row!.order!.totalAmount) }}</p>
          <label class="d-field">退款原因<textarea v-model="dialog.reason" name="reason" data-test="refund-reason" required></textarea></label>
          <p class="d-muted">申请先进入人工审核，实际审核与到账请以结果通知为准。当前不支持在这里填写部分退款金额。</p>
        </template>
        <template v-else-if="dialog.kind === 'credential-update'">
          <p>{{ reservationDisplayText(dialog.row!.reservation) }} · {{ dialog.row!.reservation.venueName }}</p>
          <p>签发新凭证后，旧码立即失效。</p>
        </template>
        <template v-else-if="dialog.kind === 'show-code'">
          <p>{{ dialog.row!.reservation.venueName }} · {{ spaceName(dialog.row!) }}</p>
          <div class="d-code">{{ activeCode }}</div>
          <p>{{ credentialWindow(dialog.row!) }}</p>
          <p class="d-muted">请在场馆核销处出示最新凭证。</p>
        </template>
        <p v-if="dialog.error" class="d-error" role="alert">{{ dialog.error }}</p>
        <div class="d-dialog-actions">
          <button class="d-btn" type="button" @click="closeDialog">{{ dialog.kind === 'show-code' ? '关闭' : '暂不操作' }}</button>
          <button
            v-if="dialog.kind !== 'show-code'"
            class="d-btn primary"
            type="submit"
            :data-test="`confirm-${dialog.kind}`"
            :disabled="dialog.busy || (dialog.kind === 'reschedule' && !dialog.slot)"
          >{{ dialog.busy ? '处理中…' : dialog.submit }}</button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { checkinApi } from '@/api/checkin'
import { orderApi } from '@/api/order'
import { reservationApi } from '@/api/reservation'
import { storedValueApi, type StoredValueOrderStoredStatus } from '@/api/storedvalue'
import { feedbackApi } from '@/api/feedback'
import { ensureSpaceNames, spaceNameOf } from './spaceNameLookup'
import type { AvailabilitySlot, Order, Reservation, ReservationLifecycleResponse } from '@/api/types'
import { useMenuStore } from '@/stores/menu'
import {
  ORDER_STATUS_LABELS,
  SECTION_LABELS,
  buildTransactionRow,
  credentialView,
  dateParts,
  isEndedStatus,
  maskPhone,
  money,
  nextUpcoming,
  orderDisplayText,
  orderStatusLabel,
  reservationDisplayText,
  reservationStatusLabel,
  statusTone,
  timeRange,
  whenLabel,
  type SectionKey,
  type TransactionRow
} from './transactionsShared'
import ShiguangTransactionsPaymentPanel from './ShiguangTransactionsPaymentPanel.vue'

const route = useRoute()
const router = useRouter()
const menu = useMenuStore()

const canOrder = computed(() => menu.hasPermission('order:my'))
const canCredential = computed(() => menu.hasPermission('reservation:checkin:me'))

const isolated = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
const PAGE_SIZE = 100

const rootEl = ref<HTMLElement | null>(null)
const loading = ref(true)
const loadError = ref(false)
const denied = ref(false)
const section = ref<SectionKey>('reservations')
const filter = ref<'all' | 'active' | 'history'>('all')
const screen = ref<'list' | 'detail' | 'payment' | 'credential'>('list')
const activeId = ref<number | null>(null)
const activeCode = ref('')

const reservations = ref<Reservation[]>([])
const orders = ref<Order[]>([])
const lifecycles = ref<ReservationLifecycleResponse[]>([])
const pendingRescheduleIds = ref<Set<number>>(new Set())
const storedSplits = ref<Map<number, { principalAmount: number; bonusAmount: number }>>(new Map())
const hasPendingFeedback = ref(false)

const rows = computed<TransactionRow[]>(() => {
  const ordersById = new Map(orders.value.map(order => [order.id, order]))
  const lifecyclesByReservation = new Map(lifecycles.value.map(item => [item.reservationId, item]))
  return reservations.value.map(reservation => buildTransactionRow(
    reservation,
    ordersById,
    lifecyclesByReservation,
    pendingRescheduleIds.value
  ))
})

const rowsForSection = computed(() => {
  if (section.value === 'orders') return rows.value.filter(row => row.order)
  if (section.value === 'credentials') {
    return rows.value.filter(row => row.lifecycle || row.reservation.status === 'PAID')
  }
  return rows.value
})

const filteredRows = computed(() => {
  if (filter.value === 'all') return rowsForSection.value
  if (filter.value === 'active') return rowsForSection.value.filter(row => !isEndedStatus(row.reservation.status))
  return rowsForSection.value.filter(row => isEndedStatus(row.reservation.status))
})

const nextUp = computed(() => nextUpcoming(rows.value))
const pendingOrderRow = computed(() => rows.value.find(row => row.reservation.status === 'PENDING_PAYMENT' && row.order) ?? null)
const refundingRow = computed(() => rows.value.find(row => ['REFUNDING', 'REFUNDED'].includes(row.reservation.status)) ?? null)

const detailRow = computed(() => rows.value.find(row => row.reservation.id === activeId.value) ?? null)
const credentialRow = computed(() => detailRow.value)

const sectionHeading = computed(() => {
  if (section.value === 'orders') return '订单与申请记录'
  if (section.value === 'credentials') return '凭证与到场结果'
  return '你的预约记录'
})
const sectionIntro = computed(() => {
  if (section.value === 'orders') return '付款、退款与预约状态分别记录。'
  if (section.value === 'credentials') return '查询默认只显示尾号，完整码需在有效范围内领取。'
  return '保留已提交的安排，也保留变更与已结束记录。'
})
const sectionDenied = computed(() => (section.value === 'orders' && !canOrder.value) || (section.value === 'credentials' && !canCredential.value))

const heading = computed(() => {
  if (screen.value === 'payment') return '先核对，再完成付款。'
  if (screen.value === 'credential') return '到场时，把这段安排出示给场馆。'
  return '每一段安排，都有下一步。'
})
const intro = computed(() => {
  if (screen.value === 'payment') return '付款以订单金额为准。若结果尚不确定，先核对原支付单，再决定下一步。'
  if (screen.value === 'credential') return '注意使用时段与凭证状态，核销结果由场馆确认。'
  if (screen.value === 'detail') return '保留原预约信息，按当前状态继续处理；有变化时，消息会带你回到这里。'
  return '查看已经提交的预约、处理待支付订单，或准备下一次到场。时间与结果在这里清楚衔接。'
})

const surfaceState = computed(() => {
  if (denied.value) {
    return {
      role: undefined as string | undefined,
      busy: false,
      title: '此内容暂不向当前账号开放',
      text: '只能查看本人且已获授权的预约、订单和凭证。',
      primary: false,
      action: { label: '返回首页', handler: () => router.push('/') }
    }
  }
  if (loading.value) {
    return { role: undefined, busy: true, title: '正在找回你的安排', text: '预约与订单状态加载完成后，再继续操作。', primary: false, action: null }
  }
  if (loadError.value) {
    return {
      role: 'alert' as const,
      busy: false,
      title: '暂时读不到这段安排',
      text: '记录没有被清空，请重试读取。',
      primary: true,
      action: { label: '重新读取', handler: () => { void load() } }
    }
  }
  if (screen.value === 'list' && !rows.value.length) {
    return {
      role: undefined,
      busy: false,
      title: '这里还没有预约记录',
      text: '选定喜欢的场馆之后，你的预约与后续订单会出现在这里。',
      primary: false,
      action: { label: '去看看场馆', handler: () => router.push('/venues') }
    }
  }
  return null
})

type DialogKind = 'cancel' | 'reschedule' | 'refund' | 'credential-update' | 'show-code'

interface DialogState {
  kind: DialogKind
  title: string
  submit: string
  row: TransactionRow | null
  reservationId: number | null
  reason: string
  slot: string
  slots: AvailabilitySlot[]
  error: string
  busy: boolean
}

const dialogEl = ref<HTMLDialogElement | null>(null)
const dialog = ref<DialogState | null>(null)
let lastTrigger: HTMLElement | null = null

const readableError = (error: unknown, fallback: string) => {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (responseMessage) return responseMessage
  return error instanceof Error && error.message ? error.message : fallback
}

function orderStatusOf(row: TransactionRow): string | null {
  return row.order?.status ?? null
}

function venueName(row: TransactionRow): string {
  return row.reservation.venueName || '场馆信息读取中'
}

function spaceName(row: TransactionRow): string {
  return spaceNameOf(row.reservation.spaceId)
}

function rowTitle(row: TransactionRow): string {
  return spaceName(row) || venueName(row)
}

function contactLine(row: TransactionRow): string {
  const name = row.reservation.contactName || '本人'
  return `${name} ${maskPhone(row.reservation.contactPhone)}`
}

function discountOf(order: Pick<Order, 'memberDiscountAmount' | 'couponDiscountAmount' | 'activityDiscountAmount'>): number {
  return Number(order.memberDiscountAmount ?? 0) + Number(order.couponDiscountAmount ?? 0) + Number(order.activityDiscountAmount ?? 0)
}

function paySourceOf(order: Pick<Order, 'paymentToolType' | 'payTime'>): string {
  if (order.paymentToolType === 'STORED_VALUE') return '储值支付'
  return order.payTime ? 'DEV 外部支付' : '尚未支付'
}

function refundSummaryOf(row: TransactionRow) {
  return row.order?.refundSummary ?? null
}

function storedSplitOf(reservationId: number) {
  return storedSplits.value.get(reservationId) ?? null
}

function credentialLine(row: TransactionRow): string {
  const state = credentialView(row.reservation, row.lifecycle)
  if (state.invalid) return '凭证已失效，不能更新'
  return `未核销 · 凭证${state.suffix}`
}

function credentialState(row: TransactionRow) {
  return credentialView(row.reservation, row.lifecycle)
}

function credentialWindow(row: TransactionRow): string {
  const start = row.lifecycle?.checkinWindowStart
  const end = row.lifecycle?.checkinWindowEnd
  if (!start || !end) return '签发后以服务返回为准'
  return `${dateTimeLabel(start)}—${clockLabel(end)}`
}

function dateTimeLabel(value: string): string {
  return `${whenLabel(value).split(' · ')[0]} ${timeRange(value, value).slice(0, 5)}`
}

function clockLabel(value: string): string {
  return timeRange(value, value).slice(0, 5)
}

const detailFeedbackLink = computed(() => activeId.value != null ? `/me/feedback#new-feedback-${activeId.value}` : '/me/feedback#feedback')

function canReschedule(row: TransactionRow): boolean {
  return ['PENDING_REVIEW', 'APPROVED', 'PENDING_PAYMENT'].includes(row.reservation.status) && !row.reschedulePending
}

function canCancel(row: TransactionRow): boolean {
  return ['PENDING_REVIEW', 'APPROVED', 'PENDING_PAYMENT', 'PAID'].includes(row.reservation.status)
}

// —— 深链与导航 ——

function parseHash(): void {
  const hash = route.hash
  if (dialog.value) closeDialog()
  activeCode.value = ''
  if (hash.startsWith('#booking-')) {
    activeId.value = Number(hash.slice('#booking-'.length))
    screen.value = 'detail'
  } else if (hash.startsWith('#pay-')) {
    activeId.value = Number(hash.slice('#pay-'.length))
    screen.value = 'payment'
    section.value = 'orders'
  } else if (hash.startsWith('#credential-')) {
    activeId.value = Number(hash.slice('#credential-'.length))
    screen.value = 'credential'
    section.value = 'credentials'
  } else if (hash.startsWith('#sandbox-return-')) {
    // 沙箱返回在产品中始终走配置固定的独立路由（后端校验 returnUrl），不在页内承接。
    router.replace({ name: 'AlipaySandboxReturn', query: route.query })
    return
  } else {
    screen.value = 'list'
    activeId.value = null
    if (hash === '#orders' || hash === '#reservations' || hash === '#credentials') {
      section.value = hash.slice(1) as SectionKey
    }
  }
}

function initialSectionFromRoute(): SectionKey {
  if (route.name === 'MyOrders') return 'orders'
  if (route.name === 'MyCheckins') return 'credentials'
  return 'reservations'
}

function pushHash(hash: string): void {
  router.push({ hash })
}

function selectSection(key: SectionKey): void {
  section.value = key
  filter.value = 'all'
  activeId.value = null
  pushHash(key === 'reservations' ? '#reservations' : `#${key}`)
}

function go(next: 'detail' | 'payment' | 'credential', id: number): void {
  const prefix = next === 'detail' ? 'booking' : next === 'payment' ? 'pay' : next
  pushHash(`#${prefix}-${id}`)
  nextTick(() => {
    (rootEl.value?.querySelector('#transaction-content') as HTMLElement | null)?.focus({ preventScroll: true })
    window.scrollTo({ top: 0 })
  })
}

function openDetail(id: number): void { go('detail', id) }
function openPayment(id: number): void { go('payment', id) }
function openCredential(id: number): void { go('credential', id) }

function backToList(): void {
  activeId.value = null
  pushHash(section.value === 'reservations' ? '#reservations' : `#${section.value}`)
}

// —— 数据 ——

async function load(): Promise<void> {
  loading.value = true
  loadError.value = false
  denied.value = false
  try {
    const [reservationPage, orderPage, lifecycleList, reschedulePage, complaintPage] = await Promise.all([
      reservationApi.my({ pageNum: 1, pageSize: PAGE_SIZE }, isolated),
      canOrder.value
        ? orderApi.my({ pageNum: 1, pageSize: PAGE_SIZE }, isolated)
        : Promise.resolve({ records: [], total: 0, pageNum: 1, pageSize: PAGE_SIZE }),
      canCredential.value ? checkinApi.my(isolated) : Promise.resolve([]),
      reservationApi.myRescheduleRequests({ pageNum: 1, pageSize: PAGE_SIZE }, isolated),
      feedbackApi.myComplaints({ pageNum: 1, pageSize: PAGE_SIZE }, isolated)
    ])
    reservations.value = reservationPage.records
    orders.value = orderPage.records
    lifecycles.value = lifecycleList
    pendingRescheduleIds.value = new Set(
      reschedulePage.records
        .filter(item => item.status === 'PENDING_REVIEW')
        .map(item => item.reservationId)
    )
    hasPendingFeedback.value = complaintPage.records.some(item => !['RESOLVED', 'CLOSED', 'REJECTED'].includes(item.status ?? ''))
    await ensureSpaceNames(reservations.value.map(item => item.venueId).filter((id): id is number => id != null))
    await loadStoredSplits()
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    if (status === 403) denied.value = true
    else loadError.value = true
  } finally {
    loading.value = false
  }
}

/** 储值支付订单读取本金/赠送分摊（只读核对端点，失败不阻断）。 */
async function loadStoredSplits(): Promise<void> {
  const paidStored = orders.value.filter(order => order.paymentToolType === 'STORED_VALUE' && order.status === 'PAID')
  for (const order of paidStored) {
    if (storedSplits.value.has(order.reservationId)) continue
    try {
      const status: StoredValueOrderStoredStatus = await storedValueApi.orderStoredStatus(order.id, isolated)
      if (status.payment) {
        storedSplits.value.set(order.reservationId, {
          principalAmount: Number(status.payment.principalAmount ?? 0),
          bonusAmount: Number(status.payment.bonusAmount ?? 0)
        })
      }
    } catch {
      // 分摊为补充展示，读取失败不阻断页面
    }
  }
}

// —— 弹层 ——

function openDialog(next: DialogState, trigger: Event | null): void {
  const target = trigger?.target
  lastTrigger = target instanceof HTMLElement ? target : (document.activeElement as HTMLElement | null)
  dialog.value = next
  nextTick(() => dialogEl.value?.showModal())
}

function closeDialog(): void {
  dialogEl.value?.close()
}

function onDialogClose(): void {
  const trigger = lastTrigger
  dialog.value = null
  lastTrigger = null
  if (trigger?.isConnected) trigger.focus()
  else (rootEl.value?.querySelector('#transaction-content') as HTMLElement | null)?.focus({ preventScroll: true })
}

function openCancel(id: number, event?: Event): void {
  const row = detailRow.value
  if (!row) return
  openDialog({
    kind: 'cancel', title: '取消这条预约', submit: '确认取消预约', row,
    reservationId: id, reason: '', slot: '', slots: [], error: '', busy: false
  }, event ?? null)
}

function openReschedule(id: number, event?: Event): void {
  const row = detailRow.value
  if (!row) return
  const state: DialogState = {
    kind: 'reschedule', title: '申请调整使用时间', submit: '提交改期申请', row,
    reservationId: id, reason: '', slot: '', slots: [], error: '', busy: false
  }
  dialog.value = state
  lastTrigger = event?.target instanceof HTMLElement ? event.target : (document.activeElement as HTMLElement | null)
  nextTick(() => dialogEl.value?.showModal())
  void loadRescheduleSlots(state)
}

async function loadRescheduleSlots(state: DialogState): Promise<void> {
  if (!state.reservationId) return
  try {
    const dates = upcomingDates(7)
    const pages = await Promise.allSettled(
      dates.map(date => reservationApi.rescheduleSlots(state.reservationId!, { date }, isolated))
    )
    const slots: AvailabilitySlot[] = []
    for (const result of pages) {
      if (result.status === 'fulfilled') slots.push(...result.value)
    }
    state.slots = slots
    if (slots.length) state.slot = slotKey(slots[0])
  } catch {
    state.slots = []
  }
}

function upcomingDates(count: number): string[] {
  const dates: string[] = []
  const base = new Date()
  for (let offset = 1; dates.length < count && offset <= 30; offset += 1) {
    const day = new Date(base)
    day.setDate(base.getDate() + offset)
    dates.push(`${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`)
  }
  return dates
}

function slotKey(slot: AvailabilitySlot): string {
  return `${slot.startTime}|${slot.endTime}`
}

function slotLabel(slot: AvailabilitySlot): string {
  return `${dateTimeRangeLabel(slot.startTime, slot.endTime)} · 可申请`
}

function dateTimeRangeLabel(start: string, end: string): string {
  return `${whenLabel(start).split(' · ')[0]} ${timeRange(start, end)}`
}

function openRefund(id: number, event?: Event): void {
  const row = detailRow.value
  if (!row?.order) return
  openDialog({
    kind: 'refund', title: '申请全额退款', submit: '提交退款申请', row,
    reservationId: id, reason: '', slot: '', slots: [], error: '', busy: false
  }, event ?? null)
}

function openShowCode(event?: Event): void {
  const row = detailRow.value
  if (!row || !activeCode.value) return
  openDialog({
    kind: 'show-code', title: '到场核销凭证', submit: '关闭', row,
    reservationId: row.reservation.id, reason: '', slot: '', slots: [], error: '', busy: false
  }, event ?? null)
}

function openCredentialUpdate(id: number, event?: Event): void {
  const row = detailRow.value
  if (!row) return
  openDialog({
    kind: 'credential-update', title: activeCode.value ? '更新到场凭证' : '领取到场凭证', submit: '领取最新码', row,
    reservationId: id, reason: '', slot: '', slots: [], error: '', busy: false
  }, event ?? null)
}

async function confirmDialog(): Promise<void> {
  const state = dialog.value
  if (!state || state.busy) return
  state.busy = true
  state.error = ''
  try {
    if (state.kind === 'cancel') {
      await reservationApi.cancel(state.reservationId!, state.reason.trim(), isolated)
    }
    if (state.kind === 'reschedule') {
      const [startTime, endTime] = state.slot.split('|')
      await reservationApi.requestReschedule(state.reservationId!, {
        startTime, endTime, reasonCode: 'USER_REQUEST', reasonText: state.reason.trim()
      }, isolated)
    }
    if (state.kind === 'refund') {
      await orderApi.applyRefund(state.row!.order!.id, state.reason.trim(), isolated)
    }
    if (state.kind === 'credential-update') {
      const response = await checkinApi.reissueCredential(state.reservationId!, isolated)
      activeCode.value = response.checkinCode || ''
    }
    closeDialog()
    if (state.kind === 'cancel' || state.kind === 'refund' || state.kind === 'reschedule') {
      await load()
    }
  } catch (error) {
    state.error = readableError(error, '操作未完成，输入已保留。请重试。')
  } finally {
    state.busy = false
  }
}

onMounted(() => {
  section.value = initialSectionFromRoute()
  parseHash()
  void load()
})

watch(() => route.hash, () => parseHash())
watch(() => route.name, () => {
  if (screen.value === 'list' && ['MyReservations', 'MyOrders', 'MyCheckins'].includes(String(route.name))) {
    section.value = initialSectionFromRoute()
  }
})
</script>
