<template>
  <div class="profile-page">
    <header class="page-header">
      <h1>个人中心</h1>
      <p>查看你的面试准备进度与历史数据统计</p>
    </header>

    <!-- 加载骨架 -->
    <div v-if="loading" class="stats-grid">
      <div v-for="i in 4" :key="i" class="stat-card skeleton-card">
        <div class="skeleton skeleton-num"></div>
        <div class="skeleton skeleton-label"></div>
      </div>
    </div>

    <!-- 数据卡片 -->
    <div v-else class="stats-grid">
      <div class="stat-card fade-in-up">
        <div class="stat-icon-wrap stat-icon-resume">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value num-display">{{ stats?.resumeCount ?? 0 }}</div>
          <div class="stat-label">简历数量</div>
        </div>
      </div>

      <div class="stat-card fade-in-up" style="animation-delay: 80ms">
        <div class="stat-icon-wrap stat-icon-interview">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value num-display">{{ stats?.sessionCount ?? 0 }}</div>
          <div class="stat-label">面试会话</div>
        </div>
      </div>

      <div class="stat-card fade-in-up" style="animation-delay: 160ms">
        <div class="stat-icon-wrap stat-icon-finished">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M9 11l3 3L22 4 M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value num-display">{{ stats?.finishedSessionCount ?? 0 }}</div>
          <div class="stat-label">已完成面试</div>
        </div>
      </div>

      <div class="stat-card fade-in-up" style="animation-delay: 240ms">
        <div class="stat-icon-wrap stat-icon-score">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M3 3v18h18 M7 14l4-4 4 4 5-5"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="stat-info">
          <div class="stat-value num-display">{{ formatScore(stats?.avgResumeScore) }}</div>
          <div class="stat-label">简历平均分</div>
        </div>
      </div>
    </div>

    <!-- 平均面试评分 -->
    <div v-if="!loading && stats?.avgInterviewScore != null" class="score-banner fade-in-up">
      <div class="banner-content">
        <div class="banner-text">
          <h3>面试答题平均分</h3>
          <p>基于所有已完成面试题目的 AI 评分</p>
        </div>
        <div class="banner-score">
          <span class="banner-star">★</span>
          <span class="banner-score-num num-display">{{ formatScore(stats?.avgInterviewScore) }}</span>
          <span class="banner-score-unit">分</span>
        </div>
      </div>
    </div>

    <!-- 数据与备份（第三批 C） -->
    <section class="data-section fade-in-up">
      <div class="section-header">
        <h2>数据与备份</h2>
        <span class="section-note">导出为单个 JSON 备份；导入前会自动先下载一份「导入前快照」</span>
      </div>

      <div class="data-actions">
        <BaseButton variant="gradient" :disabled="exporting || importing" @click="exportData">
          {{ exporting ? '导出中…' : '导出我的数据' }}
        </BaseButton>
        <BaseButton variant="ghost" :disabled="exporting || importing" @click="pickImportFile">
          {{ importing ? '导入中…' : '从备份导入' }}
        </BaseButton>
        <select v-model="importMode" class="mode-select" :disabled="importing">
          <option v-for="m in IMPORT_MODES" :key="m.value" :value="m.value">{{ m.label }}</option>
        </select>
        <span class="mode-hint">{{ modeHint }}</span>
        <input ref="importInput" type="file" accept=".json,application/json" class="hidden-file" @change="onImportFile" />
      </div>

      <div v-if="importNote" class="data-note" :class="'note-' + importNoteKind">{{ importNote }}</div>
    </section>

    <!-- 最近活动 -->
    <section class="recent-section">
      <div class="section-header">
        <h2>最近活动</h2>
        <div class="section-actions">
          <button class="btn-link" @click="router.push('/resume/history')">简历历史</button>
          <button class="btn-link" @click="router.push('/history')">面试记录</button>
        </div>
      </div>

      <div v-if="!loading && stats?.recentActivities?.length" class="activity-list">
        <div v-for="(a, idx) in stats.recentActivities" :key="idx"
             class="activity-item fade-in-up" :style="{ animationDelay: (idx * 60) + 'ms' }">
          <div class="activity-icon" :class="a.type">
            <svg v-if="a.type === 'resume'" width="14" height="14" viewBox="0 0 24 24" fill="none">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6"
                stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
                stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <div class="activity-content">
            <div class="activity-title">{{ a.title }}</div>
            <div class="activity-desc">{{ a.description }}</div>
          </div>
          <div class="activity-time">{{ fmtRelative(a.createdAt) }}</div>
        </div>
      </div>

      <div v-else-if="!loading" class="empty-state">
        <div class="empty-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
            <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2 M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2 M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2 M9 12h6 M9 16h4"
              stroke="var(--c-text-quaternary)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="empty-title">暂无活动记录</div>
        <div class="empty-desc">开始使用后，最近活动会出现在这里</div>
        <BaseButton variant="gradient" @click="router.push('/resume')">开始使用</BaseButton>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { getErrMessage } from '../api'
import { EMPTY } from '../utils/format'
import {
  EXPORT_PATH,
  IMPORT_MODES,
  IMPORT_PATH,
  exportFileName,
  fingerprintSource,
  describeImportSummary,
  isFingerprintConflict,
  isFingerprintMismatch,
  parseBackupFile,
  type BackupPayload,
  type ImportApplyResult,
  type ImportDryRunResult,
  type ImportMode,
} from '../utils/backup'
import { BaseButton } from '../components'

const router = useRouter()
const stats = ref<any>(null)
const loading = ref(true)

interface RecentActivity {
  type: string
  title: string
  description: string
  createdAt: string
}

onMounted(() => loadStats())

async function loadStats() {
  loading.value = true
  try {
    const data = await api.get('/api/stats/dashboard') as unknown as any
    stats.value = data
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载统计数据失败'))
  } finally {
    loading.value = false
  }
}

// ───────────────────────── 数据导出 / 导入（第三批 C） ─────────────────────────
// 导入严格按序：① 先 await 完成「导入前快照下载」→ ② dryRun 预览 → ③ 确认后 apply。
// dryRun 永不 409；仅 apply + replace + 指纹不符 + 未 force 才 409（此时给出 force 入口）。

const exporting = ref(false)
const importing = ref(false)
const importMode = ref<ImportMode>('merge')
const importNote = ref('')
const importNoteKind = ref<'info' | 'warn' | 'ok'>('info')
const importInput = ref<HTMLInputElement | null>(null)
let currentImportId = ''

const modeHint = computed(() => IMPORT_MODES.find((m) => m.value === importMode.value)?.hint ?? '')

/** 稳定 importId：同一备份 + 同一模式 → 同一 id → 后端可幂等重放（不二次写入） */
function importIdFor(payload: BackupPayload): string {
  const fp = payload.dataFingerprint || fingerprintSource(payload.data)
  const short = fp.replace(/[^a-z0-9]/gi, '').slice(0, 24)
  return `imp-${importMode.value}-${short || 'empty'}`
}

function setNote(kind: 'info' | 'warn' | 'ok', text: string) {
  importNoteKind.value = kind
  importNote.value = text
}

/** 触发浏览器下载（前端生成 Blob，不经后端） */
function downloadJson(obj: unknown, filename: string) {
  const blob = new Blob([JSON.stringify(obj, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

async function fetchExport(): Promise<Record<string, unknown>> {
  return (await api.get(EXPORT_PATH, { params: { includeConversations: false } })) as unknown as Record<string, unknown>
}

async function exportData() {
  exporting.value = true
  try {
    downloadJson(await fetchExport(), exportFileName())
    ElMessage.success('数据已导出')
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '导出失败'))
  } finally {
    exporting.value = false
  }
}

function pickImportFile() {
  importInput.value?.click()
}

async function onImportFile(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importing.value = true
  importNote.value = ''
  try {
    const payload = parseBackupFile(await file.text())
    currentImportId = importIdFor(payload)

    // ① 导入前快照：必须先 await 完成下载，再进入预览/写入
    setNote('info', '正在下载「导入前快照」…')
    downloadJson(await fetchExport(), exportFileName().replace(/\.json$/, '-before-import.json'))

    // ② dryRun 预览（不写库、永不 409）
    setNote('info', '正在生成预览（dryRun，不写库）…')
    const dry = (await api.post(IMPORT_PATH, {
      mode: importMode.value,
      dryRun: true,
      payload,
    })) as unknown as ImportDryRunResult

    const mismatch = isFingerprintMismatch(payload.dataFingerprint, dry.currentFingerprint)

    if (!dry.canApply) {
      setNote('warn',
        `预览未通过（未写库）：${describeImportSummary(dry.summary).join('，')}。` +
        (dry.fatalErrors?.length ? `存在 ${dry.fatalErrors.length} 条致命错误。` : '') +
        (mismatch ? '当前数据与备份不一致——「覆盖」模式需二次确认。' : '') +
        ' 可改用「合并」模式后重试。')
      return
    }

    setNote('info',
      `预览：${describeImportSummary(dry.summary).join('，')}。` +
      (dry.warnings?.length ? `另有 ${dry.warnings.length} 条提示。` : '') +
      (mismatch ? '（当前数据与备份不同）' : '') + ' 正在导入…')

    // ③ apply（幂等 importId + 指纹预校验）
    await applyImport(payload, dry, false)
  } catch (e: unknown) {
    setNote('warn', getErrMessage(e, '导入失败'))
    ElMessage.error(getErrMessage(e, '导入失败'))
  } finally {
    importing.value = false
  }
}

async function applyImport(payload: BackupPayload, dry: ImportDryRunResult, force: boolean) {
  try {
    const res = (await api.post(IMPORT_PATH, {
      mode: importMode.value,
      dryRun: false,
      force,
      expectedFingerprint: dry.currentFingerprint,
      importId: currentImportId,
      payload,
    })) as unknown as ImportApplyResult

    setNote('ok', res.idempotent
      ? '该备份此前已导入过（幂等，未重复写入）。'
      : `导入完成：${describeImportSummary(res.appliedCounts || res.summary).join('，')}`)
    ElMessage.success('导入完成')
    await loadStats()
  } catch (e: unknown) {
    if (isFingerprintConflict(e)) {
      // 只有 apply + replace + 指纹不符 + 未 force 才会到这里
      let ok = false
      try {
        await ElMessageBox.confirm(
          '当前数据与这份备份不一致，继续将用备份「覆盖」现有数据（已自动下载导入前快照，可回退）。确定继续？',
          '覆盖确认',
          { type: 'warning', confirmButtonText: '覆盖', cancelButtonText: '取消' },
        )
        ok = true
      } catch {
        ok = false
      }
      if (!ok) {
        setNote('info', '已取消导入，数据未发生变化。')
        return
      }
      return applyImport(payload, dry, true)
    }
    throw e
  }
}

function formatScore(s?: number | null): string {
  if (s == null || isNaN(s)) return EMPTY
  return s.toFixed(1)
}

function fmtRelative(iso: string): string {
  if (!iso) return EMPTY
  const d = new Date(iso)
  if (isNaN(d.getTime())) return EMPTY
  const now = Date.now()
  const diff = now - d.getTime()
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < hour) return Math.max(1, Math.floor(diff / minute)) + ' 分钟前'
  if (diff < day) return Math.floor(diff / hour) + ' 小时前'
  if (diff < 30 * day) return Math.floor(diff / day) + ' 天前'
  return d.toLocaleDateString('zh-CN')
}
</script>

<style scoped>
.profile-page {
  max-width: 1100px;
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

/* ── 数据卡片 ── */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  margin-bottom: 32px;
}

.stat-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 14px;
  transition: all var(--transition-base);
  box-shadow: var(--shadow-xs);
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--c-border);
}

.stat-icon-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  flex-shrink: 0;
}

.stat-icon-resume {
  background: var(--brand-primary-50);
  color: var(--brand-primary);
}

.stat-icon-interview {
  background: var(--c-info-light);
  color: var(--c-info);
}

.stat-icon-finished {
  background: var(--c-accent-soft);
  color: var(--c-accent);
}

.stat-icon-score {
  background: var(--c-accent-soft);
  color: var(--c-accent);
}

.stat-info {
  flex: 1;
  min-width: 0;
}

/* 数值：等宽 + 琥珀金（num-display 语义，强调斩获感） */
.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--c-accent);
  line-height: 1.1;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 12px;
  color: var(--c-text-secondary);
}

.skeleton-card {
  height: 84px;
}

.skeleton-num {
  width: 60px;
  height: 24px;
  margin-bottom: 6px;
}

.skeleton-label {
  width: 80px;
  height: 14px;
}

/* ── 平均分横幅（v4：琥珀浅底编辑风） ── */
.score-banner {
  background: var(--c-accent-soft);
  border: 1px solid var(--c-accent-line);
  border-radius: var(--radius-xl);
  padding: 24px 32px;
  margin-bottom: 32px;
  box-shadow: var(--shadow-sm);
}

.banner-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.banner-text h3 {
  font-size: 18px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 4px;
}

.banner-text p {
  font-size: 13px;
  color: var(--c-text-secondary);
  margin: 0;
}

.banner-score {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.banner-star {
  color: var(--c-accent);
  font-size: 22px;
  line-height: 1;
}

.banner-score-num {
  font-size: 36px;
  font-weight: 800;
  color: var(--c-accent);
  line-height: 1;
}

.banner-score-unit {
  font-size: 14px;
  color: var(--c-accent-hover);
}

/* ── 最近活动 ── */
.recent-section {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 24px;
  box-shadow: var(--shadow-xs);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.section-header h2 {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0;
}

.section-actions {
  display: flex;
  gap: 16px;
}

.btn-link {
  font-size: 13px;
  color: var(--brand-primary);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
  transition: color var(--transition-fast);
}

.btn-link:hover {
  color: var(--brand-primary-hover);
  text-decoration: underline;
}

.btn-link:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

.activity-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.activity-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: var(--radius-md);
  transition: background var(--transition-fast);
}

.activity-item:hover {
  background: var(--c-bg-alt);
}

.activity-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}

.activity-icon.resume {
  background: var(--brand-primary-50);
  color: var(--brand-primary);
}

.activity-icon.interview {
  background: var(--c-info-light);
  color: var(--c-info);
}

.activity-content {
  flex: 1;
  min-width: 0;
}

.activity-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text);
  margin-bottom: 2px;
}

.activity-desc {
  font-size: 12px;
  color: var(--c-text-secondary);
}

.activity-time {
  font-size: 12px;
  color: var(--c-text-tertiary);
  white-space: nowrap;
  flex-shrink: 0;
}

/* ── 空状态 ── */
.empty-state {
  text-align: center;
  padding: 48px 24px;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
  opacity: 0.6;
}

.empty-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 6px;
}

.empty-desc {
  font-size: 13px;
  color: var(--c-text-secondary);
  margin-bottom: 20px;
}

/* ── 数据与备份（第三批 C） ── */
.data-section {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 24px;
  box-shadow: var(--shadow-xs);
  margin-bottom: 24px;
}

.section-note {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.data-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.mode-select {
  padding: 8px 12px;
  font-size: 13px;
  color: var(--c-text);
  background: var(--c-bg);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  cursor: pointer;
}

.mode-hint {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.hidden-file {
  display: none;
}

.data-note {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  font-size: 13px;
  line-height: 1.6;
}

.note-info { background: var(--c-info-light); color: var(--c-info); }
.note-warn { background: var(--c-warning-light); color: var(--c-warning); }
.note-ok { background: var(--c-success-light); color: var(--c-success); }

/* ── 响应式 ── */
@media (max-width: 768px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }
  .banner-content {
    flex-direction: column;
    align-items: flex-start;
    text-align: left;
  }
  .section-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}

@media (max-width: 480px) {
  .stats-grid {
    grid-template-columns: 1fr;
  }
}
</style>
