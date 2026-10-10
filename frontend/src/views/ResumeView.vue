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
            <p class="extract-title">把简历拿到这里</p>
            <!-- v1.46.0 重写。旧版给 8 个 App 的「网页版」入口，实测它们落到的是
                 厂商首页/登录页（im.qq.com / dingtalk.com / kdocs.cn / pan.baidu.com…），
                 而副标题承诺「点对应入口直达取件」——用户到站后仍要自己摸索，
                 属于「承诺大于交付」。微信那条更彻底：filehelper 网页版会被它自己拒绝手机端。
                 现在改成：**3 个不依赖任何 App 的常驻通道 + 一份各 App 的真实操作说明**。
                 判断依据见 docs/ux5-scripts/mobile-intake-design.md（设计师实测）。 -->
            <p class="extract-sub">
              手机上最省事的一条路是<b>截图</b>：在任何 App 里打开简历，截一张图，
              回来点「选择相册截图」，系统会自动把图里的文字认出来再分析。
            </p>
          </div>

          <!-- 三个常驻通道：不依赖任何 App 的深链，因此在任何环境（含微信内置浏览器）里都可用 -->
          <div class="extract-actions">
            <button class="btn-pick" type="button" @click="triggerFilePicker">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              选择本机文件
            </button>
            <button class="btn-pick" type="button" @click="triggerAlbumPicker">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <rect x="3" y="5" width="18" height="14" rx="2" stroke="currentColor" stroke-width="2"/>
                <circle cx="9" cy="10" r="1.6" stroke="currentColor" stroke-width="2"/>
                <path d="M4 17l5-5 4 4 3-3 4 4" stroke="currentColor" stroke-width="2"
                  stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              选择相册截图
            </button>
            <button class="btn-clipboard" type="button" @click="pasteFromClipboard">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M9 2h6a1 1 0 011 1v1h2a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2V3a1 1 0 011-1z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M9 12h6 M9 16h4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
              从剪贴板粘贴
            </button>
            <!-- 原生文件选择：桌面打开文件管理器，iOS/Android 打开「文件」App（可从 iCloud / 云盘选取）。
                 v1.46.0 起同时接受图片——截图是手机上从任何 App 取简历的通用出路。 -->
            <input
              ref="nativeFileInput"
              class="hidden-file"
              type="file"
              accept=".pdf,.txt,.html,.htm,.md,.markdown,image/png,image/jpeg,image/webp,image/gif"
              @change="onNativeFile"
            />
            <!-- 相册专用入口：accept="image/*" 在手机上会直接打开相册 -->
            <input
              ref="albumFileInput"
              class="hidden-file"
              type="file"
              accept="image/*"
              @change="onNativeFile"
            />
          </div>

          <details class="extract-guide">
            <summary>各 App 里怎么把简历拿出来</summary>
            <ul>
              <li><b>微信</b>：打开简历 → 右上角「⋯」→ 截图。文件在「文件传输助手」里时，
                手机上打不开它的网页版，需要用电脑浏览器打开下面的地址。</li>
              <li><b>钉钉 / 企业微信</b>：打开文档 → 右上角「⋯」→ 导出为 PDF / 保存到手机，
                再回来点「选择本机文件」。</li>
              <li><b>WPS / 腾讯文档</b>：打开文档 → 分享 → 导出为 PDF → 保存到手机。</li>
              <li><b>BOSS 直聘 / 猎聘等</b>：在线简历通常不给导出，进「我的简历」直接截图最省事。</li>
              <li><b>网盘（百度网盘等）</b>：长按文件 → 下载到手机 → 点「选择本机文件」。</li>
              <li>懒得折腾？把简历正文复制下来，用下面的「从剪贴板粘贴」也行。</li>
            </ul>
            <div class="guide-copy">
              <button class="guide-copy-btn" type="button" @click="copyFilehelper">
                {{ COPY_LINK_LABEL }}
              </button>
              <!-- 复制失败时必须把地址渲染出来，否则「请长按下方链接」指向一个不存在的元素 -->
              <!-- F-04：保留 <code>（长按选中的唯一交互），按 DESIGN.md 5.5 补键盘可达属性 -->
              <code
                v-if="copyFailed"
                class="ex-url"
                role="button"
                tabindex="0"
                @click="copyFilehelper"
                @keydown.enter="copyFilehelper"
                @keydown.space.prevent="copyFilehelper"
              >
                https://filehelper.weixin.qq.com/
              </code>
            </div>
          </details>

          <p class="extract-note">
            截图识别会把图片里的文字提取出来再分析。长简历可能只识别到一部分，
            建议分两张截图，或直接用原始文件上传。
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
            <span class="score-num">{{ parsed.overallScore ?? EMPTY }}</span>
            <!-- 无评分时不再拼「分」单位，避免出现「— 分」这种占位符+单位的错误渲染 -->
            <span v-if="parsed.overallScore != null" class="score-unit">分</span>
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
              <!-- v1.65.1：判空 —— 原来 `{{ d.score }}分` 在 score 缺失时会渲染出一个孤零零的「分」 -->
              <span class="dim-score" :style="{ color: getScoreColor(d.score) }">{{ d.score != null ? `${d.score}分` : EMPTY }}</span>
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
                  stroke="var(--c-accent)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
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
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api, { AI_TIMEOUT, getErrMessage } from '../api'
import { repairAndCheck } from '../utils/jsonRepair'
import { getScoreColor, getScoreGradient } from '../utils/score'
import { EMPTY } from '../utils/format'
import { JOB_SUGGESTIONS } from '../utils/jobOptions'
import {
  COPY_LINK_LABEL,
  copyResultHint,
  copyText,
  detectMobile,
} from '../utils/appLaunch'
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
   取件区（v1.46.0 重写）

   旧版给 8 个 App 的「网页版」入口，实测它们落到的是厂商首页/登录页
   （im.qq.com / dingtalk.com / kdocs.cn / pan.baidu.com…），而副标题承诺
   「点对应入口直达取件」——用户到站后仍要自己摸索，属于「承诺大于交付」。
   微信那条更彻底：filehelper 的网页版会被它自己拒绝手机端。

   现在只保留**不依赖任何 App 深链**的三条通道（本机文件 / 相册截图 / 剪贴板），
   外加一份「各 App 里怎么把简历拿出来」的真实操作说明。
   ───────────────────────────────────────────────────────────── */

const nativeFileInput = ref<HTMLInputElement | null>(null)
/** 相册专用入口：`accept="image/*"` 在手机上会直接打开相册 */
const albumFileInput = ref<HTMLInputElement | null>(null)

/**
 * 环境判定（v1.44.1）：在 ref 初始化时求值，避免首帧按桌面分支渲染。
 * 目前只用于按端调整提示文案。
 */
const isMobile = ref(detectMobile())

/**
 * 「文件传输助手」地址复制失败（v1.44.1）。
 * 复制失败时必须把地址渲染成可选中的文本，否则 `copyResultHint(false)` 那句
 * 「请长按下方链接」会指向一个页面上不存在的元素。
 */
const copyFailed = ref(false)

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

/** 触发相册选择：手机上直接打开相册，桌面端退化为普通文件选择器 */
function triggerAlbumPicker() {
  albumFileInput.value?.click()
}

/**
 * 复制「文件传输助手」的电脑版地址。
 *
 * 微信「文件传输助手」的网页版**只在电脑浏览器可用**，手机打开会被它自己拒绝。
 * 所以这里只提供「复制地址」——让用户在电脑上打开，而不是给一个手机点不开的链接。
 */
async function copyFilehelper() {
  const ok = await copyText('https://filehelper.weixin.qq.com/')
  copyFailed.value = !ok
  if (ok) ElMessage.success(copyResultHint(true))
  else ElMessage.warning(copyResultHint(false))
}

// 环境判定（isMobile）已在 ref 初始化时求值（v1.44.1），不再放 onMounted：
// 那会让首帧按桌面分支渲染，手机上先画出桌面端才有的提示。

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
  return EMPTY
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
  font-family: var(--font-display);
  color: var(--c-text);
  margin: 0 0 6px;
  letter-spacing: -0.5px;
}

.page-header p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
}

/* ── Tab 切换（模式切换型）──
 * ⚠️ 与 JobsView 的 `.filter-chips` 是**两种不同语义**，刻意长得不一样：
 * 这里是「模式切换」（上传 / 导入 / 粘贴，互斥、无计数）→ 分段控件；
 * 那里是「带计数的筛选行」→ 独立胶囊按钮。
 * v1.64.1 之前两者共用 `.tab-switch` 这个类名，看代码像同一组件被写歪了 —— 已改名区分。 */
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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
  transition: transform var(--transition-fast);
}

/* 按下时轻微下沉，模拟物理按键（此前完全没有按下反馈） */
.ex-main:active {
  transform: scale(0.985);
}

/* 唤起中：左侧图标轻微呼吸，避免用户以为点击没生效而反复点 */
.ex-main.launching .ex-ico {
  animation: pulse 1.2s ease-in-out infinite;
}

.ex-main.launching .ex-enter {
  opacity: 1;
  color: var(--brand-primary);
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
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

/* 「网页版仅电脑可用」标记：提前告知能力边界，避免用户点进去才发现是死路。
   用 warning 语义令牌而非写死颜色，深色主题下自动跟随。 */
.ex-flag {
  flex-shrink: 0;
  padding: 1px 5px;
  font-size: 10.5px;
  font-style: normal;
  font-weight: 600;
  line-height: 1.5;
  color: var(--c-warning);
  background: var(--c-warning-light);
  border-radius: var(--radius-sm);
  white-space: nowrap;
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

/* 网页版入口（常驻，见模板注释）：
   默认是安静的次级文本链接，不抢主按钮的注意力；
   唤起未交棒成功的那一张升级为实色徽标，把用户直接引到这条路上。 */
.ex-fallback {
  display: block;
  margin: 0 12px 8px;
  padding: 8px 0 2px;
  font-size: 12.5px;
  font-weight: 500;
  /* v1.44.1：原来用 --c-text-tertiary（#78716c）压在 --c-bg-alt 上只有 4.40:1，
     低于 WCAG AA 的 4.5:1。改用 secondary 令牌，对比度约 9:1，且随主题切换。 */
  color: var(--c-text-secondary);
  text-decoration: none;
  transition: color var(--transition-fast), background-color var(--transition-fast);
}

.ex-fallback:hover {
  color: var(--brand-primary);
}

.ex-fallback.prominent {
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-primary);
  background: var(--brand-primary-50);
  border-radius: var(--radius-sm);
}

.ex-fallback.prominent:hover {
  background: var(--brand-primary-100);
}

/* 「复制链接」形态（v1.44.0）：手机上打不开的网页版，用复制代替打开。
   视觉与 <a> 形态的 ex-fallback 保持一致，避免同一位置出现两种气质。

   v1.44.1：实测可点高度只有 19px（移动端建议 ≥44px，本项目按 36px 起步），
   且它是这一屏**唯一能走通**的入口 —— 手指点不中等于又一条死路。
   改成 flex + min-height，并让整个宽度都可点。 */
.ex-fallback-btn {
  display: flex;
  align-items: center;
  width: calc(100% - 24px);
  min-height: 36px;
  text-align: left;
  font-family: var(--font-sans);
  background: transparent;
  border: none;
  cursor: pointer;
}

.ex-fallback-btn.prominent {
  justify-content: center;
  text-align: center;
}

/* 复制失败时暴露出的原始地址（v1.44.1）。
   提示语让用户「长按下方链接手动复制」，这里就是那个可长按的元素 —— 缺了它，
   整条出路在复制失败时就断掉了。 */
.ex-url {
  display: block;
  margin: 0 12px 8px;
  padding: 6px 8px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--c-text-secondary);
  background: var(--c-bg-alt);
  border-radius: var(--radius-sm);
  word-break: break-all;
  /* 允许长按选中：这是「手动复制」这条路的唯一交互 */
  user-select: all;
  -webkit-user-select: all;
  cursor: text;
}
/* F-04：键盘可达的可见焦点环 */
.ex-url:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

/* 各 App 取件说明（v1.46.0）。
   取代了原先 8 张「网页版直达」卡片——那些卡片实测落到厂商首页/登录页，
   与「直达取件」的承诺不符。说明用 <details> 折叠，不占首屏。 */
.extract-guide {
  margin: 12px 12px 0;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  background: var(--c-bg-alt);
  font-size: 12.5px;
}

.extract-guide summary {
  padding: 11px 12px;
  font-weight: 600;
  color: var(--c-text);
  cursor: pointer;
  list-style: none;
}

.extract-guide summary::-webkit-details-marker {
  display: none;
}

.extract-guide summary::before {
  content: '▸';
  display: inline-block;
  margin-right: 6px;
  transition: transform var(--transition-fast);
}

.extract-guide[open] summary::before {
  transform: rotate(90deg);
}

.extract-guide ul {
  margin: 0;
  padding: 0 14px 8px 28px;
  color: var(--c-text-secondary);
  line-height: 1.8;
}

.extract-guide li {
  margin-bottom: 4px;
}

.guide-copy {
  padding: 0 12px 12px;
}

/* 可点高度按移动端标准给足：这是手机上唯一能走通的那条路的入口 */
.guide-copy-btn {
  display: block;
  width: 100%;
  min-height: 36px;
  padding: 6px 0;
  font-family: var(--font-sans);
  font-size: 12.5px;
  font-weight: 600;
  text-align: left;
  color: var(--brand-primary);
  background: transparent;
  border: none;
  cursor: pointer;
}

.guide-copy-btn:hover {
  text-decoration: underline;
}

/* 复制失败时暴露出的原始地址：让「请长按下方链接」这句指令可执行 */
.ex-url {
  display: block;
  margin: 6px 0 0;
  padding: 6px 8px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--c-text-secondary);
  background: var(--c-surface);
  border-radius: var(--radius-sm);
  word-break: break-all;
  user-select: all;
  -webkit-user-select: all;
  cursor: text;
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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
  color: var(--c-on-primary);
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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
  /* R10-F09：随主题翻转（同 AdminView 的 .spinner） */
  border: 2px solid color-mix(in srgb, currentColor 40%, transparent);
  border-top-color: currentColor;
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
  color: var(--c-warning);
  background: var(--c-warning-light);
  border-radius: var(--radius-md);
  border: 1px solid var(--c-warning);
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
  font-family: var(--font-title);
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
  color: var(--c-on-primary);
  background: var(--brand-primary);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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
  font-family: var(--font-title);
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
  font-family: var(--font-title);
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
  font-family: var(--font-title);
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
  color: var(--c-on-primary);
  background: var(--brand-primary);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
}

.optimize-tabs button.active {
  background: var(--brand-primary);
  color: var(--c-on-primary);
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
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
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
