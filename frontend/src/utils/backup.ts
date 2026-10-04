/**
 * 个人数据导出 / 导入（第三批 C）—— 纯前端辅助：文件名、指纹同构预校验、结果摘要。
 *
 * <p><b>指纹语义</b>：后端 {@code BackupService#fingerprintOf} 是<b>唯一权威</b>——
 * 它对 7 个集合拼接 {@code "<name>:<count>:<max(ts)>;"} 后取 {@code sha256} 前 16 位。
 * 本模块的 {@link fingerprintSource} 只复刻这段<b>规范化字符串</b>（同构预校验），
 * 用于「导出文件 vs 当前数据是否已不同」的本地告警；真正的冲突判定仍以后端
 * {@code currentFingerprint} / HTTP 409 为准，前端不自行计算 sha256（避免环境差异）。
 *
 * <p>纯函数、零网络、零外部依赖。
 */

import { isConflictError } from './httpError'

/** 与后端 BackupService.SCHEMA_VERSION 对齐 */
export const BACKUP_SCHEMA_VERSION = '3'

export const EXPORT_PATH = '/api/me/export'
export const IMPORT_PATH = '/api/me/import'

/** 导出结构中的各集合计数 */
export interface BackupCounts {
  resumes: number
  sessions: number
  questions: number
  applications: number
  events: number
  favoriteQuestions: number
  storyBank: number
  jobFavorites: number
}

/** 导出/备份文件结构（字段按后端实现为准，冗余字段可选） */
export interface BackupPayload {
  schemaVersion?: string
  exportedAt?: string
  includeConversations?: boolean
  dataFingerprint?: string
  counts?: Partial<BackupCounts>
  data?: Record<string, unknown>
}

export type ImportMode = 'merge' | 'replace'

export interface ImportModeOption {
  value: ImportMode
  label: string
  hint: string
}

/** 导入模式（merge 合并 / replace 覆盖） */
export const IMPORT_MODES: ImportModeOption[] = [
  { value: 'merge', label: '合并', hint: '保留现有数据，仅追加备份中的记录' },
  { value: 'replace', label: '覆盖', hint: '用备份替换现有数据（数据有变时会要求二次确认）' },
]

export interface ImportSummary {
  added: number
  merged: number
  skipped: number
  warnings: number
}

/** dryRun 预览结果 */
export interface ImportDryRunResult {
  mode: ImportMode
  dryRun: boolean
  canApply: boolean
  currentFingerprint: string
  summary: ImportSummary
  warnings: Array<Record<string, unknown>>
  fatalErrors: Array<Record<string, unknown>>
  applied: boolean
  message?: string
}

/** apply 成功结果 */
export interface ImportApplyResult extends ImportDryRunResult {
  applied: true
  importId?: string
  appliedCounts?: ImportSummary
  idempotent?: boolean
}

/** 参与指纹计算、以及各自使用的时间戳字段（与后端 fingerprintOf 完全一致） */
const FINGERPRINT_COLLECTIONS: Array<{ name: string; field: 'createdAt' | 'updatedAt' }> = [
  { name: 'resumes', field: 'createdAt' },
  { name: 'sessions', field: 'createdAt' },
  { name: 'applications', field: 'updatedAt' },
  { name: 'events', field: 'createdAt' },
  { name: 'favoriteQuestions', field: 'createdAt' },
  { name: 'storyBank', field: 'updatedAt' },
  { name: 'jobFavorites', field: 'createdAt' },
]

function two(n: number): string {
  return String(n).padStart(2, '0')
}

/** 导出文件名：`interview-guide-backup-YYYYMMDD-HHmmss.json` */
export function exportFileName(now: Date = new Date()): string {
  const stamp = `${now.getFullYear()}${two(now.getMonth() + 1)}${two(now.getDate())}`
  const time = `${two(now.getHours())}${two(now.getMinutes())}${two(now.getSeconds())}`
  return `interview-guide-backup-${stamp}-${time}.json`
}

function arr(v: unknown): unknown[] {
  return Array.isArray(v) ? v : []
}

/** 各集合计数（questions 由 sessions[].questions 汇总，与后端 counts 口径一致） */
export function collectionStats(data: Record<string, unknown> | null | undefined): BackupCounts {
  const d = data || {}
  const sessions = arr(d.sessions)
  const questions = sessions.reduce<number>(
    (n, s) => n + arr((s as Record<string, unknown> | null)?.['questions']).length,
    0,
  )
  return {
    resumes: arr(d.resumes).length,
    sessions: sessions.length,
    questions,
    applications: arr(d.applications).length,
    events: arr(d.events).length,
    favoriteQuestions: arr(d.favoriteQuestions).length,
    storyBank: arr(d.storyBank).length,
    jobFavorites: arr(d.jobFavorites).length,
  }
}

/** 时间戳取值：字符串直接用；Jackson 数组形式（[y,m,d,h,mi,s,ns]）兜底 join */
function timestampOf(value: unknown): string | null {
  if (value === null || value === undefined) return null
  if (typeof value === 'string') return value === '' ? null : value
  if (Array.isArray(value)) return value.join(',')
  return String(value)
}

function collectionPart(rows: unknown, name: string, field: 'createdAt' | 'updatedAt'): string {
  const list = arr(rows)
  let max: string | null = null
  for (const r of list) {
    const v = timestampOf((r as Record<string, unknown> | null)?.[field])
    if (v !== null && (max === null || v > max)) max = v
  }
  return `${name}:${list.length}:${max ?? '-'};`
}

/**
 * 复刻后端指纹的**规范化字符串**（同构预校验）。
 *
 * <p>形如 `resumes:3:2026-09-01T10:00;applications:2:-;...`。空数据时每段为 `<name>:0:-;`。
 */
export function fingerprintSource(data: Record<string, unknown> | null | undefined): string {
  const d = data || {}
  return FINGERPRINT_COLLECTIONS
    .map((c) => collectionPart(d[c.name], c.name, c.field))
    .join('')
}

/** 导入摘要的展示行（无值时按 0，真实计数不臆造） */
export function describeImportSummary(summary: Partial<ImportSummary> | null | undefined): string[] {
  const s = summary || {}
  return [
    `新增 ${s.added ?? 0}`,
    `合并 ${s.merged ?? 0}`,
    `跳过 ${s.skipped ?? 0}`,
    `警告 ${s.warnings ?? 0}`,
  ]
}

/** 导出文件指纹与当前数据指纹是否不一致（两者都有值且不同） */
export function isFingerprintMismatch(fileFingerprint?: string | null, currentFingerprint?: string | null): boolean {
  if (!fileFingerprint || !currentFingerprint) return false
  return fileFingerprint !== currentFingerprint
}

/**
 * 是否为后端指纹冲突（HTTP 409）——仅 apply + replace + 指纹不符 + 未 force 才有。
 *
 * <p>实现委托给 {@link isConflictError}（单一来源）：状态码判定只允许有一份实现，
 * 否则「按 409 分流」的逻辑会随使用点增多而各自漂移。
 */
export function isFingerprintConflict(e: unknown): boolean {
  return isConflictError(e)
}

/**
 * 解析备份文件文本为 {@link BackupPayload}（严格校验，抛出可读错误）。
 *
 * @throws 非法 JSON / 顶层非对象 / 缺少 data 字段时抛出
 */
export function parseBackupFile(text: string): BackupPayload {
  let obj: unknown
  try {
    obj = JSON.parse(text)
  } catch {
    throw new Error('文件不是有效的 JSON，无法导入')
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    throw new Error('备份文件格式不正确（顶层应为对象）')
  }
  const payload = obj as BackupPayload
  if (!payload.data || typeof payload.data !== 'object' || Array.isArray(payload.data)) {
    throw new Error('备份文件缺少 data 字段，无法导入')
  }
  return payload
}
