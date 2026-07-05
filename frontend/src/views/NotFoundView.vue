<template>
  <div class="not-found">
    <div class="nf-content">
      <div class="nf-emoji">🔍</div>
      <h1 class="nf-title">
        <span class="text-gradient-static">404</span>
      </h1>
      <h2 class="nf-subtitle">页面未找到</h2>
      <p class="nf-desc">你访问的页面不存在或已被移除</p>
      <div class="nf-actions">
        <button class="btn-primary" @click="router.push('/')">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
            <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z M9 22V12h6v10"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          返回首页
        </button>
        <button class="btn-secondary" @click="goBack">返回上一页</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

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

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 12px 24px;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-gradient);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: var(--shadow-brand);
  font-family: inherit;
}

.btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(79, 70, 229, 0.4);
}

.btn-secondary {
  padding: 12px 24px;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  font-family: inherit;
}

.btn-secondary:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
}
</style>
