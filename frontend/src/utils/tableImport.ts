/**
 * 表格导入解析（第三批 F）—— **零依赖、纯函数、零网络**。
 *
 * <p>职责：把逗号/制表符分隔的文本（CSV/TSV）解析成二维表格，自动识别表头、
 * 推断列映射，并归一化成后端 `POST /api/application/import` 所需的行结构。
 *
 * <p>`.xlsx` 的字节解析由 {@link File} 交给 `xlsxLazy.ts`（动态加载 read-excel-file），
 * 本模块只吃「已解析好的字符串网格」，因此可零成本单测。
 *
 * <p><b>合规（R1）</b>：解析只在本地进行，不联网、不调用 AI；导入的行只进本地台账，
 * 不代表任何投递动作。
 */

/** 单次导入行数上限（与后端 ApplicationImportService.MAX_ROWS 对齐） */
export const MAX_IMPORT_ROWS = 200

/** 后端需要的台账字段 */
export type ImportField =
  | 'companyName'
  | 'title'
  | 'location'
  | 'salary'
  | 'deadline'
  | 'applyUrl'
  | 'status'
  | 'note'

export interface ImportFieldDef {
  field: ImportField
  label: string
  /** 是否为必填（缺失即致命错误行） */
  required: boolean
  /** 表头别名（用于自动列映射） */
  aliases: string[]
}

/** 字段定义（顺序即展示顺序） */
export const IMPORT_FIELDS: ImportFieldDef[] = [
  { field: 'companyName', label: '公司名称', required: true, aliases: ['公司', '公司名称', '企业', '企业名称', '单位', 'company', 'companyname', 'employer'] },
  { field: 'title', label: '岗位名称', required: true, aliases: ['岗位', '岗位名称', '职位', '职位名称', 'title', 'position', 'job', 'jobtitle', 'role'] },
  { field: 'location', label: '工作地点', required: false, aliases: ['地点', '工作地点', '城市', 'location', 'city'] },
  { field: 'salary', label: '薪资', required: false, aliases: ['薪资', '薪水', '待遇', '工资', 'salary', 'pay'] },
  { field: 'deadline', label: '截止日期', required: false, aliases: ['截止', '截止日期', '截止时间', 'deadline', 'duedate', 'enddate'] },
  { field: 'applyUrl', label: '投递链接', required: false, aliases: ['链接', '投递链接', '申请链接', '官网', 'applyurl', 'url', 'link'] },
  { field: 'status', label: '当前状态', required: false, aliases: ['状态', '进度', '当前状态', 'status', 'stage'] },
  { field: 'note', label: '备注', required: false, aliases: ['备注', '说明', 'note', 'remark', 'memo'] },
]

export interface ParsedTable {
  /** 列标题（无表头时自动生成「第 N 列」） */
  headers: string[]
  /** 全部非空行（**含表头行**，列索引与 mapping 一致） */
  rows: string[][]
  /** 是否把首行识别为表头 */
  hasHeader: boolean
}

/** 列映射：字段 → 列索引（未映射则该字段不导入） */
export type ColumnMapping = Partial<Record<ImportField, number>>

/** 归一化后的导入行（与后端字段名一致） */
export interface ImportRow {
  companyName: string
  title: string
  location?: string
  salary?: string
  deadline?: string
  applyUrl?: string
  status?: string
  note?: string
}

export type SpreadsheetKind = 'csv' | 'tsv' | 'xlsx' | 'xls' | 'unknown'

/** 文件选择框的 accept 值 */
export const IMPORT_ACCEPT = '.xlsx,.xls,.csv,.tsv'

/** 旧版 .xls 的显式引导文案（best-effort 解析失败时展示） */
export const XLS_SAVE_AS_CSV_HINT = '暂不支持旧版 .xls 直接解析，请在 Excel/WPS 中「另存为 CSV (.csv)」后再导入'

/** 依据文件名判断表格类型 */
export function fileKind(name: string): SpreadsheetKind {
  const lower = (name || '').toLowerCase()
  if (lower.endsWith('.csv')) return 'csv'
  if (lower.endsWith('.tsv')) return 'tsv'
  if (lower.endsWith('.xlsx')) return 'xlsx'
  if (lower.endsWith('.xls')) return 'xls'
  return 'unknown'
}

/** 传给后端的 source 标签（后端仅回显） */
export function sourceLabel(kind: SpreadsheetKind): string {
  switch (kind) {
    case 'csv': return 'csv'
    case 'tsv': return 'tsv'
    case 'xlsx':
    case 'xls': return 'excel'
    default: return 'paste'
  }
}

/** 去掉 UTF-8 BOM */
function stripBom(s: string): string {
  return s.charCodeAt(0) === 0xfeff ? s.slice(1) : s
}

/** 依扩展名（优先）或首行分隔符数量嗅探分隔符 */
export function detectDelimiter(filename: string, text: string): ',' | '\t' {
  const kind = fileKind(filename)
  if (kind === 'tsv') return '\t'
  if (kind === 'csv') return ','
  const line = text.split(/\r?\n/).find((l) => l.trim() !== '') || ''
  const tabs = (line.match(/\t/g) || []).length
  const commas = (line.match(/,/g) || []).length
  return tabs > commas ? '\t' : ','
}

/**
 * RFC4180 分隔符解析：支持引号包裹、`""` 转义引号、字段内换行、CRLF/CR/LF。
 *
 * @returns 二维字符串网格（保留空行，交由 {@link detectTable} 过滤）
 */
export function parseDelimited(text: string, delimiter: ',' | '\t'): string[][] {
  const s = stripBom(text).replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let inQuotes = false

  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (inQuotes) {
      if (c === '"') {
        if (s[i + 1] === '"') { field += '"'; i++ } else { inQuotes = false }
      } else {
        field += c
      }
    } else if (c === '"') {
      inQuotes = true
    } else if (c === delimiter) {
      row.push(field); field = ''
    } else if (c === '\n') {
      row.push(field); rows.push(row); row = []; field = ''
    } else {
      field += c
    }
  }
  // 末行（无换行结尾时也要收尾；避免纯末尾换行产生多余空行）
  if (field !== '' || row.length > 0) { row.push(field); rows.push(row) }
  return rows
}

/** 表头归一化：小写、去空白与常见标点，便于别名比对 */
function normHeader(s: string): string {
  return (s || '').trim().toLowerCase().replace(/[\s_\-:：.．、()（）/]/g, '')
}

/** 单个单元格是否命中某字段的别名 */
function cellMatchesField(cell: string, def: ImportFieldDef): boolean {
  const h = normHeader(cell)
  if (!h) return false
  return def.aliases.some((a) => {
    const na = normHeader(a)
    return na !== '' && (h === na || h.includes(na))
  })
}

/** 首行是否像表头：至少命中 2 个字段别名（降低把数据行误判为表头的概率） */
export function looksLikeHeader(row: string[]): boolean {
  let hits = 0
  for (const def of IMPORT_FIELDS) {
    if (row.some((c) => cellMatchesField(c, def))) hits++
  }
  return hits >= 2
}

/**
 * 从二维网格构建 {@link ParsedTable}（自动过滤整行空白、识别表头）。
 *
 * @param grid 已解析的字符串网格（CSV/TSV/xlsx 均可）
 */
export function detectTable(grid: string[][]): ParsedTable {
  const rows = grid
    .map((r) => (Array.isArray(r) ? r.map((c) => (c == null ? '' : String(c))) : []))
    .filter((r) => r.some((c) => c.trim() !== ''))
  if (rows.length === 0) return { headers: [], rows: [], hasHeader: false }

  const maxCols = rows.reduce((m, r) => Math.max(m, r.length), 0)
  const hasHeader = looksLikeHeader(rows[0])
  const headers = Array.from({ length: maxCols }, (_, i) =>
    hasHeader ? (rows[0][i] ?? '').trim() || `第 ${i + 1} 列` : `第 ${i + 1} 列`,
  )
  return { headers, rows, hasHeader }
}

/** 解析 CSV/TSV 文本为 {@link ParsedTable} */
export function parseTextTable(text: string, filename: string): ParsedTable {
  return detectTable(parseDelimited(text, detectDelimiter(filename, text)))
}

/** 自动列映射：按字段顺序，取第一个命中别名的列 */
export function autoMapColumns(headers: string[]): ColumnMapping {
  const mapping: ColumnMapping = {}
  for (const def of IMPORT_FIELDS) {
    for (let i = 0; i < headers.length; i++) {
      if (cellMatchesField(headers[i], def)) { mapping[def.field] = i; break }
    }
  }
  return mapping
}

export interface BuildRowsResult {
  /** 归一化行（含缺少必填字段的行，交由后端 dryRun 归类为 ERROR） */
  rows: ImportRow[]
  /** 被跳过的纯空白行数 */
  blankSkipped: number
  /** 是否因超过 {@link MAX_IMPORT_ROWS} 而被截断 */
  truncated: boolean
}

/**
 * 依列映射把表格归一化成导入行。
 *
 * <p>**不做本地剔除**：缺少公司/岗位的行照样产出，让后端 dryRun 做权威的
 * 「新增 / 合并 / 跳过 / 致命」四类分类（前端不重复一套判定，避免口径漂移）。
 * 单次超过 {@link MAX_IMPORT_ROWS} 行时按上限截断并置 {@link BuildRowsResult.truncated}。
 */
export function buildImportRows(table: ParsedTable, mapping: ColumnMapping): BuildRowsResult {
  const dataRows = table.hasHeader ? table.rows.slice(1) : table.rows
  const truncated = dataRows.length > MAX_IMPORT_ROWS
  const limited = dataRows.slice(0, MAX_IMPORT_ROWS)

  const rows: ImportRow[] = []
  let blankSkipped = 0

  for (const raw of limited) {
    const picked: Partial<Record<ImportField, string>> = {}
    let any = false
    for (const def of IMPORT_FIELDS) {
      const idx = mapping[def.field]
      const v = idx === undefined || idx < 0 || idx >= raw.length ? '' : (raw[idx] ?? '').trim()
      if (v !== '') { picked[def.field] = v; any = true }
    }
    if (!any) { blankSkipped++; continue }

    const row: ImportRow = { companyName: picked.companyName ?? '', title: picked.title ?? '' }
    if (picked.location) row.location = picked.location
    if (picked.salary) row.salary = picked.salary
    if (picked.deadline) row.deadline = picked.deadline
    if (picked.applyUrl) row.applyUrl = picked.applyUrl
    if (picked.status) row.status = picked.status
    if (picked.note) row.note = picked.note
    rows.push(row)
  }

  return { rows, blankSkipped, truncated }
}
