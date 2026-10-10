<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Right } from '@element-plus/icons-vue'
import {
  communityApi,
  communityError,
  createIdempotencyAttempt,
  type CommunityGroup
} from '@/api/community'
import NativeDialog from './NativeDialog.vue'

/**
 * 创建/编辑小组对话框（冻结参考 groupForm 同款）。
 * 类别来自 community_group_category 字典（父级加载并透传）。
 */
const props = defineProps<{
  open: boolean
  categories: Array<{ value: string; label: string }>
  editGroup?: CommunityGroup | null
}>()
const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'saved', group: CommunityGroup): void
}>()

const attempt = createIdempotencyAttempt('group-form')
const name = ref('')
const description = ref('')
const category = ref('')
const visibility = ref<'PLATFORM_VISIBLE' | 'MEMBERS_ONLY'>('PLATFORM_VISIBLE')
const joinPolicy = ref<'DIRECT' | 'REVIEW_REQUIRED'>('DIRECT')
const error = ref('')
const submitting = ref(false)

watch(
  () => props.open,
  (value) => {
    if (!value) return
    error.value = ''
    name.value = props.editGroup?.name ?? ''
    description.value = props.editGroup?.description ?? ''
    category.value = props.editGroup?.categoryCode ?? props.categories[0]?.value ?? ''
    visibility.value = props.editGroup?.visibility ?? 'PLATFORM_VISIBLE'
    joinPolicy.value = props.editGroup?.joinPolicy ?? 'DIRECT'
  }
)

const close = () => emit('update:open', false)

const submit = async () => {
  if (submitting.value) return
  if (!name.value.trim()) {
    error.value = '请填写小组名称。'
    return
  }
  if (!category.value) {
    error.value = '暂无可选类别，请稍后再试。'
    return
  }
  error.value = ''
  submitting.value = true
  try {
    if (props.editGroup) {
      const payload = {
        name: name.value.trim(),
        description: description.value.trim() || undefined,
        visibility: visibility.value,
        joinPolicy: joinPolicy.value,
        category: category.value,
        versionNo: props.editGroup.versionNo
      }
      const saved = await communityApi.updateGroup(props.editGroup.id, payload, attempt.keyFor(payload))
      attempt.complete()
      emit('saved', saved)
      ElMessage({ message: '小组资料已保存', type: 'success' })
    } else {
      const payload = {
        name: name.value.trim(),
        description: description.value.trim() || undefined,
        visibility: visibility.value,
        joinPolicy: joinPolicy.value,
        category: category.value
      }
      const saved = await communityApi.createGroup(payload, attempt.keyFor(payload))
      attempt.complete()
      emit('saved', saved)
      ElMessage({ message: '小组资料已保存', type: 'success' })
    }
    close()
  } catch (err) {
    error.value = communityError(err, '保存失败，请稍后重试').message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <NativeDialog :open="open" :title="editGroup ? '编辑小组资料' : '创建一个小组'" @update:open="emit('update:open', $event)">
    <form data-test="group-form" @submit.prevent="submit">
      <label for="group-name">小组名称</label>
      <input
        id="group-name"
        v-model="name"
        type="text"
        maxlength="40"
        required
        placeholder="给你的同好圈子起个名字"
      >
      <label for="group-description">小组简介</label>
      <textarea
        id="group-description"
        v-model="description"
        maxlength="300"
        rows="3"
        placeholder="想和什么样的人，分享怎样的日常？"
      />
      <label for="group-category">类别</label>
      <select id="group-category" v-model="category">
        <option v-for="option in categories" :key="option.value" :value="option.value">{{ option.label }}</option>
      </select>
      <label for="group-visibility">内容可见范围</label>
      <select id="group-visibility" v-model="visibility">
        <option value="PLATFORM_VISIBLE">广场可见</option>
        <option value="MEMBERS_ONLY">仅成员可见</option>
      </select>
      <label for="group-policy">加入方式</label>
      <select id="group-policy" v-model="joinPolicy">
        <option value="DIRECT">直接加入</option>
        <option value="REVIEW_REQUIRED">审核后加入</option>
      </select>
      <p class="dialog-error" role="alert">{{ error }}</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="close">取消</button>
        <button class="button primary" type="submit" :disabled="submitting">
          {{ editGroup ? '保存资料' : '创建小组' }} <span class="icon"><el-icon><Right /></el-icon></span>
        </button>
      </div>
    </form>
  </NativeDialog>
</template>
