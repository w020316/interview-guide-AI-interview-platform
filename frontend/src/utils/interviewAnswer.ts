/**
 * 答题提交的纯函数（P1-06，2026-10-30 质量循环）。
 *
 * ── 要解决的缺陷 ────────────────────────────────────────────────────
 * 提交回答后要等 AI 回评（通常 3-15 秒）。原实现在 `await` **之后**继续读
 * `currentQ.value`（响应式的「当前题」）。用户在等待期间点「下一题」，
 * `currentQ` 已经指向 B 题 —— 于是 A 题的回答、评分与 `questionId`
 * 全部写到 B 题名下：历史回顾错配、A 题永远「未作答」，且是**事后无法自动修复**的
 * 静默数据污染。
 *
 * ── 修复思路 ────────────────────────────────────────────────────────
 * 在 `await` 之前 `snapshotQuestion()` 出不可变快照，后续所有请求体都由快照构造。
 * 把「请求体怎么拼」抽成纯函数，既让这个约束可单测，也避免以后有人在
 * 函数里又去碰响应式状态。
 */

/** 答题时需要的题目字段（与 InterviewView 的 Question 结构兼容） */
export interface AnsweredQuestion {
  id?: number | null
  question: string
  category?: string
  difficulty?: string
  referenceAnswer?: string | null
}

/** 快照：浅拷贝即可 —— 题目对象在会话内不会被原地修改，拷贝的目的是断开「之后又被替换引用」 */
export function snapshotQuestion<T extends AnsweredQuestion>(question: T): T {
  return { ...question }
}

/** `/api/interview/evaluate` 请求体 */
export interface EvaluateBody {
  question: string
  userAnswer: string
  referenceAnswer?: string
  imageUrl?: string
}

export function buildEvaluateBody(
  question: AnsweredQuestion,
  userAnswer: string,
  imageUrl: string | null | undefined,
): EvaluateBody {
  return {
    question: question.question,
    userAnswer,
    // P2-24：参考答案必须传给后端，否则提示词里的「逐项核对参考答案」完全落空
    referenceAnswer: question.referenceAnswer || undefined,
    imageUrl: imageUrl || undefined,
  }
}

/** `/api/session/answer` 请求体（题号必须来自快照，不能来自「当前题」） */
export interface SessionAnswerBody {
  questionId: number
  userAnswer: string
  evaluationScore: number | null
  evalDetail: string | null
}

/**
 * @returns 题目没有 id（出题入库失败）时返回 null —— 调用方据此提示「本次不会记入历史」
 */
export function buildSessionAnswerBody(
  question: AnsweredQuestion,
  userAnswer: string,
  overallScore: number | null | undefined,
  evalDetail: unknown,
): SessionAnswerBody | null {
  if (question.id == null) return null
  return {
    questionId: question.id,
    userAnswer,
    evaluationScore: typeof overallScore === 'number' ? overallScore : null,
    // 后端校验：合法 JSON 且 ≤4000 字；非法则 400。故序列化成字符串后再传。
    evalDetail: evalDetail ? JSON.stringify(evalDetail) : null,
  }
}
