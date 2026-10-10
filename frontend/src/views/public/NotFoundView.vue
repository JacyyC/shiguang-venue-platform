<template>
  <main class="simple-page" aria-labelledby="notfound-title">
    <section class="simple-panel">
      <span class="simple-code" aria-hidden="true">404</span>
      <h1 id="notfound-title">页面不存在</h1>
      <p>链接可能已经失效，或地址输入有误。返回上一页重新选择即可。</p>
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
