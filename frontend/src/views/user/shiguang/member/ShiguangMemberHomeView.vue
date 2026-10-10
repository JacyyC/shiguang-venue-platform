<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, TopRight } from '@element-plus/icons-vue'
import { memberRewardApi } from '@/api/member'
import type { MemberRewardCatalogResponse, MemberRewardRedemptionResponse, UserCouponResponse } from '@/api/types'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import ShiguangMemberShell, { type DomainState } from './ShiguangMemberShell.vue'
import MemberCouponDialog from './MemberCouponDialog.vue'
import MemberIcon from './MemberIcon.vue'
import MemberRecordDialog from './MemberRecordDialog.vue'
import MemberRewardDialog from './MemberRewardDialog.vue'
import {
  couponFaceValue,
  couponExpiringSoonDays,
  formatMemberDate,
  formatMemberInt,
  recordDetailText,
  recordPointsText,
  recordStatusText,
  recordStatusTone,
  rewardUnavailableText,
  sceneForReward
} from './memberShared'

/**
 * 会员中心主入口（/me/membership，SHIGUANG-MEMBER-REFACTOR-20260912 S04）。
 * ?tab=overview|rewards|records 深链刷新可恢复；概览/积分权益/兑换记录三视图
 * 全部读取真实服务端合同（目录、兑换记录 status 过滤、券夹共享壳数据），不使用夹具。
 */
type HomeTab = 'overview' | 'rewards' | 'records'

const route = useRoute()
const router = useRouter()
const tab = computed<HomeTab>(() => {
  const value = String(route.query.tab ?? '')
  return value === 'rewards' || value === 'records' ? value : 'overview'
})

const isolated = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

/* ---------- 权益目录（member-rewards 域，独立错误态） ---------- */

const catalog = ref<MemberRewardCatalogResponse[]>([])
const catalogState = ref<DomainState>('loading')
const rewardFilter = ref<'all' | 'eligible'>('all')

const loadCatalog = async (background = false) => {
  if (!background) catalogState.value = 'loading'
  try {
    catalog.value = await memberRewardApi.catalog(isolated)
    catalogState.value = 'ready'
  } catch {
    if (background && catalogState.value === 'ready') return
    catalog.value = []
    catalogState.value = 'error'
  }
}

const catalogItems = computed(() =>
  catalog.value.filter((item) => rewardFilter.value === 'all' || item.eligible)
)

/* ---------- 兑换记录（服务端 status 过滤 + 真实分页） ---------- */

const records = ref<MemberRewardRedemptionResponse[]>([])
const recordsState = ref<DomainState>('loading')
const recordsTotal = ref(0)
const recordFilter = ref<'all' | 'SUCCEEDED' | 'PENDING' | 'COMPENSATED'>('all')
const recordPage = ref(1)
const RECORD_PAGE_SIZE = 20

const loadRecords = async (append = false, background = false) => {
  if (!append && !background) recordsState.value = 'loading'
  try {
    const page = await memberRewardApi.history({
      pageNum: recordPage.value,
      pageSize: RECORD_PAGE_SIZE,
      ...(recordFilter.value === 'all' ? {} : { status: recordFilter.value })
    }, isolated)
    records.value = append ? [...records.value, ...(page.records ?? [])] : (page.records ?? [])
    recordsTotal.value = page.total ?? 0
    recordsState.value = 'ready'
  } catch {
    if (background && recordsState.value === 'ready') return
    if (!append) records.value = []
    recordsState.value = 'error'
  }
}

const hasMoreRecords = computed(() => records.value.length < recordsTotal.value)
const loadMoreRecords = () => {
  recordPage.value += 1
  void loadRecords(true)
}

watch(recordFilter, () => {
  recordPage.value = 1
  void loadRecords()
})

/* ---------- 弹层 ---------- */

const rewardDialogOpen = ref(false)
const rewardTarget = ref<MemberRewardCatalogResponse | null>(null)
const openReward = (item: MemberRewardCatalogResponse) => {
  rewardTarget.value = item
  rewardDialogOpen.value = true
}

const couponDialogOpen = ref(false)
const couponTarget = ref<UserCouponResponse | null>(null)
const openCoupon = (coupon: UserCouponResponse) => {
  couponTarget.value = coupon
  couponDialogOpen.value = true
}

const recordDialogOpen = ref(false)
const recordTarget = ref<MemberRewardRedemptionResponse | null>(null)
const recordChecking = ref(false)
const openRecord = (record: MemberRewardRedemptionResponse) => {
  recordTarget.value = record
  recordDialogOpen.value = true
}

/** 核对原请求：重取记录列表同步该记录最新状态（同 key 重放在兑换弹层内完成，见 MemberRewardDialog）。 */
const checkRecord = async (record: MemberRewardRedemptionResponse) => {
  if (recordChecking.value) return
  recordChecking.value = true
  try {
    const page = await memberRewardApi.history({ pageNum: 1, pageSize: RECORD_PAGE_SIZE, status: 'PENDING' }, isolated)
    const fresh = (page.records ?? []).find((row) => row.id === record.id)
    if (fresh) recordTarget.value = fresh
    else {
      recordDialogOpen.value = false
      recordPage.value = 1
      void loadRecords()
    }
  } catch {
    // 核对读取失败：保持原 PENDING 表达，不写成成功也不写成失败
  } finally {
    recordChecking.value = false
  }
}

/* ---------- 壳上下文刷新 ---------- */

const shellRef = ref<InstanceType<typeof ShiguangMemberShell> | null>(null)
const onSettled = () => {
  recordPage.value = 1
  // 成交后静默重读：重入 loading 会让记录/目录闪现空态，也与打开中的弹层状态竞争
  void loadRecords(false, true)
  void loadCatalog(true)
  shellRef.value?.reload?.()
}

watch(tab, (current) => {
  if (current === 'rewards' && catalogState.value === 'loading') void loadCatalog()
  if (current === 'records' && recordsState.value === 'loading') void loadRecords()
}, { immediate: true })

void loadCatalog()
void loadRecords()

const goCoupons = () => router.push('/me/coupons')
const goRecords = () => {
  recordDialogOpen.value = false
  rewardDialogOpen.value = false
  router.push({ path: '/me/membership', query: { tab: 'records' } })
}
const goBrowse = () => {
  couponDialogOpen.value = false
  router.push('/venues')
}
</script>

<template>
  <ShiguangMemberShell ref="shellRef" :active-tab="tab" v-slot="{ account, availablePoints, pendingOffset, couponsState, usableCoupons, retryDomain }">
    <!-- ================= 概览 ================= -->
    <template v-if="tab === 'overview'">
      <div class="overview-desk">
        <section>
          <div class="section-head">
            <h2>下一次出发，带上这份心意。</h2>
            <router-link class="text-button" to="/me/coupons">打开券夹<el-icon><TopRight /></el-icon></router-link>
          </div>
          <div v-if="couponsState === 'error' || couponsState === 'denied'" class="inline-empty" data-test="overview-coupons-error">
            <h3>券夹暂时没读到</h3>
            <p>会员等级与积分仍可查看。请重新读取券夹，不将错误显示为 0 张。</p>
            <button class="text-button accent" type="button" @click="retryDomain('coupons')">重新读取<el-icon><TopRight /></el-icon></button>
          </div>
          <div v-else-if="usableCoupons.length" class="ticket-list">
            <article v-for="coupon in usableCoupons.slice(0, 2)" :key="coupon.id" class="ticket">
              <div class="ticket-face">
                <strong class="ticket-number num">{{ couponFaceValue(coupon) }}<small>{{ coupon.couponType === 'PERCENT' ? '折' : '' }}</small></strong>
                <span class="ticket-threshold">满 {{ Number(coupon.thresholdAmount ?? 0).toFixed(0) }} 元可用</span>
              </div>
              <div class="ticket-body">
                <h3>{{ coupon.templateName }}</h3>
                <div class="ticket-meta">
                  <span v-if="couponExpiringSoonDays(coupon) !== null" class="ending-soon">{{ couponExpiringSoonDays(coupon) }} 天后到期</span><span v-if="couponExpiringSoonDays(coupon) !== null">·</span>
                  <time>{{ formatMemberDate(coupon.validEndTime) }} 到期</time>
                </div>
              </div>
              <button class="ticket-action" type="button" :aria-label="`查看${coupon.templateName}`" @click="openCoupon(coupon)"><el-icon><ArrowRight /></el-icon></button>
            </article>
          </div>
          <div v-else class="inline-empty">
            <h3>还没有可用优惠券</h3>
            <p>先去领券，或用积分兑换一份想要的权益。</p>
            <router-link class="text-button accent" to="/me/coupons">看看可领取的券<el-icon><TopRight /></el-icon></router-link>
          </div>
        </section>
        <section class="overview-rewards">
          <div class="section-head">
            <h2>积分，换一点喜欢。</h2>
            <router-link class="text-button" :to="{ path: '/me/membership', query: { tab: 'rewards' } }">全部权益<el-icon><TopRight /></el-icon></router-link>
          </div>
          <div v-if="catalogState === 'error'" class="inline-empty">
            <h3>权益目录暂时没读到</h3>
            <p>请重新读取，不把读取失败展示为没有权益。</p>
            <button class="text-button accent" type="button" @click="loadCatalog()">重新读取<el-icon><TopRight /></el-icon></button>
          </div>
          <div v-else class="mini-rewards">
            <article v-for="item in catalog.slice(0, 2)" :key="item.itemId" class="mini-reward">
              <figure class="mini-art"><ShiguangArt :scene="sceneForReward(item)" /></figure>
              <div>
                <h3>{{ item.itemName }}</h3>
                <p>{{ item.minimumLevelName }} · 兑换后 {{ item.validityDays }} 天有效</p>
              </div>
              <button class="mini-cost" type="button" :aria-label="`兑换${item.itemName}`" @click="openReward(item)">
                {{ formatMemberInt(item.pointsCost) }}<small>积分 <el-icon><ArrowRight /></el-icon></small>
              </button>
            </article>
          </div>
          <p class="footnote">兑换使用积分，不扣减成长值。<br>券的适用条件与最终优惠，以订单结算结果为准。</p>
        </section>
      </div>
      <section class="recent-section">
        <div class="section-head">
          <h2>最近的权益记录</h2>
          <router-link class="text-button" :to="{ path: '/me/membership', query: { tab: 'records' } }">全部记录<el-icon><TopRight /></el-icon></router-link>
        </div>
        <div v-if="recordsState === 'error'" class="inline-empty">
          <h3>权益记录暂时没读到</h3>
          <p>请重新读取，读取失败不会作为空记录处理。</p>
          <button class="text-button accent" type="button" @click="loadRecords()">重新读取<el-icon><TopRight /></el-icon></button>
        </div>
        <div v-else-if="records.length" class="record-list">
          <div v-for="record in records.slice(0, 2)" :key="record.id" class="record-row">
            <div class="record-description">
              <h3><button class="text-button" type="button" @click="openRecord(record)">{{ record.itemName }}<el-icon><TopRight /></el-icon></button></h3>
              <p>{{ recordDetailText(record) }}</p>
            </div>
            <time>{{ formatMemberDate(record.createTime) }}</time>
            <div class="record-value">{{ recordPointsText(record) }}<small v-if="record.status !== 'PENDING'">积分</small></div>
            <span class="status" :class="recordStatusTone(record)">{{ recordStatusText(record) }}</span>
          </div>
        </div>
        <div v-else class="inline-empty">
          <h3>还没有兑换记录</h3>
          <p>每一次兑换、处理与积分补偿，都在这里留有记录。</p>
        </div>
      </section>
    </template>

    <!-- ================= 积分权益 ================= -->
    <section v-else-if="tab === 'rewards'">
      <div class="section-intro">
        <div>
          <h2>让积累，变成下一次喜欢。</h2>
          <p>挑一份用得上的权益。成长值留在等级里，积分用在生活里。</p>
        </div>
        <div class="balance-note">
          <span>当前可用积分</span>
          <strong>{{ formatMemberInt(availablePoints) }}<small>分</small></strong>
        </div>
      </div>
      <div v-if="pendingOffset > 0" class="account-banner warning" role="status">
        <MemberIcon name="InfoFilled" />
        <p>待抵扣 {{ formatMemberInt(pendingOffset) }} 积分。后续获得积分先抵扣欠额；成长值不因此变为负数。</p>
      </div>
      <div class="filter-bar">
        <div class="filter-options" aria-label="权益筛选">
          <button type="button" :aria-pressed="rewardFilter === 'all'" @click="rewardFilter = 'all'">全部权益</button>
          <button type="button" :aria-pressed="rewardFilter === 'eligible'" @click="rewardFilter = 'eligible'">当前可兑换</button>
        </div>
        <span class="filter-caption">{{ catalogItems.length }} 项权益 · 兑换前再次确认条件</span>
      </div>
      <div v-if="catalogState === 'error'" class="state-panel">
        <div class="state-line" aria-hidden="true"></div>
        <h2>权益目录暂时无法读取</h2>
        <p>请重试读取；读取失败不会作为空目录处理。</p>
        <button class="button primary" type="button" @click="loadCatalog()">重新读取</button>
      </div>
      <div v-else-if="catalogItems.length">
        <article v-for="item in catalogItems" :key="item.itemId" class="catalog-row">
          <figure class="catalog-art"><ShiguangArt :scene="sceneForReward(item)" /></figure>
          <div class="catalog-copy">
            <h3>{{ item.itemName }}</h3>
            <p>{{ item.descriptionText || '兑换后优惠券将放入券夹。' }}</p>
            <div class="catalog-meta">{{ item.minimumLevelName }}起 · 兑换后 {{ item.validityDays }} 天有效 · 版本 v{{ item.versionNo }}</div>
          </div>
          <div class="catalog-price">
            <strong>{{ formatMemberInt(item.pointsCost) }}</strong><small>积分</small>
            <p>不消耗成长值</p>
          </div>
          <div class="catalog-cta">
            <button class="button small" :class="{ primary: item.eligible }" type="button" @click="openReward(item)">{{ item.eligible ? '兑换' : '查看条件' }}</button>
            <p v-if="!item.eligible">{{ rewardUnavailableText(item) }}</p>
          </div>
        </article>
      </div>
      <div v-else class="state-panel">
        <div class="state-line" aria-hidden="true"></div>
        <h2>还没有适合当前积分的权益</h2>
        <p>换个筛选看看完整目录；不会把不满足条件的权益展示为可兑换。</p>
      </div>
      <p class="footnote">权益目录、积分价格与有效期以服务端生效配置为准；兑换确认绑定当前生效版本，过期版本不能提交。</p>
    </section>

    <!-- ================= 兑换记录 ================= -->
    <section v-else>
      <div class="section-intro">
        <div>
          <h2>每一份权益，都有来处。</h2>
          <p>完成、处理中与积分补偿分别记录，未知结果不写成成功。</p>
        </div>
      </div>
      <div class="filter-bar">
        <div class="filter-options" aria-label="记录筛选">
          <button type="button" :aria-pressed="recordFilter === 'all'" @click="recordFilter = 'all'">全部</button>
          <button type="button" :aria-pressed="recordFilter === 'SUCCEEDED'" @click="recordFilter = 'SUCCEEDED'">已兑换</button>
          <button type="button" :aria-pressed="recordFilter === 'PENDING'" @click="recordFilter = 'PENDING'">处理中</button>
          <button type="button" :aria-pressed="recordFilter === 'COMPENSATED'" @click="recordFilter = 'COMPENSATED'">已补偿</button>
        </div>
        <span class="filter-caption">{{ recordsTotal }} 条记录</span>
      </div>
      <div v-if="recordsState === 'error'" class="state-panel">
        <div class="state-line" aria-hidden="true"></div>
        <h2>权益记录暂时无法读取</h2>
        <p>请重试读取；读取失败不会作为空记录处理。</p>
        <button class="button primary" type="button" @click="loadRecords()">重新读取</button>
      </div>
      <template v-else-if="records.length">
        <div class="record-list">
          <div v-for="record in records" :key="record.id" class="record-row">
            <div class="record-description">
              <h3><button class="text-button" type="button" @click="openRecord(record)">{{ record.itemName }}<el-icon><TopRight /></el-icon></button></h3>
              <p>{{ recordDetailText(record) }}</p>
            </div>
            <time>{{ formatMemberDate(record.createTime) }}</time>
            <div class="record-value">{{ recordPointsText(record) }}<small v-if="record.status !== 'PENDING'">积分</small></div>
            <span class="status" :class="recordStatusTone(record)">{{ recordStatusText(record) }}</span>
          </div>
        </div>
        <button v-if="hasMoreRecords" class="button" type="button" style="margin-top:18px" @click="loadMoreRecords">加载更早的记录</button>
      </template>
      <div v-else class="state-panel">
        <div class="state-line" aria-hidden="true"></div>
        <h2>还没有这类兑换记录</h2>
        <p>记录会随实际兑换结果更新；读取失败不会作为空记录处理。</p>
      </div>
      <p class="footnote">这是权益兑换历史，不冒充尚未接入的全量积分流水。成长值不因兑换而减少。</p>
    </section>

    <!-- 弹层 -->
    <MemberRewardDialog
      v-model:open="rewardDialogOpen"
      :item="rewardTarget"
      :account="account"
      @settled="onSettled"
      @go-coupons="goCoupons"
      @go-records="goRecords"
    />
    <MemberCouponDialog v-model:open="couponDialogOpen" :coupon="couponTarget" @browse="goBrowse" />
    <MemberRecordDialog
      v-model:open="recordDialogOpen"
      :record="recordTarget"
      :checking="recordChecking"
      @check="checkRecord"
      @go-records="goRecords"
    />
  </ShiguangMemberShell>
</template>
