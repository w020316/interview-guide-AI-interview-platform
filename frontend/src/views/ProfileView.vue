<template>
  <div class="profile-page">
    <header class="page-header">
      <h1>个人中心</h1>
      <p>查看你的面试准备进度与历史数据统计</p>
    </header>

    <!-- 加载骨架 -->
    <div v-if="loading" class="stats-grid">
      <div v-for="i in 4" :key="i" class="stat-card skeleton-card">
        <div class="skeleton skeleton-num"></div>
        <div class="skeleton skeleton-label"></div>
      </div>
    </div>

    <!-- 数据卡片 -->
    <div v-else class="stats-grid">
      <div class="stat-card fade-in-up">
        <div class="stat-icon-wrap stat-icon-resume">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stats?.resumeCount ?? 0 }}</div>
          <div class="stat-label">简历数量</div>
        </div>
      </div>

      <div class="stat-card fade-in-up" style="animation-delay: 80ms">
        <div class="stat-icon-wrap stat-icon-interview">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stats?.sessionCount ?? 0 }}</div>
          <div class="stat-label">面试会话</div>
        </div>
      </div>

      <div class="stat-card fade-in-up" style="animation-delay: 160ms">
        <div class="stat-icon-wrap stat-icon-finished">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M9 11l3 3L22 4 M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stats?.finishedSessionCount ?? 0 }}</div>
          <div class="stat-label">已完成面试</div>
        </div>
      </div>

      <div class="stat-card fade-in-up" style="animation-delay: 240ms">
        <div class="stat-icon-wrap stat-icon-score">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M3 3v18h18 M7 14l4-4 4 4 5-5"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ formatScore(stats?.avgResumeScore) }}</div>
          <div class="stat-label">简历平均分</div>
        </div>
      </div>
    </div>

    <!-- 平均面试评分 -->
    <div v-if="!loading && stats?.avgInterviewScore != null" class="score-banner fade-in-up">
      <div class="banner-content">
        <div class="banner-text">
          <h3>面试答题平均分</h3>
          <p>基于所有已完成面试题目的 AI 评分</p>
        </div>
        <div class="banner-score">
          <span class="banner-score-num">{{ formatScore(stats?.avgInterviewScore) }}</span>
          <span class="banner-score-unit">分</span>
        </div>
      </div>
    </div>

    <!-- 最近活动 -->
    <section class="recent-section">
      <div class="section-header">
        <h2>最近活动</h2>
        <div class="section-actions">
          <button class="btn-link" @click="router.push('/resume/history')">简历历史</button>
          <button class="btn-link" @click="router.push('/history')">面试记录</button>
        </div>
      </div>

      <div v-if="!loading && stats?.recentActivities?.length" class="activity-list">
        <div v-for="(a, idx) in stats.recentActivities" :key="idx"
             class="activity-item fade-in-up" :style="{ animationDelay: (idx * 60) + 'ms' }">
          <div class="activity-icon" :class="a.type">
            <svg v-if="a.type === 'resume'" width="14" height="14" viewBox="0 0 24 24" fill="none">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6"
                stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
                stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <div class="activity-content">
            <div class="activity-title">{{ a.title }}</div>
            <div class="activity-desc">{{ a.description }}</div>
          </div>
          <div class="activity-time">{{ fmtRelative(a.createdAt) }}</div>
        </div>
      </div>

      <div v-else-if="!loading" class="empty-state">
        <div class="empty-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
            <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2 M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2 M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2 M9 12h6 M9 16h4"
              stroke="var(--c-text-quaternary)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="empty-title">暂无活动记录</div>
        <div class="empty-desc">开始使用后，最近活动会出现在这里</div>
        <BaseButton variant="gradient" @click="router.push('/resume')">开始使用</BaseButton>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton } from '../components'

const router = useRouter()
const stats = ref<any>(null)
const loading = ref(true)

interface RecentActivity {
  type: string
  title: string
  description: string
  createdAt: string
}

onMounted(() => loadStats())

async function loadStats() {
  loading.value = true
  try {
    const data = await api.get('/api/stats/dashboard') as unknown as any
    stats.value = data
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载统计数据失败'))
  } finally {
    loading.value = false
  }
}

function formatScore(s?: number | null): string {
  if (s == null || isNaN(s)) return '-'
  return s.toFixed(1)
}

function fmtRelative(iso: string): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return '-'
  const now = Date.now()
  const diff = now - d.getTime()
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < hour) return Math.max(1, Math.floor(diff / minute)) + ' 分钟前'
  if (diff < day) return Math.floor(diff / hour) + ' 小时前'
  if (diff < 30 * day) return Math.floor(diff / day) + ' 天前'
  return d.toLocaleDateString('zh-CN')
}
</script>

<style scoped>
.profile-page {
  max-width: 1100px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 32px;
}

.page-header h1 {
  font-size: 28px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 6px;
  letter-spacing: -0.5px;
}

.page-header p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
}

/* ── 数据卡片 ── */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  margin-bottom: 32px;
}

.stat-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 14px;
  transition: all var(--transition-base);
  box-shadow: var(--shadow-xs);
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--c-border);
}

.stat-icon-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  flex-shrink: 0;
}

.stat-icon-resume {
  background: var(--brand-primary-50);
  color: var(--brand-primary);
}

.stat-icon-interview {
  background: var(--c-info-light);
  color: var(--c-info);
}

.stat-icon-finished {
  background: var(--c-success-light);
  color: var(--c-success);
}

.stat-icon-score {
  background: var(--c-warning-light);
  color: var(--c-warning);
}

.stat-info {
  flex: 1;
  min-width: 0;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--c-text);
  line-height: 1.1;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 12px;
  color: var(--c-text-secondary);
}

.skeleton-card {
  height: 84px;
}

.skeleton-num {
  width: 60px;
  height: 24px;
  margin-bottom: 6px;
}

.skeleton-label {
  width: 80px;
  height: 14px;
}

/* ── 平均分横幅 ── */
.score-banner {
  background: var(--brand-gradient);
  border-radius: var(--radius-xl);
  padding: 24px 32px;
  margin-bottom: 32px;
  box-shadow: var(--shadow-brand);
}

.banner-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.banner-text h3 {
  font-size: 18px;
  font-weight: 600;
  color: #fff;
  margin: 0 0 4px;
}

.banner-text p {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.9);
  margin: 0;
}

.banner-score {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.banner-score-num {
  font-size: 36px;
  font-weight: 800;
  color: #fff;
  line-height: 1;
}

.banner-score-unit {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.9);
}

/* ── 最近活动 ── */
.recent-section {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 24px;
  box-shadow: var(--shadow-xs);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.section-header h2 {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0;
}

.section-actions {
  display: flex;
  gap: 16px;
}

.btn-link {
  font-size: 13px;
  color: var(--brand-primary);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
  transition: color var(--transition-fast);
}

.btn-link:hover {
  color: var(--brand-primary-hover);
  text-decoration: underline;
}

.activity-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.activity-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: var(--radius-md);
  transition: background var(--transition-fast);
}

.activity-item:hover {
  background: var(--c-bg-alt);
}

.activity-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}

.activity-icon.resume {
  background: var(--brand-primary-50);
  color: var(--brand-primary);
}

.activity-icon.interview {
  background: var(--c-info-light);
  color: var(--c-info);
}

.activity-content {
  flex: 1;
  min-width: 0;
}

.activity-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text);
  margin-bottom: 2px;
}

.activity-desc {
  font-size: 12px;
  color: var(--c-text-secondary);
}

.activity-time {
  font-size: 12px;
  color: var(--c-text-tertiary);
  white-space: nowrap;
  flex-shrink: 0;
}

/* ── 空状态 ── */
.empty-state {
  text-align: center;
  padding: 48px 24px;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
  opacity: 0.6;
}

.empty-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 6px;
}

.empty-desc {
  font-size: 13px;
  color: var(--c-text-secondary);
  margin-bottom: 20px;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }
  .banner-content {
    flex-direction: column;
    align-items: flex-start;
    text-align: left;
  }
  .section-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}

@media (max-width: 480px) {
  .stats-grid {
    grid-template-columns: 1fr;
  }
}
</style>
