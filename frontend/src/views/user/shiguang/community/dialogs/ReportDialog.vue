<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Right } from '@element-plus/icons-vue'
import { communityApi, communityError, createIdempotencyAttempt } from '@/api/community'
import NativeDialog from './NativeDialog.vue'

/**
 * 举报对话框（冻结参考 reportDialog 同款）。
 * 举报结果以服务端为准；重复提交经幂等键回放，不产生第二条举报。
 */
const props = defineProps<{
  open: boolean
  targetType: 'GROUP' | 'POST' | 'COMMENT'
  targetId: number | null
  targetTitle: string
}>()
const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'submitted'): void
}>()

const attempt = createIdempotencyAttempt('report')
const reason = ref('')
const error = ref('')
const submitting = ref(false)

watch(
  () => props.open,
  (value) => {
    if (!value) return
    reason.value = ''
    error.value = ''
  }
)

const close = () => emit('update:open', false)

const submit = async () => {
  if (submitting.value) return
  if (!reason.value.trim()) {
    error.value = '请说明举报原因。'
    return
  }
  if (props.targetId == null) return
  error.value = ''
  submitting.value = true
  try {
    const payload = { targetType: props.targetType, targetId: props.targetId, reason: reason.value.trim() }
    await communityApi.report(payload, attempt.keyFor(payload))
    attempt.complete()
    emit('submitted')
    ElMessage({ message: '举报已提交，可在我的举报中查看', type: 'success' })
    close()
  } catch (err) {
    error.value = communityError(err, '举报提交失败，请稍后重试').message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <NativeDialog :open="open" title="举报内容" @update:open="emit('update:open', $event)">
    <form data-test="report-form" @submit.prevent="submit">
      <p>{{ targetTitle }}</p>
      <label for="report-reason">举报原因</label>
      <textarea
        id="report-reason"
        v-model="reason"
        maxlength="1000"
        required
        rows="4"
        placeholder="说明你遇到的问题"
      />
      <p class="dialog-error" role="alert">{{ error }}</p>
      <div class="dialog-actions">
        <button type="button" class="text-button" @click="close">取消</button>
        <button class="button primary" type="submit" :disabled="submitting">
          提交举报 <span class="icon"><el-icon><Right /></el-icon></span>
        </button>
      </div>
    </form>
  </NativeDialog>
</template>
