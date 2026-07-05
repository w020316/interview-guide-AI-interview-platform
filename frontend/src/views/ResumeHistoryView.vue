<template>
  <div class="resume-history-page">
    <header class="page-header">
      <h1>简历历史</h1>
      <p>查看历次简历分析的结果与评分变化</p>
    </header>

    <!-- 空状态 -->
    <div v-if="!loading && !resumes.length" class="empty-state fade-in">
      <div class="empty-icon">📄</div>
      <div class="empty-title">暂无简历分析记录</div>
      <div class="empty-desc">完成一次简历分析后，记录会出现在这里</div>
      <button class="btn-primary" @click="router.push('/resume')">前往简历分析</button>
    </div>

    <!-- 加载骨架 -->
    <div v-if="loading" class="resume-list">
      <div v-for="i in 3" :key="i" class="skeleton-card">
        <div class="skeleton skeleton-line w-30"></div>
        <div class="skeleton skeleton-line w-60"></div>
        <div class="skeleton skeleton-line w-40"></div>
      </div>
    </div>

    <!-- 简历列表 -->
    <div v-else-if="resumes.length" class="resume-list">
      <div v-for="(r, idx) in resumes" :key="r.id"
           class="resume-card fade-in-up" :style="{ animationDelay: (idx * 80) + 'ms' }"
           @click="openDetail(r)">
        <div class="card-score" :style="{ background: scoreBg(r.overallScore) }">
          <span class="score-num">{{ r.overallScore ?? '—' }}</span>
          <span class="score-unit" v-if="r.overallScore != null">分</span>
        </div>
        <div class="card-body">
          <div class="card-title">{{ getPreview(r) }}</div>
          <div class="card-meta">
            <span class="meta-tag">{{ r.targetJob || '未指定岗位' }}</span>
            <span class="meta-time">🕒 {{ fmtDate(r.createdAt) }}</span>
          </div>
        </div>
        <div class="card-action">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
            <path d="M9 18l6-6-6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <div v-if="detailVisible" class="modal-backdrop" @click.self="detailVisible = false">
      <div class="modal fade-in-up">
        <div class="modal-header">
          <h3>简历分析详情</h3>
          <button class="modal-close" @click="detailVisible = false">×</button>
        </div>
        <div class="modal-body">
          <div v-if="detailLoading" class="loading">加载中…</div>
          <template v-else-if="detail">
            <div v-if="parseError" class="parse-warning">
              <span>⚠</span>
              <span>{{ parseError }}</span>
            </div>
            <div v-if="parsed.overallScore" class="detail-score-row">
              <div class="detail-score-circle" :style="{ '--score-color': scoreColor(parsed.overallScore) }">
                <svg viewBox="0 0 120 120" class="score-svg">
                  <circle cx="60" cy="60" r="52" class="score-track" />
                  <circle cx="60" cy="60" r="52" class="score-fill"
                    :style="{ strokeDasharray: 327, strokeDashoffset: 327 - (327 * (parsed.overallScore || 0)) / 100 }" />
                </svg>
                <div class="score-value">
                  <span class="score-num">{{ parsed.overallScore }}</span>
                  <span class="score-unit">分</span>
                </div>
              </div>
              <div>
                <div class="detail-job">{{ detail.targetJob || '未指定岗位' }}</div>
                <div class="detail-time">{{ fmtDate(detail.createdAt) }}</div>
              </div>
            </div>

            <div v-if="parsed.dimensions?.length" class="dim-list">
              <div v-for="(d, idx) in parsed.dimensions" :key="idx" class="dim-item">
                <div class="dim-head">
                  <span class="dim-name">{{ d.name }}</span>
                  <span class="dim-score" :style="{ color: scoreColor(d.score) }">{{ d.score }}</span>
                </div>
                <div class="dim-bar">
                  <div class="dim-bar-fill" :style="{ width: (d.score || 0) + '%', background: scoreGradient(d.score) }"></div>
                </div>
                <p class="dim-suggestion">{{ d.suggestion }}</p>
              </div>
            </div>

            <div v-if="parsed.strengths?.length || parsed.improvements?.length" class="detail-grid">
              <div v-if="parsed.strengths?.length" class="detail-card detail-card-success">
                <h4>优势</h4>
                <ul>
                  <li v-for="(s, i) in parsed.strengths" :key="i">{{ s }}</li>
                </ul>
              </div>
              <div v-if="parsed.improvements?.length" class="detail-card detail-card-warning">
                <h4>改进建议</h4>
                <ul>
                  <li v-for="(s, i) in parsed.improvements" :key="i">{{ s }}</li>
                </ul>
              </div>
            </div>

            <details class="raw-detail">
              <summary>查看原始返回 JSON</summary>
              <pre>{{ detail.analysisResult }}</pre>
            </details>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { repairAndCheck } from '../utils/jsonRepair'

const router = useRouter()

interface Resume {
  id: number
  userId: string
  content: string
  fileUrl?: string
  targetJob?: string
  overallScore?: number | null
  analysisResult?: string
  createdAt: string
}

interface AnalysisResult {
  overallScore: number
  dimensions: Array<{ name: string; score: number; suggestion: string }>
  strengths: string[]
  improvements: string[]
}

const resumes = ref<Resume[]>([])
const loading = ref(true)
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<Resume | null>(null)
const parseError = ref('')

onMounted(() => loadResumes())

async function loadResumes() {
  loading.value = true
  try {
    const data = await api.get('/api/resume/history') as unknown as Resume[]
    resumes.value = data || []
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载简历历史失败'))
  } finally {
    loading.value = false
  }
}

async function openDetail(r: Resume) {
  detailVisible.value = true
  detail.value = null
  detailLoading.value = true
  parseError.value = ''
  try {
    const data = await api.get(`/api/resume/${r.id}`) as unknown as Resume
    // 加载详情后立即尝试修复，保证 parsed computed 能正常解析
    if (data.analysisResult) {
      const { repaired, valid } = repairAndCheck(data.analysisResult)
      if (valid && repaired !== data.analysisResult) {
        data.analysisResult = repaired // 用修复后的内容替换
      }
      if (!valid) {
        try {
          JSON.parse(data.analysisResult)
        } catch (e) {
          parseError.value = 'AI 返回内容无法解析为标准 JSON。错误：' + (e as Error).message
        }
      }
    }
    detail.value = data
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载详情失败'))
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

const parsed = computed<AnalysisResult>(() => {
  if (!detail.value?.analysisResult) {
    return { overallScore: 0, dimensions: [], strengths: [], improvements: [] }
  }
  try {
    const obj = JSON.parse(detail.value.analysisResult)
    // 强制数字转换（与 ResumeView 保持一致）
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

function getPreview(r: Resume): string {
  if (!r.content) return '简历记录'
  const text = r.content.replace(/^\[上传文件\]\s*/, '')
  return text.length > 40 ? text.slice(0, 40) + '…' : text
}

function fmtDate(dt: string): string {
  if (!dt) return '-'
  const d = new Date(dt)
  if (isNaN(d.getTime())) return '-'
  return d.toLocaleString('zh-CN', { hour12: false })
}

function scoreColor(s?: number | null): string {
  if (s == null) return 'var(--c-text-tertiary)'
  if (s >= 85) return '#10b981'
  if (s >= 70) return '#3b82f6'
  if (s >= 60) return '#f59e0b'
  return '#ef4444'
}

function scoreGradient(s?: number | null): string {
  if (s == null) return 'var(--c-border)'
  if (s >= 85) return 'linear-gradient(90deg, #10b981, #34d399)'
  if (s >= 70) return 'linear-gradient(90deg, #3b82f6, #60a5fa)'
  if (s >= 60) return 'linear-gradient(90deg, #f59e0b, #fbbf24)'
  return 'linear-gradient(90deg, #ef4444, #f87171)'
}

function scoreBg(s?: number | null): string {
  if (s == null) return 'var(--c-bg-alt)'
  if (s >= 85) return 'linear-gradient(135deg, #d1fae5, #a7f3d0)'
  if (s >= 70) return 'linear-gradient(135deg, #dbeafe, #bfdbfe)'
  if (s >= 60) return 'linear-gradient(135deg, #fef3c7, #fde68a)'
  return 'linear-gradient(135deg, #fee2e2, #fecaca)'
}
</script>

<style scoped>
.resume-history-page {
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

/* ── 空状态 ── */
.empty-state {
  text-align: center;
  padding: 64px 24px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
}

.empty-icon {
  font-size: 56px;
  margin-bottom: 16px;
  opacity: 0.6;
}

.empty-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 6px;
}

.empty-desc {
  font-size: 13px;
  color: var(--c-text-secondary);
  margin-bottom: 24px;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 11px 24px;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-gradient);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: var(--shadow-brand);
  font-family: inherit;
}

.btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(79, 70, 229, 0.4);
}

/* ── 简历卡片 ── */
.resume-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.resume-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: all var(--transition-base);
  box-shadow: var(--shadow-xs);
}

.resume-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--c-border);
}

.card-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: var(--radius-md);
  flex-shrink: 0;
}

.score-num {
  font-size: 22px;
  font-weight: 800;
  color: var(--c-text);
  line-height: 1;
}

.score-unit {
  font-size: 11px;
  color: var(--c-text-secondary);
  margin-top: 2px;
}

.card-body {
  flex: 1;
  min-width: 0;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-meta {
  display: flex;
  gap: 12px;
  align-items: center;
}

.meta-tag {
  font-size: 12px;
  padding: 2px 10px;
  background: var(--brand-primary-50);
  color: var(--brand-primary);
  border-radius: var(--radius-full);
  font-weight: 500;
}

.meta-time {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.card-action {
  color: var(--c-text-tertiary);
  flex-shrink: 0;
  transition: color var(--transition-fast);
}

.resume-card:hover .card-action {
  color: var(--brand-primary);
}

/* ── Skeleton ── */
.skeleton-card {
  padding: 20px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
}

.skeleton-line {
  height: 14px;
  margin-bottom: 10px;
  border-radius: var(--radius-sm);
}

.skeleton-line.w-30 { width: 30%; }
.skeleton-line.w-60 { width: 60%; }
.skeleton-line.w-40 { width: 40%; }

/* ── 详情弹窗 ── */
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.5);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  z-index: var(--z-modal-backdrop);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.modal {
  background: var(--c-surface);
  border-radius: var(--radius-xl);
  max-width: 720px;
  width: 100%;
  max-height: 85vh;
  overflow: hidden;
  box-shadow: var(--shadow-2xl);
  z-index: var(--z-modal);
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid var(--c-border-light);
}

.modal-header h3 {
  font-size: 18px;
  font-weight: 600;
  margin: 0;
  color: var(--c-text);
}

.modal-close {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--c-text-secondary);
  font-size: 20px;
  transition: all var(--transition-fast);
}

.modal-close:hover {
  background: var(--c-bg-alt);
  color: var(--c-text);
}

.modal-body {
  padding: 24px;
  overflow-y: auto;
}

.loading {
  padding: 48px 24px;
  text-align: center;
  color: var(--c-text-secondary);
}

.parse-warning {
  background: var(--c-warning-light);
  color: var(--c-warning);
  padding: 10px 14px;
  border-radius: var(--radius-md);
  font-size: 13px;
  margin-bottom: 16px;
  display: flex;
  gap: 8px;
}

.detail-score-row {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 24px;
}

.detail-score-circle {
  position: relative;
  width: 100px;
  height: 100px;
  flex-shrink: 0;
  color: var(--score-color, var(--brand-primary));
}

.score-svg {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}

.score-track {
  fill: none;
  stroke: var(--c-border);
  stroke-width: 8;
}

.score-fill {
  fill: none;
  stroke: var(--score-color, var(--brand-primary));
  stroke-width: 8;
  stroke-linecap: round;
  transition: stroke-dashoffset 1s ease;
}

.score-value {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.score-value .score-num {
  font-size: 24px;
  font-weight: 800;
  color: var(--c-text);
}

.score-value .score-unit {
  font-size: 11px;
  color: var(--c-text-secondary);
}

.detail-job {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 4px;
}

.detail-time {
  font-size: 13px;
  color: var(--c-text-secondary);
}

.dim-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 24px;
}

.dim-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
}

.dim-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text);
}

.dim-score {
  font-size: 14px;
  font-weight: 700;
}

.dim-bar {
  height: 6px;
  background: var(--c-bg-alt);
  border-radius: var(--radius-full);
  overflow: hidden;
  margin-bottom: 8px;
}

.dim-bar-fill {
  height: 100%;
  border-radius: var(--radius-full);
  transition: width 0.6s ease;
}

.dim-suggestion {
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.5;
  margin: 0;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}

.detail-card {
  padding: 16px;
  border-radius: var(--radius-md);
  border: 1px solid var(--c-border-light);
}

.detail-card h4 {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 10px;
}

.detail-card-success h4 {
  color: var(--c-success);
}

.detail-card-warning h4 {
  color: var(--c-warning);
}

.detail-card ul {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.6;
}

.raw-detail {
  margin-top: 16px;
  padding: 12px;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  font-size: 12px;
}

.raw-detail summary {
  cursor: pointer;
  color: var(--c-text-secondary);
  font-weight: 500;
}

.raw-detail pre {
  margin: 8px 0 0;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--c-text-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
}

@media (max-width: 640px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
  .detail-score-row {
    flex-direction: column;
    align-items: flex-start;
    text-align: left;
  }
  .modal {
    max-height: 90vh;
  }
}
</style>
