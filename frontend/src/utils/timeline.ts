/**
 * 投递 ↔ 面试时序视图工具函数（v1.61.0，竞品清单 #4）
 *
 * <p>纯函数、零网络，便于单测。核心立场与后端 `ApplicationTimelineService` 一致：
 * **只陈述真实存在的数据**——关联就是关联、未关联就是未关联，
 * 不从标题相似度等启发式去「猜」关系。
 */

/** 时间线节点 */
export interface TimelineNode {
  kind: 'APPLIED' | 'INTERVIEW' | 'STATUS'
  /** ISO 时间（yyyy-MM-ddTHH:mm[:ss]） */
  at: string
  applicationId?: number | null
  eventId?: number | null
  title?: string | null
  companyName?: string | null
  stage?: string | null
  stageLabel?: string | null
  channel?: string | null
  applyUrl?: string | null
  location?: string | null
  interviewer?: string | null
  note?: string | null
  eventStatus?: string | null
  /** 投出到面试的天数；**未关联投递时为 null**（不猜） */
  daysSinceApplied?: number | null
}

/** 时序统计 */
export interface TimelineStats {
  appliedCount: number
  interviewCount: number
  linkedInterviewCount: number
  unlinkedInterviewCount: number
  interviewSampleSize: number
  /** 无样本时后端返回 null——前端不得渲染成 0 */
  avgDaysToInterview: number | null
  medianDaysToInterview: number | null
}

export interface TimelineData {
  nodes: TimelineNode[]
  stats: TimelineStats
}

/** 节点类型展示文案 */
export function kindLabel(kind: string): string {
  if (kind === 'APPLIED') return '投递'
  if (kind === 'INTERVIEW') return '面试'
  return '状态变更'
}

/**
 * 天数展示：null → 占位符（**不是 0**）。
 *
 * <p>「未关联投递」与「当天投递当天面试（0.3 天）」是两件完全不同的事，
 * 前者没有数据、后者是有数据。二者必须显示得不一样——这正是本函数存在的理由。
 */
export function daysText(days: number | null | undefined): string {
  if (days == null) return '—'
  if (days === 0) return '同一天'
  if (days < 1) return `${Math.round(days * 24)} 小时`
  return `${days} 天`
}

/** 时间展示：MM-DD HH:mm（跨年补年份） */
export function timelineAtText(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  const sameYear = d.getFullYear() === now.getFullYear()
  return sameYear ? `${mm}-${dd} ${hh}:${mi}` : `${d.getFullYear()}-${mm}-${dd} ${hh}:${mi}`
}

/**
 * 面试事件状态 → 中文（与 `utils/calendar.ts` 的 statusText 同口径）。
 * 后端字段是 `eventStatus`（UPCOMING/DONE/CANCELLED），此处独立实现以避免跨模块耦合。
 */
export function eventStatusText(s: string | null | undefined): string {
  if (s === 'DONE') return '已完成'
  if (s === 'CANCELLED') return '已取消'
  if (s === 'UPCOMING') return '待面试'
  return '—'
}

/**
 * 统计摘要文案。
 *
 * <p><b>无样本时明确说「还没有可用于统计的记录」，绝不显示「平均 0 天」</b>——
 * 那会被读成「每次都是当天面试」，与真相（压根没有可比较的数据）完全相反。
 */
export function summaryText(stats: TimelineStats | null | undefined): string {
  if (!stats) return ''
  const n = stats.interviewSampleSize || 0
  if (n === 0) {
    return '还没有「投递 → 面试」的可比较记录。把面试日程关联到对应投递记录后，这里会显示平均间隔。'
  }
  const avg = stats.avgDaysToInterview
  const med = stats.medianDaysToInterview
  if (avg == null && med == null) {
    return `已关联 ${n} 组记录。`
  }
  const parts: string[] = []
  if (avg != null) parts.push(`平均 ${avg} 天`)
  if (med != null) parts.push(`中位 ${med} 天`)
  return `基于 ${n} 组「投递 → 面试」关联：${parts.join('，')}。`
}
