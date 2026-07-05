<template>
  <el-config-provider :locale="zhCn">
    <div class="app-wrapper">
      <!-- 顶部导航栏 -->
      <header v-if="authState.token" class="navbar">
        <div class="nav-inner">
          <div class="nav-brand" @click="router.push('/')">
            <span class="brand-mark">AI</span>
            <span class="brand-text">面试助手</span>
          </div>
          <nav class="nav-menu">
            <router-link to="/" class="nav-link" :class="{ active: route.path === '/' }">首页</router-link>
            <router-link to="/resume" class="nav-link" :class="{ active: route.path === '/resume' }">简历分析</router-link>
            <router-link to="/interview" class="nav-link" :class="{ active: route.path === '/interview' }">模拟面试</router-link>
            <router-link to="/history" class="nav-link" :class="{ active: route.path === '/history' }">历史记录</router-link>
          </nav>
          <div class="nav-actions">
            <div class="user-chip">
              <span class="user-avatar">{{ (authState.username || '?').charAt(0).toUpperCase() }}</span>
              <span class="user-name">{{ authState.username }}</span>
            </div>
            <button class="btn-logout" @click="logout">退出</button>
          </div>
        </div>
      </header>

      <!-- 主内容区 -->
      <main class="main-content">
        <router-view />
      </main>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { authState, clearAuth } from './auth'
import api from './api'

const router = useRouter()
const route = useRoute()

async function logout() {
  // 通知后端使 token 失效（best-effort，失败也清前端）
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
  background: var(--c-bg);
}

/* ── 导航栏 ── */
.navbar {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--c-border);
}

.nav-inner {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  height: 60px;
  padding: 0 24px;
  gap: 32px;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.brand-mark {
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
  letter-spacing: 0.5px;
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.3);
}

.brand-text {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  letter-spacing: -0.2px;
}

.nav-menu {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
}

.nav-link {
  padding: 8px 14px;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-secondary);
  text-decoration: none;
  border-radius: 8px;
  transition: all var(--transition-fast);
}

.nav-link:hover {
  color: var(--c-text);
  background: var(--c-bg-alt);
}

.nav-link.active {
  color: var(--brand-primary);
  background: var(--brand-primary-light);
}

.nav-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px 4px 4px;
  background: var(--c-bg-alt);
  border-radius: 999px;
}

.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--brand-gradient);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
}

.btn-logout {
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: transparent;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.btn-logout:hover {
  color: var(--c-danger);
  border-color: var(--c-danger);
  background: #fef2f2;
}

/* ── 主内容 ── */
.main-content {
  flex: 1;
  padding: 32px 24px;
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .nav-inner {
    padding: 0 16px;
    gap: 16px;
    height: 54px;
  }
  .brand-text {
    display: none;
  }
  .nav-menu {
    gap: 2px;
  }
  .nav-link {
    padding: 6px 10px;
    font-size: 13px;
  }
  .user-name {
    display: none;
  }
  .user-chip {
    padding: 4px;
  }
  .main-content {
    padding: 20px 16px;
  }
}

@media (max-width: 480px) {
  .nav-link {
    padding: 6px 8px;
    font-size: 12px;
  }
  .btn-logout {
    padding: 6px 10px;
    font-size: 12px;
  }
}
</style>
