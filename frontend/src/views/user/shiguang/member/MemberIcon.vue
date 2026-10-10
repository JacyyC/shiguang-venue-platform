<script setup lang="ts">
import { computed } from 'vue'

/**
 * 会员中心自定义线框图标（冻结参考 app.js memberIcons 逐字节同源：
 * Coin/Ticket/Wallet/InfoFilled，stroke 1.5、24 视窗、非填充）。
 */
const props = defineProps<{ name: 'Coin' | 'Ticket' | 'Wallet' | 'InfoFilled' }>()

const paths: Record<string, string> = {
  Coin: '<circle cx="12" cy="12" r="8.5"/><path d="M9.5 8.5h3.3a2 2 0 0 1 0 4H10m0-4v8m0-4h3"/>',
  Ticket: '<path d="M3 6h18v4a2 2 0 0 0 0 4v4H3v-4a2 2 0 0 0 0-4Z"/><path d="M9 6v2m0 3v2m0 3v2"/>',
  Wallet: '<path d="M20 7H5a2 2 0 0 1 0-4h12v4M3 5v14a2 2 0 0 0 2 2h15V7m0 5h-5v5h5"/><circle cx="16.5" cy="14.5" r=".5" fill="currentColor" stroke="none"/>',
  InfoFilled: '<circle cx="12" cy="12" r="9"/><path d="M12 10v6m0-9h.01"/>'
}

const markup = computed(
  () => `<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[props.name]}</svg>`
)
</script>

<template>
  <span class="icon member-icon" aria-hidden="true" v-html="markup" />
</template>
