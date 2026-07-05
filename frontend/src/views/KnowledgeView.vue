<template>
  <div class="knowledge-page">
    <header class="page-header">
      <h1>知识库</h1>
      <p>导入面试知识点 / 八股文，AI 将基于知识库增强问答；并关联每次模拟面试的错题与题目汇总</p>
    </header>

    <!-- Tab 切换 -->
    <div class="tab-switch">
      <button :class="{ active: tab === 'ask' }" @click="tab = 'ask'">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
            stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        RAG 问答
      </button>
      <button :class="{ active: tab === 'import' }" @click="tab = 'import'">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
          <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        导入知识
      </button>
      <button :class="{ active: tab === 'wrong' }" @click="switchTab('wrong')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
          <path d="M12 9v4 M12 17h.01 M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"
            stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        错题总结
      </button>
      <button :class="{ active: tab === 'summary' }" @click="switchTab('summary')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
          <path d="M3 3v18h18 M7 14l4-4 4 4 5-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        题目汇总
      </button>
    </div>

    <!-- RAG 问答 -->
    <div v-if="tab === 'ask'" class="ask-section fade-in">
      <div class="field-row">
        <label>你的问题</label>
        <textarea v-model="question" rows="3" placeholder="例如：HashMap 的底层原理是什么？"></textarea>
      </div>
      <button class="btn-primary" :disabled="loading" @click="ask">
        <span v-if="loading" class="spinner"></span>
        {{ loading ? '查询中…' : 'AI 知识问答' }}
      </button>

      <div v-if="answer" class="answer-card fade-in-up">
        <div class="answer-head">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2z M12 16v-4 M12 8h.01"
              stroke="var(--brand-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <h4>AI 回答</h4>
        </div>
        <div class="answer-body" v-html="renderedAnswer"></div>
      </div>
    </div>

    <!-- 导入知识 -->
    <div v-else-if="tab === 'import'" class="import-section fade-in">
      <div class="field-row">
        <label>分类 <span class="hint">可选</span></label>
        <input v-model="category" type="text" placeholder="例如：Spring、Java 基础" />
      </div>
      <div class="field-row">
        <label>知识点内容 <span class="hint">每行一条，建议 200 字以内</span></label>
        <textarea v-model="importText" rows="10"
          placeholder="例如：&#10;HashMap 基于哈希表实现，JDK 8 后采用数组+链表+红黑树结构。&#10;ConcurrentHashMap 在 JDK 8 中使用 CAS + synchronized 实现。"></textarea>
      </div>
      <button class="btn-primary" :disabled="importing" @click="importKnowledge">
        <span v-if="importing" class="spinner"></span>
        {{ importing ? '导入中…' : '批量导入' }}
      </button>

      <div v-if="importResult" class="result-card fade-in-up" :class="importResult.success ? 'success' : 'error'">
        <span>{{ importResult.message }}</span>
      </div>
    </div>

    <!-- 错题总结 -->
    <div v-else-if="tab === 'wrong'" class="wrong-section fade-in">
      <div class="wrong-controls">
        <div class="field-row inline">
          <label>错题阈值</label>
          <select v-model.number="threshold" @change="loadWrong">
            <option :value="60">低于 60 分</option>
            <option :value="70">低于 70 分</option>
            <option :value="80">低于 80 分</option>
            <option :value="90">低于 90 分</option>
          </select>
        </div>
        <button class="btn-ghost btn-sm" :disabled="wrongLoading" @click="loadWrong">
          <span v-if="wrongLoading" class="spinner-sm"></span>
          {{ wrongLoading ? '加载中…' : '刷新' }}
        </button>
      </div>

      <div v-if="wrongLoading" class="loading-hint">正在查询错题…</div>

      <template v-else-if="wrongList.length">
        <div class="result-summary">
          共 <strong>{{ wrongList.length }}</strong> 道错题（评分低于 {{ threshold }} 分）
        </div>
        <div v-for="(q, idx) in wrongList" :key="q.id" class="wrong-card fade-in-up" :style="{ animationDelay: idx * 0.05 + 's' }">
          <div class="wrong-head">
            <span v-if="q.category" class="tag tag-category">{{ q.category }}</span>
            <span v-if="q.difficulty" class="tag" :class="diffClass(q.difficulty)">{{ q.difficulty }}</span>
            <span class="wrong-score" :style="{ color: scoreColor(q.evaluationScore) }">{{ q.evaluationScore }} 分</span>
            <span class="wrong-job">{{ q.jobDescription }}</span>
          </div>
          <h4 class="wrong-question">{{ q.question }}</h4>
          <div class="wrong-body">
            <div v-if="q.userAnswer" class="wrong-field">
              <span class="field-label">你的回答</span>
              <div class="field-content">{{ q.userAnswer }}</div>
            </div>
            <div v-if="q.referenceAnswer" class="wrong-field">
              <span class="field-label">参考答案</span>
              <div class="field-content ref-answer">{{ q.referenceAnswer }}</div>
            </div>
            <div class="wrong-meta">
              <span>会话：{{ q.sessionId?.slice(0, 8) }}…</span>
              <span v-if="q.createdAt">{{ formatTime(q.createdAt) }}</span>
            </div>
          </div>
        </div>
      </template>

      <div v-else class="empty-state">
        <div class="empty-icon">✓</div>
        <div class="empty-text">暂无错题，继续加油！</div>
        <div class="empty-hint">完成模拟面试后，评分低于阈值的题目会自动汇总到这里</div>
      </div>
    </div>

    <!-- 题目汇总 -->
    <div v-else-if="tab === 'summary'" class="summary-section fade-in">
      <div v-if="summaryLoading" class="loading-hint">正在统计…</div>

      <template v-else-if="summary">
        <!-- 概览卡片 -->
        <div class="stat-grid">
          <div class="stat-card">
            <div class="stat-num">{{ summary.totalQuestions }}</div>
            <div class="stat-name">总题数</div>
          </div>
          <div class="stat-card">
            <div class="stat-num" style="color: var(--brand-primary)">{{ summary.answeredQuestions }}</div>
            <div class="stat-name">已答题</div>
          </div>
          <div class="stat-card">
            <div class="stat-num" style="color: #ef4444">{{ summary.wrongQuestions }}</div>
            <div class="stat-name">错题数</div>
          </div>
          <div class="stat-card">
            <div class="stat-num" :style="{ color: scoreColor(summary.averageScore) }">{{ summary.averageScore }}</div>
            <div class="stat-name">平均分</div>
          </div>
        </div>

        <!-- 答题率进度条 -->
        <div v-if="summary.totalQuestions > 0" class="rate-bar-wrap">
          <div class="rate-bar-head">
            <span>答题率</span>
            <span>{{ ratePercent }}%</span>
          </div>
          <div class="rate-bar">
            <div class="rate-fill" :style="{ width: ratePercent + '%' }"></div>
          </div>
        </div>

        <!-- 按分类统计 -->
        <div v-if="summary.byCategory?.length" class="group-section">
          <h4 class="block-title">按分类统计</h4>
          <div v-for="(c, idx) in summary.byCategory" :key="idx" class="group-row">
            <div class="group-head">
              <span class="group-name">{{ c.category }}</span>
              <span class="group-score" :style="{ color: scoreColor(c.avgScore) }">{{ c.avgScore }} 分</span>
            </div>
            <div class="group-bar">
              <div class="group-bar-fill" :style="{ width: (c.total ? (c.answered / c.total) * 100 : 0) + '%', background: getScoreGradient(c.avgScore) }"></div>
            </div>
            <div class="group-meta">
              共 {{ c.total }} 题 · 已答 {{ c.answered }} · 错题 {{ c.wrong }}
            </div>
          </div>
        </div>

        <!-- 按难度统计 -->
        <div v-if="summary.byDifficulty?.length" class="group-section">
          <h4 class="block-title">按难度统计</h4>
          <div v-for="(d, idx) in summary.byDifficulty" :key="idx" class="group-row">
            <div class="group-head">
              <span class="group-name">{{ diffLabel(d.difficulty) }}</span>
              <span class="group-score" :style="{ color: scoreColor(d.avgScore) }">{{ d.avgScore }} 分</span>
            </div>
            <div class="group-bar">
              <div class="group-bar-fill" :style="{ width: (d.total ? (d.answered / d.total) * 100 : 0) + '%', background: getScoreGradient(d.avgScore) }"></div>
            </div>
            <div class="group-meta">
              共 {{ d.total }} 题 · 已答 {{ d.answered }} · 错题 {{ d.wrong }}
            </div>
          </div>
        </div>

        <div v-if="summary.totalQuestions === 0" class="empty-state">
          <div class="empty-icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
              <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2 M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2 M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2 M9 12h6 M9 16h4"
                stroke="var(--c-text-quaternary)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <div class="empty-text">暂无题目记录</div>
          <div class="empty-hint">完成模拟面试后，题目会自动汇总到这里</div>
        </div>
      </template>

      <div v-else class="empty-state">
        <div class="empty-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
            <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2 M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2 M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2 M9 12h6 M9 16h4"
              stroke="var(--c-text-quaternary)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="empty-text">暂无数据</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import api, { AI_TIMEOUT, getErrMessage } from '../api'

const md = new MarkdownIt({ html: false, linkify: true })

type Tab = 'ask' | 'import' | 'wrong' | 'summary'
const tab = ref<Tab>('ask')

// ── RAG 问答 ──
const question = ref('')
const answer = ref('')
const loading = ref(false)

// ── 导入知识 ──
const category = ref('')
const importText = ref('')
const importing = ref(false)
const importResult = ref<{ success: boolean; message: string } | null>(null)

// ── 错题总结 ──
interface WrongQuestion {
  id: number
  question: string
  category?: string
  difficulty?: string
  userAnswer?: string
  referenceAnswer?: string
  evaluationScore?: number
  sessionId?: string
  jobDescription?: string
  createdAt?: string
}
const threshold = ref(60)
const wrongList = ref<WrongQuestion[]>([])
const wrongLoading = ref(false)

// ── 题目汇总 ──
interface GroupStat {
  category?: string
  difficulty?: string
  total: number
  answered: number
  wrong: number
  avgScore: number
}
interface Summary {
  totalQuestions: number
  answeredQuestions: number
  wrongQuestions: number
  averageScore: number
  byCategory?: GroupStat[]
  byDifficulty?: GroupStat[]
}
const summary = ref<Summary | null>(null)
const summaryLoading = ref(false)

const renderedAnswer = computed(() =>
  DOMPurify.sanitize(md.render(answer.value || '*等待提问...*'),
    { FORBID_TAGS: ['style', 'iframe'], FORBID_ATTR: ['onerror', 'onload'] })
)
const ratePercent = computed(() => {
  if (!summary.value || !summary.value.totalQuestions) return 0
  return Math.round((summary.value.answeredQuestions / summary.value.totalQuestions) * 100)
})

async function ask() {
  if (!question.value.trim()) return ElMessage.warning('请输入问题')
  loading.value = true
  answer.value = ''
  try {
    const data = await api.post('/api/knowledge/ask',
      { question: question.value },
      { timeout: AI_TIMEOUT }) as unknown as string
    answer.value = data || '(空回答)'
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '问答失败'))
  } finally {
    loading.value = false
  }
}

async function importKnowledge() {
  if (!importText.value.trim()) return ElMessage.warning('请输入知识内容')
  const chunks = importText.value
    .split('\n')
    .map(s => s.trim())
    .filter(s => s.length > 0)
  if (!chunks.length) return ElMessage.warning('未识别到有效知识内容')

  importing.value = true
  importResult.value = null
  try {
    const data = await api.post('/api/knowledge/import/batch',
      { category: category.value || '通用', chunks },
      { timeout: AI_TIMEOUT }) as unknown as { imported: number; category: string }
    importResult.value = {
      success: true,
      message: `成功导入 ${data.imported} 条知识（分类：${data.category}）`,
    }
    ElMessage.success('导入成功')
    importText.value = ''
  } catch (e: unknown) {
    importResult.value = {
      success: false,
      message: getErrMessage(e, '导入失败'),
    }
    ElMessage.error('导入失败')
  } finally {
    importing.value = false
  }
}

/** 切换到错题/汇总 Tab 时自动加载数据 */
function switchTab(t: Tab) {
  tab.value = t
  if (t === 'wrong' && !wrongList.value.length && !wrongLoading.value) {
    loadWrong()
  } else if (t === 'summary' && !summary.value && !summaryLoading.value) {
    loadSummary()
  }
}

async function loadWrong() {
  wrongLoading.value = true
  try {
    const data = await api.get('/api/knowledge/wrong-questions',
      { params: { threshold: threshold.value } }) as unknown as { total: number; questions: WrongQuestion[] }
    wrongList.value = data.questions || []
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载错题失败'))
    wrongList.value = []
  } finally {
    wrongLoading.value = false
  }
}

async function loadSummary() {
  summaryLoading.value = true
  try {
    const data = await api.get('/api/knowledge/question-summary') as unknown as Summary
    summary.value = data
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载汇总失败'))
    summary.value = null
  } finally {
    summaryLoading.value = false
  }
}

function diffClass(d?: string) {
  if (d === 'HARD') return 'tag-danger'
  if (d === 'MEDIUM') return 'tag-warning'
  return 'tag-success'
}

function diffLabel(d?: string) {
  if (d === 'EASY') return '简单'
  if (d === 'MEDIUM') return '中等'
  if (d === 'HARD') return '困难'
  return d || '未知'
}

function scoreColor(s?: number) {
  if (s == null) return 'var(--c-text-tertiary)'
  if (s >= 85) return '#10b981'
  if (s >= 70) return '#3b82f6'
  if (s >= 60) return '#f59e0b'
  return '#ef4444'
}

function getScoreGradient(s: number) {
  if (s >= 85) return 'linear-gradient(90deg, #10b981, #34d399)'
  if (s >= 70) return 'linear-gradient(90deg, #3b82f6, #60a5fa)'
  if (s >= 60) return 'linear-gradient(90deg, #f59e0b, #fbbf24)'
  return 'linear-gradient(90deg, #ef4444, #f87171)'
}

function formatTime(t?: string) {
  if (!t) return ''
  try {
    const d = new Date(t.replace(' ', 'T'))
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  } catch {
    return t
  }
}
</script>

<style scoped>
.knowledge-page {
  max-width: 900px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;
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
  line-height: 1.6;
}

/* ── Tab ── */
.tab-switch {
  display: inline-flex;
  flex-wrap: wrap;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  padding: 4px;
  margin-bottom: 24px;
  gap: 4px;
}

.tab-switch button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
  font-family: inherit;
}

.tab-switch button.active {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: var(--shadow-sm);
}

/* ── 表单 ── */
.field-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.field-row.inline {
  flex-direction: row;
  align-items: center;
  gap: 12px;
  margin-bottom: 0;
}

.field-row label {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
}

.hint {
  color: var(--c-text-tertiary);
  font-weight: 400;
  font-size: 12px;
}

.field-row input,
.field-row textarea,
.field-row select {
  padding: 11px 14px;
  font-size: 14px;
  font-family: var(--font-sans);
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  outline: none;
  transition: all var(--transition-fast);
  resize: vertical;
}

.field-row input:focus,
.field-row textarea:focus,
.field-row select:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.12);
}

/* ── 按钮 ── */
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 11px 24px;
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

.btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(15, 118, 110, 0.4);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
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
  font-family: inherit;
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

.spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

.spinner-sm {
  display: inline-block;
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

/* ── 答案卡片 ── */
.answer-card {
  margin-top: 24px;
  padding: 20px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
}

.answer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--c-border-light);
}

.answer-head h4 {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
  color: var(--c-text);
}

.answer-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--c-text);
  word-break: break-word;
}

.answer-body :deep(h1),
.answer-body :deep(h2),
.answer-body :deep(h3) {
  font-size: 16px;
  margin: 16px 0 8px;
  color: var(--c-text);
}

.answer-body :deep(code) {
  background: var(--c-bg-alt);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--brand-primary);
}

.answer-body :deep(pre) {
  background: var(--c-bg-alt);
  padding: 12px;
  border-radius: var(--radius-md);
  overflow-x: auto;
  margin: 8px 0;
}

.answer-body :deep(p) {
  margin: 8px 0;
}

/* ── 结果卡片 ── */
.result-card {
  margin-top: 16px;
  padding: 12px 16px;
  border-radius: var(--radius-md);
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.result-card.success {
  background: var(--c-success-light);
  color: var(--c-success);
  border: 1px solid var(--c-success);
}

.result-card.error {
  background: var(--c-danger-light);
  color: var(--c-danger);
  border: 1px solid var(--c-danger);
}

/* ── 错题总结 ── */
.wrong-controls {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.wrong-controls .field-row.inline select {
  min-width: 140px;
}

.result-summary {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin-bottom: 16px;
  padding: 10px 14px;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
}

.result-summary strong {
  color: var(--c-danger);
  font-weight: 600;
}

.wrong-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-left: 3px solid var(--c-danger);
  border-radius: var(--radius-md);
  padding: 18px 20px;
  margin-bottom: 14px;
  box-shadow: var(--shadow-xs);
  animation: fadeInUp 0.4s ease both;
}

@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.wrong-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.tag {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 500;
  border-radius: 999px;
  letter-spacing: 0.3px;
}

.tag-category {
  background: var(--brand-primary-light);
  color: var(--brand-primary);
}

.tag-success {
  background: rgba(16, 185, 129, 0.1);
  color: #059669;
}

.tag-warning {
  background: rgba(245, 158, 11, 0.1);
  color: #d97706;
}

.tag-danger {
  background: rgba(239, 68, 68, 0.1);
  color: #dc2626;
}

.wrong-score {
  font-size: 14px;
  font-weight: 700;
  margin-left: auto;
}

.wrong-job {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.wrong-question {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 14px;
  line-height: 1.5;
  letter-spacing: -0.2px;
}

.wrong-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.wrong-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.field-content {
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.6;
  background: var(--c-bg-alt);
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  white-space: pre-wrap;
  word-break: break-word;
}

.field-content.ref-answer {
  border-left: 2px solid var(--brand-primary);
  background: var(--brand-primary-light);
  color: var(--c-text);
}

.wrong-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--c-text-tertiary);
  margin-top: 4px;
}

/* ── 题目汇总 ── */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 24px;
}

.stat-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  padding: 20px;
  text-align: center;
  box-shadow: var(--shadow-xs);
}

.stat-num {
  font-size: 32px;
  font-weight: 700;
  line-height: 1;
  margin-bottom: 6px;
  letter-spacing: -1px;
  color: var(--c-text);
}

.stat-name {
  font-size: 13px;
  color: var(--c-text-secondary);
  font-weight: 500;
}

.rate-bar-wrap {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  padding: 16px 20px;
  margin-bottom: 28px;
}

.rate-bar-head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-secondary);
  margin-bottom: 8px;
}

.rate-bar-head span:last-child {
  color: var(--brand-primary);
  font-weight: 600;
}

.rate-bar {
  height: 8px;
  background: var(--c-bg-alt);
  border-radius: 999px;
  overflow: hidden;
}

.rate-fill {
  height: 100%;
  background: var(--brand-gradient);
  border-radius: 999px;
  transition: width 0.6s ease;
}

.group-section {
  margin-bottom: 28px;
}

.block-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 14px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--c-border-light);
}

.group-row {
  margin-bottom: 14px;
}

.group-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.group-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text);
}

.group-score {
  font-size: 13px;
  font-weight: 600;
}

.group-bar {
  height: 6px;
  background: var(--c-bg-alt);
  border-radius: 999px;
  overflow: hidden;
  margin-bottom: 4px;
}

.group-bar-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.6s ease;
}

.group-meta {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

/* ── 空状态 / 加载 ── */
.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: var(--c-text-tertiary);
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
  color: var(--c-success);
}

.empty-text {
  font-size: 16px;
  font-weight: 500;
  color: var(--c-text-secondary);
  margin-bottom: 6px;
}

.empty-hint {
  font-size: 13px;
  color: var(--c-text-tertiary);
}

.loading-hint {
  text-align: center;
  padding: 40px;
  color: var(--c-text-tertiary);
  font-size: 14px;
}

/* ── 响应式 ── */
@media (max-width: 640px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .stat-num {
    font-size: 26px;
  }
  .wrong-card,
  .stat-card,
  .rate-bar-wrap {
    padding: 14px;
  }
}
</style>
