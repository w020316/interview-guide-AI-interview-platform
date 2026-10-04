import { describe, it, expect } from 'vitest'
import {
  canRenderDimensions,
  buildReportView,
  buildScopeText,
  findDim,
  type ReportEvalInput,
} from './reportView'

/**
 * RK1 断言级防护：旧会话 eval_detail 为 null ⇒ 维度条数 0 且**不补 0**。
 * 这些是「无数据 ≠ 0」红线的回归锁（本项目已复发两次）。
 */
describe('reportView · canRenderDimensions（单条维度判定）', () => {
  it('null / undefined → false（旧会话，绝不当作 0 分）', () => {
    expect(canRenderDimensions(null)).toBe(false)
    expect(canRenderDimensions(undefined)).toBe(false)
  })

  it('空串 / 非法 JSON / 非对象 → false', () => {
    expect(canRenderDimensions('')).toBe(false)
    expect(canRenderDimensions('   ')).toBe(false)
    expect(canRenderDimensions('不是JSON')).toBe(false)
    expect(canRenderDimensions(42)).toBe(false)
    expect(canRenderDimensions({})).toBe(false)
  })

  it('对象无任何有效数字维度 → false（不把 undefined 当维度）', () => {
    expect(canRenderDimensions({})).toBe(false)
    expect(canRenderDimensions({ completeness: null, accuracy: undefined })).toBe(false)
    expect(canRenderDimensions({ completeness: 'x', accuracy: 'y' })).toBe(false)
  })

  it('含至少一个有效数字维度 → true（对象或 JSON 字符串均可）', () => {
    expect(canRenderDimensions({ completeness: 72 })).toBe(true)
    expect(canRenderDimensions('{"completeness":72,"accuracy":65,"expression":60}')).toBe(true)
    expect(canRenderDimensions({ expression: 0 })).toBe(true) // 显式 0 是「真实的 0」，可渲染
  })
})

describe('reportView · buildReportView（整场降级判定）', () => {
  const fullDetail = { completeness: 72, accuracy: 65, expression: 60, improvements: ['补充项目细节'] }

  it('RK1：全部 evalDetail 为 null → degraded=true 且 dims 数 === 0（不补 0）', () => {
    const evals: ReportEvalInput[] = [
      { question: 'Q1', category: 'Java', overallScore: 70, evalDetail: null },
      { question: 'Q2', category: 'Java', overallScore: 80, evalDetail: null },
    ]
    const view = buildReportView(evals)

    expect(view.degraded).toBe(true)
    expect(view.dims).toEqual([])          // 关键：不是 [{value:0}...]
    expect(view.dims.length).toBe(0)
    // 综合分仍如实展示（来自 evaluationScore），且是均分 75
    expect(view.overall).toBe(75)
    // 摘要不得出现「0 分」这类编造结论
    expect(view.summary).not.toMatch(/0 分/)
    expect(view.summary).toContain('未保存分维度明细')
    // 缺明细不参与进步/短板结论
    expect(view.summary).not.toContain('相对优势')
  })

  it('含完整 detail → 正常渲染，四维度条（综合/完整性/准确性/表达力）', () => {
    const evals: ReportEvalInput[] = [
      { question: 'Q1', category: 'Java', overallScore: 76, evalDetail: fullDetail },
      { question: 'Q2', category: 'Java', overallScore: 66, evalDetail: '{"completeness":68,"accuracy":63,"expression":58}' },
    ]
    const view = buildReportView(evals)

    expect(view.degraded).toBe(false)
    expect(view.dims.map((d) => d.name)).toEqual(['综合', '完整性', '准确性', '表达力'])
    expect(findDim(view, '综合')).toBe(71)       // round((76+66)/2)
    expect(findDim(view, '完整性')).toBe(70)     // round((72+68)/2)
    expect(findDim(view, '准确性')).toBe(64)     // round((65+63)/2)
    expect(findDim(view, '表达力')).toBe(59)     // round((60+58)/2)
    expect(view.summary).toContain('相对优势')
  })

  it('部分有明细：仅用有明细的作答算维度均分，缺明细的不被当作 0 稀释', () => {
    const evals: ReportEvalInput[] = [
      { question: 'Q1', overallScore: 90, evalDetail: { completeness: 80, accuracy: 80, expression: 80 } },
      { question: 'Q2', overallScore: 10, evalDetail: null }, // 旧题：不得拉低维度均分
    ]
    const view = buildReportView(evals)

    expect(view.degraded).toBe(false)
    expect(findDim(view, '完整性')).toBe(80) // 只对 Q1 求平均，而非 (80+0)/2=40
    expect(findDim(view, '综合')).toBe(50)   // 综合分仍来自所有已答（90+10）/2
  })

  it('空列表 → degraded=true、dims 空、overall=null、友好空态文案', () => {
    const view = buildReportView([])
    expect(view.degraded).toBe(true)
    expect(view.dims).toEqual([])
    expect(view.overall).toBeNull()
    expect(view.answeredCount).toBe(0)
    expect(view.summary).toContain('暂无')
  })

  it('改进建议聚合：按出现次数降序、去重', () => {
    const evals: ReportEvalInput[] = [
      { question: 'Q1', overallScore: 70, evalDetail: { completeness: 70, improvements: ['多举例子', '结构化'] } },
      { question: 'Q2', overallScore: 70, evalDetail: { completeness: 70, improvements: ['多举例子'] } },
    ]
    const view = buildReportView(evals)
    expect(view.improvements[0]).toEqual({ text: '多举例子', times: 2 })
  })

  it('question 归一化：category 缺失回落空串，overallScore 缺失为 null（不填 0）', () => {
    const view = buildReportView([{ question: 'Q1' }])
    expect(view.questions[0]).toEqual({ question: 'Q1', category: '', overallScore: null })
  })
})

describe('reportView · buildScopeText（P3-03 计数口径）', () => {
  it('三段口径：本场共 N 题 · 本次作答 X · 历史已答 Y', () => {
    expect(buildScopeText(5, 3, 2)).toBe('本场共 5 题 · 本次作答 3 · 历史已答 2')
  })

  it('非法/负数归零，不产生 NaN', () => {
    expect(buildScopeText(-1, Number.NaN, -5)).toBe('本场共 0 题 · 本次作答 0 · 历史已答 0')
  })
})
