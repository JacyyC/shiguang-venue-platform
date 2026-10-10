<template>
  <div class="sgv-user-service">
    <div class="d-top">
      <div>
        <p class="d-eyebrow">我的预约与订单</p>
        <h1>已经返回，继续核对付款结果。</h1>
        <p class="d-intro">支付页面的返回只表示回到拾光，订单结果仍需核对。</p>
      </div>
    </div>
    <ShiguangSandboxReturnPanel />
  </div>
</template>

<script setup lang="ts">
import ShiguangSandboxReturnPanel from '@/views/user/shiguang/transactions/ShiguangSandboxReturnPanel.vue'

/** 沙箱返回承接路由：路径由后端支付宝沙箱配置固定（/me/payments/alipay/sandbox/return）。 */
</script>
