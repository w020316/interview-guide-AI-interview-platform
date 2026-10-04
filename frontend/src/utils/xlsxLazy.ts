/**
 * Excel（.xlsx）懒加载解析（第三批 F）—— **保证首屏主包不含 read-excel-file**。
 *
 * <p>`read-excel-file` 仅在用户真正选中 `.xlsx` 文件时才通过动态 `import()` 加载，
 * 由打包器拆成独立 chunk。因此 `dist/assets/index-*.js`（主包）中不会出现该依赖；
 * 构建后可用 `grep -l "read-excel-file" dist/assets/index-*.js` 验证（应为空）。
 *
 * <p><b>合规（R1）</b>：解析在客户端本地完成，不上传文件、不联网。
 */

/** 单元格原始值类型（read-excel-file 的 `CellValue` 子集） */
export type CellValue = string | number | boolean | Date | null | undefined

/** 日期单元格格式化为 `yyyy-MM-dd`（本地时区，避免 UTC 偏移） */
export function formatDateCell(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 单元格 → 字符串（null/undefined → 空串；布尔 → TRUE/FALSE；日期 → yyyy-MM-dd） */
export function cellToString(v: CellValue): string {
  if (v === null || v === undefined) return ''
  if (v instanceof Date) return formatDateCell(v)
  if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE'
  return String(v)
}

/** 二维单元格网格 → 二维字符串网格 */
export function normalizeXlsxRows(rows: CellValue[][]): string[][] {
  return rows.map((r) => (Array.isArray(r) ? r.map(cellToString) : []))
}

/** 读取失败时的显式引导（尤其是旧版 .xls 无法解析时） */
export const XLSX_READ_ERROR_HINT =
  'Excel 读取失败：请确认文件未损坏；若为旧版 .xls，请在 Excel/WPS 中「另存为 CSV (.csv)」后再导入'

/**
 * 解析 `.xlsx` 文件为字符串网格（**动态加载** read-excel-file）。
 *
 * @throws 读取失败（文件损坏 / 非 .xlsx / 旧版 .xls）时抛出，由调用方展示
 *         {@link XLSX_READ_ERROR_HINT}
 */
export async function parseXlsxRows(file: File): Promise<string[][]> {
  const mod = await import('read-excel-file')
  const readXlsxFile = ((mod as { default?: unknown }).default ?? mod) as unknown as
    (input: File | Blob) => Promise<CellValue[][]>
  const rows = await readXlsxFile(file)
  return normalizeXlsxRows(rows)
}
