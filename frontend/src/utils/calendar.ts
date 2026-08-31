/**
 * 面试日历工具函数（纯函数，便于单元测试）
 * - 月历矩阵生成 / 日期比较 / 状态映射 / 时间格式化
 */

export interface CalendarDay {
  /** 公历日期：yyyy-MM-dd */
  date: string
  /** 是否为当前展示月份内的日期 */
  inMonth: boolean
  /** 是否今天 */
  isToday: boolean
}

/**
 * 生成某年某月的日历矩阵（6 行 × 7 列，周日开头）
 * 不足 6 行时补齐，保证网格稳定
 */
export function monthMatrix(year: number, month: number): CalendarDay[] {
  // month 为 0-11
  const first = new Date(year, month, 1)
  const today = new Date()
  const todayStr = toDateString(today)

  // first.getDay(): 0=周日...6=周六
  const startWeekday = first.getDay()

  const cells: CalendarDay[] = []
  // 本月第 1 天对应格子的起始日（可能跨到上月）
  const start = new Date(year, month, 1 - startWeekday)
  for (let i = 0; i < 42; i++) {
    const d = new Date(start)
    d.setDate(start.getDate() + i)
    cells.push({
      date: toDateString(d),
      inMonth: d.getMonth() === month && d.getFullYear() === year,
      isToday: toDateString(d) === todayStr,
    })
  }
  return cells
}

/** 返回 yyyy-MM-dd（本地时区，不使用 toISOString 避免 UTC 偏移） */
export function toDateString(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 判断两个日期字符串是否同一天 */
export function isSameDate(a: string, b: string): boolean {
  return a === b
}

/** 解析 yyyy-MM-dd 为 Date（本地 0 点） */
export function parseDate(s: string): Date {
  const [y, m, d] = s.split('-').map(Number)
  return new Date(y, m - 1, d)
}

/** 用于 <input type="datetime-local"> 的默认值：yyyy-MM-ddTHH:mm */
export function toDatetimeLocal(d: Date): string {
  const date = toDateString(d)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${date}T${hh}:${mm}`
}

/** 友好化时间展示：MM-DD HH:mm 或 今天 HH:mm */
export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const today = toDateString(new Date())
  const datePart = toDateString(d) === today ? '今天' : `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${datePart} ${hh}:${mm}`
}

/** 相对时间差（天）：今天=0，未来为正，过去为负 */
export function dayDiff(dateStr: string): number {
  const today0 = new Date()
  today0.setHours(0, 0, 0, 0)
  const target = parseDate(dateStr)
  return Math.round((target.getTime() - today0.getTime()) / 86400000)
}

export type EventStatus = 'UPCOMING' | 'DONE' | 'CANCELLED'

/** 状态展示文案 */
export function statusText(s: string | null | undefined): string {
  if (s === 'DONE') return '已完成'
  if (s === 'CANCELLED') return '已取消'
  return '待面试'
}

/** 状态对应标签变体 */
export function statusVariant(s: string | null | undefined): 'default' | 'success' | 'danger' | 'warning' {
  if (s === 'DONE') return 'success'
  if (s === 'CANCELLED') return 'danger'
  return 'warning'
}

/** 周末列指示 */
export const WEEKDAY_LABELS = ['日', '一', '二', '三', '四', '五', '六']