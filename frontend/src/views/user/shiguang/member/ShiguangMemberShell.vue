<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { TopRight } from '@element-plus/icons-vue'
import { memberApi } from '@/api/member'
import { couponApi } from '@/api/promotion'
import { storedValueApi } from '@/api/storedvalue'
import type { MemberAccountResponse, UserCouponResponse } from '@/api/types'
import type { StoredValueSelf } from '@/api/storedvalue'
import { useMenuStore } from '@/stores/menu'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import MemberDialog from './MemberDialog.vue'
import MemberIcon from './MemberIcon.vue'
import {
  availablePointsOf,
  couponExpiringSoonDays,
  discountText,
  formatMemberInt,
  formatMemberMoney,
  isCouponUsable,
  memberSerialText,
  pendingOffsetPointsOf
} from './memberShared'

/**
 * 会员中心共享壳（SHIGUANG-MEMBER-REFACTOR-20260912 S04）：
 * 页头、会员凭证身份区、三口袋分账条与五页内部导航，复刻冻结构图。
 * G6 局部失败隔离：会员账户、券夹、储值三域独立请求、独立错误态，
 * 单域失败只影响对应区块，张数/余额显示「—」不置零；会员域整页失败给重试，403 给权限态。
 */
export type MemberTab = 'overview' | 'rewards' | 'coupons' | 'stored' | 'records'
export type DomainState = 'loading' | 'ready' | 'error' | 'denied'

const props = defineProps<{ activeTab: MemberTab }>()

const menu = useMenuStore()

const canCoupons = computed(() => menu.hasPermission('coupon:me'))
const canStored = computed(() => menu.hasPermission('stored-value:me'))

const account = ref<MemberAccountResponse | null>(null)
const accountState = ref<DomainState>('loading')
const coupons = ref<UserCouponResponse[]>([])
const couponsState = ref<DomainState>('loading')
const storedSelf = ref<StoredValueSelf | null>(null)
const storedState = ref<DomainState>('loading')

const isolated = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }

const stateOf = (error: unknown): DomainState =>
  (error as { response?: { status?: number } })?.response?.status === 403 ? 'denied' : 'error'

const loadAccount = async (background = false) => {
  if (!background) accountState.value = 'loading'
  try {
    account.value = await memberApi.me(isolated)
    accountState.value = 'ready'
  } catch (error) {
    if (background && accountState.value === 'ready') return
    account.value = null
    accountState.value = stateOf(error)
  }
}

/** 券夹全量拉取（G2 推导基数：逐页取尽，不用单页筛选冒充全量；单用户券量天然小）。 */
const loadCoupons = async (background = false) => {
  if (!canCoupons.value) {
    coupons.value = []
    couponsState.value = 'denied'
    return
  }
  if (!background) couponsState.value = 'loading'
  try {
    const all: UserCouponResponse[] = []
    let pageNum = 1
    for (;;) {
      const page = await couponApi.my({ pageNum, pageSize: 100 }, isolated)
      all.push(...(page.records ?? []))
      if ((page.records ?? []).length < 100 || all.length >= 1000) break
      pageNum += 1
    }
    coupons.value = all
    couponsState.value = 'ready'
  } catch (error) {
    if (background && couponsState.value === 'ready') return
    coupons.value = []
    couponsState.value = stateOf(error)
  }
}

const loadStored = async (background = false) => {
  if (!canStored.value) {
    storedSelf.value = null
    storedState.value = 'denied'
    return
  }
  if (!background) storedState.value = 'loading'
  try {
    storedSelf.value = await storedValueApi.self(isolated)
    storedState.value = 'ready'
  } catch (error) {
    if (background && storedState.value === 'ready') return
    storedSelf.value = null
    storedState.value = stateOf(error)
  }
}

const retryDomain = (domain: 'account' | 'coupons' | 'stored') => {
  if (domain === 'account') void loadAccount()
  else if (domain === 'coupons') void loadCoupons()
  else void loadStored()
}

/** 写链路成交后由子视图触发：三域全部后台重读（兑换发券、领券、充值入账会同时影响口袋条）。
 * 必须用 background=true：重入 loading 会卸载 member-content（含打开中的弹层），
 * 弹层结果态会被清空退回初始阶段。 */
const reload = () => {
  void loadAccount(true)
  void loadCoupons(true)
  void loadStored(true)
}

defineExpose({ reload })

onMounted(() => {
  void loadAccount()
  void loadCoupons()
  void loadStored()
})

/* ---------- 身份区 ---------- */

const availablePoints = computed(() => availablePointsOf(account.value?.pointsBalance))
const pendingOffset = computed(() => pendingOffsetPointsOf(account.value?.pointsBalance))
const displayName = computed(() => account.value?.nickname?.trim() || account.value?.username || '会员')
const levelName = computed(() => account.value?.levelName || '普通会员')
const isNewcomer = computed(() => Number(account.value?.growthValue ?? 0) === 0)
const balanceText = computed(() => (isNewcomer.value ? '从第一次喜欢的安排开始' : '成长值与积分分别记录'))
const serial = computed(() => memberSerialText(account.value?.memberAccountId))
const discount = computed(() => discountText(account.value?.discountRate))
const savedAmount = computed(() => formatMemberMoney(account.value?.totalMemberDiscountAmount ?? 0))
const nextLevel = computed(() => account.value?.nextLevel ?? null)
const growthGap = computed(() => Number(account.value?.nextLevelGrowthGap ?? 0))
const growthTarget = computed(() => Number(nextLevel.value?.growthThreshold ?? 0))
const growthProgress = computed(() => {
  if (!nextLevel.value || growthTarget.value <= 0) return 100
  return Math.min(100, Math.round((Number(account.value?.growthValue ?? 0) / growthTarget.value) * 100))
})

/* ---------- 口袋条 ---------- */

const usableCoupons = computed(() => coupons.value.filter((coupon) => isCouponUsable(coupon)))
const soonCount = computed(() => coupons.value.filter((coupon) => couponExpiringSoonDays(coupon) !== null).length)
const couponPocketValue = computed(() => (couponsState.value === 'ready' ? String(usableCoupons.value.length) : '—'))
const couponPocketMeta = computed(() => {
  if (couponsState.value === 'error' || couponsState.value === 'denied') return '暂未读到券夹，金额与张数不置零'
  if (couponsState.value === 'loading') return '正在读取券夹…'
  if (!usableCoupons.value.length) return '去看看有什么可领取'
  return `${soonCount.value ? `${soonCount.value} 张即将到期` : '暂无即将到期的券'} · 不含锁定中的券`
})

const storedAccount = computed(() => storedSelf.value?.account ?? null)
const storedReady = computed(() => storedState.value === 'ready' && !!storedAccount.value)
const storedFrozen = computed(() => (storedAccount.value?.status ?? '') !== 'NORMAL' && !!storedAccount.value)
const storedTotal = computed(() => Number(storedAccount.value?.principalAvailable ?? 0) + Number(storedAccount.value?.bonusAvailable ?? 0))
const storedEnv = computed(() => storedSelf.value?.product?.environment ?? 'DEV')
const storedPocketMeta = computed(() => {
  if (storedState.value === 'error' || storedState.value === 'denied') return '暂未读到储值账户，不显示为零'
  if (storedState.value === 'loading') return '正在读取储值账户…'
  if (!storedAccount.value) return storedSelf.value?.eligibilityMessage || '未开通不是余额为零'
  const frozenNote = storedFrozen.value ? '账户冻结 · 暂不可支付 · ' : ''
  return `${frozenNote}本金 ¥${formatMemberMoney(storedAccount.value?.principalAvailable)} ＋ 赠送 ¥${formatMemberMoney(storedAccount.value?.bonusAvailable)}`
})

/* ---------- 内部导航（三入口权限独立：无权限的页签不渲染，不伪造） ---------- */

const tabs = computed(() => {
  const entries: Array<{ key: MemberTab; label: string; short: string; to: { path: string; query?: Record<string, string> } }> = [
    { key: 'overview', label: '会员概览', short: '概览', to: { path: '/me/membership' } },
    { key: 'rewards', label: '积分权益', short: '积分权益', to: { path: '/me/membership', query: { tab: 'rewards' } } }
  ]
  if (canCoupons.value) entries.push({ key: 'coupons', label: '优惠券夹', short: '券夹', to: { path: '/me/coupons' } })
  if (canStored.value) entries.push({ key: 'stored', label: '储值账户', short: '储值', to: { path: '/me/stored-value' } })
  entries.push({ key: 'records', label: '兑换记录', short: '记录', to: { path: '/me/membership', query: { tab: 'records' } } })
  return entries
})

/* ---------- 等级与权益说明（真实等级数据 + 规则解释） ---------- */

const levelDocOpen = ref(false)
</script>

<template>
  <div class="sgv-member" data-route-focus tabindex="-1">
    <div class="shell">
      <div class="page-heading">
        <div>
          <h1>会员中心</h1>
          <p>把日常积累，留给下一次喜欢的生活。</p>
        </div>
      </div>

      <!-- 会员域 403：整页权限态（预览 denied 场景） -->
      <div v-if="accountState === 'denied'" class="state-panel" data-test="member-denied">
        <div class="state-line" aria-hidden="true"></div>
        <h2>当前账号没有会员访问权限</h2>
        <p>这里不显示会员、积分或储值信息。正式页面仍由独立权限判断，其他账户能力不因视觉合并自动授权。</p>
      </div>

      <!-- 会员域读取失败：整页错误 + 重试（预览 error 场景），不把账户画成零 -->
      <template v-else-if="accountState === 'error'">
        <nav class="member-tabs" aria-label="会员中心导航">
          <a v-for="tab in tabs" :key="tab.key" :aria-current="tab.key === activeTab ? 'page' : undefined"><span class="tab-name-full">{{ tab.label }}</span><span class="tab-name-short">{{ tab.short }}</span></a>
          <span class="tab-note">积分、成长与储值，各有自己的账。</span>
        </nav>
        <div class="state-panel" data-test="member-error">
          <div class="state-line" aria-hidden="true"></div>
          <h2>会员信息暂时无法读取</h2>
          <p>你的等级、积分和余额没有变成零。请重试读取；读取失败不会作为空账户处理。</p>
          <button class="button primary" type="button" @click="retryDomain('account')">重新读取</button>
        </div>
      </template>

      <template v-else-if="accountState === 'ready' && account">
        <section class="identity-spread" aria-label="会员身份与当前权益">
          <div class="member-pass">
            <div class="pass-top">
              <svg class="wordmark" aria-hidden="true"><use href="#sgv-brand-sign" /></svg>
              <span>会员账户</span>
            </div>
            <div class="pass-identity">
              <h2>{{ displayName }}</h2>
              <p class="pass-level">{{ levelName }}</p>
            </div>
            <figure class="pass-art feature-art" aria-hidden="true"><ShiguangArt scene="quiet" /></figure>
            <div class="pass-foot">
              <span>{{ balanceText }}</span>
              <span class="serial">{{ serial }}</span>
            </div>
          </div>
          <div class="membership-summary">
            <div class="summary-heading">
              <h2>你的日常，值得一点优待。</h2>
              <button class="text-button" type="button" data-test="level-doc" @click="levelDocOpen = true">等级与权益<el-icon><TopRight /></el-icon></button>
            </div>
            <div class="benefit-facts">
              <div class="discount-number num">
                <template v-if="discount.plain">原价</template>
                <template v-else><span v-html="discount.main"></span><small>{{ discount.suffix }}</small></template>
              </div>
              <div class="discount-explain">
                <p><b>{{ discount.plain ? '普通会员预约价格' : '当前会员预约折扣' }}</b></p>
                <p>已享会员优惠 <b class="num">¥{{ savedAmount }}</b> · 按实际订单累计</p>
              </div>
            </div>
            <div class="growth-block">
              <div class="growth-top">
                <span>成长值<strong class="num">{{ formatMemberInt(account.growthValue) }}</strong></span>
                <span v-if="nextLevel && growthGap > 0" class="growth-next">再积累 <b class="num">{{ formatMemberInt(growthGap) }}</b>，成为{{ nextLevel.levelName }}</span>
                <span v-else class="growth-next">当前已达最高可用等级</span>
              </div>
              <div
                class="growth-scale"
                :style="{ '--progress': `${growthProgress}%` }"
                role="progressbar"
                aria-label="到下一等级的成长值"
                :aria-valuenow="account.growthValue"
                aria-valuemin="0"
                :aria-valuemax="growthTarget || account.growthValue"
              ><span class="growth-pin"></span></div>
              <div class="growth-ends">
                <span>0</span>
                <span v-if="nextLevel">{{ formatMemberInt(growthTarget) }} · {{ nextLevel.levelName }}</span>
                <span v-else>{{ formatMemberInt(account.growthValue) }} · {{ levelName }}</span>
              </div>
            </div>
          </div>
        </section>

        <section class="pocket-strip" aria-label="分别记录的账户信息">
          <router-link class="pocket" :to="{ path: '/me/membership', query: { tab: 'rewards' } }">
            <div class="pocket-main">
              <span class="pocket-label"><MemberIcon name="Coin" />可用积分</span>
              <strong class="pocket-value num">{{ formatMemberInt(availablePoints) }}<small>分</small></strong>
            </div>
            <el-icon><TopRight /></el-icon>
            <p class="pocket-meta">{{ pendingOffset > 0 ? `待抵扣 ${formatMemberInt(pendingOffset)} 分 · 后续积分先抵扣` : '用于兑换权益，不是现金' }}</p>
          </router-link>
          <router-link v-if="canCoupons" class="pocket" to="/me/coupons">
            <div class="pocket-main">
              <span class="pocket-label"><MemberIcon name="Ticket" />可用优惠券</span>
              <strong class="pocket-value num">{{ couponPocketValue }}<small>张</small></strong>
            </div>
            <el-icon><TopRight /></el-icon>
            <p class="pocket-meta">{{ couponPocketMeta }}</p>
          </router-link>
          <router-link v-if="canStored" class="pocket pocket-money" to="/me/stored-value">
            <div class="pocket-main">
              <span class="pocket-label"><MemberIcon name="Wallet" />储值账户 <span class="environment">{{ storedEnv }}</span></span>
              <strong class="pocket-value num" v-if="storedReady"><small class="money-symbol">¥</small>{{ formatMemberMoney(storedTotal) }}</strong>
              <strong class="pocket-value num" v-else-if="storedState === 'ready'"><small>暂未开通</small></strong>
              <strong class="pocket-value num" v-else><small>—</small></strong>
            </div>
            <el-icon><TopRight /></el-icon>
            <p class="pocket-meta">{{ storedPocketMeta }}</p>
          </router-link>
        </section>
      </template>

      <nav v-if="accountState === 'ready'" class="member-tabs" aria-label="会员中心导航">
        <router-link v-for="tab in tabs" :key="tab.key" :to="tab.to" :aria-current="tab.key === activeTab ? 'page' : undefined">
          <span class="tab-name-full">{{ tab.label }}</span><span class="tab-name-short">{{ tab.short }}</span>
        </router-link>
        <span class="tab-note">积分、成长与储值，各有自己的账。</span>
      </nav>

      <div v-if="accountState === 'loading'" class="state-panel">
        <div class="state-line" aria-hidden="true"></div>
        <h2>正在读取会员信息…</h2>
      </div>

      <div v-if="accountState === 'ready'" class="member-content" id="member-content" tabindex="-1">
        <slot
          :account="account"
          :available-points="availablePoints"
          :pending-offset="pendingOffset"
          :coupons="coupons"
          :coupons-state="couponsState"
          :usable-coupons="usableCoupons"
          :stored-self="storedSelf"
          :stored-state="storedState"
          :retry-domain="retryDomain"
        />
      </div>
    </div>

    <MemberDialog v-model:open="levelDocOpen" title="等级与权益" clarifier="当前等级数据来自接口生效配置">
      <div class="document-copy">
        <h3>现在是{{ levelName }}<template v-if="nextLevel && growthGap > 0">，再积累 {{ formatMemberInt(growthGap) }} 成长值可成为{{ nextLevel.levelName }}</template>。</h3>
        <p>{{ account?.benefitText?.trim() || '真实等级、折扣与权益以接口返回的生效配置为准，本页不写死等级规则。' }}当前会员预约折扣不等于每笔订单最终价格，适用范围与库存由结算链路判定。</p>
        <h3>成长值和积分，是两件事。</h3>
        <p>成长值记录会员成长；积分用于兑换权益。兑换积分不减少成长值。退款等规则可能让积分产生待抵扣额，需单独展示，不能显示成负现金。</p>
        <h3>优惠以结算结果为准。</h3>
        <p>活动、券、适用范围及库存由服务端检查，不因新界面改变规则。</p>
      </div>
      <template #actions>
        <button class="button primary" type="button" @click="levelDocOpen = false">知道了</button>
      </template>
    </MemberDialog>
  </div>
</template>
