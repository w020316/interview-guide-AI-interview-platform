/**
 * 面试题生成感知进度曲线（v1.23.2 优化①）
 *
 * AI 生成题目是一次 2-3 分钟的阻塞请求，无法拿到真实进度；
 * 采用渐近曲线让进度条平滑逼近上限后驻留，避免提前到 100 造成"假完成"。
 */
export const GEN_PROGRESS_CAP = 95
export const GEN_PROGRESS_RATE = 0.035

/** 由当前进度计算下一秒进度：单调递增且永不超过上限 */
export function nextGenProgress(current: number): number {
  return Math.min(GEN_PROGRESS_CAP, current + (GEN_PROGRESS_CAP - current) * GEN_PROGRESS_RATE)
}
