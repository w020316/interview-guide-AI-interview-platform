<template>
  <div class="auth-page">
    <!-- 左侧品牌展示（桌面端） -->
    <aside class="auth-aside">
      <div class="aside-bg">
        <div class="aside-glow aside-glow-1"></div>
        <div class="aside-glow aside-glow-2"></div>
        <div class="aside-grid"></div>
      </div>
      <div class="aside-content">
        <div class="aside-brand">
          <span class="brand-mark">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
              <path d="M12 2L2 7l10 5 10-5-10-5z M2 17l10 5 10-5 M2 12l10 5 10-5"
                stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </span>
          <span class="brand-text">AI 面试助手</span>
        </div>
        <h1 class="aside-title">
          让每一次面试<br />
          <span class="text-gradient-static">都有备而来</span>
        </h1>
        <p class="aside-desc">AI 驱动的简历分析与模拟面试平台，助你高效备战求职季</p>
        <ul class="aside-features">
          <li>
            <span class="dot"></span>
            <span>简历多维度 AI 评分</span>
          </li>
          <li>
            <span class="dot"></span>
            <span>个性化面试题生成</span>
          </li>
          <li>
            <span class="dot"></span>
            <span>实时流式 AI 提示</span>
          </li>
          <li>
            <span class="dot"></span>
            <span>答题质量自动评估</span>
          </li>
        </ul>

        <!-- 装饰统计 -->
        <div class="aside-stats">
          <div class="aside-stat">
            <div class="aside-stat-num">100%</div>
            <div class="aside-stat-label">免费使用</div>
          </div>
          <div class="aside-stat">
            <div class="aside-stat-num">∞</div>
            <div class="aside-stat-label">不限次数</div>
          </div>
        </div>
      </div>
    </aside>

    <!-- 右侧表单 -->
    <main class="auth-main">
      <div class="auth-card fade-in-up">
        <div class="card-header">
          <div class="mobile-brand">
            <span class="brand-mark">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M12 2L2 7l10 5 10-5-10-5z M2 17l10 5 10-5 M2 12l10 5 10-5"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </span>
            <span class="brand-text">AI 面试助手</span>
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
            <div class="input-wrap">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <input v-model="loginForm.username" type="text" placeholder="请输入用户名" autocomplete="username" />
            </div>
          </div>
          <div class="field">
            <label>密码</label>
            <div class="input-wrap">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M19 11H5a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2z M7 11V7a5 5 0 0 1 10 0v4"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <input v-model="loginForm.password" type="password" placeholder="请输入密码" autocomplete="current-password" />
            </div>
          </div>
          <button type="submit" class="btn-submit" :disabled="loading">
            <span v-if="loading" class="spinner"></span>
            {{ loading ? '登录中...' : '登录' }}
          </button>
        </form>

        <form v-else @submit.prevent="handleRegister" class="auth-form">
          <div class="field">
            <label>用户名 <span class="hint">至少 3 位</span></label>
            <div class="input-wrap">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <input v-model="regForm.username" type="text" placeholder="请输入用户名" autocomplete="username" />
            </div>
          </div>
          <div class="field">
            <label>密码 <span class="hint">至少 6 位</span></label>
            <div class="input-wrap">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M19 11H5a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2z M7 11V7a5 5 0 0 1 10 0v4"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <input v-model="regForm.password" type="password" placeholder="请输入密码" autocomplete="new-password" />
            </div>
          </div>
          <div class="field">
            <label>邮箱 <span class="hint">可选</span></label>
            <div class="input-wrap">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z M22 6l-10 7L2 6"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <input v-model="regForm.email" type="email" placeholder="name@example.com" autocomplete="email" />
            </div>
          </div>
          <button type="submit" class="btn-submit" :disabled="loading">
            <span v-if="loading" class="spinner"></span>
            {{ loading ? '注册中...' : '注册' }}
          </button>
        </form>

        <p class="auth-foot">
          {{ activeTab === 'login' ? '还没有账号？' : '已有账号？' }}
          <button class="link-btn" @click="activeTab = activeTab === 'login' ? 'register' : 'login'">
            {{ activeTab === 'login' ? '立即注册' : '去登录' }}
          </button>
        </p>
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
  min-height: calc(100vh - 64px);
  margin: -32px -24px;
  /* 抵消父容器 padding，让 auth-page 占满 */
}

@media (max-width: 768px) {
  .auth-page {
    margin: -20px -16px;
  }
}

/* ── 左侧品牌区：深墨绿实色背景 ── */
.auth-aside {
  position: relative;
  flex: 0 0 44%;
  max-width: 600px;
  background: var(--brand-primary);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 装饰元素已移除视觉表现，保留类名以兼容模板 */
.aside-bg,
.aside-glow,
.aside-glow-1,
.aside-glow-2,
.aside-grid {
  display: none;
}

.aside-content {
  position: relative;
  z-index: 2;
  padding: 48px;
  color: #fff;
  max-width: 460px;
}

.aside-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 56px;
}

/* brand-mark：白色边框 + 白色图标，在深绿背景上 */
.aside-brand .brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.7);
  color: #fff;
}

/* mobile-brand 在白底卡片中：深绿边框 + 深绿图标 */
.mobile-brand .brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: var(--brand-primary-light);
  border: 1px solid var(--brand-primary-100);
  color: var(--brand-primary);
}

.aside-brand .brand-text {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.2px;
  color: #fff;
}

/* aside-title：衬线字体，编辑风 */
.aside-title {
  font-family: var(--font-serif);
  font-size: 40px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.5px;
  margin: 0 0 20px;
  color: #fff;
}

/* text-gradient-static 在深绿背景上改为浅白色 */
.aside-title .text-gradient-static {
  color: rgba(255, 255, 255, 0.92);
  font-family: var(--font-serif);
  font-weight: 600;
  background: none;
  -webkit-text-fill-color: rgba(255, 255, 255, 0.92);
}

.aside-desc {
  font-size: 15px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.82);
  margin: 0 0 36px;
}

/* aside-features：简洁列表 */
.aside-features {
  list-style: none;
  padding: 0;
  margin: 0 0 48px;
}

.aside-features li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.9);
}

/* dot：白色小圆点 */
.aside-features .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.9);
  flex-shrink: 0;
}

/* aside-stats：白色文字 + 半透明背景 */
.aside-stats {
  display: flex;
  gap: 16px;
  padding: 20px;
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.15);
}

.aside-stat {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.aside-stat-num {
  font-size: 22px;
  font-weight: 700;
  color: #fff;
  line-height: 1;
  margin-bottom: 4px;
}

.aside-stat-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.75);
}

/* ── 右侧表单区：暖米白背景 ── */
.auth-main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background: var(--c-bg);
}

/* auth-card：白底 + 边框 + 阴影 + 大圆角 */
.auth-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-xl);
  padding: 40px;
  width: 100%;
  max-width: 400px;
  box-shadow: var(--shadow-lg);
}

.card-header {
  margin-bottom: 24px;
}

.mobile-brand {
  display: none;
  align-items: center;
  gap: 8px;
  margin-bottom: 24px;
}

.mobile-brand .brand-text {
  font-size: 15px;
  font-weight: 700;
  color: var(--c-text);
}

.card-header h2 {
  font-family: var(--font-serif);
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

/* tab-switch：底部下划线式切换 */
.tab-switch {
  display: flex;
  border-bottom: 1px solid var(--c-border);
  margin-bottom: 24px;
  gap: 0;
}

.tab-switch button {
  position: relative;
  padding: 10px 20px;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: color var(--transition-fast);
  font-family: inherit;
  margin-bottom: -1px;
}

.tab-switch button:hover {
  color: var(--c-text);
}

/* active：深绿下划线 + 深绿文字 */
.tab-switch button.active {
  color: var(--brand-primary);
  font-weight: 600;
}

.tab-switch button.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: var(--brand-primary);
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

.hint {
  color: var(--c-text-tertiary);
  font-weight: 400;
  font-size: 12px;
}

.input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.input-icon {
  position: absolute;
  left: 12px;
  color: var(--c-text-tertiary);
  pointer-events: none;
  z-index: 1;
}

/* 表单输入框：边框 + 聚焦时变深绿 + 暖灰背景 */
.input-wrap input {
  width: 100%;
  padding: 11px 14px 11px 40px;
  font-size: 14px;
  font-family: var(--font-sans);
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  outline: none;
  transition: border-color var(--transition-fast), background var(--transition-fast);
}

.input-wrap input::placeholder {
  color: var(--c-text-tertiary);
}

.input-wrap input:focus {
  border-color: var(--brand-primary);
  background: var(--c-bg-alt);
}

.input-wrap:focus-within .input-icon {
  color: var(--brand-primary);
}

/* 提交按钮：深墨绿实色背景，hover 加深 */
.btn-submit {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 8px;
  padding: 12px;
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-primary);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background var(--transition-fast);
  font-family: inherit;
}

.btn-submit:hover:not(:disabled) {
  background: var(--brand-primary-hover);
}

.btn-submit:active:not(:disabled) {
  background: var(--brand-primary-active);
}

.btn-submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.auth-foot {
  margin-top: 24px;
  text-align: center;
  font-size: 13px;
  color: var(--c-text-secondary);
}

.link-btn {
  background: transparent;
  border: none;
  color: var(--brand-primary);
  font-weight: 600;
  cursor: pointer;
  padding: 0;
  font-size: 13px;
  font-family: inherit;
  transition: color var(--transition-fast);
}

.link-btn:hover {
  color: var(--brand-primary-hover);
  text-decoration: underline;
}

/* ── 响应式 ── */
@media (max-width: 960px) {
  .auth-aside {
    flex: 0 0 38%;
  }
  .aside-content {
    padding: 32px;
  }
  .aside-title {
    font-size: 32px;
  }
}

@media (max-width: 768px) {
  .auth-aside {
    display: none;
  }
  .mobile-brand {
    display: flex;
  }
  .auth-main {
    padding: 32px 20px;
    background: var(--c-bg);
  }
  .auth-card {
    padding: 28px 24px;
    box-shadow: var(--shadow-md);
    border: 1px solid var(--c-border);
  }
}

@media (max-width: 480px) {
  .auth-card {
    padding: 24px 20px;
    border-radius: var(--radius-lg);
  }
}
</style>
