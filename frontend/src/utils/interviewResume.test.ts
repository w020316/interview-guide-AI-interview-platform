import { describe, it, expect } from 'vitest'
import { isResumableSession, isAnswered, firstUnansweredIndex } from './interviewResume'

describe('isResumableSession（N1 断点续面）', () => {
  it('后端实际的「进行中」状态可继续', () => {
    expect(isResumableSession('ONGOING')).toBe(true)
  })

  it('兼容别名状态也可继续', () => {
    expect(isResumableSession('IN_PROGRESS')).toBe(true)
    expect(isResumableSession('ACTIVE')).toBe(true)
  })

  it('已完成 / 已取消不可继续', () => {
    expect(isResumableSession('FINISHED')).toBe(false)
    expect(isResumableSession('CANCELLED')).toBe(false)
  })

  it('空值不可继续（避免给未知状态渲染出「继续作答」）', () => {
    expect(isResumableSession('')).toBe(false)
    expect(isResumableSession(null)).toBe(false)
    expect(isResumableSession(undefined)).toBe(false)
  })
})

describe('isAnswered：判空不判 0', () => {
  it('有分数（含 0 分）即视为已作答', () => {
    expect(isAnswered({ evaluationScore: 88 })).toBe(true)
    // ⚠️ 关键边界：0 分是有效得分，不能当成「未作答」
    expect(isAnswered({ evaluationScore: 0 })).toBe(true)
  })

  it('null / undefined / 缺字段视为未作答', () => {
    expect(isAnswered({ evaluationScore: null })).toBe(false)
    expect(isAnswered({ evaluationScore: undefined })).toBe(false)
    expect(isAnswered({})).toBe(false)
  })
})

describe('firstUnansweredIndex：断点定位', () => {
  it('空数组回退到 0（不越界）', () => {
    expect(firstUnansweredIndex([])).toBe(0)
    // 非数组兜底（接口异常时不应崩）
    expect(firstUnansweredIndex(undefined as unknown as [])).toBe(0)
  })

  it('全部未作答 → 0（与旧行为一致）', () => {
    expect(firstUnansweredIndex([{}, {}, {}])).toBe(0)
  })

  it('部分作答 → 第一道未作答的下标', () => {
    expect(firstUnansweredIndex([{ evaluationScore: 80 }, { evaluationScore: 75 }, {}, {}])).toBe(2)
  })

  it('跳题场景：第 1 题跳过、第 2 题已答 → 断点应在第 1 题', () => {
    // 这是「用已作答数量」会算错的情形：数量为 1，但断点是下标 0
    expect(firstUnansweredIndex([{}, { evaluationScore: 90 }])).toBe(0)
  })

  it('全部已作答 → 0（没有断点，回到开头且不越界）', () => {
    expect(firstUnansweredIndex([{ evaluationScore: 80 }, { evaluationScore: 0 }])).toBe(0)
  })

  it('0 分算已作答，不会把断点误判回该题', () => {
    expect(firstUnansweredIndex([{ evaluationScore: 0 }, {}])).toBe(1)
  })
})
