import { describe, it, expect } from 'vitest'
import { buildLineChart, computeTrendStats } from './scoreTrend'
import type { TrendPoint } from './scoreTrend'

const SIZE = { width: 720, height: 260, paddingX: 40, paddingY: 24 }

function pt(score: number, seq = 1): TrendPoint {
  return { seq, date: `2026-09-0${seq}`, label: `第${seq}次`, score, questionCount: 3, jobTitle: 'PM', sessionId: `s${seq}` }
}

describe('computeTrendStats', () => {
  it('空/undefined 输入返回空统计', () => {
    expect(computeTrendStats(null)).toEqual({ count: 0, average: 0, max: 0, min: 0, latest: 0, delta: 0, scores: [] })
    expect(computeTrendStats([])).toEqual({ count: 0, average: 0, max: 0, min: 0, latest: 0, delta: 0, scores: [] })
  })

  it('过滤非法得分（NaN/Infinity）', () => {
    const bad = [
      { ...pt(60, 1), score: NaN },
      { ...pt(80, 2) },
    ] as TrendPoint[]
    const s = computeTrendStats(bad)
    expect(s.count).toBe(1)
    expect(s.average).toBe(80)
    expect(s.scores).toEqual([80])
  })

  it('计算平均/最高/最低/最新（保留 1 位小数）', () => {
    const points = [pt(70, 1), pt(80, 2), pt(90, 3)]
    const s = computeTrendStats(points)
    expect(s.count).toBe(3)
    expect(s.average).toBe(80)
    expect(s.max).toBe(90)
    expect(s.min).toBe(70)
    expect(s.latest).toBe(90)
  })

  it('delta 为最近两次差值，不足两次为 0', () => {
    expect(computeTrendStats([pt(70, 1)]).delta).toBe(0)
    expect(computeTrendStats([pt(70, 1), pt(85, 2)]).delta).toBe(15)
    expect(computeTrendStats([pt(85, 1), pt(70, 2)]).delta).toBe(-15)
  })
})

describe('buildLineChart', () => {
  it('空数据返回空点集与默认刻度', () => {
    expect(buildLineChart(null, SIZE).points).toEqual([])
    expect(buildLineChart([], SIZE).yTicks).toEqual([0, 50, 100])
  })

  it('单点居中（x = width/2）', () => {
    const r = buildLineChart([pt(80, 1)], SIZE)
    expect(r.points).toHaveLength(1)
    expect(r.points[0].x).toBe(SIZE.width / 2)
  })

  it('多点 X 均匀分布，Y 随分数单调', () => {
    const r = buildLineChart([pt(0, 1), pt(50, 2), pt(100, 3)], SIZE)
    const [a, b, c] = r.points
    // X 均匀分布
    const innerW = SIZE.width - SIZE.paddingX * 2
    expect(a.x).toBe(SIZE.paddingX)
    expect(b.x).toBeCloseTo(SIZE.paddingX + innerW / 2)
    expect(c.x).toBeCloseTo(SIZE.width - SIZE.paddingX)
    // 分数越高，Y 越小（贴近顶部）
    expect(a.y).toBeGreaterThan(b.y)
    expect(b.y).toBeGreaterThan(c.y)
    // Y 保持在有效绘图区域内
    const innerH = SIZE.height - SIZE.paddingY * 2
    for (const p of [a, b, c]) {
      expect(p.y).toBeGreaterThanOrEqual(SIZE.paddingY)
      expect(p.y).toBeLessThanOrEqual(SIZE.paddingY + innerH)
    }
  })

  it('yTicks 为升序三段刻度', () => {
    const r = buildLineChart([pt(60, 1), pt(75, 2)], SIZE)
    expect(r.yTicks.length).toBeGreaterThanOrEqual(2)
    expect([...r.yTicks].sort((x, y) => x - y)).toEqual(r.yTicks)
  })
})