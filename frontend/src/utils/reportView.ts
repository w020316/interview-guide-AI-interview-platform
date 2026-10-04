/**
 * 复盘报告视图模型（第三批 A · RK1「无数据 ≠ 0」降级判定）。
 *
 * <p><b>为什么要有这层纯函数</b>：
 * 历史会话的题目 `eval_detail` 为 `NULL`（早于 v1.49 的题目从未保存维度明细）。
 * 报告若把缺失的维度当作 0 渲染，会显示出「完整性 0 分」这类与事实相反的结论——
 * 这正是本项目「无数据 ≠ 0」已复发两次的缺陷。把判定收敛到可单测的纯函数里
 * （而不是散落在组件模板的 `?? 0`），才能在回归测试中真正锁死。
 *
 * <ul>
 *   <li>{@link canRenderDimensions}：单个 `eval_detail` 是否含**至少一个**有效维度。</li>
 *   <li>{@link buildReportView}：把原始作答列表归一化为可直接渲染的视图模型；
 *       无任何维度 ⇒ `degraded=true` 且 `dims=[]`（**不补 0**）。</li>
 *   <li>{@link buildScopeText}：P3-03 计数口径「本场共 N 题 · 本次作答 X · 历史已答 Y」。</li>
 * </ul>
 */

import { EMPTY } from './format'
import type { CompareStatus } from './reportCompare'

/** 「与历史成绩对比」区块的视图数据（多轮对比，仅面试结束的实时报告有） */
export interface ReportCompareView {
  status: CompareStatus
  /** 中文状态标签（进步 / 持平 / 待加强 / 首次 / 数据异常） */
  label: string
  hint: string
  /** 下一轮目标综合分 */
  nextTarget: number
}

/** 维度明细（对应后端 `eval_detail` JSON 的结构） */
export interface EvalDimensions {
  completeness?: number | null
  accuracy?: number | null
  expression?: number | null
  improvements?: string[] | null
}

/** 报告输入：一条作答（综合分来自 `evaluationScore`，维度明细来自 `evalDetail`） */
export interface ReportEvalInput {
  question: string
  category?: string
  difficulty?: string
  /** 综合分（0-100）；缺失为 `null`（**不要填 0**） */
  overallScore?: number | null
  /** 维度明细原始值：对象 / JSON 字符串 / `null` */
  evalDetail?: unknown
}

export interface ReportDimension {
  name: string
  value: number
}

export interface ReportImprovement {
  text: string
  times: number
}

export interface ReportQuestion {
  question: string
  category: string
  overallScore: number | null
}

export interface ReportView {
  /** 是否降级：整场都没有维度明细 ⇒ 不渲染维度条 */
  degraded: boolean
  /** 综合平均分；无数据为 `null` */
  overall: number | null
  /** 维度条列表；`degraded` 时为**空数组** */
  dims: ReportDimension[]
  summary: string
  improvements: ReportImprovement[]
  questions: ReportQuestion[]
  answeredCount: number
}

const DIM_KEYS = ['completeness', 'accuracy', 'expression'] as const
type DimKey = (typeof DIM_KEYS)[number]

const DIM_LABELS: Record<DimKey, string> = {
  completeness: '完整性',
  accuracy: '准确性',
  expression: '表达力',
}

function toFiniteNumber(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

/**
 * 解析单条 `eval_detail`：接受对象或 JSON 字符串。
 *
 * @returns 含有效维度时返回规范化对象；`null`/空串/解析失败/无任何有效维度 → `null`
 */
function parseDetail(detail: unknown): EvalDimensions | null {
  if (detail == null) return null
  let obj: unknown = detail
  if (typeof detail === 'string') {
    const s = detail.trim()
    if (!s) return null
    try {
      obj = JSON.parse(s)
    } catch {
      return null
    }
  }
  if (typeof obj !== 'object' || obj === null) return null
  const rec = obj as Record<string, unknown>

  const out: EvalDimensions = {}
  let hasDim = false
  for (const k of DIM_KEYS) {
    const v = toFiniteNumber(rec[k])
    if (v !== null) {
      out[k] = v
      hasDim = true
    }
  }
  const imp = rec.improvements
  if (Array.isArray(imp)) {
    out.improvements = imp.filter((x): x is string => typeof x === 'string')
  }
  // 关键：没有任何有效维度时返回 null（而不是「值全为 undefined 的对象」），
  // 否则会被误判为「有维度」。
  return hasDim ? out : null
}

/** 单个 `eval_detail` 是否可渲染出维度条（至少一个有效维度）。 */
export function canRenderDimensions(detail: unknown): boolean {
  return parseDetail(detail) !== null
}

function meanOrNull(nums: Array<number | null>): number | null {
  const valid = nums.filter((n): n is number => n !== null)
  if (!valid.length) return null
  return Math.round(valid.reduce((a, b) => a + b, 0) / valid.length)
}

function scoreLevel(score: number): string {
  if (score >= 85) return '优秀'
  if (score >= 70) return '良好'
  if (score >= 60) return '合格'
  return '待加强'
}

function aggregateImprovements(present: EvalDimensions[]): ReportImprovement[] {
  const freq = new Map<string, number>()
  for (const d of present) {
    for (const imp of d.improvements ?? []) {
      const key = imp.trim()
      if (key) freq.set(key, (freq.get(key) || 0) + 1)
    }
  }
  return [...freq.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([text, times]) => ({ text, times }))
}

function buildSummary(
  degraded: boolean,
  overall: number | null,
  answeredCount: number,
  dims: ReportDimension[],
): string {
  if (!answeredCount) return '本场暂无可展示的作答记录。'
  const overallText = overall === null ? EMPTY : String(overall)
  const level = overall === null ? '' : `（${scoreLevel(overall)}）`

  if (degraded) {
    // 不编造维度结论：旧数据只如实说明「未记录明细」
    return `本轮共记录 ${answeredCount} 题作答，综合得分 ${overallText} 分${level}。本场为早期会话，未保存分维度明细，故不展示完整性 / 准确性 / 表达力拆解。`
  }

  const ranked = (['completeness', 'accuracy', 'expression'] as DimKey[])
    .map((k) => ({ name: DIM_LABELS[k], value: dims.find((d) => d.name === DIM_LABELS[k])?.value ?? null }))
    .filter((x): x is { name: string; value: number } => x.value !== null)
    .sort((a, b) => b.value - a.value)

  if (ranked.length < 2) {
    return `本轮共回答 ${answeredCount} 题，综合得分 ${overallText} 分${level}。`
  }
  const strongest = ranked[0]
  const weakest = ranked[ranked.length - 1]
  return `本轮共回答 ${answeredCount} 题，综合得分 ${overallText} 分${level}。你的${strongest.name}是相对优势，建议继续保持；${weakest.name}是当前短板，可针对性多加练习。针对短板高频改进点，在下一次练习时有意识地调整，稳扎稳打即可稳步提升。`
}

/**
 * 归一化作答列表为报告视图模型。
 *
 * <p>维度平均**仅在有明细的作答上计算**，缺失项被跳过（绝不填 0）；整场无明细 ⇒ `degraded`。
 */
export function buildReportView(evals: ReportEvalInput[]): ReportView {
  const list = Array.isArray(evals) ? evals.filter((e): e is ReportEvalInput => e != null) : []

  const parsed = list.map((e) => parseDetail(e.evalDetail))
  const present = parsed.filter((d): d is EvalDimensions => d !== null)
  const degraded = present.length === 0

  const overall = meanOrNull(list.map((e) => toFiniteNumber(e.overallScore)))

  const dims: ReportDimension[] = []
  if (!degraded) {
    if (overall !== null) dims.push({ name: '综合', value: overall })
    for (const k of DIM_KEYS) {
      const m = meanOrNull(present.map((d) => toFiniteNumber(d[k])))
      if (m !== null) dims.push({ name: DIM_LABELS[k], value: m })
    }
  }

  const questions: ReportQuestion[] = list.map((e) => ({
    question: e.question,
    category: e.category || '',
    overallScore: toFiniteNumber(e.overallScore),
  }))

  return {
    degraded,
    overall,
    dims,
    summary: buildSummary(degraded, overall, list.length, dims),
    improvements: aggregateImprovements(present),
    questions,
    answeredCount: list.length,
  }
}

/** 取某维度在视图中的值（无则 `null`）——供导出 PDF / 分享卡片复用同一来源。 */
export function findDim(view: ReportView, name: string): number | null {
  return view.dims.find((d) => d.name === name)?.value ?? null
}

/**
 * P3-03 计数口径：把会误导的单一口径拆成三段——
 * 「本场共 N 题 · 本次作答 X · 历史已答 Y」。
 */
export function buildScopeText(total: number, thisRound: number, history: number): string {
  const t = Number.isFinite(total) && total > 0 ? total : 0
  const r = Number.isFinite(thisRound) && thisRound > 0 ? thisRound : 0
  const h = Number.isFinite(history) && history > 0 ? history : 0
  return `本场共 ${t} 题 · 本次作答 ${r} · 历史已答 ${h}`
}
