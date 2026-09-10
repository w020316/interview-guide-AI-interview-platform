<template>
  <div class="resume-history-page">
    <header class="page-header page-head-row">
      <div>
        <h1>简历历史</h1>
        <p>查看历次简历分析的结果与评分变化</p>
      </div>
      <button v-if="resumes.length >= 2 && !compareMode" class="compare-toggle" @click="toggleCompareMode">
        多版本对比
      </button>
    </header>

    <!-- 对比模式工具条 -->
    <div v-if="compareMode" class="compare-bar" role="status">
      <span class="compare-hint">
        {{ selectedIds.length < 2 ? '选择两个版本进行对比' : '已选择 2 个版本，可开始对比' }}
      </span>
      <button class="compare-btn primary" :disabled="selectedIds.length !== 2 || compareLoading" @click="startCompare">
        {{ compareLoading ? '加载中…' : '开始对比' }}
      </button>
      <button class="compare-btn" @click="toggleCompareMode">取消</button>
    </div>

    <!-- 空状态 -->
    <div v-if="!loading && loadError" class="empty-state fade-in">
      <div class="empty-icon">⚠️</div>
      <div class="empty-title">加载失败</div>
      <div class="empty-desc">简历历史加载失败，请检查网络后重试</div>
      <button class="retry-btn" @click="loadResumes">重新加载</button>
    </div>
    <div v-else-if="!loading && !resumes.length" class="empty-state fade-in">
      <div class="empty-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M9 13h6 M9 17h6"
            stroke="var(--c-text-quaternary)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <div class="empty-title">暂无简历分析记录</div>
      <div class="empty-desc">完成一次简历分析后，记录会出现在这里</div>
      <BaseButton variant="gradient" @click="router.push('/resume')">前往简历分析</BaseButton>
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
           class="resume-card fade-in-up" :class="{ 'is-selected': compareMode && selectedIds.includes(r.id) }"
           :style="{ animationDelay: (idx * 80) + 'ms' }"
           @click="onCardClick(r)">
        <span v-if="compareMode" class="pick-dot" :class="{ on: selectedIds.includes(r.id) }" aria-hidden="true">
          <svg v-if="selectedIds.includes(r.id)" width="11" height="11" viewBox="0 0 24 24" fill="none">
            <path d="M20 6L9 17l-5-5" stroke="#fff" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </span>
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

    <!-- 多版本对比弹窗（v1.25.0） -->
    <div v-if="compareOpen && compareData" class="modal-backdrop" @click.self="compareOpen = false">
      <div class="modal fade-in-up compare-modal">
        <div class="modal-header">
          <h3>简历版本对比</h3>
          <button class="modal-close" @click="compareOpen = false">×</button>
        </div>
        <div class="modal-body">
          <div class="cmp-versions">
            <div class="cmp-ver">
              <span class="cmp-tag">版本 A（较早）</span>
              <div class="cmp-job">{{ compareData.a.targetJob || '未指定岗位' }}</div>
              <div class="cmp-date">{{ fmtDate(compareData.a.createdAt || '') }}</div>
              <div class="cmp-score" :style="{ color: scoreColor(compareData.a.overallScore) }">
                {{ compareData.a.overallScore }}<small>分</small>
              </div>
            </div>
            <div class="cmp-arrow" :class="overallDir">
              {{ overallDiffText }}
            </div>
            <div class="cmp-ver">
              <span class="cmp-tag b">版本 B（较晚）</span>
              <div class="cmp-job">{{ compareData.b.targetJob || '未指定岗位' }}</div>
              <div class="cmp-date">{{ fmtDate(compareData.b.createdAt || '') }}</div>
              <div class="cmp-score" :style="{ color: scoreColor(compareData.b.overallScore) }">
                {{ compareData.b.overallScore }}<small>分</small>
              </div>
            </div>
          </div>

          <div class="cmp-dims">
            <div class="cmp-row cmp-head">
              <span>维度</span><span>版本 A</span><span>版本 B</span><span>变化</span>
            </div>
            <div v-for="row in compareResult.rows" :key="row.name" class="cmp-row">
              <span class="cmp-name">{{ row.name }}</span>
              <span :style="{ color: scoreColor(row.a ?? undefined) }">{{ row.a ?? '—' }}</span>
              <span :style="{ color: scoreColor(row.b ?? undefined) }">{{ row.b ?? '—' }}</span>
              <span class="cmp-diff" :class="row.better">
                {{ row.diff == null ? '—' : row.diff === 0 ? '持平' : (row.diff > 0 ? `+${row.diff}` : `${row.diff}`) }}
              </span>
            </div>
            <p v-if="!compareResult.rows.length" class="cmp-empty">两个版本均无维度明细，仅综合分可比</p>
          </div>

          <div v-if="summaryText" class="cmp-summary">{{ summaryText }}</div>
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
import { compareResume, type ResumeVersion, type DimDiff } from '../utils/resumeCompare'
import { BaseButton } from '../components'

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
const loadError = ref(false)
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
    loadError.value = true
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

// ── 多版本对比（v1.25.0）──
const compareMode = ref(false)
const selectedIds = ref<number[]>([])
const compareOpen = ref(false)
const compareLoading = ref(false)
const compareData = ref<{ a: ResumeVersion; b: ResumeVersion } | null>(null)

function toggleCompareMode() {
  compareMode.value = !compareMode.value
  selectedIds.value = []
}

/** 对比模式下点击卡片切换选中态；普通模式打开详情 */
function onCardClick(r: Resume) {
  if (compareMode.value) {
    const arr = selectedIds.value
    const i = arr.indexOf(r.id)
    if (i >= 0) arr.splice(i, 1)
    else if (arr.length < 2) arr.push(r.id)
    else ElMessage.info('最多选择两个版本进行对比')
  } else {
    openDetail(r)
  }
}

/** 解析简历记录为对比版本（综合分 + 维度，数字强制转换） */
function parseVersion(r: Resume): ResumeVersion {
  let obj: { overallScore?: unknown; dimensions?: Array<{ name?: unknown; score?: unknown }> } = {}
  try {
    obj = JSON.parse(r.analysisResult || '{}')
  } catch {
    // 解析失败保持空对象，仅综合分可比
  }
  const num = (v: unknown): number =>
    typeof v === 'number' ? v : typeof v === 'string' ? (Number(v) || 0) : 0
  return {
    id: r.id,
    targetJob: r.targetJob,
    createdAt: r.createdAt,
    overallScore: num(obj.overallScore),
    dimensions: Array.isArray(obj.dimensions)
      ? obj.dimensions.map((d) => ({ name: String(d?.name ?? ''), score: num(d?.score) }))
      : [],
  }
}

async function startCompare() {
  if (selectedIds.value.length !== 2 || compareLoading.value) return
  compareLoading.value = true
  try {
    // 按 createdAt 升序确定 A（较早）→ B（较晚）
    const picked = resumes.value
      .filter((r) => selectedIds.value.includes(r.id))
      .sort((x, y) => new Date(x.createdAt).getTime() - new Date(y.createdAt).getTime())
    const versions: ResumeVersion[] = []
    for (const r of picked) {
      try {
        const data = await api.get(`/api/resume/${r.id}`) as unknown as Resume
        if (data.analysisResult) {
          const { repaired, valid } = repairAndCheck(data.analysisResult)
          if (valid) data.analysisResult = repaired
        }
        versions.push(parseVersion(data))
      } catch {
        versions.push(parseVersion(r)) // 详情加载失败时降级用列表快照
      }
    }
    compareData.value = { a: versions[0], b: versions[1] }
    compareOpen.value = true
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载对比数据失败'))
  } finally {
    compareLoading.value = false
  }
}

const compareResult = computed(() =>
  compareData.value
    ? compareResume(compareData.value.a, compareData.value.b)
    : { overallDiff: 0, rows: [] as DimDiff[] },
)

const overallDiffText = computed(() => {
  const d = compareResult.value.overallDiff
  if (d > 0) return `↑ +${d}`
  if (d < 0) return `↓ ${d}`
  return '持平'
})

const overallDir = computed(() => {
  const d = compareResult.value.overallDiff
  return d > 0 ? 'up' : d < 0 ? 'down' : 'flat'
})

const summaryText = computed(() => {
  if (!compareData.value) return ''
  const { overallDiff, rows } = compareResult.value
  const up = rows.filter((r) => r.better === 'b').map((r) => r.name)
  const down = rows.filter((r) => r.better === 'a').map((r) => r.name)
  const parts: string[] = []
  parts.push(overallDiff >= 0 ? `综合分较早期版本${overallDiff === 0 ? '持平' : `提升 ${overallDiff} 分`}` : `综合分下降 ${-overallDiff} 分`)
  if (up.length) parts.push(`${up.join('、')}有提升`)
  if (down.length) parts.push(`${down.join('、')}有所回落，可针对性优化`)
  return parts.join('；') + '。'
})
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

/* ── 多版本对比（v1.25.0）── */
.page-head-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.compare-toggle {
  padding: 9px 18px;
  font-size: 13px;
  font-weight: 500;
  font-family: var(--font-sans);
  color: var(--brand-primary);
  background: var(--brand-primary-light);
  border: 1px solid var(--brand-primary);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
}
.compare-toggle:hover {
  background: var(--brand-primary);
  color: #fff;
}
.compare-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  flex-wrap: wrap;
}
.compare-hint {
  flex: 1;
  font-size: 13px;
  color: var(--c-text-secondary);
}
.compare-btn {
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 600;
  font-family: var(--font-sans);
  color: var(--c-text-secondary);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
}
.compare-btn.primary {
  color: #fff;
  background: var(--brand-primary);
  border-color: var(--brand-primary);
}
.compare-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.resume-card.is-selected {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 2px var(--brand-primary-light);
}
.pick-dot {
  width: 22px;
  height: 22px;
  border-radius: 999px;
  border: 2px solid var(--c-border);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all var(--transition-fast);
}
.pick-dot.on {
  border-color: var(--brand-primary);
  background: var(--brand-primary);
}
.compare-modal {
  max-width: 680px;
}
.cmp-versions {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  gap: 14px;
  align-items: center;
  padding: 16px;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  margin-bottom: 16px;
}
.cmp-ver {
  text-align: center;
  min-width: 0;
}
.cmp-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  color: var(--c-text-tertiary);
  background: var(--c-surface);
  padding: 2px 10px;
  border-radius: 999px;
  margin-bottom: 6px;
}
.cmp-tag.b {
  color: var(--brand-primary);
}
.cmp-job {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cmp-date {
  font-size: 11.5px;
  color: var(--c-text-tertiary);
  margin-bottom: 8px;
}
.cmp-score {
  font-size: 34px;
  font-weight: 800;
  line-height: 1;
}
.cmp-score small {
  font-size: 14px;
  font-weight: 600;
  margin-left: 2px;
}
.cmp-arrow {
  font-size: 20px;
  font-weight: 800;
  padding: 8px 12px;
  border-radius: 999px;
  white-space: nowrap;
}
.cmp-arrow.up { color: #10b981; background: rgba(16, 185, 129, 0.1); }
.cmp-arrow.down { color: #ef4444; background: rgba(239, 68, 68, 0.1); }
.cmp-arrow.flat { color: var(--c-text-tertiary); background: var(--c-surface); }
.cmp-dims {
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
  margin-bottom: 14px;
}
.cmp-row {
  display: grid;
  grid-template-columns: 1.6fr 1fr 1fr 1fr;
  gap: 8px;
  padding: 10px 14px;
  font-size: 13px;
  color: var(--c-text);
  align-items: center;
}
.cmp-row + .cmp-row {
  border-top: 1px solid var(--c-border-light);
}
.cmp-row.cmp-head {
  background: var(--c-bg-alt);
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-secondary);
}
.cmp-name {
  font-weight: 500;
}
.cmp-diff {
  font-weight: 700;
  text-align: right;
}
.cmp-diff.b { color: #10b981; }
.cmp-diff.a { color: #ef4444; }
.cmp-diff.tie { color: var(--c-text-tertiary); }
.cmp-empty {
  padding: 16px;
  font-size: 13px;
  color: var(--c-text-tertiary);
  text-align: center;
}
.cmp-summary {
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text-secondary);
  background: var(--c-accent-soft);
  border-left: 3px solid var(--c-accent);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
}
@media (max-width: 640px) {
  .cmp-versions {
    grid-template-columns: 1fr;
    text-align: center;
  }
  .cmp-arrow {
    justify-self: center;
  }
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
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
  color: var(--c-accent);
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
  font-family: var(--font-serif);
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
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
  color: var(--c-accent);
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
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
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
  font-family: var(--font-serif);
  margin: 0 0 10px;
}

.detail-card-success {
  border-color: var(--c-accent-line);
  background: var(--c-accent-soft);
}

.detail-card-success h4 {
  color: var(--c-accent);
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

.detail-card-success ul {
  list-style: none;
  padding-left: 18px;
}

.detail-card-success li {
  position: relative;
  padding-left: 4px;
}

.detail-card-success li::before {
  content: '★';
  position: absolute;
  left: -16px;
  color: var(--c-accent);
  font-size: 10px;
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

.retry-btn { padding: 8px 20px; border: 1px solid var(--c-accent); background: transparent; color: var(--c-accent); border-radius: var(--radius-md); cursor: pointer; font-size: 14px; transition: all 0.2s; }
.retry-btn:hover { background: var(--c-accent-soft); }
</style>
