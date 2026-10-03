/**
 * 「无数据 ≠ 0 分」的统一渲染口径（v1.46.x UX 修复 · P0）
 *
 * 背景：知识库「题目汇总」与成长页「维度掌握度」都以分组平均分展示，
 * 但 `answered == 0` 的分组此前被渲染成「0 分」+ 危险红 + 空进度条，
 * 与同一份数据在错题本里「没有错题」的说法自相矛盾。
 *
 * 后端修复后 `answered == 0` 时 `avgScore` 返回 `null`，但前端必须**同时**
 * 兼容两种形状：`avgScore === null` 与历史脏数据的 `avgScore === 0 + answered === 0`。
 * 二者都视为「无数据」。
 */
import { EMPTY } from './format'
import { getScoreColor, getScoreGradient, DEFAULT_THRESHOLDS, type ScoreThresholds } from './score'

/** 一个分组/维度的评分样本 */
export interface ScoreSample {
  /** 已作答数 */
  answered: number
  /** 平均分；无样本时为 null（历史脏数据可能是 0） */
  avgScore: number | null | undefined
}

/** 无样本时的中性占位色（刻意不用危险红） */
export const NEUTRAL_SCORE_COLOR = 'var(--c-text-quaternary)'

/** 无样本时的进度条填充色（中性） */
export const NEUTRAL_SCORE_FILL = 'var(--c-border)'

/**
 * 是否存在有效样本：必须 `answered > 0` **且** `avgScore` 非空。
 * 二者任一不满足都视为「无数据」，不显示分数。
 */
export function hasScoreSample(sample: ScoreSample): boolean {
  return sample.answered > 0 && sample.avgScore != null
}

/** 分数文本（不带单位）：无样本返回「—」，有样本返回 `"78"` */
export function scoreText(sample: ScoreSample): string {
  return hasScoreSample(sample) ? String(sample.avgScore) : EMPTY
}

/** 分数文本（带「分」单位）：无样本返回「—」，有样本返回 `"78 分"` */
export function scoreTextWithUnit(sample: ScoreSample): string {
  return hasScoreSample(sample) ? `${sample.avgScore} 分` : EMPTY
}

/** 分数颜色：无样本返回中性占位色，有样本走评分色阶 */
export function scoreColor(
  sample: ScoreSample,
  thresholds: ScoreThresholds = DEFAULT_THRESHOLDS,
): string {
  return hasScoreSample(sample)
    ? getScoreColor(sample.avgScore as number, thresholds)
    : NEUTRAL_SCORE_COLOR
}

/** 分数进度条填充：无样本返回中性色，有样本走评分渐变 */
export function scoreFill(sample: ScoreSample): string {
  return hasScoreSample(sample)
    ? getScoreGradient(sample.avgScore as number)
    : NEUTRAL_SCORE_FILL
}
