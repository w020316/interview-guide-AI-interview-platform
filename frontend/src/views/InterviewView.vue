<template>
  <div>
    <h2>🤖 AI 模拟面试</h2>

    <!-- Step 1: 创建会话 -->
    <el-card v-if="!sessionId" style="max-width:600px">
      <el-form label-width="100px">
        <el-form-item label="目标岗位">
          <el-input v-model="jobDesc" placeholder="Java 后端开发工程师" />
        </el-form-item>
        <el-form-item label="简历摘要">
          <el-input v-model="resumeText" type="textarea" :rows="4" placeholder="粘贴简历核心内容（可选）" />
        </el-form-item>
        <el-form-item label="题目数量">
          <el-input-number v-model="count" :min="3" :max="10" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="startInterview">开始面试</el-button>
      </el-form>
    </el-card>

    <!-- Step 2: 面试进行中 -->
    <div v-else>
      <el-progress :percentage="progress" :stroke-width="12" style="margin-bottom:20px" />

      <el-card v-if="currentQ" class="question-card">
        <div class="q-meta">
          <el-tag>{{ currentQ.category }}</el-tag>
          <el-tag :type="diffColor(currentQ.difficulty)">{{ currentQ.difficulty }}</el-tag>
          <span style="color:#999;margin-left:8px">第 {{ qIndex+1 }} / {{ questions.length }} 题</span>
        </div>
        <h3 style="margin:16px 0">{{ currentQ.question }}</h3>

        <!-- SSE 流式 AI 提示 -->
        <el-collapse style="margin-bottom:16px">
          <el-collapse-item title="💡 AI 实时提示（流式）">
            <div class="stream-box" v-html="streamHtml" />
            <el-button size="small" @click="streamHint" :loading="streaming">获取 AI 提示</el-button>
            <el-button size="small" v-if="streaming" @click="stopStream">停止</el-button>
          </el-collapse-item>
        </el-collapse>

        <el-input v-model="userAnswer" type="textarea" :rows="6" placeholder="请输入你的回答..." />
        <div style="margin-top:12px;display:flex;gap:8px">
          <el-button type="primary" :loading="evalLoading" @click="submitAnswer">提交回答</el-button>
          <el-button @click="nextQuestion" v-if="qIndex < questions.length-1">跳过</el-button>
          <el-button type="success" @click="finishSession" v-if="qIndex === questions.length-1">结束面试</el-button>
        </div>
      </el-card>

      <el-card v-if="evalResult" class="eval-card">
        <h4>📊 AI 评估</h4>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="综合">{{ evalResult.overallScore ?? '-' }}分</el-descriptions-item>
          <el-descriptions-item label="完整性">{{ evalResult.completeness ?? '-' }}分</el-descriptions-item>
          <el-descriptions-item label="准确性">{{ evalResult.accuracy ?? '-' }}分</el-descriptions-item>
        </el-descriptions>
        <ul v-if="evalResult.improvements?.length" style="margin-top:12px">
          <li v-for="(i, idx) in evalResult.improvements" :key="idx" style="color:#e6a23c">{{ i }}</li>
        </ul>
        <el-button type="primary" style="margin-top:12px" @click="nextQuestion"
          v-if="qIndex < questions.length-1">下一题</el-button>
        <el-button type="success" style="margin-top:12px" @click="finishSession"
          v-else>结束面试</el-button>
      </el-card>
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

// AbortController 用于取消 SSE 流式请求
let abortController: AbortController | null = null

const currentQ = computed(() => questions.value[qIndex.value])
// 修复进度条：第一题不为 0%，最后一题能到 100%
const progress = computed(() =>
  Math.round(((qIndex.value + 1) / Math.max(questions.value.length, 1)) * 100)
)
const streamHtml = computed(() => md.render(streamContent.value || '等待获取...'))

function diffColor(d: string) {
  return d === 'HARD' ? 'danger' : d === 'MEDIUM' ? 'warning' : 'success'
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
.question-card { margin-bottom:16px; }
.q-meta { display:flex; gap:8px; flex-wrap:wrap; }
.stream-box { font-size:14px; line-height:1.6; max-height:200px; overflow-y:auto;
  background:#f8f8f8; padding:8px; border-radius:4px; margin-bottom:8px; }
.eval-card { background:#fdf6ec; }
</style>
