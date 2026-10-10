<template>
  <div ref="rootEl" class="sgv-user-service" data-route-focus-root>
    <div class="d-top">
      <div>
        <p class="d-eyebrow">我的 / {{ sectionLabel }}</p>
        <h1>{{ heading }}</h1>
        <p class="d-intro">{{ intro }}</p>
      </div>
    </div>

    <div class="d-personal-grid">
      <nav class="d-personal-nav" aria-label="个人服务">
        <button
          v-for="[key, label] in visibleSections"
          :key="key"
          type="button"
          :data-view="key"
          :aria-pressed="section === key"
          @click="selectSection(key)"
        >{{ label }}</button>
      </nav>
      <div id="personal-content" data-route-focus tabindex="-1">
        <section v-if="surfaceState" class="d-state" :role="surfaceState.role" :aria-busy="surfaceState.busy">
          <h2>{{ surfaceState.title }}</h2>
          <p>{{ surfaceState.text }}</p>
          <button v-if="surfaceState.action" class="d-btn" :class="{ primary: surfaceState.primary }" type="button" @click="surfaceState.action.handler">
            {{ surfaceState.action.label }}
          </button>
        </section>

        <template v-else>
          <!-- 概览 -->
          <div v-if="section === 'overview'" class="d-overview">
            <section>
              <div class="d-greeting">
                <h2>先看看与你有关的新进展。</h2>
                <p class="d-muted">{{ maskPhone(profile?.phone) }} · {{ maskEmail(profile?.email) }}</p>
                <div v-if="messagesState === 'ready'" class="d-inset">
                  <div class="d-top">
                    <p class="d-eyebrow">消息中心</p>
                    <span class="d-pill">{{ unreadCount }} 条未读</span>
                  </div>
                  <h3>{{ latestMessage?.title || '暂时没有新消息' }}</h3>
                  <p>{{ latestMessage?.content || '预约与服务的后续变化会留在这里。' }}</p>
                  <button class="d-link" type="button" @click="selectSection('messages')">查看所有消息 →</button>
                </div>
                <p v-else role="status" class="d-muted">消息暂时无法读取，请到消息中心重试。</p>
              </div>
              <button v-if="pendingFeedback" class="d-service-link" type="button" @click="gotoFeedback(pendingFeedback)">
                <div class="d-top">
                  <h3>{{ pendingFeedback.title }}，继续查看处理记录。</h3>
                  <span class="d-pill attention">待处理</span>
                </div>
                <p>你在{{ pendingFeedback.venueName }}提交的反馈已进入队列，后续用户可见回复会保留在这里。</p>
              </button>
              <router-link v-if="nextUp" class="d-service-link" :to="`/me/reservations#credential-${resId(nextUp)}`">
                <h3>下一次到场 · {{ resVenueName(nextUp) }}</h3>
                <p>{{ resSpaceName(nextUp) || '场地详情见预约' }} · {{ resWhen(nextUp) }}。查看预约与凭证 →</p>
              </router-link>
              <p v-else-if="reservationsState === 'ready'" class="d-muted">暂时没有待到场的安排。</p>
              <p v-else class="d-muted">到场安排暂时无法读取，请到预约记录核对。</p>
            </section>
          </div>

          <!-- 消息 -->
          <section v-else-if="section === 'messages'">
            <div v-if="messagesState === 'error'" class="d-state" role="alert">
              <h2>这部分内容暂时无法读取</h2>
              <p>读取失败不代表记录不存在，请稍后重试。</p>
              <button class="d-btn primary" type="button" @click="reloadAll">重新读取</button>
            </div>
            <template v-else>
            <div class="d-top">
              <div class="d-row">
                <button class="d-btn" type="button" data-test="filter-all" :aria-pressed="unreadOnly === false" @click="setUnreadOnly(false)">全部消息</button>
                <button class="d-btn" type="button" data-test="filter-unread" :aria-pressed="unreadOnly === true" @click="setUnreadOnly(true)">只看未读</button>
              </div>
              <button class="d-link" type="button" data-test="read-all" :disabled="readonlyBusy || !messages.some(m => !m.readFlag)" @click="openReadAll">全部标为已读</button>
            </div>
            <template v-if="visibleMessages.length">
              <article v-for="m in visibleMessages" :key="m.id" class="d-message" :class="{ unread: !m.readFlag }">
                <div class="d-row">
                  <span class="d-pill">{{ messageTypePill(m.type) }}</span>
                  <span class="d-muted">{{ messageTime(m.createTime) }} · {{ m.readFlag ? '已读' : '未读' }}</span>
                </div>
                <h3>{{ m.title }}</h3>
                <p>{{ m.content }}</p>
                <div class="d-row">
                  <button v-if="messageTarget(m)" class="d-link" type="button" @click="openMessageTarget(m)">{{ messageTarget(m)!.label }}</button>
                  <button v-if="!m.readFlag" class="d-link" type="button" :data-test="`read-${m.id}`" @click="markOne(m.id)">标为已读</button>
                </div>
              </article>
            </template>
            <div v-else class="d-state">
              <h2>没有未读消息</h2>
              <p>新的进度会在这里提醒你。</p>
              <button v-if="unreadOnly" class="d-btn" type="button" @click="setUnreadOnly(false)">查看全部消息</button>
            </div>
            </template>
          </section>

          <!-- 反馈 -->
          <section v-else-if="section === 'feedback'">
            <div v-if="feedbackState === 'denied'" class="d-state">
              <h2>当前缺少本人反馈权限</h2>
              <p>你的反馈需在恢复授权后读取。</p>
            </div>
            <div v-else-if="feedbackState === 'error'" class="d-state" role="alert">
              <h2>这部分内容暂时无法读取</h2>
              <p>读取失败不代表记录不存在，请稍后重试。</p>
              <button class="d-btn primary" type="button" @click="reloadAll">重新读取</button>
            </div>
            <template v-else>
              <template v-if="feedbackId">
                <button class="d-link" type="button" data-test="feedback-back" @click="closeFeedbackDetail">← 返回反馈记录</button>
                <template v-if="feedbackDetail">
                  <p class="d-eyebrow" style="margin-top:25px">{{ feedbackDetail.caseNo || `本人${feedbackDetail.kindLabel}` }} · {{ feedbackDetail.reservationId ? `预约 #${feedbackDetail.reservationId}` : feedbackDetail.venueName }}</p>
                  <h2>{{ feedbackDetail.title }}</h2>
                  <p class="d-muted">{{ feedbackDetail.venueName }} · {{ messageTime(feedbackDetail.createTime) }}</p>
                  <p>{{ feedbackDetail.body }}</p>
                  <p v-if="feedbackDetail.kind === 'complaint' && feedbackDetail.problem" class="d-muted">问题：{{ feedbackDetail.problem }} · 影响程度：{{ feedbackDetail.severity }}</p>
                  <p v-if="feedbackDetail.kind === 'review' && detailRatingsText" class="d-muted">整体 {{ feedbackDetail.rating }} 分 · {{ detailRatingsText }}<br v-if="feedbackDetail.tags">标签：{{ feedbackDetail.tags }}</p>
                  <span class="d-pill" :class="{ attention: feedbackDetail.attention }">{{ feedbackDetail.status }}</span>
                  <ol v-if="feedbackDetail.events.length" class="d-history" aria-label="本人处理记录">
                    <li v-for="ev in feedbackDetail.events" :key="ev.label + ev.time">
                      <strong>{{ ev.label }}</strong> <small>{{ ev.time }}</small>
                      <p v-if="ev.note">{{ ev.note }}</p>
                    </li>
                  </ol>
                  <p class="d-muted">这里展示与你有关的处理结果和回复。新的进展会继续保留在此记录中。</p>
                  <router-link v-if="feedbackDetail.reservationId" class="d-link" :to="`/me/reservations#booking-${feedbackDetail.reservationId}`">查看这次预约 →</router-link>
                </template>
                <div v-else class="d-state">
                  <h2>没有找到这条本人反馈</h2>
                  <p>请返回反馈列表重新选择。</p>
                  <button class="d-btn" type="button" @click="closeFeedbackDetail">返回列表</button>
                </div>
              </template>
              <template v-else>
                <div class="d-top">
                  <div>
                    <h2>属于你的反馈记录</h2>
                    <p class="d-muted">评价审核与投诉处理各自保留状态。</p>
                  </div>
                  <button class="d-btn primary" type="button" data-test="feedback-new" @click="openFeedbackNew(null, $event)">提交新反馈</button>
                </div>
                <template v-if="feedbackList.length">
                  <article v-for="f in feedbackList" :key="f.key" class="d-feedback">
                    <div class="d-row">
                      <span class="d-pill">{{ f.kindLabel }}</span>
                      <span class="d-muted">{{ f.venueName }} · {{ messageTime(f.createTime) }}</span>
                    </div>
                    <h3>{{ f.title }}</h3>
                    <p>{{ f.body }}</p>
                    <div class="d-row">
                      <span class="d-pill" :class="{ attention: f.attention }">{{ f.status }}</span>
                      <span v-if="f.rating">{{ f.rating }} / 5 分</span>
                      <button class="d-link" type="button" @click="gotoFeedback(f)">查看本人处理记录 →</button>
                    </div>
                  </article>
                </template>
                <div v-else class="d-state">
                  <h2>还没有提交过反馈</h2>
                  <p>新的记录会在你使用对应功能后显示。</p>
                </div>
              </template>
            </template>
          </section>

          <!-- 收藏 -->
          <section v-else-if="section === 'favorites'">
            <div v-if="favoritesState === 'denied'" class="d-state">
              <h2>当前缺少收藏权限</h2>
              <p>收藏列表与操作不展示。</p>
            </div>
            <div v-else-if="favoritesState === 'error'" class="d-state" role="alert">
              <h2>这部分内容暂时无法读取</h2>
              <p>读取失败不代表记录不存在，请稍后重试。</p>
              <button class="d-btn primary" type="button" @click="reloadAll">重新读取</button>
            </div>
            <template v-else>
              <h2>收藏的场馆</h2>
              <div v-if="removedFavorite" class="d-notice" role="status">
                已移出收藏。<button class="d-link" type="button" data-test="undo-favorite" @click="undoFavorite">撤销</button>
              </div>
              <template v-if="favoriteVenues.length">
                <article v-for="v in favoriteVenues" :key="v.id" class="d-favorite">
                  <div>
                    <span class="d-pill">{{ venueKindLabel(v) }}</span>
                    <h3 style="margin-top:16px">{{ v.name }}</h3>
                    <p>{{ v.description || v.address }}</p>
                    <p style="margin-top:10px">营业时间 {{ v.openTime }}—{{ v.closeTime }}</p>
                    <button class="d-link" type="button" @click="openVenue(v.id)">查看场馆摘要 →</button>
                  </div>
                  <button class="d-btn" type="button" :data-test="`remove-favorite-${v.id}`" @click="removeFavorite(v.id)">取消收藏</button>
                </article>
              </template>
              <div v-else class="d-state">
                <h3>还没有收藏场馆</h3>
                <p>下次遇到喜欢的地方，可以把它留下来。</p>
              </div>
            </template>
          </section>

          <!-- 资料与偏好 -->
          <section v-else-if="section === 'settings'" class="d-limited">
            <h2>你的日常信息</h2>
            <section class="d-profile-section">
              <h3>基本资料</h3>
              <form @submit.prevent="openProfileSave">
                <div class="d-formgrid">
                  <label class="d-field">昵称<input v-model="profileForm.nickname" name="nickname" maxlength="64" required data-test="profile-nickname"></label>
                  <label class="d-field">手机号 <span class="d-muted">选填，11 位大陆手机号</span>
                    <input v-model="profileForm.phone" name="phone" type="tel" inputmode="numeric"
                      maxlength="11" placeholder="13800138000" autocomplete="tel"
                      data-test="profile-phone">
                  </label>
                </div>
                <p v-if="profilePhoneError" class="d-error" role="alert" data-test="profile-phone-error">{{ profilePhoneError }}</p>
                <label class="d-field">邮箱 <span class="d-muted">当前 {{ maskEmail(profile?.email) }}；留空保留当前邮箱</span>
                  <input v-model="profileForm.email" name="email" type="email" maxlength="128" :placeholder="`使用 ${maskEmail(profile?.email) || '邮箱'} 以外的地址替换`" data-test="profile-email">
                </label>
                <details>
                  <summary>头像图片地址（选填）</summary>
                  <label class="d-field">图片地址<input v-model="profileForm.avatar" name="avatar" type="url" maxlength="512"></label>
                  <p class="d-muted">仅保存图片地址，可随时更换。</p>
                </details>
                <button class="d-btn primary" type="submit" data-test="profile-save">保存资料</button>
              </form>
            </section>
            <section class="d-profile-section">
              <h3>通知与隐私</h3>
              <div v-if="settingsState === 'error'" class="d-state" role="alert">
                <h2>这部分内容暂时无法读取</h2>
                <p>读取失败不代表记录不存在，请稍后重试。</p>
                <button class="d-btn primary" type="button" @click="reloadAll">重新读取</button>
              </div>
              <template v-else>
              <p class="d-muted">三项独立选择，初始都关闭。</p>
              <div v-for="pref in preferenceRows" :key="pref.key" class="d-switchrow">
                <div>
                  <strong>{{ pref.title }}</strong>
                  <p>{{ pref.description }}</p>
                </div>
                <button
                  class="d-switch"
                  type="button"
                  role="switch"
                  :aria-checked="pref.value"
                  :aria-label="pref.title"
                  :data-test="`pref-${pref.key}`"
                  @click="openPreference(pref.key, $event)"
                ></button>
              </div>
              </template>
            </section>
            <section class="d-profile-section">
              <h3>账号密码</h3>
              <p class="d-muted">修改时需要当前密码，提交前再确认新密码。</p>
              <button class="d-btn" type="button" data-test="password-open" @click="openPassword">修改密码</button>
            </section>
          </section>
        </template>
        <div v-if="section === 'overview'" class="d-personal-areas" aria-label="个人功能入口">
          <section v-for="area in personalAreas" :key="area.title" class="d-personal-area" :aria-label="area.title">
            <h2>{{ area.title }}</h2>
            <p class="d-muted">{{ area.intro }}</p>
            <div class="d-area-links">
              <router-link v-for="link in area.links" :key="link.label" :to="link.to">{{ link.label }}<span aria-hidden="true">↗</span></router-link>
            </div>
          </section>
        </div>
      </div>
    </div>

    <dialog ref="dialogEl" aria-labelledby="dialog-title" @close="onDialogClose">
      <form v-if="dialog && dialog.kind" @submit.prevent="confirmDialog">
        <h2 id="dialog-title">{{ dialog.title }}</h2>
        <template v-if="dialog.kind === 'password'">
          <label class="d-field">当前密码<input v-model="dialog.old" name="old" type="password" autocomplete="current-password" required data-test="password-old"></label>
          <label class="d-field">新密码<input v-model="dialog.next" name="new" type="password" autocomplete="new-password" minlength="6" maxlength="64" required data-test="password-new"></label>
          <label class="d-field">再次输入新密码<input v-model="dialog.confirm" name="confirm" type="password" autocomplete="new-password" required></label>
          <p class="d-muted">新密码 6—64 个字符，UTF-8 编码不超过 72 字节。修改成功后请使用新密码重新登录。</p>
        </template>
        <template v-else-if="dialog.kind === 'profile-save'">
          <p>昵称：{{ dialog.profileDraft?.nickname }}</p>
          <p>联系信息只用于预约与通知；保存后可继续编辑。</p>
        </template>
        <template v-else-if="dialog.kind === 'preference'">
          <p>{{ dialog.preferenceValue ? '关闭' : '开启' }}{{ dialog.preferenceLabel }}。</p>
          <p>只改变{{ dialog.preferenceLabel }}这一项，其余偏好保留当前选择。</p>
          <p class="d-muted">{{ dialog.preferenceKey === 'transactionalEmailEnabled' ? '事务邮件设置不影响短信与站内消息。' : '广告展示与测量授权相互独立，可随时回到这里修改。' }}</p>
        </template>
        <template v-else-if="dialog.kind === 'read-all'">
          <p>把当前消息标为已读。</p>
          <p class="d-muted">消息仍会保留在全部消息中，之后可以继续查阅。</p>
        </template>
        <template v-else-if="dialog.kind === 'feedback-new'">
          <label class="d-field">反馈类型
            <select v-model="dialog.fbType" data-test="feedback-type">
              <option value="complaint">说明问题 / 投诉</option>
              <option value="review">评价体验</option>
            </select>
          </label>
          <label class="d-field">关联范围
            <select v-model="dialog.fbSource" data-test="feedback-source">
              <template v-if="dialog.fbType === 'complaint'">
                <option v-for="opt in complaintSources" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </template>
              <template v-else>
                <option v-if="reviewCandidates.length" v-for="opt in reviewCandidates" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                <option v-else value="">暂无可评价的已完成预约</option>
              </template>
            </select>
          </label>
          <template v-if="dialog.fbType === 'complaint'">
            <div class="d-formgrid">
              <label class="d-field">问题类型
                <select v-model="dialog.problem" data-test="complaint-type">
                  <option v-for="t in COMPLAINT_TYPES" :key="t" :value="t">{{ t }}</option>
                </select>
              </label>
              <label class="d-field">影响程度
                <select v-model="dialog.severity" data-test="complaint-severity">
                  <option v-for="s in COMPLAINT_SEVERITIES" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
            </div>
            <label class="d-field">问题标题<input v-model="dialog.titleText" maxlength="100" required placeholder="用一句话说明遇到的问题" data-test="complaint-title"></label>
            <label class="d-field">希望怎样处理<input v-model="dialog.resolution" maxlength="200" placeholder="选填，说明你的诉求" data-test="complaint-resolution"></label>
          </template>
          <template v-else>
            <div class="d-formgrid">
              <label class="d-field">整体评分
                <select v-model="dialog.rating" data-test="review-rating">
                  <option v-for="n in RATING_OPTIONS" :key="n" :value="String(n)">{{ n }} 分</option>
                </select>
              </label>
              <label class="d-field">体验标签<input v-model="dialog.tag" placeholder="空间舒适，服务细致" data-test="review-tag"></label>
            </div>
            <details>
              <summary>补充场馆、场地与服务评分（选填）</summary>
              <div class="d-formgrid">
                <label v-for="(label, index) in ['场馆', '场地', '服务']" :key="label" class="d-field">{{ label }}评分
                  <select :value="dialog.subRatings?.[index] || ''" @change="dialog.subRatings![index] = ($event.target as HTMLSelectElement).value">
                    <option value="">未评分</option>
                    <option v-for="n in RATING_OPTIONS" :key="n" :value="String(n)">{{ n }} 分</option>
                  </select>
                </label>
              </div>
            </details>
          </template>
          <label class="d-field">具体内容<textarea v-model="dialog.body" required maxlength="2000" placeholder="描述你实际遇到的情况，避免填写完整个人信息。" data-test="feedback-body"></textarea></label>
        </template>
        <template v-else-if="dialog.kind === 'venue'">
          <span class="d-pill">{{ dialog.venue && venueKindLabel(dialog.venue) }}</span>
          <p style="margin-top:20px">{{ dialog.venue?.description }}</p>
          <p>{{ dialog.venue?.address }}</p>
          <p>营业时间 {{ dialog.venue?.openTime }}—{{ dialog.venue?.closeTime }}</p>
          <p class="d-muted">开放时间为场馆信息，不表示当前存在可预约库存。</p>
        </template>
        <p v-if="dialog.error" class="d-error" role="alert">{{ dialog.error }}</p>
        <div class="d-dialog-actions">
          <button class="d-btn" type="button" @click="closeDialog">{{ dialog.kind === 'venue' ? '关闭' : '暂不操作' }}</button>
          <button v-if="dialog.kind !== 'venue'" class="d-btn primary" type="submit" :data-test="`confirm-${dialog.kind}`" :disabled="dialog.busy">
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
import { authApi } from '@/api/auth'
import { noticeApi } from '@/api/notice'
import { feedbackApi } from '@/api/feedback'
import { funnelApi } from '@/api/funnel'
import { reservationApi } from '@/api/reservation'
import { venueApi } from '@/api/venue'
import { advertisingApi } from '@/api/advertising'
import type { Message, Venue } from '@/api/types'
import {
  COMPLAINT_SEVERITIES,
  COMPLAINT_TYPES,
  PERSONAL_SECTIONS,
  PERSONAL_AREAS,
  RATING_OPTIONS,
  complaintSeverityEnum,
  complaintTypeEnum,
  caseStatusAttention,
  caseStatusLabel,
  complaintSeverityLabel,
  complaintTypeLabel,
  messageTarget,
  messageTypePill,
  reviewStatusLabel,
  type PersonalSection
} from './personalShared'
import { ensureSpaceNames, spaceNameOf } from '../transactions/spaceNameLookup'
import { maskEmail, maskPhone, timeRange, whenLabel } from '../transactions/transactionsShared'
import { useMenuStore } from '@/stores/menu'
import { useAppStore } from '@/stores/app'
import { OPTIONAL_CHINA_MOBILE_MESSAGE, isValidOptionalChinaMobile } from '@/utils/phone'

const route = useRoute()
const router = useRouter()
const menu = useMenuStore()
const app = useAppStore()
const personalAreas = computed(() => PERSONAL_AREAS.map(area => ({
  ...area, links: area.links.filter(link => !link.permission || menu.hasPermission(link.permission))
})).filter(area => area.links.length))
const visibleSections = computed(() => PERSONAL_SECTIONS.filter(([key]) => key === 'feedback' ? canFeedback.value
  : key === 'favorites' ? canFavorites.value : key === 'settings' || key === 'overview' ? menu.hasPermission('user:view') : true))
const sectionLabel = computed(() => PERSONAL_SECTIONS.find(([key]) => key === section.value)?.[1] || '个人中心')

const isolated = { skipGlobalErrorRedirect: true, skipGlobalErrorToast: true }
const PAGE_SIZE = 100

// 各服务域独立降级（G-P2）：任一域失败只影响对应区，本人身份（users/me）失败才是整页错误
type DomainState = 'ready' | 'denied' | 'error'
const stateOf = (error: unknown): DomainState =>
  (error as { response?: { status?: number } })?.response?.status === 403 ? 'denied' : 'error'

const rootEl = ref<HTMLElement | null>(null)
const loading = ref(true)
const loadError = ref(false)
const denied = ref(false)
const section = ref<PersonalSection>('overview')
const readonlyBusy = ref(false)
const messagesState = ref<DomainState>('ready')
const reservationsState = ref<DomainState>('ready')
const favoritesState = ref<DomainState>('ready')
const feedbackState = ref<DomainState>('ready')
const settingsState = ref<DomainState>('ready')
const canFeedback = computed(() => menu.hasPermission('reservation:my'))
const canFavorites = computed(() => menu.hasPermission('funnel:favorite:me'))

interface Profile {
  nickname?: string
  phone?: string
  email?: string
  avatar?: string
}
const profile = ref<Profile | null>(null)
const unreadCount = ref(0)
const messages = ref<Message[]>([])
const unreadOnly = ref(false)
const favorites = ref<Array<{ id: number; venueId: number }>>([])
const venuesById = ref<Map<number, Venue>>(new Map())
const complaints = ref<Array<Record<string, unknown>>>([])
const reviews = ref<Array<Record<string, unknown>>>([])
const cases = ref<Array<Record<string, unknown>>>([])
const reservations = ref<Array<Record<string, unknown>>>([])
const emailPreference = ref<{ transactionalEmailEnabled: boolean; maskedEmail?: string } | null>(null)
const advertisingPrefs = ref<{ commercialEnabled: boolean; measurementEnabled: boolean } | null>(null)

const feedbackId = ref<string>('')
const removedFavorite = ref<number | null>(null)
const profileForm = reactive({ nickname: '', phone: '', email: '', avatar: '' })
/** 提交前提示：与服务端 `@OptionalChinaMobile` 一致；服务端错误仍在确认弹层内展示。 */
const profilePhoneError = ref('')
watch(() => profileForm.phone, (value) => {
  if (profilePhoneError.value && isValidOptionalChinaMobile(value)) {
    profilePhoneError.value = ''
  }
})

const heading = computed(() => {
  if (section.value === 'overview') return `你好，${profile.value?.nickname || '朋友'}。`
  if (section.value === 'messages') return '需要知道的变化，都留在这里。'
  if (section.value === 'feedback') return '把你的体验，说给我们听。'
  if (section.value === 'favorites') return '喜欢的地方，留着下次再去。'
  return '资料与偏好，由你来决定。'
})
const intro = computed(() => {
  if (section.value === 'overview') return '从消息到反馈，从日常资料到下一次安排，把与你有关的事情接着处理好。'
  if (section.value === 'messages') return '预约、付款和退款结果集中呈现。每条消息保留来源，方便回到对应记录。'
  if (section.value === 'feedback') return '提交评价或说明遇到的问题，也能查看属于你的处理记录。'
  if (section.value === 'favorites') return '收藏方便下次找到场馆；开放时间与是否可预约仍以实时场馆信息为准。'
  return '修改常用信息，分别决定事务邮件、商业广告和广告测量偏好。'
})

const latestMessage = computed(() => messages.value.find(m => !m.readFlag) || messages.value[0] || null)

// 参考为客户端过滤：只看未读时仅显示未读消息
const visibleMessages = computed(() => (unreadOnly.value ? messages.value.filter(m => !m.readFlag) : messages.value))

const nextUp = computed(() => reservations.value
  .filter(r => r.status === 'PAID')
  .sort((a, b) => String(a.startTime).localeCompare(String(b.startTime)))[0] || null)

interface FeedbackRow {
  key: string
  kind: 'complaint' | 'review' | 'case'
  kindLabel: string
  id: number
  title: string
  body: string
  venueName: string
  venueId?: number
  reservationId?: number
  caseNo?: string
  problem?: string
  severity?: string
  ratings?: Array<number | undefined>
  tags?: string
  status: string
  attention: boolean
  rating?: number
  createTime?: string
}
const feedbackList = ref<FeedbackRow[]>([])

const pendingFeedback = computed(() => feedbackList.value.find(f => f.kind === 'complaint' && f.attention) || null)

const favoriteVenues = computed(() => favorites.value
  .map(f => venuesById.value.get(f.venueId))
  .filter((v): v is Venue => Boolean(v)))

const preferenceRows = computed(() => [
  {
    key: 'transactionalEmailEnabled' as const,
    title: '接收事务邮件',
    description: `预约与支付相关的邮件。发送地址 ${emailPreference.value?.maskedEmail || maskEmail(profile.value?.email)}；开启仅表示允许尝试发送，不保证邮件送达。`,
    value: emailPreference.value?.transactionalEmailEnabled ?? false
  },
  {
    key: 'commercialEnabled' as const,
    title: '展示商业广告',
    description: '允许在既有商业广告位置展示内容，不改变你的预约和会员权益。',
    value: advertisingPrefs.value?.commercialEnabled ?? false
  },
  {
    key: 'measurementEnabled' as const,
    title: '参与广告效果测量',
    description: '允许记录已有广告效果测量。与是否展示商业广告分别选择。',
    value: advertisingPrefs.value?.measurementEnabled ?? false
  }
])

const complaintSources = computed(() => {
  const options = reservations.value
    .filter(r => String(r.status) !== 'REJECTED' && String(r.status) !== 'CANCELED')
    .slice(0, 8)
    .map(r => ({
      value: `reservation-${r.id}`,
      label: `${venueNameOf(Number(r.venueId))} · ${spaceNameOf(Number(r.spaceId)) || `预约 #${r.id}`}（${whenLabel(String(r.startTime))}）`
    }))
  const venueOptions = [...venuesById.value.values()]
    .map(v => ({ value: `venue-${v.id}`, label: `${v.name} · 场馆反馈` }))
  return [...options, ...venueOptions]
})

const reviewCandidates = computed(() => {
  const reviewedReservationIds = new Set(
    reviews.value.map(r => Number(r.reservationId)).filter(id => !Number.isNaN(id) && id > 0)
  )
  return reservations.value
    .filter(r => r.status === 'COMPLETED' && !reviewedReservationIds.has(Number(r.id)))
    .map(r => ({
      value: `reservation-${r.id}`,
      label: `${venueNameOf(Number(r.venueId))} · 已完成 ${spaceNameOf(Number(r.spaceId)) || `预约 #${r.id}`}`
    }))
})

function venueNameOf(venueId: number): string {
  return venuesById.value.get(venueId)?.name || '场馆'
}

const resId = (row: Record<string, unknown>): number => Number(row.id)
const resVenueName = (row: Record<string, unknown>): string => venueNameOf(Number(row.venueId))
const resSpaceName = (row: Record<string, unknown>): string => spaceNameOf(Number(row.spaceId))
const resWhen = (row: Record<string, unknown>): string =>
  `${whenLabel(String(row.startTime))} ${timeRange(String(row.startTime), String(row.endTime))}`

function venueKindLabel(venue: Venue | null): string {
  if (!venue) return ''
  return venue.area || '场馆'
}

function messageTime(value: string | null | undefined): string {
  if (!value) return ''
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  const now = new Date()
  const sameDay = parsed.toDateString() === now.toDateString()
  const yesterday = new Date(now.getTime() - 86400000).toDateString() === parsed.toDateString()
  const hm = `${String(parsed.getHours()).padStart(2, '0')}:${String(parsed.getMinutes()).padStart(2, '0')}`
  if (sameDay) return `今天 ${hm}`
  if (yesterday) return `昨天 ${hm}`
  return `${parsed.getMonth() + 1} 月 ${parsed.getDate()} 日 ${hm}`
}

// —— 反馈行建模 ——
function parseTags(raw: unknown): string | undefined {
  if (raw == null || raw === '') return undefined
  if (Array.isArray(raw)) return raw.map(String).join('、') || undefined
  try {
    const parsed = JSON.parse(String(raw))
    if (Array.isArray(parsed)) return parsed.map(String).join('、') || undefined
  } catch {
    // 非 JSON 数组按原文展示
  }
  return String(raw)
}

function buildFeedbackRows(): void {
  const rows: FeedbackRow[] = []
  for (const c of complaints.value) {
    const id = Number(c.id)
    const caseRow = cases.value.find(item => Number(item.complaintId) === id)
    rows.push({
      key: `complaint-${id}`,
      kind: 'complaint',
      kindLabel: '投诉',
      id,
      title: String(c.title || '投诉反馈'),
      body: String(c.contentSanitized || ''),
      venueName: venueNameOf(Number(c.venueId)),
      venueId: c.venueId != null ? Number(c.venueId) : undefined,
      reservationId: c.reservationId != null ? Number(c.reservationId) : undefined,
      caseNo: caseRow?.caseNo ? String(caseRow.caseNo) : undefined,
      problem: c.complaintType ? complaintTypeLabel(String(c.complaintType)) : undefined,
      severity: c.severity ? complaintSeverityLabel(String(c.severity)) : undefined,
      status: caseStatusLabel((caseRow?.status ?? c.status) as string),
      attention: caseStatusAttention((caseRow?.status ?? c.status) as string),
      createTime: String(c.submittedAt || c.createTime || '')
    })
  }
  for (const r of reviews.value) {
    const id = Number(r.id)
    rows.push({
      key: `review-${id}`,
      kind: 'review',
      kindLabel: '评价',
      id,
      title: String(r.title || '我的场馆体验评价'),
      body: String(r.contentSanitized || ''),
      venueName: venueNameOf(Number(r.venueId)),
      venueId: r.venueId != null ? Number(r.venueId) : undefined,
      reservationId: r.reservationId != null ? Number(r.reservationId) : undefined,
      ratings: [
        r.venueRating != null ? Number(r.venueRating) : undefined,
        r.spaceRating != null ? Number(r.spaceRating) : undefined,
        r.serviceRating != null ? Number(r.serviceRating) : undefined
      ],
      tags: parseTags(r.tagsJson),
      status: reviewStatusLabel(r.status as string),
      attention: false,
      rating: r.overallRating != null ? Number(r.overallRating) : undefined,
      createTime: String(r.submittedAt || r.createTime || '')
    })
  }
  feedbackList.value = rows
}

interface FeedbackEvent {
  label: string
  time: string
  note: string
}
const feedbackDetail = ref<(FeedbackRow & { events: FeedbackEvent[] }) | null>(null)

// 参考详情行：整体 X 分 · 场馆 X / 场地 X / 服务 X
const detailRatingsText = computed(() => {
  const detail = feedbackDetail.value
  if (!detail || detail.kind !== 'review') return ''
  return ['场馆', '场地', '服务']
    .map((label, index) => `${label} ${detail.ratings?.[index] ?? '未评分'}`)
    .join(' / ')
})

function feedbackRefLabel(row: FeedbackRow): string {
  return row.reservationId ? `预约 #${row.reservationId}` : row.venueName
}

const EVENT_LABELS: Record<string, string> = {
  COMPLAINT_SUBMITTED: '反馈已提交',
  COMPLAINT_TRIAGED: '问题已分派',
  CUSTOMER_SERVICE_CASE_CREATED: '客服工单已建立',
  CUSTOMER_SERVICE_CASE_ASSIGNED: '客服工单已分派',
  CUSTOMER_SERVICE_CASE_STATUS_CHANGED: '处理状态更新',
  CUSTOMER_SERVICE_CASE_RESOLVED: '已解决',
  CUSTOMER_SERVICE_CASE_CLOSED: '已关闭',
  REPLY_ADDED: '客服回复',
  REVIEW_SUBMITTED: '评价已提交',
  REVIEW_MODERATED: '评价审核完成'
}

async function loadFeedbackDetail(key: string): Promise<void> {
  const row = feedbackList.value.find(f => f.key === key)
  if (!row) {
    feedbackDetail.value = null
    return
  }
  const events: FeedbackEvent[] = []
  if (row.kind === 'complaint') {
    const caseRow = cases.value.find(item => Number(item.complaintId) === row.id)
    if (caseRow) {
      try {
        const eventsPage = await feedbackApi.myCaseEvents(Number(caseRow.id), isolated)
        for (const ev of eventsPage) {
          events.push({
            label: EVENT_LABELS[String(ev.eventType)] || '处理进展',
            time: messageTime(ev.occurredAt),
            note: ev.noteSanitized || ''
          })
        }
      } catch {
        // 事件读取失败保留基础记录
      }
    }
  }
  if (!events.length) {
    events.push({
      label: row.kind === 'review' ? '评价已提交' : '反馈已提交',
      time: messageTime(row.createTime),
      note: row.kind === 'review' ? '评价已收到，审核后决定公开。' : '问题已收到，处理结果会保留在本人记录中。'
    })
  }
  feedbackDetail.value = { ...row, events }
}

// —— 路由 ——
function parseHash(): void {
  const hash = route.hash
  if (dialog.value) closeDialog()
  feedbackId.value = ''
  if (hash.startsWith('#feedback-')) {
    section.value = 'feedback'
    feedbackId.value = hash.slice('#feedback-'.length)
    void loadFeedbackDetail(feedbackId.value)
  } else if (hash.startsWith('#new-feedback-')) {
    section.value = 'feedback'
    feedbackId.value = ''
    const source = Number(hash.slice('#new-feedback-'.length))
    void nextTick(() => openFeedbackNew(Number.isNaN(source) ? null : source))
  } else if (route.query.case) {
    const caseId = Number(route.query.case)
    const caseRow = cases.value.find(item => Number(item.id) === caseId)
    section.value = 'feedback'
    feedbackId.value = caseRow ? `complaint-${caseRow.complaintId}` : ''
    void loadFeedbackDetail(feedbackId.value)
  } else if (PERSONAL_SECTIONS.some(([key]) => key === hash.slice(1))) {
    section.value = hash.slice(1) as PersonalSection
  }
}

function initialSection(): PersonalSection {
  if (route.name === 'Favorites') return 'favorites'
  if (route.name === 'Messages') return 'messages'
  if (route.name === 'MyFeedback') return 'feedback'
  if (route.name === 'Profile') return 'settings'
  return 'overview'
}

function selectSection(key: PersonalSection): void {
  section.value = key
  feedbackId.value = ''
  const { case: _feedbackCase, ...query } = route.query
  router.push({ query, hash: `#${key}` })
  nextTick(() => {
    (document.querySelector(`[data-view="${key}"]`) as HTMLElement | null)?.focus({ preventScroll: true })
  })
}

function gotoFeedback(row: FeedbackRow): void {
  section.value = 'feedback'
  feedbackId.value = row.key
  router.push({ hash: `#feedback-${row.key}` })
  void loadFeedbackDetail(row.key)
}

function closeFeedbackDetail(): void {
  feedbackId.value = ''
  router.push({ hash: '#feedback' })
}

function setUnreadOnly(value: boolean): void {
  unreadOnly.value = value
}

// —— 数据 ——
async function load(): Promise<void> {
  loading.value = true
  loadError.value = false
  denied.value = false
  messagesState.value = 'ready'
  favoritesState.value = 'ready'
  feedbackState.value = 'ready'
  settingsState.value = 'ready'
  try {
    const me = await authApi.me()
    profile.value = me as Profile
    profileForm.nickname = (me as Profile).nickname || ''
    profileForm.phone = (me as Profile).phone || ''
    profileForm.email = ''
    profileForm.avatar = (me as Profile).avatar || ''
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    if (status === 403) denied.value = true
    else loadError.value = true
    loading.value = false
    return
  }

  // 各域独立读取：失败只降级对应区（G-P2），不阻塞其他区
  const jobs: Array<Promise<void>> = []
  jobs.push((async () => {
    try {
      const [messagePage, unread] = await Promise.all([
        noticeApi.messages({ pageNum: 1, pageSize: PAGE_SIZE }, isolated),
        noticeApi.unreadCount(isolated)
      ])
      messages.value = messagePage.records
      unreadCount.value = Number(unread) || 0
      app.setUnreadCount(unreadCount.value)
    } catch (error) {
      messages.value = []
      unreadCount.value = 0
      messagesState.value = stateOf(error)
    }
  })())
  jobs.push(loadFavorites())
  jobs.push(loadFeedbackData())
  jobs.push((async () => {
    try {
      const reservationPage = await reservationApi.my({ pageNum: 1, pageSize: PAGE_SIZE }, isolated)
      reservations.value = reservationPage.records as never
      reservationsState.value = 'ready'
    } catch {
      reservationsState.value = 'error'
      reservations.value = [] // 概览附加卡静默降级：缺数据只隐藏对应卡
    }
  })())
  jobs.push((async () => {
    try {
      const [emailPref, adPrefs] = await Promise.all([
        noticeApi.notificationPreferences(isolated),
        advertisingApi.preferences(isolated)
      ])
      emailPreference.value = emailPref as never
      advertisingPrefs.value = { commercialEnabled: adPrefs.commercialEnabled, measurementEnabled: adPrefs.measurementEnabled }
    } catch (error) {
      settingsState.value = stateOf(error)
    }
  })())
  await Promise.all(jobs)
  await enrichVenueDetails()
  buildFeedbackRows()
  loading.value = false
}

async function loadFavorites(): Promise<void> {
  if (!canFavorites.value) {
    favoritesState.value = 'denied'
    favorites.value = []
    return
  }
  try {
    favorites.value = (await funnelApi.myFavorites(isolated)) as never
  } catch (error) {
    favorites.value = []
    favoritesState.value = stateOf(error)
  }
}

async function loadFeedbackData(): Promise<void> {
  if (!canFeedback.value) {
    feedbackState.value = 'denied'
    complaints.value = []
    reviews.value = []
    cases.value = []
    return
  }
  try {
    const [complaintPage, reviewPage, casePage] = await Promise.all([
      feedbackApi.myComplaints({ pageNum: 1, pageSize: PAGE_SIZE }, isolated),
      feedbackApi.myReviews({ pageNum: 1, pageSize: PAGE_SIZE }, isolated),
      feedbackApi.myCases({ pageNum: 1, pageSize: PAGE_SIZE }, isolated)
    ])
    complaints.value = complaintPage.records as never
    reviews.value = reviewPage.records as never
    cases.value = (casePage.records ?? []) as never
  } catch (error) {
    complaints.value = []
    reviews.value = []
    cases.value = []
    feedbackState.value = stateOf(error)
  }
}

async function enrichVenueDetails(): Promise<void> {
  const venueIds = [...new Set<number>([
    ...favorites.value.map(f => f.venueId),
    ...reservations.value.map(r => Number(r.venueId)).filter(id => !Number.isNaN(id) && id > 0),
    ...complaints.value.map(c => Number(c.venueId)).filter(id => !Number.isNaN(id) && id > 0),
    ...reviews.value.map(r => Number(r.venueId)).filter(id => !Number.isNaN(id) && id > 0)
  ])]
  await Promise.all(venueIds.map(async id => {
    if (venuesById.value.has(id)) return
    try {
      venuesById.value.set(id, await venueApi.detail(id))
    } catch {
      // 场馆信息缺失时回退占位名
    }
  }))
  await ensureSpaceNames([...venuesById.value.keys()])
}

function reloadAll(): void {
  void load().then(() => { section.value = initialSection(); parseHash() })
}

// —— 弹层 ——
// 参考偏好弹层的短标签映射（弹层标题与“只改变…这一项”用短标签）
const PREFERENCE_SHORT_LABELS: Record<'transactionalEmailEnabled' | 'commercialEnabled' | 'measurementEnabled', string> = {
  transactionalEmailEnabled: '事务邮件',
  commercialEnabled: '商业广告展示',
  measurementEnabled: '广告效果测量'
}

interface DialogState {
  kind: 'password' | 'profile-save' | 'preference' | 'read-all' | 'feedback-new' | 'venue'
  title: string
  submit: string
  error: string
  busy: boolean
  old?: string
  next?: string
  confirm?: string
  profileDraft?: { nickname: string; phone: string; email: string; avatar: string }
  preferenceKey?: 'transactionalEmailEnabled' | 'commercialEnabled' | 'measurementEnabled'
  preferenceLabel?: string
  preferenceValue?: boolean
  fbType?: 'complaint' | 'review'
  fbSource?: string
  problem?: string
  severity?: string
  titleText?: string
  resolution?: string
  rating?: string
  tag?: string
  subRatings?: string[]
  body?: string
  venue?: Venue | null
}

const dialogEl = ref<HTMLDialogElement | null>(null)
const dialog = ref<DialogState | null>(null)
let lastTrigger: HTMLElement | null = null

const readableError = (error: unknown, fallback: string) => {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (responseMessage) return responseMessage
  return error instanceof Error && error.message ? error.message : fallback
}

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
  else (document.querySelector('#personal-content') as HTMLElement | null)?.focus({ preventScroll: true })
}

function openPassword(event?: Event): void {
  openDialog({
    kind: 'password', title: '修改账号密码', submit: '确认修改', error: '', busy: false,
    old: '', next: '', confirm: ''
  }, event ?? null)
}

function openProfileSave(event: Event): void {
  if (!isValidOptionalChinaMobile(profileForm.phone)) {
    profilePhoneError.value = OPTIONAL_CHINA_MOBILE_MESSAGE
    return
  }
  profilePhoneError.value = ''
  openDialog({
    kind: 'profile-save', title: '确认保存资料', submit: '保存资料', error: '', busy: false,
    profileDraft: { ...profileForm }
  }, event)
}

function openPreference(key: 'transactionalEmailEnabled' | 'commercialEnabled' | 'measurementEnabled', event?: Event): void {
  const row = preferenceRows.value.find(item => item.key === key)!
  const shortLabel = PREFERENCE_SHORT_LABELS[key]

  openDialog({
    kind: 'preference',
    title: `${row?.value ? '关闭' : '开启'}${shortLabel}`,
    submit: '保存这一项选择',
    error: '', busy: false,
    preferenceKey: key,
    preferenceLabel: shortLabel,
    preferenceValue: row?.value ?? false
  }, event ?? null)
}

function openReadAll(event?: Event): void {
  openDialog({ kind: 'read-all', title: '把当前消息标为已读', submit: '全部标为已读', error: '', busy: false }, event ?? null)
}

function openVenue(venueId: number, event?: Event): void {
  openDialog({ kind: 'venue', title: venueNameOf(venueId), submit: '关闭', error: '', busy: false, venue: venuesById.value.get(venueId) ?? null }, event ?? null)
}

function openFeedbackNew(sourceReservationId: number | null = null, event?: Event): void {
  openDialog({
    kind: 'feedback-new', title: '提交你的体验与问题', submit: '提交反馈', error: '', busy: false,
    fbType: 'complaint',
    fbSource: sourceReservationId != null ? `reservation-${sourceReservationId}` : (complaintSources.value[0]?.value || ''),
    problem: COMPLAINT_TYPES[0],
    severity: COMPLAINT_SEVERITIES[0],
    titleText: '', resolution: '', rating: '5', tag: '', subRatings: ['', '', ''], body: ''
  }, event ?? null)
}

async function confirmDialog(): Promise<void> {
  const state = dialog.value
  if (!state || state.busy) return
  state.busy = true
  state.error = ''
  try {
    if (state.kind === 'password') {
      if (state.next !== state.confirm) {
        state.error = '两次新密码不一致，请重新核对。'
        return
      }
      if (new TextEncoder().encode(state.next || '').length > 72) {
        state.error = '新密码编码超过 72 字节，请缩短后重试。'
        return
      }
      await authApi.changePassword({ oldPassword: state.old || '', newPassword: state.next || '' }, isolated)
    }
    if (state.kind === 'profile-save' && state.profileDraft) {
      const draft = state.profileDraft
      const savedEmail = draft.email || profile.value?.email || ''
      await authApi.updateProfile({
        nickname: draft.nickname,
        phone: draft.phone,
        email: savedEmail,
        avatar: draft.avatar
      }, isolated)
      profile.value = { ...profile.value, nickname: draft.nickname, phone: draft.phone, email: savedEmail, avatar: draft.avatar }
    }
    if (state.kind === 'preference' && state.preferenceKey) {
      const key = state.preferenceKey
      if (key === 'transactionalEmailEnabled') {
        const nextValue = !state.preferenceValue
        const updated = await noticeApi.updateNotificationPreferences({ transactionalEmailEnabled: nextValue }, isolated)
        emailPreference.value = updated as never
      } else {
        const current = advertisingPrefs.value || { commercialEnabled: false, measurementEnabled: false }
        const payload = key === 'commercialEnabled'
          ? { commercialEnabled: !current.commercialEnabled, measurementEnabled: current.measurementEnabled }
          : { commercialEnabled: current.commercialEnabled, measurementEnabled: !current.measurementEnabled }
        const updated = await advertisingApi.updatePreferences(payload, isolated)
        advertisingPrefs.value = { commercialEnabled: updated.commercialEnabled, measurementEnabled: updated.measurementEnabled }
      }
    }
    if (state.kind === 'read-all') {
      await noticeApi.markRead({ entry: 'PERSONAL_CENTER' }, isolated)
      messages.value = messages.value.map(m => ({ ...m, readFlag: 1 }))
      unreadCount.value = 0
      app.clearUnreadCount()
    }
    if (state.kind === 'feedback-new') {
      await submitFeedback(state)
    }
    closeDialog()
    await load()
  } catch (error) {
    state.error = readableError(error, '保存未完成，输入与原选择已保留。请重试。')
  } finally {
    state.busy = false
  }
}

function resolveSource(source: string): { reservationId?: number; venueId?: number } {
  if (source.startsWith('reservation-')) return { reservationId: Number(source.slice('reservation-'.length)) }
  if (source.startsWith('venue-')) return { venueId: Number(source.slice('venue-'.length)) }
  return {}
}

async function submitFeedback(state: DialogState): Promise<void> {
  const source = resolveSource(state.fbSource || '')
  if (state.fbType === 'review') {
    if (!source.reservationId) throw new Error('请选择尚未评价的本人已完成预约。')
    const reservation = reservations.value.find(r => Number(r.id) === source.reservationId)
    await feedbackApi.submitReview({
      venueId: Number(reservation?.venueId ?? source.venueId),
      spaceId: reservation?.spaceId != null ? Number(reservation.spaceId) : undefined,
      reservationId: source.reservationId,
      orderId: reservation?.orderId != null ? Number(reservation.orderId) : undefined,
      overallRating: Number(state.rating || '5'),
      venueRating: state.subRatings?.[0] ? Number(state.subRatings[0]) : undefined,
      spaceRating: state.subRatings?.[1] ? Number(state.subRatings[1]) : undefined,
      serviceRating: state.subRatings?.[2] ? Number(state.subRatings[2]) : undefined,
      tags: state.tag ? state.tag.split(/[,，、]/).map(item => item.trim()).filter(Boolean) : undefined,
      contentText: state.body || ''
    }, isolated)
    section.value = 'feedback'
    feedbackId.value = ''
    router.push({ hash: '#feedback' })
    return
  }
  const reservation = source.reservationId != null ? reservations.value.find(r => Number(r.id) === source.reservationId) : null
  await feedbackApi.submitComplaint({
    venueId: Number(reservation?.venueId ?? source.venueId),
    spaceId: reservation?.spaceId != null ? Number(reservation.spaceId) : undefined,
    reservationId: reservation?.id != null ? Number(reservation.id) : undefined,
    orderId: reservation?.orderId != null ? Number(reservation.orderId) : undefined,
    complaintType: complaintTypeEnum(state.problem || '其他'),
    severity: complaintSeverityEnum(state.severity || '中'),
    title: state.titleText || '',
    contentText: state.body || '',
    requestedResolution: state.resolution || undefined
  }, isolated)
  section.value = 'feedback'
  feedbackId.value = ''
  router.push({ hash: '#feedback' })
}

// —— 消息与收藏动作 ——
async function markOne(id: number): Promise<void> {
  if (readonlyBusy.value) return
  readonlyBusy.value = true
  try {
    await noticeApi.markOneRead(id, { entry: 'PERSONAL_CENTER' }, isolated)
    messages.value = messages.value.map(m => (m.id === id ? { ...m, readFlag: 1 } : m))
    app.setUnreadCount(Math.max(0, app.unreadCount - 1))
    unreadCount.value = app.unreadCount
    // 参考行为：按钮随行变为已读消失，焦点落到“只看未读”筛选按钮
    nextTick(() => (document.querySelector('[data-test="filter-unread"]') as HTMLElement | null)?.focus())
  } finally {
    readonlyBusy.value = false
  }
}

async function openMessageTarget(m: Message): Promise<void> {
  if (!m.readFlag) await markOne(m.id)
  const target = messageTarget(m)
  if (target) await router.push(target.route)
}

async function removeFavorite(venueId: number): Promise<void> {
  if (readonlyBusy.value) return
  readonlyBusy.value = true
  try {
    await funnelApi.cancelFavorite(venueId, {}, isolated)
    removedFavorite.value = venueId
    favorites.value = favorites.value.filter(f => f.venueId !== venueId)
    nextTick(() => (document.querySelector('[data-test="undo-favorite"]') as HTMLElement | null)?.focus())
  } finally {
    readonlyBusy.value = false
  }
}

async function undoFavorite(): Promise<void> {
  const venueId = removedFavorite.value
  if (venueId == null) return
  removedFavorite.value = null
  await funnelApi.favoriteVenue(venueId, {}, isolated)
  favorites.value = [...favorites.value, { id: Date.now(), venueId }]
  nextTick(() => (document.querySelector(`[data-test="remove-favorite-${venueId}"]`) as HTMLElement | null)?.focus())
}

const surfaceState = computed(() => {
  if (denied.value) {
    return {
      role: undefined as string | undefined,
      busy: false,
      title: '当前账号不能查看这部分内容',
      text: '不会展示其他账号的消息、反馈或个人信息。',
      primary: false,
      action: { label: '返回首页', handler: () => router.push('/') }
    }
  }
  if (loading.value) {
    return { role: undefined, busy: true, title: '正在读取你的个人记录', text: '每项内容都按当前本人账号读取。', primary: false, action: null }
  }
  if (loadError.value) {
    return {
      role: 'alert' as const,
      busy: false,
      title: '这部分内容暂时无法读取',
      text: '读取失败不代表记录不存在，请稍后重试。',
      primary: true,
      action: { label: '重新读取', handler: () => { void load() } }
    }
  }
  return null
})

onMounted(() => {
  section.value = initialSection()
  void load().then(() => { section.value = initialSection(); parseHash() })
})

watch(() => route.fullPath, () => {
  if (!loading.value) {
    section.value = initialSection()
    parseHash()
  }
})
</script>
