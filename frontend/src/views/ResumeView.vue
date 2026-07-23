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
          <input v-model="targetJob" type="text" list="job-suggestions" placeholder="如：Java 后端、产品经理、教师、医生、销售经理…" />
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
      </div>

      <div v-else-if="tab === 'import'" class="import-area">
        <div class="import-tips">
          <p class="import-tip-title">支持的导入方式</p>
          <ul class="import-tip-list">
            <li><strong>在线简历链接</strong>：超级简历、GitHub 主页、个人博客等公开页面</li>
            <li><strong>剪贴板粘贴</strong>：从招聘 App 或其他简历工具复制文本后一键粘贴</li>
            <li><strong>云盘文件</strong>：iOS 点击"上传文件"可从 iCloud Drive / 文件 App 选取</li>
          </ul>
        </div>

        <div class="field-row">
          <label>简历页面 URL</label>
          <input v-model="importUrl" type="url" placeholder="https://your-resume-url.com" />
        </div>
        <button class="btn-import" :disabled="importLoading" @click="importFromUrl">
          <span v-if="importLoading" class="spinner"></span>
          {{ importLoading ? '抓取分析中...' : '从 URL 导入' }}
        </button>

        <div class="import-divider"><span>或</span></div>

        <button class="btn-clipboard" @click="pasteFromClipboard">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path d="M9 2h6a1 1 0 011 1v1h2a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2V3a1 1 0 011-1z"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M9 12h6 M9 16h4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          从剪贴板粘贴
        </button>

        <div class="field-row" style="margin-top: 16px;">
          <label>目标岗位</label>
          <input v-model="targetJob" type="text" list="job-suggestions" placeholder="如：Java 后端、产品经理、教师、医生、销售经理…" />
        </div>
      </div>

      <div v-else class="text-area">
        <div class="field-row">
          <label>简历内容</label>
          <textarea v-model="resumeText" rows="10" placeholder="粘贴你的简历文本..."></textarea>
        </div>
        <div class="field-row">
          <label>目标岗位</label>
          <input v-model="targetJob" type="text" list="job-suggestions" placeholder="如：Java 后端、产品经理、教师、医生、销售经理…" />
        </div>
        <button class="btn-analyze" :disabled="loading" @click="analyzeText">
          <span v-if="loading" class="spinner"></span>
          {{ loading ? '分析中...' : '开始分析' }}
        </button>
      </div>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <div class="loading-card">
        <div class="loading-spinner"></div>
        <div class="loading-text">AI 正在分析你的简历</div>
        <div class="loading-hint">首次调用需冷启动，最长约 1-2 分钟</div>
      </div>
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
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api, { AI_TIMEOUT, getErrMessage } from '../api'
import { repairAndCheck } from '../utils/jsonRepair'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const md = new MarkdownIt({ html: false, linkify: true })

interface AnalysisResult {
  overallScore: number
  dimensions: Array<{ name: string; score: number; suggestion: string }>
  strengths: string[]
  improvements: string[]
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

// 优化简历相关状态
const optimizing = ref(false)
const optimizedMarkdown = ref('')
const optimizeView = ref<'preview' | 'source'>('preview')

const optimizedHtml = computed(() => {
  if (!optimizedMarkdown.value) return ''
  return DOMPurify.sanitize(md.render(optimizedMarkdown.value), {
    FORBID_TAGS: ['style', 'iframe'],
    FORBID_ATTR: ['onerror', 'onload']
  })
})

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
  try {
    JSON.parse(val)
    parseError.value = ''
    return
  } catch (e) {
    // 解析失败，走前端兜底修复
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
    parseError.value = 'AI 返回内容无法解析为标准 JSON，可在下方查看原始返回。错误：' + (e as Error).message
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

/** 评分对应颜色 */
function getScoreColor(score: number): string {
  if (score >= 85) return '#10b981'
  if (score >= 70) return '#3b82f6'
  if (score >= 60) return '#f59e0b'
  return '#ef4444'
}

/** 评分对应渐变 */
function getScoreGradient(score: number): string {
  if (score >= 85) return 'linear-gradient(90deg, #10b981, #34d399)'
  if (score >= 70) return 'linear-gradient(90deg, #3b82f6, #60a5fa)'
  if (score >= 60) return 'linear-gradient(90deg, #f59e0b, #fbbf24)'
  return 'linear-gradient(90deg, #ef4444, #f87171)'
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

.import-tips {
  padding: 16px 20px;
  background: var(--brand-primary-50);
  border: 1px solid var(--brand-primary-100);
  border-radius: var(--radius-md);
}

.import-tip-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--brand-primary);
  margin: 0 0 8px;
}

.import-tip-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.import-tip-list li {
  position: relative;
  padding: 3px 0 3px 16px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-secondary);
}

.import-tip-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 11px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--brand-primary);
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

.btn-clipboard {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 28px;
  font-size: 15px;
  font-weight: 600;
  color: var(--brand-primary);
  background: var(--c-surface);
  border: 2px solid var(--brand-primary);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.btn-clipboard:hover {
  background: var(--brand-primary-50);
  transform: translateY(-1px);
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
  color: var(--c-text);
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
  color: var(--c-text);
  margin: 0 0 4px;
}

.score-meta p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
}

/* ── 维度评分 ── */
.block-title {
  font-size: 16px;
  font-weight: 600;
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
  background: #d1fae5;
  color: #059669;
}

.improvements-icon {
  background: #fef3c7;
  color: #d97706;
}

.card-head h4 {
  font-size: 15px;
  font-weight: 600;
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
