<template>
  <el-config-provider>
    <div class="app-wrapper">
      <!-- 顶部导航栏 -->
      <el-header class="navbar" v-if="authState.token">
        <div class="nav-logo">🎯 AI 面试助手</div>
        <el-menu mode="horizontal" :default-active="route.path" router class="nav-menu">
          <el-menu-item index="/">首页</el-menu-item>
          <el-menu-item index="/resume">简历分析</el-menu-item>
          <el-menu-item index="/interview">模拟面试</el-menu-item>
          <el-menu-item index="/history">历史记录</el-menu-item>
        </el-menu>
        <div class="nav-right">
          <span v-if="authState.username" class="user-name">{{ authState.username }}</span>
          <el-button type="danger" size="small" @click="logout">退出</el-button>
        </div>
      </el-header>

      <!-- 主内容区 -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { authState, clearAuth } from './auth'

const router = useRouter()
const route = useRoute()

function logout() {
  clearAuth()
  router.push('/login')
}
</script>

<style scoped>
.app-wrapper { display: flex; flex-direction: column; min-height: 100vh; }
.navbar {
  display: flex; align-items: center; gap: 16px;
  padding: 0 24px; background: #fff;
  box-shadow: 0 1px 4px rgba(0,0,0,.08); height: 56px;
}
.nav-logo { font-size: 18px; font-weight: 700; color: #409eff; white-space: nowrap; }
.nav-menu { flex: 1; border-bottom: none; }
.nav-right { margin-left: auto; display: flex; align-items: center; gap: 12px; }
.user-name { color: #606266; font-size: 14px; }
.main-content { flex: 1; padding: 24px; background: #f5f7fa; }

/* 响应式：移动端 */
@media (max-width: 640px) {
  .navbar { padding: 0 12px; gap: 8px; }
  .nav-logo { font-size: 16px; }
  .nav-menu .el-menu-item { padding: 0 8px; }
  .main-content { padding: 12px; }
}
</style>
