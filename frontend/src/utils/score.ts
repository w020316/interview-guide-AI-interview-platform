/**
 * 评分可视化工具
 *
 * 统一各页面（ResumeView / ResumeHistoryView / InterviewView / HistoryView /
 * KnowledgeView / JobAnalysisView）重复的 scoreColor / scoreGradient 实现。
 *
 * 默认阈值：85（优秀）/ 70（良好）/ 60（及格），与多数页面原有实现一致。
 * JobAnalysisView 的匹配度场景使用 80/60/40 阈值，可通过 {@link ScoreThresholds}
 * 参数自定义。
 */

/** 评分阈值配置 */
export interface ScoreThresholds {
  /** >= 此值显示优秀色（默认 85） */
  excellent: number
  /** >= 此值显示良好色（默认 70） */
  good: number
  /** >= 此值显示及格色（默认 60） */
  pass: number
}

/** 默认阈值（简历/面试/知识库场景） */
export const DEFAULT_THRESHOLDS: ScoreThresholds = {
  excellent: 85,
  good: 70,
  pass: 60,
}

/** 匹配度场景阈值（岗位分析差距诊断） */
export const MATCH_THRESHOLDS: ScoreThresholds = {
  excellent: 80,
  good: 60,
  pass: 40,
}

/** 评分对应颜色（语义令牌），null/undefined 返回中性灰
 *
 * 返回的是 CSS 令牌（如 `var(--score-good)`），随 `[data-theme='dark']` 切换。
 * 历史上这里写死 hex（#10b981/#3b82f6/#f59e0b/#ef4444），实测深色下品牌墨绿会
 * 提亮成 rgb(20,184,166)，而蓝/红完全不变（浅深同为 rgb(59,130,246)/rgb(239,68,68)），
 * 且 77 分的蓝与相邻「已答题」墨绿并排像两套配色体系。改令牌后根治。
 */
export function getScoreColor(score: number | null | undefined, thresholds: ScoreThresholds = DEFAULT_THRESHOLDS): string {
  if (score == null) return 'var(--c-text-tertiary)'
  if (score >= thresholds.excellent) return 'var(--score-excellent)'
  if (score >= thresholds.good) return 'var(--score-good)'
  if (score >= thresholds.pass) return 'var(--score-pass)'
  return 'var(--score-fail)'
}

/** 评分对应渐变背景（令牌 + color-mix，随主题切换） */
export function getScoreGradient(score: number, thresholds: ScoreThresholds = DEFAULT_THRESHOLDS): string {
  const soft = (token: string) => `color-mix(in srgb, ${token} 82%, transparent)`
  if (score >= thresholds.excellent) return `linear-gradient(90deg, var(--score-excellent), ${soft('var(--score-excellent)')})`
  if (score >= thresholds.good) return `linear-gradient(90deg, var(--score-good), ${soft('var(--score-good)')})`
  if (score >= thresholds.pass) return `linear-gradient(90deg, var(--score-pass), ${soft('var(--score-pass)')})`
  return `linear-gradient(90deg, var(--score-fail), ${soft('var(--score-fail)')})`
}
