<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, Back, Clock, Delete, EditPen, Flag, Lock,
  MoreFilled, Plus, Refresh, Remove, Right, Share, TopRight, View
} from '@element-plus/icons-vue'
import {
  communityApi,
  communityError,
  createIdempotencyRegistry,
  type CommunityComment,
  type CommunityGroup,
  type CommunityPost
} from '@/api/community'
import { useAuthStore } from '@/stores/auth'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import NativeDialog from './dialogs/NativeDialog.vue'
import ComposeDialog from './dialogs/ComposeDialog.vue'
import ReportDialog from './dialogs/ReportDialog.vue'
import {
  avatarColorClass,
  detailTimeText,
  initialOf,
  joinActionText,
  memberBadgeText,
  sceneForGroup,
  visibilityLabel
} from './communityShared'

/**
 * 帖子详情（冻结参考 #post/{id}：约 690px 阅读列 + 小组侧栏 + 评论区）。
 * 详情为 S03 受控聚合读取（作者/来源小组/可见性）；成员状态经小组详情端点核对；
 * 评论创建遵循服务端幂等与审计，成功以服务端结果为准。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const postId = computed(() => Number(route.params.id))

const post = ref<CommunityPost | null>(null)
const group = ref<CommunityGroup | null>(null)
const groupError = ref(false)
const notFound = ref(false)
const loadFailed = ref(false)
const comments = ref<CommunityComment[]>([])
const commentsLoading = ref(false)
const commentsError = ref('')
const nextCursor = ref<number | null>(null)
const hasMore = ref(false)
const loadingMore = ref(false)

const writes = createIdempotencyRegistry()

const paragraphs = computed(() =>
  (post.value?.content ?? '').split(/\n\s*\n/).map((item) => item.trim()).filter(Boolean)
)

const from = computed(() => {
  const raw = String(route.query.from ?? '')
  if (/^(feed|groups|mine|my-posts)(\?|$)/.test(raw)) return raw
  const groupMatch = /^group\/(\d+)$/.exec(raw)
  if (groupMatch) return raw
  return post.value ? `group/${post.value.groupId}` : 'feed'
})

const backTarget = computed(() => {
  const value = from.value
  if (value.startsWith('group/')) return { path: `/community/groups/${value.slice(6)}`, label: '返回小组' }
  if (value === 'my-posts') return { path: '/community', query: { rail: 'my-posts' }, label: '返回社区' }
  if (value === 'groups') return { path: '/community', query: { rail: 'groups' }, label: '返回社区' }
  if (value === 'mine') return { path: '/community', query: { rail: 'mine' }, label: '返回社区' }
  return { path: '/community', label: '返回社区' }
})

const isAuthor = computed(() => post.value?.authorUserId === auth.user?.id)
const isActiveMember = computed(() => group.value?.currentMemberStatus === 'ACTIVE')
const isPendingMember = computed(() => group.value?.currentMemberStatus === 'PENDING')

/* ---------- 读取 ---------- */

const loadPost = async () => {
  notFound.value = false
  loadFailed.value = false
  post.value = null
  try {
    post.value = await communityApi.post(postId.value)
    document.title = `${post.value.title} · 拾光`
  } catch (error) {
    const state = communityError(error)
    if (state.kind === 'missing' || state.kind === 'forbidden') notFound.value = true
    else loadFailed.value = true
    return
  }
  await Promise.all([loadGroup(), loadComments()])
}

const loadGroup = async () => {
  if (!post.value) return
  groupError.value = false
  try {
    group.value = await communityApi.group(post.value.groupId, { skipGlobalErrorRedirect: true })
  } catch {
    // 详情可见而小组元数据短暂不可读：评论区降级为不可参与，不伪造成员状态
    groupError.value = true
  }
}

const loadComments = async () => {
  commentsLoading.value = true
  commentsError.value = ''
  comments.value = []
  nextCursor.value = null
  hasMore.value = false
  try {
    const page = await communityApi.comments(postId.value, { limit: 20 })
    comments.value = page.records
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (error) {
    commentsError.value = communityError(error, '评论加载失败，请稍后重试').message
  } finally {
    commentsLoading.value = false
  }
}

const loadMoreComments = async () => {
  if (!hasMore.value || nextCursor.value == null || loadingMore.value) return
  loadingMore.value = true
  try {
    const page = await communityApi.comments(postId.value, { cursorId: nextCursor.value, limit: 20 })
    comments.value = [...comments.value, ...page.records]
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (error) {
    ElMessage({ message: communityError(error, '加载更多失败，请稍后重试').message, type: 'error' })
  } finally {
    loadingMore.value = false
  }
}

/* 继续听听别人的日常：同组优先的两篇其他分享（真实读取，不用夹具） */
const others = ref<CommunityPost[]>([])
const loadOthers = async () => {
  if (!post.value) return
  try {
    const page = await communityApi.posts({ groupId: post.value.groupId, limit: 3 })
    others.value = page.records.filter((item) => item.id !== post.value?.id).slice(0, 2)
  } catch {
    others.value = []
  }
}

/* ---------- 评论（真实命令） ---------- */

const commentDraft = ref('')
const commentError = ref('')
const commentBusy = ref(false)

const submitComment = async () => {
  if (commentBusy.value) return
  if (!commentDraft.value.trim()) {
    commentError.value = '写一点想说的话，再发表吧。'
    return
  }
  commentError.value = ''
  commentBusy.value = true
  try {
    const payload = { postId: postId.value, content: commentDraft.value.trim() }
    const created = await communityApi.createComment(
      postId.value,
      payload.content,
      writes.keyFor('comment-create', payload)
    )
    writes.complete('comment-create')
    comments.value = [created, ...comments.value]
    commentDraft.value = ''
    ElMessage({ message: '评论已发表', type: 'success' })
  } catch (error) {
    commentError.value = communityError(error, '评论发表失败，请稍后重试').message
  } finally {
    commentBusy.value = false
  }
}

/* ---------- 加入小组（评论区承接） ---------- */

const joinBusy = ref(false)
const join = async () => {
  if (!group.value || joinBusy.value) return
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

/* ---------- 帖子/评论操作 ---------- */

const postMenuOpen = ref(false)
const commentMenuTarget = ref<CommunityComment | null>(null)
const commentMenuOpen = ref(false)
const composeOpen = ref(false)
const deletePostOpen = ref(false)
const deleteCommentOpen = ref(false)
const editCommentOpen = ref(false)
const editCommentDraft = ref('')
const editCommentError = ref('')
const reportOpen = ref(false)
const reportTarget = ref<{ type: 'POST' | 'COMMENT'; id: number; title: string }>({ type: 'POST', id: 0, title: '' })
const blockConfirm = ref<{ userId: number | null; nickname: string }>({ userId: null, nickname: '' })
const blockConfirmOpen = ref(false)
const actionBusy = ref(false)

const openReport = (type: 'POST' | 'COMMENT', id: number, title: string) => {
  reportTarget.value = { type, id, title }
  reportOpen.value = true
}

const onSaved = (saved: CommunityPost) => {
  post.value = saved
}

const deletePost = async () => {
  if (!post.value || actionBusy.value) return
  actionBusy.value = true
  try {
    await communityApi.deletePost(post.value.id, writes.keyFor('post-delete', { postId: post.value.id }))
    writes.complete('post-delete')
    deletePostOpen.value = false
    ElMessage({ message: '帖子已删除', type: 'success' })
    router.push({ path: '/community', query: { rail: 'my-posts' } })
  } catch (error) {
    ElMessage({ message: communityError(error, '删除失败，请稍后重试').message, type: 'error' })
  } finally {
    actionBusy.value = false
  }
}

const startEditComment = () => {
  if (!commentMenuTarget.value) return
  editCommentDraft.value = commentMenuTarget.value.content
  editCommentError.value = ''
  commentMenuOpen.value = false
  editCommentOpen.value = true
}

const saveComment = async () => {
  const target = commentMenuTarget.value
  if (!target || actionBusy.value) return
  if (!editCommentDraft.value.trim()) {
    editCommentError.value = '评论内容不能为空。'
    return
  }
  actionBusy.value = true
  try {
    const payload = { commentId: target.id, content: editCommentDraft.value.trim(), versionNo: target.versionNo }
    const saved = await communityApi.updateComment(
      target.id,
      payload.content,
      payload.versionNo,
      writes.keyFor('comment-update', payload)
    )
    writes.complete('comment-update')
    comments.value = comments.value.map((item) => (item.id === saved.id ? saved : item))
    editCommentOpen.value = false
    ElMessage({ message: '评论已更新', type: 'success' })
  } catch (error) {
    editCommentError.value = communityError(error, '保存失败，请稍后重试').message
  } finally {
    actionBusy.value = false
  }
}

const deleteComment = async () => {
  const target = commentMenuTarget.value
  if (!target || actionBusy.value) return
  actionBusy.value = true
  try {
    await communityApi.deleteComment(target.id, writes.keyFor('comment-delete', { commentId: target.id }))
    writes.complete('comment-delete')
    comments.value = comments.value.filter((item) => item.id !== target.id)
    deleteCommentOpen.value = false
    ElMessage({ message: '评论已删除', type: 'success' })
  } catch (error) {
    ElMessage({ message: communityError(error, '删除失败，请稍后重试').message, type: 'error' })
  } finally {
    actionBusy.value = false
  }
}

const blockUser = async () => {
  if (blockConfirm.value.userId == null || actionBusy.value) return
  actionBusy.value = true
  try {
    const userId = blockConfirm.value.userId
    await communityApi.block(userId, writes.keyFor('block', { userId }))
    writes.complete('block')
    blockConfirmOpen.value = false
    ElMessage({ message: '已屏蔽，可在屏蔽名单中恢复', type: 'success' })
    router.push('/community')
  } catch (error) {
    ElMessage({ message: communityError(error, '操作失败，请稍后重试').message, type: 'error' })
  } finally {
    actionBusy.value = false
  }
}

/* ---------- 分享 ---------- */

const shareOpen = ref(false)
const shareUrl = ref('')

const share = async () => {
  const url = `${window.location.origin}/community/posts/${postId.value}`
  try {
    await navigator.clipboard.writeText(url)
    ElMessage({ message: '链接已复制', type: 'success' })
  } catch {
    shareUrl.value = url
    shareOpen.value = true
  }
}

onMounted(async () => {
  await loadPost()
  await loadOthers()
})

watch(postId, async () => {
  await loadPost()
  await loadOthers()
})
</script>

<template>
  <div class="sgv-community" data-route-focus tabindex="-1">
    <div v-if="notFound" class="shell">
      <div class="empty-state" data-test="post-not-found">
        <span class="icon"><el-icon><Lock /></el-icon></span>
        <h2>这篇分享暂时无法查看</h2>
        <p>它可能已被删除，或只对小组成员开放。</p>
        <router-link class="button" to="/community">返回社区 <span class="icon"><el-icon><Right /></el-icon></span></router-link>
      </div>
    </div>
    <div v-else-if="loadFailed" class="shell">
      <div class="empty-state" data-test="state-error">
        <span class="icon"><el-icon><Refresh /></el-icon></span>
        <h2>暂时没能加载出来</h2>
        <p>稍后再试一次。</p>
        <button type="button" class="button" @click="loadPost">重新加载 <span class="icon"><el-icon><Refresh /></el-icon></span></button>
      </div>
    </div>
    <div v-else-if="!post" class="shell">
      <div class="skeleton" role="status" aria-label="正在加载"><span /><span /><span /><span /></div>
    </div>

    <div v-else class="shell page-arrive">
      <div class="back-row">
        <router-link class="text-button" :to="{ path: backTarget.path, query: backTarget.query }">
          <span class="icon"><el-icon><Back /></el-icon></span>{{ backTarget.label }}
        </router-link>
        <span class="small">社区 / {{ post.groupName }} / 帖子</span>
      </div>

      <div class="post-layout">
        <article class="article-column">
          <header class="article-header">
            <div class="post-source">
              <router-link :to="`/community/groups/${post.groupId}`">{{ post.groupName }}</router-link>
              <span class="source-dot" aria-hidden="true" />
              <span class="icon"><el-icon><Lock v-if="post.groupVisibility === 'MEMBERS_ONLY'" /><View v-else /></el-icon></span>
              {{ visibilityLabel(post.groupVisibility) }}
            </div>
            <h1>{{ post.title }}</h1>
            <div class="author-line">
              <span class="avatar" :class="avatarColorClass(post.authorUserId)">{{ initialOf(post.author?.nickname) }}</span>
              <div>
                <strong>{{ post.author?.nickname ?? '社区用户' }}</strong>
                <p>{{ detailTimeText(post.publishedAt ?? post.createTime) }} · 发布于{{ post.groupName }}</p>
              </div>
              <button type="button" class="icon-button" aria-label="更多帖子操作" title="更多帖子操作" data-test="post-menu" @click="postMenuOpen = true">
                <el-icon><MoreFilled /></el-icon>
              </button>
            </div>
          </header>

          <div class="article-text" data-test="article-text">
            <p v-for="(paragraph, index) in paragraphs" :key="index">{{ paragraph }}</p>
          </div>

          <div class="article-bottom">
            <span>这一段日常，来自 {{ post.author?.nickname ?? '社区用户' }}。</span>
            <button type="button" class="text-button" data-test="share" @click="share">
              <span class="icon"><el-icon><Share /></el-icon></span>分享
            </button>
          </div>

          <section class="comments" id="comments">
            <div class="section-head">
              <h2>评论 <span class="small muted">{{ comments.length }}{{ hasMore ? '+' : '' }}</span></h2>
              <span class="small muted">慢慢说，我们都在听。</span>
            </div>

            <template v-if="commentsLoading">
              <div class="skeleton" role="status" aria-label="正在加载"><span /><span /><span /><span /></div>
            </template>
            <div v-else-if="commentsError" class="empty-state" data-test="comments-error">
              <span class="icon"><el-icon><Refresh /></el-icon></span>
              <h2>评论暂时没能加载出来</h2>
              <p>{{ commentsError }}</p>
              <button type="button" class="button" @click="loadComments">重新加载 <span class="icon"><el-icon><Refresh /></el-icon></span></button>
            </div>
            <template v-else>
              <article v-for="comment in comments" :key="comment.id" class="comment-row" data-test="comment-row">
                <span class="avatar" :class="avatarColorClass(comment.authorUserId)">{{ initialOf(comment.author?.nickname) }}</span>
                <div>
                  <div class="comment-name">
                    {{ comment.author?.nickname ?? '社区用户' }}
                    <span v-if="comment.authorUserId === post.authorUserId" class="badge">作者</span>
                    <button
                      type="button"
                      class="icon-button"
                      aria-label="更多评论操作"
                      title="更多评论操作"
                      @click="commentMenuTarget = comment; commentMenuOpen = true"
                    >
                      <el-icon><MoreFilled /></el-icon>
                    </button>
                  </div>
                  <p>{{ comment.content }}</p>
                  <time class="comment-time">{{ detailTimeText(comment.publishedAt ?? comment.createTime) }}</time>
                </div>
              </article>
              <div v-if="hasMore" class="feed-end">
                <button type="button" class="text-button" :disabled="loadingMore" @click="loadMoreComments">
                  加载更多评论 <span class="icon"><el-icon><ArrowDown /></el-icon></span>
                </button>
              </div>
            </template>

            <form class="comment-compose" @submit.prevent="submitComment">
              <label for="comment-content">说说你的想法</label>
              <template v-if="isActiveMember">
                <textarea
                  id="comment-content"
                  v-model="commentDraft"
                  maxlength="1000"
                  rows="3"
                  placeholder="写下此刻想说的话…"
                />
                <div class="compose-footer">
                  <span>{{ visibilityLabel(post.groupVisibility) }} · {{ post.groupName }}</span>
                  <button class="button primary" type="submit" :disabled="commentBusy" data-test="comment-submit">
                    发表评论 <span class="icon"><el-icon><Right /></el-icon></span>
                  </button>
                </div>
              </template>
              <template v-else>
                <textarea id="comment-content" rows="3" placeholder="写下此刻想说的话…" disabled />
                <div class="compose-footer">
                  <span>{{ groupError ? '暂时无法确认成员身份，请稍后重试。' : '加入小组后，就能参与这段对话。' }}</span>
                  <button v-if="groupError" type="button" class="button" @click="loadGroup">
                    重试 <span class="icon"><el-icon><Refresh /></el-icon></span>
                  </button>
                  <button v-else-if="group" type="button" class="button" :disabled="joinBusy || isPendingMember" data-test="join-button" @click="join">
                    <span class="icon"><el-icon><Clock v-if="isPendingMember" /><Plus v-else /></el-icon></span>
                    {{ joinActionText(group) }}
                  </button>
                </div>
              </template>
              <p class="dialog-error" role="alert">{{ commentError }}</p>
            </form>
          </section>
        </article>

        <aside class="article-sidebar">
          <div class="feature-art"><ShiguangArt :scene="sceneForGroup(post.groupCategory ?? group?.categoryCode)" /></div>
          <section class="aside-section">
            <router-link class="text-link" :to="`/community/groups/${post.groupId}`">
              <h2>{{ post.groupName }} <span class="icon"><el-icon><TopRight /></el-icon></span></h2>
            </router-link>
            <p>{{ group?.description || '' }}</p>
            <div class="meta-line">
              <span>{{ group?.memberCount ?? 0 }} 位成员</span>
              <span>{{ visibilityLabel(post.groupVisibility) }}</span>
              <span v-if="group" class="badge" :class="{ pending: isPendingMember }">{{ memberBadgeText(group, auth.user?.id) }}</span>
            </div>
            <button
              v-if="group && !isActiveMember"
              type="button"
              class="button"
              :disabled="joinBusy || isPendingMember"
              @click="join"
            >
              <span class="icon"><el-icon><Clock v-if="isPendingMember" /><Plus v-else /></el-icon></span>
              {{ joinActionText(group) }}
            </button>
          </section>
          <section v-if="others.length" class="aside-section">
            <h2>继续听听别人的日常</h2>
            <router-link
              v-for="other in others"
              :key="other.id"
              class="text-link"
              :to="{ path: `/community/posts/${other.id}`, query: { from: `group/${other.groupId}` } }"
            >
              <p style="margin-top:17px;color:var(--ink)">{{ other.title }}</p>
              <p class="small" style="margin-top:5px">{{ other.groupName }}</p>
            </router-link>
          </section>
        </aside>
      </div>
    </div>

    <!-- 帖子操作菜单 -->
    <NativeDialog :open="postMenuOpen" title="帖子操作" @update:open="postMenuOpen = $event">
      <div class="menu-list">
        <template v-if="isAuthor">
          <button type="button" @click="postMenuOpen = false; composeOpen = true"><span class="icon"><el-icon><EditPen /></el-icon></span>编辑帖子</button>
          <button type="button" class="danger" @click="postMenuOpen = false; deletePostOpen = true"><span class="icon"><el-icon><Delete /></el-icon></span>删除帖子</button>
        </template>
        <template v-else>
          <button type="button" @click="postMenuOpen = false; openReport('POST', post?.id ?? 0, post?.title ?? '')"><span class="icon"><el-icon><Flag /></el-icon></span>举报帖子</button>
          <button type="button" @click="postMenuOpen = false; blockConfirm = { userId: post?.authorUserId ?? null, nickname: post?.author?.nickname ?? '作者' }; blockConfirmOpen = true"><span class="icon"><el-icon><Remove /></el-icon></span>屏蔽 {{ post?.author?.nickname ?? '作者' }}</button>
        </template>
        <button type="button" @click="postMenuOpen = false; share()"><span class="icon"><el-icon><Share /></el-icon></span>分享链接</button>
      </div>
    </NativeDialog>

    <!-- 评论操作菜单 -->
    <NativeDialog :open="commentMenuOpen" title="评论操作" @update:open="commentMenuOpen = $event">
      <div class="menu-list">
        <template v-if="commentMenuTarget?.authorUserId === auth.user?.id">
          <button type="button" @click="startEditComment"><span class="icon"><el-icon><EditPen /></el-icon></span>编辑评论</button>
          <button type="button" class="danger" @click="commentMenuOpen = false; deleteCommentOpen = true"><span class="icon"><el-icon><Delete /></el-icon></span>删除评论</button>
        </template>
        <template v-else>
          <button type="button" @click="commentMenuOpen = false; openReport('COMMENT', commentMenuTarget?.id ?? 0, commentMenuTarget?.content ?? '')"><span class="icon"><el-icon><Flag /></el-icon></span>举报评论</button>
          <button type="button" @click="commentMenuOpen = false; blockConfirm = { userId: commentMenuTarget?.authorUserId ?? null, nickname: commentMenuTarget?.author?.nickname ?? '对方' }; blockConfirmOpen = true"><span class="icon"><el-icon><Remove /></el-icon></span>屏蔽 {{ commentMenuTarget?.author?.nickname ?? '对方' }}</button>
        </template>
      </div>
    </NativeDialog>

    <!-- 删除帖子确认 -->
    <NativeDialog :open="deletePostOpen" title="删除这篇分享？" @update:open="deletePostOpen = $event">
      <p>删除后，其他人将无法再阅读这篇帖子。</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="deletePostOpen = false">取消</button>
        <button type="button" class="button primary" :disabled="actionBusy" @click="deletePost">确认 <span class="icon"><el-icon><Right /></el-icon></span></button>
      </div>
    </NativeDialog>

    <!-- 删除评论确认 -->
    <NativeDialog :open="deleteCommentOpen" title="删除这条评论？" @update:open="deleteCommentOpen = $event">
      <p>这条评论将从帖子中移除。</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="deleteCommentOpen = false">取消</button>
        <button type="button" class="button primary" :disabled="actionBusy" @click="deleteComment">确认 <span class="icon"><el-icon><Right /></el-icon></span></button>
      </div>
    </NativeDialog>

    <!-- 编辑评论 -->
    <NativeDialog :open="editCommentOpen" title="编辑评论" @update:open="editCommentOpen = $event">
      <form @submit.prevent="saveComment">
        <label for="edit-comment-content">评论内容</label>
        <textarea id="edit-comment-content" v-model="editCommentDraft" maxlength="1000" required />
        <p class="dialog-error" role="alert">{{ editCommentError }}</p>
        <div class="dialog-actions">
          <button type="button" class="text-button" @click="editCommentOpen = false">取消</button>
          <button type="submit" class="button primary" :disabled="actionBusy">保存修改 <span class="icon"><el-icon><Right /></el-icon></span></button>
        </div>
      </form>
    </NativeDialog>

    <!-- 屏蔽确认 -->
    <NativeDialog :open="blockConfirmOpen" :title="`屏蔽 ${blockConfirm.nickname}？`" @update:open="blockConfirmOpen = $event">
      <p>屏蔽后，对方的内容将不再出现在你的社区浏览中。</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="blockConfirmOpen = false">取消</button>
        <button type="button" class="button primary" :disabled="actionBusy" @click="blockUser">确认 <span class="icon"><el-icon><Right /></el-icon></span></button>
      </div>
    </NativeDialog>

    <!-- 分享链接回退 -->
    <NativeDialog :open="shareOpen" title="分享这篇帖子" @update:open="shareOpen = $event">
      <label for="share-url">帖子链接</label>
      <input id="share-url" type="text" readonly :value="shareUrl" @focus="($event.target as HTMLInputElement).select()">
      <div class="dialog-actions">
        <button type="button" class="button" @click="shareOpen = false">完成</button>
      </div>
    </NativeDialog>

    <ComposeDialog
      v-model:open="composeOpen"
      :eligible-groups="group ? [group] : []"
      :edit-post="post"
      @saved="onSaved"
    />
    <ReportDialog
      v-model:open="reportOpen"
      :target-type="reportTarget.type"
      :target-id="reportTarget.id"
      :target-title="reportTarget.title"
    />
  </div>
</template>
