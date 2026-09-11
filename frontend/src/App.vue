<template>
  <el-config-provider :locale="zhCn">
    <div class="app-wrapper">
      <!-- 顶部导航栏 -->
      <header class="navbar">
        <div class="nav-inner">
          <div class="nav-brand" @click="router.push('/')">
            <span class="brand-mark">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M12 2L2 7l10 5 10-5-10-5z M2 17l10 5 10-5 M2 12l10 5 10-5"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </span>
            <div class="brand-text-wrap">
              <span class="brand-text">AI 面试助手</span>
              <span class="brand-sub">智能面试准备平台</span>
            </div>
          </div>

          <nav class="nav-menu">
            <router-link to="/" class="nav-link" :class="{ active: route.path === '/' }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z M9 22V12h6v10"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>首页</span>
            </router-link>
            <router-link to="/resume" class="nav-link" :class="{ active: route.path.startsWith('/resume') }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M16 13H8 M16 17H8 M10 9H8"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>简历分析</span>
            </router-link>
            <router-link to="/job" class="nav-link" :class="{ active: route.path === '/job' }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M21 13.255A23.931 23.931 0 0112 15c-3.183 0-6.22-.62-9-1.745M16 6V4a2 2 0 00-2-2h-4a2 2 0 00-2 2v2m4 6h.01M5 20h14a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>岗位分析</span>
            </router-link>
            <router-link v-if="authState.token" to="/jobs" class="nav-link" :class="{ active: route.path === '/jobs' }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>招聘广场</span>
            </router-link>
            <router-link v-if="authState.token" to="/agent" class="nav-link" :class="{ active: route.path === '/agent' }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M12 2a7 7 0 0 1 4 12.7V17a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-2.3A7 7 0 0 1 12 2z M9 21h6"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>智能体</span>
            </router-link>
            <router-link to="/interview" class="nav-link" :class="{ active: route.path === '/interview' }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>模拟面试</span>
            </router-link>
            <router-link v-if="authState.token" to="/learning" class="nav-link" :class="{ active: route.path === '/learning' || route.path === '/calendar' || route.path === '/wrong-book' || route.path === '/favorites' || route.path === '/progress' }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M22 10L12 5 2 10l10 5 10-5z M6 12v5c0 1.7 2.7 3 6 3s6-1.3 6-3v-5 M22 10v6"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>学习中心</span>
            </router-link>
            <router-link v-if="authState.token" to="/history" class="nav-link" :class="{ active: route.path === '/history' }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M12 8v4l3 3 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>历史记录</span>
            </router-link>
            <router-link v-if="authState.token" to="/knowledge" class="nav-link" :class="{ active: route.path === '/knowledge' }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20 M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>知识库</span>
            </router-link>
            <router-link v-if="authState.token && isAdmin()" to="/admin" class="nav-link" :class="{ active: route.path === '/admin' }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>管理后台</span>
            </router-link>
            <router-link v-if="authState.token" to="/profile" class="nav-link" :class="{ active: route.path === '/profile' }">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>个人中心</span>
            </router-link>
          </nav>

          <div class="nav-actions">
            <button
              class="theme-toggle"
              :aria-label="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
              :title="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
              @click="toggleTheme"
            >
              <svg v-if="theme === 'dark'" width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z M12 3v2 M12 19v2 M3 12h2 M17 12h2 M5.6 5.6l1.4 1.4 M17 17l1.4 1.4 M5.6 18.4L7 17"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
            <template v-if="authState.token">
              <div class="user-chip">
                <span class="user-avatar">{{ (authState.username || '?').charAt(0).toUpperCase() }}</span>
                <span class="user-name">{{ authState.username }}</span>
              </div>
              <button class="btn-logout" @click="logout">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4 M16 17l5-5-5-5 M21 12H9"
                    stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span>退出</span>
              </button>
            </template>
            <template v-else>
              <button class="btn-login" @click="router.push('/login')">登录</button>
              <button class="btn-register" @click="router.push('/login')">免费注册</button>
            </template>
          </div>
        </div>
      </header>

      <!-- 主内容区 -->
      <main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>

      <!-- 页脚 -->
      <footer class="app-footer">
        <div class="footer-inner">
          <div class="footer-brand">
            <span class="brand-mark">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                <path d="M12 2L2 7l10 5 10-5-10-5z M2 17l10 5 10-5 M2 12l10 5 10-5"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </span>
            <span>AI 面试助手</span>
          </div>
          <p class="footer-desc">基于 Spring Boot 3.3 + Spring AI 1.0 + Vue 3 构建，为求职者打造的智能面试准备平台</p>
          <p class="footer-copy">
            © 2026 AI 面试助手 · MIT License
            <button class="version-link" @click="showChangelog = true">v{{ CURRENT_VERSION }}</button>
          </p>
        </div>
      </footer>

      <!-- 版本更新弹窗 -->
      <ChangelogDialog v-model:visible="showChangelog" />
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { authState, clearAuth, isAdmin } from './auth'
import api from './api'
import ChangelogDialog from './components/ChangelogDialog.vue'
import { CURRENT_VERSION } from './changelog'
import { theme, toggleTheme as toggle } from './theme'

const router = useRouter()
const route = useRoute()
const showChangelog = ref(false)

function toggleTheme() {
  toggle()
}

async function logout() {
  try {
    await api.post('/api/auth/logout')
  } catch {
    // 忽略后端失败
  }
  clearAuth()
  router.push('/login')
}
</script>

<style scoped>
.app-wrapper {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  min-height: 100dvh;
  overflow-x: hidden;
}

/* ── 导航栏：实色 + 细边框，无 blur ── */
.navbar {
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  background: rgba(255, 255, 255, 0.95);
  border-bottom: 1px solid var(--c-border);
}

.nav-inner {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  height: 64px;
  padding: 0 32px;
  gap: 20px;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
}

/* 品牌图标：纯色，无渐变光晕 */
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: var(--brand-primary);
  color: #fff;
  transition: background var(--transition-fast);
}

.nav-brand:hover .brand-mark {
  background: var(--brand-primary-hover);
}

.brand-text-wrap {
  display: flex;
  flex-direction: column;
  line-height: 1.1;
}

/* 品牌标题：衬线字体，编辑风 */
.brand-text {
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  letter-spacing: -0.3px;
}

.brand-sub {
  font-family: var(--font-sans);
  font-size: 11px;
  color: var(--c-text-tertiary);
  margin-top: 2px;
  letter-spacing: 0.2px;
}

.nav-menu {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 1;
}

.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-secondary);
  text-decoration: none;
  border-radius: var(--radius-md);
  transition: color var(--transition-fast), background-color var(--transition-fast);
  white-space: nowrap;
}

.nav-link svg {
  opacity: 0.7;
  transition: opacity var(--transition-fast);
}

/* hover：浅灰背景 */
.nav-link:hover {
  color: var(--c-text);
  background: var(--c-bg-alt);
}

.nav-link:hover svg {
  opacity: 1;
}

/* active：品牌色浅绿背景 + 深墨绿文字 */
.nav-link.active {
  color: var(--brand-primary);
  background: var(--brand-primary-50);
  font-weight: 600;
}

.nav-link.active svg {
  opacity: 1;
}

.nav-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

/* 主题切换按钮 */
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  color: var(--c-text-secondary);
  background: transparent;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast);
}
.theme-toggle:hover {
  color: var(--brand-primary);
  border-color: var(--brand-primary);
  background: var(--brand-primary-50);
}
.theme-toggle:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 1px;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 14px 4px 4px;
  background: var(--c-bg-alt);
  border-radius: var(--radius-full);
  transition: background var(--transition-fast);
}

.user-chip:hover {
  background: var(--c-border-light);
}

/* 用户头像：纯色背景，无渐变 */
.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--brand-primary);
  color: #fff;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 600;
}

.user-name {
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
}

.btn-logout {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: transparent;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast);
}

.btn-logout:hover {
  color: var(--c-danger);
  border-color: var(--c-danger);
  background: var(--c-danger-light);
}

/* 登录按钮：描边样式 */
.btn-login {
  padding: 7px 16px;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
  background: transparent;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast);
}

.btn-login:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  background: var(--brand-primary-50);
}

/* 注册按钮：实色背景，hover 颜色加深而非上浮 */
.btn-register {
  padding: 7px 16px;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-primary);
  border: 1px solid var(--brand-primary);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color var(--transition-fast), border-color var(--transition-fast);
}

.btn-register:hover {
  background: var(--brand-primary-hover);
  border-color: var(--brand-primary-hover);
}

/* ── 主内容 ── */
.main-content {
  flex: 1;
  padding: 32px 24px;
  max-width: 1280px;
  width: 100%;
  margin: 0 auto;
}

/* ── 页脚：简洁实色 ── */
.app-footer {
  border-top: 1px solid var(--c-border);
  background: var(--c-surface);
  padding: 40px 24px;
}

.footer-inner {
  max-width: 1280px;
  margin: 0 auto;
  text-align: center;
}

.footer-brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.footer-brand .brand-mark {
  width: 28px;
  height: 28px;
}

.footer-brand span:last-child {
  font-family: var(--font-serif);
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.footer-desc {
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--c-text-secondary);
  margin: 0 0 8px;
  max-width: 560px;
  margin-left: auto;
  margin-right: auto;
  line-height: 1.6;
}

.footer-copy {
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--c-text-tertiary);
  margin: 0;
}

.version-link {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 8px;
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 600;
  color: var(--brand-primary);
  background: var(--brand-primary-light);
  border: none;
  border-radius: 999px;
  cursor: pointer;
  transition: all var(--transition-fast);
  letter-spacing: 0.2px;
}

.version-link:hover {
  background: var(--brand-primary);
  color: #fff;
}

/* ── 页面切换动画 ── */
.page-enter-active,
.page-leave-active {
  transition: opacity var(--transition-base), transform var(--transition-base);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .nav-inner {
    padding: 0 16px;
    gap: 12px;
    height: 56px;
  }
  .brand-text-wrap {
    display: none;
  }
  .nav-menu {
    gap: 2px;
    overflow-x: auto;
    scrollbar-width: none;
    -webkit-overflow-scrolling: touch;
  }
  .nav-menu::-webkit-scrollbar {
    display: none;
  }
  .nav-link {
    padding: 10px 8px;
    font-size: 12px;
    flex-shrink: 0;
  }
  .nav-link span {
    display: none;
  }
  .nav-link svg {
    opacity: 1;
  }
  .user-name {
    display: none;
  }
  .user-chip {
    padding: 4px;
  }
  .btn-logout span {
    display: none;
  }
  .btn-logout {
    padding: 8px 10px;
  }
  .main-content {
    padding: 20px 16px;
  }
}

@media (max-width: 480px) {
  .btn-logout span, .btn-logout svg {
    font-size: 12px;
  }
  .btn-login, .btn-register {
    padding: 6px 12px;
    font-size: 12px;
  }
}
</style>
