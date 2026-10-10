<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, TopRight } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { couponApi } from '@/api/promotion'
import type { CouponTemplateResponse, UserCouponResponse } from '@/api/types'
import ShiguangMemberShell, { type DomainState } from './ShiguangMemberShell.vue'
import MemberCouponDialog from './MemberCouponDialog.vue'
import MemberDialog from './MemberDialog.vue'
import MemberIcon from './MemberIcon.vue'
import {
  couponExpiringSoonDays,
  couponFaceValue,
  couponMatchesFilter,
  couponRuleText,
  couponStatusText,
  couponStatusTone,
  formatMemberDate,
  formatMemberMoney,
  isCouponUsable,
  type CouponFolderFilter
} from './memberShared'

/**
 * 优惠券夹（/me/coupons，SHIGUANG-MEMBER-REFACTOR-20260912 S04）。
 * 我的券夹（共享壳全量数据 + G2 口径筛选）与可领取（真实模板）双模式；
 * ?mode=claimable&filter= 深链刷新可恢复；领取为真实命令，
 * 409「已达到每人限领数量」映射为「已领取」终态并刷新券夹（G3 语义）。
 */
type CouponMode = 'mine' | 'claimable'

const route = useRoute()
const router = useRouter()
const isolated = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

const mode = computed<CouponMode>(() => (String(route.query.mode ?? '') === 'claimable' ? 'claimable' : 'mine'))
const filter = computed<CouponFolderFilter>(() => {
  const value = String(route.query.filter ?? '')
  return (['available', 'locked', 'used', 'inactive', 'all'] as CouponFolderFilter[]).includes(value as CouponFolderFilter)
    ? (value as CouponFolderFilter)
    : 'available'
})

const pushQuery = (updates: Record<string, string | undefined>) => {
  const merged: Record<string, string | undefined> = {
    mode: route.query.mode as string | undefined,
    filter: route.query.filter as string | undefined,
    ...updates
  }
  const query: Record<string, string> = {}
  for (const [key, value] of Object.entries(merged)) {
    if (!value) continue
    if (key === 'mode' && value === 'mine') continue
    if (key === 'filter' && value === 'available') continue
    query[key] = value
  }
  router.push({ path: '/me/coupons', query })
}

const setMode = (next: CouponMode) => pushQuery({ mode: next === 'mine' ? undefined : next })
const setFilter = (next: CouponFolderFilter) => pushQuery({ filter: next === 'available' ? undefined : next })

const FILTERS: Array<[CouponFolderFilter, string]> = [
  ['available', '可用'],
  ['locked', '锁定中'],
  ['used', '已使用'],
  ['inactive', '已失效'],
  ['all', '全部']
]

/* ---------- 我的券夹（壳共享数据经插槽传入，G2 推导） ---------- */

const shellRef = ref<InstanceType<typeof ShiguangMemberShell> | null>(null)

const folderItemsFor = (coupons: UserCouponResponse[], current: CouponFolderFilter) =>
  coupons.filter((coupon) => couponMatchesFilter(coupon, current))

/* ---------- 可领取（独立域读取） ---------- */

const claimable = ref<CouponTemplateResponse[]>([])
const claimableState = ref<DomainState>('loading')
const claimingIds = ref<Set<number>>(new Set())

const loadClaimable = async () => {
  claimableState.value = 'loading'
  try {
    claimable.value = await couponApi.claimable({}, isolated)
    claimableState.value = 'ready'
  } catch {
    claimable.value = []
    claimableState.value = 'error'
  }
}

watch(mode, (current) => {
  if (current === 'claimable' && claimableState.value === 'loading') void loadClaimable()
}, { immediate: true })

const claim = async (template: CouponTemplateResponse) => {
  if (claimingIds.value.has(template.id)) return
  claimingIds.value = new Set(claimingIds.value).add(template.id)
  try {
    await couponApi.claim(template.id, isolated)
    ElMessage.success('优惠券已放入券夹')
    shellRef.value?.reload?.()
    void loadClaimable()
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message || ''
    if (status === 409 && message.includes('限领')) {
      // 重复领取/响应丢失重试：映射为「已领取」终态并刷新券夹核对，不当作失败诱导重复点击（G3）
      ElMessage.info('这张券已在你的券夹里')
      shellRef.value?.reload?.()
      void loadClaimable()
    } else if (status === 409) {
      ElMessage.warning(message || '这张券暂时无法领取')
      void loadClaimable()
    } else {
      ElMessage.error('领取失败，请稍后重试')
    }
  } finally {
    const next = new Set(claimingIds.value)
    next.delete(template.id)
    claimingIds.value = next
  }
}

const alreadyClaimed = (coupons: UserCouponResponse[], template: CouponTemplateResponse) =>
  coupons.some((coupon) => coupon.templateId === template.id)

/* ---------- 弹层与说明 ---------- */

const couponDialogOpen = ref(false)
const couponTarget = ref<UserCouponResponse | null>(null)
const openCoupon = (coupon: UserCouponResponse) => {
  couponTarget.value = coupon
  couponDialogOpen.value = true
}

const rulesOpen = ref(false)
const goBrowse = () => {
  couponDialogOpen.value = false
  router.push('/venues')
}
const goClaim = () => setMode('claimable')

const claimableFace = (template: CouponTemplateResponse) =>
  couponFaceValue({ couponType: template.couponType, discountValue: template.discountValue })
const claimableRule = (template: CouponTemplateResponse) =>
  couponRuleText({ couponType: template.couponType, discountValue: template.discountValue, thresholdAmount: template.thresholdAmount })
const claimableMeta = (template: CouponTemplateResponse) => {
  const parts: string[] = []
  if (template.validEndTime) parts.push(`${formatMemberDate(template.validEndTime)} 到期`)
  parts.push(template.allowActivityStack ? '可叠加活动优惠' : '不叠加活动优惠')
  return parts.join(' · ')
}
const claimableDisabled = (coupons: UserCouponResponse[], template: CouponTemplateResponse) =>
  claimingIds.value.has(template.id) || alreadyClaimed(coupons, template) || Number(template.issuedQuantity ?? 0) >= Number(template.totalQuantity ?? 0)
const claimableActionText = (coupons: UserCouponResponse[], template: CouponTemplateResponse) => {
  if (claimingIds.value.has(template.id)) return '正在领取…'
  if (alreadyClaimed(coupons, template)) return '已领取'
  if (Number(template.issuedQuantity ?? 0) >= Number(template.totalQuantity ?? 0)) return '已领完'
  return '领取'
}
</script>

<template>
  <ShiguangMemberShell ref="shellRef" active-tab="coupons" v-slot="{ coupons, couponsState, retryDomain }">
    <section>
      <div class="section-intro">
        <div>
          <h2>把心意收好，用在喜欢的地方。</h2>
          <p>已领取、订单锁定和历史优惠券，分别归位。</p>
        </div>
      </div>

      <div v-if="mode === 'mine' && (couponsState === 'error' || couponsState === 'denied')" class="state-panel" data-test="coupons-error">
        <div class="state-line" aria-hidden="true"></div>
        <h2>优惠券夹暂时无法读取</h2>
        <p>这是读取失败，不是你没有优惠券。账户其他信息仍然保留。</p>
        <button class="button primary" type="button" @click="retryDomain('coupons')">重新读取</button>
      </div>

      <template v-else>
        <div class="coupon-topline">
          <div class="filter-options" aria-label="券夹模式">
            <button type="button" :aria-pressed="mode === 'mine'" @click="setMode('mine')">我的券夹</button>
            <button type="button" :aria-pressed="mode === 'claimable'" @click="setMode('claimable')">可领取</button>
          </div>
          <button class="text-button" type="button" @click="rulesOpen = true">使用规则<el-icon><TopRight /></el-icon></button>
        </div>

        <template v-if="mode === 'mine'">
          <div class="filter-bar">
            <div class="filter-options" aria-label="券状态筛选">
              <button v-for="[key, label] in FILTERS" :key="key" type="button" :aria-pressed="filter === key" @click="setFilter(key)">{{ label }}</button>
            </div>
            <span class="filter-caption">{{ folderItemsFor(coupons, filter).length }} 张 · 不同状态不混计</span>
          </div>
          <div v-if="folderItemsFor(coupons, filter).length" class="coupon-folder">
            <article v-for="coupon in folderItemsFor(coupons, filter)" :key="coupon.id" class="ticket" :class="{ 'status-muted': !isCouponUsable(coupon) }">
              <div class="ticket-face">
                <strong class="ticket-number num">{{ couponFaceValue(coupon) }}<small>{{ coupon.couponType === 'PERCENT' ? '折' : '' }}</small></strong>
                <span class="ticket-threshold">满 {{ Number(coupon.thresholdAmount ?? 0).toFixed(0) }} 元可用</span>
              </div>
              <div class="ticket-body">
                <h3>{{ coupon.templateName }}</h3>
                <p>{{ couponRuleText(coupon) }}</p>
                <div class="ticket-meta">
                  <span v-if="couponExpiringSoonDays(coupon) !== null" class="ending-soon">{{ couponExpiringSoonDays(coupon) }} 天后到期</span><span v-if="couponExpiringSoonDays(coupon) !== null">·</span>
                  <time>{{ formatMemberDate(coupon.validEndTime) }} 到期</time>
                </div>
                <div class="ticket-meta">
                  <span class="status" :class="couponStatusTone(coupon)">{{ couponStatusText(coupon) }}</span>
                </div>
              </div>
              <button class="ticket-action" type="button" :aria-label="`查看${coupon.templateName}`" @click="openCoupon(coupon)"><el-icon><ArrowRight /></el-icon></button>
            </article>
          </div>
          <div v-else class="state-panel">
            <div class="state-line" aria-hidden="true"></div>
            <h2>这里暂时没有优惠券</h2>
            <p>已领取的券会在对应状态里归档，也可以看看当前可领取的优惠券。</p>
            <button class="button" type="button" @click="goClaim">看看可领取</button>
          </div>
        </template>

        <template v-else>
          <div v-if="claimableState === 'error'" class="state-panel">
            <div class="state-line" aria-hidden="true"></div>
            <h2>可领取优惠券暂时无法读取</h2>
            <p>请重试读取；读取失败不会作为没有可领取券处理。</p>
            <button class="button primary" type="button" @click="loadClaimable">重新读取</button>
          </div>
          <div v-else-if="claimable.length" class="coupon-folder">
            <article v-for="template in claimable" :key="template.id" class="ticket claim-ticket">
              <div class="ticket-face">
                <strong class="ticket-number num"><small v-if="template.couponType !== 'PERCENT'">¥</small>{{ claimableFace(template) }}<small v-if="template.couponType === 'PERCENT'">折</small></strong>
                <span class="ticket-threshold">满 {{ Number(template.thresholdAmount ?? 0).toFixed(0) }} 元可用</span>
              </div>
              <div class="ticket-body">
                <h3>{{ template.templateName }}</h3>
                <p>{{ claimableRule(template) }}</p>
                <div class="ticket-meta">{{ claimableMeta(template) }}</div>
              </div>
              <div class="ticket-action">
                <button class="button small" :class="{ primary: !claimableDisabled(coupons, template) }" type="button" :disabled="claimableDisabled(coupons, template)" :data-test="`claim-${template.id}`" @click="claim(template)">
                  {{ claimableActionText(coupons, template) }}
                </button>
              </div>
            </article>
          </div>
          <div v-else class="state-panel">
            <div class="state-line" aria-hidden="true"></div>
            <h2>当前没有可领取的优惠券</h2>
            <p>新的领取活动上线后会出现在这里；已领取的券在「我的券夹」归档。</p>
          </div>
        </template>
      </template>

      <p class="footnote">已释放券可恢复使用；退款券不自动复用。优惠叠加与最终价格仍由订单结算确认。</p>
    </section>

    <MemberCouponDialog v-model:open="couponDialogOpen" :coupon="couponTarget" @browse="goBrowse" />

    <MemberDialog v-model:open="rulesOpen" title="优惠券使用规则">
      <div class="document-copy">
        <h3>先看状态，再看条件。</h3>
        <p>可用与已释放的券可以参与结算；锁定中不能再用于另一笔订单。已使用、已过期、已作废、已退款均独立显示。</p>
        <h3>不是所有券都能重复用。</h3>
        <p>未支付订单取消后的释放与退款后的优惠券处理是不同状态。退款券不自动恢复。最终叠加、门槛与适用性由订单接口判定。</p>
        <h3>张数按同一口径计算。</h3>
        <p>可用张数按「已领取且未过有效期」口径计算，不含锁定中的券；不同状态不混计。</p>
      </div>
      <template #actions>
        <button class="button primary" type="button" @click="rulesOpen = false">知道了</button>
      </template>
    </MemberDialog>
  </ShiguangMemberShell>
</template>
