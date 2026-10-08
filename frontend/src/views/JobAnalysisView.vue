<template>
  <div class="job-page">
    <header class="page-header">
      <h1>岗位分析</h1>
      <p>深度拆解 JD、诊断简历匹配度、一键生成求职信</p>
    </header>

    <!-- Tab 切换 -->
    <div class="tab-switch">
      <button :class="{ active: tab === 'analyze' }" @click="tab = 'analyze'">JD 岗位分析</button>
      <button :class="{ active: tab === 'gap' }" @click="tab = 'gap'">差距诊断</button>
      <button :class="{ active: tab === 'letter' }" @click="tab = 'letter'">求职信生成</button>
    </div>

    <!-- Tab 1: JD 岗位分析 -->
    <div v-if="tab === 'analyze'" class="tab-panel">
      <div class="input-card">
        <label>粘贴岗位描述（JD）</label>

        <!-- v1.58.0：从岗位链接导入。放在 textarea 之上——
             它只是「更快地填满下面那个框」，不是一条独立的替代路径。 -->
        <div class="url-import">
          <BaseInput
            v-model="jdUrl"
            aria-label="岗位链接"
            placeholder="或粘贴岗位链接（https://...），自动抓取 JD 正文"
            @keyup.enter="importFromUrl"
          />
          <BaseButton
            variant="ghost"
            :loading="urlLoading"
            :disabled="urlLoading || !jdUrl.trim()"
            @click="importFromUrl"
          >
            {{ urlLoading ? '抓取中...' : '从链接导入' }}
          </BaseButton>
        </div>
        <p class="url-hint">
          支持 <strong>Ashby / Greenhouse</strong> 托管的岗位页（很多科技公司用它们发布官方岗位，
          如 <code>jobs.ashbyhq.com/…</code>）。这类页面能从官方公开接口读到完整 JD 原文。
          BOSS直聘、拉勾、牛客等 App 内页面为动态渲染，服务端读不到正文——
          <strong>请直接复制正文粘贴到下方输入框</strong>，不要在这里白试。
        </p>
        <div v-if="importError" class="import-error">
          <span class="import-error-text">{{ importError }}</span>
          <button type="button" class="import-error-close" aria-label="关闭提示" @click="importError = ''">×</button>
        </div>
        <div v-if="importedFrom" class="imported-badge">
          已从链接导入
          <span v-if="importSource === 'Ashby' || importSource === 'Greenhouse'" class="badge-source">
            · {{ importSource }} 官方接口（原文）
          </span>
          <span v-if="importedTitle">· {{ importedTitle }}</span>
          <span v-if="importedCompany">@ {{ importedCompany }}</span>
        </div>

        <BaseTextarea v-model="jdText" :rows="10" placeholder="把招聘网站上的岗位描述全文粘贴到这里..." />
        <BaseButton variant="gradient" :loading="loading" :disabled="loading" @click="analyzeJd">
          {{ loading ? '分析中...' : '开始分析' }}
        </BaseButton>
      </div>

      <div v-if="loading" class="loading-state">
        <div class="loading-card">
          <div class="loading-spinner"></div>
          <div class="loading-text">AI 正在拆解岗位要求</div>
          <div class="loading-hint">首次调用需冷启动，最长约 30-60 秒</div>
        </div>
      </div>

      <div v-if="jdResult && !loading" class="result-section fade-in-up">
        <div class="result-summary">
          <h3>{{ jdResult.jobTitle || '岗位分析' }}</h3>
          <p>{{ jdResult.summary }}</p>
          <div class="meta-tags">
            <span v-if="jdResult.seniorityLevel" class="tag tag-info">{{ jdResult.seniorityLevel }}</span>
            <span v-if="jdResult.salaryRange && jdResult.salaryRange !== '未知'" class="tag tag-success">{{ jdResult.salaryRange }}</span>
          </div>
        </div>

        <div v-if="jdResult.responsibilities?.length" class="block">
          <h4 class="block-title">核心职责</h4>
          <ul class="list-card">
            <li v-for="(r, i) in jdResult.responsibilities" :key="i">{{ r }}</li>
          </ul>
        </div>

        <div class="block-grid">
          <div v-if="jdResult.hardSkills?.length" class="block">
            <h4 class="block-title">硬技能要求</h4>
            <div class="skill-tags">
              <span v-for="(s, i) in jdResult.hardSkills" :key="i" class="tag tag-hard">{{ s }}</span>
            </div>
          </div>
          <div v-if="jdResult.softSkills?.length" class="block">
            <h4 class="block-title">软技能要求</h4>
            <div class="skill-tags">
              <span v-for="(s, i) in jdResult.softSkills" :key="i" class="tag tag-soft">{{ s }}</span>
            </div>
          </div>
        </div>

        <div v-if="jdResult.hiddenRequirements?.length" class="block">
          <h4 class="block-title">隐性条件（JD 没明说但 HR 会看）</h4>
          <ul class="list-card list-warning">
            <li v-for="(r, i) in jdResult.hiddenRequirements" :key="i">{{ r }}</li>
          </ul>
        </div>

        <div v-if="jdResult.keywords?.length" class="block">
          <h4 class="block-title">ATS 关键词</h4>
          <div class="skill-tags">
            <span v-for="(k, i) in jdResult.keywords" :key="i" class="tag tag-keyword">{{ k }}</span>
          </div>
        </div>

        <div v-if="jdResult.matchTips?.length" class="block block-tips">
          <h4 class="block-title">简历优化建议</h4>
          <ul class="list-card list-success">
            <li v-for="(t, i) in jdResult.matchTips" :key="i">{{ t }}</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Tab 2: 差距诊断 -->
    <div v-if="tab === 'gap'" class="tab-panel">
      <div class="input-card">
        <div class="field-row">
          <label>岗位描述（JD）</label>
          <BaseTextarea v-model="jdText" :rows="6" placeholder="粘贴岗位描述..." />
        </div>
        <div class="field-row">
          <label>你的简历</label>
          <div class="resume-input-group">
            <BaseTextarea v-model="resumeText" :rows="8" placeholder="粘贴简历内容，或点击下方按钮上传简历文件..." />
            <div class="resume-upload-bar">
              <el-upload accept=".pdf,.txt,.html,.htm,.md,.markdown,application/pdf,text/plain,text/html,text/markdown"
                :before-upload="handleResumeUpload" :show-file-list="false" :http-request="() => {}">
                <button class="btn-upload-resume" type="button">
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M17 8l-5-5-5 5 M12 3v12"
                      stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                  上传简历文件
                </button>
              </el-upload>
              <span v-if="resumeFileName" class="resume-file-name">{{ resumeFileName }}</span>
              <button v-if="resumeFileName" class="btn-clear-resume" @click="clearResumeFile">清除</button>
            </div>
          </div>
        </div>
        <BaseButton variant="gradient" :loading="gapLoading" :disabled="gapLoading" @click="diagnoseGap">
          {{ gapLoading ? '诊断中...' : '开始诊断' }}
        </BaseButton>
      </div>

      <div v-if="gapLoading" class="loading-state">
        <div class="loading-card">
          <div class="loading-spinner"></div>
          <div class="loading-text">AI 正在逐条对比简历与岗位</div>
          <div class="loading-hint">分析需要 20-40 秒，请耐心等待</div>
        </div>
      </div>

      <div v-if="gapResult && !gapLoading" class="result-section fade-in-up">
        <div class="match-score-hero" :style="{ '--score-color': getScoreColor(gapResult.overallMatchScore, MATCH_THRESHOLDS) }">
          <div class="match-score-num">{{ gapResult.overallMatchScore ?? EMPTY }}</div>
          <div class="match-score-label">综合匹配度</div>
          <p class="match-summary">{{ gapResult.summary }}</p>
        </div>

        <div v-if="gapResult.items?.length" class="block">
          <h4 class="block-title">逐条诊断</h4>
          <div v-for="(item, i) in gapResult.items" :key="i" class="gap-item" :class="gapStatusClass(item.status)">
            <div class="gap-status-icon">
              <svg v-if="item.status === 'strong'" width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M5 12l5 5L20 7" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <svg v-else-if="item.status === 'weak'" width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 9v4 M12 17h.01" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/>
                <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M6 6l12 12 M18 6L6 18" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/>
              </svg>
            </div>
            <div class="gap-content">
              <div class="gap-requirement">{{ item.requirement }}</div>
              <div v-if="item.evidence" class="gap-evidence">证据：{{ item.evidence }}</div>
              <div v-if="item.suggestion" class="gap-suggestion">建议：{{ item.suggestion }}</div>
            </div>
          </div>
        </div>

        <div class="block-grid">
          <div v-if="gapResult.strengths?.length" class="block">
            <h4 class="block-title">核心优势</h4>
            <ul class="list-card list-success">
              <li v-for="(s, i) in gapResult.strengths" :key="i">{{ s }}</li>
            </ul>
          </div>
          <div v-if="gapResult.gaps?.length" class="block">
            <h4 class="block-title">需补充的缺口</h4>
            <ul class="list-card list-danger">
              <li v-for="(g, i) in gapResult.gaps" :key="i">{{ g }}</li>
            </ul>
          </div>
        </div>

        <div v-if="gapResult.actionItems?.length" class="block block-tips">
          <h4 class="block-title">下一步行动</h4>
          <ul class="list-card list-info">
            <li v-for="(a, i) in gapResult.actionItems" :key="i">{{ a }}</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Tab 3: 求职信生成 -->
    <div v-if="tab === 'letter'" class="tab-panel">
      <div class="input-card">
        <div class="field-row">
          <label>岗位描述（JD）</label>
          <BaseTextarea v-model="jdText" :rows="6" placeholder="粘贴岗位描述..." />
        </div>
        <div class="field-row">
          <label>你的简历</label>
          <BaseTextarea v-model="resumeText" :rows="8" placeholder="粘贴简历内容..." />
        </div>
        <div class="field-row">
          <label>生成类型</label>
          <div class="letter-type-switch">
            <button :class="{ active: letterType === 'coverLetter' }" @click="letterType = 'coverLetter'">求职信</button>
            <button :class="{ active: letterType === 'email' }" @click="letterType = 'email'">申请邮件</button>
            <button :class="{ active: letterType === 'referral' }" @click="letterType = 'referral'">内推私信</button>
          </div>
        </div>
        <BaseButton variant="gradient" :loading="letterLoading" :disabled="letterLoading" @click="generateLetter">
          {{ letterLoading ? '生成中...' : '生成' }}
        </BaseButton>
      </div>

      <div v-if="letterLoading" class="loading-state">
        <div class="loading-card">
          <div class="loading-spinner"></div>
          <div class="loading-text">AI 正在撰写{{ letterTypeLabel }}</div>
          <div class="loading-hint">生成需要 15-30 秒</div>
        </div>
      </div>

      <div v-if="letterResult && !letterLoading" class="result-section fade-in-up">
        <div class="letter-toolbar">
          <h4 class="block-title">{{ letterTypeLabel }}预览</h4>
          <div class="letter-actions">
            <BaseButton variant="ghost" size="sm" @click="copyLetter">复制</BaseButton>
            <BaseButton variant="ghost" size="sm" @click="downloadLetter">下载 .md</BaseButton>
          </div>
        </div>
        <div class="letter-preview" v-html="letterHtml"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import api, { AI_TIMEOUT, getErrMessage } from '../api'
import { repairAndCheck } from '../utils/jsonRepair'
import { EMPTY } from '../utils/format'
import { getScoreColor, MATCH_THRESHOLDS } from '../utils/score'
import renderMarkdown from '../utils/markdown'
import { BaseButton, BaseTextarea, BaseInput } from '../components'

// ── 公共状态 ──
const tab = ref<'analyze' | 'gap' | 'letter'>('analyze')
const jdText = ref('')
const resumeText = ref('')

// ── Tab 1: JD 分析 ──
const loading = ref(false)
const jdResult = ref<Record<string, any> | null>(null)

// ── Tab 1: 从链接导入（v1.58.0） ──
const jdUrl = ref('')
const urlLoading = ref(false)
/** 导入成功后展示来源，让用户知道这段 JD 是从哪个链接来的（可回溯、可核对） */
const importedFrom = ref('')
const importedTitle = ref('')
const importedCompany = ref('')
/**
 * 导入失败的常驻提示。不只是弹个 toast —— 抓取失败时用户需要**照着做**（改用复制粘贴），
 * 而 toast 几秒就没了。这里把它留在页面上，直到下一次尝试。
 */
const importError = ref('')
/** 数据来源标识（Ashby / Greenhouse / web）—— 让用户知道这段 JD 是怎么来的、可信度如何 */
const importSource = ref('')

// ── Tab 2: 差距诊断 ──
const gapLoading = ref(false)
const gapResult = ref<Record<string, any> | null>(null)

// ── Tab 3: 求职信 ──
const letterLoading = ref(false)
const letterType = ref<'coverLetter' | 'email' | 'referral'>('coverLetter')
const letterResult = ref('')
const letterHtml = computed(() => renderMarkdown(letterResult.value))
const letterTypeLabel = computed(() => {
  if (letterType.value === 'email') return '申请邮件'
  if (letterType.value === 'referral') return '内推私信'
  return '求职信'
})

/** JSON 安全解析 */
function safeParse<T>(str: string, fallback: T): T {
  try { return JSON.parse(str) as T } catch { return fallback }
}

/** JSON 修复解析（后端返回的 JSON 可能不标准） */
function repairAndParse<T>(str: string, fallback: T): T {
  try { return JSON.parse(str) as T } catch {
    const { repaired, valid } = repairAndCheck(str)
    if (valid) {
      try { return JSON.parse(repaired) as T } catch { /* ignore */ }
    }
    return fallback
  }
}

// ── 简历上传（差距诊断用） ──
const resumeFileName = ref('')

/** 上传简历文件，解析为纯文本填入 textarea */
async function handleResumeUpload(file: File) {
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 10MB')
    return false
  }
  const allowed = ['.pdf', '.txt', '.html', '.htm', '.md', '.markdown']
  const ext = file.name.toLowerCase().match(/\.[^.]+$/)?.[0] || ''
  if (!allowed.includes(ext)) {
    ElMessage.error('仅支持 PDF / HTML / MD / TXT 格式')
    return false
  }

  // 上传到后端解析为纯文本（复用 /api/resume/upload 但仅取 resumeText）
  const form = new FormData()
  form.append('file', file)
  form.append('targetJob', '通用岗位')
  try {
    ElMessage.info('正在解析简历文件...')
    const data = await api.post('/api/resume/upload', form, { timeout: AI_TIMEOUT }) as unknown as { resumeText?: string; analysis?: string }
    if (data?.resumeText) {
      resumeText.value = data.resumeText
      resumeFileName.value = file.name
      ElMessage.success(`已加载：${file.name}`)
    } else {
      ElMessage.error('简历解析失败，请尝试直接粘贴文本')
    }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '上传失败，请尝试直接粘贴文本'))
  }
  return false
}

/** 清除上传的简历文件 */
function clearResumeFile() {
  resumeText.value = ''
  resumeFileName.value = ''
}

// ── Tab 1: JD 分析 ──

/**
 * 从岗位链接导入 JD（v1.58.0）。
 *
 * 成功只做一件事：**把 JD 正文回填进输入框**，不自动开始分析。
 * 理由：抓取结果天然含噪声，自动分析会让用户来不及核对就拿到基于脏文本的结论；
 * 回填后由用户确认/编辑，再点「开始分析」——这是本项目一贯的「不替用户下判断」。
 */
async function importFromUrl() {
  const url = jdUrl.value.trim()
  if (!url) return ElMessage.warning('请粘贴岗位链接')
  urlLoading.value = true
  try {
    const data = await api.post('/api/job/import-url',
      { url },
      { timeout: AI_TIMEOUT }) as unknown as {
        extracted: string | Record<string, string>
        sourceUrl: string
        pageText: string
        source?: string
        location?: string
      }

    // 后端两条路径返回形态不同，这里统一成对象：
    // - ATS 路径（Ashby/Greenhouse）：extracted 已是结构化对象，字段直接取自官方公开接口
    // - HTML 抓取路径：extracted 是 AI 返回的 JSON 字符串，需修复 + 解析
    const extracted: Record<string, string> =
      typeof data?.extracted === 'string'
        ? repairAndParse<Record<string, string>>(data.extracted, {})
        : (data?.extracted ?? {})

    // 优先用 JD 正文；它为空时回退到原始抓取正文（用户仍拿到可编辑的文本）
    const jd = (extracted.jobDescription || '').trim()
    const text = jd || (data?.pageText || '').trim()
    if (!text) {
      ElMessage.warning('已获取页面，但没有识别出岗位正文，请手动复制粘贴')
      return
    }

    jdText.value = text
    importedFrom.value = data?.sourceUrl || url
    // 岗位名/公司可能确实为空（页面上没写）——此时留空，不编造
    importedTitle.value = (extracted.jobTitle || '').trim()
    importedCompany.value = (extracted.company || '').trim()
    importSource.value = data?.source || ''
    importError.value = ''

    const who = [importedCompany.value, importedTitle.value].filter(Boolean).join(' · ')
    const via = importSource.value === 'Ashby' || importSource.value === 'Greenhouse'
      ? `（来自 ${importSource.value} 官方接口）`
      : ''
    ElMessage.success(who ? `已导入${via}：${who}` : `已导入岗位正文${via}，请核对后点击「开始分析」`)
  } catch (e: unknown) {
    // 后端已把「抓不到」细分出 reason（需要登录 / 动态渲染 / 反爬 / 链接贴错）。
    // toast 一闪而过，用户来不及照着做 —— 因此同时把原因落成页面上的常驻提示。
    importError.value = getErrMessage(e, '导入失败，请改为手动复制粘贴')
    ElMessage.error(importError.value)
  } finally {
    urlLoading.value = false
  }
}

async function analyzeJd() {
  if (!jdText.value.trim()) return ElMessage.warning('请粘贴岗位描述')
  loading.value = true
  jdResult.value = null
  try {
    const data = await api.post('/api/job/analyze',
      { jobDescription: jdText.value },
      { timeout: AI_TIMEOUT }) as unknown as string
    jdResult.value = repairAndParse<Record<string, any>>(data, { error: '解析失败' })
    if (jdResult.value?.error) ElMessage.warning('AI 返回内容解析失败，请重试')
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '分析失败'))
  } finally { loading.value = false }
}

// ── Tab 2: 差距诊断 ──
async function diagnoseGap() {
  if (!jdText.value.trim()) return ElMessage.warning('请粘贴岗位描述')
  if (!resumeText.value.trim()) return ElMessage.warning('请粘贴简历内容')
  gapLoading.value = true
  gapResult.value = null
  try {
    const data = await api.post('/api/job/gap',
      { resumeText: resumeText.value, jobDescription: jdText.value },
      { timeout: AI_TIMEOUT }) as unknown as string
    gapResult.value = repairAndParse<Record<string, any>>(data, { error: '解析失败' })
    if (gapResult.value?.error) ElMessage.warning('AI 返回内容解析失败，请重试')
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '诊断失败'))
  } finally { gapLoading.value = false }
}

// ── Tab 3: 求职信 ──
async function generateLetter() {
  if (!jdText.value.trim()) return ElMessage.warning('请粘贴岗位描述')
  if (!resumeText.value.trim()) return ElMessage.warning('请粘贴简历内容')
  letterLoading.value = true
  letterResult.value = ''
  try {
    const data = await api.post('/api/job/letter',
      { resumeText: resumeText.value, jobDescription: jdText.value, type: letterType.value },
      { timeout: AI_TIMEOUT }) as unknown as string
    letterResult.value = typeof data === 'string' ? data : String(data)
    ElMessage.success(`${letterTypeLabel.value}已生成`)
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '生成失败'))
  } finally { letterLoading.value = false }
}

async function copyLetter() {
  if (!letterResult.value) return
  try {
    await navigator.clipboard.writeText(letterResult.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制')
  }
}

function downloadLetter() {
  if (!letterResult.value) return
  const blob = new Blob([letterResult.value], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${letterTypeLabel.value}-${formatDate()}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function formatDate() {
  const d = new Date()
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
}

// ── 工具函数 ──
function gapStatusClass(status: string): string {
  if (status === 'strong') return 'gap-strong'
  if (status === 'weak') return 'gap-weak'
  return 'gap-missing'
}
</script>

<style scoped>
.job-page {
  max-width: 900px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 28px;
}

.page-header h1 {
  font-size: 28px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 6px;
  letter-spacing: -0.5px;
  font-family: var(--font-display);
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
  padding: 8px 18px;
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

/* ── 输入区 ── */
.input-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 28px;
  box-shadow: var(--shadow-sm);
  margin-bottom: 24px;
}

.input-card label,
.field-row label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
  margin-bottom: 8px;
}

.input-card textarea,
.field-row textarea {
  width: 100%;
  padding: 12px 14px;
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
  box-sizing: border-box;
}

.input-card textarea:focus,
.field-row textarea:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.12);
}

/* ── 从链接导入（v1.58.0） ── */

.url-import {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}

/* 输入框占满剩余宽度；按钮保持自然宽度不被挤压 */
.url-import :deep(.base-input),
.url-import > :first-child {
  flex: 1;
  min-width: 0;
}

.url-import > :last-child {
  flex: 0 0 auto;
}

.url-hint {
  margin: 0 0 14px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-tertiary);
}

.url-hint strong {
  font-weight: 600;
  color: var(--c-text-secondary);
}

/* 导入成功后的来源标记：让用户能确认「这段 JD 是从哪来的」 */
.imported-badge {
  display: inline-block;
  margin-bottom: 12px;
  padding: 4px 10px;
  font-size: 12px;
  color: var(--c-info);
  background: var(--c-info-light);
  border: 1px solid var(--c-info-border);
  border-radius: var(--radius-sm);
}

/* 数据来源标识（Ashby/Greenhouse 官方接口）——强于普通说明，因为它是「原文可信」的信号 */
.badge-source {
  font-weight: 600;
}

/* 导入失败的常驻提示：比 toast 活得久，用户能照着步骤做 */
.import-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-secondary);
  background: var(--c-warning-light, rgba(255, 176, 32, 0.1));
  border: 1px solid var(--c-warning-border, rgba(255, 176, 32, 0.3));
  border-radius: var(--radius-sm);
}

.import-error-text {
  flex: 1;
}

.import-error-close {
  flex: 0 0 auto;
  padding: 0 2px;
  font-size: 14px;
  line-height: 1;
  color: inherit;
  background: none;
  border: none;
  cursor: pointer;
  opacity: 0.6;
}

.import-error-close:hover {
  opacity: 1;
}

/* 窄屏下按钮换行，避免挤压输入框到不可用 */
@media (max-width: 640px) {
  .url-import {
    flex-direction: column;
    align-items: stretch;
  }
}

/* ── 简历上传组合（差距诊断用） ── */
.resume-input-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.resume-upload-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-upload-resume {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 500;
  color: var(--brand-primary);
  background: var(--brand-primary-50);
  border: 1px solid var(--brand-primary-100);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.btn-upload-resume:hover {
  background: var(--brand-primary-100);
}

.resume-file-name {
  font-size: 13px;
  color: var(--c-text-secondary);
  background: var(--c-bg-alt);
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.btn-clear-resume {
  padding: 4px 10px;
  font-size: 12px;
  color: var(--c-text-tertiary);
  background: transparent;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.btn-clear-resume:hover {
  color: var(--c-danger);
  border-color: var(--c-danger);
}

.input-card textarea::placeholder,
.field-row textarea::placeholder {
  color: var(--c-text-tertiary);
}

.field-row {
  margin-bottom: 18px;
}

.btn-primary {
  margin-top: 16px;
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

.btn-ghost {
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.btn-ghost:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  background: var(--brand-primary-50);
}

.btn-sm {
  padding: 6px 12px;
  font-size: 12px;
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
  margin-top: 24px;
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
  margin-top: 32px;
}

.result-summary {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 24px;
  box-shadow: var(--shadow-sm);
  margin-bottom: 20px;
}

.result-summary h3 {
  font-size: 20px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 8px;
  font-family: var(--font-title);
}

.result-summary p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0 0 12px;
  line-height: 1.6;
}

.meta-tags {
  display: flex;
  gap: 8px;
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
  border: 1px solid transparent;
}

.tag-info {
  background: var(--brand-primary-50);
  color: var(--brand-primary);
  border: 1px solid var(--brand-primary-100);
}

.tag-success {
  background: var(--c-accent-soft);
  color: var(--c-accent);
  border: 1px solid var(--c-accent-line);
}

.tag-hard {
  background: var(--brand-primary-50);
  color: var(--brand-primary);
  border: 1px solid var(--brand-primary-100);
}

.tag-soft {
  background: var(--c-accent-soft);
  color: var(--c-accent);
  border: 1px solid var(--c-accent-line);
}

.tag-keyword {
  background: var(--brand-primary-50);
  color: var(--brand-primary);
  border: 1px solid var(--brand-primary-100);
}

/* ── 内容块 ── */
.block {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 20px 24px;
  box-shadow: var(--shadow-sm);
  margin-bottom: 16px;
}

.block-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.block-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 14px;
  font-family: var(--font-title);
}

.block-tips {
  background: var(--c-accent-soft);
  border-color: var(--c-accent-line);
}

.list-card {
  list-style: none;
  padding: 0;
  margin: 0;
}

.list-card li {
  position: relative;
  padding: 6px 0 6px 18px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--c-text-secondary);
}

.list-card li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 13px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--c-text-tertiary);
}

.list-success li::before { background: var(--c-accent); }
.list-warning li::before { background: var(--c-warning); }
.list-danger li::before { background: var(--c-danger); }
.list-info li::before { background: var(--brand-primary); }

.skill-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* ── 匹配度评分 ── */
.match-score-hero {
  text-align: center;
  padding: 30px 32px 32px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-top: 3px solid var(--c-accent);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  margin-bottom: 20px;
}

.match-score-num {
  font-size: 48px;
  font-weight: 700;
  color: var(--score-color, var(--brand-primary));
  line-height: 1;
  margin-bottom: 4px;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.match-score-label {
  font-size: 13px;
  color: var(--c-accent);
  font-weight: 600;
  margin-bottom: 12px;
  letter-spacing: 0.02em;
}

.match-score-label::before {
  content: '★ ';
}

.match-summary {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
  line-height: 1.6;
}

/* ── 差距诊断条目 ── */
.gap-item {
  display: flex;
  gap: 14px;
  padding: 16px;
  border-radius: var(--radius-md);
  margin-bottom: 10px;
  border: 1px solid var(--c-border-light);
}

.gap-strong {
  background: var(--c-accent-soft);
  border-color: var(--c-accent-line);
  color: var(--c-accent);
}

.gap-weak {
  background: var(--brand-primary-50);
  border-color: var(--brand-primary-100);
  color: var(--brand-primary);
}

.gap-missing {
  background: rgba(239, 68, 68, 0.04);
  border-color: rgba(239, 68, 68, 0.2);
  color: var(--c-danger);
}

.gap-status-icon {
  font-size: 18px;
  flex-shrink: 0;
  line-height: 1.5;
  display: inline-flex;
  align-items: center;
}

.gap-content {
  flex: 1;
}

.gap-requirement {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 4px;
}

.gap-evidence,
.gap-suggestion {
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.5;
  margin-top: 2px;
}

/* ── 求职信 ── */
.letter-type-switch {
  display: inline-flex;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  padding: 4px;
}

.letter-type-switch button {
  padding: 7px 16px;
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.letter-type-switch button.active {
  background: var(--c-surface);
  color: var(--brand-primary);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

.letter-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.letter-actions {
  display: flex;
  gap: 8px;
}

.letter-preview {
  padding: 28px 32px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  font-family: var(--font-sans);
  color: var(--c-text);
  line-height: 1.8;
  white-space: pre-wrap;
}

.letter-preview :deep(h1),
.letter-preview :deep(h2) {
  color: var(--brand-primary);
}

.letter-preview :deep(p) {
  margin: 8px 0;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .block-grid {
    grid-template-columns: 1fr;
  }
  .input-card {
    padding: 20px;
  }
  .match-score-num {
    font-size: 36px;
  }
  .letter-preview {
    padding: 20px;
  }
}
</style>
