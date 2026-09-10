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

            <!-- 多轮成绩对比（进阶） -->
            <div v-if="historyCompareLoaded && answeredCount" class="report-block report-compare">
              <div class="report-block-title">与历史成绩对比</div>
              <div class="compare-line">
                <span class="compare-badge" :class="reportCompare.status">{{ compareStatusLabel }}</span>
                <span class="compare-text">{{ reportCompare.hint }}</span>
              </div>
              <div class="compare-target">
                下一轮目标：综合
                <b :style="{ color: scoreColor(nextTarget) }">{{ nextTarget }} 分</b>
                。可在准备页提高难度或聚焦薄弱分类，逐步达成。
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
              <BaseButton variant="ghost" @click="exportPdf">导出 PDF</BaseButton>
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
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { AI_TIMEOUT, getErrMessage, apiBaseUrl } from '../api'
import { authState, isTokenValid, clearAuth } from '../auth'
import { JOB_SUGGESTIONS } from '../utils/jobOptions'
import renderMarkdown from '../utils/markdown'
import { createSpeechRecorder, isSpeechSupported } from '../utils/speech'
import { compareWithHistory, suggestNextTarget } from '../utils/reportCompare'
import { BaseButton, BaseInput, BaseTextarea } from '../components'

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
const count = ref(!Number.isNaN(routeCount) ? Math.min(10, Math.max(3, routeCount)) : 5)
const difficultyPref = ref(routeDiff && DIFF_OPTIONS.some((o) => o.value === routeDiff) ? routeDiff : '')
const loading = ref(false)
/** 由错题本带入聚焦项时的提示文案 */
const focusNote = computed(() => FOCUS_OVERRIDE ? `已聚焦薄弱分类：${FOCUS_OVERRIDE}` : '')
const sessionId = ref('')
const questions = ref<Question[]>([])
const qIndex = ref(0)
const userAnswer = ref('')
const evalLoading = ref(false)
const evalResult = ref<EvalResult | null>(null)
const streaming = ref(false)
const streamContent = ref('')
const hintOpen = ref(false)

// ── 语音作答（ASR）──
const speechSupported = isSpeechSupported()
const speechRecording = ref(false)
const speechFeedback = ref('')
// 识别器仅在打开语音时按需创建，避免占用麦克风资源
let speechRec: ReturnType<typeof createSpeechRecorder> | null = null

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

// ── 复盘报告进阶：多轮成绩对比 ──
/** 历史各场综合得分（不含本轮，从 trend 过滤当前会话） */
const historyScores = ref<number[]>([])
const historyCompareLoaded = ref(false)
/** 与历史对比结论 */
const reportCompare = computed(() => compareWithHistory(reportAverages.value.overall, historyScores.value))
/** 下一轮目标分 */
const nextTarget = computed(() => suggestNextTarget(reportAverages.value.overall))
/** 对比状态的中文标签 */
const compareStatusLabel = computed(() => {
  switch (reportCompare.value.status) {
    case 'improved': return '进步'
    case 'declined': return '待加强'
    case 'steady': return '持平'
    default: return '首次'
  }
})

// AbortController 用于取消 SSE 流式请求
let abortController: AbortController | null = null
// 冷启动重试标记：AI 提示流在 Render 冷启动网络断开时，唤醒后端后自动重试一次
let hintColdRetried = false

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
        speechFeedback.value =
          `语速 ${metrics.rateVerdict}（${metrics.ratePerMin} 字/分）· 时长 ${metrics.durationSec.toFixed(0)}s` +
          (metrics.pauseCount ? ` · 停顿 ${metrics.pauseCount} 次` : '')
      },
      onError: (msg) => {
        speechRecording.value = false
        ElMessage.error(msg)
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
  if (!sessionEvals.value.length) return
  const a = reportAverages.value
  // 动态 import：仅在点击导出时拉取 PDF 生成模块，避免其进入 Interview 首屏 chunk
  void import('../utils/reportPdf').then(({ exportReportToPdf }) => {
    exportReportToPdf({
      jobTitle: jobDesc.value.trim() || '未指定岗位',
      answeredCount: answeredCount.value,
      overall: a.overall,
      completeness: a.completeness,
      accuracy: a.accuracy,
      expression: a.expression,
      questions: sessionEvals.value.map((e) => ({
        question: e.question,
        category: e.category,
        overallScore: e.overallScore,
      })),
      improvements: reportImprovements.value.map((i) => i.text),
      summary: reportSummary.value,
    })
  }).catch(() => ElMessage.error('导出 PDF 模块加载失败，请重试'))
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

    // 2. 生成面试题（跨场自适应：按历史成绩推断难度 + 聚焦薄弱分类）
    const { difficulty, focusCategories } = await resolveAdaptiveTarget()
    const qs = await api.post('/api/interview/questions',
      { resumeText: resumeText.value || jobDesc.value, jobDescription: jobDesc.value, count: count.value, difficulty, focusCategories },
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
  hintColdRetried = false
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
    } else if (e instanceof TypeError && !hintColdRetried) {
      // 冷启动自动重试：网络层断开（后端休眠），唤醒后再试一次
      hintColdRetried = true
      try {
        ElMessage.info('后端服务正在冷启动（30-60s），正在唤醒，请稍候...')
        const wake = new AbortController()
        const wakeTimer = setTimeout(() => wake.abort(), 100000)
        await fetch(`${apiBaseUrl}/api/info`, { signal: wake.signal })
        clearTimeout(wakeTimer)
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
    // 切题时终止上一题的提示流，避免旧题 token 继续写入新题的 streamContent
    abortController?.abort()
    streaming.value = false
    streamContent.value = ''
  }
}

/**
 * 拉取历史各场得分用于复盘对比。
 * 通过 trend(DAY) 获取已完成会话，并剔除当前会话（避免把本轮算进基数）。
 * 失败时降级为“无历史”，不影响报告展示。
 */
async function loadHistoryCompare(currentSessionId: string) {
  historyCompareLoaded.value = false
  try {
    const trend = await api.get('/api/stats/trend', { params: { dimension: 'DAY' } }) as unknown as TrendPoint[]
    historyScores.value = (trend || [])
      .filter((p) => p.sessionId !== currentSessionId)
      .map((p) => p.score ?? 0)
  } catch (e: unknown) {
    console.warn('历史成绩加载失败，跳过对比：', e)
    historyScores.value = []
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
  // 组件卸载时释放麦克风资源，避免持续占用
  if (speechRec?.isRecording()) speechRec.cancel()
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
  transition: all var(--transition-fast);
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
.compare-line {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  line-height: 1.6;
  margin-bottom: 8px;
}
.compare-badge {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 999px;
  color: #fff;
}
.compare-badge.improved { background: #10b981; }
.compare-badge.steady { background: #3b82f6; }
.compare-badge.declined { background: #f59e0b; }
.compare-badge.unknown { background: var(--c-text-tertiary); }
.compare-text {
  font-size: 13px;
  color: var(--c-text-secondary);
}
.compare-target {
  font-size: 13px;
  color: var(--c-text-secondary);
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  line-height: 1.6;
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
