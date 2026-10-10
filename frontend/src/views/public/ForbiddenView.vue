<template>
  <main class="simple-page" aria-labelledby="forbidden-title">
    <section class="simple-panel">
      <span class="simple-code" aria-hidden="true">403</span>
      <h1 id="forbidden-title">无权访问</h1>
      <p>当前账号暂时无法打开这个页面。你可以返回上一页，或回到首页继续使用。</p>
      <div class="simple-actions">
        <el-button @click="$router.back()">返回上一页</el-button>
        <el-button type="primary" @click="$router.replace('/')">回到首页</el-button>
      </div>
    </section>
  </main>
</template>

<style scoped>
@import './auth.css';
</style>
