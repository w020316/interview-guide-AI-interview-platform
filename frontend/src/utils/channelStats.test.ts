import { describe, expect, it } from 'vitest'
import { EMPTY } from './format'
import { formatRate } from './channelStats'

/**
 * 单测目的：锁死「比率不可定义（null）必须显示占位符，而不是 0%」这条纪律，
 * 以及「0 是合法值，必须显示 0%」——两者不能混。
 */
describe('formatRate', () => {
  it('null / undefined / 非有限数 → 占位符（不显示 0%）', () => {
    expect(formatRate(null)).toBe(EMPTY)
    expect(formatRate(undefined)).toBe(EMPTY)
    expect(formatRate(Number.NaN)).toBe(EMPTY)
    expect(formatRate(Number.POSITIVE_INFINITY)).toBe(EMPTY)
    expect(formatRate(null)).not.toBe('0%')
  })

  it('0 是合法值 → 显示 0%（与 null 明确区分）', () => {
    expect(formatRate(0)).toBe('0%')
  })

  it('正常比率按百分数取整', () => {
    expect(formatRate(1)).toBe('100%')
    expect(formatRate(0.333)).toBe('33%')
    expect(formatRate(0.5)).toBe('50%')
    expect(formatRate(0.005)).toBe('1%')
  })
})
