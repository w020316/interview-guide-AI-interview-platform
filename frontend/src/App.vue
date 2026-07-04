<template>
  <el-container class="app-container">
    <el-header class="app-header">
      <div class="header-left" @click="router.push('/')">
        <el-icon :size="24" color="#409EFF"><ChatDotRound /></el-icon>
        <span class="app-title">AI 智能面试辅助平台</span>
      </div>
      <div class="header-right">
        <el-menu mode="horizontal" :default-active="activeMenu" router :ellipsis="false" background-color="transparent" text-color="#303133" active-text-color="#409EFF">
          <el-menu-item index="/">首页</el-menu-item>
          <el-menu-item index="/resume" v-if="logged">简历分析</el-menu-item>
          <el-menu-item index="/interview" v-if="logged">模拟面试</el-menu-item>
          <el-menu-item index="/history" v-if="logged">历史记录</el-menu-item>
        </el-menu>
        <div v-if="logged" class="user-box">
          <el-avatar :size="28" style="background:#409EFF">{{ username.charAt(0).toUpperCase() }}</el-avatar>
          <span class="username">{{ username }}</span>
          <el-button text type="danger" @click="logout">退出</el-button>
        </div>
        <el-button v-else type="primary" @click="router.push('/login')">登录</el-button>
      </div>
    </el-header>

    <el-main class="app-main">
      <router-view />
    </el-main>

    <el-footer class="app-footer">
      <span>Spring Boot 3.3.6 + Spring AI 1.0 + Java 21 | Agnes AI | PostgreSQL + pgvector | 0 元部署</span>
    </el-footer>
  </el-container>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ChatDotRound } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const username = ref('')

const logged = computed(() => !!username.value)
const activeMenu = computed(() => route.path)

const logout = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  username.value = ''
  router.push('/login')
}

onMounted(() => {
  username.value = localStorage.getItem('username') || ''
})
</script>

<style scoped>
* { box-sizing: border-box; }
body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
.app-container { min-height: 100vh; }
.app-header {
  display: flex; justify-content: space-between; align-items: center;
  background: #fff; border-bottom: 1px solid #ebeef5;
  padding: 0 24px; height: 60px;
}
.header-left { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.app-title { font-size: 16px; font-weight: 600; color: #303133; }
.header-right { display: flex; align-items: center; gap: 16px; }
.header-right :deep(.el-menu) { border-bottom: none; }
.user-box { display: flex; align-items: center; gap: 8px; }
.username { font-size: 14px; color: #606266; }
.app-main { background: #f5f7fa; padding: 0; }
.app-footer {
  text-align: center; color: #909399; font-size: 12px;
  padding: 16px; background: #fff; border-top: 1px solid #ebeef5;
}
</style>
