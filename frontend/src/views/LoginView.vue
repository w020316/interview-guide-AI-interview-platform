<template>
  <div class="auth-page">
    <!-- 左侧品牌展示（桌面端） -->
    <aside class="auth-aside">
      <div class="aside-content">
        <div class="aside-brand">
          <span class="brand-mark">AI</span>
          <span class="brand-text">面试助手</span>
        </div>
        <h1 class="aside-title">让每一次面试<br />都有备而来</h1>
        <p class="aside-desc">AI 驱动的简历分析与模拟面试平台，助你高效备战求职季</p>
        <ul class="aside-features">
          <li><span class="dot"></span>简历多维度 AI 评分</li>
          <li><span class="dot"></span>个性化面试题生成</li>
          <li><span class="dot"></span>实时流式 AI 提示</li>
        </ul>
      </div>
      <div class="aside-glow"></div>
    </aside>

    <!-- 右侧表单 -->
    <main class="auth-main">
      <div class="auth-card fade-in-up">
        <div class="card-header">
          <div class="mobile-brand">
            <span class="brand-mark">AI</span>
            <span class="brand-text">面试助手</span>
          </div>
          <h2>{{ activeTab === 'login' ? '欢迎回来' : '创建账号' }}</h2>
          <p>{{ activeTab === 'login' ? '登录后继续你的面试准备' : '注册后即可开始使用' }}</p>
        </div>

        <div class="tab-switch">
          <button :class="{ active: activeTab === 'login' }" @click="activeTab = 'login'">登录</button>
          <button :class="{ active: activeTab === 'register' }" @click="activeTab = 'register'">注册</button>
        </div>

        <form v-if="activeTab === 'login'" @submit.prevent="handleLogin" class="auth-form">
          <div class="field">
            <label>用户名</label>
            <input v-model="loginForm.username" type="text" placeholder="请输入用户名" autocomplete="username" />
          </div>
          <div class="field">
            <label>密码</label>
            <input v-model="loginForm.password" type="password" placeholder="请输入密码" autocomplete="current-password" />
          </div>
          <button type="submit" class="btn-submit" :disabled="loading">
            <span v-if="loading" class="spinner"></span>
            {{ loading ? '登录中...' : '登录' }}
          </button>
        </form>

        <form v-else @submit.prevent="handleRegister" class="auth-form">
          <div class="field">
            <label>用户名 <span class="hint">至少 3 位</span></label>
            <input v-model="regForm.username" type="text" placeholder="请输入用户名" autocomplete="username" />
          </div>
          <div class="field">
            <label>密码 <span class="hint">至少 6 位</span></label>
            <input v-model="regForm.password" type="password" placeholder="请输入密码" autocomplete="new-password" />
          </div>
          <div class="field">
            <label>邮箱 <span class="hint">可选</span></label>
            <input v-model="regForm.email" type="email" placeholder="name@example.com" autocomplete="email" />
          </div>
          <button type="submit" class="btn-submit" :disabled="loading">
            <span v-if="loading" class="spinner"></span>
            {{ loading ? '注册中...' : '注册' }}
          </button>
        </form>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { setAuth } from '../auth'

const router = useRouter()
const route = useRoute()
const activeTab = ref('login')
const loading = ref(false)
const loginForm = ref({ username: '', password: '' })
const regForm = ref({ username: '', password: '', email: '' })

/** 登录/注册成功后跳转：redirect 必须以 / 开头的相对路径，防止开放重定向 */
function redirectAfterAuth() {
  const r = Array.isArray(route.query.redirect) ? route.query.redirect[0] : route.query.redirect
  const safe = typeof r === 'string' && r.startsWith('/') ? r : '/'
  router.push(safe)
}

async function handleLogin() {
  if (!loginForm.value.username || !loginForm.value.password)
    return ElMessage.warning('请填写用户名和密码')
  loading.value = true
  try {
    const token = await api.post('/api/auth/login', loginForm.value) as unknown as string
    setAuth(token, loginForm.value.username)
    ElMessage.success('登录成功')
    redirectAfterAuth()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '登录失败'))
  } finally { loading.value = false }
}

async function handleRegister() {
  if (!regForm.value.username || regForm.value.username.length < 3)
    return ElMessage.warning('用户名至少 3 位')
  if (!regForm.value.password || regForm.value.password.length < 6)
    return ElMessage.warning('密码至少 6 位')
  if (regForm.value.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(regForm.value.email))
    return ElMessage.warning('邮箱格式不正确')
  loading.value = true
  try {
    const token = await api.post('/api/auth/register', regForm.value) as unknown as string
    setAuth(token, regForm.value.username)
    ElMessage.success('注册成功')
    redirectAfterAuth()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '注册失败'))
  } finally { loading.value = false }
}
</script>

<style scoped>
.auth-page {
  display: flex;
  min-height: 100vh;
  background: var(--c-bg);
}

/* ── 左侧品牌区 ── */
.auth-aside {
  position: relative;
  flex: 0 0 42%;
  max-width: 560px;
  background: #0f172a;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.aside-content {
  position: relative;
  z-index: 2;
  padding: 48px;
  color: #fff;
  max-width: 420px;
}

.aside-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 56px;
}

.aside-brand .brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 9px;
  background: var(--brand-gradient);
  font-weight: 700;
  font-size: 14px;
}

.aside-brand .brand-text {
  font-size: 18px;
  font-weight: 600;
}

.aside-title {
  font-size: 36px;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: -0.5px;
  margin: 0 0 20px;
}

.aside-desc {
  font-size: 15px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.7);
  margin: 0 0 40px;
}

.aside-features {
  list-style: none;
  padding: 0;
  margin: 0;
}

.aside-features li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.85);
}

.aside-features .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-accent);
  box-shadow: 0 0 12px var(--brand-accent);
}

.aside-glow {
  position: absolute;
  width: 500px;
  height: 500px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(79, 70, 229, 0.4) 0%, transparent 70%);
  top: -100px;
  right: -150px;
  filter: blur(40px);
}

/* ── 右侧表单区 ── */
.auth-main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.auth-card {
  width: 100%;
  max-width: 400px;
  background: var(--c-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  padding: 40px 36px;
  border: 1px solid var(--c-border-light);
}

.card-header {
  text-align: center;
  margin-bottom: 28px;
}

.mobile-brand {
  display: none;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-bottom: 24px;
}

.mobile-brand .brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--brand-gradient);
  color: #fff;
  font-weight: 700;
  font-size: 13px;
}

.mobile-brand .brand-text {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
}

.card-header h2 {
  font-size: 24px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 6px;
  letter-spacing: -0.3px;
}

.card-header p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
}

.tab-switch {
  display: flex;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  padding: 4px;
  margin-bottom: 28px;
}

.tab-switch button {
  flex: 1;
  padding: 9px 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.tab-switch button.active {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: var(--shadow-sm);
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field label {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
}

.field .hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--c-text-tertiary);
}

.field input {
  padding: 11px 14px;
  font-size: 14px;
  font-family: var(--font-sans);
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  outline: none;
  transition: all var(--transition-fast);
}

.field input::placeholder {
  color: var(--c-text-tertiary);
}

.field input:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.12);
}

.btn-submit {
  margin-top: 8px;
  padding: 12px 0;
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-gradient);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  box-shadow: 0 4px 12px rgba(79, 70, 229, 0.25);
}

.btn-submit:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(79, 70, 229, 0.35);
}

.btn-submit:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .auth-aside {
    display: none;
  }
  .mobile-brand {
    display: flex;
  }
  .auth-main {
    padding: 24px 16px;
  }
  .auth-card {
    padding: 32px 24px;
    box-shadow: var(--shadow-md);
  }
  .card-header h2 {
    font-size: 22px;
  }
}
</style>
