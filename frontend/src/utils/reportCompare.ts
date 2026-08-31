/**
 * 复盘报告进阶：多轮成绩对比 + 目标提示
 *
 * 面试结束时，将本次综合得分与该用户的历史成绩（trend 中已完成的其它会话）
 * 做对比，给出进步/持平/退步结论与可执行的下一步提示，并推荐下一轮目标分。
 * 纯逻辑抽离便于单元测试。
 */

/** 对比结论 */
export type CompareStatus = 'improved' | 'declined' | 'steady' | 'unknown'

export interface CompareResult {
  /** 本轮与历史均分的差值（取整），无历史时为 null */
  delta: number | null
  /** 历史均分，无历史时为 null */
  historyAvg: number | null
  status: CompareStatus
  /** 面向用户的提示语（可直接展示） */
  hint: string
}

/** 视为“明显进步/退步”的分数差下限 */
const DELTA_THRESHOLD = 3

/**
 * 与历史成绩对比并生成提示语
 * @param currentOverall 本轮综合得分（0-100）
 * @param historyScores  历史各场得分（不含本轮）
 * @param threshold      判定进步/退步的差值下限
 */
export function compareWithHistory(
  currentOverall: number,
  historyScores: number[],
  threshold = DELTA_THRESHOLD,
): CompareResult {
  if (!historyScores.length) {
    return {
      delta: null,
      historyAvg: null,
      status: 'unknown',
      hint: '暂无历史成绩，完成更多场练习后可开启趋势对比。',
    }
  }
  const historyAvg = historyScores.reduce((a, b) => a + b, 0) / historyScores.length
  const delta = Math.round(currentOverall - historyAvg)

  let status: CompareStatus
  let hint: string
  if (delta >= threshold) {
    status = 'improved'
    hint = `本轮较历史均分（${Math.round(historyAvg)}）领先 ${delta} 分，进步明显，保持当前节奏。`
  } else if (delta <= -threshold) {
    status = 'declined'
    hint = `本轮较历史均分（${Math.round(historyAvg)}）落后 ${Math.abs(delta)} 分，可回到错题本回顾薄弱项，下次针对性重练。`
  } else {
    status = 'steady'
    hint = `本轮与历史均分（${Math.round(historyAvg)}）基本持平，建议通过错题重练或提高难度来突破瓶颈。`
  }
  return { delta, historyAvg: Math.round(historyAvg), status, hint }
}

/**
 * 推荐下一轮目标分：在当前基础上稳妥提升 5 分，上限 100
 */
export function suggestNextTarget(currentOverall: number): number {
  return Math.min(100, Math.round(currentOverall + 5))
}