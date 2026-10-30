import { describe, expect, it } from 'vitest'
import {
  buildEvaluateBody,
  buildSessionAnswerBody,
  snapshotQuestion,
  type AnsweredQuestion,
} from './interviewAnswer'

const Q: AnsweredQuestion = {
  id: 11,
  question: '请介绍 HashMap 的扩容机制',
  category: '技术',
  difficulty: '中等',
  referenceAnswer: '负载因子 0.75…',
}

describe('interviewAnswer（P1-06 答题快照）', () => {
  it('snapshotQuestion 返回内容相同但引用不同的副本（避免后续状态替换污染已提交的题）', () => {
    const snap = snapshotQuestion(Q)
    expect(snap).toEqual(Q)
    expect(snap).not.toBe(Q)
  })

  it('评估请求体只读快照字段，并携带参考答案与附图', () => {
    expect(buildEvaluateBody(Q, '答：扩容时…', 'https://cdn/x.png')).toEqual({
      question: '请介绍 HashMap 的扩容机制',
      userAnswer: '答：扩容时…',
      referenceAnswer: '负载因子 0.75…',
      imageUrl: 'https://cdn/x.png',
    })
    // 空附图/空参考答案不传空串（后端按 undefined 走缺省分支）
    expect(buildEvaluateBody({ ...Q, referenceAnswer: '' }, '答', '')).toEqual({
      question: Q.question,
      userAnswer: '答',
      referenceAnswer: undefined,
      imageUrl: undefined,
    })
  })

  it('持久化请求体使用快照中的 questionId，与「当前题」无关', () => {
    const snap = snapshotQuestion(Q)
    // 模拟用户切题：原对象引用被替换成下一题
    const next: AnsweredQuestion = { ...Q, id: 12, question: '请介绍索引失效场景' }
    expect(buildSessionAnswerBody(snap, '答：扩容时…', 82, { completeness: 80 })?.questionId).toBe(11)
    expect(buildSessionAnswerBody(next, '答：扩容时…', 82, null)?.questionId).toBe(12)
  })

  it('题目没有 id 时返回 null（调用方提示「不会记入历史」，而不是静默丢弃）', () => {
    expect(buildSessionAnswerBody({ ...Q, id: null }, '答', 80, null)).toBeNull()
    expect(buildSessionAnswerBody({ ...Q, id: undefined }, '答', 80, null)).toBeNull()
  })

  it('评分缺失写 null 而不是 0（无数据 ≠ 0）', () => {
    expect(buildSessionAnswerBody(Q, '答', undefined, null)?.evaluationScore).toBeNull()
    expect(buildSessionAnswerBody(Q, '答', 0, null)?.evaluationScore).toBe(0)
  })

  it('维度明细序列化为 JSON 字符串，空明细写 null', () => {
    expect(buildSessionAnswerBody(Q, '答', 80, { accuracy: 90 })?.evalDetail).toBe('{"accuracy":90}')
    expect(buildSessionAnswerBody(Q, '答', 80, null)?.evalDetail).toBeNull()
  })
})
