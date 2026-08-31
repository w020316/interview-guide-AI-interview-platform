<template>
  <div class="learning-page">
    <header class="page-header">
      <h1>学习中心</h1>
      <p>从错题中定位薄弱点，用趋势见证成长，让每一场面试都有规划</p>
    </header>

    <section class="module-grid fade-in-up">
      <!-- 面试日历 -->
      <router-link to="/calendar" class="module-card cal">
        <span class="module-icon">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M8 2v4M16 2v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </span>
        <div class="module-body">
          <h2>面试日历</h2>
          <p>规划面试与准备节点，掌控求职节奏</p>
        </div>
        <div class="module-stat">
          <span class="stat-num num-display">{{ calCount }}</span>
          <span class="stat-label">日程</span>
        </div>
        <span class="module-arrow" aria-hidden="true">→</span>
      </router-link>

      <!-- 错题本 -->
      <router-link to="/wrong-book" class="module-card book">
        <span class="module-icon">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20 V2H6.5A2.5 2.5 0 0 0 4 4.5v15z M9 7h7 M9 11h7"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </span>
        <div class="module-body">
          <h2>错题本</h2>
          <p>回顾低分题目，重点突破薄弱题型</p>
        </div>
        <div class="module-stat">
          <span class="stat-num num-display">{{ wrongCount }}</span>
          <span class="stat-label">错题</span>
        </div>
        <span class="module-arrow" aria-hidden="true">→</span>
      </router-link>

      <!-- 收藏夹 -->
      <router-link to="/favorites" class="module-card fav">
        <span class="module-icon">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </span>
        <div class="module-body">
          <h2>收藏夹</h2>
          <p>集中回看重点题目与高频考点</p>
        </div>
        <div class="module-stat">
          <span class="stat-num num-display">{{ favCount }}</span>
          <span class="stat-label">收藏</span>
        </div>
        <span class="module-arrow" aria-hidden="true">→</span>
      </router-link>

      <!-- 成长趋势 -->
      <router-link to="/progress" class="module-card trend">
        <span class="module-icon">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M3 3v18h18 M7 14l4-4 3 3 5-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </span>
        <div class="module-body">
          <h2>成长趋势</h2>
          <p>用数据看见每一次进步的轨迹</p>
        </div>
        <div class="module-stat">
          <span class="stat-num num-display">{{ trendCount }}</span>
          <span class="stat-label">场次</span>
        </div>
        <span class="module-arrow" aria-hidden="true">→</span>
      </router-link>
    </section>

    <section class="quick-actions fade-in-up">
      <div class="qa-head">
        <h2>快捷开始</h2>
        <span class="qa-note">从一次真实的模拟开始</span>
      </div>
      <div class="qa-row">
        <BaseButton variant="gradient" @click="$router.push('/interview')">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          开始模拟面试
        </BaseButton>
        <BaseButton variant="ghost" @click="$router.push('/calendar')">规划下一场</BaseButton>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import api from '../api'
import { BaseButton } from '../components'

const calCount = ref(0)
const wrongCount = ref(0)
const favCount = ref(0)
const trendCount = ref(0)

function safeLen(arr: unknown): number {
  return Array.isArray(arr) ? arr.length : 0
}

onMounted(async () => {
  try {
    const [cal, wrong, fav, trend] = await Promise.all([
      api.get('/api/calendar/event/list').catch(() => null),
      api.get('/api/knowledge/wrong-questions', { params: { threshold: 60 } }).catch(() => null),
      api.get('/api/favorite/list').catch(() => null),
      api.get('/api/stats/trend').catch(() => null),
    ])
    calCount.value = safeLen(cal)
    const wrongData = wrong as unknown as { total?: number; questions?: unknown } | null
    wrongCount.value = wrongData?.total ?? safeLen(wrongData?.questions) ?? 0
    const favData = fav as unknown as { total?: number } | null
    favCount.value = favData?.total ?? safeLen(fav) ?? 0
    trendCount.value = safeLen(trend)
  } catch {
    // 静默降级，统计失败不影响页面可用性
  }
})
</script>

<style scoped>
.learning-page { max-width: 980px; margin: 0 auto; }
.page-header { margin-bottom: 28px; }
.page-header h1 { font-size: 28px; font-weight: 700; color: var(--c-text); margin: 0 0 6px; letter-spacing: -0.5px; }
.page-header p { font-size: 14px; color: var(--c-text-secondary); margin: 0; }

.module-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.module-card { position: relative; display: flex; align-items: center; gap: 14px; padding: 22px; background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); text-decoration: none; transition: transform var(--transition-base), box-shadow var(--transition-base), border-color var(--transition-fast); overflow: hidden; }
.module-card::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 4px; }
.module-card.cal::before { background: var(--c-info); }
.module-card.book::before { background: var(--c-danger); }
.module-card.fav::before { background: var(--c-warning); }
.module-card.trend::before { background: var(--c-success); }
.module-card:hover { transform: translateY(-3px); box-shadow: var(--shadow-md); border-color: var(--brand-primary-200); }

.module-icon { flex-shrink: 0; width: 46px; height: 46px; border-radius: var(--radius-md); display: inline-flex; align-items: center; justify-content: center; }
.module-card.cal .module-icon { background: var(--c-info-light); color: var(--c-info); }
.module-card.book .module-icon { background: var(--c-danger-light); color: var(--c-danger); }
.module-card.fav .module-icon { background: var(--c-warning-light); color: var(--c-warning); }
.module-card.trend .module-icon { background: var(--c-success-light); color: var(--c-success); }

.module-body { flex: 1; min-width: 0; }
.module-body h2 { font-family: var(--font-serif); font-size: 17px; font-weight: 600; color: var(--c-text); margin: 0 0 4px; }
.module-body p { font-size: 12px; color: var(--c-text-tertiary); margin: 0; }

.module-stat { display: flex; flex-direction: column; align-items: flex-end; flex-shrink: 0; }
.stat-num { font-size: 24px; font-weight: 700; color: var(--c-text); line-height: 1; }
.stat-label { font-size: 11px; color: var(--c-text-tertiary); margin-top: 3px; }
.module-arrow { color: var(--c-text-tertiary); font-size: 18px; transition: transform var(--transition-fast), color var(--transition-fast); flex-shrink: 0; }
.module-card:hover .module-arrow { transform: translateX(3px); color: var(--brand-primary); }

.quick-actions { margin-top: 20px; background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); padding: 20px 24px; box-shadow: var(--shadow-sm); display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.qa-head h2 { font-size: 16px; font-weight: 600; color: var(--c-text); margin: 0 0 4px; }
.qa-note { font-size: 12px; color: var(--c-text-tertiary); }
.qa-row { display: flex; gap: 10px; flex-wrap: wrap; }

@media (max-width: 640px) {
  .module-grid { grid-template-columns: 1fr; }
}
</style>