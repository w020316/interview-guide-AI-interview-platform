/**
 * 面试成绩成长趋势的数据处理工具（纯函数，便于单元测试）
 *
 * 输入来自后端 GET /api/stats/trend，输出用于折线图几何计算与统计摘要。
 */

export interface TrendPoint {
  seq: number
  date: string
  label: string
  score: number
  questionCount: number
  jobTitle: string
  sessionId: string
}

export interface TrendStats {
  count: number
  average: number
  max: number
  min: number
  latest: number
  /** 最近两次得分的差值（正=上升，负=下滑），不足两次为 0 */
  delta: number
  /** 各次得分，仅含有效数字 */
  scores: number[]
}

export interface ChartSize {
  width: number
  height: number
  paddingX: number
  paddingY: number
}

export interface ChartPoint {
  x: number
  y: number
  score: number
  label: string
  jobTitle: string
}

export interface TrendStatsResult {
  stats: TrendStats
  /** 规范化后的折线图坐标点 */
  points: ChartPoint[]
  /** Y 轴刻度（升序） */
  yTicks: number[]
}

/**
 * 计算成绩统计摘要。忽略得分缺失/为空的点。
 */
export function computeTrendStats(pointsInput: TrendPoint[] | null | undefined): TrendStats {
  const scores = (pointsInput ?? [])
    .map((p) => p.score)
    .filter((s: number) => typeof s === 'number' && isFinite(s))

  if (scores.length === 0) {
    return { count: scores.length, average: 0, max: 0, min: 0, latest: 0, delta: 0, scores: [] }
  }

  const list = [...scores].sort((a, b) => a - b)
  const count = list.length
  const sum = list.reduce((a, b) => a + b, 0)
  const average = Math.round((sum / count) * 10) / 10
  const max = list[count - 1]
  const min = list[0]
  const latest = list[count - 1]

  let delta = 0
  if (count >= 2 && scores.length >= 2) {
    delta = Math.round((scores[scores.length - 1] - scores[scores.length - 2]) * 10) / 10
  }

  return { count: scores.length, average, max, min, latest, delta, scores }
}

/** 四舍五入到 10，便于生成刻度 */
function roundToTen(v: number): number {
  return Math.round(v / 10) * 10
}

/**
 * 将趋势点映射为折线图坐标（含 X 均匀分布、Y 按分数归一化）。
 * 空数据返回空点集与默认刻度。
 */
export function buildLineChart(
  pointsInput: TrendPoint[] | null | undefined,
  size: ChartSize,
): TrendStatsResult {
  const stats = computeTrendStats(pointsInput)
  const pts = (pointsInput ?? []).filter((p) => typeof p.score === 'number' && isFinite(p.score))
  const n = pts.length
  const { width, height, paddingX, paddingY } = size

  if (n === 0) {
    return { stats, points: [], yTicks: [0, 50, 100] }
  }

  const all = [...stats.scores, stats.min, stats.max]
  const yMin = Math.min(...all, 0)
  const yMax = Math.max(...all, 100)
  // 取整刻度：下取整到十、上取整到十，至少保留 30 分间隔
  const low = Math.floor((yMin - 5) / 10) * 10
  const high = Math.ceil((yMax + 5) / 10) * 10
  const yTop = Math.max(low, 0)
  const yBottom = Math.max(high, high - yTop > 30 ? high : yTop + 30)

  const yTicks = [yTop, Math.round((yTop + yBottom) / 20) * 10, yBottom]
    .filter((v, i, arr) => arr.indexOf(v) === i)
    .sort((a, b) => a - b)

  const innerW = width - paddingX * 2
  const innerH = height - paddingY * 2

  const points: ChartPoint[] = pts.map((p, i) => {
    const x = n === 1 ? width / 2 : paddingX + (i * innerW) / (n - 1)
    const ratio = yBottom === yTop ? 0.5 : (yBottom - p.score) / (yBottom - yTop)
    const y = paddingY + ratio * innerH
    return { x, y, score: p.score, label: p.label, jobTitle: p.jobTitle }
  })

  return { stats, points, yTicks }
}