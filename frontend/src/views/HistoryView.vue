<template>
  <div class="history-page">
    <header class="page-header">
      <h1>历史面试记录</h1>
      <p>回顾历次模拟面试的题目、回答与评分</p>
    </header>

    <!-- 空状态 -->
    <div v-if="!sessions.length && !loading" class="empty-state fade-in">
      <div class="empty-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
          <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2 M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2 M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2 M9 12h6 M9 16h4"
            stroke="var(--c-text-quaternary)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <div class="empty-title">暂无面试记录</div>
      <div class="empty-desc">完成一次模拟面试后，记录会出现在这里</div>
      <button class="btn-primary" @click="$router.push('/interview')">前往面试</button>
    </div>

    <!-- 会话列表 -->
    <div v-else class="session-list">
      <div v-for="s in sessions" :key="s.sessionId" class="session-card fade-in-up">
        <div class="session-head" @click="toggleSession(s.sessionId)">
          <div class="session-info">
            <div class="session-title">{{ s.jobDescription || '未指定岗位' }}</div>
            <div class="session-meta">
              <span class="meta-item">
                <span class="meta-icon">🕒</span>
                {{ fmtDate(s.createdAt) }}
              </span>
              <span class="status-badge" :class="statusClass(s.status)">{{ statusText(s.status) }}</span>
            </div>
          </div>
          <button class="btn-ghost btn-sm" @click.stop="loadQuestions(s.sessionId)" :disabled="loadingId === s.sessionId">
            <span v-if="loadingId === s.sessionId" class="spinner-sm"></span>
            {{ qMap[s.sessionId] ? '收起' : '查看题目' }}
          </button>
        </div>

        <div v-if="qMap[s.sessionId]" class="question-list">
          <div v-if="!qMap[s.sessionId].length" class="empty-questions">
            该会话暂无题目记录
          </div>
          <div v-for="(q, idx) in qMap[s.sessionId]" :key="q.id" class="question-item">
            <div class="q-index">{{ idx + 1 }}</div>
            <div class="q-content">
              <div class="q-text">{{ q.question }}</div>
              <div v-if="q.userAnswer" class="q-answer">
                <span class="answer-label">我的回答：</span>
                <span class="answer-text">{{ q.userAnswer }}</span>
              </div>
              <div v-else class="q-answer q-answer-empty">
                <span class="answer-label">未作答</span>
              </div>
              <div v-if="q.evaluationScore != null" class="q-score" :style="{ color: scoreColor(q.evaluationScore) }">
                评分 {{ q.evaluationScore }} 分
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'

interface Session {
  sessionId: string
  jobDescription: string
  status: string
  createdAt: string
}

interface Question {
  id: number
  question: string
  userAnswer?: string
  evaluationScore?: number | null
}

const sessions = ref<Session[]>([])
const qMap = ref<Record<string, Question[]>>({})
const loadingId = ref('')
const loading = ref(false)

onMounted(() => loadHistory())

async function loadHistory() {
  loading.value = true
  try {
    // userId 由后端从 token 提取，前端不传
    const data = await api.get('/api/session/list') as unknown as Session[]
    sessions.value = data || []
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载历史失败'))
  } finally { loading.value = false }
}

async function loadQuestions(sessionId: string) {
  // 若已展开，点击则收起
  if (qMap.value[sessionId]) {
    delete qMap.value[sessionId]
    qMap.value = { ...qMap.value }
    return
  }
  loadingId.value = sessionId
  try {
    const data = await api.get(`/api/session/${sessionId}/questions`) as unknown as Question[]
    qMap.value = { ...qMap.value, [sessionId]: data || [] }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载题目失败'))
  } finally { loadingId.value = '' }
}

function toggleSession(sessionId: string) {
  if (qMap.value[sessionId]) {
    delete qMap.value[sessionId]
    qMap.value = { ...qMap.value }
  } else {
    loadQuestions(sessionId)
  }
}

function fmtDate(dt: string) {
  if (!dt) return '-'
  const d = new Date(dt)
  if (isNaN(d.getTime())) return '-'
  return d.toLocaleString('zh-CN', { hour12: false })
}

function statusClass(status: string) {
  if (!status) return ''
  if (status === 'FINISHED' || status === 'COMPLETED') return 'status-success'
  if (status === 'IN_PROGRESS' || status === 'ACTIVE') return 'status-info'
  return 'status-default'
}

function statusText(status: string) {
  if (!status) return '未知'
  const map: Record<string, string> = {
    FINISHED: '已完成',
    COMPLETED: '已完成',
    IN_PROGRESS: '进行中',
    ACTIVE: '进行中',
  }
  return map[status] || status
}

function scoreColor(s?: number | null) {
  if (s == null) return 'var(--c-text-tertiary)'
  if (s >= 85) return '#10b981'
  if (s >= 70) return '#3b82f6'
  if (s >= 60) return '#f59e0b'
  return '#ef4444'
}
</script>

<style scoped>
.history-page {
  max-width: 900px;
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

/* ── 空状态 ── */
.empty-state {
  text-align: center;
  padding: 64px 24px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.empty-icon {
  font-size: 56px;
  margin-bottom: 16px;
  opacity: 0.6;
}

.empty-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 6px;
}

.empty-desc {
  font-size: 14px;
  color: var(--c-text-tertiary);
  margin-bottom: 24px;
}

/* ── 会话列表 ── */
.session-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.session-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  transition: box-shadow var(--transition-fast);
}

.session-card:hover {
  box-shadow: var(--shadow-md);
}

.session-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 18px 24px;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.session-head:hover {
  background: var(--c-bg-alt);
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.meta-item {
  font-size: 13px;
  color: var(--c-text-tertiary);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.meta-icon {
  font-size: 12px;
}

.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  font-size: 12px;
  font-weight: 500;
  border-radius: 999px;
}

.status-success {
  background: rgba(16, 185, 129, 0.1);
  color: #059669;
}

.status-info {
  background: rgba(59, 130, 246, 0.1);
  color: #2563eb;
}

.status-default {
  background: var(--c-bg-alt);
  color: var(--c-text-secondary);
}

/* ── 题目列表 ── */
.question-list {
  padding: 0 24px 20px;
  border-top: 1px solid var(--c-border-light);
  padding-top: 16px;
  margin-top: 0;
  background: var(--c-bg);
}

.empty-questions {
  text-align: center;
  font-size: 13px;
  color: var(--c-text-tertiary);
  padding: 24px 0;
}

.question-item {
  display: flex;
  gap: 14px;
  padding: 16px 0;
  border-bottom: 1px dashed var(--c-border-light);
}

.question-item:last-child {
  border-bottom: none;
}

.q-index {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  border-radius: 50%;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.q-content {
  flex: 1;
  min-width: 0;
}

.q-text {
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text);
  line-height: 1.6;
  margin-bottom: 6px;
}

.q-answer {
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-secondary);
  margin-bottom: 6px;
}

.q-answer-empty {
  color: var(--c-text-tertiary);
  font-style: italic;
}

.answer-label {
  font-weight: 500;
  color: var(--c-text);
}

.q-score {
  display: inline-block;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  background: var(--c-bg-alt);
  border-radius: 999px;
}

/* ── 按钮 ── */
.btn-primary {
  padding: 11px 24px;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-gradient);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: 0 4px 12px rgba(15, 118, 110, 0.25);
}

.btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(15, 118, 110, 0.35);
}

.btn-ghost {
  padding: 11px 22px;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.btn-ghost:hover:not(:disabled) {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  background: var(--brand-primary-light);
}

.btn-ghost:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-sm {
  padding: 7px 14px;
  font-size: 13px;
}

.spinner-sm {
  width: 13px;
  height: 13px;
  border: 2px solid rgba(15, 118, 110, 0.3);
  border-top-color: var(--brand-primary);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── 响应式 ── */
@media (max-width: 640px) {
  .session-head {
    padding: 16px;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .question-list {
    padding: 16px;
  }
  .session-title {
    white-space: normal;
  }
}
</style>
