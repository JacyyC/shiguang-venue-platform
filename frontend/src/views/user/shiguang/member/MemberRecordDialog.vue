<script setup lang="ts">
import { computed } from 'vue'
import { Check, Clock } from '@element-plus/icons-vue'
import type { MemberRewardRedemptionResponse } from '@/api/types'
import MemberDialog from './MemberDialog.vue'
import { formatMemberDate, recordDetailText, recordPointsText, recordStatusText } from './memberShared'

/**
 * 权益记录弹层（预览 openRecord/showRewardResult 同款）。
 * PENDING 为结果待确认变体：保留原请求身份，允许核对，不重复提交（G8 语义）。
 */
const props = defineProps<{ open: boolean; record: MemberRewardRedemptionResponse | null; checking?: boolean }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void; (e: 'check', record: MemberRewardRedemptionResponse): void; (e: 'go-records'): void }>()

const record = computed(() => props.record)
const pending = computed(() => (record.value?.status ?? '').toUpperCase() === 'PENDING')
const title = computed(() => (pending.value ? '结果待确认' : '权益记录'))
</script>

<template>
  <MemberDialog :open="open" :title="title" @update:open="emit('update:open', $event)">
    <template v-if="record">
      <template v-if="pending">
        <div class="dialog-mark pending"><el-icon><Clock /></el-icon></div>
        <h3>先核对，不重复兑换。</h3>
        <p>本次兑换结果尚未确认。保留同一请求身份，积分与券夹暂不推定变化；关掉页面也不代表取消。</p>
        <div style="margin-top:24px">
          <dl class="detail-list">
            <div><dt>权益</dt><dd>{{ record.itemName }}</dd></div>
            <div><dt>请求记录</dt><dd>{{ record.redemptionNo }}</dd></div>
            <div><dt>当前结果</dt><dd>处理中 · 非成功</dd></div>
          </dl>
        </div>
      </template>
      <template v-else>
        <h3>{{ record.itemName }}</h3>
        <p>{{ recordDetailText(record) }}</p>
        <div class="dialog-cost"><strong class="num">{{ recordPointsText(record) }}</strong><small>积分</small></div>
        <dl class="detail-list">
          <div><dt>兑换记录</dt><dd>{{ record.redemptionNo }}</dd></div>
          <div><dt>日期</dt><dd>{{ formatMemberDate(record.createTime) }}</dd></div>
          <div><dt>状态</dt><dd>{{ recordStatusText(record) }}</dd></div>
          <div><dt>成长值</dt><dd>兑换不扣减成长值</dd></div>
        </dl>
      </template>
    </template>
    <template #actions>
      <template v-if="pending && record">
        <button class="button" type="button" @click="emit('go-records')">查看记录</button>
        <button class="button primary" type="button" :disabled="checking" @click="emit('check', record)">
          {{ checking ? '核对原请求中…' : '核对原请求' }}
        </button>
      </template>
      <button v-else class="button primary" type="button" @click="emit('update:open', false)">知道了</button>
    </template>
  </MemberDialog>
</template>
