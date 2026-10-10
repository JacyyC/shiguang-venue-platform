<template>
  <main class="auth-page">
    <div class="auth-halo" aria-hidden="true"></div>
    <section class="auth-panel sg-focus-in">
      <div class="auth-brand" aria-label="拾光 · 场地空间与活动平台">
        <span class="auth-brand-mark" aria-hidden="true">拾<i>光</i></span>
        <span class="auth-brand-copy">
          <strong>拾光 Shiguang</strong>
          <small>场地空间与活动平台</small>
        </span>
      </div>
      <p class="sg-kicker auth-kicker">登录</p>
      <h1>欢迎回来</h1>
      <p class="auth-subtitle">登录后继续查找场地、管理预约和查看最新消息。</p>
      <p v-if="errorMessage" class="sg-state-error" role="alert">{{ errorMessage }}</p>
      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" :disabled="loading" class="full">登录</el-button>
      </el-form>
      <div class="auth-links">
        <router-link to="/register">注册账号</router-link>
        <router-link to="/forgot-password">找回密码</router-link>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { canAccessContentAdmin } from '@/utils/contentAdminAccess'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const errorMessage = ref('')
const demoLoginPrefill = import.meta.env.VITE_DEMO_LOGIN_PREFILL === 'true'
const form = reactive({
  username: demoLoginPrefill ? 'admin' : '',
  password: demoLoginPrefill ? 'password' : ''
})

const defaultEntry = () => {
  if (auth.hasRole('SYSTEM_ADMIN')) return '/system-admin'
  if (auth.hasRole('VENUE_ADMIN')) return '/venue-admin'
  if (canAccessContentAdmin(auth.permissions)) return '/content-admin'
  return '/'
}

const redirectTarget = () => {
  const redirect = route.query.redirect
  if (typeof redirect !== 'string' || !redirect.startsWith('/')) {
    return defaultEntry()
  }
  const target = router.resolve(redirect)
  return target.meta.public || target.path === '/login' ? defaultEntry() : redirect
}

const loginFailureMessage = (error: unknown) => {
  if (error && typeof error === 'object') {
    const message = (error as { response?: { data?: { message?: unknown } } }).response?.data?.message
    if (typeof message === 'string' && message.trim()) return message
  }
  return error instanceof Error && error.message ? error.message : '登录失败，请检查账号信息后重试'
}

const submit = async () => {
  if (loading.value) return
  errorMessage.value = ''
  if (!form.username.trim() || !form.password) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    await router.replace(redirectTarget())
    ElMessage.success('登录成功')
  } catch (error) {
    errorMessage.value = loginFailureMessage(error)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
@import './auth.css';
</style>
