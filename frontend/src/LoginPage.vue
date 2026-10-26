<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { authApi } from './api'

const emit = defineEmits(['login'])
const mode = ref('login')
const form = ref({ username: '', password: '', confirmPassword: '' })
const error = ref('')
const notice = ref('')
const loading = ref(false)
const bgVideo = ref(null)
let videoLoopTimer = null
let errorTimer = null
let noticeTimer = null

const title = computed(() => mode.value === 'login' ? '登录' : mode.value === 'register' ? '注册' : '忘记密码')

function resetForm() {
  form.value = { username: '', password: '', confirmPassword: '' }
  error.value = ''
  notice.value = ''
}

function switchMode(nextMode) {
  mode.value = nextMode
  resetForm()
}

function validatePassword(value, label = '密码') {
  if (!value || value.length < 8) return `${label}长度至少 8 位`
  if (value.length > 72) return `${label}长度不能超过 72 位`
  return ''
}

async function submit() {
  error.value = ''
  notice.value = ''

  if (!form.value.username.trim() || !form.value.password.trim()) {
    error.value = '请填写用户名和密码'
    return
  }
  const passwordIssue = validatePassword(form.value.password)
  if (passwordIssue) {
    error.value = passwordIssue
    return
  }
  if (mode.value === 'register' && form.value.password !== form.value.confirmPassword) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    const payload = { username: form.value.username.trim(), password: form.value.password }
    const auth = mode.value === 'register' ? await authApi.register(payload) : await authApi.login(payload)
    emit('login', auth)
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

function playBackgroundVideo() {
  const video = bgVideo.value
  if (!video) return
  video.play?.().catch(() => {})
}

function keepVideoLooping() {
  const video = bgVideo.value
  if (!video || !Number.isFinite(video.duration) || video.duration <= 1) return
  if (video.duration - video.currentTime <= 0.18) {
    video.currentTime = 0.05
    playBackgroundVideo()
  }
}

watch(error, (message) => {
  if (errorTimer) clearTimeout(errorTimer)
  if (!message) return
  const current = message
  errorTimer = window.setTimeout(() => {
    if (error.value === current) error.value = ''
  }, 5000)
})

watch(notice, (message) => {
  if (noticeTimer) clearTimeout(noticeTimer)
  if (!message) return
  const current = message
  noticeTimer = window.setTimeout(() => {
    if (notice.value === current) notice.value = ''
  }, 5000)
})

onMounted(() => {
  playBackgroundVideo()
  videoLoopTimer = window.setInterval(keepVideoLooping, 80)
})

onBeforeUnmount(() => {
  if (videoLoopTimer) window.clearInterval(videoLoopTimer)
  if (errorTimer) window.clearTimeout(errorTimer)
  if (noticeTimer) window.clearTimeout(noticeTimer)
})
</script>

<template>
  <div class="login-page hero-login">
    <video
      ref="bgVideo"
      class="login-bg-video"
      src="/img/login-bg.mp4"
      autoplay
      muted
      loop
      playsinline
      preload="auto"
      @loadedmetadata="playBackgroundVideo"
      @canplay="playBackgroundVideo"
      @ended="keepVideoLooping"
      @pause="playBackgroundVideo"
      @stalled="playBackgroundVideo"
      @waiting="playBackgroundVideo"
    ></video>
    <div class="login-video-overlay"></div>
    <div class="login-grid"></div>
    <div class="login-light-orb orb-c"></div>
    <div class="login-light-orb orb-d"></div>
    <div class="login-light-orb orb-streak"></div>
    <div class="login-light-orb orb-streak-2"></div>

    <main class="hero-login-shell">
      <header class="hero-login-topbar">
        <div class="hero-login-brand">
          <img src="/img/logo3.jpg" alt="智流工坊 logo" />
          <span>智流工坊</span>
        </div>
        <span class="hero-login-pill">A22 STREAM CONSOLE</span>
      </header>

      <section class="hero-login-copy">
        <p class="login-kicker blur-reveal delay-1">AI DATA ORCHESTRATION</p>
        <h1 aria-label="Smart Workshop">
          <span class="blur-reveal delay-2">Smart</span>
          <span class="blur-reveal delay-3">Workshop</span>
        </h1>
        <p class="hero-login-subtitle blur-reveal delay-4">用自然语言生成数据流，连接文件、规则、任务与实时计算。</p>
        <div class="login-flow-preview blur-reveal delay-5" aria-hidden="true">
          <span>Source</span>
          <i></i>
          <span>Transform</span>
          <i></i>
          <span>Sink</span>
        </div>
      </section>

      <aside class="login-card hero-login-card">
        <div class="login-brand">
          <h1>{{ title }} <small>智流工坊工作台</small></h1>
          <p>进入任务编排、数据工作区与 Flink 作业控制台。</p>
        </div>

        <div class="login-tabs">
          <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
          <button type="button" :class="{ active: mode === 'register' }" @click="switchMode('register')">注册</button>
        </div>

        <form class="login-form" @submit.prevent="submit">
          <h2 class="login-form-title">{{ title }}</h2>

          <template v-if="mode === 'forgot'">
            <div class="forgot-info">
              <div class="forgot-icon">密码</div>
              <h3>密码重置</h3>
              <p>如果您忘记了密码，请联系平台管理员进行密码重置。</p>
              <div class="forgot-steps">
                <p><strong>重置流程：</strong></p>
                <ol>
                  <li>联系平台管理员</li>
                  <li>管理员在「用户管理」中为您设置临时密码</li>
                  <li>使用临时密码登录</li>
                  <li>登录后请及时修改密码</li>
                </ol>
              </div>
            </div>

          </template>

          <template v-else>
            <label>
              <span>用户名</span>
              <input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
            </label>
            <label>
              <span>密码</span>
              <input v-model="form.password" type="password" :placeholder="mode === 'login' ? '请输入密码' : '请输入至少 8 位密码'" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" />
            </label>
            <label v-if="mode === 'register'">
              <span>确认密码</span>
              <input v-model="form.confirmPassword" type="password" placeholder="请再次输入密码" autocomplete="new-password" />
            </label>
          </template>

          <p v-if="error" class="login-error">{{ error }}</p>
          <p v-if="notice" class="notice">{{ notice }}</p>

          <button v-if="mode !== 'forgot'" type="submit" class="btn btn-primary login-submit" :disabled="loading">{{ loading ? '处理中..' : mode === 'login' ? '进入工作台' : '注册并登录' }}</button>
        </form>

        <button v-if="mode === 'login'" type="button" class="login-back" @click="switchMode('forgot')">忘记密码？</button>
        <button v-else type="button" class="login-back" @click="switchMode('login')">返回登录</button>
      </aside>
    </main>
  </div>
</template>


