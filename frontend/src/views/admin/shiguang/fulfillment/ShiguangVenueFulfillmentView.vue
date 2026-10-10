<template>
  <div ref="rootEl" class="sga-fulfillment" data-test="sga-fulfillment-root">
    <header class="heading">
      <div>
        <p class="eyebrow">场馆运营 / 预约与履约</p>
        <h1>让每一场预约，顺利发生</h1>
        <p>从审核到到场，把预约、核验与订单放回同一条处理线上。</p>
      </div>
      <div class="date">
        <strong>{{ todayLabel }}</strong>
      </div>
    </header>
    <nav class="f-tabs" aria-label="履约工作面">
      <button
        v-for="item in TABS"
        :key="item.key"
        :data-test="`tab-${item.key}`"
        type="button"
        :aria-current="tab === item.key"
        @click="switchTab(item.key)"
      >{{ item.label }}</button>
    </nav>

    <div v-if="surface === 'loading'" class="f-state" role="status" data-test="surface-loading">
      <h2>正在读取当前场馆</h2>
      <p>记录还没有返回，暂时不能开始处理。</p>
    </div>
    <div v-else-if="surface === 'error'" class="f-state" role="alert" data-test="surface-error">
      <h2>暂时无法读取记录</h2>
      <p>读取失败不代表没有记录。请重新读取当前范围。</p>
      <button class="btn" type="button" @click="bootstrap">重新读取</button>
    </div>
    <div v-else-if="surface === 'lock'" class="f-state" role="status" data-test="surface-lock">
      <h2>尚未绑定可管理场馆</h2>
      <p>请由系统管理员在用户管理中绑定场馆，再重新进入工作空间。</p>
      <button class="btn" type="button" @click="bootstrap">重新检查</button>
    </div>
    <template v-else>
      <div v-if="tab === 'arrival' && !canCheckin" class="f-state" data-test="denied-arrival">
        <h2>当前没有此项权限</h2>
        <p>到场核验的读取与写入都需要核销权限。可返回其他已授权任务。</p>
      </div>
      <div v-else-if="tab === 'review' && !canAudit" class="f-state" data-test="denied-review">
        <h2>当前没有此项权限</h2>
        <p>预约与改期审核需要审核权限。可返回其他已授权任务。</p>
      </div>
      <div v-else-if="tab === 'orders' && !canOrder" class="f-state" data-test="denied-orders">
        <h2>当前没有此项权限</h2>
        <p>订单与退款处理需要订单管理权限。可返回其他已授权任务。</p>
      </div>
      <template v-else>
        <!-- 到场核验 -->
        <template v-if="tab === 'arrival'">
          <div v-if="!arrivalList.length" class="f-state" data-test="arrival-empty">
            <h2>当前范围没有记录</h2>
            <p>这是一个空范围，可以调整场馆或记录范围。</p>
          </div>
          <div v-else class="f-grid">
            <section class="f-rail">
              <h2>{{ arrivalHistory ? '历史履约记录' : '当前预约与异常' }}</h2>
              <label class="f-inline-label">
                记录范围
                <select v-model="arrivalHistory" data-test="arrival-range">
                  <option :value="false">当前预约与异常</option>
                  <option :value="true">历史已完成</option>
                </select>
              </label>
              <div class="f-inline-search">
                <label>预约号、用户或凭证尾号
                  <input v-model="arrivalQueryDraft" data-test="arrival-query" placeholder="例如 4821" @keydown.enter="applyArrivalQuery">
                </label>
                <button class="btn" data-test="arrival-search" type="button" @click="applyArrivalQuery">查找</button>
                <button v-if="arrivalQuery" class="btn" data-test="arrival-clear" type="button" @click="clearArrivalQuery">清除</button>
              </div>
              <div class="f-list">
                <button
                  v-for="row in arrivalList"
                  :key="row.reservation.id"
                  class="f-pick"
                  type="button"
                  :data-test="`arrival-${row.reservation.id}`"
                  :aria-pressed="selectedId === row.reservation.id"
                  @click="selectedId = row.reservation.id"
                >
                  <span class="f-time">{{ whenRange(row.reservation.startTime, row.reservation.endTime) }}</span>
                  <strong>{{ row.reservation.venueName }} · {{ spaceLabel(row) }}</strong>
                  <small>{{ contactOf(row) }} · {{ arrivalCheckLabel(row) }}</small>
                </button>
              </div>
              <p class="f-note" style="margin-top:20px">核验先确认预约，再确认到场。退款中的预约不能放行。</p>
            </section>
            <article v-if="selectedArrival" class="f-detail" data-test="arrival-detail">
              <div class="f-status-row">
                <span class="badge">{{ reservationStatusLabel(selectedArrival.reservation.status) }}</span>
                <span class="badge" :class="{ warn: !!noShowBadgeOf(selectedArrival) }">{{ noShowBadgeOf(selectedArrival) || arrivalCheckLabel(selectedArrival) }}</span>
              </div>
              <h2>{{ spaceLabel(selectedArrival) }}</h2>
              <p class="lead">{{ whenRange(selectedArrival.reservation.startTime, selectedArrival.reservation.endTime) }}</p>
              <p class="muted">{{ contactOf(selectedArrival) }} · {{ reservationNo(selectedArrival) }}</p>
              <dl class="f-facts">
                <div><dt>场馆 / 场地</dt><dd>{{ selectedArrival.reservation.venueName }} · {{ spaceLabel(selectedArrival) }}</dd></div>
                <div><dt>凭证</dt><dd>{{ credentialText(selectedArrival) }}</dd></div>
                <div><dt>实付金额</dt><dd>¥{{ money(selectedArrival.order?.paidAmount) }}</dd></div>
                <div><dt>核验窗口</dt><dd>{{ verifyWindowLabel(selectedArrival) }}</dd></div>
              </dl>
              <div v-if="canManual(selectedArrival)" class="f-actions">
                <button class="btn primary" data-test="verify" type="button" :disabled="!canVerify(selectedArrival)" @click="openVerify">核验到场凭证</button>
                <button class="btn" data-test="manual-pass" type="button" @click="openManualPass">人工放行</button>
                <button v-if="selectedArrival.lifecycle?.noShowStatus === 'CANDIDATE'" class="btn" data-test="no-show" type="button" @click="openNoShowConfirm">确认爽约</button>
              </div>
              <p v-else-if="selectedArrival.lifecycle?.noShowStatus === 'CONFIRMED'" class="f-actions">
                <button class="btn" data-test="revoke-no-show" type="button" @click="openNoShowRevoke">撤销爽约认定</button>
              </p>
              <p v-else class="f-note">{{ arrivalBlockedNote(selectedArrival) }}</p>
              <p v-if="selectedArrival.lifecycle?.noShowStatus === 'CANDIDATE' && canManual(selectedArrival)" class="f-note">窗口已关闭。请核对现场记录，选择人工到场或确认爽约。</p>
              <div class="f-actions">
                <button class="btn" data-test="lifecycle" type="button" @click="openLifecycle">查看履约记录</button>
                <button v-if="canOrder && selectedArrival.order" class="btn" data-test="related-order" type="button" @click="openRelatedOrder">关联订单</button>
              </div>
              <ol class="f-events">
                <li>
                  <strong>{{ arrivalCheckLabel(selectedArrival) }}</strong>
                  <small>{{ selectedArrival.lifecycle?.reasonText || '支付、凭证和到场状态分开保留。' }}</small>
                </li>
                <li>
                  <strong>付款 ¥{{ money(selectedArrival.order?.paidAmount) }}</strong>
                  <small>{{ selectedArrival.order ? selectedArrival.order.orderNo : '尚无订单' }}</small>
                </li>
              </ol>
            </article>
          </div>
        </template>

        <!-- 预约与改期 -->
        <template v-else-if="tab === 'review'">
          <div v-if="!reviewList.length" class="f-state" data-test="review-empty">
            <h2>当前范围没有记录</h2>
            <p>这是一个空范围，可以调整场馆或日期。</p>
          </div>
          <div v-else class="f-grid">
            <section class="f-rail">
              <h2>预约处理</h2>
              <div class="f-list">
                <button
                  v-for="row in reviewList"
                  :key="row.reservation.id"
                  class="f-pick"
                  type="button"
                  :data-test="`review-${row.reservation.id}`"
                  :aria-pressed="selectedReviewId === row.reservation.id"
                  @click="selectedReviewId = row.reservation.id"
                >
                  <span class="badge">{{ row.reschedule ? '改期待审核' : reservationStatusLabel(row.reservation.status) }}</span>
                  <strong>{{ spaceLabel(row) }}</strong>
                  <small>{{ monthDay(row.reservation.startTime) }} {{ timeSpan(row.reservation.startTime, row.reservation.endTime) }} · {{ contactOf(row) }}</small>
                </button>
              </div>
            </section>
            <article v-if="selectedReview" class="f-detail" data-test="review-detail">
              <p class="eyebrow">{{ reservationNo(selectedReview) }}</p>
              <h2>{{ selectedReview.reschedule ? '改期申请' : '预约审核与管理' }}</h2>
              <p>{{ spaceLabel(selectedReview) }} · {{ contactOf(selectedReview) }}</p>
              <template v-if="selectedReview.reschedule">
                <div class="f-compare">
                  <div>
                    <span class="muted">当前有效时段</span>
                    <strong>{{ monthDay(selectedReview.reschedule.fromStartTime) }}</strong>
                    {{ timeSpan(selectedReview.reschedule.fromStartTime, selectedReview.reschedule.fromEndTime) }}
                    <p class="muted">应付 ¥{{ money(selectedReview.order?.payableAmount ?? selectedReview.reservation.payableAmount) }}</p>
                  </div>
                  <div>
                    <span class="muted">申请时段</span>
                    <strong>{{ monthDay(selectedReview.reschedule.toStartTime) }}</strong>
                    {{ timeSpan(selectedReview.reschedule.toStartTime, selectedReview.reschedule.toEndTime) }}
                    <p class="muted">待审核时不改原预约</p>
                  </div>
                </div>
                <p class="f-note">批准前重新检查目标时段库存及报价。</p>
                <p v-if="selectedReview.reschedule.reasonText">申请说明：{{ selectedReview.reschedule.reasonText }}</p>
                <div v-if="selectedReview.reschedule.status === 'PENDING_REVIEW'" class="f-actions">
                  <button class="btn primary" data-test="reschedule-approve" type="button" @click="openRescheduleReview(true)">复核并批准改期</button>
                  <button class="btn" data-test="reschedule-reject" type="button" @click="openRescheduleReview(false)">拒绝改期</button>
                </div>
              </template>
              <template v-else>
                <dl class="f-facts">
                  <div><dt>预约时间</dt><dd>{{ monthDay(selectedReview.reservation.startTime) }} {{ timeSpan(selectedReview.reservation.startTime, selectedReview.reservation.endTime) }}</dd></div>
                  <div><dt>当前状态</dt><dd>{{ reservationStatusLabel(selectedReview.reservation.status) }}</dd></div>
                  <div><dt>资源</dt><dd>{{ selectedReview.reservation.venueName }} · {{ spaceLabel(selectedReview) }}</dd></div>
                  <div><dt>订单</dt><dd>{{ selectedReview.order ? selectedReview.order.orderNo : '尚未生成，审核时复核报价' }}</dd></div>
                </dl>
                <div v-if="selectedReview.reservation.status === 'PENDING_REVIEW'" class="f-actions">
                  <button class="btn primary" data-test="approve" type="button" @click="openDecision('approve')">批准预约</button>
                  <button class="btn" data-test="reject" type="button" @click="openDecision('reject')">拒绝预约</button>
                </div>
              </template>
              <div v-if="['PENDING_REVIEW', 'PENDING_PAYMENT', 'PAID'].includes(selectedReview.reservation.status)" class="f-actions">
                <button class="btn" data-test="cancel-reservation" type="button" @click="openDecision('cancel')">取消预约</button>
              </div>
              <p v-else class="f-note">本条处理已结束，保留结果供查阅。</p>
            </article>
          </div>
        </template>

        <!-- 订单与退款 -->
        <template v-else-if="tab === 'orders'">
          <div v-if="!orderList.length" class="f-state" data-test="orders-empty">
            <h2>当前范围没有记录</h2>
            <p>这是一个空范围，可以调整场馆。</p>
          </div>
          <template v-else>
            <div class="f-inline-search">
              <label>选择场馆订单
                <select v-model="selectedOrderId" data-test="order-select">
                  <option v-for="row in orderList" :key="row.order!.id" :value="row.order!.id">
                    {{ row.order!.orderNo }} · {{ spaceLabel(row) }}
                  </option>
                </select>
              </label>
            </div>
            <article v-if="selectedOrder" class="f-ledger" data-test="order-ledger">
              <header class="bill-head">
                <div>
                  <p class="eyebrow">{{ selectedOrder.order!.orderNo }}</p>
                  <h2>{{ spaceLabel(selectedOrder) }}</h2>
                  <span class="badge">{{ orderStatusLabel(orderDisplayStatus(selectedOrder)) }}</span>
                </div>
                <div class="f-money"><small>实付</small> ¥{{ money(selectedOrder.order!.paidAmount) }}</div>
              </header>
              <dl class="f-facts">
                <div><dt>关联预约</dt><dd>{{ reservationNo(selectedOrder) }}</dd></div>
                <div><dt>预约人</dt><dd>{{ contactOf(selectedOrder) }}</dd></div>
                <div><dt>使用时段</dt><dd>{{ monthDay(selectedOrder.reservation.startTime) }} {{ timeSpan(selectedOrder.reservation.startTime, selectedOrder.reservation.endTime) }}</dd></div>
                <div><dt>支付方式</dt><dd>{{ paySourceLabel(selectedOrder.order) }}</dd></div>
              </dl>
              <div class="f-lines">
                <div><span>原价</span><span>¥{{ money(selectedOrder.order!.originalAmount ?? selectedOrder.order!.totalAmount) }}</span></div>
                <div><span>优惠</span><span>− ¥{{ money(discountOf(selectedOrder.order)) }}</span></div>
                <div><strong>应付</strong><strong>¥{{ money(selectedOrder.order!.payableAmount ?? selectedOrder.order!.totalAmount) }}</strong></div>
              </div>
              <section v-if="selectedOrder.refund" data-test="refund-section">
                <div class="f-status-row">
                  <h2 style="margin:0">退款申请</h2>
                  <span class="badge">{{ refundStatusLabel(selectedOrder.refund.status) }}</span>
                </div>
                <p class="muted">{{ selectedOrder.refund.refundNo }} · {{ reservationNo(selectedOrder) }}</p>
                <div class="f-lines">
                  <div><span>申请退款</span><span>¥{{ money(selectedOrder.refund.refundAmount) }}</span></div>
                  <div><span>手续费 / 净退款</span><span>¥0.00 / ¥{{ money(selectedOrder.refund.refundAmount) }}</span></div>
                </div>
                <p v-if="selectedOrder.refund.refundReason">申请说明：{{ selectedOrder.refund.refundReason }}</p>
                <p class="f-note">{{ refundNote(selectedOrder.refund) }}</p>
                <div v-if="selectedOrder.refund.status === 'PENDING_REVIEW'" class="f-actions">
                  <button class="btn primary" data-test="refund-approve" type="button" @click="openRefundApprove">同意退款</button>
                  <button class="btn" data-test="refund-reject" type="button" @click="openRefundReject">拒绝退款</button>
                </div>
                <div v-else-if="selectedOrder.refund.status === 'REFUNDING'" class="f-actions">
                  <button class="btn" data-test="refund-success" type="button" @click="openRefundSuccess">核对并确认退款到账</button>
                </div>
                <p v-if="selectedOrder.refund.auditReason">审核说明：{{ selectedOrder.refund.auditReason }}</p>
              </section>
              <p v-else class="f-note">本订单没有待审核退款。已完成预约不能发起新的退款。</p>
            </article>
          </template>
        </template>

        <!-- 当日工作 -->
        <template v-else>
          <div class="f-workday" data-test="day-tab">
            <section>
              <p class="eyebrow">当班先处理</p>
              <button v-if="canAudit" class="f-duty" type="button" data-test="duty-review" @click="switchTab('review')">
                <strong>预约审核与改期 →</strong>
                <small>先核对库存与时段，再明确告知处理结果</small>
              </button>
              <p v-else class="f-permission">预约审核与改期：当前没有处理权限。</p>
              <button v-if="canCheckin" class="f-duty" type="button" data-test="duty-arrival" @click="switchTab('arrival')">
                <strong>临近到场核验 →</strong>
                <small>核销窗口、凭证与爽约候选分开判断</small>
              </button>
              <p v-else class="f-permission">到场核验：当前没有查看与处理权限。</p>
              <a class="f-duty" data-test="duty-site-ops" href="/venue-admin/site-ops">
                <strong>现场班次与未尽事项 →</strong>
                <small>巡检、任务、异常与交接班</small>
              </a>
              <div class="f-actions">
                <a class="btn" href="/venue-admin/calendar">营业日历</a>
                <a class="btn" href="/venue-admin/notices">场馆公告</a>
                <button v-if="canOrder" class="btn" type="button" data-test="duty-orders" @click="switchTab('orders')">订单与退款</button>
              </div>
            </section>
            <aside class="f-summary">
              <h2>当日经营参照</h2>
              <p class="muted">{{ venueName }} · {{ todayLabel }}</p>
              <dl>
                <div><dt>今日预约</dt><dd>{{ dashboard?.todayReservations ?? '—' }}<small v-if="dashboard != null"> 单</small></dd></div>
                <div><dt>待审核</dt><dd>{{ dashboard?.pendingReviews ?? '—' }}<small v-if="dashboard != null"> 项</small></dd></div>
                <div><dt>今日收入</dt><dd>¥{{ money(dashboard?.revenue) }}</dd></div>
                <div><dt>占用率</dt><dd>{{ dashboard ? Number(dashboard.occupancyRate).toFixed(1) : '—' }}<small v-if="dashboard != null">%</small></dd></div>
              </dl>
              <p class="f-note">收入用于当日经营查看；结算、退款与资金核对请进入对应账务范围。</p>
            </aside>
          </div>
        </template>
      </template>
    </template>

    <dialog ref="dialogEl" class="f-modal" aria-labelledby="f-modal-title" @close="onDialogClose">
      <form v-if="dialog" @submit.prevent="confirmDialog">
        <h2 id="f-modal-title">{{ dialog.title }}</h2>
        <!-- 核验凭证 -->
        <template v-if="dialog.kind === 'verify'">
          <p>{{ selectedArrival ? `${spaceLabel(selectedArrival)} · ${contactOf(selectedArrival)}` : '' }}</p>
          <p>请从预约人的到场凭证核对完整短码。</p>
          <label>六位短码<input v-model="form.code" data-test="verify-code" inputmode="numeric" maxlength="6" required autocomplete="one-time-code"></label>
        </template>
        <!-- 人工放行 / 爽约确认 / 撤销爽约 / 审核 / 改期审核 / 取消 / 退款审核 -->
        <template v-else-if="dialog.kind === 'manual-pass'">
          <p>{{ selectedArrival ? `${spaceLabel(selectedArrival)} · ${contactOf(selectedArrival)}` : '' }}</p>
          <label>原因类别
            <select v-model="form.reasonCode" data-test="manual-reason-code">
              <option value="DEVICE_ISSUE">现场设备问题</option>
              <option value="VERIFIED_ON_SITE">现场身份与预约已核对</option>
            </select>
          </label>
        </template>
        <template v-else-if="dialog.kind === 'no-show-confirm'">
          <p>请确认现场未发现到场记录。候选标记本身不等于已经认定爽约。</p>
        </template>
        <template v-else-if="dialog.kind === 'no-show-revoke'">
          <p>请补充实际到场依据。</p>
          <p class="f-note">原因码为 MANUAL_PRESENT；已完成预约只能按人工到场撤销。</p>
        </template>
        <template v-else-if="dialog.kind === 'lifecycle'">
          <p>{{ dialog.targetText }}</p>
          <ol class="f-events">
            <li v-for="(event, index) in lifecycleEvents" :key="index">
              <strong>{{ event.title }}</strong>
              <small>{{ event.detail }}</small>
            </li>
          </ol>
        </template>
        <template v-else-if="dialog.kind === 'approve'">
          <p>本次将复核资源与报价，通过后进入待支付。</p>
        </template>
        <template v-else-if="dialog.kind === 'reject'">
          <p>请写明具体原因，避免预约人无法理解如何调整。</p>
        </template>
        <template v-else-if="dialog.kind === 'reschedule-review'">
          <div class="f-compare">
            <div>
              <span class="muted">当前</span>
              <strong>{{ monthDay(dialogReschedule?.fromStartTime) }}<br>{{ timeSpan(dialogReschedule?.fromStartTime, dialogReschedule?.fromEndTime) }}</strong>
              应付 ¥{{ money(selectedReview?.order?.payableAmount ?? selectedReview?.reservation.payableAmount) }}
            </div>
            <div>
              <span class="muted">申请</span>
              <strong>{{ monthDay(dialogReschedule?.toStartTime) }}<br>{{ timeSpan(dialogReschedule?.toStartTime, dialogReschedule?.toEndTime) }}</strong>
              复核应付 ¥{{ money(selectedReview?.order?.payableAmount ?? selectedReview?.reservation.payableAmount) }}
            </div>
          </div>
        </template>
        <template v-else-if="dialog.kind === 'cancel'">
          <p>{{ selectedReview ? `${spaceLabel(selectedReview)} · ${monthDay(selectedReview.reservation.startTime)} ${timeSpan(selectedReview.reservation.startTime, selectedReview.reservation.endTime)}` : '' }}</p>
          <p>{{ cancelNote }}</p>
        </template>
        <template v-else-if="dialog.kind === 'refund-approve'">
          <p>{{ dialog.targetText }} · ¥{{ money(selectedOrder?.refund?.refundAmount) }}</p>
          <p>审核通过后进入退款中，等待后续退款结果。此处不确认资金到账。</p>
        </template>
        <template v-else-if="dialog.kind === 'refund-reject'">
          <p>此订单为用户主动申请退款。拒绝后预约与订单恢复已支付。</p>
        </template>
        <template v-else-if="dialog.kind === 'refund-success'">
          <p>{{ dialog.targetText }} · ¥{{ money(selectedOrder?.refund?.refundAmount) }}</p>
          <p>请独立核对退款结果。审核通过只是前一步，不能作为到账凭据。</p>
          <label class="check">
            <input v-model="form.checked" data-test="refund-checked" type="checkbox">
            <span>已核对退款结果、订单与净退款金额一致</span>
          </label>
          <p class="f-note">确认后普通退款为成功，预约与订单为已退款，凭证失效。</p>
        </template>
        <label v-if="dialog.kind !== 'lifecycle'">处理说明
          <textarea v-model="form.reason" data-test="dialog-reason" rows="3" maxlength="500" placeholder="请填写判断依据与处理说明" required></textarea>
        </label>
        <p v-if="dialog.error" class="error-text" role="alert" data-test="dialog-error">{{ dialog.error }}</p>
        <div class="row modal-actions">
          <button class="btn" type="button" data-test="dialog-cancel" @click="closeDialog">{{ dialog.kind === 'lifecycle' ? '关闭详情' : '取消' }}</button>
          <button v-if="dialog.kind !== 'lifecycle'" class="btn primary" type="submit" :disabled="dialog.busy" :data-test="`confirm-${dialog.kind}`">
            {{ dialog.busy ? '处理中…' : dialog.submit }}
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useVenueAccessStore } from '@/stores/venueAccess'
import { useAppStore } from '@/stores/app'
import { adminCheckinApi } from '@/api/checkin'
import { adminOrderApi } from '@/api/order'
import { adminReservationApi } from '@/api/reservation'
import { venueApi } from '@/api/venue'
import { statisticsApi, type DashboardSummary } from '@/api/statistics'
import type { LifecycleEventResponse, RefundRecord, Reservation, ReservationRescheduleRequest, ReservationLifecycleResponse, Order } from '@/api/types'
import {
  attendanceStatusLabel,
  ISOLATED_REQUEST as isolated,
  money,
  noShowBadge,
  orderStatusLabel,
  refundStatusLabel,
  reservationStatusLabel
} from '../adminShared'
import {
  arrivalBlockedNote,
  arrivalCheckLabel,
  arrivalRows,
  buildRows,
  canManual,
  canVerify,
  credentialText,
  discountOf,
  initialTabFromRoute,
  matchArrivalQuery,
  monthDay,
  orderDisplayStatus,
  paySourceLabel,
  readableError,
  refundNote,
  reservationNo,
  reviewRows,
  sortArrival,
  timeSpan,
  verifyWindowLabel,
  whenRange,
  type FulfillmentRow
} from './fulfillmentShared'

type SurfaceState = 'loading' | 'ready' | 'error' | 'lock'
type DialogKind =
  | 'verify' | 'manual-pass' | 'no-show-confirm' | 'no-show-revoke' | 'lifecycle'
  | 'approve' | 'reject' | 'reschedule-review' | 'cancel'
  | 'refund-approve' | 'refund-reject' | 'refund-success'

const EVENT_TYPE_LABELS: Record<string, string> = {
  CHECKIN_VERIFIED: '到场核验完成',
  CHECKIN_EXCEPTION_RECORDED: '人工到场已记录',
  NO_SHOW_CANDIDATE_MARKED: '爽约候选已标记',
  NO_SHOW_CONFIRMED: '已确认爽约',
  NO_SHOW_REVOKED: '爽约已撤销',
  CREDENTIAL_ISSUED: '核销凭证已签发',
  CREDENTIAL_INVALIDATED: '核销凭证已失效',
  CREDENTIAL_REISSUED: '核销凭证已更新'
}

const TABS: Array<{ key: 'arrival' | 'review' | 'orders' | 'day'; label: string }> = [
  { key: 'arrival', label: '到场核验' },
  { key: 'review', label: '预约与改期' },
  { key: 'orders', label: '订单与退款' },
  { key: 'day', label: '当日工作' }
]

const route = useRoute()
const auth = useAuthStore()
const venueAccess = useVenueAccessStore()
const app = useAppStore()
const rootEl = ref<HTMLElement | null>(null)

const canCheckin = computed(() => auth.hasPermission('reservation:checkin:manage'))
const canAudit = computed(() => auth.hasPermission('reservation:audit'))
const canOrder = computed(() => auth.hasPermission('order:admin'))

const tab = ref<'arrival' | 'review' | 'orders' | 'day'>(initialTabFromRoute(route?.name))
const surface = ref<SurfaceState>('loading')
const rows = ref<FulfillmentRow[]>([])
const dashboard = ref<DashboardSummary | null>(null)
const selectedId = ref<number | null>(null)
const selectedReviewId = ref<number | null>(null)
const selectedOrderId = ref<number | null>(null)
const arrivalHistory = ref(false)
const arrivalQueryDraft = ref('')
const arrivalQuery = ref('')
const lifecycleEvents = ref<Array<{ id: number | null; createdAt: string | null; title: string; detail: string }>>([])
// 场地名映射：admin 预约列表只带 spaceId；场地名经公开场地接口补齐（B2-T6 同源）
const spaceNames = ref(new Map<number, string>())

const activeVenue = computed(() => venueAccess.venues.find((venue) => venue.id === venueAccess.activeVenueId) ?? null)
const venueName = computed(() => activeVenue.value?.name ?? '')

const pad2 = (value: number): string => String(value).padStart(2, '0')
const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const now = new Date()
const todayLabel = computed(() => {
  const value = now
  return `${pad2(value.getMonth() + 1)}月${pad2(value.getDate())}日 · ${WEEKDAYS[value.getDay()]}`
})

const arrivalList = computed(() =>
  sortArrival(arrivalRows(rows.value, arrivalHistory.value).filter((row) => matchArrivalQuery(row, arrivalQuery.value)))
)
const reviewList = computed(() => reviewRows(rows.value))
const orderList = computed(() => {
  const list = rows.value.filter((row) => row.order != null)
  return list
})
const selectedArrival = computed(() => arrivalList.value.find((row) => row.reservation.id === selectedId.value) ?? null)
const selectedReview = computed(() => reviewList.value.find((row) => row.reservation.id === selectedReviewId.value) ?? null)
const selectedOrder = computed(() => orderList.value.find((row) => row.order?.id === selectedOrderId.value) ?? null)
const dialogReschedule = computed<ReservationRescheduleRequest | null>(() => selectedReview.value?.reschedule ?? null)
const cancelNote = computed(() => {
  const row = selectedReview.value
  if (!row) return ''
  const paid = Number(row.order?.paidAmount ?? 0)
  return paid > 0
    ? `当前预约已支付且处于免费取消窗口，取消后预约为已取消、订单进入退款中；退款金额 ¥${money(paid)}，并不代表到账。`
    : '本次取消尚未支付的预约；已有待支付订单会关闭。'
})

onMounted(() => {
  void bootstrap()
})

async function bootstrap(): Promise<void> {
  surface.value = 'loading'
  if (!venueAccess.venues.length) {
    try {
      await venueAccess.refresh()
    } catch {
      // 壳层锁定态兜底
    }
  }
  if (!venueAccess.activeVenueId) {
    surface.value = venueAccess.venues.length ? 'error' : 'lock'
    return
  }
  try {
    const venueId = venueAccess.activeVenueId
    const pageParams = { pageNum: 1, pageSize: 100 }
    const [reservations, checkins, orders, refunds, reschedules, summary] = await Promise.all([
      adminReservationApi.list({ venueId, ...pageParams }, isolated),
      canCheckin.value
        ? adminCheckinApi.list({ venueId, ...pageParams }, isolated)
        : Promise.resolve({ records: [] as ReservationLifecycleResponse[], total: 0, pageNum: 1, pageSize: 100 }),
      canOrder.value
        ? adminOrderApi.list({ venueId, ...pageParams }, isolated)
        : Promise.resolve({ records: [] as Order[], total: 0, pageNum: 1, pageSize: 100 }),
      canOrder.value
        ? adminOrderApi.refunds({ venueId, ...pageParams }, isolated)
        : Promise.resolve({ records: [] as RefundRecord[], total: 0, pageNum: 1, pageSize: 100 }),
      canAudit.value
        ? adminReservationApi.rescheduleRequests({ ...pageParams }, isolated)
        : Promise.resolve({ records: [] as ReservationRescheduleRequest[], total: 0, pageNum: 1, pageSize: 100 }),
      statisticsApi.dashboard({ venueId }, isolated)
    ])
    rows.value = buildRows(reservations.records, orders.records, checkins.records, reschedules.records, refunds.records)
    dashboard.value = summary
    try {
      const venueSpaces = await venueApi.spaces(venueId, isolated)
      spaceNames.value = new Map((venueSpaces ?? []).map((space) => [space.id, space.name]))
    } catch {
      // 场地名缺失时回退"场地 #ID"
    }
    if (!selectedId.value) selectedId.value = arrivalList.value[0]?.reservation.id ?? null
    if (!selectedReviewId.value) selectedReviewId.value = reviewList.value[0]?.reservation.id ?? null
    if (!selectedOrderId.value) selectedOrderId.value = orderList.value[0]?.order?.id ?? null
    surface.value = 'ready'
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    surface.value = status === 403 ? 'lock' : 'error'
  }
}

function switchTab(next: 'arrival' | 'review' | 'orders' | 'day'): void {
  tab.value = next
  void nextTick(() => {
    ;(rootEl.value?.querySelector(`[data-test="tab-${next}"]`) as HTMLElement | null)?.focus()
  })
}

function spaceLabel(row: FulfillmentRow): string {
  return spaceNames.value.get(row.reservation.spaceId) || `场地 #${row.reservation.spaceId}`
}

function contactOf(row: FulfillmentRow): string {
  return row.reservation.contactName || `用户 #${row.reservation.userId}`
}

function noShowBadgeOf(row: FulfillmentRow): string {
  return noShowBadge(row.lifecycle?.checkinStatus, row.lifecycle?.noShowStatus)
}

function applyArrivalQuery(): void {
  arrivalQuery.value = arrivalQueryDraft.value.trim()
}

function clearArrivalQuery(): void {
  arrivalQueryDraft.value = ''
  arrivalQuery.value = ''
}

function openRelatedOrder(): void {
  const orderId = selectedArrival.value?.order?.id
  if (orderId != null) {
    selectedOrderId.value = orderId
    switchTab('orders')
  }
}

// ---------- 弹层 ----------

interface DialogState {
  kind: DialogKind
  title: string
  submit: string
  error: string
  busy: boolean
  targetText: string
  rescheduleApprove: boolean
}

const dialogEl = ref<HTMLDialogElement | null>(null)
const dialog = ref<DialogState | null>(null)
let lastTrigger: HTMLElement | null = null
const form = reactive({ code: '', reason: '', reasonCode: 'VERIFIED_ON_SITE', checked: false })

function openDialog(next: DialogState, trigger: Event | null): void {
  const target = trigger?.target
  lastTrigger = target instanceof HTMLElement ? target : (document.activeElement as HTMLElement | null)
  dialog.value = next
  form.code = ''
  form.reason = ''
  form.checked = false
  if (next.kind === 'manual-pass') form.reasonCode = 'VERIFIED_ON_SITE'
  void nextTick(() => dialogEl.value?.showModal())
}

function closeDialog(): void {
  dialogEl.value?.close()
}

function onDialogClose(): void {
  const trigger = lastTrigger
  dialog.value = null
  lastTrigger = null
  if (trigger?.isConnected) trigger.focus()
}

function openVerify(event?: Event): void {
  openDialog({ kind: 'verify', title: '核验到场凭证', submit: '确认核验', error: '', busy: false, targetText: '', rescheduleApprove: false }, event ?? null)
}

function openManualPass(event?: Event): void {
  openDialog({ kind: 'manual-pass', title: '人工放行', submit: '记录人工到场', error: '', busy: false, targetText: '', rescheduleApprove: false }, event ?? null)
}

function openNoShowConfirm(event?: Event): void {
  openDialog({ kind: 'no-show-confirm', title: '确认爽约候选', submit: '确认爽约', error: '', busy: false, targetText: '', rescheduleApprove: false }, event ?? null)
}

function openNoShowRevoke(event?: Event): void {
  openDialog({ kind: 'no-show-revoke', title: '撤销爽约认定', submit: '撤销并记录到场', error: '', busy: false, targetText: '', rescheduleApprove: false }, event ?? null)
}

function openDecision(kind: 'approve' | 'reject' | 'cancel', event?: Event): void {
  const titles = { approve: '批准预约', reject: '拒绝预约', cancel: '取消预约' }
  const submits = { approve: '批准预约', reject: '拒绝预约', cancel: '确认取消' }
  openDialog({ kind, title: titles[kind], submit: submits[kind], error: '', busy: false, targetText: '', rescheduleApprove: false }, event ?? null)
}

function openRescheduleReview(approve: boolean, event?: Event): void {
  openDialog({
    kind: 'reschedule-review',
    title: approve ? '复核改期并批准' : '拒绝改期',
    submit: approve ? '复核并批准' : '拒绝改期',
    error: '',
    busy: false,
    targetText: '',
    rescheduleApprove: approve
  }, event ?? null)
}

function openRefundApprove(event?: Event): void {
  openDialog({ kind: 'refund-approve', title: '同意退款', submit: '同意退款', error: '', busy: false, targetText: selectedOrder.value?.refund?.refundNo ?? '退款申请', rescheduleApprove: false }, event ?? null)
}

function openRefundReject(event?: Event): void {
  openDialog({ kind: 'refund-reject', title: '拒绝退款', submit: '拒绝退款', error: '', busy: false, targetText: '', rescheduleApprove: false }, event ?? null)
}

function openRefundSuccess(event?: Event): void {
  openDialog({ kind: 'refund-success', title: '核对退款到账', submit: '确认退款到账', error: '', busy: false, targetText: selectedOrder.value?.refund?.refundNo ?? '退款申请', rescheduleApprove: false }, event ?? null)
}

async function openLifecycle(event?: Event): Promise<void> {
  const row = selectedArrival.value
  if (!row) return
  openDialog({
    kind: 'lifecycle',
    title: '履约记录',
    submit: '',
    error: '',
    busy: false,
    targetText: `${reservationNo(row)} · ${contactOf(row)}`,
    rescheduleApprove: false
  }, event ?? null)
  try {
    const lifecycle: ReservationLifecycleResponse = await adminCheckinApi.lifecycle(row.reservation.id, isolated)
    lifecycleEvents.value = (lifecycle.events ?? []).map((event: LifecycleEventResponse) => ({
      id: null,
      createdAt: event.createTime ?? null,
      title: EVENT_TYPE_LABELS[event.eventType] ?? event.eventType,
      detail: event.reasonText || `${event.sourceType === 'ADMIN' ? '管理员操作' : event.sourceType === 'SYSTEM' ? '系统记录' : '人工触发'}${event.fromStatus ? ` · ${event.fromStatus} → ${event.toStatus ?? ''}` : ''}`
    }))
    if (!lifecycleEvents.value.length) {
      lifecycleEvents.value = [
        { id: null, createdAt: null, title: `预约状态：${reservationStatusLabel(row.reservation.status)}`, detail: `${monthDay(row.reservation.startTime)} ${timeSpan(row.reservation.startTime, row.reservation.endTime)}` },
        { id: null, createdAt: null, title: `到场状态：${attendanceStatusLabel(lifecycle.attendanceStatus)}`, detail: lifecycle.reasonText || '' },
        { id: null, createdAt: null, title: `凭证：${lifecycle.checkinCodeLast4 ? `尾号 ${lifecycle.checkinCodeLast4}` : '本条不展示凭证'}`, detail: '独立保留核销、到场与爽约结果' }
      ]
    }
  } catch {
    lifecycleEvents.value = [{ id: null, createdAt: null, title: '履约记录暂时无法读取', detail: '请稍后重新打开。' }]
  }
}

// ---------- 提交 ----------

async function confirmDialog(): Promise<void> {
  const state = dialog.value
  if (!state || state.busy) return
  state.error = ''
  if (state.kind !== 'lifecycle' && !form.reason.trim() && state.kind !== 'verify') {
    state.error = '请填写处理说明。'
    return
  }
  state.busy = true
  try {
    await submitOf(state)
  } catch (error) {
    state.busy = false
    state.error = readableError(error, '这次提交没有成功，内容已保留。请重试。')
    return
  }
  state.busy = false
  closeDialog()
  await bootstrap()
}

async function submitOf(state: DialogState): Promise<void> {
  switch (state.kind) {
    case 'verify': {
      if (!selectedArrival.value) return
      if (!/^\d{6}$/.test(form.code.trim())) throw new Error('请输入六位数字短码。')
      await adminCheckinApi.verify({ credential: form.code.trim() }, isolated)
      app.showToast('到场已记录，不能重复核销')
      return
    }
    case 'manual-pass': {
      if (!selectedArrival.value) return
      await adminCheckinApi.manualPass(selectedArrival.value.reservation.id, {
        reasonCode: form.reasonCode,
        reasonText: form.reason.trim()
      }, isolated)
      app.showToast('人工到场已记录')
      return
    }
    case 'no-show-confirm': {
      if (!selectedArrival.value) return
      await adminCheckinApi.confirmNoShow(selectedArrival.value.reservation.id, {
        reasonCode: 'NO_SHOW_CONFIRMED',
        reasonText: form.reason.trim()
      }, isolated)
      app.showToast('爽约已确认')
      return
    }
    case 'no-show-revoke': {
      if (!selectedArrival.value) return
      await adminCheckinApi.revokeNoShow(selectedArrival.value.reservation.id, {
        reasonCode: 'MANUAL_PRESENT',
        reasonText: form.reason.trim()
      }, isolated)
      app.showToast('爽约认定已撤销')
      return
    }
    case 'approve': {
      if (!selectedReview.value) return
      await adminReservationApi.approve(selectedReview.value.reservation.id, form.reason.trim(), isolated)
      app.showToast('预约已批准，进入待支付')
      return
    }
    case 'reject': {
      if (!selectedReview.value) return
      await adminReservationApi.reject(selectedReview.value.reservation.id, form.reason.trim(), isolated)
      app.showToast('预约已拒绝')
      return
    }
    case 'reschedule-review': {
      const request = dialogReschedule.value
      if (!request) return
      await adminReservationApi.reviewReschedule(request.id, state.rescheduleApprove, form.reason.trim(), isolated)
      app.showToast(state.rescheduleApprove ? '改期已批准' : '改期已拒绝，原预约保持不变')
      return
    }
    case 'cancel': {
      if (!selectedReview.value) return
      await adminReservationApi.cancel(selectedReview.value.reservation.id, form.reason.trim(), isolated)
      app.showToast('预约已取消')
      return
    }
    case 'refund-approve': {
      const refund = selectedOrder.value?.refund
      if (!refund) return
      await adminOrderApi.approveRefund(refund.id, form.reason.trim(), isolated)
      app.showToast('退款审核已通过，等待退款结果')
      return
    }
    case 'refund-reject': {
      const refund = selectedOrder.value?.refund
      if (!refund) return
      await adminOrderApi.rejectRefund(refund.id, form.reason.trim(), isolated)
      app.showToast('退款已拒绝，预约与订单恢复已支付')
      return
    }
    case 'refund-success': {
      const refund = selectedOrder.value?.refund
      if (!refund) return
      if (!form.checked) throw new Error('请先完成退款结果与金额的独立核对。')
      await adminOrderApi.markRefundSuccess(refund.id, isolated)
      app.showToast('退款到账已确认')
      return
    }
    case 'lifecycle':
      return
  }
}
</script>
