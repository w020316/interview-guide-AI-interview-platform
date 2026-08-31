import { describe, it, expect } from 'vitest'
import { compareWithHistory, suggestNextTarget } from './reportCompare'

describe('compareWithHistory', () => {
  it('无历史成绩时返回 unknown 与引导提示', () => {
    const r = compareWithHistory(70, [])
    expect(r.status).toBe('unknown')
    expect(r.delta).toBeNull()
    expect(r.historyAvg).toBeNull()
    expect(r.hint).toContain('暂无历史成绩')
  })

  it('领先超过阈值判定为 improved', () => {
    const r = compareWithHistory(80, [70, 75]) // 历史均分 72.5 → delta +8
    expect(r.status).toBe('improved')
    expect(r.delta).toBe(8)
    expect(r.historyAvg).toBe(73)
    expect(r.hint).toContain('领先 8 分')
  })

  it('落后超过阈值判定为 declined 并提示回错题本', () => {
    const r = compareWithHistory(60, [75, 80]) // 历史均分 77.5 → delta -17.5，Math.round(-17.5)=-17
    expect(r.status).toBe('declined')
    expect(r.delta).toBe(-17)
    expect(r.hint).toContain('落后 17 分')
    expect(r.hint).toContain('错题本')
  })

  it('差值在阈值内判定为 steady', () => {
    const r = compareWithHistory(72, [70, 74]) // 历史均分 72 → delta 0
    expect(r.status).toBe('steady')
    expect(r.delta).toBe(0)
    expect(r.hint).toContain('基本持平')
  })

  it('自定义阈值生效', () => {
    // 阈值 10 时，+8 判定为 steady
    const r1 = compareWithHistory(80, [70, 75], 10)
    expect(r1.status).toBe('steady')
    // 阈值 1 时，+8 判定为 improved
    const r2 = compareWithHistory(80, [70, 75], 1)
    expect(r2.status).toBe('improved')
  })

  it('历史均分小数时正确取整展示', () => {
    const r = compareWithHistory(100, [99, 98]) // 均分 98.5 → 99
    expect(r.historyAvg).toBe(99)
  })
})

describe('suggestNextTarget', () => {
  it('在基础分上加 5', () => {
    expect(suggestNextTarget(70)).toBe(75)
  })
  it('小数向上取整', () => {
    expect(suggestNextTarget(72.4)).toBe(77)
  })
  it('上限 100', () => {
    expect(suggestNextTarget(98)).toBe(100)
    expect(suggestNextTarget(100)).toBe(100)
  })
})