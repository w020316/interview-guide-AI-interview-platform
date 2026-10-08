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
        <div class="field-row">
          <label>难度偏好 <span class="optional">（自适应按历史成绩）</span></label>
          <div class="difficulty-pref">
            <button v-for="o in DIFF_OPTIONS" :key="o.value" type="button"
              class="pref-btn" :class="{ active: difficultyPref === o.value }"
              @click="difficultyPref = o.value">
              {{ o.label }}
            </button>
          </div>
        </div>
        <!-- 错题本带入聚焦项时的提示 -->
        <Transition name="fade">
          <div v-if="focusNote" class="focus-note" role="status">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M12 3a4 4 0 0 1 4 4c0 2-4 4-4 6m0 8h.01M12 19v-6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
            <span>{{ focusNote }}</span>
          </div>
        </Transition>
      </div>
      <BaseButton variant="gradient" :loading="loading" :disabled="loading" @click="startInterview">
        {{ loading ? '正在准备…' : '开始面试' }}
      </BaseButton>

      <!-- 生成分步进度（v1.23.2 优化①：消除 2-3 分钟生成的"黑盒等待"） -->
      <div v-if="loading" class="gen-progress" role="status" aria-live="polite">
        <div class="gen-steps">
          <div v-for="(s, i) in GEN_STEPS" :key="s" class="gen-step"
            :class="{ active: genStep === i + 1, done: genStep > i + 1 }">
            <span class="gen-dot">
              <svg v-if="genStep > i + 1" width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M20 6L9 17l-5-5" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span v-else class="gen-dot-inner" :class="{ pulse: genStep === i + 1 }"></span>
            </span>
            <span class="gen-label">{{ s }}</span>
            <span v-if="genStep === i + 1" class="gen-elapsed">{{ genElapsed }}s</span>
          </div>
        </div>
        <div class="gen-bar" aria-hidden="true">
          <div class="gen-bar-fill" :style="{ width: genProgress + '%' }"></div>
        </div>
        <p class="gen-tip">题目由 AI 生成，通常需要 2-3 分钟，请保持页面打开，完成后自动进入答题</p>
      </div>
    </div>

    <!-- Step 2: 面试进行中 -->
    <div v-else class="interview-session fade-in">
      <!-- 进度条 -->
      <div class="progress-wrap">
        <!-- v1.44.0：显示本场面试的目标岗位。
             此前从「面试历史」继续面试时，整页找不到「我在面什么岗位」——
             数据其实早在 jobDesc 里（resumeSession 已回填），只是模板没渲染。 -->
        <p v-if="jobDesc.trim()" class="session-job">
          <span class="session-job-label">目标岗位</span>
          <span class="session-job-name">{{ jobDesc.trim() }}</span>
        </p>
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
              <BaseButton variant="ghost" size="sm" :loading="streaming" :disabled="streaming" @click="startHint">
                {{ streaming ? '获取中…' : (streamContent ? '重新获取' : '获取 AI 提示') }}
              </BaseButton>
              <BaseButton v-if="streaming" variant="ghost" size="sm" @click="stopStream">停止</BaseButton>
            </div>
          </div>
        </div>

        <!-- 答题区 -->
        <div class="answer-section">
          <label>你的回答</label>
          <!-- 语音作答入口 -->
          <div v-if="speechSupported" class="speech-bar">
            <BaseButton variant="ghost" size="sm" :class="{ 'is-recording': speechRecording }"
              :loading="speechRecording" @click="toggleSpeech">
              <svg v-if="!speechRecording" width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 2a3 3 0 0 1 3 3v6a3 3 0 1 1-6 0V5a3 3 0 0 1 3-3z M19 10v1a7 7 0 0 1-14 0v-1 M12 18.5V21" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span v-else class="rec-dot" aria-hidden="true"></span>
              {{ speechRecording ? '语音识别中… 再点一下结束' : '语音作答' }}
            </BaseButton>
            <span v-if="speechFeedback" class="speech-feedback">{{ speechFeedback }}</span>
          </div>
          <div v-else class="speech-bar speech-unsupported">
            <span>当前浏览器不支持语音识别，请使用 Chrome/Edge 开启语音作答与表达分析</span>
          </div>
          <BaseTextarea v-model="userAnswer" :rows="6" placeholder="请输入你的回答，可结合项目经验展开…（Ctrl+Enter 提交，或用上方语音作答）"
            @keydown.ctrl.enter="submitAnswer" @keydown.meta.enter="submitAnswer" />

          <!-- 多模态附图（v1.30.0）：上传代码截图/白板草图/证书，AI 结合图片评估 -->
          <div class="attach-bar">
            <BaseButton variant="ghost" size="sm" :loading="imageUploading" :disabled="imageUploading" @click="pickImageFile">
              <svg v-if="!imageUploading" width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <rect x="3" y="4" width="18" height="16" rx="2" stroke="currentColor" stroke-width="2"/>
                <circle cx="9" cy="10" r="2" stroke="currentColor" stroke-width="2"/>
                <path d="M21 16l-4-4-7 7M11 19l3-3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
              <span v-else class="rec-dot" aria-hidden="true"></span>
              {{ imageUrl ? '重新上传截图' : (imageUploading ? '上传中…' : '上传附图（截图/草图/证书）') }}
            </BaseButton>
            <input ref="imageInputEl" type="file" accept="image/*" hidden @change="onImageSelected" />
            <span v-if="imageUrl" class="attach-hint">已附加 1 张图片</span>
            <template v-if="imageUrl">
              <img :src="imageSignedUrl" class="attach-preview" alt="作答附图" />
              <button class="attach-remove" aria-label="移除附图" :title="'移除附图'" @click="removeImage">×</button>
            </template>
          </div>

          <div class="action-row">
            <BaseButton variant="gradient" :loading="evalLoading" :disabled="evalLoading" @click="submitAnswer">
              {{ evalLoading ? '评估中…' : '提交回答' }}
            </BaseButton>
            <BaseButton v-if="qIndex < questions.length - 1" variant="ghost" @click="nextQuestion">跳过本题</BaseButton>
            <BaseButton v-if="qIndex === questions.length - 1" variant="success" @click="finishSession">结束面试</BaseButton>
          </div>

          <!-- 评分等待反馈（P2-21）：让用户看得出是在评估，而不是卡死了 -->
          <div v-if="evalLoading" class="eval-waiting" role="status" aria-live="polite">
            <span class="eval-waiting-dot" aria-hidden="true"></span>
            <span class="eval-waiting-text">
              {{ evalLongWait ? 'AI 正在深度评估你的回答，请稍候…' : 'AI 正在评估你的回答…' }}
            </span>
            <span class="eval-waiting-elapsed">已用 {{ evalElapsed }}s</span>
          </div>
          <p v-if="evalLoading && evalLongWait" class="eval-waiting-hint">
            评分通常需要 10–30 秒，偶尔会更久。请勿刷新页面，以免丢失当前答题进度。
          </p>
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
              {{ evalResult.overallScore ?? EMPTY }}
            </div>
            <div class="score-name">综合</div>
          </div>
          <div class="score-divider"></div>
          <div class="score-item">
            <div class="score-num num-display">
              {{ evalResult.completeness ?? EMPTY }}
            </div>
            <div class="score-name">完整性</div>
          </div>
          <div class="score-divider"></div>
          <div class="score-item">
            <div class="score-num num-display">
              {{ evalResult.accuracy ?? EMPTY }}
            </div>
            <div class="score-name">准确性</div>
          </div>
          <div class="score-divider"></div>
          <div class="score-item">
            <div class="score-num num-display">
              {{ evalResult.expression ?? EMPTY }}
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
          <BaseButton variant="ghost" :loading="followingUp" :disabled="followingUp" @click="deepFollowUp">深挖提问</BaseButton>
          <BaseButton v-if="qIndex < questions.length - 1" variant="gradient" @click="nextQuestion">下一题</BaseButton>
          <BaseButton v-else variant="success" @click="finishSession">结束面试</BaseButton>
        </div>
      </div>
    </div>

    <!-- Step 3: 面试复盘报告弹窗（第三批 A：抽出 ReportPanel，供历史回看复用） -->
    <Teleport to="body">
      <Transition name="report-fade">
        <!-- P2-29：遮罩不再关闭报告。
             复盘报告是一次面试的核心产出物，而 reportOpen 全仓只有 finishSession 一处置真、
             resetSession 又已清空 questions/sessionId —— 一旦误点遮罩就永久失去查看入口，
             用户只能重做一场面试。关闭动作收敛到右上角 ✕ 与底部两个明确按钮。 -->
        <ReportPanel
          v-if="reportOpen && answeredCount"
          :evals="sessionEvals"
          :job-title="reportJobTitle"
          :scope-text="reportScopeText"
          :compare="reportCompareView"
          :compare-loaded="historyCompareLoaded"
          :sharing="sharing"
          @close="closeReportGoSetup"
          @go-history="closeReportGoHistory"
          @export-pdf="exportPdf"
          @share="shareCard"
        />
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { AI_TIMEOUT, getErrMessage, apiBaseUrl } from '../api'
import { authState, isTokenValid, clearAuth } from '../auth'
import { JOB_SUGGESTIONS } from '../utils/jobOptions'
import renderMarkdown from '../utils/markdown'
import { createSpeechRecorder, isSpeechSupported } from '../utils/speech'
import { compareWithHistory, suggestNextTarget } from '../utils/reportCompare'
import { nextGenProgress } from '../utils/genProgress'
import { EMPTY } from '../utils/format'
import { buildReportView, buildScopeText, findDim, type ReportCompareView, type ReportEvalInput } from '../utils/reportView'
import { BaseButton, BaseInput, BaseTextarea } from '../components'
import ReportPanel from '../components/ReportPanel.vue'

const router = useRouter()
const route = useRoute()

/**
 * 从错题本等入口带入的聚焦参数：
 * - focus：需重点考察的薄弱分类（逗号分隔），作为本次出题的 focusCategories
 * - job：预填目标岗位
 * - count：预填题目数量
 * - difficulty：预填难度偏好
 * 仅在携带 query 时生效；直接进入面试页时为空，走历史自适应逻辑。
 */
const FOCUS_OVERRIDE = typeof route.query.focus === 'string' ? route.query.focus.trim() : ''
const routeJob = typeof route.query.job === 'string' ? route.query.job : ''
const routeCount = typeof route.query.count === 'string' ? Number(route.query.count) : NaN
const routeDiff = typeof route.query.difficulty === 'string' ? route.query.difficulty : ''

/** 难度偏好选项：''=自适应（按历史成绩），其余向对应难度倾斜 */
const DIFF_OPTIONS = [
  { value: '', label: '自适应' },
  { value: 'EASY', label: '打基础' },
  { value: 'MEDIUM', label: '稳中有升' },
  { value: 'HARD', label: '挑战自我' },
]

interface Question {
  id?: number
  question: string
  category: string
  difficulty: string
  referenceAnswer?: string
  /**
   * 后端返回字段：已持久化的用户回答与评分（P2-26 用于统计会话累计作答量）。
   * 此前接口未声明这两个字段，导致「从历史恢复时该会话已有几题作答」这一信息在前端不可见。
   */
  userAnswer?: string | null
  evaluationScore?: number | null
}

interface EvalResult {
  overallScore?: number
  completeness?: number
  accuracy?: number
  expression?: number
  improvements?: string[]
}

const jobDesc = ref(routeJob)
const resumeText = ref('')

// 简历分析页"带着这份简历去模拟面试"带入的简历摘要（v1.23.2 优化③，读取后即清除）
const PREFILL_RESUME_KEY = 'interview_prefill_resume'
try {
  const prefillResume = sessionStorage.getItem(PREFILL_RESUME_KEY)
  if (prefillResume) {
    resumeText.value = prefillResume
    sessionStorage.removeItem(PREFILL_RESUME_KEY)
  }
} catch {
  /* sessionStorage 不可用时忽略 */
}

const count = ref(!Number.isNaN(routeCount) ? Math.min(10, Math.max(3, routeCount)) : 5)
const difficultyPref = ref(routeDiff && DIFF_OPTIONS.some((o) => o.value === routeDiff) ? routeDiff : '')
const loading = ref(false)

// ── 生成分步进度（v1.23.2 优化①）──
/** 生成流程各阶段文案，genStep 取值 1-4 对应数组下标 0-3 */
const GEN_STEPS = ['连接后端服务', '分析历史成绩', 'AI 生成题目（约 2-3 分钟）', '保存题目'] as const
const genStep = ref(0)
const genElapsed = ref(0)
/** AI 生成阶段的感知进度条：渐近逼近 95%，完成后置 100 */
const genProgress = ref(0)
let genTimer: ReturnType<typeof setInterval> | null = null

function startGenProgress() {
  genStep.value = 1
  genElapsed.value = 0
  genProgress.value = 4
  if (genTimer) clearInterval(genTimer)
  genTimer = setInterval(() => {
    genElapsed.value++
    // AI 生成阶段（step 3）驱动感知进度，渐近 95% 避免提前到 100
    if (genStep.value >= 3) {
      genProgress.value = nextGenProgress(genProgress.value)
    }
  }, 1000)
}

function stopGenProgress(success = false) {
  if (genTimer) {
    clearInterval(genTimer)
    genTimer = null
  }
  if (success) {
    genStep.value = GEN_STEPS.length + 1 // 全部打勾
    genProgress.value = 100
  }
}

/** 由错题本带入聚焦项时的提示文案 */
const focusNote = computed(() => FOCUS_OVERRIDE ? `已聚焦薄弱分类：${FOCUS_OVERRIDE}` : '')
const sessionId = ref('')
const questions = ref<Question[]>([])
const qIndex = ref(0)
const userAnswer = ref('')
const evalLoading = ref(false)
const evalResult = ref<EvalResult | null>(null)

// ── 评分等待反馈（P2-21）──
/**
 * 评分耗时实测 11s ~ 71s（同一题三次独立评分也有 1.4x 波动），而此前界面上只有
 * 按钮 loading，用户无法区分「AI 正在评估」与「已经卡死」；长尾场景下用户会刷新页面，
 * 反而丢失当前答题进度。这里给出已用时长与长等待文案。
 */
const evalElapsed = ref(0)
let evalTimer: ReturnType<typeof setInterval> | null = null
/** 超过该秒数后切换为「深度评估」文案 */
const EVAL_LONG_WAIT_SEC = 15
const evalLongWait = computed(() => evalElapsed.value >= EVAL_LONG_WAIT_SEC)

function startEvalTimer() {
  evalElapsed.value = 0
  if (evalTimer) clearInterval(evalTimer)
  evalTimer = setInterval(() => { evalElapsed.value++ }, 1000)
}

function stopEvalTimer() {
  if (evalTimer) {
    clearInterval(evalTimer)
    evalTimer = null
  }
  evalElapsed.value = 0
}
const streaming = ref(false)
const streamContent = ref('')
const hintOpen = ref(false)

// ── 多模态附图（v1.30.0）──
const imageInputEl = ref<HTMLInputElement | null>(null)
const imageUrl = ref('')
// P2-06：预览专用签名 URL（bucket 转私有后公开 URL 无法直接展示）
const imageSignedUrl = ref('')
const imageUploading = ref(false)
function pickImageFile() {
  imageInputEl.value?.click()
}
async function onImageSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 允许重复选择同一文件
  if (!file) return
  if (!file.type.startsWith('image/')) return ElMessage.warning('请选择图片文件')
  if (file.size > 5 * 1024 * 1024) return ElMessage.warning('图片不能超过 5MB')
  imageUploading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    const res = await api.post('/api/interview/upload-image', form) as unknown as { url?: string; signedUrl?: string }
    if (!res?.url) throw new Error('未返回图片地址')
    imageUrl.value = res.url
    // P2-06：bucket 转私有后公开 URL 无法直接预览，优先使用后端签发的短时效签名 URL 展示
    imageSignedUrl.value = res.signedUrl || res.url
    ElMessage.success('附图已上传，提交后 AI 将结合图片评估')
  } catch (err: unknown) {
    ElMessage.error(getErrMessage(err, '图片上传失败'))
  } finally {
    imageUploading.value = false
  }
}
function removeImage() {
  imageUrl.value = ''
  imageSignedUrl.value = ''
}

// 通过 ?sessionId= 载入指定会话（从收藏题库发起面试等入口进入时直接答题）
onMounted(() => {
  const sid = typeof route.query.sessionId === 'string' ? route.query.sessionId : ''
  if (sid) {
    resumeSession(sid)
    // 载入后清理 query 中的 sessionId，避免刷新重复触发
    const q = { ...route.query }
    delete q.sessionId
    router.replace({ path: '/interview', query: q }).catch(() => {})
  }
})

/** 载入一个已存在会话的题目集并进入答题（不复用旧回答，从头作答） */
async function resumeSession(targetId: string) {
  loading.value = true
  try {
    // P3-27：同时取会话元信息回填岗位描述。此前只取题目数组（该接口不含 jobDescription），
    // 于是 jobDesc 保持空串，复盘报告副题 / 导出 PDF 页眉 / 分享卡片主标题与文件名
    // 四处都会显示「未指定岗位」。
    const [qs, meta] = await Promise.all([
      api.get(`/api/session/${targetId}/questions`, { timeout: AI_TIMEOUT }) as unknown as Question[],
      (api.get(`/api/session/${targetId}`, { timeout: AI_TIMEOUT }) as unknown as Promise<{ jobDescription?: string }>)
        .catch(() => null)
    ])
    if (!Array.isArray(qs) || qs.length === 0) {
      ElMessage.warning('该会话暂无题目')
      router.replace('/interview')
      return
    }
    sessionId.value = targetId
    questions.value = qs
    qIndex.value = 0
    if (meta?.jobDescription) {
      jobDesc.value = meta.jobDescription
    }
    // P2-26：记录该会话「此前已作答」的题数。复盘报告的 sessionEvals 是页面级状态，
    // 只统计本次新作答，若不把会话累计数一并展示，报告会显得「丢了题」
    //（实测：服务端 4 题、其中 2 题早有得分，报告却写「基于本次 1 道作答」）。
    historyAnsweredCount.value = qs.filter((q) => typeof q.evaluationScore === 'number').length
    ElMessage.success(`已载入 ${qs.length} 道题，开始面试！`)
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '载入面试失败'))
    router.replace('/interview')
  } finally {
    loading.value = false
  }
}

// ── 语音作答（ASR）──
const speechSupported = isSpeechSupported()
const speechRecording = ref(false)
const speechFeedback = ref('')
// 识别器仅在打开语音时按需创建，避免占用麦克风资源
let speechRec: ReturnType<typeof createSpeechRecorder> | null = null

// ── 面试复盘报告（第三批 A：视图模型统一由 utils/reportView.ts 纯函数派生）──
/**
 * 本次页面内新作答的题目（含综合分与维度明细）。
 * 维度明细以 `evalDetail` 原始对象承载——**不在此处 `?? 0` 兜底**，
 * 缺失交由 {@link buildReportView} 判定降级（RK1「无数据 ≠ 0」）。
 */
const sessionEvals = ref<ReportEvalInput[]>([])
/** P1-18：题目缺 id 导致答案无法上报时，每次会话只提示一次，避免逐题刷屏 */
const persistWarned = ref(false)
/**
 * P2-26：从历史会话恢复时，该会话「此前已作答」的题数。
 * sessionEvals 是页面级状态，只统计本次新作答；不复用服务端既有得分的前提下，
 * 至少在报告抬头如实体现会话累计量，避免出现「服务端 4 题、报告说 1 题」的割裂。
 */
const historyAnsweredCount = ref(0)
const reportOpen = ref(false)

// ── 复盘报告进阶：多轮成绩对比 ──
/** 历史各场综合得分（不含本轮，从 trend 过滤当前会话） */
const historyScores = ref<number[]>([])
const historyCompareLoaded = ref(false)
/**
 * P2-20：历史对比必须区分三种状态，此前三者被压成同一个「首次」——
 * ① 真的没有历史会话；② 有历史会话但 trend 返回空（数据异常）；③ 加载失败。
 * ②③ 都会让有历史的老用户看到与事实完全相反的「你是首次」。
 */
const compareFailed = ref(false)
const hasPastSessions = ref(false)
/** 下一轮目标分（无综合分时按 0 起步，仅影响建议值） */
const nextTarget = computed(() => suggestNextTarget(reportView.value.overall ?? 0))
/** 与历史对比结论（含 P2-20 的三态修正） */
const reportCompare = computed(() => {
  const base = compareWithHistory(reportView.value.overall ?? 0, historyScores.value)
  if (compareFailed.value) {
    return { ...base, hint: '历史成绩加载失败，本次未参与对比。可稍后重开报告重试。' }
  }
  if (base.status === 'unknown' && hasPastSessions.value) {
    return { ...base, hint: '历史数据暂不可用（未能读取既有会话的成绩），本次未参与对比。' }
  }
  return base
})
/** 对比状态的中文标签 */
const compareStatusLabel = computed(() => {
  // P2-20：加载失败时不再谎称「首次」
  if (compareFailed.value) return '数据异常'
  switch (reportCompare.value.status) {
    case 'improved': return '进步'
    case 'declined': return '待加强'
    case 'steady': return '持平'
    default: return '首次'
  }
})
/** 传给 ReportPanel 的对比视图（仅在历史对比加载完成后提供） */
const reportCompareView = computed<ReportCompareView | null>(() => {
  if (!historyCompareLoaded.value) return null
  return {
    status: reportCompare.value.status,
    label: compareStatusLabel.value,
    hint: reportCompare.value.hint,
    nextTarget: nextTarget.value,
  }
})

// AbortController 用于取消 SSE 流式请求
let abortController: AbortController | null = null
// 冷启动重试标记：AI 提示流在 Render 冷启动网络断开时，唤醒后端后自动重试一次
let hintColdRetryCount = 0

const currentQ = computed(() => questions.value[qIndex.value])
// 修复进度条：第一题不为 0%，最后一题能到 100%
const progress = computed(() =>
  Math.round(((qIndex.value + 1) / Math.max(questions.value.length, 1)) * 100)
)
const streamHtml = computed(() => renderMarkdown(streamContent.value || '等待获取...'))

// ── 复盘报告计算属性 ──
const answeredCount = computed(() => sessionEvals.value.length)

/**
 * 报告覆盖范围说明（P3-03 口径修正）。
 *
 * <p>旧的单一口径（「基于本次 N 道作答」）把「本场总题数 / 本次新作答 / 历史已答」三件事
 * 压成一句话，从历史恢复作答时尤其误导。现改为三段式：
 * 「本场共 N 题 · 本次作答 X · 历史已答 Y」。
 *
 * <p>用快照 ref 承载：`finishSession()` 会在 `resetSession()` **之前**求值写入，
 * 避免被清空的 questions / historyAnsweredCount 让报告抬头显示成 0。
 */
const reportScopeText = ref('')

/**
 * 复盘报告抬头里的岗位名（v1.44.0）。
 *
 * <p>取不到时**不渲染**，而不是显示「未指定岗位」——报告是要转发给别人的产物，
 * 与其印一行占位文案，不如不占这一行。（分享卡片/导出 PDF 有文件名等约束，
 * 仍保留 `|| '未指定岗位'` 的兜底，两者取舍不同。）
 */
const reportJobTitle = computed(() => jobDesc.value.trim())
/**
 * 复盘报告视图模型（单一来源）。
 *
 * <p>降级判定、维度条、综合评价、改进建议全部由 {@link buildReportView} 派生——
 * 组件与导出/分享都读同一份结果，避免各处重复实现（尤其杜绝在组件层 `?? 0` 补零）。
 * 实时面试的维度明细恒为真，故 `degraded` 通常为 false；历史回看才可能出现降级。
 */
const reportView = computed(() => buildReportView(sessionEvals.value))

function diffClass(d: string) {
  if (d === 'HARD') return 'tag-danger'
  if (d === 'MEDIUM') return 'tag-warning'
  return 'tag-success'
}

/** JSON.parse 失败时返回 fallback，不抛异常 */
function safeParse<T>(str: string, fallback: T): T {
  try { return JSON.parse(str) as T } catch { return fallback }
}

interface CategoryStat { category: string; total: number; avgScore: number }
interface TrendPoint { score?: number; sessionId?: string }

/**
 * 跨场自适应出题目标：
 * - 用户手动选难度（EASY/MEDIUM/HARD）时直接采用，并附带聚焦弱项
 * - 选「自适应」时按历史平均分推断难度，并聚焦历史弱项分类
 * 任一步骤失败均降级为默认值，不阻塞面试创建
 */
async function resolveAdaptiveTarget(): Promise<{ difficulty: string; focusCategories: string }> {
  let difficulty = difficultyPref.value
  let focusCategories = FOCUS_OVERRIDE

  // 从错题本等入口带入聚焦项时直接采用，不再拉取历史分类
  if (!focusCategories) {
    try {
      // 聚焦历史薄弱分类（avgScore < 70 且有一定样本量的前 3 项）
      const summary = await api.get('/api/knowledge/question-summary') as unknown as { byCategory?: CategoryStat[] }
      focusCategories = (summary?.byCategory || [])
        .filter((c) => c.total >= 1 && (c.avgScore ?? 100) < 70)
        .sort((a, b) => (a.avgScore ?? 100) - (b.avgScore ?? 100))
        .slice(0, 3)
        .map((c) => c.category)
        .join(',')
    } catch (e: unknown) {
      console.warn('聚焦薄弱分类解析失败，忽略：', e)
    }
  }

  // 自适应时按历史平均分推断难度（仅当用户未手动指定）
  if (!difficulty) {
    try {
      const trend = await api.get('/api/stats/trend') as unknown as TrendPoint[]
      const scores = trend.map((p) => p.score ?? 0)
      if (scores.length >= 1) {
        const avg = scores.reduce((a, b) => a + b, 0) / scores.length
        if (avg < 62) difficulty = 'EASY'
        else if (avg > 82) difficulty = 'HARD'
        else difficulty = 'MEDIUM'
      }
    } catch (e: unknown) {
      console.warn('难度推断失败，使用默认：', e)
    }
  }
  return { difficulty, focusCategories }
}

// ── 语音作答：录制转写 + 表达分析 ──
function ensureSpeechRec() {
  if (!speechRec) {
    speechRec = createSpeechRecorder({
      onFinalText: (text) => {
        // 将识别好的文本追加到回答输入框（失败时保留已有文本）
        userAnswer.value = (userAnswer.value.trim() ? userAnswer.value.trim() + '\n' : '') + text.trim()
      },
      onMetrics: (metrics) => {
        // P3-30：无有效语音时不显示语速判定——此前会拼出自相矛盾的「语速 适中（0 字/分）· 时长 0s」
        //（rateVerdict 在 durationSec===0 时兜底为「适中」，与 0 字/分并列展示）。
        // 同时优先使用 speech.ts 里按场景写好的 feedback（该字段此前从未被读取，指引文案被白白浪费）。
        if (metrics.charCount === 0 || metrics.durationSec === 0) {
          speechFeedback.value = metrics.feedback
        } else {
          speechFeedback.value =
            `语速 ${metrics.rateVerdict}（${metrics.ratePerMin} 字/分）· 时长 ${metrics.durationSec.toFixed(0)}s` +
            (metrics.pauseCount ? ` · 停顿 ${metrics.pauseCount} 次` : '')
        }
      },
      onError: (msg) => {
        speechRecording.value = false
        ElMessage.error(msg)
      },
      onEnd: () => {
        // P2-28：识别正常结束（含静音自动结束）时复位按钮状态。
        // 此前只有 onError 会复位，onend 路径漏掉 → speechRecording 永久为 true，
        // 按钮带 loading 显示「语音识别中… 再点一下结束」，
        // 而 BaseButton 在 loading 时静默 return 点击处理器 → 按钮彻底不可用，只能刷新页面。
        speechRecording.value = false
      },
    })
  }
  return speechRec!
}

function toggleSpeech() {
  const rec = ensureSpeechRec()
  if (rec.isRecording()) {
    speechRecording.value = false
    speechFeedback.value = ''
    rec.stop()
    return
  }
  speechFeedback.value = ''
  const ok = rec.start()
  if (!ok) {
    ElMessage.error('语音识别启动失败，请允许麦克风权限后重试')
    return
  }
  speechRecording.value = true
}

// ── 导出复盘报告 PDF（按需动态加载，减小面试页首屏包体） ──
function exportPdf() {
  const v = reportView.value
  // 无作答则无可导出内容；维度缺失交由导出模块降级处理（不在此处补 0）。
  if (!v.answeredCount) return
  const dim = (name: string) => findDim(v, name)
  // 动态 import：仅在点击导出时拉取 PDF 生成模块，避免其进入 Interview 首屏 chunk
  void import('../utils/reportPdf').then(({ exportReportToPdf }) => {
    exportReportToPdf({
      jobTitle: jobDesc.value.trim() || '未指定岗位',
      answeredCount: v.answeredCount,
      overall: v.overall,
      completeness: dim('完整性'),
      accuracy: dim('准确性'),
      expression: dim('表达力'),
      questions: v.questions.map((q) => ({
        question: q.question,
        category: q.category,
        overallScore: q.overallScore,
      })),
      improvements: v.improvements.map((i) => i.text),
      summary: v.summary,
    })
  }).catch(() => ElMessage.error('导出 PDF 模块加载失败，请重试'))
}

// ── 生成分享卡片（v1.25.0：canvas 成绩海报 + PNG 下载，动态加载） ──
const sharing = ref(false)
function shareCard() {
  if (sharing.value) return
  const v = reportView.value
  if (!v.answeredCount) return
  const dim = (name: string) => findDim(v, name)
  sharing.value = true
  void import('../utils/reportShare').then(({ generateShareCard }) =>
    generateShareCard({
      jobTitle: jobDesc.value.trim() || '未指定岗位',
      answeredCount: v.answeredCount,
      overall: v.overall,
      completeness: dim('完整性'),
      accuracy: dim('准确性'),
      expression: dim('表达力'),
    }),
  ).then(() => ElMessage.success('分享卡片已生成并下载'))
    .catch((e: unknown) => ElMessage.error((e as Error)?.message || '分享卡片生成失败，请重试'))
    .finally(() => { sharing.value = false })
}

async function startInterview() {
  if (!jobDesc.value.trim()) return ElMessage.warning('请填写目标岗位')
  if (loading.value) return // 防止重复点击
  loading.value = true
  startGenProgress()
  try {
    // 1. 先生成面试题（U2：先出题后建会话——出题失败不再留下"已完成"的空会话）
    genStep.value = 2
    const { difficulty, focusCategories } = await resolveAdaptiveTarget()
    genStep.value = 3
    const qs = await api.post('/api/interview/questions',
      { resumeText: resumeText.value || jobDesc.value, jobDescription: jobDesc.value, count: count.value, difficulty, focusCategories },
      { timeout: AI_TIMEOUT }) as unknown as string
    genStep.value = 4

    // 2. 解析题目
    const parsed = safeParse<Question[]>(qs, [])
    if (!parsed.length) {
      ElMessage.error('面试题生成失败，请检查岗位描述后重试')
      return
    }

    // 3. 出题成功后再创建会话并持久化题目
    //    持久化失败不阻塞流程，仅记录日志（用户仍可在当前会话答题，仅历史回顾不可用）
    const sess = await api.post('/api/session/create',
      { jobDescription: jobDesc.value }, { timeout: AI_TIMEOUT }) as unknown as { sessionId: string }
    const createdSessionId = sess.sessionId
    try {
      const saved = await api.post(`/api/session/${createdSessionId}/questions`,
        parsed, { timeout: AI_TIMEOUT }) as unknown as Question[]
      if (Array.isArray(saved) && saved.length === parsed.length) {
        // 用后端返回的带 id 题目替换，后续 saveAnswer 需要 questionId
        parsed.splice(0, parsed.length, ...saved)
      }
    } catch (persistErr) {
      console.warn('题目持久化失败，历史回顾将不可用：', persistErr)
      // P1-18：持久化失败此前只写 console.warn，用户拿到一份「看起来完整」的面试，
      // 却没有任何数据落库（历史/错题本/趋势全空），且毫不知情。
      // 这里显式提示，让失败可见——「不阻断流程」不等于「可以不告诉用户」。
      ElMessage.warning('题目保存失败：本次作答将不会记入面试历史与错题本')
    }

    // 4. 题目成功后设置状态，切换到面试页
    sessionId.value = createdSessionId
    questions.value = parsed
    ElMessage.success(`已生成 ${questions.value.length} 道题目，开始面试！`)
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '创建面试失败'))
  } finally {
    loading.value = false
    stopGenProgress()
  }
}

// 用户手动触发提示入口：重置冷启动重试配额，避免上一次失败残留导致后续不再重试
function startHint() {
  hintColdRetryCount = 0
  streamHint()
}

async function streamHint() {
  if (!currentQ.value) return
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
    } else if (e instanceof TypeError && hintColdRetryCount < 1) {
      // 冷启动自动重试：网络层断开（后端休眠），唤醒后再试一次（每次用户触发仅允许一次）
      hintColdRetryCount += 1
      try {
        ElMessage.info('后端服务正在冷启动（30-60s），正在唤醒，请稍候...')
        const wake = new AbortController()
        const wakeTimer = setTimeout(() => wake.abort(), 100000)
        await fetch(`${apiBaseUrl}/api/info`, { signal: wake.signal })
        clearTimeout(wakeTimer)
        // P2-22：递归前先清除外层 60s 兜底定时器——唤醒最多耗时 100s 计入外层超时后，
        // 定时器触发 abort 的是递归内新建的 controller，重试流会被静默掐断
        clearTimeout(timeoutId)
        await streamHint()
        return
      } catch {
        ElMessage.error('唤醒后端失败，请检查网络后重试')
      }
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
  if (evalLoading.value) return // 防并发：Ctrl+Enter 可绕过按钮 disabled，此处兜底
  if (!userAnswer.value.trim()) return ElMessage.warning('请输入回答')
  evalLoading.value = true
  evalResult.value = null
  startEvalTimer()
  try {
    // 1. 评估回答（AI 返回评分 + 改进建议；可选携带附图 imageUrl 做多模态评估 v1.30.0）
    const data = await api.post('/api/interview/evaluate', {
      question: currentQ.value.question,
      userAnswer: userAnswer.value,
      // P2-24：此前漏传参考答案，后端 getOrDefault("referenceAnswer","") 恒为空串，
      // 导致提示词里「评分须结合【参考答案】要点逐项核对」完全落空、评分退化为凭模型印象。
      referenceAnswer: currentQ.value.referenceAnswer || undefined,
      imageUrl: imageUrl.value || undefined
    }) as unknown as string
    evalResult.value = safeParse<EvalResult>(data, {})

    // 维度明细（第三批 A）：以原始对象承载，**不在此处 `?? 0` 兜底**——
    // 缺失交由 utils/reportView.ts 判定降级（RK1「无数据 ≠ 0」）。
    const evalDetail = evalResult.value
      ? {
          completeness: evalResult.value.completeness,
          accuracy: evalResult.value.accuracy,
          expression: evalResult.value.expression,
          improvements: evalResult.value.improvements ?? [],
        }
      : undefined

    // 收集本次回答的评估结果，供面试结束后的综合复盘报告使用
    if (evalResult.value && typeof evalResult.value.overallScore === 'number') {
      sessionEvals.value.push({
        question: currentQ.value.question,
        category: currentQ.value.category,
        difficulty: currentQ.value.difficulty,
        overallScore: evalResult.value.overallScore,
        evalDetail,
      })
    }

    // 2. 持久化用户答案 + 评估分 + 维度明细到后端（关联 questionId）
    //    失败不阻塞流程，仅记录日志（历史回顾会缺失本次答题记录）
    const questionId = currentQ.value.id
    if (questionId != null) {
      try {
        await api.post('/api/session/answer', {
          questionId,
          userAnswer: userAnswer.value,
          evaluationScore: evalResult.value.overallScore ?? null,
          // 后端校验：合法 JSON 且 ≤4000 字；非法则 400。故序列化成字符串后再传。
          evalDetail: evalDetail ? JSON.stringify(evalDetail) : null
        })
      } catch (persistErr) {
        console.warn('答案持久化失败，历史回顾将缺失本次记录：', persistErr)
        ElMessage.warning('本次回答保存失败，不会记入面试历史与错题本')
      }
    } else if (!persistWarned.value) {
      // P1-18：题目没有 id（通常源于出题入库失败）时，此前是「静默什么都不做」——
      // 用户答完整场面试，服务端一条记录都没有，界面上也毫无提示。
      // 这里在首次命中时明确告知，避免「看起来正常、实际全丢」。
      persistWarned.value = true
      ElMessage.warning('该题未成功入库，本次回答不会记入面试历史与错题本')
    }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '评估失败'))
  } finally {
    stopEvalTimer()
    evalLoading.value = false
  }
}

function nextQuestion() {
  if (qIndex.value < questions.value.length - 1) {
    qIndex.value++
    userAnswer.value = ''
    evalResult.value = null
    imageUrl.value = '' // 切题时清除上一题附图
    imageSignedUrl.value = ''
    // 切题时终止上一题的提示流，避免旧题 token 继续写入新题的 streamContent
    abortController?.abort()
    streaming.value = false
    streamContent.value = ''
  }
}

const followingUp = ref(false)

/** 深挖提问（v1.28.0 追问链）：基于本题回答+简历生成针对性追问，追加进会话继续作答 */
async function deepFollowUp() {
  if (!currentQ.value || !sessionId.value || followingUp.value) return
  followingUp.value = true
  try {
    const q = (await api.post('/api/interview/followup', {
      question: currentQ.value.question,
      userAnswer: userAnswer.value.trim(),
      resumeText: resumeText.value || '',
    }, { timeout: AI_TIMEOUT })) as unknown as string
    if (!q || !q.trim()) {
      ElMessage.warning('未能生成追问，请稍后重试')
      return
    }
    // 持久化到当前会话（获取带 id 的新题），再追加到题目列表继续作答
    const saved = (await api.post(`/api/session/${sessionId.value}/questions`,
      [{ question: q.trim(), category: currentQ.value.category, difficulty: currentQ.value.difficulty }],
      { timeout: AI_TIMEOUT })) as unknown as Question[]
    if (Array.isArray(saved) && saved.length) {
      questions.value.push(saved[0])
      qIndex.value = questions.value.length - 1
      userAnswer.value = ''
      evalResult.value = null
      ElMessage.success('已生成深挖追问')
    } else {
      ElMessage.warning('追问已生成但保存失败')
    }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '生成追问失败'))
  } finally {
    followingUp.value = false
  }
}

/**
 * 拉取历史各场得分用于复盘对比。
 * 通过 trend(DAY) 获取已完成会话，并剔除当前会话（避免把本轮算进基数）。
 * 失败时降级为“无历史”，不影响报告展示。
 */
async function loadHistoryCompare(currentSessionId: string) {
  historyCompareLoaded.value = false
  compareFailed.value = false
  try {
    const [trendRaw, sessionsRaw] = await Promise.all([
      api.get('/api/stats/trend', { params: { dimension: 'DAY' } }),
      // 其它已完成会话是否存在 —— 用于把「真首次」与「有历史但趋势为空」区分开（P2-20）。
      // 该请求失败不应让整段对比失败，故单独 catch 成空数组。
      api.get('/api/session/list').catch(() => []),
    ])
    const trend = (trendRaw || []) as unknown as TrendPoint[]
    const sessions = (sessionsRaw || []) as unknown as Array<{ sessionId?: string; status?: string }>
    historyScores.value = trend
      .filter((p) => p.sessionId !== currentSessionId)
      .map((p) => p.score ?? 0)
    hasPastSessions.value = sessions.some(
      (s) => s.sessionId !== currentSessionId && s.status === 'FINISHED',
    )
  } catch (e: unknown) {
    // P2-20：不再把「加载失败」静默降级成「你是首次」——那会把异常伪装成事实
    console.warn('历史成绩加载失败：', e)
    compareFailed.value = true
    historyScores.value = []
    hasPastSessions.value = false
  } finally {
    historyCompareLoaded.value = true
  }
}

async function finishSession() {
  const finishedSessionId = sessionId.value
  if (!finishedSessionId) return
  try {
    await api.put(`/api/session/${finishedSessionId}/finish`)
    const hasReport = sessionEvals.value.length > 0
    // 有作答记录则弹出综合复盘报告，否则仅提示完成
    if (hasReport) {
      // P3-03：在 resetSession() 清空 questions/historyAnsweredCount 之前先算好抬头口径
      reportScopeText.value = buildScopeText(
        questions.value.length,
        sessionEvals.value.length,
        historyAnsweredCount.value,
      )
      reportOpen.value = true
      // 拉取历史成绩用于“多轮对比”展示（在当前会话 id 被清空前传入）
      loadHistoryCompare(finishedSessionId)
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
  persistWarned.value = false
  historyAnsweredCount.value = 0
}

/**
 * 关闭报告并回到面试准备页。
 *
 * <p>注意：关闭后<b>没有</b>重新打开的入口——reportOpen 只在 finishSession 中置真，
 * 且同一函数内已调用 resetSession 清空 questions/sessionId。
 * 因此遮罩点击不再触发本函数（P2-29），关闭动作只保留右上角 ✕ 与底部两个明确按钮，
 * 避免一次误点造成不可逆的入口丢失。
 */
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
  // 组件卸载时释放麦克风资源，避免持续占用
  if (speechRec?.isRecording()) speechRec.cancel()
  // 清理生成进度计时器
  stopGenProgress()
  // 清理评分等待计时器（P2-21）
  stopEvalTimer()
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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

/* ── 难度偏好（自适应出题）── */
.difficulty-pref {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.pref-btn {
  padding: 8px 18px;
  font-size: 13px;
  color: var(--c-text-secondary);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 999px;
  cursor: pointer;
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
  font-family: var(--font-sans);
}
.pref-btn:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
}
.pref-btn.active {
  background: var(--brand-primary);
  border-color: var(--brand-primary);
  color: #fff;
  font-weight: 600;
}
.focus-note {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--brand-primary);
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  padding: 10px 14px;
  line-height: 1.5;
}

/* ── 生成分步进度（v1.23.2 优化①）── */
.gen-progress {
  margin-top: 16px;
  padding: 16px 18px;
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
}
.gen-steps {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 14px;
}
.gen-step {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--c-text-tertiary);
  transition: color var(--transition-fast);
}
.gen-step.active,
.gen-step.done {
  color: var(--c-text);
}
.gen-step.active .gen-label {
  font-weight: 600;
  color: var(--brand-primary);
}
.gen-dot {
  width: 18px;
  height: 18px;
  border-radius: 999px;
  border: 2px solid var(--c-border);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--brand-primary);
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
}
.gen-step.done .gen-dot {
  border-color: var(--brand-primary);
  background: var(--brand-primary);
  color: #fff;
}
.gen-step.active .gen-dot {
  border-color: var(--brand-primary);
}
.gen-dot-inner {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--c-border);
}
.gen-dot-inner.pulse {
  background: var(--brand-primary);
  animation: gen-pulse 1.2s ease-in-out infinite;
}
@keyframes gen-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.75); }
}
.gen-elapsed {
  margin-left: auto;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-tertiary);
  font-variant-numeric: tabular-nums;
}
.gen-bar {
  height: 6px;
  background: var(--brand-primary-50);
  border-radius: 999px;
  overflow: hidden;
}
.gen-bar-fill {
  height: 100%;
  background: var(--brand-gradient);
  border-radius: 999px;
  transition: width 1s linear;
}
.gen-tip {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--c-text-tertiary);
  line-height: 1.5;
}

/* ── 语音作答 ── */
.speech-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.speech-feedback {
  font-size: 12px;
  color: var(--c-text-tertiary);
}
.speech-unsupported {
  font-size: 12px;
  color: var(--c-danger);
}
.rec-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--c-danger);
  display: inline-block;
  margin-right: 6px;
  animation: rec-blink 1s ease-in-out infinite;
}
.speech-bar :deep(.base-btn).is-recording {
  border-color: var(--c-danger);
  color: var(--c-danger);
}
@keyframes rec-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.25; }
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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
  background: linear-gradient(135deg, var(--c-success), color-mix(in srgb, var(--c-success) 78%, #000));
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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

/* 本场面试的目标岗位（v1.44.0）。
   放在进度条上方，用最轻的一行交代「这场面的是什么岗位」——
   从面试历史继续面试时，此前整页都没有这个信息。 */
.session-job {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 0 0 10px;
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--c-border-light);
  min-width: 0;
}

.session-job-label {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.session-job-name {
  font-family: var(--font-title);
  font-size: 14.5px;
  font-weight: 700;
  letter-spacing: -0.2px;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  color: var(--c-success);
}

.tag-warning {
  background: rgba(245, 158, 11, 0.1);
  color: var(--c-warning);
}

.tag-danger {
  background: rgba(239, 68, 68, 0.1);
  color: var(--c-danger);
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
  border-left: 3px solid var(--brand-primary-200);
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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

/* ── 评分等待反馈（P2-21）──
   评分耗时 11s~71s 波动大，此前界面上只有按钮 loading，
   用户无法区分「AI 正在评估」与「已经卡死」。 */
.eval-waiting {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  font-size: 13px;
  color: var(--c-text-secondary);
}

.eval-waiting-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand-primary);
  animation: eval-pulse 1.2s ease-in-out infinite;
}

@keyframes eval-pulse {
  0%, 100% { opacity: 0.35; transform: scale(0.85); }
  50% { opacity: 1; transform: scale(1); }
}

.eval-waiting-elapsed {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
  color: var(--c-text-tertiary);
}

.eval-waiting-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-tertiary);
}

/* ── 多模态附图（v1.30.0）── */
.attach-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 10px;
}
.attach-hint {
  font-size: 12px;
  color: var(--c-text-secondary, #888);
}
.attach-preview {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--c-border-light, #e5e5e5);
}
.attach-remove {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: none;
  background: var(--c-danger, #e11d48);
  color: #fff;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
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

<!-- 复盘报告弹窗被 Teleport 到 body：报告本体已抽到 components/ReportPanel.vue（自带头部/维度/对比/逐题/建议全量样式）。
     此处仅保留父级 <Transition name="report-fade"> 所需的过渡类（全局作用，避免在子组件内重复定义）。 -->
<style>
.report-fade-enter-active,
.report-fade-leave-active {
  transition: opacity 0.25s ease;
}
.report-fade-enter-from,
.report-fade-leave-to {
  opacity: 0;
}
</style>
