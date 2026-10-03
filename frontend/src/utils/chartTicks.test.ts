import { describe, it, expect } from 'vitest'
import { pickTickIndices } from './chartTicks'

describe('pickTickIndices', () => {
  it('空/非法输入返回空数组', () => {
    expect(pickTickIndices(0, 6)).toEqual([])
    expect(pickTickIndices(-1, 6)).toEqual([])
    expect(pickTickIndices(NaN, 6)).toEqual([])
  })

  it('单点返回 [0]', () => {
    expect(pickTickIndices(1, 6)).toEqual([0])
  })

  it('点数不超过上限时全部显示', () => {
    expect(pickTickIndices(4, 6)).toEqual([0, 1, 2, 3])
    expect(pickTickIndices(6, 6)).toEqual([0, 1, 2, 3, 4, 5])
  })

  it('抽稀时始终包含首尾，且下标升序去重', () => {
    const idx = pickTickIndices(20, 6)
    expect(idx.length).toBeLessThanOrEqual(6)
    expect(idx[0]).toBe(0)
    expect(idx[idx.length - 1]).toBe(19)
    expect([...idx].sort((a, b) => a - b)).toEqual(idx)
    expect(new Set(idx).size).toBe(idx.length)
  })

  it('maxLabels<2 时退化为最多 2 个（首尾）', () => {
    expect(pickTickIndices(10, 1)).toEqual([0, 9])
    expect(pickTickIndices(10, 0)).toEqual([0, 9])
  })

  it('分布尽量均匀（10 点取 5 个）', () => {
    expect(pickTickIndices(10, 5)).toEqual([0, 2, 5, 7, 9])
  })
})
