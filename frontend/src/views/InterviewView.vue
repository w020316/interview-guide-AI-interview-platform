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
          <BaseInput v-model="jobDesc" block list="job-suggestions" placeholder="如：Java 后端、产品经理、教师、医生、销售经理…" />
          <datalist id="job-suggestions">
            <option value="Java 后端开发工程师" />
            <option value="前端开发工程师" />
            <option value="Python 后端开发工程师" />
            <option value="Go 后端开发工程师" />
            <option value="全栈开发工程师" />
            <option value="iOS 开发工程师" />
            <option value="Android 开发工程师" />
            <option value="数据分析师" />
            <option value="算法工程师" />
            <option value="机器学习工程师" />
            <option value="产品经理" />
            <option value="项目经理" />
            <option value="UI/UX 设计师" />
            <option value="测试工程师" />
            <option value="运维工程师" />
            <option value="DevOps 工程师" />
            <option value="数据库管理员" />
            <option value="安全工程师" />
            <option value="教师" />
            <option value="医生" />
            <option value="护士" />
            <option value="药剂师" />
            <option value="律师" />
            <option value="会计师" />
            <option value="审计师" />
            <option value="财务经理" />
            <option value="销售经理" />
            <option value="市场专员" />
            <option value="运营专员" />
            <option value="人力资源专员" />
            <option value="行政助理" />
            <option value="翻译" />
            <option value="编辑" />
            <option value="记者" />
            <option value="建筑师" />
            <option value="土木工程师" />
            <option value="机械工程师" />
            <option value="电气工程师" />
            <option value="化工工程师" />
            <option value="供应链管理" />
            <option value="采购专员" />
            <option value="物流管理" />
            <option value="客户经理" />
            <option value="店长" />
            <option value="厨师" />
            <option value="摄影师" />
          </datalist>
        </div>
        <div class="field-row">
          <label>简历摘要 <span class="optional">（可选）</span></label>
          <BaseTextarea v-model="resumeText" :rows="4" placeholder="粘贴简历核心内容，AI 将据此定制题目" />
        </div>
        <div class="field-row">
          <label>题目数量</label>
          <div class="count-stepper">
            <button @click="count = Math.max(3, count - 1)" :disabled="count <= 3">−</button>
            <input class="count-input" type="number" v-model.number="count"
              min="3" max="10" @blur="count = Math.min(10, Math.max(3, count || 5))" />
            <button @click="count = Math.min(10, count + 1)" :disabled="count >= 10">+</button>
          </div>
          <div class="count-hint">建议 5 题，约 20-30 分钟（3-10 题）</div>
        </div>
      </div>
      <BaseButton variant="gradient" :loading="loading" :disabled="loading" @click="startInterview">
        {{ loading ? '正在准备…' : '开始面试' }}
      </BaseButton>
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
            <svg class="hint-icon" width="14" height="14" viewBox="0 0 24 24" fill="none">
              <path d="M9 21h6 M10 18h4 M12 2a7 7 0 0 0-4 12.7c.6.5 1 1.3 1 2.1V17h6v-.2c0-.8.4-1.6 1-2.1A7 7 0 0 0 12 2z"
                stroke="var(--brand-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>AI 实时提示</span>
            <span class="hint-arrow" :class="{ open: hintOpen }">▾</span>
          </button>
          <div v-if="hintOpen" class="hint-body">
            <div class="stream-box" v-html="streamHtml"></div>
            <div class="hint-actions">
              <BaseButton variant="ghost" size="sm" :loading="streaming" :disabled="streaming" @click="streamHint">
                {{ streaming ? '获取中…' : (streamContent ? '重新获取' : '获取 AI 提示') }}
              </BaseButton>
              <BaseButton v-if="streaming" variant="ghost" size="sm" @click="stopStream">停止</BaseButton>
            </div>
          </div>
        </div>

        <!-- 答题区 -->
        <div class="answer-section">
          <label>你的回答</label>
          <BaseTextarea v-model="userAnswer" :rows="6" placeholder="请输入你的回答，可结合项目经验展开…" />
          <div class="action-row">
            <BaseButton variant="gradient" :loading="evalLoading" :disabled="evalLoading" @click="submitAnswer">
              {{ evalLoading ? '评估中…' : '提交回答' }}
            </BaseButton>
            <BaseButton v-if="qIndex < questions.length - 1" variant="ghost" @click="nextQuestion">跳过本题</BaseButton>
            <BaseButton v-if="qIndex === questions.length - 1" variant="success" @click="finishSession">结束面试</BaseButton>
          </div>
        </div>
      </div>

      <!-- 评估结果卡片 -->
      <div v-if="evalResult" class="eval-card fade-in-up">
        <div class="eval-head">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path d="M3 3v18h18 M7 14l4-4 4 4 5-5" stroke="var(--brand-primary)" stroke-width="2"
              stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
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
          <div class="score-divider"></div>
          <div class="score-item">
            <div class="score-num" :style="{ color: scoreColor(evalResult.expression) }">
              {{ evalResult.expression ?? '-' }}
            </div>
            <div class="score-name">表达力</div>
          </div>
        </div>
        <div v-if="evalResult.improvements?.length" class="improve-list">
          <div class="improve-title">改进建议</div>
          <ul>
            <li v-for="(i, idx) in evalResult.improvements" :key="idx">{{ i }}</li>
          </ul>
        </div>
        <div class="action-row">
          <BaseButton v-if="qIndex < questions.length - 1" variant="gradient" @click="nextQuestion">下一题</BaseButton>
          <BaseButton v-else variant="success" @click="finishSession">结束面试</BaseButton>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { AI_TIMEOUT, getErrMessage, apiBaseUrl } from '../api'
import { authState, isTokenValid, clearAuth } from '../auth'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { BaseButton, BaseInput, BaseTextarea } from '../components'

// html: false 禁止 HTML 标签通过，linkify 自动识别链接
const md = new MarkdownIt({ html: false, linkify: true })

// DOMPurify 消毒：阻止 javascript: 协议等 XSS 向量
function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, { FORBID_TAGS: ['style', 'iframe'], FORBID_ATTR: ['onerror', 'onload'] })
}

interface Question {
  id?: number
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

const jobDesc = ref('')
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
const streamHtml = computed(() => sanitizeHtml(md.render(streamContent.value || '等待获取...')))

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
  if (loading.value) return // 防止重复点击
  loading.value = true
  let createdSessionId = ''
  try {
    // 1. 先创建会话（但不立即设置到响应式状态）
    const sess = await api.post('/api/session/create',
      { jobDescription: jobDesc.value }, { timeout: AI_TIMEOUT }) as unknown as { sessionId: string }
    createdSessionId = sess.sessionId

    // 2. 生成面试题
    const qs = await api.post('/api/interview/questions',
      { resumeText: resumeText.value || jobDesc.value, jobDescription: jobDesc.value, count: count.value },
      { timeout: AI_TIMEOUT }) as unknown as string

    // 3. 解析题目（失败时清理已创建的会话，防孤儿会话）
    const parsed = safeParse<Question[]>(qs, [])
    if (!parsed.length) {
      ElMessage.error('面试题生成失败，请检查岗位描述后重试')
      // 清理已创建的会话
      api.put(`/api/session/${createdSessionId}/finish`).catch(() => {})
      return
    }

    // 4. 持久化题目到后端（关联 sessionId），获取带 id 的题目列表
    //    失败不阻塞流程，仅记录日志（用户仍可在当前会话答题，仅历史回顾不可用）
    try {
      const saved = await api.post(`/api/session/${createdSessionId}/questions`,
        parsed, { timeout: AI_TIMEOUT }) as unknown as Question[]
      if (Array.isArray(saved) && saved.length === parsed.length) {
        // 用后端返回的带 id 题目替换，后续 saveAnswer 需要 questionId
        parsed.splice(0, parsed.length, ...saved)
      }
    } catch (persistErr) {
      console.warn('题目持久化失败，历史回顾将不可用：', persistErr)
    }

    // 5. 题目成功后设置状态，切换到面试页
    sessionId.value = createdSessionId
    questions.value = parsed
    ElMessage.success(`已生成 ${questions.value.length} 道题目，开始面试！`)
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '创建面试失败'))
    // 异常时也清理已创建的会话
    if (createdSessionId) {
      api.put(`/api/session/${createdSessionId}/finish`).catch(() => {})
    }
  } finally { loading.value = false }
}

async function streamHint() {
  if (!currentQ.value) return
  // 防重入：若上一次流仍在，先 abort 并等待退出
  if (streaming.value) {
    abortController?.abort()
    await new Promise(r => setTimeout(r, 50))
  }
  streaming.value = true
  streamContent.value = ''
  abortController = new AbortController()
  // 60s 超时兜底，防止 SSE 无限挂起
  const timeoutId = setTimeout(() => abortController?.abort(), 60000)
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null
  try {
    const token = authState.token
    if (!isTokenValid(token)) {
      ElMessage.error('登录已过期，请重新登录')
      clearAuth()
      return
    }
    const resp = await fetch(`${apiBaseUrl}/api/interview/ask/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({ question: currentQ.value.question }),
      signal: abortController.signal
    })
    if (!resp.ok) {
      if (resp.status === 401 || resp.status === 403) {
        ElMessage.error('登录已过期，请重新登录')
        clearAuth()
        if (window.location.pathname !== '/login') {
          window.location.href = '/login?redirect=' + encodeURIComponent(window.location.pathname)
        }
      } else {
        ElMessage.error(`AI 提示请求失败（HTTP ${resp.status}）`)
      }
      return
    }
    if (!resp.body) throw new Error('Response body is null')
    reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      let currentEvent = 'message'
      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (currentEvent === 'error') {
            ElMessage.error(data || 'AI 服务异常，请重试')
            await reader.cancel()
            return
          }
          if (currentEvent === 'done' || data === '[DONE]') {
            await reader.cancel()
            return
          }
          if (currentEvent === 'token' && data) {
            streamContent.value += data
          }
          // start 事件和 comment 行忽略
          currentEvent = 'message'
        }
      }
    }
  } catch (e: unknown) {
    if ((e as Error).name === 'AbortError') {
      // 用户主动取消或超时，静默
    } else if (e instanceof TypeError) {
      ElMessage.error('网络连接失败，请检查网络后重试')
    } else {
      ElMessage.error(getErrMessage(e, '流式请求失败'))
    }
  } finally {
    clearTimeout(timeoutId)
    if (reader) {
      try { await reader.cancel() } catch { /* 已关闭 */ }
    }
    streaming.value = false
    abortController = null
  }
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
    // 1. 评估回答（AI 返回评分 + 改进建议）
    const data = await api.post('/api/interview/evaluate', {
      question: currentQ.value.question,
      userAnswer: userAnswer.value
    }) as unknown as string
    evalResult.value = safeParse<EvalResult>(data, {})

    // 2. 持久化用户答案 + 评估分到后端（关联 questionId）
    //    失败不阻塞流程，仅记录日志（历史回顾会缺失本次答题记录）
    const questionId = currentQ.value.id
    if (questionId != null) {
      try {
        await api.post('/api/session/answer', {
          questionId,
          userAnswer: userAnswer.value,
          evaluationScore: evalResult.value.overallScore ?? null
        })
      } catch (persistErr) {
        console.warn('答案持久化失败，历史回顾将缺失本次记录：', persistErr)
      }
    }
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
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.12);
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

.count-input {
  width: 48px;
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  text-align: center;
  border: none;
  background: transparent;
  outline: none;
  -moz-appearance: textfield;
}
.count-input::-webkit-outer-spin-button,
.count-input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
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
  box-shadow: 0 4px 12px rgba(15, 118, 110, 0.25);
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(15, 118, 110, 0.35);
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
  border: 2px solid rgba(15, 118, 110, 0.3);
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
  background: rgba(15, 118, 110, 0.04);
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
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.12);
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
