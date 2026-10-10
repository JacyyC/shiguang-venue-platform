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
      <p class="sg-kicker auth-kicker">找回密码</p>
      <h1>找回密码</h1>
      <p class="auth-subtitle">提交账号后，请使用受控渠道收到的一次性重置凭证设置新密码。</p>
      <p v-if="errorMessage" class="sg-state-error" role="alert">{{ errorMessage }}</p>
      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model.trim="form.username" autocomplete="username" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="full">提交申请</el-button>
      </el-form>
      <p v-if="submitted" class="auth-success" role="status">申请已提交，请获取重置凭证后完成重置。</p>
      <el-form v-if="submitted" :model="resetForm" label-position="top" @submit.prevent="resetPassword">
        <el-form-item label="重置凭证">
          <el-input v-model.trim="resetForm.token" autocomplete="one-time-code" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="resetForm.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-button native-type="submit" :loading="resetting" class="full">重置密码</el-button>
      </el-form>
      <router-link class="back" to="/login">返回登录</router-link>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/auth'

const form = reactive({ username: '' })
const resetForm = reactive({ token: '', password: '' })
const loading = ref(false)
const resetting = ref(false)
const submitted = ref(false)
const errorMessage = ref('')

const submit = async () => {
  if (loading.value) return
  errorMessage.value = ''
  submitted.value = false
  if (!form.username) {
    errorMessage.value = '请输入用户名'
    return
  }
  loading.value = true
  try {
    await authApi.forgotPassword({ username: form.username })
    submitted.value = true
    ElMessage.success('申请已提交')
  } catch (error) {
    errorMessage.value = error instanceof Error && error.message ? error.message : '申请提交失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

const resetPassword = async () => {
  if (resetting.value) return
  errorMessage.value = ''
  if (!resetForm.token || !resetForm.password) {
    errorMessage.value = '请输入重置凭证和新密码'
    return
  }
  resetting.value = true
  try {
    await authApi.resetPassword(resetForm)
    ElMessage.success('密码已重置，请重新登录')
    resetForm.token = ''
    resetForm.password = ''
  } catch (error) {
    errorMessage.value = error instanceof Error && error.message ? error.message : '重置失败，请重新申请'
  } finally {
    resetting.value = false
  }
}
</script>

<style scoped>
@import './auth.css';
</style>
