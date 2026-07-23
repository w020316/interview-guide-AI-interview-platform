<template>
  <div class="not-found">
    <div class="nf-content">
      <div class="nf-emoji">
        <svg width="72" height="72" viewBox="0 0 24 24" fill="none">
          <circle cx="11" cy="11" r="7" stroke="var(--brand-primary)" stroke-width="1.5"/>
          <path d="M21 21l-4.3-4.3" stroke="var(--brand-primary)" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
      </div>
      <h1 class="nf-title">
        <span class="text-gradient-static">404</span>
      </h1>
      <h2 class="nf-subtitle">页面未找到</h2>
      <p class="nf-desc">你访问的页面不存在或已被移除</p>
      <div class="nf-actions">
        <BaseButton variant="gradient" @click="router.push('/')">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
            <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z M9 22V12h6v10"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          返回首页
        </BaseButton>
        <BaseButton variant="ghost" @click="goBack">返回上一页</BaseButton>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { BaseButton } from '../components'

const router = useRouter()

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/')
  }
}
</script>

<style scoped>
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  padding: 48px 24px;
}

.nf-content {
  text-align: center;
  max-width: 480px;
}

.nf-emoji {
  font-size: 72px;
  margin-bottom: 24px;
  animation: float 4s ease-in-out infinite;
}

@keyframes float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-12px); }
}

.nf-title {
  font-size: 96px;
  font-weight: 900;
  line-height: 1;
  margin: 0 0 8px;
  letter-spacing: -3px;
}

.nf-subtitle {
  font-size: 24px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 12px;
}

.nf-desc {
  font-size: 15px;
  color: var(--c-text-secondary);
  margin: 0 0 32px;
  line-height: 1.6;
}

.nf-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}
</style>
