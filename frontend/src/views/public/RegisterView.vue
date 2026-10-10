<template>
  <main class="auth-page">
    <section class="auth-panel sg-focus-in">
      <div class="auth-brand" aria-label="拾光 · 场地空间与活动平台">
        <span class="auth-brand-mark" aria-hidden="true">拾<i>光</i></span>
        <span class="auth-brand-copy">
          <strong>拾光 Shiguang</strong>
          <small>场地空间与活动平台</small>
        </span>
      </div>
      <p class="sg-kicker auth-kicker">注册</p>
      <h1>注册账号</h1>
      <p class="auth-subtitle">创建账号后即可收藏场馆、提交预约并接收进度通知。</p>
      <p v-if="errorMessage" class="sg-state-error" role="alert">{{ errorMessage }}</p>
      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" required><el-input v-model.trim="form.username" autocomplete="username" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model.trim="form.nickname" autocomplete="nickname" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model.trim="form.email" type="email" autocomplete="email" /></el-form-item>
        <el-form-item label="密码" required><el-input v-model="form.password" type="password" autocomplete="new-password" show-password /></el-form-item>
        <el-form-item label="确认密码" required><el-input v-model="form.confirmPassword" type="password" autocomplete="new-password" show-password /></el-form-item>
        <el-button type="primary" native-type="submit" class="full" :loading="loading">注册</el-button>
      </el-form>
      <router-link class="back" to="/login">返回登录</router-link>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const errorMessage = ref('')
const form = reactive({ username: '', nickname: '', email: '', password: '', confirmPassword: '' })

const registerFieldLabels: Record<string, string> = {
  username: '用户名',
  nickname: '昵称',
  phone: '手机号',
  email: '邮箱',
  password: '密码'
}

const readableRegisterError = (error: unknown) => {
  const response = (error as { response?: { data?: { data?: unknown; message?: unknown } } })?.response?.data
  if (response && typeof response.data === 'object' && response.data !== null) {
    const fieldErrors = response.data as Record<string, unknown>
    const [field, message] = Object.entries(fieldErrors).find(([, value]) => typeof value === 'string' && value.trim()) || []
    if (field && typeof message === 'string') {
      return `${registerFieldLabels[field] || field}：${message}`
    }
  }
  if (typeof response?.message === 'string' && response.message.trim()) return response.message
  return '注册失败，请稍后重试'
}

const submit = async () => {
  if (loading.value) return
  errorMessage.value = ''
  if (!form.username || !form.password || !form.confirmPassword) {
    errorMessage.value = '请填写用户名、密码和确认密码'
    return
  }
  if (form.password.length < 6 || form.password.length > 64) {
    errorMessage.value = '密码长度需为 6-64 位'
    return
  }
  if (form.password !== form.confirmPassword) {
    errorMessage.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    await authApi.register({
      username: form.username,
      nickname: form.nickname,
      email: form.email,
      password: form.password
    })
    // 注册成功即复用登录逻辑写入会话；自动登录失败时退回手动登录，不阻塞新用户。
    try {
      await auth.login(form.username, form.password)
    } catch {
      ElMessage.info('注册成功，请登录')
      await router.replace('/login')
      return
    }
    await router.replace('/')
    ElMessage.success('欢迎加入拾光')
  } catch (error) {
    errorMessage.value = readableRegisterError(error)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
@import './auth.css';
</style>
