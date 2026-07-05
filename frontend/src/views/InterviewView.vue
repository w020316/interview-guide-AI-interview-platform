<template>
  <div class="interview-page">
    <header class="page-header">
      <h1>AI 模拟面试</h1>
      <p>基于岗位与简历生成题目，AI 实时提示，自动评估打分</p>
    </header>

    <!-- Step 1: 创建会话 -->
    <div v-if="!sessionId" class="setup-card fade-in-up">
      <div class="form-grid">
        <div class="field-row">
          <label>目标岗位</label>
          <input v-model="jobDesc" type="text" placeholder="Java 后端开发工程师" />
        </div>
        <div class="field-row">
          <label>简历摘要 <span class="optional">（可选）</span></label>
          <textarea v-model="resumeText" rows="4" placeholder="粘贴简历核心内容，AI 将据此定制题目"></textarea>
        </div>
        <div class="field-row">
          <label>题目数量</label>
          <div class="count-stepper">
            <button @click="count = Math.max(3, count - 1)" :disabled="count <= 3">−</button>
            <span class="count-value">{{ count }}</span>
            <button @click="count = Math.min(10, count + 1)" :disabled="count >= 10">+</button>
          </div>
          <div class="count-hint">建议 5 题，约 20-30 分钟</div>
        </div>
      </div>
      <button class="btn-primary" :disabled="loading" @click="startInterview">
        <span v-if="loading" class="spinner"></span>
        {{ loading ? '正在准备…' : '开始面试' }}
      </button>
    </div>

    <!-- Step 2: 面试进行中 -->
    <div v-else class="interview-session fade-in">
      <!-- 进度条 -->
      <div class="progress-wrap">
        <div class="progress-info">
          <span class="progress-label">面试进度</span>
          <span class="progress-count">第 {{ qIndex + 1 }} / {{ questions.length }} 题</span>
        </div>
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: progress + '%' }"></div>
        </div>
      </div>

      <!-- 题目卡片 -->
      <div v-if="currentQ" class="question-card">
        <div class="q-meta">
          <span class="tag tag-category">{{ currentQ.category }}</span>
          <span class="tag" :class="diffClass(currentQ.difficulty)">{{ currentQ.difficulty }}</span>
        </div>
        <h3 class="q-title">{{ currentQ.question }}</h3>

        <!-- SSE 流式 AI 提示 -->
        <div class="hint-section">
          <button class="hint-toggle" @click="hintOpen = !hintOpen">
            <span class="hint-icon">💡</span>
            <span>AI 实时提示</span>
            <span class="hint-arrow" :class="{ open: hintOpen }">▾</span>
          </button>
          <div v-if="hintOpen" class="hint-body">
            <div class="stream-box" v-html="streamHtml"></div>
            <div class="hint-actions">
              <button class="btn-ghost btn-sm" @click="streamHint" :disabled="streaming">
                <span v-if="streaming" class="spinner-sm"></span>
                {{ streaming ? '获取中…' : (streamContent ? '重新获取' : '获取 AI 提示') }}
              </button>
              <button v-if="streaming" class="btn-ghost btn-sm" @click="stopStream">停止</button>
            </div>
          </div>
        </div>

        <!-- 答题区 -->
        <div class="answer-section">
          <label>你的回答</label>
          <textarea v-model="userAnswer" rows="6" placeholder="请输入你的回答，可结合项目经验展开…"></textarea>
          <div class="action-row">
            <button class="btn-primary" :disabled="evalLoading" @click="submitAnswer">
              <span v-if="evalLoading" class="spinner"></span>
              {{ evalLoading ? '评估中…' : '提交回答' }}
            </button>
            <button v-if="qIndex < questions.length - 1" class="btn-ghost" @click="nextQuestion">跳过本题</button>
            <button v-if="qIndex === questions.length - 1" class="btn-success" @click="finishSession">结束面试</button>
          </div>
        </div>
      </div>

      <!-- 评估结果卡片 -->
      <div v-if="evalResult" class="eval-card fade-in-up">
        <div class="eval-head">
          <span class="eval-icon">📊</span>
          <h4>AI 评估结果</h4>
        </div>
        <div class="eval-scores">
          <div class="score-item">
            <div class="score-num" :style="{ color: scoreColor(evalResult.overallScore) }">
              {{ evalResult.overallScore ?? '-' }}
            </div>
            <div class="score-name">综合</div>
          </div>
          <div class="score-divider"></div>
          <div class="score-item">
            <div class="score-num" :style="{ color: scoreColor(evalResult.completeness) }">
              {{ evalResult.completeness ?? '-' }}
            </div>
            <div class="score-name">完整性</div>
          </div>
          <div class="score-divider"></div>
          <div class="score-item">
            <div class="score-num" :style="{ color: scoreColor(evalResult.accuracy) }">
              {{ evalResult.accuracy ?? '-' }}
            </div>
            <div class="score-name">准确性</div>
          </div>
        </div>
        <div v-if="evalResult.improvements?.length" class="improve-list">
          <div class="improve-title">改进建议</div>
          <ul>
            <li v-for="(i, idx) in evalResult.improvements" :key="idx">{{ i }}</li>
          </ul>
        </div>
        <div class="action-row">
          <button v-if="qIndex < questions.length - 1" class="btn-primary" @click="nextQuestion">下一题</button>
          <button v-else class="btn-success" @click="finishSession">结束面试</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { AI_TIMEOUT, getErrMessage } from '../api'
import { authState, isTokenValid, clearAuth } from '../auth'
import MarkdownIt from 'markdown-it'

// html: false 禁止 HTML 标签通过，防止 XSS
const md = new MarkdownIt({ html: false, linkify: true })

interface Question {
  question: string
  category: string
  difficulty: string
  referenceAnswer?: string
}

interface EvalResult {
  overallScore?: number
  completeness?: number
  accuracy?: number
  expression?: number
  improvements?: string[]
}

const jobDesc = ref('Java 后端开发工程师')
const resumeText = ref('')
const count = ref(5)
const loading = ref(false)
const sessionId = ref('')
const questions = ref<Question[]>([])
const qIndex = ref(0)
const userAnswer = ref('')
const evalLoading = ref(false)
const evalResult = ref<EvalResult | null>(null)
const streaming = ref(false)
const streamContent = ref('')
const hintOpen = ref(false)

// AbortController 用于取消 SSE 流式请求
let abortController: AbortController | null = null

const currentQ = computed(() => questions.value[qIndex.value])
// 修复进度条：第一题不为 0%，最后一题能到 100%
const progress = computed(() =>
  Math.round(((qIndex.value + 1) / Math.max(questions.value.length, 1)) * 100)
)
const streamHtml = computed(() => md.render(streamContent.value || '等待获取...'))

function diffClass(d: string) {
  if (d === 'HARD') return 'tag-danger'
  if (d === 'MEDIUM') return 'tag-warning'
  return 'tag-success'
}

function scoreColor(s?: number) {
  if (s == null) return 'var(--c-text-tertiary)'
  if (s >= 85) return '#10b981'
  if (s >= 70) return '#3b82f6'
  if (s >= 60) return '#f59e0b'
  return '#ef4444'
}

/** JSON.parse 失败时返回 fallback，不抛异常 */
function safeParse<T>(str: string, fallback: T): T {
  try { return JSON.parse(str) as T } catch { return fallback }
}

async function startInterview() {
  if (!jobDesc.value.trim()) return ElMessage.warning('请填写目标岗位')
  loading.value = true
  try {
    // 创建会话（userId 由后端从 token 提取，前端不传）
    const sess = await api.post('/api/session/create',
      { jobDescription: jobDesc.value }, { timeout: AI_TIMEOUT }) as unknown as { sessionId: string }
    sessionId.value = sess.sessionId

    // 生成面试题
    const qs = await api.post('/api/interview/questions',
      { resumeText: resumeText.value || jobDesc.value, jobDescription: jobDesc.value, count: count.value },
      { timeout: AI_TIMEOUT }) as unknown as string
    const parsed = safeParse<Question[]>(qs, [])
    if (!parsed.length) {
      ElMessage.error('面试题生成失败，请重试')
      // 回滚：题目生成失败时清空 sessionId，避免用户卡在空界面
      sessionId.value = ''
      return
    }
    questions.value = parsed
    ElMessage.success(`已生成 ${questions.value.length} 道题目，开始面试！`)
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '创建面试失败'))
    // 回滚 sessionId，让用户能重新开始
    sessionId.value = ''
  } finally { loading.value = false }
}

async function streamHint() {
  if (!currentQ.value) return
  // 防重入：若上一次流仍在，先 abort
  if (streaming.value) {
    abortController?.abort()
  }
  streaming.value = true
  streamContent.value = ''
  abortController = new AbortController()
  try {
    const token = authState.token
    if (!isTokenValid(token)) {
      ElMessage.error('登录已过期，请重新登录')
      clearAuth()
      streaming.value = false
      return
    }
    const resp = await fetch(`${import.meta.env.VITE_API_BASE_URL || ''}/api/interview/ask/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({ question: currentQ.value.question }),
      signal: abortController.signal
    })
    // HTTP 状态码校验：非 2xx 不应作为流式内容渲染
    if (!resp.ok) {
      if (resp.status === 401) {
        ElMessage.error('登录已过期，请重新登录')
        clearAuth()
      } else {
        ElMessage.error(`AI 提示请求失败（HTTP ${resp.status}）`)
      }
      streaming.value = false
      return
    }
    if (!resp.body) throw new Error('Response body is null')
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // 按行解析 SSE
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (data === '[DONE]') { streaming.value = false; return }
          streamContent.value += data
        }
      }
    }
  } catch (e: unknown) {
    if ((e as Error).name === 'AbortError') {
      // 用户主动取消，静默
    } else if (e instanceof TypeError) {
      ElMessage.error('网络连接失败，请检查网络后重试')
    } else {
      ElMessage.error(getErrMessage(e, '流式请求失败'))
    }
  } finally { streaming.value = false }
}

function stopStream() {
  abortController?.abort()
  streaming.value = false
}

async function submitAnswer() {
  if (!userAnswer.value.trim()) return ElMessage.warning('请输入回答')
  evalLoading.value = true
  evalResult.value = null
  try {
    // 不再传 referenceAnswer，由后端自行评估
    const data = await api.post('/api/interview/evaluate', {
      question: currentQ.value.question,
      userAnswer: userAnswer.value
    }) as unknown as string
    evalResult.value = safeParse<EvalResult>(data, {})
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '评估失败'))
  } finally { evalLoading.value = false }
}

function nextQuestion() {
  if (qIndex.value < questions.value.length - 1) {
    qIndex.value++
    userAnswer.value = ''
    evalResult.value = null
    streamContent.value = ''
  }
}

async function finishSession() {
  if (!sessionId.value) return
  try {
    await api.put(`/api/session/${sessionId.value}/finish`)
    ElMessage.success('面试结束，结果已保存！')
    sessionId.value = ''
    qIndex.value = 0
    questions.value = []
  } catch (e: unknown) {
    // 失败时提供"重试"与"强制退出"两个选项，避免用户卡死
    try {
      await ElMessageBox.confirm(
        getErrMessage(e, '结束面试失败') + '。是否强制退出当前面试？（后端记录可能未保存）',
        '结束失败',
        { confirmButtonText: '强制退出', cancelButtonText: '重试', type: 'warning' }
      )
      // 用户选择强制退出，清本地状态
      sessionId.value = ''
      qIndex.value = 0
      questions.value = []
    } catch {
      // 用户选择重试，不清理状态
    }
  }
}

// 组件卸载时取消流式请求
onUnmounted(() => {
  abortController?.abort()
})
</script>

<style scoped>
.interview-page {
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

/* ── Step 1: 创建会话 ── */
.setup-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 32px;
  box-shadow: var(--shadow-sm);
}

.form-grid {
  display: flex;
  flex-direction: column;
  gap: 20px;
  margin-bottom: 24px;
}

.field-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-row label {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
}

.optional {
  color: var(--c-text-tertiary);
  font-weight: 400;
}

.field-row input,
.field-row textarea {
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

.field-row input::placeholder,
.field-row textarea::placeholder {
  color: var(--c-text-tertiary);
}

.field-row input:focus,
.field-row textarea:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.12);
}

/* ── 题目数量步进器 ── */
.count-stepper {
  display: inline-flex;
  align-items: center;
  gap: 16px;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  padding: 6px;
  width: fit-content;
}

.count-stepper button {
  width: 32px;
  height: 32px;
  border: none;
  background: var(--c-surface);
  border-radius: var(--radius-sm);
  font-size: 18px;
  color: var(--c-text);
  cursor: pointer;
  transition: all var(--transition-fast);
  display: flex;
  align-items: center;
  justify-content: center;
}

.count-stepper button:hover:not(:disabled) {
  background: var(--brand-primary);
  color: #fff;
}

.count-stepper button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.count-value {
  font-size: 18px;
  font-weight: 600;
  color: var(--c-text);
  min-width: 24px;
  text-align: center;
}

.count-hint {
  font-size: 12px;
  color: var(--c-text-tertiary);
  margin-top: 2px;
}

/* ── 按钮 ── */
.btn-primary {
  padding: 12px 28px;
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-gradient);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: 0 4px 12px rgba(79, 70, 229, 0.25);
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(79, 70, 229, 0.35);
}

.btn-primary:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.btn-success {
  padding: 12px 28px;
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(135deg, #10b981, #059669);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: 0 4px 12px rgba(16, 185, 129, 0.25);
}

.btn-success:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(16, 185, 129, 0.35);
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

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

.spinner-sm {
  width: 13px;
  height: 13px;
  border: 2px solid rgba(79, 70, 229, 0.3);
  border-top-color: var(--brand-primary);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── Step 2: 面试进行中 ── */
.interview-session {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ── 进度条 ── */
.progress-wrap {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 16px 20px;
  box-shadow: var(--shadow-sm);
}

.progress-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.progress-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-secondary);
}

.progress-count {
  font-size: 13px;
  font-weight: 600;
  color: var(--brand-primary);
}

.progress-bar {
  height: 8px;
  background: var(--c-bg-alt);
  border-radius: 999px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--brand-gradient);
  border-radius: 999px;
  transition: width 0.4s ease;
}

/* ── 题目卡片 ── */
.question-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 28px;
  box-shadow: var(--shadow-sm);
}

.q-meta {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.tag {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
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

.q-title {
  font-size: 19px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 24px;
  line-height: 1.5;
  letter-spacing: -0.2px;
}

/* ── AI 提示区 ── */
.hint-section {
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  margin-bottom: 24px;
  overflow: hidden;
}

.hint-toggle {
  width: 100%;
  padding: 12px 16px;
  background: transparent;
  border: none;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: background var(--transition-fast);
}

.hint-toggle:hover {
  background: rgba(79, 70, 229, 0.04);
}

.hint-icon {
  font-size: 16px;
}

.hint-arrow {
  margin-left: auto;
  font-size: 12px;
  color: var(--c-text-tertiary);
  transition: transform var(--transition-fast);
}

.hint-arrow.open {
  transform: rotate(180deg);
}

.hint-body {
  padding: 0 16px 16px;
}

.stream-box {
  font-size: 14px;
  line-height: 1.7;
  max-height: 240px;
  overflow-y: auto;
  background: var(--c-surface);
  padding: 14px 16px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--c-border-light);
  margin-bottom: 10px;
  color: var(--c-text);
}

.stream-box :deep(p) {
  margin: 0 0 8px;
}

.stream-box :deep(p:last-child) {
  margin-bottom: 0;
}

.stream-box :deep(code) {
  background: var(--c-bg-alt);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 13px;
}

.hint-actions {
  display: flex;
  gap: 8px;
}

/* ── 答题区 ── */
.answer-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.answer-section label {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
}

.answer-section textarea {
  padding: 14px 16px;
  font-size: 14px;
  font-family: var(--font-sans);
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  outline: none;
  transition: all var(--transition-fast);
  resize: vertical;
  line-height: 1.6;
}

.answer-section textarea::placeholder {
  color: var(--c-text-tertiary);
}

.answer-section textarea:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.12);
}

.action-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 4px;
}

/* ── 评估结果卡片 ── */
.eval-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 28px;
  box-shadow: var(--shadow-sm);
}

.eval-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}

.eval-icon {
  font-size: 22px;
}

.eval-head h4 {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0;
}

.eval-scores {
  display: flex;
  align-items: center;
  justify-content: space-around;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  padding: 20px;
  margin-bottom: 20px;
}

.score-item {
  text-align: center;
  flex: 1;
}

.score-num {
  font-size: 32px;
  font-weight: 700;
  line-height: 1;
  margin-bottom: 6px;
  letter-spacing: -1px;
}

.score-name {
  font-size: 13px;
  color: var(--c-text-secondary);
  font-weight: 500;
}

.score-divider {
  width: 1px;
  height: 40px;
  background: var(--c-border);
}

.improve-list {
  background: rgba(245, 158, 11, 0.05);
  border-left: 3px solid var(--c-warning);
  padding: 14px 18px;
  border-radius: var(--radius-sm);
  margin-bottom: 20px;
}

.improve-title {
  font-size: 13px;
  font-weight: 600;
  color: #92400e;
  margin-bottom: 8px;
}

.improve-list ul {
  margin: 0;
  padding-left: 18px;
}

.improve-list li {
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.7;
  margin-bottom: 4px;
}

/* ── 响应式 ── */
@media (max-width: 640px) {
  .interview-page {
    padding: 0 4px;
  }
  .setup-card,
  .question-card,
  .eval-card {
    padding: 20px;
  }
  .eval-scores {
    padding: 14px 8px;
  }
  .score-num {
    font-size: 26px;
  }
}
</style>
