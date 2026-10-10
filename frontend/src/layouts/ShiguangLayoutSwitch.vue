<template>
  <ShiguangLayout v-if="$route.meta.layout === 'shiguang'" />
  <UserLayout v-else />
</template>

<script setup lang="ts">
// 拾光一级浏览与旧页共用 '/' 父级路由：按子路由 meta 选择布局，旧深链/旧页全部保持原样。
import ShiguangLayout from './ShiguangLayout.vue'
import UserLayout from './UserLayout.vue'
</script>
