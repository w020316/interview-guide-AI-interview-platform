<template>
  <div class="progress-page">
    <header class="page-header">
      <h1>成长趋势</h1>
      <p>用数据看见每一次面试的进步，定位薄弱维度</p>
    </header>

    <!-- 准备度检测（第三批 G）：规则化「客观完成度」，与「求职诊断」（AI 能力画像）互补 -->
    <section class="readiness-card fade-in-up" data-readiness-card>
      <div v-if="readinessLoading" class="readiness-skeleton">
        <div class="skeleton skeleton-line w-30"></div>
        <div class="skeleton skeleton-line w-70"></div>
        <div class="skeleton skeleton-line w-50"></div>
      </div>

      <!-- 空态（R3）：不得出现「C 级」「0/14」等任何看起来像真实评估结果的文案 -->
      <div v-else-if="!readiness.hasData" class="readiness-empty" data-readiness-empty>
        <div class="readiness-empty-icon" aria-hidden="true">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none">
            <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2 M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2 M9 12h6 M9 16h4"
              stroke="var(--brand-primary)" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <p class="readiness-empty-title">尚未开始准备</p>
        <p class="readiness-empty-desc">上传一份简历，或先收藏几个心仪岗位，我们就会开始帮你跟踪准备进度。</p>
        <div class="readiness-empty-actions">
          <BaseButton variant="primary" size="sm" @click="go('/resume')">上传简历</BaseButton>
          <BaseButton variant="ghost" size="sm" @click="go('/jobs')">去招聘广场</BaseButton>
        </div>
      </div>

      <template v-else>
        <div class="readiness-head">
          <div class="readiness-title-wrap">
            <h2 class="readiness-title">我的准备度</h2>
            <span class="readiness-grade" :class="'grade-' + readiness.grade">{{ gradeLabel(readiness.grade) }}</span>
          </div>
          <span class="readiness-progress">{{ progressText(readiness) }}</span>
        </div>

        <ul class="readiness-list">
          <li
            v-for="it in readiness.items"
            :key="it.id"
            class="readiness-item"
            :class="{ done: it.achieved, manual: !!it.manualKey }"
          >
            <button v-if="it.manualKey" type="button" class="ri-btn" @click="toggleManual(it.manualKey)">
              <span class="ri-check" aria-hidden="true">
                <svg v-if="it.achieved" width="15" height="15" viewBox="0 0 24 24" fill="none">
                  <path d="M9 11l3 3L22 4 M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"
                    stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none">
                  <rect x="3" y="3" width="18" height="18" rx="2" stroke="currentColor" stroke-width="2"/>
                </svg>
              </span>
              <span class="ri-dim">{{ it.dimension }}</span>
              <span class="ri-label">{{ it.label }}</span>
              <span class="ri-evidence">{{ it.evidence }}</span>
              <span class="ri-toggle-hint">自述</span>
            </button>
            <div v-else class="ri-row">
              <span class="ri-check" aria-hidden="true">
                <svg v-if="it.achieved" width="15" height="15" viewBox="0 0 24 24" fill="none">
                  <path d="M9 11l3 3L22 4 M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"
                    stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none">
                  <rect x="3" y="3" width="18" height="18" rx="2" stroke="currentColor" stroke-width="2"/>
                </svg>
              </span>
              <span class="ri-dim">{{ it.dimension }}</span>
              <span class="ri-label">{{ it.label }}</span>
              <span class="ri-evidence">{{ it.evidence }}</span>
            </div>
          </li>
        </ul>

        <p class="readiness-rule">等级规则：≥11 项 A · 6–10 项 B · ≤5 项 C</p>
      </template>
    </section>

    <!-- 统计摘要 -->
    <section class="stat-grid fade-in-up">
      <div class="stat-card">
        <div class="stat-label">已完成面试</div>
        <div class="stat-value num-display">{{ stats.count }}</div>
        <div class="stat-sub">次模拟面试</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">平均得分</div>
        <div class="stat-value num-display">{{ stats.average }}</div>
        <div class="stat-sub">综合得分 / 100</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">历史最高</div>
        <div class="stat-value num-display">{{ stats.max }}</div>
        <div class="stat-sub">单次最佳表现</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">最新一次</div>
        <div class="stat-value num-display">{{ stats.latest }}</div>
        <div
          class="stat-sub trend"
          :class="delta > 0 ? 'trend-up' : delta < 0 ? 'trend-down' : ''"
        >
          {{ deltaText }}
        </div>
      </div>
    </section>

    <!-- 目标达成提示 -->
    <section v-if="goalHint" class="goal-card fade-in-up">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="9" stroke="var(--brand-primary)" stroke-width="2"/>
        <path d="M8 12.2l2.4 2.4L16.5 8.3" stroke="var(--brand-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
      <div>
        <div class="goal-title">{{ goalHint.title }}</div>
        <div class="goal-text">{{ goalHint.text }}</div>
      </div>
    </section>

    <!-- 折线图 -->
    <section class="chart-card fade-in-up">
      <div class="section-head chart-head">
        <div>
          <h2>得分走势</h2>
          <span class="section-note">按「{{ dimensionLabel }}」维度展示综合得分{{ dimension === 'DAY' ? '（各题平均分）' : '（平均分）' }}</span>
        </div>
        <div class="dim-switch" role="group" aria-label="趋势维度">
          <button v-for="o in DIMENSIONS" :key="o.value" type="button"
            :class="{ active: dimension === o.value }"
            :aria-pressed="dimension === o.value" @click="setDimension(o.value)">
            {{ o.label }}
          </button>
        </div>
      </div>
      <div class="chart-wrap">
        <svg v-if="!loading && chart.points.length" :viewBox="`0 0 ${chartWidth} ${chartHeight}`" class="line-chart" role="img" aria-label="面试得分走势折线图">
          <!-- 横向参考线 + 刻度 -->
          <line
            v-for="t in chart.yTicks"
            :key="'g' + t"
            :x1="padX" :y1="tickY(t)" :x2="chartWidth - padX" :y2="tickY(t)"
            class="chart-grid"
          />
          <text v-for="t in chart.yTicks" :key="'t' + t" :x="padX - 8" :y="tickY(t) + 4"
            text-anchor="end" class="chart-tick">
            {{ t }}
          </text>
          <!-- 面积填充 -->
          <polygon class="chart-area"
            :points="areaPoints" />
          <!-- 折线 -->
          <polyline class="chart-line" :points="linePoints" />
          <!-- 数据点 -->
          <g v-for="(p, i) in chart.points" :key="i" class="chart-dot-g">
            <circle :cx="p.x" :cy="p.y" r="4.5" class="chart-dot" />
            <title>{{ p.label }} · {{ p.score }} 分 · {{ p.jobTitle }}</title>
          </g>
          <!-- X 轴刻度（v1.48.0）：按点数抽稀，窄屏不再挤成一团 -->
          <text
            v-for="p in xTickPoints"
            :key="'x' + p.x"
            :x="p.x"
            :y="chartHeight - 4"
            text-anchor="middle"
            class="chart-x-tick"
          >{{ p.label }}</text>
        </svg>
        <div v-else-if="!loading" class="empty-inline">
          <p>暂无面试记录，完成一次模拟面试后这里会呈现你的成长曲线。</p>
          <BaseButton variant="gradient" @click="$router.push('/interview')">开始第一次模拟面试</BaseButton>
        </div>
        <div v-else class="chart-skeleton"></div>
      </div>
    </section>

    <!-- 维度分析 -->
    <section class="dim-card fade-in-up">
      <div class="section-head">
        <h2>维度掌握度</h2>
        <span class="section-note">按题目分类统计平均得分</span>
      </div>
      <div v-if="!loading && categoryStats.length" class="dim-list">
        <div v-for="c in categoryStats" :key="c.category" class="dim-item">
          <div class="dim-head">
            <span class="dim-cat">{{ c.category }}</span>
            <span class="dim-score">
              <span class="num-display">{{ scoreText(c) }}</span>
              <span class="dim-count">（{{ c.total }} 题）</span>
            </span>
          </div>
          <div class="dim-track">
            <div
              class="dim-fill num-display"
              :class="dimClass(c)"
              :style="{ width: dimWidth(c) }"
            ></div>
          </div>
        </div>
      </div>
      <div v-else-if="!loading" class="empty-inline">
        <p>暂无答题数据可分析，去完成几道面试题吧。</p>
      </div>
      <div v-else class="dim-list dim-skeleton">
        <div v-for="i in 3" :key="i">
          <div class="skeleton skeleton-line w-40"></div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton } from '../components'
import type { TrendPoint } from '../utils/scoreTrend'
import { buildLineChart, computeTrendStats } from '../utils/scoreTrend'
import { pickTickIndices } from '../utils/chartTicks'
import { hasScoreSample, scoreText } from '../utils/scoreDisplay'
import {
  buildReadiness,
  gradeLabel,
  loadManual,
  progressText,
  saveManual,
  type ReadinessInput,
  type ReadinessManual,
  type ReadinessResult,
} from '../utils/readiness'

const router = useRouter()

function go(path: string) {
  void router.push(path)
}

interface CategoryStat {
  category: string
  total: number
  /** 已作答数；为 0 时该维度无数据，不显示分数 */
  answered: number
  /** 平均分；无作答时为 null（历史脏数据可能是 0） */
  avgScore: number | null
}

/** 趋势维度切换：DAY=按次，WEEK=按周聚合，MONTH=按月聚合 */
const DIMENSIONS = [
  { value: 'DAY', label: '按次' },
  { value: 'WEEK', label: '按周' },
  { value: 'MONTH', label: '按月' },
]

const chartWidth = 720
const chartHeight = 260
const padX = 40
const padY = 24

const rawPoints = ref<TrendPoint[]>([])
const categoryStats = ref<CategoryStat[]>([])
const loading = ref(true)
const dimension = ref('DAY')

const dimensionLabel = computed(() => DIMENSIONS.find((d) => d.value === dimension.value)?.label || '按次')

/**
 * 目标达成提示：
 * - 数据不足时提示沉淀目标
 * - 有趋势时依据「最新一次相对上一次」给出鼓励/提醒
 */
const goalHint = computed(() => {
  const pts = rawPoints.value
  const n = pts.length
  if (n === 0) return { title: '积累第一次数据', text: '完成一次模拟面试后，这里会呈现你的成长趋势与目标达成情况。' }
  if (n < 2) return { title: '再迈一步即可看到趋势', text: `当前已记录 ${n} 次面试，再完成 ${2 - n} 次即可开启趋势对比与目标追踪。` }
  const last = pts[n - 1]
  const prev = pts[n - 2]
  const delta = (last.score ?? 0) - (prev.score ?? 0)
  if (delta > 0) return { title: '目标达成中，稳中有升', text: `最新一次（${last.label}）较上次提升 ${delta} 分，继续保持节奏冲刺目标分。` }
  if (delta < 0) return { title: '略有回落，及时复盘', text: `最新一次（${last.label}）较上次下降 ${Math.abs(delta)} 分，建议回看复盘报告的薄弱维度针对性加练。` }
  return { title: '平台期，寻求突破', text: `最新一次（${last.label}）与上次持平，可尝试挑战更高难度题目或聚焦薄弱分类。` }
})

const stats = computed(() => computeTrendStats(rawPoints.value))
const chart = computed(() => buildLineChart(rawPoints.value, {
  width: chartWidth,
  height: chartHeight,
  paddingX: padX,
  paddingY: padY,
}))

const delta = computed(() => stats.value.delta)
const deltaText = computed(() => {
  if (stats.value.count < 2) return '完成两次后可见趋势'
  if (stats.value.delta > 0) return `较上次 +${stats.value.delta}`
  if (stats.value.delta < 0) return `较上次 ${stats.value.delta}`
  return '与上次持平'
})

const linePoints = computed(() => chart.value.points.map((p) => `${p.x},${p.y}`).join(' '))

/** X 轴刻度最多显示的标签数：图表自适应宽度后，按点数抽稀避免拥挤 */
const MAX_X_TICKS = 6
const xTickPoints = computed(() => {
  const pts = chart.value.points
  const idx = pickTickIndices(pts.length, MAX_X_TICKS)
  return idx.map((i) => pts[i])
})
const areaPoints = computed(() => {
  const pts = chart.value.points
  if (!pts.length) return ''
  return `${padX},${chartHeight - padY} ${pts.map((p) => `${p.x},${p.y}`).join(' ')} ${pts[pts.length - 1].x},${chartHeight - padY}`
})

function tickY(t: number): number {
  const ticks = chart.value.yTicks
  const top = ticks[ticks.length - 1]
  const bottom = ticks[0]
  const innerH = chartHeight - padY * 2
  if (top === bottom) return padY + innerH / 2
  const ratio = (top - t) / (top - bottom)
  return padY + ratio * innerH
}

function barWidth(score: number): string {
  return `${Math.max(4, Math.min(100, Math.round(score)))}%`
}

/** 无作答的维度：不渲染填充（min-width 也清零），避免出现「0 分空条」 */
function dimWidth(c: CategoryStat): string {
  return hasScoreSample(c) ? barWidth(c.avgScore as number) : '0%'
}

/** 低分染红只在「有作答」时生效，空数据不染红 */
function dimClass(c: CategoryStat): Record<string, boolean> {
  if (!hasScoreSample(c)) return { 'is-empty': true }
  const s = c.avgScore as number
  return { 'is-low': s < 60, 'is-mid': s >= 60 && s < 75 }
}

/** 切换趋势维度（按次/按周/按月） */
function setDimension(v: string) {
  if (dimension.value === v) return
  dimension.value = v
  loadTrend(v)
}

/** 按指定维度加载趋势数据 */
async function loadTrend(dim: string) {
  const prev = rawPoints.value
  rawPoints.value = []
  loading.value = true
  try {
    rawPoints.value = await api.get(`/api/stats/trend?dimension=${encodeURIComponent(dim)}`) as unknown as TrendPoint[]
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载成长数据失败'))
    rawPoints.value = prev
  } finally {
    loading.value = false
  }
}

// ── 准备度检测（第三批 G，零 AI）─────────────────────────────────────
/** 全空快照：未加载完成前不产出任何等级（守 R3） */
function emptySnapshot(): Omit<ReadinessInput, 'manual'> {
  return {
    resumeCount: null,
    resumeScore: null,
    favoriteCount: null,
    applicationCount: null,
    appliedCount: null,
    interviewInviteCount: null,
    finishedSessionCount: null,
    interviewAvgScore: null,
    answeredQuestionCount: null,
    questionTotal: null,
    storyCount: null,
  }
}

function toNumOrNull(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

const readinessLoading = ref(true)
const manual = ref<ReadinessManual>(loadManual())
const snapshot = ref<Omit<ReadinessInput, 'manual'>>(emptySnapshot())

/** 等级 / 清单 / 进度全部由纯函数派生（单一来源） */
const readiness = computed<ReadinessResult>(() =>
  buildReadiness({ ...snapshot.value, manual: manual.value }),
)

/** 勾选自述项并持久化 */
function toggleManual(key: keyof ReadinessManual) {
  manual.value = { ...manual.value, [key]: !manual.value[key] }
  saveManual(manual.value)
}

/**
 * 拉取准备度快照——数据全部来自**已有接口**，每项独立容错（一路失败不影响其余）。
 * @param categories 已加载的 question-summary 分类（用于刷题覆盖率，避免二次请求）
 */
async function loadReadiness(categories: CategoryStat[]) {
  readinessLoading.value = true
  const base = emptySnapshot()
  // 刷题：answered / total 合计（无分类数据 → 保持 null，不臆造 0）
  if (categories.length) {
    base.answeredQuestionCount = categories.reduce((s, c) => s + (Number.isFinite(c.answered) ? c.answered : 0), 0)
    base.questionTotal = categories.reduce((s, c) => s + (Number.isFinite(c.total) ? c.total : 0), 0)
  }
  try {
    const [dash, favs, apps, stories] = await Promise.all([
      api.get('/api/stats/dashboard').catch(() => null),
      api.get('/api/jobs/favorite').catch(() => null),
      api.get('/api/application/list').catch(() => null),
      api.get('/api/story-bank').catch(() => null),
    ])

    const d = dash as { resumeCount?: unknown; avgResumeScore?: unknown; finishedSessionCount?: unknown; avgInterviewScore?: unknown } | null
    base.resumeCount = toNumOrNull(d?.resumeCount)
    base.resumeScore = toNumOrNull(d?.avgResumeScore)
    base.finishedSessionCount = toNumOrNull(d?.finishedSessionCount)
    base.interviewAvgScore = toNumOrNull(d?.avgInterviewScore)

    const f = favs as { items?: unknown[] } | null
    base.favoriteCount = Array.isArray(f?.items) ? f.items.length : null

    const a = apps as { items?: Array<{ status?: unknown }> } | null
    if (Array.isArray(a?.items)) {
      const list = a.items
      const statusOf = (x: { status?: unknown }) => String(x?.status ?? '')
      base.applicationCount = list.length
      base.appliedCount = list.filter((x) => statusOf(x) !== 'PLANNED').length
      base.interviewInviteCount = list.filter((x) => statusOf(x) === 'INTERVIEW' || statusOf(x) === 'OFFER').length
    }

    base.storyCount = Array.isArray(stories) ? stories.length : null
  } catch (e: unknown) {
    // 理论上 .catch 已吸收单点失败；此处兜底防未预期异常中断整块
    ElMessage.error(getErrMessage(e, '加载准备度失败'))
  } finally {
    snapshot.value = base
    readinessLoading.value = false
  }
}

onMounted(async () => {
  try {
    const [trend, summary] = await Promise.all([
      api.get('/api/stats/trend?dimension=DAY') as unknown as TrendPoint[],
      api.get('/api/knowledge/question-summary') as unknown as { byCategory?: CategoryStat[] },
    ])
    rawPoints.value = trend || []
    categoryStats.value = (summary?.byCategory || [])
      .filter((c) => c.total > 0)
      .map((c) => ({
        category: c.category,
        total: c.total,
        answered: c.answered ?? 0,
        avgScore: c.avgScore ?? null,
      }))
    void loadReadiness(categoryStats.value)
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载成长数据失败'))
    // 主数据失败也要让准备度卡落到确定状态（用空分类，仍尝试其余数据源）
    void loadReadiness([])
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.progress-page { max-width: 1080px; margin: 0 auto; }
.page-header { margin-bottom: 28px; }
.page-header h1 { font-size: 28px; font-weight: 700; color: var(--c-text); margin: 0 0 6px; letter-spacing: -0.5px; }
.page-header p { font-size: 14px; color: var(--c-text-secondary); margin: 0; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 20px; }
.stat-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); padding: 20px 22px; box-shadow: var(--shadow-sm); }
.stat-label { font-family: var(--font-sans); font-size: 12px; color: var(--c-text-tertiary); margin-bottom: 8px; }
.stat-value { font-size: 40px; font-weight: 700; }
.stat-sub { font-size: 12px; color: var(--c-text-tertiary); margin-top: 6px; }
.trend-up { color: var(--c-success); }
.trend-down { color: var(--c-danger); }

.chart-card, .dim-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); padding: 24px; box-shadow: var(--shadow-sm); margin-bottom: 20px; }
.section-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 18px; }
.section-head h2 { font-size: 18px; font-weight: 600; color: var(--c-text); margin: 0; }
.section-note { font-size: 12px; color: var(--c-text-tertiary); }

/* ── 维度切换 ── */
.chart-head {
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 14px;
}
.dim-switch {
  display: inline-flex;
  gap: 4px;
  background: var(--c-bg-alt);
  border-radius: var(--radius-full);
  padding: 4px;
}
.dim-switch button {
  padding: 6px 16px;
  font-size: 13px;
  font-family: var(--font-sans);
  color: var(--c-text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
}
.dim-switch button.active {
  background: var(--c-surface);
  color: var(--brand-primary);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

/* ── 目标达成提示 ── */
.goal-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  background: linear-gradient(120deg, color-mix(in srgb, var(--brand-primary) 10%, var(--c-surface)), var(--c-surface));
  border: 1px solid color-mix(in srgb, var(--brand-primary) 35%, var(--c-border-light));
  border-radius: var(--radius-lg);
  padding: 16px 20px;
  margin-bottom: 20px;
  box-shadow: var(--shadow-sm);
}
.goal-card svg { flex-shrink: 0; margin-top: 2px; }
.goal-title { font-size: 15px; font-weight: 600; color: var(--c-text); margin-bottom: 3px; }
.goal-text { font-size: 13px; color: var(--c-text-secondary); line-height: 1.6; }

/* v1.48.0（第六轮 UX P2）：此前 min-width:560px + overflow-x:auto 会在 390px 视口下
   裁掉右侧数据点且无滚动提示。改为图表自适应容器宽度（viewBox 等比缩放），
   配合 X 轴刻度抽稀，整页不产生横向溢出。 */
.chart-wrap { width: 100%; }
.line-chart { width: 100%; height: auto; display: block; }
.chart-grid { stroke: var(--c-border); stroke-width: 1; stroke-dasharray: 3 4; }
.chart-tick { font-family: var(--font-mono); font-size: 11px; fill: var(--c-text-tertiary); }
.chart-x-tick { font-family: var(--font-mono); font-size: 10px; fill: var(--c-text-tertiary); }
.chart-area { fill: var(--brand-primary-100); opacity: 0.35; }
.chart-line { fill: none; stroke: var(--brand-primary); stroke-width: 3; stroke-linecap: round; stroke-linejoin: round; }
.chart-dot { fill: var(--c-accent); stroke: var(--c-surface); stroke-width: 2; }

.dim-list { display: flex; flex-direction: column; gap: 16px; }
.dim-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 6px; }
.dim-cat { font-family: var(--font-title); font-size: 14px; font-weight: 600; color: var(--c-text); }
.dim-score { font-size: 13px; color: var(--c-text-secondary); }
.dim-score .num-display { font-size: 15px; }
.dim-count { font-size: 12px; color: var(--c-text-tertiary); }
.dim-track { height: 8px; background: var(--c-bg-alt); border-radius: var(--radius-full); overflow: hidden; }
.dim-fill { height: 100%; background: var(--brand-primary); border-radius: var(--radius-full); min-width: 4px; transition: width var(--transition-base); }
.dim-fill.is-mid { background: var(--c-warning); }
.dim-fill.is-low { background: var(--c-danger); }
.dim-fill.is-empty { min-width: 0; background: var(--c-border); }

.empty-inline { text-align: center; padding: 36px 16px; color: var(--c-text-tertiary); }
.empty-inline p { margin: 0 0 16px; font-size: 14px; }

/* ── 准备度检测卡（第三批 G）── */
.readiness-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 22px 24px;
  box-shadow: var(--shadow-sm);
  margin-bottom: 20px;
}
.readiness-skeleton { display: flex; flex-direction: column; gap: 12px; }
.skeleton-line.w-70 { width: 70%; }
.skeleton-line.w-50 { width: 50%; }

.readiness-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.readiness-title-wrap { display: flex; align-items: center; gap: 10px; }
.readiness-title {
  font-family: var(--font-title);
  font-size: 18px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0;
  letter-spacing: -0.3px;
}
.readiness-grade {
  font-size: 12.5px;
  font-weight: 700;
  padding: 3px 12px;
  border-radius: var(--radius-full);
  color: var(--c-surface);
}
.readiness-grade.grade-A { background: var(--score-excellent); }
.readiness-grade.grade-B { background: var(--score-good); }
.readiness-grade.grade-C { background: var(--score-pass); }
.readiness-progress { font-size: 13px; color: var(--c-text-secondary); }

.readiness-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px 18px;
}
.readiness-item { min-width: 0; }
.ri-row, .ri-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  text-align: left;
  padding: 7px 10px;
  border-radius: var(--radius-sm);
  font-family: var(--font-sans);
}
.ri-btn {
  border: 1px solid var(--c-border-light);
  background: var(--c-bg-alt);
  cursor: pointer;
  transition: border-color var(--transition-fast), background var(--transition-fast);
}
.ri-btn:hover { border-color: var(--brand-primary); background: var(--brand-primary-50); }
.ri-check { flex-shrink: 0; display: inline-flex; align-items: center; color: var(--c-text-quaternary); }
.ri-dim {
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--brand-primary);
  background: var(--brand-primary-50);
  border-radius: var(--radius-sm);
  padding: 1px 7px;
}
.ri-label { font-size: 13px; color: var(--c-text); }
.readiness-item.done .ri-label { color: var(--c-text-secondary); }
.readiness-item.done .ri-check { color: var(--brand-primary); }
.ri-evidence {
  margin-left: auto;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--c-text-tertiary);
  white-space: nowrap;
}
.ri-toggle-hint {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--c-text-quaternary);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  padding: 0 5px;
}
.readiness-rule { margin: 16px 0 0; font-size: 12px; color: var(--c-text-tertiary); }

.readiness-empty { text-align: center; padding: 22px 16px 14px; }
.readiness-empty-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--brand-primary-50);
  margin-bottom: 12px;
}
.readiness-empty-title {
  margin: 0 0 6px;
  font-family: var(--font-title);
  font-size: 15px;
  font-weight: 700;
  color: var(--c-text);
}
.readiness-empty-desc { margin: 0 0 16px; font-size: 13px; color: var(--c-text-secondary); }
.readiness-empty-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
}

/* 加载骨架 */
.chart-skeleton { height: 200px; border-radius: var(--radius-md); background: linear-gradient(90deg, var(--c-bg-alt) 25%, var(--c-border-light) 37%, var(--c-bg-alt) 63%); background-size: 400% 100%; animation: skeleton-loading 1.4s ease infinite; }
.dim-skeleton { gap: 16px; }
.dim-skeleton .skeleton-line { margin-bottom: 12px; }
.skeleton { width: 100%; height: 14px; border-radius: var(--radius-sm); background: linear-gradient(90deg, var(--c-bg-alt) 25%, var(--c-border-light) 37%, var(--c-bg-alt) 63%); background-size: 400% 100%; animation: skeleton-loading 1.4s ease infinite; }
.skeleton-line.w-40 { width: 40%; }
@keyframes skeleton-loading { 0% { background-position: 100% 50%; } 100% { background-position: 0 50%; } }

@media (max-width: 768px) {
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .stat-value { font-size: 32px; }
  .readiness-list { grid-template-columns: 1fr; }
  .ri-evidence { display: none; }
}
</style>