<script setup lang="ts">
import { computed } from 'vue'
import ShiguangArt from './ShiguangArt.vue'
import { formatShiguangAmount } from '@/utils/money'
import type { ShiguangScene } from './types'

const props = withDefaults(defineProps<{
  dateLabel: string
  timeLabel: string
  detailLabel: string
  venueName?: string
  spaceName?: string
  unitPrice?: number | null
  quantityLine?: string
  quantityAmount?: number | null
  pendingLabel?: string
  payable?: number | null
  quoteError?: string
  loading?: boolean
  submitLabel: string
  submitDisabled?: boolean
  hint?: string
  confirm?: boolean
  scene?: ShiguangScene
  trustText?: string
}>(), {
  venueName: '',
  spaceName: '',
  unitPrice: null,
  quantityLine: '',
  quantityAmount: null,
  pendingLabel: '',
  payable: null,
  quoteError: '',
  loading: false,
  submitDisabled: false,
  hint: '',
  confirm: false,
  scene: 'together',
  trustText: '审核通过后再付款'
})

const emit = defineEmits<{ submit: [] }>()

const money = formatShiguangAmount

const hasSelection = computed(() => Boolean(props.quantityLine))
/** 第二行只陈述时长与服务端金额；人数/座位等资源事实在上方明细行呈现。 */
const quantityLabel = computed(() => `时长 ${props.quantityLine}`)
const totalText = computed(() => {
  if (props.quoteError || props.payable === null || props.payable === undefined) return '—'
  return money(props.payable)
})
</script>

<template>
  <div class="summary-heading">
    <h2>{{ props.confirm ? '这次预约' : '留给自己的时间' }}</h2>
    <svg class="icon" aria-hidden="true"><use href="#sgv-calendar" /></svg>
  </div>
  <p class="summary-date">{{ props.dateLabel }}</p>
  <h3 class="summary-time" :class="{ 'is-empty': !hasSelection }">{{ props.timeLabel }}</h3>
  <p class="summary-duration">{{ hasSelection ? props.detailLabel : (props.pendingLabel || '从左侧选择时段，慢慢安排。') }}</p>
  <div class="summary-place">
    <strong>{{ props.venueName || '场馆待确认' }}</strong>
    <p>{{ props.spaceName || '场地待确认' }}</p>
  </div>
  <div class="summary-lines">
    <div class="cost-row"><span>场地单价</span><span>{{ props.unitPrice === null || props.unitPrice === undefined ? '—' : `¥${money(props.unitPrice)} / 小时` }}</span></div>
    <div v-if="hasSelection" class="cost-row">
      <span>{{ quantityLabel }}</span>
      <span>{{ props.quantityAmount === null || props.quantityAmount === undefined ? '待服务端计算' : `¥${money(props.quantityAmount)}` }}</span>
    </div>
  </div>
  <div class="cost-total">
    <span>{{ props.confirm ? '预计应付' : '预计费用' }}</span>
    <strong v-if="totalText !== '—'"><small>¥</small>{{ totalText }}</strong>
    <strong v-else>—</strong>
  </div>
  <p v-if="props.loading" class="fee-note">正在按服务端计价核对…</p>
  <p v-else-if="props.quoteError" class="fee-note">{{ props.quoteError }}{{ props.confirm ? '，请先重新计算再提交。' : '，最终费用在确认页核对。' }}</p>
  <p v-else class="fee-note">{{ props.confirm ? '提交后等待审核，通过后再支付。' : '优惠与最终费用在确认页核对。' }}</p>
  <button class="primary-button" type="button" :disabled="props.submitDisabled" data-test="summary-submit" @click="emit('submit')">
    <span>{{ props.submitLabel }}</span><svg class="icon" aria-hidden="true"><use href="#sgv-arrow" /></svg>
  </button>
  <p v-if="props.submitDisabled && props.hint" class="submit-hint" role="status" data-test="summary-hint">{{ props.hint }}</p>
  <p class="summary-trust"><svg class="icon" aria-hidden="true"><use href="#sgv-shield" /></svg>{{ props.trustText }}</p>
  <div class="summary-micro-art" aria-hidden="true"><ShiguangArt :scene="props.scene" /></div>
</template>
