/**
 * 日期格式化工具
 *
 * 统一各页面重复的 fmtDate / formatDate 实现。
 * 原 ResumeHistoryView / HistoryView 用 fmtDate，ResumeView / JobAnalysisView 用 formatDate，
 * 两者实现等价，本文件统一为 formatDate。
 */

/**
 * 将时间戳/日期字符串格式化为 "YYYY-MM-DD HH:mm"。
 *
 * @param input 时间戳（毫秒）或日期字符串，null/空串返回 "—"
 * @returns 格式化后的日期字符串
 */
export function formatDate(input: number | string | null | undefined): string {
  if (!input) return '—'
  const d = new Date(input)
  if (isNaN(d.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 仅日期部分 "YYYY-MM-DD"。
 */
export function formatDateOnly(input: number | string | null | undefined): string {
  if (!input) return '—'
  const d = new Date(input)
  if (isNaN(d.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
