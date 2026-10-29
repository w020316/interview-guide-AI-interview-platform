/**
 * 谈薪助手（v1.85.0）—— 请求组装、报告形状兜底与金额展示。
 *
 * ── 三条与后端对齐的硬约定 ─────────────────────────────────────────
 * 1. **不编造行情**：本模块不产生任何市场薪资数字，只把用户自己填的金额做换算展示；
 *    面板文案也明确写「AI 不掌握实时行情」。
 * 2. **缺失就是 null**：任何一项没填/算不出，服务端返回 null，界面显示「—」。
 *    绝不补 0 ——「涨幅 0%」是一条看起来像事实的假信息。
 * 3. **填错不能静默当没填**：数字格式错或越界，本地立刻提示（与后端同一套文案），
 *    否则用户会以为建议已经考虑了他的薪资，而模型拿到的是「未填写」。
 */

export type SalaryMode = 'EXPECTATION' | 'OFFER' | 'NEGOTIATION'

export interface SalaryModeMeta {
  value: SalaryMode
  label: string
  hint: string
  /** 当前场景下「哪些数字值得填」的说明 */
  fieldNote: string
}

export const SALARY_MODES: readonly SalaryModeMeta[] = [
  {
    value: 'EXPECTATION',
    label: '被问期望薪资',
    hint: '还没 offer，先想好怎么报',
    fieldNote: '报价至少填「期望月薪」；填了当前月薪才能对比你想要多少涨幅。',
  },
  {
    value: 'OFFER',
    label: '评估 offer',
    hint: '拿到了，值不值得接',
    fieldNote: '填上 offer 月薪与年薪月数，才能算出年薪折算与涨幅。',
  },
  {
    value: 'NEGOTIATION',
    label: '正在还价',
    hint: '被压价，怎么谈、怎么应对僵局',
    fieldNote: '填上 offer 月薪与期望月薪，才能算出还差多少。',
  },
]

export function modeMeta(mode: SalaryMode): SalaryModeMeta {
  return SALARY_MODES.find((m) => m.value === mode) ?? SALARY_MODES[0]
}

export interface SalaryAnalysis {
  point: string
  basis: string
}

export interface SalaryScript {
  scene: string
  say: string
}

export interface SalaryQa {
  q: string
  a: string
}

/** 服务端算出的金额；任何一项算不出就是 null（界面显示「—」） */
export interface SalaryNumbers {
  raiseAmount: number | null
  raisePct: number | null
  offerAnnual: number | null
  expectedGapMonthly: number | null
  expectedGapPct: number | null
}

export interface SalaryReport {
  headline: string
  analysis: SalaryAnalysis[]
  scripts: SalaryScript[]
  qa: SalaryQa[]
  warnings: string[]
  actions: string[]
  numbers: SalaryNumbers
}

/** 表单状态：数字字段一律用字符串保存（输入框原样，提交时才校验与转换） */
export interface SalaryForm {
  mode: SalaryMode
  targetJob: string
  city: string
  years: string
  currentMonthly: string
  expectedMonthly: string
  offerMonthly: string
  offerMonths: string
  competing: string
  situation: string
}

export function emptySalaryForm(): SalaryForm {
  return {
    mode: 'EXPECTATION',
    targetJob: '',
    city: '',
    years: '',
    currentMonthly: '',
    expectedMonthly: '',
    offerMonthly: '',
    offerMonths: '',
    competing: '',
    situation: '',
  }
}

/** 数值字段的标签与边界（与后端 CareerController.number 保持一致） */
const NUMBER_FIELDS: ReadonlyArray<{
  key: 'years' | 'currentMonthly' | 'expectedMonthly' | 'offerMonthly' | 'offerMonths'
  label: string
  min: number
  max: number
}> = [
  { key: 'years', label: '工作年限', min: 0, max: 60 },
  { key: 'currentMonthly', label: '当前月薪', min: 1, max: 1_000_000 },
  { key: 'expectedMonthly', label: '期望月薪', min: 1, max: 1_000_000 },
  { key: 'offerMonthly', label: 'offer 月薪', min: 1, max: 1_000_000 },
  { key: 'offerMonths', label: '年薪月数', min: 1, max: 36 },
]

export interface SalaryPayloadResult {
  payload: Record<string, unknown>
  /** 本地校验失败的原因；null 表示可以提交 */
  error: string | null
}

/**
 * 表单 → 请求体；数字字段空串转 null，格式错/越界直接返回错误（不提交、不消耗配额）。
 */
export function buildSalaryPayload(form: SalaryForm): SalaryPayloadResult {
  const targetJob = form.targetJob.trim()
  if (!targetJob) return { payload: {}, error: '请填写目标岗位' }

  const payload: Record<string, unknown> = {
    mode: form.mode,
    targetJob,
    city: form.city.trim(),
    competing: form.competing.trim(),
    situation: form.situation.trim(),
  }

  for (const field of NUMBER_FIELDS) {
    const raw = form[field.key].trim()
    if (!raw) {
      payload[field.key] = null
      continue
    }
    const parsed = Number(raw)
    if (!Number.isFinite(parsed)) return { payload: {}, error: `${field.label}请填写数字` }
    const rounded = Math.round(parsed)
    if (rounded < field.min || rounded > field.max) {
      return { payload: {}, error: `${field.label}需在 ${field.min} ~ ${field.max} 之间` }
    }
    payload[field.key] = rounded
  }

  return { payload, error: null }
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v)
}

function asText(v: unknown): string {
  return typeof v === 'string' ? v.trim() : ''
}

function asList(v: unknown): unknown[] {
  return Array.isArray(v) ? v : []
}

function finiteOrNull(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

function toTextList(v: unknown): string[] {
  return asList(v)
    .map(asText)
    .filter((s) => s !== '')
}

function toAnalysis(v: unknown): SalaryAnalysis | null {
  if (!isRecord(v)) return null
  const point = asText(v.point)
  const basis = asText(v.basis)
  return point || basis ? { point, basis } : null
}

function toScript(v: unknown): SalaryScript | null {
  if (!isRecord(v)) return null
  const scene = asText(v.scene)
  const say = asText(v.say)
  return scene || say ? { scene, say } : null
}

function toQa(v: unknown): SalaryQa | null {
  if (!isRecord(v)) return null
  const q = asText(v.q)
  const a = asText(v.a)
  return q || a ? { q, a } : null
}

function toNumbers(v: unknown): SalaryNumbers {
  const r = isRecord(v) ? v : {}
  return {
    raiseAmount: finiteOrNull(r.raiseAmount),
    raisePct: finiteOrNull(r.raisePct),
    offerAnnual: finiteOrNull(r.offerAnnual),
    expectedGapMonthly: finiteOrNull(r.expectedGapMonthly),
    expectedGapPct: finiteOrNull(r.expectedGapPct),
  }
}

function compact<T>(items: Array<T | null>): T[] {
  return items.filter((x): x is T => x !== null)
}

/**
 * 把接口返回值归一化为报告；完全没有任何内容时返回 null（界面展示错误态）。
 */
export function normalizeSalaryReport(raw: unknown): SalaryReport | null {
  if (!isRecord(raw)) return null

  const headline = asText(raw.headline)
  const analysis = compact(asList(raw.analysis).map(toAnalysis))
  const scripts = compact(asList(raw.scripts).map(toScript))
  const qa = compact(asList(raw.qa).map(toQa))
  const warnings = toTextList(raw.warnings)
  const actions = toTextList(raw.actions)

  const hasContent =
    headline !== '' ||
    analysis.length > 0 ||
    scripts.length > 0 ||
    qa.length > 0 ||
    warnings.length > 0 ||
    actions.length > 0
  if (!hasContent) return null

  return {
    headline: headline || '这次没有生成有效建议，请稍后重试',
    analysis,
    scripts,
    qa,
    warnings,
    actions,
    numbers: toNumbers(raw.numbers),
  }
}

/** 是否至少有一项金额可算（全 null 时界面显示「填数字才能算」的说明，而不是一排 0） */
export function hasSalaryNumbers(n: SalaryNumbers): boolean {
  return (
    n.raiseAmount !== null ||
    n.raisePct !== null ||
    n.offerAnnual !== null ||
    n.expectedGapMonthly !== null ||
    n.expectedGapPct !== null
  )
}

function group(n: number): string {
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 金额：null → 「—」 */
export function formatMoney(v: number | null): string {
  return v === null ? '—' : `${group(v)} 元`
}

/** 带正负号的金额（涨幅 / 差额）：null → 「—」，0 → 「0 元」 */
export function formatSignedMoney(v: number | null): string {
  if (v === null) return '—'
  if (v === 0) return '0 元'
  return `${v > 0 ? '+' : '-'}${group(Math.abs(v))} 元`
}

/** 百分比（服务端已保留 1 位小数）：null → 「—」 */
export function formatPercent(v: number | null): string {
  if (v === null) return '—'
  const rounded = Math.round(v * 10) / 10
  return `${rounded > 0 ? '+' : ''}${rounded}%`
}
