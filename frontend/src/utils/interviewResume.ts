/**
 * 断点续面（N1）的纯逻辑。
 *
 * <p><b>为什么单独抽出来</b>：下面两个判断都容易写错 ——
 * 尤其是「全部已作答」与「一个都没作答」两个边界，以及跳题场景。
 * 它们决定「从第几题开始」，错了用户会以为自己的作答丢了。放纯函数里便于单测锁定。
 */

/** 会话状态里「可继续作答」的取值（后端 `InterviewSessionEntity.status` 实际只有 ONGOING / FINISHED / CANCELLED） */
const RESUMABLE_STATUSES = new Set(['ONGOING', 'IN_PROGRESS', 'ACTIVE'])

/** 该会话是否处于「可继续作答」的状态 */
export function isResumableSession(status: string | null | undefined): boolean {
  return typeof status === 'string' && RESUMABLE_STATUSES.has(status)
}

/**
 * 题目「是否已作答」的唯一判据：服务端是否给了评分（未作答为 null / undefined）。
 *
 * <p>⚠️ 必须用 `typeof === 'number'` 而不是真值判断：**得分 0 是有效值**
 * （全错也是 0 分），用 `if (q.evaluationScore)` 会把 0 当成「未作答」而回退到该题。
 */
export function isAnswered(q: { evaluationScore?: number | null }): boolean {
  return typeof q?.evaluationScore === 'number'
}

/**
 * 第一道未作答的题的下标 —— 即「断点」。
 *
 * - 全部未作答 → `0`（从头开始，与旧行为一致）
 * - 部分作答 → 第一道未作答的下标（真正的断点）
 * - 全部已作答 → `0`（没有断点了，回到开头供复看/重答，同时避免下标越界）
 *
 * <p>⚠️ 用 `findIndex` 而不是「已作答数量」：跳题场景下（第 1 题跳过、第 2 题作答）
 * 已作答数量是 1，但断点应在**第 1 题**（那道被跳过的）。
 */
export function firstUnansweredIndex(questions: Array<{ evaluationScore?: number | null }>): number {
  if (!Array.isArray(questions) || questions.length === 0) return 0
  const i = questions.findIndex((q) => !isAnswered(q))
  return i >= 0 ? i : 0
}
