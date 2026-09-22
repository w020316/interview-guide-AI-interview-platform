<template>
  <div class="resume-page">
    <header class="page-header">
      <h1>简历分析</h1>
      <p>AI 从多维度评估你的简历，给出可执行的改进建议</p>
    </header>

    <!-- 输入区 -->
    <div class="input-section">
      <div class="tab-switch">
        <button :class="{ active: tab === 'upload' }" @click="tab = 'upload'">上传文件</button>
        <button :class="{ active: tab === 'import' }" @click="tab = 'import'">从其他平台导入</button>
        <button :class="{ active: tab === 'text' }" @click="tab = 'text'">粘贴文本</button>
      </div>

      <div v-if="tab === 'upload'" class="upload-area">
        <el-upload drag accept=".pdf,.txt,.html,.htm,.md,.markdown,application/pdf,text/plain,text/html,text/markdown"
          :before-upload="handleUpload" :show-file-list="false" :http-request="() => {}">
          <div class="upload-inner">
            <div class="upload-icon"></div>
            <div class="upload-text">拖拽文件到此处或 <span class="upload-action">点击上传</span></div>
            <div class="upload-hint">支持 PDF / HTML / MD / TXT 格式，文件 ≤ 10MB</div>
          </div>
        </el-upload>
        <div class="field-row">
          <label>目标岗位</label>
          <BaseInput v-model="targetJob" block list="job-suggestions" placeholder="如：Java 后端、产品经理、教师、医生、销售经理…" />
          <datalist id="job-suggestions">
            <option v-for="job in JOB_SUGGESTIONS" :key="job" :value="job" />
          </datalist>
        </div>
      </div>

      <div v-else-if="tab === 'import'" class="import-area">
        <!-- v1.37.0 新增：从其他软件提取简历。
             场景：简历常常不在本地，而是躺在微信「文件传输助手」、网盘、WPS 云文档
             或招聘 App 的在线简历里。此前只能靠用户自己找到并导出，取件成本高。
             现在给出直达入口——移动端唤起对应 App，桌面端打开其网页版。 -->
        <div class="extract-block">
          <div class="extract-head">
            <p class="extract-title">从其他软件提取简历</p>
            <p class="extract-sub">
              点对应入口直达取件，导出 PDF / 复制文本后回到本页
            </p>
          </div>

          <div class="extract-grid">
            <div v-for="s in EXTRACT_SOURCES" :key="s.key" class="extract-card">
              <button class="ex-main" type="button" :title="`前往 ${s.name}（${s.hint}）`" @click="openExtractSource(s)">
                <span class="ex-ico" :style="{ background: s.bg, color: s.color }">{{ s.glyph }}</span>
                <span class="ex-body">
                  <b>{{ s.name }}</b>
                  <em>{{ s.hint }}</em>
                </span>
                <svg class="ex-enter" width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M7 17L17 7 M9 7h8v8" stroke="currentColor" stroke-width="2"
                    stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </button>
              <!-- 唤起 App 失败时的兜底入口（用户手势触发，不会被浏览器拦截） -->
              <a
                v-if="failedKey === s.key"
                class="ex-fallback"
                :href="s.webUrl"
                target="_blank"
                rel="noopener"
              >未唤起 App？打开网页版 →</a>
            </div>
          </div>

          <div class="extract-actions">
            <button class="btn-pick" type="button" @click="triggerFilePicker">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              选择本机文件
            </button>
            <button class="btn-clipboard" type="button" @click="pasteFromClipboard">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M9 2h6a1 1 0 011 1v1h2a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2V3a1 1 0 011-1z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M9 12h6 M9 16h4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
              从剪贴板粘贴
            </button>
            <!-- 原生文件选择：桌面打开文件管理器，iOS/Android 打开「文件」App（可从 iCloud / 云盘选取） -->
            <input
              ref="nativeFileInput"
              class="hidden-file"
              type="file"
              accept=".pdf,.txt,.html,.htm,.md,.markdown"
              @change="onNativeFile"
            />
          </div>

          <p class="extract-note">
            简历是 Word 文档？在 WPS / 腾讯文档里「导出为 PDF」后，再用上面的
            「选择本机文件」上传即可（系统会自动识别 PDF 文本）。
          </p>
        </div>

        <div class="import-divider"><span>或从链接导入</span></div>

        <div class="field-row">
          <label>简历页面 URL</label>
          <BaseInput v-model="importUrl" type="url" block placeholder="https://your-resume-url.com" />
          <p class="field-hint">支持超级简历、GitHub 主页、个人博客等公开页面</p>
        </div>
        <button class="btn-import" :disabled="importLoading" @click="importFromUrl">
          <span v-if="importLoading" class="spinner"></span>
          {{ importLoading ? '抓取分析中...' : '从 URL 导入' }}
        </button>

        <div class="field-row" style="margin-top: 4px;">
          <label>目标岗位</label>
          <BaseInput v-model="targetJob" block list="job-suggestions" placeholder="如：Java 后端、产品经理、教师、医生、销售经理…" />
        </div>
      </div>

      <div v-else class="text-area">
        <div class="field-row">
          <label>简历内容</label>
          <BaseTextarea v-model="resumeText" :rows="10" placeholder="粘贴你的简历文本..." />
        </div>
        <div class="field-row">
          <label>目标岗位</label>
          <BaseInput v-model="targetJob" block list="job-suggestions" placeholder="如：Java 后端、产品经理、教师、医生、销售经理…" />
        </div>
        <button class="btn-analyze" :disabled="loading" @click="analyzeText">
          <span v-if="loading" class="spinner"></span>
          {{ loading ? '分析中...' : '开始分析' }}
        </button>
      </div>
    </div>

    <!-- 加载状态：骨架屏（v1.23.2 优化②，按结果布局占位，降低等待焦虑） -->
    <div v-if="loading" class="loading-state" role="status" aria-live="polite">
      <div class="skeleton-hero">
        <div class="skeleton skeleton-circle"></div>
        <div class="skeleton-hero-lines">
          <div class="skeleton skeleton-line" style="width: 30%"></div>
          <div class="skeleton skeleton-line" style="width: 52%"></div>
        </div>
      </div>
      <div class="skeleton-grid">
        <div v-for="i in 4" :key="i" class="skeleton-card">
          <div class="skeleton skeleton-line" style="width: 42%"></div>
          <div class="skeleton skeleton-bar"></div>
          <div class="skeleton skeleton-line" style="width: 88%"></div>
        </div>
      </div>
      <div class="skeleton-hint">AI 正在分析你的简历 · 首次调用需冷启动，最长约 1-2 分钟</div>
    </div>

    <!-- 分析结果 -->
    <div v-if="result && !loading" class="result-section fade-in-up">
      <div v-if="parseError" class="parse-warning">
        <span class="warning-icon">⚠</span>
        <span>{{ parseError }}</span>
      </div>

      <!-- 综合评分 -->
      <div class="score-hero">
        <div class="score-circle" :style="{ '--score-color': scoreColor }">
          <svg viewBox="0 0 120 120" class="score-svg">
            <circle cx="60" cy="60" r="52" class="score-track" />
            <circle cx="60" cy="60" r="52" class="score-fill"
              :style="{ strokeDasharray: 327, strokeDashoffset: 327 - (327 * (parsed.overallScore || 0)) / 100 }" />
          </svg>
          <div class="score-value">
            <span class="score-num">{{ parsed.overallScore ?? '-' }}</span>
            <span class="score-unit">分</span>
          </div>
        </div>
        <div class="score-meta">
          <h3>综合评分</h3>
          <p>{{ scoreLevel }}</p>
        </div>
      </div>

      <!-- 一键直达模拟面试（v1.23.2 优化③：核心路径减 2 步） -->
      <div class="next-action">
        <button class="btn-to-interview" :disabled="!resumeText" @click="goInterview">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M5 3l14 9-14 9V3z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          带着这份简历去模拟面试
        </button>
        <p class="next-hint">自动携带简历摘要{{ targetJob ? `与目标岗位「${targetJob}」` : '' }}，AI 将据此定制面试题</p>
      </div>

      <!-- 维度评分 -->
      <div v-if="parsed.dimensions?.length" class="dimensions">
        <h4 class="block-title">维度评分</h4>
        <div class="dim-grid">
          <div v-for="(d, idx) in parsed.dimensions" :key="idx" class="dim-card">
            <div class="dim-head">
              <span class="dim-name">{{ d.name }}</span>
              <span class="dim-score" :style="{ color: getScoreColor(d.score) }">{{ d.score }}分</span>
            </div>
            <div class="dim-bar">
              <div class="dim-bar-fill" :style="{ width: (d.score || 0) + '%', background: getScoreGradient(d.score) }"></div>
            </div>
            <p class="dim-suggestion">{{ d.suggestion }}</p>
          </div>
        </div>
      </div>

      <!-- 优势 & 建议 -->
      <div class="analysis-grid">
        <div class="analysis-card strengths">
          <div class="card-head">
            <span class="card-icon strengths-icon">✓</span>
            <h4>核心优势</h4>
          </div>
          <ul v-if="parsed.strengths?.length" class="analysis-list">
            <li v-for="(s, idx) in parsed.strengths" :key="idx">{{ s }}</li>
          </ul>
          <div v-else class="empty-hint">暂无</div>
        </div>
        <div class="analysis-card improvements">
          <div class="card-head">
            <span class="card-icon improvements-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M9 21h6 M10 18h4 M12 2a7 7 0 0 0-4 12.7c.6.5 1 1.3 1 2.1V17h6v-.2c0-.8.4-1.6 1-2.1A7 7 0 0 0 12 2z"
                  stroke="#d97706" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </span>
            <h4>改进建议</h4>
          </div>
          <ul v-if="parsed.improvements?.length" class="analysis-list">
            <li v-for="(i, idx) in parsed.improvements" :key="idx">{{ i }}</li>
          </ul>
          <div v-else class="empty-hint">暂无</div>
        </div>
      </div>

      <!-- 原始返回 -->
      <details class="raw-section">
        <summary>查看 AI 原始返回</summary>
        <pre class="raw-output">{{ result }}</pre>
      </details>

      <!-- 优化简历区 -->
      <div class="optimize-section fade-in-up">
        <div class="optimize-head">
          <div>
            <h4 class="optimize-title">一键生成优化简历</h4>
            <p class="optimize-desc">基于分析建议自动改写，量化项目成果，强化岗位匹配，可下载 Markdown / HTML 文档</p>
          </div>
          <button class="btn-optimize" :disabled="optimizing || !result" @click="generateOptimized">
            <span v-if="optimizing" class="spinner"></span>
            <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none">
              <path d="M9 21h6 M10 18h4 M12 2a7 7 0 0 0-4 12.7c.6.5 1 1.3 1 2.1V17h6v-.2c0-.8.4-1.6 1-2.1A7 7 0 0 0 12 2z"
                stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            {{ optimizing ? '生成中…' : '生成优化简历' }}
          </button>
        </div>

        <div v-if="optimizing" class="optimize-loading">
          <div class="loading-spinner"></div>
          <div class="loading-text">AI 正在基于分析建议改写你的简历…</div>
          <div class="loading-hint">冷启动约 30-60s，请耐心等待</div>
        </div>

        <div v-if="optimizedMarkdown && !optimizing" class="optimize-result">
          <div class="optimize-toolbar">
            <div class="optimize-tabs">
              <button :class="{ active: optimizeView === 'preview' }" @click="optimizeView = 'preview'">预览</button>
              <button :class="{ active: optimizeView === 'source' }" @click="optimizeView = 'source'">源码</button>
            </div>
            <div class="optimize-downloads">
              <button class="btn-download" @click="downloadMarkdown">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <path d="M12 3v12 M7 10l5 5 5-5 M5 21h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                下载 .md
              </button>
              <button class="btn-download" @click="downloadHtml">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <path d="M12 3v12 M7 10l5 5 5-5 M5 21h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                下载 .html
              </button>
              <button class="btn-download" @click="copyOptimized">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <rect x="9" y="9" width="11" height="11" rx="2" stroke="currentColor" stroke-width="2"/>
                  <path d="M5 15V5a2 2 0 0 1 2-2h10" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                </svg>
                复制
              </button>
            </div>
          </div>
          <div v-if="optimizeView === 'preview'" class="optimize-preview" v-html="optimizedHtml"></div>
          <pre v-else class="optimize-source">{{ optimizedMarkdown }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api, { AI_TIMEOUT, getErrMessage } from '../api'
import { repairAndCheck } from '../utils/jsonRepair'
import { getScoreColor, getScoreGradient } from '../utils/score'
import { JOB_SUGGESTIONS } from '../utils/jobOptions'
import renderMarkdown from '../utils/markdown'
import { BaseInput, BaseTextarea } from '../components'

interface AnalysisResult {
  overallScore: number
  dimensions: Array<{ name: string; score: number; suggestion: string }>
  strengths: string[]
  improvements: string[]
}

const router = useRouter()

/** 简历摘要预填的 sessionStorage 键（与 InterviewView 约定一致） */
const PREFILL_RESUME_KEY = 'interview_prefill_resume'

/**
 * 携带简历摘要与目标岗位直达模拟面试（v1.23.2 优化③）
 * 简历文本经 sessionStorage 传递（URL 不宜携带长文本），截断 4000 字防超限
 */
function goInterview() {
  try {
    sessionStorage.setItem(PREFILL_RESUME_KEY, resumeText.value.slice(0, 4000))
  } catch {
    /* 存储不可用时降级：仅预填岗位 */
  }
  router.push('/interview' + (targetJob.value ? `?job=${encodeURIComponent(targetJob.value)}` : ''))
}

const tab = ref('upload')
const resumeText = ref('')
const targetJob = ref('')
const loading = ref(false)
const result = ref('')
const parseError = ref('')

// 从其他平台导入相关状态
const importUrl = ref('')
const importLoading = ref(false)

/* ─────────────────────────────────────────────────────────────
   从其他软件提取简历（v1.37.0）
   简历的常见存放位置并不在本地磁盘，而是在微信文件传输助手、网盘、
   WPS 云文档或招聘 App 的在线简历里。这里给出直达取件入口：
   移动端用 App scheme 唤起，桌面端打开其网页版，再配合「选择本机文件」
   与「剪贴板粘贴」两条通用兜底通道。
   ───────────────────────────────────────────────────────────── */

interface ExtractSource {
  key: string
  name: string
  /** 图标字形（单字/字母，避免依赖第三方图标资源） */
  glyph: string
  bg: string
  color: string
  /** 简历通常在该软件里的位置 */
  hint: string
  /** 移动端 App scheme；未提供则移动端也直接走网页版 */
  scheme?: string
  /** 桌面端（或唤起失败后）打开的网页地址 */
  webUrl: string
}

const EXTRACT_SOURCES: ExtractSource[] = [
  {
    key: 'wechat', name: '微信', glyph: '微', bg: '#e8f7ec', color: '#07c160',
    hint: '文件传输助手 / 收藏', scheme: 'weixin://',
    webUrl: 'https://filehelper.weixin.qq.com/',
  },
  {
    key: 'qq', name: 'QQ', glyph: 'Q', bg: '#e8f1ff', color: '#12b7f5',
    hint: '我的电脑 / 文件中转站', scheme: 'mqq://', webUrl: 'https://im.qq.com',
  },
  {
    key: 'dingtalk', name: '钉钉', glyph: '钉', bg: '#e9f2ff', color: '#1a73e8',
    hint: '钉盘 / 我的文件', scheme: 'dingtalk://', webUrl: 'https://www.dingtalk.com',
  },
  {
    key: 'wps', name: 'WPS 云文档', glyph: 'W', bg: '#fff1ec', color: '#e6432c',
    hint: '最近文档', scheme: 'wps://', webUrl: 'https://www.kdocs.cn',
  },
  {
    key: 'docs', name: '腾讯文档', glyph: '腾', bg: '#e8f0ff', color: '#1e6fff',
    hint: '我的文档', scheme: 'tencentdocs://', webUrl: 'https://docs.qq.com',
  },
  {
    key: 'pan', name: '百度网盘', glyph: '盘', bg: '#e9f3ff', color: '#2b7efb',
    hint: '我的资源', scheme: 'baiduyun://', webUrl: 'https://pan.baidu.com',
  },
  {
    key: 'wondercv', name: '超级简历', glyph: '超', bg: '#eaf7f4', color: '#0f9b7d',
    hint: '在线简历 / 导出 PDF', webUrl: 'https://www.wondercv.com',
  },
  {
    key: 'zhipin', name: 'BOSS 直聘', glyph: 'B', bg: '#e8f6f0', color: '#00a97f',
    hint: '附件简历 / 在线简历', scheme: 'bosszp://', webUrl: 'https://www.zhipin.com',
  },
]

const nativeFileInput = ref<HTMLInputElement | null>(null)
const isMobile = ref(false)
/** 唤起 App 失败的软件 key：命中后在该卡片内展开「打开网页版」兜底入口 */
const failedKey = ref('')

/** 终端判定：移动端优先唤起 App，桌面端直达网页版 */
function detectMobile(): boolean {
  if (typeof navigator === 'undefined') return false
  return /Android|iPhone|iPad|iPod|HarmonyOS|Mobile|Windows Phone/i.test(navigator.userAgent)
}

/** 触发原生文件选择（桌面打开文件管理器；iOS/Android 打开「文件」App，可进 iCloud / 云盘取件） */
function triggerFilePicker() {
  nativeFileInput.value?.click()
}

function onNativeFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) void handleUpload(file)
  // 重置 value，允许连续选择同一个文件
  input.value = ''
}

/**
 * 打开取件入口。
 *
 * - 移动端且配置了 scheme：直接跳 scheme 唤起 App，并在 1.6s 后检查页面是否被切到后台；
 *   若仍可见说明「未安装 App / 唤起被拦截」，展开该卡片的网页版兜底链接
 *   （兜底必须是 <a> 由用户手势触发，setTimeout 里 window.open 会被浏览器拦截）。
 * - 其他情况：新标签打开网页版。
 *
 * 注意：scheme 跳转必须由用户手势同步发起，因此本函数不做任何 await。
 */
function openExtractSource(s: ExtractSource) {
  failedKey.value = ''
  if (isMobile.value && s.scheme) {
    window.location.href = s.scheme
    const startedAt = Date.now()
    window.setTimeout(() => {
      const stillVisible = typeof document === 'undefined' || document.visibilityState === 'visible'
      if (stillVisible && Date.now() - startedAt < 3200) {
        failedKey.value = s.key
        ElMessage.info(`未检测到「${s.name}」App，可点下方「打开网页版」，或直接选择本机文件`)
      }
    }, 1600)
    return
  }
  window.open(s.webUrl, '_blank', 'noopener')
}

onMounted(() => {
  isMobile.value = detectMobile()
})

// 优化简历相关状态
const optimizing = ref(false)
const optimizedMarkdown = ref('')
const optimizeView = ref<'preview' | 'source'>('preview')

const optimizedHtml = computed(() => renderMarkdown(optimizedMarkdown.value))

const parsed = computed<AnalysisResult>(() => {
  if (!result.value) {
    return { overallScore: 0, dimensions: [], strengths: [], improvements: [] }
  }
  try {
    const obj = JSON.parse(result.value)
    // 强制数字转换：AI 可能返回字符串 "75" 而非数字 75
    const rawScore = obj.overallScore
    const overallScore = typeof rawScore === 'number' ? rawScore
      : typeof rawScore === 'string' ? (Number(rawScore) || 0)
      : 0
    const dimensions = Array.isArray(obj.dimensions) ? obj.dimensions.map((d: any) => ({
      name: String(d?.name ?? ''),
      score: typeof d?.score === 'number' ? d.score
        : typeof d?.score === 'string' ? (Number(d.score) || 0)
        : 0,
      suggestion: String(d?.suggestion ?? ''),
    })) : []
    const strengths = Array.isArray(obj.strengths) ? obj.strengths.map((s: any) => String(s)) : []
    const improvements = Array.isArray(obj.improvements) ? obj.improvements.map((s: any) => String(s)) : []
    return { overallScore, dimensions, strengths, improvements }
  } catch {
    return { overallScore: 0, dimensions: [], strengths: [], improvements: [] }
  }
})

/**
 * 监听 result 变化，更新 parseError
 * 使用标志位避免在 watch 内修改 result 导致递归触发
 */
let isRepairing = false
watch(result, (val) => {
  if (!val) {
    parseError.value = ''
    return
  }
  // 直接尝试解析
  let parseFailMessage: string | null = null
  try {
    JSON.parse(val)
    parseError.value = ''
    return
  } catch (e) {
    // 解析失败，走前端兜底修复；捕获错误信息供后续展示（catch 作用域外不可访问）
    parseFailMessage = e instanceof Error ? e.message : String(e)
  }

  if (isRepairing) return // 避免递归
  const { repaired, valid } = repairAndCheck(val)
  if (valid) {
    isRepairing = true
    result.value = repaired // 修复后重新赋值，会再次触发 watch
    parseError.value = ''
    // 用 nextTick 重置标志位
    setTimeout(() => { isRepairing = false }, 0)
  } else {
    parseError.value = 'AI 返回内容无法解析为标准 JSON，可在下方查看原始返回。错误：' + (parseFailMessage ?? '未知错误')
    isRepairing = false
  }
})

function handleResult(data: unknown) {
  if (data == null || (typeof data === 'string' && !data.trim())) {
    ElMessage.error('AI 返回为空，请重试')
    result.value = ''
    return false
  }
  // 新格式：上传文件时后端返回 { analysis, resumeText }
  if (data && typeof data === 'object' && 'analysis' in (data as Record<string, unknown>)) {
    const payload = data as { analysis?: string; resumeText?: string }
    const analysis = payload.analysis || ''
    if (!analysis.trim()) {
      ElMessage.error('AI 返回为空，请重试')
      result.value = ''
      return false
    }
    result.value = analysis
    // 保存后端解析出的简历文本，供"生成优化简历"使用
    if (payload.resumeText) {
      resumeText.value = payload.resumeText
    }
    return true
  }
  // 兼容旧格式：纯字符串
  result.value = typeof data === 'string' ? data : JSON.stringify(data, null, 2)
  return true
}

async function handleUpload(file: File) {
  // 文件大小校验
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 10MB')
    return false
  }
  // 文件类型校验（与后端 ALLOWED_EXTS + 模板 accept 对齐）
  const allowed = ['.pdf', '.txt', '.html', '.htm', '.md', '.markdown']
  const ext = file.name.toLowerCase().match(/\.[^.]+$/)?.[0] || ''
  if (!allowed.includes(ext)) {
    ElMessage.error('仅支持 PDF / HTML / MD / TXT 格式（Word 请转换为 PDF）')
    return false
  }

  loading.value = true
  result.value = ''
  const form = new FormData()
  form.append('file', file)
  form.append('targetJob', targetJob.value)
  try {
    const data = await api.post('/api/resume/upload', form, { timeout: AI_TIMEOUT }) as unknown as string
    if (handleResult(data)) ElMessage.success('分析完成')
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '上传失败'))
  } finally { loading.value = false }
  return false
}

async function analyzeText() {
  if (!resumeText.value.trim()) return ElMessage.warning('请输入简历内容')
  loading.value = true
  result.value = ''
  try {
    const data = await api.post('/api/resume/analyze',
      { resumeText: resumeText.value, targetJob: targetJob.value },
      { timeout: AI_TIMEOUT }) as unknown as string
    if (handleResult(data)) ElMessage.success('分析完成')
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '分析失败'))
  } finally { loading.value = false }
}

/** 从 URL 导入简历（iOS"从其他平台导入"功能） */
async function importFromUrl() {
  if (!importUrl.value.trim()) return ElMessage.warning('请输入简历页面 URL')
  importLoading.value = true
  loading.value = true
  result.value = ''
  try {
    const data = await api.post('/api/resume/import-url',
      { url: importUrl.value, targetJob: targetJob.value || '通用岗位' },
      { timeout: AI_TIMEOUT }) as unknown as { analysis?: string; resumeText?: string }
    if (handleResult(data)) {
      ElMessage.success('导入分析完成')
    }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '导入失败'))
  } finally {
    importLoading.value = false
    loading.value = false
  }
}

/** 从剪贴板粘贴简历文本 */
async function pasteFromClipboard() {
  try {
    const text = await navigator.clipboard.readText()
    if (!text || !text.trim()) {
      ElMessage.warning('剪贴板为空，请先复制简历内容')
      return
    }
    resumeText.value = text
    tab.value = 'text'
    ElMessage.success('已粘贴，请确认内容后点击"开始分析"')
  } catch {
    ElMessage.info('剪贴板访问被拒绝，请手动粘贴到文本框')
    tab.value = 'text'
  }
}

/** 综合评分主色 */
const scoreColor = computed(() => getScoreColor(parsed.value.overallScore || 0))

/** 综合评分等级文案 */
const scoreLevel = computed(() => {
  const s = parsed.value.overallScore || 0
  if (s >= 85) return '优秀 · 简历竞争力强'
  if (s >= 70) return '良好 · 仍有提升空间'
  if (s >= 60) return '合格 · 建议针对性优化'
  if (s > 0) return '待提升 · 需重点修改'
  return '-'
})

/** 重新分析时清空优化简历 */
watch(tab, (v) => {
  if (v === 'upload') {
    optimizedMarkdown.value = ''
  }
})

/** 调用后端生成优化简历 */
async function generateOptimized() {
  if (!result.value) {
    ElMessage.warning('请先完成简历分析')
    return
  }
  optimizing.value = true
  optimizedMarkdown.value = ''
  optimizeView.value = 'preview'
  try {
    // axios 拦截器已解包 Result.data，返回的就是纯字符串
    const res = await api.post('/api/resume/optimize', {
      resumeText: resumeText.value,
      targetJob: targetJob.value || '通用岗位',
      analysis: result.value
    }, { timeout: AI_TIMEOUT }) as unknown as string
    if (typeof res === 'string' && res.trim()) {
      optimizedMarkdown.value = res
      ElMessage.success('优化简历已生成')
    } else {
      ElMessage.error('生成结果为空，请重试')
    }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '生成优化简历失败'))
  } finally {
    optimizing.value = false
  }
}

/** 下载 Markdown 文件 */
function downloadMarkdown() {
  if (!optimizedMarkdown.value) return
  const blob = new Blob([optimizedMarkdown.value], { type: 'text/markdown;charset=utf-8' })
  triggerDownload(blob, `优化简历-${targetJob.value || '通用'}-${formatDate()}.md`)
}

/** 下载 HTML 文件（含基本样式） */
function downloadHtml() {
  if (!optimizedMarkdown.value) return
  const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>优化简历 - ${targetJob.value || '通用岗位'}</title>
<style>
  body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; max-width: 800px; margin: 40px auto; padding: 0 24px; color: #1c1917; line-height: 1.7; }
  h1 { color: #0f766e; border-bottom: 2px solid #0f766e; padding-bottom: 8px; }
  h2 { color: #115e59; margin-top: 28px; border-left: 4px solid #0f766e; padding-left: 12px; }
  h3 { color: #134e4a; }
  ul { padding-left: 24px; }
  li { margin: 4px 0; }
  strong { color: #0f766e; }
  @media print { body { margin: 0; } }
</style>
</head>
<body>
${optimizedHtml.value}
</body>
</html>`
  const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
  triggerDownload(blob, `优化简历-${targetJob.value || '通用'}-${formatDate()}.html`)
}

/** 复制到剪贴板 */
async function copyOptimized() {
  if (!optimizedMarkdown.value) return
  try {
    await navigator.clipboard.writeText(optimizedMarkdown.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制')
  }
}

/** 触发浏览器下载 */
function triggerDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/** 格式化日期为 YYYYMMDD */
function formatDate() {
  const d = new Date()
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
}
</script>
<style scoped>
.resume-page {
  max-width: 900px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 32px;
}

.page-header h1 {
  font-size: 28px;
  font-weight: 700;
  font-family: var(--font-serif);
  color: var(--c-text);
  margin: 0 0 6px;
  letter-spacing: -0.5px;
}

.page-header p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
}

/* ── Tab 切换 ── */
.tab-switch {
  display: inline-flex;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  padding: 4px;
  margin-bottom: 24px;
}

.tab-switch button {
  padding: 8px 20px;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.tab-switch button.active {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: var(--shadow-sm);
}

/* ── 上传区 ── */
.upload-area {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.upload-area :deep(.el-upload-dragger) {
  border: 2px dashed var(--c-border);
  border-radius: var(--radius-lg);
  padding: 40px 20px;
  transition: all var(--transition-fast);
  background: var(--c-surface);
}

.upload-area :deep(.el-upload-dragger:hover) {
  border-color: var(--brand-primary);
  background: var(--brand-primary-light);
}

.upload-inner {
  text-align: center;
}

.upload-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

.upload-text {
  font-size: 15px;
  color: var(--c-text);
  margin-bottom: 6px;
}

.upload-action {
  color: var(--brand-primary);
  font-weight: 500;
}

.upload-hint {
  font-size: 13px;
  color: var(--c-text-tertiary);
}

/* ── 表单字段 ── */
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

.text-area {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ── 从其他平台导入区 ── */
.import-area {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ── 从其他软件提取简历（v1.37.0）── */
.extract-block {
  padding: 20px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
}

.extract-head {
  margin-bottom: 14px;
}

.extract-title {
  margin: 0 0 4px;
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
}

.extract-sub {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-tertiary);
}

.extract-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(208px, 1fr));
  gap: 8px;
}

.extract-card {
  border-radius: var(--radius-md);
  background: var(--c-bg-alt);
  border: 1px solid transparent;
  transition: border-color var(--transition-fast), background-color var(--transition-fast);
}

.extract-card:hover {
  background: var(--c-surface);
  border-color: var(--brand-primary-200);
}

.ex-main {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  font-family: var(--font-sans);
  text-align: left;
  background: transparent;
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
}

.ex-ico {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 700;
}

.ex-body {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}

.ex-body b {
  font-size: 13.5px;
  font-weight: 600;
  line-height: 1.3;
  color: var(--c-text);
}

.ex-body em {
  font-style: normal;
  font-size: 11.5px;
  line-height: 1.3;
  color: var(--c-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ex-enter {
  flex-shrink: 0;
  color: var(--c-text-quaternary);
  opacity: 0;
  transition: opacity var(--transition-fast), transform var(--transition-fast), color var(--transition-fast);
}

.extract-card:hover .ex-enter {
  color: var(--brand-primary);
  opacity: 1;
  transform: translate(1px, -1px);
}

/* 唤起 App 失败后的网页版兜底入口 */
.ex-fallback {
  display: block;
  margin: 0 12px 10px;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-primary);
  background: var(--brand-primary-50);
  border-radius: var(--radius-sm);
  text-decoration: none;
}

.ex-fallback:hover {
  background: var(--brand-primary-100);
}

.extract-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed var(--c-border);
}

.extract-note {
  margin: 14px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--c-text-tertiary);
}

/* 原生文件选择器：视觉隐藏，由 .click() 触发 */
.hidden-file {
  display: none;
}

.field-hint {
  margin: 0;
  font-size: 12px;
  color: var(--c-text-quaternary);
}

.btn-import {
  align-self: flex-start;
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

.btn-import:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(15, 118, 110, 0.35);
}

.btn-import:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.import-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--c-text-tertiary);
  font-size: 13px;
}

.import-divider::before,
.import-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--c-border);
}

/* 取件通用通道：选择本机文件 / 剪贴板粘贴，并列排布 */
.btn-pick,
.btn-clipboard {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 20px;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 600;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color var(--transition-fast), border-color var(--transition-fast);
}

.btn-pick {
  color: #fff;
  background: var(--brand-primary);
  border: 1px solid var(--brand-primary);
}

.btn-pick:hover {
  background: var(--brand-primary-hover);
  border-color: var(--brand-primary-hover);
}

.btn-clipboard {
  color: var(--brand-primary);
  background: var(--c-surface);
  border: 1px solid var(--brand-primary);
}

.btn-clipboard:hover {
  background: var(--brand-primary-50);
}

.btn-analyze {
  align-self: flex-start;
  padding: 12px 32px;
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

.btn-analyze:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(15, 118, 110, 0.35);
}

.btn-analyze:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── 加载状态 ── */
.loading-state {
  margin-top: 32px;
}

.loading-card {
  text-align: center;
  padding: 48px 24px;
  background: var(--c-surface);
  border-radius: var(--radius-lg);
  border: 1px solid var(--c-border-light);
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--c-border);
  border-top-color: var(--brand-primary);
  border-radius: 50%;
  margin: 0 auto 20px;
  animation: spin 0.8s linear infinite;
}

.loading-text {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 6px;
}

.loading-hint {
  font-size: 13px;
  color: var(--c-text-tertiary);
}

/* ── 加载骨架屏（v1.23.2 优化②）── */
.skeleton-hero {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 24px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  margin-bottom: 16px;
}
.skeleton-circle {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  flex-shrink: 0;
}
.skeleton-hero-lines {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.skeleton-line {
  height: 14px;
}
.skeleton-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
.skeleton-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
}
.skeleton-bar {
  height: 8px;
  border-radius: 999px;
}
.skeleton-hint {
  text-align: center;
  font-size: 13px;
  color: var(--c-text-tertiary);
}
@media (max-width: 640px) {
  .skeleton-grid {
    grid-template-columns: 1fr;
  }
  .skeleton-circle {
    width: 72px;
    height: 72px;
  }
}

/* ── 结果区 ── */
.result-section {
  margin-top: 40px;
}

.parse-warning {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  margin-bottom: 20px;
  font-size: 13px;
  color: #92400e;
  background: #fef3c7;
  border-radius: var(--radius-md);
  border: 1px solid #fde68a;
}

.warning-icon {
  font-size: 16px;
}

/* ── 综合评分 ── */
.score-hero {
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 32px;
  background: var(--c-surface);
  border-radius: var(--radius-lg);
  border: 1px solid var(--c-border-light);
  box-shadow: var(--shadow-sm);
  margin-bottom: 32px;
}

.score-circle {
  position: relative;
  width: 120px;
  height: 120px;
  flex-shrink: 0;
}

.score-svg {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}

.score-track {
  fill: none;
  stroke: var(--c-bg-alt);
  stroke-width: 8;
}

.score-fill {
  fill: none;
  stroke: var(--score-color, var(--brand-primary));
  stroke-width: 8;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.8s ease;
}

.score-value {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
}

.score-num {
  font-size: 32px;
  font-weight: 800;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
  color: var(--c-accent);
  display: block;
  line-height: 1;
}

.score-unit {
  font-size: 13px;
  color: var(--c-text-tertiary);
}

.score-meta h3 {
  font-size: 18px;
  font-weight: 600;
  font-family: var(--font-serif);
  color: var(--c-text);
  margin: 0 0 4px;
}

.score-meta p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
}

/* ── 一键直达模拟面试（v1.23.2 优化③）── */
.next-action {
  margin: 20px 0 28px;
  padding: 18px 22px;
  background: var(--brand-primary-light);
  border: 1px solid var(--brand-primary);
  border-radius: var(--radius-lg);
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}
.btn-to-interview {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 11px 22px;
  font-size: 14px;
  font-weight: 600;
  font-family: var(--font-sans);
  color: #fff;
  background: var(--brand-primary);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: 0 4px 12px rgba(15, 118, 110, 0.25);
}
.btn-to-interview:hover:not(:disabled) {
  background: var(--brand-primary-hover);
  transform: translateY(-1px);
}
.btn-to-interview:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.next-hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--c-text-secondary);
  line-height: 1.5;
}

/* ── 维度评分 ── */
.block-title {
  font-size: 16px;
  font-weight: 600;
  font-family: var(--font-serif);
  color: var(--c-text);
  margin: 0 0 16px;
}

.dim-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 32px;
}

.dim-card {
  padding: 20px;
  background: var(--c-surface);
  border-radius: var(--radius-md);
  border: 1px solid var(--c-border-light);
}

.dim-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.dim-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text);
}

.dim-score {
  font-size: 16px;
  font-weight: 700;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.dim-bar {
  height: 6px;
  background: var(--c-bg-alt);
  border-radius: 3px;
  overflow: hidden;
  margin-bottom: 10px;
}

.dim-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.6s ease;
}

.dim-suggestion {
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-secondary);
  margin: 0;
}

/* ── 优势 & 建议 ── */
.analysis-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.analysis-card {
  padding: 20px;
  background: var(--c-surface);
  border-radius: var(--radius-md);
  border: 1px solid var(--c-border-light);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}

.card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 700;
}

.strengths-icon {
  background: var(--c-accent-soft);
  color: var(--c-accent);
  border: 1px solid var(--c-accent-line);
}

.improvements-icon {
  background: var(--c-accent-soft);
  color: var(--c-warning);
  border: 1px solid var(--c-accent-line);
}

.card-head h4 {
  font-size: 15px;
  font-weight: 600;
  font-family: var(--font-serif);
  color: var(--c-text);
  margin: 0;
}

.analysis-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.analysis-list li {
  position: relative;
  padding: 6px 0 6px 16px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-secondary);
}

.analysis-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 13px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--c-text-tertiary);
}

.analysis-card.strengths .analysis-list li::before {
  content: '★';
  background: transparent;
  border: none;
  color: var(--c-accent);
  font-size: 11px;
  width: auto;
  height: auto;
  top: 7px;
  left: 1px;
}

.empty-hint {
  font-size: 13px;
  color: var(--c-text-tertiary);
  padding: 8px 0;
}

/* ── 原始返回 ── */
.raw-section {
  margin-top: 8px;
  padding: 14px 18px;
  background: var(--c-surface);
  border-radius: var(--radius-md);
  border: 1px solid var(--c-border-light);
}

.raw-section summary {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-secondary);
  cursor: pointer;
}

.raw-section summary:hover {
  color: var(--c-text);
}

.raw-output {
  margin: 12px 0 0;
  padding: 14px;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.5;
  color: var(--c-text-secondary);
  background: var(--c-bg-alt);
  border-radius: var(--radius-sm);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 400px;
  overflow: auto;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .score-hero {
    flex-direction: column;
    text-align: center;
    gap: 16px;
    padding: 24px 20px;
  }
  .dim-grid {
    grid-template-columns: 1fr;
  }
  .analysis-grid {
    grid-template-columns: 1fr;
  }
  .page-header h1 {
    font-size: 24px;
  }
}

/* ── 优化简历区 ── */
.optimize-section {
  margin-top: 28px;
  padding: 24px;
  background: var(--brand-primary-50);
  border: 1px solid var(--brand-primary-100);
  border-radius: var(--radius-lg);
}

.optimize-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}

.optimize-title {
  font-size: 18px;
  font-weight: 600;
  font-family: var(--font-serif);
  color: var(--c-text);
  margin: 0 0 6px;
}

.optimize-desc {
  font-size: 13px;
  color: var(--c-text-secondary);
  margin: 0;
  max-width: 520px;
  line-height: 1.6;
}

.btn-optimize {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-primary);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
}

.btn-optimize:hover:not(:disabled) {
  background: var(--brand-primary-hover);
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

.btn-optimize:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.optimize-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 40px 20px;
  text-align: center;
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--brand-primary-100);
  border-top-color: var(--brand-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.loading-text {
  font-size: 15px;
  font-weight: 500;
  color: var(--c-text);
}

.loading-hint {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.optimize-result {
  margin-top: 20px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.optimize-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  background: var(--c-bg-alt);
  border-bottom: 1px solid var(--c-border);
  flex-wrap: wrap;
  gap: 12px;
}

.optimize-tabs {
  display: inline-flex;
  gap: 4px;
  background: var(--c-surface);
  padding: 3px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--c-border);
}

.optimize-tabs button {
  padding: 5px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: transparent;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.optimize-tabs button.active {
  background: var(--brand-primary);
  color: #fff;
}

.optimize-downloads {
  display: inline-flex;
  gap: 8px;
  flex-wrap: wrap;
}

.btn-download {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.btn-download:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  background: var(--brand-primary-50);
}

.optimize-preview {
  padding: 32px 40px;
  font-family: var(--font-sans);
  color: var(--c-text);
  line-height: 1.8;
  max-height: 800px;
  overflow-y: auto;
}

.optimize-preview :deep(h1) {
  font-size: 24px;
  font-weight: 700;
  color: var(--brand-primary);
  border-bottom: 2px solid var(--brand-primary-100);
  padding-bottom: 8px;
  margin: 0 0 20px;
}

.optimize-preview :deep(h2) {
  font-size: 18px;
  font-weight: 600;
  color: var(--brand-primary-hover);
  margin: 24px 0 12px;
  border-left: 4px solid var(--brand-primary);
  padding-left: 12px;
}

.optimize-preview :deep(h3) {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
  margin: 16px 0 8px;
}

.optimize-preview :deep(ul) {
  padding-left: 24px;
  margin: 8px 0;
}

.optimize-preview :deep(li) {
  margin: 4px 0;
}

.optimize-preview :deep(strong) {
  color: var(--brand-primary);
  font-weight: 600;
}

.optimize-preview :deep(p) {
  margin: 8px 0;
}

.optimize-source {
  padding: 24px;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--c-text);
  background: var(--c-bg-alt);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 800px;
  overflow-y: auto;
  margin: 0;
}

@media (max-width: 768px) {
  .optimize-head {
    flex-direction: column;
  }
  .btn-optimize {
    width: 100%;
    justify-content: center;
  }
  .optimize-preview {
    padding: 20px;
  }
  .optimize-toolbar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
