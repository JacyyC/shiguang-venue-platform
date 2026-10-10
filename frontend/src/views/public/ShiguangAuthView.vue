<template>
  <main
    ref="sceneRef"
    class="sgv-auth auth-scene"
    :class="{ 'is-arriving': arriving }"
    :data-mode="mode"
    :data-auth-focus="authFocused ? 'true' : 'false'"
    :data-motion="reduced ? 'reduced' : 'full'"
    :data-page-hidden="pageHidden ? 'true' : 'false'"
    aria-label="拾光品牌与登录"
  >
    <a class="sgv-skip-link" href="#sgv-auth-panel">跳到登录区域</a>
    <header class="auth-header">
      <button class="brand" type="button" aria-label="回到登录" @click="goMode('login')">
        <span>
          <svg class="wordmark" aria-hidden="true"><use href="#sgv-brand-sign" /></svg>
          <small>SHIGUANG</small>
        </span>
      </button>
      <span class="brand-purpose">日常之外，生活之中。</span>
      <div class="header-actions" />
    </header>
    <canvas ref="ambientRef" class="ambient-canvas" aria-hidden="true" />
    <div class="auth-wordmark" aria-hidden="true">
      <span>MAKE</span><span>TIME.</span>
      <small>把时间，留给喜欢。</small>
    </div>
    <div class="auth-art art-left" aria-hidden="true"><ShiguangArt scene="move" /></div>
    <div class="auth-art art-right" aria-hidden="true"><ShiguangArt scene="quiet" /></div>
    <div class="auth-side-note" aria-hidden="true">
      <span class="note-number">NO. 004</span>
      <span>一场球，一盏灯。<br>一段刚刚好的空闲。</span>
    </div>
    <div class="auth-center">
      <div v-if="mode === 'login'" class="auth-promise">
        <p class="eyebrow">A LITTLE TIME FOR YOURSELF</p>
        <h1><span class="sr-only">拾光</span><svg class="wordmark" aria-hidden="true"><use href="#sgv-brand-sign" /></svg></h1>
      </div>
      <section id="sgv-auth-panel" class="auth-panel" tabindex="-1" aria-labelledby="sgv-auth-title">
        <template v-if="!success">
          <div class="auth-form-head"><div><h2 id="sgv-auth-title">{{ title }}</h2></div></div>
          <p class="auth-description">{{ description }}</p>
          <form novalidate @submit.prevent="submit">
            <div v-for="field in fields" :key="field.id" class="field">
              <div class="field-heading">
                <label :for="field.id">{{ field.label }}</label>
              </div>
              <div class="input-wrap" :class="{ 'is-invalid': invalidField === field.id }">
                <input
                  :id="field.id"
                  ref="fieldRefs"
                  v-model="values[field.id]"
                  :name="field.id"
                  :type="field.type === 'password' && passwordVisible.has(field.id) ? 'text' : field.type"
                  :placeholder="field.placeholder"
                  :autocomplete="field.autocomplete"
                  :aria-invalid="invalidField === field.id ? 'true' : undefined"
                  @input="clearInvalid(field.id)"
                  @focus="focusedField = field.id"
                  @blur="onFieldBlur(field.id)"
                >
                <button
                  v-if="field.type === 'password'"
                  type="button"
                  class="password-toggle"
                  :aria-pressed="passwordVisible.has(field.id) ? 'true' : 'false'"
                  :aria-label="passwordVisible.has(field.id) ? '隐藏密码' : '显示密码'"
                  @click="togglePassword(field.id)"
                >
                  <svg aria-hidden="true"><use href="#sgv-eye" /></svg>
                </button>
              </div>
              <!--
                F-02 焦点顺序（SHIGUANG-WAVE-01-REMEDIATION-20260916 S03）：
                找回入口改到密码输入之后，Tab 顺序才是
                用户名 → 密码 → 显示密码 → 找回密码 → 登录。
                原先渲染在密码之前的 `.field-heading` 中，焦点序列变成
                用户名 → 找回密码 → 密码，不符合最高频输入路径。
                仍使用原生 button：不加正 tabindex，也不用 `tabindex="-1"`
                把合法控件移出键盘序列；面板内聚焦时重新高亮它，视觉归属不变。
              -->
              <button
                v-if="field.forgot"
                type="button"
                class="sgv-text-link field-forgot"
                :class="{ 'is-field-active': focusedField === field.id }"
                @click="goMode('forgot')"
              >忘记密码？</button>
              <small v-if="field.helper" class="field-helper">{{ field.helper }}</small>
            </div>
            <p class="form-error" role="alert">{{ errorMessage }}</p>
            <button class="primary-button" type="submit" :disabled="loading">
              <span>{{ submitLabel }}</span>
              <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
            </button>
          </form>
          <div class="auth-secondary">
            <template v-if="mode === 'login'">
              <span>还没有拾光账号？</span>
              <button type="button" @click="goMode('register')">注册一个</button>
            </template>
            <template v-else-if="mode === 'register'">
              <span>已经有账号？</span>
              <button type="button" @click="goMode('login')">回到登录</button>
            </template>
            <template v-else-if="forgotStep === 'username'">
              <span>想起来了？</span>
              <button type="button" @click="goMode('login')">回到登录</button>
            </template>
            <template v-else>
              <button type="button" @click="forgotStep = 'username'">重新填写用户名</button>
              <span>·</span>
              <button type="button" @click="goMode('login')">回到登录</button>
            </template>
          </div>
        </template>
        <template v-else>
          <div class="auth-success">
            <span class="success-mark"><svg aria-hidden="true"><use href="#sgv-check" /></svg></span>
            <h3>{{ success.title }}</h3>
            <p>{{ success.description }}</p>
            <button class="primary-button" type="button" @click="success.action">
              <span>{{ success.actionLabel }}</span>
              <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
            </button>
          </div>
          <div class="auth-secondary">
            <button type="button" @click="goMode('login')">回到登录入口</button>
          </div>
        </template>
      </section>
    </div>
    <footer class="auth-footer">
      <span>成都 · 场地与生活</span>
      <span class="micro">SHIGUANG / OFF HOURS CLUB</span>
    </footer>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { canAccessContentAdmin } from '@/utils/contentAdminAccess'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'

type Mode = 'login' | 'register' | 'forgot'

interface FieldDef {
  id: string
  label: string
  type?: string
  placeholder?: string
  helper?: string
  autocomplete?: string
  forgot?: boolean
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const mode = computed<Mode>(() => {
  if (route.name === 'Register') return 'register'
  if (route.name === 'ForgotPassword') return 'forgot'
  return 'login'
})

const sceneRef = ref<HTMLElement | null>(null)
const ambientRef = ref<HTMLCanvasElement | null>(null)
const arriving = ref(true)
const authFocused = ref(false)
const pageHidden = ref(false)
const reduced = ref(window.matchMedia('(prefers-reduced-motion: reduce)').matches)
const loading = ref(false)
const errorMessage = ref('')
const invalidField = ref('')
const success = ref<null | { title: string; description: string; actionLabel: string; action: () => void }>(null)
const passwordVisible = reactive(new Set<string>())
const values = reactive<Record<string, string>>({
  'login-username': '', 'login-password': '',
  'register-username': '', 'register-nickname': '', 'register-email': '',
  'register-password': '', 'register-confirm': '',
  'reset-username': '', 'reset-token': '', 'reset-password': ''
})
const forgotStep = ref<'username' | 'token'>('username')

const fieldRefs = ref<HTMLInputElement[]>([])

const fields = computed<FieldDef[]>(() => {
  if (mode.value === 'login') {
    return [
      { id: 'login-username', label: '用户名', placeholder: '输入你的用户名', autocomplete: 'username' },
      { id: 'login-password', label: '密码', type: 'password', placeholder: '输入密码', autocomplete: 'current-password', forgot: true }
    ]
  }
  if (mode.value === 'register') {
    return [
      { id: 'register-username', label: '用户名', placeholder: '3-32 位字符，用于登录', autocomplete: 'username' },
      { id: 'register-nickname', label: '怎么称呼你', placeholder: '例如：林光', autocomplete: 'nickname' },
      { id: 'register-email', label: '邮箱（可选）', type: 'email', placeholder: 'name@example.com', autocomplete: 'email' },
      { id: 'register-password', label: '设置密码', type: 'password', placeholder: '至少 6 位字符', autocomplete: 'new-password' },
      { id: 'register-confirm', label: '确认密码', type: 'password', placeholder: '再输入一次', autocomplete: 'new-password' }
    ]
  }
  if (forgotStep.value === 'username') {
    return [{ id: 'reset-username', label: '用户名', placeholder: '输入注册时的用户名', autocomplete: 'username', helper: '提交后，请通过受控渠道获取一次性重置凭证。' }]
  }
  return [
    { id: 'reset-token', label: '重置凭证', placeholder: '输入获取到的一次性凭证', autocomplete: 'one-time-code' },
    { id: 'reset-password', label: '新密码', type: 'password', placeholder: '至少 6 位字符', autocomplete: 'new-password' }
  ]
})

const title = computed(() => {
  if (mode.value === 'login') return '登录拾光'
  if (mode.value === 'register') return '创建拾光账号'
  return forgotStep.value === 'username' ? '找回密码' : '设置新密码'
})
const description = computed(() => {
  if (mode.value === 'login') return '把喜欢的事，放进今天。'
  if (mode.value === 'register') return '只需几步，把喜欢的时间留给自己。'
  return forgotStep.value === 'username' ? '输入用户名，继续完成找回流程。' : '设置完成后，你会回到登录入口。'
})
const submitLabel = computed(() => {
  if (mode.value === 'login') return '登录'
  if (mode.value === 'register') return '创建账号'
  return forgotStep.value === 'username' ? '提交申请' : '重置密码'
})

const goMode = (next: Mode) => {
  if (next === mode.value) return
  const target = next === 'register' ? '/register' : next === 'forgot' ? '/forgot-password' : '/login'
  router.push(target)
}

const clearInvalid = (id: string) => {
  if (invalidField.value === id) invalidField.value = ''
  errorMessage.value = ''
}

/**
 * F-02：找回入口移到密码之后仍要保持视觉归属。记录当前聚焦字段，让对应字段的
 * 找回入口在编辑该字段时保持强调（等价于原来 `.field-heading` 里紧贴 label 的观感），
 * 但不改变 DOM 顺序、不新增 tabindex。
 */
const focusedField = ref('')
const onFieldBlur = (id: string) => {
  if (focusedField.value === id) focusedField.value = ''
}

const togglePassword = (id: string) => {
  if (passwordVisible.has(id)) passwordVisible.delete(id)
  else passwordVisible.add(id)
}

const fail = (message: string, fieldId?: string) => {
  errorMessage.value = message
  if (fieldId) {
    invalidField.value = fieldId
    fieldRefs.value.find((input) => input.id === fieldId)?.focus()
  }
}

const defaultEntry = () => {
  if (auth.hasRole('SYSTEM_ADMIN')) return '/system-admin'
  if (auth.hasRole('VENUE_ADMIN')) return '/venue-admin'
  if (canAccessContentAdmin(auth.permissions)) return '/content-admin'
  return '/'
}

const redirectTarget = () => {
  const redirect = route.query.redirect
  if (typeof redirect !== 'string' || !redirect.startsWith('/')) return defaultEntry()
  const target = router.resolve(redirect)
  return target.meta.public || target.path === '/login' ? defaultEntry() : redirect
}

const failureMessage = (error: unknown, fallback: string) => {
  if (error && typeof error === 'object') {
    const message = (error as { response?: { data?: { message?: unknown } } }).response?.data?.message
    if (typeof message === 'string' && message.trim()) return message
  }
  return fallback
}

const submit = async () => {
  if (loading.value) return
  errorMessage.value = ''
  invalidField.value = ''
  if (mode.value === 'login') {
    if (!values['login-username'].trim()) return fail('请先填写用户名。', 'login-username')
    if (!values['login-password']) return fail('请输入密码。', 'login-password')
    loading.value = true
    try {
      await auth.login(values['login-username'].trim(), values['login-password'])
      const target = redirectTarget()
      // UI-02：完整品牌仪式只在「认证成功且目标为用户新首页」时执行
      if (target === '/') window.sessionStorage.setItem('sgv-entry-full', '1')
      await router.replace(target)
    } catch (error) {
      fail(failureMessage(error, '登录失败，请检查账号信息后重试'), 'login-password')
    } finally {
      loading.value = false
    }
    return
  }
  if (mode.value === 'register') {
    if (!values['register-username'].trim()) return fail('给自己留一个登录名吧。', 'register-username')
    if (!values['register-nickname'].trim()) return fail('给自己留一个称呼吧。', 'register-nickname')
    if (!values['register-password'] || values['register-password'].length < 6) {
      return fail('密码至少需要 6 位字符。', 'register-password')
    }
    if (values['register-password'] !== values['register-confirm']) {
      return fail('两次输入的密码不一致。', 'register-confirm')
    }
    loading.value = true
    try {
      await authApi.register({
        username: values['register-username'].trim(),
        nickname: values['register-nickname'].trim(),
        email: values['register-email'].trim() || undefined,
        password: values['register-password']
      })
      success.value = {
        title: '账号已创建',
        description: '用刚刚设置的账号登录，把喜欢的事放进今天。',
        actionLabel: '去登录',
        action: () => goMode('login')
      }
      ElMessage.success('注册成功，请登录')
    } catch (error) {
      fail(failureMessage(error, '注册失败，请稍后重试'))
    } finally {
      loading.value = false
    }
    return
  }
  // 找回密码（Q1：用户名 → 受控渠道凭证 → 新密码）
  if (forgotStep.value === 'username') {
    if (!values['reset-username'].trim()) return fail('请输入用户名。', 'reset-username')
    loading.value = true
    try {
      await authApi.forgotPassword({ username: values['reset-username'].trim() })
      forgotStep.value = 'token'
      ElMessage.success('申请已提交，请获取重置凭证')
    } catch (error) {
      fail(failureMessage(error, '申请提交失败，请稍后重试'))
    } finally {
      loading.value = false
    }
    return
  }
  if (!values['reset-token'].trim()) return fail('请输入重置凭证。', 'reset-token')
  if (!values['reset-password'] || values['reset-password'].length < 6) {
    return fail('密码至少需要 6 位字符。', 'reset-password')
  }
  loading.value = true
  try {
    await authApi.resetPassword({ token: values['reset-token'].trim(), password: values['reset-password'] })
    success.value = {
      title: '密码已更新',
      description: '设置完成后，回到登录入口，把喜欢的事放进今天。',
      actionLabel: '进入拾光',
      action: () => goMode('login')
    }
  } catch (error) {
    fail(failureMessage(error, '重置失败，请重新申请'))
  } finally {
    loading.value = false
  }
}

let arrivingTimer = 0
let parallaxFrame = 0

const resetParallax = () => {
  window.cancelAnimationFrame(parallaxFrame)
  parallaxFrame = 0
  sceneRef.value?.style.removeProperty('--pointer-x')
  sceneRef.value?.style.removeProperty('--pointer-y')
}

const onPointerMove = (event: PointerEvent) => {
  if (reduced.value || pageHidden.value || authFocused.value || event.pointerType !== 'mouse') return
  window.cancelAnimationFrame(parallaxFrame)
  parallaxFrame = window.requestAnimationFrame(() => {
    const rect = sceneRef.value?.getBoundingClientRect()
    if (!rect) return
    sceneRef.value?.style.setProperty('--pointer-x', `${((event.clientX - rect.left) / rect.width - .5) * 12}px`)
    sceneRef.value?.style.setProperty('--pointer-y', `${((event.clientY - rect.top) / rect.height - .5) * 10}px`)
    parallaxFrame = 0
  })
}

const onPointerLeave = () => resetParallax()

const onFocusIn = (event: FocusEvent) => {
  if ((event.target as HTMLElement).closest('form')) {
    arriving.value = false
    authFocused.value = true
    resetParallax()
  }
}

const onFocusOut = (event: FocusEvent) => {
  if ((event.target as HTMLElement).closest('form') && !(event.relatedTarget as HTMLElement | null)?.closest('form')) {
    authFocused.value = false
  }
}

const onVisibility = () => {
  pageHidden.value = document.hidden
  if (document.hidden) arriving.value = false
}

const media = window.matchMedia('(prefers-reduced-motion: reduce)')
const onMediaChange = (event: MediaQueryListEvent) => {
  reduced.value = event.matches
  if (event.matches) {
    arriving.value = false
    resetParallax()
  }
}

const paintAmbient = () => {
  const canvas = ambientRef.value
  if (!canvas || reduced.value) return
  const width = canvas.clientWidth
  const height = canvas.clientHeight
  if (!width || !height) return
  const scale = Math.min(window.devicePixelRatio || 1, 2)
  canvas.width = Math.round(width * scale)
  canvas.height = Math.round(height * scale)
  const context = canvas.getContext('2d')
  if (!context) return
  context.scale(scale, scale)
  const plane = (points: Array<[number, number]>, fill: string, shadow = false) => {
    context.save()
    context.fillStyle = fill
    if (shadow) { context.shadowColor = '#6b837129'; context.shadowBlur = 28 }
    context.beginPath()
    points.forEach(([horizontal, vertical], index) => {
      if (index === 0) context.moveTo(horizontal * width, vertical * height)
      else context.lineTo(horizontal * width, vertical * height)
    })
    context.closePath()
    context.fill()
    context.restore()
  }
  plane([[0, 0], [.34, 0], [.12, 1], [0, 1]], '#f5f8ef70')
  plane([[.86, 0], [1, 0], [1, .86], [.70, 1]], '#9ab09d12')
  plane([[0, .72], [.33, .88], [.28, 1], [0, .90]], '#f5f8ef57')
  plane([[.80, .61], [1, .66], [1, .77], [.70, .72]], '#8fa69816', true)
}

const ambientObserver = new ResizeObserver(() => paintAmbient())

onMounted(() => {
  ambientObserver.observe(ambientRef.value as HTMLElement)
  paintAmbient()
  arrivingTimer = window.setTimeout(() => { arriving.value = false }, 1100)
  document.addEventListener('visibilitychange', onVisibility)
  sceneRef.value?.addEventListener('pointermove', onPointerMove, { passive: true })
  sceneRef.value?.addEventListener('pointerleave', onPointerLeave)
  sceneRef.value?.addEventListener('focusin', onFocusIn)
  sceneRef.value?.addEventListener('focusout', onFocusOut)
  media.addEventListener('change', onMediaChange)
})

onBeforeUnmount(() => {
  window.clearTimeout(arrivingTimer)
  window.cancelAnimationFrame(parallaxFrame)
  document.removeEventListener('visibilitychange', onVisibility)
  media.removeEventListener('change', onMediaChange)
  ambientObserver.disconnect()
})

watch(mode, () => {
  errorMessage.value = ''
  invalidField.value = ''
  success.value = null
  forgotStep.value = 'username'
  for (const key of Object.keys(values)) values[key] = ''
  passwordVisible.clear()
  window.scrollTo({ top: 0, behavior: 'instant' })
})
</script>
