import { describe, it, expect } from 'vitest'
import { asArray, asObject, asFiniteNumber } from './safeData'

describe('接口形状兜底（v1.76.1）', () => {
  it('asArray 只放行真数组', () => {
    expect(asArray([1, 2])).toEqual([1, 2])
    // 这三种是线上真实出现过的形态：空数组被 `||` 当成"没数据"放过、对象、null
    expect(asArray(null)).toEqual([])
    expect(asArray(undefined)).toEqual([])
    expect(asArray({ items: [] })).toEqual([])
    expect(asArray('abc')).toEqual([])
  })

  it('asObject 拒绝数组，回落到默认值', () => {
    const d = { a: 1, b: [1] }
    expect(asObject([], d)).toEqual(d)
    // 关键：空数组是 truthy，`res || d` 会把它放过 —— 这里必须拦住
    expect(asObject([], d)).not.toBe(d) // 返回的是拷贝，调用方可安全改写
    expect(asObject(null, d)).toEqual(d)
    expect(asObject('x', d)).toEqual(d)
  })

  it('asObject 对普通对象做浅合并，缺字段用默认值补齐', () => {
    const d = { a: 1, b: [1], c: 'x' }
    expect(asObject({ a: 9 }, d)).toEqual({ a: 9, b: [1], c: 'x' })
  })

  it('asFiniteNumber 拦住 NaN / Infinity / 非数字', () => {
    expect(asFiniteNumber(3)).toBe(3)
    expect(asFiniteNumber('12')).toBe(12)
    expect(asFiniteNumber(NaN, 7)).toBe(7)
    expect(asFiniteNumber(Infinity, 7)).toBe(7)
    expect(asFiniteNumber('abc', 7)).toBe(7)
  })
})
