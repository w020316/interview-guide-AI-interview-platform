/**
 * 导出体检（v1.85.0）—— 形状兜底与文案映射。
 *
 * 后端 `/api/resume/export-check` 用 PDFBox **纯本地规则**判定这份 PDF 能不能被
 * 招聘系统（ATS）正常读取，全程不调用 AI、不保存文件。
 *
 * 这里只做三件事，全部可单测：
 * 1. {@link normalizeExportReport} 在**赋值那一刻**校验形状 —— 缺检查项、字段类型变了、
 *    返回了数组 → 统一判为「没拿到结果」，由弹窗展示可操作的错误态，而不是渲染半张空白报告。
 * 2. {@link deriveVerdict} / {@link stricterVerdict} —— 结论只能比证据**更差**，不能更好：
 *    服务端若把有 fail 项的报告标成 pass（字段漂移、手工改数据），界面按 fail 显示。
 * 3. {@link formatCount} —— 缺失数据一律「—」，**绝不当 0 显示**（0 页 / 0 字是另一个含义）。
 */

export type ExportCheckStatus = 'pass' | 'warn' | 'fail'
export type ExportCheckVerdict = ExportCheckStatus

export interface ExportCheckItem {
  id: string
  label: string
  status: ExportCheckStatus
  detail: string
}

export interface ExportCheckReport {
  verdict: ExportCheckVerdict
  summary: string
  /** 页数；服务端未给出时为 null（界面显示「—」，不是 0 页） */
  pageCount: number | null
  /** 可提取的文字数；图片型 PDF 这里是真实的 0 */
  charCount: number | null
  checks: ExportCheckItem[]
}

/** 与后端 ResumeExportCheckService.MAX_FILE_BYTES 保持一致 */
export const PDF_MAX_BYTES = 10 * 1024 * 1024

const STATUSES: readonly ExportCheckStatus[] = ['pass', 'warn', 'fail']

const VERDICT_LABEL: Record<ExportCheckVerdict, string> = {
  pass: '通过',
  warn: '有提醒',
  fail: '需要修复',
}

const STATUS_LABEL: Record<ExportCheckStatus, string> = {
  pass: '通过',
  warn: '留意',
  fail: '未通过',
}

/** 服务端没给 summary 时的兜底说法（措辞与后端保持一致，避免两处口径打架） */
const VERDICT_HEADLINE: Record<ExportCheckVerdict, string> = {
  pass: '这份 PDF 可以被招聘系统正常读取',
  warn: '有需要留意的项目，建议修复后重新导出',
  fail: '这份 PDF 大概率无法被招聘系统正常读取',
}

const SEVERITY: Record<ExportCheckVerdict, number> = { pass: 0, warn: 1, fail: 2 }

export function verdictLabel(v: ExportCheckVerdict): string {
  return VERDICT_LABEL[v]
}

export function statusLabel(s: ExportCheckStatus): string {
  return STATUS_LABEL[s]
}

/** 取两方结论中更差的一个（结论不得优于证据） */
export function stricterVerdict(a: ExportCheckVerdict, b: ExportCheckVerdict): ExportCheckVerdict {
  return SEVERITY[a] >= SEVERITY[b] ? a : b
}

/** 由检查项推导结论：任一 fail → fail；任一 warn → warn；全 pass → pass */
export function deriveVerdict(checks: ExportCheckItem[]): ExportCheckVerdict {
  if (checks.some((c) => c.status === 'fail')) return 'fail'
  if (checks.some((c) => c.status === 'warn')) return 'warn'
  return 'pass'
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v)
}

function asStatus(v: unknown): ExportCheckStatus {
  // 认不出的状态按 warn 处理：宁可多提醒一次，也不能把未知当成「通过」
  return STATUSES.includes(v as ExportCheckStatus) ? (v as ExportCheckStatus) : 'warn'
}

function asCount(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) && v >= 0 ? Math.floor(v) : null
}

function asText(v: unknown): string {
  return typeof v === 'string' ? v.trim() : ''
}

/**
 * 把接口返回值归一化为报告；形状不足以支撑判定时返回 null。
 *
 * @returns null 表示「没拿到可用的体检结果」，调用方应展示错误态而不是空报告
 */
export function normalizeExportReport(raw: unknown): ExportCheckReport | null {
  if (!isRecord(raw)) return null

  const checks: ExportCheckItem[] = []
  const list = Array.isArray(raw.checks) ? raw.checks : []
  for (const item of list) {
    if (!isRecord(item)) continue
    const id = asText(item.id)
    const label = asText(item.label) || id
    // id 与 label 都缺失的条目没有展示价值，丢弃（不能凭空造一个检查项）
    if (!label) continue
    checks.push({
      id: id || label,
      label,
      status: asStatus(item.status),
      detail: asText(item.detail),
    })
  }
  if (!checks.length) return null

  const derived = deriveVerdict(checks)
  const declared = STATUSES.includes(raw.verdict as ExportCheckVerdict)
    ? (raw.verdict as ExportCheckVerdict)
    : derived
  const verdict = stricterVerdict(declared, derived)

  return {
    verdict,
    summary: asText(raw.summary) || VERDICT_HEADLINE[verdict],
    pageCount: asCount(raw.pageCount),
    charCount: asCount(raw.charCount),
    checks,
  }
}

/** 数字展示：null → 「—」；千分位分组（避免 12345 字读成 1 万 2 千 3 百 45） */
export function formatCount(n: number | null): string {
  if (n === null) return '—'
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/**
 * 上传前的本地预校验（与后端同一套规则与文案）。
 *
 * @returns 错误文案；null 表示可以上传
 */
export function validatePdfFile(name: string, size: number): string | null {
  if (!name.trim()) return '请选择要体检的 PDF 文件'
  if (!/\.pdf$/i.test(name.trim())) {
    return '导出体检只支持 PDF：请先在编辑器点「导出 PDF」，再把保存下来的文件上传'
  }
  if (size > PDF_MAX_BYTES) return '文件大小不能超过 10MB'
  if (size <= 0) return '这个文件是空的，请重新导出后再试'
  return null
}
