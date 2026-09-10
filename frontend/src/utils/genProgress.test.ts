import { describe, it, expect } from 'vitest'
import { nextGenProgress, GEN_PROGRESS_CAP } from './genProgress'

describe('nextGenProgress（题目生成感知进度曲线）', () => {
  it('单调递增：任意中间值下一步都更大', () => {
    let p = 4
    for (let i = 0; i < 60; i++) {
      const next = nextGenProgress(p)
      expect(next).toBeGreaterThan(p)
      p = next
    }
  })

  it(`永不超过上限 ${GEN_PROGRESS_CAP}`, () => {
    let p = 4
    for (let i = 0; i < 600; i++) p = nextGenProgress(p)
    expect(p).toBeLessThanOrEqual(GEN_PROGRESS_CAP)
  })

  it('已到上限时保持不变（不会到 100 造成假完成）', () => {
    expect(nextGenProgress(GEN_PROGRESS_CAP)).toBe(GEN_PROGRESS_CAP)
  })

  it('60 秒内推进过半（曲线速度可用）', () => {
    let p = 4
    for (let i = 0; i < 60; i++) p = nextGenProgress(p)
    expect(p).toBeGreaterThan(50)
  })
})
