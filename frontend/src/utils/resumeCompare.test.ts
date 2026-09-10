import { describe, it, expect } from 'vitest'
import { compareResume, type ResumeVersion } from './resumeCompare'

const ver = (over: Partial<ResumeVersion>): ResumeVersion => ({
  id: 1,
  overallScore: 70,
  dimensions: [
    { name: '技术匹配度', score: 70 },
    { name: '项目含金量', score: 65 },
    { name: '表述清晰度', score: 75 },
  ],
  ...over,
})

describe('compareResume 简历版本对比', () => {
  it('综合分差异为 B - A', () => {
    const r = compareResume(ver({ overallScore: 70 }), ver({ overallScore: 82, id: 2 }))
    expect(r.overallDiff).toBe(12)
  })

  it('维度按名称对齐并计算差异', () => {
    const a = ver({})
    const b = ver({
      id: 2,
      dimensions: [
        { name: '技术匹配度', score: 80 },
        { name: '项目含金量', score: 60 },
        { name: '表述清晰度', score: 75 },
      ],
    })
    const r = compareResume(a, b)
    const tech = r.rows.find((d) => d.name === '技术匹配度')!
    expect(tech.diff).toBe(10)
    expect(tech.better).toBe('b')
    const proj = r.rows.find((d) => d.name === '项目含金量')!
    expect(proj.diff).toBe(-5)
    expect(proj.better).toBe('a')
    const expr = r.rows.find((d) => d.name === '表述清晰度')!
    expect(expr.diff).toBe(0)
    expect(expr.better).toBe('tie')
  })

  it('单侧独有的维度保留且 better=none', () => {
    const a = ver({ dimensions: [{ name: '技术匹配度', score: 70 }] })
    const b = ver({ id: 2, dimensions: [{ name: '岗位匹配度', score: 80 }] })
    const r = compareResume(a, b)
    expect(r.rows).toHaveLength(2)
    const onlyA = r.rows.find((d) => d.name === '技术匹配度')!
    expect(onlyA.a).toBe(70)
    expect(onlyA.b).toBeNull()
    expect(onlyA.better).toBe('none')
  })

  it('空维度数组不抛异常', () => {
    const r = compareResume(ver({ dimensions: [] }), ver({ id: 2, dimensions: [] }))
    expect(r.rows).toHaveLength(0)
    expect(r.overallDiff).toBe(0)
  })

  it('分数差四舍五入到一位小数', () => {
    const a = ver({ dimensions: [{ name: '技术匹配度', score: 70 }] })
    const b = ver({ id: 2, dimensions: [{ name: '技术匹配度', score: 72.34 }] })
    const r = compareResume(a, b)
    expect(r.rows[0].diff).toBe(2.3)
  })
})
