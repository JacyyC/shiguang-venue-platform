<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Lock, Right, View } from '@element-plus/icons-vue'
import {
  communityApi,
  communityError,
  createIdempotencyAttempt,
  type CommunityGroup,
  type CommunityPost
} from '@/api/community'
import NativeDialog from './NativeDialog.vue'
import { visibilityLabel } from '../communityShared'

/**
 * 发布/编辑帖子对话框（冻结参考 compose 同款）。
 * 服务端结果是唯一成功依据：幂等键按 payload 复用，成功后才 complete 并关闭。
 */
const props = defineProps<{
  open: boolean
  eligibleGroups: CommunityGroup[]
  editPost?: CommunityPost | null
  preselectedGroupId?: number | null
}>()
const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'published', post: CommunityPost): void
  (e: 'saved', post: CommunityPost): void
}>()

const attempt = createIdempotencyAttempt('post-compose')
const groupId = ref<number | null>(null)
const title = ref('')
const content = ref('')
const error = ref('')
const submitting = ref(false)

const selectedGroup = computed(() =>
  props.eligibleGroups.find((group) => group.id === groupId.value) ?? null
)
const selectedVisibility = computed(() => selectedGroup.value?.visibility ?? 'PLATFORM_VISIBLE')

watch(
  () => props.open,
  (value) => {
    if (!value) return
    error.value = ''
    title.value = props.editPost?.title ?? ''
    content.value = props.editPost?.content ?? ''
    groupId.value = props.editPost?.groupId
      ?? props.preselectedGroupId
      ?? props.eligibleGroups[0]?.id
      ?? null
  }
)

const close = () => emit('update:open', false)

const submit = async () => {
  if (submitting.value) return
  if (!title.value.trim() || !content.value.trim()) {
    error.value = '标题和正文都需要写一点内容。'
    return
  }
  error.value = ''
  submitting.value = true
  try {
    if (props.editPost) {
      const payload = { title: title.value.trim(), content: content.value.trim(), versionNo: props.editPost.versionNo }
      const saved = await communityApi.updatePost(props.editPost.id, payload, attempt.keyFor(payload))
      attempt.complete()
      emit('saved', saved)
      ElMessage({ message: '修改已保存', type: 'success' })
    } else {
      if (groupId.value == null) {
        error.value = '先选择一个小组。'
        submitting.value = false
        return
      }
      const payload = { groupId: groupId.value, title: title.value.trim(), content: content.value.trim() }
      const published = await communityApi.createPost(payload, attempt.keyFor(payload))
      attempt.complete()
      emit('published', published)
      ElMessage({ message: '你的分享已发布', type: 'success' })
    }
    close()
  } catch (err) {
    // 响应可能已到达服务端：保留幂等键，用户重试走服务端回放语义，不产生重复写入
    error.value = communityError(err, '发布失败，请稍后重试').message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <NativeDialog :open="open" :title="editPost ? '编辑这篇分享' : '写下一点日常'" @update:open="emit('update:open', $event)">
    <form data-test="compose-form" @submit.prevent="submit">
      <label for="compose-group">发布到</label>
      <select id="compose-group" v-model="groupId" :disabled="!!editPost">
        <option v-for="group in eligibleGroups" :key="group.id" :value="group.id">{{ group.name }}</option>
      </select>
      <p class="field-hint">
        <span class="icon"><el-icon><Lock v-if="selectedVisibility === 'MEMBERS_ONLY'" /><View v-else /></el-icon></span>
        {{ visibilityLabel(selectedVisibility) }}
      </p>
      <label for="compose-title">标题</label>
      <input
        id="compose-title"
        v-model="title"
        type="text"
        maxlength="80"
        required
        placeholder="给这段日常起个名字"
      >
      <label for="compose-content">正文</label>
      <textarea
        id="compose-content"
        v-model="content"
        maxlength="5000"
        rows="7"
        required
        placeholder="从一个瞬间、一句话，或一件小事开始…"
      />
      <p class="dialog-error" role="alert">{{ error }}</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="close">取消</button>
        <button class="button primary" type="submit" :disabled="submitting">
          {{ editPost ? '保存修改' : '发布帖子' }} <span class="icon"><el-icon><Right /></el-icon></span>
        </button>
      </div>
    </form>
  </NativeDialog>
</template>
