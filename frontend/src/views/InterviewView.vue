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
            <option v-for="job in JOB_SUGGESTIONS" :key="job" :value="job" />
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
        <div class="progress-bar" role="progressbar" :aria-valuenow="progress" aria-valuemin="0" aria-valuemax="100">
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
          <button class="hint-toggle" :aria-expanded="hintOpen" aria-controls="hint-body" @click="hintOpen = !hintOpen">
            <svg class="hint-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M9 21h6 M10 18h4 M12 2a7 7 0 0 0-4 12.7c.6.5 1 1.3 1 2.1V17h6v-.2c0-.8.4-1.6 1-2.1A7 7 0 0 0 12 2z"
                stroke="var(--brand-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>AI 实时提示</span>
            <span class="hint-arrow" :class="{ open: hintOpen }">▾</span>
          </button>
          <div v-if="hintOpen" id="hint-body" class="hint-body">
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
          <BaseTextarea v-model="userAnswer" :rows="6" placeholder="请输入你的回答，可结合项目经验展开…（Ctrl+Enter 提交）"
            @keydown.ctrl.enter="submitAnswer" @keydown.meta.enter="submitAnswer" />
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
            <div class="score-num num-display">
              {{ evalResult.overallScore ?? '-' }}
            </div>
            <div class="score-name">综合</div>
          </div>
          <div class="score-divider"></div>
          <div class="score-item">
            <div class="score-num num-display">
              {{ evalResult.completeness ?? '-' }}
            </div>
            <div class="score-name">完整性</div>
          </div>
          <div class="score-divider"></div>
          <div class="score-item">
            <div class="score-num num-display">
              {{ evalResult.accuracy ?? '-' }}
            </div>
            <div class="score-name">准确性</div>
          </div>
          <div class="score-divider"></div>
          <div class="score-item">
            <div class="score-num num-display">
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

    <!-- Step 3: 面试复盘报告弹窗（Lollipop 式结构化复盘） -->
    <Teleport to="body">
      <Transition name="report-fade">
        <div v-if="reportOpen && answeredCount" class="report-mask" @click.self="closeReportGoSetup">
          <div class="report-modal" role="dialog" aria-modal="true" aria-label="面试复盘报告">
            <div class="report-head">
              <div>
                <h3 class="report-title">模拟面试复盘报告</h3>
                <p class="report-sub"><span class="report-star" aria-hidden="true">★</span> 基于本次 {{ answeredCount }} 道作答的结构化总结 · 灵感参考 AI 面试工具</p>
              </div>
              <button class="report-close" aria-label="关闭" @click="closeReportGoSetup">✕</button>
            </div>

            <!-- 综合得分 -->
            <div class="report-overall">
              <div class="overall-score" :style="{ color: scoreColor(reportAverages.overall) }">
                {{ reportAverages.overall }}
                <span class="overall-unit">分</span>
              </div>
              <div class="overall-summary">{{ reportSummary }}</div>
            </div>

            <!-- 四个维度 -->
            <div class="report-dims">
              <div v-for="d in [
                { name: '综合', v: reportAverages.overall },
                { name: '完整性', v: reportAverages.completeness },
                { name: '准确性', v: reportAverages.accuracy },
                { name: '表达力', v: reportAverages.expression }
              ]" :key="d.name" class="report-dim">
                <div class="dim-bar">
                  <div class="dim-fill" :style="{ width: Math.max(0, Math.min(100, d.v)) + '%', background: scoreColor(d.v) }"></div>
                </div>
                <div class="dim-row">
                  <span class="dim-name">{{ d.name }}</span>
                  <span class="dim-value" :style="{ color: scoreColor(d.v) }">{{ d.v }}</span>
                </div>
              </div>
            </div>

            <!-- 逐题得分回顾 -->
            <div class="report-block">
              <div class="report-block-title">逐题得分</div>
              <div class="report-questions">
                <div v-for="(e, idx) in sessionEvals" :key="idx" class="report-question">
                  <div class="rq-head">
                    <span class="rq-index">{{ idx + 1 }}</span>
                    <span class="rq-cat">{{ e.category }}</span>
                    <span class="rq-score" :style="{ color: scoreColor(e.overallScore) }">{{ e.overallScore }} 分</span>
                  </div>
                  <div class="rq-text">{{ e.question }}</div>
                </div>
              </div>
            </div>

            <!-- 高频改进建议 -->
            <div v-if="reportImprovements.length" class="report-block">
              <div class="report-block-title">建议提升的要点</div>
              <ul class="report-improve">
                <li v-for="(imp, idx) in reportImprovements" :key="idx">
                  <span class="imp-text">{{ imp.text }}</span>
                  <span v-if="imp.times > 1" class="imp-times">×{{ imp.times }}</span>
                </li>
              </ul>
            </div>

            <div class="report-actions">
              <BaseButton variant="ghost" @click="closeReportGoHistory">查看历史记录</BaseButton>
              <BaseButton variant="gradient" @click="closeReportGoSetup">完成，继续练习</BaseButton>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { AI_TIMEOUT, getErrMessage, apiBaseUrl } from '../api'
import { authState, isTokenValid, clearAuth } from '../auth'
import { JOB_SUGGESTIONS } from '../utils/jobOptions'
import renderMarkdown from '../utils/markdown'
import { BaseButton, BaseInput, BaseTextarea } from '../components'

const router = useRouter()

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

// ── 面试复盘报告（Lollipop 式结构化复盘）──
interface SessionEval {
  question: string
  category: string
  difficulty: string
  overallScore: number
  completeness: number
  accuracy: number
  expression: number
  improvements: string[]
}
const sessionEvals = ref<SessionEval[]>([])
const reportOpen = ref(false)

// AbortController 用于取消 SSE 流式请求
let abortController: AbortController | null = null

const currentQ = computed(() => questions.value[qIndex.value])
// 修复进度条：第一题不为 0%，最后一题能到 100%
const progress = computed(() =>
  Math.round(((qIndex.value + 1) / Math.max(questions.value.length, 1)) * 100)
)
const streamHtml = computed(() => renderMarkdown(streamContent.value || '等待获取...'))

// ── 复盘报告计算属性 ──
const answeredCount = computed(() => sessionEvals.value.length)
/** 各维度平均分 */
const reportAverages = computed(() => {
  const list = sessionEvals.value
  const n = list.length
  if (!n) return { overall: 0, completeness: 0, accuracy: 0, expression: 0 }
  const mean = (k: keyof SessionEval) => Math.round(list.reduce((a, e) => a + (e[k] as number || 0), 0) / n)
  return { overall: mean('overallScore'), completeness: mean('completeness'), accuracy: mean('accuracy'), expression: mean('expression') }
})
/** 汇总的待改进点：按出现次数排序、去重展示 */
const reportImprovements = computed(() => {
  const freq = new Map<string, number>()
  for (const e of sessionEvals.value) {
    for (const imp of e.improvements || []) {
      const k = imp.trim()
      if (k) freq.set(k, (freq.get(k) || 0) + 1)
    }
  }
  return [...freq.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([text, cnt]) => ({ text, times: cnt }))
})
/** 基于均分的综合评价文案（智能生成，非硬编码 AI 调用，稳定可靠） */
const reportSummary = computed(() => {
  const { overall, completeness, accuracy, expression } = reportAverages.value
  const dims = [
    { name: '完整性', v: completeness },
    { name: '准确性', v: accuracy },
    { name: '表达力', v: expression }
  ].sort((x, y) => y.v - x.v)
  const weakest = dims[dims.length - 1]
  const strongest = dims[0]
  const level = overall >= 85 ? '优秀' : overall >= 70 ? '良好' : overall >= 60 ? '合格' : '待加强'
  return `本轮共回答 ${answeredCount.value} 题，综合得分 ${overall} 分（${level}）。你的${strongest.name}是相对优势，建议继续保持；${weakest.name}是当前短板，可针对性多加练习。针对短板高频改进点，在下一次练习时有意识地调整，稳扎稳打即可稳步提升。`
})

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

    // 收集本次回答的评估结果，供面试结束后的综合复盘报告使用
    if (evalResult.value && typeof evalResult.value.overallScore === 'number') {
      sessionEvals.value.push({
        question: currentQ.value.question,
        category: currentQ.value.category,
        difficulty: currentQ.value.difficulty,
        overallScore: evalResult.value.overallScore,
        completeness: evalResult.value.completeness ?? 0,
        accuracy: evalResult.value.accuracy ?? 0,
        expression: evalResult.value.expression ?? 0,
        improvements: evalResult.value.improvements ?? []
      })
    }

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
    const hasReport = sessionEvals.value.length > 0
    // 有作答记录则弹出综合复盘报告，否则仅提示完成
    if (hasReport) {
      reportOpen.value = true
    } else {
      ElMessage.success('面试结束，结果已保存！')
    }
    resetSession()
  } catch (e: unknown) {
    // 失败时提供"重试"与"强制退出"两个选项，避免用户卡死
    try {
      await ElMessageBox.confirm(
        getErrMessage(e, '结束面试失败') + '。是否强制退出当前面试？（后端记录可能未保存）',
        '结束失败',
        { confirmButtonText: '强制退出', cancelButtonText: '重试', type: 'warning' }
      )
      // 用户选择强制退出，清本地状态
      resetSession()
    } catch {
      // 用户选择重试，不清理状态
    }
  }
}

/** 清空本地面试状态，进入报名准备页 */
function resetSession() {
  sessionId.value = ''
  qIndex.value = 0
  questions.value = []
  userAnswer.value = ''
  evalResult.value = null
  streamContent.value = ''
}

/** 关闭报告并回到面试准备页（报告内容仍保留在本次会话内，可再次打开直到离开页面） */
function closeReportGoSetup() {
  reportOpen.value = false
}

/** 关闭报告并跳转历史回顾 */
function closeReportGoHistory() {
  reportOpen.value = false
  router.push('/history')
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
  background: var(--brand-primary-50);
  border-radius: 999px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--brand-primary);
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

.stream-box :deep(h1),
.stream-box :deep(h2),
.stream-box :deep(h3),
.stream-box :deep(h4) {
  font-family: var(--font-sans);
  line-height: 1.4;
  font-weight: 700;
  color: var(--c-text);
  margin: 12px 0 6px;
}

.stream-box :deep(h1) { font-size: 17px; }
.stream-box :deep(h2) { font-size: 16px; padding-bottom: 4px; border-bottom: 1px solid var(--c-border-light); }
.stream-box :deep(h3) { font-size: 15px; }
.stream-box :deep(h4) { font-size: 14px; }

.stream-box :deep(> h1:first-child),
.stream-box :deep(> h2:first-child),
.stream-box :deep(> h3:first-child) {
  margin-top: 0;
}

.stream-box :deep(strong) {
  font-weight: 650;
  color: var(--c-text);
}

.stream-box :deep(ul),
.stream-box :deep(ol) {
  margin: 4px 0 8px;
  padding-left: 20px;
}

.stream-box :deep(li) {
  margin: 2px 0;
}

.stream-box :deep(li) > ul,
.stream-box :deep(li) > ol {
  margin: 2px 0;
}

.stream-box :deep(blockquote) {
  margin: 6px 0 10px;
  padding: 2px 12px;
  border-left: 3px solid var(--c-primary-soft);
  color: var(--c-text-secondary);
}

.stream-box :deep(code) {
  background: var(--c-bg-alt);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 13px;
}

.stream-box :deep(pre) {
  background: var(--c-bg-alt);
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  overflow-x: auto;
  margin: 6px 0 10px;
}

.stream-box :deep(pre code) {
  background: transparent;
  padding: 0;
  font-size: 13px;
}

.stream-box :deep(hr) {
  border: none;
  border-top: 1px solid var(--c-border-light);
  margin: 10px 0;
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
  background: var(--c-accent-soft);
  border-left: 3px solid var(--c-accent);
  padding: 14px 18px;
  border-radius: var(--radius-sm);
  margin-bottom: 20px;
}

.improve-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-accent-hover);
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

<!-- 复盘报告弹窗被 Teleport 到 body，需非 scoped 样式 -->
<style>
.report-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  backdrop-filter: blur(2px);
}
.report-modal {
  width: 720px;
  max-width: 100%;
  max-height: 88vh;
  overflow-y: auto;
  background: var(--c-surface);
  border-radius: var(--radius-lg);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.25);
  padding: 28px 30px;
  font-family: var(--font-sans);
}
.report-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.report-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 4px;
  letter-spacing: -0.4px;
}
.report-sub {
  font-size: 12.5px;
  color: var(--c-text-tertiary);
  margin: 0;
}
.report-star {
  color: var(--c-accent);
  margin-right: 3px;
}
.report-close {
  border: none;
  background: var(--c-bg-alt);
  color: var(--c-text-secondary);
  width: 32px;
  height: 32px;
  border-radius: 999px;
  font-size: 14px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all var(--transition-fast);
}
.report-close:hover {
  background: var(--c-border);
  color: var(--c-text);
}
.report-overall {
  display: flex;
  align-items: center;
  gap: 22px;
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  padding: 20px 22px;
  margin-bottom: 18px;
}
.overall-score {
  font-size: 52px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -2px;
  min-width: 96px;
  text-align: center;
}
.overall-unit {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text-tertiary);
  margin-left: 2px;
}
.overall-summary {
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--c-text-secondary);
}
.report-dims {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}
.report-dim {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  padding: 14px;
}
.dim-bar {
  height: 6px;
  background: var(--brand-primary-50);
  border-radius: 999px;
  overflow: hidden;
  margin-bottom: 10px;
}
.dim-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.6s ease;
}
.dim-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.dim-name {
  font-size: 12.5px;
  color: var(--c-text-secondary);
  font-weight: 500;
}
.dim-value {
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
}
.report-block {
  margin-bottom: 20px;
}
.report-block-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 10px;
}
.report-questions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 200px;
  overflow-y: auto;
  padding-right: 4px;
}
.report-question {
  background: var(--c-bg-alt);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
}
.rq-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.rq-index {
  width: 20px;
  height: 20px;
  border-radius: 999px;
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  font-size: 12px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.rq-cat {
  font-size: 12px;
  color: var(--c-text-tertiary);
}
.rq-score {
  margin-left: auto;
  font-size: 13px;
  font-weight: 700;
}
.rq-text {
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.6;
}
.report-improve {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.report-improve li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  background: var(--c-accent-soft);
  border-left: 3px solid var(--c-accent);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.6;
}
.imp-times {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-warning);
}
.report-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 6px;
}
.report-fade-enter-active,
.report-fade-leave-active {
  transition: opacity 0.25s ease;
}
.report-fade-enter-from,
.report-fade-leave-to {
  opacity: 0;
}
@media (max-width: 640px) {
  .report-modal {
    padding: 20px;
  }
  .report-overall {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .report-dims {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
