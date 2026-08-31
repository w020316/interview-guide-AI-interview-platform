<template>
  <div class="progress-page">
    <header class="page-header">
      <h1>成长趋势</h1>
      <p>用数据看见每一次面试的进步，定位薄弱维度</p>
    </header>

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
              <span class="num-display">{{ c.avgScore }}</span>
              <span class="dim-count">（{{ c.total }} 题）</span>
            </span>
          </div>
          <div class="dim-track">
            <div
              class="dim-fill num-display"
              :class="{ 'is-low': c.avgScore < 60, 'is-mid': c.avgScore >= 60 && c.avgScore < 75 }"
              :style="{ width: barWidth(c.avgScore) }"
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
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton } from '../components'
import type { TrendPoint } from '../utils/scoreTrend'
import { buildLineChart, computeTrendStats } from '../utils/scoreTrend'

interface CategoryStat { category: string; total: number; avgScore: number }

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

onMounted(async () => {
  try {
    const [trend, summary] = await Promise.all([
      api.get('/api/stats/trend?dimension=DAY') as unknown as TrendPoint[],
      api.get('/api/knowledge/question-summary') as unknown as { byCategory?: CategoryStat[] },
    ])
    rawPoints.value = trend || []
    categoryStats.value = summary?.byCategory?.filter((c) => c.total > 0) || []
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载成长数据失败'))
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.progress-page { max-width: 980px; margin: 0 auto; }
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
  border-radius: 999px;
  padding: 4px;
}
.dim-switch button {
  padding: 6px 16px;
  font-size: 13px;
  font-family: var(--font-sans);
  color: var(--c-text-secondary);
  background: transparent;
  border: none;
  border-radius: 999px;
  cursor: pointer;
  transition: all var(--transition-fast);
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

.chart-wrap { width: 100%; overflow-x: auto; }
.line-chart { width: 100%; min-width: 560px; height: auto; display: block; }
.chart-grid { stroke: var(--c-border); stroke-width: 1; stroke-dasharray: 3 4; }
.chart-tick { font-family: var(--font-mono); font-size: 11px; fill: var(--c-text-tertiary); }
.chart-area { fill: var(--brand-primary-100); opacity: 0.35; }
.chart-line { fill: none; stroke: var(--brand-primary); stroke-width: 3; stroke-linecap: round; stroke-linejoin: round; }
.chart-dot { fill: var(--c-accent); stroke: var(--c-surface); stroke-width: 2; }

.dim-list { display: flex; flex-direction: column; gap: 16px; }
.dim-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 6px; }
.dim-cat { font-family: var(--font-serif); font-size: 14px; font-weight: 600; color: var(--c-text); }
.dim-score { font-size: 13px; color: var(--c-text-secondary); }
.dim-score .num-display { font-size: 15px; }
.dim-count { font-size: 12px; color: var(--c-text-tertiary); }
.dim-track { height: 8px; background: var(--c-bg-alt); border-radius: 999px; overflow: hidden; }
.dim-fill { height: 100%; background: var(--brand-primary); border-radius: 999px; min-width: 4px; transition: width var(--transition-base); }
.dim-fill.is-mid { background: var(--c-warning); }
.dim-fill.is-low { background: var(--c-danger); }

.empty-inline { text-align: center; padding: 36px 16px; color: var(--c-text-tertiary); }
.empty-inline p { margin: 0 0 16px; font-size: 14px; }

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
}
</style>