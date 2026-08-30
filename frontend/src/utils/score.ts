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

/** 评分对应颜色（hex），null/undefined 返回中性灰 */
export function getScoreColor(score: number | null | undefined, thresholds: ScoreThresholds = DEFAULT_THRESHOLDS): string {
  if (score == null) return 'var(--c-text-tertiary)'
  if (score >= thresholds.excellent) return '#10b981'
  if (score >= thresholds.good) return '#3b82f6'
  if (score >= thresholds.pass) return '#f59e0b'
  return '#ef4444'
}

/** 评分对应渐变背景 */
export function getScoreGradient(score: number, thresholds: ScoreThresholds = DEFAULT_THRESHOLDS): string {
  if (score >= thresholds.excellent) return 'linear-gradient(90deg, #10b981, #34d399)'
  if (score >= thresholds.good) return 'linear-gradient(90deg, #3b82f6, #60a5fa)'
  if (score >= thresholds.pass) return 'linear-gradient(90deg, #f59e0b, #fbbf24)'
  return 'linear-gradient(90deg, #ef4444, #f87171)'
}

/** 评分对应浅色背景（用于徽章/标签底色） */
export function getScoreBg(score: number, thresholds: ScoreThresholds = DEFAULT_THRESHOLDS): string {
  if (score >= thresholds.excellent) return '#ecfdf5'
  if (score >= thresholds.good) return '#eff6ff'
  if (score >= thresholds.pass) return '#fffbeb'
  return '#fef2f2'
}
