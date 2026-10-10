<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, Back, ChatDotRound, Check, Clock, Close, Delete, EditPen, Flag, Lock,
  MoreFilled, Refresh, Remove, Right, Setting, TopRight, User, View
} from '@element-plus/icons-vue'
import {
  communityApi,
  communityError,
  createIdempotencyRegistry,
  type CommunityGroup,
  type CommunityMember,
  type CommunityPost
} from '@/api/community'
import { systemApi } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { formatLongDate } from '@/utils/datetime'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import NativeDialog from './dialogs/NativeDialog.vue'
import ComposeDialog from './dialogs/ComposeDialog.vue'
import GroupFormDialog from './dialogs/GroupFormDialog.vue'
import ReportDialog from './dialogs/ReportDialog.vue'
import {
  avatarColorClass,
  excerptOf,
  initialOf,
  isGroupManager,
  isGroupOwner,
  joinActionText,
  joinPolicyLabel,
  listTimeText,
  memberBadgeText,
  memberRoleText,
  sceneForGroup,
  visibilityLabel
} from './communityShared'

/**
 * 小组详情（冻结参考 #group/{id}：帖子/成员/组内治理三页签）。
 * 成员、审核、治理动作全部使用真实服务端合同；仅成员小组对非成员以 G7 空列表语义表达，
 * 不在前端伪造成员身份或审核结果。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const groupId = computed(() => Number(route.params.id))
const tab = computed(() => {
  const value = String(route.query.tab ?? 'posts')
  return ['posts', 'members', 'governance'].includes(value) ? value : 'posts'
})

const group = ref<CommunityGroup | null>(null)
const notFound = ref(false)
const loadFailed = ref(false)
const posts = ref<CommunityPost[]>([])
const members = ref<CommunityMember[]>([])
const pendingMembers = ref<CommunityMember[]>([])
const moderationPosts = ref<CommunityPost[]>([])
const contentLoading = ref(false)
const contentError = ref('')
const nextCursor = ref<number | null>(null)
const hasMore = ref(false)
const loadingMore = ref(false)
const categories = ref<Array<{ value: string; label: string }>>([])

const manager = computed(() => isGroupManager(group.value))
const owner = computed(() => isGroupOwner(group.value))
const isActiveMember = computed(() => group.value?.currentMemberStatus === 'ACTIVE')
const isPendingMember = computed(() => group.value?.currentMemberStatus === 'PENDING')
const membersOnlyLocked = computed(() =>
  group.value?.visibility === 'MEMBERS_ONLY' && !isActiveMember.value
)

const categoryLabel = computed(() => {
  const code = group.value?.categoryCode
  return categories.value.find((item) => item.value === code)?.label ?? '其他'
})

const writes = createIdempotencyRegistry()

/* ---------- 读取 ---------- */

const loadGroup = async () => {
  notFound.value = false
  loadFailed.value = false
  group.value = null
  try {
    group.value = await communityApi.group(groupId.value)
    document.title = `${group.value.name} · 拾光`
  } catch (error) {
    const state = communityError(error)
    if (state.kind === 'missing' || state.kind === 'forbidden') notFound.value = true
    else loadFailed.value = true
  }
}

const loadContent = async () => {
  if (!group.value) return
  contentLoading.value = true
  contentError.value = ''
  posts.value = []
  members.value = []
  pendingMembers.value = []
  moderationPosts.value = []
  nextCursor.value = null
  hasMore.value = false
  try {
    if (tab.value === 'posts') {
      const page = await communityApi.posts({ groupId: groupId.value, limit: 20 })
      posts.value = page.records
      nextCursor.value = page.nextCursor
      hasMore.value = page.hasMore
    } else if (tab.value === 'members' && isActiveMember.value) {
      const page = await communityApi.members(groupId.value, { status: 'ACTIVE', limit: 20 })
      members.value = page.records
      nextCursor.value = page.nextCursor
      hasMore.value = page.hasMore
    } else if (tab.value === 'governance' && manager.value) {
      const [pending, moderation] = await Promise.all([
        communityApi.members(groupId.value, { status: 'PENDING', limit: 20 }),
        communityApi.posts({ groupId: groupId.value, status: 'ALL', limit: 20 })
      ])
      pendingMembers.value = pending.records
      moderationPosts.value = moderation.records
      nextCursor.value = moderation.nextCursor
      hasMore.value = moderation.hasMore
    }
  } catch (error) {
    contentError.value = communityError(error, '加载失败，请稍后重试').message
  } finally {
    contentLoading.value = false
  }
}

const loadMorePosts = async () => {
  if (!hasMore.value || nextCursor.value == null || loadingMore.value) return
  loadingMore.value = true
  try {
    const page = tab.value === 'posts'
      ? await communityApi.posts({ groupId: groupId.value, cursorId: nextCursor.value, limit: 20 })
      : await communityApi.members(groupId.value, { status: 'ACTIVE', cursorId: nextCursor.value, limit: 20 })
    if (tab.value === 'posts') posts.value = [...posts.value, ...(page.records as CommunityPost[])]
    else members.value = [...members.value, ...(page.records as CommunityMember[])]
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (error) {
    ElMessage({ message: communityError(error, '加载更多失败，请稍后重试').message, type: 'error' })
  } finally {
    loadingMore.value = false
  }
}

/* ---------- 加入 / 退出（真实命令；409 终态按 G6 映射） ---------- */

const joinBusy = ref(false)
const leaveConfirmOpen = ref(false)

const join = async () => {
  if (!group.value || joinBusy.value) return
  if (isActiveMember.value) {
    if (owner.value) {
      ElMessage({ message: '你是这个小组的创建者', type: 'info' })
      return
    }
    leaveConfirmOpen.value = true
    return
  }
  if (isPendingMember.value) {
    ElMessage({ message: '申请已提交，正在等待审核', type: 'info' })
    return
  }
  joinBusy.value = true
  try {
    await communityApi.joinGroup(group.value.id, writes.keyFor('join', { groupId: group.value.id }))
    writes.complete('join')
    const policy = group.value.joinPolicy
    await loadGroup()
    ElMessage({
      message: policy === 'DIRECT' ? `已加入${group.value?.name ?? '小组'}` : '申请已提交，等待小组审核',
      type: 'success'
    })
  } catch (error) {
    ElMessage({ message: communityError(error, '操作失败，请稍后重试').message, type: 'error' })
  } finally {
    joinBusy.value = false
  }
}

const leave = async () => {
  if (!group.value || joinBusy.value) return
  joinBusy.value = true
  try {
    await communityApi.leaveGroup(
      group.value.id,
      writes.keyFor('leave', { groupId: group.value.id }),
      // G6：409（跨幂等键重试）由下方映射为「已退出」终态，抑制拦截器的全局冲突 toast
      { skipGlobalErrorToast: true }
    )
    writes.complete('leave')
    leaveConfirmOpen.value = false
    await loadGroup()
    ElMessage({ message: '已退出小组', type: 'success' })
  } catch (error) {
    const state = communityError(error)
    // G6：跨幂等键重试得到 409 时，LEFT 已是成功终态，按终态刷新而非报错重试
    if (state.kind === 'conflict') {
      leaveConfirmOpen.value = false
      await loadGroup()
      ElMessage({ message: '已退出小组', type: 'success' })
    } else {
      ElMessage({ message: state.message, type: 'error' })
    }
  } finally {
    joinBusy.value = false
  }
}

/* ---------- 审核与组内治理（真实命令） ---------- */

const decide = async (member: CommunityMember, decision: 'APPROVE' | 'REJECT') => {
  try {
    await communityApi.decideMembership(
      groupId.value,
      member.id,
      { decision },
      writes.keyFor('membership-decision', { memberId: member.id, decision })
    )
    writes.complete('membership-decision')
    pendingMembers.value = pendingMembers.value.filter((item) => item.id !== member.id)
    ElMessage({ message: decision === 'APPROVE' ? '已通过加入申请' : '已拒绝加入申请', type: 'success' })
  } catch (error) {
    ElMessage({ message: communityError(error, '操作失败，请稍后重试').message, type: 'error' })
  }
}

const moderationTarget = ref<CommunityPost | null>(null)
const moderationOpen = ref(false)
const moderationReason = ref('OFF_TOPIC')
const moderationNote = ref('')
const moderationBusy = ref(false)

const openModeration = (post: CommunityPost) => {
  moderationTarget.value = post
  moderationReason.value = 'OFF_TOPIC'
  moderationNote.value = ''
  moderationOpen.value = true
}

const hidePost = async () => {
  const post = moderationTarget.value
  if (!post || moderationBusy.value) return
  moderationBusy.value = true
  try {
    await communityApi.groupModerate(
      groupId.value, 'POST', post.id, false,
      { reasonCode: moderationReason.value, internalNote: moderationNote.value || undefined },
      writes.keyFor('moderate', { targetType: 'POST', targetId: post.id, restore: false, reasonCode: moderationReason.value })
    )
    writes.complete('moderate')
    moderationOpen.value = false
    moderationPosts.value = moderationPosts.value.map((item) =>
      item.id === post.id ? { ...item, status: 'HIDDEN' } : item
    )
    ElMessage({ message: '内容已隐藏', type: 'success' })
  } catch (error) {
    ElMessage({ message: communityError(error, '操作失败，请稍后重试').message, type: 'error' })
  } finally {
    moderationBusy.value = false
  }
}

const restorePost = async (post: CommunityPost) => {
  if (moderationBusy.value) return
  moderationBusy.value = true
  try {
    await communityApi.groupModerate(
      groupId.value, 'POST', post.id, true,
      { reasonCode: 'RESTORE' },
      writes.keyFor('moderate', { targetType: 'POST', targetId: post.id, restore: true })
    )
    writes.complete('moderate')
    moderationPosts.value = moderationPosts.value.map((item) =>
      item.id === post.id ? { ...item, status: 'PUBLISHED' } : item
    )
    ElMessage({ message: '已恢复展示', type: 'success' })
  } catch (error) {
    ElMessage({ message: communityError(error, '操作失败，请稍后重试').message, type: 'error' })
  } finally {
    moderationBusy.value = false
  }
}

/* ---------- 组主治理：角色 / 移除 / 删组 / 按 ID 处置（G9 真实命令，能力承接旧六页签） ---------- */

const canManageMember = (member: CommunityMember) =>
  !!member && member.status === 'ACTIVE' && member.role !== 'OWNER'
  && member.userId !== auth.user?.id

const roleConfirmOpen = ref(false)
const roleTarget = ref<{ member: CommunityMember; role: 'ADMIN' | 'MEMBER' } | null>(null)
const roleBusy = ref(false)

const openRoleChange = (member: CommunityMember) => {
  if (!owner.value || !member.id) return
  roleTarget.value = { member, role: member.role === 'ADMIN' ? 'MEMBER' : 'ADMIN' }
  memberMenuOpen.value = false
  roleConfirmOpen.value = true
}

const confirmRoleChange = async () => {
  const target = roleTarget.value
  if (!target || roleBusy.value) return
  roleBusy.value = true
  try {
    const payload = { memberId: target.member.id, role: target.role }
    const updated = await communityApi.changeMemberRole(
      groupId.value, target.member.id, target.role,
      writes.keyFor('member-role', payload)
    )
    writes.complete('member-role')
    // 角色更新响应为实体重载（无 user 摘要），只合并角色/状态字段，避免行内昵称被占位符替换
    members.value = members.value.map((item) => (
      item.id === updated.id ? { ...item, role: updated.role, status: updated.status } : item
    ))
    roleConfirmOpen.value = false
    ElMessage({ message: target.role === 'ADMIN' ? '已设为管理员' : '已设为普通成员', type: 'success' })
  } catch (error) {
    ElMessage({ message: communityError(error, '角色更新失败，请稍后重试').message, type: 'error' })
  } finally {
    roleBusy.value = false
  }
}

const removeConfirmOpen = ref(false)
const removeTarget = ref<CommunityMember | null>(null)
const removeBusy = ref(false)

const openRemoveMember = (member: CommunityMember) => {
  if (!manager.value || !member.id) return
  // 移除管理员仅组主可做（服务端收口）；普通管理员只能移除普通成员
  if (member.role === 'ADMIN' && !owner.value) return
  removeTarget.value = member
  memberMenuOpen.value = false
  removeConfirmOpen.value = true
}

const confirmRemoveMember = async () => {
  const target = removeTarget.value
  if (!target || removeBusy.value) return
  removeBusy.value = true
  try {
    await communityApi.removeMember(
      groupId.value, target.id,
      writes.keyFor('member-remove', { memberId: target.id })
    )
    writes.complete('member-remove')
    members.value = members.value.filter((item) => item.id !== target.id)
    pendingMembers.value = pendingMembers.value.filter((item) => item.id !== target.id)
    if (group.value?.memberCount != null) {
      group.value = { ...group.value, memberCount: group.value.memberCount - 1 }
    }
    removeConfirmOpen.value = false
    ElMessage({ message: '成员已移除', type: 'success' })
  } catch (error) {
    ElMessage({ message: communityError(error, '移除成员失败，请稍后重试').message, type: 'error' })
  } finally {
    removeBusy.value = false
  }
}

const deleteConfirmOpen = ref(false)
const deleteGroupBusy = ref(false)

const openDeleteGroup = () => {
  menuOpen.value = false
  deleteConfirmOpen.value = true
}

const deleteGroup = async () => {
  if (!group.value || deleteGroupBusy.value) return
  deleteGroupBusy.value = true
  try {
    await communityApi.deleteGroup(
      group.value.id,
      writes.keyFor('group-delete', { groupId: group.value.id, versionNo: group.value.versionNo })
    )
    writes.complete('group-delete')
    deleteConfirmOpen.value = false
    ElMessage({ message: '小组已删除', type: 'success' })
    router.push('/community?rail=groups')
  } catch (error) {
    ElMessage({ message: communityError(error, '删除失败，请稍后重试').message, type: 'error' })
  } finally {
    deleteGroupBusy.value = false
  }
}

const byIdOpen = ref(false)
const byIdForm = reactive({
  targetType: 'COMMENT' as 'POST' | 'COMMENT',
  targetId: null as number | null,
  reasonCode: 'OFF_TOPIC',
  internalNote: ''
})
const byIdBusy = ref(false)

const openModerateById = () => {
  byIdForm.targetType = 'COMMENT'
  byIdForm.targetId = null
  byIdForm.reasonCode = 'OFF_TOPIC'
  byIdForm.internalNote = ''
  byIdOpen.value = true
}

const moderateById = async (restore: boolean) => {
  if (byIdBusy.value || !byIdForm.targetId) return
  byIdBusy.value = true
  try {
    await communityApi.groupModerate(
      groupId.value, byIdForm.targetType, byIdForm.targetId, restore,
      { reasonCode: byIdForm.reasonCode, internalNote: byIdForm.internalNote || undefined },
      writes.keyFor('moderate', {
        targetType: byIdForm.targetType, targetId: byIdForm.targetId, restore, reasonCode: byIdForm.reasonCode
      })
    )
    writes.complete('moderate')
    byIdForm.internalNote = ''
    byIdOpen.value = false
    if (byIdForm.targetType === 'POST') await loadContent()
    ElMessage({ message: restore ? '内容已恢复展示' : '内容已隐藏', type: 'success' })
  } catch (error) {
    ElMessage({ message: communityError(error, '处置失败，请稍后重试').message, type: 'error' })
  } finally {
    byIdBusy.value = false
  }
}

/* ---------- 屏蔽与举报 ---------- */

const menuOpen = ref(false)
const reportOpen = ref(false)
const blockConfirmOpen = ref(false)
const memberMenuTarget = ref<CommunityMember | null>(null)
const memberMenuOpen = ref(false)
const blockBusy = ref(false)

const blockUser = async (userId: number | null | undefined, done?: () => void) => {
  if (userId == null || blockBusy.value) return
  blockBusy.value = true
  try {
    await communityApi.block(userId, writes.keyFor('block', { userId }))
    writes.complete('block')
    blockConfirmOpen.value = false
    memberMenuOpen.value = false
    menuOpen.value = false
    ElMessage({ message: '已屏蔽，可在屏蔽名单中恢复', type: 'success' })
    done?.()
  } catch (error) {
    ElMessage({ message: communityError(error, '操作失败，请稍后重试').message, type: 'error' })
  } finally {
    blockBusy.value = false
  }
}

/* ---------- 对话框：发帖与编辑小组 ---------- */

const composeOpen = ref(false)
const groupFormOpen = ref(false)

const onPublished = (post: CommunityPost) => {
  router.push({ path: `/community/posts/${post.id}`, query: { from: `group/${groupId.value}` } })
}

const onGroupSaved = (saved: CommunityGroup) => {
  group.value = { ...saved, currentMemberRole: group.value?.currentMemberRole, currentMemberStatus: group.value?.currentMemberStatus, memberCount: group.value?.memberCount }
}

const switchTab = (target: string) => {
  router.push({ path: `/community/groups/${groupId.value}`, query: target === 'posts' ? {} : { tab: target } })
}

watch(tab, () => loadContent())
watch(groupId, async () => {
  await loadGroup()
  await loadContent()
})

onMounted(async () => {
  try {
    const dict = await systemApi.dict('community_group_category')
    categories.value = dict.map((item) => ({ value: item.value, label: item.label }))
  } catch {
    categories.value = []
  }
  await loadGroup()
  await loadContent()
})
</script>

<template>
  <div class="sgv-community" data-route-focus tabindex="-1">
    <!-- 小组不可见（fail closed） -->
    <div v-if="notFound" class="shell">
      <div class="empty-state" data-test="group-not-found">
        <span class="icon"><el-icon><Lock /></el-icon></span>
        <h2>这个小组已无法查看</h2>
        <p>它可能已被删除，或当前账号无法访问。</p>
        <router-link class="button" to="/community?rail=groups">返回小组列表 <span class="icon"><el-icon><Right /></el-icon></span></router-link>
      </div>
    </div>
    <div v-else-if="loadFailed" class="shell">
      <div class="empty-state" data-test="state-error">
        <span class="icon"><el-icon><Refresh /></el-icon></span>
        <h2>暂时没能加载出来</h2>
        <p>稍后再试一次。</p>
        <button type="button" class="button" @click="loadGroup().then(loadContent)">重新加载 <span class="icon"><el-icon><Refresh /></el-icon></span></button>
      </div>
    </div>
    <div v-else-if="!group" class="shell">
      <div class="skeleton" role="status" aria-label="正在加载"><span /><span /><span /><span /></div>
    </div>

    <div v-else class="shell page-arrive">
      <div class="back-row">
        <router-link class="text-button" to="/community?rail=groups"><span class="icon"><el-icon><Back /></el-icon></span>社区 / 小组</router-link>
        <button type="button" class="icon-button" aria-label="更多小组操作" title="更多小组操作" data-test="group-menu" @click="menuOpen = true">
          <el-icon><MoreFilled /></el-icon>
        </button>
      </div>

      <section class="group-heading">
        <div>
          <div class="post-source">
            <span>{{ categoryLabel }}</span>
            <span class="source-dot" aria-hidden="true" />
            <span class="icon"><el-icon><Lock v-if="group.visibility === 'MEMBERS_ONLY'" /><View v-else /></el-icon></span>
            {{ visibilityLabel(group.visibility) }}
          </div>
          <h1>{{ group.name }}<span class="dot-stop">.</span></h1>
          <p class="group-description">{{ group.description || '暂无简介' }}</p>
          <div class="meta-line">
            <span><span class="icon"><el-icon><User /></el-icon></span>{{ group.memberCount ?? 0 }} 位成员</span>
            <span>{{ joinPolicyLabel(group.joinPolicy) }}</span>
            <span>由 {{ group.owner?.nickname ?? '社区用户' }} 创建</span>
          </div>
          <div class="heading-actions">
            <button
              type="button"
              class="button"
              :class="{ primary: !isActiveMember }"
              :disabled="joinBusy || isPendingMember"
              data-test="join-button"
              @click="join"
            >
              <span class="icon"><el-icon><Check v-if="isActiveMember" /><Clock v-else-if="isPendingMember" /><Plus v-else /></el-icon></span>
              {{ joinActionText(group) }}
            </button>
            <button v-if="isActiveMember" type="button" class="button primary" data-test="compose-in-group" @click="composeOpen = true">
              <span class="icon"><el-icon><EditPen /></el-icon></span>发布帖子
            </button>
          </div>
        </div>
        <div class="feature-art"><ShiguangArt :scene="sceneForGroup(group.categoryCode)" /></div>
      </section>

      <div class="group-body">
        <section>
          <div class="tabs" role="tablist" aria-label="小组内容">
            <button type="button" role="tab" :aria-selected="tab === 'posts'" data-test="tab-posts" @click="switchTab('posts')">帖子</button>
            <button v-if="isActiveMember" type="button" role="tab" :aria-selected="tab === 'members'" data-test="tab-members" @click="switchTab('members')">成员</button>
            <button v-if="manager" type="button" role="tab" :aria-selected="tab === 'governance'" data-test="tab-governance" @click="switchTab('governance')">组内治理</button>
          </div>

          <template v-if="contentLoading">
            <div v-for="index in 2" :key="index" class="skeleton" role="status" aria-label="正在加载"><span /><span /><span /><span /></div>
          </template>
          <div v-else-if="contentError" class="empty-state" data-test="state-error">
            <span class="icon"><el-icon><Refresh /></el-icon></span>
            <h2>暂时没能加载出来</h2>
            <p>{{ contentError }}</p>
            <button type="button" class="button" @click="loadContent">重新加载 <span class="icon"><el-icon><Refresh /></el-icon></span></button>
          </div>

          <!-- 帖子页签 -->
          <template v-else-if="tab === 'posts'">
            <div v-if="membersOnlyLocked" class="empty-state" data-test="members-only-empty">
              <span class="icon"><el-icon><Lock /></el-icon></span>
              <h2>{{ isPendingMember ? '申请已送达，等一个回应' : '这是一个仅成员可见的小组' }}</h2>
              <p>加入审核通过后，就能阅读大家的分享。</p>
              <router-link class="button" to="/community?rail=groups">先逛逛其他小组 <span class="icon"><el-icon><Right /></el-icon></span></router-link>
            </div>
            <template v-else-if="posts.length">
              <article v-for="(post, index) in posts" :key="post.id" class="post-row" :class="{ lead: index === 0 }">
                <div class="post-source">
                  <span class="icon"><el-icon><Lock v-if="group.visibility === 'MEMBERS_ONLY'" /><View v-else /></el-icon></span>
                  <span>{{ visibilityLabel(group.visibility) }}</span>
                </div>
                <div class="post-body">
                  <div class="post-copy">
                    <h2><router-link :to="{ path: `/community/posts/${post.id}`, query: { from: `group/${group.id}` } }">{{ post.title }}</router-link></h2>
                    <p>{{ excerptOf(post.content) }}</p>
                  </div>
                  <router-link class="post-art" :to="{ path: `/community/posts/${post.id}`, query: { from: `group/${group.id}` } }" tabindex="-1" aria-hidden="true">
                    <ShiguangArt :scene="sceneForGroup(group.categoryCode)" />
                  </router-link>
                </div>
                <div class="post-footer">
                  <span class="avatar" :class="avatarColorClass(post.authorUserId)">{{ initialOf(post.author?.nickname) }}</span>
                  <span class="author">{{ post.author?.nickname ?? '社区用户' }}</span>
                  <span class="source-dot" aria-hidden="true" />
                  <time>{{ listTimeText(post.publishedAt ?? post.createTime) }}</time>
                  <router-link class="read-link" :to="{ path: `/community/posts/${post.id}`, query: { from: `group/${group.id}` } }">
                    读全文 <span class="icon"><el-icon><TopRight /></el-icon></span>
                  </router-link>
                </div>
              </article>
              <div class="feed-end">
                <button v-if="hasMore" type="button" class="text-button" :disabled="loadingMore" @click="loadMorePosts">
                  再看看更多分享 <span class="icon"><el-icon><ArrowDown /></el-icon></span>
                </button>
                <span v-else>这一页的分享，已经看完了。</span>
              </div>
            </template>
            <div v-else class="empty-state" data-test="posts-empty">
              <span class="icon"><el-icon><ChatDotRound /></el-icon></span>
              <h2>还没有人写下第一句</h2>
              <p>从一件小事开始，让这个小组热闹起来。</p>
              <button v-if="isActiveMember" type="button" class="button" @click="composeOpen = true">发布帖子 <span class="icon"><el-icon><Right /></el-icon></span></button>
            </div>
          </template>

          <!-- 成员页签 -->
          <template v-else-if="tab === 'members'">
            <template v-if="members.length">
              <article v-for="member in members" :key="member.id" class="member-row" data-test="member-row">
                <span class="avatar" :class="avatarColorClass(member.userId)">{{ initialOf(member.user?.nickname) }}</span>
                <div>
                  <h3>{{ member.user?.nickname ?? '社区用户' }}</h3>
                  <p>{{ memberRoleText(member.role) }}</p>
                </div>
                <span v-if="member.userId === auth.user?.id" class="muted small">你自己</span>
                <button
                  v-else
                  type="button"
                  class="icon-button"
                  :aria-label="`${member.user?.nickname ?? '该成员'}的成员操作`"
                  title="成员操作"
                  @click="memberMenuTarget = member; memberMenuOpen = true"
                >
                  <el-icon><MoreFilled /></el-icon>
                </button>
              </article>
              <div class="feed-end">
                <button v-if="hasMore" type="button" class="text-button" :disabled="loadingMore" @click="loadMorePosts">
                  加载更多成员 <span class="icon"><el-icon><ArrowDown /></el-icon></span>
                </button>
              </div>
            </template>
            <div v-else class="empty-state"><span class="icon"><el-icon><User /></el-icon></span><h2>还没有成员</h2></div>
          </template>

          <!-- 组内治理页签 -->
          <template v-else-if="tab === 'governance' && manager">
            <div class="list-heading">
              <h2>加入申请</h2>
              <span class="small muted">{{ pendingMembers.length }} 条待处理</span>
            </div>
            <div v-if="!pendingMembers.length" class="member-row"><div><p class="muted">暂时没有等待审核的申请。</p></div></div>
            <article v-for="member in pendingMembers" :key="member.id" class="member-row" data-test="pending-row">
              <span class="avatar" :class="avatarColorClass(member.userId)">{{ initialOf(member.user?.nickname) }}</span>
              <div>
                <h3>{{ member.user?.nickname ?? '社区用户' }}</h3>
                <p>希望加入这个小组</p>
              </div>
              <button type="button" class="icon-button" :aria-label="`拒绝${member.user?.nickname ?? '该成员'}`" title="拒绝申请" @click="decide(member, 'REJECT')">
                <el-icon><Close /></el-icon>
              </button>
              <button type="button" class="icon-button" :aria-label="`通过${member.user?.nickname ?? '该成员'}`" title="通过申请" @click="decide(member, 'APPROVE')">
                <el-icon><Check /></el-icon>
              </button>
            </article>

            <div class="list-heading"><h2>组内内容</h2></div>
            <div v-if="!moderationPosts.length" class="member-row"><div><p class="muted">这个小组还没有帖子。</p></div></div>
            <article v-for="post in moderationPosts" :key="post.id" class="record-row" data-test="moderation-row">
              <div>
                <h3>{{ post.title }}</h3>
                <p>{{ post.status === 'HIDDEN' ? '已隐藏' : '正常展示' }}</p>
              </div>
              <button v-if="post.status === 'HIDDEN'" type="button" class="text-button" @click="restorePost(post)">恢复展示</button>
              <button v-else type="button" class="text-button" @click="openModeration(post)">隐藏</button>
            </article>
            <button type="button" class="text-button" data-test="moderate-by-id" @click="openModerateById">
              <span class="icon"><el-icon><Setting /></el-icon></span>按内容 ID 处置评论或帖子
            </button>
            <button type="button" class="text-button accent" data-test="edit-group" @click="groupFormOpen = true">
              <span class="icon"><el-icon><EditPen /></el-icon></span>编辑小组资料
            </button>
          </template>
        </section>

        <aside class="group-sidebar">
          <section class="aside-section">
            <h2>关于这个小组</h2>
            <p>{{ group.description || '暂无简介' }}</p>
            <div class="member-row">
              <span class="avatar" :class="avatarColorClass(group.ownerUserId)">{{ initialOf(group.owner?.nickname) }}</span>
              <div>
                <h3>{{ group.owner?.nickname ?? '社区用户' }}</h3>
                <p>小组创建者</p>
              </div>
            </div>
            <p class="small muted" style="margin-top:17px">创建于 {{ formatLongDate(group.createTime) }}</p>
          </section>
          <section class="aside-section">
            <h2>下一次相聚</h2>
            <p>有些话，适合面对面慢慢说。看看小组里正在发起的活动。</p>
            <router-link class="text-button accent" to="/activities">去活动里看看 <span class="icon"><el-icon><TopRight /></el-icon></span></router-link>
          </section>
        </aside>
      </div>
    </div>

    <!-- 小组操作菜单 -->
    <NativeDialog :open="menuOpen" title="小组操作" @update:open="menuOpen = $event">
      <div class="menu-list">
        <template v-if="owner">
          <button type="button" @click="menuOpen = false; groupFormOpen = true"><span class="icon"><el-icon><EditPen /></el-icon></span>编辑资料</button>
          <button type="button" @click="menuOpen = false; switchTab('governance')"><span class="icon"><el-icon><Setting /></el-icon></span>组内治理</button>
          <button type="button" class="danger" data-test="delete-group" @click="openDeleteGroup"><span class="icon"><el-icon><Delete /></el-icon></span>删除小组</button>
        </template>
        <template v-else>
          <button type="button" @click="menuOpen = false; reportOpen = true"><span class="icon"><el-icon><Flag /></el-icon></span>举报小组</button>
          <button type="button" @click="menuOpen = false; blockConfirmOpen = true"><span class="icon"><el-icon><Remove /></el-icon></span>屏蔽组主</button>
        </template>
        <button v-if="isActiveMember && !owner" type="button" @click="menuOpen = false; leaveConfirmOpen = true">
          <span class="icon"><el-icon><Back /></el-icon></span>退出小组
        </button>
      </div>
    </NativeDialog>

    <!-- 成员操作菜单 -->
    <NativeDialog :open="memberMenuOpen" title="成员操作" @update:open="memberMenuOpen = $event">
      <div class="menu-list">
        <template v-if="memberMenuTarget">
          <button
            v-if="owner && canManageMember(memberMenuTarget)"
            type="button"
            data-test="member-role"
            @click="openRoleChange(memberMenuTarget)"
          >
            <span class="icon"><el-icon><User /></el-icon></span>{{ memberMenuTarget.role === 'ADMIN' ? '设为普通成员' : '设为管理员' }}
          </button>
          <button
            v-if="manager && canManageMember(memberMenuTarget) && (owner || memberMenuTarget.role === 'MEMBER')"
            type="button"
            data-test="member-remove"
            @click="openRemoveMember(memberMenuTarget)"
          >
            <span class="icon"><el-icon><Remove /></el-icon></span>移出小组
          </button>
          <button type="button" @click="memberMenuOpen = false; blockConfirmOpen = true">
            <span class="icon"><el-icon><Remove /></el-icon></span>屏蔽 {{ memberMenuTarget?.user?.nickname ?? '该成员' }}
          </button>
        </template>
      </div>
    </NativeDialog>

    <!-- 屏蔽确认 -->
    <NativeDialog :open="blockConfirmOpen" :title="`屏蔽 ${memberMenuTarget?.user?.nickname ?? group?.owner?.nickname ?? '对方'}？`" @update:open="blockConfirmOpen = $event">
      <p>屏蔽后，对方的内容将不再出现在你的社区浏览中。</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="blockConfirmOpen = false">取消</button>
        <button
          type="button"
          class="button primary"
          :disabled="blockBusy"
          @click="blockUser(memberMenuTarget ? memberMenuTarget.userId : group?.ownerUserId, () => router.push('/community'))"
        >确认 <span class="icon"><el-icon><Right /></el-icon></span></button>
      </div>
    </NativeDialog>

    <!-- 退出确认 -->
    <NativeDialog :open="leaveConfirmOpen" title="退出这个小组？" @update:open="leaveConfirmOpen = $event">
      <p>退出{{ group?.name }}后，将无法继续发帖和评论。</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="leaveConfirmOpen = false">取消</button>
        <button type="button" class="button primary" :disabled="joinBusy" @click="leave">确认 <span class="icon"><el-icon><Right /></el-icon></span></button>
      </div>
    </NativeDialog>

    <!-- 隐藏处置 -->
    <NativeDialog :open="moderationOpen" title="隐藏组内内容" @update:open="moderationOpen = $event">
      <form @submit.prevent="hidePost">
        <p>{{ moderationTarget?.title }}</p>
        <label for="moderation-reason">处置原因</label>
        <select id="moderation-reason" v-model="moderationReason">
          <option value="OFF_TOPIC">偏离小组主题</option>
          <option value="SPAM">重复或广告内容</option>
          <option value="OTHER">其他</option>
        </select>
        <label for="moderation-note">内部备注</label>
        <textarea id="moderation-note" v-model="moderationNote" rows="3" maxlength="500" />
        <div class="dialog-actions">
          <button type="button" class="text-button" @click="moderationOpen = false">取消</button>
          <button type="submit" class="button primary" :disabled="moderationBusy">隐藏内容 <span class="icon"><el-icon><Right /></el-icon></span></button>
        </div>
      </form>
    </NativeDialog>

    <!-- 按 ID 处置（承接旧六页签的内容处置能力） -->
    <NativeDialog :open="byIdOpen" title="按内容 ID 处置" @update:open="byIdOpen = $event">
      <form data-test="by-id-form" @submit.prevent="moderateById(false)">
        <label for="by-id-type">内容类型</label>
        <select id="by-id-type" v-model="byIdForm.targetType">
          <option value="COMMENT">评论</option>
          <option value="POST">帖子</option>
        </select>
        <label for="by-id-target">内容 ID</label>
        <input id="by-id-target" v-model.number="byIdForm.targetId" type="number" min="1" required>
        <label for="by-id-reason">处置原因</label>
        <select id="by-id-reason" v-model="byIdForm.reasonCode">
          <option value="OFF_TOPIC">偏离小组主题</option>
          <option value="SPAM">重复或广告内容</option>
          <option value="OTHER">其他</option>
        </select>
        <label for="by-id-note">内部备注</label>
        <textarea id="by-id-note" v-model="byIdForm.internalNote" rows="3" maxlength="500" />
        <div class="dialog-actions">
          <button type="button" class="text-button" @click="byIdOpen = false">取消</button>
          <button type="button" class="text-button" :disabled="byIdBusy || !byIdForm.targetId" @click="moderateById(true)">恢复展示</button>
          <button type="submit" class="button primary" :disabled="byIdBusy || !byIdForm.targetId">隐藏内容 <span class="icon"><el-icon><Right /></el-icon></span></button>
        </div>
      </form>
    </NativeDialog>

    <!-- 调整角色确认 -->
    <NativeDialog :open="roleConfirmOpen" :title="roleTarget?.role === 'ADMIN' ? '设为管理员？' : '设为普通成员？'" @update:open="roleConfirmOpen = $event">
      <p>{{ roleTarget?.role === 'ADMIN' ? '对方将可以处理加入申请和组内内容。' : '对方将不再拥有管理员权限。' }}</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="roleConfirmOpen = false">取消</button>
        <button type="button" class="button primary" :disabled="roleBusy" data-test="confirm-role" @click="confirmRoleChange">确认 <span class="icon"><el-icon><Right /></el-icon></span></button>
      </div>
    </NativeDialog>

    <!-- 移除成员确认 -->
    <NativeDialog :open="removeConfirmOpen" :title="`将 ${removeTarget?.user?.nickname ?? '该成员'} 移出小组？`" @update:open="removeConfirmOpen = $event">
      <p>移出后，对方将无法继续在这个小组发帖和评论，但可以重新申请加入。</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="removeConfirmOpen = false">取消</button>
        <button type="button" class="button primary" :disabled="removeBusy" data-test="confirm-remove" @click="confirmRemoveMember">确认 <span class="icon"><el-icon><Right /></el-icon></span></button>
      </div>
    </NativeDialog>

    <!-- 删除小组确认 -->
    <NativeDialog :open="deleteConfirmOpen" title="删除这个小组？" @update:open="deleteConfirmOpen = $event">
      <p>删除后「{{ group?.name }}」和它的普通内容将立即不可见，此操作不可撤销。</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="deleteConfirmOpen = false">取消</button>
        <button type="button" class="button primary" :disabled="deleteGroupBusy" data-test="confirm-delete-group" @click="deleteGroup">确认删除 <span class="icon"><el-icon><Right /></el-icon></span></button>
      </div>
    </NativeDialog>

    <ComposeDialog
      v-model:open="composeOpen"
      :eligible-groups="group ? [group] : []"
      :preselected-group-id="group?.id ?? null"
      @published="onPublished"
    />
    <GroupFormDialog
      v-model:open="groupFormOpen"
      :categories="categories"
      :edit-group="group"
      @saved="onGroupSaved"
    />
    <ReportDialog
      v-model:open="reportOpen"
      target-type="GROUP"
      :target-id="group?.id ?? null"
      :target-title="group?.name ?? ''"
    />
  </div>
</template>
